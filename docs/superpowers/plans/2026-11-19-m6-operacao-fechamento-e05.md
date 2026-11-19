# E05 do M6 — Inspeção e liberação da UH: plano de implementação

> **Para agentes:** execução por `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde, não o código.

**Goal:** implementar a inspeção (RF-GOV-06): a supervisora executa o checklist de inspeção e aprova (UH Inspecionada, tarefa `DONE`) ou reprova (UH Suja, tarefa `REJECTED` de volta à camareira com os itens apontados); inspeção voluntária de UH Limpa; aviso à recepção quando a UH fica pronta (RF-GOV-07, RF-NOT-01), por SSE e por notificação de UH liberada; inspeção offline pelo padrão da E19.

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E05"; handoffs da E04 (D3: tarefa `AWAITING_INSPECTION` cuja UH já perdeu a pendência) e da E19 (padrão offline D7); SPEC RF-GOV-06, RF-GOV-07, RN-05, RF-NOT-01, §8.2.10 (`Inspection`), §8.4; ARCHITECTURE §3.1, §6.2, §7, §9, §10. Brain: `ZenyHotelManager - Módulo housekeeping`, `ZenyHotelManager - Módulo rooms`, `ZenyHotelManager - Módulo checklists`, `ZenyHotelManager - Módulo notifications`, `ZenyHotelManager - Tempo real (SSE)`, `ZenyHotelManager - Sincronização offline`, e as capturas pendentes da E04, E19, E20 e E21.

**Architecture:** a `Inspection` é um agregado do `housekeeping` ligado a uma tarefa (ou só à UH, na voluntária) e a uma execução de checklist de contexto `INSPECTION`. Um caso de uso único (`HousekeepingInspections`) atende o online e os comandos offline. A UH muda só pela `RoomsApi`, por operações condicionais com a linha travada (precedente da E04: `markInspectedIfPending`/`rejectInspectionIfPending`), porque a pendência pode ter sumido (check-out, fechamento ou aprovação manual pelo `rooms`). A ordem de trava é tarefa → execução do checklist → UH. A prontidão (RN-05) é do `rooms` (`isReadyForCheckIn`); o SSE da UH pronta é a mensagem `rooms.room-status` que o `rooms` já publica a cada mudança; a notificação de UH liberada é do `housekeeping`.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1, Hibernate 7.4, Flyway 12, Testcontainers.

**Riscos:**
- **autenticação ou autorização:** permissão própria de inspeção; quem limpou não inspeciona sem ela (Tasks 1 e 3);
- **concorrência:** inspeção × check-out/fechamento/aprovação manual na mesma UH; ordem de trava (Tasks 1 e 2);
- **dado pessoal ou segredo:** avisos e respostas sem dado de hóspede (RN-13) (Tasks 2 e 3);
- **parsing de entrada externa não confiável:** corpos e payloads da inspeção (Tasks 1 e 3);
- **migração ou escrita destrutiva de dados:** tabela da inspeção (Task 1).

## Global Constraints

- Grafo §3.1 com o `ModularityTest` em igualdade exata; o `housekeeping` não depende de `frontdesk`.
- Migrações com timestamp posterior à última do projeto (hoje `V2026_11_19_1202`); `outOfOrder` desligado.
- Transição inválida `409 HOUSEKEEPING_TASK_TRANSITION_NOT_ALLOWED`; ações com `If-Match`; `@Audited` sem dado pessoal; autorização antes de qualquer recusa que revele estado.
- O status da UH só muda pela `RoomsApi`; o checklist só pela `ChecklistsApi`.
- RN-13: nenhuma resposta, aviso, trilha ou mensagem leva `notes`, nome, documento, contato nem id de hóspede ou de reserva.
- Entrada estrita da E20, `409 CONCURRENT_UPDATE` da E21, `ExceptionClasses.chain` nos logs, regra do `required` nos DTOs.
- Padrão offline da E19 (D7): comandos `housekeeping.<verbo>-…`, `REJECTED` por `DomainException`, alertas por `warnings`, `occurredAt` corrigido, política de foto `TOLERATE` com alertas.
- Contrato regenerado; `checkOpenApi` e `check-api-breaking.sh` sem `ERR`.
- Código em inglês; KDoc, ARCHITECTURE e commits em pt-BR; commits com `Soul:`, intermediários.
- Um Gradle por vez; `timeout` em tudo; sem laço de espera; nunca `--stop`.
- Testes: banco compartilhado, só os próprios dados; tarefas na data operacional corrente isoladas por UHs, tipos e camareiras próprios; `neutralizeGeneralHousekeepingTemplates()` no `@BeforeEach` de todo teste que inicia ou conclui tarefa (e um equivalente para modelos gerais de `INSPECTION`, se o teste depender da resolução); `HousekeepingExecutionTestSupport.assign` grava a posse; trava por `pg_blocking_pids`; tipo novo de aviso entra na lista exata do `NotificationsApiTest` (não há tipo novo aqui: `room-released` e `inspection-rejected` já existem).
- Verificação da fatia: testes focados mais `timeout 900 ./gradlew checkOpenApi lint`.

## Review Focus

1. **Aprovar tarefa `AWAITING_INSPECTION` cuja UH perdeu a pendência** (check-out deixou a UH Suja, ou o `rooms` já a aprovou): a tarefa vai a `DONE`, a UH não muda, a resposta diz que a UH não foi inspecionada; nunca 500 nem 422 do `rooms`. Teste na Task 1.
2. **Reprovar com a UH já Suja:** a tarefa vai a `REJECTED` com os itens, a UH segue Suja (a operação condicional não mexe), a camareira é avisada. Teste na Task 2.
3. **Quem limpou a tarefa tenta inspecioná-la sem a permissão de inspeção:** `403`. Teste na Task 1.
4. **UH Inspecionada mas ocupada ou em OOO:** não é "pronta"; nenhum aviso de UH liberada. Teste na Task 2.
5. **Comando offline de inspeção numa tarefa que já foi inspecionada online:** `REJECTED` com o código de transição, sem segunda inspeção. Teste na Task 3.

## Decisões do plano

- **D1 — permissão.** `housekeeping.inspections.perform`, só `ALL`, não sensível, nos modelos Governanta e Gerente. A camareira não tem. Quem limpou só inspeciona se tiver a permissão (a SPEC não proíbe a governanta de inspecionar a tarefa que ela mesma concluiu).
- **D2 — agregado `Inspection`.** Tabela `housekeeping.inspection`: id, tarefa (anulável, na voluntária), UH, execução do checklist (anulável sem modelo), resultado (`APPROVED`/`REJECTED`), itens reprovados (chaves do checklist), quem, quando. Uma tarefa pode ter várias inspeções (reprovar, refazer, aprovar). Não é só de inserção (o resultado é gravado na conclusão da inspeção), mas não se apaga.
- **D3 — fluxo online.** `POST /api/v1/housekeeping-tasks/{id}/inspection/start` (abre a execução do checklist de contexto `INSPECTION` pelo `resolveTemplate`, se houver) e `POST …/inspection/complete` (`result`, `answers`, `rejectedItems`), com `If-Match` da tarefa. Aprovar: tarefa `AWAITING_INSPECTION → DONE`, `RoomsApi.markInspectedIfPending`. Reprovar: tarefa `AWAITING_INSPECTION → REJECTED`, `RoomsApi.rejectInspectionIfPending`; refazer é o `start` da E04 (`REJECTED → IN_PROGRESS`). A inspeção voluntária de UH Limpa: `POST /api/v1/rooms/{roomId}/inspections` no `housekeeping` (ou rota equivalente sob `housekeeping`), sem tarefa, que só aprova (`markInspected`; UH não Limpa dá `409`).
- **D4 — UH pronta (RF-GOV-07).** Depois de uma aprovação que levou a UH a Inspecionada, se `RoomsApi.isReadyForCheckIn` (RN-05: vaga, fora de OOO), o `housekeeping` notifica `notifications.room-released` aos usuários com a permissão de check-in da recepção, referida pelo código público `frontdesk.stays.checkin` (sem dependência de módulo: o código de permissão é contrato do catálogo). O SSE é a mensagem `rooms.room-status` que o `rooms` já publica; o critério é provado no tópico `rooms`. Alternativa descartada: um evento novo de "UH pronta" no `rooms`, desnecessário enquanto o painel rebusca a prontidão.
- **D5 — reprovação.** Os itens reprovados vão na tarefa (`rejected_items`, chaves) e no aviso `notifications.inspection-rejected` à camareira atribuída (usuário pela `StaffApi`), com o número da UH, sem dado de hóspede.
- **D6 — offline.** Comandos `housekeeping.start-inspection`, `housekeeping.complete-inspection` e `housekeeping.inspect-room` (voluntária), pelo mesmo caso de uso, com o padrão da E19 (só `ALL`: sem caminho de conflito de posse).

---

### Task 1: Permissão, agregado e aprovação online

**Objetivo:** permissão de inspeção (D1), agregado e tabela (D2), início e conclusão online com aprovação (D3), operação condicional `markInspectedIfPending` no `rooms`, o caso da UH que perdeu a pendência (Review Focus 1).

**Critérios de aceite:** `HousekeepingInspectionApiTest` (aprovação → UH Inspecionada e tarefa `DONE`; UH sem pendência → tarefa `DONE` e UH igual; checklist de inspeção aberto e concluído; trilha); `HousekeepingInspectionPermissionsTest` (sem a permissão, `403` antes de `404`/`409`/`412`, inclusive quem limpou); `RoomsApiInspectionOperationsTest`; schema e `DatabaseRolesTest`; contrato. Verificações com `timeout 900 ./gradlew test --tests …` e `checkOpenApi lint`.

**Áreas tocadas:** `HousekeepingPermissions.kt`, `domain/Inspection.kt` (novo), `domain/HousekeepingTask.kt` (aprovar), `application/HousekeepingInspections.kt` (novo), `infrastructure/` (persistência), `web/HousekeepingInspectionsController.kt` (novo), `rooms/RoomsApi.kt` e `rooms/application/RoomsApiService.kt`, a migração, `api/openapi.yaml`.

**Tamanho:** ~60 min; 9 arquivos de produção.

**Depende de:** nenhuma.

### Task 2: Reprovação, inspeção voluntária e UH liberada

**Objetivo:** reprovar (D3, D5) com `rejectInspectionIfPending`; refazer pela E04; inspeção voluntária de UH Limpa; aviso de UH liberada (D4).

**Critérios de aceite:** reprovação → UH Suja, tarefa `REJECTED` com os itens, camareira notificada, refazer → `IN_PROGRESS`; reprovar com a UH já Suja (Review Focus 2); voluntária de UH Limpa → Inspecionada; UH vaga sem OOO aprovada → `notifications.room-released` para quem tem `frontdesk.stays.checkin` e `rooms.room-status` no tópico `rooms`; UH ocupada ou em OOO → sem aviso (Review Focus 4); avisos sem dado de hóspede.

**Áreas tocadas:** `application/HousekeepingInspections.kt`, `domain/HousekeepingTask.kt` (reprovar), `web/HousekeepingInspectionsController.kt`, `rooms/RoomsApi.kt` e `RoomsApiService.kt` (`rejectInspectionIfPending`), a migração (`rejected_items`), `api/openapi.yaml`.

**Tamanho:** ~55 min; 6 arquivos de produção.

**Depende de:** Task 1.

### Task 3: Inspeção offline

**Objetivo:** comandos `housekeeping.start-inspection`, `housekeeping.complete-inspection` e `housekeeping.inspect-room` (D6) pelo mesmo caso de uso, com o padrão da E19.

**Critérios de aceite:** `HousekeepingInspectionOfflineTest` (lote offline produz o mesmo estado que o online; idempotência; `CLOCK_SKEW_EXCEEDED`; sem permissão `REJECTED`; inspeção offline de tarefa já inspecionada online `REJECTED` com o código de transição — Review Focus 5); ARCHITECTURE §6.2/§9 com os nomes.

**Áreas tocadas:** `HousekeepingOffline.kt`, `application/HousekeepingInspectionOfflineHandlers.kt` (novo), `HousekeepingAudit.kt`, `docs/ARCHITECTURE.md`.

**Tamanho:** ~45 min; 3 arquivos de produção.

**Depende de:** Task 2.
