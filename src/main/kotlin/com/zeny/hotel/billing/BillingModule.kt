package com.zeny.hotel.billing

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `billing` (ARCHITECTURE §3.1, SPEC §5.10 e §8.2.7; camada Financeiro): códigos de
 * lançamento, contas (folios) para qualquer dono e lançamentos imutáveis com estorno e ajuste (M3 E04), roteamento,
 * transferência e divisão (E05), taxa de serviço (E06) e pontos e turnos de caixa (E07). Depende de `shared`, de
 * `hotel` (a data operacional, RN-01), de `guests` (a empresa dona ou pagadora, só pela `GuestsApi`) e, desde a E07,
 * de `notifications` (a diferença no fechamento do turno, H14; só a `NotificationsApi` e o catálogo de tipos);
 * desde a E08, de `payments` (a intenção de pagamento de todo recebimento, H1; só a `PaymentsApi` e o evento
 * `PaymentSucceeded`). Não conhece reservas (o dono é um [FolioOwner] opaco), não chama o `fiscal` e não importa
 * o `iam`.
 *
 * A raiz do pacote é a API pública: [BillingApi] (abrir conta, lançar e o turno de caixa aberto, para os módulos de
 * cima), os ids e tipos de valor, os códigos de erro ([BillingErrorCodes]), as permissões ([BillingPermissions]) e os
 * tipos de notificação ([BillingNotificationTypes]).
 */
@PackageInfo
@ApplicationModule(
    displayName = "Billing",
    allowedDependencies = ["shared", "hotel", "guests", "notifications", "payments"],
)
class BillingModule
