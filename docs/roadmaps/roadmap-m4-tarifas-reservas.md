# Roadmap — M4 Tarifas, disponibilidade e reservas

> Arquivo: `docs/roadmaps/roadmap-m4-tarifas-reservas.md` · criado em 2026-10-13 · última revisão 2026-10-20
>
> Marco 4 de 11 · mapa dos marcos em [`docs/roadmaps/README.md`](README.md) · marco anterior: [M3 — Hóspedes, contas e caixa](roadmap-m3-hospedes-contas.md) · próximo marco: [M5 — Recepção, FNRH e impressão](roadmap-m5-recepcao-fnrh.md)
>
> **Pré-requisito:** etapa E11 ("Gate do marco M3") de `docs/roadmaps/roadmap-m3-hospedes-contas.md` marcada `#concluida` — atendido em 2026-10-20 (commits `1fcc0bb`, `e7251d3` e `013ed01`); CI do push verde no run `39458089966` (os três jobs: build, oasdiff e imagem).

## Como usar

Duas formas, ambas na raiz do repositório:

- **Manual:** cole o prompt de uma etapa numa sessão limpa do Claude Code. Uma etapa por sessão.
- **Automática:** cole o prompt da seção "Execução automática" numa sessão limpa; ela executa até 3 etapas em sequência, um subagente por etapa mais um verificador, e para para revisão. Para não parar em cada pedido de permissão, libere antes os comandos de build/teste do projeto nas permissões do Claude Code (os comandos oficiais estão no `CLAUDE.md`, e o Docker é necessário para os testes de integração).

Etapas marcadas como paralelizáveis só devem rodar ao mesmo tempo em worktrees git separados; na mesma pasta, rode-as uma de cada vez.

Em ambas, quem executa segue o "Protocolo de execução", atualiza a tag da etapa e preenche "Evidências e handoff". Não pule dependências. Revise e faça commit entre etapas ou lotes — nenhuma sessão faz commit por conta própria.

**Sincronizado com o código em 2026-10-20**, depois do gate do M3 (commit `013ed01`): caminhos, comandos, convenções, APIs públicas e pendências vêm do `CLAUDE.md`, do código e dos handoffs de `docs/roadmaps/roadmap-m3-hospedes-contas.md` (principalmente as "Notas para o marco M4" da E11). Nenhuma etapa nova, removida ou renumerada; todas seguem `#pendente`. As pendências do M3 entraram nas etapas que as usam (E05, E07, E08, E09, E10, E11 e E12). A H4 caiu no código (o `rooms` não publica evento de cadastro de UH) e a E05 passou a criá-lo; H2, H6 e H8 foram confirmadas no código. A nova regra da H4, H5, a origem do lançamento da multa na H6, H7 e H9 a H12 foram decididas pelo usuário em 2026-10-20, na entrevista da sincronização (P1 a P8), todas pela opção recomendada; H1, H3, H13, H15 e H16 continuam hipóteses, conferidas na etapa que as usa, e as decisões que dependem da investigação de cada etapa ficam para a entrevista da própria etapa (lista no fim de "Hipóteses e decisões em aberto").

## Objetivo

Entregar os módulos `rates` e `reservations`. Ao fim do marco, a API permite manter planos tarifários com derivação, preços por data e ocupação, restrições, pacotes e políticas; consultar disponibilidade e cotações; criar, alterar, cancelar e reativar reservas com preço congelado por noite, sem overbooking mesmo sob concorrência; manter pré-reservas com expiração automática; alocar UHs sem sobreposição garantida pelo banco; e operar grupos e bloqueios com pick-up. O `reservations` passa a ser o primeiro consumidor de produção de `OutOfOrderChanged` e de `GuestsMerged` e a primeira implementação das portas `GuestPersonalDataContributor` e `GuestActiveStayCheck` do `guests`. Check-in e check-out (M5), no-show, diárias e devolução de bloqueios no fechamento (M6), PDF de confirmação (M5) e channel manager (M8) usam o que este marco entrega. Reservas exigem conexão: nada deste marco entra na sincronização offline nem num tópico SSE (H14).

## Contexto confirmado

- Requisitos: SPEC §5.6 (TAR, exceto RF-TAR-10, envio ao channel manager, que é do M8: aqui só os eventos) e §5.7 (RES, exceto RF-RES-07 automático, RF-RES-10 e RF-RES-12, e o recorte integrado de RF-RES-08); regras RN-01, RN-02, RN-04, RN-07, RN-12, RN-14, RN-15, RN-16 e RN-23 (SPEC §6); RNF-06 (p95 < 300 ms). Ações sensíveis deste marco (RF-ACS-08): desconto além do limite, isentar multa de cancelamento e alterar tarifa publicada; reativar reserva cancelada ou no-show (SPEC §8.4).
- Modelo de domínio: SPEC §8.2.5 (`RatePlan`, `DerivationRule`, `RatePrice`, `Restriction`, `Package`, `CancellationPolicy`, `GuaranteePolicy`, `Season`) e §8.2.6 (`Booking`, `Reservation`, `ReservationStatus`, `StayGuest`, `NightlyRate`, `RoomAssignment`, `RoomBlock`, `BlockAllocation`; `Trace`, `MinorAuthorization` e os campos de check-in são do M5). Máquina de estados da reserva: SPEC §8.4. Eventos: `ReservationCreated`/`Changed`/`Cancelled`, `AvailabilityChanged`, `RatesChanged`/`RestrictionsChanged` e o consumo de `OutOfOrderChanged` (SPEC §8.3). SPEC Q-09 (overbooking proibido) e Q-15 (cortesia conta na ocupação e não no ADR; uso da casa não conta em nenhum dos dois).
- Módulos e dependências (ARCHITECTURE §3.1 e §3.2): `rates` (camada Hospedagem) → `shared`, `rooms`, `billing`; `reservations` (Hospedagem) → `shared`, `hotel`, `rooms`, `rates`, `guests`, `billing`, `printing` (o `printing` só a partir do M5: ele ainda não existe). Pela **regra por camadas**, um módulo pode depender de camada inferior e da Plataforma (`hotel`, `files`, `notifications`…) sem ciclo, acrescentando a aresta à tabela da §3.1 e ao `ModularityTest` na mesma mudança: `rates` → `hotel` (faixas etárias de criança e data operacional) entra na primeira etapa que usar a `HotelApi`; `reservations` → `notifications` só se a H11 for confirmada. `reservations` não chama `frontdesk`, `distribution` nem `endofday` (os de cima escutam os eventos deste marco). `rates` e `reservations` não importam `iam` (o `ModularityTest` barra: só `staff` e `notifications` usam a `IamApi`).
- Persistência e concorrência: ARCHITECTURE §11 (`EXCLUDE USING gist (room_id WITH =, stay WITH &&)` sobre alocações ativas, `btree_gist` já instalado no schema `public` pelo `shared`; contadores `room_type_inventory(room_type_id, date, total, sold, blocked, out_of_order)` com `SELECT … FOR UPDATE`; `daterange` para estadias), §5 (lock otimista com `ETag`, lock pessimista curto nos contadores) e §13 (expiração de pré-reservas a cada 5 min, com ShedLock).
- Segurança: ARCHITECTURE §6.3 (step-up) e §8.2 (permissões, escopo, limites; o exemplo de limite é `rates.discount.maxPercent`).
- Base técnica herdada de M0 a M3 (fonte: `CLAUDE.md`, código e handoffs; conferida no código em 2026-10-20):
  - **Módulos existentes** em `src/main/kotlin/com/zeny/hotel/`: `shared`, `iam`, `audit`, `hotel`, `staff`, `files`, `rooms`, `checklists`, `notifications`, `guests`, `payments` e `billing`, cada um com `domain/`, `application/`, `infrastructure/` e `web/` e um `…Module.kt` na raiz com `@ApplicationModule(allowedDependencies = …)` (ex.: `billing/BillingModule.kt` com `["shared", "hotel", "guests", "notifications", "payments"]`, `rooms/RoomsModule.kt` com `["shared", "hotel"]`, `guests/GuestsModule.kt` com `["shared", "files"]`). O `ModularityTest` (`src/test/kotlin/com/zeny/hotel/ModularityTest.kt`) tem um teste por módulo com as dependências diretas exatas e só pela API pública: um módulo novo ganha o seu teste. As regras de camadas ficam em `src/test/kotlin/com/zeny/hotel/architecture/LayerRules.kt` (inclui `writeUseCasesAreAudited`).
  - **Comandos oficiais** na tabela do `CLAUDE.md`: `./gradlew build` (compilação, testes, `ModularityTest`, ArchUnit, detekt, ktlint, `checkOpenApi` e `koverVerify`), `test`, `lint`, `ktlintFormat`, `checkOpenApi`, `updateOpenApi`, `docker compose -f docker/compose.yaml up -d --build` e `.github/scripts/check-api-breaking.sh`. Versões só em `gradle/libs.versions.toml`.
  - **Migrações:** uma pasta por módulo em `src/main/resources/db/migration/` (hoje `shared`, `iam`, `audit`, `hotel`, `staff`, `files`, `rooms`, `checklists`, `notifications`, `guests`, `payments`, `billing`), com versão por data e hora em UTC e uma migração de `GRANT` ao `"${app_role}"` por pasta (modelo: `billing/V2026_10_17_0546__grant_billing_to_app_role.sql`). A última versão aplicada é `V2026_10_20_0347` (`billing`, views): com `outOfOrder` desligado, toda migração nova precisa de timestamp posterior. `MigrationConventionTest` barra objeto, FK, trigger, `INSERT` ou `GRANT` fora do schema do módulo. Tabela *append-only* recebe só `SELECT, INSERT`, vai num bean `AppendOnlyTables` do módulo (conferido no boot pelo `DatabaseRoleGuard`) e, quando o dono também não pode mudar, ganha um trigger como `billing.posting_immutable`; exclusão física proibida por trigger como `rooms.refuse_delete`. Período sem sobreposição no banco: modelo `rooms.out_of_order_overlap_ex` (`EXCLUDE USING gist (room_id WITH =, blocked_period WITH &&)` sobre uma coluna gerada `daterange`), com a violação `23P01` convertida pelo nome da restrição no mesmo `409` da aplicação.
  - **Views `report_*`** (seção "Schemas e migrações" do `CLAUDE.md`; modelos `checklists/V2026_10_16_1700__create_checklist_report_views.sql` e `billing/V2026_10_20_0347__create_billing_report_views.sql`): `report_` seguido do assunto, no schema do módulo, uma linha por fato, ids opacos, **sem texto livre digitado por quem opera nem dado de hóspede**; contrato no comentário da migração; `GRANT SELECT` e `REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER` por view; consultas só pelas views, provadas com um papel que só as lê (`ChecklistReportsTest`, `BillingReportViewsTest`). Publicadas hoje: três `checklists.report_*`, `guests.report_guests` e cinco `billing.report_*`.
  - **Portas do kernel** (raiz de `com.zeny.hotel.shared`): `CurrentActor`, `Authorization` (`require`, `grantOrNull`, `scopeFilter`, `limit`, `requireOrApproval`), `SensitiveAction`/`LimitAmount`/`APPROVAL_TOKEN_HEADER` (`Approvals.kt`), `@Audited`/`AuditContext`/`AppendOnlyTables` (`Auditing.kt`), `ModulePermissions` com `LimitDefinition` (`LimitUnit.MONEY`, `PERCENT`, `QUANTITY`) e `templates` para `RoleTemplate` (`MANAGER`, `RECEPTIONIST`, `SALES_EVENTS`, `FINANCE`…; códigos de permissão em segmentos minúsculos, sem hífen), `Money` (`allocate`, `split`, `round`), `Percent`, `DocumentNumberGenerator`, `UuidV7`/`TypedId`, `PageParams`/`CursorPage`, `ETags`, `@Idempotent`, `@ExtensibleEnum` (`x-extensible-enum` nas propriedades de resposta que crescem), `BlindIndex` e `Clock` UTC.
  - **APIs públicas para o M4** (todas sem checar permissão: quem chama já se autorizou):
    - `HotelApi` (`hotel/HotelApi.kt`): `currentBusinessDate()`, `timezone()`, `policy()` com `checkInTime`, `checkOutTime`, `childMaxAge` e `childAgeBands` (`ChildAgeBand(minAge, maxAge, label)`, contíguas de 0 a `childMaxAge`, **vazia se o hotel não diferencia**), `taxSettings()` e `operationalSettings()` com `preReservationExpiry` (`Duration`, gravada em horas inteiras de 1 a 720, padrão 48 h) e `preReservationAlertLead` (menor que a validade). A data operacional só avança +1 dia pelo `advanceBusinessDate` (trigger em `hotel.business_day`), chamado só pelo fechamento (M6): nada a avança em produção no M4.
    - `RoomsApi` (`rooms/RoomsApi.kt`): `roomType(id)` (`RoomTypeSummary` com `occupancy` = `Occupancy(standardAdults, standardChildren, maxAdults, maxChildren)`, `featureIds`, `displayOrder`, `active`), `activeRoomTypes()`, `room(id)` e `activeRooms(roomTypeId?)` (`RoomSummary` com `number`, `floor`, `roomTypeId`, `featureIds`, `active`), `feature(key)` (`RoomFeatureKey`: `VIEW`, `ACCESSIBLE`, `CONNECTING`), `status(id)`, `isReadyForCheckIn(id)`, `outOfOrder(id)` e `outOfOrderPeriods(from, to, roomId?)` (OOO e OOS que bloqueiam algum dia do intervalo, com `kind` e `blockedUntil`). Eventos (`rooms/RoomsEvents.kt`): `RoomStatusChanged` e `OutOfOrderChanged(outOfOrderId, roomId, kind, action OPENED|UPDATED|RELEASED, startsOn, endsOn, releasedOn, blockedUntil, previousStartsOn, previousEndsOn, version, changedAt)`. **Não há evento de cadastro de UH** (criação, troca de tipo, desativação, reativação): a H4 caiu. OOO só pode ser aberto em UH ocupada se não incluir a data operacional; "a reserva no período é assunto do M4" (`CLAUDE.md`).
    - `GuestsApi` (`guests/GuestsApi.kt`): `guest(id)` (`GuestSummary` com `fullName`, `socialName`, `birthDate`, `vip`, `restricted`, `companyId`, `status` `ACTIVE`/`MERGED`/`ANONYMIZED` e `mergedInto`; **sem as preferências**), `findByDocument(type, issuingCountry, number)` (índice cego, só ativos), `restrictionAlert(id)`, `isMinorOn(id, date)`, `company(id)` e `fnrhData(id)` (exclusiva do `fnrh`). **Não há busca por nome** na API pública (só no endpoint do `guests`). Portas (`guests/GuestsPrivacy.kt`): `GuestPersonalDataContributor` (`section` no formato `módulo[.assunto]`, `personalData(guestIds)`) e `GuestActiveStayCheck` (`hasActiveStay(guestIds)`; `409 GUEST_HAS_ACTIVE_STAY` na anonimização), **sem implementação no M3**. Evento `GuestsMerged(survivorId, mergedId, mergedAt)` (`guests/GuestsEvents.kt`), **sem consumidor de produção**.
    - `BillingApi` (`billing/BillingApi.kt`): `openFolio(NewFolio(ownerType, ownerId, payerCompanyId, creditLimit))` com `FolioOwnerType` `RESERVATION`, `GROUP`, `EVENT`, `COMPANY`, `WALK_IN` (id opaco), `folio(id)`, `foliosOf(owner)`, `balanceOf(owner)` (`OwnerBalance.settled`), `closeFolio(id)`, `post(folioId, NewPosting(transactionCodeId, unitPrice, quantity, description, source, serviceChargeRate))` na data operacional com roteamento e taxa de serviço, `transactionCode(id)` e `transactionCode(TransactionCodeKey)` (`ROOM_CHARGE` `DIARIA` sem taxa de serviço, `CANCELLATION_FEE` `MULTA-CANC` e `NO_SHOW_FEE` `MULTA-NOSHOW` do tipo `PENALTY`, grupo `FEES`), `requireOpenShift(user)` e `cardPreAuthorizationsExpiring(from, to)`. `PostingSource` é `MANUAL`, `POS`, `END_OF_DAY`, `LAUNDRY`, `EVENT`, e o `source` da resposta de lançamento é um `enum` **fechado** no contrato (acrescentar um valor seria breaking change; só o `type` é `x-extensible-enum`). **Não há** consulta de depósitos por conta nem criação de regra de roteamento na `BillingApi` (só pela API HTTP `/api/v1/routing-rules`). O depósito é um pagamento com `deposit: true` (`POST /api/v1/folios/{id}/payments`), e a devolução é o reembolso sensível `billing.payments.refund`.
    - `NotificationsApi.notify(tipo, título, texto, NotificationRecipients)` (no próprio caso de uso; tipos num `ModuleNotificationTypes` do módulo; **nunca dado pessoal de hóspede no texto**), `PaymentsApi`, `FilesApi`, `StaffApi` e `ChecklistsApi` (sem uso previsto no M4).
  - **Eventos e jobs:** listeners de produção `ChecklistRoutineListener`, `PushDispatchListener` e `PaymentSucceededListener`. O outbox **só grava a publicação quando há listener**: um evento sem consumidor de produção (`RatesChanged`, `RestrictionsChanged`, `AvailabilityChanged`, `ReservationCreated`/`Changed`/`Cancelled` até o M5/M8) é testado com `@EnableScenarios` e um listener de teste (modelos `BusinessDateListenerConfiguration` em `src/test/kotlin/com/zeny/hotel/hotel/BusinessDateTest.kt` e `GuestsMergedListenerConfiguration` nos testes do `guests`). Reenvio com backoff de 1 min a 1 h e abandono depois de 24 h (M2 E12, métrica `abandoned` e alerta): todo listener é idempotente, e a recontagem do inventário cobre uma publicação abandonada. Job novo: `@Scheduled` + `@SchedulerLock` com nome único, e uma constante de adiamento no `@IntegrationTest` como `FILES_PURGE_DEFERRED` (`src/test/kotlin/com/zeny/hotel/testsupport/IntegrationTest.kt`), com os testes chamando o job direto.
  - **Idempotência e aprovação:** `@Idempotent` no método do controller exige `Idempotency-Key` e guarda a resposta (inclusive erro e `403 APPROVAL_REQUIRED`) por 24 h; a repetição com `X-Approval-Token` usa **outra** chave. Só JSON síncrono. Ação sensível: permissão `sensitive = true` e `authorization.requireOrApproval(SensitiveAction(ação, permissão, resourceRef, recurso?, LimitAmount?), approvalToken)` no caso de uso, com o cabeçalho repassado pelo controller; o token vincula ação, permissão, registro e valor do limite, não as datas (M1 E05).
  - **Web e contrato:** controllers com `@ApiV1Controller`, `@Operation(operationId)` único e `@Tag` do módulo; DTOs com `Instant`, `LocalDate`, `Money`/`Percent` como string e `List<@Valid T>`; erros por família (`InvalidInputException` 400 com `violations`, `ConflictException` 409, `BusinessRuleException` 422…) com códigos na API pública do módulo; referência inexistente no corpo é `400 UNKNOWN_…` com o campo, desativada `422 …_INACTIVE` (padrão do M2/M3); `api/openapi.yaml` regenerado por `./gradlew updateOpenApi`. **Número de documento nunca vai na URL** (busca no corpo, como `POST /api/v1/guests/search-by-document`).
  - **Apoio de testes:** `src/test/kotlin/com/zeny/hotel/testsupport/` (`IntegrationTest`, `asUser(userId, sessionId)` em `TestAuth.kt`, `MutableClockConfiguration`, `PostgresContainer`, `MinioContainer`) e, nos pacotes de cada módulo, `IamTestSupport` (roles, usuários, `setPin`, `call(…, approvalToken = …)`), `RoomsTestSupport` (tipo, UH, característica e área pela API), `GuestsTestSupport` (CPF e CNPJ válidos, `passport(prefixo)` com dígito garantido) e `BillingTestSupport` (chamadas com `Idempotency-Key`). O banco é compartilhado entre os testes: **cada teste de inventário cria os próprios tipos de UH** (senão os contadores de outro teste interferem), as datas partem de `HotelApi.currentBusinessDate()` e, para provar "data operacional ≠ data do relógio" ou vencer prazos, o relógio é movido com o `MutableClock` e devolvido no fim.
  - **Compose e gates:** os gates do M1 a M3 conferiram tudo numa cópia limpa (`git archive`, `GRADLE_USER_HOME` novo, `--no-daemon --no-build-cache`) e num `docker compose` isolado (`-p` próprio, `--env-file` no scratchpad gerado do `docker/.env.example`, porta própria, profile `prod`, pelo proxy, `down -v` no fim), por roteiro `curl`, com oasdiff contra o gate anterior.
- **Pendências herdadas do M3 e onde entram aqui:**
  - primeiro listener de produção de `GuestsMerged` (`@ApplicationModuleListener` idempotente que reponta `mergedId` → `survivorId`) → **E07** (titular e hóspedes da estadia), estendido a bloqueios na **E11**;
  - primeira implementação de `GuestPersonalDataContributor` (seção `reservations`) e de `GuestActiveStayCheck` (estadia ativa bloqueia a anonimização, H12) → **E07**;
  - primeiro consumidor de `OutOfOrderChanged` (contadores e alerta de reservas alocadas) → **E05** (contadores) e **E10** (alerta, H11);
  - multa de cancelamento pela `BillingApi.post` com `TransactionCodeKey.CANCELLATION_FEE` na data operacional → **E08** (H6); o no-show (`NO_SHOW_FEE`) e as diárias (`ROOM_CHARGE`) são lançados pelo fechamento (M6);
  - regras de roteamento da conta master do grupo só pela API HTTP → **E11** (acrescentar à `BillingApi` só se a etapa precisar criá-las);
  - consulta de depósitos da conta, que a `BillingApi` não tem → **E09** (H13);
  - busca por nome e preferências do hóspede, que a `GuestsApi` não expõe → **E12** e **E10** (H13);
  - publicação de `GuestsMerged` e de `OutOfOrderChanged` no outbox, que o compose do M2/M3 não mostrava por falta de listener → **E13** (o roteiro mostra as duas).
- **Pendências herdadas que não são do M4:** consumidores de `ChecklistCompleted` (M6/M9); push real quando houver projeto Firebase; taxa de serviço padrão definida na implantação, recifragem dos demais valores na rotação do anel e configuração do PostgreSQL de produção (M8); `FilesApi` gravando conteúdo (M5, `printing`); contas a receber e `CITY_LEDGER` (M10); lançamento dos pacotes (na chegada pelo check-in do M5, diário pelo fechamento do M6).

## Escopo

**Incluído:**

- `rates`: planos tarifários com regime de alimentação, canais, tipos de UH, categoria (normal, cortesia, uso da casa); políticas de cancelamento e garantia; pacotes ligados a códigos de lançamento; preços por tipo × data × ocupação; derivação; edição em massa e temporadas; restrições; API de preço e vendabilidade; eventos `RatesChanged` e `RestrictionsChanged`.
- `reservations`: inventário por tipo e data, disponibilidade e calendário, cotação; reservas e reservas agrupadoras com preço congelado e política vigente; abertura da conta da reserva em `billing`; alteração, cancelamento com multa ou isenção, reativação; pré-reserva, garantia manual e expiração; alocação de UH com sugestão e "não mover"; consumo de `OutOfOrderChanged`; grupos, bloqueios, rooming list e pick-up; busca, listas operacionais e views `report_*`; eventos de reserva e de disponibilidade; listener de `GuestsMerged` e as portas de privacidade do `guests`.
- Acréscimos mínimos em módulos de baixo, sem quebrar contrato: evento de cadastro de UH no `rooms` (H4, E05), consulta de depósitos na `BillingApi` (E09) e busca por nome e preferências na `GuestsApi` (E10 e E12).

**Fora de escopo:**

- Check-in, check-out, desfazer check-in, troca de UH de hóspede em casa e walk-in (M5, `frontdesk`).
- No-show automático, lançamento das diárias e dos pacotes e devolução de bloqueios vencidos no fechamento (M6, `endofday`); aqui só a API e a regra.
- Confirmação de reserva em PDF ou texto (RF-RES-12): M5. `reservations` monta os dados e chama `printing` (dono + printing).
- Garantia por cartão tokenizado e sinal por Pix ou link (RF-RES-08, parte integrada): M7.
- Reservas do channel manager e envio de ARI (RF-RES-10, RF-TAR-10): M8; aqui só os eventos.
- Alerta por push de reservas sem garantia: a SPEC (RF-RES-08) pede alerta, não push, e RF-NOT-01 não lista esse tipo; fica a lista consultável aqui e o painel em M6.
- Sincronização offline e tópico SSE de reservas (H14).
- Eventos e locação de espaços (M10).

## Restrições e invariantes

- Respeitar o grafo de módulos da ARCHITECTURE §3.1 e a regra por camadas. Reações de módulos abaixo (`rooms`, `guests`) chegam por evento; reações de módulos acima (`frontdesk`, `distribution`, `endofday`) escutam os eventos deste marco. `rates` e `reservations` não importam `iam`, não leem tabelas de outros schemas e não criam FK entre schemas; toda aresta nova vai à ARCHITECTURE §3.1 e ao `ModularityTest` na mesma mudança.
- RN-04: UH em dobro é impossível no banco, não só na aplicação.
- Sem overbooking (Q-09): o limite configurável por tipo e data nasce zero; os contadores são a fonte da verdade e só mudam com lock na mesma transação da reserva ou do bloqueio (o OOO chega pelo listener, H10).
- RN-14: o preço é congelado por noite na criação ou alteração; mudar a tarifa depois não altera reservas existentes. RN-15: a política vigente no momento da reserva é guardada com a reserva.
- RN-01: data operacional pela `HotelApi.currentBusinessDate()`; instantes pelo `Clock` injetado; prazos em horas no fuso do hotel (`HotelApi.timezone()`).
- RN-02: valores só com `Money` e percentuais só com `Percent`; derivações e totais usam o arredondamento único de `Money`; repartir com `allocate`/`split`.
- RN-13 e privacidade: nenhuma view `report_*`, evento, notificação ou log expõe nome, documento, contato ou dado sensível de hóspede; os eventos levam só ids, datas e situações; o número de documento nunca vai na URL; a trilha não leva texto livre (pedidos especiais) nem dado pessoal, como na H10 do M3.
- Toda escrita auditada (`@Audited`, regra `writeUseCasesAreAudited`); `@Idempotent` nos POSTs que criam reserva, bloqueio ou rooming list, cancelam (lançam multa) ou reativam; `ETag`/`If-Match` nas alterações; ações sensíveis por `requireOrApproval`.
- Enums de resposta que vão crescer (motivo de cancelamento, origem, situações novas a cada marco) com `@field:ExtensibleEnum`; um `enum` fechado e estável continua `enum`.
- Migrações novas com timestamp posterior a `V2026_10_20_0347`; nenhum objeto, `GRANT` ou `INSERT` fora do schema do próprio módulo.
- Convenções de API, erros, migrações e tipos seguem o `CLAUDE.md`; não criar convenção paralela. Cada etapa atualiza o "Estado atual" e a seção do módulo no `CLAUDE.md` e a seção "API" do `README.md`.
- Contrato `api/openapi.yaml` regenerado e versionado a cada etapa que mexe na API; `.github/scripts/check-api-breaking.sh` sem mudança `ERR`.

## Hipóteses e decisões em aberto

| # | Hipótese ou decisão | Impacto se errada / sem decisão | Etapa afetada |
|---|---|---|---|
| H1 | Preços de planos derivados são calculados na leitura a partir do plano base, não materializados; `RatesChanged` informa também os planos derivados afetados. | Se o desempenho da cotação não atender RNF-06, materializar numa etapa futura sem mudar a API. | E02, E04 |
| H2 | **Confirmada no código em 2026-10-20:** as faixas etárias de criança vêm de `HotelApi.policy().childAgeBands` (de 0 a `childMaxAge`; lista vazia = um preço único de criança). O `rates` passa a depender de `hotel` (Plataforma, regra por camadas), com a aresta na ARCHITECTURE §3.1 e no `ModularityTest` na primeira etapa que usar a `HotelApi`. | Mudar as faixas no hotel não reprecifica reservas (RN-14); preços por faixa ficam órfãos se uma faixa sumir (a E02 decide o que fazer e registra). | E02, E04 |
| H3 | O inventário é mantido para um horizonte de 730 dias a partir da data operacional; um job diário com ShedLock estende o horizonte (o OOO vai até 365 dias). | Reservas além do horizonte são recusadas com `code` estável até o job estender. | E05 |
| H4 | **Caiu no código em 2026-10-20:** o `rooms` só publica `RoomStatusChanged` e `OutOfOrderChanged`. **Decidida pelo usuário em 2026-10-20** (entrevista da sincronização, P3, a opção recomendada): a E05 acrescenta ao `rooms` um evento mínimo de cadastro de UH (nome a confirmar na etapa, em `rooms/RoomsEvents.kt`: id da UH, tipo, tipo anterior, ativa, versão e instante, sem dado de hóspede), publicado pelo outbox na criação, na troca de tipo, na desativação e na reativação; o `reservations` o escuta para recalcular o `total` dos tipos afetados, e uma recontagem a partir da `RoomsApi` repara qualquer divergência. O `rooms` não recusa desativar UH com reservas futuras (como o OOO, RN-07): a disponibilidade fica negativa e as datas aparecem como sobrevendidas. | Alternativa: sem evento, o `total` lido ao vivo da `RoomsApi.activeRooms` na checagem e a coluna só para leitura, refeita pela recontagem. Muda a E05 e o `rooms`. | E05 |
| H5 | **Decidida pelo usuário em 2026-10-20** (entrevista da sincronização, P6, a opção recomendada): o day use (RF-RES-14) tem chegada = saída, não consome o contador de pernoite e usa um plano com tarifa própria; a alocação de day use usa o intervalo `[data, data]` (fechado) e só conflita na mesma UH com uma estadia que inclua aquela noite ou com outro day use da data; a saída e a chegada de outras reservas no mesmo dia não conflitam. **Aplicada na E10 (2026-10-21):** a contradição interna (uma estadia que chega na data "inclui aquela noite") foi resolvida pela frase explícita sobre a chegada: "estadia que inclua aquela noite" = estadia que **atravessa** o dia (chegada < data < saída); a restrição usa meios-dias (`room_assignment.slots`), e o day use não conflita com a saída nem com a chegada de outras reservas na data. | Alternativa: day use consome a noite da data (mais simples, perde a venda da noite). Revisão local à alocação e ao inventário. | E07, E10 |
| H6 | **Confirmada no código em 2026-10-20:** a multa de cancelamento é lançada na conta da reserva pela `BillingApi.post` com o código de `BillingApi.transactionCode(TransactionCodeKey.CANCELLATION_FEE)` (`MULTA-CANC`, `PENALTY`/`FEES`), na data operacional e na mesma transação do cancelamento; a devolução de depósito segue o reembolso manual do M3 (`billing.payments.refund`, sensível, operado pela recepção). Origem **decidida pelo usuário em 2026-10-20** (entrevista da sincronização, P7, a opção recomendada): `PostingSource.MANUAL` (quem cancela é um operador), sem valor novo no `enum`, porque o `source` da resposta de lançamento é `enum` fechado e acrescentar um valor seria breaking change. | Alternativa: origem nova `RESERVATION`, trocando antes o `source` para `x-extensible-enum` (decisão registrada, muda o tipo gerado nos clientes). | E08 |
| H7 | **Decidida pelo usuário em 2026-10-20** (entrevista da sincronização, P1, a opção recomendada): a permissão do desconto fica no catálogo do `rates`, como na ARCHITECTURE §8.2 (`RatesPermissions`, código sugerido `rates.discounts.apply`, **sensível**, limite `maxPercent` em `LimitUnit.PERCENT`), e o `reservations` a confere ao gravar uma noite abaixo do preço calculado (`LimitAmount("maxPercent", desconto)`); modelos: Gerente sem teto, Recepcionista com teto de 10%. | Alternativa: permissão no `reservations` (quem aplica o desconto). Um código publicado não muda depois. | E01, E07, E08 |
| H8 | **Confirmada no código em 2026-10-20:** o evento `GuestsMerged` existe e não tem consumidor; o `reservations` guarda ids de hóspede (titular do booking, hóspedes da estadia e, na E11, titular do bloqueio) e é o primeiro `@ApplicationModuleListener` de produção dele, idempotente (repontar o que já foi repontado não muda nada; cadeia A → B → C, um evento por mesclagem). Na criação, um hóspede `MERGED` é trocado pelo sobrevivente (seguindo `mergedInto`) e um `ANONYMIZED` é recusado. | Sem isso, reservas antigas apontariam para perfis mesclados; a corrida entre a mesclagem e uma criação em andamento fica no foco do verificador da E07. | E07, E11 |
| H9 | **Decidida pelo usuário em 2026-10-20** (entrevista da sincronização, P2, a opção recomendada): "tarifa publicada" é um preço já gravado, de plano ativo, para data igual ou posterior à data operacional; alterar ou retirar um preço publicado exige uma permissão **sensível** própria (código sugerido `rates.prices.override`, só o Gerente no modelo), e gravar preço onde não havia, ou num plano inativo, usa a permissão comum de preços (`rates.prices.manage`, não sensível). | Alternativa: toda a permissão de preços sensível (mais simples, sem distinguir publicado). Muda E02 e os modelos de role. | E02 |
| H10 | **Decidida pelo usuário em 2026-10-20** (entrevista da sincronização, P4, a opção recomendada): o OOO chega ao inventário pelo listener assíncrono de `OutOfOrderChanged` (SPEC §8.3), idempotente porque **recalcula** o `out_of_order` das datas afetadas a partir da `RoomsApi.outOfOrderPeriods` (fonte da verdade, só o tipo `OUT_OF_ORDER`; OOS continua vendável), e não soma deltas; a janela entre o commit do OOO e o listener (segundos) é aceita, e a frase da ARCHITECTURE §11 ("na mesma transação da reserva, bloqueio ou OOO") é corrigida na E05 para dizer que o OOO entra pelo evento. | Alternativa: porta no `rooms`, implementada pelo `reservations`, chamada na transação do OOO (sem janela, mas acopla a escrita do OOO ao inventário). | E05 |
| H11 | **Decidida pelo usuário em 2026-10-20** (entrevista da sincronização, P5, a opção recomendada): o alerta de RN-07 é a marca "requer realocação" nas reservas alocadas na UH do OOO (consultável numa lista) **e** uma notificação interna de um tipo novo do `reservations` (código sugerido `reservations.reallocation-required`, não obrigatório no painel) aos usuários com a permissão de alocação, com o número da UH e as datas, sem dado de hóspede; aresta `reservations` → `notifications` na ARCHITECTURE §3.1 e no `ModularityTest`. | Alternativa: só a lista (o painel do M6 a mostra), sem aresta com `notifications`. Muda a E10. | E10 |
| H12 | **Decidida pelo usuário em 2026-10-20** (entrevista da sincronização, P8, a opção recomendada): para `GuestActiveStayCheck`, estadia ativa é uma reserva `PROVISIONAL`, `CONFIRMED` ou `IN_HOUSE` (este a partir do M5) em que o hóspede é titular do booking ou hóspede da estadia; a anonimização fica recusada até a reserva terminar ou ser cancelada. A seção `reservations` da exportação traz, por reserva, código, situação, datas, tipo de UH, plano, ocupação, segmento, pedidos especiais e as noites com o preço. | Alternativa: só `IN_HOUSE` bloqueia (reservas futuras não impedem a anonimização; a reserva fica com o perfil anonimizado). | E07 |
| H13 | Os módulos de baixo ganham, sem quebrar contrato, o que o `reservations` precisa e as APIs públicas não têm: `BillingApi` com o total líquido de depósitos de uma conta (recebimentos com `deposit` menos os reembolsos deles; E09), `GuestsApi` com as preferências do hóspede (E10) e uma busca por nome que devolve só ids, limitada (E12). | Se a revisão preferir outro desenho (ex.: o operador declara o depósito na confirmação, sem conferir), muda só a etapa afetada. | E09, E10, E12 |
| H14 | **Confirmada na documentação** (ARCHITECTURE §9, "Recepção, contas e PDV exigem conexão", e §10, sem tópico de reservas): o M4 não registra `OfflineSource`, `OfflineCommandHandler` nem `RealtimeTopic`. | Se a recepção pedir atualização em tempo real do mapa de reservas, um tópico entra depois sem mudar o modelo. | Todas |
| H15 | Datas e prazos: restrições, antecedência e listas usam a data operacional (RN-01); a antecedência é contada em dias da data operacional até a chegada; o prazo de cancelamento (horas) é contado até o horário de check-in (`HotelApi.policy().checkInTime`) da data de chegada, no fuso do hotel; a validade da pré-reserva é o instante da criação mais `HotelApi.operationalSettings().preReservationExpiry`, e o alerta usa `preReservationAlertLead`. | Mudança é local ao cálculo; testar perto da meia-noite e com o `MutableClock`. | E03, E04, E08, E09, E12 |
| H16 | Nomes de permissões sugeridos (segmentos minúsculos, sem hífen; confirmados em cada etapa; um código publicado não muda): `rates.plans.read`/`manage` (planos, políticas e pacotes), `rates.prices.manage` e `rates.prices.override` (sensível, H9), `rates.restrictions.manage`, `rates.discounts.apply` (sensível, `maxPercent`, H7); `reservations.availability.read`, `reservations.inventory.manage` (limite de overbooking), `reservations.reservations.read`/`manage`, `reservations.penalties.waive` (sensível), `reservations.reservations.reactivate` (sensível), `reservations.assignments.manage`, `reservations.blocks.read`/`manage`. Modelos pela SPEC §3.1: Gerente todas (sem teto), Recepcionista consulta, reservas, alocação e desconto até o teto, Comercial/Eventos bloqueios e consulta, Financeiro consulta. | Nomes ajustados na etapa sem efeito fora dela. | E01, E02, E03, E05, E07, E08, E10, E11 |

**Decisões abertas para a entrevista de cada etapa** (dependem da investigação; a sessão da etapa devolve os pontos ao orquestrador, que entrevista o usuário):

- **E01:** ~~canais como lista fixa (`enum`) ou códigos cadastráveis; regime de alimentação; política editada na hora ou versionada~~ — **decididas pelo usuário em 2026-10-20** (todas pela opção recomendada): canais `enum` fixo `DIRECT`/`WEBSITE`/`OTA` com `x-extensible-enum` na resposta (a OTA específica é a origem da reserva, mapeada no M8); regime `ROOM_ONLY`/`BREAKFAST`/`HALF_BOARD` com `x-extensible-enum`; políticas não versionadas (a edição vale para as reservas novas; a reserva copia os termos, RN-15). Pontos novos da E01 no handoff dela.
- **E02:** ~~o que acontece com preços por faixa quando as faixas do hotel mudam; limite de período da edição em massa~~ — **decididas pelo usuário em 2026-10-20** (entrevista da E02, ambas pela opção recomendada): (1) o preço guarda a faixa (idades) com que foi gravado; a faixa que não existe mais nas `childAgeBands` atuais é ignorada e a data fica sem preço de criança naquela faixa (não vendável para criança dessa idade, regra que a E04 aplica) até o gestor regravar, e a leitura sinaliza a faixa obsoleta; nada é recalculado em silêncio, reservas já feitas não mudam (RN-14) e nunca cai no preço de adulto extra; (2) edição em massa de até 366 dias por requisição (acima, `400 RATE_PRICE_PERIOD_TOO_LONG` no campo `to`); períodos maiores em duas chamadas. Pontos novos da E02 no handoff dela.
- **E03:** ~~semântica de CTA, CTD e estadia mínima (pela restrição da data de chegada ou de cada noite), registrada para o M8 mapear~~ — **decididas pelo usuário em 2026-10-20** (entrevista da E03, todas pela opção recomendada): (1) estadia mínima e máxima "on arrival": valem as da data de chegada, não as de cada noite (a antecedência também pela data de chegada, H15); (2) CTA e CTD só na data exata: o CTA da data de chegada impede chegar nela, o CTD da data de saída impede sair nela, e as noites do meio não são afetadas (só o "fechado" vale em qualquer noite da estadia); (3) precedência: a restrição do tipo de UH substitui a geral do plano **campo a campo** (o campo informado no tipo prevalece, o vazio herda o da geral), com todos os campos anuláveis e os booleanos tri-estado (o `false` do tipo abre exceção sobre a geral `true`). Registradas no `CLAUDE.md` (seção "Restrições de venda") para o M8 mapear. Pontos novos da E03 no handoff dela.
- **E04:** ~~criança acima da `childMaxAge`; inclusos acima da diária; onde entra o pacote por estadia; pacotes extras na consulta; preço faltante no `isSellable`; ocupação máxima; quem conta no pacote por pessoa/noite~~ — **decididas pelo usuário em 2026-10-20** (entrevista da E04, todas pela opção recomendada; detalhes no handoff da E04).
- **E07:** ~~segmentos (lista fixa ou cadastrável); se a reserva agrupadora tem conta própria além das contas das reservas~~ — **decididas em 2026-10-21 pelo orquestrador, por delegação do usuário ausente** (opção recomendada depois de pesar prós e contras): segmentos em lista fixa `MarketSegment` com `x-extensible-enum`; a reserva agrupadora sem conta própria (cada reserva tem a sua, RF-CTA-01; o "a empresa paga tudo" pelo roteamento ou pela conta master da E11). Demais pontos no handoff da E07.
- **E08:** ~~o que acontece com a conta de uma reserva cancelada sem saldo (encerrar pela `BillingApi.closeFolio` ou deixar aberta); antecedência mínima para alterar uma reserva~~ — **decididas em 2026-10-21 pelo orquestrador, por delegação do usuário ausente** (opção recomendada depois de pesar prós e contras): a conta da reserva cancelada fica aberta (o reembolso do depósito, o estorno da multa e a reativação precisam dela; o fechamento do dia, M6, pode encerrar as zeradas); nenhuma antecedência mínima para alterar (valem a máquina de estados, a chegada não antes da data operacional e a antecedência do plano). Demais pontos no handoff da E08.
- **E09:** como a garantia "cartão" é registrada manualmente até o M7 (só informada ou ligada a uma caução registrada do M3).
- **E11:** se a unidade de uma reserva de pick-up cancelada antes do corte volta ao bloqueio; se a rooming list cria regras de roteamento para a conta master (exigiria acrescentar a criação de regra à `BillingApi`).
- **E12:** nomes e colunas das views `report_*` de `rates` e `reservations`.

## Definição global de pronto

- [ ] Cotação para um período, ocupação e canal devolve tipos e planos vendáveis com total e detalhe por noite, respeitando restrições, derivação e pacotes.
- [ ] Teste concorrente: N reservas simultâneas para o último quarto disponível de um tipo resultam em exatamente uma criada e as demais recusadas com `code` estável.
- [ ] Inserir duas alocações sobrepostas da mesma UH falha no banco mesmo contornando a aplicação (teste de integração com SQL direto).
- [ ] Mudar a tarifa depois de criar uma reserva não altera o preço por noite dela.
- [ ] Pré-reserva vencida é cancelada pelo job e devolve o inventário.
- [ ] OOO de uma UH reduz a disponibilidade do tipo nas datas (pelo listener de `OutOfOrderChanged`) e sinaliza reservas alocadas no período.
- [ ] Bloqueio de grupo consome inventário e o pick-up transforma unidades do bloqueio em reservas sem consumir duas vezes.
- [ ] Desconto acima do limite, isenção de multa, alteração de tarifa publicada e reativação exigem permissão ou aprovação de superior (`403 APPROVAL_REQUIRED`) e ficam auditadas com solicitante e aprovador.
- [ ] `GuestsMerged` reponta as referências de hóspede das reservas (primeiro listener de produção do evento), a anonimização é recusada para hóspede com estadia ativa e a exportação do titular inclui a seção `reservations`.
- [ ] As views `report_*` de `rates` e `reservations` existem, o papel da aplicação só tem `SELECT` nelas, e nenhuma tem texto livre nem dado de hóspede.
- [ ] `ApplicationModules.verify()` passa, sem dependência fora da ARCHITECTURE §3.1 (`ModularityTest` com um teste por módulo novo).
- [ ] Contrato `api/openapi.yaml` atualizado; `./gradlew build` e `.github/scripts/check-api-breaking.sh` passam numa cópia limpa; o roteiro ponta a ponta passa num `docker compose` isolado.

## Protocolo de execução

Vale para toda etapa, executada manualmente ou pelo orquestrador. Cada etapa declara sua estratégia em "Estratégia de execução".

**Papéis**

- **Sessão principal** (no modo automático, o subagente implementador): a única que edita código e este roadmap. Implementa, testa, atualiza tags e handoff.
- **Investigação** (subagente `Explore`, somente leitura): para material extenso que será lido mas não editado. Recebe as perguntas listadas na etapa e devolve um resumo com caminhos e linhas. Arquivos que serão editados são lidos diretamente pela sessão principal.
- **Implementação paralela** (subagentes `general-purpose`, só na estratégia `paralela`): cada um recebe uma parte com os arquivos listados na etapa, disjuntos dos demais; roda só os testes focados da sua parte; não toca arquivos compartilhados (build, configuração, este roadmap).
- **Verificação independente** (subagente `general-purpose` novo, que não participou da implementação): confere a etapa contra os critérios de aceite. Não edita nada.

**Estratégias:** `direta` — sem subagentes, a sessão principal valida · `padrão` — investigação quando indicada + verificação independente obrigatória · `paralela` — `padrão` + partes paralelas.

**Passo a passo**

1. **Preparação:** leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho deste roadmap, a seção da etapa e o handoff das dependências; rode `git status`. Confirme que as dependências estão `#concluida`, que a etapa não está `#bloqueada` e que o código bate com o handoff das dependências; se não, pare e relate.
2. **Snapshot inicial:** registre o estado da árvore de trabalho, sem commit e sem alterar o índice do git:

   ```bash
   idx=$(mktemp -u); cp "$(git rev-parse --git-path index)" "$idx" 2>/dev/null; GIT_INDEX_FILE="$idx" git add -A && GIT_INDEX_FILE="$idx" git write-tree; rm -f "$idx"
   ```

   Anote o hash impresso como "Snapshot inicial" no handoff da etapa (no modo automático, use o hash recebido do orquestrador em vez de tirar outro). Para ver tudo o que a etapa mudou, rode o mesmo comando de novo e compare: `git diff HASH_INICIAL HASH_ATUAL`. Se `git status` mostrar artefatos de build não ignorados, corrija o `.gitignore` antes do snapshot — senão o diff fica lento e poluído.
3. **Início:** troque a tag da etapa para `#em-andamento` no índice e no título.
4. **Investigação** (se a etapa listar perguntas): dispare um subagente `Explore` com essas perguntas.
5. **Implementação:** quando os critérios descrevem comportamento verificável, escreva os testes primeiro e confirme que falham. Implemente em passos pequenos; na estratégia `paralela`, dispare as partes paralelas e integre-as. Rode as validações obrigatórias da etapa.
6. **Verificação independente** (`padrão` e `paralela`): dispare um subagente `general-purpose` novo com o "Prompt do verificador" abaixo, trocando ETAPA pelo ID da etapa e HASH_INICIAL pelo snapshot. Se o parecer for REPROVADO, corrija e verifique de novo com um verificador novo. Após duas reprovações, pare e relate, mantendo `#em-andamento`.
7. **Fechamento:** marque `#concluida` (índice e título) só com todos os critérios atendidos e, quando houver verificação, parecer APROVADO. Diante de impedimento real, marque `#bloqueada` e preencha "Bloqueios" com causa, evidência, impacto e ação de desbloqueio. Preencha "Evidências e handoff" e responda com um resumo curto. Não faça commit, push ou deploy.

**Prompt do verificador**

```text
Você é o verificador independente da etapa ETAPA do roadmap docs/roadmaps/roadmap-m4-tarifas-reservas.md. Você não participou da implementação e não deve editar nenhum arquivo.
1. Leia CLAUDE.md (se existir), o cabeçalho do roadmap e a seção ETAPA inteira (objetivo, escopo, fora de escopo, estratégia, critérios de aceite, validação obrigatória).
2. Veja o que mudou desde o início da etapa: gere um snapshot do estado atual com o comando da seção "Protocolo de execução" e rode git diff --stat HASH_INICIAL seguido do hash gerado; depois leia o diff completo dos arquivos relevantes.
3. Rode todos os comandos da validação obrigatória da etapa e guarde a saída relevante.
4. Para cada critério de aceite, decida APROVADO ou REPROVADO com evidência concreta (arquivo:linha, nome do teste, saída de comando). Critério sem evidência é REPROVADO.
5. Ataque os pontos listados em "Foco do verificador" na etapa e aponte também: mudanças fora do escopo, testes que não exercitam o comportamento (passariam mesmo com o código errado), segredos ou dados pessoais expostos e violações das restrições do roadmap.
Responda com: parecer geral (APROVADO só se todos os critérios forem APROVADO e não houver problema grave), tabela critério → resultado → evidência, problemas por gravidade e comandos executados.
```

## Execução automática

Cole o prompt abaixo numa sessão limpa do Claude Code, na raiz do repositório. Ajuste o limite de etapas se quiser.

```text
Você é o orquestrador do roadmap docs/roadmaps/roadmap-m4-tarifas-reservas.md neste repositório. Seu papel é coordenar subagentes, não implementar: não edite código e mantenha seu contexto enxuto (leia o roadmap e os relatórios dos subagentes, não o código).

Limite desta execução: no máximo 3 etapas concluídas.

Preparação:
1. Leia CLAUDE.md (e AGENTS.md, se existir) e, no roadmap, o cabeçalho, o "Protocolo de execução" e o índice.
2. Rode git status. Se alguma etapa estiver #em-andamento, pare e pergunte ao usuário como prosseguir — provavelmente é uma execução interrompida.

Repita, uma etapa por vez (nunca em paralelo):
1. Escolha a primeira etapa, na ordem do documento, marcada #pendente e com todas as dependências #concluida. Etapas #bloqueada e as que dependem delas ficam para trás. Se não houver candidata, pare.
2. Gere o snapshot inicial com o comando do protocolo e guarde o hash.
3. Dispare um subagente general-purpose com o prompt da etapa copiado literalmente da seção dela, acrescido deste adendo: "Você está rodando como subagente do orquestrador; este adendo prevalece sobre o prompt acima. O snapshot inicial já foi tirado: use HASH e não tire outro. Não dispare subagentes: faça a investigação você mesmo, implemente em sequência as partes paralelas, se houver, e pule a verificação independente, que o orquestrador fará. Não marque a etapa como #concluida: ao terminar, deixe-a #em-andamento com 'Evidências e handoff' preenchido e relate o resultado das validações. Diante de bloqueio real, marque #bloqueada como o prompt manda." Substitua HASH pelo hash do passo 2.
4. Se o subagente relatar bloqueio, divergência do plano ou validação falhando que não conseguiu resolver, pare.
5. Qualquer que seja a estratégia, dispare um subagente general-purpose novo com o "Prompt do verificador" do protocolo (ETAPA e HASH_INICIAL preenchidos). Se REPROVADO, mande os problemas ao mesmo implementador, se puder retomá-lo; senão, dispare um implementador novo com o prompt da etapa, o adendo, o parecer do verificador e o handoff atual. Verifique de novo com um verificador novo; após duas reprovações, pare.
6. Com o aceite, troque a tag da etapa para #concluida no índice e no título e acrescente ao handoff o parecer do verificador.
7. Se a etapa tiver "Revisão humana: sim", pare para o usuário revisar.

Pare também ao atingir o limite desta execução. Nunca faça commit, push ou deploy e não altere tags de etapas que não executou.

Ao parar, responda com: etapas concluídas e seus pareceres, a etapa atual e seu estado, o motivo da parada, as etapas #bloqueada com a ação de desbloqueio de cada uma, os arquivos alterados (git status --short) e o próximo passo sugerido — normalmente revisar e fazer commit antes da próxima execução.
```

## Índice de etapas

**Tarifas (`rates`)**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E01 | Planos tarifários, políticas e pacotes | #concluida | — | Módulo `rates` com planos, políticas de cancelamento e garantia, pacotes e o catálogo de permissões |
| E02 | Preços por data e ocupação, derivação e edição em massa | #concluida | E01 | `RatePrice` por faixa etária do hotel, planos derivados, temporadas e alteração de tarifa publicada com step-up |
| E03 | Restrições de venda | #concluida | E01 | Fechado, CTA, CTD, estadia e antecedência mín./máx. por data, plano e tipo |
| E04 | Cálculo de preço e vendabilidade | #concluida | E02, E03 | API pública `priceFor` e `isSellable` com pacotes e faixas etárias |

**Disponibilidade e reservas (`reservations`)**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E05 | Inventário por tipo e data | #concluida | — | `room_type_inventory` com lock, horizonte, listener de OOO, evento de cadastro de UH no `rooms` e `AvailabilityChanged` |
| E06 | Disponibilidade, calendário e cotação | #concluida | E04, E05 | Calendário com preços e restrições; cotação por período, ocupação e canal |
| E07 | Criação de reservas | #concluida | E06 | `Booking` e `Reservation` com preço congelado, política vigente, conta em `billing`, `ReservationCreated`, `GuestsMerged` e portas do `guests` |
| E08 | Alteração, cancelamento e reativação | #concluida | E07 | Recálculo e histórico; multa pela `BillingApi` ou isenção sensível; reativação sensível |
| E09 | Pré-reserva, garantia e expiração | #concluida | E07 | Garantia manual com depósito conferido no `billing`, confirmação e job de expiração |
| E10 | Alocação de UH | #concluida | E07 | Alocação com `EXCLUDE`, sugestão, "não mover", OOS como última opção e alerta de realocação |
| E11 | Grupos e bloqueios | #pendente | E07 | `RoomBlock`, rooming list, pick-up, conta master e regra de data de corte |
| E12 | Busca, listas operacionais e views de leitura | #pendente | E08, E09, E10, E11 | Busca de reservas, chegadas, saídas, hospedados, no-shows previstos e views `report_*` |
| E13 | Gate do marco M4 | #pendente | E12 | Definição global de pronto verificada numa cópia limpa e num compose isolado |

---

## E01 — Planos tarifários, políticas e pacotes — #concluida

**Objetivo:** criar o módulo `rates` com planos tarifários (código, nome, descrição, regime de alimentação, canais, tipos de UH, categoria normal/cortesia/uso da casa, políticas, ativo), políticas de cancelamento e de garantia, pacotes ligados a códigos de lançamento e o catálogo de permissões do módulo, inclusive a do desconto (RF-TAR-01, RF-TAR-06, RF-TAR-07, H7).

**Por que agora:** é a base de preço, restrição, cotação e reserva. A categoria do plano alimenta a regra de Q-15 nos indicadores de M6/M10.

**Dependências e paralelismo:** nenhuma dentro deste roadmap; exige o gate de M3 `#concluida`. Pode correr em paralelo com E05 (em worktree separado); conflito de edição em `api/openapi.yaml`, `CLAUDE.md`, `README.md` e `ModularityTest`.

**Escopo:**

- Pacote `com.zeny.hotel.rates` (a criar) com `RatesModule.kt` (`allowedDependencies = ["shared", "rooms", "billing"]`), schema e pasta de migração `rates` com a migração de `GRANT`, API pública na raiz (`RatesApi`, ids, `RatesErrorCodes`, `RatesPermissions`) e um teste novo no `ModularityTest`.
- `RatePlan` com tipos de UH validados pela `RoomsApi.roomType(id)` (inexistente `400 UNKNOWN_ROOM_TYPE` com o campo, desativado `422 ROOM_TYPE_INACTIVE`, só no vínculo novo), canais, regime de alimentação e categoria (`NORMAL`, `COURTESY`, `HOUSE_USE`, Q-15); referência às políticas de cancelamento e de garantia.
- `CancellationPolicy` (prazo em horas antes da chegada, H15; multa em % da estadia, nº de diárias ou valor fixo em `Money`) e `GuaranteePolicy` (nenhuma, cartão, depósito de X% até N dias antes da chegada).
- `Package` com preço em `Money` por noite, por estadia ou por pessoa/noite, ritmo (diário ou na chegada), incluso ou adicional e código de lançamento validado pela `BillingApi.transactionCode(id)` (inexistente `400 UNKNOWN_TRANSACTION_CODE`, desativado `422 TRANSACTION_CODE_INACTIVE`).
- Endpoints em `/api/v1/rate-plans`, `/api/v1/cancellation-policies`, `/api/v1/guarantee-policies` e `/api/v1/packages` (lista por cursor, detalhe com `ETag`, criação 201 com `Location`, `PUT` com `If-Match`, `POST …/{id}/deactivate` e `/reactivate`; sem `DELETE`, com trigger contra a exclusão).
- Permissões (H16) no `RatesPermissions` publicado como bean, inclusive a do desconto (H7: sensível, `LimitDefinition("maxPercent", LimitUnit.PERCENT, …)`, Gerente sem teto e Recepcionista com teto de 10% no modelo); auditoria `rates.<entidade>.<verbo>` com retratos sem texto livre.

**Fora de escopo:** preços e derivação (E02); restrições (E03); a aplicação do desconto (E07).

**Passos de implementação:**

1. Conferir no código a `RoomsApi` (tipos de UH) e a `BillingApi` (códigos de lançamento) citadas no "Contexto confirmado".
2. Testes de integração: cadastro dos quatro recursos com `ETag`/`If-Match`, tipo de UH e código de lançamento inexistentes ou desativados, permissões, nenhum `DELETE`, permissão do desconto no catálogo.
3. Implementar migração, domínio, casos de uso, controllers e permissões; regenerar o contrato; atualizar `CLAUDE.md`, `README.md` e o `ModularityTest`.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "o que SPEC §5.6 (RF-TAR-01, 06, 07), §8.2.5, RN-11, RN-15 e Q-15 definem para plano, políticas e pacotes?".
- Partes paralelas: nenhuma.
- Foco do verificador: `rates` acessando tabelas de `rooms` ou `billing` em vez da API pública; pacote sem código de lançamento; política editada sem `If-Match`; exclusão física possível; permissão do desconto sem o limite `maxPercent`; escrita sem auditoria.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E01 — "Planos tarifários, políticas e pacotes" do roadmap docs/roadmaps/roadmap-m4-tarifas-reservas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Contexto confirmado", restrições, as hipóteses H7, H15 e H16 e o "Protocolo de execução" —, a seção E01 inteira e docs/SPEC.md §5.6 e §8.2.5. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que a etapa E11 (gate) de docs/roadmaps/roadmap-m3-hospedes-contas.md está #concluida e que a RoomsApi e a BillingApi batem com o "Contexto confirmado"; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar o módulo rates (RatesModule com shared, rooms e billing) com planos tarifários (regime de alimentação, canais, tipos de UH, categoria normal/cortesia/uso da casa, políticas, ativo), políticas de cancelamento e de garantia e pacotes ligados a códigos de lançamento, com endpoints em /api/v1/rate-plans, /api/v1/cancellation-policies, /api/v1/guarantee-policies e /api/v1/packages (cursor, ETag/If-Match, desativação por ação, sem DELETE), o catálogo de permissões (inclusive a do desconto com o limite maxPercent, H7) e auditoria. Use só a RoomsApi e a BillingApi. Não implemente preços, derivação nem restrições.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E01 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume SPEC RF-TAR-01, 06 e 07, §8.2.5, RN-11, RN-15 e Q-15;
- escreva os testes de integração primeiro, implemente, acrescente o teste do rates ao ModularityTest, atualize CLAUDE.md e README.md, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em acesso a tabelas de outros módulos, pacote sem código de lançamento, exclusão física e escrita sem auditoria.

Critérios de aceite (detalhes na seção E01):
- cadastro dos quatro recursos com ETag/If-Match e sem DELETE, coberto por testes de integração;
- tipo de UH ou código de lançamento inexistente → 400 com code e campo; desativado → 422;
- escrita sem permissão → 403; toda escrita auditada; permissão do desconto com maxPercent no catálogo;
- ModularityTest com o rates; build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E01 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências, incluindo os pontos para a entrevista da E01. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Cadastro de planos, políticas e pacotes com `ETag`/`If-Match`, desativação e reativação, coberto por testes de integração com Testcontainers; nenhum `DELETE` e exclusão recusada pelo banco.
- [ ] Plano com tipo de UH inexistente ou pacote com código de lançamento inexistente → `400` com `code` estável e o campo; desativado → `422`.
- [ ] Escrita sem permissão → `403`; toda escrita auditada, sem texto livre no retrato.
- [ ] Permissão do desconto registrada no catálogo, sensível, com o limite `maxPercent` (`PERCENT`) e os modelos de role da H7.
- [ ] `ModularityTest` com um teste do `rates` (só `shared`, `rooms`, `billing`); contrato regenerado; `./gradlew build` e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Edição de política usada por reservas existentes: a reserva copia a política vigente (E07, RN-15); registrar no handoff.

**Evidências e handoff:**

- Snapshot inicial: `9e46946e3e720ec0e8e90ead5e8a3e4448b4801f` (recebido do orquestrador). Pré-requisitos conferidos: E11 do M3 `#concluida`; `RoomsApi.roomType(id)` (`RoomTypeSummary.active`) e `BillingApi.transactionCode(id)` (`TransactionCodeSummary.kind`/`active`) batem com o "Contexto confirmado"; a aresta `rates` → `shared`, `rooms`, `billing` já estava na ARCHITECTURE §3.1.
- Arquivos alterados:
  - novos: `src/main/kotlin/com/zeny/hotel/rates/` (`RatesModule.kt`, `RatesApi.kt` com ids, enums e resumos, `RatesErrorCodes.kt`, `RatesPermissions.kt`; `domain/RatesRecord.kt`, `Policies.kt`, `RatePackage.kt`, `RatePlan.kt`; `application/RatesPorts.kt`, `RatesAudit.kt`, `PolicyServices.kt`, `PackageService.kt`, `RatePlanService.kt`, `RatesApiService.kt`; `infrastructure/RatesPersistence.kt`, `JdbcRatesDirectory.kt`; `web/RatesDtos.kt`, `RatesControllers.kt`), `src/main/resources/db/migration/rates/V2026_10_20_1740__create_rates.sql` e `V2026_10_20_1741__grant_rates_to_app_role.sql`, `src/test/kotlin/com/zeny/hotel/rates/RatesApiTest.kt` e `RatesTestSupport.kt`;
  - alterados: `src/main/kotlin/com/zeny/hotel/shared/web/OpenApiConfiguration.kt` (`@ExtensibleEnum` também numa lista de `enum`, nos itens), `src/test/kotlin/com/zeny/hotel/shared/web/ExtensibleEnumContractTest.kt` (confere a lista), `src/test/kotlin/com/zeny/hotel/ModularityTest.kt` (teste do `rates`), `src/test/kotlin/com/zeny/hotel/iam/AuthorizationTest.kt` (o modelo Gerente com as permissões do `rates`), `api/openapi.yaml` (só acréscimos: 16 caminhos e 13 schemas do `rates`; a ordem das tags mudou), `CLAUDE.md` (estado atual, seção do módulo, convenção do `x-extensible-enum` em lista), `README.md` (API e árvore de módulos) e este roadmap.
- Decisões:
  - **do usuário (entrevista da E01, 2026-10-20, todas pela opção recomendada):** canais `SalesChannel` fixo `DIRECT`/`WEBSITE`/`OTA` com `x-extensible-enum` nos itens da resposta (a OTA específica é a origem da reserva, M8); regime `MealPlan` `ROOM_ONLY`/`BREAKFAST`/`HALF_BOARD` com `x-extensible-enum`; políticas de cancelamento e garantia **não versionadas** (a edição com `If-Match` vale na hora para as reservas criadas depois; a reserva copia os termos na criação, RN-15, E07). H7 aplicada: `rates.discounts.apply` sensível, limite `maxPercent` (`PERCENT`), Gerente sem teto e Recepcionista 10% no modelo.
  - **do implementador (pontos para a entrevista, todas pela opção mais conservadora):** (1) categoria do plano **fixa desde a criação** (como o tipo do código de lançamento; o `PUT` não a recebe); (2) política ou pacote em uso por plano **ativo** não é desativado (`422 …_IN_USE`) e o plano só é reativado com as políticas e os pacotes ativos — invariante "plano ativo nunca tem política ou pacote desativado", com trava de linha (o tipo de UH desativado no `rooms` continua no plano: o `rooms` não o impede, a E04 o descarta); (3) pacote só num código de lançamento de consumo `CHARGE` (`422 TRANSACTION_CODE_KIND_NOT_ALLOWED`); (4) preço por estadia (`PER_STAY`) só com o ritmo `ON_ARRIVAL` (`400 PACKAGE_INVALID`, também em `CHECK`); (5) preço do pacote de 0,01 a 1.000.000,00 (incluso também tem preço, a parte da diária); (6) nome das políticas único sem diferenciar maiúsculas (`409 …_NAME_ALREADY_IN_USE`); multa zero permitida (política sem multa); (7) `penaltyNights` até 365 e `deadlineHours` até 8760; depósito de mais de 0 a 100% e prazo de 0 a 365 dias; (8) plano com ao menos um canal e um tipo de UH, até 50 tipos e 20 pacotes; (9) `GuaranteeType` com `x-extensible-enum` na resposta (o M7 integra cartão/Pix); `category`, `penaltyType`, `pricing` e `postingRhythm` ficam `enum` fechados; (10) os códigos de erro do tipo de UH e do código de lançamento são os dos módulos donos (`UNKNOWN_ROOM_TYPE`, `ROOM_TYPE_INACTIVE`, `UNKNOWN_TRANSACTION_CODE`, `TRANSACTION_CODE_INACTIVE`), reexportados em `RatesErrorCodes`; (11) só as permissões usadas agora entram no catálogo (`rates.plans.read`, `rates.plans.manage`, `rates.discounts.apply`); `rates.prices.*` e `rates.restrictions.manage` ficam para E02/E03; (12) o vínculo plano → pacotes (SPEC §8.2.5) entrou no plano já nesta etapa (`packageIds`), porque nenhuma etapa seguinte o previa.
- Validações executadas:
  - `./gradlew test --tests 'com.zeny.hotel.rates.*'` → 17 testes, 0 falhas (`RatesApiTest`); `ModularityTest` (15), `ExtensibleEnumContractTest`, `LayerArchitectureTest`, `MigrationConventionTest`, `DatabaseRolesTest` e `DatabaseMigrationsTest` passaram;
  - verificação de que o teste da corrida detecta o erro: trocando `cancellationPolicyForUpdate` por `cancellationPolicy` (sem trava) em `RatePlanService`, o teste "deactivating a policy while a plan links it…" falhou; o código foi restaurado;
  - `./gradlew updateOpenApi` → contrato regenerado; diff semântico só com acréscimos;
  - `.github/scripts/check-api-breaking.sh` → "Nenhuma mudança incompatível (nível ERR) em /api/v1";
  - `./gradlew lint` → sucesso;
  - `./gradlew build` → BUILD SUCCESSFUL (881 testes, 0 falhas; detekt, ktlint, `checkOpenApi` e `koverVerify`). As duas primeiras rodadas falharam e foram corrigidas: detekt (linhas longas, `ThrowsCount`, `TooManyFunctions` e testes longos, divididos) e o `AuthorizationTest` (o modelo Gerente passou a ter as três permissões do `rates`; a expectativa foi atualizada).
- Parecer do verificador: **APROVADO** (verificador independente, 2026-10-20; snapshot de verificação `7daf6d6e2dd2d2fb05a715362432d0cdac643c36`): os cinco critérios aprovados com evidência (`RatesApiTest` 17/17, `ModularityTest` 15/15, `ExtensibleEnumContractTest` 2/2, `./gradlew build` com 881 testes e 0 falhas, oasdiff sem `ERR`, contrato só com acréscimos); nenhum acesso a tabelas de `rooms`/`billing`, pacote sem código barrado no DTO, no banco e por SQL direto, exclusão recusada pelo trigger nas quatro tabelas, toda escrita auditada, a extensão de `@ExtensibleEnum` para lista correta e coberta. Problemas menores, sem impedir o aceite: 403 não testado no `PUT`/`reactivate` de pacote e de garantia (o código confere); sem teste do 409 de nome/código repetido numa edição; `version` só no `ETag`, não no corpo; o teste do `x-extensible-enum` trata só `List` (não `Set`); descrição de `channels` repetida em `items`; ordem das tags do contrato mudou.
- Entrevista dos pontos do implementador (usuário, 2026-10-20): todos aceitos pela opção recomendada, que é a implementada — categoria fixa; política/pacote em uso por plano ativo não se desativa (`422 …_IN_USE`); `GuaranteeType` extensível e os demais `enum` fechados; vínculo plano → pacotes já na E01; pacote só em código `CHARGE`; `PER_STAY` só com `ON_ARRIVAL` e preço ≥ 0,01; nome de política único e multa zero permitida; permissões de preços e restrições declaradas na etapa que as usa (limites como implementados).
- Notas para a próxima etapa:
  - E02: acrescentar `rates` → `hotel` no `RatesModule`, no `ModularityTest` (o teste do `rates` lista as dependências exatas) e na ARCHITECTURE §3.1; as permissões de preço (`rates.prices.manage`, `rates.prices.override` sensível) vão no `RatesPermissions`; a migração nova precisa de timestamp posterior a `V2026_10_20_1741`. `RatePlan` é JPA com coleções `@ElementCollection` (`@BatchSize`); a derivação (plano base) é um campo novo do plano.
  - E04: a vendabilidade descarta tipo de UH desativado no `rooms` (pode estar no plano) e pacote nunca desativado num plano ativo; o preço por estadia é sempre `ON_ARRIVAL`.
  - E07: a reserva copia os termos das políticas pela `RatesApi.cancellationPolicy`/`guaranteePolicy` (com a `version`) e confere o desconto com `RatesPermissions.DISCOUNTS_APPLY` e `LimitAmount(RatesPermissions.MAX_PERCENT, desconto)`.
  - `@field:ExtensibleEnum` agora vale numa `List<E>` (itens); o `ExtensibleEnumContractTest` cobre os dois casos.

---

## E02 — Preços por data e ocupação, derivação e edição em massa — #concluida

**Objetivo:** manter preços por tipo de UH × data × ocupação (1 adulto, 2 adultos, adulto extra, criança por faixa etária do hotel), planos derivados por percentual ou valor (por UH ou por pessoa), edição em massa por período e dia da semana com temporadas nomeadas, e alteração de tarifa publicada como ação sensível, publicando `RatesChanged` (RF-TAR-02, 03, 04; RF-ACS-08; H1, H2, H9).

**Por que agora:** cotação e reservas dependem do preço; a regra de derivação e o arredondamento definidos aqui se repetem em todo cálculo de diária.

**Dependências e paralelismo:** E01. Pode correr em paralelo com E03 (em worktree separado); conflito de edição no agregado `RatePlan`, em `api/openapi.yaml`, `CLAUDE.md` e `ModularityTest` — preferir executar em sequência.

**Escopo:**

- `RatePrice` por plano, tipo de UH e data, com os valores em `Money` (1 adulto, 2 adultos, adulto extra e um valor por faixa de `HotelApi.policy().childAgeBands`, ou um valor único de criança sem faixas; H2). Aresta `rates` → `hotel` no `RatesModule`, na ARCHITECTURE §3.1 e no `ModularityTest`.
- `DerivationRule` (percentual com `Percent` ou valor com `Money`, por UH ou por pessoa) com plano base; cálculo na leitura (H1) pela regra única de `Money`; derivação em ciclo `422` com `code` estável.
- `Season` como atalho de período para edição em massa; edição em massa por período e dias da semana em lote (SQL em bloco).
- "Tarifa publicada" (H9): alterar ou retirar exige a permissão sensível de preços publicados por `authorization.requireOrApproval(SensitiveAction(ação, permissão, "rates.rate-plan:" + id do plano))`, auditada com a aprovação; gravar onde não havia ou em plano inativo usa a permissão comum.
- Evento `RatesChanged` (`rates/RatesEvents.kt`: plano, tipos e intervalo de datas, incluindo os planos derivados afetados; sem autor) pelo outbox na transação; teste com `@EnableScenarios` e um listener de teste (sem consumidor de produção até o M8).

**Fora de escopo:** restrições (E03); cálculo de estadia com pacotes (E04).

**Passos de implementação:**

1. Testes de domínio (derivação percentual e por pessoa, arredondamento, ciclo) e de integração (edição em massa por dia da semana, faixas do hotel, step-up com `IamTestSupport.setPin` e sessão fixa, evento pelo `Scenario`).
2. Implementar migração, domínio, casos de uso e endpoints; regenerar o contrato; documentar no `CLAUDE.md`.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação: nenhuma; regras em RF-TAR-02 a 04, H1, H2, H9 e no handoff de E01.
- Partes paralelas: nenhuma.
- Foco do verificador: arredondamento fora de `Money`; derivação em ciclo aceita; edição em massa que ignora o filtro de dia da semana; alteração de tarifa publicada sem step-up (ou criação de preço novo exigindo-o); evento sem os planos derivados; teste do evento que passaria sem listener de teste.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E02 — "Preços por data e ocupação, derivação e edição em massa" do roadmap docs/roadmaps/roadmap-m4-tarifas-reservas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo as hipóteses H1, H2, H9 e H16 e o "Protocolo de execução" —, a seção E02 inteira, o "Evidências e handoff" de E01 e RF-TAR-02 a RF-TAR-04 em docs/SPEC.md §5.6. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 está #concluida e que o código bate com o handoff dela, e que a H9 já foi decidida (se ainda disser "a confirmar pelo usuário", pare e peça a decisão); se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: em rates, manter preços por tipo de UH, data e ocupação (criança por faixa etária da HotelApi.policy, com a aresta rates → hotel), planos derivados por percentual ou valor (por UH ou por pessoa) calculados na leitura, temporadas e edição em massa por período e dia da semana, e tratar a alteração de tarifa publicada como ação sensível (requireOrApproval), publicando RatesChanged com os planos derivados afetados. Não implemente restrições nem cálculo de estadia.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E02 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de domínio e de integração primeiro (evento com @EnableScenarios e listener de teste), implemente, atualize a ARCHITECTURE §3.1, o ModularityTest, CLAUDE.md e README.md, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em arredondamento fora de Money, derivação em ciclo, filtro de dia da semana ignorado, tarifa publicada sem step-up e evento incompleto.

Critérios de aceite (detalhes na seção E02):
- preço derivado acompanha a mudança do plano base sem gravação adicional;
- derivação em ciclo → 422 com code estável;
- edição em massa respeita período e dias da semana;
- alterar tarifa publicada sem permissão nem aprovação → 403 APPROVAL_REQUIRED; RatesChanged no outbox com derivados; build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E02 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Preço de plano derivado (−10%, + R$ 40/pessoa) calculado pela regra de `Money` e acompanha a mudança do plano base sem gravação no derivado (testes de domínio e integração).
- [ ] Derivação que formaria ciclo → `422` com `code` estável.
- [ ] Preço de criança por faixa de `HotelApi.policy().childAgeBands` (e valor único sem faixas); aresta `rates` → `hotel` na ARCHITECTURE §3.1 e no `ModularityTest`.
- [ ] Edição em massa por período e dias da semana altera só as datas filtradas; temporada aplicada como atalho.
- [ ] Alterar tarifa publicada sem permissão nem aprovação → `403` com `APPROVAL_REQUIRED`; com aprovação, auditada com solicitante e aprovador; gravar preço novo não pede aprovação (H9).
- [ ] `RatesChanged` registrado no outbox (com listener de teste) com plano, tipos, intervalo e planos derivados afetados.
- [ ] Contrato regenerado; `./gradlew build` e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Volume de linhas de preço (planos × tipos × 730 dias): edição em massa em lote único com SQL e teste de desempenho simples.
- Faixas do hotel alteradas depois de gravar preços: decisão da entrevista da E02, registrada no handoff.

**Evidências e handoff:**

- Snapshot inicial: `b62e23a2f4017ddf0dfc82bb616b1a95888c8f67` (recebido do orquestrador). Pré-requisitos conferidos: E01 `#concluida` (commit `b138fc9`) e o código bate com o handoff dela (`RatesModule` com `shared`, `rooms`, `billing`; `RatePlan` JPA com as coleções; `RatesPermissions` com as três permissões); H9 decidida pelo usuário em 2026-10-20 (P2 da sincronização).
- Arquivos alterados:
  - novos: `src/main/kotlin/com/zeny/hotel/rates/RatesEvents.kt` (`RatesChanged`, `RatesChangeKind`); `domain/RatePrices.kt` (`PriceSet`, `ChildPrice`, `AgeBand`, `ChildBands`, `PriceAdjustment`, `DerivationRule`, `Derivations`, `PriceSelection`, `PriceKey`, `PriceChanges`) e `domain/Season.kt`; `application/RatePricePorts.kt` (`RatePriceStore`, comandos e visões), `RatePriceReader.kt`, `RatePriceService.kt`, `RatePlanDerivationService.kt`, `SeasonService.kt` (com `PriceSelections`); `infrastructure/JdbcRatePrices.kt`; `web/RatePricesWeb.kt` (DTOs, `RatePlanPricesController` e `SeasonsController`); `src/main/resources/db/migration/rates/V2026_10_20_1835__add_rate_prices.sql`; testes `src/test/kotlin/com/zeny/hotel/rates/RatePriceDomainTest.kt`, `RatePricesApiTest.kt`, `RatePlanDerivationApiTest.kt` e `RatePricesTestSupport.kt` (com o listener de teste `RatesChangedProbeListener`);
  - alterados: `rates/RatesModule.kt` (aresta `hotel`), `RatesApi.kt` (`SeasonId`, `RateAdjustmentType`, `RatePlanDerivation`, `RatePlanSummary.derivation`), `RatesErrorCodes.kt`, `RatesPermissions.kt` (`rates.prices.manage`, `rates.prices.override`), `domain/RatePlan.kt` (colunas e `derive`), `application/RatesPorts.kt`, `RatesAudit.kt`, `RatesApiService.kt`, `infrastructure/RatesPersistence.kt` (temporadas, `ratePlanForUpdate`), `JdbcRatesDirectory.kt`, `web/RatesDtos.kt` (`derivation` na resposta do plano); `src/test/kotlin/com/zeny/hotel/ModularityTest.kt` (o `rates` com `hotel`), `iam/AuthorizationTest.kt` (o modelo Gerente com as duas permissões novas), `rates/RatesApiTest.kt` (catálogo e mapeamentos novos), `rates/RatesTestSupport.kt` (`createPlanFor`); `api/openapi.yaml` (só acréscimos: 9 caminhos de preços, derivação e temporadas, `derivation` na resposta do plano); `docs/ARCHITECTURE.md` §3.1 (`rates` → `hotel`); `CLAUDE.md` (estado atual e a seção "Preços, derivação e temporadas"); `README.md` (API e árvore); este roadmap.
- Decisões:
  - **do usuário (entrevista da E02, 2026-10-20, ambas pela opção recomendada):** (1) faixas etárias que mudam no hotel: o preço guarda a faixa (idades) com que foi gravado; a faixa que não existe mais nas `childAgeBands` atuais é ignorada e a data fica sem preço de criança naquela faixa (não vendável para criança dessa idade, regra que a E04 aplica) até o gestor regravar; a leitura sinaliza a faixa obsoleta (`obsoleteChildren`); nada é recalculado em silêncio, reservas já feitas não mudam (RN-14) e nunca cai no preço de adulto extra; (2) edição em massa de até 366 dias por requisição (acima, `400 RATE_PRICE_PERIOD_TOO_LONG` no campo `to`); períodos maiores em duas chamadas. H9 aplicada como decidida na sincronização.
  - **do implementador (pontos para a entrevista; todas pela opção mais conservadora ou recomendada):** (1) derivação por valor **por pessoa** soma uma vez por pessoa que o valor cobre (1 no de um adulto, 2 no de dois, 1 no adulto extra, 1 em cada faixa de criança); **por UH**, só nas diárias de 1 e 2 adultos; (2) ~~um valor derivado negativo vale zero (piso)~~ — **decidida pelo usuário na revisão humana (2026-10-20): ocupação sem preço** (ver abaixo); (3) derivação em cadeia permitida até 5 níveis na árvore (`422 RATE_PLAN_DERIVATION_TOO_DEEP`); (4) o plano base pode estar inativo (tarifa de referência não vendida); (5) **tarifa publicada** inclui os preços de um plano inativo que é base, direta ou indireta, de um plano ativo (os preços dele aparecem no derivado); (6) trocar ou retirar a derivação de um plano publicado é a mesma ação sensível (`rates.prices.override`); a primeira derivação de um plano sem preços não pede aprovação; (7) tornar derivado um plano com preços próprios a partir da data operacional é `422 RATE_PLAN_HAS_PRICES` (retire antes), e o derivado nunca grava preços (`422 RATE_PLAN_DERIVED`); (8) o passado não se edita: período explícito que começa antes da data operacional é `422 RATE_PRICE_PERIOD_IN_PAST`; a temporada é cortada na data operacional; (9) `oneAdult` obrigatório, `twoAdults` e `extraAdult` opcionais (nulo = ocupação sem preço), todos de 0,00 a 1.000.000,00 (zero aceito, para cortesia); a faixa de criança omitida fica sem preço; (10) a edição em massa troca o conjunto inteiro de cada data (sem edição parcial de campos), sem `If-Match` (define o valor) e sem `@Idempotent`; (11) a retirada apaga a linha (`DELETE` auditado), não é *soft delete*; (12) temporadas globais (não por plano), nome único, de 1 a 366 dias, mantidas com `rates.prices.manage`; os preços são lidos com `rates.plans.read` (sem `rates.prices.read`); (13) `RatesChanged` da derivação vale da data operacional sem fim (`to` nulo), com os tipos do plano; `derivedRatePlanIds` lista os derivados ativos e inativos; (14) os tipos de UH de um derivado não precisam estar no base (o tipo que o base não tem fica sem preço no derivado); (15) duas travas: a consultiva da árvore de derivação (exclusiva nas escritas de derivação, compartilhada nas de preços) e a linha do plano (`SELECT … FOR UPDATE`) em toda escrita de preço ou derivação.
- Revisão humana (usuário, 2026-10-20; verificador independente: APROVADO): das 15 decisões do implementador, 14 (a 1 e da 3 à 15) **aceitas pelo usuário na revisão humana em 2026-10-20** como implementadas. A decisão 2 mudou: **decidida pelo usuário na revisão humana: ocupação sem preço** — quando o ajuste da derivação dá valor menor ou igual a zero, aquela ocupação e data fica **sem preço** (não vendável, como uma ocupação sem preço gravado), e a leitura sinaliza (`unpricedByDerivation` em `GET …/prices`, com `oneAdult`, `twoAdults`, `extraAdult` e as faixas de criança; `oneAdult` nulo quando ele é a lacuna); a regra vale nas outras datas; o valor que o base já tinha em 0,00 (cortesia gravada) continua 0,00 sob percentual (um `AMOUNT` negativo sobre ele deixa a ocupação sem preço); na cadeia, a lacuna segue. Motivo: zero é preço vendável e poria à venda uma diária grátis sem aviso; a cortesia é um plano `COURTESY` com preço explícito. Ajuste aplicado em `domain/RatePrices.kt` (`PriceSet.derive`, `PriceSet.withoutPrice`, `DerivationGaps`; `oneAdult` passou a anulável só no resultado derivado), `application/RatePricePorts.kt` (`UnpricedByDerivation`, `AgeRange`, `DailyPriceView.oneAdult` anulável), `RatePriceService.read`, `web/RatePricesWeb.kt` (`unpricedByDerivation`, `oneAdult` anulável na resposta; só endpoints novos desta etapa), `RatesAudit` e `JdbcRatePrices`; testes novos `RatePriceDomainTest` "a derived value of zero or less leaves the occupancy without price, never a free rate" e `RatePlanDerivationApiTest` "a derived rate of zero or less leaves that occupancy and date without price, flagged on read", que falham com o comportamento antigo (conferido trocando a regra pelo piso em zero: os dois falham; restaurado).
- Validações executadas:
  - testes primeiro: `RatePriceDomainTest` (11), `RatePricesApiTest` e `RatePlanDerivationApiTest`; `./gradlew test --tests 'com.zeny.hotel.rates.*'` → 47 testes, 0 falhas;
  - mutações conferidas (cada uma restaurada depois): sem o filtro de dia da semana (`PriceSelection.dates`) → 4 testes falham (domínio e API); sem o step-up dos preços (`authorizeOverride`) → 2 falham; sem a conferência de ciclo (`Derivations.requireValid`) → 4 falham; sem os derivados no evento → o teste do `Scenario` falha; sem o step-up da derivação → 1 falha; sem a trava exclusiva da derivação → "two opposite derivations at the same time never form a cycle" falha;
  - `ModularityTest`, `AuthorizationTest`, `LayerArchitectureTest`, `MigrationConventionTest`, `DatabaseRolesTest`, `DatabaseMigrationsTest` e `ApiSecurityTest` passaram;
  - `./gradlew updateOpenApi` → contrato regenerado (só acréscimos; o resto do diff é reordenação);
  - `.github/scripts/check-api-breaking.sh` (contra `origin/main`) e `.github/scripts/check-api-breaking.sh HEAD` (contra a E01) → "Nenhuma mudança incompatível (nível ERR) em /api/v1";
  - ajustes de texto pedidos na 2ª verificação (0,00 do base mantido só sob percentual; 15 decisões, 14 aceitas e a 2 mudada; nota da E04 sobre `oneAdult` nulo no derivado): `./gradlew updateOpenApi`, `./gradlew checkOpenApi lint` → BUILD SUCCESSFUL; `./gradlew test --tests 'com.zeny.hotel.rates.*' --tests 'com.zeny.hotel.shared.*'` → 194 testes, 0 falhas; oasdiff sem `ERR`;
  - depois do ajuste da revisão humana: `./gradlew test --tests 'com.zeny.hotel.rates.*'` → 49 testes, 0 falhas; `./gradlew updateOpenApi` (a resposta de `GET …/prices` ganhou `unpricedByDerivation` e `oneAdult` anulável); `./gradlew build` → BUILD SUCCESSFUL (913 testes, 0 falhas); `.github/scripts/check-api-breaking.sh` e `… HEAD` → nenhuma mudança `ERR`;
  - `./gradlew build` → BUILD SUCCESSFUL (911 testes, 0 falhas; detekt, ktlint, `checkOpenApi` e `koverVerify`). A primeira rodada falhou no detekt (linhas longas, `ReturnCount`, `ThrowsCount`, `ComplexCondition`, `LongParameterList`, `LongMethod` em dois testes, `NoNameShadowing`), corrigido.
- Parecer do verificador: **APROVADO** em duas verificações independentes (2026-10-20). A primeira (snapshot `376d7859284bb499ae6400f2724bf33580b060a9`) aprovou os sete critérios e levou à revisão humana a decisão 2 (valor derivado negativo virando 0,00, uma diária grátis vendável) e as ampliações do step-up (decisões 5 e 6). Depois da mudança da decisão 2 pelo usuário, a segunda (snapshot `4dd498268839e50942ef9260398841d6b68704f6`) aprovou de novo, com `./gradlew build` (913 testes, 0 falhas) e oasdiff sem `ERR` contra `origin/main` e contra `HEAD`, a regra "≤ 0 fica sem preço" no domínio, na leitura (`unpricedByDerivation`), na cadeia e nos testes que falhariam com o piso em zero. Ela não apontou problema grave nem médio; os três menores de texto (0,00 só sob percentual, a contagem das decisões e `oneAdult` nulo na nota da E04) foram corrigidos depois, com `checkOpenApi`, `lint` e os testes do `rates`/`shared` (194, 0 falhas) verdes. Restam, como informativos: a aprovação vincula o plano e não as datas (regra do M1 E05); uma reativação simultânea de um derivado pode, numa janela de milissegundos, deixar uma alteração passar sem step-up; a leitura do passado de um derivado usa a regra de hoje (inofensivo pela RN-14); e uma faixa obsoleta que vai a ≤ 0 aparece em `unpricedByDerivation` e não em `obsoleteChildren` (cosmético).
- Notas para a próxima etapa:
  - E03 (restrições): a migração nova precisa de timestamp posterior a `V2026_10_20_1835`; a `PriceSelection` (período até 366 dias, `weekdays`, `startingAt`) e as `PriceSelections` (período **ou** temporada) servem à edição em massa de restrições; `RatesChanged` é o modelo do `RestrictionsChanged`; a trava da linha do plano (`RatesStore.ratePlanForUpdate`) serializa as escritas por plano. As restrições valem também para planos derivados (a derivação é só de preço; decidir na E03).
  - E04 (cálculo): use o `RatePriceReader.effectivePrices(plan, types, from, to)` (aplica a cadeia de derivação) e o `currentChildBands()`; criança sem preço na faixa atual dela (ou só com preço obsoleto) torna a ocupação não vendável; `twoAdults`/`extraAdult` nulos também; o adulto extra é por adulto além do segundo. Num plano derivado, `oneAdult` (e os demais valores) pode vir nulo quando o ajuste o deixou sem preço (`PriceSet.withoutPrice`, `DerivationGaps`; na API, `unpricedByDerivation`): a E04 trata essa ocupação como não vendável naquela data, como uma sem preço gravado.
  - A `RatesApi` ainda não expõe preços (só `RatePlanSummary.derivation`); `priceFor` entra na E04.

---

## E03 — Restrições de venda — #concluida

**Objetivo:** manter restrições por data × plano × tipo de UH (fechado para venda, CTA, CTD, estadia mínima e máxima, antecedência mínima e máxima), com edição em massa e evento `RestrictionsChanged` (RF-TAR-05).

**Por que agora:** a vendabilidade (E04) e a cotação (E06) dependem das restrições.

**Dependências e paralelismo:** E01. Pode correr em paralelo com E02 (em worktree separado); conflito de edição no agregado `RatePlan`, em `api/openapi.yaml` e `CLAUDE.md` — preferir executar em sequência.

**Escopo:**

- `Restriction` por plano, data e tipo de UH opcional (vale para todos os tipos do plano quando ausente), com precedência documentada entre a restrição específica do tipo e a geral, e a semântica de CTA, CTD e estadia mínima registrada para o M8 (decisão da entrevista da E03; antecedência pela data operacional, H15).
- Edição em massa por período e dias da semana.
- Evento `RestrictionsChanged` (`rates/RatesEvents.kt`: plano, tipos e intervalo) pelo outbox; teste com listener de teste.
- Permissões (H16) e auditoria.

**Fora de escopo:** avaliação de uma estadia contra as restrições (E04).

**Passos de implementação:**

1. Testes de integração: gravação, precedência tipo × geral, valores inválidos, edição em massa, evento.
2. Implementar migração, domínio, casos de uso e endpoints; regenerar o contrato; documentar no `CLAUDE.md`.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regra em RF-TAR-05.
- Partes paralelas: nenhuma.
- Foco do verificador: precedência ambígua entre restrição por tipo e geral; valores inválidos aceitos (mínimo maior que máximo, negativos); evento ausente na edição em massa.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E03 — "Restrições de venda" do roadmap docs/roadmaps/roadmap-m4-tarifas-reservas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo as hipóteses H15 e H16 e o "Protocolo de execução" —, a seção E03 inteira, o "Evidências e handoff" de E01 e RF-TAR-05 em docs/SPEC.md §5.6. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: em rates, manter restrições por data, plano e tipo de UH opcional (fechado, CTA, CTD, estadia mínima e máxima, antecedência mínima e máxima), com precedência documentada entre restrição por tipo e geral, a semântica de CTA, CTD e estadia mínima registrada para o marco M8, edição em massa por período e dias da semana e evento RestrictionsChanged. Não implemente a avaliação de uma estadia (E04).

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E03 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes primeiro (evento com @EnableScenarios e listener de teste), implemente, atualize CLAUDE.md e README.md, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em precedência ambígua, valores inválidos aceitos e evento ausente na edição em massa.

Critérios de aceite (detalhes na seção E03):
- restrição por tipo prevalece sobre a geral do plano (teste);
- mínimo maior que máximo → 400 com code estável;
- edição em massa respeita período e dias da semana e publica RestrictionsChanged;
- build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E03 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências, incluindo os pontos para a entrevista da E03. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Restrições gravadas e lidas por plano, data e tipo, com a precedência tipo > geral coberta por teste.
- [ ] Mínimo maior que máximo (estadia ou antecedência) ou valor negativo → `400` com `code` estável e o campo.
- [ ] Edição em massa altera só as datas e dias da semana filtrados.
- [ ] `RestrictionsChanged` registrado no outbox (com listener de teste) em gravação individual e em massa.
- [ ] Semântica de CTA, CTD e estadia mínima documentada no `CLAUDE.md`; escritas auditadas; contrato regenerado; `./gradlew build` e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Semântica de CTA/CTD e estadia mínima difere entre channel managers: documentar a semântica escolhida para M8 mapear.

**Evidências e handoff:**

- Snapshot inicial: `208b49616188f9c9004c1db9316a1b0899b56732` (recebido do orquestrador). Pré-requisitos conferidos: E01 `#concluida` (commit `b138fc9`) e E02 `#concluida` (commit `8a19a35`); o código bate com os handoffs (`RatesModule` com `shared`, `rooms`, `billing`, `hotel`; `RatesStore.ratePlanForUpdate`; `PriceSelection`/`PriceSelections` com o teto de 366 dias e a temporada; `RatesChanged` com `RatesChangedProbeListener`; a última migração era `V2026_10_20_1835`).
- Arquivos alterados:
  - novos: `src/main/kotlin/com/zeny/hotel/rates/domain/Restrictions.kt` (`RestrictionKey`, `RestrictionValues`, `RestrictionPatch`, `EffectiveRestriction`, `RestrictionChanges`, `Restrictions`); `application/RestrictionPorts.kt` (`RestrictionStore`, `RestrictionData`, `RestrictionCommand`, visões) e `application/RestrictionService.kt` (com o `RestrictionReader`); `infrastructure/JdbcRestrictions.kt`; `web/RestrictionsWeb.kt` (DTOs e `RatePlanRestrictionsController`); `src/main/resources/db/migration/rates/V2026_10_20_2021__add_rate_restrictions.sql`; testes `src/test/kotlin/com/zeny/hotel/rates/RestrictionDomainTest.kt`, `RestrictionsApiTest.kt`, `RestrictionsEventsTest.kt` e `RestrictionsTestSupport.kt` (com o listener de teste `RestrictionsChangedProbeListener`);
  - alterados: `rates/RatesApi.kt` (`RestrictionField`, com a semântica no KDoc), `RatesEvents.kt` (`RestrictionsChanged`), `RatesErrorCodes.kt` (sete códigos `RESTRICTION_*`), `RatesPermissions.kt` (`rates.restrictions.manage`), `domain/RatePrices.kt` (`SelectionErrors`: os códigos da seleção de datas por assunto, preços ou restrições; `PriceSelection.of` com o parâmetro `errors`, padrão os dos preços), `application/SeasonService.kt` (`PriceSelections` genérica sobre `DateTarget`, mantendo os métodos dos preços), `application/RatePricePorts.kt` (`DateTarget`, `PriceTarget.dates`), `application/RatesAudit.kt` (entidade e ação das restrições, retrato); testes `RatePricesTestSupport.kt` (o `RatesChangedListenerConfiguration` passou a registrar também o listener de `RestrictionsChanged`, um contexto só), `RatesApiTest.kt` (catálogo e mapeamentos com as restrições) e `iam/AuthorizationTest.kt` (o modelo Gerente com `rates.restrictions.manage`); `api/openapi.yaml` (só acréscimos: 2 caminhos e os schemas das restrições); `CLAUDE.md` (estado atual, permissões do `rates` e a seção "Restrições de venda" com a semântica para o M8); `README.md` (API e árvore); este roadmap.
- Decisões:
  - **do usuário (entrevista da E03, 2026-10-20, todas pela opção recomendada):** (1) estadia mínima e máxima "on arrival" (as da data de chegada); (2) CTA e CTD só na data exata (chegada e saída; as noites do meio não), só o "fechado" vale em cada noite; (3) precedência campo a campo da restrição do tipo sobre a geral, com campos anuláveis e booleanos tri-estado (o `false` do tipo abre exceção). Antecedência pela data operacional até a chegada (H15), das restrições da data de chegada. H16 confirmada: `rates.restrictions.manage`.
  - **do implementador (pontos para a entrevista; todas pela opção mais conservadora ou recomendada):** (1) **edição parcial** (`set` com os campos não nulos e `clear` com os que voltam a "não informado"; os demais ficam) em vez de trocar a linha inteira como nos preços (E02): fechar uma data não apaga a estadia mínima dela; (2) **mínimo maior que o máximo nunca é gravado**: no pedido `400 RESTRICTION_RANGE_INVALID`; com o que já está gravado, na mesma linha ou na efetiva de um tipo (a do tipo sobre a geral, inclusive retirando um campo do tipo), `422 RESTRICTION_RANGE_CONFLICT` (alternativa: aceitar e deixar a data não vendável na E04); (3) **restrições de cada plano**: um derivado tem as próprias e não herda as do base (a derivação é só de preço; o `RestrictionsChanged` não lista derivados); vale também num plano inativo; (4) faixas: estadia de 1 a 365 noites, antecedência de 0 a 730 dias (o horizonte do inventário, H3; 0 na mínima serve de exceção do tipo); (5) códigos próprios da seleção de datas (`RESTRICTION_PERIOD_INVALID`, `…_TOO_LONG`, `RESTRICTION_SELECTION_INVALID`, `RESTRICTION_PERIOD_IN_PAST`) em vez dos `RATE_PRICE_*`, com o mesmo teto de 366 dias, a temporada como atalho e o passado não editável; (6) a linha sem nenhum campo sai (`DELETE` auditado), e a geral é uma linha com `room_type_id` nulo sob `UNIQUE NULLS NOT DISTINCT`; (7) `rates.restrictions.manage` **não sensível** e só do Gerente no modelo (a H9 vale só para a tarifa); leitura com `rates.plans.read`; (8) sem `If-Match` nem `@Idempotent` na edição em massa (define o valor, como os preços); (9) `RestrictionsChanged` com `general`, `roomTypeIds` (na geral, todos os tipos do plano) e `fields` (os campos que mudaram); (10) `RestrictionField` `enum` fechado (os sete da SPEC, estáveis); (11) a leitura lista só as datas com alguma linha gravada, com a geral, a do tipo e a efetiva (`fromRoomType`); o tipo que saiu do plano é ignorado (as linhas dele ficam).
- Validações executadas:
  - testes (escritos junto da implementação; a falha conferida por mutação, cada uma restaurada depois): precedência invertida (a geral sobre a do tipo) em `EffectiveRestriction.of` → 2 testes falham (domínio e API); sem a publicação do evento, sem a conferência de coerência e sem a trava da linha → 5 falham (os quatro do evento/corrida e o de `RESTRICTION_RANGE_CONFLICT`); só sem a trava da linha do plano (`ratePlan` em vez de `ratePlanForUpdate`) → "two simultaneous edits of the general and the type restriction…" falha;
  - `./gradlew test --tests 'com.zeny.hotel.rates.Restriction*'` → 21 testes, 0 falhas (`RestrictionDomainTest` 5, `RestrictionsApiTest` 11, `RestrictionsEventsTest` 5; contagem corrigida pelo orquestrador conforme o verificador);
  - `./gradlew test` do `rates`, `shared`, `AuthorizationTest`, `ModularityTest`, `architecture.*`, `MigrationConventionTest`, `DatabaseRolesTest` e `DatabaseMigrationsTest` → 293 testes, 0 falhas;
  - `./gradlew lint` → sucesso (as primeiras rodadas falharam em linhas longas, `ThrowsCount`, `LongMethod`, `LongParameterList`, `NoNameShadowing` e `UseOrEmpty`; corrigidos);
  - `./gradlew updateOpenApi` → contrato regenerado (399 linhas acrescentadas, nenhuma removida);
  - `.github/scripts/check-api-breaking.sh` (contra `origin/main`) e `.github/scripts/check-api-breaking.sh HEAD` (contra a E02) → "Nenhuma mudança incompatível (nível ERR) em /api/v1";
  - `./gradlew build` → BUILD SUCCESSFUL em 5 min 40 s (934 testes, 0 falhas; detekt, ktlint, `checkOpenApi` e `koverVerify`).
- Parecer do verificador: **APROVADO** (verificador independente, 2026-10-20; snapshot `5288c4ec5fb721a4bca2b6785ecbd5e4d04d44c1`). Os cinco critérios foram aprovados com evidência: precedência campo a campo com o tri-estado nos dois sentidos, recusas 400/422 e `CHECK` no banco, edição em massa só nas datas e dias filtrados, `RestrictionsChanged` com listener de teste (sem publicação no no-op e na recusa), semântica no `CLAUDE.md`, `./gradlew build` com 934 testes e 0 falhas e oasdiff sem `ERR` contra `origin/main` e contra `HEAD`. Os códigos publicados da E02 (`RATE_PRICE_*`) não mudaram e os testes da E02 estão inalterados. O `RestrictionsApiTest.kt` está íntegro (11 testes). Nenhum problema grave. Menores: a contagem de testes deste handoff (já corrigida); a incoerência com o já gravado sai como `422 RESTRICTION_RANGE_CONFLICT` sem campo (confirmado pelo usuário); `GET …/restrictions?roomTypeId=` com tipo fora do plano não é recusado e devolve só a geral (inofensivo); os campos `Int?` dependem da coerção padrão do Jackson (vale para o projeto todo).
- Entrevista dos pontos do implementador (usuário, 2026-10-20): todos os sete aceitos pela opção recomendada, que é a implementada: edição parcial com `set`/`clear`; `422 RESTRICTION_RANGE_CONFLICT` na combinação com o gravado; o derivado não herda as restrições do base; faixas de 1 a 365 noites e de 0 a 730 dias; `rates.restrictions.manage` não sensível e só para o Gerente; códigos próprios `RESTRICTION_PERIOD_*`/`RESTRICTION_SELECTION_INVALID`; `RestrictionField` como `enum` fechado.
- Notas para a próxima etapa:
  - E04 (vendabilidade): use `RestrictionReader.effective(plano, tipos, de, até)` (a efetiva por tipo e data; `EffectiveRestriction.NONE` sem linha) com a semântica decidida: `closed` em cada noite (chegada até a véspera da saída), CTA só na data de chegada, CTD só na data de saída (que pode ser o dia seguinte à última noite, então leia até a saída), estadia mínima e máxima e antecedência pelas da data de chegada, antecedência = dias de `HotelApi.currentBusinessDate()` até a chegada (H15). Defensivo: se uma efetiva vier com o mínimo acima do máximo (só possível se um tipo voltar a um plano com linhas antigas dele), trate a data como não vendável. Os códigos de recusa da vendabilidade entram em `RatesErrorCodes`.
  - M8 (`distribution`): escute `RestrictionsChanged` (no `general`, todos os tipos do plano) e releia as efetivas; a semântica está no `CLAUDE.md` e no KDoc de `RestrictionField`.
  - A migração nova precisa de timestamp posterior a `V2026_10_20_2021`. `PriceSelections` agora aceita um `DateTarget` com os `SelectionErrors` do assunto.

---

## E04 — Cálculo de preço e vendabilidade — #concluida

**Objetivo:** expor na API pública de `rates` o cálculo do preço de uma estadia (`priceFor` por noite, para tipo, plano, ocupação e idades das crianças, com pacotes inclusos e adicionais) e a vendabilidade (`isSellable` para tipo, estadia, canal e data operacional da consulta, avaliando restrições, canal, plano ativo, antecedência e ocupação máxima).

**Por que agora:** cotação (E06) e reservas (E07) usam esse cálculo; centralizá-lo evita preços divergentes entre recepção e channel manager.

**Dependências e paralelismo:** E02, E03. Pode correr em paralelo com E05 (em worktree separado); sem conflito de arquivos além de `CLAUDE.md`.

**Escopo:**

- `RatesApi.priceFor`: detalhe por noite (diária, adultos extras, crianças pela faixa da idade, H2, pacotes por noite, por estadia ou por pessoa/noite), total e separação de componentes inclusos quando a classificação exigir (RF-TAR-06), tudo em `Money`; a soma das noites é igual ao total.
- `RatesApi.isSellable`: plano ativo, canal, tipo no plano, fechado, CTA na chegada, CTD na saída, estadia mínima e máxima, antecedência pela data operacional (`HotelApi.currentBusinessDate()`, H15); motivos de recusa com `code` estável em `RatesErrorCodes`.
- Ocupação acima da máxima do tipo de UH (`RoomsApi.roomType(id).occupancy`) recusada.
- Documentação da API pública no `CLAUDE.md`.

**Fora de escopo:** disponibilidade (E05) e endpoint de cotação (E06).

**Passos de implementação:**

1. Testes de domínio com tabela de casos: derivado + pacote por pessoa/noite + criança por faixa; cada restrição isolada; antecedência; ocupação máxima.
2. Implementar o serviço e a API pública.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "como os componentes de pacote (incluso/adicional, ritmo, base de preço) entram no preço segundo SPEC RF-TAR-06, RF-FIS-04 e §8.2.5?".
- Partes paralelas: nenhuma.
- Foco do verificador: CTA avaliado na data errada (chegada) ou CTD na data errada (saída); estadia mínima contada em noites erradas; antecedência pela data do relógio em vez da operacional; pacote por estadia cobrado por noite; soma das noites diferente do total.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E04 — "Cálculo de preço e vendabilidade" do roadmap docs/roadmaps/roadmap-m4-tarifas-reservas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo as hipóteses H1, H2 e H15 e o "Protocolo de execução" —, a seção E04 inteira e o "Evidências e handoff" de E01, E02 e E03. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E02 e E03 estão #concluida e que o código bate com o handoff delas; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: expor na API pública de rates priceFor (detalhe por noite e total, com adultos extras, crianças pela faixa etária do hotel e pacotes inclusos e adicionais, em Money) e isSellable (plano ativo, canal, tipo no plano, fechado, CTA, CTD, estadia mínima e máxima, antecedência pela data operacional, ocupação máxima do tipo de UH pela RoomsApi), com motivos de recusa em code estável. Não implemente disponibilidade nem o endpoint de cotação.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E04 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume como pacotes entram no preço segundo SPEC RF-TAR-06, RF-FIS-04 e §8.2.5;
- escreva os testes de domínio em tabela de casos primeiro, implemente, documente a API pública no CLAUDE.md e rode ./gradlew build;
- verificação independente com o Prompt do verificador, com foco em CTA/CTD na data errada, estadia mínima mal contada, antecedência pela data do relógio, pacote por estadia cobrado por noite e total diferente da soma das noites.

Critérios de aceite (detalhes na seção E04):
- priceFor devolve detalhe por noite cuja soma é igual ao total, em todos os casos de teste;
- isSellable recusa cada restrição isoladamente com o code correspondente;
- ocupação acima da máxima do tipo recusada;
- API documentada no CLAUDE.md; build passa.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E04 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] `priceFor` devolve detalhe por noite e total, e a soma das noites é igual ao total em todos os casos de teste (incluindo derivado, pacote por pessoa/noite, pacote por estadia e criança por faixa).
- [ ] `isSellable` recusa, com `code` próprio, plano inativo, canal não permitido, tipo fora do plano, data fechada, CTA na chegada, CTD na saída, estadia abaixo do mínimo ou acima do máximo e antecedência fora da faixa contada da data operacional (um caso de teste para cada, um com a data operacional diferente da do relógio).
- [ ] Ocupação acima da máxima do tipo de UH é recusada.
- [ ] API pública documentada no `CLAUDE.md`; `./gradlew build` passa.

**Validação obrigatória:**

- `./gradlew build` → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Desempenho (RNF-06) com derivação na leitura: carregar preços do intervalo numa consulta; medir no teste da cotação (E06).

**Evidências e handoff:**

- Snapshot inicial: `1f482fa1da9af06d00fe87c3c87980680b78bd45` (recebido do orquestrador). Pré-requisitos conferidos: E02 e E03 `#concluida` (commits `8a19a35` e `653bc26`); o código bate com os handoffs (`RatePriceReader.effectivePrices`/`currentChildBands`, `RestrictionReader.effective` com `EffectiveRestriction.NONE`, `PriceSet.withoutPrice`, `ChildBands.classify`, `RatesModule` com `shared`, `rooms`, `billing`, `hotel`).
- Investigação (feita pela própria sessão, sem subagente, conforme o adendo do orquestrador): a SPEC só diz que o pacote tem preço por noite, por estadia ou por pessoa/noite, ritmo diário ou na chegada e "incluso na diária" ou "adicional", e que os componentes inclusos são lançados separados quando a classificação fiscal exigir (RF-TAR-06, §5.6; RF-FIS-04 pede a separação das receitas de hospedagem, A&B, eventos e outros); o §8.2.5 liga `RatePlan * → * Package` e `Package → 1 TransactionCode`, e o `NightlyRate` (§8.2.6) guarda um valor por noite. Daí: o incluso é parte da diária (não soma; é separado da hospedagem para o lançamento à parte) e o adicional soma ao total; o ritmo é de lançamento, não de preço.
- Arquivos alterados:
  - novos: `src/main/kotlin/com/zeny/hotel/rates/StayPricing.kt` (tipos públicos `StayQuery`, `SaleRefusal`, `Sellability`, `StayPriceResult`, `StayPrice`, `NightPrice`, `ChildCharge`, `PackageCharge`); `domain/StayEvaluation.kt` (`StayRules`, `StayPricing`, `SaleConditions`, `SaleRules`); `application/StayEvaluator.kt`; testes `src/test/kotlin/com/zeny/hotel/rates/StayEvaluationDomainTest.kt` (tabelas de casos) e `StayEvaluationApiTest.kt` (integração);
  - alterados: `rates/RatesApi.kt` (`priceFor`, `isSellable`, KDoc), `RatesErrorCodes.kt` (seção E04: `STAY_INVALID` e 15 motivos), `application/RatesApiService.kt` (implementa as duas; `RatePackage.summary()` compartilhado), `domain/RatePrices.kt` (`ChildBands.maxAge` e `bandOf`); `CLAUDE.md` (estado atual, nota na `RatesApi` da E01 e a seção "Preço e vendabilidade de uma estadia"); `README.md` (API e árvore); este roadmap. Sem migração, sem endpoint e sem mudança no `api/openapi.yaml`.
- Decisões:
  - **já dadas:** semântica das restrições da E03 (fechado em cada noite, CTA só na chegada, CTD só na saída, estadia e antecedência "on arrival"); faixas obsoletas e lacunas da derivação = sem preço (E02); antecedência pela data operacional (H15).
  - **propostas pelo implementador e decididas pelo usuário em 2026-10-20** (entrevista da E04 conduzida pelo orquestrador, as sete pela opção recomendada, sem ajuste no código): (1) idade de criança acima da `childMaxAge` é `400 STAY_INVALID` no campo `childAges[i]` (quem chama a conta como adulto), não convertida em adulto em silêncio; (2) pacotes inclusos acima da diária numa noite deixam a estadia sem preço (`INCLUDED_PACKAGES_EXCEED_RATE`), nunca uma hospedagem negativa; (3) o pacote por estadia pertence à noite da chegada (uma vez), não é repartido entre as noites; (4) só os pacotes do plano entram no preço (nenhum pacote avulso na consulta; a E07 acrescenta o parâmetro se precisar, sem quebrar nada); (5) `isSellable` inclui a falta de preço (vendável implica preço) e devolve todos os motivos, não só o primeiro; (6) ocupação máxima conferida separadamente (adultos ≤ `maxAdults` e crianças ≤ `maxChildren`; a `Occupancy` não tem total), sem criança "no lugar" de adulto; (7) o pacote por pessoa e noite conta adultos e crianças de qualquer idade.
  - **do implementador, sem impacto fora da etapa:** os resultados são valores (`StayPriceResult`, `Sellability` com `SaleRefusal(code, message, dates)`), não exceções, salvo o formato (`400 STAY_INVALID`); `priceFor` devolve `UNKNOWN_RATE_PLAN` e `ROOM_TYPE_NOT_IN_PLAN` mas não confere plano ativo, canal, restrições nem ocupação (para a E08 reprecificar uma reserva de plano já desativado); a chegada antes da data operacional é `ARRIVAL_BEFORE_BUSINESS_DATE` (e a antecedência não é avaliada), a chegada na própria data operacional tem antecedência 0; estadia de 1 a 365 noites (o day use da H5, chegada = saída, fica para a E07); o tipo de UH desativado no `rooms` mas ainda no plano é `ROOM_TYPE_INACTIVE`; uma efetiva incoerente (mínimo acima do máximo) recusa naturalmente por um dos dois motivos.
- Validações executadas:
  - `./gradlew test --tests 'com.zeny.hotel.rates.StayEvaluationDomainTest'` → 62 testes (casos das tabelas), 0 falhas; `StayEvaluationApiTest` → 4 testes, 0 falhas; `./gradlew test --tests 'com.zeny.hotel.rates.*' --tests 'com.zeny.hotel.ModularityTest' --tests 'com.zeny.hotel.architecture.*'` → 173 testes, 0 falhas;
  - os testes foram escritos junto da implementação; para provar que exercitam o comportamento, cada mutação abaixo foi aplicada, rodada e revertida: CTA lido na data de saída → 3 casos falham; CTD lido na data de chegada → 3 falham; estadia mínima pela maior de qualquer noite → 1 falha; pacote por estadia cobrado em cada noite → 4 falham; total da noite somando os inclusos → 4 falham; noites contadas como saída − chegada + 1 → 3 falham; noite fechada contando a data de saída → 1 falha; data de referência da antecedência tirada do `Clock` da aplicação em vez da `HotelApi.currentBusinessDate()` → o teste de integração "the advance counts from the business date…" falha (`MAX_ADVANCE_EXCEEDED` com o relógio 40 dias antes);
  - `./gradlew lint` → sucesso, depois de corrigir os achados do detekt (`ReturnCount`, `LongMethod`, `LongParameterList`, `MaxLineLength`) e de rodar `./gradlew ktlintFormat`;
  - `./gradlew build` → BUILD SUCCESSFUL em 4 min 43 s (1000 testes, 0 falhas; detekt, ktlint, `checkOpenApi` e `koverVerify`); o `api/openapi.yaml` não mudou (nenhum endpoint novo);
  - `.github/scripts/check-api-breaking.sh` → "Nenhuma mudança incompatível (nível ERR) em /api/v1";
  - `python3 ~/.claude/skills/development-roadmap/scripts/check_roadmap.py docs/roadmaps/roadmap-m4-tarifas-reservas.md` → OK.
- Parecer do verificador: **APROVADO** (verificador independente, 2026-10-20; snapshot verificado `8e87949afe5b24d5de5c9547e9a36124f6b01382`). Os quatro critérios aprovados: soma das noites = total em todos os casos, com valores conferidos à mão (2916,00 e 2091,00); um caso por motivo de recusa em `StayEvaluationDomainTest` e na integração, com a antecedência pela data operacional (relógio deslocado ±40 dias); ocupação acima da máxima recusada; `CLAUDE.md` documentado e `./gradlew build` verde (1000 testes, 0 falhas), oasdiff sem `ERR`, `api/openapi.yaml` inalterado. Foco do verificador sem achados (CTA lido na chegada, CTD na saída, estadia mínima da chegada, `PER_STAY` só na chegada, total por construção). Observações baixas: `MAX_ADVANCE_EXCEEDED` coberto só no domínio na integração com o relógio deslocado; `priceFor` não confere plano ativo nem pacote desativado (intencional, para a E08 reprecificar; a E07/E08 deve saber); idade num buraco entre faixas daria `STAY_INVALID` com mensagem imprecisa (o `hotel` já exige faixas contíguas); sem limite de tamanho de `childAges` no `priceFor` (o `isSellable` confere). As sete decisões pendentes foram confirmadas pelo usuário na entrevista do orquestrador.
- Notas para a próxima etapa:
  - E06 (cotação): para cada tipo e plano, `isSellable(query, canal)` e, se vendável, `priceFor(query)` (que relê os preços; se o RNF-06 apertar, um método combinado lê uma vez só, sem mudar os dois); os motivos já vêm em `code` estável com as datas para o calendário. A disponibilidade (E05) é à parte.
  - E07 (reservas): congele por noite o `NightPrice.total` (ou o `roomAmount` e os pacotes separados, conforme o `NightlyRate`); converta `UNKNOWN_RATE_PLAN`/`UNKNOWN_ROOM_TYPE` em `400` com o campo e os demais `SaleRefusal` em `422` com o `code`; o desconto (`rates.discounts.apply`, `maxPercent`) compara o valor gravado com o `priceFor`. As idades das crianças são na data de chegada; acima da `childMaxAge`, conte como adulto. Day use (chegada = saída, H5) hoje é `STAY_INVALID`: a E07 decide como precificá-lo.
  - M5/M6: o `PackageCharge` traz o código de lançamento, o ritmo e se é incluso, para o check-in e o fechamento lançarem os pacotes.

---

## E05 — Inventário por tipo e data — #concluida

**Objetivo:** criar o módulo `reservations` com os contadores `room_type_inventory(room_type_id, date, total, sold, blocked, out_of_order)`, atualizados só com lock pessimista curto, mantidos num horizonte de datas, com `total` acompanhando o cadastro de UHs (evento novo no `rooms`, H4) e `out_of_order` reagindo a `OutOfOrderChanged` (primeiro consumidor de produção, H10), com recontagem, limite de overbooking configurável nascido zero e evento `AvailabilityChanged` (RF-TAR-08, RF-TAR-09, RN-07, ARCHITECTURE §11).

**Por que agora:** é o ponto de concorrência mais crítico do sistema: reservas da recepção e do channel manager disputam os mesmos contadores. Precisa estar correto antes de qualquer reserva.

**Dependências e paralelismo:** nenhuma dentro deste roadmap; exige o gate de M3 `#concluida` e a decisão de H4 e H10. Pode correr em paralelo com E01 a E04 (em worktree separado); conflito de edição em `api/openapi.yaml`, `CLAUDE.md`, `README.md`, `ModularityTest` e `docs/ARCHITECTURE.md`.

**Escopo:**

- Pacote `com.zeny.hotel.reservations` (a criar) com `ReservationsModule.kt` (`allowedDependencies = ["shared", "hotel", "rooms"]`), schema e pasta de migração `reservations` com a migração de `GRANT`, API pública na raiz, `ReservationsPermissions` (H16: `reservations.availability.read`, `reservations.inventory.manage`) e um teste novo no `ModularityTest`.
- Tabela de inventário com chave (tipo, data); `total` = UHs ativas do tipo (`RoomsApi.activeRooms(tipo)`); `out_of_order` a partir de `RoomsApi.outOfOrderPeriods` (só `OUT_OF_ORDER`); limite de overbooking por tipo e data, padrão zero (Q-09), configurável por endpoint com permissão e auditoria.
- Serviço interno de inventário (`application`): trava as linhas das datas envolvidas com `SELECT … FOR UPDATE` em ordem determinística (tipo, data), confere `total − sold − blocked − out_of_order + limite ≥ pedido` e atualiza na transação de escrita do chamador (fora dela, `IllegalStateException`, como `requireOpenShift`); operações de liberar; recusa com `409` e `code` estável.
- Horizonte (H3): job diário com `@SchedulerLock` que cria as datas faltantes e uma constante de adiamento no `@IntegrationTest` (como `FILES_PURGE_DEFERRED`).
- Evento de cadastro de UH no `rooms` (H4; nome a confirmar): publicado pelo outbox nos casos de uso de criação, edição com troca de tipo, desativação e reativação (`rooms/application/RoomsRegistry.kt`), sem mudar o contrato HTTP do `rooms`, com teste no `rooms`; listener idempotente no `reservations` que recalcula o `total` dos tipos afetados.
- Listener de `OutOfOrderChanged` (H10): recalcula `out_of_order` das datas do período (e das do período anterior numa alteração) a partir da `RoomsApi`, idempotente; a marca de realocação das reservas alocadas fica para a E10.
- Recontagem (`application`, chamável por teste e pelo fechamento do M6): refaz `total` e `out_of_order` pela `RoomsApi` e `sold`/`blocked` pelas reservas e bloqueios (zero até a E07).
- Evento `AvailabilityChanged` (`reservations/ReservationsEvents.kt`: tipo e intervalo) em toda mudança de contador, testado com listener de teste; frase da ARCHITECTURE §11 sobre o OOO ajustada (H10).

**Fora de escopo:** reservas (E07); bloqueios (E11); consulta de calendário (E06); alerta de realocação (E10).

**Passos de implementação:**

1. Conferir no código `rooms/RoomsEvents.kt` e os casos de uso de cadastro do `rooms` (H4).
2. Testes de integração, cada um com tipos de UH próprios (banco compartilhado): concorrência (N threads disputando a última unidade), ordem de locks sem deadlock em intervalos sobrepostos, OOO reduz disponibilidade pelo listener (chamado direto e pelo `Scenario`), reentrega do mesmo evento sem mudar o resultado, horizonte estendido pelo job, cadastro de UH atualiza `total`, recontagem repara um contador adulterado por SQL.
3. Implementar migração, serviço, listeners, job, eventos e o endpoint do limite; regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "onde, nos casos de uso do `rooms` (`RoomsRegistry.kt`, `RoomsApiService.kt`), uma UH é criada, muda de tipo, é desativada ou reativada, e como o `OutOfOrderService` publica `OutOfOrderChanged`?"; "o que ARCHITECTURE §5, §11 e §13 e SPEC RF-TAR-08, RF-TAR-09, RN-07 e §8.3 exigem dos contadores e dos eventos?".
- Partes paralelas: nenhuma.
- Foco do verificador: corrida que permite vender acima do disponível; deadlock entre reservas com intervalos sobrepostos; contador atualizado fora da transação do chamador; `AvailabilityChanged` ausente em alguma mudança; listener de OOO ou de cadastro não idempotente (somando deltas); OOS reduzindo a disponibilidade; mudança de contrato HTTP no `rooms`; testes que dividem tipos de UH com outros testes.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E05 — "Inventário por tipo e data" do roadmap docs/roadmaps/roadmap-m4-tarifas-reservas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Contexto confirmado", restrições, as hipóteses H3, H4, H10 e H16 e o "Protocolo de execução" —, a seção E05 inteira e docs/ARCHITECTURE.md §5, §11 e §13. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que a etapa E11 (gate) de docs/roadmaps/roadmap-m3-hospedes-contas.md está #concluida, que H4 e H10 já foram decididas (se ainda disserem "a confirmar pelo usuário", pare e peça a decisão) e que o rooms bate com o "Contexto confirmado"; se não, pare e relate sem implementar.

Objetivo: criar o módulo reservations (shared, hotel, rooms) com os contadores room_type_inventory por tipo e data (total pelas UHs ativas da RoomsApi, sold, blocked, out_of_order só de OOO, limite de overbooking nascido zero e configurável), um serviço que trava as datas com SELECT FOR UPDATE em ordem determinística na transação do chamador, job de horizonte com ShedLock, o evento de cadastro de UH acrescentado ao rooms (H4) e o listener dele, o listener idempotente de OutOfOrderChanged que recalcula pela RoomsApi (H10), a recontagem e o evento AvailabilityChanged. Não implemente reservas, bloqueios, calendário nem o alerta de realocação.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E05 para #em-andamento no índice e no título;
- investigação: um subagente Explore localiza os casos de uso de cadastro e de OOO do rooms e resume as exigências de ARCHITECTURE §5, §11 e §13 e SPEC RF-TAR-08, RF-TAR-09, RN-07 e §8.3;
- escreva os testes de integração primeiro, com tipos de UH próprios, incluindo o teste concorrente e os eventos com @EnableScenarios e listener de teste; implemente; atualize ModularityTest, ARCHITECTURE §11, CLAUDE.md e README.md; rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em venda acima do disponível sob concorrência, deadlock, atualização fora da transação, listener não idempotente e evento ausente.

Critérios de aceite (detalhes na seção E05):
- N transações simultâneas pela última unidade: exatamente uma consegue (teste concorrente com Testcontainers);
- intervalos sobrepostos em ordens diferentes não geram deadlock;
- OOO reduz a disponibilidade nas datas pelo listener e publica AvailabilityChanged; reentrega não muda o resultado; OOS não reduz;
- cadastro de UH atualiza o total; job estende o horizonte; recontagem repara; build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E05 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Teste concorrente com PostgreSQL real: N transações simultâneas disputando a última unidade de um tipo numa data → exatamente uma reserva o inventário; as demais recebem `409` com `code` estável.
- [ ] Duas transações com intervalos sobrepostos travando em ordens diferentes terminam sem deadlock (teste).
- [ ] `OutOfOrderChanged` (abertura, alteração e liberação) ajusta `out_of_order` nas datas do período e publica `AvailabilityChanged`; reprocessar o mesmo evento não altera o resultado; um OOS não reduz a disponibilidade.
- [ ] Criar, desativar, reativar ou trocar o tipo de uma UH atualiza `total` dos tipos afetados (teste), pelo evento novo do `rooms`, sem mudança no contrato HTTP do `rooms`.
- [ ] Job de horizonte cria as datas faltantes, é protegido por ShedLock e adiado no `@IntegrationTest`.
- [ ] Limite de overbooking nasce zero e, quando configurado (com permissão e auditoria), é respeitado (teste).
- [ ] Recontagem refaz um contador adulterado por SQL.
- [ ] `ModularityTest` com o `reservations` (só `shared`, `hotel`, `rooms`); ARCHITECTURE §11 coerente com a H10; `./gradlew build` e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Contador divergir da realidade após bug ou publicação abandonada no outbox: a recontagem a partir da `RoomsApi`, das reservas e dos bloqueios reconstrói os contadores (chamável por teste e, depois, pelo fechamento em M6); registrar no handoff.
- UH desativada com reservas futuras deixa a disponibilidade negativa (H4): a E06 mostra as datas sobrevendidas.

**Rollback / migração:** tabelas novas; a recontagem permite reconstruir os contadores se a migração precisar ser refeita. O evento novo do `rooms` é acréscimo.

**Evidências e handoff:**

- Snapshot inicial: `099af532ec2d1bda4312aae7724bebab6e93acf5` (recebido do orquestrador). Snapshot ao fim da implementação: `b73cd0dcfb227f8f1e8fb8ef0ef8b6a940212ef0`; depois da correção da 1ª verificação (antes deste handoff): `691f888cb618602d665d566b83ffaed35ce3901c`. Pré-requisitos conferidos: E11 do M3 `#concluida`; H4 e H10 decididas pelo usuário em 2026-10-20; o `rooms` bate com o "Contexto confirmado" (só `RoomStatusChanged` e `OutOfOrderChanged`; `RoomsApi.activeRooms`, `outOfOrderPeriods` com `blockedUntil`). Investigação feita pela própria sessão (adendo do orquestrador): o cadastro de UH está em `rooms/application/RoomsRegistry.kt` (`createRoom`, `updateRoom`, `deactivateRoom`, `reactivateRoom`, todos com `flush` antes do retorno) e o OOO em `OutOfOrderOperations.publish` (`OutOfOrderChanged` com o período anterior na alteração); ARCHITECTURE §5/§11/§13 e SPEC RF-TAR-08/09, RN-07, §8.3 e Q-09 resumidos no `CLAUDE.md` (seção "Inventário por tipo e data").
- Arquivos alterados:
  - novos (`reservations`): `ReservationsModule.kt`, `ReservationsApi.kt` (`inventory`, `recountInventory`, `InventoryDay`, `InventoryRecount`), `ReservationsErrorCodes.kt`, `ReservationsEvents.kt` (`AvailabilityChanged`), `ReservationsPermissions.kt`; `domain/Inventory.kt`; `application/InventoryPorts.kt`, `RoomTypeInventory.kt` (com `ReservationsAudit` e `AvailabilityEvents`), `InventoryRecalculation.kt` (`InventoryRefresher`, `InventoryRecalculation`, `InventoryListeners`), `InventoryService.kt` (com `ReservationsApiService` e o bean das permissões); `infrastructure/JdbcInventoryStore.kt`, `InventoryHorizonJob.kt`; `web/InventoryController.kt`; migrações `reservations/V2026_10_20_2315__create_room_type_inventory.sql` e `V2026_10_20_2316__grant_reservations_to_app_role.sql`;
  - `rooms`: `RoomsEvents.kt` (`RoomRegistryChanged`, `RoomRegistryChange`) e `application/RoomsRegistry.kt` (publicação nos quatro casos de uso; contrato HTTP inalterado);
  - testes novos: `reservations/InventoryDomainTest.kt`, `InventoryApiTest.kt`, `InventoryTestSupport.kt`, `rooms/RoomRegistryEventsTest.kt`, `testsupport/InventoryListenersQuiescence.kt`; `checklists/ChecklistOfflineTest.kt` (espera os listeners do inventário antes de baixar); `gradle.properties` (novo); alterados: `ModularityTest` (teste do `reservations`), `testsupport/IntegrationTest.kt` (`INVENTORY_HORIZON_DEFERRED`, repassada também em `RealtimeStreamTest`, `ProductionProfileApiTest` e `EventPublicationRegistryTest`), `rooms/OutOfOrderApiTest.kt` (publicações filtradas pelo listener de teste: o inventário também escuta o evento), `iam/AuthorizationTest.kt` (as duas permissões novas no modelo Gerente), `sync/OfflineSyncTest.kt` (o download incremental das UHs espera a transação concorrente do listener do inventário terminar: o cursor só serve transações terminadas, H6 do M2);
  - `src/main/resources/application.yaml` (`zeny.reservations.inventory-horizon`), `api/openapi.yaml` (regenerado: 3 operações novas), `docs/ARCHITECTURE.md` (§11 coerente com a H10 e a H4; §13 com o job do horizonte), `CLAUDE.md` (estado atual, seção "Inventário por tipo e data", listeners, jobs, constante de adiamento), `README.md` (API e árvore), este roadmap.
- Decisões:
  - **já dadas:** H4 (evento de cadastro de UH no `rooms`, listener que recalcula o total, recontagem), H10 (OOO pelo listener assíncrono que refaz pela `RoomsApi`, só `OUT_OF_ORDER`), H3 (730 dias), H16 (nomes `reservations.availability.read` e `reservations.inventory.manage`), Q-09 (limite nasce zero).
  - **do implementador, decididas pela opção recomendada em nome do usuário** (o usuário, ausente, delegou em 2026-10-20 ao orquestrador escolher a opção recomendada depois de pesar prós e contras; avaliadas assim depois da 1ª verificação, sem ajuste no código; os dois itens de revisão humana, o disponível negativo sem recusa no `rooms` e a janela do listener com o job diário, também ficaram como estão): (1) nome do evento `RoomRegistryChanged` com o campo `change` (`CREATED`, `ROOM_TYPE_CHANGED`, `DEACTIVATED`, `REACTIVATED`) além dos campos da H4; (2) limite de overbooking de 0 a 50, permissão **não sensível** só do Gerente, sem `If-Match`; (3) recontagem exposta também por endpoint (`POST /api/v1/room-type-inventory/recount`, `reservations.inventory.manage`), além da `ReservationsApi` para o M6; (4) `AvailabilityChanged` só com tipo e intervalo (sem os números; o consumidor relê), publicado também quando o horizonte ganha datas com UHs; (5) o passado (datas antes da data operacional) nunca é refeito; (6) o job diário refaz o horizonte inteiro de cada tipo ativo (repara evento perdido) e cria linhas também para tipos sem UHs (total 0: a tomada recusa com `409 ROOM_TYPE_UNAVAILABLE`, não `422` de horizonte); (7) consulta `GET /api/v1/room-type-inventory` já nesta etapa (a E06 traz o calendário com preços e restrições); (8) devolver mais do que foi tomado é `IllegalStateException` (erro de programação), não um ajuste silencioso; (9) quem refaz o total e o OOO toma uma trava consultiva por tipo antes de ler a `RoomsApi` (serializa os refazimentos; a tomada não a usa).
  - **do implementador, sem impacto fora da etapa:** coluna `stay_date` (não `date`); `out_of_order` conta só UHs **ativas** em OOO e o `CHECK` exige `out_of_order ≤ total` (por isso todo refazimento refaz os dois juntos); as mudanças de uma chamada valem juntas por linha (o bloqueio que vira reserva não precisa de unidade extra); `INVENTORY_DATE_OUT_OF_HORIZON` é `422` com as datas no problema e `ROOM_TYPE_UNAVAILABLE` traz `roomTypeIds` e `dates`.
- Validações executadas:
  - `./gradlew test --tests 'com.zeny.hotel.reservations.*' --tests 'com.zeny.hotel.rooms.RoomRegistryEventsTest' --tests 'com.zeny.hotel.ModularityTest' --tests 'com.zeny.hotel.architecture.*'` → `InventoryApiTest` 16, `InventoryDomainTest` 7, `RoomRegistryEventsTest` 2, `ModularityTest` 16, 0 falhas;
  - os testes foram escritos junto da implementação; para provar que exercitam o comportamento, cada mutação foi aplicada, rodada e revertida: travar as linhas mudança a mudança na ordem do pedido (sem a ordem global) → o teste de intervalos sobrepostos falha com `deadlock detected`; tirar o `FOR UPDATE` → 8 das 8 transações tomam a última unidade (`expected:<1> but was:<8>`); contar o OOS como OOO → o teste da reentrega/OOS falha; somar o OOO ao que já estava (delta) → a reentrega muda o contador e o teste falha;
  - `./gradlew lint` → sucesso (depois de corrigir `MaxLineLength`, `ThrowsCount`, `LongParameterList` e uma desestruturação de 4 no teste);
  - `./gradlew updateOpenApi` → `api/openapi.yaml` com `listRoomTypeInventory`, `setRoomTypeOverbookingLimit` e `recountRoomTypeInventory`;
  - primeiro `./gradlew build` → 2 falhas fora do módulo (`AuthorizationTest`, lista exata do modelo Gerente; `OfflineSyncTest`, o download incremental das UHs segurado pela transação do listener do inventário), corrigidas nos testes; `./gradlew build` → BUILD SUCCESSFUL em 6 min 38 s (1026 testes, 0 falhas; detekt, ktlint, `checkOpenApi` e `koverVerify`);
  - `.github/scripts/check-api-breaking.sh` → "Nenhuma mudança incompatível (nível ERR) em /api/v1";
  - `python3 ~/.claude/skills/development-roadmap/scripts/check_roadmap.py docs/roadmaps/roadmap-m4-tarifas-reservas.md` → OK, nenhum problema estrutural.
  - **Correção da 1ª verificação** (REPROVADO: o comportamento estava correto, mas um `./gradlew build` falhou):
    - (grave) `OfflineSyncTest` instável sob carga: a transação de um listener do inventário ainda aberta retinha o download. Correção para a classe inteira: o apoio `testsupport/InventoryListenersQuiescence.await(jdbc)` espera zero publicações não concluídas (sem as `FAILED`) dos `InventoryListeners` em `shared.event_publication` (timeout de 30 s) e é chamado antes de **todo** download (o `download` do `OfflineSyncTest` e o do `ChecklistOfflineTest`) e no `@BeforeEach` das duas classes; o ajuste caso a caso do teste de RN-13 saiu. O `RealtimeStreamTest` não precisa: o SSE sai no `afterCommit`, sem horizonte de transação. Nenhum outro teste baixa a sincronização;
    - `AvailabilityChanged` da tomada e da devolução: teste novo `taking and releasing units publish AvailabilityChanged…` (o `Scenario` na tomada, o intervalo exato na devolução, a troca bloqueio → venda publicando a data e a mudança líquida zero não publicando nada);
    - asserções exatas: `InventoryTestSupport.publishedBy(tipo) { ação }` espera os listeners antes e depois e devolve só as publicações feitas por aquela ação; o teste do OOO confere exatamente uma por ação (abrir `start..start+2`, alterar `start..start+6`, liberar `start+5..start+6`) e o da recontagem exatamente uma pela API pública, nenhuma ao recontar de novo e uma pelo endpoint. Mutações: tirar o `availability.publish` da tomada → o teste novo falha; tirar o do refazimento → os testes do OOO e da recontagem falham (2 de 2);
    - ambiente: `gradle.properties` na raiz com `org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8` (registrado no `CLAUDE.md`, junto da nota do Dockerfile);
    - `./gradlew test` focado (`reservations.*`, `OfflineSyncTest`, `ChecklistOfflineTest`) → 17 + 7 + 25 + 6 testes, 0 falhas; `./gradlew build` duas vezes seguidas → 1ª rodada BUILD SUCCESSFUL em 7 min (1027 testes, 0 falhas); 2ª com `--rerun-tasks` (compilação do zero, com o heap novo) BUILD SUCCESSFUL em 7 min 32 s (1027 testes, 0 falhas); `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR`.
- Parecer do verificador: 1ª verificação **REPROVADO** (instabilidade do `OfflineSyncTest` num dos dois builds, falta de teste do evento da tomada, asserções fracas de evento, heap do Gradle); corrigido acima. 2ª verificação independente **APROVADO** (2026-10-21, snapshot `e30d5ea11f505651a2bb77fb88ca1c38c3f66e7b`): os oito critérios aprovados, com evidência (8 threads pela última unidade, 1 sucesso e 7 `ROOM_TYPE_UNAVAILABLE`; 10 rodadas em ordens opostas sem deadlock; uma publicação exata por ação do OOO; reentrega sem efeito e OOS ignorado; cadastro de UH pelo `RoomRegistryChanged` sem mudar o contrato do `rooms`; horizonte com ShedLock; limite de overbooking; recontagem), e as quatro correções conferidas. `./gradlew build --rerun-tasks` e `./gradlew cleanTest build` verdes (1027 testes, 0 falhas); `OfflineSyncTest`, `ChecklistOfflineTest` e os testes do inventário em 5 rodadas seguidas sem falha; oasdiff sem `ERR`. A espera dos testes (`InventoryListenersQuiescence`) não mascara defeito: termina numa asserção em 30 s, exclui só as `FAILED` e não muda a regra de produção do cursor. Observações menores: um contexto fechado com publicação do inventário na fila faria a espera estourar e falhar o teste (erro visível; não ocorreu em 7 execuções); o teste concorrente usa a porta `RoomTypeInventory`, porque não há endpoint de reserva até a E07; `InventoryApiTest.settledRow` usa `Thread.sleep(500)` só para conferir que nada mudou.
- Notas para a próxima etapa:
  - E06 (disponibilidade e calendário): leia os contadores pela `InventoryStore.rows`/`ReservationsApi.inventory` (o disponível pode ser negativo: mostre a data como sobrevendida) e combine com `RatesApi.isSellable`/`priceFor`; datas fora do horizonte não têm linha.
  - E07/E08/E11: tome e devolva pelo `RoomTypeInventory.apply` **numa só chamada** por transação (todas as mudanças juntas: alteração = devolver as datas antigas e tomar as novas; pick-up = devolver `BLOCKED` e tomar `SOLD`), dentro do caso de uso auditado, e mantenha a transação curta (as linhas ficam travadas até o commit). Converta `RoomTypeUnavailableException` (`409`) e `InventoryOutOfHorizonException` (`422`) como estão. Implemente `InventoryUsageSource` (reservas na E07, bloqueios na E11): sem ela, a recontagem zera `sold`/`blocked`. O day use (H5) não toma unidade.
  - E10: a marca "requer realocação" e a notificação ficam no listener de `OutOfOrderChanged` da E10 (o de hoje só refaz os contadores).
  - M6: o fechamento chama `ReservationsApi.recountInventory()` para reparar divergências; o job do horizonte já roda diário, mas a data operacional avança no fechamento, então chame também a recontagem (ou o horizonte) depois de avançar a data.
  - Testes: cada teste de inventário cria os próprios tipos de UH; os listeners são assíncronos (use `InventoryTestSupport.awaitRow`); a recontagem refaz todos os tipos do período (use datas que nenhum outro teste use). Os listeners do inventário rodam em todo contexto de teste: todo teste que baixa a sincronização offline chama `InventoryListenersQuiescence.await(jdbc)` antes (como o `OfflineSyncTest` e o `ChecklistOfflineTest`), e todo teste de `AvailabilityChanged` conta só as publicações da própria ação (`InventoryTestSupport.publishedBy`).

---

## E06 — Disponibilidade, calendário e cotação — #concluida

**Objetivo:** expor a consulta de disponibilidade em calendário (por tipo e data, com preços e restrições) e a cotação (período, ocupação e canal → tipos e planos disponíveis com total e detalhe por noite) (RF-TAR-08, RF-TAR-11).

**Por que agora:** a recepção cota antes de reservar, e a criação de reserva (E07) usa a mesma verificação.

**Dependências e paralelismo:** E04, E05. Não paralelizável.

**Escopo:**

- `GET /api/v1/availability` (calendário por intervalo e tipos, com disponibilidade, datas sobrevendidas, preço do plano pedido e restrições) e `GET /api/v1/quotes` (cotação) em `reservations`, combinando inventário (E05) com `priceFor` e `isSellable` de `rates` (E04). Aresta `reservations` → `rates` no `ReservationsModule` e no `ModularityTest`.
- Cotação lista só combinações vendáveis e disponíveis; opcionalmente indica o motivo das indisponíveis.
- Medição de desempenho: cotação de 7 noites para todos os tipos e planos dentro de RNF-06 num teste com dados de exemplo (30 UHs, 730 dias).

**Fora de escopo:** criação de reserva (E07).

**Passos de implementação:**

1. Testes de integração: calendário reflete OOO e vendas; cotação respeita restrição, canal e disponibilidade; soma das noites igual ao total.
2. Implementar consultas e endpoints; regenerar o contrato.
3. Medir a cotação e registrar o resultado no handoff.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em RF-TAR-08, RF-TAR-11 e nos handoffs de E04 e E05.
- Partes paralelas: nenhuma.
- Foco do verificador: cotação oferecendo tipo sem disponibilidade ou plano não vendável no canal; calendário ignorando OOO; consultas N+1 que estouram RNF-06; leitura das tabelas de `rates` em vez da `RatesApi`.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E06 — "Disponibilidade, calendário e cotação" do roadmap docs/roadmaps/roadmap-m4-tarifas-reservas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E06 inteira, o "Evidências e handoff" de E04 e E05 e RF-TAR-08 e RF-TAR-11 em docs/SPEC.md §5.6. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E04 e E05 estão #concluida e que o código bate com o handoff delas; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: em reservations, expor GET /api/v1/availability (calendário por intervalo e tipos, com disponibilidade, datas sobrevendidas, preços e restrições) e GET /api/v1/quotes (período, ocupação e canal → tipos e planos vendáveis e disponíveis com total e detalhe por noite), usando o inventário de E05 e a RatesApi (aresta reservations → rates). Meça a cotação de 7 noites com 30 UHs e 730 dias de dados. Não implemente criação de reserva.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E06 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de integração primeiro, implemente, atualize ModularityTest, CLAUDE.md e README.md, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em oferta sem disponibilidade ou fora do canal, OOO ignorado e consultas N+1.

Critérios de aceite (detalhes na seção E06):
- calendário reflete vendas e OOO;
- cotação não oferece tipo sem disponibilidade nem plano não vendável no canal, e a soma das noites é igual ao total;
- tempo da cotação medido e registrado no handoff, dentro de RNF-06;
- build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E06 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [x] Calendário devolve, por tipo e data, disponibilidade, preço do plano pedido e restrições; reflete vendas e OOO (teste de integração).
- [x] Cotação não oferece tipo sem disponibilidade nem plano não vendável no canal ou com restrição violada (testes).
- [x] Soma das noites igual ao total em todas as ofertas.
- [x] Cotação de 7 noites, 30 UHs e 730 dias de dados abaixo de 300 ms no teste local (medição registrada no handoff).
- [x] `ModularityTest` com `reservations` → `rates`; contrato regenerado; `./gradlew build` e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Se a medição estourar RNF-06, registrar e propor materialização de preços derivados (H1) como etapa nova, sem bloquear esta.

**Evidências e handoff:**

- Snapshot inicial: `7b997a840d5bd743244f1bee3d3a51655f27e90a` (recebido do orquestrador). Snapshot ao fim da implementação (antes deste handoff): `65e319913eaf66471d964371fc31b4c44bb3f755`. Pré-requisitos conferidos: E04 e E05 `#concluida` (commits `359e85c` e `c7efa3d`); o código bate com os handoffs (`RatesApi.priceFor`/`isSellable` com `SaleRefusal`, `StayEvaluator` sobre `RatePriceReader.effectivePrices` e `RestrictionReader.effective`; `InventoryStore.rows`, `InventoryDay.available` podendo ser negativo, `ReservationsModule` com `shared`, `hotel`, `rooms`); a ARCHITECTURE §3.1 já tinha `reservations` → `rates`. Investigação: nenhuma delegada (adendo do orquestrador); RF-TAR-08 ("disponibilidade = UHs ativas − OOO − reservas − bloqueios; consulta em calendário com preços e restrições") e RF-TAR-11 ("dado período, ocupação e canal, lista tipos de UH e planos disponíveis, com o total e o detalhamento por noite") lidos na SPEC §5.6, RNF-06 (p95 < 300 ms) na §7.
- Arquivos alterados:
  - `rates` (acréscimos à API pública, sem quebrar nada): `RatesApi.kt` (`stayOptions`, `rateCalendar`), `StayPricing.kt` (`StayOptionsQuery`, `StayOption`), novo `RateCalendar.kt` (`RateCalendar`, `RateCalendarDay`, `DailyRates`, `DailyChildRate`, `DailyRestriction`, `ChildAgeBandSummary`), `RatesErrorCodes.kt` (descrição de `UNKNOWN_RATE_PLAN`), `application/StayEvaluator.kt` (as quatro leituras pelo mesmo `Prepared`, que lê os pacotes numa consulta e os preços e as restrições de cada plano uma vez; `isSellable` e `priceFor` também passam por ele), `application/RatesApiService.kt` (`RatePlan.summary()` compartilhado), `application/RatesPorts.kt` e `infrastructure/JdbcRatesDirectory.kt` (`activeRatePlansWith(tipos)`);
  - `reservations`: `ReservationsModule.kt` (`"rates"` nas dependências), `ReservationsErrorCodes.kt` (`AVAILABILITY_QUERY_INVALID` e os reexportados `UNKNOWN_RATE_PLAN`, `STAY_INVALID`), `application/InventoryPorts.kt` e `infrastructure/JdbcInventoryStore.kt` (`rows(tipos, de, até)`, uma consulta para todos os tipos), novos `domain/Availability.kt` (`StayAvailability`, `AvailabilityRules`), `application/AvailabilityService.kt` (calendário e cotação, só leitura) e `web/AvailabilityController.kt` (`GET /api/v1/availability`, `GET /api/v1/quotes` e os DTOs), `web/InventoryController.kt` (constantes da tag compartilhadas e descrição da tag);
  - testes: novos `reservations/AvailabilityApiTest.kt` e `reservations/QuotePerformanceTest.kt` (no contexto do `InventoryApiTest`); `rates/StayEvaluationApiTest.kt` (teste de `stayOptions` e `rateCalendar`); `ModularityTest.kt` (`reservations` → `rates`);
  - `api/openapi.yaml` (regenerado: `getAvailabilityCalendar` e `quoteStay`, a descrição da tag `reservations`), `CLAUDE.md` (estado atual, a "Cotação e calendário" na seção do `rates` e a seção nova "Disponibilidade, calendário e cotação"), `README.md` (API e árvore), este roadmap. Sem migração.
- Decisões:
  - **já dadas:** H1 (derivado calculado na leitura), H4 (disponível negativo mostrado como sobrevendido), H10 (OOO pelo `out_of_order` do inventário), H14 (sem offline nem SSE), a semântica das restrições da E03 e as regras de venda da E04.
  - **do implementador, decididas pela opção recomendada em nome do usuário** (o usuário, ausente, delegou em 2026-10-21 ao orquestrador escolher a opção recomendada depois de pesar prós e contras; avaliadas assim depois da verificação, sem ajuste no código: seguem as convenções da E05 e o que a E07 vai tomar, e nenhuma tem efeito fora da etapa): (1) o calendário e a cotação exigem só `reservations.availability.read` (os mesmos modelos de `rates.plans.read`: Gerente, Recepcionista, Comercial/Eventos e Financeiro), sem pedir também a permissão do `rates` para ver os preços; (2) o calendário mostra só as datas **com inventário mantido** (como o `GET /room-type-inventory`: nada antes da criação das linhas nem além dos 730 dias), até 366 dias; (3) a cotação, sem `ratePlanIds`, considera todos os planos **ativos** que têm algum dos tipos (os de outro canal entram só em `unavailable`, com `SALES_CHANNEL_NOT_ALLOWED`) e, com `ratePlanIds`, inclui também o plano desativado pedido (recusado com `RATE_PLAN_INACTIVE`); (4) a oferta é de **uma** UH: exige disponível ≥ 1 em todas as noites, o limite de overbooking conta como disponível (como na tomada da E05) e `availableUnits` é o menor disponível entre as noites; (5) `unavailable` só com `includeUnavailable=true` (senão nulo), com todos os motivos, os do `rates` antes dos do inventário (`ROOM_TYPE_UNAVAILABLE`, `INVENTORY_DATE_OUT_OF_HORIZON`, com as noites); (6) limites de entrada: até 40 adultos, 20 crianças e 50 ids, sem repetir, com o código novo `400 AVAILABILITY_QUERY_INVALID`; (7) a cotação é `GET` com parâmetros na query (nenhum dado pessoal: só datas, ocupação, canal e ids), sem `@Idempotent`; (8) acréscimos à `RatesApi` (`stayOptions` e `rateCalendar`, em bloco) em vez de chamar `isSellable` e `priceFor` por combinação, com `isSellable` e `priceFor` reimplementados sobre o mesmo caminho (mesmo resultado, provado no teste).
  - **do implementador, sem impacto fora da etapa:** ordem das ofertas (exibição do tipo, total, código do plano) e dos tipos (exibição, código); o código de lançamento do pacote não vai à resposta da cotação (o `reservations` não depende de `billing` até a E07); o tipo de UH pedido é lido por id, um a um (até 50), e não pela lista de ativos (que no banco de testes tem milhares); o day use (chegada = saída) continua `400 STAY_INVALID` na cotação (a E07 decide, H5); `AvailabilityRules`/`StayAvailability` no domínio.
- Validações executadas:
  - testes escritos antes do `reservations`: `AvailabilityApiTest` falhou inteiro (5 de 5, sem endpoint) antes da implementação; o teste novo do `rates` passou já com os acréscimos à `RatesApi`;
  - `./gradlew test --tests 'com.zeny.hotel.reservations.*' --tests 'com.zeny.hotel.rates.StayEvaluationApiTest' --tests 'com.zeny.hotel.ModularityTest'` → `AvailabilityApiTest` 5, `QuotePerformanceTest` 1, `InventoryApiTest` 17, `InventoryDomainTest` 7, `StayEvaluationApiTest` 5, `ModularityTest` 16, 0 falhas;
  - mutações aplicadas, rodadas e revertidas: a cotação ignorando a disponibilidade (`stay.available` fora da condição) → 2 testes da cotação falham; ignorando a vendabilidade (`sellable` fora) → o teste da cotação falha; o inventário em bloco lido com `outOfOrder = 0` (o OOO ignorado) → os 3 testes do calendário e da cotação falham;
  - **medição (RNF-06)**, `QuotePerformanceTest` (30 UHs em 5 tipos, 730 dias de inventário e de preços em 3 planos com preços próprios, 2 derivados — um com pacote —, estadia mínima nos fins de semana de um ano; cotação de 7 noites, 2 adultos, canal DIRECT, 5 tipos × 5 planos = 20 ofertas e 5 de fora, pela API com MockMvc, 10 de aquecimento e 60 amostras): isolado `p50=34ms p95=42ms max=47ms` e `p50=31ms p95=43ms max=46ms`; no `./gradlew build` completo (banco compartilhado com os dados de todos os testes) `p50=19ms p95=21ms max=23ms`; escala `nights1=13–21ms nights7=16–23ms nights28=26–31ms`, calendário de 31 dias com o plano `16–18ms` — bem abaixo de 300 ms; o tempo quase não cresce com as noites (sem N+1 por noite: preços, restrições e inventário são lidos por plano/período numa consulta cada);
  - `./gradlew lint` → sucesso (depois de corrigir `MaxLineLength`, `ReturnCount`, `LongMethod` e `UnusedPrivateProperty`, e `./gradlew ktlintFormat`);
  - `./gradlew updateOpenApi` → `api/openapi.yaml` com `getAvailabilityCalendar` e `quoteStay`;
  - `./gradlew build` → BUILD SUCCESSFUL em 4 min 29 s (1034 testes, 0 falhas; detekt, ktlint, `checkOpenApi` e `koverVerify`);
  - `.github/scripts/check-api-breaking.sh` → "Nenhuma mudança incompatível (nível ERR) em /api/v1";
  - `python3 ~/.claude/skills/development-roadmap/scripts/check_roadmap.py docs/roadmaps/roadmap-m4-tarifas-reservas.md` → OK.
- Parecer do verificador: **APROVADO** (verificador independente, 2026-10-21; snapshot `bfaa8b987dee70e878e4a944000b6f73e2eb9fdf`, 24 arquivos, +2965/−135). Os cinco critérios aprovados com evidência (`AvailabilityApiTest` 5/5 com os contadores dia a dia, o OOO pelo listener, a data sobrevendida e os motivos exatos das combinações de fora; a condição da oferta em `AvailabilityService.kt:201`; `QuotePerformanceTest` no build completo `p50=19ms p95=21ms max=22ms`, escala `nights1=14ms nights7=17ms nights28=26ms calendar31days=15ms`; `ModularityTest` 16/16). `./gradlew build` BUILD SUCCESSFUL em 5 min 41 s; oasdiff sem `ERR`. Foco: nenhuma oferta sem disponibilidade ou fora do canal, OOO não ignorado, sem N+1 por noite, nenhum SQL do `reservations` sobre o schema `rates`. Sem problema grave nem médio. Baixos: (1) as coleções dos planos (`findAllById`) e os tipos pedidos (`RoomsApi.roomType`, no `reservations` e no `rates`) são lidos um a um, custo proporcional aos planos e tipos, dentro do RNF-06; (2) `ratePlanIds=` vazio na URL vira conjunto vazio e a cotação sai sem ofertas, em vez de "todos os planos ativos" como `roomTypeIds` vazio (caso de borda, a alinhar quando a E07 ou a E12 mexerem na cotação); (3) a medição é no processo, pelo MockMvc, sem rede.
- Notas para a próxima etapa:
  - E07 (reservas): para conferir a estadia, use `RatesApi.isSellable`/`priceFor` (ou `stayOptions` com um plano e um tipo) e congele por noite o `NightPrice` (a cotação devolve o mesmo); a disponibilidade da cotação é leitura sem trava: a criação toma pelo `RoomTypeInventory.apply` (que recusa com `409 ROOM_TYPE_UNAVAILABLE`). Ao acrescentar `billing`, o código de lançamento dos pacotes (`PackageCharge.transactionCodeId`) pode entrar na resposta da cotação se for útil. O day use (H5) precisa de regra própria na cotação (hoje `STAY_INVALID`).
  - E12: a busca e as listas podem reusar `InventoryStore.rows(tipos, de, até)` e `StayAvailability`.
  - M8 (`distribution`): o ARI pode ler o calendário pela mesma composição (`ReservationsApi.inventory` + `RatesApi.rateCalendar`).

---

## E07 — Criação de reservas — #concluida

**Objetivo:** criar reservas e reservas agrupadoras (uma reserva por UH sob o mesmo titular e canal), com hóspede principal e acompanhantes, origem, canal, segmento e pedidos especiais; preço congelado por noite (editável com desconto sujeito a limite e step-up); política vigente copiada; consumo de inventário na mesma transação; conta da reserva aberta pela `BillingApi`; day use; evento `ReservationCreated`; e as integrações com o `guests` que o M3 deixou para o M4: listener de `GuestsMerged`, `GuestPersonalDataContributor` e `GuestActiveStayCheck` (RF-RES-01, 02, 03, 14; RN-14, RN-15; RF-CTA-01; H5, H7, H8, H12).

**Por que agora:** a API de criação é o contrato que recepção (M5), channel manager (M8) e eventos (M10) vão repetir.

**Dependências e paralelismo:** E06. Não paralelizável.

**Escopo:**

- `Booking` com código legível (`DocumentNumberGenerator` e sequence própria), titular pela `GuestsApi.guest` (`MERGED` trocado pelo sobrevivente, `ANONYMIZED` recusado, H8), origem, canal e ID externo opcional; `Reservation` com código legível, tipo, período, ocupação, plano, segmento, pedidos especiais, `StayGuest` (principal e acompanhantes), status inicial `PROVISIONAL` (sem garantia, com validade, H15) ou `CONFIRMED` (com garantia). Arestas `reservations` → `guests` e `billing` no `ReservationsModule` e no `ModularityTest`.
- `NightlyRate` por noite a partir de `priceFor`, com `overridden` quando editado; desconto acima do limite (H7) por `authorization.requireOrApproval(SensitiveAction(ação, permissão do desconto, resourceRef, limit = LimitAmount("maxPercent", desconto)))`.
- Cópia das políticas de cancelamento e garantia vigentes na reserva (RN-15).
- Inventário consumido pelo serviço de E05 na mesma transação; falta de disponibilidade → `409` com `code` estável e nada gravado.
- Conta da reserva aberta pela `BillingApi.openFolio(NewFolio(FolioOwnerType.RESERVATION, id da reserva))` na mesma transação.
- Alerta de lista de restrição (`GuestsApi.restrictionAlert`) devolvido na resposta; não bloqueia.
- Day use com a regra de H5.
- `@ApplicationModuleListener` de `GuestsMerged` (primeiro de produção) que reponta titular e hóspedes da estadia, idempotente.
- `GuestPersonalDataContributor` (seção `reservations`) e `GuestActiveStayCheck` (H12) como beans do `reservations`.
- Evento `ReservationCreated` (`reservations/ReservationsEvents.kt`, só ids, datas e situação) testado com listener de teste; `@Idempotent` no POST; auditoria sem pedidos especiais nem dado pessoal; permissões (H16).

**Fora de escopo:** alteração e cancelamento (E08); garantia e expiração (E09); alocação (E10); bloqueios (E11).

**Passos de implementação:**

1. Testes de integração: reserva simples e agrupadora; preço congelado após mudar a tarifa; desconto acima do limite com e sem aprovação; sem disponibilidade; conta aberta; evento pelo `Scenario`; idempotência; day use; `GuestsMerged` pelo listener (chamado sem o proxy, `AopTestUtils.getTargetObject`, e pela reentrega); hóspede mesclado e anonimizado na criação; anonimização recusada com estadia ativa e exportação com a seção `reservations` (pela API do `guests`, com aprovação).
2. Implementar migração, domínio, casos de uso e endpoints em `/api/v1/bookings` e `/api/v1/reservations`; regenerar o contrato.
3. Documentar a API pública de criação no `CLAUDE.md` para M5, M8 e M10.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "o que SPEC §5.7 (RF-RES-01 a 03, 08, 14), §8.2.6, §8.4 (reserva), RN-14, RN-15 e RF-CTA-01 definem para a criação?"; "como o `guests` usa as portas `GuestPersonalDataContributor` e `GuestActiveStayCheck` (`GuestDataSubjectService.kt`) e como o `GuestPrivacyApiTest` as testa?".
- Partes paralelas: nenhuma.
- Foco do verificador: reserva criada sem consumir inventário ou consumindo fora da transação; conta não aberta ou aberta duas vezes em retentativa; preço lido da tarifa atual em vez do congelado; desconto acima do limite sem aprovação; POST repetido criando duas reservas; listener de `GuestsMerged` não idempotente ou corrida com uma criação em andamento; dado pessoal ou pedido especial na trilha, no evento ou no log.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E07 — "Criação de reservas" do roadmap docs/roadmaps/roadmap-m4-tarifas-reservas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo restrições, as hipóteses H5, H7, H8, H12, H15 e H16 e o "Protocolo de execução" —, a seção E07 inteira e o "Evidências e handoff" de E04, E05 e E06. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E06 está #concluida e que o código bate com o handoff dela, e que H5, H7 e H12 já foram decididas (se ainda disserem "a confirmar pelo usuário", pare e peça a decisão); se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: em reservations, criar Booking e reservas (uma por UH) com titular e acompanhantes pela GuestsApi, origem, canal, segmento e pedidos especiais; preço congelado por noite a partir de priceFor, editável com desconto sujeito ao limite maxPercent e a step-up (requireOrApproval); cópia das políticas vigentes; consumo de inventário na mesma transação (409 sem disponibilidade); conta da reserva aberta pela BillingApi.openFolio com FolioOwnerType.RESERVATION; alerta de lista de restrição; day use (H5); o listener de produção de GuestsMerged; as portas GuestPersonalDataContributor e GuestActiveStayCheck (H12); evento ReservationCreated; @Idempotent. Não implemente alteração, cancelamento, garantia, alocação nem bloqueios.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E07 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume SPEC RF-RES-01 a 03, 08 e 14, §8.2.6, §8.4, RN-14, RN-15 e RF-CTA-01 e como o guests usa e testa as portas de privacidade;
- escreva os testes de integração primeiro (eventos com @EnableScenarios e listener de teste), implemente, atualize ModularityTest, ARCHITECTURE §3.1 se mudar, CLAUDE.md (API pública de criação) e README.md, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em inventário fora da transação, conta duplicada em retentativa, preço não congelado, desconto sem aprovação, POST repetido, listener de GuestsMerged e dado pessoal na trilha.

Critérios de aceite (detalhes na seção E07):
- reserva simples e agrupadora criadas com conta aberta, inventário consumido e ReservationCreated no outbox;
- mudar a tarifa depois não altera os NightlyRate da reserva;
- desconto acima do limite sem aprovação → 403 APPROVAL_REQUIRED; sem disponibilidade → 409;
- repetição com a mesma Idempotency-Key não cria segunda reserva nem segunda conta;
- GuestsMerged reponta os hóspedes; anonimização recusada com estadia ativa; exportação com a seção reservations; build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E07 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências, incluindo os pontos para a entrevista da E07. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [x] Reserva simples e reserva agrupadora com duas UHs criadas: inventário consumido, conta da reserva aberta em `billing` (`FolioOwner(RESERVATION, id)`), `ReservationCreated` no outbox (com listener de teste), tudo na mesma transação (teste de integração).
- [x] Alterar a tarifa do plano depois da criação não muda os `NightlyRate` da reserva.
- [x] Políticas de cancelamento e garantia vigentes copiadas na reserva; editar a política depois não altera a reserva.
- [x] Desconto acima do limite sem permissão nem aprovação → `403` com `APPROVAL_REQUIRED` (com `limit.key` `maxPercent`); com aprovação, auditado com solicitante e aprovador.
- [x] Sem disponibilidade → `409` com `code` estável e nada gravado (nem conta).
- [x] Repetição com a mesma `Idempotency-Key` não cria segunda reserva nem segunda conta.
- [x] Hóspede na lista de restrição gera alerta na resposta; hóspede mesclado vira o sobrevivente e anonimizado é recusado; `GuestsMerged` reponta titular e hóspedes da estadia, e a reentrega não muda nada.
- [x] `GuestActiveStayCheck` recusa a anonimização (`409 GUEST_HAS_ACTIVE_STAY`) de hóspede com reserva ativa (H12); a exportação do titular traz a seção `reservations`.
- [x] Day use criado segundo H5, com a regra registrada no handoff.
- [x] Trilha, evento e log sem pedidos especiais nem dado pessoal; API pública de criação documentada no `CLAUDE.md`; `ModularityTest` atualizado; contrato regenerado; `./gradlew build` e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Agregado grande (booking com várias reservas e noites): manter `Reservation` como agregado próprio e `Booking` leve; registrar a decisão.
- Corrida entre uma mesclagem e uma criação com o perfil absorvido: o listener roda depois do commit da mesclagem e pode ver antes a reserva ainda não confirmada; registrar a mitigação escolhida (ex.: a criação trava ou relê o perfil, ou uma varredura idempotente pelo `mergedInto`).

**Evidências e handoff:**

- Snapshot inicial: `e0aea76e205ca20b40ab4ceeaf7ae3a48a0ee413` (recebido do orquestrador). Snapshot ao fim da implementação (antes deste handoff): `fd27da2412550f498754f672a76d02c32aac34ea`. Pré-requisitos conferidos: E06 `#concluida` (commit `45e1d26`) e o código bate com o handoff dela (`AvailabilityService`, `RatesApi.stayOptions`/`rateCalendar`, `RoomTypeInventory.apply`, `InventoryUsageSource` sem implementação); H5, H7 e H12 decididas pelo usuário em 2026-10-20; a ARCHITECTURE §3.1 já tinha `reservations` → `guests` e `billing` (sem mudança). Investigação feita pela própria sessão (adendo do orquestrador): SPEC RF-RES-01 a 03, 08 e 14, §8.2.6 (`Booking`, `Reservation`, `StayGuest`, `NightlyRate`), §8.4 (criada sem garantia → pré-reserva; com garantia → confirmada), RN-14, RN-15 e RF-CTA-01 (a conta existe desde a criação); `GuestDataSubjectService` (contribuidores por `ObjectProvider`, seção nula omitida; `GuestActiveStayCheck` chamado na transação da anonimização, depois de travar os perfis) e `GuestPrivacyApiTest` (contribuidor e checagem de teste).
- Arquivos alterados:
  - novos (`reservations`): `Bookings.kt` (ids, `ReservationStatus`, `ReservationOrigin` com o canal, `MarketSegment`, `NewBooking`/`NewReservation`/`NewStayGuest`/`NightlyRateOverride`, `BookingSummary`, `ReservationSummary`, `NightlyRateSummary`, `CancellationTerms`, `GuaranteeTerms`), `domain/Booking.kt`, `application/BookingPorts.kt`, `BookingCreation.kt`, `BookingService.kt`, `infrastructure/JdbcBookingStore.kt`, `web/BookingsController.kt`; migração `reservations/V2026_10_21_0520__create_bookings.sql`;
  - alterados (`reservations`): `ReservationsApi.kt` (`createBooking`, `booking`, `reservation`), `ReservationsEvents.kt` (`ReservationCreated`), `ReservationsErrorCodes.kt` (seção E07), `ReservationsPermissions.kt` (`reservations.reservations.read`/`manage`), `ReservationsModule.kt` (`guests`, `billing`), `application/InventoryService.kt` (`ReservationsApiService`), `RoomTypeInventory.kt` (`ReservationsAudit` com as ações e os retratos da reserva), `AvailabilityService.kt` (a cotação recusa o day use explicitamente), `web/InventoryController.kt` (descrição da tag);
  - `guests` (correção da 1ª verificação): `application/GuestsPorts.kt` (`freshGuest`), `infrastructure/GuestsPersistence.kt`, `application/GuestsApiService.kt` (leituras frescas);
  - `rates` (day use, sem quebrar nada): `StayPricing.kt` (`StayQuery.dayUse`), `domain/StayEvaluation.kt` (`StayRules.nights` e `lastPricedDate`, formato aceita chegada = saída, estadia mínima e máxima fora do day use), `application/StayEvaluator.kt`, `RatesApi.kt` (KDoc);
  - testes novos: `reservations/BookingDomainTest.kt`, `BookingsApiTest.kt`, `BookingGuestsApiTest.kt`, `ReservationsTestSupport.kt`; alterados: `reservations/InventoryTestSupport.kt` (o `ReservationCreatedProbeListener` no `InventoryTestConfiguration`), `ModularityTest.kt`, `iam/AuthorizationTest.kt` (modelo Gerente), `rates/StayEvaluationDomainTest.kt` (chegada = saída deixou de ser inválida; dois testes de day use), `rates/StayEvaluationApiTest.kt` (estadia inválida = saída antes da chegada);
  - `api/openapi.yaml` (regenerado: `createBooking`, `getBooking`, `getReservation`), `CLAUDE.md` (estado atual, seção "Reservas e reservas agrupadoras", listeners, recontagem), `README.md` (API e árvore), este roadmap.
- Decisões:
  - **já dadas:** H5 (day use: chegada = saída, não toma o contador de pernoite, plano com tarifa própria), H7 (`rates.discounts.apply`, sensível, `maxPercent`; Gerente sem teto, Recepcionista 10%), H8 (mesclado → sobrevivente, anonimizado recusado, listener idempotente), H12 (estadia ativa = `PROVISIONAL`/`CONFIRMED`/`IN_HOUSE` como titular ou hóspede; seção `reservations` da exportação), H16 (nomes `reservations.reservations.read`/`manage`), H14 (sem offline nem SSE).
  - **do implementador, pela opção recomendada em nome do usuário (delegado ao orquestrador); confirmadas pelo orquestrador em 2026-10-21 na revisão humana delegada** (o usuário, ausente, delegou a escolha da opção recomendada depois de pesar prós e contras; todas mantidas como implementadas, inclusive os itens de revisão humana: a mitigação da corrida com a mesclagem pela trava consultiva por hóspede com a leitura fresca, a aprovação do desconto vinculada a `reservations.booking:new:<titular>` e os pedidos especiais na resposta HTTP e na exportação, nunca na trilha, nos eventos ou no log):
    1. **Segmentos:** lista fixa `MarketSegment` (`LEISURE`, `BUSINESS`, `EVENT`, `OTHER`; os três da SPEC mais "outro"), `x-extensible-enum` na resposta, obrigatório por reserva. Contra o cadastrável: sem CRUD nem permissão nova; um valor novo não quebra o contrato.
    2. **Conta da reserva agrupadora:** **sem conta própria** do booking; cada reserva tem a sua (RF-CTA-01). Para "a empresa paga tudo", roteamento para a conta de uma das reservas (ou a conta master do grupo, E11). Evita um dono novo em `FolioOwnerType` e a conta vazia.
    3. **Day use na cotação:** a cotação **continua recusando** chegada = saída (`400 STAY_INVALID`, campo `departure`): o day use não toma inventário e a disponibilidade dele depende da alocação (E10); o preço da data já está no calendário. A criação aceita e precifica pela tarifa da data (o `rates` passou a aceitar o day use em `priceFor`/`isSellable`/`stayOptions`: o fechado, o CTA e o CTD da data e a antecedência valem; a estadia mínima e a máxima, não).
    4. **Situação inicial pela política de garantia:** `NONE` → `CONFIRMED`; `CARD`/`DEPOSIT` → `PROVISIONAL` com `provisionalUntil` = criação + `preReservationExpiry` (sem campo no pedido para forçar a pré-reserva).
    5. **Origem define o canal** (`ReservationOrigin.channel`: balcão, telefone, e-mail, WhatsApp e outros → `DIRECT`; `WEBSITE`; `OTA`), sem `channel` no pedido; `x-extensible-enum` (o M8 pode acrescentar origens por OTA).
    6. **Diária editável = a diária da noite** (`roomAmount`, com os inclusos dentro); os adicionais ficam como calculados; abaixo dos inclusos `422 NIGHTLY_RATE_BELOW_INCLUDED_PACKAGES`; acima da calculada não é desconto. Desconto em percentual da calculada com 4 casas **arredondado para cima** (conservador para o teto), conferido **uma vez** pelo maior desconto da agrupadora, com `resourceRef` `reservations.booking:new:<titular>` (a chave natural da criação; a aprovação vale para o mesmo solicitante, sessão, ação e valor, uma vez, por 2 min).
    7. **Um só endpoint de criação** (`POST /api/v1/bookings`, com 1 a 30 reservas; a reserva simples é a agrupadora com uma), e as consultas `GET /bookings/{id}` e `GET /reservations/{id}`; sem listagem (E12).
    8. **`ReservationsApi.createBooking` sem permissão nem step-up do desconto** (o channel manager grava a diária da OTA; quem chama se autoriza), auditada como a HTTP.
    9. **Corrida com a mesclagem e a anonimização** (risco da E07): trava consultiva por hóspede, compartilhada na criação (depois de descobrir a cadeia de mesclagens, com releitura; mudança no meio `409 GUEST_PROFILE_CHANGED`) e exclusiva no listener e na checagem de estadia ativa. Alternativas descartadas: varredura periódica pelo `mergedInto` (janela maior) e travar as linhas do `guests` (outro schema).
    10. **Listener:** reponta para o sobrevivente **final** (segue `mergedInto`); reserva com os dois perfis fica com um só (principal se algum era); avança as versões; ator sistema; sem mudança, nada na trilha.
    11. **Exportação:** a seção `reservations` traz, por reserva, código, agrupadora, papel (`HOLDER`/`PRIMARY_GUEST`/`GUEST`), situação, datas, tipo e plano (ids e nomes), ocupação, segmento, pedidos especiais, noites e total; `null` sem reservas.
    12. **Alertas da lista de restrição** lidos na hora, em toda resposta (criação e consultas), não gravados.
    13. **Evento `ReservationCreated`** um por reserva, com origem, canal, contagens e `dayUse`; sem hóspede nem preço.
    14. Persistência por `JdbcClient` (`Reservation` como agregado próprio, `Booking` leve; noites e hóspedes num comando cada, por `jsonb_to_recordset`); bookings e reservas nunca apagados (trigger); `nightly_rate` e `reservation_guest` mutáveis (a E08 refaz as noites; o listener mexe nos hóspedes).
- Validações executadas:
  - testes escritos antes da implementação dos casos de uso (a primeira rodada, com a implementação, falhou em 4: a mesclagem recusada por CPFs diferentes nos hóspedes de teste, o `limit.amount` sem zeros e a estadia de zero noite do `rates`, corrigidos nos testes e no apoio);
  - `./gradlew test --tests 'com.zeny.hotel.reservations.Booking*' --tests 'com.zeny.hotel.rates.StayEvaluationApiTest'` → `BookingDomainTest` 8, `BookingGuestsApiTest` 5, `BookingsApiTest` 13, `StayEvaluationApiTest` 5, 0 falhas;
  - mutações aplicadas, rodadas e revertidas: sem a tomada de inventário → 6 testes do `BookingsApiTest` falham (simples, agrupadora, sem unidade, concorrência, idempotência, day use); o step-up do desconto pulado → o teste do desconto falha; a trava do listener compartilhada em vez de exclusiva → o teste "the repointing waits for a reservation being created…" falha;
  - `./gradlew lint` → sucesso (depois de `ktlintFormat` e de corrigir `MaxLineLength`, `LongMethod`, `ThrowsCount`, `ReturnCount`, `SwallowedException`, `UnsafeCallOnNullableType`, `TooManyFunctions`);
  - `./gradlew updateOpenApi` → `createBooking`, `getBooking`, `getReservation`; `./gradlew checkOpenApi` → em dia;
  - `./gradlew build` → BUILD SUCCESSFUL em 4 min 43 s (1061 testes, 0 falhas; detekt, ktlint, `checkOpenApi` e `koverVerify`);
  - `.github/scripts/check-api-breaking.sh` → "Nenhuma mudança incompatível (nível ERR) em /api/v1".
- Parecer do verificador: 1ª verificação independente **REPROVADO** (orquestrador, 2026-10-21):
  - **(grave)** a releitura do hóspede depois da trava lia a cópia do contexto de persistência: `GuestsApi.guest` usava `findById`, que na segunda leitura da mesma transação não vai ao banco. Por isso a anonimização ou a mesclagem confirmada entre a descoberta e a trava não era vista. O verificador reproduziu `first=ACTIVE db=ANONYMIZED resolve={A=A}`;
  - o teste da trava exercitava só a trava consultiva isolada, e o caminho `GUEST_PROFILE_CHANGED` não tinha teste;
  - **(menor)** o `CLAUDE.md` ainda dizia "noites (1 a 365)" na `StayQuery`.
- **Correção da 1ª verificação** (snapshot depois da correção, antes deste registro: `08ecf32fa6942afe1b84df4346525fd84dd59d1b`):
  - **leitura fresca na `GuestsApi`, sem mudar o contrato.** Novo `GuestsStore.freshGuest(id)` em `JpaGuestsStore`, que faz `findById` seguido de `entityManager.refresh`. `GuestsApiService.guest` e `current` (usado por `restrictionAlert`, `isMinorOn` e `fnrhData`) passaram a usá-lo, com a regra documentada na `GuestsApi`/`GuestsStore` e no `CLAUDE.md`. Escolhida em vez de "travar cada id antes da primeira leitura" porque descobrir a cadeia de mesclagens exige ler antes de saber o que travar. Travar o sobrevivente depois, fora de ordem, daria deadlock com a anonimização, que trava a cadeia em ordem. Com a leitura fresca, a sequência continua descobrir, travar tudo em ordem e reler, e a releitura vê o que foi confirmado. Nenhum código do `guests` chama a `GuestsApi` (o `refresh` descartaria mudanças não enviadas de quem alterou o perfil na mesma transação);
  - **testes determinísticos novos** em `BookingGuestsApiTest`:
    - (1) a transação da criação já leu o perfil, a anonimização confirma noutra thread, e `ReservationsApi.createBooking` termina com `422 GUEST_ANONYMIZED`, sem reserva;
    - (2) idem com a mesclagem: a reserva fica com o sobrevivente;
    - (3) outra transação segura a trava exclusiva do absorvido (como o listener), a criação descobre o perfil ativo e para na trava (conferido em `pg_locks` pela chave consultiva do hóspede), a mesclagem confirma, a trava sai, e a criação termina com `409 GUEST_PROFILE_CHANGED`, sem reserva;
  - **mutação:** `GuestsApiService` voltando a `store.guest` (sem `refresh`) faz os 3 testes falharem; com a correção, passam;
  - `CLAUDE.md`: a `StayQuery` com o day use (chegada = saída), a leitura fresca na `GuestsApi` e na seção das reservas;
  - **registros pedidos pelo orquestrador** (sem mudar código):
    - (a) day use: qualquer plano vende day use pela diária da data, e a quantidade só é controlada na alocação (E10). É decisão do implementador, aceita pelo orquestrador: o "plano com tarifa própria" da H5 é o plano que o operador escolhe para o day use (o preço dele na data). Evolução possível: uma marca no plano que restrinja o day use;
    - (b) nota para o M8: `ReservationsApi.createBooking` não tem idempotência nem `external_id` único; o channel manager vai precisar dos dois (por exemplo, unicidade de origem + `external_id` e a criação idempotente por ele);
    - (c) o `externalId` vai à trilha, porque não é dado pessoal (é o número da reserva na OTA);
  - validações:
    - `./gradlew test --tests 'com.zeny.hotel.reservations.BookingGuestsApiTest'` → 8 testes, 0 falhas;
    - `./gradlew lint` → sucesso;
    - `./gradlew build` → BUILD SUCCESSFUL em 5 min 42 s (1064 testes, 0 falhas);
    - `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR`; contrato sem mudança nesta correção.
  - 2ª verificação independente **APROVADO** (orquestrador, 2026-10-21; snapshot `57c8c7ba825a04810364d78bf5b98e61f6aaf00e`, 37 arquivos desde o inicial): os dez critérios aprovados com evidência; a leitura fresca (`GuestsStore.freshGuest`) fecha as corridas com a anonimização (antes e depois da trava compartilhada) e com a mesclagem (entre a descoberta e a trava, `409 GUEST_PROFILE_CHANGED`; depois da releitura, o listener espera e reponta), sem deadlock; o `refresh` não descarta mudança pendente de nenhum chamador atual (só `reservations` usa `guest`; o `billing` só `company`; nada dentro do `guests` chama a `GuestsApiService`); a mutação sem a leitura fresca faz falhar exatamente os três testes novos. `./gradlew build` BUILD SUCCESSFUL em 5 min 42 s (1064 testes, 0 falhas); oasdiff sem `ERR`. Sem problema grave. Menores: (1) **corrida residual numa cadeia de mesclagens**: `GuestReferenceRepointing.repoint` calcula o perfil final antes de travar e trava só o absorvido; com A→B e B→C quase juntos, o listener de A pode repontar para B já mesclado, sem reparo posterior (janela estreita; correção: calcular o final depois da trava e travar também o alvo, em ordem) — **encaminhada à E08**; (2) colisão de `hashtext` (32 bits) nas travas por hóspede poderia, em tese, inverter a ordem e dar um deadlock que o PostgreSQL aborta (probabilidade desprezível; aceita); (3) a regra "não chame a `GuestsApi` numa transação que alterou o perfil" (o `refresh` descartaria a mudança) é só documental (aceita; KDoc e `CLAUDE.md`).
- Notas para a próxima etapa:
  - **E08, pendência da verificação da E07:** corrija a corrida residual do listener de `GuestsMerged` numa cadeia (A→B e B→C quase juntos): em `GuestReferenceRepointing.repoint`, calcule o perfil final **depois** de travar e trave também o alvo (as travas por hóspede em ordem estável), com um teste determinístico da cadeia.
  - E08 (alteração e cancelamento): os termos copiados estão na reserva (`cancellation_*`, `guarantee_*`); refaça as noites (`nightly_rate`) com o histórico e tome/devolva o inventário numa só `RoomTypeInventory.apply` (devolver as datas antigas e tomar as novas); a multa vai à conta da reserva (`folio_id`); o day use não tem unidade a devolver. `priceFor` não confere plano ativo (para reprecificar um plano desativado).
  - E09: a pré-reserva já nasce com `provisional_until`; a confirmação muda `status` e limpa a validade (o `CHECK reservation_provisional_ck` exige os dois juntos); o job de expiração devolve a unidade.
  - E10: o day use usa `[data, data]` na alocação (H5); a reserva não tem alocação ainda.
  - E11: estenda o listener de `GuestsMerged` ao titular do bloqueio (o `BookingStore.repointGuest` e a trava por hóspede); o pick-up chama a criação com o bloqueio (devolver `BLOCKED` e tomar `SOLD` na mesma `apply`).
  - E12: busca e listas sobre `reservations.reservation`/`booking` (índices por tipo e período e por titular já existem); views `report_*` sem pedidos especiais.
  - Testes: hóspedes de teste sem documentos (`ReservationsTestSupport.guest()`), para a mesclagem não esbarrar em CPFs diferentes; o `ReservationCreatedProbeListener` está no `InventoryTestConfiguration` (o mesmo contexto dos testes do inventário); a recontagem do `BookingsApiTest` usa datas a 640 dias (nenhum outro teste as usa).

---

## E08 — Alteração, cancelamento e reativação — #concluida

**Objetivo:** alterar datas, tipo, ocupação ou plano com recálculo de preço, ajuste de inventário e histórico; cancelar aplicando a política copiada (multa lançada na conta pela `BillingApi` ou isentada com aprovação); reativar reserva cancelada ou no-show como ação sensível (RF-RES-05, RF-RES-06, SPEC §8.4, H6).

**Por que agora:** completa o ciclo de vida da reserva antes do check-in (M5); o channel manager (M8) usa as mesmas operações.

**Dependências e paralelismo:** E07. Pode correr em paralelo com E09, E10 e E11 (em worktrees separados); conflito de edição no agregado `Reservation`, em `api/openapi.yaml` e `CLAUDE.md` — preferir executar em sequência.

**Escopo:**

- Alteração com `If-Match`: recalcula `NightlyRate` só das noites afetadas (noites mantidas preservam o preço congelado; RN-14 "na criação ou alteração"), ajusta inventário com lock (libera e consome na mesma transação), guarda o histórico da alteração (tabela *append-only* num `AppendOnlyTables`); evento `ReservationChanged`.
- Cancelamento (`@Idempotent`): calcula a multa pela política copiada e pelo prazo (H15); lança na conta da reserva pela `BillingApi.post` com `transactionCode(TransactionCodeKey.CANCELLATION_FEE)` e a origem da H6, na mesma transação, ou isenta por `requireOrApproval` (permissão sensível de isenção, H16); libera inventário; evento `ReservationCancelled` com o motivo (`x-extensible-enum` na resposta, se a lista vai crescer).
- Reativação de `CANCELLED` ou `NO_SHOW` para `CONFIRMED` (`@Idempotent`): ação sensível por `requireOrApproval`, consome inventário de novo (falha sem disponibilidade, nada muda).
- Transições inválidas pela máquina de estados (ex.: alterar reserva `CHECKED_OUT`) → `409`.

**Fora de escopo:** no-show automático e a multa de no-show (M6); devolução de depósito (reembolso manual sensível do M3, operado pela recepção).

**Passos de implementação:**

1. Testes de domínio da máquina de estados e do cálculo de multa; testes de integração de alteração, cancelamento com multa e com isenção (step-up com PIN e sessão fixa), reativação com e sem disponibilidade, multa e cancelamento tudo ou nada.
2. Implementar casos de uso e endpoints de ação (`POST /api/v1/reservations/{id}/cancel`, `/reactivate`, `PUT` de alteração); regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em RF-RES-05, RF-RES-06, RF-TAR-07, §8.4 e nos handoffs de E05 e E07.
- Partes paralelas: nenhuma.
- Foco do verificador: alteração que reprecifica noites não afetadas; inventário liberado e não reconsumido (ou vice-versa) em falha parcial; multa calculada pela política atual em vez da copiada; isenção sem aprovação; multa lançada duas vezes em retentativa; multa fora da conta da reserva ou com o código errado; motivo de cancelamento (texto livre) na trilha.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E08 — "Alteração, cancelamento e reativação" do roadmap docs/roadmaps/roadmap-m4-tarifas-reservas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo as hipóteses H6, H15 e H16 e o "Protocolo de execução" —, a seção E08 inteira, o "Evidências e handoff" de E05 e E07 e RF-RES-05, RF-RES-06, RF-TAR-07 e a máquina de estados da reserva em docs/SPEC.md §8.4. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E07 está #concluida e que o código bate com o handoff dela, e que a H6 já foi decidida (se ainda disser "a confirmar pelo usuário", pare e peça a decisão); se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: em reservations, alterar datas, tipo, ocupação ou plano recalculando só as noites afetadas e ajustando o inventário com lock na mesma transação, com histórico append-only e ReservationChanged; cancelar aplicando a política copiada na reserva, lançando a multa na conta da reserva pela BillingApi.post com TransactionCodeKey.CANCELLATION_FEE ou isentando com aprovação (requireOrApproval), liberando o inventário e publicando ReservationCancelled; reativar reserva cancelada ou no-show como ação sensível. Não implemente no-show automático nem devolução de depósito.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E08 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de domínio e de integração primeiro, implemente, atualize CLAUDE.md e README.md, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em reprecificação de noites não afetadas, inventário inconsistente em falha, multa pela política atual, isenção sem aprovação e multa duplicada.

Critérios de aceite (detalhes na seção E08):
- alteração recalcula só as noites afetadas e ajusta o inventário; histórico registrado;
- cancelamento lança a multa da política copiada uma única vez, na conta da reserva; isenção sem aprovação → 403 APPROVAL_REQUIRED;
- reativação sem aprovação → 403; sem disponibilidade → 409;
- transição inválida → 409 com code estável; build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E08 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências, incluindo os pontos para a entrevista da E08. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [x] Estender uma reserva em uma noite mantém o preço das noites originais e precifica só a nova; inventário da nova data consumido (teste de integração).
- [x] Mudar o tipo de UH libera o inventário do tipo antigo e consome o do novo na mesma transação; sem disponibilidade no novo, nada muda.
- [x] Cancelamento dentro do prazo de multa lança, na conta da reserva e no código `CANCELLATION_FEE`, a multa calculada pela política copiada; repetição com a mesma `Idempotency-Key` não lança de novo; uma falha simulada depois do lançamento desfaz tudo.
- [x] Isenção de multa sem permissão nem aprovação → `403` com `APPROVAL_REQUIRED`; com aprovação, auditada com solicitante e aprovador.
- [x] Reativação sem aprovação → `403`; sem disponibilidade → `409`; com ambos, reserva `CONFIRMED` e inventário consumido.
- [x] Transições fora da SPEC §8.4 → `409` com `code` estável.
- [x] `ReservationChanged` e `ReservationCancelled` no outbox (com listener de teste); histórico *append-only*; contrato regenerado; `./gradlew build` e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Lançar multa em `billing` e cancelar na mesma transação: ambos no mesmo processo e banco; teste de falha simulada confirma o tudo ou nada.
- Conta da reserva cancelada: encerrar ou não fica para a entrevista da E08 (a `BillingApi.closeFolio` só encerra com saldo zero e sem caução ativa).

**Evidências e handoff:**

- Snapshot inicial: `a503f6f568edfb276e039086bcf4200de2dcda5c` (recebido do orquestrador). Snapshot ao fim da implementação (antes deste handoff): `ec11f58a97f2867f6e04f97c56f9985a2da14fae`. Pré-requisitos conferidos: E07 `#concluida` (commit `b258ad0`) e o código bate com o handoff dela (`BookingCreation`, `GuestResolution`, `GuestReferenceRepointing`, `JdbcBookingStore` com `nightly_rate` e `reservation_guest` mutáveis, termos copiados em `cancellation_*`/`guarantee_*`, `folio_id`, `RoomTypeInventory.apply`); H6 decidida pelo usuário em 2026-10-20 (`CANCELLATION_FEE`, `PostingSource.MANUAL`). Sem investigação delegada (adendo do orquestrador): SPEC RF-RES-05, RF-RES-06, RF-TAR-07, RN-14, RN-15 e a máquina de estados da reserva (§8.4) lidas pela própria sessão.
- Arquivos alterados:
  - novos (`reservations`): `ReservationChanges.kt` (`ReservationChange`, `CancellationReason`, `ReservationCancellationRequest`, `CancellationPenalty`, `ReservationCancellation`, `ReservationHistoryId`, `ReservationHistoryKind`, `HistoryNight`, `HistoryCancellation`, `ReservationState`, `ReservationHistoryEntry`), `domain/ReservationLifecycle.kt` (`ReservationAction`, `ReservationTransitions`, `ReservationTransitionNotAllowedException`, `StayShape`, `StayRepricing`, `ReservationFields`, `ReservationChangeRules`, `CancellationRules`, `CancellationPenalties`, `InventoryNights`), `application/ReservationLifecycle.kt` (`WaiverAuthorization`, `ReservationViews`, `ReservationLifecycle`, `ReservationLifecycleService`), `web/ReservationLifecycleDtos.kt`; migração `reservations/V2026_10_21_0640__add_reservation_lifecycle.sql` (colunas do cancelamento e `penalties_charged` com `CHECK`, `reservation_penalty_posting_uk`, tabela `reservation_history` *append-only* com trigger e `REVOKE`);
  - alterados (`reservations`): `Bookings.kt` (`ReservationSummary.cancellation` e `penaltiesCharged`), `ReservationsEvents.kt` (`ReservationChanged`, `ReservationCancelled`, `ReservationReactivated`), `ReservationsErrorCodes.kt` (seção E08), `ReservationsPermissions.kt` (`reservations.penalties.waive` e `reservations.reservations.reactivate`, sensíveis; descrição de `manage`), `ReservationsApi.kt` (`cancelReservation`), `application/BookingPorts.kt` (`reservationForUpdate`, `update`, `insertHistory`, `history`), `BookingService.kt` (`ReservationViews`; **correção da corrida da cadeia** no `GuestReferenceRepointing` e no `GuestsMergedListener`), `InventoryService.kt` (`ReservationsApiService.cancelReservation`), `RoomTypeInventory.kt` (`ReservationsAudit`: ações novas, o cancelamento no retrato e `state`), `domain/Booking.kt` (`NightlyRates.freeze` sobre noites avulsas; `StayNotSellableException.reservationIndex` anulável), `infrastructure/JdbcBookingStore.kt` (leitura do cancelamento, `reservationForUpdate`, `update` com a versão, histórico com `StoredState`, bean `ReservationsTablesConfiguration`), `web/BookingsController.kt` (`ReservationsController`: `PUT /{id}`, `POST /{id}/cancel`, `/reactivate`, `GET /{id}/history`; `CancellationResponse` e `penaltiesCharged` na resposta);
  - testes novos: `reservations/ReservationLifecycleDomainTest.kt` (10), `ReservationLifecycleApiTest.kt` (14); alterados: `ReservationsTestSupport.kt` (`ReservationLifecycleProbeListener`, `changeBody`, `change`, `cancel`, `reactivate`, `etag`, `postings`, `status`, `historyKinds`, `lifecyclePublications`; `plan(cancellationBody)`), `InventoryTestSupport.kt` (o listener de teste no `InventoryTestConfiguration`), `BookingGuestsApiTest.kt` (o teste da cadeia A → B → C), `iam/AuthorizationTest.kt` (modelo Gerente com as duas permissões novas);
  - `api/openapi.yaml` (regenerado: `changeReservation`, `cancelReservation`, `reactivateReservation`, `listReservationHistory`; `ReservationResponse` com `cancellation` e `penaltiesCharged`), `CLAUDE.md` (estado atual, seção nova "Alteração, cancelamento e reativação de reservas", as travas do listener de `GuestsMerged`, o `AppendOnlyTables` do `reservations`), `README.md` (API e árvore), este roadmap.
- Decisões:
  - **já dadas:** H6 (multa pela `BillingApi.post` com `CANCELLATION_FEE`, origem `MANUAL`, na data operacional e na mesma transação; devolução de depósito pelo reembolso manual do M3), H15 (prazo da multa até o horário de check-in da data de chegada, no fuso do hotel), H16 (nomes `reservations.penalties.waive` e `reservations.reservations.reactivate`, sensíveis, só o Gerente no modelo; alterar e cancelar com `reservations.reservations.manage`).
  - **do implementador, pela opção recomendada em nome do usuário (delegado ao orquestrador); confirmadas pelo orquestrador em 2026-10-21** (o usuário, ausente, delegou a escolha da opção recomendada depois de pesar prós e contras; todas mantidas como implementadas):
    1. **Conta da reserva cancelada** (decisão aberta da E08): fica **aberta**, sem `BillingApi.closeFolio`. Prós: o reembolso de depósito do M3 e um estorno da multa precisam da conta aberta, e a reativação continua com ela (a reabertura é ação sensível só pela API HTTP do `billing`); contra: a conta vazia de uma cancelada fica aberta até alguém encerrá-la (o fechamento do dia do M6 pode listar/encerrar as de saldo zero).
    2. **Antecedência mínima para alterar** (decisão aberta da E08): **nenhuma**; vale a máquina de estados (só `PROVISIONAL`/`CONFIRMED`) e, com a chegada mudada, a chegada nova não antes da data operacional (o `ARRIVAL_BEFORE_BUSINESS_DATE` do `rates`) e a antecedência do plano. Prós: sem parâmetro novo no `hotel`; a recepção estende ou corrige no próprio dia; contra: nenhuma trava operacional configurável (evolução: parâmetro em `operational-settings`).
    3. **Noites recalculadas:** tipo, plano, ocupação ou pernoite ↔ day use trocados recalculam todas; só as datas, só as novas (mais a noite da chegada antiga e a da nova quando a noite da chegada antiga tinha pacote por estadia); as mantidas guardam o `NightlyRate` (inclusive a diária editada e o desconto aprovado). As afetadas são precificadas por sequência contínua, sem o pacote por estadia fora da chegada.
    4. **Venda na alteração:** `isSellable` da estadia nova no canal da agrupadora, só com os motivos do que mudou (um plano desativado depois não impede mudar a ocupação; uma antecedência mínima não impede estender a saída; uma noite mantida que ficou sem preço ou fechada não recusa). Trocar para outro plano é uma venda nova dele.
    5. **Plano novo copia as políticas vigentes dele** (RN-15 aplicada à alteração, como "o momento da reserva" do plano novo) e a pré-reserva num plano de garantia `NONE` vira `CONFIRMED` (como na criação); uma confirmada num plano com garantia continua confirmada.
    6. **Diárias editadas na alteração** só nas noites recalculadas (`400 NIGHTLY_RATE_INVALID` nas mantidas); o desconto novo pelo step-up de `rates.discounts.apply` com `resourceRef` `reservations.reservation:<id>`; a aprovação do desconto na reserva passa a ser a nova, ou some quando nenhuma noite tem mais desconto.
    7. **Base da multa:** `PERCENT_OF_STAY` sobre `roomTotal` (as diárias congeladas, com os inclusos, sem os pacotes adicionais, que não serão consumidos); `NIGHTS` = as diárias das primeiras N noites (no máximo todas); `FIXED_AMOUNT` sem teto. Cancelar **no** instante do prazo já tem multa.
    8. **Multa nunca duas vezes:** `penalties_charged` soma as multas lançadas pela reserva; um novo cancelamento (depois de uma reativação) lança só o que falta para a multa da política. A reativação não estorna a multa (o estorno é ação sensível do `billing`, pela conta). Alternativa descartada: estornar automaticamente na reativação (exigiria estorno na `BillingApi`).
    9. **Isenção sem multa devida** (antes do prazo, ou já lançada): `waivePenalty` não pede aprovação e `waived` fica falso. Isenção sem limite (`maxAmount` não declarado).
    10. **Inventário só a partir da data operacional** (`InventoryNights`): cancelar, alterar e reativar mexem só nas noites a partir dela; as passadas ficam como estão (coerente com a recontagem, que só refaz da data operacional em diante). Reativar uma estadia sem noite a partir dela: `422 RESERVATION_STAY_ENDED`. A reativação não reconfere a venda (restaura a venda feita).
    11. **Motivos de cancelamento** `CancellationReason` (`GUEST_REQUEST`, `HOTEL_REQUEST`, `DUPLICATE`, `NO_GUARANTEE`, `OTHER`; `x-extensible-enum`, a E09 acrescenta a expiração) e a observação livre de até 500 caracteres, só na reserva e na resposta HTTP (nunca na trilha, no histórico, no evento nem no log).
    12. **Problema da transição:** `reservationStatus` (não `status`, que é o campo do RFC 9457) e `action`.
    13. **Contrato:** `PUT /api/v1/reservations/{id}` sem `@Idempotent` (o `If-Match` barra a repetição), `cancel` e `reactivate` com `@Idempotent` e sem `If-Match` (a transição recusa a repetição com outra chave), `GET …/history` por cursor; a resposta das três escritas é a mesma da consulta (`ReservationDetailResponse`).
    14. **API pública:** só `ReservationsApi.cancelReservation` (para a expiração da E09 e o channel manager do M8, sem permissão nem step-up da isenção); alteração e reativação ficam só na API HTTP até alguém precisar.
    15. **Corrida da cadeia de mesclagens** (pendência da verificação da E07): `GuestReferenceRepointing.repoint` descobre a cadeia a partir do sobrevivente, trava o absorvido e a cadeia inteira numa só chamada (em ordem de id) e relê o final depois da trava; se o final saiu do travado, `GuestChainChangedException` e o `GuestsMergedListener` tenta de novo (até 5 vezes, `repoint` com `REQUIRES_NEW`; esgotadas, o outbox reentrega). Alternativas descartadas: travar o alvo depois, fora de ordem (risco de deadlock com a anonimização), e deixar só a reentrega do outbox (1 min com a referência no perfil mesclado).
- Validações executadas:
  - testes de domínio e de integração escritos antes dos casos de uso; a primeira rodada focada falhou em 2 (a repetição idempotente com outro corpo, `422 IDEMPOTENCY_KEY_REUSED`, erro do teste; e a propriedade `status` do problema da transição, que colidia com o `status` do RFC 9457: renomeada para `reservationStatus`);
  - `./gradlew test --tests 'com.zeny.hotel.reservations.ReservationLifecycle*' --tests 'com.zeny.hotel.reservations.BookingGuestsApiTest'` → 33 testes, 0 falhas;
  - mutações aplicadas, rodadas e revertidas: a versão da E07 do `GuestReferenceRepointing.repoint` (o final calculado antes da trava e só o absorvido travado) → o teste novo da cadeia A → B → C falha (o titular fica em B); `affectedNights` recalculando todas as noites em toda alteração → falham a extensão e o desconto na noite mantida; a isenção sem `requireOrApproval` → falha a isenção; a alteração sem devolver as noites antigas → falham a troca de tipo, a extensão e a mudança de plano;
  - `./gradlew lint` (ktlint e detekt) → sucesso, depois de `ktlintFormat` e de quebrar funções longas e complexas;
  - `./gradlew updateOpenApi` → `changeReservation`, `cancelReservation`, `reactivateReservation`, `listReservationHistory` e os schemas novos; `./gradlew checkOpenApi` → em dia;
  - `./gradlew build` → BUILD SUCCESSFUL em 6 min 32 s (1089 testes, 0 falhas; detekt, ktlint, `checkOpenApi` e `koverVerify`);
  - `.github/scripts/check-api-breaking.sh` → "Nenhuma mudança incompatível (nível ERR) em /api/v1".
- Parecer do verificador: **APROVADO** (verificador independente, 2026-10-21; snapshot `6726d258649cb50a9980831a61b79be1510a21cd`, 27 arquivos, +4518/−70). Os sete critérios aprovados com evidência (`ReservationLifecycleApiTest` 14/14, `ReservationLifecycleDomainTest` 10/10, `BookingGuestsApiTest` 9/9): noites mantidas com o preço congelado e só a nova precificada; troca de tipo numa só `inventory.apply` e nada mudado no `409`; multa pela política copiada (a editada depois para FIXED 999 não muda nada), uma só, na conta da reserva, desfeita junto numa falha simulada; isenção e reativação com `APPROVAL_REQUIRED` e aprovação auditada; a tabela de transições inteira; histórico *append-only* recusado ao papel da aplicação e ao dono. `./gradlew build` BUILD SUCCESSFUL em 5 min 15 s (1089 testes, 0 falhas); oasdiff sem `ERR`. A correção da cadeia de mesclagens herdada da E07 foi conferida: sem deadlock (travas exclusivas da cadeia numa só chamada ordenada, sem trava de linha no `guests`), teste determinístico que discrimina e `REQUIRES_NEW` necessário para a retentativa. Sem problema grave nem médio. Baixos, avaliados pelo orquestrador: (1) a multa passa pela `BillingApi.post` e segue as regras de roteamento da conta da reserva (por código ou pelo grupo `FEES`), e com a conta encerrada o cancelamento com multa devida dá `409 FOLIO_CLOSED` (só passa com a isenção) — **aceito**, coerente com o roteamento (quem roteia `FEES` para a empresa quer a multa lá) e com a conta encerrada que recusa lançamentos; (2) o `REQUIRES_NEW` no listener usa duas conexões por entrega de `GuestsMerged` — aceito; (3) a pré-reserva com garantia `NONE` é confirmada em qualquer alteração, mesmo sem troca de plano (`ReservationLifecycle.kt:192`; hoje não ocorre) — **encaminhado à E09**; (4) sem teste de dois cancelamentos simultâneos com chaves diferentes nem de alteração simultânea ao cancelamento (a trava `FOR UPDATE` da reserva cobre) — aceito.
- Notas para a próxima etapa:
  - **E09, pendência da verificação da E08:** em `ReservationLifecycle.kt:192`, a alteração confirma em silêncio uma pré-reserva cuja garantia é `NONE`, mesmo sem trocar o plano; se a E09 criar pré-reservas explícitas num plano sem garantia, restrinja a confirmação automática à troca de plano (ou retire-a) e cubra com teste.
  - E09 (expiração): cancele pela `ReservationsApi.cancelReservation` (acrescente o motivo da expiração a `CancellationReason`, `x-extensible-enum`, e decida se a expiração tem multa — provavelmente `waivePenalty = true` ou motivo sem multa); a confirmação da pré-reserva muda `status` e limpa `provisional_until` (o `CHECK reservation_provisional_ck`); grave o histórico pela mesma `BookingStore.update`/`insertHistory` (um `ReservationHistoryKind` novo, `x-extensible-enum` na resposta).
  - E10 (alocação): a alteração das datas e do tipo e o cancelamento terão de soltar ou conferir a alocação (o `EXCLUDE` da UH); hoje a reserva não tem alocação.
  - E11 (bloqueios): o pick-up cria reservas pela criação; o cancelamento devolve `SOLD` (a unidade voltar ao bloqueio é decisão da E11).
  - E12: as views `report_*` podem ler `reservation_history` (sem texto livre) e as colunas do cancelamento (sem `cancellation_notes`).
  - M6: no-show (`NO_SHOW`) não segura inventário (a recontagem já conta só `PROVISIONAL`/`CONFIRMED`/`IN_HOUSE`) e é reativável por esta etapa; a multa de no-show pode reusar `CancellationPenalties` e `penalties_charged`.
  - Testes: a multa sempre devida usa uma política com `deadlineHours` 8760 e a sem multa, `deadlineHours` 0 com a chegada a 5 dias ou mais (a data operacional nunca fica atrás do relógio), sem `MutableClock`.

---

## E09 — Pré-reserva, garantia e expiração — #concluida

**Objetivo:** confirmar pré-reservas ao registrar garantia manual (depósito pago na conta, conferido no `billing`, ou garantia informada), expirar automaticamente pré-reservas vencidas liberando o inventário e listar pré-reservas e reservas sem garantia perto do vencimento (RF-RES-08 recorte manual, RN-23, H13, H15).

**Por que agora:** sem expiração, pré-reservas prendem inventário indefinidamente; o job precisa existir antes da operação real.

**Dependências e paralelismo:** E07. Pode correr em paralelo com E08, E10 e E11 (em worktrees separados); conflito de edição no agregado `Reservation`, em `api/openapi.yaml`, `CLAUDE.md` e, pela consulta de depósitos, em `billing/BillingApi.kt` — preferir executar em sequência.

**Escopo:**

- Validade da pré-reserva = criação + `HotelApi.operationalSettings().preReservationExpiry` (H15).
- Consulta de depósitos na `BillingApi` (H13; nome a confirmar): o total líquido dos pagamentos com `deposit` de uma conta (recebimentos menos os reembolsos deles), lido de `billing.payment` no próprio `billing`, sem conferir permissão, com teste no `billing`.
- Confirmação: `POST /api/v1/reservations/{id}/confirm` com o tipo de garantia; se a política copiada exige depósito, confere o total de depósitos da conta da reserva pela consulta nova (`422` com `code` estável se abaixo).
- Job de expiração a cada 5 min com `@SchedulerLock` e constante de adiamento no `@IntegrationTest`: cancela pré-reservas vencidas sem multa, libera inventário, publica `ReservationCancelled` com motivo "expirada"; ator sistema na trilha.
- Consulta de pré-reservas e reservas sem garantia que vencem num intervalo, com `preReservationAlertLead` (para o painel de M6).

**Fora de escopo:** cartão tokenizado e Pix (M7); alerta por push.

**Passos de implementação:**

1. Testes: confirmação com e sem depósito suficiente (depósito registrado pela API do `billing`, com turno de caixa aberto); job expira só as vencidas (com o `MutableClock`), é idempotente e não roda em paralelo; corrida entre confirmação e expiração; consulta de vencimento.
2. Implementar a consulta na `BillingApi`, caso de uso, job, consulta e endpoints; regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em RF-RES-08, RN-23, RF-TAR-07, ARCHITECTURE §13, nos handoffs de E07 e na seção "Pagamentos, depósitos e reembolsos" do `CLAUDE.md`.
- Partes paralelas: nenhuma.
- Foco do verificador: job expirando reserva confirmada; expiração sem liberar inventário; corrida entre confirmação e expiração da mesma reserva; confirmação com depósito abaixo do exigido; depósito reembolsado contado; `reservations` lendo o schema `billing` em vez da `BillingApi`.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E09 — "Pré-reserva, garantia e expiração" do roadmap docs/roadmaps/roadmap-m4-tarifas-reservas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo as hipóteses H13 e H15 e o "Protocolo de execução" —, a seção E09 inteira, o "Evidências e handoff" de E07 e RF-RES-08, RN-23 e RF-TAR-07 em docs/SPEC.md. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E07 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: em reservations, confirmar pré-reservas com garantia manual, conferindo o depósito exigido pela política copiada por uma consulta nova de depósitos na BillingApi (H13, acrescentada ao billing sem quebrar contrato); expirar por job com ShedLock a cada 5 minutos as pré-reservas vencidas (validade pela HotelApi.operationalSettings) liberando o inventário e publicando ReservationCancelled com motivo expirada; e listar pré-reservas e reservas sem garantia que vencem num intervalo. Não implemente cartão tokenizado, Pix nem alerta por push.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E09 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes primeiro (MutableClock para vencer prazos; job adiado no @IntegrationTest e chamado direto), implemente, atualize CLAUDE.md e README.md, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em expiração de reserva confirmada, inventário não liberado, corrida entre confirmação e expiração e depósito insuficiente aceito.

Critérios de aceite (detalhes na seção E09):
- confirmação com depósito abaixo do exigido → 422; com depósito suficiente → CONFIRMED;
- job cancela só pré-reservas vencidas, libera inventário e é idempotente;
- confirmação e expiração simultâneas da mesma reserva terminam em um único estado coerente;
- consulta de vencimentos correta; build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E09 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências, incluindo os pontos para a entrevista da E09. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Confirmação com depósito abaixo do exigido pela política → `422` com `code` estável; com depósito suficiente ou política sem depósito → `CONFIRMED`; um depósito reembolsado não conta.
- [ ] A consulta de depósitos é da `BillingApi` (o `reservations` não lê o schema `billing`), com teste no `billing`.
- [ ] Job cancela só pré-reservas com validade vencida, libera o inventário e publica `ReservationCancelled` com motivo "expirada"; segunda execução não altera nada.
- [ ] Job protegido por ShedLock e adiado no `@IntegrationTest`.
- [ ] Confirmação e expiração simultâneas da mesma reserva resultam em um único estado final coerente (lock otimista ou pessimista, teste).
- [ ] Consulta de vencimentos devolve as reservas certas para o intervalo.
- [ ] Contrato regenerado; `./gradlew build` e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Prazo de expiração em fuso errado: calcular com o fuso do hotel e o `Clock` e testar perto da meia-noite.

**Evidências e handoff:**

- Snapshot inicial: `dd70bb6ace319c709dde2757665c79cbc38dbd48` (tirado pelo orquestrador; árvore limpa sobre `c5f70bf`). E07 e E08 `#concluida` e o código conferido com os handoffs delas (pré-reserva criada com `provisional_until` por `InitialStatus`, `CHECK reservation_provisional_ck`, `ReservationsApi.cancelReservation`, histórico *append-only*).
- Arquivos alterados:
  - `billing` (H13): `BillingApi.kt` (`netDeposits(folioId)` e `netDeposits(folioIds)`; `@Suppress("TooManyFunctions")`), `application/BillingApiService.kt`, `application/PaymentPorts.kt` e `infrastructure/JdbcPaymentLedger.kt` (`PaymentLedger.netDeposits`: recebimentos com `deposit` menos os reembolsos de um recebimento com `deposit`, pelo `refund_of_id`); teste novo `billing/NetDepositsTest.kt`;
  - `reservations`, raiz: `Guarantees.kt` (novo: `GuaranteeMethod`, `ReservationGuarantee`, `PendingGuarantee`, `PendingGuaranteeKind`), `Bookings.kt` (`GuaranteeTerms.requiredDeposit`/`depositDueOn`, `ReservationSummary.guarantee` e `requiredDeposit`), `ReservationChanges.kt` (`CancellationReason.EXPIRED`, `ReservationHistoryKind.CONFIRM` e `GUARANTEE`, `ReservationState.guaranteeMethod`), `ReservationsEvents.kt` (`ReservationConfirmed`), `ReservationsErrorCodes.kt` (`GUARANTEE_METHOD_NOT_ACCEPTED`, `DEPOSIT_INSUFFICIENT`, `PROVISIONAL_RESERVATION_EXPIRED`, `RESERVATION_PERIOD_INVALID`), `ReservationsApi.kt` (`confirmReservation`, `pendingGuarantees`), `ReservationsPermissions.kt` (descrição de `reservations.reservations.manage`);
  - `reservations`, internos: `domain/Guarantees.kt` (novo: `GuaranteeRules` com `unguaranteed`/`actionFor`, `GuaranteeAction`, `ProvisionalValidity`, as duas exceções com propriedades no problema), `domain/ReservationLifecycle.kt` (`ReservationAction.CONFIRM`, `CancellationRules` recusa `EXPIRED`), `application/ReservationLifecycle.kt` (`confirm` com o registro da garantia, `expire`, `requireNotExpired` também no `change`, cancelamento refatorado em `applyCancellation` + `PenaltyOutcome`; `confirm`/`confirmAuthorized` no serviço), `application/ProvisionalReservations.kt` (novo: `ProvisionalExpiryService`, `ProvisionalExpiry`, `PendingGuaranteesService`), `application/BookingPorts.kt` (`dueProvisionals`, `pendingGuarantees`, `PendingGuaranteeRow`), `application/InventoryService.kt` (a `ReservationsApi`), `application/RoomTypeInventory.kt` (ações `confirm`/`expire` e a garantia nos retratos), `infrastructure/JdbcBookingStore.kt` (colunas da garantia, as duas consultas, `StoredState.guaranteeMethod` com padrão nulo para o histórico já gravado), `infrastructure/ProvisionalExpiryJob.kt` (novo), `web/BookingsController.kt` (`POST /reservations/{id}/confirm`, `GET /reservations/pending-guarantees`, `requiredDeposit`/`depositDueOn`/`guarantee` na resposta), `web/ReservationLifecycleDtos.kt`;
  - migração `reservations/V2026_10_21_1720__add_reservation_guarantee.sql` (colunas e `CHECK reservation_guarantee_received_ck`, índice parcial `reservation_provisional_idx`, `CONFIRM` no `reservation_history_kind_ck`); `application.yaml` (`zeny.reservations.provisional-expiry.interval` `PT5M`, `initial-delay` `PT1M`);
  - testes: novos `reservations/ProvisionalReservationDomainTest.kt` (5) e `ProvisionalReservationsApiTest.kt` (9, dois deles dos ajustes); alterados `ReservationLifecycleDomainTest.kt` (tabela de transições com `CONFIRM`), `ReservationsTestSupport.kt` (plano `CARD`, `confirm`, o listener de teste de `ReservationConfirmed`), `testsupport/IntegrationTest.kt` (`PROVISIONAL_EXPIRY_DEFERRED`) e os três contextos montados à mão que passam os adiamentos (`EventPublicationRegistryTest`, `RealtimeStreamTest`, `ProductionProfileApiTest`);
  - `api/openapi.yaml` (regenerado: `confirmReservation`, `listPendingGuarantees`, `ReservationConfirmRequest`, `PendingGuaranteeResponse`, `ReservationGuaranteeResponse`, `EXPIRED`, `CONFIRM` e `GUARANTEE` nas listas extensíveis), `CLAUDE.md` (estado atual, seção nova "Pré-reserva, garantia e expiração", `netDeposits` nos pagamentos, o job e o adiamento), `README.md` (API e árvore), este roadmap.
- Decisões:
  - **já dadas:** H13 (consulta de depósitos na `BillingApi`, sem quebrar contrato: confirmada no código, `netDeposits`), H15 (validade = criação + `preReservationExpiry`, já gravada pela E07; o alerta pelo `preReservationAlertLead`, usado como padrão do fim da lista), ARCHITECTURE §13 (expiração a cada 5 min com ShedLock), fora de escopo (cartão tokenizado, Pix e push).
  - **do implementador; decididas pelo orquestrador em nome do usuário (ausente; delegado em 2026-10-21 escolher a opção recomendada depois de pesar prós e contras)**: 1 a 6, 8 e 9 mantidas como implementadas; a 7 trocada pela opção (b) (ajuste A abaixo):
    1. **Garantia "cartão" até o M7** (decisão aberta da E09): só **informada** (`CARD`, sem nenhum dado do cartão, nem bandeira nem últimos dígitos), registrada com quem e quando; não ligada a uma caução do M3 (a caução vale até 90 dias e costuma ser feita no check-in).
    2. **Garantia aceita = a da política copiada** (a de depósito só `DEPOSIT`, a de cartão só `CARD`; a sem garantia, qualquer uma): `422 GUARANTEE_METHOD_NOT_ACCEPTED`.
    3. **Depósito exigido** = percentual da política sobre o **`total`** da reserva (diárias + pacotes adicionais), pela regra de `Money` (`HALF_EVEN`) — base **diferente** da multa `PERCENT_OF_STAY` da E08, que é o `roomTotal`: o depósito protege a compra inteira; conferido contra o total líquido de depósitos **da conta da reserva** (só ela; um depósito noutra conta não conta), lido depois da trava da reserva; igual ou maior passa.
    4. **Validade vencida recusa a confirmação** (`422 PROVISIONAL_RESERVATION_EXPIRED`) mesmo antes de o job rodar: a validade é o instante exato, não "até o job passar".
    5. **Expiração sem multa**, com o motivo novo `EXPIRED` (`x-extensible-enum`), histórico `CANCEL` (sem tipo novo), ação de trilha própria `reservations.reservation.expire` (ator sistema), `ReservationCancelled` como no cancelamento; a conta da reserva fica aberta (como no cancelamento da E08). O motivo `EXPIRED` é recusado num pedido (`400 RESERVATION_CANCELLATION_INVALID`, campo `reason`); ele aparece na lista do `enum` do pedido no contrato (o springdoc gera do `enum` Kotlin), com a recusa descrita.
    6. **Confirmação** (e o reembolso posterior que não a desfaz: na E12, uma lista das confirmadas com depósito líquido abaixo do exigido): endpoint próprio `POST …/confirm` com `@Idempotent` (como `cancel`/`reactivate`), permissão `reservations.reservations.manage` (sem permissão nova, não sensível), evento novo `ReservationConfirmed` e a garantia gravada na reserva (`guarantee` na resposta; o histórico guarda `guaranteeMethod`). Também na `ReservationsApi.confirmReservation` (sem permissão), para o pagamento integrado do M7. Um reembolso posterior do depósito não desfaz a confirmação.
    7. **Prazo do depósito da política** (`dueDaysBefore`): só informativo (`depositDueOn` na reserva e na lista); não muda a validade nem recusa a confirmação depois dele. A validade não é limitada pela chegada (uma pré-reserva feita na véspera pode vencer depois do check-in).
    8. **"Reservas sem garantia" na lista** (implementada como só as pré-reservas; **trocada pela opção (b)** no ajuste A): a lista inclui também as confirmadas sem garantia. Lista por instantes `[from, to)`, sem `from` = desde as vencidas (`overdue: true`), sem `to` = agora + `preReservationAlertLead`.
    9. **Job:** lotes de 100, até 10 por execução, candidatos lidos sem trava e cada expiração na sua transação com a linha travada e conferida de novo; uma falha vai ao log e não para as outras.
  - **Ajustes decididos pelo orquestrador em nome do usuário (delegado em 2026-10-21), depois do parecer APROVADO do verificador**, aplicados nesta etapa:
    - **A (ponto 7 → opção b):** "reserva sem garantia" = `CONFIRMED` com a política copiada `CARD` ou `DEPOSIT` e sem garantia registrada (`GuaranteeRules.unguaranteed`). A lista virou **garantias pendentes**: `GET /api/v1/reservations/pending-guarantees` (operação `listPendingGuarantees`, resposta `PendingGuaranteeResponse`; endpoint novo desta etapa, renomeado sem quebrar contrato publicado) e `ReservationsApi.pendingGuarantees` (`PendingGuarantee`), com `kind` (`PendingGuaranteeKind`: `PROVISIONAL`, `UNGUARANTEED`; `x-extensible-enum`), `status`, `dueAt` e `overdue`. O vencimento da reserva sem garantia é o horário de check-in do hotel, no fuso do hotel, do dia do prazo do depósito (`depositDueOn`) na política `DEPOSIT`, ou do dia da chegada na política `CARD` (calculado na SQL, com a ordenação e o cursor por `(dueAt, id)`). O mesmo `POST …/confirm` registra a garantia dela (`GuaranteeRules.actionFor` → `GuaranteeAction.REGISTER`), com as mesmas conferências de método e depósito e sem a validade; a situação continua `CONFIRMED`; grava a garantia, o histórico com o tipo novo **`GUARANTEE`** (`changedFields` `guarantee`; `CHECK` da migração atualizado) e a trilha (`reservations.reservation.confirm`), **sem evento** (a situação não muda: nada que o channel manager precise; escolha documentada no KDoc, no contrato e no `CLAUDE.md`). Uma reserva já garantida, ou confirmada numa política sem garantia, continua `409 RESERVATION_TRANSITION_NOT_ALLOWED`.
    - **B (observação 1 do verificador):** a reativação da E08 não mudou; a pré-reserva expirada e reativada fica `CONFIRMED` sem garantia, aparece como `UNGUARANTEED` e recebe a garantia pelo `confirm` (teste).
    - **C (observação 2 do verificador):** o `PUT` da E08 numa pré-reserva já vencida (antes de o job rodar) é `422 PROVISIONAL_RESERVATION_EXPIRED` (`requireNotExpired`, a mesma conferência da confirmação), coerente com a decisão 4 (teste com a validade um microssegundo antes e no instante).
    - As observações 3 e 4 do verificador foram aceitas como estão.
  - **Ajustes finais decididos pelo orquestrador em nome do usuário (delegado em 2026-10-21), depois da 2ª verificação (APROVADO, com um ponto médio):**
    - **D (ponto médio):** uma reserva garantida por cartão que a alteração da E08 leva a um plano de depósito (ou o contrário) ficava com `guarantee.method` que não corresponde à política copiada. Agora é "sem garantia" (`UNGUARANTEED`) a `CONFIRMED` cuja política exige garantia e cuja garantia registrada é nula **ou de outro meio** (`GuaranteeRules.unguaranteed` com `accepts`; na SQL, `guarantee_method IS NULL OR guarantee_method <> guarantee_type`), sem apagar a garantia antiga (o histórico a preserva; a nova a substitui pelo `confirm`, com as mesmas conferências). A alteração da E08 não mudou. **Nota para a E12:** o percentual de depósito que muda entre dois planos `DEPOSIT` não deixa a reserva pendente; entra na lista de depósito abaixo do exigido.
    - **E:** teste de paginação de `pending-guarantees` com `limit` 2 (ao menos duas páginas, nenhum item perdido nem repetido, em ordem de `(dueAt, id)`, com três reservas de mesma validade desempatadas pelo id).
    - **F:** teste do vencimento perto da meia-noite: check-in do hotel trocado pela API para 23h30 (check-out 21h, janela de 60 min) e devolvido no fim; o `dueAt` da reserva sem garantia é 23h30 no fuso do hotel (já o dia seguinte em UTC), o fim exclusivo no próprio instante não a traz e um segundo depois traz, pela API HTTP e pela `ReservationsApi`.
  - **pendência da verificação da E08** (`ReservationLifecycle.kt`, confirmação automática da pré-reserva num plano de garantia `NONE` em qualquer alteração): **não se aplica** — a E09 não cria pré-reserva explícita num plano sem garantia (nenhum campo no pedido), então uma pré-reserva com termos `NONE` continua impossível; o código ficou como estava.
- Validações executadas:
  - testes escritos antes dos casos de uso (domínio e API); como dependiam dos tipos novos, rodaram junto com a primeira implementação: `./gradlew test --tests 'com.zeny.hotel.billing.NetDepositsTest' --tests 'com.zeny.hotel.reservations.ProvisionalReservation*' --tests 'com.zeny.hotel.reservations.ReservationLifecycleDomainTest'` → 1 + 5 + 7 + 10 testes, 0 falhas;
  - mutações aplicadas, rodadas e revertidas (cada uma faz falhar os testes indicados): a expiração lendo a reserva sem a trava → falha "a confirmation holding the reservation makes the expiry wait…"; a confirmação sem a trava → falham "an expiry holding the reservation makes the confirmation wait…" e o do cartão (500 no inexistente); `netDeposits` sem descontar os reembolsos → falham `NetDepositsTest` e "a deposit pre-reservation is confirmed only with the net deposit…"; a expiração sem devolver o inventário → falham o teste do job e a segunda corrida;
  - `./gradlew lint` → sucesso (depois de `ktlintFormat`, quebrar linhas longas, `@Suppress` de `TooManyFunctions` na `BillingApi` e de `LongParameterList` no `BillingApiService`);
  - `./gradlew updateOpenApi` → as duas operações e os schemas novos; `./gradlew build` → BUILD SUCCESSFUL em 6 min 31 s (1102 testes, 0 falhas; detekt, ktlint, `checkOpenApi` e `koverVerify`);
  - `.github/scripts/check-api-breaking.sh` → "Nenhuma mudança incompatível (nível ERR) em /api/v1".
  - **depois dos ajustes A a C:** testes novos em `ProvisionalReservationsApiTest` ("a confirmed reservation lacking the guarantee its policy asks for is listed and gets it registered": troca de plano para cartão e para depósito, expirada e reativada, a lista com `kind`, `dueAt` e o depósito, o registro com as recusas e o histórico `GUARANTEE` sem `ReservationConfirmed`; "a pre-reservation past its validity is no longer changed, even before the job runs") e a lista renomeada; `./gradlew test --tests 'com.zeny.hotel.reservations.ProvisionalReservation*' --tests 'com.zeny.hotel.reservations.ReservationLifecycle*' --tests 'com.zeny.hotel.billing.NetDepositsTest'` → 9 + 5 + 14 + 10 + 1, 0 falhas; mutações (a alteração sem `requireNotExpired`; `actionFor` sem o caso da reserva sem garantia) → os dois testes novos falham, revertidas; `./gradlew lint` → sucesso; `./gradlew updateOpenApi` → `listPendingGuarantees`, `PendingGuaranteeResponse`, `GUARANTEE` no tipo do histórico; `./gradlew build` → BUILD SUCCESSFUL em 5 min 18 s (1104 testes, 0 falhas); `.github/scripts/check-api-breaking.sh` → "Nenhuma mudança incompatível (nível ERR) em /api/v1".
  - **depois dos ajustes D a F:** testes novos em `ProvisionalReservationsApiTest` ("a card guarantee no longer counts after a change to a deposit plan…", "the pending guarantees page by due instant and id…", "the due instant of a reservation without guarantee is the check-in in the hotel zone, across midnight"); `./gradlew test --tests 'com.zeny.hotel.reservations.ProvisionalReservation*'` → 12 + 5, 0 falhas; mutações (a SQL e o domínio voltando a só `guarantee_method IS NULL`; o vencimento calculado em UTC em vez do fuso do hotel) → falham o teste da troca de cartão para depósito, o da meia-noite e o das reservas sem garantia, revertidas; `./gradlew lint` → sucesso; `./gradlew updateOpenApi` → só descrições; `./gradlew build` → BUILD SUCCESSFUL em 5 min 18 s (1107 testes, 0 falhas); `.github/scripts/check-api-breaking.sh` → "Nenhuma mudança incompatível (nível ERR) em /api/v1".
- Parecer do verificador: **APROVADO** (verificador independente do orquestrador, sobre a entrega antes dos ajustes; as observações 1 e 2 viraram os ajustes B e C, a 3 e a 4 foram aceitas). 2ª verificação (orquestrador, sobre os ajustes A a C): **APROVADO**, com um ponto médio (a garantia de outro meio depois da troca de plano), resolvido pelo ajuste D; 3ª verificação independente (orquestrador, 2026-10-21, snapshot `5a61fc34c6dc3528bb8d77dfb0c9a12ae2c75731`, 37 arquivos, +2732/−79): **APROVADO**, sem problemas graves nem médios; os sete critérios aprovados com evidência (`ProvisionalReservationsApiTest` 12/12, `ProvisionalReservationDomainTest` 5/5, `NetDepositsTest` 1/1, `./gradlew build` com 1107 testes e 0 falhas, oasdiff sem `ERR`); os ajustes D (SQL × domínio equivalentes), E (cursor por `(dueAt, id)` com empate) e F (meia-noite no fuso do hotel) conferidos com testes que falhariam com o código errado. Observações baixas: a regra SQL depende dos nomes iguais de `GuaranteeMethod` e `GuaranteeType` (nota acrescentada ao M7), a confirmação lê os depósitos sem travar a conta (decisão 8), o job varre o banco compartilhado nos testes (inofensivo) e três ajustes de texto (aplicados: frase do `CLAUDE.md`, descrição de `ReservationGuaranteeResponse` no contrato, nota do M7). Etapa marcada `#concluida` pelo orquestrador.
- Notas para a próxima etapa:
  - E10 (alocação): a expiração e o cancelamento terão de soltar a alocação da reserva (o `applyCancellation` é o ponto único dos dois); a confirmação não mexe nela.
  - E11 (bloqueios): o pick-up que cria uma pré-reserva num plano com garantia segue esta mesma expiração; decida se a unidade da pré-reserva expirada volta ao bloqueio (como no cancelamento, decisão aberta da E11).
  - E12 (listas e views): a lista das garantias pendentes já existe (`/reservations/pending-guarantees`, pré-reservas e reservas sem garantia); as views podem ler `guarantee_method`, `guaranteed_at`, `guarantee_deposit_required`/`received` e `cancellation_reason = 'EXPIRED'`. **Pedido do orquestrador (decisão 8):** acrescentar uma lista das reservas confirmadas com depósito cujo depósito líquido atual (`BillingApi.netDeposits`) ficou abaixo do exigido (um reembolso depois da confirmação não a desfaz).
  - M6: o painel lê `ReservationsApi.pendingGuarantees`; o job roda sozinho (não depende do fechamento).
  - M7: o pagamento integrado (Pix, cartão tokenizado) confirma pela `ReservationsApi.confirmReservation` com um `GuaranteeMethod` novo (`x-extensible-enum`; acrescentar o valor ao `CHECK reservation_guarantee_received_ck`). Atenção: a regra "sem garantia" na SQL de `BookingStore.pendingGuarantees` (`guarantee_method <> guarantee_type`) depende de `GuaranteeMethod` e `GuaranteeType` terem os mesmos nomes; um meio novo (por exemplo, Pix aceito numa política `DEPOSIT`) exige atualizar juntos o `GuaranteeRules.accepts`, essa SQL e o `CHECK`.
  - Testes: o job varre **todas** as pré-reservas vencidas do banco compartilhado (as asserções olham só as do próprio teste); o `ProvisionalReservationsApiTest` tem contexto próprio com o `MutableClock` e devolve o relógio no fim (`rewind`); a espera da trava nas corridas é conferida em `pg_stat_activity` (`wait_event_type = 'Lock'` no `SELECT … FOR UPDATE` da reserva).

---

## E10 — Alocação de UH — #concluida

**Objetivo:** alocar UH a uma reserva com garantia no banco contra sobreposição, sugerir UHs por características e preferências (OOS como última opção), marcar "não mover" e efetivar o alerta de reservas alocadas atingidas por OOO, com a marca "requer realocação" e a notificação da H11 (RF-RES-04, RN-04, RN-07, RF-UH-05).

**Por que agora:** o check-in (M5) exige UH alocada; a garantia de não haver UH em dobro precisa estar no banco antes.

**Dependências e paralelismo:** E07. Pode correr em paralelo com E08, E09 e E11 (em worktrees separados); conflito de edição no agregado `Reservation`, em `api/openapi.yaml`, `CLAUDE.md`, `ModularityTest` e, pelas preferências, em `guests/GuestsApi.kt` — preferir executar em sequência.

**Escopo:**

- `RoomAssignment` com UH (validada pela `RoomsApi.room`, ativa e do tipo da reserva, ou mudança de tipo explícita), período (coluna `daterange` meio-aberta `[chegada, saída)`, para a saída de um e a chegada de outro no mesmo dia não conflitarem) e motivo; `EXCLUDE USING gist (room_id WITH =, stay WITH &&)` só sobre alocações ativas (predicado `WHERE`), com a violação `23P01` convertida pelo nome da restrição no mesmo `409` da aplicação (modelo `rooms.out_of_order_overlap_ex`).
- Sugestão: UHs ativas do tipo (`RoomsApi.activeRooms`), sem OOO no período (`RoomsApi.outOfOrderPeriods`), livres, ordenadas por preferências do hóspede (a `GuestsApi` ganha as preferências, H13) e características (`RoomsApi`), com OOS por último.
- Flag "não mover".
- Desalocação e realocação de reservas futuras (troca de UH de hóspede em casa é `frontdesk`, M5).
- Alerta de OOO (H11): o listener de `OutOfOrderChanged` da E05 passa também a marcar "requer realocação" as reservas alocadas na UH no período (idempotente) e a notificar pela `NotificationsApi.notify`, com o tipo novo do `reservations` num `ModuleNotificationTypes`, os usuários com a permissão de alocação, texto com o número da UH e as datas, sem dado de hóspede; aresta `reservations` → `notifications` na ARCHITECTURE §3.1 e no `ModularityTest`; consulta das reservas marcadas.
- Day use conforme H5.

**Fora de escopo:** troca de UH com hóspede em casa (`moveRoom`, M5); otimização automática de alocação.

**Passos de implementação:**

1. Testes de integração: alocação sobreposta recusada pela aplicação e pelo banco (SQL direto); saída e chegada no mesmo dia; sugestão ordenada e sem OOO; OOS por último; OOO marca reservas alocadas e notifica só quem tem a permissão, sem dado de hóspede, e a reentrega não duplica a notificação.
2. Implementar migração com a constraint, domínio, casos de uso, endpoints, consulta e o tipo de notificação; regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em RF-RES-04, RN-04, RN-07, RF-UH-05, ARCHITECTURE §11, nos handoffs de E05 e E07 e na seção "Notificações e push" do `CLAUDE.md`.
- Partes paralelas: nenhuma.
- Foco do verificador: constraint ausente ou aplicada a alocações canceladas; limites do `daterange` errados (saída de um no mesmo dia da chegada de outro deve ser permitida); sugestão incluindo UH em OOO; alocação de reserva cancelada; notificação duplicada na reentrega ou com nome do hóspede.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E10 — "Alocação de UH" do roadmap docs/roadmaps/roadmap-m4-tarifas-reservas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo as hipóteses H5, H11 e H13 e o "Protocolo de execução" —, a seção E10 inteira, o "Evidências e handoff" de E05 e E07, RF-RES-04, RN-04, RN-07 e RF-UH-05 em docs/SPEC.md e docs/ARCHITECTURE.md §11. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E07 está #concluida e que o código bate com o handoff dela, e que a H11 já foi decidida (se ainda disser "a confirmar pelo usuário", pare e peça a decisão); se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: em reservations, alocar UH a reservas com a constraint EXCLUDE USING gist sobre alocações ativas (saída e chegada no mesmo dia permitidas, 23P01 convertido no mesmo 409), sugerir UHs livres do tipo por preferências do hóspede (acrescentadas à GuestsApi) e características, com OOS por último e sem OOO, marcar não mover, desalocar e realocar reservas futuras, e, no listener de OutOfOrderChanged, marcar como requer realocação as reservas alocadas atingidas e notificar conforme a H11. Não implemente troca de UH com hóspede em casa (marco M5).

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E10 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de integração primeiro, incluindo um INSERT direto em SQL que deve falhar, implemente, atualize ModularityTest, ARCHITECTURE §3.1, CLAUDE.md e README.md, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em constraint ausente ou sobre alocações canceladas, limites do daterange, sugestão com UH em OOO, alocação de reserva cancelada e notificação duplicada ou com dado pessoal.

Critérios de aceite (detalhes na seção E10):
- alocação sobreposta recusada pela API (409) e pelo banco (INSERT direto falha);
- saída e chegada no mesmo dia na mesma UH permitidas;
- sugestão sem OOO e com OOS por último;
- OOO marca reservas alocadas como requer realocação, listadas em consulta, e notifica conforme a H11; build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E10 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Alocação sobreposta da mesma UH → `409` com `code` estável pela API e falha de constraint num `INSERT` direto em SQL (teste de integração).
- [ ] Saída de uma reserva e chegada de outra no mesmo dia na mesma UH são permitidas.
- [ ] Alocações de reservas canceladas não bloqueiam a UH.
- [ ] Sugestão lista só UHs ativas do tipo, livres e sem OOO no período, com OOS por último e preferências do hóspede consideradas (pela `GuestsApi`).
- [ ] "Não mover" impede realocação sem remover a flag antes.
- [ ] OOO numa UH alocada marca a reserva como "requer realocação", ela aparece na consulta correspondente e, com a H11, a notificação chega só a quem tem a permissão de alocação, sem dado de hóspede, uma vez só na reentrega do evento.
- [ ] `ModularityTest` e ARCHITECTURE §3.1 coerentes com as arestas; contrato regenerado; `./gradlew build` e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- `EXCLUDE` com predicado parcial (só ativas): testar o caso de reserva cancelada e reativada.
- Se a H11 for recusada, o critério da notificação sai e a E10 não cria a aresta com `notifications`.

**Rollback / migração:** constraint nova sobre tabela nova; sem dados reais ainda.

**Evidências e handoff:**

- Snapshot inicial: `92db3e245a79f24fce442032f45ea329b4db2243` (recebido do orquestrador; árvore limpa sobre `ebcb268`). Snapshot ao fim da implementação (antes deste handoff): `ff90bc765fb5db5408226f7c42937836aa22653e`. Pré-requisitos conferidos: E07 `#concluida` e o código bate com o handoff dela (e com o da E08 e da E09: `ReservationLifecycle.applyCancellation` é o ponto único do cancelamento e da expiração; `reservationForUpdate`; `InventoryListeners` de `OutOfOrderChanged` só refaz os contadores); H5, H11 e H13 decididas pelo usuário em 2026-10-20 (H11 sem "a confirmar"). Investigação feita pela própria sessão (adendo do orquestrador): SPEC RF-RES-04, RN-04, RN-07, RF-UH-05 e §8.2.6 (`RoomAssignment` com `from`, `to`, `reason`; `Reservation 1 *-- * RoomAssignment`), ARCHITECTURE §3.1 e §11, o modelo `rooms.out_of_order_overlap_ex` e o `unlessViolated` do `rooms`, a `GuestsApi` (preferências andar, camas e travesseiro no perfil, sem leitura pública) e a seção "Notificações e push" do `CLAUDE.md` (o `billing` como modelo de `ModuleNotificationTypes` e `notify`).
- Arquivos alterados:
  - novos (`reservations`): `RoomAssignments.kt` (raiz: `RoomAssignmentId`, `RoomAssignmentReason`, `RoomAssignmentEndReason`, `ReallocationMark`, `RoomAssignmentSummary`, `RoomSuggestion`, `ReservationsNotificationTypes`), `domain/RoomAssignments.kt`, `application/RoomAssignmentPorts.kt`, `application/RoomAssignments.kt` (`RoomAssignments`, `RoomAssignmentService`, `ReallocationMarking`, `ReallocationListener`, `ReservationsNotificationsConfiguration`), `infrastructure/JdbcRoomAssignmentStore.kt`, `web/RoomAssignmentsController.kt` (`ReservationRoomsController`, `RoomAssignmentsController`); migração `reservations/V2026_10_21_1853__create_room_assignment.sql`;
  - alterados (`reservations`): `Bookings.kt` (`ReservationSummary.roomAssignment`), `ReservationsErrorCodes.kt` (seção E10), `ReservationsPermissions.kt` (`reservations.assignments.manage`), `ReservationsModule.kt` (`notifications`), `application/ReservationLifecycle.kt` (a alteração acompanha a UH, o cancelamento e a expiração a soltam; `ReservationViews` com o número da UH), `application/BookingService.kt` (`ReservationView.roomNumber`, `BookingView.roomNumbers`), `application/RoomTypeInventory.kt` (`ReservationsAudit`: ações e retrato da alocação), `infrastructure/JdbcBookingStore.kt` (a alocação ativa na leitura da reserva), `web/BookingsController.kt` (`ReservationResponse.roomAssignment`), `web/InventoryController.kt` (descrição da tag);
  - `guests` (H13): `GuestsApi.kt` (`preferences(id)`) e `application/GuestsApiService.kt`;
  - testes: novos `reservations/RoomAssignmentDomainTest.kt` (6) e `RoomAssignmentsApiTest.kt` (13); alterados `guests/GuestMergeApiTest.kt` (a `GuestsApi.preferences`, com o mesclado), `ModularityTest.kt` (aresta `reservations` → `notifications`), `iam/AuthorizationTest.kt` (modelo Gerente), `notifications/NotificationsApiTest.kt` (o tipo novo no catálogo), `testsupport/InventoryListenersQuiescence.kt` (espera também o `ReallocationListener`);
  - `api/openapi.yaml` (regenerado: `assignReservationRoom`, `unassignReservationRoom`, `setReservationRoomDoNotMove`, `listReservationRoomSuggestions`, `listReservationRoomAssignments`, `listReallocationRequired` e `roomAssignment` na resposta da reserva), `docs/ARCHITECTURE.md` (§3.1 com `notifications` no `reservations`; §11 com a restrição e a trava por UH), `CLAUDE.md` (estado atual, seção nova "Alocação de UH", a `GuestsApi.preferences`, o segundo disparo de notificação e o sétimo listener), `README.md` (API e árvore), este roadmap.
- Decisões:
  - **já dadas:** H5 (day use `[data, data]`), H11 (a marca "requer realocação" consultável e a notificação de um tipo novo do `reservations`, não obrigatório, a quem tem a permissão de alocação, com o número da UH e as datas, sem dado de hóspede; aresta `reservations` → `notifications`), H13 (preferências na `GuestsApi`, sem quebrar contrato), H16 (`reservations.assignments.manage`, Gerente e Recepcionista), H14 (sem offline nem SSE).
  - **do implementador; os pontos 2 a 12 decididos pelo orquestrador em nome do usuário, delegado em 2026-10-21** (o usuário, ausente, delegou escolher a opção recomendada depois de pesar prós e contras; mantidos como implementados). O ponto 1 **não foi aceito** e virou o ajuste A abaixo; os pontos 13 e 14 ficaram como implementados:
    1. ~~**Day use × chegada no mesmo dia** (recusado pelo orquestrador: contraria a H5, decisão do próprio usuário; ver o ajuste A).~~ Texto original: o `[data, data]` da H5 é guardado pelo PostgreSQL como `[data, data + 1)`, então o day use conflita com outro day use da data e com toda estadia que pernoita na data, **inclusive a que chega nela**; não conflita com a que sai nela. A frase da H5 "a chegada de outras reservas no mesmo dia não conflita" não cabe num `daterange` (a estadia que chega na data inclui a noite dela). Implementado assim (recomendado: o day use e o check-in da tarde disputam a mesma UH). Alternativa: permitir a chegada no mesmo dia, o que exigiria outra representação (por exemplo, horários) e deixaria a governança sem janela entre o day use e o check-in.
    2. **UH de outro tipo:** recusada (`422 ROOM_TYPE_MISMATCH`); para usar outro tipo, altera-se antes o tipo da reserva (`PUT`, que move o inventário e reprecifica). Recomendado: o inventário fica sempre certo. Alternativa: upgrade/downgrade explícito na alocação, sem mexer no preço, que exigiria mover a unidade do inventário entre os tipos na mesma transação (`RoomTypeInventory.apply`) e um campo de "tipo cobrado".
    3. **Reativação sem UH:** a reserva reativada volta sem alocação (a UH pode já ser de outra reserva); realoca-se. Recomendado: simples e sem surpresa. Alternativa: tentar restaurar a UH anterior se livre (mais código na reativação, com recusa silenciosa quando ocupada).
    4. **Alteração de datas com UH alocada:** mantém a UH se continuar livre e sem OOO no período novo (o período da alocação acompanha e a marca sai); senão a solta (`RESERVATION_CHANGED`); "não mover" recusa a alteração (`409 ROOM_ASSIGNMENT_LOCKED`, nada muda). Troca de tipo solta a UH (com "não mover", `409`). Recomendado: não perde a UH à toa e respeita "não mover". Alternativas: sempre soltar (mais simples, perde a UH) ou recusar a alteração sempre que a UH não puder ficar (bloquearia alterações legítimas).
    5. **A marca sai sozinha** quando o OOO deixa de atingir a estadia (liberado ou alterado), refeita pelo listener a partir da `RoomsApi` (como a H10). Recomendado: a lista mostra só o que ainda precisa de ação. Alternativa: a marca fica até alguém agir (exigiria um endpoint para limpar).
    6. **Notificação:** uma por período OOO e por evento, só com as alocações marcadas agora (o evento reentregue não notifica), com os **códigos das reservas** (não são dado pessoal) e o número da UH; a quem tem `reservations.assignments.manage` (o Administrador também). Recomendado. Alternativas: uma por reserva (mais ruído) ou sem os códigos (o operador teria de abrir a lista).
    7. **Quem aloca:** só pré-reserva ou confirmada com noite a partir da data operacional (`409 ROOM_ASSIGNMENT_NOT_ALLOWED`, `422 RESERVATION_STAY_ENDED`); a hospedada é do M5. Recomendado (escopo da etapa).
    8. **Versão da reserva:** alocar, realocar, desalocar e "não mover" avançam a versão (o `ETag`) da reserva; a marca do listener não (o listener não trava a reserva, para não inverter a ordem das travas reserva → UH). Recomendado. Alternativa: nunca avançar (o `ETag` não refletiria a UH) ou avançar também na marca (exigiria travar as reservas no listener, com risco de deadlock).
    9. **Ordem das sugestões:** OOS por último; depois, mais características pedidas; depois, o andar preferido do hóspede principal (comparação sem maiúsculas, acentos e espaços repetidos; camas e travesseiro não distinguem UHs do mesmo tipo); por fim, o número. A resposta diz só se havia preferência de andar, nunca o texto. Recomendado. Alternativa: o andar antes das características pedidas pelo operador.
    10. **"Não mover"** impede também desalocar; o cancelamento e a expiração soltam a UH mesmo com a marca. Recomendado.
    11. **Motivo** em lista fixa `RoomAssignmentReason` (`INITIAL`, `GUEST_REQUEST`, `OUT_OF_ORDER`, `ROOM_ISSUE`, `OPERATIONAL`; `x-extensible-enum` na resposta), padrão `INITIAL`/`OPERATIONAL`; sem texto livre (H10 do M3: a trilha não leva texto livre). Recomendado. Alternativa: texto livre fora da trilha.
    12. **Sem `@Idempotent`** nos endpoints da alocação: repetir a mesma UH não muda nada (naturalmente idempotentes). Recomendado.
    13. **Gatilhos adiados no banco:** alocação ativa só de reserva ativa e reserva cancelada ou no-show sem alocação ativa. Recomendado (o verificador pediu "alocação de reserva cancelada"); consequência: o no-show do M6 tem de soltar a UH.
    14. **`GuestsApi.preferences(id)`** devolve as três preferências (`GuestPreferences`, o tipo que já era público); de um mesclado, as do sobrevivente.
  - **Ajustes decididos pelo orquestrador em nome do usuário (delegado em 2026-10-21), depois do parecer APROVADO da verificação independente**, aplicados nesta etapa:
    - **A (ponto 1, H5):** o que a alocação ocupa passou a ser contado em **meios-dias**, na coluna gerada `slots` (`int4range`; com `k` = data − 2000-01-01, `2k` é o dia `k` entre o check-out e o check-in e `2k+1` a noite `k`): o pernoite `[chegada a, saída s)` ocupa `[2a+1, 2s)` e o day use na data `d` ocupa `[2d, 2d+1)`. A restrição virou `room_assignment_overlap_ex EXCLUDE USING gist (room_id WITH =, slots WITH &&) WHERE (released_at IS NULL)`; o `stay` (`daterange`) ficou para leitura e para cruzar com os OOO (que continuam bloqueando a data inteira). Pernoite × pernoite segue igual (a saída e a chegada no mesmo dia não conflitam); o day use não conflita com a saída nem com a chegada de outras reservas na data, só com a estadia que atravessa a data e com outro day use. A aplicação confere com a mesma regra (`AssignmentPeriod.conflictsWith`, com `firstSlot`/`endSlot`) na sugestão e no "livre" da alteração; a consulta por `stay` só traz candidatos (um superconjunto) e o filtro decide.
    - **B (verificador):** ao manter a UH numa alteração (`RoomAssignments.keeps`), confere também que a UH continua ativa e do tipo da reserva (`RoomsApi.room`); senão a solta (`RESERVATION_CHANGED`; com "não mover", `409`).
    - **C (verificador):** a marca é recalculada contra **todos** os OOO da UH que atingem a estadia (`ReallocationMarks.markFor`): enquanto o OOO citado atinge, a marca fica com ele; liberado ou alterado o citado e outro ainda atingindo, a marca fica e passa a citá-lo (`RoomAssignmentStore.remark`), **sem notificar de novo**; só sai quando nenhum atinge. O recálculo de cada alocação foi para `ReallocationMarking.refreshMark` (complexidade).
    - **D:** notas para o M5 e o M6 (em "Notas para a próxima etapa").
  - **do implementador, sem impacto fora da etapa:** a trava consultiva por UH (sem ela, duas alocações conflitantes simultâneas podem dar deadlock na verificação do `EXCLUDE` e uma vira `500`; mutação conferida), a alocação solta nunca volta a valer nem muda de UH (trigger), a alteração mexe no período da alocação ativa em vez de criar outra linha, a conversão do `23P01` pelo nome da restrição capturando `DataAccessException` (o `JdbcClient` não garante a subclasse), o listener separado do `InventoryListeners` (falha isolada, transação própria).
- Validações executadas:
  - testes escritos com a implementação (a primeira rodada dos novos já passou: 6 + 13, 0 falhas); para provar que exercitam o comportamento, cada mutação foi aplicada, rodada (`./gradlew test --tests 'com.zeny.hotel.reservations.RoomAssignmentsApiTest'`) e revertida: o `EXCLUDE` sem `WHERE (released_at IS NULL)` → falham o da sobreposição (a solta inserida) e o do cancelamento; o `stay` fechado `[]` → falham saída/chegada no mesmo dia, day use e a alteração; a sugestão sem o filtro de OOO → falha a das sugestões; a marca sem conferir "marcada agora" → falha a do OOO (notificação repetida); o cancelamento sem soltar a UH → falha a do cancelamento; a alocação sem a trava da UH → falha a das duas alocações simultâneas (`[200, 500]`, `deadlock detected`);
  - `./gradlew test` focado (`reservations.*`, `GuestMergeApiTest`, `ModularityTest`, `AuthorizationTest`, `architecture.*`, `MigrationConventionTest`, `DatabaseRolesTest`, `DatabaseMigrationsTest`) → 0 falhas (uma falha inicial do `LayerArchitectureTest`, o `web` lendo um tipo de `domain`, corrigida movendo `RoomSuggestion` para a raiz);
  - `./gradlew lint` → sucesso (depois de quebrar linhas longas e de `@Suppress` justificados de `TooManyFunctions`, `ReturnCount` e `ThrowsCount`);
  - `./gradlew updateOpenApi` → as seis operações novas e `roomAssignment` na reserva;
  - primeiro `./gradlew build` → 1 falha fora do módulo (`NotificationsApiTest`, a lista exata do catálogo de tipos sem o tipo novo), corrigida no teste; `./gradlew build` → BUILD SUCCESSFUL em 6 min 38 s (1127 testes, 0 falhas; detekt, ktlint, `checkOpenApi` e `koverVerify`);
  - `.github/scripts/check-api-breaking.sh` → "Nenhuma mudança incompatível (nível ERR) em /api/v1";
  - `python3 ~/.claude/skills/development-roadmap/scripts/check_roadmap.py docs/roadmaps/roadmap-m4-tarifas-reservas.md` → OK.
  - **depois dos ajustes A a D:** testes novos ou reescritos em `RoomAssignmentsApiTest` ("a day use shares the room with a departure and an arrival on its date, not with a stay crossing it": saída, chegada e day use na mesma UH, também na sugestão; a estadia que atravessa a data recusada pela API e pelo `INSERT` direto, com `23P01`; dois day use recusados; "a change of dates lets the room go when it was deactivated or moved to another type in the meantime"; "the mark follows every OOO hitting the stay…": o segundo OOO herda a marca, sem notificação nova, e ela só sai quando nenhum atinge) e em `RoomAssignmentDomainTest` (o day use que não conflita com a chegada; os meios-dias); `./gradlew test --tests 'com.zeny.hotel.reservations.RoomAssignment*'` → 6 + 15, 0 falhas; mutações aplicadas, rodadas e revertidas: o `EXCLUDE` de volta ao `stay` → falha o do day use; o `keeps` sem conferir a UH → falha o da UH desativada ou trocada de tipo; o `remark` que não troca o OOO → falha o da marca que segue os OOO; a sugestão sem `conflictsWith` → falha o do day use; `./gradlew lint` → sucesso (`CyclomaticComplexMethod` resolvido extraindo `refreshMark`); `./gradlew updateOpenApi` → só a descrição de `assignReservationRoom`; `./gradlew build` → BUILD SUCCESSFUL em 6 min 1 s (1129 testes, 0 falhas); `.github/scripts/check-api-breaking.sh` → "Nenhuma mudança incompatível (nível ERR) em /api/v1".
  - **retoques da 2ª verificação:** KDoc de `JdbcRoomAssignmentStore` corrigido (a restrição usa `slots`; `stay` só traz candidatos e cruza com os OOO) e o teste novo "an OOO on the date blocks a day use - out of the suggestions, refused, and marks the one already assigned" (o OOO na data marca o day use já alocado, com a notificação, tira a UH da sugestão de outro day use da data e recusa a alocação nela com `422 ROOM_OUT_OF_ORDER` e a data); `./gradlew test --tests 'com.zeny.hotel.reservations.RoomAssignment*' --tests 'com.zeny.hotel.ModularityTest'` → `RoomAssignmentDomainTest` 6, `RoomAssignmentsApiTest` 16, `ModularityTest` 16, 0 falhas; `./gradlew lint` → sucesso. Contrato sem mudança (`./gradlew build` completo da rodada anterior: 1129 testes; com o teste novo, 1130).
- Parecer do verificador: verificação independente do orquestrador **APROVADO** (sem problemas graves), sobre a entrega antes dos ajustes; os pontos menores viraram os ajustes B e C, e o ponto 1 da entrevista, o ajuste A. 2ª verificação independente (sobre os ajustes A a D): **APROVADO**, sem graves nem médios; os dois retoques pedidos (KDoc e o teste do OOO com day use) foram aplicados acima. Detalhe da 2ª verificação (orquestrador, 2026-10-21, snapshot `ffaf3b969989284bab7a627057732d9a7f79c05b`, 31 arquivos, +3709/−76): os sete critérios aprovados com evidência; a regra dos meios-dias conferida por força bruta (0 divergências contra a H5: pernoite × pernoite igual ao `daterange [)`, day use × chegada e saída no dia permitidos, day use × estadia que atravessa a data e × outro day use recusados) e num PostgreSQL 18 avulso (datas antes de 2000 com faixas negativas; o `int4` só estoura depois do ano ~2.941.000, inalcançável); banco e domínio com a mesma expressão; `./gradlew build` com 1129 testes e 0 falhas; oasdiff sem `ERR`. Depois dos retoques: `RoomAssignmentsApiTest` 16/16, `RoomAssignmentDomainTest` 6/6, `ModularityTest` 16/16 e `./gradlew lint` verdes ; o build completo antes do commit, pelo orquestrador: `./gradlew build` BUILD SUCCESSFUL em 10m57s, 1130 testes, 0 falhas. Etapa marcada `#concluida` pelo orquestrador.
- Notas para a próxima etapa:
  - E11 (bloqueios): o pick-up cria reservas sem UH; a alocação segue igual. Se a unidade de uma reserva de pick-up cancelada voltar ao bloqueio, o cancelamento continua soltando a UH (`applyCancellation`).
  - E12 (listas e views): as listas operacionais (chegadas, hospedados) podem trazer a UH alocada pelo `ReservationSummary.roomAssignment`; uma view `report_room_assignments` (sem dado de hóspede) pode ler `reservations.room_assignment` (o histórico inteiro, com a soltura e a marca).
  - M5 (recepção): o check-in exige UH alocada (RN-04; a `ReservationsApi` ainda não tem `assignRoom`: acrescente a alocação sem permissão, como `cancelReservation`); a troca de UH do hóspede em casa encerra a alocação na data da troca e cria outra (o `room_assignment_guarded` permite mudar o período da ativa; um motivo de soltura novo, como `ROOM_MOVED`, exige atualizar o `CHECK room_assignment_release_ck` e o `enum`); o check-out encerra a alocação (ver o ajuste D abaixo).
  - M6 (fechamento): o no-show tem de soltar a UH (o gatilho adiado `reservation_assignment_released` recusa `NO_SHOW` com alocação ativa); o painel lê `GET /api/v1/room-assignments/reallocation-required`.
  - **Ajuste D (orquestrador):** (1) `RoomRegistryChanged` (desativação ou troca de tipo da UH) **não** marca nem solta as alocações existentes (só a alteração da reserva confere a UH, ajuste B): o check-in do M5 deve conferir que a UH alocada continua ativa e do tipo, e uma etapa futura pode marcar essas reservas como o OOO faz; (2) o check-out do M5, inclusive o antecipado, precisa soltar (ou encerrar) a alocação na mesma transação: o gatilho adiado `room_assignment_reservation_active` recusa qualquer escrita numa alocação ativa de reserva que não está ativa (o `ReallocationListener` falharia ao marcá-la, e a publicação ficaria `FAILED`), e a alocação esquecida de um check-out antecipado continuaria bloqueando a UH nas datas seguintes; (3) o no-show do M6 solta a UH na mesma transação; (4) o M5 acrescenta `ReservationsApi.assignRoom` (sem permissão, como `cancelReservation`) para o check-in e o walk-in.
  - Testes: todo teste que espera os listeners de `OutOfOrderChanged` usa `InventoryListenersQuiescence.await(jdbc)` (agora também o `ReallocationListener`); as notificações de realocação de um teste se filtram pelo título com o número da UH do próprio teste.

---

## E11 — Grupos e bloqueios — #pendente

**Objetivo:** criar bloqueios de grupo com N UHs por tipo e data e tarifa do grupo, consumindo o contador `blocked`; registrar rooming list e pick-up (converter unidades do bloqueio em reservas sem consumir inventário duas vezes); abrir conta master do grupo; e expor a regra de data de corte com `releaseUnused` para o fechamento de M6 (RF-RES-09, RN-16).

**Por que agora:** eventos e grupos (M10) e o fechamento (M6) dependem dos bloqueios; a interação com os contadores precisa estar resolvida junto com as reservas.

**Dependências e paralelismo:** E07. Pode correr em paralelo com E08, E09 e E10 (em worktrees separados); conflito de edição no serviço de inventário, em `api/openapi.yaml` e `CLAUDE.md` — preferir executar em sequência.

**Escopo:**

- `RoomBlock` com código legível, nome, data de corte, titular (hóspede pela `GuestsApi.guest` ou empresa pela `GuestsApi.company`), `BlockAllocation` (tipo, data, quantidade, pick-up, tarifa do grupo em `Money`); o listener de `GuestsMerged` da E07 passa a repontar o titular hóspede do bloqueio.
- Criação e alteração de alocações do bloqueio (`@Idempotent` na criação, `If-Match` na alteração) ajustam `blocked` com o lock de E05.
- Pick-up: criar reserva vinculada ao bloqueio move uma unidade de `blocked` para `sold` na mesma transação, com a tarifa do grupo congelada.
- Rooming list: criação em lote de reservas a partir de uma lista de hóspedes, `@Idempotent`.
- Conta master do grupo aberta pela `BillingApi.openFolio(NewFolio(FolioOwnerType.GROUP, id do bloqueio))`; o roteamento para ela segue pela API HTTP de regras do M3 (criar regras automaticamente exigiria acrescentar a criação à `BillingApi`; decisão da entrevista da E11).
- `releaseUnused(blockId)` na API pública: devolve ao inventário o não utilizado após a data de corte (data operacional); o fechamento de M6 chama.
- Eventos: `AvailabilityChanged` nas mudanças de contador. Permissões de bloqueio também sugeridas ao modelo Comercial/Eventos (H16).

**Fora de escopo:** chamada automática no fechamento (M6); eventos e espaços (M10).

**Passos de implementação:**

1. Testes de integração: bloqueio consome `blocked`; pick-up não consome duas vezes; rooming list idempotente; `releaseUnused` devolve só o não utilizado; bloqueio sem disponibilidade → `409`; `GuestsMerged` reponta o titular do bloqueio.
2. Implementar migração, domínio, casos de uso e endpoints em `/api/v1/room-blocks`; regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em RF-RES-09, RN-16, §8.2.6 e nos handoffs de E05 e E07.
- Partes paralelas: nenhuma.
- Foco do verificador: pick-up consumindo `sold` sem reduzir `blocked`; `releaseUnused` antes da data de corte; rooming list repetida duplicando reservas; tarifa do grupo não congelada; conta master aberta duas vezes.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E11 — "Grupos e bloqueios" do roadmap docs/roadmaps/roadmap-m4-tarifas-reservas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo as hipóteses H8 e H16 e o "Protocolo de execução" —, a seção E11 inteira, o "Evidências e handoff" de E05 e E07 e RF-RES-09 e RN-16 em docs/SPEC.md. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E07 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: em reservations, criar bloqueios de grupo (N UHs por tipo e data, tarifa do grupo, data de corte, titular hóspede ou empresa pela GuestsApi) que consomem o contador blocked com lock, pick-up que move uma unidade de blocked para sold na mesma transação com a tarifa do grupo congelada, rooming list idempotente, conta master do grupo aberta pela BillingApi.openFolio com FolioOwnerType.GROUP, o repontamento do titular no listener de GuestsMerged e releaseUnused na API pública para o fechamento do marco M6 chamar após a data de corte. Não chame releaseUnused automaticamente.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E11 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de integração primeiro, implemente, atualize CLAUDE.md e README.md, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em pick-up que consome duas vezes, release antes do corte, rooming list duplicada e tarifa do grupo não congelada.

Critérios de aceite (detalhes na seção E11):
- bloqueio reduz a disponibilidade; sem disponibilidade → 409;
- pick-up mantém blocked + sold constante para a unidade convertida;
- rooming list repetida com a mesma Idempotency-Key não duplica reservas;
- releaseUnused antes da data de corte → 409 e depois devolve só o não utilizado; build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E11 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências, incluindo os pontos para a entrevista da E11. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Criar bloqueio reduz a disponibilidade do tipo nas datas; sem disponibilidade → `409` com `code` estável.
- [ ] Pick-up cria reserva vinculada ao bloqueio, decrementa `blocked` e incrementa `sold` na mesma transação; a disponibilidade total não muda (teste).
- [ ] Reserva do pick-up usa a tarifa do grupo congelada por noite.
- [ ] Rooming list cria as reservas em lote; repetição com a mesma `Idempotency-Key` não duplica.
- [ ] Conta master do grupo aberta em `billing` (`FolioOwner(GROUP, id)`), uma só.
- [ ] `releaseUnused` antes da data de corte → `409`; depois, devolve só as unidades não utilizadas e publica `AvailabilityChanged`.
- [ ] `GuestsMerged` reponta o titular hóspede do bloqueio; contrato regenerado; `./gradlew build` e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Cancelamento de reserva de pick-up: decidir se a unidade volta ao bloqueio ou ao inventário geral (recomendado: antes do corte, volta ao bloqueio); registrar a decisão e coordenar com E08 se já concluída.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E12 — Busca, listas operacionais e views de leitura — #pendente

**Objetivo:** buscar reservas por nome, documento, número, número da OTA, período e status; listar por data operacional chegadas, saídas, hospedados e no-shows previstos; e publicar as views `report_*` de `rates` e `reservations` para os relatórios de M6 (RF-RES-11, RF-RES-13, ARCHITECTURE §11).

**Por que agora:** a recepção (M5) opera a partir dessas listas e o `reporting` (M6) lê só as views.

**Dependências e paralelismo:** E08, E09, E10, E11. Não paralelizável.

**Escopo:**

- `GET /api/v1/reservations` com filtros (número, número da OTA, período, status, nome) e paginação por cursor; a busca por nome usa uma busca nova da `GuestsApi` que devolve só ids, limitada (H13), e filtra as reservas por eles (o `reservations` nunca guarda nome); a busca por documento vai **no corpo** (`POST /api/v1/reservations/search-by-document`, como no `guests`) e usa `GuestsApi.findByDocument` (índice cego; o número nunca vai na URL nem no log).
- Listas por data operacional (`HotelApi.currentBusinessDate()`, padrão quando a data não vem): chegadas, saídas, hospedados (status `IN_HOUSE`, preenchido a partir de M5), no-shows previstos (confirmadas não chegadas na data).
- Views pela convenção do `CLAUDE.md` (nomes e colunas decididos na entrevista da E12; sugestão: `reservations.report_reservations` sem dado de hóspede, `reservations.report_room_nights` com noites vendidas por tipo, plano, canal, segmento e a categoria do plano para Q-15, `reservations.report_inventory` e `rates.report_rate_prices`), cada uma com o contrato no comentário da migração, `GRANT SELECT`/`REVOKE` por view e um teste que as lê com um papel que só tem `SELECT` nelas.
- Índices para as consultas mais frequentes.

**Fora de escopo:** relatórios e painel (M6); pick-up e on-the-books (RF-REL-04, M10).

**Passos de implementação:**

1. Testes de integração: cada filtro de busca, cada lista (com a data operacional diferente da do relógio pelo `MutableClock`) e leitura de cada view, inclusive pelo papel que só lê as views.
2. Implementar a busca por nome na `GuestsApi`, consultas com `JdbcClient`, endpoints, views e índices; regenerar o contrato; documentar as views no `CLAUDE.md`.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em RF-RES-11, RF-RES-13, ARCHITECTURE §11, nos handoffs de E07 a E11 e no `BillingReportViewsTest` como modelo.
- Partes paralelas: nenhuma.
- Foco do verificador: busca por nome ou documento acessando o schema de `guests`; número de documento na URL ou no log; view com texto livre, nome ou dado sensível; lista usando data do calendário em vez da data operacional; consulta sem índice em filtro frequente; view com privilégio além de `SELECT`.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E12 — "Busca, listas operacionais e views de leitura" do roadmap docs/roadmaps/roadmap-m4-tarifas-reservas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo as hipóteses H13 e H15 e o "Protocolo de execução" —, a seção E12 inteira, o "Evidências e handoff" de E07 a E11, RF-RES-11 e RF-RES-13 em docs/SPEC.md e "Leitura para relatórios" em docs/ARCHITECTURE.md §11. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E08, E09, E10 e E11 estão #concluida e que o código bate com o handoff delas; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: em reservations, buscar reservas por nome (busca nova da GuestsApi que devolve só ids), documento (no corpo, pela GuestsApi.findByDocument), número, número da OTA, período e status com paginação por cursor; listar por data operacional chegadas, saídas, hospedados e no-shows previstos; publicar views report_* de reservations e rates pela convenção do CLAUDE.md, sem texto livre nem dado de hóspede, com a categoria do plano para a regra de Q-15; criar os índices necessários. Não implemente relatórios nem painel.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E12 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de integração primeiro (inclusive um papel que só lê as views), implemente, documente as views no CLAUDE.md, atualize README.md, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em acesso ao schema de guests, documento na URL, view com dado de hóspede, data do calendário no lugar da operacional e filtro sem índice.

Critérios de aceite (detalhes na seção E12):
- cada filtro de busca e cada lista operacional coberto por teste de integração;
- views report_* lidas em teste, só com SELECT, sem texto livre nem dado de hóspede;
- listas usam a data operacional do hotel;
- views documentadas no CLAUDE.md; build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E12 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências, incluindo os pontos para a entrevista da E12. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Busca por nome, documento, número, número da OTA, período e status, cada filtro com teste de integração; busca por nome e por documento usam só a `GuestsApi`, e o documento vai no corpo.
- [ ] Listas de chegadas, saídas, hospedados e no-shows previstos por data operacional, com teste que usa data operacional diferente da do relógio.
- [ ] Views `report_*` de `reservations` e `rates` existem, são lidas em teste (também por um papel que só as lê), o papel da aplicação só tem `SELECT` nelas, e não contêm texto livre nem dado de hóspede; a de noites vendidas traz a categoria do plano.
- [ ] Views documentadas no `CLAUDE.md` como API de leitura.
- [ ] `ApplicationModules.verify()` passa; contrato regenerado; `./gradlew build` e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Busca por nome cruzando módulos: buscar IDs de hóspede no `guests` e filtrar reservas por eles; limitar resultados para manter RNF-06; o nome vai na query string, então nada de log de acesso com query string (a pendência do M8 já registrada no `CLAUDE.md`).

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E13 — Gate do marco M4 — #pendente

**Objetivo:** confirmar, a partir de uma cópia limpa do repositório e de um `docker compose` isolado, que toda a definição global de pronto do M4 é atendida, e deixar o roadmap do M5 pronto para ser sincronizado.

**Por que agora:** é o critério de entrada do marco M5.

**Dependências e paralelismo:** E12 (a única etapa-folha, que cobre transitivamente as demais). Não paralelizável.

**Escopo:**

- Rodar `./gradlew clean build`, `clean test`, `lint` e `checkOpenApi` do zero numa cópia limpa (`git archive` do snapshot, `GRADLE_USER_HOME` novo no scratchpad, `--no-daemon --no-build-cache`), e `.github/scripts/check-api-breaking.sh` contra o gate do M3 (`013ed01`).
- Conferir cada item da "Definição global de pronto" com evidência.
- Cenário ponta a ponta automatizado (teste de integração): criar plano base e derivado com restrição, cotar, criar reserva agrupadora com desconto aprovado, alocar UH, aplicar OOO que marca realocação, alterar a estadia, criar bloqueio e fazer pick-up, cancelar com multa lançada na conta, expirar uma pré-reserva pelo job, mesclar o titular e conferir o repontamento.
- Roteiro com `curl` contra um `docker compose` isolado (`-p` próprio, `--env-file` no scratchpad gerado do `docker/.env.example`, porta própria, profile `prod`, pelo proxy; `down -v` no fim, sem tocar no `docker/.env` do usuário): o mesmo cenário com os modelos de role do boot; o OOO pela API refletido na disponibilidade pelo listener (publicação `OutOfOrderChanged` `COMPLETED` no outbox) e, com a H11, a notificação de realocação no painel; a mesclagem refletida nas reservas (publicação `GuestsMerged` `COMPLETED`); a anonimização recusada com estadia ativa; o banco recusando a alocação sobreposta e as escritas nas tabelas *append-only*; as views só com `SELECT`; a expiração pelo job, se o roteiro conseguir vencer uma pré-reserva (validade mínima de 1 h no hotel; senão, por SQL do dono e a próxima execução do job, ou evidência nos testes); logs do `prod` sem documento, nome de hóspede, pedidos especiais, senha, PIN nem token.
- Revisar `CLAUDE.md` (API pública de `rates` e `reservations`, eventos publicados e consumidos, portas implementadas e views) e a seção "API" do `README.md`.
- Registrar a conclusão do M4 no histórico de revisões de `docs/roadmaps/README.md`.

**Fora de escopo:** funcionalidade nova. Problemas voltam para a etapa de origem (reabrir com `#em-andamento` e registrar no handoff dela), salvo ajustes triviais de documentação.

**Passos de implementação:**

1. Escrever o teste ponta a ponta do marco.
2. Rodar tudo do zero numa cópia limpa e o roteiro no compose isolado, registrando a evidência de cada item.
3. Revisar documentação e atualizar o README dos marcos.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação: nenhuma; o modelo do roteiro é o handoff da E11 do roadmap do M3.
- Partes paralelas: nenhuma.
- Foco do verificador: item da definição de pronto sem evidência; teste concorrente que não roda de fato em paralelo; constraint de sobreposição não exercitada por SQL direto; listener de `OutOfOrderChanged` ou `GuestsMerged` não mostrado no compose; dado de hóspede exposto em algum endpoint, view ou log do marco; dependência entre módulos fora da ARCHITECTURE §3.1.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E13 — "Gate do marco M4" do roadmap docs/roadmaps/roadmap-m4-tarifas-reservas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a "Definição global de pronto" —, a seção E13 inteira, o "Evidências e handoff" de E01 a E12 e, como modelo de roteiro, o handoff da E11 de docs/roadmaps/roadmap-m3-hospedes-contas.md. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 a E12 estão #concluida; se não, ou se o código divergiu do plano, pare e relate.

Objetivo: escrever o teste ponta a ponta do marco (plano base e derivado com restrição, cotação, reserva agrupadora com desconto aprovado, alocação, OOO que marca realocação, alteração de estadia, bloqueio com pick-up, cancelamento com multa na conta, expiração de pré-reserva pelo job e mesclagem do titular), verificar numa cópia limpa, sem caches nem estado local, cada item da definição global de pronto do M4, e rodar o roteiro num docker compose isolado pelo proxy, inclusive os listeners de OutOfOrderChanged e GuestsMerged de ponta a ponta. Não implemente funcionalidade nova: problemas são devolvidos à etapa de origem, salvo ajustes triviais de documentação.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E13 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva o teste ponta a ponta, rode do zero numa cópia limpa ./gradlew clean build, clean test, lint e checkOpenApi, rode .github/scripts/check-api-breaking.sh contra 013ed01, suba o compose isolado (-p próprio, env no scratchpad, porta própria, prod) e registre a evidência de cada item da definição global de pronto; derrube com down -v;
- revise o CLAUDE.md e o README.md e registre a conclusão do M4 no histórico de docs/roadmaps/README.md;
- verificação independente com o Prompt do verificador, com foco em item sem evidência, teste concorrente que não roda em paralelo, constraint não exercitada por SQL direto, listener não mostrado no compose, dado de hóspede exposto e dependência fora da ARCHITECTURE §3.1.

Critérios de aceite (detalhes na seção E13):
- teste ponta a ponta do marco passa;
- todos os itens da definição global de pronto marcados com evidência numa cópia limpa e, onde o compose alcança, no roteiro pelo proxy;
- CLAUDE.md e README.md coerentes com o código; docs/roadmaps/README.md registra a conclusão do M4.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E13 (snapshot, verificações, resultados, parecer do verificador, notas para o marco M5) e responda com um resumo curto. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] O teste ponta a ponta do marco passa e exercita concorrência, step-up, multa na conta, expiração e repontamento.
- [ ] Todos os itens da "Definição global de pronto" estão marcados, cada um com evidência numa cópia limpa e, onde o compose alcança, no roteiro pelo proxy (inclusive as publicações de `OutOfOrderChanged` e `GuestsMerged` concluídas pelos listeners do `reservations`).
- [ ] `CLAUDE.md` documenta a API pública de `rates` e `reservations`, os eventos publicados e consumidos, as portas implementadas e as views; o `README.md` cobre a API do marco.
- [ ] `docs/roadmaps/README.md` registra a conclusão do M4 no histórico de revisões.

**Validação obrigatória:**

- `./gradlew clean build`, `clean test`, `lint` e `checkOpenApi` numa cópia limpa, sem cache → sucesso; `.github/scripts/check-api-breaking.sh 013ed01` → nenhuma mudança `ERR`.
- `docker compose -p` isolado `-f docker/compose.yaml up -d --build` → readiness `UP` e roteiro ponta a ponta concluído; `down -v` em seguida.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Testes concorrentes intermitentes: rodar o conjunto ao menos três vezes na cópia limpa e registrar.
- O que o compose não alcança (eventos sem listener de produção, como `ReservationCreated` e `AvailabilityChanged`, sem consumidor até o M5/M8; prazos que dependem do tempo): evidência nos testes da cópia limpa, registrada no handoff, como nos gates anteriores.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para o marco M5: —

---

## Histórico de revisões

- 2026-10-13 — roadmap criado.
- 2026-10-13 — aplicada a regra por camadas da ARCHITECTURE §3.1 (decisão do usuário): `reservations` pode chamar módulos da Plataforma; confirmação em PDF de M5 segue o modelo dono + printing; o alerta de reservas sem garantia continua como lista porque a SPEC não pede push. Nenhuma etapa mudou de escopo.
- 2026-10-15 — nota herdada do M2 acrescentada em "Hipóteses e decisões em aberto" (listener de `OutOfOrderChanged` da E05 com o backoff e o abandono do outbox da M2 E12). Nenhuma etapa reescrita; tags inalteradas.
- 2026-10-20 — sincronizado com o código depois do gate do M3 (commits `1fcc0bb`, `e7251d3` e `013ed01`; CI do push verde no run `39458089966`, os três jobs: build, oasdiff e imagem). Cabeçalho com o pré-requisito atendido e o aviso "escrito antes do código" trocado pela nota de sincronização. "Contexto confirmado" reescrito com os módulos, caminhos, comandos, a última migração (`V2026_10_20_0347`), as convenções (views `report_*`, `AppendOnlyTables`, `requireOrApproval`, `@Idempotent`, `x-extensible-enum`, número de documento fora da URL), o apoio de testes, os gates com compose e as APIs públicas reais (`HotelApi` com faixas etárias e validade da pré-reserva, `RoomsApi` e `OutOfOrderChanged`, `GuestsApi` sem busca por nome nem preferências, portas `GuestPersonalDataContributor`/`GuestActiveStayCheck`, `GuestsMerged`, `BillingApi` com `FolioOwnerType`, `TransactionCodeKey` e `PostingSource` fechado, sem consulta de depósitos), mais a lista de pendências do M3 com a etapa de cada uma; a nota herdada do M2 entrou no contexto. Hipóteses: H2, H6 e H8 confirmadas no código; H4 caiu (o `rooms` não publica evento de cadastro de UH) e virou regra a confirmar; H5 e H7 reescritas com a opção recomendada a confirmar; novas H9 (tarifa publicada), H10 (OOO pelo listener que recalcula), H11 (alerta de realocação com notificação), H12 (estadia ativa e seção da exportação), H13 (acréscimos à `BillingApi` e à `GuestsApi`), H14 (sem offline nem SSE, confirmada na documentação), H15 (datas e prazos) e H16 (nomes de permissões); lista de decisões abertas para a entrevista de cada etapa. Definição global de pronto com os itens do `guests`, das views e do compose isolado. Etapas alteradas: E01 (módulo com `RatesModule`, recusas `400`/`422` no padrão do M3, permissão do desconto), E02 (faixas do hotel e aresta `rates` → `hotel`, tarifa publicada pela H9, evento com listener de teste), E03 (semântica para o M8, evento com listener de teste), E04 (antecedência pela data operacional), E05 (módulo `reservations`, evento de cadastro de UH no `rooms`, listener de OOO que recalcula, recontagem, job adiado nos testes, tipos de UH próprios por teste, ajuste da ARCHITECTURE §11), E06 (aresta `reservations` → `rates`), E07 (conta pela `BillingApi.openFolio` com `RESERVATION`, desconto por `requireOrApproval` com `maxPercent`, listener de `GuestsMerged`, as duas portas do `guests`), E08 (multa pela `BillingApi.post` com `CANCELLATION_FEE`, histórico *append-only*), E09 (consulta de depósitos na `BillingApi`, validade pela `HotelApi`, job adiado), E10 (`EXCLUDE` com conversão do `23P01`, preferências pela `GuestsApi`, alerta de realocação com a notificação da H11), E11 (conta master com `GROUP`, titular repontado, roteamento pela API HTTP), E12 (busca por nome pela `GuestsApi`, documento no corpo, views pela convenção com o papel que só as lê) e E13 (cópia limpa, oasdiff contra `013ed01` e roteiro no compose isolado com os listeners de ponta a ponta); validações trocadas pelos comandos oficiais (`./gradlew build` e o oasdiff). Nenhuma etapa nova, removida ou renumerada; todas seguem `#pendente`.
- 2026-10-20 — entrevista da sincronização: o usuário decidiu as oito questões pela opção recomendada (P1 permissão do desconto no `rates`, `rates.discounts.apply`, sensível com `maxPercent`, Gerente sem teto e Recepcionista com 10% — H7; P2 só mudar ou retirar preço já gravado de plano ativo a partir da data operacional é sensível, `rates.prices.override`, só o Gerente — H9; P3 evento novo e mínimo de cadastro de UH no `rooms`, rotina de recontagem e desativação não recusada — H4; P4 listener assíncrono de `OutOfOrderChanged` que recalcula pela `RoomsApi`, com a frase da ARCHITECTURE §11 corrigida na E05 — H10; P5 marca "requer realocação" e notificação `reservations.reallocation-required` sem dado de hóspede, aresta `reservations` → `notifications` — H11; P6 day use sem consumir o contador de pernoite — H5; P7 multa com `PostingSource.MANUAL` — H6; P8 `GuestActiveStayCheck` com `PROVISIONAL`, `CONFIRMED` e `IN_HOUSE`, titular ou hóspede da estadia — H12). CI do push do gate do M3 verde no run `39458089966` (build, oasdiff e imagem). Texto das etapas inalterado (já seguia as recomendadas); tags inalteradas.
