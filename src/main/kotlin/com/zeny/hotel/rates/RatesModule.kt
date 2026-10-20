package com.zeny.hotel.rates

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `rates` (ARCHITECTURE §3.1, SPEC §5.6 e §8.2.5; camada Hospedagem): planos tarifários,
 * políticas de cancelamento e de garantia e pacotes (M4 E01); preços, derivação e restrições nas etapas seguintes.
 * Pode depender de `shared`, `rooms` (tipos de UH, só pela `RoomsApi`) e `billing` (códigos de lançamento, só pela
 * `BillingApi`); não conhece reservas nem `iam`.
 *
 * A raiz do pacote é a API pública: [RatesApi], os ids e tipos de valor, os códigos de erro ([RatesErrorCodes]) e as
 * permissões ([RatesPermissions]).
 */
@PackageInfo
@ApplicationModule(displayName = "Rates and policies", allowedDependencies = ["shared", "rooms", "billing"])
class RatesModule
