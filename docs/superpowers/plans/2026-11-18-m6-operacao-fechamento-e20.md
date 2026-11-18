# E20 do M6 — Entrada estrita no corpo, na query e no caminho, e `required` no contrato: plano de implementação

> **Para agentes:** execução por `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde, não o código.

**Goal:** completar no `shared`, para todos os módulos, a leitura estrita da E18: faixa do `StorableRange` no corpo, datas e instantes estritos em query e caminho, `LocalTime`, inteiro e UUID estritos no JSON, e o `required` do contrato gerado automaticamente a partir de toda regra de obrigatoriedade do DTO.

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E20", com H13, H20 (c e d) e H21 (d); handoffs da E18 ("Decisões para o usuário" (c) e (e), "Notas para a próxima etapa") e da E01 ("Para o `arquiteto`"); ARCHITECTURE §7 (linhas "Datas e valores" e "Contrato") e §11 (`StorableRange`). Brain: `ZenyHotelManager - Convenções de API` e a captura pendente `atualização m6-operacao-fechamento E18 (refação)`.

**Architecture:** tudo vive no `shared`. O corpo JSON e os payloads offline passam pelo mapeador único do Jackson 3: o `StrictDateTimeJson` (E18) ganha a faixa, o `LocalTime` e as regras de inteiro e de UUID. Query e caminho são conversão do Spring MVC: um registro de formatadores estritos no `WebMvcConfigurer` do `shared` substitui a conversão leniente de `LocalDate` e `Instant` para todo `@RequestParam` e `@PathVariable`, sem código por endpoint. O `required` do contrato sai de um customizador do springdoc no `shared` que lê as mesmas regras que o servidor aplica.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1 / Spring Framework 7 (Spring MVC, `FormatterRegistry`), Jackson 3 (`tools.jackson`), springdoc 3.1.1 (swagger-core com Jackson 2 e `jackson-module-kotlin`), Testcontainers.

**Riscos:**
- **parsing de entrada externa não confiável:** corpo JSON e payloads offline (Task 1), parâmetros de query e de caminho (Task 2). É o tema da etapa inteira.

Nenhum outro item da lista fechada é tocado: sem autenticação, dado pessoal, valor monetário, migração ou concorrência novos.

## Global Constraints

- Respeitar o grafo de módulos e a regra por camadas da ARCHITECTURE §3.1; esta etapa não cria aresta. O código novo fica em `src/main/kotlin/com/zeny/hotel/shared/`.
- Sem migração.
- Toda mudança de API regenera o contrato `api/openapi.yaml` com `./gradlew updateOpenApi` na mesma mudança (nunca editado à mão); `./gradlew checkOpenApi` passa.
- `.github/scripts/check-api-breaking.sh` só pode acusar `ERR` do tipo "propriedade de request passou a ser obrigatória" (H20 d), listados no relatório da Task 3; nenhum outro `ERR`. As Tasks 1 e 2 não mudam o contrato.
- Erro de entrada: `400 VALIDATION_FAILED` com o campo ou o parâmetro em `errors[]` e `code` `INVALID_FORMAT`; a mensagem nunca repete o valor recebido. "Sem valor" é o campo ausente ou `null`.
- Biblioteca nova só pelo catálogo `gradle/libs.versions.toml`, com os lockfiles regravados (não se espera nenhuma).
- O `SerproFnrhWire` tem mapeador próprio e fica fora.
- Código, nomes e testes em inglês; KDoc, comentários, ARCHITECTURE e mensagens de commit em pt-BR (D17).
- Commits por fatia com o trailer `Soul: <soul>` no mesmo parágrafo do `Co-Authored-By`.
- Máquina de 14 GB: um Gradle por vez; nunca `./gradlew --stop`; todo comando longo com `timeout` (testes focados: `timeout 900`; build: `timeout 1800`); nunca laço de espera (`while pgrep …`, `sleep` em laço).
- A verificação da fatia são os testes focados que ela cita mais `timeout 900 ./gradlew lint`; a suíte inteira roda no gate de integração do orquestrador.
- Os testes de integração usam `@IntegrationTest` (banco compartilhado, que nunca avança a data operacional) e afirmam só sobre os próprios dados.
- Testes que hoje mandam fracionário, `""` num UUID, formato curto ou epoch para entrada legítima são dívida de teste e mudam junto, cada um listado no relatório da fatia.

## Review Focus

1. **Inteiro com fração zero ou notação científica** (`7.0`, `7e0`, `1E2`): é número fracionário no JSON e é recusado com `INVALID_FORMAT`, como `7.5`; o mesmo num `Long` e num inteiro dentro de lista ou objeto aninhado. Teste na Task 1.
2. **`""` num UUID dentro de lista ou de objeto aninhado, e num payload offline:** recusado no campo, com o caminho completo (`items[0].roomId`); offline volta `REJECTED COMMAND_PAYLOAD_INVALID`. Teste na Task 1.
3. **`LocalTime` com fração, `24:00` ou hora de um dígito** (`"09:05:30.5"`, `"24:00"`, `"9:05"`): recusados; `"00:00"` e `"23:59:59"` aceitos. Teste na Task 1.
4. **Parâmetro de query vazio e repetido** (`?from=` e `?from=a&from=b`): o vazio num parâmetro opcional segue "ausente" (comportamento do Spring, decisão D2); o vazio num obrigatório dá `400` no parâmetro, nunca 500. Teste na Task 2.
5. **Data no caminho com o ano com sinal** (`/assignment-plans/+5874898-01-01` e `/assignment-plans/-4713-12-31`) e no teto exato (`+5874897-12-31`): fora da faixa dá `400` no parâmetro `date`, não 500 nem `-infinity`; o teto passa da conversão (o resultado da operação depois disso não importa). Teste na Task 2.

## Decisões do plano

- **D1 — premissa do item 5 corrigida (ruling do orquestrador, 2026-11-18).** O roadmap e o handoff da E01 dizem que os schemas de request não marcam `required`. No código, o swagger-core já marca `required` nos campos com `@NotNull`, `@NotBlank` e `@NotEmpty` e nas propriedades Kotlin não anuláveis (ex.: `CleaningTypeRequest` sai com `required: [code, credits, defaultPriority, name, requiresInspection]`, e 111 dos 115 schemas de corpo têm `required`). O item 5 vira: (a) uma regra única, no `shared`, que gera o `required` de toda regra de obrigatoriedade do DTO, inclusive as que o swagger-core não enxerga hoje; (b) um teste de contrato que prende a regra para todos os schemas de request; (c) o fechamento das lacunas, com a lista dos `ERR` no relatório (pode ser curta ou vazia). A quebra aceita pela H20 d continua valendo só para "propriedade de request passou a ser obrigatória".
- **D2 — vazio em query.** `?param=` num parâmetro opcional continua "ausente" (o Spring trata o texto vazio como ausente antes da conversão). Num parâmetro obrigatório, dá `400` com `REQUIRED` ou `INVALID_FORMAT` no parâmetro, como já dá hoje. Mudar isso fugiria do escopo e quebraria o padrão de query dos clientes.
- **D3 — `nullable: true` nos campos obrigatórios fica.** Os DTOs usam propriedades anuláveis com `@NotNull` para o erro sair `REQUIRED` no campo, e o contrato as declara `nullable`. Tirar o `nullable` é outro tipo de `ERR` no `oasdiff` ("request property became not nullable"), que a H20 d não aceita. Fica como nota para o `arquiteto` no handoff.
- **D4 — fração em inteiro.** Qualquer token de ponto flutuante num campo `Int`, `Long` ou `Short` é recusado, inclusive `7.0` e notação científica. Inteiro em texto (`"7"`) segue como hoje e não entra no escopo; o comportamento atual vai para o relatório da Task 1.

---

### Task 1: Corpo JSON estrito: faixa, `LocalTime`, inteiro e UUID

**Objetivo:** o mapeador único da aplicação recusa, com `400 VALIDATION_FAILED` e `INVALID_FORMAT` no campo, `LocalDate` e `Instant` fora do `StorableRange`, `LocalTime` fora de `HH:mm`/`HH:mm:ss`, número fracionário em campo inteiro e `""` num UUID. Vale para todo corpo de `/api/v1` e para os payloads offline (`REJECTED COMMAND_PAYLOAD_INVALID`). Fecha a regressão (e) da E18.

**Critérios de aceite:**
- `StrictDateTimeJsonApiTest` (estendido, `@IntegrationTest`):
  - no `POST` de recado do `frontdesk`, `dueOn` `+5874898-01-01` e `-4713-12-31` dão `400 VALIDATION_FAILED` com `INVALID_FORMAT` em `dueOn`, e `+5874897-12-31` e `-4712-01-01` passam da leitura do corpo; o relatório traz o vermelho antes da correção com `+5874898-01-01` (500);
  - no `expiresAt` da pré-autorização de cartão do `billing`, `+294277-01-01T00:00:00Z` e `-4713-12-31T23:59:59Z` dão `400` no campo, e `-4712-01-01T00:00:00Z` e `+294276-12-31T23:59:59.999999Z` passam da leitura;
  - na política do hotel (`checkInTime`), número, `"9:5"`, `"09:05:00Z"`, `"09:05:30.5"`, `"24:00"` e `""` dão `400` no campo, e `"09:05"` e `"09:05:30"` são aceitos;
  - `frequencyDays: 7.5` no `POST` de faxina programada e `credits: 1.5` num crédito do tipo de limpeza dão `400 VALIDATION_FAILED` com `INVALID_FORMAT` no campo (com o caminho, `credits[0].credits`), e `7` é aceito; o relatório traz o vermelho antes da correção;
  - um `PUT` de tipo de limpeza com `checklistTemplateId: ""` dá `400` com `INVALID_FORMAT` no campo e não remove o vínculo (o `GET` seguinte ainda o traz); com `null`, remove; o relatório traz o vermelho antes da correção.
  - Verificação: `timeout 900 ./gradlew test --tests '*StrictDateTimeJsonApiTest'`.
- `StrictDateTimeJsonMapperTest` (estendido, sobre o `JsonMapper` injetado do contexto): recusa os formatos acima e os do Review Focus 1 a 3 para `LocalTime`, `Int`, `Long` e `UUID`, também aninhados e em lista, com o caminho completo; aceita os extremos do `StorableRange` em `LocalDate` e `Instant`; `null` e ausente não mudam. Verificação: `timeout 900 ./gradlew test --tests '*StrictDateTimeJsonMapperTest'`.
- Offline: em `OfflineSyncTest`, um comando com data fora do `StorableRange` e outro com `""` num UUID voltam `REJECTED COMMAND_PAYLOAD_INVALID`, sem efeito; com o valor válido, é aplicado. Verificação: `timeout 900 ./gradlew test --tests '*OfflineSyncTest'`.
- Releitura do JSON que o servidor grava: `timeout 1200 ./gradlew test --tests 'com.zeny.hotel.reservations.*'` e `timeout 900 ./gradlew test --tests 'com.zeny.hotel.checklists.*'` verdes.
- `timeout 900 ./gradlew checkOpenApi` passa sem regenerar o contrato.
- `timeout 900 ./gradlew lint` verde.

**Áreas tocadas:** `src/main/kotlin/com/zeny/hotel/shared/infrastructure/json/StrictDateTimeJson.kt` (pode ganhar um nome mais largo, como `StrictJson.kt`, com o KDoc atualizado), `src/main/kotlin/com/zeny/hotel/shared/web/FieldViolations.kt` (só se o erro não sair no campo). Testes: `src/test/kotlin/com/zeny/hotel/shared/web/StrictDateTimeJsonApiTest.kt`, `StrictDateTimeJsonMapperTest.kt`, o `OfflineSyncTest` e os testes lenientes que mudam.

**Tamanho:** ~50 min; 1 a 2 arquivos de produção.

**Depende de:** nenhuma.

**Interfaces (produz):** nenhuma API nova para os módulos; o mapeador único fica estrito também em `LocalTime`, inteiros e `UUID`, e na faixa do `StorableRange` (`com.zeny.hotel.shared.StorableRange.contains`, da E18).

### Task 2: Datas e instantes estritos em query string e caminho

**Objetivo:** todo `@RequestParam` e `@PathVariable` de tipo `LocalDate` ou `Instant` só aceita o texto ISO-8601 (`ISO_LOCAL_DATE` e `ISO_INSTANT`, como no corpo) dentro do `StorableRange`; o resto dá `400 VALIDATION_FAILED` com o parâmetro em `errors[]` (`INVALID_FORMAT`), nunca 500 nem `-infinity`. Vale para os endpoints atuais e futuros, sem código por endpoint.

**Critérios de aceite:**
- `StrictParametersApiTest` (novo, `@IntegrationTest`): em `GET /api/v1/traces` (`dueOn`), `GET /api/v1/audit-entries` (`from`), `GET /api/v1/card-pre-authorizations/expiring` (`to`) e `GET /api/v1/assignment-plans/{date}`, o formato curto do locale (`13/11/25`), epoch em ms (`1763000000000`), data-hora numa data, o valor fora da faixa por cima e por baixo (`+5874898-01-01`/`-4713-12-31` nas datas, `+294277-01-01T00:00:00Z`/`-4713-12-31T23:59:59Z` nos instantes) dão `400 VALIDATION_FAILED` com o nome do parâmetro em `errors[].field` e `INVALID_FORMAT`; o ISO dentro da faixa continua aceito (não é 400). Os casos do Review Focus 4 e 5 também. O relatório traz o vermelho antes da correção em pelo menos um parâmetro de query e no de caminho. Os usuários do teste têm a permissão de cada endpoint, para que a resposta venha da conversão e não do `403`. Verificação: `timeout 900 ./gradlew test --tests '*StrictParametersApiTest'`.
- Nenhum `@DateTimeFormat` local fica divergente da regra: `grep -rn 'DateTimeFormat' src/main/kotlin` só mostra usos compatíveis (ou nenhum), e o relatório diz o que fez com cada um.
- As suítes dos módulos com parâmetros de data seguem verdes: `timeout 1800 ./gradlew test --tests 'com.zeny.hotel.reservations.*' --tests 'com.zeny.hotel.audit.*' --tests 'com.zeny.hotel.billing.*' --tests 'com.zeny.hotel.frontdesk.*' --tests 'com.zeny.hotel.housekeeping.*' --tests 'com.zeny.hotel.staff.*' --tests 'com.zeny.hotel.rooms.*' --tests 'com.zeny.hotel.checklists.*'`.
- `timeout 900 ./gradlew checkOpenApi` passa sem regenerar o contrato (o `format` dos parâmetros já é `date`/`date-time`).
- `timeout 900 ./gradlew lint` verde.

**Áreas tocadas:** `src/main/kotlin/com/zeny/hotel/shared/web/ApiWebConfiguration.kt` (ou um arquivo novo `StrictParameterFormats.kt` no mesmo pacote, registrado por `addFormatters`), `src/main/kotlin/com/zeny/hotel/shared/web/ApiExceptionHandler.kt` (só se a falha de conversão não sair como `INVALID_FORMAT` no parâmetro). Testes: `src/test/kotlin/com/zeny/hotel/shared/web/StrictParametersApiTest.kt` (novo) e os testes que mandam formato leniente.

**Tamanho:** ~45 min; 1 a 3 arquivos de produção.

**Depende de:** nenhuma.

**Interfaces (produz):** nenhuma API nova para os módulos; os conversores de `String` → `LocalDate`/`Instant` do MVC passam a ser os estritos.

### Task 3: `required` gerado de toda regra de obrigatoriedade do DTO

**Objetivo:** todo campo que o servidor exige no corpo sai `required` no schema de request do `api/openapi.yaml`, gerado por uma regra única no `shared` (D1), e nenhum campo opcional sai `required`. A ARCHITECTURE §7, linha "Contrato", passa de "a cumprir pela M6 E20" para "implementada", com o que mudou de fato.

**Critérios de aceite:**
- `RequiredRequestPropertiesContractTest` (novo, sem Spring, como o `ExtensibleEnumContractTest`): para cada schema referenciado por um `requestBody` do `api/openapi.yaml`, e por cada schema aninhado que eles alcançam, o conjunto `required` é exatamente o das propriedades do DTO que o servidor exige ausentes: `@NotNull`, `@NotBlank` e `@NotEmpty` (no campo ou no getter) e propriedade Kotlin não anulável sem valor padrão. O teste acha o DTO pela classe (ArchUnit ou reflexão, como o precedente) e falha nomeando o schema e o campo divergente. Ele afirma ainda, de forma explícita, o `required` de `CleaningTypeRequest` (`code`, `credits`, `defaultPriority`, `name`, `requiresInspection`) e de um request de escrita de cada módulo que tem escrita por corpo, e que um campo com padrão (`linenChangeEveryNights`, `checklistTemplateId`) não sai `required`. Verificação: `timeout 900 ./gradlew test --tests '*RequiredRequestPropertiesContractTest'`.
- Se o teste achar lacuna, um customizador do springdoc no `shared` (bean ao lado dos de `OpenApiConfiguration`) a fecha a partir das mesmas regras, sem anotação por DTO, e o contrato é regenerado com `timeout 900 ./gradlew updateOpenApi`. Se não achar nenhuma, o relatório diz isso e o customizador não entra (YAGNI); o teste fica como a prova da regra para os schemas futuros.
- `.github/scripts/check-api-breaking.sh` acusa só `ERR` de "propriedade de request passou a ser obrigatória" (ou nenhum). O relatório traz a lista completa (schema e campo) e a saída do script. O `nullable` dos campos não muda (D3).
- `timeout 900 ./gradlew checkOpenApi` passa.
- A ARCHITECTURE §7, linhas "Contrato" e "Datas e valores", troca "a cumprir pela M6 E20" por "implementada pela M6 E20" e registra D1 (o swagger-core já marcava o `required` da Bean Validation; a regra agora é provada por teste) e D3. Verificação: `grep -n 'a cumprir pela M6 E20' docs/ARCHITECTURE.md` vazio.
- `timeout 900 ./gradlew lint` verde.

**Áreas tocadas:** `src/main/kotlin/com/zeny/hotel/shared/web/OpenApiConfiguration.kt` (só se houver lacuna), `api/openapi.yaml` (regenerado, só se houver lacuna), `docs/ARCHITECTURE.md`. Testes: `src/test/kotlin/com/zeny/hotel/shared/web/RequiredRequestPropertiesContractTest.kt` (novo).

**Tamanho:** ~50 min; 0 a 1 arquivo de produção, mais o contrato regenerado.

**Depende de:** nenhuma.

**Interfaces (produz):** nenhuma API nova; a regra "obrigatório no DTO ⇒ `required` no schema" passa a valer para os DTOs de request futuros (E04 em diante) sem esforço.
