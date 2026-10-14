#!/bin/sh
# Cria o papel da aplicação no PostgreSQL (H4 do roadmap do M1; ARCHITECTURE §8.3 e §11).
#
# Dois papéis: o dono dos schemas é o POSTGRES_USER da imagem (ZENY_DB_MIGRATION_* no compose),
# usado só pelo Flyway; a aplicação conecta com o papel criado aqui (ZENY_DB_USERNAME e
# ZENY_DB_PASSWORD), sem DDL nem tabelas temporárias: as migrações concedem a ele só o DML de cada
# tabela e, nas tabelas append-only (a trilha de auditoria), só SELECT e INSERT.
#
# A imagem postgres roda os scripts de /docker-entrypoint-initdb.d/ só na primeira inicialização,
# com o volume de dados vazio. Num banco que já existe, crie o papel uma vez à mão (README.md) e
# reinicie a aplicação: as migrações concedem os privilégios. Os testes (Testcontainers) usam este
# mesmo script. A senha vem do ambiente e nunca é gravada no repositório nem no log.
set -eu

: "${ZENY_DB_USERNAME:?defina ZENY_DB_USERNAME (papel da aplicação)}"
: "${ZENY_DB_PASSWORD:?defina ZENY_DB_PASSWORD (senha do papel da aplicação)}"

if [ "$ZENY_DB_USERNAME" = "$POSTGRES_USER" ]; then
    echo "ZENY_DB_USERNAME não pode ser o dono dos schemas ($POSTGRES_USER): use um papel próprio para a aplicação" >&2
    exit 1
fi

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --set app_user="$ZENY_DB_USERNAME" \
    --set app_password="$ZENY_DB_PASSWORD" \
    --set db_name="$POSTGRES_DB" <<'SQL'
-- Só login: nada de superusuário, criação de papéis ou bancos, replicação nem RLS ignorada.
CREATE ROLE :"app_user" LOGIN PASSWORD :'app_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
-- Só conexão: sem CREATE (schemas) nem TEMPORARY (tabelas temporárias) no banco. O PostgreSQL dá
-- TEMPORARY a PUBLIC por padrão; aqui ele sai de PUBLIC, e o dono (superusuário) não precisa dele.
REVOKE TEMPORARY ON DATABASE :"db_name" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db_name" TO :"app_user";
-- Ninguém além do dono cria objetos no schema public (já é o padrão desde o PostgreSQL 15).
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SQL

echo "Papel da aplicação criado: $ZENY_DB_USERNAME"
