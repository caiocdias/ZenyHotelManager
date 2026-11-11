-- View de leitura para relatórios do módulo fnrh (M5 E08, Task 12; RF-FNRH-03, ARCHITECTURE §11 "Leitura para
-- relatórios"). Faz parte da API do módulo: o fechamento do M6 lê só ela para listar as pendências do envio à FNRH,
-- nunca as tabelas internas. Uma coluna publicada não muda de nome nem de sentido; uma mudança incompatível cria outra
-- view (report_submissions_v2).
--
-- Conteúdo: uma linha por submissão (um evento da reserva enviado ao Serpro), com ids opacos (submissão, reserva),
-- rótulos fixos (operation, status, last_failure), a contagem de tentativas e instantes (timestamptz, UTC). Fica FORA:
-- o código da reserva (quem precisa dele junta com reservations.report_reservations pelo reservation_id), os erros por
-- campo da validação local (submission_error), os ids e o link de pré-check-in do Serpro (remote_reservation,
-- remote_guest), o cursor da API, a escrita em curso, o autor, a versão e todo dado de hóspede.
--
-- operation: REGISTER_RESERVATION, UPDATE_RESERVATION, CANCEL_RESERVATION, CHECK_IN, UNDO_CHECK_IN ou CHECK_OUT.
-- status: PENDING, SENT, FAILED, INVALID, CANCELLED ou DISMISSED; as pendências são PENDING, FAILED e INVALID.
-- last_failure: a causa da última falha (nula sem falha); as listas podem crescer. attempts: as tentativas feitas.
-- created_at: quando o evento virou submissão; sent_at: o envio concluído (nulo se não enviada); next_attempt_at: a
-- próxima tentativa (só vale enquanto PENDING).
CREATE VIEW fnrh.report_submissions AS
SELECT s.id              AS submission_id,
       s.reservation_id,
       s.operation,
       s.status,
       s.attempts,
       s.last_failure,
       s.created_at,
       s.sent_at,
       s.next_attempt_at
FROM fnrh.submission s;

-- O ALTER DEFAULT PRIVILEGES do schema daria INSERT, UPDATE e DELETE à view: o papel da aplicação só a lê.
GRANT SELECT ON fnrh.report_submissions TO "${app_role}";
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON fnrh.report_submissions FROM "${app_role}";
