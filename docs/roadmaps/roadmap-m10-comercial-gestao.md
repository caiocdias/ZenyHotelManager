# Roadmap — M10 Comercial e gestão: eventos, contas a receber, preventiva, relatórios gerenciais e LGPD

> Arquivo: `docs/roadmaps/roadmap-m10-comercial-gestao.md` · branch de execução: `roadmap/m10-comercial-gestao` · criado em 2026-10-13 · última revisão 2026-10-22
>
> Marco 10 de 11 (último) · mapa dos marcos em [`docs/roadmaps/README.md`](README.md) · marco anterior: [M9 — Suprimentos](roadmap-m9-suprimentos.md) · não há próximo marco: o gate deste roadmap fecha a v1.
>
> **Pré-requisito:** o gate do marco M9 (última etapa de `docs/roadmaps/roadmap-m9-suprimentos.md`) está `#concluida`.
>
> **Sincronização:** este roadmap foi escrito antes do código existir. Antes da primeira etapa, sincronize-o com o código real usando a skill `development-roadmap` (modo atualização): confira caminhos, nomes de módulos, APIs públicas e convenções registrados nos handoffs de M0 a M9 e nas notas de tema do brain, e ajuste as etapas pendentes.

## Como usar

Inicie uma sessão limpa do Claude Code na raiz do repositório com `claude --agent orquestrador` e cole o "Prompt de execução". Ele executa até 3 etapas em sequência na branch `roadmap/m10-comercial-gestao`, cada uma planejada pelo plugin superpowers e executada pelas souls (subagentes do Second Brain), e para para revisão. No fim do lote você decide como integrar a branch (merge, PR, manter ou descartar). Para não parar em cada pedido de permissão, libere antes os comandos de build/teste do projeto nas permissões do Claude Code (os comandos oficiais estão na tabela do `CLAUDE.md`; o Docker é necessário para os testes de integração).

## Objetivo

Completar a gestão do hotel (SPEC §9, Fase 4) e fechar a v1. Ao fim do marco, o comercial vende e executa eventos completos e locações simples de espaços, com proposta, contrato, parcelas, BEO, bloqueio de UHs e faturamento; o financeiro opera contas a receber, inclusive a transferência de saldo no check-out; a manutenção gera OS preventivas a partir de planos por ativo ou área; a gerência consulta indicadores e relatórios gerenciais; e a retenção LGPD roda sozinha. A exportação contábil fica pronta assim que o formato for definido com o contador.

## Contexto confirmado

- Requisitos: SPEC §5.20 (EVT), §5.10 RF-CTA-13 (contas a receber) e RF-CTA-06 (pagamento faturado), §5.8 RF-REC-06 (check-out com transferência), §5.16 RF-MAN-04 e RF-MAN-05 (ativos e preventivas), §5.23 RF-REL-02 a RF-REL-05, RF-REL-07 e RF-REL-08, §5.5 RF-HOS-07 (LGPD), §5.7 RF-RES-09 (grupos e bloqueios).
- Regras de negócio: SPEC §6 RN-03 (imutabilidade financeira), RN-06 (check-out com saldo zero ou transferência a contas a receber), RN-12, RN-19 (dados sensíveis da FNRH), RN-20 (retenção e anonimização).
- Modelo de domínio: SPEC §8.2.16 (eventos e espaços), §8.2.7 (contas), §8.2.12 (`Asset`, `PreventivePlan`), §8.2.9 (`DailyStatistics`), §8.2.4 (hóspedes e empresas); eventos em §8.3 (`BusinessDateAdvanced`).
- Questões da SPEC §10: Q-01 (financeiro administrativo, fora da v1), Q-07 (prazos de retenção), Q-15 (cortesia e uso da casa nos indicadores).
- Arquitetura: ARCHITECTURE §3.1 — regra por camadas (um módulo pode depender de módulos de camada inferior da §3.2 e de qualquer módulo da Plataforma, sem ciclo; aresta nova é registrada na tabela na mesma mudança). `events` depende de `shared`, `guests`, `reservations`, `billing`, `payments`, `pos`, `checklists`, `files`, `printing`; `billing` depende de `shared`, `hotel`, `guests`, `payments`, `fiscal`; `maintenance` depende de `hotel`, `rooms`, `staff`, `checklists`, `inventory`, `files`, `notifications`; `reporting` lê só views `report_*` (§11); `guests` depende só de `shared` e `files`. ARCHITECTURE §8.3 (LGPD e cifragem), §13 (jobs: preventivas após `BusinessDateAdvanced`, retenção LGPD semanal), §11 (`EXCLUDE` com ranges, views de leitura).
- Convenções herdadas (ler nos handoffs e nas notas de tema do brain): M0 — API, erros, migrações, `Money`, IDs, `Idempotency-Key`, outbox, ShedLock; M1 — autorização, step-up, auditoria, `hotel` (`BusinessDateAdvanced`), `files`; M2 — checklists, notificações; M3 — `guests` (empresas, consentimentos, dados cifrados), `billing` (contas, lançamentos, roteamento, caixa), `payments` manual; M4 — `reservations` (grupos e bloqueios); M5 — `frontdesk` (check-out exige saldo zero), `fnrh`, `printing` (PDFs); M6 — `maintenance` corretiva, `endofday` (`DailyStatistics`), `reporting` operacional; M7 — Pix e cartão em `payments`, `fiscal`; M8 — `pos`; M9 — fichas técnicas e API de baixa por ficha em `pos`.
- Comandos de build, teste e lint: os oficiais da tabela do `CLAUDE.md`. Testes de integração com Testcontainers (base de M0 E02; nota `ZenyHotelManager - Testes de integração`).
- Conhecimento do projeto no Second Brain: hub `ZenyHotelManager` e as notas de tema `ZenyHotelManager - Estado por marco`, `ZenyHotelManager - Arquitetura e estrutura de módulos`, `ZenyHotelManager - Schemas, migrações e views de relatório`, `ZenyHotelManager - Tipos do kernel`, `ZenyHotelManager - Convenções de API`, `ZenyHotelManager - Segurança, autenticação e autorização`, `ZenyHotelManager - Auditoria`, `ZenyHotelManager - Eventos e jobs`, `ZenyHotelManager - Testes de integração`, `ZenyHotelManager - Docker, observabilidade e CI` (gate), `ZenyHotelManager - Módulos hotel e staff` (`BusinessDateAdvanced`), `ZenyHotelManager - Módulo files`, `ZenyHotelManager - Módulo checklists`, `ZenyHotelManager - Módulo notifications`, `ZenyHotelManager - Módulo guests` (empresas, LGPD, anonimização), `ZenyHotelManager - Módulo billing (contas, roteamento e taxa de serviço)`, `ZenyHotelManager - Módulo billing (caixa, pagamentos, caução e encerramento)` (inclui `payments`), `ZenyHotelManager - Módulo rates` (categoria do plano, Q-15), `ZenyHotelManager - Módulo reservations (inventário, disponibilidade e reservas)`, `ZenyHotelManager - Módulo reservations (ciclo de vida, alocação, grupos e busca)` (bloqueios, views `report_*`) e `ZenyHotelManager - Hospedagem e recepção` (check-out). As notas dos módulos criados de M5 a M9 (`printing`, `fnrh`, `housekeeping`, `maintenance`, `endofday`, `reporting`, `fiscal`, `pos`, `inventory`) entram pelas capturas de atualização desses marcos; confira no hub quais existem.
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
- Documentação: o que muda a referência técnica do projeto (módulo, API pública, convenção, estado do marco) vai na captura `ZenyHotelManager - atualização m10-comercial-gestao Eyy` do brain, com o texto novo ou alterado das notas de tema afetadas (um módulo novo, como `events`, propõe a nota `ZenyHotelManager - Módulo events`), nunca no `CLAUDE.md`; o `README.md`, a ARCHITECTURE e o `api/openapi.yaml` continuam atualizados no repositório na mesma etapa.

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

Vale para toda execução deste roadmap. O planejamento e a execução de cada etapa são feitos pelo plugin superpowers; este protocolo diz como encadear as etapas e manter o roadmap em dia.

**Papéis**

| Papel | Soul | Usa |
|---|---|---|
| Orquestrador | `orquestrador` (a sessão principal, iniciada com `claude --agent orquestrador`) | este protocolo; atualiza tags e handoffs; roda a verificação mecânica dos testes |
| Planejador | `orquestrador` | `superpowers:writing-plans` |
| Implementador | `desenvolvedor`, subagente por tarefa | `superpowers:subagent-driven-development`, prompt implementer |
| Testador | `testador`, subagente por tarefa, depois do implementador | testes da tarefa, sem tocar em código de produção |
| Revisor de plano | `revisor-arquitetura`, subagente novo por etapa, modelo mais capaz | revisão do plano antes da execução |
| Revisor de tarefa | `revisor-seguranca`, subagente novo por tarefa | revisão da `subagent-driven-development` |
| Revisor final | `revisor-seguranca`, subagente novo, modelo mais capaz | revisão final da `subagent-driven-development` |
| Captura no brain | `brain-escriba` | publica a captura de atualização em `00 Inbox/` |

Souls: este protocolo exige `orquestrador`, `desenvolvedor`, `testador`, `revisor-seguranca` e `revisor-arquitetura`. Não há fallback: se a sessão não roda como `orquestrador` ou alguma dessas souls não está instalada, pare e diga ao usuário para configurar a máquina (skill `configurar-maquina`) ou iniciar com `claude --agent orquestrador`. O caminho inline (`superpowers:executing-plans`) não vale aqui.

**Branch**

- Toda execução acontece no worktree/branch `roadmap/m10-comercial-gestao`, criado ou retomado com `superpowers:using-git-worktrees`. Nunca execute na branch principal (`main`).
- Se a branch já existir com trabalho não integrado, continue nela. Se o lote anterior já foi integrado, crie a branch de novo a partir da `main` atualizada.
- Este roadmap é atualizado dentro da branch: tags e handoffs viajam com o código.

**Por etapa**

1. **Escolha:** a primeira etapa, na ordem do documento, marcada `#pendente` e com todas as dependências `#concluida`. Etapas `#bloqueada` e as que dependem delas ficam para trás.
2. **Conferência:** leia a seção da etapa, os handoffs das dependências e o código que eles citam. Se o código divergiu do que a etapa supõe, pare e relate.
3. **Início:** troque a tag para `#em-andamento` (índice e título).
4. **Plano:** use `superpowers:writing-plans`. A spec é a seção desta etapa mais os documentos citados em "Contexto confirmado"; as "Restrições e invariantes" entram como Global Constraints. Salve em `docs/superpowers/plans/AAAA-MM-DD-m10-comercial-gestao-eNN.md` (AAAA-MM-DD = a data do dia). Antes de executar, o plano passa pela revisão de arquitetura do `orquestrador` (`revisor-arquitetura`); as regras (rodadas, veredito, seção no plano) vivem só nele. A execução já está definida (abaixo): não pergunte ao usuário o método, apenas siga.
5. **Execução:** use `superpowers:subagent-driven-development` com esse plano, executado pelas souls conforme o ciclo por tarefa, a verificação mecânica dos testes e a revisão final da etapa definidos no `orquestrador` (as regras de encaixe vivem só nele). Não invoque `superpowers:finishing-a-development-branch` ao fim da etapa: ele roda uma vez, no fim do lote.
6. **Fechamento:** publique a captura de atualização do brain (bloco "Second Brain"). Marque `#concluida` (índice e título) só com a revisão final limpa e cada critério de aceite verificado com evidência. Diante de impedimento real, marque `#bloqueada` e preencha "Bloqueios" com causa, evidência, impacto e ação de desbloqueio. Preencha "Evidências e handoff". Rulings do ledger que mudam contrato, escopo, segurança, dados ou algo caro de desfazer vão para "Decisões para o usuário".

**Second Brain**

Vale se o `CLAUDE.md` tiver a seção "Conhecimento do projeto no Second Brain"; senão, ignore este bloco.

- O contexto da sessão precisa trazer a linha `BRAIN: carregado (ZenyHotelManager)`, injetada pelo hook. Sem ela, pare e relate.
- Na conferência de cada etapa, leia as notas de tema do brain ligadas a ela (pelo hub `ZenyHotelManager`) e as capturas pendentes do projeto que tratem do assunto: são a referência técnica do projeto. Num handoff antigo, "seção X do `CLAUDE.md`" é a nota de tema correspondente.
- No fechamento de cada etapa que mudou algo descrito no brain (inclusive o estado do roadmap), o orquestrador manda o `brain-escriba` publicar em `00 Inbox/` do vault a captura `ZenyHotelManager - atualização m10-comercial-gestao Eyy`, com o texto novo ou alterado de cada nota afetada e qual nota ele substitui ou complementa; o contrato de agente externo do vault fica com o `brain-escriba`. Nunca edite notas do brain nem registre referência técnica no `CLAUDE.md`.

**Paradas**

O lote para ao concluir uma etapa com **Revisão humana: sim**, diante de etapa `#bloqueada` ou divergência do plano, nas paradas do próprio superpowers (operação destrutiva, ação sensível de segurança, efeito fora da branch, plano sem caminho viável) e ao atingir o limite de etapas do prompt.

**Fim do lote**

1. Use `superpowers:finishing-a-development-branch`: o usuário decide merge, PR, manter ou descartar a branch. Nunca faça push, merge ou deploy sem essa decisão.
2. Apresente as "Decisões para o usuário" acumuladas como entrevista: perguntas de múltipla escolha, cada uma com uma opção recomendada e justificada (no Claude Code, `AskUserQuestion` com a recomendada em primeiro lugar e o rótulo "(Recomendado)"). Registre as respostas no handoff das etapas e em "Hipóteses e decisões em aberto".

## Prompt de execução

```text
Execute o roadmap docs/roadmaps/roadmap-m10-comercial-gestao.md deste repositório seguindo a seção "Protocolo de execução" dele, com o plugin superpowers.

Limite desta execução: no máximo 3 etapas concluídas.

1. Confira a pré-condição do protocolo: esta sessão roda como orquestrador e as souls desenvolvedor, testador, revisor-seguranca e revisor-arquitetura estão disponíveis; se não, pare e diga como resolver. Leia CLAUDE.md (e AGENTS.md, se existir) e, no roadmap, o cabeçalho inteiro, o protocolo e o índice. Se o CLAUDE.md exigir o Second Brain, confira a linha BRAIN: carregado no contexto e leia o hub. Rode git status e git branch -a.
2. Se alguma etapa estiver #em-andamento, retome-a a partir do ledger do superpowers e do handoff; não recomece tarefas já commitadas.
3. Crie ou retome o worktree/branch roadmap/m10-comercial-gestao com superpowers:using-git-worktrees. Não trabalhe na branch principal.
4. Repita, uma etapa por vez: escolha a próxima etapa elegível, marque #em-andamento, planeje com superpowers:writing-plans (spec = seção da etapa + documentos citados), execute com superpowers:subagent-driven-development, feche a etapa (com a captura de atualização do brain) e preencha o handoff conforme o protocolo.
5. Pare nas paradas do protocolo ou ao atingir o limite.
6. No fim, rode superpowers:finishing-a-development-branch e deixe a decisão de integração comigo. Depois, apresente as decisões para o usuário como entrevista com opção recomendada.

Nunca faça push, merge ou deploy sem minha decisão. Não altere tags de etapas que não executou.

Ao parar, responda com: etapas concluídas e o resultado da revisão final de cada uma, a etapa atual e seu estado, o motivo da parada, as etapas #bloqueada com a ação de desbloqueio, a branch e os commits do lote, as capturas do brain publicadas e o próximo passo sugerido.
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

**Dependências:** nenhuma dentro deste roadmap; exige o gate de M9 `#concluida`. Independente de E03, E09 e E13 (sem conflito de arquivos).

**Escopo:**

- Entidades de contas a receber em `billing` (a criar; nomes conforme o modelo de M3): conta do devedor (empresa de `guests`), fatura, itens da fatura (saldos transferidos), recebimento e alocação do recebimento às faturas.
- Transferência de saldo: zera a conta de origem por um lançamento de transferência vinculado (RN-03) e cria o débito no devedor; respeita o limite de crédito da empresa (RF-HOS-06) com step-up acima do limite.
- Fatura com número legível, vencimento, estados (aberta, parcialmente paga, paga, cancelada por estorno) e PDF conforme H2 (mesmo mecanismo do extrato de M3/M5).
- Recebimento exige turno de caixa aberto (RF-CTA-10) e `Idempotency-Key`; aceita as formas de pagamento de M3/M7.
- Extrato por cliente e aging 30/60/90 por data de vencimento; views `report_*` para o razão de contas a receber (consumidas em E11).
- Permissões: transferir para contas a receber, emitir fatura, baixar recebimento.

**Fora de escopo:** contas a pagar e conciliação bancária (Q-01); cobrança automática de faturas por boleto ou Pix (não está na SPEC).

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

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Revisão humana:** sim

**Notas para o plano:**

- Leia os handoffs de M3 (`billing`: contas e `FolioOwner`, lançamentos de transferência, caixa, formas de pagamento) e de M5 (PDF do extrato pelo `printing`), e as notas `ZenyHotelManager - Módulo billing (contas, roteamento e taxa de serviço)`, `ZenyHotelManager - Módulo billing (caixa, pagamentos, caução e encerramento)` e `ZenyHotelManager - Módulo guests` (empresa e limite de crédito).
- H2: o PDF da fatura sai pelo mesmo mecanismo do extrato (M5).
- A forma "faturado" (RF-CTA-06) é o `CITY_LEDGER`, que o M3 E08 deixou recusado com `422 PAYMENT_METHOD_NOT_AVAILABLE` "até o M10"; `PaymentMethod` é `x-extensible-enum` nas respostas justamente para recebê-lo.
- Divergência a resolver na sincronização: o critério pede `409` para recebimento sem turno aberto, mas o código usa `422 CASHIER_SHIFT_REQUIRED` desde o M3 E07/E08 (código mantido pelo usuário na revisão humana do M3 E08).
- Armadilhas apontadas para a revisão: saldo transferido duas vezes; soma das alocações diferente do recebido; limite de crédito ignorado.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Interação com a nota fiscal (M7): a NFS-e da hospedagem continua emitida no check-out; a fatura não emite nota nova. Registrar a regra no handoff.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E02 — Check-out com transferência para contas a receber — #pendente

**Objetivo:** completar RN-06 e RF-REC-06: o check-out aceita, com permissão, transferir o saldo das contas da reserva para uma empresa em contas a receber, em vez de exigir saldo zero.

**Por que agora:** remove a limitação deixada no check-out de M5 e é o uso mais comum de contas a receber em hotel.

**Dependências:** E01. Independente de E03 a E07 (sem conflito de arquivos).

**Escopo:**

- Opção de check-out em `frontdesk` (`frontdesk` depende de `billing` ✓) que chama a transferência de E01 para as contas com saldo e conclui o check-out na mesma transação.
- Permissão específica para transferir no check-out; limite de crédito herdado de E01.
- Comportamento sem a opção permanece idêntico (saldo zero obrigatório).

**Fora de escopo:** mudanças na emissão fiscal do check-out.

**Critérios de aceite:**

- [ ] Check-out com saldo, opção de transferência e permissão: contas da reserva zeradas, débito criado em contas a receber e reserva encerrada, na mesma transação.
- [ ] Sem a permissão → `403`.
- [ ] Sem a opção, check-out com saldo devolve o mesmo erro e `code` de M5 (teste de regressão).
- [ ] Falha simulada na transferência deixa reserva e contas inalteradas.
- [ ] Toda a suíte de `frontdesk` passa; build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Revisão humana:** não

**Notas para o plano:**

- Leia o handoff do check-out do `frontdesk` (M5) e a nota `ZenyHotelManager - Hospedagem e recepção`: quais efeitos o check-out executa na transação e como confere a RN-06 (`BillingApi.balanceOf(owner)`, `OwnerBalance.settled`, do M3 E10).
- Armadilhas apontadas para a revisão: check-out concluído com transferência parcial; regressão no check-out sem a opção; transferência sem permissão.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Efeitos assíncronos do check-out (FNRH, governança) não devem disparar se a transação falhar: confirmar que saem do outbox só após o commit.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E03 — Espaços, equipamentos e agenda — #pendente

**Objetivo:** criar o módulo `events` com espaços locáveis (capacidade por formato de montagem, preços por hora, meio período e diária), equipamentos de evento e a agenda de espaços e equipamentos com detecção de conflito que considera montagem e desmontagem (RF-EVT-01, RF-EVT-02, RF-EVT-11).

**Por que agora:** base de locação simples (E04) e de eventos completos (E05).

**Dependências:** nenhuma dentro deste roadmap; exige o gate de M9 `#concluida`. Independente de E01, E09 e E13.

**Escopo:**

- Pacote `events` (a criar), schema e migrações próprios; `Space`, `SpaceCapacity`, `Equipment` (SPEC §8.2.16).
- Reservas de agenda (`SpaceBooking`, `EquipmentBooking`) com intervalo efetivo = início − montagem até fim + desmontagem; conflito impedido no banco (H9) e traduzido para `409` com `code` estável.
- Consulta de agenda por período e de disponibilidade de equipamento.
- Permissões do catálogo para gerenciar espaços e agenda.

**Fora de escopo:** cliente, preço e conta da locação (E04); funil do evento (E05).

**Critérios de aceite:**

- [ ] CRUD de espaços (com capacidades por montagem e preços) e equipamentos em `/api/v1`, no contrato e com testes de integração.
- [ ] Reservas de agenda que se sobrepõem, inclusive apenas no tempo de montagem ou desmontagem → `409` com `code` estável.
- [ ] Reservas encostadas (fim + desmontagem = início − montagem) são aceitas.
- [ ] Duas reservas concorrentes conflitantes: só uma é gravada (constraint no banco).
- [ ] Equipamento com quantidade limitada recusa reservas além do disponível no intervalo.
- [ ] `ModularityTest` passa; build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Revisão humana:** não

**Notas para o plano:**

- H9. Leia o handoff do M4 E10 (nota `ZenyHotelManager - Módulo reservations (ciclo de vida, alocação, grupos e busca)`): `EXCLUDE USING gist` sobre ranges com a violação `23P01` convertida em `409` pelo nome da restrição, e a trava consultiva por recurso tomada antes de gravar, sem a qual duas reservas conflitantes simultâneas podem dar deadlock (`40P01`, que viraria `500`) na verificação do `EXCLUDE`.
- Armadilhas apontadas para a revisão: conflito validado só na aplicação (corrida); montagem/desmontagem fora do cálculo; fuso horário errado nos intervalos (instantes em UTC, horário de parede no fuso do hotel).

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Quantidade de equipamentos não se resolve com `EXCLUDE` simples: usar contagem com lock curto por equipamento, como os contadores de disponibilidade (ARCHITECTURE §11), e registrar a escolha.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E04 — Locação simples de espaços — #pendente

**Objetivo:** permitir a locação simples de um espaço por data e hora para um cliente pessoa física ou jurídica, com valor calculado pela tabela do espaço, serviços extras e conta para cobrança (RF-EVT-03).

**Por que agora:** é a forma mais simples de receita de espaços e valida a integração de `events` com `guests` e `billing` antes do evento completo.

**Dependências:** E03. Independente de E05 (as duas mexem nas migrações de `events`).

**Escopo:**

- Locação vinculada a hóspede ou empresa de `guests` (`events` depende de `guests` ✓) e a uma reserva de agenda de E03.
- Preço: hora, meio período ou diária conforme a duração (regra registrada), com extras (equipamentos, serviços).
- Conta da locação aberta em `billing` com `FolioOwner` do tipo locação/evento; lançamentos de espaço e extras com origem "evento" (RF-CTA-02); pagamento pelos fluxos de M3/M7.
- Cancelamento libera a agenda; com lançamentos, exige estorno.

**Fora de escopo:** proposta e contrato (E05, E06).

**Critérios de aceite:**

- [ ] Locação para hóspede ou empresa reserva a agenda e calcula o preço pela regra de duração registrada no handoff (teste para hora, meio período e diária).
- [ ] Uma conta é aberta em `billing` e os lançamentos de espaço e extras são feitos uma única vez, com origem "evento".
- [ ] Cancelar locação sem lançamentos libera a agenda; com lançamentos → `409` orientando o estorno.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Revisão humana:** não

**Notas para o plano:**

- Leia o handoff de M3 sobre contas de donos que não são reserva: `FolioOwnerType` já tem `EVENT` e `PostingSource` já tem `EVENT` (nota `ZenyHotelManager - Módulo billing (contas, roteamento e taxa de serviço)`); confira os códigos de lançamento disponíveis para espaço e extras.
- A SPEC não define a regra de faixa de preço: a recomendação registrada em "Riscos" é o menor preço entre as combinações, registrado como decisão.
- Armadilhas apontadas para a revisão: preço calculado em ponto flutuante; lançamento duplicado; agenda não liberada no cancelamento.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Regra de faixa de preço não definida na SPEC: escolher o menor preço entre as combinações e registrar como decisão.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E05 — Evento completo e proposta versionada — #pendente

**Objetivo:** implementar o evento completo com o funil consulta → proposta → confirmado → realizado/cancelado (RF-EVT-04) e a proposta versionada com espaços, A&B por pessoa, equipamentos, serviços, hospedagem prevista, condições de pagamento, validade e PDF (RF-EVT-05).

**Por que agora:** é o núcleo comercial; contrato (E06) e BEO (E07) partem da proposta aceita.

**Dependências:** E03. Independente de E04 (as duas mexem nas migrações de `events`).

**Escopo:**

- `Event` com estados e transições (§8.2.16), cliente (hóspede ou empresa) e responsável comercial (usuário), participantes e datas.
- `Proposal` e `ProposalItem` versionados: editar uma proposta enviada cria nova versão; aceitar uma versão confirma o evento e congela os preços.
- Itens de A&B referenciam produtos do cardápio de `pos` (`events` depende de `pos` ✓); espaços e equipamentos reservam agenda provisória (E03) ao aceitar.
- PDF da proposta (H2): `events` monta os dados da versão e chama `printing` para gerar o PDF (`events` → `printing` já na ARCHITECTURE §3.1).
- Cancelamento com motivo; reabertura não prevista.

**Fora de escopo:** contrato e parcelas (E06); BEO (E07); faturamento (E08).

**Critérios de aceite:**

- [ ] Transições do evento fora do funil → `409` com `code` estável.
- [ ] Editar uma proposta já enviada cria nova versão; versões anteriores ficam imutáveis e consultáveis.
- [ ] Aceitar uma versão dentro da validade confirma o evento, cria as reservas de agenda de espaços e equipamentos e congela os preços; aceitar proposta vencida → `409`.
- [ ] Itens de A&B referenciam produtos do cardápio de `pos`.
- [ ] O PDF da proposta é gerado por `printing` com os dados da versão pedida (teste de integração).
- [ ] Toda dependência nova segue a regra por camadas, está na ARCHITECTURE §3.1 e `ModularityTest` passa.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Revisão humana:** sim

**Notas para o plano:**

- H2. Leia os handoffs de M5 (API do `printing` para gerar PDF a partir de um template e quem a chama) e de M8 (produtos e preços do cardápio em `pos`).
- Armadilhas apontadas para a revisão: PDF montado com dados de versão diferente da pedida; versão aceita alterável; aceite de proposta vencida; preço da proposta mudando após o aceite.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Hospedagem prevista na proposta é só estimativa; o bloqueio real de UHs acontece em E06.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E06 — Contrato, parcelas e bloqueio de UHs — #pendente

**Objetivo:** registrar o contrato do evento com cronograma de pagamentos (sinal e parcelas), cobrança por Pix e controle de recebimento (RF-EVT-06), e criar o bloqueio de UHs para participantes com tarifa do evento e data de corte, integrado aos grupos e bloqueios de `reservations` (RF-EVT-08, RF-RES-09).

**Por que agora:** confirma o compromisso financeiro do evento e reserva o inventário de hospedagem.

**Dependências:** E05. Independente de E07 (as duas mexem nas migrações de `events`).

**Escopo:**

- `Contract` e `PaymentScheduleItem` (§8.2.16): parcelas com vencimento e valor somando o total da proposta aceita.
- Cobrança Pix por parcela via `payments` (H7, `events` depende de `payments` ✓); confirmação marca a parcela paga e registra o pagamento na conta master do evento em `billing`; fallback de registro manual se o Pix estiver indisponível.
- Conta master do evento em `billing` criada no contrato (usada em E08).
- Bloqueio de UHs: criação pela API de grupos e bloqueios de `reservations` (`events` depende de `reservations` ✓) com quantidade por tipo e data, tarifa do evento e data de corte; a liberação automática no fechamento já existe (M4/M6).
- Parcelas vencidas listadas para cobrança.

**Fora de escopo:** faturamento a prazo do saldo (E08); rooming list além do que M4 oferece.

**Critérios de aceite:**

- [ ] Contrato com parcelas cuja soma difere do total da proposta aceita → `400` com `code` estável.
- [ ] Cobrança Pix criada por parcela; a confirmação (evento de `payments`) marca a parcela paga e registra o pagamento na conta master uma única vez, mesmo com reentrega.
- [ ] Registro manual de pagamento de parcela funciona com caixa aberto.
- [ ] O bloqueio de UHs é criado em `reservations`, com tarifa do evento e data de corte, e reduz a disponibilidade (teste de integração).
- [ ] Endpoint lista parcelas vencidas.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Revisão humana:** não

**Notas para o plano:**

- H7. Leia os handoffs de M7 (cobrança Pix, `PaymentSucceeded`) e do M4 E11 (grupos e bloqueios; nota `ZenyHotelManager - Módulo reservations (ciclo de vida, alocação, grupos e busca)`): `ReservationsApi.createBlock` existe sem permissão justamente para os eventos do M10, e a liberação do não utilizado é só do fechamento (`releaseUnused`).
- O bloqueio do M4 já abre a própria conta master (dono `GROUP`, uma por bloqueio); a conta master do evento é outra: defina no plano como as duas se relacionam e registre.
- Pendência do M4 E11 para o M10: a tarifa do grupo não aplica os pacotes do plano (sem o lançamento separado do pacote, RF-FIS-04, nem o diário do fechamento); quem fizer o lançamento de pacotes e eventos decide se o bloqueio leva pacotes.
- A correlação do pagamento usa a finalidade e o destino opacos da intenção (`PaymentPurpose` no formato módulo.nome-kebab, como `billing.folio-payment`, e `PaymentTarget`; nota `ZenyHotelManager - Módulo billing (caixa, pagamentos, caução e encerramento)`); confira no handoff do M7 como o `PaymentSucceededListener` do `billing` passou a tratar intenções assíncronas.
- Armadilhas apontadas para a revisão: pagamento de parcela registrado duas vezes; parcelas que não somam o total; bloqueio criado fora de `reservations`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- `events` escutar `PaymentSucceeded` de `payments` é permitido (`events` depende de `payments`); garantir a correlação pelo ID da cobrança.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E07 — BEO e checklists de montagem — #pendente

**Objetivo:** gerar a OS do evento (BEO) por atividade — espaço, horário, montagem, cardápio e quantidades, equipamentos e instruções por setor — distribuí-la aos setores por checklists de montagem e disponibilizá-la em PDF (RF-EVT-07).

**Por que agora:** é o documento operacional do evento e fornece as quantidades planejadas e servidas usadas na baixa de A&B (E08).

**Dependências:** E05. Independente de E06 (as duas mexem nas migrações de `events`).

**Escopo:**

- `EventFunction`, `EventOrder`, `EventMenuLine` (planejado e servido) (§8.2.16).
- Emissão do BEO a partir do evento confirmado; reemissão gera nova versão com data.
- Checklists de montagem atribuídos por setor via API de `checklists` (H8), usando modelos de contexto "evento" (RF-CHK-01).
- PDF do BEO gerado por `events` chamando `printing` (H2).
- Registro das quantidades servidas por linha de cardápio após o evento.

**Fora de escopo:** baixa de estoque e faturamento (E08); notificação push aos setores (H8).

**Critérios de aceite:**

- [ ] Emitir BEO de evento não confirmado → `409`.
- [ ] A emissão cria um checklist de montagem por setor com instruções, via `checklists`, uma única vez.
- [ ] Reemitir cria nova versão datada; a anterior continua consultável e os checklists não se duplicam (os novos itens são acrescentados ou os pendentes substituídos, conforme decisão registrada).
- [ ] Quantidades servidas registradas por linha de cardápio.
- [ ] PDF do BEO gerado por `printing` para a versão emitida.
- [ ] `ModularityTest` passa; toda aresta nova está na ARCHITECTURE §3.1.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Revisão humana:** não

**Notas para o plano:**

- H2 e H8. Leia o handoff de M2 (nota `ZenyHotelManager - Módulo checklists`): o motor só cria execuções pendentes por rotina (candidatos pela escala do setor) e a execução avulsa pede o executor; os modelos de contexto `EVENT` existem.
- Armadilha nos testes: o contexto `EVENT` é reservado ao teste do `resolveTemplate`, que desativa os modelos `EVENT` do banco compartilhado antes de rodar; os testes do BEO precisam conviver com isso (ou o teste do `resolveTemplate` muda de contexto).
- Armadilhas apontadas para a revisão: checklists duplicados na reemissão; BEO antigo sobrescrito; PDF do BEO com versão errada.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Setores sem escala no dia do evento: checklist atribuído ao setor sem executor, visível à liderança (RF-CHK-08).

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E08 — Faturamento do evento e baixa de A&B — #pendente

**Objetivo:** faturar o evento na conta master (espaços, A&B, equipamentos e serviços), emitir as notas pelo fluxo de `billing`/`fiscal`, permitir faturamento a prazo do saldo em contas a receber e dar baixa no estoque do A&B consumido pelas fichas técnicas a partir das quantidades servidas (RF-EVT-09, RF-EVT-10).

**Por que agora:** fecha o ciclo financeiro e de custos do evento; alimenta a receita de eventos nos indicadores (E10).

**Dependências:** E01, E06, E07.

**Escopo:**

- Encerramento do evento (realizado): lançamentos na conta master por código de lançamento de espaço, A&B e equipamentos, com origem "evento", idempotentes.
- Emissão fiscal pelo mecanismo que `billing` já usa (M7), por desenho: uma única via de emissão evita nota duplicada. A regra por camadas permitiria `events` → `fiscal`, mas não é necessário.
- Faturamento a prazo: transferência do saldo da conta master para a empresa em contas a receber (E01).
- Baixa de A&B: chamada à API de baixa por ficha técnica de `pos` (M9 E12) com as quantidades servidas de E07, idempotente por (evento, linha).
- Receita de eventos identificável por departamento nas views usadas por `endofday`/`reporting`.

**Fora de escopo:** indicadores (E10).

**Critérios de aceite:**

- [ ] Marcar o evento como realizado lança espaços, A&B e equipamentos na conta master uma única vez (repetir não duplica).
- [ ] O saldo da conta master considera as parcelas já pagas em E06.
- [ ] A emissão fiscal acontece só pelo fluxo de `billing` (nenhuma nota emitida diretamente por `events`, verificado em teste).
- [ ] O saldo pode ser transferido para a empresa em contas a receber.
- [ ] A baixa de estoque usa as quantidades servidas e as fichas técnicas, uma única vez por linha.
- [ ] A receita do evento aparece com o departamento "eventos" nas views de leitura.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Revisão humana:** não

**Notas para o plano:**

- Leia os handoffs de M7 (como o `billing` dispara a emissão fiscal; decisões Q-02 a Q-04) e do M9 E12 (API de baixa por ficha técnica de `pos`).
- `RevenueGroup` é uma lista fixa (um grupo novo exige migração; nota `ZenyHotelManager - Módulo billing (contas, roteamento e taxa de serviço)`): o critério do departamento "eventos" nas views depende de como o M6 agrupou as receitas.
- Armadilhas apontadas para a revisão: lançamento ou baixa duplicados no reencerramento; nota emitida fora do fluxo de `billing` ou baixa fora das fichas técnicas de `pos`; parcelas já pagas (E06) não abatidas do saldo.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Regras fiscais de evento (NFS-e de locação × NFC-e de A&B) dependem das decisões de M7 (Q-02 a Q-04): seguir o que M7 registrou e listar divergências no handoff.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E09 — Ativos e manutenção preventiva — #pendente

**Objetivo:** cadastrar ativos (local, categoria, fabricante, modelo, nº de série, garantia, documentos) e planos preventivos por ativo ou área, com frequência e checklist, que geram OS automaticamente após `BusinessDateAdvanced` e alertam vencimentos (RF-MAN-04, RF-MAN-05).

**Por que agora:** completa a manutenção iniciada em M6 e é independente do resto do marco.

**Dependências:** nenhuma dentro deste roadmap; exige o gate de M9 `#concluida`. Independente de E01, E03 e E13.

**Escopo:**

- `Asset` e `PreventivePlan` em `maintenance` (§8.2.12); documentos do ativo em `files` (`maintenance` depende de `files` ✓).
- Listener de `BusinessDateAdvanced` (`maintenance` depende de `hotel` ✓) que gera as OS vencidas na data operacional, com o checklist do plano (`checklists` ✓), de forma idempotente por (plano, data de vencimento).
- Alerta de vencimento próximo e de garantia vencendo via `notifications` (`maintenance` depende de `notifications` ✓).
- Histórico de OS por ativo.

**Fora de escopo:** contratos de manutenção com terceiros e custos de contrato.

**Critérios de aceite:**

- [ ] CRUD de ativos com documentos anexados via `files` e histórico de OS por ativo.
- [ ] `BusinessDateAdvanced` gera as OS preventivas vencidas na data operacional, com o checklist do plano; reentregar o evento não duplica (teste de módulo).
- [ ] Se a data operacional avançar mais de um dia, todas as OS vencidas no intervalo são geradas.
- [ ] Plano inativo não gera OS.
- [ ] Vencimento próximo e garantia vencendo geram notificação.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Revisão humana:** não

**Notas para o plano:**

- Leia os handoffs de M1 (`BusinessDateAdvanced`; nota `ZenyHotelManager - Módulos hotel e staff`) e de M6 (como `maintenance` cria OS). `advanceBusinessDate` avança exatamente um dia por chamada: um atraso de vários dias chega como vários eventos, entregues de forma assíncrona e possivelmente reentregues.
- Padrão de listener idempotente já usado: o `ChecklistRoutineListener` (M2 E07), que grava com `INSERT … ON CONFLICT DO NOTHING` sobre uma chave única (nota `ZenyHotelManager - Eventos e jobs`).
- Nos testes, as datas partem da `HotelApi.currentBusinessDate()`: outros testes avançam a data operacional do banco compartilhado.
- Armadilhas apontadas para a revisão: OS duplicada na reentrega do evento; data operacional confundida com data do relógio; vencimentos pulados quando o fechamento atrasa mais de um dia.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Planos por área podem colidir com a faxina programada da governança (RF-GOV-10): manter separados (preventiva é manutenção) e registrar.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E10 — Indicadores e relatório flash — #pendente

**Objetivo:** disponibilizar os indicadores ocupação, ADR, RevPAR, TRevPAR e permanência média por dia, mês e ano com comparação ao período anterior (RF-REL-02) e o relatório gerencial diário (flash) para dia, mês e ano (RF-REL-03), com exportação CSV.

**Por que agora:** depende das estatísticas diárias de M6 e da receita de eventos (E08).

**Dependências:** E08. Independente de E11 (as duas mexem em `reporting`).

**Escopo:**

- Fórmulas centralizadas em `reporting`, com a regra de Q-15 (H3) explícita: cortesia conta na ocupação e não no ADR; uso da casa não conta em nenhum; OOO fora do denominador de UHs disponíveis (conforme `DailyStatistics`).
- Leitura só de views `report_*` e das estatísticas diárias de `endofday`.
- Flash: UHs vendidas, cortesias, OOO, ocupação, ADR, RevPAR, receitas por departamento (hospedagem, A&B, eventos, outras), no-shows e cancelamentos, para dia, mês até a data e ano até a data, com comparação.
- Exportação CSV; o PDF do flash arquivado no fechamento segue o mecanismo de M6, se existir.

**Fora de escopo:** produção por canal e relatórios financeiros (E11).

**Critérios de aceite:**

- [ ] Ocupação, ADR, RevPAR, TRevPAR e permanência média conferem com cálculo manual num teste com dados semeados que inclui cortesia, uso da casa e OOO.
- [ ] A regra de Q-15 está numa única função usada por todos os indicadores.
- [ ] Comparação com o período anterior para dia, mês e ano.
- [ ] Flash com todos os campos de RF-REL-03, incluindo receita de eventos.
- [ ] Período sem UH disponível devolve indicadores nulos ou zero sem erro.
- [ ] Exportação CSV; `reporting` não lê tabelas internas.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Revisão humana:** não

**Notas para o plano:**

- H3. Leia o handoff de M6 (campos de `DailyStatistics`, views `report_*` e padrão de endpoints e CSV do `reporting`). A regra de Q-15 já está publicada por noite em `reservations.report_room_nights` (`counts_in_occupancy`, `counts_in_adr`, M4 E12) e a categoria do plano é fixa desde a criação (nota `ZenyHotelManager - Módulo rates`).
- A troca barata da regra de Q-15 (ver "Riscos") é documentada na captura de atualização do brain, na nota de tema do `reporting`.
- Armadilhas apontadas para a revisão: regra de Q-15 aplicada num indicador e esquecida noutro; divisão por zero em dias sem UH disponível; leitura de tabelas internas.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- A gerência pode querer outra regra de Q-15: regra centralizada e documentada (captura de atualização do brain, nota de tema do `reporting`) para troca barata.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E11 — Produção e relatórios financeiros — #pendente

**Objetivo:** disponibilizar a produção por canal, origem, segmento e plano, as reservas já registradas (on-the-books) e o ritmo de reservas (pick-up) (RF-REL-04), e os relatórios financeiros: razão de hóspedes, de depósitos e de contas a receber, fechamento de caixas e balancete diário por código de lançamento (RF-REL-05), com exportação CSV.

**Por que agora:** usa as views de `reservations`, `billing` e o razão de contas a receber de E01.

**Dependências:** E01. Independente de E10 (as duas mexem em `reporting`).

**Escopo:**

- Views `report_*` necessárias em `reservations` (produção, on-the-books, pick-up por data de criação) e `billing` (razões, caixas, balancete), sem expor tabelas internas.
- Endpoints em `reporting` filtráveis por período; on-the-books e pick-up por data de referência.
- Balancete diário: saldo anterior + lançamentos − pagamentos = saldo final por código e por razão, fechando com o total.
- Exportação CSV.

**Fora de escopo:** exportação contábil (E12).

**Critérios de aceite:**

- [ ] Produção por canal, origem, segmento e plano, on-the-books e pick-up conferem com cálculo manual; pick-up usa a data de criação da reserva.
- [ ] Razões de hóspedes, depósitos e contas a receber e fechamento de caixas conferem com cálculo manual.
- [ ] Balancete diário: para cada código, saldo anterior + lançamentos − pagamentos = saldo final; a soma dos razões fecha com o total (teste).
- [ ] Exportação CSV de cada relatório; `reporting` não lê tabelas internas.
- [ ] Relatórios não expõem documento nem contato de hóspedes.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Revisão humana:** não

**Notas para o plano:**

- Leia os handoffs de M3, M4 e M6 e a nota `ZenyHotelManager - Schemas, migrações e views de relatório`: views já publicadas (`billing.report_folios`, `report_postings`, `report_payments`, `report_cashier_shifts`, `report_cashier_counts`; `reservations.report_reservations`, `report_room_nights` e as demais do M4 E12); o razão de depósitos é `report_payments` filtrada por `deposit` (decisão do M3 E10); coluna publicada não muda de sentido (mudança incompatível cria `report_x_v2`); a prova "só as views" é um teste com um papel que só lê as views.
- O segmento de mercado existe nas reservas desde o M4 E07 (`MarketSegment`); confira na sincronização se o risco de "segmento ausente" ainda vale.
- Armadilhas apontadas para a revisão: balancete que não fecha; pick-up calculado pela data da estadia em vez da data de criação; dados pessoais além do necessário.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Segmento de mercado pode não existir nas reservas de M4: listar a lacuna no handoff e agrupar como "não informado".

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E12 — Exportação contábil — #bloqueada

**Objetivo:** gerar a exportação para a contabilidade (receitas, notas, recebimentos) por período, no formato definido com o contador (RF-REL-08).

**Por que agora:** completa os relatórios gerenciais; depende dos dados de E10 e E11.

**Dependências:** E10, E11.

**Escopo:**

- Exportação por período a partir de views `report_*` de `billing`, `fiscal` e `payments`, no layout de D1.
- Mapeamento de códigos de lançamento e formas de pagamento para contas contábeis, configurável.
- Arquivo gerado com totais de controle que conferem com o balancete de E11.

**Fora de escopo:** integração online com sistema contábil; financeiro administrativo (Q-01).

**Critérios de aceite:**

- [ ] O arquivo gerado para os dados semeados é igual ao exemplo aceito pelo contador (teste de comparação).
- [ ] Os totais de controle do arquivo conferem com o balancete de E11 no mesmo período.
- [ ] Código de lançamento ou forma de pagamento sem mapeamento → erro com `code` estável listando os itens, nunca exportação parcial silenciosa.
- [ ] O arquivo não contém documento nem contato de hóspedes além do exigido pelo layout.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Revisão humana:** não

**Notas para o plano:**

- Depende de D1: o teste compara o arquivo gerado com o exemplo aceito pelo contador, registrado junto da decisão.
- Armadilhas apontadas para a revisão: totais que não batem com o balancete; código de lançamento sem mapeamento exportado em silêncio; dados pessoais desnecessários no arquivo.

**Bloqueios:**

- **Causa:** o formato da exportação contábil não está definido: a SPEC diz "formato a definir com o contador" e não há layout, sistema de destino nem plano de contas.
- **Evidência:** `docs/SPEC.md` RF-REL-08 (§5.23); Q-01 (§10) mantém o financeiro administrativo fora da v1, sem definir o layout.
- **Impacto:** impede implementar e testar a exportação; o gate E14 depende desta etapa.
- **Ação de desbloqueio:** o usuário define com o contador o layout (ou o sistema contábil e seu formato de importação), o plano de contas e fornece um arquivo de exemplo aceito; registrar a decisão em D1 e trocar a tag para `#pendente` (índice e título). Se a decisão for não exportar na v1, registrar em D1 e remover a etapa no "Histórico de revisões".

**Riscos e mitigação:**

- Layout proprietário do sistema contábil pode mudar: geração isolada num adaptador por layout.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E13 — Retenção LGPD automática — #pendente

**Objetivo:** automatizar a retenção LGPD (RN-19, RN-20, RF-HOS-07): um job semanal anonimiza perfis de hóspedes inativos após o prazo configurado e expurga os dados sensíveis da FNRH após o prazo pós-checkout, preservando contas e documentos fiscais por 5 anos e auditando cada execução.

**Por que agora:** é a última obrigação legal da v1 sem automação; independente das demais etapas do marco.

**Dependências:** nenhuma dentro deste roadmap; exige o gate de M9 `#concluida`. Independente de E01, E03 e E09.

**Escopo:**

- Prazos configuráveis (H4): expurgo dos sensíveis da FNRH (padrão 30 dias após o check-out) e anonimização de perfis (padrão 5 anos após a última estadia).
- Data da última estadia via porta em `guests` implementada por `reservations` (H5), ou o dado já existente.
- Anonimização em `guests`: substitui nome, documento, contato, endereço, preferências e restrições por valores irreversíveis; remove anexos em `files`; mantém o ID para que contas e notas continuem referenciando; reutiliza a anonimização manual de M3, se existir.
- Expurgo dos sensíveis da FNRH no módulo dono (H6), incluindo a chave de cifragem por registro, se houver.
- Job semanal com ShedLock (ARCHITECTURE §13), idempotente e retomável; registro de auditoria por execução com contagens, sem dados pessoais.
- Pendência do M1 E04 (decidida pelo usuário em 2026-10-14): decidir a retenção da própria trilha de auditoria (`audit.audit_entry`), que cresce sem limite (todo refresh de token gera uma entrada). O papel da aplicação não apaga a trilha (H4): um expurgo dela precisa rodar com o dono dos schemas.
- Modo simulação (lista o que seria afetado) com permissão própria.

**Fora de escopo:** exportação dos dados do titular (entregue em M3, se aplicável).

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

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Revisão humana:** sim

**Notas para o plano:**

- H4, H5 e H6. Leia os handoffs de M3 E03 e de M5 e a nota `ZenyHotelManager - Módulo guests`: a anonimização manual já existe (`POST /api/v1/guests/{id}/anonymize`, ação sensível, com a cadeia de mesclagens e a porta `GuestActiveStayCheck` implementada por `reservations`); os dados sensíveis da FNRH (RN-19) estão hoje cifrados em `guests` (`sensitive_data`), e a nota registra que a anonimização por prazo e o expurgo deles são do M10. Confira H6 contra o que o `fnrh` do M5 guardou.
- A anonimização apaga anexos pela `FilesApi.delete` (objeto removido só depois do commit) e nunca recusa a revogação de consentimento; a trilha não guarda dado pessoal (H10 do M3).
- Pendência do M1 E04 (no escopo): o papel da aplicação não apaga a trilha de auditoria; um expurgo dela precisa do dono dos schemas.
- Armadilhas apontadas para a revisão: anonimização de hóspede com reserva futura ou estadia recente; registro fiscal alterado; dado pessoal sobrando (anexo, log, auditoria, view `report_*`); `guests` dependendo de `reservations`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Operação destrutiva e irreversível: modo simulação, revisão humana, e prazos padrão conservadores (H4) até o usuário confirmar Q-07.

**Rollback / migração:** a anonimização é irreversível por desenho; o único rollback é restaurar backup (M8). Rodar a primeira execução real em produção só após a simulação revisada pelo usuário.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E14 — Gate do marco M10 e da v1 — #pendente

**Objetivo:** confirmar, a partir de uma cópia limpa do repositório, que a definição global de pronto do M10 é atendida e que a v1 do servidor está completa em relação à SPEC §9 (Fases 0 a 4), com testes ponta a ponta dos fluxos comerciais.

**Por que agora:** fecha o último marco e a v1.

**Dependências:** E02, E04, E09, E12, E13 (que dependem transitivamente de todas as demais).

**Escopo:**

- Testes ponta a ponta (de integração): evento completo (proposta → contrato com parcela paga → bloqueio → BEO → realizado → faturado a prazo → recebimento) e check-out com transferência para contas a receber.
- Build, testes, lint, verificação do contrato e oasdiff numa cópia limpa.
- Conferência de cada item da "Definição global de pronto" e da cobertura dos requisitos da SPEC §9 pelos gates de M0 a M10 (tabela fase → marcos → gate concluído).
- ARCHITECTURE §3.1 atualizada com o grafo final (todas as arestas acrescentadas pela regra por camadas em M0–M10) e `ApplicationModules.verify()` coerente com ela.
- Captura de atualização do brain publicada (módulo `events`, contas a receber, retenção LGPD e o estado final do M10 e da v1) e conclusão do M10 e da v1 registrada em `docs/roadmaps/README.md`.

**Fora de escopo:** funcionalidade nova. Problemas voltam à etapa de origem (reabrir com `#em-andamento` e registrar no handoff dela), salvo ajustes triviais de documentação.

**Critérios de aceite:**

- [ ] Os dois testes ponta a ponta passam usando só endpoints e APIs públicas.
- [ ] Todos os itens da "Definição global de pronto" estão marcados, cada um com evidência numa cópia limpa.
- [ ] oasdiff não aponta *breaking change* em `/api/v1` em relação ao fim do M9.
- [ ] A tabela de cobertura da SPEC §9 (fase → marcos → gate concluído) está no handoff, sem lacunas não explicadas.
- [ ] ARCHITECTURE §3.1 descreve o grafo final e `ModularityTest` passa com ele.
- [ ] A captura de atualização do brain documenta `events` (nota de tema nova `ZenyHotelManager - Módulo events`), contas a receber e a retenção LGPD; `docs/roadmaps/README.md` registra a conclusão do M10 e da v1.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint (tabela do `CLAUDE.md`) numa cópia limpa → sucesso.
- Verificação do contrato `api/openapi.yaml` e oasdiff → sucesso.
- `docker compose -f docker/compose.yaml up -d` na cópia limpa → readiness `UP`; `down` em seguida.

**Revisão humana:** sim

**Notas para o plano:**

- Leia os handoffs de todas as etapas E01 a E13 e as pendências registradas nos gates de M0 a M9.
- Padrão dos gates anteriores: o cenário do marco também vira teste de integração na raiz do pacote de testes (como `MilestoneM3EndToEndTest` e `MilestoneM4EndToEndTest`, com os modelos de role e o `MutableClock`), e a conferência ponta a ponta roda num `docker compose` isolado, pelo proxy.
- A captura de atualização do gate leva o estado final (M10 e v1) para `ZenyHotelManager - Estado por marco` e propõe ou completa as notas de `events`, contas a receber (billing) e retenção LGPD (guests).
- Armadilhas apontadas para a revisão: item da definição de pronto sem evidência; teste ponta a ponta com atalhos (escrita direta em tabelas); ARCHITECTURE §3.1 divergente do `ModularityTest`; pendência de marco anterior esquecida.

**Bloqueios:** Nenhum bloqueio conhecido. Depende de E12, que está `#bloqueada` até D1.

**Riscos e mitigação:**

- Pendências acumuladas nos handoffs de marcos anteriores: a conferência da etapa as lista e o usuário decide quais ficam para depois da v1.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —
- Pendências para depois da v1: —

---

## Histórico de revisões

- 2026-10-13 — roadmap criado (antes do código existir; sincronizar antes da primeira etapa).
- 2026-10-13 — aplicadas as decisões da entrevista sobre SPEC × ARCHITECTURE: regra por camadas na §3.1 (contexto, restrições e gate); H2 decidida (`events` → `printing`; E05 e E07 geram os PDFs sem pedir aprovação); H8 sem a justificativa de proibição (BEO segue por checklists, sem push, porque a SPEC não pede); critérios "events não depende de housekeeping, maintenance, notifications, fiscal, inventory" trocados por verificações de desenho (nota só pelo fluxo de `billing`, baixa só pelas fichas técnicas de `pos`).
- 2026-10-14 — E13 recebe a pendência do M1 E04 sobre a retenção da trilha de auditoria (expurgo só com o dono dos schemas). Nenhuma tag mudou.
- 2026-10-22 — migrado para o formato da skill development-roadmap com superpowers (branch `roadmap/m10-comercial-gestao`, protocolo e prompt de execução únicos, etapas sem prompt e estratégia) e para o Second Brain (notas de tema no lugar das seções do CLAUDE.md, captura de atualização por etapa); decisão do usuário.
- 2026-10-27 — protocolo de execução migrado para as souls do Second Brain: a sessão roda como `orquestrador-etapa` (`claude --agent orquestrador-etapa`), e as tarefas passam por `desenvolvedor` → `testador` → verificação mecânica dos testes → `revisor-seguranca`; a captura do brain é publicada pelo `brain-escriba`. Sem fallback para os prompts padrão do superpowers nem para `executing-plans`. Nenhuma etapa, tag, evidência ou handoff mudou.
- 2026-10-29 — protocolo de execução alinhado ao template atual da skill `development-roadmap`: o orquestrador passou de `orquestrador-etapa` para `orquestrador` (`claude --agent orquestrador`), entrou o papel "Revisor de plano" (`revisor-arquitetura`, pré-condição das souls e passo 1 do prompt) e o passo "Plano" diz que o plano passa pela revisão de arquitetura do orquestrador antes de executar. Só "Como usar", "Protocolo de execução" e "Prompt de execução" mudaram; etapas, handoffs e entradas anteriores do histórico seguem como estavam.
