# Roadmap — M5 Recepção, FNRH e impressão

> Arquivo: `docs/roadmaps/roadmap-m5-recepcao-fnrh.md` · criado em 2026-10-13 · última revisão 2026-10-13
>
> Marco 5 de 11 · mapa dos marcos em [`docs/roadmaps/README.md`](README.md) · marco anterior: [M4 — Tarifas, disponibilidade e reservas](roadmap-m4-tarifas-reservas.md) · próximo marco: [M6 — Operação diária e fechamento](roadmap-m6-operacao-fechamento.md)
>
> **Pré-requisito:** a etapa de gate do marco M4 (última etapa de `docs/roadmaps/roadmap-m4-tarifas-reservas.md`) está `#concluida`.

## Como usar

Duas formas, ambas na raiz do repositório:

- **Manual:** cole o prompt de uma etapa numa sessão limpa do Claude Code. Uma etapa por sessão.
- **Automática:** cole o prompt da seção "Execução automática" numa sessão limpa; ela executa até 3 etapas em sequência, um subagente por etapa mais um verificador, e para para revisão. Para não parar em cada pedido de permissão, libere antes os comandos de build/teste do projeto (registrados no `CLAUDE.md`) nas permissões do Claude Code.

**Este roadmap foi escrito antes do código existir.** Antes da primeira etapa, sincronize-o com o código real usando a skill `development-roadmap` (modo atualização): confira caminhos, APIs públicas dos módulos de M1–M4 e convenções registradas nos handoffs e no `CLAUDE.md`, e ajuste as etapas pendentes.

Etapas marcadas como paralelizáveis só devem rodar ao mesmo tempo em worktrees git separados; na mesma pasta, rode-as uma de cada vez.

Em ambas, quem executa segue o "Protocolo de execução", atualiza a tag da etapa e preenche "Evidências e handoff". Não pule dependências. Revise e faça commit entre etapas ou lotes — nenhuma sessão faz commit por conta própria.

## Objetivo

Colocar a recepção para funcionar: check-in orquestrado numa única transação (UH pronta, menores, caução manual, contas abertas), walk-in, check-out com saldo zero, troca de UH, extensão e redução de estadia, early/late e recados. Integrar a FNRH Digital (envio assíncrono com retry, pendências visíveis) e entregar o lado servidor do agente de impressão, com ficha de registro, extrato de conta e confirmação de reserva em PDF e ESC/POS. Ao fim do marco, um hóspede pode chegar, ser registrado na FNRH, ter a ficha impressa e sair com a conta zerada.

## Contexto confirmado

- Requisitos: SPEC §5.8 (REC), §5.9 (FNRH), §5.22 RF-NOT-03 e RF-NOT-04 (impressão e PDFs), RF-CTA-12 (extrato em PDF).
- Regras: RN-04, RN-05 (UH pronta), RN-06 (check-out com saldo zero), RN-11 (early/late com preço informado antes), RN-12 (ações sensíveis), RN-19 (dados sensíveis da FNRH), RN-24 (menores).
- Modelo de domínio: SPEC §8.2.6 (Reservas e recepção: `Reservation`, `StayGuest`, `MinorAuthorization`, `RoomAssignment`, `Trace`), §8.2.4 (`SensitiveGuestData` cifrado, só FNRH), §8.2.17 (`FnrhSubmission`, `FnrhGateway`), §8.2.18 (`PrintAgent`, `Printer`, `PrintJob`); máquina de estados da reserva em §8.4; eventos em §8.3 (`GuestCheckedIn`, `GuestCheckedOut`, `RoomMoved`, `ReservationCreated`/`Changed`/`Cancelled`).
- Arquitetura: ARCHITECTURE §3.1 com a **regra por camadas** (um módulo pode depender de camadas inferiores e da Plataforma sem ciclo; aresta nova entra na tabela §3.1 na mesma mudança). `frontdesk` depende de `shared`, `hotel`, `reservations`, `rooms`, `guests`, `billing`, `payments`, `fiscal`, `printing`; `fnrh` depende de `shared`, `hotel`, `reservations`, `guests` e passa a depender de `notifications` (alerta de falha, E08); `printing` depende de `shared` e passa a depender de `hotel` (cabeçalho dos documentos, E11); `billing` e `reservations` dependem de `printing` (extrato e confirmação, E11). `printing` e `notifications` nunca escutam eventos de módulos de negócio. Fluxo do check-in em §6.1, step-up em §6.3, integrações em §12 (porta + adaptador, outbox, retry com backoff, idempotência, log redigido, WireMock), impressão em §14 e D13, segurança em §8.1 (credenciais de serviço) e §8.3 (cifragem de campo).
- Documentação oficial da API FNRH v2.4 (Serpro/MTur): link em SPEC §11.
- Comandos de build, teste, lint e verificação do contrato: registrados no `CLAUDE.md` por M0 E01; não são repetidos aqui.
- Convenções herdadas, documentadas no `CLAUDE.md`: M0 (API, erros RFC 9457, migrações por módulo, `Money`, IDs, `Idempotency-Key`, outbox, ShedLock), M1 (autenticação, `authz.require`/escopo/limites/step-up, `@Audited`, data operacional, `files`), M2 (`rooms` e status, SSE, sync offline, notificações), M3 (`guests`, `billing`, `payments` manual), M4 (`rates`, `reservations`, disponibilidade).
- Caminhos de código previstos: um pacote por módulo sob o pacote base `com.zeny.hotel` (hipótese herdada de M0, H1) — `frontdesk/`, `fnrh/`, `printing/` (a criar).

## Escopo

**Incluído:** transições de hospedagem em `reservations`; módulo `frontdesk` (check-in, walk-in, check-out, troca de UH, extensão/redução, early/late, recados); módulo `fnrh` (configuração cifrada, domínios oficiais, adaptador Serpro, envio assíncrono, pendências); módulo `printing` (agentes, impressoras, fila de jobs, renderização PDF/ESC-POS) e documentos ficha, extrato e confirmação.

**Fora de escopo:**

- Emissão de nota fiscal no check-out (RF-REC-06 passo 3): M7. Até lá, notas são emitidas manualmente no portal da prefeitura (SPEC §9).
- Pré-autorização de cartão e caução integrada (RF-CTA-08): M7. Aqui a caução é só registro manual.
- Transferência de saldo para contas a receber no check-out (RF-REC-06 passo 1, RF-CTA-13): M10. Até lá, o check-out exige saldo zero por pagamento.
- Itens de empréstimo (RF-REC-09): M9.
- Tarefas de limpeza geradas por check-out ou troca de UH: M6 (`housekeeping` escuta `GuestCheckedOut` e `RoomMoved`).
- Expurgo dos dados sensíveis da FNRH após o checkout (RN-19, Q-07): M10 (automação de retenção LGPD).
- O aplicativo do agente de impressão: outro repositório.

## Restrições e invariantes

- Respeitar o grafo de módulos da ARCHITECTURE §3.1 e a regra por camadas: `frontdesk` não chama `fnrh` (mesma camada não Plataforma); `printing` só renderiza e enfileira, sem conhecer módulos de negócio — quem é dono do documento monta os dados e chama `printing`; `fnrh` reage a eventos de `reservations`. Dependências para cima continuam proibidas e vão por evento ou por porta definida no módulo de baixo nível.
- O check-in é tudo ou nada (ARCHITECTURE §6.1): validação, caução, contas, transição da reserva e ocupação da UH na mesma transação.
- Falha da FNRH nunca bloqueia check-in nem check-out (RF-FNRH-03).
- Dados sensíveis da FNRH (RN-19) só são lidos pelo módulo `fnrh`; nunca aparecem em respostas de consulta, logs, relatórios nem documentos impressos.
- Ações sensíveis exigem permissão ou step-up (RN-12): desfazer check-in, reativar reserva, check-out com ajuste.
- Lançamentos financeiros seguem RN-03 (imutáveis) e a idempotência de M0 nos POSTs com efeito financeiro.
- Credenciais da FNRH e tokens de agente são guardados cifrados ou como hash; nunca no repositório.

## Hipóteses e decisões em aberto

| # | Hipótese ou decisão | Impacto se errada / sem decisão | Etapa afetada |
|---|---|---|---|
| H1 | Pacote base `com.zeny.hotel` (herdado de M0). | Ajuste mecânico de caminhos. | todas |
| H2 | `reservations` (M4) tem a máquina de estados de §8.4, mas pode não ter as transições de hospedagem (`checkIn`, `undoCheckIn`, `checkOut`, `moveRoom`, `changeStay`) nem os eventos `GuestCheckedIn`/`GuestCheckedOut`/`RoomMoved`. | E01 completa o que faltar, conforme o handoff de M4. | E01 |
| H3 | `rooms` (M2) expõe consulta de UH pronta (RN-05) e comandos de ocupação (ocupar, liberar como Vago + Sujo). | Se faltar, E02/E04 acrescentam à API pública de `rooms` (dependência permitida). | E02, E04 |
| H4 | `guests` (M3) guarda `SensitiveGuestData` cifrado e expõe leitura restrita, usada só por `fnrh`. | Se não houver, E08 para e relata: não criar cópia dos dados em outro módulo. | E08 |
| H5 | O CPF do solicitante exigido pela FNRH vem de um cadastro no próprio `fnrh` (usuário → CPF), com um CPF padrão configurado pelo administrador; o ID do usuário viaja nos metadados do evento. | Se o hotel exigir o CPF de quem fez o check-in sem cadastro prévio, muda a origem do dado. | E07, E08 |
| H6 | A documentação v2.4 da API FNRH (SPEC §11) basta para escrever os stubs WireMock. | Se a API mudar (v2.x), os stubs e o adaptador mudam; o adaptador fica isolado (ARCHITECTURE §18). | E07 |
| H8 | Renderização PDF por templates HTML (ex.: Thymeleaf + openhtmltopdf) e ESC/POS gerado no servidor a partir de templates de texto, com bibliotecas no *version catalog*. | Troca de biblioteca é local ao `printing`. | E10, E11 |
| H9 | O agente de impressão autentica com token próprio guardado como hash em `PrintAgent` (SPEC §8.2.18), integrado à cadeia de segurança de M1. Se M1 já entregou credenciais de serviço genéricas, usa-as. | Duplicar mecanismo de credencial de serviço. | E10 |
| D1 | **Credencial de homologação da FNRH e Cadastur ativo (SPEC Q-12).** | Sem credencial não há homologação real com o Serpro. | E09 (bloqueada até decidir) |
| D2 | **Decidido em 2026-10-13 (dono + printing):** cada módulo dono de um documento monta os dados, checa a própria permissão e chama a API pública de `printing` para renderizar (PDF ou ESC/POS) e enfileirar: `frontdesk` → ficha de registro, `billing` → extrato, `reservations` → confirmação. `printing` compõe o cabeçalho do hotel pela API pública de `hotel`. Não há porta em `shared` para documentos. | — | E11 |

## Definição global de pronto

- [ ] `POST /api/v1/reservations/{id}/check-in` executa o fluxo de ARCHITECTURE §6.1 numa transação: UH não pronta → `409` `ROOM_NOT_READY`; menor sem comprovação → `422` com `code` estável; sucesso publica `GuestCheckedIn`.
- [ ] Walk-in, check-out (saldo zero), troca de UH, extensão/redução, early/late e recados têm endpoints no contrato e testes de integração.
- [ ] Eventos de reserva e de hospedagem geram `FnrhSubmission` enviadas ao adaptador Serpro (WireMock), com retry, identificadores remotos guardados e pendências listadas.
- [ ] Agente de impressão autenticado recebe jobs, confirma e jobs não confirmados são reenviados.
- [ ] Ficha de registro, extrato e confirmação de reserva saem em PDF e em job de impressão.
- [ ] Verificação de módulos, build, testes e verificação do contrato passam.
- [ ] A homologação real da FNRH (E09) está concluída ou registrada como pendência obrigatória antes da operação real.

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
Você é o verificador independente da etapa ETAPA do roadmap docs/roadmaps/roadmap-m5-recepcao-fnrh.md. Você não participou da implementação e não deve editar nenhum arquivo.
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
Você é o orquestrador do roadmap docs/roadmaps/roadmap-m5-recepcao-fnrh.md neste repositório. Seu papel é coordenar subagentes, não implementar: não edite código e mantenha seu contexto enxuto (leia o roadmap e os relatórios dos subagentes, não o código).

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

**Recepção**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E01 | Transições de hospedagem em reservations | #pendente | — | `checkIn`, `undoCheckIn`, `checkOut`, `moveRoom`, `changeStay` e eventos de hospedagem |
| E02 | Check-in orquestrado | #pendente | E01 | `POST /reservations/{id}/check-in` numa transação, com RN-05, RN-24, caução manual e desfazer check-in |
| E03 | Walk-in | #pendente | E02 | Reserva e check-in no mesmo fluxo |
| E04 | Check-out | #pendente | E02 | Saldo zero, caução manual, contas encerradas, UH Vago + Sujo, late check-out |
| E05 | Troca de UH, extensão, redução e early check-in | #pendente | E02 | `RoomMoved`, recálculo de preço, cobrança de early com preço informado |
| E06 | Recados | #pendente | E02 | Recados por setor vinculados a reserva ou UH |

**FNRH**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E07 | Configuração, domínios e adaptador Serpro da FNRH | #pendente | — | Credenciais cifradas, domínios oficiais, validação local, `FnrhGateway` com WireMock |
| E08 | Envio assíncrono e pendências da FNRH | #pendente | E01, E07 | `FnrhSubmission` por evento, retry, IDs remotos, fila de pendências |
| E09 | Homologação da FNRH com o Serpro | #bloqueada | E08 | Envios reais no ambiente de homologação |

**Impressão e documentos**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E10 | Agentes, impressoras e fila de impressão | #pendente | — | `PrintAgent` autenticado, jobs por SSE/long-poll, confirmação e reenvio |
| E11 | Documentos: ficha, extrato e confirmação | #pendente | E02, E10 | Renderização em `printing` chamada pelos módulos donos, templates PDF/ESC-POS, download e impressão |

**Gate**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E12 | Gate do marco M5 | #pendente | E03, E04, E05, E06, E08, E11 | Definição global de pronto verificada |

---

## E01 — Transições de hospedagem em reservations — #pendente

**Objetivo:** garantir que o agregado `Reservation` e a API pública de `reservations` ofereçam as transições de hospedagem de SPEC §8.4 e §8.2.6 — `checkIn`, `undoCheckIn` (mesmo dia), `checkOut`, `moveRoom` e `changeStay` — e publiquem `GuestCheckedIn`, `GuestCheckedOut` e `RoomMoved`.

**Por que agora:** `frontdesk` orquestra, mas o estado da reserva é de `reservations` (ARCHITECTURE §6.1). Sem essas transições, nenhuma etapa da recepção avança, e `fnrh` (E08) e `housekeeping` (M6) dependem desses eventos.

**Dependências e paralelismo:** nenhuma dentro deste roadmap; exige o gate de M4 `#concluida`. Pode correr em paralelo com E07 e E10 (worktrees separados); sem conflito de arquivos de módulo, mas possível conflito em `api/openapi.yaml` se houver endpoint novo.

**Escopo:** pacote `reservations` (existente desde M4): agregado, casos de uso na camada `application`, eventos na raiz do pacote e migração, se houver campos novos (ex.: instantes de check-in/out por `StayGuest`, histórico de `RoomAssignment`). Só acrescentar o que o handoff de M4 mostrar que falta (H2).

- `checkIn()`: `CONFIRMED` → `IN_HOUSE`, exige UH alocada (RN-04), registra `checkedInAt` dos `StayGuest`.
- `undoCheckIn()`: `IN_HOUSE` → `CONFIRMED`, só na mesma data operacional; ação sensível (permissão própria no catálogo de M1).
- `checkOut()`: `IN_HOUSE` → `CHECKED_OUT`.
- `moveRoom(room, reason, keepRate)`: fecha a `RoomAssignment` corrente e abre outra, respeitando a restrição `EXCLUDE` de M4.
- `changeStay(arrival, departure)`: recalcula `NightlyRate` via `rates` (dependência permitida) e ajusta os contadores de disponibilidade.
- Cotação de early check-in e late check-out a partir das políticas de `hotel` (RN-11), em `reservations`, que é dono do preço da estadia e já depende de `hotel`. `frontdesk` também pode ler as políticas de `hotel` diretamente (regra por camadas) quando só precisar exibi-las.
- Eventos `GuestCheckedIn`, `GuestCheckedOut`, `RoomMoved` (com UH de origem e destino) e `ReservationChanged` nos casos de mudança de estadia.

**Fora de escopo:** orquestração com `rooms`, `billing` e `guests` (E02–E05); endpoints de recepção (ficam em `frontdesk`).

**Passos de implementação:**

1. Ler o handoff do gate de M4 e listar o que já existe; registrar no handoff o que falta.
2. Escrever testes de domínio para cada transição válida e inválida da máquina de estados da reserva (SPEC §8.4).
3. Escrever testes de módulo (`Scenario` do Spring Modulith) para os eventos publicados.
4. Implementar, incluindo testes de integração para `moveRoom` contra a restrição `EXCLUDE` e para `changeStay` contra os contadores de disponibilidade.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "quais transições, eventos e métodos públicos o módulo `reservations` já oferece, segundo o código e o handoff do gate de M4?"; "que regras SPEC §5.7, §5.8 (RF-REC-03/04/05) e §8.4 impõem a check-in, check-out, troca de UH e mudança de estadia?".
- Partes paralelas: nenhuma.
- Foco do verificador: `undoCheckIn` aceito em outra data operacional; troca de UH que deixa duas alocações sobrepostas; `changeStay` que não devolve inventário ao reduzir; evento publicado antes do commit.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E01 — "Transições de hospedagem em reservations" do roadmap docs/roadmaps/roadmap-m5-recepcao-fnrh.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e as hipóteses —, a seção E01 inteira e o "Evidências e handoff" do gate do marco M4 em docs/roadmaps/roadmap-m4-tarifas-reservas.md. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que o gate de M4 está #concluida; se não estiver, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: completar no módulo reservations as transições checkIn, undoCheckIn (só na mesma data operacional, ação sensível), checkOut, moveRoom e changeStay (com recálculo de preço via rates e ajuste de disponibilidade), a cotação de early/late a partir das políticas de hotel e os eventos GuestCheckedIn, GuestCheckedOut e RoomMoved. Acrescente só o que M4 não entregou. Não crie endpoints de recepção nem chame rooms, billing ou guests: isso é de frontdesk, nas etapas seguintes.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E01 para #em-andamento no índice e no título;
- investigação: um subagente Explore levanta o que reservations já oferece (código e handoff de M4) e resume SPEC §5.7, RF-REC-03/04/05 e §8.4;
- escreva os testes de domínio e de módulo primeiro, implemente e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em undoCheckIn fora da data, sobreposição na troca de UH, inventário não devolvido na redução e evento antes do commit.

Critérios de aceite (detalhes na seção E01):
- todas as transições de §8.4 ligadas à hospedagem têm testes de domínio, válidas e inválidas;
- GuestCheckedIn, GuestCheckedOut e RoomMoved são publicados pelo outbox (teste de módulo);
- moveRoom e changeStay respeitam a restrição EXCLUDE e os contadores de disponibilidade (Testcontainers);
- verificação de módulos, build e testes oficiais passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E01 (snapshot, arquivos alterados, o que já existia e o que foi acrescentado, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Testes de domínio cobrem `checkIn`, `undoCheckIn`, `checkOut`, `moveRoom` e `changeStay`, incluindo transições inválidas (ex.: check-in de reserva `CANCELLED` lança erro de domínio com `code` estável).
- [ ] `undoCheckIn` é rejeitado fora da data operacional do check-in e exige a permissão sensível.
- [ ] `GuestCheckedIn`, `GuestCheckedOut` e `RoomMoved` são publicados após o commit (teste de módulo com `Scenario`).
- [ ] Teste de integração: `moveRoom` para UH ocupada no período falha pela restrição `EXCLUDE`; `changeStay` reduzindo a estadia devolve inventário nos contadores.
- [ ] Cotação de early/late usa a política vigente de `hotel` (teste).
- [ ] Verificação de módulos, build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso, incluindo `ModularityTest`.
- Tarefa de verificação do contrato (CLAUDE.md) → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- M4 pode ter modelado estadia de forma diferente: a investigação compara antes de implementar; divergência grande → parar e relatar.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E02 — Check-in orquestrado — #pendente

**Objetivo:** criar o módulo `frontdesk` com `POST /api/v1/reservations/{id}/check-in`, executando numa única transação o fluxo de ARCHITECTURE §6.1 e RF-REC-01: dados FNRH dos hóspedes, validação de menores (RN-24), UH pronta (RN-05), caução manual, contas abertas, transição da reserva e ocupação da UH; e o desfazer check-in como ação sensível.

**Por que agora:** é o fluxo central da recepção e define o padrão de orquestração entre módulos que walk-in, check-out e troca de UH vão seguir.

**Dependências e paralelismo:** E01. Não paralelizável com E03–E06 (mesmo módulo).

**Escopo:** pacote `frontdesk` (a criar) com `application/`, `web/` e API pública; chamadas a `reservations`, `rooms`, `guests`, `billing`, `payments` e, quando necessário, aos módulos de Plataforma `hotel` e `printing` (ARCHITECTURE §3.1, regra por camadas).

- Corpo do check-in: hóspedes da estadia com confirmação/edição dos dados FNRH (via `guests`), comprovação de menores (tipo, responsável, parentesco e anexo via `guests`, RF-REC-08), caução opcional registrada manualmente (via `payments`/`billing`, conforme a convenção de M3).
- Ordem na transação: validar hóspedes e menores → `rooms` confirma UH pronta → `billing` garante as contas da reserva e registra a caução → `reservations.checkIn()` → `rooms` marca ocupada.
- Alerta de hóspede em lista de restrição (RF-HOS-04) devolvido na resposta, sem bloquear.
- `POST /api/v1/reservations/{id}/undo-check-in`: ação sensível com step-up (ARCHITECTURE §6.3), mesma data operacional; libera a UH.
- Erros com `code` estável: `ROOM_NOT_READY`, `ROOM_NOT_ASSIGNED`, `MINOR_AUTHORIZATION_REQUIRED`, `GUEST_DATA_INCOMPLETE`, `APPROVAL_REQUIRED`.
- Auditoria com `@Audited` e permissões novas no catálogo (`frontdesk.check-in`, `frontdesk.check-in.undo`).

**Fora de escopo:** envio à FNRH (E08, por evento); impressão da ficha (E11); pré-autorização de cartão (M7).

**Passos de implementação:**

1. Escrever testes de integração do endpoint: sucesso, UH suja, UH em OOO, UH não alocada, menor sem comprovação, falha no meio que reverte tudo (nenhuma conta, caução ou ocupação persistida).
2. Criar o módulo, os casos de uso e o controller; acrescentar à API pública de `rooms` o que faltar (H3).
3. Implementar o desfazer check-in com step-up.
4. Regenerar o contrato e conferir a verificação de módulos.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "que APIs públicas `rooms`, `guests`, `billing` e `payments` oferecem para UH pronta, ocupação, dados FNRH, anexos, contas e caução manual, segundo o código e os handoffs de M2 e M3?"; "o que RF-REC-01, RF-REC-08, RN-05, RN-24 e ARCHITECTURE §6.1 e §6.3 exigem?".
- Partes paralelas: nenhuma.
- Foco do verificador: transação parcial (caução ou conta persistida quando o check-in falha); menor aceito sem comprovação; `frontdesk` chamando módulo fora do grafo; dados FNRH sensíveis devolvidos na resposta.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E02 — "Check-in orquestrado" do roadmap docs/roadmaps/roadmap-m5-recepcao-fnrh.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução", as restrições e as hipóteses H3 e H4 —, a seção E02 inteira e o "Evidências e handoff" de E01. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar o módulo frontdesk com POST /api/v1/reservations/{id}/check-in executando numa única transação o fluxo de docs/ARCHITECTURE.md §6.1 — dados FNRH dos hóspedes, menores (RN-24), UH pronta (RN-05), caução manual, contas abertas, reservation.checkIn() e UH ocupada — e POST /api/v1/reservations/{id}/undo-check-in como ação sensível com step-up. frontdesk só chama reservations, rooms, guests, billing e payments. Não envie à FNRH nem imprima nada aqui.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E02 para #em-andamento no índice e no título;
- investigação: um subagente Explore levanta as APIs públicas de rooms, guests, billing e payments (código e handoffs de M2 e M3) e resume RF-REC-01, RF-REC-08, RN-05, RN-24 e ARCHITECTURE §6.1 e §6.3;
- escreva os testes de integração primeiro (incluindo rollback total quando um passo falha), implemente, regenere o contrato e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em transação parcial, menor sem comprovação, dependência fora do grafo e dados sensíveis na resposta.

Critérios de aceite (detalhes na seção E02):
- UH não pronta → 409 ROOM_NOT_READY; menor sem comprovação → 422 MINOR_AUTHORIZATION_REQUIRED;
- falha em qualquer passo não deixa conta, caução nem ocupação persistidas;
- sucesso publica GuestCheckedIn e marca a UH ocupada; undo-check-in exige step-up e só vale na mesma data operacional;
- verificação de módulos, build, testes e contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E02 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Check-in com UH Suja, em OOO ou com inspeção pendente → `409` `ROOM_NOT_READY`; sem UH alocada → `409` `ROOM_NOT_ASSIGNED`.
- [ ] Hóspede menor sem comprovação → `422` `MINOR_AUTHORIZATION_REQUIRED`; com autorização anexada → aceito e registrado com tipo, responsável e parentesco.
- [ ] Teste de integração força falha depois da caução e confirma que nada foi persistido (conta, caução, status da reserva, ocupação).
- [ ] Sucesso devolve `200`, publica `GuestCheckedIn` e a UH fica Ocupada.
- [ ] `undo-check-in` sem permissão → `403` `APPROVAL_REQUIRED`; com token de aprovação válido → reserva volta a `CONFIRMED` e UH a Vago; em outra data operacional → rejeitado com `code` estável.
- [ ] Hóspede em lista de restrição gera alerta na resposta sem bloquear.
- [ ] Nenhum dado sensível da FNRH (RN-19) aparece na resposta.
- [ ] `ModularityTest` passa com `frontdesk` dependendo só dos módulos permitidos.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Tarefa de verificação do contrato (CLAUDE.md) → sucesso com `api/openapi.yaml` regenerado.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Transação longa com vários módulos: manter as chamadas síncronas mínimas e deixar efeitos secundários para eventos.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E03 — Walk-in — #pendente

**Objetivo:** permitir criar a reserva e fazer o check-in no mesmo fluxo (RF-REC-02), reutilizando o caso de uso de reserva de `reservations` e o de check-in de E02.

**Por que agora:** completa o caminho de chegada sem reserva prévia, comum em hotel pequeno.

**Dependências e paralelismo:** E02. Pode correr em paralelo com E06 (worktree separado); conflito em `api/openapi.yaml` e no controller de `frontdesk`.

**Escopo:** `frontdesk` — `POST /api/v1/walk-ins` com dados da reserva (tipo/UH, datas, plano, hóspedes) e do check-in; validação de disponibilidade e preço via `reservations`; mesma transação do check-in; `Idempotency-Key` obrigatório.

**Fora de escopo:** pagamento antecipado integrado (M7).

**Passos de implementação:**

1. Testes de integração: walk-in completo; sem disponibilidade → nada criado; UH não pronta → nada criado; repetição com a mesma `Idempotency-Key` → mesma resposta.
2. Implementar o caso de uso e o endpoint.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; o padrão está no handoff de E02.
- Partes paralelas: nenhuma.
- Foco do verificador: reserva criada sem check-in quando o check-in falha; duplicidade sem `Idempotency-Key`.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E03 — "Walk-in" do roadmap docs/roadmaps/roadmap-m5-recepcao-fnrh.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E03 inteira e o "Evidências e handoff" de E02. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E02 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar POST /api/v1/walk-ins em frontdesk, que cria a reserva via reservations e faz o check-in de E02 na mesma transação, com Idempotency-Key obrigatório (RF-REC-02). Se qualquer parte falhar, nada é persistido.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E03 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de integração primeiro, implemente, regenere o contrato e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em reserva órfã quando o check-in falha e duplicidade por repetição.

Critérios de aceite (detalhes na seção E03):
- walk-in completo deixa a reserva IN_HOUSE e a UH ocupada;
- sem disponibilidade ou com UH não pronta, nenhuma reserva, conta ou caução é criada;
- repetição com a mesma Idempotency-Key devolve a mesma resposta sem nova reserva;
- build, testes e contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E03 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] `POST /api/v1/walk-ins` válido → reserva `IN_HOUSE`, contas abertas, UH Ocupada, `GuestCheckedIn` publicado.
- [ ] Sem disponibilidade → erro com `code` estável e nenhuma reserva persistida.
- [ ] UH não pronta → `409` `ROOM_NOT_READY` e nenhuma reserva persistida.
- [ ] Sem `Idempotency-Key` → `400`; repetição com a mesma chave → mesma resposta, uma única reserva.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Tarefa de verificação do contrato (CLAUDE.md) → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Reutilizar casos de uso com transações próprias pode quebrar a atomicidade: o teste de rollback detecta.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E04 — Check-out — #pendente

**Objetivo:** implementar `POST /api/v1/reservations/{id}/check-out` (RF-REC-06 sem nota fiscal nem contas a receber): exige saldo zero em todas as contas da reserva (RN-06), trata a caução manual (captura ou devolução registrada), encerra as contas, cobra late check-out com preço informado (RF-REC-03, RN-11), passa a reserva a `CHECKED_OUT` e a UH a Vago + Sujo.

**Por que agora:** fecha o ciclo da estadia e publica `GuestCheckedOut`, consumido pela FNRH (E08) e pela governança (M6).

**Dependências e paralelismo:** E02. Pode correr em paralelo com E06 (worktree separado); conflito em `api/openapi.yaml`.

**Escopo:** `frontdesk` (caso de uso e endpoint), chamadas a `billing` (saldo por conta, encerramento, lançamento de late check-out), `payments` (caução manual), `reservations.checkOut()` e `rooms` (Vago + Sujo, H3). Erros: `FOLIO_BALANCE_NOT_ZERO` com o saldo de cada conta, `RESERVATION_NOT_IN_HOUSE`.

**Fora de escopo:** emissão de nota (M7); transferência para contas a receber (M10 — registrar no `CLAUDE.md` que o check-out só aceita saldo zero por pagamento até lá); tarefa de limpeza de saída (M6, por evento).

**Passos de implementação:**

1. Testes de integração: check-out com saldo zero; saldo pendente em uma das contas; caução devolvida; late check-out com preço cotado e lançado; repetição idempotente.
2. Implementar o caso de uso e o endpoint; acrescentar à API de `rooms` o que faltar.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "como `billing` (M3) expõe saldo por conta, encerramento de conta e lançamento por código, e como `payments` registra devolução de caução manual?".
- Partes paralelas: nenhuma.
- Foco do verificador: check-out aceito com saldo diferente de zero em alguma conta; late check-out cobrado sem cotação prévia; conta encerrada que ainda aceita lançamento.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E04 — "Check-out" do roadmap docs/roadmaps/roadmap-m5-recepcao-fnrh.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e o "Fora de escopo" —, a seção E04 inteira e o "Evidências e handoff" de E01 e E02. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E02 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar POST /api/v1/reservations/{id}/check-out em frontdesk: exige saldo zero em todas as contas (RN-06), registra captura ou devolução da caução manual, lança late check-out só com preço cotado antes (RN-11), encerra as contas, chama reservation.checkOut() e deixa a UH Vago + Sujo. Não emita nota fiscal (M7) nem transfira saldo para contas a receber (M10).

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E04 para #em-andamento no índice e no título;
- investigação: um subagente Explore levanta como billing e payments (M3) expõem saldo, encerramento de conta, lançamento por código e devolução de caução;
- escreva os testes de integração primeiro, implemente, regenere o contrato e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em check-out com saldo pendente, late sem cotação e conta encerrada aceitando lançamento.

Critérios de aceite (detalhes na seção E04):
- saldo diferente de zero em qualquer conta → 409 FOLIO_BALANCE_NOT_ZERO com o saldo por conta;
- sucesso deixa reserva CHECKED_OUT, contas encerradas, UH Vago + Sujo e publica GuestCheckedOut;
- late check-out lançado com o preço cotado; limitação de contas a receber documentada no CLAUDE.md;
- build, testes e contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E04 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Saldo diferente de zero em qualquer conta da reserva → `409` `FOLIO_BALANCE_NOT_ZERO`, com o saldo de cada conta no corpo.
- [ ] Reserva fora de `IN_HOUSE` → `409` `RESERVATION_NOT_IN_HOUSE`.
- [ ] Sucesso: reserva `CHECKED_OUT`, contas encerradas, UH Vago + Sujo, `GuestCheckedOut` publicado.
- [ ] Lançamento em conta encerrada é rejeitado (teste).
- [ ] Late check-out é lançado com o valor cotado pela política vigente; sem cotação prévia não há cobrança.
- [ ] Devolução de caução manual fica registrada e zera o saldo da caução.
- [ ] `CLAUDE.md` registra que, até M10, o check-out só aceita saldo zero por pagamento.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Tarefa de verificação do contrato (CLAUDE.md) → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Saldo calculado com roteamento entre contas (RF-CTA-04): usar o saldo oficial de `billing`, nunca recalcular em `frontdesk`.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E05 — Troca de UH, extensão, redução e early check-in — #pendente

**Objetivo:** expor em `frontdesk` a troca de UH com motivo, mantendo ou recalculando a tarifa (RF-REC-04), a extensão e redução da estadia com recálculo de preço (RF-REC-05) e a cobrança de early check-in com preço informado antes (RF-REC-03, RN-11).

**Por que agora:** completa as operações de estadia em curso usando as transições de E01.

**Dependências e paralelismo:** E02. Pode correr em paralelo com E06 (worktree separado); conflito em `api/openapi.yaml`.

**Escopo:** `frontdesk` — `POST /api/v1/reservations/{id}/room-moves` (UH destino pronta, origem passa a Vago + Sujo, `RoomMoved`), `POST /api/v1/reservations/{id}/stay-changes` (novas datas, preço recalculado, diferença informada), `GET` de cotação de early/late e lançamento do early no check-in quando aceito. `If-Match` nas alterações.

**Fora de escopo:** tarefa de limpeza na UH de origem (M6, por `RoomMoved`).

**Passos de implementação:**

1. Testes de integração: troca para UH pronta; para UH não pronta; mantendo tarifa; recalculando; extensão sem disponibilidade; redução; early cotado e lançado.
2. Implementar casos de uso e endpoints.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em RF-REC-03/04/05 e no handoff de E01.
- Partes paralelas: nenhuma.
- Foco do verificador: troca para UH não pronta; preço recalculado sem a diferença aparecer na resposta; early cobrado sem cotação.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E05 — "Troca de UH, extensão, redução e early check-in" do roadmap docs/roadmaps/roadmap-m5-recepcao-fnrh.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E05 inteira e o "Evidências e handoff" de E01 e E02. Leia também RF-REC-03, RF-REC-04, RF-REC-05 e RN-11 em docs/SPEC.md. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E02 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar em frontdesk os endpoints de troca de UH (com motivo, mantendo ou recalculando tarifa, UH destino pronta, origem Vago + Sujo, RoomMoved), de extensão/redução da estadia com recálculo de preço e de cotação e cobrança de early check-in com preço informado antes. Use as transições de reservations entregues em E01; não gere tarefas de limpeza (M6).

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E05 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de integração primeiro, implemente, regenere o contrato e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em troca para UH não pronta, diferença de preço omitida e early sem cotação.

Critérios de aceite (detalhes na seção E05):
- troca para UH não pronta → 409 ROOM_NOT_READY; troca válida publica RoomMoved e deixa a origem Vago + Sujo;
- extensão sem disponibilidade → erro com code estável; redução devolve inventário;
- early check-in só é cobrado com cotação aceita;
- build, testes e contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E05 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Troca para UH não pronta → `409` `ROOM_NOT_READY`; troca válida → `RoomMoved` publicado, origem Vago + Sujo, destino Ocupado, motivo registrado.
- [ ] Troca "mantendo tarifa" não altera `NightlyRate`; "recalculando" altera e devolve a diferença na resposta.
- [ ] Extensão sem disponibilidade → erro com `code` estável e nada alterado; redução devolve inventário.
- [ ] Early check-in só é lançado quando a cotação foi aceita no check-in (teste).
- [ ] Alterações sem `If-Match` atual → `412`.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Tarefa de verificação do contrato (CLAUDE.md) → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Preço congelado (RN-14): recálculo só nas noites alteradas; noites mantidas preservam o preço original.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E06 — Recados — #pendente

**Objetivo:** implementar recados (RF-REC-07): lembretes para setores vinculados a uma reserva ou UH, com data, responsável e resolução.

**Por que agora:** é a última funcionalidade de recepção da Fase 1 e independe do fluxo financeiro.

**Dependências e paralelismo:** E02 (módulo `frontdesk` criado). Paralelizável com E03, E04 e E05 em worktrees separados; conflito em `api/openapi.yaml`.

**Escopo:** `frontdesk` — entidade de recado (`Trace` em SPEC §8.2.6) com setor (setores de `staff`, M1, referenciados por código), data de vencimento, texto, vínculo a reserva ou UH e resolução; `POST/GET/PATCH /api/v1/traces` com filtros por setor, data e status; resolução com usuário e instante. Migração no schema de `frontdesk`.

**Fora de escopo:** notificação push dos recados: a SPEC não pede (RF-NOT-01 não lista recados). Se o hotel quiser, `frontdesk` pode chamar `notifications` pela regra por camadas.

**Passos de implementação:**

1. Testes de integração de criação, listagem filtrada com paginação por cursor e resolução.
2. Implementar.

**Estratégia de execução:** `direta` · Revisão humana: não

- Investigação: nenhuma.
- Partes paralelas: nenhuma.
- Foco do verificador: recado de reserva visível para quem não tem permissão de leitura de reservas; resolução sem auditoria.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E06 — "Recados" do roadmap docs/roadmaps/roadmap-m5-recepcao-fnrh.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E06 inteira, o "Evidências e handoff" de E02 e RF-REC-07 em docs/SPEC.md. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E02 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: implementar recados em frontdesk — criação, listagem filtrada por setor, data e status com paginação por cursor, e resolução com usuário e instante — vinculados a reserva ou UH, em /api/v1/traces, com migração no schema de frontdesk. Não envie notificações push.

Siga o Protocolo de execução com a estratégia direta:
- tire o snapshot inicial e troque a tag de E06 para #em-andamento no índice e no título;
- sem subagentes: escreva os testes de integração primeiro, implemente, regenere o contrato e rode os comandos oficiais de build e teste do CLAUDE.md.

Critérios de aceite (detalhes na seção E06):
- criação, listagem filtrada e resolução cobertas por testes de integração;
- escrita auditada e protegida por permissão;
- build, testes e contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E06 (snapshot, arquivos alterados, decisões, comandos e resultados, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] `POST /api/v1/traces` cria recado vinculado a reserva ou UH; sem vínculo → `400`.
- [ ] `GET /api/v1/traces` filtra por setor, data e status, com paginação por cursor.
- [ ] Resolver registra usuário e instante; recado resolvido não é resolvido de novo (`409`).
- [ ] Escrita sem permissão → `403`; escrita auditada.
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Tarefa de verificação do contrato (CLAUDE.md) → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Nenhum relevante.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E07 — Configuração, domínios e adaptador Serpro da FNRH — #pendente

**Objetivo:** criar o módulo `fnrh` com configuração cifrada das credenciais (RF-FNRH-02), cadastro do CPF solicitante (H5), domínios oficiais com validação local (RF-FNRH-06) e a porta `FnrhGateway` com o adaptador REST do Serpro (Basic Auth, header `cpf_solicitante`), testado com WireMock a partir da documentação v2.4.

**Por que agora:** é a primeira integração externa do sistema e fixa o padrão de porta + adaptador + log de integração da ARCHITECTURE §12 que M7 e M8 vão repetir.

**Dependências e paralelismo:** nenhuma dentro deste roadmap; exige o gate de M4 `#concluida`. Paralelizável com E01–E06 e E10 em worktree separado; conflito em `gradle/libs.versions.toml` (WireMock, se ainda não existir) e `api/openapi.yaml`.

**Escopo:** pacote `fnrh` (a criar):

- Configuração: URL base, usuário e senha cifrados com a cifragem de campo de M0, CPF solicitante padrão e por usuário; endpoints de administração protegidos por permissão própria; senha nunca devolvida.
- Domínios oficiais (motivo de viagem, meio de transporte, tipos de documento etc.): consulta à API e cache local com atualização sob demanda.
- Validador local: documento, código IBGE e nacionalidade (RF-FNRH-06), com erros por campo.
- Porta `FnrhGateway` (SPEC §8.2.17): registrar reserva e hóspedes, check-in, check-out, no-show, cancelamento; adaptador Serpro com timeouts, mapeamento de erros e IDs remotos retornados.
- Log de integração com payload redigido (sem documento completo, sem dados de RN-19), conforme ARCHITECTURE §12.
- Indicador de saúde da integração (ARCHITECTURE §16).

**Fora de escopo:** disparo por eventos e fila (E08); chamadas reais ao Serpro (E09).

**Passos de implementação:**

1. Investigar a documentação v2.4 (H6) e registrar no handoff os endpoints, campos e códigos de erro usados.
2. Escrever testes de contrato com WireMock para cada operação, incluindo erro de validação, `401` e timeout.
3. Implementar configuração, domínios, validador, porta, adaptador e log.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore` ou leitura da documentação oficial com acesso à web): "quais endpoints, autenticação, headers, campos obrigatórios e códigos de erro a API FNRH v2.4 define para reserva, hóspede, check-in, check-out, no-show, cancelamento e domínios?"; "o que ARCHITECTURE §12 e §8.3 exigem de integrações e credenciais?".
- Partes paralelas: nenhuma.
- Foco do verificador: credencial em texto claro no banco ou em log; dado sensível RN-19 no log de integração; `cpf_solicitante` ausente em alguma chamada; testes WireMock que não conferem headers e corpo.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E07 — "Configuração, domínios e adaptador Serpro da FNRH" do roadmap docs/roadmaps/roadmap-m5-recepcao-fnrh.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução", as restrições e as hipóteses H5 e H6 —, a seção E07 inteira, docs/SPEC.md §5.9 e §8.2.17 e docs/ARCHITECTURE.md §12. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que o gate de M4 em docs/roadmaps/roadmap-m4-tarifas-reservas.md está #concluida; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar o módulo fnrh com credenciais cifradas, CPF solicitante padrão e por usuário, domínios oficiais com cache, validação local (documento, IBGE, nacionalidade) e a porta FnrhGateway com adaptador REST do Serpro (Basic Auth, header cpf_solicitante), log de integração redigido e indicador de saúde, tudo testado com WireMock a partir da documentação v2.4. Não ligue eventos nem faça chamadas reais ao Serpro.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E07 para #em-andamento no índice e no título;
- investigação: um subagente Explore (ou você, se precisar de acesso à web) levanta da documentação FNRH v2.4 os endpoints, headers, campos e erros, e resume ARCHITECTURE §12 e §8.3; registre o levantamento no handoff;
- escreva os testes de contrato WireMock primeiro (sucesso, erro de validação, 401, timeout), implemente e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em credencial em claro, dado sensível em log, cpf_solicitante ausente e stubs que não conferem headers e corpo.

Critérios de aceite (detalhes na seção E07):
- cada operação de FnrhGateway tem teste WireMock que confere Basic Auth, cpf_solicitante e corpo;
- credenciais gravadas cifradas e nunca devolvidas pela API;
- validação local rejeita documento, IBGE e nacionalidade inválidos com erros por campo;
- log de integração sem dados de RN-19; build, testes e contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E07 (snapshot, arquivos alterados, levantamento da API, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Cada operação de `FnrhGateway` tem teste WireMock que confere URL, Basic Auth, header `cpf_solicitante` e corpo, e mapeia o ID remoto da resposta.
- [ ] Erro de validação, `401` e timeout do Serpro viram erros tipados do adaptador (teste).
- [ ] Usuário e senha ficam cifrados no banco (teste lê a coluna e não encontra o texto claro); a API de configuração nunca devolve a senha.
- [ ] Validação local rejeita CPF inválido, código IBGE inexistente e nacionalidade fora do domínio, com `errors[]` por campo.
- [ ] O log de integração não contém raça/cor, deficiência nem número completo de documento (teste).
- [ ] Indicador de saúde da FNRH aparece no health (sem derrubar a readiness).
- [ ] Verificação de módulos (`fnrh` depende só de módulos permitidos), build, testes e contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Tarefa de verificação do contrato (CLAUDE.md) → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido. Se a documentação v2.4 estiver inacessível, registre o bloqueio com a ação "usuário fornece o PDF da documentação em `docs/` ou um link acessível; registrar a decisão em H6 e trocar a tag para `#pendente`".

**Riscos e mitigação:**

- API FNRH mudar de versão (ARCHITECTURE §18): adaptador isolado e stubs nomeados pela versão.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E08 — Envio assíncrono e pendências da FNRH — #pendente

**Objetivo:** ligar `fnrh` aos eventos de `reservations` (`ReservationCreated`/`Changed`/`Cancelled`, `GuestCheckedIn`, `GuestCheckedOut`, no-show) para criar `FnrhSubmission`, enviá-las pelo adaptador de E07 com retry e backoff, guardar os IDs remotos (RF-FNRH-04), vincular menores ao responsável (RF-FNRH-05), enviar o número da reserva na OTA (RF-FNRH-07) e expor a fila de pendências (RF-FNRH-03).

**Por que agora:** a FNRH é obrigatória (SPEC §5.9); com os eventos de E01 e o adaptador de E07, o envio pode ser ligado.

**Dependências e paralelismo:** E01, E07. Não paralelizável com E07.

**Escopo:** `fnrh`:

- Listeners `@ApplicationModuleListener` para os eventos de `reservations`; cada evento gera uma `FnrhSubmission` idempotente por (reserva, hóspede, operação).
- Leitura dos dados do hóspede via `guests`, incluindo `SensitiveGuestData` pela API restrita (H4); dados sensíveis só em memória durante o envio.
- Envio por job com ShedLock, retry com backoff exponencial, limite de tentativas e ordem por reserva (check-out não sai antes do check-in).
- `GET /api/v1/fnrh/submissions?status=…` e `POST /api/v1/fnrh/submissions/{id}/retry`, com permissão própria.
- View de leitura `fnrh.report_pending_submissions` (ARCHITECTURE §11) para as pendências do fechamento (M6) e métrica de pendentes.
- Alerta de falha (RF-FNRH-03 e RF-NOT-01 "falha de integração"): quando uma submissão vira `FAILED`, `fnrh` chama a API pública de `notifications` (aresta `fnrh` → `notifications`, permitida pela regra por camadas; acrescentar à tabela da ARCHITECTURE §3.1) para os usuários com a permissão de pendências da FNRH; o aviso não leva dados pessoais.

**Fora de escopo:** chamadas reais (E09); expurgo de dados sensíveis (M10).

**Passos de implementação:**

1. Testes de módulo: cada evento gera a submissão certa; evento repetido não duplica.
2. Testes de integração com WireMock: sucesso grava ID remoto; falha temporária reagenda com backoff; falha permanente fica pendente e visível; ordem check-in → check-out respeitada.
3. Teste: submissão que vira `FAILED` gera uma notificação, sem dados pessoais, para quem tem a permissão de pendências.
4. Implementar listeners, job, endpoints, view, métrica e alerta; acrescentar `notifications` às dependências de `fnrh` na tabela da ARCHITECTURE §3.1.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; padrão no handoff de E07.
- Partes paralelas: nenhuma.
- Foco do verificador: falha da FNRH afetando o check-in; submissão duplicada; dado sensível persistido fora de `guests`; check-out enviado antes do check-in.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E08 — "Envio assíncrono e pendências da FNRH" do roadmap docs/roadmaps/roadmap-m5-recepcao-fnrh.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução", as restrições e a hipótese H4 —, a seção E08 inteira e o "Evidências e handoff" de E01 e E07. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 e E07 estão #concluida e que o código bate com o handoff delas; se não, ou se o código divergiu do plano, pare e relate sem implementar. Se guests não oferecer leitura restrita de SensitiveGuestData (H4), pare e relate.

Objetivo: ligar fnrh aos eventos de reservations (criação, alteração, cancelamento, check-in, check-out, no-show) gerando FnrhSubmission idempotentes, enviadas por job com ShedLock, retry com backoff e ordem por reserva; guardar IDs remotos, vincular menores ao responsável, enviar o número da OTA, expor GET /api/v1/fnrh/submissions e retry manual, a view fnrh.report_pending_submissions, uma métrica de pendentes e o alerta de falha via notifications (aresta fnrh → notifications, permitida pela regra por camadas da ARCHITECTURE §3.1; atualize a tabela). Falha de envio nunca afeta check-in ou check-out.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E08 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de módulo e de integração com WireMock primeiro, implemente e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em falha da FNRH afetando o check-in, duplicidade, dado sensível persistido fora de guests e check-out antes do check-in.

Critérios de aceite (detalhes na seção E08):
- cada evento gera exatamente uma submissão; repetição não duplica;
- sucesso grava o ID remoto; falha temporária reagenda com backoff; falha permanente aparece na listagem de pendências;
- check-in com o Serpro (WireMock) fora do ar conclui normalmente;
- submissão FAILED gera notificação sem dados pessoais;
- build, testes e contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E08 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Cada evento de `reservations` listado gera exatamente uma `FnrhSubmission`; o mesmo evento reprocessado não duplica (teste de módulo).
- [ ] Envio com sucesso grava o ID remoto da reserva e da pessoa (WireMock).
- [ ] Falha temporária reagenda com backoff crescente; após o limite, a submissão fica `FAILED` e aparece em `GET /api/v1/fnrh/submissions?status=FAILED`.
- [ ] `POST …/retry` reenfileira uma submissão falha.
- [ ] Check-out de uma reserva não é enviado antes do check-in dela (teste).
- [ ] Com o WireMock devolvendo `503`, um check-in pelo endpoint de E02 conclui com `200`.
- [ ] Menor é enviado vinculado ao responsável; reserva de OTA envia o número externo.
- [ ] Nenhuma tabela de `fnrh` guarda raça/cor ou deficiência.
- [ ] A view `fnrh.report_pending_submissions` existe e a métrica de pendentes aparece em `/actuator/prometheus`.
- [ ] Submissão que vira `FAILED` gera uma notificação para os usuários com a permissão de pendências da FNRH, sem nome nem documento do hóspede (teste).
- [ ] Build, testes e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Tarefa de verificação do contrato (CLAUDE.md) → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O evento de no-show só existirá com o fechamento do dia (M6): o listener é escrito e testado com o evento publicado em teste; M6 só precisa publicá-lo.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E09 — Homologação da FNRH com o Serpro — #bloqueada

**Objetivo:** executar os envios reais no ambiente de homologação do Serpro (reserva, hóspedes, check-in, check-out, cancelamento e no-show), ajustar o adaptador ao comportamento real e registrar o resultado.

**Por que agora:** os stubs de E07 vêm da documentação; só a homologação confirma o contrato real antes da operação do hotel.

**Dependências e paralelismo:** E08. Não paralelizável.

**Escopo:** configuração das credenciais de homologação por variável de ambiente ou pela API de configuração (nunca no repositório); roteiro de homologação em `docs/` (a criar) com os casos executados e os IDs remotos obtidos; ajustes no adaptador e nos stubs WireMock para refletir o comportamento real.

**Fora de escopo:** credenciais de produção e operação real.

**Passos de implementação:**

1. Confirmar que D1 foi decidida e as credenciais estão disponíveis fora do repositório.
2. Executar o roteiro, registrar respostas e corrigir divergências no adaptador e nos stubs.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação: nenhuma.
- Partes paralelas: nenhuma.
- Foco do verificador: credencial ou dado pessoal real no repositório, nos stubs ou no roteiro; stubs não atualizados após divergência real.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E09 — "Homologação da FNRH com o Serpro" do roadmap docs/roadmaps/roadmap-m5-recepcao-fnrh.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a decisão D1 —, a seção E09 inteira e o "Evidências e handoff" de E07 e E08. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E08 está #concluida, que D1 está decidida e que o usuário forneceu as credenciais de homologação fora do repositório; se não, mantenha a etapa #bloqueada e relate.

Objetivo: executar contra o ambiente de homologação do Serpro os envios de reserva, hóspedes, check-in, check-out, cancelamento e no-show, ajustar o adaptador e os stubs WireMock às divergências reais e registrar o roteiro e os resultados num documento em docs/. Nunca grave credenciais nem dados pessoais reais no repositório; use dados de teste.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E09 para #em-andamento no índice e no título;
- sem investigação delegada;
- execute o roteiro, corrija divergências com testes WireMock atualizados e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em credencial ou dado real no repositório e stubs desatualizados.

Critérios de aceite (detalhes na seção E09):
- todas as operações do roteiro executadas com sucesso na homologação, com IDs remotos registrados;
- divergências corrigidas no adaptador e refletidas nos stubs;
- nenhuma credencial ou dado pessoal real no repositório; build e testes passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E09 (snapshot, arquivos alterados, roteiro e resultados, comandos, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Reserva, hóspedes, check-in, check-out, cancelamento e no-show executados com sucesso na homologação, com IDs remotos registrados no roteiro.
- [ ] Cada divergência real tem correção no adaptador e stub WireMock atualizado.
- [ ] Nenhuma credencial nem dado pessoal real no repositório (busca no diff).
- [ ] Build e testes oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Inspeção manual do diff e do roteiro em busca de credenciais e dados reais.

**Bloqueios:**

- **Causa:** não há credencial de homologação da FNRH nem confirmação de Cadastur ativo.
- **Evidência:** SPEC §10 Q-12 deixa a credencial como "responsabilidade do hotel antes da Fase 1"; nenhuma credencial foi fornecida.
- **Impacto:** impede validar o contrato real com o Serpro; o restante do marco segue com WireMock.
- **Ação de desbloqueio:** o hotel ativa o Cadastur, gera a credencial no Módulo Meio de Hospedagem e o usuário a disponibiliza fora do repositório; registrar a decisão em D1 e trocar a tag desta etapa para `#pendente` (índice e título).

**Riscos e mitigação:**

- Ambiente de homologação instável: registrar horários e respostas; repetir antes de concluir que há divergência.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E10 — Agentes, impressoras e fila de impressão — #pendente

**Objetivo:** criar o módulo `printing` com cadastro de agentes de impressão e impressoras, autenticação do agente por token de serviço (H9), fila de `PrintJob` entregue por SSE com fallback de long-poll, confirmação pelo agente e reenvio de jobs não confirmados (ARCHITECTURE §14, D13).

**Por que agora:** a ficha de registro e o extrato (E11) e, depois, as comandas de produção do PDV (M8) dependem da fila de impressão.

**Dependências e paralelismo:** nenhuma dentro deste roadmap; exige o gate de M4 `#concluida`. Paralelizável com E01–E08 em worktree separado; conflito em `gradle/libs.versions.toml` e `api/openapi.yaml`.

**Escopo:** pacote `printing` (a criar), dependendo só de `shared`:

- `PrintAgent` (nome, último contato, hash do token) e `Printer` (nome, tipo térmica ESC/POS ou A4, endereço na LAN) — SPEC §8.2.18; endpoints de administração; geração e rotação do token exibido uma única vez.
- Autenticação do agente plugada à cadeia de segurança de M1, com permissões mínimas (só receber e confirmar seus jobs).
- `PrintJob` com tipo de documento, impressora, conteúdo renderizado (PDF ou ESC/POS) guardado, status e tentativas; criação por API interna e por `POST /api/v1/print-jobs` com conteúdo já renderizado (os documentos vêm em E11).
- Entrega: `GET /api/v1/print-agents/me/jobs/stream` (SSE com `Last-Event-ID`, infraestrutura de M2) e `GET /api/v1/print-agents/me/jobs?wait=…` (long-poll); `POST /api/v1/print-jobs/{id}/ack` com sucesso ou falha.
- Job com ShedLock que reenvia jobs não confirmados após timeout configurável e marca falha após o limite.

**Fora de escopo:** renderização de documentos (E11); o software do agente (outro repositório).

**Passos de implementação:**

1. Testes de integração: agente autenticado recebe só jobs das suas impressoras; agente sem token → `401`; ack muda status; job sem ack é reenviado; token rotacionado invalida o anterior.
2. Implementar entidades, migração, autenticação, endpoints e job.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "como a infraestrutura de SSE (M2) e a autenticação/credenciais de serviço (M1) foram implementadas, segundo o código e os handoffs?".
- Partes paralelas: nenhuma.
- Foco do verificador: agente acessando jobs de outro agente ou endpoints fora da impressão; token guardado em claro; job perdido quando o agente reconecta.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E10 — "Agentes, impressoras e fila de impressão" do roadmap docs/roadmaps/roadmap-m5-recepcao-fnrh.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a hipótese H9 —, a seção E10 inteira, docs/SPEC.md §5.22 e §8.2.18 e docs/ARCHITECTURE.md §14 e D13. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que o gate de M4 em docs/roadmaps/roadmap-m4-tarifas-reservas.md está #concluida; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar o módulo printing (dependendo só de shared) com cadastro de agentes e impressoras, token de agente guardado como hash e exibido uma vez, autenticação do agente com permissões mínimas, fila de PrintJob entregue por SSE (com Last-Event-ID) e long-poll, ack pelo agente e reenvio de jobs não confirmados por job com ShedLock. Não renderize documentos (E11).

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E10 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume como o SSE (M2) e a autenticação/credenciais de serviço (M1) foram implementados;
- escreva os testes de integração primeiro, implemente, regenere o contrato e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em isolamento entre agentes, token em claro e job perdido na reconexão.

Critérios de aceite (detalhes na seção E10):
- agente recebe só jobs das próprias impressoras, por SSE e por long-poll; sem token → 401;
- ack muda o status; job sem ack é reenviado após o timeout e falha após o limite;
- token guardado como hash; rotação invalida o anterior;
- verificação de módulos, build, testes e contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E10 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Agente autenticado recebe por SSE e por long-poll só os jobs das próprias impressoras; outro agente não os vê (teste).
- [ ] Requisição sem token ou com token revogado → `401`; token de agente não acessa endpoints fora de `printing` (`403`).
- [ ] `POST /api/v1/print-jobs/{id}/ack` muda o status para impresso ou falho.
- [ ] Job sem ack é reenviado após o timeout e marcado como falho após o limite de tentativas.
- [ ] Reconexão com `Last-Event-ID` entrega os jobs pendentes.
- [ ] O token é guardado como hash e exibido só na criação/rotação.
- [ ] `printing` depende só de `shared` (`ModularityTest`); build, testes e contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Tarefa de verificação do contrato (CLAUDE.md) → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Conteúdo binário grande na fila: limitar tamanho e guardar o conteúdo renderizado fora da linha principal da tabela, se necessário.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E11 — Documentos: ficha, extrato e confirmação — #pendente

**Objetivo:** gerar a ficha de registro, o extrato de conta (RF-CTA-12) e a confirmação de reserva em PDF A4 e em ESC/POS, para download (RF-NOT-04) e para impressão pela fila de E10 (RF-NOT-03), no modelo dono + `printing` (D2).

**Por que agora:** RF-REC-01 prevê imprimir a ficha no check-in, e a recepção precisa entregar extrato e confirmação na Fase 1.

**Dependências e paralelismo:** E02, E10. Não paralelizável (toca `printing`, `frontdesk`, `billing` e `reservations`).

**Escopo:**

- `printing`: API pública de renderização e impressão — `render(template, model, format)` devolve o PDF ou o ESC/POS, e `print(template, model, printerId)` cria o `PrintJob` no formato da impressora. Os templates são registrados pelos módulos donos (cada um versiona os seus no próprio pacote de recursos) e `printing` não conhece os modelos de dados de negócio. `printing` compõe o cabeçalho do hotel (razão social, CNPJ, endereço, logotipo) pela API pública de `hotel` (aresta `printing` → `hotel`, permitida porque ambos são Plataforma e não há ciclo; acrescentar à tabela da ARCHITECTURE §3.1).
- Módulos donos, cada um montando os dados, checando a própria permissão e chamando `printing` (arestas já na ARCHITECTURE §3.1):
  - `frontdesk`: ficha de registro (dados da estadia e hóspedes, sem RN-19) em `GET /api/v1/reservations/{id}/registration-card` e `POST …/registration-card/print`;
  - `billing`: extrato em `GET /api/v1/folios/{id}/statement` (PDF por `Accept`) e `POST …/statement/print`;
  - `reservations`: confirmação com as políticas exigidas pela RN-11 em `GET /api/v1/reservations/{id}/confirmation` e `POST …/confirmation/print`.
- Os caminhos exatos seguem a convenção de recursos de M0 E04; ajustar se ela divergir e registrar no handoff.

**Fora de escopo:** comandas de produção e cupons do PDV (M8); relatórios do fechamento (M6, em `reporting`); porta em `shared` para documentos (descartada por D2).

**Passos de implementação:**

1. Testes: cada módulo dono monta os dados certos e aplica sua permissão; PDF gerado contém os campos-chave (extração de texto); ESC/POS gerado contém os comandos de corte e o texto; documento sem permissão → `403`; ficha nunca contém dados de RN-19; `printing` não depende de nenhum módulo de negócio.
2. Implementar a API de renderização e impressão em `printing`, o cabeçalho via `hotel`, os templates e os endpoints nos módulos donos.
3. Atualizar a tabela da ARCHITECTURE §3.1 com `printing` → `hotel` e documentar no `CLAUDE.md` como um módulo registra um template e imprime um documento.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "que campos RF-REC-01, RF-CTA-12, RN-11 e RF-CFG-02 exigem na ficha, no extrato e na confirmação?"; "como E10 expôs a fila de `PrintJob` e como `hotel` (M1) expõe os dados e o logotipo do hotel?".
- Partes paralelas: nenhuma.
- Foco do verificador: `printing` dependendo de módulo de negócio ou escutando eventos; documento acessível sem a permissão do módulo dono; dado de RN-19 ou documento completo em PDF; dados do hotel fixos no código ou no template.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E11 — "Documentos: ficha, extrato e confirmação" do roadmap docs/roadmaps/roadmap-m5-recepcao-fnrh.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução", as restrições, a decisão D2 e a hipótese H8 —, a seção E11 inteira, a regra por camadas em docs/ARCHITECTURE.md §3.1 e o "Evidências e handoff" de E02 e E10. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E02 e E10 estão #concluida e que o código bate com o handoff delas; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: no modelo dono + printing (D2), criar em printing a API pública render e print sobre templates registrados pelos módulos donos, com o cabeçalho do hotel obtido pela API pública de hotel; implementar a ficha de registro em frontdesk, o extrato em billing e a confirmação com as políticas da RN-11 em reservations, cada um com endpoint de PDF e de impressão e checando a própria permissão. printing não conhece dados de negócio nem escuta eventos; nenhum documento contém dados de RN-19. Não crie porta em shared para documentos.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E11 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume os campos exigidos por RF-REC-01, RF-CTA-12, RN-11 e RF-CFG-02 e como E10 e hotel expõem a fila e os dados do hotel;
- escreva os testes primeiro, implemente, acrescente printing → hotel à tabela da ARCHITECTURE §3.1, documente no CLAUDE.md, regenere o contrato e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em printing dependendo de módulo de negócio, documento sem permissão, dado sensível em PDF e dados do hotel fixos.

Critérios de aceite (detalhes na seção E11):
- os três documentos saem em PDF e em PrintJob ESC/POS ou A4, com os campos-chave verificados por teste;
- documento sem a permissão do módulo dono → 403;
- ModularityTest passa com printing dependendo só de shared e hotel;
- ARCHITECTURE §3.1 e CLAUDE.md atualizados; build, testes e contrato passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E11 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Ficha, extrato e confirmação saem em PDF pelos endpoints dos módulos donos; teste extrai o texto e encontra os campos-chave (nome do hóspede, UH, datas, saldo, políticas).
- [ ] O endpoint de impressão de cada documento cria `PrintJob` na impressora escolhida com conteúdo ESC/POS ou PDF conforme o tipo da impressora.
- [ ] Usuário sem a permissão do módulo dono → `403`.
- [ ] Nenhum documento contém raça/cor, deficiência nem número completo de documento além do exigido na ficha (teste).
- [ ] Dados do hotel nos documentos vêm da API pública de `hotel`, não de texto fixo.
- [ ] `ModularityTest` passa; `printing` depende só de `shared` e `hotel` e não tem listener de eventos de outros módulos.
- [ ] A tabela da ARCHITECTURE §3.1 inclui `printing` → `hotel`; o `CLAUDE.md` explica como registrar template e imprimir; build, testes e contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (CLAUDE.md) → sucesso.
- Tarefa de verificação do contrato (CLAUDE.md) → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Templates espalhados por vários módulos podem divergir no visual: `printing` oferece o cabeçalho e o rodapé padrão, e os módulos só preenchem o corpo.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E12 — Gate do marco M5 — #pendente

**Objetivo:** confirmar, a partir de uma cópia limpa do repositório, que a definição global de pronto do M5 é atendida, com um cenário ponta a ponta de recepção, e deixar o roadmap do M6 pronto para ser sincronizado.

**Por que agora:** é o critério de entrada do marco M6.

**Dependências e paralelismo:** E03, E04, E05, E06, E08, E11 (cobrem transitivamente E01, E02, E07 e E10). Não depende de E09: a homologação real depende de terceiro (D1) e fica registrada como pendência obrigatória antes da operação real, sem travar M6.

**Escopo:**

- Rodar do zero numa cópia limpa: build, testes, verificação de módulos e do contrato.
- Teste de integração ponta a ponta (a criar, se não houver): reserva → check-in com menor autorizado → submissão FNRH enviada (WireMock) → ficha impressa (job) → lançamento e pagamento manual → extrato → check-out → submissão de check-out.
- Conferir cada item da "Definição global de pronto"; registrar o estado de E09.
- Atualizar `docs/roadmaps/README.md` registrando a conclusão do M5 no histórico de revisões.

**Fora de escopo:** funcionalidades novas. Problemas encontrados voltam para a etapa de origem.

**Passos de implementação:**

1. Escrever ou executar o teste ponta a ponta.
2. Rodar tudo do zero e marcar a definição global de pronto com evidências.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação: nenhuma.
- Partes paralelas: nenhuma.
- Foco do verificador: item sem evidência; cenário ponta a ponta que pula módulos com mocks; pendência de E09 omitida.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E12 — "Gate do marco M5" do roadmap docs/roadmaps/roadmap-m5-recepcao-fnrh.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a "Definição global de pronto" —, a seção E12 inteira e o "Evidências e handoff" de todas as etapas E01 a E11. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E03, E04, E05, E06, E08 e E11 estão #concluida; se não, ou se o código divergiu do plano, pare e relate.

Objetivo: verificar numa cópia limpa, sem caches nem estado local, cada item da definição global de pronto do M5, incluindo um teste de integração ponta a ponta: reserva → check-in com menor autorizado → submissão FNRH (WireMock) → ficha em PrintJob → lançamento e pagamento manual → extrato → check-out → submissão de check-out. Registre o estado da homologação E09 como pendência obrigatória se ela não estiver concluída. Não implemente funcionalidade nova além do teste ponta a ponta.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E12 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva ou rode o teste ponta a ponta, rode do zero os comandos oficiais de build e teste e a verificação do contrato do CLAUDE.md e registre a evidência de cada item;
- verificação independente com o Prompt do verificador, com foco em item sem evidência, ponta a ponta com mocks entre módulos e pendência de E09 omitida.

Critérios de aceite (detalhes na seção E12):
- todos os itens da definição global de pronto marcados com evidência;
- teste ponta a ponta passa sem mocks entre módulos;
- docs/roadmaps/README.md registra a conclusão do M5 e o estado de E09.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E12 (snapshot, verificações, resultados, parecer do verificador, notas para o marco M6) e responda com um resumo curto. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Todos os itens da "Definição global de pronto" marcados, cada um com evidência numa cópia limpa.
- [ ] O teste ponta a ponta passa usando os módulos reais (só a FNRH em WireMock).
- [ ] O estado de E09 está registrado: concluída, ou pendência obrigatória antes da operação real, com a ação de desbloqueio.
- [ ] `docs/roadmaps/README.md` registra a conclusão do M5 no histórico de revisões.

**Validação obrigatória:**

- Comandos oficiais de build e teste e verificação do contrato (CLAUDE.md) numa cópia limpa → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O teste ponta a ponta pode ficar lento: mantê-lo único e marcado como teste de integração.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para o marco M6: —

---

## Histórico de revisões

- 2026-10-13 — roadmap criado.
- 2026-10-13 — aplicada a regra por camadas da ARCHITECTURE §3.1 e as decisões da entrevista: H7 virou D2 (dono + printing) e E11 foi reescrita sem porta em `shared`, com `printing` → `hotel` para o cabeçalho; E08 ganhou o alerta de falha via `notifications` (RF-FNRH-03, RF-NOT-01); contexto e restrições atualizados (`frontdesk` → `hotel`, `printing`). Recados seguem sem push porque a SPEC não pede.
