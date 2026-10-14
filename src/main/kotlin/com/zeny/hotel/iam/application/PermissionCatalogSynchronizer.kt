package com.zeny.hotel.iam.application

import com.zeny.hotel.iam.IamPermissions
import com.zeny.hotel.iam.domain.PermissionCatalog
import com.zeny.hotel.iam.domain.Role
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.RoleTemplate
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/** O que uma sincronização do catálogo mudou. */
data class CatalogSyncResult(
    val inserted: Set<PermissionCode>,
    val updated: Set<PermissionCode>,
    val obsoleted: Set<PermissionCode>,
)

/**
 * Sincroniza no boot o catálogo declarado em código pelos módulos ([ModulePermissions]) com a
 * tabela de permissões (ARCHITECTURE §8.2): insere as novas, atualiza as que mudaram (inclusive
 * as que voltaram ao código) e marca como obsoletas as que saíram, que deixam de conceder
 * acesso. Na mesma transação, cria os modelos de role da SPEC §3.1 que faltam e aplica as
 * sugestões de cada permissão a eles (H5): a role de sistema Administrador recebe todas as
 * permissões, e cada outro modelo só ganha concessões novas enquanto o administrador não o
 * editar. Toda mudança troca a versão das autorizações (triggers do banco); a trava das escritas
 * de autorização ([AuthorizationLock]) vem logo depois da trava da sincronização.
 */
@Service
class PermissionCatalogSynchronizer(
    private val modules: List<ModulePermissions>,
    private val store: PermissionStore,
    private val roles: Roles,
    private val lock: AuthorizationLock,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Audited(SYNCHRONIZE)
    @Transactional
    fun synchronize(): CatalogSyncResult = synchronize(modules)

    /**
     * Sincroniza com as declarações dadas (as do contexto, em [synchronize] sem argumento). Vai
     * para a trilha como o sistema: cada permissão nova, alterada ou obsoleta e cada modelo de
     * role que muda; sem mudança, nada.
     */
    @Audited(SYNCHRONIZE)
    @Transactional
    fun synchronize(declared: Collection<ModulePermissions>): CatalogSyncResult {
        val catalog = PermissionCatalog.of(declared)
        store.lockForSynchronization()
        lock.acquire()
        val now = clock.instant()
        audit.noChanges()
        val stored = store.findAll().associateBy { it.definition.code }
        val inserted = mutableSetOf<PermissionCode>()
        val updated = mutableSetOf<PermissionCode>()
        catalog.definitions.values.forEach { definition ->
            val current = stored[definition.code]
            when {
                current == null -> {
                    store.insert(definition, now).also { inserted += definition.code }
                    audit.record(IamAudit.PERMISSION, definition.code, null, definition.auditState())
                }

                current.obsolete || !current.definition.sameStoredData(definition) -> {
                    store.update(definition, now).also { updated += definition.code }
                    audit.record(
                        IamAudit.PERMISSION,
                        definition.code,
                        current.definition.auditState(current.obsolete),
                        definition.auditState(),
                    )
                }
            }
        }
        val obsoleted =
            stored.values
                .filter { !it.obsolete && it.definition.code !in catalog }
                .map { it.definition.code }
                .onEach { code ->
                    store.markObsolete(code, now)
                    val definition = stored.getValue(code).definition
                    audit.record(
                        IamAudit.PERMISSION,
                        code,
                        definition.auditState(),
                        definition.auditState(obsolete = true),
                    )
                }.toSet()
        seedTemplates(catalog, now)
        val result = CatalogSyncResult(inserted, updated, obsoleted)
        log.info(
            "Catálogo de permissões sincronizado: {} vigentes, {} novas, {} atualizadas, {} obsoletas",
            catalog.definitions.size,
            inserted.size,
            updated.size,
            obsoleted.size,
        )
        return result
    }

    private fun seedTemplates(
        catalog: PermissionCatalog,
        now: Instant,
    ) {
        RoleTemplate.entries.forEach { template ->
            val role =
                roles.findByTemplate(template)?.also { existing ->
                    audit.track(IamAudit.ROLE, existing.id) { existing.auditState() }
                } ?: Role.fromTemplate(template, now).also {
                    roles.add(it)
                    audit.created(IamAudit.ROLE, it.id) { it.auditState() }
                    log.info("Modelo de role {} criado", template)
                }
            catalog.definitions.values.forEach { definition ->
                if (role.systemRole) {
                    role.ensureFullGrant(definition, now)
                } else {
                    definition.templates[template]?.let { role.applyTemplateGrant(definition, it, now) }
                }
            }
        }
    }

    private companion object {
        const val SYNCHRONIZE = "iam.permission-catalog.synchronize"
    }

    /** Compara só o que a tabela guarda (as sugestões aos modelos ficam no código). */
    private fun PermissionDefinition.sameStoredData(other: PermissionDefinition): Boolean =
        copy(templates = emptyMap()) == other.copy(templates = emptyMap())
}

/** Publica as permissões do próprio `iam` no catálogo. */
@Configuration(proxyBeanMethods = false)
class IamPermissionsConfiguration {
    @Bean
    fun iamPermissions(): ModulePermissions = IamPermissions
}
