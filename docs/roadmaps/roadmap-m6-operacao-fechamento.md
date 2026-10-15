# Roadmap — M6 Operação diária e fechamento

> Arquivo: `docs/roadmaps/roadmap-m6-operacao-fechamento.md` · criado em 2026-10-13 · última revisão 2026-10-15
>
> Marco 6 de 11 · mapa dos marcos em [`docs/roadmaps/README.md`](README.md) · marco anterior: [M5 — Recepção, FNRH e impressão](roadmap-m5-recepcao-fnrh.md) · próximo marco: [M7 — Pagamentos integrados e fiscal](roadmap-m7-pagamentos-fiscal.md)
>
> **Pré-requisito:** a etapa de gate do marco M5 (última etapa de `docs/roadmaps/roadmap-m5-recepcao-fnrh.md`) está `#concluida`.

## Como usar

Duas formas, ambas na raiz do repositório:

- **Manual:** cole o prompt de uma etapa numa sessão limpa do Claude Code. Uma etapa por sessão.
- **Automática:** cole o prompt da seção "Execução automática" numa sessão limpa; ela executa até 3 etapas em sequência, um subagente por etapa mais um verificador, e para para revisão. Para não parar em cada pedido de permissão, libere antes os comandos de build/teste do projeto (registrados no `CLAUDE.md`) nas permissões do Claude Code.

**Este roadmap foi escrito antes do código existir.** Antes da primeira etapa, sincronize-o com o código real usando a skill `development-roadmap` (modo atualização): confira caminhos, APIs públicas dos módulos de M1–M5 e convenções registradas nos handoffs e no `CLAUDE.md`, e ajuste as etapas pendentes.

Etapas marcadas como paralelizáveis só devem rodar ao mesmo tempo em worktrees git separados; na mesma pasta, rode-as uma de cada vez.

Em ambas, quem executa segue o "Protocolo de execução", atualiza a tag da etapa e preenche "Evidências e handoff". Não pule dependências. Revise e faça commit entre etapas ou lotes — nenhuma sessão faz commit por conta própria.

## Objetivo

Fechar a Fase 1 da SPEC: o hotel opera a hospedagem. A governança gera, distribui, executa (inclusive offline) e inspeciona as tarefas de limpeza; a manutenção corretiva abre e conclui ordens de serviço com OOO/OOS; o fechamento do dia roda automaticamente, é idempotente e retomável, lança diárias e avança a data operacional; e o painel do dia e os relatórios operacionais ficam disponíveis, com os relatórios do fechamento arquivados em PDF.

## Contexto confirmado

- Requisitos: SPEC §5.14 (GOV), §5.16 (MAN, só a parte corretiva: RF-MAN-01, 02, 03, 07), §5.13 (FEC), §5.23 RF-REL-01, RF-REL-06 e RF-REL-07, §5.22 RF-NOT-01 (avisos de tarefa atribuída, UH liberada, inspeção reprovada, OS urgente e pendência do fechamento).
- Regras: RN-05, RN-08 (transições de status), RN-09 (sujeira automática), RN-13 (minimização no app operacional), RN-15 (multa de no-show pela política vigente), RN-16 (bloqueio vencido), RN-21 (fechamento único), RN-22 (offline), RN-23 (pré-reserva), RF-FEC-05 (não lançar em data fechada).
- Modelo de domínio: SPEC §8.2.9 (`EndOfDayRun`, `EndOfDayStep`, `EndOfDayIssue`, `DailyStatistics`), §8.2.10 (`CleaningType`, `CreditRule`, `HousekeepingTask`, `AssignmentPlan`, `Inspection`, `ScheduledCleaning`, `LostAndFoundItem`, `DamageReport`), §8.2.12 (`WorkOrder`; `Asset`, `PreventivePlan` e `WorkOrderPart` ficam fora); máquinas de estado da tarefa de governança e do status de governança da UH em §8.4; eventos em §8.3 (`GuestCheckedOut`, `RoomMoved`, `ChecklistCompleted`, `HousekeepingTaskCompleted`, `BusinessDateAdvanced`).
- Arquitetura: ARCHITECTURE §3.1 — `housekeeping` depende de `shared`, `hotel`, `rooms`, `reservations`, `staff`, `checklists`, `billing`, `notifications`; `maintenance` depende de `shared`, `hotel`, `rooms`, `staff`, `checklists`, `inventory`, `files`, `notifications` (`inventory` só existe em M9); `endofday` depende de `shared`, `hotel`, `rooms`, `reservations`, `billing`, `payments`, `reporting`, `notifications`, `files`; `reporting` consulta só views `report_*` (§11). Regra por camadas da §3.1: qualquer módulo pode chamar os serviços de plataforma (`files`, `notifications`, `printing`, `hotel`) e depender de camadas inferiores sem aprovação, desde que o grafo siga acíclico e a aresta nova seja acrescentada à tabela §3.1 na mesma mudança; `notifications` e `printing` nunca escutam eventos de negócio — quem gera o aviso ou é dono do documento os chama. Fluxo offline em §6.2 e §9; jobs em §13 (fechamento idempotente e retomável com ShedLock; geração de tarefas após `BusinessDateAdvanced`); inversão de dependência em §3.1 (`housekeeping` escuta `BusinessDateAdvanced` de `hotel`).
- Comandos de build, teste, lint e verificação do contrato: registrados no `CLAUDE.md` por M0 E01; não são repetidos aqui.
- Convenções herdadas, documentadas no `CLAUDE.md`: M0 (API, erros, migrações, `Money`, IDs, idempotência, outbox, ShedLock), M1 (autenticação, `authz.require`/escopo `OWN`/limites/step-up, `@Audited`, data operacional, `staff` e escala, `files`), M2 (`rooms` e status, SSE, sync offline com `OfflineCommandHandler`, `checklists`, `notifications`), M3 (`guests`, `billing`, `payments` manual), M4 (`rates`, `reservations`, disponibilidade), M5 (`frontdesk`, eventos de hospedagem, `fnrh.report_pending_submissions`, documentos montados pelo módulo dono e renderizados por `printing`).
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

## Hipóteses e decisões em aberto

| # | Hipótese ou decisão | Impacto se errada / sem decisão | Etapa afetada |
|---|---|---|---|
| H1 | Pacote base `com.zeny.hotel` (herdado de M0). | Ajuste mecânico de caminhos. | todas |
| H2 | M2 entregou `OfflineCommandHandler`, o feed `GET /api/v1/sync/changes` com `change_seq` e a API de execução de checklist (`complete(execution, answers, occurredAt)`), e `ChecklistCompleted` carrega as ocorrências configuradas (manutenção, achado, dano). | Se faltar, a etapa afetada para e relata; não reimplementar a base de sync. | E04, E06, E08 |
| H3 | `rooms` (M2) expõe comandos de status de governança (`markClean` com inspeção pendente, `markInspected`, `markDirty`), sinalização do hóspede (DND), OOO/OOS e registro de discrepância (RF-UH-08). | Acrescentar à API pública de `rooms` o que faltar (dependência permitida). | E04, E05, E07, E11 |
| H4 | `reservations` (M4/M5) expõe consultas por data (chegadas, saídas, hospedados) e comandos `markNoShow`, expiração de pré-reservas e liberação de bloqueios vencidos. | Acrescentar à API pública de `reservations` o que faltar. | E02, E11 |
| H5 | `billing` (M3) aceita lançamento com chave de idempotência por origem (ex.: `(reserva, data, EOD)`) e já impede lançamento em data fechada (RF-FEC-05). | Se não, E11 acrescenta a chave e o bloqueio em `billing`. | E06, E11 |
| H6 | Os PDFs do fechamento são guardados em `files` (S3/MinIO) pelo próprio `reporting`, com um índice por data no schema `reporting`; o download é por URL assinada. A aresta `reporting` → `files` é permitida pela regra por camadas e entra na tabela §3.1 em E09. | Se preferir que `endofday` guarde (aresta `endofday` → `files` já listada na §3.1), mover só a chamada de armazenamento. | E09 |
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
- [ ] Verificação de módulos, build, testes e verificação do contrato passam.

## Protocolo de execução

Vale para toda etapa, executada manualmente ou pelo orquestrador. Cada etapa declara sua estratégia em "Estratégia de execução".

**Papéis**

- **Sessão principal** (no modo automático, o subagente implementador): a única que edita código e este roadmap. Implementa, testa, atualiza tags e handoff.
- **Investigação** (subagente `Explore`, somente leitura): para material extenso que será lido mas não editado. Recebe as perguntas listadas na etapa e devolve um resumo com caminhos e linhas. Arquivos que serão editados são lidos diretamente pela sessão principal.
- **Implementação paralela** (subagentes `general-purpose`, só na estratégia `paralela`): cada um recebe uma parte com os arquivos listados na etapa, disjuntos dos demais; roda só os testes focados da sua parte; não toca arquivos compartilhados (build, configuração, este roadmap).
- **Verificação independente** (subagente `general-purpose` novo, que não participou da implementação): confere a etapa contra os critérios de aceite. Não edita nada.

**Estratégias:** `direta` — sem subagentes, a sessão principal valida · `padrão` — investigação quando indicada + verificação independente obrigatória · `paralela` — `padrão` + partes paralelas.

**Passo a passo**

1. **Preparação:** leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho deste roadmap, a seção da etapa e o handoff das dependências; rode `git status`. Confirme que as dependências estão `#concluida`, que a etapa não está `#bloqueada` e que o código bate com o handoff das dependências; se não, pare e relate.
2. **Snapshot inicial:** registre o estado da árvore de trabalho, sem commit e sem alterar o índice do git:

   ```bash
   idx=$(mktemp -u); cp "$(git rev-parse --git-path index)" "$idx" 2>/dev/null; GIT_INDEX_FILE="$idx" git add -A && GIT_INDEX_FILE="$idx" git write-tree; rm -f "$idx"
   ```

   Anote o hash impresso como "Snapshot inicial" no handoff da etapa (no modo automático, use o hash recebido do orquestrador em vez de tirar outro). Para ver tudo o que a etapa mudou, rode o mesmo comando de novo e compare: `git diff HASH_INICIAL HASH_ATUAL`. Se `git status` mostrar artefatos de build não ignorados, corrija o `.gitignore` antes do snapshot — senão o diff fica lento e poluído.
3. **Início:** troque a tag da etapa para `#em-andamento` no índice e no título.
4. **Investigação** (se a etapa listar perguntas): dispare um subagente `Explore` com essas perguntas.
5. **Implementação:** quando os critérios descrevem comportamento verificável, escreva os testes primeiro e confirme que falham. Implemente em passos pequenos; na estratégia `paralela`, dispare as partes paralelas e integre-as. Rode as validações obrigatórias da etapa.
6. **Verificação independente** (`padrão` e `paralela`): dispare um subagente `general-purpose` novo com o "Prompt do verificador" abaixo, trocando ETAPA pelo ID da etapa e HASH_INICIAL pelo snapshot. Se o parecer for REPROVADO, corrija e verifique de novo com um verificador novo. Após duas reprovações, pare e relate, mantendo `#em-andamento`.
7. **Fechamento:** marque `#concluida` (índice e título) só com todos os critérios atendidos e, quando houver verificação, parecer APROVADO. Diante de impedimento real, marque `#bloqueada` e preencha "Bloqueios" com causa, evidência, impacto e ação de desbloqueio. Preencha "Evidências e handoff" e responda com um resumo curto. Não faça commit, push ou deploy.

**Prompt do verificador**

```text
Você é o verificador independente da etapa ETAPA do roadmap docs/roadmaps/roadmap-m6-operacao-fechamento.md. Você não participou da implementação e não deve editar nenhum arquivo.
1. Leia CLAUDE.md (se existir), o cabeçalho do roadmap e a seção ETAPA inteira (objetivo, escopo, fora de escopo, estratégia, critérios de aceite, validação obrigatória).
2. Veja o que mudou desde o início da etapa: gere um snapshot do estado atual com o comando da seção "Protocolo de execução" e rode git diff --stat HASH_INICIAL seguido do hash gerado; depois leia o diff completo dos arquivos relevantes.
3. Rode todos os comandos da validação obrigatória da etapa e guarde a saída relevante.
4. Para cada critério de aceite, decida APROVADO ou REPROVADO com evidência concreta (arquivo:linha, nome do teste, saída de comando). Critério sem evidência é REPROVADO.
5. Ataque os pontos listados em "Foco do verificador" na etapa e aponte também: mudanças fora do escopo, testes que não exercitam o comportamento (passariam mesmo com o código errado), segredos ou dados pessoais expostos e violações das restrições do roadmap.
Responda com: parecer geral (APROVADO só se todos os critérios forem APROVADO e não houver problema grave), tabela critério → resultado → evidência, problemas por gravidade e comandos executados.
```

## Execução automática

Cole o prompt abaixo numa sessão limpa do Claude Code, na raiz do repositório. Ajuste o limite de etapas se quiser.

```text
Você é o orquestrador do roadmap docs/roadmaps/roadmap-m6-operacao-fechamento.md neste repositório. Seu papel é coordenar subagentes, não implementar: não edite código e mantenha seu contexto enxuto (leia o roadmap e os relatórios dos subagentes, não o código).

Limite desta execução: no máximo 3 etapas concluídas.

Preparação:
1. Leia CLAUDE.md (e AGENTS.md, se existir) e, no roadmap, o cabeçalho, o "Protocolo de execução" e o índice.
2. Rode git status. Se alguma etapa estiver #em-andamento, pare e pergunte ao usuário como prosseguir — provavelmente é uma execução interrompida.

Repita, uma etapa por vez (nunca em paralelo):
1. Escolha a primeira etapa, na ordem do documento, marcada #pendente e com todas as dependências #concluida. Etapas #bloqueada e as que dependem delas ficam para trás. Se não houver candidata, pare.
2. Gere o snapshot inicial com o comando do protocolo e guarde o hash.
3. Dispare um subagente general-purpose com o prompt da etapa copiado literalmente da seção dela, acrescido deste adendo: "Você está rodando como subagente do orquestrador; este adendo prevalece sobre o prompt acima. O snapshot inicial já foi tirado: use HASH e não tire outro. Não dispare subagentes: faça a investigação você mesmo, implemente em sequência as partes paralelas, se houver, e pule a verificação independente, que o orquestrador fará. Não marque a etapa como #concluida: ao terminar, deixe-a #em-andamento com 'Evidências e handoff' preenchido e relate o resultado das validações. Diante de bloqueio real, marque #bloqueada como o prompt manda." Substitua HASH pelo hash do passo 2.
4. Se o subagente relatar bloqueio, divergência do plano ou validação falhando que não conseguiu resolver, pare.
5. Qualquer que seja a estratégia, dispare um subagente general-purpose novo com o "Prompt do verificador" do protocolo (ETAPA e HASH_INICIAL preenchidos). Se REPROVADO, mande os problemas ao mesmo implementador, se puder retomá-lo; senão, dispare um implementador novo com o prompt da etapa, o adendo, o parecer do verificador e o handoff atual. Verifique de novo com um verificador novo; após duas reprovações, pare.
6. Com o aceite, troque a tag da etapa para #concluida no índice e no título e acrescente ao handoff o parecer do verificador.
7. Se a etapa tiver "Revisão humana: sim", pare para o usuário revisar.

Pare também ao atingir o limite desta execução. Nunca faça commit, push ou deploy e não altere tags de etapas que não executou.

Ao parar, responda com: etapas concluídas e seus pareceres, a etapa atual e seu estado, o motivo da parada, as etapas #bloqueada com a ação de desbloqueio de cada uma, os arquivos alterados (git status --short) e o próximo passo sugerido — normalmente revisar e fazer commit antes da próxima execução.
```

## Índice de etapas

**Governança**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E01 | Tipos de limpeza, créditos e faxinas programadas | #pendente | — | Configuração de `CleaningType`, `CreditRule` e `ScheduledCleaning` |
| E02 | Geração automática de tarefas | #pendente | E01 | Tarefas por saída, permanência, chegada, faxina programada e avulsa |
| E03 | Distribuição sugerida e publicação | #pendente | E02 | `AssignmentPlan` balanceado por créditos e escala, publicado com aviso |
| E04 | Execução da tarefa online e offline | #pendente | E03 | Máquina de estados da tarefa, checklist, DND, conflitos RN-22 |
| E05 | Inspeção e liberação da UH | #pendente | E04 | Aprovação/reprovação, UH Inspecionada, aviso de UH pronta |
| E06 | Achados e perdidos e danos | #pendente | E04 | `LostAndFoundItem` com prazo de guarda, `DamageReport` lançado na conta |

**Manutenção**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E07 | Ordens de serviço corretivas e OOO/OOS | #pendente | — | `WorkOrder` com estados, custos, anexos e bloqueio da UH |
| E08 | OS por checklist e app offline da manutenção | #pendente | E07 | Abertura por `ChecklistCompleted` e comandos offline da OS |

**Relatórios e fechamento**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E09 | Relatórios operacionais e painel do dia | #pendente | E05, E08 | Views `report_*`, painel, relatórios, CSV/XLSX, `DailyStatistics`, arquivo de PDFs |
| E10 | Motor do fechamento do dia | #pendente | — | `EndOfDayRun` com etapas persistidas, retomada, ShedLock, gatilhos automático e manual |
| E11 | Etapas do fechamento do dia | #pendente | E02, E09, E10 | No-show, diárias, bloqueios, pré-reservas, UHs Sujas, estatísticas, PDFs, avanço da data, pendências |

**Gate**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E12 | Gate do marco M6 e fim da Fase 1 | #pendente | E06, E11 | Definição global de pronto e cenário ponta a ponta da Fase 1 |

---

## E01 — Tipos de limpeza, créditos e faxinas programadas — #pendente

**Objetivo:** criar o módulo `housekeeping` com a configuração de tipos de limpeza (RF-GOV-01) — créditos por tipo de UH, modelo de checklist, exigência de inspeção, prioridade padrão e regra de troca de enxoval — e de faxinas programadas por UH ou área (RF-GOV-10).

**Por que agora:** geração, distribuição e inspeção dependem dessas regras.

**Dependências e paralelismo:** nenhuma dentro deste roadmap; exige o gate de M5 `#concluida`. Paralelizável com E07 e E10 em worktrees separados; conflito em `api/openapi.yaml`.

**Escopo:** pacote `housekeeping` (a criar): `CleaningType`, `CreditRule` (por `RoomType` de `rooms`), vínculo ao `ChecklistTemplate` de `checklists`, `ScheduledCleaning` (local, tipo, frequência em dias, próxima data); endpoints CRUD com permissões próprias; migração no schema do módulo; carga inicial dos tipos da SPEC (saída, permanência, retoque, faxina pesada, pré-chegada, turndown desativado — H9).

**Fora de escopo:** geração das tarefas (E02); movimentação de enxoval (M9).

**Passos de implementação:**

1. Testes de integração dos endpoints e da validação (crédito por tipo de UH inexistente → erro; modelo de checklist de outro contexto → erro).
2. Implementar módulo, migração, carga inicial e endpoints.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "como `rooms` (tipos de UH, áreas) e `checklists` (modelos, contextos, aplicabilidade) expõem suas APIs públicas, segundo o código e o handoff do gate de M2?".
- Partes paralelas: nenhuma.
- Foco do verificador: `housekeeping` acessando pacotes internos de `rooms` ou `checklists`; tipo de limpeza sem crédito para algum tipo de UH ativo sem aviso.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E01 — "Tipos de limpeza, créditos e faxinas programadas" do roadmap docs/roadmaps/roadmap-m6-operacao-fechamento.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução", as restrições e a hipótese H9 —, a seção E01 inteira, docs/SPEC.md RF-GOV-01 e RF-GOV-10 e §8.2.10. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que o gate do marco M5 em docs/roadmaps/roadmap-m5-recepcao-fnrh.md está #concluida; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar o módulo housekeeping com CRUD de tipos de limpeza (créditos por tipo de UH, modelo de checklist, exigência de inspeção, prioridade, regra de troca de enxoval) e de faxinas programadas por UH ou área, com migração própria e carga inicial dos tipos da SPEC (turndown desativado). Não gere tarefas nem mova enxoval.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E01 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume as APIs públicas de rooms e checklists (código e handoff do gate de M2);
- escreva os testes de integração primeiro, implemente, regenere o contrato e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em acesso a pacotes internos de outros módulos e tipos de UH sem crédito.

Critérios de aceite (detalhes na seção E01):
- CRUD de tipos de limpeza e faxinas programadas coberto por testes de integração, com validação de tipo de UH e de contexto do checklist;
- carga inicial presente;
- ModularityTest, build, testes e contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E01 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] CRUD de `CleaningType` com créditos por tipo de UH, modelo de checklist, `requiresInspection`, prioridade e regra de troca de enxoval, coberto por testes de integração.
- [ ] Crédito para tipo de UH inexistente → `422` com `code` estável; modelo de checklist de contexto incompatível → `422`.
- [ ] CRUD de `ScheduledCleaning` calcula a próxima data a partir da frequência.
- [ ] Carga inicial cria os tipos da SPEC, com turndown desativado.
- [ ] Escrita exige permissão e é auditada.
- [ ] `ModularityTest`, build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Tarefa de verificação do contrato (CLAUDE.md) → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Nenhum relevante.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E02 — Geração automática de tarefas — #pendente

**Objetivo:** gerar as tarefas de governança (RF-GOV-02) a partir de saídas (`GuestCheckedOut`), troca de UH (`RoomMoved`, UH de origem), permanências e chegadas da data (ao receber `BusinessDateAdvanced`), faxinas programadas vencidas e solicitações avulsas da recepção, com créditos e prioridade do tipo de limpeza.

**Por que agora:** sem tarefas não há distribuição nem execução; o fechamento (E11) depende de as tarefas do dia nascerem ao avançar a data.

**Dependências e paralelismo:** E01. Não paralelizável com E03–E06 (mesmo módulo).

**Escopo:** `housekeeping`:

- Listeners `@ApplicationModuleListener` para `GuestCheckedOut`, `RoomMoved` e `BusinessDateAdvanced`; cada origem gera no máximo uma tarefa por (UH, data, tipo).
- Consultas a `reservations` (hospedados, chegadas, saídas da data — H4) e a `rooms` (status) para decidir permanência, pré-chegada (UH vaga há N dias) e prioridade de chegada/early.
- `POST /api/v1/housekeeping-tasks` para solicitações avulsas da recepção (ex.: toalhas extras), com permissão própria.
- `GET /api/v1/housekeeping-tasks?date=…` com filtros e paginação.
- Faxinas programadas: tarefa gerada quando `nextDueOn` ≤ data operacional; `lastDoneOn` atualizado na conclusão (E04).

**Fora de escopo:** distribuição (E03); disparo do avanço de data (E11 publica via `hotel`; aqui o evento é simulado nos testes).

**Passos de implementação:**

1. Testes de módulo com `Scenario`: cada evento gera a tarefa certa; reprocessar o mesmo evento não duplica.
2. Testes de integração das consultas e do endpoint avulso.
3. Implementar.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "que consultas de `reservations` e de `rooms` existem para hospedados, chegadas, saídas e UHs vagas por data (código e handoffs de M2, M4 e M5)?".
- Partes paralelas: nenhuma.
- Foco do verificador: tarefa duplicada por reprocessamento de evento; permanência gerada para UH vaga; dado pessoal do hóspede copiado para a tarefa.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E02 — "Geração automática de tarefas" do roadmap docs/roadmaps/roadmap-m6-operacao-fechamento.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução", as restrições e a hipótese H4 —, a seção E02 inteira, o "Evidências e handoff" de E01 e docs/SPEC.md RF-GOV-02. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: gerar tarefas de governança a partir de GuestCheckedOut (saída), RoomMoved (UH de origem), BusinessDateAdvanced (permanências, pré-chegada e chegadas da data, faxinas programadas vencidas) e de POST /api/v1/housekeeping-tasks (avulsas da recepção), com créditos e prioridade do tipo de limpeza e no máximo uma tarefa por (UH, data, tipo). Não distribua tarefas nem dispare o avanço de data.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E02 para #em-andamento no índice e no título;
- investigação: um subagente Explore levanta as consultas de reservations e rooms por data (código e handoffs de M2, M4 e M5);
- escreva os testes de módulo e de integração primeiro, implemente, regenere o contrato e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em duplicidade por reprocessamento, permanência em UH vaga e dado pessoal copiado para a tarefa.

Critérios de aceite (detalhes na seção E02):
- cada origem gera a tarefa certa com créditos e prioridade; reprocessar um evento não duplica;
- UH com chegada prevista recebe prioridade maior;
- tarefa não guarda nome, documento nem contato do hóspede;
- build, testes e contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E02 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] `GuestCheckedOut` gera tarefa de saída na UH; `RoomMoved` gera tarefa na UH de origem (testes de módulo).
- [ ] `BusinessDateAdvanced` gera permanência para UHs ocupadas, pré-chegada para UHs vagas há N dias com chegada e tarefas de faxinas programadas vencidas.
- [ ] Reprocessar o mesmo evento não cria segunda tarefa (restrição única por UH, data e tipo).
- [ ] UH com chegada prevista recebe prioridade maior que permanência comum.
- [ ] `POST /api/v1/housekeeping-tasks` cria tarefa avulsa; sem permissão → `403`.
- [ ] A tabela de tarefas não tem colunas de dados pessoais do hóspede.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Tarefa de verificação do contrato (CLAUDE.md) → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Ordem de eventos (check-out antes do avanço de data e vice-versa): a restrição única e testes com as duas ordens evitam duplicidade.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E03 — Distribuição sugerida e publicação — #pendente

**Objetivo:** implementar o `AssignmentPlan` (RF-GOV-03): sugestão que balanceia créditos entre as camareiras escaladas no dia (escala de `staff`), agrupa por andar, prioriza chegadas e early check-in e respeita "Não perturbe"; ajuste manual pela governanta; publicação que notifica só quem teve mudança.

**Por que agora:** a execução (E04) só começa com tarefas atribuídas.

**Dependências e paralelismo:** E02. Não paralelizável com E04–E06.

**Escopo:** `housekeeping`:

- Algoritmo de sugestão determinístico no `domain` (testável sem Spring), usando a escala do dia (`staff`, RF-PES-04), andar das UHs (`rooms`) e sinalização DND.
- `POST /api/v1/assignment-plans/{date}/suggest`, `PATCH` de atribuições, `POST …/publish` e republicação.
- Notificação de tarefa atribuída via `notifications` (RF-NOT-01) só para quem teve mudança; evento de SSE para o painel.

**Fora de escopo:** execução (E04).

**Passos de implementação:**

1. Testes de domínio do algoritmo: balanceamento de créditos (diferença máxima entre camareiras limitada), agrupamento por andar, prioridade, DND.
2. Testes de integração de sugerir, ajustar, publicar e republicar com notificações.
3. Implementar.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "como `staff` expõe a escala do dia por setor e como `notifications` recebe pedidos de notificação, segundo o código e os handoffs de M1 e M2?".
- Partes paralelas: nenhuma.
- Foco do verificador: tarefa atribuída a quem não está escalado; republicação notificando todo mundo; algoritmo não determinístico.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E03 — "Distribuição sugerida e publicação" do roadmap docs/roadmaps/roadmap-m6-operacao-fechamento.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E03 inteira, o "Evidências e handoff" de E02 e docs/SPEC.md RF-GOV-03 e RF-PES-04. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E02 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: implementar o AssignmentPlan em housekeeping: sugestão determinística no domain que balanceia créditos entre as camareiras escaladas no dia, agrupa por andar, prioriza chegadas e early e respeita DND; ajuste manual; publicação e republicação que notificam via notifications só quem teve mudança. Não implemente a execução das tarefas.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E03 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume como staff expõe a escala do dia e como notifications recebe pedidos (código e handoffs de M1 e M2);
- escreva os testes de domínio e de integração primeiro, implemente, regenere o contrato e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em atribuição a quem não está escalado, republicação notificando todos e não determinismo.

Critérios de aceite (detalhes na seção E03):
- sugestão só usa camareiras escaladas, balanceia créditos dentro do limite definido, agrupa por andar e respeita DND;
- republicação notifica só quem teve mudança;
- build, testes e contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E03 (snapshot, arquivos alterados, decisões sobre o algoritmo, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] A sugestão só atribui a camareiras escaladas na data (teste com escala de `staff`).
- [ ] Em casos de teste, a diferença de créditos entre camareiras não passa do maior crédito de uma tarefa.
- [ ] UHs do mesmo andar ficam com a mesma camareira sempre que o balanceamento permite; chegadas e early vêm primeiro na ordem.
- [ ] UH com DND não recebe tarefa de permanência na sugestão inicial.
- [ ] A mesma entrada gera a mesma sugestão (determinismo).
- [ ] Publicar notifica cada camareira atribuída; republicar notifica só quem teve tarefa incluída ou removida.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Tarefa de verificação do contrato (CLAUDE.md) → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Algoritmo ótimo é desnecessário para 30 UHs: guloso simples, documentado e testado.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E04 — Execução da tarefa online e offline — #pendente

**Objetivo:** implementar a execução da tarefa de governança (RF-GOV-04, RF-GOV-05) seguindo a máquina de estados de SPEC §8.4 — iniciar, pausar, retomar, concluir com checklist, registrar DND ou recusa — por API online e por comandos offline (`OfflineCommandHandler`, ARCHITECTURE §6.2 e §9), atualizando o status da UH, registrando discrepâncias (RF-UH-08) e tratando conflitos de RN-22.

**Por que agora:** é o coração do app da camareira e o primeiro módulo de negócio a usar a sincronização offline de M2 de ponta a ponta.

**Dependências e paralelismo:** E03. Não paralelizável com E05 e E06.

**Escopo:** `housekeeping`:

- Transições da tarefa no `domain` com testes; escopo `OWN` para a camareira (só as próprias tarefas).
- Endpoints online de ação (`POST /api/v1/housekeeping-tasks/{id}/start` etc.) e handlers offline dos mesmos comandos, com idempotência por `commandId` e `occurredAt` corrigido pela defasagem.
- Conclusão: chama `checklists` para concluir a execução (H2), `rooms.markClean(room, requiresInspection)` (H3), registra quem limpou, início, fim e duração, atualiza `lastDoneOn` de faxina programada e publica `HousekeepingTaskCompleted`.
- Ocupação encontrada pela camareira (vazia/ocupada, número de hóspedes) enviada a `rooms` para detecção de discrepância.
- Conflito RN-22: tarefa reatribuída ou cancelada enquanto offline → execução registrada e sinalizada à governanta (notificação e marca na tarefa), nunca descartada.
- Feed de sincronização: tarefas da camareira e UHs (número e status, sem dados pessoais — RN-13) no `GET /api/v1/sync/changes`.

**Fora de escopo:** inspeção (E05); achados e danos (E06); ações de estoque e enxoval do checklist (M9).

**Passos de implementação:**

1. Testes de domínio da máquina de estados (todas as transições válidas e inválidas).
2. Testes de integração online e offline: lote com iniciar e concluir; reenvio do mesmo `commandId`; conflito por reatribuição; escopo `OWN`.
3. Implementar e conferir o feed de sync sem dados pessoais.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "como M2 implementou `OfflineCommandHandler`, o processamento de lotes, a correção de relógio, o feed `sync/changes` e a conclusão de execução de checklist (código e handoff do gate de M2)?"; "o que RN-13, RN-22 e ARCHITECTURE §6.2 e §9 exigem?".
- Partes paralelas: nenhuma.
- Foco do verificador: execução offline descartada em conflito; camareira agindo sobre tarefa de outra; dado pessoal do hóspede no feed de sync; status da UH alterado fora da API de `rooms`.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E04 — "Execução da tarefa online e offline" do roadmap docs/roadmaps/roadmap-m6-operacao-fechamento.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução", as restrições e as hipóteses H2 e H3 —, a seção E04 inteira e o "Evidências e handoff" de E02 e E03. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E03 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar. Se a base de sync ou a API de execução de checklist de M2 não existir (H2), pare e relate.

Objetivo: implementar a máquina de estados da tarefa de governança (SPEC §8.4) com endpoints online e handlers offline (OfflineCommandHandler) para iniciar, pausar, retomar, concluir com checklist, DND e recusa; na conclusão, concluir a execução do checklist, chamar rooms.markClean com a exigência de inspeção, registrar tempos e publicar HousekeepingTaskCompleted; enviar a ocupação encontrada a rooms; tratar conflitos de RN-22 sem descartar a execução; expor no feed de sync só número e status das UHs (RN-13).

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E04 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume a base de sync de M2 (handlers, lotes, relógio, feed) e a conclusão de checklist, e RN-13, RN-22 e ARCHITECTURE §6.2 e §9;
- escreva os testes de domínio e de integração (online e offline) primeiro, implemente, regenere o contrato e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em execução descartada em conflito, escopo OWN, dado pessoal no feed e status da UH fora da API de rooms.

Critérios de aceite (detalhes na seção E04):
- todas as transições de §8.4 da tarefa testadas; online e offline produzem o mesmo resultado;
- mesmo commandId reenviado não reaplica; conflito por reatribuição registra a execução e sinaliza a governanta;
- camareira não age sobre tarefa de outra (403); feed de sync sem dados pessoais;
- build, testes e contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E04 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

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

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Tarefa de verificação do contrato (CLAUDE.md) → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Relógio do celular muito adiantado: confiar na correção de defasagem de M2 e testar `occurredAt` fora da tolerância.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E05 — Inspeção e liberação da UH — #pendente

**Objetivo:** implementar a inspeção (RF-GOV-06): a supervisora executa o checklist de inspeção, aprova (UH Inspecionada, tarefa `DONE`) ou reprova (UH Suja, tarefa `REJECTED` de volta à camareira com os itens apontados); inspeção voluntária de UH Limpa; aviso em tempo real à recepção quando a UH fica pronta (RF-GOV-07), por SSE e por notificação de UH liberada (RF-NOT-01).

**Por que agora:** fecha o ciclo de limpeza e libera UHs para check-in (RN-05).

**Dependências e paralelismo:** E04. Pode correr em paralelo com E06 (worktree separado); conflito em `api/openapi.yaml` e no controller de tarefas.

**Escopo:** `housekeeping` — `Inspection` ligada à tarefa e a uma execução de checklist; endpoints online e handlers offline; `rooms.markInspected`/`markDirty` (H3); notificação à camareira na reprovação (RF-NOT-01); evento de UH pronta no SSE e notificação de UH liberada via `notifications` para a recepção (RN-05 avaliada por `rooms`).

**Fora de escopo:** indicadores de reprovação (E09).

**Passos de implementação:**

1. Testes de integração: aprovar, reprovar, refazer após reprovação, inspeção voluntária, aviso de UH pronta no SSE e notificação de UH liberada.
2. Implementar.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; padrão no handoff de E04.
- Partes paralelas: nenhuma.
- Foco do verificador: UH ficando pronta com inspeção reprovada; inspeção feita pela própria camareira que limpou sem permissão de inspeção.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E05 — "Inspeção e liberação da UH" do roadmap docs/roadmaps/roadmap-m6-operacao-fechamento.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E05 inteira, o "Evidências e handoff" de E04 e docs/SPEC.md RF-GOV-06, RF-GOV-07 e RN-05. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E04 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: implementar a inspeção em housekeeping, online e offline: aprovação deixa a UH Inspecionada e a tarefa DONE; reprovação deixa a UH Suja e devolve a tarefa à camareira (REJECTED) com os itens apontados e notificação; inspeção voluntária de UH Limpa; aviso no SSE à recepção quando a UH fica pronta. Não implemente indicadores.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E05 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de integração primeiro, implemente, regenere o contrato e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em UH pronta após reprovação e inspeção sem permissão.

Critérios de aceite (detalhes na seção E05):
- aprovar → UH Inspecionada; reprovar → UH Suja, tarefa REJECTED, camareira notificada; refazer volta a IN_PROGRESS;
- inspeção exige permissão própria;
- recepção recebe aviso de UH pronta no SSE e notificação de UH liberada;
- build, testes e contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E05 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Aprovação → UH Inspecionada e tarefa `DONE`; UH vaga e sem OOO passa a pronta para check-in (RN-05).
- [ ] Reprovação → UH Suja, tarefa `REJECTED` com os itens reprovados, camareira notificada; refazer leva a `IN_PROGRESS`.
- [ ] Inspeção voluntária de UH Limpa → Inspecionada.
- [ ] Inspeção sem a permissão própria → `403`.
- [ ] Cliente inscrito no tópico de UHs do SSE recebe o evento quando a UH fica pronta.
- [ ] Inspeção offline funciona pelo mesmo handler pattern de E04.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Tarefa de verificação do contrato (CLAUDE.md) → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Nenhum relevante.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E06 — Achados e perdidos e danos — #pendente

**Objetivo:** implementar achados e perdidos (RF-GOV-08), com guarda, devolução e descarte após o prazo configurado, e danos (RF-GOV-09), com revisão pela recepção ou gerência e lançamento opcional na conta do hóspede via `billing`. Ambos podem ser abertos manualmente, offline ou por ocorrência de `ChecklistCompleted`.

**Por que agora:** completa as ocorrências da governança previstas para a Fase 1.

**Dependências e paralelismo:** E04. Pode correr em paralelo com E05 (worktree separado); conflito em `api/openapi.yaml`.

**Escopo:** `housekeeping`:

- `LostAndFoundItem`: registro, local de guarda, devolução (a quem, quando, por quem), descarte/doação; job com ShedLock que lista itens vencidos pelo prazo de guarda de `hotel` (RF-CFG-04) para decisão (não descarta sozinho).
- `DamageReport`: registro com custo estimado, revisão, `chargeTo(folio)` lançando via `billing` com chave de idempotência (H5) ou `waive(reason)`.
- Listener de `ChecklistCompleted` para ocorrências do tipo achado ou dano (H2).
- Handlers offline para registro pela camareira; revisão e cobrança só online.

**Fora de escopo:** fotos e anexos de achados e danos, que a SPEC §5.14 não pede. Se forem pedidos depois, `housekeeping` → `files` é permitido pela regra por camadas (acrescentar à tabela §3.1).

**Passos de implementação:**

1. Testes de integração: achado registrado offline; devolução; vencimento listado pelo job; dano revisado e lançado; dano dispensado; ocorrência de checklist gerando dano.
2. Implementar.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "como `billing` (M3) expõe lançamento em conta da reserva por código de lançamento, com idempotência?".
- Partes paralelas: nenhuma.
- Foco do verificador: dano lançado duas vezes; dano lançado sem revisão; camareira vendo dados do hóspede ao registrar dano.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E06 — "Achados e perdidos e danos" do roadmap docs/roadmaps/roadmap-m6-operacao-fechamento.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução", as restrições e as hipóteses H2 e H5 —, a seção E06 inteira, o "Evidências e handoff" de E04 e docs/SPEC.md RF-GOV-08 e RF-GOV-09. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E04 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: implementar em housekeeping achados e perdidos (registro, guarda, devolução, descarte, job que lista vencidos pelo prazo de hotel) e danos (registro, revisão pela recepção ou gerência, lançamento idempotente na conta via billing ou dispensa), abertos manualmente, offline ou por ocorrência de ChecklistCompleted. Revisão e cobrança só online; nenhum dado do hóspede para a camareira.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E06 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume como billing expõe lançamento em conta da reserva com idempotência;
- escreva os testes de integração primeiro, implemente, regenere o contrato e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em lançamento duplicado, lançamento sem revisão e dados do hóspede para a camareira.

Critérios de aceite (detalhes na seção E06):
- achado registrado offline, devolvido e listado como vencido após o prazo;
- dano só é lançado após revisão, uma única vez, na conta da reserva da UH;
- ocorrência de checklist gera achado ou dano conforme configurado;
- build, testes e contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E06 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Achado registrado offline aparece na listagem; devolução registra a quem, quando e por quem.
- [ ] Após o prazo de guarda, o job lista o item como vencido; descarte ou doação registra motivo e usuário.
- [ ] Dano não revisado não pode ser lançado (`409`); revisado e cobrado gera um único lançamento na conta da reserva (repetição não duplica).
- [ ] Dano dispensado registra motivo e não gera lançamento.
- [ ] `ChecklistCompleted` com ocorrência de dano ou achado cria o registro correspondente.
- [ ] Respostas para a camareira não contêm dados do hóspede.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Tarefa de verificação do contrato (CLAUDE.md) → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Dano em UH sem reserva ativa: permitir só registro e dispensa; cobrança exige reserva vinculada.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E07 — Ordens de serviço corretivas e OOO/OOS — #pendente

**Objetivo:** criar o módulo `maintenance` com a OS corretiva (RF-MAN-01, RF-MAN-02, RF-MAN-03): local, categoria, prioridade, descrição, anexos, origem, responsável interno ou terceiro; estados aberta, em andamento, aguardando peça/terceiro, concluída, cancelada; custos de mão de obra, terceiros e peças informadas; bloqueio da UH em OOO/OOS via `rooms` e retorno como Suja ao concluir.

**Por que agora:** a manutenção corretiva é parte da Fase 1 e alimenta o relatório operacional (E09).

**Dependências e paralelismo:** nenhuma dentro deste roadmap; exige o gate de M5 `#concluida`. Paralelizável com E01–E06 e E10 em worktree separado; conflito em `api/openapi.yaml`.

**Escopo:** pacote `maintenance` (a criar):

- `WorkOrder` com número legível (gerador de M0), máquina de estados, custos em `Money`; terceiro como texto livre e custo de peças como valor informado até M9.
- Anexos via `files` (dependência permitida).
- `blockRoom(kind, from, to)`: pede OOO ou OOS a `rooms` com período previsto; OOO exige a permissão/step-up definida em M2 (RF-ACS-08). Conclusão devolve a UH (normalmente como Suja).
- Notificação de OS urgente via `notifications` (RF-NOT-01).
- Escopo `OWN` para o técnico; endpoints CRUD e de ação em `/api/v1/work-orders`.

**Fora de escopo:** abertura por checklist e offline (E08); ativos, preventivas e peças do estoque (M9, M10).

**Passos de implementação:**

1. Testes de domínio da máquina de estados da OS.
2. Testes de integração: abrir com anexo, OOO com step-up, concluir devolvendo a UH Suja, OS urgente notificada, escopo `OWN`.
3. Implementar.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "como `rooms` (M2) expõe OOO/OOS com período, motivo e step-up, e como `files` (M1) expõe anexos?".
- Partes paralelas: nenhuma.
- Foco do verificador: OOO aplicado sem step-up ou em UH com hóspede (RN-07); OS concluída sem devolver a UH; `maintenance` dependendo de `inventory`.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E07 — "Ordens de serviço corretivas e OOO/OOS" do roadmap docs/roadmaps/roadmap-m6-operacao-fechamento.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução", as restrições e o "Fora de escopo" —, a seção E07 inteira e docs/SPEC.md RF-MAN-01 a RF-MAN-03, RN-07 e §8.2.12. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que o gate do marco M5 em docs/roadmaps/roadmap-m5-recepcao-fnrh.md está #concluida; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar o módulo maintenance com WorkOrder corretiva (número legível, estados, custos em Money, terceiro como texto livre, peças como valor informado), anexos via files, bloqueio da UH em OOO/OOS via rooms com step-up para OOO, retorno da UH como Suja ao concluir, notificação de OS urgente e escopo OWN para o técnico, em /api/v1/work-orders. Não use inventory (M9) nem crie ativos ou preventivas (M10).

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E07 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume como rooms expõe OOO/OOS e como files expõe anexos (código e handoffs de M1 e M2);
- escreva os testes de domínio e de integração primeiro, implemente, regenere o contrato e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em OOO sem step-up ou com hóspede, UH não devolvida e dependência de inventory.

Critérios de aceite (detalhes na seção E07):
- máquina de estados da OS testada; OS urgente notifica a manutenção;
- OOO sem aprovação → 403 APPROVAL_REQUIRED; OOO em UH ocupada → erro com code estável;
- concluir a OS devolve a UH como Suja; técnico com escopo OWN só vê as próprias OS;
- ModularityTest, build, testes e contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E07 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Testes de domínio cobrem as transições da OS (aberta, em andamento, aguardando, concluída, cancelada), válidas e inválidas.
- [ ] OS com anexo grava o anexo via `files` e o devolve só por URL assinada.
- [ ] Pedido de OOO sem aprovação → `403` `APPROVAL_REQUIRED`; em UH ocupada → erro com `code` estável (RN-07); com aprovação → UH em OOO pelo período.
- [ ] Concluir a OS encerra o OOO/OOS e deixa a UH Suja.
- [ ] OS de prioridade urgente gera notificação para a manutenção.
- [ ] Técnico com escopo `OWN` lista só as próprias OS.
- [ ] `maintenance` não depende de `inventory` (`ModularityTest`); build, testes e contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Tarefa de verificação do contrato (CLAUDE.md) → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- OOO com reserva alocada no período (RN-07): `rooms` não conhece reservas; quem alerta e exige a realocação é `reservations`, ao escutar `OutOfOrderChanged` (M4). A OS registra o bloqueio quando `rooms` confirma a abertura do OOO, sem esperar a realocação.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E08 — OS por checklist e app offline da manutenção — #pendente

**Objetivo:** abrir OS automaticamente a partir de ocorrências `MAINTENANCE` de `ChecklistCompleted` (RF-CHK-05, ARCHITECTURE §6.2) e permitir ao técnico operar a OS offline (RF-MAN-07), na mesma base de sincronização do app da governança.

**Por que agora:** completa a manutenção corretiva da Fase 1 e liga checklists à manutenção.

**Dependências e paralelismo:** E07. Não paralelizável com E07.

**Escopo:** `maintenance` — listener de `ChecklistCompleted` (idempotente por execução e item); handlers offline para abrir OS (ID gerado no cliente), iniciar, pausar, concluir e registrar observações; OS e locais do técnico no feed de sync, sem dados pessoais (RN-13).

**Fora de escopo:** peças do estoque (M9).

**Passos de implementação:**

1. Testes de módulo: `ChecklistCompleted` com item `MAINTENANCE` não ok gera uma OS com origem checklist; reprocessar não duplica.
2. Testes de integração offline: OS aberta offline com UUID do cliente; reenvio idempotente; conclusão offline.
3. Implementar.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; padrão de handlers no handoff de E04.
- Partes paralelas: nenhuma.
- Foco do verificador: OS duplicada por reprocessamento; OS aberta offline com ID trocado pelo servidor; dados pessoais no feed.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E08 — "OS por checklist e app offline da manutenção" do roadmap docs/roadmaps/roadmap-m6-operacao-fechamento.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a hipótese H2 —, a seção E08 inteira e o "Evidências e handoff" de E07 (e de E04, se já concluída, para o padrão de handlers offline). Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E07 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: em maintenance, abrir OS a partir de ocorrências MAINTENANCE de ChecklistCompleted (idempotente por execução e item) e implementar handlers offline para abrir (com UUID do cliente), iniciar, pausar e concluir OS, com as OS do técnico no feed de sync sem dados pessoais. Não use inventory.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E08 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de módulo e de integração primeiro, implemente e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em duplicidade, ID do cliente trocado e dados pessoais no feed.

Critérios de aceite (detalhes na seção E08):
- ChecklistCompleted com item MAINTENANCE não ok gera uma única OS com origem checklist;
- OS aberta offline mantém o UUID do cliente; reenvio não duplica;
- feed de sync do técnico sem dados pessoais;
- build, testes e contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E08 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] `ChecklistCompleted` com item `MAINTENANCE` marcado "não ok" gera exatamente uma OS com origem checklist e vínculo à execução; reprocessar não duplica.
- [ ] OS aberta offline é persistida com o UUID do cliente; o mesmo `commandId` reenviado não cria outra.
- [ ] Iniciar, pausar e concluir offline produzem o mesmo estado que online.
- [ ] O feed de sync do técnico contém suas OS e locais, sem dados pessoais de hóspede.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Tarefa de verificação do contrato (CLAUDE.md) → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Nenhum relevante.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E09 — Relatórios operacionais e painel do dia — #pendente

**Objetivo:** criar o módulo `reporting` com o painel do dia (RF-REL-01), os relatórios operacionais de governança e manutenção (RF-REL-06, RF-GOV-11), o snapshot `DailyStatistics` (SPEC §8.2.9), a exportação CSV/XLSX (RF-REL-07), a geração e o arquivo de PDFs por data e o relatório de pendências do fechamento (RF-FEC-04), consultando só views `report_*` publicadas pelos módulos (ARCHITECTURE §11).

**Por que agora:** o fechamento (E11) gera o snapshot e os PDFs por meio de `reporting`, e a gerência precisa do painel na operação.

**Dependências e paralelismo:** E05, E08. Não paralelizável com E10 e E11 se os três tocarem `api/openapi.yaml` ao mesmo tempo; com worktrees separados, pode correr em paralelo com E10.

**Escopo:**

- Views `report_*` (migrações nos schemas donos, como parte da API de cada módulo): `rooms` (status), `reservations` (chegadas, saídas, hospedados, ocupação futura), `housekeeping` (tarefas, tempos, créditos, reprovações), `maintenance` (OS por estado, categoria e tempo), `billing` (contas com saldo de hóspedes que saíram, caixas abertos). `fnrh.report_pending_submissions` já existe (M5).
- `reporting` (a criar): `GET /api/v1/reports/day-panel`, relatórios de governança e manutenção com filtros, exportação `?format=csv|xlsx` (H7), relatório de pendências.
- API pública para o fechamento: `snapshotDailyStatistics(date)` (UHs disponíveis, OOO, vendidas, cortesias, uso da casa, chegadas, saídas, no-shows, cancelamentos, receitas por departamento; regras de Q-15 — H9) e `archiveDayReports(date)` (PDFs renderizados por `printing` e guardados em `files`, com índice por data no schema `reporting` — H6, H7), ambos idempotentes por data.
- `GET /api/v1/reports/archive?date=…` e download dos PDFs arquivados por URL assinada de `files`.
- Arestas `reporting` → `printing` e `reporting` → `files` acrescentadas à tabela da ARCHITECTURE §3.1.

**Fora de escopo:** KPIs comparativos e relatórios gerenciais (M10).

**Passos de implementação:**

1. Testes de integração com dados semeados: painel do dia, relatórios, exportações (CSV e XLSX abertos e conferidos), snapshot idempotente, arquivo idempotente, pendências.
2. Escrever as views nos schemas donos e o módulo `reporting`.
3. Confirmar com `ModularityTest` e uma regra ArchUnit que `reporting` não referencia classes de outros módulos.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "que dados RF-REL-01, RF-REL-06, RF-GOV-11, RF-FEC-04 e `DailyStatistics` (SPEC §8.2.9) exigem, e quais tabelas de cada módulo os contêm?".
- Partes paralelas: nenhuma.
- Foco do verificador: `reporting` lendo tabela interna em vez de view; snapshot recalculado gerando duplicata; regra de cortesia e uso da casa diferente de Q-15; dados pessoais em relatório operacional.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E09 — "Relatórios operacionais e painel do dia" do roadmap docs/roadmaps/roadmap-m6-operacao-fechamento.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução", as restrições e as hipóteses H6, H7 e H9 —, a seção E09 inteira, o "Evidências e handoff" de E05 e E08 e docs/ARCHITECTURE.md §11 ("Leitura para relatórios"). Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E05 e E08 estão #concluida e que o código bate com o handoff delas; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: publicar views report_* nos schemas de rooms, reservations, housekeeping, maintenance e billing e criar o módulo reporting, que só consulta views, com painel do dia, relatórios operacionais de governança e manutenção, exportação CSV/XLSX, relatório de pendências e uma API pública idempotente por data para o fechamento: snapshot de DailyStatistics (regras de Q-15) e arquivo de PDFs renderizados por printing e guardados em files, com índice por data no schema reporting; acrescente reporting → printing e reporting → files à tabela da docs/ARCHITECTURE.md §3.1. Não implemente KPIs gerenciais (M10).

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E09 para #em-andamento no índice e no título;
- investigação: um subagente Explore mapeia os dados exigidos por RF-REL-01, RF-REL-06, RF-GOV-11, RF-FEC-04 e DailyStatistics para as tabelas de cada módulo;
- escreva os testes de integração primeiro, implemente, regenere o contrato e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em leitura de tabela interna, duplicata no snapshot, regra de Q-15 e dados pessoais em relatório operacional.

Critérios de aceite (detalhes na seção E09):
- painel do dia e relatórios devolvem os números esperados para dados semeados; CSV e XLSX abrem e conferem;
- snapshot e arquivo por data são idempotentes;
- reporting só lê views report_* (teste de arquitetura);
- build, testes e contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E09 (snapshot, arquivos alterados, views criadas, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

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

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Tarefa de verificação do contrato (CLAUDE.md) → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Views acopladas ao esquema interno de cada módulo: tratá-las como API do módulo e testá-las no próprio módulo dono.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E10 — Motor do fechamento do dia — #pendente

**Objetivo:** criar o módulo `endofday` com o motor do fechamento (RF-FEC-01, RF-FEC-03, RN-21, ARCHITECTURE §13): `EndOfDayRun` com etapas persistidas, execução ordenada, retomada da etapa pendente, uma execução por data e nunca em paralelo, disparo automático no horário configurado em `hotel` (fuso do hotel) e manual com step-up.

**Por que agora:** as etapas de negócio (E11) se encaixam neste motor; separá-lo permite testar idempotência e retomada com etapas falsas antes de mexer em dinheiro.

**Dependências e paralelismo:** nenhuma dentro deste roadmap; exige o gate de M5 `#concluida`. Paralelizável com E01–E09 em worktree separado; conflito em `api/openapi.yaml`.

**Escopo:** pacote `endofday` (a criar):

- `BusinessDay`, `EndOfDayRun`, `EndOfDayStep`, `EndOfDayIssue` (SPEC §8.2.9); restrição única por data de execução concluída; lock por ShedLock e lock de banco contra execução paralela.
- Interface interna de etapa (nome, ordem, `execute(date)` idempotente) e registro ordenado; falha numa etapa interrompe e deixa a execução retomável.
- Gatilho automático por `@Scheduled` no horário de RF-CFG-04, no fuso do hotel; `POST /api/v1/end-of-day/runs` manual, ação sensível com step-up (RF-ACS-08); `POST …/runs/{id}/resume`; `GET` de execução, etapas e pendências.
- Publicação do andamento no SSE e aviso de pendências via `notifications` (push e painel) para quem acompanha o fechamento (H8, RF-NOT-01).

**Fora de escopo:** as etapas de negócio (E11).

**Passos de implementação:**

1. Testes de integração com etapas falsas: execução completa; falha na etapa 3 e retomada a partir dela sem reexecutar 1 e 2; disparo concorrente (dois gatilhos) executa uma vez; segunda execução para a mesma data rejeitada; manual sem aprovação → `403`; gatilho no horário do fuso do hotel.
2. Implementar.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "como `hotel` (M1) expõe horário do fechamento, fuso e data operacional, e como o ShedLock foi configurado em M0?".
- Partes paralelas: nenhuma.
- Foco do verificador: duas execuções simultâneas para a mesma data; retomada reexecutando etapa concluída; gatilho calculado em UTC em vez do fuso do hotel; fechamento manual sem step-up.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E10 — "Motor do fechamento do dia" do roadmap docs/roadmaps/roadmap-m6-operacao-fechamento.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução", as restrições e a hipótese H8 —, a seção E10 inteira, docs/SPEC.md §5.13, RN-21 e §8.2.9 e docs/ARCHITECTURE.md §13. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que o gate do marco M5 em docs/roadmaps/roadmap-m5-recepcao-fnrh.md está #concluida; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar o módulo endofday com BusinessDay, EndOfDayRun, EndOfDayStep e EndOfDayIssue; execução ordenada de etapas idempotentes, retomada da etapa pendente, uma execução por data e nunca em paralelo (ShedLock e lock de banco); gatilho automático no horário de RF-CFG-04 no fuso do hotel e manual com step-up; endpoints de execução, retomada e consulta; andamento no SSE e aviso de pendências via notifications (RF-NOT-01). Use etapas falsas nos testes; as etapas de negócio são da E11.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E10 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume como hotel expõe horário, fuso e data operacional e como o ShedLock foi configurado em M0;
- escreva os testes de integração primeiro (completa, falha e retomada, concorrência, data repetida, manual sem aprovação, fuso), implemente, regenere o contrato e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em execução paralela, retomada reexecutando etapa, horário em UTC e manual sem step-up.

Critérios de aceite (detalhes na seção E10):
- falha numa etapa e retomada não reexecutam etapas concluídas;
- dois gatilhos simultâneos executam uma vez; segunda execução para a mesma data é rejeitada;
- manual sem aprovação → 403 APPROVAL_REQUIRED; gatilho respeita o fuso do hotel;
- pendência registrada gera notificação via notifications;
- build, testes e contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E10 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Execução com etapas falsas percorre todas na ordem e registra início, fim e resultado de cada uma.
- [ ] Falha forçada na terceira etapa deixa a execução retomável; `POST …/resume` continua da terceira sem reexecutar as duas primeiras (contador no teste).
- [ ] Dois disparos simultâneos para a mesma data resultam em uma única execução.
- [ ] Nova execução para data já fechada → `409` com `code` estável.
- [ ] `POST /api/v1/end-of-day/runs` sem permissão → `403` `APPROVAL_REQUIRED`; com aprovação → executa e audita solicitante e aprovador.
- [ ] O gatilho automático calcula o horário no fuso do hotel (teste com relógio fixo e fuso diferente de UTC).
- [ ] Andamento publicado no SSE.
- [ ] Uma `EndOfDayIssue` registrada gera notificação de pendência do fechamento via `notifications` para quem tem a permissão de acompanhar o fechamento (RF-NOT-01).
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Tarefa de verificação do contrato (CLAUDE.md) → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Restart no meio da execução (ARCHITECTURE §13): o estado persistido por etapa e o ShedLock com tempo máximo cobrem; testar retomada após "queda" simulada.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E11 — Etapas do fechamento do dia — #pendente

**Objetivo:** implementar as etapas de RF-FEC-02 no motor de E10, na ordem: no-show com multa pela política vigente (RN-15), alerta de saídas pendentes, lançamento de diárias, pacotes e taxas, liberação de bloqueios vencidos (RN-16), expiração de pré-reservas (RN-23), renovação de pré-autorizações (não aplicável até M7), UHs ocupadas → Sujo (RN-09), snapshot de estatísticas, arquivo dos PDFs e avanço da data operacional via `hotel` (que publica `BusinessDateAdvanced`).

**Por que agora:** é o que faz a data operacional andar e as diárias entrarem nas contas; sem isso o hotel não opera.

**Dependências e paralelismo:** E02, E09, E10. Não paralelizável.

**Escopo:** `endofday`, chamando só `hotel`, `rooms`, `reservations`, `billing`, `payments`, `reporting` e `notifications`:

- Cada etapa idempotente por chave natural (ex.: diária por `(reserva, data, origem=EOD)` — H5); `EndOfDayIssue` para saídas pendentes e falhas não fatais.
- Lançamento em data operacional fechada bloqueado em `billing` (RF-FEC-05), com teste; correção entra na data corrente com referência ao original.
- Etapa de pré-autorizações registrada como "não aplicável" até M7, com nota no `CLAUDE.md`.
- Avanço da data por `hotel`; `housekeeping` (E02) gera as tarefas do novo dia ao receber `BusinessDateAdvanced`.
- No-show publicado por `reservations` para a FNRH (listener de M5 E08).

**Fora de escopo:** pendências de notas rejeitadas e ARI (M7, M8).

**Passos de implementação:**

1. Testes de integração por etapa, com dados semeados, incluindo repetição de cada uma sem efeito duplicado.
2. Teste de retomada real: falha forçada depois de lançar parte das diárias e retomada sem duplicar.
3. Teste de módulo: avanço da data gera tarefas do dia em `housekeeping` e submissão de no-show em `fnrh`.
4. Implementar.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "que operações `reservations`, `billing`, `rooms`, `hotel` e `reporting` oferecem para no-show com multa, diárias, pacotes e taxas, bloqueios, pré-reservas, UHs ocupadas e avanço da data (código e handoffs de M1–M5 e de E09)?"; "o que RF-FEC-02, RF-FEC-05, RN-09, RN-15, RN-16 e RN-23 exigem?".
- Partes paralelas: nenhuma.
- Foco do verificador: diária lançada duas vezes após retomada; lançamento aceito em data fechada; no-show sem multa ou com multa da política atual em vez da vigente na reserva; data avançada antes das etapas anteriores concluírem.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E11 — "Etapas do fechamento do dia" do roadmap docs/roadmaps/roadmap-m6-operacao-fechamento.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução", as restrições e as hipóteses H4 e H5 —, a seção E11 inteira e o "Evidências e handoff" de E02, E09 e E10. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E02, E09 e E10 estão #concluida e que o código bate com o handoff delas; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: implementar no motor de endofday as etapas de RF-FEC-02, na ordem: no-show com multa pela política vigente na reserva, alerta de saídas pendentes, diárias, pacotes e taxas, bloqueios vencidos, pré-reservas vencidas, pré-autorizações (não aplicável até M7), UHs ocupadas → Sujo, snapshot de estatísticas, arquivo de PDFs e avanço da data via hotel. Cada etapa idempotente por chave natural; nada lançado em data fechada (RF-FEC-05). endofday só chama hotel, rooms, reservations, billing, payments e reporting.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E11 para #em-andamento no índice e no título;
- investigação: um subagente Explore levanta as operações disponíveis em reservations, billing, rooms, hotel e reporting e resume RF-FEC-02, RF-FEC-05, RN-09, RN-15, RN-16 e RN-23;
- escreva os testes de integração e de módulo primeiro (cada etapa repetida sem duplicar, retomada após falha no meio das diárias, efeitos em housekeeping e fnrh), implemente e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em diária duplicada, lançamento em data fechada, multa pela política errada e data avançada cedo demais.

Critérios de aceite (detalhes na seção E11):
- cada etapa repetida não duplica efeitos; retomada após falha no meio das diárias não duplica lançamentos;
- lançamento em data fechada é rejeitado;
- avanço da data gera as tarefas do dia em housekeeping e o no-show gera submissão em fnrh;
- build, testes e contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E11 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Chegada pendente vira `NO_SHOW` com multa calculada pela política vigente no momento da reserva (RN-15).
- [ ] Saída pendente gera `EndOfDayIssue` e o check-out não é feito automaticamente.
- [ ] Diárias, pacotes e taxas das reservas hospedadas são lançados uma única vez por `(reserva, data, EOD)`, mesmo com a etapa repetida.
- [ ] Falha forçada no meio do lançamento de diárias e retomada não duplicam nenhum lançamento.
- [ ] Bloqueios vencidos devolvem as UHs não utilizadas ao inventário; pré-reservas vencidas são expiradas.
- [ ] UHs ocupadas passam a Sujo; a etapa de pré-autorizações registra "não aplicável".
- [ ] Snapshot de estatísticas e PDFs da data ficam em `reporting`.
- [ ] A data operacional avança só depois de todas as etapas anteriores concluídas; `BusinessDateAdvanced` gera as tarefas do dia em `housekeeping` (teste de módulo).
- [ ] Lançamento em data operacional fechada é rejeitado com `code` estável (RF-FEC-05).
- [ ] No-show gera submissão em `fnrh`.
- [ ] `endofday` depende só dos módulos permitidos (`ModularityTest`); build, testes e contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Tarefa de verificação do contrato (CLAUDE.md) → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Lançamento de pacotes e taxa de turismo (RF-CFG-03) pode exigir dados de `rates` não expostos por `reservations`: se faltar, acrescentar à API de `reservations` (que depende de `rates`) em vez de `endofday` chamar `rates`.

**Rollback / migração:** lançamentos são imutáveis (RN-03); um erro de cálculo em produção se corrige por estorno na data corrente, nunca apagando linhas. Por isso os testes de idempotência são obrigatórios antes do uso real.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E12 — Gate do marco M6 e fim da Fase 1 — #pendente

**Objetivo:** confirmar, a partir de uma cópia limpa do repositório, que a definição global de pronto do M6 é atendida e que o cenário ponta a ponta da Fase 1 funciona com os módulos reais; deixar o roadmap do M7 pronto para ser sincronizado.

**Por que agora:** marca o fim da Fase 1 da SPEC ("o hotel opera a hospedagem") e é o critério de entrada do M7.

**Dependências e paralelismo:** E06, E11 (cobrem transitivamente todas as demais). Não paralelizável.

**Escopo:**

- Teste de integração ponta a ponta (a criar): reserva → check-in → fechamento do dia (diária lançada, UH Suja, data avançada) → tarefa de permanência gerada, distribuída, executada offline e inspecionada → check-out com saldo zero → tarefa de saída gerada e concluída → submissões FNRH de check-in e check-out (WireMock).
- Rodar do zero numa cópia limpa: build, testes, verificação de módulos e do contrato.
- Conferir a "Definição global de pronto"; registrar pendências herdadas (ex.: homologação da FNRH de M5, se ainda bloqueada).
- Atualizar `docs/roadmaps/README.md` registrando a conclusão do M6 e da Fase 1 no histórico de revisões.

**Fora de escopo:** funcionalidades novas. Problemas encontrados voltam para a etapa de origem.

**Passos de implementação:**

1. Escrever e rodar o teste ponta a ponta.
2. Rodar tudo do zero e marcar a definição global de pronto com evidências.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação: nenhuma.
- Partes paralelas: nenhuma.
- Foco do verificador: item sem evidência; ponta a ponta com mocks entre módulos; pendências herdadas omitidas.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E12 — "Gate do marco M6 e fim da Fase 1" do roadmap docs/roadmaps/roadmap-m6-operacao-fechamento.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a "Definição global de pronto" —, a seção E12 inteira e o "Evidências e handoff" de todas as etapas E01 a E11. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E06 e E11 estão #concluida (e, por transitividade, as demais); se não, ou se o código divergiu do plano, pare e relate.

Objetivo: verificar numa cópia limpa, sem caches nem estado local, cada item da definição global de pronto do M6, incluindo um teste de integração ponta a ponta da Fase 1 com os módulos reais (só a FNRH em WireMock): reserva → check-in → fechamento do dia → tarefa de permanência distribuída, executada offline e inspecionada → check-out com saldo zero → tarefa de saída → submissões FNRH. Registre pendências herdadas, como a homologação da FNRH de M5. Não implemente funcionalidade nova além do teste ponta a ponta.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E12 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva e rode o teste ponta a ponta, rode do zero os comandos oficiais de build e teste e a verificação do contrato do CLAUDE.md e registre a evidência de cada item;
- verificação independente com o Prompt do verificador, com foco em item sem evidência, ponta a ponta com mocks entre módulos e pendências herdadas omitidas.

Critérios de aceite (detalhes na seção E12):
- todos os itens da definição global de pronto marcados com evidência;
- teste ponta a ponta da Fase 1 passa sem mocks entre módulos;
- docs/roadmaps/README.md registra a conclusão do M6 e da Fase 1, com pendências herdadas.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E12 (snapshot, verificações, resultados, parecer do verificador, notas para o marco M7) e responda com um resumo curto. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Todos os itens da "Definição global de pronto" marcados, cada um com evidência numa cópia limpa.
- [ ] O teste ponta a ponta da Fase 1 passa usando os módulos reais (só a FNRH em WireMock).
- [ ] Pendências herdadas (ex.: homologação da FNRH) estão listadas com a ação de desbloqueio.
- [ ] `docs/roadmaps/README.md` registra a conclusão do M6 e da Fase 1 no histórico de revisões.

**Validação obrigatória:**

- Comandos oficiais de build e teste e verificação do contrato (CLAUDE.md) numa cópia limpa → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O cenário ponta a ponta atravessa muitos módulos e pode ficar frágil: usar relógio fixo e dados semeados mínimos.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para o marco M7: —

---

## Histórico de revisões

- 2026-10-13 — roadmap criado.
- 2026-10-13 — aplicadas as decisões da entrevista SPEC × ARCHITECTURE (regra por camadas na ARCHITECTURE §3.1; documentos montados pelo dono e renderizados por `printing`): H6 passa a guardar os PDFs do fechamento em `files`; H7 renderiza PDFs via `printing`; H8 envia pendências do fechamento por `notifications`; E05 notifica UH liberada; E09 acrescenta `reporting` → `printing`/`files` à §3.1; E10 e E11 chamam `notifications`; fora de escopo de E06 reescrito (SPEC não pede fotos).
- 2026-10-15 — notas herdadas do M2 acrescentadas em "Hipóteses e decisões em aberto" (step-up da manutenção antes da API de OOO de `rooms`; rotinas de checklist geradas só com o `endofday`) e corrigida a contradição de camadas no risco da E07: quem alerta e exige a realocação é `reservations`, ao escutar `OutOfOrderChanged`. Tags inalteradas.
