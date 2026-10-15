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

/**
 * Cifragem de conteúdo binário (ARCHITECTURE §8.3): os anexos sensíveis (documentos de hóspede,
 * por exemplo) antes de irem ao armazenamento. Mesmo esquema do [FieldCipher] — AES-256-GCM com
 * nonce aleatório por conteúdo, as mesmas chaves (`ZENY_ENCRYPTION_KEYS`) e o identificador da
 * chave gravado junto, para a rotação — sobre bytes, e não sobre texto.
 *
 * O resultado é `"ZNC1"` (4 bytes) ‖ tamanho do id da chave (1 byte) ‖ id da chave (ASCII) ‖ nonce
 * (12 bytes) ‖ texto cifrado ‖ tag (16 bytes): o conteúdo cresce `21 + tamanho do id` bytes. O
 * cabeçalho e o [associatedData] entram como dado autenticado (AAD): trocar o id da chave, ou
 * apresentar o conteúdo cifrado de um anexo como se fosse de outro (com outro [associatedData]),
 * faz a decifragem falhar.
 *
 * Trabalha em memória: serve para os anexos, que têm limite de tamanho por finalidade, e não para
 * arquivos de tamanho arbitrário.
 */
interface ContentCipher {
    /** Cifra com a chave ativa. Cifrar o mesmo conteúdo duas vezes dá resultados diferentes. */
    fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray = ByteArray(0),
    ): ByteArray

    /**
     * Decifra com a chave indicada no próprio conteúdo e o mesmo [associatedData] da cifragem; falha
     * com [FieldDecryptionException].
     */
    fun decrypt(
        ciphertext: ByteArray,
        associatedData: ByteArray = ByteArray(0),
    ): ByteArray

    /** O identificador da chave que cifrou o conteúdo. */
    fun keyIdOf(ciphertext: ByteArray): String

    /** O identificador da chave que cifra os conteúdos novos. */
    val activeKeyId: String
}

/**
 * Tokens selados (cifrados e autenticados) que o servidor emite e depois lê de volta, como o das
 * URLs de download de anexos sensíveis. Separado do [FieldCipher] de propósito: a chave de cada
 * [purpose] é **derivada** (HKDF-SHA256) da chave do anel `ZENY_ENCRYPTION_KEYS`, nunca a mesma das
 * colunas cifradas, então um valor que o servidor cifrou numa coluna (inclusive com texto escolhido
 * pelo usuário) não vale como token, e o token de uma finalidade não vale em outra. O resultado é
 * `t1:<id da chave>:<base64url(nonce ‖ texto cifrado ‖ tag)>`, com o id da chave para a rotação.
 * O token não guarda estado: quem lê confere a validade que vai dentro dele.
 */
interface TokenSealer {
    /** Sela [payload] com a chave ativa, derivada para [purpose]. */
    fun seal(
        purpose: String,
        payload: String,
    ): String

    /** O payload de um token selado para [purpose]; falha com [FieldDecryptionException]. */
    fun open(
        purpose: String,
        token: String,
    ): String
}

/**
 * Valor ou conteúdo cifrado que não pode ser decifrado: formato inválido, chave ausente ou dado
 * adulterado ([FieldCipher], [ContentCipher] e [TokenSealer]).
 */
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
