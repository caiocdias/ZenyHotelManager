package com.zeny.hotel.shared

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * Cifragem em nível de campo (ARCHITECTURE §8.3): credenciais de integração, dados sensíveis
 * da FNRH e número de documento. AES-256-GCM com nonce aleatório por valor; a chave fica fora
 * do banco (variáveis `ZENY_ENCRYPTION_KEYS` e `ZENY_ENCRYPTION_ACTIVE_KEY_ID`) e o valor
 * gravado leva o identificador da chave, então valores antigos continuam legíveis depois de
 * uma rotação, enquanto a chave antiga seguir configurada.
 *
 * O valor gravado é texto: `v1:<id da chave>:<base64url(nonce ‖ texto cifrado ‖ tag)>`. Para
 * um texto de `n` bytes em UTF-8, ocupa cerca de `ceil((n + 28) × 4 / 3) + 4 + tamanho do id`
 * caracteres; use `text` na coluna.
 */
interface FieldCipher {
    /** Cifra com a chave ativa. Cifrar o mesmo texto duas vezes dá valores diferentes. */
    fun encrypt(plaintext: String): String

    /** Decifra com a chave indicada no próprio valor; falha com [FieldDecryptionException]. */
    fun decrypt(ciphertext: String): String

    /** O identificador da chave que cifrou o valor. */
    fun keyIdOf(ciphertext: String): String

    /** `false` quando o valor foi cifrado com uma chave que não é mais a ativa (re-cifrar na rotação). */
    fun isEncryptedWithActiveKey(ciphertext: String): Boolean
}

/** Valor cifrado que não pode ser decifrado: formato inválido, chave ausente ou dado adulterado. */
class FieldDecryptionException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Coluna `text` cifrada com o [FieldCipher] da aplicação. Use com
 * `@Convert(converter = EncryptedStringConverter::class)` num atributo `String`. O Hibernate
 * pede o conversor ao Spring, que injeta o [FieldCipher].
 *
 * Coluna cifrada não serve para busca nem para índice: o mesmo texto vira valores diferentes.
 */
@Converter
class EncryptedStringConverter(
    private val cipher: FieldCipher,
) : AttributeConverter<String, String> {
    override fun convertToDatabaseColumn(attribute: String?): String? = attribute?.let(cipher::encrypt)

    override fun convertToEntityAttribute(dbData: String?): String? = dbData?.let(cipher::decrypt)
}
