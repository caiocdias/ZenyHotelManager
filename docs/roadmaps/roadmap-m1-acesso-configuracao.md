# Roadmap — M1 Acesso, auditoria e configuração

> Arquivo: `docs/roadmaps/roadmap-m1-acesso-configuracao.md` · criado em 2026-10-13 · última revisão 2026-10-14
>
> Marco 1 de 11 · mapa dos marcos em [`docs/roadmaps/README.md`](README.md) · marco anterior: [M0 — Fundação técnica](roadmap-m0-fundacao-tecnica.md) · próximo marco: [M2 — Plataforma operacional](roadmap-m2-plataforma-operacional.md)
>
> **Pré-requisito:** etapa E08 ("Gate do marco M0") de `docs/roadmaps/roadmap-m0-fundacao-tecnica.md` marcada `#concluida` — atendido em 2026-10-14 (commit `5dd29f5`, CI verde).

## Como usar

Duas formas, ambas na raiz do repositório:

- **Manual:** cole o prompt de uma etapa numa sessão limpa do Claude Code. Uma etapa por sessão.
- **Automática:** cole o prompt da seção "Execução automática" numa sessão limpa; ela executa até 3 etapas em sequência, um subagente por etapa mais um verificador, e para para revisão. Para não parar em cada pedido de permissão, libere antes os comandos de build/teste do projeto nas permissões do Claude Code (os comandos oficiais estão no `CLAUDE.md`).

Etapas marcadas como paralelizáveis só devem rodar ao mesmo tempo em worktrees git separados; na mesma pasta, rode-as uma de cada vez.

Em ambas, quem executa segue o "Protocolo de execução", atualiza a tag da etapa e preenche "Evidências e handoff". Não pule dependências. Revise e faça commit entre etapas ou lotes — nenhuma sessão faz commit por conta própria.

**Sincronizado com o código em 2026-10-14**, depois do gate do M0 (commit `5dd29f5`): caminhos, comandos, convenções e pendências vêm do `CLAUDE.md` e dos handoffs de `docs/roadmaps/roadmap-m0-fundacao-tecnica.md`. As hipóteses H1–H4 e H9 foram confirmadas pelo usuário na mesma data.

## Objetivo

Entregar os módulos de plataforma que todo o resto usa para saber **quem** está agindo, **o que** pode fazer e **em que contexto do hotel**: login com sessão por dispositivo, roles compostas pelo administrador a partir de um catálogo de permissões com escopo e limites, aprovação de superior por PIN, trilha de auditoria imutável, configuração do hotel com a data operacional, cadastro de colaboradores com escala e armazenamento de anexos. Ao fim do marco, nenhum endpoint de `/api/v1` fica acessível sem autenticação e toda escrita é auditada.

## Contexto confirmado

- Requisitos: SPEC §5.1 (ACS, RF-ACS-01 a 12), §5.2 (CFG, RF-CFG-01 a 05), §5.3 (PES, RF-PES-01 a 05); papéis sugeridos em SPEC §3.1; regras RN-01, RN-11, RN-12 (SPEC §6); RNF-08, RNF-09, RNF-11, RNF-14 (SPEC §7).
- Modelo de domínio: SPEC §8.2.1 (User, Role, RoleGrant, Permission, Scope, DeviceSession, Approval, AuditEntry) e §8.2.2 (Hotel, HotelPolicy, TaxSettings, Department, Employee, Shift, SchedulePattern, ScheduleEntry).
- Arquitetura: módulos `iam`, `audit`, `hotel`, `staff` e `files` (ARCHITECTURE §3.1), todos da camada Plataforma (§3.2). Vale a **regra por camadas** da §3.1: qualquer módulo pode depender de um módulo da Plataforma desde que o grafo siga acíclico, e a aresta nova é acrescentada à tabela §3.1 na mesma mudança. Arestas deste marco: `iam`, `audit` e `files` dependem só de `shared`; `staff` depende de `shared` e `iam`; `hotel` depende de `shared` e `files` (logotipo).
- Segurança: ARCHITECTURE §8.1 (JWT de 15 min, refresh opaco de 30 dias deslizantes, rotação a cada uso, detecção de reuso, Argon2id, bloqueio progressivo), §8.2 (catálogo em código sincronizado no boot, permissões efetivas em cache invalidado por versão e fora do JWT, `authz.require(...)` na camada `application`, escopo `OWN` como filtro, limites no domínio), §8.3 (cifragem de campo e de anexos, auditoria append-only) e §6.3 (fluxo de step-up com `X-Approval-Token`).
- Fluxo de requisição e auditoria: ARCHITECTURE §5 (autorização em `application`, `@Audited(action)` na mesma transação, eventos pelo outbox).
- Arquivos: ARCHITECTURE §14 (API S3, MinIO no compose, URL assinada curta, limites por finalidade) e D14.
- Base técnica herdada de M0 (fonte: `CLAUDE.md` e handoffs de M0; conferida no código em 2026-10-14):
  - Pacote base `com.zeny.hotel`; módulos em `src/main/kotlin/com/zeny/hotel/<módulo>/` com `domain/`, `application/`, `infrastructure/` e `web/`; hoje só existe `shared`. Regras de camadas em `src/test/kotlin/com/zeny/hotel/architecture/LayerRules.kt`, verificação de módulos no `ModularityTest`.
  - Comandos oficiais na tabela do `CLAUDE.md` (`./gradlew build`, `test`, `lint`, `checkOpenApi`, `updateOpenApi`, `docker compose -f docker/compose.yaml up -d --build`). Versões só em `gradle/libs.versions.toml`; o catálogo **ainda não tem** Resource Server/JWT, implementação de Argon2, AOP, cliente S3 nem Testcontainers MinIO: cada etapa acrescenta o que usar.
  - Banco: uma pasta de migração por módulo (`src/main/resources/db/migration/<módulo>/V<aaaa>_<mm>_<dd>_<hhmm>__…sql`), validada pelo `MigrationConventionTest` (`src/test/kotlin/com/zeny/hotel/persistence/`), que aceita `INSERT INTO`/`GRANT` só dentro do schema do módulo. O Flyway e a aplicação usam hoje **o mesmo usuário** (`ZENY_DB_*`); o compose cria só o `POSTGRES_USER`, e o `PostgresContainer` dos testes (`src/test/kotlin/com/zeny/hotel/testsupport/`) usa o usuário padrão do Testcontainers.
  - Kernel `shared` (raiz pública): `Money`, `Percent`, IDs tipados com UUIDv7, `Cpf`, `Cnpj`, `Address`, `DocumentNumberGenerator`, `FieldCipher`/`EncryptedStringConverter` (só cifra `String`; o anel de chaves fica em `shared/infrastructure/crypto/EncryptionKeyRing.kt`) e o `Clock` UTC injetável.
  - API: `@ApiV1Controller`, `DomainException` e famílias (`PermissionDeniedException` 403, `ConflictException` 409…), `ErrorCodes` (`UNAUTHENTICATED`, `ACCESS_DENIED`…), paginação por cursor, `ETags`/`requireCurrentVersion` e `@Idempotent`, todos documentados no `CLAUDE.md`.
  - Segurança provisória em `src/main/kotlin/com/zeny/hotel/shared/web/SecurityConfiguration.kt`: só `GET /api/v1/meta/version` é público, o resto de `/api/**` exige autenticação sem nenhum mecanismo de login; recusas saem como `401 UNAUTHENTICATED`/`403 ACCESS_DENIED`. O `ApiSecurityTest` (`src/test/kotlin/com/zeny/hotel/shared/web/`) percorre todos os endpoints de `/api/**` sem autenticação, com exceção da lista `PUBLIC_API_ENDPOINTS`. Os testes de API autenticam com `@WithMockUser` sobre o `MockMvc` do `@IntegrationTest`.
  - Idempotência: o escopo é `user:<authentication.name>` em `IdempotencyFilter.scopeOfCurrentUser` (`shared/web/`). O filtro recusa formulário e multipart com `415`, então endpoint multipart não pode ser `@Idempotent`.
  - Compose (`docker/compose.yaml`, variáveis em `docker/.env`, modelo `docker/.env.example`): o `api` recebe só `ZENY_DB_*` e `ZENY_ENCRYPTION_*`; o MinIO (`pgsty/minio:RELEASE.2026-08-04T00-00-00Z`, porque a `minio/minio` deixou de ser publicada) usa o usuário root `ZENY_STORAGE_ACCESS_KEY`/`ZENY_STORAGE_SECRET_KEY`, e o `storage-init` cria o bucket `ZENY_STORAGE_BUCKET`. Variável obrigatória nova entra com `${VAR:?…}`.
  - CI (`.github/workflows/ci.yml`): build completo com Testcontainers, contrato, oasdiff e imagem; não usa segredos, então tudo o que os testes precisam (chaves de cifragem, chave JWT) é gerado pelos próprios testes, como o `TestEncryptionKeys`.
- Pendências herdadas do M0 e onde são tratadas aqui: JWT na `SecurityConfiguration` e `PUBLIC_API_ENDPOINTS` (E01); escopo do `Idempotency-Key` pelo id do usuário (E01, H9); usuário e sessão de dispositivo no MDC dos logs (E01); conferir `LayerRules` e o `TypedIdConverter` com o primeiro módulo real (E01); `DataIntegrityViolationException` → `ConflictException` e teste do `412` por lock otimista (E03); usuário de migração distinto do usuário da aplicação e falsos positivos do `MigrationConventions` com `GRANT … ON FUNCTION f(numeric(14,2))` e `ALTER DEFAULT PRIVILEGES` (E04, H4); cifragem de bytes, `ZENY_STORAGE_*` no `api` e usuário de serviço do MinIO (E08). O limite de tentativas e o arquivamento do outbox esperam o primeiro listener real (M2).

## Escopo

**Incluído:** autenticação e sessões por dispositivo; catálogo de permissões, roles, escopo, limites e checagem de autorização; gestão de usuários e roles; aprovação de superior (step-up); trilha de auditoria; configuração do hotel e data operacional; colaboradores, setores, turnos e escala; armazenamento de anexos; troca da segurança mínima de M0 E04 e do identificador provisório de M0 E05 pelo usuário e dispositivo autenticados.

**Fora de escopo:**

- Fechamento do dia (quem avança a data operacional): M6. Aqui só existe a API pública interna `advanceBusinessDate`, sem endpoint.
- Credenciais de serviço para o agente de impressão e integrações de entrada (ARCHITECTURE §8.1): M5, junto com `printing`.
- Exportação de dados pessoais e retenção LGPD: M3 (hóspedes) e M10 (automação).
- Taxa de serviço por ponto de venda e por código de lançamento (RF-CFG-03): os pontos de venda e códigos de lançamento só existem em M3 e M8; aqui fica a taxa padrão e a estrutura para sobrescrita.
- Uso da escala pela governança e pelas rotinas de checklist (RF-PES-04): M2 e M6 consomem a API pública criada aqui.
- Notificações e tempo real: M2.
- Limite de tentativas e arquivamento das publicações do outbox: nenhum módulo deste marco escuta eventos; fica para o primeiro listener real (M2).

## Restrições e invariantes

- Respeitar o grafo de módulos da ARCHITECTURE §3.1 e a regra por camadas. A checagem de autorização, a consulta ao usuário corrente, o consumo de aprovações e o registro de auditoria são **portas definidas em `shared`** (o "contexto de segurança" do kernel) e implementadas por `iam` e `audit`. A regra por camadas permitiria a dependência direta, mas as portas continuam por desenho: `iam` grava auditoria e `audit` precisa do ator corrente, o que criaria o ciclo `iam` ↔ `audit`, e todos os módulos de negócio dependeriam de `iam` só para checar permissão. Nenhum módulo além de `staff` importa `iam` diretamente.
- Permissões efetivas nunca vão dentro do JWT (ARCHITECTURE §8.2).
- Senha, PIN e refresh token nunca são guardados em claro nem aparecem em logs, auditoria ou respostas.
- Toda escrita é auditada na mesma transação (RNF-11); a tabela de auditoria é append-only para o usuário da aplicação.
- Usuários e colaboradores nunca são excluídos, só desativados (RF-ACS-12).
- Instantes em UTC; datas de negócio em `date`; o fuso do hotel vem da configuração (RN-01, RNF-14).
- Convenções de API, erros, migrações e tipos de `shared` seguem o que M0 documentou no `CLAUDE.md`; não criar convenção paralela.
- Nenhum segredo (chave JWT, chave de cifragem, senha do admin inicial, credenciais do MinIO) no repositório.

## Hipóteses e decisões em aberto

| # | Hipótese ou decisão | Impacto se errada / sem decisão | Etapa afetada |
|---|---|---|---|
| H1 | Pacote base `com.zeny.hotel`. **Confirmado** (M0 E01, conferido no código em 2026-10-14). | — | Todas |
| H2 | O primeiro administrador é criado no primeiro boot a partir de variáveis de ambiente (login e senha inicial), só quando não existe nenhum usuário, com troca de senha obrigatória no primeiro login. **Confirmado pelo usuário em 2026-10-14** (alternativa descartada: comando de linha). | Trocar por um comando de linha depois é barato. | E01 |
| H3 | O JWT é assinado com **RS256**, com par de chaves RSA fornecido por variável de ambiente ou Docker secret e `kid` para rotação (mais de uma chave pública aceita na validação, uma ativa para assinar). **Decidido pelo usuário em 2026-10-14** (alternativas descartadas: ES256 e HS256). A E01 registra o algoritmo na ARCHITECTURE §8.1, que hoje diz só "chave própria". | Trocar o algoritmo afeta só a configuração do Resource Server. | E01 |
| H4 | A imutabilidade da auditoria usa **dois papéis** no PostgreSQL: o dono dos schemas, usado pelo Flyway (variáveis `ZENY_DB_MIGRATION_*`), e o papel da aplicação (`ZENY_DB_*`), só com DML e sem `UPDATE`/`DELETE` nas tabelas imutáveis. O papel da aplicação é criado fora das migrações (sem senha no repositório); as migrações só concedem privilégios. Testcontainers e compose passam a criar os dois papéis. **Decidido pelo usuário em 2026-10-14** (alternativa descartada: *trigger* que rejeita `UPDATE`/`DELETE`). Vale também para os lançamentos e pagamentos do M3 (RN-03). | Se a etapa encontrar impedimento técnico real, para e relata em vez de cair no *trigger*. | E04 |
| H5 | Os modelos de role da SPEC §3.1 são carregados com as permissões que já existem neste marco; cada marco seguinte acrescenta as permissões dos seus módulos aos modelos por migração ou seed do próprio módulo. | Se o administrador já tiver editado um modelo, o seed não pode sobrescrevê-lo: o seed só acrescenta em modelos não editados. | E02 |
| H6 | Setores (RF-PES-01) ficam numa tabela `Department` com os seis setores da SPEC pré-carregados, editável pelo administrador. | Se o hotel quiser setores fixos, basta bloquear a edição. | E07 |
| H7 | O vínculo usuário ↔ colaborador (RF-ACS-01, RF-PES-01) é guardado em `staff` (`Employee.userId`), porque `staff` já depende de `iam` e a aresta inversa criaria ciclo. | Consultar "colaborador do usuário" passa por `staff`. | E07 |
| H8 | O logotipo do hotel (RF-CFG-01) é um arquivo de `files` com finalidade própria. E06 guarda o ID do arquivo; E08 cria a aresta `hotel` → `files` (já registrada na ARCHITECTURE §3.1) e passa a validar que o ID existe e tem a finalidade de logotipo. | Se a validação ficar complexa, `hotel` aceita o ID e o cliente garante o upload; registrar no handoff. | E06, E08 |
| H9 | O `Idempotency-Key` passa a ser isolado pelo **id estável do usuário** do JWT (`user:<id>`), sem o dispositivo. **Decidido pelo usuário em 2026-10-14.** Chaves gravadas com o escopo antigo só vencem em 24h, sem migração. | Acrescentar o dispositivo depois só muda o escopo. | E01 |

## Definição global de pronto

- [ ] Nenhum endpoint de `/api/v1`, exceto login, refresh e os públicos de documentação e actuator, responde sem token válido.
- [ ] Um usuário criado pelo administrador faz login, renova o token, é desativado e perde o acesso no próximo refresh.
- [ ] Roles compostas a partir do catálogo controlam o acesso, com escopo `OWN`/`ALL` e limites, e a permissão efetiva é a união das roles.
- [ ] Uma ação sensível sem permissão devolve `403 APPROVAL_REQUIRED` e é concluída com um `X-Approval-Token` válido de outro usuário.
- [ ] Toda escrita dos módulos do marco gera entrada de auditoria; o usuário da aplicação não consegue alterar nem apagar a trilha.
- [ ] Dados do hotel, políticas, taxas, parâmetros e a data operacional corrente são consultáveis e editáveis com permissão.
- [ ] Colaboradores, turnos e escala com padrões e exceções funcionam, e a API pública devolve a escala de um dia.
- [ ] Upload e download de anexos funcionam com URL assinada; anexos sensíveis são cifrados e cada acesso é auditado.
- [ ] `ApplicationModules.verify()`, build, testes, lint e verificação do contrato passam.

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
Você é o verificador independente da etapa ETAPA do roadmap docs/roadmaps/roadmap-m1-acesso-configuracao.md. Você não participou da implementação e não deve editar nenhum arquivo.
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
Você é o orquestrador do roadmap docs/roadmaps/roadmap-m1-acesso-configuracao.md neste repositório. Seu papel é coordenar subagentes, não implementar: não edite código e mantenha seu contexto enxuto (leia o roadmap e os relatórios dos subagentes, não o código).

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

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E01 | Autenticação e sessões por dispositivo | #pendente | — | Login, JWT de 15 min, refresh rotativo com detecção de reuso, bloqueio por tentativas, admin inicial e fim da segurança provisória de M0 |
| E02 | Catálogo de permissões, roles e autorização | #pendente | E01 | Catálogo em código, roles com escopo e limites, permissão efetiva em cache e porta de autorização em `shared` |
| E03 | Gestão de usuários e roles | #pendente | E02 | Endpoints de usuários, roles e sessões para o administrador, com RF-ACS-09 e desativação |
| E04 | Trilha de auditoria | #pendente | E03 | Módulo `audit` append-only, `@Audited` via porta em `shared` e consulta filtrada |
| E05 | Aprovação de superior (step-up) | #pendente | E04 | `POST /auth/approvals`, `X-Approval-Token` de uso único e `403 APPROVAL_REQUIRED` |
| E06 | Configuração do hotel e data operacional | #pendente | E04 | Módulo `hotel` com dados, políticas, taxas, parâmetros, data operacional e `BusinessDateAdvanced` |
| E07 | Colaboradores, turnos e escala | #pendente | E04 | Módulo `staff` com setores, colaboradores, turnos, padrões, exceções e "escala do dia" |
| E08 | Armazenamento de anexos | #pendente | E04, E06 | Módulo `files` com S3/MinIO, URL assinada, limites por finalidade, cifragem de sensíveis e validação do logotipo do hotel |
| E09 | Gate do marco M1 | #pendente | E05, E06, E07, E08 | Definição global de pronto verificada do zero |

---

## E01 — Autenticação e sessões por dispositivo — #pendente

**Objetivo:** criar o módulo `iam` com usuários, login por senha, access token JWT de 15 min, refresh token opaco rotativo por dispositivo com detecção de reuso, bloqueio progressivo por tentativas e criação do administrador inicial, e substituir a segurança provisória de M0 E04 e o identificador provisório de idempotência de M0 E05 pelo usuário e dispositivo autenticados.

**Por que agora:** todo o marco, e todos os seguintes, precisa saber quem está agindo. É também o primeiro contrato entre o servidor e os clientes (formato do login e dos tokens).

**Dependências e paralelismo:** nenhuma dentro deste marco; exige o gate de M0 concluído. Não paralelizável: troca a configuração de segurança usada por todas as etapas.

**Escopo:**

- Módulo `iam` (a criar, em `src/main/kotlin/com/zeny/hotel/iam/`) com a estrutura de módulo documentada no `CLAUDE.md`; schema e pasta de migração `src/main/resources/db/migration/iam/` (a criar). É o primeiro módulo real: conferir que `LayerRules` e `ModularityTest` o tratam como esperado e onde ficam as subclasses de `TypedIdConverter` usadas por entidades de `domain` (em `domain` ou na raiz do módulo, nunca em `infrastructure`).
- Entidades `User` (nome, login, hash de senha e de PIN em Argon2id, ativo, contador de falhas, bloqueado até) e `DeviceSession` (ID e nome do dispositivo, hash do refresh token, família de rotação, última atividade, revogada) — SPEC §8.2.1.
- Endpoints: `POST /api/v1/auth/login` (login, senha, dados do dispositivo), `POST /api/v1/auth/refresh`, `POST /api/v1/auth/logout`, `GET /api/v1/auth/sessions` (sessões do próprio usuário) e `POST /api/v1/auth/sessions/{id}/revoke` (própria sessão).
- Regras da ARCHITECTURE §8.1: JWT de 15 min assinado em RS256 com `kid` (H3), chaves por variável de ambiente sem valor padrão no código nem no YAML (mesmo padrão das chaves de cifragem) e geradas pelos próprios testes; refresh de 30 dias deslizantes, rotacionado a cada uso e guardado como hash; refresh reutilizado revoga a sessão inteira; usuário inativo ou sessão revogada não renovam; bloqueio progressivo após falhas de login. Registrar o algoritmo na ARCHITECTURE §8.1.
- Dependências novas no `gradle/libs.versions.toml` (sem versão literal no build): Resource Server/JWT do Spring Boot e a implementação de Argon2 exigida pelo `Argon2PasswordEncoder` do Spring Security.
- Porta em `shared` (raiz pública) para o ator corrente (id do usuário e da sessão de dispositivo), consumida por qualquer módulo sem depender de `iam`. Usuário e sessão de dispositivo entram no MDC dos logs (ARCHITECTURE §16), sem login, senha nem token.
- Administrador inicial por variáveis de ambiente no primeiro boot, só quando não existe usuário, com troca de senha obrigatória (H2).
- Variáveis novas (chaves JWT, administrador inicial) documentadas no `CLAUDE.md` e no `README.md`, passadas ao `api` no `docker/compose.yaml` com `${VAR:?…}` quando obrigatórias e listadas no `docker/.env.example` sem segredo real.
- Fim da segurança provisória de M0 E04 na `SecurityConfiguration` (`shared/web/`): tudo em `/api/**` exige token, exceto login, refresh e o `GET /api/v1/meta/version` que já é público; Swagger e actuator seguem as regras atuais. A lista `PUBLIC_API_ENDPOINTS` do `ApiSecurityTest` ganha login e refresh.
- Idempotência de M0 E05: `IdempotencyFilter.scopeOfCurrentUser` passa a usar o id do usuário do JWT (`user:<id>`, H9).
- Apoio de teste em `src/test/kotlin/com/zeny/hotel/testsupport/` para autenticar como um usuário com id (os testes que usam `@WithMockUser`, como o `IdempotencyTest`, passam a usá-lo onde o id importar), e geração da chave JWT por JVM, também no `OpenApiContractExport`.
- Códigos de erro estáveis: credenciais inválidas, conta bloqueada, refresh inválido, refresh reutilizado, troca de senha pendente.

**Fora de escopo:** permissões e roles (E02); gestão de usuários pelo administrador (E03); PIN e aprovações (E05, aqui só o campo e o hash); credenciais de serviço (M5).

**Passos de implementação:**

1. Escrever testes de integração dos fluxos: login válido e inválido, refresh com rotação, reuso do refresh revogando a sessão, bloqueio progressivo, usuário desativado sem refresh, token expirado rejeitado, endpoint protegido sem token → `401`.
2. Criar módulo, migrações, entidades e casos de uso.
3. Configurar o Resource Server e a porta de ator corrente em `shared`; trocar a segurança provisória, o escopo de idempotência e o MDC.
4. Implementar o administrador inicial; documentar as variáveis no `CLAUDE.md` e no `README.md`, passá-las no `docker/compose.yaml` e listá-las no `docker/.env.example` sem segredo real.
5. Regenerar `api/openapi.yaml`.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "o que a ARCHITECTURE §8.1, §8.2 e §16 e a SPEC RF-ACS-10 e RNF-08 exigem da autenticação, das sessões e dos logs?"; "quais testes de `src/test/kotlin` dependem de `@WithMockUser` ou do escopo `user:<login>` da idempotência?".
- Partes paralelas: nenhuma.
- Foco do verificador: refresh reutilizado que não revoga a sessão; refresh ou senha em claro no banco, log ou resposta; endpoint de `/api/**` acessível sem token; bloqueio contornável trocando maiúsculas do login; chave de assinatura com valor padrão no código, no YAML ou no `.env.example`; JWT aceito com `alg` diferente de RS256 ou `kid` desconhecido.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E01 — "Autenticação e sessões por dispositivo" do roadmap docs/roadmaps/roadmap-m1-acesso-configuracao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Contexto confirmado", "Restrições e invariantes", as hipóteses H1–H3 e H9 e o "Protocolo de execução" —, a seção E01 inteira e o "Evidências e handoff" de E04, E05 e E08 de docs/roadmaps/roadmap-m0-fundacao-tecnica.md. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que a etapa E08 (gate) de docs/roadmaps/roadmap-m0-fundacao-tecnica.md está #concluida e que o código bate com os handoffs de M0; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar o módulo iam com usuários (senha e PIN em Argon2id), login em POST /api/v1/auth/login, JWT de 15 min em RS256 com kid, refresh opaco rotativo por dispositivo com detecção de reuso que revoga a sessão, bloqueio progressivo, logout, listagem e revogação das próprias sessões, porta do ator corrente em shared (também no MDC dos logs) e administrador inicial por variáveis de ambiente. Troque a segurança provisória da SecurityConfiguration (todo /api/** passa a exigir token, exceto login, refresh e GET /api/v1/meta/version) e o escopo do IdempotencyFilter para o id do usuário. Variáveis novas vão para CLAUDE.md, README.md, docker/compose.yaml e docker/.env.example, sem segredo real nem valor padrão. Não implemente permissões, roles, gestão de usuários nem aprovações.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E01 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume docs/ARCHITECTURE.md §8.1, §8.2 e §16, RF-ACS-10 e RNF-08 de docs/SPEC.md e lista os testes que dependem de @WithMockUser ou do escopo de idempotência pelo login;
- escreva os testes de integração dos fluxos de login, refresh, reuso, bloqueio e acesso sem token primeiro, implemente, regenere api/openapi.yaml, rode os comandos oficiais de build, teste e lint do CLAUDE.md e suba o docker compose com as variáveis novas;
- verificação independente com o Prompt do verificador, com foco em reuso de refresh sem revogação, segredo em claro, endpoint aberto e chave de assinatura com valor padrão.

Critérios de aceite (detalhes na seção E01):
- login, refresh com rotação, reuso revogando a sessão, bloqueio progressivo e usuário desativado cobertos por testes de integração;
- nenhum endpoint de /api/** além de login e refresh responde sem token;
- senha, PIN e refresh guardados só como hash; chave JWT fora do código e do YAML;
- Idempotency-Key isolado pelo id do usuário;
- contrato regenerado; build, testes e lint passam; compose sobe com readiness UP.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E01 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] `POST /api/v1/auth/login` com credenciais válidas devolve access token (expira em 15 min) e refresh token; com senha errada devolve `401` com `code` estável.
- [ ] `POST /api/v1/auth/refresh` devolve novo par e invalida o refresh anterior; reapresentar o refresh anterior devolve `401` e revoga a sessão inteira (o refresh mais novo também deixa de funcionar).
- [ ] Após o número configurado de falhas, o login fica bloqueado por tempo crescente, inclusive com a senha correta, e o login é comparado sem diferenciar maiúsculas.
- [ ] Usuário desativado ou sessão revogada não obtêm novo refresh.
- [ ] Senha, PIN e refresh token aparecem no banco só como hash; nenhum deles aparece em log (teste ou inspeção do log de teste).
- [ ] Todo endpoint de `/api/**`, exceto login, refresh e `GET /api/v1/meta/version`, devolve `401` sem token (`ApiSecurityTest`, que percorre os endpoints mapeados); um token com assinatura, `kid` ou `alg` inválidos ou expirado também devolve `401`.
- [ ] O administrador inicial é criado no primeiro boot a partir das variáveis de ambiente, só uma vez, e precisa trocar a senha antes de usar outros endpoints.
- [ ] As chaves de `Idempotency-Key` ficam isoladas pelo id do usuário (H9; teste de M0 E05 adaptado).
- [ ] Os logs de uma requisição autenticada trazem o id do usuário e da sessão de dispositivo, e nenhum login, senha ou token.
- [ ] Sem a chave JWT a aplicação não sobe; `CLAUDE.md`, `README.md`, `docker/compose.yaml` e `docker/.env.example` (sem segredo real) trazem as variáveis novas.
- [ ] `api/openapi.yaml` regenerado; build, testes e lint oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint (`CLAUDE.md`) → sucesso.
- Verificação do contrato → sucesso.
- `ApplicationModules.verify()` (parte do build) → `iam` depende só de `shared`.
- `docker compose -f docker/compose.yaml up -d --build` com as variáveis novas no `docker/.env` → readiness `UP` e login do administrador inicial pelo proxy; `down` em seguida.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Spring Security 7 com Spring Boot 4.1 mudou APIs de configuração: seguir a documentação dessas versões (registradas no `CLAUDE.md`).
- Argon2id lento nos testes: parâmetros reduzidos só no profile de teste.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E02 — Catálogo de permissões, roles e autorização — #pendente

**Objetivo:** implementar o modelo de autorização da ARCHITECTURE §8.2: catálogo de permissões declarado em código por módulo e sincronizado no boot, roles no banco compostas por concessões com escopo e limites, permissão efetiva como união das roles em cache invalidado por versão, e a porta `authz.require(...)` em `shared` usada pela camada `application` de qualquer módulo.

**Por que agora:** todos os endpoints de M1 em diante dependem dessa checagem. É a convenção de autorização que cada módulo vai repetir.

**Dependências e paralelismo:** E01. Não paralelizável.

**Escopo:**

- Em `shared`: interface para um módulo declarar suas permissões (código, escopos suportados, se é sensível, chaves de limite), a porta de autorização (`require(permission)`, `require(permission, resource)` com dono para escopo `OWN`, leitura de limites) e o filtro de escopo `OWN` para consultas.
- Em `iam`: agregação das declarações de todos os módulos, sincronização com a tabela de permissões no boot (novas inseridas, removidas marcadas como obsoletas), `Role` com `RoleGrant(permission, scope, limits)`, vínculo usuário ↔ roles, cálculo da permissão efetiva (escopo mais amplo e maior limite — RF-ACS-04), cache por usuário invalidado por versão quando o usuário ou uma role muda.
- Permissões do próprio `iam` declaradas (usuários, roles, sessões, catálogo).
- Seed dos modelos de role da SPEC §3.1 com as permissões existentes (H5) e concessão de todas ao administrador inicial. Seed por migração do `iam` (`INSERT INTO iam.…`, permitido pelo `MigrationConventionTest` só no próprio schema) ou no boot, junto da sincronização do catálogo.
- Endpoint `GET /api/v1/permissions` (catálogo, para o cliente montar a tela de roles) e `GET /api/v1/auth/me` com as permissões efetivas do usuário logado.
- Erro `403` com `code` estável quando falta permissão, lançado como `PermissionDeniedException` (família já existente em `shared/DomainErrors.kt`), com o código declarado na API pública do módulo dono.

**Fora de escopo:** endpoints de gestão de roles e usuários (E03); step-up e `APPROVAL_REQUIRED` (E05).

**Passos de implementação:**

1. Escrever testes de domínio da união efetiva (escopo, limites, roles inativas ignoradas) e testes de integração de `require` com escopo `OWN` e `ALL`, de invalidação do cache e da sincronização do catálogo.
2. Implementar portas em `shared` e implementação em `iam`.
3. Seed dos modelos e do administrador.
4. Documentar no `CLAUDE.md` como um módulo declara permissões, como checar autorização num caso de uso e como aplicar o filtro `OWN` numa consulta.
5. Regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "o que SPEC §5.1 (RF-ACS-02 a 06, 08 e 09), §3.1 e §8.2.1 e ARCHITECTURE §8.2 definem para catálogo, roles, escopo, limites e ações sensíveis?".
- Partes paralelas: nenhuma.
- Foco do verificador: permissão que continua valendo após remover a role (cache não invalidado); escopo `OWN` que vaza registros de outro usuário; permissão efetiva dentro do JWT; módulo que importa `iam` em vez da porta de `shared`.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E02 — "Catálogo de permissões, roles e autorização" do roadmap docs/roadmaps/roadmap-m1-acesso-configuracao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Restrições e invariantes", a hipótese H5 e o "Protocolo de execução" —, a seção E02 inteira e o "Evidências e handoff" de E01. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: implementar o catálogo de permissões declarado em código por módulo e sincronizado no boot, roles com concessões de escopo (OWN/ALL) e limites, permissão efetiva como união das roles (escopo mais amplo, maior limite) em cache invalidado por versão, e a porta de autorização em shared (require com recurso, filtro OWN, leitura de limites) implementada por iam. Faça o seed dos modelos de role da SPEC §3.1 e exponha GET /api/v1/permissions e GET /api/v1/auth/me. Não crie endpoints de gestão de roles e usuários nem step-up. Permissões efetivas nunca entram no JWT.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E02 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume RF-ACS-02 a 06, 08 e 09, SPEC §3.1 e §8.2.1 e ARCHITECTURE §8.2;
- escreva primeiro os testes de domínio da união efetiva e os de integração de require, escopo OWN, invalidação do cache e sincronização do catálogo; implemente, documente no CLAUDE.md, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint;
- verificação independente com o Prompt do verificador, com foco em cache não invalidado, escopo OWN vazando dados, permissão no JWT e módulo importando iam em vez da porta.

Critérios de aceite (detalhes na seção E02):
- união efetiva com escopo mais amplo e maior limite coberta por testes;
- remover uma role do usuário retira a permissão na requisição seguinte;
- consulta com escopo OWN devolve só registros do usuário; falta de permissão → 403 com code estável;
- catálogo sincronizado no boot e modelos da SPEC §3.1 carregados; CLAUDE.md documenta o uso; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E02 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Testes de domínio comprovam a união efetiva: escopo `ALL` prevalece sobre `OWN`, o maior limite prevalece e roles inativas são ignoradas.
- [ ] Uma permissão nova declarada em código aparece na tabela após o boot; uma removida é marcada obsoleta e deixa de conceder acesso.
- [ ] Remover uma role de um usuário (ou uma concessão de uma role) retira a permissão já na requisição seguinte, sem novo login.
- [ ] Com escopo `OWN`, uma consulta de exemplo devolve só os registros do próprio usuário e um comando sobre registro alheio devolve `403`.
- [ ] Falta de permissão devolve `403` com `code` estável.
- [ ] O JWT emitido não contém permissões.
- [ ] Os modelos de role da SPEC §3.1 existem após a migração/seed, e o administrador inicial tem todas as permissões.
- [ ] `GET /api/v1/permissions` e `GET /api/v1/auth/me` respondem conforme o contrato regenerado.
- [ ] `CLAUDE.md` documenta declaração de permissão, checagem e filtro `OWN`; build, testes e lint passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Cache em memória com várias instâncias: há uma única instância (ARCHITECTURE §10); a versão no banco permite trocar por outro mecanismo depois.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E03 — Gestão de usuários e roles — #pendente

**Objetivo:** expor ao administrador o cadastro de usuários (criar, editar, desativar, redefinir senha e PIN, atribuir roles), o cadastro de roles (criar, editar concessões, duplicar, desativar) e a revogação de sessões de outros usuários, respeitando RF-ACS-09 e RF-ACS-12.

**Por que agora:** sem isso o sistema só tem o administrador inicial. Os marcos seguintes precisam de usuários com roles diferentes para testar escopo e limites.

**Dependências e paralelismo:** E02. Não paralelizável.

**Escopo:**

- Endpoints (recursos em inglês, plural, kebab-case): `/api/v1/users` (lista paginada, detalhe, criação, edição, `POST …/{id}/deactivate`, `POST …/{id}/reactivate`, `POST …/{id}/reset-password`, `POST …/{id}/reset-pin`, atribuição de roles), `/api/v1/roles` (lista, detalhe, criação, edição de concessões, `POST …/{id}/duplicate`, `POST …/{id}/deactivate`), `GET /api/v1/users/{id}/sessions` e `POST /api/v1/users/{id}/sessions/{sessionId}/revoke`.
- Regras: ninguém concede permissão, escopo ou limite que não possui (RF-ACS-09); só administrador gerencia administradores; desativar um usuário revoga todas as sessões (RF-ACS-10); nada é excluído (RF-ACS-12); login único.
- `ETag`/`If-Match` nas edições (convenção de M0 E04), com o primeiro teste do `412` por conflito de lock otimista no commit (conversão que já existe no `ApiExceptionHandler`, mas sem teste desde M0 E04).
- Violação de unicidade (login duplicado) convertida em `ConflictException` no caso de uso, sem deixar `DataIntegrityViolationException` virar `500` (pendência de M0 E04).
- Permissões do `iam` para essas ações, marcadas como sensíveis conforme RF-ACS-08 ("gerenciar usuários e roles").

**Fora de escopo:** auditoria das ações (E04 aplica `@Audited` a estes casos de uso); exigência de step-up nas ações sensíveis (E05); vínculo com colaborador (E07, do lado de `staff`).

**Passos de implementação:**

1. Escrever testes de integração dos endpoints, incluindo RF-ACS-09, administrador gerenciando administrador e desativação revogando sessões.
2. Implementar casos de uso, controllers e DTOs.
3. Regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em SPEC §5.1 e convenções no `CLAUDE.md`.
- Partes paralelas: nenhuma.
- Foco do verificador: escalada de privilégio (gerente concedendo permissão que não tem, ou editando a própria role para ampliar escopo ou limite); usuário desativado com sessão ativa; exclusão física.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E03 — "Gestão de usuários e roles" do roadmap docs/roadmaps/roadmap-m1-acesso-configuracao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Restrições e invariantes" e o "Protocolo de execução" —, a seção E03 inteira, o "Evidências e handoff" de E01 e E02 e RF-ACS-01, 03, 04, 09, 10 e 12 em docs/SPEC.md §5.1. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E02 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: expor /api/v1/users e /api/v1/roles para o administrador — criar, editar, desativar e reativar usuários, redefinir senha e PIN, atribuir roles, criar, editar, duplicar e desativar roles, listar e revogar sessões de outros usuários — com If-Match nas edições. Ninguém concede permissão, escopo ou limite que não possui; só administrador gerencia administradores; desativar revoga todas as sessões; nada é excluído. Não implemente auditoria nem step-up (E04 e E05).

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E03 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de integração primeiro, implemente, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em escalada de privilégio, usuário desativado com sessão ativa e exclusão física.

Critérios de aceite (detalhes na seção E03):
- login duplicado → 409 com code estável (sem 500 por violação de unicidade); conflito de lock otimista → 412;
- conceder permissão, escopo ou limite acima do próprio → 403 com code estável, inclusive editando a própria role;
- não administrador gerenciando administrador → 403;
- desativar usuário invalida todas as sessões no próximo refresh; nenhum endpoint exclui fisicamente;
- contrato regenerado; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E03 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Administrador cria usuário com nome, login, senha e PIN; login duplicado → `409` com `code` estável.
- [ ] Usuário que tenta conceder permissão, escopo ou limite maior que os seus recebe `403` com `code` estável, inclusive ao editar uma role que ele próprio tem.
- [ ] Usuário sem role de administrador não cria, edita nem desativa administradores (`403`).
- [ ] Desativar um usuário revoga todas as sessões: o refresh seguinte falha.
- [ ] Roles podem ser duplicadas e desativadas; role desativada deixa de conceder permissão.
- [ ] Nenhum endpoint exclui usuário ou role fisicamente.
- [ ] Edição sem `If-Match` correto → `412`; duas edições concorrentes com o mesmo `If-Match` → uma passa e a outra recebe `412` (lock otimista).
- [ ] Contrato regenerado; build, testes e lint passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Remover a última role de administrador ativo trancaria o sistema: rejeitar com `code` estável e cobrir com teste.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E04 — Trilha de auditoria — #pendente

**Objetivo:** criar o módulo `audit` com a trilha append-only de toda escrita (quem, quando, dispositivo, ação, entidade, antes e depois, aprovação associada), gravada na mesma transação por `@Audited(action)` através de uma porta em `shared`, e a consulta filtrada por usuário, entidade e período com permissão própria.

**Por que agora:** a partir daqui toda etapa que escreve dados aplica `@Audited`. Os casos de uso de E01–E03 são os primeiros a receber a anotação.

**Dependências e paralelismo:** E03. Não paralelizável (toca os casos de uso de `iam`).

**Escopo:**

- Em `shared`: anotação `@Audited(action)`, aspecto que captura antes e depois, e porta de gravação de auditoria; registro do ator corrente e da sessão de dispositivo (porta de E01).
- Módulo `audit` (a criar) com schema próprio, tabela `AuditEntry` e implementação da porta.
- Imutabilidade com dois papéis (H4, decidido):
  - o Flyway passa a conectar com o papel dono dos schemas (`spring.flyway.user`/`password` a partir de `ZENY_DB_MIGRATION_*`) e a aplicação com o papel restrito (`ZENY_DB_*`), só com DML;
  - o papel da aplicação é criado fora das migrações, sem senha no repositório: no compose, por um script de inicialização do `db`; nos testes, pelo `PostgresContainer`; a implantação documenta o mesmo passo;
  - as migrações concedem privilégios ao papel da aplicação em todos os schemas, inclusive os que já existem em `shared` (`event_publication`, `shedlock`, `idempotency_key`), e tiram `UPDATE`/`DELETE` da tabela de auditoria; o nome do papel vem de um placeholder do Flyway ou de convenção fixa, decisão registrada no handoff;
  - o `MigrationConventions` (`src/test/kotlin/com/zeny/hotel/persistence/`) deixa de dar falso positivo em `GRANT … ON FUNCTION f(numeric(14,2))` e `ALTER DEFAULT PRIVILEGES IN SCHEMA … GRANT … ON TABLES`, com testes positivos e negativos;
  - `CLAUDE.md`, `README.md`, `docker/compose.yaml` e `docker/.env.example` passam a descrever os dois papéis; um volume de banco de desenvolvimento antigo precisa ser recriado (`down -v`) ou receber o papel novo, e o handoff diz como.
- Mascaramento: campos de senha, PIN, tokens e campos cifrados nunca aparecem nos valores antes/depois.
- `GET /api/v1/audit-entries` paginado com filtros por usuário, tipo e ID de entidade e período, exigindo permissão `audit.read` (declarada no catálogo).
- `@Audited` aplicado a todos os casos de uso de escrita de `iam` (usuários, roles, sessões, login com sucesso e falha conforme RF-ACS-11). O aspecto exige a dependência de AOP do Spring Boot 4 no catálogo.
- Campo de referência à aprovação preparado para E05.

**Fora de escopo:** auditoria de acesso a anexos (E08 usa esta porta); auditoria de leitura de dados pessoais de hóspedes (M3).

**Passos de implementação:**

1. Escrever testes de integração: escrita auditada na mesma transação (rollback não deixa entrada), antes/depois corretos, mascaramento, `UPDATE`/`DELETE` negados ao usuário da aplicação, filtros e permissão da consulta.
2. Implementar os dois papéis do banco (compose, Testcontainers, Flyway, grants e `MigrationConventions`) e confirmar que toda a suíte passa com a aplicação no papel restrito; depois, aspecto, porta, módulo e migrações de `audit`.
3. Aplicar `@Audited` aos casos de uso de `iam`.
4. Documentar no `CLAUDE.md` a regra "todo caso de uso de escrita leva `@Audited`" e como ela é verificada.

**Estratégia de execução:** `padrão` · Revisão humana: sim (estabelece a separação de papéis do banco que todas as migrações seguintes repetem)

- Investigação (`Explore`): "o que RF-ACS-11, RN-03, RNF-11, ARCHITECTURE §5 (passo 6), §8.3 e §11 exigem da auditoria e da imutabilidade?"; "onde o compose, o `PostgresContainer`/`@IntegrationTest`, o `OpenApiContractExport` e o `application.yaml` definem o usuário do banco e o do Flyway?".
- Partes paralelas: nenhuma.
- Foco do verificador: entrada gravada fora da transação (sobrevive a rollback) ou perdida em sucesso; senha, PIN ou token nos valores antes/depois; usuário da aplicação conseguindo `UPDATE`/`DELETE` (ou DDL) e testes que ainda rodam com o papel dono; senha de papel no repositório; caso de uso de escrita de `iam` sem `@Audited`.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E04 — "Trilha de auditoria" do roadmap docs/roadmaps/roadmap-m1-acesso-configuracao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Contexto confirmado", "Restrições e invariantes", a hipótese H4 e o "Protocolo de execução" —, a seção E04 inteira e o "Evidências e handoff" de E01 a E03. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E03 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: separar os papéis do banco (Flyway como dono dos schemas com ZENY_DB_MIGRATION_*, aplicação com papel restrito criado fora das migrações, no compose e no Testcontainers) e criar o módulo audit com a trilha append-only (quem, quando, dispositivo, ação, entidade, antes e depois, referência à aprovação), gravada na mesma transação por @Audited através de uma porta em shared, com campos sensíveis mascarados, papel da aplicação sem UPDATE/DELETE na tabela e GET /api/v1/audit-entries filtrado e protegido por audit.read. Corrija os falsos positivos do MigrationConventions com GRANT ON FUNCTION e ALTER DEFAULT PRIVILEGES. Aplique @Audited a todos os casos de uso de escrita de iam. Não audite anexos nem dados de hóspedes ainda.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E04 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume RF-ACS-11, RN-03, RNF-11, docs/ARCHITECTURE.md §5, §8.3 e §11 e onde compose, testes e application.yaml definem o usuário do banco e o do Flyway;
- escreva os testes de integração primeiro (transação, antes/depois, mascaramento, UPDATE/DELETE negados, filtros e permissão), separe os papéis antes do módulo audit, implemente, documente no CLAUDE.md e no README.md, regenere api/openapi.yaml, rode os comandos oficiais de build, teste e lint e suba o docker compose do zero;
- verificação independente com o Prompt do verificador, com foco em entrada fora da transação, segredo nos valores, imutabilidade contornável e escrita de iam sem @Audited.

Critérios de aceite (detalhes na seção E04):
- escrita auditada na mesma transação, sem entrada após rollback;
- a suíte inteira roda com a aplicação no papel restrito e o Flyway no papel dono; nenhuma senha de papel no repositório;
- UPDATE e DELETE na tabela de auditoria falham com o usuário da aplicação;
- senha, PIN e tokens nunca aparecem em antes/depois;
- consulta filtrada exige audit.read; todos os casos de uso de escrita de iam auditados; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E04 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Uma escrita auditada grava uma entrada com usuário, sessão de dispositivo, instante UTC, ação, tipo e ID da entidade, antes e depois.
- [ ] Se a transação do caso de uso faz rollback, nenhuma entrada fica gravada.
- [ ] Os testes de integração e o compose rodam com a aplicação no papel restrito e o Flyway no papel dono (teste que confere o `current_user` de cada um e que o papel da aplicação não executa DDL).
- [ ] `UPDATE` e `DELETE` na tabela de auditoria falham quando executados com o usuário da aplicação (teste de integração).
- [ ] O `MigrationConventionTest` aceita `GRANT … ON FUNCTION f(numeric(14,2))` e `ALTER DEFAULT PRIVILEGES IN SCHEMA` do próprio módulo e continua recusando os de outro schema.
- [ ] `CLAUDE.md`, `README.md`, `docker/compose.yaml` e `docker/.env.example` descrevem os dois papéis, sem senha real.
- [ ] Senha, PIN, refresh token e campos cifrados não aparecem nos valores antes/depois.
- [ ] `GET /api/v1/audit-entries` filtra por usuário, entidade e período; sem `audit.read` → `403`.
- [ ] Todos os casos de uso de escrita de `iam` têm `@Audited` (teste ou regra ArchUnit que confere).
- [ ] `ApplicationModules.verify()` mostra `audit` dependendo só de `shared`; build, testes, lint e verificação do contrato passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Verificação do contrato → sucesso.
- `docker compose -f docker/compose.yaml up -d --build` num volume novo, com os dois papéis do banco → readiness `UP`; `down -v` em seguida.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Dois papéis no banco complicam o compose e o Testcontainers: o usuário decidiu por eles (H4); diante de impedimento técnico real, parar e relatar em vez de trocar pelo *trigger*.
- O script de inicialização do `postgres` só roda em volume vazio: documentar a recriação do banco de desenvolvimento e o passo equivalente na implantação.

**Rollback / migração:** o papel da aplicação é criado fora das migrações e as migrações concedem privilégios; documentar no handoff como recriar o banco de desenvolvimento e como criar o papel num banco já existente.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E05 — Aprovação de superior (step-up) — #pendente

**Objetivo:** implementar o fluxo da ARCHITECTURE §6.3: ação sensível sem permissão ou acima do limite devolve `403 APPROVAL_REQUIRED`; outro usuário com a permissão aprova com login e PIN; o token de aprovação é de uso único, vale 2 min e fica vinculado a ação, recurso e solicitante; a ação repetida com `X-Approval-Token` é concluída e auditada com solicitante e aprovador.

**Por que agora:** RF-ACS-08 lista ações sensíveis em quase todos os módulos (OOO em M2, estornos em M3, tarifas em M4…). A infraestrutura precisa existir antes deles.

**Dependências e paralelismo:** E04. Pode correr em paralelo com E06, E07 e E08 (em worktree separado); conflito de edição em `api/openapi.yaml` e no `CLAUDE.md`.

**Escopo:**

- `POST /api/v1/auth/approvals` com ação, referência do recurso, login e PIN do aprovador e motivo; devolve o token de aprovação.
- Persistência de `Approval` (SPEC §8.2.1) e referência na entrada de auditoria (E04).
- Em `shared`: porta usada pela autorização para, em ação sensível sem permissão ou acima do limite, lançar `APPROVAL_REQUIRED` com `action` e `resourceRef` no corpo RFC 9457, e para consumir o `X-Approval-Token` validando o vínculo. Hoje o `ApiExceptionHandler` só acrescenta `code` e `errors[]` ao problema: a etapa estende `DomainException` (ou cria uma subclasse) com propriedades extras e documenta o cabeçalho e as propriedades no contrato.
- Bloqueio progressivo por PIN errado, separado do bloqueio de login.
- Regras: o aprovador precisa ter a permissão com limite suficiente; o aprovador não pode ser o próprio solicitante; token expirado, reutilizado ou de outra ação/recurso/solicitante é rejeitado.
- Aplicação nos casos de uso sensíveis existentes de `iam` ("gerenciar usuários e roles", RF-ACS-08) e um teste com uma ação de exemplo com limite.

**Fora de escopo:** ações sensíveis dos módulos futuros (cada marco aplica a porta às suas).

**Passos de implementação:**

1. Escrever testes de integração do fluxo completo e de cada rejeição (expirado, reutilizado, vínculo errado, PIN errado com bloqueio, aprovador sem permissão, aprovador igual ao solicitante).
2. Implementar porta, endpoint, persistência e consumo.
3. Documentar no `CLAUDE.md` como marcar um caso de uso como sensível.
4. Regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; fluxo em ARCHITECTURE §6.3 e §8.2 e regras em RF-ACS-07 e 08.
- Partes paralelas: nenhuma.
- Foco do verificador: token reutilizável ou aceito para outro recurso; aprovação válida além de 2 min; PIN sem limite de tentativas; auditoria sem o aprovador.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E05 — "Aprovação de superior (step-up)" do roadmap docs/roadmaps/roadmap-m1-acesso-configuracao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Restrições e invariantes" e o "Protocolo de execução" —, a seção E05 inteira, o "Evidências e handoff" de E02 e E04, docs/ARCHITECTURE.md §6.3 e §8.2 e RF-ACS-07 e 08 em docs/SPEC.md §5.1. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E04 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: implementar o step-up: ação sensível sem permissão ou acima do limite devolve 403 APPROVAL_REQUIRED com action e resourceRef; POST /api/v1/auth/approvals valida login e PIN de outro usuário com a permissão e devolve token de uso único, válido por 2 min e vinculado a ação, recurso e solicitante; a requisição repetida com X-Approval-Token é concluída e auditada com solicitante e aprovador. PIN errado tem bloqueio progressivo próprio. Aplique aos casos de uso sensíveis de iam e não toque em módulos futuros.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E05 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de integração do fluxo e de cada rejeição primeiro, implemente, documente no CLAUDE.md, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint;
- verificação independente com o Prompt do verificador, com foco em token reutilizável ou aceito para outro recurso, validade acima de 2 min, PIN sem limite e auditoria sem aprovador.

Critérios de aceite (detalhes na seção E05):
- fluxo completo coberto por teste de integração, com auditoria contendo solicitante, aprovador e motivo;
- token expirado, reutilizado ou com vínculo diferente rejeitado com code estável;
- PIN errado repetido bloqueia aprovações do aprovador; aprovador sem permissão ou igual ao solicitante rejeitado;
- contrato regenerado; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E05 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Ação sensível sem permissão devolve `403` com `code` `APPROVAL_REQUIRED`, `action` e `resourceRef`.
- [ ] Com um token obtido em `POST /api/v1/auth/approvals`, a mesma requisição com `X-Approval-Token` é concluída, e a entrada de auditoria referencia a aprovação (solicitante, aprovador, motivo).
- [ ] O mesmo token usado uma segunda vez, após 2 min, para outro recurso, outra ação ou por outro solicitante é rejeitado com `code` estável.
- [ ] Aprovador sem a permissão (ou com limite insuficiente) e aprovador igual ao solicitante são rejeitados.
- [ ] PIN errado repetido bloqueia aprovações daquele aprovador por tempo crescente, sem bloquear o login dele.
- [ ] Ação com limite (teste de exemplo) acima do limite do usuário exige aprovação; dentro do limite, não.
- [ ] Contrato regenerado; build, testes e lint passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Relógio do servidor afetando a validade: usar o `Clock` injetável de M0 E03 e testar a expiração de forma determinística.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E06 — Configuração do hotel e data operacional — #pendente

**Objetivo:** criar o módulo `hotel` com os dados do hotel único, políticas operacionais, taxas, parâmetros e fuso, e com a data operacional corrente: consultável por todos os clientes e avançável apenas pela API pública do módulo, que publica `BusinessDateAdvanced`.

**Por que agora:** reservas, contas, governança e fechamento (M3–M6) leem políticas, taxas e a data operacional (RN-01). O evento `BusinessDateAdvanced` é o gancho de várias rotinas futuras.

**Dependências e paralelismo:** E04. Pode correr em paralelo com E05 e E07 (em worktree separado); conflito de edição em `api/openapi.yaml` e no `CLAUDE.md`. E08 depende desta etapa por causa do logotipo.

**Escopo:**

- Módulo `hotel` (a criar) com schema próprio; `Hotel` singleton (razão social, nome fantasia, CNPJ, inscrição municipal, Cadastur, `Address` com IBGE, fuso, regime tributário, referência ao logotipo — H8), `HotelPolicy` (horários de check-in/out, janela de arrumação, preços e regras de early/late, idade limite e faixas etárias de criança, textos de política), `TaxSettings` (taxa de serviço padrão, ISS, taxa de turismo por pessoa/noite desligada por padrão — Q-03), parâmetros operacionais (horário do fechamento, expiração de pré-reserva, guarda de achados, limiares de alerta) — SPEC §5.2 e §8.2.2.
- Endpoints: `GET/PUT /api/v1/hotel`, `GET/PUT /api/v1/hotel/policies`, `GET/PUT /api/v1/hotel/tax-settings`, `GET/PUT /api/v1/hotel/operational-settings` e `GET /api/v1/hotel/business-date` (qualquer usuário autenticado — RF-CFG-05). Edições com `If-Match`, permissão própria e `@Audited`.
- Validações: CNPJ válido, horários coerentes, janela de arrumação de até 3h dentro da diária de 24h e textos obrigatórios da Portaria MTur 28/2025 (RN-11). CNPJ, endereço, dinheiro e percentual usam os tipos de `shared` (`Cnpj`, `Address` com IBGE, `Money`, `Percent`), nunca tipos próprios.
- Data operacional: inicializada na configuração inicial; API pública do módulo `advanceBusinessDate(expectedCurrent)` que avança exatamente um dia, rejeita data esperada diferente da atual e publica `BusinessDateAdvanced(previous, current)` pelo outbox. Sem endpoint: quem chama é o `endofday` (M6).
- API pública de leitura para outros módulos: fuso, políticas, taxas e data operacional.

**Fora de escopo:** fechamento do dia (M6); taxa de serviço por ponto de venda e por código de lançamento (M3/M8); upload e validação do logotipo (E08).

**Passos de implementação:**

1. Escrever testes de domínio das validações (RN-11, CNPJ, horários) e do avanço da data; testes de integração dos endpoints e da publicação do evento.
2. Implementar módulo, migrações, casos de uso, controllers e API pública.
3. Declarar as permissões no catálogo e acrescentá-las aos modelos de role pertinentes (H5).
4. Regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "o que SPEC §5.2, RN-01, RN-11, RNF-14, §8.2.2 e o glossário (data operacional) definem para configuração e data operacional?".
- Partes paralelas: nenhuma.
- Foco do verificador: data operacional avançável por endpoint ou por mais de um dia; evento publicado fora do outbox; dinheiro ou percentual fora dos tipos de `shared`; `hotel` dependendo de outro módulo além de `shared`.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E06 — "Configuração do hotel e data operacional" do roadmap docs/roadmaps/roadmap-m1-acesso-configuracao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Restrições e invariantes", a hipótese H8 e o "Protocolo de execução" —, a seção E06 inteira e o "Evidências e handoff" de E02 e E04. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E04 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar o módulo hotel com dados do hotel, políticas, taxas, parâmetros operacionais e fuso (endpoints em /api/v1/hotel com If-Match, permissão e @Audited), GET /api/v1/hotel/business-date para qualquer usuário autenticado e a API pública advanceBusinessDate(expectedCurrent), que avança exatamente um dia e publica BusinessDateAdvanced pelo outbox. Não crie endpoint de fechamento nem de avanço da data; hotel depende só de shared.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E06 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume docs/SPEC.md §5.2, RN-01, RN-11, RNF-14, §8.2.2 e o verbete "Data operacional" do glossário;
- escreva primeiro os testes de domínio (validações e avanço da data) e de integração (endpoints e evento), implemente, declare as permissões, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint;
- verificação independente com o Prompt do verificador, com foco em data avançável por endpoint ou mais de um dia, evento fora do outbox e dependência indevida de hotel.

Critérios de aceite (detalhes na seção E06):
- endpoints de configuração validam CNPJ, horários e janela de arrumação (RN-11) e exigem permissão e If-Match;
- GET /api/v1/hotel/business-date responde a qualquer usuário autenticado;
- advanceBusinessDate com data esperada errada falha; com a certa avança um dia e publica BusinessDateAdvanced;
- contrato regenerado; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E06 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] `PUT /api/v1/hotel` rejeita CNPJ inválido com `code` estável; sem permissão → `403`; sem `If-Match` correto → `412`.
- [ ] Políticas rejeitam janela de arrumação acima de 3h ou horários incoerentes (RN-11), com teste.
- [ ] Valores monetários usam `Money` e percentuais usam `Percent`.
- [ ] `GET /api/v1/hotel/business-date` responde a qualquer usuário autenticado com a data no formato `YYYY-MM-DD`.
- [ ] `advanceBusinessDate` com data esperada diferente da atual falha sem efeito; com a data certa avança um dia e um teste de módulo (`Scenario` do Modulith) observa `BusinessDateAdvanced` publicado.
- [ ] Não existe endpoint que avance a data operacional.
- [ ] Todas as escritas são auditadas.
- [ ] `ApplicationModules.verify()` mostra `hotel` dependendo só de `shared`; contrato regenerado; build, testes e lint passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Regime tributário (Q-02) e taxa de turismo (Q-03) ainda indefinidos: campos configuráveis, taxa de turismo desligada por padrão.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E07 — Colaboradores, turnos e escala — #pendente

**Objetivo:** criar o módulo `staff` com setores, colaboradores (com vínculo opcional a usuário), turnos por setor, padrões de escala recorrentes (12x36, 6x1, fixo), exceções (folga, falta, férias, troca) e a API pública "escala do dia" que governança e rotinas de checklist vão consumir.

**Por que agora:** RF-PES-04: a escala alimenta a distribuição da governança (M6) e as rotinas de checklist (M2).

**Dependências e paralelismo:** E04. Pode correr em paralelo com E05, E06 e E08 (em worktree separado); conflito de edição em `api/openapi.yaml` e no `CLAUDE.md`.

**Escopo:**

- Módulo `staff` (a criar) com schema próprio; entidades `Department` (seed com os seis setores — H6), `Employee` (nome, cargo, contato, status, setor, `userId` opcional — H7), `Shift`, `SchedulePattern` e `ScheduleEntry` (SPEC §8.2.2).
- Endpoints: `/api/v1/departments`, `/api/v1/employees` (com `POST …/{id}/deactivate`), `/api/v1/shifts`, `/api/v1/schedule-patterns` e `/api/v1/schedule` (consulta por período e setor; lançamento de exceções).
- Geração das entradas de escala a partir dos padrões para um período, sem sobrescrever exceções registradas.
- Vínculo com usuário validado pela API pública de `iam` (usuário existe e está ativo; um usuário vinculado a no máximo um colaborador).
- API pública: escala de uma data (por setor), com colaboradores presentes e o turno de cada um, e "colaborador do usuário".
- Permissões declaradas, escopo `OWN` para o colaborador ver a própria escala, `@Audited` nas escritas.

**Fora de escopo:** ponto eletrônico e folha (RF-PES-05); uso da escala pela governança (M6) e pelas rotinas (M2).

**Passos de implementação:**

1. Escrever testes de domínio da geração de escala (12x36, 6x1, fixo, exceções prevalecendo) e testes de integração dos endpoints, do vínculo com usuário e da API pública.
2. Implementar módulo, migrações, casos de uso e controllers.
3. Regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em SPEC §5.3 e §8.2.2.
- Partes paralelas: nenhuma.
- Foco do verificador: geração que apaga exceções; colaborador excluído fisicamente; dois colaboradores com o mesmo usuário; `staff` acessando pacotes internos de `iam`.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E07 — "Colaboradores, turnos e escala" do roadmap docs/roadmaps/roadmap-m1-acesso-configuracao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Restrições e invariantes", as hipóteses H6 e H7 e o "Protocolo de execução" —, a seção E07 inteira, o "Evidências e handoff" de E02 e E04 e docs/SPEC.md §5.3 e §8.2.2. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E04 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar o módulo staff com setores (seis pré-carregados), colaboradores com vínculo opcional a usuário validado pela API pública de iam, turnos por setor, padrões de escala 12x36, 6x1 e fixo que geram entradas por período sem sobrescrever exceções (folga, falta, férias, troca), endpoints em /api/v1 e a API pública "escala de uma data por setor". Colaboradores só são desativados. Não implemente ponto, folha nem consumo pela governança.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E07 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva primeiro os testes de domínio da geração de escala e os de integração dos endpoints, do vínculo e da API pública; implemente, declare permissões, regenere api/openapi.yaml e rode os comandos oficiais de build, teste e lint;
- verificação independente com o Prompt do verificador, com foco em exceções apagadas pela geração, exclusão física, usuário vinculado a dois colaboradores e acesso a pacotes internos de iam.

Critérios de aceite (detalhes na seção E07):
- padrões 12x36, 6x1 e fixo geram as datas esperadas e exceções prevalecem;
- vínculo com usuário inexistente, inativo ou já vinculado é rejeitado com code estável;
- a API pública devolve a escala de uma data por setor; colaborador vê só a própria escala com escopo OWN;
- contrato regenerado; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E07 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Testes de domínio: 12x36, 6x1 e fixo geram exatamente as datas esperadas num período de 30 dias; uma exceção registrada não é sobrescrita ao gerar de novo.
- [ ] Os seis setores da SPEC §5.3 existem após a migração.
- [ ] Vínculo com usuário inexistente, inativo ou já vinculado a outro colaborador → erro com `code` estável.
- [ ] A API pública devolve, para uma data e um setor, os colaboradores escalados e seus turnos (teste de módulo).
- [ ] Usuário com escopo `OWN` vê só a própria escala.
- [ ] Colaborador só é desativado; não há exclusão física.
- [ ] Escritas auditadas; `ApplicationModules.verify()` mostra `staff` dependendo só de `shared` e `iam`; contrato regenerado; build, testes e lint passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Turnos que atravessam a meia-noite: a entrada pertence à data de início; cobrir com teste.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E08 — Armazenamento de anexos — #pendente

**Objetivo:** criar o módulo `files` com armazenamento compatível com S3 atrás de uma interface (MinIO no compose e nos testes), upload com limites de tamanho e tipo por finalidade, download só por URL assinada de curta duração, cifragem dos anexos sensíveis e auditoria de cada acesso a eles.

**Por que agora:** hóspedes (documentos, M3), checklists (fotos, M2), fiscal (XMLs, M7) e o logotipo do hotel dependem de anexos.

**Dependências e paralelismo:** E04, E06. Pode correr em paralelo com E05 e E07 (em worktree separado); conflito de edição em `api/openapi.yaml`, `CLAUDE.md` e dependências do build.

**Escopo:**

- Módulo `files` (a criar) com schema próprio para metadados (finalidade, módulo e entidade donos, tipo, tamanho, hash, sensível, chave de cifragem usada, criado por).
- Porta de armazenamento com adaptador S3 (D14) configurado por variáveis de ambiente; chaves de objeto organizadas por módulo e entidade (ARCHITECTURE §14).
- Catálogo de finalidades em código (ex.: logotipo do hotel, foto de checklist, documento de hóspede) com limite de tamanho, tipos aceitos e se é sensível; módulos futuros acrescentam finalidades.
- Endpoints: `POST /api/v1/files` (multipart, com finalidade) e `GET /api/v1/files/{id}/download-url` (URL assinada curta).
- Anexo sensível cifrado antes de ir ao armazenamento e servido por download que decifra; todo acesso a sensível auditado pela porta de E04. O `FieldCipher` de M0 E03 só cifra `String`: a etapa acrescenta em `shared` a cifragem de bytes/streams reaproveitando o `EncryptionKeyRing` (mesmas chaves e mesmo `kid` para rotação).
- `POST /api/v1/files` é multipart, e o `IdempotencyFilter` recusa multipart com `415`: o upload não leva `@Idempotent` (a repetição gera outro arquivo, que fica órfão sem dono).
- Compose: o `api` passa a receber `ZENY_STORAGE_*` (endpoint `http://storage:9000`, credenciais e bucket), e o `storage-init` cria um usuário de serviço do MinIO com política só do bucket, usado pelo `api` no lugar do root. Variáveis novas no `docker/.env.example` sem segredo real e documentadas no `CLAUDE.md` e no `README.md`.
- Testes com o módulo MinIO do Testcontainers usando a imagem `pgsty/minio` na mesma tag do compose (a padrão, `minio/minio`, deixou de ser publicada); dependências novas (cliente S3, Testcontainers MinIO) no `gradle/libs.versions.toml`.
- API pública para outros módulos: confirmar que um arquivo existe e pertence a uma finalidade.
- Logotipo do hotel (H8): `hotel` passa a usar essa API para validar o ID do logotipo ao salvar os dados do hotel (aresta `hotel` → `files`, já na ARCHITECTURE §3.1).

**Fora de escopo:** finalidades e regras de cada módulo consumidor; XMLs fiscais (M7); espelhamento para storage externo (M8).

**Passos de implementação:**

1. Escrever testes de integração com MinIO via Testcontainers: upload válido, tipo e tamanho rejeitados, URL assinada que expira, sensível cifrado no bucket e acesso auditado.
2. Implementar módulo, adaptador e endpoints.
3. Ligar a validação do logotipo em `hotel` (teste: ID inexistente ou de outra finalidade → `422` com `code` estável).
4. Documentar no `CLAUDE.md` como um módulo registra uma finalidade e que qualquer módulo pode depender de `files` (regra por camadas).
5. Regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "o que ARCHITECTURE §8.3, §14 e D14 e RNF-09 exigem do armazenamento de arquivos?"; "como o `docker/compose.yaml` configura `storage` e `storage-init` e como o `FieldCipher`/`EncryptionKeyRing` cifram hoje?".
- Partes paralelas: nenhuma.
- Foco do verificador: anexo sensível legível em claro no bucket; URL assinada longa ou sem expiração; acesso a sensível sem auditoria; tipo de arquivo validado só pela extensão; `api` do compose usando o usuário root do MinIO; credencial do MinIO no repositório.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E08 — "Armazenamento de anexos" do roadmap docs/roadmaps/roadmap-m1-acesso-configuracao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Restrições e invariantes" e o "Protocolo de execução" —, a seção E08 inteira, o "Evidências e handoff" de E02, E04 e E06 e o handoff de E06 de docs/roadmaps/roadmap-m0-fundacao-tecnica.md. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E04 e E06 estão #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar o módulo files com metadados em schema próprio, porta de armazenamento com adaptador S3 (MinIO), catálogo de finalidades com limite de tamanho, tipos aceitos e flag de sensível, POST /api/v1/files (multipart, sem @Idempotent) e GET /api/v1/files/{id}/download-url com URL assinada curta, cifragem de bytes em shared sobre o EncryptionKeyRing para os anexos sensíveis e auditoria de cada acesso a eles. No compose, passe ZENY_STORAGE_* ao api com um usuário de serviço do MinIO restrito ao bucket. files depende só de shared. Ligue também a validação do logotipo em hotel (aresta hotel → files, já na ARCHITECTURE §3.1). Não implemente regras de outros módulos consumidores nem espelhamento externo.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E08 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume docs/ARCHITECTURE.md §8.3, §14 e D14, RNF-09, a configuração de storage e storage-init no docker/compose.yaml e a cifragem atual do FieldCipher;
- escreva os testes de integração com MinIO via Testcontainers (imagem pgsty/minio na tag do compose) primeiro, implemente, documente no CLAUDE.md e no README.md, regenere api/openapi.yaml, rode os comandos oficiais de build, teste e lint e faça upload e download pelo compose;
- verificação independente com o Prompt do verificador, com foco em sensível em claro no bucket, URL sem expiração curta, acesso sem auditoria e tipo validado só pela extensão.

Critérios de aceite (detalhes na seção E08):
- tamanho e tipo fora do limite da finalidade rejeitados com code estável;
- URL assinada funciona e expira no prazo configurado;
- o objeto de um anexo sensível no bucket não contém o conteúdo original e cada acesso gera auditoria;
- logotipo do hotel com ID inexistente ou de outra finalidade → 422;
- o api do compose usa um usuário de serviço do MinIO, não o root;
- contrato regenerado; build, testes e lint passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E08 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Upload acima do tamanho ou com tipo (verificado pelo conteúdo, não só pela extensão) fora da finalidade → `400` ou `415` com `code` estável.
- [ ] `GET /api/v1/files/{id}/download-url` devolve URL que baixa o arquivo e deixa de funcionar após a expiração configurada (teste com MinIO).
- [ ] O objeto de um anexo sensível no bucket não contém o conteúdo original; o download devolve o conteúdo correto.
- [ ] Cada geração de URL ou download de anexo sensível gera uma entrada de auditoria.
- [ ] Sem permissão da finalidade → `403`.
- [ ] Salvar os dados do hotel com logotipo inexistente ou de outra finalidade → `422` com `code` estável.
- [ ] No compose, o `api` recebe `ZENY_STORAGE_*` e acessa o bucket com um usuário de serviço restrito a ele; upload e download funcionam pelo proxy; variáveis novas no `docker/.env.example` sem segredo real.
- [ ] `ApplicationModules.verify()` mostra `files` dependendo só de `shared`; contrato regenerado; build, testes e lint passam.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → sucesso.
- Verificação do contrato → sucesso.
- `docker compose -f docker/compose.yaml up -d --build` → readiness `UP`, upload e download de um anexo pelo proxy; `down` em seguida.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Download de sensível por URL assinada direta ao bucket entregaria o conteúdo cifrado: servir sensíveis por endpoint que decifra, com URL assinada pelo próprio servidor; registrar a decisão no handoff.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E09 — Gate do marco M1 — #pendente

**Objetivo:** confirmar, numa cópia limpa do repositório, que toda a definição global de pronto do M1 é atendida e que a documentação reflete as convenções de autenticação, autorização, auditoria e anexos.

**Por que agora:** é o critério de entrada do marco M2.

**Dependências e paralelismo:** E05, E06, E07, E08 (que cobrem transitivamente as demais). Não paralelizável.

**Escopo:**

- Rodar build, testes, lint, verificação do contrato e compose do zero numa cópia limpa.
- Exercitar ponta a ponta, contra o compose: login do administrador inicial, criação de role e usuário, ação sensível com step-up, consulta de auditoria, configuração do hotel, escala do dia e upload/download de anexo.
- Conferir cada item da "Definição global de pronto".
- Revisar `CLAUDE.md` e `README.md`.
- Registrar a conclusão do M1 no histórico de `docs/roadmaps/README.md`.

**Fora de escopo:** funcionalidade nova; problemas encontrados voltam à etapa de origem, salvo ajustes triviais de documentação.

**Passos de implementação:**

1. Cópia limpa e validações completas.
2. Roteiro ponta a ponta com `curl` contra o compose, registrado no handoff.
3. Evidência de cada item da definição de pronto.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação: nenhuma.
- Partes paralelas: nenhuma.
- Foco do verificador: item da definição de pronto sem evidência; endpoint de `/api/v1` sem autenticação ou sem permissão; escrita sem auditoria; documentação desatualizada.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E09 — "Gate do marco M1" do roadmap docs/roadmaps/roadmap-m1-acesso-configuracao.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Definição global de pronto" e o "Protocolo de execução" —, a seção E09 inteira e o "Evidências e handoff" de E01 a E08. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 a E08 estão #concluida; se não, ou se o código divergiu do plano, pare e relate.

Objetivo: verificar numa cópia limpa do repositório, sem caches nem estado local, cada item da definição global de pronto do M1, e exercitar ponta a ponta contra o docker compose: login do administrador inicial, criação de role e usuário, ação sensível com step-up, consulta de auditoria, configuração do hotel, escala do dia e upload/download de anexo. Não implemente funcionalidade nova: problemas voltam à etapa de origem, salvo ajustes triviais de documentação.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E09 para #em-andamento no índice e no título;
- sem investigação delegada;
- rode do zero os comandos oficiais de build, teste, lint e verificação do contrato e o roteiro ponta a ponta com curl contra o compose; registre a evidência de cada item;
- registre a conclusão do M1 no histórico de docs/roadmaps/README.md;
- verificação independente com o Prompt do verificador, com foco em item sem evidência, endpoint sem autenticação ou permissão, escrita sem auditoria e documentação desatualizada.

Critérios de aceite (detalhes na seção E09):
- todos os itens da definição global de pronto marcados com evidência;
- roteiro ponta a ponta registrado no handoff;
- CLAUDE.md e README.md coerentes com o código; docs/roadmaps/README.md atualizado.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E09 (snapshot, verificações, resultados, parecer do verificador, notas para o marco M2) e responda com um resumo curto. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Todos os itens da "Definição global de pronto" estão marcados, cada um com evidência numa cópia limpa.
- [ ] O roteiro ponta a ponta contra o compose está registrado no handoff com os comandos e as respostas relevantes.
- [ ] `CLAUDE.md` e `README.md` estão coerentes com o código (autenticação, autorização, step-up, auditoria, anexos, variáveis de ambiente).
- [ ] `docs/roadmaps/README.md` registra a conclusão do M1 no histórico de revisões.

**Validação obrigatória:**

- Comandos oficiais de build, teste, lint e verificação do contrato numa cópia limpa → sucesso.
- `docker compose -f docker/compose.yaml up -d` na cópia limpa → readiness `UP` e roteiro ponta a ponta concluído; `down` em seguida.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Variáveis de ambiente obrigatórias novas (chaves JWT e administrador inicial da E01, `ZENY_DB_MIGRATION_*` da E04, `ZENY_STORAGE_*` do `api` na E08) ausentes do `docker/.env.example` ou da documentação: o gate falha e a etapa de origem corrige.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para o marco M2: —

---

## Histórico de revisões

- 2026-10-13 — roadmap criado.
- 2026-10-13 — aplicada a regra por camadas da ARCHITECTURE §3.1 (decisão do usuário): `hotel` → `files` para o logotipo (H8; E08 passa a depender de E06 e valida o logotipo); portas de autorização e auditoria em `shared` mantidas por desenho (evitam o ciclo `iam` ↔ `audit`), com a justificativa atualizada nas restrições.
- 2026-10-14 — sincronizado com o código depois do gate do M0 (commit `5dd29f5`): "Contexto confirmado" passa a citar caminhos, comandos e o estado real de segurança, banco, idempotência, compose e CI, além das pendências herdadas do M0 com a etapa que trata cada uma. Decisões do usuário: H1 confirmada, H2 (admin por variáveis no primeiro boot), H3 (JWT RS256 com `kid`), H4 (dois papéis no banco, sem *trigger*) e a nova H9 (`Idempotency-Key` pelo id do usuário). E01 ganha MDC, apoio de teste com usuário autenticado, variáveis no compose e validação no compose; E03 ganha o `409` por unicidade e o teste do `412` por lock otimista; E04 passa a separar os papéis do banco, corrigir o `MigrationConventions` e pedir revisão humana; E05 estende o problema RFC 9457 com propriedades extras; E08 ganha cifragem de bytes em `shared`, upload sem `@Idempotent`, usuário de serviço do MinIO no compose e Testcontainers com `pgsty/minio`. Limite de tentativas do outbox adiado para o M2. Nenhuma etapa foi criada, removida ou renumerada.
