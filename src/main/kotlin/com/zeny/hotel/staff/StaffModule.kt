package com.zeny.hotel.staff

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `staff` (ARCHITECTURE §3.1, SPEC §5.3): setores, colaboradores, turnos,
 * padrões de escala e a escala por data. Depende de `shared`, de `iam` (só da [IamApi][com.zeny.hotel.iam.IamApi],
 * para validar o vínculo colaborador ↔ usuário, H7) e de `hotel` (a data operacional, que é o "hoje"
 * da escala).
 *
 * A raiz do pacote é a API pública: [StaffApi] (a escala de uma data por setor, os setores e o
 * colaborador de um usuário), os ids e tipos de valor, os códigos de erro ([StaffErrorCodes]) e as
 * permissões ([StaffPermissions]).
 */
@PackageInfo
@ApplicationModule(displayName = "Staff and schedule", allowedDependencies = ["shared", "iam", "hotel"])
class StaffModule
