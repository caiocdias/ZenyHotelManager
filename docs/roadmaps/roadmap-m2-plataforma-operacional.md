# Roadmap — M2 Plataforma operacional

> Arquivo: `docs/roadmaps/roadmap-m2-plataforma-operacional.md` · criado em 2026-10-13 · última revisão 2026-10-13
>
> Marco 2 de 11 · mapa dos marcos em [`docs/roadmaps/README.md`](README.md) · marco anterior: [M1 — Acesso, auditoria e configuração](roadmap-m1-acesso-configuracao.md) · próximo marco: [M3 — Hóspedes, contas e caixa](roadmap-m3-hospedes-contas.md)
>
> **Pré-requisito:** etapa E09 ("Gate do marco M1") de `docs/roadmaps/roadmap-m1-acesso-configuracao.md` marcada `#concluida`.

## Como usar

Duas formas, ambas na raiz do repositório:

- **Manual:** cole o prompt de uma etapa numa sessão limpa do Claude Code. Uma etapa por sessão.
- **Automática:** cole o prompt da seção "Execução automática" numa sessão limpa; ela executa até 3 etapas em sequência, um subagente por etapa mais um verificador, e para para revisão. Para não parar em cada pedido de permissão, libere antes os comandos de build/teste do projeto nas permissões do Claude Code (os comandos oficiais estão no `CLAUDE.md`).

Etapas marcadas como paralelizáveis só devem rodar ao mesmo tempo em worktrees git separados; na mesma pasta, rode-as uma de cada vez.

Em ambas, quem executa segue o "Protocolo de execução", atualiza a tag da etapa e preenche "Evidências e handoff". Não pule dependências. Revise e faça commit entre etapas ou lotes — nenhuma sessão faz commit por conta própria.

**Este roadmap foi escrito antes do código existir.** Antes da primeira etapa, sincronize-o com o código real usando a skill `development-roadmap` (modo atualização): confira caminhos, portas de `shared`, comandos e convenções registrados nos handoffs de M0 e M1 e ajuste as etapas pendentes.

## Objetivo

Entregar a base operacional sobre a qual governança, manutenção, recepção e restaurante vão rodar: cadastro de UHs e áreas com status em várias dimensões, histórico e OOO/OOS; canal de tempo real por SSE; protocolo de sincronização offline para o app operacional; motor genérico de checklists com versões, execução online e offline e rotinas; e notificações internas com push. Ao fim do marco, uma mudança de status de UH chega aos clientes conectados, e um checklist concluído offline é sincronizado sem perda nem duplicidade e publica `ChecklistCompleted` com as ações configuradas.

## Contexto confirmado

- Requisitos: SPEC §5.4 (UH, RF-UH-01 a 08), §5.15 (CHK, RF-CHK-01 a 09; ações de estoque e enxoval só são consumidas na Fase 3), §5.22 (NOT, RF-NOT-01 e 02; impressão RF-NOT-03 e 04 fica para M5); regras RN-05, RN-07, RN-08, RN-12, RN-13, RN-22 (SPEC §6); RNF-04 e RNF-05 (SPEC §7).
- Modelo de domínio: SPEC §8.2.3 (RoomType, Room, RoomFeature, Area, Location, OutOfOrder, RoomStatusChange), §8.2.11 (ChecklistTemplate, ChecklistVersion, ChecklistSection, ChecklistItem, ItemAction, ApplicabilityRule, ChecklistExecution, ChecklistAnswer, RecurringChecklist) e §8.2.18 (Notification, NotificationPreference, PushDevice). Eventos em SPEC §8.3 (`RoomStatusChanged`, `OutOfOrderChanged`, `ChecklistCompleted`) e máquina de estados do status de governança em SPEC §8.4.
- Arquitetura: módulos `rooms` (depende de `shared`, `hotel`), `checklists` (depende de `shared`, `hotel`, `staff`, `files`) e `notifications` (depende de `shared`, `iam`); tempo real e sincronização ficam em `shared` (ARCHITECTURE §3.1). Vale a **regra por camadas** da §3.1: um módulo pode depender de módulos de camada inferior e da Plataforma sem ciclo, acrescentando a aresta à tabela na mesma mudança; `notifications` nunca escuta eventos de módulos de negócio — quem gera o aviso chama a API pública dele. Offline: ARCHITECTURE §9 e D11; tempo real: ARCHITECTURE §10 e D10; fluxo de checklist offline: ARCHITECTURE §6.2; push FCM: ARCHITECTURE §12.
- Base herdada (fonte: handoffs de M0 e M1 e `CLAUDE.md`): comandos oficiais (M0 E01); migrações por módulo, outbox e ShedLock (M0 E02); tipos de `shared` (M0 E03); convenções de API e contrato (M0 E04); `Idempotency-Key` (M0 E05); ator corrente e autenticação (M1 E01); catálogo de permissões, porta de autorização e filtro `OWN` (M1 E02); auditoria com `@Audited` (M1 E04); step-up (M1 E05); data operacional e `BusinessDateAdvanced` (M1 E06); escala do dia (M1 E07); anexos (M1 E08).

## Escopo

**Incluído:** tipos de UH, UHs, características e áreas comuns; status de ocupação, governança, sinalização do hóspede e OOO/OOS com histórico e discrepâncias; API pública de status para os módulos futuros; SSE com tópicos, filtro por permissão e reconexão; sincronização offline (download por cursor e upload de comandos idempotentes); modelos de checklist com versões, condicionais, aplicabilidade e ações; execução de checklist online e offline; rotinas; relatórios de checklists; notificações internas, preferências e push.

**Fora de escopo:**

- Ocupação derivada da recepção (check-in/out): o `frontdesk` (M5) chama a API pública de `rooms` criada aqui.
- Inventário vendável e alerta de reserva alocada em UH que entra em OOO: `reservations` (M4) escuta `OutOfOrderChanged`.
- "Em limpeza" a partir da tarefa em andamento, tarefas de governança e tipos de limpeza: M6.
- Consumidores de `ChecklistCompleted`: baixa de estoque e troca de enxoval (M9), ordem de serviço, achados e danos (M6).
- Impressão e agente de impressão: M5.
- Tópicos SSE `tasks`, `tabs` e `production`: M6 e M8 os registram usando a infraestrutura daqui.

## Restrições e invariantes

- Respeitar o grafo de módulos da ARCHITECTURE §3.1. `rooms` não conhece reservas nem governança; `checklists` não conhece estoque, rouparia, manutenção nem `rooms` (referências a UH, área, tipo de UH, tipo de limpeza e ativo são IDs opacos); `shared` não depende de nenhum módulo — tempo real e sincronização usam portas que os módulos implementam.
- RN-13: nada que viaje pela sincronização offline ou pelo tópico `rooms` contém nome, documento ou contato de hóspede.
- O servidor tem a palavra final na sincronização, mas registra os fatos (RN-22): execução offline de tarefa reatribuída ou cancelada é registrada e sinalizada, nunca descartada.
- Versões publicadas de checklist são imutáveis (RF-CHK-06).
- Toda escrita é auditada (`@Audited`) e toda checagem de permissão passa pela porta de autorização de `shared` (M1).
- Convenções de API, erros, migrações e tipos seguem o `CLAUDE.md`; não criar convenção paralela.

## Hipóteses e decisões em aberto

| # | Hipótese ou decisão | Impacto se errada / sem decisão | Etapa afetada |
|---|---|---|---|
| H1 | Pacote base `com.zeny.hotel`, herdado de M0 (confirmar no handoff de M0 E01). | Caminhos citados como "(a criar)" mudam de prefixo. | Todas |
| D1 | **Decidido em 2026-10-13:** as rotinas de checklist são geradas por um listener de `BusinessDateAdvanced` em `checklists`, que passa a depender de `hotel` (regra por camadas; aresta já registrada na ARCHITECTURE §3.1; sem ciclo, pois `hotel` depende só de `shared` e `files`). | — | E07 |
| H3 | A aplicabilidade de modelos (RF-CHK-04) guarda IDs opacos de tipos de UH, tipos de limpeza, áreas e ativos; quem pede o modelo de uma tarefa informa os atributos do sujeito. `checklists` não valida esses IDs. | Validar exigiria dependência de `rooms`; se necessário, por porta. | E06 |
| H4 | O número de hóspedes informado pela recepção chega a `rooms` pela API pública de marcação de ocupação (usada pelo `frontdesk` em M5), o que permite a discrepância "número de hóspedes diverge" (RF-UH-08) sem `rooms` depender de reservas. | Se a recepção não informar, essa discrepância fica sem dados até M5. | E02 |
| H5 | Buffer de reconexão SSE em memória com tamanho configurável (instância única, ARCHITECTURE §10). | Reinício do servidor responde `reset` a todos, o que a ARCHITECTURE já prevê. | E04 |
| H6 | `change_seq` é uma *sequence* global no schema `shared`; cada tabela sincronizável tem a coluna atualizada pela aplicação a cada escrita e as exclusões viram *tombstones* numa tabela de `shared`. | Se a atualização pela aplicação se mostrar frágil, usar *trigger* por tabela; a etapa registra a escolha. | E05 |
| H7 | Push via FCM HTTP v1 com credencial de conta de serviço por variável de ambiente; sem credencial configurada, o adaptador fica desligado e só a notificação no painel é entregue. O projeto Firebase do hotel ainda não existe. | Nenhum teste depende da credencial real (WireMock); a primeira entrega real fica para quando houver projeto Firebase. | E09 |

## Definição global de pronto

- [ ] Tipos de UH, UHs e áreas são cadastrados; número de UH duplicado é rejeitado.
- [ ] O status de governança segue as transições da SPEC §8.4; toda mudança fica no histórico com quem, quando e origem; RN-05 é respondida pela API pública.
- [ ] OOO exige step-up, é rejeitado em UH ocupada e publica `OutOfOrderChanged`; OOS mantém a UH vendável.
- [ ] Um cliente inscrito em `GET /api/v1/stream?topics=rooms` recebe a mudança de status em até 2 s e reconecta com `Last-Event-ID` sem perder eventos do buffer.
- [ ] `GET /api/v1/sync/changes` devolve mudanças incrementais com *tombstones* e sem dados pessoais; `POST /api/v1/sync/commands` processa lotes idempotentes por `commandId`.
- [ ] Um checklist concluído offline e sincronizado duas vezes gera uma única execução e um único `ChecklistCompleted` com as ações configuradas.
- [ ] Rotinas são geradas por setor e escala; relatórios de checklists respondem.
- [ ] Notificações aparecem no painel e no tópico SSE do destinatário, respeitam preferências e são enviadas por push quando habilitado.
- [ ] `ApplicationModules.verify()`, build, testes, lint e verificação do contrato passam.

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
Você é o verificador independente da etapa ETAPA do roadmap docs/roadmaps/roadmap-m2-plataforma-operacional.md. Você não participou da implementação e não deve editar nenhum arquivo.
1. Leia CLAUDE.md, o cabeçalho do roadmap e a seção ETAPA inteira (objetivo, escopo, fora de escopo, estratégia, critérios de aceite, validação obrigatória).
2. Veja o que mudou desde o início da etapa: gere um snapshot do estado atual com o comando da seção "Protocolo de execução" e rode git diff --stat HASH_INICIAL seguido do hash gerado; depois leia o diff completo dos arquivos relevantes.
3. Rode todos os comandos da validação obrigatória da etapa e guarde a saída relevante.
4. Para cada critério de aceite, decida APROVADO ou REPROVADO com evidência concreta (arquivo:linha, nome do teste, saída de comando). Critério sem evidência é REPROVADO.
5. Ataque os pontos listados em "Foco do verificador" na etapa e aponte também: mudanças fora do escopo, testes que não exercitam o comportamento (passariam mesmo com o código errado), segredos ou dados pessoais expostos e violações das restrições do roadmap.
Responda com: parecer geral (APROVADO só se todos os critérios forem APROVADO e não houver problema grave), tabela critério → resultado → evidência, problemas por gravidade e comandos executados.
```

## Execução automática

Cole o prompt abaixo numa sessão limpa do Claude Code, na raiz do repositório. Ajuste o limite de etapas se quiser.

```text
Você é o orquestrador do roadmap docs/roadmaps/roadmap-m2-plataforma-operacional.md neste repositório. Seu papel é coordenar subagentes, não implementar: não edite código e mantenha seu contexto enxuto (leia o roadmap e os relatórios dos subagentes, não o código).

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

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E01 | Cadastro de tipos de UH, UHs e áreas | #pendente | — | Módulo `rooms` com tipos, UHs, características e áreas comuns |
| E02 | Status de UH, histórico e discrepâncias | #pendente | E01 | Dimensões de status, transições da SPEC §8.4, histórico, RN-05, discrepâncias e `RoomStatusChanged` |
| E03 | OOO e OOS | #pendente | E02 | Períodos fora de ordem e fora de serviço com step-up, retorno e `OutOfOrderChanged` |
| E04 | Tempo real (SSE) | #pendente | E02 | `GET /api/v1/stream` com tópicos, filtro por permissão, `Last-Event-ID` e tópico `rooms` |
| E05 | Sincronização offline | #pendente | E02 | `GET /sync/changes` por cursor e `POST /sync/commands` idempotente, com `rooms` como primeira fonte |
| E06 | Modelos de checklist | #pendente | — | Modelos, versões imutáveis, itens, condicionais, aplicabilidade e ações configuradas |
| E07 | Execução de checklists e rotinas | #pendente | E05, E06 | Execução online e offline, `ChecklistCompleted` e rotinas por setor e escala |
| E08 | Relatórios de checklists | #pendente | E07 | Conformidade por modelo, itens mais reprovados e histórico por local |
| E09 | Notificações internas e push | #pendente | E04 | Notificações, preferências, tópico SSE e adaptador FCM |
| E10 | Gate do marco M2 | #pendente | E03, E08, E09 | Definição global de pronto verificada do zero |

---

## E01 — Cadastro de tipos de UH, UHs e áreas — #pendente

**Objetivo:** criar o módulo `rooms` com o cadastro de tipos de UH, características, UHs e áreas comuns (RF-UH-01 a 03), com endpoints, permissões e auditoria.

**Por que agora:** status, OOO, sincronização, checklists e todos os marcos de hospedagem referenciam UHs e áreas.

**Dependências e paralelismo:** nenhuma dentro deste marco; exige o gate de M1 concluído. Pode correr em paralelo com E06 (em worktree separado); conflito de edição em `api/openapi.yaml`.

**Escopo:**

- Módulo `rooms` (a criar) com schema e pasta de migração próprios.
- `RoomType` (código, nome, descrição, ocupação padrão e máxima de adultos e crianças, configuração de camas, características, ordem de exibição), `RoomFeature`, `Room` (número único, andar/ala, tipo, características como vista, acessível, conjugada, ativa/inativa, observações) e `Area` (nome, tipo: lobby, piscina, cozinha, salão, corredor, outro) — SPEC §8.2.3.
- Endpoints: `/api/v1/room-types`, `/api/v1/room-features`, `/api/v1/rooms`, `/api/v1/areas`, com lista paginada, detalhe, criação, edição com `If-Match` e desativação (sem exclusão física de UH com histórico).
- Permissões declaradas no catálogo e acrescentadas aos modelos de role pertinentes; `@Audited` nas escritas.
- API pública de leitura (UH, tipo, área, UHs ativas por tipo) para os módulos futuros.

**Fora de escopo:** status e histórico (E02); OOO/OOS (E03).

**Passos de implementação:**

1. Escrever testes de integração dos endpoints, incluindo número de UH duplicado, ocupação máxima menor que a padrão e desativação de tipo com UHs ativas.
2. Implementar módulo, migrações, casos de uso, controllers e API pública.
3. Regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em SPEC §5.4 e §8.2.3 e convenções no `CLAUDE.md`.
- Partes paralelas: nenhuma.
- Foco do verificador: número de UH duplicado aceito; exclusão física; `rooms` dependendo de algo além de `shared` e `hotel`; escrita sem permissão ou sem auditoria.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E01 — "Cadastro de tipos de UH, UHs e áreas" do roadmap docs/roadmaps/roadmap-m2-plataforma-operacional.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Restrições e invariantes" e o "Protocolo de execução" —, a seção E01 inteira, docs/SPEC.md §5.4 e §8.2.3 e o "Evidências e handoff" da etapa E09 de docs/roadmaps/roadmap-m1-acesso-configuracao.md. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que a etapa E09 (gate) de docs/roadmaps/roadmap-m1-acesso-configuracao.md está #concluida e que o código bate com os handoffs de M1; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar o módulo rooms com tipos de UH, características, UHs (número único) e áreas comuns, endpoints /api/v1/room-types, /room-features, /rooms e /areas com If-Match, desativação sem exclusão física, permissões no catálogo, @Audited e API pública de leitura. Não implemente status, histórico nem OOO/OOS. rooms depende só de shared e hotel.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E01 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de integração primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em número duplicado, exclusão física, dependência indevida e escrita sem permissão ou auditoria.

Critérios de aceite (detalhes na seção E01):
- CRUD dos quatro recursos coberto por testes de integração;
- número de UH duplicado → 409 com code estável; sem permissão → 403;
- nenhuma exclusão física de UH; escritas auditadas;
- contrato regenerado; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E01 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Os quatro recursos têm lista paginada, detalhe, criação, edição com `If-Match` e desativação, cobertos por testes de integração.
- [ ] Número de UH duplicado → `409` com `code` estável.
- [ ] Ocupação máxima menor que a padrão → `400` com `code` estável.
- [ ] Não existe exclusão física de UH; UH desativada some das listas de UHs ativas da API pública.
- [ ] Sem permissão → `403`; escritas auditadas.
- [ ] `ApplicationModules.verify()` mostra `rooms` dependendo só de `shared` (e `hotel`, se usado); contrato regenerado; build, testes e lint passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Cadastro inicial manual de até 30 UHs (SPEC §1): aceitável; não há importação.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E02 — Status de UH, histórico e discrepâncias — #pendente

**Objetivo:** implementar o status da UH em dimensões independentes (ocupação, governança com "inspeção pendente", sinalização do hóspede), as transições da máquina de estados da SPEC §8.4, o histórico de mudanças, a regra RN-05 de UH pronta para check-in, a detecção de discrepâncias entre recepção e governança e o evento `RoomStatusChanged`.

**Por que agora:** recepção (M5), governança (M6) e o tempo real (E04) dependem do status; a sincronização (E05) usa a UH como primeira fonte de mudanças.

**Dependências e paralelismo:** E01. Não paralelizável.

**Escopo:**

- Em `Room`: `occupancy` (VACANT/OCCUPIED), `hkStatus` (DIRTY/CLEAN/INSPECTED), `inspectionPending`, `serviceFlag` (nenhum, não perturbe, arrumar agora), número de hóspedes informado pela recepção (H4) e `version` para lock otimista.
- Regras de transição da SPEC §8.4 no domínio, incluindo origem (manual, tarefa, check-out, fechamento) e RN-08 (toda mudança manual registrada).
- `RoomStatusChange` (campo, de, para, quando, quem, origem) — RF-UH-06.
- API pública para os módulos futuros: `markDirty(source)`, `markClean(requiresInspection)`, `markInspected()`, `rejectInspection()`, `markOccupied(guestCount)`, `markVacant()`, `setServiceFlag(...)`, `isReadyForCheckIn(room)` (RN-05; a parte de OOO é completada em E03).
- Endpoints: `GET /api/v1/rooms/status` (painel, RF-UH-07), `GET /api/v1/rooms/{id}/status-history`, `POST /api/v1/rooms/{id}/housekeeping-status` (mudança manual com permissão) e `POST /api/v1/rooms/{id}/service-flag`.
- Discrepâncias (RF-UH-08): `POST /api/v1/rooms/{id}/housekeeping-observations` (governança informa se achou a UH ocupada ou vazia e quantas pessoas) e `GET /api/v1/rooms/discrepancies`.
- Evento `RoomStatusChanged` com payload mínimo (ID da UH, dimensões novas, versão).

**Fora de escopo:** OOO/OOS (E03); "em limpeza" (M6); check-in e check-out reais (M5).

**Passos de implementação:**

1. Escrever testes de domínio de todas as transições permitidas e proibidas da SPEC §8.4 e de RN-05; testes de integração dos endpoints, do histórico, das discrepâncias e da publicação do evento.
2. Implementar domínio, migração, casos de uso, API pública e controllers.
3. Regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "quais transições a máquina 'Status de governança da UH' da SPEC §8.4 permite, e o que RF-UH-04 a 08, RN-05, RN-08 e RN-09 exigem?".
- Partes paralelas: nenhuma.
- Foco do verificador: transição proibida aceita (ex.: DIRTY → INSPECTED direto); mudança sem histórico; evento publicado com dados pessoais; condição de corrida em duas mudanças simultâneas.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E02 — "Status de UH, histórico e discrepâncias" do roadmap docs/roadmaps/roadmap-m2-plataforma-operacional.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Restrições e invariantes", a hipótese H4 e o "Protocolo de execução" —, a seção E02 inteira e o "Evidências e handoff" de E01. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: implementar em rooms as dimensões de status (ocupação, governança com inspeção pendente, sinalização do hóspede), as transições da SPEC §8.4 com origem, o histórico RoomStatusChange, a API pública (markDirty, markClean, markInspected, rejectInspection, markOccupied com número de hóspedes, markVacant, setServiceFlag, isReadyForCheckIn segundo RN-05), os endpoints de painel, histórico, mudança manual, sinalização e discrepâncias (RF-UH-08) e o evento RoomStatusChanged com payload mínimo. Não implemente OOO/OOS nem check-in real.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E02 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume a máquina de status de governança da docs/SPEC.md §8.4 e RF-UH-04 a 08, RN-05, RN-08 e RN-09;
- escreva primeiro os testes de domínio de todas as transições e de RN-05 e os de integração dos endpoints, histórico, discrepâncias e evento; implemente, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint;
- verificação independente com o Prompt do verificador, com foco em transição proibida aceita, mudança sem histórico, evento com dados pessoais e corrida entre mudanças simultâneas.

Critérios de aceite (detalhes na seção E02):
- todas as transições permitidas e proibidas da SPEC §8.4 cobertas por testes;
- toda mudança gera RoomStatusChange com quem, quando e origem;
- discrepâncias de ocupação e de número de hóspedes listadas;
- RoomStatusChanged publicado sem dados pessoais; contrato regenerado; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E02 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Testes de domínio cobrem cada transição permitida da máquina de status de governança da SPEC §8.4 e rejeitam as não previstas (ex.: DIRTY → INSPECTED) com `code` estável.
- [ ] `markClean(requiresInspection = true)` deixa a UH em CLEAN com `inspectionPending`; `markInspected()` remove a pendência; `rejectInspection()` volta a DIRTY.
- [ ] `isReadyForCheckIn` só devolve verdadeiro para UH vaga e Inspecionada, ou Limpa sem inspeção pendente (RN-05).
- [ ] Toda mudança, manual ou pela API pública, grava `RoomStatusChange` com usuário, instante e origem; `GET /api/v1/rooms/{id}/status-history` a devolve.
- [ ] Observação da governança divergente da ocupação ou do número de hóspedes aparece em `GET /api/v1/rooms/discrepancies`.
- [ ] Duas mudanças simultâneas na mesma UH não se sobrescrevem silenciosamente (lock otimista, teste concorrente).
- [ ] `RoomStatusChanged` é publicado pelo outbox com ID, dimensões e versão, sem dados de hóspede.
- [ ] Contrato regenerado; build, testes e lint passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Transições de origem automática (check-out, fechamento) ainda sem chamador: cobrir pela API pública em testes de módulo.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E03 — OOO e OOS — #pendente

**Objetivo:** implementar períodos fora de ordem (OOO) e fora de serviço (OOS) por UH (RF-UH-05, RN-07): período, motivo, responsável e status de retorno; OOO exige step-up e não se aplica a UH ocupada; liberação devolve a UH ao status de retorno; o evento `OutOfOrderChanged` avisa os módulos de inventário.

**Por que agora:** o inventário vendável (M4) e o channel manager (M8) dependem desse evento; RF-ACS-08 lista "colocar UH em OOO" como ação sensível.

**Dependências e paralelismo:** E02. Pode correr em paralelo com E04 e E05 (em worktree separado); conflito de edição em `api/openapi.yaml` e na API pública de `rooms`.

**Escopo:**

- `OutOfOrder` (tipo OOO/OOS, de, até, motivo, responsável, status de retorno, liberado em/por) — SPEC §8.2.3.
- Endpoints: `POST /api/v1/rooms/{id}/out-of-order` (abrir), `POST /api/v1/rooms/{id}/out-of-order/{oooId}/release`, edição de período e `GET /api/v1/out-of-order` (por período).
- Regras: OOO é ação sensível (porta de step-up de M1 E05); OOO não se aplica a UH ocupada no período atual (RN-07); períodos de OOO/OOS da mesma UH não se sobrepõem; OOS mantém a UH vendável.
- `isReadyForCheckIn` passa a considerar OOO (RN-05); o painel de status mostra a sobreposição OOO/OOS.
- Evento `OutOfOrderChanged` (UH, tipo, período, ação: aberto, alterado, liberado).
- API pública: períodos OOO/OOS por UH e intervalo de datas (para reservas em M4).

**Fora de escopo:** recálculo de inventário e alerta de reserva alocada (M4, ao escutar o evento); ordem de serviço ligada ao OOO (M6).

**Passos de implementação:**

1. Escrever testes de domínio (sobreposição, UH ocupada, retorno) e de integração (step-up, evento, painel).
2. Implementar domínio, migração, casos de uso e controllers.
3. Regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em RF-UH-05, RN-05, RN-07 e RF-ACS-08.
- Partes paralelas: nenhuma.
- Foco do verificador: OOO aplicado sem aprovação ou a UH ocupada; períodos sobrepostos; evento não publicado na liberação.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E03 — "OOO e OOS" do roadmap docs/roadmaps/roadmap-m2-plataforma-operacional.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Restrições e invariantes" e o "Protocolo de execução" —, a seção E03 inteira, o "Evidências e handoff" de E02, RF-UH-05, RN-05, RN-07 e RF-ACS-08 em docs/SPEC.md e o handoff de E05 (step-up) de docs/roadmaps/roadmap-m1-acesso-configuracao.md. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E02 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: implementar em rooms os períodos OOO e OOS com motivo, responsável e status de retorno; OOO é ação sensível com step-up e é rejeitado em UH ocupada; períodos da mesma UH não se sobrepõem; liberação devolve a UH ao status de retorno; isReadyForCheckIn considera OOO; o evento OutOfOrderChanged é publicado ao abrir, alterar e liberar. Não recalcule inventário nem toque em reservas, que são do marco M4.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E03 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de domínio e de integração primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em OOO sem aprovação ou em UH ocupada, períodos sobrepostos e evento ausente na liberação.

Critérios de aceite (detalhes na seção E03):
- OOO sem permissão → 403 APPROVAL_REQUIRED e concluído com X-Approval-Token;
- OOO em UH ocupada e período sobreposto rejeitados com code estável;
- liberação aplica o status de retorno; OutOfOrderChanged publicado nas três ações;
- contrato regenerado; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E03 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Abrir OOO sem a permissão devolve `403 APPROVAL_REQUIRED`; com `X-Approval-Token` válido é concluído e auditado com o aprovador.
- [ ] OOO em UH ocupada → erro com `code` estável (RN-07); OOS em UH ocupada é aceito.
- [ ] Período sobreposto ao de outro OOO/OOS da mesma UH → erro com `code` estável.
- [ ] Liberar o período aplica o status de governança de retorno e grava o histórico.
- [ ] `isReadyForCheckIn` devolve falso para UH em OOO.
- [ ] `OutOfOrderChanged` é publicado ao abrir, alterar e liberar (teste de módulo).
- [ ] Contrato regenerado; build, testes e lint passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- OOO futuro sobre reserva já alocada: o alerta só existe em M4; registrar no handoff que `OutOfOrderChanged` carrega o período inteiro para o consumidor decidir.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E04 — Tempo real (SSE) — #pendente

**Objetivo:** implementar em `shared` o canal de tempo real da ARCHITECTURE §10: `GET /api/v1/stream?topics=…` por SSE, eventos com payload mínimo filtrados por permissão e escopo do usuário, reconexão com `Last-Event-ID` a partir de um buffer circular e `reset` quando o ID saiu do buffer; e registrar o tópico `rooms` alimentado por `RoomStatusChanged`.

**Por que agora:** RNF-04 exige mudanças de status em até 2 s; notificações (E09), governança (M6) e PDV (M8) usam o mesmo canal.

**Dependências e paralelismo:** E02. Pode correr em paralelo com E03 e E05 (em worktree separado); conflito de edição em `api/openapi.yaml` e na configuração web de `shared`.

**Escopo:**

- Em `shared`: broadcaster em processo; porta para um módulo registrar um tópico (nome, permissão exigida, filtro por escopo `OWN` quando aplicável, conversão do evento de domínio em payload `type`, `id`, `version`); envio só após o commit.
- Endpoint `GET /api/v1/stream` autenticado, com `topics` validados contra os tópicos registrados e as permissões do usuário.
- Buffer circular por tópico (H5), `Last-Event-ID`, evento `reset`, *heartbeat* periódico para proxies e encerramento das conexões de sessão revogada.
- Tópico `rooms` registrado por `rooms` (permissão de leitura de status).
- Teste de latência: mudança de status até o recebimento pelo cliente em menos de 2 s.

**Fora de escopo:** tópicos `tasks`, `tabs`, `production` (M6, M8) e `notifications` (E09); `LISTEN/NOTIFY` para várias instâncias.

**Passos de implementação:**

1. Escrever testes de integração com cliente SSE real: recebimento, filtro por permissão, tópico desconhecido, reconexão com `Last-Event-ID`, `reset`, latência e evento não enviado em rollback.
2. Implementar broadcaster, porta, endpoint e tópico `rooms`.
3. Documentar no `CLAUDE.md` como um módulo registra um tópico.
4. Regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "o que ARCHITECTURE §10 e D10 e RNF-04 exigem do canal SSE?"; "como Spring MVC/WebFlux na versão registrada em M0 E01 suporta SSE com `Last-Event-ID` e o proxy Caddy do compose de M0 E06 trata conexões longas?".
- Partes paralelas: nenhuma.
- Foco do verificador: usuário recebendo evento de tópico sem permissão; evento enviado antes do commit ou em rollback; vazamento de conexões abertas; payload com dados além de `type`, `id` e `version`.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E04 — "Tempo real (SSE)" do roadmap docs/roadmaps/roadmap-m2-plataforma-operacional.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Restrições e invariantes", a hipótese H5 e o "Protocolo de execução" —, a seção E04 inteira e o "Evidências e handoff" de E02. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E02 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: implementar em shared o canal GET /api/v1/stream?topics=… por SSE: porta para módulos registrarem tópicos com permissão e filtro OWN, payload mínimo (type, id, version) enviado só após o commit, buffer circular com Last-Event-ID e reset, heartbeat e encerramento de conexões de sessão revogada; registrar o tópico rooms alimentado por RoomStatusChanged. shared não pode depender de rooms: o registro é feito pelo próprio módulo. Não implemente outros tópicos.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E04 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume docs/ARCHITECTURE.md §10 e D10, RNF-04 e o suporte a SSE e Last-Event-ID na versão do Spring registrada em M0, além do comportamento do proxy do compose;
- escreva os testes de integração com cliente SSE real primeiro, implemente, documente no CLAUDE.md, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint;
- verificação independente com o Prompt do verificador, com foco em evento sem permissão, envio antes do commit ou em rollback, conexões vazando e payload além do mínimo.

Critérios de aceite (detalhes na seção E04):
- cliente inscrito em rooms recebe RoomStatusChanged em menos de 2 s; sem permissão não recebe;
- reconexão com Last-Event-ID entrega os eventos perdidos; ID fora do buffer recebe reset;
- evento de transação revertida não é enviado; CLAUDE.md documenta o registro de tópicos;
- contrato regenerado; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E04 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Um cliente em `GET /api/v1/stream?topics=rooms` recebe a mudança de status de uma UH em menos de 2 s (teste de integração).
- [ ] Usuário sem a permissão do tópico recebe `403` ao se inscrever, ou não recebe os eventos, conforme documentado; tópico desconhecido → `400` com `code` estável.
- [ ] Reconexão com `Last-Event-ID` dentro do buffer entrega os eventos perdidos na ordem; fora do buffer, o cliente recebe `reset`.
- [ ] Mudança feita numa transação revertida não gera evento.
- [ ] O payload contém só `type`, `id` e `version`.
- [ ] Revogar a sessão encerra a conexão SSE dela.
- [ ] `CLAUDE.md` documenta como registrar um tópico; `ApplicationModules.verify()` passa sem `shared` depender de módulos; contrato regenerado; build, testes e lint passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Verificação do contrato → sucesso.
- Inspeção manual: `curl -N` contra o compose, através do proxy, recebe eventos e *heartbeat* sem corte.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Proxy com *buffering* atrasando eventos: desligar o buffer para `/api/v1/stream` na configuração do Caddy e registrar no handoff.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E05 — Sincronização offline — #pendente

**Objetivo:** implementar em `shared` o protocolo de sincronização da ARCHITECTURE §9: download incremental `GET /api/v1/sync/changes?cursor=…` por `change_seq` com *tombstones*, upload `POST /api/v1/sync/commands` com lote ordenado de comandos idempotentes por `commandId`, correção da defasagem de relógio do dispositivo e portas para cada módulo registrar fontes de mudança e handlers de comando; e registrar `rooms` como primeira fonte (número e status, sem dados pessoais).

**Por que agora:** RNF-05 exige o app operacional offline por um turno. Checklists (E07), governança e manutenção (M6) e rouparia (M9) registram seus handlers e fontes sobre este protocolo.

**Dependências e paralelismo:** E02. Pode correr em paralelo com E03 e E04 (em worktree separado); conflito de edição em `api/openapi.yaml` e nas migrações de `shared`.

**Escopo:**

- Em `shared`: *sequence* global `change_seq` e tabela de *tombstones* (H6); porta `OfflineChangeSource` (o que o usuário pode ver no escopo offline, mudado desde o cursor) e porta `OfflineCommandHandler` (tipo de comando, handler); registro por módulo, sem `shared` conhecer os módulos.
- `GET /api/v1/sync/changes`: devolve mudanças por fonte, *tombstones* e o novo cursor, paginado; respeita permissões e escopo; RN-13.
- `POST /api/v1/sync/commands`: lote ordenado `{commandId (UUIDv7), type, payload, occurredAt, deviceClock}`; processa em ordem; tabela de idempotência por `commandId`; resultado por comando (aplicado, rejeitado com motivo, aplicado com alerta); repetição devolve o resultado anterior.
- Relógio: defasagem calculada no início de cada sincronização a partir do `deviceClock`; `occurredAt` corrigido dentro de uma tolerância configurada; acima dela, aplicado com alerta.
- `rooms` registra uma fonte (UHs ativas: ID, número, andar, status e sinalização) e grava `change_seq` nas mudanças.
- Comando de exemplo em código de teste para validar o pipeline.

**Fora de escopo:** handlers de checklists (E07), governança e manutenção (M6) e enxoval (M9); armazenamento local no app (repositório do cliente).

**Passos de implementação:**

1. Escrever testes de integração: download inicial e incremental com *tombstone*, ausência de dados pessoais, lote com comando repetido, comando rejeitado sem interromper o lote, ordem de processamento, correção de relógio e cursor com permissões diferentes.
2. Implementar migrações, portas, endpoints e a fonte de `rooms`.
3. Documentar no `CLAUDE.md` como um módulo registra fonte de mudanças e handler de comando.
4. Regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "o que ARCHITECTURE §9, §6.2 e D11, RNF-05, RN-13 e RN-22 exigem da sincronização?"; "como a idempotência HTTP de M0 E05 foi implementada, para reaproveitar ou separar a tabela de `commandId`?".
- Partes paralelas: nenhuma.
- Foco do verificador: comando aplicado duas vezes; mudança perdida entre duas páginas do download (cursor não monotônico sob transações concorrentes); dados pessoais no download; `shared` dependendo de `rooms`.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E05 — "Sincronização offline" do roadmap docs/roadmaps/roadmap-m2-plataforma-operacional.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Restrições e invariantes", a hipótese H6 e o "Protocolo de execução" —, a seção E05 inteira e o "Evidências e handoff" de E02. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E02 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: implementar em shared o protocolo offline: GET /api/v1/sync/changes por cursor change_seq com tombstones, filtrado por permissão e sem dados pessoais (RN-13); POST /api/v1/sync/commands com lote ordenado, idempotência por commandId, resultado por comando (aplicado, rejeitado com motivo, aplicado com alerta) e correção da defasagem de relógio dentro de tolerância; portas OfflineChangeSource e OfflineCommandHandler registradas pelos módulos; rooms como primeira fonte (ID, número, andar, status, sinalização). shared não pode depender de módulos. Não implemente handlers de checklists, governança nem enxoval.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E05 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume docs/ARCHITECTURE.md §9, §6.2 e D11, RNF-05, RN-13 e RN-22 e como a idempotência HTTP de M0 E05 foi implementada;
- escreva os testes de integração primeiro, inclusive o de transações concorrentes durante o download; implemente, documente no CLAUDE.md, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint;
- verificação independente com o Prompt do verificador, com foco em comando aplicado duas vezes, mudança perdida entre páginas, dados pessoais no download e shared dependendo de rooms.

Critérios de aceite (detalhes na seção E05):
- download inicial e incremental com tombstones; nenhuma mudança perdida com transações concorrentes;
- comando repetido devolve o resultado anterior sem reaplicar; rejeição não interrompe o lote;
- occurredAt corrigido pela defasagem; acima da tolerância, aplicado com alerta;
- CLAUDE.md documenta fontes e handlers; contrato regenerado; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E05 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Download sem cursor devolve o estado completo visível ao usuário; com cursor devolve só o que mudou, incluindo *tombstones* de exclusões.
- [ ] Um teste com transações concorrentes demonstra que nenhuma mudança commitada fica para trás do cursor devolvido.
- [ ] A resposta do download não contém nome, documento nem contato de hóspede (RN-13).
- [ ] Um lote com o mesmo `commandId` repetido aplica o comando uma vez e devolve o mesmo resultado nas repetições.
- [ ] Um comando rejeitado devolve motivo com `code` estável e não impede os seguintes do lote.
- [ ] `occurredAt` é corrigido pela defasagem calculada; acima da tolerância, o resultado é "aplicado com alerta".
- [ ] `CLAUDE.md` documenta como registrar fonte e handler; `ApplicationModules.verify()` passa sem `shared` depender de módulos.
- [ ] Contrato regenerado; build, testes e lint passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Sequências do PostgreSQL não respeitam a ordem de commit: limitar o cursor ao menor `change_seq` de transações ainda abertas (ou técnica equivalente) e registrar a escolha.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E06 — Modelos de checklist — #pendente

**Objetivo:** criar o módulo `checklists` com modelos por contexto, versões publicadas imutáveis, seções e itens ordenáveis dos tipos da SPEC, itens obrigatórios e condicionais, regras de aplicabilidade e ações configuradas por item, mais a API pública que escolhe o modelo de um sujeito.

**Por que agora:** governança, manutenção, áreas comuns, restaurante e eventos usam o mesmo motor (SPEC §1.1). A execução (E07) depende dos modelos.

**Dependências e paralelismo:** nenhuma dentro deste marco; exige o gate de M1 concluído. Pode correr em paralelo com E01 a E05 (em worktree separado); conflito de edição em `api/openapi.yaml`.

**Escopo:**

- Módulo `checklists` (a criar) com schema próprio; `ChecklistTemplate` (nome, contexto: governança, inspeção, manutenção, área comum, restaurante, evento, outro; ativo), `ChecklistVersion` (número, publicada em), `ChecklistSection`, `ChecklistItem` (rótulo, tipo, obrigatório, ordem, condição `visibleWhen`, opções de escolha, unidade de medida), `ApplicabilityRule` (H3) e `ItemAction` (baixa de estoque, troca de enxoval, abrir ocorrência, com IDs opacos) — SPEC §8.2.11.
- Regras: rascunho editável; publicar congela a versão; editar um modelo publicado cria nova versão (RF-CHK-06); condição só referencia item anterior com resposta compatível; ação de baixa só em item de quantidade; foto nunca obrigatória por padrão.
- Endpoints: `/api/v1/checklist-templates` (CRUD do rascunho, `POST …/{id}/publish`, `GET …/{id}/versions/{number}`).
- API pública: `resolveTemplate(context, subjectAttributes)` (RF-CHK-04) e leitura de uma versão.
- Permissões e `@Audited`.

**Fora de escopo:** execução e rotinas (E07); consumidores das ações (M6, M9).

**Passos de implementação:**

1. Escrever testes de domínio (versionamento, validação de condições e ações, resolução por aplicabilidade) e de integração dos endpoints.
2. Implementar módulo, migrações, casos de uso, controllers e API pública.
3. Regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "o que SPEC §5.15 (RF-CHK-01 a 06) e §8.2.11 definem para modelos, itens, condicionais, aplicabilidade e ações?".
- Partes paralelas: nenhuma.
- Foco do verificador: versão publicada alterável; condição circular ou apontando para item posterior; `checklists` importando `rooms`, `inventory` ou outro módulo fora de `shared`, `staff` e `files`.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E06 — "Modelos de checklist" do roadmap docs/roadmaps/roadmap-m2-plataforma-operacional.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Restrições e invariantes", a hipótese H3 e o "Protocolo de execução" —, a seção E06 inteira e o "Evidências e handoff" da etapa E09 de docs/roadmaps/roadmap-m1-acesso-configuracao.md. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que a etapa E09 (gate) de docs/roadmaps/roadmap-m1-acesso-configuracao.md está #concluida e que o código bate com os handoffs de M1; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar o módulo checklists com modelos por contexto, rascunho editável, versões publicadas imutáveis (editar publicado cria nova versão), seções e itens ordenáveis dos tipos da SPEC, obrigatórios e condicionais, regras de aplicabilidade com IDs opacos, ações configuradas por item (baixa de estoque, troca de enxoval, abrir ocorrência), endpoints em /api/v1/checklist-templates e a API pública resolveTemplate(context, subjectAttributes). checklists depende só de shared, staff e files. Não implemente execução, rotinas nem consumidores das ações.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E06 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume docs/SPEC.md §5.15 (RF-CHK-01 a 06) e §8.2.11;
- escreva os testes de domínio e de integração primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em versão publicada alterável, condição inválida e dependência indevida de módulos.

Critérios de aceite (detalhes na seção E06):
- editar modelo publicado cria nova versão e a anterior permanece idêntica;
- condição apontando para item posterior ou com resposta incompatível e ação de baixa fora de item de quantidade rejeitadas com code estável;
- resolveTemplate escolhe o modelo pela aplicabilidade;
- contrato regenerado; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E06 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Publicar um rascunho cria a versão 1; editar o modelo publicado e publicar cria a versão 2, e a versão 1 continua idêntica (teste).
- [ ] Os sete tipos de item da SPEC §8.2.11 são aceitos; foto não é obrigatória por padrão.
- [ ] Condição que referencia item posterior, inexistente ou com valor incompatível → `400` com `code` estável.
- [ ] Ação de baixa de estoque fora de item de quantidade → `400` com `code` estável.
- [ ] `resolveTemplate` devolve o modelo cuja aplicabilidade casa com os atributos informados e, sem regra específica, o modelo geral do contexto (teste de módulo).
- [ ] Escritas com permissão e auditadas; `ApplicationModules.verify()` mostra `checklists` dependendo só de `shared`, `staff` e `files`.
- [ ] Contrato regenerado; build, testes e lint passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Linguagem de condição complexa demais: limitar a "item X tem resposta Y" e registrar como decisão.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E07 — Execução de checklists e rotinas — #pendente

**Objetivo:** permitir executar checklists online e offline, preservando os horários reais, publicar `ChecklistCompleted` com as respostas que têm ações, e gerar rotinas recorrentes por setor e escala após `BusinessDateAdvanced`.

**Por que agora:** é o fluxo de ponta a ponta do marco (ARCHITECTURE §6.2) e a base das tarefas de governança e manutenção em M6.

**Dependências e paralelismo:** E05, E06. Não paralelizável.

**Escopo:**

- `ChecklistExecution` (versão, sujeito por tipo e ID opaco, executor, início, conclusão, dispositivo, `clientCommandId`) e `ChecklistAnswer` — SPEC §8.2.11.
- Online: `POST /api/v1/checklist-executions` (iniciar, com sujeito e contexto), `PUT …/{id}/answers`, `POST …/{id}/complete`; validação de obrigatórios considerando condicionais; foto via `files` (finalidade nova registrada).
- Offline: handlers `StartChecklistExecution` e `CompleteChecklistExecution` (com respostas e horários) registrados na porta de E05, usando o UUID do cliente como ID; fonte de mudanças com as versões aplicáveis ao usuário e as rotinas atribuídas a ele.
- `ChecklistCompleted` pelo outbox, com execução, sujeito, versão e as respostas que disparam ações (tipo da ação e parâmetros). A API pública também permite que um módulo dono do sujeito (governança em M6) inicie e conclua execuções.
- Rotinas (RF-CHK-08): `RecurringChecklist` (modelo, local opcional, setor, frequência, horário); geração das execuções pendentes do dia para os colaboradores escalados no setor (API pública de `staff`), disparada por `BusinessDateAdvanced` (D1).
- Escopo `OWN` para o executor ver as próprias execuções.

**Fora de escopo:** tarefas de governança e inspeção (M6); consumidores das ações (M6, M9); relatórios (E08).

**Passos de implementação:**

1. Escrever testes: execução online com obrigatórios e condicionais, conclusão offline repetida (idempotência), horários preservados com correção de relógio, `ChecklistCompleted` com as ações (teste `Scenario`), geração de rotinas a partir da escala.
2. Implementar domínio, migrações, casos de uso, handlers, fonte de mudanças, listener de `BusinessDateAdvanced` (D1) e controllers.
3. Regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "o que RF-CHK-05, 07 e 08, RN-22, ARCHITECTURE §6.2, §9 e §13 e SPEC §8.3 dizem sobre execução offline, ações e rotinas?"; "como registrar handler e fonte de mudanças segundo o `CLAUDE.md` (E05) e qual a API pública de escala de `staff` (handoff de M1 E07)?".
- Partes paralelas: nenhuma.
- Foco do verificador: `ChecklistCompleted` publicado duas vezes para a mesma execução; conclusão com obrigatório visível sem resposta; horário do servidor no lugar do horário real; rotina gerada para colaborador de folga; dependência de módulo não registrada na ARCHITECTURE.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E07 — "Execução de checklists e rotinas" do roadmap docs/roadmaps/roadmap-m2-plataforma-operacional.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Restrições e invariantes", a decisão D1 e o "Protocolo de execução" —, a seção E07 inteira, o "Evidências e handoff" de E05 e E06 e o handoff de E07 (escala) de docs/roadmaps/roadmap-m1-acesso-configuracao.md. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E05 e E06 estão #concluida e que o código bate com o handoff delas; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: executar checklists online (/api/v1/checklist-executions: iniciar, responder, concluir com validação de obrigatórios e condicionais) e offline (handlers StartChecklistExecution e CompleteChecklistExecution e fonte de mudanças registrados na sincronização), preservando os horários reais; publicar ChecklistCompleted uma única vez com as respostas que têm ações; gerar rotinas por setor e escala por um listener de BusinessDateAdvanced (decisão D1: checklists depende de hotel, aresta já na ARCHITECTURE §3.1). Não implemente tarefas de governança nem consumidores das ações.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E07 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume RF-CHK-05, 07 e 08, RN-22, docs/ARCHITECTURE.md §6.2, §9 e §13, a SPEC §8.3 e a API pública de escala de staff;
- escreva os testes primeiro (online, offline repetido, horários, ChecklistCompleted com Scenario, rotinas), implemente, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint;
- verificação independente com o Prompt do verificador, com foco em evento duplicado, obrigatório sem resposta, horário do servidor no lugar do real, rotina para colaborador de folga e dependência não registrada.

Critérios de aceite (detalhes na seção E07):
- conclusão sem item obrigatório visível → erro com code estável; item oculto por condição não é exigido;
- CompleteChecklistExecution sincronizado duas vezes gera uma execução e um ChecklistCompleted;
- completedAt reflete o horário do dispositivo corrigido;
- rotinas geradas só para colaboradores escalados; contrato regenerado; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E07 (snapshot, arquivos alterados, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Concluir sem resposta a item obrigatório visível → erro com `code` estável; item obrigatório oculto por condição não é exigido.
- [ ] `CompleteChecklistExecution` enviado duas vezes (mesmo `commandId`) gera uma única execução e um único `ChecklistCompleted`.
- [ ] `startedAt` e `completedAt` refletem os horários do dispositivo corrigidos pela defasagem, não o horário de chegada ao servidor.
- [ ] `ChecklistCompleted` contém as respostas com ações configuradas (tipo e parâmetros) e é observado num teste `Scenario`.
- [ ] Após `BusinessDateAdvanced`, as rotinas do dia são geradas para os colaboradores escalados no setor e não para os de folga.
- [ ] O executor com escopo `OWN` vê só as próprias execuções.
- [ ] `checklists` depende de `hotel` conforme D1 e de nada além do listado na ARCHITECTURE §3.1; `ApplicationModules.verify()` passa.
- [ ] Contrato regenerado; build, testes e lint passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Execução offline sobre versão de modelo já substituída: aceitar e guardar a versão usada (RF-CHK-06), com teste.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E08 — Relatórios de checklists — #pendente

**Objetivo:** entregar os relatórios do RF-CHK-09 — conformidade por modelo, itens mais reprovados e histórico por local — a partir de views de leitura publicadas por `checklists`, no padrão que o `reporting` usará em M6.

**Por que agora:** fecha o requisito de checklists da Fase 0 e estabelece o primeiro exemplo de view `report_*` (ARCHITECTURE §11).

**Dependências e paralelismo:** E07. Pode correr em paralelo com E09 (em worktree separado); conflito de edição em `api/openapi.yaml`.

**Escopo:**

- Views SQL `checklists.report_*` (a criar) para conformidade, itens reprovados e histórico por local, documentadas como API de leitura do módulo.
- Endpoints de leitura em `/api/v1/checklist-reports` com filtros de período, modelo e local, via `JdbcClient` (D5), com permissão própria.
- Exportação CSV opcional se simples; caso contrário, fica para M6 (RF-REL-07).

**Fora de escopo:** o módulo `reporting` e painéis gerenciais (M6, M10).

**Passos de implementação:**

1. Escrever testes de integração com dados de execuções conhecidos e resultados esperados.
2. Implementar migração das views, consultas e endpoints.
3. Documentar no `CLAUDE.md` o padrão de view `report_*`.
4. Regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regra em RF-CHK-09 e ARCHITECTURE §11 ("Leitura para relatórios").
- Partes paralelas: nenhuma.
- Foco do verificador: conformidade calculada errado com itens "não se aplica"; consulta às tabelas internas em vez das views; relatório sem permissão.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E08 — "Relatórios de checklists" do roadmap docs/roadmaps/roadmap-m2-plataforma-operacional.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E08 inteira, o "Evidências e handoff" de E07, RF-CHK-09 em docs/SPEC.md §5.15 e "Leitura para relatórios" em docs/ARCHITECTURE.md §11. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E07 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar views checklists.report_* para conformidade por modelo, itens mais reprovados e histórico por local, e endpoints de leitura em /api/v1/checklist-reports com filtros de período, modelo e local, via JdbcClient e com permissão própria. Os endpoints consultam só as views. Não crie o módulo reporting.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E08 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de integração com dados conhecidos primeiro, implemente, documente no CLAUDE.md o padrão de view report_*, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint;
- verificação independente com o Prompt do verificador, com foco em itens "não se aplica" na conformidade, consulta a tabelas internas e falta de permissão.

Critérios de aceite (detalhes na seção E08):
- os três relatórios devolvem os valores esperados para o conjunto de dados de teste;
- itens "não se aplica" ficam fora do denominador da conformidade;
- sem permissão → 403; endpoints leem só as views;
- contrato regenerado; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E08 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Conformidade por modelo, itens mais reprovados e histórico por local devolvem os valores esperados para o conjunto de dados do teste.
- [ ] Respostas "não se aplica" ficam fora do denominador da conformidade.
- [ ] As consultas dos endpoints usam só as views `checklists.report_*`.
- [ ] Sem permissão → `403`.
- [ ] `CLAUDE.md` documenta o padrão de view `report_*`; contrato regenerado; build, testes e lint passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Views quebradas por migrações futuras do módulo: o teste de integração cobre cada view.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E09 — Notificações internas e push — #pendente

**Objetivo:** criar o módulo `notifications` com notificações internas para funcionários (painel e tópico SSE), preferências por usuário e tipo, registro de dispositivos de push vinculados à sessão e envio por FCM HTTP v1 atrás de uma porta, testado com WireMock.

**Por que agora:** RF-NOT-01 lista avisos de vários módulos (tarefa atribuída, UH liberada, OS urgente, falha de integração…); os marcos seguintes só chamam a API pública criada aqui.

**Dependências e paralelismo:** E04. Pode correr em paralelo com E08 (em worktree separado); conflito de edição em `api/openapi.yaml`.

**Escopo:**

- Módulo `notifications` (a criar) com schema próprio; `Notification`, `NotificationPreference`, `PushDevice` (vinculado à sessão de dispositivo de `iam`) — SPEC §8.2.18.
- Catálogo de tipos de notificação em código (os sete de RF-NOT-01, acrescentáveis por módulos).
- API pública: `notify(type, title, body, recipients)` com destinatários por usuário ou por permissão (resolvidos via `iam`). Pela regra por camadas, qualquer módulo de negócio pode chamá-la; `notifications` não escuta eventos de outros módulos.
- Endpoints: `GET /api/v1/notifications` (próprias, paginadas), `POST …/{id}/read`, `POST …/read-all`, `GET/PUT /api/v1/notification-preferences`, `POST/DELETE /api/v1/push-devices`.
- Tópico SSE `notifications` (só as próprias).
- Porta de push com adaptador FCM HTTP v1 (H7): envio assíncrono pelo outbox, retry com backoff, token inválido desativa o dispositivo, log de integração com payload sem dados pessoais; sessão revogada deixa de receber push.

**Fora de escopo:** e-mail SMTP (opcional, ARCHITECTURE §12; fica para M6 com os relatórios do fechamento); disparo das notificações pelos módulos consumidores.

**Passos de implementação:**

1. Escrever testes: entrega no painel e no SSE, preferências respeitadas, destinatários por permissão, push com WireMock simulando sucesso, token inválido e erro temporário, sessão revogada sem push.
2. Implementar módulo, migrações, API pública, controllers, tópico e adaptador.
3. Documentar no `README.md` as variáveis do FCM e no `CLAUDE.md` como um módulo envia notificação.
4. Regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "o que RF-NOT-01 e 02, SPEC §8.2.18 e ARCHITECTURE §12 exigem de notificações e push?"; "qual o formato de requisição e de erro do FCM HTTP v1 (token não registrado, cota, erro temporário)?".
- Partes paralelas: nenhuma.
- Foco do verificador: usuário recebendo notificação de outro (painel ou SSE); push enviado contra a preferência ou para sessão revogada; credencial FCM no repositório; falha do FCM derrubando a transação de quem notificou.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E09 — "Notificações internas e push" do roadmap docs/roadmaps/roadmap-m2-plataforma-operacional.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Restrições e invariantes", a hipótese H7 e o "Protocolo de execução" —, a seção E09 inteira e o "Evidências e handoff" de E04. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E04 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar o módulo notifications com notificações internas (painel e tópico SSE notifications, só as próprias), preferências por usuário e tipo, catálogo de tipos de RF-NOT-01, API pública notify com destinatários por usuário ou permissão, registro de dispositivos de push vinculados à sessão e envio por FCM HTTP v1 atrás de uma porta, assíncrono pelo outbox, com retry e desativação de token inválido. Sem credencial FCM configurada, o push fica desligado. notifications depende só de shared e iam. Não implemente e-mail nem os disparos dos módulos consumidores.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E09 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume RF-NOT-01 e 02, docs/SPEC.md §8.2.18, docs/ARCHITECTURE.md §12 e o formato de requisição e erros do FCM HTTP v1;
- escreva os testes primeiro (painel, SSE, preferências, destinatários por permissão, FCM com WireMock em sucesso, token inválido e erro temporário, sessão revogada), implemente, documente, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint;
- verificação independente com o Prompt do verificador, com foco em notificação de outro usuário, push contra preferência ou para sessão revogada, credencial no repositório e falha do FCM afetando quem notificou.

Critérios de aceite (detalhes na seção E09):
- notificação aparece no painel e no SSE só do destinatário; preferências respeitadas;
- push enviado ao FCM (WireMock); token inválido desativa o dispositivo; erro temporário é reprocessado;
- falha do FCM não reverte a transação de quem chamou notify; nenhuma credencial no repositório;
- contrato regenerado; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E09 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] `notify` para um usuário cria a notificação no painel dele e a envia no tópico SSE `notifications` só para ele; outro usuário não a vê.
- [ ] `notify` por permissão alcança exatamente os usuários ativos com a permissão.
- [ ] Preferência com push desligado para um tipo impede o push, mantendo o painel; com painel desligado, nada é criado para aquele tipo, salvo tipos obrigatórios documentados.
- [ ] Com WireMock simulando o FCM: sucesso registra envio; token não registrado desativa o `PushDevice`; erro temporário é reprocessado com backoff.
- [ ] Sessão de dispositivo revogada deixa de receber push.
- [ ] Uma falha do FCM não reverte a transação de quem chamou `notify` (teste).
- [ ] Nenhuma credencial FCM no repositório; `README.md` lista as variáveis; `CLAUDE.md` explica como notificar.
- [ ] `ApplicationModules.verify()` mostra `notifications` dependendo só de `shared` e `iam`; contrato regenerado; build, testes e lint passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Sem projeto Firebase (H7), o push real não é testado neste marco: registrar no handoff como pendência operacional.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E10 — Gate do marco M2 — #pendente

**Objetivo:** confirmar, numa cópia limpa do repositório, que toda a definição global de pronto do M2 é atendida, exercitando ponta a ponta o status em tempo real, a sincronização offline de um checklist e as notificações.

**Por que agora:** é o critério de entrada do marco M3.

**Dependências e paralelismo:** E03, E08, E09 (que cobrem transitivamente as demais). Não paralelizável.

**Escopo:**

- Rodar build, testes, lint, verificação do contrato e compose do zero numa cópia limpa.
- Roteiro ponta a ponta contra o compose: cadastrar tipo, UH e área; inscrever um cliente SSE em `rooms` e `notifications`; mudar status e abrir OOO com step-up; sincronizar um checklist concluído offline duas vezes; consultar relatórios; enviar uma notificação.
- Conferir cada item da "Definição global de pronto".
- Revisar `CLAUDE.md` e `README.md`; registrar a conclusão do M2 no histórico de `docs/roadmaps/README.md`.

**Fora de escopo:** funcionalidade nova; problemas encontrados voltam à etapa de origem, salvo ajustes triviais de documentação.

**Passos de implementação:**

1. Cópia limpa e validações completas.
2. Roteiro ponta a ponta com `curl`, registrado no handoff.
3. Evidência de cada item da definição de pronto.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação: nenhuma.
- Partes paralelas: nenhuma.
- Foco do verificador: item da definição de pronto sem evidência; dados pessoais na sincronização ou no SSE; dependência entre módulos fora da ARCHITECTURE §3.1; documentação desatualizada.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E10 — "Gate do marco M2" do roadmap docs/roadmaps/roadmap-m2-plataforma-operacional.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Definição global de pronto" e o "Protocolo de execução" —, a seção E10 inteira e o "Evidências e handoff" de E01 a E09. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 a E09 estão #concluida; se não, ou se o código divergiu do plano, pare e relate.

Objetivo: verificar numa cópia limpa do repositório, sem caches nem estado local, cada item da definição global de pronto do M2, e exercitar ponta a ponta contra o docker compose: cadastro de tipo, UH e área; cliente SSE em rooms e notifications; mudança de status e OOO com step-up; checklist concluído offline sincronizado duas vezes; relatórios; notificação. Não implemente funcionalidade nova: problemas voltam à etapa de origem, salvo ajustes triviais de documentação.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E10 para #em-andamento no índice e no título;
- sem investigação delegada;
- rode do zero os comandos oficiais de build, teste, lint e verificação do contrato e o roteiro ponta a ponta com curl contra o compose; registre a evidência de cada item;
- registre a conclusão do M2 no histórico de docs/roadmaps/README.md;
- verificação independente com o Prompt do verificador, com foco em item sem evidência, dados pessoais na sincronização ou no SSE, dependência fora da ARCHITECTURE §3.1 e documentação desatualizada.

Critérios de aceite (detalhes na seção E10):
- todos os itens da definição global de pronto marcados com evidência;
- roteiro ponta a ponta registrado no handoff;
- CLAUDE.md e README.md coerentes com o código; docs/roadmaps/README.md atualizado.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E10 (snapshot, verificações, resultados, parecer do verificador, notas para o marco M3) e responda com um resumo curto. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Todos os itens da "Definição global de pronto" estão marcados, cada um com evidência numa cópia limpa.
- [ ] O roteiro ponta a ponta contra o compose está registrado no handoff com comandos e respostas relevantes.
- [ ] `CLAUDE.md` e `README.md` estão coerentes com o código (tópicos SSE, sincronização, checklists, notificações, variáveis do FCM).
- [ ] `docs/roadmaps/README.md` registra a conclusão do M2 no histórico de revisões.

**Validação obrigatória:**

- Comandos oficiais de build, teste, lint e verificação do contrato numa cópia limpa → sucesso.
- `docker compose -f docker/compose.yaml up -d` na cópia limpa → readiness `UP` e roteiro ponta a ponta concluído; `down` em seguida.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- SSE pelo proxy do compose se comportando diferente do teste: o roteiro passa pelo proxy de propósito.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para o marco M3: —

---

## Histórico de revisões

- 2026-10-13 — roadmap criado.
- 2026-10-13 — aplicada a regra por camadas da ARCHITECTURE §3.1 (decisão do usuário): H2 virou a decisão D1 (`checklists` → `hotel`, listener de `BusinessDateAdvanced`); E07 não pergunta mais ao usuário nem edita a ARCHITECTURE; E09 explicita que módulos chamam `notifications` e que ele não escuta eventos.
