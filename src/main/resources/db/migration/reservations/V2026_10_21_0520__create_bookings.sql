-- Reservas agrupadoras (bookings) e reservas (M4 E07; RF-RES-01 a 03 e 14, RN-14, RN-15, RF-CTA-01, SPEC §8.2.6).
-- Ids de outros módulos (hóspede, tipo de UH, plano, políticas, conta, usuários) são opacos: sem FK entre schemas.
--
-- booking: a compra (RF-RES-03), com o titular (hóspede do guests), a origem, o canal derivado dela e o id externo
-- opcional (a OTA no M8). Uma ou mais reservas, uma por UH, sob o mesmo titular e canal.
-- reservation: uma UH (um tipo) num período, com a ocupação, o plano, o segmento, os pedidos especiais (texto livre do
-- operador: nunca vai à trilha, a eventos nem a log), a situação (SPEC §8.4; PROVISIONAL com a validade da pré-reserva,
-- H15), os termos copiados das políticas de cancelamento e de garantia vigentes na criação (RN-15), os totais do preço
-- congelado e a conta aberta no billing (RF-CTA-01). Day use (H5): chegada = saída, sem noite no inventário.
-- reservation_guest: os hóspedes da estadia (StayGuest), exatamente um principal; reapontados pelo listener de
-- GuestsMerged (o absorvido vira o sobrevivente).
-- nightly_rate: o preço congelado de cada noite (RN-14; no day use, uma linha na própria data): a diária calculada
-- pela RatesApi.priceFor, a diária gravada (editável; overridden quando difere, com o desconto em percentual quando é
-- menor), os pacotes inclusos (parte da diária) e os adicionais, e o total da noite (diária + adicionais).

CREATE SEQUENCE reservations.booking_number_seq;
CREATE SEQUENCE reservations.reservation_number_seq;

CREATE TABLE reservations.booking (
    id              uuid        NOT NULL,
    code            varchar(30) NOT NULL,
    holder_guest_id uuid        NOT NULL,
    origin          varchar(20) NOT NULL,
    channel         varchar(20) NOT NULL,
    external_id     varchar(60),
    version         bigint      NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL,
    created_by      uuid,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT booking_pk PRIMARY KEY (id),
    CONSTRAINT booking_code_uk UNIQUE (code),
    CONSTRAINT booking_channel_ck CHECK (channel IN ('DIRECT', 'WEBSITE', 'OTA')),
    CONSTRAINT booking_external_id_ck CHECK (external_id IS NULL OR char_length(external_id) BETWEEN 1 AND 60)
);

CREATE INDEX booking_holder_idx ON reservations.booking (holder_guest_id);
CREATE INDEX booking_external_idx ON reservations.booking (external_id) WHERE external_id IS NOT NULL;

CREATE TABLE reservations.reservation (
    id                            uuid          NOT NULL,
    code                          varchar(30)   NOT NULL,
    booking_id                    uuid          NOT NULL,
    status                        varchar(20)   NOT NULL,
    provisional_until             timestamptz,
    room_type_id                  uuid          NOT NULL,
    rate_plan_id                  uuid          NOT NULL,
    arrival                       date          NOT NULL,
    departure                     date          NOT NULL,
    day_use                       boolean GENERATED ALWAYS AS (arrival = departure) STORED,
    adults                        integer       NOT NULL,
    child_ages                    integer[]     NOT NULL DEFAULT '{}',
    segment                       varchar(20)   NOT NULL,
    special_requests              text,
    cancellation_policy_id        uuid          NOT NULL,
    cancellation_policy_name      varchar(120)  NOT NULL,
    cancellation_policy_version   bigint        NOT NULL,
    cancellation_deadline_hours   integer       NOT NULL,
    cancellation_penalty_type     varchar(20)   NOT NULL,
    cancellation_penalty_percent  numeric(7, 4),
    cancellation_penalty_nights   integer,
    cancellation_penalty_amount   numeric(14, 2),
    guarantee_policy_id           uuid          NOT NULL,
    guarantee_policy_name         varchar(120)  NOT NULL,
    guarantee_policy_version      bigint        NOT NULL,
    guarantee_type                varchar(20)   NOT NULL,
    guarantee_deposit_percent     numeric(7, 4),
    guarantee_due_days_before     integer,
    room_total                    numeric(14, 2) NOT NULL,
    additional_packages_total     numeric(14, 2) NOT NULL,
    total                         numeric(14, 2) NOT NULL,
    folio_id                      uuid          NOT NULL,
    approval_id                   uuid,
    approved_by                   uuid,
    version                       bigint        NOT NULL DEFAULT 0,
    created_at                    timestamptz   NOT NULL,
    created_by                    uuid,
    updated_at                    timestamptz   NOT NULL,
    CONSTRAINT reservation_pk PRIMARY KEY (id),
    CONSTRAINT reservation_code_uk UNIQUE (code),
    CONSTRAINT reservation_folio_uk UNIQUE (folio_id),
    CONSTRAINT reservation_booking_fk FOREIGN KEY (booking_id) REFERENCES reservations.booking (id),
    CONSTRAINT reservation_status_ck
        CHECK (status IN ('PROVISIONAL', 'CONFIRMED', 'IN_HOUSE', 'CHECKED_OUT', 'CANCELLED', 'NO_SHOW')),
    CONSTRAINT reservation_provisional_ck CHECK ((status = 'PROVISIONAL') = (provisional_until IS NOT NULL)),
    CONSTRAINT reservation_stay_ck CHECK (departure >= arrival AND departure - arrival <= 365),
    CONSTRAINT reservation_occupancy_ck
        CHECK (adults BETWEEN 1 AND 40 AND cardinality(child_ages) <= 20 AND 0 <= ALL (child_ages)),
    CONSTRAINT reservation_segment_ck CHECK (segment IN ('LEISURE', 'BUSINESS', 'EVENT', 'OTHER')),
    CONSTRAINT reservation_special_requests_ck
        CHECK (special_requests IS NULL OR char_length(special_requests) BETWEEN 1 AND 1000),
    CONSTRAINT reservation_penalty_ck CHECK (
        (cancellation_penalty_type = 'PERCENT_OF_STAY' AND cancellation_penalty_percent IS NOT NULL
            AND cancellation_penalty_nights IS NULL AND cancellation_penalty_amount IS NULL)
        OR (cancellation_penalty_type = 'NIGHTS' AND cancellation_penalty_nights IS NOT NULL
            AND cancellation_penalty_percent IS NULL AND cancellation_penalty_amount IS NULL)
        OR (cancellation_penalty_type = 'FIXED_AMOUNT' AND cancellation_penalty_amount IS NOT NULL
            AND cancellation_penalty_percent IS NULL AND cancellation_penalty_nights IS NULL)
    ),
    CONSTRAINT reservation_guarantee_ck CHECK (
        (guarantee_type = 'DEPOSIT') = (guarantee_deposit_percent IS NOT NULL AND guarantee_due_days_before IS NOT NULL)
        AND (guarantee_type = 'DEPOSIT' OR (guarantee_deposit_percent IS NULL AND guarantee_due_days_before IS NULL))
    ),
    CONSTRAINT reservation_totals_ck
        CHECK (room_total >= 0 AND additional_packages_total >= 0 AND total = room_total + additional_packages_total),
    CONSTRAINT reservation_approval_ck CHECK ((approval_id IS NULL) = (approved_by IS NULL))
);

CREATE INDEX reservation_booking_idx ON reservations.reservation (booking_id);
-- A recontagem do inventário e a checagem de estadia ativa leem por tipo, período e situação.
CREATE INDEX reservation_stay_idx ON reservations.reservation (room_type_id, arrival, departure);

CREATE TABLE reservations.reservation_guest (
    reservation_id uuid     NOT NULL,
    guest_id       uuid     NOT NULL,
    is_primary     boolean  NOT NULL,
    position       smallint NOT NULL,
    CONSTRAINT reservation_guest_pk PRIMARY KEY (reservation_id, guest_id),
    CONSTRAINT reservation_guest_reservation_fk
        FOREIGN KEY (reservation_id) REFERENCES reservations.reservation (id)
);

-- Exatamente um principal por reserva (o "ao menos um" fica na aplicação).
CREATE UNIQUE INDEX reservation_guest_primary_uk ON reservations.reservation_guest (reservation_id) WHERE is_primary;
CREATE INDEX reservation_guest_guest_idx ON reservations.reservation_guest (guest_id);

CREATE TABLE reservations.nightly_rate (
    reservation_id             uuid           NOT NULL,
    stay_date                  date           NOT NULL,
    rate_plan_id               uuid           NOT NULL,
    calculated_room_amount     numeric(14, 2) NOT NULL,
    room_amount                numeric(14, 2) NOT NULL,
    included_packages_amount   numeric(14, 2) NOT NULL,
    additional_packages_amount numeric(14, 2) NOT NULL,
    amount                     numeric(14, 2) NOT NULL,
    overridden                 boolean        NOT NULL,
    discount_percent           numeric(7, 4),
    packages                   jsonb          NOT NULL DEFAULT '[]',
    CONSTRAINT nightly_rate_pk PRIMARY KEY (reservation_id, stay_date),
    CONSTRAINT nightly_rate_reservation_fk FOREIGN KEY (reservation_id) REFERENCES reservations.reservation (id),
    CONSTRAINT nightly_rate_amounts_ck CHECK (
        calculated_room_amount >= 0 AND room_amount >= included_packages_amount AND included_packages_amount >= 0
        AND additional_packages_amount >= 0 AND amount = room_amount + additional_packages_amount
    ),
    CONSTRAINT nightly_rate_overridden_ck CHECK (overridden = (room_amount <> calculated_room_amount)),
    CONSTRAINT nightly_rate_discount_ck CHECK (
        (discount_percent IS NOT NULL) = (room_amount < calculated_room_amount)
        AND (discount_percent IS NULL OR discount_percent > 0 AND discount_percent <= 100)
    )
);

-- Reservas e bookings nunca são apagados (a reserva cancelada continua, SPEC §8.4).
CREATE FUNCTION reservations.refuse_delete() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'reservations: % não é apagado', TG_TABLE_NAME USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER booking_refuse_delete
    BEFORE DELETE ON reservations.booking
    FOR EACH ROW EXECUTE FUNCTION reservations.refuse_delete();

CREATE TRIGGER reservation_refuse_delete
    BEFORE DELETE ON reservations.reservation
    FOR EACH ROW EXECUTE FUNCTION reservations.refuse_delete();
