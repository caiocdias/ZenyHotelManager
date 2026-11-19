# E22 do M6 — Hora prevista de chegada e prioridade do early check-in: plano de implementação

> **Para agentes:** execução por `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde, não o código.

**Goal:** a reserva guarda a hora prevista de chegada, opcional, em hora local do hotel (H20 b), e a sugestão de distribuição da governança (E03) põe o early check-in e as chegadas primeiro, pela hora prevista (RF-GOV-03, item 2; H17).

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E22", H17, H19 (b e d), H20 (a e b); handoffs da E03 (D3: chegada recalculada na sugestão; `ArrivalsByRoom`) e da E02 (D7: `ReservationsApi.assignedRooms`; D8: `arrivalExpected` é retrato da geração); SPEC RF-GOV-03, RN-13, §8.2.6 (hora prevista de chegada, registrada em 2026-11-18); ARCHITECTURE §3.1, §6.1, §7. Brain: `ZenyHotelManager - Módulo reservations (ciclo de vida, alocação, grupos e busca)`, `ZenyHotelManager - Módulo reservations (inventário, disponibilidade e reservas)`, `ZenyHotelManager - Módulo housekeeping`, `ZenyHotelManager - Convenções de API` e as capturas pendentes da E03, E20 e E21.

**Architecture:** a hora é um atributo opcional da `ReservationSummary` (`expectedArrivalTime: LocalTime? = null`), gravado numa coluna `time` nova em `reservations.reservation`, entrando pelos mesmos caminhos do `specialRequests` (criação pela reserva agrupadora e alteração por substituição da estadia inteira). Mudar só a hora não recalcula noites (a `StayShape` não inclui a hora), mas entra em `ReservationFields.changed`, no retrato da história (`ReservationState`/`StoredState`/`ReservationStateResponse`), no retrato da trilha (`reservationSnapshot`), na exportação do titular e no `ReservationChanged`. A `ReservationsApi` ganha uma consulta sem dado pessoal (`expectedArrivalTimes`, Task 2); o `housekeeping` a lê num método novo do `ArrivalsByRoom` (`arrivalTimesByRoom`), usado só pela sugestão, decide o early contra `HotelApi.policy().checkInTime` na `application` e passa ao algoritmo puro (`AssignmentSuggester`) dois campos novos. Nenhuma aresta nova: `housekeeping` já depende de `reservations` e `hotel`.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1, Flyway 12, Testcontainers.

**Riscos:**
- **parsing de entrada externa não confiável:** `expectedArrivalTime` no corpo da criação e da alteração (formato `LocalTime` estrito da E20, `null` explícito × ausente) (Task 1);
- **dado pessoal ou segredo:** a hora entra na exportação do titular (RF-HOS-07) (Task 1); o dado que a governança recebe do `reservations` não leva dado de hóspede (RN-13) (Task 2);
- **cálculo de valor monetário:** alterar só a hora não pode recalcular diárias nem mexer em inventário ou alocação (Task 1);
- **migração ou escrita destrutiva de dados:** coluna nova anulável na reserva (Task 1).

## Global Constraints

- Grafo §3.1 com o `ModularityTest` em igualdade exata; nenhuma aresta nova (`housekeeping` não depende de `frontdesk` nem de `endofday`).
- Migração nova com timestamp posterior à última do projeto (hoje `housekeeping/V2026_11_19_1600__…`), no diretório `reservations/`; `outOfOrder` desligado; coluna anulável, sem apagar dado.
- Entrada estrita da E20: `LocalTime` só `HH:mm` ou `HH:mm:ss`; fora disso `400 VALIDATION_FAILED` com o campo (`errors[].field = "expectedArrivalTime"` ou com o caminho da reserva dentro da agrupadora). Regra do `required` nos DTOs (o campo é opcional, não entra em `required`).
- Contrato regenerado com `./gradlew updateOpenApi`; `checkOpenApi` e `.github/scripts/check-api-breaking.sh` sem `ERR` (campo opcional novo em request e response).
- RN-13: a consulta nova da `ReservationsApi` devolve só id da reserva e hora; nada de nome, documento, contato ou id de hóspede.
- A governança não escuta eventos de reserva (H19 b): a sugestão lê a hora quando sugere.
- Código em inglês; KDoc, ARCHITECTURE e commits em pt-BR; commits com o trailer `Soul:`.
- Um Gradle por vez; `timeout` em todo comando; sem laço de espera; nunca `./gradlew --stop`.
- Testes: banco compartilhado, só os próprios dados; data operacional corrente sem avanço; tarefas isoladas por UHs, tipos e camareiras próprios (padrão do `AssignmentPlanTestSupport`).
- Verificação da fatia: os testes focados citados mais `timeout 900 ./gradlew checkOpenApi lint`.

## Review Focus

1. **Alteração que repete a estadia e só troca a hora** (inclusive de uma reserva `IN_HOUSE` e de uma do pick-up de bloqueio, que não recalculam noites): grava a hora, não muda total, noites, inventário nem alocação, e não passa pelo desconto. Teste na Task 1.
2. **Alteração sem o campo** (cliente antigo que não conhece a hora): pela semântica de substituição da alteração (como `specialRequests`), a hora é apagada; o KDoc do DTO e a descrição no contrato dizem isso. Teste na Task 1.
3. **Hora com segundos, `"24:00"`, `"7:30"`, número ou string vazia:** `HH:mm:ss` é aceito e devolvido como o Jackson serializa `LocalTime`; os demais dão `400` no campo. Teste na Task 1.
4. **Duas reservas que chegam na mesma UH na mesma data** (day use mais chegada, ou alocação repetida): a UH fica com a menor hora; uma sem hora não apaga a hora da outra. Teste na Task 2.
5. **Hora exatamente igual ao `checkInTime`:** não é early (early é estritamente antes). Teste na Task 2.

## Decisões do plano

- **D1 — nome e tipo.** `expectedArrivalTime` (`LocalTime?` com padrão `null`, hora local do hotel, sem fuso nem data), na `ReservationSummary`, na `NewReservation`, na `ReservationChange`, na `ReservationState`, nos DTOs `ReservationRequest`, `ReservationChangeRequest`, `ReservationResponse` e `ReservationStateResponse`. Coluna `expected_arrival_time time` em `reservations.reservation`. Precisão: o servidor guarda o que recebe até segundos (`HH:mm:ss` aceito pelo formato da E20); frações de segundo são recusadas pela leitura estrita. Alternativa descartada: exigir minuto inteiro como os horários do `hotel` (`TIME_NOT_WHOLE_MINUTE`): a hora prevista é um palpite do hóspede, não uma regra do hotel, e o segundo não atrapalha a ordem nem a comparação com o `checkInTime`. Como a hora e o `checkInTime` já são hora local do hotel, a comparação não usa `HotelApi.timezone()`.
- **D2 — semântica da alteração.** A alteração já substitui a estadia inteira (`specialRequests` nulo apaga). A hora segue igual: ausente ou `null` apaga. Alternativa descartada: "ausente mantém", que exigiria distinguir ausente de `null` só neste campo e quebraria a regra do DTO.
- **D3 — só a hora não recalcula.** A `StayShape` não inclui a hora; a alteração só da hora cai em `restay == null` e só muda o campo. Vale também para a reserva `IN_HOUSE` (a `InHouseChangeRules` compara a `StayShape`) e a do pick-up (que não recalcula). Mudar a hora de uma hospedada é aceito (não tem efeito na governança, que só olha chegadas pendentes).
- **D4 — trilha, história, exportação e evento.** `ReservationFields.changed` acrescenta `"expectedArrivalTime"`. A hora **vai ao retrato**: `ReservationState` e o `StoredState` do `JdbcBookingStore` ganham `expectedArrivalTime: LocalTime? = null` (JSON antigo sem a chave é lido como `null`), o `ReservationStateResponse` ganha o campo opcional (aditivo) e `ReservationsAudit.reservationSnapshot` (`application/RoomTypeInventory.kt`) inclui `"expectedArrivalTime"`. A hora não é sensível (pode ir à trilha), mas é dado da reserva do titular: entra na exportação de dados do titular (`BookingService.personalData`, RF-HOS-07). O `ReservationChanged` é publicado como em qualquer alteração (o `changedFields` traz o nome); o evento **não** ganha campo novo. Consequência aceita: o `fnrh` registra um `UPDATE_RESERVATION`, como já faz numa alteração só de `specialRequests`; o M8 (distribuição de canais) lê a hora pela reserva. O KDoc do `ReservationChanged` passa a citar a hora entre os campos que a alteração muda.
- **D5 — fora desta etapa.** O pick-up e a rooming list de bloqueio (`RoomBlocks`) e o walk-in não recebem a hora (a recepção a informa pela alteração). A view `report_reservations` não muda (E09 decide).
- **D6 — consulta da governança (Task 2).** `ReservationsApi.expectedArrivalTimes(ids: Collection<ReservationId>): Map<ReservationId, LocalTime>`: só as reservas com hora; sem permissão e sem trava, como `assignedRooms`.
- **D7 — chegada por UH.** `ArrivalsByRoom.arrivalsByRoom(date): Set<RoomId>` **não muda** (a geração da E02 e a avulsa seguem iguais, sem consulta a mais, e o `HousekeepingTaskGenerationTest` fica intacto). Um método novo `arrivalTimesByRoom(date): Map<RoomId, LocalTime?>` devolve as mesmas UHs com a menor hora entre as reservas que chegam na UH (ou `null` se nenhuma tem hora), compondo `pendingArrivals`, `assignedRooms` e `expectedArrivalTimes`; só a sugestão o usa.
- **D8 — ordem da sugestão.** `SuggestionTask` ganha `arrivalTime: LocalTime? = null` e `earlyCheckIn: Boolean = false` (este decidido na `application`: `arrivalExpected && arrivalTime != null && arrivalTime < policy().checkInTime`, estritamente antes), com `require(!earlyCheckIn || (arrivalExpected && arrivalTime != null))` no `init`, como o dos créditos. A chegada atrasada (pendente de data anterior, que `ArrivalsByRoom` já conta na data operacional) com hora antes do `checkInTime` também sai early: o hóspede já está atrasado e a UH deve ficar pronta primeiro (decisão aceita; alternativa descartada: ler a data de chegada de cada reserva só para rebaixá-la). A ordem de execução do `AssignmentSuggester` passa a ser: `earlyCheckIn` (desc), `arrivalExpected` (desc), `arrivalTime` (asc, `null` por último; só pesa entre chegadas, porque as demais têm `null`), prioridade (desc), andar, local, id. Os passos 3 e 4 (andares inteiros e rebalanceamento) não mudam. A ordem da lista de tarefas da E02 (cursor) não muda.

## Revisão de arquitetura

- **Veredito:** aprovado com notas, na rodada 1 (`revisor-arquitetura`, modelo opus).
- **Rodada 0:** bloqueado por 4 Importantes: a Task 1 sem a porta e o JDBC da consulta e acima de 12 arquivos (I1); a hora fora do retrato da história e da trilha (I2); a hora fora da exportação do titular, RF-HOS-07 (I3); a mudança de `arrivalsByRoom` quebrando o teste da E02 (I4). Havia também 7 Menores. Corrigido em `ac4d755`, com a ARCHITECTURE §6.1 alinhada.
- **Notas Menores da rodada 1, aplicadas neste texto:** o endpoint da exportação e a ação sensível (N1); o arquivo do `ReservationsAudit` (N2).
- **Para o handoff:** avisar o front desktop de que a alteração precisa reenviar `expectedArrivalTime` (D2); o escopo "hora por UH" virou consulta por id composta com `assignedRooms` no `housekeeping`.

---

### Task 1: Hora prevista de chegada na reserva

**Objetivo:** D1 a D5: coluna, domínio, criação, alteração, resposta, retrato da história e da trilha, exportação do titular e evento, com o contrato regenerado.

**Critérios de aceite:**
- `ReservationExpectedArrivalTimeApiTest` (`@IntegrationTest`, novo, em `src/test/kotlin/com/zeny/hotel/reservations/`):
  - criar uma reserva agrupadora com `expectedArrivalTime: "10:30"` grava e devolve `"10:30:00"` (ou o formato que o mapeador do projeto serializa para `LocalTime`; o teste fixa o real) no `GET` da reserva; sem o campo, a resposta traz `null`;
  - alterar só a hora (mesma estadia) devolve a hora nova, com o mesmo `total`, as mesmas noites e a mesma alocação, sem mudar o inventário da data (conferido pelo endpoint de inventário), sem pedir aprovação de desconto, com uma entrada `CHANGE` na história (`GET` do histórico) que lista `expectedArrivalTime` e cujo `before`/`after` trazem a hora antiga e a nova, uma entrada na trilha cujo retrato traz a hora, e um `ReservationChanged` com `changedFields == ["expectedArrivalTime"]`;
  - alterar mandando `expectedArrivalTime: null`, e alterar sem o campo, apagam a hora (Review Focus 2);
  - alterar só a hora de uma reserva `IN_HOUSE` é aceito e não mexe em noites (Review Focus 1);
  - `"7:30"`, `"24:00"`, `"10:30:00.5"`, `""`, `1030` e `"10h30"` dão `400 VALIDATION_FAILED` com `errors[].field` apontando o campo, na criação (dentro de `reservations[0]`) e na alteração; `"10:30:15"` é aceito (Review Focus 3).
  - a exportação de dados do titular (`POST /api/v1/guests/{id}/export`, que junta as seções pelos contribuidores, entre eles `BookingService.personalData`; ação sensível: o teste usa a permissão ou a aprovação exigida, como os testes existentes da exportação) traz `expectedArrivalTime` na seção da reserva.
  - Verificação: `timeout 900 ./gradlew test --tests '*ReservationExpectedArrivalTimeApiTest'`.
- Os testes existentes de criação, alteração e história continuam verdes sem mudança (o padrão `null` dos campos novos mantém os construtores atuais): `timeout 900 ./gradlew test --tests '*ReservationLifecycleApiTest' --tests '*BookingsApiTest' --tests '*NightChargesTest' --tests '*StayTransitionsDomainTest'`.
- Contrato regenerado; `timeout 900 ./gradlew checkOpenApi lint` verde; `timeout 600 .github/scripts/check-api-breaking.sh` sem `ERR` (saída anexada ao relatório).

**Áreas tocadas:** `reservations/Bookings.kt` (`NewReservation`, `ReservationSummary`), `reservations/ReservationChanges.kt` (`ReservationChange`, `ReservationState`), `reservations/ReservationsEvents.kt` (só KDoc do `ReservationChanged`), `reservations/application/BookingCreation.kt`, `reservations/application/ReservationLifecycle.kt` (`candidate`), `reservations/application/RoomTypeInventory.kt` (`ReservationsAudit.reservationSnapshot` e `ReservationsAudit.state`), `reservations/application/BookingService.kt` (`personalData`), `reservations/domain/ReservationLifecycle.kt` (`ReservationFields`), `reservations/infrastructure/JdbcBookingStore.kt` (insert, update, mapeamento, `StoredState`), `reservations/web/BookingsController.kt` (`ReservationRequest`, `ReservationResponse`), `reservations/web/ReservationLifecycleDtos.kt` (`ReservationChangeRequest`, `ReservationStateResponse`), migração `src/main/resources/db/migration/reservations/V2026_11_19_1700__add_reservation_expected_arrival_time.sql`, `api/openapi.yaml`.

**Tamanho:** ~55 min; 12 arquivos de produção (um só de KDoc), mais a migração e o contrato.

**Depende de:** nenhuma.

**Interfaces (produz):**
- `ReservationSummary.expectedArrivalTime: LocalTime? = null`, `NewReservation.expectedArrivalTime: LocalTime? = null`, `ReservationChange.expectedArrivalTime: LocalTime? = null`, `ReservationState.expectedArrivalTime: LocalTime? = null`; coluna `reservations.reservation.expected_arrival_time`.

### Task 2: Early check-in e chegadas por hora na sugestão

**Objetivo:** D6 a D8: a consulta `expectedArrivalTimes` na `ReservationsApi` (porta e JDBC), `ArrivalsByRoom.arrivalTimesByRoom`, early decidido contra o `checkInTime` da política, ordem nova no `AssignmentSuggester`, KDoc do algoritmo atualizado.

**Critérios de aceite:**
- `ReservationsApiExpectedArrivalTimesTest` (`@IntegrationTest`, novo, em `src/test/kotlin/com/zeny/hotel/reservations/`): `expectedArrivalTimes` devolve só as reservas pedidas que têm hora; id inexistente ou sem hora fica de fora; lista vazia devolve mapa vazio sem consultar. Verificação: `timeout 900 ./gradlew test --tests '*ReservationsApiExpectedArrivalTimesTest'`.
- `AssignmentSuggesterTest` (domínio, `src/test/kotlin/com/zeny/hotel/housekeeping/domain/`, existente, acrescido):
  - `earlyComesBeforeOtherArrivalsWhichComeBeforeOtherTasks`: com uma camareira e as tarefas (early 09:00, chegada 15:00, chegada 13:00, chegada sem hora, saída de prioridade maior, permanência), a ordem de execução é early, 13:00, 15:00, sem hora, saída, permanência;
  - `twoEarlyArrivalsOrderedByTime`: early 10:00 vem antes de early 11:00;
  - `orderIsDeterministicForAnyInputPermutation`: todas as permutações da lista de entrada (ou 50 embaralhamentos com semente fixa) dão a mesma `Suggestion`;
  - `earlyWithoutArrivalIsRejected`: `SuggestionTask` com `earlyCheckIn = true` e `arrivalExpected = false` ou `arrivalTime = null` lança `IllegalArgumentException`;
  - os testes existentes seguem verdes (tarefas sem hora e sem early mantêm a ordem da E03).
  - Verificação: `timeout 600 ./gradlew test --tests '*AssignmentSuggesterTest'`.
- `AssignmentPlanEarlyArrivalTest` (`@IntegrationTest`, novo, com o `AssignmentPlanTestSupport`): reservas próprias alocadas a UHs próprias chegando na data, uma com hora antes do `checkInTime` da política, uma depois, uma sem hora, mais uma tarefa de permanência; `POST /api/v1/assignment-plans/{date}/suggest` devolve a ordem early → chegada por hora → sem hora → demais; uma reserva com hora igual ao `checkInTime` não é early (Review Focus 5); duas reservas chegando na mesma UH (uma sem hora) dão à UH a hora da que tem hora (Review Focus 4, pode ser pela `ArrivalsByRoom` direto). A resposta da sugestão não traz nome, documento, contato, id de hóspede nem de reserva (RN-13). Verificação: `timeout 900 ./gradlew test --tests '*AssignmentPlanEarlyArrivalTest'`.
- Os testes da E02 e da E03 seguem verdes **sem mudança**: `timeout 900 ./gradlew test --tests '*AssignmentPlansApiTest' --tests '*HousekeepingTaskGenerationTest'`.
- `timeout 900 ./gradlew checkOpenApi lint` verde (o contrato não deve mudar nesta fatia).

**Áreas tocadas:** `reservations/ReservationsApi.kt` (`expectedArrivalTimes`, KDoc), `reservations/application/InventoryService.kt` (`ReservationsApiService`, que delega), `reservations/application/ReservationQueries.kt` (método novo na porta de consultas, ao lado de `pendingArrivalIds`), `reservations/infrastructure/JdbcReservationQueries.kt` (a consulta), `housekeeping/application/ArrivalsByRoom.kt` (`arrivalTimesByRoom`), `housekeeping/application/AssignmentPlansService.kt` (`suggestionTasks`), `housekeeping/domain/AssignmentSuggester.kt` (campos, `init` e ordem, KDoc dos passos 2 e 5).

**Tamanho:** ~50 min; 7 arquivos de produção.

**Depende de:** Task 1 (coluna `expected_arrival_time`).

**Interfaces (consome):** coluna `reservations.reservation.expected_arrival_time` (Task 1); `HotelApi.policy().checkInTime: LocalTime`.

**Interfaces (produz):** `ReservationsApi.expectedArrivalTimes(ids: Collection<ReservationId>): Map<ReservationId, LocalTime>` (KDoc: hora local do hotel; só as reservas com hora; sem permissão nem trava; sem dado de hóspede); `ArrivalsByRoom.arrivalTimesByRoom(date: LocalDate): Map<RoomId, LocalTime?>`; `SuggestionTask.arrivalTime: LocalTime? = null` e `SuggestionTask.earlyCheckIn: Boolean = false`.
