# M5 E12 — Gate do marco M5 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** provar, numa cópia limpa e num `docker compose` isolado, que a "Definição global de pronto" do M5 é atendida, com o teste ponta a ponta do marco (`MilestoneM5EndToEndTest`) e um roteiro `curl` versionado, sem funcionalidade nova.

**Architecture:** a etapa só acrescenta evidência: um teste de integração na raiz do pacote de testes, que usa os módulos reais (só a FNRH em WireMock) com os modelos de role do boot; um compose isolado em `docker/gate/` (env sorteado fora do repositório, override com um WireMock da FNRH na rede do projeto, roteiro `curl`/`jq` pelo proxy, `down -v` garantido); a revisão do `README.md`; e uma auditoria só de leitura das notas do brain contra o código. A cópia limpa, os lockfiles, o oasdiff, o scanner e o compose a partir da cópia são rodados pelo orquestrador ("Verificação do gate").

**Tech Stack:** Kotlin 2.4, JDK 25, Spring Boot 4.1, JUnit 5, MockMvc, WireMock 3.13.2, Testcontainers 2; bash, `curl`, `jq`, `openssl`, Docker Compose, imagem `wiremock/wiremock:3.13.2@sha256:0d4ecb3e4dc8213fd7a4d37d6a78f6e6b553a6d2e15bd51b0999781282ac61b3`.

**Spec:** `docs/roadmaps/roadmap-m5-recepcao-fnrh.md`, seção "E12 — Gate do marco M5", mais a "Definição global de pronto", "Restrições e invariantes" e os handoffs de E01 a E11 do mesmo arquivo; modelo do roteiro no handoff da E13 de `docs/roadmaps/roadmap-m4-tarifas-reservas.md` (inclusive o parecer do verificador, Menor 2).

## Global Constraints

- Sem funcionalidade nova (E12 "Fora de escopo"). **Nenhuma mudança no catálogo de permissões nem nos modelos de role**; nenhuma migração nova; nenhuma aresta nova na ARCHITECTURE §3.1.
- Defeito de produção achado pelo gate: o implementador para e relata (`DONE_WITH_CONCERNS`/`BLOCKED`) com a evidência; não corrige sozinho. O orquestrador decide (ruling R2): correção pequena sem mudar contrato, migração ou catálogo entra nesta etapa como rodada de correção, com nota no handoff da etapa de origem e **sem mudar a tag dela**; o que mudar contrato, migração ou catálogo para o lote.
- `fnrh.settings.manage` e `printing.agents.manage` são **só do Administrador** (sem modelo de role, H20): configurar e limpar a FNRH, o CPF solicitante, o agente e a impressora são feitos pelo administrador inicial (`asAdmin()` nos testes).
- `frontdesk` não chama `fnrh`; `printing` não conhece modelos de negócio.
- Falha da FNRH nunca bloqueia check-in nem check-out (RF-FNRH-03).
- Dados sensíveis da FNRH (RN-19) e o número inteiro dos documentos nunca aparecem em respostas, logs, trilha, eventos, notificações, views nem documentos impressos.
- Ações sensíveis exigem permissão ou aprovação de superior (RN-12): desfazer check-in.
- `@Idempotent` nos POSTs com efeito financeiro ou externo: cada chamada com `Idempotency-Key` própria.
- Credenciais da FNRH, senhas, PIN, access tokens e token de agente nunca no repositório, no log, na saída do roteiro nem em relatório (o token do agente só na criação/rotação, guardado em variável).
- RN-01: data operacional pela `HotelApi.currentBusinessDate()` (teste) ou `GET /api/v1/hotel/business-date` (roteiro), lida no passo em que é usada. **Nunca avançar a data operacional do banco compartilhado** nos testes.
- RN-13: eventos, notificações e trilha sem nome, documento ou contato de hóspede.
- Testes: banco compartilhado; cada teste cria os próprios tipos de UH, UHs, planos e hóspedes; o que muda configuração global (FNRH, perfil do hotel, política de early/late) devolve o original no fim; `FnrhTestServer.reset()` no início e no fim; relógio do `MutableClock` devolvido no fim (padrão REWIND de `FnrhReservationSendTestBase.kt`); sem contexto de teste novo (reaproveitar as combinações de `@Import` existentes, como as de `FnrhStageEndToEndTest` e `FnrhSettingsClearSendTest`).
- Máquina de 14 GB: um Gradle (ou um `docker build`) por vez; `./gradlew --stop` entre execuções longas.
- Commits com o trailer `Soul: <soul>` e a linha `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`; mensagens em pt-BR; código, nomes de teste e API em inglês (D17).
- O `docker/.env` do usuário nunca é lido nem tocado; o compose do gate usa sempre `-p` próprio, `ZENY_HTTP_ADDRESS=127.0.0.1`, uma tag de imagem própria (não `zeny-hotel-manager:dev`) e `down -v` no fim, também em falha.

## Review Focus

1. **Data operacional que anda no meio do teste:** outros testes da JVM avançam a data operacional; datas exibidas no PDF, nas listas e nas submissões são comparadas com o valor lido no mesmo passo (nota da E11). Teste: Task 2 assere as datas do PDF da ficha contra a reserva lida no passo.
2. **WireMock da FNRH compartilhado:** o `FnrhTestServer` é um só por JVM. Teste: Tasks 1 a 3 filtram as requisições pelos ids remotos ou pelo código da reserva do próprio teste e só enviam as submissões dela (como o `FnrhStageEndToEndTest`).
3. **Configuração global alterada e não devolvida:** FNRH, perfil do hotel e política de early/late. Teste: Tasks 1 a 3 restauram em `finally`/`@AfterEach` (`resetFnrhSettings`, política original por `FrontdeskTestSupport.putPolicy`, perfil original).
4. **Roteiro que passa sem conferir:** um `curl` sem conferência de status ou um `jq` que devolve `null`. Teste: Task 5 e 6 usam `check` que compara status e valor esperado, conta falhas e sai ≠ 0 com falha; `down -v` por `trap`.
5. **Segredo ou dado de hóspede vazando no compose `prod`:** logs do `api`, `db`, `proxy`, `storage` e saída do próprio roteiro. Teste: Task 6 varre os logs (exceto o `fnrh-mock`, que faz o papel do Serpro e recebe os dados por desenho) pelos valores listados nela; o `check` nunca imprime valores de segredo.

---

## Mapa da "Definição global de pronto" → evidência

| Item da definição | Evidência |
|---|---|
| Check-in numa transação (`409 ROOM_NOT_READY`, `409 ROOM_NOT_ASSIGNED`, `422 MINOR_AUTHORIZATION_REQUIRED`, rollback, `IN_HOUSE` + UH Ocupada + `GuestCheckedIn`) | Task 1, testes da E02 na cópia limpa, roteiro (Task 5) |
| Desfazer check-in sensível, mesma data, trilha com solicitante e aprovador | Task 3, roteiro (Task 6) |
| Walk-in, check-out, troca de UH, extensão e redução do hospedado, early/late com o preço informado antes, recados: endpoints no contrato e testes | Task 1 (early), Task 2 (troca, extensão/redução, late, check-out), Task 3 (walk-in), testes de E03 a E06 na cópia limpa, roteiro (Tasks 5 e 6, recados na 6), `api/openapi.yaml` |
| Eventos → `FnrhSubmission` enviadas (WireMock), retry, ordem, ids remotos, pendências, alerta sem dado de hóspede; Serpro fora do ar não bloqueia | Tasks 1 a 3, `FnrhStageEndToEndTest`, roteiro com o WireMock da Task 4 |
| Agente recebe só os próprios jobs, confirma, reenvio, token só hash | Task 2 (ficha recebida e confirmada), testes da E10 (reenvio, isolamento), roteiro (agente por `curl`, `psql` sem o token no schema `printing`) |
| Ficha, extrato e confirmação em PDF e em job, dados do hotel pela `HotelApi`, sem RN-19 nem documento inteiro | Tasks 2 e 3, `DocumentsStageEndToEndTest`, roteiro |
| `ApplicationModules.verify()`, `ModularityTest`, arestas na §3.1; build, testes, `checkOpenApi`, oasdiff sem `ERR` contra `ffae917` | cópia limpa e conferência das `allowedDependencies` × §3.1 (orquestrador) |
| Compose mostra as publicações dos eventos de reserva e de hospedagem concluídas pelo listener do `fnrh` | roteiro (Task 5) |
| E09 concluída ou pendência obrigatória registrada | handoff da E12 (orquestrador) |
| `gradle.lockfile` versionado e atualizado; `osv-scanner` lista os pacotes | `--write-locks` na cópia limpa sem diferença + scanner (orquestrador) |
| Sem High/Critical salvo risco aceito (E14) | scanner (orquestrador) |
| Gênero `OUTRO` com descrição enviado (E15); credencial do Serpro apagável com trilha (E16) | Task 3, roteiro (Task 6) |

---

### Task 1: `MilestoneM5EndToEndTest` — preparo, recusas, check-in com early e FNRH

**Estimativa:** 45 min · **Depende de:** nenhuma · **Risco:** alto (núcleo do gate)

**Files:**
- Create: `src/test/kotlin/com/zeny/hotel/MilestoneM5EndToEndTest.kt`

**Interfaces:**
- Consumes: `IntegrationTest`; a combinação de `@Import` do `FnrhStageEndToEndTest` (`IamTestConfiguration`, `InventoryTestConfiguration`, `MutableClockConfiguration`, a configuração de FNRH dele); roles pela `template_key` como em `MilestoneM4EndToEndTest`; `IamTestSupport` (`asAdmin()`, `setPin`, `call(…, approvalToken)`); `IamTestSupport.enableFnrh` e `JdbcClient.resetFnrhSettings` (`testsupport/FnrhTestConfiguration.kt`); `FnrhTestServer`; `FrontdeskTestSupport` (`preAuthorization()`, `checkIn`, `policy()`/`putPolicy`); `RoomsTestSupport`, `GuestsTestSupport`, `BillingTestSupport`, `ReservationsTestSupport`, `RatesTestSupport`.
- Produces: a classe `MilestoneM5EndToEndTest` (`@IntegrationTest`, pacote `com.zeny.hotel`) com o teste `` `a guest arrives, is checked in with early check-in and the stay reaches FNRH in order` `` e helpers privados para as Tasks 2 e 3: `receptionist`/`manager` (operadores pelos modelos de role, o Gerente com PIN), `newStay(...)` (tipo de UH, duas UHs inspecionadas, plano, preços, titular adulto brasileiro com CPF e um menor com CPF e data de nascimento), `stubFnrhFor(stay)` e `sendOwnFnrhSubmissions(reservationId)`.

- [ ] **Step 1: Escrever o teste**, com os **modelos de role do boot** (Recepcionista e Gerente; um usuário de caixa só se a Recepcionista não puder receber pagamento com turno aberto, conferido no catálogo e registrado no relatório), na ordem:
  1. O administrador liga a FNRH (`enableFnrh`); o Gerente cadastra o tipo de UH, duas UHs, o plano e os preços; a política de early/late é ajustada para oferecer early com valor (guardando a original para o `finally`); os stubs v2.4 do `FnrhTestServer` têm ids remotos distintos para o titular e o menor.
  2. A Recepcionista cria a reserva (titular e menor com o adulto como responsável) e consulta `GET …/early-late-quote` (o preço é informado antes).
  3. Recusas, cada uma com o pedido completo (caução e `earlyCheckInFee` no corpo): UH não alocada → `409 ROOM_NOT_ASSIGNED`; alocada mas suja → `409 ROOM_NOT_READY`; menor sem comprovação → `422 MINOR_AUTHORIZATION_REQUIRED`. Depois de cada recusa: reserva `CONFIRMED`, nenhuma caução e nenhum lançamento na conta, UH não ocupada.
  4. Check-in com o menor autorizado (comprovação com anexo, como no `FrontdeskCheckInApiTest`), caução e o `earlyCheckInFee` da cotação: `200`, reserva `IN_HOUSE`, UH Ocupada com 2 hóspedes, caução ativa, o early lançado no valor cotado, `GuestCheckedIn` no outbox.
  5. `sendOwnFnrhSubmissions`: as submissões da reserva (criação e check-in) ficam `SENT`, com os ids remotos guardados; as chamadas ao WireMock **desta reserva** chegam em ordem (registro da reserva antes do check-in), com o titular e o menor como pessoas distintas.
  No `finally`: `resetFnrhSettings`, `FnrhTestServer.reset()`, política original, relógio devolvido. `FnrhTestServer.reset()` também no início.

- [ ] **Step 2: Rodar.** `./gradlew test --tests 'com.zeny.hotel.MilestoneM5EndToEndTest'` → 1 teste, 0 falhas. Defeito de produção → parar e relatar (Global Constraints).

- [ ] **Step 3: Provar que morde** (mutação temporária, código restaurado, `git diff` limpo depois): com o envio do `fnrh` sem efeito, o teste falha no subpasso 5. Registrar a mensagem.

- [ ] **Step 4:** `./gradlew ktlintFormat lint` → verde. **Commit** `Acrescenta o teste ponta a ponta do marco M5: check-in com early e FNRH (M5 E12)` com os trailers.

### Task 2: `MilestoneM5EndToEndTest` — ficha, troca, extensão e redução, pagamento, extrato, check-out com late e privacidade

**Estimativa:** 45 min · **Depende de:** Task 1 · **Risco:** alto

**Files:**
- Modify: `src/test/kotlin/com/zeny/hotel/MilestoneM5EndToEndTest.kt`

**Interfaces:**
- Consumes: helpers da Task 1; `PdfText`; o agente por long-poll do `DocumentsStageEndToEndTest` (classes privadas lá: replicar o mínimo no teste novo, sem mexer no teste da E11).
- Produces: o teste do cenário principal continua do ponto em que a Task 1 parou (mesmo `@Test`, renomeado para `` `a guest arrives, is registered with FNRH, has the registration card printed and leaves with a zero balance` ``), e o helper privado `agentLongPoll(token)`.

- [ ] **Step 1: Continuar o cenário:**
  1. O administrador cadastra um agente (`POST /api/v1/print-agents`, token só em memória) e uma impressora `A4_PDF` dele. Ficha: `POST /api/v1/reservations/{id}/registration-card/print` (Recepcionista) → `202` com `printJobId`; o agente recebe o job pelo long-poll, baixa o conteúdo (PDF), confirma (`ack` `PRINTED`) e o job fica `PRINTED`; o texto do PDF (`PdfText`) tem o nome do titular, a UH e as datas da reserva lida neste passo, o documento **mascarado**, e não tem o número inteiro do CPF.
  2. Troca para a UH 2 (`POST …/room-moves`, `If-Match`, `tariff` `KEEP`): UH 1 Vaga e Suja, UH 2 Ocupada, `RoomMoved` publicada.
  3. Extensão da saída em uma noite (`PUT /api/v1/reservations/{id}` com `If-Match`) e redução de volta: a UH 2 acompanha, as noites existentes não mudam de preço.
  4. Lançamento manual (`POST /api/v1/folios/{id}/postings`) e pagamento do saldo (com o early e o lançamento) com turno de caixa aberto.
  5. Extrato: `GET /api/v1/folios/{id}/statement/pdf` → `200 application/pdf` com o lançamento e o saldo `0,00`.
  6. Check-out com o `lateCheckOutFee` da cotação (a política oferece late; pagar o late antes ou incluir no pagamento conforme o fluxo da E04: o check-out exige saldo zero depois das cobranças, então o primeiro check-out com late e saldo pendente dá `409 FOLIO_BALANCE_NOT_ZERO` com o total; paga-se e repete-se com outra chave): `200`, caução liberada, contas encerradas, reserva `CHECKED_OUT`, UH 2 Vaga e Suja, alocação encerrada; `sendOwnFnrhSubmissions` deixa a submissão do check-out `SENT` e a chamada de check-out é a última desta reserva no WireMock.
  7. Privacidade: nenhuma linha de `audit` da reserva, publicação do outbox, notificação ou linha de `fnrh.report_submissions` contém o nome do titular nem o número do CPF.

- [ ] **Step 2: Rodar** → 1 teste, 0 falhas.
- [ ] **Step 3: Provar que morde:** sem a liberação das cauções no check-out, o teste falha no subpasso 6. Código restaurado.
- [ ] **Step 4:** `ktlintFormat lint` verde; **commit** `Completa o cenário principal do teste ponta a ponta do M5: ficha, troca, extrato e check-out (M5 E12)`.

### Task 3: `MilestoneM5EndToEndTest` — walk-in, desfazer check-in e Serpro fora do ar

**Estimativa:** 40 min · **Depende de:** Task 2 · **Risco:** alto

**Files:**
- Modify: `src/test/kotlin/com/zeny/hotel/MilestoneM5EndToEndTest.kt`

**Interfaces:**
- Consumes: helpers das Tasks 1 e 2.
- Produces: três testes novos na classe.

- [ ] **Step 1: `` `a walk-in guest of gender OTHER with a description is checked in and reaches FNRH` ``.** A Recepcionista faz o walk-in (`POST /api/v1/walk-ins`, hóspede cadastrado com gênero `OUTRO` e a descrição da E15, UH inspecionada) → `201`, `IN_HOUSE`, UH Ocupada; `sendOwnFnrhSubmissions` deixa a criação e o check-in `SENT`, e o corpo da pessoa enviado ao WireMock tem a descrição de gênero; a confirmação (`GET …/confirmation/pdf`) traz os horários de check-in e check-out da `HotelApi.policy()` lida no passo. (O walk-in com hóspede mesclado já está coberto pela E05: `FrontdeskEarlyCheckInApiTest` "a walk-in with a merged guest id never leaves a partial stay"; o comportamento decidido é `201` com o sobrevivente.)
- [ ] **Step 2: `` `undoing a check-in needs a superior's approval on the same business date and is audited with both users` ``.** Check-in de uma reserva; a Recepcionista desfaz → `403 APPROVAL_REQUIRED`; o Gerente aprova com PIN; com `X-Approval-Token` → `200`, reserva `CONFIRMED`, UH Vaga sem sujar; a trilha do desfazer tem a Recepcionista e o `approval_id`, e `iam.approval` tem solicitante e aprovador. Novo check-in; recuo de `checked_in_on` **da própria reserva** por SQL (como `FrontdeskCheckInApiTest.kt:490-498`) e outro desfazer → `422 UNDO_CHECK_IN_EXPIRED`. Nunca mover a data operacional.
- [ ] **Step 3: `` `with Serpro down the check-in concludes and the failure alert carries no guest data` ``.** O WireMock responde `503` para as rotas desta reserva; check-in → `200` e `IN_HOUSE`; `sendOwnFnrhSubmissions` deixa a submissão pendente com tentativa gasta (não `SENT`); `GET /api/v1/fnrh/submissions` (Recepcionista) a lista sem dado de hóspede; avançando o relógio pelas 8 tentativas (padrão REWIND de `FnrhReservationSendTestBase.kt:101-118`), a submissão vira `FAILED` e a notificação `notifications.integration-failure` do Gerente tem só o código da reserva; com o WireMock de volta e `POST …/{id}/retry` do Gerente, ela vira `SENT` sem duplicar o registro remoto.
- [ ] **Step 4: Rodar** → 4 testes, 0 falhas; rodar de novo com `--rerun` para pegar dependência de ordem.
- [ ] **Step 5: Provar que morde:** sem a exigência de aprovação no desfazer (a permissão `frontdesk.stays.undocheckin` concedida à Recepcionista **só na mutação temporária**), o Step 2 falha no `403`; sem a descrição de gênero no corpo enviado, o Step 1 falha. Código restaurado.
- [ ] **Step 6:** `ktlintFormat lint` verde; **commit** `Completa o teste ponta a ponta do M5: walk-in, desfazer check-in e Serpro fora do ar (M5 E12)`.

### Task 4: Compose isolado do gate com o WireMock da FNRH (`docker/gate/`)

**Estimativa:** 40 min · **Depende de:** nenhuma · **Risco:** alto (gera segredos; guarda a trava "nunca no repositório nem no `docker/.env`")

**Files:**
- Create: `docker/gate/gate-env.sh` — `gate-env.sh <dir> <porta>`: com `umask 077`, gera `<dir>/gate.env` (modo 600) a partir de `docker/.env.example` (achado pelo caminho do próprio script, sem `git`; funciona na cópia limpa) com chaves, senhas do banco, do MinIO e do admin sorteadas por `openssl`, `ZENY_HTTP_PORT=<porta>`, `ZENY_HTTP_ADDRESS=127.0.0.1`, `ZENY_STORAGE_PUBLIC_ENDPOINT=http://localhost:<porta>`, `ZENY_PROFILE=prod`, `ZENY_FCM_CREDENTIALS` vazia; imprime só o caminho, nunca valores. Recusa (`exit 2`) `<dir>` dentro da raiz do projeto (comparando caminhos reais com a raiz derivada do caminho do script).
- Create: `docker/gate/compose.gate.yaml` — override para `-f docker/compose.yaml -f docker/gate/compose.gate.yaml`: `image:` própria para o `api` (`zeny-hotel-manager:gate`, não a `:dev`); serviço `fnrh-mock` com a imagem fixada por digest (Tech Stack), sem porta publicada, com o volume `./gate/fnrh-mock` (caminho relativo ao diretório do projeto, `docker/`) montado só leitura e `--global-response-templating` (ou `transformers` por mapeamento); no `api`, a URL de homologação da FNRH (`zeny.fnrh.homologation-url` de `FnrhProperties`, pela variável do relaxed binding) apontada para `http://fnrh-mock:8080/<mesmo caminho base da homologação>` e `depends_on` do mock.
- Create: `docker/gate/fnrh-mock/mappings/*.json` — stubs v2.4 equivalentes aos do `FnrhTestServer` (registrar, consultar e cancelar reserva, listar hóspedes, consultar pessoa por documento, cadastrar pessoa, incluir hóspede/check-in, check-out, domínios), com: `Authorization` por `matches "^Basic .+"` (a credencial é sorteada pelo roteiro); `urlPathPattern` nas rotas com documento ou id no caminho; `cpf_solicitante` igual ao CPF padrão de teste que o roteiro configura; ids remotos distintos por templating (por exemplo `{{randomValue type='UUID'}}`), para o titular e o menor não receberem o mesmo `pessoa_id` nem duas reservas o mesmo `reserva_id`. Nenhum dado real.
- Create: `docker/gate/README.md` — uso (gerar o env, subir, rodar o roteiro, `down -v`), requisitos (`docker`, `curl`, `jq`, `openssl`), o aviso de nunca usar o `docker/.env` e de que os logs do `fnrh-mock` contêm os dados enviados por desenho.

**Interfaces:**
- Produces: `docker/gate/gate-env.sh <dir> <porta>` → caminho do env; `-p <projeto> --env-file <env> -f docker/compose.yaml -f docker/gate/compose.gate.yaml`; o host `fnrh-mock:8080` na rede do projeto; o CPF solicitante de teste e o formato da credencial que os mapeamentos aceitam (no README).

- [ ] **Step 1:** Escrever os arquivos; `bash -n` no script; `shellcheck` se existir (não instalar); `docker compose -p zeny-e12-smoke --env-file <env> -f docker/compose.yaml -f docker/gate/compose.gate.yaml config -q` sem erro; `gate-env.sh` com destino dentro do repositório → exit 2.
- [ ] **Step 2:** Subir (`up -d --build`, a partir da árvore de trabalho; um build pesado por vez), readiness `{"status":"UP"}` pelo proxy, e de dentro da rede (`docker compose exec api wget -qO- http://fnrh-mock:8080/__admin/mappings` ou um container efêmero na rede) os stubs carregados; um pedido de teste ao mock com dois documentos diferentes devolve ids diferentes. `down -v`. Colar a saída (sem valores do env) no relatório.
- [ ] **Step 3:** WireMock inviável na rede (motivo concreto) → `DONE_WITH_CONCERNS` com a evidência; o orquestrador decide o recuo previsto na E12.
- [ ] **Step 4: Commit** `Acrescenta o compose isolado do gate com o WireMock da FNRH (M5 E12)`.

### Task 5: Roteiro do gate, parte 1 — públicos, cadastro, check-in, FNRH, ficha, troca, extrato e check-out

**Estimativa:** 45 min · **Depende de:** Task 4 · **Risco:** alto (evidência do gate pelo proxy)

**Files:**
- Create: `docker/gate/m5-roteiro.sh` — `m5-roteiro.sh <base-url> <projeto> <env-file>`; só `curl`, `jq`, `openssl` e `docker compose exec db psql` (dono, para `shared.event_publication`, trilha e schema `printing`); sem `set -x`; toda conferência passa por `check <rótulo> <esperado> <obtido>`, que imprime o rótulo e OK/FALHA e **nunca imprime valor de segredo** (senhas, PIN, tokens, credencial da FNRH, Basic); a senha do admin é lida do env sem eco; no fim imprime `N conferências, F falhas` e sai com 1 se `F > 0`.
- Modify: `docker/gate/README.md` — seção do roteiro.

- [ ] **Step 1: Escrever as seções 1 a 8**, modelo no handoff da E13 do M4:
  1. Públicos: readiness 200, `meta/version` 200, `/actuator/prometheus` 404 pelo proxy, `/api/v1/reservations` sem token 401.
  2. Admin inicial com troca obrigatória; `recepcao` (Recepcionista) e `gerente` (Gerente, com PIN) pelos modelos do boot, com a troca de senha; usuário de caixa só se o catálogo exigir.
  3. Cadastro: o Gerente cria tipo de UH, UHs, inspeção, plano e preços; política de early/late oferecendo os dois com valor; o **administrador** configura a FNRH (`PUT /api/v1/fnrh/settings`, `HOMOLOGATION`, credencial de teste sorteada no script, ligada) e o CPF solicitante de teste, cria o agente (token só em variável) e a impressora `A4_PDF`; `psql`: o token `zpa_` não aparece em nenhuma coluna do schema `printing`.
  4. Reserva (titular e menor com CPF e data de nascimento) → `409 ROOM_NOT_READY` antes da inspeção → alocação → cotação → check-in com o menor autorizado (anexo pelo upload do `files`), caução e `earlyCheckInFee`.
  5. **FNRH:** laço com teto (≤ 3 min) até as submissões da reserva ficarem `SENT` em `GET /api/v1/fnrh/submissions`; `shared.event_publication` com o listener do `fnrh` `COMPLETED` para `ReservationCreated` e `GuestCheckedIn`; `GET /api/v1/fnrh/status` com `lastSuccessAt`; os logs do `fnrh-mock` sem pedido não casado (pedido não casado = falha de stub).
  6. **Ficha pelo agente:** `POST …/registration-card/print` → `202`; o agente abre `GET /api/v1/print-agents/me/jobs/stream` (`curl -N`, tempo limite), lê o evento `job`, baixa o conteúdo (`%PDF-`), confirma (`ack` `PRINTED`); `GET /api/v1/print-jobs` mostra `PRINTED`; JWT de usuário em `/api/v1/print-agents/me/identity` → 401; token do agente em `/api/v1/reservations` → 401.
  7. Troca de UH (`If-Match`); extensão e redução da saída pelo `PUT` (`If-Match`); lançamento, turno de caixa e pagamento; extrato PDF e confirmação PDF (`%PDF-`).
  8. Check-out com `lateCheckOutFee` (pagando o saldo antes): contas encerradas, caução liberada, UH Vaga e Suja; a submissão do check-out `SENT` e `GuestCheckedOut` `COMPLETED` no listener do `fnrh`.
- [ ] **Step 2: Rodar** o ciclo a partir da árvore de trabalho: `gate-env.sh` num diretório do scratchpad, `up -d --build` com `-p zeny-e12-gate` e os dois `-f`, readiness, roteiro, `down -v` sempre (por `trap` no wrapper ou no roteiro). Esperado: `N conferências, 0 falhas`, exit 0. Log completo no arquivo de relatório; no relatório curto, só o resumo.
- [ ] **Step 3: Commit** `Acrescenta o roteiro curl do gate do M5, parte 1 (M5 E12)`.

### Task 6: Roteiro do gate, parte 2 — walk-in, desfazer, recados, Serpro fora do ar, E16 e varredura de logs

**Estimativa:** 40 min · **Depende de:** Task 5 · **Risco:** alto

**Files:**
- Modify: `docker/gate/m5-roteiro.sh`, `docker/gate/README.md`

- [ ] **Step 1: Escrever as seções 9 a 13:**
  9. Walk-in com hóspede de gênero `OUTRO` e descrição (E15) → `201`; a submissão `SENT`. Desfazer check-in de outra reserva: Recepcionista `403 APPROVAL_REQUIRED`, aprovação do Gerente com PIN, `200`, trilha com os dois e `iam.approval`.
  10. Recados: criar para um setor vinculado à reserva, listar, resolver; `409 TRACE_ALREADY_RESOLVED` na segunda resolução.
  11. Serpro fora do ar: `docker compose stop fnrh-mock`; check-in de outra reserva → `200` e `IN_HOUSE`; a submissão não `SENT`, com tentativa gasta; `start fnrh-mock`.
  12. E16: `POST /api/v1/fnrh/settings/clear-credentials` (administrador, `If-Match`) → `passwordSet=false`, integração desligada; a trilha `fnrh.settings.clear-credentials` existe e não contém o usuário nem a senha da FNRH.
  13. Varredura: `docker compose logs --no-color` de `api`, `db`, `proxy`, `storage` e `storage-init` (o `fnrh-mock` fica de fora: faz o papel do Serpro) não contém: os nomes dos hóspedes, os números de CPF/documento, o usuário e a senha da FNRH e o Basic em base64, o token `zpa_` do agente, `eyJ` (access tokens), o PIN, as senhas do admin, dos usuários, do banco e do MinIO; a própria saída do roteiro também não (o roteiro guarda a saída e se varre no fim).
- [ ] **Step 2: Rodar** o ciclo completo (seções 1 a 13) como na Task 5 → `0 falhas`, exit 0; `down -v`.
- [ ] **Step 3: Commit** `Completa o roteiro curl do gate do M5 (M5 E12)`.

### Task 7: `README.md` coerente com o código do M5

**Estimativa:** 20 min · **Depende de:** Task 4 · **Risco:** normal (documentação)

**Files:**
- Modify: `README.md` (seções "API", "Ambiente completo em Docker" e árvore de módulos)

- [ ] **Step 1:** Conferir cada item M5 da seção "API" contra o código e o `api/openapi.yaml` (rotas, permissões, códigos de erro, eventos) e corrigir o defasado. Conhecidos: o item de encerramento e extrato diz "O PDF do extrato vem no M5" (já existe: apontar para "Documentos impressos"); "Transições de hospedagem" fala em "nas etapas seguintes do M5"; conferir a descrição de gênero (E15) no `guests` e a limpeza da credencial (E16) na FNRH, e se a árvore de módulos lista `frontdesk`, `fnrh` e `printing`.
- [ ] **Step 2:** Em "Ambiente completo em Docker", uma frase apontando o `docker/gate/README.md` como o roteiro de verificação de gate.
- [ ] **Step 3: Commit** `Atualiza o README com o estado do M5 (M5 E12)`.

### Task 8: Auditoria das notas do brain contra o código do M5 (só leitura)

**Estimativa:** 30 min · **Depende de:** nenhuma · **Risco:** normal (sem mudança no repositório; vai à revisão final como anexo)

**Files:**
- Create (fora do repositório versionado): o relatório no workspace, caminho dado no despacho.

- [ ] **Step 1:** Listar, pelo código, o que o M5 acrescentou: APIs públicas (`*Api`: `PrintingApi` com `render`/`print`/`enqueue`; acréscimos em `ReservationsApi`, `BillingApi`, `GuestsApi`, `HotelApi.profile`, `FilesApi.readContent`), eventos (`GuestCheckedIn`, `GuestCheckInUndone`, `GuestCheckedOut`, `RoomMoved`) e quem os consome, portas (`ServiceCredentialRealm` ou o nome real da credencial de serviço no `shared`, `FolioOwnerDescriber`, `ModuleDocumentTemplates`, `FnrhGateway`) e views (`fnrh.report_submissions` e outras do marco), com o arquivo de cada uma.
- [ ] **Step 2:** Comparar essa lista com o hub `/home/caio/SecondBrain/10 Projects/ZenyHotelManager.md`, as notas de tema ligadas a ele (as dos módulos `frontdesk`/hospedagem, `fnrh`, `printing`, `billing`, `guests`, `hotel`, `files`, eventos, segurança) e as capturas pendentes `00 Inbox/*ZenyHotelManager - atualização m5-recepcao-fnrh*`; não editar nada no vault.
- [ ] **Step 3:** Escrever no relatório, por nota, o texto exato da correção ou do complemento (o que falta, o que diverge do código, com o arquivo e a linha de evidência), pronto para o `brain-escriba` publicar. Sem commit.

---

## Verificação do gate (orquestrador, depois das tarefas e antes da revisão final)

Rodados pelo orquestrador, um Gradle por vez, com a saída inteira no workspace e as linhas-chave no handoff:

1. Cópia limpa: `git archive <HEAD da branch> | tar -x -C <scratchpad>/e12-clean`, `GRADLE_USER_HOME=<scratchpad>/e12-gradle-home` novo; `./gradlew --no-daemon --no-build-cache clean build`; `clean test` mais duas vezes (três rodadas dos testes); `lint checkOpenApi`.
2. Lockfiles: na cópia limpa, `./gradlew --no-daemon dependencies --write-locks` e `diff` de cada `*.lockfile` contra o do snapshot (`git show <HEAD>:<arquivo>`) → sem diferença.
3. `allowedDependencies` de cada `…Module.kt` do M5 (`frontdesk`, `fnrh`, `printing`, e as arestas novas de `reservations`, `billing`, `guests`, `hotel`, `files`) contra a tabela da ARCHITECTURE §3.1.
4. `.github/scripts/check-api-breaking.sh ffae917` → nenhuma mudança `ERR`.
5. Compose a partir da cópia limpa: `gate-env.sh`, `up -d --build`, `m5-roteiro.sh`, `down -v` → 0 falhas.
6. `osv-scanner scan source -r --no-ignore .` → pacotes listados, 0 Critical e 0 High fora dos riscos aceitos da E14.
7. `check_test_integrity.py --base <MERGE_BASE>`.

## Decisões da etapa (rulings do orquestrador)

- **R1 — roteiro versionado em `docker/gate/`.** Alternativa: o scratchpad, como nos gates do M3 e do M4 (perdido ao fim da sessão). Escolhido versionar: revisável, conferível por trailer e reaproveitável no gate do M6; contra: não roda na CI e pode envelhecer. Fora da imagem (`.dockerignore` é lista de permissão). Custo se errado: apagar a pasta. Vai às "Decisões para o usuário".
- **R2 — defeito achado no gate.** Correção pequena sem mudar contrato, migração ou catálogo de permissões entra nesta etapa, com nota no handoff da etapa de origem e sem mudar a tag dela (o prompt da execução proíbe alterar tags de etapas não executadas); o que mudar contrato, migração ou catálogo para o lote.
- **R3 — pendência da E03 (walk-in com hóspede mesclado).** Coberta pela E05 (handoff da E05, "Walk-in com um hóspede mesclado: dá `201` com o sobrevivente em tudo"; teste `FrontdeskEarlyCheckInApiTest` "a walk-in with a merged guest id never leaves a partial stay"). O esperado mudou de "recusa com rollback" para `201` com o sobrevivente, decisão da E05; não é lacuna do gate.
- **R4 — ordem interna da camada Hospedagem na ARCHITECTURE §3.2** (nota da E03): mudança de regra de arquitetura (§3.1: "uma nova exige decisão"); vai ao usuário, fora do gate.
- **R5 — pendência Média da E01** (teste de índices mais fraco nas listas por situação): destino M6, registrado no handoff da E12 como nota para o marco M6.

## Fora deste plano (registrado para o handoff)

- O estado da E09 (`#bloqueada` pela Q-12, pendência obrigatória antes da operação real, cobrada pelo M8 E13) e a conclusão do M5 em `docs/roadmaps/README.md`: documentos do orquestrador no fechamento.
- A captura de atualização do brain, com o texto da Task 8 e o estado do marco em `ZenyHotelManager - Estado por marco`: `brain-escriba`, no fechamento.
