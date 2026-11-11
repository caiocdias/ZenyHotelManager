package com.zeny.hotel.printing.domain

import com.zeny.hotel.printing.PrintingErrorCodes
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.UuidV7
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/**
 * O agente de impressão: o processo local que imprime (SPEC §8.2.18, D-E10-2). Guarda só o SHA-256 do token
 * ([tokenHash]); o token em claro existe apenas no retorno de [create] e [rotateToken]. [tokenGeneration] sobe a cada
 * emissão e é o [credentialStamp], que assim muda a cada rotação mesmo com o relógio parado. A desativação apaga o
 * hash.
 *
 * **Nunca** ponha o hash nem o token em `toString`, log ou trilha.
 */
@Entity
@Table(name = "print_agent", schema = "printing")
class PrintAgent private constructor(
    @Id
    val id: UUID,
    name: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    @Column(nullable = false)
    var name: String = name
        protected set

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", length = TOKEN_HASH_LENGTH)
    var tokenHash: String? = null
        protected set

    @Column(name = "token_generation", nullable = false)
    var tokenGeneration: Int = 0
        protected set

    @Column(nullable = false)
    var active: Boolean = true
        protected set

    @Column(name = "last_seen_at")
    var lastSeenAt: Instant? = null
        protected set

    @Version
    var version: Long? = null
        protected set

    /** Muda a cada emissão de token: opaco para quem o lê (o `ServicePrincipal`). */
    val credentialStamp: String get() = tokenGeneration.toString()

    /** Emite um token novo (em claro) e invalida o anterior. Agente inativo: `422 PRINT_AGENT_INACTIVE`. */
    fun rotateToken(
        @Suppress("UnusedParameter") now: Instant,
    ): String {
        if (!active) {
            throw BusinessRuleException(
                PrintingErrorCodes.PRINT_AGENT_INACTIVE,
                "O agente de impressão está desativado.",
            )
        }
        return issueToken()
    }

    /** Revoga o agente: desativa e apaga o hash do token. */
    fun deactivate() {
        active = false
        tokenHash = null
    }

    fun rename(name: String) {
        this.name = requireValidName(name)
    }

    /** Registra o contato do agente; só muda (e devolve `true`) se o último tiver mais de 1 minuto. */
    fun touch(now: Instant): Boolean {
        val last = lastSeenAt
        if (last != null && Duration.between(last, now) <= TOUCH_INTERVAL) return false
        lastSeenAt = now
        return true
    }

    private fun issueToken(): String {
        val token = newToken()
        tokenHash = hashOf(token)
        tokenGeneration += 1
        return token
    }

    override fun toString(): String = "PrintAgent(id=$id, name=$name, active=$active)"

    companion object {
        /** O prefixo do token, que o realm confere sem consultar o banco. */
        const val TOKEN_PREFIX = "zpa_"

        private const val TOKEN_BYTES = 32
        private val TOUCH_INTERVAL: Duration = Duration.ofMinutes(1)
        private val random = SecureRandom()

        /** O SHA-256 do [token] em hex minúsculo (64 caracteres). */
        fun hashOf(token: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))

        /** Cria o agente ativo, com o primeiro token. Devolve o agente e o token em claro, que não se recupera. */
        fun create(
            name: String,
            now: Instant,
        ): Pair<PrintAgent, String> {
            val agent = PrintAgent(UuidV7.generate(), requireValidName(name), now)
            return agent to agent.issueToken()
        }

        private fun newToken(): String {
            val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
            return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}

private const val MAX_NAME_LENGTH = 100
private const val TOKEN_HASH_LENGTH = 64

/** Nome de 1 a 100 caracteres, sem controle (D-E10-8); devolve o nome aparado. */
internal fun requireValidName(name: String): String {
    val trimmed = name.trim()
    require(trimmed.length in 1..MAX_NAME_LENGTH) { "O nome deve ter de 1 a $MAX_NAME_LENGTH caracteres." }
    require(trimmed.none { it.isISOControl() }) { "O nome não pode ter caracteres de controle." }
    return trimmed
}
