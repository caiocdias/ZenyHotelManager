# E07 do M6 — Ordens de serviço corretivas e OOO/OOS: plano de implementação

> **Para agentes:** execução por `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde, não o código.

**Goal:** criar o módulo `maintenance` com a OS corretiva (RF-MAN-01 a RF-MAN-03): local, categoria, prioridade, descrição, anexos, origem, responsável interno ou terceiro, estados, custos informados, bloqueio da UH em OOO/OOS pela `RoomsApi` com o step-up do OOO, devolução da UH como Suja ao concluir e aviso de OS urgente.

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E07", e a nota herdada do M2 "E07, OOO pela manutenção" (em "Hipóteses e decisões em aberto"); SPEC RF-MAN-01 a RF-MAN-03, RN-07, RF-ACS-08, RF-NOT-01, §8.2.12 (`WorkOrder`; `Asset`, `PreventivePlan` e `WorkOrderPart` ficam fora); ARCHITECTURE §3.1 (linha `maintenance`), §7, §8, §11. Brain: `ZenyHotelManager - Arquitetura e estrutura de módulos`, `ZenyHotelManager - Schemas, migrações e views de relatório`, `ZenyHotelManager - Segurança, autenticação e autorização`, `ZenyHotelManager - Auditoria`, `ZenyHotelManager - Convenções de API`, `ZenyHotelManager - Módulo rooms`, `ZenyHotelManager - Módulo files`, `ZenyHotelManager - Módulo notifications`, `ZenyHotelManager - Módulos hotel e staff`, `ZenyHotelManager - Testes de integração` e as capturas pendentes da E20 e da E21.

**Architecture:** módulo novo `com.zeny.hotel.maintenance` (camada Operação), com schema próprio `maintenance` (descoberto pela pasta de migração pelo `ModuleSchemasFlywayCustomizer`) e `allowedDependencies` crescendo por fatia: `["shared", "hotel", "rooms", "staff"]` na Task 1 e `+ "files", "notifications"` na Task 2 — subconjunto da linha da §3.1 (sem `checklists`, que entra na E08, e sem `inventory`, que não existe até o M9). A `WorkOrder` é um agregado com máquina de estados pura no `domain`; o caso de uso trava a OS, confere a transição, grava, audita e chama as APIs de plataforma. O bloqueio da UH é um período OOO/OOS do `rooms` aberto pela `RoomsApi.openOutOfOrder` (que não confere permissão): a manutenção faz o próprio step-up antes (`authorization.requireOrApproval` com `RoomsPermissions.OUT_OF_ORDER_MANAGE`, como o `OutOfOrderService` do `rooms`), guarda o `OutOfOrderId` na OS e o libera pela `releaseOutOfOrder(id, RoomStatusSource.MANUAL)` ao concluir ou cancelar (a UH volta como Suja pelo `returnStatus = DIRTY`). O `rooms` não muda. O alerta de reserva alocada no período é do `reservations` (já escuta `OutOfOrderChanged`).

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1, Hibernate 7.4, Flyway 12, Testcontainers.

**Riscos:**
- **autenticação ou autorização:** escopo `OWN` do técnico na leitura e nas ações; step-up do OOO; permissão de quem só pede OS (recepção e governança) (Tasks 1 e 3);
- **cálculo de valor monetário:** custos em `Money` (mão de obra, terceiros, peças informadas), nunca negativos (Task 1);
- **parsing de entrada externa não confiável:** corpos da OS, enum de categoria e prioridade, período do bloqueio, ids de anexo (Tasks 1, 2 e 3);
- **concorrência:** duas ações na mesma OS (`If-Match`), conclusão × liberação do OOO (Tasks 1 e 3);
- **migração ou escrita destrutiva de dados:** schema e tabelas novas, sequence do número (Task 1);
- **dado pessoal ou segredo:** descrição e motivos em texto livre (RN-13, fora da trilha), nome do terceiro, nome do responsável no `responsible` do OOO, fotos com EXIF/GPS (reencode) (Tasks 1 a 3).

## Global Constraints

- Grafo §3.1 com o `ModularityTest` em igualdade exata das dependências reais: teste novo `maintenance depends only on shared, hotel, rooms, staff` na Task 1, renomeado para incluir `files, notifications` na Task 2; `maintenance` não depende de `inventory` (que não existe) nem de `checklists` nesta etapa. A linha da §3.1 não muda (as arestas usadas já estão nela). O `rooms` não muda nesta etapa.
- Schema `maintenance` descoberto pela pasta `db/migration/maintenance/` (`ModuleSchemasFlywayCustomizer`), com `GRANT` ao papel da aplicação nas migrações e o `DatabaseRolesTest`, migrações com timestamp posterior à última do projeto no momento (depois da E22: `reservations/V2026_11_19_1700__…`), `outOfOrder` desligado; a API não expõe `DELETE` (OS cancelada, não apagada); tabela de OS sem `DELETE` para o papel da aplicação.
- Número legível pelo `DocumentNumberGenerator` com `DocumentNumberType("OS", "maintenance.work_order_number_seq")`, ano da data operacional.
- Erros RFC 9457 com `code` estável; referência inexistente no corpo `400 …UNKNOWN_…` e existente mas inativa `422` (H16, ARCHITECTURE §7); transição inválida `409 WORK_ORDER_TRANSITION_NOT_ALLOWED`; ações com `If-Match` (`412` na versão errada); entrada estrita da E20; `409 CONCURRENT_UPDATE` da E21; enum de resposta que cresce como `x-extensible-enum`.
- Autorização antes de qualquer recusa que revele estado (`403` antes de `404`/`409`/`412`). `@Audited` sem texto livre: `description`, `holdReason`, `completionNotes`, `cancellationReason` e `thirdPartyName` não vão à trilha (só os demais campos e o fato de terem mudado).
- RN-13: a OS não guarda nome, documento nem contato de hóspede; a descrição é texto livre do operador, e o KDoc/contrato orienta a não pôr dado de hóspede.
- Contrato regenerado (`./gradlew updateOpenApi`); `checkOpenApi` e `.github/scripts/check-api-breaking.sh` sem `ERR`.
- Código em inglês; KDoc, ARCHITECTURE e commits em pt-BR; commits com `Soul:`.
- Um Gradle por vez; `timeout` em todo comando; sem laço de espera; nunca `./gradlew --stop`.
- Testes: banco compartilhado, só os próprios dados (UHs e áreas próprias); data operacional corrente sem avanço; quem mexe no relógio o devolve no `finally`.
- Verificação da fatia: os testes focados citados mais `timeout 900 ./gradlew checkOpenApi lint`. O `./gradlew build` completo (com `koverVerify`) é o gate do orquestrador no fim da etapa.

## Review Focus

1. **Concluir ou cancelar uma OS cujo período OOO já foi liberado à mão no `rooms`** (ou que nunca começou): a OS conclui; a liberação repetida não falha nem muda a UH de novo. Teste na Task 3.
2. **Bloquear a UH de uma OS que já tem bloqueio vigente**, ou de uma OS de área (sem UH): `409 WORK_ORDER_ROOM_ALREADY_BLOCKED` e `422 WORK_ORDER_NOT_ROOM`. Teste na Task 3.
3. **Técnico `OWN` pedindo `GET /{id}` de OS alheia ou agindo nela:** `403` (não `404`), igual ao padrão do projeto para escopo `OWN`. Teste na Task 1.
4. **Custo negativo, com mais de 2 casas ou acima do teto:** `400 VALIDATION_FAILED` no campo. Teste na Task 1.
5. **OS criada já urgente e OS que passa a urgente numa edição:** um aviso em cada caso; editar uma OS que já era urgente não reavisa. Teste na Task 2.

## Decisões do plano

- **D1 — permissões** (`MaintenancePermissions`, raiz do módulo): `maintenance.work-orders.read` (escopos `ALL` e `OWN`), `maintenance.work-orders.request` (só `ALL`: abrir OS) e `maintenance.work-orders.manage` (escopos `ALL` e `OWN`: editar, atribuir, agir, custos, bloqueio). Modelos: Gerente e Manutenção — as três `ALL` (sem fila invisível: o técnico vê e assume as OS sem responsável; ruling U2); Governanta e Recepcionista — `request` e `read` `ALL` (acompanham o que pediram e o estado da UH). O escopo `OWN` existe para roles criadas pelo administrador: dono de uma OS = quem abriu e o usuário do colaborador responsável (pela `StaffApi`). Quem tem `manage` com `OWN` só age nas suas; o aviso urgente (D6) vai a quem tem `manage` em qualquer escopo (limitação registrada: o `OWN` pode receber aviso de OS que não abre).
- **D2 — agregado `WorkOrder`.** Campos: `id` (UUIDv7), `number` (`OS-AAAA-NNNNNN`), local (`roomId` **ou** `areaId`, exatamente um), `category` (`ELECTRICAL`, `PLUMBING`, `AIR_CONDITIONING`, `FURNITURE`, `OTHER`; `x-extensible-enum`), `priority` (`LOW`, `NORMAL`, `HIGH`, `URGENT`), `description` (1 a 2000 caracteres), `origin` (`RECEPTION`, `HOUSEKEEPING`, `MAINTENANCE`, `CHECKLIST`; `x-extensible-enum`, porque o M10 acrescenta a preventiva; o `POST` aceita as três primeiras e recusa `CHECKLIST`, reservado à E08), `assigneeEmployeeId` (anulável; inexistente → `400 UNKNOWN_EMPLOYEE`; inativo → `422 WORK_ORDER_ASSIGNEE_INACTIVE`; UH ou área inativa → `422 WORK_ORDER_LOCATION_INACTIVE`), `thirdPartyName` (anulável, texto até 200), `status`, `holdReason` (anulável, até 500, só em `ON_HOLD`), `laborCost`, `thirdPartyCost`, `partsCost` (`Money`, de 0,00 a 99999,99, 2 casas; 0 na criação), `openedBy`, `openedAt`, `dueAt` (anulável), `startedAt`, `closedAt`, `completionNotes` (até 2000), `cancellationReason` (até 500), `outOfOrderId` (anulável, Task 3), `version`.
- **D3 — máquina de estados** (`WorkOrderStatus`: `OPEN`, `IN_PROGRESS`, `ON_HOLD`, `DONE`, `CANCELLED`; "aguardando peça/terceiro" = `ON_HOLD` com motivo): `OPEN → IN_PROGRESS` (`start`), `IN_PROGRESS → ON_HOLD` (`hold`, motivo obrigatório), `ON_HOLD → IN_PROGRESS` (`resume`), `OPEN | IN_PROGRESS | ON_HOLD → DONE` (`complete`, notas e custos opcionais), `OPEN | IN_PROGRESS | ON_HOLD → CANCELLED` (`cancel`, motivo obrigatório). `DONE` e `CANCELLED` são finais; editar (inclusive os custos) ou agir numa OS final dá `409 WORK_ORDER_TRANSITION_NOT_ALLOWED` (ruling U4).
- **D4 — API** em `/api/v1/work-orders`: `POST` (`request`; `201`, `Location`, `ETag`; corpo **só** com local, categoria, prioridade, descrição, origem e prazo — responsável, terceiro e custos só pelo `PUT` com `manage`), `GET` (lista com cursor, filtros `status`, `roomId`, `areaId`, `priority`, `assigneeEmployeeId`; `OWN` só as suas), `GET /{id}`, `PUT /{id}` (`manage`; DTO exclusivo de alteração, substituição inteira com os campos exigidos marcados `required` — os três custos inclusive, sem valor padrão —; edita local, categoria, prioridade, descrição, responsável, terceiro, prazo e custos enquanto não final; `If-Match`; trocar o local com bloqueio vigente dá `409 WORK_ORDER_LOCATION_LOCKED`, Task 3), e as ações `POST /{id}/start`, `/hold`, `/resume`, `/complete`, `/cancel` (`manage`; `If-Match`). Anexos e bloqueio nas Tasks 2 e 3.
- **D5 — anexos.** Duas finalidades comuns (não sensíveis), como o precedente do `guests`: `maintenance.work-order-photo` (JPEG e PNG, até 10 MB, `ImageReencoding()` — sem EXIF/GPS) e `maintenance.work-order-document` (PDF, até 10 MB); upload `maintenance.work-orders.request`, download `maintenance.work-orders.read`. No `files`, o `OWN` da permissão de download é "quem enviou" (não "dono da OS"): o KDoc das finalidades registra a limitação para roles `OWN` criadas pelo administrador. `POST /{id}/attachments` `{fileId}` vincula pela `FilesApi.attach` (finalidade que não é das duas → `422 WORK_ORDER_ATTACHMENT_INVALID`), com `request` na OS que o ator abriu ou com `manage` (no escopo dele); `POST /{id}/attachments/{fileId}/detach` desvincula pela `FilesApi.detach`, só com `manage`; nenhum dos dois pede `If-Match` nem muda a versão da OS (o KDoc da resposta registra que a versão e o `ETag` não cobrem a lista de anexos); OS final recusa os dois (`409 WORK_ORDER_TRANSITION_NOT_ALLOWED`). A resposta da OS lista os anexos vigentes (id e finalidade); o download é pela URL assinada do `files` (`GET /api/v1/files/{id}/download-url`). Tabela `maintenance.work_order_attachment` (OS, arquivo, quem e quando vinculou, `detached_at`/`detached_by` anuláveis; o detach marca, sem `DELETE`).
- **D6 — aviso urgente.** Ao criar uma OS `URGENT`, ou ao editá-la de outra prioridade para `URGENT`, `NotificationsApi.notify(URGENT_WORK_ORDER, …)` a `NotificationRecipients(permissions = {maintenance.work-orders.manage}, except = {ator})`, com número, local (número da UH ou nome da área) e categoria; sem a descrição (texto livre) nem dado de hóspede.
- **D7 — bloqueio da UH.** `POST /{id}/room-block` `{kind: OUT_OF_ORDER|OUT_OF_SERVICE, startsOn, endsOn}` (`manage`; `If-Match`; cabeçalho `X-Approval-Token` para o OOO). Ordem das recusas: `400` do corpo → `403` sem `manage` → `404` → `412` → OS final (`409 WORK_ORDER_TRANSITION_NOT_ALLOWED`), de área (`422 WORK_ORDER_NOT_ROOM`) ou com bloqueio vigente (`409 WORK_ORDER_ROOM_ALREADY_BLOCKED`) → `403 APPROVAL_REQUIRED` → regras do `rooms`. OOO passa por `authorization.requireOrApproval(SensitiveAction("maintenance.work-order.block-room", RoomsPermissions.OUT_OF_ORDER_MANAGE, "maintenance.work-order:<id>"), token)`, com o mesmo `action` no `@Audited` do caso de uso; OOS exige `RoomsPermissions.OUT_OF_SERVICE_MANAGE`. Chama `RoomsApi.openOutOfOrder(roomId, kind, OutOfOrderRequest(startsOn, endsOn, reason = "OS <número>", responsible = <nome do responsável ou terceiro ou "Manutenção">, returnStatus = DIRTY))`; os erros do `rooms` (`422 OUT_OF_ORDER_ROOM_OCCUPIED`, `422 OUT_OF_ORDER_PERIOD_IN_PAST`, `409 OUT_OF_ORDER_OVERLAP`) passam como estão. Grava o `outOfOrderId` na OS. **Bloqueio vigente** = `outOfOrderId` presente e o período não liberado (`releasedOn == null`). `complete` libera o período sem step-up (RF-MAN-03: o bloqueio foi autorizado para esta OS), pela `releaseOutOfOrder(id, RoomStatusSource.MANUAL)` na mesma transação (ruling U1: o enum publicado não muda; a trilha `maintenance.*` guarda a proveniência). `cancel` com bloqueio vigente exige o que o `rooms` exige para liberar (ruling U3): OOO `requireOrApproval(SensitiveAction("maintenance.work-order.cancel", OUT_OF_ORDER_MANAGE, "maintenance.work-order:<id>"), token)`, OOS `require(OUT_OF_SERVICE_MANAGE)`; sem bloqueio vigente, o `cancel` só pede `manage`. Ordem das recusas do `cancel`: `400` → `403` sem `manage` → `404` → `412` → OS final (`409`) → `403 APPROVAL_REQUIRED` (ou `403` sem `OUT_OF_SERVICE_MANAGE`). Um período que terminou sozinho sem ser liberado continua "vigente" (trava o local e um novo bloqueio) até a OS concluir ou cancelar, quando a liberação só o encerra, sem mexer no status da UH; o KDoc e o handoff registram isso. Período já liberado à mão no `rooms` não falha. Concluir sem bloqueio não mexe na UH. Prorrogar ou encurtar o período fica no `rooms` (`PUT` do período), registrado no KDoc e no handoff.
- **D8 — tempo.** `startedAt` no primeiro `start`, `closedAt` no `complete`/`cancel`, pelo `Clock`; o relatório da E09 deriva o tempo daí.

## Revisão de arquitetura

- **Veredito:** aprovado, na rodada 2 (`revisor-arquitetura`, modelo opus).
- **Rodada 0:** bloqueado por 9 Importantes:
  - o valor novo `RoomStatusSource.MAINTENANCE`, que mudava um enum publicado e esbarrava no CHECK do banco;
  - o `ModularityTest` exato com dependências que só entram na Task 2;
  - escopo `OWN`, aviso e download incoerentes;
  - liberação do OOO no cancelamento sem step-up;
  - `PUT` que troca o local com bloqueio;
  - campos do `POST`;
  - permissão dos anexos;
  - `origin` extensível e o risco de dado pessoal.

  Houve também 12 Menores. Corrigido em `705d897`, com as rulings U1 a U4 do orquestrador pelas opções recomendadas (sem mudança de contrato).
- **Rodada 1:** bloqueado por 1 Importante: o critério que esperava `400` para campo desconhecido, quando a convenção do projeto o ignora. Havia também 4 Menores (contagem de arquivos, `ETag` × anexos, ordem das recusas do `cancel`, período vencido não liberado). Corrigido em `83d4ae1`.
- **Para o usuário (sem bloquear):** U1 (liberação com origem `MANUAL`), U2 (Manutenção com `read`/`manage` `ALL`), U3 (concluir sem step-up; cancelar com bloqueio pede o step-up do `rooms`), U4 (OS final não aceita custo).

---

### Task 1: Módulo, OS, estados e escopo `OWN`

**Objetivo:** D1 a D4 e D8: módulo e schema, permissões e modelos, agregado e máquina de estados, CRUD e ações, custos, número legível, auditoria e escopo `OWN`.

**Critérios de aceite:**
- `WorkOrderTest` (domínio, novo): cada transição válida de D3 e cada inválida (inclusive a partir de `DONE` e `CANCELLED`) com o erro de transição; `hold` sem motivo e `cancel` sem motivo recusados; custos negativos recusados. Verificação: `timeout 600 ./gradlew test --tests '*WorkOrderTest'`.
- `WorkOrdersApiTest` (`@IntegrationTest`, novo): criar OS de UH e de área (`201`, número `OS-<ano>-…`, `status = OPEN`, custos 0); local com UH e área juntos ou nenhum → `400`; `roomId`/`areaId`/`assigneeEmployeeId` inexistentes → `400 UNKNOWN_…`; UH/área ou colaborador inativos → `422`; `PUT` sem um dos custos → `400 REQUIRED`; `origin = CHECKLIST` → `400`; ciclo `start → hold → resume → complete` com `If-Match` e `startedAt`/`closedAt`; `412` com versão errada; custos `-1`, `1.234` e `100000.00` → `400` no campo (Review Focus 4); `PUT` numa OS `DONE` → `409`; trilha sem `description`, `holdReason`, `completionNotes`, `cancellationReason` e `thirdPartyName`; `origin` e `category` como `x-extensible-enum` no contrato. Verificação: `timeout 900 ./gradlew test --tests '*WorkOrdersApiTest'`.
- `WorkOrderPermissionsTest` (`@IntegrationTest`, novo): sem permissão `403` antes de `404`/`409`/`412`; com uma role de teste `read`+`manage` `OWN`, o técnico lista só as OS que abriu ou de que é responsável e recebe `403` em `GET`/ação de OS alheia (Review Focus 3); o modelo Manutenção vê e age em todas; recepção abre e lê, mas não age (`403`); `POST` com `assigneeEmployeeId`, `thirdPartyName` ou custos cria a OS **ignorando** esses campos (sem responsável, sem terceiro, custos 0 na resposta e no banco), pela convenção do projeto para campo desconhecido (o DTO do `POST` não os tem); permissões e modelos no catálogo. Verificação: `timeout 900 ./gradlew test --tests '*WorkOrderPermissionsTest'`.
- `ModularityTest` com o teste novo `maintenance depends only on shared, hotel, rooms, staff`; `DatabaseRolesTest` com o schema novo. Verificação: `timeout 900 ./gradlew test --tests '*ModularityTest' --tests '*DatabaseRolesTest'`.
- Contrato regenerado; `timeout 900 ./gradlew checkOpenApi lint` verde.

**Áreas tocadas:** `maintenance/` novo: `MaintenanceModule.kt`, `MaintenancePermissions.kt`, `MaintenanceTypes.kt` (ids, enums, códigos de erro), `domain/WorkOrder.kt`, `application/WorkOrdersService.kt`, `application/MaintenanceAudit.kt`, `application/MaintenanceConfiguration.kt` (beans das permissões), `infrastructure/WorkOrderPersistence.kt`, `web/WorkOrdersController.kt` (com os DTOs); migrações `src/main/resources/db/migration/maintenance/` (tabela, sequence, grants, sem `DELETE`); `api/openapi.yaml`; `ModularityTest`. Se a fatia passar de ~60 min, o `PUT` e os filtros da lista são o que sai dela (vão para a Task 2), registrado no relatório.

**Tamanho:** ~60 min; 10 arquivos de produção, mais migrações, configuração do schema e contrato.

**Depende de:** nenhuma.

**Interfaces (produz):** `WorkOrderId`, `WorkOrderStatus`, `WorkOrderPriority`, `WorkOrderOrigin`, `WorkOrderCategory`, `MaintenancePermissions.{READ, REQUEST, MANAGE}`, `WorkOrdersService` com `open`, `update`, `start`, `hold`, `resume`, `complete`, `cancel`; tabela `maintenance.work_order` com `out_of_order_id` anulável já criado.

### Task 2: Anexos e aviso de OS urgente

**Objetivo:** D5 e D6.

**Critérios de aceite:**
- `WorkOrderAttachmentsTest` (`@IntegrationTest`, novo, com `FilesTestSupport`): enviar foto JPEG com EXIF e um PDF com as finalidades da OS, vincular (`200`, ids na resposta da OS), obter a URL assinada pelo `files` e baixar (a foto sem EXIF); anexo de outra finalidade → `422 WORK_ORDER_ATTACHMENT_INVALID`; anexo já vinculado a outra OS → `409 FILE_ALREADY_ATTACHED`; a recepção vincula na OS que abriu e recebe `403` na OS alheia e no detach; desvincular (com `manage`) tira o id da resposta e marca `detached_at`; OS final recusa vincular; sem `read` não obtém a URL. Verificação: `timeout 900 ./gradlew test --tests '*WorkOrderAttachmentsTest'`.
- `WorkOrderUrgentNoticeTest` (`@IntegrationTest`, novo): OS criada `URGENT` gera um aviso `notifications.urgent-work-order` para um usuário com `manage` e nenhum para o ator; editar para `URGENT` gera um; editar uma já `URGENT` não gera outro (Review Focus 5); o corpo não contém a descrição. Verificação: `timeout 900 ./gradlew test --tests '*WorkOrderUrgentNoticeTest'`.
- `ModularityTest` com o teste renomeado para `maintenance depends only on shared, hotel, rooms, staff, files, notifications`. Verificação: `timeout 900 ./gradlew test --tests '*ModularityTest'`.
- `timeout 900 ./gradlew checkOpenApi lint` verde.

**Áreas tocadas:** `maintenance/MaintenanceModule.kt` (`allowedDependencies`), `ModularityTest`, `maintenance/MaintenanceFilePurposes.kt` (novo), `maintenance/application/MaintenanceConfiguration.kt` (bean da finalidade), `maintenance/application/WorkOrderAttachments.kt` (novo), `maintenance/application/WorkOrdersService.kt` (aviso), `maintenance/infrastructure/` (tabela do vínculo), `maintenance/web/WorkOrdersController.kt`, migração da tabela do vínculo, `api/openapi.yaml`.

**Tamanho:** ~45 min; 7 arquivos de produção, mais a migração e o contrato.

**Depende de:** Task 1.

**Interfaces (consome):** `WorkOrdersService` e a tabela `maintenance.work_order` (Task 1).

### Task 3: Bloqueio da UH em OOO/OOS e devolução como Suja

**Objetivo:** D7.

**Critérios de aceite:**
- `WorkOrderRoomBlockTest` (`@IntegrationTest`, novo): OOO sem a permissão do `rooms` nem aprovação → `403 APPROVAL_REQUIRED`; com aprovação de superior → período OOO na UH, `outOfOrderId` na OS; OOO em UH ocupada → `422 OUT_OF_ORDER_ROOM_OCCUPIED`; OOS com `OUT_OF_SERVICE_MANAGE` sem aprovação; OS de área → `422 WORK_ORDER_NOT_ROOM`; segundo bloqueio → `409 WORK_ORDER_ROOM_ALREADY_BLOCKED` (Review Focus 2); concluir a OS (sem a permissão do `rooms`) libera o período e deixa a UH Suja, com histórico de origem `MANUAL`; cancelar com OOO vigente sem aprovação → `403 APPROVAL_REQUIRED`, com aprovação libera; cancelar com OOS vigente exige `OUT_OF_SERVICE_MANAGE`; `PUT` que troca o local com bloqueio vigente → `409 WORK_ORDER_LOCATION_LOCKED`; concluir uma OS cujo período foi liberado à mão no `rooms` não falha (Review Focus 1); a ordem das recusas do D7. Verificação: `timeout 900 ./gradlew test --tests '*WorkOrderRoomBlockTest'`.
- Contrato regenerado; `timeout 900 ./gradlew checkOpenApi lint` e `timeout 600 .github/scripts/check-api-breaking.sh` sem `ERR`.

**Áreas tocadas:** `maintenance/application/WorkOrderRoomBlocks.kt` (novo), `maintenance/application/WorkOrdersService.kt` (liberar no `complete`/`cancel`, step-up no `cancel`, local travado no `PUT`), `maintenance/domain/WorkOrder.kt` (bloqueio), `maintenance/web/WorkOrdersController.kt`, `api/openapi.yaml`.

**Tamanho:** ~45 min; 5 arquivos de produção, mais o contrato.

**Depende de:** Task 1.

**Interfaces (consome):** `RoomsApi.openOutOfOrder`, `RoomsApi.releaseOutOfOrder`, `Authorization.requireOrApproval`, `RoomsPermissions.OUT_OF_ORDER_MANAGE` e `OUT_OF_SERVICE_MANAGE`.
