package com.zeny.hotel.shared.infrastructure.crypto

import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * As chaves de cifragem de campo, por identificador, e qual delas cifra os valores novos.
 * Vêm sempre da configuração (`zeny.encryption.*`, alimentada por variáveis de ambiente ou
 * Docker secrets); não há chave padrão em lugar nenhum do código.
 *
 * Rotação: acrescente a chave nova à lista, aponte a ativa para ela e mantenha a antiga até
 * re-cifrar os valores que ainda a usam ([com.zeny.hotel.shared.FieldCipher.isEncryptedWithActiveKey]).
 */
class EncryptionKeyRing private constructor(
    private val keys: Map<String, SecretKey>,
    val activeKeyId: String,
) {
    val keyIds: Set<String> get() = keys.keys

    val activeKey: SecretKey get() = keys.getValue(activeKeyId)

    fun keyOrNull(keyId: String): SecretKey? = keys[keyId]

    /** Nunca mostra o material das chaves. */
    override fun toString(): String = "EncryptionKeyRing(keyIds=$keyIds, activeKeyId=$activeKeyId)"

    companion object {
        const val KEY_BYTES = 32
        private val KEY_ID = Regex("[A-Za-z0-9_-]{1,32}")

        /**
         * Lê `id:base64,id:base64,…` (base64 padrão de 32 bytes por chave, AES-256). As mensagens
         * de erro citam o identificador, nunca a chave.
         */
        fun parse(
            keys: String,
            activeKeyId: String,
        ): EncryptionKeyRing {
            val entries = keys.split(',').map(String::trim).filter(String::isNotEmpty)
            require(entries.isNotEmpty()) { "Nenhuma chave de cifragem configurada (zeny.encryption.keys)" }
            val parsed =
                entries.mapIndexed { index, entry ->
                    val separator = entry.indexOf(':')
                    require(separator > 0) { "Chave de cifragem fora do formato id:base64 (entrada ${index + 1})" }
                    val id = entry.substring(0, separator).trim()
                    val material =
                        try {
                            Base64.getDecoder().decode(entry.substring(separator + 1).trim())
                        } catch (_: IllegalArgumentException) {
                            throw IllegalArgumentException("A chave de cifragem '$id' não está em base64")
                        }
                    id to material
                }
            val duplicated = parsed.groupBy { it.first }.filterValues { it.size > 1 }.keys
            require(duplicated.isEmpty()) { "Chave de cifragem repetida: $duplicated" }
            return of(parsed.toMap(), activeKeyId.trim())
        }

        fun of(
            keys: Map<String, ByteArray>,
            activeKeyId: String,
        ): EncryptionKeyRing {
            require(keys.isNotEmpty()) { "Nenhuma chave de cifragem configurada" }
            keys.forEach { (id, material) ->
                require(KEY_ID.matches(id)) { "Identificador de chave de cifragem inválido: '$id'" }
                require(material.size == KEY_BYTES) {
                    "A chave de cifragem '$id' precisa ter $KEY_BYTES bytes (AES-256); tem ${material.size}"
                }
            }
            require(activeKeyId in keys) { "A chave de cifragem ativa '$activeKeyId' não está entre as configuradas" }
            return EncryptionKeyRing(keys.mapValues { (_, material) -> SecretKeySpec(material, "AES") }, activeKeyId)
        }
    }
}
