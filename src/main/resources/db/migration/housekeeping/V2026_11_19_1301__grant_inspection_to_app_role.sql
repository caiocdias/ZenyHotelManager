-- Privilégios do papel da aplicação nas inspeções (ARCHITECTURE §11): leitura, inclusão e alteração (a conclusão grava
-- o resultado), sem DELETE (a inspeção nunca é apagada; o trigger housekeeping.refuse_delete também recusa) e sem DDL.
-- O placeholder app_role é o papel da aplicação (ZENY_DB_USERNAME).
GRANT SELECT, INSERT, UPDATE ON housekeeping.inspection TO "${app_role}";
