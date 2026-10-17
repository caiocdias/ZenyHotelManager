package com.zeny.hotel.guests.web

import com.zeny.hotel.guests.ConsentPurpose
import com.zeny.hotel.guests.ConsentSource
import com.zeny.hotel.guests.application.ConsentCommand
import com.zeny.hotel.guests.application.ConsentView
import com.zeny.hotel.guests.application.GuestAttachmentView
import com.zeny.hotel.guests.application.GuestDataExportView
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.util.UUID

@Schema(description = "Um consentimento do titular: a finalidade e o meio pelo qual ele o deu.")
data class ConsentRequest(
    @field:NotNull
    val purpose: ConsentPurpose?,
    @field:Schema(description = "Por qual meio o titular deu o consentimento (a origem).")
    @field:NotNull
    val source: ConsentSource?,
) {
    fun toCommand() = ConsentCommand(checkNotNull(purpose), checkNotNull(source))
}

@Schema(description = "A revogação de um consentimento: o meio pelo qual o titular a pediu.")
data class RevokeConsentRequest(
    @field:NotNull
    val source: ConsentSource?,
)

@Schema(
    description =
        "Um consentimento do titular (RF-HOS-07): quando e por qual meio foi dado e, se for o caso, revogado. Nunca " +
            "é apagado (prova): um consentimento novo da mesma finalidade é outro registro.",
)
data class ConsentResponse(
    val id: UUID,
    val guestId: UUID,
    val purpose: ConsentPurpose,
    @field:Schema(description = "Se ainda vale (não foi revogado nem substituído numa mesclagem).")
    val active: Boolean,
    val grantedAt: Instant,
    val grantedSource: ConsentSource,
    @field:Schema(description = "Quem registrou o consentimento (nulo: o sistema).")
    val grantedBy: UUID?,
    val revokedAt: Instant?,
    val revokedSource: ConsentSource?,
    val revokedBy: UUID?,
    @field:Schema(
        description =
            "Quando deixou de valer numa mesclagem de duplicados, por haver no perfil sobrevivente um consentimento " +
                "ativo da mesma finalidade (`supersededBy`), que prevalece; não é uma revogação do titular.",
    )
    val supersededAt: Instant?,
    val supersededBy: UUID?,
) {
    companion object {
        fun of(view: ConsentView) =
            ConsentResponse(
                id = view.id.value,
                guestId = view.guestId.value,
                purpose = view.purpose,
                active = view.active,
                grantedAt = view.grantedAt,
                grantedSource = view.grantedSource,
                grantedBy = view.grantedBy?.value,
                revokedAt = view.revokedAt,
                revokedSource = view.revokedSource,
                revokedBy = view.revokedBy?.value,
                supersededAt = view.supersededAt,
                supersededBy = view.supersededBy?.value,
            )
    }
}

@Schema(description = "Os consentimentos de um perfil, do mais recente ao mais antigo.")
data class ConsentListResponse(
    val items: List<ConsentResponse>,
)

@Schema(description = "O vínculo de um anexo já enviado (`POST /api/v1/files`) ao perfil.")
data class AttachGuestFileRequest(
    @field:Schema(description = "O id do anexo, de uma finalidade de hóspede (`guests.…`).")
    @field:NotNull
    val fileId: UUID?,
)

@Schema(
    description =
        "Um anexo do hóspede (RF-HOS-08), sensível: o conteúdo sai pela URL de " +
            "`GET /api/v1/files/{fileId}/download-url` (com `guests.attachments.read`), e cada URL emitida e cada " +
            "download ficam na trilha.",
)
data class GuestAttachmentResponse(
    val fileId: UUID,
    val guestId: UUID,
    @field:Schema(description = "A finalidade do anexo.", example = "guests.id-document-photo")
    val purpose: String,
    val contentType: String,
    val sizeBytes: Long,
    @field:Schema(description = "Quem enviou o arquivo.")
    val uploadedBy: UUID?,
    val uploadedAt: Instant,
    val attachedAt: Instant,
    @field:Schema(description = "Quem vinculou o anexo ao perfil.")
    val attachedBy: UUID?,
) {
    companion object {
        fun of(view: GuestAttachmentView) =
            GuestAttachmentResponse(
                fileId = view.fileId.value,
                guestId = view.guestId.value,
                purpose = view.purpose.value,
                contentType = view.contentType,
                sizeBytes = view.sizeBytes,
                uploadedBy = view.uploadedBy?.value,
                uploadedAt = view.uploadedAt,
                attachedAt = view.attachedAt,
                attachedBy = view.attachedBy?.value,
            )
    }
}

@Schema(description = "Os anexos de um perfil, em ordem de vínculo.")
data class GuestAttachmentListResponse(
    val items: List<GuestAttachmentResponse>,
)

@Schema(description = "Uma seção do pacote: os dados que um módulo guarda do titular.")
data class PersonalDataSectionResponse(
    @field:Schema(description = "O módulo (e o assunto) que contribuiu com os dados.", example = "guests")
    val name: String,
    @field:Schema(description = "Os dados, no formato do módulo.")
    val data: Any,
) {
    override fun toString(): String = "PersonalDataSectionResponse(name=$name)"
}

@Schema(
    description =
        "O pacote com os dados pessoais do titular (RF-HOS-07): o perfil pedido e os absorvidos por ele em " +
            "mesclagens, uma seção por módulo que guarda dados dele e os anexos (o conteúdo de cada um sai pela URL " +
            "de `GET /api/v1/files/{fileId}/download-url`). É o pacote completo (LGPD art. 18): os documentos com o " +
            "número inteiro e os dados sensíveis da FNRH (raça/cor e deficiência) decifrados, só para o titular. Não " +
            "é guardado no servidor nem vai para a trilha.",
)
data class GuestDataExportResponse(
    val guestId: UUID,
    @field:Schema(description = "Os perfis incluídos: o pedido e os absorvidos por ele.")
    val guestIds: List<UUID>,
    val generatedAt: Instant,
    val sections: List<PersonalDataSectionResponse>,
    val attachments: List<GuestAttachmentResponse>,
) {
    override fun toString(): String = "GuestDataExportResponse(guestId=$guestId)"

    companion object {
        fun of(view: GuestDataExportView) =
            GuestDataExportResponse(
                guestId = view.guestId.value,
                guestIds = view.guestIds.map { it.value },
                generatedAt = view.generatedAt,
                sections = view.sections.map { PersonalDataSectionResponse(it.name, it.data) },
                attachments = view.attachments.map(GuestAttachmentResponse::of),
            )
    }
}
