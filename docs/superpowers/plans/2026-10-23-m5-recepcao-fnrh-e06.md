# M5 E06 — Recados — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** recados (RF-REC-07, `Trace` da SPEC §8.2.6): lembretes para um setor, vinculados a uma reserva **ou** a uma UH, com vencimento, texto, responsável opcional e resolução (quem e quando), com CRUD por cursor e `ETag`.

**Architecture:** primeiro estado próprio do `frontdesk`: schema `frontdesk` (criado pelo Flyway a partir da pasta), tabela `frontdesk.trace` sem `DELETE` (trigger), migração de `GRANT`. Domínio `Trace` puro (regras de criação, alteração e resolução), porta de persistência JDBC, caso de uso `FrontdeskTraceService` e controller `TracesController`. Aresta nova `frontdesk` → `staff` (setores e o vínculo usuário → colaborador, só pela `StaffApi`).

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1, Flyway, JdbcClient, Testcontainers, Kotest, MockMvc.

**Spec:** `docs/roadmaps/roadmap-m5-recepcao-fnrh.md`, seção "E06 — Recados" (e H20), SPEC RF-REC-07 e §8.2.6 (`Trace` com `department`, `dueOn`, `text`, `resolvedAt`). Referência: notas do brain `ZenyHotelManager - Arquitetura e estrutura de módulos`, `ZenyHotelManager - Schemas, migrações e views de relatório`, `ZenyHotelManager - Convenções de API`, `ZenyHotelManager - Auditoria`, `ZenyHotelManager - Módulos hotel e staff`, `ZenyHotelManager - Segurança, autenticação e autorização`, `ZenyHotelManager - Testes de integração`, e as capturas pendentes da E04 e da E05 em `00 Inbox/`.

## Global Constraints

- Grafo (ARCHITECTURE §3.1): `frontdesk` → `staff` é aresta nova, só pela `StaffApi`, registrada na tabela §3.1, no `allowedDependencies` do `FrontdeskModule` e no teste do `frontdesk` do `ModularityTest`; o `frontdesk` lê reservas e UHs só pela `ReservationsApi`/`RoomsApi`; nunca `iam` nem `fnrh`; nenhuma FK entre schemas.
- Migrações em `src/main/resources/db/migration/frontdesk/`, timestamp posterior a `V2026_10_22_2330` (a última da branch), uma migração de `GRANT` ao `"${app_role}"` no modelo de `reservations/V2026_10_20_2316__grant_reservations_to_app_role.sql`; `MigrationConventionTest` sem objeto fora do schema; exclusão física proibida por trigger (modelo `reservations.refuse_delete`).
- Convenções de API: `@ApiV1Controller`, `@Tag` `frontdesk`, `@Operation(operationId)` único, `POST` 201 com `Location` e `ETag`, `GET /{id}` com `ETag`, `PUT` com `If-Match` (`428`/`412`), lista por cursor (`PageParams`/`CursorPage`), erros RFC 9457 com códigos no `FrontdeskErrorCodes`; referência inexistente `400 UNKNOWN_…` com o campo, desativada `422 …_INACTIVE`. Contrato por `./gradlew updateOpenApi`, só acréscimos; oasdiff sem `ERR`; `ApiSecurityTest` verde.
- RN-13 e H10 do M3: o **texto do recado nunca** vai para a trilha, logs, eventos ou problemas (a trilha leva ids, setor, datas, situação e o tamanho do texto); escritas `@Audited`.
- RN-01: vencimento e filtros em data operacional (`HotelApi.currentBusinessDate()`); instantes pelo `Clock`.
- Permissões sem hífen; um código publicado não muda. Docs, mensagens e commits em pt-BR; KDoc na densidade dos vizinhos; `README.md` (seção "API") atualizado.

## Review Focus

1. **Texto do recado vazando** na trilha, no log de erro ou num problema `400` (o valor rejeitado ecoado): o problema diz o campo e a regra, nunca o texto — teste na Task 2.
2. **Vínculo duplo ou nenhum** (reserva e UH juntas, ou nenhuma): `400 TRACE_INVALID` com o campo — teste na Task 1/2.
3. **Corrida de resolução** (dois usuários resolvendo ao mesmo tempo): só um resolve; o outro `409 TRACE_ALREADY_RESOLVED` (a resolução trava a linha) — teste na Task 2.
4. **Alteração de recado resolvido**: `PUT` num resolvido → `409 TRACE_ALREADY_RESOLVED`, nada muda — teste na Task 2.
5. **Lista sem ordem estável entre páginas** (mesmo vencimento): ordem `(due_on, id)` e cursor dessas duas chaves; percorrer as páginas devolve cada recado uma vez — teste na Task 2.

---

## Decisões do plano (registrar no handoff da E06)

- **D1 — Responsável:** `assigneeUserId` opcional, o id de um usuário **vinculado a um colaborador** (`StaffApi.employeeOfUser(userId) != null`; senão `400 UNKNOWN_ASSIGNEE`, campo `assigneeUserId`); a resposta traz o id e o nome do colaborador. Alternativas descartadas: texto livre (dado pessoal sem vínculo), id de colaborador (exigiria leitura nova na `StaffApi`).
- **D2 — Permissões** (H20 ajustada): `frontdesk.traces.read` (Gerente, Recepcionista, Governanta, Manutenção, Comercial/Eventos), `frontdesk.traces.manage` (criar e alterar; Gerente e Recepcionista) e **`frontdesk.traces.resolve`** (resolver; Gerente, Recepcionista, Governanta, Manutenção, Comercial/Eventos): o setor que cumpre o recado o resolve sem poder editá-lo. Nenhuma sensível; escopo só `ALL`.
- **D3 — Vínculo fixo:** reserva **ou** UH só na criação; o `PUT` altera setor, vencimento, texto e responsável. A reserva precisa existir (`400 UNKNOWN_RESERVATION`, qualquer situação); a UH precisa existir (`400 UNKNOWN_ROOM`) e estar ativa (`422 ROOM_INACTIVE`).
- **D4 — Vencimento:** na criação e quando muda no `PUT`, não antes da data operacional (`400 TRACE_INVALID`, campo `dueOn`).
- **D5 — Situação** calculada: `OPEN` (sem resolução) ou `RESOLVED`; filtro `status` com esses dois valores; `x-extensible-enum` na resposta.
- **D6 — Texto:** obrigatório, aparado, 1 a 500 caracteres (`400 TRACE_INVALID`, campo `text`).

---

### Task 1: Domínio, migração e persistência dos recados

**Files:**
- Create: `src/main/resources/db/migration/frontdesk/V2026_10_23_1200__create_traces.sql`
- Create: `src/main/resources/db/migration/frontdesk/V2026_10_23_1201__grant_frontdesk_to_app_role.sql`
- Create: `src/main/kotlin/com/zeny/hotel/frontdesk/Traces.kt` (tipos públicos: `TraceId`, `TraceStatus`)
- Create: `src/main/kotlin/com/zeny/hotel/frontdesk/domain/Trace.kt` (`Trace`, `TraceLink`, `TraceRules`)
- Create: `src/main/kotlin/com/zeny/hotel/frontdesk/application/TracePorts.kt` (`TraceStore`, `TraceFilter`)
- Create: `src/main/kotlin/com/zeny/hotel/frontdesk/infrastructure/JdbcTraceStore.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/frontdesk/FrontdeskErrorCodes.kt` (`TRACE_INVALID`, `TRACE_NOT_FOUND`, `TRACE_ALREADY_RESOLVED`, `UNKNOWN_DEPARTMENT`, `UNKNOWN_ASSIGNEE`, reexportados `UNKNOWN_RESERVATION`/`ROOM_INACTIVE` se existirem no módulo dono)
- Test: `src/test/kotlin/com/zeny/hotel/frontdesk/TraceDomainTest.kt` (puro) e `src/test/kotlin/com/zeny/hotel/frontdesk/TraceStoreTest.kt` (`@IntegrationTest`, a tabela e o trigger)

**Interfaces:**
- Produces:
  - Tabela `frontdesk.trace`: `id uuid PK`, `department_id uuid NOT NULL`, `due_on date NOT NULL`, `text varchar(500) NOT NULL` (`CHECK length(btrim(text)) BETWEEN 1 AND 500`), `reservation_id uuid NULL`, `room_id uuid NULL` (`CHECK ((reservation_id IS NULL) <> (room_id IS NULL))`), `assignee_user_id uuid NULL`, `created_at timestamptz`, `created_by uuid`, `updated_at timestamptz`, `resolved_at timestamptz NULL`, `resolved_by uuid NULL` (`CHECK ((resolved_at IS NULL) = (resolved_by IS NULL))`), `version bigint NOT NULL`; índices `(due_on, id)`, `(department_id, due_on, id)`, `(reservation_id)` e `(room_id)` parciais; trigger `trace_refuse_delete` com a função `frontdesk.refuse_delete()`; comentário de cabeçalho no estilo das migrações vizinhas (finalidade, por que o texto nunca vai à trilha).
  - `value class TraceId(val value: UUID) : TypedId` com `new()`; `enum class TraceStatus { OPEN, RESOLVED }`.
  - `sealed interface TraceLink { data class Reservation(val id: ReservationId); data class Room(val id: RoomId) }`.
  - `data class Trace(id, departmentId: DepartmentId, dueOn: LocalDate, text: String, link: TraceLink, assigneeUserId: UserId?, createdAt, createdBy: UserId, updatedAt, resolvedAt: Instant?, resolvedBy: UserId?, version: Long)` com `status`, `fun change(...)`, `fun resolve(by, at)` (resolvido → `ConflictException(TRACE_ALREADY_RESOLVED)`), `auditState()` **sem o texto** (`textLength`), `toString()` sem o texto.
  - `object TraceRules`: `text(raw): String` (D6), `dueOn(date, businessDate)` (D4), `link(reservationId?, roomId?): TraceLink` (exatamente um, senão `400 TRACE_INVALID` com o campo `reservationId`).
  - `interface TraceStore { fun add(trace); fun update(trace) /* version += 1, por id e versão */; fun find(id): Trace?; fun forUpdate(id): Trace? /* SELECT … FOR UPDATE */; fun page(filter: TraceFilter, after: Pair<LocalDate, UUID>?, limit: Int): List<Trace> }`; `data class TraceFilter(departmentId?, dueOn?, status?, reservationId?, roomId?)`; página em ordem `(due_on, id)`.

- [ ] **Step 1: Testes que falham:**
  - `TraceDomainTest`: texto aparado e limites (vazio, só espaços, 501 → `TRACE_INVALID` campo `text`; 500 aceito); vínculo com os dois ou nenhum → `TRACE_INVALID` campo `reservationId`; vencimento antes da data operacional → `TRACE_INVALID` campo `dueOn`; `resolve` duas vezes → `TRACE_ALREADY_RESOLVED`; `change` num resolvido → `TRACE_ALREADY_RESOLVED`; `auditState()` e `toString()` não contêm o texto.
  - `TraceStoreTest`: grava e lê; `update` incrementa a versão; `DELETE` direto na tabela (pelo `JdbcClient`) é recusado pelo trigger; o `CHECK` do vínculo recusa as duas colunas preenchidas; `page` filtra por cada campo e pagina em `(due_on, id)` sem repetir nem pular (três recados no mesmo vencimento, limite 2).
- [ ] **Step 2:** rodar e ver falhar (`./gradlew test --tests 'com.zeny.hotel.frontdesk.Trace*'`).
- [ ] **Step 3:** implementar migrações, tipos, domínio e store (o store no estilo dos `Jdbc…` do projeto, com `JdbcClient`).
- [ ] **Step 4:** rodar e ver passar, mais `./gradlew test --tests '*MigrationConventionTest' --tests 'com.zeny.hotel.ModularityTest'`.
- [ ] **Step 5: Commit** `git commit -m "Cria o schema do frontdesk com os recados (M5 E06)"`.

---

### Task 2: Caso de uso, permissões e endpoints dos recados

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/frontdesk/application/TraceService.kt` (`NewTraceCommand`, `TraceChangeCommand`, `TraceView`, `FrontdeskTraceService`)
- Create: `src/main/kotlin/com/zeny/hotel/frontdesk/web/TracesController.kt` e `frontdesk/web/TraceDtos.kt`
- Modify: `frontdesk/FrontdeskPermissions.kt` (`TRACES_READ`, `TRACES_MANAGE`, `TRACES_RESOLVE`, D2), `frontdesk/FrontdeskModule.kt` (`"staff"` em `allowedDependencies` e KDoc), `frontdesk/application/CheckInService.kt` (`FrontdeskAudit.TRACE = "frontdesk.trace"` e as ações `frontdesk.trace.create`/`update`/`resolve`, ou num objeto de auditoria próprio dos recados)
- Modify: `src/test/kotlin/com/zeny/hotel/ModularityTest.kt` (o teste do `frontdesk` com `staff`, só pela `StaffApi`), `docs/ARCHITECTURE.md` §3.1 (`frontdesk` → `staff`), `src/test/kotlin/com/zeny/hotel/iam/AuthorizationTest.kt` (modelos que ele enumera), `api/openapi.yaml` (por `updateOpenApi`), `README.md`
- Test: `src/test/kotlin/com/zeny/hotel/frontdesk/FrontdeskTracesApiTest.kt` (novo, `@IntegrationTest`; apoio de `FrontdeskTestSupport`, `ReservationsTestSupport`, `RoomsTestSupport`; setores pela `StaffApi.departments()`; usuário com colaborador pelo apoio do `staff`)

**Interfaces:**
- Consumes: Task 1 (`TraceStore`, `Trace`, `TraceRules`, `TraceFilter`, códigos); `StaffApi.departments()`, `StaffApi.employeeOfUser(userId)`; `ReservationsApi.reservation(id)`; `RoomsApi.room(id)` (com `active`); `HotelApi.currentBusinessDate()`; `CurrentActor`; `Authorization`; `AuditContext`/`@Audited`; `ETags`; `PageParams`/`CursorPage`/`PageCursor`.
- Produces (HTTP, `@Tag` `frontdesk`):
  - `POST /api/v1/traces` (`createTrace`, `frontdesk.traces.manage`): `{departmentId, dueOn, text, reservationId?, roomId?, assigneeUserId?}` → `201`, `Location` `/api/v1/traces/{id}`, `ETag`, `TraceResponse`.
  - `GET /api/v1/traces` (`listTraces`, `frontdesk.traces.read`): filtros `departmentId`, `dueOn`, `status` (`OPEN|RESOLVED`), `reservationId`, `roomId`, `cursor`, `limit` → `CursorPage<TraceResponse>` no formato das outras listas.
  - `GET /api/v1/traces/{id}` (`getTrace`, `frontdesk.traces.read`) → `ETag`; inexistente `404 TRACE_NOT_FOUND`.
  - `PUT /api/v1/traces/{id}` (`updateTrace`, `frontdesk.traces.manage`, `If-Match`): `{departmentId, dueOn, text, assigneeUserId?}` → `200` com `ETag` novo; resolvido `409 TRACE_ALREADY_RESOLVED`.
  - `POST /api/v1/traces/{id}/resolve` (`resolveTrace`, `frontdesk.traces.resolve`): trava a linha; resolvido `409 TRACE_ALREADY_RESOLVED`; `200` com `resolvedAt`/`resolvedBy`.
  - `TraceResponse(id, departmentId, departmentName, dueOn, text, reservationId?, roomId?, roomNumber?, assigneeUserId?, assigneeName?, status, createdAt, createdBy, resolvedAt?, resolvedBy?, version)`.
- Recusas: setor inexistente `400 UNKNOWN_DEPARTMENT` (campo `departmentId`); responsável sem colaborador `400 UNKNOWN_ASSIGNEE`; reserva inexistente `400 UNKNOWN_RESERVATION` (campo `reservationId`); UH inexistente `400 UNKNOWN_ROOM`, desativada `422 ROOM_INACTIVE` (campo `roomId`); as de `TraceRules`.
- Trilha: `frontdesk.trace.create`/`update`/`resolve` na entidade `frontdesk.trace`, com `Trace.auditState()` (sem o texto).

- [ ] **Step 1: Testes que falham** (`FrontdeskTracesApiTest`):
  - `a trace is created for a department, linked to a reservation or a room, and read back with its ETag`: os dois vínculos (`201`, `Location`, `ETag`, `status` `OPEN`, `departmentName`, `roomNumber` no vínculo de UH, `assigneeName` com o responsável).
  - `a trace without a link, with both links, an unknown department, reservation, room or assignee is refused`: cada caso com o código e o campo; UH desativada `422 ROOM_INACTIVE`; nenhum recado gravado.
  - `the list filters by department, due date, status, reservation and room, page by page`: cinco recados variados; cada filtro devolve o esperado; com `limit=2` as páginas cobrem todos sem repetição (Review Focus 5).
  - `updating needs If-Match and never touches a resolved trace`: sem `If-Match` → `428`; velho → `412`; certo → `200` com versão nova; resolvido → `409 TRACE_ALREADY_RESOLVED` e nada muda (Review Focus 4).
  - `resolving records who and when, once`: `200` com `resolvedBy` = o usuário e `resolvedAt` = o relógio; de novo → `409`; dois resolvendo ao mesmo tempo (`CyclicBarrier`, duas threads) → um `200` e um `409` (Review Focus 3).
  - `writes need their permission, and the department staff resolve without editing`: sem permissão → `403` em cada escrita; um usuário só com `frontdesk.traces.read` + `frontdesk.traces.resolve` resolve e recebe `403` no `PUT`.
  - `the trace text never reaches the audit trail, the problems or the logs` (Review Focus 1): crie com um texto marcado (`"BERCO-UH-SEGREDO"`), altere com outro, provoque um `400` de texto longo; a trilha (`support.auditEntries`), os corpos de erro e o `CapturedOutput` não contêm os marcadores; a trilha traz `textLength`.
  - `there is no DELETE for traces`: `DELETE /api/v1/traces/{id}` → `405` (ou o código que o projeto devolve para método não mapeado).
- [ ] **Step 2:** rodar e ver falhar.
- [ ] **Step 3:** implementar caso de uso, permissões, controller, DTOs, a aresta `staff` (módulo, `ModularityTest`, ARCHITECTURE §3.1).
- [ ] **Step 4:** rodar e ver passar, mais `./gradlew test --tests 'com.zeny.hotel.frontdesk.*' --tests 'com.zeny.hotel.ModularityTest' --tests '*ApiSecurityTest' --tests '*AuthorizationTest'`.
- [ ] **Step 5:** `./gradlew updateOpenApi` (só acréscimos), `README.md` (seção "API"), `./gradlew lint`, `.github/scripts/check-api-breaking.sh`.
- [ ] **Step 6: Commit** `git commit -m "Implementa os recados no frontdesk (M5 E06)"`.

---

### Task 3: Verificação completa

- [ ] `./gradlew build` → `BUILD SUCCESSFUL`.
- [ ] `.github/scripts/check-api-breaking.sh` e `… ffae917` → sem `ERR`.

## Notas para o handoff (o orquestrador registra)

- D1 a D3 para a entrevista do fim do lote (responsável por usuário com colaborador; permissão de resolver separada para os setores; vínculo fixo).
- Nenhuma notificação push de recado (fora de escopo; RF-NOT-01 não lista recados).
