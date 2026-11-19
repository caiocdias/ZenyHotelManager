# E07 do M6 — Ordens de serviço corretivas e OOO/OOS: plano de implementação

> **Para agentes:** execução por `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde, não o código.

**Goal:** criar o módulo `maintenance` com a OS corretiva (RF-MAN-01 a RF-MAN-03): local, categoria, prioridade, descrição, anexos, origem, responsável interno ou terceiro, estados, custos informados, bloqueio da UH em OOO/OOS pela `RoomsApi` com o step-up do OOO, devolução da UH como Suja ao concluir e aviso de OS urgente.

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E07", e a nota herdada do M2 "E07, OOO pela manutenção" (em "Hipóteses e decisões em aberto"); SPEC RF-MAN-01 a RF-MAN-03, RN-07, RF-ACS-08, RF-NOT-01, §8.2.12 (`WorkOrder`; `Asset`, `PreventivePlan` e `WorkOrderPart` ficam fora); ARCHITECTURE §3.1 (linha `maintenance`), §7, §8, §11. Brain: `ZenyHotelManager - Arquitetura e estrutura de módulos`, `ZenyHotelManager - Schemas, migrações e views de relatório`, `ZenyHotelManager - Segurança, autenticação e autorização`, `ZenyHotelManager - Auditoria`, `ZenyHotelManager - Convenções de API`, `ZenyHotelManager - Módulo rooms`, `ZenyHotelManager - Módulo files`, `ZenyHotelManager - Módulo notifications`, `ZenyHotelManager - Módulos hotel e staff`, `ZenyHotelManager - Testes de integração` e as capturas pendentes da E20 e da E21.

**Architecture:** módulo novo `com.zeny.hotel.maintenance` (camada Operação), com schema próprio `maintenance` e `allowedDependencies = ["shared", "hotel", "rooms", "staff", "files", "notifications"]` — subconjunto da linha da §3.1 (sem `checklists`, que entra na E08, e sem `inventory`, que não existe até o M9). A `WorkOrder` é um agregado com máquina de estados pura no `domain`; o caso de uso trava a OS, confere a transição, grava, audita e chama as APIs de plataforma. O bloqueio da UH é um período OOO/OOS do `rooms` aberto pela `RoomsApi.openOutOfOrder` (que não confere permissão): a manutenção faz o próprio step-up antes (`authorization.requireOrApproval` com `RoomsPermissions.OUT_OF_ORDER_MANAGE`, como o `OutOfOrderService` do `rooms`), guarda o `OutOfOrderId` na OS e o libera pela `releaseOutOfOrder` ao concluir ou cancelar (a UH volta como Suja pelo `returnStatus = DIRTY`). O alerta de reserva alocada no período é do `reservations` (já escuta `OutOfOrderChanged`).

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1, Hibernate 7.4, Flyway 12, Testcontainers.

**Riscos:**
- **autenticação ou autorização:** escopo `OWN` do técnico na leitura e nas ações; step-up do OOO; permissão de quem só pede OS (recepção e governança) (Tasks 1 e 3);
- **cálculo de valor monetário:** custos em `Money` (mão de obra, terceiros, peças informadas), nunca negativos (Task 1);
- **parsing de entrada externa não confiável:** corpos da OS, enum de categoria e prioridade, período do bloqueio, ids de anexo (Tasks 1, 2 e 3);
- **concorrência:** duas ações na mesma OS (`If-Match`), conclusão × liberação do OOO (Tasks 1 e 3);
- **migração ou escrita destrutiva de dados:** schema e tabelas novas, sequence do número (Task 1).

## Global Constraints

- Grafo §3.1 com o `ModularityTest` em igualdade exata: teste novo `maintenance depends only on shared, hotel, rooms, staff, files, notifications`; `maintenance` não depende de `inventory` (que não existe) nem de `checklists` nesta etapa. A linha da §3.1 não muda (as arestas usadas já estão nela).
- Schema `maintenance` criado como os demais (papéis, `GRANT` ao papel da aplicação, `DatabaseRolesTest`), migrações com timestamp posterior à última do projeto no momento (depois da E22: `reservations/V2026_11_19_1700__…`), `outOfOrder` desligado; a API não expõe `DELETE` (OS cancelada, não apagada); tabela de OS sem `DELETE` para o papel da aplicação.
- Número legível pelo `DocumentNumberGenerator` com `DocumentNumberType("OS", "maintenance.work_order_number_seq")`, ano da data operacional.
- Erros RFC 9457 com `code` estável; referência inexistente no corpo `400 …UNKNOWN_…` (H16); transição inválida `409 WORK_ORDER_TRANSITION_NOT_ALLOWED`; ações com `If-Match` (`412` na versão errada); entrada estrita da E20; `409 CONCURRENT_UPDATE` da E21; enum de resposta que cresce como `x-extensible-enum`.
- Autorização antes de qualquer recusa que revele estado (`403` antes de `404`/`409`/`412`). `@Audited` sem dado pessoal (a descrição é texto livre: não vai à trilha).
- RN-13: a OS não guarda nome, documento nem contato de hóspede; a descrição é texto livre do operador, e o KDoc/contrato orienta a não pôr dado de hóspede.
- Contrato regenerado (`./gradlew updateOpenApi`); `checkOpenApi` e `.github/scripts/check-api-breaking.sh` sem `ERR`.
- Código em inglês; KDoc, ARCHITECTURE e commits em pt-BR; commits com `Soul:`.
- Um Gradle por vez; `timeout` em todo comando; sem laço de espera; nunca `./gradlew --stop`.
- Testes: banco compartilhado, só os próprios dados (UHs e áreas próprias); data operacional corrente sem avanço; quem mexe no relógio o devolve no `finally`.
- Verificação da fatia: os testes focados citados mais `timeout 900 ./gradlew checkOpenApi lint`.

## Review Focus

1. **Concluir ou cancelar uma OS cujo período OOO já foi liberado à mão no `rooms`** (ou que nunca começou): a OS conclui; a liberação repetida não falha nem muda a UH de novo. Teste na Task 3.
2. **Bloquear a UH de uma OS que já tem bloqueio vigente**, ou de uma OS de área (sem UH): `409 WORK_ORDER_ROOM_ALREADY_BLOCKED` e `422 WORK_ORDER_NOT_ROOM`. Teste na Task 3.
3. **Técnico `OWN` pedindo `GET /{id}` de OS alheia ou agindo nela:** `403` (não `404`), igual ao padrão do projeto para escopo `OWN`. Teste na Task 1.
4. **Custo negativo, com mais de 2 casas ou acima do teto:** `400 VALIDATION_FAILED` no campo. Teste na Task 1.
5. **OS criada já urgente e OS que passa a urgente numa edição:** um aviso em cada caso; editar uma OS que já era urgente não reavisa. Teste na Task 2.

## Decisões do plano

- **D1 — permissões** (`MaintenancePermissions`, raiz do módulo): `maintenance.work-orders.read` (escopos `ALL` e `OWN`), `maintenance.work-orders.request` (só `ALL`: abrir OS) e `maintenance.work-orders.manage` (escopos `ALL` e `OWN`: editar, atribuir, agir, custos, bloqueio). Modelos: Gerente — as três `ALL`; Manutenção — `read` `OWN`, `request` e `manage` `OWN`; Governanta e Recepcionista — `request` e `read` `ALL` (acompanham o que pediram e o estado da UH). Dono de uma OS para o escopo `OWN`: quem abriu e o usuário do colaborador responsável (pela `StaffApi`).
- **D2 — agregado `WorkOrder`.** Campos: `id` (UUIDv7), `number` (`OS-AAAA-NNNNNN`), local (`roomId` **ou** `areaId`, exatamente um), `category` (`ELECTRICAL`, `PLUMBING`, `AIR_CONDITIONING`, `FURNITURE`, `OTHER`; `x-extensible-enum`), `priority` (`LOW`, `NORMAL`, `HIGH`, `URGENT`), `description` (1 a 2000 caracteres), `origin` (`RECEPTION`, `HOUSEKEEPING`, `MAINTENANCE`, `CHECKLIST`; o `POST` aceita as três primeiras e recusa `CHECKLIST`, reservado à E08), `assigneeEmployeeId` (anulável; colaborador ativo do `staff`, inexistente → `400 UNKNOWN_EMPLOYEE`), `thirdPartyName` (anulável, texto até 200), `status`, `holdReason` (anulável, até 500, só em `ON_HOLD`), `laborCost`, `thirdPartyCost`, `partsCost` (`Money`, ≥ 0, padrão 0), `openedBy`, `openedAt`, `dueAt` (anulável), `startedAt`, `closedAt`, `completionNotes` (até 2000), `cancellationReason` (até 500), `outOfOrderId` (anulável, Task 3), `version`.
- **D3 — máquina de estados** (`WorkOrderStatus`: `OPEN`, `IN_PROGRESS`, `ON_HOLD`, `DONE`, `CANCELLED`; "aguardando peça/terceiro" = `ON_HOLD` com motivo): `OPEN → IN_PROGRESS` (`start`), `IN_PROGRESS → ON_HOLD` (`hold`, motivo obrigatório), `ON_HOLD → IN_PROGRESS` (`resume`), `OPEN | IN_PROGRESS | ON_HOLD → DONE` (`complete`, notas e custos opcionais), `OPEN | IN_PROGRESS | ON_HOLD → CANCELLED` (`cancel`, motivo obrigatório). `DONE` e `CANCELLED` são finais; editar ou agir numa OS final dá `409 WORK_ORDER_TRANSITION_NOT_ALLOWED`.
- **D4 — API** em `/api/v1/work-orders`: `POST` (`request`; `201`, `Location`, `ETag`), `GET` (lista com cursor, filtros `status`, `roomId`, `areaId`, `priority`, `assigneeEmployeeId`; `OWN` só as suas), `GET /{id}`, `PUT /{id}` (`manage`; edita local, categoria, prioridade, descrição, responsável, terceiro, prazo e custos enquanto não final; `If-Match`), e as ações `POST /{id}/start`, `/hold`, `/resume`, `/complete`, `/cancel` (`manage`; `If-Match`). Anexos e bloqueio nas Tasks 2 e 3.
- **D5 — anexos.** Finalidade `maintenance.work-order-photo` (PNG, JPEG, WEBP e PDF, até 10 MB, comum — não sensível —, upload `maintenance.work-orders.request`, download `maintenance.work-orders.read`, sem reencode porque aceita PDF). `POST /{id}/attachments` `{fileId}` vincula pela `FilesApi.attach` (finalidade errada `422 WORK_ORDER_ATTACHMENT_INVALID`); `POST /{id}/attachments/{fileId}/detach` desvincula (sem `DELETE`). A resposta da OS lista os ids dos anexos; o download é pela URL assinada do `files` (`GET /api/v1/files/{id}/download-url`). Tabela `maintenance.work_order_attachment` (OS, arquivo, quem, quando).
- **D6 — aviso urgente.** Ao criar uma OS `URGENT`, ou ao editá-la de outra prioridade para `URGENT`, `NotificationsApi.notify(URGENT_WORK_ORDER, …)` a `NotificationRecipients(permissions = {maintenance.work-orders.manage}, except = {ator})`, com número, local (número da UH ou nome da área) e categoria; sem a descrição (texto livre) nem dado de hóspede.
- **D7 — bloqueio da UH.** `POST /{id}/room-block` `{kind: OUT_OF_ORDER|OUT_OF_SERVICE, startsOn, endsOn}` (`manage`; `If-Match`; cabeçalho `X-Approval-Token` para o OOO): OOO passa por `authorization.requireOrApproval(SensitiveAction("maintenance.work-order.block-room", RoomsPermissions.OUT_OF_ORDER_MANAGE, "maintenance.work-order:<id>"), token)`; OOS exige `RoomsPermissions.OUT_OF_SERVICE_MANAGE`. Chama `RoomsApi.openOutOfOrder(roomId, kind, OutOfOrderRequest(startsOn, endsOn, reason = "OS <número>", responsible = <nome do responsável ou terceiro ou "Manutenção">, returnStatus = DIRTY))`; os erros do `rooms` (`422 OUT_OF_ORDER_ROOM_OCCUPIED`, `422 OUT_OF_ORDER_PERIOD_IN_PAST`, `409 OUT_OF_ORDER_OVERLAP`) passam como estão. Grava o `outOfOrderId` na OS. OS de área: `422 WORK_ORDER_NOT_ROOM`; bloqueio vigente: `409 WORK_ORDER_ROOM_ALREADY_BLOCKED`. `complete` e `cancel` liberam o período pela `releaseOutOfOrder(id, RoomStatusSource.MAINTENANCE)` (valor novo no enum do `rooms`, `x-extensible-enum`) na mesma transação; período já liberado não falha. Concluir sem bloqueio não mexe na UH.
- **D8 — tempo.** `startedAt` no primeiro `start`, `closedAt` no `complete`/`cancel`, pelo `Clock`; o relatório da E09 deriva o tempo daí.

---

### Task 1: Módulo, OS, estados e escopo `OWN`

**Objetivo:** D1 a D4 e D8: módulo e schema, permissões e modelos, agregado e máquina de estados, CRUD e ações, custos, número legível, auditoria e escopo `OWN`.

**Critérios de aceite:**
- `WorkOrderTest` (domínio, novo): cada transição válida de D3 e cada inválida (inclusive a partir de `DONE` e `CANCELLED`) com o erro de transição; `hold` sem motivo e `cancel` sem motivo recusados; custos negativos recusados. Verificação: `timeout 600 ./gradlew test --tests '*WorkOrderTest'`.
- `WorkOrdersApiTest` (`@IntegrationTest`, novo): criar OS de UH e de área (`201`, número `OS-<ano>-…`, `status = OPEN`); local com UH e área juntos ou nenhum → `400`; `roomId`/`areaId`/`assigneeEmployeeId` inexistentes → `400 UNKNOWN_…`; `origin = CHECKLIST` → `400`; ciclo `start → hold → resume → complete` com `If-Match` e `startedAt`/`closedAt`; `412` com versão errada; custos `-1`, `1.234` e acima do teto → `400` no campo (Review Focus 4); trilha sem a descrição. Verificação: `timeout 900 ./gradlew test --tests '*WorkOrdersApiTest'`.
- `WorkOrderPermissionsTest` (`@IntegrationTest`, novo): sem permissão `403` antes de `404`/`409`/`412`; técnico `OWN` lista só as OS que abriu ou de que é responsável e recebe `403` em `GET`/ação de OS alheia (Review Focus 3); recepção abre e lê, mas não age (`403`); permissões e modelos no catálogo. Verificação: `timeout 900 ./gradlew test --tests '*WorkOrderPermissionsTest'`.
- `ModularityTest` com o teste novo do `maintenance`; `DatabaseRolesTest` com o schema novo. Verificação: `timeout 900 ./gradlew test --tests '*ModularityTest' --tests '*DatabaseRolesTest'`.
- Contrato regenerado; `timeout 900 ./gradlew checkOpenApi lint` verde.

**Áreas tocadas:** `maintenance/` novo: `MaintenanceModule.kt`, `MaintenancePermissions.kt`, `MaintenanceTypes.kt` (ids, enums, códigos de erro), `domain/WorkOrder.kt`, `application/WorkOrdersService.kt`, `application/MaintenanceAudit.kt`, `application/MaintenanceConfiguration.kt` (beans das permissões), `infrastructure/WorkOrderPersistence.kt`, `web/WorkOrdersController.kt` (com os DTOs); migrações `src/main/resources/db/migration/maintenance/` (schema, tabela, sequence, grants) e o que o projeto exige para registrar um schema novo (configuração do Flyway e dos papéis, como o `housekeeping` fez na E01); `api/openapi.yaml`; `ModularityTest`.

**Tamanho:** ~60 min; 10 arquivos de produção, mais migrações, configuração do schema e contrato.

**Depende de:** nenhuma.

**Interfaces (produz):** `WorkOrderId`, `WorkOrderStatus`, `WorkOrderPriority`, `WorkOrderOrigin`, `MaintenancePermissions.{READ, REQUEST, MANAGE}`, `WorkOrdersService` com `open`, `update`, `start`, `hold`, `resume`, `complete`, `cancel`; tabela `maintenance.work_order` com `out_of_order_id` anulável já criado.

### Task 2: Anexos e aviso de OS urgente

**Objetivo:** D5 e D6.

**Critérios de aceite:**
- `WorkOrderAttachmentsTest` (`@IntegrationTest`, novo, com `FilesTestSupport`): enviar foto com a finalidade da OS, vincular (`200`, id na resposta da OS), obter a URL assinada pelo `files` e baixar o mesmo conteúdo; anexo de outra finalidade → `422 WORK_ORDER_ATTACHMENT_INVALID`; anexo já vinculado a outra OS → `409 FILE_ALREADY_ATTACHED`; desvincular tira o id da resposta; sem `read` não obtém a URL. Verificação: `timeout 900 ./gradlew test --tests '*WorkOrderAttachmentsTest'`.
- `WorkOrderUrgentNoticeTest` (`@IntegrationTest`, novo): OS criada `URGENT` gera um aviso `notifications.urgent-work-order` para um usuário com `manage` e nenhum para o ator; editar para `URGENT` gera um; editar uma já `URGENT` não gera outro (Review Focus 5); o corpo não contém a descrição. Verificação: `timeout 900 ./gradlew test --tests '*WorkOrderUrgentNoticeTest'`.
- `timeout 900 ./gradlew checkOpenApi lint` verde.

**Áreas tocadas:** `maintenance/MaintenanceFilePurposes.kt` (novo), `maintenance/application/MaintenanceConfiguration.kt` (bean da finalidade), `maintenance/application/WorkOrderAttachments.kt` (novo), `maintenance/application/WorkOrdersService.kt` (aviso), `maintenance/infrastructure/` (tabela do vínculo), `maintenance/web/WorkOrdersController.kt`, migração da tabela do vínculo, `api/openapi.yaml`.

**Tamanho:** ~45 min; 6 arquivos de produção, mais a migração e o contrato.

**Depende de:** Task 1.

**Interfaces (consome):** `WorkOrdersService` e a tabela `maintenance.work_order` (Task 1).

### Task 3: Bloqueio da UH em OOO/OOS e devolução como Suja

**Objetivo:** D7.

**Critérios de aceite:**
- `WorkOrderRoomBlockTest` (`@IntegrationTest`, novo): OOO sem permissão nem aprovação → `403 APPROVAL_REQUIRED`; com aprovação de superior → período OOO na UH, `outOfOrderId` na OS; OOO em UH ocupada → `422 OUT_OF_ORDER_ROOM_OCCUPIED`; OOS com `OUT_OF_SERVICE_MANAGE` sem aprovação; OS de área → `422 WORK_ORDER_NOT_ROOM`; segundo bloqueio → `409 WORK_ORDER_ROOM_ALREADY_BLOCKED` (Review Focus 2); concluir a OS libera o período e deixa a UH Suja, com histórico de origem `MAINTENANCE`; cancelar também libera; concluir uma OS cujo período foi liberado à mão no `rooms` não falha (Review Focus 1). Verificação: `timeout 900 ./gradlew test --tests '*WorkOrderRoomBlockTest'`.
- Testes do `rooms` sobre a origem nova seguem verdes: `timeout 900 ./gradlew test --tests '*OutOfOrder*' --tests '*RoomStatus*'`.
- Contrato regenerado (`RoomStatusSource` com `MAINTENANCE`, extensível); `timeout 900 ./gradlew checkOpenApi lint` e `timeout 600 .github/scripts/check-api-breaking.sh` sem `ERR`.

**Áreas tocadas:** `rooms/RoomsApi.kt` (`RoomStatusSource.MAINTENANCE`), `maintenance/application/WorkOrderRoomBlocks.kt` (novo), `maintenance/application/WorkOrdersService.kt` (liberar no `complete`/`cancel`), `maintenance/domain/WorkOrder.kt` (bloqueio), `maintenance/web/WorkOrdersController.kt`, `api/openapi.yaml`.

**Tamanho:** ~45 min; 5 arquivos de produção, mais o contrato.

**Depende de:** Task 1.

**Interfaces (consome):** `RoomsApi.openOutOfOrder`, `RoomsApi.releaseOutOfOrder`, `Authorization.requireOrApproval`, `RoomsPermissions.OUT_OF_ORDER_MANAGE` e `OUT_OF_SERVICE_MANAGE`.
