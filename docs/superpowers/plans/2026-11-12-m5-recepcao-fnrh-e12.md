# M5 E12 — Gate do marco M5 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** provar, numa cópia limpa e num `docker compose` isolado, que a "Definição global de pronto" do M5 é atendida, com o teste ponta a ponta do marco (`MilestoneM5EndToEndTest`) e um roteiro `curl` versionado, sem funcionalidade nova.

**Architecture:** a etapa só acrescenta evidência: um teste de integração na raiz do pacote de testes, que usa os módulos reais (só a FNRH em WireMock) com os modelos de role do boot; um roteiro de compose em `docker/gate/` (env sorteado fora do repositório, override com um WireMock da FNRH na rede do projeto, roteiro `curl`/`jq` pelo proxy, `down -v` garantido); e a revisão do `README.md`. A cópia limpa, o oasdiff, o scanner e o compose a partir da cópia são rodados pelo orquestrador (seção "Verificação do gate").

**Tech Stack:** Kotlin 2.4, JDK 25, Spring Boot 4.1, JUnit 5, MockMvc, WireMock 3.13.2, Testcontainers 2; bash, `curl`, `jq`, Docker Compose, imagem `wiremock/wiremock` 3.13.2.

**Spec:** `docs/roadmaps/roadmap-m5-recepcao-fnrh.md`, seção "E12 — Gate do marco M5", mais a "Definição global de pronto", "Restrições e invariantes" e os handoffs de E01 a E11 do mesmo arquivo; modelo do roteiro no handoff da E13 de `docs/roadmaps/roadmap-m4-tarifas-reservas.md`.

## Global Constraints

- Sem funcionalidade nova (E12 "Fora de escopo"). Defeito de produção achado pelo gate é corrigido nesta etapa como rodada de correção, com ruling no ledger e nota no handoff da etapa de origem, **sem mudar a tag dela** (o prompt da execução proíbe alterar tags de etapas não executadas; isso substitui o "reabrir com `#em-andamento`" da E12).
- Grafo da ARCHITECTURE §3.1 intacto: nenhuma aresta nova; `frontdesk` não chama `fnrh`; `printing` não conhece modelos de negócio.
- Falha da FNRH nunca bloqueia check-in nem check-out (RF-FNRH-03).
- Dados sensíveis da FNRH (RN-19) e o número inteiro dos documentos nunca aparecem em respostas, logs, trilha, eventos, notificações, views nem documentos impressos.
- Ações sensíveis exigem permissão ou aprovação de superior (RN-12): desfazer check-in.
- `@Idempotent` nos POSTs com efeito financeiro ou externo: cada chamada com `Idempotency-Key` própria.
- Credenciais da FNRH e token de agente nunca no repositório, no log nem na resposta (o token só na criação/rotação).
- RN-01: data operacional pela `HotelApi.currentBusinessDate()` (no teste) ou `GET /api/v1/hotel/business-date` (no roteiro), lida na hora em que é usada.
- RN-13: eventos, notificações e trilha sem nome, documento ou contato de hóspede.
- Testes: banco compartilhado; cada teste cria os próprios tipos de UH, UHs, planos e hóspedes; o que muda configuração global (FNRH, perfil do hotel, política) devolve o original no fim; relógio do `MutableClock` devolvido no fim.
- Máquina de 14 GB: um Gradle (ou um `docker build`) por vez; `./gradlew --stop` entre execuções longas.
- Nenhuma migração nova.
- Commits com o trailer `Soul: <soul>` (desenvolvedor ou testador) e a linha `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`; mensagens em pt-BR; código, nomes de teste e API em inglês (D17).
- O `docker/.env` do usuário nunca é lido nem tocado; o projeto do compose é sempre `-p` próprio, com `down -v` no fim, também em falha.

## Review Focus

1. **Data operacional que anda no meio do teste:** outros testes da JVM avançam a data operacional; datas exibidas no PDF, nas listas e nas submissões são comparadas com o valor lido no mesmo passo (nota da E11). Teste: Task 1, passo 1, assere as datas do PDF da ficha contra a reserva lida, não contra uma data guardada no início.
2. **WireMock da FNRH compartilhado:** o `FnrhTestServer` é um só por JVM; contar ou ordenar chamadas de outros testes faria o teste falhar ou passar por acaso. Teste: Task 1 e Task 2 filtram as requisições pelo id remoto ou pelo código da reserva do próprio teste e só enviam as submissões dela (como o `FnrhStageEndToEndTest`).
3. **Configuração global alterada e não devolvida:** ligar a FNRH, mudar o perfil do hotel ou a política deixaria outros testes vermelhos em ordem aleatória. Teste: Task 1 restaura em `@AfterEach`/`finally` (`resetFnrhSettings`, perfil e política originais) e o Task 2 também.
4. **Roteiro que passa sem conferir:** um `curl` sem `-f`/conferência de status ou um `jq` que devolve `null` deixam o roteiro verde com a API quebrada. Teste: Task 4 usa uma função `check` que compara status e valor esperado e conta falhas; o roteiro sai com código ≠ 0 se houver falha, e um `down -v` roda por `trap` em qualquer saída.
5. **Dado de hóspede vazando no compose `prod`:** logs JSON do `api` com nome, documento, credencial da FNRH ou token de agente. Teste: Task 4 procura nos logs de todos os serviços o nome do hóspede, o número do documento, a senha da FNRH e o token `zpa_` do agente e falha se achar.

---

## Mapa da "Definição global de pronto" → evidência

| Item da definição | Evidência |
|---|---|
| Check-in numa transação (`409 ROOM_NOT_READY`, `409 ROOM_NOT_ASSIGNED`, `422 MINOR_AUTHORIZATION_REQUIRED`, rollback, `IN_HOUSE` + UH Ocupada + `GuestCheckedIn`) | Task 1 (recusas e sucesso), testes da E02 na cópia limpa, roteiro (Task 4) |
| Desfazer check-in sensível, mesma data, trilha com solicitante e aprovador | Task 2, roteiro |
| Walk-in, check-out, troca de UH, extensão e redução, early/late, recados no contrato e com testes | Task 1, Task 2, testes de E03 a E06 na cópia limpa, roteiro, `api/openapi.yaml` |
| Eventos → `FnrhSubmission` enviadas (WireMock), retry, ordem, ids remotos, pendências, alerta sem dado de hóspede; Serpro fora do ar não bloqueia | Task 1, Task 2, `FnrhStageEndToEndTest`, roteiro com o WireMock da Task 3 |
| Agente recebe só os próprios jobs, confirma, reenvio, token só hash | Task 1 (ficha recebida e confirmada), testes da E10, roteiro (agente por `curl`) |
| Ficha, extrato e confirmação em PDF e em job, dados do hotel pela `HotelApi`, sem RN-19 nem documento inteiro | Task 1, `DocumentsStageEndToEndTest`, roteiro |
| `ApplicationModules.verify()`, `ModularityTest`, §3.1; build, testes, `checkOpenApi`, oasdiff sem `ERR` contra `ffae917` | cópia limpa (orquestrador) |
| Compose mostra as publicações dos eventos de reserva e de hospedagem concluídas pelo listener do `fnrh` | roteiro (Task 4) |
| E09 concluída ou pendência obrigatória registrada | handoff da E12 (orquestrador) |
| `gradle.lockfile` versionado; `osv-scanner` lista os pacotes | scanner na revisão final (orquestrador) |
| Sem High/Critical salvo risco aceito (E14) | scanner na revisão final (orquestrador) |
| Gênero `OUTRO` com descrição enviado (E15); credencial do Serpro apagável com trilha (E16) | Task 2, roteiro |

---

### Task 1: `MilestoneM5EndToEndTest` — recepção completa de uma reserva

**Estimativa:** 45 min · **Depende de:** nenhuma · **Risco:** alto (núcleo do gate)

**Files:**
- Create: `src/test/kotlin/com/zeny/hotel/MilestoneM5EndToEndTest.kt`

**Interfaces:**
- Consumes: `IntegrationTest`, `IamTestConfiguration`/`IamTestSupport` (roles pela `template_key` como em `MilestoneM4EndToEndTest`, `setPin`, `call(…, approvalToken)`), `InventoryTestConfiguration`, `MutableClockConfiguration`, `FnrhTestConfiguration` + `FnrhTestServer` + `IamTestSupport.enableFnrh` + `JdbcClient.resetFnrhSettings` (como em `FnrhStageEndToEndTest`), `FrontdeskTestSupport`, `RoomsTestSupport`, `GuestsTestSupport`, `BillingTestSupport`, `ReservationsTestSupport`, `RatesTestSupport`, `PdfText`, e o agente por long-poll do `DocumentsStageEndToEndTest` (as classes `Agent`/`Stay` são privadas lá: replicar o mínimo no teste novo, sem mexer no teste da E11).
- Produces: a classe `MilestoneM5EndToEndTest` (`@IntegrationTest`, na raiz `com.zeny.hotel`) com o teste `` `a guest arrives, is registered with FNRH, has the registration card printed and leaves with a zero balance` `` e helpers privados reaproveitáveis pela Task 2: o operador por modelo de role (`receptionist`, `manager`), `newStay(...)`, `sendOwnFnrhSubmissions(reservationId)` e `agentLongPoll(...)`.

- [ ] **Step 1: Escrever o teste do cenário principal.** Um único `@Test`, com os **modelos de role do boot** (Recepcionista e Gerente pela `template_key`; o Gerente com PIN; um usuário de caixa só se a Recepcionista não puder receber pagamento com turno aberto — conferir no catálogo e registrar no relatório), na ordem:
  1. O Gerente cadastra o tipo de UH e duas UHs do mesmo tipo (prontas: inspecionadas), o plano e os preços (apoio de `RatesTestSupport`/`RatePricesTestSupport`), a impressora `A4_PDF` e um agente (`POST /api/v1/print-agents`, token guardado só em memória); a FNRH é ligada pelo `enableFnrh` com os stubs v2.4 do `FnrhTestServer`.
  2. A Recepcionista cria a reserva (titular adulto brasileiro com CPF, um menor com o adulto como responsável) e aloca a UH 1. Antes da UH pronta: `409 ROOM_NOT_READY` (UH marcada suja pela `RoomsApi`/endpoint e depois inspecionada); sem UH alocada noutra reserva do teste: `409 ROOM_NOT_ASSIGNED`; menor sem comprovação: `422 MINOR_AUTHORIZATION_REQUIRED`. Depois de cada recusa: reserva `CONFIRMED`, nenhuma caução na conta, nenhum lançamento novo, UH não ocupada.
  3. Check-in com o menor autorizado (comprovação "autorização escrita" com anexo, como no `FrontdeskCheckInApiTest`) e caução (`FrontdeskTestSupport.preAuthorization()`): `200`, reserva `IN_HOUSE`, UH Ocupada com 2 hóspedes, caução ativa na conta, publicação de `GuestCheckedIn` no outbox.
  4. `sendOwnFnrhSubmissions`: as submissões da reserva (`ReservationCreated` e o check-in) ficam `SENT`, com os ids remotos guardados; as chamadas ao WireMock **desta reserva** chegam em ordem (registro antes do check-in).
  5. Ficha: `POST /api/v1/reservations/{id}/registration-card/print` na `A4_PDF` → `202` com `printJobId`; o agente recebe o job pelo long-poll, baixa o conteúdo (PDF), confirma (`ack` `PRINTED`) e o job fica `PRINTED`; o texto do PDF (`PdfText`) tem o nome do titular, a UH e as datas da reserva lida neste passo, o documento **mascarado** e não tem o número inteiro do CPF.
  6. Troca de UH para a UH 2 (`POST …/room-moves` com `If-Match`, `tariff` `KEEP`): UH 1 Vaga e Suja, UH 2 Ocupada, `RoomMoved` publicada.
  7. Lançamento manual (`POST /api/v1/folios/{id}/postings`) e pagamento manual do saldo com turno de caixa aberto.
  8. Extrato: `GET /api/v1/folios/{id}/statement/pdf` → `200 application/pdf` com o lançamento e o saldo `0,00`.
  9. Check-out (`POST …/check-out`): `200`, caução liberada, contas encerradas (`BillingApi.balanceOf(...).settled` e `closed`), reserva `CHECKED_OUT`, UH 2 Vaga e Suja, alocação encerrada; `sendOwnFnrhSubmissions` deixa a submissão do check-out `SENT` e a chamada de check-out do WireMock é a última desta reserva.
  10. Privacidade: nenhuma linha de `audit` da reserva, publicação do outbox, notificação ou `fnrh.report_submissions` contém o nome do titular nem o número do CPF.
  No fim (em `finally`/`@AfterEach`): `resetFnrhSettings`, `FnrhTestServer.reset()`, relógio devolvido.

- [ ] **Step 2: Rodar e ver passar.** Run: `./gradlew test --tests 'com.zeny.hotel.MilestoneM5EndToEndTest'` → `BUILD SUCCESSFUL`, 1 teste, 0 falhas. Se um passo falhar por defeito de produção (não do teste), parar e relatar `DONE_WITH_CONCERNS`/`BLOCKED` com a evidência: a correção é decidida pelo orquestrador.

- [ ] **Step 3: Provar que o teste morde** (mutações temporárias, código restaurado e `git diff` limpo depois): sem a liberação das cauções no check-out → o teste falha no passo 9; com o envio do `fnrh` sem efeito → falha no passo 4. Registrar no relatório a mensagem de cada falha.

- [ ] **Step 4: `./gradlew ktlintFormat lint`** → `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/test/kotlin/com/zeny/hotel/MilestoneM5EndToEndTest.kt
git commit -m "Acrescenta o teste ponta a ponta do marco M5: recepção completa (M5 E12)" -m "Soul: desenvolvedor" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 2: `MilestoneM5EndToEndTest` — walk-in, desfazer check-in e Serpro fora do ar

**Estimativa:** 40 min · **Depende de:** Task 1 · **Risco:** alto (núcleo do gate)

**Files:**
- Modify: `src/test/kotlin/com/zeny/hotel/MilestoneM5EndToEndTest.kt`

**Interfaces:**
- Consumes: os helpers privados da Task 1 (`receptionist`, `manager`, `newStay`, `sendOwnFnrhSubmissions`, `agentLongPoll`).
- Produces: três testes novos na mesma classe.

- [ ] **Step 1: Teste `` `a walk-in guest of gender OTHER with a description is checked in and reaches FNRH` ``.** A Recepcionista faz o walk-in (`POST /api/v1/walk-ins`, hóspede cadastrado com gênero `OUTRO` e a descrição da E15, UH inspecionada) → `201`, reserva `IN_HOUSE`, UH Ocupada; `sendOwnFnrhSubmissions` deixa `ReservationCreated` e o check-in `SENT`, e o corpo da pessoa enviado ao WireMock tem a descrição de gênero; a confirmação (`GET …/confirmation/pdf`) traz os horários de check-in e check-out da `HotelApi.policy()` lida no passo.

- [ ] **Step 2: Teste `` `undoing a check-in needs a superior's approval on the same business date and is audited with both users` ``.** Check-in de uma reserva; a Recepcionista desfaz → `403 APPROVAL_REQUIRED`; o Gerente aprova com PIN; com o `X-Approval-Token` → `200`, reserva `CONFIRMED`, UH Vaga sem sujar; a trilha `frontdesk` do desfazer tem a Recepcionista como solicitante e o `approval_id`, e `iam.approval` tem solicitante e aprovador. Depois de mover a data operacional (relógio + fechamento não existe no M5: usar o mesmo recurso do `FrontdeskUndoCheckInApiTest` para a data seguinte), outro desfazer → `422 UNDO_CHECK_IN_EXPIRED`; devolver a data/relógio no fim.

- [ ] **Step 3: Teste `` `with Serpro down the check-in concludes and the failure alert carries no guest data` ``.** WireMock da FNRH respondendo `503` para as rotas desta reserva; check-in → `200` e reserva `IN_HOUSE`; `sendOwnFnrhSubmissions` deixa a submissão pendente com tentativa gasta e próxima tentativa agendada (não `SENT`); `GET /api/v1/fnrh/submissions?status=…` (Recepcionista) a lista sem dado de hóspede; forçando o limite (tentativas pelo apoio do `FnrhStageEndToEndTest` ou `max-attempts` do contexto de teste), a submissão vira `FAILED` e a notificação `notifications.integration-failure` do Gerente tem só o código da reserva (sem nome nem documento); com o WireMock de volta e `POST …/{id}/retry` do Gerente, a submissão vira `SENT` sem duplicar o registro remoto.

- [ ] **Step 4: Rodar.** Run: `./gradlew test --tests 'com.zeny.hotel.MilestoneM5EndToEndTest'` → 4 testes, 0 falhas. Rodar duas vezes seguidas (`--rerun`) para pegar dependência de ordem.

- [ ] **Step 5: Provar que morde:** sem a exigência de aprovação no desfazer (`requireOrApproval` trocado por `require` sem fallback, ou a permissão dada à Recepcionista) → o Step 2 falha no `403`; sem a descrição de gênero no corpo enviado → o Step 1 falha. Código restaurado.

- [ ] **Step 6: `./gradlew ktlintFormat lint`** → verde. **Commit** com a mensagem `Completa o teste ponta a ponta do marco M5: walk-in, desfazer check-in e Serpro fora do ar (M5 E12)` e os trailers da Task 1.

### Task 3: Infraestrutura do compose isolado do gate (`docker/gate/`)

**Estimativa:** 40 min · **Depende de:** nenhuma · **Risco:** normal (ferramenta de verificação, fora da imagem e da CI)

**Files:**
- Create: `docker/gate/gate-env.sh` — gera, num diretório passado como argumento (nunca dentro do repositório), um `--env-file` a partir de `docker/.env.example` com chaves, senhas do banco, do MinIO e do admin sorteadas por `openssl`, `ZENY_HTTP_PORT` e `ZENY_STORAGE_PUBLIC_ENDPOINT=http://localhost:<porta>` pela porta passada, `ZENY_PROFILE=prod`, `ZENY_FCM_CREDENTIALS` vazia; imprime o caminho do arquivo. Recusa (`exit 2`) um destino dentro do repositório.
- Create: `docker/gate/compose.gate.yaml` — override para `-f docker/compose.yaml -f docker/gate/compose.gate.yaml`: serviço `fnrh-mock` (`wiremock/wiremock:3.13.2`, sem porta publicada, com `docker/gate/fnrh-mock/` montado só leitura) e, no `api`, a URL de homologação da FNRH apontada para ele (a propriedade `zeny.fnrh.homologation-url` de `FnrhProperties`, pela variável de ambiente do relaxed binding) e `depends_on` do mock.
- Create: `docker/gate/fnrh-mock/mappings/*.json` — stubs da API v2.4 equivalentes aos do `FnrhTestServer` (registrar e consultar reserva, listar hóspedes, consultar pessoa por documento, cadastrar pessoa, incluir hóspede/check-in, check-out, cancelar, domínios), com a mesma verificação estrita de cabeçalhos v2.4 que o `strictV24` exige quando possível em JSON; o mock nunca guarda dado real.
- Create: `docker/gate/README.md` — uso (gerar o env, subir, rodar o roteiro, derrubar com `down -v`), requisitos (`docker`, `curl`, `jq`, `openssl`) e o aviso de não usar o `docker/.env`.

**Interfaces:**
- Produces: `docker/gate/gate-env.sh <dir> <porta>` → caminho do env; o projeto `-p <nome>` e os dois `-f` usados pela Task 4; o hostname `fnrh-mock` na rede do projeto; a URL `http://fnrh-mock:8080/<caminho base igual ao de homologação>`.

- [ ] **Step 1:** Escrever os arquivos. Conferir com `bash -n docker/gate/gate-env.sh`, `shellcheck` se existir na máquina (não instalar), e `docker compose -p zeny-e12-smoke --env-file <env> -f docker/compose.yaml -f docker/gate/compose.gate.yaml config -q` → sem erro.
- [ ] **Step 2:** Subir de verdade (`up -d --build`, a partir da árvore de trabalho; um só build pesado por vez na máquina), esperar a readiness `{"status":"UP"}` pelo proxy na porta escolhida e, de dentro da rede (`docker compose exec api wget -qO- http://fnrh-mock:8080/__admin/mappings` ou um `curl` num container efêmero na rede), ver os stubs carregados. Derrubar com `down -v`. Colar a saída no relatório.
- [ ] **Step 3:** Se o WireMock na rede do projeto não for viável (motivo concreto), parar com `DONE_WITH_CONCERNS` e a evidência: o orquestrador decide o recuo previsto na E12 (a fila de pendências mostra a submissão e o envio fica nos testes).
- [ ] **Step 4: Commit** `Acrescenta o compose isolado do gate com o WireMock da FNRH (M5 E12)` com os trailers.

### Task 4: Roteiro `curl` do gate (`docker/gate/m5-roteiro.sh`)

**Estimativa:** 45 min · **Depende de:** Task 3 · **Risco:** alto (evidência do gate pelo proxy)

**Files:**
- Create: `docker/gate/m5-roteiro.sh` — recebe a URL base (`http://localhost:<porta>`), o projeto e o env; usa só `curl`, `jq` e `docker compose exec db psql` (dono, para ler `shared.event_publication` e conferir a trilha); toda conferência passa por uma função `check <descrição> <esperado> <obtido>` que conta OK e falhas e imprime uma linha por conferência; no fim imprime `N conferências, F falhas` e sai com `F > 0 → 1`.
- Modify: `docker/gate/README.md` — a seção do roteiro.

**Interfaces:**
- Consumes: Task 3 (`gate-env.sh`, `compose.gate.yaml`, `fnrh-mock`).
- Produces: `docker/gate/m5-roteiro.sh <base-url> <projeto> <env-file>` com exit 0 só sem falha; o log em stdout.

- [ ] **Step 1: Escrever o roteiro**, modelo no handoff da E13 do M4, em seções numeradas:
  1. Públicos: readiness 200, `meta/version` 200, `/actuator/prometheus` 404 pelo proxy, `/api/v1/reservations` sem token 401.
  2. Admin inicial com troca obrigatória; usuários `recepcao` (Recepcionista) e `gerente` (Gerente, com PIN) pelos modelos do boot; usuário de caixa se a Task 1 tiver precisado.
  3. Cadastro (Gerente): tipo de UH, UHs, inspeção, plano e preços; perfil do hotel; FNRH configurada (`PUT /api/v1/fnrh/settings` com credenciais **de teste** sorteadas no script, ambiente `HOMOLOGATION`, ligada) e CPF solicitante; agente (`POST /api/v1/print-agents`, token só em variável) e impressora `A4_PDF`.
  4. Reserva → alocação → check-in com o menor autorizado (anexo pelo fluxo de upload do `files`) e caução; `409 ROOM_NOT_READY` antes da inspeção.
  5. **FNRH:** espera (laço com teto, ≤ 3 min, o job roda a cada minuto) pelas submissões `SENT` da reserva em `GET /api/v1/fnrh/submissions`; `shared.event_publication` com o listener do `fnrh` `COMPLETED` para `ReservationCreated` e `GuestCheckedIn` (e depois `GuestCheckedOut`); `GET /api/v1/fnrh/status` com `lastSuccessAt`.
  6. **Ficha pelo agente:** `POST …/registration-card/print` → `202`; o agente simulado abre `GET /api/v1/print-agents/me/jobs/stream` por `curl -N` com tempo limite, lê o evento `job` com o id, baixa o conteúdo (`%PDF-`), confirma (`ack` `PRINTED`) e `GET /api/v1/print-jobs` mostra `PRINTED`; o JWT de usuário em `/api/v1/print-agents/me/identity` → 401 e o token do agente em `/api/v1/reservations` → 401.
  7. Troca de UH (`If-Match`), lançamento, turno de caixa, pagamento; extrato PDF (`%PDF-`), confirmação PDF.
  8. Check-out: contas encerradas, caução liberada, UH Vaga e Suja; a submissão do check-out `SENT`.
  9. Walk-in com hóspede de gênero `OUTRO` e descrição (E15); desfazer check-in: Recepcionista `403 APPROVAL_REQUIRED`, aprovação do Gerente, `200`, trilha com os dois.
  10. Recados: criar para um setor vinculado à reserva, listar, resolver; `409 TRACE_ALREADY_RESOLVED` na segunda resolução.
  11. Serpro fora do ar: `docker compose stop fnrh-mock`, check-in de outra reserva → `200` e `IN_HOUSE`, submissão não `SENT` com tentativa gasta; `start fnrh-mock`.
  12. E16: `POST /api/v1/fnrh/settings/clear-credentials` (`If-Match`) → `passwordSet=false`, integração desligada, trilha `fnrh.settings.clear-credentials`.
  13. Logs: `docker compose logs --no-color` de todos os serviços não contêm o nome do hóspede, o número do CPF/documento, a senha da FNRH nem o token `zpa_` do agente.
- [ ] **Step 2: Rodar** o ciclo completo a partir da árvore de trabalho: `gate-env.sh` num diretório do scratchpad, `up -d --build` com `-p zeny-e12-gate` e os dois `-f`, readiness, roteiro, `down -v` (por `trap` ou no fim, sempre). Esperado: `N conferências, 0 falhas` e exit 0. Colar o resumo e as linhas de cada seção no relatório; guardar o log completo no arquivo de relatório.
- [ ] **Step 3: Commit** `Acrescenta o roteiro curl do gate do M5 (M5 E12)` com os trailers.

### Task 5: `README.md` coerente com o código do M5

**Estimativa:** 20 min · **Depende de:** nenhuma · **Risco:** normal (documentação)

**Files:**
- Modify: `README.md` (seções "API", "Ambiente completo em Docker" e árvore de módulos)

- [ ] **Step 1:** Conferir cada item M5 da seção "API" contra o código e o `api/openapi.yaml` (rotas, permissões, códigos de erro, eventos); corrigir o que está defasado. Conhecidos: o item de encerramento e extrato diz "O PDF do extrato vem no M5" (já existe: apontar para o item "Documentos impressos"); o item "Transições de hospedagem" fala em "nas etapas seguintes do M5"; conferir se a descrição de gênero (E15) e a limpeza da credencial (E16) estão nos itens do `guests` e da FNRH e se a árvore de módulos lista `frontdesk`, `fnrh` e `printing`.
- [ ] **Step 2:** Em "Ambiente completo em Docker", uma frase apontando o `docker/gate/README.md` (Task 3) como o roteiro de verificação de gate.
- [ ] **Step 3: Commit** `Atualiza o README com o estado do M5 (M5 E12)` com os trailers.

---

## Verificação do gate (orquestrador, depois das tarefas e antes da revisão final)

Rodados pelo orquestrador, um Gradle por vez, com a saída inteira guardada no workspace e as linhas-chave no handoff:

1. Cópia limpa: `git archive <HEAD da branch> | tar -x -C <scratchpad>/e12-clean`, `GRADLE_USER_HOME=<scratchpad>/e12-gradle-home` novo; `./gradlew --no-daemon --no-build-cache clean build`; `clean test` duas vezes mais (três rodadas dos testes no total); `lint checkOpenApi`.
2. `.github/scripts/check-api-breaking.sh ffae917` → nenhuma mudança `ERR`.
3. Compose a partir da cópia limpa: `gate-env.sh`, `up -d --build`, `m5-roteiro.sh`, `down -v` → 0 falhas.
4. `osv-scanner scan source -r --no-ignore .` (linha do stack na tabela da `revisor-seguranca`) → pacotes listados, 0 Critical e 0 High fora dos riscos aceitos da E14.
5. `check_test_integrity.py --base <MERGE_BASE>`.

## Fora deste plano (registrado para o handoff)

- A ordem interna da camada Hospedagem na ARCHITECTURE §3.2 (nota do handoff da E03) é mudança de regra de arquitetura: vai ao usuário como decisão de arquitetura, não ao gate.
- O estado da E09 (`#bloqueada` pela Q-12, pendência obrigatória antes da operação real, cobrada pelo M8 E13) e a conclusão do M5 em `docs/roadmaps/README.md`: documentos do orquestrador no fechamento.
- A captura de atualização do brain (com a revisão das notas e das capturas pendentes contra o código e o estado do marco em `ZenyHotelManager - Estado por marco`): `brain-escriba`, no fechamento.
