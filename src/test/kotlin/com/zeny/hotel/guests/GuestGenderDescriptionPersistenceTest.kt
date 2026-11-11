package com.zeny.hotel.guests

import com.zeny.hotel.guests.domain.Guest
import com.zeny.hotel.guests.domain.GuestProfile
import com.zeny.hotel.guests.infrastructure.GuestJpaRepository
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * Persistência real da descrição do gênero (M5 E15, Task 1): cifrada em repouso, lida de volta, apagada pela
 * anonimização aceita pelo banco, e a estrutura de `guest_anonymized_ck` conferida por comportamento (o que o
 * `pg_get_constraintdef` sem parênteses não prova: o `OR` e o `AND`).
 */
@IntegrationTest
class GuestGenderDescriptionPersistenceTest(
    @Autowired private val repository: GuestJpaRepository,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionTemplate,
) {
    private val now = Instant.parse("2026-11-06T12:00:00Z")

    private fun rawColumn(id: UUID): String? =
        jdbc
            .sql("SELECT gender_description FROM guests.guest WHERE id = :id")
            .param("id", id)
            .query(String::class.java)
            .optional()
            .orElse(null)

    @Test
    fun `the description is stored encrypted and read back in plain text`() {
        val secret = "Pessoa não binária ${UUID.randomUUID()}"
        val id =
            transactions
                .execute {
                    repository
                        .saveAndFlush(
                            Guest.create(
                                GuestProfile("Maria da Silva", gender = Gender.OTHER, genderDescription = secret),
                                now,
                            ),
                        ).id.value
                }.shouldNotBeNull()

        val stored = rawColumn(id).shouldNotBeNull()
        stored shouldStartWith "v1:"
        stored shouldNotContain "binária"
        stored shouldNotContain secret
        transactions.execute { repository.findById(id).get().genderDescription } shouldBe secret
    }

    @Test
    fun `an anonymized profile is accepted by the database with the description erased`() {
        val id =
            transactions
                .execute {
                    val guest =
                        Guest.create(
                            GuestProfile("Maria da Silva", gender = Gender.OTHER, genderDescription = "Texto aberto"),
                            now,
                        )
                    guest.anonymize(UserId(UuidV7.generate()), now.plusSeconds(1))
                    repository.saveAndFlush(guest).id.value
                }.shouldNotBeNull()

        rawColumn(id).shouldBeNull()
        transactions.execute { repository.findById(id).get().genderDescription }.shouldBeNull()
    }

    /** Insere uma linha com as colunas extras; devolve `null` se aceita, ou a mensagem da recusa. */
    private fun insert(extra: Map<String, Any?>): String? =
        transactions.execute { status ->
            status.setRollbackOnly()
            val columns =
                mapOf<String, Any?>(
                    "id" to UUID.randomUUID(),
                    "full_name" to "Maria da Silva",
                    "search_name" to "maria da silva",
                    "vip" to false,
                    "restricted" to false,
                    "status" to "ACTIVE",
                    "version" to 0L,
                ) + extra
            val names = columns.keys + listOf("created_at", "updated_at")
            val values = columns.keys.map { ":$it" } + listOf("now()", "now()")
            try {
                var spec =
                    jdbc.sql(
                        "INSERT INTO guests.guest (${names.joinToString()}) VALUES (${values.joinToString()})",
                    )
                columns.forEach { (k, v) -> spec = spec.param(k, v) }
                spec.update()
                null
            } catch (e: DataIntegrityViolationException) {
                e.message ?: "recusado"
            }
        }

    private fun anonymized(extra: Map<String, Any?> = emptyMap()) =
        insert(
            mapOf<String, Any?>(
                "full_name" to "Hóspede anonimizado",
                "search_name" to "hospede anonimizado",
                "status" to "ANONYMIZED",
                "anonymized_at" to java.sql.Timestamp.from(now),
            ) + extra,
        )

    @Test
    fun `the anonymized check accepts a clean anonymized row and the kept aggregates`() {
        anonymized() shouldBe null
        anonymized(mapOf("anonymized_state" to "SP", "anonymized_ibge_city_code" to "3550308")) shouldBe null
    }

    @Test
    fun `the anonymized check refuses an anonymized row that still has personal data`() {
        val leftovers =
            mapOf<String, Any?>(
                "gender" to "OTHER",
                "email" to "a@example.com",
                "phone" to "+5511999999999",
                "notes" to "x",
                "social_name" to "Mia",
            )
        leftovers.forEach { (column, value) ->
            anonymized(mapOf(column to value)).orEmpty() shouldContain "guest_anonymized_ck"
        }
        anonymized(mapOf("full_name" to "Maria da Silva")).orEmpty() shouldContain "guest_anonymized_ck"
        anonymized(mapOf("anonymized_state" to "sp")).orEmpty() shouldContain "guest_anonymized_ck"
        anonymized(mapOf("anonymized_ibge_city_code" to "12")).orEmpty() shouldContain "guest_anonymized_ck"
    }

    @Test
    fun `the anonymized check refuses anonymization aggregates on a profile that is not anonymized`() {
        insert(mapOf("anonymized_state" to "SP")).orEmpty() shouldContain "guest_anonymized_ck"
        insert(mapOf("anonymized_ibge_city_code" to "3550308")).orEmpty() shouldContain "guest_anonymized_ck"
        insert(mapOf("gender" to "OTHER", "gender_description" to "v1:x")) shouldBe null
    }

    @Test
    fun `an anonymized row with a description is refused whatever the gender`() {
        anonymized(mapOf("gender_description" to "v1:x")).shouldNotBeNull()
        anonymized(mapOf("gender" to "OTHER", "gender_description" to "v1:x")).shouldNotBeNull()
    }
}
