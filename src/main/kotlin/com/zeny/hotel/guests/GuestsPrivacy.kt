package com.zeny.hotel.guests

import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.UuidV7
import java.util.UUID

@JvmInline
value class GuestConsentId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): GuestConsentId = GuestConsentId(UuidV7.generate())
    }
}

/**
 * Finalidade de um consentimento do titular (RF-HOS-02 e 07, SPEC §8.2.4). Um código publicado não muda; uma
 * finalidade nova entra no fim.
 *
 * - [MARKETING]: comunicações de marketing (ofertas, novidades).
 * - [DIETARY_RESTRICTIONS]: guardar e usar as restrições alimentares (dado de saúde, sensível): sem este
 *   consentimento ativo, o perfil recusa o campo (`422 DIETARY_RESTRICTIONS_REQUIRE_CONSENT`), e revogá-lo apaga
 *   as restrições gravadas.
 */
enum class ConsentPurpose {
    MARKETING,
    DIETARY_RESTRICTIONS,
}

/** Por qual meio o titular deu ou revogou um consentimento (a origem, RF-HOS-07). */
enum class ConsentSource {
    /** No balcão, pessoalmente. */
    IN_PERSON,

    /** Ficha ou termo em papel assinado. */
    PAPER_FORM,
    PHONE,
    EMAIL,

    /** Site ou formulário eletrônico do hotel. */
    WEBSITE,
}

/**
 * Contribuidor de dados pessoais do titular para a exportação (RF-HOS-07, H3 do M3). A porta é do `guests` e a
 * implementam os módulos que guardam dados ligados a um hóspede (o próprio `guests` com o perfil e os
 * consentimentos; `reservations`, `frontdesk` e os demais acrescentam os seus nos marcos deles): cada um publica um
 * bean deste tipo, e `POST /api/v1/guests/{id}/export` junta todos, em ordem de [section].
 *
 * - [section]: o nome estável da seção no pacote, `<módulo>` ou `<módulo>.<assunto>` (minúsculas, dígitos, `.` e
 *   `-`); um nome repetido impede o boot.
 * - [personalData]: os dados do titular que o módulo guarda, para os perfis [guestIds] (o pedido e os absorvidos
 *   por ele em mesclagens, RF-HOS-05): um objeto que vira JSON (data classes, mapas, listas, `Money`, datas), ou
 *   `null` quando o módulo não tem nada deles. Roda na transação da exportação, sem conferir permissão (quem
 *   exporta já se autorizou), e não grava nada.
 *
 * Os anexos não passam por aqui: o pacote os lista à parte, com os metadados, e o conteúdo sai pela URL do `files`.
 */
interface GuestPersonalDataContributor {
    val section: String

    fun personalData(guestIds: List<GuestId>): Any?

    companion object {
        /** O formato de [section]. */
        val SECTION_PATTERN = Regex("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)*")
    }
}

/**
 * Se um hóspede tem estadia ativa (H3 do M3): a anonimização é recusada enquanto alguma implementação responder
 * `true` (`409 GUEST_HAS_ACTIVE_STAY`), porque os dados ainda são necessários à estadia (FNRH, conta). A porta é do
 * `guests` e a implementam os módulos de cima que conhecem estadias (`reservations` e `frontdesk`, M4 e M5); no M3
 * não há implementação, e nada bloqueia. [guestIds]: o perfil pedido e os absorvidos por ele.
 */
interface GuestActiveStayCheck {
    fun hasActiveStay(guestIds: List<GuestId>): Boolean
}
