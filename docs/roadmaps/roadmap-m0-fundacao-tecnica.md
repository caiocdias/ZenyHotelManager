# Roadmap — M0 Fundação técnica do servidor

> Arquivo: `docs/roadmaps/roadmap-m0-fundacao-tecnica.md` · criado em 2026-10-13 · última revisão 2026-10-13
>
> Marco 0 de 11 · mapa dos marcos em [`docs/roadmaps/README.md`](README.md) · próximo marco: [M1 — Acesso, auditoria e configuração](roadmap-m1-acesso-configuracao.md)

## Como usar

Duas formas, ambas na raiz do repositório:

- **Manual:** cole o prompt de uma etapa numa sessão limpa do Claude Code. Uma etapa por sessão.
- **Automática:** cole o prompt da seção "Execução automática" numa sessão limpa; ela executa até 3 etapas em sequência, um subagente por etapa mais um verificador, e para para revisão. Para não parar em cada pedido de permissão, libere antes os comandos de build/teste do projeto nas permissões do Claude Code (`./gradlew` e `docker`).

Etapas marcadas como paralelizáveis só devem rodar ao mesmo tempo em worktrees git separados; na mesma pasta, rode-as uma de cada vez.

Em ambas, quem executa segue o "Protocolo de execução", atualiza a tag da etapa e preenche "Evidências e handoff". Não pule dependências. Revise e faça commit entre etapas ou lotes — nenhuma sessão faz commit por conta própria.

## Objetivo

Criar o esqueleto do servidor Zeny: um projeto Kotlin/Spring Boot com Spring Modulith que compila, roda testes unitários e de integração (PostgreSQL via Testcontainers), verifica os limites entre módulos, gera e confere o contrato `api/openapi.yaml` e sobe completo em `docker compose`. O marco entrega também o kernel `shared` (dinheiro, IDs, erros, idempotência) e uma CI que barra regressões. Nenhuma regra de negócio do hotel é implementada aqui: os módulos de domínio começam em M1.

## Contexto confirmado

- Repositório greenfield: só há documentação (`docs/SPEC.md`, `docs/ARCHITECTURE.md`, `docs/roadmaps/`), um `CLAUDE.md` com a visão do projeto e as convenções de git, e um `.gitignore` que ignora `*.pdf`. Não existe código, manifesto de build nem `README.md`.
- Remoto git: `github.com/caiocdias/ZenyHotelManager`.
- Stack decidida (ARCHITECTURE §2): Kotlin 2.x sobre JDK 25 LTS (D2); Spring Boot 4.x, Spring Security, Spring Data JPA e Spring Modulith 2.x (D3); PostgreSQL 18 com um schema por módulo (D4); JPA/Hibernate para escrita, `JdbcClient` para leitura e Flyway para migrações (D5); REST em `/api/v1`, code-first com springdoc, `api/openapi.yaml` gerado e versionado (D6); UUIDv7 gerados na aplicação (D15); `timestamptz` em UTC e `Money` sobre `numeric(14,2)` (D16); código, API e banco em inglês (D17).
- Estrutura de pastas prevista: ARCHITECTURE §4 (projeto Gradle único em Kotlin DSL com *version catalog*, `docker/`, `api/`, um pacote por módulo com `domain/`, `application/`, `infrastructure/` e `web/`).
- Convenções de API: ARCHITECTURE §7 (RFC 9457 com `code` estável, `Idempotency-Key`, `ETag`/`If-Match`, paginação por cursor, dinheiro como string decimal).
- Implantação: ARCHITECTURE §15.1 (compose com `api`, `db`, `storage`, `proxy`, `backup`; imagem multi-estágio, usuário não-root, `HEALTHCHECK` em `/actuator/health/readiness`).
- Observabilidade: ARCHITECTURE §16. Testes e qualidade: ARCHITECTURE §17 (JUnit 5, Kotest assertions, MockK, Testcontainers, ArchUnit, detekt, ktlint, Kover, oasdiff).
- Comandos de build e teste: **ainda não existem**. A etapa E01 os define e os registra no `CLAUDE.md` e no `README.md`; as etapas seguintes os leem de lá.

## Escopo

**Incluído:** projeto Gradle e aplicação Spring Boot; Spring Modulith com verificação de módulos; regras ArchUnit de camadas; lint e cobertura; banco, Flyway, JPA e Testcontainers; outbox de eventos do Modulith e ShedLock; kernel `shared` (tipos de valor, IDs, erros, cifragem de campo); convenções de API e geração do contrato OpenAPI; idempotência de requisições; imagem Docker e compose de desenvolvimento; observabilidade básica; CI.

**Fora de escopo:**

- Qualquer módulo de domínio (`iam`, `hotel`, `rooms`…): marcos M1 em diante. Autenticação e autorização reais chegam em M1; até lá, a segurança fica num estado mínimo documentado (E04).
- Tempo real (SSE) e sincronização offline: M2.
- Serviço de backup, TLS público e decisão nuvem × local (SPEC Q-06): M8.
- Armazenamento de arquivos com regras de negócio (`files`): M1. Aqui o MinIO só sobe no compose.

## Restrições e invariantes

- Respeitar as decisões D1–D17 da ARCHITECTURE §2. Mudar alguma delas exige parar e perguntar ao usuário.
- Identificadores de código, API e banco em inglês (D17); documentação e mensagens ao usuário em pt-BR.
- Nada de segredos no repositório: chaves e senhas vêm de variáveis de ambiente ou Docker secrets (ARCHITECTURE §15.1). Valores de desenvolvimento ficam num `.env.example` sem segredos reais.
- Dinheiro nunca em ponto flutuante (RN-02); instantes em UTC (RN-01, RNF-14).
- O grafo de módulos é acíclico e verificado em teste desde o primeiro módulo (RNF-15).
- O `api/openapi.yaml` é gerado pelo build e versionado; nunca editado à mão.

## Hipóteses e decisões em aberto

| # | Hipótese ou decisão | Impacto se errada / sem decisão | Etapa afetada |
|---|---|---|---|
| H1 | Pacote base `com.zeny.hotel`. **Confirmado pelo usuário em 2026-10-13**, na revisão humana da E01. | Renomear pacotes depois é mecânico, mas toca todos os arquivos. | E01 |
| H2 | Versões exatas: a última estável de Spring Boot 4.x, Kotlin 2.x, Spring Modulith 2.x compatível e springdoc compatível com Boot 4, todas no *version catalog*. | Incompatibilidade entre versões aparece no primeiro build; E01 ajusta e registra as versões escolhidas. | E01 |
| H3 | JDK 25 e Docker instalados na máquina que executa as etapas. Confirmado em 2026-10-13: OpenJDK 25.0.4.1, Docker 29.1.3 com acesso sem `sudo` e Docker Compose 2.40.3. Se rodar em outra máquina, confira de novo. | Sem JDK o Gradle não roda (E01); sem Docker não há Testcontainers (E02) nem compose (E06). A etapa confere e se bloqueia se faltar. | E01, E02, E06 |
| H4 | CI no GitHub Actions, já que o remoto é GitHub. A ARCHITECTURE §17 diz "provedor a definir". | Trocar de provedor depois só reescreve o arquivo do pipeline. | E07 |
| H5 | oasdiff roda na CI pela imagem/ação oficial, comparando o contrato com o da branch principal; localmente é opcional. | Se não houver ação adequada, E07 usa o binário via script. | E07 |

## Definição global de pronto

- [ ] O comando oficial de build (registrado no `CLAUDE.md`) passa do zero numa cópia limpa do repositório, incluindo testes unitários, de integração, de arquitetura e lint.
- [ ] `ApplicationModules.verify()` passa e gera a documentação dos módulos.
- [ ] `api/openapi.yaml` é gerado pelo build, está versionado e a verificação de diff falha se ele estiver desatualizado.
- [ ] `docker compose -f docker/compose.yaml up` sobe `api`, `db`, `storage` e `proxy`, e `/actuator/health/readiness` responde `UP`.
- [ ] A CI roda build, testes, verificação de módulos, diff do contrato, oasdiff e build da imagem.
- [ ] `CLAUDE.md` e `README.md` descrevem os comandos oficiais e as convenções estabelecidas neste marco.

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
Você é o verificador independente da etapa ETAPA do roadmap docs/roadmaps/roadmap-m0-fundacao-tecnica.md. Você não participou da implementação e não deve editar nenhum arquivo.
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
Você é o orquestrador do roadmap docs/roadmaps/roadmap-m0-fundacao-tecnica.md neste repositório. Seu papel é coordenar subagentes, não implementar: não edite código e mantenha seu contexto enxuto (leia o roadmap e os relatórios dos subagentes, não o código).

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
| E01 | Esqueleto Gradle, Spring Boot e Modulith | #concluida | — | Projeto que compila e testa, com verificação de módulos, ArchUnit, lint e comandos oficiais documentados |
| E02 | Banco, migrações e testes de integração | #concluida | E01 | PostgreSQL 18, Flyway por módulo, JPA/JdbcClient, outbox do Modulith, ShedLock e base de Testcontainers |
| E03 | Kernel compartilhado: tipos de valor e cifragem | #pendente | E01 | `Money`, `Percent`, UUIDv7, números legíveis, CPF/CNPJ, `Address` e cifragem de campo em `shared` |
| E04 | Convenções de API e contrato OpenAPI | #pendente | E02, E03 | Erros RFC 9457, paginação por cursor, `ETag`/`If-Match`, `api/openapi.yaml` gerado e verificado |
| E05 | Idempotência de requisições | #pendente | E04 | `Idempotency-Key` com respostas guardadas por 24h |
| E06 | Imagem Docker, compose e observabilidade | #pendente | E04 | Dockerfile, `docker/compose.yaml`, health, logs JSON e métricas Prometheus |
| E07 | Pipeline de CI | #pendente | E05, E06 | GitHub Actions com build, testes, contrato, oasdiff e imagem |
| E08 | Gate do marco M0 | #pendente | E07 | Definição global de pronto verificada do zero |

---

## E01 — Esqueleto Gradle, Spring Boot e Modulith — #concluida

**Objetivo:** criar o projeto Gradle (Kotlin DSL, *version catalog*) com a aplicação Spring Boot vazia, Spring Modulith, teste de modularidade, regras ArchUnit de camadas, detekt, ktlint e Kover, e definir os comandos oficiais de build, teste e lint.

**Por que agora:** é o esqueleto do qual todo o resto depende. As convenções escolhidas aqui (pacote base, layout de módulo, comandos) serão repetidas em todos os marcos.

**Dependências e paralelismo:** nenhuma. Não paralelizável: toca os arquivos de build que todas as etapas usam.

**Escopo:**

- `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, Gradle wrapper (a criar).
- `src/main/kotlin/com/zeny/hotel/ZenyHotelApplication.kt` (pacote base conforme H1) e `src/main/resources/application.yaml` com os profiles `local` e `prod` (a criar).
- Plugins `kotlin-spring`, `kotlin-jpa` e `allopen` já configurados (ARCHITECTURE §18, atrito Kotlin + JPA).
- `src/test/kotlin/com/zeny/hotel/ModularityTest.kt` com `ApplicationModules.verify()` e geração da documentação dos módulos (ARCHITECTURE §4, §17).
- Regras ArchUnit das camadas internas de um módulo (ARCHITECTURE §4): `domain` sem Spring nem `web`/`infrastructure` (anotações JPA toleradas), `web` só fala com `application`, outros módulos só usam a raiz do pacote. Como ainda não há módulos, as regras devem ser genéricas por convenção de nome de subpacote e exercitadas por um teste com classes de exemplo no código de teste.
- `shared` criado como pacote vazio de módulo, com `package-info` ou anotação do Modulith que o declare módulo compartilhado.
- detekt, ktlint e Kover configurados.
- `.gitignore` cobrindo `build/`, `.gradle/`, `.idea/`, `*.iml`, `.kotlin/`, `out/`, `.env` (mantendo `*.pdf`).
- `CLAUDE.md` (já existe: atualizar a seção "Estado atual e comandos") e `README.md` (a criar) na raiz com: visão de uma linha, links para `docs/`, comandos oficiais, convenções de módulo e onde ficam os roadmaps.

**Fora de escopo:** banco e Flyway (E02), tipos de `shared` (E03), endpoints e springdoc (E04), Docker (E06), CI (E07).

**Passos de implementação:**

1. Confirmar JDK 25 com `java -version` (H3). Se não houver, marcar `#bloqueada`.
2. Gerar o wrapper do Gradle e o *version catalog* com as versões escolhidas (H2), registrando-as no handoff.
3. Criar a aplicação, os profiles e o pacote `shared`.
4. Escrever `ModularityTest` e o teste das regras ArchUnit; confirmar que uma violação proposital em classe de teste faz a regra falhar e depois removê-la.
5. Configurar detekt, ktlint e Kover; ajustar o código até o build passar.
6. Escrever `CLAUDE.md` e `README.md` com os comandos oficiais.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "quais regras de estrutura, camadas e verificação de módulos a ARCHITECTURE §2 (D1–D3, D17), §4 e §17 impõem?"; "quais versões estáveis atuais de Spring Boot 4.x, Spring Modulith 2.x, Kotlin 2.x e springdoc são compatíveis entre si?" (consultar a documentação oficial se houver acesso à web).
- Partes paralelas: nenhuma.
- Foco do verificador: regras ArchUnit que passariam com qualquer código (sem teste negativo); build que só passa com cache local; versões fora do *version catalog*; comandos documentados no `CLAUDE.md` que não rodam.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E01 — "Esqueleto Gradle, Spring Boot e Modulith" do roadmap docs/roadmaps/roadmap-m0-fundacao-tecnica.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md e AGENTS.md (se existirem), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e as hipóteses H1–H3 —, a seção E01 inteira e docs/ARCHITECTURE.md §2, §4 e §17. Rode git status e preserve mudanças preexistentes que não sejam suas. Esta etapa não tem dependências; se o repositório já tiver código de build que diverge do roadmap, pare e relate sem implementar.

Objetivo: criar o projeto Gradle (Kotlin DSL, version catalog) com a aplicação Spring Boot vazia em Kotlin/JDK 25, Spring Modulith com ModularityTest, regras ArchUnit de camadas com teste negativo, detekt, ktlint e Kover, e documentar os comandos oficiais de build, teste e lint em CLAUDE.md e README.md. Não crie banco, endpoints, Docker nem CI: são etapas seguintes. Se JDK 25 não estiver instalado, marque E01 como #bloqueada.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E01 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume as regras de estrutura e camadas da ARCHITECTURE §2, §4 e §17 e as versões compatíveis atuais de Spring Boot 4.x, Spring Modulith 2.x, Kotlin 2.x e springdoc;
- escreva o ModularityTest e os testes ArchUnit primeiro, implemente e rode o build completo;
- verificação independente com o Prompt do verificador, com foco em regras ArchUnit sem teste negativo, versões fora do version catalog e comandos documentados que não rodam.

Critérios de aceite (detalhes na seção E01):
- o comando oficial de build passa do zero e executa ModularityTest, ArchUnit, detekt e ktlint;
- uma violação de camada em classe de teste faz o teste ArchUnit falhar;
- .gitignore cobre os artefatos do Gradle, Kotlin e IDE;
- CLAUDE.md e README.md listam os comandos oficiais e as convenções de módulo.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E01 (snapshot, arquivos alterados, versões escolhidas, pacote base, comandos oficiais e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] O comando oficial de build passa do zero (sem cache do Gradle do projeto) e executa compilação, testes, `ModularityTest`, regras ArchUnit, detekt e ktlint.
- [ ] Existe um teste que comprova que uma classe de `domain` importando Spring ou `web` faz a regra ArchUnit falhar.
- [ ] Todas as versões de dependências e plugins estão em `gradle/libs.versions.toml`.
- [ ] A aplicação sobe com o profile `local` (sem banco ainda) e encerra sem erro, ou o motivo de não subir sem banco está documentado e coberto por um teste de contexto.
- [ ] `git status` após o build não mostra artefatos não ignorados.
- [ ] `CLAUDE.md` e `README.md` existem e listam os comandos oficiais de build, teste e lint, o pacote base e a estrutura de um módulo.

**Validação obrigatória:**

- `java -version` → JDK 25.
- Comando oficial de build definido nesta etapa (provável `./gradlew build`) → sucesso.
- Comandos oficiais de lint (prováveis `./gradlew detekt ktlintCheck`) → sucesso.
- `git status --short` → nenhum artefato de build listado.

**Bloqueios:** Nenhum bloqueio conhecido. Se JDK 25 não estiver instalado (H3), o bloqueio é: instalar o JDK 25 e trocar a tag para `#pendente`.

**Riscos e mitigação:**

- Spring Boot 4 recente com bibliotecas ainda sem suporte (springdoc, ShedLock): verificar compatibilidade na investigação e registrar alternativas no handoff.
- Regras ArchUnit amplas demais quebrando os módulos futuros: cobrir com testes de exemplo e documentar no `CLAUDE.md`.

**Evidências e handoff:**

- Snapshot inicial: `831eeec242ceaa8bc87940b9e6a4ae98c36bd8be` (recebido do orquestrador). Snapshot ao fim da implementação: `bf64452e1f8fe229636a5a57cff2cdad72387b3c` (antes de preencher este handoff).
- Arquivos alterados:
  - Build: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`), `config/detekt/detekt.yml`, `.editorconfig`, `.gitignore`.
  - Aplicação: `src/main/kotlin/com/zeny/hotel/ZenyHotelApplication.kt`, `src/main/kotlin/com/zeny/hotel/shared/SharedModule.kt`, `src/main/resources/application.yaml`.
  - Testes: `src/test/kotlin/com/zeny/hotel/ModularityTest.kt`, `ZenyHotelApplicationTest.kt`, `architecture/LayerRules.kt`, `architecture/LayerArchitectureTest.kt`, `architecture/LayerRulesTest.kt` e as classes de exemplo em `src/test/kotlin/archfixtures/` (um layout correto e cinco violações propositais).
  - Documentação: `CLAUDE.md` (seção "Estado atual e comandos" reescrita; pacote base marcado como adotado), `README.md` (novo), este roadmap (tag e handoff).
- Decisões:
  - Versões (todas em `gradle/libs.versions.toml`, consultadas no Maven Central e no Gradle Plugin Portal em 2026-10-13): Gradle 9.8.1 (wrapper com `distributionSha256Sum`), Kotlin 2.4.21, Spring Boot 4.1.1, Spring Modulith 2.1.1 (feito sobre Boot 4.1.1 e Spring 7.0.9), ArchUnit 1.5.1, Kotest 6.2.5, MockK 1.14.11, detekt 2.0.0-alpha.6 (plugin `dev.detekt`), ktlint-gradle 14.2.0 com ktlint 1.8.0, Kover 0.9.11. JUnit, kotlin-reflect, Jakarta Persistence e os starters vêm dos BOMs.
  - BOMs como `platform(...)` nativo do Gradle, sem o plugin `io.spring.dependency-management`: assim o Kotlin do catálogo (2.4.21) prevalece sobre o 2.3.21 que o BOM do Boot 4.1.1 traz.
  - detekt na linha 2.0 alpha: a 1.23.8 (última estável, fev/2025) embute Kotlin 2.0.21 e não acompanha Kotlin 2.4 nem JDK 25; a 2.0.0-alpha.6 embute o compilador Kotlin 2.4.10. O `check` roda `detekt`, `detektMain` e `detektTest` (as duas últimas com resolução de tipos). Trocar pela 2.0 estável quando sair.
  - Pacote base `com.zeny.hotel` (H1). Não foi possível confirmar com o usuário dentro do subagente; fica para a revisão humana desta etapa.
  - `shared` declarado por `@PackageInfo` + `@ApplicationModule(displayName = "Shared kernel", allowedDependencies = [])` em `shared/SharedModule.kt` (Kotlin não tem `package-info.java`) e como módulo compartilhado por `@Modulithic(sharedModules = ["shared"])`.
  - Regras ArchUnit (`LayerRules`, parametrizadas pelo pacote base): `domain` sem `org.springframework..`; `domain` sem `jakarta.persistence..`/`org.hibernate..` exceto anotações e enums; `domain` sem `application`/`infrastructure`/`web` (incluir `application` vai além do texto da ARCHITECTURE §4, por ser a camada mais interna); `web` sem `domain`/`infrastructure`; nenhum módulo usa `domain`/`application`/`infrastructure`/`web` de outro módulo (os demais subpacotes, como *named interfaces*, ficam com o `ApplicationModules.verify()`). Todas com `allowEmptyShould(true)` enquanto não há módulos.
  - Aplicação sem web (só `spring-boot-starter` + `spring-modulith-starter-core`); E04 acrescenta o webmvc. Sem profile padrão: o profile é sempre explícito.
  - Kover sem limite mínimo de cobertura por enquanto (ARCHITECTURE §17 fala em módulos críticos; definir quando existirem).
  - Comandos oficiais registrados no `CLAUDE.md` e no `README.md`: `./gradlew build`, `./gradlew test`, `./gradlew lint` (tarefa agregadora nova: `detekt`, `detektMain`, `detektTest`, `ktlintCheck`), `./gradlew ktlintFormat`, `./gradlew koverHtmlReport`, `./gradlew bootRun --args='--spring.profiles.active=local'`.
- Validações executadas:
  - `java -version` → OpenJDK 25.0.4.1.
  - Testes escritos antes do código: `./gradlew compileTestKotlin` falhou por falta de `ZenyHotelApplication` antes da implementação.
  - `rm -rf build .gradle .kotlin && ./gradlew build --no-build-cache` → `BUILD SUCCESSFUL`; executou `detekt`, `detektMain`, `detektTest`, `ktlintMainSourceSetCheck`, `ktlintTestSourceSetCheck`, `ktlintKotlinScriptCheck`, `test` e `koverVerify`. Testes: `ModularityTest` 3/3, `LayerArchitectureTest` 5/5, `LayerRulesTest` 6/6, `ZenyHotelApplicationTest` 1/1, sem falhas. Documentação do Modulith gerada em `build/spring-modulith-docs/` (`components.puml`, `module-shared.*`, `all-docs.adoc`).
  - Cópia limpa só com os arquivos não ignorados (`git ls-files -co --exclude-standard`) num diretório temporário → `./gradlew build --no-build-cache` com `BUILD SUCCESSFUL`.
  - `./gradlew lint` → sucesso. Arquivo mal formatado de propósito em `shared/` → `ktlintMainSourceSetCheck` FAILED com 6 apontamentos; arquivo removido.
  - Teste negativo permanente: `LayerRulesTest` comprova que `SpringAwareBooking` (domain → Spring), `WebAwareBooking` (domain → web), `PersistingBooking` (domain → `EntityManager`), `LeakyController` (web → domain) e `ChargeService` (módulo → `domain` de outro) violam a regra correspondente, e que o layout `compliant` (com `@Entity`/`@Enumerated` em domain) passa em todas.
  - Violação proposital em código de produção (`com.zeny.hotel.probe.domain.BadDomain` com `@Component` e dependência de `probe.web`) → `LayerArchitectureTest` com 2 falhas (Spring e camadas externas); classe removida em seguida.
  - Mutação das regras (Spring trocado por pacote inexistente; `domain` retirado da regra de `web`) → `LayerRulesTest` com 2 falhas; arquivo restaurado.
  - Aplicação: `java -jar build/libs/zeny-hotel-manager-0.0.1-SNAPSHOT.jar --spring.profiles.active=local` sobe em ~0,7 s ("Started … profile local"), segue rodando e, com SIGTERM, encerra pelo shutdown hook com código 143 e nenhum `ERROR`/exceção no log. Ela não encerra sozinha porque o `spring-modulith-moments` (do starter core) agenda tarefas num executor não-daemon. `ZenyHotelApplicationTest` cobre a subida do contexto com o profile `local`.
  - `./gradlew koverLog koverHtmlReport` → relatório gerado (cobertura de linhas 33%: só o `main` fica descoberto).
  - `git status --short` após o build → só fontes, wrapper e documentação; nenhum artefato de `build/`, `.gradle/` ou `.kotlin/`.
  - Correção do problema baixo 1 do verificador: fixtures `archfixtures.webinfra` (`RepositoryController`, web → infrastructure), `archfixtures.domainapp` (`CommandAwareBooking`, domain → application) e `archfixtures.domaininfra` (`RecordAwareBooking`, domain → infrastructure), com 3 testes novos em `LayerRulesTest` (9/9 verde, `compliant` segue passando em todas as regras). Mutações de `LayerRules.kt`, uma por vez (`infrastructure` retirado da regra de `web`; `application` e depois `infrastructure` retirados da regra de camadas externas de `domain`) → em cada uma, só o teste novo correspondente falhou; arquivo restaurado sem mudança. `./gradlew build` → `BUILD SUCCESSFUL`.
- Parecer do verificador: **APROVADO** (verificador independente disparado pelo orquestrador, snapshot da verificação `b1a10e67b1ceea62f525ad86e05eeb6dc8ddbd83`). Os 6 critérios foram aprovados. Evidências: o build oficial passou numa cópia limpa com `--no-build-cache` e também com `GRADLE_USER_HOME` vazio; das 7 mutações das regras ArchUnit, 5 foram pegas; violações reais em produção, no detekt e no Modulith fizeram os testes falharem; todos os comandos documentados rodaram; o sha256 do wrapper confere com a distribuição oficial. Nenhum problema grave ou médio. Problemas baixos:
  1. Faltam fixtures negativas para web→infrastructure, domain→application e domain→infrastructure (`LayerRules.kt:62` e `:72`): retirar essas camadas das regras mantém `LayerRulesTest` verde.
  2. As regras passam sem examinar nada em produção (`allowEmptyShould(true)`, sem módulos ainda); revisar quando o primeiro módulo chegar.
  3. A aplicação só encerra com SIGTERM por causa do executor não-daemon do `spring-modulith-moments` (documentado).
  4. O detekt está em `2.0.0-alpha.6`, pré-release no build oficial (justificado).
  5. `koverVerify` roda sem regra de cobertura e nunca falha (decisão documentada).
  6. Aviso de recursos do Gradle incompatíveis com o Gradle 10, vindo de plugin.
  7. O pacote base `com.zeny.hotel` (H1) ainda precisa da confirmação do usuário na revisão humana.
- Revisão humana (2026-10-13, entrevista com o usuário; todas as opções recomendadas foram aceitas):
  - Pacote base `com.zeny.hotel` confirmado (H1).
  - detekt `2.0.0-alpha.6` mantido no build oficial; trocar pela 2.0 estável quando ela sair.
  - Executor não-daemon do `spring-modulith-moments`: a decisão fica para a E02, junto com o agendador e o ShedLock.
  - Lacunas de teste: as fixtures negativas web→infrastructure, domain→application e domain→infrastructure entram já, na revisão da E01; o limite mínimo do Kover fica para quando houver módulos críticos.
- Notas para a próxima etapa:
  - E02/E03 acrescentam dependências em `gradle/libs.versions.toml`; libs cobertas pelos BOMs entram sem versão. Não declarar versões no `build.gradle.kts`.
  - O `spring-modulith-starter-core` já traz `spring-modulith-moments` (eventos `HourHasPassed`/`DayHasPassed`) e o log avisa "No TaskScheduler/ScheduledExecutorService bean found". E02 deve definir o agendador (ShedLock) e decidir se mantém o moments; o avanço da data operacional é do `hotel` (M1), não do moments.
  - Ao criar o primeiro módulo de verdade, conferir que as regras de `LayerRules` não ficam largas demais; se uma regra mudar, manter o exemplo que a viola em `src/test/kotlin/archfixtures/`.
  - Avisos conhecidos e inofensivos: `Configuration.setVisible` depreciado (vem de plugin, não do nosso script); `sun.misc.Unsafe` no compilador Kotlin 2.2.21 embutido no ktlint 1.8.0; "Sharing is only supported for boot loader classes" do agente do Kover/MockK nos testes.
  - `bootRun` fica em primeiro plano até Ctrl+C (documentado).

---

## E02 — Banco, migrações e testes de integração — #concluida

**Objetivo:** ligar a aplicação ao PostgreSQL 18 com Flyway (uma pasta por módulo, versão por timestamp), JPA/Hibernate e `JdbcClient`, ativar o *Event Publication Registry* do Spring Modulith e o ShedLock JDBC, e criar a base de testes de integração com Testcontainers.

**Por que agora:** todos os módulos persistem dados e publicam eventos pelo outbox. A convenção de schemas e migrações precisa estar fixada antes do primeiro módulo de domínio.

**Dependências e paralelismo:** E01. Pode correr em paralelo com E03 (em worktree separado); conflito de edição em `build.gradle.kts` e `gradle/libs.versions.toml`.

**Escopo:**

- Dependências de Spring Data JPA, Flyway (com suporte a PostgreSQL), driver PostgreSQL, Spring Modulith JPA/JDBC para o registro de eventos, ShedLock e Testcontainers.
- Configuração do Flyway para descobrir migrações em uma pasta por módulo dentro de `src/main/resources/db/migration/` e criar um schema por módulo (ARCHITECTURE §11). Documentar no `CLAUDE.md` como um módulo novo declara seu schema e sua pasta.
- Migrações iniciais em uma pasta `shared` (a criar): extensão `btree_gist` (ARCHITECTURE §11), tabela do *Event Publication Registry* e tabela do ShedLock, com nomes `V2026_…__descricao.sql`.
- Jobs: configuração do ShedLock (ARCHITECTURE §13) e reprocessamento de eventos pendentes no boot e a cada minuto.
- `application.yaml`: datasource por variáveis de ambiente, `spring.jpa.open-in-view=false`, fuso UTC no JDBC e no Hibernate.
- Base de teste de integração reutilizável com um container PostgreSQL 18 compartilhado e um exemplo de teste.

**Fora de escopo:** tabelas de domínio; MinIO e testes com S3 (M1, `files`); idempotência HTTP (E05).

**Passos de implementação:**

1. Confirmar Docker disponível (H3); sem ele, marcar `#bloqueada`.
2. Escrever o teste de integração que sobe o contexto, aplica as migrações e confere a existência das tabelas e da extensão.
3. Adicionar dependências e configuração; escrever as migrações.
4. Escrever um teste que publica um evento de exemplo dentro de uma transação e confirma que ele é registrado e consumido por um listener `@ApplicationModuleListener` (pode usar módulos de teste).
5. Documentar a convenção de migrações no `CLAUDE.md`.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "o que a ARCHITECTURE §5, §11 e §13 exige de transações, outbox, schemas, migrações e jobs?"; "como o Spring Modulith 2.x configura o Event Publication Registry em JDBC/JPA e o reprocessamento de publicações incompletas?".
- Partes paralelas: nenhuma.
- Foco do verificador: migrações que dependem da ordem de pastas e podem colidir entre módulos; fuso não UTC; evento perdido quando o listener falha; teste de integração que passa sem tocar o banco real.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E02 — "Banco, migrações e testes de integração" do roadmap docs/roadmaps/roadmap-m0-fundacao-tecnica.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E02 inteira e o "Evidências e handoff" de E01. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: ligar a aplicação ao PostgreSQL 18 com Flyway (uma pasta e um schema por módulo, versão por timestamp), JPA e JdbcClient, ativar o Event Publication Registry do Spring Modulith e o ShedLock, e criar a base de testes de integração com Testcontainers. Não crie tabelas de domínio. Se o Docker não estiver disponível, marque E02 como #bloqueada.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E02 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume ARCHITECTURE §5, §11 e §13 e a configuração do Event Publication Registry no Spring Modulith 2.x;
- escreva os testes de integração primeiro (migrações aplicadas, btree_gist presente, evento registrado e consumido), implemente e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em colisão de migrações entre módulos, fuso não UTC e evento perdido quando o listener falha.

Critérios de aceite (detalhes na seção E02):
- teste de integração com PostgreSQL 18 via Testcontainers aplica as migrações e confere btree_gist, outbox e ShedLock;
- evento publicado em transação é registrado e reprocessado se o listener falhar;
- convenção de schema e pasta de migração por módulo documentada no CLAUDE.md;
- build e testes oficiais passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E02 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Um teste de integração com PostgreSQL 18 via Testcontainers aplica todas as migrações e confirma a extensão `btree_gist`, a tabela do *Event Publication Registry* e a tabela do ShedLock.
- [ ] A convenção "uma pasta e um schema por módulo, versão `V2026_…`" está configurada no Flyway e documentada no `CLAUDE.md`, com o passo a passo para um módulo novo.
- [ ] Um teste demonstra que um evento publicado numa transação é entregue a um listener assíncrono e que, se o listener falhar, a publicação fica pendente e é reprocessada.
- [ ] Hibernate e JDBC gravam instantes em UTC (teste com `timestamptz`).
- [ ] `spring.jpa.open-in-view` está desativado.
- [ ] Build e testes oficiais passam.

**Validação obrigatória:**

- `docker info` → daemon disponível.
- Comandos oficiais de build e teste (registrados no `CLAUDE.md` por E01) → sucesso.
- Inspeção manual: nenhuma credencial real em `application.yaml`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Flyway com várias pastas pode ordenar versões entre módulos de forma inesperada: versões por timestamp e teste que aplica tudo do zero.
- Spring Modulith pode exigir schema próprio para o registro de eventos: registrar a escolha no handoff.

**Rollback / migração:** banco ainda vazio; reverter é apagar o volume de desenvolvimento.

**Evidências e handoff:**

- Snapshot inicial: `57b3aa26f1ea9b680a7e5567b94d5c7c6d7756aa` (recebido do orquestrador). Snapshot ao fim da implementação: `2196b84d8ca5a74f995b8d0fbe49bd6fb859412e` (antes de preencher este handoff).
- Arquivos alterados:
  - Build: `build.gradle.kts` (dependências de banco, outbox, ShedLock e Testcontainers; exclusão do `spring-modulith-moments`; testes com `user.timezone=America/Sao_Paulo`), `gradle/libs.versions.toml` (ShedLock 7.10.1 e libs gerenciadas pelos BOMs; saiu `jakarta-persistence-api`, que agora vem do JPA).
  - Configuração: `src/main/resources/application.yaml` (datasource por `ZENY_DB_*`, Hikari em UTC, JPA, Flyway, Event Publication Registry, staleness e intervalo do job).
  - Migrações (`src/main/resources/db/migration/shared/`): `V2026_10_13_1200__enable_btree_gist.sql`, `V2026_10_13_1201__create_event_publication.sql`, `V2026_10_13_1202__create_shedlock.sql`.
  - Código (`src/main/kotlin/com/zeny/hotel/shared/infrastructure/`): `persistence/ModuleSchemasFlywayCustomizer.kt`, `scheduling/SchedulingConfiguration.kt`, `events/EventPublicationResubmissionJob.kt`.
  - Testes: `testsupport/` (`IntegrationTest`, `PostgresContainer` + `PostgresInitializer`, entidade `TimeProbe`), `persistence/` (`DatabaseMigrationsTest`, `UtcTimeTest`, `MigrationConventionTest` + `MigrationConventions`, `ModuleSchemasFlywayCustomizerTest`), `events/` (`EventPublicationRegistryTest`, `ProbeListener`), `ZenyHotelApplicationTest` (passa a usar `@IntegrationTest` e confere `open-in-view`), `src/test/resources/db/testmigration/V2026_10_13_1900__create_time_probe.sql` (schema `test_support`, só de teste).
  - Documentação: `CLAUDE.md` (estado atual, banco, convenção de schemas e migrações com passo a passo, eventos e jobs, base de testes de integração), `README.md` (pré-requisito Docker e seção "Banco de dados"), este roadmap.
- Decisões:
  - Versões: ShedLock 7.10.1 (`shedlock-spring` + `shedlock-provider-jdbc-template`, feito sobre Spring 7.0.9). Pelos BOMs: Hibernate 7.4.5, Flyway 12.4.0 (`flyway-database-postgresql`), driver PostgreSQL 42.7.13, Jackson 3.1.5 (`jackson-module-kotlin`), Testcontainers 2.0.5 (`testcontainers-postgresql`), Awaitility 4.3.0 (via `spring-modulith-starter-test`). Imagem de teste `postgres:18` (18.6 em 2026-10-13).
  - Starters do Boot 4 modularizado: `spring-boot-starter-data-jpa`, `spring-boot-starter-flyway`, `spring-boot-starter-jackson`; outbox pelo `spring-modulith-starter-jdbc` (JDBC em vez de JPA: grava pelo mesmo `JpaTransactionManager`, com SQL explícito).
  - Flyway: um único Flyway e um único histórico (`shared.flyway_schema_history`), locations `classpath:db/migration` (varredura recursiva). O `ModuleSchemasFlywayCustomizer` descobre as pastas com `classpath*:db/migration/*/*.sql` e passa os nomes como `schemas` (`shared` primeiro), e o Flyway cria os schemas. As versões são globais, então a ordem é a do timestamp e não a das pastas; versão repetida em qualquer pasta impede o boot ("Found more than one migration with version"). `outOfOrder` fica no padrão (desligado).
  - Convenção verificada sem banco (`MigrationConventionTest`): arquivos só dentro de pasta de módulo; pasta = módulo do Modulith; nome `V<aaaa>_<mm>_<dd>_<hhmm>__<snake_case>.sql` com data e hora válidas (resolução estrita); versões únicas entre todas as pastas, inclusive `db/testmigration`; DDL (`CREATE/ALTER/DROP TABLE/VIEW/SEQUENCE/FUNCTION/TYPE…` e a tabela de `CREATE INDEX … ON`) sempre qualificado com o schema da pasta; `CREATE SCHEMA` proibido. Só migrações `V…` (sem `R__`) por enquanto.
  - Outbox (Event Publication Registry, estrutura v2 do Modulith 2.x) em `shared.event_publication`, criado pelo Flyway (`spring.modulith.events.jdbc.schema-initialization.enabled=false`). `completion-mode` no padrão (`update`): publicações concluídas ficam na tabela.
  - Reprocessamento: no boot, `spring.modulith.events.republish-outstanding-events-on-restart=true`; a cada minuto, `EventPublicationResubmissionJob` chama `FailedEventPublications.resubmit` (lotes de 100) sob `@SchedulerLock(name = "events-resubmit-failed", lockAtMostFor = PT5M)`; publicações paradas em `PUBLISHED`/`PROCESSING`/`RESUBMITTED` por mais de 10 min viram `FAILED` (`spring.modulith.events.staleness.*`, checagem a cada minuto pelo Modulith). Consequência: listeners precisam ser idempotentes (documentado no `CLAUDE.md`).
  - ShedLock: `JdbcTemplateLockProvider` com `usingDbTime()` na tabela `shared.shedlock`, `@EnableScheduling` + `@EnableSchedulerLock(defaultLockAtMostFor = PT10M)`. A tabela usa `TIMESTAMP` sem fuso (esquema oficial do ShedLock): com `usingDbTime()` o ShedLock grava `timezone('utc', CURRENT_TIMESTAMP)`, e a coluna sem fuso não depende do fuso da sessão. É a única exceção à regra de `timestamptz`, comentada na migração.
  - `btree_gist` instalada no schema `public` (`CREATE EXTENSION … WITH SCHEMA public`): é extensão confiável no PostgreSQL 13+, então o dono do banco a cria sem superusuário.
  - UTC: `SET TIME ZONE 'UTC'` em toda conexão (`spring.datasource.hikari.connection-init-sql`), porque o driver abre a sessão no fuso da JVM; `hibernate.jdbc.time_zone=UTC`. Os testes rodam com a JVM em `America/Sao_Paulo` para expor dependência do fuso da JVM.
  - JPA: `open-in-view=false`, `ddl-auto=validate`.
  - Datasource: `ZENY_DB_URL`, `ZENY_DB_USERNAME`, `ZENY_DB_PASSWORD`, sem padrão na base (em `prod` a aplicação não sobe sem elas); no profile `local`, padrão `jdbc:postgresql://localhost:5432/zeny`, usuário e senha `zeny` (só desenvolvimento; E06 deve usar os mesmos nomes no compose e no `.env.example`).
  - `spring-modulith-moments` excluído do build (decisão adiada da revisão da E01): a data operacional só avança no fechamento do dia (`hotel`, M1) e o moments acrescentaria uma tarefa agendada sem trava. **Ponto para a revisão humana.**
  - Base de testes: `@IntegrationTest` (contexto completo, profile `local`, migrações de teste em `db/testmigration`, job adiado em 24 h). Um único container `postgres:18` por JVM, ligado por `ApplicationContextInitializer` e não por bean `@ServiceConnection`: o Boot para o container de um bean quando o contexto fecha, o que quebrava os outros contextos depois do teste de reinício. Por isso `spring-boot-testcontainers` e `testcontainers-junit-jupiter` não entraram.
- Validações executadas:
  - `docker info` → Docker 29.1.3 disponível. `java -version` → OpenJDK 25.0.4.1.
  - Testes escritos antes da implementação e vistos falhando: primeiro a compilação (job inexistente); depois, com o job vazio e sem `connection-init-sql`, falharam `database sessions use UTC`, `creates one schema per module folder` e os testes de evento.
  - `./gradlew clean build --no-build-cache` → `BUILD SUCCESSFUL`; rodou `detekt`, `detektMain`, `detektTest`, `ktlint*Check`, `test` e `koverVerify`. 47 testes, 0 falhas: `ModularityTest` 3, `LayerArchitectureTest` 5, `LayerRulesTest` 9, `ZenyHotelApplicationTest` 2, `DatabaseMigrationsTest` 7, `UtcTimeTest` 4, `MigrationConventionTest` 9, `ModuleSchemasFlywayCustomizerTest` 3, `EventPublicationRegistryTest` 5. `./gradlew test` e `./gradlew lint` → sucesso.
  - Critério 1: `DatabaseMigrationsTest` (PostgreSQL 18 real) confere versão 18.x, todas as migrações aplicadas e nenhuma pendente, histórico em `shared`, um schema por pasta, `btree_gist` instalada e usável (tabela temporária com `EXCLUDE USING gist (room_id WITH =, stay WITH &&)`: diária encostada aceita, sobreposição recusada com SQLSTATE 23P01) e as colunas de `shared.event_publication` e `shared.shedlock`.
  - Critério 3: `EventPublicationRegistryTest` — publicação gravada como `PUBLISHED` dentro da transação, antes de o listener rodar, e `COMPLETED` após a entrega; rollback não grava nem entrega; listener que falha deixa a publicação `FAILED` sem `completion_date`, e o job a reprocessa até `COMPLETED` (com a trava gravada em `shared.shedlock`); depois de uma falha, um segundo contexto da aplicação sobre o mesmo banco reenvia a publicação no boot e a conclui (o evento é desserializado do banco nos dois caminhos); o job está agendado com intervalo de 1 min.
  - Mutações: corpo do job vazio → só `failed listener … resubmits it` falha; `republish-outstanding-events-on-restart: false` → só `… boots again` falha; sem `connection-init-sql` → `database sessions use UTC` falha; migração `rooms/V2026_10_13_1200__create_room.sql` (versão repetida em pasta que não é módulo) → `MigrationConventionTest` aponta a versão repetida e a pasta sem módulo, e o contexto não sobe ("Found more than one migration with version 2026.10.13.1200"). Tudo restaurado e `./gradlew test` verde de novo.
  - Critério 4: `UtcTimeTest` com a JVM em `America/Sao_Paulo` (pré-condição conferida no teste): sessão em `UTC`; `TIMESTAMPTZ '2026-03-10 01:30:00+00'::date` = `2026-03-10`; JDBC e Hibernate gravam `2026-03-10T01:30:00Z` e o banco lê `2026-03-10T01:30:00` em UTC; a leitura devolve o mesmo `Instant`.
  - Critério 5: `open-in-view` desligado no `application.yaml` e conferido em `ZenyHotelApplicationTest`.
  - Aplicação real: PostgreSQL 18 em `localhost:5432` (`docker run postgres:18`) + `java -jar build/libs/zeny-hotel-manager-0.0.1-SNAPSHOT.jar --spring.profiles.active=local` → criou o schema `shared`, aplicou 3 migrações, "Started" em ~2 s, nenhum WARN/ERROR; SIGTERM encerrou pelo shutdown hook ("No publications outstanding!"). No banco: histórico com as 3 versões, schemas `public` e `shared`, `btree_gist` em `public`. Com `--spring.profiles.active=prod` e sem `ZENY_DB_URL`, a aplicação não sobe (código 1).
  - Inspeção manual do `application.yaml`: nenhuma credencial real; só os padrões de desenvolvimento do profile `local` (`zeny`/`zeny`).
  - `git status --short` → só fontes, recursos e documentação; nenhum artefato de build.
- Parecer do verificador: **APROVADO** (verificador independente disparado pelo orquestrador, snapshot da verificação `3ef9ef213ff3ad145a2185c1d0622c0a69490f5b`). Os 6 critérios foram aprovados. Evidências: `./gradlew clean build --no-build-cache` e `./gradlew test lint --rerun-tasks` passaram (47 testes, 0 falhas); os testes usaram o container do Testcontainers, e não a porta 5432; mutações próprias foram pegas (job sem corpo, reenvio no boot desligado, sem `connection-init-sql`); uma pasta de módulo nova num banco já migrado criou o schema e aplicou a migração; em `prod`, sem as variáveis, a aplicação não sobe. Nenhum problema grave ou médio. Problemas baixos:
  1. Nenhum teste sustenta `hibernate.jdbc.time_zone: UTC`: sem a propriedade, o `UtcTimeTest` continua verde, porque o Hibernate 7 já grava `Instant` sobre `timestamptz` em UTC.
  2. Uma migração com timestamp anterior à última aplicada (de uma branch longa, por exemplo) impede o boot ("Detected resolved migration not applied to database"). O `CLAUDE.md` não explica esse efeito nem a correção (renomear para um timestamp novo).
  3. `MigrationConventions` não detecta FK para outro schema (`REFERENCES outro_schema.tabela`, proibido pela ARCHITECTURE §11), `CREATE TRIGGER`, `INSERT`/`GRANT` sem schema nem `DROP TABLE a, b` além do primeiro nome.
  4. No `CLAUDE.md`, o bullet dos plugins `kotlin-spring`, `kotlin-jpa` e `allopen` ficou solto depois de "Testes de integração"; pertencia à lista de "Estrutura de um módulo".
  5. O teste de `open-in-view` só lê a propriedade; com o webmvc da E04, vale conferir o comportamento real.
  6. Confirma os pontos de revisão humana abaixo. Sobre os padrões `zeny`/`zeny`: a restrição do roadmap prevê valores de desenvolvimento num `.env.example`, e aqui eles estão no YAML do profile `local`, embora não sejam segredos.
- Decisões da revisão humana (usuário, 2026-10-13):
  - (a) O `spring-modulith-moments` continua fora do build.
  - (b) A exceção do `TIMESTAMP` sem fuso em `shared.shedlock` foi aceita.
  - (c) O reprocessamento continua sem limite de tentativas. A E06 expõe uma métrica das publicações `FAILED` (contagem e idade da mais antiga) para alerta (bullet acrescentado ao escopo da E06). Limite e arquivamento ficam para quando houver listeners reais (M1).
  - (d) Os nomes `ZENY_DB_*` e o padrão `zeny`/`zeny` no YAML do profile `local` ficam como estão; o `.env.example` da E06 usa os mesmos nomes e valores.
  - Correções pós-revisão (problemas baixos 2, 3 e 4 do verificador):
    - (2) `CLAUDE.md`, "Schemas e migrações": com `outOfOrder` desligado, uma migração com timestamp anterior à última aplicada impede o boot ("Detected resolved migration not applied to database"); a correção é renomeá-la para um timestamp novo antes do merge.
    - (3) `MigrationConventions` passou a barrar, fora do schema do módulo ou sem schema: FK (`REFERENCES`), `CREATE [OR REPLACE] [CONSTRAINT] TRIGGER … ON`, `INSERT INTO`, `GRANT`/`REVOKE … ON` (lista de objetos, assinaturas de função ignoradas; nas formas `ON SCHEMA s` e `ON ALL … IN SCHEMA s`, `s` tem de ser o schema do módulo) e cada nome de `DROP TABLE a, b`. Testes escritos antes: `accepts references, triggers, inserts, grants and drops inside the module schema` e `rejects foreign or unqualified references, triggers, inserts, grants and drop lists` (13 casos com `assertSoftly`); antes da mudança, os 13 casos negativos falharam. Ficaram de fora, por exigirem um parser SQL: `UPDATE`/`DELETE`/`COPY` sobre tabela de outro schema, `DROP INDEX`, `DROP FUNCTION a(int), b(int)` (a lista para no parêntese), objetos citados dentro de corpo de função (`$$ … $$`) e SQL dinâmico. `CLAUDE.md` atualizado com as regras novas.
    - (4) `CLAUDE.md`: o bullet dos plugins `kotlin-spring`, `kotlin-jpa` e `allopen` voltou à lista de "Estrutura de um módulo".
    - Comandos: `./gradlew build` → `BUILD SUCCESSFUL`, 49 testes e 0 falhas (`MigrationConventionTest` com 11); `./gradlew lint --rerun-tasks` → `BUILD SUCCESSFUL`; `check_roadmap.py` neste roadmap → sem problemas estruturais.
    - Reverificação: **APROVADO** (verificador independente novo, snapshot `f2586c1f1bbd87bed650fbb9a995ea5ef5fb9181`). Os 6 critérios passam: `./gradlew clean build --no-build-cache` com 49 testes e 0 falhas, `./gradlew test lint --rerun-tasks` também verde. A rodada mexeu só em 4 arquivos; na E06 mudou apenas o bullet de escopo; as tags estão corretas. Das 8 mutações de `MigrationConventions`, 7 foram pegas. Uma sonda de 56 casos SQL pegou todos os negativos e não deu falso positivo nas migrações reais. Nenhum problema grave ou médio. Problemas baixos, a tratar quando a primeira migração de domínio (M1) os tocar:
      1. Nenhum teste negativo cobre `CREATE OR REPLACE TRIGGER` nem `CREATE CONSTRAINT TRIGGER`: a regra as barra, mas a mutação que as remove passa.
      2. Falsos positivos, que falham fechado: `GRANT … ON FUNCTION f(numeric(14,2), text)` (parênteses aninhados na assinatura), `ALTER DEFAULT PRIVILEGES IN SCHEMA … GRANT … ON TABLES`, `GRANT … ON TYPE/DOMAIN` e texto dentro de literal (`COMMENT ON … IS 'references …'`). Os dois primeiros devem aparecer no M1, com as permissões de RN-03 e do `reporting`.
      3. Outras lacunas de detecção: `LIKE`/`INHERITS` de outro schema, `CREATE CONSTRAINT TRIGGER … FROM`, `ALTER TABLE … SET SCHEMA`, `TRUNCATE`, `CREATE POLICY`/`CREATE RULE` e `COMMENT ON` objeto de outro schema.
- Notas para a próxima etapa:
  - Pontos para a revisão humana: decididos pelo usuário em 2026-10-13 (ver "Decisões da revisão humana" acima). Eram: (1) exclusão do `spring-modulith-moments`; (2) exceção do `TIMESTAMP` sem fuso em `shared.shedlock`; (3) staleness de 10 min e reprocessamento sem limite de tentativas; (4) nomes `ZENY_DB_*` e os padrões `zeny`/`zeny` do profile `local`.
  - O problema baixo 5 do verificador (`open-in-view` conferido só pela propriedade) fica para a E04: com o webmvc, testar o comportamento real (sem sessão aberta na view). O problema baixo 1 (`hibernate.jdbc.time_zone` sem teste que dependa dele) fica como está: a propriedade é defensiva para tipos sem fuso.
  - E03 (paralela) também mexe em `build.gradle.kts` e `gradle/libs.versions.toml`: integrar sem perder as linhas desta etapa.
  - E04 (webmvc): o Jackson 3 já está no classpath (`spring-boot-starter-jackson` + `jackson-module-kotlin`); confira se o `JsonMapper` do Boot continua sendo o usado pelo outbox. Com web, `open-in-view=false` passa a ter efeito real.
  - E05 (idempotência): tabela nova vai numa pasta de módulo (provavelmente `shared`), seguindo a convenção; o teste de convenção barra DDL sem schema.
  - E06: o compose deve criar o banco `zeny` e passar `ZENY_DB_URL`/`ZENY_DB_USERNAME`/`ZENY_DB_PASSWORD`; a imagem deve usar `postgres:18`. Sem as variáveis, o erro do Hikari é pouco claro ("'url' must start with \"jdbc\""); vale uma mensagem melhor. O monitor de staleness do Modulith loga três linhas INFO por minuto ("No stale publications…"); ajustar o nível de log na configuração de observabilidade.
  - M1: a primeira tabela de domínio deve conferir que o usuário de migração e o da aplicação são distintos quando chegar a imutabilidade financeira (RN-03: sem `UPDATE`/`DELETE` para o usuário da aplicação); hoje o Flyway roda com o usuário da aplicação.
  - Avisos conhecidos e inofensivos nos testes: o `ERROR` do `SimpleAsyncUncaughtExceptionHandler` vem da falha simulada do `ProbeListener`.

---

## E03 — Kernel compartilhado: tipos de valor e cifragem — #pendente

**Objetivo:** implementar no módulo `shared` os tipos de valor usados por todos os módulos: `Money`, `Percent`, IDs UUIDv7, gerador de números legíveis, CPF/CNPJ, `Address` e cifragem de campo AES-GCM com identificador de chave.

**Por que agora:** todos os módulos de domínio a partir de M1 usam esses tipos. Defini-los antes evita implementações divergentes.

**Dependências e paralelismo:** E01. Pode correr em paralelo com E02 (em worktree separado); conflito de edição em `build.gradle.kts` e `gradle/libs.versions.toml` se esta etapa precisar de biblioteca para UUIDv7.

**Escopo:** pacote `shared` (a criar, sob o pacote base): tipos de valor na raiz do pacote (API pública do módulo) e implementações internas em subpacotes.

- `Money` sobre `BigDecimal` com escala 2, só BRL, regra de arredondamento única e centralizada (RN-02); operações aritméticas, comparação, soma de listas, alocação proporcional sem perder centavos; conversor JPA para `numeric(14,2)`; serialização JSON como string decimal (ARCHITECTURE §7).
- `Percent` com `numeric(7,4)` (ARCHITECTURE §11).
- Gerador de UUIDv7 e tipos de ID tipados (`value class`) reutilizáveis; conversor JPA para `uuid`.
- Gerador de números legíveis com prefixo e ano (`R-2026-000123`, D15), apoiado em *sequence* do banco por tipo de documento.
- `Cpf` e `Cnpj` com validação de dígito verificador; `Address` com código IBGE do município.
- Cifragem de campo AES-GCM com identificador de chave para rotação e chave vinda de variável de ambiente (ARCHITECTURE §8.3); conversor JPA para colunas cifradas.
- Abstração de relógio (`Clock` injetável) para testes determinísticos.

**Fora de escopo:** data operacional e fuso do hotel (M1, módulo `hotel`); formato de erro HTTP (E04).

**Passos de implementação:**

1. Escrever testes unitários de cada tipo (Kotest assertions), incluindo arredondamento, alocação de centavos, CPF/CNPJ válidos e inválidos e ida e volta da cifragem com rotação de chave.
2. Implementar os tipos e conversores.
3. Escrever um teste de integração (base de E02, se já concluída; senão, deixar anotado no handoff) que persiste e lê `Money`, ID e campo cifrado.
4. Documentar no `CLAUDE.md` quais tipos usar para dinheiro, percentuais, IDs e dados sensíveis.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; as regras estão em RN-02 (SPEC §6), ARCHITECTURE §7, §8.3, §11 e D15/D16.
- Partes paralelas: nenhuma.
- Foco do verificador: uso de `Double` em qualquer caminho de dinheiro; arredondamento disperso; alocação que perde ou cria centavos; nonce reutilizado na cifragem; chave de cifragem com valor padrão no código.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E03 — "Kernel compartilhado: tipos de valor e cifragem" do roadmap docs/roadmaps/roadmap-m0-fundacao-tecnica.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E03 inteira e o "Evidências e handoff" de E01 (e de E02, se já concluída). Leia também RN-01 e RN-02 em docs/SPEC.md §6 e docs/ARCHITECTURE.md §7, §8.3 e §11. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: implementar no módulo shared os tipos Money (BRL, BigDecimal escala 2, arredondamento único, JSON como string), Percent, IDs UUIDv7 tipados, gerador de números legíveis por sequence, Cpf, Cnpj, Address e cifragem de campo AES-GCM com identificador de chave, cada um com conversor JPA quando aplicável. Não implemente data operacional nem formato de erro HTTP.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E03 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes unitários primeiro, implemente e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em Double em dinheiro, alocação que perde centavos, nonce reutilizado e chave com valor padrão no código.

Critérios de aceite (detalhes na seção E03):
- testes cobrem arredondamento e alocação de Money sem perda de centavos, CPF/CNPJ válidos e inválidos e cifragem com rotação de chave;
- Money serializa como string decimal e persiste em numeric(14,2);
- nenhum tipo de dinheiro usa ponto flutuante;
- build e testes oficiais passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E03 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] `Money` usa `BigDecimal` com escala 2, rejeita moeda diferente de BRL e concentra o arredondamento numa única função testada.
- [ ] A alocação proporcional de `Money` soma exatamente o total em todos os casos de teste (incluindo divisões com dízima).
- [ ] `Money` serializa em JSON como string decimal (`"123.45"`) e persiste em `numeric(14,2)`.
- [ ] UUIDv7 gerados são ordenáveis por tempo (teste).
- [ ] `Cpf` e `Cnpj` rejeitam dígito verificador errado e sequências repetidas.
- [ ] A cifragem AES-GCM usa nonce aleatório por valor, grava o identificador da chave e decifra valores gravados com a chave anterior após rotação.
- [ ] Nenhuma chave de cifragem tem valor padrão no código-fonte.
- [ ] Build e testes oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- `grep -rn "Double\|Float" src/main/kotlin` em `shared` → nenhum uso em dinheiro ou percentual.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Arredondamento divergente do middleware fiscal (RN-02): função única facilita ajuste em M7; registrar a regra escolhida (provável `HALF_EVEN` ou `HALF_UP`) como decisão no handoff.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E04 — Convenções de API e contrato OpenAPI — #pendente

**Objetivo:** estabelecer as convenções REST da ARCHITECTURE §7 como infraestrutura reutilizável em `shared` e gerar o contrato `api/openapi.yaml` no build, com uma tarefa que falha se o arquivo versionado estiver desatualizado.

**Por que agora:** todos os endpoints de M1 em diante seguem estas convenções e entram no contrato. Errar aqui se repete em centenas de endpoints.

**Dependências e paralelismo:** E02, E03. Não paralelizável com E05 nem E06 (mesmos arquivos de build e configuração web).

**Escopo:**

- springdoc com geração do contrato no build em `api/openapi.yaml` (a criar) e tarefa de verificação de diff; Swagger UI habilitado só fora do profile `prod`.
- Prefixo `/api/v1` para controllers (convenção ou anotação reutilizável).
- Erros RFC 9457 (`application/problem+json`) com `code` estável, `detail` e `errors[]` por campo; hierarquia de exceções de domínio em `shared` mapeada para status HTTP; validação de DTO convertida para `errors[]`; catálogo de códigos documentado.
- Paginação por cursor (`?cursor=…&limit=…`) com tipo de resposta padrão e cursor opaco.
- `ETag` a partir da versão do agregado e `If-Match` obrigatório nas alterações, com `412` em conflito (ARCHITECTURE §5, §7).
- Jackson: datas ISO-8601, instantes em UTC com `Z`, datas de negócio `YYYY-MM-DD`, `Money` como string.
- Segurança mínima temporária: Spring Security configurado para negar tudo em `/api/**` exceto um endpoint de exemplo e os endpoints públicos de documentação e actuator, com um comentário que aponta para M1. Nada de usuário em memória com senha fixa.
- Um controller de exemplo em código de teste (ou um endpoint `GET /api/v1/meta/version` real) que exercita erro, paginação e `ETag` nos testes.

**Fora de escopo:** `Idempotency-Key` (E05); autenticação real (M1).

**Passos de implementação:**

1. Escrever testes de API (MockMvc ou WebTestClient) para: erro de validação no formato RFC 9457, erro de domínio com `code`, paginação por cursor, `412` com `If-Match` desatualizado e serialização de datas e dinheiro.
2. Implementar as convenções e o controller de exemplo.
3. Configurar a geração de `api/openapi.yaml` e a verificação de diff; gerar e versionar o arquivo.
4. Documentar as convenções no `CLAUDE.md` (como escrever um controller novo, como lançar erro de domínio, como paginar).

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "quais convenções a ARCHITECTURE §5 e §7 e a RNF-03 e RNF-13 da SPEC exigem da API?"; "como gerar o OpenAPI do springdoc no build do Gradle sem subir o servidor manualmente, com Spring Boot 4?".
- Partes paralelas: nenhuma.
- Foco do verificador: erro devolvido sem `code` estável ou com stack trace; contrato gerado diferente do versionado sem a verificação falhar; Swagger exposto em `prod`; endpoint aberto sem intenção.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E04 — "Convenções de API e contrato OpenAPI" do roadmap docs/roadmaps/roadmap-m0-fundacao-tecnica.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E04 inteira e o "Evidências e handoff" de E01, E02 e E03. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E02 e E03 estão #concluida e que o código bate com o handoff delas; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: implementar em shared as convenções REST da docs/ARCHITECTURE.md §7 — prefixo /api/v1, erros RFC 9457 com code estável, paginação por cursor, ETag/If-Match com 412, serialização ISO-8601 e Money como string — e gerar api/openapi.yaml no build com uma tarefa que falha se o arquivo versionado estiver desatualizado. Não implemente Idempotency-Key nem autenticação real; a segurança fica mínima, negando /api/** exceto o exemplo, sem usuário com senha fixa.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E04 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume ARCHITECTURE §5 e §7, RNF-03 e RNF-13 e como gerar o OpenAPI do springdoc no build com Spring Boot 4;
- escreva os testes de API primeiro, implemente, gere e versione api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em erro sem code estável, contrato desatualizado sem falha, Swagger em prod e endpoint aberto sem intenção.

Critérios de aceite (detalhes na seção E04):
- testes cobrem erro de validação e de domínio em RFC 9457, paginação por cursor, 412 com If-Match desatualizado e serialização de datas e Money;
- api/openapi.yaml versionado e a verificação de diff falha quando ele está desatualizado;
- Swagger UI desabilitado no profile prod;
- convenções documentadas no CLAUDE.md; build e testes oficiais passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E04 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Erro de validação devolve `400` com `application/problem+json`, `code` estável e `errors[]` por campo; erro de domínio devolve o status mapeado e seu `code`; nenhuma resposta de erro contém stack trace.
- [ ] Endpoint de exemplo paginado devolve cursor opaco e respeita `limit`, com teste.
- [ ] Alteração com `If-Match` desatualizado devolve `412`; resposta de leitura traz `ETag`.
- [ ] Instantes saem em UTC com `Z`, datas de negócio como `YYYY-MM-DD` e `Money` como string decimal.
- [ ] `api/openapi.yaml` está versionado; alterar um endpoint sem regenerar faz a tarefa de verificação falhar (demonstrado no handoff).
- [ ] Swagger UI responde fora de `prod` e não responde com o profile `prod`.
- [ ] Nenhum endpoint de `/api/**` além do exemplo está acessível sem autenticação.
- [ ] `CLAUDE.md` documenta como criar controller, lançar erro de domínio e paginar.
- [ ] Build e testes oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Tarefa de verificação do contrato → sucesso com o arquivo atual; falha após mudança proposital não regenerada (desfeita em seguida).

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Plugin de geração do springdoc pode exigir subir a aplicação com banco: usar Testcontainers ou um teste que grava o arquivo, registrando a escolha no handoff.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E05 — Idempotência de requisições — #pendente

**Objetivo:** implementar o header `Idempotency-Key` da ARCHITECTURE §7: endpoints marcados como idempotentes exigem a chave, guardam a resposta por 24h e devolvem a mesma resposta em repetições.

**Por que agora:** pagamentos, lançamentos e emissão fiscal (M3, M7) dependem disso; implementar uma vez no `shared` evita variações por módulo.

**Dependências e paralelismo:** E04. Pode correr em paralelo com E06 (em worktree separado); conflito de edição em `application.yaml`.

**Escopo:**

- Anotação ou marcador para endpoints que exigem `Idempotency-Key`.
- Tabela no schema `shared` (migração nova) com chave, usuário/cliente, hash da requisição, status, corpo e cabeçalhos relevantes da resposta e expiração.
- Comportamento: sem chave → `400` com `code` estável; mesma chave e mesma requisição → resposta guardada; mesma chave com corpo diferente → `422` com `code` estável; requisição concorrente com a mesma chave → `409`.
- Job de expurgo das chaves vencidas com ShedLock.
- Documentação no `CLAUDE.md`.

**Fora de escopo:** idempotência de comandos offline por `commandId` (M2, sincronização).

**Passos de implementação:**

1. Escrever os testes de integração dos quatro comportamentos e do expurgo.
2. Implementar migração, filtro/interceptor e job.
3. Aplicar ao endpoint de exemplo de E04 e regenerar o contrato (o header aparece documentado).

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; a regra está na ARCHITECTURE §7.
- Partes paralelas: nenhuma.
- Foco do verificador: corrida entre duas requisições simultâneas com a mesma chave executando o efeito duas vezes; chave compartilhada entre usuários diferentes; resposta de erro guardada como se fosse sucesso.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E05 — "Idempotência de requisições" do roadmap docs/roadmaps/roadmap-m0-fundacao-tecnica.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E05 inteira, o "Evidências e handoff" de E04 e a linha "Idempotência" da tabela em docs/ARCHITECTURE.md §7. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E04 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: implementar em shared o header Idempotency-Key para endpoints marcados: sem chave → 400; mesma chave e mesma requisição → resposta guardada por 24h; mesma chave com corpo diferente → 422; requisição concorrente com a mesma chave → 409; expurgo das chaves vencidas por job com ShedLock. Não implemente a idempotência de comandos offline, que é do marco M2.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E05 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de integração primeiro, implemente, aplique ao endpoint de exemplo, regenere api/openapi.yaml e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em corrida entre requisições simultâneas, chave compartilhada entre usuários e erro guardado como sucesso.

Critérios de aceite (detalhes na seção E05):
- testes de integração cobrem os quatro comportamentos e o expurgo;
- duas requisições simultâneas com a mesma chave executam o efeito uma única vez;
- contrato regenerado documenta o header; build e testes oficiais passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E05 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Endpoint marcado sem `Idempotency-Key` → `400` com `code` estável.
- [ ] Repetição com a mesma chave e o mesmo corpo devolve status e corpo idênticos sem reexecutar o efeito (contador no teste).
- [ ] Mesma chave com corpo diferente → `422` com `code` estável.
- [ ] Duas requisições simultâneas com a mesma chave executam o efeito uma única vez (teste concorrente).
- [ ] A chave é isolada por usuário/cliente: outro usuário com a mesma chave não recebe a resposta guardada.
- [ ] Chaves com mais de 24h são expurgadas pelo job.
- [ ] `api/openapi.yaml` documenta o header; build e testes oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Tarefa de verificação do contrato → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Até M1 não há usuário autenticado: isolar por um identificador de cliente provisório e deixar nota no handoff para M1 trocar pelo usuário/dispositivo.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E06 — Imagem Docker, compose e observabilidade — #pendente

**Objetivo:** empacotar o servidor como imagem Docker e subir o ambiente completo de desenvolvimento com `docker compose`, com health checks, logs estruturados em JSON e métricas Prometheus.

**Por que agora:** RNF-01 exige o servidor como imagem e ambiente completo via compose. Ter o ambiente rodando desde o início evita descobrir problemas de empacotamento no fim.

**Dependências e paralelismo:** E04. Pode correr em paralelo com E05 (em worktree separado); conflito de edição em `application.yaml`.

**Escopo:**

- `docker/Dockerfile` (a criar): multi-estágio (build com JDK 25, runtime com JRE 25 enxuto e camadas do Spring Boot), usuário não-root, `HEALTHCHECK` em `/actuator/health/readiness`.
- `docker/compose.yaml` (a criar): serviços `api`, `db` (PostgreSQL 18 com volume), `storage` (MinIO com volume e bucket inicial) e `proxy` (Caddy servindo HTTP local; TLS público fica para M8). O serviço `backup` fica para M8 e aparece comentado com essa referência.
- `.env.example` (a criar) com variáveis de desenvolvimento sem segredos reais; `.env` no `.gitignore`.
- Actuator: liveness, readiness e Prometheus expostos; demais endpoints fechados.
- Logs estruturados em JSON (logging estruturado nativo do Spring Boot) no profile `prod`, com `traceId`; logs legíveis no `local`.
- Métricas técnicas via Micrometer; métrica de eventos pendentes no outbox.
- Métrica e alerta das publicações `FAILED` do outbox (`shared.event_publication`): contagem e idade da mais antiga, expostas no Prometheus para alerta. Decisão da revisão humana da E02: o reprocessamento segue sem limite de tentativas; limite e arquivamento ficam para M1, quando houver listeners reais.
- `README.md`: como subir o ambiente.

**Fora de escopo:** backup, TLS público e decisão nuvem × local (M8); métricas de negócio por integração (marcos das integrações).

**Passos de implementação:**

1. Confirmar Docker disponível (H3).
2. Escrever Dockerfile e compose; subir e ajustar até a readiness responder `UP`.
3. Escrever teste que confere a exposição dos endpoints do actuator (liveness, readiness, prometheus abertos; demais fechados).
4. Configurar logs JSON e a métrica do outbox; documentar no `README.md`.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "o que a ARCHITECTURE §15.1, §16 e a RNF-01 e RNF-12 exigem da imagem, do compose e da observabilidade?".
- Partes paralelas: nenhuma.
- Foco do verificador: imagem rodando como root; segredo real em `compose.yaml` ou `.env.example`; actuator expondo `env`, `heapdump` ou `configprops`; readiness `UP` sem banco disponível.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E06 — "Imagem Docker, compose e observabilidade" do roadmap docs/roadmaps/roadmap-m0-fundacao-tecnica.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E06 inteira e o "Evidências e handoff" de E01, E02 e E04. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E04 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar docker/Dockerfile multi-estágio (JDK 25 no build, JRE 25 no runtime, usuário não-root, HEALTHCHECK na readiness), docker/compose.yaml com api, db (PostgreSQL 18), storage (MinIO) e proxy (Caddy em HTTP local), .env.example sem segredos, actuator restrito a liveness, readiness e prometheus, logs JSON no profile prod e métrica de eventos pendentes no outbox. Não implemente backup nem TLS público (marco M8). Se o Docker não estiver disponível, marque E06 como #bloqueada.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E06 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume docs/ARCHITECTURE.md §15.1 e §16 e as RNF-01 e RNF-12 de docs/SPEC.md §7;
- escreva o teste de exposição do actuator primeiro, implemente, suba o compose e rode os comandos oficiais de build e teste do CLAUDE.md;
- verificação independente com o Prompt do verificador, com foco em imagem como root, segredo no repositório, actuator expondo endpoints sensíveis e readiness UP sem banco.

Critérios de aceite (detalhes na seção E06):
- docker compose -f docker/compose.yaml up sobe os quatro serviços e a readiness responde UP pelo proxy;
- a imagem roda como usuário não-root;
- actuator expõe só liveness, readiness e prometheus;
- logs em JSON no profile prod; README.md explica como subir o ambiente; build e testes oficiais passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E06 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] `docker compose -f docker/compose.yaml up -d` sobe `api`, `db`, `storage` e `proxy`; `/actuator/health/readiness` responde `UP` pelo proxy.
- [ ] Com o `db` parado, a readiness deixa de responder `UP`.
- [ ] `docker run` da imagem mostra usuário não-root (`id -u` diferente de 0).
- [ ] Actuator expõe só liveness, readiness e prometheus (teste automatizado).
- [ ] Com o profile `prod`, cada linha de log é JSON válido e contém `traceId` nas requisições.
- [ ] `/actuator/prometheus` inclui a métrica de eventos pendentes no outbox.
- [ ] `compose.yaml` e `.env.example` não contêm segredos reais; `.env` está no `.gitignore`.
- [ ] `README.md` explica como subir e derrubar o ambiente; build e testes oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- `docker compose -f docker/compose.yaml up -d` e `curl -fsS http://localhost/actuator/health/readiness` (porta conforme o compose) → `UP`.
- `docker compose -f docker/compose.yaml down` ao final.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Build da imagem lento sem cache: usar camadas do Spring Boot e cache do Gradle no estágio de build.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E07 — Pipeline de CI — #pendente

**Objetivo:** criar o pipeline de CI que roda build, testes (incluindo Testcontainers), verificação de módulos, verificação do contrato, oasdiff contra a branch principal e build da imagem Docker.

**Por que agora:** a partir de M1 o volume de código cresce; a CI barra regressões e mudanças incompatíveis da API (RNF-03) desde o primeiro módulo.

**Dependências e paralelismo:** E05, E06. Não paralelizável.

**Escopo:**

- `.github/workflows/ci.yml` (a criar, H4): gatilhos em push e pull request; cache do Gradle; JDK 25.
- Passos: comandos oficiais de build e teste; tarefa de verificação do contrato; oasdiff com regra de *breaking change* em `/api/v1` comparando com a branch principal (H5); build da imagem com `docker/Dockerfile` (sem push).
- Publicação da documentação de módulos do Modulith e do `api/openapi.yaml` como artefatos do job.
- `README.md` com o selo e a descrição do pipeline.

**Fora de escopo:** publicação da imagem em registry e deploy (M8).

**Passos de implementação:**

1. Escrever o workflow e validá-lo localmente o quanto possível (sintaxe com `actionlint`, se disponível).
2. Documentar no `README.md` e no `CLAUDE.md` o que a CI verifica.
3. Registrar no handoff que a primeira execução real depende de push, que fica a critério do usuário.

**Estratégia de execução:** `direta` · Revisão humana: não

- Investigação: nenhuma.
- Partes paralelas: nenhuma.
- Foco do verificador: passo que ignora falha (`continue-on-error`); oasdiff que não falha em *breaking change*; segredos referenciados sem necessidade.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E07 — "Pipeline de CI" do roadmap docs/roadmaps/roadmap-m0-fundacao-tecnica.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e as hipóteses H4 e H5 —, a seção E07 inteira e o "Evidências e handoff" de E04, E05 e E06. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E05 e E06 estão #concluida e que o código bate com o handoff delas; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar .github/workflows/ci.yml (GitHub Actions) que, em push e pull request, roda os comandos oficiais de build e teste com JDK 25 e cache do Gradle, a verificação do contrato api/openapi.yaml, o oasdiff contra a branch principal falhando em breaking change e o build da imagem docker/Dockerfile sem push, publicando a documentação de módulos e o contrato como artefatos. Não publique imagem nem configure deploy.

Siga o Protocolo de execução com a estratégia direta:
- tire o snapshot inicial e troque a tag de E07 para #em-andamento no índice e no título;
- sem subagentes: valide você mesmo a sintaxe do workflow (actionlint, se disponível) e rode localmente os comandos que o workflow chama;
- documente no README.md e no CLAUDE.md o que a CI verifica.

Critérios de aceite (detalhes na seção E07):
- o workflow executa todos os passos listados na seção E07, sem continue-on-error;
- oasdiff configurado para falhar em breaking change de /api/v1;
- os comandos que o workflow chama passam localmente.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E07 (snapshot, arquivos alterados, decisões, comandos e resultados, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] `.github/workflows/ci.yml` roda, em push e pull request: build e testes oficiais, verificação do contrato, oasdiff contra a branch principal e build da imagem.
- [ ] Nenhum passo usa `continue-on-error`.
- [ ] oasdiff está configurado para falhar em *breaking change*.
- [ ] Documentação do Modulith e `api/openapi.yaml` são publicados como artefatos.
- [ ] Os comandos chamados pelo workflow passam localmente.
- [ ] `README.md` e `CLAUDE.md` descrevem o que a CI verifica.

**Validação obrigatória:**

- `actionlint .github/workflows/ci.yml` (se disponível) → sem erros.
- Comandos oficiais de build, teste e verificação do contrato → sucesso localmente.
- `docker build -f docker/Dockerfile .` → sucesso.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- A primeira execução real só acontece após push do usuário: a etapa registra isso no handoff, e o gate E08 pede a confirmação.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E08 — Gate do marco M0 — #pendente

**Objetivo:** confirmar, a partir de uma cópia limpa do repositório, que toda a definição global de pronto do M0 é atendida, e deixar o roadmap do M1 pronto para ser sincronizado.

**Por que agora:** é o critério de entrada do marco M1.

**Dependências e paralelismo:** E07 (que depende transitivamente de todas as demais). Não paralelizável.

**Escopo:**

- Clonar o repositório local para uma pasta temporária (incluindo as mudanças não commitadas, via snapshot ou cópia) e rodar tudo do zero.
- Conferir cada item da "Definição global de pronto".
- Revisar `CLAUDE.md` e `README.md`: comandos, convenções de módulo, migrações, API, tipos de `shared`.
- Atualizar `docs/roadmaps/README.md` marcando M0 como concluído no histórico de revisões.

**Fora de escopo:** qualquer funcionalidade nova. Problemas encontrados voltam para a etapa de origem (reabrir com `#em-andamento` e registrar no handoff dela) em vez de serem corrigidos aqui, salvo ajustes triviais de documentação.

**Passos de implementação:**

1. Rodar build, testes, verificação do contrato e compose do zero numa cópia limpa.
2. Marcar cada item da definição global de pronto com a evidência.
3. Perguntar ao usuário se a primeira execução da CI no GitHub passou (depende de push); registrar a resposta.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação: nenhuma.
- Partes paralelas: nenhuma.
- Foco do verificador: item da definição de pronto marcado sem evidência; build que só passa com estado local (cache, `.env`, volume); documentação desatualizada em relação ao código.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E08 — "Gate do marco M0" do roadmap docs/roadmaps/roadmap-m0-fundacao-tecnica.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" e a "Definição global de pronto" —, a seção E08 inteira e o "Evidências e handoff" de todas as etapas E01 a E07. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 a E07 estão #concluida; se não, ou se o código divergiu do plano, pare e relate.

Objetivo: verificar numa cópia limpa do repositório, sem caches nem estado local, que cada item da definição global de pronto do M0 é atendido, com evidência. Não implemente funcionalidade nova: problemas encontrados são relatados e devolvidos à etapa de origem, salvo ajustes triviais de documentação.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E08 para #em-andamento no índice e no título;
- sem investigação delegada;
- rode do zero, numa cópia limpa, os comandos oficiais de build e teste, a verificação do contrato e o docker compose; registre a evidência de cada item da definição global de pronto;
- pergunte ao usuário se a primeira execução da CI no GitHub passou e registre a resposta;
- verificação independente com o Prompt do verificador, com foco em item sem evidência, build dependente de estado local e documentação desatualizada.

Critérios de aceite (detalhes na seção E08):
- todos os itens da definição global de pronto marcados com evidência;
- CLAUDE.md e README.md coerentes com o código;
- docs/roadmaps/README.md registra a conclusão do M0 no histórico.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E08 (snapshot, verificações, resultados, parecer do verificador, notas para o marco M1) e responda com um resumo curto. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Todos os itens da "Definição global de pronto" estão marcados, cada um com evidência numa cópia limpa.
- [ ] A execução da CI no GitHub foi confirmada pelo usuário, ou a ausência dela está registrada como pendência explícita.
- [ ] `CLAUDE.md` e `README.md` estão coerentes com o código (comandos rodam como descritos).
- [ ] `docs/roadmaps/README.md` registra a conclusão do M0 no histórico de revisões.

**Validação obrigatória:**

- Comandos oficiais de build, teste e verificação do contrato numa cópia limpa → sucesso.
- `docker compose -f docker/compose.yaml up -d` na cópia limpa → readiness `UP`; `down` em seguida.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Diferenças entre a máquina local e a CI (versão do Docker, arquitetura do processador): registrar no handoff.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para o marco M1: —

---

## Histórico de revisões

- 2026-10-13 — roadmap criado.
- 2026-10-13 — H3 confirmada: JDK 25 e Docker instalados.
- 2026-10-13 — `CLAUDE.md` criado antes de E01; E01 passa a atualizá-lo em vez de criá-lo.
