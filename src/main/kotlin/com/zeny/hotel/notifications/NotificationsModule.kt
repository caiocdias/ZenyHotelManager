package com.zeny.hotel.notifications

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `notifications` (NOT, RF-NOT-01 e 02, SPEC §8.2.18, ARCHITECTURE §3.1 e §12, M2 E09):
 * notificações internas para funcionários, no painel e no tópico pessoal `notifications` do canal de tempo
 * real, preferências por usuário e tipo, dispositivos de push vinculados à sessão e envio por FCM HTTP v1 atrás
 * de uma porta, assíncrono pelo outbox. Serviço de plataforma: qualquer módulo de negócio chama a API pública
 * ([NotificationsApi]); o `notifications` nunca escuta eventos de módulos de negócio.
 *
 * Depende de `shared` e, só pela API pública, de `iam` (H9 do M2: destinatários por permissão e sessões
 * ativas). A raiz do pacote é a API pública: [NotificationsApi], o catálogo de tipos ([NotificationType],
 * [ModuleNotificationTypes], [NotificationsTypes]), os ids, o tópico ([NotificationsRealtime]) e os códigos de
 * erro ([NotificationsErrorCodes]).
 */
@PackageInfo
@ApplicationModule(displayName = "Notifications", allowedDependencies = ["shared", "iam"])
class NotificationsModule
