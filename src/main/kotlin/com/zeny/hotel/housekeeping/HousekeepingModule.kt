package com.zeny.hotel.housekeeping

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `housekeeping` (ARCHITECTURE §3.1, camada Operação; SPEC §5.14 e §8.2.10): a governança. Na M6
 * E01, a configuração dos tipos de limpeza (RF-GOV-01), com os créditos por tipo de UH, e as faxinas programadas
 * (RF-GOV-10); na E02, as tarefas de governança (RF-GOV-02). Depende de `shared` (o kernel e a porta de autorização),
 * de `hotel` (a data operacional, só pela `HotelApi`), de `rooms` (os tipos de UH dos créditos e os locais das faxinas
 * e das tarefas, só pela `RoomsApi`) e de `checklists` (o vínculo ao modelo, só pela `ChecklistsApi`), sempre dentro
 * da linha da §3.1.
 *
 * A raiz do pacote é a API pública: os ids, a chave estável dos tipos da carga inicial ([CleaningTypeKey]), o status e
 * a origem das tarefas ([TaskStatus], [TaskSource]), os códigos de erro ([HousekeepingErrorCodes]) e as permissões
 * ([HousekeepingPermissions]).
 */
@PackageInfo
@ApplicationModule(displayName = "Housekeeping", allowedDependencies = ["shared", "hotel", "rooms", "checklists"])
class HousekeepingModule
