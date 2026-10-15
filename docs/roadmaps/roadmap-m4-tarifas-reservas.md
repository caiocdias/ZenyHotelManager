# Roadmap — M4 Tarifas, disponibilidade e reservas

> Arquivo: `docs/roadmaps/roadmap-m4-tarifas-reservas.md` · criado em 2026-10-13 · última revisão 2026-10-15
>
> Marco 4 de 11 · mapa dos marcos em [`docs/roadmaps/README.md`](README.md) · marco anterior: [M3 — Hóspedes, contas e caixa](roadmap-m3-hospedes-contas.md) · próximo marco: [M5 — Recepção, FNRH e impressão](roadmap-m5-recepcao-fnrh.md)
>
> **Pré-requisito:** etapa E11 (gate) de `docs/roadmaps/roadmap-m3-hospedes-contas.md` marcada `#concluida`.

## Como usar

Duas formas, ambas na raiz do repositório:

- **Manual:** cole o prompt de uma etapa numa sessão limpa do Claude Code. Uma etapa por sessão.
- **Automática:** cole o prompt da seção "Execução automática" numa sessão limpa; ela executa até 3 etapas em sequência, um subagente por etapa mais um verificador, e para para revisão. Para não parar em cada pedido de permissão, libere antes os comandos de build/teste do projeto nas permissões do Claude Code (os comandos oficiais estão no `CLAUDE.md`, e o Docker é necessário para os testes de integração).

**Este roadmap foi escrito antes do código existir.** Antes da primeira etapa, sincronize-o com o código real usando a skill `development-roadmap` (modo atualização): confira caminhos, comandos, eventos publicados por `rooms`, API pública de `billing` e `guests` e convenções registrados nos handoffs de M0 a M3, e ajuste escopo, prompts e critérios das etapas afetadas.

Etapas marcadas como paralelizáveis só devem rodar ao mesmo tempo em worktrees git separados; na mesma pasta, rode-as uma de cada vez.

Em ambas, quem executa segue o "Protocolo de execução", atualiza a tag da etapa e preenche "Evidências e handoff". Não pule dependências. Revise e faça commit entre etapas ou lotes — nenhuma sessão faz commit por conta própria.

## Objetivo

Entregar os módulos `rates` e `reservations`. Ao fim do marco, a API permite manter planos tarifários com derivação, preços por data e ocupação, restrições, pacotes e políticas; consultar disponibilidade e cotações; criar, alterar, cancelar e reativar reservas com preço congelado por noite, sem overbooking mesmo sob concorrência; manter pré-reservas com expiração automática; alocar UHs sem sobreposição garantida pelo banco; e operar grupos e bloqueios com pick-up. Check-in e check-out (M5), no-show e devolução de bloqueios no fechamento (M6), PDF de confirmação (M5) e channel manager (M8) usam o que este marco entrega.

## Contexto confirmado

- Requisitos: SPEC §5.6 (TAR) e §5.7 (RES); regras RN-04, RN-07, RN-12, RN-14, RN-15, RN-16 e RN-23 (SPEC §6); RNF-06 (p95 < 300 ms).
- Modelo de domínio: SPEC §8.2.5 (`RatePlan`, `DerivationRule`, `RatePrice`, `Restriction`, `Package`, `CancellationPolicy`, `GuaranteePolicy`, `Season`) e §8.2.6 (`Booking`, `Reservation`, `ReservationStatus`, `StayGuest`, `NightlyRate`, `RoomAssignment`, `RoomBlock`, `BlockAllocation`, `Trace`). Máquina de estados da reserva: SPEC §8.4. Eventos: `ReservationCreated`/`Changed`/`Cancelled`, `AvailabilityChanged`, `RatesChanged`/`RestrictionsChanged` e o consumo de `OutOfOrderChanged` (SPEC §8.3).
- Módulos e dependências permitidas (ARCHITECTURE §3.1): `rates` → `shared`, `rooms`, `billing`; `reservations` → `shared`, `hotel`, `rooms`, `rates`, `guests`, `billing`, `printing`. Pela regra por camadas da §3.1, módulos da Plataforma (`notifications`, `printing`, `files`, `hotel`) podem ser chamados sem aprovação, acrescentando a aresta à tabela; `reservations` não chama `frontdesk`, `distribution` nem `endofday` (camadas acima).
- Garantias no banco (ARCHITECTURE §11): `EXCLUDE USING gist (room_id WITH =, stay WITH &&)` sobre alocações ativas (`btree_gist` instalado em M0); contadores `room_type_inventory(room_type_id, date, total, sold, blocked, out_of_order)` atualizados na mesma transação com `SELECT … FOR UPDATE`; `daterange` para estadias. Concorrência: ARCHITECTURE §5 (lock otimista com `ETag`, lock pessimista curto nos contadores). Jobs: ARCHITECTURE §13 (expiração de pré-reservas a cada 5 min, com ShedLock).
- Limites e step-up: ARCHITECTURE §8.2 (ex.: `rates.discount.maxPercent`) e §6.3. Ações sensíveis deste marco (RF-ACS-08): desconto além do limite, isentar multa de cancelamento, alterar tarifa publicada; reativar reserva cancelada ou no-show (SPEC §8.4).
- SPEC Q-09: overbooking proibido por padrão. Q-15: cortesia conta na ocupação e não no ADR; uso da casa não conta em nenhum dos dois.
- Convenções herdadas, documentadas no `CLAUDE.md`: comandos oficiais, estrutura de módulo, migrações, erros, paginação, `Money`, IDs, números legíveis, idempotência e jobs com ShedLock (M0); `authz.require`, limites, step-up, auditoria, data operacional e configuração do hotel (M1); `rooms` com status e OOO/OOS e eventos (M2); API pública de `guests` (busca, lista de restrição, `GuestsMerged`) e de `billing` (`openFolio`, `post`, códigos padrão de multa e diária) (M3).
- Pacote base `com.zeny.hotel` (hipótese herdada de M0). Caminhos de código citados abaixo são "(a criar)".

## Escopo

**Incluído:**

- `rates`: planos tarifários com regime de alimentação, canais, tipos de UH, categoria (normal, cortesia, uso da casa); políticas de cancelamento e garantia; pacotes ligados a códigos de lançamento; preços por tipo × data × ocupação; derivação; edição em massa e temporadas; restrições; API de preço e vendabilidade; eventos `RatesChanged` e `RestrictionsChanged`.
- `reservations`: inventário por tipo e data, disponibilidade e calendário, cotação; reservas e reservas agrupadoras com preço congelado e política vigente; abertura da conta da reserva em `billing`; alteração, cancelamento com multa ou isenção, reativação; pré-reserva, garantia manual e expiração; alocação de UH com sugestão e "não mover"; consumo de `OutOfOrderChanged`; grupos, bloqueios, rooming list e pick-up; busca, listas operacionais e views `report_*`; eventos de reserva e de disponibilidade.

**Fora de escopo:**

- Check-in, check-out, desfazer check-in, troca de UH de hóspede em casa e walk-in (M5, `frontdesk`).
- No-show automático e devolução de bloqueios vencidos no fechamento (M6, `endofday`); aqui só a API e a regra.
- Confirmação de reserva em PDF ou texto (RF-RES-12): M5. `reservations` monta os dados e chama `printing` (dono + printing).
- Garantia por cartão tokenizado e sinal por Pix ou link (RF-RES-08, parte integrada): M7.
- Reservas do channel manager e envio de ARI (RF-RES-10, RF-TAR-10): M8; aqui só os eventos.
- Alerta por push de reservas sem garantia: a SPEC (RF-RES-08) pede alerta, não push, e RF-NOT-01 não lista esse tipo; fica a lista consultável aqui e o painel em M6. Se o hotel quiser push, `reservations` pode chamar `notifications` (regra por camadas).
- Eventos e locação de espaços (M10).

## Restrições e invariantes

- Respeitar o grafo de módulos da ARCHITECTURE §3.1 e a regra por camadas. Reações de módulos abaixo (`rooms`) chegam por evento; reações de módulos acima (`frontdesk`, `distribution`, `endofday`) escutam os eventos deste marco.
- RN-04: UH em dobro é impossível no banco, não só na aplicação.
- Sem overbooking (Q-09): o limite configurável por tipo e data nasce zero; os contadores são a fonte da verdade e só mudam com lock na mesma transação da reserva, bloqueio ou OOO.
- RN-14: o preço é congelado por noite na criação ou alteração; mudar a tarifa depois não altera reservas existentes. RN-15: a política vigente no momento da reserva é guardada com a reserva.
- RN-02: valores só com `Money`; derivações e totais usam o arredondamento único de `shared`.
- RN-13 não se aplica aqui, mas nenhuma view `report_*` expõe documento ou dado sensível de hóspede.
- Toda escrita auditada; `Idempotency-Key` nos POSTs que lançam multa ou criam reserva; `ETag`/`If-Match` nas alterações.
- Contrato `api/openapi.yaml` regenerado e versionado a cada etapa que mexe na API.

## Hipóteses e decisões em aberto

| # | Hipótese ou decisão | Impacto se errada / sem decisão | Etapa afetada |
|---|---|---|---|
| H1 | Preços de planos derivados são calculados na leitura a partir do plano base, não materializados; `RatesChanged` informa também os planos derivados afetados. | Se o desempenho da cotação não atender RNF-06, materializar numa etapa futura sem mudar a API. | E02, E04 |
| H2 | Faixas etárias de criança vêm da configuração do `hotel` (RF-CFG-02, M1). | Se M1 não as expôs, a etapa para e relata. | E04 |
| H3 | O inventário é mantido para um horizonte de 730 dias; um job diário com ShedLock estende o horizonte. | Reservas além do horizonte são recusadas com `code` estável até o job estender. | E05 |
| H4 | `rooms` (M2) publica eventos de criação, ativação, desativação e troca de tipo de UH. Se não publicar, E05 acrescenta a publicação mínima em `rooms` (módulo abaixo, sem quebrar contrato) e registra a exceção no handoff. | O total do inventário ficaria desatualizado ao cadastrar UHs. | E05 |
| H5 | Day use (RF-RES-14) não consome o contador de pernoite; a alocação de day use usa um intervalo próprio que só conflita com hóspede em casa na mesma UH naquele dia. A etapa registra a regra escolhida. | Revisão da regra é local à alocação. | E07, E10 |
| H6 | A multa de cancelamento é lançada na conta da reserva com o código padrão de multa criado em M3; a devolução de depósito segue o reembolso manual de M3. | Se os códigos padrão de M3 tiverem outros nomes, ajustar na sincronização. | E08 |
| H7 | A chave de limite de desconto é `rates.discount.maxPercent` (ARCHITECTURE §8.2), registrada no catálogo de permissões de `rates`. | Nome diferente só muda a configuração das roles. | E02, E07 |
| H8 | Reservas guardam referências a hóspedes e escutam `GuestsMerged` (M3) para repontar o hóspede absorvido. | Sem isso, reservas antigas apontariam para perfis mesclados. | E07 |

**Notas herdadas do M2** (sincronização do roadmap do M2 com o código do M1, 2026-10-15; conferir de novo na sincronização deste marco):

- **E05, listener de `OutOfOrderChanged`:** é o primeiro listener de negócio entre módulos do sistema. Ele conta com o reenvio do outbox da M2 E12: backoff de 1 min dobrando até 1 h, abandono depois de 24 h com métrica `abandoned`, alerta e reenvio manual documentado no `CLAUDE.md`. O listener precisa ser idempotente, e a recontagem do inventário cobre uma publicação abandonada.

## Definição global de pronto

- [ ] Cotação para um período, ocupação e canal devolve tipos e planos vendáveis com total e detalhe por noite, respeitando restrições, derivação e pacotes.
- [ ] Teste concorrente: N reservas simultâneas para o último quarto disponível de um tipo resultam em exatamente uma criada e as demais recusadas com `code` estável.
- [ ] Inserir duas alocações sobrepostas da mesma UH falha no banco mesmo contornando a aplicação (teste de integração com SQL direto).
- [ ] Mudar a tarifa depois de criar uma reserva não altera o preço por noite dela.
- [ ] Pré-reserva vencida é cancelada pelo job e devolve o inventário.
- [ ] OOO de uma UH reduz a disponibilidade do tipo nas datas e sinaliza reservas alocadas no período.
- [ ] Bloqueio de grupo consome inventário e o pick-up transforma unidades do bloqueio em reservas sem consumir duas vezes.
- [ ] Desconto acima do limite, isenção de multa, alteração de tarifa publicada e reativação exigem step-up e ficam auditados.
- [ ] `ApplicationModules.verify()` passa; contrato atualizado; build, testes e lint oficiais passam.

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
Você é o verificador independente da etapa ETAPA do roadmap docs/roadmaps/roadmap-m4-tarifas-reservas.md. Você não participou da implementação e não deve editar nenhum arquivo.
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
Você é o orquestrador do roadmap docs/roadmaps/roadmap-m4-tarifas-reservas.md neste repositório. Seu papel é coordenar subagentes, não implementar: não edite código e mantenha seu contexto enxuto (leia o roadmap e os relatórios dos subagentes, não o código).

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

**Tarifas (`rates`)**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E01 | Planos tarifários, políticas e pacotes | #pendente | — | Módulo `rates` com planos, políticas de cancelamento e garantia e pacotes |
| E02 | Preços por data e ocupação, derivação e edição em massa | #pendente | E01 | `RatePrice`, planos derivados, temporadas e alteração de tarifa publicada com step-up |
| E03 | Restrições de venda | #pendente | E01 | Fechado, CTA, CTD, estadia e antecedência mín./máx. por data, plano e tipo |
| E04 | Cálculo de preço e vendabilidade | #pendente | E02, E03 | API pública `priceFor` e `isSellable` com pacotes e faixas etárias |

**Disponibilidade e reservas (`reservations`)**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E05 | Inventário por tipo e data | #pendente | — | `room_type_inventory` com lock, horizonte, OOO e `AvailabilityChanged` |
| E06 | Disponibilidade, calendário e cotação | #pendente | E04, E05 | Calendário com preços e restrições; cotação por período, ocupação e canal |
| E07 | Criação de reservas | #pendente | E06 | `Booking` e `Reservation` com preço congelado, política vigente, conta em `billing` e `ReservationCreated` |
| E08 | Alteração, cancelamento e reativação | #pendente | E07 | Recálculo e histórico; multa ou isenção; reativação sensível |
| E09 | Pré-reserva, garantia e expiração | #pendente | E07 | Garantia manual, confirmação e job de expiração |
| E10 | Alocação de UH | #pendente | E07 | Alocação com `EXCLUDE`, sugestão, "não mover" e OOS como última opção |
| E11 | Grupos e bloqueios | #pendente | E07 | `RoomBlock`, rooming list, pick-up, conta master e regra de data de corte |
| E12 | Busca, listas operacionais e views de leitura | #pendente | E08, E09, E10, E11 | Busca de reservas, chegadas, saídas, hospedados, no-shows previstos e views `report_*` |
| E13 | Gate do marco M4 | #pendente | E12 | Definição global de pronto verificada do zero |

---

## E01 — Planos tarifários, políticas e pacotes — #pendente

**Objetivo:** criar o módulo `rates` com planos tarifários (código, nome, descrição, regime de alimentação, canais, tipos de UH, categoria normal/cortesia/uso da casa, ativo), políticas de cancelamento e de garantia e pacotes ligados a códigos de lançamento (RF-TAR-01, RF-TAR-06, RF-TAR-07).

**Por que agora:** é a base de preço, restrição, cotação e reserva. A categoria do plano alimenta a regra de Q-15 nos indicadores de M6/M10.

**Dependências e paralelismo:** nenhuma dentro deste roadmap; exige o gate de M3 `#concluida`. Pode correr em paralelo com E05 (em worktree separado); conflito de edição em `api/openapi.yaml`.

**Escopo:**

- Pacote `rates` (a criar), schema e pasta de migração próprios, API pública na raiz.
- `RatePlan` com tipos de UH validados pela API pública de `rooms` e canais como conjunto de códigos.
- `CancellationPolicy` (prazo em horas, multa em % da estadia, nº de diárias ou valor) e `GuaranteePolicy` (nenhuma, cartão, depósito de X% até N dias antes).
- `Package` com preço por noite, por estadia ou por pessoa/noite, ritmo (diário ou na chegada), incluso ou adicional e código de lançamento validado pela API pública de `billing`.
- Endpoints em `/api/v1/rate-plans`, `/api/v1/cancellation-policies`, `/api/v1/guarantee-policies`, `/api/v1/packages`; permissões no catálogo de `rates` (incluindo a chave de limite de desconto, H7) e auditoria.

**Fora de escopo:** preços e derivação (E02); restrições (E03).

**Passos de implementação:**

1. Confirmar no `CLAUDE.md` a API pública de `rooms` (tipos de UH) e de `billing` (códigos de lançamento).
2. Testes de integração: CRUD com `ETag`, tipo de UH inexistente, código de lançamento inexistente, permissões.
3. Implementar migração, domínio, casos de uso, controllers e permissões; regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "o que SPEC §5.6 (RF-TAR-01, 06, 07), §8.2.5, RN-11 e RN-15 e Q-15 definem para plano, políticas e pacotes?".
- Partes paralelas: nenhuma.
- Foco do verificador: `rates` acessando tabelas de `rooms` ou `billing` em vez da API pública; política editada afetando o passado (a política deve ser versionada ou copiada na reserva, ver E07); pacote sem código de lançamento.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E01 — "Planos tarifários, políticas e pacotes" do roadmap docs/roadmaps/roadmap-m4-tarifas-reservas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Como usar", restrições, hipóteses e o "Protocolo de execução" —, a seção E01 inteira e docs/SPEC.md §5.6 e §8.2.5. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que a etapa E11 (gate) de docs/roadmaps/roadmap-m3-hospedes-contas.md está #concluida; se não estiver, ou se o código divergiu do que este roadmap assume (API pública de rooms e billing e convenções no CLAUDE.md), pare e relate sem implementar.

Objetivo: criar o módulo rates com planos tarifários (regime de alimentação, canais, tipos de UH, categoria normal/cortesia/uso da casa, ativo), políticas de cancelamento e de garantia e pacotes ligados a códigos de lançamento, com endpoints em /api/v1/rate-plans, /api/v1/cancellation-policies, /api/v1/guarantee-policies e /api/v1/packages, permissões (incluindo a chave de limite de desconto) e auditoria. Use só a API pública de rooms e billing. Não implemente preços, derivação nem restrições.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E01 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume SPEC RF-TAR-01, 06 e 07, §8.2.5, RN-11, RN-15 e Q-15;
- escreva os testes de integração primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em acesso a tabelas de outros módulos, pacote sem código de lançamento e escrita sem auditoria.

Critérios de aceite (detalhes na seção E01):
- CRUD dos quatro recursos com ETag coberto por testes de integração;
- tipo de UH ou código de lançamento inexistente → 422 com code estável;
- escrita sem permissão → 403; toda escrita auditada;
- ApplicationModules.verify() passa; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E01 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] CRUD de planos, políticas e pacotes com `ETag`/`If-Match` coberto por testes de integração com Testcontainers.
- [ ] Plano com tipo de UH inexistente ou pacote com código de lançamento inexistente → `422` com `code` estável.
- [ ] Escrita sem permissão → `403`; toda escrita auditada.
- [ ] Chave de limite de desconto registrada no catálogo de permissões.
- [ ] `ApplicationModules.verify()` passa (`rates` só depende de `shared`, `rooms`, `billing`); contrato regenerado; build, testes e lint oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint (`CLAUDE.md`) → sucesso.
- Tarefa de verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Edição de política usada por reservas existentes: a reserva copia a política vigente (E07, RN-15); registrar no handoff.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E02 — Preços por data e ocupação, derivação e edição em massa — #pendente

**Objetivo:** manter preços por tipo de UH × data × ocupação (1 adulto, 2 adultos, adulto extra, criança por faixa), planos derivados por percentual ou valor (por UH ou por pessoa), edição em massa por período e dia da semana com temporadas nomeadas, e alteração de tarifa publicada como ação sensível, publicando `RatesChanged` (RF-TAR-02, 03, 04; RF-ACS-08).

**Por que agora:** cotação e reservas dependem do preço; a regra de derivação e o arredondamento definidos aqui se repetem em todo cálculo de diária.

**Dependências e paralelismo:** E01. Pode correr em paralelo com E03 (em worktree separado); conflito de edição no agregado `RatePlan` e em `api/openapi.yaml` — preferir executar em sequência.

**Escopo:**

- `RatePrice` por plano, tipo de UH e data, com os quatro valores em `Money`; criança por faixa etária (faixas do `hotel`, H2).
- `DerivationRule` (percentual ou valor, por UH ou por pessoa) com plano base; cálculo na leitura (H1); recusar derivação em ciclo.
- `Season` como atalho de período para edição em massa; edição em massa por período e dias da semana.
- "Tarifa publicada": preço de data já vendável; alterar exige permissão ou step-up, auditado.
- Evento `RatesChanged` com plano, tipos e intervalo de datas, incluindo planos derivados afetados.

**Fora de escopo:** restrições (E03); cálculo de estadia com pacotes (E04).

**Passos de implementação:**

1. Testes de domínio (derivação percentual e por pessoa, arredondamento, ciclo) e de integração (edição em massa por dia da semana, step-up, evento).
2. Implementar migração, domínio, casos de uso e endpoints; regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação: nenhuma; regras em RF-TAR-02 a 04, H1, H2 e no handoff de E01.
- Partes paralelas: nenhuma.
- Foco do verificador: arredondamento fora de `Money`; derivação em ciclo aceita; edição em massa que ignora o filtro de dia da semana; alteração de tarifa publicada sem step-up; evento sem os planos derivados.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E02 — "Preços por data e ocupação, derivação e edição em massa" do roadmap docs/roadmaps/roadmap-m4-tarifas-reservas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo as hipóteses H1, H2 e H7 e o "Protocolo de execução" —, a seção E02 inteira, o "Evidências e handoff" de E01 e RF-TAR-02 a RF-TAR-04 em docs/SPEC.md §5.6. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: em rates, manter preços por tipo de UH, data e ocupação (criança por faixa etária do hotel), planos derivados por percentual ou valor (por UH ou por pessoa) calculados na leitura, temporadas e edição em massa por período e dia da semana, e tratar a alteração de tarifa publicada como ação sensível com step-up, publicando RatesChanged com os planos derivados afetados. Não implemente restrições nem cálculo de estadia.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E02 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de domínio e de integração primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em arredondamento fora de Money, derivação em ciclo, filtro de dia da semana ignorado, tarifa publicada sem step-up e evento incompleto.

Critérios de aceite (detalhes na seção E02):
- preço derivado acompanha a mudança do plano base sem gravação adicional;
- derivação em ciclo → 422 com code estável;
- edição em massa respeita período e dias da semana;
- alterar tarifa publicada sem aprovação → 403 APPROVAL_REQUIRED; RatesChanged no outbox com derivados; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E02 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Preço de plano derivado (−10%, + R$ 40/pessoa) calculado corretamente e acompanha a mudança do plano base sem gravação no derivado (testes de domínio e integração).
- [ ] Derivação que formaria ciclo → `422` com `code` estável.
- [ ] Edição em massa por período e dias da semana altera só as datas filtradas; temporada aplicada como atalho.
- [ ] Alterar tarifa publicada sem permissão nem aprovação → `403` com `APPROVAL_REQUIRED`; com aprovação, auditada com solicitante e aprovador.
- [ ] `RatesChanged` registrado no outbox com plano, tipos, intervalo e planos derivados afetados.
- [ ] Contrato regenerado; build, testes e lint oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Tarefa de verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Volume de linhas de preço (planos × tipos × 730 dias): edição em massa em lote único com SQL e teste de desempenho simples.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E03 — Restrições de venda — #pendente

**Objetivo:** manter restrições por data × plano × tipo de UH (fechado para venda, CTA, CTD, estadia mínima e máxima, antecedência mínima e máxima), com edição em massa e evento `RestrictionsChanged` (RF-TAR-05).

**Por que agora:** a vendabilidade (E04) e a cotação (E06) dependem das restrições.

**Dependências e paralelismo:** E01. Pode correr em paralelo com E02 (em worktree separado); conflito de edição no agregado `RatePlan` e em `api/openapi.yaml` — preferir executar em sequência.

**Escopo:**

- `Restriction` por plano, data e tipo de UH opcional (vale para todos os tipos do plano quando ausente), com precedência documentada entre a restrição específica do tipo e a geral.
- Edição em massa por período e dias da semana.
- Evento `RestrictionsChanged` com plano, tipos e intervalo.
- Permissões e auditoria.

**Fora de escopo:** avaliação de uma estadia contra as restrições (E04).

**Passos de implementação:**

1. Testes de integração: gravação, precedência tipo × geral, edição em massa, evento.
2. Implementar migração, domínio, casos de uso e endpoints; regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regra em RF-TAR-05.
- Partes paralelas: nenhuma.
- Foco do verificador: precedência ambígua entre restrição por tipo e geral; valores inválidos aceitos (mínimo maior que máximo); evento ausente na edição em massa.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E03 — "Restrições de venda" do roadmap docs/roadmaps/roadmap-m4-tarifas-reservas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E03 inteira, o "Evidências e handoff" de E01 e RF-TAR-05 em docs/SPEC.md §5.6. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: em rates, manter restrições por data, plano e tipo de UH opcional (fechado, CTA, CTD, estadia mínima e máxima, antecedência mínima e máxima), com precedência documentada entre restrição por tipo e geral, edição em massa por período e dias da semana e evento RestrictionsChanged. Não implemente a avaliação de uma estadia (E04).

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E03 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em precedência ambígua, valores inválidos aceitos e evento ausente na edição em massa.

Critérios de aceite (detalhes na seção E03):
- restrição por tipo prevalece sobre a geral do plano (teste);
- mínimo maior que máximo → 400 com code estável;
- edição em massa respeita período e dias da semana e publica RestrictionsChanged;
- build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E03 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Restrições gravadas e lidas por plano, data e tipo, com a precedência tipo > geral coberta por teste.
- [ ] Mínimo maior que máximo (estadia ou antecedência) → `400` com `code` estável.
- [ ] Edição em massa altera só as datas e dias da semana filtrados.
- [ ] `RestrictionsChanged` registrado no outbox em gravação individual e em massa.
- [ ] Escritas auditadas; contrato regenerado; build, testes e lint oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Tarefa de verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Semântica de CTA/CTD e estadia mínima difere entre channel managers: documentar a semântica escolhida para M8 mapear.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E04 — Cálculo de preço e vendabilidade — #pendente

**Objetivo:** expor na API pública de `rates` o cálculo do preço de uma estadia (`priceFor` por noite, para tipo, plano, ocupação e idades das crianças, com pacotes inclusos e adicionais) e a vendabilidade (`isSellable` para tipo, estadia, canal e data da consulta, avaliando restrições, canal, plano ativo e antecedência).

**Por que agora:** cotação (E06) e reservas (E07) usam esse cálculo; centralizá-lo evita preços divergentes entre recepção e channel manager.

**Dependências e paralelismo:** E02, E03. Pode correr em paralelo com E05 (em worktree separado); sem conflito de arquivos além de `CLAUDE.md`.

**Escopo:**

- `priceFor`: detalhe por noite (diária, adultos extras, crianças por faixa, pacotes por noite, por estadia ou por pessoa/noite), total e separação de componentes inclusos quando a classificação exigir (RF-TAR-06), tudo em `Money`.
- `isSellable`: plano ativo, canal, tipo no plano, fechado, CTA na chegada, CTD na saída, estadia mínima e máxima, antecedência; motivos de recusa com `code` estável.
- Ocupação acima da máxima do tipo de UH (API pública de `rooms`) recusada.
- Documentação da API no `CLAUDE.md`.

**Fora de escopo:** disponibilidade (E05) e endpoint de cotação (E06).

**Passos de implementação:**

1. Testes de domínio com tabela de casos: derivado + pacote por pessoa/noite + criança por faixa; cada restrição isolada; antecedência; ocupação máxima.
2. Implementar o serviço e a API pública.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "como os componentes de pacote (incluso/adicional, ritmo, base de preço) entram no preço segundo SPEC RF-TAR-06, RF-FIS-04 e §8.2.5?".
- Partes paralelas: nenhuma.
- Foco do verificador: CTA avaliado na data errada (chegada) ou CTD na data errada (saída); estadia mínima contada em noites erradas; pacote por estadia cobrado por noite; soma das noites diferente do total.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E04 — "Cálculo de preço e vendabilidade" do roadmap docs/roadmaps/roadmap-m4-tarifas-reservas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo as hipóteses H1 e H2 e o "Protocolo de execução" —, a seção E04 inteira e o "Evidências e handoff" de E01, E02 e E03. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E02 e E03 estão #concluida e que o código bate com o handoff delas; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: expor na API pública de rates priceFor (detalhe por noite e total, com adultos extras, crianças por faixa etária do hotel e pacotes inclusos e adicionais, em Money) e isSellable (plano ativo, canal, tipo no plano, fechado, CTA, CTD, estadia mínima e máxima, antecedência, ocupação máxima do tipo de UH), com motivos de recusa em code estável. Não implemente disponibilidade nem o endpoint de cotação.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E04 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume como pacotes entram no preço segundo SPEC RF-TAR-06, RF-FIS-04 e §8.2.5;
- escreva os testes de domínio em tabela de casos primeiro, implemente e rode os comandos oficiais de build, teste e lint do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em CTA/CTD na data errada, estadia mínima mal contada, pacote por estadia cobrado por noite e total diferente da soma das noites.

Critérios de aceite (detalhes na seção E04):
- priceFor devolve detalhe por noite cuja soma é igual ao total, em todos os casos de teste;
- isSellable recusa cada restrição isoladamente com o code correspondente;
- ocupação acima da máxima do tipo recusada;
- API documentada no CLAUDE.md; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E04 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] `priceFor` devolve detalhe por noite e total, e a soma das noites é igual ao total em todos os casos de teste (incluindo derivado, pacote por pessoa/noite, pacote por estadia e criança por faixa).
- [ ] `isSellable` recusa, com `code` próprio, plano inativo, canal não permitido, tipo fora do plano, data fechada, CTA na chegada, CTD na saída, estadia abaixo do mínimo ou acima do máximo e antecedência fora da faixa (um caso de teste para cada).
- [ ] Ocupação acima da máxima do tipo de UH é recusada.
- [ ] API pública documentada no `CLAUDE.md`; build, testes e lint oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Desempenho (RNF-06) com derivação na leitura: carregar preços do intervalo numa consulta; medir no teste da cotação (E06).

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E05 — Inventário por tipo e data — #pendente

**Objetivo:** criar o módulo `reservations` com os contadores `room_type_inventory(room_type_id, date, total, sold, blocked, out_of_order)`, atualizados só com lock pessimista curto, mantidos num horizonte de datas, recalculados a partir de `rooms` e reagindo a `OutOfOrderChanged`, com limite de overbooking configurável nascido zero e evento `AvailabilityChanged` (RF-TAR-08, RF-TAR-09, RN-07, ARCHITECTURE §11).

**Por que agora:** é o ponto de concorrência mais crítico do sistema: reservas da recepção e do channel manager disputam os mesmos contadores. Precisa estar correto antes de qualquer reserva.

**Dependências e paralelismo:** nenhuma dentro deste roadmap; exige o gate de M3 `#concluida`. Pode correr em paralelo com E01 a E04 (em worktree separado); conflito de edição em `api/openapi.yaml` e `CLAUDE.md`.

**Escopo:**

- Pacote `reservations` (a criar), schema e pasta de migração próprios.
- Tabela de inventário com chave (tipo, data); `total` derivado das UHs ativas do tipo (API pública de `rooms`); `out_of_order` a partir dos períodos OOO; limite de overbooking por tipo e data, padrão zero (Q-09).
- Serviço interno de reserva de inventário: trava as datas envolvidas com `SELECT … FOR UPDATE` em ordem determinística, confere `total − sold − blocked − out_of_order + limite ≥ pedido` e atualiza na mesma transação do chamador; operações de liberar.
- Horizonte (H3) com job diário com ShedLock que cria as datas faltantes.
- Listener de `OutOfOrderChanged`: recalcula `out_of_order` das datas e marca reservas alocadas no período para realocação (a marcação é efetivada em E10; aqui o listener e o recálculo).
- Listener de eventos de cadastro de UH de `rooms` (H4) para recalcular `total`.
- Evento `AvailabilityChanged` (tipo, intervalo) publicado em toda mudança de contador.

**Fora de escopo:** reservas (E07); bloqueios (E11); consulta de calendário (E06).

**Passos de implementação:**

1. Conferir no handoff de M2 os eventos de `rooms` (H4).
2. Testes de integração: concorrência (N threads disputando a última unidade), ordem de locks sem deadlock em intervalos sobrepostos, OOO reduz disponibilidade, horizonte estendido pelo job, cadastro de UH atualiza total.
3. Implementar migração, serviço, listeners, job e evento.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "quais eventos o módulo rooms publica e com que payload (handoffs de M2 e API pública de rooms)?"; "o que ARCHITECTURE §5, §11 e §13 e SPEC RF-TAR-08, RF-TAR-09, RN-07 e §8.3 exigem dos contadores e dos eventos?".
- Partes paralelas: nenhuma.
- Foco do verificador: corrida que permite vender acima do disponível; deadlock entre reservas com intervalos sobrepostos; contador atualizado fora da transação do chamador; `AvailabilityChanged` ausente em alguma mudança; listener de OOO não idempotente.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E05 — "Inventário por tipo e data" do roadmap docs/roadmaps/roadmap-m4-tarifas-reservas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo restrições, as hipóteses H3 e H4 e o "Protocolo de execução" —, a seção E05 inteira e docs/ARCHITECTURE.md §5, §11 e §13. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que a etapa E11 (gate) de docs/roadmaps/roadmap-m3-hospedes-contas.md está #concluida e que o código bate com o que este roadmap assume (eventos de rooms, jobs com ShedLock); se não, pare e relate sem implementar.

Objetivo: criar o módulo reservations com os contadores room_type_inventory por tipo e data (total a partir das UHs ativas de rooms, sold, blocked, out_of_order, limite de overbooking nascido zero), um serviço que trava as datas com SELECT FOR UPDATE em ordem determinística na transação do chamador, job de horizonte com ShedLock, listeners de OutOfOrderChanged e de cadastro de UH e o evento AvailabilityChanged. Se rooms não publicar eventos de cadastro de UH, acrescente a publicação mínima em rooms sem quebrar contrato e registre a exceção no handoff. Não implemente reservas, bloqueios nem calendário.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E05 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume os eventos publicados por rooms (handoffs de M2) e as exigências de ARCHITECTURE §5, §11 e §13 e SPEC RF-TAR-08, RF-TAR-09, RN-07 e §8.3;
- escreva os testes de integração primeiro, incluindo o teste concorrente, implemente e rode os comandos oficiais de build, teste e lint do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em venda acima do disponível sob concorrência, deadlock, atualização fora da transação e evento ausente.

Critérios de aceite (detalhes na seção E05):
- N transações simultâneas pela última unidade: exatamente uma consegue (teste concorrente com Testcontainers);
- intervalos sobrepostos em ordens diferentes não geram deadlock;
- OOO reduz a disponibilidade nas datas e publica AvailabilityChanged; listener idempotente;
- job estende o horizonte; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E05 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Teste concorrente com PostgreSQL real: N transações simultâneas disputando a última unidade de um tipo numa data → exatamente uma reserva o inventário; as demais recebem erro com `code` estável.
- [ ] Duas transações com intervalos sobrepostos travando em ordens diferentes terminam sem deadlock (teste).
- [ ] `OutOfOrderChanged` reduz `out_of_order` nas datas do período e publica `AvailabilityChanged`; reprocessar o mesmo evento não altera o resultado.
- [ ] Criar ou desativar UH atualiza `total` do tipo (teste), por evento de `rooms`.
- [ ] Job de horizonte cria as datas faltantes e é protegido por ShedLock.
- [ ] Limite de overbooking nasce zero e, quando configurado, é respeitado (teste).
- [ ] `ApplicationModules.verify()` passa; build, testes e lint oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Contador divergir da realidade após bug: incluir uma rotina de recontagem a partir das reservas e OOO (chamável por teste e, depois, pelo fechamento em M6); registrar no handoff.

**Rollback / migração:** tabelas novas; a recontagem permite reconstruir os contadores se a migração precisar ser refeita.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E06 — Disponibilidade, calendário e cotação — #pendente

**Objetivo:** expor a consulta de disponibilidade em calendário (por tipo e data, com preços e restrições) e a cotação (período, ocupação e canal → tipos e planos disponíveis com total e detalhe por noite) (RF-TAR-08, RF-TAR-11).

**Por que agora:** a recepção cota antes de reservar, e a criação de reserva (E07) usa a mesma verificação.

**Dependências e paralelismo:** E04, E05. Não paralelizável.

**Escopo:**

- `GET /api/v1/availability` (calendário por intervalo e tipos) e `GET /api/v1/quotes` (cotação) em `reservations`, combinando inventário (E05) com `priceFor` e `isSellable` de `rates` (E04).
- Cotação lista só combinações vendáveis e disponíveis; opcionalmente indica o motivo das indisponíveis.
- Medição de desempenho: cotação de 7 noites para todos os tipos e planos dentro de RNF-06 num teste com dados de exemplo (30 UHs, 730 dias).

**Fora de escopo:** criação de reserva (E07).

**Passos de implementação:**

1. Testes de integração: calendário reflete OOO e vendas; cotação respeita restrição, canal e disponibilidade; soma das noites igual ao total.
2. Implementar consultas e endpoints; regenerar o contrato.
3. Medir a cotação e registrar o resultado no handoff.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em RF-TAR-08, RF-TAR-11 e nos handoffs de E04 e E05.
- Partes paralelas: nenhuma.
- Foco do verificador: cotação oferecendo tipo sem disponibilidade ou plano não vendável no canal; calendário ignorando OOO; consultas N+1 que estouram RNF-06.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E06 — "Disponibilidade, calendário e cotação" do roadmap docs/roadmaps/roadmap-m4-tarifas-reservas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E06 inteira, o "Evidências e handoff" de E04 e E05 e RF-TAR-08 e RF-TAR-11 em docs/SPEC.md §5.6. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E04 e E05 estão #concluida e que o código bate com o handoff delas; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: em reservations, expor GET /api/v1/availability (calendário por intervalo e tipos, com disponibilidade, preços e restrições) e GET /api/v1/quotes (período, ocupação e canal → tipos e planos vendáveis e disponíveis com total e detalhe por noite), usando o inventário de E05 e a API pública de rates. Meça a cotação de 7 noites com 30 UHs e 730 dias de dados. Não implemente criação de reserva.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E06 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de integração primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em oferta sem disponibilidade ou fora do canal, OOO ignorado e consultas N+1.

Critérios de aceite (detalhes na seção E06):
- calendário reflete vendas e OOO;
- cotação não oferece tipo sem disponibilidade nem plano não vendável no canal, e a soma das noites é igual ao total;
- tempo da cotação medido e registrado no handoff, dentro de RNF-06;
- build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E06 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Calendário devolve, por tipo e data, disponibilidade, preço do plano pedido e restrições; reflete vendas e OOO (teste de integração).
- [ ] Cotação não oferece tipo sem disponibilidade nem plano não vendável no canal ou com restrição violada (testes).
- [ ] Soma das noites igual ao total em todas as ofertas.
- [ ] Cotação de 7 noites, 30 UHs e 730 dias de dados abaixo de 300 ms no teste local (medição registrada no handoff).
- [ ] Contrato regenerado; build, testes e lint oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Tarefa de verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Se a medição estourar RNF-06, registrar e propor materialização de preços derivados (H1) como etapa nova, sem bloquear esta.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E07 — Criação de reservas — #pendente

**Objetivo:** criar reservas e reservas agrupadoras (uma reserva por UH sob o mesmo titular e canal), com hóspede principal e acompanhantes, origem, canal, segmento e pedidos especiais; preço congelado por noite (editável com desconto sujeito a limite e step-up); política vigente copiada; consumo de inventário na mesma transação; conta da reserva aberta em `billing`; day use; evento `ReservationCreated` (RF-RES-01, 02, 03, 14; RN-14, RN-15; RF-CTA-01).

**Por que agora:** a API de criação é o contrato que recepção (M5), channel manager (M8) e eventos (M10) vão repetir.

**Dependências e paralelismo:** E06. Não paralelizável.

**Escopo:**

- `Booking` com código legível, titular (API pública de `guests`), origem, canal e ID externo opcional; `Reservation` com código legível, tipo, período, ocupação, plano, segmento, pedidos especiais, `StayGuest` (principal e acompanhantes), status inicial `PROVISIONAL` (sem garantia, com validade) ou `CONFIRMED` (com garantia).
- `NightlyRate` por noite a partir de `priceFor`, com `overridden` quando editado; desconto acima do limite (H7) exige step-up.
- Cópia das políticas de cancelamento e garantia vigentes na reserva (RN-15).
- Inventário consumido pelo serviço de E05 na mesma transação; falta de disponibilidade → `409` com `code` estável.
- Conta da reserva aberta via API pública de `billing` (`FolioOwner` do tipo reserva) na criação.
- Alerta de lista de restrição do hóspede (API de `guests`) devolvido na resposta; não bloqueia.
- Day use com regra de H5.
- Listener de `GuestsMerged` que reponta hóspedes das reservas (H8).
- Evento `ReservationCreated`; `Idempotency-Key` obrigatório; auditoria.

**Fora de escopo:** alteração e cancelamento (E08); garantia e expiração (E09); alocação (E10); bloqueios (E11).

**Passos de implementação:**

1. Testes de integração: reserva simples e agrupadora; preço congelado após mudar a tarifa; desconto acima do limite com e sem aprovação; sem disponibilidade; conta aberta; evento; idempotência; day use; `GuestsMerged`.
2. Implementar migração, domínio, casos de uso e endpoints em `/api/v1/bookings` e `/api/v1/reservations`; regenerar o contrato.
3. Documentar a API pública de criação no `CLAUDE.md` para M5, M8 e M10.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "o que SPEC §5.7 (RF-RES-01 a 03, 08, 14), §8.2.6, §8.4 (reserva), RN-14, RN-15 e RF-CTA-01 definem para a criação?"; "qual a API pública de guests e billing registrada nos handoffs de M3?".
- Partes paralelas: nenhuma.
- Foco do verificador: reserva criada sem consumir inventário ou consumindo fora da transação; conta não aberta ou aberta duas vezes em retentativa; preço lido da tarifa atual em vez do congelado; desconto acima do limite sem aprovação; POST repetido criando duas reservas.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E07 — "Criação de reservas" do roadmap docs/roadmaps/roadmap-m4-tarifas-reservas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo restrições, as hipóteses H5, H7 e H8 e o "Protocolo de execução" —, a seção E07 inteira e o "Evidências e handoff" de E04, E05 e E06. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E06 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: em reservations, criar Booking e reservas (uma por UH) com titular, acompanhantes, origem, canal, segmento e pedidos especiais; preço congelado por noite a partir de priceFor, editável com desconto sujeito ao limite e a step-up; cópia das políticas vigentes; consumo de inventário na mesma transação (409 sem disponibilidade); conta da reserva aberta pela API pública de billing; alerta de lista de restrição; day use; listener de GuestsMerged; evento ReservationCreated; Idempotency-Key obrigatório. Não implemente alteração, cancelamento, garantia, alocação nem bloqueios.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E07 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume SPEC RF-RES-01 a 03, 08 e 14, §8.2.6, §8.4, RN-14, RN-15 e RF-CTA-01 e a API pública de guests e billing registrada em M3;
- escreva os testes de integração primeiro, implemente, regenere api/openapi.yaml, documente a API pública de criação no CLAUDE.md e rode os comandos oficiais de build, teste e lint;
- verificação independente com o Prompt do verificador, com foco em inventário fora da transação, conta duplicada em retentativa, preço não congelado, desconto sem aprovação e POST repetido.

Critérios de aceite (detalhes na seção E07):
- reserva simples e agrupadora criadas com conta aberta, inventário consumido e ReservationCreated no outbox;
- mudar a tarifa depois não altera os NightlyRate da reserva;
- desconto acima do limite sem aprovação → 403 APPROVAL_REQUIRED; sem disponibilidade → 409;
- repetição com a mesma Idempotency-Key não cria segunda reserva nem segunda conta; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E07 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Reserva simples e reserva agrupadora com duas UHs criadas: inventário consumido, conta da reserva aberta em `billing`, `ReservationCreated` no outbox, tudo na mesma transação (teste de integração).
- [ ] Alterar a tarifa do plano depois da criação não muda os `NightlyRate` da reserva.
- [ ] Políticas de cancelamento e garantia vigentes copiadas na reserva; editar a política depois não altera a reserva.
- [ ] Desconto acima do limite sem permissão nem aprovação → `403` com `APPROVAL_REQUIRED`; com aprovação, auditado.
- [ ] Sem disponibilidade → `409` com `code` estável e nada gravado.
- [ ] Repetição com a mesma `Idempotency-Key` não cria segunda reserva nem segunda conta.
- [ ] Hóspede na lista de restrição gera alerta na resposta; `GuestsMerged` reponta o hóspede nas reservas.
- [ ] Day use criado segundo H5, com a regra registrada no handoff.
- [ ] API pública de criação documentada no `CLAUDE.md`; contrato regenerado; build, testes e lint oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Tarefa de verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Agregado grande (booking com várias reservas e noites): manter `Reservation` como agregado próprio e `Booking` leve; registrar a decisão.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E08 — Alteração, cancelamento e reativação — #pendente

**Objetivo:** alterar datas, tipo, ocupação ou plano com recálculo de preço, ajuste de inventário e histórico; cancelar aplicando a política copiada (multa lançada na conta ou isentada com aprovação); reativar reserva cancelada ou no-show como ação sensível (RF-RES-05, RF-RES-06, SPEC §8.4).

**Por que agora:** completa o ciclo de vida da reserva antes do check-in (M5); o channel manager (M8) usa as mesmas operações.

**Dependências e paralelismo:** E07. Pode correr em paralelo com E09, E10 e E11 (em worktrees separados); conflito de edição no agregado `Reservation` — preferir executar em sequência.

**Escopo:**

- Alteração com `If-Match`: recalcula `NightlyRate` só das noites afetadas (noites mantidas preservam o preço congelado; RN-14 "na criação ou alteração"), ajusta inventário com lock (libera e consome na mesma transação), guarda histórico da alteração; evento `ReservationChanged`.
- Cancelamento: calcula a multa pela política copiada e pela antecedência; lança na conta via `billing` (código padrão de multa, H6) ou isenta mediante aprovação (RF-ACS-08); libera inventário; evento `ReservationCancelled`.
- Reativação de `CANCELLED` ou `NO_SHOW` para `CONFIRMED`: ação sensível com step-up, consome inventário de novo (falha sem disponibilidade).
- Transições inválidas pela máquina de estados (ex.: alterar reserva `CHECKED_OUT`) → `409`.

**Fora de escopo:** no-show automático (M6); devolução de depósito (reembolso manual de M3, operado pela recepção).

**Passos de implementação:**

1. Testes de domínio da máquina de estados e do cálculo de multa; testes de integração de alteração, cancelamento com multa e com isenção, reativação com e sem disponibilidade.
2. Implementar casos de uso e endpoints de ação (`POST /reservations/{id}/cancel`, `/reactivate`, `PATCH` ou `PUT` de alteração); regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em RF-RES-05, RF-RES-06, RF-TAR-07, §8.4 e nos handoffs de E05 e E07.
- Partes paralelas: nenhuma.
- Foco do verificador: alteração que reprecifica noites não afetadas; inventário liberado e não reconsumido (ou vice-versa) em falha parcial; multa calculada pela política atual em vez da copiada; isenção sem aprovação; multa lançada duas vezes em retentativa.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E08 — "Alteração, cancelamento e reativação" do roadmap docs/roadmaps/roadmap-m4-tarifas-reservas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo a hipótese H6 e o "Protocolo de execução" —, a seção E08 inteira, o "Evidências e handoff" de E05 e E07 e RF-RES-05, RF-RES-06, RF-TAR-07 e a máquina de estados da reserva em docs/SPEC.md §8.4. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E07 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: em reservations, alterar datas, tipo, ocupação ou plano recalculando só as noites afetadas e ajustando o inventário com lock na mesma transação, com histórico e ReservationChanged; cancelar aplicando a política copiada na reserva, lançando a multa na conta via billing ou isentando com aprovação, liberando o inventário e publicando ReservationCancelled; reativar reserva cancelada ou no-show como ação sensível. Não implemente no-show automático nem devolução de depósito.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E08 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de domínio e de integração primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em reprecificação de noites não afetadas, inventário inconsistente em falha, multa pela política atual, isenção sem aprovação e multa duplicada.

Critérios de aceite (detalhes na seção E08):
- alteração recalcula só as noites afetadas e ajusta o inventário; histórico registrado;
- cancelamento lança a multa da política copiada uma única vez; isenção sem aprovação → 403 APPROVAL_REQUIRED;
- reativação sem aprovação → 403; sem disponibilidade → 409;
- transição inválida → 409 com code estável; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E08 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Estender uma reserva em uma noite mantém o preço das noites originais e precifica só a nova; inventário da nova data consumido (teste de integração).
- [ ] Mudar o tipo de UH libera o inventário do tipo antigo e consome o do novo na mesma transação; sem disponibilidade no novo, nada muda.
- [ ] Cancelamento dentro do prazo de multa lança a multa calculada pela política copiada; repetição com a mesma `Idempotency-Key` não lança de novo.
- [ ] Isenção de multa sem permissão nem aprovação → `403` com `APPROVAL_REQUIRED`; com aprovação, auditada.
- [ ] Reativação sem aprovação → `403`; sem disponibilidade → `409`; com ambos, reserva `CONFIRMED` e inventário consumido.
- [ ] Transições fora da SPEC §8.4 → `409` com `code` estável.
- [ ] `ReservationChanged` e `ReservationCancelled` no outbox; contrato regenerado; build, testes e lint oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Tarefa de verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Lançar multa em `billing` e cancelar na mesma transação: ambos no mesmo processo e banco; teste de falha simulada confirma o tudo ou nada.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E09 — Pré-reserva, garantia e expiração — #pendente

**Objetivo:** confirmar pré-reservas ao registrar garantia manual (depósito pago na conta ou garantia informada), expirar automaticamente pré-reservas vencidas liberando o inventário e listar pré-reservas e reservas sem garantia perto do vencimento (RF-RES-08 recorte manual, RN-23).

**Por que agora:** sem expiração, pré-reservas prendem inventário indefinidamente; o job precisa existir antes da operação real.

**Dependências e paralelismo:** E07. Pode correr em paralelo com E08, E10 e E11 (em worktrees separados); conflito de edição no agregado `Reservation` — preferir executar em sequência.

**Escopo:**

- Validade da pré-reserva a partir do parâmetro de expiração do `hotel` (RF-CFG-04).
- Confirmação: `POST /reservations/{id}/confirm` com tipo de garantia; se a política exige depósito, confere na API pública de `billing` o saldo de depósitos da conta da reserva.
- Job de expiração a cada 5 min com ShedLock (ARCHITECTURE §13): cancela pré-reservas vencidas sem multa, libera inventário, publica `ReservationCancelled` com motivo "expirada".
- Consulta de pré-reservas e reservas sem garantia que vencem num intervalo (para o painel de M6).

**Fora de escopo:** cartão tokenizado e Pix (M7); alerta por push.

**Passos de implementação:**

1. Testes: confirmação com e sem depósito suficiente; job expira só as vencidas, é idempotente e não roda em paralelo; consulta de vencimento.
2. Implementar caso de uso, job, consulta e endpoints; regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em RF-RES-08, RN-23, RF-TAR-07, ARCHITECTURE §13 e nos handoffs de E07 e de M3 (depósitos).
- Partes paralelas: nenhuma.
- Foco do verificador: job expirando reserva confirmada; expiração sem liberar inventário; corrida entre confirmação e expiração da mesma reserva; confirmação com depósito abaixo do exigido.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E09 — "Pré-reserva, garantia e expiração" do roadmap docs/roadmaps/roadmap-m4-tarifas-reservas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E09 inteira, o "Evidências e handoff" de E07 e RF-RES-08, RN-23 e RF-TAR-07 em docs/SPEC.md. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E07 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: em reservations, confirmar pré-reservas com garantia manual (conferindo na API pública de billing o depósito exigido pela política copiada), expirar por job com ShedLock a cada 5 minutos as pré-reservas vencidas liberando o inventário e publicando ReservationCancelled com motivo expirada, e listar pré-reservas e reservas sem garantia que vencem num intervalo. Não implemente cartão tokenizado, Pix nem alerta por push.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E09 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em expiração de reserva confirmada, inventário não liberado, corrida entre confirmação e expiração e depósito insuficiente aceito.

Critérios de aceite (detalhes na seção E09):
- confirmação com depósito abaixo do exigido → 422; com depósito suficiente → CONFIRMED;
- job cancela só pré-reservas vencidas, libera inventário e é idempotente;
- confirmação e expiração simultâneas da mesma reserva terminam em um único estado coerente;
- consulta de vencimentos correta; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E09 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Confirmação com depósito abaixo do exigido pela política → `422` com `code` estável; com depósito suficiente ou política sem depósito → `CONFIRMED`.
- [ ] Job cancela só pré-reservas com validade vencida, libera o inventário e publica `ReservationCancelled` com motivo "expirada"; segunda execução não altera nada.
- [ ] Job protegido por ShedLock (teste ou inspeção da configuração).
- [ ] Confirmação e expiração simultâneas da mesma reserva resultam em um único estado final coerente (lock otimista ou pessimista, teste).
- [ ] Consulta de vencimentos devolve as reservas certas para o intervalo.
- [ ] Contrato regenerado; build, testes e lint oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Tarefa de verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Prazo de expiração em fuso errado: calcular com o fuso do hotel (M1) e testar perto da meia-noite.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E10 — Alocação de UH — #pendente

**Objetivo:** alocar UH a uma reserva com garantia no banco contra sobreposição, sugerir UHs por características e preferências (OOS como última opção), marcar "não mover" e efetivar a sinalização de reservas alocadas atingidas por OOO (RF-RES-04, RN-04, RN-07, RF-UH-05).

**Por que agora:** o check-in (M5) exige UH alocada; a garantia de não haver UH em dobro precisa estar no banco antes.

**Dependências e paralelismo:** E07. Pode correr em paralelo com E08, E09 e E11 (em worktrees separados); conflito de edição no agregado `Reservation` — preferir executar em sequência.

**Escopo:**

- `RoomAssignment` com UH, período (`daterange`) e motivo; constraint `EXCLUDE USING gist (room_id WITH =, stay WITH &&)` sobre alocações ativas (ARCHITECTURE §11); UH do tipo da reserva ou mudança de tipo explícita.
- Sugestão: UHs ativas do tipo, sem OOO no período, livres, ordenadas por preferências do hóspede (API de `guests`) e características (API de `rooms`), com OOS por último.
- Flag "não mover".
- Desalocação e realocação de reservas futuras (troca de UH de hóspede em casa é `frontdesk`, M5).
- Efetivação do listener de OOO de E05: reservas alocadas na UH no período ficam marcadas "requer realocação" e aparecem numa consulta.
- Day use conforme H5.

**Fora de escopo:** troca de UH com hóspede em casa (`moveRoom`, M5); otimização automática de alocação.

**Passos de implementação:**

1. Testes de integração: alocação sobreposta recusada pela aplicação e pelo banco (SQL direto); sugestão ordenada e sem OOO; OOS por último; OOO marca reservas alocadas.
2. Implementar migração com a constraint, domínio, casos de uso, endpoints e consulta; regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em RF-RES-04, RN-04, RN-07, RF-UH-05, ARCHITECTURE §11 e nos handoffs de E05 e E07.
- Partes paralelas: nenhuma.
- Foco do verificador: constraint ausente ou aplicada a alocações canceladas; limites do `daterange` errados (saída de um no mesmo dia da chegada de outro deve ser permitida); sugestão incluindo UH em OOO; alocação de reserva cancelada.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E10 — "Alocação de UH" do roadmap docs/roadmaps/roadmap-m4-tarifas-reservas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo a hipótese H5 e o "Protocolo de execução" —, a seção E10 inteira, o "Evidências e handoff" de E05 e E07, RF-RES-04, RN-04, RN-07 e RF-UH-05 em docs/SPEC.md e docs/ARCHITECTURE.md §11. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E07 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: em reservations, alocar UH a reservas com a constraint EXCLUDE USING gist sobre alocações ativas (saída e chegada no mesmo dia permitidas), sugerir UHs livres do tipo por preferências e características com OOS por último e sem OOO, marcar não mover, desalocar e realocar reservas futuras e marcar como requer realocação as reservas alocadas atingidas por OOO. Não implemente troca de UH com hóspede em casa (marco M5).

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E10 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de integração primeiro, incluindo um INSERT direto em SQL que deve falhar, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em constraint ausente ou sobre alocações canceladas, limites do daterange, sugestão com UH em OOO e alocação de reserva cancelada.

Critérios de aceite (detalhes na seção E10):
- alocação sobreposta recusada pela API (409) e pelo banco (INSERT direto falha);
- saída e chegada no mesmo dia na mesma UH permitidas;
- sugestão sem OOO e com OOS por último;
- OOO marca reservas alocadas como requer realocação, listadas em consulta; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E10 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Alocação sobreposta da mesma UH → `409` com `code` estável pela API e falha de constraint num `INSERT` direto em SQL (teste de integração).
- [ ] Saída de uma reserva e chegada de outra no mesmo dia na mesma UH são permitidas.
- [ ] Alocações de reservas canceladas não bloqueiam a UH.
- [ ] Sugestão lista só UHs ativas do tipo, livres e sem OOO no período, com OOS por último e preferências do hóspede consideradas.
- [ ] "Não mover" impede realocação sem remover a flag antes.
- [ ] OOO numa UH alocada marca a reserva como "requer realocação" e ela aparece na consulta correspondente.
- [ ] Contrato regenerado; build, testes e lint oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Tarefa de verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- `EXCLUDE` com predicado parcial (só ativas) exige índice parcial; testar o caso de reserva cancelada e reativada.

**Rollback / migração:** constraint nova sobre tabela nova; sem dados reais ainda.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E11 — Grupos e bloqueios — #pendente

**Objetivo:** criar bloqueios de grupo com N UHs por tipo e data e tarifa do grupo, consumindo o contador `blocked`; registrar rooming list e pick-up (converter unidades do bloqueio em reservas sem consumir inventário duas vezes); abrir conta master do grupo; e expor a regra de data de corte com `releaseUnused` para o fechamento de M6 (RF-RES-09, RN-16).

**Por que agora:** eventos e grupos (M10) e o fechamento (M6) dependem dos bloqueios; a interação com os contadores precisa estar resolvida junto com as reservas.

**Dependências e paralelismo:** E07. Pode correr em paralelo com E08, E09 e E10 (em worktrees separados); conflito de edição no serviço de inventário — preferir executar em sequência.

**Escopo:**

- `RoomBlock` com código, nome, data de corte, titular (hóspede ou empresa via `guests`), `BlockAllocation` (tipo, data, quantidade, pick-up, tarifa do grupo).
- Criação e alteração de alocações do bloqueio ajustam `blocked` com o lock de E05.
- Pick-up: criar reserva vinculada ao bloqueio move uma unidade de `blocked` para `sold` na mesma transação, com a tarifa do grupo congelada.
- Rooming list: criação em lote de reservas a partir de uma lista de hóspedes, idempotente.
- Conta master do grupo aberta em `billing` (`FolioOwner` do tipo grupo); roteamento a cargo de quem opera (M3).
- `releaseUnused(blockId)` na API pública: devolve ao inventário o não utilizado após a data de corte; o fechamento de M6 chama.
- Eventos: `AvailabilityChanged` nas mudanças de contador.

**Fora de escopo:** chamada automática no fechamento (M6); eventos e espaços (M10).

**Passos de implementação:**

1. Testes de integração: bloqueio consome `blocked`; pick-up não consome duas vezes; rooming list idempotente; `releaseUnused` devolve só o não utilizado; bloqueio sem disponibilidade → `409`.
2. Implementar migração, domínio, casos de uso e endpoints em `/api/v1/room-blocks`; regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em RF-RES-09, RN-16, §8.2.6 e nos handoffs de E05 e E07.
- Partes paralelas: nenhuma.
- Foco do verificador: pick-up consumindo `sold` sem reduzir `blocked`; `releaseUnused` antes da data de corte; rooming list repetida duplicando reservas; tarifa do grupo não congelada.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E11 — "Grupos e bloqueios" do roadmap docs/roadmaps/roadmap-m4-tarifas-reservas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E11 inteira, o "Evidências e handoff" de E05 e E07 e RF-RES-09 e RN-16 em docs/SPEC.md. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E07 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: em reservations, criar bloqueios de grupo (N UHs por tipo e data, tarifa do grupo, data de corte) que consomem o contador blocked com lock, pick-up que move uma unidade de blocked para sold na mesma transação com a tarifa do grupo congelada, rooming list idempotente, conta master do grupo aberta em billing e releaseUnused na API pública para o fechamento do marco M6 chamar após a data de corte. Não chame releaseUnused automaticamente.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E11 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de integração primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em pick-up que consome duas vezes, release antes do corte, rooming list duplicada e tarifa do grupo não congelada.

Critérios de aceite (detalhes na seção E11):
- bloqueio reduz a disponibilidade; sem disponibilidade → 409;
- pick-up mantém blocked + sold constante para a unidade convertida;
- rooming list repetida com a mesma Idempotency-Key não duplica reservas;
- releaseUnused antes da data de corte → 409 e depois devolve só o não utilizado; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E11 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Criar bloqueio reduz a disponibilidade do tipo nas datas; sem disponibilidade → `409` com `code` estável.
- [ ] Pick-up cria reserva vinculada ao bloqueio, decrementa `blocked` e incrementa `sold` na mesma transação; a disponibilidade total não muda (teste).
- [ ] Reserva do pick-up usa a tarifa do grupo congelada por noite.
- [ ] Rooming list cria as reservas em lote; repetição com a mesma `Idempotency-Key` não duplica.
- [ ] Conta master do grupo aberta em `billing`.
- [ ] `releaseUnused` antes da data de corte → `409`; depois, devolve só as unidades não utilizadas e publica `AvailabilityChanged`.
- [ ] Contrato regenerado; build, testes e lint oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Tarefa de verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Cancelamento de reserva de pick-up: decidir se a unidade volta ao bloqueio ou ao inventário geral (antes do corte, volta ao bloqueio); registrar a decisão e coordenar com E08 se já concluída.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E12 — Busca, listas operacionais e views de leitura — #pendente

**Objetivo:** buscar reservas por nome, documento, número, número da OTA, período e status; listar por data chegadas, saídas, hospedados e no-shows previstos; e publicar as views `report_*` de `rates` e `reservations` para os relatórios de M6 (RF-RES-11, RF-RES-13, ARCHITECTURE §11).

**Por que agora:** a recepção (M5) opera a partir dessas listas e o `reporting` (M6) lê só as views.

**Dependências e paralelismo:** E08, E09, E10, E11. Não paralelizável.

**Escopo:**

- `GET /api/v1/reservations` com filtros e paginação por cursor; busca por documento pela API pública de `guests` (sem acessar o schema dele).
- Listas por data operacional: chegadas, saídas, hospedados (status `IN_HOUSE`, preenchido a partir de M5), no-shows previstos (confirmadas não chegadas na data).
- Views: `reservations.report_reservations` (sem documento nem dados sensíveis), `reservations.report_room_nights` (noites vendidas por tipo, plano, canal, segmento, categoria do plano para Q-15), `reservations.report_inventory`, `rates.report_rate_prices` (nomes a confirmar na etapa).
- Índices para as consultas mais frequentes.

**Fora de escopo:** relatórios e painel (M6); pick-up e on-the-books (RF-REL-04, M10).

**Passos de implementação:**

1. Testes de integração: cada filtro de busca, cada lista e leitura de cada view.
2. Implementar consultas com `JdbcClient`, endpoints, views e índices; regenerar o contrato; documentar as views no `CLAUDE.md`.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em RF-RES-11, RF-RES-13, ARCHITECTURE §11 e nos handoffs de E07 a E11.
- Partes paralelas: nenhuma.
- Foco do verificador: busca por documento acessando o schema de `guests`; view com documento ou dado sensível; lista usando data do calendário em vez da data operacional; consulta sem índice em filtro frequente.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E12 — "Busca, listas operacionais e views de leitura" do roadmap docs/roadmaps/roadmap-m4-tarifas-reservas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E12 inteira, o "Evidências e handoff" de E07 a E11, RF-RES-11 e RF-RES-13 em docs/SPEC.md e "Leitura para relatórios" em docs/ARCHITECTURE.md §11. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E08, E09, E10 e E11 estão #concluida e que o código bate com o handoff delas; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: em reservations, buscar reservas por nome, documento (via API pública de guests), número, número da OTA, período e status com paginação por cursor; listar por data operacional chegadas, saídas, hospedados e no-shows previstos; publicar views report_* de reservations e rates sem documento nem dado sensível, com a categoria do plano para a regra de Q-15; criar os índices necessários. Não implemente relatórios nem painel.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E12 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de integração primeiro, implemente, regenere api/openapi.yaml, documente as views no CLAUDE.md e rode os comandos oficiais de build, teste e lint;
- verificação independente com o Prompt do verificador, com foco em acesso ao schema de guests, view com dado sensível, data do calendário no lugar da operacional e filtro sem índice.

Critérios de aceite (detalhes na seção E12):
- cada filtro de busca e cada lista operacional coberto por teste de integração;
- views report_* lidas em teste, sem documento nem dado sensível;
- listas usam a data operacional do hotel;
- views documentadas no CLAUDE.md; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E12 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Busca por nome, documento, número, número da OTA, período e status, cada filtro com teste de integração; busca por documento usa só a API pública de `guests`.
- [ ] Listas de chegadas, saídas, hospedados e no-shows previstos por data operacional, com teste que usa data operacional diferente da do relógio.
- [ ] Views `report_*` de `reservations` e `rates` existem, são lidas em teste e não contêm documento nem dado sensível; a de noites vendidas traz a categoria do plano.
- [ ] Views documentadas no `CLAUDE.md` como API de leitura.
- [ ] `ApplicationModules.verify()` passa; contrato regenerado; build, testes e lint oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Tarefa de verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Busca por nome cruzando módulos: buscar IDs de hóspede em `guests` e filtrar reservas por eles; limitar resultados para manter RNF-06.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E13 — Gate do marco M4 — #pendente

**Objetivo:** confirmar, a partir de uma cópia limpa do repositório, que toda a definição global de pronto do M4 é atendida, e deixar o roadmap do M5 pronto para ser sincronizado.

**Por que agora:** é o critério de entrada do marco M5.

**Dependências e paralelismo:** E12 (que depende transitivamente de todas as demais). Não paralelizável.

**Escopo:**

- Rodar build, testes, lint e verificação do contrato do zero numa cópia limpa.
- Conferir cada item da "Definição global de pronto" com evidência.
- Cenário ponta a ponta automatizado: criar plano base e derivado com restrição, cotar, criar reserva agrupadora com desconto aprovado, alocar UH, aplicar OOO que marca realocação, alterar a estadia, criar bloqueio e fazer pick-up, cancelar com multa lançada na conta, expirar uma pré-reserva pelo job.
- Revisar `CLAUDE.md`: API pública de `rates` e `reservations`, eventos, views.
- Registrar a conclusão do M4 no histórico de revisões de `docs/roadmaps/README.md`.

**Fora de escopo:** funcionalidade nova. Problemas voltam para a etapa de origem (reabrir com `#em-andamento` e registrar no handoff dela), salvo ajustes triviais de documentação.

**Passos de implementação:**

1. Escrever o teste ponta a ponta do marco.
2. Rodar tudo do zero numa cópia limpa e registrar a evidência de cada item.
3. Revisar documentação e atualizar o README dos marcos.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação: nenhuma.
- Partes paralelas: nenhuma.
- Foco do verificador: item da definição de pronto sem evidência; teste concorrente que não roda de fato em paralelo; constraint de sobreposição não exercitada por SQL direto; dependência entre módulos fora da ARCHITECTURE §3.1.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E13 — "Gate do marco M4" do roadmap docs/roadmaps/roadmap-m4-tarifas-reservas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a "Definição global de pronto" —, a seção E13 inteira e o "Evidências e handoff" de E01 a E12. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 a E12 estão #concluida; se não, ou se o código divergiu do plano, pare e relate.

Objetivo: escrever o teste ponta a ponta do marco (plano base e derivado com restrição, cotação, reserva agrupadora com desconto aprovado, alocação, OOO que marca realocação, alteração de estadia, bloqueio com pick-up, cancelamento com multa na conta e expiração de pré-reserva pelo job) e verificar numa cópia limpa, sem caches nem estado local, cada item da definição global de pronto do M4, com evidência. Não implemente funcionalidade nova: problemas são devolvidos à etapa de origem, salvo ajustes triviais de documentação.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E13 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva o teste ponta a ponta, rode do zero numa cópia limpa os comandos oficiais de build, teste e lint do CLAUDE.md e a verificação do contrato, e registre a evidência de cada item da definição global de pronto;
- revise o CLAUDE.md e registre a conclusão do M4 no histórico de docs/roadmaps/README.md;
- verificação independente com o Prompt do verificador, com foco em item sem evidência, teste concorrente que não roda em paralelo, constraint não exercitada por SQL direto e dependência fora da ARCHITECTURE §3.1.

Critérios de aceite (detalhes na seção E13):
- teste ponta a ponta do marco passa;
- todos os itens da definição global de pronto marcados com evidência numa cópia limpa;
- CLAUDE.md coerente com o código; docs/roadmaps/README.md registra a conclusão do M4.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E13 (snapshot, verificações, resultados, parecer do verificador, notas para o marco M5) e responda com um resumo curto. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] O teste ponta a ponta do marco passa.
- [ ] Todos os itens da "Definição global de pronto" estão marcados, cada um com evidência numa cópia limpa.
- [ ] `CLAUDE.md` documenta a API pública de `rates` e `reservations`, os eventos publicados e consumidos e as views.
- [ ] `docs/roadmaps/README.md` registra a conclusão do M4 no histórico de revisões.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint numa cópia limpa → sucesso.
- Tarefa de verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Testes concorrentes intermitentes: rodar o conjunto ao menos três vezes na cópia limpa e registrar.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para o marco M5: —

---

## Histórico de revisões

- 2026-10-13 — roadmap criado.
- 2026-10-13 — aplicada a regra por camadas da ARCHITECTURE §3.1 (decisão do usuário): `reservations` pode chamar módulos da Plataforma; confirmação em PDF de M5 segue o modelo dono + printing; o alerta de reservas sem garantia continua como lista porque a SPEC não pede push. Nenhuma etapa mudou de escopo.
- 2026-10-15 — nota herdada do M2 acrescentada em "Hipóteses e decisões em aberto" (listener de `OutOfOrderChanged` da E05 com o backoff e o abandono do outbox da M2 E12). Nenhuma etapa reescrita; tags inalteradas.
