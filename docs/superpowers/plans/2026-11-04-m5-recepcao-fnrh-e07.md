# M5 E07 — Configuração, domínios e adaptador Serpro da FNRH — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** criar o módulo `fnrh` com a configuração cifrada, o CPF solicitante por usuário, os domínios oficiais com cache e validação local, e a porta `FnrhGateway` com o adaptador REST do Serpro (testado com WireMock pela documentação v2.4), com log de integração redigido, endpoint de status e métrica, sem nada disparado por evento (isso é a E08).

**Architecture:** módulo novo `com.zeny.hotel.fnrh` (camada "Integração governamental"), com `domain`/`application`/`infrastructure`/`web` e `FnrhModule.kt`, dependendo só de `shared` e `guests` nesta etapa (a E08 acrescenta `reservations` e `notifications`; `hotel` só se uma tarefa precisar). Schema `fnrh` com migrações próprias. A porta `FnrhGateway` fica em `application`; o adaptador `SerproFnrhGateway`, em `infrastructure`, usa `RestClient` com Basic Auth e o header `cpf_solicitante` nas três operações que o exigem. Toda chamada passa pelo registro de integração, que alimenta o status e a métrica. O validador local e o mapeamento dos dados do hóspede para o formato da FNRH ficam em `domain`, prontos para a E08.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1 (`RestClient`, `@ConfigurationProperties`), Spring Modulith 2.1, Flyway, Micrometer (`MeterBinder`), WireMock 3.13 (`libs.wiremock.standalone`, já no catálogo), Testcontainers.

**Spec:** `docs/roadmaps/roadmap-m5-recepcao-fnrh.md`, seção "E07 — Configuração, domínios e adaptador Serpro da FNRH", com os documentos citados lá: `docs/SPEC.md` §5.9 e §8.2.17, `docs/ARCHITECTURE.md` §8.3 e §12, as hipóteses H5, H6, H20, H21 e H22 do roadmap. O levantamento da API v2.4 está em `docs/fnrh/api-fnrh-v2.4.md`: é a referência dos caminhos, campos e headers.

## Decisões desta etapa

Tomadas pela opção recomendada em nome do usuário (delegado ao orquestrador, 2026-11-04), depois de pesar prós e contras; vão para o handoff.

- **D-E07-1, URL base:** o administrador escolhe o **ambiente** (`HOMOLOGATION` ou `PRODUCTION`) na configuração; as duas URLs ficam em propriedades `zeny.fnrh.homologation-url` e `zeny.fnrh.production-url`, com os padrões do levantamento (os testes apontam as duas para o WireMock). Descartadas: uma URL livre na configuração (o administrador poderia mandar a credencial para qualquer host) e uma propriedade só (trocar de ambiente exigiria nova implantação).
- **D-E07-2, operações da porta:** `registerStay` (`POST /hospedagem/registrar`, a reserva e os hóspedes numa chamada), `updateBooking` (`PUT /reservas/{id}`), `addGuest` (`POST /pessoas` + `POST /reservas/{id}/hospedes`, para hóspede acrescentado depois do registro), `checkIn` e `checkOut` individuais (`PATCH /hospedes/{id}/checkin|checkout`, corpo `text/plain`), `noShow` individual (`PATCH /hospedes/{id}/no-show`, sem uso de produção até o M6), `cancel` (`POST /reservas/{id}/cancelar`) e `fetchDomain`. Fora: pré-check-in de balcão, fichas, as operações em lote e `DELETE /reservas` (o cancelamento cobre).
- **D-E07-3, municípios IBGE:** a API não tem endpoint de municípios. O `fnrh` traz a lista oficial do IBGE como recurso versionado (`src/main/resources/fnrh/ibge-municipios.csv`, código e UF, baixada de `https://servicodados.ibge.gov.br/api/v1/localidades/municipios`, com a data no cabeçalho). Descartada: validar só o formato, que não cumpre o critério "código IBGE inexistente".
- **D-E07-4, países:** ISO 3166-1 alpha-2 pela lista do JDK (`Locale.getISOCountries()`).
- **D-E07-5, domínios sem consulta prévia:** a migração semeia o cache com os valores do levantamento v2.4; o `refresh` substitui. Assim a validação funciona antes da primeira configuração. Descartado: cache vazio até o primeiro `refresh`, que deixaria toda submissão inválida.
- **D-E07-6, permissões** (H20, sem hífen): `fnrh.settings.manage` (só Administrador: configuração, CPFs por usuário e atualização dos domínios), `fnrh.domains.read` (Gerente e Recepcionista: o cliente oferece os códigos no check-in) e `fnrh.submissions.read` (Gerente e Recepcionista: o status agora, a fila de pendências na E08). `fnrh.submissions.manage` nasce na E08.
- **D-E07-7, CPF por usuário:** o id do usuário é opaco (o `fnrh` não importa `iam`); a etapa não confere se o usuário existe.

## Global Constraints

- Módulo `fnrh` com `allowedDependencies` só com o que usa (`shared`, `guests`); um teste no `ModularityTest` com as dependências exatas; nunca `iam`, `frontdesk` nem tabela de outro schema.
- Migrações em `src/main/resources/db/migration/fnrh/`, com versão **posterior a `V2026_10_23_1201`** (a última existente; o roadmap ainda cita `V2026_10_22_0427`), mais uma migração de `GRANT` ao `"${app_role}"` (modelo `frontdesk/V2026_10_23_1201__grant_frontdesk_to_app_role.sql`). `MigrationConventionTest` verde.
- Credenciais (usuário, senha), CPF padrão e CPFs por usuário cifrados com `FieldCipher`/`EncryptedStringConverter` (`shared/FieldEncryption.kt`); `Cpf` de `shared/TaxIds.kt` para validar. Nenhuma resposta devolve a senha; todo CPF sai mascarado.
- Nunca em log, trilha, resposta ou métrica: senha, número de documento, raça/cor, deficiência, nome de hóspede. O `toString` de todo tipo com esses dados não os mostra (como `FnrhGuestData`).
- Casos de uso de escrita com `@Audited` (`LayerRules.writeUseCasesAreAudited`), retratos sem senha nem CPF.
- Convenções de API (nota "ZenyHotelManager - Convenções de API" do brain): `@ApiV1Controller`, `@Operation(operationId)` único, `@Tag` do módulo, erros RFC 9457 com códigos na API pública do módulo (`FnrhErrorCodes`), `PUT` de configuração com `If-Match`/`ETag`, `api/openapi.yaml` regenerado por `./gradlew updateOpenApi` no mesmo commit; `ApiSecurityTest` sem endpoint público novo.
- Actuator intacto (`ActuatorExposureTest`): nenhum `HealthIndicator`; a métrica sai por um `MeterBinder` em `infrastructure`, com prefixo `zeny.fnrh` (no Prometheus, `zeny_fnrh_*`).
- Adaptador no modelo do `FcmPushGateway` (`notifications/infrastructure/FcmPushGateway.kt`: propriedades com `toString` sem segredo, timeouts, `RestClient`) e testes no modelo do `FcmTestServer` (WireMock em `src/test/kotlin/com/zeny/hotel/testsupport/`). Os stubs conferem método, caminho, `Authorization`, `cpf_solicitante` (presente nas três operações, ausente nas demais), `Content-Type` e corpo; ficam nomeados pela versão da API (`v2_4`).
- `@IntegrationTest` e o banco compartilhado: cada teste cria os próprios dados e devolve a configuração da FNRH ao estado anterior.
- Documentação, comentários e commits em pt-BR; código e API em inglês (D17). Commits com o trailer `Soul: <soul>`.

## Review Focus

1. **Vazamento de dado sensível pelo erro:** um `400` do Serpro que ecoa o CPF ou o nome na `mensagem` não pode chegar inteiro ao log, ao registro de integração nem à resposta; o saneamento troca sequências de 5 ou mais dígitos e e-mails por `***` e corta em 200 caracteres (teste com uma mensagem que ecoa CPF e e-mail).
2. **Configuração parcial:** ligar a integração sem usuário, senha ou CPF padrão é recusado; um `PUT` sem senha mantém a senha guardada; trocar o ambiente não apaga as credenciais.
3. **Corpo `text/plain` do check-in e do check-out:** o adaptador manda a data e hora UTC com `Z` como texto puro, não JSON entre aspas (o stub confere o corpo exato e o `Content-Type`).
4. **Timeout e rede:** um Serpro lento além do `read-timeout` e uma conexão recusada viram o mesmo tipo temporário, sem travar a thread além do limite (teste com atraso no WireMock maior que o timeout configurado no teste).
5. **Menor sem documento do responsável e estrangeiro sem residência no Brasil:** a validação local pede o documento do responsável para o menor de 18 anos na data de entrada e não exige município nem UF quando a residência não é `BR`.

---

### Task 1: Módulo `fnrh`, permissões e configuração cifrada

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/fnrh/FnrhModule.kt`, `FnrhPermissions.kt`, `FnrhErrorCodes.kt` (raiz = API pública)
- Create: `fnrh/domain/FnrhSettings.kt`, `fnrh/application/FnrhSettingsService.kt`, `fnrh/application/FnrhPorts.kt`, `fnrh/infrastructure/FnrhProperties.kt`, `fnrh/infrastructure/` (persistência), `fnrh/web/FnrhSettingsController.kt` e DTOs
- Create: `src/main/resources/db/migration/fnrh/V2026_11_04_1000__create_fnrh_settings.sql`, `…_1001__grant_fnrh_to_app_role.sql` (o `GRANT` cobre as tabelas das tarefas seguintes se cada uma acrescentar o seu na própria migração; siga o modelo do módulo)
- Modify: `src/test/kotlin/com/zeny/hotel/ModularityTest.kt`, `api/openapi.yaml`, `README.md` (seção "API")
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/FnrhSettingsApiTest.kt`, `fnrh/domain/FnrhSettingsTest.kt`

**Interfaces:**
- Produces:
  - `enum class FnrhEnvironment { HOMOLOGATION, PRODUCTION }` (resposta `x-extensible-enum`).
  - `FnrhProperties` em `zeny.fnrh`: `homologationUrl: URI` (padrão `https://hom-lowcode.serpro.gov.br/FNRH_API/rest/v2`), `productionUrl: URI` (padrão `https://fnrh.turismo.serpro.gov.br/FNRH_API/rest/v2`), `connectTimeout: Duration` (5 s), `readTimeout: Duration` (20 s).
  - Porta em `application`: `interface FnrhConnectionSource { fun current(): FnrhConnection? }`, com `data class FnrhConnection(val baseUrl: URI, val username: String, val password: String, val defaultRequesterCpf: Cpf)` (`toString` sem senha nem CPF). Devolve `null` quando a integração está desligada ou incompleta. A Task 3 consome.
  - `FnrhPermissions.SETTINGS_MANAGE = "fnrh.settings.manage"` (só `ADMINISTRATOR`), `DOMAINS_READ = "fnrh.domains.read"` (`MANAGER`, `RECEPTIONIST`), `SUBMISSIONS_READ = "fnrh.submissions.read"` (`MANAGER`, `RECEPTIONIST`); nenhuma sensível.
- Endpoints (`@Tag` "FNRH"):
  - `GET /api/v1/fnrh/settings` (`SETTINGS_MANAGE`) → `{environment, username, passwordSet, defaultRequesterCpf (mascarado), enabled}` com `ETag`; sem configuração gravada, `environment = HOMOLOGATION`, campos nulos, `passwordSet = false`, `enabled = false`.
  - `PUT /api/v1/fnrh/settings` (`SETTINGS_MANAGE`, `If-Match` obrigatório como no `HotelController`) com `{environment, username, password?, defaultRequesterCpf, enabled}`; `password` ausente mantém a guardada; CPF inválido → `400` com o campo, sem o valor; `enabled = true` sem usuário, senha ou CPF padrão → `422 FNRH_SETTINGS_INCOMPLETE`.

- [ ] **Step 1: Testes que falham**
  - `FnrhSettingsTest`: `enable()` sem senha lança a regra de `FNRH_SETTINGS_INCOMPLETE`; trocar o ambiente mantém usuário e senha.
  - `FnrhSettingsApiTest`: `GET` sem configuração devolve o padrão acima; `PUT` com tudo e `GET` de novo devolvem `passwordSet = true`, o CPF mascarado e nenhum campo com a senha (o corpo inteiro não contém a senha nem o CPF em claro); `PUT` sem `password` mantém `passwordSet = true`; `PUT` sem `If-Match` → `428`, com `If-Match` velho → `412`; Recepcionista → `403`; as colunas de usuário, senha e CPF lidas por `JdbcTemplate` não contêm o texto claro; `FnrhConnectionSource.current()` devolve a URL do ambiente escolhido e `null` com `enabled = false`.
  - `ModularityTest`: teste do `fnrh` com `shared` e `guests` (o `guests` entra na Task 6; até lá, só `shared`, e a Task 6 atualiza).
- [ ] **Step 2: Rodar e ver falhar** — `./gradlew test --tests 'com.zeny.hotel.fnrh.*' --tests 'com.zeny.hotel.ModularityTest'` → FAIL (classes inexistentes).
- [ ] **Step 3: Implementar** módulo, permissões, migração (`fnrh.settings`, linha única por chave fixa, colunas cifradas, `version` para o `ETag`), serviço `@Audited`, controller e propriedades; `./gradlew updateOpenApi`; README "API" com os dois endpoints.
- [ ] **Step 4: Rodar e ver passar** — o mesmo comando → PASS; `./gradlew lint` → verde.
- [ ] **Step 5: Commit** — `git add` dos arquivos acima; mensagem "Cria o módulo fnrh com a configuração cifrada da integração (M5 E07)" e o trailer `Soul: desenvolvedor`.

### Task 2: CPF solicitante por usuário

**Files:**
- Create: `fnrh/domain/RequesterCpfPolicy.kt`, `fnrh/application/FnrhRequestersService.kt`, `fnrh/web/FnrhRequestersController.kt`, persistência em `fnrh/infrastructure/`
- Create: `db/migration/fnrh/V2026_11_04_1010__create_fnrh_requesters.sql` (+ `GRANT` se o modelo pedir)
- Modify: `api/openapi.yaml`, `README.md`
- Test: `fnrh/domain/RequesterCpfPolicyTest.kt`, `fnrh/FnrhRequestersApiTest.kt`

**Interfaces:**
- Consumes: `FnrhConnection.defaultRequesterCpf` (Task 1).
- Produces:
  - `object RequesterCpfPolicy { fun choose(performedBy: UUID?, cpfOfUser: (UUID) -> Cpf?, defaultCpf: Cpf?): Cpf? }`: o CPF do usuário que fez a operação, senão o padrão; `null` sem nenhum dos dois.
  - Porta `interface FnrhRequesterDirectory { fun cpfOf(userId: UUID): Cpf? }` (a E08 usa).
- Endpoints (`SETTINGS_MANAGE`): `GET /api/v1/fnrh/requesters` → `[{userId, cpf (mascarado)}]`; `PUT /api/v1/fnrh/requesters/{userId}` `{cpf}` → `200` com o mascarado (cria ou troca); `DELETE /api/v1/fnrh/requesters/{userId}` → `204` (`404 FNRH_REQUESTER_NOT_FOUND` se não houver). CPF nunca na URL.

- [ ] **Step 1: Testes que falham** — `RequesterCpfPolicyTest`: com CPF do usuário escolhe o dele; sem, o padrão; sem os dois, `null`; `performedBy = null` (sistema) usa o padrão. `FnrhRequestersApiTest`: `PUT` e `GET` mostram só o mascarado; a coluna não tem o texto claro; CPF inválido → `400` sem o valor; `DELETE` de inexistente → `404`; Recepcionista → `403`.
- [ ] **Step 2: Rodar e ver falhar** — `./gradlew test --tests 'com.zeny.hotel.fnrh.*'` → FAIL.
- [ ] **Step 3: Implementar** (tabela `fnrh.requester`, `user_id` como chave, CPF cifrado; serviço `@Audited` sem o CPF no retrato); `updateOpenApi`; README.
- [ ] **Step 4: Rodar e ver passar** — o mesmo comando → PASS; `./gradlew lint`.
- [ ] **Step 5: Commit** — "Cadastra o CPF solicitante por usuário da FNRH (M5 E07)", trailer `Soul: desenvolvedor`.

### Task 3: Porta `FnrhGateway` e adaptador Serpro

**Files:**
- Create: `fnrh/application/FnrhGateway.kt` (porta e tipos de comando e resultado), `fnrh/infrastructure/SerproFnrhGateway.kt`
- Create: `src/test/kotlin/com/zeny/hotel/testsupport/FnrhTestServer.kt` (WireMock, como o `FcmTestServer`), stubs em código ou em `src/test/resources/wiremock/fnrh/v2_4/`
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/infrastructure/SerproFnrhGatewayTest.kt`

**Interfaces:**
- Consumes: `FnrhConnectionSource.current()` (Task 1) e `FnrhProperties` (timeouts).
- Produces (em `application`; nomes de campo do nosso lado em inglês, mapeados para os do levantamento no adaptador):
  - `data class FnrhBookingData(val bookingCode: String, val otaCode: String?, val arrival: LocalDate, val departure: LocalDate, val adults: Int, val minors: Int)`; `otaCode != null` ⇒ `origem_reserva_id = OTA`, senão `MEIOHOSPEDAGEM`.
  - `data class FnrhPersonData(...)` com os campos de pessoa do levantamento (nome, nome social, nacionalidade ISO, gênero, descrição do gênero, nascimento, raça, opção e tipo de deficiência, documento `FnrhDocument(type: FnrhDocumentType { CPF, PASSPORT }, number)`, e-mail, telefone, residência ISO, CEP, logradouro, número, complemento, bairro, município IBGE `Int?`, UF); `toString` só com o tipo do documento.
  - `data class FnrhStayGuest(val person: FnrhPersonData, val principal: Boolean, val status: FnrhGuestStatus, val checkedInAt: Instant?, val checkedOutAt: Instant?, val travelReason: String?, val transportMode: String?, val responsibleDocument: FnrhDocument?)`.
  - `interface FnrhGateway`:
    - `fun registerStay(booking: FnrhBookingData, guests: List<FnrhStayGuest>, requesterCpf: Cpf): FnrhStayRegistered` (`reservationId: FnrhRemoteId`, `precheckinLink: String?`, `guests: List<FnrhRegisteredGuest(personId, guestId)>` na ordem do pedido);
    - `fun updateBooking(reservationId: FnrhRemoteId, booking: FnrhBookingData)`;
    - `fun addGuest(reservationId: FnrhRemoteId, guest: FnrhStayGuest, responsibleGuestId: FnrhRemoteId?, requesterCpf: Cpf): FnrhRegisteredGuest`;
    - `fun checkIn(guestId: FnrhRemoteId, at: Instant)`, `fun checkOut(guestId: FnrhRemoteId, at: Instant)`, `fun noShow(guestId: FnrhRemoteId)`, `fun cancel(reservationId: FnrhRemoteId)`;
    - `fun fetchDomain(domain: FnrhDomain): List<FnrhDomainValue(code, label)>`, com `enum class FnrhDomain(val path: String)` para os 10 caminhos do levantamento.
  - `@JvmInline value class FnrhRemoteId(val value: String)`.
  - Erros: `class FnrhGatewayException(val failure: FnrhFailure, val httpStatus: Int?, val sanitizedMessage: String?) : RuntimeException`, com `enum class FnrhFailure(val temporary: Boolean) { NOT_CONFIGURED(false), VALIDATION(false), UNAUTHORIZED(false), FORBIDDEN(false), NOT_FOUND(false), SERVER_ERROR(true), UNAVAILABLE(true) }`. `400` → `VALIDATION`, `401` → `UNAUTHORIZED`, `403` → `FORBIDDEN`, `404` → `NOT_FOUND`, `5xx` → `SERVER_ERROR`, timeout ou falha de rede → `UNAVAILABLE`, sem configuração → `NOT_CONFIGURED` (sem chamada).
  - `object FnrhMessageSanitizer { fun sanitize(raw: String?): String? }`: sequências de 5 ou mais dígitos e e-mails viram `***`; corta em 200 caracteres. A mensagem da exceção nunca traz o corpo bruto.

- [ ] **Step 1: Testes que falham** (`SerproFnrhGatewayTest`, com o `FnrhTestServer` como as duas URLs do `zeny.fnrh`), um por operação, conferindo método, caminho, `Authorization: Basic` com o usuário e a senha da configuração, `cpf_solicitante` só em `registerStay` e no `POST /pessoas` do `addGuest` (e ausente em `checkIn`), corpo esperado e o id remoto mapeado da resposta:
  - `registerStay` → `POST /hospedagem/registrar` com `reserva`, `dados_hospede[]` (inclusive `responsavel` e `dados_ficha`) e resposta com `reserva_id`, `link_precheckin` e os `hospede_id`/`pessoa_id`;
  - `updateBooking` → `PUT /reservas/{id}`; `cancel` → `POST /reservas/{id}/cancelar`;
  - `addGuest` → `POST /pessoas` e depois `POST /reservas/{id}/hospedes` com o `pessoa_id` devolvido e `responsavel_id`;
  - `checkIn`/`checkOut` → `PATCH /hospedes/{id}/checkin|checkout` com corpo `text/plain` `2026-02-10T15:00:00Z` (formato ISO 8601 UTC do `Instant`);
  - `noShow` → `PATCH /hospedes/{id}/no-show`; `fetchDomain(TRANSPORT_MODES)` → `GET /dominios/fnrh/meios_transporte`;
  - erros: `400` com `{"mensagem": "CPF 12345678901 inválido para joao@x.com"}` → `VALIDATION` e `sanitizedMessage` sem o número nem o e-mail; `401` → `UNAUTHORIZED`; `503` → `SERVER_ERROR` temporário; atraso maior que o `read-timeout` do teste (por exemplo 300 ms com timeout de 100 ms) → `UNAVAILABLE`; conexão recusada → `UNAVAILABLE`; sem configuração → `NOT_CONFIGURED` e nenhuma requisição no WireMock.
- [ ] **Step 2: Rodar e ver falhar** — `./gradlew test --tests 'com.zeny.hotel.fnrh.infrastructure.*'` → FAIL.
- [ ] **Step 3: Implementar** o adaptador (`RestClient` criado por chamada a partir da `FnrhConnection` ou com `baseUrl` resolvida por chamada; `JdkClientHttpRequestFactory` com os timeouts; DTOs internos com os nomes do Serpro via `@JsonProperty`); a porta não expõe tipos do Serpro.
- [ ] **Step 4: Rodar e ver passar** — o mesmo comando → PASS; `./gradlew lint`.
- [ ] **Step 5: Commit** — "Cria a porta FnrhGateway e o adaptador Serpro da API v2.4 (M5 E07)", trailer `Soul: desenvolvedor`.

### Task 4: Registro de integração, status e métrica

**Files:**
- Create: `fnrh/application/FnrhIntegrationLog.kt` (porta e registro), `fnrh/infrastructure/` (persistência e `FnrhMetrics` `MeterBinder`), `fnrh/web/FnrhStatusController.kt`
- Create: `db/migration/fnrh/V2026_11_04_1020__create_fnrh_integration_calls.sql`
- Modify: `SerproFnrhGateway.kt` (cada chamada registrada), `api/openapi.yaml`, `README.md`
- Test: `fnrh/FnrhStatusApiTest.kt`, `fnrh/infrastructure/FnrhIntegrationLogTest.kt`

**Interfaces:**
- Consumes: `FnrhGateway`, `FnrhFailure`, `FnrhMessageSanitizer` (Task 3).
- Produces:
  - Tabela *append-only* `fnrh.integration_call` (`id`, `operation`, `outcome` = `SUCCESS` ou um `FnrhFailure`, `http_status`, `duration_ms`, `sanitized_message`, `occurred_at`), com `AppendOnlyTables`, trigger contra `UPDATE`/`DELETE` e só `SELECT, INSERT` ao papel da aplicação (modelo `reservations.reservation_history_immutable`).
  - Uma linha de log por chamada (`INFO` no sucesso, `WARN` na falha) com `operation`, `outcome`, `httpStatus` e `durationMs`, e nada além da mensagem saneada. O caminho da URL nunca entra no log, porque `GET /pessoas/documento/{tipo}/{numero}` carregaria documento.
  - `GET /api/v1/fnrh/status` (`SUBMISSIONS_READ`) → `{configured, enabled, environment, lastSuccessAt, lastFailureAt, lastFailureOutcome}`.
  - Métricas pelo `MeterBinder`: `zeny.fnrh.calls` (contador com as tags `operation` e `outcome`), `zeny.fnrh.last.success` e `zeny.fnrh.last.failure` (gauges em segundos desde a época, `NaN` sem registro) e `zeny.fnrh.configured` (0 ou 1).

- [ ] **Step 1: Testes que falham**
  - `FnrhIntegrationLogTest` (WireMock + `OutputCaptureExtension`): um `registerStay` com raça, deficiência, CPF e senha conhecidos, seguido de um `400` que ecoa o CPF. O log capturado e as linhas de `fnrh.integration_call` não contêm o CPF, o número do passaporte, o código de raça enviado, o tipo de deficiência, a senha nem o nome. Há uma linha por chamada, com o `outcome` certo. `UPDATE` na tabela falha.
  - `FnrhStatusApiTest`:
    - depois de um sucesso e de uma falha, o status mostra os dois instantes e o `outcome` da falha;
    - `/actuator/prometheus` tem `zeny_fnrh_calls_total` e `zeny_fnrh_configured`;
    - Camareira → `403`.
- [ ] **Step 2: Rodar e ver falhar** — `./gradlew test --tests 'com.zeny.hotel.fnrh.*'` → FAIL.
- [ ] **Step 3: Implementar.** O registro roda fora da transação do chamador (`REQUIRES_NEW`), para a linha ficar gravada mesmo quando a E08 desfizer a transação do envio. Rode `updateOpenApi` e atualize o README.
- [ ] **Step 4: Rodar e ver passar.** O mesmo comando dá PASS. Rode também `./gradlew test --tests 'com.zeny.hotel.shared.*ActuatorExposureTest'` (ou o nome real do teste) e `./gradlew lint`.
- [ ] **Step 5: Commit.** Mensagem "Registra as chamadas à FNRH com status e métrica, sem dado sensível (M5 E07)", trailer `Soul: desenvolvedor`.

### Task 5: Domínios oficiais com cache

**Files:**
- Create: `fnrh/application/FnrhDomainsService.kt`, `fnrh/domain/FnrhDomainCatalog.kt` (porta de leitura), persistência em `fnrh/infrastructure/`, `fnrh/web/FnrhDomainsController.kt`
- Create: `db/migration/fnrh/V2026_11_04_1030__create_fnrh_domain_values.sql`, com a semente dos valores do levantamento (todos os `id` da tabela "Domínios" de `docs/fnrh/api-fnrh-v2.4.md`, com os `label` do PDF).
- Modify: `api/openapi.yaml`, `README.md`
- Test: `fnrh/FnrhDomainsApiTest.kt`

**Interfaces:**
- Consumes: `FnrhGateway.fetchDomain` e `FnrhDomain` (Task 3).
- Produces:
  - `interface FnrhDomainCatalog { fun contains(domain: FnrhDomain, code: String): Boolean; fun values(domain: FnrhDomain): List<FnrhDomainValue> }`, que a Task 6 consome.
  - Os endpoints:
    - `GET /api/v1/fnrh/domains/{domain}` (`DOMAINS_READ`): `{domain}` em kebab-case (`transport-modes`, `travel-reasons`, `guest-statuses`, `genders`, `disability-options`, `races`, `disability-types`, `document-types`, `booking-statuses`, `record-statuses`); devolve `[{code, label}]`, e um domínio desconhecido dá `404 FNRH_DOMAIN_NOT_FOUND`;
    - `POST /api/v1/fnrh/domains/refresh` (`SETTINGS_MANAGE`, `@Audited`): busca os 10 domínios e substitui cada um numa transação só, de modo que, se um falhar, nada muda. Sem configuração, dá `422 FNRH_NOT_CONFIGURED`; com o Serpro fora do ar, `503 FNRH_UNAVAILABLE`. A resposta traz a contagem por domínio.

- [ ] **Step 1: Testes que falham**
  - sem `refresh`, `GET …/transport-modes` devolve os 8 valores da semente;
  - com o WireMock devolvendo um valor novo, o `refresh` faz o `GET` mostrá-lo e tira um valor que sumiu;
  - `refresh` com `503` num dos domínios deixa todos como estavam;
  - `refresh` sem configuração dá `422`;
  - Recepcionista consegue ler, e o `refresh` dela dá `403`.
- [ ] **Step 2: Rodar e ver falhar** — `./gradlew test --tests 'com.zeny.hotel.fnrh.*'` → FAIL.
- [ ] **Step 3: Implementar**, rodar `updateOpenApi` e atualizar o README.
- [ ] **Step 4: Rodar e ver passar** — o mesmo comando dá PASS; depois, `./gradlew lint`.
- [ ] **Step 5: Commit** — mensagem "Guarda os domínios oficiais da FNRH com atualização sob demanda (M5 E07)", com o trailer `Soul: desenvolvedor`.

### Task 6: Mapeamento dos dados do hóspede e validação local

**Files:**
- Create: `fnrh/domain/FnrhPersonMapper.kt`, `fnrh/domain/FnrhLocalValidator.kt`, `fnrh/domain/IbgeMunicipalities.kt` (leitura do recurso)
- Create: `src/main/resources/fnrh/ibge-municipios.csv`, com cabeçalho `# fonte: https://servicodados.ibge.gov.br/api/v1/localidades/municipios, baixado em <data>` e uma linha `codigo;uf` por município (os 7 dígitos e a sigla da UF)
- Modify: `FnrhModule.kt` (`guests`), `ModularityTest.kt`
- Test: `fnrh/domain/FnrhPersonMapperTest.kt`, `fnrh/domain/FnrhLocalValidatorTest.kt`, `fnrh/domain/IbgeMunicipalitiesTest.kt`

**Interfaces:**
- Consumes:
  - `FnrhGuestData` e `FnrhGuestDocument` da `GuestsApi` (`guests/GuestsApi.kt`; leia `Address`, `Gender`, `RaceColor`, `DocumentType` e o domínio de `disability` em `guests/domain/SensitiveGuestData.kt`);
  - `FnrhPersonData`, `FnrhStayGuest` e `FnrhDocument` (Task 3);
  - `FnrhDomainCatalog` (Task 5).
- Produces:
  - `object FnrhPersonMapper { fun toPerson(guest: FnrhGuestData): FnrhPersonData }`. O documento é o CPF se houver, senão o passaporte; os outros tipos não vão. Gênero: `FEMALE`→`MULHER`, `MALE`→`HOMEM`, `OTHER`→`OUTRO`, `NOT_INFORMED` ou nulo→`NAOINFORMADO`. Raça: `WHITE`→`BRANCA`, `BLACK`→`PRETA`, `BROWN`→`PARDA`, `YELLOW`→`AMARELA`, `INDIGENOUS`→`INDIGENA`, `NOT_DECLARED` ou nula→`NAOINFORMAR`. Deficiência: nula→`NAOINFORMAR`; um código de `tipos_deficiencia`→opção `SIM` com esse tipo; `NAO` ou `NAOINFORMAR`→essa opção, sem tipo. Endereço: CEP só com dígitos, município, UF e residência; os campos que não existirem no `Address` ficam nulos (registre no relatório o que faltou).
  - `class FnrhLocalValidator(catalog: FnrhDomainCatalog, municipalities: IbgeMunicipalities, countries: Set<String> = Locale.getISOCountries().toSet()) { fun validate(guest: FnrhStayGuest, arrival: LocalDate): List<FnrhFieldError> }`.
  - `data class FnrhFieldError(val field: String, val code: String)`, que nunca carrega o valor. Os campos são os nomes do `FnrhStayGuest`/`FnrhPersonData` (`person.document`, `person.nationality`, `person.cityIbge`…), e os códigos são `REQUIRED`, `INVALID`, `NOT_IN_DOMAIN` e `INCONSISTENT`.
  - Regras:
    - nome obrigatório;
    - documento obrigatório, só CPF ou passaporte, e o CPF passa pelo dígito verificador do `Cpf`;
    - nacionalidade e residência em ISO alpha-2 (`NOT_IN_DOMAIN`);
    - data de nascimento obrigatória;
    - gênero, raça, opção e tipo de deficiência, motivo de viagem e meio de transporte no `FnrhDomainCatalog`;
    - `OUTRO` exige descrição; deficiência `SIM` exige o tipo;
    - com residência `BR`: município obrigatório e existente na lista do IBGE, UF obrigatória e igual à do município (`INCONSISTENT`), e CEP de 8 dígitos quando informado, com logradouro, número e bairro;
    - situação `CHECKIN_REALIZADO` ou `CHECKOUT_REALIZADO` exige motivo de viagem, meio de transporte e `checkedInAt` (o `checkedOutAt` só na segunda);
    - menor de 18 anos na data de `arrival` exige `responsibleDocument` válido.
  - `class IbgeMunicipalities { fun ufOf(code: Int): String? }`, carregada uma vez do recurso.

- [ ] **Step 1: Baixe a lista do IBGE**, usando `curl` na URL acima, e gere o CSV com `codigo;uf`, a partir de `id` e de `microrregiao.mesorregiao.UF.sigla` ou `regiao-imediata.regiao-intermediaria.UF.sigla`, conforme o formato devolvido. Confira que são mais de 5.500 linhas e que `3550308;SP`, `3304557;RJ`, `4303509;RS` e `5300108;DF` estão lá. Se a rede falhar, devolva NEEDS_CONTEXT.
- [ ] **Step 2: Testes que falham.**
  - `FnrhLocalValidatorTest`: um hóspede válido (brasileiro, CPF válido, residência `BR`, `3550308`/`SP`) não gera erro. Cada um dos casos abaixo gera exatamente o erro esperado, e nenhum `FnrhFieldError.toString()` nem `field`/`code` contém o valor rejeitado:
    - CPF com dígito errado → `person.document`/`INVALID`;
    - só RG → `person.document`/`REQUIRED`;
    - nacionalidade `XX` → `NOT_IN_DOMAIN`;
    - município `9999999` → `person.cityIbge`/`NOT_IN_DOMAIN`;
    - `3550308` com `RJ` → `INCONSISTENT`;
    - estrangeiro com residência `AR` sem município → nenhum erro de município;
    - meio de transporte `TELEPORTE` → `NOT_IN_DOMAIN`;
    - menor sem responsável → `responsibleDocument`/`REQUIRED`;
    - `OUTRO` sem descrição;
    - deficiência `SIM` sem tipo.
  - `FnrhPersonMapperTest`: o mapeamento de cada enum, a escolha do CPF sobre o passaporte e o `toString` sem número.
  - `IbgeMunicipalitiesTest`: `ufOf(3550308) == "SP"` e `ufOf(9999999) == null`.
- [ ] **Step 3: Rode e veja falhar** com `./gradlew test --tests 'com.zeny.hotel.fnrh.domain.*'` → FAIL.
- [ ] **Step 4: Implemente** e atualize `FnrhModule`/`ModularityTest` para `shared` e `guests`.
- [ ] **Step 5: Rode e veja passar.** O mesmo comando e `--tests 'com.zeny.hotel.ModularityTest'` → PASS. Depois, `./gradlew lint`.
- [ ] **Step 6: Commit** com a mensagem "Valida localmente os dados da FNRH contra os domínios e o IBGE (M5 E07)" e o trailer `Soul: desenvolvedor`.

## Notas para o fechamento (orquestrador)

- **Handoff:**
  - o levantamento fica em `docs/fnrh/api-fnrh-v2.4.md`, com o SHA-256 do PDF;
  - as decisões D-E07-1 a 7 vão para "Decisões para o usuário";
  - a nota B2 da E02 também: o menor brasileiro só com RG não é aceito pela API, que só aceita CPF ou passaporte;
  - vão para as notas da E08: não há desfazer check-in na API; o cancelamento só vale em `CRIADA`; o caminho do no-show diverge no PDF; a ordem de `dados_hospedes` é uma premissa.
- **Captura do brain:** nota nova `ZenyHotelManager - Módulo fnrh`, e a nota de segurança, que recebe as permissões novas.
- **`docs/ARCHITECTURE.md` §12:** a linha da FNRH diz "header `cpf_solicitante`". Corrija para "em três operações".
