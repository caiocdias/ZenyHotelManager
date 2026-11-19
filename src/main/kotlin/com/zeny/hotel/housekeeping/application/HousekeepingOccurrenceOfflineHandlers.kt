package com.zeny.hotel.housekeeping.application

import com.zeny.hotel.housekeeping.DamageReportId
import com.zeny.hotel.housekeeping.HousekeepingOffline
import com.zeny.hotel.housekeeping.HousekeepingPermissions
import com.zeny.hotel.housekeeping.LostAndFoundItemId
import com.zeny.hotel.housekeeping.OccurrenceSource
import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.OfflineCommand
import com.zeny.hotel.shared.OfflineCommandHandler
import com.zeny.hotel.shared.OfflineCommandOutcome
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * `housekeeping.report-lost-found` (D6-E06): a UH **ou** a área, a descrição e, opcional, o local de guarda. Os limites
 * são os do registro online, conferidos pelo domínio (`REJECTED` com o código dele).
 */
data class ReportLostFoundPayload(
    val roomId: UUID? = null,
    val areaId: UUID? = null,
    val description: String? = null,
    val storagePlace: String? = null,
)

/**
 * `housekeeping.report-damage` (D6-E06): a UH **ou** a área, a descrição e, opcional, o custo estimado ([Money], em
 * texto decimal como na API online, `"45.50"`): número JSON ou mais de 2 casas são recusados na conversão
 * (`COMMAND_PAYLOAD_INVALID`); o teto de 99999.99 é do domínio (`HOUSEKEEPING_DATA_INVALID`).
 */
data class ReportDamagePayload(
    val roomId: UUID? = null,
    val areaId: UUID? = null,
    val description: String? = null,
    val estimatedCost: Money? = null,
)

/**
 * O handler de `housekeeping.report-lost-found` ([ReportLostFoundPayload], D6-E06): exige
 * `housekeeping.occurrences.report` (qualquer escopo; sem ela, `REJECTED PERMISSION_DENIED`) e registra o achado com o
 * `commandId` como id, origem `OFFLINE`, o `occurredAt` corrigido como o instante em que foi achado e quem enviou como
 * quem registrou. As recusas do registro online (local, textos) dão `REJECTED` com o código delas.
 */
@Component
class ReportLostFoundHandler(
    private val lostAndFound: HousekeepingLostAndFound,
    private val authorization: Authorization,
    private val actor: CurrentActor,
) : OfflineCommandHandler<ReportLostFoundPayload> {
    override val type = HousekeepingOffline.REPORT_LOST_FOUND
    override val payloadType = ReportLostFoundPayload::class.java

    @Audited(HousekeepingAudit.REGISTER_LOST_FOUND_ITEM_OFFLINE)
    @Transactional
    override fun handle(command: OfflineCommand<ReportLostFoundPayload>): OfflineCommandOutcome {
        authorization.require(HousekeepingPermissions.OCCURRENCES_REPORT)
        val payload = command.payload
        lostAndFound.recordOffline(
            LostAndFoundDraft(
                LostAndFoundItemId(command.commandId),
                payload.roomId?.let(::RoomId),
                payload.areaId?.let(::AreaId),
                payload.description,
                payload.storagePlace,
                command.occurredAt,
                actor.current().userId,
                OccurrenceSource.OFFLINE,
            ),
        )
        return OfflineCommandOutcome.APPLIED
    }
}

/**
 * O handler de `housekeeping.report-damage` ([ReportDamagePayload], D6-E06): como o do achado, com o `occurredAt`
 * corrigido como o instante do fato, que decide o vínculo sugerido à reserva (D4-E06). O resultado do comando não leva
 * a reserva nem o hóspede (RN-13, Review Focus 4).
 */
@Component
class ReportDamageHandler(
    private val damages: HousekeepingDamages,
    private val authorization: Authorization,
    private val actor: CurrentActor,
) : OfflineCommandHandler<ReportDamagePayload> {
    override val type = HousekeepingOffline.REPORT_DAMAGE
    override val payloadType = ReportDamagePayload::class.java

    @Audited(HousekeepingAudit.REGISTER_DAMAGE_REPORT_OFFLINE)
    @Transactional
    override fun handle(command: OfflineCommand<ReportDamagePayload>): OfflineCommandOutcome {
        authorization.require(HousekeepingPermissions.OCCURRENCES_REPORT)
        val payload = command.payload
        damages.recordOffline(
            DamageReportDraft(
                DamageReportId(command.commandId),
                payload.roomId?.let(::RoomId),
                payload.areaId?.let(::AreaId),
                payload.description,
                payload.estimatedCost,
                command.occurredAt,
                actor.current().userId,
                OccurrenceSource.OFFLINE,
            ),
        )
        return OfflineCommandOutcome.APPLIED
    }
}
