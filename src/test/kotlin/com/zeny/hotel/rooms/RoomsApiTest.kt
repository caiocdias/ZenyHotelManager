package com.zeny.hotel.rooms

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.rooms.RoomsTestSupport.Companion.definition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeSortedWith
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import io.kotest.matchers.string.shouldContain as shouldContainSubstring

/**
 * Cadastro de UHs e áreas pela API (M2 E01, RF-UH-01 a 03): características, tipos de UH, UHs e áreas
 * comuns com permissão, `If-Match`, cursor, auditoria e desativação; o número de UH único (409, nunca
 * 500, inclusive numa corrida); nenhuma exclusão física, nem pela API nem pelo SQL do papel da
 * aplicação; e a API pública de leitura.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LargeClass") // cenários de ponta a ponta dos quatro cadastros e de cada recusa
class RoomsApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired @Qualifier("requestMappingHandlerMapping") private val handlerMapping: RequestMappingHandlerMapping,
) {
    private val rooms = RoomsTestSupport(iam)

    // --- Características -----------------------------------------------------------------------------

    @Test
    fun `room features are created, edited, deactivated and reactivated with permission and If-Match`(): Unit =
        with(iam) {
            val reader = createUserWith(TestGrant(definition(RoomsPermissions.REGISTRY_READ), ALL))
            val code = rooms.uniqueCode("F")
            val body = mapOf("code" to code.lowercase(), "name" to "Vista para o mar")
            call(POST, "/api/v1/room-features", asTestUser(reader), body).problem(403, "PERMISSION_DENIED")

            val created = call(POST, "/api/v1/room-features", asAdmin(), body)
            val createdBody = created.expectStatus(201).shouldNotBeNull()
            val id = UUID.fromString(createdBody["id"].asString())
            created.getHeader(HttpHeaders.LOCATION) shouldBe "/api/v1/room-features/$id"
            created.getHeader(HttpHeaders.ETAG) shouldBe "\"0\""
            createdBody["code"].asString() shouldBe code // o código fica em maiúsculas
            createdBody["active"].asBoolean() shouldBe true
            // O mesmo código, em qualquer caixa: 409 com código estável.
            call(POST, "/api/v1/room-features", asAdmin(), mapOf("code" to code, "name" to "Outra"))
                .problem(409, "ROOM_FEATURE_CODE_ALREADY_IN_USE")
            call(POST, "/api/v1/room-features", asAdmin(), mapOf("code" to "com espaço", "name" to "X"))
                .problem(400, "VALIDATION_FAILED")

            val path = "/api/v1/room-features/$id"
            call(GET, path, asTestUser(reader)).ok()["name"].asString() shouldBe "Vista para o mar"
            val etag = rooms.etagOf(path)
            val edited = mapOf("code" to code, "name" to "Vista mar")
            call(PUT, path, asTestUser(reader), edited, ifMatch = etag).problem(403, "PERMISSION_DENIED")
            call(PUT, path, asAdmin(), edited).problem(428, "PRECONDITION_REQUIRED")
            call(PUT, path, asAdmin(), edited, ifMatch = "\"999\"").problem(412, "PRECONDITION_FAILED")
            val other = rooms.createFeature()
            val otherCode = call(GET, "/api/v1/room-features/$other", asAdmin()).ok()["code"].asString()
            call(PUT, path, asAdmin(), mapOf("code" to otherCode, "name" to "X"), ifMatch = etag)
                .problem(409, "ROOM_FEATURE_CODE_ALREADY_IN_USE")
            call(PUT, path, asAdmin(), edited, ifMatch = etag).ok()["name"].asString() shouldBe "Vista mar"

            call(POST, "$path/deactivate", asTestUser(reader)).problem(403, "PERMISSION_DENIED")
            call(POST, "$path/deactivate", asAdmin()).ok()["active"].asBoolean() shouldBe false
            rooms.listAll("/api/v1/room-features?active=true").map { it["id"].asString() } shouldNotContain
                id.toString()
            rooms.listAll("/api/v1/room-features?active=false").map { it["id"].asString() } shouldContain
                id.toString()
            call(POST, "$path/reactivate", asAdmin()).ok()["active"].asBoolean() shouldBe true

            call(GET, "/api/v1/room-features/${UUID.randomUUID()}", asAdmin()).problem(404, "ROOM_FEATURE_NOT_FOUND")
            call(GET, "/api/v1/room-features", asTestUser(createUser())).problem(403, "PERMISSION_DENIED")
            auditActions("rooms.room-feature", id) shouldContainExactly
                listOf(
                    "rooms.room-feature.create",
                    "rooms.room-feature.update",
                    "rooms.room-feature.deactivate",
                    "rooms.room-feature.reactivate",
                )
            refusesDelete("rooms.room_feature", id)
        }

    @Test
    fun `view, accessible and connecting come preloaded with stable keys, and new features have none`(): Unit =
        with(iam) {
            val seeded =
                jdbc
                    .sql("SELECT system_key, code, name, active FROM rooms.room_feature WHERE system_key IS NOT NULL")
                    .query { rs, _ -> listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)) }
                    .list()
            seeded shouldContainExactlyInAnyOrder
                listOf(
                    listOf("VIEW", "VIEW", "Vista", true),
                    listOf("ACCESSIBLE", "ACCESSIBLE", "Acessível", true),
                    listOf("CONNECTING", "CONNECTING", "Conjugada", true),
                )

            val listed = rooms.listAll("/api/v1/room-features").filterNot { it["key"].isNull }
            listed.map { it["key"].asString() } shouldContainExactlyInAnyOrder RoomFeatureKey.entries.map { it.name }
            RoomFeatureKey.entries.forEach { key ->
                val summary = roomsApi.feature(key)
                summary.key shouldBe key
                listed.single { it["key"].asString() == key.name }["id"].asString() shouldBe summary.id.toString()
                roomsApi.feature(summary.id) shouldBe summary
            }

            // A chave não muda quando o gestor troca o código ou o nome (e volta ao original no fim).
            val accessible = roomsApi.feature(RoomFeatureKey.ACCESSIBLE)
            val path = "/api/v1/room-features/${accessible.id}"
            val renamed = mapOf("code" to "ACESSIVEL_${rooms.suffix(4)}", "name" to "Acessível (PcD)")
            call(PUT, path, asAdmin(), renamed, ifMatch = rooms.etagOf(path)).ok()["key"].asString() shouldBe
                "ACCESSIBLE"
            roomsApi.feature(RoomFeatureKey.ACCESSIBLE).name shouldBe "Acessível (PcD)"
            val original = mapOf("code" to accessible.code, "name" to accessible.name)
            call(PUT, path, asAdmin(), original, ifMatch = rooms.etagOf(path)).ok()["code"].asString() shouldBe
                "ACCESSIBLE"

            // Uma pré-carregada serve como qualquer outra numa UH; as criadas pela API não têm chave.
            val type = rooms.createRoomType()
            val room = rooms.createRoom(type, featureIds = listOf(accessible.id.value))
            roomsApi.room(RoomId(room)).shouldNotBeNull().featureIds shouldBe setOf(accessible.id)
            val created = rooms.createFeature()
            call(GET, "/api/v1/room-features/$created", asAdmin()).ok()["key"].isNull shouldBe true
            roomsApi
                .feature(RoomFeatureId(created))
                .shouldNotBeNull()
                .key
                .shouldBeNull()
            refusesDelete("rooms.room_feature", accessible.id.value)
        }

    // --- Tipos de UH ---------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // um cenário de ponta a ponta, com cada recusa no seu passo
    fun `room types have a unique code, coherent occupancy and active features`(): Unit =
        with(iam) {
            val view = rooms.createFeature()
            val accessible = rooms.createFeature()
            val reader = createUserWith(TestGrant(definition(RoomsPermissions.REGISTRY_READ), ALL))
            val code = rooms.uniqueCode()
            val body = rooms.roomTypeBody(code = code, featureIds = listOf(view, accessible))
            call(POST, "/api/v1/room-types", asTestUser(reader), body).problem(403, "PERMISSION_DENIED")

            val id = rooms.createRoomType(body)
            val path = "/api/v1/room-types/$id"
            call(GET, path, asTestUser(reader)).ok().let {
                it["code"].asString() shouldBe code
                it["standardAdults"].asInt() shouldBe 2
                it["maxAdults"].asInt() shouldBe 3
                it["maxChildren"].asInt() shouldBe 1
                it["bedSetup"].asString() shouldBe "1 casal + 1 solteiro"
                it["displayOrder"].asInt() shouldBe 10
                it["featureIds"].values().map { f -> f.asString() } shouldContainExactlyInAnyOrder
                    listOf(view.toString(), accessible.toString())
            }

            // Código repetido (em qualquer caixa): 409.
            call(POST, "/api/v1/room-types", asAdmin(), rooms.roomTypeBody(code = code.lowercase()))
                .problem(409, "ROOM_TYPE_CODE_ALREADY_IN_USE")
            // Ocupação máxima menor que a padrão: 400 com código estável e o campo.
            call(POST, "/api/v1/room-types", asAdmin(), rooms.roomTypeBody(standardAdults = 3, maxAdults = 2))
                .problem(400, "ROOM_TYPE_OCCUPANCY_INVALID")["errors"][0]["field"]
                .asString() shouldBe "maxAdults"
            call(POST, "/api/v1/room-types", asAdmin(), rooms.roomTypeBody(standardChildren = 2, maxChildren = 1))
                .problem(400, "ROOM_TYPE_OCCUPANCY_INVALID")["errors"][0]["field"]
                .asString() shouldBe "maxChildren"
            call(POST, "/api/v1/room-types", asAdmin(), rooms.roomTypeBody(standardAdults = 0, maxAdults = 0))
                .problem(400, "VALIDATION_FAILED")
            // Característica inexistente (400) ou desativada (422).
            call(POST, "/api/v1/room-types", asAdmin(), rooms.roomTypeBody(featureIds = listOf(UUID.randomUUID())))
                .problem(400, "UNKNOWN_ROOM_FEATURE")["errors"][0]["field"]
                .asString() shouldBe "featureIds"
            val retired = rooms.createFeature()
            call(POST, "/api/v1/room-features/$retired/deactivate", asAdmin()).ok()
            call(POST, "/api/v1/room-types", asAdmin(), rooms.roomTypeBody(featureIds = listOf(retired)))
                .problem(422, "ROOM_FEATURE_INACTIVE")

            val etag = rooms.etagOf(path)
            val edited =
                rooms.roomTypeBody(
                    code = code,
                    name = "Luxo",
                    standardAdults = 2,
                    maxAdults = 4,
                    maxChildren = 2,
                    featureIds = listOf(view),
                    displayOrder = 1,
                )
            call(PUT, path, asTestUser(reader), edited, ifMatch = etag).problem(403, "PERMISSION_DENIED")
            call(PUT, path, asAdmin(), edited).problem(428, "PRECONDITION_REQUIRED")
            call(PUT, path, asAdmin(), edited + ("maxAdults" to 1), ifMatch = etag)
                .problem(400, "ROOM_TYPE_OCCUPANCY_INVALID")
            call(PUT, path, asAdmin(), edited, ifMatch = etag).ok().let {
                it["name"].asString() shouldBe "Luxo"
                it["maxAdults"].asInt() shouldBe 4
                it["featureIds"].values().map { f -> f.asString() } shouldContainExactly listOf(view.toString())
            }
            call(PUT, path, asAdmin(), edited, ifMatch = etag).problem(412, "PRECONDITION_FAILED")
            roomsApi.roomType(RoomTypeId(id)).shouldNotBeNull().let {
                it.occupancy shouldBe Occupancy(2, 0, 4, 2)
                it.featureIds shouldBe setOf(RoomFeatureId(view))
            }

            // Desativar a característica mantém o vínculo; mantê-la na edição não falha.
            call(POST, "/api/v1/room-features/$view/deactivate", asAdmin()).ok()
            call(PUT, path, asAdmin(), edited + ("name" to "Luxo II"), ifMatch = rooms.etagOf(path)).ok()
            call(POST, "/api/v1/room-features/$view/reactivate", asAdmin()).ok()

            call(GET, "/api/v1/room-types/${UUID.randomUUID()}", asAdmin()).problem(404, "ROOM_TYPE_NOT_FOUND")
            call(GET, "/api/v1/room-types", asTestUser(createUser())).problem(403, "PERMISSION_DENIED")
            auditActions("rooms.room-type", id) shouldContainExactly
                listOf("rooms.room-type.create", "rooms.room-type.update", "rooms.room-type.update")
            refusesDelete("rooms.room_type", id)
        }

    @Test
    fun `a room type with active rooms is not deactivated, and an inactive type takes no active room`(): Unit =
        with(iam) {
            val type = rooms.createRoomType()
            val typePath = "/api/v1/room-types/$type"
            val room = rooms.createRoom(type)
            val roomPath = "/api/v1/rooms/$room"

            call(POST, "$typePath/deactivate", asAdmin()).problem(422, "ROOM_TYPE_HAS_ACTIVE_ROOMS")
            call(GET, typePath, asAdmin()).ok()["active"].asBoolean() shouldBe true

            call(POST, "$roomPath/deactivate", asAdmin()).ok()["active"].asBoolean() shouldBe false
            call(POST, "$typePath/deactivate", asAdmin()).ok()["active"].asBoolean() shouldBe false
            call(POST, "$typePath/deactivate", asAdmin()).ok()["active"].asBoolean() shouldBe false
            roomsApi.activeRoomTypes().map { it.id } shouldNotContain RoomTypeId(type)

            // Tipo desativado: não recebe UH nova, nem a UH reativada, nem a que muda para ele.
            call(POST, "/api/v1/rooms", asAdmin(), rooms.roomBody(type)).problem(422, "ROOM_TYPE_INACTIVE")
            call(POST, "$roomPath/reactivate", asAdmin()).problem(422, "ROOM_TYPE_INACTIVE")
            val otherRoom = rooms.createRoom(rooms.createRoomType())
            val otherPath = "/api/v1/rooms/$otherRoom"
            call(PUT, otherPath, asAdmin(), rooms.roomBody(type), ifMatch = rooms.etagOf(otherPath))
                .problem(422, "ROOM_TYPE_INACTIVE")
            call(PUT, otherPath, asAdmin(), rooms.roomBody(UUID.randomUUID()), ifMatch = rooms.etagOf(otherPath))
                .problem(400, "UNKNOWN_ROOM_TYPE")["errors"][0]["field"]
                .asString() shouldBe "roomTypeId"

            call(POST, "$typePath/reactivate", asAdmin()).ok()["active"].asBoolean() shouldBe true
            call(POST, "$roomPath/reactivate", asAdmin()).ok()["active"].asBoolean() shouldBe true
            auditActions("rooms.room-type", type) shouldContainExactly
                listOf("rooms.room-type.create", "rooms.room-type.deactivate", "rooms.room-type.reactivate")
        }

    @Test
    fun `room types are listed in display order with a stable cursor`(): Unit =
        with(iam) {
            val ids = listOf(5, 0, 5).map { rooms.createRoomType(rooms.roomTypeBody(displayOrder = it)) }
            val listed = rooms.listAll("/api/v1/room-types", limit = 2)
            val keys = listed.map { it["displayOrder"].asInt() to it["id"].asString() }
            keys shouldBeSortedWith compareBy<Pair<Int, String>>({ it.first }, { it.second })
            keys.map { it.second } shouldContainAll ids.map { it.toString() }
            keys.map { it.second }.toSet().size shouldBe keys.size
            call(GET, "/api/v1/room-types?cursor=nao-e-cursor", asAdmin()).problem(400, "INVALID_CURSOR")
        }

    // --- UHs -----------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // um cenário de ponta a ponta, com cada recusa no seu passo
    fun `a room is registered, edited, deactivated and reactivated, with a unique number`(): Unit =
        with(iam) {
            val reader = createUserWith(TestGrant(definition(RoomsPermissions.REGISTRY_READ), ALL))
            val type = rooms.createRoomType()
            val otherType = rooms.createRoomType()
            val seaView = rooms.createFeature()
            val number = rooms.uniqueNumber() + "a"
            call(POST, "/api/v1/rooms", asTestUser(reader), rooms.roomBody(type, number))
                .problem(403, "PERMISSION_DENIED")

            val id = rooms.createRoom(type, number, listOf(seaView))
            val path = "/api/v1/rooms/$id"
            call(GET, path, asTestUser(reader)).ok().let {
                it["number"].asString() shouldBe number
                it["floor"].asString() shouldBe "1º andar"
                it["roomTypeId"].asString() shouldBe type.toString()
                it["featureIds"].values().map { f -> f.asString() } shouldContainExactly listOf(seaView.toString())
                it["notes"].asString() shouldBe "Perto do elevador"
                it["active"].asBoolean() shouldBe true
            }

            // Número repetido, em qualquer caixa: 409 ROOM_NUMBER_ALREADY_IN_USE, na criação e na edição.
            call(POST, "/api/v1/rooms", asAdmin(), rooms.roomBody(otherType, number.uppercase()))
                .problem(409, "ROOM_NUMBER_ALREADY_IN_USE")
            val other = rooms.createRoom(otherType)
            val otherPath = "/api/v1/rooms/$other"
            call(PUT, otherPath, asAdmin(), rooms.roomBody(otherType, number), ifMatch = rooms.etagOf(otherPath))
                .problem(409, "ROOM_NUMBER_ALREADY_IN_USE")
            call(POST, "/api/v1/rooms", asAdmin(), rooms.roomBody(type, "12 B")).problem(400, "VALIDATION_FAILED")
            call(POST, "/api/v1/rooms", asAdmin(), rooms.roomBody(UUID.randomUUID()))
                .problem(400, "UNKNOWN_ROOM_TYPE")
            call(POST, "/api/v1/rooms", asAdmin(), rooms.roomBody(type, featureIds = listOf(UUID.randomUUID())))
                .problem(400, "UNKNOWN_ROOM_FEATURE")

            val etag = rooms.etagOf(path)
            val edited = rooms.roomBody(otherType, number, featureIds = emptyList(), floor = null)
            call(PUT, path, asTestUser(reader), edited, ifMatch = etag).problem(403, "PERMISSION_DENIED")
            call(PUT, path, asAdmin(), edited).problem(428, "PRECONDITION_REQUIRED")
            call(PUT, path, asAdmin(), edited, ifMatch = "\"999\"").problem(412, "PRECONDITION_FAILED")
            call(PUT, path, asAdmin(), edited, ifMatch = etag).ok().let {
                it["roomTypeId"].asString() shouldBe otherType.toString()
                it["floor"].isNull shouldBe true
                it["featureIds"].values() shouldBe emptyList()
            }

            call(POST, "$path/deactivate", asTestUser(reader)).problem(403, "PERMISSION_DENIED")
            call(POST, "$path/deactivate", asAdmin()).ok()["active"].asBoolean() shouldBe false
            rooms.listAll("/api/v1/rooms?roomTypeId=$otherType&active=true").map { it["id"].asString() } shouldBe
                listOf(other.toString())
            rooms.listAll("/api/v1/rooms?roomTypeId=$otherType&active=false").map { it["id"].asString() } shouldBe
                listOf(id.toString())
            // Desativada, a UH continua lá e o número continua dela.
            call(
                POST,
                "/api/v1/rooms",
                asAdmin(),
                rooms.roomBody(type, number),
            ).problem(409, "ROOM_NUMBER_ALREADY_IN_USE")
            call(POST, "$path/reactivate", asAdmin()).ok()["active"].asBoolean() shouldBe true

            call(GET, "/api/v1/rooms/${UUID.randomUUID()}", asAdmin()).problem(404, "ROOM_NOT_FOUND")
            call(GET, "/api/v1/rooms", asTestUser(createUser())).problem(403, "PERMISSION_DENIED")
            auditActions("rooms.room", id) shouldContainExactly
                listOf("rooms.room.create", "rooms.room.update", "rooms.room.deactivate", "rooms.room.reactivate")
            auditStates("rooms.room", id).first().let { (before, after) ->
                before.shouldBeNull()
                after.shouldNotBeNull() shouldContainSubstring "\"number\": \"$number\""
            }
            refusesDelete("rooms.room", id)
        }

    @Test
    fun `two rooms with the same number at the same time - one is created, the other gets 409, never 500`(): Unit =
        with(iam) {
            val type = rooms.createRoomType()
            val number = rooms.uniqueNumber()
            val inserted = CountDownLatch(1)
            // Outra transação grava o número e segura o commit: a requisição espera o índice único e,
            // depois do commit, recebe a violação, que vira 409.
            val holder =
                thread {
                    transactions.executeWithoutResult {
                        jdbc
                            .sql(
                                """
                                INSERT INTO rooms.room (id, number, room_type_id, active, created_at, updated_at, version)
                                VALUES (:id, :number, :type, true, :now, :now, 0)
                                """.trimIndent(),
                            ).param("id", UUID.randomUUID())
                            .param("number", number)
                            .param("type", type)
                            .param("now", java.sql.Timestamp.from(Instant.now()))
                            .update()
                        inserted.countDown()
                        Thread.sleep(LOCK_HOLD_MILLIS)
                    }
                }
            inserted.await()
            call(POST, "/api/v1/rooms", asAdmin(), rooms.roomBody(type, number.uppercase()))
                .problem(409, "ROOM_NUMBER_ALREADY_IN_USE")
            holder.join()

            // O banco garante a unicidade mesmo fora da aplicação.
            shouldThrow<DataIntegrityViolationException> {
                transactions.executeWithoutResult {
                    jdbc
                        .sql(
                            """
                            INSERT INTO rooms.room (id, number, room_type_id, active, created_at, updated_at, version)
                            VALUES (:id, :number, :type, false, now(), now(), 0)
                            """.trimIndent(),
                        ).param("id", UUID.randomUUID())
                        .param("number", number.uppercase())
                        .param("type", type)
                        .update()
                }
            }
        }

    // --- Áreas ---------------------------------------------------------------------------------------

    @Test
    fun `common areas are registered, edited, deactivated and reactivated, with a unique name`(): Unit =
        with(iam) {
            val reader = createUserWith(TestGrant(definition(RoomsPermissions.REGISTRY_READ), ALL))
            val name = rooms.uniqueName("Lobby")
            call(POST, "/api/v1/areas", asTestUser(reader), rooms.areaBody(name, AreaKind.LOBBY))
                .problem(403, "PERMISSION_DENIED")
            val id = rooms.createArea(name, AreaKind.LOBBY)
            val path = "/api/v1/areas/$id"
            call(GET, path, asTestUser(reader)).ok().let {
                it["name"].asString() shouldBe name
                it["kind"].asString() shouldBe "LOBBY"
                it["description"].asString() shouldBe "Área externa"
            }
            call(POST, "/api/v1/areas", asAdmin(), rooms.areaBody(name.uppercase(), AreaKind.HALL))
                .problem(409, "AREA_NAME_ALREADY_IN_USE")
            call(POST, "/api/v1/areas", asAdmin(), mapOf("name" to "X", "kind" to "GARAGE"))
                .expectStatus(400)

            val etag = rooms.etagOf(path)
            val edited = rooms.areaBody("$name II", AreaKind.CORRIDOR)
            call(PUT, path, asTestUser(reader), edited, ifMatch = etag).problem(403, "PERMISSION_DENIED")
            call(PUT, path, asAdmin(), edited).problem(428, "PRECONDITION_REQUIRED")
            val otherName = rooms.uniqueName("Cozinha")
            rooms.createArea(otherName, AreaKind.KITCHEN)
            call(PUT, path, asAdmin(), rooms.areaBody(otherName, AreaKind.KITCHEN), ifMatch = etag)
                .problem(409, "AREA_NAME_ALREADY_IN_USE")
            call(PUT, path, asAdmin(), edited, ifMatch = etag).ok()["kind"].asString() shouldBe "CORRIDOR"

            call(POST, "$path/deactivate", asAdmin()).ok()["active"].asBoolean() shouldBe false
            rooms.listAll("/api/v1/areas?kind=CORRIDOR&active=false").map { it["id"].asString() } shouldContain
                id.toString()
            roomsApi.area(AreaId(id)).shouldNotBeNull().active shouldBe false
            call(POST, "$path/reactivate", asAdmin()).ok()["active"].asBoolean() shouldBe true

            call(GET, "/api/v1/areas/${UUID.randomUUID()}", asAdmin()).problem(404, "AREA_NOT_FOUND")
            call(GET, "/api/v1/areas", asTestUser(createUser())).problem(403, "PERMISSION_DENIED")
            auditActions("rooms.area", id) shouldContainExactly
                listOf("rooms.area.create", "rooms.area.update", "rooms.area.deactivate", "rooms.area.reactivate")
            refusesDelete("rooms.area", id)
        }

    // --- API pública, permissões e mapeamentos ---------------------------------------------------------

    @Test
    fun `the public API reads rooms, types and areas, and a deactivated room leaves the active lists`(): Unit =
        with(iam) {
            val feature = rooms.createFeature()
            val type = rooms.createRoomType(rooms.roomTypeBody(featureIds = listOf(feature)))
            val first = rooms.createRoom(type, "0" + rooms.uniqueNumber().take(7))
            val second = rooms.createRoom(type, "1" + rooms.uniqueNumber().take(7), listOf(feature))
            val area = rooms.createArea(kind = AreaKind.HALL)

            roomsApi.room(RoomId(second)).shouldNotBeNull().let {
                it.roomTypeId shouldBe RoomTypeId(type)
                it.featureIds shouldBe setOf(RoomFeatureId(feature))
                it.active shouldBe true
            }
            roomsApi.roomType(RoomTypeId(type)).shouldNotBeNull().featureIds shouldBe setOf(RoomFeatureId(feature))
            roomsApi.area(AreaId(area)).shouldNotBeNull().kind shouldBe AreaKind.HALL
            roomsApi.room(RoomId(UUID.randomUUID())).shouldBeNull()
            roomsApi.roomType(RoomTypeId(UUID.randomUUID())).shouldBeNull()
            roomsApi.area(AreaId(UUID.randomUUID())).shouldBeNull()
            roomsApi.activeRooms(RoomTypeId(type)).map { it.id } shouldContainExactly
                listOf(RoomId(first), RoomId(second))
            roomsApi.activeRoomTypes().map { it.id } shouldContain RoomTypeId(type)

            call(POST, "/api/v1/rooms/$first/deactivate", asAdmin()).ok()
            roomsApi.activeRooms(RoomTypeId(type)).map { it.id } shouldContainExactly listOf(RoomId(second))
            roomsApi.activeRooms().map { it.id } shouldNotContain RoomId(first)
            roomsApi.activeRooms().map { it.id } shouldContain RoomId(second)
            roomsApi.room(RoomId(first)).shouldNotBeNull().active shouldBe false
        }

    @Test
    fun `the role templates of SPEC 3_1 get the rooms permissions`() {
        val read = definition(RoomsPermissions.REGISTRY_READ)
        val manage = definition(RoomsPermissions.REGISTRY_MANAGE)
        val frontAndHousekeeping = listOf(RoleTemplate.MANAGER, RoleTemplate.RECEPTIONIST, RoleTemplate.HOUSEKEEPER)
        val housekeeping = listOf(RoleTemplate.MANAGER, RoleTemplate.HOUSEKEEPER)
        assertSoftly {
            read.templates.keys shouldContainExactlyInAnyOrder frontAndHousekeeping
            manage.templates.keys shouldContainExactly listOf(RoleTemplate.MANAGER)
            definition(RoomsPermissions.STATUS_READ).templates.keys shouldContainExactlyInAnyOrder frontAndHousekeeping
            definition(RoomsPermissions.STATUS_MANAGE).templates.keys shouldContainExactlyInAnyOrder housekeeping
            definition(RoomsPermissions.STATUS_FLAG).templates.keys shouldContainExactlyInAnyOrder frontAndHousekeeping
            definition(RoomsPermissions.STATUS_OBSERVE).templates.keys shouldContainExactlyInAnyOrder housekeeping
            RoomsPermissions.permissions.none { it.sensitive } shouldBe true
        }
        val catalog =
            jdbc
                .sql("SELECT code FROM iam.permission WHERE code LIKE 'rooms.%' AND obsolete_at IS NULL")
                .query(String::class.java)
                .list()
        catalog shouldContainExactlyInAnyOrder
            listOf(
                "rooms.registry.read",
                "rooms.registry.manage",
                "rooms.status.read",
                "rooms.status.manage",
                "rooms.status.flag",
                "rooms.status.observe",
            )
    }

    @Test
    fun `no rooms endpoint deletes anything`() {
        val roomsMappings =
            handlerMapping.handlerMethods.filter { (_, method) ->
                method.beanType.packageName.startsWith("com.zeny.hotel.rooms")
            }
        roomsMappings.keys
            .flatMap { it.methodsCondition.methods }
            .map { it.name }
            .toSet() shouldBe setOf("GET", "POST", "PUT")
        roomsMappings.keys.flatMap { it.patternValues }.toSet() shouldContainExactlyInAnyOrder
            listOf("/api/v1/room-types", "/api/v1/room-features", "/api/v1/rooms", "/api/v1/areas").flatMap {
                listOf(it, "$it/{id}", "$it/{id}/deactivate", "$it/{id}/reactivate")
            } +
            listOf(
                "/api/v1/rooms/status",
                "/api/v1/rooms/discrepancies",
                "/api/v1/rooms/{id}/status",
                "/api/v1/rooms/{id}/status-history",
                "/api/v1/rooms/{id}/housekeeping-status",
                "/api/v1/rooms/{id}/service-flag",
                "/api/v1/rooms/{id}/housekeeping-observations",
            )
    }

    // --- Apoio ---------------------------------------------------------------------------------------

    /** O papel da aplicação não apaga o registro: o trigger recusa, e a linha continua lá. */
    private fun refusesDelete(
        table: String,
        id: UUID,
    ) {
        val error =
            shouldThrow<DataAccessException> {
                transactions.executeWithoutResult {
                    jdbc.sql("DELETE FROM $table WHERE id = :id").param("id", id).update()
                }
            }
        withClue(error.toString()) {
            generateSequence<Throwable>(
                error,
            ) { it.cause }.any { "nunca são apagados" in it.message.orEmpty() } shouldBe
                true
        }
        jdbc
            .sql("SELECT count(*) FROM $table WHERE id = :id")
            .param("id", id)
            .query(Int::class.java)
            .single() shouldBe 1
    }

    private fun auditActions(
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

    private fun auditStates(
        entityType: String,
        entityId: UUID,
    ): List<Pair<String?, String?>> =
        jdbc
            .sql(
                "SELECT before_state::text, after_state::text FROM audit.audit_entry " +
                    "WHERE entity_type = :type AND entity_id = :id ORDER BY occurred_at, id",
            ).param("type", entityType)
            .param("id", entityId.toString())
            .query { rs, _ -> rs.getString(1) to rs.getString(2) }
            .list()

    private companion object {
        const val LOCK_HOLD_MILLIS = 1500L
    }
}
