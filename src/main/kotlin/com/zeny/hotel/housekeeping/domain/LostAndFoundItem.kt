package com.zeny.hotel.housekeeping.domain

import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.housekeeping.HousekeepingErrorCodes
import com.zeny.hotel.housekeeping.LostAndFoundItemId
import com.zeny.hotel.housekeeping.LostAndFoundStatus
import com.zeny.hotel.housekeeping.OccurrenceSource
import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.UserId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** As ações sobre um item de achados e perdidos (D2-E06): os destinos e a troca do local de guarda. */
enum class LostAndFoundAction {
    RETURN,
    DISCARD,
    DONATE,
    CHANGE_STORAGE_PLACE,
}

/** `409 HOUSEKEEPING_LOST_FOUND_TRANSITION_NOT_ALLOWED`, com o status em `itemStatus` e o destino em `action`. */
class LostAndFoundTransitionNotAllowedException(
    val action: LostAndFoundAction,
    val status: LostAndFoundStatus,
) : ConflictException(
        HousekeepingErrorCodes.HOUSEKEEPING_LOST_FOUND_TRANSITION_NOT_ALLOWED,
        "Só um item guardado pode ser devolvido, descartado, doado ou mudar de local de guarda.",
    ) {
    override val problemProperties: Map<String, Any>
        get() = mapOf("itemStatus" to status.name, "action" to action.name)
}

/**
 * Um item de achados e perdidos (RF-GOV-08, SPEC §8.2.10, D2-E06): achado numa UH **ou** numa área, com a [description]
 * (texto livre), o [storagePlace] (texto curto, opcional), quem o registrou ([reportedBy], o dono no escopo `OWN`) e
 * quando foi achado ([foundAt]). Ciclo `STORED → RETURNED | DISCARDED | DONATED`: a devolução vale a qualquer tempo e
 * grava a quem ([returnedTo], texto livre, sem ligação com hóspede ou reserva, que nunca vai à trilha); o descarte e a
 * doação só com o item vencido ([isExpired]) e gravam o motivo; os três gravam quando e por quem. Nunca é apagado.
 */
@Suppress("LongParameterList") // os campos do item, só pelo register e pelo restore
class LostAndFoundItem private constructor(
    val id: LostAndFoundItemId,
    val roomId: RoomId?,
    val areaId: AreaId?,
    val description: String,
    storagePlace: String?,
    val foundAt: Instant,
    val reportedBy: UserId,
    val source: OccurrenceSource,
    val checklistExecutionId: ChecklistExecutionId?,
    val checklistItemKey: String?,
    status: LostAndFoundStatus,
    returnedTo: String?,
    disposalReason: String?,
    resolvedAt: Instant?,
    resolvedBy: UserId?,
    val createdAt: Instant,
    updatedAt: Instant,
    version: Long,
) {
    var status: LostAndFoundStatus = status
        private set

    var storagePlace: String? = storagePlace
        private set

    var returnedTo: String? = returnedTo
        private set

    var disposalReason: String? = disposalReason
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
     * Se o item guardado venceu o prazo de guarda (D2-E06): a data local de [foundAt] no [zone] do hotel mais
     * [retentionDays] não passa da [businessDate] (a data operacional). Fora de `STORED`, nunca.
     */
    fun isExpired(
        businessDate: LocalDate,
        zone: ZoneId,
        retentionDays: Int,
    ): Boolean =
        status == LostAndFoundStatus.STORED &&
            !foundAt
                .atZone(zone)
                .toLocalDate()
                .plusDays(retentionDays.toLong())
                .isAfter(businessDate)

    /** Define ou troca o local de guarda do item guardado ([place], obrigatório, até [MAX_STORAGE_PLACE]), em [at]. */
    fun changeStoragePlace(
        place: String?,
        at: Instant,
    ) {
        requireStored(LostAndFoundAction.CHANGE_STORAGE_PLACE)
        storagePlace = HousekeepingRules.freeText("storagePlace", place, MAX_STORAGE_PLACE)
        updatedAt = at
    }

    /** Devolve o item guardado a [person] (texto livre, até [MAX_RETURNED_TO]), por [by], em [at]. */
    fun returnTo(
        person: String?,
        by: UserId,
        at: Instant,
    ) {
        requireStored(LostAndFoundAction.RETURN)
        returnedTo = HousekeepingRules.freeText("returnedTo", person, MAX_RETURNED_TO)
        resolve(LostAndFoundStatus.RETURNED, by, at)
    }

    /**
     * Descarta ou doa ([action]) o item guardado com o [reason], por [by], em [at]; só se [expired] (o prazo de guarda
     * passou), senão `409 HOUSEKEEPING_LOST_FOUND_NOT_EXPIRED`.
     */
    fun dispose(
        action: LostAndFoundAction,
        reason: String?,
        expired: Boolean,
        by: UserId,
        at: Instant,
    ) {
        require(action == LostAndFoundAction.DISCARD || action == LostAndFoundAction.DONATE) { "Só descarte ou doação" }
        requireStored(action)
        if (!expired) {
            throw ConflictException(
                HousekeepingErrorCodes.HOUSEKEEPING_LOST_FOUND_NOT_EXPIRED,
                "O item ainda está no prazo de guarda: só depois dele pode ser descartado ou doado.",
            )
        }
        disposalReason = HousekeepingRules.freeText("reason", reason, MAX_REASON)
        resolve(
            if (action == LostAndFoundAction.DISCARD) LostAndFoundStatus.DISCARDED else LostAndFoundStatus.DONATED,
            by,
            at,
        )
    }

    /**
     * O retrato da trilha: ids, local, estado e quando, com os textos livres só pelo tamanho (descrição, local de
     * guarda e motivo) e **sem** a quem foi devolvido (D2-E06).
     */
    fun auditState(): Map<String, Any?> =
        mapOf(
            "roomId" to roomId?.toString(),
            "areaId" to areaId?.toString(),
            "descriptionLength" to description.codePointCount(0, description.length),
            "storagePlaceLength" to storagePlace?.let { it.codePointCount(0, it.length) },
            "foundAt" to foundAt.toString(),
            "reportedBy" to reportedBy.toString(),
            "source" to source.name,
            "checklistExecutionId" to checklistExecutionId?.toString(),
            "checklistItemKey" to checklistItemKey,
            "status" to status.name,
            "disposalReasonLength" to disposalReason?.let { it.codePointCount(0, it.length) },
            "resolvedAt" to resolvedAt?.toString(),
            "resolvedBy" to resolvedBy?.toString(),
        )

    override fun toString(): String = "LostAndFoundItem(id=$id, status=$status, source=$source)"

    private fun requireStored(action: LostAndFoundAction) {
        if (status != LostAndFoundStatus.STORED) throw LostAndFoundTransitionNotAllowedException(action, status)
    }

    private fun resolve(
        to: LostAndFoundStatus,
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
        const val MAX_STORAGE_PLACE = 100
        const val MAX_RETURNED_TO = 200
        const val MAX_REASON = 500

        /**
         * Um item novo, `STORED`, achado em [foundAt] na UH [roomId] **ou** na área [areaId] (os dois ou nenhum, `400
         * HOUSEKEEPING_OCCURRENCE_LOCATION_INVALID`), registrado por [reportedBy]. A existência do local é do caso de
         * uso; a [description] é obrigatória e o [storagePlace] opcional, aparados (`400 HOUSEKEEPING_DATA_INVALID`).
         */
        @Suppress("LongParameterList") // os dados do registro
        fun register(
            id: LostAndFoundItemId,
            roomId: RoomId?,
            areaId: AreaId?,
            description: String?,
            storagePlace: String?,
            reportedBy: UserId,
            foundAt: Instant,
            source: OccurrenceSource,
            now: Instant,
        ): LostAndFoundItem {
            HousekeepingRules.requireOneLocation(roomId, areaId)
            require(source != OccurrenceSource.CHECKLIST) { "O registro do checklist leva a execução e o item" }
            return LostAndFoundItem(
                id,
                roomId,
                areaId,
                HousekeepingRules.freeText("description", description, MAX_DESCRIPTION),
                HousekeepingRules.optionalFreeText("storagePlace", storagePlace, MAX_STORAGE_PLACE),
                foundAt,
                reportedBy,
                source,
                null,
                null,
                LostAndFoundStatus.STORED,
                null,
                null,
                null,
                null,
                now,
                now,
                0,
            )
        }

        /** O item gravado, como está no banco (persistência). */
        fun restore(
            id: LostAndFoundItemId,
            roomId: RoomId?,
            areaId: AreaId?,
            description: String,
            storagePlace: String?,
            foundAt: Instant,
            reportedBy: UserId,
            source: OccurrenceSource,
            checklistExecutionId: ChecklistExecutionId?,
            checklistItemKey: String?,
            status: LostAndFoundStatus,
            returnedTo: String?,
            disposalReason: String?,
            resolvedAt: Instant?,
            resolvedBy: UserId?,
            createdAt: Instant,
            updatedAt: Instant,
            version: Long,
        ): LostAndFoundItem =
            LostAndFoundItem(
                id,
                roomId,
                areaId,
                description,
                storagePlace,
                foundAt,
                reportedBy,
                source,
                checklistExecutionId,
                checklistItemKey,
                status,
                returnedTo,
                disposalReason,
                resolvedAt,
                resolvedBy,
                createdAt,
                updatedAt,
                version,
            )

        /**
         * O primeiro instante em que um item achado **não** está vencido na [businessDate] (D2-E06): achado antes dele
         * (no [zone] do hotel), venceu. Equivale a [isExpired], para filtrar na consulta.
         */
        fun expiryCutoff(
            businessDate: LocalDate,
            zone: ZoneId,
            retentionDays: Int,
        ): Instant = businessDate.minusDays(retentionDays.toLong() - 1).atStartOfDay(zone).toInstant()
    }
}
