package com.zeny.hotel.shared

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `shared`, o kernel compartilhado (ARCHITECTURE §3.1). Faz o papel
 * do `package-info.java`, que não existe em Kotlin. `shared` não depende de nenhum outro
 * módulo e é declarado módulo compartilhado em `@Modulithic` na aplicação.
 *
 * Os tipos do kernel (dinheiro, IDs, erros…) chegam na etapa E03 do M0.
 */
@PackageInfo
@ApplicationModule(displayName = "Shared kernel", allowedDependencies = [])
class SharedModule
