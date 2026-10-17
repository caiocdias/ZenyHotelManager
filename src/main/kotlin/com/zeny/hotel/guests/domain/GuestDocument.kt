package com.zeny.hotel.guests.domain

import com.zeny.hotel.guests.DocumentType
import com.zeny.hotel.guests.GuestDocumentId
import com.zeny.hotel.shared.BlindIndexValue
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Documento de identidade de um hóspede (SPEC §8.2.4): tipo, país emissor, o número **cifrado** pelo
 * `FieldCipher` (ARCHITECTURE §8.3) e guardado aqui como texto opaco ([encryptedNumber]: a entidade nunca o
 * decifra), o índice cego ao lado (H8), para a busca por igualdade sem decifrar a tabela, e o **sufixo** em claro
 * ([numberSuffix], os últimos caracteres), para mostrar o número mascarado (H9) sem decifrar nada. Só o caso de
 * uso que revela o número (ou a leitura da FNRH) decifra, um documento de cada vez. Faz parte do agregado
 * [Guest]; sai quando a recepção o retira (correção). [toString] não mostra nem o sufixo.
 */
@Entity
@Table(name = "guest_document", schema = "guests")
@Suppress("LongParameterList") // as colunas do documento; só o agregado Guest o cria
class GuestDocument internal constructor(
    @Id
    val id: GuestDocumentId,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    val type: DocumentType,
    @Column(name = "issuing_country", nullable = false, updatable = false)
    val issuingCountry: String,
    /** O número cifrado (`v1:<chave>:…`); a reindexação da rotação o re-cifra por SQL. */
    @Column(name = "number", nullable = false, updatable = false)
    val encryptedNumber: String,
    @Column(name = "number_suffix", nullable = false, updatable = false)
    val numberSuffix: String,
    index: BlindIndexValue,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    /** A chave do anel de que saiu a chave do índice ([numberIndex]). */
    @Column(name = "number_index_key", nullable = false, updatable = false)
    val numberIndexKey: String = index.keyId

    @Column(name = "number_index", nullable = false, updatable = false)
    val numberIndex: String = index.hash

    /** O número mascarado (H9), do sufixo gravado: nada é decifrado. */
    val masked: String get() = IdDocuments.MASK_PREFIX + numberSuffix

    override fun toString(): String = "GuestDocument(id=$id, type=$type, issuingCountry=$issuingCountry)"
}
