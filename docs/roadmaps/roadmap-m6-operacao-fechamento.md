# Roadmap — M6 Operação diária e fechamento

> Arquivo: `docs/roadmaps/roadmap-m6-operacao-fechamento.md` · branch de execução: `roadmap/m6-operacao-fechamento` · criado em 2026-10-13 · última revisão 2026-11-06
>
> Marco 6 de 11 · mapa dos marcos em [`docs/roadmaps/README.md`](README.md) · marco anterior: [M5 — Recepção, FNRH e impressão](roadmap-m5-recepcao-fnrh.md) · próximo marco: [M7 — Pagamentos integrados e fiscal](roadmap-m7-pagamentos-fiscal.md)
>
> **Pré-requisito:** a etapa de gate do marco M5 (última etapa de `docs/roadmaps/roadmap-m5-recepcao-fnrh.md`) está `#concluida`.

## Como usar

Inicie uma sessão limpa do Claude Code na raiz do repositório com `claude --agent orquestrador` e cole o "Prompt de execução". Ele executa até 3 etapas em sequência na branch `roadmap/m6-operacao-fechamento`, cada uma planejada pelo plugin superpowers e executada pelas souls (subagentes do Second Brain), e para para revisão. No fim do lote, o orquestrador integra a branch na `main` com merge local e deixa pronto o `git push origin main`, que fica com você. Para não parar em cada pedido de permissão, libere antes os comandos de build/teste do projeto nas permissões do Claude Code (os comandos oficiais estão na tabela do `CLAUDE.md`; o Docker é necessário para os testes de integração).

**Este roadmap foi escrito antes do código existir e revisto em 2026-11-06 contra `ca9f9e2`, com o M5 ainda em andamento.** Antes da primeira etapa, sincronize-o com o código real usando a skill `development-roadmap` (modo atualização): confira caminhos, APIs públicas dos módulos de M1–M5 e convenções registradas nos handoffs e nas notas de tema do brain, e ajuste as etapas pendentes.

## Objetivo

Fechar a Fase 1 da SPEC: o hotel opera a hospedagem. A governança gera, distribui, executa (inclusive offline) e inspeciona as tarefas de limpeza; a manutenção corretiva abre e conclui ordens de serviço com OOO/OOS; o fechamento do dia roda automaticamente, é idempotente e retomável, lança diárias e avança a data operacional; e o painel do dia e os relatórios operacionais ficam disponíveis, com os relatórios do fechamento arquivados em PDF.

## Contexto confirmado

- Requisitos: SPEC §5.14 (GOV), §5.16 (MAN, só a parte corretiva: RF-MAN-01, 02, 03, 07), §5.13 (FEC), §5.23 RF-REL-01, RF-REL-06 e RF-REL-07, §5.22 RF-NOT-01 (avisos de tarefa atribuída, UH liberada, inspeção reprovada, OS urgente e pendência do fechamento).
- Regras: RN-05, RN-08 (transições de status), RN-09 (sujeira automática), RN-13 (minimização no app operacional), RN-15 (multa de no-show pela política vigente), RN-16 (bloqueio vencido), RN-21 (fechamento único), RN-22 (offline), RN-23 (pré-reserva), RF-FEC-05 (não lançar em data fechada).
- Modelo de domínio: SPEC §8.2.9 (`EndOfDayRun`, `EndOfDayStep`, `EndOfDayIssue`, `DailyStatistics`), §8.2.10 (`CleaningType`, `CreditRule`, `HousekeepingTask`, `AssignmentPlan`, `Inspection`, `ScheduledCleaning`, `LostAndFoundItem`, `DamageReport`), §8.2.12 (`WorkOrder`; `Asset`, `PreventivePlan` e `WorkOrderPart` ficam fora); máquinas de estado da tarefa de governança e do status de governança da UH em §8.4; eventos em §8.3 (`GuestCheckedOut`, `RoomMoved`, `ChecklistCompleted`, `HousekeepingTaskCompleted`, `BusinessDateAdvanced`).
- Arquitetura: ARCHITECTURE §3.1 — `housekeeping` depende de `shared`, `hotel`, `rooms`, `reservations`, `staff`, `checklists`, `billing`, `notifications`; `maintenance` depende de `shared`, `hotel`, `rooms`, `staff`, `checklists`, `inventory`, `files`, `notifications` (`inventory` só existe em M9); `endofday` depende de `shared`, `hotel`, `rooms`, `reservations`, `billing`, `payments`, `reporting`, `notifications`; `reporting` consulta só views `report_*` (§11). Regra por camadas da §3.1: qualquer módulo pode chamar os serviços de plataforma (`files`, `notifications`, `printing`, `hotel`) e depender de camadas inferiores sem aprovação, desde que o grafo siga acíclico e a aresta nova seja acrescentada à tabela §3.1 na mesma mudança; `notifications` e `printing` nunca escutam eventos de negócio — quem gera o aviso ou é dono do documento os chama. Fluxo offline em §6.2 e §9; jobs em §13 (fechamento idempotente e retomável com ShedLock; geração de tarefas após `BusinessDateAdvanced`); inversão de dependência em §3.1 (`housekeeping` escuta `BusinessDateAdvanced` de `hotel`).
- Comandos de build, teste, lint e verificação do contrato: na tabela de comandos oficiais do `CLAUDE.md`; não são repetidos aqui.
- Convenções herdadas, documentadas nas notas de tema do brain (lista abaixo): M0 (API, erros, migrações, `Money`, IDs, idempotência, outbox, ShedLock), M1 (autenticação, `authz.require`/escopo `OWN`/limites/step-up, `@Audited`, data operacional, `staff` e escala, `files`), M2 (`rooms` e status, SSE, sync offline com `OfflineCommandHandler`, `checklists`, `notifications`), M3 (`guests`, `billing`, `payments` manual), M4 (`rates`, `reservations`, disponibilidade), M5 (`frontdesk`, eventos de hospedagem, `fnrh.report_submissions`, documentos montados pelo módulo dono e renderizados por `printing`).
- Revisão de arquitetura dos roadmaps: `docs/superpowers/specs/2026-11-06-revisao-arquitetura-roadmaps-design.md`.
- Conhecimento do projeto no Second Brain: hub `ZenyHotelManager` e as notas de tema `ZenyHotelManager - Estado por marco`, `ZenyHotelManager - Arquitetura e estrutura de módulos`, `ZenyHotelManager - Schemas, migrações e views de relatório`, `ZenyHotelManager - Tipos do kernel`, `ZenyHotelManager - Convenções de API`, `ZenyHotelManager - Segurança, autenticação e autorização`, `ZenyHotelManager - Auditoria`, `ZenyHotelManager - Eventos e jobs`, `ZenyHotelManager - Tempo real (SSE)`, `ZenyHotelManager - Sincronização offline`, `ZenyHotelManager - Testes de integração`, `ZenyHotelManager - Módulos hotel e staff`, `ZenyHotelManager - Módulo files`, `ZenyHotelManager - Módulo rooms`, `ZenyHotelManager - Módulo checklists`, `ZenyHotelManager - Módulo notifications`, `ZenyHotelManager - Módulo billing (contas, roteamento e taxa de serviço)`, `ZenyHotelManager - Módulo billing (caixa, pagamentos, caução e encerramento)`, `ZenyHotelManager - Módulo rates`, `ZenyHotelManager - Módulo reservations (inventário, disponibilidade e reservas)`, `ZenyHotelManager - Módulo reservations (ciclo de vida, alocação, grupos e busca)`, `ZenyHotelManager - Hospedagem e recepção` e `ZenyHotelManager - Docker, observabilidade e CI` (este para o gate).
- Caminhos de código previstos: um pacote por módulo sob o pacote base `com.zeny.hotel` (hipótese herdada de M0) — `housekeeping/`, `maintenance/`, `endofday/`, `reporting/` (a criar).

## Escopo

**Incluído:** módulo `housekeeping` completo da Fase 1; módulo `maintenance` com OS corretiva, OOO/OOS e app offline; módulo `reporting` com views, painel do dia, relatórios operacionais de governança e manutenção, snapshot de estatísticas diárias, exportação CSV/XLSX e arquivo dos PDFs do fechamento; módulo `endofday` com motor e etapas do fechamento.

**Fora de escopo:**

- Consumo de peças do estoque na OS (RF-MAN-06) e fornecedor como entidade (`Supplier`): M9, porque `inventory` ainda não existe. Até lá, o terceiro da OS é texto livre e o custo de peças é um valor informado.
- Ativos e manutenção preventiva (RF-MAN-04, RF-MAN-05): M10.
- Troca de enxoval e baixa de insumos pelas ações de checklist (RF-CHK-05): M9. Aqui a regra de troca de enxoval só é configurada no tipo de limpeza.
- Renovação de pré-autorizações no fechamento (RF-FEC-02 passo 6): M7. A etapa existe no motor e é registrada como "não aplicável" até lá.
- Notas rejeitadas e ARI não sincronizado no relatório de pendências (RF-FEC-04): M7 e M8, quando os módulos existirem.
- KPIs e relatórios gerenciais (RF-REL-02 a RF-REL-05, RF-REL-08): M10. Aqui só se grava o snapshot `DailyStatistics` que os alimentará.

## Restrições e invariantes

- Respeitar o grafo de módulos e a regra por camadas da ARCHITECTURE §3.1; toda aresta nova (ex.: `reporting` → `files` e `reporting` → `printing` em E09) entra na tabela §3.1 na mesma etapa. Em especial: `housekeeping` não depende de `endofday` nem de `frontdesk` (reage a `BusinessDateAdvanced`, `GuestCheckedOut` e `RoomMoved`); `reporting` não lê tabelas internas de outros módulos, só views `report_*`; `maintenance` não chama `inventory` neste marco.
- RN-13: respostas e feeds de sincronização para camareira e manutenção nunca contêm nome, documento ou contato do hóspede.
- RN-21: uma única execução de fechamento por data, nunca em paralelo; cada etapa é idempotente (ex.: diária de `(reserva, data, origem=EOD)` lançada uma vez).
- RF-FEC-05: nada é lançado em data operacional fechada.
- RN-22: execução offline nunca é descartada; conflito é registrado e sinalizado à governanta.
- Transições de status da UH seguem §8.4 e passam pela API de `rooms`, com histórico (RF-UH-06).
- Ações sensíveis com step-up: fechamento manual, OOO (RF-ACS-08).
- Toda mudança de API regenera o contrato `api/openapi.yaml` com `./gradlew updateOpenApi` na mesma mudança (nunca editado à mão); a verificação do contrato e a de breaking change em `/api/v1` (`.github/scripts/check-api-breaking.sh`) passam. Enum de resposta que cresce sai como `x-extensible-enum`; `pattern` precisa ser válido em ECMA-262; a API não expõe `DELETE`: o que sai é desativado.
- Biblioteca nova entra pelo catálogo `gradle/libs.versions.toml`, com os lockfiles regravados, sem vulnerabilidade High ou Critical nova no scanner da revisão final (M5 E13 e E14).
- Documentação: a referência técnica não volta para o `CLAUDE.md`. Ao fechar cada etapa que muda algo descrito no brain (inclusive o estado do marco), o orquestrador publica a captura `ZenyHotelManager - atualização m6-operacao-fechamento Eyy` com o texto novo ou alterado das notas de tema afetadas; os módulos novos (`housekeeping`, `maintenance`, `reporting`, `endofday`) propõem cada um uma nota de tema nova (`ZenyHotelManager - Módulo housekeeping` e assim por diante). O `README.md` do repositório continua sendo atualizado quando a etapa muda algo que ele descreve.

## Hipóteses e decisões em aberto

| # | Hipótese ou decisão | Impacto se errada / sem decisão | Etapa afetada |
|---|---|---|---|
| H1 | Pacote base `com.zeny.hotel` (herdado de M0). | Ajuste mecânico de caminhos. | todas |
| H2 | Conferida no código em 2026-11-06: `OfflineCommandHandler`, o feed `GET /api/v1/sync/changes` e a execução de checklist (`ChecklistsApi.startExecution` e `completeExecution`) existem, e `ChecklistCompleted` carrega as ocorrências configuradas (`MAINTENANCE`, `LOST_AND_FOUND`, `DAMAGE`). | Se faltar, a etapa afetada para e relata; não reimplementar a base de sync. | E04, E06, E08 |
| H3 | Conferida em parte em 2026-11-06: a `RoomsApi` tem `markClean`, `markInspected`, `rejectInspection`, `markDirty`, `setServiceFlag` e OOO. O registro de discrepância (RF-UH-08) só existe por endpoint. | E04 acrescenta a discrepância à API pública de `rooms` (dependência permitida). | E04, E05, E07, E11 |
| H4 | Conferida em 2026-11-06, **caiu**: a `ReservationsApi` tem `releaseUnused`, `blocksDueForRelease` e `cancelReservation`, mas não tem no-show nem consultas por data, que só existem nos endpoints. | E11 acrescenta a transição e as consultas à API pública de `reservations`; E02 reutiliza as consultas. | E02, E11 |
| H5 | Conferida em 2026-11-06: `NewPosting.sourceReference` dá a idempotência, com a chave do fechamento definida na `BillingApi.post`. Não há bloqueio de data fechada: `post` grava na data corrente. | E11 prova RF-FEC-05 e acrescenta o bloqueio só se a prova falhar. | E06, E11 |
| H6 | Os PDFs do fechamento são guardados em `files` (S3/MinIO) pelo próprio `reporting`, com um índice por data no schema `reporting`; o download é por URL assinada. A aresta `reporting` → `files` já consta na tabela §3.1; `endofday` não acessa `files`. | Mudar de dono pede aresta nova na §3.1. | E09 |
| H7 | Exportação XLSX com biblioteca leve (ex.: fastexcel ou Apache POI) declarada no *version catalog*; os PDFs são renderizados por `printing` (M5): `reporting` monta os dados e chama `printing`, como todo módulo dono de documento. | Troca local ao `reporting`. | E09 |
| H8 | Avisos de pendência do fechamento (RF-NOT-01) são enviados por `endofday` chamando `notifications` (push e painel) para quem tem permissão de acompanhar o fechamento, além do andamento no SSE e do endpoint de pendências. | Ajuste de destinatários é configuração. | E10 |
| H9 | Q-14 (turndown) e Q-15 (cortesia e uso da casa) seguem os padrões da SPEC §10: turndown como tipo de limpeza desativado; cortesia conta na ocupação e não no ADR, uso da casa não conta em nenhum dos dois. | Ajuste de configuração e do cálculo de `DailyStatistics`. | E01, E09 |

**Notas herdadas do M2** (sincronização do roadmap do M2 com o código do M1, 2026-10-15; conferir de novo na sincronização deste marco):

- **E07, OOO pela manutenção (`blockRoom`):** a API pública de OOO de `rooms` (M2 E03) não confere permissão; o caso de uso da manutenção faz o próprio step-up (`authorization.requireOrApproval` com a permissão sensível de OOO de `rooms`) antes de chamá-la. O alerta de reserva alocada no período é de `reservations`, ao escutar `OutOfOrderChanged` (M4 E05/E10); `rooms` não conhece reservas.
- **Rotinas de checklist:** o listener de `BusinessDateAdvanced` da M2 E07 gera uma execução por rotina e data, mas em produção as rotinas só passam a ser geradas quando o `endofday` deste marco avançar a data operacional; o gate deste marco confere isso ponta a ponta.

## Definição global de pronto

- [ ] Tarefas do dia são geradas automaticamente (saídas, permanências, chegadas, faxinas programadas e avulsas), distribuídas por sugestão de créditos, publicadas, executadas online e offline e inspecionadas, com o status da UH seguindo §8.4.
- [ ] Achados e perdidos e danos são registrados; dano aprovado é lançado na conta do hóspede.
- [ ] OS corretivas são abertas (manual, recepção, governança ou checklist), executadas online e offline, colocam a UH em OOO/OOS e a devolvem como Suja ao concluir.
- [ ] O fechamento do dia roda no horário configurado e manualmente com step-up, executa as etapas de RF-FEC-02 na ordem, retoma de onde parou sem duplicar lançamentos e avança a data operacional publicando `BusinessDateAdvanced`.
- [ ] Painel do dia, relatórios operacionais de governança e manutenção, exportação CSV/XLSX, relatório de pendências e PDFs arquivados por data estão disponíveis.
- [ ] Cenário ponta a ponta da Fase 1 passa: reserva → check-in → diária lançada no fechamento → limpeza da permanência → check-out → limpeza de saída.
- [ ] No-show do fechamento gera submissão na FNRH; a falha ou a ausência de um fechamento dispara a regra de alerta (a entrega é da M8 E18).
- [ ] Verificação de módulos, build, testes e verificação do contrato passam.

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

- Toda execução acontece no worktree/branch `roadmap/m6-operacao-fechamento`, criado ou retomado com `superpowers:using-git-worktrees`. Nunca execute na branch principal (`main`).
- Se a branch já existir com trabalho não integrado, continue nela. Se o lote anterior já foi integrado, crie a branch de novo a partir da `main` atualizada.
- Este roadmap é atualizado dentro da branch: tags e handoffs viajam com o código.

**Por etapa**

1. **Escolha:** a primeira etapa, na ordem do documento, marcada `#pendente` e com todas as dependências `#concluida`. Etapas `#bloqueada` e as que dependem delas ficam para trás.
2. **Conferência:** leia a seção da etapa, os handoffs das dependências e o código que eles citam. Se o código divergiu do que a etapa supõe, pare e relate.
3. **Início:** troque a tag para `#em-andamento` (índice e título).
4. **Plano:** use `superpowers:writing-plans`. A spec é a seção desta etapa mais os documentos citados em "Contexto confirmado"; as "Restrições e invariantes" entram como Global Constraints. Salve em `docs/superpowers/plans/AAAA-MM-DD-m6-operacao-fechamento-eNN.md` (AAAA-MM-DD = a data do dia). Antes de executar, o plano passa pela revisão de arquitetura do `orquestrador` (`revisor-arquitetura`); as regras (rodadas, veredito, seção no plano) vivem só nele. A execução já está definida (abaixo): não pergunte ao usuário o método, apenas siga.
5. **Execução:** use `superpowers:subagent-driven-development` com esse plano, executado pelas souls conforme o ciclo por tarefa, a verificação mecânica dos testes e a revisão final da etapa definidos no `orquestrador` (as regras de encaixe vivem só nele). Não invoque `superpowers:finishing-a-development-branch` ao fim da etapa: ele roda uma vez, no fim do lote.
6. **Fechamento:** publique a captura de atualização do brain (bloco "Second Brain"). Marque `#concluida` (índice e título) só com a revisão final limpa e cada critério de aceite verificado com evidência. Diante de impedimento real, marque `#bloqueada` e preencha "Bloqueios" com causa, evidência, impacto e ação de desbloqueio. Preencha "Evidências e handoff". Rulings do ledger que mudam contrato, escopo, segurança, dados ou algo caro de desfazer vão para "Decisões para o usuário".

**Second Brain**

Vale se o `CLAUDE.md` tiver a seção "Conhecimento do projeto no Second Brain"; senão, ignore este bloco.

- O contexto da sessão precisa trazer a linha `BRAIN: carregado (ZenyHotelManager)`, injetada pelo hook. Sem ela, pare e relate.
- Na conferência de cada etapa, leia as notas de tema do brain ligadas a ela (pelo hub `ZenyHotelManager`) e as capturas pendentes do projeto que tratem do assunto: são a referência técnica do projeto. Num handoff antigo, "seção X do `CLAUDE.md`" é a nota de tema correspondente.
- No fechamento de cada etapa que mudou algo descrito no brain (inclusive o estado do roadmap), o orquestrador manda o `brain-escriba` publicar em `00 Inbox/` do vault a captura `ZenyHotelManager - atualização m6-operacao-fechamento Eyy`, com o texto novo ou alterado de cada nota afetada e qual nota ele substitui ou complementa; o contrato de agente externo do vault fica com o `brain-escriba`. Nunca edite notas do brain nem registre referência técnica no `CLAUDE.md`.

**Paradas**

Qualquer outra necessidade de intervenção é decidida pelo orquestrador pela opção recomendada, depois de avaliar prós e contras, com a ruling registrada; só decisão de arquitetura ou system design espera o usuário (regras em "Decisões" do `orquestrador`). O lote para ao concluir uma etapa com **Revisão humana: sim**, diante de decisão de arquitetura sem a qual nada mais anda, nas paradas que ficam com o usuário (push, deploy, publicação, instalação na máquina, apagar trabalho fora da etapa, segredo exposto), quando não resta etapa elegível e ao atingir o limite de etapas do prompt. Etapa que fica `#bloqueada` não para o lote: o orquestrador segue para a próxima elegível que não depende dela.

**Fim do lote**

1. Use `superpowers:finishing-a-development-branch` com a integração já decidida: merge local na `main`, conforme o passo de fechamento do `orquestrador` (branch atualizada e com gate verde, `merge --no-ff`, repositório limpo e pronto para o próximo orquestrador). Push e deploy ficam com o usuário: o relatório termina com o comando `git push origin main` pronto.
2. Liste as "Decisões para o usuário" acumuladas, que já foram tomadas pela opção recomendada, com prós e contras, para revisão. As de arquitetura que esperam o usuário vão como entrevista: perguntas de múltipla escolha, cada uma com uma opção recomendada e justificada (no Claude Code, `AskUserQuestion` com a recomendada em primeiro lugar e o rótulo "(Recomendado)"). Registre as respostas no handoff das etapas e em "Hipóteses e decisões em aberto".

## Prompt de execução

```text
Execute o roadmap docs/roadmaps/roadmap-m6-operacao-fechamento.md deste repositório seguindo a seção "Protocolo de execução" dele, com o plugin superpowers.

Limite desta execução: no máximo 3 etapas concluídas.

1. Confira a pré-condição do protocolo: esta sessão roda como orquestrador e as souls desenvolvedor, testador, revisor-seguranca e revisor-arquitetura estão disponíveis; se não, pare e diga como resolver. Leia CLAUDE.md (e AGENTS.md, se existir) e, no roadmap, o cabeçalho inteiro, o protocolo e o índice. Se o CLAUDE.md exigir o Second Brain, confira a linha BRAIN: carregado no contexto e leia o hub. Rode git status e git branch -a.
2. Se alguma etapa estiver #em-andamento, retome-a a partir do ledger do superpowers e do handoff; não recomece tarefas já commitadas.
3. Crie ou retome o worktree/branch roadmap/m6-operacao-fechamento com superpowers:using-git-worktrees. Não trabalhe na branch principal.
4. Repita, uma etapa por vez: escolha a próxima etapa elegível, marque #em-andamento, planeje com superpowers:writing-plans (spec = seção da etapa + documentos citados), execute com superpowers:subagent-driven-development, feche a etapa (com a captura de atualização do brain) e preencha o handoff conforme o protocolo.
5. Pare nas paradas do protocolo ou ao atingir o limite.
6. No fim, rode superpowers:finishing-a-development-branch com merge local na main, conforme o fechamento do orquestrador: tudo commitado, pronto para git push origin main e o repositório pronto para o próximo orquestrador. Depois, liste as decisões tomadas e apresente como entrevista com opção recomendada só as de arquitetura que esperam por mim.

Qualquer necessidade de intervenção: escolha a opção recomendada depois de uma avaliação crítica dos prós e contras e registre a ruling; intervenção humana só para decisão de arquitetura ou system design. Nunca faça push ou deploy. Não altere tags de etapas que não executou.

Ao parar, responda com: etapas concluídas e o resultado da revisão final de cada uma, a etapa atual e seu estado, o motivo da parada, as etapas #bloqueada com a ação de desbloqueio, a branch e os commits do lote, as capturas do brain publicadas e o próximo passo sugerido.
```

## Índice de etapas

**Fechamento do dia**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E10 | Motor do fechamento do dia | #pendente | — | `EndOfDayRun` com etapas persistidas, retomada, ShedLock, gatilhos automático e manual, métricas e alerta |
| E11 | Etapas financeiras e operacionais do fechamento | #pendente | E10 | No-show, diárias, bloqueios, pré-reservas, UHs Sujas, avanço da data, pendências |
| E14 | No-show na FNRH | #pendente | E11 | Operação nova na porta da FNRH, enviada pela fila de submissões |

**Governança**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E01 | Tipos de limpeza, créditos e faxinas programadas | #pendente | — | Configuração de `CleaningType`, `CreditRule` e `ScheduledCleaning` |
| E02 | Geração automática de tarefas | #pendente | E01, E11 | Tarefas por saída, permanência, chegada, faxina programada e avulsa |
| E03 | Distribuição sugerida e publicação | #pendente | E02 | `AssignmentPlan` balanceado por créditos e escala, publicado com aviso |
| E04 | Execução da tarefa online e offline | #pendente | E03 | Máquina de estados da tarefa, checklist, DND, conflitos RN-22 |
| E05 | Inspeção e liberação da UH | #pendente | E04 | Aprovação/reprovação, UH Inspecionada, aviso de UH pronta |
| E06 | Achados e perdidos e danos | #pendente | E04 | `LostAndFoundItem` com prazo de guarda, `DamageReport` lançado na conta |

**Manutenção**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E07 | Ordens de serviço corretivas e OOO/OOS | #pendente | — | `WorkOrder` com estados, custos, anexos e bloqueio da UH |
| E08 | OS por checklist e app offline da manutenção | #pendente | E07 | Abertura por `ChecklistCompleted` e comandos offline da OS |

**Relatórios**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E09 | Relatórios operacionais e painel do dia | #pendente | E05, E08 | Views `report_*`, painel, relatórios, CSV/XLSX, `DailyStatistics`, arquivo de PDFs |
| E13 | Snapshot e arquivo do fechamento | #pendente | E09, E11 | Estatísticas e PDFs da data gravados pelo fechamento, antes do avanço |

**Gate**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E12 | Gate do marco M6 e fim da Fase 1 | #pendente | E06, E13, E14 | Definição global de pronto e cenário ponta a ponta da Fase 1 |

---

## E10 — Motor do fechamento do dia — #pendente

**Objetivo:** criar o módulo `endofday` com o motor do fechamento (RF-FEC-01, RF-FEC-03, RN-21, ARCHITECTURE §13): `EndOfDayRun` com etapas persistidas, execução ordenada, retomada da etapa pendente, uma execução por data e nunca em paralelo, disparo automático no horário configurado em `hotel` (fuso do hotel) e manual com step-up.

**Por que agora:** é a primeira etapa do marco porque as etapas de negócio (E11) se encaixam neste motor, e o fechamento é o que grava dinheiro imutável (RN-03) e faz a data andar. Separá-lo permite testar idempotência e retomada com etapas falsas antes de mexer em dinheiro.

**Dependências:** nenhuma dentro deste roadmap; exige o gate de M5 `#concluida`. Independente de E01–E09 (conflito esperado em `api/openapi.yaml` se rodarem em branches separadas).

**Escopo:** pacote `endofday` (a criar):

- `EndOfDayRun`, `EndOfDayStep`, `EndOfDayIssue` (SPEC §8.2.9), com a data por valor: a data operacional continua só em `hotel` (`BusinessDay`), e "data fechada" deriva da execução concluída; restrição única por data de execução concluída; lock por ShedLock e lock de banco contra execução paralela.
- Interface interna de etapa (nome, ordem, `execute(date)` idempotente) e registro ordenado; falha numa etapa interrompe e deixa a execução retomável.
- Gatilho automático por `@Scheduled` no horário de RF-CFG-04, no fuso do hotel; `POST /api/v1/end-of-day/runs` manual, ação sensível com step-up (RF-ACS-08); `POST …/runs/{id}/resume`; `GET` de execução, etapas e pendências.
- Publicação do andamento no SSE e aviso de pendências via `notifications` (push e painel) para quem acompanha o fechamento (H8, RF-NOT-01).
- Métricas de duração e de falha do fechamento e alerta de fechamento que falhou ou não rodou no horário (ARCHITECTURE §16).

**Fora de escopo:** as etapas de negócio (E11).

**Critérios de aceite:**

- [ ] Execução com etapas falsas percorre todas na ordem e registra início, fim e resultado de cada uma.
- [ ] Falha forçada na terceira etapa deixa a execução retomável; `POST …/resume` continua da terceira sem reexecutar as duas primeiras (contador no teste).
- [ ] Dois disparos simultâneos para a mesma data resultam em uma única execução.
- [ ] Nova execução para data já fechada → `409` com `code` estável.
- [ ] `POST /api/v1/end-of-day/runs` sem permissão → `403` `APPROVAL_REQUIRED`; com aprovação → executa e audita solicitante e aprovador.
- [ ] O gatilho automático calcula o horário no fuso do hotel (teste com relógio fixo e fuso diferente de UTC).
- [ ] Andamento publicado no SSE.
- [ ] Uma `EndOfDayIssue` registrada gera notificação de pendência do fechamento via `notifications` para quem tem a permissão de acompanhar o fechamento (RF-NOT-01).
- [ ] A duração e a falha do fechamento aparecem em `/actuator/prometheus`, e a regra de alerta está em `docker/prometheus/alerts.yaml`; a entrega a uma pessoa é da M8 E18.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato (`./gradlew checkOpenApi`, tabela do `CLAUDE.md`) → sucesso.

**Revisão humana:** sim

**Notas para o plano:** H8; SPEC §5.13, RN-21 e §8.2.9 e ARCHITECTURE §13; etapas falsas nos testes (as de negócio são da E11). O tipo `notifications.end-of-day-pending` já existe no catálogo do `notifications` e é obrigatório no painel (nota `ZenyHotelManager - Módulo notifications`). Convenções de jobs (nota `ZenyHotelManager - Eventos e jobs`): método com `@SchedulerLock` não devolve tipo primitivo; o ShedLock trava pela hora do banco. Nos testes (nota `ZenyHotelManager - Testes de integração`), os jobs ficam adiados no `@IntegrationTest` e são chamados direto, o relógio avança pelo `MutableClockConfiguration` (devolvido no fim, porque o contexto é compartilhado) e a JVM roda em `America/Sao_Paulo`. Armadilhas do formato antigo: duas execuções simultâneas para a mesma data; retomada que reexecuta etapa concluída; gatilho calculado em UTC; fechamento manual sem step-up; testar a retomada depois de uma "queda" simulada.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Restart no meio da execução (ARCHITECTURE §13): o estado persistido por etapa e o ShedLock com tempo máximo cobrem; testar retomada após "queda" simulada.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E11 — Etapas financeiras e operacionais do fechamento — #pendente

**Objetivo:** implementar no motor de E10, na ordem de RF-FEC-02, as etapas que não dependem de `reporting`: no-show com multa pela política copiada na reserva (RN-15), alerta de saídas pendentes, lançamento de diárias, pacotes e taxas, liberação de bloqueios vencidos (RN-16), expiração de pré-reservas (RN-23), renovação de pré-autorizações (não aplicável até M7), UHs ocupadas → Sujo (RN-09) e avanço da data operacional via `hotel` (que publica `BusinessDateAdvanced`).

**Por que agora:** é o que faz a data operacional andar e as diárias entrarem nas contas; sem isso o hotel não opera. Lançamento é imutável (RN-03), então esta é a parte mais cara de errar do marco e vem antes das demais. As rotinas de checklist do M2 passam a rodar em produção a partir daqui.

**Dependências:** E10.

**Escopo:** `endofday`, chamando só `hotel`, `rooms`, `reservations`, `billing`, `payments` e `notifications`, e o que falta nas APIs públicas de quem é dono do dado:

- `reservations`: transição para `NO_SHOW` e as consultas por data, que hoje só existem nos endpoints (H4); evento de no-show com `performedBy`, publicado pelo outbox.
- Cada etapa idempotente por chave natural; os lançamentos usam a mesma chave do check-out (`NewPosting.sourceReference`, H5). `EndOfDayIssue` para saídas pendentes e falhas não fatais.
- Prova de RF-FEC-05: nada é lançado em data fechada, inclusive com um lançamento concorrente ao avanço da data; correção entra na data corrente com referência ao original.
- Etapa de pré-autorizações registrada como "não aplicável" até M7, com nota na captura de atualização do brain.
- Avanço da data por `hotel`, como última etapa desta entrega.

**Fora de escopo:** snapshot e PDFs (E13); no-show na FNRH (E14); geração das tarefas do novo dia (E02, que escuta o evento); pendências de notas rejeitadas e ARI (M7, M8).

**Critérios de aceite:**

- [ ] Só a reserva confirmada com chegada pendente vira `NO_SHOW`, com a multa da política copiada nela (RN-15), e a UH alocada é solta no mesmo commit.
- [ ] Saída pendente gera `EndOfDayIssue` e o check-out não é feito automaticamente.
- [ ] Diárias, pacotes e taxas das reservas hospedadas são lançados uma única vez por reserva e data, mesmo com a etapa repetida, e o lançamento do check-out e o do fechamento na mesma data não duplicam (teste com os dois caminhos).
- [ ] Pacotes inclusos saem à parte da hospedagem, no código de cada um, sem taxa de serviço, como no check-out (decisão M5 E04 a).
- [ ] Falha forçada no meio do lançamento de diárias e retomada não duplicam nenhum lançamento.
- [ ] Bloqueios vencidos devolvem as UHs não utilizadas ao inventário; pré-reservas vencidas são expiradas.
- [ ] UHs ocupadas passam a Sujo; a etapa de pré-autorizações registra "não aplicável".
- [ ] A data operacional avança só depois de todas as etapas anteriores concluídas, e o evento publicado gera as rotinas de checklist do novo dia (teste).
- [ ] Lançamento depois do avanço cai na data nova, e nenhum lançamento fica numa data fechada, com o caminho concorrente testado (RF-FEC-05).
- [ ] `endofday` depende só dos módulos permitidos (`ModularityTest`); build, testes e contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato (`./gradlew checkOpenApi`, tabela do `CLAUDE.md`) → sucesso.

**Revisão humana:** sim

**Notas para o plano:** H4 e H5; RF-FEC-02, RF-FEC-05, RN-09, RN-15, RN-16 e RN-23; ler o handoff de E10 e os de M5 E04 e E05. A chave da diária já está definida no comentário de `BillingApi.post`: `reservations.reservation:<id>:<data>:room` ou `…:package:<packageId>`. `post` não recebe data: grava na data operacional corrente, então RF-FEC-05 pode estar coberto por construção, mas a etapa precisa provar a corrida com o avanço. Pendências que marcos anteriores deixaram endereçadas a este fechamento, registradas nas notas do brain (conferir na sincronização): só o fechamento chama `ReservationsApi.releaseUnused` para os ids de `blocksDueForRelease()` (M4 E11); a reserva `NO_SHOW` precisa soltar a UH alocada no mesmo commit (gatilho adiado `reservation_assignment_released`, M4 E10); a multa usa o código `NO_SHOW_FEE` (M4 E08); as hospedadas com a saída passada nunca têm check-out automático (RF-FEC-02); o `ProvisionalExpiryJob` já expira pré-reservas a cada 5 min (M4 E09), e a etapa do fechamento convive com ele; turnos de caixa esquecidos abertos ficam para o fechamento listar (M3 E07); `RoomsApi.markDirty(END_OF_DAY)` é a mudança prevista para as ocupadas (M2 E02); `BillingApi.cardPreAuthorizationsExpiring` serve à renovação do M7; a tarifa de grupo não aplica os pacotes do plano (M4 E11: decidir aqui se o bloqueio leva pacotes no lançamento diário). Notas `ZenyHotelManager - Módulo reservations (ciclo de vida, alocação, grupos e busca)`, `ZenyHotelManager - Módulo billing (contas, roteamento e taxa de serviço)` e `ZenyHotelManager - Módulos hotel e staff` (`HotelApi.advanceBusinessDate(expectedCurrent)` é o único caminho de avanço). Nos testes, as datas partem da `HotelApi.currentBusinessDate()`, porque outros testes avançam a data no banco compartilhado. Armadilhas do formato antigo: diária lançada duas vezes após retomada; lançamento aceito em data fechada; multa pela política atual em vez da copiada na reserva; data avançada antes das etapas anteriores concluírem.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Lançamento de pacotes e taxa de turismo (RF-CFG-03) pode exigir dados de `rates` não expostos por `reservations`: se faltar, acrescentar à API de `reservations` (que depende de `rates`) em vez de `endofday` chamar `rates`.

**Rollback / migração:** lançamentos são imutáveis (RN-03); um erro de cálculo em produção se corrige por estorno na data corrente, nunca apagando linhas. Por isso os testes de idempotência são obrigatórios antes do uso real.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E14 — No-show na FNRH — #pendente

**Objetivo:** enviar à FNRH o no-show publicado pelo fechamento, pela fila de submissões do M5 E08, com a operação nova na porta `FnrhGateway` e no adaptador Serpro.

**Por que agora:** a M5 E08 deixou o no-show de fora (H15 do M5), porque o evento só nasce na E11 e o caminho individual diverge no PDF da API.

**Dependências:** E11. Exige a M5 E08, coberta pelo gate do M5.

**Escopo:** `fnrh`: listener do evento de no-show, operação na porta e no adaptador, testes de contrato com WireMock a partir de `docs/fnrh/api-fnrh-v2.4.md`.

**Fora de escopo:** a confirmação no ambiente real, que fica com a M5 E09.

**Critérios de aceite:**

- [ ] O no-show gera uma única `FnrhSubmission`; o evento reentregue não duplica.
- [ ] A submissão segue a ordem por reserva e só sai depois do registro da reserva.
- [ ] O caminho escolhido (`/no-show` ou `/noshow`) e o motivo da escolha ficam no handoff e na lista do que a M5 E09 confirma; se a E09 já tiver fechado, o roteiro dela é reexecutado para o no-show, ou fica a pendência que o gate de inauguração (M8 E13) cobra.
- [ ] Com o Serpro fora do ar, o fechamento conclui e a submissão fica pendente.
- [ ] Build, testes, verificação do contrato e oasdiff sem `ERR` passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Revisão humana:** não

**Notas para o plano:** leia o handoff da M5 E07 ("Notas para a E09") e o da M5 E08. Se a M5 E09 já tiver sido feita, o no-show entra no roteiro de homologação dela e a divergência se resolve lá.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Divergência no PDF sobre o caminho: sem credencial não há como confirmar; registrar a escolha e cobrá-la na homologação.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E01 — Tipos de limpeza, créditos e faxinas programadas — #pendente

**Objetivo:** criar o módulo `housekeeping` com a configuração de tipos de limpeza (RF-GOV-01) — créditos por tipo de UH, modelo de checklist, exigência de inspeção, prioridade padrão e regra de troca de enxoval — e de faxinas programadas por UH ou área (RF-GOV-10).

**Por que agora:** geração, distribuição e inspeção dependem dessas regras.

**Dependências:** nenhuma dentro deste roadmap; exige o gate de M5 `#concluida`. Independente de E07 e E10 (conflito esperado em `api/openapi.yaml` se rodarem em branches separadas).

**Escopo:** pacote `housekeeping` (a criar): `CleaningType`, `CreditRule` (por `RoomType` de `rooms`), vínculo ao `ChecklistTemplate` de `checklists`, `ScheduledCleaning` (local, tipo, frequência em dias, próxima data); endpoints CRUD com permissões próprias; migração no schema do módulo; carga inicial dos tipos da SPEC (saída, permanência, retoque, faxina pesada, pré-chegada, turndown desativado — H9).

**Fora de escopo:** geração das tarefas (E02); movimentação de enxoval (M9).

**Critérios de aceite:**

- [ ] CRUD de `CleaningType` com créditos por tipo de UH, modelo de checklist, `requiresInspection`, prioridade e regra de troca de enxoval, coberto por testes de integração.
- [ ] Crédito para tipo de UH inexistente → `422` com `code` estável; modelo de checklist de contexto incompatível → `422`.
- [ ] CRUD de `ScheduledCleaning` calcula a próxima data a partir da frequência.
- [ ] Carga inicial cria os tipos da SPEC, com turndown desativado.
- [ ] Escrita exige permissão e é auditada.
- [ ] `ModularityTest`, build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato (`./gradlew checkOpenApi`, tabela do `CLAUDE.md`) → sucesso.

**Revisão humana:** não

**Notas para o plano:** requisitos em SPEC RF-GOV-01, RF-GOV-10 e §8.2.10; H9 (turndown carregado desativado). `housekeeping` usa `rooms` e `checklists` só pelas APIs públicas; o `checklists` já aceita tipo de UH e tipo de limpeza como IDs opacos na aplicabilidade dos modelos (`cleaningTypeIds`; nota `ZenyHotelManager - Módulo checklists`), além do vínculo direto ao modelo pedido no escopo: o plano decide qual dos dois escolhe o modelo da tarefa e registra no handoff. Armadilha apontada no formato antigo: um tipo de limpeza sem crédito para algum tipo de UH ativo precisa gerar aviso, não passar calado.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Nenhum relevante.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E02 — Geração automática de tarefas — #pendente

**Objetivo:** gerar as tarefas de governança (RF-GOV-02) a partir de saídas (`GuestCheckedOut`), troca de UH (`RoomMoved`, UH de origem), permanências e chegadas da data (ao receber `BusinessDateAdvanced`), faxinas programadas vencidas e solicitações avulsas da recepção, com créditos e prioridade do tipo de limpeza.

**Por que agora:** sem tarefas não há distribuição nem execução, e o fechamento (E11) já publica o avanço da data que gera as tarefas do dia.

**Dependências:** E01 e E11 (as consultas por data na `ReservationsApi` e o avanço real da data). Mesmo módulo que E03–E06 (sem paralelismo com elas).

**Escopo:** `housekeeping`:

- Listeners `@ApplicationModuleListener` para `GuestCheckedOut`, `RoomMoved` e `BusinessDateAdvanced`; cada origem gera no máximo uma tarefa por (UH, data, tipo).
- Consultas a `reservations` (hospedados, chegadas, saídas da data — H4) e a `rooms` (status) para decidir permanência, pré-chegada (UH vaga há N dias) e prioridade de chegada/early.
- `POST /api/v1/housekeeping-tasks` para solicitações avulsas da recepção (ex.: toalhas extras), com permissão própria.
- `GET /api/v1/housekeeping-tasks?date=…` com filtros e paginação.
- Faxinas programadas: tarefa gerada quando `nextDueOn` ≤ data operacional; `lastDoneOn` atualizado na conclusão (E04).

**Fora de escopo:** distribuição (E03); disparo do avanço de data (E11 publica via `hotel`).

**Critérios de aceite:**

- [ ] `GuestCheckedOut` gera tarefa de saída na UH; `RoomMoved` gera tarefa na UH de origem (testes de módulo).
- [ ] `BusinessDateAdvanced` gera permanência para UHs ocupadas, pré-chegada para UHs vagas há N dias com chegada e tarefas de faxinas programadas vencidas.
- [ ] Reprocessar o mesmo evento não cria segunda tarefa (restrição única por UH, data e tipo).
- [ ] UH com chegada prevista recebe prioridade maior que permanência comum.
- [ ] `POST /api/v1/housekeeping-tasks` cria tarefa avulsa; sem permissão → `403`.
- [ ] A tabela de tarefas não tem colunas de dados pessoais do hóspede.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato (`./gradlew checkOpenApi`, tabela do `CLAUDE.md`) → sucesso.

**Revisão humana:** não

**Notas para o plano:** SPEC RF-GOV-02; H4. Os eventos `GuestCheckedOut` e `RoomMoved` (M5 E01) ainda não têm consumidor de produção no `housekeeping`: este é o primeiro (nota `ZenyHotelManager - Hospedagem e recepção`); o listener de `BusinessDateAdvanced` convive com o das rotinas de checklist (o outbox entrega a cada um). Testes de módulo com o `Scenario` do Spring Modulith; testar as duas ordens de chegada dos eventos (check-out antes e depois do avanço da data). Armadilhas do formato antigo: permanência nunca para UH vaga; nenhum dado pessoal do hóspede copiado para a tarefa.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Ordem de eventos (check-out antes do avanço de data e vice-versa): a restrição única e testes com as duas ordens evitam duplicidade.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E03 — Distribuição sugerida e publicação — #pendente

**Objetivo:** implementar o `AssignmentPlan` (RF-GOV-03): sugestão que balanceia créditos entre as camareiras escaladas no dia (escala de `staff`), agrupa por andar, prioriza chegadas e early check-in e respeita "Não perturbe"; ajuste manual pela governanta; publicação que notifica só quem teve mudança.

**Por que agora:** a execução (E04) só começa com tarefas atribuídas.

**Dependências:** E02. Mesmo módulo que E04–E06 (sem paralelismo com elas).

**Escopo:** `housekeeping`:

- Algoritmo de sugestão determinístico no `domain` (testável sem Spring), usando a escala do dia (`staff`, RF-PES-04), andar das UHs (`rooms`) e sinalização DND.
- `POST /api/v1/assignment-plans/{date}/suggest`, `PATCH` de atribuições, `POST …/publish` e republicação.
- Notificação de tarefa atribuída via `notifications` (RF-NOT-01) só para quem teve mudança; evento de SSE para o painel.

**Fora de escopo:** execução (E04).

**Critérios de aceite:**

- [ ] A sugestão só atribui a camareiras escaladas na data (teste com escala de `staff`).
- [ ] Em casos de teste, a diferença de créditos entre camareiras não passa do maior crédito de uma tarefa.
- [ ] UHs do mesmo andar ficam com a mesma camareira sempre que o balanceamento permite; chegadas e early vêm primeiro na ordem.
- [ ] UH com DND não recebe tarefa de permanência na sugestão inicial.
- [ ] A mesma entrada gera a mesma sugestão (determinismo).
- [ ] Publicar notifica cada camareira atribuída; republicar notifica só quem teve tarefa incluída ou removida.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato (`./gradlew checkOpenApi`, tabela do `CLAUDE.md`) → sucesso.

**Revisão humana:** não

**Notas para o plano:** SPEC RF-GOV-03 e RF-PES-04. O tipo de notificação `notifications.task-assigned` já existe no catálogo do `notifications`, criado no M2 para os módulos futuros usarem (nota `ZenyHotelManager - Módulo notifications`); título e texto nunca levam dado de hóspede (use o número da UH). Registrar no handoff as decisões do algoritmo (o guloso simples basta para 30 UHs). Armadilhas do formato antigo: atribuição a quem não está escalado; republicação que notifica todo mundo; sugestão não determinística.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Algoritmo ótimo é desnecessário para 30 UHs: guloso simples, documentado e testado.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E04 — Execução da tarefa online e offline — #pendente

**Objetivo:** implementar a execução da tarefa de governança (RF-GOV-04, RF-GOV-05) seguindo a máquina de estados de SPEC §8.4 — iniciar, pausar, retomar, concluir com checklist, registrar DND ou recusa — por API online e por comandos offline (`OfflineCommandHandler`, ARCHITECTURE §6.2 e §9), atualizando o status da UH, registrando discrepâncias (RF-UH-08) e tratando conflitos de RN-22.

**Por que agora:** é o coração do app da camareira e o primeiro módulo de negócio a usar a sincronização offline de M2 de ponta a ponta.

**Dependências:** E03. Mesmo módulo que E05 e E06 (sem paralelismo com elas).

**Escopo:** `housekeeping`:

- Transições da tarefa no `domain` com testes; escopo `OWN` para a camareira (só as próprias tarefas).
- Endpoints online de ação (`POST /api/v1/housekeeping-tasks/{id}/start` etc.) e handlers offline dos mesmos comandos, com idempotência por `commandId` e `occurredAt` corrigido pela defasagem.
- Conclusão: chama `checklists` para concluir a execução (H2), `rooms.markClean(room, requiresInspection)` (H3), registra quem limpou, início, fim e duração, atualiza `lastDoneOn` de faxina programada e publica `HousekeepingTaskCompleted`.
- Ocupação encontrada pela camareira (vazia/ocupada, número de hóspedes) enviada a `rooms` para detecção de discrepância.
- Conflito RN-22: tarefa reatribuída ou cancelada enquanto offline → execução registrada e sinalizada à governanta (notificação e marca na tarefa), nunca descartada.
- Feed de sincronização: tarefas da camareira e UHs (número e status, sem dados pessoais — RN-13) no `GET /api/v1/sync/changes`.

**Fora de escopo:** inspeção (E05); achados e danos (E06); ações de estoque e enxoval do checklist (M9).

**Critérios de aceite:**

- [ ] Testes de domínio cobrem todas as transições da tarefa em §8.4, válidas e inválidas.
- [ ] Conclusão de tarefa de tipo sem inspeção deixa a UH Limpa e a tarefa `DONE`; com inspeção, UH Limpa com inspeção pendente e tarefa `AWAITING_INSPECTION`.
- [ ] Lote offline com iniciar e concluir produz o mesmo estado que as chamadas online; o mesmo `commandId` reenviado devolve o resultado anterior sem reaplicar.
- [ ] Tarefa reatribuída enquanto offline: a execução é registrada, a tarefa é marcada como conflito e a governanta é notificada.
- [ ] Camareira com escopo `OWN` agindo sobre tarefa de outra → `403` online e rejeição no resultado do comando offline.
- [ ] `HousekeepingTaskCompleted` publicado; quem limpou, início, fim e duração gravados.
- [ ] Ocupação divergente informada pela camareira gera discrepância em `rooms`.
- [ ] O feed `GET /api/v1/sync/changes` da camareira não contém nome, documento nem contato de hóspede (teste).
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato (`./gradlew checkOpenApi`, tabela do `CLAUDE.md`) → sucesso.

**Revisão humana:** sim

**Notas para o plano:** H2 e H3: se a base de sync ou a API de execução de checklist do M2 não existir, pare e relate (não reimplementar a base). Ler RN-13, RN-22 e ARCHITECTURE §6.2 e §9 e as notas `ZenyHotelManager - Sincronização offline` e `ZenyHotelManager - Módulo checklists` (execução pela API pública, rotina já assumida registrada à parte com alerta). O `rooms` já espera a governança chamar `markClean`/`markInspected`/`rejectInspection` pela `RoomsApi` (nota `ZenyHotelManager - Módulo rooms`); o status da UH nunca muda fora dela. Armadilha de teste conhecida: todo teste que baixa `/api/v1/sync/changes` chama antes `InventoryListenersQuiescence.await(jdbc)` (os listeners do inventário seguram o cursor; nota `ZenyHotelManager - Testes de integração`). Esta etapa fixa o padrão de handlers offline que E05, E06 e E08 vão repetir: registrá-lo no handoff.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Relógio do celular muito adiantado: confiar na correção de defasagem de M2 e testar `occurredAt` fora da tolerância.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E05 — Inspeção e liberação da UH — #pendente

**Objetivo:** implementar a inspeção (RF-GOV-06): a supervisora executa o checklist de inspeção, aprova (UH Inspecionada, tarefa `DONE`) ou reprova (UH Suja, tarefa `REJECTED` de volta à camareira com os itens apontados); inspeção voluntária de UH Limpa; aviso em tempo real à recepção quando a UH fica pronta (RF-GOV-07), por SSE e por notificação de UH liberada (RF-NOT-01).

**Por que agora:** fecha o ciclo de limpeza e libera UHs para check-in (RN-05).

**Dependências:** E04. Independente de E06 (só rodam juntas em branches separadas; conflito em `api/openapi.yaml` e no controller de tarefas).

**Escopo:** `housekeeping` — `Inspection` ligada à tarefa e a uma execução de checklist; endpoints online e handlers offline; `rooms.markInspected`/`markDirty` (H3); notificação à camareira na reprovação (RF-NOT-01); evento de UH pronta no SSE e notificação de UH liberada via `notifications` para a recepção (RN-05 avaliada por `rooms`).

**Fora de escopo:** indicadores de reprovação (E09).

**Critérios de aceite:**

- [ ] Aprovação → UH Inspecionada e tarefa `DONE`; UH vaga e sem OOO passa a pronta para check-in (RN-05).
- [ ] Reprovação → UH Suja, tarefa `REJECTED` com os itens reprovados, camareira notificada; refazer leva a `IN_PROGRESS`.
- [ ] Inspeção voluntária de UH Limpa → Inspecionada.
- [ ] Inspeção sem a permissão própria → `403`.
- [ ] Cliente inscrito no tópico de UHs do SSE recebe o evento quando a UH fica pronta.
- [ ] Inspeção offline funciona pelo mesmo handler pattern de E04.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato (`./gradlew checkOpenApi`, tabela do `CLAUDE.md`) → sucesso.

**Revisão humana:** não

**Notas para o plano:** SPEC RF-GOV-06, RF-GOV-07 e RN-05; padrão de handlers offline no handoff de E04. Os tipos `notifications.room-released` e `notifications.inspection-rejected` já existem no catálogo do `notifications` (nota `ZenyHotelManager - Módulo notifications`). Armadilhas do formato antigo: a UH nunca fica pronta com a inspeção reprovada; quem limpou não inspeciona sem a permissão de inspeção.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Nenhum relevante.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E06 — Achados e perdidos e danos — #pendente

**Objetivo:** implementar achados e perdidos (RF-GOV-08), com guarda, devolução e descarte após o prazo configurado, e danos (RF-GOV-09), com revisão pela recepção ou gerência e lançamento opcional na conta do hóspede via `billing`. Ambos podem ser abertos manualmente, offline ou por ocorrência de `ChecklistCompleted`.

**Por que agora:** completa as ocorrências da governança previstas para a Fase 1.

**Dependências:** E04. Independente de E05 (só rodam juntas em branches separadas; conflito em `api/openapi.yaml`).

**Escopo:** `housekeeping`:

- `LostAndFoundItem`: registro, local de guarda, devolução (a quem, quando, por quem), descarte/doação; job com ShedLock que lista itens vencidos pelo prazo de guarda de `hotel` (RF-CFG-04) para decisão (não descarta sozinho).
- `DamageReport`: registro com custo estimado, revisão, `chargeTo(folio)` lançando via `billing` com chave de idempotência (H5) ou `waive(reason)`.
- Listener de `ChecklistCompleted` para ocorrências do tipo achado ou dano (H2).
- Handlers offline para registro pela camareira; revisão e cobrança só online.

**Fora de escopo:** fotos e anexos de achados e danos, que a SPEC §5.14 não pede. Se forem pedidos depois, `housekeeping` → `files` é permitido pela regra por camadas (acrescentar à tabela §3.1).

**Critérios de aceite:**

- [ ] Achado registrado offline aparece na listagem; devolução registra a quem, quando e por quem.
- [ ] Após o prazo de guarda, o job lista o item como vencido; descarte ou doação registra motivo e usuário.
- [ ] Dano não revisado não pode ser lançado (`409`); revisado e cobrado gera um único lançamento na conta da reserva (repetição não duplica).
- [ ] Dano dispensado registra motivo e não gera lançamento.
- [ ] `ChecklistCompleted` com ocorrência de dano ou achado cria o registro correspondente.
- [ ] Respostas para a camareira não contêm dados do hóspede.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato (`./gradlew checkOpenApi`, tabela do `CLAUDE.md`) → sucesso.

**Revisão humana:** não

**Notas para o plano:** SPEC RF-GOV-08 e RF-GOV-09; H2 e H5. Convenção do M2 para quem consome `ChecklistCompleted` (nota `ZenyHotelManager - Módulo checklists`): o evento pode ser reentregue, então deduplique por `executionId` + `itemKey` + tipo da ação; a execução registrada à parte de uma rotina (`duplicateOf`) publica o próprio evento com as ações e o consumidor decide se as aplica. Padrão de handlers offline no handoff de E04; jobs adiados no `@IntegrationTest` e chamados direto nos testes (nota `ZenyHotelManager - Testes de integração`). Armadilhas do formato antigo: dano lançado duas vezes ou sem revisão; camareira vendo dados do hóspede ao registrar dano.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Dano em UH sem reserva ativa: permitir só registro e dispensa; cobrança exige reserva vinculada.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E07 — Ordens de serviço corretivas e OOO/OOS — #pendente

**Objetivo:** criar o módulo `maintenance` com a OS corretiva (RF-MAN-01, RF-MAN-02, RF-MAN-03): local, categoria, prioridade, descrição, anexos, origem, responsável interno ou terceiro; estados aberta, em andamento, aguardando peça/terceiro, concluída, cancelada; custos de mão de obra, terceiros e peças informadas; bloqueio da UH em OOO/OOS via `rooms` e retorno como Suja ao concluir.

**Por que agora:** a manutenção corretiva é parte da Fase 1 e alimenta o relatório operacional (E09).

**Dependências:** nenhuma dentro deste roadmap; exige o gate de M5 `#concluida`. Independente de E01–E06 e E10 (conflito esperado em `api/openapi.yaml` se rodarem em branches separadas).

**Escopo:** pacote `maintenance` (a criar):

- `WorkOrder` com número legível (gerador de M0), máquina de estados, custos em `Money`; terceiro como texto livre e custo de peças como valor informado até M9.
- Anexos via `files` (dependência permitida).
- `blockRoom(kind, from, to)`: pede OOO ou OOS a `rooms` com período previsto; OOO exige a permissão/step-up definida em M2 (RF-ACS-08). Conclusão devolve a UH (normalmente como Suja).
- Notificação de OS urgente via `notifications` (RF-NOT-01).
- Escopo `OWN` para o técnico; endpoints CRUD e de ação em `/api/v1/work-orders`.

**Fora de escopo:** abertura por checklist e offline (E08); ativos, preventivas e peças do estoque (M9, M10).

**Critérios de aceite:**

- [ ] Testes de domínio cobrem as transições da OS (aberta, em andamento, aguardando, concluída, cancelada), válidas e inválidas.
- [ ] OS com anexo grava o anexo via `files` e o devolve só por URL assinada.
- [ ] Pedido de OOO sem aprovação → `403` `APPROVAL_REQUIRED`; em UH ocupada → erro com `code` estável (RN-07); com aprovação → UH em OOO pelo período.
- [ ] Concluir a OS encerra o OOO/OOS e deixa a UH Suja.
- [ ] OS de prioridade urgente gera notificação para a manutenção.
- [ ] Técnico com escopo `OWN` lista só as próprias OS.
- [ ] `maintenance` não depende de `inventory` (`ModularityTest`); build, testes e contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato (`./gradlew checkOpenApi`, tabela do `CLAUDE.md`) → sucesso.

**Revisão humana:** não

**Notas para o plano:** SPEC RF-MAN-01 a RF-MAN-03, RN-07 e §8.2.12; ver a nota herdada do M2 "E07, OOO pela manutenção" em "Hipóteses e decisões em aberto" (a manutenção faz o próprio step-up antes da API de OOO de `rooms`, que não confere permissão). A tabela da ARCHITECTURE §3.1 já lista `maintenance` → `inventory`, mas a aresta não pode ser usada neste marco. O tipo `notifications.urgent-work-order` já existe no catálogo do `notifications` (nota `ZenyHotelManager - Módulo notifications`). Anexos pela `FilesApi` com finalidade própria do módulo (nota `ZenyHotelManager - Módulo files`). Armadilhas do formato antigo: OOO sem step-up ou em UH com hóspede (RN-07); OS concluída sem devolver a UH.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- OOO com reserva alocada no período (RN-07): `rooms` não conhece reservas; quem alerta e exige a realocação é `reservations`, ao escutar `OutOfOrderChanged` (M4). A OS registra o bloqueio quando `rooms` confirma a abertura do OOO, sem esperar a realocação.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E08 — OS por checklist e app offline da manutenção — #pendente

**Objetivo:** abrir OS automaticamente a partir de ocorrências `MAINTENANCE` de `ChecklistCompleted` (RF-CHK-05, ARCHITECTURE §6.2) e permitir ao técnico operar a OS offline (RF-MAN-07), na mesma base de sincronização do app da governança.

**Por que agora:** completa a manutenção corretiva da Fase 1 e liga checklists à manutenção.

**Dependências:** E07 (sem paralelismo com ela).

**Escopo:** `maintenance` — listener de `ChecklistCompleted` (idempotente por execução e item); handlers offline para abrir OS (ID gerado no cliente), iniciar, pausar, concluir e registrar observações; OS e locais do técnico no feed de sync, sem dados pessoais (RN-13).

**Fora de escopo:** peças do estoque (M9).

**Critérios de aceite:**

- [ ] `ChecklistCompleted` com item `MAINTENANCE` marcado "não ok" gera exatamente uma OS com origem checklist e vínculo à execução; reprocessar não duplica.
- [ ] OS aberta offline é persistida com o UUID do cliente; o mesmo `commandId` reenviado não cria outra.
- [ ] Iniciar, pausar e concluir offline produzem o mesmo estado que online.
- [ ] O feed de sync do técnico contém suas OS e locais, sem dados pessoais de hóspede.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato (`./gradlew checkOpenApi`, tabela do `CLAUDE.md`) → sucesso.

**Revisão humana:** não

**Notas para o plano:** H2. Ler o handoff de E07 e, se já concluída, o de E04 (padrão de handlers offline); E04 não é dependência, então sem ela o padrão vem da nota `ZenyHotelManager - Sincronização offline` (registro criado offline usa o `commandId` como id). Consumo de `ChecklistCompleted` com a mesma convenção da E06 (deduplicar por `executionId` + `itemKey` + tipo; execução à parte com `duplicateOf`; nota `ZenyHotelManager - Módulo checklists`). Testes que baixam `/api/v1/sync/changes` chamam antes `InventoryListenersQuiescence.await(jdbc)` (nota `ZenyHotelManager - Testes de integração`). Armadilhas do formato antigo: OS duplicada por reprocessamento; OS aberta offline com o id trocado pelo servidor; dados pessoais no feed.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Nenhum relevante.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E09 — Relatórios operacionais e painel do dia — #pendente

**Objetivo:** criar o módulo `reporting` com o painel do dia (RF-REL-01), os relatórios operacionais de governança e manutenção (RF-REL-06, RF-GOV-11), o snapshot `DailyStatistics` (SPEC §8.2.9), a exportação CSV/XLSX (RF-REL-07), a geração e o arquivo de PDFs por data e o relatório de pendências do fechamento (RF-FEC-04), consultando só views `report_*` publicadas pelos módulos (ARCHITECTURE §11).

**Por que agora:** o fechamento (E13) gera o snapshot e os PDFs por meio de `reporting`, e a gerência precisa do painel na operação.

**Dependências:** E05, E08. Independente de E10; E09, E10 e E11 tocam `api/openapi.yaml` (conflito se rodarem em branches separadas).

**Escopo:**

- Views `report_*` (migrações nos schemas donos, como parte da API de cada módulo): `rooms` (status), `reservations` (chegadas, saídas, hospedados, ocupação futura), `housekeeping` (tarefas, tempos, créditos, reprovações), `maintenance` (OS por estado, categoria e tempo), `billing` (contas com saldo de hóspedes que saíram, caixas abertos). `fnrh.report_submissions` vem da M5 E08.
- `reporting` (a criar): `GET /api/v1/reports/day-panel`, relatórios de governança e manutenção com filtros, exportação `?format=csv|xlsx` (H7), relatório de pendências.
- API pública para o fechamento: `snapshotDailyStatistics(date)` (UHs disponíveis, OOO, vendidas, cortesias, uso da casa, chegadas, saídas, no-shows, cancelamentos, receitas por departamento; regras de Q-15 — H9) e `archiveDayReports(date)` (PDFs renderizados por `printing` e guardados em `files`, com índice por data no schema `reporting` — H6, H7), ambos idempotentes por data.
- `GET /api/v1/reports/archive?date=…` e download dos PDFs arquivados por URL assinada de `files`.
- Arestas `reporting` → `printing` e `reporting` → `files` acrescentadas à tabela da ARCHITECTURE §3.1.

**Fora de escopo:** KPIs comparativos e relatórios gerenciais (M10).

**Critérios de aceite:**

- [ ] `GET /api/v1/reports/day-panel` devolve chegadas, saídas, hospedados, UHs por status e ocupação de hoje e dos próximos dias, conferidos contra dados semeados.
- [ ] Relatórios de governança (UHs e créditos por camareira, tempo médio por tipo, taxa de reprovação) e de manutenção (OS por estado, categoria e tempo) conferidos contra dados semeados.
- [ ] `?format=csv` e `?format=xlsx` geram arquivos que, relidos no teste, têm as mesmas linhas da resposta JSON.
- [ ] `snapshotDailyStatistics(date)` chamado duas vezes deixa um único registro; cortesia e uso da casa seguem Q-15.
- [ ] `archiveDayReports(date)` renderiza os PDFs via `printing` e os guarda em `files` uma única vez por data; `GET /api/v1/reports/archive?date=…` os lista e devolve URLs assinadas.
- [ ] A tabela da ARCHITECTURE §3.1 lista `printing` e `files` entre as dependências de `reporting`.
- [ ] Relatório de pendências lista contas com saldo de hóspedes que saíram, caixas não fechados e FNRH pendentes.
- [ ] Teste de arquitetura confirma que `reporting` não referencia classes nem tabelas internas de outros módulos.
- [ ] Nenhum relatório operacional de governança ou manutenção expõe dados pessoais de hóspede.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato (`./gradlew checkOpenApi`, tabela do `CLAUDE.md`) → sucesso.

**Revisão humana:** não

**Notas para o plano:** H6, H7 e H9; ARCHITECTURE §11 ("Leitura para relatórios") e a convenção das views na nota `ZenyHotelManager - Schemas, migrações e views de relatório` (nome, contrato no comentário da migração, `GRANT SELECT`/`REVOKE` por view, coluna publicada que não muda de sentido). As views publicadas no M2 a M4 foram feitas para o `reporting` deste marco ler (ver a mesma nota); prova de "só as views" num teste que roda as consultas com um papel que só tem `SELECT` nas views (padrão do `ChecklistReportsTest`). Período em datas convertido no fuso do hotel. H7: a biblioteca XLSX entra no catálogo `gradle/libs.versions.toml`, sem versão literal no build. O formato antigo pedia `ModularityTest` mais uma regra ArchUnit para "`reporting` não referencia classes de outros módulos". Armadilhas do formato antigo: snapshot recalculado gerando duplicata; regra de cortesia e uso da casa diferente de Q-15; dados pessoais em relatório operacional.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Views acopladas ao esquema interno de cada módulo: tratá-las como API do módulo e testá-las no próprio módulo dono.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E13 — Snapshot e arquivo do fechamento — #pendente

**Objetivo:** acrescentar ao fechamento, antes do avanço da data, as etapas de snapshot de `DailyStatistics` e de arquivo dos PDFs da data (RF-FEC-02), pela API pública de `reporting`.

**Por que agora:** `reporting` só existe depois da E09; o motor e as etapas financeiras já rodam desde a E11, e estas duas se encaixam neles sem mexer em dinheiro.

**Dependências:** E09, E11.

**Escopo:** `endofday` chama `snapshotDailyStatistics(date)` e `archiveDayReports(date)` do `reporting`, ambas idempotentes por data, numa posição anterior ao avanço; pendências da E09 registradas como `EndOfDayIssue`.

**Fora de escopo:** os relatórios em si (E09); pendências de notas rejeitadas e ARI (M7, M8).

**Critérios de aceite:**

- [ ] Depois de um fechamento, o snapshot da data e os PDFs ficam em `reporting`, uma única vez, mesmo com a etapa repetida ou retomada.
- [ ] Falha na geração do PDF deixa a execução retomável e a data sem avançar (teste).
- [ ] Um fechamento executado antes desta etapa existir continua válido: datas sem snapshot não quebram o painel nem os relatórios (teste).
- [ ] `endofday` depende só dos módulos permitidos (`ModularityTest`); build, testes e contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato (`./gradlew checkOpenApi`, tabela do `CLAUDE.md`) → sucesso.

**Revisão humana:** não

**Notas para o plano:** o `endofday` depende de `reporting` por exceção registrada na tabela da ARCHITECTURE §3.1, na mesma camada; não amplie essa aresta além da API de snapshot e arquivo. Leia os handoffs de E09 e E11.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Geração de PDF lenta no meio do fechamento: medir a duração na métrica da E10 e registrar no handoff.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E12 — Gate do marco M6 e fim da Fase 1 — #pendente

**Objetivo:** confirmar, a partir de uma cópia limpa do repositório, que a definição global de pronto do M6 é atendida e que o cenário ponta a ponta da Fase 1 funciona com os módulos reais; deixar o roadmap do M7 pronto para ser sincronizado.

**Por que agora:** marca o fim da Fase 1 da SPEC ("o hotel opera a hospedagem") e é o critério de entrada do M7.

**Dependências:** E06, E13, E14 (cobrem transitivamente todas as demais; sem paralelismo).

**Escopo:**

- Teste de integração ponta a ponta (a criar): reserva → check-in → fechamento do dia (diária lançada, UH Suja, data avançada) → tarefa de permanência gerada, distribuída, executada offline e inspecionada → check-out com saldo zero → tarefa de saída gerada e concluída → submissões FNRH de check-in e check-out (WireMock).
- Rodar do zero numa cópia limpa: build, testes, verificação de módulos e do contrato.
- Conferir a "Definição global de pronto"; registrar pendências herdadas (ex.: homologação da FNRH de M5, se ainda bloqueada).
- Atualizar `docs/roadmaps/README.md` registrando a conclusão do M6 e da Fase 1 no histórico de revisões.

**Fora de escopo:** funcionalidades novas. Problemas encontrados voltam para a etapa de origem.

**Critérios de aceite:**

- [ ] Todos os itens da "Definição global de pronto" marcados, cada um com evidência numa cópia limpa.
- [ ] O teste ponta a ponta da Fase 1 passa usando os módulos reais (só a FNRH em WireMock).
- [ ] Pendências herdadas (ex.: homologação da FNRH) estão listadas com a ação de desbloqueio.
- [ ] `docs/roadmaps/README.md` registra a conclusão do M6 e da Fase 1 no histórico de revisões.

**Validação obrigatória:**

- Comandos oficiais de build e teste e verificação do contrato (tabela do `CLAUDE.md`) numa cópia limpa → sucesso.

**Revisão humana:** sim

**Notas para o plano:** ler o handoff de todas as etapas do marco. Convenção dos gates anteriores (nota `ZenyHotelManager - Estado por marco`): o cenário do marco também vira teste de integração na raiz do pacote de testes (como `MilestoneM3EndToEndTest` e `MilestoneM4EndToEndTest`, com os modelos de role e o `MutableClock`), e o gate confere o ambiente ponta a ponta num `docker compose` isolado, pelo proxy, registrando o que só os testes provam. A nota herdada do M2 "Rotinas de checklist" (em "Hipóteses e decisões em aberto") pede conferir aqui que as rotinas passam a ser geradas quando o `endofday` avança a data. A captura de atualização do brain desta etapa leva o fim do M6 e da Fase 1 para `ZenyHotelManager - Estado por marco`. Armadilhas do formato antigo: item sem evidência; ponta a ponta com mocks entre módulos; pendências herdadas omitidas.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O cenário ponta a ponta atravessa muitos módulos e pode ficar frágil: usar relógio fixo e dados semeados mínimos.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa (marco M7): —

---

## Histórico de revisões

- 2026-10-13 — roadmap criado.
- 2026-10-13 — aplicadas as decisões da entrevista SPEC × ARCHITECTURE (regra por camadas na ARCHITECTURE §3.1; documentos montados pelo dono e renderizados por `printing`): H6 passa a guardar os PDFs do fechamento em `files`; H7 renderiza PDFs via `printing`; H8 envia pendências do fechamento por `notifications`; E05 notifica UH liberada; E09 acrescenta `reporting` → `printing`/`files` à §3.1; E10 e E11 chamam `notifications`; fora de escopo de E06 reescrito (SPEC não pede fotos).
- 2026-10-15 — notas herdadas do M2 acrescentadas em "Hipóteses e decisões em aberto" (step-up da manutenção antes da API de OOO de `rooms`; rotinas de checklist geradas só com o `endofday`) e corrigida a contradição de camadas no risco da E07: quem alerta e exige a realocação é `reservations`, ao escutar `OutOfOrderChanged`. Tags inalteradas.
- 2026-10-22 — migrado para o formato da skill development-roadmap com superpowers (branch `roadmap/m6-operacao-fechamento`, protocolo e prompt de execução únicos, etapas sem prompt e estratégia) e para o Second Brain (notas de tema no lugar das seções do CLAUDE.md, captura de atualização por etapa); decisão do usuário.
- 2026-10-27 — protocolo de execução migrado para as souls do Second Brain: a sessão roda como `orquestrador-etapa` (`claude --agent orquestrador-etapa`), e as tarefas passam por `desenvolvedor` → `testador` → verificação mecânica dos testes → `revisor-seguranca`; a captura do brain é publicada pelo `brain-escriba`. Sem fallback para os prompts padrão do superpowers nem para `executing-plans`. Nenhuma etapa, tag, evidência ou handoff mudou.
- 2026-10-29 — protocolo de execução alinhado ao template atual da skill `development-roadmap`: o orquestrador passou de `orquestrador-etapa` para `orquestrador` (`claude --agent orquestrador`), entrou o papel "Revisor de plano" (`revisor-arquitetura`, pré-condição das souls e passo 1 do prompt) e o passo "Plano" diz que o plano passa pela revisão de arquitetura do orquestrador antes de executar. Só "Como usar", "Protocolo de execução" e "Prompt de execução" mudaram; etapas, handoffs e entradas anteriores do histórico seguem como estavam.
- 2026-11-06 — revisão de arquitetura (`docs/superpowers/specs/2026-11-06-revisao-arquitetura-roadmaps-design.md`). Protocolo e prompt alinhados ao template de 2026-11-05. O fechamento passou para o início do roadmap: E10 e E11 vêm antes da governança, porque gravam dinheiro imutável e fazem a data andar. A E11 passou a depender só da E10 e foi dividida: o snapshot e os PDFs foram para a nova E13, e o no-show na FNRH para a nova E14. O gate passou a depender de E06, E13 e E14. A E10 ganhou métricas e alerta (ARCHITECTURE §16). Hipóteses H2 a H6 conferidas no código: a H4 caiu, e a H6 e o contexto citavam a aresta `endofday` → `files`, que não existe; o nome da view da FNRH foi corrigido para `fnrh.report_submissions`. Nenhuma etapa renumerada; tags inalteradas. Depois da revisão do `revisor-arquitetura`: `BusinessDay` fica só em `hotel`, a entrega do alerta passa à M8 E18, a E14 cobra o no-show na homologação e a H2 foi fechada.
