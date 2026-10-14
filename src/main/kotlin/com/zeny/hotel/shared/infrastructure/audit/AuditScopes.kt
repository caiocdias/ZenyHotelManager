package com.zeny.hotel.shared.infrastructure.audit

import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.AuditOutcome
import com.zeny.hotel.shared.AuditRecord
import com.zeny.hotel.shared.DeviceSessionId
import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.UserId
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * As mudanças descritas por um método `@Audited` em execução. Cada invocação tem o seu escopo;
 * um método auditado chamado de dentro de outro (por outro bean) grava as próprias entradas.
 */
internal class AuditScope(
    val action: String,
) {
    private val changes = mutableListOf<Change>()
    private var declared = false
    private var actor: Pair<UserId, DeviceSessionId?>? = null
    private var approvalId: UUID? = null

    fun add(change: Change) {
        declared = true
        changes += change
    }

    fun attributeTo(
        userId: UserId,
        deviceSessionId: DeviceSessionId?,
    ) {
        actor = userId to deviceSessionId
    }

    fun approval(id: UUID) {
        approvalId = id
    }

    fun noChanges() {
        declared = true
    }

    /**
     * As entradas de uma execução que terminou bem: as mudanças registradas e as entidades
     * acompanhadas que mudaram; sem nenhuma descrição, uma entrada só com a ação.
     */
    fun succeeded(
        fallbackActor: Pair<UserId, DeviceSessionId>?,
        now: Instant,
    ): List<AuditRecord> {
        val entries =
            changes
                .map { it.resolve() }
                .filterNot { it.tracked && it.before == it.after }
                .map { record(it, AuditOutcome.SUCCEEDED, null, fallbackActor, now) }
        return if (declared) entries else listOf(record(null, AuditOutcome.SUCCEEDED, null, fallbackActor, now))
    }

    /** As entradas de uma recusa: todas as entidades já descritas, mesmo sem mudança. */
    fun rejected(
        errorCode: String,
        fallbackActor: Pair<UserId, DeviceSessionId>?,
        now: Instant,
    ): List<AuditRecord> = changes.map { record(it.resolve(), AuditOutcome.REJECTED, errorCode, fallbackActor, now) }

    private fun record(
        change: ResolvedChange?,
        outcome: AuditOutcome,
        errorCode: String?,
        fallbackActor: Pair<UserId, DeviceSessionId>?,
        now: Instant,
    ): AuditRecord {
        val (userId, sessionId) = actor ?: fallbackActor ?: (null to null)
        return AuditRecord(
            occurredAt = now,
            action = action,
            outcome = outcome,
            errorCode = errorCode,
            userId = userId,
            deviceSessionId = sessionId,
            entityType = change?.entityType,
            entityId = change?.entityId,
            before = change?.before,
            after = change?.after,
            approvalId = approvalId,
        )
    }
}

/** Uma entidade descrita no escopo; o "depois" de uma acompanhada é lido no fim do método. */
internal class Change(
    val entityType: String,
    val entityId: String,
    val before: Any?,
    val tracked: Boolean,
    private val after: () -> Any?,
) {
    fun resolve() = ResolvedChange(entityType, entityId, before, after(), tracked)
}

internal data class ResolvedChange(
    val entityType: String,
    val entityId: String,
    val before: Any?,
    val after: Any?,
    val tracked: Boolean,
)

/**
 * Implementação da [AuditContext]: a pilha de escopos da thread corrente, aberta e fechada pelo
 * [AuditAspect]. Os casos de uso a injetam como `AuditContext`.
 */
@Component
class AuditScopes : AuditContext {
    private val stack = ThreadLocal.withInitial { ArrayDeque<AuditScope>() }

    internal fun open(action: String): AuditScope {
        require(CODE.matches(action) && action.length <= MAX_CODE_LENGTH) {
            "Ação de auditoria fora do padrão <módulo>.<entidade>.<verbo>: '$action'"
        }
        return AuditScope(action).also { stack.get().addLast(it) }
    }

    internal fun close(scope: AuditScope) {
        val scopes = stack.get()
        check(scopes.lastOrNull() === scope) { "Escopo de auditoria fechado fora de ordem: ${scope.action}" }
        scopes.removeLast()
        if (scopes.isEmpty()) stack.remove()
    }

    override fun track(
        entityType: String,
        entityId: Any,
        snapshot: () -> Any?,
    ) = current().add(Change(checkedType(entityType), idOf(entityId), snapshot(), tracked = true, after = snapshot))

    override fun created(
        entityType: String,
        entityId: Any,
        snapshot: () -> Any?,
    ) = current().add(Change(checkedType(entityType), idOf(entityId), null, tracked = false, after = snapshot))

    override fun record(
        entityType: String,
        entityId: Any,
        before: Any?,
        after: Any?,
    ) = current().add(Change(checkedType(entityType), idOf(entityId), before, tracked = false, after = { after }))

    override fun attributeTo(
        userId: UserId,
        deviceSessionId: DeviceSessionId?,
    ) = current().attributeTo(userId, deviceSessionId)

    override fun approval(approvalId: UUID) = current().approval(approvalId)

    override fun noChanges() = current().noChanges()

    private fun current(): AuditScope =
        checkNotNull(stack.get().lastOrNull()) { "AuditContext usado fora de um método @Audited" }

    private fun checkedType(entityType: String): String {
        require(CODE.matches(entityType) && entityType.length <= MAX_CODE_LENGTH) {
            "Tipo de entidade fora do padrão <módulo>.<entidade>: '$entityType'"
        }
        return entityType
    }

    private fun idOf(entityId: Any): String {
        val id =
            when (entityId) {
                is TypedId -> entityId.value.toString()
                else -> entityId.toString()
            }
        require(id.isNotBlank() && id.length <= MAX_ID_LENGTH) { "Id de entidade inválido para a auditoria" }
        return id
    }

    companion object {
        /** `<módulo>.<parte>[.<parte>…]`, minúsculas, dígitos e hífen. */
        val CODE = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+")
        const val MAX_CODE_LENGTH = 100
        const val MAX_ID_LENGTH = 200
    }
}
