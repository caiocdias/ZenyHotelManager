package com.zeny.hotel.housekeeping.domain

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.PostingId
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.housekeeping.DamageReportId
import com.zeny.hotel.housekeeping.DamageReportStatus
import com.zeny.hotel.housekeeping.HousekeepingErrorCodes
import com.zeny.hotel.housekeeping.OccurrenceSource
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UserId
import java.time.Instant

/** As ações sobre um dano (D4-E06). */
enum class DamageAction {
    REVIEW,
    CHARGE,
    WAIVE,
}

/** `409 HOUSEKEEPING_DAMAGE_TRANSITION_NOT_ALLOWED`, com o status em `damageStatus` e a ação em `action`. */
class DamageTransitionNotAllowedException(
    val action: DamageAction,
    val status: DamageReportStatus,
) : ConflictException(
        HousekeepingErrorCodes.HOUSEKEEPING_DAMAGE_TRANSITION_NOT_ALLOWED,
        "A ação não vale no status atual do dano.",
    ) {
    override val problemProperties: Map<String, Any>
        get() = mapOf("damageStatus" to status.name, "action" to action.name)
}

/**
 * Um dano (RF-GOV-09, SPEC §8.2.10, D4-E06): numa UH **ou** numa área, com a [description] (texto livre), o custo
 * estimado ([estimatedCost], opcional), o instante do fato ([occurredAt]), quem registrou ([reportedBy], o dono no
 * escopo `OWN`) e a reserva vinculada ([reservationId], sugerida no registro pelo caso de uso e visível só a quem
 * revisa). Ciclo `REPORTED → REVIEWED → CHARGED | WAIVED`:
 * - [review] (`REPORTED|REVIEWED`) fixa o valor final (> 0), o código de lançamento e confirma, troca ou retira o
 *   vínculo;
 * - [charged] (`REVIEWED`, com reserva vinculada; a conta e o lançamento são do caso de uso) grava a conta e o
 *   lançamento;
 * - [waive] (`REPORTED|REVIEWED`) grava o motivo.
 * Cobrança e dispensa gravam quando e por quem. Nunca é apagado.
 */
@Suppress("LongParameterList") // os campos do dano, só pelo register e pelo restore
class DamageReport private constructor(
    val id: DamageReportId,
    val roomId: RoomId?,
    val areaId: AreaId?,
    val description: String,
    val estimatedCost: Money?,
    val occurredAt: Instant,
    val reportedBy: UserId,
    val source: OccurrenceSource,
    val checklistExecutionId: ChecklistExecutionId?,
    val checklistItemKey: String?,
    reservationId: ReservationId?,
    status: DamageReportStatus,
    review: Review?,
    waiverReason: String?,
    charge: Charge?,
    resolvedAt: Instant?,
    resolvedBy: UserId?,
    val createdAt: Instant,
    updatedAt: Instant,
    version: Long,
) {
    /** A revisão: o valor final, o código de lançamento, quem e quando. */
    data class Review(
        val finalAmount: Money,
        val transactionCodeId: TransactionCodeId,
        val reviewedBy: UserId,
        val reviewedAt: Instant,
    )

    /** A cobrança: a conta e o lançamento feitos pelo `billing`. */
    data class Charge(
        val folioId: FolioId,
        val postingId: PostingId,
    )

    var reservationId: ReservationId? = reservationId
        private set

    var status: DamageReportStatus = status
        private set

    var review: Review? = review
        private set

    var waiverReason: String? = waiverReason
        private set

    var charge: Charge? = charge
        private set

    var resolvedAt: Instant? = resolvedAt
        private set

    var resolvedBy: UserId? = resolvedBy
        private set

    var updatedAt: Instant = updatedAt
        private set

    /** A versão gravada (o `ETag`); a persistência a sobe a cada alteração. */
    var version: Long = version
        internal set

    /**
     * Revisa o dano (`REPORTED|REVIEWED`): o [finalAmount] (já conferido por [requireFinalAmount]), o código
     * [transactionCodeId] (já conferido pelo caso de uso) e a reserva vinculada [reservation] (nula retira o vínculo).
     */
    fun review(
        finalAmount: Money,
        transactionCodeId: TransactionCodeId,
        reservation: ReservationId?,
        by: UserId,
        at: Instant,
    ) {
        requireStatus(DamageAction.REVIEW, DamageReportStatus.REPORTED, DamageReportStatus.REVIEWED)
        requireFinalAmount(finalAmount)
        review = Review(finalAmount, transactionCodeId, by, at)
        reservationId = reservation
        status = DamageReportStatus.REVIEWED
        updatedAt = at
    }

    /**
     * Confere que o dano pode ser cobrado e devolve a reserva vinculada: `REPORTED`, `409
     * HOUSEKEEPING_DAMAGE_NOT_REVIEWED`; já cobrado ou dispensado, `409` de transição; sem reserva, `409
     * HOUSEKEEPING_DAMAGE_NO_RESERVATION`.
     */
    fun requireChargeable(): ReservationId {
        if (status == DamageReportStatus.REPORTED) {
            throw ConflictException(
                HousekeepingErrorCodes.HOUSEKEEPING_DAMAGE_NOT_REVIEWED,
                "Revise o dano antes de cobrá-lo.",
            )
        }
        requireStatus(DamageAction.CHARGE, DamageReportStatus.REVIEWED)
        return reservationId
            ?: throw ConflictException(
                HousekeepingErrorCodes.HOUSEKEEPING_DAMAGE_NO_RESERVATION,
                "O dano não tem reserva vinculada: só pode ser dispensado.",
            )
    }

    /** O valor e o código revisados, para a cobrança (só depois de [requireChargeable]). */
    fun reviewed(): Review = checkNotNull(review) { "Dano $id sem revisão" }

    /** Grava a cobrança feita na conta [folioId] pelo lançamento [postingId], por [by], em [at]. */
    fun charged(
        folioId: FolioId,
        postingId: PostingId,
        by: UserId,
        at: Instant,
    ) {
        requireChargeable()
        charge = Charge(folioId, postingId)
        resolve(DamageReportStatus.CHARGED, by, at)
    }

    /** Dispensa o dano (`REPORTED|REVIEWED`) com o [reason] (até [MAX_REASON]), por [by], em [at]. */
    fun waive(
        reason: String?,
        by: UserId,
        at: Instant,
    ) {
        requireStatus(DamageAction.WAIVE, DamageReportStatus.REPORTED, DamageReportStatus.REVIEWED)
        waiverReason = HousekeepingRules.freeText("reason", reason, MAX_REASON)
        resolve(DamageReportStatus.WAIVED, by, at)
    }

    /** O retrato da trilha: ids, local, valores e estado, com a descrição e o motivo só pelo tamanho (D4-E06). */
    fun auditState(): Map<String, Any?> =
        mapOf(
            "roomId" to roomId?.toString(),
            "areaId" to areaId?.toString(),
            "descriptionLength" to description.codePointCount(0, description.length),
            "estimatedCost" to estimatedCost?.toString(),
            "occurredAt" to occurredAt.toString(),
            "reportedBy" to reportedBy.toString(),
            "source" to source.name,
            "checklistExecutionId" to checklistExecutionId?.toString(),
            "checklistItemKey" to checklistItemKey,
            "reservationId" to reservationId?.toString(),
            "status" to status.name,
            "finalAmount" to review?.finalAmount?.toString(),
            "transactionCodeId" to review?.transactionCodeId?.toString(),
            "reviewedBy" to review?.reviewedBy?.toString(),
            "reviewedAt" to review?.reviewedAt?.toString(),
            "waiverReasonLength" to waiverReason?.let { it.codePointCount(0, it.length) },
            "folioId" to charge?.folioId?.toString(),
            "postingId" to charge?.postingId?.toString(),
            "resolvedAt" to resolvedAt?.toString(),
            "resolvedBy" to resolvedBy?.toString(),
        )

    override fun toString(): String = "DamageReport(id=$id, status=$status, source=$source)"

    private fun requireStatus(
        action: DamageAction,
        vararg allowed: DamageReportStatus,
    ) {
        if (status !in allowed) throw DamageTransitionNotAllowedException(action, status)
    }

    private fun resolve(
        to: DamageReportStatus,
        by: UserId,
        at: Instant,
    ) {
        status = to
        resolvedBy = by
        resolvedAt = at
        updatedAt = at
    }

    companion object {
        const val MAX_DESCRIPTION = 500
        const val MAX_REASON = 500

        /** O teto do custo estimado e do valor final (D4-E06): 99.999,99. */
        val MAX_AMOUNT: Money = Money.of("99999.99")

        /**
         * Um dano novo, `REPORTED`, com o fato em [occurredAt] na UH [roomId] **ou** na área [areaId] (os dois ou
         * nenhum, `400 HOUSEKEEPING_OCCURRENCE_LOCATION_INVALID`), registrado por [reportedBy], com a reserva sugerida
         * [reservation] (nula numa área). A existência do local e o vínculo são do caso de uso; a [description] é
         * obrigatória, aparada, e o [estimatedCost] opcional, de 0 a [MAX_AMOUNT] (`400 HOUSEKEEPING_DATA_INVALID`).
         * Só a origem `CHECKLIST` leva a execução e o item ([checklistExecutionId], [checklistItemKey]).
         */
        @Suppress("LongParameterList") // os dados do registro
        fun register(
            id: DamageReportId,
            roomId: RoomId?,
            areaId: AreaId?,
            description: String?,
            estimatedCost: Money?,
            occurredAt: Instant,
            reportedBy: UserId,
            source: OccurrenceSource,
            reservation: ReservationId?,
            now: Instant,
            checklistExecutionId: ChecklistExecutionId? = null,
            checklistItemKey: String? = null,
        ): DamageReport {
            HousekeepingRules.requireOneLocation(roomId, areaId)
            require(
                (source == OccurrenceSource.CHECKLIST) == (checklistExecutionId != null) &&
                    (checklistExecutionId == null) == (checklistItemKey == null),
            ) { "Só o registro do checklist leva a execução e o item, e leva os dois" }
            require(reservation == null || roomId != null) { "Dano de área não tem reserva" }
            requireEstimatedCost(estimatedCost)
            return DamageReport(
                id,
                roomId,
                areaId,
                HousekeepingRules.freeText("description", description, MAX_DESCRIPTION),
                estimatedCost,
                occurredAt,
                reportedBy,
                source,
                checklistExecutionId,
                checklistItemKey,
                reservation,
                DamageReportStatus.REPORTED,
                null,
                null,
                null,
                null,
                null,
                now,
                now,
                0,
            )
        }

        /** O dano gravado, como está no banco (persistência). */
        fun restore(
            id: DamageReportId,
            roomId: RoomId?,
            areaId: AreaId?,
            description: String,
            estimatedCost: Money?,
            occurredAt: Instant,
            reportedBy: UserId,
            source: OccurrenceSource,
            checklistExecutionId: ChecklistExecutionId?,
            checklistItemKey: String?,
            reservationId: ReservationId?,
            status: DamageReportStatus,
            review: Review?,
            waiverReason: String?,
            charge: Charge?,
            resolvedAt: Instant?,
            resolvedBy: UserId?,
            createdAt: Instant,
            updatedAt: Instant,
            version: Long,
        ): DamageReport =
            DamageReport(
                id,
                roomId,
                areaId,
                description,
                estimatedCost,
                occurredAt,
                reportedBy,
                source,
                checklistExecutionId,
                checklistItemKey,
                reservationId,
                status,
                review,
                waiverReason,
                charge,
                resolvedAt,
                resolvedBy,
                createdAt,
                updatedAt,
                version,
            )

        /** O valor final da revisão: maior que zero (o dano de valor zero vai à dispensa) e até [MAX_AMOUNT]. */
        fun requireFinalAmount(amount: Money) =
            HousekeepingRules.require(
                amount.isPositive() && amount <= MAX_AMOUNT,
                "finalAmount",
                "Informe o valor final, maior que zero e até $MAX_AMOUNT; um dano sem valor é dispensado.",
            )

        /** O custo estimado: opcional, de zero a [MAX_AMOUNT]. */
        fun requireEstimatedCost(amount: Money?) =
            HousekeepingRules.require(
                amount == null || (!amount.isNegative() && amount <= MAX_AMOUNT),
                "estimatedCost",
                "Informe o custo estimado de 0 a $MAX_AMOUNT.",
            )
    }
}
