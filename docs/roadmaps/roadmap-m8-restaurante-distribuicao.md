# Roadmap — M8 Restaurante, distribuição e inauguração

> Arquivo: `docs/roadmaps/roadmap-m8-restaurante-distribuicao.md` · branch de execução: `roadmap/m8-restaurante-distribuicao` · criado em 2026-10-13 · última revisão 2026-10-22
>
> Marco 8 de 11 · mapa dos marcos em [`docs/roadmaps/README.md`](README.md) · anterior: [M7 — Pagamentos integrados e fiscal](roadmap-m7-pagamentos-fiscal.md) · próximo: [M9 — Suprimentos](roadmap-m9-suprimentos.md)
>
> **Pré-requisito:** o gate do marco M7 (E12 de `docs/roadmaps/roadmap-m7-pagamentos-fiscal.md`) está `#concluida`. Exceção controlada: se o gate de M7 ainda estiver pendente **apenas** porque os adaptadores reais de fornecedores (M7 E09, E10, E11) esperam a SPEC Q-05, as etapas E01–E08, E10 e E12 deste marco podem começar com M7 E01–E08 `#concluida`, usando os adaptadores falsos. O gate E13 deste marco exige o gate de M7 concluído.

## Como usar

Inicie uma sessão limpa do Claude Code na raiz do repositório com `claude --agent orquestrador-etapa` e cole o "Prompt de execução". Ele executa até 3 etapas em sequência na branch `roadmap/m8-restaurante-distribuicao`, cada uma planejada pelo plugin superpowers e executada pelas souls (subagentes do Second Brain), e para para revisão. No fim do lote você decide como integrar a branch (merge, PR, manter ou descartar). Para não parar em cada pedido de permissão, libere antes os comandos de build/teste do projeto nas permissões do Claude Code (os comandos oficiais estão na tabela do `CLAUDE.md`; o Docker é necessário para os testes de integração).

**Este roadmap foi escrito antes do código existir.** Antes da primeira etapa, sincronize-o com o código real usando a skill `development-roadmap` (modo atualização): confira caminhos, nomes de módulos, comandos e convenções registrados nos handoffs de M0–M7 e nas notas de tema do brain e ajuste as etapas pendentes.

Este marco tem três trilhas independentes: restaurante (E01–E06), distribuição (E07–E09) e implantação (E10–E11). Num lote, as etapas rodam em sequência na mesma branch; a independência entre trilhas é só informativa.

## Objetivo

Deixar o hotel **pronto para inaugurar** (fim da Fase 2 da SPEC §9): restaurante e bar abertos ao público operando no PDV (cardápio, comandas, produção, fechamento com pagamento integrado e NFC-e, lançamento no quarto, café da manhã, taxa de serviço), distribuição pelo channel manager (ARI incremental e recebimento de reservas) e o ambiente de produção com backup testado, TLS e desempenho medido. O marco fecha com um gate ponta a ponta das Fases 0, 1 e 2.

## Contexto confirmado

- Requisitos do PDV: SPEC §5.19 (RF-PDV-01 a 06, 08 a 11; RF-PDV-07, ficha técnica, é do M9), RF-CTA-09 e RF-CTA-14 (§5.10, caixa e taxa de serviço), RN-10 (taxa de serviço), RN-12 (ações sensíveis).
- Requisitos de distribuição: SPEC §5.21 (RF-DIS-01 a 06).
- Requisitos não funcionais: RNF-01, RNF-02, RNF-04 (≤ 2 s em tempo real), RNF-06 (p95 < 300 ms com ~30 usuários), RNF-07 (backup, RPO ≤ 1h, RTO ≤ 4h, restauração testada), RNF-08 (TLS) — SPEC §7.
- Modelo de domínio: SPEC §8.2.15 (`Outlet`, `ProductionStation`, `MenuCategory`, `MenuItem`, `Modifier`, `DiningTable`, `Tab`, `TabItem`, `ProductionTicket`, `BreakfastAttendance`) e §8.2.17 (`ChannelConnection`, `Channel`, `RoomTypeMapping`, `RatePlanMapping`, `AriUpdate`, `InboundBookingMessage`, `ChannelManagerProvider`). Eventos `TabClosed`, `AvailabilityChanged`, `RatesChanged`, `RestrictionsChanged`: SPEC §8.3.
- Arquitetura: `pos` depende de `shared`, `hotel`, `staff`, `billing`, `payments`, `fiscal`, `inventory`, `reservations`, `printing`; `distribution` depende de `shared`, `rooms`, `rates`, `reservations` (ARCHITECTURE §3.1). Tempo real com tópicos `tabs` e `production` (§10). Integrações e modo pull (§12). Jobs (§13). Impressão (§14). Implantação, nuvem × local e backup (§15.1–15.3). Observabilidade (§16). Riscos Q-05/Q-06 (§18). Regra por camadas da §3.1: qualquer módulo pode chamar os serviços de plataforma (`files`, `notifications`, `printing`, `hotel`) e depender de camadas inferiores sem aprovação (ex.: `pos` → `rates`), desde que o grafo siga acíclico e a aresta nova seja acrescentada à tabela §3.1 na mesma mudança.
- Convenções herdadas (ler nos handoffs e nas notas de tema do brain): M0 (API, erros, `Money`, idempotência, outbox, ShedLock, Docker/compose com o serviço `backup` reservado, CI), M1 (autorização, limites, step-up, auditoria, `hotel`, `staff`), M2 (SSE, notificações), M3 (`billing`: contas, lançamentos, turnos de caixa, pontos de caixa), M4 (`rates`, `reservations`, disponibilidade), M5 (`printing`, padrão de integração da FNRH), M6 (relatórios operacionais), M7 (`payments` Pix e cartão, API de NFC-e em `fiscal`).
- Caminhos previstos (a criar, sob o pacote base `com.zeny.hotel`, hipótese herdada de M0): `pos/` e `distribution/` com `domain/`, `application/`, `infrastructure/`, `web/`; migrações nas pastas `pos` e `distribution` de `src/main/resources/db/migration/`; `docker/compose.yaml` e `docker/` para backup e proxy.
- Comandos de build, teste e lint: os oficiais da tabela do `CLAUDE.md` (definidos em M0 E01).
- Conhecimento do projeto no Second Brain: hub `ZenyHotelManager` e as notas de tema `ZenyHotelManager - Arquitetura e estrutura de módulos` (estrutura de módulo, `ModularityTest`, regra por camadas), `ZenyHotelManager - Schemas, migrações e views de relatório` (migrações, privilégios, *append-only*, views `report_*`), `ZenyHotelManager - Tipos do kernel` (`Money`, `Percent`, ids, cifragem), `ZenyHotelManager - Convenções de API` (controllers, erros, cursor, `ETag`, `Idempotency-Key`, contrato), `ZenyHotelManager - Segurança, autenticação e autorização` (permissões, `OWN`, limites, step-up, endpoints públicos), `ZenyHotelManager - Auditoria`, `ZenyHotelManager - Tempo real (SSE)` (tópicos `tabs` e `production`), `ZenyHotelManager - Sincronização offline`, `ZenyHotelManager - Eventos e jobs` (outbox, listeners, ShedLock), `ZenyHotelManager - Testes de integração`, `ZenyHotelManager - Docker, observabilidade e CI` (compose, proxy, métricas e alertas), `ZenyHotelManager - Ambiente, banco, chaves e build` (papéis do banco, chaves e rotação, segredos), `ZenyHotelManager - Módulos hotel e staff`, `ZenyHotelManager - Módulo notifications`, `ZenyHotelManager - Módulo guests`, `ZenyHotelManager - Módulo billing (contas, roteamento e taxa de serviço)`, `ZenyHotelManager - Módulo billing (caixa, pagamentos, caução e encerramento)` (inclui o `payments`), `ZenyHotelManager - Módulo rates`, `ZenyHotelManager - Módulo reservations (inventário, disponibilidade e reservas)`, `ZenyHotelManager - Módulo reservations (ciclo de vida, alocação, grupos e busca)`, `ZenyHotelManager - Hospedagem e recepção` e `ZenyHotelManager - Estado por marco`. Os módulos dos marcos M5 a M7 que ainda não têm nota de tema (`printing`, `fnrh`, `housekeeping`, `reporting`, `fiscal`, o `payments` integrado) são lidos nos handoffs desses marcos e nas capturas pendentes do projeto.

## Escopo

**Incluído:** pontos de venda, mesas, setores de produção e cardápio; comandas; produção com impressão e tela via SSE; fechamento com pagamento, divisão, taxa de serviço, desconto e NFC-e; consumo interno e cortesia; lançamento no quarto; café da manhã; relatórios do PDV e da taxa de serviço; distribuição com porta neutra, ARI incremental, ressincronização, recebimento de reservas e painel de saúde; adaptador real do channel manager (bloqueado até Q-05); backup e restauração; configuração de implantação nuvem ou local (bloqueada até Q-06); teste de desempenho; gate de inauguração.

**Fora de escopo:**

- Ficha técnica, baixa automática de estoque e CMV (RF-PDV-07) e baixa de estoque do consumo interno: M9. Os pontos de venda guardam a referência ao local de estoque só quando `inventory` existir.
- Eventos e locação de espaços: M10.
- Motor de reservas próprio e interface do hóspede: fora da v1 (SPEC §2.2).
- Publicação da imagem em registry público e orquestração além de `docker compose`.

## Restrições e invariantes

- **Grafo de módulos (ARCHITECTURE §3.1):** `pos` não é chamado por `billing`, `payments` nem `fiscal`; quem reage à comanda escuta `TabClosed`. A NFC-e é solicitada por um listener do próprio `pos` sobre `TabClosed`, chamando a API pública de `fiscal`; em M9 o mesmo listener chamará `inventory` para a baixa por ficha técnica. Essa decisão está registrada na ARCHITECTURE §3.1 e na SPEC §8.3 (`fiscal` e `inventory` não escutam `TabClosed`). `distribution` cria e altera reservas pela API pública de `reservations`; `reservations` não conhece `distribution`. Alertas de falha (RF-NOT-01) saem do módulo que detecta o problema (ex.: `distribution`) via `notifications`, diretamente ou pelo mecanismo genérico de falha de M5; `notifications` nunca escuta eventos de negócio, e a aresta nova entra na tabela §3.1.
- **Idempotência:** fechamento de comanda, lançamento no quarto e pagamento exigem `Idempotency-Key`; mensagens do channel manager são idempotentes pelo ID externo (RF-DIS-04).
- **Overbooking proibido (Q-09):** reservas vindas do channel manager passam pelos mesmos contadores com `SELECT … FOR UPDATE` de M4 (ARCHITECTURE §11).
- **Ações sensíveis (RF-ACS-08):** cancelar item enviado à produção e desconto acima do limite exigem permissão ou step-up.
- **Taxa de serviço (RN-10):** opcional, removível com registro, separada na conta, não é receita do hotel.
- **Segredos:** credenciais do channel manager, do backup e certificados ficam fora do repositório (variáveis de ambiente ou Docker secrets).
- **Minimização (RN-13):** tela de produção e impressões de produção não mostram dados pessoais do hóspede além do número da UH.
- **Documentação:** a referência técnica nunca volta ao `CLAUDE.md`. Cada etapa que muda algo descrito no brain fecha com a captura `ZenyHotelManager - atualização m8-restaurante-distribuicao Eyy` (em `00 Inbox/` do vault), com o texto novo ou alterado das notas de tema afetadas; os módulos novos propõem as notas `ZenyHotelManager - Módulo pos` e `ZenyHotelManager - Módulo distribution`, e a implantação (E10, E11) altera `ZenyHotelManager - Docker, observabilidade e CI` e `ZenyHotelManager - Ambiente, banco, chaves e build`. O `README.md` continua sendo atualizado no repositório.

## Hipóteses e decisões em aberto

| # | Hipótese ou decisão | Impacto se errada / sem decisão | Etapa afetada |
|---|---|---|---|
| H1 | M7 E08 registrou no handoff a API de NFC-e e M7 E02/E03 a de cobrança Pix e cartão. | Se divergir, E04 adapta a chamada e registra no handoff. | E04 |
| H2 | M2 entregou o broadcaster SSE com tópicos registráveis por módulo e filtro por permissão; `tabs` e `production` ainda não existem. | E03 cria os tópicos no padrão de M2. | E03 |
| H3 | M5 entregou `printing` com impressoras, fila de jobs e templates ESC/POS reutilizáveis por outros módulos. | Se o template for específico da recepção, E03 generaliza dentro de `printing`. | E03 |
| H4 | `reservations` (M4) guarda canal de origem, comissão e quem cobra na reserva, ou aceita esses campos pela API pública. | Se não aceitar, E08 acrescenta os campos em `reservations` (dependência permitida). | E08 |
| H5 | `reservations`, dona da estadia, expõe por data se ela inclui café da manhã (pelo plano ou pacote). `pos` → `rates` é permitido pela regra por camadas, mas não é necessário se `reservations` já responder. | Se não expuser, E05 acrescenta a consulta em `reservations`; se só faltar o dado do plano, `pos` pode consultar `rates` e acrescentar a aresta à tabela §3.1. | E05 |
| H6 | Q-08 segue o padrão da SPEC §10: taxa de serviço só em A&B, retenção para encargos configurável. | Mudar depois é configuração, sem código. | E04, E06 |
| H7 | Backup com pgBackRest ou WAL-G e cópia externa em storage compatível com S3; em desenvolvimento, um segundo MinIO simula o destino externo. | A ferramenta é trocável; o destino real depende de credenciais do usuário. | E10 |
| H8 | Teste de desempenho com k6 ou Gatling, rodando contra o compose local com dados sintéticos. | Ferramenta trocável; números de produção são confirmados no ambiente final (E11). | E12 |
| D1 | Q-05: qual channel manager (HotelRunner, Omnibees ou outro) e se ele oferece pull. | Sem o fornecedor não há credenciais nem documentação para o adaptador real. | E09 (bloqueada) |
| D2 | Q-06: servidor na nuvem (VPS) ou no hotel. | Define TLS (Let's Encrypt × LAN com túnel), modo das integrações (webhook × polling), destino do backup e procedimento de implantação. | E11 (bloqueada) |

**Notas herdadas do M2** (sincronização do roadmap do M2 com o código do M1, 2026-10-15; conferir de novo na sincronização deste marco):

- **Implantação com TLS:** o SSE (`GET /api/v1/stream`, M2 E04) passa pelo proxy público: conferir `flush_interval` sem buffer e o tempo limite de conexões longas no Caddy com TLS (e em qualquer balanceador da nuvem), com o heartbeat do M2.
- **Log de acesso sem query string** (herdado do M3 E01, decisão do usuário em 2026-10-16): a busca de hóspedes leva nome e e-mail na query string (`GET /api/v1/guests?name=…&email=…`) e o cursor leva o nome normalizado; ao configurar o Caddy com TLS e a observabilidade de produção (E11), não ligar log de acesso com a query string, nem no proxy nem na aplicação (o número de documento já vai no corpo, `POST /api/v1/guests/search-by-document`).
- **Re-cifragem na rotação de chaves** (herdado do M3 E01): o `GuestDocumentRekeyJob` re-cifra e reindexa os documentos de hóspede; os outros valores cifrados (`guests.guest.sensitive_data`, `files.stored_file.original_name`, `notifications.push_device.token` e os anexos sensíveis no bucket) ainda não têm job, então uma chave só sai do anel depois de re-cifrá-los (procedimento em "Chaves de cifragem" na nota de tema `ZenyHotelManager - Ambiente, banco, chaves e build`).
- **Taxa de serviço padrão do hotel** (herdado do M3 E11, decisão do usuário em 2026-10-20): o hotel nasce com a taxa de serviço padrão em 0% (`HotelApi.taxSettings().serviceChargeRate`), e o código `AB` não tem percentual próprio, então sem configurar nada nenhum consumo de A&B leva a taxa. Na implantação, o administrador define o percentual em `PUT /api/v1/hotel/tax-settings` (`serviceChargeRate`, com `If-Match`; `hotel.settings.manage`), e o `pos` (E04) informa o do ponto de venda quando diferente; o passo entra no checklist de inauguração da E13.
- **Segredos de implantação:** as `ZENY_FCM_*` (credencial da conta de serviço do FCM, M2 E09) entram com as demais credenciais da implantação, fora do repositório; vazias, o push fica desligado.

## Definição global de pronto

- [ ] Restaurante, bar, room service e café da manhã operam no PDV: comanda aberta, itens enviados à produção (impressora e tela), fechada com Pix, cartão ou dinheiro, com NFC-e autorizada e taxa de serviço tratada conforme RN-10.
- [ ] Consumo lançado no quarto aparece detalhado na conta do hóspede hospedado.
- [ ] ARI chega ao channel manager real a cada alteração; reservas novas, alteradas e canceladas entram sem duplicidade nem overbooking.
- [ ] Backup contínuo com cópia externa e restauração testada dentro do RTO de 4h.
- [ ] Ambiente de produção no modelo decidido (Q-06), com TLS, e desempenho dentro de RNF-04 e RNF-06.
- [ ] Gate de M7 concluído e cenário ponta a ponta das Fases 0–2 executado com sucesso.
- [ ] Build, testes, verificação de módulos e do contrato passam; `api/openapi.yaml` sem *breaking change*.

## Protocolo de execução

Vale para toda execução deste roadmap. O planejamento e a execução de cada etapa são feitos pelo plugin superpowers; este protocolo diz como encadear as etapas e manter o roadmap em dia.

**Papéis**

| Papel | Soul | Usa |
|---|---|---|
| Orquestrador | `orquestrador-etapa` (a sessão principal, iniciada com `claude --agent orquestrador-etapa`) | este protocolo; atualiza tags e handoffs; roda a verificação mecânica dos testes |
| Planejador | `orquestrador-etapa` | `superpowers:writing-plans` |
| Implementador | `desenvolvedor`, subagente por tarefa | `superpowers:subagent-driven-development`, prompt implementer |
| Testador | `testador`, subagente por tarefa, depois do implementador | testes da tarefa, sem tocar em código de produção |
| Revisor de tarefa | `revisor-seguranca`, subagente novo por tarefa | revisão da `subagent-driven-development` |
| Revisor final | `revisor-seguranca`, subagente novo, modelo mais capaz | revisão final da `subagent-driven-development` |
| Captura no brain | `brain-escriba` | publica a captura de atualização em `00 Inbox/` |

Souls: este protocolo exige `orquestrador-etapa`, `desenvolvedor`, `testador` e `revisor-seguranca`. Não há fallback: se a sessão não roda como `orquestrador-etapa` ou alguma dessas souls não está instalada, pare e diga ao usuário para configurar a máquina (skill `configurar-maquina`) ou iniciar com `claude --agent orquestrador-etapa`. O caminho inline (`superpowers:executing-plans`) não vale aqui.

**Branch**

- Toda execução acontece no worktree/branch `roadmap/m8-restaurante-distribuicao`, criado ou retomado com `superpowers:using-git-worktrees`. Nunca execute na branch principal (`main`).
- Se a branch já existir com trabalho não integrado, continue nela. Se o lote anterior já foi integrado, crie a branch de novo a partir da `main` atualizada.
- Este roadmap é atualizado dentro da branch: tags e handoffs viajam com o código.

**Por etapa**

1. **Escolha:** a primeira etapa, na ordem do documento, marcada `#pendente` e com todas as dependências `#concluida`. Etapas `#bloqueada` e as que dependem delas ficam para trás.
2. **Conferência:** leia a seção da etapa, os handoffs das dependências e o código que eles citam. Se o código divergiu do que a etapa supõe, pare e relate.
3. **Início:** troque a tag para `#em-andamento` (índice e título).
4. **Plano:** use `superpowers:writing-plans`. A spec é a seção desta etapa mais os documentos citados em "Contexto confirmado"; as "Restrições e invariantes" entram como Global Constraints. Salve em `docs/superpowers/plans/AAAA-MM-DD-m8-restaurante-distribuicao-eNN.md` (AAAA-MM-DD = a data do dia). A execução já está definida (abaixo): não pergunte ao usuário o método, apenas siga.
5. **Execução:** use `superpowers:subagent-driven-development` com esse plano, executado pelas souls conforme o ciclo por tarefa, a verificação mecânica dos testes e a revisão final da etapa definidos no `orquestrador-etapa` (as regras de encaixe vivem só nele). Não invoque `superpowers:finishing-a-development-branch` ao fim da etapa: ele roda uma vez, no fim do lote.
6. **Fechamento:** publique a captura de atualização do brain (bloco "Second Brain"). Marque `#concluida` (índice e título) só com a revisão final limpa e cada critério de aceite verificado com evidência. Diante de impedimento real, marque `#bloqueada` e preencha "Bloqueios" com causa, evidência, impacto e ação de desbloqueio. Preencha "Evidências e handoff". Rulings do ledger que mudam contrato, escopo, segurança, dados ou algo caro de desfazer vão para "Decisões para o usuário".

**Second Brain**

Vale se o `CLAUDE.md` tiver a seção "Conhecimento do projeto no Second Brain"; senão, ignore este bloco.

- O contexto da sessão precisa trazer a linha `BRAIN: carregado (ZenyHotelManager)`, injetada pelo hook. Sem ela, pare e relate.
- Na conferência de cada etapa, leia as notas de tema do brain ligadas a ela (pelo hub `ZenyHotelManager`) e as capturas pendentes do projeto que tratem do assunto: são a referência técnica do projeto. Num handoff antigo, "seção X do `CLAUDE.md`" é a nota de tema correspondente.
- No fechamento de cada etapa que mudou algo descrito no brain (inclusive o estado do roadmap), o orquestrador manda o `brain-escriba` publicar em `00 Inbox/` do vault a captura `ZenyHotelManager - atualização m8-restaurante-distribuicao Eyy`, com o texto novo ou alterado de cada nota afetada e qual nota ele substitui ou complementa; o contrato de agente externo do vault fica com o `brain-escriba`. Nunca edite notas do brain nem registre referência técnica no `CLAUDE.md`.

**Paradas**

O lote para ao concluir uma etapa com **Revisão humana: sim**, diante de etapa `#bloqueada` ou divergência do plano, nas paradas do próprio superpowers (operação destrutiva, ação sensível de segurança, efeito fora da branch, plano sem caminho viável) e ao atingir o limite de etapas do prompt.

**Fim do lote**

1. Use `superpowers:finishing-a-development-branch`: o usuário decide merge, PR, manter ou descartar a branch. Nunca faça push, merge ou deploy sem essa decisão.
2. Apresente as "Decisões para o usuário" acumuladas como entrevista: perguntas de múltipla escolha, cada uma com uma opção recomendada e justificada (no Claude Code, `AskUserQuestion` com a recomendada em primeiro lugar e o rótulo "(Recomendado)"). Registre as respostas no handoff das etapas e em "Hipóteses e decisões em aberto".

## Prompt de execução

```text
Execute o roadmap docs/roadmaps/roadmap-m8-restaurante-distribuicao.md deste repositório seguindo a seção "Protocolo de execução" dele, com o plugin superpowers.

Limite desta execução: no máximo 3 etapas concluídas.

1. Confira a pré-condição do protocolo: esta sessão roda como orquestrador-etapa e as souls desenvolvedor, testador e revisor-seguranca estão disponíveis; se não, pare e diga como resolver. Leia CLAUDE.md (e AGENTS.md, se existir) e, no roadmap, o cabeçalho inteiro, o protocolo e o índice. Se o CLAUDE.md exigir o Second Brain, confira a linha BRAIN: carregado no contexto e leia o hub. Rode git status e git branch -a.
2. Se alguma etapa estiver #em-andamento, retome-a a partir do ledger do superpowers e do handoff; não recomece tarefas já commitadas.
3. Crie ou retome o worktree/branch roadmap/m8-restaurante-distribuicao com superpowers:using-git-worktrees. Não trabalhe na branch principal.
4. Repita, uma etapa por vez: escolha a próxima etapa elegível, marque #em-andamento, planeje com superpowers:writing-plans (spec = seção da etapa + documentos citados), execute com superpowers:subagent-driven-development, feche a etapa (com a captura de atualização do brain) e preencha o handoff conforme o protocolo.
5. Pare nas paradas do protocolo ou ao atingir o limite.
6. No fim, rode superpowers:finishing-a-development-branch e deixe a decisão de integração comigo. Depois, apresente as decisões para o usuário como entrevista com opção recomendada.

Nunca faça push, merge ou deploy sem minha decisão. Não altere tags de etapas que não executou.

Ao parar, responda com: etapas concluídas e o resultado da revisão final de cada uma, a etapa atual e seu estado, o motivo da parada, as etapas #bloqueada com a ação de desbloqueio, a branch e os commits do lote, as capturas do brain publicadas e o próximo passo sugerido.
```

## Índice de etapas

**Restaurante e bar**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E01 | Pontos de venda e cardápio | #pendente | — | Módulo `pos` com pontos de venda, mesas, setores de produção, categorias, produtos e modificadores |
| E02 | Comandas | #pendente | E01 | Abrir, lançar, transferir, juntar, dividir e cancelar itens, com step-up após o envio |
| E03 | Produção: impressão e tela em tempo real | #pendente | E02 | Envio à produção, ticket impresso no setor, tela via SSE e estados do item |
| E04 | Fechamento de comanda, taxa de serviço e NFC-e | #pendente | E03 | Pagamento integrado ou manual, divisão, desconto, taxa de serviço, NFC-e, consumo interno |
| E05 | Lançamento no quarto e café da manhã | #pendente | E04 | Consumo detalhado na conta do hóspede, lista e presença do café, cobrança de quem não tem incluso |
| E06 | Relatórios do PDV e da taxa de serviço | #pendente | E05 | Vendas por produto, categoria, garçom e período; ticket médio; arrecadação e rateio da taxa |

**Distribuição**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E07 | Porta do channel manager e envio de ARI | #pendente | — | `ChannelManagerProvider`, mapeamentos, ARI incremental com fila e ressincronização, provedor falso |
| E08 | Recebimento de reservas e painel de saúde | #pendente | E07 | Pull e webhook idempotentes, reservas criadas/alteradas/canceladas, canal e comissão, painel de saúde |
| E09 | Adaptador real do channel manager | #bloqueada | E08 | Adaptador do fornecedor escolhido homologado |

**Implantação e inauguração**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E10 | Backup contínuo e restauração testada | #pendente | — | Serviço `backup` no compose, WAL contínuo com cópia externa, espelhamento do bucket, restauração documentada |
| E11 | Ambiente de produção: nuvem ou hotel | #bloqueada | E10 | Configuração e procedimento de implantação no modelo decidido, com TLS e modo das integrações |
| E12 | Teste de desempenho e tempo real | #pendente | E06, E08 | Medição de p95 < 300 ms com ~30 usuários e eventos em até 2 s |
| E13 | Gate de inauguração (Fases 0–2) | #pendente | E09, E11, E12 | Definição global de pronto e cenário ponta a ponta das Fases 0–2 |

---

## E01 — Pontos de venda e cardápio — #pendente

**Objetivo:** criar o módulo `pos` com os cadastros de pontos de venda (RF-PDV-01), mesas, setores de produção e cardápio (RF-PDV-02): categorias, produtos com preço por ponto de venda, disponibilidade, modificadores, perfil fiscal e setor de produção.

**Por que agora:** comandas, produção e fechamento dependem desses cadastros.

**Dependências:** nenhuma dentro deste roadmap; exige o pré-requisito do cabeçalho (gate de M7 ou a exceção controlada). Independente de E07 e E10; conflito possível só em `api/openapi.yaml`.

**Escopo:**

- Módulo `pos/` (a criar), schema e migrações próprios.
- `Outlet` (tipo: restaurante, bar, room service, café da manhã; horários; ponto de caixa de `billing`; impressoras de produção de `printing`; taxa de serviço própria), `DiningTable`, `ProductionStation` (cozinha, bar, com impressora opcional).
- `MenuCategory`, `MenuItem` (preço por ponto de venda, ativo/disponível, perfil fiscal de `fiscal` por ID, setor de produção), `Modifier` com variação de preço.
- Permissões novas no catálogo (padrão de M1) e auditoria das alterações.
- Endpoints CRUD em `/api/v1` com paginação por cursor e `ETag`.

**Fora de escopo:** ficha técnica e local de estoque (M9); comandas (E02).

**Critérios de aceite:**

- [ ] Endpoints CRUD de pontos de venda, mesas, setores de produção, categorias, produtos e modificadores estão no contrato e cobertos por testes de API.
- [ ] Produto tem preço por ponto de venda em `Money`; produto indisponível não aparece na consulta de cardápio do ponto de venda.
- [ ] Referência a perfil fiscal, ponto de caixa, impressora ou setor inexistente → erro com `code` estável.
- [ ] Escrita sem a permissão do catálogo → `403`; alterações auditadas.
- [ ] `ApplicationModules.verify()` passa com `pos` usando só módulos listados na sua coluna da tabela da ARCHITECTURE §3.1 (aresta nova permitida pela regra por camadas é acrescentada à tabela).
- [ ] Build, testes e verificação do contrato oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sem diff pendente e sem *breaking change*.

**Revisão humana:** não

**Notas para o plano:**

- Confirme o pré-requisito antes de planejar: gate E12 do M7 `#concluida` ou, pela exceção do cabeçalho, M7 E01 a E08 `#concluida`; senão, pare e relate.
- Ponto de caixa (`billing`), impressoras (`printing`, M5) e perfil fiscal (`fiscal`, M7) são referenciados só por ID e conferidos pela API pública de cada módulo, nunca pelas tabelas; `printing` e `fiscal` ainda não têm nota de tema no brain, então a forma de consulta vem dos handoffs de M5 e M7 (e das capturas pendentes). O ponto de caixa e a taxa de serviço do `billing` estão nas notas `ZenyHotelManager - Módulo billing (caixa, pagamentos, caução e encerramento)` e `ZenyHotelManager - Módulo billing (contas, roteamento e taxa de serviço)`.
- Pontos de atenção da revisão: `pos` acessando tabelas de outros módulos, preço em ponto flutuante e escrita sem permissão.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Preço por ponto de venda complica o cardápio: um preço padrão no produto com sobreposição por ponto de venda.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E02 — Comandas — #pendente

**Objetivo:** implementar o ciclo de vida da comanda (RF-PDV-03): abrir por mesa, balcão ou UH, lançar itens com modificadores e observações, transferir itens entre comandas, juntar e dividir comandas e cancelar itens — livremente antes do envio à produção e como ação sensível depois.

**Por que agora:** é o agregado central do PDV; produção e fechamento operam sobre ele.

**Dependências:** E01. Mesmo agregado `Tab` de E03–E06 (não paralelizável com elas).

**Escopo:**

- `Tab` (número legível por sequência, status `OPEN`, `CLOSED`, `CHARGED_TO_ROOM`, `CANCELLED`), `TabItem` (quantidade, preço unitário congelado no lançamento, modificadores, observações, status).
- Garçom vinculado por colaborador de `staff`; escopo `OWN` para o garçom ver só as próprias comandas (padrão de M1).
- Comanda por UH valida, via `reservations`, que há hóspede hospedado na UH.
- Transferência, junção e divisão de comandas preservando o histórico.
- Cancelamento de item enviado à produção com step-up (RF-ACS-08) e motivo.
- Concorrência com `ETag`/`If-Match`; publicação de eventos de alteração para o tópico `tabs` (consumido em E03).

**Fora de escopo:** envio à produção (E03); fechamento e pagamento (E04).

**Critérios de aceite:**

- [ ] Abrir comanda por mesa, balcão e UH; lançar, transferir, juntar e dividir cobertos por testes de API.
- [ ] Comanda por UH sem hóspede hospedado → erro com `code` estável.
- [ ] Divisão e junção preservam a soma dos itens e o histórico (teste).
- [ ] Preço do item fica congelado no lançamento; alterar o cardápio depois não muda comandas abertas.
- [ ] Item ainda não enviado é cancelado sem aprovação; item enviado sem aprovação → `403 APPROVAL_REQUIRED`; com aprovação, auditado com motivo.
- [ ] Garçom com escopo `OWN` não lista nem altera comandas de outro garçom (teste).
- [ ] Alteração concorrente com `If-Match` desatualizado → `412`.
- [ ] Build, testes e verificação do contrato oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Verificação do contrato → sem diff pendente e sem *breaking change*.

**Revisão humana:** não

**Notas para o plano:**

- Escopo `OWN`, step-up (`requireOrApproval` com `X-Approval-Token`) e a repetição com outra `Idempotency-Key` seguem a nota `ZenyHotelManager - Segurança, autenticação e autorização`; o vínculo garçom ↔ usuário vem da `StaffApi.employeeOfUser` (nota `ZenyHotelManager - Módulos hotel e staff`).
- O hóspede hospedado na UH vem da `ReservationsApi` (reserva `IN_HOUSE` com a alocação ativa; notas `ZenyHotelManager - Hospedagem e recepção` e `ZenyHotelManager - Módulo reservations (ciclo de vida, alocação, grupos e busca)`), nunca das tabelas de `reservations`.
- Pontos de atenção da revisão: cancelamento após o envio sem aprovação, garçom vendo comanda alheia sem `ALL`, divisão que perde ou duplica itens, preço retroativo.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Divisão por pessoa versus por item: suportar os dois e registrar a escolha de modelo no handoff.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E03 — Produção: impressão e tela em tempo real — #pendente

**Objetivo:** enviar os itens da comanda à produção (RF-PDV-04): gerar um ticket por setor, imprimi-lo na impressora do setor pelo agente de impressão e exibi-lo na tela de produção via SSE, com os estados do item pedido → em preparo → pronto → entregue.

**Por que agora:** sem produção o restaurante não opera; depende das comandas (E02).

**Dependências:** E02. Mesmo agregado `Tab` de E04 (não paralelizável com ela).

**Escopo:**

- `ProductionTicket` por setor de produção ao enviar a comanda; itens enviados ficam marcados (afeta o cancelamento de E02).
- Impressão ESC/POS pelo `printing` de M5 (H3), com template de produção sem dados pessoais (RN-13).
- Tópicos SSE `tabs` e `production` no padrão de M2 (H2), filtrados por permissão; payload mínimo.
- Endpoints para a tela de produção listar tickets do setor e mudar o estado do item.
- Reimpressão de ticket.

**Fora de escopo:** fechamento (E04); baixa de estoque por ficha técnica (M9).

**Critérios de aceite:**

- [ ] Enviar uma comanda com itens de cozinha e bar gera dois tickets, um por setor, e um job de impressão para cada impressora configurada.
- [ ] Reenviar a mesma comanda sem itens novos não gera novo ticket nem nova impressão; reimpressão explícita gera job marcado como reimpressão.
- [ ] Evento do tópico `production` chega a usuário com permissão de produção e não chega a usuário sem ela (teste SSE).
- [ ] Ticket e evento não contêm nome, documento nem contato do hóspede, só número da UH quando houver.
- [ ] Transições de estado do item seguem pedido → em preparo → pronto → entregue; transição inválida → erro com `code` estável.
- [ ] Build, testes e verificação do contrato oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Verificação do contrato → sem diff pendente e sem *breaking change*.

**Revisão humana:** não

**Notas para o plano:**

- Hipóteses H2 e H3: o registro de tópicos (`RealtimeTopic` com a permissão de leitura), a publicação dentro da transação de escrita e o payload só com `type`, `id` e `version` seguem a nota `ZenyHotelManager - Tempo real (SSE)`; os testes SSE usam contexto `RANDOM_PORT` e o `SseTestClient` (mesma nota). O `printing` é do M5 e ainda não tem nota de tema: jobs e templates vêm do handoff do M5 e das capturas pendentes.
- Pontos de atenção da revisão: ticket impresso duas vezes num reenvio, nome do hóspede no ticket ou no evento, usuário sem permissão recebendo o tópico `production`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Impressora fora do ar: o job fica pendente no `printing` e a tela de produção continua funcionando.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E04 — Fechamento de comanda, taxa de serviço e NFC-e — #pendente

**Objetivo:** fechar comandas (RF-PDV-05): pagamento em dinheiro, Pix ou cartão (integrado ou manual) dentro de um turno de caixa do ponto de venda (RF-PDV-09), divisão entre pessoas, taxa de serviço removível com registro (RN-10), desconto dentro do limite ou com aprovação, emissão de NFC-e e consumo interno ou cortesia com motivo (RF-PDV-10).

**Por que agora:** é a etapa que movimenta dinheiro e emite nota no restaurante; depende de comandas e produção.

**Dependências:** E03. Mesmo agregado `Tab` de E05 (não paralelizável com ela).

**Escopo:**

- Fechamento com um ou mais pagamentos via `payments` (Pix e cartão de M7; registro manual de M3), `Idempotency-Key`, troco em dinheiro; comanda só fecha com saldo zero.
- Turno de caixa de `billing` aberto para o usuário e o ponto de caixa do ponto de venda (RF-CTA-10).
- Divisão do pagamento entre pessoas por valor ou por itens.
- Taxa de serviço conforme a configuração do ponto de venda e de `hotel` (H6), separada do total, removível a pedido com registro de quem removeu.
- Desconto por item ou total, validado contra o limite do usuário; acima do limite → step-up.
- Evento `TabClosed` publicado no fechamento; listener do próprio `pos` solicita a NFC-e pela API pública de `fiscal` (H1) e guarda a referência do documento (decisão da ARCHITECTURE §3.1 e SPEC §8.3).
- Consumo interno e cortesia: fecham sem pagamento e sem faturar, com motivo e permissão; a baixa de estoque fica para M9.

**Fora de escopo:** lançamento no quarto (E05); baixa de estoque (M9).

**Critérios de aceite:**

- [ ] Fechamento com dinheiro (com troco) e cartão na mesma comanda registra dois pagamentos e status `CLOSED` (teste de módulo).
- [ ] Fechamento sem turno de caixa aberto do usuário no ponto de caixa do ponto de venda → erro com `code` estável.
- [ ] Comanda com saldo diferente de zero não fecha → erro com `code` estável.
- [ ] Divisão entre pessoas por valor e por itens soma exatamente o total.
- [ ] Taxa de serviço aparece separada, não entra como receita do hotel e, ao ser removida, registra quem removeu e quando.
- [ ] Desconto acima do limite do usuário sem aprovação → `403 APPROVAL_REQUIRED`; com aprovação, auditado.
- [ ] `TabClosed` publicado no fechamento; o listener do `pos` solicita a NFC-e e guarda a referência; reprocessar o evento não gera segunda NFC-e (teste).
- [ ] Consumo interno e cortesia fecham sem pagamento e sem faturar, exigem motivo e permissão.
- [ ] `ApplicationModules.verify()` passa; toda dependência nova segue a regra por camadas e está na tabela da ARCHITECTURE §3.1; `fiscal` e `payments` não dependem de `pos`.
- [ ] Build, testes e verificação do contrato oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Verificação do contrato → sem diff pendente e sem *breaking change*.

**Revisão humana:** sim

**Notas para o plano:**

- Hipóteses H1 e H6. Leia RF-PDV-05, RF-PDV-09, RF-PDV-10, RF-CTA-14 e RN-10 em `docs/SPEC.md` e os handoffs de M7 E02/E03 (Pix e cartão) e M7 E08 (NFC-e); `payments` e `fiscal` integrados ainda não têm nota de tema além da do M3.
- A taxa de serviço já existe no `billing` desde o M3 E06 (lançamento separado no `TX-SERVICO`, percentual informado pelo módulo de origem em `NewPosting.serviceChargeRate`, grupo `SERVICE_CHARGE` como repasse, remoção a pedido com motivo fixo): reuse essa regra em vez de criar outra (nota `ZenyHotelManager - Módulo billing (contas, roteamento e taxa de serviço)`). A taxa padrão do hotel nasce em 0% (nota herdada no cabeçalho).
- O turno de caixa se exige com `BillingApi.requireOpenShift(user)`, que trava o turno na transação de quem chama (trave o turno antes da conta), e os recebimentos manuais seguem o fluxo da M3 E08 (nota `ZenyHotelManager - Módulo billing (caixa, pagamentos, caução e encerramento)`). O limite de desconto é lido por `authorization.limit` e a ação sensível por `requireOrApproval` (nota `ZenyHotelManager - Segurança, autenticação e autorização`).
- O listener de `TabClosed` é `@ApplicationModuleListener` e precisa ser idempotente: o outbox reentrega (nota `ZenyHotelManager - Eventos e jobs`).
- Pontos de atenção da revisão: saldo diferente de zero, taxa de serviço como receita, NFC-e duplicada no reprocessamento, desconto sem aprovação, `fiscal` ou `payments` passando a depender de `pos`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- NFC-e rejeitada após o cliente sair: a comanda continua fechada, a nota fica na fila de `fiscal` e a rejeição gera alerta (M7 E06).

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E05 — Lançamento no quarto e café da manhã — #pendente

**Objetivo:** lançar a comanda na conta do quarto (RF-PDV-06), validando hóspede hospedado e crédito da conta e lançando o consumo detalhado, e operar o café da manhã (RF-PDV-08): lista diária de hóspedes com direito, marcação de presença e cobrança de quem não tem o café incluso.

**Por que agora:** completa o fluxo de A&B dos hóspedes e alimenta a NFC-e agrupada no check-out de M7 E08.

**Dependências:** E04. Os relatórios de E06 dependem destes dados (não paralelizável com ela).

**Escopo:**

- `Tab.chargeToRoom`: consulta a reserva hospedada via `reservations`; valida o limite de crédito da conta via `billing`; lança cada item como lançamento com código de A&B, origem PDV e taxa de serviço separada; status `CHARGED_TO_ROOM`; `Idempotency-Key`.
- O consumo lançado no quarto fica marcado para a NFC-e agrupada no check-out (M7 E08), sem NFC-e no fechamento da comanda (H6).
- `BreakfastAttendance`: lista do dia a partir das estadias com café incluso (H5), marcação de presença e número de pessoas; quem não tem incluso gera comanda no ponto de venda de café da manhã.

**Fora de escopo:** relatórios (E06).

**Critérios de aceite:**

- [ ] Lançamento no quarto com hóspede hospedado cria um lançamento por item na conta, com origem PDV e taxa de serviço separada; comanda fica `CHARGED_TO_ROOM`.
- [ ] Sem hóspede hospedado, com reserva encerrada ou acima do limite de crédito → erro com `code` estável.
- [ ] Repetição com a mesma `Idempotency-Key` não duplica lançamentos.
- [ ] Comanda lançada no quarto não emite NFC-e no fechamento; o consumo aparece para a NFC-e agrupada do check-out (teste integrando com M7 E08).
- [ ] Lista do café do dia mostra só estadias com café incluso; presença registrada; hóspede sem café incluso gera cobrança.
- [ ] Build, testes e verificação do contrato oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Verificação do contrato → sem diff pendente e sem *breaking change*.

**Revisão humana:** não

**Notas para o plano:**

- Confirme a H5 antes de planejar: se a `ReservationsApi` não expuser por data se a estadia inclui café (pelo plano ou pacote), acrescente essa consulta à API pública de `reservations` (dependência permitida); a regra de café incluso fica em `reservations`/`rates`, e o `pos` só consulta. As noites congeladas já trazem os pacotes da noite com o código de lançamento (nota `ZenyHotelManager - Módulo rates`, "Preço e vendabilidade de uma estadia"), e o regime de alimentação é do plano (`MealPlan`). Pendência do M4 E11: a tarifa do grupo não aplica os pacotes do plano, então uma reserva de pick-up num plano com café incluso não tem o pacote nas noites.
- Armadilha: no `billing`, o limite de crédito da conta **não bloqueia** lançamento (a conta só sinaliza `creditLimitExceeded`; nota `ZenyHotelManager - Módulo billing (contas, roteamento e taxa de serviço)`); o critério "acima do limite de crédito → erro" precisa da conferência no `pos` antes de lançar, ou de uma decisão registrada.
- O lançamento usa `BillingApi.post` com a origem `POS` e o percentual de taxa do ponto de venda em `NewPosting.serviceChargeRate`; a conta pode ter regra de roteamento que desvia o item (mesma nota). Leia o handoff do M7 E08 sobre a NFC-e agrupada do check-out.
- Pontos de atenção da revisão: lançamento duplicado, lançamento em reserva já encerrada, NFC-e no fechamento e de novo no check-out, nome do hóspede exposto a perfis sem permissão.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Café incluso calculado em dois lugares (`rates` e `pos`): a regra fica em `reservations`/`rates`, e `pos` só consulta.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E06 — Relatórios do PDV e da taxa de serviço — #pendente

**Objetivo:** entregar os relatórios do PDV (RF-PDV-11) — vendas por produto, categoria, garçom e período, ticket médio — e o relatório de arrecadação da taxa de serviço por colaborador com a retenção para encargos (RF-CTA-14, RN-10, Q-08).

**Por que agora:** fecha a trilha do restaurante e é necessário para o rateio da taxa aos colaboradores desde a inauguração.

**Dependências:** E05. Independente de E07–E11; conflito possível só em `api/openapi.yaml`.

**Escopo:**

- Views SQL de leitura `pos.report_*` publicadas como API do módulo (ARCHITECTURE §11), consumidas pelo `reporting` (M6) ou expostas por endpoints do `pos`, no padrão estabelecido em M6.
- Relatórios com filtros de período e ponto de venda; exportação CSV/XLSX conforme RF-REL-07 e o padrão de M6.
- Taxa de serviço: arrecadado por período, por colaborador e por ponto de venda, retenção configurável e valor a ratear; remoções registradas.

**Fora de escopo:** CMV e custo teórico (M9); KPIs gerenciais (M10).

**Critérios de aceite:**

- [ ] Relatório de vendas por produto, categoria, garçom e período e ticket médio batem com os totais esperados de um conjunto de dados de teste.
- [ ] Cortesias e consumo interno aparecem separados e não somam como venda.
- [ ] Taxa de serviço removida não é contada como arrecadada; o relatório mostra arrecadado, retenção configurada e valor a ratear por colaborador.
- [ ] Vendas são agrupadas pela data operacional, inclusive as feitas após a meia-noite antes do fechamento do dia (teste).
- [ ] `reporting` (ou o endpoint) só consulta as views `pos.report_*`.
- [ ] Exportação CSV/XLSX gera arquivo com os mesmos totais.
- [ ] Build, testes e verificação do contrato oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Verificação do contrato → sem diff pendente e sem *breaking change*.

**Revisão humana:** não

**Notas para o plano:**

- Hipótese H6 (Q-08). O padrão de `reporting`, endpoints e exportação vem do handoff do M6 (o `reporting` ainda não tem nota de tema); nome, forma, contrato no comentário, privilégios só `SELECT` e a prova com um papel que só lê as views seguem a nota `ZenyHotelManager - Schemas, migrações e views de relatório`.
- A taxa de serviço lançada na conta (inclusive a do quarto, E05) e as remoções já aparecem em `billing.report_postings` (`pass_through`, vínculos da taxa; nota `ZenyHotelManager - Módulo billing (caixa, pagamentos, caução e encerramento)`): decida e registre qual view é a fonte de cada parte do relatório para não contar a mesma taxa duas vezes.
- Pontos de atenção da revisão: `reporting` lendo tabelas internas do `pos`, cortesia somada como venda, taxa removida contada como arrecadada, data de negócio errada após a meia-noite.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Q-08 decidida diferente (taxa sobre diária, outro percentual de retenção): já é configuração; revisar o relatório se a base mudar.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E07 — Porta do channel manager e envio de ARI — #pendente

**Objetivo:** criar o módulo `distribution` com a porta neutra `ChannelManagerProvider` (RF-DIS-01), mapeamento de tipos de UH e planos para códigos externos (RF-DIS-02) e envio incremental de ARI — disponibilidade, tarifas e restrições — a cada alteração, com fila, reenvio e ressincronização completa sob demanda (RF-DIS-03), usando um provedor falso testado por WireMock.

**Por que agora:** a porta e o envio de ARI podem ser construídos sem o fornecedor; com a escolha (D1), só falta o adaptador.

**Dependências:** nenhuma dentro deste roadmap; exige o pré-requisito do cabeçalho. Independente de E01–E06 e E10; conflito possível só em `api/openapi.yaml`.

**Escopo:**

- Módulo `distribution/` (a criar; depende de `shared`, `rooms`, `rates`, `reservations` e, para alertas, `notifications`), schema e migrações próprios.
- `ChannelConnection` (provedor, status, credenciais cifradas, última sincronização), `RoomTypeMapping`, `RatePlanMapping`.
- Listeners de `AvailabilityChanged` (de `reservations`) e `RatesChanged`/`RestrictionsChanged` (de `rates`) gerando `AriUpdate` coalescidos por tipo e período; job de envio com ShedLock (ARCHITECTURE §13), retry com backoff e log redigido (padrão de M5).
- Ressincronização completa sob demanda e após reconexão.
- Provedor falso com WireMock; endpoints de configuração e mapeamento protegidos por permissão.

**Fora de escopo:** recebimento de reservas (E08); adaptador real (E09).

**Critérios de aceite:**

- [ ] Alterar disponibilidade, tarifa ou restrição gera `AriUpdate` coalescido por tipo e período e o envia ao provedor falso (teste com WireMock).
- [ ] Várias alterações do mesmo tipo e data antes do envio resultam num único envio com o valor mais recente.
- [ ] Com o provedor falhando, as atualizações ficam pendentes e são reenviadas com backoff, sem perda e sem um valor antigo sobrescrever um novo (teste).
- [ ] Ressincronização completa envia todo o horizonte configurado.
- [ ] Tipo de UH ou plano sem mapeamento não é enviado e gera notificação de falha de integração via `notifications` (RF-NOT-01), diretamente ou pelo mecanismo genérico de falha.
- [ ] Credenciais cifradas; log de integração redigido.
- [ ] `ApplicationModules.verify()` passa; `reservations` e `rates` não dependem de `distribution`.
- [ ] Build, testes e verificação do contrato oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Verificação do contrato → sem diff pendente e sem *breaking change*.

**Revisão humana:** não

**Notas para o plano:**

- Confirme o pré-requisito (gate do M7 ou a exceção do cabeçalho). Leia SPEC §5.21 e §8.2.17, ARCHITECTURE §12 e o handoff do M5 sobre o padrão de integração (FNRH: outbox, retry, log redigido, painel de saúde). A porta precisa servir a HotelRunner e a Omnibees (D1 em aberto): confira como as APIs públicas dos dois modelam ARI.
- Os três eventos não levam valores: `AvailabilityChanged` só traz tipo e período (o consumidor relê os contadores pela `ReservationsApi.inventory`), `RatesChanged` traz os planos derivados afetados (os derivados são calculados na leitura e não têm linhas próprias; preços efetivos pela `RatesApi.rateCalendar`) e `RestrictionsChanged` os campos mudados (notas `ZenyHotelManager - Módulo reservations (inventário, disponibilidade e reservas)` e `ZenyHotelManager - Módulo rates`). Até aqui não têm consumidor de produção, então o outbox só passa a gravar as publicações quando os listeners desta etapa existirem.
- Semântica das restrições para o channel manager, decidida pelo usuário em 2026-10-20 (nota `ZenyHotelManager - Módulo rates`, "Restrições de venda"): `closed` fecha qualquer noite da estadia; CTA e CTD só na data exata (o CTD é o da data de saída); estadia mínima e máxima "on arrival" (um channel manager que use "through" recebe a da chegada); a restrição do tipo de UH substitui a geral campo a campo; as restrições são de cada plano (o derivado não herda as do base).
- Credenciais com o `FieldCipher`/`EncryptedStringConverter` (nota `ZenyHotelManager - Tipos do kernel`); listeners idempotentes e job com `@SchedulerLock` e uma constante de adiamento no `@IntegrationTest` (notas `ZenyHotelManager - Eventos e jobs` e `ZenyHotelManager - Testes de integração`); o alerta usa o tipo `notifications.integration-failure`, sem dado de hóspede (nota `ZenyHotelManager - Módulo notifications`). A aresta `distribution` → `notifications` entra na tabela da ARCHITECTURE §3.1 se for usada.
- Pontos de atenção da revisão: ARI fora de ordem sobrescrevendo valor mais novo, envio perdido quando o provedor falha, `reservations` ou `rates` passando a depender de `distribution`, credencial em claro.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Limites de taxa de chamadas do provedor: coalescência e lote por envio, configuráveis.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E08 — Recebimento de reservas e painel de saúde — #pendente

**Objetivo:** receber reservas novas, alteradas e canceladas do channel manager por pull periódico (padrão para servidor local) ou webhook (RF-DIS-04), com confirmação idempotente pelo ID externo, criar ou alterar a reserva pela API de `reservations` com canal de origem, comissão e quem cobra (RF-DIS-05), e entregar log de sincronização, alertas e painel de saúde (RF-DIS-06).

**Por que agora:** completa a trilha de distribuição com o provedor falso; o adaptador real (E09) só troca a ponta.

**Dependências:** E07. Não paralelizável com E09.

**Escopo:**

- `InboundBookingMessage` (ID externo, tipo, payload, recebido em, status, erro) com processamento idempotente.
- Job de pull com ShedLock e endpoint de webhook autenticado, selecionados por configuração (RNF-02).
- Criação, alteração e cancelamento de reservas pela API pública de `reservations`, passando pelos contadores de disponibilidade (overbooking proibido, Q-09); conflito gera alerta e mensagem em erro, não reserva parcial.
- Canal de origem, comissão e quem cobra (H4); cartão virtual da OTA registrado como forma de garantia sem PAN.
- Painel de saúde: últimas sincronizações, pendências, erros e reprocessamento manual de mensagem com falha.

**Fora de escopo:** adaptador real (E09).

**Critérios de aceite:**

- [ ] Reserva nova, alteração e cancelamento chegam por pull e, em outro teste, por webhook, e refletem em `reservations` com canal, comissão e quem cobra.
- [ ] A mesma mensagem recebida duas vezes, ou pelos dois modos, gera uma única reserva e uma única confirmação ao provedor (teste).
- [ ] Reserva que excede a disponibilidade não é criada; a mensagem fica em erro e gera alerta.
- [ ] Webhook sem autenticação → `401` ou `403`, sem efeito.
- [ ] Cartão virtual da OTA registrado só como token/referência, sem número de cartão.
- [ ] Painel de saúde mostra última sincronização, pendências e erros; reprocessamento manual de mensagem com falha funciona e é auditado.
- [ ] Build, testes e verificação do contrato oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Verificação do contrato → sem diff pendente e sem *breaking change*.

**Revisão humana:** não

**Notas para o plano:**

- Confirme a H4 antes de planejar: a reserva agrupadora já guarda a origem (`OTA` vende no canal `OTA`) e o número externo (`externalId`), mas comissão e quem cobra não aparecem nas notas de tema; se faltarem, acrescente-os à API pública de `reservations` (dependência permitida).
- A `ReservationsApi` tem os caminhos sem permissão feitos para o channel manager: `createBooking` (sem step-up do desconto; o channel manager grava a diária da OTA como diária editada), `cancelReservation` e `confirmReservation(id, method)` (garantia `CARD` só informada, sem dado do cartão até o M7). A alteração da reserva **não** está na API pública (só `PUT /api/v1/reservations/{id}` com `If-Match`): a alteração vinda da OTA precisa de um caminho novo na `ReservationsApi`. A tomada do inventário recusa com `409 ROOM_TYPE_UNAVAILABLE` sem gravar nada (notas `ZenyHotelManager - Módulo reservations (inventário, disponibilidade e reservas)` e `ZenyHotelManager - Módulo reservations (ciclo de vida, alocação, grupos e busca)`).
- O titular e os hóspedes precisam existir no `guests` antes da reserva (`createBooking` recebe ids): a criação do perfil a partir da mensagem da OTA e a busca de duplicado são parte do plano (nota `ZenyHotelManager - Módulo guests`).
- O webhook é chamado pelo provedor, sem access token de usuário: um endpoint público exige mudar `SecurityConfiguration.PUBLIC_API_ENDPOINTS` e o `ApiSecurityTest`, e o `@Idempotent` (por usuário) não serve: a idempotência é pelo ID externo (nota `ZenyHotelManager - Segurança, autenticação e autorização` e `ZenyHotelManager - Convenções de API`). O painel de saúde e o reprocessamento seguem o padrão de integração do M5 (handoff); o actuator não expõe indicadores novos (nota `ZenyHotelManager - Docker, observabilidade e CI`).
- Pontos de atenção da revisão: reserva duplicada por mensagem repetida ou por pull e webhook ao mesmo tempo, overbooking por reserva de OTA, mensagem com erro descartada sem alerta, dado de cartão da OTA gravado.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Alteração de reserva que muda o tipo de UH sem disponibilidade: mesma regra da reserva nova (erro e alerta), sem alterar parcialmente.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E09 — Adaptador real do channel manager — #bloqueada

**Objetivo:** implementar e homologar o adaptador do channel manager escolhido atrás da porta `ChannelManagerProvider`, com envio de ARI, recebimento de reservas (pull, se o provedor suportar) e confirmação.

**Por que agora:** é a primeira integração real com o channel manager; o hotel abre com OTAs (SPEC §9).

**Dependências:** E08. Independente de E10–E12; conflito de edição possível na configuração de integrações.

**Escopo:** adaptador (a criar em `distribution/infrastructure/`), autenticação, mapeamento de erros, testes de contrato com WireMock a partir da documentação, mapeamentos iniciais de tipos de UH e planos, roteiro de homologação com reserva de teste de ponta a ponta.

**Fora de escopo:** certificação comercial com o fornecedor (prazo externo; registrar no handoff se aplicável).

**Critérios de aceite:**

- [ ] Testes de contrato com WireMock cobrem envio de ARI, recebimento de reservas, confirmação e os erros documentados.
- [ ] Em homologação: ARI enviado aparece no provedor; reserva de teste recebida, alterada e cancelada reflete no sistema sem duplicidade — evidências no handoff.
- [ ] Credenciais só por variável de ambiente ou secret; nada no repositório.
- [ ] Build e testes oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Roteiro de homologação → evidências registradas (IDs de reserva de teste, sem dados pessoais).

**Revisão humana:** sim

**Notas para o plano:**

- Só planeje com a D1 registrada e as credenciais de homologação disponíveis. O roteiro de homologação (ARI visível no extranet do provedor, reserva de teste recebida, alterada e cancelada) é executado com credenciais fora do repositório e conta como critério, não como passo opcional.
- Leia os handoffs de E07 e E08: o adaptador entra atrás da porta delas e os testes de contrato partem do mesmo WireMock. Sendo a Omnibees, a certificação do conector tem prazo externo (ARCHITECTURE §18).
- Pontos de atenção da revisão: segredo no repositório, erro do provedor tratado como sucesso, mapeamento que envia a disponibilidade de um tipo para outro.

**Bloqueios:**

- **Causa:** o channel manager não foi escolhido (SPEC Q-05).
- **Evidência:** SPEC §3.2 e §10 Q-05 listam o provedor como "a definir" (HotelRunner, Omnibees…); ARCHITECTURE §18 registra o risco; não há credenciais.
- **Impacto:** impede o adaptador real e a homologação da distribuição e, por consequência, o gate de inauguração E13.
- **Ação de desbloqueio:** o usuário escolhe o channel manager (considerando suporte a pull para servidor local) e obtém credenciais de homologação; registrar a decisão em D1 e trocar a tag desta etapa para `#pendente` (índice e título).

**Riscos e mitigação:**

- Omnibees exige conector certificado com prazo (ARCHITECTURE §18): se for o escolhido, iniciar a certificação assim que D1 for decidida.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E10 — Backup contínuo e restauração testada — #pendente

**Objetivo:** cumprir a RNF-07 e a ARCHITECTURE §15.3: backup completo diário e arquivamento contínuo de WAL do PostgreSQL para armazenamento externo, espelhamento do bucket de arquivos e um procedimento de restauração documentado e testado.

**Por que agora:** o hotel não pode abrir sem backup testado, seja na nuvem ou no hotel; o procedimento é o mesmo nos dois casos.

**Dependências:** nenhuma dentro deste roadmap; exige o pré-requisito do cabeçalho. Independente de E01–E09; conflito possível em `docker/compose.yaml`.

**Escopo:**

- Serviço `backup` em `docker/compose.yaml` (reservado em M0 E06) com pgBackRest ou WAL-G (H7), configurado por variáveis de ambiente.
- Arquivamento contínuo de WAL e base completa diária para storage externo compatível com S3; em desenvolvimento, um segundo MinIO simula o destino externo.
- Espelhamento do bucket do `storage` para o destino externo.
- Documento `docs/operacao/backup-e-restauracao.md` (a criar) com o procedimento de restauração (ponto no tempo), verificação e periodicidade do teste.
- Script de teste de restauração que sobe um ambiente separado a partir do backup e confere integridade (contagem de registros, migrações aplicadas, readiness `UP`).
- Métrica e alerta de backup atrasado (ARCHITECTURE §16).

**Fora de escopo:** destino externo de produção e credenciais reais (dependem de D2 e do usuário; configurados em E11).

**Critérios de aceite:**

- [ ] `docker compose -f docker/compose.yaml up -d` sobe o serviço `backup`, que faz base completa e arquiva WAL continuamente no destino externo simulado.
- [ ] A configuração de arquivamento garante RPO ≤ 1h (intervalo máximo de troca de WAL documentado e testado).
- [ ] O bucket de arquivos é espelhado no destino externo.
- [ ] O script de restauração recupera, num ambiente separado e sem o volume original, os dados até um ponto no tempo escolhido; readiness `UP` e contagens conferidas; tempo medido abaixo de 4h.
- [ ] `docs/operacao/backup-e-restauracao.md` descreve backup, restauração, verificação e periodicidade do teste.
- [ ] Métrica de idade do último backup exposta e alerta configurado.
- [ ] Nenhum segredo no repositório; build e testes oficiais passam.

**Validação obrigatória:**

- Script de teste de restauração → sucesso, com tempo registrado no handoff.
- Comandos oficiais de build e teste → sucesso.
- `docker compose -f docker/compose.yaml down` ao final.

**Revisão humana:** não

**Notas para o plano:**

- Confirme o pré-requisito (gate do M7 ou a exceção do cabeçalho) e leia o handoff do M0 E06 sobre o compose. Hipótese H7: registre no handoff a ferramenta escolhida (pgBackRest ou WAL-G).
- O compose, o MinIO (imagem `pgsty/minio`, preparado por `docker/storage/init-storage.sh`), as variáveis sem padrão (`${VAR:?…}`) do `docker/.env`, os dois papéis do banco e as regras de alerta em `docker/prometheus/alerts.yaml` estão nas notas `ZenyHotelManager - Docker, observabilidade e CI` e `ZenyHotelManager - Ambiente, banco, chaves e build`.
- Armadilha: os anexos sensíveis estão cifrados no bucket e várias colunas do banco estão cifradas com o anel `ZENY_ENCRYPTION_KEYS`; uma restauração só é útil com as mesmas chaves (e as JWT), então o procedimento precisa dizer onde elas ficam guardadas fora do servidor.
- Pontos de atenção da revisão: restauração que só funciona com o volume original, segredo do storage no repositório, RPO acima de 1h por configuração de arquivamento, bucket de arquivos fora do backup.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Servidor no hotel sem link estável para cópia externa: alerta de backup atrasado e retenção local até a cópia concluir.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E11 — Ambiente de produção: nuvem ou hotel — #bloqueada

**Objetivo:** preparar o ambiente de produção no modelo decidido (SPEC Q-06): TLS, modo das integrações (webhook ou pull/polling), destino real do backup, segredos e procedimento de implantação e atualização documentado, conforme a tabela da ARCHITECTURE §15.2.

**Por que agora:** é o último passo de infraestrutura antes da inauguração; depende do backup (E10).

**Dependências:** E10. Independente de E09 e E12; conflito possível em `docker/compose.yaml`.

**Escopo:**

- Arquivo de override de produção do compose (a criar em `docker/`) com o profile `prod`.
- Nuvem: Caddy com Let's Encrypt, webhooks habilitados. Hotel: rede local, túnel opcional para acesso externo, integrações em pull/polling, nobreak e cópia externa obrigatória (ARCHITECTURE §15.2).
- Segredos via Docker secrets ou variáveis do host (chave de cifragem, chave JWT, credenciais do banco, dos provedores e do backup).
- Dívida do M1 E04 (decidida pelo usuário em 2026-10-14): as migrações saem do boot da `api` para um job ou container de uso único que conecta com o dono dos schemas (`ZENY_DB_MIGRATION_*`). A `api` deixa de receber essas credenciais e conecta só com o papel restrito; o dono deixa de ser o superusuário do container (`POSTGRES_USER`) onde o modelo de implantação permitir.
- Pendência do M1 E04: decidir se o login com usuário inexistente vai para a trilha de auditoria ou para uma métrica (hoje não deixa registro, porque não escreve nada).
- Destino real do backup e do espelhamento (E10) com credenciais do usuário.
- `docs/operacao/implantacao.md` (a criar): instalação, atualização com o job de migração (Flyway) antes da `api`, rotação de chaves, verificação pós-implantação, rollback de versão.
- Smoke test no ambiente final: readiness `UP` via TLS, login, um evento SSE.

**Fora de escopo:** provisionamento do servidor ou contratação da VPS (ação do usuário).

**Critérios de aceite:**

- [ ] Smoke test no ambiente final: `/actuator/health/readiness` `UP` via TLS, login de um usuário de teste e recebimento de um evento SSE.
- [ ] Cada integração (FNRH, PSP, adquirente, middleware fiscal, channel manager, push) está configurada no modo compatível com o modelo decidido; no hotel, nenhuma depende de porta de entrada aberta (RNF-02).
- [ ] Swagger UI e endpoints sensíveis do actuator não respondem em produção.
- [ ] Backup e espelhamento gravam no destino real; uma restauração a partir dele foi executada em ambiente separado.
- [ ] `docs/operacao/implantacao.md` cobre instalação, atualização, rotação de chaves, verificação e rollback.
- [ ] Nenhum segredo no repositório; build e testes oficiais passam.

**Validação obrigatória:**

- Smoke test no ambiente final → sucesso.
- Teste de restauração com o destino real → sucesso, tempo registrado.
- Comandos oficiais de build e teste → sucesso.

**Revisão humana:** sim

**Notas para o plano:**

- Só planeje com a D2 registrada e o ambiente-alvo disponível; a implantação vai só ao ambiente-alvo autorizado pelo usuário (efeito fora da branch: parada do superpowers). Leia o handoff de E10 e, para o modo de cada integração (webhook × polling), os handoffs de M5, M7 e deste marco.
- Aplique as notas herdadas do cabeçalho: SSE pelo proxy com TLS (`flush_interval` sem buffer e tempo limite das conexões longas), log de acesso **sem query string** no proxy e na aplicação, segredos `ZENY_FCM_*` com os demais e a taxa de serviço padrão em 0% (para o checklist da E13).
- Pendências atribuídas a esta etapa nas notas de tema e ainda fora do Escopo (decidir com o usuário se entram): a re-cifragem dos valores que ainda usam uma chave antiga, que não tem job (`guests.guest.sensitive_data`, `files.stored_file.original_name`, `notifications.push_device.token`, os anexos sensíveis no bucket; nota `ZenyHotelManager - Ambiente, banco, chaves e build`, "Chaves de cifragem"), e o `idle_in_transaction_session_timeout` e o restante da configuração do PostgreSQL de produção (nota `ZenyHotelManager - Sincronização offline`, "Cursor sem perda").
- Swagger UI e `/v3/api-docs` já ficam desligados no `prod`, e o actuator só expõe liveness, readiness e prometheus (o proxy responde 404 ao prometheus); o critério confere isso no ambiente final (notas `ZenyHotelManager - Segurança, autenticação e autorização` e `ZenyHotelManager - Docker, observabilidade e CI`). A dívida das migrações (`ZENY_DB_MIGRATION_*`) está na nota `ZenyHotelManager - Ambiente, banco, chaves e build`.
- Pontos de atenção da revisão: segredo no repositório ou no override, porta aberta desnecessária no servidor do hotel, integração ainda em webhook sem rota de entrada, Swagger ou actuator sensível expostos.

**Bloqueios:**

- **Causa:** não está decidido se o servidor roda na nuvem ou no hotel (SPEC Q-06).
- **Evidência:** SPEC §1.1 ("local de hospedagem indefinido") e §10 Q-06; ARCHITECTURE §15.2 ("A decisão está em aberto") e §18.
- **Impacto:** impede definir TLS, modo das integrações, destino do backup e o procedimento de implantação, e por consequência o gate de inauguração E13.
- **Ação de desbloqueio:** o usuário decide entre VPS/nuvem e servidor no hotel e disponibiliza o ambiente-alvo e o destino externo de backup; registrar a decisão em D2 e trocar a tag desta etapa para `#pendente` (índice e título).

**Riscos e mitigação:**

- Queda de internet no hotel com servidor na nuvem: contingência 4G para recepção e PDV (ARCHITECTURE §15.2), registrada na documentação de operação.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E12 — Teste de desempenho e tempo real — #pendente

**Objetivo:** medir e comprovar a RNF-06 (p95 < 300 ms nas operações comuns com ~30 usuários simultâneos) e a RNF-04 (mudanças de status de UH, tarefas, comandas e notificações chegam aos clientes conectados em até 2 s), corrigindo gargalos encontrados.

**Por que agora:** com PDV e distribuição prontos, o sistema tem a carga de operação real da inauguração.

**Dependências:** E06, E08. Independente de E09–E11; conflito possível em configurações de pool e cache.

**Escopo:**

- Scripts de carga (a criar em `perf/`, ferramenta conforme H8) com cenários: painel de UHs, consulta de disponibilidade, criação de reserva, check-in, lançamento e fechamento de comanda, sincronização offline de tarefas.
- Massa de dados sintética realista para 30 UHs (sem dados pessoais reais).
- Medição de latência ponta a ponta de eventos SSE (alteração → recebimento no cliente).
- Relatório em `docs/operacao/desempenho.md` (a criar) com resultados e ajustes feitos.

**Fora de escopo:** teste de carga em produção (pode ser repetido em E11 ou E13 pelo usuário).

**Critérios de aceite:**

- [ ] Scripts de carga em `perf/` cobrem painel de UHs, disponibilidade, criação de reserva, check-in, lançamento e fechamento de comanda e sincronização offline.
- [ ] Com ~30 usuários simultâneos, p95 < 300 ms em cada operação do cenário (relatório da ferramenta anexado ao handoff).
- [ ] Latência ponta a ponta de eventos SSE (alteração → cliente) ≤ 2 s no p95.
- [ ] A massa de dados é sintética, sem dados pessoais reais.
- [ ] Ajustes de código têm testes de regressão; build e testes oficiais passam.
- [ ] `docs/operacao/desempenho.md` registra ambiente, cenários, resultados e ajustes.

**Validação obrigatória:**

- Execução dos scripts de carga contra o compose local → métricas dentro dos limites.
- Comandos oficiais de build e teste → sucesso.

**Revisão humana:** não

**Notas para o plano:**

- Hipótese H8 (k6 ou Gatling). Leia RNF-04 e RNF-06 na SPEC §7 e os handoffs de E06 e E08; as operações mais frequentes por perfil vêm da SPEC §3.1.
- A cotação já tem medição dentro dos testes (`QuotePerformanceTest`, M4 E06: p95 de 21 a 43 ms com 30 UHs e 730 dias; nota `ZenyHotelManager - Módulo reservations (inventário, disponibilidade e reservas)`), útil como linha de base e modelo de massa. O canal SSE manda mensagens só depois do commit, com *heartbeat* e buffer em memória por tópico (nota `ZenyHotelManager - Tempo real (SSE)`): a latência é medida no cliente, não no servidor.
- Pontos de atenção da revisão: medição com cache quente ou sem concorrência real, latência SSE medida só no servidor, dados pessoais reais na massa, otimização que quebra regra de negócio.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Máquina de desenvolvimento diferente do servidor final: repetir o cenário no ambiente de E11 antes do gate.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E13 — Gate de inauguração (Fases 0–2) — #pendente

**Objetivo:** confirmar que o servidor está pronto para inaugurar: definição global de pronto deste marco atendida, gate de M7 concluído e um cenário ponta a ponta das Fases 0, 1 e 2 executado no ambiente de produção (ou homologação equivalente) com os fornecedores reais.

**Por que agora:** é o critério da SPEC §9 ("Para a inauguração, o mínimo são as Fases 0, 1 e 2") e a entrada do marco M9.

**Dependências:** E09, E11, E12 (que cobrem transitivamente todas as demais). Não paralelizável.

**Escopo:**

- Conferir que os gates de M0 a M7 estão `#concluida` nos respectivos roadmaps.
- Rodar build, testes, verificação de módulos e do contrato numa cópia limpa.
- Cenário ponta a ponta: reserva vinda da OTA, check-in com FNRH e caução, tarefa de governança concluída offline, consumo no restaurante com NFC-e e outro lançado no quarto, fechamento do dia, check-out com NFS-e e captura, conciliação, backup e restauração.
- Checklist de inauguração em `docs/operacao/checklist-inauguracao.md` (a criar): credenciais de produção, Cadastur e FNRH (Q-12), cadastros iniciais (UHs, tarifas, cardápio, perfis fiscais, usuários e roles), a configuração do hotel (inclusive a taxa de serviço padrão, que nasce em 0%: `PUT /api/v1/hotel/tax-settings`, nota herdada do M3 E11), impressoras e agente, monitoramento e alertas, contatos de suporte dos fornecedores.
- Atualizar `docs/roadmaps/README.md` registrando a conclusão do M8 no histórico de revisões.

**Fora de escopo:** funcionalidade nova. Problemas voltam para a etapa de origem (reabrir com `#em-andamento` e registrar no handoff dela), salvo ajustes triviais de documentação.

**Critérios de aceite:**

- [ ] Os gates de M0 a M7 estão `#concluida` nos respectivos roadmaps.
- [ ] Todos os itens da "Definição global de pronto" estão marcados, cada um com evidência.
- [ ] O cenário ponta a ponta das Fases 0–2 foi executado com os fornecedores reais, sem erro e sem divergência na conciliação.
- [ ] `docs/operacao/checklist-inauguracao.md` cobre credenciais, Cadastur e FNRH, cadastros iniciais, impressoras, monitoramento, LGPD e contatos de suporte, e foi revisado com o usuário.
- [ ] `docs/roadmaps/README.md` registra a conclusão do M8 no histórico de revisões.

**Validação obrigatória:**

- Comandos oficiais de build, teste e verificação do contrato numa cópia limpa → sucesso.
- Cenário ponta a ponta → evidências registradas.

**Revisão humana:** sim

**Notas para o plano:**

- Leia o "Evidências e handoff" de todas as etapas E01 a E12 e, nos gates de M0 a M7, as pendências que ainda dependem de ação do usuário; o checklist de inauguração é revisado com o usuário (entrevista), não só escrito.
- Convenção dos gates anteriores (handoffs dos gates de M1 a M4 nos roadmaps; resumo na nota `ZenyHotelManager - Estado por marco`): validação numa cópia limpa (`git archive`, `GRADLE_USER_HOME` novo, `--no-daemon --no-build-cache`), cenário num `docker compose` isolado (`-p` próprio, `--env-file` gerado do `docker/.env.example` fora do repositório, profile `prod`, pelo proxy, `down -v` no fim), oasdiff contra o gate anterior e o cenário do marco também como teste de integração na raiz do pacote de testes (como `MilestoneM3EndToEndTest` e `MilestoneM4EndToEndTest`).
- A captura de atualização do brain desta etapa leva o estado do marco (M8 concluído, inauguração) para a nota `ZenyHotelManager - Estado por marco`.
- Pontos de atenção da revisão: item da definição de pronto sem evidência, cenário executado só com adaptadores falsos, gate de marco anterior não concluído, checklist sem itens legais (FNRH, fiscal, LGPD).

**Bloqueios:** Nenhum bloqueio conhecido. Esta etapa só fica disponível depois que E09 e E11 forem desbloqueadas e concluídas.

**Riscos e mitigação:**

- Pendência legal fora do sistema (Cadastur, credencial FNRH, cadastro na prefeitura): listada no checklist com responsável e prazo.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa (marco M9): —

---

## Histórico de revisões

- 2026-10-13 — roadmap criado.
- 2026-10-13 — aplicadas as decisões da entrevista SPEC × ARCHITECTURE (regra por camadas na ARCHITECTURE §3.1; `TabClosed` tratado por listener do próprio `pos`, agora registrado na ARCHITECTURE e na SPEC §8.3): restrições de grafo e alertas reescritas, H5 simplificada (`pos` → `rates` permitido), E01, E04 e E07 ajustados (critérios, escopo e prompt).
- 2026-10-14 — E11 recebe duas pendências do M1 E04, decididas pelo usuário: migrações num job de uso único com o dono dos schemas (a `api` deixa de receber `ZENY_DB_MIGRATION_*`) e a decisão entre trilha de auditoria e métrica para o login com usuário inexistente. Nenhuma tag mudou.
- 2026-10-15 — notas herdadas do M2 acrescentadas em "Hipóteses e decisões em aberto" (SSE pelo proxy com TLS e `ZENY_FCM_*` como segredos de implantação). Nenhuma etapa reescrita; tags inalteradas.
- 2026-10-20 — nota herdada do gate do M3 (E11, decisão do usuário): configurar a taxa de serviço padrão do hotel, que nasce em 0%, na implantação; acrescentada às notas herdadas e ao checklist de inauguração da E13. Tags inalteradas.
- 2026-10-22 — migrado para o formato da skill development-roadmap com superpowers (branch `roadmap/m8-restaurante-distribuicao`, protocolo e prompt de execução únicos, etapas sem prompt e estratégia) e para o Second Brain (notas de tema no lugar das seções do CLAUDE.md, captura de atualização por etapa); decisão do usuário.
- 2026-10-27 — protocolo de execução migrado para as souls do Second Brain: a sessão roda como `orquestrador-etapa` (`claude --agent orquestrador-etapa`), e as tarefas passam por `desenvolvedor` → `testador` → verificação mecânica dos testes → `revisor-seguranca`; a captura do brain é publicada pelo `brain-escriba`. Sem fallback para os prompts padrão do superpowers nem para `executing-plans`. Nenhuma etapa, tag, evidência ou handoff mudou.
