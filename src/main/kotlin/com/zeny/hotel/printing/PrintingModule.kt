package com.zeny.hotel.printing

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `printing` (ARCHITECTURE §3.1 e §14, decisão D13; M5 E10): os agentes de impressão, as
 * impressoras e a fila de jobs que o agente local consome por SSE ou long-poll. Só depende de `shared` (o kernel, a
 * cifragem do conteúdo e a credencial de serviço); nunca de `iam`, `notifications` nem de tabela de outro schema, e
 * nunca escuta eventos de negócio: os módulos donos dos documentos chamam a `PrintingApi`.
 */
@PackageInfo
@ApplicationModule(displayName = "Printing", allowedDependencies = ["shared"])
class PrintingModule
