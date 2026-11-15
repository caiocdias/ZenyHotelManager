# M6 E16 — Dívidas de teste herdadas do M5 — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** pagar as dívidas de teste que o gate do M5 passou ao M6 (teste de índices mais fraco nas listas por situação; `FnrhSubmissionDismissCompat.kt` duplicado) e as que as revisões finais da E14 e da E15 deixaram para esta etapa (o helper `messagesOf` que só entende o log de console; um comentário contraditório no `ModularityTest` e textos de teste que ainda falam em "códigos FNRH").

**Architecture:** só código de teste. Nenhum arquivo de `src/main` muda. Fluxo normal das souls: o `desenvolvedor` implementa (TDD), o `testador` ataca as tarefas `Risco: alto`, a `revisor-seguranca` revisa. Sem ondas paralelas: as tarefas rodam em série (um Gradle por vez).

**Tech Stack:** Kotlin 2.4, JUnit 5, Kotest assertions, PostgreSQL 18 (`EXPLAIN`, `pg_index`), Spring Boot 4.1 (`OutputCaptureExtension`, logback com console e ECS JSON), Jackson.

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E16 — Dívidas de teste herdadas do M5" (critérios, "Por que agora", riscos), e os handoffs da E14 e da E15 do mesmo roadmap ("Notas para a próxima etapa", itens da E16); handoff da M5 E01 em `docs/roadmaps/roadmap-m5-recepcao-fnrh.md` (o item "Médio (para o gate E12 ou o M6)" e o item 1 da correção da intermitência) e a decisão E05 (f) do M5 (o filtro "period" aceita dois índices; fica como está).

## Decisões do plano

- **D-E16-1 — Condição útil sobre `reservation r`:** nas listas por situação (`IN_HOUSE` e `EXPECTED_NO_SHOWS`, nas duas datas) e na busca só por situação, o teste exige que `reservation r` seja lida por um nó de índice (`Index Scan`, `Index Only Scan`, ou `Bitmap Heap Scan on reservation r` com o `Bitmap Index Scan` filho) que tenha uma condição útil, isto é: **(a)** uma linha `Index Cond:` no próprio nó de `r` (ou no `Bitmap Index Scan` filho dele); **ou (b)** o índice usado é parcial e o predicado dele cita `status` (consultado no catálogo: `pg_index.indpred` não nulo e `pg_get_expr(indpred, indrelid)` contendo `status`, para o índice de nome lido no nó). Nunca `Seq Scan on reservation r`. Qualquer índice que cumpra (a) ou (b) serve, sem nome fixo (risco da spec: o planejador pode preferir outro índice útil). O motivo do (b): a busca só por situação (`WHERE r.status IN (…) ORDER BY r.arrival, r.id`) não tem predicado de data, e nenhum índice tem `status` como coluna-chave; o melhor plano é o parcial `reservation_confirmed_arrival_idx`, sem `Index Cond`. O `ANALYZE` atual fica. O filtro "period" continua com `shouldUseOneOf` (decisão E05 (f) do M5), assim como os índices exatos onde não há alternativa.
- **D-E16-2 — Os parciais voltam a ter guarda determinística ("Por que agora" da spec):** um teste novo no mesmo arquivo, numa transação com o papel dono (`flyway.configuration.dataSource`, como o `ANALYZE`) que termina sempre em `ROLLBACK`, faz `DROP INDEX` dos índices de data que competem (`reservation_arrival_idx`, `reservation_departure_idx` e qualquer outro não parcial que o `EXPLAIN` mostrar usado nessas listas) e exige, para `IN_HOUSE`, `EXPECTED_NO_SHOWS` e a busca só por situação, a leitura de `r` por um índice parcial cujo predicado cita `status` (regra (b)), com `Index Cond` nas listas que têm data. Assim, um índice parcial apagado ou com predicado errado fica vermelho, sem depender das estatísticas. Execução: tudo numa só conexão e transação do dono — `SET LOCAL lock_timeout = '2s'`, `SET LOCAL enable_seqscan = off`, os `DROP INDEX` e os `EXPLAIN` (não reaproveite o `explain()` atual, que roda pela conexão da aplicação e ficaria esperando a trava da própria transação). Os índices a derrubar: comece por `reservation_arrival_idx` e `reservation_departure_idx`; se o `EXPLAIN` ainda mostrar `r` lida por outro índice não parcial, derrube-o também, num laço de no máximo 5 voltas e só sobre índices que não sustentam constraint (`pg_constraint.conindid`); um índice de constraint no caminho faz o teste falhar com mensagem clara. A consulta ao catálogo usa o nome qualificado (`'reservations.<nome>'::regclass`), porque o `EXPLAIN` mostra o nome sem o schema. Custo aceito: um `ACCESS EXCLUSIVE` curto (milissegundos) em `reservations.reservation` no banco compartilhado; os testes rodam em série e os jobs do contexto estão adiados.
- **D-E16-3 — Prova da mutação, sem `src/main` nem DDL fora da transação:** (1) testes de unidade do helper com planos de `EXPLAIN` fixos; (2) mutação real temporária: no próprio teste, troque a `ReservationQuery` de uma lista (`query.copy(sql = …)`) para uma forma sem condição útil (data não indexável, `r.arrival + 0 <= :date`, e situação não literal), rode, guarde a saída vermelha no relatório e desfaça antes do commit.
- **D-E16-4 — Dismiss de produção:** as duas cópias de `FnrhSubmissionDismissCompat.kt` saem; as chamadas `dismiss(now)`/`dismiss(later)` passam a `dismiss()` (hoje em `FnrhReportViewsAttackTest`, `FnrhSubmissionPersistenceAttackTest`, `FnrhSubmissionListenersAttackTest`, `FnrhSubmissionTransitionsTest` e os demais que o compilador apontar). Os helpers de teste que se chamam `dismiss(…)` mas não são a extensão (como `FnrhNoShowSendAttackTest.dismiss(submission: UUID)`) ficam. Variáveis e imports que sobrarem sem uso saem (o detekt acusa).
- **D-E16-5 — Textos de teste da E15 (ruling da revisão final da E15, Minors 1 e 2; handoff da E15, "Para a E16"):** no `ModularityTest`, o comentário do teste do `fnrh` deixa de dizer "Nunca iam, frontdesk nem tabela de outro schema" e passa a "Nunca iam nem tabela de outro schema; do `frontdesk`, só a porta dos rótulos (M6 E15)"; em `RegistrationCardApiTest`, o KDoc (~linha 55) e o nome do teste (~linha 86, "…and the FNRH codes…") passam a falar em rótulos da FNRH. Nenhuma asserção muda.
- **D-E16-6 — Log em ECS JSON sem afrouxar (handoff da E14, "Para a E16"; revisão final da E14, M-1):** `messagesOf` (`testsupport/LogMessages.kt`) passa a reconhecer a linha ECS JSON do logback e a **retirar só os campos de ruído conhecidos** — `@timestamp`, `process` (pid e thread), `ecs`, `service` e `log.level` — devolvendo **todos os demais valores** (inclusive `message`, `log.logger`, `error.*`, MDC e qualquer par chave-valor estruturado), para que um dado pessoal num desses campos continue visível às asserções negativas. Fixture dos testes: a linha real do formato `ecs` capturada no gate da E14, `{"@timestamp":"2026-11-14T21:30:18.039123595Z","log":{"level":"INFO","logger":"org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean"},"process":{"pid":3010000,"thread":{"name":"SpringApplicationShutdownHook"}},"service":{"name":"zeny-hotel-manager","node":{}},"message":"Closing JPA EntityManagerFactory for persistence unit 'default'","ecs":{"version":"8.11"}}`, e variações dela. Também corrige um caso do console (achado da mesma revisão): a linha com o prefixo de data, nível e PID mas sem o separador ` : ` do logger não é mais apagada inteira; fica o texto depois do prefixo.
- **D-E16-7 — Apontamentos esperados da verificação mecânica:** a Task 1 troca asserções do `ReservationSearchApiTest` (o `shouldUseOneOf` das listas por situação pelo helper novo; `ASSERCAO_REMOVIDA` esperado, justificado por D-E16-1/D-E16-2, mais fortes no conjunto); a Task 2 apaga arquivos de apoio e renomeia um teste (D-E16-4, D-E16-5); se algum desses arquivos for de autoria do testador, `DEV_TESTE_DO_TESTADOR` é esperado e autorizado por este plano. A revisora julga cada um.

## Global Constraints

- Nenhum arquivo de `src/main` muda (`git diff --stat <início da E16>..HEAD -- src/main` vazio).
- Se o teste endurecido mostrar uma lista sem índice útil (nem (a) nem (b)), a etapa para e relata: é defeito de produção da M5 E01, a tratar numa etapa própria (não mexer em índice nem consulta).
- O banco é compartilhado e outros testes avançam a data operacional e deixam hospedadas: o teste de índices continua estável com dados reais de outros testes (o `ANALYZE` antes dos `EXPLAIN` fica).
- Nenhum teste afrouxado: toda asserção existente continua tão forte quanto antes, ou o conjunto fica mais forte e isso é dito no relatório.
- Máquina de 14 GB: um Gradle por vez, sem ondas paralelas, nunca `./gradlew --stop`; `detektTest` e `ktlintCheck` antes de cada commit.
- Commits em pt-BR com o trailer no último parágrafo, sem linha em branco entre `Soul: <soul>` e `Co-Authored-By: …`.

## Review Focus

1. **Plano com `Index Cond` em nó de outra tabela** (junção com `reservation_guest` ou `booking`): não conta para `reservation r` — teste de unidade na Task 1.
2. **`Bitmap Heap Scan on reservation r` só com `Recheck Cond`**, sem `Index Cond` no `Bitmap Index Scan` filho e com índice não parcial: falha — teste de unidade na Task 1.
3. **Intermitência com dados de outros testes** (hospedadas reais, data avançada): a prova "depois" segue o método da M5 E01 (cópia temporária que roda depois do `StayTransitionsApiTest`, ordem conferida pelos carimbos do XML, apagada antes do commit) — Task 1.
4. **Linha ECS JSON com dado pessoal fora de `message`** (num campo de MDC ou em `error.message`): continua visível ao `messagesOf` — teste na Task 3.
5. **Chamada a `dismiss` com instante esquecida em teste novo**: some a extensão, e o compilador acusa — Task 2.

---

### Task 1: Teste de índices exige condição útil sobre `reservation r` e guarda os parciais

Estimativa: 45 min · Depende de: nenhuma · Risco: alto (núcleo da etapa: critérios 1 e 2)

**Files:**
- Create (teste): `src/test/kotlin/com/zeny/hotel/reservations/ReservationPlanAssertions.kt` (`internal fun` de nível superior, usado pelos dois testes)
- Modify (teste): `src/test/kotlin/com/zeny/hotel/reservations/ReservationSearchApiTest.kt`
- Test: `src/test/kotlin/com/zeny/hotel/reservations/ReservationPlanAssertionsTest.kt` (unidade, sem Spring)

**Interfaces:**
- Produces: `internal fun reservationIndexNode(plan: String): ReservationIndexRead?` (o índice e as linhas de detalhe do nó que lê `reservation r`, isolado pela indentação do `EXPLAIN`: o nó e as linhas dele até o próximo nó irmão ou de nível acima; no bitmap, o `Bitmap Index Scan` filho), `data class ReservationIndexRead(val indexName: String, val hasIndexCond: Boolean)`, e a asserção `internal fun String.shouldReadReservationsUsefully(isStatusPartial: (String) -> Boolean)` (passa com (a) ou (b) de D-E16-1; falha com `Seq Scan on reservation r` ou sem condição útil). O `ReservationSearchApiTest` fornece `isStatusPartial` lendo `pg_index` pelo nome do índice.

- [ ] **Step 1: Testes de unidade que falham** (planos fixos): `an index scan with Index Cond on reservation r passes`; `a bitmap heap scan whose bitmap index scan has Index Cond passes`; `a status partial index without Index Cond passes`; `a non partial index on reservation r with only Filter fails`; `a seq scan on reservation r fails`; `an Index Cond on another table does not count for reservation r`; `a bitmap heap scan with only Recheck Cond on a non partial index fails`.
- [ ] **Step 2:** `./gradlew test --tests 'com.zeny.hotel.reservations.ReservationPlanAssertionsTest'` → vermelho.
- [ ] **Step 3: Implementar** o apoio e, no `ReservationSearchApiTest`: trocar `shouldUseOneOf` por `shouldReadReservationsUsefully` nas listas `IN_HOUSE` e `EXPECTED_NO_SHOWS` (as duas datas) e na busca só por situação (D-E16-1); acrescentar o teste `the status lists keep their partial indexes when the date indexes are gone` (D-E16-2, transação do dono sempre em `ROLLBACK`). O "period" e os índices exatos ficam.
- [ ] **Step 4: Mutação temporária** (D-E16-3 (2)): guarde no relatório a saída vermelha do teste com a consulta trocada; desfaça.
- [ ] **Step 5: Estabilidade:** três rodadas seguidas de `./gradlew test --tests 'com.zeny.hotel.reservations.ReservationSearchApiTest' --rerun` → verdes; e a prova "depois" pelo método da M5 E01: uma cópia temporária do `ReservationSearchApiTest` rodada junto de `--tests 'com.zeny.hotel.reservations.StayTransitionsApiTest'`, com a ordem conferida pelos carimbos do relatório XML (a cópia termina depois do `StayTransitionsApiTest`) e anexada ao relatório; a cópia é apagada antes do commit.
- [ ] **Step 6: Commit** `test(reservations): o teste de índices exige condição útil sobre a reserva e guarda os índices parciais da situação` com `Soul: desenvolvedor`.

### Task 2: Sai o `FnrhSubmissionDismissCompat.kt` e os textos de teste da E15

Estimativa: 20 min · Depende de: Task 1 (só ordem; sem ondas) · Risco: normal (o compilador prova a lista de chamadores; nenhuma asserção muda)

**Files:**
- Delete: `src/test/kotlin/com/zeny/hotel/fnrh/FnrhSubmissionDismissCompat.kt`, `src/test/kotlin/com/zeny/hotel/fnrh/domain/FnrhSubmissionDismissCompat.kt`
- Modify (teste): os chamadores de `dismiss(<instante>)` (D-E16-4)
- Modify (teste, D-E16-5): `src/test/kotlin/com/zeny/hotel/ModularityTest.kt` (só o comentário) e `src/test/kotlin/com/zeny/hotel/frontdesk/RegistrationCardApiTest.kt` (só KDoc e nome de teste)

- [ ] **Step 1:** apague as duas cópias e rode `./gradlew compileTestKotlin` → falha de compilação nos chamadores (a prova de que a lista é completa).
- [ ] **Step 2:** troque as chamadas por `dismiss()` (D-E16-4) e aplique D-E16-5, sem mudar nenhuma asserção; tire variáveis e imports que sobrarem sem uso.
- [ ] **Step 3:** `grep -rn 'DismissCompat' src/test` vazio; nenhuma chamada da extensão com instante; `./gradlew detektTest ktlintCheck test --tests 'com.zeny.hotel.fnrh.*' --tests 'com.zeny.hotel.ModularityTest' --tests 'com.zeny.hotel.frontdesk.RegistrationCardApiTest'` → verde.
- [ ] **Step 4: Commit** `test(fnrh): os testes usam o dismiss de produção, a cópia de compatibilidade sai e os textos da E15 são corrigidos` com `Soul: desenvolvedor`.

### Task 3: `messagesOf` entende o log em ECS JSON sem esconder campo nenhum

Estimativa: 30 min · Depende de: Task 2 (só ordem; sem ondas) · Risco: alto (parsing; sustenta asserções negativas de dado pessoal)

**Files:**
- Modify (teste): `src/test/kotlin/com/zeny/hotel/testsupport/LogMessages.kt`
- Modify (teste): `src/test/kotlin/com/zeny/hotel/testsupport/LogMessagesTest.kt`

- [ ] **Step 1: Testes que falham** em `LogMessagesTest` (fixture: a linha ECS de D-E16-6): `an ECS JSON line drops only the known noise fields` (sem `@timestamp`, sem `3010000`, sem `8.11`, sem `zeny-hotel-manager`, sem `INFO`; com a `message` e o `log.logger`); `a personal value in an MDC field of an ECS line stays visible` (um campo extra `"guestDocument":"52998224725"` aparece na saída); `an ECS error keeps its type and message` (`"error":{"type":"…","message":"cep 01310-100"}` → aparece); `an ECS message with escaped quotes and newline is decoded`; `a console line with the prefix but no logger separator keeps the text after the prefix`; `a line that is not valid JSON follows the console rules`. Os três testes atuais continuam.
- [ ] **Step 2:** `./gradlew test --tests 'com.zeny.hotel.testsupport.LogMessagesTest'` → vermelho.
- [ ] **Step 3: Implementar** D-E16-6 (Jackson, já disponível nos testes).
- [ ] **Step 4:** `./gradlew detektTest ktlintCheck test --tests 'com.zeny.hotel.testsupport.*' --tests 'com.zeny.hotel.fnrh.infrastructure.*'` → verde (os dois usos atuais de `messagesOf` sem mudança de asserção).
- [ ] **Step 5: Commit** `test(testsupport): messagesOf lê o log em ECS JSON e só tira os campos de ruído` com `Soul: desenvolvedor`.

## Validação da etapa

- `./gradlew build` → BUILD SUCCESSFUL.
- `git diff --stat <início da E16>..HEAD -- src/main` vazio.
- Critérios de aceite da E16: (1) Task 1, Steps 1, 3 e 4 e o teste de D-E16-2; (2) Task 1, Step 5; (3) Task 2; (4) diff de `src/main` vazio.

## Para o handoff

- Escopo acrescentado por ruling, com a origem: D-E16-5 (revisão final e handoff da E15) e D-E16-6 (revisão final e handoff da E14).
- A busca só por situação é servida por índice parcial sem `Index Cond` (regra (b)); se o projeto quiser `Index Cond` também nela, precisa de índice com `status` na chave (mudança de produção, fora desta etapa).

## Revisão de arquitetura

- Veredito: **aprovado com notas**, rodada 1 (`revisor-arquitetura`, opus). Rodada 0 bloqueada com 1 Crítico (a busca só por situação não tem `Index Cond` possível) e 4 Importantes (risco das Tasks 1 e 3, guarda dos parciais, JSON afrouxaria a privacidade, prova "depois"), todos corrigidos.
- Notas Menores aplicadas na D-E16-2: uma só conexão e transação do dono com `lock_timeout`; laço limitado sobre índices sem constraint; nome qualificado no catálogo. M-10 (sem ADRs) fica como nota.
