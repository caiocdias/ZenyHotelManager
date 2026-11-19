-- Privilégios do papel da aplicação nos danos (ARCHITECTURE §11): leitura, inclusão e alteração (a revisão, a cobrança
-- e a dispensa), sem DELETE (o dano nunca é apagado; o trigger housekeeping.refuse_delete também recusa) e sem DDL. O
-- placeholder app_role é o papel da aplicação (ZENY_DB_USERNAME).
GRANT SELECT, INSERT, UPDATE ON housekeeping.damage_report TO "${app_role}";
