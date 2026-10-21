-- Grupos e bloqueios (M4 E11; RF-RES-09, RN-16, SPEC §8.2.6 RoomBlock e BlockAllocation).
-- Ids de outros módulos (hóspede, empresa, tipo de UH, plano, conta, usuários) são opacos: sem FK entre schemas.
--
-- room_block: o bloqueio de um grupo, com o código legível (G-<ano>-…), o nome do grupo, o titular (um hóspede OU uma
-- empresa do guests), o plano tarifário (as políticas que as reservas do pick-up copiam, RN-15), a data de corte, a
-- situação (ACTIVE segura as unidades não utilizadas; RELEASED já as devolveu ao inventário pela liberação, RN-16) e a
-- conta master do grupo no billing (dono GROUP com o id do bloqueio), uma só (room_block_folio_uk). Nunca apagado.
-- block_allocation: as unidades do bloqueio por tipo de UH e noite (stay_date), com a tarifa do grupo por UH e noite
-- (congelada na reserva no pick-up, RN-14), as unidades já convertidas em reservas ativas (picked_up, mantido a partir
-- da data operacional) e as devolvidas pela liberação (released). Num bloqueio ativo, a linha segura
-- quantity - picked_up unidades no contador blocked do inventário (reservations.room_type_inventory).
-- reservation.block_id: a reserva que veio do pick-up de um bloqueio.

CREATE SEQUENCE reservations.room_block_number_seq;

CREATE TABLE reservations.room_block (
    id                uuid         NOT NULL,
    code              varchar(30)  NOT NULL,
    name              varchar(120) NOT NULL,
    holder_guest_id   uuid,
    holder_company_id uuid,
    rate_plan_id      uuid         NOT NULL,
    cutoff_date       date         NOT NULL,
    status            varchar(20)  NOT NULL,
    folio_id          uuid         NOT NULL,
    released_at       timestamptz,
    released_on       date,
    released_by       uuid,
    version           bigint       NOT NULL DEFAULT 0,
    created_at        timestamptz  NOT NULL,
    created_by        uuid,
    updated_at        timestamptz  NOT NULL,
    CONSTRAINT room_block_pk PRIMARY KEY (id),
    CONSTRAINT room_block_code_uk UNIQUE (code),
    CONSTRAINT room_block_folio_uk UNIQUE (folio_id),
    CONSTRAINT room_block_name_ck CHECK (char_length(name) BETWEEN 1 AND 120),
    CONSTRAINT room_block_holder_ck CHECK ((holder_guest_id IS NULL) <> (holder_company_id IS NULL)),
    CONSTRAINT room_block_status_ck CHECK (status IN ('ACTIVE', 'RELEASED')),
    CONSTRAINT room_block_released_ck CHECK (
        (status = 'RELEASED') = (released_at IS NOT NULL AND released_on IS NOT NULL)
        AND (status = 'RELEASED' OR released_by IS NULL)
    )
);

CREATE INDEX room_block_holder_guest_idx ON reservations.room_block (holder_guest_id)
    WHERE holder_guest_id IS NOT NULL;
-- O fechamento (M6) procura os bloqueios ativos com a data de corte vencida.
CREATE INDEX room_block_active_cutoff_idx ON reservations.room_block (cutoff_date) WHERE status = 'ACTIVE';

CREATE TABLE reservations.block_allocation (
    block_id     uuid           NOT NULL,
    room_type_id uuid           NOT NULL,
    stay_date    date           NOT NULL,
    quantity     integer        NOT NULL,
    picked_up    integer        NOT NULL DEFAULT 0,
    released     integer        NOT NULL DEFAULT 0,
    rate         numeric(14, 2) NOT NULL,
    CONSTRAINT block_allocation_pk PRIMARY KEY (block_id, room_type_id, stay_date),
    CONSTRAINT block_allocation_block_fk FOREIGN KEY (block_id) REFERENCES reservations.room_block (id),
    CONSTRAINT block_allocation_quantity_ck CHECK (quantity BETWEEN 1 AND 50),
    CONSTRAINT block_allocation_counts_ck
        CHECK (picked_up >= 0 AND released >= 0 AND picked_up + released <= quantity),
    CONSTRAINT block_allocation_rate_ck CHECK (rate >= 0 AND rate <= 1000000)
);

-- A recontagem do inventário lê as unidades bloqueadas por tipo e período.
CREATE INDEX block_allocation_stay_idx ON reservations.block_allocation (room_type_id, stay_date);

ALTER TABLE reservations.reservation
    ADD COLUMN block_id uuid,
    ADD CONSTRAINT reservation_block_fk FOREIGN KEY (block_id) REFERENCES reservations.room_block (id);

CREATE INDEX reservation_block_idx ON reservations.reservation (block_id) WHERE block_id IS NOT NULL;

-- Bloqueios nunca são apagados (as unidades que saem numa alteração somem de block_allocation; o bloqueio fica).
CREATE TRIGGER room_block_refuse_delete
    BEFORE DELETE ON reservations.room_block
    FOR EACH ROW EXECUTE FUNCTION reservations.refuse_delete();
