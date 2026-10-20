package com.zeny.hotel.rates

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `rates` (ARCHITECTURE §3.1, SPEC §5.6 e §8.2.5; camada Hospedagem): planos tarifários,
 * políticas de cancelamento e de garantia e pacotes (M4 E01); preços por data e ocupação, planos derivados, temporadas
 * e edição em massa (M4 E02); restrições nas etapas seguintes. Pode depender de `shared`, `rooms` (tipos de UH, só pela
 * `RoomsApi`), `billing` (códigos de lançamento, só pela `BillingApi`) e `hotel` (faixas etárias de criança e data
 * operacional, só pela `HotelApi`; desde a E02); não conhece reservas nem `iam`.
 *
 * A raiz do pacote é a API pública: [RatesApi], os ids e tipos de valor, os códigos de erro ([RatesErrorCodes]), as
 * permissões ([RatesPermissions]) e os eventos ([RatesChanged]).
 */
@PackageInfo
@ApplicationModule(displayName = "Rates and policies", allowedDependencies = ["shared", "rooms", "billing", "hotel"])
class RatesModule
