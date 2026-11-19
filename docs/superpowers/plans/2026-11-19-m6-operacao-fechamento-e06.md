# E06 do M6 — Achados e perdidos e danos: plano de implementação

> **Para agentes:** execução por `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde, não o código.

**Goal:** implementar achados e perdidos (RF-GOV-08), com guarda, devolução e descarte/doação depois do prazo configurado, e danos (RF-GOV-09), com revisão pela recepção ou gerência e lançamento opcional na conta do hóspede pelo `billing`. Ambos podem ser abertos manualmente, offline (padrão da E19) ou por ocorrência de `ChecklistCompleted`.

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E06"; H2 e H5; handoff da E19 (padrão offline D7) e da E05; SPEC RF-GOV-08, RF-GOV-09, RF-CFG-04 (`lostAndFoundRetentionDays`), RN-13, §8.2.10 (`LostAndFoundItem`, `DamageReport`); ARCHITECTURE §3.1, §6.2, §7, §9, §11, §13. Brain: `ZenyHotelManager - Módulo housekeeping`, `ZenyHotelManager - Módulo checklists` (consumo de `ChecklistCompleted`: deduplicar por `executionId` + `itemKey` + tipo; `duplicateOf`), `ZenyHotelManager - Módulo billing (contas, roteamento e taxa de serviço)` (`post`, `sourceReference` idempotente), `ZenyHotelManager - Eventos e jobs` (ShedLock, jobs adiados no `@IntegrationTest`), `ZenyHotelManager - Sincronização offline`, e as capturas pendentes da E04, E19 e E05.

**Architecture:** dois agregados do `housekeeping`: `LostAndFoundItem` (`STORED → RETURNED | DISCARDED | DONATED`) e `DamageReport` (`REPORTED → REVIEWED → CHARGED | WAIVED`). Casos de uso de registro (online, offline e pelo ouvinte de `ChecklistCompleted`), devolução, descarte, revisão, cobrança e dispensa. A cobrança chama `BillingApi.post` na conta da reserva vinculada ao dano, com `sourceReference` idempotente (`housekeeping.damage:<id>`). Um job com ShedLock avisa os itens vencidos pelo prazo de guarda do hotel (não descarta sozinho). A camareira registra (online e offline) e não vê dado de hóspede; revisão, cobrança e destino do achado são online. Aresta nova só `housekeeping → billing`, já permitida pela ARCHITECTURE §3.1 (linha do `housekeeping`) e sem ciclo (o `billing` não depende do `housekeeping`); as demais (`hotel`, `rooms`, `reservations`, `checklists`, `staff`, `notifications`) já existem.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1 (ouvinte de evento), ShedLock 7, Hibernate 7.4, Flyway 12, Testcontainers.

**Riscos:**
- **autenticação ou autorização:** permissões de registro (camareira) e de revisão/cobrança/destino (recepção, gerência) (Tasks 1, 2 e 3);
- **dado pessoal ou segredo:** o achado e o dano em quarto ocupado não podem expor o hóspede à camareira (RN-13); descrições são texto livre (Tasks 1, 2 e 3);
- **cálculo de valor monetário:** custo estimado e valor lançado na conta, uma vez só (Task 2);
- **concorrência:** cobrança repetida, evento reentregue, comando offline reenviado (Tasks 2 e 3);
- **parsing de entrada externa não confiável:** corpos, payloads offline e as ações do checklist (Tasks 1, 2 e 3);
- **migração ou escrita destrutiva de dados:** tabelas novas (Tasks 1 e 2).

## Global Constraints

- Grafo §3.1 com o `ModularityTest` em igualdade exata; só a aresta `housekeeping → billing` (Task 2), já prevista na §3.1; o `housekeeping` não depende de `frontdesk` nem de `endofday`.
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

1. **Cobrança repetida** (dois cliques, reenvio, cobrança concorrente): um único lançamento na conta (trava do dano mais `sourceReference` idempotente, com o índice `posting_source_reference_uk` do `billing`); a segunda chamada sobre um dano `CHARGED` dá `409 HOUSEKEEPING_DAMAGE_TRANSITION_NOT_ALLOWED` (ou `412`/`409 CONCURRENT_UPDATE` pela versão) e não lança de novo. Teste na Task 2.
2. **Dano em UH sem reserva vinculada:** só registro e dispensa; a cobrança dá `409 HOUSEKEEPING_DAMAGE_NO_RESERVATION`. Teste na Task 2.
3. **`ChecklistCompleted` reentregue:** um registro só por `executionId` + `itemKey` + tipo. A execução registrada à parte de uma rotina (`duplicateOf`, outro `executionId`) cria o próprio registro (D5). Teste na Task 3.
4. **Achado ou dano registrado pela camareira numa UH ocupada:** a resposta (online e o resultado do comando offline) e a listagem com `OWN` não trazem hóspede nem reserva; a devolução (a quem) só é feita por quem tem a permissão de gestão. Teste nas Tasks 1, 2 e 3.
5. **Achado já devolvido ou descartado:** nova devolução/descarte dá `409` de transição, sem mudar o registro; descarte ou doação antes do prazo dá `409 HOUSEKEEPING_LOST_FOUND_NOT_EXPIRED`. Teste na Task 1.
6. **Dano registrado depois de um check-in novo na UH** (offline sincronizado tarde, ou checklist da limpeza de saída): não vincula a reserva nova. Teste na Task 2.

## Decisões do plano

- **D1 — permissões.** `housekeeping.occurrences.report` (`OWN` e `ALL`: a camareira com `OWN` registra e lê os próprios registros; Governanta, Gerente e Recepção com `ALL`), `housekeeping.lostfound.manage` (`ALL`: guarda, devolução, descarte e doação; Governanta, Gerente, Recepção; sem hífen, pela regra de `shared/Permissions.kt`) e `housekeeping.damages.manage` (`ALL`: revisão, vínculo, cobrança e dispensa; Gerente e Recepção). Nenhuma sensível. O KDoc da `damages.manage` registra que a cobrança lança na conta sem passar pela `billing.postings.create` (os dois perfis que a têm também têm aquela). Com `OWN`, a posse do registro é de quem registrou (`reported_by`).
- **D2 — `LostAndFoundItem`.** Campos: id, UH ou área (um dos dois), descrição (texto livre, até 500), local de guarda (texto curto), quem encontrou, quando, status, destino (`RETURNED`: a quem — nome livre de quem recebeu, quando, por quem; `DISCARDED`/`DONATED`: motivo, quando, por quem), origem (`MANUAL`, `OFFLINE`, `CHECKLIST` com `executionId` + `itemKey`). Sem id de hóspede: o achado não é ligado à reserva (a recepção identifica o dono fora do sistema; o "a quem" é texto livre, até 200, só aparece a quem tem `lostfound.manage` e fica fora da trilha; não há expurgo dele antes da anonimização do M10 E13 — nota ao `arquiteto`). Transições: `STORED → RETURNED` a qualquer tempo; `STORED → DISCARDED | DONATED` só com o item vencido (RF-GOV-08, "após o prazo configurado"), senão `409 HOUSEKEEPING_LOST_FOUND_NOT_EXPIRED`; motivo obrigatório. **Vencido** = data local de `found_at` no fuso do hotel (`HotelApi.timezone()`) mais `lostAndFoundRetentionDays` ≤ data operacional corrente (`HotelApi.currentBusinessDate()`), calculado na consulta (sem coluna).
- **D3 — prazo de guarda.** Job `@Scheduled` com ShedLock (em `infrastructure/`, como `InventoryHorizonJob`; adiado no `@IntegrationTest` por uma constante como `FNRH_SEND_DEFERRED`; linha nova na tabela da ARCHITECTURE §13) que, uma vez por dia, lista os itens `STORED` vencidos (D2) ainda não avisados, grava `expiry_notified_at` e avisa uma vez por item quem tem `housekeeping.lost-found.manage`, com o tipo de aviso do próprio módulo `housekeeping.lost-found-expired` (precedente do `housekeeping.task-conflict` da E19; entra na lista exata do `NotificationsApiTest`), só com o id, a UH/área e a data em que foi achado, sem texto livre; não descarta. A consulta "vencidos" também é exposta (`GET /api/v1/lost-and-found?expired=true`), e a resposta de gestão traz `expired`.
- **D4 — `DamageReport`.** Campos: id, UH ou área, descrição (até 500), custo estimado (`Money`, ≥ 0, 2 casas, até 99 999,99), instante do fato (`occurred_at`: o `occurredAt` corrigido no offline, o `completedAt` do checklist, o instante do registro online), reserva vinculada (anulável, visível só com `damages.manage`), quem, quando, status, revisão (valor final, código de transação, quem, quando), dispensa (motivo, quem, quando), id do lançamento. **Vínculo sugerido no registro:** a reserva alocada hoje à UH (`ReservationsApi.inHouse(data operacional)` + `assignedRooms`), só se o check-in dela (`ReservationSummary.checkIn`) é anterior ou igual ao instante do fato; nunca quando a origem é uma tarefa `CHECKOUT` ou `ROOM_MOVE` (a tarefa de saída não guarda a reserva, e a alocação já foi solta), nem em área. **Revisar** (`REPORTED|REVIEWED → REVIEWED`) fixa o valor final (> 0; dano de valor zero vai à dispensa), o código de transação (ativo e de `kind` `CHARGE` ou `PENALTY`, senão `409 HOUSEKEEPING_DAMAGE_TRANSACTION_CODE_INVALID`) e confirma, troca ou retira a reserva vinculada (`reservationId` no corpo; trocar exige reserva `IN_HOUSE`). Cobrar (`chargeTo(folio)` do roadmap, `REVIEWED → CHARGED`) exige `REVIEWED` (senão `409 HOUSEKEEPING_DAMAGE_NOT_REVIEWED`) e reserva vinculada (senão `409 HOUSEKEEPING_DAMAGE_NO_RESERVATION`); o corpo traz o `folioId`, que precisa ser uma conta aberta cujo dono é a reserva vinculada (`BillingApi.folio`/`foliosOf(FolioOwner RESERVATION)`; senão `409 HOUSEKEEPING_DAMAGE_FOLIO_INVALID`), lança com `PostingSource.MANUAL`, `sourceReference = "housekeeping.damage:<id>"`, **sem taxa de serviço** (`serviceChargeRate` zero, como os pacotes do check-out, H5) e descrição fixa sem o texto livre do dano ("Dano na UH <número>", ≤ 200), e vai a `CHARGED`. **Dispensar** (`REPORTED|REVIEWED → WAIVED`) exige motivo. **Dano achado depois do check-out não é cobrável** (o check-out encerra as contas, e o roadmap aceita "só registro e dispensa"): a recepção lança à mão depois de reabrir a conta, ação sensível já existente.
- **D5 — ouvinte de `ChecklistCompleted`.** Para cada ação `OPEN_OCCURRENCE` de tipo `LOST_AND_FOUND` ou `DAMAGE` numa execução cujo sujeito é uma tarefa (`housekeeping.task`) ou inspeção (`housekeeping.inspection`) do `housekeeping` (UH/área e tipo da tarefa resolvidos por ela), ou uma UH (`rooms.room`) ou área (`rooms.area`) de rotina, cria o registro com origem `CHECKLIST`, o instante do fato = `completedAt`, e a descrição = rótulo do item (de `ChecklistsApi.version(versionId).content`) + `category` + `note` da ação, truncada a 500. Índice único em (`executionId`, `itemKey`, tipo) garante um só registro na reentrega. A execução à parte de rotina (`duplicateOf`) tem outro `executionId` e **cria o próprio registro** (achado e dano não são consumo em dobro; a revisão filtra). Outros sujeitos são ignorados. O ouvinte é assíncrono (Modulith), sem autor humano (o executor fica como quem registrou). Os tipos de sujeito `housekeeping.task` e `housekeeping.inspection`, hoje `private const` em `HousekeepingTaskExecution.kt` e `HousekeepingInspections.kt`, viram constantes `internal` compartilhadas.
- **D6 — offline.** Comandos `housekeeping.report-lost-found` e `housekeeping.report-damage` (padrão D7; `commandId` como id; `occurredAt` corrigido) pela camareira. Revisão, cobrança e destino ficam online. Fonte offline: não há (o app só envia). O custo estimado no payload (que passa por `double` no `shared`) é validado pela entrada estrita: 2 casas e o limite de D4.

---

### Task 1: Achados e perdidos

**Objetivo:** permissões (D1), agregado e tabela de achados (D2), registro online, listagem (com escopo `OWN` para a camareira e o filtro `expired`), devolução, descarte e doação com a regra do prazo.

**Critérios de aceite:** `HousekeepingLostAndFoundApiTest` (registro; listagem com `OWN`/`ALL`; devolução registra a quem, quando e por quem; descarte e doação de item vencido registram motivo e usuário; descarte antes do prazo `409 HOUSEKEEPING_LOST_FOUND_NOT_EXPIRED` e nova devolução/descarte `409` de transição — Review Focus 5; resposta à camareira sem dado de hóspede nem o "a quem" — Review Focus 4; vencimento pela data operacional e o fuso do hotel); `HousekeepingOccurrencePermissionsTest` (`403` antes de `404`/`409`/`412`); `HousekeepingPermissionsTest` e `AuthorizationTest` com as listas exatas; schema e `DatabaseRolesTest`; contrato. Verificações com `timeout 900 ./gradlew test --tests …` e `timeout 900 ./gradlew checkOpenApi lint`.

**Áreas tocadas:** `HousekeepingPermissions.kt`, `HousekeepingTypes.kt`, `domain/LostAndFoundItem.kt` (novo), `application/HousekeepingLostAndFound.kt` (novo), `application/HousekeepingAudit.kt`, `infrastructure/` (persistência), `web/HousekeepingLostAndFoundController.kt` (novo), a migração, `api/openapi.yaml`.

**Tamanho:** ~55 min; 8 arquivos de produção.

**Depende de:** nenhuma.

### Task 2: Danos, vínculo e cobrança

**Objetivo:** aresta `housekeeping → billing`; agregado e tabela de danos (D4) com o vínculo sugerido pelo instante do fato; revisão (valor, código, vínculo), cobrança idempotente pela `BillingApi.post` sem taxa de serviço e dispensa.

**Critérios de aceite:** `HousekeepingDamageApiTest`:
- registro numa UH ocupada vincula a reserva, visível só com `damages.manage`; a resposta à camareira não traz reserva nem hóspede (Review Focus 4);
- registro com instante do fato anterior ao check-in atual da UH, ou vindo de tarefa `CHECKOUT`, não vincula (Review Focus 6); a revisão troca e retira o vínculo;
- cobrança sem revisão `409 HOUSEKEEPING_DAMAGE_NOT_REVIEWED`; revisão com código `CREDIT` `409 HOUSEKEEPING_DAMAGE_TRANSACTION_CODE_INVALID`; valor final zero `400`;
- revisado e cobrado gera um único lançamento na conta da reserva, sem taxa de serviço, com a descrição fixa e o `sourceReference`; a segunda cobrança dá `409` de transição e não lança (Review Focus 1);
- dispensa de `REPORTED` e de `REVIEWED` registra motivo e não lança; UH sem reserva só registra e dispensa (`409 HOUSEKEEPING_DAMAGE_NO_RESERVATION`, Review Focus 2); conta de outra reserva ou fechada `409 HOUSEKEEPING_DAMAGE_FOLIO_INVALID`.

`HousekeepingDamageLockTest` (duas cobranças simultâneas, contenção por `pg_blocking_pids`: um lançamento); `ModularityTest` com o conjunto do `housekeeping` acrescido de `billing` em igualdade exata; contrato.

**Áreas tocadas:** `HousekeepingModule.kt` (aresta e KDoc), `domain/DamageReport.kt` (novo), `application/HousekeepingDamages.kt` (novo), `infrastructure/` (persistência), `web/HousekeepingDamagesController.kt` (novo), `HousekeepingTypes.kt`, a migração, `api/openapi.yaml`; teste `ModularityTest.kt`.

**Tamanho:** ~60 min; 8 arquivos de produção.

**Depende de:** Task 1 (permissões).

### Task 3: Prazo de guarda, ocorrências do checklist e registro offline

**Objetivo:** job do prazo de guarda e aviso (D3), ouvinte de `ChecklistCompleted` (D5) e comandos offline de registro (D6).

**Critérios de aceite:**
- `HousekeepingLostAndFoundExpiryJobTest`: o job, chamado direto, avisa uma vez cada item vencido a quem tem `lostfound.manage`, sem texto livre; não descarta; segunda execução não repete o aviso;
- `NotificationsApiTest` com `housekeeping.lost-found-expired` na lista exata;
- `HousekeepingOccurrenceListenerTest`: `ChecklistCompleted` com ocorrência de achado e de dano (tarefa, inspeção e rotina de área) cria o registro com a descrição do rótulo, `category` e `note`; reentrega não duplica; `duplicateOf` cria o próprio registro (Review Focus 3); sujeito de outro módulo ignorado;
- `HousekeepingOccurrenceOfflineTest`: `report-lost-found` e `report-damage` offline aparecem na listagem; idempotência; `CLOCK_SKEW_EXCEEDED`; sem permissão `REJECTED`; custo com 3 casas `REJECTED`/`COMMAND_PAYLOAD_INVALID`; resultado sem dado de hóspede (Review Focus 4);
- ARCHITECTURE §6.2, §9 e §13 com os nomes, conferida por `grep -n 'report-lost-found\|report-damage\|lost-found-expired' docs/ARCHITECTURE.md`.

**Áreas tocadas:** `infrastructure/LostAndFoundExpiryJob.kt` (novo), `HousekeepingNotificationTypes.kt`, `application/HousekeepingOccurrenceListener.kt` (novo), `HousekeepingOffline.kt`, `application/HousekeepingOccurrenceOfflineHandlers.kt` (novo), `application/HousekeepingTaskExecution.kt` e `application/HousekeepingInspections.kt` (constantes de sujeito), a migração (índices únicos de origem, `expiry_notified_at`), `docs/ARCHITECTURE.md`; testes `IntegrationTest` (adiamento do job) e `NotificationsApiTest`.

**Tamanho:** ~60 min; 8 arquivos de produção.

**Depende de:** Tasks 1 e 2.
