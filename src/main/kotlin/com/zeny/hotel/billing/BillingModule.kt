package com.zeny.hotel.billing

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `billing` (ARCHITECTURE §3.1, SPEC §5.10 e §8.2.7; camada Financeiro): códigos de
 * lançamento, contas (folios) para qualquer dono e lançamentos imutáveis com estorno e ajuste (M3 E04). Depende de
 * `shared`, de `hotel` (a data operacional, RN-01) e de `guests` (a empresa dona ou pagadora, só pela `GuestsApi`);
 * `payments` entra na E08 e `notifications` na E07. Não conhece reservas (o dono é um [FolioOwner] opaco), não chama
 * o `fiscal` e não importa o `iam`.
 *
 * A raiz do pacote é a API pública: [BillingApi] (abrir conta e lançar, para os módulos de cima), os ids e tipos de
 * valor, os códigos de erro ([BillingErrorCodes]) e as permissões ([BillingPermissions]).
 */
@PackageInfo
@ApplicationModule(displayName = "Billing", allowedDependencies = ["shared", "hotel", "guests"])
class BillingModule
