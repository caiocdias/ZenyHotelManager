-- No-show na FNRH (M6 E14, D-E14-2, D-E14-4, D-E14-5 e D-E14-6): a fila de submissões passa a aceitar a operação
-- NO_SHOW e a falha REMOTE_NO_SHOW, e a reserva remota ganha o estado no-show. A remota é cancelada ou no-show, nunca as
-- duas: o passo do no-show não chama numa cancelada e o do cancelamento não chama numa no-show.
--
-- Contrato da view fnrh.report_submissions (V2026_11_10_1100, que não se edita): a definição da view não muda, mas a
-- coluna operation passa a incluir NO_SHOW e a coluna last_failure passa a incluir REMOTE_NO_SHOW.

ALTER TABLE fnrh.submission DROP CONSTRAINT submission_operation_ck;
ALTER TABLE fnrh.submission ADD CONSTRAINT submission_operation_ck CHECK (operation IN
    ('REGISTER_RESERVATION', 'UPDATE_RESERVATION', 'CANCEL_RESERVATION', 'CHECK_IN', 'UNDO_CHECK_IN', 'CHECK_OUT',
     'NO_SHOW'));

ALTER TABLE fnrh.submission DROP CONSTRAINT submission_last_failure_ck;
ALTER TABLE fnrh.submission ADD CONSTRAINT submission_last_failure_ck CHECK (last_failure IS NULL OR last_failure IN
    ('VALIDATION', 'UNAUTHORIZED', 'FORBIDDEN', 'NOT_FOUND', 'SERVER_ERROR', 'UNAVAILABLE', 'UNKNOWN_OUTCOME',
     'INTERNAL_ERROR', 'REMOTE_CANCELLED', 'NO_REMOTE_OPERATION', 'REMOTE_NO_SHOW'));

ALTER TABLE fnrh.remote_reservation ADD COLUMN no_show boolean NOT NULL DEFAULT false;
ALTER TABLE fnrh.remote_reservation
    ADD CONSTRAINT remote_reservation_closed_ck CHECK (NOT (cancelled AND no_show));
