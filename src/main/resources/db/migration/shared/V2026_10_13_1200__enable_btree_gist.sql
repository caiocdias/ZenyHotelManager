-- btree_gist permite combinar igualdade (uuid) e sobreposição (daterange) num mesmo índice
-- GiST, base da restrição EXCLUDE que impede UH em dobro (ARCHITECTURE §11). Extensões valem
-- para o banco todo; ela fica no schema public, visível a todos os módulos.
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;
