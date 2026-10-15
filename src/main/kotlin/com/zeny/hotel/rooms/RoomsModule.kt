package com.zeny.hotel.rooms

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `rooms` (ARCHITECTURE §3.1, SPEC §5.4 e §8.2.3; camada Cadastros): tipos de
 * UH, características, UHs e áreas comuns. Pode depender de `shared` e `hotel`; não conhece reservas
 * nem governança.
 *
 * A raiz do pacote é a API pública: [RoomsApi] (leituras de UH, tipo e área para os outros módulos),
 * os ids e tipos de valor, os códigos de erro ([RoomsErrorCodes]) e as permissões ([RoomsPermissions]).
 */
@PackageInfo
@ApplicationModule(displayName = "Rooms and areas", allowedDependencies = ["shared", "hotel"])
class RoomsModule
