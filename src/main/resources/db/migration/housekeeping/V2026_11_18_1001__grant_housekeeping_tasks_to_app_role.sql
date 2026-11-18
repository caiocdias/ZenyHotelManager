-- Privilégios do papel da aplicação nas tarefas de governança (ARCHITECTURE §11): leitura, inclusão e alteração, sem
-- DELETE (a tarefa nunca é apagada; o trigger housekeeping.refuse_delete também recusa) e sem DDL. O placeholder
-- app_role é o papel da aplicação (ZENY_DB_USERNAME).
GRANT SELECT, INSERT, UPDATE ON housekeeping.housekeeping_task TO "${app_role}";
