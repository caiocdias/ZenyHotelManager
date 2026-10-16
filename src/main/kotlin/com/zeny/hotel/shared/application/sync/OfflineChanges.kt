package com.zeny.hotel.shared.application.sync

import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.OfflineChange
import com.zeny.hotel.shared.OfflineChangeRequest
import com.zeny.hotel.shared.OfflineChangeSource
import com.zeny.hotel.shared.OfflinePosition
import com.zeny.hotel.shared.OfflineSource
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.shared.ScopeFilter
import com.zeny.hotel.shared.UserId
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Leituras do protocolo offline que o `shared` faz por conta própria (implementadas em `infrastructure`):
 * o horizonte das transações terminadas e os *tombstones* gravados pela aplicação.
 */
interface OfflineSyncStore {
    /**
     * `pg_snapshot_xmin(pg_current_snapshot())`: toda transação com `xid` menor já terminou, então as linhas
     * gravadas por ela estão visíveis (ou foram desfeitas) e nenhuma linha nova terá `xid` menor.
     */
    fun horizon(): Long

    /** Os *tombstones* da [source] na janela do [request], no escopo dele, em ordem de posição. */
    fun tombstones(
        source: OfflineSource,
        request: OfflineChangeRequest,
    ): List<OfflineChange>
}

/** Uma mudança do download, com a fonte de onde veio. */
data class SourcedChange(
    val source: OfflineSource,
    val change: OfflineChange,
)

/**
 * Uma página do download.
 *
 * @property reset `true` quando a página começa um download completo (sem cursor, ou com um cursor de outro
 *   usuário ou de outras permissões): o cliente descarta o que tem das fontes antes de aplicar.
 * @property hasMore há mais mudanças já disponíveis: peça a próxima página com [cursor].
 */
data class OfflineChangesPage(
    val changes: List<SourcedChange>,
    val cursor: String,
    val hasMore: Boolean,
    val reset: Boolean,
)

/**
 * O download incremental (`GET /api/v1/sync/changes`, ARCHITECTURE §9, H6). As fontes visíveis são as de
 * cuja permissão o usuário tem alguma concessão, com o escopo dela. O cursor é opaco e leva a posição
 * `(xid, seq)` da última mudança entregue, uma impressão do usuário e das fontes visíveis (se ela mudar, o
 * download recomeça com `reset`) e se o download completo ainda está em curso.
 *
 * **Nenhuma mudança fica para trás do cursor:** só são servidas linhas de transações já terminadas
 * (`xid` < horizonte), em ordem `(xid, seq)`. Toda linha ainda invisível, ou gravada depois, tem `xid` maior
 * ou igual ao horizonte e, portanto, maior que a posição do cursor. Na última página, o cursor avança até
 * logo antes do horizonte. Uma transação de escrita que fique aberta segura as mudanças posteriores (sem
 * perdê-las) até terminar; `idle_in_transaction_session_timeout` limita isso.
 */
@Service
class OfflineChangesService(
    sources: ObjectProvider<OfflineChangeSource>,
    modulePermissions: List<ModulePermissions>,
    private val store: OfflineSyncStore,
    private val authorization: Authorization,
    private val currentActor: CurrentActor,
) {
    private val sources: List<OfflineChangeSource> = catalog(sources.orderedStream().toList(), modulePermissions)

    /** As fontes registradas, por nome. */
    fun source(name: String): OfflineSource? = sources.firstOrNull { it.source.name == name }?.source

    @Transactional(readOnly = true)
    fun changes(
        cursorToken: String?,
        limit: Int,
    ): OfflineChangesPage {
        val actor = currentActor.current()
        val visible =
            sources.mapNotNull { source ->
                authorization.grantOrNull(source.source.permission)?.let { grant ->
                    source to
                        scopeOf(grant.scope, actor.userId)
                }
            }
        val fingerprint = fingerprint(actor.userId, visible.map { (source, scope) -> source.source to scope })
        val cursor = cursorToken?.let(SyncCursor::decode)?.takeIf { it.fingerprint == fingerprint }
        val reset = cursor == null
        val after = cursor?.position ?: OfflinePosition.START
        // Primeira leitura da transação: o horizonte vale para todas as fontes desta página.
        val horizon = store.horizon()
        // No download completo, o horizonte da primeira página: um registro apagado ou desativado antes dele
        // nunca chegou ao cliente (o que sumiu depois tem xid maior ou igual e vai como tombstone).
        val fullBefore = if (cursor == null) horizon else cursor.fullBefore

        val merged =
            visible
                .flatMap { (source, scope) ->
                    val request = OfflineChangeRequest(after, horizon, scope, limit + 1)
                    (source.changes(request) + store.tombstones(source.source, request))
                        .map { SourcedChange(source.source, it) }
                }.sortedBy { it.change.position }
        merged.forEach {
            check(it.change.position > after && it.change.position.xid < horizon) {
                "A fonte ${it.source} devolveu uma mudança fora da janela"
            }
        }
        val considered = merged.take(limit)
        val hasMore = merged.size > limit
        val next =
            if (hasMore) {
                considered.last().change.position
            } else {
                // Tudo de transações terminadas antes do horizonte foi entregue.
                maxOf(after, OfflinePosition(horizon - 1, Long.MAX_VALUE))
            }
        val delivered = considered.filterNot { it.change.deleted && it.change.position.xid < fullBefore }
        val nextFullBefore = if (hasMore) fullBefore else 0
        return OfflineChangesPage(delivered, SyncCursor(next, fingerprint, nextFullBefore).encode(), hasMore, reset)
    }

    private fun scopeOf(
        scope: PermissionScope,
        user: UserId,
    ): ScopeFilter = if (scope == PermissionScope.ALL) ScopeFilter.All else ScopeFilter.Own(user)

    private fun fingerprint(
        user: UserId,
        visible: List<Pair<OfflineSource, ScopeFilter>>,
    ): String {
        val text =
            visible
                .map { (source, scope) -> "${source.name}=${if (scope is ScopeFilter.All) "ALL" else "OWN"}" }
                .sorted()
                .joinToString(",", prefix = "${user.value}|")
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return HexFormat.of().formatHex(digest, 0, FINGERPRINT_BYTES)
    }

    companion object {
        private const val FINGERPRINT_BYTES = 12

        /** Confere as fontes registradas: nomes únicos e permissões declaradas por algum módulo. */
        fun catalog(
            sources: List<OfflineChangeSource>,
            modulePermissions: List<ModulePermissions>,
        ): List<OfflineChangeSource> {
            val declared = modulePermissions.flatMap { module -> module.permissions.map { it.code } }.toSet()
            val duplicated = sources.groupBy { it.source.name }.filterValues { it.size > 1 }.keys
            require(duplicated.isEmpty()) { "Fontes offline repetidas: $duplicated" }
            sources.forEach {
                require(it.source.permission in declared) {
                    "Fonte offline ${it.source}: permissão ${it.source.permission} não declarada"
                }
            }
            return sources.sortedBy { it.source.name }
        }
    }
}

/**
 * O cursor do download: posição, impressão do usuário e das fontes e, durante o download completo, o
 * horizonte da primeira página ([fullBefore]; zero fora dele).
 */
internal data class SyncCursor(
    val position: OfflinePosition,
    val fingerprint: String,
    val fullBefore: Long,
) {
    fun encode(): String =
        PageCursor
            .of(VERSION, position.xid.toString(), position.seq.toString(), fingerprint, fullBefore.toString())
            .encode()

    companion object {
        private const val VERSION = "sync1"
        private const val KEYS = 5
        private val FINGERPRINT = Regex("[0-9a-f]{24}")

        fun decode(token: String): SyncCursor {
            val cursor = PageCursor.decode(token)
            if (cursor.keys.size != KEYS || cursor.string(0) != VERSION) throw invalid()
            val xid = cursor.long(1)
            val seq = cursor.long(2)
            val fingerprint = cursor.string(3)
            val fullBefore = cursor.long(4)
            val valid = minOf(xid, seq, fullBefore) >= 0 && FINGERPRINT.matches(fingerprint)
            if (!valid) throw invalid()
            return SyncCursor(OfflinePosition(xid, seq), fingerprint, fullBefore)
        }

        private fun invalid() =
            InvalidInputException(
                ErrorCodes.INVALID_CURSOR,
                "Cursor de sincronização inválido. Recomece a sincronização sem cursor.",
            )
    }
}
