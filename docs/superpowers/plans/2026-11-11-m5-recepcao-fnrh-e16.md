# M5 E16 — Limpar a credencial do Serpro: plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** dar ao administrador uma ação explícita, com `If-Match` e trilha, que apaga usuário, senha e CPF solicitante padrão da configuração da FNRH e deixa a integração desligada.

**Architecture:** um método de domínio `FnrhSettings.clearCredentials()` zera as três colunas cifradas e desliga; o `FnrhSettingsService.clearCredentials(expectedVersion)` (`@Audited`, `@Transactional`) confere `fnrh.settings.manage` e a versão e grava o retrato antes/depois (o `FnrhSettingsAuditState` da E07, já sem segredo); o controller expõe `POST /api/v1/fnrh/settings/clear-credentials`, que responde como o `PUT` (corpo `FnrhSettingsResponse` e `ETag` novo). Nada muda no envio: o `SettingsFnrhConnectionSource` já devolve `null` com a integração desligada e o adaptador relê a conexão a cada chamada, então o job para (`NOT_CONFIGURED` → `STOP`) e as submissões ficam `PENDING` sem gastar tentativa (D-E08-5).

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Security 7, Hibernate 7.4, springdoc 3.1, WireMock, Testcontainers.

**Spec:** `docs/roadmaps/roadmap-m5-recepcao-fnrh.md`, seção "E16" (e o handoff da E08, nota M-3), `docs/superpowers/specs/2026-11-06-revisao-arquitetura-roadmaps-design.md` (R6), `docs/ARCHITECTURE.md` §12 ("Credencial cifrada, nunca na trilha (nem o usuário), nunca devolvida na API e removível por uma ação com trilha") e a nota do brain `ZenyHotelManager - Módulo fnrh`.

## Decisões desta etapa

| # | Decisão | Por quê | Custo se errada |
|---|---|---|---|
| D-E16-1 | **Rota:** `POST /api/v1/fnrh/settings/clear-credentials`, sem corpo, com `If-Match` obrigatório (`428` sem ele, `412` com versão velha, pelo `ETags.expectedVersion` e `requireCurrentVersion` já usados no `PUT`), `operationId = clearFnrhCredentials`, `200` com o `FnrhSettingsResponse` e o `ETag` novo. Descartadas: `DELETE /fnrh/settings/credentials` (a convenção "a API não apaga nada" é a mesma que trocou o `DELETE` do CPF por usuário pela desativação na E07) e um campo no `PUT` (mistura edição e remoção e deixa a ação implícita). | Convenção do projeto (ação como `POST …/<verbo>`, como `deactivate`) e o escopo da etapa ("sem `DELETE`, com `If-Match`"). | Rota publicada não muda; uma rota nova seria aditiva. |
| D-E16-2 | **O que a ação apaga:** `username`, `password` e `default_cpf` ficam `NULL`; `enabled` fica `false`. **Mantém** o `environment` (sem troca de ambiente, a nota M-3 da E08 não é tocada e o status continua lendo o histórico do ambiente atual), o cadastro de CPF por usuário (`fnrh.requester`, caminho próprio desde a E07), os ids remotos, o link de pré-check-in cifrado e o `fnrh.integration_call`, que não são credencial. | Escopo da etapa ("o CPF por usuário tem caminho próprio"). | Apagar o resto depois é aditivo. |
| D-E16-3 | **Repetição:** limpar uma configuração já vazia e desligada é `200` e não muda a versão (o mesmo comportamento do `PUT` com os mesmos valores, que mantém o `ETag`). **Não grava entrada na trilha:** o `AuditScopes` (`shared/infrastructure/audit/AuditScopes.kt`) descarta a mudança acompanhada com antes igual ao depois, como no `PUT` sem mudança. Descartada: gravar a tentativa sem efeito (destoa do `PUT`). | Clique duplo ou repetição depois de timeout não pode virar erro. | Nenhum. |
| D-E16-4 | **Trilha:** ação `fnrh.settings.clear-credentials`, entidade `fnrh.settings`, retrato `FnrhSettingsAuditState` (só `usernameSet`, `passwordSet`, o CPF mascarado, `enabled`, `environment` e `version`); o autor fica em `audit.audit_entry.user_id`, como em toda ação `@Audited`. | Critério "a trilha registra a ação e quem a fez, sem usuário, senha nem CPF"; o retrato da E07 já cumpre. | Nenhum. |
| D-E16-5 | **Sem `@Idempotent`:** a ação não tem efeito financeiro nem externo; o `If-Match` já serializa. | Restrição do roadmap (o `@Idempotent` é para POST com efeito financeiro ou externo). | Nenhum. |
| D-E16-7 | **A forma da ação vira regra de toda integração** na ARCHITECTURE §12 (linha "Credencial"), porque M7 e M8 citam "como a M5 E16": `POST …/clear-credentials` com `If-Match`, sem `Idempotency-Key`; apaga a credencial e desliga a integração, mantendo o ambiente e o que não é credencial; repetir é `200` e mantém o `ETag`; religar exige credencial nova; trilha sem segredo nem usuário. | I-2 da revisão de arquitetura; precedente da Task 13 da E08. | Mudar a regra depois muda as etapas de M7 e M8 que a citam. |
| D-E16-6 | **`ApiSecurityTest` não precisa de edição:** o teste `every other api endpoint rejects anonymous requests with a 401 problem` enumera todos os mapeamentos de `/api/**` do `RequestMappingHandlerMapping` e já cobre a rota nova; nenhum endpoint vira público. A Task 2 roda o teste e a revisão confere a rota na saída. | O critério "`ApiSecurityTest` atualizado" pede a cobertura, que é automática. | Se a revisão discordar, um teste explícito é aditivo. |

## Revisão de arquitetura

- **Veredito:** aprovado.
- **Rodadas:** 2.
- **Modelo:** opus (`revisor-arquitetura`).
- A inicial bloqueou com 2 Importantes (I-1: risco da Task 1; I-2: a forma da ação fora da ARCHITECTURE §12, que M7 e M8 citam) e 5 Menores (trilha na repetição, teste concorrente por tempo, destino da M-3 da E08, tamanho da Task 2, KDoc da classe), todos aplicados. A rodada 1 bloqueou com 1 Importante novo (I-3: atraso do teste concorrente acima do `read-timeout` curto) e 1 Menor (build depois da Task 3), aplicados. A rodada 2 aprovou sem achado.
- **Notas Menores em aberto:** nenhuma. Observação: a Task 3 abre um contexto Spring a mais nos testes (outro conjunto de `@Import`).

## Global Constraints

- Respeitar o grafo de módulos da ARCHITECTURE §3.1: o `fnrh` continua com `allowedDependencies` atuais; nada de `iam`, nenhuma tabela de outro schema, nenhuma migração nova (a tabela `fnrh.settings` já aceita `NULL` nas três colunas e o `CHECK settings_enabled_ck` só exige as três com `enabled`).
- Credenciais da FNRH cifradas (`FieldCipher`) e nunca no repositório, no log, na resposta nem na trilha (nem o usuário).
- Toda escrita é caso de uso `@Audited` (`LayerRules.writeUseCasesAreAudited`).
- `@ApiV1Controller`, `@Operation(operationId)` único, `@Tag(name = "FNRH")`; erros por `FnrhErrorCodes`; `api/openapi.yaml` regenerado por `./gradlew updateOpenApi`, nunca à mão.
- Documentação, mensagens e commits em pt-BR; código, nomes de teste e API em inglês (D17). Cada commit com o trailer `Soul: <soul>`.
- `./gradlew build` verde e `.github/scripts/check-api-breaking.sh main` sem mudança `ERR` em `/api/v1`.
- Os testes de integração compartilham o banco: todo teste que mexe em `fnrh.settings` devolve a linha semente ao estado inicial no `@BeforeEach`/`@AfterEach` (modelo `FnrhSettingsApiTest.resetSeed`).

## Review Focus

1. **Limpeza no meio de um envio:** o job já leu a conexão e está entre duas submissões; nenhuma chamada sai depois da limpeza e a submissão seguinte fica `PENDING` com `attempts = 0` (teste na Task 3, `a clear in the middle of a send run stops the run before the next call`).
2. **Repetição da ação** (clique duplo): a segunda limpeza com o `ETag` atual é `200` e mantém o `ETag`; com o `ETag` de antes da primeira é `412` (teste na Task 2).
3. **Integração já desligada com credencial gravada** (o caso que motivou a etapa): a ação apaga mesmo assim (teste na Task 2).
4. **Nenhum segredo em log nem em problema** durante a limpeza aceita ou recusada (`CapturedOutput`, teste na Task 2).
5. **O que não é credencial fica:** ambiente `PRODUCTION` continua `PRODUCTION` e o CPF por usuário continua ativo (teste na Task 2).

---

### Task 1: `FnrhSettings.clearCredentials()` no domínio

**Estimativa:** 15 min. **Depende de:** nenhuma. **Risco:** alto (grava as colunas cifradas da credencial, o núcleo da etapa).

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/domain/FnrhSettings.kt`
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/domain/FnrhSettingsClearTest.kt` (criar)

**Interfaces:**
- Produces: `fun FnrhSettings.clearCredentials()` — `username`, `password` e o CPF padrão (`encodedDefaultCpf`) passam a `null`, `enabled` a `false`; `environment` não muda. KDoc em pt-BR citando a M5 E16 e o D-E16-2, e o KDoc da classe passa a citar a limpeza ao lado do `update`.

- [x] **Step 1: Escrever os testes que falham** (`FnrhSettingsClearTest`, partindo de `FnrhSettings.initial()` e de um `update(PRODUCTION, "hotel-user", "s3cr3t", Cpf.of("52998224725"), enabled = true)`):
  - `clearCredentials empties username, password and CPF and turns the integration off` — depois: `username shouldBe null`, `password shouldBe null`, `defaultCpf shouldBe null`, `enabled shouldBe false`.
  - `clearCredentials keeps the environment` — `environment shouldBe FnrhEnvironment.PRODUCTION`.
  - `clearCredentials on a disabled configuration with credentials empties it too` — `update(..., enabled = false)` com as três preenchidas, depois `clearCredentials()`: as três `null`.
  - `enabling after clearCredentials without new credentials is FNRH_SETTINGS_INCOMPLETE` — `update(PRODUCTION, null, null, null, enabled = true)` lança `BusinessRuleException` com `FnrhErrorCodes.FNRH_SETTINGS_INCOMPLETE`, e o estado continua vazio e desligado.
  - `enabling after clearCredentials with new credentials works` — `update(PRODUCTION, "outro", "nova-senha", Cpf.of(...), true)`: `enabled shouldBe true`.

- [x] **Step 2: Rodar e ver falhar**

Run: `./gradlew test --tests 'com.zeny.hotel.fnrh.domain.FnrhSettingsClearTest'`
Expected: FAIL de compilação (`clearCredentials` não existe).

- [x] **Step 3: Implementar `fun clearCredentials()` em `FnrhSettings`**, ao lado de `update`.

- [x] **Step 4: Rodar e ver passar**

Run: `./gradlew test --tests 'com.zeny.hotel.fnrh.domain.*'`
Expected: PASS (os testes de domínio da E07 continuam verdes).

- [x] **Step 5: Commit** — `git commit -m "Acrescenta a limpeza da credencial na configuração da FNRH (M5 E16, Task 1)"`, com o trailer `Soul: desenvolvedor`.

---

### Task 2: Ação `POST /api/v1/fnrh/settings/clear-credentials`, trilha, contrato e documentação

**Estimativa:** 40 min. **Depende de:** Task 1. **Risco:** alto (segurança da credencial, contrato público).

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhAudit.kt` (constante `CLEAR_CREDENTIALS = "fnrh.settings.clear-credentials"`)
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhSettingsService.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/web/FnrhSettingsController.kt`
- Modify: `api/openapi.yaml` (só por `./gradlew updateOpenApi`)
- Modify: `README.md` (seção "API", parágrafo "Configuração da FNRH")
- Modify: `docs/ARCHITECTURE.md` (§12, linha "Credencial", hoje na linha ~494)
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/FnrhSettingsClearApiTest.kt` (criar; modelo `FnrhSettingsApiTest`, com `resetSeed` e um `resetRequester` que devolvem o banco ao estado inicial)

**Interfaces:**
- Consumes: `FnrhSettings.clearCredentials()` (Task 1).
- Produces: `FnrhSettingsService.clearCredentials(expectedVersion: Long): FnrhSettingsView`, com `@Audited(FnrhAudit.CLEAR_CREDENTIALS)` e `@Transactional`: `authorization.require(FnrhPermissions.SETTINGS_MANAGE)`, `requireCurrentVersion`, `audit.track(FnrhAudit.SETTINGS, FnrhSettings.SINGLETON_ID) { settings.auditState() }`, `clearCredentials()`, `store.flush()`, devolve a `view()`. Controller: `@PostMapping("/clear-credentials")`, `operationId = "clearFnrhCredentials"`, `summary = "Limpar a credencial da FNRH"`, `description` dizendo: exige `fnrh.settings.manage` e o `If-Match` (428/412); apaga usuário, senha e CPF solicitante padrão e desliga a integração; mantém o ambiente e o CPF por usuário; as submissões pendentes ficam na fila sem gastar tentativa; religar exige nova credencial (`422 FNRH_SETTINGS_INCOMPLETE`); repetir sobre uma configuração vazia é `200` e mantém o `ETag`. Resposta: `ResponseEntity.ok().eTag(...)` com `FnrhSettingsResponse`, pelo mesmo `ok(view)` do `PUT`.

- [x] **Step 1: Escrever os testes de API que falham** (`FnrhSettingsClearApiTest`; o arranjo comum é um `PUT` completo e ligado, como o `fullBody` do `FnrhSettingsApiTest`, e o `ETag` lido por `GET`):
  - `clearing empties the encrypted columns, turns the integration off and the status reads not configured` — `POST` com o `ETag` → `200`; corpo com `username` nulo, `passwordSet = false`, `defaultRequesterCpf` nulo, `enabled = false`, `environment` igual; `ETag` novo diferente do anterior. `SELECT username, password, default_cpf, enabled FROM fnrh.settings` → três `NULL` e `false`. `GET /api/v1/fnrh/status` (Gerente) → `configured = false`, `enabled = false`.
  - `without permission is 403, anonymous is 401, without If-Match is 428 and a stale ETag is 412, and nothing changes` — Gerente e Recepcionista reais → `403`; sem token → `401`; sem `If-Match` → `428`; `ETag` velho → `412`; depois de cada um, as três colunas continuam não nulas e `enabled = true`.
  - `the audit trail records the clear and who did it, without username, password or CPF` — uma entrada com `action = 'fnrh.settings.clear-credentials'` e `user_id` = o do administrador que chamou; `before_state` com `"passwordSet":true` e `after_state` com `"passwordSet":false` e `"enabled":false`; nenhum dos dois contém a senha, `hotel-user`, `52998224725` nem `529.982.247-25`.
  - `re-enabling without new credentials is 422 FNRH_SETTINGS_INCOMPLETE, with new ones it works` — depois da limpeza, `PUT` `{environment, enabled: true}` sem usuário, senha e CPF → `422` com `FNRH_SETTINGS_INCOMPLETE`; `PUT` completo → `200` e `enabled = true`.
  - `clearing twice keeps the ETag, and the ETag from before the first clear is 412` (Review Focus 2) — e há uma entrada `fnrh.settings.clear-credentials` só, não duas (D-E16-3).
  - `clearing a disabled configuration that still holds credentials empties it` (Review Focus 3).
  - `clearing keeps the environment and the per-user requester CPF` (Review Focus 5) — configuração em `PRODUCTION` e um `PUT /api/v1/fnrh/requesters/{userId}`; depois da limpeza, `environment = PRODUCTION` e `GET /api/v1/fnrh/requesters` ainda lista o usuário com o CPF mascarado.
  - `no clear, accepted or refused, writes the username, the password or the CPF to the logs` (Review Focus 4; `@ExtendWith(OutputCaptureExtension::class)` como no `FnrhSettingsApiAttackTest`).

- [x] **Step 2: Rodar e ver falhar**

Run: `./gradlew test --tests 'com.zeny.hotel.fnrh.FnrhSettingsClearApiTest'`
Expected: FAIL (`404` ou `405` na rota, que ainda não existe).

- [x] **Step 3: Implementar** a constante em `FnrhAudit`, `FnrhSettingsService.clearCredentials` e o endpoint no `FnrhSettingsController` com as assinaturas do bloco Interfaces.

- [x] **Step 4: Rodar e ver passar**

Run: `./gradlew test --tests 'com.zeny.hotel.fnrh.*' --tests 'com.zeny.hotel.shared.web.ApiSecurityTest' --tests 'com.zeny.hotel.architecture.*' --tests 'com.zeny.hotel.ModularityTest'`
Expected: PASS. O `ApiSecurityTest` cobre a rota nova sem edição (D-E16-6).

- [x] **Step 5: Regenerar e conferir o contrato**

Run: `./gradlew updateOpenApi && git diff --stat api/openapi.yaml && .github/scripts/check-api-breaking.sh main`
Expected: só a operação `clearFnrhCredentials` acrescentada; o script sem mudança `ERR`.

- [x] **Step 6: README e ARCHITECTURE** — no parágrafo "Configuração da FNRH" da seção "API", depois da frase da trilha do `PUT`, acrescentar uma frase: `POST /api/v1/fnrh/settings/clear-credentials` (`fnrh.settings.manage`, `If-Match`) apaga o usuário, a senha e o CPF solicitante padrão e desliga a integração, mantendo o ambiente e o CPF por usuário; as submissões ficam na fila sem gastar tentativa, religar exige nova credencial (`422 FNRH_SETTINGS_INCOMPLETE`) e a ação vai para a trilha (`fnrh.settings.clear-credentials`) sem segredo. Na ARCHITECTURE §12, completar a linha "Credencial" com a forma genérica do D-E16-7, citando a M5 E16 como a primeira a segui-la.

- [x] **Step 7: Lint e build**

Run: `./gradlew ktlintFormat && ./gradlew build`
Expected: BUILD SUCCESSFUL.

- [x] **Step 8: Commit** — `git commit -m "Acrescenta a ação de limpar a credencial da FNRH (M5 E16, Task 2)"`, com o trailer `Soul: desenvolvedor`.

---

### Task 3: Envio depois da limpeza (testes de integração)

**Estimativa:** 30 min. **Depende de:** Task 2. **Risco:** alto (concorrência entre a limpeza e o job).

**Files:**
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/FnrhSettingsClearSendTest.kt` (criar; estende `FnrhReservationSendTestBase`, como `FnrhSubmissionJobTest`, com o reset da configuração da base)

**Interfaces:**
- Consumes: `POST /api/v1/fnrh/settings/clear-credentials` (Task 2); `FnrhSubmissionSender.sendDueFor`, `awaitSubmission`, `requests` da base.
- Produces: nada para outras tarefas. Sem código de produção: se um teste revelar defeito no envio, o relatório o descreve e o orquestrador decide (não corrija o envio sem ruling).

- [x] **Step 1: Escrever os testes de envio** (`FnrhSettingsClearSendTest`, que liga a configuração no WireMock como a base faz):
  - `after clearing, the send run makes no call and the pending submission keeps zero attempts` — uma reserva com a submissão `REGISTER_RESERVATION` gravada (`awaitSubmission`); limpeza pela API; `sender.send(reservation)` → `FnrhSendRun(0, 0, 0, 0)`; a submissão continua `PENDING` com `attempts = 0`; o WireMock não recebeu nenhuma requisição desde a limpeza.
  - `after clearing, refreshing the domains is 422 FNRH_NOT_CONFIGURED without calling the Serpro`.
  - `a clear in the middle of a send run stops the run before the next call` (Review Focus 1) — duas reservas com o registro pendente; a classe **não** importa a `FnrhShortReadTimeoutConfiguration` (fica o `read-timeout` padrão de 20 s), para a primeira chamada terminar normalmente e não cair em `UNKNOWN_OUTCOME`; o primeiro `POST /reservas` fica preso até a limpeza terminar, por sincronização determinística (um `CountDownLatch` num ouvinte de requisição do WireMock, se a instância da base aceitar extensão; senão `withFixedDelay(5000)` e a limpeza feita assim que `findAll(postRequestedFor(urlEqualTo("/reservas")))` tiver um item, com espera de até 2 s), sempre abaixo do `read-timeout`; o envio roda numa thread; depois do `join`, o WireMock recebeu exatamente um `POST /reservas`, a primeira submissão terminou `SENT` e a outra continua `PENDING` com `attempts = 0`. Nunca `sleep` fixo para ordenar os passos.

- [x] **Step 2: Rodar**

Run: `./gradlew test --tests 'com.zeny.hotel.fnrh.FnrhSettingsClearSendTest'`
Expected: PASS (o comportamento vem do D-E08-5 e do adaptador da E07). Rode o teste concorrente 5 vezes seguidas (`--rerun`) e cole as 5 saídas.

- [x] **Step 3: Lint e build** — `./gradlew ktlintFormat && ./gradlew build` → BUILD SUCCESSFUL (a evidência de build da etapa precisa ser posterior a esta tarefa).

- [x] **Step 4: Commit** — `git commit -m "Testa o envio à FNRH depois da limpeza da credencial (M5 E16, Task 3)"`, com o trailer `Soul: desenvolvedor`.

---

## Notas para o handoff

- A nota M-3 da E08 (troca de ambiente com envio em curso) continua com a M8 E13: a limpeza mantém o ambiente, mas "limpar e depois `PUT` em outro ambiente" com um envio em curso é o mesmo cenário que o `PUT` já permite.
- Divergência anterior à etapa: a ARCHITECTURE §12 diz "nunca devolvida na API", e o `FnrhSettingsResponse` (E07) devolve o `username`; a E16 não muda isso.
- Não testado: a limpeza entre duas chamadas do mesmo passo `CHECK_IN` com vários hóspedes (pela leitura, `STOP` sem gravar e conciliação pela escrita em curso, como ao desligar pelo `PUT`).
