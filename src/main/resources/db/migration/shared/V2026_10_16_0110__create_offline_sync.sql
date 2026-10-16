-- Sincronização offline (ARCHITECTURE §9, D11, RNF-05; M2 E05, H6 confirmada pelo usuário em 2026-10-15).

-- Posição global das mudanças sincronizáveis. Cada tabela sincronizável de um módulo tem as colunas
-- change_seq (bigint) e change_xid (xid8), gravadas pelo trigger BEFORE INSERT OR UPDATE que o módulo cria
-- na própria migração e que executa shared.mark_offline_change(). A sequence segue a ordem de alocação,
-- não a de commit: por isso o download só serve linhas de transações já terminadas
-- (change_xid < pg_snapshot_xmin(pg_current_snapshot())), em ordem (change_xid, change_seq).
CREATE SEQUENCE shared.change_seq AS BIGINT;

-- Função genérica dos triggers das tabelas sincronizáveis. Roda com o papel de quem escreve (o da
-- aplicação recebe USAGE nas sequences de shared pelo ALTER DEFAULT PRIVILEGES da migração de grants).
CREATE FUNCTION shared.mark_offline_change() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS
$$
BEGIN
    NEW.change_seq := nextval('shared.change_seq');
    NEW.change_xid := pg_current_xact_id();
    RETURN NEW;
END;
$$;

-- Tombstones gravados pela aplicação (porta OfflineTombstones): o registro de uma fonte foi apagado
-- (left_scope_of vazio: todos que veem a fonte o removem) ou saiu do escopo OWN dos usuários listados em
-- left_scope_of (só eles o removem). Append-only para o papel da aplicação.
CREATE TABLE shared.sync_tombstone
(
    id            UUID                     NOT NULL,
    source        VARCHAR(40)              NOT NULL,
    entity_id     UUID                     NOT NULL,
    left_scope_of UUID[]                   NOT NULL,
    recorded_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    change_seq    BIGINT                   NOT NULL,
    change_xid    XID8                     NOT NULL,
    CONSTRAINT sync_tombstone_pk PRIMARY KEY (id)
);

CREATE INDEX sync_tombstone_position_idx ON shared.sync_tombstone (source, change_xid, change_seq);

CREATE TRIGGER sync_tombstone_offline_change
    BEFORE INSERT OR UPDATE
    ON shared.sync_tombstone
    FOR EACH ROW
EXECUTE FUNCTION shared.mark_offline_change();

-- Idempotência dos comandos offline por commandId (UUIDv7 do cliente), ligada ao usuário: a linha nasce
-- na transação do próprio comando (INSERT … ON CONFLICT DO NOTHING; a repetição simultânea espera o commit
-- da primeira e não aplica de novo) e guarda o resultado. Uma rejeição do handler é gravada numa
-- transação própria, depois do rollback do efeito. Quando a retenção (zeny.sync.command-retention) passa de
-- retain_from, o job de expurgo esvazia o resultado e a linha fica só com o id, o usuário e o tipo
-- (status EXPIRED: o reenvio é COMMAND_EXPIRED, qualquer que seja o relógio do aparelho); passada a retenção
-- do id (zeny.sync.command-id-retention), a linha é apagada. Um commandId cujo instante (UUIDv7) corrigido
-- pela defasagem do relógio é mais antigo que a retenção também é recusado (COMMAND_EXPIRED).
CREATE TABLE shared.offline_command
(
    command_id   UUID                     NOT NULL,
    user_id      UUID                     NOT NULL,
    type         VARCHAR(100)             NOT NULL,
    status       VARCHAR(30)              NOT NULL,
    error_code   VARCHAR(100),
    message      VARCHAR(500),
    -- Alertas do resultado ([{"code": "...", "message": "..."}]), sem dado pessoal.
    warnings     JSONB                    NOT NULL,
    -- O occurredAt do comando corrigido pela defasagem do relógio do dispositivo.
    occurred_at  TIMESTAMP WITH TIME ZONE,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Início da retenção: o mais tardio entre o processamento e o instante do commandId, cru e corrigido
    -- pela defasagem. Um id "do futuro" (relógio adiantado) fica guardado até a retenção passar também do
    -- instante dele, para nunca ser aplicado de novo depois do expurgo.
    retain_from  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT offline_command_pk PRIMARY KEY (command_id),
    CONSTRAINT offline_command_status_ck CHECK (
        status IN ('PROCESSING', 'APPLIED', 'APPLIED_WITH_WARNINGS', 'REJECTED', 'EXPIRED')),
    CONSTRAINT offline_command_rejection_ck CHECK ((status = 'REJECTED') = (error_code IS NOT NULL)),
    -- Só o id: o resultado esvaziado.
    CONSTRAINT offline_command_expired_ck CHECK (
        status <> 'EXPIRED' OR (message IS NULL AND occurred_at IS NULL AND warnings = '[]'::jsonb))
);

CREATE INDEX offline_command_retain_from_idx ON shared.offline_command (retain_from);

-- Os grants de tabela vêm do ALTER DEFAULT PRIVILEGES da migração de grants de shared (DML e USAGE na
-- sequence). Os tombstones são append-only (bean AppendOnlyTables do shared).
REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE shared.sync_tombstone FROM "${app_role}";
