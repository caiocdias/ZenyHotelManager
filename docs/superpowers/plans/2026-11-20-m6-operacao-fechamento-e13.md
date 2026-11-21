# E13 do M6 — Snapshot e arquivo do fechamento: plano de implementação

> **Para agentes:** execução por `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde, não o código.

**Goal:** acrescentar ao fechamento do dia, antes do avanço da data, as etapas `daily-statistics` (800) e `end-of-day-reports` (900) da RF-FEC-02 (passos 8 e 9), pela API pública do `reporting` da E23, e registrar as pendências da RF-FEC-04 (as da E09) como `EndOfDayIssue` numa etapa própria, `end-of-day-pending` (950).

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E13", H10 e os handoffs da E10, E11, E09 e E23; SPEC RF-FEC-02, RF-FEC-03, RF-FEC-04, RN-13, RN-21; ARCHITECTURE §3.1 (linha `endofday`, exceção de mesma camada `endofday → reporting`), §13 (linha do fechamento). Brain: `ZenyHotelManager - Módulo endofday` ("Contrato de etapa", "Motor", "Etapas reais (M6 E11)"), `ZenyHotelManager - Testes de integração` ("M6 E10" e "M6 E11"). Código: `endofday/application/EndOfDaySteps.kt` (KDoc do contrato de etapa), `reporting/ReportingApi.kt` (KDoc do snapshot e do arquivo).

**Pré-condição de execução:** a E23 está `#concluida` (gate verde no HEAD dela). A Task 1 só é despachada depois disso. Se o fechamento da E23 mudar a `ReportingApi` (`snapshotDailyStatistics`, `archiveDayReports`) ou o `EndOfDayPendingService.loadWithoutAuthorization()`, o plano volta à re-revisão de arquitetura antes da Task 1.

**Architecture:** três etapas novas em `endofday/application/steps/` (o pacote que os contextos de etapas falsas excluem), beans `EndOfDayStepHandler` como as da E11: `daily-statistics` (800) chama `ReportingApi.snapshotDailyStatistics(date)` na transação da etapa, que confirma o snapshot ao concluir; `end-of-day-reports` (900) chama `ReportingApi.archiveDayReports(date)` (transação própria por tipo, `REQUIRES_NEW`, que só vê o snapshot porque a etapa 800 já confirmou); `end-of-day-pending` (950) levanta uma pendência por item de `ReportingApi.endOfDayPendingItems()` na transação da etapa. O `endofday` passa a depender de `reporting` (aresta já na tabela §3.1 como exceção de mesma camada), só da raiz dele e só dessas três operações. Idempotência pela data vem do `reporting` (uma linha de snapshot e um anexo por data e tipo); a retomada repete só a etapa não concluída.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1, ShedLock 7, Testcontainers.

**Riscos:**
- **concorrência:** etapa repetida ou retomada depois de falha no meio do arquivo; render longo sob a trava global com *keep-alive* (Tasks 1 e 2);
- **dado pessoal ou segredo:** a pendência (`kind`, referência e mensagem, lidas pela Recepção e pelo Financeiro) e o aviso push nunca levam dado de hóspede nem o texto da falha da FNRH (Task 2);
- **autenticação ou autorização:** `endOfDayPendingItems()` é mais uma operação da API de outro módulo sem conferir permissão, e um subconjunto do que hoje sai com `reporting.pending.read` passa a sair com `endofday.runs.read` (`GET …/runs/{id}/issues` e o aviso) (Task 2, D3).

## Global Constraints

- Contrato de etapa do KDoc de `EndOfDayStepHandler`: idempotente pela data (nunca pelo `runId`); nome estável `[a-z][a-z0-9-]{0,59}`; posições 800, 900 e 950 (entre `occupied-rooms-dirty` 700 e `advance-business-date` 1000); a pendência nunca leva dado pessoal de hóspede (RN-13), e a referência é opaca (um id).
- Grafo §3.1 com o `ModularityTest` exato: `endofday` = `["shared", "hotel", "notifications", "reservations", "billing", "rooms", "reporting"]`; o `endofday` usa só a raiz do `reporting` (`ReportingApi` e os tipos dela) e só as três operações deste plano.
- Sem endpoint novo, sem migração, sem mudança no contrato OpenAPI: `checkOpenApi` e `check-api-breaking.sh` sem diferença.
- Testes que rodam o fechamento com as etapas reais usam `@EndOfDayIntegrationTest` (banco próprio `zeny_endofday`, H10) e `EndOfDayRealStepsTestConfiguration`; cada teste termina com a data corrente sem execução ou `COMPLETED` (`EndOfDayTestRuns.settle()`) e afirma só sobre os próprios dados (o banco isolado acumula execuções, pendências e a data só avança). Nenhum teste do banco compartilhado dispara o fechamento nem grava pendência que não possa desfazer.
- **Ruling dos testes do testador:** o desenvolvedor da Task 1 atualiza só o `STEPS` do companion de `EndOfDayRealRunTest` (acrescentar `"daily-statistics" to 800` e `"end-of-day-reports" to 900`; a Task 2 acrescenta `"end-of-day-pending" to 950`); nenhuma asserção sai. O apontamento `DEV_TESTE_DO_TESTADOR` do `check_test_integrity.py` nesse arquivo é esperado e coberto por esta ruling. Qualquer outro teste do testador que ficar vermelho volta ao orquestrador, sem ser editado.
- Código em inglês; KDoc e commits em pt-BR; commits com `Soul:` e `Co-Authored-By` no mesmo bloco final.
- Um Gradle por vez; `timeout` em todo comando; sem laço de espera; nunca `./gradlew --stop`.
- Verificação da fatia: os testes citados na fatia **mais todo o fechamento** (`--tests 'com.zeny.hotel.endofday.*' --tests '*HousekeepingEndOfDayTasksTest' --tests '*AssignmentPlanConcurrencyTest' --tests 'com.zeny.hotel.testsupport.EndOfDay*'`), porque todo fechamento real das 20 classes que usam `EndOfDayRealStepsTestConfiguration` passa a renderizar o PDF e a levantar as pendências, e `timeout 900 ./gradlew checkOpenApi lint`.

## Review Focus

1. **Falha do render do PDF no meio do fechamento:** a execução fica `FAILED` na etapa 900, a data não avança, nenhum anexo fica vinculado; a retomada conclui com um anexo só e o snapshot da etapa 800 não é refeito. Teste na Task 1. (A falha da gravação no `files` desfaz o tipo pela garantia da E23, `FilesServerStoreAttackTest` e `DayReportsArchiveTest`; aqui só se força o render.)
2. **Etapa 800 ou 900 repetida para a mesma data** (retomada depois de queda com a etapa `RUNNING`): um snapshot e um anexo por data. Teste na Task 1.
3. **Execução aberta antes do deploy desta etapa** (etapas gravadas até 700 concluídas, sem 800, 900 e 950): a reconciliação do motor inclui as três e a data avança depois delas; execução já `COMPLETED` sem snapshot não quebra o painel, os relatórios nem a consulta do arquivo. Teste na Task 2.
4. **Pendência ou aviso com dado de hóspede ou com a mensagem de falha da FNRH:** a mensagem e a referência só levam código, número e id; o aviso push só `"<kind>: <referência ou contagem>"`. Teste na Task 2.
5. **Render longo além do `lock-at-most-for`:** o *keep-alive* estende a trava global e um segundo disparo não roda o fechamento em paralelo. Teste de unidade na Task 1 (obrigação herdada da E10).

## Decisões do plano

- **D1 — etapas e transação.** `DailyStatisticsStep` (`daily-statistics`, 800) devolve `DONE` depois de `snapshotDailyStatistics(context.businessDate)`; o snapshot fica na transação da etapa e é confirmado com ela. `EndOfDayReportsStep` (`end-of-day-reports`, 900) chama `archiveDayReports(context.businessDate)` e devolve `DONE`. As duas chamadas ficam em etapas separadas (nota da E23: na mesma transação o arquivo falha por `lock_timeout` em toda tentativa). Exceção do `reporting` não é capturada: falha a etapa, que a retomada repete (contrato de etapa: nada de capturar exceção de bean `REQUIRED`). O anexo já gravado de um tipo concluído fica (o arquivo é idempotente por data e tipo); um tipo que falhou não deixa anexo vinculado (garantia da E23).
- **D2 — dependência.** `EndOfDayModule.allowedDependencies` ganha `reporting`, o KDoc dele cita as três etapas e o alcance (só `ReportingApi`), o `ModularityTest` do `endofday` muda para a lista exata, a linha do fechamento na ARCHITECTURE §13 passa a listar as 11 etapas na ordem, e a linha `endofday` da tabela §3.1 ganha a anotação de alcance `reporting (só a ReportingApi: snapshot, arquivo e pendências do fechamento)`, como já se faz em `frontdesk → rates`. A aresta em si não muda.
- **D3 — pendências da RF-FEC-04 como `EndOfDayIssue`, numa etapa própria.** A `ReportingApi` ganha `endOfDayPendingItems(): List<EndOfDayPendingItem>`, sem conferir permissão (quem chama, o fechamento, já se autorizou), lendo o mesmo `EndOfDayPendingService.loadWithoutAuthorization()` da E09/E23. Tipos na raiz do `reporting`: `enum class EndOfDayPendingKind { FOLIO_OPEN_BALANCE, CASHIER_SHIFT_OPEN, FNRH_SUBMISSION_PENDING }` e `data class EndOfDayPendingItem(val kind: EndOfDayPendingKind, val reference: UUID, val detail: String)`, com `reference` = `folioId`, `shiftId` ou o `reservationId` da submissão, e `detail` só com número, código e status (`"conta <folioNumber> da reserva <reservationCode>"`, `"caixa <shiftNumber> aberto em <openedOn>"`, `"FNRH <operation> <status>"`), **sem saldo**, sem nome de hóspede, sem nome do ponto de caixa e sem `lastFailure`. `EndOfDayPendingStep` (`end-of-day-pending`, 950) levanta uma pendência por item na transação da etapa (`context.issues.raise(kind.name, reference.toString(), "${kind.name}: $detail")`), agrupada pelo aviso por etapa e tipo da E11, e devolve `NOT_APPLICABLE` sem itens e `DONE` com itens. Uma pendência que persiste aparece em cada fechamento (como `pending-departures`). Notas rejeitadas e ARI ficam para o M7/M8.
  - **Etapa própria** (e não dentro da 900): resultado próprio, a falha da leitura das pendências não repete o arquivo, e as pendências não ficam gravadas com o nome publicado do passo 9 da RF-FEC-02.
  - **Autorização:** o que a pendência mostra é um subconjunto do relatório de `reporting.pending.read` e passa a sair com `endofday.runs.read`; os modelos de role das duas coincidem (Gerente, Recepcionista, Financeiro), e a passagem é aceita e registrada para o usuário.
  - **Alcance da aresta:** é a terceira operação de `endofday → reporting`, contra a letra da nota da E13 ("não amplie essa aresta além da API de snapshot e arquivo") e a favor do escopo ("pendências da E09 registradas como `EndOfDayIssue`"). Alternativas descartadas: (a) o `endofday` montar as pendências pelas APIs de `billing`, `reservations` e `fnrh` (aresta nova `endofday → fnrh` e segunda fonte da regra da E09); (b) as pendências no retorno de `archiveDayReports` (o arquivo é idempotente e, na retomada, devolve o gravado, não o estado atual); (c) porta do `endofday` implementada pelo `reporting` (ciclo `reporting → endofday`); (d) não registrar as pendências (corta escopo, decisão do usuário). Decisão tomada pela opção recomendada e revisável, em "Decisões para o usuário"; o `arquiteto` ajusta o texto da nota.
- **D4 — duração.** Sem métrica nova: a duração do render entra no `zeny.endofday.run.duration` da E10; o handoff registra a duração das etapas 800 e 900 lida de `endofday.run_step` (`started_at`/`finished_at`) no teste da Task 1.
- **D5 — datas fechadas antes do deploy** ficam sem snapshot e sem PDF, sem recomposição: registrado no handoff para os KPIs do M10.

## Revisão de arquitetura

- **Veredito:** aprovado com notas, na rodada 1 (`revisor-arquitetura`, modelo opus).
- **Rodada 0:** bloqueado por 3 Importantes: I1 a E23 ainda `#em-andamento` (pré-condição de execução); I2 Riscos sem "autenticação ou autorização" diante da operação nova sem permissão e da passagem de `reporting.pending.read` a `endofday.runs.read`; I3 verificação das fatias sem os testes do fechamento cujo comportamento muda. Havia também 8 Menores (ruling larga demais, alternativas da D3 e alcance na §3.1, `@MockitoSpyBean` com contexto a mais, pendências dentro da etapa 900, sujeira no banco compartilhado, Review Focus 1 largo, datas anteriores ao deploy, falta de ADRs). Corrigidos pela opção recomendada (M4: etapa própria 950).
- **Notas Menores da rodada 1, incorporadas:** N1 o KDoc de `EndOfDayStepHandler` passa a registrar a exceção 950 (Task 2); N2 o `EndOfDayPendingItemsTest` importa `EndOfDayRealStepsTestConfiguration` e o resíduo no banco isolado é aceito. Sem ADRs no projeto (nota).
- **Para o usuário (sem bloquear):** D3, a terceira operação na aresta `endofday → reporting` contra a letra da nota da E13 (o revisor julgou que não cabe ao usuário pela §3.1, por ser a reconciliação do escopo; vai como decisão tomada e revisável), e a passagem do subconjunto das pendências para `endofday.runs.read`.

---

### Task 1: Etapas `daily-statistics` e `end-of-day-reports` no fechamento

**Objetivo:** D1, D2 e D4, e o teste do *keep-alive* herdado da E10.

**Critérios de aceite:**
- `EndOfDayReportingStepsTest` (`@EndOfDayIntegrationTest`, novo): um fechamento com as etapas reais grava, para a data que fecha, uma linha de `reporting.daily_statistics` e um `DAY_REPORT` em `reporting.archived_report` com o anexo sensível (`files`, `encryption_key_id` não nulo), com `daily-statistics` e `end-of-day-reports` concluídas antes de `advance-business-date` (ordem por `endofday.run_step`); repetir as duas etapas para a mesma data (chamada direta dos handlers com a mesma data, simulando a retomada de etapa `RUNNING`) não cria segunda linha nem segundo anexo (Review Focus 2); registra as durações das etapas 800 e 900 na saída do teste (D4).
- `EndOfDayReportsFailureTest` (`@EndOfDayIntegrationTest`, novo), com uma sonda `PrintingRenderProbe` (`BeanPostProcessor` sobre a `PrintingApi`, no padrão de `BillingPostProbe` e `RoomsMarkProbe`, registrada em `EndOfDayRealStepsTestConfiguration`, armada pelo teste e desarmada por `EndOfDayTestRuns.settle()`) que faz o render lançar: a execução fica `FAILED` em `end-of-day-reports`, a data operacional não avança, nenhum `archived_report` da data; desarmada a sonda, a retomada (`POST …/runs/{id}/resume` com aprovação) conclui, a data avança, há um `DAY_REPORT` e o snapshot é o mesmo da primeira passagem (`created_at` igual) (Review Focus 1).
- `EndOfDayKeepAliveTest` (unidade, novo): com o `KeepAliveLockProvider` como montado em `EndOfDayExecutionConfiguration` e `lockAtMostFor` de 30 s sobre um `LockProvider` falso que registra as extensões e um agendador controlável, uma tarefa que dura além do `lockAtMostFor` tem a trava estendida antes de vencer e uma segunda tentativa na mesma janela é recusada (Review Focus 5).
- `ModularityTest` com a lista exata do D2; `EndOfDayRealRunTest.STEPS` com as duas etapas (ruling).
- Verificação: `timeout 2400 ./gradlew test --tests '*EndOfDayKeepAliveTest' --tests '*ModularityTest' --tests 'com.zeny.hotel.endofday.*' --tests '*HousekeepingEndOfDayTasksTest' --tests '*AssignmentPlanConcurrencyTest' --tests 'com.zeny.hotel.testsupport.EndOfDay*'` e `timeout 900 ./gradlew checkOpenApi lint`.

**Áreas tocadas:** `endofday/application/steps/DailyStatisticsStep.kt` (novo), `endofday/application/steps/EndOfDayReportsStep.kt` (novo), `endofday/EndOfDayModule.kt`, `docs/ARCHITECTURE.md` (§3.1 linha `endofday` e §13 linha do fechamento); testes: os três novos, `endofday/PrintingRenderProbe.kt` (novo), `endofday/EndOfDayRealStepsTestConfiguration.kt`, `endofday/EndOfDayTestRuns.kt`, `EndOfDayRealRunTest` (só `STEPS`) e o `ModularityTest`.

**Interfaces:**
- Consome: `ReportingApi.snapshotDailyStatistics(date: LocalDate): DailyStatisticsSnapshot` e `ReportingApi.archiveDayReports(date: LocalDate): List<ArchivedReport>` (E23).
- Produz: as etapas `daily-statistics` (800) e `end-of-day-reports` (900) registradas; `PrintingRenderProbe` com `failRender()` e `reset()`.

**Tamanho:** ~55 min; 3 arquivos de produção (mais as linhas da ARCHITECTURE).

**Depende de:** nenhuma (pré-condição: E23 `#concluida`).

### Task 2: Pendências da RF-FEC-04 como `EndOfDayIssue` e o fechamento anterior à etapa

**Objetivo:** D3 e o critério "um fechamento executado antes desta etapa existir continua válido".

**Critérios de aceite:**
- `EndOfDayPendingItemsTest` (`@EndOfDayIntegrationTest` com `@Import(EndOfDayRealStepsTestConfiguration::class)`, para reaproveitar o contexto em cache, novo, no pacote `reporting`: o banco isolado, para não deixar conta com saldo nem caixa aberto no banco compartilhado; o que ele deixa gera pendências e avisos em todo fechamento seguinte do banco isolado, aceito pela regra "afirma só sobre os próprios dados"): `ReportingApi.endOfDayPendingItems()` devolve, para uma conta aberta com saldo de reserva `CHECKED_OUT`, um turno de caixa aberto e uma submissão FNRH `FAILED` criados pelo teste (afirmação pelos ids do próprio teste), um item de cada tipo com a referência do D3 e o `detail` sem o nome do hóspede, sem o nome do ponto de caixa, sem o saldo e sem o `lastFailure` (gravados com marcador e conferidos ausentes) (Review Focus 4); `ReportingViewsOnlyTest` segue verde.
- `EndOfDayPendingIssuesTest` (`@EndOfDayIntegrationTest`, novo): depois de um fechamento com as mesmas três pendências, `GET /api/v1/end-of-day/runs/{id}/issues` traz `FOLIO_OPEN_BALANCE`, `CASHIER_SHIFT_OPEN` e `FNRH_SUBMISSION_PENDING` com as referências do teste, `stepName` `end-of-day-pending`, mensagens no formato do D3 e nenhum marcador; a etapa `end-of-day-pending` sai `DONE`; o aviso `notifications.end-of-day-pending` gravado para um usuário com `endofday.runs.read` não traz número de conta, código de reserva nem nada do `detail` (Review Focus 4).
- `EndOfDayPreE13RunTest` (`@EndOfDayIntegrationTest`, novo): uma execução `RUNNING` gravada com as etapas até `occupied-rooms-dirty` concluídas e sem as linhas de 800, 900 e 950 (como uma execução aberta antes do deploy) é retomada e conclui com as três executadas antes de `advance-business-date` (Review Focus 3); e, para uma data `COMPLETED` sem snapshot (execução concluída gravada sem as etapas novas), `GET /api/v1/reports/day-panel`, `GET /api/v1/reports/end-of-day-pending`, `GET /api/v1/reports/housekeeping?from=&to=` e `GET /api/v1/reports/archive?date=` respondem `200` (o arquivo com lista vazia).
- `EndOfDayRealRunTest.STEPS` com `end-of-day-pending` (ruling).
- Verificação: `timeout 2400 ./gradlew test --tests 'com.zeny.hotel.reporting.*' --tests '*ModularityTest' --tests 'com.zeny.hotel.endofday.*' --tests '*HousekeepingEndOfDayTasksTest' --tests '*AssignmentPlanConcurrencyTest' --tests 'com.zeny.hotel.testsupport.EndOfDay*'` e `timeout 900 ./gradlew checkOpenApi lint`.

**Áreas tocadas:** `reporting/ReportingApi.kt` (operação e tipos do D3), `reporting/application/ReportingApiService.kt`, `endofday/application/steps/EndOfDayPendingStep.kt` (novo), `endofday/EndOfDayModule.kt` (KDoc), `endofday/application/EndOfDaySteps.kt` (KDoc da posição: a exceção 950, pendências da RF-FEC-04, fora da numeração da RF-FEC-02); testes no `reporting` e no `endofday`.

**Interfaces:**
- Consome: `EndOfDayPendingService.loadWithoutAuthorization(): EndOfDayPending` (E09/E23); as etapas da Task 1.
- Produz: `ReportingApi.endOfDayPendingItems(): List<EndOfDayPendingItem>`, `EndOfDayPendingKind`, `EndOfDayPendingItem(kind, reference: UUID, detail: String)`; a etapa `end-of-day-pending` (950).

**Tamanho:** ~50 min; 5 arquivos de produção (dois só de KDoc).

**Depende de:** Task 1 (`EndOfDayRealRunTest.STEPS` e o registro das etapas 800 e 900, que o `EndOfDayPreE13RunTest` confere).
