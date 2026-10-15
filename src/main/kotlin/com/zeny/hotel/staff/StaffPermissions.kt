package com.zeny.hotel.staff

import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.TemplateGrant

/**
 * Permissões do módulo `staff` (RF-ACS-02, SPEC §5.3). Nenhuma está entre as ações sensíveis da
 * RF-ACS-08. A escala aceita o escopo `OWN` (RF-ACS-05): o colaborador vinculado ao usuário (H7) vê
 * só a própria escala e os próprios padrões.
 *
 * Sugestões aos modelos (H5): o Gerente recebe todas ("toda a operação"); a Governanta consulta
 * cadastros e a escala de todos (distribuição, RF-PES-04); Camareira, Manutenção e Garçom, de escopo
 * típico "próprio" (SPEC §3.1), veem a própria escala.
 */
object StaffPermissions : ModulePermissions {
    override val module = "staff"

    /** Consultar setores, colaboradores e turnos. */
    val EMPLOYEES_READ = PermissionCode("staff.employees.read")

    /** Cadastrar e editar setores, colaboradores (com o vínculo a usuário) e turnos; desativar colaboradores. */
    val EMPLOYEES_MANAGE = PermissionCode("staff.employees.manage")

    /** Consultar a escala e os padrões de escala. */
    val SCHEDULE_READ = PermissionCode("staff.schedule.read")

    /** Cadastrar padrões, gerar a escala e lançar atribuições e exceções. */
    val SCHEDULE_MANAGE = PermissionCode("staff.schedule.manage")

    private val manager = RoleTemplate.MANAGER to TemplateGrant(ALL)
    private val housekeeper = RoleTemplate.HOUSEKEEPER to TemplateGrant(ALL)
    private val ownSchedule =
        listOf(RoleTemplate.ROOM_ATTENDANT, RoleTemplate.MAINTENANCE, RoleTemplate.WAITER)
            .associateWith { TemplateGrant(OWN) }

    override val permissions =
        listOf(
            PermissionDefinition(
                EMPLOYEES_READ,
                "Consultar setores, colaboradores e turnos",
                setOf(ALL),
                templates = mapOf(manager, housekeeper),
            ),
            PermissionDefinition(
                EMPLOYEES_MANAGE,
                "Cadastrar e editar setores, colaboradores e turnos; vincular usuário e desativar colaborador",
                setOf(ALL),
                templates = mapOf(manager),
            ),
            PermissionDefinition(
                SCHEDULE_READ,
                "Consultar a escala e os padrões de escala",
                setOf(OWN, ALL),
                templates = mapOf(manager, housekeeper) + ownSchedule,
            ),
            PermissionDefinition(
                SCHEDULE_MANAGE,
                "Cadastrar padrões de escala, gerar a escala e lançar atribuições e exceções",
                setOf(ALL),
                templates = mapOf(manager),
            ),
        )
}
