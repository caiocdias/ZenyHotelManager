# Roadmap — M9 Suprimentos: estoque, compras, rouparia e fichas técnicas

> Arquivo: `docs/roadmaps/roadmap-m9-suprimentos.md` · branch de execução: `roadmap/m9-suprimentos` · criado em 2026-10-13 · última revisão 2026-10-22
>
> Marco 9 de 11 · mapa dos marcos em [`docs/roadmaps/README.md`](README.md) · marco anterior: [M8 — Restaurante, distribuição e inauguração](roadmap-m8-restaurante-distribuicao.md) · próximo marco: [M10 — Comercial e gestão](roadmap-m10-comercial-gestao.md)
>
> **Pré-requisito:** o gate do marco M8 (última etapa de `docs/roadmaps/roadmap-m8-restaurante-distribuicao.md`) está `#concluida`.

## Como usar

Inicie uma sessão limpa do Claude Code na raiz do repositório com `claude --agent orquestrador` e cole o "Prompt de execução". Ele executa até 3 etapas em sequência na branch `roadmap/m9-suprimentos`, cada uma planejada pelo plugin superpowers e executada pelas souls (subagentes do Second Brain), e para para revisão. No fim do lote você decide como integrar a branch (merge, PR, manter ou descartar). Para não parar em cada pedido de permissão, libere antes os comandos de build/teste do projeto nas permissões do Claude Code (os comandos oficiais estão na tabela do `CLAUDE.md`; o Docker é necessário para os testes de integração).

**Este roadmap foi escrito antes do código existir.** Antes da primeira etapa, sincronize-o com o código real usando a skill `development-roadmap` (modo atualização): confira caminhos, nomes de módulos, APIs públicas e convenções registrados nos handoffs de M0 a M8 e nas notas de tema do brain, e ajuste as etapas pendentes.

## Objetivo

Colocar custos e insumos sob controle (SPEC §9, Fase 3). Ao fim do marco, o hotel cadastra itens, locais e fornecedores, recebe mercadoria por XML de NF-e, transfere entre locais, faz inventário e recebe alertas de estoque mínimo. As baixas acontecem sozinhas a partir dos checklists, das OS de manutenção e das vendas do PDV (fichas técnicas), com custo médio ponderado móvel e CMV. A rouparia acompanha o ciclo de vida do enxoval, a lavanderia terceirizada e a lavanderia do hóspede, e a recepção controla os itens de empréstimo com devolução no check-out.

## Contexto confirmado

- Requisitos: SPEC §5.17 (EST), §5.18 (ROU), §5.19 RF-PDV-07 e RF-PDV-10 (ficha técnica e consumo interno), §5.16 RF-MAN-06 (peças da OS), §5.15 RF-CHK-05 (ações de checklist), §5.8 RF-REC-09 (itens de empréstimo).
- Regras de negócio: SPEC §6 RN-17 (saldo negativo nunca bloqueia saídas automáticas), RN-18 (custo médio ponderado móvel), RN-03 (imutabilidade financeira para lançamentos em conta), RN-12 (ações sensíveis).
- Modelo de domínio: SPEC §8.2.13 (estoque e compras), §8.2.14 (rouparia e lavanderia), §8.2.15 (`Recipe`, `RecipeLine`), §8.2.11 (`ConsumeStockAction`, `LinenExchangeAction`), §8.2.12 (`WorkOrderPart`); eventos em §8.3 (`ChecklistCompleted`, `TabClosed`, `StockBelowMinimum`).
- Arquitetura: ARCHITECTURE §3.1 — regra por camadas (um módulo pode depender de módulos de camada inferior da §3.2 e de qualquer módulo da Plataforma, sem ciclo; aresta nova é registrada na tabela na mesma mudança). `inventory` depende de `shared`, `checklists`, `files`, `notifications`; `linen` depende de `shared`, `rooms`, `checklists`, `inventory`, `reservations`, `billing`; `pos` depende de `inventory`; `maintenance` depende de `inventory`; `frontdesk` depende de `billing` e cuida dos itens de empréstimo, mas **não** pode depender de `inventory`, `linen` nem `housekeeping` (camada superior). ARCHITECTURE §6.2 descreve o fluxo `ChecklistCompleted` → `inventory`/`linen`. ARCHITECTURE §13: alertas de estoque mínimo por evento, com consolidação diária. ARCHITECTURE §14: XMLs de NF-e de entrada guardados em `files`.
- Convenções herdadas (ler nos handoffs e nas notas de tema do brain): M0 — API, erros RFC 9457, migrações por módulo, `Money`, IDs, `Idempotency-Key`, outbox, ShedLock; M1 — autorização, step-up, auditoria, `files`; M2 — checklists com ações no `ChecklistCompleted`, sincronização offline, notificações; M3 — `billing` (lançamentos por código); M4 — `reservations`; M5 — `frontdesk` (check-out); M6 — `maintenance` corretiva (OS sem peças), `reporting` via views `report_*`; M8 — `pos` (comandas, `TabClosed`, consumo interno sem baixa).
- Comandos de build, teste e lint: os oficiais da tabela do `CLAUDE.md`. Testes de integração com Testcontainers (base de M0 E02).
- Conhecimento do projeto no Second Brain: hub `ZenyHotelManager` e as notas de tema `ZenyHotelManager - Estado por marco`, `ZenyHotelManager - Arquitetura e estrutura de módulos`, `ZenyHotelManager - Schemas, migrações e views de relatório`, `ZenyHotelManager - Tipos do kernel`, `ZenyHotelManager - Convenções de API`, `ZenyHotelManager - Segurança, autenticação e autorização`, `ZenyHotelManager - Auditoria`, `ZenyHotelManager - Eventos e jobs`, `ZenyHotelManager - Sincronização offline`, `ZenyHotelManager - Testes de integração`, `ZenyHotelManager - Módulo files`, `ZenyHotelManager - Módulos hotel e staff`, `ZenyHotelManager - Módulo rooms`, `ZenyHotelManager - Módulo checklists`, `ZenyHotelManager - Módulo notifications`, `ZenyHotelManager - Módulo billing (contas, roteamento e taxa de serviço)`, `ZenyHotelManager - Módulo reservations (inventário, disponibilidade e reservas)`, `ZenyHotelManager - Módulo reservations (ciclo de vida, alocação, grupos e busca)` e `ZenyHotelManager - Hospedagem e recepção`. Os módulos de M6 a M8 que este marco usa (`maintenance`, `reporting`, `pos`, `printing`) ainda não têm nota no brain nesta revisão: valem os handoffs desses marcos e as capturas de atualização pendentes deles.
- Pacote base `com.zeny.hotel` (hipótese herdada de M0 H1). Módulos novos deste marco: `inventory` e `linen` (a criar), cada um com schema e pasta de migração próprios conforme a convenção de M0 E02.

## Escopo

**Incluído:** módulo `inventory` completo (itens, conversões, locais, fornecedores, movimentos, saldo, custo médio, transferências, inventário, mínimo e sugestão de compra, pedido de compra, recebimento por XML de NF-e); baixas automáticas por checklist e por OS; módulo `linen` completo (enxoval, ciclo de vida, troca por checklist, lavanderia terceirizada, lavanderia do hóspede); itens de empréstimo com devolução no check-out; fichas técnicas no `pos` com baixa por venda, consumo interno e CMV; relatórios de suprimentos (RF-EST-10, RF-ROU-06).

**Fora de escopo:**

- Baixa de A&B consumido em eventos (RF-EVT-10): M10, reutilizando a API de fichas técnicas deste marco.
- Manutenção preventiva e ativos: M10.
- Contas a pagar e financeiro administrativo (SPEC Q-01): fora da v1.
- Lavanderia própria (lotes e ciclos): fora da v1 (SPEC §2.2).
- Emissão de NF-e de saída e qualquer integração com SEFAZ: o XML de entrada é só importado.

## Restrições e invariantes

- Respeitar a regra por camadas da ARCHITECTURE §3.1: um módulo só chama a API pública de módulos de camada inferior da §3.2 ou da Plataforma, sem ciclo; o resto vai por evento ou porta. Aresta nova que siga a regra não exige aprovação, mas deve ser acrescentada à tabela §3.1 na mesma etapa e citada no handoff. Dependência para a mesma camada (fora da Plataforma) ou para cima exige parar e perguntar.
- RN-17: saídas automáticas (checklist, PDV, OS) **nunca** falham por saldo insuficiente; o saldo fica negativo e gera alerta.
- RN-18: o custo médio ponderado móvel é recalculado em toda entrada; saídas usam o custo médio vigente.
- Quantidades em `BigDecimal` (nunca ponto flutuante); valores em `Money` (M0 E03).
- Movimentos de estoque são imutáveis: correção por movimento inverso ou ajuste de inventário.
- Toda baixa vinda de outro módulo é idempotente por origem (`sourceType`, `sourceId`, linha), porque eventos podem ser reentregues pelo outbox.
- Ajuste de estoque é ação sensível (RF-ACS-08): exige permissão ou step-up (M1).
- Lançamentos em conta (lavanderia do hóspede, item de empréstimo não devolvido) passam pela API de `billing` com `Idempotency-Key`, nunca por escrita direta.
- XMLs de NF-e de teste são fictícios; nada de dados reais de fornecedores no repositório.
- Referência técnica nova (módulos `inventory` e `linen`, API de baixa, RN-17/RN-18, fichas técnicas, itens de empréstimo) vai para a captura de atualização do brain `ZenyHotelManager - atualização m9-suprimentos Eyy` de cada etapa, nunca para o `CLAUDE.md`; cada módulo novo propõe a sua nota de tema (`ZenyHotelManager - Módulo inventory`, `ZenyHotelManager - Módulo linen`). O `README.md` e o `docs/roadmaps/README.md` continuam sendo atualizados no repositório.

## Hipóteses e decisões em aberto

| # | Hipótese ou decisão | Impacto se errada / sem decisão | Etapa afetada |
|---|---|---|---|
| H1 | Pacote base `com.zeny.hotel` e convenções de M0–M8 conforme os handoffs. | Ajustar caminhos na sincronização inicial. | todas |
| H2 | Decidido (2026-10-13): `inventory` depende de `notifications` (ARCHITECTURE §3.1). Um listener do próprio `inventory` para `StockBelowMinimum` chama a API pública de `notifications` (SPEC §8.3); `notifications` nunca escuta eventos de `inventory`. | Se a API de `notifications` registrada no handoff de M2 for diferente, ajustar a chamada, não a direção da dependência. | E03 |
| H3 | Carrinho de camareira é um `StockLocation` do tipo carrinho vinculado a um colaborador (`staff`, módulo da Plataforma, dependência permitida pela regra por camadas) ou usuário; o "local do executor" de RF-CHK-05 é o carrinho vinculado ao executor do checklist. Sem carrinho vinculado, a baixa vai para um local padrão configurável da governança e gera alerta. | Baixa no local errado; ajustável por configuração. | E01, E07 |
| H4 | Decidido (2026-10-13): itens de empréstimo (RF-REC-09) ficam no módulo `frontdesk` — catálogo, empréstimo por UH e reserva, devolução e conferência no check-out —, sem porta e sem `housekeeping` (ARCHITECTURE §3.1 lista "itens de empréstimo" na responsabilidade de `frontdesk`). A cobrança de item não devolvido vai à conta pela API de `billing`. | Se a governança precisar ver os empréstimos para entregar ou recolher, `housekeeping` (camada Operações, acima de Hospedagem) pode consultar a API pública de `frontdesk`, registrando a aresta na §3.1; fora do escopo deste marco. | E11 |
| H5 | Decidido (2026-10-13): a baixa por ficha técnica é feita pelo próprio `pos`, num listener do seu `TabClosed` que chama a API de `inventory` (e, em M8, a de `fiscal`). `inventory` não escuta `TabClosed`, porque isso subiria de camada e criaria ciclo (`pos` já depende de `inventory`). SPEC §8.3 e ARCHITECTURE §3.1 já refletem isso. | — | E12 |
| H6 | NF-e modelo 55, layout 4.00, lida do XML `nfeProc` com Jakarta XML ou parser equivalente, sem validação de assinatura nem consulta à SEFAZ. | XML de outro layout é rejeitado com erro estável; ampliar depois. | E06 |
| H7 | Reposição de enxoval: o item comprado entra no estoque (`inventory`) e vira enxoval disponível quando `linen` registra a entrada na rouparia, chamando a API de `inventory` para dar a saída do item correspondente. | Duplicidade de controle se o vínculo item ↔ tipo de enxoval faltar; coberto por teste. | E08 |
| H8 | Relatórios de suprimentos são servidos por `reporting` a partir de views `report_*` publicadas por `inventory`, `linen` e `pos` (convenção de M6 e ARCHITECTURE §11). | Ajustar se M6 tiver adotado outro padrão. | E13 |

## Definição global de pronto

- [ ] `inventory` e `linen` existem como módulos do Modulith, com schema e migrações próprios, e `ApplicationModules.verify()` passa sem ciclos, respeitando a regra por camadas, com toda aresta nova deste marco registrada na tabela da ARCHITECTURE §3.1.
- [ ] Entradas (manual e XML de NF-e), transferências, saídas manuais, perdas, inventário e ajustes funcionam por API, com custo médio ponderado e saldo negativo permitido.
- [ ] Concluir um checklist com ações de baixa e de troca de enxoval movimenta estoque e enxoval uma única vez, mesmo com reentrega do evento.
- [ ] Peças de OS e vendas do PDV com ficha técnica dão baixa no estoque e geram custo; o CMV de A&B é calculável.
- [ ] Lavanderia terceirizada, lavanderia do hóspede (com lançamento em conta) e itens de empréstimo (com pendência no check-out) funcionam ponta a ponta.
- [ ] Relatórios RF-EST-10 e RF-ROU-06 disponíveis com exportação CSV.
- [ ] Build, testes, lint e verificação do contrato `api/openapi.yaml` passam; o contrato não tem *breaking change* em `/api/v1`.
- [ ] Cada etapa publicou a captura de atualização do brain `ZenyHotelManager - atualização m9-suprimentos Eyy`, com as notas novas propostas `ZenyHotelManager - Módulo inventory` e `ZenyHotelManager - Módulo linen` e o estado do M9 para `ZenyHotelManager - Estado por marco`.

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

- Toda execução acontece no worktree/branch `roadmap/m9-suprimentos`, criado ou retomado com `superpowers:using-git-worktrees`. Nunca execute na branch principal (`main`).
- Se a branch já existir com trabalho não integrado, continue nela. Se o lote anterior já foi integrado, crie a branch de novo a partir da `main` atualizada.
- Este roadmap é atualizado dentro da branch: tags e handoffs viajam com o código.

**Por etapa**

1. **Escolha:** a primeira etapa, na ordem do documento, marcada `#pendente` e com todas as dependências `#concluida`. Etapas `#bloqueada` e as que dependem delas ficam para trás.
2. **Conferência:** leia a seção da etapa, os handoffs das dependências e o código que eles citam. Se o código divergiu do que a etapa supõe, pare e relate.
3. **Início:** troque a tag para `#em-andamento` (índice e título).
4. **Plano:** use `superpowers:writing-plans`. A spec é a seção desta etapa mais os documentos citados em "Contexto confirmado"; as "Restrições e invariantes" entram como Global Constraints. Salve em `docs/superpowers/plans/AAAA-MM-DD-m9-suprimentos-eNN.md` (AAAA-MM-DD = a data do dia). Antes de executar, o plano passa pela revisão de arquitetura do `orquestrador` (`revisor-arquitetura`); as regras (rodadas, veredito, seção no plano) vivem só nele. A execução já está definida (abaixo): não pergunte ao usuário o método, apenas siga.
5. **Execução:** use `superpowers:subagent-driven-development` com esse plano, executado pelas souls conforme o ciclo por tarefa, a verificação mecânica dos testes e a revisão final da etapa definidos no `orquestrador` (as regras de encaixe vivem só nele). Não invoque `superpowers:finishing-a-development-branch` ao fim da etapa: ele roda uma vez, no fim do lote.
6. **Fechamento:** publique a captura de atualização do brain (bloco "Second Brain"). Marque `#concluida` (índice e título) só com a revisão final limpa e cada critério de aceite verificado com evidência. Diante de impedimento real, marque `#bloqueada` e preencha "Bloqueios" com causa, evidência, impacto e ação de desbloqueio. Preencha "Evidências e handoff". Rulings do ledger que mudam contrato, escopo, segurança, dados ou algo caro de desfazer vão para "Decisões para o usuário".

**Second Brain**

Vale se o `CLAUDE.md` tiver a seção "Conhecimento do projeto no Second Brain"; senão, ignore este bloco.

- O contexto da sessão precisa trazer a linha `BRAIN: carregado (ZenyHotelManager)`, injetada pelo hook. Sem ela, pare e relate.
- Na conferência de cada etapa, leia as notas de tema do brain ligadas a ela (pelo hub `ZenyHotelManager`) e as capturas pendentes do projeto que tratem do assunto: são a referência técnica do projeto. Num handoff antigo, "seção X do `CLAUDE.md`" é a nota de tema correspondente.
- No fechamento de cada etapa que mudou algo descrito no brain (inclusive o estado do roadmap), o orquestrador manda o `brain-escriba` publicar em `00 Inbox/` do vault a captura `ZenyHotelManager - atualização m9-suprimentos Eyy`, com o texto novo ou alterado de cada nota afetada e qual nota ele substitui ou complementa; o contrato de agente externo do vault fica com o `brain-escriba`. Nunca edite notas do brain nem registre referência técnica no `CLAUDE.md`.

**Paradas**

O lote para ao concluir uma etapa com **Revisão humana: sim**, diante de etapa `#bloqueada` ou divergência do plano, nas paradas do próprio superpowers (operação destrutiva, ação sensível de segurança, efeito fora da branch, plano sem caminho viável) e ao atingir o limite de etapas do prompt.

**Fim do lote**

1. Use `superpowers:finishing-a-development-branch`: o usuário decide merge, PR, manter ou descartar a branch. Nunca faça push, merge ou deploy sem essa decisão.
2. Apresente as "Decisões para o usuário" acumuladas como entrevista: perguntas de múltipla escolha, cada uma com uma opção recomendada e justificada (no Claude Code, `AskUserQuestion` com a recomendada em primeiro lugar e o rótulo "(Recomendado)"). Registre as respostas no handoff das etapas e em "Hipóteses e decisões em aberto".

## Prompt de execução

```text
Execute o roadmap docs/roadmaps/roadmap-m9-suprimentos.md deste repositório seguindo a seção "Protocolo de execução" dele, com o plugin superpowers.

Limite desta execução: no máximo 3 etapas concluídas.

1. Confira a pré-condição do protocolo: esta sessão roda como orquestrador e as souls desenvolvedor, testador, revisor-seguranca e revisor-arquitetura estão disponíveis; se não, pare e diga como resolver. Leia CLAUDE.md (e AGENTS.md, se existir) e, no roadmap, o cabeçalho inteiro, o protocolo e o índice. Se o CLAUDE.md exigir o Second Brain, confira a linha BRAIN: carregado no contexto e leia o hub. Rode git status e git branch -a.
2. Se alguma etapa estiver #em-andamento, retome-a a partir do ledger do superpowers e do handoff; não recomece tarefas já commitadas.
3. Crie ou retome o worktree/branch roadmap/m9-suprimentos com superpowers:using-git-worktrees. Não trabalhe na branch principal.
4. Repita, uma etapa por vez: escolha a próxima etapa elegível, marque #em-andamento, planeje com superpowers:writing-plans (spec = seção da etapa + documentos citados), execute com superpowers:subagent-driven-development, feche a etapa (com a captura de atualização do brain) e preencha o handoff conforme o protocolo.
5. Pare nas paradas do protocolo ou ao atingir o limite.
6. No fim, rode superpowers:finishing-a-development-branch e deixe a decisão de integração comigo. Depois, apresente as decisões para o usuário como entrevista com opção recomendada.

Nunca faça push, merge ou deploy sem minha decisão. Não altere tags de etapas que não executou.

Ao parar, responda com: etapas concluídas e o resultado da revisão final de cada uma, a etapa atual e seu estado, o motivo da parada, as etapas #bloqueada com a ação de desbloqueio, a branch e os commits do lote, as capturas do brain publicadas e o próximo passo sugerido.
```

## Índice de etapas

**Estoque e compras**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E01 | Cadastros de estoque | #pendente | — | Módulo `inventory` com itens, conversões, locais (incluindo carrinhos) e fornecedores |
| E02 | Movimentos, saldo e custo médio | #pendente | E01 | Entradas e saídas manuais, perdas, transferências, saldo negativo e custo médio ponderado; API pública de baixa idempotente |
| E03 | Estoque mínimo, alertas e sugestão de compra | #pendente | E02 | `StockBelowMinimum`, consolidação diária e sugestão de compra |
| E04 | Inventário por local | #pendente | E02 | Contagem, ajuste sensível com step-up e relatório de divergências |
| E05 | Pedido de compra simples | #pendente | E02 | Pedido com estados rascunho → recebido/cancelado |
| E06 | Recebimento por XML de NF-e | #pendente | E05 | Importação do XML, vínculo fornecedor ↔ item memorizado, conferência com o pedido e entrada com custo |

**Baixas automáticas**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E07 | Baixas por checklist e por OS | #pendente | E02 | `ChecklistCompleted` com `ConsumeStock` e peças de OS dando baixa uma única vez |

**Rouparia e serviços ao hóspede**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E08 | Enxoval e ciclo de vida | #pendente | E02 | Módulo `linen` com tipos, enxoval-padrão, status, troca por checklist, baixa e reposição |
| E09 | Lavanderia terceirizada | #pendente | E08 | Romaneio de envio, retorno com conferência, divergências e custo |
| E10 | Lavanderia do hóspede | #pendente | E08 | Pedido vinculado à reserva, tabela de preços e lançamento na conta |
| E11 | Itens de empréstimo | #pendente | — | Empréstimo por UH no `frontdesk` e conferência no check-out |

**PDV, relatórios e gate**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E12 | Fichas técnicas e baixa por venda | #pendente | E02 | Receitas no `pos`, baixa no `TabClosed`, consumo interno, custo teórico e CMV |
| E13 | Relatórios de suprimentos | #pendente | E03, E04, E06, E07, E09, E10, E12 | RF-EST-10 e RF-ROU-06 via views `report_*` |
| E14 | Gate do marco M9 | #pendente | E11, E13 | Definição global de pronto verificada |

---

## E01 — Cadastros de estoque — #pendente

**Objetivo:** criar o módulo `inventory` com os cadastros de itens (unidade de controle, conversões de compra, NCM, categoria), locais configuráveis (incluindo carrinhos de camareira vinculados a colaborador) e fornecedores com vínculos fornecedor ↔ item.

**Por que agora:** todo o marco depende desses cadastros, e é a primeira etapa: confere que o M8 terminou e fixa o layout do módulo.

**Dependências:** nenhuma dentro deste roadmap; exige o gate de M8 `#concluida`. Cria o módulo `inventory`.

**Escopo:**

- Pacote `inventory` (a criar) com API pública na raiz, `domain/`, `application/`, `infrastructure/`, `web/`; schema e pasta de migração próprios.
- Entidades `StockItem`, `UnitConversion`, `StockLocation` (tipos: almoxarifado, cozinha, bar, rouparia, carrinho, manutenção), `Supplier`, `SupplierItemLink` (SPEC §8.2.13).
- Carrinho vinculado a um colaborador (`staff`) ou usuário (H3), consultável pela API pública (`findCartOf(executor)`).
- Permissões do catálogo (`inventory.item.manage`, `inventory.location.manage`, `inventory.supplier.manage` ou nomes equivalentes na convenção de M1).
- Endpoints REST de CRUD com paginação por cursor, `ETag` e desativação em vez de exclusão.

**Fora de escopo:** saldos e movimentos (E02); mínimo/máximo por local (E03).

**Critérios de aceite:**

- [ ] Endpoints de itens, locais e fornecedores existem em `/api/v1`, estão no contrato e são cobertos por testes de integração.
- [ ] Código de item duplicado → `409`; CNPJ inválido → `400`; escrita sem a permissão → `403`, todos no formato de erro padrão.
- [ ] Item, local e fornecedor só são desativados, nunca excluídos.
- [ ] Conversão de unidade de compra (ex.: caixa com 12) converte para a unidade de controle em teste unitário.
- [ ] Um carrinho pode ser vinculado a um executor e a API pública devolve o carrinho de um executor.
- [ ] `ModularityTest` passa; build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Revisão humana:** não

**Notas para o plano:** primeira etapa do marco: o roadmap foi escrito antes do código, então confira antes que ele foi sincronizado (ver "Como usar"). H3: o carrinho de camareira é um `StockLocation` do tipo carrinho vinculado a um colaborador (`staff`, Plataforma) ou usuário; guarde só o id e, se `inventory` passar a depender de `staff`, registre a aresta na tabela da ARCHITECTURE §3.1. Regras em SPEC §5.17 e §8.2.13. Layout de módulo, permissões e testes seguem as notas `ZenyHotelManager - Arquitetura e estrutura de módulos`, `ZenyHotelManager - Segurança, autenticação e autorização` e `ZenyHotelManager - Testes de integração`; CNPJ pelo tipo do kernel (`ZenyHotelManager - Tipos do kernel`); a `StaffApi` está em `ZenyHotelManager - Módulos hotel e staff`. A captura de atualização propõe a nota nova `ZenyHotelManager - Módulo inventory`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Vínculo carrinho ↔ colaborador pode exigir dependência nova: guardar só o ID e resolver na API de quem chama (H3).

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E02 — Movimentos, saldo e custo médio — #pendente

**Objetivo:** registrar movimentos de estoque imutáveis (entrada manual, saída manual, perda/quebra com motivo, transferência entre locais com quem entregou e quem recebeu), manter o saldo por item e local, recalcular o custo médio ponderado móvel em cada entrada e expor uma API pública de baixa idempotente para outros módulos.

**Por que agora:** é o núcleo do estoque; todas as baixas automáticas, compras e inventário escrevem por aqui. O contrato da API de baixa será usado por `checklists`, `maintenance`, `pos`, `linen` e `events`.

**Dependências:** E01.

**Escopo:**

- `StockMovement` (tipos: entrada, saída, perda, transferência de saída e de entrada, ajuste) com `sourceType`/`sourceId`/linha e chave única de idempotência por origem.
- `StockLevel` por item e local, atualizado na mesma transação do movimento, com lock curto no par item/local.
- Custo médio ponderado móvel (RN-18) recalculado em entradas; saídas valorizadas pelo custo vigente; regra para entrada quando o saldo está negativo registrada como decisão.
- Saldo negativo permitido em saídas automáticas (RN-17); saída manual com saldo insuficiente também permitida, com alerta (decisão registrada).
- API pública `InventoryApi` (nome conforme convenção) com `consume(source, location, lines)` e `receive(...)`, idempotentes por origem.
- Endpoints REST de movimentos manuais e transferências; consulta de saldo e de movimentação por período (base de RF-EST-10).
- Auditoria dos movimentos manuais (M1).

**Fora de escopo:** alertas de mínimo (E03); ajuste de inventário (E04); recebimento por NF-e (E06).

**Critérios de aceite:**

- [ ] Testes de domínio cobrem o custo médio ponderado móvel em pelo menos: entradas sucessivas com custos diferentes, saída entre entradas e entrada com saldo negativo; os valores batem com cálculo manual registrado no teste.
- [ ] Saída (manual ou pela API pública) com saldo insuficiente é aceita, deixa o saldo negativo e não devolve erro.
- [ ] A mesma origem (`sourceType`, `sourceId`, linha) chamada duas vezes resulta em um único movimento (teste de integração).
- [ ] Duas baixas simultâneas no mesmo item e local resultam no saldo correto (teste concorrente).
- [ ] Transferência gera saída e entrada pareadas com quem entregou e quem recebeu.
- [ ] O usuário da aplicação não tem `UPDATE`/`DELETE` na tabela de movimentos, ou a aplicação não expõe esses caminhos e um teste comprova; correções são movimentos novos.
- [ ] Movimentos manuais são auditados.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Revisão humana:** sim

**Notas para o plano:** o contrato da API de baixa será usado por `checklists` (E07), `maintenance` (E07), `pos` (E12), `linen` (E08) e `events` (M10): é o contrato que justifica a revisão humana. Decisões a registrar e levar à revisão humana: o custo médio de uma entrada com saldo negativo (a SPEC não tem regra; sugestão nos riscos) e a saída manual com saldo insuficiente (permitida, com alerta). Regras em RN-17, RN-18 e SPEC §8.2.13. Movimentos *append-only* seguem a convenção de `ZenyHotelManager - Schemas, migrações e views de relatório` (papel da aplicação só com `SELECT, INSERT`, bean `AppendOnlyTables` e trigger contra `UPDATE`/`DELETE`); a trava de contadores em ordem de chave do inventário de reservas (`ZenyHotelManager - Módulo reservations (inventário, disponibilidade e reservas)`) é o precedente para baixas simultâneas sem deadlock. A captura leva a API pública de baixa para a nota `ZenyHotelManager - Módulo inventory`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Custo médio com saldo negativo não tem regra na SPEC: adotar "entrada com saldo negativo usa o custo da entrada para a parte que zera o saldo" ou equivalente, registrar como decisão e pedir confirmação na revisão humana.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E03 — Estoque mínimo, alertas e sugestão de compra — #pendente

**Objetivo:** configurar mínimo e máximo por item e local, publicar `StockBelowMinimum` quando um movimento cruza o mínimo, consolidar os alertas diariamente e oferecer a sugestão de compra (quantidade até o máximo, agrupada por fornecedor).

**Por que agora:** saldo negativo é permitido (RN-17), então o alerta é o controle que sobra; precisa existir antes das baixas automáticas em volume.

**Dependências:** E02. Independente de E04, E05, E07, E08 e E12 (mexem nas migrações e na API pública de `inventory`).

**Escopo:**

- Mínimo e máximo por item e local (`StockLevel`); endpoint de configuração.
- Evento `StockBelowMinimum` publicado uma vez por cruzamento (não a cada movimento abaixo do mínimo) e também quando o saldo fica negativo.
- Listener do próprio `inventory` para `StockBelowMinimum` que chama a API pública de `notifications` (H2), destinado a quem tem a permissão de almoxarife.
- Consolidação diária (job com ShedLock ou listener de `BusinessDateAdvanced`, conforme a convenção de M6) com a lista de itens abaixo do mínimo.
- Endpoint de sugestão de compra por fornecedor (vínculos de E01, prazo de entrega).

**Fora de escopo:** criação automática do pedido de compra (E05 aceita a sugestão como rascunho só se for trivial; senão fica manual).

**Critérios de aceite:**

- [ ] Movimento que leva o saldo abaixo do mínimo publica `StockBelowMinimum`; movimentos seguintes abaixo do mínimo não publicam outro até o saldo voltar acima (teste de integração).
- [ ] Saldo negativo publica o alerta mesmo sem mínimo configurado.
- [ ] O alerta resulta numa notificação para quem tem a permissão de almoxarife (teste de módulo com `Scenario` do Modulith ou equivalente).
- [ ] A consolidação diária gera a lista de itens abaixo do mínimo uma vez por dia (ShedLock ou listener idempotente).
- [ ] `GET` da sugestão de compra devolve itens agrupados por fornecedor com quantidade até o máximo.
- [ ] `ModularityTest` passa; `notifications` não depende de `inventory`.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Revisão humana:** não

**Notas para o plano:** H2 decidida: o alerta chega ao almoxarife por um listener do próprio `inventory` que chama a `NotificationsApi` (destinatários por permissão, tipo declarado no catálogo do módulo, sem dado pessoal; ver `ZenyHotelManager - Módulo notifications`); `notifications` nunca escuta eventos de `inventory`, e a aresta `inventory → notifications` já está na ARCHITECTURE §3.1. RF-EST-07 em SPEC §5.17. A consolidação diária segue a convenção de jobs (`ZenyHotelManager - Eventos e jobs`) ou de listener de `BusinessDateAdvanced` adotada no M6 (ver o handoff do M6). E05 pode usar a sugestão de compra como rascunho do pedido.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- API de `notifications` diferente da esperada: ajustar a chamada conforme o handoff de M2, sem inverter a direção da dependência.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E04 — Inventário por local — #pendente

**Objetivo:** permitir a contagem de inventário por local (`InventoryCount`), comparar contado × esperado, fechar a contagem gerando movimentos de ajuste sob permissão ou step-up e emitir o relatório de divergências.

**Por que agora:** com saldo negativo permitido, o inventário é como o saldo volta à realidade (RN-17 "pede inventário").

**Dependências:** E02. Independente de E03, E05, E07, E08 e E12 (mexem nas migrações de `inventory`).

**Escopo:**

- `InventoryCount` e `InventoryCountLine` (SPEC §8.2.13): abrir (congela o esperado), registrar contagens, fechar com aprovação.
- Fechamento gera movimentos de ajuste valorizados pelo custo médio, numa transação.
- Ajuste é ação sensível (RF-ACS-08, RF-EST-06): exige a permissão ou `X-Approval-Token` (step-up de M1); auditoria com solicitante e aprovador.
- Movimentos entre a abertura e o fechamento: tratamento registrado como decisão (provável: o esperado é recalculado no fechamento com os movimentos do período).
- Relatório de divergências por contagem (quantidade e valor).

**Fora de escopo:** contagem offline no app (não está no escopo offline da ARCHITECTURE §9).

**Critérios de aceite:**

- [ ] Fechar a contagem sem a permissão de ajuste → `403` com `code` `APPROVAL_REQUIRED`; com `X-Approval-Token` válido → movimentos de ajuste gerados e auditados com solicitante e aprovador.
- [ ] Ajustes valorizados pelo custo médio vigente.
- [ ] Movimentos ocorridos entre abertura e fechamento são tratados conforme a decisão registrada no handoff, com teste.
- [ ] Contagem fechada não aceita novas contagens nem reabertura (`409`).
- [ ] Relatório de divergências mostra quantidade e valor por item.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Revisão humana:** não

**Notas para o plano:** o fechamento é ação sensível (RF-ACS-08, RF-EST-06): use a convenção de step-up de `ZenyHotelManager - Segurança, autenticação e autorização` (`requireOrApproval`, `X-Approval-Token`, `403 APPROVAL_REQUIRED`). Decisão a registrar no handoff: o tratamento dos movimentos entre a abertura e o fechamento (provável: recalcular o esperado no fechamento com os movimentos do período). Contagem offline fica fora (ARCHITECTURE §9).

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Contagem longa com operação em andamento: o tratamento por recálculo no fechamento evita bloquear o local.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E05 — Pedido de compra simples — #pendente

**Objetivo:** criar o pedido de compra (fornecedor, itens, quantidades, preços, previsão) com os estados rascunho, enviado, recebido parcial, recebido e cancelado (RF-EST-09), com recebimento manual que gera entradas no estoque.

**Por que agora:** o recebimento por NF-e (E06) confere contra o pedido.

**Dependências:** E02. Independente de E03, E04, E07, E08 e E12 (mexem nas migrações de `inventory`).

**Escopo:**

- `PurchaseOrder` e `PurchaseOrderLine` com número legível (gerador de M0 E03).
- Transições válidas e `409` nas inválidas; recebimento parcial acumula `receivedQuantity`.
- Recebimento manual gera entradas via E02 (com custo do pedido) e atualiza o estado.
- Criação de rascunho a partir da sugestão de compra de E03, se E03 já estiver concluída; senão, registrar como nota para E13/E14.
- PDF do pedido: só se o mecanismo de documentos de M5 (`printing`) estiver acessível a `inventory` pelo grafo; caso contrário, fora de escopo e registrado.

**Fora de escopo:** envio do pedido por e-mail ao fornecedor; recebimento por XML (E06).

**Critérios de aceite:**

- [ ] Pedido recebe número legível sequencial.
- [ ] Transições fora do ciclo rascunho → enviado → recebido parcial → recebido (ou → cancelado) devolvem `409` com `code` estável.
- [ ] Recebimento parcial atualiza `receivedQuantity`, muda o estado para recebido parcial e gera entradas com o custo da linha; o recebimento do restante muda para recebido.
- [ ] Recebimento acima da quantidade do pedido segue a regra registrada no handoff (rejeitar ou aceitar com alerta), com teste.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Revisão humana:** não

**Notas para o plano:** RF-EST-09 em SPEC §5.17. Número legível pelo gerador do kernel (`ZenyHotelManager - Tipos do kernel`). O rascunho a partir da sugestão de compra só entra se E03 já estiver concluída; senão, vira nota para E13/E14. O PDF do pedido só entra se o `printing` (M5) for alcançável por `inventory` pela regra por camadas; senão fica fora e registrado. Decisão a registrar no handoff: recebimento acima da quantidade do pedido (rejeitar ou aceitar com alerta).

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Nenhum relevante além dos pontos de atenção da etapa: receber pedido cancelado, recebimento acima do pedido sem regra registrada e entrada sem custo.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E06 — Recebimento por XML de NF-e — #pendente

**Objetivo:** importar o XML da NF-e de entrada, guardar o arquivo em `files`, vincular cada item do fornecedor ao item interno (memorizando o vínculo para as próximas notas), conferir contra o pedido de compra e lançar as entradas com custo.

**Por que agora:** é a forma principal de entrada (RF-EST-03) e alimenta o custo médio com valores reais.

**Dependências:** E05.

**Escopo:**

- Upload do XML (H6) com limite de tamanho; armazenamento em `files` com a finalidade "NF-e de entrada" (ARCHITECTURE §14).
- Parser do `nfeProc`: chave de acesso, emitente (CNPJ → fornecedor), data, itens (código, descrição, NCM, unidade, quantidade, valor), total.
- `GoodsReceipt` e `GoodsReceiptLine` em rascunho; vínculo `SupplierItemLink` sugerido pelo histórico e confirmado pelo usuário, com conversão de unidade.
- Conferência com o pedido (quando informado): divergências de item, quantidade e preço destacadas.
- `post()` gera entradas (E02), atualiza o pedido (E05) e impede reimportar a mesma chave de acesso (`409`).

**Fora de escopo:** validação de assinatura e consulta à SEFAZ; contas a pagar.

**Critérios de aceite:**

- [ ] Importar um XML válido cria um recebimento em rascunho com fornecedor (pelo CNPJ do emitente), chave de acesso, itens e total, e guarda o XML em `files`.
- [ ] Na segunda nota do mesmo fornecedor, itens já vinculados são reconhecidos sem nova confirmação.
- [ ] Item comprado em caixa com 12 entra como 12 unidades com custo unitário dividido corretamente.
- [ ] Recebimento vinculado a pedido destaca divergências de item, quantidade e preço e, ao lançar, atualiza o estado do pedido.
- [ ] Reimportar a mesma chave de acesso → `409`; XML de layout não suportado → `400` com `code` estável.
- [ ] XML com `DOCTYPE` ou entidade externa é rejeitado (proteção contra XXE testada).
- [ ] Nenhum XML real de terceiros no repositório.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Revisão humana:** não

**Notas para o plano:** H6 (NF-e modelo 55, layout 4.00, `nfeProc`, sem assinatura nem SEFAZ); RF-EST-03 e RF-EST-09 em SPEC §5.17 e ARCHITECTURE §14. Armadilha: o catálogo de tipos do `files` só aceita PNG, JPEG, WebP e PDF, detectados pela assinatura do conteúdo (`ZenyHotelManager - Módulo files`): guardar o XML exige estender o `files` com o tipo XML e uma finalidade nova; o upload multipart não leva `@Idempotent`. O parser precisa recusar `DOCTYPE` e entidades externas (XXE). Só XMLs fictícios.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Variações de layout entre emissores: testes com as variações mais comuns e erro estável para o resto (H6).

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E07 — Baixas por checklist e por OS — #pendente

**Objetivo:** consumir as ações `ConsumeStock` do `ChecklistCompleted` em `inventory` (baixa do carrinho do executor) e registrar o consumo de peças nas OS de `maintenance` (RF-MAN-06), ambos pela API de baixa idempotente de E02.

**Por que agora:** fecha as ações de checklist previstas desde M2 e a pendência de peças deixada em M6.

**Dependências:** E02. Independente de E03, E04, E05, E08 e E12 (E08 também pode mexer no payload de `ChecklistCompleted` em `checklists`).

**Escopo:**

- Listener em `inventory` para `ChecklistCompleted` (`inventory` depende de `checklists` ✓): para cada resposta com `ConsumeStock`, baixa do carrinho do executor (H3) com origem `(checklistExecution, item)`.
- Execuções feitas offline (M2) usam o horário corrigido do dispositivo como `occurredAt` do movimento.
- `WorkOrderPart` em `maintenance` (`maintenance` depende de `inventory` ✓): adicionar peça à OS chama a baixa com origem `(workOrder, part)`; remover peça gera movimento inverso; custo da peça soma ao custo da OS (RF-MAN-02).
- Saldo negativo nunca bloqueia (RN-17); alerta de E03 se já existir.

**Fora de escopo:** troca de enxoval (E08); baixa por ficha técnica (E12).

**Critérios de aceite:**

- [ ] `ChecklistCompleted` com uma ação `ConsumeStock` gera exatamente um movimento de saída no carrinho do executor, com `occurredAt` igual ao horário corrigido da execução (teste de módulo).
- [ ] Reentregar o mesmo evento não cria segundo movimento.
- [ ] Executor sem carrinho vinculado: baixa no local padrão configurado e alerta registrado.
- [ ] Adicionar peça à OS gera saída com origem na OS; remover gera movimento inverso; o custo da OS inclui as peças pelo custo médio.
- [ ] `checklists` não depende de `inventory` (`ModularityTest`).
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Revisão humana:** não

**Notas para o plano:** H3 (carrinho do executor; sem carrinho, local padrão da governança com alerta); ARCHITECTURE §6.2; RF-CHK-05 e RF-MAN-06 na SPEC. O `ChecklistCompleted` publicado desde o M2 traz `actions` com o tipo `CONSUME_STOCK` (`stockItemId` e a `quantity` respondida), o executor e o `completedAt` já corrigido pela defasagem do aparelho; os consumidores deduplicam por `executionId` + `itemKey` + tipo. Armadilha decidida pelo usuário em 2026-10-16: a execução registrada à parte de uma rotina (`duplicateOf`) também publica o próprio evento com as ações, e quem consome decide se aplica (por exemplo, não baixar estoque em dobro) — registre a decisão no handoff. Ver `ZenyHotelManager - Módulo checklists` e `ZenyHotelManager - Sincronização offline`. A OS, os estados e o custo da OS vêm do handoff do M6 (`maintenance`).

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Se o payload do `ChecklistCompleted` de M2 não trouxer o executor ou o insumo, a etapa acrescenta os campos em `checklists` de forma compatível e registra no handoff.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E08 — Enxoval e ciclo de vida — #pendente

**Objetivo:** criar o módulo `linen` com tipos de enxoval, enxoval-padrão por tipo de UH, quantidades por status (disponível, em uso, suja, na lavanderia, em reparo, descartada), movimentos de status, troca de enxoval pelo `ChecklistCompleted`, baixa com motivo e reposição a partir do estoque.

**Por que agora:** base da lavanderia terceirizada (E09) e do controle de perdas (RF-ROU-06).

**Dependências:** E02. Independente de E03, E04, E05, E07 e E12 (E07 também pode mexer em `checklists`).

**Escopo:**

- Pacote `linen` (a criar), schema e migrações próprios; entidades `LinenType`, `LinenPar`, `LinenStock`, `LinenMovement` (SPEC §8.2.14).
- Movimentos de status sempre em pares (de → para) com quantidade, motivo e origem; quantidades nunca negativas por status (decisão registrada se a troca offline chegar com saldo insuficiente: aceitar e sinalizar, coerente com RN-22).
- Listener de `ChecklistCompleted` (`linen` depende de `checklists` ✓) para `LinenExchange`: retiradas vão de em uso → suja; colocadas vão de disponível → em uso; idempotente por (execução, item).
- Baixa (disponível/suja/em reparo → descartada) com motivo e permissão.
- Reposição (H7): enxoval comprado sai do estoque via API de `inventory` e entra como disponível.

**Fora de escopo:** romaneios (E09); lavanderia do hóspede (E10).

**Critérios de aceite:**

- [ ] `ChecklistCompleted` com `LinenExchange` move as retiradas de em uso → suja e as colocadas de disponível → em uso; reentrega não duplica (teste de módulo).
- [ ] A soma das quantidades de um tipo em todos os status só muda com baixa (para descartada) ou reposição (teste).
- [ ] Baixa exige motivo e permissão.
- [ ] Reposição gera saída do item correspondente em `inventory` e entrada como disponível em `linen`.
- [ ] Enxoval-padrão configurável por tipo de UH.
- [ ] `ModularityTest` passa; build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Revisão humana:** não

**Notas para o plano:** H7 (reposição: o item sai do estoque pela API de `inventory` e entra como disponível em `linen`); SPEC §5.18 e §8.2.14 e a linha de `linen` na ARCHITECTURE §3.1. A ação do `ChecklistCompleted` é `LINEN_EXCHANGE` (`linenTypeId`, `movement` `REMOVED`/`PLACED` e a quantidade), com a mesma deduplicação e a mesma armadilha da execução registrada à parte (`duplicateOf`) descritas em E07 (`ZenyHotelManager - Módulo checklists`). Troca offline com saldo insuficiente: aceitar e sinalizar (RN-22). A captura de atualização propõe a nota nova `ZenyHotelManager - Módulo linen`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Troca offline com saldo insuficiente em disponível: aceitar e sinalizar (RN-22), nunca rejeitar a execução.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E09 — Lavanderia terceirizada — #pendente

**Objetivo:** registrar romaneios de envio à lavanderia terceirizada (por peças e/ou kg), o retorno com conferência, as divergências (faltantes, danificadas, manchadas) e o custo pela tabela do fornecedor (RF-ROU-03).

**Por que agora:** completa o ciclo suja → lavanderia → disponível do enxoval.

**Dependências:** E08. Independente de E10 (as duas mexem nas migrações de `linen`).

**Escopo:**

- `LaundryShipment` e `LaundryShipmentLine` com número legível; fornecedor de `inventory` (`linen` depende de `inventory` ✓).
- Envio move suja → na lavanderia; retorno move na lavanderia → disponível (conferidas), → em reparo (danificadas/manchadas) e registra faltantes como divergência pendente (sem sumir do total).
- Tabela de preços por fornecedor (por peça e/ou kg) e custo do romaneio.
- Resolução de faltantes: retorno tardio ou baixa como perdida.

**Fora de escopo:** contas a pagar do fornecedor.

**Critérios de aceite:**

- [ ] Envio move as quantidades de suja → na lavanderia.
- [ ] Retorno com conferência move conferidas → disponível e danificadas/manchadas → em reparo; faltantes ficam como divergência pendente sem alterar a soma total do tipo.
- [ ] Divergência pendente resolvida por retorno tardio ou baixa como perdida (com motivo).
- [ ] Registrar o retorno de um romaneio já retornado → `409`.
- [ ] Custo calculado pela tabela do fornecedor por peça e por kg, com `Money`.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Revisão humana:** não

**Notas para o plano:** RF-ROU-03 em SPEC §5.18. Romaneio por kg: conferência só em peças, kg só para o custo (ver riscos); valores sempre em `Money`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Romaneio por kg não permite conferência por peça: conferência só em quantidade de peças, kg para custo.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E10 — Lavanderia do hóspede — #pendente

**Objetivo:** registrar pedidos de lavanderia do hóspede vinculados à reserva, com itens da tabela de preços, estados (recolhido, enviado, retornado, entregue) e lançamento do total na conta da reserva (RF-ROU-05).

**Por que agora:** é receita do hotel e depende só do módulo `linen` e das APIs de `reservations` e `billing`.

**Dependências:** E08. Independente de E09 (as duas mexem nas migrações de `linen`).

**Escopo:**

- `GuestLaundryOrder`, `GuestLaundryLine`, `LaundryPriceItem` (SPEC §8.2.14).
- Validação de reserva hospedada pela API de `reservations` (`linen` depende de `reservations` ✓).
- Lançamento na conta pela API de `billing` (`linen` depende de `billing` ✓) com código de lançamento de lavanderia e origem "lavanderia" (RF-CTA-02), idempotente; o roteamento de contas de M3 se aplica.
- Cancelamento antes do lançamento; depois do lançamento, a correção é estorno em `billing`.

**Fora de escopo:** NFS-e da lavanderia (segue o fluxo fiscal de M7 pela conta).

**Critérios de aceite:**

- [ ] Pedido para reserva que não está hospedada → `409` com `code` estável.
- [ ] O total do pedido é lançado na conta da reserva uma única vez (repetição da operação não duplica), com código de lavanderia e origem "lavanderia".
- [ ] Transições fora de recolhido → enviado → retornado → entregue → `409`.
- [ ] Cancelar pedido já lançado devolve `409` orientando o estorno em `billing`.
- [ ] Endpoints acessíveis à camareira não expõem nome, documento ou contato do hóspede (RN-13).
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Revisão humana:** não

**Notas para o plano:** RF-ROU-05 em SPEC §5.18; RN-13. O código de lançamento de lavanderia já existe (`TransactionCodeKey.LAUNDRY`, código `LAVANDERIA`) e há a origem de lançamento de lavanderia (`ZenyHotelManager - Módulo billing (contas, roteamento e taxa de serviço)`); a `BillingApi.post` é chamada em processo, sem `Idempotency-Key`: a idempotência do lançamento vem do estado do pedido (e do `@Idempotent` do endpoint que o dispara). A reserva hospedada e a conta da reserva vêm da `ReservationsApi` (`ZenyHotelManager - Hospedagem e recepção`). Decisão a registrar: o momento do lançamento (sugestão: na entrega).

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Momento do lançamento (recolhimento ou entrega) não é definido na SPEC: adotar a entrega e registrar como decisão.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E11 — Itens de empréstimo — #pendente

**Objetivo:** controlar no módulo `frontdesk` os itens de empréstimo (berço, ferro, adaptador) por UH e reserva, com entrega e devolução, e fazer o check-out exigir decisão sobre os itens não devolvidos (RF-REC-09).

**Por que agora:** é a última funcionalidade da Fase 3 e não depende das demais etapas deste marco.

**Dependências:** nenhuma dentro deste roadmap; exige o gate de M8 `#concluida`. Independente das etapas de `inventory` e `linen`.

**Escopo:**

- Módulo dono: `frontdesk` (H4), em subpacotes internos próprios (a criar). Catálogo de itens de empréstimo com quantidade disponível; empréstimo vinculado a reserva e UH, com quem entregou e quando; devolução; endpoints REST e permissões no catálogo de M1.
- Conferência no check-out de M5: o check-out lista os itens pendentes e exige decisão (devolvido, cobrar na conta ou baixar como perda) antes de concluir; a cobrança vai à conta pela API de `billing` (`frontdesk` → `billing` já previsto na ARCHITECTURE §3.1), com `Idempotency-Key`.
- Recado à governança para entregar o item antes da chegada pode reutilizar os recados de M5 (RF-REC-07), que também são do `frontdesk`.

**Fora de escopo:** controle de estoque dos itens em `inventory` (são ativos de empréstimo, não insumos); visão dos empréstimos no app da governança.

**Critérios de aceite:**

- [ ] Empréstimo de item sem quantidade disponível → `409`; devolução restaura a disponibilidade.
- [ ] Check-out de reserva com item não devolvido e sem decisão → `409` com `code` estável listando os itens.
- [ ] Com a decisão "cobrar", o valor é lançado na conta uma única vez (repetição com a mesma `Idempotency-Key` não duplica); com "perda", o item sai do catálogo disponível com motivo.
- [ ] Check-out de reserva sem itens emprestados se comporta como antes (suíte de `frontdesk` de M5 passa sem alteração de expectativas).
- [ ] `frontdesk` não depende de `housekeeping`, `inventory` nem `linen` (`ModularityTest`).
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Revisão humana:** não

**Notas para o plano:** H4 decidida: os itens de empréstimo ficam no `frontdesk`, sem porta e sem `housekeeping`, `inventory` nem `linen`. RF-REC-06 e RF-REC-09 em SPEC §5.8 e a linha de `frontdesk` na ARCHITECTURE §3.1. Leia no handoff do M5 e em `ZenyHotelManager - Hospedagem e recepção` como o check-out orquestrado confere pré-condições e lança na conta; a cobrança vai pela `BillingApi` em processo, com a idempotência no endpoint (`@Idempotent`). O recado à governança pode reutilizar os recados de RF-REC-07 do `frontdesk`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Alterar o check-out de M5 pode quebrar fluxos existentes: rodar a suíte de `frontdesk` inteira e manter o comportamento sem itens idêntico.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E12 — Fichas técnicas e baixa por venda — #pendente

**Objetivo:** cadastrar fichas técnicas (insumos, quantidades, rendimento) nos produtos do cardápio, dar baixa automática no estoque do ponto de venda quando a comanda fecha, dar baixa no consumo interno e cortesia sem faturar e calcular custo teórico e CMV (RF-PDV-07, RF-PDV-10).

**Por que agora:** completa o PDV de M8 com custo; é pré-requisito do CMV de A&B (E13) e da baixa de eventos em M10.

**Dependências:** E02. Independente de E03, E04, E05, E07 e E08 (sem arquivos em comum com `inventory` além da API pública).

**Escopo:**

- `Recipe` e `RecipeLine` em `pos` (SPEC §8.2.15), com rendimento e unidade de controle do item de estoque; modificadores que alteram insumos ficam fora salvo se triviais (decisão registrada).
- Listener de `pos` para o próprio `TabClosed` (H5) que chama a baixa de `inventory` no local de estoque do ponto de venda, com origem `(tab, item)`; itens cancelados não baixam; itens transferidos baixam só na comanda que fecha.
- Consumo interno e cortesia (RF-PDV-10) dão baixa sem faturar.
- API pública de `pos` para baixa por ficha técnica a partir de quantidades (usada por `events` em M10, RF-EVT-10).
- Custo teórico por produto (soma dos insumos pelo custo médio) e CMV por período e ponto de venda (consulta ou view `report_*`).

**Fora de escopo:** baixa de A&B de eventos (M10).

**Critérios de aceite:**

- [ ] Fechar comanda com produto com ficha técnica gera saídas proporcionais ao rendimento no local de estoque do ponto de venda, uma única vez mesmo com reentrega do evento.
- [ ] Itens cancelados não geram baixa; itens transferidos baixam apenas na comanda que fecha.
- [ ] Consumo interno e cortesia geram baixa e nenhum lançamento financeiro.
- [ ] A API pública de baixa por ficha a partir de quantidades existe e é coberta por teste.
- [ ] Custo teórico do produto é a soma dos insumos pelo custo médio vigente; CMV do período usa o custo registrado nos movimentos.
- [ ] `inventory` não depende de `pos` (`ModularityTest` sem ciclo).
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Revisão humana:** sim

**Notas para o plano:** H5 decidida: a baixa por ficha técnica é feita por um listener do próprio `pos` para o seu `TabClosed`, que chama a API de `inventory`; `inventory` nunca escuta `TabClosed` (criaria ciclo). RF-PDV-07 e RF-PDV-10 em SPEC §5.19 e §8.2.15; o formato do `TabClosed` (itens cancelados, transferidos, consumo interno) vem do handoff do M8. A API pública de baixa por ficha a partir de quantidades é contrato para `events` no M10 (RF-EVT-10): registre-a nas notas para o M10. Decisão a registrar: modificadores que alteram insumos (fora, salvo se triviais).

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Produtos sem ficha técnica: não baixam e aparecem num relatório de produtos sem ficha.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E13 — Relatórios de suprimentos — #pendente

**Objetivo:** disponibilizar os relatórios RF-EST-10 (posição por local, movimentação, consumo de amenities por UH ocupada, CMV de A&B) e RF-ROU-06 (giro do enxoval, perdas, custo de lavanderia por UH ocupada), com exportação CSV.

**Por que agora:** depende dos dados de todas as etapas de estoque, rouparia e PDV.

**Dependências:** E03, E04, E06, E07, E09, E10, E12.

**Escopo:**

- Views `report_*` publicadas por `inventory`, `linen` e `pos` (H8, ARCHITECTURE §11), sem expor tabelas internas.
- Endpoints em `reporting` que combinam essas views com as de ocupação já publicadas (M6), filtráveis por período e local.
- Exportação CSV (RF-REL-07), seguindo o padrão de M6.

**Fora de escopo:** indicadores gerenciais (M10); XLSX se M6 não o tiver implementado.

**Critérios de aceite:**

- [ ] Os sete relatórios listados no objetivo existem e cada um confere com cálculo manual num teste de integração com dados semeados.
- [ ] `reporting` consulta apenas views `report_*` (teste ou regra ArchUnit/SQL comprovando).
- [ ] "UH ocupada" usa o mesmo denominador das estatísticas diárias de M6.
- [ ] Exportação CSV disponível para cada relatório.
- [ ] Acesso sem a permissão de relatórios → `403`.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Revisão humana:** não

**Notas para o plano:** H8: `reporting` lê só views `report_*` publicadas por `inventory`, `linen` e `pos` (ARCHITECTURE §11), com a convenção de nome, contrato, privilégios e o teste com um papel que só lê as views de `ZenyHotelManager - Schemas, migrações e views de relatório`. O denominador "UH ocupada" precisa ser o das estatísticas diárias do M6 (as noites com `counts_in_occupancy` em `reservations.report_room_nights`, Q-15; ver `ZenyHotelManager - Módulo reservations (ciclo de vida, alocação, grupos e busca)` e o handoff do M6). Exportação CSV no padrão do M6 (RF-REL-07). Leia os handoffs de E03, E04, E06, E07, E09, E10 e E12.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Consultas pesadas em períodos longos: limitar o período máximo e medir no teste com volume moderado.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E14 — Gate do marco M9 — #pendente

**Objetivo:** confirmar, a partir de uma cópia limpa do repositório, que toda a definição global de pronto do M9 é atendida, com um teste ponta a ponta do fluxo de suprimentos, e deixar o M10 pronto para ser sincronizado.

**Por que agora:** é o critério de entrada do marco M10.

**Dependências:** E11, E13 (que dependem transitivamente de todas as demais).

**Escopo:**

- Rodar build, testes, lint, verificação do contrato e oasdiff numa cópia limpa.
- Teste ponta a ponta (de integração): NF-e de entrada → transferência para o carrinho → checklist com baixa e troca de enxoval → romaneio de lavanderia → comanda com ficha técnica → relatório de CMV e consumo por UH ocupada.
- Conferir cada item da "Definição global de pronto".
- Publicar a captura de atualização do brain (estado do M9; módulos `inventory` e `linen`, API de baixa, regras RN-17/RN-18) e registrar a conclusão do M9 no histórico de `docs/roadmaps/README.md`.

**Fora de escopo:** funcionalidade nova. Problemas voltam à etapa de origem (reabrir com `#em-andamento` e registrar no handoff dela), salvo ajustes triviais de documentação.

**Critérios de aceite:**

- [ ] O teste ponta a ponta de suprimentos passa usando só endpoints e APIs públicas dos módulos.
- [ ] Todos os itens da "Definição global de pronto" estão marcados, cada um com evidência numa cópia limpa.
- [ ] oasdiff não aponta *breaking change* em `/api/v1` em relação ao fim do M8.
- [ ] Toda dependência de módulo acrescentada neste marco segue a regra por camadas e está registrada na tabela da ARCHITECTURE §3.1.
- [ ] A captura de atualização do brain `ZenyHotelManager - atualização m9-suprimentos E14` foi publicada, e as capturas do marco documentam `inventory`, `linen`, a API de baixa e as regras RN-17/RN-18; `docs/roadmaps/README.md` registra a conclusão do M9.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint (tabela do `CLAUDE.md`) numa cópia limpa → sucesso.
- Verificação do contrato `api/openapi.yaml` e oasdiff → sucesso.

**Revisão humana:** sim

**Notas para o plano:** leia o handoff de todas as etapas E01 a E13. O teste ponta a ponta usa só endpoints e APIs públicas, sem escrita direta em tabelas. O oasdiff compara com o fim do M8. A captura de atualização desta etapa leva o estado do M9 para `ZenyHotelManager - Estado por marco` e confere que as notas propostas `ZenyHotelManager - Módulo inventory` e `ZenyHotelManager - Módulo linen` cobrem a API de baixa e as regras RN-17/RN-18. Problemas voltam à etapa de origem (reabrir com `#em-andamento` e registrar no handoff dela).

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Teste ponta a ponta lento: um único cenário, com dados mínimos.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —
- Notas para o marco M10: —

---

## Histórico de revisões

- 2026-10-13 — roadmap criado (antes do código existir; sincronizar antes da primeira etapa).
- 2026-10-13 — aplicadas as decisões da entrevista sobre SPEC × ARCHITECTURE: regra por camadas na §3.1 (restrições, definição de pronto e gate); H2 decidida (`inventory` → `notifications`, E03 não pergunta mais); H3 aceita vínculo com `staff`; H4 decidida (itens de empréstimo no `frontdesk`, E11 reescrita sem porta nem `housekeeping`, revisão humana removida); H5 confirmada (listener do `pos` para `TabClosed`).
- 2026-10-22 — migrado para o formato da skill development-roadmap com superpowers (branch `roadmap/m9-suprimentos`, protocolo e prompt de execução únicos, etapas sem prompt e estratégia) e para o Second Brain (notas de tema no lugar das seções do CLAUDE.md, captura de atualização por etapa); decisão do usuário.
- 2026-10-27 — protocolo de execução migrado para as souls do Second Brain: a sessão roda como `orquestrador-etapa` (`claude --agent orquestrador-etapa`), e as tarefas passam por `desenvolvedor` → `testador` → verificação mecânica dos testes → `revisor-seguranca`; a captura do brain é publicada pelo `brain-escriba`. Sem fallback para os prompts padrão do superpowers nem para `executing-plans`. Nenhuma etapa, tag, evidência ou handoff mudou.
- 2026-10-29 — protocolo de execução alinhado ao template atual da skill `development-roadmap`: o orquestrador passou de `orquestrador-etapa` para `orquestrador` (`claude --agent orquestrador`), entrou o papel "Revisor de plano" (`revisor-arquitetura`, pré-condição das souls e passo 1 do prompt) e o passo "Plano" diz que o plano passa pela revisão de arquitetura do orquestrador antes de executar. Só "Como usar", "Protocolo de execução" e "Prompt de execução" mudaram; etapas, handoffs e entradas anteriores do histórico seguem como estavam.
