# Roadmap — M6 Operação diária e fechamento

> Arquivo: `docs/roadmaps/roadmap-m6-operacao-fechamento.md` · branch de execução: `roadmap/m6-operacao-fechamento` · criado em 2026-10-13 · última revisão 2026-11-18
>
> Marco 6 de 11 · mapa dos marcos em [`docs/roadmaps/README.md`](README.md) · marco anterior: [M5 — Recepção, FNRH e impressão](roadmap-m5-recepcao-fnrh.md) · próximo marco: [M7 — Pagamentos integrados e fiscal](roadmap-m7-pagamentos-fiscal.md)
>
> **Pré-requisito:** a etapa de gate do marco M5 (E12 de `docs/roadmaps/roadmap-m5-recepcao-fnrh.md`) está `#concluida` — atendido em 2026-11-12 (gate verificado em `6c16557`, integrado na `main` pelo merge `ab8b406`; decisões de arquitetura da E12 registradas em `3a42e35`). A M5 E09 (homologação da FNRH) segue `#bloqueada` pela Q-12, pela exceção M5 → M6 de `docs/roadmaps/README.md`: não trava o M6 e é cobrada pelo gate de inauguração (M8 E13).

## Como usar

Inicie uma sessão limpa do Claude Code na raiz do repositório com `claude --agent orquestrador` e cole o "Prompt de execução". Ele executa até 3 etapas em sequência na branch `roadmap/m6-operacao-fechamento`, cada uma planejada pelo plugin superpowers e executada pelas souls (subagentes do Second Brain), e para para revisão. No fim do lote, o orquestrador integra a branch na `main` com merge local e deixa pronto o `git push origin main`, que fica com você. Para não parar em cada pedido de permissão, libere antes os comandos de build/teste do projeto nas permissões do Claude Code (os comandos oficiais estão na tabela do `CLAUDE.md`; o Docker é necessário para os testes de integração).

**Sincronizado com o código em 2026-11-12**, depois do gate do M5 (`main` em `3a42e35`), pelo `arquiteto` com a skill `development-roadmap` no modo atualização (regra 2 de `docs/roadmaps/README.md`). As APIs públicas, os eventos, as portas e as views de M1 a M5 que o marco usa foram conferidos no código e estão em "Base técnica herdada", em "Contexto confirmado"; as notas do handoff da M5 E12 entraram nas etapas que tocam. Entraram três etapas de manutenção herdadas do M5 (E15 a E17), e o gate passou a depender delas. Nenhuma etapa foi renumerada nem removida, e todas seguem `#pendente`.

**Revisado em 2026-11-18**, depois do lote E18–E03 (`main` em `f992696`), pelo `arquiteto` com a skill `development-roadmap` no modo atualização. A E04 foi dividida (H18 e H21): a E04 ficou com a máquina de estados, as ações online e a conclusão, e a nova **E19** com os comandos offline, o conflito RN-22 e o feed. As pendências transversais da E18 e da E01 viraram a **E20** (entrada estrita) e a **E21** (erros de trava e logs), que rodam antes da E04. O early check-in (H17) ganhou a **E22**. As decisões do usuário estão na H20. A SPEC (§8.2.6, §8.3 e §8.4) e a ARCHITECTURE (§6.1 e §7) foram alinhadas às H19 e H20 na mesma revisão.

## Objetivo

Fechar a Fase 1 da SPEC: o hotel opera a hospedagem. A governança gera, distribui, executa (inclusive offline) e inspeciona as tarefas de limpeza; a manutenção corretiva abre e conclui ordens de serviço com OOO/OOS; o fechamento do dia roda automaticamente, é idempotente e retomável, lança diárias e avança a data operacional; e o painel do dia e os relatórios operacionais ficam disponíveis, com os relatórios do fechamento arquivados em PDF.

## Contexto confirmado

- Requisitos: SPEC §5.14 (GOV), §5.16 (MAN, só a parte corretiva: RF-MAN-01, 02, 03, 07), §5.13 (FEC), §5.23 RF-REL-01, RF-REL-06 e RF-REL-07, §5.22 RF-NOT-01 (avisos de tarefa atribuída, UH liberada, inspeção reprovada, OS urgente e pendência do fechamento).
- Regras: RN-05, RN-08 (transições de status), RN-09 (sujeira automática), RN-13 (minimização no app operacional), RN-15 (multa de no-show pela política vigente), RN-16 (bloqueio vencido), RN-21 (fechamento único), RN-22 (offline), RN-23 (pré-reserva), RF-FEC-05 (não lançar em data fechada).
- Modelo de domínio: SPEC §8.2.9 (`EndOfDayRun`, `EndOfDayStep`, `EndOfDayIssue`, `DailyStatistics`), §8.2.10 (`CleaningType`, `CreditRule`, `HousekeepingTask`, `AssignmentPlan`, `Inspection`, `ScheduledCleaning`, `LostAndFoundItem`, `DamageReport`), §8.2.12 (`WorkOrder`; `Asset`, `PreventivePlan` e `WorkOrderPart` ficam fora); máquinas de estado da tarefa de governança e do status de governança da UH em §8.4; eventos em §8.3 (`GuestCheckedOut`, `RoomMoved`, `ChecklistCompleted`, `HousekeepingTaskCompleted`, `BusinessDateAdvanced`).
- Arquitetura: ARCHITECTURE §3.1 — `housekeeping` depende de `shared`, `hotel`, `rooms`, `reservations`, `staff`, `checklists`, `billing`, `notifications`; `maintenance` depende de `shared`, `hotel`, `rooms`, `staff`, `checklists`, `inventory`, `files`, `notifications` (`inventory` só existe em M9); `endofday` depende de `shared`, `hotel`, `rooms`, `reservations`, `billing`, `payments`, `reporting`, `notifications`; `reporting` consulta só views `report_*` (§11). Regra por camadas da §3.1: qualquer módulo pode chamar os serviços de plataforma (`files`, `notifications`, `printing`, `hotel`) e depender de camadas inferiores sem aprovação, desde que o grafo siga acíclico e a aresta nova seja acrescentada à tabela §3.1 na mesma mudança; `notifications` e `printing` nunca escutam eventos de negócio — quem gera o aviso ou é dono do documento os chama. Fluxo offline em §6.2 e §9; jobs em §13 (fechamento idempotente e retomável com ShedLock; geração de tarefas após `BusinessDateAdvanced`); inversão de dependência em §3.1 (`housekeeping` escuta `BusinessDateAdvanced` de `hotel`).
- Comandos de build, teste, lint e verificação do contrato: na tabela de comandos oficiais do `CLAUDE.md`; não são repetidos aqui.
- Convenções herdadas, documentadas nas notas de tema do brain (lista abaixo): M0 (API, erros, migrações, `Money`, IDs, idempotência, outbox, ShedLock), M1 (autenticação, `authz.require`/escopo `OWN`/limites/step-up, `@Audited`, data operacional, `staff` e escala, `files`), M2 (`rooms` e status, SSE, sync offline com `OfflineCommandHandler`, `checklists`, `notifications`), M3 (`guests`, `billing`, `payments` manual), M4 (`rates`, `reservations`, disponibilidade), M5 (`frontdesk`, eventos de hospedagem, `fnrh.report_submissions`, documentos montados pelo módulo dono e renderizados por `printing`).
- Revisão de arquitetura dos roadmaps: `docs/superpowers/specs/2026-11-06-revisao-arquitetura-roadmaps-design.md`.
- Conhecimento do projeto no Second Brain: hub `ZenyHotelManager` e as notas de tema `ZenyHotelManager - Estado por marco`, `ZenyHotelManager - Arquitetura e estrutura de módulos`, `ZenyHotelManager - Schemas, migrações e views de relatório`, `ZenyHotelManager - Tipos do kernel`, `ZenyHotelManager - Convenções de API`, `ZenyHotelManager - Segurança, autenticação e autorização`, `ZenyHotelManager - Auditoria`, `ZenyHotelManager - Eventos e jobs`, `ZenyHotelManager - Tempo real (SSE)`, `ZenyHotelManager - Sincronização offline`, `ZenyHotelManager - Testes de integração`, `ZenyHotelManager - Módulos hotel e staff`, `ZenyHotelManager - Módulo files`, `ZenyHotelManager - Módulo rooms`, `ZenyHotelManager - Módulo checklists`, `ZenyHotelManager - Módulo notifications`, `ZenyHotelManager - Módulo billing (contas, roteamento e taxa de serviço)`, `ZenyHotelManager - Módulo billing (caixa, pagamentos, caução e encerramento)`, `ZenyHotelManager - Módulo rates`, `ZenyHotelManager - Módulo reservations (inventário, disponibilidade e reservas)`, `ZenyHotelManager - Módulo reservations (ciclo de vida, alocação, grupos e busca)`, `ZenyHotelManager - Hospedagem e recepção` e `ZenyHotelManager - Docker, observabilidade e CI` (este para o gate).
- Caminhos de código previstos: um pacote por módulo sob o pacote base `com.zeny.hotel` — `housekeeping/`, `maintenance/`, `endofday/`, `reporting/` (a criar; conferido em 2026-11-12 que nenhum existe).
- **Base técnica herdada do M5** (conferida no código em 2026-11-12, `main` em `3a42e35`; referência completa nas notas de tema e nas capturas pendentes `ZenyHotelManager - atualização m5-recepcao-fnrh E08` a `E12`):
  - **Módulos** em `src/main/kotlin/com/zeny/hotel/`: `shared`, `iam`, `audit`, `hotel`, `staff`, `files`, `rooms`, `checklists`, `notifications`, `guests`, `payments`, `billing`, `rates`, `reservations`, `frontdesk`, `fnrh` e `printing`. Cada um declara `allowedDependencies` no `…Module.kt`, e o `ModularityTest` confere a lista exata; módulo novo ganha o seu teste, e aresta nova atualiza o teste e a tabela §3.1 na mesma mudança. A ARCHITECTURE §3.2 registra desde 2026-11-12 a ordem interna da Hospedagem (`rates` < `reservations` < `frontdesk`); `endofday` → `reporting` continua exceção de mesma camada.
  - **Migrações:** a última versão é `printing/V2026_11_11_1001__grant_printing_to_app_role.sql`; toda migração nova precisa de timestamp posterior a `V2026_11_11_1001` (`outOfOrder` desligado).
  - **`ReservationsApi`:** além do que o M4 deixou, `assignRoom`, `checkIn`, `undoCheckIn`, `checkOut` e `moveRoom` (M5 E01, sem permissão; quem chama se autoriza). **Não há** no-show nem consultas por data (chegadas, saídas, hospedados): só os endpoints (H4). Eventos em `reservations/ReservationsEvents.kt`: os de reserva do M4 (sem autor) e os de hospedagem `GuestCheckedIn`, `GuestCheckInUndone`, `GuestCheckedOut` (com `roomId`, `early`, `businessDate`) e `RoomMoved` (com `fromRoomId`, `toRoomId`, `businessDate`), todos com `performedBy` e `version`; o KDoc dos quatro diz que a governança (M6) os escuta. Consumidor de produção hoje: só o `fnrh` (`FnrhSubmissionListeners`); `RoomMoved` não tem nenhum.
  - **`HotelApi`:** `currentBusinessDate`, `timezone`, `profile()` (M5 E11), `policy`, `taxSettings`, `operationalSettings` e `advanceBusinessDate(expectedCurrent)`, único caminho de avanço, que devolve `BusinessDateAdvanced`.
  - **`RoomsApi`:** `markDirty`, `markClean`, `markInspected`, `rejectInspection`, `markOccupied`, `markOccupiedIfReady`, `markVacant`, `setServiceFlag`, `isReadyForCheckIn`, `outOfOrder`, `outOfOrderPeriods`, `openOutOfOrder` e `releaseOutOfOrder`; `RoomStatusSource` já tem `TASK` e `END_OF_DAY`. Sem registro de discrepância (H3).
  - **`BillingApi`:** `post` com `NewPosting.sourceReference` idempotente e a chave do fechamento fixada no KDoc (H5); a trinca da caução (`registerCardPreAuthorization`, `unsettledCardPreAuthorizations`, `releaseCardPreAuthorization`) e `cardPreAuthorizationsExpiring`. O extrato descreve o dono da conta pela porta `FolioOwnerDescriber` (M5 E11), implementada só para `RESERVATION`: quem criar conta de outro tipo de dono implementa a porta.
  - **Plataforma:** `ChecklistsApi.startExecution`/`completeExecution` (H2), `StaffApi.daySchedule`, `NotificationsApi.notify` com os tipos `task-assigned`, `room-released`, `inspection-rejected`, `urgent-work-order` e `end-of-day-pending` já no catálogo (`notifications/NotificationTypes.kt`), `PrintingApi.render`/`print` com templates registrados pelo dono (`ModuleDocumentTemplates`) e `FilesApi.readContent`, só de anexo comum. A `FilesApi` **não grava conteúdo** pelo servidor (H6).
  - **Views `report_*` publicadas:** `billing` (`report_cashier_counts`, `report_cashier_shifts`, `report_folios`, `report_payments`, `report_postings`), `checklists` (`report_check_item_outcome`, `report_execution_compliance`, `report_location_history`), `fnrh.report_submissions`, `guests.report_guests`, `rates` (`report_rate_plans`, `report_rate_prices`) e `reservations` (`report_block_allocations`, `report_inventory`, `report_reservations`, `report_room_assignments`, `report_room_nights`). O `rooms` não publica view.
  - **FNRH:** a porta `FnrhGateway` tem 10 operações e nenhuma de no-show; `FnrhOperation` vai de `REGISTER_RESERVATION` a `CHECK_OUT` (contrato `x-extensible-enum`) e é conferida por `submission_operation_ck` no schema `fnrh` (E14).
  - **Testes:** `@IntegrationTest` adia oito jobs (`testsupport/IntegrationTest.kt`); o banco é compartilhado por todos os testes; `ChecklistRoutinesTest`, `StayTransitionsApiTest` e `BusinessDateTest` já avançam a data operacional desse banco, e o `MilestoneM5EndToEndTest` nunca a avança (H10). Modelo de teste de gate: `MilestoneM5EndToEndTest`; modelo de compose do gate: `docker/gate/` (`run-gate.sh`, `gate-env.sh`, `compose.gate.yaml`, `fnrh-mock/`, `m5-roteiro.sh` e o `README.md` dele). Máquina de 14 GB: um Gradle ou `docker build` por vez, e nunca `./gradlew --stop`.
- **Base técnica do próprio M6** (conferida no código em 2026-11-18, `main` em `f992696`, para as etapas E04 e E19 a E22; referência completa nas capturas pendentes `ZenyHotelManager - atualização m6-operacao-fechamento` E18, E01, E02, E03 e "fim do lote E18-E03"):
  - **`housekeeping`** existe desde a E01, com as permissões `housekeeping.config.read`/`manage`, `housekeeping.tasks.read`/`request` e `housekeeping.assignments.manage`, **todas só com escopo `ALL`** (`HousekeepingPermissions.kt`), nos modelos Gerente e Governanta (a leitura e o pedido de tarefas também na Recepção). O modelo **Camareira** (`RoleTemplate.ROOM_ATTENDANT`) ainda não recebe nenhuma permissão do módulo. A última migração do módulo é `housekeeping/V2026_11_18_1102__add_task_assignee.sql`.
  - **`RoomsApi.markClean`** só leva Suja a Limpa ("uma UH já limpa, com inspeção pendente ou inspecionada não volta a limpa"); **`setServiceFlag`** recusa UH vaga com `422 ROOM_NOT_OCCUPIED`. A observação de ocupação da governança (RF-UH-08) só existe por endpoint (`RoomStatusService.recordObservation`, permissão `rooms.status.observe`, instante pelo `Clock`), sem método na `RoomsApi`.
  - **`ChecklistsApi`** tem `resolveTemplate`, `template(id)` (só o resumo, com `published`), `version(templateId, number)`, `version(id)`, `startExecution` e `completeExecution`; **não** tem a "versão publicada vigente de um modelo". Os comandos offline do `checklists` são `checklists.start-execution` e `checklists.complete-execution`, com o `versionId` vindo do aparelho, e as fontes são `checklist-templates` e `checklist-executions` (`ChecklistsOffline.kt`).
  - **Protocolo offline** (`shared/OfflineSync.kt`): o handler rejeita lançando `DomainException` (vira `REJECTED`) e aplica com alerta devolvendo `OfflineCommandOutcome.warnings`; `CLOCK_SKEW_EXCEEDED` é **alerta** de comando aplicado, não rejeição. Nomes vigentes de fonte em kebab-case no plural (`rooms`, `checklist-executions`) e de comando como `módulo.verbo-objeto` (`checklists.start-execution`).
  - **Transição inválida** no projeto é `409` (precedente `409 RESERVATION_TRANSITION_NOT_ALLOWED` do `reservations`).
  - **Catálogo do `notifications`** (`NotificationTypes.kt`): `task-assigned`, `room-released`, `inspection-rejected`, `urgent-work-order`, `low-stock`, `integration-failure` e `end-of-day-pending`; nenhum tipo para conflito de tarefa.
  - **Reserva** sem hora prevista de chegada nem pedido de early (SPEC §8.2.6 e `reservations/`); a política do hotel tem `checkInTime` (`HotelApi.policy()`).
  - **Contrato:** os schemas de request não marcam `required` (ex.: `CleaningTypeRequest` em `api/openapi.yaml`), e `.github/scripts/check-api-breaking.sh` roda `oasdiff breaking --fail-on ERR` sem lista de exceções. Há data em caminho desde a E03 (`/api/v1/assignment-plans/{date}`). Nenhum DTO de entrada usa `LocalDateTime` ou `OffsetDateTime`; `LocalTime` aparece na política do hotel, nos recados e nos checklists.
  - **Erros de trava:** só o `endofday` trata `55P03` por conta própria (`EndOfDayRunCommands`, `EndOfDayRunRepository`); no resto, `40P01` e `55P03` chegam ao `ApiExceptionHandler` como 500 (handoff da E01).
- **Pendências herdadas do M5 e onde entram aqui:** rótulos de motivo de viagem e meio de transporte na ficha e descrição de `frontdesk.stays.checkin` (M5 E11 (f) e (g)) → **E15**; teste de índices mais fraco nas listas por situação (Média da M5 E01) e `FnrhSubmissionDismissCompat.kt` duplicado em `src/test` → **E16**; corpo mínimo nas respostas de `restrict`, `lift-restriction` e `merge` do `guests` (M5 E15 (h)) → **E17**; no-show na FNRH (H15 do M5) → **E14**; caminho próprio do fechamento para mudar a data nos testes (handoff da M5 E12) → **E10** (H10); `docker/gate/` e o padrão do `MilestoneM5EndToEndTest` no gate → **E12**. Não são do M6: o aviso da LGPL do openhtmltopdf (documentação de implantação do M8), a homologação da FNRH (M5 E09, cobrada pela M8 E13) e o expurgo dos sensíveis da FNRH (M8 E16).

## Escopo

**Incluído:** módulo `housekeeping` completo da Fase 1; módulo `maintenance` com OS corretiva, OOO/OOS e app offline; módulo `reporting` com views, painel do dia, relatórios operacionais de governança e manutenção, snapshot de estatísticas diárias, exportação CSV/XLSX e arquivo dos PDFs do fechamento; módulo `endofday` com motor e etapas do fechamento.

**Fora de escopo:**

- Consumo de peças do estoque na OS (RF-MAN-06) e fornecedor como entidade (`Supplier`): M9, porque `inventory` ainda não existe. Até lá, o terceiro da OS é texto livre e o custo de peças é um valor informado.
- Ativos e manutenção preventiva (RF-MAN-04, RF-MAN-05): M10.
- Troca de enxoval e baixa de insumos pelas ações de checklist (RF-CHK-05): M9. Aqui a regra de troca de enxoval só é configurada no tipo de limpeza.
- Renovação de pré-autorizações no fechamento (RF-FEC-02 passo 6): M7. A etapa existe no motor e é registrada como "não aplicável" até lá.
- Notas rejeitadas e ARI não sincronizado no relatório de pendências (RF-FEC-04): M7 e M8, quando os módulos existirem.
- KPIs e relatórios gerenciais (RF-REL-02 a RF-REL-05, RF-REL-08): M10. Aqui só se grava o snapshot `DailyStatistics` que os alimentará.

## Restrições e invariantes

- Respeitar o grafo de módulos e a regra por camadas da ARCHITECTURE §3.1 (com a ordem interna da Hospedagem da §3.2); toda aresta nova (ex.: `reporting` → `files` e `reporting` → `printing` em E09) entra na tabela §3.1 na mesma etapa. Em especial: `housekeeping` não depende de `endofday` nem de `frontdesk` (reage a `BusinessDateAdvanced`, `GuestCheckedOut` e `RoomMoved`); `reporting` não lê tabelas internas de outros módulos, só views `report_*`; `maintenance` não chama `inventory` neste marco.
- RN-13: respostas e feeds de sincronização para camareira e manutenção nunca contêm nome, documento ou contato do hóspede.
- RN-21: uma única execução de fechamento por data, nunca em paralelo; cada etapa é idempotente (ex.: diária de `(reserva, data, origem=EOD)` lançada uma vez).
- RF-FEC-05: nada é lançado em data operacional fechada.
- RN-22: execução offline nunca é descartada; conflito é registrado e sinalizado à governanta.
- Transições de status da UH seguem §8.4 e passam pela API de `rooms`, com histórico (RF-UH-06).
- Ações sensíveis com step-up: fechamento manual, OOO (RF-ACS-08).
- Toda mudança de API regenera o contrato `api/openapi.yaml` com `./gradlew updateOpenApi` na mesma mudança (nunca editado à mão); a verificação do contrato e a de breaking change em `/api/v1` (`.github/scripts/check-api-breaking.sh`) passam. Enum de resposta que cresce sai como `x-extensible-enum`; `pattern` precisa ser válido em ECMA-262; a API não expõe `DELETE`: o que sai é desativado.
- Biblioteca nova entra pelo catálogo `gradle/libs.versions.toml`, com os lockfiles regravados, sem vulnerabilidade High ou Critical nova no scanner da revisão final (M5 E13 e E14).
- Documentação: a referência técnica não volta para o `CLAUDE.md`. Ao fechar cada etapa que muda algo descrito no brain (inclusive o estado do marco), o orquestrador publica a captura `ZenyHotelManager - atualização m6-operacao-fechamento Eyy` com o texto novo ou alterado das notas de tema afetadas; os módulos novos (`housekeeping`, `maintenance`, `reporting`, `endofday`) propõem cada um uma nota de tema nova (`ZenyHotelManager - Módulo housekeeping` e assim por diante). O `README.md` do repositório continua sendo atualizado quando a etapa muda algo que ele descreve.

## Hipóteses e decisões em aberto

| # | Hipótese ou decisão | Impacto se errada / sem decisão | Etapa afetada |
|---|---|---|---|
| H1 | **Confirmada no código em 2026-11-12:** pacote base `com.zeny.hotel`; `housekeeping/`, `maintenance/`, `endofday/` e `reporting/` não existem. | — | todas |
| H2 | Conferida no código em 2026-11-06 e de novo em 2026-11-12: `OfflineCommandHandler` (`shared/OfflineSync.kt`), o feed `GET /api/v1/sync/changes` e a execução de checklist (`ChecklistsApi.startExecution` e `completeExecution`) existem, e `ChecklistCompleted` carrega as ocorrências configuradas (`MAINTENANCE`, `LOST_AND_FOUND`, `DAMAGE`). | Se faltar, a etapa afetada para e relata; não reimplementar a base de sync. | E04, E06, E08 |
| H3 | Conferida em parte em 2026-11-06 e de novo em 2026-11-12: a `RoomsApi` tem `markClean`, `markInspected`, `rejectInspection`, `markDirty`, `setServiceFlag`, `openOutOfOrder`/`releaseOutOfOrder` (sem conferir permissão) e `RoomStatusSource` com `TASK` e `END_OF_DAY`. O registro de discrepância (RF-UH-08) só existe por endpoint. | E04 acrescenta a discrepância à API pública de `rooms` (dependência permitida). | E04, E05, E07, E11 |
| H4 | Conferida em 2026-11-06, **caiu**, e de novo em 2026-11-12 (o M5 acrescentou só as transições de hospedagem): a `ReservationsApi` tem `releaseUnused`, `blocksDueForRelease` e `cancelReservation`, mas não tem no-show nem consultas por data, que só existem nos endpoints. | E11 acrescenta a transição e as consultas à API pública de `reservations`; E02 reutiliza as consultas. | E02, E11 |
| H5 | Conferida em 2026-11-06 e de novo em 2026-11-12: `NewPosting.sourceReference` dá a idempotência, com a chave do fechamento definida no KDoc da `BillingApi.post` (`reservations.reservation:<id>:<data>:room` ou `…:package:<packageId>`, pacotes inclusos à parte e sem taxa de serviço, como o check-out da M5 E04). Não há bloqueio de data fechada: `post` grava na data corrente. | E11 prova RF-FEC-05 e acrescenta o bloqueio só se a prova falhar. | E06, E11 |
| H6 | Os PDFs do fechamento são guardados em `files` (S3/MinIO) pelo próprio `reporting`, com um índice por data no schema `reporting`; o download é por URL assinada. A aresta `reporting` → `files` já consta na tabela §3.1; `endofday` não acessa `files`. **Conferido em 2026-11-12:** a `FilesApi` não grava conteúdo pelo servidor (só `find`, `attach`, `detach`, `delete` e `readContent`, este só de anexo comum; M5 H18 e D-E11-7); a E09 acrescenta a gravação pelo servidor como acréscimo mínimo ao `files`, com finalidade própria do `reporting` e decidindo se o PDF com nome de hóspede é anexo sensível. | Mudar de dono pede aresta nova na §3.1. Guardar os PDFs no schema do `reporting` (como o `printing` guarda os jobs) é a alternativa se a gravação na `FilesApi` se mostrar cara. | E09 |
| H7 | Exportação XLSX com biblioteca leve (ex.: fastexcel ou Apache POI) declarada no *version catalog*; os PDFs são renderizados por `printing` (M5): `reporting` monta os dados e chama `printing`, como todo módulo dono de documento. | Troca local ao `reporting`. | E09 |
| H8 | Avisos de pendência do fechamento (RF-NOT-01) são enviados por `endofday` chamando `notifications` (push e painel) para quem tem permissão de acompanhar o fechamento, além do andamento no SSE e do endpoint de pendências. | Ajuste de destinatários é configuração. | E10 |
| H9 | Q-14 (turndown) e Q-15 (cortesia e uso da casa) seguem os padrões da SPEC §10: turndown como tipo de limpeza desativado; cortesia conta na ocupação e não no ADR, uso da casa não conta em nenhum dos dois. | Ajuste de configuração e do cálculo de `DailyStatistics`. | E01, E09 |
| H10 | **Decidida pela opção recomendada pelo `arquiteto`, 2026-11-12 (sincronização):** o fechamento age sobre o hotel inteiro (no-show, diárias, UHs Sujas) e avança a data operacional, e no banco compartilhado dos testes isso lançaria diárias e no-shows nas reservas de outros testes e mudaria a data de todos. Por isso, os testes que rodam o fechamento com as etapas reais (E11, E13, E14 e o gate) usam um **caminho próprio**: um banco ou contexto de teste isolado só para eles, criado na E10 junto do apoio de testes do fechamento. Os demais testes continuam sem avançar a data (como o `MilestoneM5EndToEndTest`). O mecanismo exato fica com o plano da E10. **Mecanismo definido na E10 (2026-11-13):** banco próprio `zeny_endofday` no mesmo container de testes, pela anotação `@EndOfDayIntegrationTest` (`testsupport/EndOfDayIntegrationTest.kt`); cada teste termina com a data corrente sem execução ou `COMPLETED` (`EndOfDayTestRuns.settle()`) e afirma só sobre os próprios dados. | Sem o isolamento, os testes do fechamento ficam intermitentes e quebram testes de outros módulos. Alternativa descartada: rodar o fechamento no banco compartilhado só sobre as reservas do próprio teste, que exigiria um filtro de teste no código de produção. | E10, E11, E13, E14, E12 |
| H11 | **Confirmada no código em 2026-11-12:** o `fnrh` guarda os domínios da FNRH com rótulo (`FnrhDomainValue(code, label)`, `FnrhDomainCatalog.values`), semeados por migração desde o primeiro dia (`fnrh/V2026_11_04_1030__create_fnrh_domain_values.sql`, inclusive `TRAVEL_REASONS` e `TRANSPORT_MODES`) e trocados pelo `refresh`. A ficha (`frontdesk/application/RegistrationCardService.kt` e `templates/frontdesk/registration-card.*`) mostra os códigos crus com "(código FNRH)". | Se o catálogo não tiver o código, a ficha mostra o código cru, como hoje. | E15 |
| H12 | **Decidida pelo usuário em 2026-11-13 (entrevista do fim do lote da E10):** o fechamento manual responde `201` síncrono com a execução no estado em que estiver; uma execução por data (`run_business_date_uk`) com reconciliação das etapas na retomada; os problemas transversais achados na E10 (cursor por instante sem faixa em 9 consultas, `rollbackOn` global, Jackson aceitando número/data-hora em `format: date`, log do ShedLock com a mensagem do JDBC) viram uma etapa de manutenção do M6, a criar pelo `arquiteto` antes do gate (criada em 2026-11-13: **E18**); na E11, o gatilho automático só dispara o motor e volta, sem aumentar o pool do agendador. | Mudar qualquer uma delas depois pede migração (unicidade) ou breaking change (resposta do `POST`). | E11, E12, E18 |
| H13 | **Decidida pelo usuário em 2026-11-13 (entrevista do `arquiteto` ao criar a E18), pelas opções recomendadas:** (a) a faixa do cursor vale para as chaves de instante **e** de data (`PageCursor.instant` e `PageCursor.localDate`), o que cobre 15 consultas: as 9 por instante da E10 e mais 6 por data (`EndOfDayQueries` na lista de execuções, `ReservationQueries`, `RoomAssignments`, `TraceService`, `OutOfOrderService` e `Scheduling`), conferidas no código em 2026-11-13; (b) a leitura estrita vale para `format: date` **e** `format: date-time`: número (epoch) ou data-hora num campo de data, e número num campo de instante, são recusados; (c) o mapeador é um só: os payloads dos comandos offline também ficam estritos, e um comando fora do formato vira `REJECTED` com código, registrado e não descartado (RN-22). **Decidido pelo `arquiteto`:** a faixa vive no `PageCursor` (`shared`), e a validação local do `endofday` sai; o tratador de erro é o do agendador da aplicação, não um por job. Registrado na ARCHITECTURE §5, §7, §11 e §13. | Voltar atrás é só configuração. Nenhum cliente está em produção, então apertar a entrada agora não quebra ninguém. | E18 |
| H14 | **Decidida pelo usuário em 2026-11-15 (entrevista do fim do lote da E17), pelas opções recomendadas:** (a) as escritas `restrict`, `lift-restriction` e `merge` do `guests` mantêm `oneOf: [GuestResponse, GuestWriteSummaryResponse]` sem discriminador (com o ogen, os clientes recebem `JsonElement` nessas três operações); (b) o corpo mínimo é `{id, restricted}`, com a versão no `ETag`. Regra registrada pelo `arquiteto` em 2026-11-15 na ARCHITECTURE §7, linha "Resposta que varia com a permissão" (`oneOf` sem discriminador, ramos disjuntos pelo `required`, sem `additionalProperties: false`, mesmo media type, decisão no caso de uso). | Trocar por discriminador pede uma propriedade nova no `GuestResponse` de todas as operações de perfil; tirar `restricted` do mínimo é remoção de campo obrigatório (`ERR`). | E17 |
| H15 | **Decidida pelo usuário em 2026-11-17 (entrevista de abertura do lote), pela opção recomendada:** a execução anterior concluiu a E18 e a E01 e deixou a E02 `#em-andamento` (2026-11-15 e 16) numa branch `roadmap/m6-operacao-fechamento` local que nunca foi enviada ao remoto. Ela se perdeu com a troca da distro WSL: a `main` e o `origin` estão em `5e06f16`, em que as duas etapas seguem `#pendente`. A E18, a E01 e as seguintes são **refeitas a partir da `main`**, com as notas do brain como referência (Estado por marco, "M6 E18" e "M6 E01", e as notas de tema citadas lá), para o comportamento sair igual ao já documentado, salvo decisão nova registrada no handoff. Se a branch antiga reaparecer, ela é descartada. | As notas do brain descrevem commits que não existem; a captura de cada etapa refeita diz que o texto dela substitui o da branch perdida. | E18, E01, E02 |
| H16 | **Decidida pelo usuário em 2026-11-17 (entrevista de abertura do lote), pela opção recomendada:** no `housekeeping`, uma referência inexistente no corpo (tipo de UH, modelo de checklist, local, tipo de limpeza) dá **`400` com código `…UNKNOWN_…`**, o padrão do projeto (`UNKNOWN_FOLIO`, `UNKNOWN_GUEST`, `UNKNOWN_RATE_PLAN`), e não `422`. Isso substitui o "`422`" do critério da E01 para crédito com tipo de UH inexistente, e a D8 da execução perdida. O modelo de checklist de contexto incompatível continua `422` (violação de regra). | Os SDKs tratam a referência inexistente de um jeito só; voltar a `422` depois é breaking change. | E01 e as etapas do `housekeeping` |
| H17 | **Decidida pelo orquestrador em 2026-11-18 (autorização noturna do usuário), pela opção recomendada da revisão de arquitetura do plano da E03:** a prioridade de *early check-in* da RF-GOV-03 fica **adiada**. O `reservations` não guarda pedido de early nem hora prevista de chegada, e a E03 cumpre o critério "chegadas e early vêm primeiro" só para as chegadas. O `arquiteto` decide a etapa que leva o dado ao `reservations` e devolve o early à sugestão. Alternativa descartada: bloquear a E03 até o `reservations` ter o dado. | Sem o early, uma UH com early check-in pedido é distribuída como uma chegada comum. **Encaminhada em 2026-11-18 pelo usuário (H20 a e b):** a **E22** leva a hora prevista de chegada ao `reservations` e devolve o early à sugestão. | E03 e E22 |
| H18 | **Decidida pelo usuário em 2026-11-18 (entrevista do fim do lote E18–E03), pelas opções recomendadas:** (a) a E04 **não cabe** no formato de até 3 fatias de ~60 min (revisão de arquitetura do plano `docs/superpowers/plans/2026-11-18-m6-operacao-fechamento-e04.md`, rodada 0 bloqueada com 1 Crítico e 8 Importantes) e volta a `#pendente` para o `arquiteto` dividi-la (sugestão: E04a máquina de estados, ações online e conclusão; E04b comandos offline, conflito RN-22 e feed); (b) **conflito RN-22**: o comando offline de quem foi dona da tarefa e a perdeu (reatribuída, desatribuída ou cancelada) só é **registrado e sinalizado** (o que A fez, inclusive as respostas do checklist), sem mudar a tarefa nem a UH, e a governanta resolve; um **histórico de posse** distingue esse caso do de quem nunca foi dona, que é recusado (`403`/`REJECTED`); (c) a `notes` da avulsa **fica fora do feed** offline (RN-13, regra "nada de texto livre no feed"); (d) concluir tarefa numa UH já Limpa ou Inspecionada **não mexe na UH** (só tarefa e checklist). | A divisão muda o índice e as dependências do M6 (E05 e E06 dependem da E04). **Cumprida em 2026-11-18 pelo `arquiteto` (H21 a):** E04 (máquina de estados, ações online e conclusão) e **E19** (comandos offline, conflito RN-22 e feed); os achados da revisão do plano tentado estão nas duas. | E04, E19 (e as que dependem delas) |
| H19 | **Confirmadas pelo usuário em 2026-11-18 (entrevista do fim do lote E18–E03), pelas opções recomendadas:** (a) a transição `ASSIGNED → PENDING` da E03 (D7, recolher tarefa sem passá-la a outra camareira) fica, e o `arquiteto` a registra na SPEC §8.4; (b) a governança **não** escuta os eventos de reserva (D6 da E02): o plano do dia sai do avanço da data e a E03 recalcula a chegada; o `arquiteto` alinha a SPEC §8.3 e a ARCHITECTURE §6.1; (c) a exceção à H16 da E03 (D11: colaborador inexistente dá `422 ASSIGNMENT_EMPLOYEE_NOT_SCHEDULED`, validação pela escala) fica e é registrada; (d) o early check-in (H17) fica com o `arquiteto`, que decide a etapa que leva o dado ao `reservations`. | Mudar qualquer uma pede etapa de manutenção. **Registradas em 2026-11-18 pelo `arquiteto`:** (a) na SPEC §8.4; (b) na SPEC §8.3 e na ARCHITECTURE §6.1; (c) na ARCHITECTURE §7, linha "Erros"; (d) na H20 e na E22. | E02, E03 e o `arquiteto` |
| H20 | **Decidida pelo usuário em 2026-11-18 (entrevista do `arquiteto` na revisão do roadmap), pelas opções recomendadas:** (a) o early check-in entra numa **etapa nova do M6, antes do gate** (**E22**), porque a RF-GOV-03 é da Fase 1, que o gate declara encerrada; (b) o `reservations` guarda a **hora prevista de chegada**, opcional, no fuso do hotel; o early é derivado (chegada prevista antes do `checkInTime` da política), e a hora também ordena as chegadas. É o mesmo dado que as OTAs mandam (M8); (c) as pendências transversais da E18 e da E01 rodam **antes da E04** (**E20** e **E21**, no bloco "Manutenção transversal"), para que a E04, a E19 e as etapas E05 a E09 já nasçam sobre elas, como fez a E18; (d) o contrato passa a marcar `required` em **todos** os schemas de request com campo que o servidor exige. O `oasdiff` acusa `ERR` nessa mudança, e ela é aceita **uma única vez**, na E20, registrada na ARCHITECTURE §7: o servidor não muda de comportamento (já recusa o campo faltando) e nenhum cliente está em produção. **Cumprida pela E20 em 2026-11-19, sem usar a quebra:** o contrato já marcava `required` pela Bean Validation e pelas propriedades não anuláveis; a regra única tirou 34 `required` de campos com valor padrão e não acrescentou nenhum (handoff da E20, D1). | Voltar atrás em (b) depois do M8 pede migração do dado das OTAs; em (d), outra quebra no contrato. | E20, E21, E22, E04 |
| H21 | **Decidida pelo `arquiteto` em 2026-11-18 (revisão do roadmap), revisável:** (a) a divisão da E04 mantém o ID **E04** para a parte online (máquina de estados, ações e conclusão) e cria a **E19** para a offline. Os nomes "E04a" e "E04b" sugeridos não passam no verificador do roadmap, que só aceita `E` seguido de dígitos, e a regra da skill é não reutilizar nem renumerar IDs; (b) as pendências transversais viram **duas** etapas, a E20 (entrada estrita: corpo, query, caminho e `required`) e a E21 (erros de trava, logs dos jobs, filtro do ShedLock e `JOSEException`), para cada uma caber em até 3 fatias de ~60 min; a E04 mostrou o custo de uma etapa grande demais; (c) `40P01` (deadlock) e `55P03` (`lock_timeout`) que escapam de um caso de uso dão **`409 CONCURRENT_UPDATE`**, retentável e sem a mensagem do banco (ARCHITECTURE §7); o tratamento próprio do `endofday` continua; (d) a leitura estrita da E20 inclui `LocalTime` (só `HH:mm` ou `HH:mm:ss`), que já está na política do hotel, nos recados e nos checklists e que a E22 vai usar; (e) E05 e E06 passam a depender da E19, porque repetem o padrão offline dela; (f) **Revisão humana**: sim na E04 (contrato `OWN` da camareira e máquina de estados que a E05 e a E19 repetem), na E19 (padrão offline repetido por E05, E06 e E08, RN-22 e RN-13) e na E20 (contrato de todos os requests); não na E21 e na E22; (g) a SPEC §8.3 dizia que `HousekeepingTaskCompleted` é consumido por UHs para mudar o status; desde o M2, o status muda pela chamada síncrona da governança à `RoomsApi` (ARCHITECTURE §6.2, H3). A linha foi corrigida na mesma revisão. | (a) e (b) só mudam a organização; (c) é contrato novo, mas só substitui um 500; (d) recusa um formato leniente que nenhum cliente em produção usa. | E04, E05, E06, E19, E20, E21, E22 |

**Notas herdadas do M2** (sincronização do roadmap do M2 com o código do M1, 2026-10-15; conferir de novo na sincronização deste marco):

- **E07, OOO pela manutenção (`blockRoom`):** a API pública de OOO de `rooms` (M2 E03) não confere permissão; o caso de uso da manutenção faz o próprio step-up (`authorization.requireOrApproval` com a permissão sensível de OOO de `rooms`) antes de chamá-la. O alerta de reserva alocada no período é de `reservations`, ao escutar `OutOfOrderChanged` (M4 E05/E10); `rooms` não conhece reservas.
- **Rotinas de checklist:** o listener de `BusinessDateAdvanced` da M2 E07 gera uma execução por rotina e data, mas em produção as rotinas só passam a ser geradas quando o `endofday` deste marco avançar a data operacional; o gate deste marco confere isso ponta a ponta.

## Definição global de pronto

- [ ] Tarefas do dia são geradas automaticamente (saídas, permanências, chegadas, faxinas programadas e avulsas), distribuídas por sugestão de créditos (com as chegadas e o early check-in primeiro, pela hora prevista de chegada da reserva), publicadas, executadas online e offline (com o conflito RN-22 registrado e sinalizado) e inspecionadas, com o status da UH seguindo §8.4.
- [ ] Achados e perdidos e danos são registrados; dano aprovado é lançado na conta do hóspede.
- [ ] OS corretivas são abertas (manual, recepção, governança ou checklist), executadas online e offline, colocam a UH em OOO/OOS e a devolvem como Suja ao concluir.
- [ ] O fechamento do dia roda no horário configurado e manualmente com step-up, executa as etapas de RF-FEC-02 na ordem, retoma de onde parou sem duplicar lançamentos e avança a data operacional publicando `BusinessDateAdvanced`.
- [ ] Painel do dia, relatórios operacionais de governança e manutenção, exportação CSV/XLSX, relatório de pendências e PDFs arquivados por data estão disponíveis.
- [ ] Cenário ponta a ponta da Fase 1 passa: reserva → check-in → diária lançada no fechamento → limpeza da permanência → check-out → limpeza de saída.
- [ ] No-show do fechamento gera submissão na FNRH; a falha ou a ausência de um fechamento dispara a regra de alerta (a entrega é da M8 E18).
- [ ] A manutenção herdada do M5 está entregue: a ficha mostra os rótulos de motivo de viagem e meio de transporte, `frontdesk.stays.checkin` descreve a ficha, as escritas de restrição e mesclagem do `guests` devolvem o corpo mínimo sem `guests.profiles.read` e as dívidas de teste da M5 E01 e da M5 E08 estão resolvidas.
- [x] A manutenção transversal da E18 está entregue: um cursor com chave de data ou de instante fora do alcance do PostgreSQL dá `400 INVALID_CURSOR`; uma exceção *checked* desfaz a transação e a escrita; datas e instantes fora do ISO-8601 são recusados no campo; e os jobs logam só a classe da exceção.
- [x] A manutenção transversal da E20 e da E21 está entregue: a entrada é estrita também em query string, caminho, `LocalTime`, número inteiro e UUID, e fora do alcance do banco no corpo; o contrato marca `required` nos requests; deadlock e `lock_timeout` dão `409 CONCURRENT_UPDATE`; nenhum job loga a mensagem da exceção.
- [ ] Verificação de módulos, build, testes e verificação do contrato passam.

## Protocolo de execução

Vale para toda execução deste roadmap. O planejamento e a execução de cada etapa são feitos pelo plugin superpowers; este protocolo diz como encadear as etapas e manter o roadmap em dia.

**Papéis**

| Papel | Soul | Usa |
|---|---|---|
| Orquestrador | `orquestrador` (a sessão principal, iniciada com `claude --agent orquestrador`) | este protocolo; atualiza tags e handoffs; roda a verificação mecânica dos testes |
| Planejador | `orquestrador` | `superpowers:writing-plans` |
| Implementador | `desenvolvedor`, subagente por tarefa; tarefas independentes em ondas paralelas | `superpowers:subagent-driven-development`, prompt implementer |
| Testador | `testador`, subagente nas tarefas `Risco: alto`, nas lacunas da revisão e uma vez por etapa | testes, sem tocar em código de produção |
| Revisor de plano | `revisor-arquitetura`, subagente novo por etapa, modelo mais capaz | revisão do plano antes da execução |
| Revisor de tarefa | `revisor-seguranca`, subagente novo por tarefa | revisão da `subagent-driven-development` |
| Revisor final | `revisor-seguranca`, subagente novo, modelo mais capaz | revisão final da `subagent-driven-development` |
| Captura no brain | `brain-escriba` | publica a captura de atualização em `00 Inbox/` |

Souls: este protocolo exige `orquestrador`, `desenvolvedor`, `testador`, `revisor-seguranca` e `revisor-arquitetura`. Não há fallback: se a sessão não roda como `orquestrador` ou alguma dessas souls não está instalada, pare e diga ao usuário para configurar a máquina (skill `configurar-maquina`) ou iniciar com `claude --agent orquestrador`. O caminho inline (`superpowers:executing-plans`) não vale aqui.

**Branch**

- Toda execução acontece no worktree/branch `roadmap/m6-operacao-fechamento`, criado ou retomado com `superpowers:using-git-worktrees`. Nunca execute na branch principal (`main`).
- Se a branch já existir com trabalho não integrado, continue nela. Se o lote anterior já foi integrado, crie a branch de novo a partir da `main` atualizada.
- Este roadmap é atualizado dentro da branch: tags e handoffs viajam com o código.

**Por etapa**

1. **Escolha:** a primeira etapa, na ordem do documento, marcada `#pendente` e com todas as dependências `#concluida`. Etapas `#bloqueada` e as que dependem delas ficam para trás.
2. **Conferência:** leia a seção da etapa, os handoffs das dependências e o código que eles citam. Se o código divergiu do que a etapa supõe, pare e relate.
3. **Início:** troque a tag para `#em-andamento` (índice e título).
4. **Plano:** use `superpowers:writing-plans`. A spec é a seção desta etapa mais os documentos citados em "Contexto confirmado"; as "Restrições e invariantes" entram como Global Constraints. Salve em `docs/superpowers/plans/AAAA-MM-DD-m6-operacao-fechamento-eNN.md` (AAAA-MM-DD = a data do dia). Antes de executar, o plano passa pela revisão de arquitetura do `orquestrador` (`revisor-arquitetura`); as regras (rodadas, veredito, seção no plano) vivem só nele. A execução já está definida (abaixo): não pergunte ao usuário o método, apenas siga.
5. **Execução:** use `superpowers:subagent-driven-development` com esse plano, executado pelas souls conforme o ciclo por tarefa, a verificação mecânica dos testes e a revisão final da etapa definidos no `orquestrador` (as regras de encaixe vivem só nele). Não invoque `superpowers:finishing-a-development-branch` ao fim da etapa: ele roda uma vez, no fim do lote.
6. **Fechamento:** publique a captura de atualização do brain (bloco "Second Brain"). Marque `#concluida` (índice e título) só com a revisão final limpa e cada critério de aceite verificado com evidência. Diante de impedimento real, marque `#bloqueada` e preencha "Bloqueios" com causa, evidência, impacto e ação de desbloqueio. Preencha "Evidências e handoff". Rulings do ledger que mudam contrato, escopo, segurança, dados ou algo caro de desfazer vão para "Decisões para o usuário".

**Second Brain**

Vale se o `CLAUDE.md` tiver a seção "Conhecimento do projeto no Second Brain"; senão, ignore este bloco.

- O contexto da sessão precisa trazer a linha `BRAIN: carregado (ZenyHotelManager)`, injetada pelo hook. Sem ela, pare e relate.
- Na conferência de cada etapa, leia as notas de tema do brain ligadas a ela (pelo hub `ZenyHotelManager`) e as capturas pendentes do projeto que tratem do assunto: são a referência técnica do projeto. Num handoff antigo, "seção X do `CLAUDE.md`" é a nota de tema correspondente.
- No fechamento de cada etapa que mudou algo descrito no brain (inclusive o estado do roadmap), o orquestrador manda o `brain-escriba` publicar em `00 Inbox/` do vault a captura `ZenyHotelManager - atualização m6-operacao-fechamento Eyy`, com o texto novo ou alterado de cada nota afetada e qual nota ele substitui ou complementa; o contrato de agente externo do vault fica com o `brain-escriba`. Nunca edite notas do brain nem registre referência técnica no `CLAUDE.md`.

**Paradas**

Qualquer outra necessidade de intervenção é decidida pelo orquestrador pela opção recomendada, depois de avaliar prós e contras, com a ruling registrada; só decisão de arquitetura ou system design espera o usuário (regras em "Decisões" do `orquestrador`). O lote para ao concluir uma etapa com **Revisão humana: sim**, diante de decisão de arquitetura sem a qual nada mais anda, nas paradas que ficam com o usuário (push, deploy, publicação, instalação na máquina, apagar trabalho fora da etapa, segredo exposto), quando não resta etapa elegível e ao atingir o limite de etapas do prompt. Etapa que fica `#bloqueada` não para o lote: o orquestrador segue para a próxima elegível que não depende dela.

**Fim do lote**

1. Use `superpowers:finishing-a-development-branch` com a integração já decidida: merge local na `main`, conforme o passo de fechamento do `orquestrador` (branch atualizada e com gate verde, `merge --no-ff`, repositório limpo e pronto para o próximo orquestrador). Push e deploy ficam com o usuário: o relatório termina com o comando `git push origin main` pronto.
2. Liste as "Decisões para o usuário" acumuladas, que já foram tomadas pela opção recomendada, com prós e contras, para revisão. As de arquitetura que esperam o usuário vão como entrevista: perguntas de múltipla escolha, cada uma com uma opção recomendada e justificada (no Claude Code, `AskUserQuestion` com a recomendada em primeiro lugar e o rótulo "(Recomendado)"). Registre as respostas no handoff das etapas e em "Hipóteses e decisões em aberto".

## Prompt de execução

```text
Execute o roadmap docs/roadmaps/roadmap-m6-operacao-fechamento.md deste repositório seguindo a seção "Protocolo de execução" dele, com o plugin superpowers.

Limite desta execução: no máximo 3 etapas concluídas.

1. Confira a pré-condição do protocolo: esta sessão roda como orquestrador e as souls desenvolvedor, testador, revisor-seguranca e revisor-arquitetura estão disponíveis; se não, pare e diga como resolver. Leia CLAUDE.md (e AGENTS.md, se existir) e, no roadmap, o cabeçalho inteiro, o protocolo e o índice. Se o CLAUDE.md exigir o Second Brain, confira a linha BRAIN: carregado no contexto e leia o hub. Rode git status e git branch -a.
2. Se alguma etapa estiver #em-andamento, retome-a a partir do ledger do superpowers e do handoff; não recomece tarefas já commitadas.
3. Crie ou retome o worktree/branch roadmap/m6-operacao-fechamento com superpowers:using-git-worktrees. Não trabalhe na branch principal.
4. Repita, uma etapa por vez: escolha a próxima etapa elegível, marque #em-andamento, planeje com superpowers:writing-plans (spec = seção da etapa + documentos citados), execute com superpowers:subagent-driven-development, feche a etapa (com a captura de atualização do brain) e preencha o handoff conforme o protocolo.
5. Pare nas paradas do protocolo ou ao atingir o limite.
6. No fim, rode superpowers:finishing-a-development-branch com merge local na main, conforme o fechamento do orquestrador: tudo commitado, pronto para git push origin main e o repositório pronto para o próximo orquestrador. Depois, liste as decisões tomadas e apresente como entrevista com opção recomendada só as de arquitetura que esperam por mim.

Qualquer necessidade de intervenção: escolha a opção recomendada depois de uma avaliação crítica dos prós e contras e registre a ruling; intervenção humana só para decisão de arquitetura ou system design. Nunca faça push ou deploy. Não altere tags de etapas que não executou.

Ao parar, responda com: etapas concluídas e o resultado da revisão final de cada uma, a etapa atual e seu estado, o motivo da parada, as etapas #bloqueada com a ação de desbloqueio, a branch e os commits do lote, as capturas do brain publicadas e o próximo passo sugerido.
```

## Índice de etapas

**Fechamento do dia**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E10 | Motor do fechamento do dia | #concluida | — | `EndOfDayRun` com etapas persistidas, retomada, ShedLock, gatilhos automático e manual, métricas e alerta |
| E11 | Etapas financeiras e operacionais do fechamento | #concluida | E10 | No-show, diárias, bloqueios, pré-reservas, UHs Sujas, avanço da data, pendências |
| E14 | No-show na FNRH | #concluida | E11 | Operação nova na porta da FNRH, enviada pela fila de submissões |

**Manutenção herdada do M5**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E15 | Rótulos da FNRH na ficha e descrição da permissão do check-in | #concluida | — | Porta do `frontdesk` implementada pelo `fnrh` com os rótulos de motivo de viagem e meio de transporte; `frontdesk.stays.checkin` descreve a ficha |
| E16 | Dívidas de teste herdadas do M5 | #concluida | — | Teste de índices das listas por situação endurecido e a cópia duplicada de `FnrhSubmissionDismissCompat.kt` removida |
| E17 | Corpo mínimo nas escritas de restrição e mesclagem do `guests` | #concluida | — | `restrict`, `lift-restriction` e `merge` só devolvem o perfil inteiro a quem tem `guests.profiles.read` |

**Manutenção transversal**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E18 | Faixa do cursor, rollback em toda exceção, datas estritas e log dos jobs | #concluida | — | Regras do `shared` que valem para todos os módulos: `400 INVALID_CURSOR` fora do alcance do banco, `rollbackOn = ALL_EXCEPTIONS`, Jackson estrito em `date`/`date-time` e tratador de erro do agendador que loga só a classe |
| E20 | Entrada estrita no corpo, na query e no caminho, e `required` no contrato | #concluida | — | Datas e instantes fora do alcance do banco recusados no corpo; query e caminho só ISO; `LocalTime`, inteiro e UUID estritos; `required` nos schemas de request |
| E21 | Erros de trava e logs restantes | #concluida | — | `409 CONCURRENT_UPDATE` para deadlock e `lock_timeout`; quatro jobs sem mensagem no log; filtro no lugar do `OFF` do ShedLock; `JOSEException` capturada no FCM |

**Governança**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E01 | Tipos de limpeza, créditos e faxinas programadas | #concluida | — | Configuração de `CleaningType`, `CreditRule` e `ScheduledCleaning` |
| E02 | Geração automática de tarefas | #concluida | E01, E11 | Tarefas por saída, permanência, chegada, faxina programada e avulsa |
| E03 | Distribuição sugerida e publicação | #concluida | E02 | `AssignmentPlan` balanceado por créditos e escala, publicado com aviso |
| E04 | Máquina de estados, ações online e conclusão da tarefa | #concluida | E03 | Transições da §8.4, ações online com escopo `OWN`, conclusão com checklist, UH, observação de ocupação, `HousekeepingTaskCompleted` e `recordDone` |
| E19 | Comandos offline, conflito RN-22 e feed da camareira | #concluida | E04 | Comandos `housekeeping.<verbo>-task`, histórico de posse, conflito registrado e sinalizado, fonte `housekeeping-tasks` sem dado de hóspede |
| E05 | Inspeção e liberação da UH | #em-andamento | E19 | Aprovação/reprovação, UH Inspecionada, aviso de UH pronta |
| E06 | Achados e perdidos e danos | #pendente | E19 | `LostAndFoundItem` com prazo de guarda, `DamageReport` lançado na conta |
| E22 | Hora prevista de chegada e prioridade do early check-in | #pendente | E03, E20 | Hora prevista opcional na reserva; a sugestão põe o early e as chegadas primeiro |

**Manutenção**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E07 | Ordens de serviço corretivas e OOO/OOS | #pendente | — | `WorkOrder` com estados, custos, anexos e bloqueio da UH |
| E08 | OS por checklist e app offline da manutenção | #pendente | E07 | Abertura por `ChecklistCompleted` e comandos offline da OS |

**Relatórios**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E09 | Relatórios operacionais e painel do dia | #pendente | E05, E08 | Views `report_*`, painel, relatórios, CSV/XLSX, `DailyStatistics`, arquivo de PDFs |
| E13 | Snapshot e arquivo do fechamento | #pendente | E09, E11 | Estatísticas e PDFs da data gravados pelo fechamento, antes do avanço |

**Gate**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E12 | Gate do marco M6 e fim da Fase 1 | #pendente | E06, E13, E14, E15, E16, E17, E18, E20, E21, E22 | Definição global de pronto e cenário ponta a ponta da Fase 1 |

---

## E10 — Motor do fechamento do dia — #concluida

**Objetivo:** criar o módulo `endofday` com o motor do fechamento (RF-FEC-01, RF-FEC-03, RN-21, ARCHITECTURE §13): `EndOfDayRun` com etapas persistidas, execução ordenada, retomada da etapa pendente, uma execução por data e nunca em paralelo, disparo automático no horário configurado em `hotel` (fuso do hotel) e manual com step-up.

**Por que agora:** é a primeira etapa do marco porque as etapas de negócio (E11) se encaixam neste motor, e o fechamento é o que grava dinheiro imutável (RN-03) e faz a data andar. Separá-lo permite testar idempotência e retomada com etapas falsas antes de mexer em dinheiro.

**Dependências:** nenhuma dentro deste roadmap; exige o gate de M5 `#concluida`. Independente de E01–E09 (conflito esperado em `api/openapi.yaml` se rodarem em branches separadas).

**Escopo:** pacote `endofday` (a criar):

- `EndOfDayRun`, `EndOfDayStep`, `EndOfDayIssue` (SPEC §8.2.9), com a data por valor: a data operacional continua só em `hotel` (`BusinessDay`), e "data fechada" deriva da execução concluída; restrição única por data de execução concluída; lock por ShedLock e lock de banco contra execução paralela.
- Interface interna de etapa (nome, ordem, `execute(date)` idempotente) e registro ordenado; falha numa etapa interrompe e deixa a execução retomável.
- Gatilho automático por `@Scheduled` no horário de RF-CFG-04, no fuso do hotel; `POST /api/v1/end-of-day/runs` manual, ação sensível com step-up (RF-ACS-08); `POST …/runs/{id}/resume`; `GET` de execução, etapas e pendências.
- Publicação do andamento no SSE e aviso de pendências via `notifications` (push e painel) para quem acompanha o fechamento (H8, RF-NOT-01).
- Métricas de duração e de falha do fechamento e alerta de fechamento que falhou ou não rodou no horário (ARCHITECTURE §16).

**Fora de escopo:** as etapas de negócio (E11).

**Critérios de aceite:**

- [x] Execução com etapas falsas percorre todas na ordem e registra início, fim e resultado de cada uma.
- [x] Falha forçada na terceira etapa deixa a execução retomável; `POST …/resume` continua da terceira sem reexecutar as duas primeiras (contador no teste).
- [x] Dois disparos simultâneos para a mesma data resultam em uma única execução.
- [x] Nova execução para data já fechada → `409` com `code` estável.
- [x] `POST /api/v1/end-of-day/runs` sem permissão → `403` `APPROVAL_REQUIRED`; com aprovação → executa e audita solicitante e aprovador.
- [x] O gatilho automático calcula o horário no fuso do hotel (teste com relógio fixo e fuso diferente de UTC).
- [x] Andamento publicado no SSE.
- [x] Uma `EndOfDayIssue` registrada gera notificação de pendência do fechamento via `notifications` para quem tem a permissão de acompanhar o fechamento (RF-NOT-01).
- [x] A duração e a falha do fechamento aparecem em `/actuator/prometheus`, e a regra de alerta está em `docker/prometheus/alerts.yaml`; a entrega a uma pessoa é da M8 E18.
- [x] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato (`./gradlew checkOpenApi`, tabela do `CLAUDE.md`) → sucesso.

**Revisão humana:** sim

**Notas para o plano:** H8; SPEC §5.13, RN-21 e §8.2.9 e ARCHITECTURE §13; etapas falsas nos testes (as de negócio são da E11). O tipo `notifications.end-of-day-pending` já existe no catálogo do `notifications` e é obrigatório no painel (nota `ZenyHotelManager - Módulo notifications`). Convenções de jobs (nota `ZenyHotelManager - Eventos e jobs`): método com `@SchedulerLock` não devolve tipo primitivo; o ShedLock trava pela hora do banco. Nos testes (nota `ZenyHotelManager - Testes de integração`), os jobs ficam adiados no `@IntegrationTest` e são chamados direto, o relógio avança pelo `MutableClockConfiguration` (devolvido no fim, porque o contexto é compartilhado) e a JVM roda em `America/Sao_Paulo`. Armadilhas do formato antigo: duas execuções simultâneas para a mesma data; retomada que reexecuta etapa concluída; gatilho calculado em UTC; fechamento manual sem step-up; testar a retomada depois de uma "queda" simulada. **Caminho próprio para mudar a data (H10, handoff da M5 E12):** esta etapa cria o apoio de testes do fechamento, um banco ou contexto isolado onde a execução avança a data e age sobre todas as reservas sem afetar os demais testes. E11, E13, E14 e o gate o reutilizam. Com etapas falsas, a E10 ainda pode rodar no banco compartilhado se nenhuma etapa avança a data.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Restart no meio da execução (ARCHITECTURE §13): o estado persistido por etapa e o ShedLock com tempo máximo cobrem; testar retomada após "queda" simulada.

**Evidências e handoff:**

- Plano: `docs/superpowers/plans/2026-11-12-m6-operacao-fechamento-e10.md`, 14 tarefas, todas `Risco: alto`, executadas em série (máquina de 14 GB). Revisão de arquitetura: aprovada na rodada 2 (`revisor-arquitetura`, opus; rodadas 0 e 1 bloqueadas com 12 e 4 Importantes, todos corrigidos no plano).
- Commits: branch `roadmap/m6-operacao-fechamento`, `7ae1a3b` (plano) a `8b986e7`, sobre `b23958e` da `main`; cada tarefa com desenvolvedor → testador → verificação mecânica → `revisor-seguranca`; três tarefas tiveram teste vermelho do testador corrigido no código (Tasks 9 e 11: data/instante fora do alcance do PostgreSQL davam 500) e quatro tiveram uma rodada de correção pela revisão (Tasks 4, 5, 12 e a onda final).
- Revisão final: `revisor-seguranca` (opus) sobre `b23958e..0399709` — aprovado com ressalvas, 0 Critical; onda única de correção (`f17e89a` tira do git um relatório de processo e ignora `.superpowers/`; `8b986e7` prova pela API, sem etapas, o `409 END_OF_DAY_NOT_CONFIGURED` e o `NO_STEPS` do gatilho), re-revisão limpa. Scanner `osv-scanner` 2.7.0 nos três lockfiles: 0 Critical/High; 5 Medium/Low pré-existentes (lockfiles iguais aos da `main`). `check_test_integrity.py --base b23958e`: 6 apontamentos `DEV_TESTE_DO_TESTADOR`, todos autorizados por ruling e julgados justificados. oasdiff: nenhuma mudança `ERR` em `/api/v1`.
- Critérios verificados: (1) ordem e registro — `EndOfDayEngineTest`; (2) falha na 3ª e retomada sem reexecutar — `EndOfDayEngineTest`, `EndOfDayResumeApiTest` (contadores `1,1,2,1`); (3) disparos simultâneos — `EndOfDayRunsApiTest`, `EndOfDayTriggerTest`, `EndOfDayRunTransitionsTest`; (4) data fechada → `409 END_OF_DAY_ALREADY_CLOSED` — `EndOfDayRunsApiTest`; (5) `403 APPROVAL_REQUIRED` e trilha com solicitante e aprovador — `EndOfDayRunsApiTest`, `EndOfDayResumeApiTest`; (6) horário no fuso do hotel — `EndOfDayScheduleTest` (Manaus, Tóquio, horário de verão) e `EndOfDayTriggerTest`; (7) SSE — `EndOfDayProgressTest`; (8) notificação de pendência — `EndOfDayIssuesTest`; (9) métricas em `/actuator/prometheus` e regras `ZenyEndOfDayFailed`, `ZenyEndOfDayNotRun`, `ZenyEndOfDayMetricsUnavailable` — `EndOfDayMetricsTest`, `promtool` 8 regras OK; (10) build, testes e contrato — gate abaixo.
- Gate: `./gradlew build` em `35b7d00` → BUILD SUCCESSFUL, 3604 testes, 0 falhas; gate final no HEAD integrado (`8b986e7`, só documentação depois dele) → BUILD SUCCESSFUL in 23m 34s, 3606 testes, 0 falhas (inclui `ModularityTest`, ArchUnit, detekt, ktlint, `checkOpenApi`, `koverVerify`).
- Decisões para o usuário (tomadas pela opção recomendada, revisáveis): (a) unicidade total por data (`run_business_date_uk`) com reconciliação das etapas na retomada, mais estrita que "única por data de execução concluída" do escopo; (b) `POST /runs` `201` síncrono com espera limitada (`manual-wait` 30 s) e a execução no estado em que estiver, em vez de `202` assíncrono — contrato público; (c) janela de 24 h do automático, fora dela só manual com aviso (protege RN-03/RN-15 e a FNRH da E14); (d) horário devido: antes de 12:00 → madrugada seguinte, 12:00 ou depois → o próprio dia; (e) `FAILED` só pela retomada manual, sem novas tentativas automáticas; (f) trocar o fuso ou o `endOfDayTime` recalcula o horário devido e pode reabrir a janela de uma data atrasada; (g) H10: banco de teste próprio `zeny_endofday` no mesmo container. Transversais encontradas fora da etapa: `rollbackOn` global do Spring (exceção checked confirma a transação em outros casos de uso); o Jackson aceita número e data-hora em campos `format: date`; `PageCursor.instant` sem faixa em 9 consultas de outros módulos (provável 500 com cursor de instante extremo); o ShedLock com o banco fora loga a mensagem do JDBC.
- Decisões do usuário (entrevista de 2026-11-13, todas pela opção recomendada): (1) `POST /runs` continua `201` síncrono com a execução no estado em que estiver; (2) continua uma execução por data com reconciliação na retomada (decisão (a) acima confirmada); (3) os transversais (faixa do `PageCursor.instant` no `shared`, `rollbackOn = ALL_EXCEPTIONS`, Jackson estrito em `format: date`, log do ShedLock) viram uma etapa de manutenção do M6, a acrescentar pelo `arquiteto` antes do gate; (4) na E11, o gatilho passa a só disparar o motor e voltar (espera curta), sem mudar o pool do agendador do Spring. Ver H12.
- Notas para a próxima etapa (E11, obrigatórias no plano dela): o contrato de etapa é o KDoc de `EndOfDayStepHandler` (idempotência pela data, item em `REQUIRES_NEW`, posições de 100 em 100, nomes estáveis, nada de dado pessoal na `message`, que a Recepção e o Financeiro leem); o `tick()` espera o motor por até `lock-at-most-for` e o agendador do Spring tem uma thread por padrão (decidir pool ou espera curta); `resumeManually` trava a linha sem `lock_timeout`; trocar a captura larga de `IllegalStateException` do `fail` no motor por um tipo próprio; teste de unidade do keep-alive antes de etapas longas (E13); decidir se as pendências levantadas pelas etapas ganham trilha própria; agrupar avisos por execução e etapa (uma notificação por pendência por usuário hoje); rever `EndOfDayTestConfiguration` (etapas falsas 10–40) e o `ModularityTest` do `endofday` (exige igualdade exata das dependências); o banco isolado acumula execuções de outros testes e a data só avança (afirmar só sobre os próprios dados); a próxima migração precisa de versão posterior a `V2026_11_12_1001`. Captura do brain: `00 Inbox/2026-11-13 - ZenyHotelManager - atualização m6-operacao-fechamento E10 - 1e4f709b.md`.

---

## E11 — Etapas financeiras e operacionais do fechamento — #concluida

**Objetivo:** implementar no motor de E10, na ordem de RF-FEC-02, as etapas que não dependem de `reporting`: no-show com multa pela política copiada na reserva (RN-15), alerta de saídas pendentes, lançamento de diárias, pacotes e taxas, liberação de bloqueios vencidos (RN-16), expiração de pré-reservas (RN-23), renovação de pré-autorizações (não aplicável até M7), UHs ocupadas → Sujo (RN-09) e avanço da data operacional via `hotel` (que publica `BusinessDateAdvanced`).

**Por que agora:** é o que faz a data operacional andar e as diárias entrarem nas contas; sem isso o hotel não opera. Lançamento é imutável (RN-03), então esta é a parte mais cara de errar do marco e vem antes das demais. As rotinas de checklist do M2 passam a rodar em produção a partir daqui.

**Dependências:** E10.

**Escopo:** `endofday`, chamando só `hotel`, `rooms`, `reservations`, `billing`, `payments` e `notifications`, e o que falta nas APIs públicas de quem é dono do dado:

- `reservations`: transição para `NO_SHOW` e as consultas por data, que hoje só existem nos endpoints (H4); evento de no-show com `performedBy`, publicado pelo outbox.
- Cada etapa idempotente por chave natural; os lançamentos usam a mesma chave do check-out (`NewPosting.sourceReference`, H5). `EndOfDayIssue` para saídas pendentes e falhas não fatais.
- Prova de RF-FEC-05: nada é lançado em data fechada, inclusive com um lançamento concorrente ao avanço da data; correção entra na data corrente com referência ao original.
- Etapa de pré-autorizações registrada como "não aplicável" até M7, com nota na captura de atualização do brain.
- Avanço da data por `hotel`, como última etapa desta entrega.

**Fora de escopo:** snapshot e PDFs (E13); no-show na FNRH (E14); geração das tarefas do novo dia (E02, que escuta o evento); pendências de notas rejeitadas e ARI (M7, M8).

**Critérios de aceite:**

- [x] Só a reserva confirmada com chegada pendente vira `NO_SHOW`, com a multa da política copiada nela (RN-15), e a UH alocada é solta no mesmo commit.
- [x] Saída pendente gera `EndOfDayIssue` e o check-out não é feito automaticamente.
- [x] Diárias, pacotes e taxas das reservas hospedadas são lançados uma única vez por reserva e data, mesmo com a etapa repetida, e o lançamento do check-out e o do fechamento na mesma data não duplicam (teste com os dois caminhos).
- [x] Pacotes inclusos saem à parte da hospedagem, no código de cada um, sem taxa de serviço, como no check-out (decisão M5 E04 a).
- [x] Falha forçada no meio do lançamento de diárias e retomada não duplicam nenhum lançamento.
- [x] Bloqueios vencidos devolvem as UHs não utilizadas ao inventário; pré-reservas vencidas são expiradas.
- [x] UHs ocupadas passam a Sujo; a etapa de pré-autorizações registra "não aplicável".
- [x] A data operacional avança só depois de todas as etapas anteriores concluídas, e o evento publicado gera as rotinas de checklist do novo dia (teste).
- [x] Lançamento depois do avanço cai na data nova, e nenhum lançamento fica numa data fechada, com o caminho concorrente testado (RF-FEC-05).
- [x] `endofday` depende só dos módulos permitidos (`ModularityTest`); build, testes e contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato (`./gradlew checkOpenApi`, tabela do `CLAUDE.md`) → sucesso.

**Revisão humana:** sim

**Notas para o plano:** H4 e H5; RF-FEC-02, RF-FEC-05, RN-09, RN-15, RN-16 e RN-23; ler o handoff de E10 e os de M5 E04 e E05. A chave da diária já está definida no comentário de `BillingApi.post`: `reservations.reservation:<id>:<data>:room` ou `…:package:<packageId>`. `post` não recebe data: grava na data operacional corrente, então RF-FEC-05 pode estar coberto por construção, mas a etapa precisa provar a corrida com o avanço. Pendências que marcos anteriores deixaram endereçadas a este fechamento, registradas nas notas do brain (conferir na sincronização): só o fechamento chama `ReservationsApi.releaseUnused` para os ids de `blocksDueForRelease()` (M4 E11); a reserva `NO_SHOW` precisa soltar a UH alocada no mesmo commit (gatilho adiado `reservation_assignment_released`, M4 E10); a multa usa o código `NO_SHOW_FEE` (M4 E08); as hospedadas com a saída passada nunca têm check-out automático (RF-FEC-02); o `ProvisionalExpiryJob` já expira pré-reservas a cada 5 min (M4 E09), e a etapa do fechamento convive com ele; turnos de caixa esquecidos abertos ficam para o fechamento listar (M3 E07); `RoomsApi.markDirty(END_OF_DAY)` é a mudança prevista para as ocupadas (M2 E02); `BillingApi.cardPreAuthorizationsExpiring` serve à renovação do M7; a tarifa de grupo não aplica os pacotes do plano (M4 E11: decidir aqui se o bloqueio leva pacotes no lançamento diário). Notas `ZenyHotelManager - Módulo reservations (ciclo de vida, alocação, grupos e busca)`, `ZenyHotelManager - Módulo billing (contas, roteamento e taxa de serviço)` e `ZenyHotelManager - Módulos hotel e staff` (`HotelApi.advanceBusinessDate(expectedCurrent)` é o único caminho de avanço). Nos testes, as datas partem da `HotelApi.currentBusinessDate()`, porque outros testes avançam a data no banco compartilhado; o fechamento com as etapas reais roda no caminho próprio criado na E10 (H10), nunca no banco compartilhado. O check-out da M5 E04 já lança a diária e os pacotes do day use e do check-out no mesmo dia com a mesma chave: o teste "check-out e fechamento na mesma data" usa os dois caminhos reais. Armadilhas do formato antigo: diária lançada duas vezes após retomada; lançamento aceito em data fechada; multa pela política atual em vez da copiada na reserva; data avançada antes das etapas anteriores concluírem.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Lançamento de pacotes e taxa de turismo (RF-CFG-03) pode exigir dados de `rates` não expostos por `reservations`: se faltar, acrescentar à API de `reservations` (que depende de `rates`) em vez de `endofday` chamar `rates`.

**Rollback / migração:** lançamentos são imutáveis (RN-03); um erro de cálculo em produção se corrige por estorno na data corrente, nunca apagando linhas. Por isso os testes de idempotência são obrigatórios antes do uso real.

**Evidências e handoff:**

- Plano: `docs/superpowers/plans/2026-11-13-m6-operacao-fechamento-e11.md`, 20 tarefas em série, com duas execuções (lote parado a pedido do usuário depois da Task 13 e retomado em 2026-11-13/14). Revisão de arquitetura: aprovada com notas na rodada 1 (`revisor-arquitetura`, opus; rodada 0 bloqueada com 5 Importantes, todos corrigidos).
- Commits: branch `roadmap/m6-operacao-fechamento`, `3787f93` (início) a `6bd8073`, sobre `57eb5fa` da `main`. Cada tarefa passou por desenvolvedor → testador (as de risco alto) → verificação mecânica → `revisor-seguranca`. Na retomada, a revisão de duas tarefas teve achado. A Task 17 teve uma rodada de correção: um teste exigia `BUSY` exato com a espera curta do contexto. A Task 19 teve uma ruling: o teste de um testador da E10 esperava aviso das pendências gravadas em `REQUIRES_NEW` por uma etapa que falha; o brief e o contrato de etapa dizem que não há aviso, e o teste foi reescrito pelo testador.
- Entregue:
  - `reservations`: consultas por data, transição, evento e caso de uso de no-show, a regra única das cobranças da estadia (usada também pelo check-out do `frontdesk`), `dueProvisionals` e `expireProvisional`.
  - `hotel`: `lockBusinessDate` (`FOR SHARE`), usado pelo `billing` ao carimbar todo lançamento, o encerramento de conta e, desde a onda final, a abertura, os movimentos e o fechamento de caixa (ARCHITECTURE §11).
  - `EndOfDayItems` e as 8 etapas: `no-show` 100, `pending-departures` 200, `room-charges` 300, `block-release` 400, `provisional-expiry` 500, `card-pre-authorizations` 600 (`NOT_APPLICABLE` até o M7), `occupied-rooms-dirty` 700 e `advance-business-date` 1000 (com `statement_timeout` de 30 s e `lock_timeout` de 10 s).
  - Motor e gatilho: o gatilho só dispara o motor e volta (`zeny.endofday.trigger.wait`, padrão `PT5S`, até `PT1M`; H12); `StepAttemptLostException` no `fail`; retomada manual com `lock_timeout` de 5 s, que responde `409 END_OF_DAY_RUN_IN_PROGRESS` e desfaz tudo, inclusive o uso do token de aprovação; avisos de pendência agrupados por etapa e tipo.
  - Testes no banco isolado `zeny_endofday` (H10).
- Revisão final: `revisor-seguranca` (opus) sobre `57eb5fa..790749a`, "With fixes", 0 Critical de código.
  - O Critical de processo era a falta do gate. A revisão foi feita com evidência por pacote, porque o primeiro `./gradlew build` foi morto pelo sistema por falta de memória, com o Gradle do DesktopFront rodando ao mesmo tempo.
  - Os Importantes eram: o caixa sem a trava da data, o handoff desatualizado e duas sondas de espera frouxas nos testes ponta a ponta.
  - Onda única de correção:
    - `3abf960` (desenvolvedor): o caixa trava a data, mais KDocs;
    - `9eecc75..6bd8073` (testador): `CashierDateLockTest` (vermelho em `790749a`, verde com `3abf960`), esperas provadas por `pg_blocking_pids` com o prazo contado a partir do avanço `RUNNING`, testes "waits" com sondagem e no-show com exatamente um evento.
  - Re-revisão da onda: aprovada, sem quebra nova.
- Verificação:
  - **Scanner:** `osv-scanner` 2.7.0 com `-L` nos três lockfiles (426 + 74 + 0 pacotes): 0 Critical/High, 2 Medium e 3 Low, todos pré-existentes (`commons-lang3` só no buildscript, `logback-core`, `opentelemetry-api`). Os lockfiles e o catálogo não mudaram. `scan source -r .` no worktree responde "No package sources found", por isso o `-L`.
  - **Integridade:** `check_test_integrity.py --base 57eb5fa` deu exit 1, com apontamentos todos julgados justificados pela revisora: 4 `SEM_SOUL` em commits só de plano e roadmap do orquestrador anterior; 2 `DEV_TESTE_DO_TESTADOR` em `ea3424d`, por ruling da Task 6; e `ASSERCAO_REMOVIDA` no renome `StayCharges` → `CheckOutCharges`, com a asserção mantida. Com `--base 790749a` (onda): exit 0.
  - **oasdiff:** sem `ERR` em `/api/v1` (o contrato só ganhou `NO_SHOW` e `RESERVATION_NO_SHOW` em `x-extensible-enum`).
  - **Gate:** ver a linha seguinte.
- Gate: `./gradlew build` em `a9a9b39` → BUILD SUCCESSFUL in 27m 56s, 3877 testes, 0 falhas, 0 ignorados (inclui `ModularityTest`, ArchUnit, detekt, ktlint, `checkOpenApi` e `koverVerify`). O primeiro gate, em `6bd8073`, falhou num só teste (`EndOfDayIsolationTest`: o invariante da E10 exigia propriedades iguais nas duas anotações de teste, e a Task 17 pôs a espera curta do gatilho só no `@EndOfDayIntegrationTest`). O invariante passou a ser exato, "as de integração mais só a espera curta" (`a9a9b39`), com re-revisão limpa. Antes disso, um gate tinha sido morto pelo sistema por falta de memória.
- Critérios verificados:
  - (1) No-show com a multa copiada e a UH solta no mesmo commit: `ReservationNoShowTest`, `NoShowStepTest`, `EndOfDayRealRunTest`.
  - (2) Saída pendente: `PendingDeparturesStepTest`, `EndOfDayRealRunAttackTest` (uma pendência por fechamento, sem check-out).
  - (3) e (4) Diárias, pacotes e taxa uma vez por reserva e noite; inclusos sem taxa de serviço; check-out e fechamento na mesma data: `RoomChargesStepTest`, `NightChargesTest`.
  - (5) Falha no meio das diárias e retomada: `RoomChargesStepTest`, `EndOfDayRealRunTest`.
  - (6) Bloqueios e pré-reservas: `BlockReleaseStepTest`, `ProvisionalExpiryStepTest`, `EndOfDayStageGapsTest`.
  - (7) UHs ocupadas a Sujo e pré-autorizações "não aplicável": `RoomsAndPreAuthorizationStepsTest`.
  - (8) Avanço por último e rotinas de checklist do novo dia: `EndOfDayAdvanceStepTest`, `EndOfDayRealRunTest`.
  - (9) RF-FEC-05 com o caminho concorrente: `BusinessDateLockTest`, os testes de trava do `billing` das Tasks 7 e 8, `CashierDateLockTest`, `EndOfDayRealRunTest` e `EndOfDayRealRunAttackTest`.
  - (10) `ModularityTest` com igualdade exata no uso real do `endofday`; build e contrato: gate acima.
- Decisões para o usuário (tomadas pela opção recomendada e revisáveis; nenhuma é de arquitetura):
  - **(a)** A multa de no-show é a penalidade da política de cancelamento copiada na reserva, sem prazo e sem isenção automática. Para isentar, faz-se o estorno depois.
  - **(b)** A recusa da multa (conta encerrada ou `MULTA-NOSHOW` desativado) bloqueia o no-show inteiro, e a reserva vira pendência a cada fechamento. A UH e o inventário ficam presos nas noites seguintes até alguém agir. O mesmo vale para `ROOM_CHARGE_FAILED` por código desativado. Alternativa apontada pela revisão final: marcar o no-show e soltar a UH, com a falha da multa como pendência própria e lançamento manual.
  - **(c)** Taxa de turismo = valor vigente × (adultos + crianças), por noite, nunca no day use, e só na noite que o fechamento fecha. Custo aceito: as noites recuperadas ficam sem a taxa (check-in feito durante a execução, ou item que falhou e foi recuperado no fechamento seguinte), e o operador a lança à mão. Alternativa: guardar a taxa vigente por fechamento.
  - **(d)** O fechamento recupera as noites a partir da data do check-in. Resíduo: um check-in durante a execução seguido de check-out antecipado antes do próximo fechamento deixa a noite sem lançamento.
  - **(e)** Hóspede além da saída não ganha diária automática, só a pendência.
  - **(f)** Caixa: **corrigido na onda final**. A abertura, os movimentos e o fechamento de turno travam a data como os lançamentos.
  - **(h)** O `409` da retomada quando o ShedLock dá `Busy` (trava com outro processo) gasta o token de aprovação e confirma a retomada; o `409` do `lock_timeout` desfaz tudo e não gasta o token. Esse comportamento vem da E10.
  - **(i)** O avanço que esgota o prazo (30 s/10 s) fica `FAILED` até a retomada manual, e o hotel segue operando na data antiga até lá.
  - **(j)** Uma etapa que falha não avisa as pendências que gravou em `REQUIRES_NEW`. Elas ficam visíveis no `GET` de pendências, e só o `STEP_FAILED` é avisado (as etapas reais levantam pendência na transação da etapa).
- Notas para a próxima etapa (E14 e E13):
  - A E14 escuta `ReservationMarkedNoShow` (evento pelo outbox, sem dado pessoal).
  - A E13 encaixa `daily-statistics` (800) e `end-of-day-reports` (900) antes do avanço. O teste da corrida conta o prazo a partir do avanço `RUNNING`; com etapas longas, confira o *keep-alive* do motor antes (teste de unidade pedido na E10).
  - O relatório de pendências da RF-FEC-04 (contas com saldo, caixas abertos, FNRH, notas, ARI) fica com a E09/E13, pelas views `report_*`.
  - **Notas técnicas:**
    - (g) a detecção de no-show repetido na mesma data supõe relógio monotônico; avaliar um sinal explícito de "criado ou existente" no `BillingApi.post`, o que muda o contrato do `billing`;
    - a fila do executor do motor não tem limite (um pedido por rodada enquanto uma etapa está presa, todos sem efeito); sugestão: não reenfileirar enquanto houver `Future` pendente do mesmo `runId`;
    - `ReservationsApi.markNoShow`, `releaseUnused` e `expireProvisional` não conferem permissão; sugestão: regra ArchUnit "só o `endofday` chama";
    - os contextos `@IntegrationTest` compartilhados registram as etapas reais, inertes só pelo `initial-delay PT24H`; nenhum teste no banco compartilhado pode disparar o fechamento;
    - `provisional-expiry` processa até 1000 por execução;
    - os KDocs de `EndOfDayErrorCodes` (constante e exceção de `END_OF_DAY_RUN_IN_PROGRESS`) ainda dizem "outro executor";
    - o `EndOfDayModule` justifica `billing` também pelas pré-autorizações (só no M7);
    - na revisão final, os advisories pré-existentes de `opentelemetry-api` (≥ 1.67.0) e `commons-lang3` (≥ 3.18.0) ficaram para uma manutenção de dependências.
  - A próxima migração precisa de versão posterior a `V2026_11_13_1000`.
  - Máquina de 14 GB: um Gradle por vez nesta máquina e no DesktopFront.
- Captura do brain: `00 Inbox/2026-11-14 - ZenyHotelManager - atualização m6-operacao-fechamento E11 - df23f14d.md`.

---

## E14 — No-show na FNRH — #concluida

**Objetivo:** enviar à FNRH o no-show publicado pelo fechamento, pela fila de submissões do M5 E08, com a operação nova na porta `FnrhGateway` e no adaptador Serpro.

**Por que agora:** a M5 E08 deixou o no-show de fora (H15 do M5), porque o evento só nasce na E11 e o caminho individual diverge no PDF da API.

**Dependências:** E11. Exige a M5 E08, coberta pelo gate do M5.

**Escopo:** `fnrh`: listener do evento de no-show, operação na porta e no adaptador, testes de contrato com WireMock a partir de `docs/fnrh/api-fnrh-v2.4.md`.

**Fora de escopo:** a confirmação no ambiente real, que fica com a M5 E09.

**Critérios de aceite:**

- [x] O no-show gera uma única `FnrhSubmission`; o evento reentregue não duplica.
- [x] A submissão segue a ordem por reserva e só sai depois do registro da reserva.
- [x] O caminho escolhido (`/no-show` ou `/noshow`) e o motivo da escolha ficam no handoff e na lista do que a M5 E09 confirma; se a E09 já tiver fechado, o roteiro dela é reexecutado para o no-show, ou fica a pendência que o gate de inauguração (M8 E13) cobra.
- [x] Com o Serpro fora do ar, o fechamento conclui e a submissão fica pendente.
- [x] Build, testes, verificação do contrato e oasdiff sem `ERR` passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Revisão humana:** não

**Notas para o plano:** leia o handoff da M5 E07 ("Notas para a E09") e o da M5 E08. Se a M5 E09 já tiver sido feita, o no-show entra no roteiro de homologação dela e a divergência se resolve lá. Conferido em 2026-11-12: `FnrhOperation` (`fnrh/domain/FnrhSubmission.kt`) tem `rank` por operação para a ordem causal, e a coluna é conferida por `submission_operation_ck` (`fnrh/V2026_11_10_1000__create_fnrh_submissions.sql`). A operação nova pede migração no schema `fnrh` e, se a `fnrh.report_submissions` expõe a operação, a nota do contrato da view. No contrato, a operação é `x-extensible-enum`, então acrescentá-la não quebra. O teste do no-show roda no caminho próprio do fechamento (H10) e conta só as submissões da própria reserva no WireMock compartilhado (`FnrhTestServer`).

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Divergência no PDF sobre o caminho: sem credencial não há como confirmar; registrar a escolha e cobrá-la na homologação.

**Evidências e handoff:**

- Plano: `docs/superpowers/plans/2026-11-14-m6-operacao-fechamento-e14.md`, 6 tarefas, as 5 de código `Risco: alto`. Revisão de arquitetura: aprovada com notas na rodada 1 (`revisor-arquitetura`, opus; rodada 0 bloqueada com 2 Importantes: testes existentes que fixam a lista de operações e a nota do contrato da view, e a Task 1 grande demais; ambos corrigidos).
- Commits: branch `roadmap/m6-operacao-fechamento`, `31b9f10` (início) a `1b4a0bb`, sobre `3eeef98` da `main`. Cada tarefa passou por desenvolvedor → testador (as de risco alto) → verificação mecânica → `revisor-seguranca`. A Task 4 teve uma rodada de correção: a revisão achou que um `NO_SHOW` pendente saía mesmo depois da reativação da reserva, e o passo passou a agir pelo estado atual (`398d88a`, ruling registrada no plano em `e109fd0`).
- Entregue:
  - porta `FnrhGateway.markNoShow` → `POST /reservas/{reserva_id}/noshow` (lote, da reserva, sem corpo; a resposta não é lida);
  - `FnrhOperation.NO_SHOW` (posto 6), gravada pelo listener de `ReservationMarkedNoShow` com a chave `no-show:v<versão>`;
  - estado remoto `fnrh.remote_reservation.no_show`, nunca junto de `cancelled` (`remote_reservation_closed_ck`);
  - `UPDATE` de remota no-show com a reserva ativa aqui → `FAILED REMOTE_NO_SHOW` com alerta; `CANCEL` de remota no-show → sem chamada; no-show de reserva que não está mais `NO_SHOW` aqui → `SENT` sem chamada;
  - migração `fnrh/V2026_11_14_1000__add_fnrh_no_show.sql` (CHECKs com `NO_SHOW` e `REMOTE_NO_SHOW`, coluna e nota do contrato da `fnrh.report_submissions`); contrato com `NO_SHOW` e `REMOTE_NO_SHOW` em `x-extensible-enum`;
  - SPEC §8.2.17, README ("Envio à FNRH"), `docs/fnrh/api-fnrh-v2.4.md` e a lista da M5 E09.
- **Caminho escolhido (critério 3):** o no-show em lote da reserva, `POST /reservas/{reserva_id}/noshow`, e não um dos individuais (`/no-show` ou `/noshow`). O critério formulava a escolha entre os dois individuais; este é um terceiro caminho. Motivo: a reserva é registrada sem hóspedes e os hóspedes só entram no check-in (D-E07-2), então uma reserva no-show não tem `hospede_id` remoto; o caminho da reserva só precisa do id que já guardamos, é o único sem divergência no PDF e alcança os hóspedes de pré-check-in. A M5 E09 ainda está `#bloqueada`, e a lista dela ganhou o que a homologação confirma: o código de resposta numa reserva sem hóspedes, a situação da reserva depois do lote, a resposta à repetição numa reserva já `NOSHOW` e o lote numa reserva com hóspede `CHECKIN_REALIZADO` (check-in desfeito).
- Revisão final: `revisor-seguranca` (opus) sobre `3eeef98..1b4a0bb`, "With fixes", 0 Critical e nenhum Importante de código; os dois Importantes são decisões para o usuário (abaixo) e o resto foi documentação, feita neste fechamento.
- Verificação:
  - **Gate:** `./gradlew build` em `1b4a0bb` → BUILD SUCCESSFUL in 23m 19s, 3962 testes, 0 falhas, 0 ignorados (inclui `ModularityTest`, ArchUnit, detekt, ktlint, `checkOpenApi` e `koverVerify`). O gate anterior, em `6ad73ab`, falhou num teste pré-existente do M5 (`SerproFnrhGatewayRound3Test`): ele procurava `"100"` em toda a saída, e o PID da JVM de testes (`3010000`) contém `"100"`. O testador passou a procurar os valores só na mensagem do log (`1b4a0bb`, helper `testsupport/LogMessages.kt`).
  - **oasdiff:** `.github/scripts/check-api-breaking.sh main` → nenhuma mudança `ERR` em `/api/v1`.
  - **Scanner:** `osv-scanner` 2.7.0 com `-L` nos três lockfiles (426 + 74 + 0 pacotes): 0 Critical/High, 2 Medium e 3 Low, todos pré-existentes; lockfiles e catálogo sem mudança.
  - **Integridade:** `check_test_integrity.py --base 3eeef98` → exit 1, dois apontamentos julgados justificados pela revisão final: `DEV_TESTE_DO_TESTADOR` em `398d88a` (o teste do testador que caracterizava o envio depois da reativação foi invertido pela ruling da guarda) e `ASSERCAO_REMOVIDA` em `FnrhSubmissionTest` (postos `0..5` → `0..6`, D-E14-7).
- Critérios verificados:
  - (1) Uma única submissão e reentrega sem duplicar: `FnrhNoShowListenerTest`, `FnrhNoShowListenerAttackTest` (reentrega depois de `SENT`, `FAILED` e `DISMISSED`; 8 threads).
  - (2) Ordem por reserva, só depois do registro: `FnrhNoShowSendTest`, `FnrhNoShowQueueAttackTest`, `EndOfDayFnrhNoShowAttackTest`.
  - (3) Caminho e motivo: item acima, D-E14-1 do plano, `docs/fnrh/api-fnrh-v2.4.md` e a lista da M5 E09.
  - (4) Serpro fora do ar: `EndOfDayFnrhNoShowTest` (fechamento real `COMPLETED`, submissão `PENDING` com `attempts = 1` e `SERVER_ERROR`) e `EndOfDayFnrhNoShowAttackTest` (credenciais recusadas).
  - (5) Build, testes, contrato e oasdiff: gate e oasdiff acima.
- Decisões para o usuário (tomadas pela opção recomendada e revisáveis; nenhuma é de arquitetura):
  - **(a)** Caminho em lote da reserva (acima). Alternativa: o individual, que exigiria registrar hóspedes sem check-in.
  - **(b)** O passo não envia o no-show de reserva que não está mais `NO_SHOW` aqui (reativada antes do envio), pelo estado atual (D-E08-9), porque o no-show remoto não se desfaz. Resíduo: uma reativação entre a leitura e o `POST` ainda envia o no-show; o `UPDATE` seguinte fica `FAILED REMOTE_NO_SHOW` com alerta.
  - **(c)** O `POST …/noshow` é repetido no `retry` depois de `UNKNOWN_OUTCOME`, como o cancelamento da E08. Uma repetição recusada fica `FAILED` com alerta e a remota local continua sem `noShow`; depois do `dismiss`, um `UPDATE` de reativação faria o `PUT` numa reserva `NOSHOW`.
  - **(d)** `CHECK_IN` e `CHECK_OUT` sobre uma remota no-show (ou cancelada, já na E08) não têm guarda local: depois de reativação, `dismiss` e check-in, o envio cadastra a pessoa e recebe `4xx` (`FAILED` com alerta). Recomendação: numa manutenção do `fnrh`, `remote.closed` → `FailedLocally(REMOTE_CANCELLED | REMOTE_NO_SHOW)` sem chamada.
  - **(e)** Lacuna anterior na E08, achada pela revisão final: um `CANCEL_RESERVATION` pendente sai mesmo depois da reativação, e a remota é cancelada sem volta com a reserva ativa aqui. Recomendação: a mesma guarda pelo estado local (`status != CANCELLED` → sem chamada), numa etapa de manutenção do `fnrh` criada pelo `arquiteto` (a E16 é só de teste).
- Notas para a próxima etapa:
  - Para o `arquiteto`: o critério 3 desta etapa e o critério de aceite da M5 E09 ("o no-show, se o M6 já o publicar") pedem ajuste de texto; a ARCHITECTURE §3.1 lista `hotel` entre as dependências do `fnrh`, mas o `FnrhModule.kt` não o declara.
  - Para a E16: o helper `messagesOf` (`testsupport/LogMessages.kt`) só entende o log de console; com o log em ECS JSON (contextos com o profile `prod` na mesma JVM), uma linha inteira, com `pid` e `@timestamp`, ainda pode bater num valor curto. Capturar pelo appender do logger ou ler o campo `message` do JSON.
  - Dívidas de teste menores (revisões por tarefa): `FnrhRemoteId("")` é aceito por todas as operações (pré-existente; sugerido `require(isNotBlank())`); algumas asserções contam o `FnrhTestServer` inteiro em vez dos pedidos da própria reserva; provas de ausência com `await().during(1s)`; `FnrhAlertTestBase.submit` não limpa as linhas.
  - O teste do fechamento com a FNRH (`EndOfDayFnrhNoShowTest`) sobe um contexto Spring a mais no banco isolado; os no-shows dos outros testes do fechamento agora também gravam `NO_SHOW` (inofensivo com a FNRH desligada).
  - A próxima migração precisa de versão posterior a `V2026_11_14_1000`.
- Captura do brain: `00 Inbox/2026-11-14 - ZenyHotelManager - atualização m6-operacao-fechamento E14 - 998156dc.md`.

---

## E15 — Rótulos da FNRH na ficha e descrição da permissão do check-in — #concluida

**Objetivo:** a ficha de registro passa a mostrar o motivo de viagem e o meio de transporte de cada hóspede pelo rótulo legível dos domínios da FNRH, no lugar do código cru, e a permissão `frontdesk.stays.checkin` passa a dizer que também abre a ficha. Atende às decisões que o usuário tomou em 2026-11-12, ao revisar a M5 E11 (decisões (f) e (g) do lote E11 de `docs/roadmaps/roadmap-m5-recepcao-fnrh.md`; D-E11-10 e D-E11-11).

**Por que agora:** são pendências da revisão da M5 E11 que o usuário mandou resolver antes da operação real. A ficha vai para o hóspede, e "CONGRESSO_FEIRA (código FNRH)" se lê pior que "Congresso/Feira".

**Dependências:** nenhuma dentro deste roadmap (exige o gate do M5, `#concluida`). Independente das demais etapas; se rodar em branch separada, o conflito esperado fica no `ModularityTest` e na tabela da ARCHITECTURE §3.1.

**Escopo:**

- `frontdesk`: uma porta pública na raiz do módulo, que traduz o código de motivo de viagem e o de meio de transporte no rótulo. A ficha (PDF e ESC/POS) usa a porta; código fora do catálogo sai cru, com "(código FNRH)", como hoje.
- `fnrh`: implementa a porta lendo o catálogo de domínios (H11), só leitura, sem chamar o Serpro e sem trilha. É a **inversão por porta** decidida pelo usuário: o `frontdesk` continua sem importar o `fnrh`.
- Aresta `fnrh` → `frontdesk`, só pela porta: segue a regra por camadas (a "Integração governamental" fica acima da Hospedagem, ARCHITECTURE §3.2). Entra na tabela §3.1 e no teste do `fnrh` no `ModularityTest`, cujo comentário "nunca frontdesk" passa a "do `frontdesk`, só a porta dos rótulos".
- A descrição de `frontdesk.stays.checkin` passa a citar a ficha de registro (PDF e impressão). A sincronização do catálogo no boot (`PermissionCatalogSynchronizer`) leva a descrição nova ao banco; o código da permissão, os modelos de role e as concessões não mudam.
- `README.md`: a seção "Documentos impressos" deixa de dizer que a ficha mostra "os códigos FNRH como guardados".

**Fora de escopo:** rótulos nas respostas JSON da hospedagem (seguem com os códigos; o cliente lê os domínios em `GET /api/v1/fnrh/domains/{domain}`); uma permissão nova para a ficha (a D-E11-10 a manteve sob `frontdesk.stays.checkin`); mudar como a reserva guarda os códigos (M5 H10).

**Critérios de aceite:**

- [x] O PDF e o ESC/POS da ficha de um hóspede com códigos do catálogo semeado mostram o rótulo do motivo de viagem e o do meio de transporte, sem "(código FNRH)" (o teste extrai o texto).
- [x] Um código fora do catálogo sai cru, com "(código FNRH)", e a ficha é gerada sem erro (teste).
- [x] Um rótulo trocado no catálogo (como faz o `refresh` dos domínios) aparece na ficha seguinte (teste).
- [x] `ModularityTest`: o `fnrh` depende exatamente de `shared`, `guests`, `reservations`, `notifications` e `frontdesk`, e do `frontdesk` usa só a porta; o `frontdesk` continua sem depender do `fnrh`; a tabela da ARCHITECTURE §3.1 lista a aresta nova.
- [x] `GET /api/v1/permissions` devolve a descrição nova de `frontdesk.stays.checkin`, com código, modelos de role e concessões iguais aos de antes (teste).
- [x] A ficha continua sem dado de RN-19 e sem número inteiro de documento (os testes da M5 E11 passam sem mudança de asserção).
- [x] `api/openapi.yaml` não muda, e o `README.md` descreve a ficha com os rótulos.

**Validação obrigatória:**

- `./gradlew build` → sucesso (inclui `ModularityTest` e `checkOpenApi`).
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Revisão humana:** não

**Notas para o plano:** H11; a ficha está em `frontdesk/application/RegistrationCardService.kt` e nos templates `templates/frontdesk/registration-card.xhtml` e `.txt`; o catálogo, em `fnrh/domain/FnrhDomainCatalog.kt` (`TRAVEL_REASONS` e `TRANSPORT_MODES`). A porta precisa de uma única implementação no contexto: os testes do `frontdesk` usam a do `fnrh`, sem dublê de produção. A descrição nova muda a linha do catálogo, e a sincronização troca a versão das autorizações (triggers do `iam`): confirme no teste que isso não derruba sessões nem muda concessões. Os testes que mexem no catálogo de domínios devolvem o original no fim (o banco é compartilhado). Leia o handoff da M5 E11 e as decisões do lote E11.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Rótulo com caractere fora do IBM850 no ESC/POS: a limpeza de texto do `printing` (D-E11-4) já troca por `?`; confira um rótulo acentuado nos dois formatos.

**Evidências e handoff:**

- Plano: `docs/superpowers/plans/2026-11-14-m6-operacao-fechamento-e15.md`, 3 tarefas `Risco: alto`. Revisão de arquitetura: aprovada com notas na rodada 1 (`revisor-arquitetura`, opus; rodada 0 bloqueada com 1 Importante: a descrição do endpoint da ficha no contrato ficaria falsa, corrigido pela D-E15-7).
- Commits: branch `roadmap/m6-operacao-fechamento`, `dc52ff8` (início) a `c842f2a`. Cada tarefa passou por desenvolvedor → testador → verificação mecânica → `revisor-seguranca`. A Task 2 teve duas rodadas de correção: o `detektTest` do teste novo (achado do testador, `8fbab68`) e uma linha de KDoc com mais de 120 colunas (achada na revisão e refluída dentro do commit da Task 3, `f74657d`).
- Entregue:
  - porta `com.zeny.hotel.frontdesk.RegistrationCardLabels` (`travelReason`, `transportMode`; `null` fora do catálogo), implementada por `fnrh/application/FnrhRegistrationCardLabels` sobre o catálogo `fnrh.domain_value`, sem cache, sem Serpro e sem trilha;
  - aresta `fnrh` → `frontdesk` só pela porta: `FnrhModule` com `frontdesk` em `allowedDependencies`, `ModularityTest` exigindo os cinco módulos e que os tipos do `frontdesk` usados pelo `fnrh` sejam exatamente `{RegistrationCardLabels}`; o `frontdesk` continua sem o `fnrh`; ARCHITECTURE §3.1 (linha do `fnrh` e exemplos de inversão);
  - a ficha (PDF e ESC/POS) mostra o rótulo, ou o código seguido de "(código FNRH)" fora do catálogo; o texto é montado no serviço e os templates só o mostram;
  - descrição de `frontdesk.stays.checkin`: "Fazer o check-in de uma reserva confirmada (com a caução opcional) e abrir a ficha de registro (PDF e impressão)"; código, sensibilidade, escopos, modelos e concessões iguais;
  - descrição do `GET /api/v1/reservations/{id}/registration-card/pdf` no contrato e README ("Documentos impressos"); KDoc do `FrontdeskModule` com a porta.
- Revisão final: `revisor-seguranca` (opus) sobre `dc52ff8..c842f2a`, "Ready to merge: Yes", 0 Critical e 0 Importante de código.
- Verificação:
  - **Gate:** `./gradlew build` em `c842f2a` → BUILD SUCCESSFUL in 19m 36s, 3998 testes, 0 falhas, 0 ignorados.
  - **oasdiff:** `.github/scripts/check-api-breaking.sh main` → nenhuma mudança `ERR` em `/api/v1`; o diff do `api/openapi.yaml` da E15 é só a descrição da operação da ficha.
  - **Scanner:** `osv-scanner` 2.7.0 com `-L` nos três lockfiles: 0 Critical/High, 2 Medium e 3 Low pré-existentes; lockfiles sem mudança.
  - **Integridade:** `check_test_integrity.py --base dc52ff8` → exit 0, sem apontamentos. A única asserção existente alterada (`RegistrationCardApiTest`, `AUTOMOVEL (código FNRH)` → `Automóvel` mais a negativa) foi julgada justificada pela revisão final (D-E15-6).
- Critérios verificados:
  - (1) Rótulos no PDF e no ESC/POS: `RegistrationCardLabelsApiTest`, `RegistrationCardApiTest`.
  - (2) Código fora do catálogo, cru com "(código FNRH)": `RegistrationCardLabelsApiTest`, `RegistrationCardLabelsAttackApiTest`, `RegistrationCardApiTest` (`LAZER`).
  - (3) Rótulo trocado aparece na ficha seguinte: `FnrhRegistrationCardLabelsTest`, `RegistrationCardLabelsApiTest`.
  - (4) Grafo: `ModularityTest` (dependências exatas do `fnrh`, restrição à porta, `frontdesk` sem `fnrh`) e ARCHITECTURE §3.1.
  - (5) Descrição nova em `GET /api/v1/permissions`, com código, modelos e concessões iguais e sessões abertas válidas: `FrontdeskCheckInPermissionDescriptionTest`, `FrontdeskCheckInPermissionAttackTest`.
  - (6) Sem RN-19 e sem documento inteiro: os testes da M5 E11 sem mudança dessas asserções e `RegistrationCardLabelsAttackApiTest`.
  - (7) README com os rótulos; contrato: ver a decisão (a).
- Decisões para o usuário (tomadas pela opção recomendada e revisáveis; nenhuma é de arquitetura):
  - **(a)** O critério 7 dizia que o `api/openapi.yaml` não muda, mas a descrição do endpoint da ficha no contrato afirmava "os códigos FNRH guardados". A descrição foi atualizada e o contrato regenerado (D-E15-7): o critério foi lido como "sem mudança estrutural nem breaking change". Alternativa: manter o contrato descrevendo um comportamento que não existe mais.
  - **(b)** A porta lê o catálogo a cada ficha, sem cache (D-E15-2): o `refresh` aparece na ficha seguinte; custo de duas consultas pequenas por hóspede.
- Notas para a próxima etapa:
  - Para o `arquiteto`: ajustar o texto do critério 7 desta etapa (D-E15-7); a ARCHITECTURE §3.1 lista `hotel` entre as dependências do `fnrh`, que o `FnrhModule` não declara (previsão ou divergência).
  - Para a E16 (neste lote): o comentário do `ModularityTest` do `fnrh` ainda diz "Nunca iam, frontdesk…" logo acima de "M6 E15: frontdesk, só pela porta"; o KDoc e o nome de um teste de `RegistrationCardApiTest` ainda falam em "códigos FNRH".
  - Dívidas menores: marcas bidi e zero-width cruas em literais de teste (`RegistrationCardLabelsApiTest`, `FnrhRegistrationCardLabelsAttackTest`, e o padrão anterior em `printing`), melhor como escapes; SQL interpolado com constantes nos apoios de teste; contagem exata de códigos semeados no ataque da porta; apoios duplicados entre os dois testes da permissão.
  - O check-in recusa códigos de motivo e meio com caracteres fora de letras, dígitos, `_`, `.` e `-` (achado do testador): caracteres especiais só chegam à ficha pelo rótulo do catálogo, que o `refresh` também filtra.
- Captura do brain: `00 Inbox/2026-11-14 - ZenyHotelManager - atualização m6-operacao-fechamento E15 - c139da21.md`.

---

## E16 — Dívidas de teste herdadas do M5 — #concluida

**Objetivo:** pagar as duas dívidas de teste que o gate do M5 passou ao M6: o teste de índices das listas por situação, que ficou mais fraco na M5 E01 (pendência Média), e a extensão de compatibilidade `FnrhSubmissionDismissCompat.kt`, duplicada em dois pacotes de teste desde a M5 E08.

**Por que agora:** o handoff da M5 E12 as passou ao M6. O fechamento (E11) vai acrescentar consultas por data à `ReservationsApi`, e o teste de índices precisa voltar a guardar os índices parciais antes disso.

**Dependências:** nenhuma dentro deste roadmap (exige o gate do M5). Independente das demais.

**Escopo:** só código de teste:

- `ReservationSearchApiTest` ("every search filter and every list is served by an index…"): nas listas por situação (hospedados e no-shows previstos) e na busca só por situação, exigir um percurso de índice com condição útil sobre `reservation r` (`Index Cond`, ou só os índices com condição útil), como sugeriu a revisão da M5 E01, mantendo o `ANALYZE` que tirou a intermitência.
- Apagar as duas cópias de `FnrhSubmissionDismissCompat.kt` (`src/test/kotlin/com/zeny/hotel/fnrh/` e `…/fnrh/domain/`), com os testes que as chamam passando a usar `FnrhSubmission.dismiss()` de produção.

**Fora de escopo:** mudar índice, consulta ou código de produção. Se o teste endurecido mostrar uma lista sem índice útil, a etapa para e relata (é defeito de produção da M5 E01, a tratar numa etapa própria).

**Critérios de aceite:**

- [x] O teste de índices falha quando a lista de hospedados ou a de no-shows previstos é servida por um percurso sem `Index Cond` sobre `reservation r` (provado por mutação temporária, depois desfeita) e passa com os índices atuais.
- [x] O teste passa em três rodadas seguidas de `./gradlew test --tests '…ReservationSearchApiTest'` (sem voltar a intermitência).
- [x] Nenhum `FnrhSubmissionDismissCompat.kt` em `src/test`, e nenhum teste chama `dismiss` com instante.
- [x] Nenhum arquivo de `src/main` muda.

**Validação obrigatória:**

- `./gradlew build` → sucesso.

**Revisão humana:** não

**Notas para o plano:** handoff da M5 E01 (o item "Médio (para o gate E12 ou o M6)" e o item 1 da correção da intermitência) e decisão E05 (f) do M5 (o filtro "period" aceita dois índices, e isso fica como está). O banco é compartilhado e outros testes avançam a data: o teste de índices precisa continuar estável com hospedadas reais de outros testes.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O planejador do PostgreSQL pode preferir outro índice útil: aceite qualquer índice com condição útil na situação, não um nome fixo.

**Evidências e handoff:**

- Plano: `docs/superpowers/plans/2026-11-14-m6-operacao-fechamento-e16.md`, 3 tarefas (1 e 3 `Risco: alto`, 2 `normal`). Revisão de arquitetura: aprovada com notas na rodada 1 (`revisor-arquitetura`, opus; rodada 0 bloqueada com 1 Crítico: a busca só por situação não tem `Index Cond` possível, porque nenhum índice tem `status` na chave; e 4 Importantes, todos corrigidos).
- Commits: branch `roadmap/m6-operacao-fechamento`, `72a5d11` (início) a `559e25f`. Só código de teste: `git diff --stat` de `src/main` vazio. Cada tarefa passou por desenvolvedor → testador (as de risco alto) → verificação mecânica → `revisor-seguranca`. As Tasks 1 e 3 tiveram uma rodada de correção cada, pelos testes vermelhos do testador (o apoio de leitura do plano contava `Index Cond` de `InitPlan`/`SubPlan` e lia só o primeiro ramo do bitmap; o prefixo do console comia texto entre colchetes); os testes do testador não foram mudados.
- Entregue:
  - **Teste de índices:** nas listas por situação (hospedados e no-shows previstos, nas duas datas) e na busca só por situação, `reservation r` precisa ser lida por um índice com condição útil: `Index Cond` no próprio nó (ou no `Bitmap Index Scan` filho; `BitmapAnd` com um ramo útil, `BitmapOr` com todos), ou um índice parcial cujo predicado cita `status`. Apoio em `src/test/kotlin/com/zeny/hotel/reservations/ReservationPlanAssertions.kt`. O `ANALYZE` ficou; o filtro "period" segue com os dois índices (decisão E05 (f) do M5).
  - **Guarda dos índices parciais:** um teste novo, numa só conexão do dono com `lock_timeout` de 2 s e sempre em `ROLLBACK`, derruba os índices de data e exige o parcial da situação (`reservation_in_house_idx`, `reservation_confirmed_arrival_idx`), com `Index Cond` nas listas com data.
  - **Dismiss:** as duas cópias de `FnrhSubmissionDismissCompat.kt` saíram; os testes usam `FnrhSubmission.dismiss()`.
  - **Escopo acrescentado por ruling:** os textos de teste da E15 (comentário do `ModularityTest` do `fnrh`; KDoc e nome de um teste de `RegistrationCardApiTest`), do handoff da E15; e o `messagesOf` (`testsupport/LogMessages.kt`) lendo a linha ECS JSON do logback e tirando só `@timestamp`, `process`, `ecs`, `service` e `log.level`, do handoff e da revisão final da E14.
- Revisão final: `revisor-seguranca` (opus) sobre `72a5d11..559e25f`, "Ready to merge: Yes", 0 Critical e 0 Importante.
- Verificação:
  - **Gate:** `./gradlew build` em `559e25f` → BUILD SUCCESSFUL in 21m 25s, 4049 testes, 0 falhas, 0 ignorados.
  - **oasdiff:** sem `ERR` em `/api/v1`. **Scanner:** `osv-scanner` 2.7.0 com `-L` nos três lockfiles: 0 Critical/High, 2 Medium e 3 Low pré-existentes.
  - **Integridade:** `check_test_integrity.py --base 72a5d11` → exit 1, todos julgados justificados pela revisão final: três `ASSERCAO_REMOVIDA` no `ReservationSearchApiTest` (o `shouldUseOneOf` das listas por situação trocado pelo apoio novo e pela guarda, mais fortes no conjunto); dois `ASSERCAO_REMOVIDA` no `FnrhSubmissionTransitionsTest` (só o argumento ignorado de `dismiss` saiu); e `SEM_SOUL` em `6689f13`, commit do testador (só `LogMessagesAttackTest.kt`) cujo `Soul: testador` ficou colado ao corpo da mensagem, sem linha em branco, e por isso não é lido como trailer; o histórico não foi reescrito.
- Critérios verificados:
  - (1) Mutação: com a consulta de no-shows previstos trocada para data e situação não indexáveis, o plano `Index Scan using reservation_arrival_idx on reservation r` só com `Filter` — que passava no teste antigo — fica vermelho; derrubar `reservation_in_house_idx` dentro da guarda também fica vermelho. Mutações desfeitas.
  - (2) Três rodadas seguidas do `ReservationSearchApiTest` verdes, e uma cópia temporária rodada depois do `StayTransitionsApiTest`, com a ordem conferida pelos carimbos do relatório XML (00:07:21.206Z → 00:07:21.219Z), apagada antes do commit; mais o gate.
  - (3) Nenhum `FnrhSubmissionDismissCompat.kt` em `src/test` e nenhuma chamada de `dismiss` com instante (a extensão não existe mais, e a compilação passa).
  - (4) Nenhum arquivo de `src/main` mudou.
- Decisões para o usuário (tomadas pela opção recomendada e revisáveis; nenhuma é de arquitetura):
  - **(a)** "Condição útil" inclui o índice parcial da situação sem `Index Cond` (a busca só por situação não tem data, e nenhum índice tem `status` na chave). Se o projeto quiser `Index Cond` também nela, precisa de índice com `status` na chave (mudança de produção).
  - **(b)** A guarda dos parciais faz DDL numa transação desfeita, a cada build, no banco compartilhado dos testes: um `ACCESS EXCLUSIVE` de milissegundos, com `lock_timeout` de 2 s (falha rápida, nunca build preso).
  - **(c)** Escopo acrescentado (textos da E15 e `messagesOf` em ECS), com origem nos handoffs da E14 e da E15.
- Notas para a próxima etapa:
  - **Observabilidade (para o `arquiteto` e a nota de observabilidade do brain):** em `prod`, uma chave de MDC chamada `service` ou `process` (ou com esses prefixos e ponto) faz o escritor ECS do Spring Boot falhar, e a linha de log some (achado do testador). As chaves de hoje (`userId`, `deviceSessionId`, `traceId`, `spanId`) não colidem. Recomendação: registrar a regra "chave de MDC nunca usa raiz reservada do ECS" e, numa etapa que possa mexer em `src/main`, um teste que suba o log `prod` com cada constante de MDC.
  - **Log dos testes:** os testes que sobem o profile `prod` (`ProductionProfileApiTest` e dois do `LogMessagesAttackTest`) reinicializam o logback da JVM inteira (ECS e `root: INFO`) até o próximo contexto; uma asserção negativa sobre linha DEBUG pode passar à toa, conforme a ordem. Sugestão: restaurar a configuração de log no fim desses testes ou isolá-los num fork.
  - **Dívidas menores de teste:** o teste "a BOM before the brace…" de `LogMessagesAttackTest` não tem o BOM no literal (use o escape `\uFEFF`); `messagesOf` trata qualquer objeto JSON como ECS, aceita tokens depois do JSON e chave duplicada (só tratar como ECS com `ecs.version` ou `@timestamp` no topo; ligar `FAIL_ON_TRAILING_TOKENS` e `STRICT_DUPLICATE_DETECTION`); as asserções negativas do teste do MDC `service`/`process` passam também sem linha logada; a guarda dos parciais olha só o primeiro ramo do bitmap, descarta ramo desconhecido num `BitmapOr` e lê a data operacional pelo pool da aplicação enquanto segura a trava (seguro hoje, porque lê só o schema `hotel`); o `LIKE '%status%'` do catálogo é largo; um `Index Cond` parametrizado pela junção conta como útil.
- Captura do brain: `00 Inbox/2026-11-14 - ZenyHotelManager - atualização m6-operacao-fechamento E16 - f7e211bd.md`.

---

## E17 — Corpo mínimo nas escritas de restrição e mesclagem do `guests` — #concluida

**Objetivo:** fechar a lacuna do M3 apontada na M5 E15 (decisão (h), decidida pelo usuário em 2026-11-11): as respostas de `POST /api/v1/guests/{id}/restrict`, `POST …/lift-restriction` e `POST …/merge` só trazem o perfil inteiro (com a descrição de gênero e as restrições alimentares) a quem também tem `guests.profiles.read`; sem essa permissão, devolvem um corpo mínimo.

**Por que agora:** o usuário decidiu a correção e pediu a etapa ao `arquiteto` (o que entrou nesta sincronização). Com os modelos de role padrão não há exposição, mas uma role customizada com `guests.restrictions.manage` ou `guests.profiles.merge` sem `guests.profiles.read` lê dado pessoal que não deveria.

**Dependências:** nenhuma dentro deste roadmap (exige o gate do M5). Independente das demais.

**Escopo:** `guests`:

- Nas três rotas, a escrita continua exigindo só a própria permissão; o corpo da resposta passa a depender de `guests.profiles.read`: com ela, o perfil inteiro, como hoje; sem ela, um corpo mínimo, sem nome, documento, contato, dado sensível nem restrição alimentar (o que entra no mínimo é decisão do plano).
- Contrato regenerado (`./gradlew updateOpenApi`), descrevendo os dois corpos, sem mudança `ERR` no oasdiff. Isso pode exigir manter o esquema atual e acrescentar o mínimo como alternativa, em vez de tornar opcionais as propriedades obrigatórias de hoje.
- `README.md` e KDoc das três rotas.

**Fora de escopo:** outras rotas do `guests`; mudar as permissões ou os modelos de role.

**Critérios de aceite:**

- [x] Com `guests.restrictions.manage` e sem `guests.profiles.read`, `restrict` e `lift-restriction` gravam a mudança e devolvem o corpo mínimo, sem nome, documento, contato, descrição de gênero nem restrição alimentar (teste com role customizada).
- [x] Com `guests.profiles.merge` e sem `guests.profiles.read`, `merge` mescla e devolve o corpo mínimo (teste).
- [x] Com as duas permissões, as três rotas devolvem o perfil inteiro, como antes (os testes atuais passam).
- [x] A trilha das três escritas não muda.
- [x] `.github/scripts/check-api-breaking.sh` sem mudança `ERR` em `/api/v1`, e o contrato descreve os dois corpos.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Revisão humana:** sim

**Notas para o plano:** decisão E15 (h) do lote E15 e handoff da M5 E15 (`docs/roadmaps/roadmap-m5-recepcao-fnrh.md`); a nota `ZenyHotelManager - Módulo guests` e a captura pendente da E15 (o contrato e o KDoc já foram corrigidos para não prometer o contrário). As rotas estão em `guests/web/GuestsController.kt`. A conferência de permissão do corpo fica no caso de uso ou no controller, sem `iam`: use `Authorization` do kernel. Revisão humana porque muda a forma de uma resposta pública que os clientes consomem.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Cliente que lê o perfil da resposta sem ter `guests.profiles.read`: hoje só uma role customizada chega aí; registre a mudança na seção "API" do `README.md` e na captura do brain.

**Rollback / migração:** sem migração. A mudança é só de resposta, e reverter é voltar ao corpo inteiro.

**Evidências e handoff:**

- Plano: `docs/superpowers/plans/2026-11-14-m6-operacao-fechamento-e17.md`, 2 tarefas, ambas `Risco: alto`. Revisão de arquitetura: aprovada com notas na rodada 2 (`revisor-arquitetura`, opus). Na rodada 0 foi bloqueada com 5 Importantes: a decisão do corpo estava no controller, o `oneOf` não tinha registro nem teste no SDK, havia `additionalProperties: false`, os campos do mínimo não tinham justificativa e um teste fixava o texto antigo. Na rodada 1 foi bloqueada com 2 Importantes: o `GuestMergeApiTest` usava o retorno de `merge`, e o risco da Task 2 estava errado.
- Commits: branch `roadmap/m6-operacao-fechamento`, de `829c256` (início) a `bcb06f1`.
  - Task 1: `6b1a022` (desenvolvedor) e `d3aee24` (testador, 9 testes de ataque).
  - Task 2: `e105d7d` (desenvolvedor); `ba653d7` (testador, vermelho); a rodada de correção `f975c8b` (desenvolvedor); e `aa7c668` (testador).
  - Onda final: `2f7f314` (testador) e `bcb06f1` (desenvolvedor, só KDoc).
  - Cada tarefa passou por desenvolvedor → testador → verificação mecânica → `revisor-seguranca`, e as duas foram aprovadas só com Menores.
- Entregue:
  - **Decisão no caso de uso** (ARCHITECTURE §8.2): `GuestService.restrict`, `GuestService.liftRestriction` e `GuestMergeService.merge` devolvem `GuestWriteResult(id, restricted, version, profile: GuestView?)`. Depois do `require` da escrita, e na mesma transação, o helper `Guest.writeResult(authorization)` (`guests/application/GuestsSupport.kt`) pergunta `grantOrNull(guests.profiles.read)`. Sem a permissão, o `GuestView` nem é montado, e nada é decifrado. O controller só traduz e não recebe `Authorization`. A escrita e a trilha não mudaram (o `AuditAspect` não lê o retorno).
  - **Corpo mínimo** `GuestWriteSummaryResponse {id, restricted}`, com a versão no `ETag`. No `merge`, os dois campos são do sobrevivente, e `restricted` inclui a restrição herdada do absorvido.
  - **Contrato:** nas três respostas `200 application/json`, `oneOf: [GuestResponse, GuestWriteSummaryResponse]` sem discriminador. É o primeiro `oneOf` do contrato. Os ramos são disjuntos pelo `required`: o mínimo exige `restricted`, que o `GuestResponse` não tem, e o inteiro exige `fullName`. Não há `additionalProperties: false`. O `GET /guests/{id}` e o `GuestResponse` não mudaram.
  - **Textos:** a descrição de `genderDescription`, os KDocs (`GuestView`, `GuestsController`, `liftRestriction`) e o item "Hóspedes e empresas" do `README.md` deixaram de prometer o perfil inteiro nas escritas.
  - **Teste do texto do contrato:** `yamlScalarText` (`GuestGenderDescriptionApiTest.kt`) decodifica o escalar YAML gerado (`\` + quebra + recuo; `\ ` = espaço). O testador provou que a normalização anterior comia o espaço e deixava a asserção negativa passar à toa.
- Revisão final: `revisor-seguranca` (opus) sobre `b42ce6f..aa7c668`, "Ready to merge: Yes", 0 Critical e 0 Importante. A onda única de correção (`2f7f314`, `bcb06f1`) tratou os Menores 1 a 4: as asserções positivas sobre o texto lógico, a do `Location`, o telefone e o KDoc. A re-revisão (sonnet) foi aprovada: 5/5 tratados, sem problema novo.
- Verificação:
  - **Gate:** `./gradlew build` em `bcb06f1` → BUILD SUCCESSFUL in 33m 44s, 4068 testes, 0 falhas, 0 ignorados.
  - **oasdiff:** 0 error, 3 warning `response-body-wrapped-in-one-of-original-preserved` e "Nenhuma mudança incompatível (nível ERR) em /api/v1."
  - **Scanner:** `osv-scanner` 2.7.0 com `-L` em `gradle.lockfile` (426 pacotes) e `buildscript-gradle.lockfile` (74). O `settings-gradle.lockfile` só tem `empty=…` e o 2.7.0 não tem extrator para ele. Resultado: 0 Critical/High, 2 Medium e 3 Low, todos pré-existentes. Lockfiles e catálogo sem mudança.
  - **Integridade:** `check_test_integrity.py --base b42ce6f` → exit 0, sem apontamento.
- Critérios verificados:
  - (1) `GuestWriteBodyApiTest` "restrict and lift without profiles read write the change and return only the minimal body": role só com `guests.restrictions.manage`. Chaves exatas `{id, restricted}`, nenhum texto pessoal no corpo cru (nome, e-mail, telefone, descrição de gênero, restrição alimentar e motivo), `restrictionAlert` gravado e retirado.
  - (2) "merge without profiles read merges and returns only the minimal body": o absorvido fica `MERGED`, com `mergedInto`.
  - (3) "with the write permission and profiles read the three routes return the full profile", mais o ataque "the full body with profiles read is identical to the GET of the same profile" e a suíte inteira verde.
  - (4) "the trail of the three writes is the same with either body" e "a lift on a profile outside the list returns the minimal body and writes no trail entry".
  - (5) oasdiff acima e "the contract describes both bodies only on the three writes and keeps them disjoint".
  - Ataques do testador: cabeçalhos e `ETag`, permissão retirada ou concedida entre chamadas, `takeFromMerged` com dado sensível, recusas 400/403/404/409/412/428 como `problem+json` sem dado de perfil.
- Revisão humana: feita em 2026-11-15, na entrevista do fim do lote. O usuário aprovou (a) e (b) pelas opções recomendadas (H14).
- Decisões para o usuário (tomadas pela opção recomendada; (a) e (b) confirmadas pelo usuário em 2026-11-15, H14):
  - **(a) Forma do contrato, de arquitetura (decidida pelo usuário: manter o `oneOf`):** `oneOf` sem discriminador nas três respostas. SDK de prova com o ogen 0.16.1 do front: as três operações saem como `HttpResponseTyped<JsonElement>`, sem tipo, para todo cliente gerado (desktop, mobile, PDV). O front não usa essas operações hoje. A alternativa tipada é um discriminador, que pede uma propriedade nova no `GuestResponse` de todas as operações de perfil. Outro status ou outro media type dão `ERR`.
  - **(b) Conteúdo do mínimo `{id, restricted}` (aprovado pelo usuário):** `status` (sempre `ACTIVE` num `200`) e `updatedAt` (sem consumidor) ficaram de fora; acrescentar campo depois é aditivo. Uma role só com `guests.profiles.merge` fica sabendo se o sobrevivente está restrito, pelo UUID.
  - **(c) Mudança de forma** só para roles customizadas sem `guests.profiles.read`; os modelos padrão não mudam. Registrado no `README.md`.
  - **(d)** O helper `Guest.writeResult` ficou num 5º arquivo de produção, além dos 4 do plano. A revisão aceitou.
- Notas para a próxima etapa:
  - **Para o `arquiteto`:** se o `oneOf` ficar, registrar na ARCHITECTURE §7 a regra "resposta que varia com a permissão: `oneOf` sem discriminador, ramos disjuntos pelo `required`, sem `additionalProperties: false`" (nota M-7 da revisão do plano).
  - **Supply chain, fora da E17:** `io.opentelemetry:opentelemetry-api` 1.62.0 está no `productionRuntimeClasspath` com o GHSA-ccrj-gw8r-854m (DoS por regex no `W3CTraceContextPropagator` lendo `tracestate`), corrigido na 1.67.0. Subir numa etapa de manutenção, ou limitar o `tracestate` no proxy.
  - **Uso real:** uma role com `guests.profiles.merge` e sem `guests.profiles.read` não obtém o `ETag` do sobrevivente pela API (o `GET` e o `duplicate-candidates` exigem leitura).
  - **Observação do testador, anterior à E17:** a resposta das escritas traz `updatedAt` e `restriction.since` com nanossegundos (instante em memória), e o `GET` traz microssegundos (banco).
  - **Processo:** os testes de uma etapa não devem usar laço `while pgrep -f` com um trecho de comando, porque o padrão casa com a linha de comando do próprio laço e nunca termina. Dois builds completos simultâneos (este repositório e o front) levaram a máquina de 14 GB à pressão de memória, e o Claude Code encerrou um deles: rode o gate numa janela sem outro build.
  - **Menor restante:** `yamlScalarText`, `schemaBlock` e `propertyBlock` são `internal` em `GuestGenderDescriptionApiTest.kt` e usados por outro teste. Mover para um utilitário de teste compartilhado é só organização.
- Capturas do brain: `00 Inbox/2026-11-15 - ZenyHotelManager - atualização m6-operacao-fechamento E17 - cb3979d0.md` e `00 Inbox/2026-11-15 - ZenyHotelManager - atualização m6-operacao-fechamento E17 (revisão humana) - 4ab01eb7.md`.

---

## E18 — Faixa do cursor, rollback em toda exceção, datas estritas e log dos jobs — #concluida

**Objetivo:** resolver no `shared`, para todos os módulos, os quatro problemas transversais achados na E10 (nenhum foi introduzido por ela): (1) chave de data ou de instante do cursor fora do alcance do PostgreSQL dá 500 em vez de `400 INVALID_CURSOR`; (2) uma exceção *checked* que escapa de um caso de uso confirma a transação, e a escrita fica sem trilha; (3) campos de data e de instante aceitam formatos fora do ISO-8601 da ARCHITECTURE §7; (4) a falha de um job, inclusive a da trava do ShedLock com o banco fora, é logada com a mensagem do JDBC.

**Por que agora:** decisão do usuário de 2026-11-13 (H12; handoff da E10, "Decisões do usuário", item 3), com o escopo de H13. A etapa fica depois do fechamento e da manutenção herdada e antes da governança, da manutenção e dos relatórios (E01 a E09), que vão criar muitos casos de uso, listas por cursor e campos de data. Com a regra corrigida no `shared` antes disso, os módulos novos não copiam a validação local do `endofday` nem passam a depender do commit numa exceção *checked*. O item 2 muda a semântica transacional de todos os módulos: fazê-lo antes desse volume de código reduz o que precisa ser reconferido.

**Dependências:** nenhuma dentro deste roadmap (exige o gate do M5). É independente das demais. Se rodar numa branch separada de uma etapa que mude a API, espere conflito em `api/openapi.yaml`.

**Escopo:**

1. **Faixa do cursor (`PageCursor`, `shared/Pagination.kt`):** `instant(i)` e `localDate(i)` recusam, com `400 INVALID_CURSOR`, valor fora do alcance do `timestamptz` e do `date` do PostgreSQL (ARCHITECTURE §11). Assim, toda consulta que lê essas chaves fica protegida sem código local: as 9 por instante (`AuditQueries`, `ChecklistReports`, `ChecklistExecutions`, `ReservationLifecycle`, `ProvisionalReservations`, `CardPreAuthorizationService`, `PaymentService`, `FolioService`, `RoomStatusService`) e as 6 por data (`EndOfDayQueries` na lista de execuções, `ReservationQueries`, `RoomAssignments`, `TraceService`, `OutOfOrderService`, `Scheduling`), todas conferidas em 2026-11-13. A validação local do `endofday` (`EndOfDayQueries.requireStorable` e as constantes dela) sai, e o comportamento observável não muda.
2. **Rollback em toda exceção:** o `@EnableTransactionManagement` do `shared` (`TransactionOrderingConfiguration`, em `shared/infrastructure/audit/AuditAspect.kt`, que substitui o do Boot) passa a usar `rollbackOn = RollbackOn.ALL_EXCEPTIONS`. As regras explícitas `noRollbackFor` de hoje continuam valendo: login e refresh com falha, aprovação recusada e `files`, que gravam a recusa (`recordRejections`). O inventário dos caminhos que lançam exceção *checked* dentro de `@Transactional` sai no handoff, com o efeito da mudança em cada um. Pistas de 2026-11-13: IO em `files`, `printing` e `fnrh`, o tempo real e o `endofday`, cujas transições já usam `rollbackFor = Throwable`; o plano decide se essa regra local sai.
3. **Datas e instantes estritos no JSON:** o mapeador da aplicação (Jackson 3, `tools.jackson`, configurado pelo Spring Boot) recusa número e data-hora em `LocalDate` (`format: date`) e número (epoch) em `Instant` (`format: date-time`), com `400 VALIDATION_FAILED` e o erro no campo, igual à resposta de hoje para uma data malformada. Vale para todo corpo JSON de `/api/v1` e para os payloads dos comandos offline (`POST /api/v1/sync/commands`): o mapeador é um só (H13), e o comando fora do formato vira `REJECTED` com código. O JSON que o próprio servidor grava e relê (em `reservations` e `checklists`) continua legível. O contrato já declara `format: date` e `date-time`. Se o `api/openapi.yaml` mudar, ele é regenerado com `./gradlew updateOpenApi`, sem mudança `ERR`.
4. **Log dos jobs sem mensagem:** um tratador de erro no agendador da aplicação (o `TaskScheduler` dos `@Scheduled`) loga a exceção que escapa de um job com o nome da tarefa e só a classe, a dela e a das causas, sem a mensagem (ARCHITECTURE §16 e §13; nota `ZenyHotelManager - Docker, observabilidade e CI`, "Valores do banco fora do log"). Ele vale para os 15 `@Scheduled` de hoje e para os futuros, sem código por job, e cobre a falha da trava do ShedLock com o banco fora. O plano confere também o que o próprio ShedLock loga nesse caminho.

**Fora de escopo:**

- Datas e instantes em query string e path (conversão do Spring MVC, não do Jackson) e datas fora do alcance do banco no **corpo** de outros endpoints: o plano confere se sofrem do mesmo problema e registra o achado em "Notas para a próxima etapa", sem corrigir.
- Mudar o formato de saída (já é ISO-8601) ou aceitar/recusar deslocamento diferente de `Z` num instante.
- Logs fora dos jobs: as respostas de erro das requisições já passam pelo tratador RFC 9457.
- Mudar o executor do `endofday`, que já loga só a classe.

**Critérios de aceite:**

- [x] **Cursor, unidade:** `PageCursor.instant` e `PageCursor.localDate` aceitam os extremos do `timestamptz` e do `date` do PostgreSQL e recusam com `INVALID_CURSOR` o valor imediatamente fora de cada extremo (teste do `PageCursor`).
- [x] **Cursor, consultas:** em cada uma das 15 consultas do escopo, um cursor bem formado com a chave fora da faixa dá `400 INVALID_CURSOR` e não 500 (teste pelo endpoint que expõe a consulta, ou pelo serviço quando ela não tiver endpoint). Teste vermelho antes da correção em pelo menos uma consulta por instante e uma por data.
- [x] **Cursor, `endofday`:** `EndOfDayQueries` não tem validação própria de faixa, e os testes de cursor das pendências da E10 continuam passando.
- [x] **Transação:** um caso de uso de teste `@Transactional` + `@Audited` que grava e lança uma exceção *checked* não deixa nem a escrita nem a entrada de trilha. O teste fica vermelho com a configuração de antes e verde com a nova.
- [x] **Transação, exceções explícitas:** um `noRollbackFor` continua confirmando a transação com `ALL_EXCEPTIONS` (teste novo), e os testes atuais de login com falha, refresh, aprovação recusada e `files` passam.
- [x] **Transação, inventário:** o handoff lista cada caminho com exceção *checked* dentro de `@Transactional` e diz, em cada um, que nada dependia do commit ou qual ajuste foi feito.
- [x] **Datas:** no `POST /api/v1/end-of-day/runs` e em pelo menos outro endpoint com `LocalDate` no corpo, `businessDate` em número (`20405`) e em data-hora (`"2026-11-13T00:00:00"` e `"2026-11-13T00:00:00Z"`) dão `400 VALIDATION_FAILED` com o erro no campo, e `"2026-11-13"` continua aceito.
- [x] **Instantes:** num corpo de entrada com `Instant`, número (epoch em segundos e em milissegundos) dá `400 VALIDATION_FAILED` no campo, e o ISO-8601 com `Z` continua aceito.
- [x] **Mapeador:** um teste sobre o mapeador do contexto da aplicação (não um montado no teste) recusa os mesmos formatos para `LocalDate` e `Instant`.
- [x] **Offline:** um comando offline com data em número volta `REJECTED`, com código, e fica registrado; com data ISO, é aplicado.
- [x] **Jobs:** um job agendado real, com o `LockProvider` falhando com uma exceção cuja mensagem e cuja causa levam um valor sentinela, gera log (saída capturada) com a classe da exceção e sem o sentinela em lugar nenhum. Teste vermelho antes da correção.
- [x] `./gradlew checkOpenApi` passa, e `.github/scripts/check-api-breaking.sh` não acusa mudança `ERR` em `/api/v1`.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Revisão humana:** não. O item 3 aperta entradas que o contrato já declara (`format: date` e `date-time`), sem mudar o esquema, e nenhum cliente está em produção. Se o `api/openapi.yaml` mudar além de descrições, a mudança vai para "Decisões para o usuário".

**Notas para o plano:** H12 e H13; handoff da E10 (as transversais e "Decisões do usuário") e a captura pendente `ZenyHotelManager - atualização m6-operacao-fechamento E10` e a dela "(revisão humana)"; ARCHITECTURE §5, §7, §11 e §13, onde a decisão já está registrada (o plano só a cumpre). **Cursor:** o cursor é opaco e não assinado, e as chaves são entrada não confiável (nota `ZenyHotelManager - Convenções de API`). As constantes do `endofday` (`MIN_STORABLE_INSTANT`, `MAX_STORABLE_INSTANT_EXCLUSIVE`) são o ponto de partida do instante; o limite da data é outro (o `date` vai além do `timestamptz`). **Transação:** o `RollbackOn` é da Spring Framework 6.2 em diante (o stack usa a 7). As regras de rollback dos `TransactionTemplate` programáticos, se houver, não seguem o atributo e precisam ser conferidas à parte. A interação com `recordRejections` e `noRollbackFor` está na nota `ZenyHotelManager - Auditoria`. **Datas:** o `JsonMapper` da aplicação é injetado em `JdbcBookingStore`, `JdbcRoomBlockStore`, `JdbcChecklists`, `ChecklistsOfflineSources`, `OfflineCommands` (`convertValue` do payload), `ProblemWriter`, `SecurityConfiguration` e `PrintAgentStreamController`. O `SerproFnrhWire` tem mapeador próprio, fora do escopo. A configuração atual está em `spring.jackson` (`application.yaml`). Testes que enviam data em número para entrada legítima são dívida de teste e mudam junto. **Jobs:** `@EnableScheduling` e o `LockProvider` ficam em `shared/infrastructure/scheduling/SchedulingConfiguration.kt`. O driver já conecta com `logServerErrorDetail=false` (sem o DETAIL do PostgreSQL), mas a mensagem do JDBC ainda pode trazer valores. Máquina de 14 GB: um Gradle por vez.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O item 2 muda a semântica transacional de todos os módulos: um caso de uso que hoje confirma de propósito numa exceção *checked* passaria a desfazer. Mitigação: o inventário do escopo vem antes da mudança e passa pelo `revisor-arquitetura` no plano; há teste do `noRollbackFor` e o build completo com os testes de todos os módulos.
- O item 3 pode recusar algo que o próprio servidor, um teste ou um payload offline já envia em formato leniente. Mitigação: os testes de leitura do JSON persistido e dos comandos offline estão nos critérios; os ajustes de teste ficam registrados no handoff.
- O item 4 pode esconder a causa de uma falha real de job. Mitigação: a classe da exceção e das causas continua no log, e o nome do job vai junto; métrica e alerta dos jobs seguem como estão.

**Rollback / migração:** sem migração. Reverter é tirar as regras do `shared` (faixa, `rollbackOn`, leniência e tratador).

**Evidências e handoff:**

- Plano: `docs/superpowers/plans/2026-11-17-m6-operacao-fechamento-e18.md`, com 3 fatias. Revisão de arquitetura: aprovada com notas na rodada 1 (`revisor-arquitetura`, opus). Na rodada 0 foi bloqueada por 6 Importantes: `Riscos:` incompleto, dependência da Task 2, critérios sem verificação, tamanho das fatias, a decisão (f) contrária à convenção do `defaultCandidate = false` e decisões transversais sem registro na ARCHITECTURE.
- **Refação (H15):** a execução anterior da E18 se perdeu com a branch (troca da distro WSL). Esta execução partiu da mesma base `5e06f16` e reproduz o comportamento documentado no brain. A única diferença intencional é a decisão (f), abaixo.
- Commits: branch `roadmap/m6-operacao-fechamento`, de `83f54fc` (início) a `626de72`.
  - Task 1 (faixa do cursor): `d865e23` (desenvolvedor).
  - Task 2 (datas estritas): `1cc28cc` (desenvolvedor).
  - Task 3 (rollback e log dos jobs): `f6268a1` e `fda61ff` (desenvolvedor).
  - Testador da etapa: `626de72`, só `src/test`.
  - Cada fatia passou por desenvolvedor → verificação mecânica → `revisor-seguranca`. A Task 1 foi aprovada com 3 Menores; a Task 2, com 3 Menores; a Task 3, com 1 Importante de teste (controle positivo da trilha), resolvido pelo testador, e 4 Menores.
- Entregue:
  - **Faixa do cursor:** `com.zeny.hotel.shared.StorableRange` vai de `-4712-01-01` a `+5874897-12-31` nas datas e de `-4712-01-01T00:00:00Z` a `+294276-12-31T23:59:59.999999Z` nos instantes, com os extremos inclusivos. `PageCursor.instant`/`localDate` recusam o que fica fora com `400 INVALID_CURSOR`. As 15 consultas e as pendências do `endofday` passam por eles. A validação local do `endofday` saiu, e `checkNoRun` usa o `StorableRange`. A ARCHITECTURE §7 e a §11 registram o piso efetivo do pgjdbc.
  - **Datas estritas:** `shared/infrastructure/json/StrictDateTimeJson.kt`, um `JsonMapperBuilderCustomizer` com `leniency=false` e o `IsoText` em `LocalDate` (`ISO_LOCAL_DATE`, com ano com sinal) e em `Instant` (`ISO_INSTANT`). Aceita só texto, sem espaço nas pontas. Número, epoch, data-hora numa data, array, objeto e `""` dão `400 VALIDATION_FAILED` com `INVALID_FORMAT` no campo, com o caminho completo. Um comando offline fora do formato volta `REJECTED COMMAND_PAYLOAD_INVALID` e não é guardado. A ARCHITECTURE §7 registra a regra do `""`.
  - **Rollback:** `rollbackOn = RollbackOn.ALL_EXCEPTIONS` em `TransactionOrderingConfiguration`. Os 5 `noRollbackFor` seguem confirmando, e as transições do `endofday` perderam o `rollbackFor = Throwable`. A ARCHITECTURE §5, item 6, registra a transação unida: uma *checked* engolida dentro dela leva a `UnexpectedRollbackException` no commit externo.
  - **Log dos jobs:** `shared/infrastructure/scheduling/ScheduledJobErrors.kt` (`JobErrorLoggingTaskScheduler`, bean `taskScheduler` com `defaultCandidate = false`, montado pelo `ThreadPoolTaskSchedulerBuilder`). O formato é `Job Classe.método falhou: Classe[ <- Causa…]`, sem mensagem, sem stack e sem suprimidas, com proteção contra ciclo. O logger `JdbcTemplateStorageAccessor` fica em `OFF`. A ARCHITECTURE §13 registra (d) e (f).
- **Inventário dos caminhos com exceção *checked* dentro de `@Transactional`** (critério da etapa; detalhe no relatório da Task 3): **nenhum caminho dependia do commit, e nada foi ajustado.**
  - `noRollbackFor`: `Authentication.kt:80` e `:124`, `TokenRefreshService.kt:43`, `Approvals.kt:117` e `FilesService.kt:236` seguem confirmando (teste e suítes `iam`/`files`).
  - `printing`: `HtmlPdfRenderer.render` (`SAXException`/`IOException`). O `pdf(...)` é `readOnly`, e o `print(...)` renderiza antes da única escrita. Antes confirmava uma transação vazia; agora desfaz uma vazia.
  - `shared`: as cifras capturam `GeneralSecurityException`. `IdempotencyFilter` e `OfflineCommands` gravam por `TransactionTemplate`, que desfaz em qualquer `Throwable`. `StreamController`/`RealtimeHub` ficam fora de transação.
  - `files`: `S3FileStorage.kt:183` (`URISyntaxException`) é alcançado por `FilesService.downloadUrl`, sem escrita antes. O reencode do `upload` captura `IOException`.
  - `MessageDigest.getInstance("SHA-256")` em 6 lugares: inalcançável.
  - `notifications`: `FcmPushGateway.requestAccessToken` não captura `JOSEException` de `SignedJWT.sign`. É pendência do `arquiteto` já registrada; o ouvinte não tem escrita própria.
  - `fnrh`: envio fora de transação; o alerta vai em `afterCommit` com transação própria.
  - `endofday`: o motor roda sem transação, e as transições têm transação própria.
  - Jobs (`ProvisionalExpiry`, `InventoryHorizonJob`, `GuestDocumentRekeyJob`, `FileOrphanPurgeJob`): transação própria por item, sem transação externa.
  - `iam`: `JwtEncodingException` é não-checada.
  - Há **14** `@Scheduled` em `src/main`; o roadmap e a nota de jobs dizem 15. Pelo critério `Exception`/`Throwable`/`RuntimeException`/`runCatching`, há 25 `catch` amplos; a nota de auditoria dizia 83, por outro critério. Nenhum engole uma *checked* vinda de um proxy na mesma transação.
- Revisão final: `revisor-seguranca` (opus) sobre `be3ecf3..626de72`, "Ready to merge? Yes", 0 Crítico e 0 Importante. Os 6 Menores ficaram sem correção (abaixo).
- Verificação:
  - **Gate:** `./gradlew build` em `626de72` → BUILD SUCCESSFUL em 15m 7s, com 4095 testes, 0 falhas e 0 ignorados (contagem dos XML do JUnit).
  - **oasdiff:** "No changes detected" e nenhuma mudança `ERR` em `/api/v1`; o contrato não mudou.
  - **Scanner:** `osv-scanner` 2.7.1 com `-L` em `gradle.lockfile` (426 pacotes) e `buildscript-gradle.lockfile` (74). Resultado: 0 Critical/High, 2 Medium e 3 Low, todos anteriores à etapa (`commons-lang3` 3.16.0 só no buildscript, `logback-core` 1.3.16 só no ktlint, `opentelemetry-api` 1.62.0 aceito até a M8 E17). Lockfiles e catálogo sem mudança.
  - **Integridade:** `check_test_integrity.py --base be3ecf3` → exit 0, sem apontamento. Em cada fatia, também exit 0.
  - **Ambiente:** antes do gate, o baseline da `main` estava vermelho nesta máquina, com 12 falhas no `endofday`. A causa era o relógio do WSL saltando ±25 s (o `systemd-timesyncd` brigando com a hora do Windows, atrasada). O usuário desligou o `systemd-timesyncd` em 2026-11-17; não houve mudança de código.
- Critérios verificados:
  - **Cursor, unidade:** `PaginationTest`, com os extremos exatos e os vizinhos de fora, inclusive a fração abaixo do µs acima do teto.
  - **Cursor, consultas:** `CursorKeyRangeApiTest`, com as 15 consultas e as pendências do `endofday`. O vermelho antes da correção apareceu nas 15: `200` com página vazia abaixo do piso e `500` acima do teto.
  - **Cursor, `endofday`:** o grep de `requireStorable|MIN_STORABLE|MAX_STORABLE` em `src/main` sai vazio, e os testes de cursor da E10 estão verdes.
  - **Transação:** `RollbackOnAllExceptionsTest` (1), com o vermelho `expected:<0> but was:<1>` e o controle positivo `trailEntriesWith == 1`.
  - **Transação, exceções explícitas:** `RollbackOnAllExceptionsTest` (2) e as suítes `iam.*`/`files.*`.
  - **Transação, inventário:** o inventário acima.
  - **Datas:** `StrictDateTimeJsonApiTest`, com `businessDate` (`POST /end-of-day/runs`, usuário sem permissão, H10) e `dueOn` dos recados.
  - **Instantes:** `StrictDateTimeJsonApiTest`, com `expiresAt` da pré-autorização.
  - **Mapeador:** `StrictDateTimeJsonMapperTest`, sobre o `JsonMapper` do contexto, com aninhado, lista, extremos, `null`, ausente e datas que não existem no calendário.
  - **Offline:** `OfflineSyncTest`, com número rejeitado sem efeito e a mesma recusa no reenvio; com ISO, é aplicado.
  - **Jobs:** `ScheduledJobErrorLogTest`, com o job real `IdempotencyKeyPurgeJob.purgeExpiredKeys` e a trava falhando com sentinela na mensagem e na causa. O vermelho vinha do `TaskUtils$LoggingErrorHandler`. Cobre também falha no corpo, `Error`, `toString()` que lança, agendador único, prefixo `scheduling-` e ouvinte no `applicationTaskExecutor`.
  - **Contrato:** `checkOpenApi` (no gate) e oasdiff acima.
- Decisões para o usuário (tomadas pela opção recomendada, revisáveis):
  - **(a)** O piso da faixa é `-4712-01-01` (limite do pgjdbc), e não `-4713-11-24`.
  - **(b)** "Registrado" no comando offline (H13 c) é lido como "devolvido com `REJECTED COMMAND_PAYLOAD_INVALID`, nunca descartado em silêncio", sem guardar a recusa. O texto do critério dizia "fica registrado". Guardar a recusa mudaria o protocolo da §9.
  - **(c)** A leitura estrita vai além do mínimo: recusa também array, epoch em texto, texto sem `T` num instante e espaço nas pontas. **`""` numa data ou num instante opcional passou a dar `400 INVALID_FORMAT`**; antes virava `null`. **Avisar o front desktop:** para "sem valor", omitir o campo ou mandar `null`.
  - **(d)** O logger `JdbcTemplateStorageAccessor` fica em `OFF`. O custo é que o "Unlock failed after 10 attempts" também some. A alternativa é um filtro que descarte só os eventos com exceção.
  - **(e) Regressão conhecida e aceita:** um recado gravado com `dueOn` = `+999999999-12-31` vira `infinity` e é relido como `LocalDate.MAX`. O cursor seguinte é recusado com `400 INVALID_CURSOR`, e o resto da lista fica inalcançável; antes da E18, a paginação seguia. Exige uma data absurda posta de propósito. A correção é recusar no corpo o que estiver fora do `StorableRange`.
  - **(f) Decisão nova (rodada 0 da revisão de arquitetura), que substitui o `spring.task.execution.mode: force` da execução perdida:** o `taskScheduler` tem `defaultCandidate = false`, a convenção do `EndOfDayExecutionConfiguration`. No Boot 4.1.2, o `applicationTaskExecutor` continua sendo criado, e o Boot não monta um segundo agendador. Deixam de existir os dois `Executor` padrão que o `force` deixava.
  - **(g)** Um instante continua aceitando deslocamento diferente de `Z` e `t`/`z` minúsculos (`ISO_INSTANT`); o roadmap põe isso fora de escopo.
- Notas para a próxima etapa:
  - **Para o `arquiteto`** (etapa de manutenção sugerida):
    - recusar no corpo as datas e os instantes fora do `StorableRange`. Resolve a (e) e o `500` com `dueOn` `+5874898-01-01`;
    - datas e instantes em **query string** seguem lenientes: aceitam o formato curto do locale (`13/11/25`) e epoch em ms num instante. Fora do alcance, por cima, dá `500` (`traces`, `audit-entries`, `card-pre-authorizations/expiring`); abaixo do piso, `-infinity`, sem erro. Não há `@PathVariable` de data;
    - a chave de `Map` de data ou instante segue leniente (o `KeyDeserializer` não é coberto). Hoje não há DTO de entrada com isso;
    - `LocalTime`, `LocalDateTime` e `OffsetDateTime` não entram na leitura estrita;
    - quatro jobs ainda logam a própria falha com a mensagem (`OutboxMetrics.kt:74`, `FileOrphanPurgeJob`, `ProvisionalReservations`, `InventoryHorizonJob`);
    - `JOSEException` sem captura em `FcmPushGateway.requestAccessToken`;
    - trocar o `OFF` do ShedLock por um filtro (d).
  - **Operação:**
    - manter `/actuator/scheduledtasks` fora da exposição: o Spring guarda o `Throwable` da última execução de cada `@Scheduled`, com a mensagem;
    - ao subir o ShedLock, rever o `KeepAliveLockProvider`, que no `master` loga a exceção.
  - **Menores restantes da revisão final:**
    - a cadeia de causas não tem teto (`ScheduledJobErrors.kt:92-98`);
    - comentários em `CursorKeyRangeApiTest.kt:78` (400 antes de 404) e `:112` ("o banco arredonda" não demonstrado);
    - KDoc do `StrictDateTimeJson` sem o limite da chave de mapa;
    - `?` em `problem["code"]` no `StrictDateTimeJsonApiTest`;
    - fim de linha na afirmação da trava em `ScheduledJobErrorLogTest.kt:73-75`.
  - **Processo:** nesta máquina, o `systemd-timesyncd` precisa ficar desligado. Com ele ligado, os testes de prazo do `endofday` caem em cascata.
- Capturas do brain: `00 Inbox/2026-11-17 - ZenyHotelManager - atualização m6-operacao-fechamento E18 (refação) - 6f1c16c9.md`.

---

## E20 — Entrada estrita no corpo, na query e no caminho, e `required` no contrato — #concluida

**Objetivo:** completar no `shared`, para todos os módulos, a leitura estrita da E18 (H13) e fazer o contrato declarar o que o servidor já exige. São cinco furos achados na E18 e na E01: (1) data ou instante fora do alcance do banco no **corpo** passa da validação (um recado com `dueOn` `+999999999-12-31` vira `infinity` e trava a paginação, a regressão aceita (e) da E18; `+5874898-01-01` dá 500); (2) data e instante em **query string e caminho** aceitam o formato curto do locale e epoch em ms, e fora do alcance dão 500 por cima e `-infinity` por baixo; (3) número fracionário num campo inteiro é truncado (`frequencyDays: 7.5` vira 7); (4) `""` num UUID opcional vira `null`, e um PUT com `checklistTemplateId: ""` apaga o vínculo em silêncio; (5) os schemas de request não marcam `required`, e os SDKs geram como opcionais campos que o servidor recusa ausentes.

**Por que agora:** decisão do usuário (H20 c): roda antes da E04, como a E18 rodou antes da governança, para que os endpoints, os comandos offline e os DTOs novos de E04 a E09 e da E22 já nasçam estritos e com o `required` certo. O item 5 é a quebra única de contrato aceita pelo usuário (H20 d); quanto mais cedo, menos schemas e clientes ela alcança.

**Dependências:** nenhuma dentro deste roadmap. Independente da E21. Se rodar numa branch separada de uma etapa que mude a API, espere conflito em `api/openapi.yaml`.

**Escopo:**

1. **Faixa no corpo:** `LocalDate` e `Instant` fora do `StorableRange` (`shared`, E18) no corpo JSON de `/api/v1` e nos payloads offline dão `400 VALIDATION_FAILED` com o erro no campo (offline: `REJECTED COMMAND_PAYLOAD_INVALID`, como na E18). Isso fecha a regressão (e) da E18.
2. **Query string e caminho:** `LocalDate` e `Instant` em parâmetro de query e de caminho só aceitam o texto ISO-8601 da ARCHITECTURE §7 e o alcance do `StorableRange`; o resto dá `400 VALIDATION_FAILED` com o parâmetro em `errors[]`, nunca 500 nem `-infinity`. Vale para os existentes (ex.: `traces`, `audit-entries`, `card-pre-authorizations/expiring` e o caminho `/api/v1/assignment-plans/{date}`) e para os futuros, sem código por endpoint.
3. **`LocalTime` estrito** (H21 d): só `HH:mm` ou `HH:mm:ss`, em texto, no corpo e nos payloads offline; o resto dá `400 VALIDATION_FAILED` no campo.
4. **Inteiro e UUID estritos no JSON:** número fracionário num campo inteiro e `""` num UUID (obrigatório ou opcional) dão `400 VALIDATION_FAILED` com `INVALID_FORMAT` no campo. "Sem valor" é omitir o campo ou mandar `null`, como já vale para datas desde a E18.
5. **`required` nos schemas de request** (H20 d): todo campo que o servidor exige no corpo (hoje recusado ausente com `400 REQUIRED` ou pela Bean Validation) sai `required` no `api/openapi.yaml`, em todos os módulos, de forma automática a partir do DTO, para os schemas futuros seguirem sem esforço. O comportamento do servidor não muda.

**Fora de escopo:**

- `LocalDateTime` e `OffsetDateTime` (nenhum DTO de entrada os usa, conferido em 2026-11-18) e a chave de `Map` de data ou instante (o `KeyDeserializer`; nenhum DTO de entrada a usa).
- Deslocamento diferente de `Z` e `t`/`z` minúsculos num instante (decisão (g) da E18).
- `ChecklistsApi.template` revelar existência e estado de um modelo a quem tem `housekeeping.config.manage` (handoff da E01; impacto baixo, aceito).
- Os Menores restantes da revisão final da E18.

**Critérios de aceite:**

- [x] **Faixa no corpo:** no `POST` de recado (`dueOn`) e em pelo menos um corpo com `Instant`, o valor imediatamente fora de cada extremo do `StorableRange` dá `400 VALIDATION_FAILED` no campo, e os extremos são aceitos; o mesmo valor num comando offline volta `REJECTED COMMAND_PAYLOAD_INVALID`. Teste vermelho antes da correção com `+5874898-01-01`.
- [x] **Query e caminho:** em `traces`, `audit-entries`, `card-pre-authorizations/expiring` e `/assignment-plans/{date}`, o formato curto do locale (`13/11/25`), epoch em ms e o valor fora do alcance por cima e por baixo dão `400 VALIDATION_FAILED` com o parâmetro em `errors[]`, e o ISO continua aceito. Teste vermelho antes da correção em pelo menos um parâmetro de query e no de caminho.
- [x] **`LocalTime`:** num corpo com `LocalTime` (ex.: a política do hotel), número, `"9:5"`, `"09:05:00Z"` e `""` dão `400 VALIDATION_FAILED` no campo, e `"09:05"` e `"09:05:30"` são aceitos.
- [x] **Inteiro:** `frequencyDays: 7.5` e `credits: 1.5` dão `400 VALIDATION_FAILED` com `INVALID_FORMAT` no campo; `7` continua aceito. Teste vermelho antes da correção.
- [x] **UUID:** um PUT de tipo de limpeza com `checklistTemplateId: ""` dá `400 VALIDATION_FAILED` com `INVALID_FORMAT` e não remove o vínculo; com `null`, remove. Teste vermelho antes da correção.
- [x] **Mapeador:** um teste sobre o `JsonMapper` do contexto da aplicação recusa os formatos dos itens 3 e 4, aninhados e em lista.
- [x] **`required`:** num teste sobre o contrato gerado, os campos obrigatórios de pelo menos `CleaningTypeRequest` (`credits` e os demais exigidos) e de um request de cada módulo com escrita saem em `required`, e nenhum campo opcional sai `required`.
- [x] **Contrato:** `./gradlew checkOpenApi` passa, e `.github/scripts/check-api-breaking.sh` só acusa `ERR` do tipo "propriedade de request passou a ser obrigatória"; a lista sai no handoff. Nenhum outro `ERR`.
- [x] Build e testes passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → só os `ERR` de `required` do item 5, listados no handoff (H20 d).

**Revisão humana:** sim. O item 5 muda o contrato de todos os requests, e os SDKs dos clientes passam a exigir os campos; o usuário confere a lista dos `ERR` antes de o lote seguir.

**Notas para o plano:** H13, H20 (c e d) e H21 (d); handoff da E18 ("Decisões para o usuário" (c) e (e) e "Notas para a próxima etapa") e handoff da E01 ("Para o `arquiteto`"). A regra de leitura estrita e a extensão desta etapa já estão na ARCHITECTURE §7, linha "Datas e valores", e a decisão do `required` na linha "Contrato" (o plano só as cumpre e, no fim, troca "a cumprir pela M6 E20" por "implementada"). A leitura estrita do corpo mora em `shared/infrastructure/json/StrictDateTimeJson.kt`; query e caminho são conversão do Spring MVC, não do Jackson. O `SerproFnrhWire` tem mapeador próprio e fica fora. Testes que enviam fracionário, `""` ou formato curto para entrada legítima são dívida de teste e mudam junto. **Avisar o front desktop** (como na E18): `""` num UUID passa a dar `400`, e o SDK regenerado passa a exigir os campos `required`. O `ERR` do item 5 aparece no `check-api-breaking.sh` até a etapa entrar na `main`; o usuário o verá uma vez antes do push.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- A conversão estrita de query pode recusar algo que um teste ou o próprio servidor manda. Mitigação: build completo com os testes de todos os módulos; os ajustes de teste ficam no handoff.
- O `required` gerado pode marcar um campo que o servidor aceita ausente (ex.: com padrão). Mitigação: o critério confere que nenhum opcional sai `required`, e a lista dos `ERR` vai para a revisão humana.

**Rollback / migração:** sem migração. Reverter é tirar as regras do `shared`; o `required` volta atrás com outra quebra de contrato (H20 d).

**Evidências e handoff:**

- Plano: `docs/superpowers/plans/2026-11-18-m6-operacao-fechamento-e20.md`, com 3 fatias. Revisão de arquitetura: aprovada com notas na rodada 1 (`revisor-arquitetura`, opus). Na rodada 0 foi bloqueada por 2 Importantes: o contrato já marcava `required` em propriedades com valor padrão (a D1 olhava só um sentido), e havia schemas usados no request e na resposta (D5).
- **Premissa do item 5 corrigida (D1):** o contrato **já** marcava `required` nos campos com `@NotNull`, `@NotBlank` e `@NotEmpty` e em toda propriedade Kotlin não anulável, inclusive as com valor padrão (111 dos 115 schemas de corpo). O item 5 virou uma regra única nos dois sentidos, e o resultado foi o contrário do previsto pela H20 d: **nenhum `required` acrescentado** e 34 retirados. A quebra única aceita pela H20 d não foi usada.
- Commits: branch `roadmap/m6-operacao-fechamento`, de `bc75aee` (início) a `a256943`.
  - Task 1 (corpo JSON estrito): `1ed50ce` e `98f52ad` (desenvolvedor).
  - Task 2 (query e caminho): `2fcaa68` (desenvolvedor) e `4ec1e34` (testador, dois testes de ataque ao contrato novo).
  - Task 3 (`required`): `2edfaac` e `6b21dec` (desenvolvedor).
  - Rodada 1 do fim da etapa (gate vermelho): `8a9ffd2` (desenvolvedor) e `4e4e826` (testador).
  - Testador da etapa: `d034e4b`, só `src/test`.
  - Rodada de correção da revisão final: `6b7e8bb` e `a256943` (desenvolvedor).
  - Cada fatia passou por desenvolvedor → verificação mecânica → `revisor-seguranca`, todas aprovadas sem Crítico nem Importante na própria fatia.
- Entregue:
  - **Corpo e offline:** `LocalDate` e `Instant` fora do `StorableRange` dão `400 VALIDATION_FAILED` com `INVALID_FORMAT` no campo (offline: `REJECTED COMMAND_PAYLOAD_INVALID`). Isso fecha a regressão (e) da E18. `LocalTime` só aceita `HH:mm` ou `HH:mm:ss`. Número fracionário (inclusive `7.0` e notação científica) em `Int`/`Long`/`Short` dá `INVALID_FORMAT`. `""` ou texto em branco em `Int`, `Long`, `Short`, `Boolean` e UUID dá `INVALID_FORMAT`, e o UUID só aceita a forma canônica de 36 caracteres. Um primitivo Kotlin ausente ou `null` dá `REQUIRED`, e não mais `INVALID_FORMAT`.
  - **Envelope offline:** `deviceClock` e `occurredAt` (`@UnboundedInstant`) mantêm o formato estrito, mas ficam fora da faixa, para a checagem de plausibilidade os recusar **por comando** com `REJECTED DEVICE_TIME_INVALID`, como o protocolo da §9 documenta.
  - **Query e caminho:** conversores estritos de `LocalDate` e `Instant` para todo `@RequestParam` e `@PathVariable` (`shared/web/StrictParameterFormats.kt`): só ISO e dentro do `StorableRange`; o resto dá `400` com o parâmetro em `errors[]`. Saiu o único `@DateTimeFormat`, que aceitava deslocamento. `?param=` vazio num opcional segue "ausente" (D2). O período de query convertido em instante é limitado ao alcance do `timestamptz` (`StorableRange`), para não dar 500 entre `+294277` e o teto da data.
  - **`required`:** `OpenApiCustomizer` `RequiredRequestProperties` no `shared`. Um campo é obrigatório quando tem `@NotNull`, `@NotBlank` ou `@NotEmpty` no campo ou no getter, ou quando é uma propriedade Kotlin não anulável sem valor padrão. O resto não sai `required`. Os schemas que alguma resposta alcança não perdem `required` (D5). Nos DTOs **exclusivos** de alteração ou substituição, o valor padrão que sobrescreveria o estado gravado saiu, e o campo ficou obrigatório; o mesmo vale quando o domínio recusa o próprio padrão (decisão I3, abaixo). `RequiredRequestPropertiesContractTest` prende a regra para todos os schemas futuros.
- Revisão final: `revisor-seguranca` (opus) sobre `6a43f35..4e4e826`, "With fixes", 0 Crítico e 3 Importantes:
  - I1: 500 por data de query convertida em instante;
  - I2: UUID em branco virava `null`;
  - I3: `required` tirado de campos cuja ausência redefine estado.
  - Todos foram corrigidos em `6b7e8bb` e `a256943`. A re-revisão escopada (`revisor-seguranca`, sonnet) sobre `4e4e826..a256943` deu os quatro achados por resolvidos, sem Crítico nem Importante novo, e julgou justificado o apontamento de integridade.
- Verificação:
  - **Gate:** `./gradlew build` em `a256943` → BUILD SUCCESSFUL em 17m 22s, com 4349 testes, 0 falhas e 0 ignorados (contagem dos XML do JUnit). Antes, o gate em `6b21dec` foi vermelho, com 3 falhas: dois testes do `endofday` com a expectativa anterior à E20, e um teste que listava recados de outros testes no banco compartilhado. A primeira tentativa desse gate foi interrompida por uma queda de energia e rodou de novo.
  - **oasdiff:** "Nenhuma mudança incompatível (nível ERR) em /api/v1". Contra a base `6a43f35`, o contrato perdeu 34 `required` (todos campos com valor padrão) e não ganhou nenhum.
  - **Scanner:** `osv-scanner` 2.7.1 com `-L` em `gradle.lockfile` (426 pacotes) e `buildscript-gradle.lockfile` (74). Resultado: 0 Critical/High, 2 Medium e 3 Low, todos anteriores, os mesmos da E18. Lockfiles e catálogo sem mudança.
  - **Integridade:** `check_test_integrity.py --base 6a43f35` → exit 1, com 1 apontamento: `DEV_TESTE_DO_TESTADOR` em `6b7e8bb`, no `StrictParametersApiTest`. Foi o comentário do 500 conhecido do `expiring`, que deixou de ser verdade com a I1; a mudança foi pedida pelo orquestrador e julgada pela re-revisão. Em cada fatia, exit 0.
- Critérios verificados:
  - **Faixa no corpo:** `StrictDateTimeJsonApiTest` (`dueOn` do recado e `expiresAt` da pré-autorização, extremos e vizinhos); vermelho antes com `+5874898-01-01` (500). Offline em `OfflineSyncTest`.
  - **Query e caminho:** `StrictParametersApiTest` (`traces`, `audit-entries`, `card-pre-authorizations/expiring`, `checklist-reports`, `/assignment-plans/{date}`, com `%2B` codificado); vermelho antes em 5 de 7 casos.
  - **`LocalTime`:** `StrictDateTimeJsonApiTest` (`checkInTime` da política) e `StrictDateTimeJsonMapperTest`.
  - **Inteiro:** `frequencyDays: 7.5` e `credits[0].credits: 1.5`; vermelho antes (201).
  - **UUID:** `PUT` de tipo de limpeza com `checklistTemplateId: ""` e `" "`; vermelho antes (200, com o vínculo perdido).
  - **Mapeador:** `StrictDateTimeJsonMapperTest`, sobre o `JsonMapper` do contexto, aninhado e em lista.
  - **`required`:** `RequiredRequestPropertiesContractTest` e `RequiredRequestPropertiesApiTest` (`maxAdults` ausente ⇒ `REQUIRED`; `waivePenalty` ausente aceito).
  - **Contrato:** `checkOpenApi` (no gate) e oasdiff acima.
- Decisões para o usuário (tomadas pela opção recomendada, revisáveis; revisão humana substituída por autorização do usuário neste lote):
  - **(a) D1, premissa corrigida:** o item 5 não acrescentou `required` (já existia) e retirou 34 de campos com valor padrão. O SDK regenerado passa a tratá-los como opcionais.
  - **(b) D5:** schemas usados também em resposta (`ChecklistSectionDto`, `ChecklistItemDto`, `ApplicabilityRuleDto` e outros, 17 no total) não perdem `required`. Alternativas descartadas: separar os DTOs e aceitar o `ERR` de resposta.
  - **(c) I3, refinada:** nos DTOs **exclusivos** de alteração ou substituição, o valor padrão que sobrescreveria o estado saiu, e o campo ficou obrigatório: `TransactionCodeUpdateRequest.serviceChargeApplies`, `ReplaceChecklistAnswersRequest.answers`, `UpdateChecklistTemplateRequest.sections`/`applicability`, `PriceValuesRequest.children`, `RatePlanUpdateRequest.packageIds`, `ReservationChangeRequest.childAges`/`nightlyRates`, `RoomBlockChangeRequest.allocations` e `SchedulePatternRequest.weekdays`. O mesmo vale quando o domínio recusa o padrão: `CreateChecklistTemplateRequest.sections`, `BookingRequest.reservations`, `RoomingListRequest.reservations`, `OverbookingLimitRequest.roomTypeIds` e `RoomBlockRequest.allocations`. Esses campos continuam `required` como na `main`. Os DTOs compartilhados entre criação e alteração (`CashPointRequest.blindClose`, `RoomTypeRequest`/`RoomRequest.featureIds`, `displayOrder`, `PackageRequest.includedInRate`, `GuestProfileRequest.vip`, `ChecklistRoutineRequest.weekdays`) mantêm o padrão: num `PUT`, o campo omitido ainda é sobrescrito pelo padrão (ver "Para o `arquiteto`").
  - **(d) D3:** o `nullable: true` dos campos obrigatórios ficou; tirá-lo seria outro tipo de `ERR`.
  - **(e) Códigos de erro que mudam (avisar o front desktop):** `""` ou texto em branco em UUID, inteiro e booleano passa a dar `400 INVALID_FORMAT` (para "sem valor", omita o campo ou mande `null`); a forma base64 do UUID deixa de ser aceita; o primitivo ausente ou `null` passa de `INVALID_FORMAT` a `REQUIRED`; data fora do `StorableRange` no corpo de `POST /end-of-day/runs` passa de `409 END_OF_DAY_DATE_MISMATCH` a `400 INVALID_FORMAT` (o teto exato segue 409); e o SDK regenerado trata como opcionais os 34 campos com valor padrão.
  - **(f) Envelope offline:** `deviceClock`/`occurredAt` fora da faixa continuam `REJECTED DEVICE_TIME_INVALID` por comando, e não `400` no lote. Um instante além de `Instant.MAX` (erro de formato) dá `400` no lote, como na E18.
  - **(g) D4:** inteiro em texto (`"7"`) segue aceito.
- Notas para a próxima etapa:
  - **Para o `arquiteto`:**
    - separar os DTOs de alteração dos compartilhados de criação e alteração, cujo padrão sobrescreve o estado num `PUT` com o campo omitido (`CashPointRequest`, `RoomTypeRequest`, `RoomRequest`, `PackageRequest`, `GuestProfileRequest`, `ChecklistRoutineRequest`);
    - o `nullable: true` dos campos obrigatórios (D3);
    - o `TraceStoreTest` grava recados com setor inexistente no banco compartilhado e não os apaga; o `IllegalStateException` de `TraceReferences.views` só é alcançável por SQL ou store direto;
    - a regra ISO + faixa está em dois lugares (Jackson e MVC);
    - o reconhecimento do primitivo ausente depende do texto "Missing required creator property" do Jackson (preso pelo `RequiredRequestPropertiesApiTest`).
  - **Menores restantes da revisão final e das fatias:** `@param:` e `@field:` duplicados em `SyncCommandRequest`; `Byte` e `BigInteger` fora da D4 (nenhum DTO os usa); a regra do `required` duplicada entre o teste e a produção (de propósito); o gate não gravava o SHA no arquivo (corrigido a partir desta etapa); um padrão solto `includedInRate = false` em `PackageResponse` (`rates/web/RatesDtos.kt:363`, DTO de resposta, sem efeito no contrato), resquício da rodada final.
  - **Padrão para os DTOs novos (E04 em diante):** um campo opcional é anulável sem anotação ou tem valor padrão; num DTO só de alteração, o padrão não pode sobrescrever o estado.
- Capturas do brain: `00 Inbox/2026-11-19 - ZenyHotelManager - atualização m6-operacao-fechamento E20 - 27e226dd.md`.

---

## E21 — Erros de trava e logs restantes — #concluida

**Objetivo:** fechar as pendências de erro e de log que a E18 e a E01 deixaram para o `arquiteto`: (1) `40P01` (deadlock) e `55P03` (`lock_timeout`) que escapam de um caso de uso viram 500 no `ApiExceptionHandler`, e passam a dar `409 CONCURRENT_UPDATE` (H21 c); (2) quatro jobs ainda logam a própria falha com a mensagem (`OutboxMetrics`, `FileOrphanPurgeJob`, `ProvisionalReservations` e `InventoryHorizonJob`); (3) o logger `JdbcTemplateStorageAccessor` do ShedLock está em `OFF` (decisão (d) da E18), o que também esconde o "Unlock failed after 10 attempts"; (4) `FcmPushGateway.requestAccessToken` não captura a `JOSEException` de `SignedJWT.sign`.

**Por que agora:** decisão do usuário (H20 c): roda antes da E04, que cria as primeiras travas em cadeia (tarefa → execução → UH) fora do `endofday`. Com o `409` no `shared`, a E04, a E19 e as etapas seguintes não precisam tratar deadlock e `lock_timeout` uma a uma.

**Dependências:** nenhuma dentro deste roadmap. Independente da E20.

**Escopo:**

1. **Erros de trava:** `40P01` e `55P03` que chegam ao tratador RFC 9457 dão `409` com o código estável `CONCURRENT_UPDATE`, retentável, sem a mensagem nem o detalhe do banco. Os tratamentos próprios que já existem (o `55P03` da retomada do `endofday`) continuam. No protocolo offline, a mesma exceção continua sendo falha inesperada (`COMMAND_FAILED`, reenviar), que já é o comportamento retentável do protocolo.
2. **Logs dos jobs:** os quatro jobs logam a falha com o nome do job e só a classe da exceção e das causas, como o tratador do agendador da E18 (`shared/infrastructure/scheduling/ScheduledJobErrors.kt`).
3. **ShedLock:** o `OFF` do `JdbcTemplateStorageAccessor` dá lugar a um filtro que descarta só os eventos com exceção desse logger; as mensagens sem exceção (como o "Unlock failed after 10 attempts") voltam a aparecer.
4. **FCM:** a `JOSEException` da assinatura vira a falha do gateway, sem a mensagem nem material de chave no log, e o envio do aviso segue o caminho de falha que o `notifications` já tem.

**Fora de escopo:** mudar o `KeepAliveLockProvider` ou subir a versão do ShedLock (nota de operação da E18); expor `/actuator/scheduledtasks`; os Menores da revisão final da E18.

**Critérios de aceite:**

- [x] **Deadlock:** um caso de uso de teste que provoca `40P01` de verdade (duas transações em ordem cruzada, coordenadas de forma determinística, como os testes de trava da E01) dá `409 CONCURRENT_UPDATE`, sem texto do PostgreSQL no corpo. Teste vermelho antes da correção (500).
- [x] **`lock_timeout`:** um caso de uso que espera uma linha travada além do `lock_timeout` dá `409 CONCURRENT_UPDATE`. Os testes de retomada do `endofday` continuam passando.
- [x] **Jobs:** cada um dos quatro jobs, com a falha forçada levando um sentinela na mensagem e na causa, gera log (saída capturada) com a classe e sem o sentinela. Teste vermelho antes da correção.
- [x] **ShedLock:** com o `LockProvider` falhando com sentinela, nenhum log do ShedLock traz o sentinela, e uma mensagem sem exceção do `JdbcTemplateStorageAccessor` aparece no log.
- [x] **FCM:** a `JOSEException` forçada na assinatura não escapa do gateway, e o log não traz a mensagem dela.
- [x] `./gradlew checkOpenApi` passa, e `.github/scripts/check-api-breaking.sh` não acusa `ERR` (o `409` novo só documenta o que era 500).
- [x] Build e testes passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Revisão humana:** não

**Notas para o plano:** H21 (c); handoff da E18 ("Decisões para o usuário" (d) e "Notas para a próxima etapa": `OutboxMetrics.kt:74`, os jobs e o FCM) e da E01 (os exemplos de `40P01`/`55P03`: PUTs cruzados de faxinas no mesmo local e `reactivate` do tipo concorrendo com a desativação). O código `CONCURRENT_UPDATE` está registrado na ARCHITECTURE §7, linha "Concorrência". O padrão de teste de trava determinístico (`pg_blocking_pids`) está em `ScheduledCleaningsTypeLockTest` (E01). O valor do `lock_timeout` da aplicação, se não houver um, é decisão do plano, registrada no handoff. Máquina de 14 GB: um Gradle por vez.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O filtro do ShedLock pode deixar passar uma mensagem com valor do banco. Mitigação: o critério usa sentinela na mensagem e na causa, como a E18.
- Um `409` genérico pode esconder um deadlock recorrente de verdade. Mitigação: o log do tratador leva a classe e o SQLSTATE, sem a mensagem, e a revisão final confere se há métrica.

**Evidências e handoff:**

- Plano: `docs/superpowers/plans/2026-11-19-m6-operacao-fechamento-e21.md`, com 3 fatias. Revisão de arquitetura (`revisor-arquitetura`, opus): aprovado na rodada 2.
  - Rodada 0: bloqueado por 5 Importantes. O teste do ShedLock não passava pelo filtro; o `409` retentável ficaria guardado pela idempotência; a `UnexpectedRollbackException` não traz a causa; o FCM não tinha ponto de injeção; um controller de teste vazaria para o contrato.
  - Rodada 1: bloqueado por 1 Importante, o sinal da liberação da chave e o caso negativo.
- Commits: branch `roadmap/m6-operacao-fechamento`, de `b141612` (início) a `bb79b5b`.
  - Task 1 (409, idempotência, `lock_timeout`): `5d53d55`, `b916ac0` e, na rodada 1, `a6d9af7` (desenvolvedor).
  - Task 2 (`ExceptionClasses`, jobs e FCM): `e9c9488` e `eb7d1df` (desenvolvedor).
  - Task 3 (filtro do ShedLock): `5228dcc` (desenvolvedor).
  - Testador da etapa: `7a31dd4` e `bb79b5b`, só `src/test`.
  - Cada fatia passou por desenvolvedor → verificação mecânica → `revisor-seguranca`.
    - A Task 1 teve 1 Importante: o log da falha ao liberar a chave levava o throwable. Foi corrigido na rodada 1.
    - As Tasks 2 e 3 foram aprovadas sem Importante.
- Entregue:
  - **`409 CONCURRENT_UPDATE`:** o `ApiExceptionHandler` reconhece `40P01` e `55P03` pelo SQLSTATE de qualquer `java.sql.SQLException` da cadeia, inclusive o `getNextException`, com teto de 64 elos.
    - Responde `409`, retentável, sem a mensagem do banco.
    - Loga o método, o caminho, a cadeia de classes e o SQLSTATE.
    - Conta as ocorrências em `zeny.api.concurrent.updates{sqlstate}`.
    - A `UnexpectedRollbackException`, que não traz a causa, segue `500`. A retomada do `endofday` mantém o tratamento próprio. No offline, segue `COMMAND_FAILED`.
  - **Idempotência (D4):** o tratador grava um atributo de requisição, e o `IdempotencyFilter` libera a chave só com ele. O reenvio com a mesma chave executa de novo. Um `409` de outro código continua guardado.
  - **`lock_timeout` (D1):** `10s` em toda conexão da aplicação, pelo parâmetro de partida `options` do `data-source-properties`, que sobrevive a `RESET ALL`. O Flyway usa um `SimpleDriverDataSource` próprio e não herda o prazo; um teste trava isso.
  - **`ExceptionClasses.chain`** (`shared`, D2): devolve `A <- B <- C`, com proteção contra ciclo e teto de 10 elos. É usado pelo agendador, pelos quatro jobs (`OutboxMetrics`, `FileOrphanPurgeJob`, `ProvisionalReservations`, `InventoryHorizonJob`), pelo FCM e pelos logs do `409`.
  - **FCM:** a `JOSEException` e a `RuntimeException` da montagem da asserção são capturadas e viram o `TemporaryFailure` que já existia. O ponto de injeção é o parâmetro `signerFactory` do construtor.
  - **ShedLock:** o `OFF` deu lugar ao `ShedLockLogFilter`, um `TurboFilter` que descarta os eventos com exceção do `JdbcTemplateStorageAccessor`, tanto no `Throwable` do evento quanto nos `params`.
    - O instalador é um listener em `META-INF/spring.factories` e sobrevive à reinicialização do logging do Boot, inclusive na saída ECS do `prod`.
    - O "Unlock failed after 10 attempts" voltou a aparecer.
- Revisão final: `revisor-seguranca` (opus) sobre `c2c2ab9..5228dcc`. "Ready to merge? Yes", com ressalvas, 0 Crítico e 0 Importante de código.
  - A ressalva era fechar o inventário dos `@Idempotent`. O testador o fez: só o fechamento do dia confirma efeito antes de um ponto em que um erro de trava pode escapar. Nesse caso, o reenvio cai em `409 END_OF_DAY_RUN_EXISTS` (RN-21), sem efeito duplicado.
  - A re-revisão escopada (`5228dcc..bb79b5b`) deu o inventário e os testes do testador por atendidos, sem Crítico nem Importante; restaram 3 Menores de teste (um `object Logger` que sombreia o nome; o teste negativo sem a classe da exceção; o sentinela no primeiro teste ECS).
- Verificação:
  - **Gate:** `./gradlew build` em `bb79b5b` → BUILD SUCCESSFUL em 16m 29s, com 4378 testes e 0 falhas. Em `7a31dd4`, o gate foi vermelho por um teste novo do testador que mexia no logging global e dependia da ordem das classes; o testador o reescreveu com um `LoggerContext` próprio (`bb79b5b`). Em `5228dcc`, BUILD SUCCESSFUL com 4373 testes.
  - **oasdiff:** "Nenhuma mudança incompatível (nível ERR) em /api/v1". O contrato só mudou a descrição do `code`.
  - **Scanner:** `osv-scanner` 2.7.1 (426 + 74 pacotes): 0 Critical/High, 2 Medium e 3 Low, todos anteriores. O `logback` de runtime é o 1.5.38. Lockfiles e catálogo sem mudança.
  - **Integridade:** `check_test_integrity.py --base c2c2ab9` → exit 0, sem apontamento; exit 0 também em cada fatia.
- Critérios verificados:
  - **Deadlock:** `ConcurrentUpdateApiTest`. Duas requisições em ordem cruzada, coordenadas por `pg_blocking_pids`, dão `[200, 409]`, sem texto do PostgreSQL no corpo nem no log. O vermelho antes era `500`.
  - **`lock_timeout`:** `ConcurrentUpdateApiTest`, com `SET LOCAL` de 200 ms e `SHOW lock_timeout` = `10s`. Os testes do `endofday` seguem verdes.
  - **Jobs:** `JobFailureLogTest`, com sentinela na mensagem, na causa e nas suprimidas. O vermelho antes vinha do sentinela no log.
  - **ShedLock:** `ShedLockLogFilterTest` (as duas formas, um `JdbcTemplateLockProvider` real no ramo `error("Unexpected exception", e)` do insert, e uma mensagem sem exceção visível) e `ShedLockLogFilterStructuredOutputTest` (saída ECS do `prod`).
  - **FCM:** `FcmPushGatewayTest`, com `JOSEException` e `IllegalArgumentException` com sentinela. O vermelho antes era a exceção escapando.
  - **Contrato:** `checkOpenApi` (no gate) e o oasdiff acima.
- Decisões para o usuário (tomadas pela opção recomendada, revisáveis):
  - **(a) D1:** `lock_timeout` de 10 s em toda conexão da aplicação. Uma requisição que espera uma trava mais que isso recebe `409 CONCURRENT_UPDATE`, e uma etapa do fechamento nessa situação falha e fica retomável. Um pooler na frente do banco (PgBouncer em modo transação, proxies gerenciados) pode ignorar o parâmetro `options`; ver a nota ao `arquiteto`.
  - **(b) D4:** a chave de idempotência é liberada no `409 CONCURRENT_UPDATE`, e o reenvio com a mesma chave executa de novo. Isso muda o "guardada por 24h" da §7 nesse caso. Alternativa descartada: manter guardada e pedir uma chave nova.
  - **(c)** O `signerFactory` do FCM ficou como parâmetro público do construtor, e não `internal`.
- Notas para a próxima etapa:
  - **Para o `arquiteto`:**
    - caminhos que ainda logam a exceção inteira, com o texto do PostgreSQL (sem o DETAIL): `OfflineCommands.kt:305` (`COMMAND_FAILED`, que passa a disparar mais com o `lock_timeout`), `IdempotencyFilter.kt:202` e `:217`, e o caminho `500` do `ApiExceptionHandler`;
    - um guarda de partida que confira `SHOW lock_timeout` = `10s` (por causa de poolers);
    - o Flyway não herda `SET TIME ZONE`, `logServerErrorDetail` nem `lock_timeout` do pool, o que já era assim antes;
    - `40001` (serialização) e `57014` (`statement_timeout`) ficaram fora do `409`;
    - o filtro do ShedLock depende da API clássica do SLF4J; rever ao subir o ShedLock, junto do `KeepAliveLockProvider`.
  - **Menores restantes:**
    - o `seen` é compartilhado entre a cadeia de causas e o `nextException`;
    - o contador é montado a cada resposta;
    - a cadeia cortada no teto não tem marcador;
    - o `JobFailureLogTest` fica no pacote `shared` e importa módulos;
    - o instalador do filtro não faz nada (fail-open) se o backend de logging não for o Logback.
  - **Para a E04 e seguintes:** um deadlock ou `lock_timeout` que escapa vira `409 CONCURRENT_UPDATE` sem código local; usar `ExceptionClasses.chain` em todo log de falha.
- Capturas do brain: `00 Inbox/2026-11-19 - ZenyHotelManager - atualização m6-operacao-fechamento E21 - 52521260.md`.

---

## E01 — Tipos de limpeza, créditos e faxinas programadas — #concluida

**Objetivo:** criar o módulo `housekeeping` com a configuração de tipos de limpeza (RF-GOV-01) — créditos por tipo de UH, modelo de checklist, exigência de inspeção, prioridade padrão e regra de troca de enxoval — e de faxinas programadas por UH ou área (RF-GOV-10).

**Por que agora:** geração, distribuição e inspeção dependem dessas regras.

**Dependências:** nenhuma dentro deste roadmap; exige o gate de M5 `#concluida`. Independente de E07 e E10 (conflito esperado em `api/openapi.yaml` se rodarem em branches separadas).

**Escopo:** pacote `housekeeping` (a criar): `CleaningType`, `CreditRule` (por `RoomType` de `rooms`), vínculo ao `ChecklistTemplate` de `checklists`, `ScheduledCleaning` (local, tipo, frequência em dias, próxima data); endpoints CRUD com permissões próprias; migração no schema do módulo; carga inicial dos tipos da SPEC (saída, permanência, retoque, faxina pesada, pré-chegada, turndown desativado — H9).

**Fora de escopo:** geração das tarefas (E02); movimentação de enxoval (M9).

**Critérios de aceite:**

- [x] CRUD de `CleaningType` com créditos por tipo de UH, modelo de checklist, `requiresInspection`, prioridade e regra de troca de enxoval, coberto por testes de integração.
- [x] Crédito para tipo de UH inexistente → `422` com `code` estável (**substituído pela H16:** `400 CLEANING_TYPE_UNKNOWN_ROOM_TYPE`); modelo de checklist de contexto incompatível → `422`.
- [x] CRUD de `ScheduledCleaning` calcula a próxima data a partir da frequência.
- [x] Carga inicial cria os tipos da SPEC, com turndown desativado.
- [x] Escrita exige permissão e é auditada.
- [x] `ModularityTest`, build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato (`./gradlew checkOpenApi`, tabela do `CLAUDE.md`) → sucesso.

**Revisão humana:** não

**Notas para o plano:** requisitos em SPEC RF-GOV-01, RF-GOV-10 e §8.2.10; H9 (turndown carregado desativado). `housekeeping` usa `rooms` e `checklists` só pelas APIs públicas; o `checklists` já aceita tipo de UH e tipo de limpeza como IDs opacos na aplicabilidade dos modelos (`cleaningTypeIds`; nota `ZenyHotelManager - Módulo checklists`), além do vínculo direto ao modelo pedido no escopo: o plano decide qual dos dois escolhe o modelo da tarefa e registra no handoff. Armadilha apontada no formato antigo: um tipo de limpeza sem crédito para algum tipo de UH ativo precisa gerar aviso, não passar calado.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Nenhum relevante.

**Evidências e handoff:**

- **Plano:** `docs/superpowers/plans/2026-11-17-m6-operacao-fechamento-e01.md`, com 3 fatias.
  - Revisão de arquitetura (`revisor-arquitetura`, opus): aprovada com notas na rodada 1.
  - Na rodada 0, foi bloqueada por 4 Importantes: o `ModularityTest` da Task 1 dependia de usos das Tasks 2 e 3; havia critérios sem comando; a Task 3 tinha Áreas e dependência incompletas; e `Riscos:` não citava a migração nem o piso do `lastDoneOn`.
- **Refação (H15):** a execução anterior desta etapa se perdeu com a branch. Esta execução refaz o comportamento da nota do brain `ZenyHotelManager - Módulo housekeeping`, com uma diferença: a D8 foi substituída pela H16, de modo que referência inexistente no corpo dá `400 …UNKNOWN_…`.
- **Commits:** branch `roadmap/m6-operacao-fechamento`, de `327f897` (início e plano) a `83a5839`.
  - Task 1 (módulo, tipos e créditos): `fe971a7` e `4da1643` (desenvolvedor; a guarda de NUL foi para o `PageCursor.string`, ruling); `5e49b34` (testador, `AuthorizationTest`); `a8de977` (rodada 1: `@NotNull` nos créditos e frase do PUT).
  - Task 2 (vínculo ao checklist): `978c668`.
  - Task 3 (faxinas programadas): `72c3602`.
  - Testador da etapa: `df82fad`.
  - Correção final: `83a5839` (`credits` obrigatório).
- **Entregue:**
  - **Módulo** `com.zeny.hotel.housekeeping`, que declara `["shared", "hotel", "rooms", "checklists"]` (subconjunto da linha §3.1, sem aresta nova).
  - **Schema** `housekeeping`, com as migrações `V2026_11_17_1000__create_cleaning_types.sql`, `V2026_11_17_1001__grant_cleaning_types_to_app_role.sql`, `V2026_11_17_1002__create_scheduled_cleanings.sql` (inclui o trigger `cleaning_type_system_key_never_changed`) e `V2026_11_17_1003__grant_scheduled_cleanings_to_app_role.sql`.
    - Tabelas `cleaning_type`, `cleaning_type_credit` e `scheduled_cleaning`.
    - Trigger `refuse_delete` nas duas tabelas que só se desativam; DELETE só em `cleaning_type_credit`.
    - Índices únicos parciais `cleaning_type_system_key_uk` e `scheduled_cleaning_active_uk` (`NULLS NOT DISTINCT WHERE active`).
  - **Carga D5:** `CHECKOUT`, `STAYOVER`, `TOUCH_UP`, `DEEP_CLEAN` e `PRE_ARRIVAL` ativos, e `TURNDOWN` inativo (H9). O código de cada um é igual à `key`, e a `key` é imutável em três níveis: `updatable=false`, índice e trigger.
  - **API `/api/v1/cleaning-types`:**
    - `GET` com cursor (`code`, `id`) e filtro `active`; `GET /{id}` com `ETag`;
    - `POST` (`201`, `Location`, `ETag`) e `PUT` com `If-Match`;
    - `/deactivate` e `/reactivate`.
    - A resposta traz `key` (`x-extensible-enum`) e `missingCreditRoomTypeIds`.
  - **API `/api/v1/scheduled-cleanings`:**
    - `GET` com cursor (`nextDueOn`, `id`) e filtro `active`; `GET /{id}`;
    - `POST`, e `PUT`, que muda só o tipo e a frequência;
    - `/deactivate` e `/reactivate`.
    - Nenhuma das duas APIs tem `DELETE`.
  - **Regras:**
    - D1: vínculo opcional a modelo de checklist `HOUSEKEEPING` ativo e publicado, conferido só quando o vínculo é novo.
    - D6: `missingCreditRoomTypeIds`.
    - D7: `nextDueOn`.
    - Faixas: prioridade 1–5, enxoval 1–60 ou nulo, `credits` 1–100, até 200 por corpo, frequência 1–365.
    - `lastDoneOn` acima da data operacional dá `422`; abaixo de `-4712-01-01` dá `400 VALIDATION_FAILED INVALID_VALUE`.
    - Trava sempre na ordem faxina → tipo (`FOR NO KEY UPDATE`), e a desativação do tipo com faxina ativa dá `422`.
  - **`ChecklistsApi.template(id)`:** nova, devolve `ChecklistTemplateSummary(id, context, active, published)`.
  - **Permissões e auditoria:** `housekeeping.config.read` e `housekeeping.config.manage`, só `ALL`, nos modelos Gerente e Governanta, com a autorização antes de qualquer recusa que revele estado. Trilha `housekeeping.cleaning-type.*` e `housekeeping.scheduled-cleaning.*`.
  - **`shared`:** `PageCursor.string` recusa chave com NUL (`400 INVALID_CURSOR`). Vale para todas as listas por texto, que antes davam 500.
- **Revisões:**
  - Task 1: 1 Importante (item `null` em `credits`). Na rodada 1, constatou-se que o Jackson Kotlin já recusa com 400; o `@NotNull` ficou como defesa, com teste. A re-revisão aprovou.
  - Tasks 2 e 3: aprovadas só com Menores.
  - Revisão final (`revisor-seguranca`, opus, `c312b25..72c3602`): "Ready to merge? Yes", 0 Crítico e 0 Importante.
  - Rodada única de correção: o Menor 1 (PUT sem `credits` apagava os créditos em silêncio) virou `400 REQUIRED`.
  - Re-revisão escopada (`72c3602..83a5839`, com os testes do testador): aprovada.
- **Verificação:**
  - **Gate:** `./gradlew build` em `83a5839` → BUILD SUCCESSFUL em 15m 9s, com 4170 testes, 0 falhas e 0 ignorados (contagem dos XML do JUnit). Antes da correção, o mesmo em `72c3602`: 4157 testes, 0 falhas.
  - **oasdiff:** sem breaking change; o contrato só ganhou caminhos e schemas.
  - **Scanner:** `osv-scanner` 2.7.1, 426+74 pacotes, 0 Critical/High, 2 Medium e 3 Low, todos anteriores à etapa. Lockfiles e catálogo sem mudança.
  - **Integridade:** `check_test_integrity.py --base c312b25` → exit 0, sem apontamento, também em cada fatia.
- **Critérios verificados:**
  - (1) CRUD de tipos: `CleaningTypesApiTest`, `CleaningTypesChecklistLinkTest`, `CleaningTypesBoundaryTest` e `CleaningTypesChecklistLinkRefusalTest`.
  - (2) Referências e regras:
    - crédito para tipo de UH inexistente dá `400 CLEANING_TYPE_UNKNOWN_ROOM_TYPE` (H16 substitui o "`422`" do critério);
    - modelo de contexto incompatível dá `422 CLEANING_TYPE_CHECKLIST_CONTEXT_INCOMPATIBLE`;
    - modelo inativo ou não publicado dá `422`;
    - modelo inexistente dá `400 CLEANING_TYPE_UNKNOWN_CHECKLIST_TEMPLATE`.
  - (3) `nextDueOn`: `ScheduledCleaningsApiTest`, `ScheduledCleaningsAttackTest` e `ScheduledCleaningDomainTest`. A corrida tipo × faxina está em `ScheduledCleaningsTypeLockTest` e `ScheduledCleaningsTypeSwapLockTest`, determinísticos por `pg_blocking_pids`.
  - (4) Carga inicial: `HousekeepingSchemaTest`.
  - (5) Permissão e auditoria: `HousekeepingPermissionsTest` e `AuthorizationTest`.
  - (6) `ModularityTest`, `DatabaseRolesTest`, gate e `checkOpenApi`.
- **Decisões para o usuário** (tomadas pela opção recomendada, revisáveis):
  - **H16 (decidida pelo usuário):** `400 …UNKNOWN_…` para referência inexistente.
  - **Herdadas da execução perdida, sem confirmação:** D1 (vínculo `0..1`), D5 (valores da carga), o limite de 200 créditos e a reativação que mantém o `nextDueOn` antigo.
  - **Nova nesta execução:** a reativação da faxina confere também se o **local** está ativo, o que acrescenta um `422` ao `/reactivate`.
  - **Nova nesta execução:** `credits` passou a ser obrigatório no corpo do tipo de limpeza. Omitido, dá `400`; `[]` explícito remove os créditos.
  - **Ordem observada:** um corpo malformado (`"credits":[null]`) dá 400 antes de 403, porque a validação de forma vem antes da permissão. Isso não revela estado.
- **Notas para a próxima etapa:**
  - **Para a E02:**
    - falta a porta `byKey(CleaningTypeKey)`; o índice já existe;
    - decidir o que gerar para um tipo da carga desativado (`TURNDOWN`);
    - filtrar `active` e ignorar local inativo ou inexistente, porque o `rooms` não desativa faxinas;
    - não gerar uma tarefa por dia enquanto houver uma aberta;
    - decidir o N de "UH vaga há N dias";
    - garantir chegada acima de permanência com prioridades editadas;
    - testes do desenvolvedor deixam faxinas ativas e válidas em UHs reais do banco compartilhado, que a varredura das vencidas vai encontrar.
  - **Para a E04:** `ScheduledCleaning.recordDone(date)` não atualiza `updatedAt` (a versão sobe), não confere se a faxina está ativa e não limita `date` ao `StorableRange`.
  - **Para o `arquiteto`** (manutenção transversal, como a E18):
    - o leitor JSON global trunca número fracionário em campo inteiro (`frequencyDays: 7.5` → 7, `credits: 1.5` → 1);
    - `""` num UUID opcional vira `null`, então um PUT com `checklistTemplateId: ""` remove o vínculo em silêncio;
    - `40P01` (deadlock) e `55P03` (`lock_timeout`) viram 500 no `ApiExceptionHandler`. Exemplo: PUTs cruzados de faxinas no mesmo local, ou `reactivate` do tipo concorrendo com a desativação, que dá 412 sem `If-Match`;
    - o contrato do projeto não marca `required` nos schemas de request (só em texto), então o SDK gera esses campos como opcionais;
    - `ChecklistsApi.template` revela existência, contexto e estado de um modelo a quem tem `housekeeping.config.manage` (impacto baixo).
  - **Menores aceitos:**
    - o trigger da `system_key` ficou na migração das faxinas (1002) e não foi movido, por causa do checksum;
    - a data operacional é lida sem trava na criação e no PUT da faxina;
    - uma consulta por tipo de UH novo no corpo (até 200);
    - validação dupla no `update` do tipo;
    - nome qualificado repetido em `ChecklistTemplates.kt`;
    - constantes de documentação e `HousekeepingAudit` espalhados (separar na E02);
    - nos testes, `blockedOnCleaningType` é genérico e há `.ok()` em `finally`.
- **Capturas do brain:** `00 Inbox/2026-11-18 - ZenyHotelManager - atualização m6-operacao-fechamento E01 (refação) - 3bd9134b.md`.

---

## E02 — Geração automática de tarefas — #concluida

**Objetivo:** gerar as tarefas de governança (RF-GOV-02) a partir de saídas (`GuestCheckedOut`), troca de UH (`RoomMoved`, UH de origem), permanências e chegadas da data (ao receber `BusinessDateAdvanced`), faxinas programadas vencidas e solicitações avulsas da recepção, com créditos e prioridade do tipo de limpeza.

**Por que agora:** sem tarefas não há distribuição nem execução, e o fechamento (E11) já publica o avanço da data que gera as tarefas do dia.

**Dependências:** E01 e E11 (as consultas por data na `ReservationsApi` e o avanço real da data). Mesmo módulo que E03–E06 (sem paralelismo com elas).

**Escopo:** `housekeeping`:

- Listeners `@ApplicationModuleListener` para `GuestCheckedOut`, `RoomMoved` e `BusinessDateAdvanced`; cada origem gera no máximo uma tarefa por (UH, data, tipo).
- Consultas a `reservations` (hospedados, chegadas, saídas da data — H4) e a `rooms` (status) para decidir permanência, pré-chegada (UH vaga há N dias) e prioridade de chegada/early.
- `POST /api/v1/housekeeping-tasks` para solicitações avulsas da recepção (ex.: toalhas extras), com permissão própria.
- `GET /api/v1/housekeeping-tasks?date=…` com filtros e paginação.
- Faxinas programadas: tarefa gerada quando `nextDueOn` ≤ data operacional; `lastDoneOn` atualizado na conclusão (E04).

**Fora de escopo:** distribuição (E03); disparo do avanço de data (E11 publica via `hotel`).

**Critérios de aceite:**

- [x] `GuestCheckedOut` gera tarefa de saída na UH; `RoomMoved` gera tarefa na UH de origem (testes de módulo).
- [x] `BusinessDateAdvanced` gera permanência para UHs ocupadas, pré-chegada para UHs vagas há N dias com chegada e tarefas de faxinas programadas vencidas.
- [x] Reprocessar o mesmo evento não cria segunda tarefa (restrição única por UH, data e tipo).
- [x] UH com chegada prevista recebe prioridade maior que permanência comum.
- [x] `POST /api/v1/housekeeping-tasks` cria tarefa avulsa; sem permissão → `403`.
- [x] A tabela de tarefas não tem colunas de dados pessoais do hóspede.
- [x] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato (`./gradlew checkOpenApi`, tabela do `CLAUDE.md`) → sucesso.

**Revisão humana:** não

**Notas para o plano:** SPEC RF-GOV-02; H4. Os eventos `GuestCheckedOut` e `RoomMoved` (M5 E01) ainda não têm consumidor de produção no `housekeeping`: este é o primeiro (nota `ZenyHotelManager - Hospedagem e recepção`); o listener de `BusinessDateAdvanced` convive com o das rotinas de checklist (o outbox entrega a cada um). Testes de módulo com o `Scenario` do Spring Modulith; testar as duas ordens de chegada dos eventos (check-out antes e depois do avanço da data). O KDoc de `GuestCheckedIn` e `GuestCheckInUndone` (`reservations/ReservationsEvents.kt`) também diz "escutado pela governança (M6)": o plano decide se a governança precisa deles (o desfazer devolve a UH Vaga sem sujar) e, se não precisar, corrige o KDoc. Armadilhas do formato antigo: permanência nunca para UH vaga; nenhum dado pessoal do hóspede copiado para a tarefa.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Ordem de eventos (check-out antes do avanço de data e vice-versa): a restrição única e testes com as duas ordens evitam duplicidade.

**Evidências e handoff:**

- **Plano:** `docs/superpowers/plans/2026-11-18-m6-operacao-fechamento-e02.md`, com 3 fatias.
  - **Revisão de arquitetura** (`revisor-arquitetura`, opus): aprovada com notas na rodada 2.
  - **Rodada 0:** bloqueada por 12 Importantes. Os principais:
    - a premissa errada de que o banco compartilhado não recebe avanços;
    - a auditoria da geração;
    - a pré-chegada que dependia da ordem dos eventos;
    - o `arrivalExpected` que envelhece;
    - a D6 sem registro;
    - a definição de "aberta".
  - **Rodada 1:** bloqueada por 2 Importantes: a ordem B não era produzida de fato e a D10 não tinha comando.
- **Commits:** branch `roadmap/m6-operacao-fechamento`, de `2676247` (início e plano) a `c2ae72a`.
  - **Task 1** (tarefa, lista e avulsa):
    - `d94946b`;
    - `b2aa044` (testador, `AuthorizationTest`);
    - `7bbb5d6` (rodada 1: `?date=` fora do `StorableRange` dá página vazia; limite único de `notes`).
  - **Task 2** (saída e troca de UH por evento, chegada prevista):
    - `51c659f` (`ReservationsApi.assignedRooms` e KDoc dos eventos);
    - `39d81bb`;
    - `e39d11c` (rodada 1: chegada exata em data futura e leitura numa transação).
  - **Task 3** (tarefas do dia): `c83b058`.
  - **Testador da etapa:**
    - `0a972c5` (contagens da trilha estreitadas em 3 testes de outros módulos);
    - `aac73c5`;
    - `fefec5d`;
    - `c2ae72a`.
  - **Correção final:** `4d75571` (I1 da revisão final).
- **Entregue:**
  - **Tarefa de governança `HousekeepingTask`:**
    - nasce em `PENDING`;
    - tabela `housekeeping.housekeeping_task` (migrações `V2026_11_18_1000` e `1001`);
    - unicidade `(room_id, area_id, business_date, cleaning_type_id)` com `NULLS NOT DISTINCT`;
    - CHECK `scheduled_cleaning_id` ⇔ `source = 'SCHEDULED'`;
    - DELETE recusado;
    - sem coluna de hóspede nem de reserva (RN-13).
  - **API:**
    - `GET /api/v1/housekeeping-tasks?date=…`, com cursor (`arrivalExpected` desc, `priority` desc, `id`) e filtros `roomId`, `areaId`, `status`, `cleaningTypeId` e `source`;
    - `GET /{id}`;
    - `POST` da avulsa (`201`; `409 HOUSEKEEPING_TASK_ALREADY_EXISTS`; `422 HOUSEKEEPING_TASK_DATE_INVALID` fora de [data operacional, +30 dias]; `422 HOUSEKEEPING_TASK_LOCATION_INACTIVE`);
    - `status` e `source` saem como `x-extensible-enum`.
  - **Permissões:** `housekeeping.tasks.read` e `housekeeping.tasks.request`, só `ALL`, nos modelos Gerente, Governanta e Recepção.
  - **Listeners `@ApplicationModuleListener`** (outbox), que delegam a `HousekeepingTaskGeneration`, `@Audited("housekeeping.task.generate")` com o ator sistema e `noChanges()`:
    - `GuestCheckedOut` e `RoomMoved` geram `CHECKOUT` na UH que ficou vaga, na data do evento;
    - `BusinessDateAdvanced` gera, nesta ordem (D11): as programadas vencidas, as permanências e as pré-chegadas.
  - **Avanço atrasado:** o listener ignora o avanço atrasado com `WARN` (D10).
  - **`ReservationsApi.assignedRooms(ids)`:** alocação vigente, em lote. O KDoc dos eventos de reserva deixou de citar a governança como ouvinte, exceto em `GuestCheckedOut` e `RoomMoved` (D6).
  - **Propriedade:** `zeny.housekeeping.pre-arrival-after-days: 3`, validada entre 1 e 30.
  - **Módulo:** passa a declarar `["shared", "hotel", "rooms", "reservations", "checklists"]`.
- **Revisões:**
  - **Task 1:** 1 Importante, `?date=` fora do alcance dava 500, corrigido na rodada 1.
  - **Task 2:** 1 Importante, a avulsa futura marcada com chegada de outra data, corrigido na rodada 1.
  - **Task 3:** 2 Importantes de teste (contagens globais da trilha e idempotência parcial), resolvidos pelo testador.
  - **Revisão final** (`revisor-seguranca`, opus, `78a3630..c83b058`): "With fixes", com 0 Crítico. O I1 era uma `STAYOVER` indevida no dia do check-in quando a geração do dia roda atrasada, somada à corrida `inHouse`/`pendingDepartures`. Foi corrigido em `4d75571`.
  - **Re-revisão escopada** (`c83b058..c2ae72a`): aprovada. O apontamento `DEV_TESTE_DO_TESTADOR` foi justificado: o desenvolvedor mudou só a montagem de testes dele, com as asserções iguais.
- **Verificação:**
  - **Gate:** `./gradlew build` em `c2ae72a` → BUILD SUCCESSFUL em 16m 6s, com 4239 testes, 0 falhas e 0 ignorados (contagem dos XML do JUnit, árvore limpa). Antes da correção, em `c83b058`: 4232 testes, 0 falhas.
  - **oasdiff:** sem breaking change; o contrato só ganhou caminhos e schemas.
  - **Scanner:** `osv-scanner` 2.7.1, 426+74 pacotes. Resultado: 0 Critical/High, 2 Medium e 3 Low, todos pré-existentes. Lockfiles sem mudança.
  - **Integridade:** `check_test_integrity.py --base 78a3630` → exit 1, com um apontamento `DEV_TESTE_DO_TESTADOR` justificado (acima). Em cada fatia, exit 0.
- **Critérios verificados:**
  - **(1) Saída e troca de UH:** `HousekeepingCheckoutTasksTest`. `GuestCheckedOut` gera `CHECKOUT` na UH, e `RoomMoved` gera a tarefa na UH de origem, por check-out e troca reais com espera do listener.
  - **(2) Tarefas do dia** (`HousekeepingDailyTasksTest` e `HousekeepingEndOfDayTasksTest`):
    - permanência para quem chegou antes da data e não sai nela;
    - pré-chegada para UH vaga, não Suja, fora de OOO, com chegada e sem tarefa nos últimos 3 dias;
    - programadas vencidas, sem tarefa aberta, com local ativo e fora de OOO/OOS;
    - avanço real no banco isolado.
  - **(3) Reprocessar não duplica:**
    - entrega repetida e 4 entregas simultâneas do mesmo check-out;
    - `generateForDate` em dobro;
    - restrição única.
  - **(4) Prioridade de chegada:** `arrivalExpected` e a ordem da lista, em `HousekeepingTaskGenerationTest` (prioridade editada, com portas falsas) e na lista do dia.
  - **(5) Avulsa e permissões:** `POST` cria a avulsa, e sem permissão dá `403` antes de qualquer recusa. Testes `HousekeepingTasksApiTest`, `HousekeepingTasksAttackTest` e `HousekeepingTaskPermissionsTest`.
  - **(6) Sem dado pessoal:** a tabela não tem coluna de dado pessoal (`HousekeepingTaskSchemaTest`), e a trilha da geração também não leva nenhum.
  - **(7)** Gate e `checkOpenApi` acima.
- **Decisões para o usuário** (tomadas pelo orquestrador pela opção recomendada, sob a autorização da noite de 2026-11-17; todas revisáveis):
  - **D1:** tipo por origem pela `key`. Se o tipo estiver inativo, a origem não gera tarefa.
  - **D2:** créditos do tipo para o tipo da UH; sem crédito ou numa área, 0.
  - **D3:** pré-chegada para UH vaga **e não Suja**, com N = 3 dias. A janela é (D-N, D]. Uma programada gerada no mesmo dia barra a pré-chegada.
  - **D4:** a data da tarefa é a do evento. Uma tarefa não feita fica na data dela.
  - **D5:** unicidade por (local, data, tipo) em qualquer status. Uma segunda saída no dia não gera outra tarefa, e uma avulsa repetida dá `409`, mesmo que a anterior esteja `CANCELLED`.
  - **D6:** a governança não escuta `GuestCheckedIn`, `GuestCheckInUndone` nem `Reservation*`. Isso diverge da SPEC §8.3 e da ARCHITECTURE §6.1.
  - **D7:** `ReservationsApi.assignedRooms`.
  - **D8:** `arrivalExpected` é um retrato da geração. Na data operacional ou antes, inclui a chegada atrasada; numa data futura, só a chegada exata.
  - **D9, refinada pela revisão final:** a permanência vale para quem chegou antes da data (`inHouse(date - 1)`), menos as saídas. A ocupação vem do `reservations`, e o OOO/OOS e o status vêm do `rooms`, na data operacional.
  - **D10:** um avanço atrasado é ignorado com `WARN`.
  - **D11:** as programadas são geradas primeiro.
  - **D12:** estados esperados nunca lançam exceção. Um erro inesperado refaz o dia pelo outbox.
  - **D13:** as colunas de sincronização offline ficam para a E04.
  - **Também:** uma UH desativada entre o check-out e o listener não gera `CHECKOUT`. Uma avulsa em UH ou área inativa dá `422 HOUSEKEEPING_TASK_LOCATION_INACTIVE`.
- **Notas para a próxima etapa:**
  - **Para a E03:**
    - a lista e a distribuição precisam incluir as tarefas abertas de datas anteriores (D4). Hoje a lista filtra `business_date = :date`;
    - recalcular a chegada prevista na sugestão (D8);
    - o cursor da lista não leva a data;
    - a prioridade do early check-in (RF-GOV-03) não está na E02;
    - a `HousekeepingTaskGeneration` está no limite do detekt (11 funções). Extraia `arrivalsByRoom`/`dayOf` antes de acrescentar;
    - trocar para `INFO` o log da programada pulada por tipo ou local inválido;
    - o `HousekeepingAudit` mora em `CleaningTypesService.kt` e deve ser separado;
    - uma chegada atrasada (reserva de D-1 que faz check-in em D) com a geração de D atrasada ainda ganha `STAYOVER` em D. O caso é raro e anterior a esta etapa; corrigir exige a data efetiva do check-in no `reservations`.
  - **Para a E04:**
    - colunas de mudança (`change_seq`) para o feed offline;
    - transições da tarefa;
    - `recordDone` da faxina programada (sem carimbo e sem guarda; ver a E01).
  - **Para o `arquiteto`:**
    - a divergência da D6 com a SPEC §8.3 e a ARCHITECTURE §6.1 ("atualizar plano do dia");
    - as pendências transversais da E01 (fracionário em inteiro, `""` em UUID, `40P01`/`55P03` → 500, `required` nos schemas de request).
  - **Testes:**
    - o banco compartilhado recebe avanços de data de outros testes, e a geração roda para o hotel inteiro;
    - contagens globais de `audit.audit_entry` não devem voltar;
    - um teste da lista grava uma linha `-infinity` permanente numa UH aleatória, porque o trigger impede a limpeza.
  - **Menores restantes:**
    - N+1 em `rooms.status`;
    - log com um só motivo na `CHECKOUT`;
    - o teste RN-13 da trilha sem controle positivo;
    - nenhum teste prova que a pré-chegada usa `inHouse(date)`;
    - nenhum teste de API prova a ordem por chegada com prioridade editada.
- **Capturas do brain:** `00 Inbox/2026-11-18 - ZenyHotelManager - atualização m6-operacao-fechamento E02 - f7e6203c.md`.

---

## E03 — Distribuição sugerida e publicação — #concluida

**Objetivo:** implementar o `AssignmentPlan` (RF-GOV-03): sugestão que balanceia créditos entre as camareiras escaladas no dia (escala de `staff`), agrupa por andar, prioriza chegadas e early check-in e respeita "Não perturbe"; ajuste manual pela governanta; publicação que notifica só quem teve mudança.

**Por que agora:** a execução (E04) só começa com tarefas atribuídas.

**Dependências:** E02. Mesmo módulo que E04–E06 (sem paralelismo com elas).

**Escopo:** `housekeeping`:

- Algoritmo de sugestão determinístico no `domain` (testável sem Spring), usando a escala do dia (`staff`, RF-PES-04), andar das UHs (`rooms`) e sinalização DND.
- `POST /api/v1/assignment-plans/{date}/suggest`, `PATCH` de atribuições, `POST …/publish` e republicação.
- Notificação de tarefa atribuída via `notifications` (RF-NOT-01) só para quem teve mudança; evento de SSE para o painel.

**Fora de escopo:** execução (E04).

**Critérios de aceite:**

- [x] A sugestão só atribui a camareiras escaladas na data (teste com escala de `staff`).
- [x] Em casos de teste, a diferença de créditos entre camareiras não passa do maior crédito de uma tarefa.
- [x] UHs do mesmo andar ficam com a mesma camareira sempre que o balanceamento permite; chegadas e early vêm primeiro na ordem (**early adiado pela H17**: o `reservations` não guarda o dado; só a chegada prevista conta).
- [x] UH com DND não recebe tarefa de permanência na sugestão inicial.
- [x] A mesma entrada gera a mesma sugestão (determinismo).
- [x] Publicar notifica cada camareira atribuída; republicar notifica só quem teve tarefa incluída ou removida.
- [x] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato (`./gradlew checkOpenApi`, tabela do `CLAUDE.md`) → sucesso.

**Revisão humana:** não

**Notas para o plano:** SPEC RF-GOV-03 e RF-PES-04. O tipo de notificação `notifications.task-assigned` já existe no catálogo do `notifications`, criado no M2 para os módulos futuros usarem (nota `ZenyHotelManager - Módulo notifications`); título e texto nunca levam dado de hóspede (use o número da UH). Registrar no handoff as decisões do algoritmo (o guloso simples basta para 30 UHs). Armadilhas do formato antigo: atribuição a quem não está escalado; republicação que notifica todo mundo; sugestão não determinística.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Algoritmo ótimo é desnecessário para 30 UHs: guloso simples, documentado e testado.

**Evidências e handoff:**

- **Plano:** `docs/superpowers/plans/2026-11-18-m6-operacao-fechamento-e03.md`, com 3 fatias.
  - Revisão de arquitetura (`revisor-arquitetura`, opus): aprovada com notas na rodada 2.
  - Rodada 0: bloqueada por 12 Importantes (algoritmo, janela de datas, concorrência, tempo real, ordem publicada, "camareira" etc.).
  - Rodada 1: bloqueada por 2 Importantes (datas dos testes e definição de "retirada").
- **Commits:** branch `roadmap/m6-operacao-fechamento`, de `fb9591a` (início e plano) a `41d9dcc`.
  - **Task 1:** `ede9237` (algoritmo), `b3cda5c` (extrações da dívida da E02), `9142d43` (níveis do log) e `199ea31` (rodada 1: `@Timeout` em outra thread e casos de fronteira).
  - **Task 2:** `2e90060`, `f93796f`, `c2735e6` e `4d7f62b` (testador, `AuthorizationTest`).
    - A Task 2 foi refeita do zero depois de uma queda de energia que derrubou a máquina; nada commitado se perdeu.
  - **Task 3:** `59060a5`, `cda7c27`, `1350e55`, `1f7a754`, `434470a`, `2620c1f` e `13b2730` (rodada 1: `StaffApi.employees(ids)` no aviso).
  - **Testador da etapa:** `7c07db4`, `c217474`, `2948546` e `71599bd`.
  - **Correção final:** `41d9dcc` (KDoc da D11) e `10cec11` (plano).
- **Entregue:**
  - **`AssignmentSuggester`** (`housekeeping/domain`): heurística gulosa e determinística.
    1. Ordena por chegada, prioridade, andar em ordem natural, UH ou área e id.
    2. Distribui os andares inteiros, do maior total de créditos para a camareira menos carregada.
    3. Rebalanceia movendo só tarefas com crédito > 0, da de menor crédito para a de maior, até a diferença ficar ≤ o maior crédito de uma tarefa. O término é provado pela soma dos quadrados.

    Garantia: se a distribuição gulosa dos andares inteiros cumpre o limite, nenhum andar é dividido. O algoritmo não é ótimo.
  - **`AssignmentPlan`:**
    - tabelas `assignment_plan` (`id` UUID, `business_date` único, `DRAFT`/`PUBLISHED`, nunca apagado) e `assignment_plan_item`;
    - migrações `V2026_11_18_1100`, `1101` e `1102`, esta última com `assigned_employee_id` e `assignment_position` na tarefa.
  - **API:**
    - `POST /api/v1/assignment-plans/{date}/suggest`, com `employeeIds` opcional e `If-Match` quando o plano existe;
    - `GET /api/v1/assignment-plans/{date}`;
    - `PATCH /api/v1/assignment-plans/{date}`, para mover, acrescentar e remover;
    - `POST …/publish`, com `If-Match`.
    - Janela: da data operacional até +7. Tarefas de datas anteriores entram só no plano operacional.
    - Trava com `FOR UPDATE` e `ON CONFLICT`. Falha otimista dá `412`.
  - **Publicação:**
    - grava camareira, posição e `ASSIGNED`;
    - a tarefa "retirada" (`ASSIGNED`, dentro do conteúdo do plano e ausente do rascunho) volta a `PENDING`;
    - uma tarefa que já andou nunca é tocada: se mudaria de camareira, a publicação dá `422` com os ids em `errors[]`.
  - **Aviso:** `notifications.task-assigned` só para quem teve tarefa incluída ou removida. O título leva a data, e o texto só tem números de UH e nomes de área, truncado com "e mais N". Os "não avisados" vêm com `NO_USER` ou `NOT_DELIVERED`.
  - **Tempo real:** tópico `housekeeping` (permissão `housekeeping.tasks.read`, registrado na ARCHITECTURE §10), mensagem `housekeeping.assignment-plan`.
  - **Permissão:** `housekeeping.assignments.manage` (Gerente e Governanta).
  - **`StaffApi.employees(ids)`:** nova.
  - **Dependências:** o módulo passa a declarar `staff` e `notifications`.
  - **Dívida da E02 paga:** `ArrivalsByRoom`, `HousekeepingAudit` e `HousekeepingConfiguration` extraídos; log em níveis.
- **Revisões:**
  - Task 1: 1 Importante de teste, corrigido na rodada 1.
  - Task 2: aprovada só com Menores.
  - Task 3: 1 Importante (`NO_USER` impreciso), corrigido na rodada 1.
  - Revisão final (`revisor-seguranca`, opus, `c6e1e06..13b2730`): "With fixes", só 3 Menores (KDoc da D11, stub e lixo de teste), corrigidos.
  - Re-revisão escopada (`13b2730..41d9dcc`): aprovada.
- **Verificação:**
  - **Gate:** `./gradlew build` em `41d9dcc` → BUILD SUCCESSFUL em 15m 59s, 4309 testes, 0 falhas, 0 ignorados (contagem dos XML do JUnit, árvore limpa). Antes, em `13b2730`: 4304 testes e 0 falhas.
  - **oasdiff:** sem `ERR`; o contrato só cresceu.
  - **Scanner:** `osv-scanner` 2.7.1, com 0 Critical/High, 2 Medium e 3 Low, todos pré-existentes. Lockfiles sem mudança.
  - **Integridade:** `check_test_integrity.py --base c6e1e06` → exit 0, sem apontamento.
- **Critérios verificados:**
  - (1) Só camareiras escaladas: `AssignmentPlansApiTest`, com quem está de folga e de outro setor de fora. A publicação reconfere a escala (`AssignmentPlanPublishTest`).
  - (2) Diferença de créditos ≤ maior crédito: `AssignmentSuggesterTest`, com mais de 10 casos, mais 1000 aleatórios e a fronteira exata.
  - (3) Andar com uma só camareira quando o limite permite: `AssignmentSuggesterTest`. As chegadas vêm primeiro, com a chegada recalculada (`AssignmentPlansApiTest`). O early foi adiado (H17).
  - (4) DND sem `STAYOVER` na sugestão: `AssignmentSuggesterTest` e `AssignmentPlansApiTest`.
  - (5) Determinismo: entrada embaralhada, empates por id.
  - (6) Republicação avisa só quem mudou: `AssignmentPlanPublishTest` (A→B avisa A e B, não C). O caso de quem saiu da escala também é avisado.
  - (7) Gate e `checkOpenApi`.
- **Decisões para o usuário** (tomadas pelo orquestrador pela opção recomendada da revisão de arquitetura, sob a autorização noturna; revisáveis):
  - **D1:** camareira é quem está escalado no setor de governança. O `employeeIds` opcional tira a governanta e as supervisoras e vale só no `suggest`; o ajuste manual aceita qualquer escalado.
  - **D2:** janela da data operacional até +7. As anteriores entram só no plano operacional. O plano de uma data passada fica congelado.
  - **D3 e H17:** a chegada é recalculada na sugestão, e o early check-in fica adiado.
  - **D4:** DND tira só a `STAYOVER`.
  - **D5:** heurística não ótima.
  - **D6:** o `suggest` refaz o rascunho inteiro, e o `PATCH` acrescenta e remove.
  - **D7:** `ASSIGNED → PENDING` para a tarefa "retirada". É extensão da SPEC §8.4, que o `arquiteto` registra.
  - **D8:** aviso com a data e só números de UH e nomes de área.
  - **D9:** tópico `housekeeping`, reaproveitado pela E04.
  - **D10:** permissão `housekeeping.assignments.manage`.
  - **D11:** exceção à H16. Colaborador inexistente dá `422 ASSIGNMENT_EMPLOYEE_NOT_SCHEDULED`, porque a validação é pela escala.
  - **D12:** a posição publicada fica gravada na tarefa.
  - **Ruling da rodada 1 da Task 3:** `StaffApi.employees(ids)` nova, para avisar quem saiu da escala.
- **Notas para a próxima etapa:**
  - **Para a E04:**
    - Um rascunho com uma tarefa `DONE` ou `CANCELLED` dada a outra camareira recusa a publicação inteira. Decidir se esse item deve ser ignorado.
    - Endurecer o CHECK `ASSIGNED ⇒ camareira`.
    - Desempatar por posição e depois id: uma tarefa que andou pode repetir a posição.
    - Ligar `mark_offline_change` em `housekeeping_task`.
    - Publicar as mensagens de tarefa no tópico `housekeeping`.
    - Montar o feed das tarefas abertas de datas anteriores, com a data no cursor.
    - Extrair `AssignmentPlanPublication` do `AssignmentPlansService`, que tem cerca de 520 linhas.
    - A resposta da publicação não lista as "retiradas", e a governanta não as vê no rascunho.
    - O `422` de escala na publicação não traz o `employeeId` em `errors[]`.
    - `employeeIds: []` deixa tudo sem camareira, e o contrato não diz isso.
    - Não há teste de colaborador inativo escalado.
    - O `HousekeepingTaskSchemaTest` (E02) ainda grava `PENDING` antigas no banco compartilhado.
  - **Para o `arquiteto`:**
    - registrar a D7 na SPEC §8.4;
    - registrar a D11 como exceção à H16;
    - levar o early check-in ao `reservations` (H17);
    - resolver a divergência da D6 da E02 com a SPEC §8.3 e a ARCHITECTURE §6.1.
  - **Menores aceitos:**
    - `If-Match` em branco dá `428` também na criação, consistente com o resto do projeto;
    - N+1 em `rooms.area` e `rooms.status` na sugestão;
    - `remove` de tarefa fora do plano é ignorado em silêncio, como o contrato documenta.
- **Capturas do brain:** `00 Inbox/2026-11-18 - ZenyHotelManager - atualização m6-operacao-fechamento E03 - 493d72a8.md`.

---

## E04 — Máquina de estados, ações online e conclusão da tarefa — #concluida

**Objetivo:** implementar a execução **online** da tarefa de governança (RF-GOV-04, RF-GOV-05) pela máquina de estados da SPEC §8.4: iniciar, pausar, retomar, concluir com checklist, recusar (não perturbe ou hóspede) e tentar de novo. A conclusão leva a UH Suja a Limpa (com ou sem inspeção pendente), registra quem limpou e os tempos, grava a ocupação encontrada para a discrepância (RF-UH-08), atualiza a faxina programada e publica `HousekeepingTaskCompleted`. Os comandos offline, o conflito RN-22 e o feed da camareira ficam na E19 (divisão da H18 e da H21 a).

**Por que agora:** a E19 (offline), a inspeção (E05) e os achados (E06) usam a máquina de estados e o caso de uso de execução desta etapa. Ela roda depois da E20 e da E21 pela ordem do documento (H20 c), já com a entrada estrita e o `409 CONCURRENT_UPDATE`.

**Dependências:** E03. Mesmo módulo que E19, E05, E06 e E22 (sem paralelismo com elas).

**Escopo:** `housekeeping`, com acréscimos mínimos às APIs públicas de `rooms` e `checklists` (dependências já declaradas):

- **Domínio:** todas as transições da §8.4, inclusive `ASSIGNED → PENDING` (H19 a, já registrada na SPEC), `REJECTED → IN_PROGRESS` (refazer, que a E05 vai produzir) e `REFUSED → ASSIGNED` (nova tentativa). Os instantes das transições ficam em ordem: nenhum antes da última transição da tarefa, regra que a E19 reaproveita com o `occurredAt` corrigido.
- **Permissão de execução** própria, com escopo `OWN` no modelo Camareira (dona = a camareira atribuída, pelo usuário dela no `staff`) e `ALL` na Governanta e no Gerente. A autorização vem antes de qualquer recusa que revele estado.
- **Ações online** como subrecurso de ação (ARCHITECTURE §7), com `If-Match`. Transição inválida dá `409`, no padrão `…_TRANSITION_NOT_ALLOWED` do projeto.
- **Checklist:** o início abre a execução pela `ChecklistsApi` com a versão publicada do modelo da tarefa (vínculo do tipo de limpeza ou `resolveTemplate`; D1 da E01). A `ChecklistsApi` ganha a consulta da versão publicada vigente de um modelo, que hoje não existe. A conclusão fecha a execução com as respostas. Uma execução que já foi concluída por fora (pelos endpoints ou comandos do próprio `checklists`) é tratada sem 500 e sem dois `ChecklistCompleted`.
- **UH na conclusão:** `markClean(requiresInspection)` só com a UH Suja. UH já Limpa ou Inspecionada (ex.: a pré-chegada, que nasce em UH não Suja; D3 da E02) não muda (H18 d), e a tarefa de área não tem UH. A recusa por não perturbe numa UH vaga não chama `setServiceFlag`, que a recusaria.
- **Ocupação encontrada:** a observação de governança do `rooms` (hoje só por endpoint) ganha um método na `RoomsApi`, chamado pela conclusão quando a camareira informa a ocupação.
- **Tempos e autoria:** quem limpou, início, fim e duração sem as pausas.
- **Faxina programada:** a conclusão de tarefa `SCHEDULED` chama `ScheduledCleaning.recordDone`, que passa a atualizar `updatedAt`, recusar faxina inativa e data fora do `StorableRange` (nota da E01).
- **Evento** público `HousekeepingTaskCompleted`, sem dado de hóspede. Ainda sem consumidor: o status da UH muda pela chamada síncrona acima (SPEC §8.3, corrigida em 2026-11-18).
- **Tempo real:** cada transição publica uma mensagem de tarefa no tópico `housekeeping` (D9 da E03).
- **Trava:** a ordem é **tarefa → execução do checklist → UH** em toda ação, com teste determinístico de concorrência.

**Fora de escopo:** comandos offline, histórico de posse, conflito RN-22 e feed (E19); inspeção (E05); achados e danos (E06); prioridade do early (E22); ações de estoque e enxoval do checklist (M9).

**Critérios de aceite:**

- [x] Testes de domínio cobrem todas as transições da §8.4 e `ASSIGNED → PENDING`, válidas e inválidas, e a ordem dos instantes.
- [x] Conclusão numa UH Suja: tipo sem inspeção deixa a UH Limpa e a tarefa `DONE`; com inspeção, UH Limpa com inspeção pendente e tarefa `AWAITING_INSPECTION`.
- [x] Conclusão numa UH já Limpa ou Inspecionada, e numa tarefa de área, não muda a UH; a tarefa e o checklist concluem.
- [x] Item obrigatório do checklist faltando dá `422`, com a tarefa, a UH e a faxina programada sem mudança.
- [x] Transição inválida dá `409` com `code` estável, sem mudar a UH nem o checklist.
- [x] Camareira com escopo `OWN` sobre tarefa de outra dá `403` antes de qualquer `404`, `409` ou `412`; a Governanta, com `ALL`, age sobre qualquer tarefa; sem a permissão de execução, `403`.
- [x] A Camareira não lê, pela lista do dia, pelo plano nem pelo tópico `housekeeping`, tarefas de outra camareira (teste com o modelo Camareira).
- [x] Recusa por não perturbe numa UH vaga não dá 500 e não muda a UH; a resposta escolhida está no contrato.
- [x] `HousekeepingTaskCompleted` publicado uma vez, sem dado de hóspede; quem limpou, início, fim e duração sem as pausas gravados.
- [x] Ocupação divergente informada na conclusão aparece nas discrepâncias do `rooms` (RF-UH-08).
- [x] Tarefa `SCHEDULED` concluída atualiza `lastDoneOn` e `nextDueOn`; `recordDone` atualiza `updatedAt`, recusa faxina inativa e data fora do `StorableRange` (testes de unidade).
- [x] Conclusão com a execução de checklist já concluída por fora não dá 500 nem publica um segundo `ChecklistCompleted`.
- [x] Ação e conclusão simultâneas na mesma tarefa e UH se enfileiram sem deadlock (teste determinístico).
- [x] Um cliente inscrito no tópico `housekeeping` recebe uma mensagem a cada transição.
- [x] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato (`./gradlew checkOpenApi`, tabela do `CLAUDE.md`) → sucesso.

**Revisão humana:** sim. Fixa o contrato da camareira (permissão de execução com `OWN`) e a máquina de estados que a E19 e a E05 repetem (H21 f).

**Notas para o plano:** H2, H3, H18 (b a d), H19 (a) e H21. Ler a SPEC §8.4 e as notas `ZenyHotelManager - Módulo checklists`, `ZenyHotelManager - Módulo rooms` e `ZenyHotelManager - Módulo housekeeping`, mais as capturas pendentes da E01 a E03, que valem sobre ela. O status da UH nunca muda fora da `RoomsApi`. Armadilhas da revisão do plano tentado (abaixo), já distribuídas entre esta etapa e a E19:

- **Leitura da camareira:** dar `OWN` a `housekeeping.tasks.read` vazaria a lista do dia, o plano e o tópico, que hoje filtram só por `ALL` (exigiria `scopeFilter` e dono em cada um). O precedente do `checklists` evita isso: as fontes offline usam a permissão de execução (`checklists.executions.execute`), e não a de leitura. O plano escolhe e prova pelo critério da leitura.
- **Inspeção sem UH tocada:** um tipo com inspeção concluído numa UH que não mudou (Limpa, Inspecionada ou área) deixa a tarefa em `AWAITING_INSPECTION`, mas a `RoomsApi.rejectInspection` exige inspeção pendente. O plano decide o estado da tarefa nesse caso e registra no handoff para a E05.
- **Recusa:** a SPEC só prevê `ASSIGNED → REFUSED`; o motivo (não perturbe ou hóspede) e o que acontece com o sinal de não perturbe da UH ocupada ficam no plano.
- **Notas da E03 para a E04** que o plano avalia: o item `DONE` ou `CANCELLED` de outra camareira que recusa a publicação inteira (o plano tentado propunha ignorá-lo); o CHECK `ASSIGNED ⇒ camareira`; o desempate por posição e id; a extração da `AssignmentPlanPublication` (o `AssignmentPlansService` tem ~520 linhas). As outras notas da E03 (resposta da publicação sem as retiradas, `employeeId` no `422` de escala, `employeeIds: []`, colaborador inativo) seguem para quando a publicação mudar de novo.
- **Testes:** o banco compartilhado recebe avanços de data e planos de outros testes, e a geração roda para o hotel inteiro (handoff da E02); os testes afirmam só sobre as próprias tarefas, UHs e camareiras. Trava determinística por `pg_blocking_pids` (E01).

**Plano tentado em 2026-11-18 (não executado; achados distribuídos entre esta etapa e a E19 em 2026-11-18):** `docs/superpowers/plans/2026-11-18-m6-operacao-fechamento-e04.md`, bloqueado na revisão de arquitetura (rodada 0). Achados para o desenho da divisão (H18): o conflito RN-22 contradiz o escopo `OWN` sem histórico de posse; `markClean` só vale para UH Suja (a pré-chegada nunca conclui; tarefa de área não tem status de UH); a recusa por DND numa UH vaga dá `422 ROOM_NOT_OCCUPIED`; `OWN` em `housekeeping.tasks.read` vazaria a lista do dia, o plano e o tópico (exige `scopeFilter` e `owners`); a `ChecklistsApi` não dá a versão publicada de um modelo, o checklist offline precisa do `versionId` do aparelho (precedente `checklists.start-execution`) e a execução pode ser concluída por fora; acima da tolerância do relógio o comando é aplicado com `CLOCK_SKEW_EXCEEDED` (não rejeitado) e os instantes precisam de ordem (`max(at, última transição)`); falta estratégia de trava (tarefa → execução → UH) com teste determinístico; o nome da fonte offline deve ser `housekeeping-tasks` e os comandos `housekeeping.<verbo>-task`; transição inválida é `409` no padrão do projeto; o aviso de conflito pede um tipo novo no `notifications`. As decisões D1–D11 do plano tentado são ponto de partida, não decisões tomadas; as do usuário estão na H18.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- A conclusão atravessa três módulos (checklist, UH e faxina) numa transação: uma falha no meio deixaria estado parcial. Mitigação: o critério do item obrigatório faltando e o teste de concorrência.
- A etapa volta a ficar grande demais (o motivo da H18). Mitigação: a parte offline já saiu para a E19; se o plano passar de 3 fatias, a leitura da camareira e o tempo real são os primeiros candidatos a sair, registrados no handoff.

**Rollback / migração:** colunas novas na tarefa, no schema `housekeeping`, sem apagar dados; o contrato só cresce.

**Evidências e handoff:**

- Plano: `docs/superpowers/plans/2026-11-19-m6-operacao-fechamento-e04.md`, com 3 fatias. Revisão de arquitetura: aprovada com notas na rodada 1 (`revisor-arquitetura`, opus).
  - Na rodada 0 foi bloqueada por 4 Importantes:
    - decisões tomadas sobre o estado de outro módulo lido sem trava (resolvido com operações condicionais em `rooms` e `checklists`);
    - `notes` na resposta das ações;
    - faxina inativa ou concorrente na conclusão;
    - tempo real sem teste nomeado.
  - As 7 notas Menores da rodada 1 foram aplicadas ao plano.
- Commits: branch `roadmap/m6-operacao-fechamento`, de `703a096` (início) a `694d74b`.
  - Task 1 (máquina de estados, permissão e ações): `ec5bfb0`, `dda1210` e `19164cf` (desenvolvedor), mais `52eaac0` (testador, listas exatas).
  - Task 2 (conclusão): `8974f26`, `8d142af` e `2cd62bd` (desenvolvedor), `9ebb3ab` (testador, coluna nova e CHECKs) e, na rodada 1, `56a446d` e `18b3ffe` (desenvolvedor).
  - Task 3 (tempo real, leitura da camareira, trava e D12): `2fd79d6`, `d9941a3`, `1dcd493`, `07a15b5` e `2591569` (desenvolvedor).
  - ARCHITECTURE §10: `9bc67fa` (orquestrador).
  - Testador da etapa: `8753f80`, só `src/test`.
  - Rodada de correção do fim da etapa: `e7bfd7f` (os dados de execução no `GET`) e `694d74b` (critério de espera exato no `HousekeepingTaskLockTest`) (desenvolvedor).
  - Cada fatia passou por desenvolvedor → verificação mecânica → `revisor-seguranca`.
    - A Task 1 foi aprovada, com 1 Importante de teste (trava da tarefa), que foi ao testador.
    - A Task 2 teve 4 Importantes, e a rodada 1 corrigiu dois:
      - I1: a conclusão online tolerava foto obrigatória com UUID qualquer;
      - I4: a neutralização dos modelos gerais estava presa a um teste.
      - Os outros dois eram lacunas de teste e foram ao testador.
    - A Task 3 foi aprovada sem Importante.
- Entregue:
  - **Máquina de estados** (`domain/HousekeepingTask.kt`):
    - cobre todas as transições da §8.4, com `REJECTED → IN_PROGRESS` (refazer) e `REFUSED → ASSIGNED` (nova tentativa);
    - o instante de cada transição é `max(agora, last_transition_at)`;
    - `worked_seconds` soma só os trechos em `IN_PROGRESS`;
    - colunas `started_at`, `finished_at`, `worked_seconds`, `in_progress_since`, `last_transition_at`, `cleaned_by`, `refusal_reason` (`V2026_11_19_1000`) e `checklist_execution_id` (`V2026_11_19_1001`), com CHECKs de coerência.
  - **Permissão `housekeeping.tasks.execute`:**
    - `OWN` na Camareira; `ALL` na Governanta e no Gerente;
    - a dona é a camareira atribuída (`StaffApi.employeeOfUser`);
    - com `OWN`, tarefa alheia e tarefa inexistente dão o mesmo `403 RESOURCE_OUT_OF_SCOPE`;
    - a autorização vem antes de 404, 409 e 412; o `428` sem `If-Match` vem antes, pelo padrão `shared/Concurrency.kt`, e não revela estado.
  - **Ações** (`POST /api/v1/housekeeping-tasks/{id}/start|pause|resume|refuse|retry|complete`):
    - todas com `If-Match` e a resposta `HousekeepingTaskActionResponse`, sem `notes` e com `checklistExecutionId`;
    - transição inválida dá `409 HOUSEKEEPING_TASK_TRANSITION_NOT_ALLOWED`;
    - `start` de data futura dá `422 HOUSEKEEPING_TASK_NOT_DUE`.
  - **Conclusão**, numa transação, na ordem de trava tarefa → execução → UH → faxina:
    - `ChecklistsApi.completeExecutionIfOpen(..., ChecklistPhotoPolicy)` com `REFUSE` online; a execução já concluída por fora não dá erro nem segundo `ChecklistCompleted`;
    - `RoomsApi.markCleanIfDirty`: a tarefa vai a `AWAITING_INSPECTION` só se a UH foi de Suja a Limpa com inspeção pendente, e a `DONE` nos demais casos;
    - `RoomsApi.recordObservation` (RF-UH-08);
    - `ScheduledCleaning.recordDone(businessDate da tarefa)` com a faxina travada; a faxina inativa não muda e vai à trilha;
    - o evento público `HousekeepingTaskCompleted`, sem dado de hóspede.
  - **Recusa por não perturbe:** `RoomsApi.setServiceFlagIfOccupied` liga o sinal só numa UH ocupada. Numa UH vaga ou numa tarefa de área, a recusa responde `200` com a tarefa `REFUSED`.
  - **Checklist no `start`:** usa o vínculo do tipo, senão `resolveTemplate`, com a versão publicada vigente (`ChecklistsApi.currentVersion`). `answers` numa tarefa sem checklist dá `400` no campo.
  - **Tempo real:** cada transição publica `housekeeping.task` (`id` e versão) no tópico `housekeeping`. A ARCHITECTURE §10 registra isso.
  - **Leitura da camareira:** o modelo Camareira não tem `housekeeping.tasks.read`. A lista, o `GET`, o plano e o tópico dão `403` para ela.
  - **Publicação do plano (D12):** o item de tarefa `DONE` ou `CANCELLED` sai do plano publicado, a tarefa não muda e as posições são renumeradas.
  - **Teste:** o helper `HousekeepingExecutionTestSupport.neutralizeGeneralHousekeepingTemplates()` existe porque o `start` pega qualquer modelo geral HOUSEKEEPING ativo do banco compartilhado. Todo teste que inicia ou conclui tarefa o usa.
- Revisão final: `revisor-seguranca` (opus) sobre `aa82c27..9bc67fa`, "With fixes", 0 Crítico e 2 Importantes.
  - I1: o `GET` e a lista não mostravam os dados de execução (RF-GOV-05). Corrigido em `e7bfd7f`, com `refusalReason`, `startedAt`, `finishedAt`, `workedSeconds` e `cleanedBy`, todos aditivos.
  - I2: a autorização do `complete` não tinha teste. Coberta pelo testador.
  - A re-revisão escopada (`9bc67fa..694d74b`) deu os achados por resolvidos, sem Crítico nem Importante novo.
- Verificação:
  - **Gate:** `./gradlew build` em `694d74b` → BUILD SUCCESSFUL em 15m 24s, com 4440 testes e 0 falhas. Antes, em `9bc67fa`, 4427 testes e 0 falhas.
  - **oasdiff:** "Nenhuma mudança incompatível (nível ERR) em /api/v1". O contrato só ganhou caminhos, schemas e campos.
  - **Scanner:** `osv-scanner` 2.7.1 (426 + 74 pacotes): 0 Critical/High, 2 Medium e 3 Low, todos anteriores. Lockfiles sem mudança.
  - **Integridade:** `check_test_integrity.py --base aa82c27` → exit 0, sem apontamento, também em cada fatia.
- Critérios verificados:
  - **Domínio:** `HousekeepingTaskDomainTest` (todas as transições, ordem dos instantes, pausas).
  - **Conclusão:**
    - `HousekeepingTaskCompletionApiTest`: UH Suja com e sem inspeção; UH já Limpa e área; `422` sem efeito; execução concluída por fora; foto recusada; faxina ativa e inativa; evento; DND;
    - `RoomsApiHousekeepingOperationsTest` e `ChecklistsApiHousekeepingOperationsTest`;
    - `ScheduledCleaningDomainTest`.
  - **Escopo:** `HousekeepingTaskExecutionPermissionsTest`, `HousekeepingTaskExecutionAttackTest` (o `complete`) e `HousekeepingAttendantReadTest`.
  - **Trava:** `HousekeepingTaskLockTest` (6 corridas por `pg_blocking_pids`) e `HousekeepingTaskLockAttackTest`.
  - **Tempo real:** `HousekeepingTaskRealtimeTest`.
  - **D12:** `AssignmentPlanPublishFinishedTasksTest`.
- Decisões para o usuário (tomadas pela opção recomendada, revisáveis; a revisão humana da etapa foi substituída pela autorização do usuário neste lote):
  - **(a) D2:** a camareira não lê tarefas online. Ela as vê pela fonte offline da E19 e pela resposta das ações.
  - **(b) D3:** a tarefa vai a `AWAITING_INSPECTION` só quando a conclusão deixou a inspeção pendente na UH. A E05 trata a tarefa `AWAITING_INSPECTION` cuja UH já perdeu a pendência por check-out, fechamento ou aprovação manual.
  - **(c) D10:** a faxina inativa não bloqueia a conclusão. O critério "`recordDone` recusa faxina inativa" foi cumprido no sentido de não agir e devolver que não mudou.
  - **(d) D11:** `start` só até a data operacional.
  - **(e) D12:** o item terminado sai do plano publicado, sem aviso à camareira.
  - **(f) Fotos (I1 da Task 2):** a foto obrigatória precisa ser de quem iniciou o checklist, também quando a Governanta conclui (regra do `checklists`). É decisão de produto.
  - **(g)** O `checklistExecutionId` vai nas respostas.
  - **(h)** `foundGuestCount` sem `foundOccupancy` dá `REQUIRED` em `foundOccupancy`, e `answers` não nulo, inclusive `[]`, numa tarefa sem checklist dá `400`.
  - **(j)** A camareira não consegue iniciar online a primeira tarefa até a E19, porque não tem de onde tirar o `If-Match`.
  - **(k)** Quando a Governanta inicia a tarefa, ela vira a executora do checklist, e a foto que a camareira mandar depois é recusada na conclusão.
  - **(i) D1:** o mesmo `403` para tarefa alheia e inexistente com `OWN`. Isso diverge do `checklists`, que dá `404` na leitura.
- Notas para a próxima etapa:
  - **E19:**
    - o feed `housekeeping-tasks` precisa levar a `version` de cada tarefa, porque a camareira não tem outro jeito de obter o `ETag`;
    - `HousekeepingTaskExecution` aceita `expectedVersion` nulo e o instante corrigido;
    - os comandos offline usam `ChecklistPhotoPolicy.TOLERATE`;
    - uma linha legada em `IN_PROGRESS` sem `in_progress_since` daria 500 no `pause` (não existe dado real assim).
  - **E19, mais:** uma observação numa UH desativada (`ROOM_INACTIVE`) desfaz a conclusão inteira; no offline, isso vira comando recusado.
  - **E05:** reprovação ou aprovação de tarefa `AWAITING_INSPECTION` cuja UH já perdeu a pendência (D3).
  - **Para o `arquiteto`:**
    - nota na SPEC §8.4 sobre o estado da conclusão (D3);
    - a transição para `CANCELLED` não tem etapa dona no roadmap;
    - a publicação do plano não publica `housekeeping.task` por tarefa;
    - o 403 da tarefa alheia pode virar `409 CONCURRENT_UPDATE` se outra transação segurar a linha além do `lock_timeout` (vazamento marginal de existência, aceito pela D9).
  - **Testes:**
    - modelos gerais HOUSEKEEPING não são duráveis no banco compartilhado (helper acima);
    - o `HousekeepingTaskLockTest` serializa as operações e não prova a ausência de ciclo com duas operações em andamento;
    - as janelas de tempo do `HousekeepingTaskRealtimeTest` são as primeiras suspeitas se houver intermitência.
- Capturas do brain: `00 Inbox/2026-11-19 - ZenyHotelManager - atualização m6-operacao-fechamento E04 - 44c67e4e.md`.

---

## E19 — Comandos offline, conflito RN-22 e feed da camareira — #concluida

**Objetivo:** levar a execução da E04 para o app da camareira offline (RF-GOV-04, RN-22, ARCHITECTURE §6.2 e §9): comandos offline das mesmas ações, conflito RN-22 registrado e sinalizado com histórico de posse (H18 b) e o feed das tarefas da camareira sem dado de hóspede (RN-13).

**Por que agora:** é o primeiro módulo de negócio a usar a sincronização offline do M2 de ponta a ponta, e o padrão daqui vale para a inspeção (E05), os achados e danos (E06) e a manutenção (E08).

**Dependências:** E04. Mesmo módulo que E05, E06 e E22 (sem paralelismo com elas).

**Escopo:** `housekeeping`, com um tipo novo no catálogo do `notifications`:

- **Comandos** `housekeeping.<verbo>-task` (iniciar, pausar, retomar, concluir, recusar e tentar de novo), tratados pelo mesmo caso de uso da E04, com idempotência por `commandId`. Acima da tolerância do relógio, o comando é **aplicado com o alerta `CLOCK_SKEW_EXCEEDED`**, como manda o protocolo, e não rejeitado; os instantes gravados seguem a ordem da E04 (`max(occurredAt corrigido, última transição)`).
- **Checklist offline:** o iniciar recebe o `versionId` que o aparelho tem (precedente `checklists.start-execution`), e a conclusão leva as respostas.
- **Histórico de posse:** toda atribuição, reatribuição, retirada (`ASSIGNED → PENDING`, H19 a) e cancelamento grava quem foi dona da tarefa e quando, inclusive pela publicação do plano da E03.
- **Conflito RN-22** (H18 b): o comando de quem **foi** dona e perdeu a tarefa (reatribuída, retirada ou cancelada) é aplicado com alerta. O que ela fez, inclusive as respostas do checklist, fica registrado; a tarefa ganha a marca de conflito; a governança recebe um aviso de um **tipo novo** no catálogo do `notifications`, com o número da UH e sem dado de hóspede. A tarefa e a UH **não** mudam, e a governanta resolve. Quem **nunca** foi dona recebe `REJECTED` com o código do escopo.
- **Feed:** fonte `housekeeping-tasks` com as tarefas abertas da camareira, inclusive as de datas anteriores (o cursor leva a data; nota da E03), **sem `notes`** (H18 c) e sem nome, documento, contato nem id de hóspede ou de reserva (RN-13). As colunas de mudança e o gatilho `shared.mark_offline_change` entram na tarefa (ARCHITECTURE §9; notas da E02 e da E03). A tarefa que sai do escopo da camareira (reatribuída, retirada, concluída ou cancelada) gera *tombstone* para ela. As UHs já vêm da fonte `rooms`.
- **Padrão offline** registrado no handoff (nomes, payload, código de recusa, alerta, posse e feed) para a E05, a E06 e a E08.

**Fora de escopo:** resolução do conflito pela governanta além do registro e do aviso (uma ação de resolver, se pedida, vira etapa nova); inspeção offline (E05); achados e danos offline (E06).

**Critérios de aceite:**

- [x] Um lote offline com iniciar e concluir produz o mesmo estado que as chamadas online da E04; o mesmo `commandId` reenviado devolve o resultado anterior sem reaplicar.
- [x] Um `occurredAt` além da tolerância do relógio é aplicado com `CLOCK_SKEW_EXCEEDED`, e nenhum instante gravado fica antes da última transição da tarefa.
- [x] Iniciar offline com o `versionId` do aparelho abre a execução dessa versão, mesmo que o modelo tenha publicado outra depois.
- [x] Ex-dona da tarefa (reatribuída, retirada ou cancelada): o comando volta aplicado com o alerta de conflito, o que ela fez fica registrado com as respostas do checklist, a tarefa fica marcada, um usuário com `housekeeping.assignments.manage` recebe o aviso do tipo novo, e a tarefa e a UH não mudam.
- [x] Quem nunca foi dona da tarefa recebe `REJECTED` com o código do escopo, sem registro de conflito.
- [x] O feed da camareira traz só as tarefas dela, inclusive as abertas de datas anteriores; reatribuir gera o *tombstone* para a antiga dona.
- [x] O corpo do feed, com tarefas e UHs, não contém `notes`, nome, documento, contato nem id de hóspede ou de reserva de uma reserva hospedada na UH do teste.
- [x] Os testes que baixam `/api/v1/sync/changes` chamam antes `InventoryListenersQuiescence.await(jdbc)`.
- [x] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato (`./gradlew checkOpenApi`, tabela do `CLAUDE.md`) → sucesso.

**Revisão humana:** sim. Fixa o padrão offline que a E05, a E06 e a E08 repetem, e o tratamento de RN-22 e RN-13 (H21 f).

**Notas para o plano:** H2, H18 (b e c), H21 e o handoff da E04. Ler RN-13, RN-22, ARCHITECTURE §6.2 e §9 e a nota `ZenyHotelManager - Sincronização offline`. O protocolo (`shared/OfflineSync.kt`) já tem as duas saídas: rejeitar lançando `DomainException` e aplicar com alerta devolvendo `OfflineCommandOutcome.warnings`; não reimplementar a base (H2). A ARCHITECTURE §6.2 ainda mostra o comando como `CompleteHousekeepingTask` e a conclusão do checklist por dentro dele: o nome vigente é o desta etapa, e o diagrama pode ser atualizado no handoff. O conflito não é transição inválida: uma transição inválida da própria dona continua `REJECTED` com o código da E04. A posse precisa existir antes de o conflito ser testado: tarefas atribuídas antes desta etapa não têm histórico, e o plano decide como tratá-las (nenhuma está em produção). Armadilhas do formato antigo: comando descartado em silêncio; dado de hóspede no feed; tarefa da nova dona alterada pelo comando da antiga.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Relógio do celular muito adiantado ou atrasado: a correção de defasagem do M2 mais a regra de ordem dos instantes; o critério testa o `occurredAt` fora da tolerância.
- O registro do conflito guarda as respostas do checklist de uma camareira que já não é dona: elas não podem virar a execução oficial da tarefa. Mitigação: o critério confere que a tarefa e a UH não mudam.

**Rollback / migração:** registros novos de posse e de conflito e colunas de mudança na tarefa, no schema `housekeeping`, sem apagar dados.

**Evidências e handoff:**

- Plano: `docs/superpowers/plans/2026-11-19-m6-operacao-fechamento-e19.md`, com 3 fatias. Revisão de arquitetura (`revisor-arquitetura`, opus): aprovada com notas na rodada 2.
  - **Rodada 0, bloqueada por 8 Importantes:**
    - o escopo `ALL` era ignorado;
    - os nomes das colunas de mudança;
    - o tipo de aviso estava no módulo errado;
    - um critério não tinha teste;
    - `assigned_user_id` criava uma segunda fonte de verdade;
    - o formato da posse ficou em aberto;
    - texto livre numa tabela só de inserção;
    - a fatia 2 estava subdimensionada.
  - **Rodada 1, bloqueada por 2 Importantes:**
    - versão do aparelho de outro modelo sem sinal;
    - `hasConflict` no feed antes de a coluna existir.
- Commits: branch `roadmap/m6-operacao-fechamento`, de `f2a4cfa` (início) a `6f47934`.
  - **Task 1 (comandos offline):** `da51bbe`, `42cb6fe` e `3f82b37` (desenvolvedor).
  - **Task 2 (posse, feed e *tombstones*):** `9e0e02e`, `13c4250` e `f96d457` (desenvolvedor) e `3224e4a` (testador).
  - **Task 3 (conflito RN-22):** `9db6594` e `43e0cd0` (desenvolvedor) e `0801003` (testador).
  - **Rodadas do fim da etapa:**
    - `d5cb695`: a lista exata de tipos de aviso no `NotificationsApiTest` (desenvolvedor);
    - `6f47934`: entrada própria do conflito na trilha, limites numéricos no resumo e descrição do contrato (desenvolvedor).
  - **Testador da etapa:** `0064b0e`, só `src/test`. O `Soul: testador` ficou num parágrafo separado e não é lido como trailer (`SEM_SOUL` na verificação mecânica). Decisão: não reescrever o histórico.
  - Cada fatia passou por desenvolvedor → verificação mecânica → `revisor-seguranca`. As três foram aprovadas sem Crítico nem Importante.
- Entregue:
  - **Comandos** `housekeeping.start-task`, `pause-task`, `resume-task`, `complete-task`, `refuse-task` e `retry-task` (`HousekeepingOffline`, `HousekeepingTaskOfflineHandlers`):
    - usam a execução da E04 com o `occurredAt` corrigido e a foto em `TOLERATE`, com os alertas `CHECKLIST_PHOTO_MISSING` e `INVALID`;
    - o `answeredAt` é corrigido pela defasagem, e a idempotência é pelo `commandId`;
    - uma entrada de trilha por comando (`housekeeping.task.<verbo>-offline`).
  - **Posse e escopo:**
    - com `ALL`, aplica direto;
    - com `OWN`, a decisão é tomada com a tarefa travada: a ex-dona vira conflito, a dona atual aplica, e quem nunca foi dona (ou não tem colaborador) recebe `REJECTED RESOURCE_OUT_OF_SCOPE`.
  - **Versão do checklist do aparelho:**
    - do mesmo modelo: abre com `CHECKLIST_VERSION_OUTDATED` se não for a vigente;
    - de outro modelo HOUSEKEEPING, ou tarefa sem modelo: abre com `HOUSEKEEPING_TASK_CHECKLIST_TEMPLATE_MISMATCH` e o desvio na trilha;
    - inexistente ou de outro contexto: `REJECTED HOUSEKEEPING_TASK_CHECKLIST_VERSION_INVALID`.
  - **Posse:** `housekeeping.task_assignment(id, task_id, employee_id, assigned_at)`, só de inserção, com uma linha a cada atribuição a uma camareira. As tarefas já atribuídas foram preenchidas na migração.
  - **Feed** `housekeeping-tasks`:
    - permissão `housekeeping.tasks.execute`; com `OWN`, a camareira é resolvida pelo `StaffApi.employeeOfUser`;
    - traz as tarefas abertas, inclusive de datas anteriores, com `version`, `checklistExecutionId`, `refusalReason` e `hasConflict`, sem `notes` nem dado de hóspede ou de reserva;
    - gera *tombstone* derivado da linha quando a tarefa deixa de ser aberta, e *tombstone* de escopo para a ex-dona na reatribuição ou na retirada;
    - colunas `change_seq`/`change_xid` com o gatilho.
  - **Conflito RN-22:**
    - `housekeeping.task_conflict`, só de inserção, com um resumo estruturado sem texto livre: `text`/`note` viram booleano, as fotos só pela contagem e sem vínculo, as chaves só no formato do `checklists` e os números só dentro dos limites do `checklists`;
    - `has_conflict` muda a `version` e publica `housekeeping.task`;
    - entrada própria `housekeeping.task-conflict` na trilha;
    - aviso `housekeeping.task-conflict` (tipo do próprio módulo) uma vez por par (tarefa, colaborador);
    - alerta `HOUSEKEEPING_TASK_CONFLICT`;
    - status, tempos, checklist e UH não mudam.
  - A ARCHITECTURE §6.2, §9 e §10 registram os nomes e as mensagens.
- Revisão final: `revisor-seguranca` (opus) sobre `1f9d6b7..d5cb695`, "With fixes", 0 Crítico e 1 Importante.
  - I1: o conflito não tinha entrada própria na trilha, e do segundo conflito por tarefa em diante não sobrava entrada nenhuma. Foi corrigido em `6f47934`, junto com os Menores M1 (limites numéricos no resumo) e M4 (contrato).
  - A re-revisão escopada (`d5cb695..6f47934`) deu os achados por resolvidos, sem Crítico nem Importante novo, aprovou os 12 testes do testador e julgou justificado o `SEM_SOUL`. Restou 1 Menor: os limites numéricos copiados do `checklists` podem divergir.
- Verificação:
  - **Gate:** `./gradlew build` em `6f47934` → BUILD SUCCESSFUL em 15m 50s, com 4483 testes e 0 falhas.
    - Em `0801003`, o gate foi vermelho por um teste legado (`NotificationsApiTest`) que fixa a lista exata de tipos de aviso; foi corrigido em `d5cb695`.
    - Em `d5cb695`, 4469 testes e 0 falhas.
  - **oasdiff:** sem `ERR`.
  - **Scanner:** `osv-scanner` 2.7.1 (426 + 74 pacotes): 0 Critical/High, 2 Medium e 3 Low, todos anteriores. Lockfiles sem mudança.
  - **Integridade:** `check_test_integrity.py --base 1f9d6b7` → exit 0, sem apontamento, com 1 aviso `SEM_SOUL` em `0064b0e` (trailer em parágrafo separado; justificado na re-revisão).
- Critérios verificados:
  - **Comandos:** `HousekeepingTaskOfflineTest` (13).
  - **Feed e posse:** `HousekeepingTaskFeedTest` e `AssignmentPlanOwnershipTest`.
  - **Conflito:** `HousekeepingTaskConflictTest` (8).
  - **Ataque:** `HousekeepingTaskOfflineAttackTest` (12; testador).
  - **Schema e privilégios:** `HousekeepingTaskSchemaTest` e `DatabaseRolesTest`.
- Decisões para o usuário (tomadas pela opção recomendada, revisáveis; a revisão humana foi substituída pela autorização do usuário neste lote). É o padrão que a E05, a E06 e a E08 repetem:
  - **(a) D1:** a versão do checklist de outro modelo HOUSEKEEPING é aceita offline, com alerta e registro na trilha. **Isso permite concluir sem os itens obrigatórios do modelo do tipo**: a governanta só vê isso pela trilha, sem marca na tarefa nem aviso. A alternativa é dar um aviso à governança ou uma marca na tarefa, como no conflito.
  - **(b) D3:** a posse guarda só as atribuições, sem a saída nem o motivo. O roadmap pedia "quem e quando, inclusive retirada e cancelamento".
  - **(c) D4:** o conflito fica registrado sem texto livre e sem vincular as fotos, que se perdem. O roadmap pedia "o que ela fez, inclusive as respostas do checklist".
  - **(d) D5:** o aviso de conflito sai uma vez por par (tarefa, colaborador) e segue as preferências. O sinal durável é o `hasConflict`.
  - **(e) D6:** a troca do vínculo usuário ↔ colaborador no `staff` não reenvia o feed.
  - **(f)** A recusa por não perturbe offline liga o sinal pela ocupação atual da UH, e não pela do `occurredAt`.
  - **(g)** A marca de conflito não muda o `updatedAt`, só a `version`.
  - **(h) Mudança na E04:** o `complete` online também passa a usar `max(at, lastTransitionAt)` no checklist, na observação e na faxina.
- Notas para a próxima etapa:
  - **Padrão offline (D7) para E05, E06 e E08:**
    - comandos `housekeeping.<verbo>-<objeto>`;
    - payloads próprios do módulo;
    - recusa por `DomainException`, que vira `REJECTED` com o código; alertas por `warnings`; foto `TOLERATE` com alertas;
    - `occurredAt` corrigido e `max(instante, última transição)`;
    - com `ALL`, aplica direto; com `OWN`, posse decidida com a tarefa travada (ex-dona vira conflito, nunca dona é recusada);
    - fonte em `infrastructure` com a permissão de execução, sem texto livre, e *tombstones* de escopo pelo `OfflineTombstones`.
  - **Para o `arquiteto`:**
    - a etapa que criar o cancelamento de tarefa grava o *tombstone* de escopo se limpar a camareira;
    - a etapa de expurgo ou remoção de tarefa trata as FKs de `task_conflict` e `task_assignment`;
    - `recordLeftScope` está na store, e não no serviço;
    - não há leitura nem resolução do registro de conflito;
    - os decimais dos payloads offline passam por `double` no protocolo do `shared`: acima de uns 15 algarismos perdem precisão, e o expoente fica limitado a uns 1e308 (o que afasta o DoS suspeito em `ChecklistAnswers`);
    - os limites numéricos do resumo do conflito são cópia dos do `checklists` e podem divergir;
    - não há teste dedicado da concorrência comando offline × ação online × reatribuição (a ordem de trava é a da E04).
  - **Testes:** `HousekeepingExecutionTestSupport.assign` grava a posse; o `NotificationsApiTest` fixa a lista exata de tipos de aviso, então cada tipo novo entra nela.
- Capturas do brain: CAPTURA_E19.

---

## E05 — Inspeção e liberação da UH — #em-andamento

**Objetivo:** implementar a inspeção (RF-GOV-06): a supervisora executa o checklist de inspeção, aprova (UH Inspecionada, tarefa `DONE`) ou reprova (UH Suja, tarefa `REJECTED` de volta à camareira com os itens apontados); inspeção voluntária de UH Limpa; aviso em tempo real à recepção quando a UH fica pronta (RF-GOV-07), por SSE e por notificação de UH liberada (RF-NOT-01).

**Por que agora:** fecha o ciclo de limpeza e libera UHs para check-in (RN-05).

**Dependências:** E19 (e, por ela, a E04). Independente de E06 (só rodam juntas em branches separadas; conflito em `api/openapi.yaml` e no controller de tarefas).

**Escopo:** `housekeeping` — `Inspection` ligada à tarefa e a uma execução de checklist; endpoints online e handlers offline; `rooms.markInspected`/`markDirty` (H3); notificação à camareira na reprovação (RF-NOT-01); evento de UH pronta no SSE e notificação de UH liberada via `notifications` para a recepção (RN-05 avaliada por `rooms`).

**Fora de escopo:** indicadores de reprovação (E09).

**Critérios de aceite:**

- [ ] Aprovação → UH Inspecionada e tarefa `DONE`; UH vaga e sem OOO passa a pronta para check-in (RN-05).
- [ ] Reprovação → UH Suja, tarefa `REJECTED` com os itens reprovados, camareira notificada; refazer leva a `IN_PROGRESS`.
- [ ] Inspeção voluntária de UH Limpa → Inspecionada.
- [ ] Inspeção sem a permissão própria → `403`.
- [ ] Cliente inscrito no tópico de UHs do SSE recebe o evento quando a UH fica pronta.
- [ ] Inspeção offline funciona pelo mesmo padrão de comandos offline da E19.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato (`./gradlew checkOpenApi`, tabela do `CLAUDE.md`) → sucesso.

**Revisão humana:** não

**Notas para o plano:** SPEC RF-GOV-06, RF-GOV-07 e RN-05; máquina de estados e conclusão no handoff da E04, padrão de comandos offline e histórico de posse no handoff da E19. A E04 registra o estado da tarefa com inspeção cuja UH não mudou na conclusão (UH já Limpa ou Inspecionada, ou área; H18 d): a `RoomsApi.rejectInspection` exige inspeção pendente, então a reprovação desse caso precisa de regra própria. Os tipos `notifications.room-released` e `notifications.inspection-rejected` já existem no catálogo do `notifications` (nota `ZenyHotelManager - Módulo notifications`). Armadilhas do formato antigo: a UH nunca fica pronta com a inspeção reprovada; quem limpou não inspeciona sem a permissão de inspeção.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Nenhum relevante.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E06 — Achados e perdidos e danos — #pendente

**Objetivo:** implementar achados e perdidos (RF-GOV-08), com guarda, devolução e descarte após o prazo configurado, e danos (RF-GOV-09), com revisão pela recepção ou gerência e lançamento opcional na conta do hóspede via `billing`. Ambos podem ser abertos manualmente, offline ou por ocorrência de `ChecklistCompleted`.

**Por que agora:** completa as ocorrências da governança previstas para a Fase 1.

**Dependências:** E19 (e, por ela, a E04). Independente de E05 (só rodam juntas em branches separadas; conflito em `api/openapi.yaml`).

**Escopo:** `housekeeping`:

- `LostAndFoundItem`: registro, local de guarda, devolução (a quem, quando, por quem), descarte/doação; job com ShedLock que lista itens vencidos pelo prazo de guarda de `hotel` (RF-CFG-04) para decisão (não descarta sozinho).
- `DamageReport`: registro com custo estimado, revisão, `chargeTo(folio)` lançando via `billing` com chave de idempotência (H5) ou `waive(reason)`.
- Listener de `ChecklistCompleted` para ocorrências do tipo achado ou dano (H2).
- Handlers offline para registro pela camareira; revisão e cobrança só online.

**Fora de escopo:** fotos e anexos de achados e danos, que a SPEC §5.14 não pede. Se forem pedidos depois, `housekeeping` → `files` é permitido pela regra por camadas (acrescentar à tabela §3.1).

**Critérios de aceite:**

- [ ] Achado registrado offline aparece na listagem; devolução registra a quem, quando e por quem.
- [ ] Após o prazo de guarda, o job lista o item como vencido; descarte ou doação registra motivo e usuário.
- [ ] Dano não revisado não pode ser lançado (`409`); revisado e cobrado gera um único lançamento na conta da reserva (repetição não duplica).
- [ ] Dano dispensado registra motivo e não gera lançamento.
- [ ] `ChecklistCompleted` com ocorrência de dano ou achado cria o registro correspondente.
- [ ] Respostas para a camareira não contêm dados do hóspede.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato (`./gradlew checkOpenApi`, tabela do `CLAUDE.md`) → sucesso.

**Revisão humana:** não

**Notas para o plano:** SPEC RF-GOV-08 e RF-GOV-09; H2 e H5. Convenção do M2 para quem consome `ChecklistCompleted` (nota `ZenyHotelManager - Módulo checklists`): o evento pode ser reentregue, então deduplique por `executionId` + `itemKey` + tipo da ação; a execução registrada à parte de uma rotina (`duplicateOf`) publica o próprio evento com as ações e o consumidor decide se as aplica. Padrão de comandos offline no handoff da E19; jobs adiados no `@IntegrationTest` e chamados direto nos testes (nota `ZenyHotelManager - Testes de integração`). Armadilhas do formato antigo: dano lançado duas vezes ou sem revisão; camareira vendo dados do hóspede ao registrar dano.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Dano em UH sem reserva ativa: permitir só registro e dispensa; cobrança exige reserva vinculada.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E22 — Hora prevista de chegada e prioridade do early check-in — #pendente

**Objetivo:** guardar na reserva a hora prevista de chegada (H20 b) e devolver à sugestão de distribuição da E03 a prioridade do early check-in que a H17 adiou (RF-GOV-03, item 2: "prioriza UHs com chegada prevista ou early check-in").

**Por que agora:** decisão do usuário (H20 a): a RF-GOV-03 é da Fase 1, e o gate do M6 declara a Fase 1 encerrada. A etapa é pequena e não toca a execução da tarefa.

**Dependências:** E03, E20 (a hora é lida com o `LocalTime` estrito da E20). Mesmo módulo que E04, E19, E05 e E06 no lado da governança (sem paralelismo com elas).

**Escopo:**

- **`reservations`:** a reserva ganha a hora prevista de chegada, opcional, em hora local do hotel (SPEC §8.2.6, registrada em 2026-11-18), informada na criação e na alteração e devolvida nas respostas, com auditoria. Mudar só a hora não muda preço, inventário nem alocação. A `ReservationsApi` passa a dar a hora das chegadas da data por UH à governança, sem dado pessoal (RN-13).
- **`housekeeping`:** a sugestão ordena as chegadas assim: primeiro o early (hora prevista antes do `checkInTime` da política do hotel, `HotelApi.policy()`), depois as demais chegadas por hora prevista, as sem hora por último entre elas, e então o resto, como na E03. O critério da E03 "chegadas e early vêm primeiro na ordem" passa a valer inteiro.

**Fora de escopo:** trazer a hora das OTAs (M8, channel manager: o mapeamento do dado da OTA vai para o roadmap do M8 na sincronização dele); cobrança de early (já existe, M5 E05); aviso em tempo real à governança quando a hora muda (a governança não escuta eventos de reserva, H19 b: a sugestão lê a hora na hora de sugerir); a coluna na view `report_reservations` (a E09 decide se precisa).

**Critérios de aceite:**

- [ ] Criar e alterar uma reserva com a hora prevista grava e devolve o valor; omitida, a reserva segue sem hora; `null` na alteração remove a hora.
- [ ] Hora fora de `HH:mm`/`HH:mm:ss` dá `400 VALIDATION_FAILED` no campo (regra da E20).
- [ ] Alterar só a hora não muda o preço, o inventário nem a alocação da reserva, e fica na trilha.
- [ ] Na sugestão, com a mesma entrada, a UH com chegada early vem antes das chegadas sem early, que vêm antes das demais tarefas; entre as chegadas, pela hora prevista, e as sem hora depois das com hora (teste de domínio com determinismo e teste de API).
- [ ] O dado que a governança recebe do `reservations` não contém nome, documento nem contato de hóspede.
- [ ] `.github/scripts/check-api-breaking.sh` não acusa `ERR` (campo opcional novo).
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Revisão humana:** não

**Notas para o plano:** H17, H19 (b e d), H20 (a e b); handoff da E03 (D3: a chegada é recalculada na sugestão; `ArrivalsByRoom` foi extraído na E03) e da E02 (D7: `ReservationsApi.assignedRooms`; D8: `arrivalExpected` é um retrato da geração). A ordem da hora usa o fuso do hotel (`HotelApi.timezone()`). Notas de tema: `ZenyHotelManager - Módulo reservations (ciclo de vida, alocação, grupos e busca)` e `ZenyHotelManager - Módulo housekeeping`. Uma alteração de reserva hoje publica `ReservationChanged`; se a hora sozinha deve publicá-lo, o plano decide e registra (a governança não escuta, H19 b; a distribuição de canais do M8, sim).

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- A hora vinda das OTAs no M8 pode ter outro formato ou fuso. Mitigação: o dado é hora local do hotel, e o M8 converte na entrada.

**Rollback / migração:** coluna opcional nova na reserva, no schema `reservations`, sem apagar dados; o contrato só cresce.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E07 — Ordens de serviço corretivas e OOO/OOS — #pendente

**Objetivo:** criar o módulo `maintenance` com a OS corretiva (RF-MAN-01, RF-MAN-02, RF-MAN-03): local, categoria, prioridade, descrição, anexos, origem, responsável interno ou terceiro; estados aberta, em andamento, aguardando peça/terceiro, concluída, cancelada; custos de mão de obra, terceiros e peças informadas; bloqueio da UH em OOO/OOS via `rooms` e retorno como Suja ao concluir.

**Por que agora:** a manutenção corretiva é parte da Fase 1 e alimenta o relatório operacional (E09).

**Dependências:** nenhuma dentro deste roadmap; exige o gate de M5 `#concluida`. Independente de E01–E06 e E10 (conflito esperado em `api/openapi.yaml` se rodarem em branches separadas).

**Escopo:** pacote `maintenance` (a criar):

- `WorkOrder` com número legível (gerador de M0), máquina de estados, custos em `Money`; terceiro como texto livre e custo de peças como valor informado até M9.
- Anexos via `files` (dependência permitida).
- `blockRoom(kind, from, to)`: pede OOO ou OOS a `rooms` com período previsto; OOO exige a permissão/step-up definida em M2 (RF-ACS-08). Conclusão devolve a UH (normalmente como Suja).
- Notificação de OS urgente via `notifications` (RF-NOT-01).
- Escopo `OWN` para o técnico; endpoints CRUD e de ação em `/api/v1/work-orders`.

**Fora de escopo:** abertura por checklist e offline (E08); ativos, preventivas e peças do estoque (M9, M10).

**Critérios de aceite:**

- [ ] Testes de domínio cobrem as transições da OS (aberta, em andamento, aguardando, concluída, cancelada), válidas e inválidas.
- [ ] OS com anexo grava o anexo via `files` e o devolve só por URL assinada.
- [ ] Pedido de OOO sem aprovação → `403` `APPROVAL_REQUIRED`; em UH ocupada → erro com `code` estável (RN-07); com aprovação → UH em OOO pelo período.
- [ ] Concluir a OS encerra o OOO/OOS e deixa a UH Suja.
- [ ] OS de prioridade urgente gera notificação para a manutenção.
- [ ] Técnico com escopo `OWN` lista só as próprias OS.
- [ ] `maintenance` não depende de `inventory` (`ModularityTest`); build, testes e contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato (`./gradlew checkOpenApi`, tabela do `CLAUDE.md`) → sucesso.

**Revisão humana:** não

**Notas para o plano:** SPEC RF-MAN-01 a RF-MAN-03, RN-07 e §8.2.12; ver a nota herdada do M2 "E07, OOO pela manutenção" em "Hipóteses e decisões em aberto" (a manutenção faz o próprio step-up antes da API de OOO de `rooms`, que não confere permissão). A tabela da ARCHITECTURE §3.1 já lista `maintenance` → `inventory`, mas a aresta não pode ser usada neste marco. O tipo `notifications.urgent-work-order` já existe no catálogo do `notifications` (nota `ZenyHotelManager - Módulo notifications`). Anexos pela `FilesApi` com finalidade própria do módulo (nota `ZenyHotelManager - Módulo files`). Armadilhas do formato antigo: OOO sem step-up ou em UH com hóspede (RN-07); OS concluída sem devolver a UH.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- OOO com reserva alocada no período (RN-07): `rooms` não conhece reservas; quem alerta e exige a realocação é `reservations`, ao escutar `OutOfOrderChanged` (M4). A OS registra o bloqueio quando `rooms` confirma a abertura do OOO, sem esperar a realocação.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E08 — OS por checklist e app offline da manutenção — #pendente

**Objetivo:** abrir OS automaticamente a partir de ocorrências `MAINTENANCE` de `ChecklistCompleted` (RF-CHK-05, ARCHITECTURE §6.2) e permitir ao técnico operar a OS offline (RF-MAN-07), na mesma base de sincronização do app da governança.

**Por que agora:** completa a manutenção corretiva da Fase 1 e liga checklists à manutenção.

**Dependências:** E07 (sem paralelismo com ela).

**Escopo:** `maintenance` — listener de `ChecklistCompleted` (idempotente por execução e item); handlers offline para abrir OS (ID gerado no cliente), iniciar, pausar, concluir e registrar observações; OS e locais do técnico no feed de sync, sem dados pessoais (RN-13).

**Fora de escopo:** peças do estoque (M9).

**Critérios de aceite:**

- [ ] `ChecklistCompleted` com item `MAINTENANCE` marcado "não ok" gera exatamente uma OS com origem checklist e vínculo à execução; reprocessar não duplica.
- [ ] OS aberta offline é persistida com o UUID do cliente; o mesmo `commandId` reenviado não cria outra.
- [ ] Iniciar, pausar e concluir offline produzem o mesmo estado que online.
- [ ] O feed de sync do técnico contém suas OS e locais, sem dados pessoais de hóspede.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato (`./gradlew checkOpenApi`, tabela do `CLAUDE.md`) → sucesso.

**Revisão humana:** não

**Notas para o plano:** H2. Ler o handoff de E07 e, se já concluída, o da E19 (padrão de comandos offline, que saiu da E04 na divisão de 2026-11-18); a E19 não é dependência, então sem ela o padrão vem da nota `ZenyHotelManager - Sincronização offline` (registro criado offline usa o `commandId` como id). Consumo de `ChecklistCompleted` com a mesma convenção da E06 (deduplicar por `executionId` + `itemKey` + tipo; execução à parte com `duplicateOf`; nota `ZenyHotelManager - Módulo checklists`). Testes que baixam `/api/v1/sync/changes` chamam antes `InventoryListenersQuiescence.await(jdbc)` (nota `ZenyHotelManager - Testes de integração`). Armadilhas do formato antigo: OS duplicada por reprocessamento; OS aberta offline com o id trocado pelo servidor; dados pessoais no feed.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Nenhum relevante.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E09 — Relatórios operacionais e painel do dia — #pendente

**Objetivo:** criar o módulo `reporting` com o painel do dia (RF-REL-01), os relatórios operacionais de governança e manutenção (RF-REL-06, RF-GOV-11), o snapshot `DailyStatistics` (SPEC §8.2.9), a exportação CSV/XLSX (RF-REL-07), a geração e o arquivo de PDFs por data e o relatório de pendências do fechamento (RF-FEC-04), consultando só views `report_*` publicadas pelos módulos (ARCHITECTURE §11).

**Por que agora:** o fechamento (E13) gera o snapshot e os PDFs por meio de `reporting`, e a gerência precisa do painel na operação.

**Dependências:** E05, E08. Independente de E10; E09, E10 e E11 tocam `api/openapi.yaml` (conflito se rodarem em branches separadas).

**Escopo:**

- Views `report_*` (migrações nos schemas donos, como parte da API de cada módulo): `rooms` (status), `reservations` (chegadas, saídas, hospedados, ocupação futura), `housekeeping` (tarefas, tempos, créditos, reprovações), `maintenance` (OS por estado, categoria e tempo), `billing` (contas com saldo de hóspedes que saíram, caixas abertos). `fnrh.report_submissions` vem da M5 E08.
- `reporting` (a criar): `GET /api/v1/reports/day-panel`, relatórios de governança e manutenção com filtros, exportação `?format=csv|xlsx` (H7), relatório de pendências.
- API pública para o fechamento: `snapshotDailyStatistics(date)` (UHs disponíveis, OOO, vendidas, cortesias, uso da casa, chegadas, saídas, no-shows, cancelamentos, receitas por departamento; regras de Q-15 — H9) e `archiveDayReports(date)` (PDFs renderizados por `printing` e guardados em `files`, com índice por data no schema `reporting` — H6, H7), ambos idempotentes por data.
- `GET /api/v1/reports/archive?date=…` e download dos PDFs arquivados por URL assinada de `files`.
- Arestas `reporting` → `printing` e `reporting` → `files` acrescentadas à tabela da ARCHITECTURE §3.1.

**Fora de escopo:** KPIs comparativos e relatórios gerenciais (M10).

**Critérios de aceite:**

- [ ] `GET /api/v1/reports/day-panel` devolve chegadas, saídas, hospedados, UHs por status e ocupação de hoje e dos próximos dias, conferidos contra dados semeados.
- [ ] Relatórios de governança (UHs e créditos por camareira, tempo médio por tipo, taxa de reprovação) e de manutenção (OS por estado, categoria e tempo) conferidos contra dados semeados.
- [ ] `?format=csv` e `?format=xlsx` geram arquivos que, relidos no teste, têm as mesmas linhas da resposta JSON.
- [ ] `snapshotDailyStatistics(date)` chamado duas vezes deixa um único registro; cortesia e uso da casa seguem Q-15.
- [ ] `archiveDayReports(date)` renderiza os PDFs via `printing` e os guarda em `files` uma única vez por data; `GET /api/v1/reports/archive?date=…` os lista e devolve URLs assinadas.
- [ ] A tabela da ARCHITECTURE §3.1 lista `printing` e `files` entre as dependências de `reporting`.
- [ ] Relatório de pendências lista contas com saldo de hóspedes que saíram, caixas não fechados e FNRH pendentes.
- [ ] Teste de arquitetura confirma que `reporting` não referencia classes nem tabelas internas de outros módulos.
- [ ] Nenhum relatório operacional de governança ou manutenção expõe dados pessoais de hóspede.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato (`./gradlew checkOpenApi`, tabela do `CLAUDE.md`) → sucesso.

**Revisão humana:** não

**Notas para o plano:** H6, H7 e H9; ARCHITECTURE §11 ("Leitura para relatórios") e a convenção das views na nota `ZenyHotelManager - Schemas, migrações e views de relatório` (nome, contrato no comentário da migração, `GRANT SELECT`/`REVOKE` por view, coluna publicada que não muda de sentido). As views publicadas no M2 a M4 foram feitas para o `reporting` deste marco ler (ver a mesma nota); prova de "só as views" num teste que roda as consultas com um papel que só tem `SELECT` nas views (padrão do `ChecklistReportsTest`). Período em datas convertido no fuso do hotel. H7: a biblioteca XLSX entra no catálogo `gradle/libs.versions.toml`, sem versão literal no build, com os lockfiles regravados e o scanner sem High/Critical novo. H6: a `FilesApi` não grava conteúdo pelo servidor; a gravação é acréscimo desta etapa. Os PDFs seguem o modelo dono + `printing` da M5 E11 (`ModuleDocumentTemplates`, templates em `templates/reporting/`). O formato antigo pedia `ModularityTest` mais uma regra ArchUnit para "`reporting` não referencia classes de outros módulos". Armadilhas do formato antigo: snapshot recalculado gerando duplicata; regra de cortesia e uso da casa diferente de Q-15; dados pessoais em relatório operacional.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Views acopladas ao esquema interno de cada módulo: tratá-las como API do módulo e testá-las no próprio módulo dono.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E13 — Snapshot e arquivo do fechamento — #pendente

**Objetivo:** acrescentar ao fechamento, antes do avanço da data, as etapas de snapshot de `DailyStatistics` e de arquivo dos PDFs da data (RF-FEC-02), pela API pública de `reporting`.

**Por que agora:** `reporting` só existe depois da E09; o motor e as etapas financeiras já rodam desde a E11, e estas duas se encaixam neles sem mexer em dinheiro.

**Dependências:** E09, E11.

**Escopo:** `endofday` chama `snapshotDailyStatistics(date)` e `archiveDayReports(date)` do `reporting`, ambas idempotentes por data, numa posição anterior ao avanço; pendências da E09 registradas como `EndOfDayIssue`.

**Fora de escopo:** os relatórios em si (E09); pendências de notas rejeitadas e ARI (M7, M8).

**Critérios de aceite:**

- [ ] Depois de um fechamento, o snapshot da data e os PDFs ficam em `reporting`, uma única vez, mesmo com a etapa repetida ou retomada.
- [ ] Falha na geração do PDF deixa a execução retomável e a data sem avançar (teste).
- [ ] Um fechamento executado antes desta etapa existir continua válido: datas sem snapshot não quebram o painel nem os relatórios (teste).
- [ ] `endofday` depende só dos módulos permitidos (`ModularityTest`); build, testes e contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato (`./gradlew checkOpenApi`, tabela do `CLAUDE.md`) → sucesso.

**Revisão humana:** não

**Notas para o plano:** o `endofday` depende de `reporting` por exceção registrada na tabela da ARCHITECTURE §3.1, na mesma camada; não amplie essa aresta além da API de snapshot e arquivo. Leia os handoffs de E09 e E11.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Geração de PDF lenta no meio do fechamento: medir a duração na métrica da E10 e registrar no handoff.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E12 — Gate do marco M6 e fim da Fase 1 — #pendente

**Objetivo:** confirmar, a partir de uma cópia limpa do repositório, que a definição global de pronto do M6 é atendida e que o cenário ponta a ponta da Fase 1 funciona com os módulos reais; deixar o roadmap do M7 pronto para ser sincronizado.

**Por que agora:** marca o fim da Fase 1 da SPEC ("o hotel opera a hospedagem") e é o critério de entrada do M7.

**Dependências:** E06, E13, E14, E15, E16, E17, E18, E20, E21, E22 (cobrem transitivamente todas as demais, inclusive E04 e E19 pela E06; sem paralelismo).

**Escopo:**

- Teste de integração ponta a ponta (a criar): reserva com hora prevista de chegada → check-in → fechamento do dia (diária lançada, UH Suja, data avançada) → tarefa de permanência gerada, distribuída, executada offline (E19) e inspecionada → check-out com saldo zero → tarefa de saída gerada e concluída → submissões FNRH de check-in e check-out (WireMock).
- Rodar do zero numa cópia limpa: build, testes, verificação de módulos e do contrato.
- Conferir a "Definição global de pronto"; registrar pendências herdadas (ex.: homologação da FNRH de M5, se ainda bloqueada).
- Atualizar `docs/roadmaps/README.md` registrando a conclusão do M6 e da Fase 1 no histórico de revisões.

**Fora de escopo:** funcionalidades novas. Problemas encontrados voltam para a etapa de origem.

**Critérios de aceite:**

- [ ] Todos os itens da "Definição global de pronto" marcados, cada um com evidência numa cópia limpa.
- [ ] O teste ponta a ponta da Fase 1 passa usando os módulos reais (só a FNRH em WireMock).
- [ ] Pendências herdadas (ex.: homologação da FNRH) estão listadas com a ação de desbloqueio.
- [ ] `docs/roadmaps/README.md` registra a conclusão do M6 e da Fase 1 no histórico de revisões.

**Validação obrigatória:**

- Comandos oficiais de build e teste e verificação do contrato (tabela do `CLAUDE.md`) numa cópia limpa → sucesso.

**Revisão humana:** sim

**Notas para o plano:** ler o handoff de todas as etapas do marco. Convenção dos gates anteriores (nota `ZenyHotelManager - Estado por marco`): o cenário do marco também vira teste de integração na raiz do pacote de testes (como `MilestoneM3EndToEndTest` e `MilestoneM4EndToEndTest`, com os modelos de role e o `MutableClock`), e o gate confere o ambiente ponta a ponta num `docker compose` isolado, pelo proxy, registrando o que só os testes provam. A nota herdada do M2 "Rotinas de checklist" (em "Hipóteses e decisões em aberto") pede conferir aqui que as rotinas passam a ser geradas quando o `endofday` avança a data. A captura de atualização do brain desta etapa leva o fim do M6 e da Fase 1 para `ZenyHotelManager - Estado por marco`. **Do gate do M5 (handoff da M5 E12):** reaproveite `docker/gate/` (`run-gate.sh`, `gate-env.sh`, `compose.gate.yaml`, o WireMock da FNRH e o `check`/`redact`/varredura do `m5-roteiro.sh`, num roteiro novo do M6) e o padrão do `MilestoneM5EndToEndTest` (operadores pelos modelos de role, só as submissões da própria reserva, relógio devolvido no `finally`). Como o cenário da Fase 1 precisa do fechamento, o teste do marco roda no caminho próprio da E10 (H10), e o roteiro do compose roda num banco novo (`down -v` antes e depois). Os Menores do gate do M5 (falha do `docker ps` em `leftovers`, descrição de gênero com escape não ASCII na varredura, `gate.env` sobrescrito no mesmo workdir, templating sem escape JSON no mock) podem ser corrigidos aqui, se o roteiro novo tocar neles. Armadilhas do formato antigo: item sem evidência; ponta a ponta com mocks entre módulos; pendências herdadas omitidas.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O cenário ponta a ponta atravessa muitos módulos e pode ficar frágil: usar relógio fixo e dados semeados mínimos.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa (marco M7): —

---

## Histórico de revisões

- 2026-10-13 — roadmap criado.
- 2026-10-13 — aplicadas as decisões da entrevista SPEC × ARCHITECTURE (regra por camadas na ARCHITECTURE §3.1; documentos montados pelo dono e renderizados por `printing`): H6 passa a guardar os PDFs do fechamento em `files`; H7 renderiza PDFs via `printing`; H8 envia pendências do fechamento por `notifications`; E05 notifica UH liberada; E09 acrescenta `reporting` → `printing`/`files` à §3.1; E10 e E11 chamam `notifications`; fora de escopo de E06 reescrito (SPEC não pede fotos).
- 2026-10-15 — notas herdadas do M2 acrescentadas em "Hipóteses e decisões em aberto" (step-up da manutenção antes da API de OOO de `rooms`; rotinas de checklist geradas só com o `endofday`) e corrigida a contradição de camadas no risco da E07: quem alerta e exige a realocação é `reservations`, ao escutar `OutOfOrderChanged`. Tags inalteradas.
- 2026-10-22 — migrado para o formato da skill development-roadmap com superpowers (branch `roadmap/m6-operacao-fechamento`, protocolo e prompt de execução únicos, etapas sem prompt e estratégia) e para o Second Brain (notas de tema no lugar das seções do CLAUDE.md, captura de atualização por etapa); decisão do usuário.
- 2026-10-27 — protocolo de execução migrado para as souls do Second Brain: a sessão roda como `orquestrador-etapa` (`claude --agent orquestrador-etapa`), e as tarefas passam por `desenvolvedor` → `testador` → verificação mecânica dos testes → `revisor-seguranca`; a captura do brain é publicada pelo `brain-escriba`. Sem fallback para os prompts padrão do superpowers nem para `executing-plans`. Nenhuma etapa, tag, evidência ou handoff mudou.
- 2026-10-29 — protocolo de execução alinhado ao template atual da skill `development-roadmap`: o orquestrador passou de `orquestrador-etapa` para `orquestrador` (`claude --agent orquestrador`), entrou o papel "Revisor de plano" (`revisor-arquitetura`, pré-condição das souls e passo 1 do prompt) e o passo "Plano" diz que o plano passa pela revisão de arquitetura do orquestrador antes de executar. Só "Como usar", "Protocolo de execução" e "Prompt de execução" mudaram; etapas, handoffs e entradas anteriores do histórico seguem como estavam.
- 2026-11-06 — revisão de arquitetura (`docs/superpowers/specs/2026-11-06-revisao-arquitetura-roadmaps-design.md`). Protocolo e prompt alinhados ao template de 2026-11-05. O fechamento passou para o início do roadmap: E10 e E11 vêm antes da governança, porque gravam dinheiro imutável e fazem a data andar. A E11 passou a depender só da E10 e foi dividida: o snapshot e os PDFs foram para a nova E13, e o no-show na FNRH para a nova E14. O gate passou a depender de E06, E13 e E14. A E10 ganhou métricas e alerta (ARCHITECTURE §16). Hipóteses H2 a H6 conferidas no código: a H4 caiu, e a H6 e o contexto citavam a aresta `endofday` → `files`, que não existe; o nome da view da FNRH foi corrigido para `fnrh.report_submissions`. Nenhuma etapa renumerada; tags inalteradas. Depois da revisão do `revisor-arquitetura`: `BusinessDay` fica só em `hotel`, a entrega do alerta passa à M8 E18, a E14 cobra o no-show na homologação e a H2 foi fechada.
- 2026-11-12 — sincronizado com o código depois do gate do M5 (`main` em `3a42e35`) pelo `arquiteto` (skill `development-roadmap`, modo atualização; regra 2 de `docs/roadmaps/README.md`). Pré-requisito marcado como atendido, com a E09 do M5 `#bloqueada` pela exceção M5 → M6. "Contexto confirmado" ganhou a base técnica herdada do M5 (módulos, última migração `V2026_11_11_1001`, `ReservationsApi` com as transições de hospedagem e sem no-show nem consultas por data, eventos de hospedagem, `HotelApi`, `RoomsApi`, `BillingApi` e `FolioOwnerDescriber`, APIs de plataforma, views `report_*`, `FnrhGateway` e `FnrhOperation`, apoio de testes e o gate em `docker/gate/`) e as pendências do M5 com a etapa de cada uma. Hipóteses: H1 confirmada; H2 a H5 reconfirmadas; H6 ganhou a falta da gravação de conteúdo na `FilesApi`, a acrescentar pela E09; H10 (testes do fechamento num caminho próprio, isolado do banco compartilhado) **decidida pela opção recomendada pelo `arquiteto`**, revisável pelo usuário; H11 (rótulos no catálogo da FNRH) confirmada. Etapas novas no bloco "Manutenção herdada do M5", depois do fechamento: **E15** (rótulos da FNRH na ficha por porta do `frontdesk` implementada pelo `fnrh` e descrição de `frontdesk.stays.checkin`; decisões do usuário (f) e (g) do lote E11 do M5), **E16** (dívidas de teste: teste de índices da M5 E01 e `FnrhSubmissionDismissCompat.kt` duplicado) e **E17** (corpo mínimo do `guests`, decisão E15 (h) do M5; incluída a pedido do usuário em 2026-11-12). Onde entram (decidido pelo `arquiteto`, pela opção recomendada): no M6 e não no M5, cujo gate está concluído e não reabre; depois do fechamento, para não atrasar a E10 e a E11. A definição global de pronto ganhou o item da manutenção, e o gate passou a depender de E15, E16 e E17. Notas novas em E02 (KDoc dos eventos de hospedagem), E09 (H6 e o modelo dono + `printing`), E10 (H10), E11 (H10 e a chave comum com o check-out), E12 (`docker/gate/`, `MilestoneM5EndToEndTest` e os Menores do gate do M5) e E14 (`submission_operation_ck` e `rank`). Na mesma data, a ARCHITECTURE §3.2 passou a registrar a ordem interna da Hospedagem (`rates` < `reservations` < `frontdesk`). Nenhuma etapa renumerada nem removida; tags inalteradas.
- 2026-11-13 — etapa nova **E18** (faixa do cursor, rollback em toda exceção, datas estritas e log dos jobs), no bloco novo "Manutenção transversal", pelo `arquiteto` (skill `development-roadmap`, modo atualização), cumprindo a decisão do usuário H12 (handoff da E10, "Decisões do usuário", item 3). Ela resolve os quatro problemas transversais achados na E10, conferidos no código em 2026-11-13 (`main` em `9e65b3a`). Entrevista do usuário na mesma data, pelas opções recomendadas (H13): a faixa do cursor vale também para as chaves de data (6 consultas a mais que as 9 por instante); a leitura estrita vale para `format: date` e `date-time`; e o mapeador é único, com os payloads offline também estritos. Decidido pelo `arquiteto`: a faixa fica no `PageCursor`, e a validação local do `endofday` sai. A E18 entra depois da E17 e antes da governança, para que os módulos novos já nasçam sobre as regras corrigidas. Revisão humana: não. A definição global de pronto ganhou o item da E18, e o gate E12 passou a depender dela. Na mesma mudança, a ARCHITECTURE registra as decisões em §5 (rollback), §7 (cursor e datas), §11 (alcance de `date` e `timestamptz`) e §13 (log dos jobs), e o `docs/roadmaps/README.md` passou a contar 18 etapas no M6. Nenhuma outra etapa foi renumerada, removida nem mudou de tag ou conteúdo.
- 2026-11-17 — entrevista de abertura do lote (`orquestrador`): H15 (a branch da execução anterior, com a E18 e a E01 concluídas, se perdeu e as etapas são refeitas a partir da `main`) e H16 (referência inexistente no corpo do `housekeeping` dá `400 …UNKNOWN_…`), decididas pelo usuário. Tag da E18 para `#em-andamento`; nenhuma outra etapa mudou.
- 2026-11-18 — fim do lote E18–E03 (`orquestrador`): E18, E01, E02 e E03 `#concluida` (refação de E18 e E01 pela H15); H17 (early check-in adiado, decisão do orquestrador sob autorização noturna) e H18 (E04 volta a `#pendente` para o `arquiteto` dividir; conflito RN-22, `notes` e UH limpa decididos pelo usuário). Nenhuma outra etapa mudou de tag.
- 2026-11-18 — entrevista final do lote: H19 (D7 e D11 da E03, D6 da E02 e H17 confirmadas pelo usuário, com registros a cargo do `arquiteto`).
- 2026-11-18 — revisão do `arquiteto` (skill `development-roadmap`, modo atualização; `main` em `f992696`), com o código conferido para as etapas tocadas ("Base técnica do próprio M6" em "Contexto confirmado"). **Divisão da E04** (H18 a, H21 a): a E04 manteve o ID com a máquina de estados, as ações online e a conclusão, e a etapa nova **E19** ficou com os comandos offline, o histórico de posse, o conflito RN-22 e o feed; os achados da revisão do plano tentado foram distribuídos entre as duas. E05 e E06 passaram a depender da E19 (H21 e). **Manutenção transversal** (H20 c e d, H21 b a d): etapas novas **E20** (entrada estrita no corpo, na query e no caminho, `LocalTime`, inteiro e UUID estritos e `required` nos requests, com a quebra única do `oasdiff` aceita pelo usuário) e **E21** (`409 CONCURRENT_UPDATE` para `40P01`/`55P03`, logs dos quatro jobs, filtro do ShedLock e `JOSEException` do FCM), no bloco "Manutenção transversal", para rodarem antes da E04. **Early check-in** (H17, H20 a e b): etapa nova **E22** (hora prevista de chegada na reserva e prioridade do early na sugestão), dependente de E03 e E20. Hipóteses novas H20 (decisões do usuário na entrevista desta revisão) e H21 (decisões do `arquiteto`); H17 a H19 anotadas com o encaminhamento. A definição global de pronto ganhou o early, o conflito RN-22 e o item da E20 e da E21; o gate passou a depender de E20, E21 e E22. Revisão humana: sim na E04, na E19 e na E20. Na mesma revisão, a SPEC (§8.2.6 hora prevista de chegada; §8.3 governança sem os eventos de reserva e de check-in, e `HousekeepingTaskCompleted` sem consumidor de status; §8.4 `ASSIGNED → PENDING`) e a ARCHITECTURE (§6.1 sem o "atualizar plano do dia"; §7 com a regra da H16 e a exceção D11, o `required` com a quebra única e o `409 CONCURRENT_UPDATE`) foram alinhadas, e o `docs/roadmaps/README.md` passou a contar 22 etapas no M6. Nenhuma etapa concluída mudou de tag ou conteúdo; nenhuma foi renumerada nem removida.
