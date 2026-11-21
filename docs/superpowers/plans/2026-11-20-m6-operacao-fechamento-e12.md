# E12 do M6 — Gate do marco M6 e fim da Fase 1: plano de implementação

> **Para agentes:** execução por `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde, não o código.

**Goal:** provar, com os módulos reais, o cenário ponta a ponta da Fase 1 (teste de integração do marco e roteiro `curl` num compose isolado) e conferir, numa cópia limpa, a definição global de pronto do M6, registrando a conclusão do M6 e da Fase 1.

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E12", a "Definição global de pronto", H10 e os handoffs de todas as etapas do marco (em especial E10, E11, E13, E14, E19, E22 e E23); handoff da M5 E12 (`docs/roadmaps/roadmap-m5-recepcao-fnrh.md`); `docker/gate/README.md`. Brain: `ZenyHotelManager - Estado por marco` (convenção dos gates), `ZenyHotelManager - Testes de integração` ("M6 E10", "M6 E11"), `ZenyHotelManager - Módulo housekeeping`, `ZenyHotelManager - Módulo endofday`, `ZenyHotelManager - Módulo fnrh`, `ZenyHotelManager - Docker, observabilidade e CI` e as capturas pendentes do M6 (E02, E05, E19, E23, E13).

**Architecture:** sem código de produção novo. Um teste de integração do marco na raiz do pacote de testes (`MilestoneM6EndToEndTest`, como os de M3 a M5), no caminho próprio do fechamento (`@EndOfDayIntegrationTest`, banco `zeny_endofday`, H10), com os operadores pelos modelos de role, o `MutableClock` devolvido no `finally` e só a FNRH em WireMock. O roteiro do compose (`docker/gate/m6-roteiro.sh`) reaproveita `run-gate.sh`, `gate-env.sh`, `compose.gate.yaml`, o WireMock da FNRH e o `check`/`redact`/varredura do `m5-roteiro.sh`, num banco novo (`down -v` antes e depois). A cópia limpa (`git archive`, `GRADLE_USER_HOME` novo, `--no-daemon --no-build-cache`) e a conferência da definição de pronto ficam com o orquestrador, depois das fatias.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Testcontainers, WireMock; bash, curl, jq, Docker Compose v2.

**Riscos:**
- **dado pessoal ou segredo:** o roteiro lida com senha do admin, PIN, tokens e credencial da FNRH, e os logs e a saída não podem ter dado de hóspede (Task 2 e 3);
- **concorrência:** o fechamento real no teste do marco e no compose (retomada, data avançada, banco isolado acumulado) (Tasks 1 a 3);
- **cálculo de valor monetário:** diária lançada pelo fechamento e check-out com saldo zero (Tasks 1 e 2).

## Global Constraints

- Nenhuma mudança em `src/main`, migração ou contrato. Um defeito de produção achado pelo gate é corrigido na própria E12, numa rodada com o desenvolvedor, sem mudar a tag de outra etapa (R2 do gate do M5).
- O teste do marco usa `@EndOfDayIntegrationTest` com `@Import(EndOfDayRealStepsTestConfiguration::class)` (o contexto em cache das etapas reais), afirma só sobre os próprios dados (o banco isolado acumula execuções, pendências e a data só avança) e termina com `EndOfDayTestRuns.settle()` e o `MutableClock` devolvido no `finally`. Nenhum teste do banco compartilhado dispara o fechamento.
- O roteiro segue as regras do `docker/gate/README.md`: recusa o projeto `zeny` e env dentro de repositório; segredos só em variáveis e arquivos 600 apagados na saída; `Idempotency-Key` própria por `POST`; `check <rótulo> <esperado> <obtido>`; saída com `N conferências, F falhas`; varredura de vazamento no log da `api` e na saída. `run-gate.sh` passa a receber o roteiro a rodar (padrão o do M5, para não quebrar o uso documentado).
- Máquina compartilhada com outra sessão: um Gradle ou `docker build` por vez, `timeout` em todo comando, sem laço de espera, nunca `./gradlew --stop`, nunca `docker compose down` de projeto que não seja o do gate. Gradle com `LOCALDOMAIN=" " RES_OPTIONS="timeout:1 attempts:1"` e `--no-daemon` (DNS do WSL; handoff da E23).
- Código em inglês; comentários de script, KDoc, README e commits em pt-BR; commits com `Soul:` e `Co-Authored-By` no mesmo bloco final.

## Review Focus

1. **Diária do fechamento e check-out:** a noite é lançada uma vez pelo fechamento e o check-out no dia da saída não a cobra de novo; o check-out só passa com saldo zero. Teste na Task 1, conferência na Task 2.
2. **Tarefas da Fase 1 com o status da UH (§8.4):** permanência gerada pelo avanço da data, distribuída, executada offline pelo comando do `housekeeping` e inspecionada (UH Inspecionada); saída gerada pelo check-out (`GuestCheckedOut`) e concluída. Teste na Task 1, conferência na Task 2.
3. **Fechamento com as 11 etapas e o que ele grava:** snapshot e `DAY_REPORT` da data, pendências, rotinas de checklist do novo dia (nota herdada do M2). Teste na Task 1, conferência na Task 3.
4. **Segredo ou dado de hóspede na saída do roteiro ou no log da `api`:** varredura sem achado. Task 3.
5. **Gate repetível:** roteiro num banco novo e `down -v` sem sobras, mesmo em falha. Tasks 2 e 3.

## Decisões do plano

- **D1 — cenário da Fase 1 (teste e roteiro):** reserva de duas noites (D0–D2) com hora prevista de chegada (E22), check-in em D0, fechamento manual de D0 com step-up (diária da noite D0, UH Suja, data em D1, snapshot e `DAY_REPORT` de D0), tarefa de permanência de D1 gerada pelo avanço (D9 da E02: só para quem chegou antes da data e não sai nela), distribuída e publicada, executada pela camareira por comando offline (E19) e inspecionada e aprovada (E05: UH Inspecionada); fechamento de D1 (diária da noite D1, data em D2); pagamento do saldo e check-out em D2 com saldo zero; tarefa de saída gerada por `GuestCheckedOut` e concluída; submissões FNRH de check-in e check-out entregues ao WireMock. Duas noites porque a permanência só nasce para quem não sai na data.
- **D2 — o que o compose prova além do teste:** o mesmo cenário pelo proxy, num banco novo, mais o que só o ambiente mostra (readiness, step-up real com PIN, sync offline pelo `POST /api/v1/sync/commands`, download do PDF do arquivo pelo `files`, CSV/XLSX, logs). O que o compose não alcança (prazos com `MutableClock`, falhas forçadas) fica com os testes das etapas, na cópia limpa.
- **D3 — cópia limpa:** um `clean build` (inclui testes, `ModularityTest`, ArchUnit, lint, `checkOpenApi`, `koverVerify`) e um `clean test` a mais (estabilidade), `dependencies --write-locks` sem diferença, `check-api-breaking.sh` contra o merge-base com a `main` e `osv-scanner`; pelo orquestrador, depois das fatias e antes da revisão final.
- **D4 — definição global de pronto:** cada item marcado no roadmap com a evidência (teste da etapa, teste do marco ou conferência do roteiro, na cópia limpa); pendências herdadas listadas (M5 E09 FNRH pela Q-12; M8 E18 entrega do alerta; o que os handoffs do M6 deixaram como decisão para o usuário).

## Revisão de arquitetura

- **Dispensada** (passo 2 do orquestrador): a etapa não cria nem contraria decisão de ARCHITECTURE, não muda contrato público, persistência, migração, fronteira de módulo nem traz peça nova de runtime. Conferido: só um teste de integração, scripts do gate em `docker/gate/` (ferramenta de verificação, fora da imagem e da CI, R1 do M5), `README.md` e `docs/roadmaps/README.md`. Um defeito de produção achado vira rodada na própria etapa; se ela tocar um desses pontos, a revisão passa a obrigatória.

---

### Task 1: Teste ponta a ponta da Fase 1 (`MilestoneM6EndToEndTest`)

**Objetivo:** D1 em teste de integração, com os módulos reais e só a FNRH em WireMock.

**Critérios de aceite:**
- `MilestoneM6EndToEndTest` (`src/test/kotlin/com/zeny/hotel/`, novo, `@EndOfDayIntegrationTest` + `@Import(EndOfDayRealStepsTestConfiguration::class)` e o WireMock da FNRH como no `MilestoneM5EndToEndTest`), operadores pelos modelos de role (Gerente, Recepcionista, Governanta, Camareira), cobre o cenário do D1 em passos afirmados:
  - reserva com `expectedArrivalTime`; check-in em D0; submissão FNRH de check-in entregue;
  - fechamento de D0 pelo `POST /api/v1/end-of-day/runs` com aprovação de superior: execução `COMPLETED` com as 11 etapas, diária da noite D0 lançada uma vez na conta da reserva, UH Suja, data operacional em D1, linha de `reporting.daily_statistics` e `DAY_REPORT` de D0, uma execução das rotinas de checklist de D1 (nota do M2);
  - tarefa `STAYOVER` de D1 na UH da reserva, distribuída e publicada (aviso `task-assigned`), executada pela camareira pelo comando offline do `housekeeping` (início e conclusão com o checklist), inspecionada e aprovada: UH Inspecionada;
  - fechamento de D1: diária da noite D1 lançada uma vez, data em D2;
  - pagamento do saldo e check-out em D2: `200`, contas encerradas, nenhuma diária a mais; tarefa `CHECKOUT` de D2 gerada e concluída; submissão FNRH de check-out entregue;
  - afirmações só sobre a reserva, a UH e as execuções do próprio teste; nenhuma `EndOfDayIssue` da execução sobre a reserva do teste.
- Verificação: `LOCALDOMAIN=" " RES_OPTIONS="timeout:1 attempts:1" timeout 1500 ./gradlew --no-daemon test --tests '*MilestoneM6EndToEndTest' --tests 'com.zeny.hotel.endofday.*'` (o teste mais os do mesmo banco isolado) e `timeout 900 ./gradlew --no-daemon checkOpenApi lint`.

**Áreas tocadas:** `src/test/kotlin/com/zeny/hotel/MilestoneM6EndToEndTest.kt` (novo) e, se precisar, apoio de teste novo em `src/test/kotlin/com/zeny/hotel/` (nunca em `src/main`).

**Interfaces:**
- Consome: as APIs HTTP públicas do contrato (`api/openapi.yaml`), `EndOfDayTestRuns`, `EndOfDayRealStepsTestConfiguration`, `MutableClock`, o apoio de FNRH do `MilestoneM5EndToEndTest`.
- Produz: nada para as outras fatias.

**Tamanho:** ~60 min; 0 arquivos de produção (1–2 de teste).

**Depende de:** nenhuma.

### Task 2: Roteiro do compose, parte 1 (cenário da Fase 1)

**Objetivo:** D1 e D2 pelo proxy, num compose isolado: `docker/gate/m6-roteiro.sh` com as seções do cenário, e `run-gate.sh` escolhendo o roteiro.

**Critérios de aceite:**
- `run-gate.sh <workdir> <porta> [projeto] [roteiro]` (o 4º argumento, `m5` ou `m6`, padrão `m5`) roda o roteiro escolhido e guarda a saída em `<workdir>/<roteiro>-roteiro.out`; o resto do ciclo (env, `config -q`, readiness, `down -v` por `trap`, contagem de sobras) não muda.
- `m6-roteiro.sh <base-url> <projeto> <env-file>` (novo), com o cabeçalho, as recusas, o `check`/`redact` e a varredura copiados do `m5-roteiro.sh` (sem duplicar lógica que caiba num arquivo comum `docker/gate/lib-roteiro.sh`, se o desenvolvedor extrair; senão cópia fiel), faz pelo proxy: troca da senha do admin; usuários pelos modelos de role (Gerente, Recepcionista, Governanta, Camareira) com PIN do Gerente para o step-up; configuração mínima (tipo de UH, UH, plano e preço, política com `endOfDayTime`, tipos de limpeza e créditos, escala da camareira, FNRH em homologação contra o mock); o cenário do D1 até o check-out e a tarefa de saída concluída; conferências das mesmas afirmações da Task 1 (com `psql` no `db` só onde a API não mostra: contagem de lançamentos por noite e da execução das rotinas).
- Execução real: `docker/gate/run-gate.sh <workdir no scratchpad> <porta livre> zeny-e12-t2 m6` → `0 falhas` e `down -v` com 0 sobras; a saída vai ao relatório.
- `shellcheck` nos scripts tocados (se instalado; senão `bash -n`), sem achado novo.

**Áreas tocadas:** `docker/gate/run-gate.sh`, `docker/gate/m6-roteiro.sh` (novo), `docker/gate/lib-roteiro.sh` (opcional), mapeamentos do `docker/gate/fnrh-mock/` só se o cenário precisar de um novo.

**Interfaces:**
- Consome: `gate-env.sh`, `compose.gate.yaml`, `fnrh-mock/`.
- Produz: `m6-roteiro.sh` com as funções `section`, `check` e as variáveis de sessão (tokens, ids da reserva, da UH e da execução) que a Task 3 estende.

**Tamanho:** ~60 min; 0 arquivos de produção (2–3 scripts).

**Depende de:** nenhuma (a Task 1 e esta não tocam os mesmos arquivos).

### Task 3: Roteiro do compose, parte 2 (fechamento, relatórios, operação e varredura) e documentação

**Objetivo:** o que o compose prova além do cenário (D2) e os READMEs.

**Critérios de aceite:**
- `m6-roteiro.sh` ganha, depois do cenário: o arquivo do fechamento (`GET /api/v1/reports/archive?date=D0` com o `DAY_REPORT`, download pela URL do `files` com `200 application/pdf`, e `403` para a Camareira); as pendências da execução (`GET …/runs/{id}/issues`, sem dado de hóspede); o painel do dia, o relatório de governança e o CSV e o XLSX de um deles (`200`, tipos corretos); uma OS corretiva com OOO aprovada por step-up e concluída, UH de volta Suja (E07); um achado e perdido e um dano aprovado lançado na conta (E06), numa segunda reserva própria; a varredura de vazamento (nome, documento e contato do hóspede, senhas, PIN, tokens e credencial da FNRH) na saída e no log da `api`, e a contagem de `WARN`/`ERROR` do log, como no M5.
- Execução real completa: `docker/gate/run-gate.sh … zeny-e12-t3 m6` → `0 falhas`, varredura limpa, `down -v` com 0 sobras.
- `docker/gate/README.md` com a seção "Roteiro do M6" (o que cobre, como rodar, o 4º argumento do `run-gate.sh`); `README.md` da raiz com o gate do M6 na linha do `docker/gate` e o que mudou no fechamento na E13 (11 etapas; snapshot, arquivo e pendências); `docs/roadmaps/README.md` com a conclusão do M6 e da Fase 1 no histórico (texto final preenchido pelo orquestrador com os números da cópia limpa; aqui só a estrutura).

**Áreas tocadas:** `docker/gate/m6-roteiro.sh`, `docker/gate/README.md`, `README.md`, `docs/roadmaps/README.md`.

**Interfaces:**
- Consome: `m6-roteiro.sh` da Task 2.
- Produz: nada.

**Tamanho:** ~60 min; 0 arquivos de produção (1 script e 3 documentos).

**Depende de:** Task 2 (`m6-roteiro.sh`).
