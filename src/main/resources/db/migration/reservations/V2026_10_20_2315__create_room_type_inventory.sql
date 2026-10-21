-- Inventário por tipo de UH e data (M4 E05; RF-TAR-08, RF-TAR-09, RN-07, Q-09, ARCHITECTURE §11). Uma linha por tipo de
-- UH (id opaco do rooms, sem FK entre schemas) e data, mantida no horizonte de 730 dias a partir da data operacional
-- (H3) pelo InventoryHorizonJob e pelos listeners do cadastro de UH e do OOO:
--   total             UHs ativas do tipo (RoomsApi.activeRooms), refeito pelos listeners, pelo job e pela recontagem;
--   sold              unidades vendidas a reservas (E07), só mudadas com a linha travada na transação da reserva;
--   blocked           unidades separadas a bloqueios de grupo (E11), idem;
--   out_of_order      UHs ativas do tipo em OOO na data (só OUT_OF_ORDER; o OOS continua vendável), refeito a partir
--                     da RoomsApi.outOfOrderPeriods pelo listener de OutOfOrderChanged (H10), nunca por delta;
--   overbooking_limit quantas unidades além do disponível se aceitam na data (RF-TAR-09); nasce zero (Q-09: overbooking
--                     proibido) e só muda pelo endpoint com reservations.inventory.manage.
-- Disponível = total - sold - blocked - out_of_order + overbooking_limit; pode ficar negativo (UH desativada ou OOO
-- aberto com vendas já feitas), e então a data aparece como sobrevendida. Toda escrita de sold e blocked trava as
-- linhas das datas com SELECT ... FOR UPDATE em ordem de (room_type_id, stay_date).
CREATE TABLE reservations.room_type_inventory (
    room_type_id      uuid        NOT NULL,
    stay_date         date        NOT NULL,
    total             integer     NOT NULL DEFAULT 0,
    sold              integer     NOT NULL DEFAULT 0,
    blocked           integer     NOT NULL DEFAULT 0,
    out_of_order      integer     NOT NULL DEFAULT 0,
    overbooking_limit integer     NOT NULL DEFAULT 0,
    updated_at        timestamptz NOT NULL,
    CONSTRAINT room_type_inventory_pk PRIMARY KEY (room_type_id, stay_date),
    CONSTRAINT room_type_inventory_counts_ck
        CHECK (total >= 0 AND sold >= 0 AND blocked >= 0 AND out_of_order >= 0 AND out_of_order <= total),
    CONSTRAINT room_type_inventory_overbooking_ck CHECK (overbooking_limit BETWEEN 0 AND 50)
);

-- O horizonte e a recontagem percorrem as datas de todos os tipos.
CREATE INDEX room_type_inventory_date_idx ON reservations.room_type_inventory (stay_date);
