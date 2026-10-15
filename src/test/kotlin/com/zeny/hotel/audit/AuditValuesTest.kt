package com.zeny.hotel.audit

import com.zeny.hotel.audit.infrastructure.AuditValues
import com.zeny.hotel.shared.AuditMasked
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.shared.infrastructure.crypto.EncryptionKeyRing
import com.zeny.hotel.shared.infrastructure.crypto.HkdfTokenSealer
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.security.SecureRandom
import java.time.Instant

/** As regras de máscara da trilha (sem banco): anotação, nome da propriedade e formato do valor. */
class AuditValuesTest {
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val values = AuditValues(mapper)

    @Test
    fun `sensitive property names are recognized in camelCase, snake_case and kebab-case`() {
        val sensitive =
            listOf(
                "password",
                "newPassword",
                "password_hash",
                "pin",
                "PIN",
                "pinHash",
                "refreshToken",
                "access-token",
                "apiSecret",
                "clientSecrets",
                "salt",
                "credentials",
                "encryptedDocument",
                "ciphertext",
                "senha",
            )
        val plain = listOf("name", "login", "spinner", "hashtag", "tokenizer", "description", "pinned", "opinion")

        sensitive.forEach { withClue(it) { AuditValues.isSensitiveName(it) shouldBe true } }
        plain.forEach { withClue(it) { AuditValues.isSensitiveName(it) shouldBe false } }
    }

    @Test
    fun `secret-looking values are recognized whatever the property`() {
        val secrets =
            listOf(
                "v1:dev:AbCdEfGhIjKlMnOpQrStUvWxYz012345",
                "\$argon2id\$v=19\$m=19456,t=2,p=1\$c2FsdA\$aGFzaA",
                "\$2b\$12\$abcdefghijklmnopqrstuv",
                "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.c2ln",
            )
        val plain = listOf("v1", "Maria", "2026-10-14T10:00:00Z", "eyJ sem pontos", "v1:abc", "$12.50")

        secrets.forEach { withClue(it) { AuditValues.looksSecret(it) shouldBe true } }
        plain.forEach { withClue(it) { AuditValues.looksSecret(it) shouldBe false } }
    }

    @Test
    fun `tokens sealed by the token sealer are recognized, and ordinary text with the same prefix is not`() {
        val key = ByteArray(EncryptionKeyRing.KEY_BYTES).also(SecureRandom()::nextBytes)
        val sealer = HkdfTokenSealer(EncryptionKeyRing.of(mapOf("dev" to key), "dev"))
        val tokens = listOf(sealer.seal("files.download-url", ""), sealer.seal("files.download-url", "x".repeat(200)))
        val plain =
            listOf(
                "t1",
                "t1:dev",
                "t1:dev:curto",
                "t1:dev:AbCdEfGhIjKlMnOpQrStUvWxYz0123456789A",
                "t1: texto comum com espaços e mais de quarenta caracteres",
                "turno:manha:sete-as-quinze-de-segunda-a-sexta-feira",
                "tx:dev:AbCdEfGhIjKlMnOpQrStUvWxYz0123456789AbCd",
            )

        tokens.forEach { withClue(it) { AuditValues.looksSecret(it) shouldBe true } }
        plain.forEach { withClue(it) { AuditValues.looksSecret(it) shouldBe false } }
        mapper.readTree(values.toJson(mapOf("url" to tokens.first())))["url"].stringValue() shouldBe "***"
    }

    @Test
    fun `masks by annotation, name and format at every level, keeping booleans, nulls and the rest`() {
        val snapshot =
            Snapshot(
                id = UserId(UuidV7.generate()),
                name = "Maria",
                document = "123.456.789-09",
                password = "segredo",
                hasPin = true,
                pinHash = null,
                amount = Money.of("12.50"),
                at = Instant.parse("2026-10-14T10:00:00Z"),
                items = listOf(mapOf("token" to "abc", "label" to "v1:k:AbCdEfGhIjKlMnOpQrSt"), mapOf("label" to "ok")),
            )

        val json = mapper.readTree(values.toJson(snapshot))

        json["id"].stringValue() shouldBe snapshot.id.value.toString()
        json["name"].stringValue() shouldBe "Maria"
        json["document"].stringValue() shouldBe "***"
        json["password"].stringValue() shouldBe "***"
        json["hasPin"].booleanValue() shouldBe true
        json["pinHash"].isNull shouldBe true
        json["amount"].stringValue() shouldBe "12.50"
        json["at"].stringValue() shouldBe "2026-10-14T10:00:00Z"
        json["items"][0]["token"].stringValue() shouldBe "***"
        json["items"][0]["label"].stringValue() shouldBe "***"
        json["items"][1]["label"].stringValue() shouldBe "ok"
    }

    @Test
    fun `a snapshot that is not an object is wrapped, and no snapshot stays null`() {
        values.toJson(null).shouldBeNull()
        mapper.readTree(values.toJson(listOf("a", "b")))["value"][1].stringValue() shouldBe "b"
        mapper.readTree(values.toJson("v1:k:AbCdEfGhIjKlMnOpQrSt"))["value"].stringValue() shouldBe "***"
        values.fromJson(values.toJson(mapOf("a" to 1))) shouldBe mapOf("a" to 1)
    }

    private data class Snapshot(
        val id: UserId,
        val name: String,
        @AuditMasked val document: String,
        val password: String,
        val hasPin: Boolean,
        val pinHash: String?,
        val amount: Money,
        val at: Instant,
        val items: List<Map<String, String>>,
    )
}
