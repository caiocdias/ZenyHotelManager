# E22 do M6 — Hora prevista de chegada e prioridade do early check-in: plano de implementação

> **Para agentes:** execução por `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde, não o código.

**Goal:** a reserva guarda a hora prevista de chegada, opcional, em hora local do hotel (H20 b), e a sugestão de distribuição da governança (E03) põe o early check-in e as chegadas primeiro, pela hora prevista (RF-GOV-03, item 2; H17).

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E22", H17, H19 (b e d), H20 (a e b); handoffs da E03 (D3: chegada recalculada na sugestão; `ArrivalsByRoom`) e da E02 (D7: `ReservationsApi.assignedRooms`; D8: `arrivalExpected` é retrato da geração); SPEC RF-GOV-03, RN-13, §8.2.6 (hora prevista de chegada, registrada em 2026-11-18); ARCHITECTURE §3.1, §6.1, §7. Brain: `ZenyHotelManager - Módulo reservations (ciclo de vida, alocação, grupos e busca)`, `ZenyHotelManager - Módulo reservations (inventário, disponibilidade e reservas)`, `ZenyHotelManager - Módulo housekeeping`, `ZenyHotelManager - Convenções de API` e as capturas pendentes da E03, E20 e E21.

**Architecture:** a hora é um atributo opcional da `ReservationSummary` (`expectedArrivalTime: LocalTime?`), gravado numa coluna `time` nova em `reservations.reservation`, entrando pelos mesmos caminhos do `specialRequests` (criação pela reserva agrupadora e alteração por substituição da estadia inteira). Mudar só a hora não recalcula noites (a `StayShape` não inclui a hora), mas entra em `ReservationFields.changed`, na história, na trilha e no `ReservationChanged`. A `ReservationsApi` ganha uma consulta sem dado pessoal (`expectedArrivalTimes`); o `housekeeping` a lê em `ArrivalsByRoom`, decide o early contra `HotelApi.policy().checkInTime` na `application` e passa ao algoritmo puro (`AssignmentSuggester`) dois campos novos. Nenhuma aresta nova: `housekeeping` já depende de `reservations` e `hotel`.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1, Flyway 12, Testcontainers.

**Riscos:**
- **parsing de entrada externa não confiável:** `expectedArrivalTime` no corpo da criação e da alteração (formato `LocalTime` estrito da E20, `null` explícito × ausente) (Task 1);
- **dado pessoal ou segredo:** o dado que a governança recebe do `reservations` (RN-13) (Tasks 1 e 2);
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

- **D1 — nome e tipo.** `expectedArrivalTime` (`LocalTime?`, hora local do hotel, sem fuso nem data), na `ReservationSummary`, na `NewReservation`, na `ReservationChange`, nos DTOs `ReservationRequest`, `ReservationChangeRequest` e `ReservationResponse`. Coluna `expected_arrival_time time` em `reservations.reservation`. Precisão: o servidor guarda o que recebe até segundos; frações de segundo são recusadas pela leitura estrita da E20.
- **D2 — semântica da alteração.** A alteração já substitui a estadia inteira (`specialRequests` nulo apaga). A hora segue igual: ausente ou `null` apaga. Alternativa descartada: "ausente mantém", que exigiria distinguir ausente de `null` só neste campo e quebraria a regra do DTO.
- **D3 — só a hora não recalcula.** A `StayShape` não inclui a hora; a alteração só da hora cai em `restay == null` e só muda o campo. Vale também para a reserva `IN_HOUSE` (a `InHouseChangeRules` compara a `StayShape`) e a do pick-up (que não recalcula). Mudar a hora de uma hospedada é aceito (não tem efeito na governança, que só olha chegadas pendentes).
- **D4 — trilha, história e evento.** `ReservationFields.changed` acrescenta `"expectedArrivalTime"`; a entrada da história e a trilha da alteração levam o nome do campo, como os demais (a hora não é dado pessoal e pode ir ao retrato da trilha). O `ReservationChanged` é publicado como em qualquer alteração (o `changedFields` traz o nome); o evento **não** ganha campo novo. Consequência aceita: o `fnrh` registra um `UPDATE_RESERVATION`, como já faz numa alteração só de `specialRequests`; o M8 (distribuição de canais) lê a hora pela reserva.
- **D5 — fora desta etapa.** O pick-up e a rooming list de bloqueio (`RoomBlocks`) e o walk-in não recebem a hora (a recepção a informa pela alteração). A view `report_reservations` não muda (E09 decide).
- **D6 — consulta da governança.** `ReservationsApi.expectedArrivalTimes(ids: Collection<ReservationId>): Map<ReservationId, LocalTime>`: só as reservas com hora; sem permissão e sem trava, como `assignedRooms`.
- **D7 — chegada por UH.** `ArrivalsByRoom.arrivalsByRoom(date)` passa a devolver `Map<RoomId, LocalTime?>` (as mesmas UHs de hoje; o valor é a menor hora entre as reservas que chegam na UH, ou `null` se nenhuma tem hora). A geração (E02) e a avulsa usam só as chaves (`.keys`), sem mudar de comportamento.
- **D8 — ordem da sugestão.** `SuggestionTask` ganha `arrivalTime: LocalTime?` e `earlyCheckIn: Boolean` (este decidido na `application`: `arrivalExpected && arrivalTime != null && arrivalTime < policy().checkInTime`). A ordem de execução do `AssignmentSuggester` passa a ser: `earlyCheckIn` (desc), `arrivalExpected` (desc), `arrivalTime` (asc, `null` por último; só pesa entre chegadas, porque as demais têm `null`), prioridade (desc), andar, local, id. Os passos 3 e 4 (andares inteiros e rebalanceamento) não mudam. A ordem da lista de tarefas da E02 (cursor) não muda.

---

### Task 1: Hora prevista de chegada na reserva

**Objetivo:** D1 a D6: coluna, domínio, criação, alteração, resposta, trilha, evento e a consulta `expectedArrivalTimes` na `ReservationsApi`, com o contrato regenerado.

**Critérios de aceite:**
- `ReservationExpectedArrivalTimeApiTest` (`@IntegrationTest`, novo, em `src/test/kotlin/com/zeny/hotel/reservations/`):
  - criar uma reserva agrupadora com `expectedArrivalTime: "10:30"` grava e devolve `"10:30:00"` (ou o formato que o mapeador do projeto serializa para `LocalTime`; o teste fixa o real) no `GET` da reserva; sem o campo, a resposta traz `null`;
  - alterar só a hora (mesma estadia) devolve a hora nova, com o mesmo `total`, as mesmas noites e a mesma alocação, sem mudar o inventário da data (conferido pela `InventoryApi`/endpoint de inventário), sem pedir aprovação de desconto, com uma entrada `CHANGE` na história que lista `expectedArrivalTime` e um `ReservationChanged` com `changedFields == ["expectedArrivalTime"]`;
  - alterar mandando `expectedArrivalTime: null`, e alterar sem o campo, apagam a hora (Review Focus 2);
  - alterar só a hora de uma reserva `IN_HOUSE` é aceito e não mexe em noites (Review Focus 1);
  - `"7:30"`, `"24:00"`, `"10:30:00.5"`, `""`, `1030` e `"10h30"` dão `400 VALIDATION_FAILED` com `errors[].field` apontando o campo, na criação (dentro de `reservations[0]`) e na alteração; `"10:30:15"` é aceito (Review Focus 3).
  - Verificação: `timeout 900 ./gradlew test --tests '*ReservationExpectedArrivalTimeApiTest'`.
- `ReservationsApiExpectedArrivalTimesTest` (`@IntegrationTest`, novo): `expectedArrivalTimes` devolve só as reservas pedidas que têm hora; id inexistente ou sem hora fica de fora; o tipo de retorno não carrega nada além de id e hora (RN-13, conferido pela assinatura). Verificação: `timeout 900 ./gradlew test --tests '*ReservationsApiExpectedArrivalTimesTest'`.
- Os testes existentes de criação e alteração continuam verdes: `timeout 900 ./gradlew test --tests '*ReservationLifecycleApiTest' --tests '*BookingsApiTest'` (use os nomes reais das classes de teste de criação de reserva).
- Contrato regenerado; `timeout 900 ./gradlew checkOpenApi lint` verde; `timeout 600 .github/scripts/check-api-breaking.sh` sem `ERR` (saída anexada ao relatório).

**Áreas tocadas:** `reservations/Bookings.kt` (`NewReservation`, `ReservationSummary`), `reservations/ReservationChanges.kt` (`ReservationChange`), `reservations/ReservationsApi.kt` (`expectedArrivalTimes`), `reservations/application/BookingCreation.kt`, `reservations/application/ReservationLifecycle.kt` (`candidate`), `reservations/application/InventoryService.kt` (implementação da `ReservationsApi`), `reservations/domain/ReservationLifecycle.kt` (`ReservationFields`), `reservations/infrastructure/JdbcBookingStore.kt` (insert, update, mapeamento), `reservations/web/BookingsController.kt` (`ReservationRequest`, `ReservationResponse`), `reservations/web/ReservationLifecycleDtos.kt` (`ReservationChangeRequest`), migração `src/main/resources/db/migration/reservations/V2026_11_19_1700__add_reservation_expected_arrival_time.sql`, `api/openapi.yaml`.

**Tamanho:** ~50 min; 11 arquivos de produção, mais a migração e o contrato.

**Depende de:** nenhuma.

**Interfaces (produz):**
- `ReservationSummary.expectedArrivalTime: LocalTime?`, `NewReservation.expectedArrivalTime: LocalTime? = null`, `ReservationChange.expectedArrivalTime: LocalTime? = null`.
- `ReservationsApi.expectedArrivalTimes(ids: Collection<ReservationId>): Map<ReservationId, LocalTime>` (KDoc: hora local do hotel; só as reservas com hora; sem permissão nem trava; sem dado de hóspede).

### Task 2: Early check-in e chegadas por hora na sugestão

**Objetivo:** D7 e D8: `ArrivalsByRoom` com a hora por UH, early decidido contra o `checkInTime` da política, ordem nova no `AssignmentSuggester`, KDoc do algoritmo atualizado.

**Critérios de aceite:**
- `AssignmentSuggesterTest` (domínio, existente, acrescido):
  - `earlyComesBeforeOtherArrivalsWhichComeBeforeOtherTasks`: com uma camareira e as tarefas (early 09:00, chegada 15:00, chegada 13:00, chegada sem hora, saída de prioridade maior, permanência), a ordem de execução é early, 13:00, 15:00, sem hora, saída, permanência;
  - `twoEarlyArrivalsOrderedByTime`: early 10:00 vem antes de early 11:00;
  - `orderIsDeterministicForAnyInputPermutation`: todas as permutações da lista de entrada (ou 50 embaralhamentos com semente fixa) dão a mesma `Suggestion`;
  - os testes existentes seguem verdes (tarefas sem hora e sem early mantêm a ordem da E03).
  - Verificação: `timeout 600 ./gradlew test --tests '*AssignmentSuggesterTest'`.
- `AssignmentPlanEarlyArrivalTest` (`@IntegrationTest`, novo, com o `AssignmentPlanTestSupport`): reservas próprias alocadas a UHs próprias chegando na data, uma com hora antes do `checkInTime` da política, uma depois, uma sem hora, mais uma tarefa de permanência; `POST` da sugestão (`/api/v1/assignment-plans/{date}` no verbo de sugestão da E03) devolve a ordem early → chegada por hora → sem hora → demais; uma reserva com hora igual ao `checkInTime` não é early (Review Focus 5); duas reservas chegando na mesma UH (uma sem hora) dão à UH a hora da que tem hora (Review Focus 4, pode ser pela `ArrivalsByRoom` direto). A resposta da sugestão não traz nome, documento, contato, id de hóspede nem de reserva (RN-13). Verificação: `timeout 900 ./gradlew test --tests '*AssignmentPlanEarlyArrivalTest'`.
- Os testes da E02 e da E03 que usam `ArrivalsByRoom` seguem verdes: `timeout 900 ./gradlew test --tests '*AssignmentPlansApiTest' --tests '*HousekeepingTaskGeneration*'` (use os nomes reais).
- `timeout 900 ./gradlew checkOpenApi lint` verde (o contrato não deve mudar nesta fatia).

**Áreas tocadas:** `housekeeping/application/ArrivalsByRoom.kt`, `housekeeping/application/AssignmentPlansService.kt` (`suggestionTasks`), `housekeeping/application/HousekeepingTaskGeneration.kt` e `HousekeepingTasksService.kt` (só `.keys`), `housekeeping/domain/AssignmentSuggester.kt` (campos e ordem, KDoc dos passos 2 e 5).

**Tamanho:** ~40 min; 5 arquivos de produção.

**Depende de:** Task 1 (usa `ReservationsApi.expectedArrivalTimes`).

**Interfaces (consome):** `ReservationsApi.expectedArrivalTimes` (Task 1); `HotelApi.policy().checkInTime: LocalTime`.

**Interfaces (produz):** `ArrivalsByRoom.arrivalsByRoom(date: LocalDate): Map<RoomId, LocalTime?>`; `SuggestionTask.arrivalTime: LocalTime?` e `SuggestionTask.earlyCheckIn: Boolean`.
