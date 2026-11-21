package com.zeny.hotel.endofday

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `endofday` (ARCHITECTURE §3.1; M6 E10): o motor do fechamento do dia, que roda as etapas de
 * negócio de uma data operacional em ordem, uma execução por data (RN-21), cada etapa idempotente, com acompanhamento
 * das execuções, etapas e pendências e a retomada manual. Depende de `shared` (o kernel e a porta de autorização), de
 * `hotel` (a data operacional, pela `HotelApi`) e de `notifications` (o aviso das pendências, que o `endofday` chama:
 * `notifications` nunca escuta evento de negócio); e, com as etapas reais do fechamento (M6 E11), de `reservations`
 * (no-show, saídas pendentes, liberação de bloqueios e vencimento de provisórias), `billing` (diárias, pacotes e taxa
 * de turismo lançadas pela API de lançamento, e as pré-autorizações de cartão) e `rooms` (UHs ocupadas ficam sujas).
 * E, com as etapas do snapshot, do arquivo e das pendências (M6 E13), de `reporting`: só a raiz dele (`ReportingApi`)
 * e só o snapshot diário, o arquivo dos PDFs do fechamento e as pendências da RF-FEC-04. Nunca de `files`, `rates` nem
 * `frontdesk`, e só usa a API pública da raiz de cada módulo. Só a etapa de avanço da E11 chama
 * `HotelApi.advanceBusinessDate`; "data fechada" deriva da execução `COMPLETED` da data.
 */
@PackageInfo
@ApplicationModule(
    displayName = "End of day",
    allowedDependencies = ["shared", "hotel", "notifications", "reservations", "billing", "rooms", "reporting"],
)
class EndOfDayModule
