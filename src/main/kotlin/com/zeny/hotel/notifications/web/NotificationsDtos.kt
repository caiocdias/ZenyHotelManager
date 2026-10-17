package com.zeny.hotel.notifications.web

import com.zeny.hotel.notifications.NotificationPreference
import com.zeny.hotel.notifications.NotificationTypeCode
import com.zeny.hotel.notifications.PushDeactivationReason
import com.zeny.hotel.notifications.PushPlatform
import com.zeny.hotel.notifications.application.NotificationPreferenceView
import com.zeny.hotel.notifications.application.NotificationView
import com.zeny.hotel.notifications.application.PushDeviceView
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.FieldViolationCodes
import com.zeny.hotel.shared.InvalidInputException
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

@Schema(description = "Uma notificação do próprio usuário.")
data class NotificationResponse(
    val id: UUID,
    @field:Schema(description = "Tipo da notificação (`<módulo>.<nome>`), como em `GET /notification-preferences`.")
    val type: String,
    val title: String,
    val body: String,
    val createdAt: Instant,
    @field:Schema(description = "Quando foi lida; nulo se ainda não foi.", nullable = true)
    val readAt: Instant?,
    @field:Schema(description = "Versão da notificação (a do `ETag` e do tópico `notifications`).")
    val version: Long,
) {
    companion object {
        fun of(view: NotificationView) =
            NotificationResponse(
                view.id.value,
                view.type.value,
                view.title,
                view.body,
                view.createdAt,
                view.readAt,
                view.version,
            )
    }
}

@Schema(description = "Quantas notificações do próprio usuário ainda não foram lidas.")
data class UnreadCountResponse(
    val count: Int,
)

@Schema(description = "Quantas notificações foram marcadas como lidas agora.")
data class ReadAllResponse(
    val read: Int,
)

@Schema(description = "A preferência do usuário para um tipo de notificação.")
data class NotificationPreferenceResponse(
    val type: String,
    val description: String,
    @field:Schema(description = "Tipo obrigatório: o painel não desliga; só o push.")
    val mandatory: Boolean,
    @field:Schema(description = "Aparece no painel (e no tópico `notifications`).")
    val inApp: Boolean,
    @field:Schema(description = "Vai por push aos dispositivos ativos (exige `inApp`).")
    val push: Boolean,
) {
    companion object {
        fun of(view: NotificationPreferenceView) =
            NotificationPreferenceResponse(view.type.value, view.description, view.mandatory, view.inApp, view.push)
    }
}

@Schema(description = "As preferências do usuário, uma por tipo do catálogo, em ordem de tipo.")
data class NotificationPreferencesResponse(
    val preferences: List<NotificationPreferenceResponse>,
) {
    companion object {
        fun of(views: List<NotificationPreferenceView>) =
            NotificationPreferencesResponse(views.map(NotificationPreferenceResponse::of))
    }
}

@Schema(description = "A preferência para um tipo.")
data class NotificationPreferenceRequest(
    @field:NotNull
    @field:Size(min = 1, max = NotificationTypeCode.MAX_LENGTH)
    val type: String?,
    @field:NotNull
    val inApp: Boolean?,
    @field:NotNull
    val push: Boolean?,
)

@Schema(description = "As preferências a definir; os tipos que não vierem ficam como estavam.")
data class NotificationPreferencesRequest(
    @field:NotNull
    @field:Size(min = 1, max = MAX_PREFERENCES)
    val preferences: List<@Valid NotificationPreferenceRequest>?,
) {
    fun toPreferences(): List<NotificationPreference> =
        preferences.orEmpty().mapIndexed { index, item ->
            NotificationPreference(
                typeCode(index, checkNotNull(item.type)),
                checkNotNull(item.inApp),
                checkNotNull(item.push),
            )
        }

    private fun typeCode(
        index: Int,
        value: String,
    ): NotificationTypeCode =
        try {
            NotificationTypeCode(value)
        } catch (_: IllegalArgumentException) {
            val message = "Tipo de notificação fora do formato."
            throw InvalidInputException(
                ErrorCodes.VALIDATION_FAILED,
                message,
                listOf(FieldViolation("preferences[$index].type", FieldViolationCodes.INVALID_FORMAT, message)),
            )
        }

    private companion object {
        const val MAX_PREFERENCES = 100
    }
}

@Schema(description = "Registro do token do FCM do aparelho na sessão corrente.")
data class PushDeviceRequest(
    @field:NotNull
    val platform: PushPlatform?,
    @field:Schema(description = "O token de registro do FCM do aparelho. Nunca volta nas respostas.")
    @field:NotNull
    @field:Size(min = 1, max = 4096)
    @field:Pattern(regexp = "[\\x21-\\x7E]+", message = "O token tem só caracteres ASCII visíveis.")
    val token: String?,
)

@Schema(description = "Um dispositivo de push do próprio usuário (sem o token).")
data class PushDeviceResponse(
    val id: UUID,
    val platform: PushPlatform,
    val active: Boolean,
    @field:Schema(description = "Se é o dispositivo da sessão que fez a requisição.")
    val currentSession: Boolean,
    val createdAt: Instant,
    @field:Schema(nullable = true)
    val deactivatedAt: Instant?,
    @field:Schema(
        description =
            "Por que deixou de receber push: `USER` (desativado pelo usuário), `REPLACED` (token ou sessão " +
                "registrados de novo), `INVALID_TOKEN` (recusado pelo FCM), `SESSION_ENDED` (sessão revogada ou " +
                "vencida).",
        nullable = true,
    )
    val deactivationReason: PushDeactivationReason?,
) {
    companion object {
        fun of(view: PushDeviceView) =
            PushDeviceResponse(
                view.id,
                view.platform,
                view.active,
                view.currentSession,
                view.createdAt,
                view.deactivatedAt,
                view.deactivationReason,
            )
    }
}
