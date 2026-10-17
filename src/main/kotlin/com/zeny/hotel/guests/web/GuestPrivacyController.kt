package com.zeny.hotel.guests.web

import com.zeny.hotel.files.FileId
import com.zeny.hotel.guests.GuestConsentId
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.application.GuestAttachmentService
import com.zeny.hotel.guests.application.GuestConsentService
import com.zeny.hotel.guests.application.GuestDataSubjectService
import com.zeny.hotel.shared.APPROVAL_TOKEN_HEADER
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.ETags
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import java.util.UUID

private const val CHAIN =
    "Vale para o titular inteiro: o perfil e os absorvidos por ele em mesclagens. Num perfil mesclado: 409 " +
        "`GUEST_ALREADY_MERGED` (faça o pedido no sobrevivente). Inexistente: 404 `GUEST_NOT_FOUND`."
private const val STATE_CONFLICT =
    "Num perfil mesclado: 409 `GUEST_ALREADY_MERGED`; num anonimizado, 409 `GUEST_ANONYMIZED`."

/**
 * LGPD no perfil do hóspede (RF-HOS-02, 07 e 08, RF-ACS-08): consentimentos por finalidade, anexos sensíveis e os
 * direitos do titular sob pedido (exportar e anonimizar, ações sensíveis com aprovação de superior).
 */
@ApiV1Controller
@RequestMapping("/guests")
@Tag(name = GUESTS_TAG, description = GUESTS_TAG_DESCRIPTION)
class GuestPrivacyController(
    private val consents: GuestConsentService,
    private val attachments: GuestAttachmentService,
    private val dataSubject: GuestDataSubjectService,
) {
    @GetMapping("/{id}/consents")
    @Operation(
        operationId = "listGuestConsents",
        summary = "Listar os consentimentos de um perfil",
        description =
            "Todos, inclusive os revogados, do mais recente ao mais antigo (RF-HOS-07). Exige " +
                "`guests.profiles.read`.",
    )
    fun listConsents(
        @PathVariable id: UUID,
    ): ConsentListResponse = ConsentListResponse(consents.consents(GuestId(id)).map(ConsentResponse::of))

    @PostMapping("/{id}/consents")
    @Operation(
        operationId = "grantGuestConsent",
        summary = "Registrar um consentimento",
        description =
            "O consentimento do titular para a finalidade, com o meio (a origem) e o instante. Já havendo um ativo " +
                "da mesma finalidade, devolve-o (200) sem mudar nada. Exige `guests.profiles.manage`. " +
                STATE_CONFLICT,
    )
    @ApiResponse(responseCode = "201", description = "Consentimento registrado.")
    fun grantConsent(
        @PathVariable id: UUID,
        @Valid @RequestBody request: ConsentRequest,
    ): ResponseEntity<ConsentResponse> {
        val result = consents.grant(GuestId(id), request.toCommand())
        return ResponseEntity
            .status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .body(ConsentResponse.of(result.consent))
    }

    @PostMapping("/{id}/consents/{consentId}/revoke")
    @Operation(
        operationId = "revokeGuestConsent",
        summary = "Revogar um consentimento",
        description =
            "Marca a revogação com o meio e o instante (o registro fica, como prova). Revogar o de " +
                "`DIETARY_RESTRICTIONS` apaga as restrições alimentares gravadas. Já revogado: nada muda. De outro " +
                "perfil ou inexistente: 404 `CONSENT_NOT_FOUND`. Exige `guests.profiles.manage`. Nunca é " +
                "recusada pela situação do perfil (LGPD art. 8º §5º): vale num perfil anonimizado e num mesclado, " +
                "e pelo id de um mesclado o consentimento é procurado também na cadeia até o sobrevivente, para " +
                "onde a mesclagem o levou.",
    )
    fun revokeConsent(
        @PathVariable id: UUID,
        @PathVariable consentId: UUID,
        @Valid @RequestBody request: RevokeConsentRequest,
    ): ConsentResponse =
        ConsentResponse.of(consents.revoke(GuestId(id), GuestConsentId(consentId), checkNotNull(request.source)))

    @GetMapping("/{id}/attachments")
    @Operation(
        operationId = "listGuestAttachments",
        summary = "Listar os anexos de um perfil",
        description =
            "Documento de identidade e autorização de menor (RF-HOS-08), em ordem de vínculo, com os metadados. O " +
                "conteúdo sai por `GET /api/v1/files/{fileId}/download-url` (URL de curta duração do servidor; cada " +
                "URL emitida e cada download ficam na trilha). Exige `guests.attachments.read`.",
    )
    fun listAttachments(
        @PathVariable id: UUID,
    ): GuestAttachmentListResponse =
        GuestAttachmentListResponse(attachments.attachments(GuestId(id)).map(GuestAttachmentResponse::of))

    @PostMapping("/{id}/attachments")
    @Operation(
        operationId = "attachGuestFile",
        summary = "Vincular um anexo ao perfil",
        description =
            "O anexo já enviado por `POST /api/v1/files` com uma finalidade de hóspede (`guests.id-document-photo`, " +
                "`guests.minor-authorization-photo` ou `guests.document-pdf`, sensíveis). Já vinculado a este " +
                "perfil: 200, sem mudar nada. Inexistente, de outra finalidade ou de outro registro: 422 " +
                "`GUEST_ATTACHMENT_INVALID`. Exige `guests.attachments.manage`. $STATE_CONFLICT",
    )
    @ApiResponse(responseCode = "201", description = "Anexo vinculado.")
    fun attach(
        @PathVariable id: UUID,
        @Valid @RequestBody request: AttachGuestFileRequest,
    ): ResponseEntity<GuestAttachmentResponse> {
        val result = attachments.attach(GuestId(id), FileId(checkNotNull(request.fileId)))
        return ResponseEntity
            .status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .body(GuestAttachmentResponse.of(result.attachment))
    }

    @PostMapping("/{id}/attachments/{fileId}/remove")
    @Operation(
        operationId = "removeGuestAttachment",
        summary = "Retirar um anexo do perfil",
        description =
            "Apaga o anexo (o arquivo sai do armazenamento). Não vinculado a este perfil: 404 " +
                "`GUEST_ATTACHMENT_NOT_FOUND`. Exige `guests.attachments.manage`.",
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "Anexo retirado e apagado.")
    fun removeAttachment(
        @PathVariable id: UUID,
        @PathVariable fileId: UUID,
    ) {
        attachments.remove(GuestId(id), FileId(fileId))
    }

    @PostMapping("/{id}/export")
    @Operation(
        operationId = "exportGuestData",
        summary = "Exportar os dados do titular",
        description =
            "O pacote completo com os dados pessoais do titular (inclusive o número dos documentos e os dados " +
                "sensíveis da FNRH), na própria resposta (nada fica guardado; sem cache). Ação " +
                "sensível: sem `guests.data.export`, 403 `APPROVAL_REQUIRED`, e a requisição repetida com o " +
                "`X-Approval-Token` de um superior é concluída. O acesso fica na trilha (sem os dados exportados). " +
                CHAIN,
    )
    fun export(
        @PathVariable id: UUID,
        @RequestHeader(APPROVAL_TOKEN_HEADER, required = false) approvalToken: String?,
    ): ResponseEntity<GuestDataExportResponse> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(GuestDataExportResponse.of(dataSubject.export(GuestId(id), approvalToken)))

    @PostMapping("/{id}/anonymize")
    @Operation(
        operationId = "anonymizeGuest",
        summary = "Anonimizar o perfil a pedido do titular",
        description =
            "Irreversível (RF-HOS-07). Ficam o id, a situação `ANONYMIZED`, os consentimentos, a nacionalidade, o " +
                "país de residência, a UF e o município e o VIP; saem o nome, os contatos, o endereço de rua, a data " +
                "de nascimento, o gênero, as preferências, as observações, as restrições alimentares, a empresa, a " +
                "restrição, os dados sensíveis da FNRH, os documentos e os anexos. Ação sensível: sem " +
                "`guests.data.anonymize`, 403 `APPROVAL_REQUIRED`, e a requisição repetida com o `X-Approval-Token` " +
                "de um superior é concluída. Com estadia ativa: 409 `GUEST_HAS_ACTIVE_STAY`; já anonimizado: 409 " +
                "`GUEST_ANONYMIZED`. $CHAIN",
    )
    fun anonymize(
        @PathVariable id: UUID,
        @RequestHeader(APPROVAL_TOKEN_HEADER, required = false) approvalToken: String?,
    ): ResponseEntity<GuestResponse> {
        val view = dataSubject.anonymize(GuestId(id), approvalToken)
        return ResponseEntity.ok().eTag(ETags.of(view.version)).body(GuestResponse.of(view))
    }
}
