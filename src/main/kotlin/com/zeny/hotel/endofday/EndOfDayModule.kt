package com.zeny.hotel.endofday

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `endofday` (ARCHITECTURE §3.1; M6 E10): o motor do fechamento do dia, que roda as etapas de
 * negócio de uma data operacional em ordem, uma execução por data (RN-21), cada etapa idempotente, com acompanhamento
 * das execuções, etapas e pendências e a retomada manual. Depende de `shared` (o kernel e a porta de autorização), de
 * `hotel` (a data operacional, pela `HotelApi`) e de `notifications` (o aviso das pendências, que o `endofday` chama:
 * `notifications` nunca escuta evento de negócio); nunca de `files`, e só usa a API pública da raiz de cada módulo.
 * Só a etapa de avanço da E11 chama `HotelApi.advanceBusinessDate`; "data fechada" deriva da execução `COMPLETED` da
 * data.
 */
@PackageInfo
@ApplicationModule(displayName = "End of day", allowedDependencies = ["shared", "hotel", "notifications"])
class EndOfDayModule
