package com.zeny.hotel.shared

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `shared`, o kernel compartilhado (ARCHITECTURE §3.1). Faz o papel
 * do `package-info.java`, que não existe em Kotlin. `shared` não depende de nenhum outro
 * módulo e é declarado módulo compartilhado em `@Modulithic` na aplicação.
 *
 * A raiz do pacote é a API pública: `Money`, `Percent`, `TypedId`/`UuidV7`, `DocumentNumber`,
 * `Cpf`, `Cnpj`, `Address`, `FieldCipher` e os conversores JPA desses tipos; as convenções de
 * API: `ApiV1Controller`, a hierarquia de `DomainException` com `ErrorCode`, a paginação por
 * cursor (`PageParams`, `CursorPage`, `PageCursor`), `ETags` e `@Idempotent`; e o contexto de
 * segurança: `UserId`, `DeviceSessionId`, `Actor` e a porta `CurrentActor`, a declaração do
 * catálogo de permissões (`ModulePermissions`, `PermissionDefinition`, `PermissionCode`,
 * `PermissionScope`, limites e `RoleTemplate`) e a porta `Authorization` (com
 * `GrantedPermission`, `OwnedResource` e `ScopeFilter`), a porta `DeviceSessionStatus` e o evento
 * `DeviceSessionsRevoked`, implementados por `iam`; e o canal de tempo real (`RealtimeTopic`,
 * `RealtimeMessage`, porta `RealtimePublisher`). As regras da idempotência e o canal de tempo real
 * (broadcaster, buffer e inscrição) ficam em `application`; as implementações (cifragem AES-GCM,
 * gerador de números por sequence, relógio, outbox, chaves de idempotência, jobs, métrica das
 * conexões de tempo real) em `infrastructure`; as da API
 * (erros RFC 9457, segurança com access token JWT, ator no MDC dos logs, filtro de idempotência,
 * OpenAPI, `GET /api/v1/meta/version`, `GET /api/v1/stream`) em `web`.
 */
@PackageInfo
@ApplicationModule(displayName = "Shared kernel", allowedDependencies = [])
class SharedModule
