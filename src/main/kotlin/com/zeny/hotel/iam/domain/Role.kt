package com.zeny.hotel.iam.domain

import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.TemplateGrant
import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.TypedIdConverter
import com.zeny.hotel.shared.UuidV7
import jakarta.persistence.CascadeType
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.MapKeyColumn
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Id de uma role (agregado [Role]). */
@JvmInline
value class RoleId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): RoleId = RoleId(UuidV7.generate())
    }
}

/** Conversor do [RoleId] para as coleções (`User.roleIds`). */
class RoleIdConverter : TypedIdConverter<RoleId>(::RoleId)

/**
 * Concessão de uma permissão do catálogo, como entra na união das roles: escopo e limites.
 */
data class Grant(
    val permission: PermissionCode,
    val scope: PermissionScope,
    val limits: Map<String, LimitValue> = emptyMap(),
)

/**
 * Role (SPEC §8.2.1, RF-ACS-03): um conjunto de concessões ([RoleGrant]) de permissões do
 * catálogo, com escopo e limites, atribuído a usuários. Nunca é apagada, só desativada; role
 * inativa não concede nada.
 *
 * As roles criadas a partir dos modelos da SPEC §3.1 guardam o [template]. O seed do catálogo
 * só acrescenta concessões a um modelo que o administrador não editou ([customized], H5). A
 * role de sistema ([systemRole], o Administrador) é mantida pelo catálogo com todas as
 * permissões, no escopo mais amplo e sem teto, e não aceita edição.
 */
@Entity
@Table(name = "role", schema = "iam")
@Suppress("LongParameterList", "TooManyFunctions") // estado e operações do agregado; a criação passa pelas fábricas
class Role private constructor(
    @Id
    val id: RoleId,
    @Enumerated(EnumType.STRING)
    @Column(name = "template_key", updatable = false)
    val template: RoleTemplate?,
    @Column(name = "system_role", nullable = false, updatable = false)
    val systemRole: Boolean,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    name: String,
    description: String,
) {
    @Column(nullable = false)
    var name: String = name
        protected set

    @Column(nullable = false)
    var description: String = description
        protected set

    @Column(nullable = false)
    var active: Boolean = true
        protected set

    /** Se o administrador editou a role; o seed de um modelo editado não acrescenta mais nada (H5). */
    @Column(nullable = false)
    var customized: Boolean = false
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt
        protected set

    @Version
    var version: Long? = null
        protected set

    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", nullable = false)
    private val grantSet: MutableSet<RoleGrant> = mutableSetOf()

    val grants: Set<RoleGrant> get() = grantSet

    fun grantOf(permission: PermissionCode): RoleGrant? = grantSet.firstOrNull { it.permission == permission }

    /** As concessões como entram na união das roles. */
    fun grantValues(): List<Grant> = grantSet.map { it.toGrant() }

    /**
     * Concede (ou redefine) a permissão com o escopo e os limites (RF-ACS-03). O escopo precisa
     * ser suportado e os limites, declarados e válidos para a unidade. Marca a role como editada.
     */
    fun grant(
        definition: PermissionDefinition,
        scope: PermissionScope,
        limits: Map<String, LimitValue>,
        now: Instant,
    ) {
        requireEditable()
        validate(definition, scope, limits)
        put(definition.code, scope, limits)
        touch(now, customize = true)
    }

    /** Retira a permissão da role, se ela a concedia. Marca a role como editada. */
    fun revoke(
        permission: PermissionCode,
        now: Instant,
    ) {
        requireEditable()
        if (grantSet.removeIf { it.permission == permission }) touch(now, customize = true)
    }

    /** Desativa a role: deixa de conceder qualquer permissão (RF-ACS-03). Devolve se mudou. */
    fun deactivate(now: Instant): Boolean {
        requireEditable()
        if (!active) return false
        active = false
        touch(now, customize = false)
        return true
    }

    /** Reativa a role: as concessões dela voltam a valer. Devolve se mudou. */
    fun reactivate(now: Instant): Boolean {
        requireEditable()
        if (active) return false
        active = true
        touch(now, customize = false)
        return true
    }

    /** Troca o nome e a descrição. Marca a role como editada quando algo muda. */
    fun rename(
        name: String,
        description: String,
        now: Instant,
    ) {
        requireEditable()
        val newName = checkedName(name)
        val newDescription = checkedDescription(description)
        if (newName != this.name || newDescription != this.description) {
            this.name = newName
            this.description = newDescription
            touch(now, customize = true)
        }
    }

    /**
     * Seed de um modelo (H5): acrescenta a concessão sugerida se a role ainda não tem a
     * permissão e o administrador não editou a role. Nunca muda uma concessão existente.
     * Devolve se acrescentou.
     */
    fun applyTemplateGrant(
        definition: PermissionDefinition,
        suggestion: TemplateGrant,
        now: Instant,
    ): Boolean {
        if (customized || systemRole || grantOf(definition.code) != null) return false
        validate(definition, suggestion.scope, suggestion.limits)
        put(definition.code, suggestion.scope, suggestion.limits)
        touch(now, customize = false)
        return true
    }

    /**
     * Role de sistema: garante a permissão no escopo mais amplo e com todas as chaves de limite
     * sem teto. Devolve se mudou algo.
     */
    fun ensureFullGrant(
        definition: PermissionDefinition,
        now: Instant,
    ): Boolean {
        check(systemRole) { "Só a role de sistema recebe todas as permissões" }
        val scope = definition.broadestScope
        val limits = definition.limits.associate { it.key to LimitValue.Unlimited }
        val current = grantOf(definition.code)
        if (current != null && current.scope == scope && current.limits == limits) return false
        put(definition.code, scope, limits)
        touch(now, customize = false)
        return true
    }

    private fun put(
        permission: PermissionCode,
        scope: PermissionScope,
        limits: Map<String, LimitValue>,
    ) {
        val current = grantOf(permission)
        if (current == null) {
            grantSet.add(RoleGrant(UuidV7.generate(), permission, scope, limits))
        } else {
            current.redefine(scope, limits)
        }
    }

    private fun requireEditable() {
        check(!systemRole) { "A role ${RoleTemplate.ADMINISTRATOR.displayName} é mantida pelo catálogo" }
    }

    private fun touch(
        now: Instant,
        customize: Boolean,
    ) {
        if (customize) customized = true
        updatedAt = now
    }

    companion object {
        const val MAX_NAME_LENGTH = 100
        const val MAX_DESCRIPTION_LENGTH = 500

        /** Role nova, ativa e sem concessões, criada pelo administrador. */
        fun create(
            name: String,
            description: String,
            now: Instant,
        ): Role = Role(RoleId.new(), null, false, now, checkedName(name), checkedDescription(description))

        /** Role criada a partir de um modelo da SPEC §3.1; a do Administrador é a role de sistema. */
        fun fromTemplate(
            template: RoleTemplate,
            now: Instant,
        ): Role =
            Role(
                id = RoleId.new(),
                template = template,
                systemRole = template == RoleTemplate.ADMINISTRATOR,
                createdAt = now,
                name = template.displayName,
                description = template.description,
            )

        /** Recusa escopo não suportado, chave de limite não declarada e valor inválido para a unidade. */
        fun validate(
            definition: PermissionDefinition,
            scope: PermissionScope,
            limits: Map<String, LimitValue>,
        ) {
            require(scope in definition.scopes) { "A permissão ${definition.code} não suporta o escopo $scope" }
            limits.forEach { (key, value) ->
                val limit =
                    requireNotNull(
                        definition.limitOf(key),
                    ) { "A permissão ${definition.code} não aceita o limite '$key'" }
                require(limit.accepts(value)) { "Valor inválido para o limite '$key' de ${definition.code}" }
            }
        }

        private fun checkedName(name: String): String {
            val trimmed = name.trim()
            require(trimmed.isNotEmpty() && trimmed.length <= MAX_NAME_LENGTH) { "Nome de role inválido" }
            return trimmed
        }

        private fun checkedDescription(description: String): String {
            val trimmed = description.trim()
            require(trimmed.length <= MAX_DESCRIPTION_LENGTH) { "Descrição de role longa demais" }
            return trimmed
        }
    }
}

/** Uma concessão de [Role]: a permissão, o escopo e os limites por chave (SPEC §8.2.1). */
@Entity
@Table(name = "role_grant", schema = "iam")
class RoleGrant internal constructor(
    @Id
    val id: UUID,
    @Column(name = "permission_code", nullable = false, updatable = false)
    val permission: PermissionCode,
    scope: PermissionScope,
    limits: Map<String, LimitValue>,
) {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var scope: PermissionScope = scope
        protected set

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "role_grant_limit", schema = "iam", joinColumns = [JoinColumn(name = "grant_id")])
    @MapKeyColumn(name = "limit_key")
    private val limitValues: MutableMap<String, GrantLimit> =
        limits.mapValuesTo(mutableMapOf()) { GrantLimit.of(it.value) }

    val limits: Map<String, LimitValue> get() = limitValues.mapValues { it.value.toLimitValue() }

    internal fun redefine(
        scope: PermissionScope,
        limits: Map<String, LimitValue>,
    ) {
        this.scope = scope
        limitValues.keys.retainAll(limits.keys)
        limits.forEach { (key, value) ->
            if (limitValues[key]?.toLimitValue() != value) limitValues[key] = GrantLimit.of(value)
        }
    }

    fun toGrant(): Grant = Grant(permission, scope, limits)
}

/** O valor de limite gravado como `unlimited` e `max_value` (exatamente um dos dois). */
fun limitValueOf(
    unlimited: Boolean,
    maxValue: BigDecimal?,
): LimitValue =
    if (unlimited) {
        LimitValue.Unlimited
    } else {
        LimitValue.Max(checkNotNull(maxValue) { "Limite sem teto e sem a marca de ilimitado" })
    }

/** Valor de um limite gravado: sem teto ([unlimited]) ou o teto ([maxValue]). */
@Embeddable
class GrantLimit(
    @Column(nullable = false)
    val unlimited: Boolean,
    @Column(name = "max_value", precision = 18, scale = 4)
    val maxValue: BigDecimal?,
) {
    fun toLimitValue(): LimitValue = limitValueOf(unlimited, maxValue)

    companion object {
        fun of(value: LimitValue): GrantLimit =
            when (value) {
                LimitValue.Unlimited -> GrantLimit(true, null)
                is LimitValue.Max -> GrantLimit(false, value.value)
            }
    }
}
