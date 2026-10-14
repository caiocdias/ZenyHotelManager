package com.zeny.hotel.shared.infrastructure

import com.zeny.hotel.shared.Address
import com.zeny.hotel.shared.BrazilianState
import com.zeny.hotel.shared.Cnpj
import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.DocumentNumberGenerator
import com.zeny.hotel.shared.DocumentNumberType
import com.zeny.hotel.shared.FieldCipher
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.shared.isVersion7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.KernelProbe
import com.zeny.hotel.testsupport.ProbeId
import com.zeny.hotel.testsupport.TestEncryptionKeys
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.PersistenceException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.util.UUID

/**
 * Os tipos do kernel `shared` gravados e lidos num PostgreSQL 18 real: `Money` em
 * `numeric(14,2)`, `Percent` em `numeric(7,4)`, id tipado em `uuid`, campo cifrado com o
 * identificador da chave e números legíveis vindos de uma sequence.
 */
@IntegrationTest
class SharedKernelPersistenceTest(
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactionTemplate: TransactionTemplate,
    @Autowired private val entityManagerFactory: EntityManagerFactory,
    @Autowired private val documentNumbers: DocumentNumberGenerator,
    @Autowired private val fieldCipher: FieldCipher,
) {
    private val address =
        Address(
            street = "Avenida Paulista",
            number = "1000",
            complement = null,
            district = "Bela Vista",
            city = "São Paulo",
            state = BrazilianState.SP,
            postalCode = "01310-100",
            ibgeCityCode = "3550308",
        )

    private fun probe(id: ProbeId = ProbeId(UuidV7.generate())) =
        KernelProbe(
            id = id,
            number = DocumentNumber("R", 2026, 123),
            amount = Money.of("999999999999.99"),
            discount = Percent.of("12.5"),
            cpf = Cpf.of("529.982.247-25"),
            cnpj = Cnpj.of("12.ABC.345/01DE-35"),
            document = "MG-12.345.678",
            address = address,
            related = mutableSetOf(ProbeId(UuidV7.generate()), ProbeId(UuidV7.generate())),
        )

    @Test
    fun `persists and reads back every value type`() {
        val original = probe()
        persist(original)

        val loaded =
            entityManagerFactory.createEntityManager().use { em ->
                // O id tipado é inline: o Hibernate o vê como UUID, e a busca usa o valor.
                em.find(KernelProbe::class.java, original.id.value)
            }

        loaded.id shouldBe original.id
        loaded.number shouldBe original.number
        loaded.amount shouldBe original.amount
        loaded.discount shouldBe original.discount
        loaded.cpf shouldBe original.cpf
        loaded.cnpj shouldBe original.cnpj
        loaded.document shouldBe "MG-12.345.678"
        loaded.address shouldBe address
        loaded.related shouldBe original.related
    }

    @Test
    fun `stores money as numeric(14,2), percent as numeric(7,4) and ids as uuid`() {
        val original = probe()
        persist(original)

        val row =
            jdbc
                .sql(
                    "SELECT id, amount, discount, doc_number, cpf, state FROM test_support.kernel_probe WHERE id = :id",
                ).param("id", original.id.value)
                .query { rs, _ ->
                    mapOf(
                        "id" to rs.getObject("id", UUID::class.java),
                        "amount" to rs.getBigDecimal("amount"),
                        "discount" to rs.getBigDecimal("discount"),
                        "doc_number" to rs.getString("doc_number"),
                        "cpf" to rs.getString("cpf"),
                        "state" to rs.getString("state"),
                    )
                }.single()

        (row["id"] as UUID).isVersion7() shouldBe true
        row["amount"] shouldBe BigDecimal("999999999999.99")
        row["discount"] shouldBe BigDecimal("12.5000")
        row["doc_number"] shouldBe "R-2026-000123"
        row["cpf"] shouldBe "52998224725"
        row["state"] shouldBe "SP"
        numericType("amount") shouldBe (14 to 2)
        numericType("discount") shouldBe (7 to 4)
    }

    @Test
    fun `money beyond numeric(14,2) is refused by the database`() {
        val tooLarge =
            KernelProbe(
                id = ProbeId(UuidV7.generate()),
                number = DocumentNumber("R", 2026, 1),
                amount = Money.of("1000000000000.00"),
                discount = Percent.ZERO,
                cpf = Cpf.of("52998224725"),
                cnpj = Cnpj.of("11222333000181"),
                document = "x",
                address = address,
            )

        val error = shouldThrow<PersistenceException> { persist(tooLarge) }
        error.message shouldContain "numeric field overflow"
    }

    @Test
    fun `encrypted columns hold only ciphertext tagged with the key id`() {
        val original = probe()
        persist(original)

        val stored =
            jdbc
                .sql("SELECT document FROM test_support.kernel_probe WHERE id = :id")
                .param("id", original.id.value)
                .query(String::class.java)
                .single()

        stored shouldStartWith "v1:${TestEncryptionKeys.ACTIVE_KEY_ID}:"
        stored shouldNotContain "12.345.678"
        fieldCipher.decrypt(stored) shouldBe "MG-12.345.678"
        fieldCipher.isEncryptedWithActiveKey(stored) shouldBe true
    }

    @Test
    fun `document numbers come from the sequence of the document type`() {
        val type = DocumentNumberType("R", "test_support.probe_number_seq")

        val first = documentNumbers.next(type, 2026)
        val second = documentNumbers.next(type, 2026)

        first.prefix shouldBe "R"
        first.year shouldBe 2026
        second.sequence shouldBe first.sequence + 1
        second.toString() shouldBe "R-2026-" + second.sequence.toString().padStart(6, '0')
        shouldThrow<DataAccessException> {
            documentNumbers.next(DocumentNumberType("X", "test_support.missing_seq"), 2026)
        }
    }

    private fun persist(probe: KernelProbe) {
        transactionTemplate.executeWithoutResult {
            entityManagerFactory.createEntityManager().use { em ->
                em.joinTransaction()
                em.persist(probe)
                em.flush()
            }
        }
    }

    private fun numericType(column: String): Pair<Int, Int> =
        jdbc
            .sql(
                """
                SELECT numeric_precision, numeric_scale FROM information_schema.columns
                WHERE table_schema = 'test_support' AND table_name = 'kernel_probe' AND column_name = :column
                """.trimIndent(),
            ).param("column", column)
            .query { rs, _ -> rs.getInt(1) to rs.getInt(2) }
            .single()
}
