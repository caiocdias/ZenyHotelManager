package com.zeny.hotel.maintenance

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `maintenance` (ARCHITECTURE §3.1, camada Operação; SPEC §5.15): a manutenção. Na M6 E07, as
 * ordens de serviço corretivas (RF-MAN-01 e RF-MAN-02), com o bloqueio da UH em OOO/OOS (RF-MAN-03) nas fatias
 * seguintes. Depende de `shared` (o kernel e a porta de autorização), de `hotel` (a data operacional do número
 * legível, só pela `HotelApi`), de `rooms` (a UH e a área da ordem, só pela `RoomsApi`) e de `staff` (o responsável e o
 * dono no escopo `OWN`, só pela `StaffApi`), de `files` (os anexos da ordem, só pela `FilesApi` e pelas finalidades
 * de [MaintenanceFilePurposes]) e de `notifications` (o aviso da ordem urgente, só pela `NotificationsApi`), dentro da
 * linha da §3.1.
 *
 * A raiz do pacote é a API pública: os ids, os enums, os códigos de erro ([MaintenanceErrorCodes]) e as permissões
 * ([MaintenancePermissions]).
 */
@PackageInfo
@ApplicationModule(
    displayName = "Maintenance",
    allowedDependencies = ["shared", "hotel", "rooms", "staff", "files", "notifications"],
)
class MaintenanceModule
