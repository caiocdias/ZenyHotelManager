package com.zeny.hotel.notifications.application

import com.zeny.hotel.notifications.NotificationsErrorCodes
import com.zeny.hotel.notifications.PushDeactivationReason
import com.zeny.hotel.notifications.PushPlatform
import com.zeny.hotel.notifications.domain.PushDevice
import com.zeny.hotel.notifications.domain.PushDeviceId
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.DeviceSessionStatus
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.UnauthenticatedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Um dispositivo de push como o dono o vê: sem o token. */
data class PushDeviceView(
    val id: UUID,
    val platform: PushPlatform,
    val active: Boolean,
    val currentSession: Boolean,
    val createdAt: Instant,
    val deactivatedAt: Instant?,
    val deactivationReason: PushDeactivationReason?,
)

/** O resultado do registro: o dispositivo e se ele foi criado agora (ou já existia, com o mesmo token e sessão). */
data class PushDeviceRegistration(
    val device: PushDeviceView,
    val created: Boolean,
)

/**
 * Dispositivos de push do próprio usuário (SPEC §8.2.18), sem exigir permissão: registrar o token do FCM na
 * sessão corrente, listar e desativar. Uma sessão e um token têm no máximo um dispositivo ativo: registrar de
 * novo o mesmo token na mesma sessão devolve o existente; um token novo na sessão, ou o mesmo token noutra
 * sessão (de qualquer usuário: o aparelho trocou de mãos), desativa o anterior (`REPLACED`).
 */
@Service
class PushDevicesService(
    private val devices: PushDeviceStore,
    private val currentActor: CurrentActor,
    private val sessions: DeviceSessionStatus,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    @Audited(NotificationsAudit.REGISTER_DEVICE)
    @Transactional
    fun register(
        platform: PushPlatform,
        token: String,
    ): PushDeviceRegistration {
        val actor = currentActor.current()
        // O access token de uma sessão revogada ainda vale até vencer: ela não registra dispositivo.
        if (sessions.isRevoked(actor.deviceSessionId)) {
            throw UnauthenticatedException(ErrorCodes.UNAUTHENTICATED, "Sessão encerrada. Entre de novo.")
        }
        val now = clock.instant()
        val device = PushDevice.register(actor.userId, actor.deviceSessionId, platform, token, now)
        devices.lockRegistrations()
        val existing = devices.activeByTokenOrSession(device.tokenSha256, actor.deviceSessionId.value)
        existing
            .firstOrNull {
                it.sessionId == actor.deviceSessionId && it.tokenSha256 == device.tokenSha256 &&
                    it.platform == platform
            }?.let { same ->
                audit.noChanges()
                return PushDeviceRegistration(same.view(actor.deviceSessionId.value), created = false)
            }
        existing.forEach { previous ->
            audit.track(NotificationsAudit.PUSH_DEVICE, previous.id.value) { previous.auditState() }
            previous.deactivate(PushDeactivationReason.REPLACED, now)
            devices.update(previous)
        }
        audit.created(NotificationsAudit.PUSH_DEVICE, device.id.value) { device.auditState() }
        devices.add(device)
        return PushDeviceRegistration(device.view(actor.deviceSessionId.value), created = true)
    }

    @Transactional(readOnly = true)
    fun list(
        active: Boolean?,
        page: PageParams,
    ): CursorPage<PushDeviceView> {
        val actor = currentActor.current()
        val rows = devices.page(actor.userId, active, page.decodedCursor()?.uuid(0), page.limit + 1)
        return CursorPage
            .fromRows(rows, page.limit) { PageCursor.of(it.id.toString()) }
            .map { it.view(actor.deviceSessionId.value) }
    }

    @Audited(NotificationsAudit.DEACTIVATE_DEVICE)
    @Transactional
    fun deactivate(deviceId: UUID): PushDeviceView {
        val actor = currentActor.current()
        val id = PushDeviceId(deviceId)
        devices.findOf(actor.userId, id) ?: throw notFound()
        val device = devices.findForUpdate(id) ?: throw notFound()
        audit.track(NotificationsAudit.PUSH_DEVICE, id.value) { device.auditState() }
        if (device.deactivate(PushDeactivationReason.USER, clock.instant())) devices.update(device)
        return device.view(actor.deviceSessionId.value)
    }

    private fun notFound() =
        NotFoundException(NotificationsErrorCodes.PUSH_DEVICE_NOT_FOUND, "Dispositivo de push não encontrado.")
}

/**
 * Desativação de um dispositivo pelo sistema durante o envio (token recusado pelo FCM, sessão encerrada), cada
 * uma na sua transação: o reprocessamento do envio pelo outbox não a desfaz. Auditada com ator sistema.
 */
@Service
class PushDeviceDiscards(
    private val devices: PushDeviceStore,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    @Audited(NotificationsAudit.DISCARD_DEVICE)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun discard(
        id: PushDeviceId,
        reason: PushDeactivationReason,
    ) {
        val device = devices.findForUpdate(id) ?: return audit.noChanges()
        audit.track(NotificationsAudit.PUSH_DEVICE, id.value) { device.auditState() }
        if (device.deactivate(reason, clock.instant())) devices.update(device)
    }
}

internal fun PushDevice.view(currentSessionId: UUID) =
    PushDeviceView(
        id.value,
        platform,
        active,
        sessionId.value == currentSessionId,
        createdAt,
        deactivatedAt,
        deactivationReason,
    )
