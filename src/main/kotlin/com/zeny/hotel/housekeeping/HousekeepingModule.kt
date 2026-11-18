package com.zeny.hotel.housekeeping

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `housekeeping` (ARCHITECTURE §3.1, camada Operação; SPEC §5.14 e §8.2.10): a governança. Na M6
 * E01, a configuração dos tipos de limpeza (RF-GOV-01), com os créditos por tipo de UH. Depende de `shared` (o kernel e
 * a porta de autorização), de `rooms` (os tipos de UH dos créditos, só pela `RoomsApi`) e de `checklists` (o vínculo ao
 * modelo, só pela `ChecklistsApi`). A declaração cresce por fatia, sempre dentro da linha da §3.1: `hotel` (a data
 * operacional das faxinas programadas) entra quando for usado.
 *
 * A raiz do pacote é a API pública: os ids, a chave estável dos tipos da carga inicial ([CleaningTypeKey]), os códigos
 * de erro ([HousekeepingErrorCodes]) e as permissões ([HousekeepingPermissions]).
 */
@PackageInfo
@ApplicationModule(displayName = "Housekeeping", allowedDependencies = ["shared", "rooms", "checklists"])
class HousekeepingModule
