package com.zeny.hotel.guests

import com.zeny.hotel.guests.GuestsTestSupport.Companion.newCpf
import com.zeny.hotel.guests.domain.IdDocuments
import com.zeny.hotel.guests.infrastructure.GuestDocumentRekeyJob
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.infrastructure.crypto.AesGcmFieldCipher
import com.zeny.hotel.shared.infrastructure.crypto.EncryptionKeyRing
import com.zeny.hotel.shared.infrastructure.crypto.HmacBlindIndex
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.TestEncryptionKeys
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * As chaves nos documentos de hóspede (M3 E01, H8 e H9, ajustes da revisão humana): a listagem, o detalhe e a
 * busca mostram o número mascarado pelo sufixo gravado, sem decifrar nenhum documento; e a rotação re-cifra o
 * número e recalcula o índice cego com a chave ativa, para a chave anterior poder sair do anel.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class GuestDocumentKeysTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val rekeyJob: GuestDocumentRekeyJob,
    @Autowired private val meterRegistry: MeterRegistry,
) {
    private val guests = GuestsTestSupport(iam)
    private val previousRing = EncryptionKeyRing.parse(TestEncryptionKeys.keys, TestEncryptionKeys.PREVIOUS_KEY_ID)
    private val activeRing = EncryptionKeyRing.parse(TestEncryptionKeys.keys, TestEncryptionKeys.ACTIVE_KEY_ID)

    @Test
    fun `listing, detail and search show the stored suffix and never decrypt a document`(): Unit =
        with(iam) {
            val cpf = newCpf()
            val name = guests.uniqueName("Inês")
            val created =
                guests.createGuest(
                    guests.createBody(
                        profile = guests.profileBody(fullName = name),
                        documents = listOf(guests.document("CPF", cpf)),
                    ),
                )
            val id = UUID.fromString(created["id"].asString())
            documentRow(id)["number_suffix"] shouldBe cpf.takeLast(4)
            val original = documentRow(id)["number"] as String

            // Número ilegível (chave que não existe): se alguma leitura decifrasse, falharia com 500.
            setNumber(id, "v1:missing:AAAA")
            try {
                val listed = call(GET, "/api/v1/guests?name=$name", asAdmin()).ok()["items"].values()
                listed.map { it["id"].asString() } shouldContainExactly listOf(id.toString())
                listed.single()["documents"][0]["maskedNumber"].asString() shouldBe "***" + cpf.takeLast(4)
                call(GET, "/api/v1/guests/$id", asAdmin()).ok()["documents"][0]["maskedNumber"].asString() shouldBe
                    "***" + cpf.takeLast(4)
                call(POST, "/api/v1/guests/search-by-document", asAdmin(), guests.document("CPF", cpf))
                    .ok()["items"]
                    .values()
                    .map { it["id"].asString() } shouldContainExactly listOf(id.toString())
            } finally {
                setNumber(id, original)
            }
        }

    @Test
    fun `the rekey job moves a document from the previous key to the active one, still found only by it`(): Unit =
        with(iam) {
            val passport = "RK${guests.suffix(8)}".uppercase()
            val created =
                guests.createGuest(
                    guests.createBody(documents = listOf(guests.document("PASSPORT", passport, "CL"))),
                )
            val id = UUID.fromString(created["id"].asString())
            val documentId = created["documents"][0]["id"].asString()
            val input = IdDocuments.indexInput(DocumentType.PASSPORT, "CL", passport)
            val previousIndex = HmacBlindIndex(previousRing).compute(IdDocuments.INDEX_PURPOSE, input)
            val activeIndex = HmacBlindIndex(activeRing).compute(IdDocuments.INDEX_PURPOSE, input)

            // Como antes da rotação: número cifrado e índice calculado com a chave que era a ativa.
            jdbc
                .sql(
                    "UPDATE guests.guest_document SET number = :number, number_index_key = :key, " +
                        "number_index = :hash WHERE guest_id = :id",
                ).param("number", AesGcmFieldCipher(previousRing).encrypt(passport))
                .param("key", previousIndex.keyId)
                .param("hash", previousIndex.hash)
                .param("id", id)
                .update()
            (documentRow(id)["number"] as String) shouldStartWith "v1:${TestEncryptionKeys.PREVIOUS_KEY_ID}:"

            val result = rekeyJob.rekeyStale()

            (result.rekeyed >= 1) shouldBe true
            result.failed shouldBe 0
            result.pending shouldBe 0L
            meterRegistry.get(GuestDocumentRekeyJob.PENDING_METRIC).gauge().value() shouldBe 0.0
            val row = documentRow(id)
            (row["number"] as String) shouldStartWith "v1:${TestEncryptionKeys.ACTIVE_KEY_ID}:"
            row["number_index_key"] shouldBe TestEncryptionKeys.ACTIVE_KEY_ID
            row["number_index"] shouldBe activeIndex.hash
            row["number_index"] shouldNotBe previousIndex.hash
            row["number_suffix"] shouldBe passport.takeLast(4)

            // O número continua o mesmo e encontrável (agora pelo hash da chave ativa).
            call(POST, "/api/v1/guests/$id/documents/$documentId/reveal", asAdmin()).ok()["number"].asString() shouldBe
                passport
            call(POST, "/api/v1/guests/search-by-document", asAdmin(), guests.document("PASSPORT", passport, "CL"))
                .ok()["items"]
                .values()
                .map { it["id"].asString() } shouldContainExactly listOf(id.toString())
            // Uma segunda passada não tem o que fazer com ele.
            rekeyJob.rekeyStale().pending shouldBe 0L
            documentRow(id)["number"] shouldBe row["number"]
        }

    private fun documentRow(guestId: UUID): Map<String, Any?> =
        jdbc
            .sql(
                "SELECT number, number_suffix, number_index_key, number_index FROM guests.guest_document " +
                    "WHERE guest_id = :id",
            ).param("id", guestId)
            .query()
            .singleRow()

    private fun setNumber(
        guestId: UUID,
        number: String,
    ) {
        jdbc
            .sql("UPDATE guests.guest_document SET number = :number WHERE guest_id = :id")
            .param("number", number)
            .param("id", guestId)
            .update()
    }
}
