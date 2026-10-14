package com.zeny.hotel.shared.application.idempotency

import com.zeny.hotel.shared.UuidV7
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.springframework.util.unit.DataSize
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Configuração da idempotência de requisições (`zeny.idempotency.*` no `application.yaml`).
 *
 * @property retention por quanto tempo a resposta fica guardada (ARCHITECTURE §7: 24h).
 * @property maxRequestBodySize maior corpo aceito num endpoint idempotente; o corpo é lido
 *   inteiro antes do handler para calcular o hash, e acima disso a resposta é `413`.
 */
@ConfigurationProperties("zeny.idempotency")
class IdempotencyProperties(
    val retention: Duration,
    val maxRequestBodySize: DataSize,
) {
    init {
        require(!retention.isNegative && !retention.isZero) { "zeny.idempotency.retention deve ser positivo" }
        require(maxRequestBodySize.toBytes() in 1..Int.MAX_VALUE) {
            "zeny.idempotency.max-request-body-size fora do intervalo aceito"
        }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdempotencyProperties::class)
class IdempotencyConfiguration

/**
 * Uma requisição a um endpoint idempotente: [scope] é quem a fez (usuários diferentes nunca
 * compartilham chave), [key] é o `Idempotency-Key` e [requestHash] identifica a requisição
 * (método, caminho, query, `If-Match` e corpo).
 */
data class IdempotentRequest(
    val scope: String,
    val key: String,
    val requestHash: String,
)

/** Resposta guardada para ser repetida: status, cabeçalhos relevantes e corpo, byte a byte. */
class StoredResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
)

/** Reserva de uma chave por uma requisição que vai executar; só ela grava a resposta. */
@JvmInline
value class IdempotencyReservation(
    val id: UUID,
)

/** O que fazer com uma requisição idempotente que chegou. */
sealed interface IdempotencyDecision {
    /** Chave nova (ou vencida): a requisição executa e depois grava a resposta na [reservation]. */
    data class Proceed(
        val reservation: IdempotencyReservation,
    ) : IdempotencyDecision

    /** Mesma chave e mesma requisição, já concluída: devolver a [response] guardada sem executar. */
    class Replay(
        val response: StoredResponse,
    ) : IdempotencyDecision

    /** Mesma chave e mesma requisição, ainda executando: `409`. */
    data object InProgress : IdempotencyDecision

    /** Mesma chave com outra requisição: `422`. */
    data object KeyReused : IdempotencyDecision
}

/** Registro de uma chave, como está gravado. [response] é nulo enquanto a requisição executa. */
class IdempotencyRecord(
    val id: UUID,
    val requestHash: String,
    val expiresAt: Instant,
    val response: StoredResponse?,
)

/** Reserva nova a gravar: a chave fica `IN_PROGRESS` de [createdAt] até [expiresAt]. */
class NewIdempotencyRecord(
    val id: UUID,
    val request: IdempotentRequest,
    val createdAt: Instant,
    val expiresAt: Instant,
)

/**
 * Porta de persistência das chaves (tabela `shared.idempotency_key`). Cada operação roda e
 * confirma na sua própria transação: a reserva precisa ficar visível às outras requisições
 * antes de o handler executar.
 */
interface IdempotencyRecords {
    /** Grava a reserva se não houver linha para o mesmo escopo e chave; `false` se já houver. */
    fun insertIfAbsent(record: NewIdempotencyRecord): Boolean

    fun find(
        scope: String,
        key: String,
    ): IdempotencyRecord?

    /** Troca a linha vencida [expiredId] pela reserva nova, se ela ainda estiver vencida em [now]. */
    fun replaceExpired(
        expiredId: UUID,
        record: NewIdempotencyRecord,
        now: Instant,
    ): Boolean

    /** Conclui a reserva com a resposta; `false` se a reserva não existe mais ou já foi concluída. */
    fun complete(
        id: UUID,
        response: StoredResponse,
        completedAt: Instant,
    ): Boolean

    /** Apaga a reserva ainda em andamento (requisição recusada antes de produzir efeito). */
    fun deleteInProgress(id: UUID): Boolean

    /** Apaga as chaves vencidas em [now] e devolve quantas foram. */
    fun deleteExpired(now: Instant): Int
}

/**
 * Regras da idempotência de requisições (ARCHITECTURE §7). A reserva é atômica no banco (uma
 * linha por escopo e chave, com restrição de unicidade), então duas requisições simultâneas
 * com a mesma chave nunca executam ambas: a segunda vê a reserva e recebe [InProgress].
 *
 * Uma chave vencida (mais de [IdempotencyProperties.retention]) é tratada como nova, mesmo
 * antes de o job de expurgo apagá-la.
 */
@Service
class IdempotencyService(
    private val records: IdempotencyRecords,
    private val clock: Clock,
    private val properties: IdempotencyProperties,
) {
    fun begin(request: IdempotentRequest): IdempotencyDecision {
        repeat(MAX_ATTEMPTS) { attempt(request)?.let { decision -> return decision } }
        error("Não foi possível reservar a chave de idempotência depois de $MAX_ATTEMPTS tentativas")
    }

    /**
     * Uma tentativa de reservar a chave; `null` quando a linha some ou é trocada entre a
     * tentativa de inserir e a leitura (expurgo, ou outra requisição assumindo a chave vencida).
     */
    private fun attempt(request: IdempotentRequest): IdempotencyDecision? {
        val now = clock.instant()
        val reservation = NewIdempotencyRecord(UuidV7.generate(), request, now, now.plus(properties.retention))
        if (records.insertIfAbsent(reservation)) return proceed(reservation)

        val existing = records.find(request.scope, request.key)
        return when {
            existing == null -> null
            existing.expiresAt <= now -> takeOverExpired(existing, reservation, now)
            existing.requestHash != request.requestHash -> IdempotencyDecision.KeyReused
            existing.response == null -> IdempotencyDecision.InProgress
            else -> IdempotencyDecision.Replay(existing.response)
        }
    }

    /** Guarda a resposta da requisição que executou; `false` se a reserva se perdeu (ver log). */
    fun complete(
        reservation: IdempotencyReservation,
        response: StoredResponse,
    ): Boolean = records.complete(reservation.id, response, clock.instant())

    /** Libera a chave de uma requisição recusada antes de produzir efeito (autorização negada). */
    fun release(reservation: IdempotencyReservation) {
        records.deleteInProgress(reservation.id)
    }

    /** Apaga as chaves vencidas; chamado pelo job de expurgo. */
    fun purgeExpired(): Int = records.deleteExpired(clock.instant())

    private fun takeOverExpired(
        expired: IdempotencyRecord,
        reservation: NewIdempotencyRecord,
        now: Instant,
    ): IdempotencyDecision? = if (records.replaceExpired(expired.id, reservation, now)) proceed(reservation) else null

    private fun proceed(record: NewIdempotencyRecord) = IdempotencyDecision.Proceed(IdempotencyReservation(record.id))

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
