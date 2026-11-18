package com.zeny.hotel.housekeeping

import com.zeny.hotel.housekeeping.domain.CleaningType
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.persistence.Column
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.util.UUID

/**
 * O schema `housekeeping` (M6 E01): a carga inicial dos seis tipos da D5, a chave estável (`system_key`, que o
 * mapeamento nunca atualiza, que tem índice único parcial e que o banco não deixa trocar), a recusa de DELETE em
 * `cleaning_type` e em `scheduled_cleaning` e as restrições da faxina programada (Task 3). Os testes nunca alteram os
 * seis tipos da carga.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class HousekeepingSchemaTest(
    @Autowired iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired flyway: Flyway,
    @Autowired private val transactions: TransactionTemplate,
) {
    private val owner = JdbcClient.create(flyway.configuration.dataSource)
    private val housekeeping = HousekeepingTestSupport(iam, jdbc)

    @Test
    fun `the initial load has the six types of D5, with the turndown inactive`() {
        val seeded =
            jdbc
                .sql(
                    """
                    SELECT system_key, code, name, requires_inspection, default_priority, linen_change_every_nights,
                           checklist_template_id, active
                    FROM housekeeping.cleaning_type WHERE system_key IS NOT NULL
                    """.trimIndent(),
                ).query { rs, _ ->
                    listOf(
                        rs.getString("system_key"),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getBoolean("requires_inspection"),
                        rs.getInt("default_priority"),
                        rs.getObject("linen_change_every_nights") as Int?,
                        rs.getObject("checklist_template_id", UUID::class.java),
                        rs.getBoolean("active"),
                    )
                }.list()

        seeded shouldContainExactlyInAnyOrder
            listOf(
                listOf("CHECKOUT", "CHECKOUT", "Saída", true, 4, null, null, true),
                listOf("STAYOVER", "STAYOVER", "Permanência", false, 3, 3, null, true),
                listOf("TOUCH_UP", "TOUCH_UP", "Retoque", false, 2, null, null, true),
                listOf("DEEP_CLEAN", "DEEP_CLEAN", "Faxina pesada", true, 1, null, null, true),
                listOf("PRE_ARRIVAL", "PRE_ARRIVAL", "Pré-chegada", true, 4, null, null, true),
                listOf("TURNDOWN", "TURNDOWN", "Abertura de cama", false, 2, null, null, false),
            )
        CleaningTypeKey.entries.map { it.name } shouldContainExactlyInAnyOrder seeded.map { it[0] }
    }

    @Test
    fun `the API reads the seeded types with their key, without changing them`() {
        val listed = housekeeping.listAll(HousekeepingTestSupport.CLEANING_TYPES).filter { !it["key"].isNull }

        listed.map { it["key"].asString() to it["code"].asString() } shouldContainExactlyInAnyOrder
            CleaningTypeKey.entries.map { it.name to it.name }
        val turndown = listed.single { it["key"].asString() == "TURNDOWN" }
        turndown["active"].asBoolean() shouldBe false
        turndown["missingCreditRoomTypeIds"].isEmpty shouldBe true
        listed.single { it["key"].asString() == "STAYOVER" }["linenChangeEveryNights"].asInt() shouldBe 3
    }

    @Test
    fun `the key is never updated by the mapping and has a partial unique index`() {
        CleaningType::class.java
            .getDeclaredField("key")
            .getAnnotation(Column::class.java)
            .shouldNotBeNull()
            .updatable shouldBe false

        val index =
            owner
                .sql(
                    "SELECT indexdef FROM pg_indexes WHERE schemaname = 'housekeeping' " +
                        "AND tablename = 'cleaning_type' AND indexdef LIKE '%(system_key)%'",
                ).query(String::class.java)
                .single()
        index shouldContain "UNIQUE"
        index shouldContain "WHERE (system_key IS NOT NULL)"
    }

    @Test
    fun `the database refuses DELETE on cleaning types, also for the owner, and keeps it on the credits`() {
        val roomType = housekeeping.rooms.createRoomType()
        val id =
            housekeeping.createCleaningType(
                housekeeping.cleaningTypeBody(credits = listOf(housekeeping.credit(roomType, 1))),
            )

        val appError =
            shouldThrow<DataAccessException> {
                transactions.executeWithoutResult {
                    jdbc.sql("DELETE FROM housekeeping.cleaning_type WHERE id = :id").param("id", id).update()
                }
            }
        (appError.mostSpecificCause as SQLException).sqlState shouldBe INSUFFICIENT_PRIVILEGE
        val ownerError =
            shouldThrow<DataAccessException> {
                owner.sql("DELETE FROM housekeeping.cleaning_type WHERE id = :id").param("id", id).update()
            }
        (ownerError.mostSpecificCause as SQLException).sqlState shouldBe RESTRICT_VIOLATION

        // O papel da aplicação apaga créditos (o PUT os substitui); desfeito no fim.
        transactions.executeWithoutResult { status ->
            jdbc
                .sql("DELETE FROM housekeeping.cleaning_type_credit WHERE cleaning_type_id = :id")
                .param("id", id)
                .update() shouldBe 1
            status.setRollbackOnly()
        }
        housekeeping.storedCredits(id) shouldBe mapOf(roomType to 1)
        housekeeping.countByCode(
            jdbc
                .sql("SELECT code FROM housekeeping.cleaning_type WHERE id = :id")
                .param("id", id)
                .query(String::class.java)
                .single(),
        ) shouldBe 1
    }

    @Test
    fun `the database refuses to change the key of a cleaning type`() {
        val id = housekeeping.createCleaningType()

        // Sem o trigger, o erro seria a unicidade da chave (23505); o trigger recusa antes.
        val error =
            shouldThrow<DataAccessException> {
                owner
                    .sql("UPDATE housekeeping.cleaning_type SET system_key = 'TURNDOWN' WHERE id = :id")
                    .param("id", id)
                    .update()
            }
        (error.mostSpecificCause as SQLException).sqlState shouldBe RESTRICT_VIOLATION
        // As outras colunas seguem editáveis pelo papel da aplicação (desfeito no fim).
        transactions.executeWithoutResult { status ->
            jdbc
                .sql("UPDATE housekeeping.cleaning_type SET name = 'Outro nome' WHERE id = :id")
                .param("id", id)
                .update() shouldBe 1
            status.setRollbackOnly()
        }
    }

    @Test
    fun `the database refuses DELETE on scheduled cleanings, also for the owner`() {
        val id = housekeeping.createSchedule()

        val appError =
            shouldThrow<DataAccessException> {
                transactions.executeWithoutResult {
                    jdbc.sql("DELETE FROM housekeeping.scheduled_cleaning WHERE id = :id").param("id", id).update()
                }
            }
        (appError.mostSpecificCause as SQLException).sqlState shouldBe INSUFFICIENT_PRIVILEGE
        val ownerError =
            shouldThrow<DataAccessException> {
                owner.sql("DELETE FROM housekeeping.scheduled_cleaning WHERE id = :id").param("id", id).update()
            }
        (ownerError.mostSpecificCause as SQLException).sqlState shouldBe RESTRICT_VIOLATION
    }

    @Test
    fun `a scheduled cleaning has one location, a frequency from 1 to 365 and one active per location and type`() {
        val type = housekeeping.createCleaningType()
        val room = UUID.randomUUID()
        val area = UUID.randomUUID()
        val cases =
            listOf(
                Triple(room, area, 7) to CHECK_VIOLATION,
                Triple(null, null, 7) to CHECK_VIOLATION,
                Triple(room, null, 0) to CHECK_VIOLATION,
                Triple(room, null, 366) to CHECK_VIOLATION,
            )
        cases.forEach { (row, state) ->
            val error = shouldThrow<DataAccessException> { insertSchedule(row.first, row.second, type, row.third) }
            (error.mostSpecificCause as SQLException).sqlState shouldBe state
        }

        // Uma ativa por (local, tipo): a segunda ativa na mesma área é recusada; a inativa não conta.
        insertSchedule(null, area, type, 7)
        val duplicate = shouldThrow<DataAccessException> { insertSchedule(null, area, type, 7) }
        (duplicate.mostSpecificCause as SQLException).sqlState shouldBe UNIQUE_VIOLATION
        insertSchedule(null, area, type, 7, active = false)

        val index =
            owner
                .sql(
                    "SELECT indexdef FROM pg_indexes WHERE schemaname = 'housekeeping' " +
                        "AND indexname = 'scheduled_cleaning_active_uk'",
                ).query(String::class.java)
                .single()
        index shouldContain "UNIQUE"
        index shouldContain "WHERE active"
    }

    @Suppress("LongParameterList")
    private fun insertSchedule(
        room: UUID?,
        area: UUID?,
        type: UUID,
        frequencyDays: Int,
        active: Boolean = true,
    ) = owner
        .sql(
            """
            INSERT INTO housekeeping.scheduled_cleaning (id, room_id, area_id, cleaning_type_id, frequency_days,
                last_done_on, next_due_on, active, created_at, updated_at, version)
            VALUES (:id, :room, :area, :type, :frequency, NULL, current_date, :active, now(), now(), 0)
            """.trimIndent(),
        ).param("id", UUID.randomUUID())
        .param("room", room)
        .param("area", area)
        .param("type", type)
        .param("frequency", frequencyDays)
        .param("active", active)
        .update()

    private companion object {
        const val INSUFFICIENT_PRIVILEGE = "42501"
        const val RESTRICT_VIOLATION = "23001"
        const val CHECK_VIOLATION = "23514"
        const val UNIQUE_VIOLATION = "23505"
    }
}
