# Roadmap — M10 Comercial e gestão: eventos, contas a receber, preventiva, relatórios gerenciais e LGPD

> Arquivo: `docs/roadmaps/roadmap-m10-comercial-gestao.md` · criado em 2026-10-13 · última revisão 2026-10-13
>
> Marco 10 de 11 (último) · mapa dos marcos em [`docs/roadmaps/README.md`](README.md) · marco anterior: [M9 — Suprimentos](roadmap-m9-suprimentos.md) · não há próximo marco: o gate deste roadmap fecha a v1.
>
> **Pré-requisito:** o gate do marco M9 (última etapa de `docs/roadmaps/roadmap-m9-suprimentos.md`) está `#concluida`.

## Como usar

Duas formas, ambas na raiz do repositório:

- **Manual:** cole o prompt de uma etapa numa sessão limpa do Claude Code. Uma etapa por sessão.
- **Automática:** cole o prompt da seção "Execução automática" numa sessão limpa; ela executa até 3 etapas em sequência, um subagente por etapa mais um verificador, e para para revisão. Para não parar em cada pedido de permissão, libere antes os comandos de build/teste do projeto (registrados no `CLAUDE.md`) e o `docker` nas permissões do Claude Code.

Etapas marcadas como paralelizáveis só devem rodar ao mesmo tempo em worktrees git separados; na mesma pasta, rode-as uma de cada vez.

Em ambas, quem executa segue o "Protocolo de execução", atualiza a tag da etapa e preenche "Evidências e handoff". Não pule dependências. Revise e faça commit entre etapas ou lotes — nenhuma sessão faz commit por conta própria.

**Este roadmap foi escrito antes do código existir.** Antes da primeira etapa, sincronize-o com o código real usando a skill `development-roadmap` (modo atualização): confira caminhos, nomes de módulos, APIs públicas e convenções registrados nos handoffs de M0 a M9 e ajuste as etapas pendentes.

## Objetivo

Completar a gestão do hotel (SPEC §9, Fase 4) e fechar a v1. Ao fim do marco, o comercial vende e executa eventos completos e locações simples de espaços, com proposta, contrato, parcelas, BEO, bloqueio de UHs e faturamento; o financeiro opera contas a receber, inclusive a transferência de saldo no check-out; a manutenção gera OS preventivas a partir de planos por ativo ou área; a gerência consulta indicadores e relatórios gerenciais; e a retenção LGPD roda sozinha. A exportação contábil fica pronta assim que o formato for definido com o contador.

## Contexto confirmado

- Requisitos: SPEC §5.20 (EVT), §5.10 RF-CTA-13 (contas a receber) e RF-CTA-06 (pagamento faturado), §5.8 RF-REC-06 (check-out com transferência), §5.16 RF-MAN-04 e RF-MAN-05 (ativos e preventivas), §5.23 RF-REL-02 a RF-REL-05, RF-REL-07 e RF-REL-08, §5.5 RF-HOS-07 (LGPD), §5.7 RF-RES-09 (grupos e bloqueios).
- Regras de negócio: SPEC §6 RN-03 (imutabilidade financeira), RN-06 (check-out com saldo zero ou transferência a contas a receber), RN-12, RN-19 (dados sensíveis da FNRH), RN-20 (retenção e anonimização).
- Modelo de domínio: SPEC §8.2.16 (eventos e espaços), §8.2.7 (contas), §8.2.12 (`Asset`, `PreventivePlan`), §8.2.9 (`DailyStatistics`), §8.2.4 (hóspedes e empresas); eventos em §8.3 (`BusinessDateAdvanced`).
- Questões da SPEC §10: Q-01 (financeiro administrativo, fora da v1), Q-07 (prazos de retenção), Q-15 (cortesia e uso da casa nos indicadores).
- Arquitetura: ARCHITECTURE §3.1 — regra por camadas (um módulo pode depender de módulos de camada inferior da §3.2 e de qualquer módulo da Plataforma, sem ciclo; aresta nova é registrada na tabela na mesma mudança). `events` depende de `shared`, `guests`, `reservations`, `billing`, `payments`, `pos`, `checklists`, `files`, `printing`; `billing` depende de `shared`, `hotel`, `guests`, `payments`, `fiscal`; `maintenance` depende de `hotel`, `rooms`, `staff`, `checklists`, `inventory`, `files`, `notifications`; `reporting` lê só views `report_*` (§11); `guests` depende só de `shared` e `files`. ARCHITECTURE §8.3 (LGPD e cifragem), §13 (jobs: preventivas após `BusinessDateAdvanced`, retenção LGPD semanal), §11 (`EXCLUDE` com ranges, views de leitura).
- Convenções herdadas (ler nos handoffs e no `CLAUDE.md`): M0 — API, erros, migrações, `Money`, IDs, `Idempotency-Key`, outbox, ShedLock; M1 — autorização, step-up, auditoria, `hotel` (`BusinessDateAdvanced`), `files`; M2 — checklists, notificações; M3 — `guests` (empresas, consentimentos, dados cifrados), `billing` (contas, lançamentos, roteamento, caixa), `payments` manual; M4 — `reservations` (grupos e bloqueios); M5 — `frontdesk` (check-out exige saldo zero), `fnrh`, `printing` (PDFs); M6 — `maintenance` corretiva, `endofday` (`DailyStatistics`), `reporting` operacional; M7 — Pix e cartão em `payments`, `fiscal`; M8 — `pos`; M9 — fichas técnicas e API de baixa por ficha em `pos`.
- Comandos de build, teste e lint: os oficiais registrados no `CLAUDE.md` por M0 E01. Testes de integração com Testcontainers (base de M0 E02).
- Pacote base `com.zeny.hotel` (hipótese herdada de M0 H1). Módulo novo deste marco: `events` (a criar).

## Escopo

**Incluído:** contas a receber em `billing` e a transferência no check-out; módulo `events` (espaços, equipamentos, agenda, locação simples, evento completo, proposta, contrato, parcelas, bloqueio de UHs, BEO, checklists de montagem, faturamento e baixa de A&B); ativos e manutenção preventiva; indicadores, flash, produção e relatórios financeiros; exportação contábil (bloqueada até a definição do formato); retenção LGPD automática; gate final da v1.

**Fora de escopo:**

- Financeiro administrativo: contas a pagar, fluxo de caixa, DRE, conciliação bancária (SPEC Q-01).
- Tudo o que a SPEC §2.2 e a fase "Futuro" da §9 listam: interface do hóspede, fechaduras, PABX, RMS, ponto eletrônico, multi-idioma, multipropriedade.
- Equipamentos de evento como estoque: são recursos agendáveis, não itens de `inventory`.

## Restrições e invariantes

- Respeitar a regra por camadas da ARCHITECTURE §3.1: um módulo só chama a API pública de módulos de camada inferior da §3.2 ou da Plataforma, sem ciclo; o resto vai por evento ou porta. Aresta nova que siga a regra não exige aprovação, mas deve ser acrescentada à tabela §3.1 na mesma etapa e citada no handoff. Dependência para a mesma camada (fora da Plataforma) ou para cima exige parar e perguntar.
- RN-03: lançamentos, pagamentos e faturas não são editados nem excluídos; correção por estorno ou ajuste vinculado.
- Toda operação com efeito financeiro exige `Idempotency-Key` (ARCHITECTURE §7) e turno de caixa aberto quando há recebimento (RF-CTA-10).
- Retenção LGPD: anonimizar perfis preserva registros fiscais e contas por 5 anos (RN-20); dados sensíveis da FNRH nunca aparecem em relatórios (RN-19).
- Indicadores usam as estatísticas diárias de `endofday` (M6) e as views `report_*`; `reporting` nunca lê tabelas internas.
- Dados de teste fictícios; nenhum dado pessoal real no repositório.

## Hipóteses e decisões em aberto

| # | Hipótese ou decisão | Impacto se errada / sem decisão | Etapa afetada |
|---|---|---|---|
| H1 | Pacote base `com.zeny.hotel` e convenções de M0–M9 conforme os handoffs. | Ajustar caminhos na sincronização inicial. | todas |
| H2 | Decidido (2026-10-13): o módulo dono do documento monta os dados e chama `printing` para renderizar e enfileirar. `events` → `printing` e `billing` → `printing` já estão na ARCHITECTURE §3.1; `events` gera os PDFs de proposta e BEO, e `billing` gera o da fatura pelo mesmo mecanismo do extrato (RF-CTA-12, M5). | Se a API de `printing` registrada no handoff de M5 for diferente, ajustar a chamada. | E01, E05, E07 |
| H3 | Q-15: cortesia conta na ocupação e não no ADR; uso da casa não conta em nenhum dos dois (padrão da SPEC §10). | Indicadores divergentes do que a gerência espera; regra centralizada facilita mudar. | E10 |
| H4 | Q-07: dados sensíveis da FNRH expurgados 30 dias após o check-out; perfis anonimizados 5 anos após a última estadia, ambos configuráveis (padrão da SPEC §10). | Retenção maior ou menor que a exigida; prazos em configuração. | E13 |
| H5 | A data da última estadia chega a `guests` por uma porta definida em `guests` e implementada por `reservations` (inversão por porta: `guests` não depende de `reservations`). | Se `guests` já guardar a última estadia (handoff de M3/M4), usar o dado existente. | E13 |
| H6 | Os dados sensíveis da FNRH ficam no módulo indicado pelos handoffs de M3 e M5 (provável `fnrh`), e o expurgo roda nesse módulo. | Ajustar o dono do job na sincronização. | E13 |
| H7 | Parcelas de contrato são cobradas por Pix pela API de `payments` (M7). Se o adaptador Pix de M7 estiver bloqueado (SPEC Q-05), a parcela aceita registro manual de pagamento. | Cobrança manual até o PSP ser definido. | E06 |
| H8 | A distribuição do BEO aos setores (A&B, governança, manutenção) é feita por checklists de montagem atribuídos por setor (`events` depende de `checklists` ✓) e pelo PDF do BEO, que é a solução mais simples; a SPEC (RF-EVT-07) não pede notificação push. Pela regra por camadas, `events` poderia chamar `notifications`, `housekeeping` ou `maintenance` se surgir a necessidade, registrando a aresta na §3.1. | Se a operação pedir aviso push do BEO, acrescentar a chamada a `notifications` numa etapa futura. | E07 |
| H9 | Conflito de agenda de espaços e equipamentos é garantido no banco com `EXCLUDE USING gist` sobre `tstzrange` que inclui montagem e desmontagem (ARCHITECTURE §11). | Reservas sobrepostas se só a aplicação validar. | E03 |
| D1 | Formato da exportação contábil (RF-REL-08): layout, sistema contábil de destino e plano de contas, "a definir com o contador". | Impede E12. | E12 (bloqueada até decidir) |

## Definição global de pronto

- [ ] Contas a receber operam faturas, recebimentos, extrato e aging; o check-out transfere saldo para empresa com permissão.
- [ ] `events` existe como módulo do Modulith com agenda sem conflito, locação simples, evento completo (proposta, contrato, parcelas, bloqueio de UHs, BEO, checklists, faturamento e baixa de A&B).
- [ ] Ativos e planos preventivos geram OS automaticamente após `BusinessDateAdvanced`, sem duplicar.
- [ ] Indicadores (ocupação, ADR, RevPAR, TRevPAR, permanência), flash, produção e relatórios financeiros conferem com cálculo manual e exportam CSV.
- [ ] A retenção LGPD anonimiza perfis vencidos e expurga dados sensíveis da FNRH, preservando registros fiscais.
- [ ] A exportação contábil está implementada, ou D1 segue em aberto e o usuário registrou a decisão de fechar a v1 sem ela.
- [ ] Build, testes, lint, verificação do contrato e oasdiff passam numa cópia limpa; `ApplicationModules.verify()` passa com o grafo final documentado na ARCHITECTURE §3.1.

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
Você é o verificador independente da etapa ETAPA do roadmap docs/roadmaps/roadmap-m10-comercial-gestao.md. Você não participou da implementação e não deve editar nenhum arquivo.
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
Você é o orquestrador do roadmap docs/roadmaps/roadmap-m10-comercial-gestao.md neste repositório. Seu papel é coordenar subagentes, não implementar: não edite código e mantenha seu contexto enxuto (leia o roadmap e os relatórios dos subagentes, não o código).

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

**Contas a receber**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E01 | Contas a receber | #pendente | — | Transferência de saldo para empresa, faturas, recebimentos, extrato e aging em `billing` |
| E02 | Check-out com transferência para contas a receber | #pendente | E01 | RN-06 completa: check-out com saldo transferido, sob permissão |

**Eventos e espaços**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E03 | Espaços, equipamentos e agenda | #pendente | — | Módulo `events` com espaços, capacidades, equipamentos e agenda sem conflito |
| E04 | Locação simples de espaços | #pendente | E03 | Locação por data e hora com cliente, extras e conta |
| E05 | Evento completo e proposta versionada | #pendente | E03 | Funil do evento e proposta versionada com itens e PDF |
| E06 | Contrato, parcelas e bloqueio de UHs | #pendente | E05 | Contrato com cronograma, cobrança por Pix e bloqueio de UHs com data de corte |
| E07 | BEO e checklists de montagem | #pendente | E05 | OS do evento por atividade, PDF e checklists por setor |
| E08 | Faturamento do evento e baixa de A&B | #pendente | E01, E06, E07 | Conta master, lançamentos, faturamento a prazo e baixa por fichas técnicas |

**Manutenção preventiva**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E09 | Ativos e manutenção preventiva | #pendente | — | Ativos, planos preventivos e OS geradas após `BusinessDateAdvanced` |

**Relatórios gerenciais**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E10 | Indicadores e relatório flash | #pendente | E08 | Ocupação, ADR, RevPAR, TRevPAR, permanência e flash diário/mensal/anual |
| E11 | Produção e relatórios financeiros | #pendente | E01 | Produção por canal, on-the-books, pick-up, razões, caixas e balancete |
| E12 | Exportação contábil | #bloqueada | E10, E11 | Arquivo de exportação no formato definido com o contador |

**LGPD e fechamento da v1**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E13 | Retenção LGPD automática | #pendente | — | Job semanal de anonimização de perfis e expurgo de dados sensíveis da FNRH |
| E14 | Gate do marco M10 e da v1 | #pendente | E02, E04, E09, E12, E13 | Definição global de pronto e prontidão da v1 verificadas |

---

## E01 — Contas a receber — #pendente

**Objetivo:** implementar contas a receber em `billing` (RF-CTA-13): transferência de saldo de contas para um cliente empresa, faturas com vencimento, baixa de recebimentos (parcial e total), extrato por cliente e aging de 30/60/90 dias, além da forma de pagamento "faturado" (RF-CTA-06).

**Por que agora:** é a primeira etapa (confere o gate de M9) e destrava o check-out com transferência (E02), o faturamento a prazo de eventos (E08) e o razão de contas a receber (E11).

**Dependências e paralelismo:** nenhuma dentro deste roadmap; exige o gate de M9 `#concluida`. Pode correr em paralelo com E03, E09 e E13 (worktrees separados); sem conflito de arquivos com eles.

**Escopo:**

- Entidades de contas a receber em `billing` (a criar; nomes conforme o modelo de M3): conta do devedor (empresa de `guests`), fatura, itens da fatura (saldos transferidos), recebimento e alocação do recebimento às faturas.
- Transferência de saldo: zera a conta de origem por um lançamento de transferência vinculado (RN-03) e cria o débito no devedor; respeita o limite de crédito da empresa (RF-HOS-06) com step-up acima do limite.
- Fatura com número legível, vencimento, estados (aberta, parcialmente paga, paga, cancelada por estorno) e PDF conforme H2 (mesmo mecanismo do extrato de M3/M5).
- Recebimento exige turno de caixa aberto (RF-CTA-10) e `Idempotency-Key`; aceita as formas de pagamento de M3/M7.
- Extrato por cliente e aging 30/60/90 por data de vencimento; views `report_*` para o razão de contas a receber (consumidas em E11).
- Permissões: transferir para contas a receber, emitir fatura, baixar recebimento.

**Fora de escopo:** contas a pagar e conciliação bancária (Q-01); cobrança automática de faturas por boleto ou Pix (não está na SPEC).

**Passos de implementação:**

1. Testes de domínio: transferência zera a origem e cria débito igual; recebimento parcial e total; aging por faixa.
2. Testes de integração: limite de crédito excedido → `403 APPROVAL_REQUIRED`; recebimento sem caixa aberto → `409`; repetição com a mesma `Idempotency-Key` não duplica.
3. Implementar, publicar as views, regenerar contrato.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "como `billing` (M3) modela contas, `FolioOwner`, lançamentos de transferência, caixa e formas de pagamento, e como gera o PDF do extrato (handoff de M3 e M5)?"; "como `guests` expõe empresa e limite de crédito?".
- Partes paralelas: nenhuma.
- Foco do verificador: saldo transferido duas vezes; fatura ou recebimento editáveis (RN-03); recebimento sem caixa aberto; limite de crédito ignorado; soma das alocações diferente do recebido.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E01 — "Contas a receber" do roadmap docs/roadmaps/roadmap-m10-comercial-gestao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução", as restrições e a hipótese H2 —, a seção E01 inteira, RF-CTA-06, RF-CTA-10 e RF-CTA-13 em docs/SPEC.md §5.10, RN-03 e RN-06 em §6 e §8.2.7. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que a última etapa (gate) de docs/roadmaps/roadmap-m9-suprimentos.md está #concluida; se não estiver, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: contas a receber em billing — transferência de saldo de contas para empresa (lançamento de transferência vinculado, respeitando o limite de crédito com step-up acima dele), faturas com vencimento e PDF, recebimentos parciais e totais com caixa aberto e Idempotency-Key, extrato por cliente, aging 30/60/90 e views report_* do razão de contas a receber. Não implemente contas a pagar nem cobrança automática.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E01 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume como billing modela contas, transferências, caixa, formas de pagamento e PDF do extrato (handoffs de M3 e M5) e como guests expõe empresa e limite de crédito;
- escreva os testes de domínio e de integração primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em transferência duplicada, registros editáveis, recebimento sem caixa, limite de crédito ignorado e alocação inconsistente.

Critérios de aceite (detalhes na seção E01):
- transferência zera a conta de origem e cria débito igual no devedor, uma única vez;
- acima do limite de crédito → 403 APPROVAL_REQUIRED; recebimento sem caixa aberto → 409;
- recebimento parcial e total atualizam a fatura; aging por faixa confere com cálculo manual;
- build, testes e verificação do contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E01 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Transferir o saldo de uma conta para uma empresa zera a conta de origem com lançamento de transferência vinculado e cria o débito de mesmo valor no devedor; repetir com a mesma `Idempotency-Key` não duplica.
- [ ] Transferência que excede o limite de crédito da empresa → `403` com `code` `APPROVAL_REQUIRED`; com `X-Approval-Token` válido, conclui e audita o aprovador.
- [ ] Fatura recebe número legível e vencimento; recebimento parcial muda para parcialmente paga, total para paga; a soma das alocações é igual ao valor recebido.
- [ ] Recebimento sem turno de caixa aberto do usuário → `409` com `code` estável.
- [ ] Faturas, recebimentos e lançamentos não têm caminho de edição ou exclusão; correção por estorno.
- [ ] Aging 30/60/90 e extrato por cliente conferem com cálculo manual em teste de integração.
- [ ] Views `report_*` do razão de contas a receber existem.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Interação com a nota fiscal (M7): a NFS-e da hospedagem continua emitida no check-out; a fatura não emite nota nova. Registrar a regra no handoff.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E02 — Check-out com transferência para contas a receber — #pendente

**Objetivo:** completar RN-06 e RF-REC-06: o check-out aceita, com permissão, transferir o saldo das contas da reserva para uma empresa em contas a receber, em vez de exigir saldo zero.

**Por que agora:** remove a limitação deixada no check-out de M5 e é o uso mais comum de contas a receber em hotel.

**Dependências e paralelismo:** E01. Pode correr em paralelo com E03 a E07 (worktrees separados); sem conflito de arquivos.

**Escopo:**

- Opção de check-out em `frontdesk` (`frontdesk` depende de `billing` ✓) que chama a transferência de E01 para as contas com saldo e conclui o check-out na mesma transação.
- Permissão específica para transferir no check-out; limite de crédito herdado de E01.
- Comportamento sem a opção permanece idêntico (saldo zero obrigatório).

**Fora de escopo:** mudanças na emissão fiscal do check-out.

**Passos de implementação:**

1. Testes: check-out com saldo e sem a opção → mesmo erro de M5; com a opção e permissão → contas zeradas, débito criado e check-out concluído; sem permissão → `403`.
2. Implementar; regenerar contrato; rodar toda a suíte de `frontdesk`.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "como o check-out de `frontdesk` (handoff de M5) valida o saldo e quais efeitos executa na transação?".
- Partes paralelas: nenhuma.
- Foco do verificador: check-out concluído com transferência parcial (atomicidade); regressão no check-out sem a opção; transferência sem permissão.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E02 — "Check-out com transferência para contas a receber" do roadmap docs/roadmaps/roadmap-m10-comercial-gestao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E02 inteira, o "Evidências e handoff" de E01, RF-REC-06 em docs/SPEC.md §5.8 e RN-06 em §6. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: o check-out de frontdesk passa a aceitar, com permissão própria, a transferência do saldo das contas da reserva para uma empresa em contas a receber (API de E01), concluindo tudo numa única transação. Sem a opção, o comportamento atual (saldo zero obrigatório) não muda.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E02 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume como o check-out valida saldo e executa efeitos (handoff de M5);
- escreva os testes primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md, incluindo toda a suíte de frontdesk;
- verificação independente com o Prompt do verificador, com foco em atomicidade, regressão no check-out sem a opção e transferência sem permissão.

Critérios de aceite (detalhes na seção E02):
- com a opção e permissão: contas zeradas, débito em contas a receber e check-out concluído na mesma transação;
- sem permissão → 403; sem a opção, o check-out com saldo devolve o mesmo erro de antes;
- falha na transferência não conclui o check-out;
- build, testes e verificação do contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E02 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Check-out com saldo, opção de transferência e permissão: contas da reserva zeradas, débito criado em contas a receber e reserva encerrada, na mesma transação.
- [ ] Sem a permissão → `403`.
- [ ] Sem a opção, check-out com saldo devolve o mesmo erro e `code` de M5 (teste de regressão).
- [ ] Falha simulada na transferência deixa reserva e contas inalteradas.
- [ ] Toda a suíte de `frontdesk` passa; build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Efeitos assíncronos do check-out (FNRH, governança) não devem disparar se a transação falhar: confirmar que saem do outbox só após o commit.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E03 — Espaços, equipamentos e agenda — #pendente

**Objetivo:** criar o módulo `events` com espaços locáveis (capacidade por formato de montagem, preços por hora, meio período e diária), equipamentos de evento e a agenda de espaços e equipamentos com detecção de conflito que considera montagem e desmontagem (RF-EVT-01, RF-EVT-02, RF-EVT-11).

**Por que agora:** base de locação simples (E04) e de eventos completos (E05).

**Dependências e paralelismo:** nenhuma dentro deste roadmap; exige o gate de M9 `#concluida`. Pode correr em paralelo com E01, E09 e E13 (worktrees separados).

**Escopo:**

- Pacote `events` (a criar), schema e migrações próprios; `Space`, `SpaceCapacity`, `Equipment` (SPEC §8.2.16).
- Reservas de agenda (`SpaceBooking`, `EquipmentBooking`) com intervalo efetivo = início − montagem até fim + desmontagem; conflito impedido no banco (H9) e traduzido para `409` com `code` estável.
- Consulta de agenda por período e de disponibilidade de equipamento.
- Permissões do catálogo para gerenciar espaços e agenda.

**Fora de escopo:** cliente, preço e conta da locação (E04); funil do evento (E05).

**Passos de implementação:**

1. Confirmar o gate de M9 e sincronizar caminhos.
2. Testes de integração: duas reservas sobrepostas (inclusive só na montagem) → `409`; reservas encostadas → aceitas; equipamento com quantidade limitada.
3. Implementar módulo, constraint, endpoints; regenerar contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "qual o layout de módulo e como M4 usou `EXCLUDE USING gist` com ranges para alocação de UH (handoff de M4)?".
- Partes paralelas: nenhuma.
- Foco do verificador: conflito validado só na aplicação (corrida); montagem/desmontagem fora do cálculo; fuso horário errado nos intervalos.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E03 — "Espaços, equipamentos e agenda" do roadmap docs/roadmaps/roadmap-m10-comercial-gestao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a hipótese H9 —, a seção E03 inteira, RF-EVT-01, RF-EVT-02 e RF-EVT-11 em docs/SPEC.md §5.20, §8.2.16 e a linha de events em docs/ARCHITECTURE.md §3.1. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que a última etapa (gate) de docs/roadmaps/roadmap-m9-suprimentos.md está #concluida; se não estiver, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar o módulo events com espaços (capacidade por montagem, preços por hora, meio período e diária), equipamentos e agenda de espaços e equipamentos cujo conflito, incluindo montagem e desmontagem, é impedido no banco com EXCLUDE sobre tstzrange e devolvido como 409. Não implemente cliente, preço da locação nem funil de evento.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E03 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume o layout de módulo e o uso de EXCLUDE com ranges em reservations (handoff de M4);
- escreva os testes de integração primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em conflito validado só na aplicação, montagem fora do cálculo e fuso horário.

Critérios de aceite (detalhes na seção E03):
- reservas sobrepostas, inclusive só na montagem ou desmontagem → 409 com code estável;
- reservas encostadas são aceitas; equipamento respeita a quantidade disponível;
- o conflito é garantido por constraint no banco (teste concorrente);
- ModularityTest, build, testes e verificação do contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E03 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] CRUD de espaços (com capacidades por montagem e preços) e equipamentos em `/api/v1`, no contrato e com testes de integração.
- [ ] Reservas de agenda que se sobrepõem, inclusive apenas no tempo de montagem ou desmontagem → `409` com `code` estável.
- [ ] Reservas encostadas (fim + desmontagem = início − montagem) são aceitas.
- [ ] Duas reservas concorrentes conflitantes: só uma é gravada (constraint no banco).
- [ ] Equipamento com quantidade limitada recusa reservas além do disponível no intervalo.
- [ ] `ModularityTest` passa; build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Quantidade de equipamentos não se resolve com `EXCLUDE` simples: usar contagem com lock curto por equipamento, como os contadores de disponibilidade (ARCHITECTURE §11), e registrar a escolha.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E04 — Locação simples de espaços — #pendente

**Objetivo:** permitir a locação simples de um espaço por data e hora para um cliente pessoa física ou jurídica, com valor calculado pela tabela do espaço, serviços extras e conta para cobrança (RF-EVT-03).

**Por que agora:** é a forma mais simples de receita de espaços e valida a integração de `events` com `guests` e `billing` antes do evento completo.

**Dependências e paralelismo:** E03. Pode correr em paralelo com E05 (worktrees separados); conflito em migrações de `events`.

**Escopo:**

- Locação vinculada a hóspede ou empresa de `guests` (`events` depende de `guests` ✓) e a uma reserva de agenda de E03.
- Preço: hora, meio período ou diária conforme a duração (regra registrada), com extras (equipamentos, serviços).
- Conta da locação aberta em `billing` com `FolioOwner` do tipo locação/evento; lançamentos de espaço e extras com origem "evento" (RF-CTA-02); pagamento pelos fluxos de M3/M7.
- Cancelamento libera a agenda; com lançamentos, exige estorno.

**Fora de escopo:** proposta e contrato (E05, E06).

**Passos de implementação:**

1. Testes: preço por faixa de duração; conta criada e lançamentos feitos uma única vez; cancelamento libera a agenda.
2. Implementar; regenerar contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "como `billing` cria contas para donos que não são reservas (`FolioOwner`) e quais códigos de lançamento existem para espaço e evento (handoff de M3)?".
- Partes paralelas: nenhuma.
- Foco do verificador: preço calculado em ponto flutuante; lançamento duplicado; agenda não liberada no cancelamento.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E04 — "Locação simples de espaços" do roadmap docs/roadmaps/roadmap-m10-comercial-gestao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E04 inteira, o "Evidências e handoff" de E03 e RF-EVT-03 em docs/SPEC.md §5.20. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E03 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: locação simples de espaço por data e hora para hóspede ou empresa, com preço pela tabela do espaço (hora, meio período ou diária), extras, conta própria em billing com lançamentos de origem evento feitos uma única vez, e cancelamento que libera a agenda (com estorno se já houver lançamentos). Não implemente proposta nem contrato.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E04 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume como billing cria contas para donos que não são reservas e quais códigos de lançamento existem (handoff de M3);
- escreva os testes primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em preço em ponto flutuante, lançamento duplicado e agenda não liberada.

Critérios de aceite (detalhes na seção E04):
- preço por faixa de duração confere com a regra registrada;
- conta aberta e lançamentos feitos uma única vez com origem evento;
- cancelamento libera a agenda; com lançamentos, exige estorno;
- build, testes e verificação do contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E04 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Locação para hóspede ou empresa reserva a agenda e calcula o preço pela regra de duração registrada no handoff (teste para hora, meio período e diária).
- [ ] Uma conta é aberta em `billing` e os lançamentos de espaço e extras são feitos uma única vez, com origem "evento".
- [ ] Cancelar locação sem lançamentos libera a agenda; com lançamentos → `409` orientando o estorno.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Regra de faixa de preço não definida na SPEC: escolher o menor preço entre as combinações e registrar como decisão.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E05 — Evento completo e proposta versionada — #pendente

**Objetivo:** implementar o evento completo com o funil consulta → proposta → confirmado → realizado/cancelado (RF-EVT-04) e a proposta versionada com espaços, A&B por pessoa, equipamentos, serviços, hospedagem prevista, condições de pagamento, validade e PDF (RF-EVT-05).

**Por que agora:** é o núcleo comercial; contrato (E06) e BEO (E07) partem da proposta aceita.

**Dependências e paralelismo:** E03. Pode correr em paralelo com E04 (worktrees separados); conflito em migrações de `events`.

**Escopo:**

- `Event` com estados e transições (§8.2.16), cliente (hóspede ou empresa) e responsável comercial (usuário), participantes e datas.
- `Proposal` e `ProposalItem` versionados: editar uma proposta enviada cria nova versão; aceitar uma versão confirma o evento e congela os preços.
- Itens de A&B referenciam produtos do cardápio de `pos` (`events` depende de `pos` ✓); espaços e equipamentos reservam agenda provisória (E03) ao aceitar.
- PDF da proposta (H2): `events` monta os dados da versão e chama `printing` para gerar o PDF (`events` → `printing` já na ARCHITECTURE §3.1).
- Cancelamento com motivo; reabertura não prevista.

**Fora de escopo:** contrato e parcelas (E06); BEO (E07); faturamento (E08).

**Passos de implementação:**

1. Ler no handoff de M5 a API de `printing` para gerar PDF a partir de um template.
2. Testes de domínio das transições e do versionamento; teste de integração do aceite (evento confirmado, agenda reservada, preços congelados) e da geração do PDF da proposta.
3. Implementar, incluindo o template e o PDF da proposta; regenerar contrato.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "como `printing` (M5) expõe a geração de PDF e quem a chama hoje?"; "como `pos` expõe produtos do cardápio e preços (handoff de M8)?".
- Partes paralelas: nenhuma.
- Foco do verificador: PDF montado com dados de versão diferente da pedida; versão aceita alterável; aceite de proposta vencida; preço da proposta mudando após o aceite.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E05 — "Evento completo e proposta versionada" do roadmap docs/roadmaps/roadmap-m10-comercial-gestao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a hipótese H2 —, a seção E05 inteira, o "Evidências e handoff" de E03, RF-EVT-04 e RF-EVT-05 em docs/SPEC.md §5.20, §8.2.16 e a linha de events em docs/ARCHITECTURE.md §3.1. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E03 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: evento completo com funil consulta → proposta → confirmado → realizado/cancelado e proposta versionada (espaços, A&B por pessoa com produtos do pos, equipamentos, serviços, hospedagem prevista, condições e validade); aceitar uma versão confirma o evento, reserva a agenda e congela os preços. O PDF da proposta é gerado por events chamando printing (dependência já prevista na docs/ARCHITECTURE.md §3.1).

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E05 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume como printing gera PDF (handoff de M5) e como pos expõe produtos e preços (handoff de M8);
- escreva os testes de domínio e de integração primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em PDF com dados da versão errada, versão aceita alterável, aceite de proposta vencida e preço mudando após o aceite.

Critérios de aceite (detalhes na seção E05):
- transições inválidas do evento → 409; editar proposta enviada cria nova versão;
- aceitar versão válida confirma o evento, reserva a agenda e congela preços; proposta vencida → 409;
- PDF da proposta gerado para a versão pedida;
- ModularityTest, build, testes e verificação do contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E05 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Transições do evento fora do funil → `409` com `code` estável.
- [ ] Editar uma proposta já enviada cria nova versão; versões anteriores ficam imutáveis e consultáveis.
- [ ] Aceitar uma versão dentro da validade confirma o evento, cria as reservas de agenda de espaços e equipamentos e congela os preços; aceitar proposta vencida → `409`.
- [ ] Itens de A&B referenciam produtos do cardápio de `pos`.
- [ ] O PDF da proposta é gerado por `printing` com os dados da versão pedida (teste de integração).
- [ ] Toda dependência nova segue a regra por camadas, está na ARCHITECTURE §3.1 e `ModularityTest` passa.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Hospedagem prevista na proposta é só estimativa; o bloqueio real de UHs acontece em E06.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E06 — Contrato, parcelas e bloqueio de UHs — #pendente

**Objetivo:** registrar o contrato do evento com cronograma de pagamentos (sinal e parcelas), cobrança por Pix e controle de recebimento (RF-EVT-06), e criar o bloqueio de UHs para participantes com tarifa do evento e data de corte, integrado aos grupos e bloqueios de `reservations` (RF-EVT-08, RF-RES-09).

**Por que agora:** confirma o compromisso financeiro do evento e reserva o inventário de hospedagem.

**Dependências e paralelismo:** E05. Pode correr em paralelo com E07 (worktrees separados); conflito em migrações de `events`.

**Escopo:**

- `Contract` e `PaymentScheduleItem` (§8.2.16): parcelas com vencimento e valor somando o total da proposta aceita.
- Cobrança Pix por parcela via `payments` (H7, `events` depende de `payments` ✓); confirmação marca a parcela paga e registra o pagamento na conta master do evento em `billing`; fallback de registro manual se o Pix estiver indisponível.
- Conta master do evento em `billing` criada no contrato (usada em E08).
- Bloqueio de UHs: criação pela API de grupos e bloqueios de `reservations` (`events` depende de `reservations` ✓) com quantidade por tipo e data, tarifa do evento e data de corte; a liberação automática no fechamento já existe (M4/M6).
- Parcelas vencidas listadas para cobrança.

**Fora de escopo:** faturamento a prazo do saldo (E08); rooming list além do que M4 oferece.

**Passos de implementação:**

1. Testes: soma das parcelas diferente do total → `400`; confirmação de Pix marca a parcela e lança na conta master uma única vez; bloqueio criado em `reservations` com a data de corte.
2. Implementar; regenerar contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "como `payments` cria e confirma cobrança Pix e publica `PaymentSucceeded` (handoff de M7)?"; "qual a API de grupos e bloqueios de `reservations` (handoff de M4)?".
- Partes paralelas: nenhuma.
- Foco do verificador: pagamento de parcela registrado duas vezes; parcelas que não somam o total; bloqueio criado fora de `reservations` (contadores de disponibilidade ignorados).

**Prompt para uma sessão limpa:**

```text
Execute a etapa E06 — "Contrato, parcelas e bloqueio de UHs" do roadmap docs/roadmaps/roadmap-m10-comercial-gestao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a hipótese H7 —, a seção E06 inteira, o "Evidências e handoff" de E05, RF-EVT-06 e RF-EVT-08 em docs/SPEC.md §5.20 e RF-RES-09 em §5.7. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E05 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: contrato do evento com parcelas (sinal e parcelas somando o total da proposta aceita), cobrança Pix por parcela via payments com registro idempotente do pagamento na conta master do evento em billing (registro manual se o Pix estiver indisponível) e bloqueio de UHs com tarifa do evento e data de corte criado pela API de grupos e bloqueios de reservations. Não implemente faturamento a prazo.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E06 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume a cobrança Pix e PaymentSucceeded em payments (handoff de M7) e a API de grupos e bloqueios de reservations (handoff de M4);
- escreva os testes primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em pagamento duplicado, parcelas que não somam o total e bloqueio fora de reservations.

Critérios de aceite (detalhes na seção E06):
- parcelas que não somam o total → 400;
- confirmação do Pix marca a parcela paga e lança na conta master uma única vez;
- bloqueio aparece em reservations com tarifa e data de corte e afeta a disponibilidade;
- build, testes e verificação do contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E06 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Contrato com parcelas cuja soma difere do total da proposta aceita → `400` com `code` estável.
- [ ] Cobrança Pix criada por parcela; a confirmação (evento de `payments`) marca a parcela paga e registra o pagamento na conta master uma única vez, mesmo com reentrega.
- [ ] Registro manual de pagamento de parcela funciona com caixa aberto.
- [ ] O bloqueio de UHs é criado em `reservations`, com tarifa do evento e data de corte, e reduz a disponibilidade (teste de integração).
- [ ] Endpoint lista parcelas vencidas.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- `events` escutar `PaymentSucceeded` de `payments` é permitido (`events` depende de `payments`); garantir a correlação pelo ID da cobrança.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E07 — BEO e checklists de montagem — #pendente

**Objetivo:** gerar a OS do evento (BEO) por atividade — espaço, horário, montagem, cardápio e quantidades, equipamentos e instruções por setor — distribuí-la aos setores por checklists de montagem e disponibilizá-la em PDF (RF-EVT-07).

**Por que agora:** é o documento operacional do evento e fornece as quantidades planejadas e servidas usadas na baixa de A&B (E08).

**Dependências e paralelismo:** E05. Pode correr em paralelo com E06 (worktrees separados); conflito em migrações de `events`.

**Escopo:**

- `EventFunction`, `EventOrder`, `EventMenuLine` (planejado e servido) (§8.2.16).
- Emissão do BEO a partir do evento confirmado; reemissão gera nova versão com data.
- Checklists de montagem atribuídos por setor via API de `checklists` (H8), usando modelos de contexto "evento" (RF-CHK-01).
- PDF do BEO gerado por `events` chamando `printing` (H2).
- Registro das quantidades servidas por linha de cardápio após o evento.

**Fora de escopo:** baixa de estoque e faturamento (E08); notificação push aos setores (H8).

**Passos de implementação:**

1. Testes: BEO de evento não confirmado → `409`; emissão cria checklists por setor; reemissão versiona; quantidades servidas registradas.
2. Implementar; regenerar contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "como `checklists` (M2) cria execuções atribuídas a setor e quais modelos de contexto 'evento' existem?".
- Partes paralelas: nenhuma.
- Foco do verificador: checklists duplicados na reemissão; BEO antigo sobrescrito; PDF do BEO com versão errada.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E07 — "BEO e checklists de montagem" do roadmap docs/roadmaps/roadmap-m10-comercial-gestao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e as hipóteses H2 e H8 —, a seção E07 inteira, o "Evidências e handoff" de E05, RF-EVT-07 em docs/SPEC.md §5.20 e §8.2.16. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E05 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: emitir o BEO de evento confirmado por atividade (espaço, horário, montagem, cardápio com quantidades planejadas, equipamentos, instruções por setor), versionar reemissões, criar checklists de montagem por setor pela API de checklists, gerar o PDF do BEO via printing e registrar as quantidades servidas. A distribuição aos setores é por checklists; não acrescente notificações push (H8).

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E07 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume como checklists cria execuções atribuídas a setor e os modelos de contexto evento (handoff de M2);
- escreva os testes primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em checklists duplicados, BEO sobrescrito e PDF com versão errada.

Critérios de aceite (detalhes na seção E07):
- BEO de evento não confirmado → 409; emissão cria checklists por setor uma única vez;
- reemissão cria nova versão sem apagar a anterior nem duplicar checklists;
- quantidades servidas registradas por linha;
- ModularityTest, build, testes e verificação do contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E07 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Emitir BEO de evento não confirmado → `409`.
- [ ] A emissão cria um checklist de montagem por setor com instruções, via `checklists`, uma única vez.
- [ ] Reemitir cria nova versão datada; a anterior continua consultável e os checklists não se duplicam (os novos itens são acrescentados ou os pendentes substituídos, conforme decisão registrada).
- [ ] Quantidades servidas registradas por linha de cardápio.
- [ ] PDF do BEO gerado por `printing` para a versão emitida.
- [ ] `ModularityTest` passa; toda aresta nova está na ARCHITECTURE §3.1.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Setores sem escala no dia do evento: checklist atribuído ao setor sem executor, visível à liderança (RF-CHK-08).

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E08 — Faturamento do evento e baixa de A&B — #pendente

**Objetivo:** faturar o evento na conta master (espaços, A&B, equipamentos e serviços), emitir as notas pelo fluxo de `billing`/`fiscal`, permitir faturamento a prazo do saldo em contas a receber e dar baixa no estoque do A&B consumido pelas fichas técnicas a partir das quantidades servidas (RF-EVT-09, RF-EVT-10).

**Por que agora:** fecha o ciclo financeiro e de custos do evento; alimenta a receita de eventos nos indicadores (E10).

**Dependências e paralelismo:** E01, E06, E07. Não paralelizável.

**Escopo:**

- Encerramento do evento (realizado): lançamentos na conta master por código de lançamento de espaço, A&B e equipamentos, com origem "evento", idempotentes.
- Emissão fiscal pelo mecanismo que `billing` já usa (M7), por desenho: uma única via de emissão evita nota duplicada. A regra por camadas permitiria `events` → `fiscal`, mas não é necessário.
- Faturamento a prazo: transferência do saldo da conta master para a empresa em contas a receber (E01).
- Baixa de A&B: chamada à API de baixa por ficha técnica de `pos` (M9 E12) com as quantidades servidas de E07, idempotente por (evento, linha).
- Receita de eventos identificável por departamento nas views usadas por `endofday`/`reporting`.

**Fora de escopo:** indicadores (E10).

**Passos de implementação:**

1. Testes: encerrar evento lança uma única vez; saldo transferido a contas a receber; baixa de estoque proporcional às quantidades servidas sem duplicar; nenhuma nota emitida fora do fluxo de `billing`.
2. Implementar; regenerar contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "como `billing` dispara a emissão fiscal de uma conta (handoff de M7)?"; "qual a API de baixa por ficha técnica de `pos` (handoff de M9 E12)?".
- Partes paralelas: nenhuma.
- Foco do verificador: lançamento ou baixa duplicados no reencerramento; nota emitida fora do fluxo de `billing` ou baixa fora das fichas técnicas de `pos`; parcelas já pagas (E06) não abatidas do saldo.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E08 — "Faturamento do evento e baixa de A&B" do roadmap docs/roadmaps/roadmap-m10-comercial-gestao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E08 inteira, o "Evidências e handoff" de E01, E06 e E07, RF-EVT-09 e RF-EVT-10 em docs/SPEC.md §5.20 e a linha de events em docs/ARCHITECTURE.md §3.1. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01, E06 e E07 estão #concluida e que o código bate com o handoff delas; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: ao marcar o evento como realizado, lançar espaços, A&B e equipamentos na conta master uma única vez (origem evento), emitir notas pelo fluxo existente de billing, permitir transferir o saldo para contas a receber (E01) e dar baixa no estoque pelas fichas técnicas via API de pos com as quantidades servidas, idempotente por (evento, linha). Por desenho, a nota sai só pelo fluxo de billing e a baixa só pela API de fichas técnicas de pos.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E08 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume como billing dispara a emissão fiscal (handoff de M7) e a API de baixa por ficha técnica de pos (handoff de M9);
- escreva os testes primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em duplicidade no reencerramento, nota ou baixa fora das vias previstas e parcelas pagas não abatidas.

Critérios de aceite (detalhes na seção E08):
- encerrar o evento lança na conta master uma única vez, com parcelas pagas abatidas do saldo;
- saldo transferível a contas a receber; notas emitidas pelo fluxo de billing;
- baixa de estoque proporcional às quantidades servidas, sem duplicar;
- ModularityTest, build, testes e verificação do contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E08 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Marcar o evento como realizado lança espaços, A&B e equipamentos na conta master uma única vez (repetir não duplica).
- [ ] O saldo da conta master considera as parcelas já pagas em E06.
- [ ] A emissão fiscal acontece só pelo fluxo de `billing` (nenhuma nota emitida diretamente por `events`, verificado em teste).
- [ ] O saldo pode ser transferido para a empresa em contas a receber.
- [ ] A baixa de estoque usa as quantidades servidas e as fichas técnicas, uma única vez por linha.
- [ ] A receita do evento aparece com o departamento "eventos" nas views de leitura.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Regras fiscais de evento (NFS-e de locação × NFC-e de A&B) dependem das decisões de M7 (Q-02 a Q-04): seguir o que M7 registrou e listar divergências no handoff.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E09 — Ativos e manutenção preventiva — #pendente

**Objetivo:** cadastrar ativos (local, categoria, fabricante, modelo, nº de série, garantia, documentos) e planos preventivos por ativo ou área, com frequência e checklist, que geram OS automaticamente após `BusinessDateAdvanced` e alertam vencimentos (RF-MAN-04, RF-MAN-05).

**Por que agora:** completa a manutenção iniciada em M6 e é independente do resto do marco.

**Dependências e paralelismo:** nenhuma dentro deste roadmap; exige o gate de M9 `#concluida`. Pode correr em paralelo com E01, E03 e E13 (worktrees separados).

**Escopo:**

- `Asset` e `PreventivePlan` em `maintenance` (§8.2.12); documentos do ativo em `files` (`maintenance` depende de `files` ✓).
- Listener de `BusinessDateAdvanced` (`maintenance` depende de `hotel` ✓) que gera as OS vencidas na data operacional, com o checklist do plano (`checklists` ✓), de forma idempotente por (plano, data de vencimento).
- Alerta de vencimento próximo e de garantia vencendo via `notifications` (`maintenance` depende de `notifications` ✓).
- Histórico de OS por ativo.

**Fora de escopo:** contratos de manutenção com terceiros e custos de contrato.

**Passos de implementação:**

1. Testes: avanço de data gera a OS vencida uma única vez (reentrega do evento e reexecução); frequência mensal e por dias; plano inativo não gera.
2. Implementar; regenerar contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "como `maintenance` (M6) cria OS e como os módulos escutam `BusinessDateAdvanced` (handoffs de M1 e M6)?".
- Partes paralelas: nenhuma.
- Foco do verificador: OS duplicada na reentrega do evento; data operacional confundida com data do relógio; plano que pula vencimentos quando o fechamento atrasa mais de um dia.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E09 — "Ativos e manutenção preventiva" do roadmap docs/roadmaps/roadmap-m10-comercial-gestao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E09 inteira, RF-MAN-04 e RF-MAN-05 em docs/SPEC.md §5.16, §8.2.12 e a linha de geração de preventivas em docs/ARCHITECTURE.md §13. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que a última etapa (gate) de docs/roadmaps/roadmap-m9-suprimentos.md está #concluida; se não estiver, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: ativos com documentos em files e planos preventivos por ativo ou área com frequência e checklist; um listener de BusinessDateAdvanced gera as OS vencidas na data operacional, idempotente por (plano, vencimento), sem pular vencimentos quando a data avança mais de um dia; alertas de vencimento e de garantia via notifications. Não implemente contratos com terceiros.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E09 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume como maintenance cria OS e como os módulos escutam BusinessDateAdvanced (handoffs de M1 e M6);
- escreva os testes primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em OS duplicada, data operacional × relógio e vencimentos pulados.

Critérios de aceite (detalhes na seção E09):
- avanço da data gera cada OS vencida uma única vez, com o checklist do plano;
- avanço de vários dias gera todas as OS vencidas no intervalo;
- plano inativo não gera; alertas de vencimento e garantia notificados;
- build, testes e verificação do contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E09 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] CRUD de ativos com documentos anexados via `files` e histórico de OS por ativo.
- [ ] `BusinessDateAdvanced` gera as OS preventivas vencidas na data operacional, com o checklist do plano; reentregar o evento não duplica (teste de módulo).
- [ ] Se a data operacional avançar mais de um dia, todas as OS vencidas no intervalo são geradas.
- [ ] Plano inativo não gera OS.
- [ ] Vencimento próximo e garantia vencendo geram notificação.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Planos por área podem colidir com a faxina programada da governança (RF-GOV-10): manter separados (preventiva é manutenção) e registrar.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E10 — Indicadores e relatório flash — #pendente

**Objetivo:** disponibilizar os indicadores ocupação, ADR, RevPAR, TRevPAR e permanência média por dia, mês e ano com comparação ao período anterior (RF-REL-02) e o relatório gerencial diário (flash) para dia, mês e ano (RF-REL-03), com exportação CSV.

**Por que agora:** depende das estatísticas diárias de M6 e da receita de eventos (E08).

**Dependências e paralelismo:** E08. Pode correr em paralelo com E11 (worktrees separados); conflito em `reporting`.

**Escopo:**

- Fórmulas centralizadas em `reporting`, com a regra de Q-15 (H3) explícita: cortesia conta na ocupação e não no ADR; uso da casa não conta em nenhum; OOO fora do denominador de UHs disponíveis (conforme `DailyStatistics`).
- Leitura só de views `report_*` e das estatísticas diárias de `endofday`.
- Flash: UHs vendidas, cortesias, OOO, ocupação, ADR, RevPAR, receitas por departamento (hospedagem, A&B, eventos, outras), no-shows e cancelamentos, para dia, mês até a data e ano até a data, com comparação.
- Exportação CSV; o PDF do flash arquivado no fechamento segue o mecanismo de M6, se existir.

**Fora de escopo:** produção por canal e relatórios financeiros (E11).

**Passos de implementação:**

1. Testes com estatísticas semeadas que conferem cada indicador contra cálculo manual, incluindo cortesia, uso da casa e OOO.
2. Implementar; regenerar contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "que campos `DailyStatistics` e as views `report_*` publicam hoje e como `reporting` organiza endpoints e CSV (handoff de M6)?".
- Partes paralelas: nenhuma.
- Foco do verificador: regra de Q-15 aplicada em um indicador e esquecida em outro; divisão por zero em dias sem UH disponível; leitura de tabelas internas.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E10 — "Indicadores e relatório flash" do roadmap docs/roadmaps/roadmap-m10-comercial-gestao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a hipótese H3 —, a seção E10 inteira, o "Evidências e handoff" de E08, RF-REL-02, RF-REL-03 e RF-REL-07 em docs/SPEC.md §5.23, Q-15 em §10 e §8.2.9. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E08 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: indicadores ocupação, ADR, RevPAR, TRevPAR e permanência média por dia, mês e ano com comparação ao período anterior, e o flash (vendidas, cortesias, OOO, ocupação, ADR, RevPAR, receitas por departamento, no-shows e cancelamentos) para dia, mês e ano, com exportação CSV. Fórmulas centralizadas com a regra de Q-15 explícita; reporting lê só views report_* e as estatísticas diárias.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E10 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume os campos de DailyStatistics, as views report_* e o padrão de endpoints e CSV de reporting (handoff de M6);
- escreva os testes com dados semeados primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em Q-15 aplicada de forma inconsistente, divisão por zero e leitura de tabelas internas.

Critérios de aceite (detalhes na seção E10):
- cada indicador confere com cálculo manual, incluindo cortesia, uso da casa e OOO;
- flash para dia, mês e ano com comparação; CSV disponível;
- dia sem UH disponível não gera erro;
- build, testes e verificação do contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E10 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Ocupação, ADR, RevPAR, TRevPAR e permanência média conferem com cálculo manual num teste com dados semeados que inclui cortesia, uso da casa e OOO.
- [ ] A regra de Q-15 está numa única função usada por todos os indicadores.
- [ ] Comparação com o período anterior para dia, mês e ano.
- [ ] Flash com todos os campos de RF-REL-03, incluindo receita de eventos.
- [ ] Período sem UH disponível devolve indicadores nulos ou zero sem erro.
- [ ] Exportação CSV; `reporting` não lê tabelas internas.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- A gerência pode querer outra regra de Q-15: regra centralizada e documentada no `CLAUDE.md` para troca barata.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E11 — Produção e relatórios financeiros — #pendente

**Objetivo:** disponibilizar a produção por canal, origem, segmento e plano, as reservas já registradas (on-the-books) e o ritmo de reservas (pick-up) (RF-REL-04), e os relatórios financeiros: razão de hóspedes, de depósitos e de contas a receber, fechamento de caixas e balancete diário por código de lançamento (RF-REL-05), com exportação CSV.

**Por que agora:** usa as views de `reservations`, `billing` e o razão de contas a receber de E01.

**Dependências e paralelismo:** E01. Pode correr em paralelo com E10 (worktrees separados); conflito em `reporting`.

**Escopo:**

- Views `report_*` necessárias em `reservations` (produção, on-the-books, pick-up por data de criação) e `billing` (razões, caixas, balancete), sem expor tabelas internas.
- Endpoints em `reporting` filtráveis por período; on-the-books e pick-up por data de referência.
- Balancete diário: saldo anterior + lançamentos − pagamentos = saldo final por código e por razão, fechando com o total.
- Exportação CSV.

**Fora de escopo:** exportação contábil (E12).

**Passos de implementação:**

1. Testes com dados semeados que conferem cada relatório; teste de fechamento do balancete (soma dos razões = total).
2. Implementar views e endpoints; regenerar contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "quais views `report_*` `reservations` e `billing` já publicam e quais campos faltam para RF-REL-04 e RF-REL-05 (handoffs de M3, M4 e M6)?".
- Partes paralelas: nenhuma.
- Foco do verificador: balancete que não fecha; pick-up calculado pela data da estadia em vez da data de criação; dados pessoais em relatórios além do necessário.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E11 — "Produção e relatórios financeiros" do roadmap docs/roadmaps/roadmap-m10-comercial-gestao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E11 inteira, o "Evidências e handoff" de E01, RF-REL-04, RF-REL-05 e RF-REL-07 em docs/SPEC.md §5.23 e docs/ARCHITECTURE.md §11 (leitura para relatórios). Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: views report_* em reservations e billing e endpoints em reporting para produção por canal, origem, segmento e plano, on-the-books, pick-up por data de criação, razões de hóspedes, depósitos e contas a receber, fechamento de caixas e balancete diário por código de lançamento que fecha com o total, com exportação CSV. reporting lê só views report_*. Não implemente a exportação contábil.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E11 para #em-andamento no índice e no título;
- investigação: um subagente Explore levanta as views report_* existentes em reservations e billing e os campos que faltam (handoffs de M3, M4 e M6);
- escreva os testes com dados semeados primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em balancete que não fecha, pick-up pela data errada e dados pessoais desnecessários.

Critérios de aceite (detalhes na seção E11):
- cada relatório confere com cálculo manual em teste de integração;
- balancete: saldo anterior + lançamentos − pagamentos = saldo final, e a soma dos razões fecha com o total;
- pick-up usa a data de criação da reserva; CSV disponível;
- build, testes e verificação do contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E11 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Produção por canal, origem, segmento e plano, on-the-books e pick-up conferem com cálculo manual; pick-up usa a data de criação da reserva.
- [ ] Razões de hóspedes, depósitos e contas a receber e fechamento de caixas conferem com cálculo manual.
- [ ] Balancete diário: para cada código, saldo anterior + lançamentos − pagamentos = saldo final; a soma dos razões fecha com o total (teste).
- [ ] Exportação CSV de cada relatório; `reporting` não lê tabelas internas.
- [ ] Relatórios não expõem documento nem contato de hóspedes.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Segmento de mercado pode não existir nas reservas de M4: listar a lacuna no handoff e agrupar como "não informado".

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E12 — Exportação contábil — #bloqueada

**Objetivo:** gerar a exportação para a contabilidade (receitas, notas, recebimentos) por período, no formato definido com o contador (RF-REL-08).

**Por que agora:** completa os relatórios gerenciais; depende dos dados de E10 e E11.

**Dependências e paralelismo:** E10, E11. Não paralelizável.

**Escopo:**

- Exportação por período a partir de views `report_*` de `billing`, `fiscal` e `payments`, no layout de D1.
- Mapeamento de códigos de lançamento e formas de pagamento para contas contábeis, configurável.
- Arquivo gerado com totais de controle que conferem com o balancete de E11.

**Fora de escopo:** integração online com sistema contábil; financeiro administrativo (Q-01).

**Passos de implementação:**

1. Registrar D1 (layout, sistema de destino, plano de contas) e obter um arquivo de exemplo aceito pelo contador.
2. Testes que geram o arquivo para dados semeados e comparam com o exemplo esperado; totais de controle conferem com o balancete.
3. Implementar mapeamento configurável e geração.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma além do layout registrado em D1.
- Partes paralelas: nenhuma.
- Foco do verificador: totais que não batem com o balancete; código de lançamento sem mapeamento exportado em silêncio; dados pessoais desnecessários no arquivo.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E12 — "Exportação contábil" do roadmap docs/roadmaps/roadmap-m10-comercial-gestao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a decisão D1 —, a seção E12 inteira, o "Evidências e handoff" de E10 e E11 e RF-REL-08 em docs/SPEC.md §5.23. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E12 não está #bloqueada (D1 registrada com layout, sistema de destino e plano de contas) e que E10 e E11 estão #concluida; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: exportação contábil por período (receitas, notas, recebimentos) no layout registrado em D1, a partir de views report_* de billing, fiscal e payments, com mapeamento configurável de códigos de lançamento e formas de pagamento para contas contábeis e totais de controle que conferem com o balancete de E11. Não implemente integração online nem financeiro administrativo.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E12 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva primeiro o teste que gera o arquivo para dados semeados e compara com o exemplo aceito pelo contador, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em totais divergentes do balancete, código sem mapeamento exportado em silêncio e dados pessoais desnecessários.

Critérios de aceite (detalhes na seção E12):
- arquivo gerado para dados semeados é igual ao exemplo esperado;
- totais de controle conferem com o balancete;
- código sem mapeamento → erro com code estável listando os códigos;
- build, testes e verificação do contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E12 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] O arquivo gerado para os dados semeados é igual ao exemplo aceito pelo contador (teste de comparação).
- [ ] Os totais de controle do arquivo conferem com o balancete de E11 no mesmo período.
- [ ] Código de lançamento ou forma de pagamento sem mapeamento → erro com `code` estável listando os itens, nunca exportação parcial silenciosa.
- [ ] O arquivo não contém documento nem contato de hóspedes além do exigido pelo layout.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Bloqueios:**

- **Causa:** o formato da exportação contábil não está definido: a SPEC diz "formato a definir com o contador" e não há layout, sistema de destino nem plano de contas.
- **Evidência:** `docs/SPEC.md` RF-REL-08 (§5.23); Q-01 (§10) mantém o financeiro administrativo fora da v1, sem definir o layout.
- **Impacto:** impede implementar e testar a exportação; o gate E14 depende desta etapa.
- **Ação de desbloqueio:** o usuário define com o contador o layout (ou o sistema contábil e seu formato de importação), o plano de contas e fornece um arquivo de exemplo aceito; registrar a decisão em D1 e trocar a tag para `#pendente` (índice e título). Se a decisão for não exportar na v1, registrar em D1 e remover a etapa no "Histórico de revisões".

**Riscos e mitigação:**

- Layout proprietário do sistema contábil pode mudar: geração isolada num adaptador por layout.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E13 — Retenção LGPD automática — #pendente

**Objetivo:** automatizar a retenção LGPD (RN-19, RN-20, RF-HOS-07): um job semanal anonimiza perfis de hóspedes inativos após o prazo configurado e expurga os dados sensíveis da FNRH após o prazo pós-checkout, preservando contas e documentos fiscais por 5 anos e auditando cada execução.

**Por que agora:** é a última obrigação legal da v1 sem automação; independente das demais etapas do marco.

**Dependências e paralelismo:** nenhuma dentro deste roadmap; exige o gate de M9 `#concluida`. Pode correr em paralelo com E01, E03 e E09 (worktrees separados).

**Escopo:**

- Prazos configuráveis (H4): expurgo dos sensíveis da FNRH (padrão 30 dias após o check-out) e anonimização de perfis (padrão 5 anos após a última estadia).
- Data da última estadia via porta em `guests` implementada por `reservations` (H5), ou o dado já existente.
- Anonimização em `guests`: substitui nome, documento, contato, endereço, preferências e restrições por valores irreversíveis; remove anexos em `files`; mantém o ID para que contas e notas continuem referenciando; reutiliza a anonimização manual de M3, se existir.
- Expurgo dos sensíveis da FNRH no módulo dono (H6), incluindo a chave de cifragem por registro, se houver.
- Job semanal com ShedLock (ARCHITECTURE §13), idempotente e retomável; registro de auditoria por execução com contagens, sem dados pessoais.
- Modo simulação (lista o que seria afetado) com permissão própria.

**Fora de escopo:** exportação dos dados do titular (entregue em M3, se aplicável).

**Passos de implementação:**

1. Testes: perfil vencido é anonimizado e as contas/notas continuam íntegras; perfil com estadia recente ou futura não é tocado; sensíveis da FNRH expurgados após o prazo; reexecução não altera nada; simulação não escreve.
2. Implementar porta, job e auditoria.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "onde ficam e como são cifrados os dados sensíveis da FNRH e os anexos de hóspedes, e se já existe anonimização manual (handoffs de M3 e M5)?"; "o que RN-19, RN-20, RF-HOS-07 e a ARCHITECTURE §8.3 exigem?".
- Partes paralelas: nenhuma.
- Foco do verificador: anonimização de hóspede com reserva futura ou estadia recente; registro fiscal alterado; dado pessoal sobrando (anexo, log, auditoria, view `report_*`); `guests` dependendo de `reservations`.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E13 — "Retenção LGPD automática" do roadmap docs/roadmaps/roadmap-m10-comercial-gestao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e as hipóteses H4, H5 e H6 —, a seção E13 inteira, RN-19 e RN-20 em docs/SPEC.md §6, RF-HOS-07 em §5.5, Q-07 em §10 e docs/ARCHITECTURE.md §8.3 e §13. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que a última etapa (gate) de docs/roadmaps/roadmap-m9-suprimentos.md está #concluida; se não estiver, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: job semanal com ShedLock, idempotente e auditado sem dados pessoais, que anonimiza perfis de hóspedes inativos após o prazo configurado (padrão 5 anos da última estadia, obtida por porta em guests implementada por reservations) e expurga os dados sensíveis da FNRH após o prazo pós-checkout (padrão 30 dias), preservando contas e documentos fiscais; inclui modo simulação. guests não pode depender de reservations.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E13 para #em-andamento no índice e no título;
- investigação: um subagente Explore localiza os dados sensíveis da FNRH, os anexos e a anonimização manual existente (handoffs de M3 e M5) e resume RN-19, RN-20, RF-HOS-07 e ARCHITECTURE §8.3;
- escreva os testes primeiro, implemente e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em anonimização indevida, registro fiscal alterado, dado pessoal remanescente e dependência guests → reservations.

Critérios de aceite (detalhes na seção E13):
- perfil vencido anonimizado com anexos removidos e contas e notas íntegras;
- perfil com estadia recente ou reserva futura intocado; sensíveis da FNRH expurgados após o prazo;
- reexecução não altera nada; simulação não escreve; auditoria sem dados pessoais;
- ModularityTest, build, testes e verificação do contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E13 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Perfil com última estadia além do prazo é anonimizado: nome, documento, contato, endereço, preferências e restrições substituídos de forma irreversível e anexos removidos de `files`.
- [ ] Contas, lançamentos e documentos fiscais do perfil anonimizado continuam íntegros e consultáveis (teste).
- [ ] Perfil com estadia dentro do prazo ou reserva futura não é alterado.
- [ ] Dados sensíveis da FNRH são expurgados após o prazo pós-checkout e não antes.
- [ ] Reexecutar o job não altera nada (idempotência); o job não roda em paralelo (ShedLock).
- [ ] Modo simulação lista os afetados sem escrever.
- [ ] Cada execução gera registro de auditoria com contagens e sem dados pessoais.
- [ ] `guests` não depende de `reservations` (`ModularityTest`); prazos configuráveis.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Operação destrutiva e irreversível: modo simulação, revisão humana, e prazos padrão conservadores (H4) até o usuário confirmar Q-07.

**Rollback / migração:** a anonimização é irreversível por desenho; o único rollback é restaurar backup (M8). Rodar a primeira execução real em produção só após a simulação revisada pelo usuário.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E14 — Gate do marco M10 e da v1 — #pendente

**Objetivo:** confirmar, a partir de uma cópia limpa do repositório, que a definição global de pronto do M10 é atendida e que a v1 do servidor está completa em relação à SPEC §9 (Fases 0 a 4), com testes ponta a ponta dos fluxos comerciais.

**Por que agora:** fecha o último marco e a v1.

**Dependências e paralelismo:** E02, E04, E09, E12, E13 (que dependem transitivamente de todas as demais). Não paralelizável.

**Escopo:**

- Testes ponta a ponta (de integração): evento completo (proposta → contrato com parcela paga → bloqueio → BEO → realizado → faturado a prazo → recebimento) e check-out com transferência para contas a receber.
- Build, testes, lint, verificação do contrato e oasdiff numa cópia limpa.
- Conferência de cada item da "Definição global de pronto" e da cobertura dos requisitos da SPEC §9 pelos gates de M0 a M10 (tabela fase → marcos → gate concluído).
- ARCHITECTURE §3.1 atualizada com o grafo final (todas as arestas acrescentadas pela regra por camadas em M0–M10) e `ApplicationModules.verify()` coerente com ela.
- `CLAUDE.md` atualizado (módulo `events`, contas a receber, retenção LGPD) e conclusão do M10 e da v1 registrada em `docs/roadmaps/README.md`.

**Fora de escopo:** funcionalidade nova. Problemas voltam à etapa de origem (reabrir com `#em-andamento` e registrar no handoff dela), salvo ajustes triviais de documentação.

**Passos de implementação:**

1. Escrever e rodar os testes ponta a ponta.
2. Rodar tudo do zero numa cópia limpa e marcar cada item da definição de pronto com evidência.
3. Montar a tabela de cobertura da SPEC §9 a partir dos gates; listar pendências (ex.: D1) com a decisão do usuário.
4. Atualizar ARCHITECTURE §3.1, `CLAUDE.md` e o histórico do README dos marcos.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "os gates de M0 a M9 estão `#concluida` e que pendências registraram nos handoffs?".
- Partes paralelas: nenhuma.
- Foco do verificador: item da definição de pronto sem evidência; teste ponta a ponta com atalhos (escrita direta em tabelas); ARCHITECTURE §3.1 divergente do `ModularityTest`; pendência de marco anterior esquecida.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E14 — "Gate do marco M10 e da v1" do roadmap docs/roadmaps/roadmap-m10-comercial-gestao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a "Definição global de pronto" —, a seção E14 inteira, o "Evidências e handoff" de todas as etapas E01 a E13 e docs/SPEC.md §9. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 a E13 estão #concluida (ou que o usuário registrou em D1 a decisão de fechar a v1 sem E12); se não, ou se o código divergiu do plano, pare e relate.

Objetivo: testes de integração ponta a ponta usando só APIs (evento completo da proposta ao recebimento a prazo; check-out com transferência para contas a receber), verificação numa cópia limpa de cada item da definição global de pronto, tabela de cobertura da SPEC §9 pelos gates de M0 a M10, ARCHITECTURE §3.1 com o grafo final coerente com o ModularityTest, CLAUDE.md atualizado e conclusão do M10 e da v1 registrada em docs/roadmaps/README.md. Não implemente funcionalidade nova: problemas voltam à etapa de origem, salvo ajustes triviais de documentação.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E14 para #em-andamento no índice e no título;
- investigação: um subagente Explore confere o estado dos gates de M0 a M9 e as pendências registradas nos handoffs;
- escreva e rode os testes ponta a ponta; rode do zero, numa cópia limpa, os comandos oficiais de build, teste e lint do CLAUDE.md, a verificação do contrato e o oasdiff;
- verificação independente com o Prompt do verificador, com foco em item sem evidência, atalhos nos testes, ARCHITECTURE divergente do ModularityTest e pendências esquecidas.

Critérios de aceite (detalhes na seção E14):
- testes ponta a ponta passam usando só APIs públicas;
- todos os itens da definição global de pronto marcados com evidência numa cópia limpa;
- tabela de cobertura da SPEC §9 completa; ARCHITECTURE §3.1 coerente com o ModularityTest;
- CLAUDE.md atualizado; README dos marcos registra M10 e a v1.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E14 (snapshot, verificações, resultados, parecer do verificador, pendências para depois da v1) e responda com um resumo curto. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Os dois testes ponta a ponta passam usando só endpoints e APIs públicas.
- [ ] Todos os itens da "Definição global de pronto" estão marcados, cada um com evidência numa cópia limpa.
- [ ] oasdiff não aponta *breaking change* em `/api/v1` em relação ao fim do M9.
- [ ] A tabela de cobertura da SPEC §9 (fase → marcos → gate concluído) está no handoff, sem lacunas não explicadas.
- [ ] ARCHITECTURE §3.1 descreve o grafo final e `ModularityTest` passa com ele.
- [ ] `CLAUDE.md` documenta `events`, contas a receber e a retenção LGPD; `docs/roadmaps/README.md` registra a conclusão do M10 e da v1.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint (CLAUDE.md) numa cópia limpa → sucesso.
- Verificação do contrato `api/openapi.yaml` e oasdiff → sucesso.
- `docker compose -f docker/compose.yaml up -d` na cópia limpa → readiness `UP`; `down` em seguida.

**Bloqueios:** Nenhum bloqueio conhecido. Depende de E12, que está `#bloqueada` até D1.

**Riscos e mitigação:**

- Pendências acumuladas nos handoffs de marcos anteriores: a investigação as lista e o usuário decide quais ficam para depois da v1.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Pendências para depois da v1: —

---

## Histórico de revisões

- 2026-10-13 — roadmap criado (antes do código existir; sincronizar antes da primeira etapa).
- 2026-10-13 — aplicadas as decisões da entrevista sobre SPEC × ARCHITECTURE: regra por camadas na §3.1 (contexto, restrições e gate); H2 decidida (`events` → `printing`; E05 e E07 geram os PDFs sem pedir aprovação); H8 sem a justificativa de proibição (BEO segue por checklists, sem push, porque a SPEC não pede); critérios "events não depende de housekeeping, maintenance, notifications, fiscal, inventory" trocados por verificações de desenho (nota só pelo fluxo de `billing`, baixa só pelas fichas técnicas de `pos`).
