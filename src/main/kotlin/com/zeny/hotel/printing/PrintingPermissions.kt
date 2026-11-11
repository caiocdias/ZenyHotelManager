package com.zeny.hotel.printing

import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.TemplateGrant

/**
 * Permissões do módulo `printing` (RF-ACS-02, H20 do roadmap do M5, D-E10-7). A impressão não tem dono, então só
 * aceita o escopo `ALL`. Nenhuma é sensível.
 *
 * [AGENTS_MANAGE] cuida de agentes e impressoras e é exclusiva do Administrador, que recebe todas as permissões, e por
 * isso não tem modelo de role. [JOBS_READ] mostra a lista de jobs e as impressoras, para escolher onde imprimir
 * (Gerente e Recepcionista). [JOBS_MANAGE] cria o job manual (só o Gerente).
 */
object PrintingPermissions : ModulePermissions {
    override val module = "printing"

    /** Cadastrar e alterar agentes e impressoras (só o Administrador). */
    val AGENTS_MANAGE = PermissionCode("printing.agents.manage")

    /** Consultar jobs de impressão e as impressoras. */
    val JOBS_READ = PermissionCode("printing.jobs.read")

    /** Criar um job de impressão manual. */
    val JOBS_MANAGE = PermissionCode("printing.jobs.manage")

    override val permissions =
        listOf(
            PermissionDefinition(
                AGENTS_MANAGE,
                "Cadastrar e alterar agentes de impressão e impressoras",
                setOf(ALL),
            ),
            PermissionDefinition(
                JOBS_READ,
                "Consultar os jobs de impressão e as impressoras",
                setOf(ALL),
                templates =
                    listOf(RoleTemplate.MANAGER, RoleTemplate.RECEPTIONIST).associateWith { TemplateGrant(ALL) },
            ),
            PermissionDefinition(
                JOBS_MANAGE,
                "Criar um job de impressão manual",
                setOf(ALL),
                templates = mapOf(RoleTemplate.MANAGER to TemplateGrant(ALL)),
            ),
        )
}
