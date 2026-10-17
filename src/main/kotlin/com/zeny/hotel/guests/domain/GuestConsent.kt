package com.zeny.hotel.guests.domain

import com.zeny.hotel.guests.ConsentPurpose
import com.zeny.hotel.guests.ConsentSource
import com.zeny.hotel.guests.GuestConsentId
import com.zeny.hotel.shared.UserId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Um consentimento do titular para uma finalidade (RF-HOS-07, SPEC §8.2.4): quando e por qual meio foi dado e,
 * se for o caso, revogado, e quem registrou cada um. Faz parte do agregado [Guest] e nunca é apagado (prova, RN-20;
 * fica até depois da anonimização): a revogação só marca a linha, e um consentimento novo da mesma finalidade é
 * outra linha. Na mesclagem de duplicados a linha passa para o sobrevivente (só por SQL, como os documentos), e a
 * ativa repetida fica substituída ([supersededAt]).
 */
@Entity
@Table(name = "guest_consent", schema = "guests")
class GuestConsent internal constructor(
    @Id
    val id: GuestConsentId,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    val purpose: ConsentPurpose,
    @Column(name = "granted_at", nullable = false, updatable = false)
    val grantedAt: Instant,
    @Enumerated(EnumType.STRING)
    @Column(name = "granted_source", nullable = false, updatable = false)
    val grantedSource: ConsentSource,
    grantedBy: UserId?,
) {
    @Column(name = "granted_by", updatable = false)
    private val grantedByUuid: UUID? = grantedBy?.value

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_source")
    var revokedSource: ConsentSource? = null
        protected set

    @Column(name = "revoked_by")
    private var revokedByUuid: UUID? = null

    /**
     * Quando deixou de valer numa mesclagem de duplicados por haver no sobrevivente um ativo da mesma finalidade, que
     * prevalece ([supersededBy]); não é uma revogação do titular. `null` se não foi substituído.
     */
    @Column(name = "superseded_at", updatable = false, insertable = false)
    val supersededAt: Instant? = null

    @Column(name = "superseded_by", updatable = false, insertable = false)
    private val supersededByUuid: UUID? = null

    val grantedBy: UserId? get() = grantedByUuid?.let(::UserId)

    val revokedBy: UserId? get() = revokedByUuid?.let(::UserId)

    val supersededBy: GuestConsentId? get() = supersededByUuid?.let(::GuestConsentId)

    val active: Boolean get() = revokedAt == null && supersededAt == null

    /** Revoga; devolve `false` se já estava revogado. */
    internal fun revoke(
        source: ConsentSource,
        by: UserId?,
        now: Instant,
    ): Boolean {
        if (!active) return false
        revokedAt = maxOf(now, grantedAt)
        revokedSource = source
        revokedByUuid = by?.value
        return true
    }

    override fun toString(): String = "GuestConsent(id=$id, purpose=$purpose, active=$active)"
}
