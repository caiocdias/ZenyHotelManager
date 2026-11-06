package com.zeny.hotel.guests

import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Os CHECKs da descrição do gênero (M5 E15, D-E15-1), contra o banco migrado: a descrição só existe com o gênero
 * `OTHER` e cifrada (prefixo `v1:`), e a anonimização a apaga. Cada tentativa roda numa transação desfeita, para não
 * deixar linha no banco compartilhado.
 */
@IntegrationTest
class GuestGenderDescriptionConstraintsTest(
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionTemplate,
) {
    /** Tenta inserir uma linha; devolve `null` se o banco aceitou ou a mensagem da recusa. */
    private fun insert(
        gender: String?,
        description: String?,
    ): String? =
        transactions.execute { status ->
            status.setRollbackOnly()
            try {
                jdbc
                    .sql(
                        """
                        INSERT INTO guests.guest (id, full_name, search_name, gender, gender_description, vip,
                                                  restricted, status, created_at, updated_at, version)
                        VALUES (:id, 'Maria da Silva', 'maria da silva', :gender, :description, FALSE, FALSE,
                                'ACTIVE', now(), now(), 0)
                        """.trimIndent(),
                    ).param("id", UUID.randomUUID())
                    .param("gender", gender)
                    .param("description", description)
                    .update()
                null
            } catch (e: DataIntegrityViolationException) {
                e.message ?: "recusado"
            }
        }

    @Test
    fun `the description is accepted only with OTHER and the encrypted prefix`() {
        insert("OTHER", "v1:x") shouldBe null
        insert("OTHER", null) shouldBe null
        insert("MALE", null) shouldBe null
        insert(null, null) shouldBe null
    }

    @Test
    fun `the description is refused with another gender, with no gender or without the prefix`() {
        insert("MALE", "v1:x").orEmpty() shouldContain "guest_gender_description_ck"
        insert(null, "v1:x").orEmpty() shouldContain "guest_gender_description_ck"
        insert("OTHER", "texto aberto").orEmpty() shouldContain "guest_gender_description_ck"
    }

    /**
     * Nenhuma linha viola só a cláusula nova de `guest_anonymized_ck`: com `OTHER`, a cláusula antiga
     * `gender IS NULL` já recusa; com gênero nulo, `guest_gender_description_ck` recusa. A cláusula é defesa em
     * profundidade, e a prova dela é a definição da restrição no catálogo, conferida cláusula a cláusula.
     */
    @Test
    fun `the anonymized check keeps every old clause and adds the gender description`() {
        val definition =
            jdbc
                .sql(
                    """
                    SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
                    JOIN pg_namespace n ON n.oid = c.connamespace
                    WHERE n.nspname = 'guests' AND c.conname = 'guest_anonymized_ck'
                    """.trimIndent(),
                ).query(String::class.java)
                .single()
                .replace(Regex("\\s+"), " ")
                .replace("::text", "")
                .replace("(", "")
                .replace(")", "")

        listOf(
            "anonymized_at IS NULL",
            "anonymized_by IS NULL",
            "anonymized_state IS NULL",
            "anonymized_ibge_city_code IS NULL",
            "anonymized_at IS NOT NULL",
            "full_name = 'Hóspede anonimizado'",
            "search_name = 'hospede anonimizado'",
            "social_name IS NULL",
            "birth_date IS NULL",
            "gender IS NULL",
            "email IS NULL",
            "phone IS NULL",
            "street IS NULL",
            "number IS NULL",
            "complement IS NULL",
            "district IS NULL",
            "city IS NULL",
            "state IS NULL",
            "postal_code IS NULL",
            "ibge_city_code IS NULL",
            "preferred_floor IS NULL",
            "preferred_bed IS NULL",
            "preferred_pillow IS NULL",
            "notes IS NULL",
            "company_id IS NULL",
            "NOT restricted",
            "sensitive_data IS NULL",
            "dietary_restrictions IS NULL",
            "anonymized_state ~ '^[A-Z]{2}$'",
            "anonymized_ibge_city_code ~ '^[0-9]{7}$'",
            "gender_description IS NULL",
            // Com o espaço antes, "state IS NULL" não passa por "anonymized_state IS NULL".
        ).forEach { clause -> definition shouldContain " $clause" }
    }
}
