package com.zeny.hotel.reservations

import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomTypeId
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * API pública do `reservations` para os outros módulos (sem checar permissão: quem chama já se autorizou).
 *
 * Inventário (M4 E05): a leitura dos contadores e a recontagem, que o fechamento do dia (M6) chama para reparar
 * qualquer divergência (uma publicação do outbox abandonada, por exemplo). Reservas (E07): a criação ([createBooking])
 * para o walk-in (M5), o channel manager (M8) e os eventos (M10), e as leituras ([booking], [reservation]); desde a
 * E08, o cancelamento ([cancelReservation]); desde a E09, a confirmação da pré-reserva ([confirmReservation]) e as
 * garantias pendentes que vencem num intervalo ([pendingGuarantees]); desde a E11, os bloqueios de grupo
 * ([createBlock], [block]) e a liberação do não utilizado ([releaseUnused], que o fechamento chama); desde o M5 E01, a
 * alocação de UH ([assignRoom]) e as transições de hospedagem ([checkIn], [undoCheckIn], [checkOut], [moveRoom]).
 */
@Suppress("TooManyFunctions") // a API pública do módulo: inventário, reservas, ciclo de vida e bloqueios
interface ReservationsApi {
    /**
     * Cria a reserva agrupadora [booking] com as reservas dela (M4 E07; RF-RES-01 a 03 e 14), na transação de quem
     * chama (ou numa própria), auditada `reservations.booking.create` em nome do ator corrente ou do sistema, com as
     * mesmas regras de `POST /api/v1/bookings`, **menos as permissões e o step-up do desconto** (quem chama já se
     * autorizou: o channel manager grava a diária da OTA): hóspedes pela `GuestsApi` (mesclado vira o sobrevivente;
     * anonimizado `422 GUEST_ANONYMIZED`; inexistente `400 UNKNOWN_GUEST`), estadia vendável no canal da origem
     * (`RatesApi.isSellable`; recusa `422` com o código do `rates` e todos os motivos em `refusals`), preço congelado
     * por noite (`RatesApi.priceFor`, RN-14), políticas copiadas (RN-15), uma unidade do tipo tomada em cada noite
     * (`409 ROOM_TYPE_UNAVAILABLE`, nada gravado; o day use não toma, H5), a conta de cada reserva aberta
     * (`BillingApi.openFolio`, dono `RESERVATION`) e `ReservationCreated` por reserva, tudo na mesma transação.
     */
    fun createBooking(booking: NewBooking): BookingSummary

    /**
     * Cancela a reserva (M4 E08; RF-RES-06), na transação de quem chama (ou numa própria), auditada
     * `reservations.reservation.cancel` em nome do ator corrente ou do sistema, com as mesmas regras de
     * `POST /api/v1/reservations/{id}/cancel`, **menos a permissão e o step-up da isenção** (quem chama já se
     * autorizou: a expiração da pré-reserva na E09, o channel manager no M8): só uma pré-reserva ou uma confirmada
     * (`409 RESERVATION_TRANSITION_NOT_ALLOWED`); a multa pela política copiada na reserva e pelo prazo, lançada na
     * conta da reserva (`BillingApi.post`, código `CANCELLATION_FEE`, origem `MANUAL`) salvo com
     * [ReservationCancellationRequest.waivePenalty]; as unidades das noites a partir da data operacional devolvidas; o
     * histórico e `ReservationCancelled` pelo outbox, tudo na mesma transação.
     */
    fun cancelReservation(
        id: ReservationId,
        cancellation: ReservationCancellationRequest,
    ): ReservationSummary

    /**
     * Marca como no-show a reserva confirmada que não chegou (M6 E11; RF-RES-07, RN-15, SPEC §8.4
     * `Confirmed → NoShow`). **Só o fechamento do dia chama**: na transação de quem chama (ou numa própria), auditada
     * `reservations.reservation.no-show` em nome do ator corrente ou do sistema, **sem permissão**. Travada a reserva,
     * se já não é `CONFIRMED` ou se a chegada é depois da data operacional corrente (um check-in ou um cancelamento
     * confirmados antes da trava a tiram daqui), nada muda e devolve `null`, nunca `409`. Senão: as unidades das noites
     * a partir de `max(chegada, data operacional)` voltam ao inventário ou ao bloqueio; a multa é a da política de
     * cancelamento **copiada na reserva**, sem prazo, descontado o já cobrado (`penaltiesCharged`), lançada na conta da
     * reserva (`BillingApi.post`, código `NO_SHOW_FEE`, origem `END_OF_DAY`, `sourceReference`
     * `reservations.reservation:<id>:<data operacional>:no-show`), sem isenção automática; a UH alocada é solta
     * (`RESERVATION_NO_SHOW`); a reserva vira `NO_SHOW`, com o histórico e `ReservationMarkedNoShow` pelo outbox, tudo
     * na mesma transação.
     */
    fun markNoShow(id: ReservationId): ReservationSummary?

    /**
     * Confirma a pré-reserva com a garantia [method] (M4 E09; RF-RES-08 no recorte manual), na transação de quem chama
     * (ou numa própria), auditada `reservations.reservation.confirm` em nome do ator corrente ou do sistema, com as
     * mesmas regras de `POST /api/v1/reservations/{id}/confirm`, **menos a permissão** (quem chama já se autorizou: a
     * confirmação do pagamento integrado no M7): travada, uma pré-reserva ainda na validade
     * (`422 PROVISIONAL_RESERVATION_EXPIRED`) ou uma confirmada sem garantia (a política exige uma e nenhuma foi
     * registrada, ou a registrada é de outro meio; a nova a substitui); senão
     * `409 RESERVATION_TRANSITION_NOT_ALLOWED`; com a garantia que a política copiada pede
     * (`422 GUARANTEE_METHOD_NOT_ACCEPTED`) e, no depósito, o total líquido de depósitos da conta cobrindo o exigido
     * (`422 DEPOSIT_INSUFFICIENT`). A pré-reserva vira confirmada e publica `ReservationConfirmed`; a confirmada só
     * ganha a garantia registrada (sem evento). O histórico vai na mesma transação.
     */
    fun confirmReservation(
        id: ReservationId,
        method: GuaranteeMethod,
    ): ReservationSummary

    /**
     * As pré-reservas vencidas (M6 E11; RN-23): a validade igual ou anterior a agora (`Clock`), pela validade e pelo
     * id, até 1000, lidas sem trava. É o mesmo critério do `ProvisionalExpiryJob`; o fechamento do dia expira cada uma
     * por [expireProvisional]. Sem permissão.
     */
    fun dueProvisionals(): List<ReservationId>

    /**
     * Expira a pré-reserva [id] (M6 E11; RN-23), o mesmo caso de uso do `ProvisionalExpiryJob`: na transação de quem
     * chama (ou numa própria), auditada `reservations.reservation.expire` com o ator sistema, **sem permissão**. Trava
     * a reserva e reconfere: se ainda é pré-reserva vencida, cancela com o motivo `EXPIRED`, sem multa e devolvendo o
     * inventário, e devolve `true`; se já não é (o job, uma confirmação ou um cancelamento chegaram antes), nada muda e
     * devolve `false`, nunca `409`. Id inexistente: `404 RESERVATION_NOT_FOUND`.
     */
    fun expireProvisional(id: ReservationId): Boolean

    /**
     * As garantias pendentes (pré-reservas e reservas confirmadas sem garantia) com o vencimento de [from] (inclusive;
     * nulo = sem início, inclusive as já vencidas) a [to] (exclusive), da que vence primeiro à última, com o depósito
     * exigido e o recebido (M4 E09; o painel do fechamento, M6). Até 1000. Não confere permissão.
     */
    fun pendingGuarantees(
        from: Instant?,
        to: Instant,
    ): List<PendingGuarantee>

    /**
     * Cria o bloqueio de grupo [block] (M4 E11; RF-RES-09; os eventos do M10), na transação de quem chama (ou numa
     * própria), auditada `reservations.room-block.create` em nome do ator corrente ou do sistema, com as regras de
     * `POST /api/v1/room-blocks`, **menos a permissão**: as unidades tomadas no contador `blocked` do inventário
     * (`409 ROOM_TYPE_UNAVAILABLE` sem disponibilidade, nada gravado) e a conta master do grupo aberta
     * (`BillingApi.openFolio`, dono `GROUP` com o id do bloqueio).
     */
    fun createBlock(block: NewRoomBlock): RoomBlockSummary

    /** O bloqueio de grupo com as unidades; `null` se não existe. */
    fun block(id: RoomBlockId): RoomBlockSummary?

    /**
     * Libera o não utilizado do bloqueio (M4 E11; RN-16): **o fechamento do dia (M6) chama**, para cada bloqueio de
     * [blocksDueForRelease]; nada a chama automaticamente antes disso. Na transação de quem chama (ou numa própria),
     * auditada `reservations.room-block.release-unused`: com o bloqueio travado, antes da data de corte (a data
     * operacional ainda anterior a ela) é `409 ROOM_BLOCK_CUTOFF_NOT_REACHED`; a partir dela, devolve ao inventário as
     * unidades que não viraram reserva nas noites a partir da data operacional (publica `AvailabilityChanged`) e deixa
     * o bloqueio `RELEASED` (sem pick-up nem alteração depois). Num bloqueio já liberado, não muda nada.
     */
    fun releaseUnused(id: RoomBlockId): RoomBlockSummary

    /** Os bloqueios ativos cuja data de corte já chegou (igual ou anterior à data operacional), para o fechamento. */
    fun blocksDueForRelease(): List<RoomBlockId>

    /**
     * Aloca a UH [roomId] à reserva (M5 E01, Ajuste D 4 da M4 E10: o check-in e o walk-in), na transação de quem chama
     * (ou numa própria), auditada `reservations.room-assignment.assign`, com as mesmas regras de
     * `POST /api/v1/reservations/{id}/assign-room`, **menos a permissão**: só uma pré-reserva ou uma confirmada com a
     * estadia ainda por vir; a UH ativa, do tipo da reserva e sem OOO nas noites; a sobreposição recusada pelo banco
     * (`409 ROOM_ALREADY_ASSIGNED`); com outra UH já alocada, realoca ("não mover" recusa).
     */
    fun assignRoom(
        id: ReservationId,
        roomId: RoomId,
        reason: RoomAssignmentReason? = null,
        doNotMove: Boolean? = null,
    ): ReservationSummary

    /**
     * O check-in da reserva (M5 E01; RF-REC-01, SPEC §8.4 `Confirmed → InHouse`), na transação de quem chama (ou numa
     * própria), auditado `reservations.reservation.check-in`, **sem conferir permissão nem RN-05** (o `frontdesk` se
     * autoriza, confere a UH pronta e marca a ocupação no `rooms`): só uma confirmada
     * (`409 RESERVATION_TRANSITION_NOT_ALLOWED`, ação `CHECK_IN`); a data operacional dentro da estadia (antes da
     * chegada `422 CHECK_IN_BEFORE_ARRIVAL`, depois do fim `422 RESERVATION_STAY_ENDED`; a chegada atrasada é aceita);
     * a UH alocada (`409 ROOM_NOT_ASSIGNED`), ainda ativa (`422 ROOM_INACTIVE`) e do tipo da reserva
     * (`422 ROOM_TYPE_MISMATCH`); a lista completa dos hóspedes com os dados de check-in ([CheckIn]; formato
     * `400 CHECK_IN_INVALID`; mesclado vira o sobrevivente, anonimizado `422 GUEST_ANONYMIZED`); a RN-24 (sem data de
     * nascimento `422 GUEST_BIRTH_DATE_REQUIRED`, menor sem comprovação `422 MINOR_AUTHORIZATION_REQUIRED`, comprovação
     * inválida `422 MINOR_AUTHORIZATION_INVALID`). Grava `IN_HOUSE`, o histórico `CHECK_IN` e publica [GuestCheckedIn].
     */
    fun checkIn(
        id: ReservationId,
        checkIn: CheckIn,
    ): ReservationSummary

    /**
     * Desfaz o check-in (M5 E01; SPEC §8.4 `InHouse → Confirmed`), auditado `reservations.reservation.undo-check-in`,
     * **sem conferir a permissão sensível** (é do `frontdesk`): só uma hospedada, na mesma data operacional do check-in
     * (`422 UNDO_CHECK_IN_EXPIRED`). A reserva volta a confirmada, com a UH ainda alocada e sem os dados de check-in (a
     * comprovação do menor fica no histórico); publica [GuestCheckInUndone].
     */
    fun undoCheckIn(id: ReservationId): ReservationSummary

    /**
     * O check-out (M5 E01; RF-REC-06, SPEC §8.4 `InHouse → CheckedOut`), auditado `reservations.reservation.check-out`,
     * **sem conferir permissão nem saldo** (o `frontdesk` confere a conta e muda a UH): só uma hospedada. Antecipado (a
     * data operacional antes da saída): a saída passa à data operacional, as noites seguintes voltam ao inventário (ou
     * ao bloqueio do pick-up) e as que ficam guardam o preço congelado; no próprio dia da chegada, a
     * estadia vira day use
     * na data. A alocação é encerrada na mesma transação (motivo `CHECKED_OUT`). Com [expectedVersion] (a da reserva
     * que o chamador leu), a versão é conferida contra a da reserva já travada: diferente é `412 PRECONDITION_FAILED` e
     * nada muda (M5 E05, como na [moveRoom]); nulo não confere. Publica [GuestCheckedOut].
     */
    fun checkOut(
        id: ReservationId,
        expectedVersion: Long? = null,
    ): ReservationSummary

    /**
     * A troca de UH do hospedado (M5 E01; RF-REC-04), auditada `reservations.reservation.move-room`, **sem conferir
     * permissão** (o `frontdesk` muda o status das duas UHs): só uma hospedada com a estadia ainda por usar; a UH nova
     * ativa, do tipo da reserva (`422 ROOM_TYPE_MISMATCH`), diferente da atual (`422 ROOM_MOVE_SAME_ROOM`) e sem OOO no
     * período novo (`422 ROOM_OUT_OF_ORDER`); a alocação atual termina na data operacional (`ROOM_MOVED`) e a nova vai
     * dela à saída (`409 ROOM_ALREADY_ASSIGNED` se outra reserva ocupa a UH); a tarifa das noites restantes mantida ou
     * recalculada ([RoomMoveTariff]). Com [expectedVersion] (a do `If-Match` do cliente), a versão é conferida contra a
     * da reserva já travada: diferente é `412 PRECONDITION_FAILED` e nada muda (M5 E05, D5); nulo não confere.
     * Publica [RoomMoved].
     */
    fun moveRoom(
        id: ReservationId,
        move: RoomMove,
        expectedVersion: Long? = null,
    ): ReservationSummary

    /** A reserva agrupadora com as reservas; `null` se não existe. */
    fun booking(id: BookingId): BookingSummary?

    /** A reserva; `null` se não existe. */
    fun reservation(id: ReservationId): ReservationSummary?

    /**
     * Os contadores do tipo de UH [roomTypeId] de [from] a [to] (inclusive, até 366 dias), em ordem de data; só as
     * datas mantidas no inventário (as de fora não aparecem).
     */
    fun inventory(
        roomTypeId: RoomTypeId,
        from: LocalDate,
        to: LocalDate,
    ): List<InventoryDay>

    /**
     * Recontagem (auditada `reservations.inventory.recount`): refaz, de [from] (padrão e mínimo: a data operacional) a
     * [to] (padrão e máximo: o fim do horizonte), o total e o OOO pela `RoomsApi` e as vendidas e bloqueadas pelas
     * reservas e bloqueios, de todos os tipos de UH ativos e dos que já têm inventário; cria as datas que faltam.
     * Publica [AvailabilityChanged] nos tipos que mudaram. Precisa de uma transação de escrita de quem chama ou abre a
     * própria.
     */
    fun recountInventory(
        from: LocalDate? = null,
        to: LocalDate? = null,
    ): InventoryRecount

    /**
     * As reservas com a chegada pendente na data [date]: confirmadas (`CONFIRMED`) com chegada até [date], por id (a
     * pré-reserva nunca vira no-show). Sem conferir permissão e sem trava; o fechamento do dia chama com a data que
     * fecha.
     */
    fun pendingArrivals(date: LocalDate): List<ReservationId>

    /**
     * As hospedadas (`IN_HOUSE`) com a saída pendente na data [date] (saída até [date]), por id. Sem conferir permissão
     * e sem trava; o fechamento do dia chama com a data que fecha.
     */
    fun pendingDepartures(date: LocalDate): List<ReservationId>

    /**
     * Todas as hospedadas (`IN_HOUSE`) que já chegaram até [date] (chegada até [date]), inclusive as de saída já
     * passada, por id. Sem conferir permissão e sem trava; o fechamento do dia chama com a data que fecha.
     */
    fun inHouse(date: LocalDate): List<ReservationId>

    /**
     * A UH da alocação vigente (ainda não solta) de cada reserva de [ids], numa consulta só (M6 E02, D7: a chegada
     * prevista da governança). A reserva sem alocação vigente (nunca alocada, solta no check-out, na troca ou no
     * cancelamento) ou inexistente fica de fora. Sem conferir permissão e sem trava.
     */
    fun assignedRooms(ids: Collection<ReservationId>): Map<ReservationId, RoomId>

    /**
     * A hora prevista de chegada (hora local do hotel, sem data nem fuso) de cada reserva de [ids], numa consulta só
     * (M6 E22, D6: o early check-in da governança). Só as reservas com hora: a sem hora e a inexistente ficam de fora.
     * Sem conferir permissão e sem trava; devolve só o id da reserva e a hora, sem dado de hóspede (RN-13).
     */
    fun expectedArrivalTimes(ids: Collection<ReservationId>): Map<ReservationId, LocalTime>

    /**
     * As cobranças devidas da hospedada [id] até [through] (M6 E11; a regra única do fechamento do dia e do
     * check-out): `null` se a reserva não existe ou não está `IN_HOUSE`. Senão, as cobranças
     * ([ReservationNightCharges]) de cada noite congelada `n` com
     * `max(chegada, data operacional do check-in) <= n <= through` (no day use, a noite da chegada), em ordem de
     * noite. A taxa de turismo **vigente** (`HotelApi.taxSettings`) entra **só na noite `through`**, a que fecha: as
     * noites anteriores (recuperadas, como a do check-in feito durante a execução do fechamento) levam só a hospedagem
     * e os pacotes. Motivo: a chave deduplica o que foi lançado, não a noite; com a taxa nula ou zero no fechamento de
     * uma noite, a chave da taxa dela nunca é gravada, e recalcular a noite com a taxa ligada depois a cobraria
     * retroativamente, no valor de outra data. Custo aceito: a noite recuperada (anterior à que fecha) não leva taxa
     * de turismo, inclusive depois de uma falha de item (`ROOM_CHARGE_FAILED`) recuperada no fechamento seguinte; o
     * operador lança a taxa à mão. Sem conferir permissão. As chaves de lançamento (`sourceReference`) deduplicam o que
     * já foi lançado: quem chama lança todas.
     *
     * Custo: cada fechamento repete o `post` das noites passadas de cada hospedada (cresce com o quadrado da estadia;
     * aceitável em 30 UHs); se ficar lento, filtrar as chaves já lançadas.
     */
    fun chargesDue(
        id: ReservationId,
        through: LocalDate,
    ): StayCharges?
}

/**
 * Os contadores de um tipo de UH numa data (RF-TAR-08 e 09): [total] de UHs ativas, [sold] a reservas, [blocked] a
 * bloqueios, [outOfOrder] UHs em OOO (o OOS não conta) e o [overbookingLimit]. [available] pode ser negativo (a data
 * está sobrevendida).
 */
data class InventoryDay(
    val roomTypeId: RoomTypeId,
    val date: LocalDate,
    val total: Int,
    val sold: Int,
    val blocked: Int,
    val outOfOrder: Int,
    val overbookingLimit: Int,
) {
    val available: Int get() = total - sold - blocked - outOfOrder + overbookingLimit
}

/** O resultado de uma recontagem: o período recontado, os tipos de UH e as linhas que mudaram. */
data class InventoryRecount(
    val from: LocalDate,
    val to: LocalDate,
    val roomTypes: Int,
    val changedRows: Int,
)
