package com.zeny.hotel.sync

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.rooms.RoomsPermissions
import com.zeny.hotel.rooms.RoomsTestSupport
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.shared.UuidV7Generator
import com.zeny.hotel.shared.infrastructure.sync.OfflineCommandPurgeJob
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import syncexample.RecordNoteHandler
import syncexample.SyncExample
import syncexample.SyncExampleConfiguration
import syncexample.SyncExamplePermissions
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Sincronização offline (M2 E05, ARCHITECTURE §9, D11, RNF-05, RN-13, RN-22, H6): download inicial e
 * incremental com *tombstones* (da linha desativada e da tabela do `shared`), escopo `OWN` e `reset` quando as
 * permissões mudam, nenhuma mudança perdida com transações concorrentes, a fonte `rooms` sem dado pessoal, e
 * o upload de comandos: ordem, idempotência por `commandId` (no lote, entre lotes, em requisições
 * simultâneas e entre usuários), rejeição sem interromper o lote, falha inesperada, correção do relógio,
 * retenção e expurgo. A fonte e os comandos de exemplo ficam em `src/test/kotlin/syncexample/`.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, SyncExampleConfiguration::class, MutableClockConfiguration::class)
@Suppress("LargeClass", "LongParameterList") // o protocolo inteiro; os beans de que os cenários precisam
class OfflineSyncTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val clock: MutableClock,
    @Autowired private val purgeJob: OfflineCommandPurgeJob,
    @Autowired private val recordHandler: RecordNoteHandler,
) {
    private val rooms = RoomsTestSupport(iam)

    @BeforeEach
    fun synchronizeTestPermissions() {
        // O banco é compartilhado: outro contexto que suba depois marca as permissões só de teste obsoletas.
        synchronizer.synchronize()
    }

    // --- Download ---------------------------------------------------------------------------------------

    @Test
    fun `the first download brings every active room and later ones only what changed, with tombstones`() {
        val reader = auth(iam.createUserWith(roomsOfflineRead()))
        val type = rooms.createRoomType()
        val kept = RoomId(rooms.createRoom(type))
        val retired = RoomId(rooms.createRoom(type))

        val first = downloadAll(reader)
        first.reset shouldBe true
        val firstRooms = first.changes.filter { it["source"].asString() == "rooms" }
        firstRooms.map { it["deleted"].asBoolean() }.distinct() shouldContainExactly listOf(false)
        firstRooms.ids() shouldContain kept.value
        firstRooms.ids() shouldContain retired.value
        val activeInDatabase =
            jdbc
                .sql("SELECT id FROM rooms.room WHERE active")
                .query(UUID::class.java)
                .list()
                .toSet()
        withClue("o download completo traz todas as UHs ativas e nenhuma desativada") {
            firstRooms.ids().toSet() shouldBe activeInDatabase
        }

        // Nada mudou: o incremental vem vazio.
        downloadAll(reader, first.cursor).changes.ids() shouldNotContain kept.value

        val status = roomsApi.markClean(kept, requiresInspection = true, source = RoomStatusSource.TASK)
        with(iam) { call(POST, "/api/v1/rooms/${retired.value}/deactivate", iam.asAdmin()).ok() }

        val second = downloadAll(reader, first.cursor)
        second.reset shouldBe false
        val keptChange = second.changes.single { it["id"].asString() == kept.value.toString() }
        keptChange["deleted"].asBoolean() shouldBe false
        keptChange["version"].asLong() shouldBe status.version
        keptChange["data"]["housekeepingStatus"].asString() shouldBe "CLEAN"
        keptChange["data"]["inspectionPending"].asBoolean() shouldBe true
        val tombstone = second.changes.single { it["id"].asString() == retired.value.toString() }
        tombstone["deleted"].asBoolean() shouldBe true
        tombstone["data"].isNull shouldBe true

        downloadAll(reader, second.cursor).changes.ids() shouldNotContain kept.value
    }

    @Test
    fun `the rooms source carries only number, floor and status, never guest data (RN-13)`() {
        val reader = auth(iam.createUserWith(roomsOfflineRead()))
        val room = RoomId(rooms.createRoom(rooms.createRoomType()))
        val cursor = downloadAll(reader).cursor
        // Observações livres da UH podem ter qualquer coisa; o número de hóspedes é da recepção.
        jdbc
            .sql("UPDATE rooms.room SET notes = 'Hóspede Fulano de Tal, CPF 123' WHERE id = :id")
            .param("id", room.value)
            .update()
        roomsApi.markOccupied(room, guestCount = 3, source = RoomStatusSource.CHECK_IN)

        val response = with(iam) { call(GET, "/api/v1/sync/changes?limit=200&cursor=$cursor", reader).ok() }
        val body = response.toString()
        val change = response["changes"].values().single { it["id"].asString() == room.value.toString() }

        change.propertyNames().toList() shouldContainExactly listOf("source", "id", "version", "deleted", "data")
        change["data"].propertyNames().toList() shouldContainExactlyInAnyOrder
            listOf("number", "floor", "occupancy", "housekeepingStatus", "inspectionPending", "serviceFlag")
        change["data"]["occupancy"].asString() shouldBe "OCCUPIED"
        body shouldNotContain "Fulano"
        body shouldNotContain "CPF"
        body shouldNotContain "guestCount"
        body shouldNotContain "notes"
    }

    @Test
    fun `only the sources the user may see are downloaded, and a permission change resets the download`() {
        val nobody = auth(iam.createUser())
        val notesOnly = iam.createUserWith(notesRead(ALL))
        rooms.createRoom(rooms.createRoomType())

        downloadAll(nobody).changes.shouldBeEmpty()
        val onlyNotes = downloadAll(auth(notesOnly))
        onlyNotes.changes.map { it["source"].asString() }.distinct() shouldNotContain "rooms"

        // Ganhou a permissão das UHs: o cursor antigo não serve (faltariam as UHs anteriores a ele).
        iam.assignRole(notesOnly, iam.createRole(roomsOfflineRead()))
        val afterGrant = download(auth(notesOnly), onlyNotes.cursor)
        afterGrant["reset"].asBoolean() shouldBe true
        val all = downloadAll(auth(notesOnly), onlyNotes.cursor)
        all.changes.map { it["source"].asString() }.distinct() shouldContain "rooms"

        // O cursor de outro usuário também recomeça do zero.
        val other = auth(iam.createUserWith(notesRead(ALL)))
        download(other, onlyNotes.cursor)["reset"].asBoolean() shouldBe true
    }

    @Test
    fun `with the OWN scope only the user's notes are downloaded, and a reassigned note leaves as a tombstone`() {
        val writer = iam.createUserWith(notesRead(ALL), notesWrite())
        val owner = iam.createUserWith(notesRead(OWN))
        val other = iam.createUserWith(notesRead(OWN))
        val mine = insertNote(owner.id.value, "minha")
        val theirs = insertNote(other.id.value, "dela")

        val ownerFirst = downloadAll(auth(owner))
        ownerFirst.changes.ids() shouldContain mine
        ownerFirst.changes.ids() shouldNotContain theirs
        val writerFirst = downloadAll(auth(writer))
        writerFirst.changes.ids() shouldContain mine
        writerFirst.changes.ids() shouldContain theirs

        send(auth(writer), command(SyncExample.REASSIGN, mapOf("noteId" to mine, "ownerId" to other.id.value)))
            .single()["status"]
            .asString() shouldBe "APPLIED"

        val ownerNext = downloadAll(auth(owner), ownerFirst.cursor).changes.filter { it.id() == mine }
        ownerNext.map { it["deleted"].asBoolean() } shouldBe listOf(true)
        val otherNext = downloadAll(auth(other)).changes.filter { it.id() == mine }
        otherNext.map { it["deleted"].asBoolean() } shouldBe listOf(false)
        // Quem vê todas continua com a nota: recebe a mudança, não o tombstone de saída de escopo.
        val writerNext = downloadAll(auth(writer), writerFirst.cursor).changes.filter { it.id() == mine }
        writerNext.map { it["deleted"].asBoolean() } shouldBe listOf(false)
    }

    @Test
    fun `a deleted note arrives as a tombstone recorded by the application, but not in a full download`() {
        val writer = iam.createUserWith(notesRead(ALL), notesWrite())
        val note = insertNote(writer.id.value, "apagar")
        val before = downloadAll(auth(writer))
        before.changes.ids() shouldContain note

        send(auth(writer), command(SyncExample.DELETE, mapOf("noteId" to note))).single()["status"].asString() shouldBe
            "APPLIED"

        val after = downloadAll(auth(writer), before.cursor).changes.filter { it.id() == note }
        after.map { it["deleted"].asBoolean() } shouldBe listOf(true)
        after.single()["version"].isNull shouldBe true
        // Quem começa do zero nunca teve a nota: o tombstone antigo não vem.
        downloadAll(auth(writer)).changes.ids() shouldNotContain note
    }

    @Test
    fun `pages follow the cursor without losing or repeating changes`() {
        val writer = iam.createUserWith(notesRead(OWN))
        val notes = (1..7).map { insertNote(writer.id.value, "nota $it") }

        val pages = mutableListOf<JsonNode>()
        var cursor: String? = null
        do {
            val page = download(auth(writer), cursor, limit = 3)
            pages.add(page)
            cursor = page["cursor"].asString()
        } while (page["hasMore"].asBoolean())

        pages.first()["reset"].asBoolean() shouldBe true
        pages.drop(1).map { it["reset"].asBoolean() }.distinct() shouldBe listOf(false)
        pages.flatMap { it["changes"].values() }.ids() shouldContainExactly notes
        download(auth(writer), cursor)["changes"].values().shouldBeEmpty()
    }

    @Test
    fun `an invalid cursor is refused`() {
        val reader = auth(iam.createUserWith(notesRead(ALL)))
        with(iam) {
            call(GET, "/api/v1/sync/changes?cursor=nao-e-um-cursor", reader).problem(400, "INVALID_CURSOR")
            call(GET, "/api/v1/sync/changes?cursor=YzE6YQ", reader).problem(400, "INVALID_CURSOR")
        }
    }

    @Test
    fun `a change committed after a later one is not left behind the cursor`() {
        val reader = iam.createUserWith(notesRead(OWN))
        val early = insertNote(reader.id.value, "a")
        val late = insertNote(reader.id.value, "b")
        val cursor = downloadAll(auth(reader)).cursor
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            // A primeira transação grava (e pega a posição) antes da segunda, mas confirma depois dela.
            val slow =
                executor.submit {
                    transactions.executeWithoutResult {
                        updateNote(early, "a1")
                        started.countDown()
                        release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    }
                }
            started.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
            updateNote(late, "b1")

            val during = downloadAll(auth(reader), cursor)
            withClue("a mudança confirmada fica para depois da transação ainda aberta, que gravou antes dela") {
                during.changes.ids() shouldNotContain late
            }

            release.countDown()
            slow.get(WAIT_SECONDS, TimeUnit.SECONDS)
            val after = downloadAll(auth(reader), during.cursor)
            after.changes.associate { it.id() to it["data"]["text"].asString() } shouldBe
                mapOf(early to "a1", late to "b1")
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `no committed change is lost while concurrent transactions write during the download`() {
        val reader = iam.createUserWith(notesRead(OWN))
        var cursor = downloadAll(auth(reader)).cursor
        val received = ConcurrentHashMap.newKeySet<UUID>()
        val inserted = ConcurrentHashMap.newKeySet<UUID>()
        val writers = Executors.newFixedThreadPool(WRITERS)
        try {
            // Cada escrita é uma linha nova: uma que o cursor pule nunca mais chega ao cliente. As pausas dentro
            // da transação fazem a ordem de commit diferir da ordem do change_seq.
            val jobs =
                (1..WRITERS).map { writer ->
                    writers.submit {
                        repeat(INSERTS_PER_WRITER) { round ->
                            transactions.executeWithoutResult {
                                val id = insertNote(reader.id.value, "w$writer-$round")
                                Thread.sleep(((writer * PAUSE_FACTOR + round) % MAX_PAUSE_MILLIS).toLong())
                                TransactionSynchronizationManager.registerSynchronization(
                                    object : TransactionSynchronization {
                                        override fun afterCommit() {
                                            inserted += id
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            while (jobs.any { !it.isDone }) {
                val page = download(auth(reader), cursor)
                page["changes"].values().forEach { received += it.id() }
                cursor = page["cursor"].asString()
            }
            jobs.forEach { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }
            downloadAll(auth(reader), cursor).changes.forEach { received += it.id() }
        } finally {
            writers.shutdownNow()
        }

        inserted.size shouldBe WRITERS * INSERTS_PER_WRITER
        received shouldBe inserted
    }

    // --- Comandos ---------------------------------------------------------------------------------------

    @Test
    fun `a batch is processed in order and a rejection does not stop it`() {
        val writer = iam.createUserWith(notesRead(ALL), notesWrite())
        val note = UuidV7.generate()
        val results =
            send(
                auth(writer),
                command(SyncExample.RECORD, mapOf("text" to "primeira"), id = note),
                // Depende da anterior: só funciona se o lote for processado em ordem.
                command(SyncExample.ARCHIVE, mapOf("noteId" to note)),
                command(SyncExample.RECORD, mapOf("text" to "reject")),
                command(SyncExample.RECORD, mapOf("text" to "warn: depois da rejeição")),
            )

        results.map { it["status"].asString() } shouldContainExactly
            listOf("APPLIED", "APPLIED", "REJECTED", "APPLIED_WITH_WARNINGS")
        results[2]["code"].asString() shouldBe "NOTE_REJECTED"
        results[2]["message"].asString() shouldBe "Nota recusada pelo exemplo."
        results[3]["warnings"].values().map { it["code"].asString() } shouldContainExactly listOf("NOTE_WARNING")
        noteActive(note) shouldBe false
        noteCount(UUID.fromString(results[3]["commandId"].asString())) shouldBe 1
        noteCount(UUID.fromString(results[2]["commandId"].asString())) shouldBe 0
    }

    @Test
    fun `a repeated command id is applied once and returns the first result, in the same batch and in later ones`() {
        val writer = iam.createUserWith(notesRead(ALL), notesWrite())
        val applied = command(SyncExample.RECORD, mapOf("text" to "uma vez"))
        val rejected = command(SyncExample.RECORD, mapOf("text" to "reject"))

        val first = send(auth(writer), applied, applied, rejected)
        first.map { it["status"].asString() } shouldContainExactly listOf("APPLIED", "APPLIED", "REJECTED")
        first.map { it["replayed"].asBoolean() } shouldContainExactly listOf(false, true, false)

        val again = send(auth(writer), rejected, applied)
        again.map { it["status"].asString() } shouldContainExactly listOf("REJECTED", "APPLIED")
        again.map { it["replayed"].asBoolean() } shouldContainExactly listOf(true, true)
        again[0]["code"].asString() shouldBe "NOTE_REJECTED"
        again[1]["occurredAt"].asString() shouldBe first[0]["occurredAt"].asString()

        noteCount(applied.id()) shouldBe 1
        recordHandler.invocations(applied.id()) shouldBe 1
        auditCount("syncexample.note.record", writer) shouldBe 1
    }

    @Test
    fun `simultaneous requests with the same command id apply it once`() {
        val writer = iam.createUserWith(notesRead(ALL), notesWrite())
        val slow = command(SyncExample.RECORD, mapOf("text" to SyncExample.SLOW))
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS)
        val results =
            try {
                (1..CONCURRENT_REQUESTS)
                    .map {
                        executor.submit<JsonNode> {
                            start.await()
                            send(auth(writer), slow).single()
                        }
                    }.also { start.countDown() }
                    .map { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }

        // O efeito não idempotente (o contador do handler, que conta até execuções desfeitas) aconteceu uma vez:
        // a evidência não depende da chave primária da nota.
        recordHandler.invocations(slow.id()) shouldBe 1
        results.map { it["status"].asString() }.distinct() shouldBe listOf("APPLIED")
        results.count { !it["replayed"].asBoolean() } shouldBe 1
        noteCount(slow.id()) shouldBe 1
        auditCount("syncexample.note.record", writer) shouldBe 1
    }

    @Test
    fun `a command id used by another user, or with another type, is refused`() {
        val first = iam.createUserWith(notesRead(ALL), notesWrite())
        val second = iam.createUserWith(notesRead(ALL), notesWrite())
        val recorded = command(SyncExample.RECORD, mapOf("text" to "do primeiro"))
        send(auth(first), recorded).single()["status"].asString() shouldBe "APPLIED"

        val stolen = send(auth(second), recorded).single()
        stolen["status"].asString() shouldBe "REJECTED"
        stolen["code"].asString() shouldBe "COMMAND_ID_CONFLICT"

        val reused =
            send(
                auth(first),
                command(SyncExample.ARCHIVE, mapOf("noteId" to recorded.id()), id = recorded.id()),
            ).single()
        reused["code"].asString() shouldBe "COMMAND_ID_REUSED"
        noteCount(recorded.id()) shouldBe 1
        noteActive(recorded.id()) shouldBe true
    }

    @Test
    fun `protocol refusals carry a stable code and are not stored`() {
        val writer = iam.createUserWith(notesRead(ALL), notesWrite())
        val notV7 = UUID.randomUUID()
        val old = UuidV7Generator(Clock.fixed(clock.instant().minus(Duration.ofDays(31)), clock.zone)).generate()
        val results =
            send(
                auth(writer),
                command(SyncExample.RECORD, mapOf("text" to "x"), id = notV7),
                command(SyncExample.RECORD, mapOf("text" to "x"), id = old),
                commandOf("syncexample.unknown-command", mapOf("text" to "x")),
                command(SyncExample.RECORD, mapOf("text" to "")),
                command(SyncExample.RECORD, mapOf("other" to 1)),
                command(SyncExample.ARCHIVE, mapOf("noteId" to "nao-e-uuid")),
            )

        results.map { it["code"].asString() } shouldContainExactly
            listOf(
                "COMMAND_ID_INVALID",
                "COMMAND_EXPIRED",
                "UNKNOWN_COMMAND_TYPE",
                "COMMAND_PAYLOAD_INVALID",
                "COMMAND_PAYLOAD_INVALID",
                "COMMAND_PAYLOAD_INVALID",
            )
        results.map { it["status"].asString() }.distinct() shouldBe listOf("REJECTED")
        storedCommand(notV7) shouldBe 0
        storedCommand(old) shouldBe 0
    }

    @Test
    fun `a handler without the permission rejects the command`() {
        val reader = iam.createUserWith(notesRead(ALL))
        val result = send(auth(reader), command(SyncExample.RECORD, mapOf("text" to "sem permissão"))).single()

        result["status"].asString() shouldBe "REJECTED"
        result["code"].asString() shouldBe "PERMISSION_DENIED"
    }

    @Test
    fun `an unexpected failure stores nothing and leaves the rest of the batch for a retry`() {
        val writer = iam.createUserWith(notesRead(ALL), notesWrite())
        val boom = command(SyncExample.RECORD, mapOf("text" to "boom"))
        val next = command(SyncExample.RECORD, mapOf("text" to "depois"))

        val results = send(auth(writer), boom, next)
        results.map { it["status"].asString() } shouldContainExactly listOf("FAILED", "NOT_PROCESSED")
        results.map { it["code"].asString() } shouldContainExactly listOf("COMMAND_FAILED", "COMMAND_NOT_PROCESSED")
        noteCount(boom.id()) shouldBe 0
        noteCount(next.id()) shouldBe 0
        storedCommand(boom.id()) shouldBe 0

        // O reenvio processa de novo (nada ficou guardado).
        send(auth(writer), next).single()["status"].asString() shouldBe "APPLIED"
    }

    @Test
    fun `occurredAt is corrected by the device clock skew, with a warning above the tolerance`() {
        val writer = iam.createUserWith(notesRead(ALL), notesWrite())
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val withinTolerance =
            command(
                SyncExample.RECORD,
                mapOf("text" to "relógio 2 min atrasado"),
                occurredAt = now.minus(Duration.ofHours(1)).minus(Duration.ofMinutes(2)),
                deviceClock = now.minus(Duration.ofMinutes(2)),
            )
        val aboveTolerance =
            command(
                SyncExample.RECORD,
                mapOf("text" to "relógio 10 min adiantado"),
                occurredAt = now.minus(Duration.ofHours(1)).plus(Duration.ofMinutes(10)),
                deviceClock = now.plus(Duration.ofMinutes(10)),
            )
        val inTheFuture =
            command(
                SyncExample.RECORD,
                mapOf("text" to "depois do envio"),
                occurredAt = now.plus(Duration.ofMinutes(30)),
                deviceClock = now,
            )

        val results = send(auth(writer), withinTolerance, aboveTolerance, inTheFuture)

        results.map { it["status"].asString() } shouldContainExactly
            listOf("APPLIED", "APPLIED_WITH_WARNINGS", "APPLIED")
        results[1]["warnings"].values().map { it["code"].asString() } shouldContainExactly listOf("CLOCK_SKEW_EXCEEDED")
        val expected = listOf(now.minus(Duration.ofHours(1)), now.minus(Duration.ofHours(1)), now)
        results.map { Instant.parse(it["occurredAt"].asString()) } shouldContainExactly expected
        listOf(withinTolerance, aboveTolerance, inTheFuture).map { noteOccurredAt(it.id()) } shouldContainExactly
            expected
    }

    @Test
    fun `after the retention only the command id is kept, and it is removed after the id retention`() {
        val writer = iam.createUserWith(notesRead(ALL), notesWrite())
        val recorded = command(SyncExample.RECORD, mapOf("text" to "antiga"))
        send(auth(writer), recorded).single()["status"].asString() shouldBe "APPLIED"
        storedStatus(recorded.id()) shouldBe "APPLIED"

        val start = clock.instant()
        try {
            clock.advance(RETENTION_AND_A_DAY)
            purgeJob.purgeExpiredCommands()
            // O resultado saiu; o id fica, com o usuário e o tipo, sem resultado.
            storedStatus(recorded.id()) shouldBe "EXPIRED"
            storedResultColumns(recorded.id()) shouldBe listOf(null, null, "[]", null)

            val resent = send(auth(writer), recorded.copy(occurredAt = clock.instant(), deviceClock = clock.instant()))
            resent.single()["code"].asString() shouldBe "COMMAND_EXPIRED"
            resent.single()["replayed"].asBoolean() shouldBe false
            // De outro usuário, o id continua sendo de quem o usou.
            val other = iam.createUserWith(notesRead(ALL), notesWrite())
            send(auth(other), recorded).single()["code"].asString() shouldBe "COMMAND_ID_CONFLICT"

            clock.advance(ID_RETENTION)
            purgeJob.purgeExpiredCommands()
            storedCommand(recorded.id()) shouldBe 0
        } finally {
            clock.advance(Duration.between(clock.instant(), start))
        }
        noteCount(recorded.id()) shouldBe 1
        recordHandler.invocations(recorded.id()) shouldBe 1
    }

    @Test
    fun `a command resent after the purge with the device clock further behind is never applied again`() {
        val writer = iam.createUserWith(notesRead(ALL), notesWrite())
        // Primeiro envio com o relógio certo.
        val recorded = command(SyncExample.RECORD, mapOf("text" to "reenvio atrasado"))
        send(auth(writer), recorded).single()["status"].asString() shouldBe "APPLIED"

        val start = clock.instant()
        try {
            clock.advance(RETENTION_AND_A_DAY)
            purgeJob.purgeExpiredCommands()
            // Reenvio com o aparelho 2 dias atrasado: o instante corrigido do id volta para dentro da retenção.
            val deviceNow = clock.instant().minus(Duration.ofDays(2))
            val resent = send(auth(writer), recorded.copy(occurredAt = deviceNow, deviceClock = deviceNow)).single()

            resent["status"].asString() shouldBe "REJECTED"
            resent["code"].asString() shouldBe "COMMAND_EXPIRED"
        } finally {
            clock.advance(Duration.between(clock.instant(), start))
        }
        recordHandler.invocations(recorded.id()) shouldBe 1
        noteCount(recorded.id()) shouldBe 1
    }

    @Test
    fun `an id from the right clock sent with the device behind keeps its result for the corrected term`() {
        val writer = iam.createUserWith(notesRead(ALL), notesWrite())
        val start = clock.instant()
        // O id sai com o relógio certo; antes do envio, o relógio do aparelho atrasa 10 dias.
        val deviceNow = start.minus(Duration.ofDays(10))
        val recorded =
            command(
                SyncExample.RECORD,
                mapOf("text" to "relógio atrasou depois do id"),
                occurredAt = deviceNow,
                deviceClock = deviceNow,
            )
        send(auth(writer), recorded).single()["status"].asString() shouldBe "APPLIED_WITH_WARNINGS"

        try {
            clock.advance(RETENTION_AND_A_DAY)
            purgeJob.purgeExpiredCommands()
            val laterDeviceNow = clock.instant().minus(Duration.ofDays(10))
            val resent =
                send(auth(writer), recorded.copy(occurredAt = laterDeviceNow, deviceClock = laterDeviceNow)).single()

            withClue("o termo da retenção conta do instante corrigido do id (10 dias depois do cru)") {
                resent["replayed"].asBoolean() shouldBe true
                resent["status"].asString() shouldBe "APPLIED_WITH_WARNINGS"
            }
        } finally {
            clock.advance(Duration.between(clock.instant(), start))
        }
        recordHandler.invocations(recorded.id()) shouldBe 1
    }

    @Test
    fun `a known command id is answered from what is stored whatever the device clock`() {
        val writer = iam.createUserWith(notesRead(ALL), notesWrite())
        val recorded = command(SyncExample.RECORD, mapOf("text" to "conhecido"))
        send(auth(writer), recorded).single()["status"].asString() shouldBe "APPLIED"
        val absurd = Instant.parse("-300000-01-01T00:00:00Z")

        val replay = send(auth(writer), recorded.copy(deviceClock = absurd, occurredAt = absurd)).single()
        replay["status"].asString() shouldBe "APPLIED"
        replay["replayed"].asBoolean() shouldBe true

        val start = clock.instant()
        try {
            clock.advance(RETENTION_AND_A_DAY)
            purgeJob.purgeExpiredCommands()
            storedStatus(recorded.id()) shouldBe "EXPIRED"
            val expired = send(auth(writer), recorded.copy(deviceClock = absurd, occurredAt = absurd)).single()
            expired["status"].asString() shouldBe "REJECTED"
            expired["code"].asString() shouldBe "COMMAND_EXPIRED"
        } finally {
            clock.advance(Duration.between(clock.instant(), start))
        }
        recordHandler.invocations(recorded.id()) shouldBe 1
    }

    @Test
    fun `device times outside the plausible range are refused with a stable code`() {
        val writer = iam.createUserWith(notesRead(ALL), notesWrite())
        val now = clock.instant()
        val farPast = Instant.parse("-300000-01-01T00:00:00Z")
        val nearMin = Instant.MIN.plusSeconds(1)
        val farAhead = now.plus(Duration.ofDays(400))
        val beforeProduct = Instant.parse("2019-12-31T23:59:59Z")
        val results =
            send(
                auth(writer),
                command(SyncExample.RECORD, mapOf("text" to "a"), deviceClock = farPast),
                command(SyncExample.RECORD, mapOf("text" to "b"), deviceClock = nearMin),
                command(SyncExample.RECORD, mapOf("text" to "c"), deviceClock = farAhead),
                command(SyncExample.RECORD, mapOf("text" to "d"), occurredAt = farPast),
                command(SyncExample.RECORD, mapOf("text" to "e"), occurredAt = nearMin),
                command(SyncExample.RECORD, mapOf("text" to "f"), occurredAt = farAhead),
                command(SyncExample.RECORD, mapOf("text" to "g"), occurredAt = beforeProduct),
                command(SyncExample.RECORD, mapOf("text" to "h"), id = uuidAt(farAhead)),
                command(SyncExample.RECORD, mapOf("text" to "i"), id = uuidAt(beforeProduct)),
                command(SyncExample.RECORD, mapOf("text" to "ok")),
            )

        results.map { it["status"].asString() } shouldContainExactly List(9) { "REJECTED" } + "APPLIED"
        results.take(7).map { it["code"].asString() }.distinct() shouldBe listOf("DEVICE_TIME_INVALID")
        results
            .drop(7)
            .take(2)
            .map { it["code"].asString() }
            .distinct() shouldBe listOf("COMMAND_ID_INVALID")
        results.take(9).forEach { storedCommand(UUID.fromString(it["commandId"].asString())) shouldBe 0 }
    }

    @Test
    fun `a device whose clock is far behind still applies a new command, expiring by the corrected instant`() {
        val writer = iam.createUserWith(notesRead(ALL), notesWrite())
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val deviceNow = now.minus(Duration.ofDays(40))
        // O id e o relógio vêm do aparelho, 40 dias atrasado: o comando é de agora, não de 40 dias atrás.
        val late =
            command(
                SyncExample.RECORD,
                mapOf("text" to "aparelho atrasado"),
                id = uuidAt(deviceNow),
                occurredAt = deviceNow.minus(Duration.ofMinutes(1)),
                deviceClock = deviceNow,
            )

        val result = send(auth(writer), late).single()

        result["status"].asString() shouldBe "APPLIED_WITH_WARNINGS"
        result["warnings"].values().map { it["code"].asString() } shouldContainExactly listOf("CLOCK_SKEW_EXCEEDED")
        Instant.parse(result["occurredAt"].asString()) shouldBe now.minus(Duration.ofMinutes(1))
        noteCount(late.id()) shouldBe 1

        // Um comando de verdade antigo continua recusado, pelo instante corrigido.
        val old =
            command(
                SyncExample.RECORD,
                mapOf("text" to "antigo de verdade"),
                id = uuidAt(deviceNow.minus(Duration.ofDays(31))),
                occurredAt = deviceNow.minus(Duration.ofDays(31)),
                deviceClock = deviceNow,
            )
        send(auth(writer), old).single()["code"].asString() shouldBe "COMMAND_EXPIRED"
    }

    @Test
    fun `a device whose clock is ahead does not get its command applied again after the purge`() {
        val writer = iam.createUserWith(notesRead(ALL), notesWrite())
        val start = clock.instant()
        val deviceNow = start.plus(Duration.ofDays(40))
        // Aparelho 40 dias adiantado, coerente: id e relógio do envio no mesmo futuro.
        val ahead =
            command(
                SyncExample.RECORD,
                mapOf("text" to "aparelho adiantado"),
                id = uuidAt(deviceNow),
                occurredAt = deviceNow,
                deviceClock = deviceNow,
            )
        // Id gerado com o relógio adiantado, enviado depois de o relógio ser acertado: mesmo corrigido, o
        // instante do id fica no futuro.
        val futureId =
            command(
                SyncExample.RECORD,
                mapOf("text" to "id do futuro"),
                id = uuidAt(deviceNow),
                occurredAt = start,
                deviceClock = start,
            )
        send(auth(writer), ahead, futureId).map { it["status"].asString() } shouldContainExactly
            listOf("APPLIED_WITH_WARNINGS", "APPLIED")

        try {
            clock.advance(RETENTION_AND_A_DAY)
            purgeJob.purgeExpiredCommands()
            val shift = Duration.between(start, clock.instant())
            val resent =
                send(
                    auth(writer),
                    ahead.copy(deviceClock = deviceNow.plus(shift)),
                    futureId.copy(deviceClock = clock.instant()),
                )
            withClue("depois do expurgo da retenção, nenhum dos dois é aplicado de novo") {
                resent.map { it["replayed"].asBoolean() } shouldContainExactly listOf(true, true)
            }

            // Passada a retenção também do instante do id, os resultados saem (fica só o id) e os ids ficam vencidos.
            clock.advance(Duration.ofDays(40))
            purgeJob.purgeExpiredCommands()
            storedStatus(ahead.id()) shouldBe "EXPIRED"
            storedStatus(futureId.id()) shouldBe "EXPIRED"
            val later = Duration.between(start, clock.instant())
            send(
                auth(writer),
                ahead.copy(deviceClock = deviceNow.plus(later)),
                futureId.copy(deviceClock = clock.instant()),
            ).map { it["code"].asString() } shouldContainExactly listOf("COMMAND_EXPIRED", "COMMAND_EXPIRED")
        } finally {
            clock.advance(Duration.between(clock.instant(), start))
        }
        noteCount(ahead.id()) shouldBe 1
        noteCount(futureId.id()) shouldBe 1
        recordHandler.invocations(ahead.id()) shouldBe 1
        recordHandler.invocations(futureId.id()) shouldBe 1
    }

    @Test
    fun `the batch envelope is validated`() {
        val writer = auth(iam.createUserWith(notesWrite()))
        with(iam) {
            call(POST, "/api/v1/sync/commands", writer, mapOf("commands" to emptyList<Any>()))
                .problem(400, "VALIDATION_FAILED")
            val tooMany = (1..101).map { command(SyncExample.RECORD, mapOf("text" to "x")).body() }
            call(POST, "/api/v1/sync/commands", writer, mapOf("commands" to tooMany)).problem(400, "VALIDATION_FAILED")
            val missing = mapOf("commandId" to UuidV7.generate(), "type" to SyncExample.RECORD.value)
            call(POST, "/api/v1/sync/commands", writer, mapOf("commands" to listOf(missing)))
                .problem(400, "VALIDATION_FAILED")
            // Um horário que nem cabe no Instant não chega ao protocolo: o lote é recusado, sem 500.
            val beyondInstant =
                command(SyncExample.RECORD, mapOf("text" to "x")).body() +
                    ("deviceClock" to "+1000000001-01-01T00:00:00Z")
            call(POST, "/api/v1/sync/commands", writer, mapOf("commands" to listOf(beyondInstant)))
                .problem(400, "VALIDATION_FAILED")
        }
    }

    // --- Apoio ------------------------------------------------------------------------------------------

    private data class Downloaded(
        val changes: List<JsonNode>,
        val cursor: String,
        val reset: Boolean,
    )

    private data class TestCommand(
        val id: UUID,
        val type: String,
        val payload: Map<String, Any?>,
        val occurredAt: Instant,
        val deviceClock: Instant,
    ) {
        fun id() = id

        fun body() =
            mapOf(
                "commandId" to id,
                "type" to type,
                "payload" to payload,
                "occurredAt" to occurredAt.toString(),
                "deviceClock" to deviceClock.toString(),
            )
    }

    private fun auth(user: TestUser): RequestPostProcessor = iam.asTestUser(user)

    /** Um UUIDv7 gerado num aparelho cujo relógio marca [instant]. */
    private fun uuidAt(instant: Instant): UUID = UuidV7Generator(Clock.fixed(instant, clock.zone)).generate()

    private fun download(
        auth: RequestPostProcessor,
        cursor: String? = null,
        limit: Int = 200,
    ): JsonNode =
        with(iam) {
            call(GET, "/api/v1/sync/changes?limit=$limit" + cursor?.let { "&cursor=$it" }.orEmpty(), auth).ok()
        }

    /** Segue as páginas até `hasMore` falso. */
    private fun downloadAll(
        auth: RequestPostProcessor,
        cursor: String? = null,
    ): Downloaded {
        val changes = mutableListOf<JsonNode>()
        var next = cursor
        var reset: Boolean? = null
        var pages = 0
        do {
            val page = download(auth, next)
            reset = reset ?: page["reset"].asBoolean()
            changes += page["changes"].values()
            next = page["cursor"].asString()
            pages++
            withClue("download sem fim") { (pages < MAX_PAGES) shouldBe true }
        } while (page["hasMore"].asBoolean())
        return Downloaded(changes, checkNotNull(next), reset == true)
    }

    private fun command(
        type: com.zeny.hotel.shared.OfflineCommandType,
        payload: Map<String, Any?>,
        id: UUID = UuidV7.generate(),
        occurredAt: Instant = clock.instant(),
        deviceClock: Instant = clock.instant(),
    ) = TestCommand(id, type.value, payload, occurredAt, deviceClock)

    private fun commandOf(
        type: String,
        payload: Map<String, Any?>,
    ) = TestCommand(UuidV7.generate(), type, payload, clock.instant(), clock.instant())

    private fun send(
        auth: RequestPostProcessor,
        vararg commands: TestCommand,
    ): List<JsonNode> = send(auth, commands.toList())

    private fun send(
        auth: RequestPostProcessor,
        commands: List<TestCommand>,
    ): List<JsonNode> =
        with(iam) {
            call(POST, "/api/v1/sync/commands", auth, mapOf("commands" to commands.map { it.body() }))
                .ok()["results"]
                .values()
                .toList()
                .also { results ->
                    results.map { UUID.fromString(it["commandId"].asString()) } shouldBe
                        commands.map { it.id }
                }
        }

    private fun insertNote(
        owner: UUID,
        text: String,
    ): UUID {
        val id = UuidV7.generate()
        jdbc
            .sql(
                """
                INSERT INTO test_support.sync_note (id, owner_id, text, active, version)
                VALUES (:id, :owner, :text, TRUE, 0)
                """.trimIndent(),
            ).param("id", id)
            .param("owner", owner)
            .param("text", text)
            .update()
        return id
    }

    private fun updateNote(
        id: UUID,
        text: String,
    ) {
        jdbc
            .sql("UPDATE test_support.sync_note SET text = :text, version = version + 1 WHERE id = :id")
            .param("text", text)
            .param("id", id)
            .update() shouldBe 1
    }

    private fun noteCount(commandId: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM test_support.sync_note WHERE command_id = :id")
            .param("id", commandId)
            .query(Int::class.java)
            .single()

    private fun noteActive(id: UUID): Boolean =
        jdbc
            .sql(
                "SELECT active FROM test_support.sync_note WHERE id = :id",
            ).param("id", id)
            .query(Boolean::class.java)
            .single()

    private fun noteOccurredAt(id: UUID): Instant =
        jdbc
            .sql("SELECT occurred_at FROM test_support.sync_note WHERE id = :id")
            .param("id", id)
            .query { rs, _ -> rs.getObject(1, java.time.OffsetDateTime::class.java).toInstant() }
            .single()

    private fun storedCommand(id: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM shared.offline_command WHERE command_id = :id")
            .param("id", id)
            .query(Int::class.java)
            .single()

    private fun storedStatus(id: UUID): String? =
        jdbc
            .sql("SELECT status FROM shared.offline_command WHERE command_id = :id")
            .param("id", id)
            .query(String::class.java)
            .optional()
            .orElse(null)

    /** Código, mensagem, alertas e `occurredAt` guardados (o resultado). */
    private fun storedResultColumns(id: UUID): List<String?> =
        jdbc
            .sql(
                """
                SELECT error_code, message, warnings::text, occurred_at::text
                FROM shared.offline_command
                WHERE command_id = :id
                """.trimIndent(),
            ).param("id", id)
            .query { rs, _ -> (1..4).map { rs.getString(it) } }
            .single()

    private fun auditCount(
        action: String,
        user: TestUser,
    ): Int =
        jdbc
            .sql("SELECT count(*) FROM audit.audit_entry WHERE action = :action AND user_id = :user")
            .param("action", action)
            .param("user", user.id.value)
            .query(Int::class.java)
            .single()

    private fun List<JsonNode>.ids(): List<UUID> = map { it.id() }

    private fun JsonNode.id(): UUID = UUID.fromString(this["id"].asString())

    private fun roomsOfflineRead() = TestGrant(RoomsTestSupport.definition(RoomsPermissions.OFFLINE_READ), ALL)

    private fun notesRead(scope: PermissionScope) = TestGrant(definition(SyncExamplePermissions.NOTES_READ), scope)

    private fun notesWrite() = TestGrant(definition(SyncExamplePermissions.NOTES_WRITE), ALL)

    private fun definition(code: com.zeny.hotel.shared.PermissionCode) =
        SyncExamplePermissions.permissions.single { it.code == code }

    private companion object {
        const val WAIT_SECONDS = 20L
        const val WRITERS = 4
        const val INSERTS_PER_WRITER = 40
        const val PAUSE_FACTOR = 7
        const val MAX_PAUSE_MILLIS = 20
        const val CONCURRENT_REQUESTS = 4
        const val MAX_PAGES = 1000
        val RETENTION_AND_A_DAY: Duration = Duration.ofDays(31)
        val ID_RETENTION: Duration = Duration.ofDays(365)
    }
}
