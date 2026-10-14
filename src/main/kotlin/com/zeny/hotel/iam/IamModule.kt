package com.zeny.hotel.iam

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `iam` (ARCHITECTURE §3.1): usuários, sessões por dispositivo e, nos
 * próximos passos do M1, roles, catálogo de permissões e aprovações. Depende só de `shared`.
 *
 * A raiz do pacote é a API pública: os códigos de erro ([IamErrorCodes]). O ator corrente é
 * exposto aos outros módulos pela porta `CurrentActor` do `shared`, que `iam` implementa; nenhum
 * módulo além de `staff` importa `iam` diretamente.
 */
@PackageInfo
@ApplicationModule(displayName = "Identity and access", allowedDependencies = ["shared"])
class IamModule
