package com.zeny.hotel.realtime

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.OutOfOrderKind
import com.zeny.hotel.rooms.OutOfOrderRequest
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.rooms.RoomsPermissions
import com.zeny.hotel.rooms.RoomsTestSupport
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.OUTBOX_PURGE_DEFERRED
import com.zeny.hotel.testsupport.PostgresInitializer
import com.zeny.hotel.testsupport.RESUBMISSION_JOB_DEFERRED
import com.zeny.hotel.testsupport.SseFrame
import com.zeny.hotel.testsupport.SseTestClient
import com.zeny.hotel.testsupport.TEST_MIGRATIONS
import com.zeny.hotel.testsupport.TestAuthInitializer
import com.zeny.hotel.testsupport.TestEncryptionKeysInitializer
import com.zeny.hotel.testsupport.TestJwtKeys
import com.zeny.hotel.testsupport.TestStorageInitializer
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import io.micrometer.core.instrument.MeterRegistry
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.transaction.support.TransactionTemplate
import realtimeexample.RealtimeExampleConfiguration
import realtimeexample.RealtimeExamplePermissions
import realtimeexample.RealtimeExamplePublisher
import realtimeexample.RealtimeExampleTopics
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.util.Date
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Canal de tempo real (M2 E04, ARCHITECTURE §10, D10, RNF-04, H5, H8) com um cliente SSE de verdade, num
 * servidor de porta aleatória (o `@IntegrationTest` é servlet simulado): inscrição por permissão e escopo,
 * recebimento em menos de 2 s, payload mínimo, reconexão com `Last-Event-ID` e `reset`, nada antes do
 * commit nem em rollback, encerramento na revogação da sessão, na desativação do usuário e no `exp` do
 * token, *heartbeat*, limite de conexões por sessão e liberação das conexões encerradas.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        TEST_MIGRATIONS,
        RESUBMISSION_JOB_DEFERRED,
        OUTBOX_PURGE_DEFERRED,
        "zeny.realtime.buffer-size=8",
        "zeny.realtime.heartbeat-interval=PT1S",
        "zeny.realtime.max-connections-per-session=2",
    ],
)
@ActiveProfiles("local")
@AutoConfigureMockMvc
@ContextConfiguration(
    initializers = [
        PostgresInitializer::class,
        TestEncryptionKeysInitializer::class,
        TestAuthInitializer::class,
        TestStorageInitializer::class,
    ],
)
@Import(IamTestConfiguration::class, RealtimeExampleConfiguration::class)
@Suppress("LargeClass", "LongParameterList") // cenários de ponta a ponta do protocolo; os beans de que eles precisam
class RealtimeStreamTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val hotel: HotelApi,
    @Autowired private val notes: RealtimeExamplePublisher,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val meters: MeterRegistry,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val clock: Clock,
    @LocalServerPort private val port: Int,
) {
    private val rooms = RoomsTestSupport(iam)
    private val clients = CopyOnWriteArrayList<SseTestClient>()
    private val baseUrl get() = "http://localhost:$port"

    @BeforeEach
    fun synchronizeTestPermissions() {
        // O banco é compartilhado: outro contexto que suba depois marca as permissões só de teste obsoletas.
        synchronizer.synchronize()
    }

    @AfterEach
    fun closeClients() {
        clients.forEach { it.close() }
    }

    @Test
    fun `a subscriber of rooms receives a status change in under 2 seconds with only type, id and version`() {
        val token = tokenOf(iam.createUserWith(roomsStatusRead()))
        val room = newRoom()
        val client = open(token, "rooms")
        client.expectReady()

        val started = System.nanoTime()
        val status = roomsApi.markClean(room, requiresInspection = false, source = RoomStatusSource.TASK)
        val event = client.nextEvent(Duration.ofSeconds(2)).shouldNotBeNull()
        Duration.ofNanos(System.nanoTime() - started).toMillis() shouldBeLessThan 2_000

        event.event shouldBe "rooms"
        event.id.shouldNotBeNull()
        val data = json(event)
        data.propertyNames().toList() shouldContainExactly listOf("type", "id", "version")
        data["type"].asString() shouldBe "rooms.room-status"
        data["id"].asString() shouldBe room.value.toString()
        data["version"].asLong() shouldBe status.version
    }

    @Test
    fun `the rooms topic also carries out-of-order periods, by their id and version`() {
        val token = tokenOf(iam.createUserWith(roomsStatusRead()))
        val room = newRoom()
        val client = open(token, "rooms")
        client.expectReady()

        val today = hotel.currentBusinessDate()
        val period =
            roomsApi.openOutOfOrder(
                room,
                OutOfOrderKind.OUT_OF_SERVICE,
                OutOfOrderRequest(today.plusDays(1), today.plusDays(2), "Pintura", "Equipe", HousekeepingStatus.DIRTY),
            )

        val data = json(client.nextEvent(Duration.ofSeconds(2)).shouldNotBeNull())
        data.propertyNames().toList() shouldContainExactly listOf("type", "id", "version")
        data["type"].asString() shouldBe "rooms.out-of-order"
        data["id"].asString() shouldBe period.id.value.toString()
        data["version"].asLong() shouldBe period.version
    }

    @Test
    fun `subscribing needs a token, known topics and the permission of each one`() {
        val reader = tokenOf(iam.createUserWith(roomsStatusRead()))
        val nobody = tokenOf(iam.createUser())

        open(null, "rooms").problem(401, "UNAUTHENTICATED")
        open(nobody, "rooms").problem(403, "PERMISSION_DENIED")
        // Uma permissão não cobre a outra: com rooms, mas sem a do tópico de exemplo.
        open(reader, "rooms,example-notes").problem(403, "PERMISSION_DENIED")
        open(reader, "rooms,unknown-topic").problem(400, "UNKNOWN_STREAM_TOPIC")
        open(reader, null).problem(400, "VALIDATION_FAILED")
        open(reader, " , ").problem(400, "VALIDATION_FAILED")
    }

    @Test
    fun `with the OWN scope the subscriber only receives the messages of its own records`() {
        val own = iam.createUserWith(TestGrant(notesRead(), OWN))
        val all = iam.createUserWith(TestGrant(notesRead(), ALL))
        val ownClient = open(tokenOf(own), "example-notes").also { it.expectReady() }
        val allClient = open(tokenOf(all), "example-notes").also { it.expectReady() }

        val mine = notes.publish(owners = setOf(own.id))
        val shared = notes.publish(owners = setOf(UserId(UuidV7.generate()), own.id))
        val others = notes.publish(owners = setOf(all.id))
        val ownerless = notes.publish()

        ownClient.eventsWithin(Duration.ofSeconds(2)).map { json(it)["id"].asString() } shouldContainExactly
            listOf(mine, shared).map(UUID::toString)
        allClient.eventsWithin(Duration.ofSeconds(1)).map { json(it)["id"].asString() } shouldContainExactly
            listOf(mine, shared, others, ownerless).map(UUID::toString)
    }

    @Test
    fun `an OWN subscriber reconnecting with Last-Event-ID gets only its own missed messages, in order`() {
        val own = iam.createUserWith(TestGrant(notesRead(), OWN))
        val token = tokenOf(own)
        val first = open(token, "example-notes")
        val position = first.expectReady()
        first.close()

        val mine1 = notes.publish(owners = setOf(own.id))
        notes.publish(owners = setOf(UserId(UuidV7.generate())))
        val mine2 = notes.publish(owners = setOf(UserId(UuidV7.generate()), own.id))
        notes.publish()
        val mine3 = notes.publish(owners = setOf(own.id))

        val again = open(token, "example-notes", lastEventId = position)
        val replayed = generateSequence { again.nextEvent() }.takeWhile { it.event != "ready" }.toList()
        replayed.map { json(it)["id"].asString() } shouldContainExactly listOf(mine1, mine2, mine3).map(UUID::toString)
        replayed.map { sequence(it.id!!) }.zipWithNext().forEach { (a, b) -> a shouldBeLessThan b }
    }

    @Test
    fun `a reconnection with Last-Event-ID replays the missed events in order`() {
        val token = tokenOf(iam.createUserWith(TestGrant(notesRead(), ALL), roomsStatusRead()))
        val first = open(token, "example-notes")
        val position = first.expectReady()
        first.close()

        val missed = (1..3).map { notes.publish(version = it.toLong()) }
        // Uma mudança de outro tópico, a que esta conexão não se inscreveu, não entra na reposição.
        roomsApi.markClean(newRoom(), requiresInspection = false, source = RoomStatusSource.TASK)

        val again = open(token, "example-notes", lastEventId = position)
        val replayed = listOf(again.nextEvent(), again.nextEvent(), again.nextEvent()).map { it.shouldNotBeNull() }
        replayed.map { json(it)["id"].asString() } shouldContainExactly missed.map(UUID::toString)
        replayed.map { it.event } shouldContainExactly List(3) { "example-notes" }
        replayed.map { it.id!! }.zipWithNext().forEach { (a, b) -> sequence(a) shouldBeLessThan sequence(b) }
        sequence(position) shouldBeLessThan sequence(replayed.first().id!!)
        // Depois da reposição, a posição corrente: o cliente segue dela.
        val ready = again.expectReady()
        sequence(replayed.last().id!!) shouldBeLessThanOrEqual sequence(ready)

        // Ao vivo, depois da reposição.
        val live = notes.publish()
        json(again.nextEvent().shouldNotBeNull())["id"].asString() shouldBe live.toString()
    }

    @Test
    fun `a Last-Event-ID that left the buffer, from another boot or malformed receives a reset`() {
        val token = tokenOf(iam.createUserWith(TestGrant(notesRead(), ALL)))
        val first = open(token, "example-notes")
        val position = first.expectReady()
        first.close()
        // O buffer deste contexto guarda 8 mensagens por tópico.
        repeat(10) { notes.publish() }

        for (lastEventId in listOf(position, "0-1", "garbage", "${position.substringBefore('-')}-999999999")) {
            withClue(lastEventId) {
                val client = open(token, "example-notes", lastEventId = lastEventId)
                val reset = client.nextEvent().shouldNotBeNull()
                reset.event shouldBe "reset"
                reset.id.shouldNotBeNull() shouldStartWith position.substringBefore('-')
                client.eventsWithin(Duration.ofMillis(300)).shouldBeEmpty()
                client.close()
            }
        }
    }

    @Test
    fun `nothing is sent before the commit, and a rolled back transaction sends nothing`() {
        val token = tokenOf(iam.createUserWith(TestGrant(notesRead(), ALL), roomsStatusRead()))
        val client = open(token, "example-notes,rooms")
        client.expectReady()

        val rolledBack = notes.publish(rollback = true)
        val room = newRoom()
        transactions.executeWithoutResult { status ->
            roomsApi.markClean(room, requiresInspection = false, source = RoomStatusSource.TASK)
            status.setRollbackOnly()
        }
        client.eventsWithin(Duration.ofMillis(700)).shouldBeEmpty()

        val committed = UUID.randomUUID()
        transactions.executeWithoutResult {
            notes.publish(id = committed)
            // Dentro da transação ainda aberta, nada chegou ao cliente.
            client.eventsWithin(Duration.ofMillis(700)).shouldBeEmpty()
        }
        val event = client.nextEvent(Duration.ofSeconds(2)).shouldNotBeNull()
        json(event)["id"].asString() shouldBe committed.toString()
        json(event)["id"].asString() shouldNotBe rolledBack.toString()
    }

    @Test
    fun `revoking a session closes its connections, and its still valid token cannot reconnect`() {
        val user = iam.createUserWith(roomsStatusRead())
        val phone = iam.loginOk(user, deviceId = "phone")
        val tablet = iam.loginOk(user, deviceId = "tablet")
        val phoneClient = open(phone["accessToken"].asString(), "rooms").also { it.expectReady() }
        val tabletClient = open(tablet["accessToken"].asString(), "rooms").also { it.expectReady() }

        iam
            .postWithToken(
                "/api/v1/auth/sessions/${phone["sessionId"].asString()}/revoke",
                tablet["accessToken"].asString(),
            ).status shouldBe 204

        phoneClient.awaitEnd(Duration.ofSeconds(2)) shouldBe true
        tabletClient.isEnded shouldBe false
        // O access token ainda não venceu, mas a sessão foi revogada.
        open(phone["accessToken"].asString(), "rooms").problem(401, "UNAUTHENTICATED")

        iam.postWithToken("/api/v1/auth/logout", tablet["accessToken"].asString()).status shouldBe 204
        tabletClient.awaitEnd(Duration.ofSeconds(2)) shouldBe true

        // A inscrição confere a sessão no banco (o access token não diz que ela foi revogada), mesmo sem o evento.
        val laptop = iam.loginOk(user, deviceId = "laptop")
        jdbc
            .sql("UPDATE iam.device_session SET revoked_at = now(), revocation_reason = 'LOGOUT' WHERE id = :id")
            .param("id", UUID.fromString(laptop["sessionId"].asString()))
            .update() shouldBe 1
        open(laptop["accessToken"].asString(), "rooms").problem(401, "UNAUTHENTICATED")
    }

    @Test
    fun `deactivating the user closes all of its connections`() {
        val user = iam.createUserWith(roomsStatusRead())
        val clientA = open(tokenOf(user, "device-a"), "rooms").also { it.expectReady() }
        val clientB = open(tokenOf(user, "device-b"), "rooms").also { it.expectReady() }

        iam.call(POST, "/api/v1/users/${user.id}/deactivate", iam.asAdmin()).status shouldBe 200

        clientA.awaitEnd(Duration.ofSeconds(2)) shouldBe true
        clientB.awaitEnd(Duration.ofSeconds(2)) shouldBe true
    }

    @Test
    fun `the connection ends when the access token expires`() {
        val user = iam.createUserWith(roomsStatusRead())
        val session = iam.loginOk(user)
        val expiresAt = clock.instant().plusSeconds(3)
        val token = signToken(user, UUID.fromString(session["sessionId"].asString()), expiresAt)
        val client = open(token, "rooms")
        client.expectReady()

        client.awaitEnd(Duration.ofMillis(1_500)) shouldBe false
        client.awaitEnd(Duration.ofSeconds(4)) shouldBe true
    }

    @Test
    fun `heartbeats keep an idle connection alive`() {
        val client = open(tokenOf(iam.createUserWith(roomsStatusRead())), "rooms")
        client.expectReady()

        val frames = generateSequence { client.next(Duration.ofSeconds(3)) }.take(2).toList()
        frames.size shouldBe 2
        frames.forEach { it.isComment shouldBe true }
    }

    @Test
    fun `a session keeps at most the configured connections, closing the oldest`() {
        val token = tokenOf(iam.createUserWith(roomsStatusRead()))
        val oldest = open(token, "rooms").also { it.expectReady() }
        val middle = open(token, "rooms").also { it.expectReady() }
        val newest = open(token, "rooms").also { it.expectReady() }

        oldest.awaitEnd(Duration.ofSeconds(2)) shouldBe true
        middle.isEnded shouldBe false
        newest.isEnded shouldBe false
    }

    @Test
    fun `connections closed by the client are released`() {
        val token = tokenOf(iam.createUserWith(roomsStatusRead()))
        await().atMost(Duration.ofSeconds(10)).until { connections() == 0.0 }

        val a = open(token, "rooms").also { it.expectReady() }
        val b = open(token, "rooms").also { it.expectReady() }
        connections() shouldBe 2.0

        a.close()
        b.close()
        // O servidor nota a desconexão na escrita seguinte (o heartbeat, a cada segundo neste contexto).
        await().atMost(Duration.ofSeconds(5)).until { connections() == 0.0 }
    }

    private fun newRoom(): RoomId = RoomId(rooms.createRoom(rooms.createRoomType()))

    private fun roomsStatusRead() = TestGrant(RoomsTestSupport.definition(RoomsPermissions.STATUS_READ), ALL)

    private fun notesRead() = RealtimeExamplePermissions.permissions.single()

    private fun tokenOf(
        user: TestUser,
        deviceId: String = "device-${UuidV7.generate()}",
    ): String = iam.loginOk(user, deviceId)["accessToken"].asString()

    private fun open(
        token: String?,
        topics: String?,
        lastEventId: String? = null,
    ): SseTestClient = SseTestClient.open(baseUrl, token, topics, lastEventId).also { clients += it }

    /** Confere o primeiro evento (`ready`) e devolve a posição dele. */
    private fun SseTestClient.expectReady(): String {
        withClue(errorBody) { status shouldBe 200 }
        contentType.shouldNotBeNull() shouldStartWith "text/event-stream"
        val ready = nextEvent().shouldNotBeNull()
        ready.event shouldBe "ready"
        return ready.id.shouldNotBeNull()
    }

    private fun SseTestClient.problem(
        status: Int,
        code: String,
    ) {
        withClue(errorBody) {
            this.status shouldBe status
            contentType.shouldNotBeNull() shouldStartWith "application/problem+json"
            jsonMapper.readTree(errorBody)["code"].asString() shouldBe code
        }
        close()
    }

    private fun json(frame: SseFrame): JsonNode = jsonMapper.readTree(frame.data.shouldNotBeNull())

    private fun sequence(eventId: String): Long = eventId.substringAfterLast('-').toLong()

    private fun connections(): Double = meters.get("zeny.realtime.connections").gauge().value()

    /** Um access token assinado pela chave ativa dos testes, da sessão real, que vence em [expiresAt]. */
    private fun signToken(
        user: TestUser,
        sessionId: UUID,
        expiresAt: java.time.Instant,
    ): String {
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer("zeny-hotel-manager")
                .subject(user.id.toString())
                .issueTime(Date.from(clock.instant()))
                .expirationTime(Date.from(expiresAt))
                .jwtID(UuidV7.generate().toString())
                .claim("sid", sessionId.toString())
                .build()
        val header =
            JWSHeader
                .Builder(
                    JWSAlgorithm.RS256,
                ).type(JOSEObjectType.JWT)
                .keyID(TestJwtKeys.ACTIVE_KEY_ID)
                .build()
        return SignedJWT(header, claims).apply { sign(RSASSASigner(TestJwtKeys.active.private)) }.serialize()
    }
}
