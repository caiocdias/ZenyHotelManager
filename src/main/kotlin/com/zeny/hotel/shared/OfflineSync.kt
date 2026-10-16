package com.zeny.hotel.shared

import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/**
 * Uma fonte de mudanças do download offline (`GET /api/v1/sync/changes`, ARCHITECTURE §9, D11): um tipo de
 * registro que o app operacional guarda localmente. O módulo dono a declara (`XOffline.SOURCE`) e registra a
 * sua [OfflineChangeSource] como bean. Quem não tem [permission] não recebe nada dela; com escopo `OWN`, só
 * os registros de que é dono. O boot recusa nome repetido, fora do formato ou permissão que nenhum
 * `ModulePermissions` declara.
 *
 * @property name minúsculas, dígitos e hífen, começando por letra (até 40 caracteres): `rooms`, `tasks`.
 * @property permission a permissão de leitura que a fonte exige.
 */
data class OfflineSource(
    val name: String,
    val permission: PermissionCode,
) {
    init {
        require(NAME.matches(name)) { "Nome de fonte offline inválido: '$name'" }
    }

    override fun toString(): String = name

    private companion object {
        val NAME = Regex("[a-z][a-z0-9-]{0,39}")
    }
}

/**
 * A posição de uma mudança: o `xid8` da transação que a gravou e o `change_seq` (H6). As posições são únicas
 * (uma só sequence) e a ordem é `(xid, seq)`.
 */
data class OfflinePosition(
    val xid: Long,
    val seq: Long,
) : Comparable<OfflinePosition> {
    init {
        require(xid >= 0 && seq >= 0) { "Posição offline negativa" }
    }

    override fun compareTo(other: OfflinePosition): Int = compareValuesBy(this, other, { it.xid }, { it.seq })

    companion object {
        /** Antes de qualquer mudança: o download completo. */
        val START = OfflinePosition(0, 0)

        /** A posição lida das colunas de [OfflineChangeRequest.positionColumns]. */
        fun from(rs: ResultSet): OfflinePosition =
            OfflinePosition(rs.getString(XID_COLUMN).toLong(), rs.getLong(SEQ_COLUMN))

        internal const val XID_COLUMN = "offline_change_xid"
        internal const val SEQ_COLUMN = "offline_change_seq"
    }
}

/**
 * O pedido de mudanças a uma [OfflineChangeSource]: as linhas com posição depois de [after] e de transações
 * já terminadas ([horizon], exclusivo), em ordem `(change_xid, change_seq)`, até [limit], no [scope] do
 * usuário. Na consulta, use [window], [positionColumns] e [sqlParams]:
 *
 * ```kotlin
 * jdbc.sql("""
 *     SELECT r.id, r.version, …, ${OfflineChangeRequest.positionColumns("r")}
 *     FROM rooms.room r
 *     WHERE ${OfflineChangeRequest.window("r")}
 *     ORDER BY r.change_xid, r.change_seq
 *     LIMIT :limit
 * """).params(request.sqlParams()).query { rs, _ -> … OfflinePosition.from(rs) … }.list()
 * ```
 *
 * A fonte devolve também os registros desativados (como *tombstones*): no download completo, o `shared`
 * descarta os que o cliente nunca recebeu.
 */
data class OfflineChangeRequest(
    val after: OfflinePosition,
    val horizon: Long,
    val scope: ScopeFilter,
    val limit: Int,
) {
    /** Parâmetros nomeados de [window] (`afterXid`, `afterSeq`, `horizon`), mais `limit` e `owner`. */
    fun sqlParams(): Map<String, Any?> =
        mapOf(
            "afterXid" to after.xid,
            "afterSeq" to after.seq,
            "horizon" to horizon,
            "limit" to limit,
            "owner" to scope.ownerId,
        )

    companion object {
        /**
         * O filtro da janela: depois de `:afterXid`/`:afterSeq` e só de transações terminadas
         * (`change_xid < :horizon`), sobre as colunas `change_xid` e `change_seq` de [alias].
         */
        fun window(alias: String): String =
            "($alias.change_xid, $alias.change_seq) > " +
                "(CAST(CAST(:afterXid AS text) AS xid8), :afterSeq) " +
                "AND $alias.change_xid < CAST(CAST(:horizon AS text) AS xid8)"

        /** As colunas da posição, lidas por [OfflinePosition.from]. */
        fun positionColumns(alias: String): String =
            "$alias.change_xid::text AS ${OfflinePosition.XID_COLUMN}, " +
                "$alias.change_seq AS ${OfflinePosition.SEQ_COLUMN}"
    }
}

/**
 * Uma mudança de uma fonte: o registro [id] na [version] com os [data] que o app guarda, ou um *tombstone*
 * ([data] nulo: o cliente apaga o registro). [data] vira JSON e vai ao dispositivo: só o necessário para a
 * operação, nada de nome, documento ou contato de hóspede (RN-13).
 */
data class OfflineChange(
    val position: OfflinePosition,
    val id: UUID,
    val version: Long?,
    val data: Any?,
) {
    val deleted: Boolean get() = data == null

    companion object {
        fun upsert(
            position: OfflinePosition,
            id: UUID,
            version: Long,
            data: Any,
        ) = OfflineChange(position, id, version, data)

        fun tombstone(
            position: OfflinePosition,
            id: UUID,
            version: Long? = null,
        ) = OfflineChange(position, id, version, null)
    }
}

/**
 * Porta de uma fonte do download offline, implementada pelo módulo dono e registrada como bean (o `shared`
 * não conhece os módulos). A tabela da fonte tem as colunas `change_seq` e `change_xid`, gravadas pelo
 * trigger `BEFORE INSERT OR UPDATE … EXECUTE FUNCTION shared.mark_offline_change()` criado na migração do
 * módulo. Um registro desativado (ou que deixou de ser visível) vira *tombstone* derivado da própria linha;
 * um registro apagado de verdade, ou que saiu do escopo `OWN` de alguém, vai pela [OfflineTombstones].
 *
 * A fonte devolve até [OfflineChangeRequest.limit] mudanças na janela, em ordem de posição, e aplica o
 * escopo na própria consulta (`:owner` nulo com `ALL`).
 */
interface OfflineChangeSource {
    val source: OfflineSource

    fun changes(request: OfflineChangeRequest): List<OfflineChange>
}

/**
 * Porta dos *tombstones* gravados pela aplicação (tabela `shared.sync_tombstone`), para o que a própria
 * fonte não deriva da linha: chame dentro da transação de escrita que apagou o registro ou o tirou do
 * escopo de alguém. Fora de uma transação de escrita, `IllegalStateException`; fonte não registrada,
 * `IllegalArgumentException`.
 */
interface OfflineTombstones {
    /**
     * O registro [id] da [source] sumiu. Com [leftScopeOf] vazio, foi apagado: todos que veem a fonte o
     * removem. Com usuários, só saiu do escopo `OWN` deles (reatribuído, por exemplo): só eles, quando têm a
     * permissão com `OWN`, recebem o *tombstone*; quem tem `ALL` continua com o registro.
     */
    fun record(
        source: OfflineSource,
        id: UUID,
        leftScopeOf: Set<UserId> = emptySet(),
    )
}

/**
 * O tipo de um comando offline: `<módulo>.<nome>` em minúsculas e kebab-case
 * (`housekeeping.complete-task`). Um tipo publicado não muda: os apps antigos ainda o enviam.
 */
@JvmInline
value class OfflineCommandType(
    val value: String,
) {
    init {
        require(value.length <= MAX_LENGTH && PATTERN.matches(value)) { "Tipo de comando inválido: '$value'" }
    }

    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 100
        private val PATTERN = Regex("[a-z][a-z0-9]*(?:-[a-z0-9]+)*(?:\\.[a-z][a-z0-9]*(?:-[a-z0-9]+)*)+")

        fun isValid(value: String): Boolean = value.length <= MAX_LENGTH && PATTERN.matches(value)
    }
}

/**
 * Um comando offline já aceito pelo protocolo, entregue ao handler.
 *
 * @property commandId o UUIDv7 gerado no dispositivo; um registro criado offline pode usá-lo como id.
 * @property occurredAt quando aconteceu, corrigido pela defasagem do relógio do dispositivo (RN-22) e nunca
 *   depois do recebimento pelo servidor.
 * @property deviceOccurredAt o horário informado pelo dispositivo, sem correção.
 */
data class OfflineCommand<P : Any>(
    val commandId: UUID,
    val type: OfflineCommandType,
    val payload: P,
    val occurredAt: Instant,
    val deviceOccurredAt: Instant,
)

/** Um alerta de um comando aplicado: código estável e mensagem em pt-BR, sem dado pessoal. */
data class OfflineCommandWarning(
    val code: ErrorCode,
    val message: String,
)

/** O resultado de um comando aplicado; com [warnings], "aplicado com alerta" (RN-22: registrado e sinalizado). */
data class OfflineCommandOutcome(
    val warnings: List<OfflineCommandWarning> = emptyList(),
) {
    companion object {
        val APPLIED = OfflineCommandOutcome()
    }
}

/**
 * Porta de um tipo de comando offline (`POST /api/v1/sync/commands`, ARCHITECTURE §9 e §6.2), implementada
 * pelo módulo dono e registrada como bean. [handle] é um caso de uso de escrita: `@Audited` e
 * `@Transactional` (regra ArchUnit `offlineCommandHandlersAreAudited`), com a autorização pela porta
 * [Authorization] (o ator é o usuário da sincronização). Ele roda na transação aberta pelo protocolo, a
 * mesma que grava o resultado do `commandId`: aplicado uma vez só, mesmo com repetições simultâneas.
 *
 * Para rejeitar, lance uma [DomainException] (o efeito é desfeito e a rejeição, com o código, fica gravada:
 * a repetição devolve a mesma). Para aplicar com alerta, devolva [OfflineCommandOutcome.warnings]. Qualquer
 * outra exceção é falha inesperada: nada fica gravado, e o comando e os seguintes do lote voltam para o
 * dispositivo reenviar. O payload chega já convertido em [payloadType] e conferido pela Bean Validation.
 */
interface OfflineCommandHandler<P : Any> {
    val type: OfflineCommandType

    val payloadType: Class<P>

    fun handle(command: OfflineCommand<P>): OfflineCommandOutcome
}

/** Códigos dos resultados de comando e dos alertas do protocolo offline (não são status HTTP). */
object OfflineSyncCodes {
    /** Rejeitado: o `commandId` não é um UUIDv7, ou o instante dele está fora da faixa plausível. */
    val COMMAND_ID_INVALID = ErrorCode("COMMAND_ID_INVALID")

    /** Rejeitado: o `commandId` já foi usado por outro usuário. */
    val COMMAND_ID_CONFLICT = ErrorCode("COMMAND_ID_CONFLICT")

    /** Rejeitado: o `commandId` já foi usado com outro tipo de comando. */
    val COMMAND_ID_REUSED = ErrorCode("COMMAND_ID_REUSED")

    /**
     * Rejeitado: o instante do `commandId` (UUIDv7, pelo relógio do aparelho), corrigido pela defasagem do
     * relógio, é mais antigo que a retenção dos resultados, ou o resultado do id já saiu pela retenção (o id
     * continua guardado e nunca é aplicado de novo).
     */
    val COMMAND_EXPIRED = ErrorCode("COMMAND_EXPIRED")

    /**
     * Rejeitado: `deviceClock` ou `occurredAt` fora da faixa plausível (antes de 2020-01-01 ou além de
     * `zeny.sync.max-device-clock-ahead` à frente do servidor).
     */
    val DEVICE_TIME_INVALID = ErrorCode("DEVICE_TIME_INVALID")

    /** Rejeitado: nenhum módulo trata este tipo de comando. */
    val UNKNOWN_COMMAND_TYPE = ErrorCode("UNKNOWN_COMMAND_TYPE")

    /** Rejeitado: o payload não corresponde ao do tipo de comando. */
    val COMMAND_PAYLOAD_INVALID = ErrorCode("COMMAND_PAYLOAD_INVALID")

    /** Falha inesperada no servidor: nada foi aplicado; reenvie o comando. */
    val COMMAND_FAILED = ErrorCode("COMMAND_FAILED")

    /** Não processado porque um comando anterior do lote falhou; reenvie a partir dele. */
    val COMMAND_NOT_PROCESSED = ErrorCode("COMMAND_NOT_PROCESSED")

    /** Alerta: o relógio do dispositivo estava defasado além da tolerância; o horário foi corrigido. */
    val CLOCK_SKEW_EXCEEDED = ErrorCode("CLOCK_SKEW_EXCEEDED")
}
