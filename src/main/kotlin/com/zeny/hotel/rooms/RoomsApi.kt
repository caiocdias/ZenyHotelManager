package com.zeny.hotel.rooms

import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * API pública do módulo `rooms` para os outros módulos (ARCHITECTURE §3.1, RF-UH-01 a 08): leituras de
 * UH, tipo de UH e área comum, o status da UH e os períodos OOO/OOS. Não checa permissão: quem chama é
 * um caso de uso que já se autorizou (inclusive o step-up do OOO, que é dele).
 *
 * **Status** (RF-UH-04, SPEC §8.4, RN-05, RN-08, RN-09): as mudanças entram na transação de quem chama
 * (ou numa própria), travam a linha da UH (`SELECT … FOR UPDATE`, então duas mudanças simultâneas se
 * enfileiram e a segunda vê o resultado da primeira), aplicam a máquina de estados do status de
 * governança (fora dela: `422 HOUSEKEEPING_TRANSITION_NOT_ALLOWED`, sem efeito), gravam um
 * `RoomStatusChange` por campo que mudou (quem: o ator da requisição, ou o sistema; quando; e a origem
 * [RoomStatusSource]), vão para a trilha de auditoria e publicam [RoomStatusChanged] pelo outbox.
 * Repetir a operação do estado atual não muda nada (sem histórico nem evento). UH inexistente: `404
 * ROOM_NOT_FOUND`. Todas devolvem o status depois da mudança.
 */
@Suppress("TooManyFunctions") // leituras do cadastro e uma operação por mudança de status
interface RoomsApi {
    /** A UH, ativa ou não, ou `null` se não existe. */
    fun room(id: RoomId): RoomSummary?

    /** O tipo de UH, ativo ou não, ou `null` se não existe. */
    fun roomType(id: RoomTypeId): RoomTypeSummary?

    /** A área comum, ativa ou não, ou `null` se não existe. */
    fun area(id: AreaId): AreaSummary?

    /** As UHs ativas, de todos os tipos ou só do tipo [roomTypeId], em ordem de número. UH desativada não entra. */
    fun activeRooms(roomTypeId: RoomTypeId? = null): List<RoomSummary>

    /** Os tipos de UH ativos, na ordem de exibição. */
    fun activeRoomTypes(): List<RoomTypeSummary>

    /** A característica, ativa ou não, ou `null` se não existe. */
    fun feature(id: RoomFeatureId): RoomFeatureSummary?

    /**
     * A característica pré-carregada com a chave [key] (vista, acessível, conjugada), ativa ou não. Ela
     * sempre existe: vem da migração e nunca é apagada.
     */
    fun feature(key: RoomFeatureKey): RoomFeatureSummary

    // --- Status (M2 E02) ---------------------------------------------------------------------------

    /** O status da UH, ativa ou não, ou `null` se não existe. */
    fun status(id: RoomId): RoomStatus?

    /** Governança: Limpo, Limpo com inspeção pendente ou Inspecionado → Sujo (check-out, fechamento, uso). */
    fun markDirty(
        id: RoomId,
        source: RoomStatusSource,
    ): RoomStatus

    /**
     * Governança: Sujo → Limpo (limpeza concluída), com a inspeção pendente se [requiresInspection]. Uma UH
     * já limpa, com inspeção pendente ou inspecionada não volta a "limpa".
     */
    fun markClean(
        id: RoomId,
        requiresInspection: Boolean,
        source: RoomStatusSource,
    ): RoomStatus

    /** Governança: Limpo (com inspeção pendente ou voluntária) → Inspecionado. Sujo → Inspecionado é recusado. */
    fun markInspected(
        id: RoomId,
        source: RoomStatusSource,
    ): RoomStatus

    /** Governança: Limpo com inspeção pendente → Sujo (inspeção reprovada). Sem inspeção pendente, recusado. */
    fun rejectInspection(
        id: RoomId,
        source: RoomStatusSource,
    ): RoomStatus

    /**
     * Ocupação: a UH passa a Ocupada com [guestCount] hóspedes (de 1 a 40; fora disso `400
     * GUEST_COUNT_INVALID`), informados pela recepção (H4); numa UH já ocupada, só troca o número. UH
     * desativada não é ocupada (`422 ROOM_INACTIVE`). Não confere RN-05: quem faz o check-in confere antes
     * com [isReadyForCheckIn].
     */
    fun markOccupied(
        id: RoomId,
        guestCount: Int,
        source: RoomStatusSource,
    ): RoomStatus

    /**
     * Ocupação: a UH passa a Vaga, sem número de hóspedes e sem sinalização do hóspede. Com a origem
     * [RoomStatusSource.CHECK_OUT], fica também Suja (RN-09), mesmo que já estivesse vaga.
     */
    fun markVacant(
        id: RoomId,
        source: RoomStatusSource,
    ): RoomStatus

    /**
     * Sinalização do hóspede (não perturbe, arrumar agora): só numa UH ocupada (`422 ROOM_NOT_OCCUPIED`);
     * [GuestServiceFlag.NONE] vale sempre.
     */
    fun setServiceFlag(
        id: RoomId,
        flag: GuestServiceFlag,
        source: RoomStatusSource,
    ): RoomStatus

    /**
     * RN-05: a UH está ativa, Vaga, fora de OOO na data operacional e Inspecionada, ou Limpa sem inspeção
     * pendente (OOS não impede). UH inexistente: `404 ROOM_NOT_FOUND`.
     */
    fun isReadyForCheckIn(id: RoomId): Boolean

    /**
     * O check-in atômico da UH (M5 E02): com a linha da UH **travada** (`SELECT … FOR UPDATE`, como toda mudança de
     * status), confere a RN-05 ([isReadyForCheckIn]) e, só se ela estiver pronta, marca a UH Ocupada com [guestCount]
     * hóspedes (as regras de [markOccupied]). Não lança quando a UH não está pronta: devolve [CheckInOccupation.ready]
     * falso com o status travado (nada muda, sem histórico nem evento), para quem chama recusar e desfazer a própria
     * transação. Duas chamadas simultâneas na mesma UH se enfileiram na trava, e a segunda vê a UH já ocupada pela
     * primeira. Auditada como [markOccupied] quando ocupa. UH inexistente: `404 ROOM_NOT_FOUND`.
     */
    fun markOccupiedIfReady(
        id: RoomId,
        guestCount: Int,
        source: RoomStatusSource,
    ): CheckInOccupation

    // --- OOO e OOS (M2 E03) ------------------------------------------------------------------------

    /** O período OOO/OOS, liberado ou não, ou `null` se não existe. */
    fun outOfOrder(id: OutOfOrderId): OutOfOrderPeriod?

    /**
     * Os períodos OOO e OOS que bloqueiam algum dia de [from] a [to] (inclusive), de todas as UHs ou só da
     * [roomId], em ordem de início: o que o inventário (M4) desconta. Um período liberado só bloqueia até a
     * véspera da liberação ([OutOfOrderPeriod.blockedUntil]); liberado antes de começar, não aparece.
     */
    fun outOfOrderPeriods(
        from: LocalDate,
        to: LocalDate,
        roomId: RoomId? = null,
    ): List<OutOfOrderPeriod>

    /**
     * Abre um período OOO ou OOS na UH (RF-UH-05), sem checar permissão (a manutenção do M6 faz o próprio
     * step-up antes), mas com as regras: período de até 365 dias que não começa antes da data operacional
     * (`422 OUT_OF_ORDER_PERIOD_IN_PAST`), UH ativa (`422 ROOM_INACTIVE`), OOO nunca numa UH ocupada se
     * o período inclui a data operacional (RN-07, `422 OUT_OF_ORDER_ROOM_OCCUPIED`; OOS pode) e sem
     * sobrepor outro período da UH (`409 OUT_OF_ORDER_OVERLAP`). Trava a UH, vai para a trilha e publica
     * [OutOfOrderChanged] ([OutOfOrderAction.OPENED]). Não mexe no status de governança.
     */
    fun openOutOfOrder(
        roomId: RoomId,
        kind: OutOfOrderKind,
        request: OutOfOrderRequest,
    ): OutOfOrderPeriod

    /**
     * Libera o período na data operacional: a UH deixa de estar bloqueada a partir dela. Se o período
     * estava em vigor, a UH volta ao [OutOfOrderPeriod.returnStatus], com o histórico na origem [source]
     * (e [RoomStatusChanged]); liberar um período que ainda não começou o cancela, e um que já terminou só
     * o encerra, sem mexer no status. Publica [OutOfOrderChanged] ([OutOfOrderAction.RELEASED]). Liberar
     * de novo não muda nada. Inexistente: `404 OUT_OF_ORDER_NOT_FOUND`.
     */
    fun releaseOutOfOrder(
        id: OutOfOrderId,
        source: RoomStatusSource,
    ): OutOfOrderPeriod
}

/**
 * Tipo de período (RF-UH-05, SPEC §4): OOO retira a UH do inventário vendável (inclusive no channel
 * manager) e a impede de ficar pronta para check-in (RN-05); OOS a mantém vendável, como última opção.
 */
enum class OutOfOrderKind {
    /** Fora de ordem: obra, defeito grave. */
    OUT_OF_ORDER,

    /** Fora de serviço: problema menor. */
    OUT_OF_SERVICE,
}

/** Id de um período OOO/OOS. */
@JvmInline
value class OutOfOrderId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): OutOfOrderId = OutOfOrderId(UuidV7.generate())
    }
}

/**
 * Os dados de um período OOO/OOS, para abrir (RF-UH-05): de [startsOn] a [endsOn] em datas de negócio,
 * inclusive (até 365 dias), o motivo, o responsável (pessoa ou empresa, texto) e o status de governança
 * a que a UH volta na liberação ([returnStatus]; normalmente Sujo, para limpeza, RF-MAN-03).
 */
data class OutOfOrderRequest(
    val startsOn: LocalDate,
    val endsOn: LocalDate,
    val reason: String,
    val responsible: String,
    val returnStatus: HousekeepingStatus = HousekeepingStatus.DIRTY,
)

/**
 * Um período OOO/OOS (RF-UH-05, SPEC §8.2.3). [openedBy] e [releasedBy] nulos = o sistema. Liberado,
 * tem a data operacional da liberação ([releasedOn]); [blockedUntil] é o último dia que ele bloqueia (o
 * fim, ou a véspera da liberação), `null` se não bloqueia nenhum (liberado antes de começar).
 */
data class OutOfOrderPeriod(
    val id: OutOfOrderId,
    val roomId: RoomId,
    val kind: OutOfOrderKind,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
    val reason: String,
    val responsible: String,
    val returnStatus: HousekeepingStatus,
    val openedAt: Instant,
    val openedBy: UserId?,
    val updatedAt: Instant,
    val releasedOn: LocalDate?,
    val releasedAt: Instant?,
    val releasedBy: UserId?,
    val blockedUntil: LocalDate?,
    val version: Long,
)

/** Ocupação da UH (RF-UH-04), derivada da recepção. */
enum class OccupancyStatus {
    VACANT,
    OCCUPIED,
}

/**
 * Status de governança da UH (RF-UH-04, SPEC §8.4). "Limpo — inspeção pendente" é [CLEAN] com
 * [RoomStatus.inspectionPending]; "em limpeza" vem da tarefa em andamento (M6), não daqui.
 */
enum class HousekeepingStatus {
    DIRTY,
    CLEAN,
    INSPECTED,
}

/** Sinalização do hóspede (RF-UH-04). */
enum class GuestServiceFlag {
    NONE,

    /** Não perturbe. */
    DO_NOT_DISTURB,

    /** Arrumar agora. */
    MAKE_UP_ROOM,
}

/** Origem de uma mudança de status (RF-UH-06). */
enum class RoomStatusSource {
    /** Mudança manual pela API (RN-08). */
    MANUAL,

    /** Tarefa de governança (M6). */
    TASK,

    /** Check-in da recepção (M5). */
    CHECK_IN,

    /** Check-out da recepção (M5; RN-09). */
    CHECK_OUT,

    /** Fechamento do dia (M6; RN-09). */
    END_OF_DAY,
}

/** Campo do status que mudou, num registro do histórico (RF-UH-06). */
enum class RoomStatusField {
    OCCUPANCY,
    GUEST_COUNT,
    HOUSEKEEPING_STATUS,
    INSPECTION_PENDING,
    SERVICE_FLAG,
}

/**
 * O resultado de [RoomsApi.markOccupiedIfReady]: [ready] diz se a UH travada estava pronta (RN-05) e foi ocupada;
 * [status] é o status depois (ocupada) ou, não pronta, o status travado que impediu o check-in.
 */
data class CheckInOccupation(
    val ready: Boolean,
    val status: RoomStatus,
)

/**
 * O status de uma UH (RF-UH-04): ocupação e número de hóspedes informados pela recepção, status de
 * governança com a inspeção pendente, sinalização do hóspede, a sobreposição OOO/OOS em vigor na data
 * operacional ([outOfOrder], `null` sem nenhuma), se está pronta para check-in (RN-05), o instante da
 * última mudança e a versão (a mesma do `ETag`).
 */
data class RoomStatus(
    val roomId: RoomId,
    val active: Boolean,
    val occupancy: OccupancyStatus,
    val guestCount: Int?,
    val housekeepingStatus: HousekeepingStatus,
    val inspectionPending: Boolean,
    val serviceFlag: GuestServiceFlag,
    val outOfOrder: OutOfOrderKind?,
    val readyForCheckIn: Boolean,
    val changedAt: Instant,
    val version: Long,
)

/**
 * As características pré-carregadas pela migração (decisão do usuário, M2 E01): a chave as identifica
 * para os outros módulos mesmo que o gestor troque o código ou o nome. As criadas depois não têm chave.
 */
enum class RoomFeatureKey {
    /** Vista (código inicial `VIEW`). */
    VIEW,

    /** Acessível (código inicial `ACCESSIBLE`). */
    ACCESSIBLE,

    /** Conjugada (código inicial `CONNECTING`): uma característica da UH, não um vínculo entre duas UHs. */
    CONNECTING,
}

/** Id de um tipo de UH. */
@JvmInline
value class RoomTypeId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): RoomTypeId = RoomTypeId(UuidV7.generate())
    }
}

/** Id de uma característica de UH ou de tipo de UH (vista, acessível, conjugada…). */
@JvmInline
value class RoomFeatureId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): RoomFeatureId = RoomFeatureId(UuidV7.generate())
    }
}

/** Id de uma UH. */
@JvmInline
value class RoomId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): RoomId = RoomId(UuidV7.generate())
    }
}

/** Id de uma área comum. */
@JvmInline
value class AreaId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): AreaId = AreaId(UuidV7.generate())
    }
}

/** Tipo de área comum (RF-UH-03). */
enum class AreaKind {
    LOBBY,
    POOL,
    KITCHEN,

    /** Salão (de eventos, de café da manhã…). */
    HALL,
    CORRIDOR,
    OTHER,
}

/** Ocupação de um tipo de UH (RF-UH-01): a padrão e a máxima, de adultos e de crianças. */
data class Occupancy(
    val standardAdults: Int,
    val standardChildren: Int,
    val maxAdults: Int,
    val maxChildren: Int,
)

/** Uma característica como os outros módulos a veem; [key] só nas pré-carregadas. */
data class RoomFeatureSummary(
    val id: RoomFeatureId,
    val key: RoomFeatureKey?,
    val code: String,
    val name: String,
    val active: Boolean,
)

/** Um tipo de UH como os outros módulos o veem. */
data class RoomTypeSummary(
    val id: RoomTypeId,
    val code: String,
    val name: String,
    val occupancy: Occupancy,
    val displayOrder: Int,
    val featureIds: Set<RoomFeatureId>,
    val active: Boolean,
)

/** Uma UH como os outros módulos a veem. [floor] é o andar ou a ala, se informado. */
data class RoomSummary(
    val id: RoomId,
    val number: String,
    val floor: String?,
    val roomTypeId: RoomTypeId,
    val featureIds: Set<RoomFeatureId>,
    val active: Boolean,
)

/** Uma área comum como os outros módulos a veem. */
data class AreaSummary(
    val id: AreaId,
    val name: String,
    val kind: AreaKind,
    val active: Boolean,
)
