# M6 E16 — Dívidas de teste herdadas do M5 — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** pagar as dívidas de teste que o gate do M5 passou ao M6 (teste de índices mais fraco nas listas por situação; `FnrhSubmissionDismissCompat.kt` duplicado) e as que as revisões finais da E14 e da E15 deixaram para esta etapa (o helper `messagesOf` que só entende o log de console; um comentário contraditório no `ModularityTest` e textos de teste que ainda falam em "códigos FNRH").

**Architecture:** só código de teste. Nenhum arquivo de `src/main` muda. As tarefas são de teste puro, então o implementador é o `testador` (ruling do orquestrador: a soul de testes escreve teste; a revisão é a da `revisor-seguranca`).

**Tech Stack:** Kotlin 2.4, JUnit 5, Kotest assertions, PostgreSQL 18 (`EXPLAIN`), Spring Boot 4.1 (`OutputCaptureExtension`, logback com console e ECS JSON).

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E16 — Dívidas de teste herdadas do M5", e o handoff da E14 do mesmo roadmap ("Notas para a próxima etapa", item da E16); handoff da M5 E01 em `docs/roadmaps/roadmap-m5-recepcao-fnrh.md` (o item "Médio (para o gate E12 ou o M6)" e o item 1 da correção da intermitência) e a decisão E05 (f) do M5 (o filtro "period" aceita dois índices; fica como está).

## Decisões do plano

- **D-E16-1 — Índice com condição útil:** nas listas por situação (`IN_HOUSE` e `EXPECTED_NO_SHOWS`, nas duas datas) e na busca só por situação, o teste exige que `reservation r` seja lida por um nó de índice (`Index Scan`, `Index Only Scan` ou `Bitmap Index Scan` — no bitmap, o nó `Bitmap Index Scan` sob o `Bitmap Heap Scan on reservation r`) cuja condição (`Index Cond:`) esteja no próprio nó, e nunca por `Seq Scan`. Qualquer índice serve, sem nome fixo (o planejador pode preferir outro índice útil). O `ANALYZE` atual fica. O filtro "period" continua com `shouldUseOneOf` (decisão E05 (f) do M5). O helper novo, `String.shouldReadReservationsByIndexCond()`, isola o nó de `reservation r` pela indentação do `EXPLAIN` (o nó e as linhas de detalhe dele, até o próximo nó irmão ou de nível acima).
- **D-E16-2 — Prova da mutação:** o helper ganha testes de unidade com planos de `EXPLAIN` fixos (texto colado no teste): um com `Index Cond` no nó de `reservation r` passa; um percurso de índice sobre `reservation r` só com `Filter:` (sem `Index Cond`) falha; um `Seq Scan on reservation r` falha; um `Index Cond` que pertence a outra tabela (`reservation_guest`) não salva o nó de `reservation r`. Além disso, a mutação real temporária (por exemplo, trocar no teste a lista de hospedados para uma consulta sem predicado indexável, ou desabilitar o índice parcial numa transação de teste) é feita, mostrada vermelha no relatório e desfeita antes do commit.
- **D-E16-3 — Dismiss de produção:** as duas cópias de `FnrhSubmissionDismissCompat.kt` (`src/test/kotlin/com/zeny/hotel/fnrh/` e `…/fnrh/domain/`) saem; as chamadas `dismiss(now)`/`dismiss(later)` passam a `dismiss()`. Os helpers de teste que se chamam `dismiss(…)` mas não são a extensão (como `FnrhNoShowSendAttackTest.dismiss(submission: UUID)`, que chama a API) ficam.
- **D-E16-4 — Log em JSON (ruling da revisão final da E14, M-1):** `messagesOf` (`testsupport/LogMessages.kt`) passa a entender também a linha ECS JSON do logback (`{"@timestamp":…,"message":"…",…}`): devolve só o valor de `message` (e, se houver, o texto da exceção que o ECS grava, sem `pid` nem `@timestamp`). Uma linha do console que casa só com o prefixo, sem ` : `, não é mais apagada inteira: fica o texto depois do prefixo. Os dois testes que já usam `messagesOf` (`SerproFnrhGatewayRound3Test`, `SerproFnrhGatewayRequestBuildTest`) não mudam de asserção.

- **D-E16-5 — Textos de teste da E15 (ruling da revisão final da E15, Minors 1 e 2):** no `ModularityTest`, o comentário do teste do `fnrh` deixa de dizer "Nunca iam, frontdesk nem tabela de outro schema" e passa a "Nunca iam nem tabela de outro schema; do `frontdesk`, só a porta dos rótulos (M6 E15)"; em `RegistrationCardApiTest`, o KDoc (~linha 55) e o nome do teste (~linha 86, "…and the FNRH codes…") passam a falar em rótulos da FNRH. Nenhuma asserção muda.

## Global Constraints

- Nenhum arquivo de `src/main` muda (`git diff --stat <base>..HEAD -- src/main` vazio).
- Se o teste endurecido mostrar uma lista sem índice útil, a etapa para e relata: é defeito de produção da M5 E01, a tratar numa etapa própria (não mexer em índice nem consulta).
- O banco é compartilhado e outros testes avançam a data operacional e deixam hospedadas: o teste de índices continua estável com dados reais de outros testes (o `ANALYZE` antes dos `EXPLAIN` fica).
- Nenhum teste afrouxado: toda asserção existente continua tão forte quanto antes.
- Máquina de 14 GB: um Gradle por vez, nunca `./gradlew --stop`; `detektTest` e `ktlintCheck` antes de cada commit.
- Commits em pt-BR com o trailer no último parágrafo, sem linha em branco entre `Soul: testador` e `Co-Authored-By: …`.

## Review Focus

1. **Plano com `Index Cond` em nó de outra tabela** (junção com `reservation_guest` ou `booking`): não pode contar como condição útil sobre `reservation r` — teste de unidade na Task 1.
2. **`Bitmap Heap Scan` com `Recheck Cond`** mas sem o `Bitmap Index Scan` filho com `Index Cond`: falha — teste de unidade na Task 1.
3. **Intermitência com dados de outros testes** (hospedadas reais, data avançada): três rodadas seguidas do `ReservationSearchApiTest` verdes, e uma delas depois de `StayTransitionsApiTest` no mesmo JVM — Task 1.
4. **Linha ECS JSON com `message` vazio ou com aspas e escapes** (`\"`, `\n`): `messagesOf` devolve o texto decodificado, sem quebrar — teste na Task 3.
5. **Chamada a `dismiss` com instante esquecida em teste novo**: some a extensão, e o compilador acusa — Task 2 (critério: nenhum teste chama `dismiss` com instante).

---

### Task 1: Teste de índices exige condição útil sobre `reservation r`

Estimativa: 40 min · Depende de: nenhuma · Risco: normal (só teste)

**Files:**
- Modify (teste): `src/test/kotlin/com/zeny/hotel/reservations/ReservationSearchApiTest.kt` (helper novo e uso nas listas por situação e na busca só por situação)
- Test: `src/test/kotlin/com/zeny/hotel/reservations/ReservationPlanAssertionsTest.kt` (unidade, sem Spring; se o helper for movido para um arquivo próprio de apoio no mesmo pacote, ex. `ReservationPlanAssertions.kt`, o teste o usa de lá)

**Interfaces:**
- Produces: `fun String.shouldReadReservationsByIndexCond()` (asserção sobre o texto de um `EXPLAIN`).

- [ ] **Step 1: Testes de unidade que falham** (D-E16-2, com planos fixos): `an index scan with Index Cond on reservation r passes`; `a bitmap heap scan whose bitmap index scan has Index Cond passes`; `an index scan on reservation r with only Filter fails`; `a seq scan on reservation r fails`; `an Index Cond on another table does not count for reservation r`; `a bitmap heap scan with only Recheck Cond fails`.
- [ ] **Step 2:** `./gradlew test --tests 'com.zeny.hotel.reservations.ReservationPlanAssertionsTest'` → vermelho (helper não existe).
- [ ] **Step 3: Implementar** o helper (D-E16-1) e trocar `shouldUseOneOf` por ele nas listas `IN_HOUSE` e `EXPECTED_NO_SHOWS` (as duas datas) e na busca só por situação; o "period" e os índices exatos ficam como estão.
- [ ] **Step 4: Mutação temporária** (D-E16-2): faça a lista de hospedados ou de no-shows previstos cair num percurso sem `Index Cond` sobre `reservation r` (sem tocar em `src/main`), rode o `ReservationSearchApiTest` e guarde a saída vermelha no relatório; desfaça.
- [ ] **Step 5:** três rodadas seguidas: `./gradlew test --tests 'com.zeny.hotel.reservations.ReservationSearchApiTest' --rerun` (3×) → verdes; e uma com `--tests 'com.zeny.hotel.frontdesk.StayTransitionsApiTest' --tests 'com.zeny.hotel.reservations.ReservationSearchApiTest'` (ajuste o pacote real do `StayTransitionsApiTest`) → verde.
- [ ] **Step 6: Commit** `test(reservations): o teste de índices exige condição útil sobre a reserva nas listas por situação` com `Soul: testador`.

### Task 2: Sai o `FnrhSubmissionDismissCompat.kt` e os textos de teste da E15

Estimativa: 15 min · Depende de: nenhuma · Risco: normal (só teste)

**Files:**
- Delete: `src/test/kotlin/com/zeny/hotel/fnrh/FnrhSubmissionDismissCompat.kt`, `src/test/kotlin/com/zeny/hotel/fnrh/domain/FnrhSubmissionDismissCompat.kt`
- Modify (teste): os chamadores de `dismiss(<instante>)` (hoje `FnrhReportViewsAttackTest`, `FnrhSubmissionPersistenceAttackTest` e os demais que `grep` achar)
- Modify (teste, D-E16-5): `src/test/kotlin/com/zeny/hotel/ModularityTest.kt` (só o comentário) e `src/test/kotlin/com/zeny/hotel/frontdesk/RegistrationCardApiTest.kt` (só KDoc e nome de teste)

- [ ] **Step 1:** apague as duas cópias e rode `./gradlew compileTestKotlin` → falha de compilação nos chamadores (a prova de que a lista é completa).
- [ ] **Step 2:** troque as chamadas por `dismiss()` (D-E16-3) e aplique D-E16-5, sem mudar nenhuma asserção.
- [ ] **Step 3:** `grep -rn 'DismissCompat' src/test` vazio; `grep -rnE '\.dismiss\([^)]' src/test` sem chamada da extensão com instante; `./gradlew test --tests 'com.zeny.hotel.fnrh.*' --tests 'com.zeny.hotel.ModularityTest' --tests 'com.zeny.hotel.frontdesk.RegistrationCardApiTest'` → verde.
- [ ] **Step 4: Commit** `test(fnrh): os testes usam o dismiss de produção, a cópia de compatibilidade sai e os textos da E15 são corrigidos` com `Soul: testador`.

### Task 3: `messagesOf` entende o log em ECS JSON

Estimativa: 30 min · Depende de: nenhuma · Risco: normal (só teste)

**Files:**
- Modify (teste): `src/test/kotlin/com/zeny/hotel/testsupport/LogMessages.kt`
- Modify (teste): `src/test/kotlin/com/zeny/hotel/testsupport/LogMessagesTest.kt`

- [ ] **Step 1: Testes que falham** em `LogMessagesTest`: `an ECS JSON line yields only its message` (linha com `"@timestamp"` em nanossegundos, `"pid":3010000` e `"message":"FNRH registerPerson … ref corr-3"` → só a mensagem; `100` do PID não aparece); `an ECS JSON message with escaped quotes and newline is decoded`; `an ECS JSON line with an error keeps the error text without pid`; `a console line with the prefix but no logger separator keeps the text after the prefix`. Os três testes atuais continuam.
- [ ] **Step 2:** `./gradlew test --tests 'com.zeny.hotel.testsupport.LogMessagesTest'` → vermelho.
- [ ] **Step 3: Implementar** D-E16-4 (parse da linha JSON com o mapeador Jackson já disponível nos testes; linha que não é JSON válido segue as regras do console).
- [ ] **Step 4:** `./gradlew test --tests 'com.zeny.hotel.testsupport.*' --tests 'com.zeny.hotel.fnrh.infrastructure.*'` → verde.
- [ ] **Step 5: Commit** `test(testsupport): messagesOf lê também a mensagem do log em ECS JSON` com `Soul: testador`.

## Validação da etapa

- `./gradlew build` → BUILD SUCCESSFUL.
- `git diff --stat <início da E16>..HEAD -- src/main` vazio.
- Critérios de aceite da E16: (1) Task 1, Steps 1 e 4; (2) Task 1, Step 5; (3) Task 2; (4) diff de `src/main` vazio.
