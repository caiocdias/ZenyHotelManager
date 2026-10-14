package com.zeny.hotel.iam

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `iam` (ARCHITECTURE §3.1): usuários, sessões por dispositivo, roles e o
 * catálogo de permissões e, nos próximos passos do M1, aprovações. Depende só de `shared`.
 *
 * A raiz do pacote é a API pública: os códigos de erro ([IamErrorCodes]) e as permissões do
 * módulo ([IamPermissions]). O ator corrente e a autorização chegam aos outros módulos pelas
 * portas `CurrentActor` e `Authorization` do `shared`, que `iam` implementa; nenhum módulo além
 * de `staff` importa `iam` diretamente.
 */
@PackageInfo
@ApplicationModule(displayName = "Identity and access", allowedDependencies = ["shared"])
class IamModule
