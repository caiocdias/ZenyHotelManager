package com.zeny.hotel.iam.infrastructure

import com.zeny.hotel.iam.application.AssignedRole
import com.zeny.hotel.iam.application.AuthorizationData
import com.zeny.hotel.iam.application.AuthorizationLock
import com.zeny.hotel.iam.application.PermissionStore
import com.zeny.hotel.iam.application.Roles
import com.zeny.hotel.iam.application.StoredPermission
import com.zeny.hotel.iam.domain.Grant
import com.zeny.hotel.iam.domain.Role
import com.zeny.hotel.iam.domain.RoleGrants
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.iam.domain.limitValueOf
import com.zeny.hotel.shared.LimitDefinition
import com.zeny.hotel.shared.LimitUnit
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.UserId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

interface RoleJpaRepository : JpaRepository<Role, UUID> {
    fun findByTemplate(template: RoleTemplate): Role?
}

@Component
class JpaRoles(
    private val repository: RoleJpaRepository,
) : Roles {
    override fun findById(id: RoleId): Role? = repository.findById(id.value).orElse(null)

    override fun findByTemplate(template: RoleTemplate): Role? = repository.findByTemplate(template)

    override fun findAllById(ids: Collection<RoleId>): List<Role> =
        if (ids.isEmpty()) emptyList() else repository.findAllById(ids.map { it.value })

    override fun add(role: Role) {
        repository.save(role)
    }

    override fun tryAdd(role: Role): Boolean =
        UniqueConstraints.unlessViolated(NAME_CONSTRAINT) {
            repository.saveAndFlush(role)
        }

    override fun tryFlush(): Boolean = UniqueConstraints.unlessViolated(NAME_CONSTRAINT) { repository.flush() }

    private companion object {
        /** Índice único de `lower(name)`. */
        const val NAME_CONSTRAINT = "role_name_uk"
    }
}

/**
 * Trava a linha única de `iam.authorization_version` (`SELECT … FOR UPDATE`) até o fim da
 * transação: a mesma linha que os triggers atualizam a cada mudança de autorização.
 */
@Component
class JdbcAuthorizationLock(
    private val jdbc: JdbcClient,
) : AuthorizationLock {
    override fun acquire() {
        jdbc.sql("SELECT value FROM iam.authorization_version WHERE id = 1 FOR UPDATE").query(Long::class.java).single()
    }
}

/** Um limite como fica no JSON da coluna `iam.permission.limits`. */
private data class StoredLimit(
    val key: String,
    val unit: LimitUnit,
    val description: String,
)

/** `iam.permission` pelo `JdbcClient`, na transação corrente. */
@Component
class JdbcPermissionStore(
    private val jdbc: JdbcClient,
    private val jsonMapper: JsonMapper,
) : PermissionStore {
    override fun lockForSynchronization() {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('iam.permission-catalog'))").query().singleRow()
    }

    override fun findAll(): List<StoredPermission> =
        jdbc
            .sql("SELECT $COLUMNS, obsolete_at FROM iam.permission")
            .query { rs, _ -> StoredPermission(definition(rs), rs.getObject("obsolete_at") != null) }
            .list()

    override fun insert(
        definition: PermissionDefinition,
        now: Instant,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO iam.permission (code, module, description, scopes, sensitive, limits, created_at, updated_at)
                VALUES (:code, :module, :description, string_to_array(:scopes, ',')::VARCHAR(20)[], :sensitive,
                        CAST(:limits AS JSONB), :now, :now)
                """.trimIndent(),
            ).params(parameters(definition, now))
            .update()
    }

    override fun update(
        definition: PermissionDefinition,
        now: Instant,
    ) {
        jdbc
            .sql(
                """
                UPDATE iam.permission
                SET module = :module, description = :description,
                    scopes = string_to_array(:scopes, ',')::VARCHAR(20)[], sensitive = :sensitive,
                    limits = CAST(:limits AS JSONB), updated_at = :now, obsolete_at = NULL
                WHERE code = :code
                """.trimIndent(),
            ).params(parameters(definition, now))
            .update()
    }

    override fun markObsolete(
        code: PermissionCode,
        now: Instant,
    ) {
        jdbc
            .sql("UPDATE iam.permission SET obsolete_at = :now, updated_at = :now WHERE code = :code")
            .param("now", utc(now))
            .param("code", code.value)
            .update()
    }

    override fun activeDefinitions(): List<PermissionDefinition> =
        jdbc
            .sql("SELECT $COLUMNS FROM iam.permission WHERE obsolete_at IS NULL")
            .query { rs, _ -> definition(rs) }
            .list()

    override fun activePage(
        afterCode: String?,
        limit: Int,
    ): List<PermissionDefinition> =
        jdbc
            .sql(
                """
                SELECT $COLUMNS FROM iam.permission
                WHERE obsolete_at IS NULL
                  AND (CAST(:after AS VARCHAR) IS NULL OR code COLLATE "C" > CAST(:after AS VARCHAR))
                ORDER BY code COLLATE "C"
                LIMIT :limit
                """.trimIndent(),
            ).param("after", afterCode)
            .param("limit", limit)
            .query { rs, _ -> definition(rs) }
            .list()

    private fun parameters(
        definition: PermissionDefinition,
        now: Instant,
    ): Map<String, Any> =
        mapOf(
            "code" to definition.code.value,
            "module" to definition.code.module,
            "description" to definition.description,
            "scopes" to definition.scopes.sortedBy { it.breadth }.joinToString(",") { it.name },
            "sensitive" to definition.sensitive,
            "limits" to
                jsonMapper.writeValueAsString(definition.limits.map { StoredLimit(it.key, it.unit, it.description) }),
            "now" to utc(now),
        )

    private fun definition(rs: ResultSet): PermissionDefinition {
        @Suppress("UNCHECKED_CAST")
        val scopes = (rs.getArray("scopes").array as Array<String>).map(PermissionScope::valueOf).toSet()
        val limits = jsonMapper.readValue<List<StoredLimit>>(rs.getString("limits"))
        return PermissionDefinition(
            code = PermissionCode(rs.getString("code")),
            description = rs.getString("description"),
            scopes = scopes,
            sensitive = rs.getBoolean("sensitive"),
            limits = limits.map { LimitDefinition(it.key, it.unit, it.description) },
        )
    }

    private companion object {
        const val COLUMNS = "code, description, scopes, sensitive, limits"
    }
}

/** Leitura das roles, concessões e da versão das autorizações (`JdbcClient`, ARCHITECTURE §11). */
@Component
class JdbcAuthorizationData(
    private val jdbc: JdbcClient,
) : AuthorizationData {
    override fun currentVersion(): Long =
        jdbc
            .sql("SELECT value FROM iam.authorization_version WHERE id = 1")
            .query(Long::class.java)
            .single()

    override fun isUserActive(userId: UserId): Boolean =
        jdbc
            .sql("SELECT active FROM iam.user_account WHERE id = :id")
            .param("id", userId.value)
            .query(Boolean::class.java)
            .optional()
            .orElse(false)

    override fun roleGrantsOf(userId: UserId): List<RoleGrants> = roleGrantsByRoleOf(userId).values.toList()

    override fun roleGrantsByRoleOf(userId: UserId): Map<RoleId, RoleGrants> {
        val rows =
            jdbc
                .sql(
                    """
                    SELECT r.id AS role_id, r.active, g.id AS grant_id, g.permission_code, g.scope,
                           l.limit_key, l.unlimited, l.max_value
                    FROM iam.user_role ur
                    JOIN iam.role r ON r.id = ur.role_id
                    LEFT JOIN iam.role_grant g ON g.role_id = r.id
                    LEFT JOIN iam.role_grant_limit l ON l.grant_id = g.id
                    WHERE ur.user_id = :userId
                    """.trimIndent(),
                ).param("userId", userId.value)
                .query { rs, _ -> GrantRow.of(rs) }
                .list()
        return rows.groupBy { it.roleId }.entries.associate { (roleId, roleRows) ->
            val grants =
                roleRows
                    .mapNotNull { it.grant }
                    .groupBy { it.id }
                    .values
                    .map { parts ->
                        Grant(
                            permission = parts.first().permission,
                            scope = parts.first().scope,
                            limits = parts.mapNotNull { it.limit }.toMap(),
                        )
                    }
            RoleId(roleId) to RoleGrants(roleActive = roleRows.first().roleActive, grants = grants)
        }
    }

    override fun holdersOf(roleId: RoleId): List<UserId> =
        jdbc
            .sql("SELECT user_id FROM iam.user_role WHERE role_id = :roleId ORDER BY user_id")
            .param("roleId", roleId.value)
            .query { rs, _ -> UserId(rs.getObject("user_id", UUID::class.java)) }
            .list()

    override fun activeUsersGranted(permission: PermissionCode): List<UserId> =
        jdbc
            .sql(
                """
                SELECT DISTINCT ur.user_id
                FROM iam.user_role ur
                JOIN iam.user_account u ON u.id = ur.user_id AND u.active
                JOIN iam.role r ON r.id = ur.role_id AND r.active
                JOIN iam.role_grant g ON g.role_id = r.id
                WHERE g.permission_code = :permission
                ORDER BY ur.user_id
                """.trimIndent(),
            ).param("permission", permission.value)
            .query { rs, _ -> UserId(rs.getObject("user_id", UUID::class.java)) }
            .list()

    override fun activeRolesOf(userId: UserId): List<AssignedRole> =
        jdbc
            .sql(
                """
                SELECT r.id, r.name FROM iam.user_role ur JOIN iam.role r ON r.id = ur.role_id
                WHERE ur.user_id = :userId AND r.active
                ORDER BY r.name, r.id
                """.trimIndent(),
            ).param("userId", userId.value)
            .query { rs, _ -> AssignedRole(RoleId(rs.getObject("id", UUID::class.java)), rs.getString("name")) }
            .list()

    override fun isAdministrator(userId: UserId): Boolean =
        jdbc
            .sql(
                """
                SELECT EXISTS (SELECT 1 FROM iam.user_role ur JOIN iam.role r ON r.id = ur.role_id
                               WHERE ur.user_id = :userId AND r.system_role)
                """.trimIndent(),
            ).param("userId", userId.value)
            .query(Boolean::class.java)
            .single()

    override fun activeAdministratorCount(): Int =
        jdbc
            .sql(
                """
                SELECT count(DISTINCT u.id) FROM iam.user_account u
                JOIN iam.user_role ur ON ur.user_id = u.id
                JOIN iam.role r ON r.id = ur.role_id
                WHERE u.active AND r.system_role
                """.trimIndent(),
            ).query(Int::class.java)
            .single()

    /** Uma linha da consulta: a role e, se houver, uma concessão com um dos limites dela. */
    private class GrantRow(
        val roleId: UUID,
        val roleActive: Boolean,
        val grant: GrantPart?,
    ) {
        companion object {
            fun of(rs: ResultSet): GrantRow {
                val grantId = rs.getObject("grant_id", UUID::class.java)
                val grant =
                    grantId?.let {
                        GrantPart(
                            id = it,
                            permission = PermissionCode(rs.getString("permission_code")),
                            scope = PermissionScope.valueOf(rs.getString("scope")),
                            limit =
                                rs.getString("limit_key")?.let { key ->
                                    key to limitValueOf(rs.getBoolean("unlimited"), rs.getBigDecimal("max_value"))
                                },
                        )
                    }
                return GrantRow(rs.getObject("role_id", UUID::class.java), rs.getBoolean("active"), grant)
            }
        }
    }

    private class GrantPart(
        val id: UUID,
        val permission: PermissionCode,
        val scope: PermissionScope,
        val limit: Pair<String, LimitValue>?,
    )
}

private fun utc(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)
