# E06 do M6 — Achados e perdidos e danos: plano de implementação

> **Para agentes:** execução por `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde, não o código.

**Goal:** implementar achados e perdidos (RF-GOV-08), com guarda, devolução e descarte/doação depois do prazo configurado, e danos (RF-GOV-09), com revisão pela recepção ou gerência e lançamento opcional na conta do hóspede pelo `billing`. Ambos podem ser abertos manualmente, offline (padrão da E19) ou por ocorrência de `ChecklistCompleted`.

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E06"; H2 e H5; handoff da E19 (padrão offline D7) e da E05; SPEC RF-GOV-08, RF-GOV-09, RF-CFG-04 (`lostAndFoundRetentionDays`), RN-13, §8.2.10 (`LostAndFoundItem`, `DamageReport`); ARCHITECTURE §3.1, §6.2, §7, §9, §11, §13. Brain: `ZenyHotelManager - Módulo housekeeping`, `ZenyHotelManager - Módulo checklists` (consumo de `ChecklistCompleted`: deduplicar por `executionId` + `itemKey` + tipo; `duplicateOf`), `ZenyHotelManager - Módulo billing (contas, roteamento e taxa de serviço)` (`post`, `sourceReference` idempotente), `ZenyHotelManager - Eventos e jobs` (ShedLock, jobs adiados no `@IntegrationTest`), `ZenyHotelManager - Sincronização offline`, e as capturas pendentes da E04, E19 e E05.

**Architecture:** dois agregados do `housekeeping`: `LostAndFoundItem` (`STORED → RETURNED | DISCARDED | DONATED`) e `DamageReport` (`REPORTED → REVIEWED → CHARGED | WAIVED`). Casos de uso de registro (online, offline e pelo ouvinte de `ChecklistCompleted`), devolução, descarte, revisão, cobrança e dispensa. A cobrança chama `BillingApi.post` na conta da reserva vinculada ao dano, com `sourceReference` idempotente (`housekeeping.damage:<id>`). Um job com ShedLock lista os itens vencidos pelo prazo de guarda do hotel (não descarta sozinho). A camareira registra (online e offline) e não vê dado de hóspede; revisão, cobrança e destino do achado são online. Nenhuma aresta nova: o `housekeeping` já declara `hotel`, `reservations`, `billing`, `checklists` e `notifications`.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1 (ouvinte de evento), ShedLock 7, Hibernate 7.4, Flyway 12, Testcontainers.

**Riscos:**
- **autenticação ou autorização:** permissões de registro (camareira) e de revisão/cobrança/destino (recepção, gerência) (Tasks 1, 2 e 3);
- **dado pessoal ou segredo:** o achado e o dano em quarto ocupado não podem expor o hóspede à camareira (RN-13); descrições são texto livre (Tasks 1, 2 e 3);
- **cálculo de valor monetário:** custo estimado e valor lançado na conta, uma vez só (Task 2);
- **concorrência:** cobrança repetida, evento reentregue, comando offline reenviado (Tasks 2 e 3);
- **parsing de entrada externa não confiável:** corpos, payloads offline e as ações do checklist (Tasks 1, 2 e 3);
- **migração ou escrita destrutiva de dados:** tabelas novas (Tasks 1 e 2).

## Global Constraints

- Grafo §3.1 com o `ModularityTest` em igualdade exata; sem aresta nova; o `housekeeping` não depende de `frontdesk` nem de `endofday`.
- Migrações novas por fatia, com timestamp posterior à última do projeto; `outOfOrder` desligado.
- Transição inválida `409 HOUSEKEEPING_…_TRANSITION_NOT_ALLOWED`; ações de escrita com `If-Match` (exceto o registro); `@Audited` sem dado pessoal (descrições só pelo tamanho na trilha); autorização antes de qualquer recusa que revele estado.
- RN-13: respostas à camareira, feeds e avisos sem nome, documento, contato nem id de hóspede ou de reserva. A ligação do dano à reserva só aparece para quem tem a permissão de revisão.
- Dinheiro como `Money` (BRL); o lançamento passa só pela `BillingApi.post`, com `sourceReference` idempotente; a data é a operacional corrente (RF-FEC-05, H5).
- Entrada estrita da E20, `409 CONCURRENT_UPDATE` da E21, `ExceptionClasses.chain`, regra do `required`.
- Padrão offline da E19 (D7) para o registro pela camareira; o `commandId` vira o id do registro.
- Contrato regenerado; `checkOpenApi` e `check-api-breaking.sh` sem `ERR`.
- Código em inglês; KDoc, ARCHITECTURE e commits em pt-BR; commits com `Soul:` no mesmo parágrafo do `Co-Authored-By`, intermediários.
- Um Gradle por vez; `timeout` em tudo; sem laço de espera; nunca `--stop`.
- Testes: banco compartilhado, só os próprios dados; jobs adiados no `@IntegrationTest` e chamados direto nos testes; tipos novos de aviso, se houver, entram na lista exata do `NotificationsApiTest`.
- Verificação da fatia: testes focados mais `timeout 900 ./gradlew checkOpenApi lint`.

## Review Focus

1. **Cobrança repetida** (dois cliques, reenvio, cobrança concorrente): um único lançamento na conta (`sourceReference` idempotente e a trava do dano); a segunda chamada devolve o estado `CHARGED` sem lançar de novo. Teste na Task 2.
2. **Dano em UH sem reserva vinculada:** só registro e dispensa; a cobrança dá `409 HOUSEKEEPING_DAMAGE_NO_RESERVATION`. Teste na Task 2.
3. **`ChecklistCompleted` reentregue ou com `duplicateOf`:** um registro só por `executionId` + `itemKey` + tipo. Teste na Task 3.
4. **Achado registrado pela camareira numa UH ocupada:** a resposta e o feed não trazem hóspede nem reserva; a devolução (a quem) só é feita por quem tem a permissão de gestão. Teste nas Tasks 1 e 3.
5. **Achado já devolvido ou descartado:** nova devolução/descarte dá `409` de transição, sem mudar o registro. Teste na Task 1.

## Decisões do plano

- **D1 — permissões.** `housekeeping.occurrences.report` (`OWN` e `ALL`: a camareira com `OWN` registra e lê os próprios registros; Governanta, Gerente e Recepção com `ALL`), `housekeeping.lost-found.manage` (`ALL`: guarda, devolução, descarte e doação; Governanta, Gerente, Recepção) e `housekeeping.damages.manage` (`ALL`: revisão, cobrança e dispensa; Gerente e Recepção). Nenhuma sensível, salvo decisão em contrário na revisão do plano.
- **D2 — `LostAndFoundItem`.** Campos: id, UH ou área (um dos dois), descrição (texto livre, até 500), local de guarda (texto curto), quem encontrou, quando, status, destino (`RETURNED`: a quem — nome livre de quem recebeu, quando, por quem; `DISCARDED`/`DONATED`: motivo, quando, por quem), origem (`MANUAL`, `OFFLINE`, `CHECKLIST` com `executionId` + `itemKey`). Sem id de hóspede: o achado não é ligado à reserva (a recepção identifica o dono fora do sistema; o "a quem" é texto livre e só aparece a quem tem `lost-found.manage`).
- **D3 — prazo de guarda.** Job `@Scheduled` com ShedLock que, uma vez por dia, lista os itens `STORED` cujo `found_at + lostAndFoundRetentionDays` (`HotelApi.policy()`) já passou, marca o item como vencido (`expired_at`, uma vez) e avisa uma vez por item quem tem `housekeeping.lost-found.manage`, com o tipo de aviso do próprio módulo `housekeeping.lost-found-expired` (precedente do `housekeeping.task-conflict` da E19; entra na lista exata do `NotificationsApiTest`), só com o id, a UH/área e a data em que foi achado, sem texto livre; não descarta. A consulta "vencidos" também é exposta (`GET …/lost-and-found?expired=true`).
- **D4 — `DamageReport`.** Campos: id, UH ou área, descrição (até 500), custo estimado (`Money`, ≥ 0), reserva vinculada (anulável; resolvida no registro como a reserva hospedada na UH na data operacional, pela `ReservationsApi.inHouse` + `assignedRooms`, e visível só com `damages.manage`), quem, quando, status, revisão (valor final, código de transação, quem, quando), dispensa (motivo, quem, quando), id do lançamento. Revisar fixa o valor e o código de transação (`TransactionCodeId` ativo, informado pela recepção). Cobrar (`chargeTo(folio)` do roadmap) exige `REVIEWED` (senão `409 HOUSEKEEPING_DAMAGE_NOT_REVIEWED`) e reserva vinculada (senão `409 HOUSEKEEPING_DAMAGE_NO_RESERVATION`); o corpo traz o `folioId`, que precisa ser uma conta aberta cujo dono é a reserva vinculada (`BillingApi.folio`/`foliosOf(FolioOwner RESERVATION)`; senão `409 HOUSEKEEPING_DAMAGE_FOLIO_INVALID`), lança com `PostingSource.MANUAL` e `sourceReference = "housekeeping.damage:<id>"` e vai a `CHARGED`. Dispensar exige motivo.
- **D5 — ouvinte de `ChecklistCompleted`.** Para cada ação `OPEN_OCCURRENCE` de tipo `LOST_AND_FOUND` ou `DAMAGE` numa execução de contexto `HOUSEKEEPING` ou `INSPECTION` cujo sujeito é uma tarefa ou inspeção do `housekeeping`, cria o registro com a UH/área da tarefa, a descrição do item (rótulo do item + `category`) e origem `CHECKLIST`; índice único em (`executionId`, `itemKey`, tipo) garante um só registro mesmo com reentrega ou `duplicateOf`. O ouvinte é assíncrono (Modulith), sem autor humano (o ator é o executor).
- **D6 — offline.** Comandos `housekeeping.report-lost-found` e `housekeeping.report-damage` (padrão D7; `commandId` como id; `occurredAt` corrigido) pela camareira. Revisão, cobrança e destino ficam online. Fonte offline: não há (o app só envia).

---

### Task 1: Achados e perdidos

**Objetivo:** permissões (D1), agregado e tabela de achados (D2), registro online, listagem (com escopo `OWN` para a camareira), devolução, descarte e doação, e o job do prazo de guarda (D3).

**Critérios de aceite:** `HousekeepingLostAndFoundApiTest` (registro, listagem com OWN/ALL, devolução registra a quem/quando/por quem, descarte e doação com motivo e usuário, transições inválidas `409` — Review Focus 5, resposta à camareira sem dado de hóspede — Review Focus 4); `HousekeepingLostAndFoundExpiryJobTest` (o job, chamado direto, lista e avisa os vencidos, não descarta); `HousekeepingOccurrencePermissionsTest`; schema e `DatabaseRolesTest`; contrato. Verificações com `timeout 900 ./gradlew test --tests …` e `checkOpenApi lint`.

**Áreas tocadas:** `HousekeepingPermissions.kt`, `HousekeepingTypes.kt`, `domain/LostAndFoundItem.kt` (novo), `application/HousekeepingLostAndFound.kt` (novo), `application/LostAndFoundExpiryJob.kt` (novo), `application/HousekeepingAudit.kt`, `infrastructure/` (persistência), `web/HousekeepingLostAndFoundController.kt` (novo), a migração, `api/openapi.yaml`.

**Tamanho:** ~60 min; 9 arquivos de produção.

**Depende de:** nenhuma.

### Task 2: Danos e cobrança

**Objetivo:** agregado e tabela de danos (D4), registro com a reserva vinculada, revisão, cobrança idempotente pela `BillingApi.post` e dispensa.

**Critérios de aceite:** `HousekeepingDamageApiTest` (registro numa UH ocupada vincula a reserva, visível só com `damages.manage`; cobrança sem revisão `409 HOUSEKEEPING_DAMAGE_NOT_REVIEWED`; revisado e cobrado gera um único lançamento na conta da reserva, e a repetição e a cobrança concorrente não duplicam — Review Focus 1; dispensa registra motivo e não lança; UH sem reserva só registra e dispensa — Review Focus 2; resposta à camareira sem dado de hóspede); `HousekeepingDamageLockTest` (duas cobranças simultâneas por `pg_blocking_pids`, um lançamento); contrato.

**Áreas tocadas:** `domain/DamageReport.kt` (novo), `application/HousekeepingDamages.kt` (novo), `infrastructure/` (persistência), `web/HousekeepingDamagesController.kt` (novo), `HousekeepingTypes.kt`, a migração, `api/openapi.yaml`.

**Tamanho:** ~60 min; 7 arquivos de produção.

**Depende de:** Task 1 (permissões).

### Task 3: Ocorrências do checklist e registro offline

**Objetivo:** ouvinte de `ChecklistCompleted` (D5) e comandos offline de registro (D6).

**Critérios de aceite:** `HousekeepingOccurrenceListenerTest` (`ChecklistCompleted` com ocorrência de achado e de dano cria o registro; reentrega e `duplicateOf` não duplicam — Review Focus 3; execução de outro contexto ou sujeito ignorada); `HousekeepingOccurrenceOfflineTest` (`report-lost-found` e `report-damage` offline aparecem na listagem; idempotência; `CLOCK_SKEW_EXCEEDED`; sem permissão `REJECTED`; resposta sem dado de hóspede — Review Focus 4); ARCHITECTURE §6.2/§9 com os comandos.

**Áreas tocadas:** `application/HousekeepingOccurrenceListener.kt` (novo), `HousekeepingOffline.kt`, `application/HousekeepingOccurrenceOfflineHandlers.kt` (novo), a migração (índices únicos de origem), `docs/ARCHITECTURE.md`.

**Tamanho:** ~55 min; 4 arquivos de produção.

**Depende de:** Tasks 1 e 2.
