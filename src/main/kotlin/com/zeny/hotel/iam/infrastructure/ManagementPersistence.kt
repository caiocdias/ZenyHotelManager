package com.zeny.hotel.iam.infrastructure

import com.zeny.hotel.iam.application.RoleDirectory
import com.zeny.hotel.iam.application.RoleView
import com.zeny.hotel.iam.application.UserDirectory
import com.zeny.hotel.iam.application.UserRoleView
import com.zeny.hotel.iam.application.UserView
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.iam.domain.limitValueOf
import com.zeny.hotel.shared.GrantedPermission
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.UserId
import org.postgresql.util.PSQLException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Violação de unicidade do PostgreSQL (`23505`) numa restrição ou índice único conhecido: vira
 * `false` para o caso de uso responder `409`, em vez de um `500`. A restrição é reconhecida pelo
 * campo `constraint` do erro do servidor (`PSQLException.serverErrorMessage`), procurado na cadeia
 * de causas, nunca pelo texto da mensagem. Qualquer outra violação segue.
 */
internal object UniqueConstraints {
    private const val UNIQUE_VIOLATION = "23505"

    fun unlessViolated(
        constraint: String,
        write: () -> Unit,
    ): Boolean =
        try {
            write()
            true
        } catch (e: DataIntegrityViolationException) {
            if (!violates(e, constraint)) throw e
            false
        }

    private fun violates(
        e: Throwable,
        constraint: String,
    ): Boolean =
        generateSequence(e) { it.cause }
            .filterIsInstance<PSQLException>()
            .any { it.sqlState == UNIQUE_VIOLATION && it.serverErrorMessage?.constraint == constraint }
}

/** Usuários para a gestão (`JdbcClient`, na transação corrente). */
@Component
class JdbcUserDirectory(
    private val jdbc: JdbcClient,
) : UserDirectory {
    override fun find(id: UserId): UserView? = withRoles(query("u.id = :id", mapOf("id" to id.value), 1)).singleOrNull()

    override fun page(
        afterLogin: String?,
        limit: Int,
    ): List<UserView> =
        withRoles(
            query(
                "CAST(:after AS VARCHAR) IS NULL OR u.login COLLATE \"C\" > CAST(:after AS VARCHAR)",
                mapOf("after" to afterLogin),
                limit,
            ),
        )

    private fun query(
        condition: String,
        params: Map<String, Any?>,
        limit: Int,
    ): List<UserRow> =
        jdbc
            .sql(
                """
                SELECT u.id, u.name, u.login, u.active, u.pin_hash IS NOT NULL AS has_pin,
                       u.password_change_required, u.locked_until, u.created_at, u.updated_at, u.version
                FROM iam.user_account u
                WHERE $condition
                ORDER BY u.login COLLATE "C"
                LIMIT :limit
                """.trimIndent(),
            ).params(params)
            .param("limit", limit)
            .query { rs, _ -> UserRow.of(rs) }
            .list()

    private fun withRoles(rows: List<UserRow>): List<UserView> {
        if (rows.isEmpty()) return emptyList()
        val roles =
            jdbc
                .sql(
                    """
                    SELECT ur.user_id, r.id, r.name, r.active, r.system_role
                    FROM iam.user_role ur JOIN iam.role r ON r.id = ur.role_id
                    WHERE ur.user_id IN (:ids)
                    ORDER BY r.name, r.id
                    """.trimIndent(),
                ).param("ids", rows.map { it.id })
                .query { rs, _ ->
                    rs.getObject("user_id", UUID::class.java) to
                        (
                            UserRoleView(
                                RoleId(rs.getObject("id", UUID::class.java)),
                                rs.getString("name"),
                                rs.getBoolean("active"),
                            ) to rs.getBoolean("system_role")
                        )
                }.list()
                .groupBy({ it.first }, { it.second })
        return rows.map { row ->
            val assigned = roles[row.id].orEmpty()
            row.toView(roles = assigned.map { it.first }, administrator = assigned.any { it.second })
        }
    }

    private data class UserRow(
        val id: UUID,
        val name: String,
        val login: String,
        val active: Boolean,
        val hasPin: Boolean,
        val passwordChangeRequired: Boolean,
        val lockedUntil: Instant?,
        val createdAt: Instant,
        val updatedAt: Instant,
        val version: Long,
    ) {
        fun toView(
            roles: List<UserRoleView>,
            administrator: Boolean,
        ) = UserView(
            id = UserId(id),
            name = name,
            login = login,
            active = active,
            administrator = administrator,
            hasPin = hasPin,
            passwordChangeRequired = passwordChangeRequired,
            lockedUntil = lockedUntil,
            roles = roles,
            createdAt = createdAt,
            updatedAt = updatedAt,
            version = version,
        )

        companion object {
            fun of(rs: ResultSet) =
                UserRow(
                    id = rs.getObject("id", UUID::class.java),
                    name = rs.getString("name"),
                    login = rs.getString("login"),
                    active = rs.getBoolean("active"),
                    hasPin = rs.getBoolean("has_pin"),
                    passwordChangeRequired = rs.getBoolean("password_change_required"),
                    lockedUntil = rs.instantOrNull("locked_until"),
                    createdAt = rs.instant("created_at"),
                    updatedAt = rs.instant("updated_at"),
                    version = rs.getLong("version"),
                )
        }
    }
}

/** Roles para a gestão (`JdbcClient`, na transação corrente). Só as concessões de permissões vigentes. */
@Component
class JdbcRoleDirectory(
    private val jdbc: JdbcClient,
) : RoleDirectory {
    override fun find(id: RoleId): RoleView? = withGrants(query("id = :id", mapOf("id" to id.value), 1)).singleOrNull()

    override fun page(
        afterId: UUID?,
        limit: Int,
    ): List<RoleView> =
        withGrants(
            query("CAST(:after AS uuid) IS NULL OR id > CAST(:after AS uuid)", mapOf("after" to afterId), limit),
        )

    private fun query(
        condition: String,
        params: Map<String, Any?>,
        limit: Int,
    ): List<RoleView> =
        jdbc
            .sql(
                """
                SELECT id, name, description, active, template_key, system_role, customized, created_at, updated_at,
                       version
                FROM iam.role
                WHERE $condition
                ORDER BY id
                LIMIT :limit
                """.trimIndent(),
            ).params(params)
            .param("limit", limit)
            .query { rs, _ ->
                RoleView(
                    id = RoleId(rs.getObject("id", UUID::class.java)),
                    name = rs.getString("name"),
                    description = rs.getString("description"),
                    active = rs.getBoolean("active"),
                    template = rs.getString("template_key")?.let(RoleTemplate::valueOf),
                    systemRole = rs.getBoolean("system_role"),
                    customized = rs.getBoolean("customized"),
                    grants = emptyList(),
                    createdAt = rs.instant("created_at"),
                    updatedAt = rs.instant("updated_at"),
                    version = rs.getLong("version"),
                )
            }.list()

    private fun withGrants(roles: List<RoleView>): List<RoleView> {
        if (roles.isEmpty()) return emptyList()
        val rows =
            jdbc
                .sql(
                    """
                    SELECT g.role_id, g.id AS grant_id, g.permission_code, g.scope, l.limit_key, l.unlimited, l.max_value
                    FROM iam.role_grant g
                    JOIN iam.permission p ON p.code = g.permission_code AND p.obsolete_at IS NULL
                    LEFT JOIN iam.role_grant_limit l ON l.grant_id = g.id
                    WHERE g.role_id IN (:ids)
                    ORDER BY g.permission_code COLLATE "C", l.limit_key COLLATE "C"
                    """.trimIndent(),
                ).param("ids", roles.map { it.id.value })
                .query { rs, _ -> GrantRow.of(rs) }
                .list()
        val grantsByRole =
            rows.groupBy { it.roleId }.mapValues { (_, roleRows) ->
                roleRows.groupBy { it.grantId }.values.map { parts ->
                    GrantedPermission(
                        parts.first().permission,
                        parts.first().scope,
                        parts.mapNotNull { it.limit }.toMap(),
                    )
                }
            }
        return roles.map { it.copy(grants = grantsByRole[it.id.value].orEmpty()) }
    }

    private class GrantRow(
        val roleId: UUID,
        val grantId: UUID,
        val permission: PermissionCode,
        val scope: PermissionScope,
        val limit: Pair<String, LimitValue>?,
    ) {
        companion object {
            fun of(rs: ResultSet) =
                GrantRow(
                    roleId = rs.getObject("role_id", UUID::class.java),
                    grantId = rs.getObject("grant_id", UUID::class.java),
                    permission = PermissionCode(rs.getString("permission_code")),
                    scope = PermissionScope.valueOf(rs.getString("scope")),
                    limit =
                        rs.getString("limit_key")?.let { key ->
                            key to limitValueOf(rs.getBoolean("unlimited"), rs.getBigDecimal("max_value"))
                        },
                )
        }
    }
}

private fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()

private fun ResultSet.instantOrNull(column: String): Instant? =
    getObject(column, OffsetDateTime::class.java)?.toInstant()
