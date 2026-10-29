# Roadmap — M7 Pagamentos integrados e fiscal

> Arquivo: `docs/roadmaps/roadmap-m7-pagamentos-fiscal.md` · branch de execução: `roadmap/m7-pagamentos-fiscal` · criado em 2026-10-13 · última revisão 2026-10-22
>
> Marco 7 de 11 · mapa dos marcos em [`docs/roadmaps/README.md`](README.md) · anterior: [M6 — Operação diária e fechamento](roadmap-m6-operacao-fechamento.md) · próximo: [M8 — Restaurante, distribuição e inauguração](roadmap-m8-restaurante-distribuicao.md)
>
> **Pré-requisito:** o gate do marco M6 (última etapa de `docs/roadmaps/roadmap-m6-operacao-fechamento.md`) está `#concluida`.

## Como usar

Inicie uma sessão limpa do Claude Code na raiz do repositório com `claude --agent orquestrador` e cole o "Prompt de execução". Ele executa até 3 etapas em sequência na branch `roadmap/m7-pagamentos-fiscal`, cada uma planejada pelo plugin superpowers e executada pelas souls (subagentes do Second Brain), e para para revisão. No fim do lote você decide como integrar a branch (merge, PR, manter ou descartar). Para não parar em cada pedido de permissão, libere antes os comandos de build/teste do projeto nas permissões do Claude Code (os comandos oficiais estão na tabela do `CLAUDE.md`; o Docker é necessário para os testes de integração).

**Este roadmap foi escrito antes do código existir.** Antes da primeira etapa, sincronize-o com o código real usando a skill `development-roadmap` (modo atualização): confira caminhos, nomes de módulos, comandos e convenções registrados nos handoffs de M0–M6 e nas notas de tema do brain e ajuste as etapas pendentes.

## Objetivo

Integrar os meios de pagamento e a emissão fiscal ao servidor: cobrança Pix com confirmação automática e devolução, cartão integrado (TEF ou Smart POS) com pré-autorização, captura e cancelamento, caução no check-in, tokenização para garantia, conciliação, e emissão de NFS-e e NFC-e por middleware fiscal com IBS/CBS. Tudo atrás de portas neutras, com adaptadores falsos testados por WireMock primeiro; os adaptadores reais de cada fornecedor só são escritos depois que a SPEC Q-05 for decidida. Ao fim do marco, contas e check-out emitem nota e recebem pagamentos sem digitação.

## Contexto confirmado

- Requisitos: SPEC §5.11 (RF-PAG-01 a 06), §5.12 (RF-FIS-01 a 09), §5.10 (RF-CTA-06 a 08, RF-CTA-10 e RF-CTA-15: pagamentos, caução, caixa e reembolso).
- Modelo de domínio: SPEC §8.2.7 (`Folio`, `Payment`, `CashierShift`, `CardPreAuthorization` em contas) e §8.2.8 (`PaymentIntent`, `PixCharge`, `CardTransaction`, `CardToken`, `PaymentProvider`, `FiscalProfile`, `FiscalDocument`, `FiscalDocumentItem`, `TaxAmount`, `FiscalProvider`).
- Máquina de estados do documento fiscal: SPEC §8.4. Eventos `PaymentSucceeded` e `FiscalDocumentAuthorized`/`Rejected`: SPEC §8.3.
- Regras: RN-02 (arredondamento único validado com o middleware), RN-03 (imutabilidade financeira), RN-12 (ações sensíveis), RNF-02 (sem portas abertas com servidor local), RNF-10 (PCI-DSS).
- Arquitetura: módulos `payments` (depende de `shared`), `fiscal` (depende de `shared`, `hotel`, `files`), `billing` (depende de `payments` e `fiscal`), `frontdesk` (depende de `payments`, `fiscal`, `billing`) e `endofday` (depende de `payments`, `billing`) — ARCHITECTURE §3.1. Padrão de integração, tabela de integrações e conciliação de TEF: ARCHITECTURE §12. Jobs de polling e renovação de pré-autorizações: §13. Idempotência: §7. PCI e cifragem de credenciais: §8.3. XMLs fiscais no armazenamento de arquivos: §14. Regra por camadas da §3.1: qualquer módulo pode chamar os serviços de plataforma (`files`, `notifications`, `printing`, `hotel`) e depender de camadas inferiores sem aprovação, desde que o grafo siga acíclico e a aresta nova seja acrescentada à tabela §3.1 na mesma mudança.
- Convenções herdadas (ler nos handoffs e nas notas de tema do brain): M0 (API, erros, `Money`, IDs, `Idempotency-Key`, outbox, ShedLock, CI), M1 (autorização, step-up por PIN, auditoria, `hotel`, `files`), M2 (notificações), M3 (`billing` e `payments` com registro manual e `PaymentIntent` neutro), M5 (padrão de integração porta + adaptador + outbox + WireMock da FNRH, credenciais de serviço), M6 (etapas do fechamento do dia).
- Caminhos previstos (a criar, sob o pacote base `com.zeny.hotel`, hipótese herdada de M0): `payments/` e `fiscal/` com `domain/`, `application/`, `infrastructure/`, `web/`; migrações nas pastas `payments` e `fiscal` de `src/main/resources/db/migration/`.
- Comandos de build, teste e lint: os oficiais da tabela do `CLAUDE.md` (definidos em M0 E01).
- Conhecimento do projeto no Second Brain: hub `ZenyHotelManager` e as notas de tema `ZenyHotelManager - Módulo billing (caixa, pagamentos, caução e encerramento)` (inclui o módulo `payments`: `PaymentIntent`, porta `PaymentProvider`, provedor `MANUAL`, `PaymentSucceeded`, caução manual, reembolso), `ZenyHotelManager - Módulo billing (contas, roteamento e taxa de serviço)` (lançamentos, códigos de lançamento, roteamento, taxa de serviço), `ZenyHotelManager - Hospedagem e recepção` (check-in e check-out no `frontdesk`), `ZenyHotelManager - Módulo guests` (documentos cifrados do tomador, empresas), `ZenyHotelManager - Módulo files` (XML e PDF, URL assinada), `ZenyHotelManager - Módulos hotel e staff` (data operacional, fuso, taxas), `ZenyHotelManager - Módulo notifications` (alertas de falha e de rejeição), `ZenyHotelManager - Segurança, autenticação e autorização` (permissões, step-up), `ZenyHotelManager - Auditoria`, `ZenyHotelManager - Eventos e jobs` (outbox, listeners, ShedLock), `ZenyHotelManager - Convenções de API` (idempotência, enum extensível, contrato), `ZenyHotelManager - Tipos do kernel` (`Money`, cifragem de campo), `ZenyHotelManager - Schemas, migrações e views de relatório`, `ZenyHotelManager - Arquitetura e estrutura de módulos`, `ZenyHotelManager - Ambiente, banco, chaves e build` (variáveis e segredos), `ZenyHotelManager - Docker, observabilidade e CI` (compose, métricas, alertas), `ZenyHotelManager - Testes de integração` e `ZenyHotelManager - Estado por marco`. O padrão de integração da FNRH (M5) e as etapas do fechamento do dia (M6) entram no brain pelas capturas de atualização desses marcos: leia as pendentes que o hook listar.

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

- Toda execução acontece no worktree/branch `roadmap/m7-pagamentos-fiscal`, criado ou retomado com `superpowers:using-git-worktrees`. Nunca execute na branch principal (`main`).
- Se a branch já existir com trabalho não integrado, continue nela. Se o lote anterior já foi integrado, crie a branch de novo a partir da `main` atualizada.
- Este roadmap é atualizado dentro da branch: tags e handoffs viajam com o código.

**Por etapa**

1. **Escolha:** a primeira etapa, na ordem do documento, marcada `#pendente` e com todas as dependências `#concluida`. Etapas `#bloqueada` e as que dependem delas ficam para trás.
2. **Conferência:** leia a seção da etapa, os handoffs das dependências e o código que eles citam. Se o código divergiu do que a etapa supõe, pare e relate.
3. **Início:** troque a tag para `#em-andamento` (índice e título).
4. **Plano:** use `superpowers:writing-plans`. A spec é a seção desta etapa mais os documentos citados em "Contexto confirmado"; as "Restrições e invariantes" entram como Global Constraints. Salve em `docs/superpowers/plans/AAAA-MM-DD-m7-pagamentos-fiscal-eNN.md` (AAAA-MM-DD = a data do dia). Antes de executar, o plano passa pela revisão de arquitetura do `orquestrador` (`revisor-arquitetura`); as regras (rodadas, veredito, seção no plano) vivem só nele. A execução já está definida (abaixo): não pergunte ao usuário o método, apenas siga.
5. **Execução:** use `superpowers:subagent-driven-development` com esse plano, executado pelas souls conforme o ciclo por tarefa, a verificação mecânica dos testes e a revisão final da etapa definidos no `orquestrador` (as regras de encaixe vivem só nele). Não invoque `superpowers:finishing-a-development-branch` ao fim da etapa: ele roda uma vez, no fim do lote.
6. **Fechamento:** publique a captura de atualização do brain (bloco "Second Brain"). Marque `#concluida` (índice e título) só com a revisão final limpa e cada critério de aceite verificado com evidência. Diante de impedimento real, marque `#bloqueada` e preencha "Bloqueios" com causa, evidência, impacto e ação de desbloqueio. Preencha "Evidências e handoff". Rulings do ledger que mudam contrato, escopo, segurança, dados ou algo caro de desfazer vão para "Decisões para o usuário".

**Second Brain**

Vale se o `CLAUDE.md` tiver a seção "Conhecimento do projeto no Second Brain"; senão, ignore este bloco.

- O contexto da sessão precisa trazer a linha `BRAIN: carregado (ZenyHotelManager)`, injetada pelo hook. Sem ela, pare e relate.
- Na conferência de cada etapa, leia as notas de tema do brain ligadas a ela (pelo hub `ZenyHotelManager`) e as capturas pendentes do projeto que tratem do assunto: são a referência técnica do projeto. Num handoff antigo, "seção X do `CLAUDE.md`" é a nota de tema correspondente.
- No fechamento de cada etapa que mudou algo descrito no brain (inclusive o estado do roadmap), o orquestrador manda o `brain-escriba` publicar em `00 Inbox/` do vault a captura `ZenyHotelManager - atualização m7-pagamentos-fiscal Eyy`, com o texto novo ou alterado de cada nota afetada e qual nota ele substitui ou complementa; o contrato de agente externo do vault fica com o `brain-escriba`. Nunca edite notas do brain nem registre referência técnica no `CLAUDE.md`.

**Paradas**

O lote para ao concluir uma etapa com **Revisão humana: sim**, diante de etapa `#bloqueada` ou divergência do plano, nas paradas do próprio superpowers (operação destrutiva, ação sensível de segurança, efeito fora da branch, plano sem caminho viável) e ao atingir o limite de etapas do prompt.

**Fim do lote**

1. Use `superpowers:finishing-a-development-branch`: o usuário decide merge, PR, manter ou descartar a branch. Nunca faça push, merge ou deploy sem essa decisão.
2. Apresente as "Decisões para o usuário" acumuladas como entrevista: perguntas de múltipla escolha, cada uma com uma opção recomendada e justificada (no Claude Code, `AskUserQuestion` com a recomendada em primeiro lugar e o rótulo "(Recomendado)"). Registre as respostas no handoff das etapas e em "Hipóteses e decisões em aberto".

## Prompt de execução

```text
Execute o roadmap docs/roadmaps/roadmap-m7-pagamentos-fiscal.md deste repositório seguindo a seção "Protocolo de execução" dele, com o plugin superpowers.

Limite desta execução: no máximo 3 etapas concluídas.

1. Confira a pré-condição do protocolo: esta sessão roda como orquestrador e as souls desenvolvedor, testador, revisor-seguranca e revisor-arquitetura estão disponíveis; se não, pare e diga como resolver. Leia CLAUDE.md (e AGENTS.md, se existir) e, no roadmap, o cabeçalho inteiro, o protocolo e o índice. Se o CLAUDE.md exigir o Second Brain, confira a linha BRAIN: carregado no contexto e leia o hub. Rode git status e git branch -a.
2. Se alguma etapa estiver #em-andamento, retome-a a partir do ledger do superpowers e do handoff; não recomece tarefas já commitadas.
3. Crie ou retome o worktree/branch roadmap/m7-pagamentos-fiscal com superpowers:using-git-worktrees. Não trabalhe na branch principal.
4. Repita, uma etapa por vez: escolha a próxima etapa elegível, marque #em-andamento, planeje com superpowers:writing-plans (spec = seção da etapa + documentos citados), execute com superpowers:subagent-driven-development, feche a etapa (com a captura de atualização do brain) e preencha o handoff conforme o protocolo.
5. Pare nas paradas do protocolo ou ao atingir o limite.
6. No fim, rode superpowers:finishing-a-development-branch e deixe a decisão de integração comigo. Depois, apresente as decisões para o usuário como entrevista com opção recomendada.

Nunca faça push, merge ou deploy sem minha decisão. Não altere tags de etapas que não executou.

Ao parar, responda com: etapas concluídas e o resultado da revisão final de cada uma, a etapa atual e seu estado, o motivo da parada, as etapas #bloqueada com a ação de desbloqueio, a branch e os commits do lote, as capturas do brain publicadas e o próximo passo sugerido.
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

**Dependências:** nenhuma dentro deste roadmap; exige o gate de M6 concluído. Independente de E06 (podem rodar em branches separadas); sem conflito de arquivos de domínio, conflito possível em `api/openapi.yaml`.

**Escopo:**

- `payments/` (existente desde M3; arquivos novos a criar): porta `PaymentProvider` (criar cobrança Pix, consultar status, devolver, iniciar operação de cartão em terminal remoto, consultar transações para conciliação), seleção do provedor por configuração, credenciais cifradas.
- `PaymentIntent`: estados (criado, pendente, sucesso, falha, expirado, reembolsado parcial/total), expiração, `markSucceeded`/`markFailed`/`refund` (SPEC §8.2.8), idempotência por identificador do provedor; publica `PaymentSucceeded` (já consumido por `billing` desde M3) e eventos de falha/reembolso.
- Infraestrutura de integração (padrão de M5, H2): outbox, retry com backoff, log de integração com payload redigido, indicador de saúde e evento genérico de falha.
- Adaptador falso com cliente HTTP real apontado para WireMock nos testes.
- Migração na pasta `payments`.

**Fora de escopo:** fluxos Pix e cartão específicos (E02, E03); adaptadores reais (E09, E10).

**Critérios de aceite:**

- [ ] Testes de domínio cobrem todas as transições do `PaymentIntent`, incluindo transição inválida rejeitada com `code` estável e expiração.
- [ ] Uma confirmação repetida com o mesmo identificador do provedor não muda o estado nem publica um segundo `PaymentSucceeded` (teste).
- [ ] O adaptador falso, testado com WireMock, cobre sucesso, timeout com retry e backoff, e erro permanente que gera o evento genérico de falha.
- [ ] Credenciais do provedor ficam cifradas no banco; o log de integração não contém credenciais nem dados pessoais (teste).
- [ ] O indicador de saúde da integração de pagamentos aparece no actuator/painel do padrão de M5.
- [ ] `ApplicationModules.verify()` passa sem novas dependências de `payments` além de `shared`.
- [ ] Build, testes e verificação do contrato oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sem diff pendente e sem *breaking change*.

**Revisão humana:** não

**Notas para o plano:**

- Leia o handoff da M3 E08 (`docs/roadmaps/roadmap-m3-hospedes-contas.md`, "Notas para o marco M7"): a porta `PaymentProvider`, o `PaymentProviderCode` e o provedor `MANUAL` já existem no `payments` e o `PaymentIntentService.start` já trata `ProviderOutcome.Pending` e `Failed`; falta o caminho de confirmação posterior (caso de uso que trava a intenção, `markSucceeded`/`markFailed` e publica o evento) e o `PaymentSucceededListener` do `billing` hoje **falha** com intenção sem pagamento (H1).
- H2: se o padrão de integração do M5 (FNRH) ficou acoplado a ela, extraia a parte genérica para `shared` antes de usar.
- Valide o desenho da porta contra a documentação pública de ao menos um PSP Pix e de um adquirente (risco abaixo).
- Tensão a resolver na sincronização com o código: o critério de módulos desta etapa mantém `payments` só com `shared`, mas o handoff da M3 E08 prevê a aresta `payments` → `notifications` no M7 (alerta `notifications.integration-failure`, ARCHITECTURE §3.1); até o usuário decidir, o alerta de falha sai pelo mecanismo genérico de falha de integração do M5 (H2).

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Porta desenhada para um único provedor: validar contra dois provedores conhecidos (documentação pública de um PSP e de um adquirente) na investigação.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E02 — Pix: cobrança, confirmação e devolução — #pendente

**Objetivo:** permitir cobrar por Pix (QR Code e copia-e-cola) para conta, sinal de reserva ou outro alvo, confirmar o pagamento automaticamente por webhook ou por consulta periódica e devolver valores com aprovação.

**Por que agora:** Pix é o meio integrado mais usado e o mais simples de homologar; valida a porta de E01 ponta a ponta.

**Dependências:** E01. Independente de E03 (podem rodar em branches separadas); conflito de edição na configuração do provedor em `payments` e em `api/openapi.yaml`.

**Escopo:**

- Endpoints em `/api/v1` para criar cobrança Pix (com `Idempotency-Key`), consultar status e solicitar devolução (ação sensível com step-up, RF-CTA-15).
- `PixCharge` (txid, copia-e-cola, vencimento, endToEndId) e cobrança com vencimento (`cobv`) para sinal de reserva.
- Endpoint de webhook do PSP (H3), idempotente pelo txid/endToEndId; job de polling com ShedLock (ARCHITECTURE §13) para modo sem webhook, selecionado por configuração.
- Turno de caixa da confirmação assíncrona (H4); integração com `billing` só via `PaymentSucceeded` (o alvo segue no evento).
- Expiração de cobranças não pagas.

**Fora de escopo:** caução via Pix (E04); adaptador real (E09); cobrança de comanda (M8, usa este serviço).

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

**Revisão humana:** não

**Notas para o plano:**

- H3 (autenticação do webhook por credencial de serviço ou assinatura do provedor, no padrão de M1/M5) e H4 (turno de caixa da confirmação assíncrona): registre a regra do turno como decisão no handoff, inclusive o caso de o turno já ter fechado antes da confirmação (pendência da H13 do M3, citada nas "Notas para o marco M7" do handoff da M3 E08).
- O handoff da M3 E08 descreve o encaixe esperado no `billing`: o recebimento integrado aceita a intenção `PENDING` e guarda o pedido (conta, forma, turno, depósito) até a confirmação, e o listener registra o `Payment` com `INSERT … ON CONFLICT (payment_intent_id) DO NOTHING`; a devolução integrada passa por um `PaymentsApi.refund` do provedor da intenção original, e o reembolso manual continua para o `MANUAL`.
- Modele txid, endToEndId e estados pelo padrão da API Pix do Banco Central (cob, cobv, webhook, devolução).

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Confirmação tardia de cobrança expirada: registrar como divergência para a conciliação (E05) em vez de descartar.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E03 — Cartão integrado e tokenização — #pendente

**Objetivo:** suportar cartão integrado nos dois modos da ARCHITECTURE §12 — TEF, em que o cliente executa a transação e reporta o resultado, e Smart POS, em que o servidor envia o pedido ao terminal pela API do adquirente — com venda, pré-autorização, captura e cancelamento, e tokenização de cartão para garantia de reserva, sem que o servidor jamais receba número de cartão ou CVV.

**Por que agora:** a caução (E04) e o PDV (M8) dependem desses fluxos. A decisão TEF × Smart POS ainda está aberta (D2), então os dois modos ficam prontos atrás da porta.

**Dependências:** E01. Independente de E02 (podem rodar em branches separadas); conflito de edição na configuração do provedor em `payments` e em `api/openapi.yaml`.

**Escopo:**

- `CardTransaction` (terminal, tipo, NSU, autorização, bandeira, 4 últimos) e `CardToken` (token do provedor, bandeira, 4 últimos, titular, validade).
- TEF: endpoint para criar o `PaymentIntent` de cartão e endpoint para o cliente reportar o resultado (idempotente por NSU + terminal), marcado como "não verificado" até a conciliação (ARCHITECTURE §12).
- Smart POS: envio do pedido ao terminal via porta, resultado por webhook ou polling.
- Operações: venda, pré-autorização, captura total ou parcial, cancelamento (ação sensível com step-up).
- Tokenização (RF-PAG-04): endpoint que recebe só o token gerado pelo componente do provedor e o associa a um titular/reserva.
- Guardas PCI: validação que rejeita qualquer campo com sequência que pareça PAN (Luhn, 13–19 dígitos) nos DTOs de pagamento, e máscara no log.

**Fora de escopo:** caução e renovação (E04); biblioteca TEF nos clientes; adaptador real (E10).

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

**Revisão humana:** não

**Notas para o plano:**

- D2 em aberto: os dois modos (TEF e Smart POS) ficam prontos atrás da porta; modele o reporte de NSU, autorização e bandeira pelo que integradores TEF conhecidos (SiTef, PayGo) e Smart POS devolvem.
- O M3 já recusa NSU e autorização com mais de 12 dígitos e só guarda bandeira e 4 últimos dígitos (regras de cartão do pagamento manual, nota de tema `ZenyHotelManager - Módulo billing (caixa, pagamentos, caução e encerramento)`); a guarda por Luhn desta etapa deve ser coerente com essas regras, sem afrouxá-las.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- TEF reportado pelo cliente não é verificável pelo servidor: marcação "não verificado" e conciliação (E05) cobrem o risco, conforme ARCHITECTURE §12.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E04 — Caução, renovação e reembolso — #pendente

**Objetivo:** implementar a caução da RF-CTA-08: pré-autorização de cartão (ou cobrança Pix) no check-in, captura total ou parcial no check-out com liberação da sobra, renovação de pré-autorizações perto do vencimento como etapa do fechamento do dia, e reembolsos com aprovação (RF-CTA-15).

**Por que agora:** depende dos fluxos de Pix (E02) e cartão (E03) e é o que liga pagamentos integrados à recepção.

**Dependências:** E02, E03. Independente de E05 (podem rodar em branches separadas); conflito de edição em `api/openapi.yaml`.

**Escopo:**

- `billing`: `CardPreAuthorization` (existente ou a criar conforme handoff de M3) com validade, valor, captura e liberação; caução via Pix como cobrança seguida de devolução.
- `frontdesk`: check-in passa a oferecer caução integrada; check-out captura o necessário e libera a sobra (`frontdesk` → `payments`, `billing`, permitido pelo grafo).
- `endofday`: nova etapa idempotente que renova pré-autorizações perto do vencimento (ARCHITECTURE §13), com alerta em falha.
- Reembolso de pagamento (devolução Pix ou estorno de cartão) a partir da conta, com step-up e auditoria.

**Fora de escopo:** contas a receber (M10); PDV (M8).

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

**Revisão humana:** não

**Notas para o plano:**

- Leia os handoffs da M3 E09 (caução manual: `CardPreAuthorization` já existe no `billing`, com a expiração calculada na leitura, sem job, validade máxima de 90 dias e `BillingApi.cardPreAuthorizationsExpiring` feita para o fechamento do dia e a renovação do M7), do M5 (check-in, que já registra a caução manual pela `BillingApi.registerCardPreAuthorization`, e check-out no `frontdesk`) e do M6 (como uma etapa do fechamento do dia é definida e tornada idempotente).
- Os testes de módulo seguem o `Scenario` do Spring Modulith, como nos marcos anteriores.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Prazo de validade de pré-autorização varia por adquirente: tornar a antecedência de renovação configurável e revisar em E10.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E05 — Conciliação de pagamentos — #pendente

**Objetivo:** confrontar as transações informadas pelo provedor com os pagamentos registrados (RF-PAG-05) e destacar divergências: pagamento sem transação, transação sem pagamento, valor diferente e TEF "não verificado" sem correspondência.

**Por que agora:** é o controle que cobre o risco do TEF reportado pelo cliente (ARCHITECTURE §12) e de confirmações tardias de Pix (E02).

**Dependências:** E02, E03. Independente de E04 (podem rodar em branches separadas); conflito de edição em `api/openapi.yaml`.

**Escopo:**

- `payments`: consulta de transações do provedor por período via porta; importação manual de extrato (CSV) como alternativa quando o provedor não oferecer API.
- Execução de conciliação por período e provedor, com resultado persistido e divergências classificadas; marcação de TEF como verificado quando conciliado.
- Endpoint de consulta e de resolução manual de divergência (com motivo, auditada).
- Job diário opcional com ShedLock e alerta quando houver divergência.

**Fora de escopo:** conciliação bancária e financeiro administrativo (SPEC Q-01, fora da v1).

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

**Revisão humana:** não

**Notas para o plano:**

- A marcação "não verificado" do TEF vem de E03 e a confirmação tardia de cobrança Pix expirada fica registrada como divergência em E02: leia os handoffs das duas.
- O handoff da M3 E08 ("Notas para o marco M7") indica a chave da comparação: as intenções do provedor contra `billing.payment` pelo `payment_intent_id` e pela referência do provedor (`payments.payment_intent.provider_reference`); o TEF reportado pelo cliente entra como um provedor cuja confirmação vem pela API.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Formato do extrato depende do fornecedor: importação de CSV com mapeamento configurável, revisada em E09 e E10.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E06 — Núcleo fiscal: perfis, documento e fila de emissão — #pendente

**Objetivo:** criar o módulo `fiscal` com perfis fiscais (RF-FIS-03), o agregado `FiscalDocument` com a máquina de estados da SPEC §8.4, a porta neutra `FiscalProvider` com adaptador falso testado por WireMock, a fila de emissão com reprocessamento (RF-FIS-08) e status por webhook ou polling.

**Por que agora:** NFS-e (E07) e NFC-e (E08) usam o mesmo núcleo; o contrato fiscal precisa ser revisado antes de se espalhar por contas, recepção e PDV.

**Dependências:** nenhuma dentro deste roadmap; exige o gate de M6 concluído. Independente de E01–E05 (podem rodar em branches separadas); conflito possível só em `api/openapi.yaml` e na configuração de integrações.

**Escopo:**

- Módulo `fiscal/` (a criar, depende de `shared`, `hotel` e `files`; `notifications` para alertas), schema e migrações próprios.
- `FiscalProfile` (código de serviço municipal/NBS, NCM, CFOP, ISS, CST e cClassTrib de IBS/CBS, regime específico) com CRUD protegido por permissão; vínculo por código de lançamento e por produto de venda (referência por ID).
- `FiscalDocument`, `FiscalRecipient`, `FiscalDocumentItem`, `TaxAmount` (SPEC §8.2.8); estados pendente → processando → autorizado/rejeitado → cancelado; correção e reenvio de rejeitado; cancelamento sensível (step-up) e dentro do prazo legal configurável.
- Porta `FiscalProvider` (`submit`, `queryStatus`, `cancel`); adaptador falso com WireMock; webhook de status e job de polling com ShedLock; fila com retry e backoff quando o middleware, a SEFAZ ou a prefeitura estiverem indisponíveis.
- Eventos `FiscalDocumentAuthorized` e `FiscalDocumentRejected`; alerta de rejeição (RF-NOT-01) enviado por `fiscal` via `notifications`, diretamente ou pelo mecanismo genérico de falha de M5; aresta `fiscal` → `notifications` acrescentada à tabela §3.1 se for direta.
- API pública do módulo para solicitar emissão a partir de itens já calculados (sem conhecer `billing` nem `pos`).

**Fora de escopo:** montagem da NFS-e a partir da conta (E07); NFC-e e relatórios (E08); middleware real (E11).

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

**Revisão humana:** sim

**Notas para o plano:**

- H2 e H7; leia o handoff do M5 sobre o padrão de integração (porta, adaptador, outbox, retry, log redigido, saúde, falha genérica).
- Modele a porta `FiscalProvider` a partir de ao menos dois middlewares conhecidos (PlugNotas, Focus NFe, NFE.io): envio, status, cancelamento e webhooks; os campos de IBS/CBS (CST, cClassTrib) seguem o que a NFS-e nacional exige desde 01/10/2026 (RF-FIS-04), no perfil fiscal, sem regra fixa em código.
- Módulo novo: a captura de atualização do brain desta etapa propõe a nota de tema nova `ZenyHotelManager - Módulo fiscal`; as arestas novas do `fiscal` entram na tabela da ARCHITECTURE §3.1 na mesma mudança.
- O `billing` já guarda em `TransactionCode` uma classificação fiscal em texto livre (`fiscal_classification`, H2 do M3), à espera do vínculo com `FiscalProfile` sem migração destrutiva.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Porta acoplada a um middleware específico: modelar a partir de dois middlewares na investigação.
- Mudanças de IBS/CBS (ARCHITECTURE §18): campos no perfil fiscal, sem regra fixa em código.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E07 — NFS-e da hospedagem no check-out — #pendente

**Objetivo:** emitir NFS-e para hospedagem e demais serviços a partir de uma conta inteira ou de uma seleção de lançamentos (RF-FIS-01, RF-FIS-05), com separação de receitas e destaque de IBS/CBS (RF-FIS-04), guardar XML e PDF (RF-FIS-07) e vincular a nota à conta.

**Por que agora:** com o núcleo pronto (E06), é a emissão de maior volume e a que encerra a emissão manual no portal da prefeitura (SPEC §9, Fase 1).

**Dependências:** E06. Não rodar junto com E08 (mesmo código de emissão em `billing` e `fiscal`).

**Escopo:**

- `billing`: montagem dos itens da nota a partir dos lançamentos (perfil fiscal por código de lançamento), tomador pessoa física (CPF ou estrangeiro) ou empresa, separação por regime (hospedagem, A&B, eventos, outros); chamada à API pública de `fiscal`; consumo de `FiscalDocumentAuthorized`/`Rejected` para vincular a nota aos lançamentos.
- `frontdesk`: check-out oferece a emissão da NFS-e (padrão: emitir; configurável), sem bloquear o check-out se o middleware estiver fora (a nota fica na fila).
- `fiscal`: guarda de XML e PDF no armazenamento de arquivos (H5), com download por URL assinada e reimpressão.
- Café incluso na NFS-e da hospedagem (H6).

**Fora de escopo:** NFC-e (E08); contas a receber e faturas (M10).

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

**Revisão humana:** não

**Notas para o plano:**

- H5 e H6; leia RF-FIS-01, RF-FIS-04, RF-FIS-05 e RF-FIS-07 (SPEC §5.12) e os handoffs do M3 (lançamentos, roteamento, transferências e taxa de serviço), do M5 (check-out) e do M1 (`files`, URL assinada).
- Tomador pessoa física: o número do documento do hóspede é cifrado no `guests`, e a `GuestsApi` só decifra os dados da FNRH (`fnrhData`, exclusiva do `fnrh`, RN-19); obter o CPF do tomador exige uma leitura nova e auditada na `GuestsApi`, decisão de privacidade a registrar (até o M7, a Recepcionista revela o CPF à mão, M3).
- Armadilhas do `billing`: um lançamento pode ter sido roteado, transferido (pares `TRANSFER_OUT`/`TRANSFER_IN`), dividido, ajustado ou estornado; a taxa de serviço é um lançamento separado do grupo `SERVICE_CHARGE`, repasse aos empregados e não receita do hotel (`RevenueGroup.passThrough`) — decidir como ela entra (ou não) na nota.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Regra fiscal da hotelaria ainda em transição (LC 214/2025): perfis configuráveis e revisão com o contador em E11.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E08 — NFC-e de A&B, relatórios e exportação de XMLs — #pendente

**Objetivo:** disponibilizar a emissão de NFC-e (modelo 65) para A&B — consumidor final sem identificação para o PDV (M8) e NFC-e agrupada no check-out para o consumo lançado no quarto (H6) — e entregar os relatórios fiscais e a exportação de XMLs para a contabilidade (RF-FIS-09).

**Por que agora:** o PDV de M8 depende deste serviço; os relatórios fecham o ciclo fiscal do marco.

**Dependências:** E07.

**Escopo:**

- `fiscal`: API pública de NFC-e a partir de itens com perfil fiscal de produto (NCM, CFOP), consumidor identificado ou não; reutiliza fila, porta e eventos de E06.
- `billing`: no check-out, NFC-e agrupada para lançamentos de A&B lançados no quarto (H6), configurável para emitir no consumo.
- Relatórios fiscais: notas por período, tipo, status e regime; totais de ISS e IBS/CBS; rejeitadas pendentes.
- Exportação de XMLs por período em arquivo compactado, com download por URL assinada e auditoria.

**Fora de escopo:** comandas e chamada de NFC-e no fechamento de comanda (M8); exportação contábil completa (M10).

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

**Revisão humana:** não

**Notas para o plano:**

- H6 e Q-04 (a configuração "emitir no consumo" cobre a alternativa); o núcleo está nos handoffs de E06 e E07.
- Relatórios fiscais seguem a convenção das views de relatório (`fiscal.report_*`, só `SELECT` para o papel da aplicação, sem dado pessoal de hóspede nem texto livre; nota `ZenyHotelManager - Schemas, migrações e views de relatório`).
- A exportação compactada de XMLs vai ao `files`: o tamanho máximo de uma finalidade não passa de `zeny.files.max-upload-size` (20 MB) e os limites do multipart acompanham; um período longo pode passar disso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Q-04 decidida diferente do padrão: a configuração "emitir no consumo" já cobre a alternativa.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E09 — Adaptador real do PSP Pix — #bloqueada

**Objetivo:** implementar e homologar o adaptador do PSP Pix escolhido atrás da porta `PaymentProvider`, com cobrança, webhook, consulta e devolução reais em ambiente de homologação.

**Por que agora:** é a primeira integração real de pagamento; sem ela a definição de pronto do marco não fecha.

**Dependências:** E02. Independente de E10 e E11 (podem rodar em branches separadas); conflito de edição na configuração de integrações.

**Escopo:** adaptador do PSP (a criar em `payments/infrastructure/`), autenticação (OAuth2/mTLS, conforme o PSP), mapeamento de erros, testes de contrato com WireMock gravados a partir da documentação, teste manual em homologação, configuração por variáveis de ambiente, atualização do mapeamento de CSV da conciliação (E05).

**Fora de escopo:** troca de PSP no futuro (a porta já permite).

**Critérios de aceite:**

- [ ] Testes de contrato com WireMock cobrem cobrança, consulta, webhook, devolução e os erros documentados do PSP.
- [ ] Roteiro de homologação executado: cobrança criada, pagamento confirmado (webhook ou polling) e refletido na conta, devolução concluída — evidências no handoff.
- [ ] Credenciais e certificados só por variável de ambiente ou secret; nada no repositório.
- [ ] Build e testes oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Roteiro de homologação → evidências registradas (IDs de transação de homologação, sem dados pessoais).

**Revisão humana:** sim

**Notas para o plano:**

- D1 registrada e credenciais de homologação fora do repositório antes de começar; os testes de contrato com WireMock partem da documentação oficial do PSP.
- O webhook real valida a origem (H3) e todo erro do PSP vira falha ou retry, nunca sucesso; se o PSP exigir mTLS, o certificado vai como Docker secret, documentado no `README.md`.

**Bloqueios:**

- **Causa:** o PSP Pix não foi escolhido (SPEC Q-05).
- **Evidência:** SPEC §10 Q-05 e RF-PAG-06 dizem que o PSP "será definido depois"; não há credenciais de homologação.
- **Impacto:** impede o adaptador real e a homologação do Pix (critérios 1 a 3) e, por consequência, o gate E12.
- **Ação de desbloqueio:** o usuário escolhe o PSP e obtém credenciais de homologação; registrar a decisão em D1 e trocar a tag desta etapa para `#pendente` (índice e título).

**Riscos e mitigação:**

- PSP exige mTLS com certificado: guardar como Docker secret e documentar no `README.md`.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E10 — Adaptador real do adquirente — #bloqueada

**Objetivo:** implementar e homologar a integração de cartão com o adquirente escolhido, no modo decidido (TEF com biblioteca no cliente ou Smart POS com API em nuvem), incluindo pré-autorização, captura, cancelamento e tokenização.

**Por que agora:** é a primeira integração real com adquirente; caução e PDV dependem dela em produção.

**Dependências:** E03. Independente de E09 e E11 (podem rodar em branches separadas); conflito de edição na configuração de integrações.

**Escopo:** Smart POS: adaptador (a criar em `payments/infrastructure/`) com testes de contrato e homologação. TEF: ajustes no contrato de reporte do cliente conforme a biblioteca escolhida (SiTef, PayGo ou outra) e documentação para o repositório do cliente; importação do extrato do adquirente para a conciliação (E05). Tokenização pelo componente do provedor. Validade real da pré-autorização para a renovação (E04).

**Fora de escopo:** implementação da biblioteca TEF no cliente (repositório do cliente).

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

**Revisão humana:** sim

**Notas para o plano:**

- D2 (adquirente e modo) registrada e credenciais de homologação disponíveis antes de começar; Q-13 afeta a biblioteca TEF dos clientes.
- A caução manual do M3 limita a validade a 90 dias (`CardPreAuthorization.MAX_VALIDITY`): o prazo real do adquirente precisa caber nela e na antecedência de renovação de E04.

**Bloqueios:**

- **Causa:** adquirente e modo de cartão não foram escolhidos (SPEC Q-05; Q-13 afeta a biblioteca TEF nos clientes).
- **Evidência:** SPEC §10 Q-05 e Q-13 e ARCHITECTURE §18 ("TEF vs. Smart POS") estão em aberto; não há credenciais de homologação.
- **Impacto:** impede o adaptador real e a homologação de cartão e caução, e por consequência o gate E12.
- **Ação de desbloqueio:** o usuário escolhe o adquirente e o modo (TEF ou Smart POS) e obtém credenciais de homologação; registrar a decisão em D2 e trocar a tag desta etapa para `#pendente` (índice e título).

**Riscos e mitigação:**

- TEF depende do cliente desktop/PDV ainda não pronto: homologar o contrato de reporte com um cliente de teste e registrar a pendência no handoff.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E11 — Adaptador real do middleware fiscal — #bloqueada

**Objetivo:** implementar e homologar o adaptador do middleware fiscal escolhido atrás da porta `FiscalProvider`, emitindo NFS-e da hospedagem e NFC-e de A&B em homologação com as regras do município e do regime tributário do hotel.

**Por que agora:** é a primeira integração fiscal real; sem ela o hotel não emite nota pelo sistema.

**Dependências:** E08. Independente de E09 e E10 (podem rodar em branches separadas); conflito de edição na configuração de integrações.

**Escopo:** adaptador (a criar em `fiscal/infrastructure/`), autenticação, envio, consulta, cancelamento, webhook; perfis fiscais iniciais do hotel conforme o regime e o município; validação da regra de arredondamento (RN-02) com o middleware; testes de contrato; roteiro de homologação de NFS-e e NFC-e (autorização, rejeição corrigida e reenviada, cancelamento).

**Fora de escopo:** certificados e cadastros na prefeitura/SEFAZ (responsabilidade do hotel).

**Critérios de aceite:**

- [ ] Testes de contrato com WireMock cobrem envio, consulta, cancelamento, webhook e erros documentados.
- [ ] Em homologação: NFS-e de hospedagem autorizada, NFC-e de A&B autorizada, uma rejeição corrigida e reenviada até autorizar e um cancelamento — evidências no handoff.
- [ ] Os totais calculados pelo servidor batem com os do middleware nas notas de homologação; a regra de arredondamento está registrada.
- [ ] Perfis fiscais iniciais do hotel configurados conforme D3.
- [ ] Nenhuma credencial ou certificado no repositório; build e testes oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Roteiro de homologação → evidências registradas (números e chaves de homologação).

**Revisão humana:** sim

**Notas para o plano:**

- D3 (middleware, regime e município) e H7; investigue se o município do hotel usa o emissor nacional de NFS-e.
- A regra única de arredondamento é `Money.round` (`Money.ROUNDING_MODE`, hoje `HALF_EVEN`): se a validação com o middleware (RN-02) exigir mudá-la, a mudança é transversal a todos os módulos e vale como decisão do usuário.

**Bloqueios:**

- **Causa:** middleware fiscal, regime tributário e regras do município não foram definidos (SPEC Q-05, Q-02, Q-03).
- **Evidência:** SPEC §10 Q-02, Q-03 e Q-05 estão em aberto; não há credenciais de homologação.
- **Impacto:** impede a emissão real de NFS-e e NFC-e e a validação do arredondamento (RN-02), e por consequência o gate E12.
- **Ação de desbloqueio:** o usuário escolhe o middleware, confirma regime e município com o contador e obtém credenciais de homologação; registrar a decisão em D3 e trocar a tag desta etapa para `#pendente` (índice e título).

**Riscos e mitigação:**

- Regras de IBS/CBS mudando em 2027 para o Simples (RF-FIS-04): perfis configuráveis e revisão anual com o contador.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E12 — Gate do marco M7 — #pendente

**Objetivo:** confirmar, a partir de uma cópia limpa do repositório, que toda a definição global de pronto do M7 é atendida, incluindo os roteiros de homologação com os fornecedores reais.

**Por que agora:** é o critério de conclusão do marco; o gate final de M8 (inauguração) exige este gate concluído.

**Dependências:** E04, E05, E09, E10, E11 (que cobrem transitivamente todas as demais).

**Escopo:**

- Rodar build, testes, verificação de módulos e do contrato numa cópia limpa.
- Cenário ponta a ponta em homologação: reserva com sinal por Pix, check-in com caução por cartão, consumo lançado no quarto, check-out com captura parcial, NFS-e e NFC-e autorizadas, conciliação sem divergências.
- Conferir cada item da definição global de pronto com evidência.
- Atualizar `docs/roadmaps/README.md` registrando a conclusão do M7 no histórico de revisões.

**Fora de escopo:** funcionalidade nova. Problemas voltam para a etapa de origem (reabrir com `#em-andamento` e registrar no handoff dela), salvo ajustes triviais de documentação.

**Critérios de aceite:**

- [ ] Todos os itens da "Definição global de pronto" estão marcados, cada um com evidência numa cópia limpa ou em homologação.
- [ ] O cenário ponta a ponta foi executado com os adaptadores reais, sem divergências na conciliação.
- [ ] Logs da homologação não contêm dado de cartão nem dados pessoais em claro.
- [ ] `docs/roadmaps/README.md` registra a conclusão do M7 no histórico de revisões.

**Validação obrigatória:**

- Comandos oficiais de build, teste e verificação do contrato numa cópia limpa → sucesso.
- Cenário ponta a ponta em homologação → evidências registradas.

**Revisão humana:** não

**Notas para o plano:**

- Os gates do M3 e do M4 conferiram tudo numa cópia limpa (`git archive`, Gradle baixado do zero, `--no-daemon --no-build-cache`), com um roteiro `curl` num `docker compose` isolado e o cenário do marco também como teste de integração na raiz do pacote de testes (`MilestoneM3EndToEndTest`, `MilestoneM4EndToEndTest`); siga o mesmo padrão, além do cenário real em homologação.
- A captura de atualização do brain do gate registra o M7 concluído em `ZenyHotelManager - Estado por marco`.

**Bloqueios:** Nenhum bloqueio conhecido. Esta etapa só fica disponível depois que E09, E10 e E11 forem desbloqueadas e concluídas.

**Riscos e mitigação:**

- Indisponibilidade dos ambientes de homologação dos fornecedores: repetir o cenário em outro horário e registrar as tentativas.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## Histórico de revisões

- 2026-10-13 — roadmap criado.
- 2026-10-13 — aplicadas as decisões da entrevista SPEC × ARCHITECTURE (regra por camadas na ARCHITECTURE §3.1): H5 (`fiscal` → `files`) passa a fato; alertas de rejeição fiscal e falha de integração (RF-NOT-01) saem do módulo que detecta o problema via `notifications`; E06, E07 e E04 ajustados (escopo, prompt e critérios).
- 2026-10-22 — migrado para o formato da skill development-roadmap com superpowers (branch `roadmap/m7-pagamentos-fiscal`, protocolo e prompt de execução únicos, etapas sem prompt e estratégia) e para o Second Brain (notas de tema no lugar das seções do CLAUDE.md, captura de atualização por etapa); decisão do usuário.
- 2026-10-27 — protocolo de execução migrado para as souls do Second Brain: a sessão roda como `orquestrador-etapa` (`claude --agent orquestrador-etapa`), e as tarefas passam por `desenvolvedor` → `testador` → verificação mecânica dos testes → `revisor-seguranca`; a captura do brain é publicada pelo `brain-escriba`. Sem fallback para os prompts padrão do superpowers nem para `executing-plans`. Nenhuma etapa, tag, evidência ou handoff mudou.
- 2026-10-29 — protocolo de execução alinhado ao template atual da skill `development-roadmap`: o orquestrador passou de `orquestrador-etapa` para `orquestrador` (`claude --agent orquestrador`), entrou o papel "Revisor de plano" (`revisor-arquitetura`, pré-condição das souls e passo 1 do prompt) e o passo "Plano" diz que o plano passa pela revisão de arquitetura do orquestrador antes de executar. Só "Como usar", "Protocolo de execução" e "Prompt de execução" mudaram; etapas, handoffs e entradas anteriores do histórico seguem como estavam.
