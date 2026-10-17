-- pg_trgm permite buscar por trecho (LIKE '%…%') com índice GIN de trigramas, base da busca de hóspedes
-- e empresas por nome sem acento (M3 E01, decisão do usuário em 2026-10-16): cada módulo grava o nome sem
-- acento e em minúsculas numa coluna de busca e cria o índice com public.gin_trgm_ops. Como o btree_gist,
-- a extensão vale para o banco todo e fica no schema public, visível a todos os módulos.
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;
