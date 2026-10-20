package com.zeny.hotel.payments

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `payments` (ARCHITECTURE §3.1 e §12, SPEC §5.11 e §8.2.8; camada Financeiro): a intenção de
 * pagamento neutra ([PaymentsApi]), a porta dos provedores e o provedor `MANUAL` (M3 E08, H1). Depende só de
 * `shared` (o `notifications`, para os alertas de falha de integração, entra no M7). **Nunca chama o `billing`**: quem
 * inicia a intenção (o `billing`) guarda o próprio pagamento e escuta [PaymentSucceeded] pelo outbox.
 */
@PackageInfo
@ApplicationModule(displayName = "Payments", allowedDependencies = ["shared"])
class PaymentsModule
