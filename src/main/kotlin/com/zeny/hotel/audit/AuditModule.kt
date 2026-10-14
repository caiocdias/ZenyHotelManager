package com.zeny.hotel.audit

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `audit` (ARCHITECTURE §3.1): a trilha de auditoria *append-only*
 * (RF-ACS-11, RNF-11). Depende só de `shared`: os casos de uso de todos os módulos gravam pela
 * porta `AuditTrail` do `shared`, que este módulo implementa, a partir do aspecto de `@Audited`;
 * nenhum módulo importa `audit`.
 *
 * A raiz do pacote é a API pública: as permissões do módulo ([AuditPermissions]).
 */
@PackageInfo
@ApplicationModule(displayName = "Audit trail", allowedDependencies = ["shared"])
class AuditModule
