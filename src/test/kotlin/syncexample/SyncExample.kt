package syncexample

import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.OfflineChange
import com.zeny.hotel.shared.OfflineChangeRequest
import com.zeny.hotel.shared.OfflineChangeSource
import com.zeny.hotel.shared.OfflineCommand
import com.zeny.hotel.shared.OfflineCommandHandler
import com.zeny.hotel.shared.OfflineCommandOutcome
import com.zeny.hotel.shared.OfflineCommandType
import com.zeny.hotel.shared.OfflineCommandWarning
import com.zeny.hotel.shared.OfflinePosition
import com.zeny.hotel.shared.OfflineSource
import com.zeny.hotel.shared.OfflineTombstones
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.shared.UserId
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Um módulo de exemplo, só de teste, sobre o protocolo offline (M2 E05, ARCHITECTURE §9): a fonte
 * `example-notes` (notas com dono, escopo `OWN`, tabela `test_support.sync_note` com o trigger do `shared`) e
 * comandos que criam, desativam, apagam e reatribuem notas.
 */
object SyncExamplePermissions : ModulePermissions {
    override val module = "syncexample"

    val NOTES_READ = PermissionCode("syncexample.notes.read")
    val NOTES_WRITE = PermissionCode("syncexample.notes.write")

    override val permissions =
        listOf(
            PermissionDefinition(NOTES_READ, "Baixar notas de exemplo offline", setOf(OWN, ALL)),
            PermissionDefinition(NOTES_WRITE, "Escrever notas de exemplo offline", setOf(ALL)),
        )
}

object SyncExample {
    val SOURCE = OfflineSource("example-notes", SyncExamplePermissions.NOTES_READ)

    val RECORD = OfflineCommandType("syncexample.record-note")
    val ARCHIVE = OfflineCommandType("syncexample.archive-note")
    val DELETE = OfflineCommandType("syncexample.delete-note")
    val REASSIGN = OfflineCommandType("syncexample.reassign-note")

    val NOTE_REJECTED = ErrorCode("NOTE_REJECTED")
    val NOTE_WARNING = ErrorCode("NOTE_WARNING")
    val NOTE_NOT_FOUND = ErrorCode("NOTE_NOT_FOUND")

    /** Texto que faz o handler esperar dentro da transação (corridas). */
    const val SLOW = "slow"
    const val SLOW_MILLIS = 400L
}

/** O que o app recebe de cada nota. */
data class OfflineNote(
    val text: String,
    val ownerId: UUID,
)

/** A fonte de notas: com `OWN`, só as do usuário; desativadas viram tombstone. */
class SyncNoteSource(
    private val jdbc: JdbcClient,
) : OfflineChangeSource {
    override val source = SyncExample.SOURCE

    override fun changes(request: OfflineChangeRequest): List<OfflineChange> =
        jdbc
            .sql(
                """
                SELECT n.id, n.owner_id, n.text, n.active, n.version, ${OfflineChangeRequest.positionColumns("n")}
                FROM test_support.sync_note n
                WHERE ${OfflineChangeRequest.window("n")}
                  AND (CAST(:owner AS uuid) IS NULL OR n.owner_id = CAST(:owner AS uuid))
                ORDER BY n.change_xid, n.change_seq
                LIMIT :limit
                """.trimIndent(),
            ).params(request.sqlParams())
            .query { rs, _ ->
                val position = OfflinePosition.from(rs)
                val id = rs.getObject("id", UUID::class.java)
                val version = rs.getLong("version")
                if (rs.getBoolean("active")) {
                    OfflineChange.upsert(
                        position,
                        id,
                        version,
                        OfflineNote(rs.getString("text"), rs.getObject("owner_id", UUID::class.java)),
                    )
                } else {
                    OfflineChange.tombstone(position, id, version)
                }
            }.list()
}

data class RecordNotePayload(
    @field:NotBlank
    @field:Size(max = 200)
    val text: String,
)

/**
 * Cria a nota com o `commandId` como id (registro criado offline com o UUID do cliente). Rejeita o texto
 * `reject`, alerta no texto que começa com `warn`, falha no `boom` e espera dentro da transação no `slow`.
 * Conta, em memória, quantas vezes o handler rodou para cada `commandId` (um efeito que não é idempotente e
 * não depende da chave primária da nota: conta também as execuções desfeitas).
 */
open class RecordNoteHandler(
    private val jdbc: JdbcClient,
    private val authorization: Authorization,
    private val actor: CurrentActor,
) : OfflineCommandHandler<RecordNotePayload> {
    override val type = SyncExample.RECORD
    override val payloadType = RecordNotePayload::class.java

    private val invocations = ConcurrentHashMap<UUID, AtomicInteger>()

    /** Quantas vezes o handler rodou para o [commandId], inclusive as execuções desfeitas. */
    open fun invocations(commandId: UUID): Int = invocations[commandId]?.get() ?: 0

    @Audited("syncexample.note.record")
    @Transactional
    override fun handle(command: OfflineCommand<RecordNotePayload>): OfflineCommandOutcome {
        invocations.computeIfAbsent(command.commandId) { AtomicInteger() }.incrementAndGet()
        authorization.require(SyncExamplePermissions.NOTES_WRITE)
        val text = command.payload.text
        if (text == "reject") throw BusinessRuleException(SyncExample.NOTE_REJECTED, "Nota recusada pelo exemplo.")
        jdbc
            .sql(
                """
                INSERT INTO test_support.sync_note (id, command_id, owner_id, text, active, occurred_at, version)
                VALUES (:id, :id, :owner, :text, TRUE, :occurredAt, 0)
                """.trimIndent(),
            ).param("id", command.commandId)
            .param("owner", actor.current().userId.value)
            .param("text", text)
            .param("occurredAt", command.occurredAt.atOffset(ZoneOffset.UTC))
            .update()
        check(text != "boom") { "Falha inesperada do exemplo" }
        if (text == SyncExample.SLOW) Thread.sleep(SyncExample.SLOW_MILLIS)
        return if (text.startsWith("warn")) {
            OfflineCommandOutcome(listOf(OfflineCommandWarning(SyncExample.NOTE_WARNING, "Nota com alerta.")))
        } else {
            OfflineCommandOutcome.APPLIED
        }
    }
}

data class NoteRefPayload(
    val noteId: UUID,
)

/** Desativa a nota: a fonte a entrega como tombstone. */
open class ArchiveNoteHandler(
    private val jdbc: JdbcClient,
    private val authorization: Authorization,
) : OfflineCommandHandler<NoteRefPayload> {
    override val type = SyncExample.ARCHIVE
    override val payloadType = NoteRefPayload::class.java

    @Audited("syncexample.note.archive")
    @Transactional
    override fun handle(command: OfflineCommand<NoteRefPayload>): OfflineCommandOutcome {
        authorization.require(SyncExamplePermissions.NOTES_WRITE)
        val updated =
            jdbc
                .sql("UPDATE test_support.sync_note SET active = FALSE, version = version + 1 WHERE id = :id")
                .param("id", command.payload.noteId)
                .update()
        if (updated == 0) throw NotFoundException(SyncExample.NOTE_NOT_FOUND, "Nota não encontrada.")
        return OfflineCommandOutcome.APPLIED
    }
}

/** Apaga a nota de verdade: o tombstone vai pela porta do `shared`. */
open class DeleteNoteHandler(
    private val jdbc: JdbcClient,
    private val authorization: Authorization,
    private val tombstones: OfflineTombstones,
) : OfflineCommandHandler<NoteRefPayload> {
    override val type = SyncExample.DELETE
    override val payloadType = NoteRefPayload::class.java

    @Audited("syncexample.note.delete")
    @Transactional
    override fun handle(command: OfflineCommand<NoteRefPayload>): OfflineCommandOutcome {
        authorization.require(SyncExamplePermissions.NOTES_WRITE)
        val deleted =
            jdbc
                .sql("DELETE FROM test_support.sync_note WHERE id = :id")
                .param("id", command.payload.noteId)
                .update()
        if (deleted == 0) throw NotFoundException(SyncExample.NOTE_NOT_FOUND, "Nota não encontrada.")
        tombstones.record(SyncExample.SOURCE, command.payload.noteId)
        return OfflineCommandOutcome.APPLIED
    }
}

data class ReassignNotePayload(
    val noteId: UUID,
    val ownerId: UUID,
)

/** Passa a nota a outro dono: ela sai do escopo `OWN` do anterior, que recebe o tombstone. */
open class ReassignNoteHandler(
    private val jdbc: JdbcClient,
    private val authorization: Authorization,
    private val tombstones: OfflineTombstones,
) : OfflineCommandHandler<ReassignNotePayload> {
    override val type = SyncExample.REASSIGN
    override val payloadType = ReassignNotePayload::class.java

    @Audited("syncexample.note.reassign")
    @Transactional
    override fun handle(command: OfflineCommand<ReassignNotePayload>): OfflineCommandOutcome {
        authorization.require(SyncExamplePermissions.NOTES_WRITE)
        val previous =
            jdbc
                .sql("SELECT owner_id FROM test_support.sync_note WHERE id = :id FOR UPDATE")
                .param("id", command.payload.noteId)
                .query(UUID::class.java)
                .optional()
                .orElseThrow { NotFoundException(SyncExample.NOTE_NOT_FOUND, "Nota não encontrada.") }
        jdbc
            .sql("UPDATE test_support.sync_note SET owner_id = :owner, version = version + 1 WHERE id = :id")
            .param("owner", command.payload.ownerId)
            .param("id", command.payload.noteId)
            .update()
        tombstones.record(SyncExample.SOURCE, command.payload.noteId, setOf(UserId(previous)))
        return OfflineCommandOutcome.APPLIED
    }
}

@TestConfiguration(proxyBeanMethods = false)
class SyncExampleConfiguration {
    @Bean
    fun syncExamplePermissions(): ModulePermissions = SyncExamplePermissions

    @Bean
    fun syncNoteSource(jdbc: JdbcClient): OfflineChangeSource = SyncNoteSource(jdbc)

    @Bean
    fun recordNoteHandler(
        jdbc: JdbcClient,
        authorization: Authorization,
        actor: CurrentActor,
    ) = RecordNoteHandler(jdbc, authorization, actor)

    @Bean
    fun archiveNoteHandler(
        jdbc: JdbcClient,
        authorization: Authorization,
    ) = ArchiveNoteHandler(jdbc, authorization)

    @Bean
    fun deleteNoteHandler(
        jdbc: JdbcClient,
        authorization: Authorization,
        tombstones: OfflineTombstones,
    ) = DeleteNoteHandler(jdbc, authorization, tombstones)

    @Bean
    fun reassignNoteHandler(
        jdbc: JdbcClient,
        authorization: Authorization,
        tombstones: OfflineTombstones,
    ) = ReassignNoteHandler(jdbc, authorization, tombstones)
}
