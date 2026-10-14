package com.zeny.hotel.shared

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `shared`, o kernel compartilhado (ARCHITECTURE §3.1). Faz o papel
 * do `package-info.java`, que não existe em Kotlin. `shared` não depende de nenhum outro
 * módulo e é declarado módulo compartilhado em `@Modulithic` na aplicação.
 *
 * A raiz do pacote é a API pública: `Money`, `Percent`, `TypedId`/`UuidV7`, `DocumentNumber`,
 * `Cpf`, `Cnpj`, `Address`, `FieldCipher` e os conversores JPA desses tipos, além das convenções
 * de API: `ApiV1Controller`, a hierarquia de `DomainException` com `ErrorCode`, a paginação por
 * cursor (`PageParams`, `CursorPage`, `PageCursor`), `ETags` e `@Idempotent`. As regras da
 * idempotência ficam em `application`; as implementações (cifragem AES-GCM, gerador de números
 * por sequence, relógio, outbox, chaves de idempotência, jobs) em `infrastructure`; as da API
 * (erros RFC 9457, segurança mínima, filtro de idempotência, OpenAPI, `GET /api/v1/meta/version`)
 * em `web`.
 */
@PackageInfo
@ApplicationModule(displayName = "Shared kernel", allowedDependencies = [])
class SharedModule
