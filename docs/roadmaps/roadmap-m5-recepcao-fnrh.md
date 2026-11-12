# Roadmap — M5 Recepção, FNRH e impressão

> Arquivo: `docs/roadmaps/roadmap-m5-recepcao-fnrh.md` · branch de execução: `roadmap/m5-recepcao-fnrh` · criado em 2026-10-13 · última revisão 2026-11-12
>
> Marco 5 de 11 · mapa dos marcos em [`docs/roadmaps/README.md`](README.md) · marco anterior: [M4 — Tarifas, disponibilidade e reservas](roadmap-m4-tarifas-reservas.md) · próximo marco: [M6 — Operação diária e fechamento](roadmap-m6-operacao-fechamento.md)
>
> **Pré-requisito:** etapa E13 ("Gate do marco M4") de `docs/roadmaps/roadmap-m4-tarifas-reservas.md` marcada `#concluida` — atendido em 2026-10-22 (commit `ffae917`, depois de `d68e1e3` da E12). CI do push do gate verde no run `39730633697` (os três jobs: build, oasdiff e imagem); a da E12, no run `39723562893`.

## Como usar

Inicie uma sessão limpa do Claude Code na raiz do repositório com `claude --agent orquestrador` e cole o "Prompt de execução". Ele executa até 3 etapas em sequência na branch `roadmap/m5-recepcao-fnrh`, cada uma planejada pelo plugin superpowers e executada pelas souls (subagentes do Second Brain), e para para revisão. No fim do lote, o orquestrador integra a branch na `main` com merge local e deixa pronto o `git push origin main`, que fica com você. Para não parar em cada pedido de permissão, libere antes os comandos de build/teste do projeto nas permissões do Claude Code (os comandos oficiais estão na tabela do `CLAUDE.md`; o Docker é necessário para os testes de integração).

**Sincronizado com o código em 2026-10-22**, depois do gate do M4 (commit `ffae917`): caminhos, comandos, convenções, APIs públicas e pendências vêm do `CLAUDE.md`, do código e dos handoffs de `docs/roadmaps/roadmap-m4-tarifas-reservas.md` (principalmente as "Notas para o marco M5" da E13 e as notas para o M5 das etapas E07 a E12, inclusive o "Ajuste D" da E10). Nenhuma etapa nova, removida ou renumerada; E01 a E08 e E10 a E12 seguem `#pendente` e a E09 segue `#bloqueada` (a Q-12 continua sem credencial). Mudanças de escopo: a cotação de early/late saiu da E01 para a E02 (o preço é a política do `hotel`, e quem cobra é o `frontdesk`); a E08 passou a depender também da E02 (o critério do check-in com o Serpro fora do ar usa o endpoint dela); a E10 passou a ter revisão humana (cria o segundo mecanismo de autenticação do sistema). H1 a H4 foram confirmadas no código, a H9 caiu (o M1 não tem credencial de serviço) e há hipóteses novas (H10 a H24). As decisões que cabiam ao usuário (P1 a P12 da sincronização) foram **decididas pela opção recomendada em nome do usuário (delegado ao orquestrador), 2026-10-22**: todas pela opção (a); a P7 (H14) com uma ressalva, aplicada à E04 (o check-out lança o que o fechamento nunca lançará); e a revisão humana da E10 foi mantida.

## Objetivo

Colocar a recepção para funcionar: check-in orquestrado numa única transação (UH alocada e pronta, documentos e menores conferidos, caução manual opcional, conta da reserva aberta, reserva hospedada e UH ocupada), desfazer check-in como ação sensível, walk-in, check-out com saldo zero (cauções liberadas e contas encerradas), troca de UH, extensão e redução da estadia de hóspede em casa, early check-in e late check-out com o preço informado antes, e recados. Integrar a FNRH Digital (configuração cifrada, domínios oficiais, envio assíncrono com reenvio, pendências visíveis e alerta de falha) e entregar o lado servidor do agente de impressão, com ficha de registro, extrato de conta e confirmação de reserva em PDF e em fila de impressão. Ao fim do marco, um hóspede pode chegar, ser registrado na FNRH, ter a ficha impressa e sair com a conta zerada. O `fnrh` passa a ser o primeiro consumidor de produção dos eventos de reserva do M4 (`ReservationCreated` e os demais) e dos eventos de hospedagem deste marco.

## Contexto confirmado

- Requisitos: SPEC §5.8 (REC: RF-REC-01 a 08; RF-REC-09, itens de empréstimo, é do M9), §5.9 (FNRH: RF-FNRH-01 a 07), §5.22 RF-NOT-03 e RF-NOT-04 (impressão e PDFs), RF-CTA-12 (extrato; os dados já existem desde o M3 E10) e RF-RES-12 (confirmação da reserva, pendência do M4). Regras: RN-04, RN-05 (UH pronta), RN-06 (check-out com saldo zero), RN-09 (Vago + Sujo no check-out), RN-11 (early/late só com preço informado antes; textos de política nas confirmações), RN-12 (ações sensíveis), RN-13, RN-19 (dados sensíveis da FNRH), RN-24 (menores). Questões: Q-07 (expurgo dos sensíveis, M8 E16) e Q-12 (Cadastur e credencial da FNRH, ainda em aberto).
- Modelo de domínio: SPEC §8.2.6 (`Reservation` com `checkIn()`, `checkOut()`, `moveRoom(room, reason)`, `changeStay(arrival, departure)` e `markNoShow()`; `StayGuest` com `primary`, `travelReason`, `transportMode`, `checkedInAt`, `checkedOutAt`; `MinorAuthorization` com `proofType`, `relationship`, `validUntil` e o responsável; `RoomAssignment`; `Trace` com `department`, `dueOn`, `text`, `resolvedAt`), §8.2.17 (`FnrhSubmission` com `operation`, `status`, `attempts`, `lastError`, `remoteId`, `sentAt`; porta `FnrhGateway` com `registerStay`, `checkIn`, `checkOut`, `noShow`, `cancel`), §8.2.18 (`PrintAgent` com `name`, `lastSeenAt`, `tokenHash`; `Printer` com `name`, `kind`, `address`; `PrintJob` com `documentType`, `status`, `attempts`, `createdAt`). Máquina de estados da reserva em §8.4 (`Confirmed → InHouse` no check-in, `InHouse → Confirmed` ao desfazer, sensível e no mesmo dia, `InHouse → CheckedOut`). Eventos em §8.3 (`GuestCheckedIn`/`GuestCheckedOut` consumidos por FNRH e Governança; `RoomMoved` pela Governança; `ReservationCreated`/`Changed`/`Cancelled` pela FNRH).
- Módulos e dependências (ARCHITECTURE §3.1 e §3.2, conferidos em 2026-10-22): `frontdesk` (camada Hospedagem) → `shared`, `reservations`, `rooms`, `guests`, `billing`, `payments`, `fiscal`, `printing` na tabela; pela **regra por camadas** ele também pode usar a Plataforma (`hotel` para a data operacional e a política de early/late, `staff` para os setores dos recados, `files` para conferir o anexo da autorização do menor), acrescentando cada aresta à tabela e ao `ModularityTest` na etapa que a usar (o `fiscal` e o `payments` não são usados no M5). `fnrh` (camada "Integração governamental", acima da Hospedagem) → `shared`, `hotel`, `reservations`, `guests`, `notifications`, já na tabela; `frontdesk` **não** pode chamar `fnrh` (camada acima): o `fnrh` reage aos eventos de `reservations`. `printing` (Plataforma) → `shared`, `hotel`, já na tabela (a aresta `printing` → `hotel` não precisa ser acrescentada); `billing` → `printing` e `reservations` → `printing` já estão na tabela, `frontdesk` → `printing` também. `printing` e `notifications` nunca escutam eventos de negócio. Nenhum dos três importa `iam` (o `ModularityTest` barra: só `staff` e `notifications` usam a `IamApi`). Fluxo do check-in em §6.1, step-up em §6.3, integrações em §12 (porta + adaptador, outbox, retry com backoff, idempotência, log redigido, WireMock, painel de saúde), impressão em §14 (agente com credencial de serviço, SSE, ESC/POS ou PDF, confirmação e reenvio) e D13, credenciais de serviço em §8.1 ("o agente de impressão e integrações de entrada usam credenciais de serviço próprias, com permissões mínimas"), cifragem em §8.3, escopo offline em §9 ("Recepção, contas e PDV exigem conexão").
- Documentação oficial da API FNRH v2.4 (Serpro/MTur): link em SPEC §11 (PDF no gov.br); o levantamento feito na E07 está em `docs/fnrh/api-fnrh-v2.4.md`.
- Revisão de arquitetura dos roadmaps: `docs/superpowers/specs/2026-11-06-revisao-arquitetura-roadmaps-design.md`.
- Base técnica herdada de M0 a M4 (fonte: notas de tema do Second Brain, código e handoffs; conferida no código em 2026-10-22):
  - **Módulos existentes** em `src/main/kotlin/com/zeny/hotel/`: `shared`, `iam`, `audit`, `hotel`, `staff`, `files`, `rooms`, `checklists`, `notifications`, `guests`, `payments`, `billing`, `rates` e `reservations`. Na sincronização de 2026-10-22, `frontdesk`, `fnrh` e `printing` **não existiam**; hoje só `printing` falta (a criar, com um `…Module.kt` na raiz com `@PackageInfo` e `@ApplicationModule(allowedDependencies = …)`, como `reservations/ReservationsModule.kt`, hoje com `["shared", "hotel", "rooms", "rates", "guests", "billing", "notifications"]`). O `ModularityTest` (`src/test/kotlin/com/zeny/hotel/ModularityTest.kt`) tem um teste por módulo com as dependências diretas exatas e só pela API pública: cada módulo novo ganha o seu, e cada aresta nova atualiza o teste do módulo. Regras de camadas em `src/test/kotlin/com/zeny/hotel/architecture/LayerRules.kt` (`writeUseCasesAreAudited`, `offlineCommandHandlersAreAudited`).
  - **Comandos oficiais** na tabela do `CLAUDE.md`: `./gradlew build` (compilação, testes, `ModularityTest`, ArchUnit, detekt, ktlint, `checkOpenApi` e `koverVerify`), `./gradlew test` (um só: `--tests '…'`), `./gradlew lint`, `./gradlew ktlintFormat`, `./gradlew checkOpenApi`, `./gradlew updateOpenApi`, `docker compose -f docker/compose.yaml up -d --build` e `.github/scripts/check-api-breaking.sh`. Versões só em `gradle/libs.versions.toml` (WireMock `3.13.2` já está lá, como `testImplementation(libs.wiremock.standalone)`; nenhuma biblioteca de PDF, HTML ou ESC/POS ainda).
  - **Migrações:** uma pasta por módulo em `src/main/resources/db/migration/` (hoje `shared`, `iam`, `audit`, `hotel`, `staff`, `files`, `rooms`, `checklists`, `notifications`, `guests`, `payments`, `billing`, `rates`, `reservations`), versão por data e hora em UTC, uma migração de `GRANT` ao `"${app_role}"` por pasta (modelo `reservations/V2026_10_20_2316__grant_reservations_to_app_role.sql`). A última versão aplicada é **`V2026_10_22_0427`** (`reservations`, índice dos no-shows previstos): toda migração nova precisa de timestamp posterior (`outOfOrder` desligado). `MigrationConventionTest` barra objeto, FK, trigger, `INSERT` ou `GRANT` fora do schema do módulo. *Append-only*: só `SELECT, INSERT`, bean `AppendOnlyTables` do módulo e trigger contra `UPDATE`/`DELETE` até do dono (modelo `reservations.reservation_history_immutable`); exclusão física proibida por trigger (`reservations.refuse_delete`).
  - **Views `report_*`** (nota `ZenyHotelManager - Schemas, migrações e views de relatório` do brain; modelos `billing/V2026_10_20_0347__create_billing_report_views.sql` e `reservations/V2026_10_22_0351__create_reservation_report_views.sql`): uma linha por fato, ids opacos, sem texto livre nem dado de hóspede, contrato no comentário da migração, `GRANT SELECT` e `REVOKE` por view, provadas com um papel que só as lê.
  - **Portas do kernel** (raiz de `com.zeny.hotel.shared`): `CurrentActor`, `Authorization` (`require`, `grantOrNull`, `scopeFilter`, `limit`, `requireOrApproval`), `SensitiveAction`/`LimitAmount`/`APPROVAL_TOKEN_HEADER`, `@Audited`/`AuditContext`/`AppendOnlyTables`, `ModulePermissions` (códigos `PermissionCode` em segmentos que começam por minúscula, **sem hífen**, regex `[a-z][a-z0-9]*(\.[a-z][a-zA-Z0-9]*)+`; `RoleTemplate` `MANAGER`, `RECEPTIONIST`, `FINANCE`…), `Money`, `Percent`, `DocumentNumberGenerator`, `UuidV7`/`TypedId`, `PageParams`/`CursorPage`, `ETags`, `@Idempotent`, `@ExtensibleEnum`, `FieldCipher`/`EncryptedStringConverter` (credenciais), `ContentCipher` (bytes), `TokenSealer`, `BlindIndex`, `RealtimePublisher` (tópicos de **usuário**, presos ao access token) e `Clock` UTC.
  - **APIs públicas para o M5** (todas sem checar permissão: quem chama já se autorizou):
    - `ReservationsApi` (`reservations/ReservationsApi.kt`): `createBooking(NewBooking)` (o walk-in; sem permissão nem step-up do desconto), `cancelReservation`, `confirmReservation`, `pendingGuarantees`, `createBlock`, `block`, `releaseUnused`, `blocksDueForRelease`, `booking(id)` (`BookingSummary` com `holderGuestId`, `origin`, `channel` e `externalId`, o número da OTA para a RF-FNRH-07), `reservation(id)`, `inventory`, `recountInventory`. **Não há** `assignRoom` na API pública (só `POST /api/v1/reservations/{id}/assign-room`, com permissão), nem transição de check-in, desfazer, check-out, troca de UH de hospedado ou alteração de hospedado (a alteração e a alocação aceitam só `PROVISIONAL`/`CONFIRMED`: `409 RESERVATION_TRANSITION_NOT_ALLOWED` e `409 ROOM_ASSIGNMENT_NOT_ALLOWED`). `ReservationStatus` já tem `IN_HOUSE` e `CHECKED_OUT` (sem transição que os grave); `ReservationSummary` traz `guests` (`StayGuestSummary(guestId, primary)`, sem os dados de check-in), `nightlyRates` (`NightlyRateSummary` com os `PackageCharge` e o `transactionCodeId` de cada pacote), `folioId`, `roomAssignment` (`RoomAssignmentSummary` com a UH, "não mover" e a marca de realocação) e `version`. `RoomAssignmentEndReason` (`UNASSIGNED`, `REALLOCATED`, `RESERVATION_CANCELLED`, `RESERVATION_CHANGED`) e `RoomAssignmentReason` são `x-extensible-enum` nas respostas; acrescentar um motivo de soltura exige atualizar o `CHECK room_assignment_release_ck`. Os gatilhos adiados `room_assignment_reservation_active` (alocação ativa só de reserva `PROVISIONAL`, `CONFIRMED` ou `IN_HOUSE`) e `reservation_assignment_released` (cancelada ou no-show sem alocação ativa) valem no commit. Eventos em `reservations/ReservationsEvents.kt`: `AvailabilityChanged`, `ReservationCreated`, `ReservationChanged`, `ReservationCancelled`, `ReservationReactivated`, `ReservationConfirmed` — só ids, datas, contagens e situações, **sem autor**, todos **sem consumidor de produção** (o outbox só grava a publicação quando há listener). Listas operacionais (`/api/v1/reservations/arrivals`, `/departures`, `/in-house`, `/expected-no-shows`) e views `report_reservations`/`report_room_nights` já contam `IN_HOUSE`; `GuestActiveStayCheck` também.
    - `BillingApi` (`billing/BillingApi.kt`): `folio(id)`, `foliosOf(owner)`, `balanceOf(owner)` (`OwnerBalance.settled` = todas as contas do dono com saldo exatamente zero, RN-06), `closeFolio(id)` (`409 FOLIO_BALANCE_NOT_ZERO` com `balance`; `409 FOLIO_HAS_ACTIVE_PRE_AUTHORIZATION` com caução ativa ou parcialmente capturada ainda não vencida), `openFolio`, `post(folioId, NewPosting(…, source))` na data operacional (`PostingSource` `MANUAL`, `POS`, `END_OF_DAY`, `LAUNDRY`, `EVENT`; `source` da resposta é `enum` **fechado**), `transactionCode(TransactionCodeKey)` (`ROOM_CHARGE`, `CANCELLATION_FEE`, `NO_SHOW_FEE`, `SERVICE_CHARGE`, `FOOD_BEVERAGE`, `LAUNDRY`, `TOURISM_FEE`, `MISCELLANEOUS`; o `key` da resposta de `/transaction-codes` é `enum` **fechado** no contrato), `requireOpenShift(user)`, `cashierShift(id)`, `cardPreAuthorizationsExpiring(from, to)` e `netDeposits`. **Não há** registro, captura nem liberação de caução na `BillingApi` (só pela API HTTP: `POST /api/v1/folios/{folioId}/card-pre-authorizations`, `…/{id}/capture` com turno de caixa e `…/{id}/release`, com `billing.payments.receive`). O extrato em JSON existe (`GET /api/v1/folios/{id}/statement?audience=INTERNAL|GUEST`, `billing.folios.read`); o PDF não.
    - `RoomsApi` (`rooms/RoomsApi.kt`): `room(id)`, `status(id)`, `isReadyForCheckIn(id)` (RN-05: ativa, Vaga, fora de OOO na data operacional e Inspecionada ou Limpa sem inspeção pendente), `markOccupied(id, guestCount, source)` (1 a 40; UH inativa `422 ROOM_INACTIVE`), `markVacant(id, source)` (com `RoomStatusSource.CHECK_OUT`, também Suja, RN-09), `markDirty`, `setServiceFlag`; `RoomStatusSource` já tem `CHECK_IN` e `CHECK_OUT` ("Check-in/Check-out da recepção (M5)"). As mudanças de status saem no tópico SSE `rooms` e em `RoomStatusChanged`.
    - `GuestsApi` (`guests/GuestsApi.kt`): `guest(id)` (`GuestSummary` com nome, nome social, data de nascimento, nacionalidade, VIP, restrito, empresa, `status`, `mergedInto`; **sem documentos**), `findByDocument`, `findIdsByName`, `restrictionAlert(id)`, `isMinorOn(id, date)` (`null` sem data de nascimento), `preferences(id)`, `company(id)` e `fnrhData(id)` (`FnrhGuestData` com os documentos inteiros e os sensíveis decifrados; **exclusiva do `fnrh`**, leitura sem trilha: quem audita é o envio). Toda leitura relê o perfil do banco (`freshGuest`). Finalidades de anexo do `guests`: `guests.minor-authorization-photo` (sensível, JPEG/PNG reencodado), `guests.id-document-photo` e `guests.document-pdf`, vinculadas ao perfil por `POST /api/v1/guests/{id}/attachments`.
    - `HotelApi` (`hotel/HotelApi.kt`): `currentBusinessDate()`, `timezone()`, `policy()` (`checkInTime`, `checkOutTime`, `cleaningWindow`, `earlyCheckIn`/`lateCheckOut` = `EarlyLateTerms(limitTime, fee)` **ou nulo** quando o hotel não oferece, `childMaxAge`, `childAgeBands`, `stayPolicyText`, `additionalPolicyText`), `taxSettings()`, `operationalSettings()`. **Não há** leitura dos dados cadastrais (`HotelProfile`, com razão social, CNPJ, Cadastur, endereço e `logoFileId`, existe como tipo, mas a `HotelApi` não tem `profile()`).
    - `FilesApi` (`files/FilesApi.kt`): só `find`, `attach`, `detach`, `delete` — **não lê nem grava conteúdo** pelo servidor (pendência herdada desde o M2).
    - `NotificationsApi.notify(tipo, título, texto, NotificationRecipients)` (`withPermission`, `except`), com o tipo `notifications.integration-failure` já no catálogo (obrigatório no painel); nunca dado de hóspede no texto. `StaffApi.departments()` (os seis setores com `DepartmentKey`) e `employeeOfUser`.
  - **Segurança:** todo `/api/**` é Resource Server JWT de **usuário** (`shared/web/SecurityConfiguration.kt`, `PUBLIC_API_ENDPOINTS` só com login, refresh, versão e o download de anexo sensível). **Não há credencial de serviço** no M1 (a H9 caiu). O canal SSE do `shared` (`/api/v1/stream`) exige access token de usuário e sessão de dispositivo. Actuator só expõe liveness, readiness e prometheus (`ActuatorExposureTest`): um "indicador de saúde" de integração vira métrica e endpoint de status, não um `HealthIndicator` exposto.
  - **Eventos e jobs:** sete listeners de produção (`ChecklistRoutineListener`, `PushDispatchListener`, `PaymentSucceededListener`, os dois `InventoryListeners`, `GuestsMergedListener`, `ReallocationListener`); reenvio do outbox com backoff de 1 min a 1 h e abandono depois de 24 h (todo listener idempotente). Job novo: `@Scheduled` + `@SchedulerLock` com nome único e uma constante de adiamento no `@IntegrationTest` (como `PROVISIONAL_EXPIRY_DEFERRED` em `src/test/kotlin/com/zeny/hotel/testsupport/IntegrationTest.kt`), com os testes chamando o job direto.
  - **Idempotência e aprovação:** `@Idempotent` exige `Idempotency-Key` e guarda a resposta (inclusive erro e `403 APPROVAL_REQUIRED`) por 24 h; a repetição com `X-Approval-Token` usa outra chave; só JSON síncrono (multipart recusado). Ação sensível: permissão `sensitive = true` e `authorization.requireOrApproval(SensitiveAction(…), approvalToken)` no caso de uso.
  - **Web e contrato:** `@ApiV1Controller`, `@Operation(operationId)` único, `@Tag` do módulo; erros por família com códigos na API pública do módulo; referência inexistente `400 UNKNOWN_…` com o campo, desativada `422 …_INACTIVE`; número de documento nunca na URL; `api/openapi.yaml` regenerado por `./gradlew updateOpenApi`; `ApiSecurityTest` testa todo `/api/**` sem token e só aceita `security: []` nos públicos.
  - **Apoio de testes:** `IntegrationTest`, `asUser(userId, sessionId)`, `MutableClockConfiguration`, `IamTestSupport` (roles, usuários, `setPin`, `call(…, approvalToken = …)`), `RoomsTestSupport`, `GuestsTestSupport`, `BillingTestSupport`, `ReservationsTestSupport` (tipos de UH e hóspedes próprios, sem documentos; `blockBody`, `createBlock`, `pickUp`…), `InventoryListenersQuiescence.await(jdbc)` (antes de baixar `/sync/changes` ou de esperar os listeners de OOO), `FilesTestSupport`, `FcmTestServer` (WireMock). O banco é compartilhado: cada teste cria os próprios tipos de UH e hóspedes, as datas partem de `HotelApi.currentBusinessDate()`, e o relógio movido com o `MutableClock` é devolvido no fim. Testes ponta a ponta dos marcos na raiz do pacote de testes (`MilestoneM3EndToEndTest`, `MilestoneM4EndToEndTest`).
  - **Gates:** os gates do M1 a M4 conferiram tudo numa cópia limpa (`git archive`, `GRADLE_USER_HOME` novo, `--no-daemon --no-build-cache`) e num `docker compose` isolado (`-p` próprio, `--env-file` no scratchpad gerado do `docker/.env.example`, porta própria, profile `prod`, pelo proxy, `down -v` no fim), por roteiro `curl`, com o oasdiff contra o gate anterior.
- Conhecimento do projeto no Second Brain: hub `ZenyHotelManager` e as notas de tema `ZenyHotelManager - Estado por marco`, `ZenyHotelManager - Arquitetura e estrutura de módulos`, `ZenyHotelManager - Hospedagem e recepção` (transições de hospedagem e `frontdesk`), `ZenyHotelManager - Módulo reservations (inventário, disponibilidade e reservas)`, `ZenyHotelManager - Módulo reservations (ciclo de vida, alocação, grupos e busca)`, `ZenyHotelManager - Módulo billing (contas, roteamento e taxa de serviço)`, `ZenyHotelManager - Módulo billing (caixa, pagamentos, caução e encerramento)`, `ZenyHotelManager - Módulo rates`, `ZenyHotelManager - Módulo rooms`, `ZenyHotelManager - Módulo guests`, `ZenyHotelManager - Módulos hotel e staff`, `ZenyHotelManager - Módulo files`, `ZenyHotelManager - Módulo notifications`, `ZenyHotelManager - Segurança, autenticação e autorização`, `ZenyHotelManager - Convenções de API`, `ZenyHotelManager - Tipos do kernel`, `ZenyHotelManager - Auditoria`, `ZenyHotelManager - Schemas, migrações e views de relatório`, `ZenyHotelManager - Eventos e jobs`, `ZenyHotelManager - Tempo real (SSE)`, `ZenyHotelManager - Testes de integração` e `ZenyHotelManager - Docker, observabilidade e CI` (o gate). Leia também as capturas pendentes do projeto que o hook lista; os comandos oficiais continuam na tabela do `CLAUDE.md`.
- **Pendências herdadas do M4 e onde entram aqui:**
  - check-in grava `IN_HOUSE`, exige UH alocada que **continue ativa e do tipo da reserva** (Ajuste D 1 da E10: `RoomRegistryChanged` não solta alocações) e `ReservationsApi.assignRoom` sem permissão para o check-in e o walk-in (Ajuste D 4) → **E01**;
  - check-out, inclusive o antecipado, solta ou encerra a alocação **na mesma transação** (Ajuste D 2: senão o gatilho adiado recusa e a alocação esquecida bloqueia a UH nas datas seguintes) → **E01**;
  - troca de UH do hóspede em casa: encerra a alocação na data da troca e cria outra, com um motivo de soltura novo (atualizando `room_assignment_release_ck` e o `enum`) → **E01** (transição) e **E05** (endpoint);
  - walk-in pela `ReservationsApi.createBooking` → **E03**;
  - primeiro consumidor de produção de `ReservationCreated`/`Changed`/`Cancelled`/`Reactivated`/`Confirmed` → **E08** (`fnrh`); `AvailabilityChanged` segue para o M8;
  - confirmação da reserva em PDF (RF-RES-12), extrato em PDF e o `printing` → **E10** e **E11**;
  - `FilesApi` lendo e gravando conteúdo pelo servidor → decisões H17 e H18 (decididas em 2026-10-22: o `printing` guarda o conteúdo dos jobs no próprio schema; a `FilesApi` ganha só a leitura, para o logotipo, na **E11**);
  - lançamento dos pacotes `ON_ARRIVAL` no check-in → decisão H14 (decidida em 2026-10-22: tudo pelo fechamento do M6, salvo o que o check-out da E04 lança porque o fechamento nunca lançaria);
  - listas operacionais com leitura em lote dos hóspedes ou `ReservationsApi` com as listas → só se a E02 precisar.
- **Pendências herdadas que não são do M5:** no-show (solta a UH, devolve a unidade ao bloqueio do pick-up, multa) e o envio dele à FNRH (H15), diárias e pacotes no fechamento, `releaseUnused`/`blocksDueForRelease`, pacotes da tarifa de grupo (M6, M10); garantia integrada e caução integrada (M7); channel manager (M8); recifragem dos demais valores e configuração do PostgreSQL de produção (M8); contas a receber e `CITY_LEDGER` (M10); expurgo dos sensíveis da FNRH (M8 E16).

## Escopo

**Incluído:**

- `reservations`: transições de hospedagem (check-in com os dados de check-in dos hóspedes e a comprovação de menores, desfazer check-in, check-out inclusive antecipado, troca de UH do hospedado com tarifa mantida ou recalculada, alteração da saída e da ocupação do hospedado), `ReservationsApi.assignRoom` sem permissão e os eventos `GuestCheckedIn`, `GuestCheckInUndone`, `GuestCheckedOut` e `RoomMoved`; documento de confirmação (E11).
- `frontdesk` (novo): check-in, desfazer check-in, cotação e cobrança de early/late, walk-in, check-out, troca de UH, recados.
- `fnrh` (novo): configuração cifrada, CPF solicitante, domínios oficiais, validação local, adaptador Serpro (WireMock), envio assíncrono com retry e ordem, pendências, alerta de falha, métrica e view.
- `printing` (novo): agentes, impressoras, autenticação do agente, fila de jobs com SSE e long-poll, confirmação e reenvio, API pública de renderização e impressão.
- Acréscimos mínimos em módulos de baixo, sem quebrar contrato: `GuestsApi` com a leitura dos documentos sem número (H11, E02; o mascarado também serve à ficha, E11), `BillingApi` com o registro e a liberação de caução (H12, E02 e E04) e com uma chave de idempotência opcional no lançamento (H14, E04), `HotelApi.profile()` (E07 ou E11, a primeira que precisar), `FilesApi` com a leitura do conteúdo (H18, E11); extrato em PDF no `billing` (E11).

**Fora de escopo:**

- Emissão de nota fiscal no check-out (RF-REC-06 passo 3): M7. Até lá, notas são emitidas manualmente no portal da prefeitura (SPEC §9).
- Pré-autorização de cartão e caução integradas (RF-CTA-08): M7. Aqui a caução é só o registro manual do M3.
- Transferência de saldo para contas a receber no check-out (RF-REC-06 passo 1, RF-CTA-13): M10. Até lá, o check-out exige saldo zero por pagamento.
- Itens de empréstimo (RF-REC-09): M9.
- Tarefas de limpeza geradas por check-out ou troca de UH: M6 (`housekeeping` escuta `GuestCheckedOut` e `RoomMoved`).
- No-show e o envio dele à FNRH (H15), lançamento de diárias e de pacotes (H14): M6.
- Expurgo dos dados sensíveis da FNRH após o checkout (RN-19, Q-07): M8 E16.
- Sincronização offline e tópico SSE de recepção (H19).
- O aplicativo do agente de impressão: outro repositório.

## Restrições e invariantes

- Respeitar o grafo de módulos da ARCHITECTURE §3.1 e a regra por camadas: `frontdesk` não chama `fnrh` (camada acima); `printing` só renderiza e enfileira, sem conhecer módulos de negócio — quem é dono do documento monta os dados, checa a própria permissão e chama `printing`; `fnrh` reage a eventos de `reservations`. Toda aresta nova vai à tabela §3.1 e ao `ModularityTest` na mesma mudança. Nenhum dos módulos novos importa `iam`, lê tabelas de outros schemas ou cria FK entre schemas.
- O check-in é tudo ou nada (ARCHITECTURE §6.1): conferências, caução, cobrança de early, transição da reserva e ocupação da UH na mesma transação; o mesmo vale para o walk-in (com a criação da reserva) e o check-out (com a liberação das cauções e o encerramento das contas).
- Falha da FNRH nunca bloqueia check-in nem check-out (RF-FNRH-03).
- Dados sensíveis da FNRH (RN-19) e o número inteiro dos documentos só são lidos pelo `fnrh` (`GuestsApi.fnrhData`), só em memória durante o envio; nunca aparecem em respostas, logs, trilha, eventos, notificações, views nem documentos impressos.
- Ações sensíveis exigem permissão ou aprovação de superior (RN-12): desfazer check-in (e as já existentes: desconto, isenção de multa, reativação, estorno, reembolso).
- Lançamentos financeiros seguem RN-03 (imutáveis, pela `BillingApi.post`) e `@Idempotent` nos POSTs com efeito financeiro ou externo (check-in, walk-in, check-out, troca de UH).
- Credenciais da FNRH cifradas (`FieldCipher`) e tokens de agente só como hash; nunca no repositório, no log nem na resposta (o token do agente aparece uma única vez, na criação ou na rotação).
- RN-01: data operacional pela `HotelApi.currentBusinessDate()`; instantes pelo `Clock`; horários de early/late no fuso do hotel.
- RN-13 e privacidade: eventos com ids, datas e situações; notificações sem nome, documento ou contato de hóspede; trilha sem texto livre (recados, motivos) nem dado pessoal (H10 do M3).
- Migrações novas com timestamp posterior a `V2026_10_22_0427`; cada etapa publica no Second Brain a captura `ZenyHotelManager - atualização m5-recepcao-fnrh Eyy` (o estado e o texto novo ou alterado das notas de tema afetadas; cada módulo novo propõe a sua nota de tema, `ZenyHotelManager - Módulo fnrh` e `ZenyHotelManager - Módulo printing`, e o `frontdesk` continua em `ZenyHotelManager - Hospedagem e recepção`) e atualiza a seção "API" do `README.md`; contrato regenerado e `.github/scripts/check-api-breaking.sh` sem mudança `ERR`.

## Hipóteses e decisões em aberto

| # | Hipótese ou decisão | Impacto se errada / sem decisão | Etapa afetada |
|---|---|---|---|
| H1 | **Confirmada no código em 2026-10-22:** pacote base `com.zeny.hotel`; `frontdesk/`, `fnrh/` e `printing/` não existem e nascem com a estrutura `domain`/`application`/`infrastructure`/`web` e um `…Module.kt`. | — | todas |
| H2 | **Confirmada no código em 2026-10-22:** `reservations` tem `IN_HOUSE` e `CHECKED_OUT` no `ReservationStatus`, mas nenhuma transição que os grave, nenhum `assignRoom` na API pública e nenhum evento de hospedagem; a alteração e a alocação aceitam só pré-reserva e confirmada. A E01 cria tudo isso. | — | E01, E05 |
| H3 | **Confirmada no código em 2026-10-22:** `RoomsApi.isReadyForCheckIn` (RN-05), `markOccupied(id, guestCount, CHECK_IN)` e `markVacant(id, CHECK_OUT)` (Vaga + Suja, RN-09) existem; nada a acrescentar ao `rooms`. | — | E02, E04, E05 |
| H4 | **Confirmada no código em 2026-10-22:** `GuestsApi.fnrhData` decifra os documentos e os sensíveis, é exclusiva do `fnrh` e é lida sem trilha (quem audita é o envio). Consequência: o check-in **não** pode usá-la para conferir documentos (H11). | — | E02, E08 |
| H5 | **Decidida pela opção recomendada em nome do usuário (delegado ao orquestrador), 2026-10-22 (P4 da sincronização):** o CPF solicitante (RF-FNRH-02) vem de um cadastro no próprio `fnrh` (usuário → CPF, cifrado), com um CPF padrão do hotel; os eventos de hospedagem levam o id do usuário que fez a operação (H16), e os eventos de reserva do M4 (sem autor) usam o CPF padrão. O `fnrh` não importa `iam`: o id do usuário é opaco. | Se o hotel exigir o CPF de quem fez cada operação sem cadastro prévio, muda a origem do dado. | E01, E07, E08 |
| H6 | A documentação v2.4 da API FNRH (link na SPEC §11) basta para escrever os stubs WireMock; a sessão da E07 precisa de acesso à web ou do PDF em `docs/`. | Se a API mudar (v2.x), os stubs e o adaptador mudam; o adaptador fica isolado (ARCHITECTURE §18). Sem acesso, a E07 se bloqueia. | E07 |
| H8 | Renderização PDF por templates HTML (ex.: Thymeleaf + openhtmltopdf) e ESC/POS gerado no servidor a partir de templates de texto, com as bibliotecas no *version catalog* (nenhuma existe hoje). | Troca de biblioteca é local ao `printing`. | E10, E11 |
| H9 | **Caiu no código em 2026-10-22:** o M1 não entregou credencial de serviço (só JWT de usuário). Substituída pela H23. | — | E10 |
| H10 | **Decidida pela opção recomendada em nome do usuário (delegado ao orquestrador), 2026-10-22 (P1 da sincronização):** os dados de check-in do hóspede (motivo de viagem e meio de transporte como códigos opacos dos domínios da FNRH, `checkedInAt`/`checkedOutAt`) e a comprovação do menor (`MinorAuthorization`: tipo `PARENT_PRESENT`, `WRITTEN_AUTHORIZATION` ou `COURT_ORDER`, o responsável, o parentesco, o anexo nas duas últimas e a validade) ficam no `reservations`, junto do `StayGuest` (SPEC §8.2.6); a RN-24 é garantida no domínio da reserva (nenhuma reserva fica `IN_HOUSE` com menor sem comprovação, também no walk-in), e o `fnrh` lê tudo pela `ReservationsApi`. | Alternativa: no `frontdesk` (o `fnrh` passaria a depender dele). Muda E01, E02 e E08. | E01, E02, E08 |
| H11 | **Decidida pela opção recomendada em nome do usuário (delegado ao orquestrador), 2026-10-22 (P2 da sincronização):** a `GuestsApi` ganha uma leitura **sem número** dos documentos do perfil (tipo, país emissor e o sufixo mascarado, `***` + os últimos caracteres já guardados em claro; nunca decifra) para o check-in conferir a regra mínima: cada hóspede com ao menos um documento (CPF para nacionalidade brasileira; passaporte, RNM ou identidade estrangeira para estrangeiro) e o menor com data de nascimento (`isMinorOn` não nulo); senão `422 GUEST_DATA_INCOMPLETE` com o hóspede e o que falta. A regra fina da FNRH é conferida pelo `fnrh` no envio (H21). | Alternativas: não conferir no check-in (a FNRH recusa depois e a submissão fica pendente); usar `fnrhData` (viola RN-19). | E02, E03, E11 |
| H12 | **Decidida pela opção recomendada em nome do usuário (delegado ao orquestrador), 2026-10-22 (P5 da sincronização):** a caução opcional do check-in é registrada pela `BillingApi` (método novo, sem permissão, com as regras do `POST /folios/{id}/card-pre-authorizations`) na transação do check-in; a captura (que é um pagamento e exige turno de caixa) é feita antes do check-out pelo endpoint do `billing`; o check-out libera as cauções ainda ativas das contas da reserva (método novo de liberação na `BillingApi`) e encerra as contas pela `BillingApi.closeFolio`. | Alternativa: o cliente registra e libera tudo pelos endpoints do `billing`, e o check-in e o check-out só conferem. | E02, E04 |
| H13 | **Decidida pela opção recomendada em nome do usuário (delegado ao orquestrador), 2026-10-22 (P6 da sincronização):** a taxa de early check-in e a de late check-out (`HotelApi.policy().earlyCheckIn/lateCheckOut.fee`) são lançadas no código `ROOM_CHARGE` (`DIARIA`, hospedagem) com a descrição "Early check-in"/"Late check-out" e origem `MANUAL`, só quando o cliente envia o valor que recebeu da cotação e ele ainda é o da política (`409 EARLY_LATE_FEE_CHANGED` se mudou; sem oferta na política, `422 EARLY_LATE_NOT_OFFERED`). | Alternativa: uma `TransactionCodeKey` nova, que exige trocar o `key` de `/transaction-codes` para `x-extensible-enum` (decisão registrada: muda o tipo nos clientes). | E02, E04, E05 |
| H14 | **Decidida pela opção recomendada em nome do usuário (delegado ao orquestrador), 2026-10-22 (P7 da sincronização), com ressalva:** nenhum pacote é lançado no check-in; os de ritmo `ON_ARRIVAL` e `DAILY` e as diárias ficam para o fechamento do dia (M6), na data operacional da noite. **Ressalva:** como o fechamento só lança para quem está hospedado, uma estadia que termina antes do fechamento da data operacional da chegada (o day use e qualquer check-out no mesmo dia da chegada) sairia sem a diária e sem os pacotes dessa data; por isso o check-out (E04) lança, antes de conferir o saldo, as cobranças da estadia que o fechamento ainda não lançou e nunca lançará (a diária congelada do day use e os pacotes `ON_ARRIVAL`/`DAILY` dessa data), uma vez só, com uma chave de idempotência por reserva, data e cobrança que o fechamento do M6 também usa para não lançar de novo. | Alternativa: `ON_ARRIVAL` no check-in (o desfazer check-in precisaria estornar pela `BillingApi`, que não tem estorno). | E02 |
| H15 | **Decidida pela opção recomendada em nome do usuário (delegado ao orquestrador), 2026-10-22 (P8 da sincronização):** o no-show (transição, soltura da UH e da unidade do bloqueio, multa e o envio à FNRH) fica com a etapa do no-show do M6, que cria o evento e o listener no `fnrh`; a E08 trata reserva, cancelamento, check-in e check-out. | Alternativa: a E01 cria `markNoShow` e o evento agora, sem publicador de produção até o M6. | E08 |
| H16 | **Decidida pela opção recomendada em nome do usuário (delegado ao orquestrador), 2026-10-22 (P4 da sincronização):** `GuestCheckedIn`, `GuestCheckInUndone`, `GuestCheckedOut` e `RoomMoved` levam `performedBy` (id opaco do usuário; nulo = sistema), exceção justificada à convenção "sem autor" dos eventos (a FNRH exige o solicitante). | Alternativa: sem autor, só o CPF padrão para todos os envios. | E01, E08 |
| H17 | **Decidida pela opção recomendada em nome do usuário (delegado ao orquestrador), 2026-10-22 (P9 da sincronização):** o conteúdo renderizado de cada `PrintJob` (PDF ou ESC/POS) fica no schema do `printing`, cifrado pelo `ContentCipher` (a ficha tem dado pessoal), com teto de tamanho, e é apagado depois da confirmação mais um prazo; o agente recebe o conteúdo pela API do agente. | Alternativa: uma `FilesApi.store` com finalidade sensível do `printing`. | E10, E11 |
| H18 | **Decidida pela opção recomendada em nome do usuário (delegado ao orquestrador), 2026-10-22 (P10 da sincronização):** a `FilesApi` ganha a leitura do conteúdo pelo servidor (decifrando o sensível), e o `printing` lê o logotipo do hotel por ela, com a aresta `printing` → `files` (Plataforma) na tabela §3.1; a `HotelApi` ganha `profile()` com os dados cadastrais e o `logoFileId`. | Alternativa: `HotelApi` devolve os bytes do logotipo (o `printing` fica só com `hotel`). | E11 |
| H19 | **Confirmada na documentação** (ARCHITECTURE §9: "Recepção, contas e PDV exigem conexão"): o M5 não registra `OfflineSource`, `OfflineCommandHandler` nem `RealtimeTopic` de recepção; a ocupação da UH já chega pelo tópico `rooms`. | Um tópico de recepção pode entrar depois sem mudar o modelo. | todas |
| H20 | Nomes de permissões sugeridos (sem hífen; confirmados em cada etapa; um código publicado não muda): `frontdesk.stays.checkin`, `frontdesk.stays.undocheckin` (**sensível**), `frontdesk.stays.checkout`, `frontdesk.stays.move`, `frontdesk.traces.read`/`manage`; `fnrh.settings.manage`, `fnrh.submissions.read`/`manage`; `printing.agents.manage`, `printing.jobs.read`/`manage`. Modelos: Gerente todas; Recepcionista as de recepção (sem o desfazer), recados e a leitura das pendências e da fila; Administrador sozinho na configuração da FNRH e nos agentes. | Nomes ajustados na etapa sem efeito fora dela. | E02, E04, E05, E06, E07, E08, E10 |
| H21 | **Decidida pela opção recomendada em nome do usuário (delegado ao orquestrador), 2026-10-22 (P3 da sincronização):** a validação local contra os domínios oficiais (RF-FNRH-06) roda no `fnrh`, antes do envio; uma submissão com dado inválido ou faltando fica pendente com os erros por campo (sem o valor), visível na fila e com alerta, sem bloquear o check-in; o `fnrh` expõe os domínios para o cliente oferecer os códigos certos no check-in. | Alternativa: porta no `frontdesk` implementada pelo `fnrh`, conferida no check-in (bloquearia o check-in por dado da FNRH, contra RF-FNRH-03). | E02, E07, E08 |
| H22 | **Confirmada no código:** o actuator só expõe liveness, readiness e prometheus; o "painel de saúde" da FNRH (ARCHITECTURE §12 e §16) é uma métrica `zeny_fnrh_*` e um endpoint de status com permissão, nunca um `HealthIndicator` que derrube a readiness. | — | E07, E08 |
| H23 | **Decidida pela opção recomendada em nome do usuário (delegado ao orquestrador), 2026-10-22 (P11 da sincronização):** o agente autentica com um token opaco próprio (só o SHA-256 em `PrintAgent`, exibido uma vez na criação ou rotação), numa cadeia de segurança separada para os caminhos do agente (`/api/v1/print-agents/me/**`), com esquema de segurança próprio no contrato e o `ApiSecurityTest` atualizado; o stream do agente é um SSE do próprio `printing` (o do `shared` exige usuário), com `Last-Event-ID` = a posição persistida do job, sem buffer em memória. | Alternativa: o agente como usuário do `iam` com role mínima e JWT (reaproveita a cadeia, mas sessão de 15 min com refresh e um "usuário" que não é pessoa). | E10 |
| H24 | **Decidida pela opção recomendada em nome do usuário (delegado ao orquestrador), 2026-10-22 (P12 da sincronização):** no walk-in, os hóspedes já existem no `guests` (cadastrados antes pelo cliente, `POST /api/v1/guests`); a reserva usa o preço calculado (sem diária editada: o desconto, se houver, vem depois pelo `PUT` com o step-up do M4); exige `frontdesk.stays.checkin` e `reservations.reservations.manage`. | Alternativa: o walk-in cria o perfil (método novo na `GuestsApi`) e aceita desconto com step-up (o `frontdesk` não depende de `rates`). | E03 |
| D1 | **Credencial de homologação da FNRH e Cadastur ativo (SPEC Q-12).** Ainda em aberto em 2026-10-22: nenhuma credencial fornecida. | Sem credencial não há homologação real com o Serpro. | E09 (bloqueada até decidir) |
| D2 | **Decidido em 2026-10-13 (dono + printing):** cada módulo dono de um documento monta os dados, checa a própria permissão e chama a API pública de `printing` para renderizar (PDF ou ESC/POS) e enfileirar: `frontdesk` → ficha de registro, `billing` → extrato, `reservations` → confirmação. `printing` compõe o cabeçalho do hotel pela API pública de `hotel` (aresta já na tabela §3.1; falta a `HotelApi.profile()`, H18). Não há porta em `shared` para documentos. | — | E11 |
| D3 | **Decidido pelo usuário em 2026-10-27:** o scanner de dependências da revisão final enxerga o projeto por um `gradle.lockfile` (travamento de dependências do Gradle), criado numa etapa própria executada antes das demais pendentes; o plugin OWASP Dependency-Check foi descartado. | — | E13 |

**Decisões abertas para a entrevista de cada etapa** (dependem da investigação; o orquestrador as registra em "Decisões para o usuário" no handoff da etapa e as apresenta no fim do lote como entrevista):

- **E01:** check-in de chegada atrasada (chegada antes da data operacional, sem no-show ainda) e check-out com a saída já passada; o que o desfazer check-in faz com a ocupação e a governança da UH e com o early e a caução já registrados.
- **E05:** troca de UH para outro tipo (upgrade) com a tarifa mantida ou recalculada e o inventário das noites restantes; alteração da ocupação do hospedado.
- **E07:** operações e campos exatos da API v2.4 (pré-check-in, reserva antes da chegada); onde fica a URL base (configuração do administrador ou propriedade por ambiente).
- **E08:** quais eventos geram envio (reserva criada sem documentos completos, alteração, reativação, desfazer check-in) e o limite de tentativas.
- **E10:** teto de tamanho do conteúdo, prazo de guarda e o tempo de reenvio do job não confirmado.
- **E11:** caminhos dos PDFs (rota própria ou negociação por `Accept`) e o que a ficha mostra do documento.

**Decisões do lote E04 a E06** (entrevista do fim do lote). O usuário estava ausente e delegou toda interação em 2026-10-23, pedindo a opção recomendada depois de uma avaliação mais minuciosa dos prós e contras. Por isso, todas as decisões abaixo foram **decididas pela opção recomendada em nome do usuário (delegado ao orquestrador), 2026-10-23**. O detalhe e as alternativas estão no handoff de cada etapa.

- **E04 (a) Pacotes inclusos:** lançados à parte da hospedagem, cada um no código dele, e sem taxa de serviço. O M6 precisa seguir a mesma regra. Alternativa descartada: a diária inteira no `ROOM_CHARGE`, que mistura receitas (RF-FIS-04), ou os inclusos com taxa de serviço, que passam do preço informado (RN-11).
- **E04 (b) Problema de saldo:** o `409 FOLIO_BALANCE_NOT_ZERO` traz o saldo já com as cobranças que o check-out lançaria. Alternativa descartada: o saldo sem elas, com que o hóspede pagaria e seria recusado de novo.
- **E04 (c) Cauções vencidas:** também são liberadas no check-out.
- **E05 (a) Upgrade para outro tipo de UH:** fica para depois. É uma pendência de produto fora da Definição de pronto do M5. Quando entrar, troca o tipo da reserva e move o inventário das noites restantes numa etapa própria.
- **E05 (b) Ocupação do hospedado:** continua fixa.
- **E05 (c) Redução da saída:** a redução de quem já está hospedado não passa pelas restrições de venda.
- **E05 (d) Origem da troca de UH:** registrada como `CHECK_OUT` no histórico da UH.
- **E05 (e) Early check-in:** também no walk-in.
- **E05 (f) `ReservationSearchApiTest` intermitente:** aceitar `reservation_arrival_idx` ou `reservation_stay_period_idx` no filtro "period". Aplicado na E08 (`8e6b677`), porque era o único vermelho no gate dela.
- **E06 (a) Responsável:** um usuário vinculado a um colaborador.
- **E06 (b) Resolver o recado:** permissão própria, para Gerente, Recepcionista, Governanta, Manutenção, Comercial/Eventos, Caixa A&B e Cozinha/Bar.
- **E06 (c) Vínculo do recado:** fixo depois de criado.

**Decisões do lote E13** (entrevista do fim do lote). O usuário estava ausente e delegou toda intervenção em 2026-10-28, pedindo a opção recomendada depois de uma avaliação crítica dos prós e contras. Por isso, todas as decisões abaixo foram **decididas pela opção recomendada em nome do usuário (delegado ao orquestrador), 2026-10-28**. O detalhe está no handoff da E13.

- **E13 (a) Vulnerabilidades High/Critical:** viraram a etapa E14, primeira da fila, com patch do Spring Boot ou restrição de versão e os lockfiles regravados.
  - A favor: Critical de 9,8 no servidor HTTP de produção e DoS sem autenticação no Jackson, com correções de patch de baixo risco; sem a etapa, toda revisão final seguinte volta a apontá-las.
  - Contra: atrasa a E07 em uma etapa.
  - Alternativas descartadas: deixar para o M8 (implantação) ou não tratar.
- **E13 (b) Rebaixamento no catálogo:** é ignorado em silêncio, porque o Gradle aplica a versão travada como `strictly`. Fica aceito como comportamento do Gradle e documentado no README e no `build.gradle.kts`. A E14 acrescenta à CI o passo `--write-locks` + `git diff --exit-code`, que pega a divergência. Alternativa descartada: uma tarefa de verificação própria no build.
- **E13 (c) `osv-scanner` no CI:** fica fora por ora. O scanner continua na revisão final de cada etapa. Contra pô-lo no CI: ele reprova um push por CVE publicada depois, sem relação com a mudança. Reavaliar no M8.
- **E13 (d) Verificação de checksums do Gradle** (`gradle/verification-metadata.xml`): fica para depois (M8). Ela fecha a troca de artefato com a mesma versão, mas exige manutenção a cada mudança de versão.
- **E13 (e) `settings-gradle.lockfile`:** versionado, porque o `--write-locks` o gera e, sem ele, a regeneração suja a árvore.
- **E13 (f) Revisão humana da E13:** o lote parou nela, como manda o protocolo, e a branch foi integrada na `main` a pedido do usuário. A revisão fica para quando ele voltar.

**Decisões do lote E14** (entrevista do fim do lote). Em 2026-11-04, o usuário delegou toda intervenção, pedindo a opção recomendada depois de uma avaliação crítica dos prós e contras. Por isso, as decisões abaixo foram **decididas pela opção recomendada em nome do usuário (delegado ao orquestrador), 2026-11-04**. O detalhe está no handoff da E14.

- **E14 (a) `opentelemetry-api` 1.62.0 (GHSA-ccrj-gw8r-854m, Medium 5,3):** CPU quadrática no `tracestate`, alcançável sem autenticação. Fica aceito como risco registrado até o Boot trazer a ≥ 1.67.0. A resolução é obrigatória antes da operação real (M8), e cada revisão final reavalia o caso.
  - A favor: o sistema ainda não opera; a correção pelo BOM evita uma subida de minor fora do escopo; o scanner de cada revisão final volta a mostrar o risco.
  - Contra: o risco fica aberto até lá.
  - Alternativas descartadas: plataforma `opentelemetry-bom` 1.67.0 agora (subida de minor de toda a família, à frente do BOM do Boot e sem teste da ponte do Micrometer); limpar o `tracestate` no proxy (mexe no compose e perde o contexto de trace vindo de clientes).
- **E14 (b) `commons-lang3` 3.16.0 (Medium, classpath dos plugins) e `logback-core` 1.3.16 (Low, `ktlint`):** aceitos. Só rodam no build e não são alcançáveis por entrada externa. Alternativa descartada: restrições de versão próprias, que acrescentariam manutenção sem ganho de segurança no servidor.
- **E14 (c) Jackson 2 na 2.22.4:** último patch da linha 2.22, em vez do piso 2.22.3. Ele fecha as mesmas 7 GHSA e evita um patch atrás. Alternativa descartada: a 2.22.3 exata.

**Decisões do lote E07** (entrevista do fim do lote). Em 2026-11-04, o usuário delegou toda intervenção, pedindo a opção recomendada depois de uma avaliação crítica dos prós e contras. Por isso, as decisões abaixo foram **decididas pela opção recomendada em nome do usuário (delegado ao orquestrador), 2026-11-04 e 2026-11-05**. O detalhe está no plano da E07 (D-E07-1 a 12) e no handoff dela.

- **E07 (a) Fluxo de registro na FNRH (decisão aberta "reserva antes da chegada"):** a reserva é registrada **na criação**, sem dado pessoal. No check-in entram a pessoa e o hóspede com `CHECKIN_REALIZADO`, e o check-out sai por `PATCH`. O cancelamento só vale com a reserva em `CRIADA`. O no-show fica para o M6.
  - A favor: o link de pré-check-in chega cedo (RF-FNRH-04), o cancelamento chega ao Serpro e o fluxo segue os eventos de reserva que a E08 consome.
  - Contra: mais chamadas e a reserva fica sincronizada desde a criação.
  - Alternativa descartada: registro só no check-in (`POST /hospedagem/registrar`).
- **E07 (b) `cpf_solicitante`:** só nas operações em que a API v2.4 o exige (na porta, só `registerPerson`). O roadmap supunha o header em toda chamada; a fonte oficial prevalece e manda menos dado pessoal.
- **E07 (c) URL base:** a configuração guarda o ambiente (homologação ou produção), e as URLs ficam em propriedade. Trocar de ambiente exige a senha de novo. Descartadas: a URL livre, porque a credencial poderia ir para qualquer host, e uma propriedade só, porque cada troca pediria nova implantação.
- **E07 (d) Municípios IBGE:** lista oficial versionada no repositório, com fonte, data e SHA-256.
  - Risco aceito: um município criado depois do download é recusado até atualizar o recurso.
  - Alternativa descartada: validar só o formato, que não cumpre o critério.
- **E07 (e) Gênero `OUTRO`:** o `guests` não guarda a descrição que a FNRH exige. A validação acusa a falta, sem falsear o dado.
  - Recomendação: acrescentar a descrição do gênero ao perfil e ao `FnrhGuestData`, numa etapa própria ou na E08.
  - Raça e gênero não coletados também dão `REQUIRED`.
- **E07 (f) Menor só com RG:** a API aceita só CPF ou passaporte (nota B2 da E02). O hotel precisa colher o CPF do menor. Nenhuma alternativa técnica do nosso lado.
- **E07 (g) Registro de integração sem texto livre:** a mensagem do Serpro, saneada, vai só para o log e para a exceção, nunca para a tabela *append-only*, que não admite expurgo.
- **E07 (h) CPF por usuário sem `DELETE`:** desativação (`POST …/deactivate`) que apaga o CPF e mantém a linha, pela convenção do projeto "a API não apaga nada". O plano pedia `DELETE` e o build pegou o erro.
- **E07 (i) Limpar a credencial do Serpro:** hoje não há como apagá-la; ao desligar a integração, ela continua cifrada no banco. Fica como recurso a decidir. A recomendação é uma ação explícita de limpar, com trilha, antes do M8.
- **E07 (j) Correções extras depois da onda única da revisão final:** dois despachos mínimos.
  - Controle C1 no saneador.
  - `pattern` da senha válido em ECMA-262, mais U+2028 e U+2029.

  Motivo: deixar a `main` pronta para push com o build verde e o contrato válido para os geradores de SDK. Alternativa descartada: integrar com um teste vermelho como resíduo.

**Decisões da revisão de arquitetura** (`docs/superpowers/specs/2026-11-06-revisao-arquitetura-roadmaps-design.md`, 2026-11-06). Decididas pelo usuário em entrevista:

- **E07 (e) Descrição de gênero:** etapa própria antes do envio (E15). Descartadas: embutir na E08 e aceitar a limitação.
- **E07 (i) Limpar a credencial:** antes da credencial real (E16), como pré-requisito da E09. Descartada: deixar para a prontidão do M8.

**Decisões do lote E15** (entrevista do fim do lote). Em 2026-11-10, o usuário delegou toda intervenção, pedindo a opção recomendada depois de uma avaliação crítica dos prós e contras, salvo decisão de arquitetura. Por isso, as decisões abaixo foram **decididas pela opção recomendada em nome do usuário (delegado ao orquestrador), 2026-11-06 e 2026-11-10**. O detalhe está no plano da E15 (D-E15-1 a 6) e no handoff dela.

- **E15 (a) Cifra (D-E15-1):** a descrição fica cifrada (`EncryptedStringConverter`), como as restrições alimentares. Autodeclaração de identidade de gênero é dado pessoal e pode revelar dado sensível. Custo: mais um valor para a re-cifragem da M8 E15.
- **E15 (b) Tamanho (D-E15-2):** até 100 caracteres, sem `pattern`. A API v2.4 não documenta limite; a E09 confirma. Reduzir depois é mudança restritiva de contrato.
- **E15 (c) Erro (D-E15-3):** descrição com gênero diferente de `OTHER` → `400 GENDER_DESCRIPTION_REQUIRES_OTHER_GENDER`, sem repetir o valor.
- **E15 (d) Leitura (D-E15-4):** a descrição volta em toda resposta de perfil (`GuestResponse`), com `guests.profiles.read` (Gerente, Recepcionista e **Financeiro**), e fica fora do `GuestSummary`. Precedente: `dietaryRestrictions`. Contra: o Financeiro lê um dado de que não precisa. Reduzir depois é incompatível para clientes gerados, mas nenhum cliente consome o campo ainda.
- **E15 (e) Mesclagem (D-E15-5):** a descrição anda com o gênero.
- **E15 (f) Eco curto (D-E15-6):** descrição de 1 ou 2 caracteres ecoada pelo Serpro não é mascarada no log. Exceção literal ao critério "não aparece em log", em caso raro e hipotético; a E09 confere se o Serpro ecoa o campo.
- **E15 (g) Saneador:** passou a normalizar o caractere de controle também nos valores enviados (`8d6203a`), para todos os campos. O plano dizia "o saneador não muda"; a mudança só aumenta o mascaramento.
- **E15 (h) Leitura nas respostas das escritas (I-1 da revisão final):** `restrict`, `lift-restriction` e `merge` do `guests` devolvem o perfil inteiro, com a descrição e as restrições alimentares, conferindo só a permissão da escrita. Com os modelos de role padrão não há exposição (só o Gerente tem essas permissões, e ele lê). Uma role customizada com `guests.restrictions.manage` sem `guests.profiles.read` leria. A lacuna é do M3; a E15 só corrigiu o texto do contrato, do KDoc e do README para não prometer o contrário. **Decidido pelo usuário em 2026-11-11** (entrevista do fim do lote): numa etapa de manutenção do `guests`, o corpo completo dessas respostas passa a exigir `guests.profiles.read`; sem ela, um corpo mínimo. A etapa ainda não existe no roadmap: cabe ao `arquiteto` criá-la.

**Decisões do lote E08** (entrevista do fim do lote). Em 2026-11-10, o usuário delegou toda intervenção, pedindo a opção recomendada depois de uma avaliação crítica dos prós e contras, salvo decisão de arquitetura. Por isso, as decisões abaixo foram **decididas pela opção recomendada em nome do usuário (delegado ao orquestrador), 2026-11-10 e 2026-11-11**. O detalhe, com as alternativas descartadas, está no plano da E08 (D-E08-1 a 17) e no handoff dela. A E08 tem "Revisão humana: sim": o padrão de envio assíncrono que ela fixa é repetido por M7 e M8.

- **E08 (a) Eventos (D-E08-1):** `ReservationCreated` registra; `Changed`, `Confirmed` e `Reactivated` atualizam (`PUT` idempotente); `Cancelled` cancela; os três eventos de hospedagem fazem check-in, desfazer e check-out. A pré-reserva e a reserva sem documentos também são registradas (o registro não leva dado pessoal) e a expiração vira cancelamento no MTur. Descartadas: registrar só a partir de `CONFIRMED` (o link de pré-check-in chegaria tarde) e ignorar `Confirmed`/`Reactivated` (fere o critério). `RoomMoved` não gera envio.
- **E08 (b) Granularidade (D-E08-2):** uma submissão por evento, com o progresso por hóspede em `fnrh.remote_guest`. Descartada: uma por hóspede, que quebraria "uma por evento" e criaria dependência entre submissões para a ordem responsável → menor. A SPEC §8.2.17 foi atualizada.
- **E08 (c) Ordem (D-E08-3):** ordem causal por reserva (`version` do evento e posto da operação); `FAILED` e `INVALID` seguram as seguintes até `retry` ou `dismiss`. Descartada: ordem de inserção, que o reenvio do outbox embaralha.
- **E08 (d) Retry (D-E08-4):** 8 tentativas, de 1 min a 1 h (cerca de 2 h), também em `UNAVAILABLE`; falha permanente vira `FAILED` na hora. Descartadas: a curva do outbox até 24 h, `retry` em lote e credencial recusada sem gastar tentativa. Os números são propriedade.
- **E08 (e) Sem configuração (D-E08-5):** nada sai e nenhuma tentativa é gasta; ao ligar, o acumulado sai inteiro (corte decidido na E09 ou na M8 E13).
- **E08 (f) Pessoa e conciliação (D-E08-6, 7 e 15):** marca de escrita em curso antes de cada criação; conciliação antes de repetir; consulta por documento sempre antes de criar a pessoa; hóspede recorrente reaproveitado pelo `person_id` do ambiente.
- **E08 (g) Desfazer check-in e reativação (D-E08-8 e 9):** a API não tem as operações; com hóspede já no Serpro, `FAILED` (`NO_REMOTE_OPERATION` ou `REMOTE_CANCELLED`) com alerta, correção no portal e `dismiss`.
- **E08 (h) Encerramento manual (D-E08-16) e permissão (D-E08-12):** `POST …/dismiss` leva a `DISMISSED`, que não segura a fila; `fnrh.submissions.manage` só para o Gerente.
- **E08 (i) Link de pré-check-in (D-E08-13):** guardado cifrado em `fnrh.remote_reservation`, sem exposição na API.
- **E08 (j) Alerta em transação própria (ruling da Task 9):** o resultado é gravado mesmo que o alerta falhe; o alerta pode se perder, a submissão não.
- **E08 (k) Trava do job (ruling da Task 10):** `lockAtMostFor = PT1H`.
- **E08 (l) Erro neutro (I-3 da revisão final):** raça/cor e deficiência viram `person.sensitiveData`/`INVALID_VALUE`, um por hóspede. Contra: o operador não sabe qual dos campos falta.
- **E08 (m) Revalidação:** cada tentativa do `CHECK_IN` revalida todos os hóspedes, inclusive os já enviados. Contra: um perfil editado depois pode deixar a submissão `INVALID` e a revalidação decifra RN-19 sem necessidade. Recomendação: validar só os hóspedes ainda não enviados, numa manutenção do `fnrh`.
- **E08 (n) Menor com responsável menor listado depois (ruling da Task 7):** `FAILED` com alerta, nunca `responsavel_id` errado.
- **E05 (f) aplicada:** o `ReservationSearchApiTest` aceita os dois índices no filtro "period" (`8e6b677`), porque era o único vermelho no gate da E08.

**Decisões do lote E16** (entrevista do fim do lote). Em 2026-11-11, o usuário delegou toda intervenção, pedindo a opção recomendada depois de uma avaliação crítica dos prós e contras, salvo decisão de arquitetura. Por isso, as decisões abaixo foram **decididas pela opção recomendada em nome do usuário (delegado ao orquestrador), 2026-11-11**. O detalhe está no plano da E16 (D-E16-1 a 7) e no handoff dela.

- **E16 (a) Forma da ação (D-E16-1 e D-E16-7):** `POST /api/v1/fnrh/settings/clear-credentials`, sem corpo, com `If-Match` e sem `Idempotency-Key`, `200` com o `ETag` novo. Vira a regra de toda integração na ARCHITECTURE §12 (M7 e M8 citam "como a M5 E16"). Descartados: `DELETE` (a API não apaga nada) e um campo no `PUT` (ação implícita).
- **E16 (b) O que fica (D-E16-2):** o ambiente, o CPF por usuário, os ids remotos, o link de pré-check-in e o registro de chamadas. Contra: quem quiser apagar tudo usa outros caminhos (CPF por usuário: `deactivate`).
- **E16 (c) Repetição (D-E16-3):** `200`, mesma versão e nenhuma entrada nova na trilha, como o `PUT` sem mudança. Contra: a tentativa sem efeito não aparece na trilha.
- **E16 (d) Status sem sucesso registrado (ruling da Task 2):** a correção do `500` do `GET /api/v1/fnrh/status` (defeito da E07) entrou na E16, porque um critério dela dependia disso.

**Decisões do lote E10** (entrevista do fim do lote). Em 2026-11-11, o usuário delegou toda intervenção, pedindo a opção recomendada depois de uma avaliação crítica dos prós e contras, salvo decisão de arquitetura. Por isso, as decisões abaixo foram **decididas pela opção recomendada em nome do usuário (delegado ao orquestrador), 2026-11-11**. **Decididas pelo usuário em 2026-11-11** (revisão humana da E10, na entrevista): (a), (b), (c) e o "sem alerta" de (f), todas pela opção recomendada; (d), (e) e o "contato sem trilha" de (f) seguem revisáveis. O detalhe e as alternativas estão no plano da E10 (D-E10-1 a 11) e no handoff dela. A E10 tem "Revisão humana: sim".

- **E10 (a) Credencial de serviço no `shared` (D-E10-1):** porta `ServiceCredentialRealm` no kernel e uma segunda `SecurityFilterChain`, antes da de usuário, só nos prefixos dos realms; o `printing` registra o realm do agente. Descartada: a cadeia dentro do `printing`.
- **E10 (b) Contrato com o agente (D-E10-2, 6 e 9):** token `zpa_` + 32 bytes (só o SHA-256 guardado, exibido uma vez); evento SSE `job` com `id` = posição; ack `{result, failureCode}`; esquema `printAgentToken`; `200 []` no long-poll vazio e no despejo; o agente deduplica pelo id.
- **E10 (c) Entrega:** consulta ao banco a cada 1 s (sem sinal em memória); um stream por agente (o novo despeja o anterior); até 2 long-polls; o stream reivindica um job por vez e devolve o que não conseguiu escrever. Resíduo aceito: conexão meio aberta pode levar um job a `FAILED` em ~6 min.
- **E10 (d) Prazos e tetos (D-E10-4 e 5):** ack em 2 min, 3 entregas, `PENDING` vale 24 h, conteúdo apagado 24 h depois do fim; teto de 5 MiB pela `PrintingApi` e 700 KiB pela rota manual (o `@Idempotent` limita o corpo a 1 MB); base64 com preenchimento.
- **E10 (e) Ack:** `409` para job das próprias impressoras que não está `SENT` para o agente; `404` só para impressora de outro agente ou job inexistente; `result` só como texto.
- **E10 (f) Sem alerta de falha (D-E10-10)** e **contato do agente sem trilha (D-E10-11)**; impressora ativa de agente desativado tem os jobs encerrados pela manutenção.

**Decisões do lote E11** (entrevista do fim do lote). Em 2026-11-11, o usuário delegou toda intervenção, pedindo a opção recomendada depois de uma avaliação crítica dos prós e contras, salvo decisão de arquitetura. Por isso, as decisões abaixo foram **decididas pela opção recomendada em nome do usuário (delegado ao orquestrador), 2026-11-11 e 2026-11-12**. O detalhe e as alternativas descartadas estão no plano da E11 (D-E11-1 a 14) e no handoff dela. A E11 tem "Revisão humana: sim". **Decididas pelo usuário em 2026-11-12** (revisão humana da E11, na entrevista do fim do lote), todas pela opção recomendada: (a), (d), (e), (f), (g) e (h); (b), (c) e (i) seguem revisáveis.

- **E11 (a) Bibliotecas (D-E11-1; mantidas pelo usuário em 2026-11-12, com o aviso da LGPL a registrar na documentação de implantação do M8):** Thymeleaf (`thymeleaf-spring6` do BOM, sem o starter) e o fork mantido do openhtmltopdf (`io.github.openhtmltopdf:openhtmltopdf-pdfbox` 1.1.110, sobre PDFBox 3.0.8). O openhtmltopdf é **LGPL-2.1+**: se a imagem for distribuída a terceiros, é preciso avisar a licença e permitir trocar a biblioteca (o fat jar a mantém como jar separado). A imagem Docker passou de 171,5 MB para 178,0 MB (+6,6 MB; build de 115 s para 142 s). Descartadas: iText (AGPL), JasperReports, PDFBox direto, FreeMarker. Novas versões do fork saem automaticamente: atualizar só com revisão manual.
- **E11 (b) API de documentos no `printing` (D-E11-2 e 3):** `render`/`print` pela `PrintingApi`, templates registrados pelos donos, modelo só de mapas, listas e valores simples (até 32 níveis), layout e contexto do `printing`, corpo por um único `th:insert`, construções de template proibidas e varridas por teste; CSS só no layout (o template de dono não leva `<style>`).
- **E11 (c) Texto (D-E11-4):** NFC, controle vira espaço, formatação removida; sem fonte embutida: nome em escrita não latina sai com `?` no PDF e no ESC/POS. Embutir uma TTF (~700 KB) resolve depois.
- **E11 (d) Logotipo (D-E11-7):** `FilesApi.readContent` só para anexo **comum** (o sensível é recusado, mais estreito que a H18; recorte mantido pelo usuário em 2026-11-12: quem precisar ler sensível pelo servidor acrescenta a leitura com trilha na etapa que precisar); lido em transação própria (uma 2ª conexão do pool) e reduzido a 400 px; falha vira documento sem logotipo. Sem cache.
- **E11 (e) Rotas (D-E11-9, decisão aberta da E11):** rotas próprias `GET …/pdf` e `POST …/print` (`@Idempotent`, `202`), não negociação por `Accept`; `audience` padrão `GUEST` no PDF do extrato (o JSON segue `INTERNAL`); quem tem a permissão do dono imprime em qualquer impressora ativa; parâmetro de query repetido aceito; `Accept: application/json` na rota do PDF devolve o PDF.
- **E11 (f) Permissões (D-E11-10):** a ficha (inclusive o `GET`, leitura de dado pessoal sem trilha) sob `frontdesk.stays.checkin`, sem um `frontdesk.stays.read` novo. Mantido pelo usuário em 2026-11-12; a descrição da permissão deve passar a dizer que ela abre a ficha.
- **E11 (g) Ficha (D-E11-11, decisão aberta da E11):** nome social sem o civil; motivo de viagem e meio de transporte como **códigos FNRH crus**, com "(código FNRH)". A linha "menor com autorização" foi tirada por estar fora da lista. **Decidido pelo usuário em 2026-11-12:** os rótulos legíveis entram por uma porta do `frontdesk` implementada pelo `fnrh` (inversão por porta), numa etapa de manutenção que ainda não existe no roadmap: cabe ao `arquiteto` criá-la.
- **E11 (h) Extrato (D-E11-12):** porta `FolioOwnerDescriber` na raiz do `billing`, implementada pelo `reservations` para `RESERVATION` (M6 a M8 implementam `GROUP`, `EVENT`, `COMPANY`); descritor que lança derruba o extrato. Descartadas: rótulo gravado na conta, "extrato da reserva" no `reservations`, extrato sem nome nem UH.
- **E11 (i) Execução:** por falta de memória da máquina (14 GB; o kernel matou um Java e a sessão caiu duas vezes), as ondas passaram a um Gradle por vez depois da onda 1; a suíte da onda 1 rodou junto com a da onda 2.

**Ainda sem confirmação do usuário:** as decisões dos lotes E04 a E06, E13, E14, E07, E15 (salvo a (h), decidida em 2026-11-11), E08 (o padrão foi aprovado na revisão humana; as decisões (a) a (n) seguem revisáveis), E16, E10 (salvo as decididas na revisão humana da E10) e E11 (salvo as decididas na revisão humana da E11), tomadas pela opção recomendada, e a revisão humana da E13 e da E07.

## Definição global de pronto

- [x] `POST /api/v1/reservations/{id}/check-in` executa o fluxo da ARCHITECTURE §6.1 numa transação: UH não pronta → `409 ROOM_NOT_READY`; sem UH alocada → `409 ROOM_NOT_ASSIGNED`; menor sem comprovação → `422 MINOR_AUTHORIZATION_REQUIRED`; falha no meio não deixa caução, lançamento, transição nem ocupação; sucesso deixa a reserva `IN_HOUSE`, a UH Ocupada e publica `GuestCheckedIn`. **Evidência (E12):** `MilestoneM5EndToEndTest` (as três recusas sem caução, lançamento, transição nem ocupação; `IN_HOUSE`, UH Ocupada, `GuestCheckedIn`) e `FrontdeskCheckInApiTest` nas três rodadas da cópia limpa; roteiro do compose §4.
- [x] Desfazer check-in exige permissão ou aprovação de superior (`403 APPROVAL_REQUIRED`), só na mesma data operacional, e fica na trilha com solicitante e aprovador. **Evidência (E12):** `MilestoneM5EndToEndTest` (`403 APPROVAL_REQUIRED`, aprovação do Gerente, trilha com o solicitante e o `approval_id`, `iam.approval` com os dois, token preso à reserva e de uso único, `422 UNDO_CHECK_IN_EXPIRED`); roteiro §9.
- [x] Walk-in, check-out (saldo zero, cauções liberadas, contas encerradas, UH Vaga + Suja, alocação encerrada), troca de UH, extensão e redução do hospedado, early/late com o preço informado antes e recados têm endpoints no contrato e testes de integração. **Evidência (E12):** `MilestoneM5EndToEndTest` (walk-in, troca de UH, extensão e redução com preços congelados, early e late cotados antes, check-out com caução liberada, contas encerradas, UH Vaga e Suja e alocação encerrada) e os testes de E03 a E06 na cópia limpa; roteiro §4, §7 a §10; rotas no `api/openapi.yaml`.
- [x] Eventos de reserva e de hospedagem geram `FnrhSubmission` enviadas ao adaptador Serpro (WireMock), com retry, ordem por reserva, identificadores remotos guardados, pendências listadas e alerta de falha sem dado de hóspede; com o Serpro fora do ar o check-in conclui. **Evidência (E12):** `MilestoneM5EndToEndTest` (envio em ordem, ids remotos, `SENT`; Serpro fora do ar: check-in `200`, `FAILED` na 8ª tentativa, alerta só com o código da reserva, reenvio sem duplicar o registro) e `FnrhStageEndToEndTest`; roteiro §5 e §11 com o WireMock na rede do compose.
- [x] Agente de impressão autenticado recebe só os jobs das próprias impressoras, confirma, e jobs não confirmados são reenviados; o token fica só como hash. **Evidência (E12):** `MilestoneM5EndToEndTest` (cada agente recebe só o job da própria impressora, `404` no conteúdo e no ack do alheio, `PRINTED`) e os testes da E10 (reenvio); roteiro §3 (o token não aparece no schema `printing`, só o SHA-256) e §6 (SSE, ack, recusas cruzadas de token e JWT).
- [x] Ficha de registro, extrato e confirmação de reserva saem em PDF e em job de impressão, com os dados do hotel pela `HotelApi` e sem dado de RN-19 nem número inteiro de documento. **Evidência (E12):** `MilestoneM5EndToEndTest` (PDF da ficha com o documento mascarado e sem o CPF inteiro; horários da política na confirmação) e `DocumentsStageEndToEndTest`; roteiro §6 e §7.
- [x] `ApplicationModules.verify()` passa, com um teste por módulo novo no `ModularityTest` e as arestas na ARCHITECTURE §3.1; build, testes, `checkOpenApi` e oasdiff sem `ERR` contra `ffae917`. **Evidência (E12):** cópia limpa de `6c16557` (`git archive`, `GRADLE_USER_HOME` novo, `--no-daemon --no-build-cache`): `clean build` com 3228 testes e 0 falhas, mais duas rodadas de `clean test` iguais, `lint checkOpenApi` verde; `allowedDependencies` dos módulos do marco contidos na §3.1; `.github/scripts/check-api-breaking.sh ffae917` sem `ERR`.
- [x] O compose isolado do gate mostra as publicações dos eventos de reserva e de hospedagem concluídas pelo listener do `fnrh`. **Evidência (E12):** roteiro do compose isolado a partir da cópia limpa (302 conferências, 0 falhas): `shared.event_publication` com o listener do `fnrh` `COMPLETED` para `ReservationCreated`, `GuestCheckedIn` (§5) e `GuestCheckedOut` (§8).
- [x] A homologação real da FNRH (E09) está concluída ou registrada como pendência obrigatória antes da operação real. **Evidência (E12):** a E09 segue `#bloqueada` pela Q-12, registrada como pendência obrigatória antes da operação real; o gate de inauguração (M8 E13) a cobra.
- [x] `gradle.lockfile` versionado e atualizado; `osv-scanner scan source -r .` lista os pacotes do projeto. **Evidência (E12):** `gradle.lockfile`, `buildscript-gradle.lockfile` e `settings-gradle.lockfile` versionados; `dependencies --write-locks` na cópia limpa sem diferença; `osv-scanner scan -r --no-ignore .` lista 426 + 74 pacotes.
- [x] Nenhuma vulnerabilidade High ou Critical conhecida no `productionRuntimeClasspath` nem no classpath dos plugins na data do gate, salvo as registradas como risco aceito pelo usuário (E14). **Evidência (E12):** 0 Critical e 0 High; 2 Medium e 3 Low, os riscos aceitos na E14.
- [x] Hóspede de gênero `OUTRO` com descrição é enviado à FNRH (E15), e a credencial do Serpro pode ser apagada por uma ação com trilha (E16). **Evidência (E12):** `MilestoneM5EndToEndTest` (walk-in de gênero `OUTRO` com a `GeneroDescricao` no corpo enviado) e roteiro §9 e §12 (`clear-credentials` com trilha sem usuário nem senha).

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

- Toda execução acontece no worktree/branch `roadmap/m5-recepcao-fnrh`, criado ou retomado com `superpowers:using-git-worktrees`. Nunca execute na branch principal (`main`).
- Se a branch já existir com trabalho não integrado, continue nela. Se o lote anterior já foi integrado, crie a branch de novo a partir da `main` atualizada.
- Este roadmap é atualizado dentro da branch: tags e handoffs viajam com o código.

**Por etapa**

1. **Escolha:** a primeira etapa, na ordem do documento, marcada `#pendente` e com todas as dependências `#concluida`. Etapas `#bloqueada` e as que dependem delas ficam para trás.
2. **Conferência:** leia a seção da etapa, os handoffs das dependências e o código que eles citam. Se o código divergiu do que a etapa supõe, pare e relate.
3. **Início:** troque a tag para `#em-andamento` (índice e título).
4. **Plano:** use `superpowers:writing-plans`. A spec é a seção desta etapa mais os documentos citados em "Contexto confirmado"; as "Restrições e invariantes" entram como Global Constraints. Salve em `docs/superpowers/plans/AAAA-MM-DD-m5-recepcao-fnrh-eNN.md` (AAAA-MM-DD = a data do dia). Antes de executar, o plano passa pela revisão de arquitetura do `orquestrador` (`revisor-arquitetura`); as regras (rodadas, veredito, seção no plano) vivem só nele. A execução já está definida (abaixo): não pergunte ao usuário o método, apenas siga.
5. **Execução:** use `superpowers:subagent-driven-development` com esse plano, executado pelas souls conforme o ciclo por tarefa, a verificação mecânica dos testes e a revisão final da etapa definidos no `orquestrador` (as regras de encaixe vivem só nele). Não invoque `superpowers:finishing-a-development-branch` ao fim da etapa: ele roda uma vez, no fim do lote.
6. **Fechamento:** publique a captura de atualização do brain (bloco "Second Brain"). Marque `#concluida` (índice e título) só com a revisão final limpa e cada critério de aceite verificado com evidência. Diante de impedimento real, marque `#bloqueada` e preencha "Bloqueios" com causa, evidência, impacto e ação de desbloqueio. Preencha "Evidências e handoff". Rulings do ledger que mudam contrato, escopo, segurança, dados ou algo caro de desfazer vão para "Decisões para o usuário".

**Second Brain**

Vale se o `CLAUDE.md` tiver a seção "Conhecimento do projeto no Second Brain"; senão, ignore este bloco.

- O contexto da sessão precisa trazer a linha `BRAIN: carregado (ZenyHotelManager)`, injetada pelo hook. Sem ela, pare e relate.
- Na conferência de cada etapa, leia as notas de tema do brain ligadas a ela (pelo hub `ZenyHotelManager`) e as capturas pendentes do projeto que tratem do assunto: são a referência técnica do projeto. Num handoff antigo, "seção X do `CLAUDE.md`" é a nota de tema correspondente.
- No fechamento de cada etapa que mudou algo descrito no brain (inclusive o estado do roadmap), o orquestrador manda o `brain-escriba` publicar em `00 Inbox/` do vault a captura `ZenyHotelManager - atualização m5-recepcao-fnrh Eyy`, com o texto novo ou alterado de cada nota afetada e qual nota ele substitui ou complementa; o contrato de agente externo do vault fica com o `brain-escriba`. Nunca edite notas do brain nem registre referência técnica no `CLAUDE.md`.

**Paradas**

Qualquer outra necessidade de intervenção é decidida pelo orquestrador pela opção recomendada, depois de avaliar prós e contras, com a ruling registrada; só decisão de arquitetura ou system design espera o usuário (regras em "Decisões" do `orquestrador`). O lote para ao concluir uma etapa com **Revisão humana: sim**, diante de decisão de arquitetura sem a qual nada mais anda, nas paradas que ficam com o usuário (push, deploy, publicação, instalação na máquina, apagar trabalho fora da etapa, segredo exposto), quando não resta etapa elegível e ao atingir o limite de etapas do prompt. Etapa que fica `#bloqueada` não para o lote: o orquestrador segue para a próxima elegível que não depende dela.

**Fim do lote**

1. Use `superpowers:finishing-a-development-branch` com a integração já decidida: merge local na `main`, conforme o passo de fechamento do `orquestrador` (branch atualizada e com gate verde, `merge --no-ff`, repositório limpo e pronto para o próximo orquestrador). Push e deploy ficam com o usuário: o relatório termina com o comando `git push origin main` pronto.
2. Liste as "Decisões para o usuário" acumuladas, que já foram tomadas pela opção recomendada, com prós e contras, para revisão. As de arquitetura que esperam o usuário vão como entrevista: perguntas de múltipla escolha, cada uma com uma opção recomendada e justificada (no Claude Code, `AskUserQuestion` com a recomendada em primeiro lugar e o rótulo "(Recomendado)"). Registre as respostas no handoff das etapas e em "Hipóteses e decisões em aberto".

## Prompt de execução

```text
Execute o roadmap docs/roadmaps/roadmap-m5-recepcao-fnrh.md deste repositório seguindo a seção "Protocolo de execução" dele, com o plugin superpowers.

Limite desta execução: no máximo 3 etapas concluídas.

1. Confira a pré-condição do protocolo: esta sessão roda como orquestrador e as souls desenvolvedor, testador, revisor-seguranca e revisor-arquitetura estão disponíveis; se não, pare e diga como resolver. Leia CLAUDE.md (e AGENTS.md, se existir) e, no roadmap, o cabeçalho inteiro, o protocolo e o índice. Se o CLAUDE.md exigir o Second Brain, confira a linha BRAIN: carregado no contexto e leia o hub. Rode git status e git branch -a.
2. Se alguma etapa estiver #em-andamento, retome-a a partir do ledger do superpowers e do handoff; não recomece tarefas já commitadas.
3. Crie ou retome o worktree/branch roadmap/m5-recepcao-fnrh com superpowers:using-git-worktrees. Não trabalhe na branch principal.
4. Repita, uma etapa por vez: escolha a próxima etapa elegível, marque #em-andamento, planeje com superpowers:writing-plans (spec = seção da etapa + documentos citados), execute com superpowers:subagent-driven-development, feche a etapa (com a captura de atualização do brain) e preencha o handoff conforme o protocolo.
5. Pare nas paradas do protocolo ou ao atingir o limite.
6. No fim, rode superpowers:finishing-a-development-branch com merge local na main, conforme o fechamento do orquestrador: tudo commitado, pronto para git push origin main e o repositório pronto para o próximo orquestrador. Depois, liste as decisões tomadas e apresente como entrevista com opção recomendada só as de arquitetura que esperam por mim.

Qualquer necessidade de intervenção: escolha a opção recomendada depois de uma avaliação crítica dos prós e contras e registre a ruling; intervenção humana só para decisão de arquitetura ou system design. Nunca faça push ou deploy. Não altere tags de etapas que não executou.

Ao parar, responda com: etapas concluídas e o resultado da revisão final de cada uma, a etapa atual e seu estado, o motivo da parada, as etapas #bloqueada com a ação de desbloqueio, a branch e os commits do lote, as capturas do brain publicadas e o próximo passo sugerido.
```

## Índice de etapas

**Recepção**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E01 | Transições de hospedagem em reservations | #concluida | — | `checkIn`, `undoCheckIn`, `checkOut`, `moveRoom`, alteração do hospedado, `assignRoom` na API pública e eventos de hospedagem |
| E02 | Check-in orquestrado | #concluida | E01 | Módulo `frontdesk`, `POST /reservations/{id}/check-in` numa transação, desfazer check-in sensível e cotação de early/late |
| E03 | Walk-in | #concluida | E02 | Reserva, alocação e check-in no mesmo fluxo |
| E04 | Check-out | #concluida | E02 | Saldo zero, cauções liberadas, contas encerradas, UH Vago + Sujo, late check-out |
| E05 | Troca de UH, extensão, redução e early check-in | #concluida | E02 | Troca de UH com status das duas UHs, `RoomMoved`, extensão e redução do hospedado, cobrança do early |
| E06 | Recados | #concluida | E02 | Recados por setor vinculados a reserva ou UH |

**Cadeia de dependências**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E13 | Travamento de dependências do Gradle para o scanner | #concluida | — | `gradle.lockfile` versionado e lido pelo `osv-scanner`, build falha com lockfile desatualizado |
| E14 | Correção das vulnerabilidades High/Critical apontadas pelo scanner | #concluida | E13 | Tomcat, Jackson 3 e 2 e o freemarker dos plugins em versões corrigidas, lockfiles regravados e CI conferindo lockfile × catálogo |

**FNRH**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E07 | Configuração, domínios e adaptador Serpro da FNRH | #concluida | — | Módulo `fnrh`, credenciais cifradas, CPF solicitante, domínios, validação local, `FnrhGateway` com WireMock |
| E15 | Descrição de gênero no perfil do hóspede | #concluida | E07 | Campo no perfil e nos dados da FNRH: o hóspede de gênero `OUTRO` passa a poder ser enviado |
| E08 | Envio assíncrono e pendências da FNRH | #concluida | E01, E02, E07, E15 | `FnrhSubmission` por evento, job de envio com retry e ordem, IDs remotos, pendências, alerta, métrica e view |
| E16 | Limpar a credencial do Serpro | #concluida | E07 | Ação explícita, com trilha, que apaga a credencial e desliga a integração |
| E09 | Homologação da FNRH com o Serpro | #bloqueada | E08, E16 | Envios reais no ambiente de homologação |

**Impressão e documentos**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E10 | Agentes, impressoras e fila de impressão | #concluida | — | Módulo `printing`, agente com token próprio, jobs por SSE e long-poll, confirmação e reenvio |
| E11 | Documentos: ficha, extrato e confirmação | #concluida | E02, E10 | API de renderização em `printing`, cabeçalho pela `HotelApi`, PDFs e impressão nos módulos donos |

**Gate**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E12 | Gate do marco M5 | #concluida | E03, E04, E05, E06, E08, E11, E14, E16 | Definição global de pronto verificada numa cópia limpa e num compose isolado |

---

## E01 — Transições de hospedagem em reservations — #concluida

**Objetivo:** dar ao `reservations` as transições de hospedagem da SPEC §8.4 e §8.2.6 — check-in, desfazer check-in (mesmo dia), check-out (inclusive antecipado), troca de UH do hospedado e alteração da estadia do hospedado — com os dados de check-in dos hóspedes e a comprovação de menores (H10), a alocação pela API pública e os eventos `GuestCheckedIn`, `GuestCheckInUndone`, `GuestCheckedOut` e `RoomMoved`.

**Por que agora:** o `frontdesk` orquestra, mas o estado da reserva, da alocação e do inventário é do `reservations` (ARCHITECTURE §6.1). Sem essas transições nenhuma etapa da recepção avança, e o `fnrh` (E08) e o `housekeeping` (M6) dependem dos eventos.

**Dependências e paralelismo:** nenhuma dentro deste roadmap; exige o gate do M4 `#concluida` (atendido em `ffae917`). Pode correr em paralelo com E07 e E10 (worktrees separados); conflito possível em `api/openapi.yaml`, `CLAUDE.md` e `README.md`.

**Escopo:** pacote `reservations` (existente): domínio (`domain/ReservationLifecycle.kt`, `domain/RoomAssignments.kt`), casos de uso em `application/`, tipos e eventos na raiz (`ReservationsApi.kt`, `ReservationsEvents.kt`, `RoomAssignments.kt`, `ReservationChanges.kt`), persistência em `infrastructure/JdbcBookingStore.kt` e `JdbcRoomAssignmentStore.kt`, e uma migração com timestamp posterior a `V2026_10_22_0427`.

- **Dados de check-in** (H10): no hóspede da estadia, o motivo de viagem e o meio de transporte (códigos opacos dos domínios da FNRH, nunca conferidos aqui, H21) e os instantes de entrada e saída; a comprovação do menor (`MinorAuthorization`: tipo `PARENT_PRESENT`, `WRITTEN_AUTHORIZATION` ou `COURT_ORDER`, o responsável — hóspede da estadia —, o parentesco, o id do anexo, obrigatório nos dois últimos, e a validade opcional); na reserva, a data operacional, o instante e o autor do check-in e do check-out.
- **`ReservationsApi.assignRoom`** sem permissão (Ajuste D 4 da M4 E10), com as regras de `POST /reservations/{id}/assign-room`, para o check-in e o walk-in.
- **`checkIn`** (`ReservationsApi`, sem permissão, na transação de quem chama): só `CONFIRMED` (`409 RESERVATION_TRANSITION_NOT_ALLOWED` com a ação `CHECK_IN`; a pré-reserva confirma antes); a data operacional dentro da estadia (decisão da etapa para a chegada atrasada); UH alocada (`409 ROOM_NOT_ASSIGNED`) que continua ativa e do tipo da reserva (Ajuste D 1; senão `422`); a lista completa dos hóspedes da estadia (exatamente um principal, até a ocupação da reserva; mesclado vira o sobrevivente, anonimizado `422 GUEST_ANONYMIZED`, pela mesma resolução da criação) com os dados de check-in; **RN-24 no domínio**: hóspede menor na data (`GuestsApi.isMinorOn`) sem comprovação `422 MINOR_AUTHORIZATION_REQUIRED`, sem data de nascimento `422 GUEST_BIRTH_DATE_REQUIRED`, responsável que não é hóspede adulto da estadia `422`; grava `IN_HOUSE`, o histórico `CHECK_IN` e publica `GuestCheckedIn`. Não confere RN-05 nem mexe no `rooms` (é do `frontdesk`).
- **`undoCheckIn`**: `IN_HOUSE` → `CONFIRMED` só na mesma data operacional do check-in (`422 UNDO_CHECK_IN_EXPIRED`); limpa os dados do check-in (a comprovação do menor fica registrada no histórico); histórico e `GuestCheckInUndone`. A permissão sensível é do `frontdesk` (E02).
- **`checkOut`**: `IN_HOUSE` → `CHECKED_OUT`; **check-out antecipado** (a data operacional antes da véspera da saída): a saída passa à data operacional, as noites seguintes devolvidas ao inventário numa só `RoomTypeInventory.apply` (no pick-up, ao bloqueio ativo, como `BlockUnits`), as noites congeladas que ficam não mudam; a alocação **encerrada na mesma transação** (motivo de soltura novo, ex.: `CHECKED_OUT`, com o `CHECK room_assignment_release_ck` e o `enum` atualizados; Ajuste D 2); histórico `CHECK_OUT` e `GuestCheckedOut`.
- **`moveRoom`** (hospedado): encerra a alocação na data operacional e abre outra até a saída (motivo de soltura novo, ex.: `ROOM_MOVED`), com o `EXCLUDE` dos meios-dias e a trava da UH do M4 (`409 ROOM_ALREADY_ASSIGNED`); UH ativa e sem OOO nas noites restantes; motivo da troca (`RoomAssignmentReason`); tarifa mantida (as noites não mudam) ou recalculada (as noites restantes reprecificadas pela `RatesApi.priceFor`, RN-14 nas passadas); a troca para outro tipo move o inventário das noites restantes numa só `apply` (decisão da etapa se entra agora). Publica `RoomMoved` (reserva, UH de origem e de destino, data).
- **Alteração do hospedado:** o `PUT /api/v1/reservations/{id}` e o caso de uso da M4 E08 passam a aceitar `IN_HOUSE` só para a saída (extensão e redução) e, se a etapa decidir, a ocupação; a chegada, o tipo e o plano ficam fixos (`409` com `code` estável); a extensão toma o inventário e estende a alocação (UH ocupada por outra reserva → `409 ROOM_ALREADY_ASSIGNED`, nada muda); a redução devolve as noites a partir da data operacional; `ReservationChanged` como hoje.
- **Eventos** (H16): `GuestCheckedIn`, `GuestCheckInUndone`, `GuestCheckedOut` e `RoomMoved` com ids, datas, situações e `performedBy` (id do usuário; nulo = sistema), sem dado de hóspede; pelo outbox, na transação. Sem consumidor de produção até a E08: testes com `@EnableScenarios` e um listener de teste (no `InventoryTestConfiguration`, como o `ReservationLifecycleProbeListener`).
- **Ciclo de vida existente:** `GuestActiveStayCheck`, listas operacionais e views já contam `IN_HOUSE`; conferir que a recontagem do inventário (`ReservationsInventoryUsage`) conta o hospedado e não conta o encerrado, e que o listener de `GuestsMerged` reponta os responsáveis das comprovações.

**Fora de escopo:** conferência de UH pronta, ocupação e status da UH, caução, contas e endpoints de recepção (E02 a E05, no `frontdesk`); no-show (M6, H15); cotação e cobrança de early/late (E02, E04, E05).

**Passos de implementação:**

1. Ler o handoff da E13 do M4 e as notas para o M5 da E10 a E12 do roadmap do M4; listar no handoff o que já existe e o que falta.
2. Escrever testes de domínio para cada transição válida e inválida (SPEC §8.4), RN-24 e o check-out antecipado.
3. Escrever testes de integração (Testcontainers) com `Scenario` para os eventos, o `EXCLUDE` na troca e na extensão, o inventário na redução e no check-out antecipado (inclusive do pick-up) e os gatilhos adiados.
4. Implementar, regenerar o contrato (o `PUT` muda de descrição; os `enum` de soltura e de histórico ganham valores) e atualizar `CLAUDE.md` e `README.md`.

**Revisão humana:** não (executada no formato antigo, estratégia `padrão`)

- Investigação (`Explore`): "como `ReservationLifecycle`, `RoomAssignments`, `BlockUnits` e `RoomTypeInventory` do `reservations` implementam a alteração, o cancelamento, a alocação e o inventário, e o que os gatilhos `room_assignment_reservation_active`, `reservation_assignment_released`, `room_assignment_guarded` e `room_assignment_release_ck` exigem?"; "que regras SPEC §5.8 (RF-REC-01, 04, 05, 08), §8.2.6 e §8.4 impõem a check-in, check-out, troca de UH e mudança de estadia?".
- Partes paralelas: nenhuma.
- Foco do verificador: `undoCheckIn` aceito em outra data operacional; menor `IN_HOUSE` sem comprovação por algum caminho; check-out (inclusive antecipado) que deixa a alocação ativa ou não devolve o inventário; troca de UH que deixa duas alocações sobrepostas; evento publicado antes do commit ou com dado de hóspede; alteração de hospedado que muda a chegada ou o preço das noites passadas.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E01 — "Transições de hospedagem em reservations" do roadmap docs/roadmaps/roadmap-m5-recepcao-fnrh.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Contexto confirmado", o "Protocolo de execução" e as hipóteses H2, H10, H15 e H16 —, a seção E01 inteira e, em docs/roadmaps/roadmap-m4-tarifas-reservas.md, o "Evidências e handoff" da E13 e as notas para o M5 da E10 (Ajuste D), E11 e E12. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que o gate do M4 (E13) está #concluida e que o código bate com o handoff dele; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: no módulo reservations, criar check-in (só CONFIRMED, UH alocada ativa e do tipo, hóspedes com os dados de check-in, RN-24 no domínio com MinorAuthorization), desfazer check-in (mesma data operacional), check-out (inclusive antecipado, devolvendo o inventário e encerrando a alocação na mesma transação), troca de UH do hospedado (tarifa mantida ou recalculada) e alteração da saída do hospedado pelo PUT existente, mais ReservationsApi.assignRoom sem permissão e os eventos GuestCheckedIn, GuestCheckInUndone, GuestCheckedOut e RoomMoved com performedBy e sem dado de hóspede. Não crie endpoints de recepção nem chame rooms para status, billing ou o futuro frontdesk.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E01 para #em-andamento no índice e no título;
- investigação: um subagente Explore responde às perguntas da seção E01;
- escreva os testes de domínio e de integração primeiro (com Scenario e um listener de teste), implemente com a migração depois de V2026_10_22_0427, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh e atualize CLAUDE.md e README.md;
- verificação independente com o Prompt do verificador, com o foco listado na seção E01.

Critérios de aceite (detalhes na seção E01):
- transições válidas e inválidas de §8.4 com testes; RN-24 garantida no domínio;
- check-out (inclusive antecipado) devolve o inventário e encerra a alocação; troca e extensão respeitam o EXCLUDE;
- os quatro eventos publicados pelo outbox depois do commit, sem dado de hóspede;
- ./gradlew build verde e oasdiff sem ERR.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios". Pontos que cabem ao usuário (decisões abertas da E01 no cabeçalho) vão ao handoff como perguntas, com a opção recomendada aplicada provisoriamente.

No fim, preencha "Evidências e handoff" de E01 (snapshot, arquivos alterados, o que já existia e o que foi acrescentado, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Testes de domínio cobrem check-in, desfazer, check-out (inclusive antecipado), troca de UH e alteração do hospedado, válidas e inválidas (ex.: check-in de reserva `CANCELLED` ou `PROVISIONAL` → `409 RESERVATION_TRANSITION_NOT_ALLOWED`).
- [ ] Menor sem comprovação → `422 MINOR_AUTHORIZATION_REQUIRED`; sem data de nascimento → `422 GUEST_BIRTH_DATE_REQUIRED`; com comprovação, o tipo, o responsável e o parentesco ficam gravados (teste de integração).
- [ ] Desfazer check-in fora da data operacional do check-in → `422 UNDO_CHECK_IN_EXPIRED`.
- [ ] Check-out deixa a reserva `CHECKED_OUT` e a alocação encerrada na mesma transação; o antecipado devolve as noites seguintes ao inventário (ou ao bloqueio do pick-up) e mantém o preço das noites que ficam (teste com os contadores).
- [ ] Troca de UH para UH ocupada no período → `409 ROOM_ALREADY_ASSIGNED` (inclusive por `INSERT` direto recusado com `23P01`); troca válida encerra a alocação antiga na data e cria a nova; "mantendo tarifa" não muda as noites, "recalculando" muda só as restantes.
- [ ] `PUT /api/v1/reservations/{id}` num hospedado: extensão toma o inventário e estende a alocação; redução devolve; mudar a chegada, o tipo ou o plano → `409` com `code` estável.
- [ ] `GuestCheckedIn`, `GuestCheckInUndone`, `GuestCheckedOut` e `RoomMoved` publicados depois do commit (teste com `Scenario`), nenhum com transação desfeita, sem nome nem documento.
- [ ] `ReservationsApi.assignRoom` aloca sem permissão, com as regras do endpoint.
- [ ] `./gradlew build` verde (inclui `ModularityTest`, `checkOpenApi`) e `.github/scripts/check-api-breaking.sh` sem `ERR`.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Etapa grande (cinco transições): se a troca de UH para outro tipo com recálculo pesar demais, a etapa entrega a troca no mesmo tipo e registra o restante como decisão da E05, sem deixar a E05 sem a transição básica.
- Gatilhos adiados do M4 recusam no commit: todo teste de transição confirma a transação (não só o caso de uso) para pegá-los.

**Evidências e handoff:**

- Snapshot inicial: `7e2fa792d2d9e2c1294584de52c672bbd3637df9` (tirado pelo orquestrador). Preparação: `git status` limpo antes da etapa; o gate do M4 (E13) está `#concluida` e o código bate com o handoff dele (o `reservations` sem transição que grave `IN_HOUSE`/`CHECKED_OUT`, sem `assignRoom` na API pública e sem eventos de hospedagem; a alteração e a alocação só de pré-reserva e confirmada; os gatilhos adiados da E10 e o `room_assignment_release_ck` como descritos). A investigação (perguntas da seção) foi feita pela própria sessão, sem subagente, como o adendo do orquestrador manda.
- O que já existia e o que foi acrescentado:
  - **Já existia:** `ReservationStatus.IN_HOUSE`/`CHECKED_OUT` (sem transição), as listas operacionais, o `GuestActiveStayCheck`, a recontagem e as views já contando `IN_HOUSE`; a alocação com o `EXCLUDE` dos meios-dias, a trava por UH e os gatilhos adiados `room_assignment_reservation_active`/`reservation_assignment_released`; o `BlockUnits` (devolução ao bloqueio); a `GuestResolution` da criação; o ciclo de vida da E08 (alteração, histórico, trilha).
  - **Acrescentado:** os tipos públicos de hospedagem (`reservations/StayTransitions.kt`), os campos de check-in em `StayGuestSummary` e `ReservationSummary` (`checkIn`, `checkOut`), os quatro eventos com `performedBy` (`ReservationsEvents.kt`), as transições na `ReservationsApi` (`assignRoom`, `checkIn`, `undoCheckIn`, `checkOut`, `moveRoom`), as regras puras (`domain/StayTransitions.kt`), os casos de uso (`application/StayTransitions.kt`), o `ReservationHistoryRecorder` (histórico e trilha extraídos do `ReservationLifecycle`, reusados pelas transições), a alteração do hospedado no `PUT` (`InHouseChangeRules`, `RoomAssignments.followInHouseChange`), o encerramento da alocação (`RoomAssignmentStore.end`, `RoomAssignments.endOnCheckOut`/`moveInHouse`/`requireForCheckIn`), `RoomAssignmentService.assignAuthorized`, os códigos de erro (`CHECK_IN_INVALID`, `CHECK_IN_BEFORE_ARRIVAL`, `GUEST_BIRTH_DATE_REQUIRED`, `MINOR_AUTHORIZATION_REQUIRED`, `MINOR_AUTHORIZATION_INVALID`, `UNDO_CHECK_IN_EXPIRED`, `ROOM_MOVE_SAME_ROOM`, `ROOM_MOVE_INVALID`, `IN_HOUSE_STAY_FIXED`, `IN_HOUSE_DEPARTURE_INVALID`), a migração `V2026_10_22_1546__add_stay_transitions.sql` (colunas de check-in e check-out, dados de check-in e comprovação em `reservation_guest`, os tipos novos do histórico, os motivos `CHECKED_OUT` e `ROOM_MOVED` no `room_assignment_release_ck` e três gatilhos adiados novos: a encerrada sem alocação ativa, a hospedada sempre com uma), o reapontamento do responsável da comprovação no listener de `GuestsMerged`, os dados de check-in na exportação do titular (`stayData`), os campos novos nas respostas (`checkIn`, `checkOut`, `guests[].travelReason`, `transportMode`, `checkedInAt`, `checkedOutAt`, `minorAuthorization`; no histórico, `checkIn`, `checkOut`, `roomId`, `stayGuests`), o contrato regenerado e as seções do `CLAUDE.md` e do `README.md`.
- Arquivos alterados (produção): `src/main/kotlin/com/zeny/hotel/reservations/{Bookings,ReservationChanges,ReservationsApi,ReservationsErrorCodes,ReservationsEvents,RoomAssignments,StayTransitions}.kt`; `application/{BookingPorts,BookingService,InventoryService,ReservationLifecycle,RoomAssignmentPorts,RoomAssignments,RoomTypeInventory,StayTransitions}.kt`; `domain/{ReservationLifecycle,StayTransitions}.kt`; `infrastructure/{JdbcBookingStore,JdbcRoomAssignmentStore}.kt`; `web/{BookingsController,ReservationLifecycleDtos,RoomAssignmentsController}.kt`; `src/main/resources/db/migration/reservations/V2026_10_22_1546__add_stay_transitions.sql`; `api/openapi.yaml`; `CLAUDE.md`; `README.md`. Testes: novos `StayTransitionsDomainTest` (8) e `StayTransitionsApiTest` (14, depois das correções); ajustados `ReservationLifecycleDomainTest` (a tabela da máquina de estados), `ReservationLifecycleApiTest` e `ReservationSearchApiTest` (o hospedado e o encerrado gravados por SQL passaram a `ReservationsTestSupport.forceInHouse`/`forceCheckedOut`, porque os gatilhos novos exigem a UH da hospedada), `ReservationsTestSupport` (o `StayTransitionsProbeListener`, `forceInHouse`, `forceCheckedOut` e o listener na `lifecyclePublications`) e `InventoryTestSupport` (o listener no `InventoryTestConfiguration`).
- Decisões (provisórias, pela opção recomendada; as que cabem ao usuário estão em "Perguntas para o usuário"):
  1. **Chegada atrasada:** o check-in vale com a data operacional dentro da estadia (pernoite: da chegada à véspera da saída; day use: a data); as noites anteriores ficam congeladas e vendidas; antes da chegada `422 CHECK_IN_BEFORE_ARRIVAL` (altere a chegada antes).
  2. **Check-out atrasado** (data operacional depois da saída, sem extensão): aceito, a estadia fica como está (o check-out só registra a data).
  3. **Check-out no dia da chegada:** a estadia vira day use na data, a noite da chegada fica com o preço congelado (o check-out da E04 a lança, H14) e as unidades de todas as noites voltam ao inventário.
  4. **Antecipado:** só `GuestCheckedOut` (com `early` e `previousDeparture`), sem `ReservationChanged`; a disponibilidade sai pelo `AvailabilityChanged` do inventário. A alocação encerrada tem o fim na saída nova.
  5. **Desfazer:** a reserva volta a `CONFIRMED` com a UH ainda alocada e a lista de hóspedes mantida, sem os dados de check-in (a comprovação fica no retrato de antes do histórico); o que acontece com a UH, o early e a caução é do `frontdesk` (E02).
  6. **RN-24:** todo hóspede precisa da data de nascimento no check-in (sem ela a menoridade não se confere); a menoridade é a da data operacional do check-in; a comprovação só no menor; o responsável é sempre um hóspede adulto da mesma estadia (também na autorização escrita e na judicial, como o acompanhante); `attachmentId` é um id opaco (o `reservations` não depende de `files`: quem chama confere a finalidade, E02); `relationship`, `travelReason` e `transportMode` são códigos opacos (`[A-Za-z0-9_.-]{1,40}`), conferidos pelo `fnrh` (H21); hóspedes que o perfil mescla no mesmo sobrevivente são `400 CHECK_IN_INVALID`.
  7. **Troca de UH:** só para o mesmo tipo nesta etapa (outro tipo `422 ROOM_TYPE_MISMATCH`; é decisão da E05, como o risco da seção previa); a alocação antiga termina na data operacional e a nova começa nela; "não mover" não impede e passa à nova; `RECALCULATE` reprecifica as noites da data operacional em diante pela tarifa atual (sem conferir a venda, sem diária editada; a aprovação de desconto sai se não sobrar desconto), as anteriores ficam; na reserva do pick-up, `RECALCULATE` é `409 ROOM_BLOCK_RESERVATION_STAY_FIXED`.
  8. **Alteração do hospedado:** só a saída (e o segmento e os pedidos especiais); a ocupação também fica fixa (`409 IN_HOUSE_STAY_FIXED`; decisão da E05); a saída nova depois da data operacional; a UH do hospedado nunca é solta (extensão sem UH livre é `409 ROOM_ALREADY_ASSIGNED`); a reserva do pick-up continua com as datas fixas (`409 ROOM_BLOCK_RESERVATION_STAY_FIXED`).
  9. **Banco:** gatilhos adiados novos garantem que a hospedada tem UH e a encerrada não (defesa além do Ajuste D 2).
  10. **Sem endpoint e sem permissão nova:** as transições são só da `ReservationsApi` (o `frontdesk` cria os endpoints e as permissões nas E02 a E05).
- Decisões abertas da E01 (entrevista): **decididas pela opção recomendada em nome do usuário (delegado ao orquestrador), 2026-10-22**: (1a) check-in de chegada atrasada aceito com a data operacional dentro da estadia; (2a) check-out com a saída passada aceito, mantendo a saída; (3a) check-out no dia da chegada vira day use na data; (4a) desfazer: a UH volta a Vaga sem sujar, o early e a caução desfeitos à parte (a confirmar na E02, que implementa a parte da UH e da conta); (5a) "não mover" ignorado na troca do hospedado e levado para a alocação nova; (6a) data de nascimento exigida de todo hóspede no check-in (a FNRH a exige de todos). As decisões menores do implementador (itens 1 a 10 acima) também ficaram aceitas na mesma data, pelo mesmo caminho. Continuam para a E05: a troca de UH para outro tipo e a ocupação do hospedado.
- **Correções depois da 1ª verificação (REPROVADO, 2026-10-22):**
  1. (alto) `ReservationSearchApiTest` "every search filter and every list is served by an index…" era intermitente: com hospedadas reais e a data operacional avançada no banco compartilhado, o planejador escolhia `reservation_departure_idx` em vez de `reservation_in_house_idx` para a lista de hospedados. Agora o teste roda `ANALYZE` (pelo dono, `flyway.configuration.dataSource`) antes dos `EXPLAIN` e, nas listas por situação (hospedados e no-shows previstos, nas duas datas) e na busca só por situação, confere que `reservation r` é lida por **um** dos índices aceitáveis (o parcial da situação ou o da data; `Index Scan using … on reservation r` ou `Bitmap Index Scan on …`) e nunca por `Seq Scan`; os índices exatos continuam conferidos onde não há alternativa (período, número, OTA, saídas de amanhã). Provado rodando o `ReservationSearchApiTest` (e uma cópia temporária dele) **depois** do `StayTransitionsApiTest` (e de uma cópia temporária dele, que avança a data de novo) no mesmo JVM, verde, e no build completo; as cópias foram apagadas.
  2. (médio) `PUT` de um hospedado com a saída já passada dava `500` (`check` sobre a noite passada). Regra (coerente com a decisão 2a: o check-out atrasado mantém a saída sem registrar as noites a mais, então é a extensão que as registra): a extensão de um hospedado com a saída atrasada **precifica as noites novas desde a saída antiga, inclusive as já passadas até a data operacional**, pela tarifa delas, e toma o inventário só da data operacional em diante (como sempre); uma noite **já congelada** e passada nunca é recalculada: a alteração que a recalcularia (o day use de uma data passada virando pernoite) é `422 IN_HOUSE_PAST_NIGHT_FIXED` (código novo, com `dates`). Teste novo "an in-house stay past its departure is extended with the new nights priced, never the frozen past ones" (avança a data operacional duas vezes).
  3. (baixo) O responsável mesclado no próprio menor faria o `CHECK reservation_guest_minor_ck` falhar no listener de `GuestsMerged` (retry até o abandono). Agora o reapontamento tira a comprovação do menor nesse caso (os dois perfis eram a mesma pessoa; o histórico e a trilha anteriores a guardam), antes de repontar os outros responsáveis. Teste novo "a responsible merged into the minor itself drops the proof instead of failing the listener" (a mutação que desliga a limpeza faz o teste falhar).
  - Validação depois das correções: `./gradlew test --tests '…StayTransitions*' --tests '…ReservationSearchApiTest'` → 30 testes, 0 falhas; a busca depois das transições (duas cópias, ver item 1) → verde; mutação da limpeza da comprovação → 1 falha, revertida; `./gradlew build` → BUILD SUCCESSFUL em 7 min 59 s (1185 testes em 145 classes, 0 falhas); `.github/scripts/check-api-breaking.sh` → "Nenhuma mudança incompatível (nível ERR) em /api/v1" (o contrato não mudou nas correções: os códigos de erro não entram nele); snapshot depois das correções: `99f80e65abba620b8c64d144897e2a0e81582c3f`.
  4. (baixo) **Lacuna registrada:** a RN-24 é conferida só no check-in; uma mesclagem depois (item 3) ou a data de nascimento editada no `guests` com a reserva já hospedada não reconferem a comprovação. Teste novo de rollback e de ausência de dado de hóspede para `RoomMoved` e `GuestCheckedOut` ("a check-out or a room move in a rolled back transaction leaves nothing and publishes nothing"); o `GuestCheckedIn` já tinha.
- Validações executadas:
  - `./gradlew test --tests 'com.zeny.hotel.reservations.*' --tests 'com.zeny.hotel.MilestoneM4EndToEndTest'` → 175 testes, 0 falhas (com os 19 novos: `StayTransitionsDomainTest` 8, `StayTransitionsApiTest` 11);
  - `./gradlew updateOpenApi` → `api/openapi.yaml` com os campos novos da reserva e do histórico (`checkIn`, `checkOut`, `StayGuestResponse` com os dados de check-in, `MinorAuthorizationResponse` com `proofType` em `x-extensible-enum`, `StayTransitionResponse`, `HistoryStayGuestResponse`), os valores novos dos `x-extensible-enum` de soltura (`CHECKED_OUT`, `ROOM_MOVED`) e de histórico (`CHECK_IN`, `UNDO_CHECK_IN`, `CHECK_OUT`, `ROOM_MOVE`) e a descrição nova do `PUT /reservations/{id}`;
  - `./gradlew build` → BUILD SUCCESSFUL em 6 min 22 s (1182 testes em 145 classes, 0 falhas; `ModularityTest`, ArchUnit, detekt, ktlint, `checkOpenApi` e `koverVerify`; p95 da cotação 27 ms). A primeira rodada falhou só no detekt (linhas longas, `ThrowsCount`, `LongMethod`, `!!` desnecessário), corrigidos;
  - `.github/scripts/check-api-breaking.sh` → "Nenhuma mudança incompatível (nível ERR) em /api/v1";
  - **mutações** (aplicadas juntas, rodadas e revertidas, o arquivo restaurado idêntico): sem `MinorRules.require` → falha o teste do check-in (RN-24); sem `UndoCheckInRules.require` → falha o do desfazer em outra data; o check-out sem encerrar a alocação (`endOnCheckOut`) → o commit é recusado pelo gatilho adiado e falham os dois testes de check-out (`StayTransitionsApiTest`: 4 de 11 falham);
  - snapshot depois da implementação: `cef439699dea318667a6c06bfa28a47d197de67f` (34 arquivos, +3485/−120 contra o inicial).
- Parecer do verificador (orquestrador, 2026-10-22):
  - **1ª verificação: REPROVADO.** Build intermitente (`ReservationSearchApiTest` "every search filter and every list is served by an index…": o planejador escolheu `reservation_departure_idx` na lista `IN_HOUSE`) e `500` ao estender um hospedado com a saída já passada (`check(...)` em `requireNoPastNight`); baixos: responsável do menor mesclado no próprio menor, rollback testado só no `GuestCheckedIn`, lacuna da RN-24 depois do check-in. Corrigidos pelo implementador (seção "Correções depois da 1ª verificação").
  - **2ª verificação: APROVADO** (9/9 critérios; `./gradlew build` 1185 testes, 0 falhas; oasdiff sem `ERR`; par `StayTransitionsApiTest` + `ReservationSearchApiTest` verde em 5 execuções). Ressalvas, nenhuma grave:
    - **Médio (para o gate E12 ou o M6):** o teste de índices ficou mais fraco nas listas por situação e na busca só por situação (`shouldUseOneOf` aceita um percurso de índice sem `Index Cond`; com `enable_seqscan = off`, passaria um percurso completo com `Filter`): nenhum teste guarda mais `reservation_in_house_idx` nem `reservation_confirmed_arrival_idx`. Sugestão: exigir `Index Cond` sobre `reservation r` ou aceitar só os índices com condição útil. Mantido assim pelo orquestrador (decisão em nome do usuário): o produto não mudou e o teste continua barrando `Seq Scan`; o endurecimento sem reintroduzir a intermitência fica para o gate.
    - Baixos: sem teste de rollback do `GuestCheckInUndone` (mesmo mecanismo dos outros três); a redução da saída do hospedado pelo `PUT` passa por `relevantRefusals` (estadia mínima/CTD podem recusá-la com `422`, enquanto o check-out antecipado nunca é recusado) — decidir na E05 se as restrições de venda valem para quem já está em casa; na extensão de um hospedado com a saída passada, as noites novas anteriores à data operacional entram na reserva e em `report_room_nights` como vendidas, mas não no `sold` do inventário (decisão registrada: o passado não é refeito) — conferir no M6.
- Notas para a próxima etapa:
  - **E02 (check-in orquestrado):** chame `ReservationsApi.assignRoom` (se a UH ainda não estiver alocada) e `ReservationsApi.checkIn` na mesma transação do `frontdesk`, depois de conferir RN-05 (`RoomsApi.isReadyForCheckIn`), a regra mínima de documentos (H11) e o anexo da comprovação do menor pela `FilesApi` (a finalidade `guests.minor-authorization-photo`; o `reservations` não confere); depois `RoomsApi.markOccupied(guestCount, CHECK_IN)`. As recusas do `reservations` já vêm com códigos estáveis. O desfazer sensível chama `ReservationsApi.undoCheckIn`.
  - **E03 (walk-in):** `createBooking` + `assignRoom` + `checkIn` na mesma transação.
  - **E04 (check-out):** `ReservationsApi.checkOut` (inclusive antecipado) depois de conferir o saldo e liberar as cauções; `RoomsApi.markVacant(CHECK_OUT)`. O antecipado já devolve as noites; o day use do check-out no dia da chegada deixa a noite da chegada para o lançamento da E04 (H14).
  - **E05:** a troca de UH (`moveRoom`) é só do mesmo tipo; o upgrade para outro tipo e a ocupação do hospedado são decisões da E05 (a transição de tipo precisaria trocar o `roomTypeId` da reserva e mover o inventário das noites restantes numa só `apply`). O endpoint da troca muda o status das duas UHs.
  - **E08 (`fnrh`):** escute `GuestCheckedIn`, `GuestCheckInUndone`, `GuestCheckedOut` e `RoomMoved` (com `performedBy`) e leia os dados de check-in pela `ReservationsApi.reservation` (`guests[].travelReason`, `transportMode`, `minorAuthorization`).
  - **Migrações novas:** timestamp posterior a `V2026_10_22_1546`.
  - **Testes:** os que precisam de um hospedado no passado usam `ReservationsTestSupport.forceInHouse`; os de troca e de check-out antecipado avançam a data operacional do banco compartilhado (`HotelApi.advanceBusinessDate`).

---

## E02 — Check-in orquestrado — #concluida

**Objetivo:** criar o módulo `frontdesk` com `POST /api/v1/reservations/{id}/check-in`, que executa numa única transação o fluxo da ARCHITECTURE §6.1 e da RF-REC-01 — documentos dos hóspedes (H11), menores (RN-24, pela E01), UH alocada e pronta (RN-05), caução manual opcional (H12), conta da reserva aberta, transição da reserva e UH ocupada —, o desfazer check-in como ação sensível e a cotação de early check-in e late check-out (RN-11).

**Por que agora:** é o fluxo central da recepção e define o padrão de orquestração entre módulos que walk-in, check-out e troca de UH vão seguir.

**Dependências e paralelismo:** E01. Não paralelizável com E03 a E06 (mesmo módulo).

**Escopo:** pacote `frontdesk` (a criar): `FrontdeskModule.kt` com `allowedDependencies` = `shared`, `hotel`, `reservations`, `rooms`, `guests`, `billing`, `files` (acrescentar `hotel` e `files` à linha do `frontdesk` na ARCHITECTURE §3.1; `payments`, `fiscal` e `printing` só quando usados), `FrontdeskPermissions` (H20: `frontdesk.stays.checkin` e `frontdesk.stays.undocheckin`, **sensível**), `FrontdeskErrorCodes`, `application/`, `web/` e o teste dele no `ModularityTest`. Acréscimos sem quebrar contrato: a leitura dos documentos sem número na `GuestsApi` (H11) e o registro de caução na `BillingApi` (H12).

- **Corpo do check-in:** os hóspedes da estadia (ids do `guests`; os dados FNRH são confirmados ou editados antes pelos endpoints do `guests`) com motivo de viagem, meio de transporte e, para o menor, a comprovação (tipo, responsável, parentesco e o id do anexo de finalidade `guests.minor-authorization-photo` ou `guests.document-pdf` vinculado ao perfil do menor, conferido pela `FilesApi.find`; inválido `422`), a caução opcional (valor, validade, autorização, NSU, bandeira, 4 últimos dígitos) e o número de hóspedes.
- **Ordem na transação:** permissão → reserva → documentos dos hóspedes (H11, `422 GUEST_DATA_INCOMPLETE` com o hóspede e o que falta, sem número) → alerta da lista de restrição (devolvido, não bloqueia) → `RoomsApi.isReadyForCheckIn` (`409 ROOM_NOT_READY`, com a situação da UH) → conta da reserva aberta (`BillingApi.folio`) → caução pela `BillingApi` → `ReservationsApi.checkIn` → `RoomsApi.markOccupied(guestCount, CHECK_IN)`. Uma recusa em qualquer passo desfaz tudo. `@Idempotent` (a caução tem efeito financeiro). Nenhum pacote é lançado (H14).
- **Cotação de early/late** (RN-11, H13): `GET /api/v1/reservations/{id}/early-late-quote` devolve, pela `HotelApi.policy()` no fuso do hotel, se o early check-in e o late check-out são oferecidos, o horário limite e o valor; a cobrança é da E05 (early) e da E04 (late).
- **Desfazer check-in** (`POST /api/v1/reservations/{id}/undo-check-in`): `requireOrApproval(SensitiveAction("frontdesk.stay.undo-check-in", frontdesk.stays.undocheckin, "reservations.reservation:<id>"), X-Approval-Token)`; `ReservationsApi.undoCheckIn` (mesma data operacional); UH volta a Vaga (a governança e a origem do status são decisão da E01/E02); a caução e o early já registrados ficam (estorno e liberação pelo `billing`), registrado no handoff.
- Auditoria com `@Audited` em cada caso de uso, retratos sem dado pessoal; `CLAUDE.md` (seção nova do `frontdesk`) e `README.md`.

**Fora de escopo:** envio à FNRH (E08, por evento); impressão da ficha (E11); pré-autorização integrada (M7); walk-in (E03); cobrança de early (E05).

**Passos de implementação:**

1. Escrever os testes de integração do endpoint: sucesso (reserva `IN_HOUSE`, UH Ocupada com o número de hóspedes, `GuestCheckedIn`, caução registrada sem mudar o saldo), UH Suja, em OOO e com inspeção pendente, UH não alocada, menor sem comprovação e com anexo de outra finalidade, hóspede sem documento, falha forçada depois da caução que desfaz tudo, idempotência, alerta de restrição, desfazer com e sem aprovação e em outra data, nenhum dado sensível na resposta.
2. Criar o módulo, os acréscimos à `GuestsApi` e à `BillingApi`, os casos de uso e o controller.
3. Regenerar o contrato, atualizar a ARCHITECTURE §3.1, o `ModularityTest`, o `CLAUDE.md` e o `README.md`.

**Revisão humana:** sim (executada no formato antigo, estratégia `padrão`)

- Investigação (`Explore`): "como `GuestsApi`/`GuestsStore` guardam o tipo, o país e o sufixo dos documentos e como `billing` registra a caução (`CardPreAuthorizationService`), para expor ambos sem permissão e sem decifrar?"; "o que RF-REC-01, RF-REC-08, RN-05, RN-11, RN-24 e ARCHITECTURE §6.1 e §6.3 exigem?".
- Partes paralelas: nenhuma.
- Foco do verificador: transação parcial (caução ou ocupação persistida quando o check-in falha); menor aceito sem comprovação; `frontdesk` chamando módulo fora da ARCHITECTURE §3.1 ou o `fnrh`; número de documento ou dado de RN-19 na resposta, na trilha ou no log; a leitura nova da `GuestsApi` decifrando algo; desfazer sem step-up.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E02 — "Check-in orquestrado" do roadmap docs/roadmaps/roadmap-m5-recepcao-fnrh.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Contexto confirmado", o "Protocolo de execução", as restrições e as hipóteses H3, H4, H10 a H14 e H20 —, a seção E02 inteira e o "Evidências e handoff" de E01. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar o módulo frontdesk com POST /api/v1/reservations/{id}/check-in (@Idempotent) executando numa única transação o fluxo de docs/ARCHITECTURE.md §6.1 — documentos dos hóspedes por uma leitura nova da GuestsApi sem número, menores pela E01, UH pronta (RoomsApi.isReadyForCheckIn), conta da reserva aberta, caução opcional por um método novo da BillingApi, ReservationsApi.checkIn e RoomsApi.markOccupied —, POST /api/v1/reservations/{id}/undo-check-in como ação sensível com step-up e GET /api/v1/reservations/{id}/early-late-quote pela política do hotel. frontdesk não chama fnrh, não usa GuestsApi.fnrhData e não lança pacotes nem early aqui.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E02 para #em-andamento no índice e no título;
- investigação: um subagente Explore responde às perguntas da seção E02;
- escreva os testes de integração primeiro (incluindo o rollback total quando um passo falha), implemente, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh e atualize ARCHITECTURE §3.1, ModularityTest, CLAUDE.md e README.md;
- verificação independente com o Prompt do verificador, com o foco listado na seção E02.

Critérios de aceite (detalhes na seção E02):
- UH não pronta → 409 ROOM_NOT_READY; sem UH → 409 ROOM_NOT_ASSIGNED; menor sem comprovação → 422; hóspede sem documento → 422 GUEST_DATA_INCOMPLETE;
- falha em qualquer passo não deixa caução, transição nem ocupação; sucesso publica GuestCheckedIn e ocupa a UH;
- undo-check-in exige step-up e só vale na mesma data operacional; nenhum dado sensível na resposta;
- ./gradlew build verde e oasdiff sem ERR.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E02 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Check-in com UH Suja, em OOO ou com inspeção pendente → `409 ROOM_NOT_READY`; sem UH alocada → `409 ROOM_NOT_ASSIGNED`.
- [ ] Hóspede menor sem comprovação → `422 MINOR_AUTHORIZATION_REQUIRED`; com anexo de outra finalidade ou de outro perfil → `422`; com comprovação válida → aceito e registrado com tipo, responsável e parentesco.
- [ ] Hóspede sem documento aceito → `422 GUEST_DATA_INCOMPLETE`, sem número de documento no problema; a leitura nova da `GuestsApi` não decifra nenhum documento (teste com número ilegível, como o `GuestDocumentKeysTest`).
- [ ] Teste força falha depois da caução e confirma que nada foi persistido (caução, situação da reserva, ocupação, trilha).
- [ ] Sucesso devolve `200`, publica `GuestCheckedIn`, a UH fica Ocupada com o número de hóspedes e a caução registrada não muda o saldo; a repetição com a mesma `Idempotency-Key` devolve a guardada.
- [ ] `undo-check-in` sem permissão → `403 APPROVAL_REQUIRED`; com token válido → reserva `CONFIRMED` e UH Vaga, trilha com solicitante e aprovador; em outra data operacional → `422 UNDO_CHECK_IN_EXPIRED`.
- [ ] Cotação de early/late segue a política vigente do `hotel` (oferecido ou não, horário, valor) no fuso do hotel.
- [ ] Hóspede em lista de restrição gera alerta na resposta sem bloquear; nenhum dado de RN-19 nem número de documento na resposta, na trilha ou no log.
- [ ] `ModularityTest` com o teste do `frontdesk` e a ARCHITECTURE §3.1 atualizada; `./gradlew build` verde e oasdiff sem `ERR`.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Transação longa com vários módulos: manter as chamadas síncronas mínimas (as travas da reserva e da conta), deixar efeitos secundários para eventos e registrar a ordem das travas no handoff.

**Evidências e handoff:**

- Snapshot inicial: `93a3dd57a6c51438f61f3b5f8a62b0f662935218` (tirado pelo orquestrador). Preparação: `git status` limpo; E01 `#concluida` (commit `bdb9324`) e o código bate com o handoff dela (`ReservationsApi.assignRoom`, `checkIn`, `undoCheckIn`, `checkOut`, `moveRoom`, os quatro eventos com `performedBy`, `MinorAuthorization` com `attachmentId` opaco). A investigação (perguntas da seção) foi feita pela própria sessão, sem subagente, como o adendo do orquestrador manda: `GuestDocument` já guarda o sufixo em claro (`numberSuffix`, `masked` sem decifrar); `CardPreAuthorizationService.register` só exigia a permissão antes de travar a conta e gravar; RF-REC-01/08, RN-05, RN-11, RN-24 e ARCHITECTURE §6.1/§6.3 como resumidos no cabeçalho.
- Arquivos alterados:
  - **Novos (produção):** `src/main/kotlin/com/zeny/hotel/frontdesk/{FrontdeskModule,FrontdeskPermissions,FrontdeskErrorCodes,EarlyLateOffer}.kt`, `frontdesk/domain/CheckInRules.kt`, `frontdesk/application/CheckInService.kt` (`FrontdeskStayService`, `FrontdeskAudit`, `FrontdeskPermissionsConfiguration`), `frontdesk/web/{StaysController,StayDtos}.kt`. Nenhuma migração (o `frontdesk` não tem estado próprio).
  - **Alterados (produção):** `guests/GuestsApi.kt` e `guests/application/GuestsApiService.kt` (`identification(id)`, `GuestIdentification`, `MaskedGuestDocument`); `billing/BillingApi.kt`, `billing/CardPreAuthorizations.kt` (`NewCardPreAuthorization`), `billing/application/BillingApiService.kt` e `billing/application/CardPreAuthorizationService.kt` (`registerCardPreAuthorization`, `registerAuthorized`); `api/openapi.yaml`; `docs/ARCHITECTURE.md` (§3.1: `frontdesk` → `hotel`, `files`); `CLAUDE.md` (estado atual e a seção "Recepção: check-in orquestrado"); `README.md` (seção "API").
  - **Testes:** novos `src/test/kotlin/com/zeny/hotel/frontdesk/FrontdeskDomainTest.kt` (5) e `FrontdeskCheckInApiTest.kt` (11), `testsupport/RoomStatusFailureProbe.kt`; alterados `ModularityTest.kt` (o teste do `frontdesk`), `iam/AuthorizationTest.kt` (o modelo Gerente com as duas permissões novas) e `reservations/InventoryTestSupport.kt` (o bean do probe no `InventoryTestConfiguration`, para o teste do `frontdesk` usar o mesmo contexto das reservas).
- Decisões (todas **decididas pela opção recomendada em nome do usuário (delegado ao orquestrador), 2026-10-22**; pontos de revisão humana da etapa):
  1. **Regra mínima H11:** cada hóspede com nacionalidade, data de nascimento e um documento aceito (`CPF` do brasileiro; `PASSPORT`, `RNM` ou `FOREIGN_ID` do estrangeiro; sem nacionalidade, qualquer um dos quatro); todos os incompletos num só `422 GUEST_DATA_INCOMPLETE` (`incompleteGuests[{guestId, missing, acceptedDocumentTypes}]`). Alternativa: conferir só o documento e deixar a data de nascimento ao `GUEST_BIRTH_DATE_REQUIRED` do `reservations` (um problema por vez). A nacionalidade entrou como exigência (a FNRH a pede; sem ela não se sabe que documento vale).
  2. **Ordem dos passos:** `ReservationsApi.checkIn` (que trava a reserva) **antes** da caução, e não depois como a lista da seção sugeria. A atomicidade é a mesma (uma transação), e a ordem das travas fica reserva → conta → UH, a mesma do cancelamento (reserva → conta): com a caução primeiro, um check-in e um cancelamento simultâneos da mesma reserva podiam travar em ordem oposta (deadlock, `40P01` → `500`). Consequência: as recusas da RN-24 vêm antes das recusas de formato da caução. As conferências baratas (situação, chegada, conta aberta, hóspedes, anexo, UH pronta) vêm antes de qualquer escrita.
  3. **Prontidão conferida de novo depois de ocupar:** com a linha da UH já travada pelo `markOccupied`, a governança e o OOO são conferidos de novo (`RoomReadiness.stillReady`); uma mudança confirmada entre a conferência e a ocupação recusa com `409 ROOM_NOT_READY`.
  4. **`roomId` opcional no corpo:** aloca ou realoca antes pela `ReservationsApi.assignRoom`, exigindo também `reservations.assignments.manage` (sem ela `403`): o check-in não amplia o que a permissão de check-in dá. O walk-in (E03) reusa.
  5. **Anexo do menor:** conferido só quando informado (a exigência por tipo de comprovação é do `reservations`); finalidades `guests.minor-authorization-photo` e `guests.document-pdf`; vinculado ao perfil do menor pedido ou ao corrente (o sobrevivente, num mesclado). Um anexo que ficou num perfil intermediário de uma cadeia A → B → C pedido como A é recusado (limitação aceita).
  6. **Pré-conferências no `frontdesk`** (situação e chegada no check-in; situação e data no desfazer) com os mesmos códigos do `reservations` (`StayTransitionNotAllowedException` reproduz `reservationStatus` e `action`): para o erro certo sair antes dos outros passos e, no desfazer, antes de pedir a aprovação de superior; o `reservations` confere de novo com a reserva travada.
  7. **Conta da reserva encerrada:** `409 FOLIO_CLOSED` (a reabertura é sensível e do `billing`); o check-in não reabre.
  8. **Desfazer:** exige também `frontdesk.stays.checkin`; a UH volta a Vaga **sem sujar** pela `markVacant(CHECK_IN)` (decisão 4 da E01); a caução e o early ficam, sem recusar o desfazer (liberação e estorno pelo `billing`); sem `@Idempotent` (repetir dá `409`, como a reabertura de conta).
  9. **Cotação:** exige `reservations.reservations.read` (quem lê a reserva lê a política, que é pública), vale em qualquer situação, com a janela em instantes UTC; o horário sai como `HH:mm:ss` (o mesmo serializador do `hotel`).
  10. **Número de hóspedes:** opcional (nulo = o tamanho da lista), de 1 a 40, nunca abaixo da lista (`400 CHECK_IN_REQUEST_INVALID`, campo `guestCount`).
  11. **Resposta própria** (`CheckInResponse` com um resumo da reserva, o status da UH, a caução e os alertas), sem reusar o DTO do `reservations` (é interno do `web` dele); os alertas trazem o motivo, como a resposta da reserva já faz.
  12. **Campos da caução recusados pelo `billing`** voltam com o prefixo `preAuthorization.` (o `frontdesk` reembala o `InvalidInputException`).
  13. `EarlyLateOffer` ficou na raiz do `frontdesk` (o `web` não pode ler o `domain`).
  14. **Falha forçada nos testes:** um `@EventListener` síncrono de `RoomStatusChanged` (`RoomStatusFailureProbe`), registrado no `InventoryTestConfiguration` para reusar o contexto das reservas, inerte para as UHs não armadas.
- Validações executadas:
  - `./gradlew test --tests 'com.zeny.hotel.frontdesk.*' --tests 'com.zeny.hotel.ModularityTest'` → 33 testes, 0 falhas depois de um ajuste (o horário da cotação sai `HH:mm:ss`; o teste passou a comparar `LocalTime`);
  - `./gradlew updateOpenApi` → `api/openapi.yaml` com +662 linhas: `POST /reservations/{id}/check-in` (com `IdempotencyKey`), `POST …/undo-check-in` (com `ApprovalToken`), `GET …/early-late-quote` e os schemas novos (`CheckInRequest`, `CheckInResponse`, `StayReservationResponse`, `EarlyLateQuoteResponse`…; `status`, `proofType` e o status da caução em `x-extensible-enum`); nenhuma linha removida;
  - `./gradlew lint` → verde depois de corrigir linhas longas, o nome do arquivo (`EarlyLateOffer.kt`) e `ThrowsCount`/`TooManyFunctions` do caso de uso;
  - **mutações** (aplicadas juntas, rodadas e revertidas, os arquivos restaurados idênticos): sem a conferência `GuestDataRules.require` → falha "a guest without the minimal data…"; `GuestsApi.identification` decifrando o número → falha "the document check never decrypts…" (`500` com o número ilegível); o desfazer sem `requireOrApproval` → falha "undoing the check-in asks for approval…" (3 de 11 falham). A remoção só da conferência `isReadyForCheckIn` não falhava (a reconferência depois de ocupar também recusa): o teste passou a exigir `occupancy` `VACANT` no problema, o que distingue a recusa na conferência;
  - 1º `./gradlew build` → falhou só no `AuthorizationTest` (o modelo Gerente ganhou as duas permissões do `frontdesk`; a lista esperada foi atualizada);
  - 2º `./gradlew build` → BUILD SUCCESSFUL em 7 min 21 s (1202 testes em 147 classes, 0 falhas; `ModularityTest`, ArchUnit, detekt, ktlint, `checkOpenApi` e `koverVerify`);
  - `.github/scripts/check-api-breaking.sh` → "Nenhuma mudança incompatível (nível ERR) em /api/v1" (contra `bdb9324` e contra `ffae917`, o gate do M4);
  - snapshot depois da implementação: `f4be74a0407a2075f4136f6a3e8966365fb64481`.
- **Ajustes da revisão humana** (feita pelo orquestrador em nome do usuário ausente, depois do parecer APROVADO do verificador; cada decisão **decidida pela opção recomendada em nome do usuário (delegado ao orquestrador), 2026-10-22**):
  1. **B1 (corrida de dois check-ins na mesma UH):** a `RoomsApi` ganhou `markOccupiedIfReady(id, guestCount, source)` → `CheckInOccupation(ready, status)`, que confere a RN-05 **com a linha da UH travada** pelo `RoomStatusChanger` e só então ocupa (não pronta: nada muda, sem histórico nem evento; quem chama recusa). O `frontdesk` a usa no lugar de `markOccupied` + `RoomReadiness.stillReady` (removido) e recusa com `409 ROOM_NOT_READY` (o perdedor vê `occupancy` `OCCUPIED`, e a transição dele é desfeita junto). Escolhida em vez de travar a UH antes da conferência (exigiria uma porta de trava nova na `RoomsApi` e prenderia a UH durante toda a conferência): o acréscimo é só um método e um tipo, sem mudar o contrato HTTP. **Bug latente achado no caminho:** o `RoomsStore.roomForUpdate` usava uma consulta `@Lock(PESSIMISTIC_WRITE)`; numa UH já lida na mesma transação (o check-in lê a UH antes de ocupá-la) e mudada por outra transação no meio, o Hibernate 7 recusava como conflito (`412`). Passou a `find` + `refresh(PESSIMISTIC_WRITE)`, como o `folioForUpdate` do `billing` (o método `RoomJpaRepository.findForUpdate`, agora sem uso, saiu). Teste novo "two simultaneous check-ins in the same room never both pass…" (cinco rodadas; um day use e um pernoite na mesma UH; o `CheckInRendezvousProbe`, `@EventListener` síncrono de `GuestCheckedIn` com uma `CyclicBarrier` por UH armada, faz os dois passarem a conferência sem trava antes de ocupar): um `200`, o outro `409` sem transição, histórico, caução, trilha nem publicação, e a UH com o número de hóspedes do vencedor. **Mutação:** com o `markOccupied` simples, o teste falha (`[200, 200]`); antes da correção do `roomForUpdate`, o perdedor recebia `412`. Teste novo "the atomic occupation of rooms refuses a room that is not ready, changing nothing" (a `RoomsApi` direto: suja, nada muda nem a versão; pronta, ocupa; já ocupada, recusa sem trocar o número de hóspedes).
  2. **B2 (menor brasileiro):** o brasileiro **menor** na data operacional entra com `CPF` **ou** `RG`; o adulto continua só com `CPF` (`GuestDataRules.acceptedDocuments(nacionalidade, menor)`, menoridade pelo `Period` como a `GuestsApi.isMinorOn`; no dia em que faz 18 anos, já é adulto). Testes: `FrontdeskDomainTest` "a Brazilian minor enters with a CPF or an RG…" e `FrontdeskCheckInApiTest` "a Brazilian minor with only an RG checks in, an adult with only an RG does not (B2)". **Nota para a E07/E08:** conferir na documentação da FNRH v2.4 se o Serpro aceita o menor brasileiro sem CPF (só com RG); se não aceitar, a submissão desse hóspede fica pendente na validação local do `fnrh` (H21), sem bloquear o check-in.
  3. **B3:** a ARCHITECTURE §6.1 mostra a ordem real (conta aberta, identificação sem número, UH pronta sem trava, transição que trava a reserva, caução que trava a conta, `markOccupiedIfReady` que trava a UH) com o texto da ordem das travas reserva → conta → UH e o porquê (o cancelamento trava reserva → conta; a reconferência da RN-05 com a UH travada fecha a corrida).
  4. **B7:** `requireOpenFolio` não usa mais `checkNotNull`: a conta ausente vira `404 FOLIO_NOT_FOUND` (código do `billing` reexportado em `FrontdeskErrorCodes`), documentado como impossível na prática (a conta nasce na criação da reserva e o trigger `billing.refuse_delete` impede apagá-la).
  5. **B6:** teste novo "a closed reservation folio refuses the check-in with nothing written (B6)" (`409 FOLIO_CLOSED`, a reserva confirmada e a UH vaga); o teste de sucesso cadastra o hóspede com raça/cor e uma deficiência de código único e confere que nem a deficiência nem `raceColor` aparecem no log capturado (além do CPF), na resposta e na trilha.
  6. **B4 (nota para a E04, não implementada):** depois do desfazer, a caução registrada no check-in continua `ACTIVE` na conta da reserva, e um novo check-in pode registrar outra sem aviso. O check-out (E04) deve listar e liberar as cauções ativas das contas da reserva (H12); avaliar também um aviso no check-in quando a conta já tem caução ativa.
  - Validação depois dos ajustes: `./gradlew test --tests 'com.zeny.hotel.frontdesk.*' --tests 'com.zeny.hotel.rooms.*'` verde; `./gradlew lint` verde; `./gradlew updateOpenApi` (só descrições mudaram: o contrato continua só com acréscimos, +664 linhas contra `bdb9324`); `./gradlew build` → BUILD SUCCESSFUL em 5 min 57 s (1206 testes em 147 classes, 0 falhas); `.github/scripts/check-api-breaking.sh` → "Nenhuma mudança incompatível (nível ERR) em /api/v1" contra `bdb9324` e contra `ffae917`; snapshot depois dos ajustes: `526db84c97e14bb5fcd7faef75573fb2be406c28`.
- Parecer do verificador (orquestrador, 2026-10-22):
  - **1ª verificação: APROVADO** (9/9 critérios; build 1202 testes, 0 falhas; oasdiff sem `ERR`), com riscos para a revisão humana: B1 (dois check-ins simultâneos na mesma UH, day use e pernoite, podiam ficar ambos `IN_HOUSE`: RN-04 violada na prática), B2 (menor brasileiro sem CPF bloqueava a família), B3 (ARCHITECTURE §6.1 com a ordem antiga), B4 (caução ativa depois do desfazer), B5 (motivo da restrição, texto livre, na resposta: aceito, segue o padrão do M4), B6 (sem teste de `FOLIO_CLOSED` nem de RN-19 no log) e B7 (`500` com a conta ausente).
  - **Revisão humana** (feita pelo orquestrador em nome do usuário ausente, delegado): pediu B1, B2, B3, B6 e B7 e a nota B4 para a E04; aplicados (seção "Ajustes da revisão humana"). As 14 decisões do implementador foram aceitas.
  - **2ª verificação, depois dos ajustes: APROVADO** (9/9; build 1206 testes, 0 falhas; oasdiff sem `ERR` contra o HEAD e contra `bdb9324`). Mutações conferidas pelo verificador: `markOccupied` simples → a corrida dá `[200, 200]`; `roomForUpdate` com a consulta travada antiga → `[200, 412]` (o bug latente do Hibernate 7); o menor só com CPF → falham os testes de domínio e de API do B2. Testes de concorrência do `rooms` (status, OOO, cadastro) verdes com o `find` + `refresh(PESSIMISTIC_WRITE)`. Menores, não bloqueiam: `markOccupiedIfReady` devolve `ready=false` antes de validar o `guestCount` (inofensivo); B7 sem teste (estado impossível).
- Notas para a próxima etapa:
  - **E03 (walk-in):** `ReservationsApi.createBooking` + o mesmo `FrontdeskStayService` (extraia o corpo do check-in para reusar com a reserva criada; o `roomId` do corpo já aloca); a regra mínima H11 e o anexo do menor valem iguais.
  - **E04 (check-out):** a liberação da caução pela `BillingApi` ainda não existe (H12: criar `releaseCardPreAuthorization` sem permissão, como o registro desta etapa); depois de um desfazer, a caução antiga continua ativa e um novo check-in registra outra (B4: o check-out lista e libera todas as ativas); a cotação de late está em `GET …/early-late-quote` e a cobrança confere o valor da política (`409 EARLY_LATE_FEE_CHANGED`); aceite também `frontdesk.stays.checkout` na cotação se fizer sentido.
  - **E05:** a cobrança do early usa a mesma cotação (`EarlyLateQuotes.early`); a troca de UH do hospedado deve ocupar a UH nova com `RoomsApi.markOccupiedIfReady` (a mesma corrida do B1).
  - **E07/E08:** conferir na FNRH v2.4 se o menor brasileiro só com RG é aceito (B2).
  - **E08 (`fnrh`):** o check-in nunca chama o `fnrh`; escute `GuestCheckedIn`/`GuestCheckInUndone`. A regra mínima do check-in não substitui a validação local da FNRH (H21).
  - **E11 (ficha):** `GuestsApi.identification` já dá os documentos mascarados para a ficha.
  - **Ordem das travas** do check-in: reserva → conta → UH (registre a mesma no walk-in e no check-out).

---

## E03 — Walk-in — #concluida

**Objetivo:** criar a reserva, alocar a UH e fazer o check-in no mesmo fluxo (RF-REC-02), reutilizando `ReservationsApi.createBooking` e `assignRoom` e o caso de uso de check-in da E02, numa só transação.

**Por que agora:** completa o caminho de chegada sem reserva prévia, comum em hotel pequeno.

**Dependências e paralelismo:** E02. Pode correr em paralelo com E06 (worktree separado); conflito em `api/openapi.yaml` e no controller do `frontdesk`.

**Escopo:** `frontdesk` — `POST /api/v1/walk-ins` (`@Idempotent`) com o titular, os hóspedes (já cadastrados no `guests`, H24), tipo de UH, plano, saída (a chegada é a data operacional), ocupação, segmento, a UH escolhida e os dados do check-in (os mesmos da E02, inclusive a caução); `ReservationsApi.createBooking` com a origem `FRONT_DESK` (canal `DIRECT`) e o preço calculado (sem diária editada, H24), `assignRoom` e o check-in da E02, tudo na mesma transação; exige `frontdesk.stays.checkin` e `reservations.reservations.manage`. Recusas da venda e do inventário com os códigos do `reservations`/`rates` (`409 ROOM_TYPE_UNAVAILABLE`, `422` com `refusals`).

**Fora de escopo:** criação de perfil de hóspede no walk-in e desconto no walk-in (H24); pagamento antecipado integrado (M7).

**Passos de implementação:**

1. Testes de integração: walk-in completo; sem disponibilidade → nada criado (nenhuma reserva, conta, caução, inventário); UH não pronta → nada criado; menor sem comprovação → nada criado; repetição com a mesma `Idempotency-Key` → mesma resposta, uma única reserva; sem a permissão de reservas → `403`.
2. Implementar o caso de uso e o endpoint; atualizar `CLAUDE.md` e `README.md`.

**Revisão humana:** não (executada no formato antigo, estratégia `padrão`)

- Investigação: nenhuma; o padrão está no handoff da E02 e na seção "Reservas e reservas agrupadoras" do `CLAUDE.md`.
- Partes paralelas: nenhuma.
- Foco do verificador: reserva, conta ou unidade de inventário que sobram quando o check-in falha; duplicidade sem `Idempotency-Key`; walk-in que contorna a venda ou o desconto.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E03 — "Walk-in" do roadmap docs/roadmaps/roadmap-m5-recepcao-fnrh.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a hipótese H24 —, a seção E03 inteira e o "Evidências e handoff" de E01 e E02. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E02 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar POST /api/v1/walk-ins (@Idempotent) em frontdesk, que cria a reserva por ReservationsApi.createBooking (origem FRONT_DESK, preço calculado, hóspedes já cadastrados), aloca a UH por ReservationsApi.assignRoom e faz o check-in da E02 na mesma transação, exigindo frontdesk.stays.checkin e reservations.reservations.manage. Se qualquer parte falhar, nada é persistido.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E03 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de integração primeiro, implemente, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh e atualize CLAUDE.md e README.md;
- verificação independente com o Prompt do verificador, com o foco listado na seção E03.

Critérios de aceite (detalhes na seção E03):
- walk-in completo deixa a reserva IN_HOUSE, a UH ocupada e publica ReservationCreated e GuestCheckedIn;
- sem disponibilidade, com UH não pronta ou com menor sem comprovação, nenhuma reserva, conta, caução ou unidade fica gravada;
- repetição com a mesma Idempotency-Key devolve a mesma resposta sem nova reserva;
- ./gradlew build verde e oasdiff sem ERR.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E03 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] `POST /api/v1/walk-ins` válido → reserva `IN_HOUSE` com a origem `FRONT_DESK`, conta aberta, UH Ocupada, `ReservationCreated` e `GuestCheckedIn` publicados.
- [ ] Sem disponibilidade → `409 ROOM_TYPE_UNAVAILABLE` e nenhuma reserva, conta ou unidade persistida (contadores conferidos).
- [ ] UH não pronta → `409 ROOM_NOT_READY` e nada persistido; menor sem comprovação → `422` e nada persistido.
- [ ] Sem `Idempotency-Key` → `400 IDEMPOTENCY_KEY_REQUIRED`; repetição com a mesma chave → mesma resposta, uma única reserva.
- [ ] Sem `reservations.reservations.manage` → `403`.
- [ ] `./gradlew build` verde e oasdiff sem `ERR`.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- `createBooking` abre a própria transação quando chamado fora de uma: o caso de uso do walk-in abre a transação antes, e o teste de rollback detecta a quebra da atomicidade.

**Evidências e handoff:**

- Snapshot inicial: `f6f3511716c2595523d5fa6af9b39b7d64385870` (tirado pelo orquestrador). Preparação: `git status` limpo; E02 `#concluida` (commit `87cbb62`) e o código bate com o handoff dela (`FrontdeskStayService` com o check-in, o desfazer e a cotação; `RoomsApi.markOccupiedIfReady`; `GuestsApi.identification`; `BillingApi.registerCardPreAuthorization`; `ReservationsApi.createBooking`, `assignRoom`, `confirmReservation` e `checkIn` como descritos). Sem investigação delegada (a estratégia da etapa não a pede; o adendo do orquestrador também não).
- Arquivos alterados:
  - **Novos (produção):** `src/main/kotlin/com/zeny/hotel/frontdesk/application/WalkInService.kt` (`WalkInCommand`, `WalkInOutcome`, `FrontdeskWalkInService`, `WalkInInvalidInputException`), `frontdesk/web/WalkInsController.kt` (`POST /api/v1/walk-ins`, `operationId` `createWalkIn`) e `frontdesk/web/WalkInDtos.kt` (`WalkInRequest`, `WalkInResponse`). Nenhuma migração (o `frontdesk` continua sem estado próprio).
  - **Alterados (produção):** `frontdesk/application/CheckInService.kt` (os passos do check-in extraídos de `FrontdeskStayService` para o componente `CheckInSteps` — `reservation`, `prepareGuests`, `complete`, `requireOpenFolio`, `roomNumber` —, reusados pelo check-in da E02 e pelo walk-in, com a guarda "só na transação de escrita de quem chama"; `FrontdeskAudit.WALK_IN`), `frontdesk/FrontdeskModule.kt` (`rates` em `allowedDependencies`), `frontdesk/FrontdeskErrorCodes.kt` (`UNKNOWN_ROOM` reexportado), `frontdesk/web/StaysController.kt` (descrição da tag), `api/openapi.yaml` (+211 linhas, nenhuma removida), `docs/ARCHITECTURE.md` (§3.1: `frontdesk` → `rates`, só os tipos da `ReservationsApi`), `CLAUDE.md` (estado atual, a aresta nova na seção do check-in e a seção nova "Recepção: walk-in") e `README.md` (seção "API").
  - **Testes:** novo `src/test/kotlin/com/zeny/hotel/frontdesk/FrontdeskWalkInApiTest.kt` (9 testes, no contexto dos testes das reservas e do check-in: `IamTestConfiguration` + `InventoryTestConfiguration`, `@EnableScenarios`); alterado `ModularityTest.kt` (o teste do `frontdesk` com `rates` e a conferência de que dele só se usam `RatePlanId` e `GuaranteeType`).
- Decisões (todas **decididas pela opção recomendada em nome do usuário (delegado ao orquestrador), 2026-10-22**, depois de pesar prós e contras):
  1. **Aresta `frontdesk` → `rates`, só por tipos.** `NewReservation.ratePlanId` é um `RatePlanId` do `rates` (e `GuaranteeTerms.type` um `GuaranteeType`): construir a reserva sem tocar o `rates` é impossível sem contornar a verificação de módulos. Escolhida: a aresta declarada em `allowedDependencies`, na ARCHITECTURE §3.1 e no `ModularityTest`, que confere que só esses dois tipos são usados (nenhuma chamada à `RatesApi`). Alternativas descartadas: uma fábrica na `ReservationsApi` que recebe o id do plano como `UUID` (esconderia a dependência do Modulith: o `frontdesk` continuaria usando um conceito do `rates`); um método de walk-in próprio no `reservations` (contra o escopo da etapa, que pede a `createBooking`). Ressalva: a §3.1 diz que depender da mesma camada (fora da Plataforma) exige inversão, mas a tabela já tem arestas na mesma camada (`reservations` → `rates`, `frontdesk` → `reservations`) e o grafo continua acíclico; a H24 citava "o `frontdesk` não depende de `rates`" pensando na `RatesApi` (preço e desconto), que continua sem uso.
  2. **Pré-reserva confirmada no balcão.** Um plano com garantia faz a reserva nascer `PROVISIONAL`, e o check-in só aceita a confirmada. Escolhida: confirmar na mesma transação pela `ReservationsApi.confirmReservation` com a garantia que a política copiada pede — cartão (o hóspede está no balcão; a caução opcional é o registro do cartão) ou depósito, que é recusado com `422 DEPOSIT_INSUFFICIENT` (a conta acabou de nascer, então um plano com depósito antecipado não serve ao walk-in). Alternativas: recusar todo plano com garantia (impediria o walk-in nos planos comuns de balcão com garantia de cartão); um campo `guarantee` no pedido (redundante: a política já diz o meio). Publica `ReservationConfirmed` e grava o histórico `CONFIRM`.
  3. **Três permissões.** Além das duas da H24, `reservations.assignments.manage`, como o check-in da E02 exige quando a UH vem no corpo (decisão 4 da E02: "o walk-in reusa"): o walk-in não amplia o que a alocação exige. A Recepcionista e o Gerente têm as três no modelo. Alternativa: só as duas (o walk-in alocaria sem a permissão de alocação).
  4. **Titular opcional** (nulo = o hóspede principal, senão o primeiro da lista) e **tipo de UH opcional** (nulo = o da UH escolhida; diferente dela, `422 ROOM_TYPE_MISMATCH` da alocação, com tudo desfeito). A UH é obrigatória (o walk-in sempre ocupa uma). Os alertas de restrição incluem o titular quando ele não é hóspede.
  5. **Conferências sem escrita antes da criação:** a UH existente (`400 UNKNOWN_ROOM`, campo `roomId`), o número de hóspedes e a identificação dos hóspedes e os anexos dos menores (H11) vêm antes da `createBooking`, para as recusas baratas não tomarem as travas do inventário. A RN-05 (UH pronta) e a RN-24 (comprovação do menor) são conferidas depois da criação, nos passos do check-in (a reserva precisa existir), e desfazem tudo.
  6. **Campos do pedido nas recusas:** os `400` do `reservations` com `reservations[0].x` voltam como `x` (`WalkInInvalidInputException`, que mantém as propriedades do problema); os `422` da venda mantêm `refusals` e o `reservationIndex` (sempre 0).
  7. **Ordem das travas:** inventário (as linhas do tipo, em ordem de data, pela `createBooking`) → trava consultiva da UH (`assignRoom`) → reserva → conta → linha da UH; a reserva e a conta nascem na transação (ninguém mais as trava), e inventário → UH é a ordem da alteração de reserva (E08 do M4). Dois walk-ins no mesmo tipo e datas se enfileiram no inventário; dois na mesma UH, na trava consultiva (o segundo recebe `409 ROOM_ALREADY_ASSIGNED`).
  8. **Resposta própria** (`WalkInResponse`: `bookingId`, `bookingCode`, `holderGuestId`, `total` e o mesmo resumo do check-in) com `201`, `Location` da reserva e o `ETag` dela; trilha `frontdesk.stay.walk-in` com a entidade `frontdesk.stay` (`walkIn`, `bookingId`).
- Validações executadas:
  - **testes primeiro:** o `FrontdeskWalkInApiTest` foi escrito antes do controller e do caso de uso compilarem juntos; a primeira rodada falhou só numa asserção do próprio teste (a entrada `reservations.room-assignment.assign` da trilha tem o id da alocação, não o da reserva: trocada pela contagem de alocações da UH);
  - `./gradlew test --tests 'com.zeny.hotel.frontdesk.*'` → verde (os 9 novos e os 16 da E02, depois da extração dos passos);
  - **mutações** (aplicadas, rodadas e revertidas, o arquivo restaurado idêntico, conferido com `cmp`): sem `@Transactional` no `walkIn` → os 9 testes falham (a guarda do `CheckInSteps.complete` recusa fora da transação de escrita); sem a confirmação da pré-reserva → falha "a plan with a card guarantee is confirmed at the desk…";
  - `./gradlew lint` → verde (depois de quebrar linhas longas e trocar o construtor de 8 parâmetros do apoio do teste por um mapa de contagens);
  - `./gradlew updateOpenApi` → `api/openapi.yaml` com `POST /walk-ins` (`IdempotencyKey`), `WalkInRequest` e `WalkInResponse`, e a descrição da tag `frontdesk`; nenhuma linha removida;
  - `.github/scripts/check-api-breaking.sh` → "Nenhuma mudança incompatível (nível ERR) em /api/v1" (contra `87cbb62`, o HEAD) e `.github/scripts/check-api-breaking.sh ffae917` → idem (contra o gate do M4);
  - `./gradlew build` → BUILD SUCCESSFUL em 7 min 3 s (1215 testes em 148 classes, 0 falhas; `ModularityTest`, ArchUnit, detekt, ktlint, `checkOpenApi` e `koverVerify`), rodado depois do contrato e da documentação;
  - snapshot depois da implementação (com este handoff): `56c3c36641344c6aea3010959f9364b0549b8f20` (`git diff --stat f6f3511716c2595523d5fa6af9b39b7d64385870` contra ele mostra os arquivos acima).
- Parecer do verificador (orquestrador, 2026-10-22): **APROVADO** (6/6 critérios; `./gradlew build` 1215 testes, 0 falhas; oasdiff sem `ERR` contra `87cbb62` e `ffae917`; `FrontdeskCheckInApiTest` da E02 intocado e 17/17). As recusas de RN-05 e RN-24 e a falha forçada acontecem depois da `createBooking` e provam o rollback da reserva, da conta e da unidade; o walk-in não tem `nightlyRates` (sem desconto) e passa pela `isSellable` no canal `DIRECT`. Decisões avaliadas: a aresta `frontdesk` → `rates` só por tipos é coerente na prática (a tabela já tem `reservations` → `rates` e `frontdesk` → `reservations` na mesma camada; a trava do `ModularityTest` impede usar a `RatesApi`); a confirmação da pré-reserva e a extração para `CheckInSteps`, aceitas; a ordem das travas, sem ciclo com os outros caminhos. Ressalvas baixas, aceitas pelo orquestrador em nome do usuário (delegado) e deixadas como notas:
  - garantia `CARD` gravada no walk-in sem declaração nem caução no pedido (o hóspede está presente e o check-in é imediato) — reavaliar no M7 (cartão integrado) se o walk-in deve exigir a `preAuthorization` quando a política copiada pede cartão;
  - registrar na ARCHITECTURE §3.2, no gate E12, uma ordem interna da camada Hospedagem (`rates` < `reservations` < `frontdesk`) que justifique as três arestas da mesma camada como regra, não como ressalva por aresta;
  - a H24 previa duas permissões; o walk-in exige três (`reservations.assignments.manage` também, coerente com a E02);
  - não testado: um id de hóspede **mesclado** no pedido (a `createBooking` troca pelo sobrevivente, mas o check-in usa os ids originais; o esperado é uma recusa com rollback) — cobrir na E05 ou no gate E12;
  - sem teste de concorrência próprio do walk-in (dois pela mesma UH ou pela última unidade): depende das garantias já testadas da E02 e do M4.
- Notas para a próxima etapa:
  - **E04 (check-out):** o walk-in deixa a reserva como qualquer outra hospedada (conta aberta, caução opcional ativa); nada de especial no check-out. Os passos comuns do check-in estão em `CheckInSteps`; para o check-out, um componente parecido evita duplicar a leitura da reserva e da conta.
  - **E05:** um walk-in num plano com depósito é recusado (`422 DEPOSIT_INSUFFICIENT`); se o hotel quiser cobrar adiantado no balcão, o caminho é a reserva normal com o pagamento do depósito antes do check-in.
  - **E08 (`fnrh`):** o walk-in publica `ReservationCreated` (e, num plano com cartão, `ReservationConfirmed`) e `GuestCheckedIn` na mesma transação, em ordem; o envio por evento trata o walk-in como uma reserva criada e já hospedada.
  - **Gate (E12):** o walk-in entra no cenário do `MilestoneM5EndToEndTest` e no roteiro do compose (`POST /api/v1/walk-ins` com um hóspede cadastrado e a UH inspecionada).

---

## E04 — Check-out — #concluida

**Objetivo:** implementar `POST /api/v1/reservations/{id}/check-out` (RF-REC-06 sem nota fiscal nem contas a receber): lança as cobranças da estadia que o fechamento nunca lançará (H14), cobra o late check-out só com o preço informado antes (RN-11, H13), exige saldo zero em todas as contas da reserva (RN-06), libera as cauções ainda ativas (H12), encerra as contas, passa a reserva a `CHECKED_OUT` (inclusive o check-out antecipado da E01) e a UH a Vaga + Suja (RN-09).

**Por que agora:** fecha o ciclo da estadia e publica `GuestCheckedOut`, consumido pela FNRH (E08) e pela governança (M6).

**Dependências:** E02. Independente de E05 e E06 (informativo: as três mexem em `api/openapi.yaml` e `FrontdeskPermissions`).

**Escopo:** `frontdesk` (caso de uso e endpoint, `@Idempotent`, `frontdesk.stays.checkout`), `BillingApi` (método novo de liberação de caução sem permissão, com as regras do `…/release`; `balanceOf`, `closeFolio`, `post` com o código da H13), `ReservationsApi.checkOut` e `RoomsApi.markVacant(CHECK_OUT)`. Ordem: cobranças pendentes da estadia (abaixo) → late check-out aceito (o valor da cotação enviado e ainda vigente, senão `409 EARLY_LATE_FEE_CHANGED`) → `BillingApi.balanceOf(FolioOwner(RESERVATION, id))` com `settled` (senão `409 FOLIO_BALANCE_NOT_ZERO` com o saldo de cada conta no problema) → liberação das cauções `ACTIVE`/`PARTIALLY_CAPTURED` (a captura, que é pagamento e exige turno, foi feita antes pelo endpoint do `billing`) → `closeFolio` de cada conta aberta → `checkOut` → UH Vaga + Suja. Reserva fora de `IN_HOUSE` → `409 RESERVATION_TRANSITION_NOT_ALLOWED` (o código da E01). Até o M6 as diárias não são lançadas por ninguém: os testes lançam as diárias pela `BillingApi.post` para ter saldo.

- **Cobranças que o fechamento nunca lançará** (H14, ressalva decidida em 2026-10-22): o fechamento do M6 só lança para quem está hospedado na data, então uma estadia que termina antes do fechamento da data operacional da chegada — o **day use** e qualquer **check-out no mesmo dia da chegada** — sairia sem a diária e sem os pacotes dessa data. Antes de conferir o saldo, o check-out lança, pela `BillingApi.post` na conta da reserva (o roteamento vale como em qualquer lançamento), a **diária congelada** da noite (`NightlyRateSummary.roomAmount` no código `ROOM_CHARGE`; inclusos e hospedagem conforme o lançamento separado do fechamento, RF-FIS-04, a combinar com o M6) e os pacotes `ON_ARRIVAL`/`DAILY` dessa data (`PackageCharge` com o `transactionCodeId` de cada um), com a origem `END_OF_DAY` (a cobrança é a que o fechamento faria; `PostingSource` é `enum` fechado) ou a que a etapa justificar no handoff. **Uma vez só:** cada cobrança leva uma chave de idempotência pelo vínculo com a reserva, a data e a cobrança (diária, ou o pacote); repetir o check-out, ou o fechamento do M6 depois, não lança de novo. A `BillingApi` não tem lançamento idempotente por chave: acréscimo mínimo nesta etapa, sem quebrar contrato — `NewPosting` ganha uma chave de origem opcional (ex.: `sourceReference`, texto curto sem dado pessoal, como `reservations.reservation:<id>:2026-11-02:room`) gravada no lançamento com um índice único parcial em `billing.posting`, e a `BillingApi.post` devolve o lançamento já existente com a mesma chave em vez de gravar outro (a conferência e a gravação sob a trava da conta). Nas demais estadias (pernoite com check-out em data posterior à chegada) nada é lançado aqui: o fechamento de cada noite já lançou.

**Fora de escopo:** emissão de nota (M7); transferência para contas a receber (M10 — registrar na captura de atualização do brain que o check-out só aceita saldo zero por pagamento até lá); tarefa de limpeza de saída (M6, por evento).

**Critérios de aceite:**

- [ ] Day use e check-out no mesmo dia da chegada lançam a diária congelada e os pacotes `ON_ARRIVAL`/`DAILY` da data antes de conferir o saldo, uma vez só (repetição do check-out e nova `BillingApi.post` com a mesma chave não duplicam); pernoite com check-out em data posterior não lança nada aqui (teste).
- [ ] Saldo diferente de zero (devedor ou credor) em qualquer conta da reserva → `409 FOLIO_BALANCE_NOT_ZERO`, com o saldo de cada conta no corpo e nada alterado.
- [ ] Reserva fora de `IN_HOUSE` → `409 RESERVATION_TRANSITION_NOT_ALLOWED`.
- [ ] Sucesso: reserva `CHECKED_OUT`, cauções sem sobra ativa, contas encerradas, alocação encerrada, UH Vago + Sujo, `GuestCheckedOut` publicado.
- [ ] Lançamento em conta encerrada depois do check-out é rejeitado (`409 FOLIO_CLOSED`).
- [ ] Late check-out lançado com o valor cotado pela política vigente; sem o aceite não há cobrança; valor diferente do vigente → `409 EARLY_LATE_FEE_CHANGED`; hotel sem oferta → `422 EARLY_LATE_NOT_OFFERED`.
- [ ] Repetição com a mesma `Idempotency-Key` → a resposta guardada, sem segundo lançamento.
- [ ] A captura de atualização do brain registra que, até o M10, o check-out só aceita saldo zero por pagamento.
- [ ] `./gradlew build` verde e oasdiff sem `ERR`.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Revisão humana:** não

**Notas para o plano:**

- Leia as notas para a E04 nos handoffs de E01 (o day use do check-out no dia da chegada deixa a noite da chegada para esta etapa), E02 (nota **B4** da revisão humana: depois de um desfazer, a caução do primeiro check-in continua ativa e um novo check-in registra outra; o check-out lista e libera **todas** as ativas; avaliar um aviso no check-in quando a conta já tem caução ativa) e E03 (os passos comuns do check-in estão em `CheckInSteps`; um componente parecido evita duplicar a leitura da reserva e da conta). Hipóteses H12, H13 e H14.
- Antes de encadear a liberação da caução (`CardPreAuthorizationService.release` do `billing`), o `balanceOf` e o `closeFolio`, confira que travas cada um toma, para o check-out não criar deadlock com o check-in e os pagamentos.
- A chave de idempotência da H14 não pode levar dado pessoal: só ids, a data e o tipo da cobrança.
- Cenários que o roadmap já previa além dos critérios: conta roteada da reserva com saldo, caução capturada antes com a sobra liberada, caução vencida e check-out antecipado.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Saldo com roteamento entre contas (RF-CTA-04): usar sempre o saldo oficial do `billing` (`balanceOf`), nunca recalcular no `frontdesk`.
- Ordem das travas: a conta é travada pelo `billing` em cada chamada; trave a reserva antes e registre a ordem no handoff para o walk-in e a troca de UH seguirem a mesma.
- **Nota obrigatória para o M6** (registrar no handoff e na captura de atualização do brain): o fechamento não lança de novo o que o check-out já lançou — usa a **mesma chave de idempotência** (reserva, data e cobrança) na `BillingApi.post`.

**Evidências e handoff:**

- Plano: `docs/superpowers/plans/2026-10-22-m5-recepcao-fnrh-e04.md` (5 tarefas; decisões D1 a D7 no plano), executado com `superpowers:subagent-driven-development` (implementador e revisor por tarefa, revisão final no modelo mais capaz, uma rodada de correção e re-revisão).
- Commits (branch `roadmap/m5-recepcao-fnrh`, a partir de `68a2a37`): `1955898` (início e plano), `b944c4a` (lançamento idempotente por `sourceReference` na `BillingApi`, migração `billing/V2026_10_22_2330__add_posting_source_reference.sql`), `27c1ab1` (`BillingApi.unsettledCardPreAuthorizations` e `releaseCardPreAuthorization`), `7ad294c` (regras puras `frontdesk/domain/CheckOutRules.kt`), `99c5bd7` (`FrontdeskCheckOutService`, `POST /api/v1/reservations/{id}/check-out`, `frontdesk.stays.checkout`, contrato, README, ARCHITECTURE §3.1), `51a0533` (ajustes da revisão final), mais o commit deste fechamento.
- Revisão final: **pronta para integrar**, 0 crítico, 0 importante, 6 menores. Três menores corrigidos em `51a0533`, com re-revisão limpa: os pacotes inclusos vão sem taxa de serviço e a resposta passa a trazer `serviceCharge`; o KDoc da `BillingApi` foi atualizado; e há teste do antecipado depois da chegada. Os outros três ficaram como notas (abaixo). Todos os menores adiados das revisões por tarefa foram triados como não bloqueantes.
- Critérios verificados (testes `FrontdeskCheckOutApiTest` 13, `CheckOutDomainTest` 9, `PostingSourceReferenceTest` 5 e 2 novos em `CardPreAuthorizationsApiTest`):
  - [x] **Cobranças da H14:** o day use e a saída no dia da chegada lançam a noite da chegada (hospedagem sem os inclusos, mais cada pacote), uma vez só. A repetição do check-out e uma `BillingApi.post` com a mesma chave não duplicam. O pernoite que sai depois da chegada não lança nada.
  - [x] Saldo devedor ou credor → `409 FOLIO_BALANCE_NOT_ZERO`, com `folios` e `total` já com as cobranças pendentes e o late, e nada alterado (estado conferido no banco).
  - [x] Fora de `IN_HOUSE` → `409 RESERVATION_TRANSITION_NOT_ALLOWED`.
  - [x] **Sucesso:** reserva `CHECKED_OUT`, todas as cauções em aberto `RELEASED` (vencida, de check-in desfeito e capturada em parte, B4), contas encerradas, alocação encerrada, UH Vaga + Suja e `GuestCheckedOut` publicado.
  - [x] Lançamento em conta encerrada depois do check-out → `409 FOLIO_CLOSED`.
  - [x] **Late check-out:** cobrado pelo valor cotado e vigente; sem o campo, nada é cobrado; com a política alterada, `409 EARLY_LATE_FEE_CHANGED`; sem oferta, `422 EARLY_LATE_NOT_OFFERED`.
  - [x] A mesma `Idempotency-Key` devolve a resposta guardada, sem segundo lançamento. Sem a chave, `400 IDEMPOTENCY_KEY_REQUIRED`. Sem a permissão, `403`.
  - [x] Captura do brain com "até o M10, saldo zero só por pagamento" (ver abaixo).
  - [x] `./gradlew build` → BUILD SUCCESSFUL em 7 min 8 s (1244 testes em 151 classes, 0 falhas). `.github/scripts/check-api-breaking.sh` (contra `68a2a37`) e `… ffae917` → "Nenhuma mudança incompatível (nível ERR) em /api/v1".
- **Decisões tomadas** (pela opção recomendada, em nome do usuário, delegado ao orquestrador, 2026-10-23; registradas no plano e no ledger):
  - **D1, ordem das travas:** reserva → inventário → conta(s) → UH. O caso de uso confere tudo sem trava, chama `ReservationsApi.checkOut` primeiro e depois as cobranças, o saldo, as cauções, o encerramento e a UH. O resultado é o da ordem de negócio do roadmap, porque tudo está numa transação.
  - **D2, cobranças da H14:** só quando a data operacional é a da chegada. A hospedagem `roomAmount − includedPackagesAmount` vai no `ROOM_CHARGE`, e cada pacote da noite no código dele. Os inclusos vão **sem taxa de serviço** (ajuste da revisão final, RN-11), e os adicionais seguem o código. A origem é `END_OF_DAY`.
  - **D3, chave sem dado pessoal:** `reservations.reservation:<id>:<data>:room` e `…:package:<packageId>`. O late não leva chave.
  - **D4, late:** `lateCheckOutFee` opcional, tipado como dinheiro em texto (como os outros valores da API, não `number`), lançado no `ROOM_CHARGE` como "Late check-out", origem `MANUAL`.
  - **D5, saldo:** o problema do saldo traz o saldo depois das cobranças pendentes, então o cliente paga exatamente o valor mostrado e repete o check-out (com outra chave, porque o `409` fica guardado).
  - **D6, cauções:** liberadas todas as não encerradas, inclusive `EXPIRED`.
  - **D7, permissão:** `frontdesk.stays.checkout` (Gerente e Recepcionista, não sensível).
  - O `frontdesk` passa a usar também os tipos `PackageId` e `PackageCharge` do `rates`, sem `RatesApi` (`ModularityTest` e ARCHITECTURE §3.1).
  - O apoio de testes comum fica em `FrontdeskTestSupport`.
- Decisões para o usuário (decididas pela opção recomendada em nome do usuário, ausente e delegado, 2026-10-23; ver "Decisões do lote E04 a E06"):
  - **(a) Pacotes inclusos:** lançados à parte da hospedagem e sem taxa de serviço (D2). O M6 precisa seguir a mesma regra.
  - **(b) Problema do saldo:** mostra o saldo já com as cobranças que o check-out lançaria (D5).
  - **(c) Cauções vencidas:** também são liberadas no check-out (D6).
- Notas para a próxima etapa:
  - **Nota obrigatória para o M6:** o fechamento não lança de novo o que o check-out lançou. Ele usa a **mesma chave** na `BillingApi.post`, separa os inclusos da hospedagem e lança os inclusos sem taxa de serviço. A repetição é silenciosa: a `BillingApi` devolve o lançamento existente, seja qual for o valor pedido, e o M6 deve conferir o valor ou registrar o aviso. A repetição também calcula o roteamento antes da busca: uma regra nova que feche o destino pode recusá-la.
  - Até o M10, o check-out só aceita saldo zero por pagamento (sem contas a receber). A captura da caução é feita antes, pelo `billing`.
  - **E05 (troca de UH):** siga a ordem reserva → conta(s) → UH. Risco residual: o check-out trava as contas da reserva uma a uma, não em ordem de id. Um lançamento roteado simultâneo numa segunda conta da reserva pode dar deadlock, com `500` em um dos lados (raro; travar todas as contas em ordem de id resolveria). Na mesma janela, o `409` de saldo pode vir do `billing`, sem `folios`.
  - **Gate (E12):** o check-out entra no `MilestoneM5EndToEndTest` e no roteiro do compose.

---

## E05 — Troca de UH, extensão, redução e early check-in — #concluida

**Objetivo:** expor no `frontdesk` a troca de UH do hóspede em casa com motivo, mantendo ou recalculando a tarifa (RF-REC-04), usando a transição da E01 e atualizando o status das duas UHs; cobrir de ponta a ponta a extensão e a redução do hospedado (RF-REC-05) pelo `PUT` da E01; e acrescentar ao check-in a cobrança do early check-in com o preço informado antes (RF-REC-03, RN-11, H13).

**Por que agora:** completa as operações de estadia em curso usando as transições da E01 e a cotação da E02.

**Dependências:** E02. Independente de E04 e E06 (informativo: mexe em `api/openapi.yaml` e no check-in do `frontdesk`).

**Escopo:** `frontdesk`:

- `POST /api/v1/reservations/{id}/room-moves` (`@Idempotent`, `frontdesk.stays.move`, `If-Match` da reserva): UH de destino pronta (`RoomsApi.isReadyForCheckIn`, `409 ROOM_NOT_READY`), `ReservationsApi.moveRoom`, origem Vaga + Suja (`markVacant(CHECK_OUT)` ou a origem que a etapa decidir) e destino Ocupada com o número de hóspedes, motivo, tarifa mantida ou recalculada com a diferença na resposta; `RoomMoved` (da E01).
- Extensão e redução do hospedado pelo `PUT /api/v1/reservations/{id}` (E01): testes de ponta a ponta com a UH ocupada (a UH não muda de status; a extensão sem a UH livre pede a troca).
- Early check-in no check-in da E02: campo opcional com o valor da cotação; lançado pela `BillingApi.post` (H13) na mesma transação só se aceito e ainda vigente (`409 EARLY_LATE_FEE_CHANGED`; sem oferta `422 EARLY_LATE_NOT_OFFERED`).

**Fora de escopo:** tarefa de limpeza na UH de origem (M6, por `RoomMoved`).

**Critérios de aceite:**

- [ ] Troca para UH não pronta → `409 ROOM_NOT_READY`; troca válida → `RoomMoved` publicado, origem Vago + Sujo, destino Ocupado, motivo registrado na alocação.
- [ ] Troca "mantendo tarifa" não altera as noites; "recalculando" altera só as restantes e devolve a diferença na resposta.
- [ ] Extensão sem disponibilidade → `409 ROOM_TYPE_UNAVAILABLE` e nada alterado; extensão com a UH alocada a outra reserva → `409 ROOM_ALREADY_ASSIGNED`; redução devolve inventário.
- [ ] Early check-in só é lançado quando o valor da cotação vem no check-in e ainda é o da política (teste com o valor mudado entre a cotação e o check-in).
- [ ] Troca sem `If-Match` → `428`; com versão velha → `412`.
- [ ] `./gradlew build` verde e oasdiff sem `ERR`.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Revisão humana:** não

**Notas para o plano:**

- Leia RF-REC-03, RF-REC-04, RF-REC-05 e RN-11 em `docs/SPEC.md`, a H13 e as notas para a E05 nos handoffs de E01, E02 e E03.
- Decisões que a E01 deixou para esta etapa (também em "Decisões abertas para a entrevista de cada etapa"): a `moveRoom` só troca para o **mesmo tipo** (outro tipo é `422 ROOM_TYPE_MISMATCH`; o upgrade precisaria trocar o `roomTypeId` da reserva e mover o inventário das noites restantes numa só `apply`); a ocupação do hospedado está fixa (`409 IN_HOUSE_STAY_FIXED`); e a redução da saída pelo `PUT` passa pelas restrições de venda (`relevantRefusals`: estadia mínima e CTD podem recusá-la com `422`, enquanto o check-out antecipado nunca é recusado) — decidir se valem para quem já está em casa. Cada uma vai para "Decisões para o usuário".
- A UH de destino é ocupada com `RoomsApi.markOccupiedIfReady` (a corrida B1 da E02: dois check-ins, ou uma troca e um check-in, na mesma UH); a cobrança do early usa a mesma cotação da E02 (`EarlyLateQuotes.early`).
- Pendência de teste da E03: um id de hóspede mesclado no pedido do walk-in (a `createBooking` troca pelo sobrevivente, mas o check-in usa o id original; o esperado é uma recusa com rollback) — cobrir aqui ou no gate E12.
- Preço congelado (RN-14): as noites passadas nunca mudam no recálculo.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Preço congelado (RN-14): recálculo só nas noites restantes; noites passadas preservam o preço original (teste).

**Evidências e handoff:**

- **Plano:** `docs/superpowers/plans/2026-10-23-m5-recepcao-fnrh-e05.md`, com 5 tarefas e as decisões D1 a D8. Executado com `superpowers:subagent-driven-development`: implementador e revisor por tarefa, revisão final no modelo mais capaz, uma rodada de correção e re-revisão.
- **Commits** (a partir de `83766da`):
  - `a15672a`: início e plano.
  - `1a1b9b1`: `ReservationsApi.moveRoom(..., expectedVersion)` conferido sob a trava; a redução do hospedado passa a ignorar as restrições de venda.
  - `515d0a1`: `POST /api/v1/reservations/{id}/room-moves`, com a permissão `frontdesk.stays.move` e o `FrontdeskRoomMoveService`.
  - `5aa0c9c`: early check-in no check-in e no walk-in; `LateCheckOutRules` passa a se chamar `EarlyLateFeeRules`.
  - `e692d9f`: testes de ponta a ponta da extensão e da redução do hospedado.
  - `a439886`: ajustes da revisão final.
  - Mais o commit deste fechamento.
- **Revisão final:** **com correções**, sendo 1 importante e 6 menores.
  - O importante: um check-out que esperava a trava atrás de uma troca simultânea vagava a UH de origem e deixava a de destino Ocupada sem hóspede. É uma corrida da E04, exposta pela troca. Foi corrigido em `a439886`: `ReservationsApi.checkOut(id, expectedVersion)` é conferido sob a trava, e o `frontdesk` responde `409 RESERVATION_CHANGED` quando a reserva mudou. A corrida entre check-out e troca foi testada nas duas ordens.
  - Na mesma rodada:
    - a pré-checagem do `If-Match` na troca, no lugar do pós-check morto;
    - o `complete` passa a receber o early já aceito;
    - uma asserção da ordem das UHs no teste de falha forçada.
  - A re-revisão confirmou os 4 itens, sem nada novo quebrado. Os menores restantes das revisões por tarefa foram triados como não bloqueantes.
- **Critérios verificados** (testes: `FrontdeskRoomMoveApiTest` 7, mais a corrida com o check-out; `RoomMoveDomainTest` 2; `FrontdeskEarlyCheckInApiTest` 5; `FrontdeskInHouseChangeApiTest` 5; 3 novos em `StayTransitionsApiTest`):
  - [x] Troca para UH não pronta → `409 ROOM_NOT_READY`. A troca válida publica `RoomMoved`, deixa a origem Vaga + Suja e o destino Ocupado, e registra o motivo na alocação nova (a antiga termina com `ROOM_MOVED`).
  - [x] A troca mantendo a tarifa não altera as noites. Recalculando, altera só as restantes: a noite passada fica com o preço antigo (RN-14), e `difference` = soma nova − soma antiga das restantes.
  - [x] Extensão sem disponibilidade → `409 ROOM_TYPE_UNAVAILABLE`, e nada muda. Extensão sobre a UH alocada a outra reserva → `409 ROOM_ALREADY_ASSIGNED`. A redução devolve o inventário (o `sold` foi conferido).
  - [x] O early check-in só é lançado com o valor cotado e ainda vigente. Com a política mudada entre a cotação e o check-in → `409 EARLY_LATE_FEE_CHANGED`, e nada é gravado. Sem oferta → `422 EARLY_LATE_NOT_OFFERED`. Vale também no walk-in, e a repetição com a mesma chave não lança de novo.
  - [x] Troca sem `If-Match` → `428`; com versão velha → `412`, também quando a versão muda entre a leitura e a trava.
  - [x] `./gradlew build` → BUILD SUCCESSFUL em 7 min 22 s (1266 testes em 155 classes, 0 falhas). `.github/scripts/check-api-breaking.sh` (contra `68a2a37`) e `… ffae917` → "Nenhuma mudança incompatível (nível ERR) em /api/v1".
- **Decisões tomadas:** todas pela opção recomendada, em nome do usuário (delegado ao orquestrador, 2026-10-23), depois de pesar prós e contras. Estão registradas no plano e no ledger.
  - **D1, troca só para o mesmo tipo:** outro tipo continua `422 ROOM_TYPE_MISMATCH`. O upgrade fica pendente.
  - **D2, ocupação do hospedado fixa:** `409 IN_HOUSE_STAY_FIXED`.
  - **D3, redução sem restrições de venda:** a redução do hospedado não confere estadia mínima nem CTA/CTD, como o check-out antecipado. A extensão continua conferindo as restrições.
  - **D4, status da origem:** `markVacant(CHECK_OUT)`, sem valor novo no `RoomStatusSource` do `rooms`.
  - **D5, versão sob a trava:** na troca e, desde a correção, também no check-out.
  - **D6, early também no walk-in.**
  - **D7, permissão:** `frontdesk.stays.move`, para Gerente e Recepcionista.
  - **D8, diferença da tarifa:** calculada sobre as noites a partir da data operacional.
  - **Ordem das recusas da troca:** a UH não pronta (`409`) vem antes do outro tipo (`422`). A mesma UH pula a conferência de prontidão para dar `422 ROOM_MOVE_SAME_ROOM`.
  - **Check-out com a reserva alterada:** `409 RESERVATION_CHANGED`, um código novo do `frontdesk`, porque o check-out não recebe `If-Match`.
  - **Walk-in com um hóspede mesclado:** dá `201` com o sobrevivente em tudo (pendência da E03, coberta por teste).
- **Decisões para o usuário** (decididas pela opção recomendada em nome do usuário, ausente e delegado, 2026-10-23; ver "Decisões do lote E04 a E06"):
  - **(a) Upgrade para outro tipo de UH:** fica para depois. Quando entrar, troca o tipo da reserva e move o inventário das noites restantes.
  - **(b) Ocupação do hospedado:** continua fixa.
  - **(c) Redução sem restrições de venda.**
  - **(d) Origem da troca:** registrada como `CHECK_OUT` no histórico da UH.
  - **(e) Early também no walk-in.**
  - **(f) Teste intermitente:** o `ReservationSearchApiTest` ("every search filter … is served by an index") falha às vezes, porque o planner escolhe `reservation_arrival_idx` no filtro "period". Recomendação: aceitar os dois índices nesse filtro.
- **Notas para a próxima etapa:**
  - **E06 (recados):** os recados não dependem da troca nem do check-out.
  - **Comportamento conhecido:** depois de desfazer o check-in, o early lançado continua na conta. Um novo check-in com `earlyCheckInFee` lança outro, e o cliente estorna pelo `billing`.
  - **Dívida transversal:** as descrições dos campos de dinheiro (`difference`, `remainingBefore`/`After`, `earlyCheckInFee` e `lateCheckOutFee`) somem no contrato, substituídas pela descrição genérica de `Money`.
  - **Possível confusão de nomes:** `RESERVATION_CHANGED` tem o mesmo nome do motivo de soltura `RoomAssignmentEndReason.RESERVATION_CHANGED`.
  - **M6 (`housekeeping`):** gera a limpeza da origem pelo `RoomMoved`. A UH de origem já fica Suja aqui.
  - **Gate (E12):** a troca de UH e o early entram no `MilestoneM5EndToEndTest` e no roteiro do compose.

---

## E06 — Recados — #concluida

**Objetivo:** implementar recados (RF-REC-07, `Trace` da SPEC §8.2.6): lembretes para setores vinculados a uma reserva ou UH, com data, responsável e resolução.

**Por que agora:** é a última funcionalidade de recepção da Fase 1 e independe do fluxo financeiro.

**Dependências:** E02 (módulo `frontdesk` criado). Independente de E04 e E05 (informativo: mexe em `api/openapi.yaml` e `FrontdeskPermissions`).

**Escopo:** `frontdesk` — recado com setor (`StaffApi.departments()`, aresta `frontdesk` → `staff` na ARCHITECTURE §3.1 e no `ModularityTest`; inexistente `400 UNKNOWN_DEPARTMENT`), vencimento (data de negócio), texto (até 500), vínculo a reserva (`ReservationsApi.reservation`) **ou** UH (`RoomsApi.room`) — nenhum ou os dois `400` —, responsável opcional e resolução (quem e quando). Endpoints pela convenção: `POST /api/v1/traces` (201), `GET /api/v1/traces` (filtros `departmentId`, `dueOn`, `status`, `reservationId`, `roomId`; cursor), `GET /{id}` (`ETag`), `PUT /{id}` (`If-Match`), `POST /{id}/resolve` (resolvido de novo `409 TRACE_ALREADY_RESOLVED`). Permissões `frontdesk.traces.read`/`manage` (H20). Migração no schema `frontdesk`, sem `DELETE` (trigger que recusa). Trilha sem o texto (texto livre, H10 do M3).

**Fora de escopo:** notificação push dos recados: a SPEC não pede (RF-NOT-01 não lista recados).

**Critérios de aceite:**

- [ ] `POST /api/v1/traces` cria recado vinculado a reserva ou UH; sem vínculo ou com os dois → `400`; setor inexistente → `400 UNKNOWN_DEPARTMENT`.
- [ ] `GET /api/v1/traces` filtra por setor, data e situação, com paginação por cursor.
- [ ] Resolver registra usuário e instante; recado resolvido não é resolvido de novo (`409`); `PUT` exige `If-Match`.
- [ ] Escrita sem permissão → `403`; escrita auditada sem o texto; nenhum `DELETE`.
- [ ] `ModularityTest` e ARCHITECTURE §3.1 com `frontdesk` → `staff`; `./gradlew build` verde e oasdiff sem `ERR`.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Revisão humana:** não

**Notas para o plano:** leia RF-REC-07 em `docs/SPEC.md` e a H20 (nomes das permissões). O `frontdesk` não tinha estado próprio até a E03 (nenhuma tabela nem migração): se o schema ainda não existir, a pasta de migrações dele e a migração de `GRANT` ao papel da aplicação nascem aqui, com timestamp posterior à última aplicada. O texto do recado também fica fora do log.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Nenhum relevante.

**Evidências e handoff:**

- **Plano:** `docs/superpowers/plans/2026-10-23-m5-recepcao-fnrh-e06.md` (3 tarefas; decisões D1 a D6). Executado com `superpowers:subagent-driven-development`: implementador e revisor por tarefa, revisão final no modelo mais capaz, uma rodada de correção e re-revisão.
- **Commits** (a partir de `5068040`):
  - `fb2ae34`: início e plano.
  - `b56ceaa`: schema `frontdesk`, tabela `frontdesk.trace` sem `DELETE` (trigger), migrações `frontdesk/V2026_10_23_1200__create_traces.sql` e `…1201__grant_frontdesk_to_app_role.sql`, domínio `Trace`/`TraceRules` e `JdbcTraceStore`; aresta `frontdesk` → `staff`.
  - `d938259`: `FrontdeskTraceService`, `TracesController` (`/api/v1/traces`), permissões, contrato e README.
  - `3dfeafe`: ajustes da revisão final.
  - Mais o commit deste fechamento.
- **Revisão final:** **com correções**, 0 críticas, 0 importantes, 4 menores e uma recomendação. Tudo foi corrigido em `3dfeafe`, com re-revisão limpa:
  - a citação RF-REC-08 trocada por RF-REC-07 (também na migração);
  - `textLength` contado em code points;
  - `textChanged` na trilha da alteração;
  - testes do `PUT` sem mudança e do `UNKNOWN_ASSIGNEE` no `PUT`;
  - leitura e resolução dos recados também para Caixa A&B e Cozinha/Bar;
  - aviso "não escreva dados pessoais do hóspede" na descrição do texto.

  Os menores adiados das revisões por tarefa foram triados como não bloqueantes.
- **Critérios verificados** (testes `FrontdeskTracesApiTest` 10, `TraceDomainTest` 6, `TraceStoreTest` 5):
  - [x] `POST /api/v1/traces` cria o recado vinculado a uma reserva ou a uma UH. Sem vínculo, ou com os dois → `400 TRACE_INVALID`. Setor inexistente → `400 UNKNOWN_DEPARTMENT`. Também recusa: reserva, UH ou responsável inexistentes (`400`) e UH desativada (`422 ROOM_INACTIVE`).
  - [x] `GET /api/v1/traces` filtra por setor, vencimento, situação, reserva e UH, com cursor `(due_on, id)`; as páginas cobrem todos os recados sem repetição.
  - [x] Resolver registra o usuário e o instante. Um recado já resolvido → `409 TRACE_ALREADY_RESOLVED`, também na corrida entre dois usuários (trava da linha). O `PUT` exige `If-Match` (`428`/`412`), e num recado resolvido dá `409`.
  - [x] Escrita sem permissão → `403`. As escritas são auditadas sem o texto (`textLength`, `textChanged`), e o texto não aparece na trilha, nos problemas nem no log (teste com marcadores). Não há `DELETE`: nem endpoint, nem `DELETE` físico (trigger).
  - [x] `ModularityTest` e ARCHITECTURE §3.1 incluem `frontdesk` → `staff`, só pela `StaffApi` e pelos tipos dela.
  - [x] `./gradlew build` → BUILD SUCCESSFUL em 6 min 53 s (1287 testes em 158 classes, 0 falhas). Oasdiff contra `68a2a37` e `ffae917` → "Nenhuma mudança incompatível (nível ERR) em /api/v1".
- **Decisões tomadas** pela opção recomendada, em nome do usuário (delegado ao orquestrador, 2026-10-23), depois de pesar prós e contras:
  - **D1, responsável:** `assigneeUserId` de um usuário vinculado a um colaborador, conferido pela `StaffApi.employeeOfUser` (`400 UNKNOWN_ASSIGNEE`).
  - **D2, permissões:** `frontdesk.traces.read` e `frontdesk.traces.resolve` para Gerente, Recepcionista, Governanta, Manutenção, Comercial/Eventos, Caixa A&B e Cozinha/Bar. `frontdesk.traces.manage` só para Gerente e Recepcionista. Assim, o setor que cumpre o recado o resolve sem poder editá-lo.
  - **D3, vínculo fixo:** a reserva ou a UH é definida só na criação.
  - **D4, vencimento:** não pode ser anterior à data operacional, na criação e quando muda.
  - **D5, situação:** `OPEN` ou `RESOLVED`, calculada.
  - **D6, texto:** de 1 a 500 caracteres, aparado.
  - **Fora dos modelos padrão:** os modelos de setor que ficaram de fora (Camareira, Garçom, Almoxarife, Financeiro) recebem acesso por concessão do administrador.
- **Decisões para o usuário** (decididas pela opção recomendada em nome do usuário, ausente e delegado, 2026-10-23; ver "Decisões do lote E04 a E06"):
  - (a) responsável como usuário com colaborador;
  - (b) a permissão de resolver separada e os modelos que a recebem;
  - (c) o vínculo fixo.
- **Notas para a próxima etapa:**
  - **Primeiro estado próprio do `frontdesk`:** o schema e a pasta `db/migration/frontdesk/` existem desde esta etapa.
  - **Ajuste depois da integração:** o comentário da migração `V2026_10_23_1200` mudou na correção. Um banco local que já a aplicou precisa de `flyway repair`; a CI e os testes usam bancos novos.
  - **Possíveis acompanhamentos:**
    - mostrar no recado de reserva o número da UH alocada;
    - um texto com U+0000 provavelmente vira `500` (vale para o projeto todo);
    - `StaffApi.departments()` é lido a cada chamada (aceitável para 30 UHs);
    - um filtro de recados atrasados.
  - **Gate (E12):** os recados entram no roteiro do compose.

---

## E13 — Travamento de dependências do Gradle para o scanner — #concluida

**Objetivo:** ligar o travamento de dependências do Gradle (dependency locking) e versionar o `gradle.lockfile`, para que o scanner de dependências usado na revisão final de cada etapa (`osv-scanner`, soul `revisor-seguranca`) enxergue o grafo resolvido do projeto.

**Por que agora:** desde 2026-10-27 os roadmaps executam pelas souls, e a revisão final exige saída de scanner que liste pacotes do stack; sem ela, a revisora bloqueia. Hoje `osv-scanner scan source -r .` na raiz responde "No package sources found" (verificado em 2026-10-27 com o osv-scanner 2.7.0): o projeto tem `gradle/libs.versions.toml`, mas nenhum lockfile. Por isso esta etapa vem antes das demais pendentes (decisão do usuário, D3).

**Dependências:** nenhuma. Independente das etapas de recepção, FNRH e impressão.

**Escopo:** configuração de build do Gradle (raiz do projeto), o `gradle.lockfile` gerado e versionado, e o registro da convenção de atualização do lockfile.

- Travamento ligado para as configurações resolvidas pelo build (compilação, runtime e testes); o classpath de plugins (buildscript) entra se o planejador concluir que o scanner precisa dele.
- `gradle.lockfile` gerado e versionado.
- Com o lockfile desatualizado em relação ao que o build resolve, `./gradlew build` falha (o comportamento padrão do Gradle com travamento; o plano confere).
- Convenção: toda etapa que muda dependência atualiza o lockfile no mesmo commit; registrada na captura de atualização do brain (nota `ZenyHotelManager - Ambiente, banco, chaves e build`), nunca no `CLAUDE.md`.

**Fora de escopo:** atualizar versões de dependências; corrigir vulnerabilidades que o scanner apontar (vão para "Decisões para o usuário" e para etapas futuras); plugin OWASP Dependency-Check.

**Critérios de aceite:**

- [ ] `gradle.lockfile` versionado na raiz, gerado pelo próprio Gradle.
- [ ] `osv-scanner scan source -r .` na raiz lista os pacotes Maven do projeto (não responde mais "No package sources found"); a saída fica no handoff.
- [ ] Mudar a versão de uma dependência em `gradle/libs.versions.toml` sem atualizar o lockfile faz `./gradlew build` falhar (verificado numa cópia descartável e revertido); a saída fica no handoff.
- [ ] `./gradlew build` e `./gradlew checkOpenApi` verdes com o lockfile atual, localmente e no CI.
- [ ] Vulnerabilidades apontadas pelo scanner registradas no handoff, com as de severidade alta ou crítica em "Decisões para o usuário".

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `osv-scanner scan source -r .` → lista pacotes Maven do projeto.

**Revisão humana:** sim

**Notas para o plano:**

- Documentação oficial: https://docs.gradle.org/current/userguide/dependency_locking.html (travamento por `dependencyLocking { lockAllConfigurations() }` e geração com `./gradlew dependencies --write-locks`; o plano confere a forma certa para este build).
- O `osv-scanner` 2.7.0 lê `gradle.lockfile` (conferido em 2026-10-27 num projeto de teste). Ele não está no CI; rodá-lo no CI fica fora de escopo, salvo decisão do usuário.
- A revisora não executa nada: o orquestrador roda o scanner e anexa a saída ao pacote da revisão final.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Lockfile esquecido em mudanças futuras de dependência: o build falha (critério 3), e a revisora confere lockfile e manifesto juntos.

**Evidências e handoff:**

- **Plano:** `docs/superpowers/plans/2026-10-28-m5-recepcao-fnrh-e13.md` (uma tarefa), executado pelas souls: `desenvolvedor` → `testador` (ataques numa cópia descartável) → verificação mecânica → `revisor-seguranca`.
- **Commits** (branch `roadmap/m5-recepcao-fnrh`, a partir de `94a24a9`):
  - `87c0b15` início da etapa e plano;
  - `1a4b0ab` travamento estrito, `gradle.lockfile` e `buildscript-gradle.lockfile`, imagem e README;
  - `6ba3ac1` `settings-gradle.lockfile`, que o `--write-locks` também gera;
  - `c1acc86` modo estrito também no classpath dos plugins e texto preciso (rodada 1, pelo achado do testador);
  - `a5dc411` textos e remoção da ativação redundante (onda da revisão final);
  - o commit de fechamento (este handoff, a E14 e o histórico).
- **Revisão final:** `revisor-seguranca` no modelo mais capaz, sobre `94a24a9..c1acc86`. Resultado: "With fixes", sem Critical. Os três Important eram o handoff vazio, o critério 3 só para versão maior e o `docker build` sem prova no HEAD; foram resolvidos neste fechamento e pela prova da imagem em `a5dc411`. Os três Minor entraram na onda única de correção (`a5dc411`), conferida pelo testador e por uma re-revisão restrita. Na revisão da tarefa: "Approved", 0 Critical, 0 Important.
- **Configuração:**
  - `build.gradle.kts`: `dependencyLocking { lockAllConfigurations(); lockMode = LockMode.STRICT }` no projeto e o mesmo bloco dentro de `buildscript { }`, para o classpath dos plugins.
  - Lockfiles na raiz, gerados por `./gradlew dependencies --write-locks`:
    - `gradle.lockfile`: 415 pacotes;
    - `buildscript-gradle.lockfile`: 74 pacotes;
    - `settings-gradle.lockfile`: hoje só `empty=incomingCatalogForLibs0`, versionado para a regeneração não sujar a árvore.
  - O `.dockerignore` (lista de permissão) e o `docker/Dockerfile` levam os dois primeiros.
- **Critérios verificados:**
  - [x] `gradle.lockfile` versionado na raiz, gerado pelo Gradle. O testador apagou os três lockfiles, regenerou do zero e obteve arquivos idênticos aos versionados; uma segunda regeneração também não muda nada.
  - [x] O `osv-scanner` lista os pacotes Maven. Saída numa cópia limpa do HEAD (`git archive`): "Scanned …/gradle.lockfile file and found 415 packages", "Scanned …/buildscript-gradle.lockfile file and found 74 packages", "Total 11 packages affected by 31 known vulnerabilities (5 Critical, 15 High, 8 Medium, 3 Low, 0 Unknown) from 1 ecosystem". Na base `94a24a9` a saída era "No package sources found". Dentro de um worktree em `.claude/worktrees/`, o scanner não vê nada, porque o `.gitignore` do checkout principal ignora essa pasta; rode-o na raiz do checkout principal, numa cópia ou com `--no-ignore`.
  - [x] Lockfile desatualizado faz o build falhar. Teste numa cópia descartável, revertido:
    - versão maior no catálogo (`aws-sdk = "2.57.6"`): falha com "Constraint … {strictly 2.56.0} … enforced by Dependency Locking";
    - plugin maior (`spring-boot = "4.1.2"`): falha com "Cannot find a version of 'org.springframework.boot:org.springframework.boot.gradle.plugin' that satisfies the version constraints … {strictly 4.1.1}";
    - dependência nova (`commons-pool2`): falha com "Resolved 'org.apache.commons:commons-pool2:2.13.1' which is not part of the dependency lock state";
    - sem `buildscript-gradle.lockfile`: falha com "Locking strict mode: Configuration 'classpath' is locked but does not have lock state";
    - imagem sem os lockfiles: falha no `COPY` ("not found"); com o lockfile defasado, falha no modo estrito.
    - **Ressalva:** uma versão **menor** no catálogo (`shedlock = "7.10.0"`) não falha. O Gradle aplica a versão travada como `strictly` e usa a 7.10.1. O README e o comentário do build dizem isso, e o caso foi para "Decisões para o usuário".
  - [x] `./gradlew build` e `./gradlew checkOpenApi` verdes localmente:
    - `c1acc86`: "BUILD SUCCESSFUL in 7m 51s", 1287 testes em 158 classes, 0 falhas (rodado pelo orquestrador);
    - `a5dc411`: "BUILD SUCCESSFUL in 7m 54s" (rodado pelo desenvolvedor);
    - `checkOpenApi` verde e `api/openapi.yaml` sem mudança; oasdiff contra `94a24a9`: "No changes detected";
    - `docker build -f docker/Dockerfile .` em `a5dc411`: exit 0, com o `bootJar` executado (94,3 s);
    - `ktlintFormat`, `koverHtmlReport` e `updateOpenApi` também passam no modo estrito sem mudar a árvore; o `bootRun` resolve o classpath e sobe até pedir o banco.
    - **CI:** o push da integração (`94a24a9..11cacb0` na `main`) passou no run `40788327598`, verde nos três jobs (build, testes e contrato; oasdiff; imagem Docker). Antes do push, a `main` integrada também passou no `./gradlew build` ("BUILD SUCCESSFUL in 8m 51s"), e o `osv-scanner scan source -r .` na raiz do checkout principal leu 415 + 74 pacotes.
  - [x] Vulnerabilidades registradas. Na produção (`productionRuntimeClasspath`):
    - `org.apache.tomcat.embed:tomcat-embed-core` 11.0.24: 2 Critical de 9,8 e 2 de 9,1, corrigidas na 11.0.25;
    - `tools.jackson.core:jackson-core`/`jackson-databind` 3.1.5: High 7,5, corrigidas na 3.1.6/3.1.7; inclui DoS sem autenticação no parser das requisições;
    - `com.fasterxml.jackson.core:jackson-core`/`jackson-databind` 2.22.1, usadas pelo springdoc: High 7,5, corrigidas na 2.22.2/2.22.3;
    - `io.opentelemetry:opentelemetry-api` 1.62.0: Medium.

    Só no build:
    - `org.freemarker:freemarker` 2.3.32, no classpath dos plugins: Critical 9,1, corrigida na 2.3.35;
    - `tools.jackson.*` 3.1.5 no classpath dos plugins;
    - `commons-lang3` 3.16.0 no plugin: Medium;
    - `logback-core` 1.3.16 no `ktlint`: Low.
- **Decisões para o usuário** (o usuário estava ausente e delegou em 2026-10-28; decididas pela opção recomendada em nome dele depois de pesar prós e contras; ver "Decisões do lote E13"):
  - (a) vulnerabilidades High/Critical numa etapa própria de manutenção, a E14, primeira da fila;
  - (b) o rebaixamento silencioso no catálogo é aceito como comportamento do Gradle, documentado, e fechado pelo passo de CI da E14;
  - (c) o `osv-scanner` fica fora do CI por ora;
  - (d) a verificação de checksums fica para depois;
  - (e) o `settings-gradle.lockfile` é versionado.
- **Notas para a próxima etapa:**
  - **Convenção:** toda mudança de dependência ou plugin regrava os lockfiles no mesmo commit, com `./gradlew dependencies --write-locks`. Os revisores conferem `build.gradle.kts`, catálogo e lockfiles juntos.
  - **Scanner da revisão final:** rode-o fora do worktree (veja o critério 2).
  - **Revisão humana desta etapa:** pendente. O usuário pediu, ausente, a integração na `main` no fim do lote; a revisão fica para quando ele voltar.

---

## E14 — Correção das vulnerabilidades High/Critical apontadas pelo scanner — #concluida

**Objetivo:** levar às versões corrigidas as dependências com vulnerabilidade High ou Critical que o `osv-scanner` apontou na E13. Isso vale para o runtime de produção (`tomcat-embed-core`, `jackson` 3 e 2) e para o classpath dos plugins (`freemarker`). A etapa também regrava os lockfiles e põe na CI a conferência de que lockfile e catálogo não divergem.

**Por que agora:** a E13 tornou o grafo visível. No `productionRuntimeClasspath` há duas Critical de 9,8 no servidor HTTP e um DoS sem autenticação no parser das requisições, todos corrigidos em versões de patch. Sem esta etapa, toda revisão final das etapas seguintes volta a apontá-los. Decidida pela opção recomendada em nome do usuário (delegado ao orquestrador), 2026-10-28; ver "Decisões do lote E13".

**Dependências:** E13.

**Escopo:** `gradle/libs.versions.toml`, `build.gradle.kts` (só se uma restrição de versão for necessária), os três lockfiles e `.github/workflows/ci.yml`:

- Versões corrigidas, vistas no handoff da E13. Rode o scanner de novo antes de planejar, porque a lista muda com o tempo:
  - `org.apache.tomcat.embed:tomcat-embed-core` 11.0.24 → ≥ 11.0.25;
  - `tools.jackson.core:jackson-core`/`jackson-databind` 3.1.5 → ≥ 3.1.7;
  - `com.fasterxml.jackson.core:jackson-core`/`jackson-databind` 2.22.1 → ≥ 2.22.3;
  - `org.freemarker:freemarker` 2.3.32 (classpath dos plugins) → ≥ 2.3.35;
  - de passagem, se o mesmo caminho resolver: `io.opentelemetry:opentelemetry-api` (Medium), `commons-lang3` do plugin (Medium) e `logback-core` do `ktlint` (Low).
- Caminho preferido: o patch do Spring Boot (plugin e BOM juntos, no catálogo) que já traga essas versões. Se não houver, use uma restrição de versão no catálogo e no `build.gradle.kts`, com o motivo e o advisory no comentário. A restrição sai quando o BOM alcançar a versão. Para o `freemarker`, descubra qual plugin o traz (`./gradlew buildEnvironment`) e prefira subir o plugin.
- Lockfiles regravados no mesmo commit: `./gradlew dependencies --write-locks`.
- Passo novo no job `build` da CI: `./gradlew dependencies --write-locks` seguido de `git diff --exit-code -- '*.lockfile'`. Assim a CI pega lockfile esquecido e o rebaixamento silencioso no catálogo (ressalva do critério 3 da E13).

**Fora de escopo:** subir versões sem vulnerabilidade High ou Critical, salvo as que o caminho escolhido arrastar; o `osv-scanner` no CI; a verificação de checksums (`gradle/verification-metadata.xml`); trocar de biblioteca.

**Critérios de aceite:**

- [ ] O `osv-scanner` (na raiz do checkout principal ou numa cópia limpa) não aponta High nem Critical no `gradle.lockfile` e no `buildscript-gradle.lockfile`. O que restar sem correção publicada fica no handoff como risco, em "Decisões para o usuário". A saída antes e depois fica no handoff.
- [ ] Nenhuma versão literal fora do catálogo. Os lockfiles foram regravados pelo Gradle no mesmo commit da mudança de versão.
- [ ] O passo novo da CI falha com o lockfile divergente do catálogo e passa com ele em dia. Verifique numa cópia descartável: rebaixar uma versão no catálogo sem regravar faz o passo falhar.
- [ ] `./gradlew build`, `./gradlew checkOpenApi` e `docker build -f docker/Dockerfile .` verdes; oasdiff sem `ERR` (uma subida de patch não deve mudar o contrato; se mudar, a etapa explica).

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `osv-scanner scan source -r .` → sem High nem Critical, ou com o resto registrado como risco.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Revisão humana:** não

**Notas para o plano:**

- Leia o handoff da E13: a classificação por configuração, os advisories e a convenção dos lockfiles.
- O scanner não vê nada dentro de um worktree em `.claude/worktrees/`. Rode-o numa cópia (`git archive HEAD`) ou com `--no-ignore`.
- Os BOMs entram como `platform(...)` nativo do Gradle, sem o plugin `io.spring.dependency-management`. Por isso as propriedades do BOM do Spring Boot (`tomcat.version`) não valem aqui. Use restrição de versão.
- A revisora confere catálogo, `build.gradle.kts` e lockfiles juntos, e a saída do scanner antes e depois.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Patch do Spring Boot com mudança de comportamento: a suíte inteira (1287 testes) e o `docker build` cobrem; o oasdiff pega mudança de contrato.
- Restrição de versão esquecida depois que o BOM alcança a versão: o comentário diz quando removê-la. O passo de CI **não** mostra essa sobra, porque uma restrição é piso de versão e não faz o lockfile divergir; ele pega, sim, a remoção da restrição (o lockfile volta para a versão vulnerável). Correção feita na execução (revisão de arquitetura, M7).

**Evidências e handoff:**

- **Plano:** `docs/superpowers/plans/2026-11-04-m5-recepcao-fnrh-e14.md`, com duas tarefas, executado pelas souls: `desenvolvedor` → `testador` → verificação mecânica → `revisor-seguranca`. Revisão de arquitetura (`revisor-arquitetura`, modelo mais capaz): aprovado com notas, em 1 rodada. A revisão inicial bloqueou por dois Importantes: o `koverHtmlReport` não provava o freemarker, e a prova de idempotência do `--write-locks` era impossível como estava escrita. Os dois foram corrigidos no plano.
- **Commits** (branch `roadmap/m5-recepcao-fnrh`, a partir de `15bbcdb`):
  - `be152b2` início da etapa e plano; `b09be76` plano corrigido após a revisão de arquitetura; `2eefa28` seção "Revisão de arquitetura" no plano;
  - `fa08f85` Spring Boot 4.1.2, plataforma do Jackson 2 e restrição do freemarker, lockfiles regravados (Task 1);
  - `3f47b39` passo "Lockfiles em dia com o catálogo" na CI, README, comentário do build, cabeçalho do `ci.yml` e ARCHITECTURE §17 (Task 2);
  - `2c72294` comentários das restrições (onda única da revisão final);
  - o commit de fechamento (este handoff).
- **Revisões:**
  - Tarefas: as duas saíram "Approved", com 0 Critical e 0 Important.
  - Revisão final: `revisor-seguranca` no modelo mais capaz, sobre `15bbcdb..3f47b39`, com resultado "With fixes" e sem Critical. O Important era este handoff vazio, com o risco do OpenTelemetry a registrar com precisão; foi resolvido neste fechamento. Os Minors M1 (texto do `because`) e M3 (advisories Medium no comentário do Jackson 2) entraram na onda única `2c72294`, e a re-revisão restrita deu os dois como resolvidos. O M2 (imagem e oasdiff provados só em `fa08f85`) foi refeito pelo orquestrador no HEAD `2c72294`.
  - Verificação mecânica (`check_test_integrity.py --base 15bbcdb`): "OK: nenhum apontamento".
- **Configuração:**
  - `gradle/libs.versions.toml`:
    - `spring-boot = "4.1.2"` (plugin e BOM);
    - `jackson2 = "2.22.4"`, com a lib `jackson2-bom` (`com.fasterxml.jackson:jackson-bom`);
    - `freemarker = "2.3.35"`, com a lib `freemarker`.
    - Cada restrição traz no comentário o motivo, os GHSA e a condição de saída.
  - `build.gradle.kts`:
    - `implementation(platform(libs.jackson2.bom))` junto dos outros BOMs;
    - `buildscript { dependencies { constraints { classpath(libs.freemarker) { because(…) } } } }`: o acessor `libs` compila dentro do `buildscript`.
  - Inventário das restrições:

    | Restrição | Por quê | Sai quando |
    |---|---|---|
    | plataforma do Jackson 2 na 2.22.4 | o springdoc 3.1.1 puxa a 2.22.1, e o BOM do Boot 4.1.2 fica na 2.21.7 | o springdoc ou o BOM do Boot trouxer o Jackson 2 ≥ 2.22.3 (conferir as 7 GHSA no scanner) |
    | freemarker 2.3.35 no classpath dos plugins | vem do kover 0.9.11 (`kover-features-jvm` → `intellij-coverage-reporter`), o último publicado | o kover trouxer o freemarker ≥ 2.3.35 |

  - Arrastado pelo Boot 4.1.2 (conferido contra o POM do BOM pela revisão final):
    - Tomcat 11.0.27, Jackson 3.1.7, Spring Framework 7.0.10, Spring Security 7.1.2, Hibernate 7.4.13;
    - Micrometer 1.17.2, PostgreSQL JDBC 42.7.14, slf4j 2.0.20, byte-buddy 1.18.15;
    - no classpath dos plugins: httpclient5 5.6.5, httpcore5 5.4.4 e jspecify 1.0.1.
    - Os lockfiles não ganharam nem perderam pacote: 415 e 74, antes e depois.
  - CI (`.github/workflows/ci.yml`, job `build`): o passo novo "Lockfiles em dia com o catálogo" fica antes do build completo. Ele roda `./gradlew dependencies --write-locks` e falha com `::error::` se o `git diff --exit-code -- '*.lockfile'` acusar mudança ou se o `git status --porcelain -- '*.lockfile'` não sair vazio. Fica dentro de um `if`, para a mensagem sair mesmo sob `bash -e`.
- **Critérios verificados:**
  - [x] Scanner sem High nem Critical (`osv-scanner -r` em cópias limpas por `git archive`):
    - antes, em `15bbcdb`: "Scanned …/gradle.lockfile file and found 415 packages", "Scanned …/buildscript-gradle.lockfile file and found 74 packages", "Total 11 packages affected by 31 known vulnerabilities (5 Critical, 15 High, 8 Medium, 3 Low, 0 Unknown)";
    - depois, em `3f47b39` (lockfiles iguais aos de `2c72294`): os mesmos 415 + 74 pacotes, "Total 3 packages affected by 5 known vulnerabilities (0 Critical, 0 High, 2 Medium, 3 Low, 0 Unknown)";
    - restam o `io.opentelemetry:opentelemetry-api` 1.62.0 (GHSA-ccrj-gw8r-854m, Medium 5,3, runtime), o `org.apache.commons:commons-lang3` 3.16.0 (GHSA-j288-q9x7-2f5v, Medium 6,5, só no classpath dos plugins) e o `ch.qos.logback:logback-core` 1.3.16 (três Low, só na configuração `ktlint`). Estão em "Decisões para o usuário".
  - [x] Nenhuma versão literal fora do catálogo (a única string de versão no `build.gradle.kts` é a do projeto, `0.0.1-SNAPSHOT`), e os lockfiles foram regravados pelo Gradle no mesmo commit (`fa08f85`).
    - Idempotência: uma segunda `--write-locks` dá `git diff --exit-code -- '*.lockfile'` com exit 0 (testador, num clone).
    - `jackson-annotations` está na `2.22` e os demais `com.fasterxml.jackson.*` na 2.22.4; o `dependencyInsight` mostra "{strictly 2.22.4}".
    - O `buildEnvironment` mostra `org.freemarker:freemarker:2.3.32 -> 2.3.35`, só pelo kover, e o `koverHtmlReport` gera o `index.html`.
  - [x] O passo da CI falha com lockfile divergente e passa com ele em dia. O bloco `run` foi extraído do YAML e rodado com `bash --noprofile --norc -eo pipefail` em clones descartáveis:
    - árvore em dia: exit 0, duas vezes seguidas, sem sujar a árvore;
    - `shedlock = "7.10.0"` no catálogo: exit 1, com diff `7.10.1 → 7.10.0` e o `::error::` impresso;
    - lockfile novo não rastreado: exit 1 (pego pelo `git status`);
    - dependência nova (`commons-pool2`) no catálogo e no build: exit 1;
    - restrição do freemarker removida: exit 1, com diff `2.3.35 → 2.3.32`. Sem o passo, o build estrito **não** falha nesse caso, porque o Gradle aplica a versão travada como `strictly`.
    - O verde no runner do GitHub só existe depois do push: registrar o run quando houver integração.
  - [x] Build, contrato e imagem verdes no HEAD `2c72294`:
    - `./gradlew build`: "BUILD SUCCESSFUL in 7m 38s", 1287 testes em 158 classes, 0 falhas e 0 erros (a base `15bbcdb` também tinha 1287 e 0 falhas);
    - `checkOpenApi` verde, sem mudança em `api/openapi.yaml`;
    - `.github/scripts/check-api-breaking.sh 15bbcdb`: "No changes detected", "Nenhuma mudança incompatível (nível ERR) em /api/v1";
    - `docker build -f docker/Dockerfile .`: exit 0;
    - `./gradlew lint`: verde.
- **Decisões para o usuário** (decididas pela opção recomendada em nome do usuário, delegado ao orquestrador, 2026-11-04, depois de pesar prós e contras; ver "Decisões do lote E14"):
  - (a) `opentelemetry-api` 1.62.0 (Medium 5,3, alcançável sem autenticação pelo cabeçalho `tracestate`, porque a propagação W3C está ligada em toda requisição em `application.yaml`): aceito como risco registrado até o Boot trazer a ≥ 1.67.0. A resolução é obrigatória antes da operação real (M8), e cada revisão final reavalia o caso. Alternativas descartadas por ora: plataforma `opentelemetry-bom` 1.67.0 (subida de minor do OpenTelemetry inteiro, à frente do que o BOM do Boot 4.1.2 escolheu para a ponte do Micrometer; fora do escopo da E14 e sem teste) e limpar o `tracestate` no proxy.
  - (b) `commons-lang3` 3.16.0 (Medium, só no classpath dos plugins) e `logback-core` 1.3.16 (Low, só no `ktlint`): aceitos, porque não são alcançáveis por entrada externa.
  - (c) Jackson 2 na 2.22.4 (último patch da linha), e não na 2.22.3 (o piso que fecha os advisories).
- **Notas para a próxima etapa:**
  - Duas restrições de versão vivem no catálogo (tabela acima). Numa subida futura do Boot ou do kover, confira a condição de saída de cada uma: o passo da CI não acusa restrição que ficou sobrando.
  - Mudança de dependência ou plugin: regrave os lockfiles no mesmo commit. Agora a CI recusa o lockfile defasado.
  - Scanner da revisão final: rode-o fora do worktree (cópia por `git archive` ou `--no-ignore`). Neste ambiente, `osv-scanner -r` seguido do diretório equivale a `scan source -r`.
  - Ainda falta o primeiro run da CI com o passo novo, depois do push (decisão do usuário).

---

## E07 — Configuração, domínios e adaptador Serpro da FNRH — #concluida

**Objetivo:** criar o módulo `fnrh` com a configuração das credenciais cifradas (RF-FNRH-02), o cadastro do CPF solicitante (H5), os domínios oficiais com validação local (RF-FNRH-06, H21) e a porta `FnrhGateway` com o adaptador REST do Serpro (Basic Auth, header `cpf_solicitante`), testado com WireMock a partir da documentação v2.4 (H6), com log de integração redigido e o painel de saúde como métrica e endpoint de status (H22).

**Por que agora:** é a primeira integração externa de saída com credencial do hotel e fixa o padrão de porta + adaptador + log de integração da ARCHITECTURE §12 que M7 e M8 vão repetir (o FCM do M2 já usa WireMock, `FcmTestServer`, e é o modelo de adaptador).

**Dependências:** nenhuma dentro deste roadmap; exige o gate do M4 `#concluida`. Independente das etapas de recepção e da E10 (informativo: mexe em `api/openapi.yaml`, `ModularityTest` e ARCHITECTURE).

**Escopo:** pacote `fnrh` (a criar), `FnrhModule.kt` com `allowedDependencies` = `shared`, `hotel`, `guests` (e `reservations`/`notifications` na E08), o teste dele no `ModularityTest`:

- **Configuração** (`/api/v1/fnrh/settings`, `GET` sem a senha e `PUT` com `If-Match`; `fnrh.settings.manage`, H20): URL base (homologação ou produção; a etapa decide entre a configuração do administrador e uma propriedade `zeny.fnrh.*`), usuário e senha cifrados (`EncryptedStringConverter`), CPF solicitante padrão (cifrado, `Cpf`), ligada ou não; sem configuração, nada é enviado (a E08 deixa as submissões pendentes).
- **CPF por usuário** (`/api/v1/fnrh/requesters`): id do usuário (opaco; o `fnrh` não importa `iam`) → CPF cifrado, mostrado mascarado; a escolha do CPF de um envio (o do usuário, senão o padrão) numa regra do domínio.
- **Domínios oficiais** (motivo de viagem, meio de transporte, tipos de documento etc.): consulta à API, cache local no schema `fnrh` com atualização sob demanda (`POST /api/v1/fnrh/domains/refresh`) e leitura `GET /api/v1/fnrh/domains/{domain}` para o cliente oferecer os códigos no check-in.
- **Validador local** (RF-FNRH-06, H21): documento, código IBGE, nacionalidade e os campos que a API exige, com erros por campo e sem o valor; usado pela E08 antes de cada envio.
- **Porta `FnrhGateway`** (SPEC §8.2.17) em `application`: registrar reserva e hóspedes, check-in, check-out, no-show, cancelamento; adaptador Serpro em `infrastructure` com `RestClient`, timeouts configuráveis, mapeamento de erros (validação, `401`, `5xx`, rede/timeout → tipos distintos) e IDs remotos devolvidos.
- **Log de integração** sem payload sensível: por chamada, operação, situação HTTP, duração, código de erro e mensagem do Serpro saneada; nunca raça/cor, deficiência, número de documento nem a senha (ARCHITECTURE §12).
- **Painel de saúde** (H22): `GET /api/v1/fnrh/status` (último sucesso, última falha, configurada ou não) e a métrica `zeny_fnrh_*` por um `MeterBinder` em `infrastructure`; nenhum `HealthIndicator` na readiness.
- `HotelApi.profile()` (dados cadastrais, Cadastur) se o payload da FNRH exigir algum dado do hotel; senão, fica para a E11.

**Fora de escopo:** disparo por eventos e fila (E08); chamadas reais ao Serpro (E09).

**Critérios de aceite:**

- [ ] Cada operação de `FnrhGateway` tem teste WireMock que confere URL, Basic Auth, header `cpf_solicitante` e corpo, e mapeia o ID remoto da resposta.
- [ ] Erro de validação, `401`, `5xx` e timeout do Serpro viram erros tipados do adaptador (teste).
- [ ] Usuário, senha e CPFs ficam cifrados no banco (teste lê a coluna e não encontra o texto claro); a API de configuração nunca devolve a senha, e o CPF sai mascarado.
- [ ] Validação local rejeita CPF inválido, código IBGE inexistente e nacionalidade fora do domínio, com `errors[]` por campo e sem o valor.
- [ ] O log de integração não contém raça/cor, deficiência, número completo de documento nem a senha (teste que captura a saída).
- [ ] `GET /api/v1/fnrh/status` e a métrica `zeny_fnrh_*` em `/actuator/prometheus`; a readiness não depende da FNRH (`ActuatorExposureTest` intacto).
- [ ] `ModularityTest` com o teste do `fnrh` (sem `iam`); `./gradlew build` verde e oasdiff sem `ERR`.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Revisão humana:** sim

**Notas para o plano:**

- Leia `docs/SPEC.md` §5.9 e §8.2.17, `docs/ARCHITECTURE.md` §12 e §8.3 e as hipóteses H5, H6, H20, H21 e H22.
- A documentação oficial da API FNRH v2.4 não está no repositório (link na SPEC §11): o plano precisa de acesso à web ou do PDF em `docs/` (H6). Levante os endpoints, a autenticação, os headers, os campos obrigatórios e os códigos de erro de reserva, hóspede, check-in, check-out, no-show, cancelamento, pré-check-in e domínios, e registre o levantamento no handoff.
- O modelo de adaptador é o `FcmPushGateway` do `notifications` (porta, adaptador, propriedades, timeouts) com o `FcmTestServer` (WireMock); os stubs WireMock conferem headers e corpo, e ficam nomeados pela versão da API.
- Nota da E02 (B2): conferir na v2.4 se o menor brasileiro só com RG é aceito; se não for, registrar em "Decisões para o usuário".
- Decisões abertas desta etapa (em "Decisões abertas para a entrevista de cada etapa"): as operações e os campos exatos da v2.4 (pré-check-in, reserva antes da chegada) e onde fica a URL base.
- O módulo novo pede uma nota de tema nova na captura de atualização do brain: `ZenyHotelManager - Módulo fnrh`.

**Bloqueios:** Nenhum bloqueio conhecido. Se a documentação v2.4 estiver inacessível à sessão, registre o bloqueio com a ação "usuário fornece o PDF da documentação em `docs/` ou um link acessível; registrar a decisão em H6 e trocar a tag para `#pendente`".

**Riscos e mitigação:**

- API FNRH mudar de versão (ARCHITECTURE §18): adaptador isolado e stubs nomeados pela versão.

**Evidências e handoff:**

- **Plano:** `docs/superpowers/plans/2026-11-04-m5-recepcao-fnrh-e07.md`, com 7 tarefas e as decisões D-E07-1 a 12, executado pelas souls (`desenvolvedor` → `testador` → verificação mecânica → `revisor-seguranca`).
  - **Revisão de arquitetura** (`revisor-arquitetura`, modelo mais capaz): aprovado com notas em 2 rodadas.
    - A revisão inicial bloqueou com 1 Crítico e 8 Importantes: tipos em camadas proibidas, fluxo de registro indefinido, resultado incerto, texto livre em tabela *append-only*, entre outros.
    - A rodada 1 abriu um Importante novo, a autoinvocação no refresh.
  - **Levantamento da API v2.4:** `docs/fnrh/api-fnrh-v2.4.md`, a partir do PDF oficial (60 p., SHA-256 `476849fd…cbf235`, baixado pelo link `@@display-file` do portal). A H6 se confirmou: a documentação basta.
- **Commits** (branch `roadmap/m5-recepcao-fnrh`, a partir de `360592f`):
  - `3558601`, `0185647`, `fa9091b` e `08903c4`: início da etapa, levantamento, plano e as correções da revisão de arquitetura.
  - Task 1 (módulo, permissões, configuração cifrada): `4405108` e `089b175`.
  - Task 2 (API da configuração): `fe551a2`, `da106b5`, `f8d7ea9` (o `username` saiu da trilha) e `6f58f82`.
  - Task 3 (CPF solicitante por usuário): `52ae0b1` e `60438a4`. Foi reaberta em `0316330` e `b6b6d61`, que trocaram o `DELETE` pela desativação.
  - Task 4 (porta e adaptador Serpro): `68911e6`, `55f14ab`, `a7e685d`, `937e7de`, `2e4aaf5`, `0a011c8`, `24399f9`, `45aa6ef` e `514c5cb` (4 rodadas de correção).
  - Task 5 (registro de integração, status e métrica): `2bf1b90` e `47c0797`.
  - Task 6 (domínios com cache): `c3f6b1c`, `4928795`, `40dacfc`, `e0724b3` e `531193e`.
  - Task 7 (mapeamento e validação local): `635b6c1` e `60080b7`.
  - Onda da revisão final: `e8b7303`, `55e5a75`, `0584d47` e `d5614b6`.
  - O commit de fechamento (este handoff, a ARCHITECTURE §12 e o histórico).
- **Revisão final:** `revisor-seguranca` no modelo mais capaz, sobre `360592f..60080b7`, com resultado "With fixes" e nenhum Crítico.
  - **I1:** a troca do CPF solicitante por outro com o mesmo final não deixava rastro na trilha. Corrigido com `@Version` no `FnrhRequester` e a versão no retrato.
  - **I2:** `findReservationByCode` falhava aberto. Agora falha fechado e devolve `UNKNOWN_OUTCOME` quando há ambiguidade.
  - **Minors corrigidos na mesma onda:** caracteres de controle e de formatação Unicode no saneador e nos rótulos, um CHECK no `remote_id`, a regex da senha e os casos 502/503 no `DomainErrorsTest`.
  - **Re-revisão restrita da onda:** "all findings addressed", "Ready to merge? Yes". Ela apontou dois Minor novos, a regex da senha inválida em ECMA-262 e U+2028/U+2029, corrigidos em `d5614b6`.
  - **Revisões por tarefa:** todas fecharam "Approved".
- **Integridade dos testes:** `check_test_integrity.py --base 360592f` aponta `[DEV_TESTE_DO_TESTADOR] 24399f9 SerproFnrhGatewayRound3Test.kt`. Na correção 3 da Task 4, o desenvolvedor reescreveu esse arquivo do testador e apagou 5 testes. O testador os restaurou idênticos em `45aa6ef` (`SerproFnrhGatewayRequestBuildTest.kt`), e as revisões da Task 4 e a final conferiram linha a linha que nenhuma asserção ficou mais fraca. A verificação vai repetir o apontamento enquanto o commit estiver no histórico; este registro é a justificativa.
- **Critérios verificados:**
  - [x] Cada operação de `FnrhGateway` tem teste WireMock que confere URL, Basic Auth, `cpf_solicitante` e corpo, e mapeia o id remoto. O `cpf_solicitante` vai **só** em `registerPerson`, conforme a API v2.4 (D-E07-9). Os testes conferem a presença nessa operação e a ausência nas demais. Os stubs estritos `*_v2_4` estão em `testsupport/FnrhTestServer.kt`.
  - [x] Validação (`400` e outros `4xx`), `401`, `403`, `404`, `429`/`3xx`/`5xx`, timeout de leitura em escrita (`UNKNOWN_OUTCOME`), conexão recusada (`UNAVAILABLE`) e integração sem configuração (`NOT_CONFIGURED`) viram erros tipados, sem causa encadeada (17 status × 8 operações).
  - [x] Usuário, senha, CPF padrão e CPFs por usuário ficam cifrados (os testes leem a coluna). A senha nunca volta, o CPF sai mascarado (`***.***.***-NN`) e a trilha não guarda nem a senha, nem o usuário, nem o CPF em claro.
  - [x] A validação local recusa CPF inválido, município IBGE inexistente (lista oficial com 5.571 municípios, em `src/main/resources/fnrh/ibge-municipios.csv`, com fonte, data e SHA-256) e nacionalidade fora do ISO 3166-1, com erros por campo e sem o valor.
  - [x] Log, registro de integração (`fnrh.integration_call`, *append-only* e sem texto livre), exceção, resposta, status e métricas não trazem raça/cor, deficiência, número de documento, nome, e-mail, telefone nem a senha. O saneador mascara os valores enviados e os padrões.
  - [x] `GET /api/v1/fnrh/status` e as métricas `zeny_fnrh_calls_total`, `zeny_fnrh_last_success_seconds`, `zeny_fnrh_last_failure_seconds` e `zeny_fnrh_configured` aparecem em `/actuator/prometheus`. Nenhum `HealthIndicator` (`ActuatorExposureTest` intacto).
  - [x] `ModularityTest` com o teste do `fnrh` (`shared` e `guests`, sem `iam`).
    - `./gradlew build` em `d5614b6`: BUILD SUCCESSFUL in 6m 37s, 1712 testes, 0 falhas, 0 erros.
    - `.github/scripts/check-api-breaking.sh 360592f`: "Nenhuma mudança incompatível (nível ERR) em /api/v1".
    - Scanner (cópia de `60080b7`, sem mudança de dependência depois): 415 + 74 pacotes, 0 Critical, 0 High, 2 Medium e 3 Low, os mesmos da E14.
- **Endpoints novos:**
  - `GET`/`PUT /api/v1/fnrh/settings`, com `If-Match`;
  - `GET /api/v1/fnrh/requesters`, `PUT /api/v1/fnrh/requesters/{userId}` e `POST /api/v1/fnrh/requesters/{userId}/deactivate`;
  - `GET /api/v1/fnrh/domains/{domain}` e `POST /api/v1/fnrh/domains/refresh`;
  - `GET /api/v1/fnrh/status`.

  Permissões: `fnrh.settings.manage` (só o Administrador), `fnrh.domains.read` e `fnrh.submissions.read` (Gerente e Recepcionista).
- **Kernel (`shared`):** novos `ErrorKind.BAD_GATEWAY` e `SERVICE_UNAVAILABLE`, com `BadGatewayException` e `ServiceUnavailableException` (502/503). O `pattern` global do `Cpf` no contrato passou a aceitar pontuação.
- **Decisões para o usuário.** Todas foram decididas pela opção recomendada em nome do usuário (delegado ao orquestrador, 2026-11-04 e 05); ver "Decisões do lote E07":
  - D-E07-1: o ambiente é escolhido na configuração, com as URLs em propriedade, e trocar de ambiente exige a senha de novo.
  - D-E07-2: registro na **criação da reserva**. Fluxo:
    - `POST /reservas`, sem dado pessoal;
    - no check-in, `POST /pessoas` + `POST /reservas/{id}/hospedes` com `CHECKIN_REALIZADO`;
    - `PATCH` de check-out;
    - cancelamento em `CRIADA`;
    - o no-show fica para o M6.
  - D-E07-3: lista do IBGE versionada no repositório. Risco aceito: um município criado depois do download é recusado até atualizar o arquivo.
  - D-E07-8: sem texto livre no registro de integração.
  - D-E07-9: `cpf_solicitante` só onde a API exige. O roadmap supunha o header em toda chamada.
  - D-E07-10: raça ou gênero não coletados geram `REQUIRED`.
  - D-E07-11: gênero `OUTRO` não pode ser enviado, porque o `guests` não guarda a descrição. A recomendação é acrescentá-la ao perfil numa etapa própria ou na E08.
  - D-E07-12: dependências `shared` e `guests`; a `HotelApi.profile()` fica para a E11.
  - Nota B2 da E02: a API só aceita CPF ou passaporte, então **um menor brasileiro só com RG não é aceito**. O hotel precisa colher o CPF do menor.
  - Desativação no lugar do `DELETE` dos CPFs por usuário, pela convenção do projeto.
  - Limpar a credencial do Serpro (hoje não há como apagá-la) ficou como recurso a decidir.
- **Notas para a próxima etapa (E08):**
  - Não há operação para desfazer o check-in na API.
  - O cancelamento só vale com a reserva em `CRIADA`.
  - Os ids remotos dependem do ambiente.
  - `UNKNOWN_OUTCOME`:
    - se concilia por `findReservationByCode` no registro da reserva e por `GET /reservas/{id}/hospedes` na inclusão do hóspede;
    - no `registerPerson`, falta decidir entre aceitar o risco de pessoa duplicada e conciliar por `GET /pessoas/documento/…`.
  - Toda chamada ao Serpro roda fora de transação do banco.
  - `UNAVAILABLE` é temporário: limite as tentativas (um erro de programação no envio também cai nele). Um 503 dentro de handler offline viraria recusa permanente.
  - O `performedBy` só pesa no `registerPerson`.
  - `minors` sai da data de nascimento.
  - O responsável entra antes do menor.
  - A validação local recebe a residência nula sem erro e não confere o `checkedOutAt`: a E08 decide.
  - O validador, o mapeador e a lista do IBGE ficam em `fnrh/domain`, sem Spring; a E08 os monta.
- **Notas para a E09:**
  - Confirmar se o `POST /reservas/{id}/hospedes` aceita `CHECKIN_REALIZADO` numa reserva `CRIADA`.
  - Confirmar se o filtro `codigo_reserva` casa exatamente; hoje só a página 1 é lida.
  - Confirmar o formato do `400`, o caminho do no-show (`/no-show` ou `/noshow`), a ordem de `dados_hospedes` e o limite de 200 caracteres da senha.
- **Migrações:** `V2026_11_04_1000` a `1030`, alteradas no lugar durante a etapa porque nunca saíram da branch. Um banco local de desenvolvimento que tenha aplicado uma versão anterior delas precisa ser recriado (`docker compose -f docker/compose.yaml down -v`).
- **Revisão humana desta etapa:** pendente. O lote para aqui.

---

## E15 — Descrição de gênero no perfil do hóspede — #concluida

**Objetivo:** guardar no perfil do hóspede a descrição de gênero que a API da FNRH exige quando o gênero é `OUTRO` e levá-la aos dados da FNRH, para que esse hóspede possa ser enviado sem falsear o dado.

**Por que agora:** a E07 deixou a validação local acusando `person.genderDescription`/`REQUIRED` em todo hóspede `OTHER`. Sem o campo, esse hóspede nunca sai da fila quando a E08 ligar o envio, e o registro é obrigação legal. A mudança é pequena e aditiva: melhor antes dos primeiros dados reais do que com um perfil já em uso.

**Dependências:** E07.

**Escopo:** `guests` (perfil, `FnrhGuestData` e contrato da API de hóspedes) e o mapeador do `fnrh`. A descrição é opcional no cadastro; o `guests` a recusa quando o gênero não é `OTHER`.

**Fora de escopo:** tornar o campo obrigatório no cadastro (quem acusa a falta é a validação local da FNRH); outras lacunas do perfil; o caso do menor só com RG, que continua sendo decisão operacional (E07 f).

**Critérios de aceite:**

- [ ] O perfil aceita a descrição com gênero `OTHER` e a recusa com outro gênero, com `code` estável; o contrato traz o campo com tamanho máximo e `pattern` válido em ECMA-262, se houver `pattern`.
- [ ] Hóspede `OTHER` com descrição passa na validação local da FNRH; sem descrição, continua acusando `person.genderDescription`/`REQUIRED`.
- [ ] A chamada ao Serpro no WireMock leva a descrição no campo da API v2.4 (`docs/fnrh/api-fnrh-v2.4.md`).
- [ ] A descrição não aparece em log, retrato da trilha, `toString`, notificação nem view de relatório (teste).
- [ ] A descrição entra na exportação dos dados do titular e sai na anonimização do perfil (teste).
- [ ] `./gradlew build` verde e oasdiff sem `ERR`.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Revisão humana:** não

**Notas para o plano:**

- Decisão do usuário em 2026-11-06, que resolve a E07 (e): `docs/superpowers/specs/2026-11-06-revisao-arquitetura-roadmaps-design.md`, R5.
- Texto livre sobre gênero é dado pessoal mesmo fora da RN-19: siga o tratamento da nota `ZenyHotelManager - Módulo guests` (H10 do M3, retrato sem dado pessoal). Decida no plano, com registro, se o valor fica cifrado.
- O mesmo saneador da E07 cobre o eco desse valor nas mensagens do Serpro, por ser um valor enviado: confira.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Migração em tabela com dado de hóspede: coluna nova e anulável, sem reescrever linhas.

**Evidências e handoff:**

- **Plano:** `docs/superpowers/plans/2026-11-06-m5-recepcao-fnrh-e15.md`, com 6 tarefas e as decisões D-E15-1 a 6, executado pelas souls (`desenvolvedor` → `testador` → verificação mecânica → `revisor-seguranca`).
  - **Revisão de arquitetura** (`revisor-arquitetura`, opus): aprovado com notas em 2 rodadas. A inicial bloqueou com 5 Importantes; a rodada 1, com 2 novos (o `CHECK` com gênero nulo e um 4º arquivo de produção numa tarefa).
  - A linha D-E15-6 do plano ganhou uma "Atualização da execução (Task 5)" depois da aprovação, sem re-revisão: registra a exceção do saneador (M-2 da revisão final).
  - A execução foi interrompida a pedido do usuário em 2026-11-06, com as Tasks 1 a 5 concluídas, e retomada em 2026-11-10 pelo ledger.
- **Commits** (branch `roadmap/m5-recepcao-fnrh`, a partir de `ca9f9e2`):
  - `340846c`: a revisão de arquitetura de 2026-11-06, trazida da árvore da `main`, onde segue sem commit (só documentos).
  - `fea187c`, `fcb70c2`, `724cf9e` e `0518d13`: início da etapa, plano e as correções da revisão de arquitetura.
  - Task 1 (coluna cifrada, regra no agregado, `CHECK` e mesclagem): `cd722ea` e `d8d0abb`.
  - Task 2 (escrita pela API): `6927a9b`, `423059d` e `7165250`.
  - Task 3 (leitura pela API): `43d18ac` e `a354f09`.
  - Task 4 (exportação do titular e anonimização): `b8dd5fd` e `cd8ffad`.
  - Task 5 (dados da FNRH e envio ao Serpro): `b8de5e0`, `3964ec3` e `8d6203a` (correção do saneador).
  - Documentos: `fb5eb58`, `e718190` (ponto de retomada) e o commit de fechamento (este handoff).
  - `5b11231`: tira do Git um relatório do testador commitado por engano em `cd8ffad` (sem segredo nem dado real; fica no histórico).
  - Onda da revisão final: `54d51cd`, `1fe2c77` e `2059a16`.
- **Revisão final:** `revisor-seguranca` (opus) sobre `340846c..5b11231`, com resultado "With fixes" e nenhum Crítico.
  - **I-1:** o contrato, o KDoc e o README prometiam a descrição "só a quem tem `guests.profiles.read`", mas `restrict`, `lift-restriction` e `merge` devolvem o perfil inteiro com a permissão da escrita. Corrigido o texto (`54d51cd` e o README no fechamento), sem mudar comportamento; a lacuna, que é do M3, vai às "Decisões para o usuário" (E15 h).
  - **M-1:** o teste de controle aceitava `201` ou `400`; fixado em `201` (`1fe2c77`). O teste de contrato do testador fixava a promessa falsa da I-1 e foi ajustado à nota verdadeira (`2059a16`).
  - **Re-revisão restrita da onda** (`revisor-seguranca`, sonnet): "all findings addressed", sem quebra nova.
  - As minors do ledger foram triadas: nenhuma bloqueia.
- **Integridade dos testes:** `check_test_integrity.py --base ca9f9e2` → "OK: nenhum apontamento", na revisão final e depois da onda.
- **Critérios verificados:**
  - [x] O perfil aceita a descrição com `OTHER` e a recusa com outro gênero (`400 GENDER_DESCRIPTION_REQUIRES_OTHER_GENDER`, sem o valor); o contrato traz `maxLength: 100`, sem `pattern` (`GuestGenderDescriptionApiTest`).
  - [x] Hóspede `OTHER` com descrição passa na validação local; sem ela, `person.genderDescription`/`REQUIRED` (testes do mapeador e do validador).
  - [x] O WireMock confere `pessoa.GeneroDescricao` no `POST /pessoas` (`SerproFnrhGatewayGenderDescriptionTest`).
  - [x] A descrição não aparece em log, retrato da trilha, `toString`, evento, notificação nem view (`GuestGenderDescriptionPrivacyTest`, `FnrhGenderDescriptionLogTest`). **Exceção literal:** a D-E15-6 (eco de 1 ou 2 caracteres não mascarado no log).
  - [x] A descrição entra na exportação do titular e sai na anonimização, também dos perfis absorvidos da cadeia.
  - [x] `./gradlew build` em `5b11231`: BUILD SUCCESSFUL in 15m 19s, 1809 testes, 0 falhas; depois da onda, em `2059a16`: BUILD SUCCESSFUL in 7m 17s, 1809 testes, 0 falhas. `.github/scripts/check-api-breaking.sh main`: nenhuma mudança `ERR` em `/api/v1`.
  - Scanner (`osv-scanner scan source -r --no-ignore .`, porque o worktree fica sob `.claude/worktrees/`, ignorado pelo `.gitignore` do checkout principal): 415 + 74 pacotes, 0 Critical, 0 High, 2 Medium e 3 Low, os riscos aceitos na E14.
- **Decisões para o usuário** (todas tomadas pela opção recomendada; ver "Decisões do lote E15"):
  - D-E15-4: a descrição volta em toda resposta de perfil, com `guests.profiles.read`, inclusive para o Financeiro.
  - D-E15-6: descrição de 1 ou 2 caracteres ecoada pelo Serpro não é mascarada no log.
  - Exceção ao plano: o saneador passou a normalizar o controle nos valores enviados (`8d6203a`), para todos os campos.
  - E15 (h): as respostas de `restrict`, `lift-restriction` e `merge` do `guests` trazem o perfil inteiro com a permissão da escrita (lacuna do M3).
- **Notas para a próxima etapa:**
  - E08: teste o alerta de falha com um hóspede que tenha descrição, para provar que ela não chega à notificação.
  - E09: confirme o limite real de `GeneroDescricao` (reduzir os 100 caracteres é mudança restritiva de contrato) e se o Serpro ecoa esse campo.
  - E10 e E11: o texto livre do perfil aceita caracteres de controle e de formatação Unicode (bidi, largura zero); a renderização da ficha e dos PDFs precisa tratá-los (M-3 da revisão final).
  - Ondas paralelas: com a sessão num worktree, o harness recusou git de subagente em outro worktree; as tarefas rodaram em sequência.
  - Trailer: `Soul:` e `Co-Authored-By` no mesmo último parágrafo, senão o Git não reconhece o trailer.

---

## E08 — Envio assíncrono e pendências da FNRH — #concluida

**Objetivo:** ligar o `fnrh` aos eventos de `reservations` (`ReservationCreated`, `ReservationChanged`, `ReservationCancelled`, `ReservationReactivated`, `ReservationConfirmed` do M4 e `GuestCheckedIn`, `GuestCheckInUndone`, `GuestCheckedOut` da E01; o no-show fica para o M6, H15) para criar `FnrhSubmission`, enviá-las por um job com retry e backoff, guardar os IDs remotos (RF-FNRH-04), vincular menores ao responsável (RF-FNRH-05), enviar o número da reserva na OTA (RF-FNRH-07), expor a fila de pendências (RF-FNRH-03) e alertar a falha.

**Por que agora:** a FNRH é obrigatória (SPEC §5.9); com os eventos da E01, o check-in da E02 e o adaptador da E07, o envio pode ser ligado.

**Dependências:** E01, E02, E07, E15.

**Escopo:** `fnrh` (`allowedDependencies` ganha `reservations` e `notifications`, já na ARCHITECTURE §3.1):

- **Listeners** `@ApplicationModuleListener` dos eventos acima (os **primeiros consumidores de produção** dos eventos de reserva do M4: o outbox passa a gravar essas publicações); cada um só grava a `FnrhSubmission` idempotente por (reserva, hóspede quando houver, operação, evento) e nada envia.
- **Envio** por um job (`@Scheduled` + `@SchedulerLock`, nome único como `fnrh-submissions-send`, adiado nos testes por uma constante como `FNRH_SEND_DEFERRED` no `@IntegrationTest`), num caso de uso `@Audited` (ator sistema; a trilha registra a submissão enviada, nunca os dados, como pede a nota do M3 E01): lê a reserva pela `ReservationsApi` e os hóspedes pela `GuestsApi.fnrhData` (dados sensíveis só em memória), valida localmente (H21: dado inválido fica `INVALID` com os erros por campo, sem o valor), escolhe o CPF solicitante (H5, pelo `performedBy` dos eventos da E01 ou o padrão), envia pelo adaptador da E07, grava o ID remoto; falha temporária reagenda com backoff exponencial e limite de tentativas; falha permanente fica `FAILED`; **ordem por reserva** (o check-out não sai antes do check-in, nem o check-in antes do registro da reserva).
- **Endpoints:** `GET /api/v1/fnrh/submissions?status=…` (cursor; `fnrh.submissions.read`, sem dado de hóspede além de ids e o código da reserva) e `POST /api/v1/fnrh/submissions/{id}/retry` (`fnrh.submissions.manage`).
- **View** `fnrh.report_submissions` (uma linha por submissão: operação, situação, tentativas, datas, ids opacos; sem `lastError` em texto livre se trouxer valor do hóspede) pela convenção das views `report_*`, para as pendências do fechamento (M6), e a métrica de pendentes no `MeterBinder` da E07.
- **Alerta de falha** (RF-FNRH-03, RF-NOT-01): quando uma submissão vira `FAILED` ou `INVALID`, o próprio caso de uso chama `NotificationsApi.notify` com o tipo `notifications.integration-failure` (obrigatório no painel) para `NotificationRecipients.withPermission(fnrh.submissions.manage)`, com o código da reserva e a operação, **sem** nome nem documento do hóspede.
- **Resultado incerto:** um `UNKNOWN_OUTCOME` da E07 nunca é repetido às cegas; a submissão concilia antes de reenviar.
- **Documentação:** a SPEC §8.2.17 passa a descrever a porta real da E07, no lugar de `registerStay`, `checkIn`, `checkOut`, `noShow` e `cancel`.

**Fora de escopo:** chamadas reais (E09); no-show (M6 E14, H15); expurgo de dados sensíveis (M8 E16).

**Critérios de aceite:**

- [x] Cada evento listado gera exatamente uma `FnrhSubmission`; o mesmo evento reentregue não duplica (teste chamando o listener de novo).
- [x] Envio com sucesso grava o ID remoto da reserva e da pessoa (WireMock).
- [x] Falha temporária reagenda com backoff crescente (`MutableClock`); após o limite, a submissão fica `FAILED` e aparece em `GET /api/v1/fnrh/submissions?status=FAILED`; `POST …/retry` a reenfileira.
- [x] Dado inválido pela validação local fica `INVALID` com os erros por campo, sem chamada ao Serpro.
- [x] Check-out de uma reserva não é enviado antes do check-in dela (teste).
- [x] Com o WireMock devolvendo `503`, um check-in pelo endpoint da E02 conclui com `200`.
- [x] Menor é enviado vinculado ao responsável; reserva de OTA envia o número externo (`BookingSummary.externalId`).
- [x] Nenhuma tabela, view, log, trilha ou notificação do `fnrh` guarda raça/cor, deficiência ou número de documento.
- [x] A view `fnrh.report_submissions` existe só com `SELECT` para o papel da aplicação e a métrica de pendentes aparece em `/actuator/prometheus`.
- [x] Submissão que vira `FAILED` gera uma notificação `notifications.integration-failure` para os usuários com `fnrh.submissions.manage`, sem nome nem documento do hóspede (teste).
- [x] Timeout de leitura numa escrita (WireMock) concilia pelo Serpro antes de repetir e não cria reserva nem hóspede duplicado; a escolha para o `registerPerson` fica registrada no handoff.
- [x] A SPEC §8.2.17 descreve a porta `FnrhGateway` real.
- [x] `./gradlew build` verde e oasdiff sem `ERR`.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Revisão humana:** sim

**Notas para o plano:**

- A revisão humana vem do padrão: esta etapa fixa o envio assíncrono (pedido idempotente, job, ordem, resultado incerto, alerta) que M7 e M8 repetem.
- Hipóteses H4, H5, H15, H16, H21 e H22; leia os handoffs de E01, E02, E03 e E07, inclusive as "Notas para a E08" dele: limite de tentativas também em `UNAVAILABLE`, responsável antes do menor, e a falta de operação para desfazer o check-in na porta. Das notas deles: os dados de check-in vêm da `ReservationsApi.reservation` (`guests[].travelReason`, `transportMode`, `minorAuthorization`); o check-in nunca chama o `fnrh`, e a regra mínima do check-in não substitui a validação local da FNRH (H21); o walk-in publica `ReservationCreated` (e, num plano com cartão, `ReservationConfirmed`) e `GuestCheckedIn` na mesma transação, em ordem, e é tratado como reserva criada e já hospedada.
- Modelo de envio idempotente com registro *append-only*: o `PushDispatchListener` do `notifications`. O listener só grava a submissão; o envio nunca acontece na transação do evento.
- Decisões abertas desta etapa (em "Decisões abertas para a entrevista de cada etapa"): quais eventos geram envio (reserva criada sem documentos completos, alteração, reativação, desfazer check-in) e o limite de tentativas.
- O texto novo vai à nota de tema `ZenyHotelManager - Módulo fnrh` (proposta na E07) e à `ZenyHotelManager - Eventos e jobs` (os listeners novos de produção e o job) pela captura de atualização do brain.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Os listeners novos rodam em todo contexto de teste e seguram o cursor da sincronização offline enquanto a transação deles está aberta: estender o `InventoryListenersQuiescence` (ou equivalente) para esperá-los antes de baixar `/api/v1/sync/changes`, como na M4 E05.
- O evento de no-show só existirá no M6 (H15): registrar no handoff o que a etapa do no-show precisa acrescentar ao `fnrh`.

**Evidências e handoff:**

- **Plano:** `docs/superpowers/plans/2026-11-10-m5-recepcao-fnrh-e08.md`, com 13 tarefas e as decisões D-E08-1 a 17, executado pelas souls (`desenvolvedor` → `testador` → verificação mecânica → `revisor-seguranca`), em sequência no worktree da etapa (sem ondas paralelas, como na E15).
  - **Revisão de arquitetura** (`revisor-arquitetura`, opus): aprovado com notas em 2 rodadas. A inicial bloqueou com 1 Crítico (ordem de chegada dos eventos) e 7 Importantes; a rodada 1, com 1 Importante novo (o `CHECK_IN` que sobrevive ao desfazer). Notas Menores M-a e M-b aplicadas na execução.
  - A fonte da API foi conferida de novo: as respostas de `GET /reservas/{id}/hospedes` e `GET /pessoas/documento/{tipo}/{numero}` entraram em `docs/fnrh/api-fnrh-v2.4.md` (o PDF baixado tem o mesmo SHA-256 do levantamento).
- **Commits** (branch `roadmap/m5-recepcao-fnrh`, a partir de `1d72325`):
  - Plano e revisão de arquitetura: `183e3d8`, `5d2b3ec`, `d8f8c59` e `976e757`.
  - Task 1 (domínio e política de retry): `976e757..e26662b`.
  - Task 2 (migração, portas e persistência): `e26662b..4dfb2e4`.
  - Task 3 (listeners): `4dfb2e4..a4683a7`.
  - Task 4 (conciliação na porta e no adaptador): `a4683a7..a6c3d25`.
  - Task 5 (propriedades e montagem): `a6c3d25..c7922fc`.
  - Task 6 (núcleo do envio e operações da reserva): `c7922fc..d60334e`.
  - Task 7 (check-in): `d60334e..bd79e03`.
  - Task 8 (check-out e desfazer check-in): `bd79e03..efb69dd`.
  - Task 9 (alerta e permissão de gestão): `efb69dd..2b1411d`.
  - Task 10 (job e métrica): `2b1411d..25806d4`.
  - Task 11 (endpoints de pendências, reenvio e encerramento): `25806d4..7770158`.
  - Task 12 (view `fnrh.report_submissions`): `7770158..243f9f4`.
  - Task 13 (SPEC §8.2.17, ARCHITECTURE §12 e §13, README): `68b86cd`.
  - Testador de etapa: `e717e00` e `ffb5318`.
  - Correções do gate: `3ca3602` (enums espelhados em `application`, contrato idêntico), `3c6715b` (`AuthorizationTest` e fixture da varredura) e `8e6b677` (decisão E05 (f) aplicada).
  - Onda da revisão final: `1df90ff`, `4d018cd`, `26e7f42`, `8f04342`, `cf9d8e8` (desenvolvedor) e `e6ac8eb` (testador).
  - Fechamento: o commit deste handoff.
- **Revisão final:** `revisor-seguranca` (opus) sobre `1d72325..8e6b677`, com resultado "With fixes": 0 Críticos, 3 Importantes e 6 Menores. A seção de arquitetura do plano confere com o ledger.
  - **I-1:** um `CHECK_IN` que sobrevivia ao desfazer perdia o hóspede recorrente, que não tinha linha em `remote_guest`, e o desfazer não o enxergava. Corrigido em `1df90ff`: a linha é gravada antes do `POST` do hóspede.
  - **I-2:** o cancelamento sem id remoto não conciliava quando havia escrita em curso na reserva e podia deixar ativa no MTur uma reserva criada por um `POST` com timeout. Corrigido em `4d018cd`.
  - **I-3:** os erros de validação gravados e expostos em `GET /api/v1/fnrh/submissions` diziam `person.race` e `person.disabilityOption`/`disabilityType` (RN-19). Corrigido em `cf9d8e8`: os três viram `person.sensitiveData`/`INVALID_VALUE`, um por hóspede. Duas asserções do testador fixavam o comportamento antigo e foram ajustadas por ele em `e6ac8eb`, com uma checagem negativa a mais (ruling).
  - **M-1** (KDocs), **M-2** (erros repetidos, `distinct`) e **M-5** (`dismiss` sem o instante sem uso) corrigidos em `26e7f42` e `8f04342`. M-3, M-4 e M-6 diferidos (notas abaixo).
  - **Re-revisão restrita da onda** (`revisor-seguranca`, sonnet) sobre `8e6b677..e6ac8eb`: "all findings addressed", sem Crítico nem Importante novo. Duas Menores vão às notas abaixo.
  - As minors do ledger foram triadas: nenhuma bloqueia; as que pedem dono estão nas notas abaixo.
- **Integridade dos testes:** `check_test_integrity.py --base 1d72325` → "OK: nenhum apontamento" (exit 0) na revisão final e depois da onda.
- **Critérios verificados:**
  - [x] Cada um dos 8 eventos gera exatamente uma `FnrhSubmission` (chave `(reservation_id, operation, event_key)`); o mesmo evento reentregue não duplica, inclusive com a submissão já `SENT` (`FnrhSubmissionListenersTest`, `FnrhSubmissionListenersAttackTest`).
  - [x] Envio com sucesso grava o id remoto da reserva, da pessoa e do hóspede (`FnrhReservationSendTest`, `FnrhCheckInSendTest`, WireMock).
  - [x] Falha temporária reagenda com `1 min × 2^(n−1)` até `1 h` (`MutableClock`); na 8ª falha, `FAILED`, listada em `GET /api/v1/fnrh/submissions?status=FAILED`, e `POST …/retry` a reenfileira (`FnrhRetryPolicyTest`, `FnrhReservationSendTest`, `FnrhSubmissionsApiTest` e o ponta a ponta `FnrhStageFailureFlowTest`).
  - [x] Dado inválido pela validação local fica `INVALID` com os erros por hóspede, campo e código, sem valor e sem chamada ao Serpro (`FnrhCheckInSendTest`, `FnrhCheckInAttackTest`).
  - [x] Check-out não sai antes do check-in, nem o check-in antes do registro, com eventos fora de ordem (ordem causal, D-E08-3; `FnrhCheckOutSendTest`, `FnrhReservationSendAttackTest`).
  - [x] Com o WireMock devolvendo `503`, o check-in pelo endpoint da E02 conclui com `200` (`FnrhStageEndToEndTest`).
  - [x] Menor enviado com o `responsavel_id` do responsável, que sai antes; reserva de OTA envia `BookingSummary.externalId` (`FnrhCheckInSendTest`, `FnrhReservationSendTest`).
  - [x] Nenhuma tabela, view, log, trilha ou notificação do `fnrh` guarda raça/cor, deficiência ou número de documento: varreduras em `FnrhCheckInAttackTest`, `FnrhStageFailureFlowTest` (audit e notifications) e `FnrhReportViewsAttackTest`; os nomes dos campos sensíveis também saíram dos erros (I-3).
  - [x] A view `fnrh.report_submissions` existe só com `SELECT` para o papel da aplicação (`FnrhReportViewsTest`), e o gauge `zeny_fnrh_submissions{status}` aparece em `/actuator/prometheus` (`FnrhSubmissionMetricsTest`).
  - [x] Submissão que vira `FAILED` ou `INVALID` gera `notifications.integration-failure` para quem tem `fnrh.submissions.manage`, só com o código da reserva e rótulos fixos; testado com hóspede com descrição de gênero (nota da E15) (`FnrhFailureAlertTest`, `FnrhFailureAlertAttackTest`).
  - [x] Timeout de leitura numa escrita concilia pelo Serpro antes de repetir e não duplica reserva, pessoa nem hóspede (`FnrhReservationSendRecoveryTest`, `FnrhCheckInSendTest`, `FnrhCheckOutSendTest`). **Escolha do `registerPerson`** (D-E08-6): sem `person_id` conhecido no ambiente, **sempre** `GET /pessoas/documento/{tipo}/{numero}` antes de criar; `dados.id` presente → reaproveita; ausente ou `404` → `POST /pessoas`; corpo ambíguo ou incompleto → `UNKNOWN_OUTCOME` (falha fechado).
  - [x] A SPEC §8.2.17 descreve a porta `FnrhGateway` real, a `FnrhSubmission` e os ids remotos (`68b86cd`).
  - [x] `./gradlew build` em `8e6b677`: BUILD SUCCESSFUL in 9m 57s, 2192 testes, 0 falhas; depois da onda, em `e6ac8eb`: BUILD SUCCESSFUL in 12m 18s, 2202 testes, 0 falhas. `.github/scripts/check-api-breaking.sh main`: nenhuma mudança `ERR` em `/api/v1`.
  - Scanner (`osv-scanner scan source -r --no-ignore .`): 415 + 74 pacotes, 0 Critical, 0 High, 2 Medium e 3 Low, os riscos aceitos na E14; nenhuma dependência mudou na etapa.
- **Decisões para o usuário** (todas tomadas pela opção recomendada; ver "Decisões do lote E08"):
  - E08 (a) Eventos: a pré-reserva (`PROVISIONAL`) e a reserva sem documentos também são registradas no MTur; a expiração vira cancelamento.
  - E08 (b) Retry: 8 tentativas, de 1 min a 1 h (cerca de 2 h), também em `UNAVAILABLE`; depois, `FAILED` com alerta e `retry` manual, um por submissão.
  - E08 (c) Desfazer check-in e reativação: sem operação na API; com hóspede já no Serpro, `FAILED` com `NO_REMOTE_OPERATION` (ou `REMOTE_CANCELLED` na reativação) e correção manual no portal, seguida de `dismiss`.
  - E08 (d) Revalidação: cada tentativa do `CHECK_IN` revalida todos os hóspedes, inclusive os já enviados; um perfil editado depois pode deixar a submissão `INVALID`.
  - E08 (e) Erro neutro dos dados sensíveis (I-3): o operador vê `person.sensitiveData` e precisa abrir o perfil para saber qual campo falta.
  - E08 (f) Alerta em transação própria: se o `notifications` falhar, o alerta se perde, mas a submissão segue na fila e na métrica.
  - E08 (g) Trava do job `lockAtMostFor = PT1H`: uma instância que morre com a trava atrasa o envio até 1 h.
  - E08 (h) Menor cujo responsável é outro menor listado depois: `FAILED` (`INTERNAL_ERROR`) com alerta, nunca `responsavel_id` errado.
  - Divergência da SPEC (D-E08-2): uma submissão por evento, não por hóspede; a SPEC §8.2.17 foi atualizada.
  - Fora do escopo, aplicada para o gate: a decisão E05 (f) (`8e6b677`).
- **Revisão humana desta etapa:** feita pelo usuário em 2026-11-11, na entrevista do fim do lote: **padrão de envio assíncrono aprovado** como padrão para M7 e M8 (uma submissão por evento, ordem causal por reserva, escrita em curso e conciliação antes de repetir, 8 tentativas, `FAILED`/`INVALID` segurando a fila até `retry` ou `dismiss`). As decisões (a) a (n) seguem revisáveis uma a uma.
- **Notas para a próxima etapa:**
  - E09 (homologação): confirmar o nome `dados.id` da consulta por documento; o `PUT` numa reserva `EM_ANDAMENTO`; o `POST /pessoas` com documento repetido; se o `GET /reservas` devolve o link de pré-check-in (nulo numa reserva conciliada); se o `PATCH` de check-out repetido é aceito (M-6); se um `404` por rota errada é distinguível de pessoa ausente; os timeouts de 100 ms dos testes do adaptador.
  - E09 e M8 E13: sem configuração, as submissões ficam `PENDING` sem gastar tentativa; ao ligar, todo o acumulado sai no ambiente escolhido, inclusive estadias antigas. Decidir corte ou limpeza antes de ligar em homologação (D-E08-5).
  - E16 ou M8 E13 (M-3): trocar o ambiente com submissões em execução pode gravar id no ambiente errado; ao trocar, drenar ou pausar o job.
  - M-4: as propriedades `zeny.fnrh.submissions.*` não têm validação de limites (zero ou negativo).
  - M6 E14 (no-show): acrescentar a operação na porta, `markNoShow` publicando o evento e o listener gravando `NO_SHOW` com posto próprio na ordem causal; hoje o registro e a atualização de reserva no-show sem id remoto ficam `SENT` sem chamada.
  - M6 (fechamento): as pendências vêm de `fnrh.report_submissions`; `DISMISSED` não segura a fila.
  - `RoomMoved` não gera envio: a API v2.4 não tem UH.
  - A marca de escrita em curso de uma submissão `DISMISSED` fica ligada para sempre: custa um `GET` a mais por registro da reserva.
  - Da re-revisão: a gravação da linha do hóspede recorrente (I-1) limpa a marca de escrita em curso antes do `POST` do hóspede, a mesma janela estreita que o `registerPerson` já tinha; e a lista `SENSITIVE_FIELDS` do `FnrhCheckInStep` repete a do validador: um campo sensível novo no validador precisa entrar nela (constante compartilhada ou teste).
  - Os testes ainda chamam `dismiss(now)` por extensões só de teste (`FnrhSubmissionDismissCompat.kt`); migrar e apagar numa limpeza.
  - O gauge roda um `GROUP BY` por tag a cada raspagem; `FnrhSubmissionJobAttackTest` usa `sleep` fixo (risco de intermitência em CI lenta).
  - Trailer: `Soul:` e `Co-Authored-By` no mesmo último parágrafo.

---

## E16 — Limpar a credencial do Serpro — #concluida

**Objetivo:** permitir ao administrador apagar a credencial do Serpro por uma ação explícita, com trilha, deixando a integração desligada.

**Por que agora:** hoje, ao desligar a integração, a credencial continua cifrada no banco, sem caminho de remoção (decisão E07 i). A correção precisa existir antes de a E09 gravar a primeira credencial real e fixa a regra que os provedores de M7 e M8 seguem.

**Dependências:** E07.

**Escopo:** `fnrh`: ação na API de configuração, nas convenções do projeto (sem `DELETE`, com `If-Match`), sob `fnrh.settings.manage`. Apaga usuário, senha e CPF padrão; o CPF por usuário tem caminho próprio desde a E07.

**Fora de escopo:** os provedores de M7 e M8, que nascem com a mesma ação; rotação de chaves (M8 E15).

**Critérios de aceite:**

- [x] Depois da ação, as colunas cifradas ficam vazias (teste lê as colunas), a integração fica desligada e o `GET /api/v1/fnrh/status` diz não configurada.
- [x] Sem a permissão → `403`; sem `If-Match` → `428`; com ETag velho → `412`.
- [x] A trilha registra a ação e quem a fez, sem usuário, senha nem CPF.
- [x] Depois da ação, nenhuma chamada sai ao Serpro, e religar sem nova credencial dá `422 FNRH_SETTINGS_INCOMPLETE`.
- [x] `ApiSecurityTest` atualizado; `./gradlew build` verde e oasdiff sem `ERR`.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Revisão humana:** não

**Notas para o plano:**

- Decisão do usuário em 2026-11-06, que resolve a E07 (i): `docs/superpowers/specs/2026-11-06-revisao-arquitetura-roadmaps-design.md`, R6.
- Submissões pendentes da E08 ficam na fila com a integração desligada (`NOT_CONFIGURED`), sem perda: confira se a E08 já estiver concluída.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Limpeza acidental em produção: a ação pede `If-Match` e fica na trilha; a recuperação é informar a credencial de novo.

**Evidências e handoff:**

- **Plano:** `docs/superpowers/plans/2026-11-11-m5-recepcao-fnrh-e16.md`, com 3 tarefas (todas `Risco: alto`, em série) e as decisões D-E16-1 a 7, executado pelas souls (`desenvolvedor` → `testador` → verificação mecânica → `revisor-seguranca`). Sem testador de etapa: nenhuma tarefa `Risco: normal`.
  - **Revisão de arquitetura** (`revisor-arquitetura`, opus): aprovado em 2 rodadas. A inicial bloqueou com 2 Importantes (o risco da Task 1 e a forma da ação fora da ARCHITECTURE §12, que M7 e M8 citam) e 5 Menores; a rodada 1, com 1 Importante novo (o atraso do teste concorrente acima do `read-timeout` curto). Tudo aplicado no plano.
- **Commits** (branch `roadmap/m5-recepcao-fnrh`, a partir de `7eac7d0`):
  - Plano e revisão de arquitetura: `3bc4d34`, `11341e2`, `70af24d` e `f366e48`.
  - Task 1 (`FnrhSettings.clearCredentials()`): `f366e48..3ff7bbd` (desenvolvedor `07ab7b0`, testador `3ff7bbd`).
  - Task 2 (ação, trilha, contrato, README e ARCHITECTURE §12): `3ff7bbd..3e9106c` (desenvolvedor `96dbb12` e `6f4e60b`, testador `0412e6e` e `3e9106c`) e a quebra de linha de KDoc `c7281b3`.
  - Task 3 (envio depois da limpeza): `3e9106c..67c721a` (desenvolvedor `f078ce4`, testador `67c721a`).
  - Fechamento: o commit deste handoff.
- **Revisão final:** `revisor-seguranca` (opus) sobre `7eac7d0..67c721a`: "aprovado com ressalvas", 0 Críticos, sem defeito de código; os 2 Importantes eram documentais (fechar o roadmap e registrar a correção da E07 no handoff e na captura), resolvidos neste fechamento. As minors do ledger foram triadas: nenhuma bloqueia (ficam nas notas abaixo). A seção de arquitetura do plano confere com o ledger.
- **Integridade dos testes:** `check_test_integrity.py --base 7eac7d0` → "OK: nenhum apontamento" (exit 0), e o mesmo em cada tarefa.
- **Critérios verificados:**
  - [x] Colunas cifradas vazias (lidas em SQL), integração desligada e `GET /api/v1/fnrh/status` com `configured = false` (`FnrhSettingsClearApiTest`).
  - [x] Gerente e Recepcionista reais → `403`, anônimo → `401`, sem `If-Match` → `428`, `ETag` velho → `412`, sem mudança (`FnrhSettingsClearApiTest`, `FnrhSettingsClearApiAttackTest`, com 11 formas de `If-Match` malformado).
  - [x] Trilha `fnrh.settings.clear-credentials` com o `user_id` do administrador, sem usuário, senha nem CPF; repetir não grava entrada nova (`FnrhSettingsClearApiTest`, `FnrhSettingsClearApiAttackTest`, com segredos unicode na resposta, na trilha e no log).
  - [x] Nenhuma chamada ao Serpro depois da ação: o envio devolve `FnrhSendRun(0, 0, 0, 0)` e a submissão fica `PENDING` com `attempts = 0`; o refresh dos domínios dá `422 FNRH_NOT_CONFIGURED`; a limpeza no meio de uma rodada para antes da chamada seguinte, inclusive entre duas chamadas do mesmo `CHECK_IN` com 2 hóspedes, e ao religar concilia sem duplicar pessoa nem hóspede (`FnrhSettingsClearSendTest`, `FnrhSettingsClearSendAttackTest`, sincronizados por latch no WireMock). Religar sem credencial nova → `422 FNRH_SETTINGS_INCOMPLETE`.
  - [x] `ApiSecurityTest` cobre a rota sem edição (enumera todos os mapeamentos de `/api/**`; D-E16-6). `./gradlew build` em `67c721a`: BUILD SUCCESSFUL in 16m 31s, 2246 testes, 0 falhas. `.github/scripts/check-api-breaking.sh main`: nenhuma mudança `ERR` em `/api/v1` (só a operação `clearFnrhCredentials`).
  - Scanner (`osv-scanner scan source -r --no-ignore .`): 415 + 74 pacotes, 0 Critical, 0 High, 2 Medium e 3 Low, os riscos aceitos na E14; nenhuma dependência mudou.
- **Correção fora do escopo, aplicada (ruling):** `GET /api/v1/fnrh/status` respondia `500` em qualquer ambiente sem chamada bem-sucedida registrada (instalação nova ou a primeira troca para `PRODUCTION`), e a métrica `zeny_fnrh_last_success_seconds` passava pelo mesmo caminho: `JdbcFnrhCallHistory.lastSuccessAt` fazia `.single()` sobre um `max()` nulo. Corrigido em `6f4e60b` (a consulta tem a forma da do `lastFailure`), com o teste de regressão `FnrhCallHistoryEmptyTest`. Defeito da E07.
- **Decisões para o usuário** (todas tomadas pela opção recomendada; ver "Decisões do lote E16"): a forma da ação, que vira regra de toda integração na ARCHITECTURE §12 (D-E16-1 e 7); o que a ação mantém (D-E16-2); a repetição sem erro e sem entrada na trilha (D-E16-3); a correção da E07 no status.
- **Notas para a próxima etapa:**
  - E09: a E16 deixa de travá-la; segue `#bloqueada` só pela Q-12 (credencial e Cadastur).
  - A nota M-3 da E08 (troca de ambiente com envio em curso) continua com a M8 E13: a limpeza mantém o ambiente, mas "limpar e depois `PUT` em outro ambiente" com um envio em curso é o mesmo cenário que o `PUT` já permite.
  - Divergência anterior à etapa: a ARCHITECTURE §12 diz que a credencial nunca volta na API, e o `FnrhSettingsResponse` (E07) devolve o `username` (nulo depois da limpeza).
  - M7 e M8: a ação de cada provedor segue a linha "Credencial" da ARCHITECTURE §12.
  - Limpezas de teste: o `FnrhSettingsClearSendTest` registra um ouvinte do WireMock por execução, sem removê-lo (o `FnrhSettingsClearSendAttackTest` usa um por JVM com `@AfterEach`; unificar); o teste do contrato no `FnrhSettingsClearApiAttackTest` lê o YAML por caminho relativo e nas 30 primeiras linhas; o `FnrhCallHistoryEmptyTest` usa `session_replication_role` (superusuário do Testcontainers); a classe de ataque do envio leva cerca de 75 s.
  - A ação ignora um corpo enviado (200); o contrato não declara `requestBody`. O `If-Match` aparece como `required: false` no contrato, como no `PUT` (o `428` vem do código).

---

## E09 — Homologação da FNRH com o Serpro — #bloqueada

**Objetivo:** executar os envios reais no ambiente de homologação do Serpro (reserva, hóspedes, check-in, check-out e cancelamento; o no-show quando o M6 o publicar), ajustar o adaptador ao comportamento real e registrar o resultado.

**Por que agora:** os stubs da E07 vêm da documentação; só a homologação confirma o contrato real antes da operação do hotel.

**Dependências:** E08, E16.

**Escopo:** configuração das credenciais de homologação pela API de configuração da E07 (ou variável de ambiente, conforme a E07 decidir; nunca no repositório); roteiro de homologação em `docs/` (a criar) com os casos executados e os IDs remotos obtidos; ajustes no adaptador e nos stubs WireMock para refletir o comportamento real.

**Fora de escopo:** credenciais de produção e operação real.

**Critérios de aceite:**

- [ ] Reserva, hóspedes, check-in, check-out e cancelamento executados com sucesso na homologação, com IDs remotos registrados no roteiro (o no-show, se o M6 já o publicar, ou registrado como pendência).
- [ ] Cada divergência real tem correção no adaptador e stub WireMock atualizado.
- [ ] Nenhuma credencial nem dado pessoal real no repositório (busca no diff).
- [ ] `./gradlew build` verde.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- Inspeção manual do diff e do roteiro em busca de credenciais e dados reais.

**Revisão humana:** sim

**Notas para o plano:** antes de planejar, confirme que a D1 foi decidida e que o usuário forneceu as credenciais de homologação fora do repositório; leia os handoffs de E07 e E08. Use só dados de teste: credenciais e dados pessoais reais nunca entram no repositório, nos stubs nem no roteiro.

**Bloqueios:**

- **Causa:** não há credencial de homologação da FNRH nem confirmação de Cadastur ativo.
- **Evidência:** SPEC §10 Q-12 deixa a credencial como "responsabilidade do hotel antes da Fase 1"; nenhuma credencial foi fornecida até a sincronização de 2026-10-22 (a tabela "Questões em aberto que travam marcos" de `docs/roadmaps/README.md` ainda pede a decisão até o início do M5).
- **Impacto:** impede validar o contrato real com o Serpro; o restante do marco segue com WireMock, e o gate do M5 não espera esta etapa (exceção registrada em `docs/roadmaps/README.md`). O gate de inauguração (M8 E13) a exige concluída.
- **Ação de desbloqueio:** o hotel ativa o Cadastur, gera a credencial de homologação no Módulo Meio de Hospedagem e o usuário a disponibiliza fora do repositório (pela API de configuração da E07 ou por variável de ambiente); registrar a decisão em D1 e trocar a tag desta etapa para `#pendente` (índice e título).

**Riscos e mitigação:**

- Ambiente de homologação instável: registrar horários e respostas; repetir antes de concluir que há divergência.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E10 — Agentes, impressoras e fila de impressão — #concluida

**Objetivo:** criar o módulo `printing` com cadastro de agentes de impressão e impressoras, autenticação do agente por token próprio (H23), fila de `PrintJob` com o conteúdo guardado cifrado (H17), entregue por SSE com fallback de long-poll, confirmação pelo agente e reenvio de jobs não confirmados (ARCHITECTURE §14, D13).

**Por que agora:** a ficha de registro, o extrato e a confirmação (E11) e, depois, as comandas de produção do PDV (M8) dependem da fila de impressão.

**Dependências:** nenhuma dentro deste roadmap; exige o gate do M4 `#concluida`. Independente de E01 a E08 (informativo: mexe em `gradle/libs.versions.toml`, se precisar de biblioteca, `SecurityConfiguration`, `ApiSecurityTest` e `api/openapi.yaml`).

**Escopo:** pacote `printing` (a criar), `PrintingModule.kt` com `allowedDependencies` = `shared` (o `hotel` entra na E11), o teste dele no `ModularityTest`:

- `PrintAgent` (nome, último contato, hash SHA-256 do token) e `Printer` (nome, tipo `THERMAL_ESCPOS` ou `A4_PDF`, endereço na LAN, agente) — SPEC §8.2.18; endpoints de administração com `printing.agents.manage` (H20); geração e rotação do token exibido uma única vez; desativação revoga.
- **Autenticação do agente** (H23; é o motivo da parada desta etapa para revisão): token opaco com prefixo próprio numa `SecurityFilterChain` separada, só para `/api/v1/print-agents/me/**`, com o mínimo (receber e confirmar os próprios jobs); um token de agente noutro caminho e um JWT de usuário no caminho do agente são recusados; esquema de segurança próprio no contrato e o `ApiSecurityTest` atualizado (nenhum endpoint vira público).
- `PrintJob` com tipo de documento, impressora, conteúdo renderizado (PDF ou ESC/POS) **cifrado pelo `ContentCipher`** no schema `printing` com teto de tamanho (H17), situação (`PENDING`, `SENT`, `PRINTED`, `FAILED`, `x-extensible-enum` na resposta), tentativas e uma posição crescente; criação pela API pública do módulo (`PrintingApi`, para a E11) e por `POST /api/v1/print-jobs` com conteúdo já renderizado (`printing.jobs.manage`); `GET /api/v1/print-jobs` (`printing.jobs.read`) sem o conteúdo.
- **Entrega ao agente:** `GET /api/v1/print-agents/me/jobs/stream` (SSE próprio do `printing`, com `Last-Event-ID` = a posição do job; na reconexão, os pendentes depois dela, lidos do banco) e `GET /api/v1/print-agents/me/jobs?wait=…` (long-poll); conteúdo do job por `GET /api/v1/print-agents/me/jobs/{id}/content`; `POST /api/v1/print-agents/me/jobs/{id}/ack` com sucesso ou falha.
- Job com ShedLock (adiado nos testes) que reenvia jobs não confirmados após timeout configurável e marca falha após o limite; o conteúdo é apagado depois da confirmação mais um prazo (`zeny.printing.*`).

**Fora de escopo:** renderização de documentos e templates (E11); o software do agente (outro repositório).

**Critérios de aceite:**

- [x] Agente autenticado recebe por SSE e por long-poll só os jobs das próprias impressoras; outro agente não os vê (teste).
- [x] Requisição sem token ou com token revogado → `401`; token de agente fora de `/api/v1/print-agents/me/**` e JWT de usuário dentro dele → recusados (teste).
- [x] `POST /api/v1/print-agents/me/jobs/{id}/ack` muda a situação para impresso ou falho.
- [x] Job sem ack é reenviado após o timeout e marcado como falho após o limite de tentativas (`MutableClock`).
- [x] Reconexão com `Last-Event-ID` entrega os jobs pendentes depois daquela posição.
- [x] O token é guardado só como hash e exibido só na criação/rotação; o conteúdo do job fica cifrado na coluna (teste lê a coluna).
- [x] `printing` depende só de `shared` (`ModularityTest`); `ApiSecurityTest` atualizado sem endpoint público novo; `./gradlew build` verde e oasdiff sem `ERR`.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Revisão humana:** sim

**Notas para o plano:**

- Leia `docs/SPEC.md` §5.22 e §8.2.18, `docs/ARCHITECTURE.md` §8.1, §14 e D13 e as hipóteses H17, H20 e H23.
- Modelos no código: a cadeia e o esquema de segurança de `SecurityConfiguration`, `ApiSecurityTest` e `OpenApiConfiguration`; o SSE do `shared` (`RealtimeHub`, `StreamController`: *virtual threads*, *heartbeat*, encerramento) — o stream do agente é próprio do `printing`, porque o do `shared` exige usuário e sessão de dispositivo.
- O isolamento das duas cadeias se prova testando cada combinação token × caminho (a ordem das cadeias e o `securityMatcher` decidem quem atende).
- Decisões abertas desta etapa (em "Decisões abertas para a entrevista de cada etapa"): teto de tamanho do conteúdo, prazo de guarda e tempo de reenvio do job não confirmado.
- O módulo novo pede uma nota de tema nova na captura de atualização do brain: `ZenyHotelManager - Módulo printing`; o segundo mecanismo de autenticação vai também à `ZenyHotelManager - Segurança, autenticação e autorização`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Conteúdo binário grande na fila: teto de tamanho configurável e expurgo do conteúdo depois da confirmação.
- Duas cadeias de segurança: a ordem das cadeias e o `securityMatcher` decidem quem atende; o teste de cada combinação (token × caminho) prova o isolamento.

**Evidências e handoff:**

- **Plano:** `docs/superpowers/plans/2026-11-11-m5-recepcao-fnrh-e10.md`, com 13 tarefas em série (12 `Risco: alto`; a Task 13, de documentação, `normal`) e as decisões D-E10-1 a 11, executado pelas souls (`desenvolvedor` → `testador` → verificação mecânica → `revisor-seguranca`), mais o testador de etapa.
  - **Revisão de arquitetura** (`revisor-arquitetura`, opus): aprovado com notas em 1 rodada. A inicial bloqueou com 7 Importantes (o `Last-Event-ID` filtrando `PENDING`; o limite de 1 MB do `@Idempotent`; um `410` sem `ErrorKind`; `PENDING` sem prazo; laços sem limite nem desligamento; uma onda com arquivo comum; quem imprime sem ler as impressoras) e 16 Menores, todos aplicados; a rodada 1 aprovou com 4 notas Menores, aplicadas. A revisão apontou também que o fechamento da E16 tinha copiado o handoff dela em E09, E10 e E11; corrigido em `33c15bc`.
- **Commits** (branch `roadmap/m5-recepcao-fnrh`, a partir de `3d25de0`):
  - Plano e revisão de arquitetura: `13f8079`, `33c15bc`, `6ff12ec` e `1eb1a26`.
  - Task 1 (módulo e migração): `1eb1a26..f516667`. Task 2 (tipos e domínio de agente e impressora): `f516667..c1af1c2`. Task 3 (domínio do job e propriedades): `c1af1c2..cbb44d7`.
  - Task 4 (credencial de serviço no `shared`): `cbb44d7..4d620dc`. Task 5 (administração, permissões e persistência): `4d620dc..f4ebb22`. Task 6 (endpoints de administração): `f4ebb22..dd1d2fd`. Task 7 (realm do agente): `dd1d2fd..657c0dc`.
  - Task 8 (fila e `PrintingApi`): `657c0dc..cf761ff`. Task 9 (endpoints da fila): `cf761ff..9c84f99`. Task 10 (long-poll, conteúdo, ack e conexões): `9c84f99..ac0e7dc`. Task 11 (stream SSE): `ac0e7dc..11d9984`. Task 12 (manutenção e métrica): `11d9984..d16fedf` e `3b1cabd`. Task 13 (documentação): `495338b` e `c7c10d9`.
  - Testador de etapa: `e182dbb`. Onda da revisão final: `0a31187` e `538d408`.
  - Fechamento: o commit deste handoff.
- **Revisão final:** `revisor-seguranca` (opus) sobre `3d25de0..e182dbb`: "aprovado com ressalvas", 0 Críticos, 1 Importante (I1: o conteúdo cifrado era carregado na listagem e nas consultas de candidatos da manutenção) e Menores. A onda única (`0a31187`, `538d408`) corrigiu I1 (projeção sem o conteúdo), M1 (o long-poll devolve a reivindicação quando a resposta não é entregue), M2, M3, M4 (`wait` com `minimum`/`maximum`/`default`) e M6; a re-revisão restrita (sonnet) respondeu "all findings addressed". A seção de arquitetura do plano confere com o ledger. As rulings do ledger foram aceitas pela revisora.
- **Integridade dos testes:** `check_test_integrity.py --base 3d25de0` → "OK: nenhum apontamento" (exit 0), também depois da onda. Os apontamentos por tarefa (as asserções `hasSize(10)` trocadas por `12` pelos códigos 404 próprios; um import retirado do teste da métrica) foram julgados justificados.
- **Critérios verificados** (tabela critério → teste no relatório do testador de etapa; ponta a ponta em `PrintingStageEndToEndTest`):
  - [x] SSE e long-poll só com os jobs das próprias impressoras; outro agente não os vê (`PrintAgentStreamTest`, `PrintAgentJobsApiTest`, E2E).
  - [x] Sem token, com token revogado ou rotacionado → `401`; token de agente fora de `/api/v1/print-agents/me/**` e JWT de usuário dentro → `401` (`ServiceCredentialChainTest` e o ataque, `PrintAgentRealmTest` e o ataque, `ApiSecurityTest`).
  - [x] O ack muda para `PRINTED` ou `FAILED` (`PrintAgentJobsApiTest`, E2E).
  - [x] Job sem ack volta à fila depois de 2 min e vira `FAILED`/`ACK_TIMEOUT` na 3ª entrega (`PrintJobsMaintenanceTest` e o ataque, com o `MutableClock`).
  - [x] Reconexão com `Last-Event-ID` reenvia os `SENT` sem ack depois da posição e ainda entrega `PENDING` mais antigos (`PrintAgentStreamTest`).
  - [x] Token só como hash, exibido só na criação e na rotação (`no-store`, sem `Idempotency-Key`); conteúdo cifrado na coluna, lido pelo teste (`PrintAgentsApiTest`, `PrintJobEntityAttackTest`, E2E).
  - [x] `printing` depende só de `shared` (`ModularityTest`, exato); `ApiSecurityTest` cobre cada combinação token × caminho, sem endpoint público novo. `./gradlew build` em `e182dbb`: BUILD SUCCESSFUL in 15m 13s, 2860 testes, 0 falhas; depois da onda, em `538d408`: BUILD SUCCESSFUL in 13m 28s. `.github/scripts/check-api-breaking.sh main`: nenhuma mudança `ERR` em `/api/v1`.
  - Scanner (`osv-scanner scan source -r --no-ignore .`): 415 + 74 pacotes, 0 Critical, 0 High, 2 Medium e 3 Low, os riscos aceitos na E14; nenhuma dependência mudou.
- **Decisões para o usuário** (todas tomadas pela opção recomendada; ver "Decisões do lote E10"; as de arquitetura vão à entrevista da revisão humana): D-E10-1 (porta de credencial de serviço no `shared`); o contrato com o repositório do agente (token `zpa_`, evento SSE, corpo do ack, esquema `printAgentToken`, `200 []` do despejo); um stream por agente; valores de reenvio, validade, teto e guarda; sem alerta de falha; o contato do agente sem trilha.
- **Revisão humana desta etapa:** feita pelo usuário em 2026-11-11, na entrevista do fim do lote, que aprovou as quatro decisões de arquitetura: a credencial de serviço como porta no `shared` (D-E10-1, padrão para M7 e M8); o contrato com o repositório do agente como está (token `zpa_`, `printAgentToken`, evento SSE `job` com `id` = posição, `200 []`, ack `{result, failureCode}`, deduplicação pelo id); um stream por agente, com a contrapressão por ack para depois; e nenhum alerta de falha de impressão por ora (o alerta pode vir pelo Prometheus/Alertmanager da M8 E18, sem aresta `printing` → `notifications`).
- **Notas para a próxima etapa:**
  - E11: a `PrintingApi.enqueue(NewPrintJob(printerId, documentType, content))` aceita até 5 MiB, sem conferir permissão (quem chama confere a própria); `documentType` no padrão `<módulo>.<documento>`; o cliente escolhe a impressora por `GET /api/v1/printers` (`printing.jobs.read`). O `printing` ainda só depende de `shared`: a E11 acrescenta `hotel` (e `files`, H18).
  - E11 (desempenho): `claimPending` e `unackedSent` ainda carregam a entidade com o conteúdo cifrado; com PDFs reais, considere separar o conteúdo numa tabela própria lida só pela entrega do conteúdo e pelo expurgo.
  - O resíduo da conexão meio aberta: com o agente fora do ar sem FIN, um job pode virar `FAILED`/`ACK_TIMEOUT` em cerca de 6 min; a contrapressão por ack ficou para depois (sem alerta, a falha só aparece na fila e na métrica).
  - O registro de conexões (um stream por agente, limite de long-polls) vale por instância.
  - Limpezas: `PrintingProperties` em `infrastructure` usado pela `application`; `PrintAgent.touch` e `tokenGeneration` no retrato da trilha redundantes; a coerção global do Jackson (número para enum ou texto) é anterior à etapa e merece um item próprio; testes de ataque grandes, alguns com asserções frouxas (lista no ledger da etapa, encerrado).
  - O agente (outro repositório) deduplica pelo id do job e confirma de novo sem imprimir; trata `200 []` sem repetir em laço apertado.

---

## E11 — Documentos: ficha, extrato e confirmação — #concluida

**Objetivo:** gerar a ficha de registro, o extrato de conta (RF-CTA-12) e a confirmação de reserva (RF-RES-12, com os textos da RN-11) em PDF A4 e em ESC/POS, para download (RF-NOT-04) e para impressão pela fila da E10 (RF-NOT-03), no modelo dono + `printing` (D2).

**Por que agora:** a RF-REC-01 prevê imprimir a ficha no check-in, e a recepção precisa entregar extrato e confirmação na Fase 1.

**Dependências:** E02, E10.

**Escopo:**

- **`printing`:** API pública de renderização e impressão — `render(template, model, format)` devolve o PDF ou o ESC/POS, e `print(template, model, printerId)` cria o `PrintJob` no formato da impressora. Os templates são registrados pelos módulos donos (cada um versiona os seus no próprio pacote de recursos) e o `printing` não conhece os modelos de dados de negócio. O cabeçalho do hotel (razão social, CNPJ, Cadastur, endereço, logotipo) vem da `HotelApi.profile()` (acrescentar, H18) e o logotipo da `FilesApi` (leitura do conteúdo pelo servidor, acrescentar, H18; aresta `printing` → `files` na ARCHITECTURE §3.1 e no `ModularityTest`). Bibliotecas no catálogo (H8).
- **Módulos donos**, cada um montando os dados, checando a própria permissão e chamando o `printing` (arestas `frontdesk`, `billing` e `reservations` → `printing` já na tabela §3.1; atualizar o `ModularityTest` de cada um):
  - `frontdesk`: ficha de registro (estadia, hóspedes com nome, nacionalidade, data de nascimento, endereço se houver e o documento **mascarado** pela leitura da H11, motivo de viagem; nada de RN-19), com endpoint de PDF e `POST …/registration-card/print`;
  - `billing`: extrato em PDF a partir do extrato JSON existente (`GET /api/v1/folios/{id}/statement`, a prévia `GUEST` para o hóspede), com endpoint de PDF e de impressão e a permissão `billing.folios.read`;
  - `reservations`: confirmação com as políticas copiadas na reserva e os textos e horários da `HotelApi.policy()` (RN-11), com endpoint de PDF e de impressão e a permissão `reservations.reservations.read`.
- Os caminhos exatos (rota própria ou `Accept: application/pdf`) seguem a convenção de API (nota "ZenyHotelManager - Convenções de API" do brain); a etapa decide e registra no handoff (o contrato precisa descrever a resposta binária).

**Fora de escopo:** comandas de produção e cupons do PDV (M8); relatórios do fechamento (M6, em `reporting`); porta em `shared` para documentos (descartada por D2); envio por e-mail.

**Critérios de aceite:**

- [x] Ficha, extrato e confirmação saem em PDF pelos endpoints dos módulos donos; teste extrai o texto e encontra os campos-chave (nome do hóspede, UH, datas, saldo, políticas e horários da RN-11).
- [x] O endpoint de impressão de cada documento cria `PrintJob` na impressora escolhida com conteúdo ESC/POS ou PDF conforme o tipo da impressora.
- [x] Usuário sem a permissão do módulo dono → `403`.
- [x] Nenhum documento contém raça/cor, deficiência nem número inteiro de documento (a ficha mostra o mascarado; teste).
- [x] Dados do hotel nos documentos vêm da `HotelApi.profile()` e o logotipo da `FilesApi`, não de texto fixo (teste muda os dados pela API e os vê no PDF).
- [x] `ModularityTest` passa; `printing` depende só de `shared`, `hotel` e `files` e não tem listener de eventos de outros módulos.
- [x] A tabela da ARCHITECTURE §3.1 inclui `printing` → `files`; a captura de atualização do brain explica como registrar template e imprimir; `./gradlew build` verde e oasdiff sem `ERR`.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Revisão humana:** sim

**Notas para o plano:**

- Leia a decisão D2, as hipóteses H8, H11, H17 e H18, a regra por camadas da `docs/ARCHITECTURE.md` §3.1 e os handoffs de E02 e E10.
- Campos de cada documento: RF-REC-01 (ficha), RF-CTA-12 (extrato), RF-RES-12 e RN-11 (confirmação) e RF-CFG-01/02 (dados do hotel) em `docs/SPEC.md`. O extrato em PDF parte do JSON do `FolioStatementService`; o logotipo e o conteúdo sensível estão descritos nas notas de tema `ZenyHotelManager - Módulos hotel e staff` e `ZenyHotelManager - Módulo files`.
- Nota da E02: a `GuestsApi.identification` já dá os documentos mascarados para a ficha.
- A leitura nova de conteúdo na `FilesApi` não pode servir anexo sem conferir quem chama.
- Testes que mudam os dados do hotel pela API devolvem o original no fim (o banco é compartilhado).
- Decisões abertas desta etapa (em "Decisões abertas para a entrevista de cada etapa"): os caminhos dos PDFs (rota própria ou negociação por `Accept`) e o que a ficha mostra do documento.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Templates espalhados por vários módulos podem divergir no visual: o `printing` oferece o cabeçalho e o rodapé padrão, e os módulos só preenchem o corpo.
- Biblioteca de PDF pesada na imagem: conferir o tamanho e o tempo do `docker build` no handoff.

**Evidências e handoff:**

- **Plano:** `docs/superpowers/plans/2026-11-11-m5-recepcao-fnrh-e11.md`, com 14 tarefas (13 `Risco: alto`; a Task 14, de documentação, `normal`) e as decisões D-E11-1 a 14, executado pelas souls (`desenvolvedor` → `testador` → verificação mecânica → `revisor-seguranca`), mais o testador de etapa.
  - **Revisão de arquitetura** (`revisor-arquitetura`, opus): aprovado com notas em 1 rodada. A inicial bloqueou com 7 Importantes (bean único da `PrintingApi`; a falha do logotipo marcando a transação do dono como *rollback-only*; logotipo grande estourando o teto do job; trava contra injeção em template incompleta; tarefas acima do limite; risco `normal` em contratos; a porta `FolioOwnerDescriber` sem alternativas) e 11 Menores, todos aplicados; a rodada 1 aprovou com 3 notas Menores, aplicadas.
  - **Ondas:** onda 1 (Tasks 1, 2 e 7) e onda 2 (Tasks 3, 4 e 5) em worktrees próprios; depois, por falta de memória da máquina (OOM do kernel), um Gradle por vez: Task 6 e as Tasks 8 a 14 em série.
- **Commits** (branch `roadmap/m5-recepcao-fnrh`, a partir de `7aa2527`):
  - Plano e revisão de arquitetura: `8dd0e0c`, `8dcdab0` e `9f3f101`.
  - Task 1 (bibliotecas e XHTML → PDF): `9f3f101..ca89b8d`. Task 2 (texto, formatação e ESC/POS): `ca89b8d..c3c4239`. Task 7 (logotipo reduzido): `c3c4239..381c274`.
  - Task 3 (`HotelApi.profile()`): `381c274..f150b6e`. Task 4 (`FilesApi.readContent`): `f150b6e..a67b741`. Task 5 (catálogo e motor): `a67b741..7708728`. Task 6 (endereço na identificação): `7708728..857449f`.
  - Task 8 (`render`/`print` na `PrintingApi`): `857449f..4093b60`. Task 9 (extrato, porta e templates): `4093b60..99ab938`. Task 10 (rotas do extrato): `99ab938..e8bd7f0`. Task 11 (ficha): `e8bd7f0..eb704c2` e `b2de404`. Task 12 (descritor da reserva): `eb704c2..4bd3884`. Task 13 (confirmação): `4bd3884..170eba0`. Task 14 (documentação e imagem): `fc4e646` e `8dd8ea5`.
  - Testador de etapa: `5916bd3`. Onda da revisão final: `933b3ee`.
  - Fechamento: o commit deste handoff.
- **Revisão final:** `revisor-seguranca` (opus) sobre `7aa2527..5916bd3`: "aprovado com ressalvas" (With fixes), 0 Críticos; os 2 Importantes eram de fechamento (esta captura e este handoff; a LGPL do openhtmltopdf nas decisões, E11 (a)). A onda única (`933b3ee`) corrigiu os 4 Menores pedidos antes do merge (bloco do `build.gradle.kts`, situação da caução em pt-BR no extrato, comentário do `ModularityTest`, logotipo sem arquivo temporário); a re-revisão restrita respondeu "all findings addressed". A seção de arquitetura do plano confere com o ledger; as rulings da execução foram aceitas.
- **Integridade dos testes:** `check_test_integrity.py --base 7aa2527` → 4 apontamentos, todos justificados nas revisões (2 `DEV_TESTE_DO_TESTADOR` da rodada 1 da Task 5, que endureceu a varredura e restringiu o motor; 1 da Task 8, que fez a varredura falhar sem o layout; 1 `ASSERCAO_REMOVIDA` que é um comentário do `ModularityTest` reescrito).
- **Critérios verificados** (tabela critério → teste no relatório do testador de etapa; ponta a ponta em `DocumentsStageEndToEndTest`, que baixa os 3 PDFs e imprime os 3 documentos numa `A4_PDF` e numa `THERMAL_ESCPOS`, recebidos pelo long-poll do agente):
  - [x] PDFs pelos donos com os campos-chave (`FolioStatementDocumentsApiTest`, `RegistrationCardApiTest`, `ReservationConfirmationApiTest`, E2E).
  - [x] `PrintJob` em PDF ou ESC/POS conforme a impressora (`DocumentRenderingTest`, os testes de API dos donos, E2E).
  - [x] `403` sem a permissão do dono (matriz 3×3 no E2E e os testes de cada dono).
  - [x] Sem raça/cor, deficiência nem documento inteiro (`RegistrationCardAttackTest`, E2E nos PDFs, nos jobs e no ESC/POS).
  - [x] Hotel e logotipo pela API (`DocumentRenderingTest` e E2E mudam os dados pela API e os veem no PDF).
  - [x] `printing` → só `shared`, `hotel`, `files`, sem listener (`ModularityTest` exato, ArchUnit no E2E).
  - [x] §3.1 com `printing` → `files`; `./gradlew build` em `933b3ee`: BUILD SUCCESSFUL in 20m 58s, 3220 testes, 0 falhas; `.github/scripts/check-api-breaking.sh main`: nenhuma mudança `ERR` em `/api/v1`; a captura do brain `atualização m5-recepcao-fnrh E11` explica como registrar template e imprimir.
  - Scanner (`osv-scanner scan source -r --no-ignore .`): 426 + 74 pacotes, 0 Critical, 0 High, 2 Medium e 3 Low (os riscos aceitos na E14); nenhum pacote novo da E11 com achado.
  - Imagem Docker: 171.458.666 B (115 s) → 178.024.307 B (142 s).
- **Decisões para o usuário** (todas tomadas pela opção recomendada; ver "Decisões do lote E11"; as de arquitetura vão à entrevista da revisão humana): D-E11-1 (bibliotecas, LGPL e imagem), D-E11-4 (sem fonte embutida), D-E11-7 (leitura só de anexo comum; logotipo em transação própria), D-E11-9 (rotas, `audience` padrão `GUEST`, impressão em qualquer impressora ativa), D-E11-10 (ficha sob `frontdesk.stays.checkin`, `GET` sem trilha), D-E11-11 (nome social sem o civil; códigos FNRH sem rótulo) e D-E11-12 (porta `FolioOwnerDescriber`).
- **Revisão humana desta etapa:** feita pelo usuário em 2026-11-12, na entrevista do fim do lote, que aprovou pela opção recomendada: a porta `FolioOwnerDescriber` (D-E11-12); o contrato das rotas como está (D-E11-9); as bibliotecas, com o aviso da LGPL a registrar na documentação de implantação (D-E11-1); a leitura de conteúdo só de anexo comum (D-E11-7); a ficha sob `frontdesk.stays.checkin`, com a descrição da permissão a atualizar (D-E11-10); e os rótulos da FNRH na ficha por uma porta do `frontdesk` implementada pelo `fnrh` (D-E11-11), numa etapa de manutenção a criar pelo `arquiteto`.
- **Notas para a próxima etapa:**
  - E12 (gate): o roteiro `curl` pode baixar os três PDFs e imprimir a ficha pelo agente simulado; a `MilestoneM5EndToEndTest` pode reaproveitar o apoio do `DocumentsStageEndToEndTest` (`PdfText`, agente de teste por long-poll).
  - Máquina de 14 GB: rodar um Gradle por vez (correção da E12: nunca `./gradlew --stop`, que derruba daemons de outras sessões; conferir a memória livre e builds de outras sessões); testes que comparam datas exibidas não devem usar a data operacional lida depois (outros testes a avançam).
  - Pendências registradas (menores aceitos pela revisão final): o logotipo sem cache e com uma 2ª conexão; a regra "sem `<style>`" só por convenção; os descritores de teste `EVENT`/`COMPANY`/`WALK_IN` do `FolioStatementDocumentsAttackTest` colidirão com os de produção do M6 ao M8; `STATUS_LABELS.getValue` na confirmação (trocar por `when` exaustivo); a lógica do titular duplicada entre a confirmação e o descritor; o titular anonimizado sai "Hóspede anonimizado" no extrato; o `unbescape` usado direto só como transitivo do Thymeleaf; chaves do modelo sem limpeza; da E10, `claimPending`/`unackedSent` ainda carregam o conteúdo cifrado.

---

## E12 — Gate do marco M5 — #concluida

**Objetivo:** confirmar, a partir de uma cópia limpa do repositório e de um `docker compose` isolado, que a definição global de pronto do M5 é atendida, com um cenário ponta a ponta de recepção, e deixar o roadmap do M6 pronto para ser sincronizado.

**Por que agora:** é o critério de entrada do marco M6.

**Dependências:** E03, E04, E05, E06, E08, E11, E14, E16 (as etapas-folha, que cobrem transitivamente E01, E02, E07, E10, E13 e E15). Não depende de E09: a homologação real depende de terceiro (D1) e fica registrada como pendência obrigatória antes da operação real, sem travar o M6 (exceção em `docs/roadmaps/README.md`); quem a cobra é o gate de inauguração (M8 E13).

**Escopo:**

- Rodar `./gradlew clean build`, `clean test`, `lint` e `checkOpenApi` do zero numa cópia limpa (`git archive` do snapshot, `GRADLE_USER_HOME` novo no scratchpad, `--no-daemon --no-build-cache`), ao menos três rodadas dos testes, e `.github/scripts/check-api-breaking.sh` contra o gate do M4 (`ffae917`).
- Teste de integração ponta a ponta do marco (`MilestoneM5EndToEndTest`, na raiz do pacote de testes, com os modelos de role do boot): reserva → alocação → check-in com menor autorizado e caução → submissão FNRH enviada (WireMock) → ficha em `PrintJob` → troca de UH → lançamento e pagamento manual → extrato → check-out com a caução liberada e as contas encerradas → submissão de check-out; walk-in; desfazer check-in com aprovação; o Serpro fora do ar sem afetar o check-in.
- Roteiro `curl` contra um `docker compose` isolado (`-p` próprio, `--env-file` no scratchpad gerado do `docker/.env.example`, porta própria, profile `prod`, pelo proxy, `down -v` no fim; sem tocar no `docker/.env` do usuário), modelo no handoff da E13 do M4: o mesmo cenário com os modelos de role; as publicações dos eventos de reserva e de hospedagem `COMPLETED` no listener do `fnrh`; a FNRH apontada para um WireMock avulso na rede do projeto, se viável (senão, a fila de pendências mostra a submissão e o envio fica nos testes); um agente simulado por `curl` no SSE do agente recebendo e confirmando o job da ficha; logs do `prod` sem documento, nome de hóspede, credencial da FNRH nem token de agente.
- Conferir cada item da "Definição global de pronto"; registrar o estado da E09.
- Revisar as notas do projeto no brain e as capturas pendentes, publicando a captura de atualização com as correções (APIs públicas, eventos, portas e views do marco) e a seção "API" do `README.md`; registrar a conclusão do M5 no histórico de revisões de `docs/roadmaps/README.md`.

**Fora de escopo:** funcionalidades novas. Problemas encontrados voltam para a etapa de origem (reabrir com `#em-andamento` e registrar no handoff dela), salvo ajustes triviais de documentação.

**Critérios de aceite:**

- [x] Todos os itens da "Definição global de pronto" marcados, cada um com evidência numa cópia limpa e, onde o compose alcança, no roteiro pelo proxy (inclusive as publicações de `ReservationCreated` e `GuestCheckedIn` concluídas pelo listener do `fnrh`).
- [x] O teste ponta a ponta passa usando os módulos reais (só a FNRH em WireMock), nas três rodadas.
- [x] O estado de E09 está registrado: concluída, ou pendência obrigatória antes da operação real, com a ação de desbloqueio.
- [x] Notas do projeto no brain (com as capturas pendentes) e `README.md` coerentes com o código; `docs/roadmaps/README.md` registra a conclusão do M5 no histórico de revisões.

**Validação obrigatória:**

- `./gradlew clean build`, `clean test`, `lint` e `checkOpenApi` numa cópia limpa, sem cache → sucesso; `.github/scripts/check-api-breaking.sh ffae917` → nenhuma mudança `ERR`.
- `docker compose -p` isolado `-f docker/compose.yaml up -d --build` → readiness `UP` e roteiro concluído; `down -v` em seguida.

**Revisão humana:** sim

**Notas para o plano:**

- O modelo do roteiro é o handoff da E13 de `docs/roadmaps/roadmap-m4-tarifas-reservas.md`; leia também os handoffs de E01 a E11. A "cópia limpa" é a do commit da ponta da branch `roadmap/m5-recepcao-fnrh` (o antigo "snapshot").
- Pendência de teste da E03, se a E05 não a cobriu: um id de hóspede mesclado no pedido do walk-in deve ser recusado com rollback.
- O cenário ponta a ponta usa os módulos reais; só a FNRH fica em WireMock. O que o compose não alcança fica com a evidência dos testes da cópia limpa, registrada no handoff.
- Na captura de atualização do brain, além das correções, o estado do marco vai para `ZenyHotelManager - Estado por marco`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O teste ponta a ponta pode ficar lento: mantê-lo único e no contexto de outro teste do marco.
- O que o compose não alcança (envio real à FNRH sem WireMock na rede, prazos que dependem do tempo, reenvio do job de impressão): evidência nos testes da cópia limpa, registrada no handoff, como nos gates anteriores.

**Evidências e handoff:**

- **Plano:** `docs/superpowers/plans/2026-11-12-m5-recepcao-fnrh-e12.md`, com 8 tarefas (Tasks 1 a 6 `Risco: alto`: o `MilestoneM5EndToEndTest` em três partes, a infraestrutura do compose do gate e o roteiro em duas partes; Task 7, o `README.md`, e Task 8, a auditoria só de leitura do brain contra o código, `normal`), executado pelas souls (`desenvolvedor` → `testador` → verificação mecânica → `revisor-seguranca`), mais o testador de etapa e a verificação do gate pelo orquestrador.
  - **Revisão de arquitetura** (`revisor-arquitetura`, opus): aprovado com notas em 1 rodada. A inicial bloqueou com 8 Importantes (permissões só do Administrador dadas ao Gerente; um teste inexistente e o avanço da data operacional; mapeamentos estáticos do WireMock que não casariam; early/late e extensão/redução sem tarefa; lockfiles sem evidência; o critério do brain sem passo executável; segredos no roteiro e varredura mais fraca que a do M4; risco e tamanho das tarefas) e 11 Menores, todos aplicados; a rodada 1 aprovou com 3 Menores, aplicados.
  - **Execução:** em série, por memória (14 GB, um Gradle ou `docker build` por vez), salvo a Task 8 (só leitura) junto da Task 1 e as Tasks 2, 3 e 7 junto dos testadores e do roteiro (arquivos disjuntos).
- **Commits** (branch `roadmap/m5-recepcao-fnrh`, a partir de `6c87b04`):
  - Início, plano e revisão de arquitetura: `2860f61`, `b0739bf`, `9bcadd2` e `3a9542c`; restrição do `--stop` corrigida no plano: `0b45fe6`.
  - Task 1 (preparo, recusas, check-in com early e FNRH): `3a9542c..cec01a2` (`b070cfa`, testador `cec01a2`). Task 2 (ficha, troca, extensão e redução, extrato, check-out com late e privacidade): `8f6cf0b`, testador `986d253`. Task 3 (walk-in, desfazer e Serpro fora do ar): `e5d8e7c`, testador `f909e8c`.
  - Task 4 (compose do gate e WireMock da FNRH): `721c48b` e `db4d44c`. Task 5 (roteiro, §1 a §8): `a4e1e8e`, `60ba321` e a rodada de correção `0955c87`. Task 6 (roteiro, §9 a §13, e `run-gate.sh`): `21cc994` e `6c16557`. Task 7 (`README.md`): `00eca76`. Task 8: sem commit.
  - Ajustes triviais de documentação do orquestrador: `93c0364` (ARCHITECTURE §14, dependências do `printing`) e `73b7bcc` (`README.md`, CPF solicitante).
  - Fechamento: o commit deste handoff.
- **Revisão final:** `revisor-seguranca` (opus) sobre `6c87b04..73b7bcc`: "aprovado com ressalvas", pronto para merge, 0 Críticos. Os 2 Importantes eram de fechamento e foram cumpridos aqui: a recomendação do `./gradlew --stop` (nota da E11 corrigida acima e substituída na captura do brain) e a captura acrescentando o `docker/gate/` e o `MilestoneM5EndToEndTest` às notas de tema. Nenhum Menor do ledger precisou de correção antes do merge; sem onda de correção. A seção de arquitetura do plano confere com o ledger.
- **Integridade dos testes:** `check_test_integrity.py --base 6c87b04` → 2 apontamentos `DEV_TESTE_DO_TESTADOR` (`8f6cf0b`, `e5d8e7c`: o desenvolvedor editou o `MilestoneM5EndToEndTest.kt`, que tem commits do testador), julgados justificados pela revisão final (só KDoc, assinaturas de apoio e um filtro ampliado; nenhuma asserção removida). Por tarefa, todas "OK: nenhum apontamento". Cada teste novo foi provado por mutação de produção temporária (18 mutações; 17 falharam no ponto previsto e uma era equivalente, a M3 da aprovação de uso único, coberta pela variante M3b).
- **Critérios verificados:**
  - [x] Definição global de pronto marcada item a item com a evidência (acima, no topo deste roadmap).
  - [x] Cópia limpa (`git archive 6c16557`, `GRADLE_USER_HOME` novo, `--no-daemon --no-build-cache`; de `6c16557` a `73b7bcc` só documentação): `clean build` BUILD SUCCESSFUL in 17m 48s, 3228 testes em 330 classes, 0 falhas, 0 erros, 0 pulados; `clean test` 14m 41s e 14m 38s, 3228/0 cada, `MilestoneM5EndToEndTest` 8/8 nas três; `lint checkOpenApi` 3m 37s; `dependencies --write-locks` sem diferença nos três lockfiles.
  - [x] `.github/scripts/check-api-breaking.sh ffae917`: "Nenhuma mudança incompatível (nível ERR) em /api/v1"; `api/openapi.yaml` idêntico ao da cópia.
  - [x] Compose isolado a partir da cópia limpa (`docker/gate/run-gate.sh`, projeto `zeny-e12-final`, porta 18114, profile `prod`, pelo proxy, env sorteado no scratchpad, sem tocar o `docker/.env`): readiness `UP` em ~20 s, **302 conferências, 0 falhas**, listener do `fnrh` `COMPLETED` para `ReservationCreated`, `GuestCheckedIn` e `GuestCheckedOut`, envio ao WireMock da FNRH na rede do projeto, agente simulado por SSE recebendo e confirmando a ficha, logs sem documento, nome, credencial da FNRH, token de agente, access token, PIN nem senhas; `down -v` com 0 sobras.
  - [x] Scanner (`osv-scanner scan -r --no-ignore .`): 426 + 74 pacotes, 0 Critical, 0 High, 2 Medium e 3 Low (os aceitos na E14); nenhuma dependência mudou.
  - [x] E09: `#bloqueada` pela Q-12, pendência obrigatória antes da operação real; ação de desbloqueio no "Bloqueios" dela; cobrada pelo M8 E13.
  - [x] `README.md` coerente (Task 7 e testador de etapa: 64 códigos de erro, 25 permissões e 50 rotas dos itens do M5 conferidos no código, nos testes e no contrato); `docs/roadmaps/README.md` com a conclusão do M5; captura do brain `ZenyHotelManager - atualização m5-recepcao-fnrh E12` com as 14 correções da auditoria (G1 a G14), o compose do gate, o teste de gate e o estado do marco.
  - O que o compose não alcança (prazos do reenvio da FNRH e do job de impressão, 8 tentativas até `FAILED`): evidência no `MilestoneM5EndToEndTest` com o `MutableClock` e nos testes das etapas, na cópia limpa.
- **Decisões para o usuário** (tomadas pela opção recomendada; as de arquitetura vão à entrevista da revisão humana):
  - R1: o roteiro e o compose do gate versionados em `docker/gate/` (antes, scratchpad), fora da imagem e da CI.
  - R2: defeito achado pelo gate seria corrigido na própria E12, sem mudar a tag de outra etapa (nenhum apareceu).
  - Imagem `wiremock/wiremock:3.13.2` (por digest) com CVEs de SO segundo scanners de terceiros, sem scanner de imagem local: risco aceito (só no gate, sem porta publicada, rede descartável); a variante `-alpine` fica como alternativa.
  - Os 41 WARN no log do `api` em `prod` (39 do PDFBox, "fallback font", pela D-E11-4; 2 da queda simulada da FNRH) aceitos: sem dado nem segredo.
  - Incidente: um `./gradlew --stop` pedido pelo orquestrador derrubou daemons de outra sessão do usuário (ZenyHotelManager-DesktopFront), talvez interrompendo um build dela; a regra virou "nunca `--stop`" no plano, no README do gate e na captura.
  - **Arquitetura (entrevista):** a ordem interna da camada Hospedagem (`rates` < `reservations` < `frontdesk`) na ARCHITECTURE §3.2 (nota da E03); o `FnrhSettingsResponse.username` devolvido em claro contra a ARCHITECTURE §12 ("credencial nunca devolvida na API"), divergência anterior registrada na E16.
- **Notas para a próxima etapa (marco M6):**
  - Sincronize o roadmap do M6 com o código antes do primeiro lote (regra 2 de `docs/roadmaps/README.md`); a captura E12 lista as APIs, eventos, portas e views do M5.
  - Gate do M6: reaproveite `docker/gate/` (`run-gate.sh`, `gate-env.sh`, `compose.gate.yaml`, o `check`/`redact`/varredura do `m5-roteiro.sh`) e o padrão do `MilestoneM5EndToEndTest` (operadores pelos modelos de role, só as submissões da própria reserva, sem avançar a data operacional; o fechamento do M6 vai precisar de um caminho próprio para mudar a data).
  - Pendências que passam ao M6: a Média da E01 (teste de índices mais fraco nas listas por situação); a descrição de `frontdesk.stays.checkin` sem a ficha (D-E11-10) e os rótulos da FNRH na ficha, na etapa de manutenção que o usuário pediu ao `arquiteto`; `FnrhSubmissionDismissCompat.kt` duplicado em `src/test`.
  - Menores do gate (sem efeito no produto): `run-gate.sh` não propaga falha do `docker ps` em `leftovers`; a varredura não acharia a descrição de gênero se o encoder escapasse não-ASCII; dois gates no mesmo workdir sobrescrevem o `gate.env`; templating sem escape JSON no mock.

---

## Histórico de revisões

- 2026-10-13 — roadmap criado.
- 2026-10-13 — aplicada a regra por camadas da ARCHITECTURE §3.1 e as decisões da entrevista: H7 virou D2 (dono + printing) e E11 foi reescrita sem porta em `shared`, com `printing` → `hotel` para o cabeçalho; E08 ganhou o alerta de falha via `notifications` (RF-FNRH-03, RF-NOT-01); contexto e restrições atualizados (`frontdesk` → `hotel`, `printing`). Recados seguem sem push porque a SPEC não pede.
- 2026-10-22 — sincronizado com o código depois do gate do M4 (commit `ffae917`, depois de `d68e1e3` da E12; CI da E12 verde no run `39723562893`, a do gate em andamento no run `39730633697`). Cabeçalho com o pré-requisito atendido e o aviso "escrito antes do código" trocado pela nota de sincronização. "Contexto confirmado" reescrito com os módulos existentes, a última migração (`V2026_10_22_0427`), os comandos oficiais, as convenções (views `report_*`, `AppendOnlyTables`, `requireOrApproval`, `@Idempotent`, `x-extensible-enum`, códigos de permissão sem hífen, actuator restrito), o apoio de testes, os gates e as APIs públicas reais (`ReservationsApi` sem `assignRoom` nem transições de hospedagem e com eventos sem autor e sem consumidor; `BillingApi` com `balanceOf`/`closeFolio` e sem registro nem liberação de caução, `key` e `source` como `enum` fechado; `RoomsApi` com `isReadyForCheckIn`, `markOccupied` e `markVacant(CHECK_OUT)`; `GuestsApi` sem documentos fora da `fnrhData`; `HotelApi` sem `profile()`; `FilesApi` sem conteúdo; SSE do `shared` só de usuário; sem credencial de serviço), mais as pendências do M4 com a etapa de cada uma e as que passam adiante. Hipóteses: H1 a H4 confirmadas no código, H9 caiu (substituída pela H23), H22 confirmada no código e H19 na documentação; novas H10 a H24 (H5 refeita), as decididas pelo usuário marcadas "provisória" com a opção recomendada (P1 a P12 da sincronização); D1 continua em aberto. Etapas: E01 (dados de check-in e `MinorAuthorization` no `reservations`, RN-24 no domínio, check-out antecipado com a alocação encerrada, `assignRoom` na API pública, alteração do hospedado pelo `PUT`, eventos com `performedBy`; a cotação de early/late saiu para a E02), E02 (módulo `frontdesk` com as arestas `hotel` e `files`, leitura de documentos sem número na `GuestsApi`, caução pela `BillingApi`, cotação de early/late, desfazer com step-up, códigos de permissão sem hífen), E03 (`createBooking` + `assignRoom` + check-in, hóspedes já cadastrados, sem desconto), E04 (late pelo código `ROOM_CHARGE`, `balanceOf`, liberação de caução e `closeFolio`), E05 (troca de UH pelo `frontdesk`, extensão e redução pelo `PUT` da E01, cobrança do early no check-in), E06 (endpoints pela convenção, aresta `frontdesk` → `staff`), E07 (status e métrica no lugar do health, CPF por usuário, domínios para o cliente), E08 (dependência nova da E02; job com ShedLock, ordem, `INVALID`, view `fnrh.report_submissions`, tipo `notifications.integration-failure`; no-show para o M6), E09 (evidência e ação de desbloqueio reconfirmadas; segue `#bloqueada`), E10 (cadeia de segurança e SSE próprios do agente, conteúdo cifrado; revisão humana passou a sim), E11 (`HotelApi.profile()`, leitura de conteúdo na `FilesApi` com `printing` → `files`, extrato PDF a partir do JSON existente, ficha com documento mascarado) e E12 (cópia limpa, oasdiff contra `ffae917`, `MilestoneM5EndToEndTest` e compose isolado com o listener do `fnrh` e um agente simulado); validações trocadas pelos comandos oficiais (`./gradlew build` e o oasdiff). Nenhuma etapa nova, removida ou renumerada; tags inalteradas (E09 `#bloqueada`, as demais `#pendente`).
- 2026-10-22 — decisões da sincronização (P1 a P12) **decididas pela opção recomendada em nome do usuário (delegado ao orquestrador), 2026-10-22**: todas pela opção (a), tirada a marca de provisória das H5, H10 a H18, H21, H23 e H24. A P7 (H14) com ressalva: a E04 passou a lançar, no day use e no check-out no mesmo dia da chegada, a diária congelada e os pacotes `ON_ARRIVAL`/`DAILY` da data antes de conferir o saldo, uma vez só, por uma chave de idempotência opcional nova na `BillingApi.post` (acréscimo mínimo sem quebrar contrato), com teste e a nota para o M6 de usar a mesma chave. Revisão humana da E10 mantida (segundo mecanismo de autenticação). CI do push do gate do M4 verde no run `39730633697` (build, oasdiff e imagem): ressalva do cabeçalho retirada. Tags inalteradas.
- 2026-10-22 — migrado para o formato da skill development-roadmap com superpowers (branch `roadmap/m5-recepcao-fnrh`, protocolo e prompt de execução únicos, etapas sem prompt e estratégia) e para o Second Brain (notas de tema no lugar das seções do CLAUDE.md, captura de atualização por etapa); decisão do usuário.
- 2026-10-23 — lote E04 a E06 executado com o superpowers na branch `roadmap/m5-recepcao-fnrh` (check-out, troca de UH, extensão, redução e early check-in, recados). As três etapas estão `#concluida`, com handoffs preenchidos e as capturas de atualização do brain publicadas. As decisões do lote foram tomadas pela opção recomendada em nome do usuário (ausente, delegado) e registradas em "Hipóteses e decisões em aberto". As demais tags não mudaram.
- 2026-10-27 — protocolo de execução migrado para as souls do Second Brain: a sessão roda como `orquestrador-etapa` (`claude --agent orquestrador-etapa`), e as tarefas passam por `desenvolvedor` → `testador` → verificação mecânica dos testes → `revisor-seguranca`; a captura do brain é publicada pelo `brain-escriba`. Sem fallback para os prompts padrão do superpowers nem para `executing-plans`. Nenhuma etapa, tag, evidência ou handoff mudou.
- 2026-10-27 — nova etapa E13 (travamento de dependências do Gradle para o scanner), decidida pelo usuário (D3) e posicionada antes das pendentes para rodar primeiro; E12 passa a depender também da E13 e a definição global de pronto ganha o item do lockfile.
- 2026-10-28 — lote da E13 executado pelas souls na branch `roadmap/m5-recepcao-fnrh`. A E13 está `#concluida`, com o handoff preenchido e a captura do brain publicada. O lote parou na revisão humana dela e foi integrado na `main` a pedido do usuário, que estava ausente. Nova etapa E14 (correção das vulnerabilidades High/Critical apontadas pelo scanner), decidida pela opção recomendada em nome do usuário. Ela depende da E13 e fica antes das pendentes, para rodar primeiro. A E12 passa a depender da E14 no lugar da E13, que a E14 cobre. A definição global de pronto ganha o item das vulnerabilidades. As decisões do lote estão em "Decisões do lote E13". As demais tags não mudaram.
- 2026-10-29 — protocolo de execução alinhado ao template atual da skill `development-roadmap`: o orquestrador passou de `orquestrador-etapa` para `orquestrador` (`claude --agent orquestrador`), entrou o papel "Revisor de plano" (`revisor-arquitetura`, pré-condição das souls e passo 1 do prompt) e o passo "Plano" diz que o plano passa pela revisão de arquitetura do orquestrador antes de executar. Só "Como usar", "Protocolo de execução" e "Prompt de execução" mudaram; etapas, handoffs e entradas anteriores do histórico seguem como estavam.
- 2026-11-04 — E14 executada pelas souls na branch `roadmap/m5-recepcao-fnrh` e `#concluida`: Spring Boot 4.1.2, plataforma do Jackson 2 na 2.22.4, restrição do freemarker 2.3.35 no classpath dos plugins, lockfiles regravados e o passo "Lockfiles em dia com o catálogo" na CI. O scanner passou de 5 Critical e 15 High a 0 e 0, e os resíduos Medium/Low ficaram em "Decisões do lote E14". O risco do item "Restrição de versão esquecida" foi corrigido: o passo de CI não acusa a sobra. Handoff preenchido. As demais tags não mudaram.
- 2026-11-05 — E07 executada pelas souls na branch `roadmap/m5-recepcao-fnrh` e `#concluida`: módulo `fnrh` com configuração cifrada, CPF solicitante por usuário, porta `FnrhGateway` com o adaptador Serpro v2.4 (levantamento em `docs/fnrh/api-fnrh-v2.4.md`), registro de integração sem dado pessoal, status e métricas, domínios com cache e validação local com a lista do IBGE. Handoff preenchido, decisões em "Decisões do lote E07" e ARCHITECTURE §12 corrigida (o `cpf_solicitante` vale só nas operações de pessoa e de hospedagem completa). O lote parou na revisão humana da E07. As demais tags não mudaram.
- 2026-11-06 — revisão de arquitetura (`claude --agent arquiteto`; `docs/superpowers/specs/2026-11-06-revisao-arquitetura-roadmaps-design.md`). Protocolo e prompt alinhados ao template de 2026-11-05: merge local na `main` no fim do lote, ondas paralelas, testador por risco, decisões pela opção recomendada, salvo as de arquitetura. Novas E15 (descrição de gênero, antes da E08) e E16 (limpar a credencial, antes da E09), que resolvem as decisões E07 (e) e (i). A E08 passou a depender da E15, com critérios de resultado incerto e da SPEC §8.2.17 e revisão humana, por fixar o padrão de M7 e M8; a E09 passou a depender da E16; a E12 passou a depender da E16. Contexto defasado corrigido. Nenhuma etapa renumerada; etapas concluídas, evidências e tags existentes inalteradas.
- 2026-11-10 — E15 retomada pelo ledger e `#concluida` na branch `roadmap/m5-recepcao-fnrh`: descrição de gênero cifrada no perfil, na API, na exportação, na anonimização e no envio ao Serpro (`GeneroDescricao`). Revisão final "With fixes" (I-1, texto do contrato) corrigida numa onda; "Decisões do lote E15" acrescentadas, com a lacuna de leitura nas respostas das escritas do `guests` (E15 h) para o usuário.
- 2026-11-11 — E08 executada pelas souls na branch `roadmap/m5-recepcao-fnrh` e `#concluida`: `FnrhSubmission` por evento com ordem causal por reserva, job `fnrh-submissions-send` com retry de 8 tentativas, escrita em curso e conciliação antes de repetir, consulta da pessoa por documento antes de criar, ids remotos e link de pré-check-in cifrado por ambiente, pendências (`GET`, `retry`, `dismiss`), alerta de falha, gauge e view `fnrh.report_submissions`. Revisão final "With fixes" (I-1 a I-3) corrigida numa onda; "Decisões do lote E08" acrescentadas e a E05 (f) aplicada. O lote parou na revisão humana da E08, feita pelo usuário no mesmo dia (padrão aprovado; E15 (h) decidida).
- 2026-11-12 — E11 executada pelas souls na branch `roadmap/m5-recepcao-fnrh` e `#concluida`: API de documentos no `printing` (render e print, templates por dono, layout comum, limpeza de texto, sem recurso externo, ESC/POS IBM850 em 48 colunas, logotipo reduzido), `HotelApi.profile()`, `FilesApi.readContent`, extrato em PDF (`billing`, porta `FolioOwnerDescriber`), ficha de registro (`frontdesk`) e confirmação de reserva (`reservations`). Revisão final "With fixes" corrigida numa onda; "Decisões do lote E11" acrescentadas. O lote parou na revisão humana da E11. As demais tags não mudaram.
- 2026-11-12 — revisão humana da E11 feita pelo usuário na entrevista do fim do lote (decisões em "Decisões do lote E11" e no handoff da E11); a etapa de manutenção para os rótulos da FNRH na ficha fica a cargo do `arquiteto`.
- 2026-11-12 — E12 (gate do marco) executada pelas souls na branch `roadmap/m5-recepcao-fnrh` e `#concluida`: `MilestoneM5EndToEndTest` (8 testes, módulos reais, só a FNRH em WireMock) e o compose isolado do gate versionado em `docker/gate/` (`run-gate.sh`, roteiro `curl` com 302 conferências e WireMock da FNRH na rede do projeto). Cópia limpa de `6c16557` com três rodadas (3228 testes, 0 falhas), lint, contrato, lockfiles e oasdiff contra `ffae917` verdes; compose da cópia com 0 falhas; scanner sem High/Critical. "Definição global de pronto" marcada item a item com a evidência; a E09 segue `#bloqueada` como pendência obrigatória antes da operação real. Revisão final "aprovado com ressalvas" (ressalvas de fechamento, cumpridas). Correções de documentação: ARCHITECTURE §14 (dependências do `printing`), `README.md` e a nota do `--stop` no handoff da E11. O lote parou na revisão humana da E12. As demais tags não mudaram.
