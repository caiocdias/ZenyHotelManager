# M5 E10 — Agentes, impressoras e fila de impressão: plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** criar o módulo `printing` com agentes de impressão e impressoras, autenticação do agente por token opaco próprio numa cadeia de segurança separada, fila de `PrintJob` com o conteúdo cifrado, entrega por SSE e long-poll, confirmação pelo agente e reenvio dos jobs não confirmados.

**Architecture:** o `shared` ganha uma porta genérica de **credencial de serviço** (`ServiceCredentialRealm`, ARCHITECTURE §8.1) e uma segunda `SecurityFilterChain`, ordenada antes da de usuário, que só atende os prefixos de caminho dos realms registrados e autentica `Authorization: Bearer <token opaco>` pelo realm do caminho. O `printing` implementa o realm do agente (`/api/v1/print-agents/me/`), guarda só o SHA-256 do token, e expõe a administração (agentes, impressoras, jobs) com permissões de usuário e a API do agente (stream SSE, long-poll, conteúdo e ack) com o principal de serviço. Um job, criado pela `PrintingApi` ou por `POST /api/v1/print-jobs`, guarda o conteúdo cifrado pelo `ContentCipher` e uma posição crescente; a entrega reivindica o job (`PENDING → SENT`) de forma atômica; um job com ShedLock devolve à fila o `SENT` sem ack depois do prazo (posição nova), marca `FAILED` no limite de tentativas e apaga o conteúdo dos jobs terminados depois do prazo de guarda.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Security 7, Spring MVC (`SseEmitter`, `DeferredResult`), Hibernate 7.4, Flyway 12, ShedLock 7, springdoc 3.1, Testcontainers.

**Spec:** `docs/roadmaps/roadmap-m5-recepcao-fnrh.md`, seção "E10" (e H17, H20, H23 em "Hipóteses e decisões em aberto", "Restrições e invariantes"), `docs/SPEC.md` §5.22 (RF-NOT-03) e §8.2.18, `docs/ARCHITECTURE.md` §3.1, §8.1, §11, §14 e D13, e as notas do brain `ZenyHotelManager - Segurança, autenticação e autorização`, `ZenyHotelManager - Tempo real (SSE)`, `ZenyHotelManager - Arquitetura e estrutura de módulos`, `ZenyHotelManager - Eventos e jobs`, `ZenyHotelManager - Schemas, migrações e views de relatório` e `ZenyHotelManager - Tipos do kernel`.

## Decisões desta etapa

| # | Decisão | Por quê | Custo se errada |
|---|---|---|---|
| D-E10-1 | **Credencial de serviço no `shared`.** Porta na raiz do `shared`: `interface ServiceCredentialRealm { val name: String; val pathPrefix: String; fun authenticate(token: String): ServicePrincipal? }` e `data class ServicePrincipal(val realm: String, val id: UUID)`. A `SecurityConfiguration` cria, se houver algum realm, a cadeia `serviceSecurityFilterChain` (`@Order` antes da de usuário, `securityMatcher` = os `pathPrefix + "**"`), com filtro próprio que lê só o cabeçalho `Authorization: Bearer`, escolhe o realm pelo prefixo do caminho, autentica e põe um `ServicePrincipal` no contexto; sem token ou com token recusado, `401 UNAUTHENTICATED` (problema RFC 9457, `WWW-Authenticate: Bearer`) pelo mesmo `ProblemWriter`; despacho `ASYNC` e `ERROR` liberados como na outra cadeia; sem sessão, CSRF, Basic nem formulário. Um JWT de usuário num caminho de realm é recusado (não é token do realm); um token de realm fora do prefixo cai na cadeia de usuário e é recusado (não é JWT). O `printing` registra o realm como bean. Descartada: a cadeia dentro do `printing` (precisaria duplicar o `ProblemWriter`, interno do `shared`, e espalhar configuração de segurança por módulo de plataforma). | H23 (cadeia separada); ARCHITECTURE §8.1 fala em "credenciais de serviço" no plural; o `shared` já é dono das cadeias e do formato de erro, como o `iam` fornece o `JwtDecoder` sem o `shared` depender dele. | Uma porta a mais no kernel. Mover a cadeia depois é refatoração interna. |
| D-E10-2 | **Token do agente:** `zpa_` + 32 bytes aleatórios (`SecureRandom`) em base64url sem preenchimento; guardado só como SHA-256 em hexadecimal (`print_agent.token_hash`, único). Exibido uma vez, na criação e na rotação; a rotação invalida o anterior na hora; a desativação apaga o hash (revoga). O realm recusa sem consulta o token sem o prefixo. `last_seen_at` é gravado na autenticação no máximo uma vez por minuto. **Sem `@Idempotent`** na criação e na rotação: a resposta guardada pela idempotência teria o token em claro. | H23; "token só como hash" (Restrições). | Um agente duplicado por repetição de `POST` é desativado pelo admin. |
| D-E10-3 | **Trilha do agente:** as escritas do agente (reivindicar job e ack) são casos de uso `@Audited` com ator sistema (o `CurrentActor` do `iam` só reconhece JWT de usuário); o retrato do job leva `id`, `printerId`, `status`, `attempts` e `agentId` (quem entregou ou confirmou), nunca o conteúdo nem o token. | `LayerRules.writeUseCasesAreAudited`; a trilha não guarda dado pessoal (a ficha tem). | Uma entrada por entrega; volume baixo (um hotel). |
| D-E10-4 | **Ciclo do job:** `PENDING` → `SENT` (reivindicado pela entrega, `attempts + 1`, `sent_at`, `delivered_to` = agente) → `PRINTED` ou `FAILED` (ack do agente, com `failure_code` opcional `[A-Z][A-Z0-9_]{0,49}`, nunca texto livre). Sem ack até `zeny.printing.ack-timeout` (`PT2M`): volta a `PENDING` com **posição nova**; na reivindicação de número `zeny.printing.max-attempts` (3) sem ack, `FAILED` com `failure_code = ACK_TIMEOUT`. Ack de job terminado: `409 PRINT_JOB_NOT_ACKNOWLEDGEABLE`; job de outro agente ou inexistente: `404 PRINT_JOB_NOT_FOUND` (não vaza a existência). `status` é `x-extensible-enum` na resposta. | Critérios de ack e reenvio; posição nova faz o job reenviado passar do `Last-Event-ID` do agente. | O agente precisa ser idempotente por id de job (um `SENT` pode ser reentregue na reconexão, D-E10-6). |
| D-E10-5 | **Conteúdo:** só no schema `printing`, coluna `content` (`bytea`) cifrada pelo `ContentCipher` com o id do job como dado associado; teto `zeny.printing.max-content-bytes` (5 MiB = `5242880`) → `422 PRINT_CONTENT_TOO_LARGE`; vazio → `422 PRINT_CONTENT_EMPTY`; impressora `A4_PDF` exige conteúdo que começa por `%PDF-` (`422 PRINT_CONTENT_NOT_PDF`); `THERMAL_ESCPOS` aceita bytes. Tipo de mídia na entrega: `application/pdf` ou `application/octet-stream`. Apagado (`NULL`, `content_purged_at`) depois de `zeny.printing.content-retention` (`PT24H`) do fim (`PRINTED`/`FAILED`); depois disso o `GET …/content` é `410 PRINT_CONTENT_PURGED`. | H17; decisão aberta da E10 (teto, guarda). | Valores em propriedade. |
| D-E10-6 | **Entrega:** a reivindicação é atômica (`UPDATE … WHERE status = 'PENDING' … RETURNING`, ou `SELECT … FOR UPDATE SKIP LOCKED`), então duas conexões do mesmo agente nunca levam o mesmo `PENDING`. Só entram jobs de impressoras **ativas** do agente autenticado. **SSE** `GET /api/v1/print-agents/me/jobs/stream`: ao conectar, reenvia os `SENT` desse agente ainda sem ack e reivindica os `PENDING`, ambos com posição maior que o `Last-Event-ID` (ou todos, sem ele), em ordem de posição; depois reivindica a cada `zeny.printing.delivery-poll-interval` (`PT1S`), lendo o banco: **sem sinal em memória**, o que vale igual com uma ou várias instâncias; evento `job`, `id` = posição, `data` JSON com `id`, `printerId`, `documentType`, `mediaType`, `attempts` e `createdAt`; comentário `: heartbeat` a cada `PT15S`; a conexão fecha depois de `zeny.printing.stream-max-duration` (`PT30M`) e o agente reconecta com o `Last-Event-ID`. **Long-poll** `GET /api/v1/print-agents/me/jobs?wait=<segundos>` (0 a 30; padrão 25): devolve na hora o que reivindicar; sem nada, espera (assíncrono, `DeferredResult` numa *virtual thread*) consultando a cada `delivery-poll-interval` até achar ou acabar o `wait`, e devolve a lista (vazia = `200 []`). | ARCHITECTURE §14 e D13; H23 ("`Last-Event-ID` = a posição persistida do job, sem buffer em memória"). A consulta periódica dispensa um canal entre instâncias; para um hotel, uma consulta por segundo por conexão é desprezível. | Agente que não deduplica pode imprimir duas vezes após uma queda; o contrato documenta. Até 1 s de latência na entrega. |
| D-E10-7 | **Permissões** (H20): `printing.agents.manage` (agentes e impressoras; nenhum modelo de role além do Administrador, que recebe todas); `printing.jobs.read` (Gerente e Recepcionista); `printing.jobs.manage` (criar job manual; Gerente). Nenhuma sensível. | H20. | Um código publicado não muda. |
| D-E10-8 | **Endpoints de administração:** `POST /api/v1/print-agents` (`{name}` → `201` com `token`), `GET /api/v1/print-agents`, `GET /api/v1/print-agents/{id}`, `POST /api/v1/print-agents/{id}/rotate-token` (`200` com `token`), `POST /api/v1/print-agents/{id}/deactivate` (`200`; revoga; rotação de inativo → `409 PRINT_AGENT_INACTIVE`); `POST /api/v1/printers` (`{name, kind, address, agentId}`), `GET /api/v1/printers`, `PUT /api/v1/printers/{id}` (`If-Match`), `POST /api/v1/printers/{id}/deactivate`; `POST /api/v1/print-jobs` (`@Idempotent`, `printing.jobs.manage`, `{printerId, documentType, content}` com o conteúdo em base64 no JSON) e `GET /api/v1/print-jobs?status=&printerId=` (cursor, `printing.jobs.read`, sem conteúdo). Respostas com o token: `Cache-Control: no-store`. Nome do agente: 1 a 100 caracteres, sem controle; `address`: 1 a 255, sem controle nem espaço; `documentType`: `[a-z][a-z0-9]*(\.[a-z][a-z0-9-]*)+`, até 100 (o módulo dono escolhe, por exemplo `frontdesk.registration-card`); referência inexistente `400 UNKNOWN_PRINT_AGENT`/`UNKNOWN_PRINTER`, desativada `422 PRINT_AGENT_INACTIVE`/`PRINTER_INACTIVE`. Nada é apagado. | Convenções de API do projeto ("a API não apaga nada", `If-Match`, `@Idempotent` em POST com efeito externo). | Rotas publicadas não mudam. |
| D-E10-9 | **Contrato:** esquema de segurança `printAgentToken` (HTTP bearer, `bearerFormat: opaque`) nas operações de `/api/v1/print-agents/me/**`, no lugar do `bearerAuth`; o `OpenApiConfiguration` aplica o esquema de cada realm aos caminhos do prefixo dele. Nenhuma operação com `security: []`. | H23 ("esquema de segurança próprio no contrato"). | Nenhum. |
| D-E10-11 | **Limite de tamanho das tarefas:** contam como arquivo de produção os fontes Kotlin e a migração de criação; o `api/openapi.yaml` (gerado), a migração de `GRANT` (boilerplate de 10 linhas no modelo do projeto) e o `application.yaml` não contam. | Regra do orquestrador (até 3 arquivos de produção por tarefa) aplicada ao que exige revisão. | Uma tarefa um pouco maior. |
| D-E10-10 | **Um job de manutenção** `printing-jobs-maintenance` (`@Scheduled` a cada minuto + `@SchedulerLock`, `lockAtMostFor = PT10M`), adiado nos testes por `PRINTING_MAINTENANCE_DEFERRED`, faz o reenvio/limite e o expurgo; os testes chamam o caso de uso direto com o `MutableClock`. | Modelo do projeto (nota "Eventos e jobs"). | Nenhum. |

## Global Constraints

- Grafo de módulos (ARCHITECTURE §3.1): `printing` depende **só de `shared`** nesta etapa (`@ApplicationModule(allowedDependencies = ["shared"])`, teste dele no `ModularityTest`); não importa `iam`, não lê tabela de outro schema, sem FK entre schemas; nunca escuta eventos de negócio.
- Estrutura do módulo (nota "Arquitetura e estrutura de módulos"): raiz com `PrintingModule.kt` (`@PackageInfo`), `PrintingApi`, `PrintingPermissions`, `PrintingErrorCodes`; `domain` sem Spring; `application` com casos de uso e portas; `infrastructure`; `web` só controllers e DTOs. Toda escrita da `application` é `@Audited` + `@Transactional`.
- Migrações na pasta `src/main/resources/db/migration/printing/`, versão posterior a `V2026_11_10_1100`, uma de `GRANT` ao `"${app_role}"` no modelo de `fnrh/V2026_11_04_1001__grant_fnrh_to_app_role.sql`; o schema é criado pelo Flyway pelo nome da pasta.
- Token do agente só como hash; nunca em log, trilha, resposta (salvo a criação e a rotação), `toString` nem idempotência. Conteúdo do job cifrado na coluna e nunca em log, trilha nem resposta de usuário.
- Instantes pelo `Clock`; nenhum `Thread.sleep` em teste para ordenar passos (latch, `awaitility` ou o `MutableClock`).
- Convenções de API: `@ApiV1Controller`, `@Operation(operationId)` único, `@Tag(name = "Printing")`, erros RFC 9457 com códigos em `PrintingErrorCodes`, paginação por cursor (`PageParams`/`CursorPage`), `If-Match` com `ETags`, `@Idempotent` só em JSON síncrono. `api/openapi.yaml` só por `./gradlew updateOpenApi`.
- `ApiSecurityTest` atualizado com cada combinação token × caminho; nenhum endpoint público novo.
- Documentação, mensagens e commits em pt-BR; código, API e banco em inglês. Commits com `Soul: <soul>`.
- Os testes compartilham o banco: cada teste cria os próprios agentes e impressoras e não conta linhas em valor absoluto.
- `./gradlew build` verde e `.github/scripts/check-api-breaking.sh main` sem `ERR`.

## Review Focus

1. **Agente desativado ou com token rotacionado com stream aberto:** a conexão aberta não pode seguir recebendo jobs; cada reivindicação confere o agente ativo e o hash atual, e o stream encerra quando a conferência falha (teste na Task 11).
2. **Duas conexões do mesmo agente** (SSE e long-poll ao mesmo tempo): nenhum `PENDING` é entregue às duas (teste concorrente na Task 10).
3. **Impressora movida para outro agente ou desativada com jobs pendentes:** o agente antigo não recebe nem confirma mais esses jobs; o ack de um `SENT` dele vira `404` (teste na Task 10).
4. **Job reenviado depois do timeout e ack atrasado do primeiro envio:** o ack de um job que voltou a `PENDING` e foi reivindicado de novo é aceito uma vez; um ack de job já terminado é `409` (teste na Task 12).
5. **Token com espaço, quebra de linha, prefixo de outro realm ou `Bearer` repetido:** `401`, sem `500` e sem log do token (teste na Task 4 e na Task 7).

---

### Task 1: Esqueleto do módulo e migração

**Estimativa:** 30 min. **Depende de:** nenhuma. **Risco:** alto (persistência e migração).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/printing/PrintingModule.kt`
- Create: `src/main/resources/db/migration/printing/V2026_11_11_1000__create_printing_tables.sql`
- Create: `src/main/resources/db/migration/printing/V2026_11_11_1001__grant_printing_to_app_role.sql`
- Modify: `src/test/kotlin/com/zeny/hotel/ModularityTest.kt` (teste do `printing`: dependências exatas `shared`)
- Test: `src/test/kotlin/com/zeny/hotel/printing/PrintingSchemaTest.kt`

**Interfaces:**
- Produces: tabelas `printing.print_agent (id uuid pk, name text not null, token_hash char(64) unique null, active boolean not null, last_seen_at timestamptz null, created_at timestamptz not null, version bigint not null)`; `printing.printer (id uuid pk, name text not null, kind varchar(20) not null CHECK in ('THERMAL_ESCPOS','A4_PDF'), address text not null, agent_id uuid not null FK print_agent, active boolean not null, version bigint not null)`; `printing.print_job (id uuid pk, printer_id uuid not null FK printer, document_type text not null, status varchar(20) not null CHECK in ('PENDING','SENT','PRINTED','FAILED'), position bigint not null unique, attempts int not null CHECK >= 0, content bytea null, content_purged_at timestamptz null, media_type varchar(50) not null, created_at timestamptz not null, sent_at timestamptz null, delivered_to uuid null, finished_at timestamptz null, failure_code varchar(50) null, version bigint not null)`; sequence `printing.print_job_position_seq`; índices `(printer_id, status, position)`, `(status, sent_at)` e `(status, finished_at) WHERE content IS NOT NULL`. CHECKs: `active OR token_hash IS NULL`; `content` e `content_purged_at` nunca os dois preenchidos; `finished_at` presente se e só se `status IN ('PRINTED','FAILED')`. Comentário no topo da migração no padrão das demais.

- [ ] **Step 1:** teste do `printing` no `ModularityTest` (modelo: o do `fnrh`) e `PrintingSchemaTest` (`@IntegrationTest`): o papel da aplicação tem `SELECT, INSERT, UPDATE, DELETE` nas três tabelas e `USAGE` na sequence; `token_hash` repetido viola o único; `active = false` com `token_hash` viola o CHECK; job `PENDING` com `finished_at` viola o CHECK.
- [ ] **Step 2:** ver falhar: `./gradlew test --tests 'com.zeny.hotel.ModularityTest' --tests 'com.zeny.hotel.printing.*'`.
- [ ] **Step 3:** `PrintingModule.kt` (modelo `fnrh/FnrhModule.kt`, `allowedDependencies = ["shared"]`, KDoc citando ARCHITECTURE §14 e D13) e as duas migrações.
- [ ] **Step 4:** ver passar (o mesmo comando, mais o teste de convenção de migrações do projeto).
- [ ] **Step 5:** commit "Cria o módulo printing e as tabelas de agentes, impressoras e jobs (M5 E10, Task 1)".

---

### Task 2: Domínio de agente e impressora

**Estimativa:** 35 min. **Depende de:** Task 1. **Risco:** alto (token).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/printing/PrintingErrorCodes.kt`
- Create: `src/main/kotlin/com/zeny/hotel/printing/domain/PrintAgent.kt`
- Create: `src/main/kotlin/com/zeny/hotel/printing/domain/Printer.kt`
- Test: `src/test/kotlin/com/zeny/hotel/printing/domain/PrintAgentTest.kt`, `PrinterTest.kt`

**Interfaces:**
- Produces (entidades JPA mapeadas nas tabelas da Task 1, `toString` sem hash):
  - `PrintAgent.create(name: String, now: Instant): Pair<PrintAgent, String>` (o token em claro, uma vez); `rotateToken(): String` (inativo → `BusinessRuleException(PRINT_AGENT_INACTIVE)`); `deactivate()` (apaga o hash); `rename(name)`; `touch(now: Instant): Boolean` (só muda se o último contato tiver mais de 1 min); `companion fun hashOf(token: String): String` (SHA-256 em hex, 64 caracteres) e `const val TOKEN_PREFIX = "zpa_"`.
  - `Printer.create(name, kind: PrinterKind, address, agentId: UUID)`, `update(name, kind, address, agentId)`, `deactivate()`; `enum class PrinterKind(val mediaType: String) { THERMAL_ESCPOS("application/octet-stream"), A4_PDF("application/pdf") }` em `domain`.
  - `PrintingErrorCodes` (modelo `FnrhErrorCodes`): `PRINT_AGENT_INACTIVE`, `PRINTER_INACTIVE`, `UNKNOWN_PRINT_AGENT`, `UNKNOWN_PRINTER`, `PRINT_JOB_NOT_FOUND`, `PRINT_JOB_NOT_ACKNOWLEDGEABLE`, `PRINT_CONTENT_TOO_LARGE`, `PRINT_CONTENT_EMPTY`, `PRINT_CONTENT_NOT_PDF`, `PRINT_CONTENT_PURGED`, `INVALID_FAILURE_CODE`.

- [ ] **Step 1:** testes unitários: o token tem o prefixo, 47 caracteres e só base64url; dois tokens diferem; `hashOf` tem 64 caracteres hex; a rotação troca o hash e o anterior deixa de casar; desativar apaga o hash; rotacionar inativo falha; `touch` não muda dentro de 1 min; nome (1 a 100) e endereço (1 a 255, sem espaço) com caractere de controle recusados.
- [ ] **Step 2–4:** ver falhar, implementar, ver passar: `./gradlew test --tests 'com.zeny.hotel.printing.domain.*' --tests 'com.zeny.hotel.architecture.*'`.
- [ ] **Step 5:** commit "Acrescenta o domínio de agentes e impressoras (M5 E10, Task 2)".

---

### Task 3: Domínio do job e propriedades da fila

**Estimativa:** 35 min. **Depende de:** Task 2. **Risco:** alto (máquina de estados).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/printing/domain/PrintJob.kt`
- Create: `src/main/kotlin/com/zeny/hotel/printing/infrastructure/PrintingProperties.kt` (`@ConfigurationProperties("zeny.printing")`, validado: `maxContentBytes` 5242880, `contentRetention` PT24H, `ackTimeout` PT2M, `maxAttempts` 3, `longPollMaxWait` PT30S, `deliveryPollInterval` PT1S, `streamHeartbeat` PT15S, `streamMaxDuration` PT30M, `maintenanceInitialDelay` PT1M; todos positivos; `toString` padrão)
- Modify: `src/main/resources/application.yaml` (os valores acima)
- Test: `src/test/kotlin/com/zeny/hotel/printing/domain/PrintJobTest.kt`, `src/test/kotlin/com/zeny/hotel/printing/PrintingPropertiesTest.kt`

**Interfaces:**
- Produces: `PrintJob.create(id: UUID, printerId, kind: PrinterKind, documentType, sealedContent: ByteArray, position: Long, now: Instant)`; `claim(agentId: UUID, now)` (`PENDING → SENT`, `attempts + 1`, `deliveredTo`); `acknowledge(agentId, printed: Boolean, failureCode: String?, now)` (`SENT` → `PRINTED`/`FAILED`; outro estado → `PRINT_JOB_NOT_ACKNOWLEDGEABLE`; `failureCode` fora de `[A-Z][A-Z0-9_]{0,49}` → `INVALID_FAILURE_CODE`); `expireAck(now, maxAttempts: Int, newPosition: Long)` (`SENT` → `PENDING` com a posição nova, ou `FAILED`/`ACK_TIMEOUT` com `attempts >= maxAttempts`); `purgeContent(now)` (só em `PRINTED`/`FAILED`); `enum class PrintJobStatus { PENDING, SENT, PRINTED, FAILED }`; `toString` sem conteúdo. `PrintingProperties` como acima.

- [ ] **Step 1:** testes: cada operação em cada estado (válida e inválida); `expireAck` na 3ª tentativa vira `FAILED/ACK_TIMEOUT`; `purgeContent` em `PENDING` falha; propriedade zero ou negativa recusa a partida (`ApplicationContextRunner`).
- [ ] **Step 2–4:** ver falhar, implementar, ver passar: `./gradlew test --tests 'com.zeny.hotel.printing.*'`.
- [ ] **Step 5:** commit "Acrescenta o domínio do job de impressão e as propriedades da fila (M5 E10, Task 3)".

---

### Task 4: Credencial de serviço no `shared`

**Estimativa:** 45 min. **Depende de:** nenhuma (onda 1, com a Task 1). **Risco:** alto (segurança).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/shared/ServiceCredentials.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/shared/web/SecurityConfiguration.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/shared/web/OpenApiConfiguration.kt`
- Test: `src/test/kotlin/com/zeny/hotel/shared/web/ServiceCredentialChainTest.kt` (com um realm e um controller **só de teste**, por exemplo `/api/v1/test-service/me/ping`, num `@TestConfiguration` importado só por esse teste)

**Interfaces:**
- Produces: `interface ServiceCredentialRealm { val name: String; val pathPrefix: String; val openApiScheme: String; fun authenticate(token: String): ServicePrincipal? }` e `data class ServicePrincipal(val realm: String, val id: UUID)`; o `Authentication` da cadeia tem o `ServicePrincipal` como `principal`, sem autoridades. `pathPrefix` começa por `/api/v1/` e termina em `/`; a partida falha se dois realms se sobrepõem. O `OpenApiConfiguration` registra, para cada realm, o esquema HTTP bearer `openApiScheme` (`bearerFormat: opaque`) e o usa no lugar do `bearerAuth` nas operações cujo caminho começa pelo prefixo.

- [ ] **Step 1:** testes (`ServiceCredentialChainTest`): token aceito no caminho do realm → `200` com o principal certo; sem token → `401` problema com `WWW-Authenticate: Bearer`; token recusado → `401`; JWT de usuário válido no caminho do realm → `401`; token do realm num endpoint de usuário autenticado (por exemplo `GET /api/v1/fnrh/status`) → `401`; token com espaço, quebra de linha, `Bearer` repetido ou esquema `Basic` → `401`, nunca `500`; o token não aparece no log (`OutputCaptureExtension`).
- [ ] **Step 2:** ver falhar: `./gradlew test --tests 'com.zeny.hotel.shared.web.*'`.
- [ ] **Step 3:** implementar a porta, a cadeia (bean só com realms presentes, ordenada antes da de usuário, com o filtro de D-E10-1) e o esquema no OpenAPI; atualizar o KDoc da `SecurityConfiguration` (duas cadeias).
- [ ] **Step 4:** ver passar, mais `com.zeny.hotel.ModularityTest` e `com.zeny.hotel.architecture.*`; `./gradlew checkOpenApi` (sem realm de produção, o contrato não muda).
- [ ] **Step 5:** commit "Acrescenta a credencial de serviço ao shared, numa cadeia de segurança própria (M5 E10, Task 4)".

---

### Task 5: Administração de agentes e impressoras (aplicação e persistência)

**Estimativa:** 45 min. **Depende de:** Task 2. **Risco:** alto (credencial).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/printing/PrintingPermissions.kt` (`ModulePermissions`, D-E10-7; modelo `FnrhPermissions`)
- Create: `src/main/kotlin/com/zeny/hotel/printing/application/PrintAgentsService.kt` (com as portas `PrintAgentStore` e `PrinterStore` no mesmo arquivo)
- Create: `src/main/kotlin/com/zeny/hotel/printing/infrastructure/PrintingPersistence.kt`
- Test: `src/test/kotlin/com/zeny/hotel/printing/PrintAgentsServiceTest.kt`

**Interfaces:**
- Produces: `PrintAgentsService.createAgent(name): CreatedAgent(view, token)`, `agents(): List<PrintAgentView>`, `agent(id)`, `rotateToken(id): CreatedAgent`, `deactivateAgent(id): PrintAgentView`, `createPrinter(NewPrinter)`, `printers(): List<PrinterView>`, `printer(id)`, `updatePrinter(id, expectedVersion, NewPrinter)`, `deactivatePrinter(id)`; cada escrita `@Audited` (`printing.agent.create`, `printing.agent.rotate-token`, `printing.agent.deactivate`, `printing.printer.create`, `printing.printer.update`, `printing.printer.deactivate`; retratos sem o hash) e conferindo `printing.agents.manage`. `PrintAgentView(id, name, active, lastSeenAt, version)`, `PrinterView(id, name, kind, address, agentId, active, version)`, `NewPrinter(name, kind, address, agentId)`. Portas: `PrintAgentStore.byTokenHash(hash): PrintAgent?`, `byId(id)`, `save(agent)`; `PrinterStore.byId(id)`, `save(printer)`.

- [ ] **Step 1:** testes de integração do serviço: criar devolve o token uma vez e a coluna tem só o hash; a rotação invalida o anterior; desativar apaga o hash; impressora com agente inexistente → `UNKNOWN_PRINT_AGENT`, com agente inativo → `PRINT_AGENT_INACTIVE`; `updatePrinter` com versão velha → `412`; sem a permissão → `403`; a trilha das seis ações não contém o token nem o hash.
- [ ] **Step 2–4:** ver falhar, implementar, ver passar: `./gradlew test --tests 'com.zeny.hotel.printing.*' --tests 'com.zeny.hotel.architecture.*' --tests 'com.zeny.hotel.ModularityTest'`.
- [ ] **Step 5:** commit "Acrescenta a administração de agentes e impressoras ao printing (M5 E10, Task 5)".

---

### Task 6: Endpoints de administração

**Estimativa:** 40 min. **Depende de:** Task 5. **Risco:** alto (contrato público, token na resposta).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/printing/web/PrintAgentsController.kt`
- Create: `src/main/kotlin/com/zeny/hotel/printing/web/PrintersController.kt`
- Modify: `api/openapi.yaml` (gerado)
- Test: `src/test/kotlin/com/zeny/hotel/printing/PrintAgentsApiTest.kt`, `PrintersApiTest.kt`

**Interfaces:**
- Consumes: `PrintAgentsService` (Task 5). Produces: as rotas de agentes e impressoras de D-E10-8; `PrintAgentResponse` (sem token), `PrintAgentTokenResponse` (com `token`, descrito como exibido uma única vez); `ETag` no `PrinterResponse`; `kind` como `enum` fechado na requisição e `x-extensible-enum` na resposta.

- [ ] **Step 1:** testes de API: criação `201` com `token` e `Cache-Control: no-store`; `GET` sem token nem hash; rotação devolve token novo; desativação; o Gerente e a Recepcionista reais recebem `403`; nome inválido `400` no campo; `PUT` de impressora sem `If-Match` `428`, velho `412`.
- [ ] **Step 2–4:** ver falhar, implementar, ver passar: `./gradlew test --tests 'com.zeny.hotel.printing.*' --tests 'com.zeny.hotel.shared.web.ApiSecurityTest'`; `./gradlew updateOpenApi`.
- [ ] **Step 5:** commit "Acrescenta os endpoints de agentes e impressoras (M5 E10, Task 6)".

---

### Task 7: Realm do agente

**Estimativa:** 35 min. **Depende de:** Tasks 4 e 6. **Risco:** alto (autenticação).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/printing/infrastructure/PrintAgentRealm.kt` (`name = "print-agent"`, `pathPrefix = "/api/v1/print-agents/me/"`, `openApiScheme = "printAgentToken"`; `authenticate` recusa sem o prefixo `zpa_`, procura pelo hash, exige agente ativo e grava o `touch` numa transação própria curta, sem `@Audited` por ser registro técnico de contato — ver Step 1)
- Create: `src/main/kotlin/com/zeny/hotel/printing/web/PrintAgentSelfController.kt` (`GET /api/v1/print-agents/me/identity` → `{id, name}` do agente autenticado)
- Modify: `src/test/kotlin/com/zeny/hotel/shared/web/ApiSecurityTest.kt`
- Modify: `api/openapi.yaml` (gerado: esquema `printAgentToken`)
- Test: `src/test/kotlin/com/zeny/hotel/printing/PrintAgentRealmTest.kt`

Nota: o `touch` é uma escrita fora da `application` (no realm, em `infrastructure`), então a regra `writeUseCasesAreAudited` não a alcança; o plano decide assim porque o último contato não é ação de negócio e auditá-lo geraria uma entrada por minuto por agente. A revisão pode contestar.

- [ ] **Step 1:** testes: token de agente em `/print-agents/me/identity` `200`; em `/print-agents` (administração) `401`; JWT de usuário em `/print-agents/me/identity` `401`; token revogado e token anterior à rotação `401`; `last_seen_at` gravado no primeiro uso e não regravado dentro de 1 min (`MutableClock`); o contrato tem `printAgentToken` em `components.securitySchemes`, só nas operações de `/api/v1/print-agents/me/**`, e nenhuma operação com `security: []`.
- [ ] **Step 2–4:** ver falhar, implementar, ver passar; `./gradlew updateOpenApi`.
- [ ] **Step 5:** commit "Autentica o agente de impressão pelo token próprio (M5 E10, Task 7)".

---

### Task 8: Fila de jobs: `PrintingApi`, aplicação e persistência

**Estimativa:** 45 min. **Depende de:** Tasks 3 e 5. **Risco:** alto (cifragem do conteúdo).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/printing/PrintingApi.kt` (`interface PrintingApi { fun enqueue(job: NewPrintJob): UUID }`, `data class NewPrintJob(val printerId: UUID, val documentType: String, val content: ByteArray)` com `equals`/`hashCode`/`toString` sem conteúdo; sem conferência de permissão: quem chama já se autorizou)
- Create: `src/main/kotlin/com/zeny/hotel/printing/application/PrintJobsService.kt` (implementa `PrintingApi`; `create(job)` com `printing.jobs.manage` para a rota; `jobs(status, printerId, page)` com `printing.jobs.read`; valida D-E10-5; cifra com o `ContentCipher` e o id do job como dado associado; posição pela sequence; porta `PrintJobStore` no mesmo arquivo; escritas `@Audited` `printing.job.create`, retrato sem conteúdo)
- Create: `src/main/kotlin/com/zeny/hotel/printing/infrastructure/PrintJobPersistence.kt`
- Test: `src/test/kotlin/com/zeny/hotel/printing/PrintJobsServiceTest.kt`

**Interfaces:**
- Produces: `PrintingApi.enqueue`; `PrintJobView(id, printerId, documentType, status, attempts, createdAt, sentAt, finishedAt, failureCode)` (sem conteúdo); `PrintJobStore` com `nextPosition(): Long`, `save(job)`, `byId(id)`, `page(status, printerId, page): CursorPage<PrintJob>`, `claimPending(agentId, afterPosition, limit): List<PrintJob>` (atômico, D-E10-6, só impressoras ativas do agente), `unackedSent(agentId, afterPosition): List<PrintJob>`, `expiredSent(before, limit)`, `purgeable(before, limit)`.

- [ ] **Step 1:** testes: `enqueue` grava a coluna `content` diferente do conteúdo e decifrável com o `ContentCipher` e o id; posições crescentes; conteúdo acima do teto → `PRINT_CONTENT_TOO_LARGE`, vazio → `PRINT_CONTENT_EMPTY`, não-PDF para `A4_PDF` → `PRINT_CONTENT_NOT_PDF`; impressora inexistente → `UNKNOWN_PRINTER`, inativa → `PRINTER_INACTIVE`; `jobs` exige `printing.jobs.read`, `create` exige `printing.jobs.manage` e `enqueue` não exige nada; `claimPending` concorrente em duas threads nunca devolve o mesmo job; a trilha da criação não tem o conteúdo.
- [ ] **Step 2–4:** ver falhar, implementar, ver passar: `./gradlew test --tests 'com.zeny.hotel.printing.*' --tests 'com.zeny.hotel.architecture.*'`.
- [ ] **Step 5:** commit "Acrescenta a fila de jobs de impressão e a PrintingApi (M5 E10, Task 8)".

---

### Task 9: Endpoints da fila

**Estimativa:** 30 min. **Depende de:** Task 8. **Risco:** alto (contrato público, idempotência).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/printing/web/PrintJobsController.kt` (`POST /api/v1/print-jobs` com `@Idempotent` e conteúdo em base64 no JSON; `GET /api/v1/print-jobs?status=&printerId=` com cursor)
- Modify: `api/openapi.yaml` (gerado)
- Test: `src/test/kotlin/com/zeny/hotel/printing/PrintJobsApiTest.kt`

- [ ] **Step 1:** testes: `POST` com `Idempotency-Key` → `201`; a repetição com a mesma chave devolve o mesmo job e não cria outro; sem a chave → o erro do `@Idempotent`; base64 inválido → `400` no campo; a Recepcionista lê (`GET` sem conteúdo) e não cria (`403`); `status` na resposta é `x-extensible-enum` no contrato.
- [ ] **Step 2–4:** ver falhar, implementar, ver passar; `./gradlew updateOpenApi`.
- [ ] **Step 5:** commit "Acrescenta os endpoints da fila de impressão (M5 E10, Task 9)".

---

### Task 10: Entrega ao agente: long-poll, conteúdo e ack

**Estimativa:** 45 min. **Depende de:** Tasks 7 e 8. **Risco:** alto (concorrência, autorização por agente).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/printing/application/PrintAgentJobsService.kt` (`claim(agentId, afterPosition, limit)`, `unacknowledged(agentId, afterPosition)`, `content(agentId, jobId): JobContent(mediaType, bytes)`, `acknowledge(agentId, jobId, printed, failureCode)`; `@Audited` com ator sistema nas escritas (`printing.job.claim`, `printing.job.ack`), retrato de D-E10-3; a cada chamada confere que o agente segue ativo e que a impressora do job é ativa e dele)
- Create: `src/main/kotlin/com/zeny/hotel/printing/web/PrintAgentJobsController.kt` (`GET /api/v1/print-agents/me/jobs?wait=` assíncrono, `GET …/jobs/{id}/content`, `POST …/jobs/{id}/ack` com `{result: PRINTED|FAILED, failureCode?}`; o agente vem do `ServicePrincipal`)
- Modify: `api/openapi.yaml` (gerado)
- Test: `src/test/kotlin/com/zeny/hotel/printing/PrintAgentJobsApiTest.kt`

- [ ] **Step 1:** testes: o agente A recebe só os jobs das impressoras dele; o B não os recebe, não lê o conteúdo nem confirma (`404`); `wait=0` sem job → `200 []`; long-poll que espera recebe o job criado durante a espera (latch, sem `sleep`); long-poll e uma reivindicação simultânea do mesmo agente nunca entregam o mesmo job (Review Focus 2); ack `PRINTED` e `FAILED` mudam o status (critério); ack de job terminado → `409`; impressora movida para B depois do envio a A: ack de A → `404` (Review Focus 3); conteúdo com o tipo de mídia certo e igual ao enviado; `wait` fora de 0..30 → `400`; a trilha do claim e do ack não tem conteúdo nem token.
- [ ] **Step 2–4:** ver falhar, implementar, ver passar; `./gradlew updateOpenApi`.
- [ ] **Step 5:** commit "Acrescenta a entrega dos jobs ao agente por long-poll, conteúdo e ack (M5 E10, Task 10)".

---

### Task 11: Stream SSE do agente

**Estimativa:** 45 min. **Depende de:** Task 10. **Risco:** alto (concorrência, conexões longas).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/printing/web/PrintAgentStreamController.kt` (`GET /api/v1/print-agents/me/jobs/stream`, `text/event-stream`, `Last-Event-ID`; laço numa *virtual thread* que reenvia os `SENT` sem ack e reivindica os `PENDING` depois da posição, consulta a cada `delivery-poll-interval`, manda `: heartbeat`, encerra em `stream-max-duration`, na desconexão e quando o agente deixa de ser válido; `X-Accel-Buffering: no` e `Cache-Control: no-store`; modelo `shared/web/StreamController.kt`)
- Modify: `api/openapi.yaml` (gerado)
- Test: `src/test/kotlin/com/zeny/hotel/printing/PrintAgentStreamTest.kt`

- [ ] **Step 1:** testes (cliente que lê a resposta assíncrona, sem `sleep`; propriedades de intervalo encurtadas no teste): conectar recebe os pendentes do agente em ordem de posição, `id` = posição; job criado depois chega; reconectar com `Last-Event-ID` = N entrega só os de posição maior que N, inclusive o `SENT` sem ack (critério); outro agente não recebe; agente desativado com stream aberto: nada mais é entregue e a conexão encerra (Review Focus 1); heartbeat aparece.
- [ ] **Step 2–4:** ver falhar, implementar, ver passar; `./gradlew updateOpenApi`.
- [ ] **Step 5:** commit "Acrescenta o stream SSE do agente de impressão (M5 E10, Task 11)".

---

### Task 12: Reenvio, limite e expurgo

**Estimativa:** 35 min. **Depende de:** Task 10. **Risco:** alto (job agendado, estados).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/printing/application/PrintJobsMaintenance.kt` (`expireUnacknowledged(now)` e `purgeContent(now)`, `@Audited` com ator sistema, em lotes)
- Create: `src/main/kotlin/com/zeny/hotel/printing/infrastructure/PrintJobsMaintenanceJob.kt` (`@Scheduled(fixedDelay = 1 min, initialDelay pela propriedade)` + `@SchedulerLock(name = "printing-jobs-maintenance", lockAtMostFor = "PT10M")`)
- Modify: `src/test/kotlin/com/zeny/hotel/testsupport/IntegrationTest.kt` (`PRINTING_MAINTENANCE_DEFERRED = "zeny.printing.maintenance-initial-delay=PT24H"` na lista)
- Test: `src/test/kotlin/com/zeny/hotel/printing/PrintJobsMaintenanceTest.kt`

- [ ] **Step 1:** testes com o `MutableClock` (relógio devolvido no fim): `SENT` sem ack depois de `PT2M` volta a `PENDING` com posição maior e é entregue de novo; na 3ª reivindicação sem ack vira `FAILED/ACK_TIMEOUT` (critério); o ack atrasado de um job que voltou e foi reivindicado de novo é aceito uma vez, e o seguinte é `409` (Review Focus 4); conteúdo de `PRINTED` apagado depois de `PT24H` (coluna nula, `content_purged_at`) e `GET …/content` → `410 PRINT_CONTENT_PURGED`; `PENDING` e `SENT` não são apagados.
- [ ] **Step 2–4:** ver falhar, implementar, ver passar.
- [ ] **Step 5:** commit "Acrescenta o reenvio, o limite e o expurgo dos jobs de impressão (M5 E10, Task 12)".

---

### Task 13: Documentação

**Estimativa:** 20 min. **Depende de:** Tasks 11 e 12. **Risco:** normal (só documentação).

**Files:**
- Modify: `docs/ARCHITECTURE.md` (§8.1: a credencial de serviço do `shared` e o token do agente; §14: entrega por SSE e long-poll, posição, ack, reenvio e guarda do conteúdo)
- Modify: `docs/SPEC.md` (§8.2.18: `PrintJob` com `position`, `deliveredTo`, `finishedAt`, `failureCode`; `Printer.kind` com os dois valores; `PrintAgent.active`)
- Modify: `README.md` (seção "API": parágrafo "Impressão (M5 E10, módulo `printing`)" com rotas, permissões, o token exibido uma vez, o esquema `printAgentToken`, os códigos de erro e as propriedades `zeny.printing.*`)

- [ ] **Step 1:** escrever os três trechos.
- [ ] **Step 2:** `./gradlew build` → BUILD SUCCESSFUL; `.github/scripts/check-api-breaking.sh main` → sem `ERR`.
- [ ] **Step 3:** commit "Documenta o módulo printing (M5 E10, Task 13)".

## Ondas

- **Onda 1:** Tasks 1 e 4 (arquivos disjuntos: `printing/` novo, migrações e `ModularityTest` × `shared/` e o teste novo de `shared/web`).
- Depois, em série: 2 → 3 → 5 → 6 → 7 → 8 → 9 → 10 → 11 → 12 → 13 (6, 7, 9, 10 e 11 regeneram `api/openapi.yaml`; 11 e 12 dependem só da 10, mas a 12 espera a 11 para não disputar o contrato e o `IntegrationTest`).
