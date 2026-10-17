package com.zeny.hotel.guests.application

import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.domain.Company
import com.zeny.hotel.guests.domain.Guest
import com.zeny.hotel.guests.domain.GuestConsent
import com.zeny.hotel.guests.domain.GuestDocument

/**
 * Tipos de entidade, ações e retratos do `guests` na trilha de auditoria (RF-ACS-11), conforme a H10 do M3:
 * **nenhum dado pessoal em claro**. Os retratos levam só ids, situação, flags (VIP, restrição, dados sensíveis
 * gravados), o tipo e o país dos documentos e a lista dos campos alterados (`changedFields`); nome, contatos,
 * endereço, número de documento, motivo da restrição, observações e os dados sensíveis da FNRH ficam fora.
 * Assim a anonimização (E03) não deixa dado pessoal na trilha *append-only*.
 */
internal object GuestsAudit {
    const val GUEST = "guests.guest"
    const val GUEST_DOCUMENT = "guests.guest-document"
    const val COMPANY = "guests.company"
    const val GUEST_CONSENT = "guests.guest-consent"
    const val GUEST_ATTACHMENT = "guests.guest-attachment"

    const val CREATE_GUEST = "guests.guest.create"
    const val UPDATE_GUEST = "guests.guest.update"
    const val RECORD_SENSITIVE_DATA = "guests.guest.record-sensitive-data"
    const val RESTRICT_GUEST = "guests.guest.restrict"
    const val LIFT_RESTRICTION = "guests.guest.lift-restriction"
    const val MERGE_GUEST = "guests.guest.merge"
    const val EXPORT_GUEST = "guests.guest.export"
    const val ANONYMIZE_GUEST = "guests.guest.anonymize"
    const val GRANT_CONSENT = "guests.guest-consent.grant"
    const val REVOKE_CONSENT = "guests.guest-consent.revoke"
    const val ATTACH_FILE = "guests.guest-attachment.attach"
    const val REMOVE_ATTACHMENT = "guests.guest-attachment.remove"
    const val ADD_DOCUMENT = "guests.guest-document.add"
    const val REMOVE_DOCUMENT = "guests.guest-document.remove"
    const val REVEAL_DOCUMENT = "guests.guest-document.reveal"
    const val CREATE_COMPANY = "guests.company.create"
    const val UPDATE_COMPANY = "guests.company.update"
    const val DEACTIVATE_COMPANY = "guests.company.deactivate"
    const val REACTIVATE_COMPANY = "guests.company.reactivate"

    const val CHANGED_FIELDS = "changedFields"

    /** A origem de uma correção feita a pedido do titular (RF-HOS-07), no retrato depois da mudança. */
    const val ORIGIN = "origin"
    const val DATA_SUBJECT_REQUEST = "DATA_SUBJECT_REQUEST"
}

/** O perfil na trilha (H10): ids, situação, flags e documentos sem número. */
internal fun Guest.auditState(): Map<String, Any?> =
    mapOf(
        "status" to status.name,
        "mergedInto" to mergedInto?.toString(),
        "anonymized" to anonymized,
        "vip" to vip,
        "restricted" to restricted,
        "companyId" to companyId?.toString(),
        "hasSensitiveData" to hasSensitiveData,
        "hasDietaryRestrictions" to (dietaryRestrictions != null),
        "documents" to documents.map { it.auditState() },
    )

/** O consentimento na trilha: finalidade, situação e as origens (sem dado pessoal). */
internal fun GuestConsent.auditState(guestId: GuestId): Map<String, Any?> =
    mapOf(
        "guestId" to guestId.toString(),
        "purpose" to purpose.name,
        "active" to active,
        "grantedSource" to grantedSource.name,
        "revokedSource" to revokedSource?.name,
        "superseded" to (supersededAt != null),
    )

/** O vínculo de um anexo na trilha: o perfil e a finalidade (o `files` audita o anexo em si). */
internal fun GuestAttachmentRow.auditState(): Map<String, Any?> =
    mapOf("guestId" to guestId.toString(), "purpose" to purpose.value)

/** O documento na trilha: id, tipo e país emissor (nunca o número). */
internal fun GuestDocument.auditState(): Map<String, Any?> =
    mapOf("id" to id.toString(), "type" to type.name, "issuingCountry" to issuingCountry)

/** A empresa na trilha: CNPJ, situação e limite de crédito; nomes, contatos e endereço ficam fora. */
internal fun Company.auditState(): Map<String, Any?> =
    mapOf(
        "cnpj" to cnpj.value,
        "active" to active,
        "creditLimit" to creditLimit?.toString(),
    )

/** [state] com a lista dos campos alterados. */
internal fun withChangedFields(
    state: Map<String, Any?>,
    changed: List<String>,
): Map<String, Any?> = state + (GuestsAudit.CHANGED_FIELDS to changed)
