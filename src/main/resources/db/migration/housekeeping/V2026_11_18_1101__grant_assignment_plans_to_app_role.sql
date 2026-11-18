-- Privilégios do papel da aplicação no plano do dia da distribuição (ARCHITECTURE §11): no plano, leitura, inclusão e
-- alteração, sem DELETE (nunca é apagado; o trigger housekeeping.refuse_delete também recusa); nos itens, também
-- DELETE (a sugestão e o ajuste os regravam). Sem DDL. O placeholder app_role é o papel da aplicação (ZENY_DB_USERNAME).
GRANT SELECT, INSERT, UPDATE ON housekeeping.assignment_plan TO "${app_role}";
GRANT SELECT, INSERT, UPDATE, DELETE ON housekeeping.assignment_plan_item TO "${app_role}";
