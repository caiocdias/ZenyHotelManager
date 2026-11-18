package com.zeny.hotel.housekeeping

import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.CheckIn
import com.zeny.hotel.reservations.CheckInGuest
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomsTestSupport
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.UuidV7
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.util.UUID

/**
 * Apoio dos testes do `housekeeping`: monta corpos e cria tipos de limpeza, faxinas programadas e tarefas avulsas pela
 * API, como o administrador, sempre com código único (o banco é compartilhado, e os seis tipos da carga
 * inicial nunca são alterados pelos testes).
 */
class HousekeepingTestSupport(
    private val iam: IamTestSupport,
    private val jdbc: JdbcClient,
) {
    val rooms = RoomsTestSupport(iam)

    fun uniqueCode(prefix: String = "CT"): String = rooms.uniqueCode(prefix)

    @Suppress("LongParameterList") // os campos do tipo de limpeza, com padrões
    fun cleaningTypeBody(
        code: String = uniqueCode(),
        name: String = rooms.uniqueName("Limpeza"),
        requiresInspection: Boolean = false,
        defaultPriority: Int? = 3,
        linenChangeEveryNights: Int? = null,
        credits: List<Map<String, Any?>> = emptyList(),
        checklistTemplateId: UUID? = null,
    ): Map<String, Any?> =
        mapOf(
            "code" to code,
            "name" to name,
            "requiresInspection" to requiresInspection,
            "defaultPriority" to defaultPriority,
            "linenChangeEveryNights" to linenChangeEveryNights,
            "checklistTemplateId" to checklistTemplateId,
            "credits" to credits,
        )

    fun credit(
        roomTypeId: UUID,
        credits: Int,
    ): Map<String, Any?> = mapOf("roomTypeId" to roomTypeId, "credits" to credits)

    fun createCleaningType(body: Map<String, Any?> = cleaningTypeBody()): UUID =
        with(iam) { UUID.fromString(call(POST, CLEANING_TYPES, asAdmin(), body).expectStatus(201)!!["id"].asString()) }

    /** Uma UH nova, ativa, de um tipo de UH novo. */
    fun createRoom(): UUID = rooms.createRoom(rooms.createRoomType())

    /** O corpo de criação de uma faxina programada (o local é a UH [roomId] **ou** a área [areaId]). */
    fun scheduleBody(
        cleaningTypeId: UUID,
        roomId: UUID? = null,
        areaId: UUID? = null,
        frequencyDays: Int? = 7,
        lastDoneOn: String? = null,
    ): Map<String, Any?> =
        mapOf(
            "roomId" to roomId,
            "areaId" to areaId,
            "cleaningTypeId" to cleaningTypeId,
            "frequencyDays" to frequencyDays,
            "lastDoneOn" to lastDoneOn,
        )

    /** Cria uma faxina programada como o administrador; numa UH nova se o corpo não diz o local. */
    fun createSchedule(body: Map<String, Any?> = scheduleBody(createCleaningType(), roomId = createRoom())): UUID =
        with(iam) {
            UUID.fromString(call(POST, SCHEDULED_CLEANINGS, asAdmin(), body).expectStatus(201)!!["id"].asString())
        }

    /** O corpo de uma solicitação avulsa (o local é a UH [roomId] **ou** a área [areaId]). */
    fun taskBody(
        cleaningTypeId: UUID?,
        roomId: UUID? = null,
        areaId: UUID? = null,
        date: String? = null,
        notes: String? = null,
    ): Map<String, Any?> =
        mapOf(
            "roomId" to roomId,
            "areaId" to areaId,
            "cleaningTypeId" to cleaningTypeId,
            "date" to date,
            "notes" to notes,
        )

    /** Solicita uma tarefa avulsa como o administrador e devolve a resposta (`201`). */
    fun requestTask(body: Map<String, Any?>): JsonNode =
        with(iam) { call(POST, TASKS, asAdmin(), body).expectStatus(201)!! }

    fun etagOf(path: String): String {
        val response: MockHttpServletResponse = iam.call(GET, path, iam.asAdmin())
        withClue(response.getContentAsString(Charsets.UTF_8)) { response.status shouldBe 200 }
        return response.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
    }

    /** Todas as páginas de [path] (com ou sem filtros), como o administrador. */
    fun listAll(path: String): List<JsonNode> {
        val items = mutableListOf<JsonNode>()
        var cursor: String? = null
        var pages = 0
        do {
            val separator = if ('?' in path) '&' else '?'
            val url = path + cursor?.let { "${separator}cursor=$it" }.orEmpty()
            val page = with(iam) { call(GET, url, asAdmin()).ok() }
            items += page["items"].values()
            cursor = page["nextCursor"].takeUnless { it.isNull }?.asString()
            pages++
            withClue("paginação sem fim em $path") { (pages < MAX_PAGES) shouldBe true }
        } while (cursor != null)
        return items
    }

    /** As ações da trilha da entidade, na ordem em que aconteceram. */
    fun auditActions(
        entityType: String,
        entityId: UUID,
    ): List<String> =
        jdbc
            .sql(
                "SELECT action FROM audit.audit_entry WHERE entity_type = :type AND entity_id = :id " +
                    "ORDER BY occurred_at, id",
            ).param("type", entityType)
            .param("id", entityId.toString())
            .query(String::class.java)
            .list()
            .filterNotNull()

    /** Quantos tipos de limpeza têm o código, gravados de fato. */
    fun countByCode(code: String): Int =
        jdbc
            .sql("SELECT count(*) FROM housekeeping.cleaning_type WHERE code = :code")
            .param("code", code)
            .query(Int::class.java)
            .single()

    /** Os créditos gravados do tipo, por tipo de UH. */
    fun storedCredits(id: UUID): Map<UUID, Int> =
        jdbc
            .sql("SELECT room_type_id, credits FROM housekeeping.cleaning_type_credit WHERE cleaning_type_id = :id")
            .param("id", id)
            .query { rs, _ -> rs.getObject("room_type_id", UUID::class.java) to rs.getInt("credits") }
            .list()
            .toMap()

    companion object {
        const val CLEANING_TYPES = "/api/v1/cleaning-types"
        const val SCHEDULED_CLEANINGS = "/api/v1/scheduled-cleanings"
        const val TASKS = "/api/v1/housekeeping-tasks"
        private const val MAX_PAGES = 1000

        fun definition(code: PermissionCode) = HousekeepingPermissions.permissions.single { it.code == code }
    }
}

/**
 * Apoio de reservas dos testes da geração de tarefas do `housekeeping`: plano com preços, reserva confirmada com um
 * titular novo e hospedagem pelo check-in real, todos pelos casos de uso do `reservations`, na data operacional do
 * banco (que os testes não avançam).
 */
class HousekeepingStays(
    private val reservations: ReservationsTestSupport,
    private val reservationsApi: ReservationsApi,
    private val hotel: HotelApi,
) {
    private val today: LocalDate get() = hotel.currentBusinessDate()

    /** Um plano novo do tipo de UH [type], com preços de hoje a [PRICED_DAYS] dias. */
    fun planFor(type: UUID): UUID {
        val plan = reservations.plan(listOf(type))
        reservations.setPrices(plan.id, listOf(type), today, today.plusDays(PRICED_DAYS))
        return plan.id
    }

    /**
     * Uma reserva confirmada de [arrival] (padrão: hoje) a [departure] (padrão: a noite seguinte), com um titular novo
     * de nome [name]; devolve a reserva e o titular.
     */
    fun reservation(
        type: UUID,
        plan: UUID,
        arrival: LocalDate = today,
        departure: LocalDate = arrival.plusDays(1),
        name: String = guestName(),
    ): Pair<ReservationId, UUID> {
        val holder =
            UUID.fromString(
                reservations.guests
                    .createGuest(
                        reservations.guests.createBody(
                            profile = reservations.guests.profileBody(fullName = name),
                            documents = emptyList(),
                        ),
                    )["id"]
                    .asString(),
            )
        val created =
            reservations.created(
                reservations.booking(holder, reservations.reservation(type, plan, arrival, departure, adults = 1)),
            )["reservations"][0]
        return ReservationId(UUID.fromString(created["id"].asString())) to holder
    }

    /** Uma reserva hospedada na UH [room] desde hoje até [departure], pelo check-in real; a reserva e o titular. */
    fun inHouse(
        type: UUID,
        plan: UUID,
        room: UUID,
        departure: LocalDate,
        name: String = guestName(),
    ): Pair<ReservationId, UUID> {
        val (id, holder) = reservation(type, plan, today, departure, name)
        reservationsApi.assignRoom(id, RoomId(room))
        reservationsApi.checkIn(
            id,
            CheckIn(
                listOf(
                    CheckInGuest(GuestId(holder), primary = true, travelReason = "LAZER", transportMode = "AUTOMOVEL"),
                ),
            ),
        )
        return id to holder
    }

    private fun guestName(): String = "Hóspede ${UuidV7.generate().toString().takeLast(NAME_SUFFIX)}"

    private companion object {
        const val PRICED_DAYS = 10L
        const val NAME_SUFFIX = 8
    }
}
