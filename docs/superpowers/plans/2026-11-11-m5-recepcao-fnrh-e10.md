# M5 E10 — Agentes, impressoras e fila de impressão: plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** criar o módulo `printing` com agentes de impressão e impressoras, autenticação do agente por token opaco próprio numa cadeia de segurança separada, fila de `PrintJob` com o conteúdo cifrado, entrega por SSE e long-poll, confirmação pelo agente, reenvio dos jobs não confirmados e prazo para o conteúdo.

**Architecture:** o `shared` ganha uma porta genérica de **credencial de serviço** (`ServiceCredentialRealm`, ARCHITECTURE §8.1) e uma segunda `SecurityFilterChain`, ordenada antes da de usuário, que só atende os prefixos de caminho dos realms registrados e autentica `Authorization: Bearer <token opaco>` pelo realm do caminho. O `printing` implementa o realm do agente (`/api/v1/print-agents/me/`), guarda só o SHA-256 do token e expõe a administração (agentes, impressoras, jobs) com permissões de usuário e a API do agente (stream SSE, long-poll, conteúdo e ack) com o principal de serviço. Um job, criado pela `PrintingApi` ou por `POST /api/v1/print-jobs`, guarda o conteúdo cifrado pelo `ContentCipher` e uma posição crescente. A entrega reivindica o job (`PENDING → SENT`) de forma atômica, consultando o banco em intervalo curto (sem sinal em memória). Um job com ShedLock devolve à fila o `SENT` sem ack depois do prazo (posição nova), marca `FAILED` no limite de tentativas, encerra os jobs de impressora desativada e os `PENDING` vencidos, e apaga o conteúdo dos jobs terminados depois do prazo de guarda.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Security 7, Spring MVC (`SseEmitter`, `DeferredResult`), Hibernate 7.4, Flyway 12, ShedLock 7, Micrometer, springdoc 3.1, Testcontainers.

**Spec:** `docs/roadmaps/roadmap-m5-recepcao-fnrh.md`, seção "E10" (e H17, H20, H23 em "Hipóteses e decisões em aberto", "Restrições e invariantes"), `docs/SPEC.md` §5.22 (RF-NOT-03) e §8.2.18, `docs/ARCHITECTURE.md` §3.1, §8.1, §8.3, §10, §11, §12, §13, §14 e D13, e as notas do brain `ZenyHotelManager - Segurança, autenticação e autorização`, `ZenyHotelManager - Tempo real (SSE)`, `ZenyHotelManager - Arquitetura e estrutura de módulos`, `ZenyHotelManager - Eventos e jobs`, `ZenyHotelManager - Convenções de API`, `ZenyHotelManager - Schemas, migrações e views de relatório` e `ZenyHotelManager - Tipos do kernel`.

## Decisões desta etapa

| # | Decisão | Por quê | Custo se errada |
|---|---|---|---|
| D-E10-1 | **Credencial de serviço no `shared`.** Porta na raiz do `shared`: `interface ServiceCredentialRealm { val name: String; val pathPrefix: String; val openApiScheme: String; fun authenticate(token: String): ServicePrincipal? }` e `data class ServicePrincipal(val realm: String, val id: UUID, val credentialStamp: String)` (`credentialStamp` é opaco: muda quando a credencial é trocada). A `SecurityConfiguration` cria, se houver algum realm, a cadeia `serviceSecurityFilterChain` (ordenada antes da de usuário; `securityMatcher` = um `PathPatternRequestMatcher` por realm, `pathPrefix + "**"`), com filtro próprio que escolhe o realm **pelo mesmo matcher**, lê só o cabeçalho `Authorization: Bearer`, autentica e põe o `ServicePrincipal` no contexto; sem token ou com token recusado, `401 UNAUTHENTICATED` (problema RFC 9457, `WWW-Authenticate: Bearer`) pelo `ProblemWriter`; despachos `ASYNC` e `ERROR` liberados como na outra cadeia; sem sessão, CSRF, Basic nem formulário. Um JWT de usuário num caminho de realm é recusado; um token de realm fora do prefixo cai na cadeia de usuário e é recusado. Alternativa descartada: a cadeia dentro do `printing` (duplicaria o `ProblemWriter`, interno do `shared`, e espalharia configuração de segurança por um módulo de plataforma). | H23 (cadeia separada); ARCHITECTURE §8.1 ("credenciais de serviço", no plural) e §3.1 (o `shared` é dono do contexto de segurança); repete o padrão porta no `shared` implementada por módulo (`CurrentActor` pelo `iam`). | Uma porta a mais no kernel; mover depois é refatoração interna. **Vai às "Decisões para o usuário"** da revisão humana da E10 e à ARCHITECTURE §8.1. |
| D-E10-2 | **Token do agente:** `zpa_` + 32 bytes de `SecureRandom` em base64url sem preenchimento; guardado só como SHA-256 em hex (`print_agent.token_hash`, único) com `token_generation` (inteiro que sobe a cada emissão; é o `credentialStamp`, que assim muda a cada rotação mesmo com o relógio parado). Exibido uma vez, na criação e na rotação; a rotação invalida o anterior na hora; a desativação apaga o hash. O realm recusa sem consulta o token sem o prefixo. **Sem `@Idempotent`** na criação e na rotação: a resposta guardada pela idempotência teria o token em claro. | H23; "token só como hash" (Restrições). | Um agente duplicado por repetição de `POST` é desativado pelo admin. Formato publicado ao repositório do agente: **vai às "Decisões para o usuário"**. |
| D-E10-3 | **Trilha do agente:** as escritas do agente (reivindicar e confirmar job) são casos de uso `@Audited` com ator sistema (o `CurrentActor` do `iam` só reconhece JWT de usuário); o retrato do job leva `id`, `printerId`, `status`, `attempts` e `deliveredTo`, nunca o conteúdo nem o token. | `LayerRules.writeUseCasesAreAudited`; a trilha não guarda dado pessoal. | Uma entrada por entrega; volume baixo (um hotel). |
| D-E10-4 | **Ciclo do job:** `PENDING` → `SENT` (reivindicado, `attempts + 1`, `sent_at`, `delivered_to`) → `PRINTED` ou `FAILED` (ack do agente, com `failure_code` opcional `[A-Z][A-Z0-9_]{0,49}`, nunca texto livre). Sem ack até `zeny.printing.ack-timeout` (`PT2M`): volta a `PENDING` com **posição nova**; na reivindicação de número `zeny.printing.max-attempts` (3) sem ack, `FAILED` com `ACK_TIMEOUT`. `PENDING` mais velho que `zeny.printing.pending-ttl` (`PT24H`): `FAILED` com `EXPIRED`. Impressora desativada: seus `PENDING` e `SENT` viram `FAILED` com `PRINTER_INACTIVE` na manutenção. Ack de job que já saiu de `SENT` para esse agente: `409 PRINT_JOB_NOT_ACKNOWLEDGEABLE` se o job é dele; `404 PRINT_JOB_NOT_FOUND` se não é dele ou não existe (não vaza a existência). Ack e manutenção travam a linha (`SELECT … FOR UPDATE`); quem chega depois vê o estado novo. Na reentrega de um job já impresso, o agente confirma de novo sem imprimir (contrato). `status` é `x-extensible-enum` na resposta. | Critérios de ack e reenvio; posição nova faz o job reenviado passar do `Last-Event-ID`; a validade dos `PENDING` evita conteúdo pessoal guardado sem prazo (ARCHITECTURE §8.3, LGPD). | Valores em propriedade; **vão às "Decisões para o usuário"** (decisão aberta da E10). |
| D-E10-5 | **Conteúdo:** só no schema `printing`, coluna `content` (`bytea`) cifrada pelo `ContentCipher` com o id do job como dado associado. Teto da `PrintingApi`: `zeny.printing.max-content-bytes` (5 MiB = `5242880`). Teto da rota manual `POST /print-jobs`: `zeny.printing.max-manual-content-bytes` (700 KiB = `716800`), abaixo do limite de corpo do `@Idempotent` (`zeny.idempotency.max-request-body-size: 1MB`) com o base64; acima dele, `422 PRINT_CONTENT_TOO_LARGE`; corpo acima de 1 MB recebe o `413` do filtro da idempotência, documentado no contrato. Vazio → `422 PRINT_CONTENT_EMPTY`; impressora `A4_PDF` exige `%PDF-` no início (`422 PRINT_CONTENT_NOT_PDF`). Tipo de mídia: `application/pdf` (`A4_PDF`) ou `application/octet-stream` (`THERMAL_ESCPOS`). O conteúdo só é lido pelo agente a que o job foi entregue, com o job `SENT` (senão `404`). Apagado (`NULL`, `content_purged_at`) depois de `zeny.printing.content-retention` (`PT24H`) do fim (`PRINTED`/`FAILED`). | H17; decisão aberta da E10; I2 e I3 da revisão de arquitetura (sem `410`, que o `ErrorKind` não tem). | Documentos maiores que 700 KiB só pela `PrintingApi` (os módulos donos). **Vai às "Decisões para o usuário"**. |
| D-E10-6 | **Entrega:** a reivindicação é atômica (`SELECT … FOR UPDATE SKIP LOCKED` e atualização pela entidade, com `version`), então duas conexões do mesmo agente nunca levam o mesmo `PENDING`. Entram só jobs `PENDING` de impressoras **ativas** do agente autenticado, em ordem de posição, **sem filtro de posição**: o `Last-Event-ID` só decide quais `SENT` sem ack desse agente são reenviados na reconexão (os de posição maior que ele). Assim um job que commitou depois de outro de posição maior, ou de impressora transferida, nunca fica para trás. **SSE** `GET /api/v1/print-agents/me/jobs/stream`: ao conectar, reenvia os `SENT` sem ack de posição maior que o `Last-Event-ID` (todos, sem ele) e reivindica os `PENDING`; depois reivindica a cada `zeny.printing.delivery-poll-interval` (`PT1S`); evento `job`, `id` = posição, `data` JSON com `id`, `printerId`, `documentType`, `mediaType`, `attempts` e `createdAt`; `: heartbeat` a cada `PT15S`; encerra depois de `zeny.printing.stream-max-duration` (`PT30M`) ou quando o agente deixa de ser válido (inativo ou `credentialStamp` diferente). **Long-poll** `GET /api/v1/print-agents/me/jobs?wait=<segundos>` (0 a 30; padrão 25): devolve o que reivindicar; sem nada, espera (assíncrono) consultando no mesmo intervalo até achar ou acabar o `wait` (`200 []`). **Conexões:** no máximo `zeny.printing.max-connections-per-agent` (2) streams e long-polls abertos por agente (a nova encerra a mais antiga), num registro `SmartLifecycle` da `application` que encerra tudo antes do desligamento. Alternativas descartadas: sinal em memória no `afterCommit` com consulta de segurança (dois caminhos de entrega para testar, sem ganho para um hotel) e `LISTEN/NOTIFY` do PostgreSQL (peça nova de runtime e conexão dedicada). | ARCHITECTURE §14 e D13; H23 ("`Last-Event-ID` = a posição persistida do job, sem buffer em memória"); a consulta periódica vale com uma ou várias instâncias; limite e desligamento seguem o `RealtimeHub`. | Até 1 s de latência e uma consulta por segundo por conexão. Formato do evento publicado ao repositório do agente: **vai às "Decisões para o usuário"**. |
| D-E10-7 | **Permissões** (H20): `printing.agents.manage` (cadastro e alteração de agentes e impressoras; nenhum modelo além do Administrador, que recebe todas); `printing.jobs.read` (Gerente e Recepcionista: lista de jobs **e leitura das impressoras**, para escolher a impressora no job manual e nos documentos da E11); `printing.jobs.manage` (criar job manual; Gerente). Nenhuma sensível. | H20; I7 da revisão (quem imprime precisa ver as impressoras). | Um código publicado não muda. |
| D-E10-8 | **Endpoints de administração:** `POST /api/v1/print-agents` (`{name}` → `201` com `token`), `GET /api/v1/print-agents`, `GET /api/v1/print-agents/{id}`, `POST /api/v1/print-agents/{id}/rotate-token` (`200` com `token`), `POST /api/v1/print-agents/{id}/deactivate` (`200`; revoga; rotação de inativo → `422 PRINT_AGENT_INACTIVE`); `POST /api/v1/printers` (`{name, kind, address, agentId}`), `GET /api/v1/printers` e `GET /api/v1/printers/{id}` (`printing.jobs.read`), `PUT /api/v1/printers/{id}` (`If-Match`; `{name, address, agentId}`: o `kind` é fixo depois da criação, porque os jobs pendentes têm o tipo de mídia dele), `POST /api/v1/printers/{id}/deactivate`; `POST /api/v1/print-jobs` (`@Idempotent`, `printing.jobs.manage`, `{printerId, documentType, content}` com o conteúdo em base64) e `GET /api/v1/print-jobs?status=&printerId=` (cursor, `printing.jobs.read`, sem conteúdo). O `{id}` das rotas de agente é `{id:[0-9a-fA-F-]+}` no mapeamento (sem chaves no regex, que o `ApiSecurityTest` corta), para `me` nunca casar com elas. Respostas com token: `Cache-Control: no-store`. Nome do agente e da impressora: 1 a 100 caracteres, sem controle; `address`: 1 a 255, sem controle nem espaço; `documentType`: `[a-z][a-z0-9]*(\.[a-z][a-z0-9-]*)+`, até 100 (o módulo dono escolhe, como `frontdesk.registration-card`). Referência inexistente `400 UNKNOWN_PRINT_AGENT`/`UNKNOWN_PRINTER`, desativada `422 PRINT_AGENT_INACTIVE`/`PRINTER_INACTIVE`. Nada é apagado. | Convenções de API ("a API não apaga nada", `If-Match`, `@Idempotent` em POST com efeito externo; desativada é `422`). | Rotas publicadas não mudam. |
| D-E10-9 | **Contrato:** esquema de segurança `printAgentToken` (HTTP bearer, `bearerFormat: opaque`) nas operações de `/api/v1/print-agents/me/**`, no lugar do `bearerAuth`; o `OpenApiConfiguration` aplica o esquema de cada realm aos caminhos do prefixo dele. Nenhuma operação com `security: []`. A descrição da API passa a citar os dois esquemas. | H23. | Nome publicado: **vai às "Decisões para o usuário"**. |
| D-E10-10 | **Um job de manutenção** `printing-jobs-maintenance` (`@Scheduled` com `fixedDelayString` de `zeny.printing.maintenance-interval` (`PT1M`) e `initialDelayString` de `zeny.printing.maintenance-initial-delay`, mais `@SchedulerLock(lockAtMostFor = "PT10M")`), adiado nos testes por `PRINTING_MAINTENANCE_DEFERRED`, faz o reenvio e o limite, a validade dos `PENDING`, os jobs de impressora inativa e o expurgo; os testes chamam o caso de uso direto com o `MutableClock`. Métrica `zeny_printing_jobs{status}` (gauge por `MeterBinder`: `PENDING` e `SENT` na fila, e `FAILED` só os terminados dentro de `content-retention`, para mostrar falhas recentes e não um total que só cresce). **Sem alerta**: o `printing` só depende de `shared` (ARCHITECTURE §3.1) e não chama `notifications`; um `FAILED` fica na lista e na métrica. | Modelo do projeto (`FnrhSubmissionJob`, `FnrhMetrics`). | Falha de impressão silenciosa até alguém olhar a fila ou a métrica. **Vai às "Decisões para o usuário"**. |
| D-E10-11 | **Último contato do agente sem trilha:** o realm grava `last_seen_at` (no máximo uma vez por minuto) numa transação curta em `infrastructure`, fora da regra `writeUseCasesAreAudited`. Alternativas descartadas: `@Audited` (uma entrada por minuto por agente, sem valor de negócio) e não gravar (o admin perde o "visto por último" que a SPEC §8.2.18 pede). | É registro técnico de contato, como a sessão de dispositivo do `iam`. | Uma escrita sem trilha; a revisão pode pedir a trilha. |

## Revisão de arquitetura

- **Veredito:** aprovado com notas.
- **Rodadas:** 1.
- **Modelo:** opus (`revisor-arquitetura`).
- A inicial bloqueou com 7 Importantes (o `Last-Event-ID` filtrando `PENDING`; o limite de 1 MB do `@Idempotent`; o `410` sem `ErrorKind`; `PENDING` sem prazo; laços sem limite nem desligamento; a onda 1 com arquivo e fixture em comum; quem imprime sem ler as impressoras) e 16 Menores; todos aplicados na rodada 1, inclusive a restauração dos handoffs de E09, E10 e E11 no roadmap (`33c15bc`). A rodada 1 aprovou com notas.
- **Notas Menores** (aplicadas no plano depois da aprovação, sem re-revisão, por serem pontuais): N1 `credentialStamp` por `token_generation`, que muda a cada rotação mesmo com o relógio parado; N2 `{id:[0-9a-fA-F-]+}` sem chaves no regex; N3 o gauge de `FAILED` só conta os terminados dentro da guarda; N4 a espera do long-poll na `application` e o `200 []` do despejo documentado e testado.
- **Decisões que esperam o usuário:** nenhuma para a execução; as de contrato com o repositório do agente e a D-E10-1 vão à revisão humana da E10 ("Notas para o handoff").

## Global Constraints

- Grafo de módulos (ARCHITECTURE §3.1): `printing` depende **só de `shared`** nesta etapa (`@ApplicationModule(allowedDependencies = ["shared"])`, teste dele no `ModularityTest`); não importa `iam` nem `notifications`, não lê tabela de outro schema, sem FK entre schemas; nunca escuta eventos de negócio.
- Estrutura do módulo (nota "Arquitetura e estrutura de módulos"): raiz com `PrintingModule.kt` (`@PackageInfo`), `PrintingApi`, `PrintingPermissions` e `PrintingTypes.kt` (os enums que a web e a API pública usam, `PrinterKind` e `PrintJobStatus`, e os `PrintingErrorCodes`, no modelo do `FnrhEnvironment` e do `FnrhErrorCodes`); `domain` sem Spring; `application` com casos de uso e portas; `infrastructure`; `web` só controllers e DTOs, falando só com a `application` e a raiz. Toda escrita da `application` é `@Audited` + `@Transactional`.
- Limite de tarefa: até 3 arquivos de produção (fontes, migrações e `application.yaml`; só o `api/openapi.yaml` gerado não conta).
- Migrações em `src/main/resources/db/migration/printing/`, versão posterior a `V2026_11_10_1100`, uma de `GRANT` ao `"${app_role}"` no modelo de `fnrh/V2026_11_04_1001__grant_fnrh_to_app_role.sql`; o schema é criado pelo Flyway pelo nome da pasta.
- Token do agente só como hash; nunca em log, trilha, resposta (salvo a criação e a rotação), `toString` nem idempotência. Conteúdo do job cifrado na coluna e nunca em log, trilha nem resposta de usuário.
- Instantes pelo `Clock`; nenhum `Thread.sleep` em teste para ordenar passos (latch, espera com prazo ou o `MutableClock`).
- Convenções de API: `@ApiV1Controller`, `@Operation(operationId)` único, `@Tag(name = "Printing")`, erros RFC 9457 com códigos em `PrintingErrorCodes`, cursor (`PageParams`/`CursorPage`), `If-Match` com `ETags`, `@Idempotent` só em JSON síncrono. `api/openapi.yaml` só por `./gradlew updateOpenApi`.
- `ApiSecurityTest` atualizado com cada combinação token × caminho; nenhum endpoint público novo.
- Documentação, mensagens e commits em pt-BR; código, API e banco em inglês. Commits com `Soul: <soul>`.
- Os testes compartilham o banco: cada teste cria os próprios agentes e impressoras e não conta linhas em valor absoluto.
- `./gradlew build` verde e `.github/scripts/check-api-breaking.sh main` sem `ERR`.

## Review Focus

1. **Agente desativado ou com token rotacionado com stream aberto:** a conexão para de entregar e encerra (`credentialStamp` diferente ou agente inativo) (teste na Task 11).
2. **Duas conexões do mesmo agente** (SSE e long-poll ao mesmo tempo): nenhum `PENDING` vai às duas (teste concorrente na Task 10).
3. **Impressora movida para outro agente com jobs pendentes e enviados:** o agente novo recebe os `PENDING` pelo SSE e pelo long-poll, mesmo com posição menor que o `Last-Event-ID` dele; o ack de um `SENT` do agente antigo vira `404` (testes nas Tasks 10 e 11).
4. **Job reenviado depois do timeout e ack atrasado do primeiro envio:** o ack do job que voltou a `PENDING` é `409`; depois de reivindicado de novo, o ack é aceito uma vez (teste na Task 12).
5. **Token com espaço, quebra de linha, prefixo de outro realm, `Bearer` repetido; token de agente em `POST /api/v1/print-agents/me/rotate-token`:** `401` ou `404`, nunca `200` numa rota de usuário, nunca `500` e nunca o token no log (testes nas Tasks 4 e 7).

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
- Produces: `printing.print_agent (id uuid pk, name text not null, token_hash char(64) unique null, token_generation int not null default 0, active boolean not null, last_seen_at timestamptz null, created_at timestamptz not null, version bigint not null)`; `printing.printer (id uuid pk, name text not null, kind varchar(20) not null CHECK in ('THERMAL_ESCPOS','A4_PDF'), address text not null, agent_id uuid not null FK print_agent, active boolean not null, version bigint not null)`; `printing.print_job (id uuid pk, printer_id uuid not null FK printer, document_type text not null, status varchar(20) not null CHECK in ('PENDING','SENT','PRINTED','FAILED'), position bigint not null unique, attempts int not null CHECK >= 0, content bytea null, content_purged_at timestamptz null, media_type varchar(50) not null, created_at timestamptz not null, sent_at timestamptz null, delivered_to uuid null, finished_at timestamptz null, failure_code varchar(50) null, version bigint not null)`; sequence `printing.print_job_position_seq`; índices `(printer_id, status, position)`, `(status, sent_at)`, `(status, created_at)` e `(status, finished_at) WHERE content IS NOT NULL`. CHECKs: `active OR token_hash IS NULL`; `content` e `content_purged_at` nunca os dois preenchidos; `finished_at` presente se e só se `status IN ('PRINTED','FAILED')`; `delivered_to` e `sent_at` presentes em `SENT`. Comentário no topo da migração no padrão das demais.

- [x] **Step 1:** teste do `printing` no `ModularityTest` (modelo: o do `fnrh`) e `PrintingSchemaTest` (`@IntegrationTest`): o papel da aplicação tem `SELECT, INSERT, UPDATE, DELETE` nas três tabelas e `USAGE` na sequence; `token_hash` repetido viola o único; `active = false` com `token_hash` viola o CHECK; job `PENDING` com `finished_at` viola o CHECK; `SENT` sem `delivered_to` viola o CHECK.
- [x] **Step 2:** ver falhar: `./gradlew test --tests 'com.zeny.hotel.ModularityTest' --tests 'com.zeny.hotel.printing.*'`.
- [x] **Step 3:** `PrintingModule.kt` (modelo `fnrh/FnrhModule.kt`, `allowedDependencies = ["shared"]`, KDoc citando ARCHITECTURE §14 e D13) e as duas migrações.
- [x] **Step 4:** ver passar (o mesmo comando, mais o `MigrationConventionTest`).
- [x] **Step 5:** commit "Cria o módulo printing e as tabelas de agentes, impressoras e jobs (M5 E10, Task 1)".

---

### Task 2: Tipos públicos e domínio de agente e impressora

**Estimativa:** 35 min. **Depende de:** Task 1. **Risco:** alto (token).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/printing/PrintingTypes.kt` (`enum class PrinterKind(val mediaType: String) { THERMAL_ESCPOS("application/octet-stream"), A4_PDF("application/pdf") }`, `enum class PrintJobStatus { PENDING, SENT, PRINTED, FAILED }` e `object PrintingErrorCodes` com `PRINT_AGENT_INACTIVE`, `PRINTER_INACTIVE`, `UNKNOWN_PRINT_AGENT`, `UNKNOWN_PRINTER`, `PRINT_JOB_NOT_FOUND`, `PRINT_JOB_NOT_ACKNOWLEDGEABLE`, `PRINT_CONTENT_TOO_LARGE`, `PRINT_CONTENT_EMPTY`, `PRINT_CONTENT_NOT_PDF`, `INVALID_FAILURE_CODE`)
- Create: `src/main/kotlin/com/zeny/hotel/printing/domain/PrintAgent.kt`
- Create: `src/main/kotlin/com/zeny/hotel/printing/domain/Printer.kt`
- Test: `src/test/kotlin/com/zeny/hotel/printing/domain/PrintAgentTest.kt`, `PrinterTest.kt`

**Interfaces:**
- Produces (entidades JPA mapeadas nas tabelas da Task 1, `toString` sem hash):
  - `PrintAgent.create(name: String, now: Instant): Pair<PrintAgent, String>` (o token em claro, uma vez); `rotateToken(now): String` (inativo → `BusinessRuleException(PRINT_AGENT_INACTIVE)`, `422`); `deactivate()` (apaga o hash); `rename(name)`; `touch(now: Instant): Boolean` (só muda se o último contato tiver mais de 1 min); `val credentialStamp: String` (o `tokenGeneration` como texto; `create` e `rotateToken` o incrementam); `companion fun hashOf(token: String): String` (SHA-256 em hex, 64 caracteres) e `const val TOKEN_PREFIX = "zpa_"`.
  - `Printer.create(name, kind: PrinterKind, address, agentId: UUID)`, `update(name, address, agentId)` (o `kind` não muda), `deactivate()`.

- [x] **Step 1:** testes unitários: o token tem o prefixo, 47 caracteres e só base64url; dois tokens diferem; `hashOf` tem 64 caracteres hex; a rotação troca o hash e o `credentialStamp`, e o anterior deixa de casar; desativar apaga o hash; rotacionar inativo falha com `PRINT_AGENT_INACTIVE`; `touch` não muda dentro de 1 min; nome (1 a 100) e endereço (1 a 255, sem espaço) com caractere de controle recusados.
- [x] **Step 2–4:** ver falhar, implementar, ver passar: `./gradlew test --tests 'com.zeny.hotel.printing.domain.*' --tests 'com.zeny.hotel.architecture.*'`.
- [x] **Step 5:** commit "Acrescenta os tipos públicos e o domínio de agentes e impressoras (M5 E10, Task 2)".

---

### Task 3: Domínio do job e propriedades da fila

**Estimativa:** 35 min. **Depende de:** Task 2. **Risco:** alto (máquina de estados).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/printing/domain/PrintJob.kt`
- Create: `src/main/kotlin/com/zeny/hotel/printing/infrastructure/PrintingProperties.kt` (`@ConfigurationProperties("zeny.printing")`, validado, todos positivos: `maxContentBytes` 5242880, `maxManualContentBytes` 716800 (e não maior que `maxContentBytes`), `contentRetention` PT24H, `pendingTtl` PT24H, `ackTimeout` PT2M, `maxAttempts` 3, `longPollMaxWait` PT30S, `deliveryPollInterval` PT1S, `streamHeartbeat` PT15S, `streamMaxDuration` PT30M, `maxConnectionsPerAgent` 2, `maintenanceInterval` PT1M, `maintenanceInitialDelay` PT1M)
- Modify: `src/main/resources/application.yaml` (os valores acima)
- Test: `src/test/kotlin/com/zeny/hotel/printing/domain/PrintJobTest.kt`, `src/test/kotlin/com/zeny/hotel/printing/PrintingPropertiesTest.kt`

**Interfaces:**
- Produces: `PrintJob.create(id: UUID, printerId, kind: PrinterKind, documentType, sealedContent: ByteArray, position: Long, now: Instant)`; `claim(agentId: UUID, now)` (`PENDING → SENT`); `acknowledge(agentId, printed: Boolean, failureCode: String?, now)` (`SENT` do mesmo agente → `PRINTED`/`FAILED`; outro estado → `PRINT_JOB_NOT_ACKNOWLEDGEABLE`; `failureCode` fora de `[A-Z][A-Z0-9_]{0,49}` → `INVALID_FAILURE_CODE`); `expireAck(now, maxAttempts: Int, newPosition: Long)` (`SENT` → `PENDING` com a posição nova, ou `FAILED`/`ACK_TIMEOUT` com `attempts >= maxAttempts`); `fail(code: String, now)` (de `PENDING` ou `SENT`, para `EXPIRED` e `PRINTER_INACTIVE`); `purgeContent(now)` (só em `PRINTED`/`FAILED`); `isDeliveredTo(agentId)`; `toString` sem conteúdo. `PrintingProperties` como acima.

- [x] **Step 1:** testes: cada operação em cada estado (válida e inválida); `acknowledge` de outro agente falha; `expireAck` na 3ª tentativa vira `FAILED/ACK_TIMEOUT`; `fail` em `PRINTED` falha; `purgeContent` em `PENDING` falha; propriedade zero ou negativa, e `maxManualContentBytes > maxContentBytes`, recusam a partida (`ApplicationContextRunner`).
- [x] **Step 2–4:** ver falhar, implementar, ver passar: `./gradlew test --tests 'com.zeny.hotel.printing.*'`.
- [x] **Step 5:** commit "Acrescenta o domínio do job de impressão e as propriedades da fila (M5 E10, Task 3)".

---

### Task 4: Credencial de serviço no `shared`

**Estimativa:** 45 min. **Depende de:** Task 3 (em série, para não dividir o build e o banco de testes com outra tarefa). **Risco:** alto (segurança).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/shared/ServiceCredentials.kt` (`ServiceCredentialRealm`, `ServicePrincipal`)
- Modify: `src/main/kotlin/com/zeny/hotel/shared/web/SecurityConfiguration.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/shared/web/OpenApiConfiguration.kt` (esquema por realm nas operações do prefixo; descrição da API citando os dois esquemas)
- Test: `src/test/kotlin/com/zeny/hotel/shared/web/ServiceCredentialChainTest.kt` (realm e controller **só de teste**, por exemplo `/api/v1/test-service/me/ping`, num `@TestConfiguration` importado só por esse teste)

**Interfaces:**
- Produces: as assinaturas de D-E10-1. `pathPrefix` começa por `/api/v1/` e termina em `/`; a partida falha se dois realms se sobrepõem. O `Authentication` da cadeia tem o `ServicePrincipal` como `principal`, sem autoridades.

- [x] **Step 1:** testes: token aceito no caminho do realm → `200` com o principal certo; o caminho do prefixo sem a barra final (`/api/v1/test-service/me`) também é da cadeia de serviço; sem token → `401` problema com `WWW-Authenticate: Bearer`; token recusado → `401`; JWT de usuário válido no caminho do realm → `401`; token do realm num endpoint de usuário autenticado (por exemplo `GET /api/v1/fnrh/status`) → `401`; token com espaço, quebra de linha, `Bearer` repetido ou esquema `Basic` → `401`, nunca `500`; o token não aparece no log (`OutputCaptureExtension`).
- [x] **Step 2:** ver falhar: `./gradlew test --tests 'com.zeny.hotel.shared.web.*'`.
- [x] **Step 3:** implementar a porta, a cadeia (bean só com realms presentes, ordenada antes da de usuário, matcher e filtro de D-E10-1) e o esquema no OpenAPI; atualizar o KDoc da `SecurityConfiguration` (duas cadeias).
- [x] **Step 4:** ver passar, mais `com.zeny.hotel.ModularityTest` e `com.zeny.hotel.architecture.*`; `./gradlew checkOpenApi` (sem realm de produção, o contrato não muda).
- [x] **Step 5:** commit "Acrescenta a credencial de serviço ao shared, numa cadeia de segurança própria (M5 E10, Task 4)".

---

### Task 5: Administração de agentes e impressoras (aplicação e persistência)

**Estimativa:** 45 min. **Depende de:** Task 4. **Risco:** alto (credencial).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/printing/PrintingPermissions.kt` (definições de D-E10-7; modelo `FnrhPermissions`)
- Create: `src/main/kotlin/com/zeny/hotel/printing/application/PrintAgentsService.kt` (com as portas `PrintAgentStore` e `PrinterStore` e o `@Configuration` do bean `ModulePermissions` no mesmo arquivo, modelo `FnrhPermissionsConfiguration`)
- Create: `src/main/kotlin/com/zeny/hotel/printing/infrastructure/PrintingPersistence.kt`
- Test: `src/test/kotlin/com/zeny/hotel/printing/PrintAgentsServiceTest.kt`

**Interfaces:**
- Produces: `PrintAgentsService.createAgent(name): CreatedAgent(view, token)`, `agents(): List<PrintAgentView>`, `agent(id)`, `rotateToken(id): CreatedAgent`, `deactivateAgent(id): PrintAgentView`, `identity(agentId: UUID): PrintAgentIdentity(id, name)` (sem permissão: serve ao próprio agente autenticado), `createPrinter(NewPrinter)`, `printers(): List<PrinterView>` e `printer(id)` (com `printing.jobs.read`), `updatePrinter(id, expectedVersion, PrinterChange)`, `deactivatePrinter(id)`. Escritas `@Audited` (`printing.agent.create`, `printing.agent.rotate-token`, `printing.agent.deactivate`, `printing.printer.create`, `printing.printer.update`, `printing.printer.deactivate`; retratos sem o hash) e conferindo `printing.agents.manage`. `PrintAgentView(id, name, active, lastSeenAt, version)`, `PrinterView(id, name, kind, address, agentId, active, version)`, `NewPrinter(name, kind, address, agentId)`, `PrinterChange(name, address, agentId)`. Portas: `PrintAgentStore.byTokenHash(hash): PrintAgent?`, `byId(id)`, `save(agent)`; `PrinterStore.byId(id)`, `all()`, `save(printer)`.

- [x] **Step 1:** testes de integração do serviço: criar devolve o token uma vez e a coluna tem só o hash; a rotação invalida o anterior; desativar apaga o hash; impressora com agente inexistente → `UNKNOWN_PRINT_AGENT`, com agente inativo → `PRINT_AGENT_INACTIVE`; `updatePrinter` com versão velha → `412`; o Gerente e a Recepcionista leem as impressoras e não as alteram (`403`); a trilha das seis ações não contém o token nem o hash.
- [x] **Step 2–4:** ver falhar, implementar, ver passar: `./gradlew test --tests 'com.zeny.hotel.printing.*' --tests 'com.zeny.hotel.architecture.*' --tests 'com.zeny.hotel.ModularityTest'`.
- [x] **Step 5:** commit "Acrescenta a administração de agentes e impressoras ao printing (M5 E10, Task 5)".

---

### Task 6: Endpoints de administração

**Estimativa:** 40 min. **Depende de:** Task 5. **Risco:** alto (contrato público, token na resposta).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/printing/web/PrintAgentsController.kt` (`{id:[0-9a-fA-F-]+}` no mapeamento; conferir que o teste anônimo do `ApiSecurityTest` sonda o caminho certo)
- Create: `src/main/kotlin/com/zeny/hotel/printing/web/PrintersController.kt`
- Modify: `api/openapi.yaml` (gerado)
- Test: `src/test/kotlin/com/zeny/hotel/printing/PrintAgentsApiTest.kt`, `PrintersApiTest.kt`

**Interfaces:**
- Consumes: `PrintAgentsService` (Task 5). Produces: as rotas de agentes e impressoras de D-E10-8; `PrintAgentResponse` (sem token), `PrintAgentTokenResponse` (com `token`, descrito como exibido uma única vez); `ETag` no `PrinterResponse`; `kind` como `enum` fechado na criação e `x-extensible-enum` na resposta.

- [x] **Step 1:** testes de API: criação `201` com `token` e `Cache-Control: no-store`; `GET` sem token nem hash; rotação devolve token novo; desativação; o Gerente e a Recepcionista reais recebem `403` nas rotas de agentes e em `POST`/`PUT` de impressoras e `200` no `GET` de impressoras; nome inválido `400` no campo; `PUT` de impressora sem `If-Match` `428`, velho `412`; `PUT` com `kind` no corpo não muda o `kind` (ignorado ou `400`; a escolha fica no relatório).
- [x] **Step 2–4:** ver falhar, implementar, ver passar: `./gradlew test --tests 'com.zeny.hotel.printing.*' --tests 'com.zeny.hotel.shared.web.ApiSecurityTest'`; `./gradlew updateOpenApi`.
- [x] **Step 5:** commit "Acrescenta os endpoints de agentes e impressoras (M5 E10, Task 6)".

---

### Task 7: Realm do agente

**Estimativa:** 35 min. **Depende de:** Task 6. **Risco:** alto (autenticação).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/printing/infrastructure/PrintAgentRealm.kt` (`name = "print-agent"`, `pathPrefix = "/api/v1/print-agents/me/"`, `openApiScheme = "printAgentToken"`; `authenticate` recusa sem o prefixo `zpa_`, procura pelo hash, exige agente ativo, devolve o `credentialStamp` e grava o `touch` numa transação própria curta, D-E10-11)
- Create: `src/main/kotlin/com/zeny/hotel/printing/web/PrintAgentSelfController.kt` (`GET /api/v1/print-agents/me/identity` → `{id, name}` pela `PrintAgentsService.identity`)
- Modify: `src/test/kotlin/com/zeny/hotel/shared/web/ApiSecurityTest.kt`
- Modify: `api/openapi.yaml` (gerado: esquema `printAgentToken`)
- Test: `src/test/kotlin/com/zeny/hotel/printing/PrintAgentRealmTest.kt`

- [x] **Step 1:** testes: token de agente em `/print-agents/me/identity` `200`; em `/print-agents` (administração) `401`; em `POST /print-agents/me/rotate-token` nunca `200` (Review Focus 5); JWT de usuário em `/print-agents/me/identity` `401`; token revogado e token anterior à rotação `401`; `last_seen_at` gravado no primeiro uso e não regravado dentro de 1 min (`MutableClock`); o contrato tem `printAgentToken` em `components.securitySchemes`, só nas operações de `/api/v1/print-agents/me/**`, e nenhuma operação com `security: []`; os únicos mapeamentos sob `/api/v1/print-agents/me/` são de controllers do agente (enumeração dos mapeamentos).
- [x] **Step 2–4:** ver falhar, implementar, ver passar; `./gradlew updateOpenApi`.
- [x] **Step 5:** commit "Autentica o agente de impressão pelo token próprio (M5 E10, Task 7)".

---

### Task 8: Fila de jobs: `PrintingApi`, aplicação e persistência

**Estimativa:** 45 min. **Depende de:** Task 7. **Risco:** alto (cifragem do conteúdo, concorrência).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/printing/PrintingApi.kt` (`interface PrintingApi { fun enqueue(job: NewPrintJob): UUID }`, `class NewPrintJob(val printerId: UUID, val documentType: String, val content: ByteArray)` com `toString` sem conteúdo; sem conferência de permissão: quem chama já se autorizou)
- Create: `src/main/kotlin/com/zeny/hotel/printing/application/PrintJobsService.kt` (implementa `PrintingApi`; `create(job)` para a rota, com `printing.jobs.manage` e o teto manual; `jobs(status, printerId, page)` com `printing.jobs.read`; valida D-E10-5; cifra com o `ContentCipher` e o id do job como dado associado; posição pela sequence; porta `PrintJobStore` no mesmo arquivo; escrita `@Audited` `printing.job.create`, retrato sem conteúdo)
- Create: `src/main/kotlin/com/zeny/hotel/printing/infrastructure/PrintJobPersistence.kt`
- Test: `src/test/kotlin/com/zeny/hotel/printing/PrintJobsServiceTest.kt`

**Interfaces:**
- Produces: `PrintingApi.enqueue`; `PrintJobView(id, printerId, documentType, status: PrintJobStatus, attempts, createdAt, sentAt, finishedAt, failureCode)` (sem conteúdo); `PrintJobStore` com `nextPosition(): Long`, `save(job)`, `lockById(id): PrintJob?` (`FOR UPDATE`), `page(status, printerId, page): CursorPage<PrintJob>`, `claimPending(agentId, limit): List<PrintJob>` (`FOR UPDATE SKIP LOCKED`, só impressoras ativas do agente, ordem de posição, sem filtro de posição), `unackedSent(agentId, afterPosition): List<PrintJob>`, `expiredSent(before, limit)`, `stalePending(before, limit)`, `ofInactivePrinters(limit)`, `purgeable(before, limit)`, `countByStatus(): Map<PrintJobStatus, Long>`.

- [x] **Step 1:** testes: `enqueue` grava a coluna `content` diferente do conteúdo e decifrável com o `ContentCipher` e o id; posições crescentes; acima de 5 MiB pela `PrintingApi` → `PRINT_CONTENT_TOO_LARGE`, e acima de 700 KiB pela `create` → o mesmo código; vazio → `PRINT_CONTENT_EMPTY`; não-PDF para `A4_PDF` → `PRINT_CONTENT_NOT_PDF`; impressora inexistente → `UNKNOWN_PRINTER`, inativa → `PRINTER_INACTIVE`; `jobs` exige `printing.jobs.read`, `create` exige `printing.jobs.manage` e `enqueue` não exige nada; `claimPending` concorrente em duas threads nunca devolve o mesmo job; `claimPending` devolve um `PENDING` de posição menor que a de um `SENT` já entregue; a trilha da criação não tem o conteúdo.
- [x] **Step 2–4:** ver falhar, implementar, ver passar: `./gradlew test --tests 'com.zeny.hotel.printing.*' --tests 'com.zeny.hotel.architecture.*'`.
- [x] **Step 5:** commit "Acrescenta a fila de jobs de impressão e a PrintingApi (M5 E10, Task 8)".

---

### Task 9: Endpoints da fila

**Estimativa:** 30 min. **Depende de:** Task 8. **Risco:** alto (contrato público, idempotência).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/printing/web/PrintJobsController.kt` (`POST /api/v1/print-jobs` com `@Idempotent` e o conteúdo em base64; `GET /api/v1/print-jobs?status=&printerId=` com cursor; a descrição do `POST` documenta o teto de 700 KiB e o `413` acima de 1 MB de corpo)
- Modify: `api/openapi.yaml` (gerado)
- Test: `src/test/kotlin/com/zeny/hotel/printing/PrintJobsApiTest.kt`

- [x] **Step 1:** testes: `POST` com `Idempotency-Key` → `201`; a repetição com a mesma chave devolve o mesmo job e não cria outro; sem a chave → o erro do `@Idempotent`; base64 inválido → `400` no campo; conteúdo de 716800 bytes → `201`, de 716801 → `422 PRINT_CONTENT_TOO_LARGE`, e corpo acima de 1 MB → `413`; a Recepcionista lê (`GET` sem conteúdo) e não cria (`403`); `status` na resposta é `x-extensible-enum` no contrato.
- [x] **Step 2–4:** ver falhar, implementar, ver passar; `./gradlew updateOpenApi`.
- [x] **Step 5:** commit "Acrescenta os endpoints da fila de impressão (M5 E10, Task 9)".

---

### Task 10: Entrega ao agente: long-poll, conteúdo, ack e conexões

**Estimativa:** 45 min. **Depende de:** Task 9. **Risco:** alto (concorrência, autorização por agente).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/printing/application/PrintAgentJobsService.kt` (`claim(principal, limit)`, `awaitJobs(principal, wait: Duration, connection): List<…>` (a espera do long-poll com consulta a cada `deliveryPollInterval`, na `application`, como o laço do SSE; despejada pelo limite de conexões, devolve a lista vazia na hora), `unacknowledged(principal, afterPosition)`, `content(principal, jobId): JobContent(mediaType, bytes)` (só `SENT` entregue a esse agente; senão `404`), `acknowledge(principal, jobId, printed, failureCode)` (`lockById`); `@Audited` com ator sistema nas escritas (`printing.job.claim`, `printing.job.ack`), retrato de D-E10-3; cada chamada confere que o agente segue ativo e com o mesmo `credentialStamp`, senão `401`)
- Create: `src/main/kotlin/com/zeny/hotel/printing/application/PrintAgentConnections.kt` (`SmartLifecycle`: registra cada stream e long-poll por agente, encerra a mais antiga acima de `maxConnectionsPerAgent` e todas no `stop()`, antes do *graceful shutdown*; modelo `RealtimeHub`)
- Create: `src/main/kotlin/com/zeny/hotel/printing/web/PrintAgentJobsController.kt` (`GET /api/v1/print-agents/me/jobs?wait=` assíncrono por `DeferredResult`, que só liga a resposta ao `awaitJobs` numa *virtual thread*; o contrato documenta o `200 []` do despejo; `GET …/jobs/{id}/content`; `POST …/jobs/{id}/ack` com `{result: PRINTED|FAILED, failureCode?}`; o agente vem do `ServicePrincipal`)
- Modify: `api/openapi.yaml` (gerado)
- Test: `src/test/kotlin/com/zeny/hotel/printing/PrintAgentJobsApiTest.kt`

- [x] **Step 1:** testes: o agente A recebe só os jobs das impressoras dele; o B não os recebe, não lê o conteúdo nem confirma (`404`); `wait=0` sem job → `200 []`; long-poll que espera recebe o job criado durante a espera (espera com prazo, sem `sleep` fixo); duas reivindicações simultâneas do mesmo agente nunca levam o mesmo job (Review Focus 2); ack `PRINTED` e `FAILED` mudam o status (critério); ack de job terminado → `409`; impressora movida de A para B com um `PENDING` e um `SENT`: B recebe o `PENDING` pelo long-poll, e o ack de A no `SENT` → `404` (Review Focus 3); conteúdo de `SENT` próprio com o tipo de mídia certo e igual ao enviado, e de `PENDING` ou `PRINTED` → `404`; `wait` fora de 0..30 → `400`; um terceiro long-poll do mesmo agente faz o mais antigo responder `200 []` na hora; a trilha do claim e do ack não tem conteúdo nem token.
- [x] **Step 2–4:** ver falhar, implementar, ver passar; `./gradlew updateOpenApi`.
- [x] **Step 5:** commit "Acrescenta a entrega dos jobs ao agente por long-poll, conteúdo e ack (M5 E10, Task 10)".

---

### Task 11: Stream SSE do agente

**Estimativa:** 45 min. **Depende de:** Task 10. **Risco:** alto (concorrência, conexões longas).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/printing/application/PrintAgentStreams.kt` (o laço de D-E10-6 numa *virtual thread*: reenvia os `SENT` sem ack depois do `Last-Event-ID`, reivindica os `PENDING`, consulta a cada `deliveryPollInterval`, `: heartbeat`, encerra em `streamMaxDuration`, na desconexão, quando o agente deixa de ser válido e no `stop()` do `PrintAgentConnections`; escreve por uma porta de quadros, como o `StreamSink` do `shared`)
- Create: `src/main/kotlin/com/zeny/hotel/printing/web/PrintAgentStreamController.kt` (`GET /api/v1/print-agents/me/jobs/stream`, `text/event-stream`, `Last-Event-ID`; só liga o `SseEmitter` ao laço; `X-Accel-Buffering: no` e `Cache-Control: no-store`; modelo `shared/web/StreamController.kt`)
- Modify: `api/openapi.yaml` (gerado)
- Test: `src/test/kotlin/com/zeny/hotel/printing/PrintAgentStreamTest.kt` (no modelo do `RealtimeStreamTest`: `RANDOM_PORT` e `SseTestClient`; intervalos encurtados por propriedade)

- [x] **Step 1:** testes: conectar recebe os pendentes do agente em ordem de posição, `id` = posição; job criado depois chega; reconectar com `Last-Event-ID` = N reenvia só os `SENT` sem ack de posição maior que N e ainda entrega um `PENDING` de posição menor que N (critério e I1); impressora transferida com pendentes entrega ao agente novo pelo stream (Review Focus 3); outro agente não recebe; agente desativado ou com token rotacionado com stream aberto: nada mais é entregue e a conexão encerra (Review Focus 1); heartbeat aparece; um terceiro stream do mesmo agente encerra o mais antigo.
- [x] **Step 2–4:** ver falhar, implementar, ver passar; `./gradlew updateOpenApi`.
- [x] **Step 5:** commit "Acrescenta o stream SSE do agente de impressão (M5 E10, Task 11)".

---

### Task 12: Manutenção da fila e métrica

**Estimativa:** 40 min. **Depende de:** Task 11. **Risco:** alto (job agendado, estados).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/printing/application/PrintJobsMaintenance.kt` (`run(now)`: `expireUnacknowledged`, `expireStalePending`, `failInactivePrinters` e `purgeContent`, `@Audited` com ator sistema, em lotes)
- Create: `src/main/kotlin/com/zeny/hotel/printing/infrastructure/PrintJobsMaintenanceJob.kt` (`@Scheduled` pelas propriedades + `@SchedulerLock(name = "printing-jobs-maintenance", lockAtMostFor = "PT10M")`)
- Create: `src/main/kotlin/com/zeny/hotel/printing/infrastructure/PrintingMetrics.kt` (`MeterBinder`, gauge `zeny_printing_jobs{status}`: `PENDING` e `SENT` na fila, `FAILED` só com `finished_at` dentro de `content-retention`)
- Modify: `src/test/kotlin/com/zeny/hotel/testsupport/IntegrationTest.kt` (`PRINTING_MAINTENANCE_DEFERRED = "zeny.printing.maintenance-initial-delay=PT24H"` na lista)
- Test: `src/test/kotlin/com/zeny/hotel/printing/PrintJobsMaintenanceTest.kt`, `PrintingMetricsTest.kt`

- [x] **Step 1:** testes com o `MutableClock` (relógio devolvido no fim): `SENT` sem ack depois de `PT2M` volta a `PENDING` com posição maior e é entregue de novo; na 3ª reivindicação sem ack vira `FAILED/ACK_TIMEOUT` (critério); o ack atrasado de um job que voltou a `PENDING` é `409`, e depois de reivindicado de novo o ack é aceito uma vez e o seguinte é `409` (Review Focus 4); `PENDING` mais velho que `PT24H` vira `FAILED/EXPIRED`; impressora desativada leva os `PENDING` e `SENT` dela a `FAILED/PRINTER_INACTIVE`; conteúdo de `PRINTED` e de `FAILED` apagado depois de `PT24H` (coluna nula e `content_purged_at`), e `PENDING`/`SENT` nunca são apagados; o gauge aparece em `/actuator/prometheus` e muda com a fila, e um `FAILED` mais velho que a guarda sai da contagem.
- [x] **Step 2–4:** ver falhar, implementar, ver passar.
- [x] **Step 5:** commit "Acrescenta a manutenção da fila de impressão e a métrica (M5 E10, Task 12)".

---

### Task 13: Documentação

**Estimativa:** 25 min. **Depende de:** Task 12. **Risco:** normal (só documentação).

**Files:**
- Modify: `docs/ARCHITECTURE.md` (§8.1: a credencial de serviço do `shared` e o token do agente, com a alternativa descartada de D-E10-1; §8.3: o conteúdo do job cifrado e a re-cifragem pendente da M8 E15; §10: o segundo SSE, por consulta ao banco e não pelo broadcaster em processo; §12: a linha "Impressão" com o long-poll; §13: o job `printing-jobs-maintenance`; §14: entrega, posição, ack, reenvio, validade e guarda do conteúdo)
- Modify: `docs/SPEC.md` (§8.2.18: `PrintAgent.active`; `Printer.kind` com os dois valores; `PrintJob` com `position`, `deliveredTo`, `finishedAt`, `failureCode`)
- Modify: `README.md` (seção "API": parágrafo "Impressão (M5 E10, módulo `printing`)" com rotas, permissões, o token exibido uma vez, o esquema `printAgentToken`, o formato do evento SSE, os códigos de erro, a métrica e as propriedades `zeny.printing.*`)

- [x] **Step 1:** escrever os trechos.
- [x] **Step 2:** `./gradlew build` → BUILD SUCCESSFUL; `.github/scripts/check-api-breaking.sh main` → sem `ERR`.
- [x] **Step 3:** commit "Documenta o módulo printing (M5 E10, Task 13)".

## Ondas

Todas em série, na branch da etapa: 1 → 2 → … → 13. Não há par de tarefas sem arquivo, teste ou fixture em comum que valha uma onda (I6 da revisão de arquitetura).

## Notas para o handoff

- "Decisões para o usuário" (revisão humana da E10): D-E10-1 (porta no `shared`), D-E10-2 (formato do token), D-E10-4 e D-E10-5 (valores de reenvio, validade, teto e guarda), D-E10-6 (formato do evento SSE, corpo do ack, consulta ao banco), D-E10-9 (nome do esquema) e D-E10-10 (sem alerta de falha). O formato do evento, o corpo do ack, o token e o esquema são contrato com o repositório do agente.
- Fica para depois: o id do agente no MDC dos logs (o `ActorLogging` só conhece usuário).
