# Roadmap — M8 Restaurante, distribuição e inauguração

> Arquivo: `docs/roadmaps/roadmap-m8-restaurante-distribuicao.md` · criado em 2026-10-13 · última revisão 2026-10-15
>
> Marco 8 de 11 · mapa dos marcos em [`docs/roadmaps/README.md`](README.md) · anterior: [M7 — Pagamentos integrados e fiscal](roadmap-m7-pagamentos-fiscal.md) · próximo: [M9 — Suprimentos](roadmap-m9-suprimentos.md)
>
> **Pré-requisito:** o gate do marco M7 (E12 de `docs/roadmaps/roadmap-m7-pagamentos-fiscal.md`) está `#concluida`. Exceção controlada: se o gate de M7 ainda estiver pendente **apenas** porque os adaptadores reais de fornecedores (M7 E09, E10, E11) esperam a SPEC Q-05, as etapas E01–E08, E10 e E12 deste marco podem começar com M7 E01–E08 `#concluida`, usando os adaptadores falsos. O gate E13 deste marco exige o gate de M7 concluído.

## Como usar

Duas formas, ambas na raiz do repositório:

- **Manual:** cole o prompt de uma etapa numa sessão limpa do Claude Code. Uma etapa por sessão.
- **Automática:** cole o prompt da seção "Execução automática" numa sessão limpa; ela executa até 3 etapas em sequência, um subagente por etapa mais um verificador, e para para revisão. Para não parar em cada pedido de permissão, libere antes os comandos de build/teste do projeto nas permissões do Claude Code (os comandos oficiais estão no `CLAUDE.md`, mais `docker`).

**Este roadmap foi escrito antes do código existir.** Antes da primeira etapa, sincronize-o com o código real usando a skill `development-roadmap` (modo atualização): confira caminhos, nomes de módulos, comandos e convenções registrados nos handoffs de M0–M7 e ajuste as etapas pendentes.

Etapas marcadas como paralelizáveis só devem rodar ao mesmo tempo em worktrees git separados; na mesma pasta, rode-as uma de cada vez. Este marco tem três trilhas independentes: restaurante (E01–E06), distribuição (E07–E09) e implantação (E10–E11).

Em ambas, quem executa segue o "Protocolo de execução", atualiza a tag da etapa e preenche "Evidências e handoff". Não pule dependências. Revise e faça commit entre etapas ou lotes — nenhuma sessão faz commit por conta própria.

## Objetivo

Deixar o hotel **pronto para inaugurar** (fim da Fase 2 da SPEC §9): restaurante e bar abertos ao público operando no PDV (cardápio, comandas, produção, fechamento com pagamento integrado e NFC-e, lançamento no quarto, café da manhã, taxa de serviço), distribuição pelo channel manager (ARI incremental e recebimento de reservas) e o ambiente de produção com backup testado, TLS e desempenho medido. O marco fecha com um gate ponta a ponta das Fases 0, 1 e 2.

## Contexto confirmado

- Requisitos do PDV: SPEC §5.19 (RF-PDV-01 a 06, 08 a 11; RF-PDV-07, ficha técnica, é do M9), RF-CTA-09 e RF-CTA-14 (§5.10, caixa e taxa de serviço), RN-10 (taxa de serviço), RN-12 (ações sensíveis).
- Requisitos de distribuição: SPEC §5.21 (RF-DIS-01 a 06).
- Requisitos não funcionais: RNF-01, RNF-02, RNF-04 (≤ 2 s em tempo real), RNF-06 (p95 < 300 ms com ~30 usuários), RNF-07 (backup, RPO ≤ 1h, RTO ≤ 4h, restauração testada), RNF-08 (TLS) — SPEC §7.
- Modelo de domínio: SPEC §8.2.15 (`Outlet`, `ProductionStation`, `MenuCategory`, `MenuItem`, `Modifier`, `DiningTable`, `Tab`, `TabItem`, `ProductionTicket`, `BreakfastAttendance`) e §8.2.17 (`ChannelConnection`, `Channel`, `RoomTypeMapping`, `RatePlanMapping`, `AriUpdate`, `InboundBookingMessage`, `ChannelManagerProvider`). Eventos `TabClosed`, `AvailabilityChanged`, `RatesChanged`, `RestrictionsChanged`: SPEC §8.3.
- Arquitetura: `pos` depende de `shared`, `hotel`, `staff`, `billing`, `payments`, `fiscal`, `inventory`, `reservations`, `printing`; `distribution` depende de `shared`, `rooms`, `rates`, `reservations` (ARCHITECTURE §3.1). Tempo real com tópicos `tabs` e `production` (§10). Integrações e modo pull (§12). Jobs (§13). Impressão (§14). Implantação, nuvem × local e backup (§15.1–15.3). Observabilidade (§16). Riscos Q-05/Q-06 (§18). Regra por camadas da §3.1: qualquer módulo pode chamar os serviços de plataforma (`files`, `notifications`, `printing`, `hotel`) e depender de camadas inferiores sem aprovação (ex.: `pos` → `rates`), desde que o grafo siga acíclico e a aresta nova seja acrescentada à tabela §3.1 na mesma mudança.
- Convenções herdadas (ler nos handoffs e no `CLAUDE.md`): M0 (API, erros, `Money`, idempotência, outbox, ShedLock, Docker/compose com o serviço `backup` reservado, CI), M1 (autorização, limites, step-up, auditoria, `hotel`, `staff`), M2 (SSE, notificações), M3 (`billing`: contas, lançamentos, turnos de caixa, pontos de caixa), M4 (`rates`, `reservations`, disponibilidade), M5 (`printing`, padrão de integração da FNRH), M6 (relatórios operacionais), M7 (`payments` Pix e cartão, API de NFC-e em `fiscal`).
- Caminhos previstos (a criar, sob o pacote base `com.zeny.hotel`, hipótese herdada de M0): `pos/` e `distribution/` com `domain/`, `application/`, `infrastructure/`, `web/`; migrações nas pastas `pos` e `distribution` de `src/main/resources/db/migration/`; `docker/compose.yaml` e `docker/` para backup e proxy.
- Comandos de build, teste e lint: os oficiais registrados no `CLAUDE.md` (definidos em M0 E01).

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
- **Re-cifragem na rotação de chaves** (herdado do M3 E01): o `GuestDocumentRekeyJob` re-cifra e reindexa os documentos de hóspede; os outros valores cifrados (`guests.guest.sensitive_data`, `files.stored_file.original_name`, `notifications.push_device.token` e os anexos sensíveis no bucket) ainda não têm job, então uma chave só sai do anel depois de re-cifrá-los (procedimento em "Chaves de cifragem" no `CLAUDE.md`).
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
Você é o verificador independente da etapa ETAPA do roadmap docs/roadmaps/roadmap-m8-restaurante-distribuicao.md. Você não participou da implementação e não deve editar nenhum arquivo.
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
Você é o orquestrador do roadmap docs/roadmaps/roadmap-m8-restaurante-distribuicao.md neste repositório. Seu papel é coordenar subagentes, não implementar: não edite código e mantenha seu contexto enxuto (leia o roadmap e os relatórios dos subagentes, não o código).

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

**Dependências e paralelismo:** nenhuma dentro deste roadmap; exige o pré-requisito do cabeçalho. Pode correr em paralelo com E07 e E10 (em worktrees separados); conflito possível só em `api/openapi.yaml`.

**Escopo:**

- Módulo `pos/` (a criar), schema e migrações próprios.
- `Outlet` (tipo: restaurante, bar, room service, café da manhã; horários; ponto de caixa de `billing`; impressoras de produção de `printing`; taxa de serviço própria), `DiningTable`, `ProductionStation` (cozinha, bar, com impressora opcional).
- `MenuCategory`, `MenuItem` (preço por ponto de venda, ativo/disponível, perfil fiscal de `fiscal` por ID, setor de produção), `Modifier` com variação de preço.
- Permissões novas no catálogo (padrão de M1) e auditoria das alterações.
- Endpoints CRUD em `/api/v1` com paginação por cursor e `ETag`.

**Fora de escopo:** ficha técnica e local de estoque (M9); comandas (E02).

**Passos de implementação:**

1. Confirmar o pré-requisito (gate de M7 ou a exceção do cabeçalho).
2. Escrever testes de API para os cadastros, incluindo validações (preço negativo, perfil fiscal inexistente, setor inexistente).
3. Implementar módulo, migrações, permissões e endpoints; regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "o que SPEC §5.19 e §8.2.15 definem para pontos de venda e cardápio?"; "como M1 registra permissões no catálogo e como M3/M5/M7 expõem ponto de caixa, impressoras e perfis fiscais para referência por ID (handoffs)?".
- Partes paralelas: nenhuma.
- Foco do verificador: `pos` acessando tabelas de outros módulos em vez da API pública; preço em ponto flutuante; escrita sem permissão.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E01 — "Pontos de venda e cardápio" do roadmap docs/roadmaps/roadmap-m8-restaurante-distribuicao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Pré-requisito", o "Protocolo de execução" e as restrições — e a seção E01 inteira. Confirme o pré-requisito: o gate E12 de docs/roadmaps/roadmap-m7-pagamentos-fiscal.md está #concluida, ou, pela exceção do cabeçalho, M7 E01 a E08 estão #concluida e só os adaptadores reais aguardam Q-05; se nenhum dos dois, pare e relate sem implementar. Rode git status e preserve mudanças preexistentes que não sejam suas. Se o código divergiu do plano, pare e relate.

Objetivo: criar o módulo pos com pontos de venda (tipo, horários, ponto de caixa, impressoras de produção, taxa de serviço), mesas, setores de produção, categorias, produtos com preço por ponto de venda, disponibilidade, perfil fiscal e setor, e modificadores, com permissões no catálogo, auditoria e endpoints CRUD em /api/v1. Referências a outros módulos são por ID via API pública. Não implemente comandas nem ficha técnica.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E01 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume SPEC §5.19 e §8.2.15 e como M1, M3, M5 e M7 expõem permissões, pontos de caixa, impressoras e perfis fiscais (handoffs);
- escreva os testes de API primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em acesso a tabelas de outros módulos, preço em ponto flutuante e escrita sem permissão.

Critérios de aceite (detalhes na seção E01):
- CRUD de pontos de venda, mesas, setores, categorias, produtos e modificadores coberto por testes de API;
- referência a perfil fiscal, ponto de caixa ou impressora inexistente → erro com code estável;
- escrita sem permissão → 403; verificação de módulos, build e testes oficiais passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E01 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Endpoints CRUD de pontos de venda, mesas, setores de produção, categorias, produtos e modificadores estão no contrato e cobertos por testes de API.
- [ ] Produto tem preço por ponto de venda em `Money`; produto indisponível não aparece na consulta de cardápio do ponto de venda.
- [ ] Referência a perfil fiscal, ponto de caixa, impressora ou setor inexistente → erro com `code` estável.
- [ ] Escrita sem a permissão do catálogo → `403`; alterações auditadas.
- [ ] `ApplicationModules.verify()` passa com `pos` usando só módulos listados na sua coluna da tabela da ARCHITECTURE §3.1 (aresta nova permitida pela regra por camadas é acrescentada à tabela).
- [ ] Build, testes e verificação do contrato oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (no `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sem diff pendente e sem *breaking change*.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Preço por ponto de venda complica o cardápio: um preço padrão no produto com sobreposição por ponto de venda.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E02 — Comandas — #pendente

**Objetivo:** implementar o ciclo de vida da comanda (RF-PDV-03): abrir por mesa, balcão ou UH, lançar itens com modificadores e observações, transferir itens entre comandas, juntar e dividir comandas e cancelar itens — livremente antes do envio à produção e como ação sensível depois.

**Por que agora:** é o agregado central do PDV; produção e fechamento operam sobre ele.

**Dependências e paralelismo:** E01. Não paralelizável com E03–E06 (mesmo agregado `Tab`).

**Escopo:**

- `Tab` (número legível por sequência, status `OPEN`, `CLOSED`, `CHARGED_TO_ROOM`, `CANCELLED`), `TabItem` (quantidade, preço unitário congelado no lançamento, modificadores, observações, status).
- Garçom vinculado por colaborador de `staff`; escopo `OWN` para o garçom ver só as próprias comandas (padrão de M1).
- Comanda por UH valida, via `reservations`, que há hóspede hospedado na UH.
- Transferência, junção e divisão de comandas preservando o histórico.
- Cancelamento de item enviado à produção com step-up (RF-ACS-08) e motivo.
- Concorrência com `ETag`/`If-Match`; publicação de eventos de alteração para o tópico `tabs` (consumido em E03).

**Fora de escopo:** envio à produção (E03); fechamento e pagamento (E04).

**Passos de implementação:**

1. Escrever testes de domínio e de API das operações e das regras de escopo e step-up.
2. Implementar e regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em SPEC §5.19 e §8.2.15 e convenções de escopo e step-up no handoff de M1.
- Partes paralelas: nenhuma.
- Foco do verificador: item cancelado após envio sem aprovação; garçom vendo comanda de outro sem permissão `ALL`; divisão que perde ou duplica itens; preço alterado retroativamente quando o cardápio muda.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E02 — "Comandas" do roadmap docs/roadmaps/roadmap-m8-restaurante-distribuicao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e as restrições —, a seção E02 inteira e o "Evidências e handoff" de E01. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: implementar o agregado Tab do módulo pos — abrir por mesa, balcão ou UH (validando hóspede hospedado via reservations), lançar itens com preço congelado, modificadores e observações, transferir itens, juntar e dividir comandas e cancelar itens, exigindo step-up e motivo depois do envio à produção — com escopo OWN para garçom, ETag/If-Match e eventos para o tópico tabs. Não implemente produção nem fechamento.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E02 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de domínio e de API primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em cancelamento sem aprovação, garçom vendo comanda alheia, divisão que perde itens e preço retroativo.

Critérios de aceite (detalhes na seção E02):
- abrir, lançar, transferir, juntar, dividir e cancelar cobertos por testes;
- cancelamento de item enviado sem aprovação → 403 APPROVAL_REQUIRED;
- comanda por UH sem hóspede hospedado → erro com code estável;
- build, testes e verificação do contrato oficiais passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E02 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

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

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Divisão por pessoa versus por item: suportar os dois e registrar a escolha de modelo no handoff.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E03 — Produção: impressão e tela em tempo real — #pendente

**Objetivo:** enviar os itens da comanda à produção (RF-PDV-04): gerar um ticket por setor, imprimi-lo na impressora do setor pelo agente de impressão e exibi-lo na tela de produção via SSE, com os estados do item pedido → em preparo → pronto → entregue.

**Por que agora:** sem produção o restaurante não opera; depende das comandas (E02).

**Dependências e paralelismo:** E02. Não paralelizável com E04 (mesmo agregado `Tab`).

**Escopo:**

- `ProductionTicket` por setor de produção ao enviar a comanda; itens enviados ficam marcados (afeta o cancelamento de E02).
- Impressão ESC/POS pelo `printing` de M5 (H3), com template de produção sem dados pessoais (RN-13).
- Tópicos SSE `tabs` e `production` no padrão de M2 (H2), filtrados por permissão; payload mínimo.
- Endpoints para a tela de produção listar tickets do setor e mudar o estado do item.
- Reimpressão de ticket.

**Fora de escopo:** fechamento (E04); baixa de estoque por ficha técnica (M9).

**Passos de implementação:**

1. Escrever testes de módulo: envio gera tickets por setor, job de impressão criado, evento SSE emitido, transições de estado do item.
2. Implementar e regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "como M2 registra tópicos SSE e filtra por permissão (handoff de M2)?"; "como M5 cria jobs de impressão e templates ESC/POS em `printing` (handoff de M5)?".
- Partes paralelas: nenhuma.
- Foco do verificador: ticket impresso duas vezes num reenvio; nome do hóspede no ticket ou no evento SSE; usuário sem permissão recebendo o tópico `production`.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E03 — "Produção: impressão e tela em tempo real" do roadmap docs/roadmaps/roadmap-m8-restaurante-distribuicao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução", as restrições e as hipóteses H2 e H3 —, a seção E03 inteira e o "Evidências e handoff" de E02. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E02 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: ao enviar a comanda à produção, gerar um ticket por setor, criar o job de impressão ESC/POS no printing do M5 com template sem dados pessoais, publicar nos tópicos SSE tabs e production (padrão do M2, filtrados por permissão) e oferecer endpoints para a tela de produção listar tickets e mudar o estado do item (pedido, em preparo, pronto, entregue), com reimpressão. Não implemente fechamento nem baixa de estoque.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E03 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume como M2 registra tópicos SSE e como M5 cria jobs e templates de impressão (handoffs);
- escreva os testes de módulo primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em impressão duplicada, dado pessoal no ticket ou no SSE e tópico sem filtro de permissão.

Critérios de aceite (detalhes na seção E03):
- envio gera um ticket por setor e um job de impressão por impressora configurada;
- evento no tópico production chega a usuário com permissão e não chega a quem não tem;
- transições de estado do item cobertas por teste;
- build, testes e verificação do contrato oficiais passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E03 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

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

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Impressora fora do ar: o job fica pendente no `printing` e a tela de produção continua funcionando.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E04 — Fechamento de comanda, taxa de serviço e NFC-e — #pendente

**Objetivo:** fechar comandas (RF-PDV-05): pagamento em dinheiro, Pix ou cartão (integrado ou manual) dentro de um turno de caixa do ponto de venda (RF-PDV-09), divisão entre pessoas, taxa de serviço removível com registro (RN-10), desconto dentro do limite ou com aprovação, emissão de NFC-e e consumo interno ou cortesia com motivo (RF-PDV-10).

**Por que agora:** é a etapa que movimenta dinheiro e emite nota no restaurante; depende de comandas e produção.

**Dependências e paralelismo:** E03. Não paralelizável com E05 (mesmo agregado `Tab`).

**Escopo:**

- Fechamento com um ou mais pagamentos via `payments` (Pix e cartão de M7; registro manual de M3), `Idempotency-Key`, troco em dinheiro; comanda só fecha com saldo zero.
- Turno de caixa de `billing` aberto para o usuário e o ponto de caixa do ponto de venda (RF-CTA-10).
- Divisão do pagamento entre pessoas por valor ou por itens.
- Taxa de serviço conforme a configuração do ponto de venda e de `hotel` (H6), separada do total, removível a pedido com registro de quem removeu.
- Desconto por item ou total, validado contra o limite do usuário; acima do limite → step-up.
- Evento `TabClosed` publicado no fechamento; listener do próprio `pos` solicita a NFC-e pela API pública de `fiscal` (H1) e guarda a referência do documento (decisão da ARCHITECTURE §3.1 e SPEC §8.3).
- Consumo interno e cortesia: fecham sem pagamento e sem faturar, com motivo e permissão; a baixa de estoque fica para M9.

**Fora de escopo:** lançamento no quarto (E05); baixa de estoque (M9).

**Passos de implementação:**

1. Ler os handoffs de M3 (turno de caixa), M7 E02/E03 (Pix e cartão) e M7 E08 (NFC-e).
2. Escrever testes de módulo: fechamento com dois meios de pagamento, sem turno de caixa, com taxa removida, com desconto acima do limite, NFC-e solicitada após `TabClosed`, cortesia.
3. Implementar e regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "como `billing` exige turno de caixa e registra pagamentos de PDV (handoff de M3)?"; "como chamar Pix, cartão e a API de NFC-e (handoffs de M7 E02, E03 e E08)?"; "como limites de desconto são lidos do contexto de autorização (handoff de M1)?".
- Partes paralelas: nenhuma.
- Foco do verificador: comanda fechada com saldo diferente de zero; taxa de serviço contabilizada como receita do hotel; NFC-e emitida duas vezes ao reprocessar `TabClosed`; desconto acima do limite sem aprovação; `fiscal` ou `payments` passando a depender de `pos`.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E04 — "Fechamento de comanda, taxa de serviço e NFC-e" do roadmap docs/roadmaps/roadmap-m8-restaurante-distribuicao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução", as restrições e as hipóteses H1 e H6 —, a seção E04 inteira e o "Evidências e handoff" de E03. Leia RF-PDV-05, RF-PDV-09, RF-PDV-10, RF-CTA-14 e RN-10 em docs/SPEC.md. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E03 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: fechar comandas com um ou mais pagamentos (dinheiro com troco, Pix e cartão via payments, registro manual) com Idempotency-Key e turno de caixa aberto do ponto de venda, divisão entre pessoas, taxa de serviço separada e removível com registro, desconto validado contra o limite ou com step-up, publicação de TabClosed e NFC-e solicitada por um listener do próprio pos chamando a API pública de fiscal, além de consumo interno e cortesia com motivo, sem faturar. fiscal e payments não podem passar a depender de pos.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E04 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume turno de caixa e pagamentos em billing (M3), chamadas de Pix, cartão e NFC-e (M7 E02, E03, E08) e limites de desconto (M1);
- escreva os testes de módulo primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em saldo diferente de zero, taxa de serviço como receita, NFC-e duplicada, desconto sem aprovação e violação do grafo de módulos.

Critérios de aceite (detalhes na seção E04):
- fechamento com dois meios de pagamento e troco coberto por teste; sem turno de caixa → erro com code estável;
- taxa de serviço separada, removível com registro; desconto acima do limite sem aprovação → 403 APPROVAL_REQUIRED;
- TabClosed reprocessado não emite segunda NFC-e;
- verificação de módulos, build e testes oficiais passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E04 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

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

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- NFC-e rejeitada após o cliente sair: a comanda continua fechada, a nota fica na fila de `fiscal` e a rejeição gera alerta (M7 E06).

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E05 — Lançamento no quarto e café da manhã — #pendente

**Objetivo:** lançar a comanda na conta do quarto (RF-PDV-06), validando hóspede hospedado e crédito da conta e lançando o consumo detalhado, e operar o café da manhã (RF-PDV-08): lista diária de hóspedes com direito, marcação de presença e cobrança de quem não tem o café incluso.

**Por que agora:** completa o fluxo de A&B dos hóspedes e alimenta a NFC-e agrupada no check-out de M7 E08.

**Dependências e paralelismo:** E04. Não paralelizável com E06 (relatórios dependem destes dados).

**Escopo:**

- `Tab.chargeToRoom`: consulta a reserva hospedada via `reservations`; valida o limite de crédito da conta via `billing`; lança cada item como lançamento com código de A&B, origem PDV e taxa de serviço separada; status `CHARGED_TO_ROOM`; `Idempotency-Key`.
- O consumo lançado no quarto fica marcado para a NFC-e agrupada no check-out (M7 E08), sem NFC-e no fechamento da comanda (H6).
- `BreakfastAttendance`: lista do dia a partir das estadias com café incluso (H5), marcação de presença e número de pessoas; quem não tem incluso gera comanda no ponto de venda de café da manhã.

**Fora de escopo:** relatórios (E06).

**Passos de implementação:**

1. Confirmar H5; se `reservations` não expuser a inclusão de café por data, acrescentar a consulta na API pública de `reservations`.
2. Escrever testes de módulo: lançamento no quarto com e sem hóspede hospedado, acima do crédito, repetição com a mesma chave; lista de café, presença e cobrança.
3. Implementar e regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "como `billing` lança itens com origem e valida limite de crédito (handoff de M3)?"; "como `reservations` expõe hóspede hospedado por UH e planos/pacotes com café (handoff de M4)?".
- Partes paralelas: nenhuma.
- Foco do verificador: lançamento duplicado na conta; lançamento em reserva já encerrada; NFC-e emitida no fechamento e de novo no check-out; nome do hóspede exposto a perfis sem permissão.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E05 — "Lançamento no quarto e café da manhã" do roadmap docs/roadmaps/roadmap-m8-restaurante-distribuicao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução", as restrições e as hipóteses H5 e H6 —, a seção E05 inteira e o "Evidências e handoff" de E04. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E04 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: lançar a comanda na conta do quarto, validando hóspede hospedado via reservations e crédito da conta via billing, com cada item lançado com origem PDV, taxa de serviço separada, Idempotency-Key e marcação para a NFC-e agrupada no check-out; e operar o café da manhã com lista diária de hóspedes com direito, presença e cobrança de quem não tem incluso. Se reservations não expuser a inclusão de café por data, acrescente essa consulta à API pública dele.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E05 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume como billing lança itens e valida crédito (M3) e como reservations expõe hóspede hospedado e café incluso (M4);
- escreva os testes de módulo primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em lançamento duplicado, reserva encerrada, NFC-e em dobro e dado pessoal exposto.

Critérios de aceite (detalhes na seção E05):
- lançamento no quarto com hóspede hospedado gera lançamentos detalhados na conta;
- sem hóspede hospedado ou acima do crédito → erro com code estável; repetição com a mesma chave não duplica;
- lista de café, presença e cobrança de quem não tem incluso cobertas por teste;
- build, testes e verificação do contrato oficiais passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E05 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

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

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Café incluso calculado em dois lugares (`rates` e `pos`): a regra fica em `reservations`/`rates`, e `pos` só consulta.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E06 — Relatórios do PDV e da taxa de serviço — #pendente

**Objetivo:** entregar os relatórios do PDV (RF-PDV-11) — vendas por produto, categoria, garçom e período, ticket médio — e o relatório de arrecadação da taxa de serviço por colaborador com a retenção para encargos (RF-CTA-14, RN-10, Q-08).

**Por que agora:** fecha a trilha do restaurante e é necessário para o rateio da taxa aos colaboradores desde a inauguração.

**Dependências e paralelismo:** E05. Pode correr em paralelo com E07–E11 (em worktrees separados); conflito possível só em `api/openapi.yaml`.

**Escopo:**

- Views SQL de leitura `pos.report_*` publicadas como API do módulo (ARCHITECTURE §11), consumidas pelo `reporting` (M6) ou expostas por endpoints do `pos`, no padrão estabelecido em M6.
- Relatórios com filtros de período e ponto de venda; exportação CSV/XLSX conforme RF-REL-07 e o padrão de M6.
- Taxa de serviço: arrecadado por período, por colaborador e por ponto de venda, retenção configurável e valor a ratear; remoções registradas.

**Fora de escopo:** CMV e custo teórico (M9); KPIs gerenciais (M10).

**Passos de implementação:**

1. Ler o handoff de M6 sobre o padrão de relatórios e views de leitura.
2. Escrever testes de integração com dados conhecidos e totais esperados.
3. Implementar views, endpoints e exportação; regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "qual padrão de views `report_*`, endpoints e exportação M6 estabeleceu em `reporting` (handoff de M6)?".
- Partes paralelas: nenhuma.
- Foco do verificador: `reporting` lendo tabelas internas do `pos` em vez das views; cortesias somadas como venda; taxa removida contada como arrecadada; data de negócio errada nas vendas após meia-noite.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E06 — "Relatórios do PDV e da taxa de serviço" do roadmap docs/roadmaps/roadmap-m8-restaurante-distribuicao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a hipótese H6 —, a seção E06 inteira e o "Evidências e handoff" de E04 e E05. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E05 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: publicar views de leitura pos.report_* e os relatórios do PDV (vendas por produto, categoria, garçom e período, ticket médio) e da taxa de serviço (arrecadado por colaborador e ponto de venda, retenção configurável, valor a ratear, remoções), com filtros e exportação CSV/XLSX no padrão de relatórios estabelecido no M6. reporting só lê as views, nunca tabelas internas do pos.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E06 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume o padrão de views, endpoints e exportação do reporting (handoff de M6);
- escreva os testes de integração com totais esperados primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em leitura de tabelas internas, cortesia somada como venda, taxa removida contada e data de negócio errada.

Critérios de aceite (detalhes na seção E06):
- relatórios de vendas e de taxa de serviço batem com os totais esperados dos dados de teste;
- cortesias e taxa removida tratadas corretamente;
- exportação CSV/XLSX funcionando; build, testes e verificação do contrato oficiais passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E06 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

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

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Q-08 decidida diferente (taxa sobre diária, outro percentual de retenção): já é configuração; revisar o relatório se a base mudar.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E07 — Porta do channel manager e envio de ARI — #pendente

**Objetivo:** criar o módulo `distribution` com a porta neutra `ChannelManagerProvider` (RF-DIS-01), mapeamento de tipos de UH e planos para códigos externos (RF-DIS-02) e envio incremental de ARI — disponibilidade, tarifas e restrições — a cada alteração, com fila, reenvio e ressincronização completa sob demanda (RF-DIS-03), usando um provedor falso testado por WireMock.

**Por que agora:** a porta e o envio de ARI podem ser construídos sem o fornecedor; com a escolha (D1), só falta o adaptador.

**Dependências e paralelismo:** nenhuma dentro deste roadmap; exige o pré-requisito do cabeçalho. Pode correr em paralelo com E01–E06 e E10 (em worktrees separados); conflito possível só em `api/openapi.yaml`.

**Escopo:**

- Módulo `distribution/` (a criar; depende de `shared`, `rooms`, `rates`, `reservations` e, para alertas, `notifications`), schema e migrações próprios.
- `ChannelConnection` (provedor, status, credenciais cifradas, última sincronização), `RoomTypeMapping`, `RatePlanMapping`.
- Listeners de `AvailabilityChanged` (de `reservations`) e `RatesChanged`/`RestrictionsChanged` (de `rates`) gerando `AriUpdate` coalescidos por tipo e período; job de envio com ShedLock (ARCHITECTURE §13), retry com backoff e log redigido (padrão de M5).
- Ressincronização completa sob demanda e após reconexão.
- Provedor falso com WireMock; endpoints de configuração e mapeamento protegidos por permissão.

**Fora de escopo:** recebimento de reservas (E08); adaptador real (E09).

**Passos de implementação:**

1. Confirmar o pré-requisito e ler os handoffs de M4 (eventos e consultas de disponibilidade e tarifas) e M5 (padrão de integração).
2. Escrever testes de módulo: alteração de disponibilidade gera ARI coalescido, falha do provedor reenvia com backoff, ressincronização envia todo o horizonte, tipo de UH sem mapeamento não é enviado e gera alerta.
3. Implementar e regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "o que SPEC §5.21 e §8.2.17 e ARCHITECTURE §12 exigem?"; "quais eventos e consultas de disponibilidade e tarifas `reservations` e `rates` expõem (handoff de M4)?"; "como as APIs públicas de HotelRunner e Omnibees modelam ARI, para que a porta sirva aos dois?".
- Partes paralelas: nenhuma.
- Foco do verificador: ARI enviado fora de ordem sobrescrevendo valor mais novo; envio perdido quando o provedor falha; `reservations` ou `rates` passando a depender de `distribution`; credencial em claro.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E07 — "Porta do channel manager e envio de ARI" do roadmap docs/roadmaps/roadmap-m8-restaurante-distribuicao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Pré-requisito", o "Protocolo de execução" e as restrições — e a seção E07 inteira. Confirme o pré-requisito: o gate E12 de docs/roadmaps/roadmap-m7-pagamentos-fiscal.md está #concluida, ou, pela exceção do cabeçalho, M7 E01 a E08 estão #concluida; se nenhum dos dois, pare e relate sem implementar. Leia os handoffs de M4 sobre eventos de disponibilidade e tarifas e de M5 sobre o padrão de integração. Rode git status e preserve mudanças preexistentes que não sejam suas. Se o código divergiu do plano, pare e relate.

Objetivo: criar o módulo distribution (depende de shared, rooms, rates, reservations e notifications para alertas; acrescente distribution → notifications à tabela da docs/ARCHITECTURE.md §3.1 se usar diretamente) com a porta ChannelManagerProvider, ChannelConnection com credenciais cifradas, mapeamentos de tipos de UH e planos, listeners de AvailabilityChanged, RatesChanged e RestrictionsChanged que geram AriUpdate coalescidos, job de envio com ShedLock, retry com backoff, log redigido, ressincronização completa sob demanda e provedor falso testado com WireMock. Não implemente recebimento de reservas nem adaptador real.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E07 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume SPEC §5.21 e §8.2.17, ARCHITECTURE §12, os eventos e consultas de M4 e como HotelRunner e Omnibees modelam ARI;
- escreva os testes de módulo primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em ARI fora de ordem, envio perdido, dependência invertida e credencial em claro.

Critérios de aceite (detalhes na seção E07):
- alteração de disponibilidade, tarifa ou restrição gera ARI coalescido enviado ao provedor falso;
- falha do provedor reenvia com backoff sem perder nem reordenar atualizações;
- ressincronização completa envia todo o horizonte configurado;
- verificação de módulos, build e testes oficiais passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E07 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

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

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Limites de taxa de chamadas do provedor: coalescência e lote por envio, configuráveis.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E08 — Recebimento de reservas e painel de saúde — #pendente

**Objetivo:** receber reservas novas, alteradas e canceladas do channel manager por pull periódico (padrão para servidor local) ou webhook (RF-DIS-04), com confirmação idempotente pelo ID externo, criar ou alterar a reserva pela API de `reservations` com canal de origem, comissão e quem cobra (RF-DIS-05), e entregar log de sincronização, alertas e painel de saúde (RF-DIS-06).

**Por que agora:** completa a trilha de distribuição com o provedor falso; o adaptador real (E09) só troca a ponta.

**Dependências e paralelismo:** E07. Não paralelizável com E09.

**Escopo:**

- `InboundBookingMessage` (ID externo, tipo, payload, recebido em, status, erro) com processamento idempotente.
- Job de pull com ShedLock e endpoint de webhook autenticado, selecionados por configuração (RNF-02).
- Criação, alteração e cancelamento de reservas pela API pública de `reservations`, passando pelos contadores de disponibilidade (overbooking proibido, Q-09); conflito gera alerta e mensagem em erro, não reserva parcial.
- Canal de origem, comissão e quem cobra (H4); cartão virtual da OTA registrado como forma de garantia sem PAN.
- Painel de saúde: últimas sincronizações, pendências, erros e reprocessamento manual de mensagem com falha.

**Fora de escopo:** adaptador real (E09).

**Passos de implementação:**

1. Confirmar H4 e, se preciso, acrescentar canal, comissão e quem cobra à API pública de `reservations`.
2. Escrever testes de módulo: reserva nova por pull e por webhook, mensagem repetida, alteração, cancelamento, reserva que excede a disponibilidade, reprocessamento manual.
3. Implementar e regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "como `reservations` cria, altera e cancela reservas pela API pública e onde guarda canal e origem (handoff de M4)?"; "como o padrão de integração de M5 expõe painel de saúde e reprocessamento?".
- Partes paralelas: nenhuma.
- Foco do verificador: reserva duplicada por mensagem repetida ou por pull e webhook ao mesmo tempo; overbooking por reserva de OTA; mensagem com erro descartada sem alerta; dado de cartão da OTA gravado.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E08 — "Recebimento de reservas e painel de saúde" do roadmap docs/roadmaps/roadmap-m8-restaurante-distribuicao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução", as restrições e a hipótese H4 —, a seção E08 inteira e o "Evidências e handoff" de E07. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E07 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: receber reservas novas, alteradas e canceladas do channel manager por job de pull com ShedLock ou por webhook autenticado (selecionados por configuração), processando InboundBookingMessage de forma idempotente pelo ID externo, criando ou alterando a reserva pela API pública de reservations com canal, comissão e quem cobra, sem overbooking, e entregar log de sincronização, alertas e painel de saúde com reprocessamento manual. Se reservations não aceitar canal, comissão e quem cobra, acrescente-os à API pública dele. Não implemente o adaptador real.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E08 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume a API pública de reservations para criar, alterar e cancelar (M4) e o painel de saúde e reprocessamento do padrão de integração de M5;
- escreva os testes de módulo primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em reserva duplicada, overbooking, mensagem descartada sem alerta e dado de cartão gravado.

Critérios de aceite (detalhes na seção E08):
- reserva nova, alteração e cancelamento por pull e por webhook cobertos por teste;
- mensagem repetida ou recebida pelos dois modos não duplica a reserva;
- reserva sem disponibilidade não é criada e gera alerta;
- painel de saúde e reprocessamento manual funcionando; build e testes oficiais passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E08 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

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

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Alteração de reserva que muda o tipo de UH sem disponibilidade: mesma regra da reserva nova (erro e alerta), sem alterar parcialmente.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E09 — Adaptador real do channel manager — #bloqueada

**Objetivo:** implementar e homologar o adaptador do channel manager escolhido atrás da porta `ChannelManagerProvider`, com envio de ARI, recebimento de reservas (pull, se o provedor suportar) e confirmação.

**Por que agora:** é a primeira integração real com o channel manager; o hotel abre com OTAs (SPEC §9).

**Dependências e paralelismo:** E08. Pode correr em paralelo com E10–E12 (em worktrees separados); conflito de edição na configuração de integrações.

**Escopo:** adaptador (a criar em `distribution/infrastructure/`), autenticação, mapeamento de erros, testes de contrato com WireMock a partir da documentação, mapeamentos iniciais de tipos de UH e planos, roteiro de homologação com reserva de teste de ponta a ponta.

**Fora de escopo:** certificação comercial com o fornecedor (prazo externo; registrar no handoff se aplicável).

**Passos de implementação:**

1. Confirmar D1 registrada e credenciais de homologação disponíveis.
2. Escrever testes de contrato.
3. Implementar e executar o roteiro de homologação: ARI enviado e visível no extranet do provedor, reserva de teste recebida, alterada e cancelada.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "quais endpoints, autenticação, formatos de ARI e de reserva, modos push/pull e limites de chamadas a documentação do channel manager escolhido define?".
- Partes paralelas: nenhuma.
- Foco do verificador: segredo no repositório; erro do provedor tratado como sucesso; mapeamento que envia disponibilidade de um tipo para outro.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E09 — "Adaptador real do channel manager" do roadmap docs/roadmaps/roadmap-m8-restaurante-distribuicao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a decisão D1 —, a seção E09 inteira e o "Evidências e handoff" de E07 e E08. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E08 está #concluida, que E09 está #pendente (não #bloqueada) e que D1 registra o channel manager escolhido; se não, pare e relate sem implementar. Se o código divergiu do plano, pare e relate.

Objetivo: implementar o adaptador do channel manager escolhido atrás da porta ChannelManagerProvider (autenticação, envio de ARI, recebimento de reservas por pull se suportado ou webhook, confirmação, mapeamento de erros), com testes de contrato em WireMock baseados na documentação oficial, mapeamentos iniciais de tipos de UH e planos e roteiro de homologação executado com credenciais fora do repositório.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E09 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume endpoints, autenticação, formatos, modos push/pull e limites do channel manager escolhido;
- escreva os testes de contrato primeiro, implemente, rode os comandos oficiais de build e teste do CLAUDE.md e o roteiro de homologação;
- verificação independente com o Prompt do verificador, com foco em segredo no repositório, erro tratado como sucesso e mapeamento trocado.

Critérios de aceite (detalhes na seção E09):
- testes de contrato passam;
- ARI visível no provedor e reserva de teste recebida, alterada e cancelada, com evidência no handoff;
- nenhum segredo no repositório; build e testes oficiais passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E09 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Testes de contrato com WireMock cobrem envio de ARI, recebimento de reservas, confirmação e os erros documentados.
- [ ] Em homologação: ARI enviado aparece no provedor; reserva de teste recebida, alterada e cancelada reflete no sistema sem duplicidade — evidências no handoff.
- [ ] Credenciais só por variável de ambiente ou secret; nada no repositório.
- [ ] Build e testes oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Roteiro de homologação → evidências registradas (IDs de reserva de teste, sem dados pessoais).

**Bloqueios:**

- **Causa:** o channel manager não foi escolhido (SPEC Q-05).
- **Evidência:** SPEC §3.2 e §10 Q-05 listam o provedor como "a definir" (HotelRunner, Omnibees…); ARCHITECTURE §18 registra o risco; não há credenciais.
- **Impacto:** impede o adaptador real e a homologação da distribuição e, por consequência, o gate de inauguração E13.
- **Ação de desbloqueio:** o usuário escolhe o channel manager (considerando suporte a pull para servidor local) e obtém credenciais de homologação; registrar a decisão em D1 e trocar a tag desta etapa para `#pendente` (índice e título).

**Riscos e mitigação:**

- Omnibees exige conector certificado com prazo (ARCHITECTURE §18): se for o escolhido, iniciar a certificação assim que D1 for decidida.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E10 — Backup contínuo e restauração testada — #pendente

**Objetivo:** cumprir a RNF-07 e a ARCHITECTURE §15.3: backup completo diário e arquivamento contínuo de WAL do PostgreSQL para armazenamento externo, espelhamento do bucket de arquivos e um procedimento de restauração documentado e testado.

**Por que agora:** o hotel não pode abrir sem backup testado, seja na nuvem ou no hotel; o procedimento é o mesmo nos dois casos.

**Dependências e paralelismo:** nenhuma dentro deste roadmap; exige o pré-requisito do cabeçalho. Pode correr em paralelo com E01–E09 (em worktrees separados); conflito possível em `docker/compose.yaml`.

**Escopo:**

- Serviço `backup` em `docker/compose.yaml` (reservado em M0 E06) com pgBackRest ou WAL-G (H7), configurado por variáveis de ambiente.
- Arquivamento contínuo de WAL e base completa diária para storage externo compatível com S3; em desenvolvimento, um segundo MinIO simula o destino externo.
- Espelhamento do bucket do `storage` para o destino externo.
- Documento `docs/operacao/backup-e-restauracao.md` (a criar) com o procedimento de restauração (ponto no tempo), verificação e periodicidade do teste.
- Script de teste de restauração que sobe um ambiente separado a partir do backup e confere integridade (contagem de registros, migrações aplicadas, readiness `UP`).
- Métrica e alerta de backup atrasado (ARCHITECTURE §16).

**Fora de escopo:** destino externo de produção e credenciais reais (dependem de D2 e do usuário; configurados em E11).

**Passos de implementação:**

1. Escrever o script de teste de restauração primeiro e confirmar que falha sem backup.
2. Configurar o serviço `backup`, o espelhamento e a métrica.
3. Executar o ciclo completo: dados de teste, backup, perda simulada, restauração em ambiente separado, verificação; medir o tempo.
4. Documentar o procedimento.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "o que ARCHITECTURE §15.1–15.3 e §16 e RNF-07 exigem?"; "como pgBackRest e WAL-G configuram arquivamento contínuo para S3 e restauração em ponto no tempo com PostgreSQL 18 em container?".
- Partes paralelas: nenhuma.
- Foco do verificador: restauração que só funciona com o volume original; segredo do storage no repositório; RPO acima de 1h por configuração de arquivamento; bucket de arquivos fora do backup.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E10 — "Backup contínuo e restauração testada" do roadmap docs/roadmaps/roadmap-m8-restaurante-distribuicao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Pré-requisito", o "Protocolo de execução" e a hipótese H7 — e a seção E10 inteira. Confirme o pré-requisito: o gate E12 de docs/roadmaps/roadmap-m7-pagamentos-fiscal.md está #concluida, ou, pela exceção do cabeçalho, M7 E01 a E08 estão #concluida; se nenhum dos dois, pare e relate sem implementar. Leia o handoff de M0 E06 sobre o compose. Rode git status e preserve mudanças preexistentes que não sejam suas. Se o código divergiu do plano, pare e relate.

Objetivo: adicionar o serviço backup ao docker/compose.yaml com pgBackRest ou WAL-G (base diária e WAL contínuo para storage compatível com S3, simulado em desenvolvimento por um segundo MinIO), espelhar o bucket de arquivos, expor métrica e alerta de backup atrasado, escrever docs/operacao/backup-e-restauracao.md e um script que restaura num ambiente separado e verifica a integridade. Não configure destino nem credenciais de produção, que dependem da decisão D2.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E10 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume ARCHITECTURE §15.1–15.3 e §16, RNF-07 e a configuração de arquivamento contínuo e restauração em ponto no tempo do pgBackRest e do WAL-G;
- escreva o script de teste de restauração primeiro, implemente, execute o ciclo completo medindo o tempo e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em restauração dependente do volume original, segredo no repositório, RPO acima de 1h e bucket fora do backup.

Critérios de aceite (detalhes na seção E10):
- ciclo backup → perda simulada → restauração em ambiente separado executado com sucesso e tempo medido abaixo de 4h;
- WAL arquivado com intervalo que garante RPO ≤ 1h;
- bucket de arquivos espelhado; procedimento documentado; nenhum segredo no repositório.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E10 (snapshot, arquivos alterados, decisões — inclusive a ferramenta escolhida —, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

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

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Servidor no hotel sem link estável para cópia externa: alerta de backup atrasado e retenção local até a cópia concluir.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E11 — Ambiente de produção: nuvem ou hotel — #bloqueada

**Objetivo:** preparar o ambiente de produção no modelo decidido (SPEC Q-06): TLS, modo das integrações (webhook ou pull/polling), destino real do backup, segredos e procedimento de implantação e atualização documentado, conforme a tabela da ARCHITECTURE §15.2.

**Por que agora:** é o último passo de infraestrutura antes da inauguração; depende do backup (E10).

**Dependências e paralelismo:** E10. Pode correr em paralelo com E09 e E12 (em worktrees separados); conflito possível em `docker/compose.yaml`.

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

**Passos de implementação:**

1. Confirmar D2 registrada e o ambiente-alvo disponível.
2. Escrever o override, a documentação e o smoke test.
3. Implantar no ambiente-alvo e executar o smoke test e um teste de restauração com o destino real.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "o que ARCHITECTURE §15.1–15.3 e RNF-01, RNF-02 e RNF-08 exigem para o modelo decidido?"; "quais integrações precisam mudar de modo (webhook × polling) e como cada uma é configurada (handoffs de M5, M7 e deste marco)?".
- Partes paralelas: nenhuma.
- Foco do verificador: segredo no repositório ou no override; porta aberta desnecessária no servidor do hotel; integração ainda em modo webhook sem rota de entrada; Swagger ou actuator sensível expostos em produção.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E11 — "Ambiente de produção: nuvem ou hotel" do roadmap docs/roadmaps/roadmap-m8-restaurante-distribuicao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a decisão D2 —, a seção E11 inteira e o "Evidências e handoff" de E10. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E10 está #concluida, que E11 está #pendente (não #bloqueada) e que D2 registra se o servidor roda na nuvem ou no hotel; se não, pare e relate sem implementar. Se o código divergiu do plano, pare e relate.

Objetivo: preparar a produção no modelo decidido conforme docs/ARCHITECTURE.md §15.2 — override de compose com profile prod, TLS (Let's Encrypt na nuvem; rede local com túnel opcional no hotel), modo de cada integração (webhook ou pull/polling), segredos por Docker secrets ou variáveis do host, destino real do backup — e escrever docs/operacao/implantacao.md com instalação, atualização, rotação de chaves, verificação e rollback, mais um smoke test no ambiente final. Nenhum segredo entra no repositório.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E11 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume ARCHITECTURE §15.1–15.3, RNF-01, RNF-02 e RNF-08 e como cada integração troca entre webhook e polling (handoffs de M5, M7 e deste marco);
- escreva o smoke test primeiro, implemente, implante no ambiente-alvo, rode o smoke test e um teste de restauração com o destino real, e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em segredo no repositório, porta aberta desnecessária, integração sem rota de entrada e endpoints sensíveis expostos.

Critérios de aceite (detalhes na seção E11):
- smoke test passa no ambiente final via TLS;
- todas as integrações configuradas no modo compatível com o modelo decidido;
- restauração com o destino real de backup executada; documentação de implantação completa; nenhum segredo no repositório.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E11 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy além do ambiente-alvo autorizado pelo usuário.
```

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

**Bloqueios:**

- **Causa:** não está decidido se o servidor roda na nuvem ou no hotel (SPEC Q-06).
- **Evidência:** SPEC §1.1 ("local de hospedagem indefinido") e §10 Q-06; ARCHITECTURE §15.2 ("A decisão está em aberto") e §18.
- **Impacto:** impede definir TLS, modo das integrações, destino do backup e o procedimento de implantação, e por consequência o gate de inauguração E13.
- **Ação de desbloqueio:** o usuário decide entre VPS/nuvem e servidor no hotel e disponibiliza o ambiente-alvo e o destino externo de backup; registrar a decisão em D2 e trocar a tag desta etapa para `#pendente` (índice e título).

**Riscos e mitigação:**

- Queda de internet no hotel com servidor na nuvem: contingência 4G para recepção e PDV (ARCHITECTURE §15.2), registrada na documentação de operação.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E12 — Teste de desempenho e tempo real — #pendente

**Objetivo:** medir e comprovar a RNF-06 (p95 < 300 ms nas operações comuns com ~30 usuários simultâneos) e a RNF-04 (mudanças de status de UH, tarefas, comandas e notificações chegam aos clientes conectados em até 2 s), corrigindo gargalos encontrados.

**Por que agora:** com PDV e distribuição prontos, o sistema tem a carga de operação real da inauguração.

**Dependências e paralelismo:** E06, E08. Pode correr em paralelo com E09–E11 (em worktrees separados); conflito possível em configurações de pool e cache.

**Escopo:**

- Scripts de carga (a criar em `perf/`, ferramenta conforme H8) com cenários: painel de UHs, consulta de disponibilidade, criação de reserva, check-in, lançamento e fechamento de comanda, sincronização offline de tarefas.
- Massa de dados sintética realista para 30 UHs (sem dados pessoais reais).
- Medição de latência ponta a ponta de eventos SSE (alteração → recebimento no cliente).
- Relatório em `docs/operacao/desempenho.md` (a criar) com resultados e ajustes feitos.

**Fora de escopo:** teste de carga em produção (pode ser repetido em E11 ou E13 pelo usuário).

**Passos de implementação:**

1. Criar massa de dados e scripts de carga.
2. Rodar contra o compose local, medir p95 por operação e latência SSE.
3. Corrigir gargalos (índices, consultas N+1, pool) com testes de regressão quando houver mudança de código.
4. Registrar resultados no documento.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "quais operações são as mais frequentes por perfil (recepção, governança, garçom) segundo a SPEC §3.1 e os módulos implementados?"; "há consultas sem índice nas tabelas de disponibilidade, contas e comandas?".
- Partes paralelas: nenhuma.
- Foco do verificador: medição feita com cache quente ou sem concorrência real; latência SSE medida só no servidor; dados pessoais reais na massa de teste; otimização que quebra regra de negócio.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E12 — "Teste de desempenho e tempo real" do roadmap docs/roadmaps/roadmap-m8-restaurante-distribuicao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a hipótese H8 —, a seção E12 inteira e o "Evidências e handoff" de E06 e E08. Leia RNF-04 e RNF-06 em docs/SPEC.md §7. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E06 e E08 estão #concluida e que o código bate com o handoff delas; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar em perf/ scripts de carga com massa sintética para 30 UHs (sem dados pessoais reais) cobrindo painel de UHs, disponibilidade, reserva, check-in, comanda e sincronização offline; medir p95 por operação com ~30 usuários simultâneos e a latência ponta a ponta de eventos SSE no cliente; corrigir gargalos com testes de regressão; registrar resultados em docs/operacao/desempenho.md.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E12 para #em-andamento no índice e no título;
- investigação: um subagente Explore levanta as operações mais frequentes por perfil e consultas sem índice em disponibilidade, contas e comandas;
- implemente scripts e massa, meça, corrija gargalos e rode os comandos oficiais de build e teste do CLAUDE.md após cada ajuste de código;
- verificação independente com o Prompt do verificador, com foco em medição sem concorrência real, latência SSE medida só no servidor, dados pessoais reais e otimização que quebra regra de negócio.

Critérios de aceite (detalhes na seção E12):
- p95 < 300 ms em todas as operações do cenário com ~30 usuários simultâneos;
- eventos SSE chegam ao cliente em até 2 s no p95;
- resultados e ajustes registrados em docs/operacao/desempenho.md; build e testes oficiais passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E12 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

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

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Máquina de desenvolvimento diferente do servidor final: repetir o cenário no ambiente de E11 antes do gate.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E13 — Gate de inauguração (Fases 0–2) — #pendente

**Objetivo:** confirmar que o servidor está pronto para inaugurar: definição global de pronto deste marco atendida, gate de M7 concluído e um cenário ponta a ponta das Fases 0, 1 e 2 executado no ambiente de produção (ou homologação equivalente) com os fornecedores reais.

**Por que agora:** é o critério da SPEC §9 ("Para a inauguração, o mínimo são as Fases 0, 1 e 2") e a entrada do marco M9.

**Dependências e paralelismo:** E09, E11, E12 (que cobrem transitivamente todas as demais). Não paralelizável.

**Escopo:**

- Conferir que os gates de M0 a M7 estão `#concluida` nos respectivos roadmaps.
- Rodar build, testes, verificação de módulos e do contrato numa cópia limpa.
- Cenário ponta a ponta: reserva vinda da OTA, check-in com FNRH e caução, tarefa de governança concluída offline, consumo no restaurante com NFC-e e outro lançado no quarto, fechamento do dia, check-out com NFS-e e captura, conciliação, backup e restauração.
- Checklist de inauguração em `docs/operacao/checklist-inauguracao.md` (a criar): credenciais de produção, Cadastur e FNRH (Q-12), cadastros iniciais (UHs, tarifas, cardápio, perfis fiscais, usuários e roles), a configuração do hotel (inclusive a taxa de serviço padrão, que nasce em 0%: `PUT /api/v1/hotel/tax-settings`, nota herdada do M3 E11), impressoras e agente, monitoramento e alertas, contatos de suporte dos fornecedores.
- Atualizar `docs/roadmaps/README.md` registrando a conclusão do M8 no histórico de revisões.

**Fora de escopo:** funcionalidade nova. Problemas voltam para a etapa de origem (reabrir com `#em-andamento` e registrar no handoff dela), salvo ajustes triviais de documentação.

**Passos de implementação:**

1. Conferir os gates anteriores e rodar as validações numa cópia limpa.
2. Executar o cenário ponta a ponta e registrar as evidências.
3. Escrever o checklist de inauguração e revisá-lo com o usuário.
4. Marcar a definição global de pronto e atualizar o README dos marcos.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "quais itens de definição de pronto e pendências registradas nos gates de M0 a M7 ainda dependem de ação do usuário?".
- Partes paralelas: nenhuma.
- Foco do verificador: item da definição de pronto sem evidência; cenário executado só com adaptadores falsos; gate de marco anterior não concluído; checklist sem itens legais (FNRH, fiscal, LGPD).

**Prompt para uma sessão limpa:**

```text
Execute a etapa E13 — "Gate de inauguração (Fases 0–2)" do roadmap docs/roadmaps/roadmap-m8-restaurante-distribuicao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Pré-requisito", o "Protocolo de execução" e a "Definição global de pronto" —, a seção E13 inteira e o "Evidências e handoff" de todas as etapas E01 a E12. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 a E12 estão #concluida e que os gates de M0 a M7 estão #concluida nos roadmaps de docs/roadmaps/; se não, ou se o código divergiu do plano, pare e relate.

Objetivo: verificar que o servidor está pronto para inaugurar: validações oficiais numa cópia limpa, cenário ponta a ponta das Fases 0–2 no ambiente de produção ou homologação equivalente com fornecedores reais (reserva da OTA, check-in com FNRH e caução, tarefa offline, consumo no restaurante com NFC-e e no quarto, fechamento do dia, check-out com NFS-e e captura, conciliação, backup e restauração), checklist de inauguração em docs/operacao/checklist-inauguracao.md revisado com o usuário e conclusão do M8 registrada no histórico de docs/roadmaps/README.md. Não implemente funcionalidade nova: problemas voltam à etapa de origem.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E13 para #em-andamento no índice e no título;
- investigação: um subagente Explore levanta, nos gates de M0 a M7, pendências que ainda dependem de ação do usuário;
- rode os comandos oficiais de build e teste do CLAUDE.md e a verificação do contrato numa cópia limpa, execute o cenário e registre a evidência de cada item;
- verificação independente com o Prompt do verificador, com foco em item sem evidência, cenário com adaptador falso, gate anterior não concluído e checklist sem itens legais.

Critérios de aceite (detalhes na seção E13):
- gates de M0 a M7 concluídos e definição global de pronto marcada com evidência;
- cenário ponta a ponta executado com fornecedores reais;
- checklist de inauguração escrito e revisado com o usuário; README dos marcos atualizado.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E13 (snapshot, verificações, resultados, parecer do verificador, notas para o marco M9) e responda com um resumo curto. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Os gates de M0 a M7 estão `#concluida` nos respectivos roadmaps.
- [ ] Todos os itens da "Definição global de pronto" estão marcados, cada um com evidência.
- [ ] O cenário ponta a ponta das Fases 0–2 foi executado com os fornecedores reais, sem erro e sem divergência na conciliação.
- [ ] `docs/operacao/checklist-inauguracao.md` cobre credenciais, Cadastur e FNRH, cadastros iniciais, impressoras, monitoramento, LGPD e contatos de suporte, e foi revisado com o usuário.
- [ ] `docs/roadmaps/README.md` registra a conclusão do M8 no histórico de revisões.

**Validação obrigatória:**

- Comandos oficiais de build, teste e verificação do contrato numa cópia limpa → sucesso.
- Cenário ponta a ponta → evidências registradas.

**Bloqueios:** Nenhum bloqueio conhecido. Esta etapa só fica disponível depois que E09 e E11 forem desbloqueadas e concluídas.

**Riscos e mitigação:**

- Pendência legal fora do sistema (Cadastur, credencial FNRH, cadastro na prefeitura): listada no checklist com responsável e prazo.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para o marco M9: —

---

## Histórico de revisões

- 2026-10-13 — roadmap criado.
- 2026-10-13 — aplicadas as decisões da entrevista SPEC × ARCHITECTURE (regra por camadas na ARCHITECTURE §3.1; `TabClosed` tratado por listener do próprio `pos`, agora registrado na ARCHITECTURE e na SPEC §8.3): restrições de grafo e alertas reescritas, H5 simplificada (`pos` → `rates` permitido), E01, E04 e E07 ajustados (critérios, escopo e prompt).
- 2026-10-14 — E11 recebe duas pendências do M1 E04, decididas pelo usuário: migrações num job de uso único com o dono dos schemas (a `api` deixa de receber `ZENY_DB_MIGRATION_*`) e a decisão entre trilha de auditoria e métrica para o login com usuário inexistente. Nenhuma tag mudou.
- 2026-10-15 — notas herdadas do M2 acrescentadas em "Hipóteses e decisões em aberto" (SSE pelo proxy com TLS e `ZENY_FCM_*` como segredos de implantação). Nenhuma etapa reescrita; tags inalteradas.
- 2026-10-20 — nota herdada do gate do M3 (E11, decisão do usuário): configurar a taxa de serviço padrão do hotel, que nasce em 0%, na implantação; acrescentada às notas herdadas e ao checklist de inauguração da E13. Tags inalteradas.
