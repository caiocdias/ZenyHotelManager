# Roadmap — M3 Hóspedes, contas e caixa

> Arquivo: `docs/roadmaps/roadmap-m3-hospedes-contas.md` · criado em 2026-10-13 · última revisão 2026-10-13
>
> Marco 3 de 11 · mapa dos marcos em [`docs/roadmaps/README.md`](README.md) · marco anterior: [M2 — Plataforma operacional](roadmap-m2-plataforma-operacional.md) · próximo marco: [M4 — Tarifas, disponibilidade e reservas](roadmap-m4-tarifas-reservas.md)
>
> **Pré-requisito:** etapa de gate do marco M2 (última etapa de `docs/roadmaps/roadmap-m2-plataforma-operacional.md`) marcada `#concluida`.

## Como usar

Duas formas, ambas na raiz do repositório:

- **Manual:** cole o prompt de uma etapa numa sessão limpa do Claude Code. Uma etapa por sessão.
- **Automática:** cole o prompt da seção "Execução automática" numa sessão limpa; ela executa até 3 etapas em sequência, um subagente por etapa mais um verificador, e para para revisão. Para não parar em cada pedido de permissão, libere antes os comandos de build/teste do projeto nas permissões do Claude Code (os comandos oficiais estão no `CLAUDE.md`, e o Docker é necessário para os testes de integração).

**Este roadmap foi escrito antes do código existir.** Antes da primeira etapa, sincronize-o com o código real usando a skill `development-roadmap` (modo atualização): confira caminhos, comandos, nomes de permissões, mecanismo de auditoria e convenções registrados nos handoffs de M0, M1 e M2, e ajuste escopo, prompts e critérios das etapas afetadas.

Etapas marcadas como paralelizáveis só devem rodar ao mesmo tempo em worktrees git separados; na mesma pasta, rode-as uma de cada vez.

Em ambas, quem executa segue o "Protocolo de execução", atualiza a tag da etapa e preenche "Evidências e handoff". Não pule dependências. Revise e faça commit entre etapas ou lotes — nenhuma sessão faz commit por conta própria.

## Objetivo

Entregar os módulos `guests`, `payments` (só o registro manual) e `billing` (sem contas a receber). Ao fim do marco, a API permite cadastrar hóspedes e empresas com proteção de dados pessoais (LGPD), abrir contas para qualquer pagador, lançar débitos imutáveis com estorno e ajuste sob aprovação, rotear e transferir lançamentos, operar turnos de caixa, registrar pagamentos manuais, depósitos, reembolsos e caução, e encerrar contas com saldo zero. É a base financeira que as reservas (M4) e a recepção (M5) vão usar. Nenhuma nota fiscal é emitida neste marco: até M7 as notas são emitidas manualmente no portal da prefeitura (SPEC §9).

## Contexto confirmado

- Requisitos: SPEC §5.5 (HOS), §5.10 (CTA, exceto RF-CTA-13, contas a receber, Fase 4), §5.11 RF-PAG-03 (registro manual) e RF-PAG-06 (provedores intercambiáveis); regras RN-01, RN-02, RN-03, RN-06, RN-10, RN-12, RN-19 e RN-20 (SPEC §6); RNF-09, RNF-10 e RNF-11 (SPEC §7).
- Modelo de domínio: SPEC §8.2.4 (`Guest`, `IdDocument`, `SensitiveGuestData`, `Company`, `Consent`, `Attachment`) e §8.2.7 (`Folio`, `FolioOwner`, `Posting`, `TransactionCode`, `RoutingRule`, `Payment`, `PaymentMethod`, `CardPreAuthorization`, `CashPoint`, `CashierShift`, `CashMovement`, `PaymentIntent`). Eventos: `PaymentSucceeded` (Pagamentos → Contas, SPEC §8.3).
- Módulos e dependências permitidas (ARCHITECTURE §3.1, com a regra por camadas): `guests` → `shared`, `files`; `payments` → `shared`; `billing` → `shared`, `hotel`, `guests`, `payments`, `fiscal`, `printing`. Qualquer módulo pode chamar módulos da Plataforma (`files`, `notifications`, `printing`, `hotel`…) sem ciclo, acrescentando a aresta à tabela §3.1 na mesma mudança. Como `fiscal` só chega em M7, `billing` não chama `fiscal` neste marco. Uma conta referencia seu dono por `FolioOwner(type, id)` e não conhece reservas (ARCHITECTURE §3.1).
- Persistência: ARCHITECTURE §11 (schema por módulo, sem FK entre schemas, `numeric(14,2)`, imutabilidade financeira sem `UPDATE`/`DELETE` para o usuário da aplicação, views `report_*` como API de leitura).
- Segurança e privacidade: ARCHITECTURE §8.2 (permissões por módulo, escopo, limites, step-up) e §8.3 (cifragem de campo para número de documento e dados sensíveis da FNRH, anexos sensíveis por URL assinada com acesso auditado, exportação auditada, PCI-DSS).
- API: ARCHITECTURE §7 (`Idempotency-Key` obrigatório em POSTs com efeito financeiro; `ETag`/`If-Match`; erros com `code` estável).
- Convenções herdadas, documentadas no `CLAUDE.md`: comandos oficiais de build, teste e lint, estrutura de módulo, migrações, erros, paginação, `Money`, IDs, números legíveis, cifragem de campo e idempotência (M0); autenticação, `authz.require`, escopo, limites, step-up por PIN, auditoria `@Audited`, data operacional e `files` (M1); SSE e notificações (M2).
- Pacote base `com.zeny.hotel` (hipótese herdada de M0, confirmar no `CLAUDE.md`). Caminhos de código citados abaixo são "(a criar)".

## Escopo

**Incluído:**

- `guests`: perfis de hóspede com campos da FNRH, documentos cifrados, dados sensíveis da FNRH cifrados e nunca expostos, preferências, VIP, lista de restrição, empresas, detecção e mesclagem de duplicados, consentimentos, anexos restritos, exportação auditada dos dados do titular, correção e anonimização sob pedido, view de leitura.
- `payments`: `PaymentIntent` neutro com porta de provedor e um provedor `MANUAL`; evento `PaymentSucceeded`.
- `billing`: códigos de lançamento, contas por pagador, lançamentos imutáveis, estorno e ajuste com aprovação e limites, roteamento, transferência e divisão, taxa de serviço removível, pontos de caixa e turnos, pagamentos manuais com troco, depósitos, reembolsos, caução registrada manualmente, encerramento e reabertura, extrato em JSON e views de leitura.

**Fora de escopo:**

- Histórico consolidado do hóspede (RF-HOS-03): depende de reservas e consumo; vem com `reporting` em M6, a partir das views `report_*`.
- Anonimização automática por retenção e expurgo dos dados sensíveis da FNRH (RN-19, RN-20, automação): M10.
- Contas a receber, faturas e método `CITY_LEDGER` (RF-CTA-13): M10.
- Pix, cartão integrado, tokenização, conciliação (RF-PAG-01, 02, 04, 05) e renovação de caução: M7.
- Emissão fiscal, perfil fiscal dos códigos de lançamento e vínculo de notas: M7.
- Extrato e confirmação em PDF (RF-CTA-12, parte PDF): M5. O dono do documento monta os dados e chama `printing` (`billing` → `printing` para o extrato); por isso o extrato em JSON de E10 já deve ter os dados completos.
- Abertura de conta a partir de reserva, multas de cancelamento e no-show: M4 e M6 usam a API deste marco.
- Lançamentos vindos de PDV, fechamento, lavanderia ou eventos: marcos desses módulos.

## Restrições e invariantes

- Respeitar o grafo de módulos da ARCHITECTURE §3.1 e a regra por camadas: `guests` não chama `billing`, `payments` não chama `billing`, `billing` não conhece reservas (todos seriam dependências para cima ou na mesma camada). Reações entre módulos vão por evento ou porta.
- RN-03: lançamentos e pagamentos nunca são editados nem excluídos; correção só por estorno ou ajuste vinculado. As tabelas de lançamentos, pagamentos e movimentos de caixa ficam sem `UPDATE`/`DELETE` para o usuário da aplicação, com o mesmo mecanismo usado pela trilha de auditoria em M1.
- RN-02: dinheiro só com o tipo `Money` de `shared`; nada de ponto flutuante.
- RN-01: todo lançamento leva a data operacional corrente do módulo `hotel`.
- RN-19 e RNF-09: raça/cor e deficiência ficam cifradas, não aparecem em nenhuma resposta da API de consulta, em logs, nem em views `report_*`. Número de documento é cifrado.
- RNF-10 (PCI-DSS): nunca receber nem guardar número de cartão ou CVV; só bandeira, 4 últimos dígitos, NSU e autorização.
- Toda escrita é auditada (RNF-11). Ações sensíveis de RF-ACS-08 deste marco (estorno/ajuste de lançamento, desconto além do limite, reabrir conta fechada, exportar dados pessoais) exigem permissão ou step-up.
- `Idempotency-Key` obrigatório em todo POST que cria lançamento, pagamento, reembolso, caução ou movimento de caixa.
- Contrato `api/openapi.yaml` regenerado e versionado a cada etapa que mexe na API.

## Hipóteses e decisões em aberto

| # | Hipótese ou decisão | Impacto se errada / sem decisão | Etapa afetada |
|---|---|---|---|
| H1 | Todo pagamento passa por um `PaymentIntent` do módulo `payments`. No registro manual, `billing` chama `payments` de forma síncrona na mesma transação (provedor `MANUAL`, confirmado na hora); `payments` publica `PaymentSucceeded`; o listener de `billing` para esse evento é idempotente e ignora intents já registrados, para que M7 só acrescente provedores assíncronos. | Se a revisão humana preferir outro desenho, muda só E08 e a forma de M7 integrar. | E08 |
| H2 | Até M7, `TransactionCode` guarda uma classificação fiscal opcional em texto livre; o vínculo com `FiscalProfile` chega em M7 sem migração destrutiva. | Coluna renomeada ou convertida em M7. | E04 |
| H3 | A exportação de dados do titular usa uma porta definida em `guests` (contribuidores de dados pessoais) que módulos acima implementam. Em M3 só `guests` e anexos contribuem; `billing` e marcos seguintes acrescentam os seus. | Exportação incompleta até os demais módulos contribuírem; registrado como pendência nos marcos seguintes. | E03 |
| H4 | Faixas etárias, taxa de serviço por ponto de venda e por código e ISS vêm da configuração do `hotel` entregue em M1 (RF-CFG-02, RF-CFG-03). | Se M1 não expôs essas configurações na API pública do `hotel`, a etapa para e relata. | E06 |
| H5 | SPEC Q-08 segue o padrão adotado: taxa de serviço só em A&B por configuração, retenção configurável; o motor em `billing` é genérico por código de lançamento. | Mudança de política é só configuração. | E06 |
| H6 | Mesclagem de duplicados mantém o perfil sobrevivente, marca o absorvido como mesclado (sem excluir) e publica um evento `GuestsMerged`, que módulos acima de `guests` escutam para repontar referências. | Sem o evento, reservas antigas apontariam para o perfil absorvido; o evento evita reescrever outros schemas. | E02 |

## Definição global de pronto

- [ ] Hóspedes e empresas podem ser cadastrados, buscados, corrigidos, mesclados, exportados e anonimizados pela API, com permissões, escopo e auditoria; nenhum endpoint, log ou view devolve raça/cor, deficiência ou número de documento em claro fora do uso autorizado.
- [ ] Uma conta aberta para qualquer `FolioOwner` aceita lançamentos, estornos, ajustes, roteamento, transferências, taxa de serviço, pagamentos manuais, reembolsos e caução, e só fecha com saldo zero.
- [ ] Tentativa de `UPDATE` ou `DELETE` em lançamentos, pagamentos e movimentos de caixa com o usuário da aplicação falha no banco (teste de integração).
- [ ] Recebimento sem turno de caixa aberto do usuário é recusado com `code` estável.
- [ ] Estorno, ajuste, desconto além do limite, reabertura de conta e exportação de dados pessoais exigem permissão ou step-up e ficam auditados com solicitante e aprovador.
- [ ] `ApplicationModules.verify()` passa, sem dependência fora da ARCHITECTURE §3.1.
- [ ] Contrato `api/openapi.yaml` atualizado; build, testes e lint oficiais passam.

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
Você é o verificador independente da etapa ETAPA do roadmap docs/roadmaps/roadmap-m3-hospedes-contas.md. Você não participou da implementação e não deve editar nenhum arquivo.
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
Você é o orquestrador do roadmap docs/roadmaps/roadmap-m3-hospedes-contas.md neste repositório. Seu papel é coordenar subagentes, não implementar: não edite código e mantenha seu contexto enxuto (leia o roadmap e os relatórios dos subagentes, não o código).

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

**Hóspedes e clientes (`guests`)**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E01 | Perfis de hóspedes e empresas | #pendente | — | Módulo `guests` com hóspedes, documentos cifrados, dados sensíveis protegidos, VIP, lista de restrição e empresas |
| E02 | Detecção e mesclagem de duplicados | #pendente | E01 | Sugestão de duplicados e mesclagem sem perda de histórico, com evento `GuestsMerged` |
| E03 | LGPD: consentimentos, anexos e direitos do titular | #pendente | E01 | Consentimentos, anexos restritos, exportação auditada, correção e anonimização sob pedido, view de leitura |

**Contas e caixa (`billing`, `payments`)**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E04 | Códigos de lançamento, contas e lançamentos imutáveis | #pendente | E01 | Módulo `billing` com `TransactionCode`, `Folio`, `Posting`, estorno e ajuste sob aprovação |
| E05 | Roteamento, transferência e divisão | #pendente | E04 | Regras de roteamento aplicadas no lançamento; transferência e divisão manuais |
| E06 | Taxa de serviço | #pendente | E04 | Taxa de serviço automática por código, separada na conta e removível com registro |
| E07 | Pontos e turnos de caixa | #pendente | E04 | Abertura, sangria, suprimento e fechamento (inclusive cego) de turnos |
| E08 | Pagamentos manuais, depósitos e reembolsos | #pendente | E07 | Módulo `payments` com `PaymentIntent` e provedor `MANUAL`; pagamentos na conta com troco |
| E09 | Caução registrada manualmente | #pendente | E08 | `CardPreAuthorization` manual com captura total ou parcial e liberação |
| E10 | Encerramento, reabertura, extrato e views de leitura | #pendente | E05, E06, E09 | Ciclo de vida da conta, extrato em JSON e views `report_*` de `billing` |
| E11 | Gate do marco M3 | #pendente | E02, E03, E10 | Definição global de pronto verificada do zero |

---

## E01 — Perfis de hóspedes e empresas — #pendente

**Objetivo:** criar o módulo `guests` com o cadastro de hóspedes (pessoa física) com os campos exigidos pela FNRH, documentos com número cifrado, dados sensíveis da FNRH cifrados e invisíveis na API de consulta, preferências, VIP, lista de restrição e cadastro de empresas.

**Por que agora:** `billing` (E04) usa empresas como pagadoras, e reservas (M4) e recepção (M5) dependem de hóspedes. É também o primeiro módulo com dados pessoais em volume; o padrão de proteção definido aqui se repete nos marcos seguintes.

**Dependências e paralelismo:** nenhuma dentro deste roadmap; exige o gate de M2 `#concluida`. Não paralelizável.

**Escopo:**

- Pacote `guests` (a criar) com API pública na raiz (consultas por ID, busca, verificação de lista de restrição, leitura dos dados exigidos pela FNRH para uso exclusivo do módulo `fnrh` em M5) e subpacotes `domain`, `application`, `infrastructure`, `web`.
- Schema e pasta de migração `guests` conforme a convenção de M0.
- `Guest` (RF-HOS-01, RF-HOS-02): nome, nome social, nacionalidade ISO, data de nascimento, gênero, e-mail, telefone, país de residência, `Address` com IBGE e UF; `isMinorOn(date)`; preferências, VIP e observações. Restrições alimentares só são aceitas com consentimento registrado (o consentimento em si chega em E03: até lá o campo fica recusado com `code` estável).
- `IdDocument` (CPF, passaporte e outros) com número cifrado pela cifragem de campo de `shared`; CPF validado com o tipo `Cpf`. Busca por documento por hash determinístico ou índice cego, sem decifrar a tabela inteira.
- `SensitiveGuestData` (raça/cor, deficiência) cifrado, gravável pela API de cadastro e **nunca** devolvido nas respostas de consulta (RN-19).
- Lista de restrição (RF-HOS-04) com motivo, como ação sensível com permissão própria; método da API pública para módulos acima consultarem o alerta.
- `Company` (RF-HOS-06): razão social, nome fantasia, `Cnpj`, IE/IM, endereço, contatos, e-mail de cobrança, limite de crédito em `Money`.
- Endpoints REST em `/api/v1/guests` e `/api/v1/companies` com busca por nome, documento e e-mail, paginação por cursor e `ETag`.
- Permissões no catálogo (`guests.*`, `companies.*`) e auditoria `@Audited` em toda escrita, seguindo as convenções de M1.

**Fora de escopo:** mesclagem (E02); consentimentos, anexos e direitos do titular (E03); histórico de estadias (M6).

**Passos de implementação:**

1. Confirmar no `CLAUDE.md` e nos handoffs de M1 como declarar permissões, aplicar escopo e auditar.
2. Escrever testes de domínio (`isMinorOn`, validação de documento) e de integração da API (cadastro, busca por documento, ausência dos dados sensíveis nas respostas, 403 sem permissão).
3. Implementar migração, domínio, casos de uso, controllers e permissões.
4. Regenerar `api/openapi.yaml`.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "quais campos e regras a SPEC §5.5 (RF-HOS-01, 02, 04, 06), RN-19, RN-24 e §8.2.4 definem para hóspede, documento e empresa?"; "quais campos a FNRH exige do hóspede segundo a SPEC §5.9?"; "como M1 registrou permissões, escopo, auditoria e cifragem no CLAUDE.md e nos handoffs?".
- Partes paralelas: nenhuma.
- Foco do verificador: raça/cor, deficiência ou número de documento aparecendo em resposta, log, mensagem de erro ou contrato OpenAPI; busca por documento que decifra tudo; escrita sem auditoria; empresa com CNPJ inválido aceita.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E01 — "Perfis de hóspedes e empresas" do roadmap docs/roadmaps/roadmap-m3-hospedes-contas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Como usar", restrições, hipóteses e o "Protocolo de execução" —, a seção E01 inteira e docs/SPEC.md §5.5, RN-19 e §8.2.4. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que a última etapa (gate) de docs/roadmaps/roadmap-m2-plataforma-operacional.md está #concluida; se não estiver, ou se o código divergiu do que este roadmap assume (convenções de M0, M1 e M2 no CLAUDE.md), pare e relate sem implementar.

Objetivo: criar o módulo guests com hóspedes (campos da FNRH, preferências, VIP), documentos com número cifrado e busca sem decifrar a tabela, dados sensíveis da FNRH (raça/cor, deficiência) cifrados e nunca devolvidos na API de consulta, lista de restrição como ação com permissão própria e cadastro de empresas, em /api/v1/guests e /api/v1/companies. Não implemente mesclagem, consentimentos, anexos nem exportação (E02 e E03).

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E01 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume SPEC §5.5, §5.9 (campos da FNRH), RN-19, RN-24 e §8.2.4 e as convenções de permissões, escopo, auditoria e cifragem registradas por M1;
- escreva os testes de domínio e de integração primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em dado sensível ou número de documento exposto em resposta, log ou contrato, busca que decifra tudo e escrita sem auditoria.

Critérios de aceite (detalhes na seção E01):
- cadastro, busca por nome, documento e e-mail e edição com ETag cobertos por testes de integração;
- nenhuma resposta da API de consulta nem o contrato expõem raça/cor, deficiência ou documento em claro;
- escrita sem permissão → 403; toda escrita auditada;
- CPF e CNPJ inválidos → 400 com code estável; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E01 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] `POST`, `GET`, `PUT` (com `If-Match`) e busca paginada de `/api/v1/guests` e `/api/v1/companies` cobertos por testes de integração com Testcontainers.
- [ ] Busca por número de documento encontra o hóspede sem decifrar todos os registros (teste e inspeção da consulta).
- [ ] Raça/cor e deficiência são gravadas cifradas (conferido no banco) e não aparecem em nenhuma resposta de consulta, no `api/openapi.yaml` de leitura nem em logs (teste).
- [ ] CPF e CNPJ inválidos → `400` com `code` estável.
- [ ] Incluir ou retirar da lista de restrição sem a permissão própria → `403`; com ela, auditado com motivo.
- [ ] Toda escrita gera registro de auditoria com antes e depois (dados cifrados não aparecem em claro na auditoria).
- [ ] `isMinorOn(date)` testado nos limites de idade.
- [ ] `ApplicationModules.verify()` passa; contrato regenerado; build, testes e lint oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint (`CLAUDE.md`) → sucesso.
- Tarefa de verificação do contrato → sucesso.
- Inspeção manual: `grep` por `raceColor`/`disability` (ou nomes equivalentes) nos DTOs de resposta e no `api/openapi.yaml` → nenhum campo de leitura.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Busca por documento cifrado: usar hash com segredo (HMAC) de valor normalizado; registrar a decisão no handoff, pois `fnrh` e `reservations` vão buscar por documento.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E02 — Detecção e mesclagem de duplicados — #pendente

**Objetivo:** detectar perfis de hóspede duplicados (mesmo documento ou e-mail) e mesclá-los num perfil sobrevivente sem excluir o absorvido e sem perder histórico, publicando `GuestsMerged` para os módulos que referenciam hóspedes (RF-HOS-05, H6).

**Por que agora:** reservas (M4) e contas passam a referenciar hóspedes; o evento de mesclagem precisa existir antes que essas referências se multipliquem.

**Dependências e paralelismo:** E01. Pode correr em paralelo com E03 e com E04 (em worktrees separados); conflito de edição em `api/openapi.yaml` (regenerar ao integrar) e, com E03, no agregado `Guest`.

**Escopo:**

- Consulta de candidatos a duplicado por documento (hash de E01) e e-mail normalizado.
- Comando de mesclagem com permissão própria: sobrevivente recebe documentos, contatos e preferências escolhidos; absorvido fica com status "mesclado" e referência ao sobrevivente; leituras do absorvido redirecionam ou informam o sobrevivente.
- Evento `GuestsMerged(survivorId, mergedId)` publicado pelo outbox, documentado na API pública de `guests`.
- Auditoria da mesclagem com o estado anterior dos dois perfis.

**Fora de escopo:** repontar referências em outros módulos (cada módulo acima escuta o evento no seu marco); mesclagem de empresas.

**Passos de implementação:**

1. Testes de domínio da mesclagem (regras de escolha de campos, absorvido não excluído) e de integração (candidatos, endpoint, evento registrado no outbox).
2. Implementar consulta, comando, evento e endpoint.
3. Regenerar o contrato e documentar o evento no `CLAUDE.md` ou na API pública do módulo.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regra em RF-HOS-05 e H6.
- Partes paralelas: nenhuma.
- Foco do verificador: perfil absorvido excluído fisicamente; mesclagem de um perfil com ele mesmo ou de um já mesclado; evento publicado fora da transação; documento duplicado após a mesclagem.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E02 — "Detecção e mesclagem de duplicados" do roadmap docs/roadmaps/roadmap-m3-hospedes-contas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo a hipótese H6 e o "Protocolo de execução" —, a seção E02 inteira, o "Evidências e handoff" de E01 e RF-HOS-05 em docs/SPEC.md §5.5. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: listar candidatos a duplicado por documento e e-mail e mesclar dois perfis de hóspede num sobrevivente, marcando o absorvido como mesclado (sem excluí-lo), auditando o estado anterior e publicando GuestsMerged pelo outbox. Não altere outros módulos: quem referencia hóspedes escuta o evento no seu próprio marco.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E02 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de domínio e de integração primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em exclusão física do absorvido, mesclagem de perfil consigo mesmo ou já mesclado e evento fora da transação.

Critérios de aceite (detalhes na seção E02):
- candidatos a duplicado por documento e por e-mail retornados pela API;
- mesclagem mantém o absorvido com status mesclado e referência ao sobrevivente, auditada;
- GuestsMerged registrado no outbox na mesma transação;
- mesclar perfil consigo mesmo ou já mesclado → 409 com code estável; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E02 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Endpoint de candidatos devolve pares com mesmo documento ou mesmo e-mail normalizado (teste de integração).
- [ ] Mesclagem com permissão: sobrevivente atualizado, absorvido com status mesclado e referência ao sobrevivente, nenhuma linha excluída (teste).
- [ ] Sem permissão → `403`; mesclar consigo mesmo ou perfil já mesclado → `409` com `code` estável.
- [ ] `GuestsMerged` registrado no *Event Publication Registry* na mesma transação (teste com `Scenario` ou equivalente).
- [ ] Mesclagem auditada com o estado anterior dos dois perfis.
- [ ] Contrato regenerado; build, testes e lint oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Tarefa de verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Consumidores futuros esquecerem de escutar `GuestsMerged`: registrar no handoff e no `CLAUDE.md` que todo módulo que guarda ID de hóspede deve escutar o evento.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E03 — LGPD: consentimentos, anexos e direitos do titular — #pendente

**Objetivo:** completar `guests` com consentimentos por finalidade, anexos de hóspede com acesso restrito e auditado e os direitos do titular atendidos sob pedido: exportação auditada (ação sensível), correção e anonimização respeitando retenções legais, mais a view de leitura `report_*` do módulo (RF-HOS-07, RF-HOS-08, RF-ACS-08).

**Por que agora:** a LGPD vale desde o primeiro hóspede cadastrado. A porta de exportação (H3) precisa existir para que `billing` e os marcos seguintes contribuam com seus dados.

**Dependências e paralelismo:** E01. Pode correr em paralelo com E02 e com E04 (em worktrees separados); conflito de edição em `api/openapi.yaml` e, com E02, no agregado `Guest`.

**Escopo:**

- `Consent` com finalidade, concedido/revogado, instante e origem; restrições alimentares (RF-HOS-02) passam a ser aceitas só com consentimento ativo.
- Anexos (documento de identidade, autorização de menor) pelo módulo `files` de M1, marcados como sensíveis, servidos por URL assinada curta, com permissão própria e todo acesso auditado.
- Porta de contribuidores de dados pessoais (H3) na API pública de `guests`; implementação para os dados do próprio `guests` e anexos.
- Exportação dos dados de um titular: ação sensível (permissão ou step-up conforme M1), gera pacote (JSON, e anexos se pedido) guardado em `files`, auditada.
- Correção de dados a pedido do titular (já coberta pela edição de E01; aqui registrar a origem "pedido do titular" na auditoria).
- Anonimização sob pedido: remove ou substitui dados pessoais e sensíveis, mantém o ID e o que a retenção legal exige (RN-20), bloqueada se houver estadia ativa informada por porta ou consulta permitida (sem `guests` depender de módulos acima: a checagem de estadia ativa fica como porta implementada em M4/M5; em M3 a porta tem implementação padrão que não bloqueia, registrada como pendência).
- View `guests.report_guests` sem dados sensíveis nem documento em claro.

**Fora de escopo:** anonimização automática por prazo e expurgo dos dados sensíveis da FNRH (M10).

**Passos de implementação:**

1. Testes de integração: consentimento e restrição alimentar; upload e download de anexo com auditoria; exportação exige step-up e gera pacote; anonimização remove dados pessoais e preserva o ID; view sem dados sensíveis.
2. Implementar consentimentos, anexos, porta de contribuidores, exportação, anonimização e view.
3. Regenerar o contrato; documentar a porta de contribuidores e a porta de estadia ativa no `CLAUDE.md`.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "o que a SPEC §5.5 (RF-HOS-02, 07, 08), RN-19, RN-20, RNF-09 e a ARCHITECTURE §8.3 e §14 exigem de consentimento, anexos, exportação e anonimização?"; "como o módulo files de M1 expõe upload, URL assinada, finalidade e auditoria de acesso (handoffs de M1)?".
- Partes paralelas: nenhuma.
- Foco do verificador: exportação sem step-up ou sem auditoria; anexo sensível acessível por URL longa ou sem auditoria; anonimização que deixa dado pessoal em outra coluna, no histórico de auditoria exposto pela API ou na view; view `report_*` com documento ou dado sensível.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E03 — "LGPD: consentimentos, anexos e direitos do titular" do roadmap docs/roadmaps/roadmap-m3-hospedes-contas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo a hipótese H3 e o "Protocolo de execução" —, a seção E03 inteira e o "Evidências e handoff" de E01. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: acrescentar ao módulo guests consentimentos por finalidade (restrição alimentar só com consentimento), anexos sensíveis via módulo files com URL assinada curta e acesso auditado, a porta de contribuidores de dados pessoais, exportação dos dados do titular como ação sensível com step-up e auditoria, anonimização sob pedido que preserva o ID e as retenções legais, e a view guests.report_guests sem dados sensíveis. Não implemente anonimização automática nem expurgo por prazo (marco M10), e não faça guests depender de módulos acima dele.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E03 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume SPEC RF-HOS-02, 07 e 08, RN-19, RN-20, RNF-09, ARCHITECTURE §8.3 e §14 e a API do módulo files registrada nos handoffs de M1;
- escreva os testes de integração primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em exportação sem step-up, anexo acessível sem auditoria, anonimização incompleta e view com dado sensível.

Critérios de aceite (detalhes na seção E03):
- restrição alimentar recusada sem consentimento ativo e aceita com ele;
- download de anexo sensível só por URL assinada curta, com acesso auditado;
- exportação sem permissão nem aprovação → 403 APPROVAL_REQUIRED ou equivalente; com aprovação gera pacote auditado;
- anonimização remove dados pessoais e sensíveis e mantém o ID; view sem documento nem dado sensível; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E03 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Consentimento concedido e revogado por finalidade, com instante e origem; restrição alimentar sem consentimento ativo → `422` com `code` estável (teste).
- [ ] Anexo sensível só é baixado por URL assinada de curta duração e cada acesso gera registro de auditoria (teste).
- [ ] Exportação sem permissão nem aprovação → `403` com o `code` de aprovação definido em M1; com aprovação, gera pacote em `files` e auditoria com solicitante e aprovador.
- [ ] A porta de contribuidores de dados pessoais está na API pública de `guests` e documentada no `CLAUDE.md`.
- [ ] Anonimização sob pedido apaga ou substitui nome, contatos, documento, endereço e dados sensíveis, mantém o ID e é auditada; leitura posterior não devolve nenhum dado pessoal (teste).
- [ ] `guests.report_guests` existe e não contém documento, raça/cor nem deficiência (teste que lê a view).
- [ ] Contrato regenerado; build, testes e lint oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Tarefa de verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- A trilha de auditoria guarda antes e depois e pode conter dados pessoais do anonimizado: verificar como M1 trata isso e registrar no handoff; se a auditoria expuser dados anonimizados, abrir pendência para M10.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E04 — Códigos de lançamento, contas e lançamentos imutáveis — #pendente

**Objetivo:** criar o módulo `billing` com códigos de lançamento, contas (`Folio`) para qualquer `FolioOwner`, lançamentos imutáveis na data operacional e correção por estorno ou ajuste vinculados, com permissão, limites e step-up (RF-CTA-01, 02, 03; RN-01, RN-02, RN-03).

**Por que agora:** é o núcleo financeiro: tudo o que vem depois (roteamento, caixa, pagamentos, reservas, PDV, fiscal) lança nesta conta. A garantia de imutabilidade no banco precisa nascer com as tabelas.

**Dependências e paralelismo:** E01 (empresa como pagadora). Pode correr em paralelo com E02 e E03 (em worktrees separados); conflito de edição em `api/openapi.yaml`.

**Escopo:**

- Pacote `billing` (a criar), schema e pasta de migração próprios.
- `TransactionCode`: código, nome, tipo (débito, crédito, multa, taxa…), grupo de receita, `serviceChargeApplies`, classificação fiscal opcional (H2), ativo. Códigos padrão pré-carregados por migração (diária, multa de cancelamento, multa de no-show, taxa de serviço, A&B, lavanderia, avulso), editáveis pelo administrador.
- `Folio` com número legível (gerador de `shared`), `FolioOwner(type, id)` com tipos iniciais (reserva, grupo, evento, empresa, avulsa), pagador opcional (`Company` via API de `guests`), limite de crédito, status aberta/fechada, `balance()`, `version` para `ETag`.
- `Posting` imutável: código, quantidade, valor unitário, valor, descrição, origem, data operacional (via `hotel`), usuário, instante.
- Estorno (lançamento inverso vinculado) e ajuste com motivo: ação sensível com permissão, limite de valor (RF-ACS-06) e step-up quando acima do limite ou sem permissão; auditoria com solicitante e aprovador.
- Banco: sem `UPDATE`/`DELETE` para o usuário da aplicação nas tabelas de lançamentos, usando o mesmo mecanismo da auditoria de M1.
- Endpoints em `/api/v1/folios` e `/api/v1/transaction-codes`; `Idempotency-Key` obrigatório nos POSTs de lançamento, estorno e ajuste.
- API pública de `billing` para módulos acima abrirem contas e lançarem (`openFolio`, `post`), já pensando em M4.

**Fora de escopo:** roteamento e transferência (E05); taxa de serviço (E06); pagamentos (E08); encerramento (E10).

**Passos de implementação:**

1. Testes de domínio de `Folio` (saldo, estorno vinculado, ajuste) e de integração (imutabilidade no banco, data operacional, idempotência, step-up, auditoria).
2. Implementar migração com permissões de banco, domínio, casos de uso, controllers e permissões no catálogo.
3. Regenerar o contrato e documentar no `CLAUDE.md` como outros módulos abrem conta e lançam.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "o que SPEC §5.10 (RF-CTA-01 a 03, 11), §8.2.7, RN-01, RN-02, RN-03 e ARCHITECTURE §6.3, §8.2 e §11 exigem de contas, lançamentos, estorno e aprovação?"; "como M1 implementou limites, step-up (X-Approval-Token) e a proteção append-only da auditoria (handoffs de M1)?".
- Partes paralelas: nenhuma.
- Foco do verificador: `UPDATE`/`DELETE` possível em lançamentos com o usuário da aplicação; estorno de estorno ou estorno parcial sem regra; ajuste acima do limite sem aprovação; lançamento com data de calendário em vez da data operacional; POST repetido gerando dois lançamentos.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E04 — "Códigos de lançamento, contas e lançamentos imutáveis" do roadmap docs/roadmaps/roadmap-m3-hospedes-contas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo restrições, a hipótese H2 e o "Protocolo de execução" —, a seção E04 inteira e o "Evidências e handoff" de E01. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar o módulo billing com TransactionCode (códigos padrão pré-carregados), Folio para qualquer FolioOwner(type, id) com pagador empresa opcional, Posting imutável na data operacional do módulo hotel, estorno e ajuste vinculados com motivo, limite e step-up, e banco sem UPDATE/DELETE nas tabelas de lançamentos para o usuário da aplicação. billing não conhece reservas e não chama fiscal. Não implemente roteamento, taxa de serviço, pagamentos nem encerramento.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E04 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume SPEC RF-CTA-01 a 03, §8.2.7, RN-01 a RN-03, ARCHITECTURE §6.3, §8.2 e §11 e como M1 implementou limites, step-up e a proteção append-only da auditoria;
- escreva os testes de domínio e de integração primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em UPDATE/DELETE possível no banco, ajuste acima do limite sem aprovação, data de calendário no lugar da data operacional e POST repetido gerando dois lançamentos.

Critérios de aceite (detalhes na seção E04):
- UPDATE e DELETE em lançamentos com o usuário da aplicação falham (teste de integração);
- estorno cria lançamento inverso vinculado; estorno ou ajuste sem permissão ou acima do limite → 403 APPROVAL_REQUIRED, e com X-Approval-Token válido → 201 auditado com solicitante e aprovador;
- lançamento grava a data operacional do hotel; repetição com a mesma Idempotency-Key não duplica;
- API pública openFolio/post documentada no CLAUDE.md; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E04 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] `UPDATE` e `DELETE` na tabela de lançamentos com o usuário da aplicação falham no banco (teste de integração).
- [ ] Lançamento grava a data operacional corrente do `hotel`, não a data do calendário (teste com data operacional diferente da data do relógio).
- [ ] Estorno cria lançamento inverso vinculado ao original; estornar duas vezes o mesmo lançamento → `409` com `code` estável.
- [ ] Estorno ou ajuste sem permissão ou acima do limite → `403` com `APPROVAL_REQUIRED`; com `X-Approval-Token` válido → `201` e auditoria com solicitante e aprovador.
- [ ] POST de lançamento sem `Idempotency-Key` → `400`; repetição com a mesma chave não duplica.
- [ ] Saldo da conta calculado corretamente em cenário com lançamentos, estornos e ajustes (teste de domínio).
- [ ] Códigos padrão presentes após as migrações.
- [ ] `ApplicationModules.verify()` passa (sem dependência de `billing` para módulos fora da ARCHITECTURE §3.1); contrato regenerado; build, testes e lint oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Tarefa de verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Flyway roda com o mesmo usuário da aplicação (ARCHITECTURE §15.1): se M1 resolveu a auditoria com papéis separados, reutilizar; se não, parar e relatar, pois a imutabilidade no banco depende disso.

**Rollback / migração:** tabelas novas; reverter em desenvolvimento é recriar o schema `billing`. Depois de existir dado real, nenhuma migração pode alterar lançamentos.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E05 — Roteamento, transferência e divisão — #pendente

**Objetivo:** aplicar regras de roteamento que enviam lançamentos, por código ou grupo de códigos e período, para outra conta no momento do lançamento, e permitir transferência manual e divisão de lançamentos entre contas por valor ou percentual (RF-CTA-04, RF-CTA-05).

**Por que agora:** reservas com conta da empresa e contas master de grupo (M4) dependem do roteamento; precisa estar pronto antes de as diárias serem lançadas.

**Dependências e paralelismo:** E04. Pode correr em paralelo com E06 e E07 (em worktrees separados); conflito de edição no agregado `Folio` e no serviço de lançamento com E06 — preferir executar em sequência.

**Escopo:**

- `RoutingRule` com conta de origem, conta de destino, conjunto de códigos ou grupo de receita e período; destino pode ser qualquer conta aberta.
- No lançamento, a regra vigente redireciona o lançamento para a conta de destino, registrando a origem.
- Transferência manual de lançamentos entre contas preservando a imutabilidade (lançamentos de saída e entrada vinculados) e divisão por valor ou percentual com `Money.allocate` (sem perder centavos).
- Permissões e auditoria; `Idempotency-Key` nos POSTs.

**Fora de escopo:** transferência a contas a receber (M10).

**Passos de implementação:**

1. Testes de domínio (seleção da regra vigente, divisão sem perda de centavos) e de integração (lançamento roteado, transferência, conta de destino fechada).
2. Implementar regras, aplicação no fluxo de lançamento, transferência e divisão.
3. Regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em RF-CTA-04 e RF-CTA-05 e no handoff de E04.
- Partes paralelas: nenhuma.
- Foco do verificador: lançamento original alterado na transferência; centavos perdidos na divisão; roteamento em cadeia ou em ciclo (A → B → A); transferência para conta fechada.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E05 — "Roteamento, transferência e divisão" do roadmap docs/roadmaps/roadmap-m3-hospedes-contas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E05 inteira, o "Evidências e handoff" de E04 e RF-CTA-04 e RF-CTA-05 em docs/SPEC.md §5.10. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E04 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: implementar em billing as regras de roteamento (origem, destino, códigos ou grupo, período) aplicadas no momento do lançamento, a transferência manual de lançamentos entre contas e a divisão por valor ou percentual, sempre por lançamentos novos vinculados, sem alterar lançamentos existentes. Não implemente transferência para contas a receber (marco M10).

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E05 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em lançamento original alterado, centavos perdidos na divisão, roteamento em ciclo e destino fechado.

Critérios de aceite (detalhes na seção E05):
- lançamento com código coberto por regra vigente vai para a conta de destino, com origem registrada;
- transferência e divisão criam lançamentos vinculados e preservam a soma exata;
- regra que formaria ciclo → 422 e destino fechado → 409, com code estável;
- build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E05 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Lançamento cujo código está numa regra vigente é gravado na conta de destino com referência à conta de origem; fora do período da regra, fica na origem (teste de integração).
- [ ] Transferência cria lançamentos de saída e entrada vinculados; o lançamento original continua intacto.
- [ ] Divisão por percentual e por valor soma exatamente o original em casos com dízima (teste de domínio).
- [ ] Regra que criaria ciclo de roteamento → `422`; transferência ou roteamento para conta fechada → `409`, ambos com `code` estável.
- [ ] Escritas auditadas; `Idempotency-Key` exigido; contrato regenerado; build, testes e lint oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Tarefa de verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Lock entre duas contas na transferência: travar sempre em ordem de ID para evitar deadlock; teste concorrente simples.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E06 — Taxa de serviço — #pendente

**Objetivo:** lançar automaticamente a taxa de serviço sobre lançamentos cujo código a aceita, conforme a configuração do hotel, separada na conta e removível a pedido do cliente com registro (RF-CTA-14, RN-10).

**Por que agora:** o PDV (M8) e as diárias (M4, se a configuração pedir) lançam em contas; a taxa precisa ser um comportamento do `billing`, não de cada módulo.

**Dependências e paralelismo:** E04. Pode correr em paralelo com E05 e E07 (em worktrees separados); conflito de edição no serviço de lançamento com E05 — preferir executar em sequência.

**Escopo:**

- Percentual por ponto de venda e por código de lançamento lido da configuração do `hotel` (RF-CFG-03, H4).
- Ao lançar um item com `serviceChargeApplies`, gerar lançamento separado de taxa de serviço vinculado ao item (código próprio de E04).
- Remoção a pedido do cliente: estorno do lançamento de taxa com motivo "removida a pedido", registrada e auditada; não exige aprovação além da permissão própria.
- Taxa de serviço marcada como repasse (não receita) no grupo de receita, para o relatório de arrecadação em M6/M10.

**Fora de escopo:** relatório de rateio aos colaboradores e retenção para encargos (M6/M10, Q-08).

**Passos de implementação:**

1. Testes: lançamento com e sem taxa conforme código e ponto; remoção registrada; arredondamento único de `Money`.
2. Implementar a regra no fluxo de lançamento e o endpoint de remoção.
3. Regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em RF-CTA-14, RN-10, RF-CFG-03 e H4.
- Partes paralelas: nenhuma.
- Foco do verificador: taxa calculada com arredondamento próprio em vez do de `Money`; taxa somada ao item em vez de lançamento separado; remoção sem registro; taxa sobre o estorno de um item.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E06 — "Taxa de serviço" do roadmap docs/roadmaps/roadmap-m3-hospedes-contas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo as hipóteses H4 e H5 e o "Protocolo de execução" —, a seção E06 inteira, o "Evidências e handoff" de E04 e RF-CTA-14, RN-10 e RF-CFG-03 em docs/SPEC.md. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E04 está #concluida e que o código bate com o handoff dela; confirme também que o módulo hotel expõe a configuração de taxa de serviço; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: no billing, gerar automaticamente um lançamento separado de taxa de serviço, vinculado ao item, quando o código de lançamento aceita a taxa, com o percentual configurado no hotel por ponto de venda e código, e permitir a remoção a pedido do cliente por estorno registrado e auditado. Não implemente o relatório de rateio nem a retenção para encargos.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E06 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em arredondamento fora de Money, taxa embutida no item, remoção sem registro e taxa sobre estorno.

Critérios de aceite (detalhes na seção E06):
- item com código que aceita taxa gera lançamento separado com o percentual configurado; item sem taxa não gera;
- remoção a pedido cria estorno da taxa com motivo, auditado;
- estorno do item estorna também a taxa vinculada, sem nova taxa;
- build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E06 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Lançamento de item com `serviceChargeApplies` e percentual configurado gera lançamento separado de taxa, vinculado ao item, calculado com o arredondamento de `Money` (teste de integração).
- [ ] Código sem `serviceChargeApplies` ou ponto sem taxa configurada não gera taxa.
- [ ] Remoção a pedido cria estorno da taxa com motivo "removida a pedido", auditado.
- [ ] Estorno do item estorna a taxa vinculada e não gera nova taxa.
- [ ] Taxa marcada como repasse no grupo de receita.
- [ ] Contrato regenerado; build, testes e lint oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Tarefa de verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Configuração de taxa ausente na API pública do `hotel` (H4): parar e relatar em vez de duplicar a configuração em `billing`.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E07 — Pontos e turnos de caixa — #pendente

**Objetivo:** permitir abrir turno de caixa por usuário e ponto de caixa com fundo de troco, registrar sangrias e suprimentos e fechar com contagem por forma de pagamento, opcionalmente cega, com diferenças registradas e relatório de fechamento (RF-CTA-09).

**Por que agora:** todo recebimento exige turno aberto (RF-CTA-10); pagamentos (E08) dependem desta etapa.

**Dependências e paralelismo:** E04. Pode correr em paralelo com E05 e E06 (em worktrees separados); conflito de edição em `api/openapi.yaml`.

**Escopo:**

- `CashPoint` (recepção, restaurante, bar…) cadastrável.
- `CashierShift`: abertura com fundo de troco; no máximo um turno aberto por usuário e ponto; `CashMovement` (sangria, suprimento) imutável com motivo.
- Fechamento com contagem declarada por forma de pagamento; modo cego não mostra o esperado antes da contagem; diferença por forma registrada.
- `expectedByMethod()` calculado a partir de fundo, movimentos e pagamentos do turno (pagamentos chegam em E08; aqui a soma considera zero pagamentos e o teste de E08 completa o cenário).
- Relatório de fechamento do turno em JSON (PDF em M5).
- Tabela de movimentos sem `UPDATE`/`DELETE` para o usuário da aplicação; `Idempotency-Key` nos POSTs; permissões e escopo `OWN` (operador vê só os próprios turnos sem permissão ampla).
- API pública `requireOpenShift(user, cashPoint)` para E08 e módulos futuros.

**Fora de escopo:** pagamentos (E08); reabertura de turno fechado.

**Passos de implementação:**

1. Testes de domínio (esperado, diferença, modo cego) e de integração (turno único por usuário e ponto, imutabilidade, escopo).
2. Implementar migração, domínio, casos de uso, controllers e permissões.
3. Regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em RF-CTA-09 e RF-CTA-10 e no handoff de E04.
- Partes paralelas: nenhuma.
- Foco do verificador: dois turnos abertos para o mesmo usuário e ponto sob concorrência; valor esperado vazando no fechamento cego; movimento editável; operador vendo turno de outro sem permissão.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E07 — "Pontos e turnos de caixa" do roadmap docs/roadmaps/roadmap-m3-hospedes-contas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E07 inteira, o "Evidências e handoff" de E04 e RF-CTA-09 e RF-CTA-10 em docs/SPEC.md §5.10. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E04 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: implementar em billing os pontos de caixa e os turnos de caixa por usuário e ponto: abertura com fundo de troco, sangria e suprimento imutáveis, fechamento com contagem por forma de pagamento (opcionalmente cego) e diferenças registradas, relatório de fechamento em JSON e a API pública requireOpenShift para os pagamentos. Não implemente pagamentos (E08) nem PDF.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E07 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em turno duplicado sob concorrência, esperado visível no fechamento cego, movimento editável e escopo OWN.

Critérios de aceite (detalhes na seção E07):
- segundo turno aberto para o mesmo usuário e ponto → 409 com code estável, inclusive em requisições simultâneas;
- fechamento cego não devolve o esperado antes da contagem e registra a diferença por forma;
- UPDATE e DELETE em movimentos falham no banco; operador sem permissão ampla só vê os próprios turnos;
- build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E07 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Abrir segundo turno para o mesmo usuário e ponto → `409` com `code` estável, inclusive com duas requisições simultâneas (teste).
- [ ] Sangria e suprimento registrados com motivo; `UPDATE`/`DELETE` em movimentos falham no banco.
- [ ] Fechamento cego: a API não devolve o valor esperado antes da contagem; após o fechamento, diferença por forma registrada e relatório disponível.
- [ ] Operador sem permissão ampla lista só os próprios turnos (escopo `OWN`).
- [ ] `requireOpenShift` documentado na API pública de `billing`.
- [ ] Contrato regenerado; build, testes e lint oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Tarefa de verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Turno esquecido aberto: fica para o fechamento do dia (M6) listar como pendência; registrar no handoff.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E08 — Pagamentos manuais, depósitos e reembolsos — #pendente

**Objetivo:** criar o módulo `payments` com `PaymentIntent` neutro, porta de provedor e provedor `MANUAL`, e registrar pagamentos na conta por dinheiro, Pix, crédito, débito e transferência informados manualmente (forma, valor, NSU/autorização), com vários pagamentos por conta, troco, depósitos antes da chegada e reembolso mediante aprovação (RF-PAG-03, RF-PAG-06, RF-CTA-06, 07, 10, 15).

**Por que agora:** sem pagamento não há check-out (M5). O desenho do `PaymentIntent` define como Pix e cartão entram em M7.

**Dependências e paralelismo:** E07 (turno de caixa; E04 transitivamente). Não paralelizável com E09.

**Escopo:**

- Pacote `payments` (a criar), schema próprio: `PaymentIntent` (finalidade, valor, provedor, status, referência do destino opaca para `payments`), porta `PaymentProvider` e adaptador `MANUAL` que confirma na hora com os dados informados; evento `PaymentSucceeded` (H1).
- `billing`: `Payment` na conta com método, valor, status, NSU, autorização, bandeira e 4 últimos dígitos quando informados; turno de caixa obrigatório (`requireOpenShift`); troco em dinheiro; vários pagamentos por conta.
- Fluxo síncrono do registro manual e listener idempotente de `PaymentSucceeded` (H1).
- Depósito: pagamento em conta antes da chegada, identificado para o razão de depósitos (a ligação com reserva chega em M4 pelo `FolioOwner`).
- Reembolso manual (devolução registrada) como ação sensível com aprovação, vinculado ao pagamento original, sem exceder o valor pago menos reembolsos anteriores; sai do turno aberto do usuário.
- `CITY_LEDGER` recusado com `code` estável até M10.
- Tabelas de pagamentos sem `UPDATE`/`DELETE` para o usuário da aplicação; `Idempotency-Key` obrigatório.
- Validação que impede qualquer campo de número de cartão completo ou CVV no contrato e nos DTOs (RNF-10).

**Fora de escopo:** Pix com PSP, cartão integrado, tokenização e conciliação (M7); caução (E09).

**Passos de implementação:**

1. Testes de integração: pagamento sem turno aberto recusado; vários pagamentos e troco; reembolso com e sem aprovação; idempotência; evento `PaymentSucceeded` registrado e listener idempotente; imutabilidade no banco.
2. Implementar `payments`, adaptador manual, integração com `billing` e endpoints.
3. Regenerar o contrato; documentar no `CLAUDE.md` como um provedor novo se encaixa (para M7).

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "o que SPEC §5.10 (RF-CTA-06, 07, 10, 15), §5.11, §8.2.7, §8.3 (`PaymentSucceeded`) e ARCHITECTURE §8.3 (PCI-DSS) e §12 (padrão de integração) exigem de pagamentos e reembolsos?".
- Partes paralelas: nenhuma.
- Foco do verificador: pagamento registrado duas vezes (síncrono e pelo listener); reembolso acima do pago; recebimento sem turno aberto; campo de cartão completo ou CVV aceito; `payments` chamando `billing` (violação do grafo).

**Prompt para uma sessão limpa:**

```text
Execute a etapa E08 — "Pagamentos manuais, depósitos e reembolsos" do roadmap docs/roadmaps/roadmap-m3-hospedes-contas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo restrições, a hipótese H1 e o "Protocolo de execução" —, a seção E08 inteira e o "Evidências e handoff" de E04 e E07. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E07 está #concluida e que o código bate com o handoff dela e de E04; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar o módulo payments com PaymentIntent neutro, porta PaymentProvider, provedor MANUAL e evento PaymentSucceeded, e registrar em billing pagamentos manuais na conta (dinheiro, Pix, crédito, débito, transferência; NSU, autorização, bandeira e 4 últimos dígitos quando informados) com troco, vários pagamentos por conta, turno de caixa aberto obrigatório, depósitos antes da chegada e reembolso com aprovação. payments não chama billing; CITY_LEDGER é recusado até o marco M10; nunca aceitar número de cartão completo nem CVV.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E08 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume SPEC RF-CTA-06, 07, 10 e 15, §5.11, §8.2.7, §8.3 e ARCHITECTURE §8.3 e §12;
- escreva os testes de integração primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em pagamento duplicado pelo listener, reembolso acima do pago, recebimento sem turno, dado de cartão completo e payments chamando billing.

Critérios de aceite (detalhes na seção E08):
- pagamento sem turno aberto → 409 com code estável; com turno, registrado na conta e no turno;
- PaymentSucceeded registrado no outbox e o listener de billing não duplica o pagamento;
- reembolso sem aprovação → 403 APPROVAL_REQUIRED; acima do saldo reembolsável → 422;
- UPDATE e DELETE em pagamentos falham no banco; nenhum campo de cartão completo ou CVV no contrato; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E08 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa e para o marco M7) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Pagamento sem turno de caixa aberto do usuário → `409` com `code` estável; com turno, o pagamento aparece na conta e no esperado do turno (teste de integração).
- [ ] Vários pagamentos numa conta e troco em dinheiro calculados corretamente; saldo da conta atualizado.
- [ ] `PaymentSucceeded` registrado no outbox; o listener de `billing` é idempotente e não duplica o pagamento já registrado (teste).
- [ ] Reembolso sem permissão nem aprovação → `403` com `APPROVAL_REQUIRED`; com aprovação, vinculado ao pagamento original e auditado; acima do saldo reembolsável → `422`.
- [ ] Depósito registrado em conta antes da chegada fica identificável como depósito.
- [ ] `CITY_LEDGER` → `422` com `code` estável.
- [ ] `UPDATE`/`DELETE` em pagamentos falham no banco; `Idempotency-Key` exigido.
- [ ] Nenhum DTO nem o `api/openapi.yaml` têm campo de número de cartão completo ou CVV.
- [ ] `ApplicationModules.verify()` passa (`payments` não depende de `billing`); contrato regenerado; build, testes e lint oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Tarefa de verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O desenho de H1 pode não servir ao Pix assíncrono de M7: revisão humana nesta etapa; registrar no handoff as notas para M7.

**Rollback / migração:** tabelas novas; após dados reais, pagamentos nunca são alterados por migração.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E09 — Caução registrada manualmente — #pendente

**Objetivo:** registrar na conta a pré-autorização de cartão feita em maquininha não integrada (valor, validade, NSU, autorização, bandeira, 4 últimos dígitos), capturá-la total ou parcialmente como pagamento e liberar a sobra (RF-CTA-08, recorte manual).

**Por que agora:** a recepção (M5) exige caução no check-in; até M7 ela é registrada manualmente.

**Dependências e paralelismo:** E08. Não paralelizável.

**Escopo:**

- `CardPreAuthorization` na conta com status (ativa, capturada, parcialmente capturada, liberada, expirada), `expiresAt` e dados não sensíveis do cartão.
- Captura total ou parcial gera `Payment` vinculado pelo fluxo de E08 (exige turno aberto); liberação registrada; captura acima do autorizado recusada.
- Consulta de cauções a vencer (para o fechamento do dia em M6 listar e para M7 renovar).
- Auditoria e `Idempotency-Key`.

**Fora de escopo:** pré-autorização integrada, renovação automática e caução via Pix (M7).

**Passos de implementação:**

1. Testes: registro, captura parcial e total, liberação, captura acima do autorizado, caução expirada, consulta de cauções a vencer.
2. Implementar domínio, casos de uso e endpoints.
3. Regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em RF-CTA-08 e no handoff de E08.
- Partes paralelas: nenhuma.
- Foco do verificador: captura acima do autorizado ou de caução expirada; captura sem turno aberto; dado de cartão completo; caução contada no saldo da conta como pagamento antes da captura.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E09 — "Caução registrada manualmente" do roadmap docs/roadmaps/roadmap-m3-hospedes-contas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E09 inteira, o "Evidências e handoff" de E08 e RF-CTA-08 em docs/SPEC.md §5.10. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E08 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: registrar em billing a caução feita em maquininha não integrada (valor, validade, NSU, autorização, bandeira, 4 últimos dígitos), capturá-la total ou parcialmente gerando pagamento pelo fluxo de E08, liberar a sobra e consultar cauções a vencer. Não implemente pré-autorização integrada, renovação automática nem caução via Pix (marco M7).

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E09 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em captura acima do autorizado ou expirada, captura sem turno, dado de cartão completo e caução contada como pagamento antes da captura.

Critérios de aceite (detalhes na seção E09):
- captura parcial gera pagamento do valor capturado e libera a sobra; captura acima do autorizado ou de caução expirada → 422;
- caução não captura não altera o saldo da conta;
- consulta de cauções a vencer retorna as que vencem no período;
- build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E09 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Caução registrada não altera o saldo da conta (teste).
- [ ] Captura parcial gera `Payment` do valor capturado vinculado à caução e registra a liberação da sobra; captura total encerra a caução.
- [ ] Captura acima do autorizado ou de caução expirada → `422` com `code` estável; captura sem turno aberto → `409`.
- [ ] Consulta de cauções a vencer devolve as que expiram no intervalo pedido.
- [ ] Escritas auditadas e com `Idempotency-Key`; contrato regenerado; build, testes e lint oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Tarefa de verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- M7 vai integrar a pré-autorização real: manter o agregado neutro quanto ao provedor e registrar no handoff.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E10 — Encerramento, reabertura, extrato e views de leitura — #pendente

**Objetivo:** completar o ciclo de vida da conta (encerrar só com saldo zero, reabrir como ação sensível), oferecer o extrato da conta em JSON e publicar as views `report_*` de `billing` para os relatórios de M6 (RF-CTA-11, RF-CTA-12 parte de dados, RN-06).

**Por que agora:** o check-out (M5) depende do encerramento com saldo zero, e o `reporting` (M6) lê só as views publicadas.

**Dependências e paralelismo:** E05, E06, E09. Não paralelizável.

**Escopo:**

- Encerrar conta: exige saldo zero e nenhuma caução ativa não resolvida; conta fechada recusa lançamentos e pagamentos.
- Reabrir conta fechada: ação sensível com step-up, auditada.
- API pública `isSettled(ownerRef)` / saldo por dono, para o check-out de M5 aplicar RN-06 sem `billing` conhecer reservas.
- Extrato em JSON: lançamentos, estornos, taxa de serviço, pagamentos, saldo, agrupamento por data operacional; prévia para o hóspede sem dados internos.
- Views `billing.report_postings`, `billing.report_payments`, `billing.report_cashier_shifts` e `billing.report_deposits` (nomes a confirmar na etapa), sem dados pessoais além do necessário.

**Fora de escopo:** extrato em PDF (M5); razão de contas a receber (M10); relatórios em si (M6).

**Passos de implementação:**

1. Testes: encerrar com saldo diferente de zero recusado; conta fechada recusa lançamento; reabertura com step-up; extrato consistente com o saldo; views legíveis com os dados esperados.
2. Implementar ciclo de vida, extrato e views (migração).
3. Regenerar o contrato; documentar as views no `CLAUDE.md` como API de leitura.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em RF-CTA-11, RF-CTA-12, RN-06 e ARCHITECTURE §11 ("Leitura para relatórios").
- Partes paralelas: nenhuma.
- Foco do verificador: conta encerrada com saldo diferente de zero por arredondamento; lançamento aceito em conta fechada; reabertura sem aprovação; view expondo documento do hóspede ou dado de cartão.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E10 — "Encerramento, reabertura, extrato e views de leitura" do roadmap docs/roadmaps/roadmap-m3-hospedes-contas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E10 inteira e o "Evidências e handoff" de E04 a E09. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E05, E06 e E09 estão #concluida e que o código bate com o handoff delas; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: em billing, encerrar conta só com saldo zero e sem caução ativa, recusar lançamentos e pagamentos em conta fechada, reabrir como ação sensível com step-up, expor a API pública de saldo por dono para o check-out do marco M5, entregar o extrato da conta em JSON e publicar as views report_* de lançamentos, pagamentos, turnos de caixa e depósitos. Não gere PDF nem relatórios.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E10 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em encerramento com saldo residual, lançamento em conta fechada, reabertura sem aprovação e view com dado pessoal ou de cartão.

Critérios de aceite (detalhes na seção E10):
- encerrar com saldo diferente de zero ou caução ativa → 409 com code estável;
- lançamento ou pagamento em conta fechada → 409; reabertura sem aprovação → 403 APPROVAL_REQUIRED;
- extrato em JSON soma exatamente o saldo da conta;
- views report_* criadas, documentadas e sem dado pessoal ou de cartão; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E10 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Encerrar conta com saldo diferente de zero ou caução ativa → `409` com `code` estável; com saldo zero, conta fechada e auditada.
- [ ] Lançamento ou pagamento em conta fechada → `409`.
- [ ] Reabertura sem permissão nem aprovação → `403` com `APPROVAL_REQUIRED`; com aprovação, conta reaberta e auditada com solicitante e aprovador.
- [ ] API pública de saldo por `FolioOwner` documentada e testada.
- [ ] Extrato em JSON: soma de lançamentos menos pagamentos igual ao saldo, em cenário com estorno, taxa de serviço, transferência e caução capturada.
- [ ] Views `report_*` de `billing` existem, são lidas num teste de integração e não contêm documento de hóspede nem dado de cartão além de bandeira e 4 últimos dígitos.
- [ ] Contrato regenerado; build, testes e lint oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Tarefa de verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Views acopladas às tabelas internas: tratá-las como contrato do módulo e cobrir com teste, para que mudanças de tabela não quebrem `reporting` em silêncio.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E11 — Gate do marco M3 — #pendente

**Objetivo:** confirmar, a partir de uma cópia limpa do repositório, que toda a definição global de pronto do M3 é atendida, e deixar o roadmap do M4 pronto para ser sincronizado.

**Por que agora:** é o critério de entrada do marco M4.

**Dependências e paralelismo:** E02, E03, E10 (as etapas-folha, que cobrem transitivamente as demais). Não paralelizável.

**Escopo:**

- Rodar build, testes, lint e verificação do contrato do zero numa cópia limpa.
- Conferir cada item da "Definição global de pronto" com evidência.
- Cenário ponta a ponta automatizado (teste de integração): cadastrar hóspede e empresa, abrir conta com pagador empresa, lançar com roteamento e taxa de serviço, abrir turno, receber pagamento manual, registrar e capturar caução, estornar com aprovação, zerar saldo e encerrar.
- Revisar `CLAUDE.md`: API pública de `guests`, `billing` e `payments`, eventos, portas e views.
- Registrar a conclusão do M3 no histórico de revisões de `docs/roadmaps/README.md`.

**Fora de escopo:** funcionalidade nova. Problemas voltam para a etapa de origem (reabrir com `#em-andamento` e registrar no handoff dela), salvo ajustes triviais de documentação.

**Passos de implementação:**

1. Escrever o teste ponta a ponta do marco.
2. Rodar tudo do zero numa cópia limpa e registrar a evidência de cada item.
3. Revisar documentação e atualizar o README dos marcos.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação: nenhuma.
- Partes paralelas: nenhuma.
- Foco do verificador: item da definição de pronto sem evidência; teste ponta a ponta que não exercita a imutabilidade nem a aprovação; dado sensível exposto em algum endpoint do marco; dependência entre módulos fora da ARCHITECTURE §3.1.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E11 — "Gate do marco M3" do roadmap docs/roadmaps/roadmap-m3-hospedes-contas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a "Definição global de pronto" —, a seção E11 inteira e o "Evidências e handoff" de E01 a E10. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 a E10 estão #concluida; se não, ou se o código divergiu do plano, pare e relate.

Objetivo: escrever o teste ponta a ponta do marco (hóspede e empresa, conta com pagador empresa, lançamento com roteamento e taxa de serviço, turno de caixa, pagamento manual, caução capturada, estorno com aprovação, saldo zero e encerramento) e verificar numa cópia limpa, sem caches nem estado local, cada item da definição global de pronto do M3, com evidência. Não implemente funcionalidade nova: problemas são devolvidos à etapa de origem, salvo ajustes triviais de documentação.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E11 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva o teste ponta a ponta, rode do zero numa cópia limpa os comandos oficiais de build, teste e lint do CLAUDE.md e a verificação do contrato, e registre a evidência de cada item da definição global de pronto;
- revise o CLAUDE.md e registre a conclusão do M3 no histórico de docs/roadmaps/README.md;
- verificação independente com o Prompt do verificador, com foco em item sem evidência, teste ponta a ponta fraco, dado sensível exposto e dependência fora da ARCHITECTURE §3.1.

Critérios de aceite (detalhes na seção E11):
- teste ponta a ponta do marco passa;
- todos os itens da definição global de pronto marcados com evidência numa cópia limpa;
- CLAUDE.md coerente com o código; docs/roadmaps/README.md registra a conclusão do M3.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E11 (snapshot, verificações, resultados, parecer do verificador, notas para o marco M4) e responda com um resumo curto. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] O teste ponta a ponta do marco passa e exercita imutabilidade, aprovação, turno de caixa e encerramento com saldo zero.
- [ ] Todos os itens da "Definição global de pronto" estão marcados, cada um com evidência numa cópia limpa.
- [ ] `CLAUDE.md` documenta a API pública de `guests`, `billing` e `payments`, os eventos `GuestsMerged` e `PaymentSucceeded`, as portas e as views.
- [ ] `docs/roadmaps/README.md` registra a conclusão do M3 no histórico de revisões.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint numa cópia limpa → sucesso.
- Tarefa de verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Lacunas descobertas só no ponta a ponta: devolver à etapa de origem em vez de remendar no gate.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para o marco M4: —

---

## Histórico de revisões

- 2026-10-13 — roadmap criado.
- 2026-10-13 — aplicada a regra por camadas da ARCHITECTURE §3.1 (decisão do usuário): contexto e restrições citam a regra; `billing` → `printing` registrado para o extrato em PDF de M5 (dono + printing). Nenhuma etapa mudou de escopo.
