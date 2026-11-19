-- Local da execução (M6 E08, D1): a UH ou a área onde ela aconteceu, informado pelo dono do sujeito ao iniciar ou
-- derivado do sujeito rooms.room/rooms.area de uma rotina. Anuláveis (a execução avulsa do cliente fica sem local e
-- as já gravadas não são tocadas); o id vem de outro módulo e não é conferido aqui (H3).

ALTER TABLE checklists.checklist_execution
    ADD COLUMN location_room_id uuid,
    ADD COLUMN location_area_id uuid,
    ADD CONSTRAINT checklist_execution_location_ck CHECK (location_room_id IS NULL OR location_area_id IS NULL);
