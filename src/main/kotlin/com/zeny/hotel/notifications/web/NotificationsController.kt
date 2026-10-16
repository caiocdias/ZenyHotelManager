package com.zeny.hotel.notifications.web

import com.zeny.hotel.notifications.NotificationId
import com.zeny.hotel.notifications.application.NotificationPreferencesService
import com.zeny.hotel.notifications.application.NotificationService
import com.zeny.hotel.notifications.application.PushDevicesService
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.PageParams
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.net.URI
import java.util.UUID

private const val TAG = "notifications"
private const val TAG_DESCRIPTION = "Notificações internas, preferências e dispositivos de push do próprio usuário."

/**
 * O painel de notificações do próprio usuário (RF-NOT-01, M2 E09). Exige só autenticação, sem permissão: cada um
 * vê só as próprias (a de outro usuário responde `404 NOTIFICATION_NOT_FOUND`). As mudanças chegam pelo tópico
 * pessoal `notifications` do `GET /api/v1/stream`.
 */
@ApiV1Controller
@RequestMapping("/notifications")
@Tag(name = TAG, description = TAG_DESCRIPTION)
class NotificationsController(
    private val notifications: NotificationService,
) {
    @GetMapping
    @Operation(
        operationId = "listNotifications",
        summary = "Listar as próprias notificações",
        description = "Da mais nova para a mais antiga. Exige só autenticação.",
    )
    fun list(
        @Parameter(description = "Só as não lidas (`true`); omita ou `false` para todas.")
        @RequestParam(required = false) unread: Boolean?,
        @Valid page: PageParams,
    ): CursorPage<NotificationResponse> = notifications.list(unread == true, page).map(NotificationResponse::of)

    @GetMapping("/unread-count")
    @Operation(
        operationId = "countUnreadNotifications",
        summary = "Contar as próprias notificações não lidas",
        description = "Exige só autenticação.",
    )
    fun unreadCount(): UnreadCountResponse = UnreadCountResponse(notifications.unreadCount())

    @GetMapping("/{id}")
    @Operation(
        operationId = "getNotification",
        summary = "Consultar uma notificação própria",
        description = "Com o `ETag` da versão. De outro usuário ou inexistente: 404 `NOTIFICATION_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<NotificationResponse> {
        val view = notifications.get(NotificationId(id))
        return ResponseEntity.ok().eTag(ETags.of(view.version)).body(NotificationResponse.of(view))
    }

    @PostMapping("/{id}/read")
    @Operation(
        operationId = "readNotification",
        summary = "Marcar uma notificação própria como lida",
        description = "Idempotente: já lida, nada muda. De outro usuário ou inexistente: 404 `NOTIFICATION_NOT_FOUND`.",
    )
    fun read(
        @PathVariable id: UUID,
    ): ResponseEntity<NotificationResponse> {
        val view = notifications.markRead(NotificationId(id))
        return ResponseEntity.ok().eTag(ETags.of(view.version)).body(NotificationResponse.of(view))
    }

    @PostMapping("/read-all")
    @Operation(
        operationId = "readAllNotifications",
        summary = "Marcar todas as próprias notificações como lidas",
        description = "Devolve quantas foram marcadas agora. Exige só autenticação.",
    )
    fun readAll(): ReadAllResponse = ReadAllResponse(notifications.markAllRead())
}

/**
 * As preferências de notificação do próprio usuário (RF-NOT-02): uma por tipo do catálogo, com painel e push
 * ligados por padrão. Exige só autenticação.
 */
@ApiV1Controller
@RequestMapping("/notification-preferences")
@Tag(name = TAG, description = TAG_DESCRIPTION)
class NotificationPreferencesController(
    private val preferences: NotificationPreferencesService,
) {
    @GetMapping
    @Operation(
        operationId = "getNotificationPreferences",
        summary = "Consultar as próprias preferências de notificação",
        description = "Todos os tipos do catálogo, em ordem de tipo; sem preferência gravada, painel e push ligados.",
    )
    fun get(): NotificationPreferencesResponse = NotificationPreferencesResponse.of(preferences.list())

    @PutMapping
    @Operation(
        operationId = "updateNotificationPreferences",
        summary = "Definir as próprias preferências de notificação",
        description =
            "Define os tipos enviados (os outros ficam como estavam) e devolve todos. Sem painel, nada é criado para " +
                "o tipo (nem push). Recusas: 400 `UNKNOWN_NOTIFICATION_TYPE`, 400 `NOTIFICATION_PREFERENCE_INVALID` " +
                "(tipo repetido, push sem painel), 422 `NOTIFICATION_TYPE_MANDATORY` (painel desligado num tipo " +
                "obrigatório).",
    )
    fun update(
        @Valid @RequestBody request: NotificationPreferencesRequest,
    ): NotificationPreferencesResponse = NotificationPreferencesResponse.of(preferences.update(request.toPreferences()))
}

/**
 * Dispositivos de push do próprio usuário (SPEC §8.2.18), vinculados à sessão de dispositivo em que foram
 * registrados: a sessão revogada ou vencida deixa de receber push. Exige só autenticação. Nada é apagado.
 */
@ApiV1Controller
@RequestMapping("/push-devices")
@Tag(name = TAG, description = TAG_DESCRIPTION)
class PushDevicesController(
    private val devices: PushDevicesService,
) {
    @PostMapping
    @Operation(
        operationId = "registerPushDevice",
        summary = "Registrar o token de push do aparelho na sessão corrente",
        description =
            "201 com o dispositivo novo; o mesmo token e plataforma já ativos nesta sessão: 200 com o existente. Um " +
                "token novo nesta sessão, ou este token noutra sessão, desativa o anterior (`REPLACED`). Sessão " +
                "revogada: 401 `UNAUTHENTICATED`.",
    )
    @ApiResponse(responseCode = "201", description = "Dispositivo registrado.")
    fun register(
        @Valid @RequestBody request: PushDeviceRequest,
    ): ResponseEntity<PushDeviceResponse> {
        val registration = devices.register(checkNotNull(request.platform), checkNotNull(request.token))
        val body = PushDeviceResponse.of(registration.device)
        return if (registration.created) {
            ResponseEntity.created(URI.create("/api/v1/push-devices/${body.id}")).body(body)
        } else {
            ResponseEntity.status(HttpStatus.OK).body(body)
        }
    }

    @GetMapping
    @Operation(
        operationId = "listPushDevices",
        summary = "Listar os próprios dispositivos de push",
        description = "Do mais novo para o mais antigo, sem o token. Exige só autenticação.",
    )
    fun list(
        @Parameter(description = "Só os ativos (`true`) ou só os inativos (`false`); omita para todos.")
        @RequestParam(required = false) active: Boolean?,
        @Valid page: PageParams,
    ): CursorPage<PushDeviceResponse> = devices.list(active, page).map(PushDeviceResponse::of)

    @PostMapping("/{id}/deactivate")
    @Operation(
        operationId = "deactivatePushDevice",
        summary = "Desativar um dispositivo de push próprio",
        description = "Idempotente. De outro usuário ou inexistente: 404 `PUSH_DEVICE_NOT_FOUND`.",
    )
    fun deactivate(
        @PathVariable id: UUID,
    ): PushDeviceResponse = PushDeviceResponse.of(devices.deactivate(id))
}
