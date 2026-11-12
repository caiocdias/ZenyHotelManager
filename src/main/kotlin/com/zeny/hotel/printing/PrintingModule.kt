package com.zeny.hotel.printing

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `printing` (ARCHITECTURE §3.1 e §14, decisão D13; M5 E10 e E11): os agentes de impressão, as
 * impressoras, a fila de jobs que o agente local consome por SSE ou long-poll e a renderização dos documentos (PDF e
 * ESC/POS). Depende de `shared` (o kernel, a cifragem do conteúdo e a credencial de serviço), de `hotel` (o perfil do
 * cabeçalho dos documentos) e de `files` (o logotipo); nunca de `iam`, `notifications` nem de tabela de outro schema, e
 * nunca escuta eventos de negócio: os módulos donos dos documentos chamam a `PrintingApi`.
 */
@PackageInfo
@ApplicationModule(displayName = "Printing", allowedDependencies = ["shared", "hotel", "files"])
class PrintingModule
