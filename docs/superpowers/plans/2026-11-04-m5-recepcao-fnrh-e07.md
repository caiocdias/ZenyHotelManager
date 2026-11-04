# M5 E07 — Configuração, domínios e adaptador Serpro da FNRH — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** criar o módulo `fnrh` com estas peças:
- configuração cifrada;
- CPF solicitante por usuário;
- domínios oficiais com cache e validação local;
- porta `FnrhGateway`, com o adaptador REST do Serpro testado com WireMock a partir da documentação v2.4;
- registro de integração sem dado pessoal, endpoint de status e métrica.

Nada é disparado por evento: isso é a E08.

**Architecture:** módulo novo `com.zeny.hotel.fnrh`, na camada "Integração governamental", com `domain`, `application`, `infrastructure`, `web` e `FnrhModule.kt`. Nesta etapa ele depende só de `shared` e `guests`; a E08 acrescenta `reservations` e `notifications`.

- **Domain:** o modelo de dados da FNRH, as regras de configuração, a escolha do CPF, o mapeamento do hóspede e o validador local.
- **Application:** os casos de uso e as portas (`FnrhGateway`, `FnrhConnectionSource`, `FnrhIntegrationLog`, repositórios).
- **Infrastructure:** o adaptador `SerproFnrhGateway` (`RestClient`, Basic Auth, `cpf_solicitante` só onde a API exige), a persistência e o registro de integração em transação própria, além do `MeterBinder`.
- **Web:** só os controllers e os DTOs.

Toda chamada ao Serpro roda fora de transação do banco.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1 (`RestClient`, `@ConfigurationProperties`), Spring Modulith 2.1, Flyway, Micrometer (`MeterBinder`), WireMock 3.13 (`libs.wiremock.standalone`, já no catálogo) e Testcontainers.

**Spec:** `docs/roadmaps/roadmap-m5-recepcao-fnrh.md`, seção "E07 — Configuração, domínios e adaptador Serpro da FNRH". Valem também os documentos que ela cita: `docs/SPEC.md` §5.9 e §8.2.17, `docs/ARCHITECTURE.md` §8.3 e §12, e as hipóteses H5, H6, H20, H21 e H22 do roadmap. O levantamento da API v2.4 está em `docs/fnrh/api-fnrh-v2.4.md`, que é a referência dos caminhos, campos, rótulos e headers. Quando ele divergir da spec, vale o levantamento: é a fonte oficial (D-E07-9).

## Decisões desta etapa

Tomadas pela opção recomendada em nome do usuário (delegado ao orquestrador, 2026-11-04), depois de pesar prós e contras. Vão para o handoff, em "Decisões para o usuário".

- **D-E07-1, URL base.** O administrador escolhe o **ambiente** (`HOMOLOGATION` ou `PRODUCTION`) na configuração. As duas URLs ficam em `zeny.fnrh.homologation-url` e `zeny.fnrh.production-url`, com os padrões do levantamento; os testes apontam as duas para o WireMock. **Trocar de ambiente exige informar a senha de novo**, para a credencial de um ambiente não ir para o outro. Descartadas: a URL livre na configuração (a credencial poderia ir para qualquer host) e uma única propriedade (trocar de ambiente exigiria nova implantação).
- **D-E07-2, fluxo de registro (decisão aberta "reserva antes da chegada").**
  - Na criação da reserva, registro **sem dado pessoal**, com `POST /reservas`, que devolve o id remoto e o link de pré-check-in (RF-FNRH-04).
  - Na alteração, `PUT /reservas/{id}`. No cancelamento, `POST /reservas/{id}/cancelar`, que só vale em `CRIADA`, o que este fluxo garante antes do check-in.
  - No check-in, cada hóspede entra com `POST /pessoas`, que exige `cpf_solicitante`, seguido de `POST /reservas/{id}/hospedes` com `CHECKIN_REALIZADO`, `checkin_em`, motivo, meio de transporte e responsável.
  - No check-out, `PATCH /hospedes/{id}/checkout`.
  - Descartada a alternativa "registro só no check-in" (`POST /hospedagem/registrar`): com ela, o cancelamento nunca chega ao Serpro e o link de pré-check-in não serve para nada.
  - Fora da porta: `hospedagem/registrar`, pré-check-in de balcão, fichas, operações em lote, `DELETE /reservas`, `PATCH …/checkin` (o hóspede já entra com check-in) e o no-show. O no-show fica para o M6 (H15), porque o caminho individual diverge no PDF. A SPEC §8.2.17 lista `registerStay/checkIn/checkOut/noShow/cancel`; a porta segue a API real e o registro vai no handoff.
- **D-E07-3, municípios IBGE.** A API não tem endpoint de municípios. O `fnrh` traz a lista oficial como recurso versionado, em `src/main/resources/fnrh/ibge-municipios.csv`, baixada de `https://servicodados.ibge.gov.br/api/v1/localidades/municipios`, com a data e o SHA-256 do JSON no cabeçalho. Risco aceito: um município criado depois do download é recusado até a próxima atualização do recurso. Descartada a validação só de formato, que não cumpre o critério "código IBGE inexistente".
- **D-E07-4, países.** ISO 3166-1 alpha-2 pela lista do JDK (`Locale.getISOCountries()`), como já faz `guests/domain/GuestsRules.kt`.
- **D-E07-5, domínios desde o primeiro dia.** A migração semeia o cache com os `id` e `label` do levantamento, e o `refresh` substitui. Descartado o cache vazio até o primeiro `refresh`, que deixaria toda submissão inválida.
- **D-E07-6, permissões** (H20, sem hífen):
  - `fnrh.settings.manage`: configuração, CPFs por usuário e atualização dos domínios. É exclusiva do Administrador, por isso fica **sem modelo de role**: o Administrador recebe todas as permissões e não pode constar.
  - `fnrh.domains.read` (Gerente e Recepcionista): o cliente oferece os códigos no check-in.
  - `fnrh.submissions.read` (Gerente e Recepcionista): o status agora, a fila de pendências na E08.
  - `fnrh.submissions.manage` nasce na E08.
- **D-E07-7, CPF por usuário.** O id do usuário é opaco, porque o `fnrh` não importa `iam`, e não se confere se o usuário existe.
- **D-E07-8, sem texto livre no registro de integração.** A tabela *append-only* `fnrh.integration_call` guarda só a operação, o resultado, o status HTTP, a duração, o ambiente e referências opacas (o nosso id de correlação e o id remoto). A mensagem do Serpro, saneada, vai só para o log e para a exceção. Descartada a gravação da mensagem: ela pode trazer o nome do hóspede, e uma tabela *append-only* não admite expurgo.
- **D-E07-9, `cpf_solicitante`.** A spec supõe o header em toda chamada, mas a API v2.4 o exige só em `POST /pessoas`, `GET /pessoas/documento/…` e `POST /hospedagem/registrar`; nesta porta, só em `registerPerson`. Consequência para a H16 e a E08: o `performedBy` só pesa no registro da pessoa, no check-in; o registro da reserva, a alteração, o cancelamento e o check-out não levam CPF.
- **D-E07-10, dados não coletados.** Raça ou gênero nulos no perfil significam "não coletado", e a validação acusa `REQUIRED`. Só `NOT_DECLARED`/`NOT_INFORMED` viram `NAOINFORMAR`/`NAOINFORMADO`.
- **D-E07-11, gênero `OUTRO`.** O `guests` não guarda a descrição do gênero que a FNRH exige com `OUTRO`. O mapeamento não falseia o dado, e a validação acusa `person.genderDescription`/`REQUIRED`. Fica para o usuário decidir: a recomendação é acrescentar a descrição ao perfil e ao `FnrhGuestData`, numa etapa própria ou na E08.
- **D-E07-12, dependências.** A spec sugere `shared, hotel, guests`. A etapa usa `shared, guests`, porque o payload não pede dado do hotel; a `HotelApi.profile()` fica para a E11.

## Global Constraints

- **Camadas** (`src/test/kotlin/com/zeny/hotel/architecture/LayerRules.kt`):
  - `domain` não conhece `application`, `infrastructure` nem `web`;
  - `web` fala só com `application` e com a raiz do módulo;
  - os tipos que a web expõe (`FnrhEnvironment`) ficam na raiz do módulo;
  - todo método público `@Transactional` de escrita em `application` leva `@Audited`;
  - o registro de integração em transação própria fica em `infrastructure`, no modelo do `PushDeliveryLog` de `notifications/infrastructure/JdbcNotifications.kt` (linhas ~319–333, com `TransactionTemplate` e `PROPAGATION_REQUIRES_NEW`).
- **Módulo.** `fnrh` declara em `allowedDependencies` só o que usa e tem um teste no `ModularityTest` com as dependências exatas. Nunca `iam`, `frontdesk` nem tabela de outro schema.
- **Migrações:**
  - ficam em `src/main/resources/db/migration/fnrh/`, com versão **posterior a `V2026_10_23_1201`** (a última existente);
  - o `GRANT` ao `"${app_role}"` segue o modelo de `frontdesk/V2026_10_23_1201__grant_frontdesk_to_app_role.sql`;
  - a tabela *append-only* recebe `REVOKE UPDATE, DELETE` explícito, como em `notifications/V2026_10_16_1741__grant_notifications_to_app_role.sql`, com bean `AppendOnlyTables` e trigger contra `UPDATE`/`DELETE` (modelo `reservations.reservation_history_immutable`);
  - `MigrationConventionTest` verde.
- **Cifragem e mascaramento:**
  - usuário, senha, CPF padrão e CPFs por usuário são cifrados com `FieldCipher`/`EncryptedStringConverter` (`shared/FieldEncryption.kt`), e o `Cpf` (`shared/TaxIds.kt`) valida;
  - todo CPF sai mascarado como `***.***.***-` mais os 2 últimos dígitos;
  - nenhuma resposta devolve a senha;
  - **`Cpf.toString()` devolve os dígitos**: nunca interpole `Cpf` em log, mensagem nem `toString`.
- **Dados que nunca saem:** senha, número de documento, raça/cor, deficiência, nome, e-mail e telefone de hóspede não entram em log, trilha, resposta, métrica nem no registro de integração. Todo tipo com esses dados sobrescreve o `toString` (`FnrhPersonData`, `FnrhGuest`, `FnrhDocument`, `FnrhConnection`), como `FnrhGuestData`. A `FnrhGatewayException` não encadeia a exceção do `RestClient` como causa, porque a mensagem dela pode trazer o corpo; use `exchange`, como o `FcmPushGateway`, ou descarte a causa.
- **Convenções de API** (nota "ZenyHotelManager - Convenções de API" do brain):
  - `@ApiV1Controller`, `@Operation(operationId)` único e `@Tag("FNRH")`;
  - erros RFC 9457 com os códigos em `FnrhErrorCodes`, na raiz;
  - `PUT` de configuração com `If-Match`/`ETag`, como o `HotelController`;
  - enums de resposta com `x-extensible-enum`;
  - `api/openapi.yaml` regenerado por `./gradlew updateOpenApi` no mesmo commit;
  - `ApiSecurityTest` sem endpoint público novo;
  - os códigos de campo usam `FieldViolationCodes` (`REQUIRED`, `INVALID_VALUE`, `INVALID_FORMAT`) quando um deles serve, e os específicos (`NOT_IN_DOMAIN`, `INCONSISTENT`) ficam documentados em `FnrhErrorCodes`.
- **Actuator intacto** (`ActuatorExposureTest`): nenhum `HealthIndicator`. A métrica sai por `MeterBinder` em `infrastructure`, com prefixo `zeny.fnrh`.
- **Instantes enviados ao Serpro:** UTC com milissegundos e `Z` (`2026-02-10T15:00:00.000Z`, `truncatedTo(MILLIS)` com padrão fixo `.SSS'Z'`), tanto no `text/plain` quanto no JSON.
- **Testes:**
  - o adaptador segue o modelo do `FcmPushGateway`, e os testes seguem o `FcmTestServer` de `src/test/kotlin/com/zeny/hotel/notifications/NotificationsTestSupport.kt`, ligado por `@TestConfiguration` com bean `@Primary` para não multiplicar contextos;
  - o `FnrhTestServer` novo fica em `src/test/kotlin/com/zeny/hotel/testsupport/`, porque a E08 e o teste de ponta a ponta do marco também vão usá-lo;
  - os stubs conferem método, caminho, `Authorization`, presença ou ausência de `cpf_solicitante`, `Content-Type` e corpo, e ficam nomeados pela versão da API (`v2_4`);
  - nos testes `@IntegrationTest` o banco é compartilhado: cada teste cria os próprios dados e devolve a configuração da FNRH ao estado anterior.
- **Verificação de toda tarefa** (Step 4), além dos testes da tarefa: `./gradlew test --tests 'com.zeny.hotel.architecture.*' --tests 'com.zeny.hotel.ModularityTest' --tests '*MigrationConventionTest' --tests '*ApiSecurityTest'` e `./gradlew lint`.
- **Idioma e commits:** documentação, comentários e commits em pt-BR; código e API em inglês (D17); commits com o trailer `Soul: <soul>`.

## Review Focus

1. **Vazamento pelo erro do Serpro.** Um `400` que ecoa o CPF, o e-mail ou o **nome** enviado não pode chegar inteiro ao log nem à exceção. O saneador troca por `***` os valores que o adaptador acabou de enviar (nome, nome social, número de documento, e-mail e telefone), as sequências de 5 ou mais dígitos e os e-mails, e corta em 200 caracteres. O teste usa uma mensagem que ecoa nome, CPF e e-mail.
2. **Resultado desconhecido.** Um timeout de leitura num `POST`/`PUT` não diz se o Serpro gravou. Ele vira `UNKNOWN_OUTCOME`, diferente de `UNAVAILABLE` (conexão recusada, nada enviado). A porta oferece `findReservationByCode` para a E08 conciliar.
3. **Configuração parcial.** Estes casos são recusados: ligar sem usuário, senha ou CPF padrão; trocar de ambiente sem senha nova. Um `PUT` sem senha e no mesmo ambiente mantém a senha.
4. **Corpo `text/plain` do check-out.** Leva a data e hora com milissegundos e `Z` como texto puro, não JSON entre aspas.
5. **Menor e estrangeiro.** O menor de 18 anos na data de entrada exige o responsável. A residência fora do `BR` não exige município nem UF.

---

### Task 1: Módulo `fnrh`, permissões e configuração no domínio e no banco

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/fnrh/FnrhModule.kt`, `FnrhPermissions.kt`, `FnrhErrorCodes.kt` e `FnrhEnvironment.kt` (raiz = API pública)
- Create: `fnrh/domain/FnrhSettings.kt`, `fnrh/application/FnrhConnectionSource.kt` (porta e `FnrhConnection`), `fnrh/infrastructure/FnrhProperties.kt`, persistência e implementação da porta em `fnrh/infrastructure/`
- Create: `src/main/resources/db/migration/fnrh/V2026_11_04_1000__create_fnrh_settings.sql` (cria o schema e a tabela, com uma **linha semente** de `version` 0, desligada e em homologação, para o primeiro `PUT` ter `ETag`) e `V2026_11_04_1001__grant_fnrh_to_app_role.sql`
- Modify: `src/test/kotlin/com/zeny/hotel/ModularityTest.kt` (teste do `fnrh` só com `shared`)
- Test: `fnrh/domain/FnrhSettingsTest.kt`, `fnrh/FnrhConnectionSourceTest.kt` (`@IntegrationTest`)

**Interfaces:**
- Produces:
  - Na raiz: `enum class FnrhEnvironment { HOMOLOGATION, PRODUCTION }` e `FnrhPermissions`. A `FnrhPermissions` traz `SETTINGS_MANAGE = "fnrh.settings.manage"` (sem modelo), `DOMAINS_READ = "fnrh.domains.read"` (`MANAGER`, `RECEPTIONIST`) e `SUBMISSIONS_READ = "fnrh.submissions.read"` (`MANAGER`, `RECEPTIONIST`); nenhuma é sensível.
  - `FnrhErrorCodes` (códigos de problema): `FNRH_SETTINGS_INCOMPLETE`, `FNRH_PASSWORD_REQUIRED_FOR_ENVIRONMENT_CHANGE`, `FNRH_REQUESTER_NOT_FOUND`, `FNRH_DOMAIN_NOT_FOUND`, `FNRH_NOT_CONFIGURED`, `FNRH_CREDENTIALS_REJECTED`, `FNRH_UNAVAILABLE` e `FNRH_REJECTED`. Separado deles, `FnrhFieldCodes` (códigos de campo): `NOT_IN_DOMAIN` e `INCONSISTENT`. Cada uma das Tasks 2 a 7 usa os seus.
  - `domain/FnrhSettings`: ambiente, usuário, senha, CPF padrão, ligado e versão. Operação `update(environment, username, password?, defaultCpf, enabled)`, que aplica estas regras:
    - `password` nula mantém a atual;
    - trocar de ambiente com `password` nula lança `FNRH_PASSWORD_REQUIRED_FOR_ENVIRONMENT_CHANGE`;
    - `enabled = true` sem usuário, senha ou CPF lança `FNRH_SETTINGS_INCOMPLETE`.
  - `FnrhProperties` em `zeny.fnrh`, com `toString` sem segredo:
    - `homologationUrl: URI`, padrão `https://hom-lowcode.serpro.gov.br/FNRH_API/rest/v2`;
    - `productionUrl: URI`, padrão `https://fnrh.turismo.serpro.gov.br/FNRH_API/rest/v2`;
    - `connectTimeout: Duration`, padrão 5 s;
    - `readTimeout: Duration`, padrão 20 s.
  - `application/FnrhConnectionSource { fun current(): FnrhConnection? }`, com `data class FnrhConnection(val environment: FnrhEnvironment, val baseUrl: URI, val username: String, val password: String, val defaultRequesterCpf: Cpf)` e `toString` só com o ambiente. Devolve `null` com a integração desligada.
- [ ] **Step 1: Testes que falham.**
  - `FnrhSettingsTest`: as três regras de `update`; trocar de ambiente com senha nova mantém o usuário.
  - `FnrhConnectionSourceTest`:
    - com a configuração gravada e ligada, `current()` traz a URL do ambiente escolhido;
    - desligada, devolve `null`;
    - as colunas de usuário, senha e CPF lidas por `JdbcTemplate` não contêm o texto claro.
  - `ModularityTest`: o teste do `fnrh`.
- [ ] **Step 2: Rodar e ver falhar.** `./gradlew test --tests 'com.zeny.hotel.fnrh.*' --tests 'com.zeny.hotel.ModularityTest'` → FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Rodar e ver passar.** O mesmo comando e a verificação comum das Global Constraints → PASS.
- [ ] **Step 5: Commit.** Mensagem "Cria o módulo fnrh com a configuração cifrada da integração (M5 E07)", trailer `Soul: desenvolvedor`.

### Task 2: API da configuração

**Files:**
- Create: `fnrh/application/FnrhSettingsService.kt` (`@Audited`, retrato sem senha nem CPF), `fnrh/web/FnrhSettingsController.kt` e DTOs
- Modify: `api/openapi.yaml`, `README.md` (seção "API")
- Test: `fnrh/FnrhSettingsApiTest.kt`

**Interfaces:**
- Consumes: `FnrhSettings`, `FnrhEnvironment` e o repositório da Task 1.
- Endpoints:
  - `GET /api/v1/fnrh/settings` (`SETTINGS_MANAGE`) → `{environment, username, passwordSet, defaultRequesterCpf (mascarado), enabled}`, com `ETag`.
  - `PUT /api/v1/fnrh/settings` (`SETTINGS_MANAGE`, `If-Match` obrigatório) com `{environment, username, password?, defaultRequesterCpf, enabled}`.
    - CPF inválido → `400` com o campo, sem o valor.
    - As regras do domínio → `422` com o código delas.
- [ ] **Step 1: Testes que falham.**
  - `GET` na semente devolve `HOMOLOGATION`, campos nulos, `passwordSet = false` e `enabled = false`.
  - `PUT` completo seguido de `GET` → `passwordSet = true` e o CPF mascarado. O corpo inteiro não contém a senha nem o CPF em claro.
  - `PUT` sem `password` no mesmo ambiente mantém `passwordSet = true`.
  - `PUT` que troca o ambiente sem senha → `422 FNRH_PASSWORD_REQUIRED_FOR_ENVIRONMENT_CHANGE`.
  - `PUT` sem `If-Match` → `428`; com `If-Match` velho → `412`.
  - Recepcionista → `403`.
  - A trilha da alteração não contém a senha nem o CPF.
- [ ] **Step 2: Rodar e ver falhar.** `./gradlew test --tests 'com.zeny.hotel.fnrh.*'` → FAIL.
- [ ] **Step 3: Implementar.** Depois, `./gradlew updateOpenApi` e README.
- [ ] **Step 4: Rodar e ver passar.** O mesmo comando e a verificação comum → PASS.
- [ ] **Step 5: Commit.** Mensagem "Expõe a configuração da FNRH na API (M5 E07)", trailer `Soul: desenvolvedor`.

### Task 3: CPF solicitante por usuário

**Files:**
- Create: `fnrh/domain/RequesterCpfPolicy.kt`, `fnrh/application/FnrhRequestersService.kt` (e a porta `FnrhRequesterDirectory`), persistência em `fnrh/infrastructure/` e `fnrh/web/FnrhRequestersController.kt`
- Create: `db/migration/fnrh/V2026_11_04_1010__create_fnrh_requesters.sql`
- Modify: `api/openapi.yaml`, `README.md`
- Test: `fnrh/domain/RequesterCpfPolicyTest.kt`, `fnrh/FnrhRequestersApiTest.kt`

**Interfaces:**
- Produces:
  - `object RequesterCpfPolicy { fun choose(performedBy: UUID?, cpfOfUser: (UUID) -> Cpf?, defaultCpf: Cpf?): Cpf? }`. Escolhe o CPF do usuário que fez a operação; sem ele, o padrão. Devolve `null` quando não há nenhum dos dois.
  - `interface FnrhRequesterDirectory { fun cpfOf(userId: UUID): Cpf? }`, para a E08.
- Endpoints (`SETTINGS_MANAGE`; o CPF nunca vai na URL):
  - `GET /api/v1/fnrh/requesters` → `[{userId, cpf (mascarado)}]`;
  - `PUT /api/v1/fnrh/requesters/{userId}` `{cpf}` → `200` com o CPF mascarado;
  - `DELETE /api/v1/fnrh/requesters/{userId}` → `204`, ou `404 FNRH_REQUESTER_NOT_FOUND`.
- [ ] **Step 1: Testes que falham.**
  - Na política:
    - com o CPF do usuário, escolhe o dele;
    - sem ele, escolhe o padrão;
    - sem os dois, devolve `null`;
    - com `performedBy = null`, usa o padrão.
  - Na API:
    - o `PUT` seguido de `GET` mostra só o CPF mascarado;
    - a coluna não guarda o texto claro;
    - CPF inválido → `400` sem o valor;
    - `DELETE` de inexistente → `404`;
    - Recepcionista → `403`.
- [ ] **Step 2: Rodar e ver falhar.** `./gradlew test --tests 'com.zeny.hotel.fnrh.*'` → FAIL.
- [ ] **Step 3: Implementar.** Tabela `fnrh.requester`, com `user_id` como chave e o CPF cifrado. O serviço é `@Audited`, sem o CPF no retrato. Depois, `updateOpenApi` e o README.
- [ ] **Step 4: Rodar e ver passar.** O mesmo comando e a verificação comum.
- [ ] **Step 5: Commit.** Mensagem "Cadastra o CPF solicitante por usuário da FNRH (M5 E07)", trailer `Soul: desenvolvedor`.

### Task 4: Porta `FnrhGateway` e adaptador Serpro

**Files:**
- Create:
  - `fnrh/domain/FnrhModel.kt`, ou um arquivo por tipo, com `FnrhReservationData`, `FnrhPersonData`, `FnrhDocument`/`FnrhDocumentType`, `FnrhGuest`, `FnrhGuestStatus`, `FnrhRemoteId`, `FnrhDomain` e `FnrhDomainValue`;
  - `fnrh/application/FnrhGateway.kt` (porta, resultados e `FnrhGatewayException`/`FnrhFailure`) e `fnrh/application/FnrhIntegrationLog.kt` (a **porta** do registro, que a Task 5 implementa);
  - `fnrh/infrastructure/SerproFnrhGateway.kt` e `fnrh/infrastructure/FnrhMessageSanitizer.kt`;
  - `src/test/kotlin/com/zeny/hotel/testsupport/FnrhTestServer.kt`.
- Test: `fnrh/infrastructure/SerproFnrhGatewayTest.kt`, sem contexto Spring: monte o adaptador com `FnrhProperties` de teste, uma `FnrhConnectionSource` falsa e um `FnrhIntegrationLog` em memória. `fnrh/infrastructure/FnrhMessageSanitizerTest.kt`.

**Interfaces** (os nomes do nosso lado ficam em inglês, e o adaptador os mapeia para os do levantamento):

- Consumes: `FnrhConnectionSource` e `FnrhProperties` (Task 1).
- Produces, em `domain`:
  - `FnrhReservationData(reservationCode: String, otaCode: String?, arrival: LocalDate, departure: LocalDate, adults: Int, minors: Int)`:
    - `numero_reserva` = **`Reservation.code`**, nunca o código do `Booking`;
    - `numero_reserva_ota` = `Booking.externalId`;
    - com `otaCode` não nulo, `origem_reserva_id = OTA`; senão, `MEIOHOSPEDAGEM`.
    - O KDoc diz tudo isso e acrescenta que `minors` sai da data de nascimento (menor de 18 anos na entrada), não de `childAges`.
  - `FnrhPersonData`, com os campos de pessoa do levantamento: nome, nome social, nacionalidade ISO, gênero, descrição do gênero, nascimento, raça, opção e tipo de deficiência, `document: FnrhDocument?`, e-mail, telefone, residência ISO, CEP, logradouro, número, complemento, bairro, município IBGE (`Int?`) e UF.
  - `FnrhDocument(type: FnrhDocumentType { CPF, PASSPORT }, number)`.
  - `FnrhGuest(principal: Boolean, checkedInAt: Instant, travelReason: String?, transportMode: String?, responsibleGuestId: FnrhRemoteId?)`: entra sempre com `CHECKIN_REALIZADO` (D-E07-2).
  - `FnrhGuestStatus`, com os valores do domínio.
  - `@JvmInline value class FnrhRemoteId(val value: String)`.
  - `enum class FnrhDomain(val path: String)`, com os 10 caminhos.
  - `FnrhDomainValue(code, label)`.
- Produces, em `application`:
  - `interface FnrhGateway`:
    - `fun registerReservation(reservation: FnrhReservationData, correlationRef: String): FnrhReservationRegistered(reservationId: FnrhRemoteId, precheckinLink: String?)` → `POST /reservas`;
    - `fun updateReservation(reservationId: FnrhRemoteId, reservation: FnrhReservationData, correlationRef: String)` → `PUT /reservas/{id}`;
    - `fun cancelReservation(reservationId: FnrhRemoteId, correlationRef: String)` → `POST /reservas/{id}/cancelar`;
    - `fun findReservationByCode(reservationCode: String, correlationRef: String): FnrhRemoteId?` → `GET /reservas?page_number=1&codigo_reserva=…`, para conciliar um `UNKNOWN_OUTCOME`;
    - `fun registerPerson(person: FnrhPersonData, requesterCpf: Cpf, correlationRef: String): FnrhRemoteId` → `POST /pessoas`, com `cpf_solicitante` (a única operação da porta que o leva);
    - `fun addGuestToReservation(reservationId: FnrhRemoteId, personId: FnrhRemoteId, guest: FnrhGuest, correlationRef: String): FnrhRemoteId` → `POST /reservas/{id}/hospedes`, com `situacao_hospede_id = CHECKIN_REALIZADO`, `checkin_em`, `fnrh{motivo_viagem_id, meio_transporte_id}` e `responsavel_id`;
    - `fun checkOut(guestId: FnrhRemoteId, at: Instant, correlationRef: String)` → `PATCH /hospedes/{id}/checkout`, com corpo `text/plain`;
    - `fun fetchDomain(domain: FnrhDomain): List<FnrhDomainValue>`.
    - O `correlationRef` é uma referência opaca nossa (o id da reserva, ou da submissão na E08), nunca um dado pessoal.
  - `class FnrhGatewayException(val failure: FnrhFailure, val httpStatus: Int?, val sanitizedMessage: String?)`, sem causa encadeada.
  - `enum class FnrhFailure(val temporary: Boolean) { NOT_CONFIGURED(false), VALIDATION(false), UNAUTHORIZED(false), FORBIDDEN(false), NOT_FOUND(false), SERVER_ERROR(true), UNAVAILABLE(true), UNKNOWN_OUTCOME(true) }`, com este mapeamento:

    | O que acontece | `FnrhFailure` |
    |---|---|
    | `400`, `409`, `415`, `422` ou outro `4xx` não listado abaixo | `VALIDATION` |
    | `401` | `UNAUTHORIZED` |
    | `403` | `FORBIDDEN` |
    | `404` | `NOT_FOUND` |
    | `429`, `3xx` ou `5xx` | `SERVER_ERROR` |
    | conexão recusada ou timeout de conexão | `UNAVAILABLE` (nada foi enviado) |
    | timeout de leitura em `GET` | `UNAVAILABLE` |
    | timeout de leitura em `POST`, `PUT` ou `PATCH` | `UNKNOWN_OUTCOME` |
    | integração sem configuração | `NOT_CONFIGURED`, sem chamada |
  - `interface FnrhIntegrationLog { fun record(call: FnrhCall) }`, com `data class FnrhCall(operation: String, environment: FnrhEnvironment, outcome: String, httpStatus: Int?, durationMs: Long, correlationRef: String?, remoteId: String?)`. O `outcome` é `SUCCESS` ou um nome de `FnrhFailure`. O adaptador chama o `record` uma vez por chamada HTTP.
- Produces, em `infrastructure`: `object FnrhMessageSanitizer { fun sanitize(raw: String?, sentValues: Collection<String>): String? }`. Troca por `***` os valores enviados (só os de 3 caracteres ou mais) e cada palavra deles com 3 ou mais letras, ignorando maiúsculas e acentos, porque o Serpro pode ecoar só "MARIA" ou "Maria da Luz". Troca também as sequências de 5 ou mais dígitos e os e-mails, e corta em 200 caracteres. A mensagem vem de `mensagem` no corpo JSON, quando existir, e o corpo bruto nunca é usado.

- [ ] **Step 1: Testes que falham.**
  - Em `SerproFnrhGatewayTest`, um teste por operação, com o `FnrhTestServer`. Cada um confere método, caminho e `Authorization: Basic` com o usuário e a senha da conexão. Confere também que `cpf_solicitante` aparece **só** em `registerPerson` e está ausente nas demais, e confere o corpo exato e o id remoto mapeado:
    - `registerReservation` → `reserva.reserva_id` e `link_precheckin`;
    - `addGuestToReservation` → `checkin_em` como `"2026-02-10T15:00:00.000Z"`, mesmo quando o `Instant` não tem milissegundos;
    - `checkOut` → `Content-Type: text/plain` e corpo `2026-02-15T11:00:00.000Z`, sem aspas;
    - `findReservationByCode` → o primeiro `reserva_id` de `dados`, ou `null` com a lista vazia;
    - `fetchDomain(TRANSPORT_MODES)` → `GET /dominios/fnrh/meios_transporte`.
  - Ainda em `SerproFnrhGatewayTest`, os erros:
    - `400` com `{"mensagem": "Pessoa MARIA DA LUZ, CPF 12345678901, maria@x.com já existe"}` (nome e CPF iguais aos enviados) → `VALIDATION`, e o `sanitizedMessage` não traz o nome, o número nem o e-mail;
    - `401` → `UNAUTHORIZED`;
    - `503` → `SERVER_ERROR`;
    - `POST` com atraso maior que o `read-timeout` de teste (300 ms contra 100 ms) → `UNKNOWN_OUTCOME`;
    - `GET` com o mesmo atraso → `UNAVAILABLE`;
    - porta fechada → `UNAVAILABLE`;
    - sem configuração → `NOT_CONFIGURED`, sem nenhuma requisição;
    - em todos os casos, a exceção não tem causa e o log em memória recebeu uma `FnrhCall` com o `outcome` certo.
  - Em `FnrhMessageSanitizerTest`: os três padrões e o corte em 200 caracteres.
- [ ] **Step 2: Rodar e ver falhar.** `./gradlew test --tests 'com.zeny.hotel.fnrh.infrastructure.*'` → FAIL.
- [ ] **Step 3: Implementar.**
  - O `RestClient` usa a `baseUrl` da conexão de cada chamada.
  - Use a `JdkClientHttpRequestFactory` com os timeouts.
  - Os DTOs internos levam os nomes do Serpro via `@JsonProperty`.
  - A porta não expõe nenhum tipo do Serpro.
- [ ] **Step 4: Rodar e ver passar.** O mesmo comando e a verificação comum.
- [ ] **Step 5: Commit.** Mensagem "Cria a porta FnrhGateway e o adaptador Serpro da API v2.4 (M5 E07)", trailer `Soul: desenvolvedor`.

### Task 5: Registro de integração, status e métrica

**Files:**
- Create: `fnrh/infrastructure/JdbcFnrhIntegrationLog.kt` (implementa a porta da Task 4 com `TransactionTemplate` `PROPAGATION_REQUIRES_NEW`, no modelo do `PushDeliveryLog`), `fnrh/infrastructure/FnrhMetrics.kt` (`MeterBinder`), `fnrh/application/FnrhStatusService.kt` (leitura, sem `@Transactional` de escrita), `fnrh/web/FnrhStatusController.kt`
- Create: `db/migration/fnrh/V2026_11_04_1020__create_fnrh_integration_calls.sql`: tabela *append-only* `fnrh.integration_call` (`id`, `operation`, `environment`, `outcome`, `http_status`, `duration_ms`, `correlation_ref`, `remote_id`, `occurred_at`), sem coluna de texto livre, com índice `(environment, outcome, occurred_at)`, trigger e `REVOKE UPDATE, DELETE`
- Modify: `api/openapi.yaml`, `README.md`
- Test: `fnrh/FnrhIntegrationLogTest.kt` (`@IntegrationTest` + `OutputCaptureExtension`), `fnrh/FnrhStatusApiTest.kt`

**Interfaces:**
- Consumes: `FnrhIntegrationLog`/`FnrhCall` e `SerproFnrhGateway` (Task 4).
- Produces:
  - Uma linha de log por chamada: `INFO` no sucesso, `WARN` na falha. Leva `operation`, `environment`, `outcome`, `httpStatus` e `durationMs`, mais a mensagem saneada quando houver. A URL nunca entra.
  - `GET /api/v1/fnrh/status` (`SUBMISSIONS_READ`) devolve `{configured, enabled, environment, lastSuccessAt, lastFailureAt, lastFailureOutcome}`, com os instantes do **ambiente atual**. `lastFailureOutcome` é `x-extensible-enum`.
  - Métricas (`MeterBinder`; os gauges leem do banco, como o status):
    - `zeny.fnrh.calls`: contador com as tags `operation`, `outcome` e `environment`, incrementado no `record`;
    - `zeny.fnrh.last.success` e `zeny.fnrh.last.failure`: gauges com `baseUnit` segundos (época do último registro do ambiente atual; `NaN` sem registro);
    - `zeny.fnrh.configured`: 0 ou 1.
- [ ] **Step 1: Testes que falham.**
  - `FnrhIntegrationLogTest`, com a configuração ligada apontando para o `FnrhTestServer` e uma `@TestConfiguration` única, compartilhada com a Task 6:
    - `registerPerson` com nome, CPF, raça, deficiência e senha conhecidos, seguido de um `400` que ecoa o nome e o CPF;
    - o log capturado e as linhas de `fnrh.integration_call` não contêm o nome, o CPF, o código de raça, o tipo de deficiência nem a senha;
    - há uma linha por chamada, com o `outcome` certo e o `correlation_ref`;
    - a linha fica gravada mesmo quando o chamador está numa transação que faz rollback;
    - `UPDATE` na tabela falha.
  - `FnrhStatusApiTest`:
    - depois de um sucesso e de uma falha, o status traz os dois instantes e o `outcome`;
    - registros de outro ambiente não entram;
    - `/actuator/prometheus` traz `zeny_fnrh_calls_total`, `zeny_fnrh_last_success_seconds` e `zeny_fnrh_configured`;
    - Camareira → `403`.
- [ ] **Step 2: Rodar e ver falhar.** `./gradlew test --tests 'com.zeny.hotel.fnrh.*'` → FAIL.
- [ ] **Step 3: Implementar.** Depois, `updateOpenApi` e README.
- [ ] **Step 4: Rodar e ver passar.** O mesmo comando, a verificação comum e `--tests '*ActuatorExposureTest'`.
- [ ] **Step 5: Commit.** Mensagem "Registra as chamadas à FNRH com status e métrica, sem dado pessoal (M5 E07)", trailer `Soul: desenvolvedor`.

### Task 6: Domínios oficiais com cache

**Files:**
- Create: `fnrh/domain/FnrhDomainCatalog.kt` (interface), `fnrh/application/FnrhDomainsService.kt`, persistência e implementação do catálogo em `fnrh/infrastructure/`, `fnrh/web/FnrhDomainsController.kt`
- Create: `db/migration/fnrh/V2026_11_04_1030__create_fnrh_domain_values.sql`, com `fnrh.domain_value (domain, code, label, refreshed_at)`, chave `(domain, code)`, e a semente com **todos os `id` e `label` da tabela "Domínios" de `docs/fnrh/api-fnrh-v2.4.md`**
- Modify: `api/openapi.yaml`, `README.md`
- Test: `fnrh/FnrhDomainsApiTest.kt`

**Interfaces:**
- Consumes: `FnrhGateway.fetchDomain` e `FnrhDomain` (Task 4).
- Produces:
  - `interface FnrhDomainCatalog { fun contains(domain: FnrhDomain, code: String): Boolean; fun values(domain: FnrhDomain): List<FnrhDomainValue> }`, que a Task 7 consome.
  - `GET /api/v1/fnrh/domains/{domain}` (`DOMAINS_READ`) → `[{code, label}]`.
    - A web recebe o `{domain}` como `String` em kebab-case: `transport-modes`, `travel-reasons`, `guest-statuses`, `genders`, `disability-options`, `races`, `disability-types`, `document-types`, `booking-statuses` ou `record-statuses`.
    - A application converte. Um nome desconhecido dá `404 FNRH_DOMAIN_NOT_FOUND`.
  - `POST /api/v1/fnrh/domains/refresh` (`SETTINGS_MANAGE`):
    - O `FnrhDomainsService`, sem transação, confere a permissão e busca os 10 domínios **fora de transação**. Depois chama **outro bean**, `application/FnrhDomainValuesWriter`, cujo método `@Audited @Transactional` só grava a substituição. A gravação fica num bean separado porque a autoinvocação na mesma classe pularia o proxy, e com ele a transação e a trilha. Se qualquer busca falhar, nada muda.
    - `NOT_CONFIGURED` → `422 FNRH_NOT_CONFIGURED`; `UNAUTHORIZED`/`FORBIDDEN` → `422 FNRH_CREDENTIALS_REJECTED`; as falhas temporárias → `503 FNRH_UNAVAILABLE`; `VALIDATION`/`NOT_FOUND` → `502 FNRH_REJECTED`.
    - A resposta traz a contagem por domínio.
  - A web nunca devolve `FnrhDomainValue` (domain): a application o mapeia para um tipo dela.
- [ ] **Step 1: Testes que falham.**
  - Sem `refresh`, `GET …/transport-modes` devolve os 8 valores da semente, com os rótulos.
  - Com um valor novo no WireMock, o `refresh` faz o valor aparecer e remove um que sumiu.
  - Um `503` em um domínio deixa todos como estavam.
  - `refresh` sem configuração → `422`; com `401` → `422 FNRH_CREDENTIALS_REJECTED`.
  - Um `refresh` bem-sucedido deixa uma entrada na trilha de auditoria. Isso prova que a gravação passou pelo proxy, com transação e `@Audited`.
  - A Recepcionista lê, e o `refresh` dela → `403`.
- [ ] **Step 2: Rodar e ver falhar.** `./gradlew test --tests 'com.zeny.hotel.fnrh.*'` → FAIL.
- [ ] **Step 3: Implementar.** Depois, `updateOpenApi` e README.
- [ ] **Step 4: Rodar e ver passar.** O mesmo comando e a verificação comum.
- [ ] **Step 5: Commit.** Mensagem "Guarda os domínios oficiais da FNRH com atualização sob demanda (M5 E07)", trailer `Soul: desenvolvedor`.

### Task 7: Mapeamento dos dados do hóspede e validação local

**Files:**
- Create: `fnrh/domain/FnrhPersonMapper.kt`, `fnrh/domain/FnrhLocalValidator.kt`, `fnrh/domain/FnrhFieldError.kt` e `fnrh/domain/IbgeMunicipalities.kt` (lê o recurso com `getResourceAsStream`, do JDK, sem o Spring)
- Create: `src/main/resources/fnrh/ibge-municipios.csv`. O cabeçalho leva `# fonte: https://servicodados.ibge.gov.br/api/v1/localidades/municipios`, a data do download, o SHA-256 do JSON baixado e a transformação usada. Depois, uma linha `codigo;uf` por município.
- Modify: `FnrhModule.kt` e `ModularityTest.kt`, para incluir `guests`
- Test: `fnrh/domain/FnrhPersonMapperTest.kt`, `fnrh/domain/FnrhLocalValidatorTest.kt`, `fnrh/domain/IbgeMunicipalitiesTest.kt`

**Interfaces:**
- Consumes:
  - `FnrhGuestData`/`FnrhGuestDocument`, `Address`, `Gender`, `RaceColor` e `DocumentType`, de `guests/GuestsApi.kt`; o domínio de `disability` está em `guests/domain/SensitiveGuestData.kt`;
  - `FnrhPersonData`, `FnrhDocument` e `FnrhGuest` (Task 4);
  - `FnrhDomainCatalog` (Task 6).
- Produces (domain):
  - `object FnrhPersonMapper { fun toPerson(guest: FnrhGuestData): FnrhPersonData }`:
    - documento: o CPF, se houver; senão o passaporte; os outros tipos não entram;
    - gênero: `FEMALE`→`MULHER`, `MALE`→`HOMEM`, `OTHER`→`OUTRO` (sem descrição, D-E07-11), `NOT_INFORMED`→`NAOINFORMADO`, nulo→nulo;
    - raça: `WHITE`→`BRANCA`, `BLACK`→`PRETA`, `BROWN`→`PARDA`, `YELLOW`→`AMARELA`, `INDIGENOUS`→`INDIGENA`, `NOT_DECLARED`→`NAOINFORMAR`, nula→nula;
    - deficiência: nula→nula; um código de `tipos_deficiencia`→opção `SIM` com esse tipo; `NAO` ou `NAOINFORMAR`→essa opção, sem tipo;
    - endereço: o CEP só com dígitos, o município, a UF e a residência; o que o `Address` não tiver fica nulo, e o relatório lista o que faltou.
  - `data class FnrhFieldError(val field: String, val code: String)`. Nunca carrega o valor.
    - O `field` é o caminho no `FnrhPersonData`/`FnrhGuest` (`person.document`, `person.nationality`, `person.cityIbge`, `guest.travelReason`…).
    - O `code` é `REQUIRED`, `INVALID_VALUE`, `NOT_IN_DOMAIN` ou `INCONSISTENT`.
  - `class FnrhLocalValidator(catalog: FnrhDomainCatalog, municipalities: IbgeMunicipalities, countries: Set<String> = Locale.getISOCountries().toSet()) { fun validate(person: FnrhPersonData, guest: FnrhGuest, arrival: LocalDate, responsibleKnown: Boolean): List<FnrhFieldError> }`. As regras:
    - nome obrigatório;
    - documento obrigatório; o CPF passa pelo `Cpf` (`INVALID_VALUE`);
    - nacionalidade e residência ISO alpha-2 (`NOT_IN_DOMAIN`);
    - data de nascimento, gênero e raça obrigatórios (D-E07-10);
    - gênero, raça, opção e tipo de deficiência, motivo de viagem e meio de transporte no catálogo (`NOT_IN_DOMAIN`);
    - motivo e meio de transporte obrigatórios;
    - `OUTRO` exige descrição; `SIM` exige o tipo;
    - com residência `BR`:
      - município obrigatório e na lista do IBGE;
      - UF obrigatória e igual à do município (`INCONSISTENT`);
      - CEP de 8 dígitos quando informado, e nesse caso logradouro, número e bairro obrigatórios;
    - o menor de 18 anos na data de `arrival`, com `responsibleKnown = false`, gera `guest.responsibleGuestId`/`REQUIRED`.
  - `class IbgeMunicipalities { fun ufOf(code: Int): String? }`, carregada uma vez.

- [ ] **Step 1: Baixar a lista do IBGE.** Use `curl` na URL acima e gere o CSV `codigo;uf` a partir de `id` e da sigla da UF do município, no caminho que o JSON real trouxer. Confira:
  - são mais de 5.500 linhas;
  - `3550308;SP`, `3304557;RJ`, `4303509;RS` e `5300108;DF` estão presentes.

  Se a rede falhar, devolva NEEDS_CONTEXT.
- [ ] **Step 2: Escrever os testes que falham.**
  - `FnrhLocalValidatorTest`:
    - Hóspede válido (brasileiro, CPF válido, residência `BR`, `3550308`/`SP`, motivo `LAZER_FERIAS`, meio `AVIAO`) → nenhum erro.
    - Cada caso abaixo gera exatamente o erro esperado:
      - CPF com dígito errado → `person.document`/`INVALID_VALUE`;
      - só RG, que o mapeamento descarta → `person.document`/`REQUIRED`;
      - nacionalidade `XX` → `NOT_IN_DOMAIN`;
      - município `9999999` → `person.cityIbge`/`NOT_IN_DOMAIN`;
      - `3550308` com UF `RJ` → `INCONSISTENT`;
      - residência `AR` sem município → nenhum erro de município;
      - meio `TELEPORTE` → `NOT_IN_DOMAIN`;
      - menor sem responsável → `REQUIRED`;
      - `OUTRO` sem descrição → `person.genderDescription`/`REQUIRED`;
      - deficiência `SIM` sem tipo;
      - raça nula → `person.race`/`REQUIRED`.
    - Nenhum `FnrhFieldError`, nem o `toString` dele, contém o valor rejeitado.
  - `FnrhPersonMapperTest`:
    - cada enum mapeado;
    - o CPF preferido ao passaporte;
    - o `toString` de `FnrhPersonData` e de `FnrhDocument` sem nome nem número.
  - `IbgeMunicipalitiesTest`: `ufOf(3550308) == "SP"`; `ufOf(9999999) == null`.
- [ ] **Step 3: Rodar e ver falhar.** `./gradlew test --tests 'com.zeny.hotel.fnrh.domain.*'` → FAIL.
- [ ] **Step 4: Implementar** e atualizar `FnrhModule`/`ModularityTest` (`shared`, `guests`).
- [ ] **Step 5: Rodar e ver passar.** O mesmo comando e a verificação comum.
- [ ] **Step 6: Verificação da etapa.** `./gradlew build` → `BUILD SUCCESSFUL`. `.github/scripts/check-api-breaking.sh` contra o início da etapa → nenhuma mudança `ERR`. Só acréscimos são esperados.
- [ ] **Step 7: Commit.** Mensagem "Valida localmente os dados da FNRH contra os domínios e o IBGE (M5 E07)", trailer `Soul: desenvolvedor`.

## Notas para o fechamento (orquestrador)

- **Handoff:**
  - o levantamento, em `docs/fnrh/api-fnrh-v2.4.md`, com o SHA-256 do PDF;
  - as decisões D-E07-1 a 12 em "Decisões para o usuário", com a 2, a 9 e a 11 em destaque;
  - a nota B2 da E02: um menor brasileiro só com RG não é aceito pela API, que pede CPF ou passaporte;
  - a defasagem do recurso IBGE (D-E07-3) como risco.
- **Notas para a E08:**
  - não há desfazer check-in na API;
  - o cancelamento só vale em `CRIADA`;
  - os ids remotos dependem do ambiente;
  - `UNKNOWN_OUTCOME` se concilia por `findReservationByCode`;
  - a chamada ao Serpro roda fora da transação do banco;
  - `minors` sai da data de nascimento;
  - o `performedBy` só pesa no `registerPerson`;
  - `UNKNOWN_OUTCOME` no `addGuestToReservation` se concilia por `GET /reservas/{id}/hospedes`; no `registerPerson`, decidir entre aceitar o risco de pessoa duplicada e conciliar por `GET /pessoas/documento/…` (documento no caminho, com `cpf_solicitante`);
  - o responsável (`responsavel_id` = id remoto do **hóspede** adulto) entra antes do menor; a E09 confirma se um responsável de outra `Reservation` do mesmo Booking é aceito;
  - o `sanitizedMessage` só vai para a fila de pendências ou para coluna persistente depois do mesmo saneamento, e nunca para tabela *append-only*.
- **Premissas a confirmar na E09:** o `POST /reservas/{id}/hospedes` aceita `CHECKIN_REALIZADO` numa reserva `CRIADA`; o filtro `codigo_reserva` casa com `numero_reserva`.
- **Captura do brain:** nota nova `ZenyHotelManager - Módulo fnrh`; as permissões novas na nota de segurança.
- **Documentação:**
  - na `docs/ARCHITECTURE.md` §12, a linha da FNRH diz "header `cpf_solicitante`"; deve dizer "só nas operações de pessoa e de hospedagem completa";
  - na §16, confira o "painel de saúde";
  - na seção da E07 no roadmap, o objetivo e o primeiro critério supõem `cpf_solicitante` em toda operação: registre a correção no handoff.
