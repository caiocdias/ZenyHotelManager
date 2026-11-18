# E01 do M6 — Tipos de limpeza, créditos e faxinas programadas: plano de implementação

> **Para agentes:** a execução é por `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde; o código fica com quem implementa.

**Goal:** criar o módulo `housekeeping` com a configuração dos tipos de limpeza (RF-GOV-01) e das faxinas programadas (RF-GOV-10). O tipo tem créditos por tipo de UH, modelo de checklist opcional, exigência de inspeção, prioridade padrão e regra de troca de enxoval. A faxina programada é por UH ou área, com frequência em dias e próxima data. Entra também a carga inicial dos tipos da SPEC, com o turndown desativado.

**Spec:**
- `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E01", com H9, H15 e **H16**;
- SPEC RF-GOV-01, RF-GOV-10 e §8.2.10;
- ARCHITECTURE §3.1 (linha do `housekeeping`), §7 (API), §8 (permissões) e §11 (persistência).

**Referência do comportamento já documentado:** pela H15, a execução anterior desta etapa, que partiu da mesma base `5e06f16`, se perdeu, e esta refaz o mesmo comportamento. A nota do brain `ZenyHotelManager - Módulo housekeeping` é a referência, **salvo a D8, que a H16 substitui**. Ver também `ZenyHotelManager - Módulo checklists`, `ZenyHotelManager - Arquitetura e estrutura de módulos`, `ZenyHotelManager - Schemas, migrações e views de relatório`, `ZenyHotelManager - Segurança, autenticação e autorização`, `ZenyHotelManager - Auditoria` e `ZenyHotelManager - Convenções de API`.

**Architecture:**
- **Módulo:** `com.zeny.hotel.housekeeping`, na camada Operação. No fim da etapa, `allowedDependencies = ["shared", "hotel", "rooms", "checklists"]`, um subconjunto da linha §3.1, sem aresta nova; a declaração cresce por fatia (Global Constraints). Usa `rooms` e `checklists` só pelas APIs públicas.
- **Camadas:** `domain` com os agregados e as regras; `application` com os casos de uso e as portas; `infrastructure` com escrita por JPA e leitura por `JdbcClient`; `web` com os controllers.
- **Banco:** schema `housekeeping` próprio, com migrações e grants.
- **Checklists:** a `ChecklistsApi` ganha `template(id)` para o vínculo do tipo ao modelo.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1, Hibernate 7.4, Flyway 12, Testcontainers.

**Riscos:**
- **autenticação ou autorização:** as permissões novas `housekeeping.config.read`/`manage` e os modelos de papel (Tasks 1 e 3);
- **concorrência:** desativar um tipo com faxinas ativas, criar ou reativar uma faxina num tipo que está sendo desativado (Task 3);
- **parsing de entrada externa não confiável:** os corpos de criação e edição, com listas de créditos, nomes, o `checklistTemplateId` e o `lastDoneOn` (Tasks 1, 2 e 3);
- **migração ou escrita destrutiva de dados:** 4 migrações Flyway com schema, CHECKs, índices únicos parciais, o trigger que recusa DELETE e a carga inicial com a `key` estável (D5). Esse formato não se desfaz depois de aplicado (Tasks 1 e 3).

## Global Constraints

- Grafo e camadas da ARCHITECTURE §3.1. O `ModularityTest` exige que as dependências usadas sejam **exatamente** as declaradas, e o módulo novo ganha o seu teste. A declaração cresce por fatia, sempre dentro da linha §3.1:
  - Task 1: `["shared", "rooms"]`;
  - Task 2: acrescenta `"checklists"`;
  - Task 3: acrescenta `"hotel"`.

  O teste do módulo acompanha cada fatia com a igualdade exata. Nenhum uso artificial entra para passar o teste.
- Migrações no schema `housekeeping`, com timestamp posterior a `V2026_11_14_1000` (`outOfOrder` desligado): `V2026_11_17_1000` em diante. A ordem e os grants seguem o padrão do `endofday` (`create_*` e depois `grant_*_to_app_role`). Não há `DELETE` pela API, e o banco recusa DELETE nas tabelas que só se desativam.
- **H16:** uma referência inexistente no corpo dá **`400`** com código `…UNKNOWN_…`. Isso vale para tipo de UH, modelo de checklist, local e tipo de limpeza. Uma referência existente que fere uma regra (contexto incompatível, inativa, não publicada) dá `422`.
- O contrato é regenerado com `./gradlew updateOpenApi` na mesma mudança e nunca editado à mão. Enum de resposta que cresce sai como `x-extensible-enum`. O `pattern` precisa ser válido em ECMA-262. `.github/scripts/check-api-breaking.sh` não pode acusar `ERR`.
- Escritas exigem permissão (`authz.require` na `application`), com a autorização **antes** de qualquer checagem que revele estado, e são `@Audited` com retrato só da configuração.
- Datas e instantes seguem a leitura estrita da E18; a paginação é por cursor (`PageCursor`), com `ETag`/`If-Match` nas edições.
- Código, API e banco em inglês; KDoc, comentários e commits em pt-BR (D17).
- Commits por fatia com o trailer `Soul: <soul>` no mesmo parágrafo do `Co-Authored-By`.
- Um Gradle por vez; nunca `./gradlew --stop`; todo comando com `timeout` (testes focados `timeout 900`); nunca laço de espera.
- A verificação da fatia são os testes focados que ela cita, mais `timeout 900 ./gradlew lint` e `timeout 900 ./gradlew checkOpenApi`. A suíte inteira roda no gate do orquestrador.

## Review Focus

1. **Tipo ativo sem crédito para um tipo de UH ativo:** não é recusado, porque o gestor configura aos poucos, mas não passa calado. A resposta traz `missingCreditRoomTypeIds`, calculado na leitura, que muda quando um tipo de UH é criado depois. Teste na Task 1.
2. **Créditos duplicados ou fora de faixa no mesmo corpo:** o mesmo `roomTypeId` duas vezes, `credits` 0, negativo ou acima de 100, e lista enorme (acima de 200 itens) dão `400` no campo, e nada é gravado. Teste na Task 1.
3. **Modelo de checklist vinculado e depois desativado ou despublicado:** o vínculo continua, e só o momento do vínculo confere ativo e publicado. Teste na Task 2.
4. **Desativar o tipo enquanto outra transação cria ou reativa uma faxina nele:** nunca sobra faxina ativa num tipo inativo, com a trava sempre na ordem faxina → tipo. Teste de corrida na Task 3.
5. **`lastDoneOn` no futuro, abaixo do piso do banco, ou faxina reativada com `nextDueOn` vencido:** `lastDoneOn` posterior à data operacional dá `422`. Um `lastDoneOn` anterior a `StorableRange.MIN_DATE` (`-4712-01-01`; o pgjdbc o gravaria como `-infinity`) dá `400 VALIDATION_FAILED`, com `lastDoneOn` no campo (`INVALID_VALUE`). A reativação mantém o `nextDueOn` antigo, e a E02 decide se o recalcula. Testes na Task 3.

## Decisões herdadas (H15: refazer o comportamento documentado)

Valem como requisito. Um achado novo volta ao orquestrador, sem decisão de quem implementa.

- **D1:** o vínculo ao modelo de checklist é opcional (`0..1`). O modelo precisa ser do contexto `HOUSEKEEPING` e estar ativo e publicado no momento do vínculo. Na E04, a precedência é: vínculo ativo e publicado → `ChecklistsApi.resolveTemplate(HOUSEKEEPING, …cleaningTypeId…)` → sem checklist.
- **D5:** a carga inicial vem com a chave estável `key`, que nunca muda. O gestor pode trocar código, nome e configuração, e desativar.

  | key | nome | inspeção | prioridade | enxoval | ativo |
  |---|---|---|---|---|---|
  | `CHECKOUT` | Saída | sim | 4 | — | sim |
  | `STAYOVER` | Permanência | não | 3 | 3 noites | sim |
  | `TOUCH_UP` | Retoque | não | 2 | — | sim |
  | `DEEP_CLEAN` | Faxina pesada | sim | 1 | — | sim |
  | `PRE_ARRIVAL` | Pré-chegada | sim | 4 | — | sim |
  | `TURNDOWN` | Abertura de cama | não | 2 | — | não (H9, Q-14) |

  Os testes nunca alteram os seis tipos da carga.
- **D6:** um tipo ativo sem crédito para um tipo de UH ativo aparece em `missingCreditRoomTypeIds`. Crédito para tipo de UH desativado é aceito.
- **D7:** `nextDueOn = lastDoneOn + frequencyDays` ou, sem `lastDoneOn`, a data operacional (`HotelApi.currentBusinessDate()`). O `recordDone` do domínio nunca faz o `lastDoneOn` voltar (a E04 o usa).
- **Faixas:** prioridade de 1 a 5; enxoval de 1 a 60 noites ou nulo; `credits` de 1 a 100; até 200 créditos por corpo; `frequencyDays` de 1 a 365. Os nomes recusam NUL (`400 HOUSEKEEPING_DATA_INVALID`).
- **Reativação** da faxina: mantém o `nextDueOn` antigo e confere, como a criação, que o tipo e o local estão ativos (decisão do orquestrador na revisão do plano). O PUT que troca o tipo exige tipo ativo mesmo com a faxina inativa.
- **Enxoval nulo** (`linenChangeEveryNights`) significa "sem regra de troca por noites", com troca só a pedido. A descrição do campo no contrato diz isso.
- **Fica com a E02:** o N de "UH vaga há N dias" da pré-chegada, e o que gerar para um tipo da carga desativado.

## Revisão de arquitetura

- **Veredito:** aprovado com notas na rodada 1 (`revisor-arquitetura`, modelo opus).
- **Rodada 0:** bloqueado por 4 Importantes:
  - o `ModularityTest` da Task 1 dependia de usos que só chegam nas Tasks 2 e 3;
  - havia critérios sem teste nem comando;
  - as Áreas e a dependência da Task 3 estavam incompletas;
  - `Riscos:` não tinha migração, e faltava piso para o `lastDoneOn`.

  Os 4 Importantes e os 5 Menores foram corrigidos em `6878467`.
- **Notas Menores da rodada 1:**
  - N1: a conferência do local ativo na reativação é regra nova em relação à execução perdida, e vai ao handoff como decisão;
  - N2: a Task 2 entra no risco de parsing, já aplicado neste texto.

---

### Task 1: Módulo `housekeeping` e tipos de limpeza com créditos

**Objetivo:** criar o módulo, o schema e a carga inicial, mais o CRUD dos tipos de limpeza:
- créditos por tipo de UH, `requiresInspection`, prioridade padrão e regra de troca de enxoval;
- desativar e reativar;
- listagem por cursor e `missingCreditRoomTypeIds`;
- as permissões novas nos modelos de papel e a auditoria.

O vínculo ao modelo de checklist é da Task 2: nesta fatia, o campo existe e só aceita `null`.

**Critérios de aceite:**
- **`CleaningTypesApiTest`** (`@IntegrationTest`):
  - `POST /api/v1/cleaning-types` dá `201` com `Location` e `ETag`;
  - `GET /{id}` devolve o tipo com `ETag`;
  - `PUT /{id}` com `If-Match` muda código, nome, créditos, inspeção, prioridade e enxoval; sem `If-Match` dá `428`, e com `ETag` velho dá `412`;
  - `POST /{id}/deactivate` e `/reactivate` funcionam;
  - `GET` lista por cursor do código, com o filtro `active`.

  Verificação: `timeout 900 ./gradlew test --tests '*CleaningTypesApiTest'`.
- **Recusas no corpo** (`CleaningTypesApiTest` ou `CleaningTypesApiAttackTest`):
  - crédito para tipo de UH inexistente dá **`400 CLEANING_TYPE_UNKNOWN_ROOM_TYPE`** (H16);
  - `roomTypeId` repetido dá `400 CLEANING_TYPE_CREDIT_DUPLICATED`;
  - código repetido dá `409 CLEANING_TYPE_CODE_ALREADY_IN_USE`;
  - prioridade, enxoval, `credits` fora de faixa, mais de 200 créditos e nome com NUL dão `400` no campo;
  - em nenhum desses casos algo é gravado (Review Focus 2).

  Verificação: `timeout 900 ./gradlew test --tests '*CleaningTypesApi*'`.
- **Aviso de crédito faltante** (`CleaningTypesApiTest`): um tipo ativo sem crédito para um tipo de UH ativo devolve o id em `missingCreditRoomTypeIds`. Um tipo de UH criado depois aparece na próxima leitura (Review Focus 1). Verificação: `timeout 900 ./gradlew test --tests '*CleaningTypesApiTest'`.
- **Carga inicial e banco:** `HousekeepingSchemaTest` confere:
  - os seis tipos da tabela D5, com o `TURNDOWN` inativo;
  - a `key` não muda por `PUT` (`updatable=false`), e há um índice único parcial em `system_key` (onde não é nulo), para a E02 buscar o tipo pela `key`;
  - o banco recusa DELETE em `cleaning_type`, que entra em `NO_DELETE_TABLES` do `DatabaseRolesTest`. O papel da aplicação mantém DELETE só em `cleaning_type_credit`, porque o `PUT` substitui os créditos. Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingSchemaTest'`.
- **Permissões e auditoria** (`HousekeepingPermissionsTest`):
  - sem `housekeeping.config.manage`, a escrita dá `403`; sem `housekeeping.config.read`, a leitura dá `403`;
  - a autorização vem antes de `404`/`409` e antes dos `400 …UNKNOWN_…` e dos `422`, que revelariam se um tipo de UH, modelo ou local existe;
  - as duas permissões são só de escopo `ALL`, não sensíveis, e entram nos modelos Gerente e Governanta pelo próprio objeto de permissões do módulo (`PermissionDefinition.templates`, como em `ChecklistsPermissions.kt`) e pelo `@Bean ModulePermissions` no `application`, sem editar `iam` nem `shared`;
  - os testes que fixam os grants dos modelos de papel (`AuthorizationTest` e afins) são atualizados;
  - cada escrita grava a trilha `housekeeping.cleaning-type.<create|update|deactivate|reactivate>`.

  Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingPermissionsTest' --tests 'com.zeny.hotel.iam.*'`.
- **Módulos:** o `ModularityTest` declara o módulo novo com `["shared", "rooms"]`, em igualdade exata, e o `DatabaseRolesTest` conhece as tabelas novas. Verificação: `timeout 900 ./gradlew test --tests 'com.zeny.hotel.ModularityTest' --tests '*DatabaseRolesTest'`.
- **Contrato:** `api/openapi.yaml` regenerado, com a tag `housekeeping`, `key` como `x-extensible-enum` e a descrição do enxoval nulo. Verificação: `timeout 900 ./gradlew checkOpenApi lint`.

**Áreas tocadas:**
- `src/main/kotlin/com/zeny/hotel/housekeeping/`: `HousekeepingModule.kt`, `HousekeepingPermissions.kt`, `HousekeepingTypes.kt`, `domain/CleaningType.kt`, `application/CleaningTypesService.kt`, `infrastructure/CleaningTypePersistence.kt` e `web/CleaningTypesController.kt`;
- `src/main/resources/db/migration/housekeeping/` (`V2026_11_17_1000__create_cleaning_types.sql` e `V2026_11_17_1001__grant_cleaning_types_to_app_role.sql`);
- `api/openapi.yaml`.

Testes: `src/test/kotlin/com/zeny/hotel/housekeeping/` e os testes de grants e módulos citados.

**Tamanho:** ~60 min; 10 arquivos de produção, sendo 2 SQL e o contrato gerado. O campo do modelo de checklist entra nesta fatia como UUID opcional, que só aceita `null`; o tipo `ChecklistTemplateId` só aparece na Task 2.

**Depende de:** nenhuma.

**Interfaces (produz):**
- `com.zeny.hotel.housekeeping.CleaningTypeId` (`TypedId`, `new()` por UUIDv7);
- `enum class CleaningTypeKey { CHECKOUT, STAYOVER, TOUCH_UP, DEEP_CLEAN, PRE_ARRIVAL, TURNDOWN }`;
- `HousekeepingErrorCodes`, com os códigos desta fatia;
- `HousekeepingPermissions.CONFIG_READ = "housekeeping.config.read"` e `CONFIG_MANAGE = "housekeeping.config.manage"`;
- a porta `CleaningTypeStore`, com `forUpdate(id: CleaningTypeId): CleaningType?`, que faz `SELECT … FOR NO KEY UPDATE` (a Task 3 a usa para a trava);
- a porta `CleaningTypeStore`, também com `hasActiveSchedules(id: CleaningTypeId): Boolean`. Na Task 1 ela devolve `false`, e a Task 3 a implementa contra `scheduled_cleaning`;
- tabela `housekeeping.cleaning_type`, com `id`, `system_key`, `code`, `name`, `requires_inspection`, `default_priority`, `linen_change_every_nights`, `checklist_template_id` (sem FK entre schemas), `active` e `version`;
- tabela `housekeeping.cleaning_type_credit`, com `cleaning_type_id`, `room_type_id` e `credits`.

### Task 2: Vínculo do tipo de limpeza ao modelo de checklist

**Objetivo:** o tipo de limpeza aceita o vínculo opcional a um modelo de checklist (D1), conferido pela nova `ChecklistsApi.template(id)`. Ele precisa ser do contexto `HOUSEKEEPING` e estar ativo e publicado no momento do vínculo.

**Critérios de aceite:**
- `ChecklistsApi.template(id)` devolve o resumo do modelo (id, contexto, ativo, se tem versão publicada), ou `null` se ele não existe. Teste no `checklists`. Verificação: `timeout 900 ./gradlew test --tests 'com.zeny.hotel.checklists.*'`.
- No `POST` e no `PUT` de `/api/v1/cleaning-types` (`CleaningTypesChecklistLinkTest`):
  - modelo inexistente dá **`400 CLEANING_TYPE_UNKNOWN_CHECKLIST_TEMPLATE`** (H16);
  - contexto diferente de `HOUSEKEEPING` dá **`422 CLEANING_TYPE_CHECKLIST_CONTEXT_INCOMPATIBLE`**;
  - modelo inativo dá `422 CLEANING_TYPE_CHECKLIST_TEMPLATE_INACTIVE`;
  - modelo sem versão publicada dá `422 CLEANING_TYPE_CHECKLIST_TEMPLATE_NOT_PUBLISHED`;
  - modelo válido é gravado, e `null` remove o vínculo.

  Verificação: `timeout 900 ./gradlew test --tests '*CleaningTypesChecklistLinkTest'`.
- Um modelo vinculado que depois é desativado continua vinculado, e a leitura do tipo não falha (Review Focus 3). Teste em `CleaningTypesChecklistLinkTest`; verificação: `timeout 900 ./gradlew test --tests '*CleaningTypesChecklistLinkTest'`.
- O KDoc do tipo registra a precedência da E04 (D1); quem confere é a revisão da tarefa.
- O `ModularityTest` passa a declarar `["shared", "rooms", "checklists"]`, em igualdade exata. Verificação: `timeout 900 ./gradlew test --tests 'com.zeny.hotel.ModularityTest'` e `timeout 900 ./gradlew checkOpenApi lint`.

**Áreas tocadas:**
- `src/main/kotlin/com/zeny/hotel/checklists/ChecklistsApi.kt` e a implementação no `application` do `checklists`;
- `src/main/kotlin/com/zeny/hotel/housekeeping/` (`HousekeepingModule.kt`, `application/CleaningTypesService.kt`, `HousekeepingTypes.kt` e `web/CleaningTypesController.kt`, se o campo mudar de descrição);
- `api/openapi.yaml`.

Testes: no `checklists` e `src/test/kotlin/com/zeny/hotel/housekeeping/CleaningTypesChecklistLinkTest.kt`.

**Tamanho:** ~35 min; 6 arquivos de produção.

**Depende de:** Task 1.

**Interfaces:**
- Consome: `CleaningTypeId`, `HousekeepingErrorCodes` e `CleaningTypesService` (Task 1).
- Produz: `ChecklistsApi.template(id: ChecklistTemplateId): ChecklistTemplateSummary?`, com `data class ChecklistTemplateSummary(val id: ChecklistTemplateId, val context: ChecklistContext, val active: Boolean, val published: Boolean)` no pacote público do `checklists`.

### Task 3: Faxinas programadas

**Objetivo:** CRUD de `ScheduledCleaning` por UH ou área (RF-GOV-10), com tipo de limpeza, frequência em dias, `lastDoneOn` e `nextDueOn` calculado (D7). Inclui desativar e reativar, a listagem por cursor de (`nextDueOn`, `id`) e a trava contra faxina ativa em tipo inativo.

**Critérios de aceite:**
- **`ScheduledCleaningsApiTest`** (`@IntegrationTest`):
  - `POST /api/v1/scheduled-cleanings` com `roomId` **ou** `areaId` (exatamente um), `cleaningTypeId`, `frequencyDays` e `lastDoneOn` opcional dá `201`, com `nextDueOn = lastDoneOn + frequencyDays` ou, sem `lastDoneOn`, a data operacional;
  - `PUT /{id}` com `If-Match` muda só `cleaningTypeId` e `frequencyDays` e recalcula `nextDueOn`;
  - `/deactivate` e `/reactivate` funcionam;
  - `GET` lista por cursor (`nextDueOn`, `id`), com o filtro `active`;
  - o `201` traz `Location` e `ETag`, e `GET /{id}` devolve a faxina com `ETag`.

  Verificação: `timeout 900 ./gradlew test --tests '*ScheduledCleaningsApiTest'`.
- **Recusas** (`ScheduledCleaningsAttackTest`):
  - local inexistente dá **`400 SCHEDULED_CLEANING_UNKNOWN_LOCATION`**, e tipo inexistente, **`400 SCHEDULED_CLEANING_UNKNOWN_CLEANING_TYPE`** (H16);
  - os dois locais, ou nenhum, dão `400 SCHEDULED_CLEANING_LOCATION_INVALID`;
  - local inativo dá `422 SCHEDULED_CLEANING_LOCATION_INACTIVE`;
  - tipo inativo dá `422 CLEANING_TYPE_INACTIVE`;
  - `lastDoneOn` depois da data operacional dá `422 SCHEDULED_CLEANING_LAST_DONE_IN_FUTURE`;
  - uma segunda faxina ativa no mesmo (local, tipo) dá `409 SCHEDULED_CLEANING_ALREADY_EXISTS`, pelo índice único parcial;
  - `frequencyDays` fora de 1..365 dá `400` no campo;
  - `lastDoneOn` anterior a `-4712-01-01` dá `400 VALIDATION_FAILED`, com `lastDoneOn` no campo (Review Focus 5).

  Verificação: `timeout 900 ./gradlew test --tests '*ScheduledCleaningsAttackTest'`.
- **Desativação do tipo:** desativar um tipo com faxina ativa dá `422 CLEANING_TYPE_HAS_ACTIVE_SCHEDULES`. O teste de corrida (Review Focus 4) desativa o tipo enquanto outra transação cria ou reativa uma faxina nele, e nunca sobra faxina ativa em tipo inativo. Verificação: `timeout 900 ./gradlew test --tests '*ScheduledCleanings*' --tests '*CleaningTypes*'`.
- **Reativação** (`ScheduledCleaningsApiTest`): a faxina reativada mantém o `nextDueOn` antigo, e a reativação com tipo ou local inativo dá `422` (Review Focus 5). O `recordDone` tem teste de unidade (`ScheduledCleaningDomainTest`): nunca faz o `lastDoneOn` voltar. Verificação: `timeout 900 ./gradlew test --tests '*ScheduledCleaningsApiTest' --tests '*ScheduledCleaningDomainTest'`.
- **Permissões e auditoria** (`HousekeepingPermissionsTest`): `housekeeping.config.read`/`manage`, como na Task 1, com a autorização antes de `400`/`404`/`409`/`422`, e a trilha `housekeeping.scheduled-cleaning.<create|update|deactivate|reactivate>`. O banco recusa DELETE em `scheduled_cleaning` (`NO_DELETE_TABLES`). Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingPermissionsTest' --tests '*DatabaseRolesTest'`.
- O `ModularityTest` passa a declarar `["shared", "hotel", "rooms", "checklists"]`, em igualdade exata. Verificação: `timeout 900 ./gradlew test --tests 'com.zeny.hotel.ModularityTest'` e `timeout 900 ./gradlew checkOpenApi lint`.

**Áreas tocadas:**
- `src/main/kotlin/com/zeny/hotel/housekeeping/`: `HousekeepingModule.kt`, `HousekeepingTypes.kt` (`ScheduledCleaningId` e os códigos `SCHEDULED_CLEANING_*`, `CLEANING_TYPE_INACTIVE` e `CLEANING_TYPE_HAS_ACTIVE_SCHEDULES`), `domain/ScheduledCleaning.kt`, `application/ScheduledCleaningsService.kt`, `infrastructure/ScheduledCleaningPersistence.kt`, `web/ScheduledCleaningsController.kt`, `application/CleaningTypesService.kt` (regra de desativação) e `infrastructure/CleaningTypePersistence.kt` (`hasActiveSchedules`);
- `src/main/resources/db/migration/housekeeping/` (`V2026_11_17_1002__create_scheduled_cleanings.sql` e `V2026_11_17_1003__grant_scheduled_cleanings_to_app_role.sql`);
- `api/openapi.yaml`.

Testes: em `src/test/kotlin/com/zeny/hotel/housekeeping/`.

**Tamanho:** ~60 min; 11 arquivos de produção, sendo 2 SQL e o contrato gerado.

**Depende de:** Task 1 e Task 2. As duas mexem em `CleaningTypesService.kt`, `HousekeepingTypes.kt` e `api/openapi.yaml`, então a execução é em série.

**Interfaces:**
- Consome: `CleaningTypeId`, `CleaningTypeStore.forUpdate` e `hasActiveSchedules`, que esta fatia implementa, e as permissões (Task 1); `RoomsApi.room(id)`/`area(id)` e `HotelApi.currentBusinessDate()`.
- Produz:
  - `ScheduledCleaningId`;
  - a porta `ScheduledCleaningStore`;
  - a tabela `housekeeping.scheduled_cleaning`, com `id`, `room_id`, `area_id`, `cleaning_type_id`, `frequency_days`, `last_done_on`, `next_due_on`, `active` e `version`, o CHECK de exatamente um local e o índice único parcial `scheduled_cleaning_active_uk`;
  - `ScheduledCleaning.recordDone(date: LocalDate)`, que a E04 usará.
