-- Views de leitura para relatórios do módulo reservations (M4 E12; RF-RES-11, RF-RES-13, SPEC Q-15, ARCHITECTURE §11
-- "Leitura para relatórios"). Fazem parte da API do módulo: o reporting (M6) lê só elas, nunca as tabelas internas.
-- Uma coluna publicada não muda de nome nem de sentido; uma mudança incompatível cria outra view (report_x_v2). Cada
-- linha é um fato (uma reserva, uma noite, um tipo de UH numa data, uma alocação, as unidades de um bloqueio numa
-- noite), sem agregar por período: quem consulta filtra e agrega.
--
-- Conteúdo: ids opacos (reserva, reserva agrupadora, hóspede titular e principal, tipo de UH, plano, políticas, conta,
-- bloqueio, empresa titular, UH, usuário, aprovação, lançamento da multa, período OOO), códigos legíveis (R-…, B-…, G-…),
-- situações, tipos, contagens, valores (numeric(14,2); percentual numeric(7,4)), datas de negócio (date) e instantes
-- (timestamptz, UTC). Fica FORA: todo dado de hóspede (nome, documento, contato: o reservations não guarda nenhum; os
-- hóspedes vão só pelo id, que cruza com guests.report_guests, também sem dado pessoal), todo texto livre digitado por
-- quem opera (os pedidos especiais, a observação do cancelamento, o nome do grupo do bloqueio), o número da OTA
-- (booking.external_id), os nomes das políticas copiados e o retrato do histórico.
--
-- Categoria do plano (Q-15): NORMAL, COURTESY (cortesia: conta na ocupação e não no ADR) ou HOUSE_USE (uso da casa:
-- nem na ocupação nem no ADR), copiada na reserva (reservation.rate_plan_category) na criação e na troca de plano.

-- Uma linha por reserva (SPEC §8.2.6). status: PROVISIONAL, CONFIRMED, IN_HOUSE, CHECKED_OUT, CANCELLED ou NO_SHOW (a
-- lista pode crescer). origin: FRONT_DESK, PHONE, EMAIL, WHATSAPP, WEBSITE, OTA ou OTHER; channel: DIRECT, WEBSITE ou
-- OTA (derivado da origem); segment: LEISURE, BUSINESS, EVENT ou OTHER (as listas podem crescer). holder_guest_id: o
-- titular da reserva agrupadora; primary_guest_id: o hóspede principal da estadia. arrival e departure são datas de
-- negócio (day_use: chegada = saída, sem noite no inventário); nights = departure - arrival (0 no day use). block_id: o
-- bloqueio de grupo do pick-up. room_total (as diárias congeladas), additional_packages_total e total
-- (= room_total + additional_packages_total) são do preço congelado por noite (RN-14). created_at/created_by: a
-- criação (created_by nulo = o sistema ou uma integração). discount_approval_id: a aprovação de superior de um
-- desconto nas diárias. Garantia (E09): guarantee_type é o que a política copiada pede (NONE, CARD, DEPOSIT);
-- guarantee_method, guaranteed_at, guarantee_deposit_required e guarantee_deposit_received, a última garantia
-- registrada (nula se nenhuma); provisional_until, a validade da pré-reserva. Cancelamento (E08; só na CANCELLED, o
-- corrente): cancellation_reason (GUEST_REQUEST, HOTEL_REQUEST, DUPLICATE, NO_GUARANTEE, OTHER ou EXPIRED, a pré-reserva
-- vencida que o job cancelou; pode crescer), cancellation_previous_status, cancelled_at, cancelled_by (nulo = o sistema),
-- penalty_amount (a multa devida pela política copiada), penalty_waived (isentada com aprovação) e penalty_posting_id
-- (o lançamento na conta). penalties_charged: o total das multas já lançadas em todos os cancelamentos da reserva.
CREATE VIEW reservations.report_reservations AS
SELECT r.id                                  AS reservation_id,
       r.code                                AS reservation_code,
       r.booking_id,
       b.code                                AS booking_code,
       b.holder_guest_id,
       (SELECT g.guest_id
        FROM reservations.reservation_guest g
        WHERE g.reservation_id = r.id AND g.is_primary) AS primary_guest_id,
       b.origin,
       b.channel,
       r.status,
       r.room_type_id,
       r.rate_plan_id,
       r.rate_plan_category,
       r.block_id,
       r.arrival,
       r.departure,
       r.day_use,
       r.departure - r.arrival               AS nights,
       r.adults,
       cardinality(r.child_ages)             AS children,
       r.segment,
       r.room_total,
       r.additional_packages_total,
       r.total,
       r.folio_id,
       r.created_at,
       r.created_by,
       r.approval_id                         AS discount_approval_id,
       r.cancellation_policy_id,
       r.cancellation_policy_version,
       r.guarantee_policy_id,
       r.guarantee_policy_version,
       r.guarantee_type,
       r.guarantee_method,
       r.guaranteed_at,
       r.guarantee_deposit_required,
       r.guarantee_deposit_received,
       r.provisional_until,
       r.cancellation_reason,
       r.cancellation_previous_status,
       r.cancelled_at,
       r.cancelled_by,
       r.penalty_amount,
       r.penalty_waived,
       r.penalty_posting_id,
       r.penalties_charged
FROM reservations.reservation r
         JOIN reservations.booking b ON b.id = r.booking_id;

-- Uma linha por noite de cada reserva (o preço congelado, RN-14; no day use, uma linha na própria data), de qualquer
-- situação: a noite vendida é a de uma reserva sold (status PROVISIONAL, CONFIRMED, IN_HOUSE ou CHECKED_OUT; a
-- cancelada e o no-show ficam com as noites, sold falso). As noites de uma reserva são sempre do plano dela (a troca de
-- plano recalcula todas), então rate_plan_id e rate_plan_category são os da reserva. Q-15: counts_in_occupancy (a noite
-- entra na ocupação: pernoite de plano NORMAL ou COURTESY) e counts_in_adr (entra no ADR: pernoite de plano NORMAL); o
-- day use nunca entra em nenhum dos dois (não ocupa noite do inventário, H5), e o uso da casa (HOUSE_USE) também não.
-- Uma noite vendida conta na ocupação quando sold E counts_in_occupancy; no ADR, quando sold E counts_in_adr.
-- calculated_room_amount: a diária calculada pelo rates; room_amount: a gravada (overridden quando difere;
-- discount_percent quando é menor, o desconto); included_packages_amount: os pacotes inclusos (parte da diária);
-- accommodation_amount = room_amount - included_packages_amount (a hospedagem, sem os inclusos, RF-FIS-04);
-- additional_packages_amount: os pacotes adicionais; amount = room_amount + additional_packages_amount.
CREATE VIEW reservations.report_room_nights AS
SELECT n.reservation_id,
       n.stay_date,
       r.room_type_id,
       r.rate_plan_id,
       r.rate_plan_category,
       r.status,
       r.status IN ('PROVISIONAL', 'CONFIRMED', 'IN_HOUSE', 'CHECKED_OUT')              AS sold,
       NOT r.day_use AND r.rate_plan_category IN ('NORMAL', 'COURTESY')                 AS counts_in_occupancy,
       NOT r.day_use AND r.rate_plan_category = 'NORMAL'                                 AS counts_in_adr,
       r.day_use,
       b.channel,
       b.origin,
       r.segment,
       r.block_id,
       r.adults,
       cardinality(r.child_ages)                                                         AS children,
       n.calculated_room_amount,
       n.room_amount,
       n.included_packages_amount,
       n.room_amount - n.included_packages_amount                                        AS accommodation_amount,
       n.additional_packages_amount,
       n.amount,
       n.overridden,
       n.discount_percent
FROM reservations.nightly_rate n
         JOIN reservations.reservation r ON r.id = n.reservation_id
         JOIN reservations.booking b ON b.id = r.booking_id;

-- Uma linha por tipo de UH e data do inventário (RF-TAR-08 e 09), no horizonte mantido (as datas anteriores à data
-- operacional ficam como estavam no último refazimento). total: UHs ativas do tipo; sold: unidades vendidas a reservas;
-- blocked: separadas a bloqueios de grupo; out_of_order: UHs em OOO (o OOS não conta); overbooking_limit: o limite da
-- data (nasce zero, Q-09); available = total - sold - blocked - out_of_order + overbooking_limit (negativo = a data
-- está sobrevendida). updated_at: a última mudança da linha.
CREATE VIEW reservations.report_inventory AS
SELECT i.room_type_id,
       i.stay_date,
       i.total,
       i.sold,
       i.blocked,
       i.out_of_order,
       i.overbooking_limit,
       i.total - i.sold - i.blocked - i.out_of_order + i.overbooking_limit AS available,
       i.updated_at
FROM reservations.room_type_inventory i;

-- Uma linha por alocação de UH a uma reserva (RF-RES-04, E10), ativa ou solta (o histórico inteiro). starts_on e
-- ends_on: o período (a chegada e a saída da reserva; day_use quando são iguais). reason: INITIAL, GUEST_REQUEST,
-- OUT_OF_ORDER, ROOM_ISSUE ou OPERATIONAL (pode crescer); do_not_move: "não mover". reallocation_required_at e
-- reallocation_out_of_order_id: a marca "requer realocação" (a UH entrou num OOO que atinge a estadia, RN-07).
-- assigned_by/released_by nulos = o sistema. released_at e release_reason (UNASSIGNED, REALLOCATED,
-- RESERVATION_CANCELLED ou RESERVATION_CHANGED; pode crescer): a soltura (nulos na ativa).
CREATE VIEW reservations.report_room_assignments AS
SELECT a.id                    AS assignment_id,
       a.reservation_id,
       a.room_id,
       a.starts_on,
       a.ends_on,
       a.starts_on = a.ends_on AS day_use,
       a.reason,
       a.do_not_move,
       a.reallocation_required_at,
       a.reallocation_out_of_order_id,
       a.assigned_at,
       a.assigned_by,
       a.released_at IS NULL   AS active,
       a.released_at,
       a.released_by,
       a.release_reason
FROM reservations.room_assignment a;

-- Uma linha por bloqueio de grupo, tipo de UH e noite (RF-RES-09, E11). block_status: ACTIVE (segura as unidades não
-- utilizadas) ou RELEASED (já devolveu o não utilizado ao inventário, released_on é a data operacional da liberação);
-- holder_guest_id ou holder_company_id: o titular (um só). rate_plan_id: o plano do bloqueio (as reservas do pick-up
-- copiam as políticas dele); cutoff_date: a data de corte; folio_id: a conta master. quantity: as unidades do bloqueio
-- na noite; picked_up: as convertidas em reservas ativas (mantido a partir da data operacional); released: as
-- devolvidas pela liberação; remaining = quantity - picked_up - released (as que o bloqueio ainda segura); rate: a
-- tarifa do grupo por UH e noite.
CREATE VIEW reservations.report_block_allocations AS
SELECT k.id                                         AS block_id,
       k.code                                       AS block_code,
       k.status                                     AS block_status,
       k.holder_guest_id,
       k.holder_company_id,
       k.rate_plan_id,
       k.cutoff_date,
       k.folio_id,
       k.released_on,
       l.room_type_id,
       l.stay_date,
       l.quantity,
       l.picked_up,
       l.released,
       l.quantity - l.picked_up - l.released        AS remaining,
       l.rate
FROM reservations.block_allocation l
         JOIN reservations.room_block k ON k.id = l.block_id;

-- O ALTER DEFAULT PRIVILEGES do schema daria INSERT, UPDATE e DELETE às views: o papel da aplicação só as lê.
GRANT SELECT ON reservations.report_reservations TO "${app_role}";
GRANT SELECT ON reservations.report_room_nights TO "${app_role}";
GRANT SELECT ON reservations.report_inventory TO "${app_role}";
GRANT SELECT ON reservations.report_room_assignments TO "${app_role}";
GRANT SELECT ON reservations.report_block_allocations TO "${app_role}";
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON reservations.report_reservations FROM "${app_role}";
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON reservations.report_room_nights FROM "${app_role}";
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON reservations.report_inventory FROM "${app_role}";
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON reservations.report_room_assignments FROM "${app_role}";
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON reservations.report_block_allocations FROM "${app_role}";
