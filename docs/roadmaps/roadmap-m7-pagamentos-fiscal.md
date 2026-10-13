# Roadmap — M7 Pagamentos integrados e fiscal

> Arquivo: `docs/roadmaps/roadmap-m7-pagamentos-fiscal.md` · criado em 2026-10-13 · última revisão 2026-10-13
>
> Marco 7 de 11 · mapa dos marcos em [`docs/roadmaps/README.md`](README.md) · anterior: [M6 — Operação diária e fechamento](roadmap-m6-operacao-fechamento.md) · próximo: [M8 — Restaurante, distribuição e inauguração](roadmap-m8-restaurante-distribuicao.md)
>
> **Pré-requisito:** o gate do marco M6 (última etapa de `docs/roadmaps/roadmap-m6-operacao-fechamento.md`) está `#concluida`.

## Como usar

Duas formas, ambas na raiz do repositório:

- **Manual:** cole o prompt de uma etapa numa sessão limpa do Claude Code. Uma etapa por sessão.
- **Automática:** cole o prompt da seção "Execução automática" numa sessão limpa; ela executa até 3 etapas em sequência, um subagente por etapa mais um verificador, e para para revisão. Para não parar em cada pedido de permissão, libere antes os comandos de build/teste do projeto nas permissões do Claude Code (os comandos oficiais estão no `CLAUDE.md`).

**Este roadmap foi escrito antes do código existir.** Antes da primeira etapa, sincronize-o com o código real usando a skill `development-roadmap` (modo atualização): confira caminhos, nomes de módulos, comandos e convenções registrados nos handoffs de M0–M6 e ajuste as etapas pendentes.

Etapas marcadas como paralelizáveis só devem rodar ao mesmo tempo em worktrees git separados; na mesma pasta, rode-as uma de cada vez.

Em ambas, quem executa segue o "Protocolo de execução", atualiza a tag da etapa e preenche "Evidências e handoff". Não pule dependências. Revise e faça commit entre etapas ou lotes — nenhuma sessão faz commit por conta própria.

## Objetivo

Integrar os meios de pagamento e a emissão fiscal ao servidor: cobrança Pix com confirmação automática e devolução, cartão integrado (TEF ou Smart POS) com pré-autorização, captura e cancelamento, caução no check-in, tokenização para garantia, conciliação, e emissão de NFS-e e NFC-e por middleware fiscal com IBS/CBS. Tudo atrás de portas neutras, com adaptadores falsos testados por WireMock primeiro; os adaptadores reais de cada fornecedor só são escritos depois que a SPEC Q-05 for decidida. Ao fim do marco, contas e check-out emitem nota e recebem pagamentos sem digitação.

## Contexto confirmado

- Requisitos: SPEC §5.11 (RF-PAG-01 a 06), §5.12 (RF-FIS-01 a 09), §5.10 (RF-CTA-06 a 08, RF-CTA-10 e RF-CTA-15: pagamentos, caução, caixa e reembolso).
- Modelo de domínio: SPEC §8.2.7 (`Folio`, `Payment`, `CashierShift`, `CardPreAuthorization` em contas) e §8.2.8 (`PaymentIntent`, `PixCharge`, `CardTransaction`, `CardToken`, `PaymentProvider`, `FiscalProfile`, `FiscalDocument`, `FiscalDocumentItem`, `TaxAmount`, `FiscalProvider`).
- Máquina de estados do documento fiscal: SPEC §8.4. Eventos `PaymentSucceeded` e `FiscalDocumentAuthorized`/`Rejected`: SPEC §8.3.
- Regras: RN-02 (arredondamento único validado com o middleware), RN-03 (imutabilidade financeira), RN-12 (ações sensíveis), RNF-02 (sem portas abertas com servidor local), RNF-10 (PCI-DSS).
- Arquitetura: módulos `payments` (depende de `shared`), `fiscal` (depende de `shared`, `hotel`, `files`), `billing` (depende de `payments` e `fiscal`), `frontdesk` (depende de `payments`, `fiscal`, `billing`) e `endofday` (depende de `payments`, `billing`) — ARCHITECTURE §3.1. Padrão de integração, tabela de integrações e conciliação de TEF: ARCHITECTURE §12. Jobs de polling e renovação de pré-autorizações: §13. Idempotência: §7. PCI e cifragem de credenciais: §8.3. XMLs fiscais no armazenamento de arquivos: §14. Regra por camadas da §3.1: qualquer módulo pode chamar os serviços de plataforma (`files`, `notifications`, `printing`, `hotel`) e depender de camadas inferiores sem aprovação, desde que o grafo siga acíclico e a aresta nova seja acrescentada à tabela §3.1 na mesma mudança.
- Convenções herdadas (ler nos handoffs e no `CLAUDE.md`): M0 (API, erros, `Money`, IDs, `Idempotency-Key`, outbox, ShedLock, CI), M1 (autorização, step-up por PIN, auditoria, `hotel`, `files`), M2 (notificações), M3 (`billing` e `payments` com registro manual e `PaymentIntent` neutro), M5 (padrão de integração porta + adaptador + outbox + WireMock da FNRH, credenciais de serviço), M6 (etapas do fechamento do dia).
- Caminhos previstos (a criar, sob o pacote base `com.zeny.hotel`, hipótese herdada de M0): `payments/` e `fiscal/` com `domain/`, `application/`, `infrastructure/`, `web/`; migrações nas pastas `payments` e `fiscal` de `src/main/resources/db/migration/`.
- Comandos de build, teste e lint: os oficiais registrados no `CLAUDE.md` (definidos em M0 E01).

## Escopo

**Incluído:** portas `PaymentProvider` e `FiscalProvider` com adaptadores falsos; Pix (cobrança, confirmação por webhook e por polling, devolução); cartão TEF reportado pelo cliente e Smart POS pela API do adquirente; pré-autorização, captura, cancelamento; tokenização para garantia; caução no check-in e renovação no fechamento; reembolso com aprovação; conciliação; perfis fiscais; ciclo de vida do documento fiscal com fila e reprocessamento; NFS-e da hospedagem no check-out; NFC-e de A&B como serviço para o PDV; relatórios e exportação de XMLs; adaptadores reais (bloqueados até Q-05).

**Fora de escopo:**

- PDV, comandas e chamada de NFC-e no fechamento de comanda: M8.
- Channel manager e cartão virtual de OTA: M8.
- Contas a receber e faturas (RF-CTA-13) e parcelas de evento: M10.
- Exportação contábil (RF-REL-08): M10. Aqui só a exportação de XMLs (RF-FIS-09).
- Biblioteca TEF nos clientes desktop/PDV: repositórios dos clientes (ARCHITECTURE §1); o servidor só cria o `PaymentIntent` e recebe o resultado.

## Restrições e invariantes

- **PCI-DSS (RNF-10):** o servidor nunca recebe nem grava número de cartão ou CVV. Só token, bandeira, 4 últimos dígitos, NSU e autorização. DTOs e logs rejeitam/mascaram qualquer campo parecido com PAN.
- **Imutabilidade (RN-03):** pagamentos, estornos e documentos fiscais não são editados nem excluídos; correções são novas linhas.
- **Grafo de módulos (ARCHITECTURE §3.1):** `payments` não chama `billing`, `frontdesk` nem `pos`; quem reage a pagamento escuta `PaymentSucceeded`. `fiscal` não chama `billing`; `billing` escuta `FiscalDocumentAuthorized`/`Rejected`. `notifications` nunca escuta eventos de `fiscal` nem de `payments`: os alertas de RF-NOT-01 (falha de integração, rejeição de nota) saem do módulo que detecta o problema (`fiscal`, `payments`, `billing` ou `endofday`), que chama `notifications` diretamente ou pelo mecanismo genérico de falha de integração de M5 (H2); as arestas novas para `notifications` entram na tabela §3.1 na mesma etapa.
- **Idempotência:** POSTs com efeito financeiro ou externo exigem `Idempotency-Key` (ARCHITECTURE §7); confirmações recebidas por webhook ou polling são idempotentes pelo identificador do provedor (txid, NSU, chave de acesso).
- **Webhook e polling:** toda confirmação funciona nos dois modos, selecionados por configuração (RNF-02, Q-06). Nenhum fluxo depende só de webhook.
- **Segredos:** credenciais de provedores cifradas em campo (ARCHITECTURE §8.3) e fora do repositório; logs de integração com payload redigido.
- **Arredondamento:** só a função única de `Money` (M0 E03); nenhum módulo arredonda por conta própria.

## Hipóteses e decisões em aberto

| # | Hipótese ou decisão | Impacto se errada / sem decisão | Etapa afetada |
|---|---|---|---|
| H1 | M3 entregou `PaymentIntent` neutro, registro manual e evento `PaymentSucceeded` consumido por `billing`; `CardPreAuthorization` vive em `billing` (SPEC §8.2.7). | Se o modelo divergir, E01 adapta a porta ao que existe e registra no handoff. | E01 |
| H2 | M5 estabeleceu o padrão de integração (porta, adaptador, outbox, retry com backoff, log redigido, painel de saúde, evento genérico de falha) e ele é reutilizável aqui. | Se o padrão ficou acoplado à FNRH, E01 o extrai para `shared` antes de usar. | E01, E06 |
| H3 | O recebimento de webhook usa credencial de serviço ou assinatura do provedor, no padrão de M1/M5. | Ajuste local na etapa que cria o endpoint. | E02, E06 |
| H4 | Pix confirmado de forma assíncrona é registrado no turno de caixa de quem criou a cobrança (RF-CTA-10). | Se o turno já tiver fechado, o pagamento vai para o turno aberto do mesmo ponto de caixa ou fica pendente de alocação; E02 registra a regra como decisão. | E02 |
| H5 | Decidido em 2026-10-13: `fiscal` depende de `files` para guardar XML e PDF (ARCHITECTURE §14); a aresta já consta na tabela §3.1. | Nenhum: deixou de ser hipótese. | E07 |
| H6 | Q-04 segue o padrão adotado na SPEC §10: NFC-e do consumo de A&B lançado no quarto emitida no check-out, agrupada; café incluso entra na NFS-e da hospedagem. | Mudar depois troca o momento de emissão; a API de E08 aceita os dois. | E07, E08 |
| H7 | Q-02/Q-03: regime tributário e regras municipais ficam em perfis fiscais configuráveis; o middleware resolve o layout. | Sem a decisão, a homologação real não fecha (E11). | E06, E11 |
| D1 | Q-05: qual PSP Pix. | Sem o fornecedor não há credenciais nem documentação para o adaptador real. | E09 (bloqueada) |
| D2 | Q-05 e Q-13: adquirente e modo de cartão (TEF com biblioteca no cliente ou Smart POS com API em nuvem). | Define qual fluxo de cartão vai para produção e o adaptador real. | E10 (bloqueada) |
| D3 | Q-05, Q-02 e Q-03: middleware fiscal, regime tributário, município (emissor nacional ou próprio, ISS, taxa de turismo). | Sem eles não há homologação de NFS-e/NFC-e nem validação do arredondamento (RN-02). | E11 (bloqueada) |

## Definição global de pronto

- [ ] Pix: cobrança, confirmação por webhook e por polling, e devolução funcionam ponta a ponta com o PSP escolhido em homologação, e o pagamento aparece na conta.
- [ ] Cartão: venda, pré-autorização, captura e cancelamento funcionam no modo escolhido (TEF ou Smart POS) em homologação, sem número de cartão no servidor.
- [ ] Caução: pré-autorização no check-in, captura total ou parcial no check-out, liberação da sobra e renovação no fechamento.
- [ ] Conciliação aponta divergências entre transações do provedor e pagamentos registrados.
- [ ] NFS-e da hospedagem emitida no check-out e NFC-e de A&B disponível para o PDV, com IBS/CBS, autorizadas pelo middleware escolhido em homologação.
- [ ] Rejeições e falhas de integração ficam na fila, são reprocessadas e geram alerta.
- [ ] Build, testes (incluindo WireMock e Testcontainers), verificação de módulos e verificação do contrato passam; `api/openapi.yaml` atualizado sem *breaking change*.

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
Você é o verificador independente da etapa ETAPA do roadmap docs/roadmaps/roadmap-m7-pagamentos-fiscal.md. Você não participou da implementação e não deve editar nenhum arquivo.
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
Você é o orquestrador do roadmap docs/roadmaps/roadmap-m7-pagamentos-fiscal.md neste repositório. Seu papel é coordenar subagentes, não implementar: não edite código e mantenha seu contexto enxuto (leia o roadmap e os relatórios dos subagentes, não o código).

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

**Pagamentos**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E01 | Porta de pagamentos e provedor falso | #pendente | — | `PaymentProvider` neutro, ciclo de vida do `PaymentIntent` integrado e adaptador falso testado por WireMock |
| E02 | Pix: cobrança, confirmação e devolução | #pendente | E01 | Cobrança com QR Code, confirmação por webhook e polling, devolução com aprovação |
| E03 | Cartão integrado e tokenização | #pendente | E01 | Fluxos TEF e Smart POS, pré-autorização, captura, cancelamento e token de garantia sem PAN |
| E04 | Caução, renovação e reembolso | #pendente | E02, E03 | Caução no check-in, captura no check-out, renovação no fechamento, reembolso com aprovação |
| E05 | Conciliação de pagamentos | #pendente | E02, E03 | Transações do provedor × pagamentos, com divergências destacadas |

**Fiscal**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E06 | Núcleo fiscal: perfis, documento e fila de emissão | #pendente | — | `FiscalProfile`, `FiscalDocument` com máquina de estados, `FiscalProvider` falso, fila com reprocessamento |
| E07 | NFS-e da hospedagem no check-out | #pendente | E06 | Emissão por conta ou seleção de lançamentos, IBS/CBS, XML e PDF guardados, vínculo na conta |
| E08 | NFC-e de A&B, relatórios e exportação de XMLs | #pendente | E07 | Serviço de NFC-e para o PDV, NFC-e agrupada no check-out, relatórios e exportação |

**Fornecedores reais**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E09 | Adaptador real do PSP Pix | #bloqueada | E02 | Adaptador do PSP escolhido homologado |
| E10 | Adaptador real do adquirente | #bloqueada | E03 | Adaptador TEF ou Smart POS do adquirente escolhido homologado |
| E11 | Adaptador real do middleware fiscal | #bloqueada | E08 | NFS-e e NFC-e homologadas no middleware escolhido |

**Encerramento**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E12 | Gate do marco M7 | #pendente | E04, E05, E09, E10, E11 | Definição global de pronto verificada ponta a ponta em homologação |

---

## E01 — Porta de pagamentos e provedor falso — #pendente

**Objetivo:** estender o módulo `payments` de M3 com a porta neutra `PaymentProvider`, o ciclo de vida completo do `PaymentIntent` integrado e um adaptador falso que simula um provedor, testado com WireMock, reaproveitando o padrão de integração de M5.

**Por que agora:** Pix, cartão, caução e conciliação usam a mesma porta e o mesmo ciclo de vida. Fixá-los primeiro evita que cada meio invente o seu.

**Dependências e paralelismo:** nenhuma dentro deste roadmap; exige o gate de M6 concluído. Pode correr em paralelo com E06 (em worktree separado); sem conflito de arquivos de domínio, conflito possível em `api/openapi.yaml`.

**Escopo:**

- `payments/` (existente desde M3; arquivos novos a criar): porta `PaymentProvider` (criar cobrança Pix, consultar status, devolver, iniciar operação de cartão em terminal remoto, consultar transações para conciliação), seleção do provedor por configuração, credenciais cifradas.
- `PaymentIntent`: estados (criado, pendente, sucesso, falha, expirado, reembolsado parcial/total), expiração, `markSucceeded`/`markFailed`/`refund` (SPEC §8.2.8), idempotência por identificador do provedor; publica `PaymentSucceeded` (já consumido por `billing` desde M3) e eventos de falha/reembolso.
- Infraestrutura de integração (padrão de M5, H2): outbox, retry com backoff, log de integração com payload redigido, indicador de saúde e evento genérico de falha.
- Adaptador falso com cliente HTTP real apontado para WireMock nos testes.
- Migração na pasta `payments`.

**Fora de escopo:** fluxos Pix e cartão específicos (E02, E03); adaptadores reais (E09, E10).

**Passos de implementação:**

1. Ler o handoff de M3 (pagamentos) e de M5 (padrão de integração); se o padrão estiver acoplado à FNRH, extrair a parte genérica para `shared`.
2. Escrever testes de domínio das transições do `PaymentIntent` e testes de integração do adaptador falso com WireMock (sucesso, timeout com retry, erro permanente).
3. Implementar porta, ciclo de vida, adaptador e configuração.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "como M3 implementou `PaymentIntent`, `PaymentSucceeded` e o registro manual (handoff de M3 e código de `payments`)?"; "quais componentes do padrão de integração da FNRH em M5 (outbox, retry, log redigido, saúde, falha genérica) são reutilizáveis e onde estão?"; "o que SPEC §8.2.8 e ARCHITECTURE §12 exigem da porta?".
- Partes paralelas: nenhuma.
- Foco do verificador: `payments` chamando `billing` ou outro módulo fora do grafo; evento `PaymentSucceeded` publicado duas vezes para a mesma confirmação; credencial em texto claro no banco ou no log.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E01 — "Porta de pagamentos e provedor falso" do roadmap docs/roadmaps/roadmap-m7-pagamentos-fiscal.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e as hipóteses H1 e H2 — e a seção E01 inteira. Confirme que o gate do marco M6 (última etapa de docs/roadmaps/roadmap-m6-operacao-fechamento.md) está #concluida; se não estiver, pare e relate sem implementar. Leia os handoffs de M3 sobre payments e de M5 sobre o padrão de integração da FNRH. Rode git status e preserve mudanças preexistentes que não sejam suas. Se o código divergiu do plano, pare e relate.

Objetivo: estender o módulo payments com a porta neutra PaymentProvider, o ciclo de vida completo do PaymentIntent integrado (com idempotência pelo identificador do provedor) e um adaptador falso testado com WireMock, reaproveitando outbox, retry, log redigido e indicador de saúde do padrão de M5. payments não chama billing nem outros módulos: quem reage escuta PaymentSucceeded.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E01 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume como M3 implementou PaymentIntent e PaymentSucceeded, quais partes do padrão de integração de M5 são reutilizáveis e o que SPEC §8.2.8 e ARCHITECTURE §12 exigem;
- escreva os testes de domínio e de integração com WireMock primeiro, implemente e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em violação do grafo de módulos, PaymentSucceeded duplicado e credencial em texto claro.

Critérios de aceite (detalhes na seção E01):
- transições do PaymentIntent cobertas por testes, incluindo confirmação repetida sem segundo evento;
- adaptador falso testado com WireMock em sucesso, timeout com retry e erro permanente;
- credenciais cifradas e log de integração redigido;
- verificação de módulos, build e testes oficiais passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E01 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Testes de domínio cobrem todas as transições do `PaymentIntent`, incluindo transição inválida rejeitada com `code` estável e expiração.
- [ ] Uma confirmação repetida com o mesmo identificador do provedor não muda o estado nem publica um segundo `PaymentSucceeded` (teste).
- [ ] O adaptador falso, testado com WireMock, cobre sucesso, timeout com retry e backoff, e erro permanente que gera o evento genérico de falha.
- [ ] Credenciais do provedor ficam cifradas no banco; o log de integração não contém credenciais nem dados pessoais (teste).
- [ ] O indicador de saúde da integração de pagamentos aparece no actuator/painel do padrão de M5.
- [ ] `ApplicationModules.verify()` passa sem novas dependências de `payments` além de `shared`.
- [ ] Build, testes e verificação do contrato oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (no `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sem diff pendente e sem *breaking change*.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Porta desenhada para um único provedor: validar contra dois provedores conhecidos (documentação pública de um PSP e de um adquirente) na investigação.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E02 — Pix: cobrança, confirmação e devolução — #pendente

**Objetivo:** permitir cobrar por Pix (QR Code e copia-e-cola) para conta, sinal de reserva ou outro alvo, confirmar o pagamento automaticamente por webhook ou por consulta periódica e devolver valores com aprovação.

**Por que agora:** Pix é o meio integrado mais usado e o mais simples de homologar; valida a porta de E01 ponta a ponta.

**Dependências e paralelismo:** E01. Pode correr em paralelo com E03 (em worktree separado); conflito de edição na configuração do provedor em `payments` e em `api/openapi.yaml`.

**Escopo:**

- Endpoints em `/api/v1` para criar cobrança Pix (com `Idempotency-Key`), consultar status e solicitar devolução (ação sensível com step-up, RF-CTA-15).
- `PixCharge` (txid, copia-e-cola, vencimento, endToEndId) e cobrança com vencimento (`cobv`) para sinal de reserva.
- Endpoint de webhook do PSP (H3), idempotente pelo txid/endToEndId; job de polling com ShedLock (ARCHITECTURE §13) para modo sem webhook, selecionado por configuração.
- Turno de caixa da confirmação assíncrona (H4); integração com `billing` só via `PaymentSucceeded` (o alvo segue no evento).
- Expiração de cobranças não pagas.

**Fora de escopo:** caução via Pix (E04); adaptador real (E09); cobrança de comanda (M8, usa este serviço).

**Passos de implementação:**

1. Escrever testes de integração: criação com `Idempotency-Key`, confirmação por webhook, confirmação por polling, webhook repetido, devolução sem aprovação → `403 APPROVAL_REQUIRED`, devolução com aprovação.
2. Implementar endpoints, webhook, job e expiração.
3. Regenerar `api/openapi.yaml`.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "como o padrão Pix do Banco Central (cob, cobv, webhook, devolução) define txid, endToEndId e estados?"; "como `billing` consome `PaymentSucceeded` e exige turno de caixa aberto (handoff de M3)?".
- Partes paralelas: nenhuma.
- Foco do verificador: pagamento registrado duas vezes por webhook e polling simultâneos; webhook aceito sem autenticação; devolução maior que o valor pago; confirmação sem turno de caixa.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E02 — "Pix: cobrança, confirmação e devolução" do roadmap docs/roadmaps/roadmap-m7-pagamentos-fiscal.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e as hipóteses H3 e H4 —, a seção E02 inteira e o "Evidências e handoff" de E01. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: cobrança Pix (QR Code e copia-e-cola, cob e cobv) com Idempotency-Key, confirmação automática por webhook autenticado ou por job de polling com ShedLock (selecionado por configuração), idempotente pelo txid/endToEndId, expiração de cobranças e devolução como ação sensível com step-up. billing só recebe o pagamento via PaymentSucceeded. Não implemente caução nem o adaptador real.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E02 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume o padrão Pix do Banco Central (cob, cobv, webhook, devolução) e como billing consome PaymentSucceeded e exige turno de caixa (handoff de M3);
- escreva os testes de integração primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em pagamento duplicado por webhook e polling simultâneos, webhook sem autenticação e devolução acima do pago.

Critérios de aceite (detalhes na seção E02):
- cobrança criada com Idempotency-Key e confirmada por webhook e por polling, com um único pagamento na conta;
- webhook repetido ou sem autenticação não gera pagamento;
- devolução sem aprovação → 403 APPROVAL_REQUIRED; acima do pago → erro com code estável;
- build, testes e verificação do contrato oficiais passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E02 (snapshot, arquivos alterados, decisões — inclusive a regra de turno de caixa —, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] `POST` de cobrança Pix exige `Idempotency-Key` e devolve txid, copia-e-cola e vencimento.
- [ ] Confirmação por webhook registra exatamente um pagamento na conta-alvo (via `PaymentSucceeded`), no turno de caixa definido pela regra registrada (H4).
- [ ] Com o modo polling ativo e o webhook desligado, o job confirma a cobrança paga no provedor falso (teste).
- [ ] Webhook e polling simultâneos para a mesma cobrança geram um único pagamento (teste concorrente).
- [ ] Webhook sem autenticação/assinatura válida → `401` ou `403`, sem efeito.
- [ ] Cobrança vencida passa a expirada e não aceita confirmação posterior sem registro de divergência.
- [ ] Devolução sem aprovação → `403 APPROVAL_REQUIRED`; com aprovação, gera reembolso auditado; valor acima do pago → erro com `code` estável.
- [ ] Build, testes e verificação do contrato oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Verificação do contrato → sem diff pendente e sem *breaking change*.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Confirmação tardia de cobrança expirada: registrar como divergência para a conciliação (E05) em vez de descartar.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E03 — Cartão integrado e tokenização — #pendente

**Objetivo:** suportar cartão integrado nos dois modos da ARCHITECTURE §12 — TEF, em que o cliente executa a transação e reporta o resultado, e Smart POS, em que o servidor envia o pedido ao terminal pela API do adquirente — com venda, pré-autorização, captura e cancelamento, e tokenização de cartão para garantia de reserva, sem que o servidor jamais receba número de cartão ou CVV.

**Por que agora:** a caução (E04) e o PDV (M8) dependem desses fluxos. A decisão TEF × Smart POS ainda está aberta (D2), então os dois modos ficam prontos atrás da porta.

**Dependências e paralelismo:** E01. Pode correr em paralelo com E02 (em worktree separado); conflito de edição na configuração do provedor em `payments` e em `api/openapi.yaml`.

**Escopo:**

- `CardTransaction` (terminal, tipo, NSU, autorização, bandeira, 4 últimos) e `CardToken` (token do provedor, bandeira, 4 últimos, titular, validade).
- TEF: endpoint para criar o `PaymentIntent` de cartão e endpoint para o cliente reportar o resultado (idempotente por NSU + terminal), marcado como "não verificado" até a conciliação (ARCHITECTURE §12).
- Smart POS: envio do pedido ao terminal via porta, resultado por webhook ou polling.
- Operações: venda, pré-autorização, captura total ou parcial, cancelamento (ação sensível com step-up).
- Tokenização (RF-PAG-04): endpoint que recebe só o token gerado pelo componente do provedor e o associa a um titular/reserva.
- Guardas PCI: validação que rejeita qualquer campo com sequência que pareça PAN (Luhn, 13–19 dígitos) nos DTOs de pagamento, e máscara no log.

**Fora de escopo:** caução e renovação (E04); biblioteca TEF nos clientes; adaptador real (E10).

**Passos de implementação:**

1. Escrever testes: fluxo TEF reportado, Smart POS por webhook e por polling, pré-autorização seguida de captura parcial, cancelamento com e sem aprovação, tokenização, requisição com PAN rejeitada.
2. Implementar modelo, endpoints e adaptador falso para Smart POS.
3. Regenerar `api/openapi.yaml`.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "o que SPEC RF-PAG-02, RF-PAG-04, RNF-10 e ARCHITECTURE §8.3 e §12 exigem do fluxo de cartão e da tokenização?"; "como integradores TEF (SiTef, PayGo) e Smart POS reportam NSU, autorização e bandeira?".
- Partes paralelas: nenhuma.
- Foco do verificador: qualquer caminho em que um PAN chegue ao banco ou ao log; captura acima do valor pré-autorizado; resultado TEF repetido gerando dois pagamentos; cancelamento sem aprovação.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E03 — "Cartão integrado e tokenização" do roadmap docs/roadmaps/roadmap-m7-pagamentos-fiscal.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e as restrições de PCI —, a seção E03 inteira e o "Evidências e handoff" de E01. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: cartão integrado nos modos TEF (cliente executa e reporta NSU, autorização, bandeira e 4 últimos, idempotente por NSU + terminal) e Smart POS (pedido pela porta, resultado por webhook ou polling), com venda, pré-autorização, captura total ou parcial e cancelamento como ação sensível, mais tokenização para garantia que recebe só o token do provedor. O servidor nunca recebe número de cartão nem CVV: DTOs rejeitam sequências que pareçam PAN e o log as mascara. Não implemente caução nem adaptador real.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E03 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume RF-PAG-02, RF-PAG-04, RNF-10, ARCHITECTURE §8.3 e §12 e como TEF e Smart POS reportam o resultado;
- escreva os testes primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em PAN chegando ao banco ou ao log, captura acima do pré-autorizado, resultado TEF duplicado e cancelamento sem aprovação.

Critérios de aceite (detalhes na seção E03):
- fluxos TEF e Smart POS (webhook e polling) cobertos por testes de integração;
- captura parcial libera a sobra; captura acima do pré-autorizado é rejeitada;
- requisição com sequência que passa no Luhn é rejeitada com code estável e nada é gravado;
- build, testes e verificação do contrato oficiais passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E03 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] TEF: o cliente reporta o resultado de um `PaymentIntent` de cartão; o reporte repetido com o mesmo NSU + terminal não gera segundo pagamento; o pagamento fica marcado como "não verificado" até a conciliação.
- [ ] Smart POS: pedido enviado ao terminal pelo adaptador falso, com resultado recebido por webhook e, em outro teste, por polling.
- [ ] Pré-autorização seguida de captura parcial registra o valor capturado e libera a sobra; captura acima do pré-autorizado → erro com `code` estável.
- [ ] Cancelamento sem aprovação → `403 APPROVAL_REQUIRED`; com aprovação, auditado com solicitante e aprovador.
- [ ] Tokenização grava só token, bandeira, 4 últimos, titular e validade.
- [ ] Qualquer DTO de pagamento com campo que passe no Luhn com 13–19 dígitos é rejeitado com `code` estável e nada é gravado nem logado em claro (teste).
- [ ] Build, testes e verificação do contrato oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Verificação do contrato → sem diff pendente e sem *breaking change*.
- Inspeção: busca por colunas `pan`, `card_number` ou `cvv` nas migrações → nenhuma.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- TEF reportado pelo cliente não é verificável pelo servidor: marcação "não verificado" e conciliação (E05) cobrem o risco, conforme ARCHITECTURE §12.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E04 — Caução, renovação e reembolso — #pendente

**Objetivo:** implementar a caução da RF-CTA-08: pré-autorização de cartão (ou cobrança Pix) no check-in, captura total ou parcial no check-out com liberação da sobra, renovação de pré-autorizações perto do vencimento como etapa do fechamento do dia, e reembolsos com aprovação (RF-CTA-15).

**Por que agora:** depende dos fluxos de Pix (E02) e cartão (E03) e é o que liga pagamentos integrados à recepção.

**Dependências e paralelismo:** E02, E03. Pode correr em paralelo com E05 (em worktree separado); conflito de edição em `api/openapi.yaml`.

**Escopo:**

- `billing`: `CardPreAuthorization` (existente ou a criar conforme handoff de M3) com validade, valor, captura e liberação; caução via Pix como cobrança seguida de devolução.
- `frontdesk`: check-in passa a oferecer caução integrada; check-out captura o necessário e libera a sobra (`frontdesk` → `payments`, `billing`, permitido pelo grafo).
- `endofday`: nova etapa idempotente que renova pré-autorizações perto do vencimento (ARCHITECTURE §13), com alerta em falha.
- Reembolso de pagamento (devolução Pix ou estorno de cartão) a partir da conta, com step-up e auditoria.

**Fora de escopo:** contas a receber (M10); PDV (M8).

**Passos de implementação:**

1. Ler os handoffs de M3 (caução manual, se houver), M5 (check-in/out) e M6 (etapas do fechamento).
2. Escrever testes de módulo (`Scenario` do Modulith) do check-in com caução, check-out com captura parcial e liberação, renovação no fechamento e reembolso com aprovação.
3. Implementar nos três módulos e regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "como M5 orquestra check-in e check-out em `frontdesk` e onde a caução manual entrou (handoffs de M3 e M5)?"; "como M6 define e torna idempotente uma etapa do fechamento do dia?".
- Partes paralelas: nenhuma.
- Foco do verificador: renovação executada duas vezes ao retomar o fechamento; captura maior que o saldo devido; caução liberada sem registro; dependência nova que viole o grafo (por exemplo `payments` chamando `frontdesk`).

**Prompt para uma sessão limpa:**

```text
Execute a etapa E04 — "Caução, renovação e reembolso" do roadmap docs/roadmaps/roadmap-m7-pagamentos-fiscal.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E04 inteira e o "Evidências e handoff" de E02 e E03. Leia também RF-CTA-08 e RF-CTA-15 em docs/SPEC.md §5.10. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E02 e E03 estão #concluida e que o código bate com o handoff delas; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: caução no check-in (pré-autorização de cartão ou Pix cobrado e depois devolvido), captura total ou parcial no check-out com liberação da sobra, nova etapa idempotente do fechamento do dia que renova pré-autorizações perto do vencimento, e reembolso a partir da conta como ação sensível com step-up. Respeite o grafo de módulos: frontdesk e endofday chamam payments e billing; payments não chama ninguém.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E04 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume como frontdesk orquestra check-in/out e onde está a caução manual (handoffs de M3 e M5) e como uma etapa do fechamento é definida e tornada idempotente (handoff de M6);
- escreva os testes de módulo primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em renovação duplicada ao retomar o fechamento, captura acima do devido e violação do grafo de módulos.

Critérios de aceite (detalhes na seção E04):
- check-in com caução e check-out com captura parcial e liberação cobertos por teste de módulo;
- etapa de renovação do fechamento idempotente ao ser retomada;
- reembolso sem aprovação → 403 APPROVAL_REQUIRED;
- verificação de módulos, build e testes oficiais passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E04 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Check-in com caução por cartão cria pré-autorização vinculada à conta da reserva (teste de módulo).
- [ ] Caução por Pix gera cobrança e, no check-out, devolução do excedente.
- [ ] Check-out captura só o saldo devido (limitado ao pré-autorizado) e libera a sobra; o saldo da conta fica zero (RN-06).
- [ ] A etapa de renovação do fechamento renova pré-autorizações que vencem antes da próxima data configurada e, retomada após falha, não renova de novo as já renovadas (teste).
- [ ] Falha na renovação gera notificação via `notifications` (RF-NOT-01), diretamente ou pelo mecanismo genérico de falha de integração.
- [ ] Reembolso sem aprovação → `403 APPROVAL_REQUIRED`; com aprovação, auditado e refletido na conta como nova linha (RN-03).
- [ ] `ApplicationModules.verify()` passa; toda dependência nova segue a regra por camadas e está na tabela da ARCHITECTURE §3.1.
- [ ] Build, testes e verificação do contrato oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Verificação do contrato → sem diff pendente e sem *breaking change*.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Prazo de validade de pré-autorização varia por adquirente: tornar a antecedência de renovação configurável e revisar em E10.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E05 — Conciliação de pagamentos — #pendente

**Objetivo:** confrontar as transações informadas pelo provedor com os pagamentos registrados (RF-PAG-05) e destacar divergências: pagamento sem transação, transação sem pagamento, valor diferente e TEF "não verificado" sem correspondência.

**Por que agora:** é o controle que cobre o risco do TEF reportado pelo cliente (ARCHITECTURE §12) e de confirmações tardias de Pix (E02).

**Dependências e paralelismo:** E02, E03. Pode correr em paralelo com E04 (em worktree separado); conflito de edição em `api/openapi.yaml`.

**Escopo:**

- `payments`: consulta de transações do provedor por período via porta; importação manual de extrato (CSV) como alternativa quando o provedor não oferecer API.
- Execução de conciliação por período e provedor, com resultado persistido e divergências classificadas; marcação de TEF como verificado quando conciliado.
- Endpoint de consulta e de resolução manual de divergência (com motivo, auditada).
- Job diário opcional com ShedLock e alerta quando houver divergência.

**Fora de escopo:** conciliação bancária e financeiro administrativo (SPEC Q-01, fora da v1).

**Passos de implementação:**

1. Escrever testes com transações falsas cobrindo cada tipo de divergência e a marcação de TEF verificado.
2. Implementar porta, importação, execução, endpoints e job.
3. Regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em RF-PAG-05 e ARCHITECTURE §12.
- Partes paralelas: nenhuma.
- Foco do verificador: divergência resolvida sem auditoria; conciliação que altera pagamentos existentes (viola RN-03); falso positivo por diferença de fuso na data da transação.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E05 — "Conciliação de pagamentos" do roadmap docs/roadmaps/roadmap-m7-pagamentos-fiscal.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E05 inteira e o "Evidências e handoff" de E02 e E03. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E02 e E03 estão #concluida e que o código bate com o handoff delas; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: conciliar transações do provedor (via porta ou importação de CSV) com pagamentos registrados por período, persistindo o resultado e classificando divergências (pagamento sem transação, transação sem pagamento, valor diferente, TEF não verificado), marcando TEF como verificado quando conciliado, com resolução manual auditada e job diário opcional com alerta. A conciliação não altera pagamentos existentes (RN-03).

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E05 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em resolução sem auditoria, alteração de pagamentos existentes e falso positivo por fuso.

Critérios de aceite (detalhes na seção E05):
- cada tipo de divergência coberto por teste;
- TEF conciliado passa a verificado;
- resolução manual exige motivo e é auditada;
- build, testes e verificação do contrato oficiais passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E05 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Testes cobrem os quatro tipos de divergência com dados falsos.
- [ ] Pagamento TEF com transação correspondente passa a "verificado".
- [ ] A importação de CSV produz o mesmo resultado que a consulta pela porta para o mesmo conjunto de transações (teste).
- [ ] Transações perto da meia-noite são comparadas no fuso do hotel, sem falso positivo (teste).
- [ ] Resolução manual exige motivo, é auditada e não altera o pagamento original.
- [ ] Build, testes e verificação do contrato oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Verificação do contrato → sem diff pendente e sem *breaking change*.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Formato do extrato depende do fornecedor: importação de CSV com mapeamento configurável, revisada em E09 e E10.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E06 — Núcleo fiscal: perfis, documento e fila de emissão — #pendente

**Objetivo:** criar o módulo `fiscal` com perfis fiscais (RF-FIS-03), o agregado `FiscalDocument` com a máquina de estados da SPEC §8.4, a porta neutra `FiscalProvider` com adaptador falso testado por WireMock, a fila de emissão com reprocessamento (RF-FIS-08) e status por webhook ou polling.

**Por que agora:** NFS-e (E07) e NFC-e (E08) usam o mesmo núcleo; o contrato fiscal precisa ser revisado antes de se espalhar por contas, recepção e PDV.

**Dependências e paralelismo:** nenhuma dentro deste roadmap; exige o gate de M6 concluído. Pode correr em paralelo com E01–E05 (em worktree separado); conflito possível só em `api/openapi.yaml` e na configuração de integrações.

**Escopo:**

- Módulo `fiscal/` (a criar, depende de `shared`, `hotel` e `files`; `notifications` para alertas), schema e migrações próprios.
- `FiscalProfile` (código de serviço municipal/NBS, NCM, CFOP, ISS, CST e cClassTrib de IBS/CBS, regime específico) com CRUD protegido por permissão; vínculo por código de lançamento e por produto de venda (referência por ID).
- `FiscalDocument`, `FiscalRecipient`, `FiscalDocumentItem`, `TaxAmount` (SPEC §8.2.8); estados pendente → processando → autorizado/rejeitado → cancelado; correção e reenvio de rejeitado; cancelamento sensível (step-up) e dentro do prazo legal configurável.
- Porta `FiscalProvider` (`submit`, `queryStatus`, `cancel`); adaptador falso com WireMock; webhook de status e job de polling com ShedLock; fila com retry e backoff quando o middleware, a SEFAZ ou a prefeitura estiverem indisponíveis.
- Eventos `FiscalDocumentAuthorized` e `FiscalDocumentRejected`; alerta de rejeição (RF-NOT-01) enviado por `fiscal` via `notifications`, diretamente ou pelo mecanismo genérico de falha de M5; aresta `fiscal` → `notifications` acrescentada à tabela §3.1 se for direta.
- API pública do módulo para solicitar emissão a partir de itens já calculados (sem conhecer `billing` nem `pos`).

**Fora de escopo:** montagem da NFS-e a partir da conta (E07); NFC-e e relatórios (E08); middleware real (E11).

**Passos de implementação:**

1. Escrever testes de domínio da máquina de estados e do cancelamento fora do prazo; testes de integração do adaptador falso (autorizado, rejeitado, indisponível com reprocessamento).
2. Implementar módulo, migrações, porta, fila, webhook e polling.
3. Regenerar o contrato e conferir a verificação de módulos.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "o que SPEC §5.12, §8.2.8 e §8.4 (documento fiscal) e ARCHITECTURE §12 exigem?"; "como middlewares fiscais conhecidos (PlugNotas, Focus NFe, NFE.io) modelam envio, status, cancelamento e webhooks, para que a porta sirva a qualquer um?"; "quais campos de IBS/CBS (CST, cClassTrib) a NFS-e nacional exige desde 01/10/2026 (RF-FIS-04)?".
- Partes paralelas: nenhuma.
- Foco do verificador: transição fora da máquina de estados; documento autorizado editável; cancelamento sem aprovação ou fora do prazo; `fiscal` dependendo de `billing` ou `pos`; reenvio que gera documento duplicado no provedor.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E06 — "Núcleo fiscal: perfis, documento e fila de emissão" do roadmap docs/roadmaps/roadmap-m7-pagamentos-fiscal.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e as hipóteses H2 e H7 — e a seção E06 inteira. Confirme que o gate do marco M6 (última etapa de docs/roadmaps/roadmap-m6-operacao-fechamento.md) está #concluida; se não estiver, pare e relate sem implementar. Leia o handoff de M5 sobre o padrão de integração. Rode git status e preserve mudanças preexistentes que não sejam suas. Se o código divergiu do plano, pare e relate.

Objetivo: criar o módulo fiscal (depende de shared, hotel e files; notifications para o alerta de rejeição) com FiscalProfile, FiscalDocument e a máquina de estados da docs/SPEC.md §8.4, cancelamento sensível com step-up dentro do prazo configurável, porta FiscalProvider com adaptador falso testado por WireMock, fila de emissão com reprocessamento, status por webhook ou polling e eventos FiscalDocumentAuthorized/Rejected. fiscal não conhece billing nem pos: expõe uma API para emitir a partir de itens já calculados.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E06 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume SPEC §5.12, §8.2.8 e §8.4 e ARCHITECTURE §12, como middlewares fiscais conhecidos modelam envio, status e cancelamento, e os campos de IBS/CBS exigidos na NFS-e;
- escreva os testes de domínio e de integração primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em transição inválida, documento autorizado editável, cancelamento sem aprovação ou fora do prazo, dependência de billing ou pos e reenvio duplicado.

Critérios de aceite (detalhes na seção E06):
- máquina de estados coberta por testes, inclusive transições inválidas;
- adaptador falso cobre autorizado, rejeitado e indisponível com reprocessamento;
- cancelamento sem aprovação → 403 APPROVAL_REQUIRED; fora do prazo → erro com code estável;
- verificação de módulos, build e testes oficiais passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E06 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Testes de domínio cobrem todas as transições da SPEC §8.4 e rejeitam as inválidas com `code` estável.
- [ ] Documento autorizado não pode ser alterado; correção só por cancelamento e nova emissão.
- [ ] Adaptador falso testado com WireMock: autorizado publica `FiscalDocumentAuthorized`; rejeitado publica `FiscalDocumentRejected` com motivo; indisponível fica na fila e é reprocessado com backoff até autorizar.
- [ ] Reenvio após timeout usa a mesma referência idempotente e não cria segundo documento no provedor (teste).
- [ ] Status chega por webhook autenticado e, com o webhook desligado, por polling.
- [ ] Cancelamento sem aprovação → `403 APPROVAL_REQUIRED`; fora do prazo configurado → erro com `code` estável; cancelamento válido é auditado.
- [ ] CRUD de perfis fiscais exige permissão própria e audita alterações.
- [ ] `ApplicationModules.verify()` passa; `fiscal` depende só de `shared`, `hotel`, `files` e, se usado diretamente, `notifications`, todos listados na tabela da ARCHITECTURE §3.1.
- [ ] Nota rejeitada gera notificação via `notifications` (RF-NOT-01).
- [ ] Build, testes e verificação do contrato oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Verificação do contrato → sem diff pendente e sem *breaking change*.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Porta acoplada a um middleware específico: modelar a partir de dois middlewares na investigação.
- Mudanças de IBS/CBS (ARCHITECTURE §18): campos no perfil fiscal, sem regra fixa em código.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E07 — NFS-e da hospedagem no check-out — #pendente

**Objetivo:** emitir NFS-e para hospedagem e demais serviços a partir de uma conta inteira ou de uma seleção de lançamentos (RF-FIS-01, RF-FIS-05), com separação de receitas e destaque de IBS/CBS (RF-FIS-04), guardar XML e PDF (RF-FIS-07) e vincular a nota à conta.

**Por que agora:** com o núcleo pronto (E06), é a emissão de maior volume e a que encerra a emissão manual no portal da prefeitura (SPEC §9, Fase 1).

**Dependências e paralelismo:** E06. Não paralelizável com E08 (mesmo código de emissão em `billing` e `fiscal`).

**Escopo:**

- `billing`: montagem dos itens da nota a partir dos lançamentos (perfil fiscal por código de lançamento), tomador pessoa física (CPF ou estrangeiro) ou empresa, separação por regime (hospedagem, A&B, eventos, outros); chamada à API pública de `fiscal`; consumo de `FiscalDocumentAuthorized`/`Rejected` para vincular a nota aos lançamentos.
- `frontdesk`: check-out oferece a emissão da NFS-e (padrão: emitir; configurável), sem bloquear o check-out se o middleware estiver fora (a nota fica na fila).
- `fiscal`: guarda de XML e PDF no armazenamento de arquivos (H5), com download por URL assinada e reimpressão.
- Café incluso na NFS-e da hospedagem (H6).

**Fora de escopo:** NFC-e (E08); contas a receber e faturas (M10).

**Passos de implementação:**

1. Usar `files` para XML e PDF (aresta `fiscal` → `files` já na tabela da ARCHITECTURE §3.1, H5).
2. Escrever testes de módulo: conta com diárias e A&B gerando itens separados por regime, seleção de lançamentos, tomador empresa, middleware indisponível no check-out, autorização vinculando a nota.
3. Implementar e regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "como `billing` modela lançamentos, códigos de lançamento e roteamento (handoff de M3) e como `frontdesk` conduz o check-out (handoff de M5)?"; "como `files` expõe upload e URL assinada (handoff de M1)?".
- Partes paralelas: nenhuma.
- Foco do verificador: lançamento faturado em duas notas; receita de A&B misturada com hospedagem; check-out bloqueado por indisponibilidade do middleware; arredondamento fora da função única de `Money`.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E07 — "NFS-e da hospedagem no check-out" do roadmap docs/roadmaps/roadmap-m7-pagamentos-fiscal.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e as hipóteses H5 e H6 —, a seção E07 inteira e o "Evidências e handoff" de E06. Leia RF-FIS-01, RF-FIS-04, RF-FIS-05 e RF-FIS-07 em docs/SPEC.md §5.12. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E06 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: billing monta a NFS-e a partir da conta inteira ou de uma seleção de lançamentos, com tomador pessoa física ou empresa, receitas separadas por regime e IBS/CBS destacados, e chama a API de fiscal; billing vincula a nota aos lançamentos ao receber FiscalDocumentAuthorized; frontdesk oferece a emissão no check-out sem bloqueá-lo se o middleware estiver fora; fiscal guarda XML e PDF em files com download por URL assinada (a aresta fiscal → files já consta na tabela da docs/ARCHITECTURE.md §3.1). Não implemente NFC-e.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E07 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume como billing modela lançamentos e roteamento (handoff de M3), como frontdesk conduz o check-out (handoff de M5) e como files expõe upload e URL assinada (handoff de M1);
- escreva os testes de módulo primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em lançamento em duas notas, receitas misturadas, check-out bloqueado e arredondamento disperso.

Critérios de aceite (detalhes na seção E07):
- conta com diárias e A&B gera itens separados por regime com IBS/CBS;
- lançamento já faturado não entra em outra nota;
- check-out conclui com middleware indisponível e a nota fica na fila;
- XML e PDF baixáveis por URL assinada; build e testes oficiais passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E07 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Emissão por conta inteira e por seleção de lançamentos, com tomador CPF, estrangeiro ou CNPJ (testes).
- [ ] Itens separados por regime (hospedagem, A&B, eventos, outros), cada um com o perfil fiscal do código de lançamento e `TaxAmount` de ISS e IBS/CBS.
- [ ] Um lançamento já vinculado a nota autorizada ou em processamento não entra em nova nota → erro com `code` estável.
- [ ] Check-out com o middleware indisponível conclui; a nota fica pendente na fila e é emitida quando ele volta (teste com WireMock).
- [ ] `FiscalDocumentAuthorized` vincula a nota aos lançamentos na conta; `FiscalDocumentRejected` gera alerta.
- [ ] XML e PDF ficam no armazenamento de arquivos e são baixados só por URL assinada; o acesso é auditado.
- [ ] Totais calculados só com a função de arredondamento de `Money`.
- [ ] `ApplicationModules.verify()` passa; a ARCHITECTURE §3.1 reflete qualquer aresta nova.
- [ ] Build, testes e verificação do contrato oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Verificação do contrato → sem diff pendente e sem *breaking change*.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Regra fiscal da hotelaria ainda em transição (LC 214/2025): perfis configuráveis e revisão com o contador em E11.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E08 — NFC-e de A&B, relatórios e exportação de XMLs — #pendente

**Objetivo:** disponibilizar a emissão de NFC-e (modelo 65) para A&B — consumidor final sem identificação para o PDV (M8) e NFC-e agrupada no check-out para o consumo lançado no quarto (H6) — e entregar os relatórios fiscais e a exportação de XMLs para a contabilidade (RF-FIS-09).

**Por que agora:** o PDV de M8 depende deste serviço; os relatórios fecham o ciclo fiscal do marco.

**Dependências e paralelismo:** E07. Não paralelizável.

**Escopo:**

- `fiscal`: API pública de NFC-e a partir de itens com perfil fiscal de produto (NCM, CFOP), consumidor identificado ou não; reutiliza fila, porta e eventos de E06.
- `billing`: no check-out, NFC-e agrupada para lançamentos de A&B lançados no quarto (H6), configurável para emitir no consumo.
- Relatórios fiscais: notas por período, tipo, status e regime; totais de ISS e IBS/CBS; rejeitadas pendentes.
- Exportação de XMLs por período em arquivo compactado, com download por URL assinada e auditoria.

**Fora de escopo:** comandas e chamada de NFC-e no fechamento de comanda (M8); exportação contábil completa (M10).

**Passos de implementação:**

1. Escrever testes: NFC-e sem identificação, NFC-e agrupada no check-out, configuração "emitir no consumo", relatório por período e exportação.
2. Implementar e regenerar o contrato.
3. Registrar no handoff a API de NFC-e que o PDV (M8) vai chamar.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em RF-FIS-02, RF-FIS-09 e Q-04, e o núcleo já está no handoff de E06 e E07.
- Partes paralelas: nenhuma.
- Foco do verificador: lançamento de A&B que entra na NFS-e e na NFC-e ao mesmo tempo; exportação sem auditoria; relatório que expõe dados pessoais além do necessário.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E08 — "NFC-e de A&B, relatórios e exportação de XMLs" do roadmap docs/roadmaps/roadmap-m7-pagamentos-fiscal.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a hipótese H6 —, a seção E08 inteira e o "Evidências e handoff" de E06 e E07. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E07 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: API pública de NFC-e em fiscal (consumidor identificado ou não, itens com perfil fiscal de produto), NFC-e agrupada no check-out para A&B lançado no quarto (configurável para emitir no consumo), relatórios fiscais por período, tipo, status e regime, e exportação de XMLs por período com download por URL assinada e auditoria. Não implemente comandas nem o fechamento de comanda, que são do marco M8.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E08 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em lançamento de A&B em duas notas, exportação sem auditoria e dados pessoais em relatório.

Critérios de aceite (detalhes na seção E08):
- NFC-e sem identificação e NFC-e agrupada no check-out cobertas por teste;
- nenhum lançamento entra em NFS-e e NFC-e ao mesmo tempo;
- relatórios e exportação de XMLs funcionando, exportação auditada;
- build, testes e verificação do contrato oficiais passam; API de NFC-e registrada no handoff para o PDV.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E08 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] NFC-e para consumidor final sem identificação emitida pela API pública de `fiscal` (teste com adaptador falso).
- [ ] No check-out, o consumo de A&B lançado no quarto gera uma NFC-e agrupada; com a configuração "emitir no consumo", a emissão acontece no lançamento.
- [ ] Nenhum lançamento entra em NFS-e e NFC-e ao mesmo tempo (teste).
- [ ] Relatório fiscal por período, tipo, status e regime com totais de ISS e IBS/CBS.
- [ ] Exportação de XMLs por período gera arquivo compactado, baixado por URL assinada, com acesso auditado.
- [ ] A API de NFC-e que o PDV deve chamar está descrita no handoff.
- [ ] Build, testes e verificação do contrato oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Verificação do contrato → sem diff pendente e sem *breaking change*.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Q-04 decidida diferente do padrão: a configuração "emitir no consumo" já cobre a alternativa.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E09 — Adaptador real do PSP Pix — #bloqueada

**Objetivo:** implementar e homologar o adaptador do PSP Pix escolhido atrás da porta `PaymentProvider`, com cobrança, webhook, consulta e devolução reais em ambiente de homologação.

**Por que agora:** é a primeira integração real de pagamento; sem ela a definição de pronto do marco não fecha.

**Dependências e paralelismo:** E02. Pode correr em paralelo com E10 e E11 (em worktrees separados); conflito de edição na configuração de integrações.

**Escopo:** adaptador do PSP (a criar em `payments/infrastructure/`), autenticação (OAuth2/mTLS, conforme o PSP), mapeamento de erros, testes de contrato com WireMock gravados a partir da documentação, teste manual em homologação, configuração por variáveis de ambiente, atualização do mapeamento de CSV da conciliação (E05).

**Fora de escopo:** troca de PSP no futuro (a porta já permite).

**Passos de implementação:**

1. Confirmar que D1 está registrada e as credenciais de homologação estão disponíveis fora do repositório.
2. Escrever testes de contrato com WireMock a partir da documentação do PSP.
3. Implementar o adaptador e rodar o roteiro de homologação (cobrança, pagamento simulado, webhook ou polling, devolução).

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "quais endpoints, autenticação, formatos de webhook e códigos de erro a documentação do PSP escolhido define?".
- Partes paralelas: nenhuma.
- Foco do verificador: certificado ou segredo no repositório; erro do PSP tratado como sucesso; webhook sem validação de origem.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E09 — "Adaptador real do PSP Pix" do roadmap docs/roadmaps/roadmap-m7-pagamentos-fiscal.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a decisão D1 —, a seção E09 inteira e o "Evidências e handoff" de E01, E02 e E05. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E02 está #concluida, que E09 está #pendente (não #bloqueada) e que D1 registra o PSP escolhido; se não, pare e relate sem implementar. Se o código divergiu do plano, pare e relate.

Objetivo: implementar o adaptador do PSP Pix escolhido atrás da porta PaymentProvider (autenticação, cobrança cob/cobv, webhook, consulta, devolução, mapeamento de erros), com testes de contrato em WireMock baseados na documentação oficial e roteiro de homologação executado com credenciais fora do repositório. Ajuste o mapeamento de CSV da conciliação se necessário. Não altere a porta sem registrar o motivo.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E09 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume endpoints, autenticação, webhooks e erros da documentação do PSP escolhido;
- escreva os testes de contrato primeiro, implemente, rode os comandos oficiais de build e teste do CLAUDE.md e o roteiro de homologação;
- verificação independente com o Prompt do verificador, com foco em segredo no repositório, erro tratado como sucesso e webhook sem validação de origem.

Critérios de aceite (detalhes na seção E09):
- testes de contrato do adaptador passam;
- roteiro de homologação executado com cobrança, confirmação e devolução, com evidência no handoff;
- nenhum segredo no repositório; build e testes oficiais passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E09 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Testes de contrato com WireMock cobrem cobrança, consulta, webhook, devolução e os erros documentados do PSP.
- [ ] Roteiro de homologação executado: cobrança criada, pagamento confirmado (webhook ou polling) e refletido na conta, devolução concluída — evidências no handoff.
- [ ] Credenciais e certificados só por variável de ambiente ou secret; nada no repositório.
- [ ] Build e testes oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Roteiro de homologação → evidências registradas (IDs de transação de homologação, sem dados pessoais).

**Bloqueios:**

- **Causa:** o PSP Pix não foi escolhido (SPEC Q-05).
- **Evidência:** SPEC §10 Q-05 e RF-PAG-06 dizem que o PSP "será definido depois"; não há credenciais de homologação.
- **Impacto:** impede o adaptador real e a homologação do Pix (critérios 1 a 3) e, por consequência, o gate E12.
- **Ação de desbloqueio:** o usuário escolhe o PSP e obtém credenciais de homologação; registrar a decisão em D1 e trocar a tag desta etapa para `#pendente` (índice e título).

**Riscos e mitigação:**

- PSP exige mTLS com certificado: guardar como Docker secret e documentar no `README.md`.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E10 — Adaptador real do adquirente — #bloqueada

**Objetivo:** implementar e homologar a integração de cartão com o adquirente escolhido, no modo decidido (TEF com biblioteca no cliente ou Smart POS com API em nuvem), incluindo pré-autorização, captura, cancelamento e tokenização.

**Por que agora:** é a primeira integração real com adquirente; caução e PDV dependem dela em produção.

**Dependências e paralelismo:** E03. Pode correr em paralelo com E09 e E11 (em worktrees separados); conflito de edição na configuração de integrações.

**Escopo:** Smart POS: adaptador (a criar em `payments/infrastructure/`) com testes de contrato e homologação. TEF: ajustes no contrato de reporte do cliente conforme a biblioteca escolhida (SiTef, PayGo ou outra) e documentação para o repositório do cliente; importação do extrato do adquirente para a conciliação (E05). Tokenização pelo componente do provedor. Validade real da pré-autorização para a renovação (E04).

**Fora de escopo:** implementação da biblioteca TEF no cliente (repositório do cliente).

**Passos de implementação:**

1. Confirmar D2 registrada (adquirente e modo) e credenciais de homologação disponíveis.
2. Escrever testes de contrato a partir da documentação do adquirente.
3. Implementar e executar o roteiro de homologação (venda, pré-autorização, captura parcial, cancelamento, token).

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "quais operações, autenticação, formatos de resultado e prazos de pré-autorização a documentação do adquirente escolhido define?".
- Partes paralelas: nenhuma.
- Foco do verificador: dado de cartão além de token, bandeira e 4 últimos chegando ao servidor; prazo de pré-autorização incompatível com a renovação de E04; segredo no repositório.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E10 — "Adaptador real do adquirente" do roadmap docs/roadmaps/roadmap-m7-pagamentos-fiscal.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução", as restrições de PCI e a decisão D2 —, a seção E10 inteira e o "Evidências e handoff" de E03, E04 e E05. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E03 está #concluida, que E10 está #pendente (não #bloqueada) e que D2 registra o adquirente e o modo (TEF ou Smart POS); se não, pare e relate sem implementar. Se o código divergiu do plano, pare e relate.

Objetivo: integrar o adquirente escolhido no modo decidido — adaptador Smart POS atrás da porta, ou contrato de reporte TEF ajustado à biblioteca escolhida e documentado para o cliente —, com venda, pré-autorização, captura, cancelamento e tokenização homologados, importação do extrato para a conciliação e prazo real de pré-autorização configurado para a renovação. O servidor continua sem receber número de cartão nem CVV.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E10 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume operações, autenticação, formatos de resultado e prazos de pré-autorização do adquirente escolhido;
- escreva os testes de contrato primeiro, implemente, rode os comandos oficiais de build e teste do CLAUDE.md e o roteiro de homologação;
- verificação independente com o Prompt do verificador, com foco em dado de cartão além do permitido, prazo de pré-autorização incompatível e segredo no repositório.

Critérios de aceite (detalhes na seção E10):
- testes de contrato passam;
- roteiro de homologação executado com venda, pré-autorização, captura parcial, cancelamento e token, com evidência no handoff;
- prazo de renovação configurado conforme o adquirente; build e testes oficiais passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E10 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Testes de contrato cobrem as operações e erros documentados do adquirente no modo escolhido.
- [ ] Roteiro de homologação executado: venda, pré-autorização, captura parcial, cancelamento e tokenização — evidências no handoff.
- [ ] Extrato do adquirente importado na conciliação de E05 sem divergência para as transações de homologação.
- [ ] Antecedência de renovação da pré-autorização configurada conforme o prazo do adquirente.
- [ ] Nenhum dado de cartão além de token, bandeira e 4 últimos chega ao servidor; nenhum segredo no repositório.
- [ ] Build e testes oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Roteiro de homologação → evidências registradas.

**Bloqueios:**

- **Causa:** adquirente e modo de cartão não foram escolhidos (SPEC Q-05; Q-13 afeta a biblioteca TEF nos clientes).
- **Evidência:** SPEC §10 Q-05 e Q-13 e ARCHITECTURE §18 ("TEF vs. Smart POS") estão em aberto; não há credenciais de homologação.
- **Impacto:** impede o adaptador real e a homologação de cartão e caução, e por consequência o gate E12.
- **Ação de desbloqueio:** o usuário escolhe o adquirente e o modo (TEF ou Smart POS) e obtém credenciais de homologação; registrar a decisão em D2 e trocar a tag desta etapa para `#pendente` (índice e título).

**Riscos e mitigação:**

- TEF depende do cliente desktop/PDV ainda não pronto: homologar o contrato de reporte com um cliente de teste e registrar a pendência no handoff.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E11 — Adaptador real do middleware fiscal — #bloqueada

**Objetivo:** implementar e homologar o adaptador do middleware fiscal escolhido atrás da porta `FiscalProvider`, emitindo NFS-e da hospedagem e NFC-e de A&B em homologação com as regras do município e do regime tributário do hotel.

**Por que agora:** é a primeira integração fiscal real; sem ela o hotel não emite nota pelo sistema.

**Dependências e paralelismo:** E08. Pode correr em paralelo com E09 e E10 (em worktrees separados); conflito de edição na configuração de integrações.

**Escopo:** adaptador (a criar em `fiscal/infrastructure/`), autenticação, envio, consulta, cancelamento, webhook; perfis fiscais iniciais do hotel conforme o regime e o município; validação da regra de arredondamento (RN-02) com o middleware; testes de contrato; roteiro de homologação de NFS-e e NFC-e (autorização, rejeição corrigida e reenviada, cancelamento).

**Fora de escopo:** certificados e cadastros na prefeitura/SEFAZ (responsabilidade do hotel).

**Passos de implementação:**

1. Confirmar D3 registrada (middleware, regime, município) e credenciais de homologação disponíveis.
2. Escrever testes de contrato a partir da documentação do middleware.
3. Implementar, configurar os perfis fiscais iniciais e executar o roteiro de homologação.
4. Validar o arredondamento com o middleware e registrar a regra no handoff (e na função única de `Money`, se precisar mudar).

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "quais endpoints, campos de NFS-e e NFC-e, campos de IBS/CBS, webhooks e regras de arredondamento a documentação do middleware escolhido define?"; "o município do hotel usa o emissor nacional de NFS-e?".
- Partes paralelas: nenhuma.
- Foco do verificador: arredondamento divergente entre servidor e middleware; perfil fiscal com dado errado do município; certificado ou token no repositório.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E11 — "Adaptador real do middleware fiscal" do roadmap docs/roadmaps/roadmap-m7-pagamentos-fiscal.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução", a hipótese H7 e a decisão D3 —, a seção E11 inteira e o "Evidências e handoff" de E06, E07 e E08. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E08 está #concluida, que E11 está #pendente (não #bloqueada) e que D3 registra o middleware, o regime tributário e o município; se não, pare e relate sem implementar. Se o código divergiu do plano, pare e relate.

Objetivo: implementar o adaptador do middleware fiscal escolhido atrás da porta FiscalProvider (envio, consulta, cancelamento, webhook), configurar os perfis fiscais iniciais conforme regime e município, validar a regra de arredondamento com o middleware (RN-02) e homologar NFS-e e NFC-e, incluindo rejeição corrigida e reenviada e cancelamento. Credenciais e certificados ficam fora do repositório.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E11 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume endpoints, campos de NFS-e, NFC-e e IBS/CBS, webhooks e arredondamento do middleware escolhido, e se o município usa o emissor nacional;
- escreva os testes de contrato primeiro, implemente, rode os comandos oficiais de build e teste do CLAUDE.md e o roteiro de homologação;
- verificação independente com o Prompt do verificador, com foco em arredondamento divergente, perfil fiscal errado e segredo no repositório.

Critérios de aceite (detalhes na seção E11):
- testes de contrato passam;
- NFS-e e NFC-e autorizadas em homologação, com rejeição corrigida e cancelamento, evidências no handoff;
- arredondamento validado com o middleware e registrado; build e testes oficiais passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E11 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Testes de contrato com WireMock cobrem envio, consulta, cancelamento, webhook e erros documentados.
- [ ] Em homologação: NFS-e de hospedagem autorizada, NFC-e de A&B autorizada, uma rejeição corrigida e reenviada até autorizar e um cancelamento — evidências no handoff.
- [ ] Os totais calculados pelo servidor batem com os do middleware nas notas de homologação; a regra de arredondamento está registrada.
- [ ] Perfis fiscais iniciais do hotel configurados conforme D3.
- [ ] Nenhuma credencial ou certificado no repositório; build e testes oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Roteiro de homologação → evidências registradas (números e chaves de homologação).

**Bloqueios:**

- **Causa:** middleware fiscal, regime tributário e regras do município não foram definidos (SPEC Q-05, Q-02, Q-03).
- **Evidência:** SPEC §10 Q-02, Q-03 e Q-05 estão em aberto; não há credenciais de homologação.
- **Impacto:** impede a emissão real de NFS-e e NFC-e e a validação do arredondamento (RN-02), e por consequência o gate E12.
- **Ação de desbloqueio:** o usuário escolhe o middleware, confirma regime e município com o contador e obtém credenciais de homologação; registrar a decisão em D3 e trocar a tag desta etapa para `#pendente` (índice e título).

**Riscos e mitigação:**

- Regras de IBS/CBS mudando em 2027 para o Simples (RF-FIS-04): perfis configuráveis e revisão anual com o contador.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E12 — Gate do marco M7 — #pendente

**Objetivo:** confirmar, a partir de uma cópia limpa do repositório, que toda a definição global de pronto do M7 é atendida, incluindo os roteiros de homologação com os fornecedores reais.

**Por que agora:** é o critério de conclusão do marco; o gate final de M8 (inauguração) exige este gate concluído.

**Dependências e paralelismo:** E04, E05, E09, E10, E11 (que cobrem transitivamente todas as demais). Não paralelizável.

**Escopo:**

- Rodar build, testes, verificação de módulos e do contrato numa cópia limpa.
- Cenário ponta a ponta em homologação: reserva com sinal por Pix, check-in com caução por cartão, consumo lançado no quarto, check-out com captura parcial, NFS-e e NFC-e autorizadas, conciliação sem divergências.
- Conferir cada item da definição global de pronto com evidência.
- Atualizar `docs/roadmaps/README.md` registrando a conclusão do M7 no histórico de revisões.

**Fora de escopo:** funcionalidade nova. Problemas voltam para a etapa de origem (reabrir com `#em-andamento` e registrar no handoff dela), salvo ajustes triviais de documentação.

**Passos de implementação:**

1. Rodar as validações numa cópia limpa.
2. Executar o cenário ponta a ponta em homologação e registrar as evidências.
3. Marcar a definição global de pronto e atualizar o README dos marcos.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma.
- Partes paralelas: nenhuma.
- Foco do verificador: item da definição de pronto sem evidência; cenário que só passa com o adaptador falso; dado de cartão ou pessoal em log de homologação.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E12 — "Gate do marco M7" do roadmap docs/roadmaps/roadmap-m7-pagamentos-fiscal.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a "Definição global de pronto" —, a seção E12 inteira e o "Evidências e handoff" de todas as etapas E01 a E11. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 a E11 estão #concluida; se não, ou se o código divergiu do plano, pare e relate.

Objetivo: verificar numa cópia limpa do repositório que cada item da definição global de pronto do M7 é atendido, executando em homologação o cenário ponta a ponta: sinal por Pix, check-in com caução por cartão, consumo lançado no quarto, check-out com captura parcial, NFS-e e NFC-e autorizadas e conciliação sem divergências. Não implemente funcionalidade nova: problemas são devolvidos à etapa de origem, salvo ajustes triviais de documentação. Ao final, registre a conclusão do M7 no histórico de docs/roadmaps/README.md.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E12 para #em-andamento no índice e no título;
- sem investigação delegada;
- rode do zero, numa cópia limpa, os comandos oficiais de build e teste do CLAUDE.md e a verificação do contrato, execute o cenário em homologação e registre a evidência de cada item;
- verificação independente com o Prompt do verificador, com foco em item sem evidência, cenário que só passa com adaptador falso e dado de cartão ou pessoal em log.

Critérios de aceite (detalhes na seção E12):
- todos os itens da definição global de pronto marcados com evidência;
- cenário ponta a ponta executado em homologação com os fornecedores reais;
- docs/roadmaps/README.md registra a conclusão do M7.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E12 (snapshot, verificações, resultados, parecer do verificador, notas para o marco M8) e responda com um resumo curto. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Todos os itens da "Definição global de pronto" estão marcados, cada um com evidência numa cópia limpa ou em homologação.
- [ ] O cenário ponta a ponta foi executado com os adaptadores reais, sem divergências na conciliação.
- [ ] Logs da homologação não contêm dado de cartão nem dados pessoais em claro.
- [ ] `docs/roadmaps/README.md` registra a conclusão do M7 no histórico de revisões.

**Validação obrigatória:**

- Comandos oficiais de build, teste e verificação do contrato numa cópia limpa → sucesso.
- Cenário ponta a ponta em homologação → evidências registradas.

**Bloqueios:** Nenhum bloqueio conhecido. Esta etapa só fica disponível depois que E09, E10 e E11 forem desbloqueadas e concluídas.

**Riscos e mitigação:**

- Indisponibilidade dos ambientes de homologação dos fornecedores: repetir o cenário em outro horário e registrar as tentativas.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para o marco M8: —

---

## Histórico de revisões

- 2026-10-13 — roadmap criado.
- 2026-10-13 — aplicadas as decisões da entrevista SPEC × ARCHITECTURE (regra por camadas na ARCHITECTURE §3.1): H5 (`fiscal` → `files`) passa a fato; alertas de rejeição fiscal e falha de integração (RF-NOT-01) saem do módulo que detecta o problema via `notifications`; E06, E07 e E04 ajustados (escopo, prompt e critérios).
