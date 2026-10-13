# Roadmap — M9 Suprimentos: estoque, compras, rouparia e fichas técnicas

> Arquivo: `docs/roadmaps/roadmap-m9-suprimentos.md` · criado em 2026-10-13 · última revisão 2026-10-13
>
> Marco 9 de 11 · mapa dos marcos em [`docs/roadmaps/README.md`](README.md) · marco anterior: [M8 — Restaurante, distribuição e inauguração](roadmap-m8-restaurante-distribuicao.md) · próximo marco: [M10 — Comercial e gestão](roadmap-m10-comercial-gestao.md)
>
> **Pré-requisito:** o gate do marco M8 (última etapa de `docs/roadmaps/roadmap-m8-restaurante-distribuicao.md`) está `#concluida`.

## Como usar

Duas formas, ambas na raiz do repositório:

- **Manual:** cole o prompt de uma etapa numa sessão limpa do Claude Code. Uma etapa por sessão.
- **Automática:** cole o prompt da seção "Execução automática" numa sessão limpa; ela executa até 3 etapas em sequência, um subagente por etapa mais um verificador, e para para revisão. Para não parar em cada pedido de permissão, libere antes os comandos de build/teste do projeto (registrados no `CLAUDE.md`) e o `docker` nas permissões do Claude Code.

Etapas marcadas como paralelizáveis só devem rodar ao mesmo tempo em worktrees git separados; na mesma pasta, rode-as uma de cada vez.

Em ambas, quem executa segue o "Protocolo de execução", atualiza a tag da etapa e preenche "Evidências e handoff". Não pule dependências. Revise e faça commit entre etapas ou lotes — nenhuma sessão faz commit por conta própria.

**Este roadmap foi escrito antes do código existir.** Antes da primeira etapa, sincronize-o com o código real usando a skill `development-roadmap` (modo atualização): confira caminhos, nomes de módulos, APIs públicas e convenções registrados nos handoffs de M0 a M8 e ajuste as etapas pendentes.

## Objetivo

Colocar custos e insumos sob controle (SPEC §9, Fase 3). Ao fim do marco, o hotel cadastra itens, locais e fornecedores, recebe mercadoria por XML de NF-e, transfere entre locais, faz inventário e recebe alertas de estoque mínimo. As baixas acontecem sozinhas a partir dos checklists, das OS de manutenção e das vendas do PDV (fichas técnicas), com custo médio ponderado móvel e CMV. A rouparia acompanha o ciclo de vida do enxoval, a lavanderia terceirizada e a lavanderia do hóspede, e a recepção controla os itens de empréstimo com devolução no check-out.

## Contexto confirmado

- Requisitos: SPEC §5.17 (EST), §5.18 (ROU), §5.19 RF-PDV-07 e RF-PDV-10 (ficha técnica e consumo interno), §5.16 RF-MAN-06 (peças da OS), §5.15 RF-CHK-05 (ações de checklist), §5.8 RF-REC-09 (itens de empréstimo).
- Regras de negócio: SPEC §6 RN-17 (saldo negativo nunca bloqueia saídas automáticas), RN-18 (custo médio ponderado móvel), RN-03 (imutabilidade financeira para lançamentos em conta), RN-12 (ações sensíveis).
- Modelo de domínio: SPEC §8.2.13 (estoque e compras), §8.2.14 (rouparia e lavanderia), §8.2.15 (`Recipe`, `RecipeLine`), §8.2.11 (`ConsumeStockAction`, `LinenExchangeAction`), §8.2.12 (`WorkOrderPart`); eventos em §8.3 (`ChecklistCompleted`, `TabClosed`, `StockBelowMinimum`).
- Arquitetura: ARCHITECTURE §3.1 — regra por camadas (um módulo pode depender de módulos de camada inferior da §3.2 e de qualquer módulo da Plataforma, sem ciclo; aresta nova é registrada na tabela na mesma mudança). `inventory` depende de `shared`, `checklists`, `files`, `notifications`; `linen` depende de `shared`, `rooms`, `checklists`, `inventory`, `reservations`, `billing`; `pos` depende de `inventory`; `maintenance` depende de `inventory`; `frontdesk` depende de `billing` e cuida dos itens de empréstimo, mas **não** pode depender de `inventory`, `linen` nem `housekeeping` (camada superior). ARCHITECTURE §6.2 descreve o fluxo `ChecklistCompleted` → `inventory`/`linen`. ARCHITECTURE §13: alertas de estoque mínimo por evento, com consolidação diária. ARCHITECTURE §14: XMLs de NF-e de entrada guardados em `files`.
- Convenções herdadas (ler nos handoffs e no `CLAUDE.md`): M0 — API, erros RFC 9457, migrações por módulo, `Money`, IDs, `Idempotency-Key`, outbox, ShedLock; M1 — autorização, step-up, auditoria, `files`; M2 — checklists com ações no `ChecklistCompleted`, sincronização offline, notificações; M3 — `billing` (lançamentos por código); M4 — `reservations`; M5 — `frontdesk` (check-out); M6 — `maintenance` corretiva (OS sem peças), `reporting` via views `report_*`; M8 — `pos` (comandas, `TabClosed`, consumo interno sem baixa).
- Comandos de build, teste e lint: os oficiais registrados no `CLAUDE.md` por M0 E01. Testes de integração com Testcontainers (base de M0 E02).
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
Você é o verificador independente da etapa ETAPA do roadmap docs/roadmaps/roadmap-m9-suprimentos.md. Você não participou da implementação e não deve editar nenhum arquivo.
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
Você é o orquestrador do roadmap docs/roadmaps/roadmap-m9-suprimentos.md neste repositório. Seu papel é coordenar subagentes, não implementar: não edite código e mantenha seu contexto enxuto (leia o roadmap e os relatórios dos subagentes, não o código).

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

**Dependências e paralelismo:** nenhuma dentro deste roadmap; exige o gate de M8 `#concluida`. Não paralelizável (cria o módulo).

**Escopo:**

- Pacote `inventory` (a criar) com API pública na raiz, `domain/`, `application/`, `infrastructure/`, `web/`; schema e pasta de migração próprios.
- Entidades `StockItem`, `UnitConversion`, `StockLocation` (tipos: almoxarifado, cozinha, bar, rouparia, carrinho, manutenção), `Supplier`, `SupplierItemLink` (SPEC §8.2.13).
- Carrinho vinculado a um colaborador (`staff`) ou usuário (H3), consultável pela API pública (`findCartOf(executor)`).
- Permissões do catálogo (`inventory.item.manage`, `inventory.location.manage`, `inventory.supplier.manage` ou nomes equivalentes na convenção de M1).
- Endpoints REST de CRUD com paginação por cursor, `ETag` e desativação em vez de exclusão.

**Fora de escopo:** saldos e movimentos (E02); mínimo/máximo por local (E03).

**Passos de implementação:**

1. Confirmar o gate de M8 e sincronizar caminhos com os handoffs.
2. Escrever testes de integração dos endpoints (criação, validação de CNPJ, conversão de unidade, item duplicado por código → `409`).
3. Implementar módulo, migrações, permissões e endpoints; regenerar `api/openapi.yaml`.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "qual o layout de módulo, convenção de permissões e padrão de testes de integração registrados nos handoffs de M1 e M2?"; "como o `staff` expõe colaboradores na API pública?".
- Partes paralelas: nenhuma.
- Foco do verificador: dependência de `inventory` para módulo de camada igual ou superior (fora da Plataforma) ou aresta nova não registrada na ARCHITECTURE §3.1; exclusão física de cadastros; escrita sem permissão.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E01 — "Cadastros de estoque" do roadmap docs/roadmaps/roadmap-m9-suprimentos.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e as hipóteses —, a seção E01 inteira, docs/SPEC.md §5.17 e §8.2.13 e a linha de inventory em docs/ARCHITECTURE.md §3.1. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que a última etapa (gate) de docs/roadmaps/roadmap-m8-restaurante-distribuicao.md está #concluida; se não estiver, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar o módulo inventory com itens (unidade, conversões, NCM, categoria), locais (incluindo carrinho de camareira vinculado a colaborador) e fornecedores com vínculos fornecedor ↔ item, com endpoints REST, permissões e migrações próprias. Não implemente saldos nem movimentos. Vincular o carrinho ao colaborador pode usar a API pública de staff (Plataforma, permitido pela regra por camadas da docs/ARCHITECTURE.md §3.1); se acrescentar essa dependência, registre a aresta na tabela §3.1.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E01 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume o layout de módulo, a convenção de permissões e o padrão de testes de integração registrados nos handoffs de M1 e M2;
- escreva os testes de integração primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em dependência que viole a regra por camadas ou não registrada na §3.1, exclusão física e escrita sem permissão.

Critérios de aceite (detalhes na seção E01):
- CRUD de itens, locais e fornecedores com paginação, ETag e desativação;
- código de item duplicado → 409; CNPJ inválido → 400; escrita sem permissão → 403;
- ModularityTest passa; build, testes e verificação do contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E01 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Endpoints de itens, locais e fornecedores existem em `/api/v1`, estão no contrato e são cobertos por testes de integração.
- [ ] Código de item duplicado → `409`; CNPJ inválido → `400`; escrita sem a permissão → `403`, todos no formato de erro padrão.
- [ ] Item, local e fornecedor só são desativados, nunca excluídos.
- [ ] Conversão de unidade de compra (ex.: caixa com 12) converte para a unidade de controle em teste unitário.
- [ ] Um carrinho pode ser vinculado a um executor e a API pública devolve o carrinho de um executor.
- [ ] `ModularityTest` passa; build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Vínculo carrinho ↔ colaborador pode exigir dependência nova: guardar só o ID e resolver na API de quem chama (H3).

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E02 — Movimentos, saldo e custo médio — #pendente

**Objetivo:** registrar movimentos de estoque imutáveis (entrada manual, saída manual, perda/quebra com motivo, transferência entre locais com quem entregou e quem recebeu), manter o saldo por item e local, recalcular o custo médio ponderado móvel em cada entrada e expor uma API pública de baixa idempotente para outros módulos.

**Por que agora:** é o núcleo do estoque; todas as baixas automáticas, compras e inventário escrevem por aqui. O contrato da API de baixa será usado por `checklists`, `maintenance`, `pos`, `linen` e `events`.

**Dependências e paralelismo:** E01. Não paralelizável.

**Escopo:**

- `StockMovement` (tipos: entrada, saída, perda, transferência de saída e de entrada, ajuste) com `sourceType`/`sourceId`/linha e chave única de idempotência por origem.
- `StockLevel` por item e local, atualizado na mesma transação do movimento, com lock curto no par item/local.
- Custo médio ponderado móvel (RN-18) recalculado em entradas; saídas valorizadas pelo custo vigente; regra para entrada quando o saldo está negativo registrada como decisão.
- Saldo negativo permitido em saídas automáticas (RN-17); saída manual com saldo insuficiente também permitida, com alerta (decisão registrada).
- API pública `InventoryApi` (nome conforme convenção) com `consume(source, location, lines)` e `receive(...)`, idempotentes por origem.
- Endpoints REST de movimentos manuais e transferências; consulta de saldo e de movimentação por período (base de RF-EST-10).
- Auditoria dos movimentos manuais (M1).

**Fora de escopo:** alertas de mínimo (E03); ajuste de inventário (E04); recebimento por NF-e (E06).

**Passos de implementação:**

1. Testes de domínio do custo médio (sequências de entradas e saídas, inclusive com saldo negativo) e de idempotência por origem.
2. Testes de integração de concorrência: duas baixas simultâneas no mesmo item/local mantêm o saldo correto.
3. Implementar, regenerar contrato, documentar a API pública no `CLAUDE.md`.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação: nenhuma; regras em RN-17, RN-18 e SPEC §8.2.13.
- Partes paralelas: nenhuma.
- Foco do verificador: custo médio errado após saldo negativo; baixa repetida pela mesma origem criando dois movimentos; `UPDATE` ou `DELETE` em movimentos; uso de `Double`; corrida entre baixas simultâneas.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E02 — "Movimentos, saldo e custo médio" do roadmap docs/roadmaps/roadmap-m9-suprimentos.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e as restrições —, a seção E02 inteira, o "Evidências e handoff" de E01 e RN-17 e RN-18 em docs/SPEC.md §6. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: registrar movimentos de estoque imutáveis (entrada, saída, perda com motivo, transferência com quem entregou e quem recebeu), manter o saldo por item e local, recalcular o custo médio ponderado móvel nas entradas e expor na API pública de inventory uma baixa idempotente por origem (sourceType, sourceId, linha) que nunca falha por saldo insuficiente. Não implemente alertas de mínimo, inventário nem NF-e.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E02 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva primeiro os testes de domínio do custo médio e os testes de integração de idempotência e concorrência, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em custo médio após saldo negativo, baixa duplicada pela mesma origem, movimentos alteráveis e corrida entre baixas.

Critérios de aceite (detalhes na seção E02):
- custo médio ponderado correto nas sequências de teste; saída com saldo insuficiente deixa saldo negativo sem erro;
- mesma origem chamada duas vezes gera um único movimento; baixas simultâneas mantêm o saldo correto;
- transferência registra quem entregou e quem recebeu; movimentos não aceitam UPDATE/DELETE;
- build, testes e verificação do contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E02 (snapshot, arquivos alterados, decisões sobre custo com saldo negativo, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

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

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Custo médio com saldo negativo não tem regra na SPEC: adotar "entrada com saldo negativo usa o custo da entrada para a parte que zera o saldo" ou equivalente, registrar como decisão e pedir confirmação na revisão humana.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E03 — Estoque mínimo, alertas e sugestão de compra — #pendente

**Objetivo:** configurar mínimo e máximo por item e local, publicar `StockBelowMinimum` quando um movimento cruza o mínimo, consolidar os alertas diariamente e oferecer a sugestão de compra (quantidade até o máximo, agrupada por fornecedor).

**Por que agora:** saldo negativo é permitido (RN-17), então o alerta é o controle que sobra; precisa existir antes das baixas automáticas em volume.

**Dependências e paralelismo:** E02. Pode correr em paralelo com E04, E05, E07, E08 e E12 (worktrees separados); conflito em migrações e na API pública de `inventory`.

**Escopo:**

- Mínimo e máximo por item e local (`StockLevel`); endpoint de configuração.
- Evento `StockBelowMinimum` publicado uma vez por cruzamento (não a cada movimento abaixo do mínimo) e também quando o saldo fica negativo.
- Listener do próprio `inventory` para `StockBelowMinimum` que chama a API pública de `notifications` (H2), destinado a quem tem a permissão de almoxarife.
- Consolidação diária (job com ShedLock ou listener de `BusinessDateAdvanced`, conforme a convenção de M6) com a lista de itens abaixo do mínimo.
- Endpoint de sugestão de compra por fornecedor (vínculos de E01, prazo de entrega).

**Fora de escopo:** criação automática do pedido de compra (E05 aceita a sugestão como rascunho só se for trivial; senão fica manual).

**Passos de implementação:**

1. Ler no handoff de M2 a API pública de `notifications` para criar notificação (H2); a aresta `inventory → notifications` já está na ARCHITECTURE §3.1.
2. Testes de integração: cruzar o mínimo publica um evento; continuar abaixo não publica outro; voltar acima e cruzar de novo publica.
3. Implementar configuração, evento, consolidação e sugestão.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "qual é a API pública de `notifications` para criar uma notificação por permissão ou papel, segundo o handoff de M2 e o código?".
- Partes paralelas: nenhuma.
- Foco do verificador: alerta repetido a cada movimento; evento publicado fora da transação do movimento; `notifications` escutando evento de `inventory` (dependência invertida errada).

**Prompt para uma sessão limpa:**

```text
Execute a etapa E03 — "Estoque mínimo, alertas e sugestão de compra" do roadmap docs/roadmaps/roadmap-m9-suprimentos.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a hipótese H2 —, a seção E03 inteira, o "Evidências e handoff" de E01 e E02 e RF-EST-07 em docs/SPEC.md §5.17. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E02 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: configurar mínimo e máximo por item e local, publicar StockBelowMinimum uma vez por cruzamento do mínimo (e quando o saldo fica negativo), consolidar os alertas diariamente e expor a sugestão de compra agrupada por fornecedor. O alerta chega ao almoxarife por um listener do próprio inventory que chama a API pública de notifications (dependência inventory → notifications já prevista na docs/ARCHITECTURE.md §3.1); notifications não escuta eventos de inventory.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E03 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume a API pública de notifications para criar notificação por permissão (handoff de M2);
- escreva os testes de integração primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em alerta repetido, evento fora da transação e notifications escutando evento de inventory.

Critérios de aceite (detalhes na seção E03):
- cruzar o mínimo publica um único StockBelowMinimum até o saldo voltar acima do mínimo;
- o alerta chega a uma notificação do almoxarife (teste de módulo);
- consolidação diária lista os itens abaixo do mínimo; sugestão de compra agrupa por fornecedor e completa até o máximo;
- ModularityTest, build, testes e verificação do contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E03 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Movimento que leva o saldo abaixo do mínimo publica `StockBelowMinimum`; movimentos seguintes abaixo do mínimo não publicam outro até o saldo voltar acima (teste de integração).
- [ ] Saldo negativo publica o alerta mesmo sem mínimo configurado.
- [ ] O alerta resulta numa notificação para quem tem a permissão de almoxarife (teste de módulo com `Scenario` do Modulith ou equivalente).
- [ ] A consolidação diária gera a lista de itens abaixo do mínimo uma vez por dia (ShedLock ou listener idempotente).
- [ ] `GET` da sugestão de compra devolve itens agrupados por fornecedor com quantidade até o máximo.
- [ ] `ModularityTest` passa; `notifications` não depende de `inventory`.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- API de `notifications` diferente da esperada: ajustar a chamada conforme o handoff de M2, sem inverter a direção da dependência.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E04 — Inventário por local — #pendente

**Objetivo:** permitir a contagem de inventário por local (`InventoryCount`), comparar contado × esperado, fechar a contagem gerando movimentos de ajuste sob permissão ou step-up e emitir o relatório de divergências.

**Por que agora:** com saldo negativo permitido, o inventário é como o saldo volta à realidade (RN-17 "pede inventário").

**Dependências e paralelismo:** E02. Pode correr em paralelo com E03, E05, E07, E08 e E12 (worktrees separados); conflito em migrações de `inventory`.

**Escopo:**

- `InventoryCount` e `InventoryCountLine` (SPEC §8.2.13): abrir (congela o esperado), registrar contagens, fechar com aprovação.
- Fechamento gera movimentos de ajuste valorizados pelo custo médio, numa transação.
- Ajuste é ação sensível (RF-ACS-08, RF-EST-06): exige a permissão ou `X-Approval-Token` (step-up de M1); auditoria com solicitante e aprovador.
- Movimentos entre a abertura e o fechamento: tratamento registrado como decisão (provável: o esperado é recalculado no fechamento com os movimentos do período).
- Relatório de divergências por contagem (quantidade e valor).

**Fora de escopo:** contagem offline no app (não está no escopo offline da ARCHITECTURE §9).

**Passos de implementação:**

1. Testes de integração: fechar sem permissão → `403 APPROVAL_REQUIRED`; com aprovação → ajustes gerados; contagem fechada não reabre.
2. Implementar fluxo, ajustes e relatório.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; convenção de step-up no handoff de M1.
- Partes paralelas: nenhuma.
- Foco do verificador: ajuste sem aprovação; movimentos ocorridos durante a contagem ignorados ou contados em dobro; contagem fechada editável.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E04 — "Inventário por local" do roadmap docs/roadmaps/roadmap-m9-suprimentos.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E04 inteira, o "Evidências e handoff" de E02, a convenção de step-up no handoff de M1 (docs/roadmaps/roadmap-m1-acesso-configuracao.md) e RF-EST-06 em docs/SPEC.md §5.17. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E02 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: contagem de inventário por local com esperado congelado na abertura, fechamento que gera ajustes valorizados pelo custo médio sob permissão ou step-up (ação sensível, auditada com solicitante e aprovador) e relatório de divergências em quantidade e valor. Não implemente contagem offline.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E04 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de integração primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em ajuste sem aprovação, movimentos durante a contagem e contagem fechada editável.

Critérios de aceite (detalhes na seção E04):
- fechar sem permissão → 403 com code APPROVAL_REQUIRED; com X-Approval-Token válido → ajustes gerados e auditados;
- movimentos ocorridos durante a contagem são tratados conforme a decisão registrada, com teste;
- contagem fechada não aceita alterações (409); relatório de divergências em quantidade e valor;
- build, testes e verificação do contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E04 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Fechar a contagem sem a permissão de ajuste → `403` com `code` `APPROVAL_REQUIRED`; com `X-Approval-Token` válido → movimentos de ajuste gerados e auditados com solicitante e aprovador.
- [ ] Ajustes valorizados pelo custo médio vigente.
- [ ] Movimentos ocorridos entre abertura e fechamento são tratados conforme a decisão registrada no handoff, com teste.
- [ ] Contagem fechada não aceita novas contagens nem reabertura (`409`).
- [ ] Relatório de divergências mostra quantidade e valor por item.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Contagem longa com operação em andamento: o tratamento por recálculo no fechamento evita bloquear o local.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E05 — Pedido de compra simples — #pendente

**Objetivo:** criar o pedido de compra (fornecedor, itens, quantidades, preços, previsão) com os estados rascunho, enviado, recebido parcial, recebido e cancelado (RF-EST-09), com recebimento manual que gera entradas no estoque.

**Por que agora:** o recebimento por NF-e (E06) confere contra o pedido.

**Dependências e paralelismo:** E02. Pode correr em paralelo com E03, E04, E07, E08 e E12 (worktrees separados); conflito em migrações de `inventory`.

**Escopo:**

- `PurchaseOrder` e `PurchaseOrderLine` com número legível (gerador de M0 E03).
- Transições válidas e `409` nas inválidas; recebimento parcial acumula `receivedQuantity`.
- Recebimento manual gera entradas via E02 (com custo do pedido) e atualiza o estado.
- Criação de rascunho a partir da sugestão de compra de E03, se E03 já estiver concluída; senão, registrar como nota para E13/E14.
- PDF do pedido: só se o mecanismo de documentos de M5 (`printing`) estiver acessível a `inventory` pelo grafo; caso contrário, fora de escopo e registrado.

**Fora de escopo:** envio do pedido por e-mail ao fornecedor; recebimento por XML (E06).

**Passos de implementação:**

1. Testes de domínio das transições e de integração do recebimento parcial e total.
2. Implementar, regenerar contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma.
- Partes paralelas: nenhuma.
- Foco do verificador: receber pedido cancelado; recebimento acima do pedido sem regra; entrada sem custo.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E05 — "Pedido de compra simples" do roadmap docs/roadmaps/roadmap-m9-suprimentos.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E05 inteira, o "Evidências e handoff" de E01 e E02 (e de E03, se concluída) e RF-EST-09 em docs/SPEC.md §5.17. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E02 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: pedido de compra com número legível, linhas de item, quantidade e preço, estados rascunho, enviado, recebido parcial, recebido e cancelado, e recebimento manual que gera entradas no estoque com o custo do pedido. Não implemente recebimento por XML de NF-e (E06) nem envio por e-mail.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E05 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de transição e de recebimento primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em receber pedido cancelado, recebimento acima do pedido e entrada sem custo.

Critérios de aceite (detalhes na seção E05):
- transições inválidas → 409 com code estável;
- recebimento parcial muda para recebido parcial e o total para recebido, gerando entradas com custo;
- build, testes e verificação do contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E05 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Pedido recebe número legível sequencial.
- [ ] Transições fora do ciclo rascunho → enviado → recebido parcial → recebido (ou → cancelado) devolvem `409` com `code` estável.
- [ ] Recebimento parcial atualiza `receivedQuantity`, muda o estado para recebido parcial e gera entradas com o custo da linha; o recebimento do restante muda para recebido.
- [ ] Recebimento acima da quantidade do pedido segue a regra registrada no handoff (rejeitar ou aceitar com alerta), com teste.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Nenhum relevante além dos do verificador.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E06 — Recebimento por XML de NF-e — #pendente

**Objetivo:** importar o XML da NF-e de entrada, guardar o arquivo em `files`, vincular cada item do fornecedor ao item interno (memorizando o vínculo para as próximas notas), conferir contra o pedido de compra e lançar as entradas com custo.

**Por que agora:** é a forma principal de entrada (RF-EST-03) e alimenta o custo médio com valores reais.

**Dependências e paralelismo:** E05. Não paralelizável com E05.

**Escopo:**

- Upload do XML (H6) com limite de tamanho; armazenamento em `files` com a finalidade "NF-e de entrada" (ARCHITECTURE §14).
- Parser do `nfeProc`: chave de acesso, emitente (CNPJ → fornecedor), data, itens (código, descrição, NCM, unidade, quantidade, valor), total.
- `GoodsReceipt` e `GoodsReceiptLine` em rascunho; vínculo `SupplierItemLink` sugerido pelo histórico e confirmado pelo usuário, com conversão de unidade.
- Conferência com o pedido (quando informado): divergências de item, quantidade e preço destacadas.
- `post()` gera entradas (E02), atualiza o pedido (E05) e impede reimportar a mesma chave de acesso (`409`).

**Fora de escopo:** validação de assinatura e consulta à SEFAZ; contas a pagar.

**Passos de implementação:**

1. Criar XMLs fictícios de teste (nota simples, nota com unidade de caixa, nota de outro layout).
2. Testes: importação, vínculo memorizado na segunda nota, conferência com pedido, chave duplicada → `409`, layout inválido → `400` com `code` estável.
3. Implementar parser, rascunho, vínculo e lançamento.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "como `files` (M1) recebe upload, define finalidade e limita tipo e tamanho, segundo o handoff de M1?".
- Partes paralelas: nenhuma.
- Foco do verificador: XXE no parser XML; mesma nota lançada duas vezes; custo unitário errado após conversão de unidade; XML real de terceiros no repositório.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E06 — "Recebimento por XML de NF-e" do roadmap docs/roadmaps/roadmap-m9-suprimentos.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a hipótese H6 —, a seção E06 inteira, o "Evidências e handoff" de E01, E02 e E05, RF-EST-03 e RF-EST-09 em docs/SPEC.md §5.17 e docs/ARCHITECTURE.md §14. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E05 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: importar o XML da NF-e de entrada (modelo 55, layout 4.00), guardá-lo em files, criar o recebimento em rascunho, sugerir e memorizar o vínculo item do fornecedor ↔ item interno com conversão de unidade, conferir contra o pedido de compra e, ao lançar, gerar as entradas com custo e atualizar o pedido. Não valide assinatura nem consulte a SEFAZ. Use apenas XMLs fictícios nos testes.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E06 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume como files recebe upload, finalidade e limites, segundo o handoff de M1;
- escreva os testes primeiro (importação, vínculo memorizado, conferência, chave duplicada, layout inválido, XXE), implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em XXE, nota lançada duas vezes, custo após conversão de unidade e XML real no repositório.

Critérios de aceite (detalhes na seção E06):
- XML válido gera recebimento em rascunho com fornecedor e itens; a segunda nota do mesmo fornecedor reaproveita o vínculo;
- lançar gera entradas com custo convertido e atualiza o pedido; mesma chave de acesso → 409;
- XML com DOCTYPE/entidade externa é rejeitado; layout inválido → 400 com code estável;
- build, testes e verificação do contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E06 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

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

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Variações de layout entre emissores: testes com as variações mais comuns e erro estável para o resto (H6).

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E07 — Baixas por checklist e por OS — #pendente

**Objetivo:** consumir as ações `ConsumeStock` do `ChecklistCompleted` em `inventory` (baixa do carrinho do executor) e registrar o consumo de peças nas OS de `maintenance` (RF-MAN-06), ambos pela API de baixa idempotente de E02.

**Por que agora:** fecha as ações de checklist previstas desde M2 e a pendência de peças deixada em M6.

**Dependências e paralelismo:** E02. Pode correr em paralelo com E03, E04, E05, E08 e E12 (worktrees separados); conflito com E08 se ambos alterarem o payload de `ChecklistCompleted` em `checklists`.

**Escopo:**

- Listener em `inventory` para `ChecklistCompleted` (`inventory` depende de `checklists` ✓): para cada resposta com `ConsumeStock`, baixa do carrinho do executor (H3) com origem `(checklistExecution, item)`.
- Execuções feitas offline (M2) usam o horário corrigido do dispositivo como `occurredAt` do movimento.
- `WorkOrderPart` em `maintenance` (`maintenance` depende de `inventory` ✓): adicionar peça à OS chama a baixa com origem `(workOrder, part)`; remover peça gera movimento inverso; custo da peça soma ao custo da OS (RF-MAN-02).
- Saldo negativo nunca bloqueia (RN-17); alerta de E03 se já existir.

**Fora de escopo:** troca de enxoval (E08); baixa por ficha técnica (E12).

**Passos de implementação:**

1. Testes de módulo: `ChecklistCompleted` com `ConsumeStock` gera movimento; reentrega do mesmo evento não gera outro; sem carrinho vinculado → local padrão e alerta.
2. Testes de integração da OS com peças (adicionar, remover, custo).
3. Implementar listener e a parte de `maintenance`; regenerar contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "qual o formato do `ChecklistCompleted` e das ações `ConsumeStock` publicado por `checklists` (handoff de M2)?"; "como a OS de `maintenance` registra custo e estados (handoff de M6)?".
- Partes paralelas: nenhuma.
- Foco do verificador: baixa duplicada na reentrega do evento; listener que falha e perde o evento; `checklists` passando a depender de `inventory`; custo da OS sem as peças.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E07 — "Baixas por checklist e por OS" do roadmap docs/roadmaps/roadmap-m9-suprimentos.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a hipótese H3 —, a seção E07 inteira, o "Evidências e handoff" de E02, docs/ARCHITECTURE.md §6.2 e RF-CHK-05 e RF-MAN-06 em docs/SPEC.md. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E02 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: inventory escuta ChecklistCompleted e, para cada ação ConsumeStock, dá baixa no carrinho do executor de forma idempotente por (execução, item), usando o horário corrigido de execuções offline; maintenance registra peças da OS pela API de baixa de inventory, com remoção gerando movimento inverso e custo somado à OS. checklists não pode passar a depender de inventory. Não implemente troca de enxoval nem fichas técnicas.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E07 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume o formato do ChecklistCompleted e de ConsumeStock (handoff de M2) e como a OS registra custo e estados (handoff de M6);
- escreva os testes de módulo e de integração primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em baixa duplicada na reentrega, evento perdido, dependência invertida e custo da OS sem peças.

Critérios de aceite (detalhes na seção E07):
- ChecklistCompleted com ConsumeStock gera um movimento no carrinho do executor; reentrega não duplica;
- sem carrinho vinculado, a baixa vai ao local padrão e gera alerta;
- adicionar e remover peça da OS movimenta o estoque e atualiza o custo da OS;
- ModularityTest, build, testes e verificação do contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E07 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] `ChecklistCompleted` com uma ação `ConsumeStock` gera exatamente um movimento de saída no carrinho do executor, com `occurredAt` igual ao horário corrigido da execução (teste de módulo).
- [ ] Reentregar o mesmo evento não cria segundo movimento.
- [ ] Executor sem carrinho vinculado: baixa no local padrão configurado e alerta registrado.
- [ ] Adicionar peça à OS gera saída com origem na OS; remover gera movimento inverso; o custo da OS inclui as peças pelo custo médio.
- [ ] `checklists` não depende de `inventory` (`ModularityTest`).
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Se o payload do `ChecklistCompleted` de M2 não trouxer o executor ou o insumo, a etapa acrescenta os campos em `checklists` de forma compatível e registra no handoff.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E08 — Enxoval e ciclo de vida — #pendente

**Objetivo:** criar o módulo `linen` com tipos de enxoval, enxoval-padrão por tipo de UH, quantidades por status (disponível, em uso, suja, na lavanderia, em reparo, descartada), movimentos de status, troca de enxoval pelo `ChecklistCompleted`, baixa com motivo e reposição a partir do estoque.

**Por que agora:** base da lavanderia terceirizada (E09) e do controle de perdas (RF-ROU-06).

**Dependências e paralelismo:** E02. Pode correr em paralelo com E03, E04, E05, E07 e E12 (worktrees separados); conflito com E07 se ambos alterarem `checklists`.

**Escopo:**

- Pacote `linen` (a criar), schema e migrações próprios; entidades `LinenType`, `LinenPar`, `LinenStock`, `LinenMovement` (SPEC §8.2.14).
- Movimentos de status sempre em pares (de → para) com quantidade, motivo e origem; quantidades nunca negativas por status (decisão registrada se a troca offline chegar com saldo insuficiente: aceitar e sinalizar, coerente com RN-22).
- Listener de `ChecklistCompleted` (`linen` depende de `checklists` ✓) para `LinenExchange`: retiradas vão de em uso → suja; colocadas vão de disponível → em uso; idempotente por (execução, item).
- Baixa (disponível/suja/em reparo → descartada) com motivo e permissão.
- Reposição (H7): enxoval comprado sai do estoque via API de `inventory` e entra como disponível.

**Fora de escopo:** romaneios (E09); lavanderia do hóspede (E10).

**Passos de implementação:**

1. Testes de domínio dos movimentos de status e de integração do listener (incluindo reentrega).
2. Implementar módulo, endpoints e listener; regenerar contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "qual o formato da ação `LinenExchange` no `ChecklistCompleted` (handoff de M2)?".
- Partes paralelas: nenhuma.
- Foco do verificador: troca duplicada na reentrega; quantidade total de um tipo mudando sem baixa ou reposição; reposição sem saída no estoque.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E08 — "Enxoval e ciclo de vida" do roadmap docs/roadmaps/roadmap-m9-suprimentos.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a hipótese H7 —, a seção E08 inteira, o "Evidências e handoff" de E02, docs/SPEC.md §5.18 e §8.2.14 e a linha de linen em docs/ARCHITECTURE.md §3.1. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E02 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar o módulo linen com tipos de enxoval, enxoval-padrão por tipo de UH, quantidades por status, movimentos de status com motivo e origem, troca de enxoval idempotente a partir das ações LinenExchange do ChecklistCompleted, baixa com motivo e reposição a partir do estoque via API de inventory. Não implemente romaneios nem lavanderia do hóspede.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E08 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume o formato da ação LinenExchange no ChecklistCompleted (handoff de M2);
- escreva os testes de domínio e de integração primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em troca duplicada, total que muda sem baixa ou reposição e reposição sem saída no estoque.

Critérios de aceite (detalhes na seção E08):
- troca por checklist move retiradas para suja e colocadas para em uso, uma única vez por execução;
- a soma por tipo só muda com baixa (descartada) ou reposição;
- reposição gera saída no estoque e entrada como disponível;
- ModularityTest, build, testes e verificação do contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E08 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] `ChecklistCompleted` com `LinenExchange` move as retiradas de em uso → suja e as colocadas de disponível → em uso; reentrega não duplica (teste de módulo).
- [ ] A soma das quantidades de um tipo em todos os status só muda com baixa (para descartada) ou reposição (teste).
- [ ] Baixa exige motivo e permissão.
- [ ] Reposição gera saída do item correspondente em `inventory` e entrada como disponível em `linen`.
- [ ] Enxoval-padrão configurável por tipo de UH.
- [ ] `ModularityTest` passa; build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Troca offline com saldo insuficiente em disponível: aceitar e sinalizar (RN-22), nunca rejeitar a execução.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E09 — Lavanderia terceirizada — #pendente

**Objetivo:** registrar romaneios de envio à lavanderia terceirizada (por peças e/ou kg), o retorno com conferência, as divergências (faltantes, danificadas, manchadas) e o custo pela tabela do fornecedor (RF-ROU-03).

**Por que agora:** completa o ciclo suja → lavanderia → disponível do enxoval.

**Dependências e paralelismo:** E08. Pode correr em paralelo com E10 (worktrees separados); conflito em migrações de `linen`.

**Escopo:**

- `LaundryShipment` e `LaundryShipmentLine` com número legível; fornecedor de `inventory` (`linen` depende de `inventory` ✓).
- Envio move suja → na lavanderia; retorno move na lavanderia → disponível (conferidas), → em reparo (danificadas/manchadas) e registra faltantes como divergência pendente (sem sumir do total).
- Tabela de preços por fornecedor (por peça e/ou kg) e custo do romaneio.
- Resolução de faltantes: retorno tardio ou baixa como perdida.

**Fora de escopo:** contas a pagar do fornecedor.

**Passos de implementação:**

1. Testes de integração: envio, retorno integral, retorno com faltantes e danificadas, custo por kg e por peça.
2. Implementar; regenerar contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma.
- Partes paralelas: nenhuma.
- Foco do verificador: faltantes sumindo do total; retorno registrado duas vezes; custo calculado em ponto flutuante.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E09 — "Lavanderia terceirizada" do roadmap docs/roadmaps/roadmap-m9-suprimentos.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E09 inteira, o "Evidências e handoff" de E08 e RF-ROU-03 em docs/SPEC.md §5.18. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E08 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: romaneio de envio à lavanderia terceirizada por peças e/ou kg (suja → na lavanderia), retorno com conferência (→ disponível, → em reparo para danificadas e manchadas, faltantes como divergência pendente), resolução de faltantes e custo pela tabela de preços do fornecedor. Não implemente contas a pagar.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E09 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de integração primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em faltantes sumindo do total, retorno duplicado e custo em ponto flutuante.

Critérios de aceite (detalhes na seção E09):
- envio e retorno movimentam os status corretos; faltantes ficam pendentes até retorno tardio ou baixa;
- retorno de romaneio já retornado → 409;
- custo por peça e por kg calculado com Money;
- build, testes e verificação do contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E09 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Envio move as quantidades de suja → na lavanderia.
- [ ] Retorno com conferência move conferidas → disponível e danificadas/manchadas → em reparo; faltantes ficam como divergência pendente sem alterar a soma total do tipo.
- [ ] Divergência pendente resolvida por retorno tardio ou baixa como perdida (com motivo).
- [ ] Registrar o retorno de um romaneio já retornado → `409`.
- [ ] Custo calculado pela tabela do fornecedor por peça e por kg, com `Money`.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Romaneio por kg não permite conferência por peça: conferência só em quantidade de peças, kg para custo.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E10 — Lavanderia do hóspede — #pendente

**Objetivo:** registrar pedidos de lavanderia do hóspede vinculados à reserva, com itens da tabela de preços, estados (recolhido, enviado, retornado, entregue) e lançamento do total na conta da reserva (RF-ROU-05).

**Por que agora:** é receita do hotel e depende só do módulo `linen` e das APIs de `reservations` e `billing`.

**Dependências e paralelismo:** E08. Pode correr em paralelo com E09 (worktrees separados); conflito em migrações de `linen`.

**Escopo:**

- `GuestLaundryOrder`, `GuestLaundryLine`, `LaundryPriceItem` (SPEC §8.2.14).
- Validação de reserva hospedada pela API de `reservations` (`linen` depende de `reservations` ✓).
- Lançamento na conta pela API de `billing` (`linen` depende de `billing` ✓) com código de lançamento de lavanderia e origem "lavanderia" (RF-CTA-02), idempotente; o roteamento de contas de M3 se aplica.
- Cancelamento antes do lançamento; depois do lançamento, a correção é estorno em `billing`.

**Fora de escopo:** NFS-e da lavanderia (segue o fluxo fiscal de M7 pela conta).

**Passos de implementação:**

1. Testes de integração: pedido para reserva não hospedada → `409`; entrega lança na conta uma única vez; estados inválidos → `409`.
2. Implementar; regenerar contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "como `billing` expõe o lançamento por código e origem e qual código de lançamento usar (handoff de M3)?".
- Partes paralelas: nenhuma.
- Foco do verificador: lançamento duplicado; escrita direta em tabela de `billing`; dados pessoais do hóspede expostos em endpoints do app operacional (RN-13).

**Prompt para uma sessão limpa:**

```text
Execute a etapa E10 — "Lavanderia do hóspede" do roadmap docs/roadmaps/roadmap-m9-suprimentos.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e as restrições —, a seção E10 inteira, o "Evidências e handoff" de E08 e RF-ROU-05 em docs/SPEC.md §5.18. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E08 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: pedido de lavanderia do hóspede vinculado a reserva hospedada, com itens da tabela de preços, estados recolhido → enviado → retornado → entregue e lançamento do total na conta pela API de billing, idempotente e com origem lavanderia. Não acesse tabelas de billing diretamente e não exponha dados pessoais do hóspede a perfis operacionais (RN-13).

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E10 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume como billing expõe o lançamento por código e origem (handoff de M3);
- escreva os testes de integração primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em lançamento duplicado, escrita direta em billing e exposição de dados pessoais.

Critérios de aceite (detalhes na seção E10):
- pedido para reserva não hospedada → 409;
- o total é lançado na conta uma única vez, com origem lavanderia;
- transições inválidas → 409; cancelamento após lançamento exige estorno em billing;
- build, testes e verificação do contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E10 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Pedido para reserva que não está hospedada → `409` com `code` estável.
- [ ] O total do pedido é lançado na conta da reserva uma única vez (repetição da operação não duplica), com código de lavanderia e origem "lavanderia".
- [ ] Transições fora de recolhido → enviado → retornado → entregue → `409`.
- [ ] Cancelar pedido já lançado devolve `409` orientando o estorno em `billing`.
- [ ] Endpoints acessíveis à camareira não expõem nome, documento ou contato do hóspede (RN-13).
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Momento do lançamento (recolhimento ou entrega) não é definido na SPEC: adotar a entrega e registrar como decisão.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E11 — Itens de empréstimo — #pendente

**Objetivo:** controlar no módulo `frontdesk` os itens de empréstimo (berço, ferro, adaptador) por UH e reserva, com entrega e devolução, e fazer o check-out exigir decisão sobre os itens não devolvidos (RF-REC-09).

**Por que agora:** é a última funcionalidade da Fase 3 e não depende das demais etapas deste marco.

**Dependências e paralelismo:** nenhuma dentro deste roadmap; exige o gate de M8 `#concluida`. Pode correr em paralelo com qualquer etapa de `inventory` (worktrees separados); conflito com nenhuma, salvo `CLAUDE.md`.

**Escopo:**

- Módulo dono: `frontdesk` (H4), em subpacotes internos próprios (a criar). Catálogo de itens de empréstimo com quantidade disponível; empréstimo vinculado a reserva e UH, com quem entregou e quando; devolução; endpoints REST e permissões no catálogo de M1.
- Conferência no check-out de M5: o check-out lista os itens pendentes e exige decisão (devolvido, cobrar na conta ou baixar como perda) antes de concluir; a cobrança vai à conta pela API de `billing` (`frontdesk` → `billing` já previsto na ARCHITECTURE §3.1), com `Idempotency-Key`.
- Recado à governança para entregar o item antes da chegada pode reutilizar os recados de M5 (RF-REC-07), que também são do `frontdesk`.

**Fora de escopo:** controle de estoque dos itens em `inventory` (são ativos de empréstimo, não insumos); visão dos empréstimos no app da governança.

**Passos de implementação:**

1. Ler no handoff de M5 como o check-out valida pré-condições e onde acrescentar a nova pendência.
2. Testes: empréstimo indisponível → `409`; check-out com item pendente sem decisão → `409`; cobrança lança na conta uma única vez; check-out sem itens emprestados se comporta como antes.
3. Implementar catálogo, empréstimo, devolução e a conferência no check-out; regenerar contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "como o check-out de `frontdesk` (handoff de M5) valida pré-condições e lança valores na conta via `billing`?".
- Partes paralelas: nenhuma.
- Foco do verificador: check-out concluído com item pendente sem decisão; cobrança duplicada em repetição da requisição; regressão no check-out sem itens; `frontdesk` dependendo de módulo de camada superior (`housekeeping`, `inventory`, `linen`).

**Prompt para uma sessão limpa:**

```text
Execute a etapa E11 — "Itens de empréstimo" do roadmap docs/roadmaps/roadmap-m9-suprimentos.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a hipótese H4 —, a seção E11 inteira, RF-REC-06 e RF-REC-09 em docs/SPEC.md §5.8 e a linha de frontdesk em docs/ARCHITECTURE.md §3.1. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que a última etapa (gate) de docs/roadmaps/roadmap-m8-restaurante-distribuicao.md está #concluida; se não estiver, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: itens de empréstimo por UH e reserva no módulo frontdesk (catálogo, entrega, devolução, disponibilidade) e, no check-out, conferência dos itens não devolvidos, exigindo decisão (devolvido, cobrar na conta via billing com Idempotency-Key ou baixar como perda) antes de concluir. Não use housekeeping nem inventory; o check-out sem itens emprestados deve continuar idêntico.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E11 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume como o check-out de frontdesk valida pré-condições e lança valores na conta (handoff de M5);
- escreva os testes primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md, incluindo a suíte inteira de frontdesk;
- verificação independente com o Prompt do verificador, com foco em check-out concluído com pendência, cobrança duplicada e regressão no check-out sem itens.

Critérios de aceite (detalhes na seção E11):
- empréstimo de item sem disponibilidade → 409; devolução restaura a disponibilidade;
- check-out com item pendente sem decisão → 409 com code estável; com decisão, conclui;
- cobrança lança na conta uma única vez; check-out sem itens inalterado;
- build, testes e verificação do contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E11 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Empréstimo de item sem quantidade disponível → `409`; devolução restaura a disponibilidade.
- [ ] Check-out de reserva com item não devolvido e sem decisão → `409` com `code` estável listando os itens.
- [ ] Com a decisão "cobrar", o valor é lançado na conta uma única vez (repetição com a mesma `Idempotency-Key` não duplica); com "perda", o item sai do catálogo disponível com motivo.
- [ ] Check-out de reserva sem itens emprestados se comporta como antes (suíte de `frontdesk` de M5 passa sem alteração de expectativas).
- [ ] `frontdesk` não depende de `housekeeping`, `inventory` nem `linen` (`ModularityTest`).
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Alterar o check-out de M5 pode quebrar fluxos existentes: rodar a suíte de `frontdesk` inteira e manter o comportamento sem itens idêntico.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E12 — Fichas técnicas e baixa por venda — #pendente

**Objetivo:** cadastrar fichas técnicas (insumos, quantidades, rendimento) nos produtos do cardápio, dar baixa automática no estoque do ponto de venda quando a comanda fecha, dar baixa no consumo interno e cortesia sem faturar e calcular custo teórico e CMV (RF-PDV-07, RF-PDV-10).

**Por que agora:** completa o PDV de M8 com custo; é pré-requisito do CMV de A&B (E13) e da baixa de eventos em M10.

**Dependências e paralelismo:** E02. Pode correr em paralelo com E03, E04, E05, E07 e E08 (worktrees separados); sem conflito de arquivos com `inventory` além da API pública.

**Escopo:**

- `Recipe` e `RecipeLine` em `pos` (SPEC §8.2.15), com rendimento e unidade de controle do item de estoque; modificadores que alteram insumos ficam fora salvo se triviais (decisão registrada).
- Listener de `pos` para o próprio `TabClosed` (H5) que chama a baixa de `inventory` no local de estoque do ponto de venda, com origem `(tab, item)`; itens cancelados não baixam; itens transferidos baixam só na comanda que fecha.
- Consumo interno e cortesia (RF-PDV-10) dão baixa sem faturar.
- API pública de `pos` para baixa por ficha técnica a partir de quantidades (usada por `events` em M10, RF-EVT-10).
- Custo teórico por produto (soma dos insumos pelo custo médio) e CMV por período e ponto de venda (consulta ou view `report_*`).

**Fora de escopo:** baixa de A&B de eventos (M10).

**Passos de implementação:**

1. Testes: fechar comanda com produto com ficha gera baixa proporcional; reentrega não duplica; item cancelado não baixa; consumo interno baixa sem lançamento financeiro.
2. Implementar ficha técnica, listener, API pública e custo teórico/CMV; regenerar contrato.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "como `pos` (M8) publica `TabClosed`, que dados leva e como trata itens cancelados, transferidos e consumo interno?".
- Partes paralelas: nenhuma.
- Foco do verificador: `inventory` escutando `TabClosed` (ciclo); baixa duplicada; CMV calculado com custo atual em vez do custo do movimento; uso de ponto flutuante em quantidades.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E12 — "Fichas técnicas e baixa por venda" do roadmap docs/roadmaps/roadmap-m9-suprimentos.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a hipótese H5 —, a seção E12 inteira, o "Evidências e handoff" de E02, RF-PDV-07 e RF-PDV-10 em docs/SPEC.md §5.19 e §8.2.15. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E02 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: fichas técnicas (insumos, quantidades, rendimento) nos produtos do pos; um listener do próprio pos para TabClosed dá baixa idempotente por (comanda, item) no local de estoque do ponto de venda via API de inventory; consumo interno e cortesia baixam sem faturar; API pública de baixa por ficha a partir de quantidades (para eventos em M10); custo teórico por produto e CMV por período e ponto de venda. inventory não pode escutar TabClosed (criaria ciclo).

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E12 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume como pos publica TabClosed e trata itens cancelados, transferidos e consumo interno (handoff de M8);
- escreva os testes primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em ciclo pos ↔ inventory, baixa duplicada, CMV com custo errado e ponto flutuante.

Critérios de aceite (detalhes na seção E12):
- comanda fechada baixa os insumos proporcionalmente uma única vez; itens cancelados não baixam;
- consumo interno baixa sem lançamento financeiro;
- custo teórico e CMV usam o custo dos movimentos; ModularityTest sem ciclo;
- build, testes e verificação do contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E12 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa e para M10) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Fechar comanda com produto com ficha técnica gera saídas proporcionais ao rendimento no local de estoque do ponto de venda, uma única vez mesmo com reentrega do evento.
- [ ] Itens cancelados não geram baixa; itens transferidos baixam apenas na comanda que fecha.
- [ ] Consumo interno e cortesia geram baixa e nenhum lançamento financeiro.
- [ ] A API pública de baixa por ficha a partir de quantidades existe e é coberta por teste.
- [ ] Custo teórico do produto é a soma dos insumos pelo custo médio vigente; CMV do período usa o custo registrado nos movimentos.
- [ ] `inventory` não depende de `pos` (`ModularityTest` sem ciclo).
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Produtos sem ficha técnica: não baixam e aparecem num relatório de produtos sem ficha.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E13 — Relatórios de suprimentos — #pendente

**Objetivo:** disponibilizar os relatórios RF-EST-10 (posição por local, movimentação, consumo de amenities por UH ocupada, CMV de A&B) e RF-ROU-06 (giro do enxoval, perdas, custo de lavanderia por UH ocupada), com exportação CSV.

**Por que agora:** depende dos dados de todas as etapas de estoque, rouparia e PDV.

**Dependências e paralelismo:** E03, E04, E06, E07, E09, E10, E12. Não paralelizável.

**Escopo:**

- Views `report_*` publicadas por `inventory`, `linen` e `pos` (H8, ARCHITECTURE §11), sem expor tabelas internas.
- Endpoints em `reporting` que combinam essas views com as de ocupação já publicadas (M6), filtráveis por período e local.
- Exportação CSV (RF-REL-07), seguindo o padrão de M6.

**Fora de escopo:** indicadores gerenciais (M10); XLSX se M6 não o tiver implementado.

**Passos de implementação:**

1. Testes de integração com dados semeados que conferem os números de cada relatório contra cálculo manual.
2. Implementar views e endpoints; regenerar contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "qual o padrão de views `report_*`, endpoints e exportação CSV de `reporting` (handoff de M6)?".
- Partes paralelas: nenhuma.
- Foco do verificador: `reporting` lendo tabelas internas; denominador "UH ocupada" diferente do usado em M6; relatório sem permissão.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E13 — "Relatórios de suprimentos" do roadmap docs/roadmaps/roadmap-m9-suprimentos.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a hipótese H8 —, a seção E13 inteira, o "Evidências e handoff" de E03, E04, E06, E07, E09, E10 e E12, RF-EST-10 e RF-ROU-06 em docs/SPEC.md e docs/ARCHITECTURE.md §11 (leitura para relatórios). Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que essas dependências estão #concluida e que o código bate com o handoff delas; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: views report_* em inventory, linen e pos e endpoints em reporting para posição por local, movimentação, consumo de amenities por UH ocupada, CMV de A&B, giro do enxoval, perdas e custo de lavanderia por UH ocupada, filtráveis por período e local, com exportação CSV. reporting só lê views report_*, nunca tabelas internas.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E13 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume o padrão de views report_*, endpoints e CSV de reporting (handoff de M6);
- escreva os testes de integração com dados semeados primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em leitura de tabelas internas, denominador de UH ocupada divergente de M6 e acesso sem permissão.

Critérios de aceite (detalhes na seção E13):
- cada relatório confere com cálculo manual em teste de integração;
- reporting consulta só views report_*; exportação CSV disponível;
- acesso sem permissão → 403; build, testes e verificação do contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E13 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Os sete relatórios listados no objetivo existem e cada um confere com cálculo manual num teste de integração com dados semeados.
- [ ] `reporting` consulta apenas views `report_*` (teste ou regra ArchUnit/SQL comprovando).
- [ ] "UH ocupada" usa o mesmo denominador das estatísticas diárias de M6.
- [ ] Exportação CSV disponível para cada relatório.
- [ ] Acesso sem a permissão de relatórios → `403`.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Consultas pesadas em períodos longos: limitar o período máximo e medir no teste com volume moderado.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E14 — Gate do marco M9 — #pendente

**Objetivo:** confirmar, a partir de uma cópia limpa do repositório, que toda a definição global de pronto do M9 é atendida, com um teste ponta a ponta do fluxo de suprimentos, e deixar o M10 pronto para ser sincronizado.

**Por que agora:** é o critério de entrada do marco M10.

**Dependências e paralelismo:** E11, E13 (que dependem transitivamente de todas as demais). Não paralelizável.

**Escopo:**

- Rodar build, testes, lint, verificação do contrato e oasdiff numa cópia limpa.
- Teste ponta a ponta (de integração): NF-e de entrada → transferência para o carrinho → checklist com baixa e troca de enxoval → romaneio de lavanderia → comanda com ficha técnica → relatório de CMV e consumo por UH ocupada.
- Conferir cada item da "Definição global de pronto".
- Atualizar `CLAUDE.md` (módulos `inventory` e `linen`, API de baixa, regras RN-17/RN-18) e registrar a conclusão do M9 no histórico de `docs/roadmaps/README.md`.

**Fora de escopo:** funcionalidade nova. Problemas voltam à etapa de origem (reabrir com `#em-andamento` e registrar no handoff dela), salvo ajustes triviais de documentação.

**Passos de implementação:**

1. Escrever e rodar o teste ponta a ponta.
2. Rodar tudo do zero numa cópia limpa e marcar cada item da definição de pronto com evidência.
3. Atualizar `CLAUDE.md` e o histórico do README dos marcos.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação: nenhuma.
- Partes paralelas: nenhuma.
- Foco do verificador: item da definição de pronto sem evidência; teste ponta a ponta que usa atalhos (escrita direta em tabelas) em vez das APIs; dependências de módulo que violem a regra por camadas da ARCHITECTURE §3.1 ou não estejam registradas na tabela.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E14 — "Gate do marco M9" do roadmap docs/roadmaps/roadmap-m9-suprimentos.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a "Definição global de pronto" —, a seção E14 inteira e o "Evidências e handoff" de todas as etapas E01 a E13. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 a E13 estão #concluida; se não, ou se o código divergiu do plano, pare e relate.

Objetivo: escrever um teste de integração ponta a ponta do fluxo de suprimentos (NF-e de entrada, transferência ao carrinho, checklist com baixa e troca de enxoval, romaneio de lavanderia, comanda com ficha técnica, relatórios) usando só as APIs, e verificar numa cópia limpa, sem caches nem estado local, cada item da definição global de pronto do M9 com evidência. Não implemente funcionalidade nova: problemas voltam à etapa de origem, salvo ajustes triviais de documentação.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E14 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva e rode o teste ponta a ponta; rode do zero, numa cópia limpa, os comandos oficiais de build, teste e lint do CLAUDE.md, a verificação do contrato e o oasdiff;
- atualize CLAUDE.md (inventory, linen, API de baixa, RN-17 e RN-18) e registre a conclusão do M9 no histórico de docs/roadmaps/README.md;
- verificação independente com o Prompt do verificador, com foco em item sem evidência, atalhos no teste ponta a ponta e dependências que violem a regra por camadas ou não estejam registradas na §3.1.

Critérios de aceite (detalhes na seção E14):
- teste ponta a ponta passa usando só APIs públicas;
- todos os itens da definição global de pronto marcados com evidência numa cópia limpa;
- CLAUDE.md atualizado; histórico do README dos marcos registra o M9.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E14 (snapshot, verificações, resultados, parecer do verificador, notas para o marco M10) e responda com um resumo curto. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] O teste ponta a ponta de suprimentos passa usando só endpoints e APIs públicas dos módulos.
- [ ] Todos os itens da "Definição global de pronto" estão marcados, cada um com evidência numa cópia limpa.
- [ ] oasdiff não aponta *breaking change* em `/api/v1` em relação ao fim do M8.
- [ ] Toda dependência de módulo acrescentada neste marco segue a regra por camadas e está registrada na tabela da ARCHITECTURE §3.1.
- [ ] `CLAUDE.md` documenta `inventory`, `linen`, a API de baixa e as regras RN-17/RN-18; `docs/roadmaps/README.md` registra a conclusão do M9.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint (CLAUDE.md) numa cópia limpa → sucesso.
- Verificação do contrato `api/openapi.yaml` e oasdiff → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Teste ponta a ponta lento: um único cenário, com dados mínimos.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para o marco M10: —

---

## Histórico de revisões

- 2026-10-13 — roadmap criado (antes do código existir; sincronizar antes da primeira etapa).
- 2026-10-13 — aplicadas as decisões da entrevista sobre SPEC × ARCHITECTURE: regra por camadas na §3.1 (restrições, definição de pronto e gate); H2 decidida (`inventory` → `notifications`, E03 não pergunta mais); H3 aceita vínculo com `staff`; H4 decidida (itens de empréstimo no `frontdesk`, E11 reescrita sem porta nem `housekeeping`, revisão humana removida); H5 confirmada (listener do `pos` para `TabClosed`).
