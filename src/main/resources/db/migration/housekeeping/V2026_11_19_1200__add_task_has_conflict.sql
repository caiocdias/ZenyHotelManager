-- A marca de conflito offline da tarefa de governança (M6 E19, D4; RN-22): verdadeira desde o primeiro comando de uma
-- ex-dona registrado como conflito, para a governança e a nova dona verem a tarefa sinalizada. A resolução do conflito
-- fica para outra etapa.
ALTER TABLE housekeeping.housekeeping_task
    ADD COLUMN has_conflict BOOLEAN NOT NULL DEFAULT false;
