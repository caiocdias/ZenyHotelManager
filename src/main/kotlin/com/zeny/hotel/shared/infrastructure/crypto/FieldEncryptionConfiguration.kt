package com.zeny.hotel.shared.infrastructure.crypto

import com.zeny.hotel.shared.BlindIndex
import com.zeny.hotel.shared.ContentCipher
import com.zeny.hotel.shared.FieldCipher
import com.zeny.hotel.shared.TokenSealer
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Chaves da cifragem de campo, sem valor padrão em nenhum profile: no `application.yaml`,
 * `zeny.encryption.keys` vem de `ZENY_ENCRYPTION_KEYS` (`id:base64,id:base64…`) e
 * `zeny.encryption.active-key-id` de `ZENY_ENCRYPTION_ACTIVE_KEY_ID`. Sem elas a aplicação
 * não sobe.
 */
@ConfigurationProperties("zeny.encryption")
class FieldEncryptionProperties(
    val keys: String,
    val activeKeyId: String,
) {
    init {
        // Variável ausente chega aqui como o próprio texto `${...}` do application.yaml.
        require(keys.isNotBlank() && !keys.contains("\${")) {
            "Cifragem de campo sem chaves: defina ZENY_ENCRYPTION_KEYS (id:base64 com 32 bytes, AES-256)"
        }
        require(activeKeyId.isNotBlank() && !activeKeyId.contains("\${")) {
            "Cifragem de campo sem chave ativa: defina ZENY_ENCRYPTION_ACTIVE_KEY_ID"
        }
    }

    /** Nunca mostra as chaves. */
    override fun toString(): String = "FieldEncryptionProperties(activeKeyId=$activeKeyId)"
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FieldEncryptionProperties::class)
class FieldEncryptionConfiguration {
    /** O anel de chaves, lido uma vez: a cifragem de campo e a de conteúdo usam as mesmas chaves. */
    @Bean
    fun encryptionKeyRing(properties: FieldEncryptionProperties): EncryptionKeyRing =
        EncryptionKeyRing.parse(properties.keys, properties.activeKeyId)

    @Bean
    fun fieldCipher(keyRing: EncryptionKeyRing): FieldCipher = AesGcmFieldCipher(keyRing)

    @Bean
    fun contentCipher(keyRing: EncryptionKeyRing): ContentCipher = AesGcmContentCipher(keyRing)

    @Bean
    fun tokenSealer(keyRing: EncryptionKeyRing): TokenSealer = HkdfTokenSealer(keyRing)

    /** O índice cego das colunas cifradas pesquisáveis (H8 do M3), com chaves derivadas do mesmo anel. */
    @Bean
    fun blindIndex(keyRing: EncryptionKeyRing): BlindIndex = HmacBlindIndex(keyRing)
}
