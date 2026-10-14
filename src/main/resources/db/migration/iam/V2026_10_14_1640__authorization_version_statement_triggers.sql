-- Versão das autorizações trocada uma vez por comando (M1 E03, decisão do usuário na revisão da
-- E02). Os triggers por linha da E02 atualizavam a linha única iam.authorization_version uma vez
-- por linha alterada; por comando, um DELETE de vários limites ou uma sincronização do catálogo
-- a troca uma vez só. O efeito para o cache não muda: toda escrita (inclusive SQL manual e
-- TRUNCATE) troca a versão na mesma transação. Um comando que não altera nenhuma linha também a
-- troca, o que só faz o cache recalcular.
--
-- Contra deadlock, toda escrita de autorização da aplicação começa travando essa mesma linha
-- (SELECT ... FOR UPDATE, AuthorizationLock), então as travas são pegas sempre na mesma ordem.
--
-- iam.user_account continua com o trigger por linha da E02: ele só dispara quando o estado ativo
-- muda (WHEN), e o PostgreSQL não aceita tabelas de transição num trigger UPDATE OF coluna.

DROP TRIGGER permission_authorization_version ON iam.permission;
DROP TRIGGER role_authorization_version ON iam.role;
DROP TRIGGER role_grant_authorization_version ON iam.role_grant;
DROP TRIGGER role_grant_limit_authorization_version ON iam.role_grant_limit;
DROP TRIGGER user_role_authorization_version ON iam.user_role;

CREATE TRIGGER permission_authorization_version
    AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE
    ON iam.permission
    FOR EACH STATEMENT
EXECUTE FUNCTION iam.bump_authorization_version();

CREATE TRIGGER role_authorization_version
    AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE
    ON iam.role
    FOR EACH STATEMENT
EXECUTE FUNCTION iam.bump_authorization_version();

CREATE TRIGGER role_grant_authorization_version
    AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE
    ON iam.role_grant
    FOR EACH STATEMENT
EXECUTE FUNCTION iam.bump_authorization_version();

CREATE TRIGGER role_grant_limit_authorization_version
    AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE
    ON iam.role_grant_limit
    FOR EACH STATEMENT
EXECUTE FUNCTION iam.bump_authorization_version();

CREATE TRIGGER user_role_authorization_version
    AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE
    ON iam.user_role
    FOR EACH STATEMENT
EXECUTE FUNCTION iam.bump_authorization_version();
