package com.zeny.hotel.fnrh

import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.TemplateGrant

/**
 * Permissões do módulo `fnrh` (RF-ACS-02, SPEC §5.9, H20 do roadmap do M5, D-E07-6). A integração não tem dono, então
 * só aceita o escopo `ALL`. Nenhuma é sensível.
 *
 * [SETTINGS_MANAGE] cuida da configuração, dos CPFs por usuário e da atualização dos domínios; é exclusiva do
 * Administrador, que recebe todas as permissões, e por isso não tem modelo de role. [DOMAINS_READ] deixa o cliente
 * oferecer os códigos oficiais no check-in e [SUBMISSIONS_READ] mostra o status da integração (Gerente e
 * Recepcionista).
 */
object FnrhPermissions : ModulePermissions {
    override val module = "fnrh"

    /** Configurar a integração, os CPFs por usuário e atualizar os domínios (só o Administrador). */
    val SETTINGS_MANAGE = PermissionCode("fnrh.settings.manage")

    /** Consultar os domínios oficiais da FNRH. */
    val DOMAINS_READ = PermissionCode("fnrh.domains.read")

    /** Consultar o status da integração. */
    val SUBMISSIONS_READ = PermissionCode("fnrh.submissions.read")

    private val frontOffice =
        listOf(RoleTemplate.MANAGER, RoleTemplate.RECEPTIONIST).associateWith { TemplateGrant(ALL) }

    override val permissions =
        listOf(
            PermissionDefinition(
                SETTINGS_MANAGE,
                "Configurar a integração com a FNRH, os CPFs por usuário e atualizar os domínios",
                setOf(ALL),
            ),
            PermissionDefinition(
                DOMAINS_READ,
                "Consultar os domínios oficiais da FNRH",
                setOf(ALL),
                templates = frontOffice,
            ),
            PermissionDefinition(
                SUBMISSIONS_READ,
                "Consultar o status da integração com a FNRH",
                setOf(ALL),
                templates = frontOffice,
            ),
        )
}
