# Roadmap — M0 Fundação técnica do servidor

> Arquivo: `docs/roadmaps/roadmap-m0-fundacao-tecnica.md` · criado em 2026-10-13 · última revisão 2026-10-14
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
| H4 | CI no GitHub Actions, já que o remoto é GitHub. **Confirmado pelo usuário em 2026-10-13**, na entrevista da E07; a ARCHITECTURE §17 foi atualizada. | Trocar de provedor depois só reescreve o arquivo do pipeline. | E07 |
| H5 | oasdiff roda na CI pela imagem/ação oficial, comparando o contrato com o da branch principal; localmente é opcional. | Se não houver ação adequada, E07 usa o binário via script. | E07 |

## Definição global de pronto

Conferida no gate E08 (2026-10-14) numa cópia limpa do commit `6e9bd9c` (árvore `3777a7b`), com `GRADLE_USER_HOME` novo e `--no-build-cache`; detalhes no handoff da E08.

- [x] O comando oficial de build (registrado no `CLAUDE.md`) passa do zero numa cópia limpa do repositório, incluindo testes unitários, de integração, de arquitetura e lint. Evidência: `./gradlew build --no-build-cache` → `BUILD SUCCESSFUL` em 2 min 1 s, 26 tarefas executadas (inclusive o download do Gradle 9.8.1), 178 testes em 30 classes, 0 falhas, 0 pulados, com `detekt`, `detektMain`, `detektTest`, `ktlint*Check`, `generateOpenApi`, `checkOpenApi` e `koverVerify`.
- [x] `ApplicationModules.verify()` passa e gera a documentação dos módulos. Evidência: `ModularityTest` 3/3 no build e sozinho (`./gradlew test --tests 'com.zeny.hotel.ModularityTest'`); `build/spring-modulith-docs/` com `all-docs.adoc`, `components.puml`, `module-shared.adoc` e `module-shared.puml`.
- [x] `api/openapi.yaml` é gerado pelo build, está versionado e a verificação de diff falha se ele estiver desatualizado. Evidência: o `build/openapi/openapi.yaml` gerado é idêntico ao versionado (`cmp`); `checkOpenApi` falhou com "api/openapi.yaml está desatualizado" ao editar o arquivo à mão (linha 8) e ao mudar a rota do `MetaController` sem regenerar (linha 17); `updateOpenApi` regravou e o `checkOpenApi` voltou a passar.
- [x] `docker compose -f docker/compose.yaml up` sobe `api`, `db`, `storage` e `proxy`, e `/actuator/health/readiness` responde `UP`. Evidência: imagem construída com `--no-cache`, `up -d` com o projeto `zeny-e08` e um `docker/.env` novo; `api`, `db` e `storage` `healthy`, `proxy` de pé, `storage-init` `Exited (0)`; `curl http://127.0.0.1/actuator/health/readiness` → 200 `{"status":"UP"}`; `down -v` no fim.
- [x] A CI roda build, testes, verificação de módulos, diff do contrato, oasdiff e build da imagem. Evidência: `.github/workflows/ci.yml` (jobs `build`, `api-compatibility` e `image`; actionlint 1.7.12 com 0 erros); execução real `38502250745` do commit `6e9bd9c` (push na `main`, 2026-10-14) concluída com sucesso nos três jobs, com os artefatos `spring-modulith-docs` e `openapi`, confirmada pelo usuário.
- [x] `CLAUDE.md` e `README.md` descrevem os comandos oficiais e as convenções estabelecidas neste marco. Evidência: todos os comandos da tabela rodaram como descritos na cópia limpa (`build`, `test`, `lint`, `ktlintFormat` sem mudar arquivo, `koverHtmlReport`, `checkOpenApi`, `updateOpenApi`, `bootRun` com o banco e as chaves documentados, o compose com `build`, `up -d`, `ps` e `down -v`, o actionlint e o `check-api-breaking.sh`); caminhos, classes e valores citados conferidos no código.

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
| E03 | Kernel compartilhado: tipos de valor e cifragem | #concluida | E01 | `Money`, `Percent`, UUIDv7, números legíveis, CPF/CNPJ, `Address` e cifragem de campo em `shared` |
| E04 | Convenções de API e contrato OpenAPI | #concluida | E02, E03 | Erros RFC 9457, paginação por cursor, `ETag`/`If-Match`, `api/openapi.yaml` gerado e verificado |
| E05 | Idempotência de requisições | #concluida | E04 | `Idempotency-Key` com respostas guardadas por 24h |
| E06 | Imagem Docker, compose e observabilidade | #concluida | E04 | Dockerfile, `docker/compose.yaml`, health, logs JSON e métricas Prometheus |
| E07 | Pipeline de CI | #concluida | E05, E06 | GitHub Actions com build, testes, contrato, oasdiff e imagem |
| E08 | Gate do marco M0 | #concluida | E07 | Definição global de pronto verificada do zero |

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

## E03 — Kernel compartilhado: tipos de valor e cifragem — #concluida

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

- Snapshot inicial: `dfd9b3fd9de2e40783eaac54b50d8983e5d80fd2` (recebido do orquestrador). Snapshot ao fim da implementação: `35522f48def7fb3ddd3296b758fe6dda5024353a` (antes de preencher este handoff).
- Arquivos alterados:
  - API pública do `shared` (raiz de `src/main/kotlin/com/zeny/hotel/shared/`): `Money.kt` (`Money`, `sum()`, `MoneyConverter`), `Percent.kt` (`Percent`, `PercentConverter`), `Ids.kt` (`TypedId`, `TypedIdConverter`, `UuidV7`, `UuidV7Generator`, `isVersion7()`, `v7Instant()`), `DocumentNumbers.kt` (`DocumentNumber`, `DocumentNumberType`, `DocumentNumberGenerator`, `DocumentNumberConverter`), `TaxIds.kt` (`Cpf`, `Cnpj`, conversores), `Address.kt` (`Address` `@Embeddable`, `BrazilianState`), `FieldEncryption.kt` (`FieldCipher`, `FieldDecryptionException`, `EncryptedStringConverter`); `SharedModule.kt` (KDoc).
  - Implementações (`shared/infrastructure/`): `crypto/EncryptionKeyRing.kt`, `crypto/AesGcmFieldCipher.kt`, `crypto/FieldEncryptionConfiguration.kt` (`FieldEncryptionProperties` + bean `FieldCipher`), `numbering/JdbcDocumentNumberGenerator.kt`, `time/ClockConfiguration.kt` (bean `Clock` UTC).
  - Configuração: `src/main/resources/application.yaml` (`zeny.encryption.keys`/`active-key-id` de `ZENY_ENCRYPTION_KEYS`/`ZENY_ENCRYPTION_ACTIVE_KEY_ID`, sem padrão).
  - Testes unitários (`src/test/kotlin/com/zeny/hotel/shared/`): `MoneyTest` (13), `PercentTest` (5), `UuidV7Test` (6), `TaxIdTest` (5), `AddressTest` (5), `DocumentNumberTest` (4), `ValueTypesJsonTest` (4), `infrastructure/crypto/AesGcmFieldCipherTest` (9).
  - Testes de integração: `shared/infrastructure/SharedKernelPersistenceTest` (5) e `SharedKernelBeansTest` (2); apoio em `testsupport/KernelProbe.kt` (entidade com todos os tipos, `ProbeId` e `ProbeIdConverter`), `testsupport/TestEncryptionKeys.kt` (chave sorteada por JVM + inicializador), `IntegrationTest.kt` e `EventPublicationRegistryTest.kt` (passam a usar o inicializador da chave), migração só de teste `src/test/resources/db/testmigration/V2026_10_13_2320__create_kernel_probe.sql` (schema `test_support`).
  - Documentação: `CLAUDE.md` (estado atual, chaves de cifragem, seção "Tipos do kernel", base de testes), `README.md` (seção "Cifragem de campo", pré-requisito do `bootRun`), este roadmap.
  - Build: nenhuma mudança em `build.gradle.kts` nem em `gradle/libs.versions.toml`. Tudo usa o JDK, Jackson (anotações 2.21 já no classpath), Jakarta Persistence e Spring.
- Decisões:
  - **Arredondamento:** `HALF_EVEN` (ABNT NBR 5891, sem viés acumulado), em `Money.ROUNDING_MODE`, aplicado só por `Money.round(BigDecimal)`. `Money * BigDecimal`, `Money * Percent` e `Percent.of(Money)` passam por ela. A construção (`Money.of`) é exata: recusa mais de 2 casas significativas em vez de arredondar. Não há divisão; repartir é `allocate`/`split`. A regra tem de ser validada com o middleware fiscal no M7 (RN-02); se mudar, muda nessa constante. **Ponto para o usuário confirmar.**
  - **Alocação:** método do maior resto em centavos inteiros (`BigInteger`). Cada parte recebe o piso da sua cota, e os centavos que sobram vão às maiores frações, com empate decidido pela ordem. Total negativo é alocado pelo valor absoluto, com o sinal reaplicado. Pesos: `BigDecimal` não negativos, ao menos um positivo.
  - **`Money`:** classe comum (não `value class`), construtor privado, `equals` por valor com escala fixa 2, `Comparable`, só BRL (`of(valor, "BRL")` recusa outra moeda). Em JSON é string pelas próprias anotações (`@JsonValue`/`@JsonCreator` delegante), o que vale em qualquer `JsonMapper`, inclusive no do outbox. Números JSON são recusados (só string). Persistência pelo `MoneyConverter` (`autoApply`) em `numeric(14,2)`; acima do limite, o banco recusa (`numeric field overflow`, testado).
  - **`Percent`:** pontos percentuais com escala 4 (`12.5` = 12,5%), de -999.9999 a 999.9999 (`numeric(7,4)`), construção exata, string em JSON, `PercentConverter` automático.
  - **UUIDv7:** implementação própria, sem biblioteca (cerca de 30 linhas, RFC 9562 §5.7 com o contador monotônico do §6.2, método 1): 48 bits de ms, contador de 12 bits com semente aleatória na metade de baixo e 62 bits do `SecureRandom`. No mesmo ms ou com o relógio voltando, o contador avança; se ele estoura, o carimbo avança 1 ms. Fica em ordem estrita no `compareTo` do Java e na ordem textual (a do `uuid` do PostgreSQL). **Ponto para o usuário confirmar** (alternativa: `uuid-creator`/`java-uuid-generator`).
  - **Ids tipados:** `@JvmInline value class XId(override val value: UUID) : TypedId`. Num atributo de entidade o Kotlin guarda o UUID desembrulhado e o Hibernate mapeia direto para `uuid`, sem conversor; por isso a busca usa `id.value` e o repositório usa `UUID` como tipo do id. O `TypedIdConverter` é para quando o id chega embrulhado (coleções): sem ele, o contexto não sobe (mutação testada).
  - **Números legíveis:** `DocumentNumber(prefixo [A-Z]{1,5}, ano, sequência)`, impresso com ao menos 6 dígitos (`R-2026-000123`, cresce além disso). Uma sequence por tipo de documento, criada pelo módulo dono na sua migração e citada qualificada (`schema.nome`, validado por regex) em `DocumentNumberType`. `nextval` recebe o nome como parâmetro `regclass`, nunca concatenado. O ano vem de quem chama (data operacional, M1). A sequência **não reinicia por ano** e pode ter buracos (rollback). **Ponto para o usuário confirmar.**
  - **CPF/CNPJ:** classes com construtor privado e `of`/`isValid`. Aceitam entrada com ou sem pontuação e guardam só dígitos/caracteres. Recusam dígito verificador errado e sequência de um caractere repetido. O CNPJ aceita o formato **alfanumérico** (IN RFB 2.229/2024, emitido desde julho de 2026): valor do caractere = ASCII − 48, mesmos pesos, DVs numéricos, conferido com o exemplo oficial `12.ABC.345/01DE-35`. Conversores `autoApply` para `varchar`.
  - **`Address`:** `@Embeddable`, endereço no Brasil: CEP normalizado para 8 dígitos e código IBGE de 7 dígitos com o prefixo da UF (`BrazilianState`, 27 UFs com o código IBGE). Não confere o dígito verificador do código IBGE (há municípios que fogem dele) nem se o município existe (lista oficial, validação da FNRH, M5). Endereço no exterior fica fora.
  - **Cifragem:** AES-256-GCM, nonce de 96 bits do `SecureRandom` a cada valor, tag de 128 bits. Formato `v1:<id da chave>:<base64url(nonce‖cifrado‖tag)>`, com o cabeçalho `v1:<id>` como AAD (trocar o id não decifra, testado). `FieldCipher` (raiz) com `encrypt`/`decrypt`/`keyIdOf`/`isEncryptedWithActiveKey`. Chaves em `EncryptionKeyRing` (`id:base64,…`, 32 bytes cada; `toString` e mensagens de erro nunca mostram material). `EncryptedStringConverter` (raiz, não `autoApply`) recebe o `FieldCipher` por injeção: o Hibernate cria o conversor pelo `SpringBeanContainer` (testado no PostgreSQL). Só API de `String`; bytes para anexos ficam para o `files` (M1).
  - **Chave sem padrão em lugar nenhum:** `ZENY_ENCRYPTION_KEYS` e `ZENY_ENCRYPTION_ACTIVE_KEY_ID` são obrigatórias em todos os profiles, **inclusive `local`**. O placeholder sem variável chega como texto literal, e `FieldEncryptionProperties` o detecta para dar uma mensagem clara. Os testes usam uma chave sorteada por JVM (`TestEncryptionKeys`). Consequência: `bootRun` local agora exige as duas variáveis (comando no `CLAUDE.md`/`README.md`). **Ponto para o usuário confirmar** (e se o `.env.example` da E06 traz uma chave de desenvolvimento).
  - **Relógio:** bean `java.time.Clock` (`Clock.systemUTC()`, `@ConditionalOnMissingBean`) em `shared/infrastructure/time/`. O `UuidV7` padrão usa o relógio UTC da JVM direto, para ser chamado do domínio sem injeção.
  - Os conversores e `Address` ficam na raiz do `shared` (API pública), para que entidades de qualquer módulo os referenciem sem tocar camadas internas. O `ApplicationModules.verify()` e as regras ArchUnit seguem verdes.
- Validações executadas:
  - Testes escritos antes da implementação: `./gradlew compileTestKotlin` falhou por falta dos tipos (`Unresolved reference 'BrazilianState'`, `'Address'`…).
  - `./gradlew clean build --no-build-cache` → `BUILD SUCCESSFUL`: compilação sem avisos nos fontes novos, `detekt`, `detektMain`, `detektTest`, `ktlint*Check`, `test` e `koverVerify`. Foram 107 testes e 0 falhas: os 49 anteriores mais 58 novos (`MoneyTest` 13, `PercentTest` 5, `UuidV7Test` 6, `TaxIdTest` 5, `AddressTest` 5, `DocumentNumberTest` 4, `ValueTypesJsonTest` 4, `AesGcmFieldCipherTest` 9, `SharedKernelPersistenceTest` 5, `SharedKernelBeansTest` 2).
  - `./gradlew test lint --rerun-tasks` → `BUILD SUCCESSFUL`.
  - `grep -rn "Double\|Float" src/main/kotlin` → nenhuma ocorrência. `MoneyTest` › `no money or percent API accepts or returns floating point` confere por reflexão que nenhum método, construtor ou campo de `Money`/`Percent` usa `double`/`float`.
  - Critérios, por teste: arredondamento (`the single rounding rule is half-even to cents`, com casos de empate, negativos e `0.005`); alocação (`allocates proportionally…`, `splits into equal parts…` e `allocation never loses cents, including repeating decimals`: 2 000 casos sorteados com semente fixa, soma exata e cada parte a menos de 1 centavo da exata); JSON (`ValueTypesJsonTest` e `SharedKernelBeansTest` com o `JsonMapper` do Boot: `"123.45"`); `numeric(14,2)` (`SharedKernelPersistenceTest`: grava `999999999999.99`, lê o mesmo `BigDecimal` com escala 2, coluna 14,2, e `1000000000000.00` é recusado pelo banco); UUIDv7 ordenável (`UuidV7Test`: ms crescentes, 10 000 no mesmo ms, relógio voltando, 50 000 do gerador padrão); CPF/CNPJ (`TaxIdTest`: 10 CPFs e 12 CNPJs inválidos, entre eles DV errado e sequências repetidas); cifragem (`AesGcmFieldCipherTest`: 10 000 cifragens com nonces distintos, rotação com a chave antiga ainda legível, chave removida recusada, adulteração e troca de id recusadas); coluna cifrada no banco só com `v1:test:…`, sem o texto claro (`SharedKernelPersistenceTest`).
  - Mutações (cada uma revertida em seguida), todas pegas: `HALF_UP` no lugar de `HALF_EVEN` (2 testes falham); alocação sem distribuir os centavos que sobram (3); sobra para os primeiros índices em vez das maiores frações (2); nonce fixo (1); AAD omitido na decifragem (2); `@Convert` do id tipado removido da coleção (o contexto não sobe, 7); contador do UUIDv7 ressorteado em vez de incrementado (2); regra de dígito repetido removida do CPF (1) e do CNPJ (1).
  - Aplicação real (`java -jar … --spring.profiles.active=local` com `postgres:18` em Docker): sem `ZENY_ENCRYPTION_KEYS`, em `local` e em `prod`, não sobe (código 1, "Cifragem de campo sem chaves: defina ZENY_ENCRYPTION_KEYS"); sem a chave ativa, mensagem própria; chave de 16 bytes → "A chave de cifragem 'dev' precisa ter 32 bytes (AES-256); tem 16"; com chave válida, "Started" em ~2 s, sem `ERROR`. Nenhum log contém o material da chave.
  - `git status --short` → só fontes, recursos e documentação; nenhum artefato de build.
- Parecer do verificador: **APROVADO** (verificador independente disparado pelo orquestrador, snapshot da verificação `58acf6e64537b451b06d8f9119db155004f89013`). Os 8 critérios foram aprovados. Evidências: `./gradlew clean build --no-build-cache` e `./gradlew test lint --rerun-tasks` passaram (107 testes, 0 falhas, 0 pulados); `grep -rn "Double\|Float" src/main/kotlin` sem ocorrência; `Money.ROUNDING_MODE` é o único modo de arredondamento em `src/main`; alocação conferida também em jshell (pesos com escala negativa, zeros, total negativo); DVs de CPF/CNPJ recalculados à parte em Python (as sequências repetidas testadas têm DV válido, logo exercitam a regra de repetição); nenhum base64 de 32 bytes em `src/`, docs, README ou CLAUDE.md. Nenhum problema grave ou médio. Problemas baixos:
  1. `DocumentNumber.parse` aceita zeros à esquerda além de 6 dígitos (`R-2026-0001234567`), e o texto não volta igual em `toString`.
  2. `Money` não tem limite superior na construção; valores acima de `numeric(14,2)` só falham no banco (documentado e testado).
  3. No estouro do contador, o gerador de UUIDv7 avança `lastMillis` além do relógio (aceito pela RFC 9562, não afeta a ordem).
  4. `Money.of(BigDecimal)` não tem `@JvmStatic` (só pesa para chamadas de Java).
- Decisões do usuário (2026-10-13, entrevista conduzida pelo orquestrador; todas as opções recomendadas foram aceitas): (1) arredondamento `HALF_EVEN` em `Money.ROUNDING_MODE`, a validar com o middleware fiscal no M7; (2) UUIDv7 com implementação própria, sem biblioteca; (3) números legíveis por sequence simples, sem reinício anual e com buracos possíveis; (4) chaves de cifragem obrigatórias em todos os profiles, sem padrão no código ou no YAML, e o `.env.example` da E06 traz uma chave marcada como só de desenvolvimento para o compose subir direto.
- Notas para a próxima etapa:
  - Pontos para o usuário confirmar (decisões adotadas acima): (1) arredondamento `HALF_EVEN`; (2) UUIDv7 com implementação própria; (3) números legíveis sem reinício anual e com buracos possíveis; (4) chaves de cifragem obrigatórias também no profile `local`, e se o `.env.example` da E06 traz uma chave de desenvolvimento.
  - E04: o `JsonMapper` do Boot já serializa `Money`/`Percent`/`DocumentNumber`/`Cpf`/`Cnpj` como string pelas anotações dos tipos; no springdoc, configure esses tipos como `type: string` (com `format`/`pattern` decimal) para o contrato não mostrar objetos. `IllegalArgumentException` dos construtores (`Money.of`, `Cpf.of`…) é erro de entrada: mapear para 400/422 com `errors[]` por campo.
  - E05/E06: o compose e o `.env.example` precisam passar `ZENY_ENCRYPTION_KEYS` e `ZENY_ENCRYPTION_ACTIVE_KEY_ID` (Docker secret em produção); sem elas a `api` não sobe.
  - M1: `hotel` usa `Address` e `Cnpj`; `files` precisa de cifragem de bytes/streams (hoje o `FieldCipher` só cifra `String`) e pode reaproveitar o `EncryptionKeyRing`. Um job de re-cifragem na rotação pode usar `isEncryptedWithActiveKey`. Subclasses de `TypedIdConverter` usadas por entidades de `domain` precisam ficar em `domain` ou na raiz do módulo (entidade não pode referenciar `infrastructure`); confira com `LayerRules` no primeiro caso real.
  - M7: validar `Money.ROUNDING_MODE` com o middleware fiscal (RN-02).
  - Coluna cifrada não serve para busca: se o M3 precisar buscar hóspede por CPF, será preciso um hash com chave (*blind index*), a decidir lá.

---

## E04 — Convenções de API e contrato OpenAPI — #concluida

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

- Snapshot inicial: `0cb9c5c66834195a204c5074b53cae290fe563f7` (recebido do orquestrador; inclui as mudanças da E03 ainda não commitadas). Snapshot ao fim da implementação: `d3d4e9e0a02fa206f990e68e0582ab328415a13e` (antes de preencher este handoff).
- Arquivos alterados:
  - Build: `build.gradle.kts` (dependências web, validação, segurança e springdoc; `springBoot.buildInfo` sem horário; tarefas `generateOpenApi`, `checkOpenApi`, ligada ao `check`, e `updateOpenApi`; o `test` exclui a tag `openapi-contract`), `gradle/libs.versions.toml` (springdoc 3.1.1; `spring-boot-starter-webmvc`, `-validation`, `-security`, `-webmvc-test`, `-security-test` e `jackson2-module-kotlin`, todos gerenciados pelos BOMs).
  - Configuração: `src/main/resources/application.yaml` (exclusão do `UserDetailsServiceAutoConfiguration`, Jackson com `time-zone: UTC` e `write-dates-as-timestamps: false`, `spring.web.locale: pt_BR` com resolvedor fixo, springdoc com OpenAPI 3.0, chaves ordenadas e saída indentada; no `prod`, `springdoc.api-docs.enabled` e `swagger-ui.enabled` desligados).
  - Contrato: `api/openapi.yaml` (novo, gerado).
  - API pública do `shared` (raiz): `ApiV1Controller.kt` (`@ApiV1Controller`, `API_V1`), `DomainErrors.kt` (`ErrorCode`, `ErrorCodes`, `FieldViolationCodes`, `FieldViolation`, `ErrorKind`, `DomainException` e as famílias `InvalidInputException`, `NotFoundException`, `ConflictException`, `BusinessRuleException`, `PermissionDeniedException`, `PreconditionFailedException`, `PreconditionRequiredException`), `Pagination.kt` (`PageParams`, `CursorPage`, `PageCursor`), `Concurrency.kt` (`ETags`, `requireCurrentVersion`, `StaleVersionException`); `SharedModule.kt` (KDoc).
  - Web do `shared` (`shared/web/`): `ApiExceptionHandler`, `FieldViolations`, `Problems`, `ProblemErrorController` (substitui o `BasicErrorController`), `SecurityConfiguration`, `ApiWebConfiguration` (prefixo `/api/v1`), `OpenApiConfiguration`, `MetaController` (`GET /api/v1/meta/version`).
  - Testes: `shared/PaginationTest` (5), `shared/ConcurrencyTest` (5), `shared/DomainErrorsTest` (3), `shared/web/ApiConventionsTest` (13), `shared/web/ApiSecurityTest` (8), `shared/web/ProductionProfileApiTest` (2), `shared/web/OpenApiContractExport` (1, só na `generateOpenApi`); controller de exemplo só de teste em `src/test/kotlin/apiexample/ExampleItemsController.kt` (fora do pacote base); `testsupport/IntegrationTest.kt` (passa a ter `@AutoConfigureMockMvc`); `ZenyHotelApplicationTest` (confere que o `OpenEntityManagerInViewInterceptor` não existe).
  - Documentação: `CLAUDE.md` (estado atual, stack, comandos `checkOpenApi`/`updateOpenApi`, seção "Convenções de API" com controller, erro de domínio, catálogo de códigos, paginação, concorrência, contrato e segurança), `README.md` (comandos e seção "API"), este roadmap.
- Decisões:
  - **Geração do contrato sem subir o servidor à mão:** um teste com a tag `openapi-contract` (`OpenApiContractExport`) sobe o contexto de produção com `@IntegrationTest` (profile `local`, PostgreSQL do Testcontainers, chave de cifragem sorteada), lê `/v3/api-docs.yaml` pelo `MockMvc` e grava em `build/openapi/openapi.yaml`. A tarefa `generateOpenApi` (do tipo `Test`) roda só esse teste; `checkOpenApi` compara com `api/openapi.yaml` e falha com "api/openapi.yaml está desatualizado (primeira diferença na linha N). Rode ./gradlew updateOpenApi…"; `updateOpenApi` copia o gerado para `api/`. O plugin `org.springdoc.openapi-gradle-plugin` (1.9.0) foi descartado porque sobe a aplicação via `bootRun` e exigiria banco e chaves fora do Testcontainers (risco previsto na etapa). **Ponto para o usuário confirmar.**
  - **OpenAPI 3.0** (`springdoc.api-docs.version: openapi_3_0`), e não 3.1, o padrão do springdoc 3: o suporte a 3.1 no oasdiff (E07) e nos geradores de SDK ainda é parcial. Chaves ordenadas e indentação fixa deixam o diff estável. **Ponto para o usuário confirmar.**
  - **springdoc 3.1.1** (feito sobre Spring Boot 4.1.0). O swagger-core dele ainda usa Jackson 2; sem o `com.fasterxml.jackson.module:jackson-module-kotlin` (só `runtimeOnly`), o contrato não marcava como `required` as propriedades Kotlin não nulas. O swagger-core puxa o Jackson 2 para 2.22.1 (o BOM do Boot traz 2.21.5); o Jackson 3 da aplicação não muda.
  - **Prefixo `/api/v1`** por anotação: `@ApiV1Controller` (meta-anotada com `@RestController`) e `PathMatchConfigurer.addPathPrefix` em `ApiWebConfiguration`. `ApiSecurityTest` barra controller da aplicação fora de `/api/v1`, com exceção do `ProblemErrorController`.
  - **Erros RFC 9457:** `ProblemDetail` do Spring com as propriedades `code` e, na validação, `errors[]` (`field`, `code`, `message`). `title` é a frase do status em pt-BR; `type` fica omitido, o que equivale a `about:blank` (RFC 9457 §3.1.1), em vez de uma URI por código. As exceções do Spring MVC saem com o status do Spring e `code`, título e detalhe padronizados (`Problems.normalize`), sem a mensagem técnica do Spring ou do Jackson. Status das famílias: entrada inválida 400, permissão 403, inexistente 404, conflito de estado 409, regra de negócio 422, `If-Match` desatualizado 412 e ausente 428. Lock otimista do JPA (`OptimisticLockingFailureException`/`OptimisticLockException`) vira 412. Qualquer outra exceção vira `500 INTERNAL_ERROR` com detalhe genérico, e a causa vai para o log em `ERROR`. `IllegalArgumentException` **não** vira 400 automaticamente (poderia vazar mensagem de bug): tipos de valor inválidos no corpo chegam como erro do Jackson e viram `INVALID_FORMAT` no campo. `AccessDeniedException`/`AuthenticationException` lançadas dentro de controller são relançadas para o `ExceptionTranslationFilter` (base para a segurança por método do M1). **Catálogo e mapeamento de status: ponto para o usuário confirmar.**
  - **Códigos por campo:** o nome da restrição Bean Validation em `UPPER_SNAKE_CASE` (`NOT_BLANK`, `SIZE`, `MIN`, `MAX`…), `REQUIRED` (ausente ou nulo: parâmetro, cabeçalho, campo Kotlin não nulo) e `INVALID_FORMAT` (tipo ou formato inválido: UUID, número, data, `Money`). Mensagens de validação em pt-BR com `spring.web.locale=pt_BR` e resolvedor fixo, independentemente do `Accept-Language` (os clientes traduzem pelo `code`).
  - **`ProblemErrorController`** no lugar do `BasicErrorController`: erros encaminhados pelo servlet para `/error` também saem como problema com `code`. Acesso direto a `/error` é negado; só o despacho `ERROR` é liberado.
  - **Paginação:** keyset com cursor opaco `base64url("c1:" + chaves)`, sem padding, com até 1024 caracteres e não assinado (as chaves são entrada não confiável; consulta parametrizada e escopo do usuário continuam valendo). Cursor ilegível ou com chave do tipo errado vira `400 INVALID_CURSOR`. `PageParams`: `limit` padrão 50 e máximo 200, validado com `@Valid`. Resposta `{"items": [...], "nextCursor": "..."|null}`; `CursorPage.fromRows` usa a técnica de `limit + 1` linhas. **Ponto para o usuário confirmar** (alternativa: cursor assinado com HMAC).
  - **`ETag`/`If-Match`:** etag forte com a versão do agregado (`"7"`). `ETags.expectedVersion`: ausente vira 428; etag fraca, `*`, lista ou lixo viram 412, porque nunca casam com a versão atual (RFC 9110 §13.1.1). A comparação fica no caso de uso (`requireCurrentVersion`).
  - **Jackson 3** (já ISO-8601 por padrão), fixado na configuração: `write-dates-as-timestamps: false` e `time-zone: UTC`. `Instant` sai com `Z` mesmo com a JVM em `America/Sao_Paulo` (testado). A convenção documentada é usar `Instant` e `LocalDate` nos DTOs; `OffsetDateTime`/`LocalDateTime` não entram.
  - **Segurança mínima:** um único `SecurityFilterChain`. `GET /api/v1/meta/version` é público; o resto de `/api/**` exige `authenticated()`, e não existe mecanismo de autenticação (sem usuário em memória, senha, HTTP Basic, formulário nem logout; `UserDetailsServiceAutoConfiguration` excluído). Documentação aberta só quando o springdoc está ligado (fora de `prod`); todo o resto, `denyAll()`. Sem sessão (`STATELESS`), sem cache de requisição e sem CSRF (API sem cookie; token de portador no M1). Recusas como problema: `401 UNAUTHENTICATED` e `403 ACCESS_DENIED`. O actuator ainda não existe: a E06 o libera.
  - **Endpoint de exemplo:** o controller que exercita erro, paginação e `ETag` (`ExampleItemsController`) é só de teste e fica fora do pacote base (`apiexample`), para não entrar na varredura nem no contrato. Para o contrato não ficar vazio e o roteamento e a segurança terem um caso real, entrou o `GET /api/v1/meta/version` (`{application, version, apiVersion}`, com a versão do `build-info`). **Ponto para o usuário confirmar.**
  - Toda operação ganha a resposta `default` com o schema `Problem` (`OpenApiCustomizer`). `Money`, `Percent`, `Cpf`, `Cnpj` e `DocumentNumber` aparecem como `string` com `pattern` (`SpringDocUtils.replaceWithSchema`). As descrições do contrato estão em pt-BR (documentação); os identificadores, em inglês (D17).
  - `springdoc.api-docs.enabled`/`swagger-ui.enabled` ficam explícitos (`true`) na base, para o springdoc não avisar "enabled by default" no log.
- Validações executadas:
  - Testes escritos antes do código: `./gradlew compileTestKotlin` falhou com `Unresolved reference 'ApiV1Controller'`, `'CursorPage'`, `'ETags'`… Depois da primeira implementação, 2 testes falharam e foram corrigidos (`type` omitido pelo Spring; instância com "swagger" no caminho do problema).
  - `./gradlew clean build --no-build-cache` → `BUILD SUCCESSFUL`: rodou `detekt`, `detektMain`, `detektTest`, `ktlint*Check`, `test` (143 testes, 0 falhas, 0 pulados: os 107 anteriores mais 36 novos), `generateOpenApi` (1 teste), `checkOpenApi` e `koverVerify`. `./gradlew test lint --rerun-tasks` → `BUILD SUCCESSFUL`. Os logs dos testes não têm `WARN` novo.
  - Critério 1 (erros): `ApiConventionsTest` confere 400 `application/problem+json` com `VALIDATION_FAILED` e `errors[]` (`name`/`NOT_BLANK`/"não deve estar em branco"); `REQUIRED` para campo ausente e nulo; `INVALID_FORMAT` para `Money` com 3 casas, `Money` numérico e data fora do ISO; `MIN`/`MAX`/`INVALID_FORMAT` em query e path; `SIZE`/`REQUIRED` na validação por método; `MALFORMED_REQUEST` sem `errors`; erros de domínio 404 `EXAMPLE_NOT_FOUND`, 409 `EXAMPLE_ALREADY_ARCHIVED`, 422 `LIMIT_EXCEEDED` e 403 `APPROVAL_REQUIRED`, com a mensagem como `detail`; 404/405/415 do Spring com `code`; 500 `INTERNAL_ERROR` sem a mensagem da exceção ("senha=hunter2"), sem nome de exceção nem stack trace, e só com as chaves `title`, `status`, `detail`, `instance` e `code`. Todo problema verificado não tem `trace`, `exception` nem `message`.
  - Critério 2 (paginação): 5 itens percorridos com `limit=2` em 3 páginas (2, 2 e 1), sem repetição e em ordem, `nextCursor` nulo na última, cursor só com caracteres base64url e sem o UUID em claro; sem `limit`, uma página com tudo; cursor adulterado → 400 `INVALID_CURSOR`. `PaginationTest` cobre ida e volta, adulteração, tamanho máximo e acessores tipados.
  - Critério 3 (`ETag`/412): `GET` devolve `ETag: "0"`; `PUT` com `If-Match: "0"` → 200 e `ETag: "1"`; de novo com `"0"` → 412 `PRECONDITION_FAILED`; `W/"1"` → 412; sem `If-Match` → 428 `PRECONDITION_REQUIRED`; o item fica na versão 1 com o nome da primeira alteração. `POST` devolve 201 com `Location` e `ETag`.
  - Critério 4 (serialização): `createdAt` = `"2026-03-10T01:30:00.123Z"` (string, com a JVM em `America/Sao_Paulo`), `dueDate` = `"2026-03-10"`, `price` = `"1234.50"` (string). No contrato, `price` aparece como `type: string, format: decimal`, `createdAt` como `date-time`, `dueDate` como `date` e `percent` como string.
  - Critério 5 (contrato): `api/openapi.yaml` gerado por `./gradlew updateOpenApi`. Demonstração: trocar `@GetMapping("/version")` por `"/server-version"` em `MetaController` sem regenerar → `./gradlew checkOpenApi` falhou com "api/openapi.yaml está desatualizado (primeira diferença na linha 16)…"; código restaurado → `BUILD SUCCESSFUL`. Editar o título à mão em `api/openapi.yaml` → falha (linha 7); apagar o arquivo → falha (linha 1); arquivo restaurado → sucesso. Na primeira tentativa, a asserção de sanidade do próprio teste exportador (que procurava `/api/v1/meta/version`) fez a falha aparecer na `generateOpenApi`, e não na `checkOpenApi`; ela passou a conferir só o cabeçalho `openapi:`.
  - Critério 6 (Swagger): `ApiSecurityTest` → `/swagger-ui/index.html` 200 e `/v3/api-docs` 200 no `local`. `ProductionProfileApiTest` (profile `prod`) → nenhum bean `SwaggerWelcomeWebMvc`/`OpenApiResource`; `/swagger-ui.html`, `/swagger-ui/index.html`, `/v3/api-docs` e `/v3/api-docs.yaml` respondem ≥ 400, sem página do Swagger nem documento OpenAPI; a API continua respondendo.
  - Critério 7 (endpoints abertos): `ApiSecurityTest` percorre **todos** os mapeamentos de `/api/**` dos controllers da aplicação (com as variáveis de caminho preenchidas) e exige 401 `UNAUTHENTICATED` sem autenticação, exceto `GET /api/v1/meta/version`; também caminhos inexistentes (`/api/v1/does-not-exist`, `POST /api/v2/x`), caminhos fora da API (`/`, `/index.html`, `/actuator`, `/error`, `/anything`), `Authorization: Basic …` e `POST /login`. Nenhum bean `UserDetailsService`.
  - Mutações (cada uma revertida em seguida), todas pegas: `/api/**` com `permitAll()` → 2 falhas em `ApiSecurityTest`; springdoc ligado no `prod` → falha em `ProductionProfileApiTest`; `write-dates-as-timestamps: true` → 2 falhas de serialização em `ApiConventionsTest`.
  - Aplicação real (`java -jar … --spring.profiles.active=local` sobre `postgres:18` em Docker, porta 18080): `GET /api/v1/meta/version` 200 com `{"application":"zeny-hotel-manager","version":"0.0.1-SNAPSHOT","apiVersion":"v1"}`; `/api/v1/rooms`, `/` e Basic → 401 `application/problem+json` com `UNAUTHENTICATED`; Swagger UI e `/v3/api-docs.yaml` 200; nenhuma senha gerada no log. Com `prod` (porta 18081): Swagger UI e `/v3/api-docs` → 401 problema, `meta/version` 200, sem `WARN`/`ERROR`. Caminho com codificação inválida (`/api/v1/%zz`) é recusado pelo próprio Tomcat com 400 em HTML mínimo, sem stack trace nem versão do servidor, antes de chegar ao Spring (ver notas).
  - `git status --short` → só fontes, recursos, `api/` e documentação; nenhum artefato de build.
- Parecer do verificador: **APROVADO** (verificador independente disparado pelo orquestrador, snapshot da verificação `6bf9ef05cbf9cf717512497544351e843f75c6ef`, igual antes e depois das mutações temporárias). Os 9 critérios foram aprovados. Evidências: `./gradlew clean build --no-build-cache` e `./gradlew test --rerun-tasks` passaram (143 testes, 0 falhas, 0 pulados); `checkOpenApi` passa com o arquivo atual e falha ao trocar o path do `MetaController`, ao mudar a descrição de um `@Schema` (derruba o `build`) e ao editar o yaml à mão; mutações próprias pegas (500 com a mensagem da exceção, etag fraca aceita, `api-docs` ligado no `prod`); jar real em `local` e `prod`: Swagger/api-docs 200 em `local` e 401 problem em `prod`, `/api/**` (exceto `meta/version`), `/error` e `/actuator/health` 401, `..`/`..;` barrados pelo firewall, nenhuma senha gerada no log. Nenhum problema grave ou médio. Problemas baixos:
  1. A conversão de lock otimista (`OptimisticLockingFailureException`/`OptimisticLockException`) em 412 no `ApiExceptionHandler` não tem teste; fica sem prova até o M1.
  2. A mutação "Swagger UI ligado no `prod` com `api-docs` desligado" é equivalente (a UI depende do `api-docs` e a segurança nega `/swagger-ui/**`); o teste de UI repete o do `api-docs`.
  3. `Pagination.kt` e `ApiV1Controller.kt`, na raiz pública do `shared`, importam springdoc/swagger e Spring Web; nenhuma regra impede, atenção se um `domain` vier a usar `CursorPage`/`PageParams`.
  4. Caminho malformado (`/api/v1/%zz`) recebe do Tomcat um 400 em HTML mínimo, sem stack trace nem versão (anotado para a E06).
  5. O exemplo de CPF no contrato (`52998224725`) tem DV válido e é muito usado em testes; um número sabidamente fictício seria mais seguro.
- Revisão humana (2026-10-13, entrevista conduzida pelo orquestrador; todas as opções recomendadas foram aceitas): (1) contrato gerado por teste com Testcontainers mais as tarefas `generateOpenApi`/`checkOpenApi`/`updateOpenApi`, sem o `springdoc-openapi-gradle-plugin`; (2) OpenAPI 3.0; (3) endpoint real e público `GET /api/v1/meta/version`, com o exemplo das convenções só em teste; (4) 422 para regra de negócio, 409 para conflito de estado, 412 para `If-Match` desatualizado, 428 sem `If-Match` e `type` omitido (`about:blank`); (5) cursor base64url sem assinatura, limite padrão 50 e máximo 200; (6) mensagens de erro sempre em pt-BR, com locale fixo.
- Pontos para a revisão humana (decisões adotadas acima, cada uma com a alternativa):
  1. Geração do contrato por teste com Testcontainers e tarefas Gradle (recomendado) × plugin `org.springdoc.openapi-gradle-plugin`, que sobe a aplicação com banco e chaves reais.
  2. OpenAPI 3.0 (recomendado, pela compatibilidade com oasdiff e geradores) × 3.1 (padrão do springdoc, alinhado ao JSON Schema).
  3. Endpoint real e público `GET /api/v1/meta/version`, com o exemplo das convenções só em teste (recomendado) × só o exemplo em teste (contrato vazio, nenhum endpoint público).
  4. Status e catálogo: 422 para regra de negócio e 409 para conflito de estado, 428 sem `If-Match`, `type` omitido (recomendado) × 409 para tudo que é de negócio, ou `type` com uma URI por código.
  5. Cursor opaco em base64url sem assinatura, com limite padrão 50 e máximo 200 (recomendado) × cursor assinado com HMAC.
  6. Mensagens sempre em pt-BR, com locale fixo (recomendado, RNF-13) × respeitar o `Accept-Language`.
- Notas para a próxima etapa:
  - E05 (idempotência): use `DomainException`/`ErrorCodes` para os códigos novos (por exemplo `IDEMPOTENCY_KEY_REQUIRED` e `IDEMPOTENCY_KEY_REUSED`) e guarde a resposta inteira, inclusive problemas RFC 9457 e cabeçalhos como `ETag`/`Location`. O filtro ou interceptador precisa rodar depois da segurança. Testes de API: `@IntegrationTest` já traz `MockMvc`, e `@WithMockUser` autentica; um controller de exemplo só de teste pode seguir o padrão de `apiexample`.
  - E06: liberar `/actuator/health/liveness`, `/actuator/health/readiness` e `/actuator/prometheus` em `SecurityConfiguration` (e acrescentar o caso no `ApiSecurityTest`, que hoje confere `/actuator` negado). O Tomcat responde em HTML a URL malformada (`/api/v1/%zz`), antes do Spring; se incomodar, tratar no proxy (Caddy) ou na válvula de erro do Tomcat.
  - E07: `./gradlew build` já inclui `checkOpenApi`; o oasdiff compara o `api/openapi.yaml` (OpenAPI 3.0) com o da `main`.
  - M1: a regra `requestMatchers("/api/**").authenticated()` ganha o JWT, e a lista `PUBLIC_API_ENDPOINTS` do `ApiSecurityTest` acompanha os endpoints públicos novos (login, refresh). Mapear `DataIntegrityViolationException` (hoje 500) quando surgirem constraints de unicidade, de preferência convertendo-as em `ConflictException` no caso de uso. Conferir no primeiro DTO com id tipado (`value class`) que o contrato mostra `string`/`uuid` (o springdoc 3 tem `KotlinInlineClassUnwrappingConverter`). Cada operação precisa de `operationId` único.
  - Avisos conhecidos e inofensivos: `o.s.web.servlet.PageNotFound` para o 405 proposital dos testes; o aviso de depreciação do Gradle 10 continua vindo de plugin (as tarefas novas usam `tasks.register`).

---

## E05 — Idempotência de requisições — #concluida

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

- Snapshot inicial: `9bf11f708e078af9544d771c8a11f9eae911e4cc` (recebido do orquestrador). Snapshot ao fim da implementação: `76bb7b9c4dfbd942a1abcee698c10d21d830fbb3` (antes de preencher este handoff). Snapshot depois das decisões do usuário e das correções do verificador: `4eedb6471d1e9e49a4e2ad64cddd5f4802de9d78` (antes de atualizar este handoff).
- Arquivos alterados:
  - API pública do `shared` (raiz): `Idempotency.kt` (novo: `@Idempotent`, `IDEMPOTENCY_KEY_HEADER`, `IDEMPOTENT_REPLAYED_HEADER`), `DomainErrors.kt` (códigos `IDEMPOTENCY_KEY_REQUIRED`, `IDEMPOTENCY_KEY_INVALID`, `IDEMPOTENCY_KEY_IN_USE`, `IDEMPOTENCY_KEY_REUSED`), `SharedModule.kt` (KDoc).
  - `shared/application/idempotency/Idempotency.kt` (novo): `IdempotencyProperties` (`zeny.idempotency.*`), modelos (`IdempotentRequest`, `StoredResponse`, `IdempotencyDecision`…), a porta `IdempotencyRecords` e o `IdempotencyService` (reserva, repetição, conclusão, liberação e expurgo).
  - `shared/infrastructure/idempotency/` (novo): `JdbcIdempotencyRecords` (`JdbcClient`, cada operação em `REQUIRES_NEW`, corpo e cabeçalhos guardados cifrados pelo `FieldCipher`) e `IdempotencyKeyPurgeJob` (`@Scheduled` + `@SchedulerLock("idempotency-keys-purge")`).
  - `shared/web/`: `IdempotencyFilter` (recebe o resolvedor de handler como função, o que permite o teste unitário; recusa formulário e multipart com `415`) e `IdempotencyWebConfiguration` (novos), `ProblemWriter` (novo, extraído do `SecurityConfiguration`, que passou a usá-lo), `Problems` (detalhes em pt-BR dos códigos novos), `OpenApiConfiguration` (componentes `parameters.IdempotencyKey` e `headers.IdempotentReplayed`, `OperationCustomizer` das operações marcadas, códigos novos na descrição do `code`).
  - Migração: `src/main/resources/db/migration/shared/V2026_10_14_0031__create_idempotency_key.sql` (tabela `shared.idempotency_key`; `response_headers` e `response_body` em `text`, cifrados). Ela ainda não tinha sido aplicada em ambiente compartilhado nem commitada, por isso foi editada em vez de ganhar uma migração nova.
  - Configuração: `src/main/resources/application.yaml` (`zeny.idempotency.retention: PT24H`, `max-request-body-size: 1MB`, `purge.interval: PT1H`, `purge.initial-delay: PT5M`).
  - Contrato: `api/openapi.yaml` (regenerado por `./gradlew updateOpenApi`).
  - Testes: `shared/web/IdempotencyTest` (21, novo); `shared/web/IdempotencyFilterTest` (3, novo, unitário com MockK); `apiexample/ExampleItemsController` (pagamento `POST /api/v1/examples/{id}/payments` marcado com `@Idempotent`, com contador de execuções e gancho para segurar ou derrubar a execução); `DatabaseMigrationsTest` (colunas da tabela nova); `ApiSecurityTest` (o contrato de produção traz o parâmetro `IdempotencyKey`).
  - Documentação: `CLAUDE.md` (estado atual, `shared` no banco, catálogo de códigos, item "Idempotência" nas convenções de API, jobs do `shared`), `README.md` (seção "API"), este roadmap.
- Decisões:
  - **Onde roda:** um filtro de servlet (`IdempotencyFilter`) registrado em `/api/*` com ordem `SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1`, isto é, logo depois da cadeia do Spring Security e antes do `DispatcherServlet`. Assim a requisição chega autenticada (401 vem antes de qualquer checagem de chave, testado) e a resposta capturada é a final, inclusive os problemas do `ApiExceptionHandler`. O filtro descobre se o handler tem `@Idempotent` pelo `RequestMappingHandlerMapping` (resolvido na primeira requisição), numa cópia dos atributos da requisição para não deixar rastro; falha na busca (405, 415…) segue para o MVC responder.
  - **Camadas:** a regra (`IdempotencyService`) fica em `application`, a persistência (`JdbcIdempotencyRecords`) e o job em `infrastructure`, e o filtro em `web`, que só fala com `application` (regra ArchUnit `webTalksOnlyToApplication`).
  - **Escopo da chave** (**decidido pelo usuário em 2026-10-13**): o usuário autenticado, `user:<authentication.name>` (nome longo vira `user-sha256:<hash>`). Usuários diferentes com a mesma chave nunca se veem (nem 422 nem repetição). Requisição sem usuário autenticado num endpoint marcado recebe `401 UNAUTHENTICATED` (fail-closed: um endpoint público marcado por engano não compartilha respostas entre anônimos). **Provisório até o M1** (ver notas).
  - **Reserva atômica:** `INSERT … ON CONFLICT (scope, idempotency_key) DO NOTHING` numa transação própria, confirmada antes de o handler rodar; a restrição `UNIQUE (scope, idempotency_key)` garante que, de N requisições simultâneas, só uma reserva. As outras leem a linha: `IN_PROGRESS` com o mesmo hash → `409 IDEMPOTENCY_KEY_IN_USE`; hash diferente → `422 IDEMPOTENCY_KEY_REUSED` (vale mesmo em andamento); `COMPLETED` com o mesmo hash → resposta guardada com `Idempotent-Replayed: true`. Cada id de reserva é um UUIDv7 e a conclusão é por id (`WHERE id = … AND status = 'IN_PROGRESS'`).
  - **Identidade da requisição:** SHA-256 de método, caminho, query, `If-Match` e corpo, byte a byte (cada parte com o tamanho na frente). O mesmo JSON com outra formatação conta como outra requisição (422). Com o `If-Match` no hash, uma nova tentativa com a mesma chave e outra versão recebe `422 IDEMPOTENCY_KEY_REUSED`, e não o 412 guardado: depois de um 412, o cliente precisa de uma chave nova de qualquer jeito (**decidido pelo usuário em 2026-10-13**).
  - **O que se guarda:** a resposta **de qualquer status**, inclusive 5xx (modelo do Stripe; **decidido pelo usuário em 2026-10-13**), com status, corpo e os cabeçalhos `Content-Type`, `Location`, `ETag`, `Last-Modified`, `Content-Language`, `Content-Disposition`, `Retry-After` e `Link` (lista fechada; `Set-Cookie` e os de segurança/cache nunca, pois o Spring Security os escreve de novo na repetição). Erro se repete como erro, com o mesmo status e problema (testado com 422). Para tentar de novo depois de um erro definitivo, o cliente gera outra chave.
  - **Exceção que escapa do MVC:** recusa de segurança na cadeia de causas (`AccessDeniedException`/`AuthenticationException`, que o `ApiExceptionHandler` relança) libera a chave, porque nada executou (testado: 403 e depois 201 com a mesma chave); qualquer outra (na prática, `Error`) guarda um `500 INTERNAL_ERROR` genérico, porque o efeito pode ter acontecido.
  - **Falha ao gravar a resposta ou queda do processo no meio:** a linha fica `IN_PROGRESS` e a chave responde 409 até vencer (24h), em vez de deixar o efeito repetir (**decidido pelo usuário em 2026-10-13**).
  - **24h:** `expires_at = created_at + zeny.idempotency.retention` (`PT24H`), com o relógio injetado (`Clock`). Chave vencida é tratada como nova mesmo antes do expurgo (`UPDATE … WHERE expires_at <= now` assume a linha). O `IdempotencyKeyPurgeJob` roda a cada hora (`PT1H`, primeira vez 5 min depois do boot), sob ShedLock (`lockAtMostFor = PT10M`), e apaga as vencidas com um `DELETE … WHERE expires_at <= now` (índice em `expires_at`).
  - **Corpo:** lido inteiro antes do handler, até `zeny.idempotency.max-request-body-size` (1 MB); acima, `413 PAYLOAD_TOO_LARGE`. O handler o lê de novo de um wrapper (`CachedBodyRequest`). O handler precisa ser síncrono (assíncrono derruba com `IllegalStateException`).
  - **Só JSON:** o `CachedBodyRequest` não reconstrói `getParameter` a partir do corpo. Corrigir não seria barato (o Tomcat e o `MockHttpServletRequest` tratam de jeitos diferentes os parâmetros de formulário depois de o corpo ser lido, e o hash teria de cobri-los), então o filtro recusa com `415 UNSUPPORTED_MEDIA_TYPE` um endpoint marcado chamado com `application/x-www-form-urlencoded` ou multipart, em vez de perder os parâmetros em silêncio. Registrado no `CLAUDE.md`.
  - **Validação da chave:** 1 a 255 caracteres ASCII visíveis (`[\x21-\x7E]`); sem chave ou em branco → `400 IDEMPOTENCY_KEY_REQUIRED`; fora disso → `400 IDEMPOTENCY_KEY_INVALID`. Códigos separados do `VALIDATION_FAILED` para o cliente tratar sem ler `errors[]`.
  - **Contrato:** como nenhum endpoint de produção é idempotente ainda (o exemplo das convenções é só de teste e fica fora do contrato, decisão da E04), o `api/openapi.yaml` documenta o cabeçalho como componente reutilizável (`components.parameters.IdempotencyKey`, obrigatório, com as regras e os códigos) e `components.headers.IdempotentReplayed`, além da menção na descrição da API e dos quatro códigos na descrição do `code`. Cada operação marcada com `@Idempotent` recebe sozinha o parâmetro (por `$ref`), o cabeçalho `Idempotent-Replayed` nas respostas 2xx e as respostas 409 e 422 (sem trocar as que já documente); `IdempotencyTest` confere isso na operação de exemplo e que operações não marcadas não ganham nada.
  - **Dados guardados cifrados** (**decidido pelo usuário em 2026-10-13**): o corpo (em base64) e os cabeçalhos guardados (em JSON) são cifrados com o `FieldCipher` (`v1:<chave>:<base64url>`, AES-256-GCM) em colunas `text`. Os cabeçalhos também, porque `Location` leva ids e `Content-Disposition` pode levar nome de arquivo com nome de hóspede; status, escopo, chave e hash ficam em claro (não são dado pessoal e o escopo/chave precisam de índice). Como a chave vive no máximo 24h, uma rotação da chave de cifragem não exige re-cifrar: basta manter a antiga no anel por um dia. Se a chave de cifragem de um valor guardado sumir do anel, a repetição falha com 500 (`FieldDecryptionException`).
- Validações executadas:
  - Testes escritos antes do código: `./gradlew compileTestKotlin` falhou com `Unresolved reference 'Idempotent'`, `'IdempotencyKeyPurgeJob'`, `'purgeExpiredKeys'`. Na primeira execução depois da implementação, 16 de 17 passaram; o do contrato procurava a resposta `201`, mas o springdoc documenta `200` para `ResponseEntity`, e o teste passou a conferir toda resposta 2xx.
  - `./gradlew clean build --no-build-cache` → `BUILD SUCCESSFUL`: `detekt`, `detektMain`, `detektTest`, `ktlint*Check`, `test` (161 testes, 0 falhas, 0 pulados: os 143 anteriores mais 17 do `IdempotencyTest` e 1 do `DatabaseMigrationsTest`), `generateOpenApi`, `checkOpenApi` e `koverVerify`. Depois das mutações, `./gradlew build` → `BUILD SUCCESSFUL` e `./gradlew test --rerun-tasks` → 161 testes, 0 falhas. `./gradlew lint` → sucesso. Os logs de teste não têm `WARN`/`ERROR` novos (só os propositais de antes: listener de sonda, `/boom`, estouro numérico).
  - `./gradlew updateOpenApi` regravou `api/openapi.yaml` (+31/−4: descrição da API, `components.headers.IdempotentReplayed`, `components.parameters.IdempotencyKey`, códigos na descrição do `code`); `checkOpenApi` passa com o arquivo atual (dentro do `build`).
  - Critério 1 (sem chave → 400): `a marked endpoint without the key is a 400 with a stable code and runs nothing` (sem cabeçalho e em branco → `400 IDEMPOTENCY_KEY_REQUIRED`, `instance` com o caminho, handler não executa, nada gravado); `a malformed key is a 400 with a stable code` (256 caracteres, espaço, acento e tab → `400 IDEMPOTENCY_KEY_INVALID`; 255 caracteres aceitos); `security runs before idempotency` (anônimo sem chave → `401 UNAUTHENTICATED`); `endpoints that are not marked do not require the key`.
  - Critério 2 (repetição): `repeating the same request returns the stored response without running the effect again` — 3 requisições, mesmo status (201), corpo idêntico (inclusive o id UUIDv7 do pagamento), `Location`, `ETag` e `Content-Type` iguais, `Idempotent-Replayed: true` só nas repetições, 1 execução e 1 pagamento. `an error response is stored and replayed as the same error, never as a success` — 422 `LIMIT_EXCEEDED` repetido como 422 `application/problem+json` com o mesmo problema, 1 execução.
  - Critério 3 (outra requisição → 422): `the same key with a different request is a 422 with a stable code` — outro valor e outro caminho com a mesma chave → `422 IDEMPOTENCY_KEY_REUSED`, sem executar.
  - Critério 4 (concorrência): `a request with a key still in progress is a 409 and the effect runs once` (o handler da primeira fica preso num latch; a segunda → `409 IDEMPOTENCY_KEY_IN_USE`; solta a primeira → 201; a terceira é repetição; 1 execução) e `simultaneous requests with the same key run the effect exactly once` (8 threads liberadas por `CyclicBarrier`, handler segura 300 ms: exatamente 1 execução, 1 pagamento, uma única resposta original e as demais 409 ou repetição com o mesmo id). `simultaneous requests with different keys all run` (6 chaves → 6 pagamentos). O `IdempotencyTest` rodou 5 vezes seguidas com `--rerun-tasks`, sem falha.
  - Critério 5 (isolamento): `the key is isolated per user` — Alice e Bob com a mesma chave e o mesmo corpo → dois 201 com ids diferentes, sem `Idempotent-Replayed`; com a mesma chave e corpos diferentes → dois 201, sem 422; escopos gravados `user:alice`/`user:bob`.
  - Critério 6 (expurgo): `the response is kept for 24 hours` (`expires_at − created_at = 24h`); `an expired key is not replayed and the request runs again`; `the purge job deletes keys older than 24 hours and keeps the others` (chave com 24h01s apagada; com 23h59min e nova mantidas, e a de 23h59min ainda é repetida); `the purge job runs under a ShedLock lock` (`@Scheduled` + `@SchedulerLock(name = "idempotency-keys-purge")`). `DatabaseMigrationsTest` confere as colunas de `shared.idempotency_key`; `MigrationConventionTest` aceita a migração nova.
  - Correções depois do parecer (2026-10-13): `the stored response is encrypted at rest and replayed as the original` (as colunas `response_headers` e `response_body` começam com `v1:` e não contêm o id do pagamento nem `Location`; a repetição devolve corpo, `Location` e id originais); `an unexpected error in the handler is stored as a 500 and replayed as the same 500` (500 `INTERNAL_ERROR` sem a mensagem interna, repetido igual, 1 execução); `a body over the limit is a 413 and runs nothing` (corpo > 1 MB → `413 PAYLOAD_TOO_LARGE`, nada gravado); `form and multipart bodies are refused because the filter reads the body before the mvc` (`415 UNSUPPORTED_MEDIA_TYPE`). `IdempotencyFilterTest` cobre o `settleAfterFailure`, que o MVC real não alcança porque o `ApiExceptionHandler` converte toda `Exception`: exceção que escapa da cadeia guarda um 500 genérico (sem a mensagem) e é relançada intacta; `ServletException` com `AccessDeniedException` na causa libera a chave sem guardar; falha ao gravar a resposta não derruba a resposta.
  - Critério 7 (contrato e build): `the contract documents the header on marked operations only` (componente obrigatório no `header`, `$ref` na operação de pagamento, respostas 409/422, `Idempotent-Replayed` nas 2xx, nada na criação de item); `ApiSecurityTest` confere o componente no contrato de produção; build e testes oficiais acima.
  - Mutações (cada uma revertida em seguida, só o `IdempotencyTest`), todas pegas: chave em andamento passa a executar → 2 falhas (os dois testes de concorrência); escopo fixo para todos os usuários → 1 falha (isolamento); repetição sempre com status 200 → 2 falhas (repetição e erro guardado); expurgo só depois de 48h → 1 falha (expurgo); corpo fora do hash → 1 falha (422); chave vencida tratada como válida → 1 falha (vencida executa de novo); corpo gravado sem cifra → 8 falhas (inclusive a do teste de cifragem); exceção fora do MVC sem guardar o 500 → 1 falha no `IdempotencyFilterTest`.
  - Depois das correções: `./gradlew clean build --no-build-cache` → `BUILD SUCCESSFUL` (168 testes, 0 falhas, 0 pulados: 161 de antes mais 4 do `IdempotencyTest` e 3 do `IdempotencyFilterTest`); `checkOpenApi` passa sem regenerar (o contrato não mudou com as correções); `./gradlew lint` → sucesso; o `IdempotencyTest` rodou mais 3 vezes com `--rerun-tasks`, sem falha.
- Parecer do verificador: **APROVADO** (verificador independente disparado pelo orquestrador), sem problema grave ou médio. Os pontos baixos foram corrigidos depois: texto sobre o `If-Match` no hash, testes do `settleAfterFailure` e do 413, e a limitação do `getParameter` (agora recusada com 415). As correções e a cifragem ainda não passaram por nova verificação. **Segunda verificação (verificador novo, snapshot `88a844bc7c3d832021cbb99108662d46f5b99248`): APROVADO**, com os 7 critérios atendidos e sem problema grave ou médio. A cifragem está correta: corpo e cabeçalhos ficam em `v1:` no banco, a repetição devolve a resposta original e a migração segue a convenção. Não houve regressão. `./gradlew clean build --no-build-cache` passou com 168 testes e 0 falhas; `test lint checkOpenApi` também passou, e `IdempotencyTest`/`IdempotencyFilterTest` passaram 3 vezes com `--rerun-tasks`. Pontos baixos, não bloqueantes: (1) `JdbcIdempotencyRecords.toRecord` decifra em todo `find`, mesmo quando o registro está vencido ou tem outro hash; se a chave de cifragem sumir do anel antes do expurgo, até o 422 ou a retomada de uma chave vencida viram 500 (decifrar só na repetição resolveria); (2) o `scope` `user:<login>` fica em claro e é dado pessoal se o login for e-mail; é provisório até o M1; (3) o 413 só tem teste com `Content-Length`, não no caminho chunked; (4) handler assíncrono deixa a chave `IN_PROGRESS` (409) até vencer, o que é um erro de programação documentado; (5) o `ProblemWriter` extraído passou a enviar `Content-Length` nas respostas 401/403.
- Revisão humana (2026-10-13, entrevista conduzida pelo orquestrador): (1) guardar a resposta de qualquer status, inclusive 5xx, e repeti-la por 24h — mantido; (2) chave presa em `IN_PROGRESS` responde 409 até vencer — mantido; (3) requisição comparada byte a byte, com `If-Match` — mantido; (4) escopo `user:<login>` até o M1 — mantido; (5) **cifrar a resposta guardada com o `FieldCipher`** — mudou o código: corpo e cabeçalhos guardados passaram a ser cifrados (ver Decisões).
- Notas para a próxima etapa:
  - E06: nada muda no compose; o `IdempotencyKeyPurgeJob` usa o ShedLock que já existe. O filtro só age em `/api/*`, então o actuator não é afetado.
  - E07: o contrato ganhou `components.parameters` e `components.headers`; o oasdiff compara a partir deste arquivo.
  - M1: trocar o escopo `user:<authentication.name>` (em `IdempotencyFilter.scopeOfCurrentUser`) pelo id estável do usuário do JWT (e, se fizer sentido, do dispositivo); o nome de login pode mudar, o id não. Chaves gravadas antes da troca só vencem em 24h, sem migração.
  - M3/M7 (pagamentos, lançamentos, emissão fiscal): marque os endpoints com `@Idempotent`; o contrato ganha o cabeçalho sozinho. O caso de uso não precisa saber da idempotência. Para efeitos externos (PSP, middleware fiscal), a idempotência HTTP não substitui a idempotência com o provedor (D12).
  - M2: a idempotência de comandos offline por `commandId` é outra coisa e não usa esta tabela.
  - O springdoc documenta `200` para métodos que devolvem `ResponseEntity`, mesmo quando o status real é 201; se o contrato precisar do 201, anote a operação com `@ApiResponse(responseCode = "201")`.

---

## E06 — Imagem Docker, compose e observabilidade — #concluida

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

- Snapshot inicial: `f72ed79877428ca8785bc409e995931e9cab4b4b` (recebido do orquestrador; inclui as mudanças da E05 ainda não commitadas). Snapshot ao fim da implementação: `948a305904be7089ba619693e469b8a4772e8ad1` (antes do último ajuste do `HEALTHCHECK` e deste handoff).
- Arquivos alterados:
  - Docker (novos): `docker/Dockerfile`, `docker/compose.yaml`, `docker/.env.example`, `docker/caddy/Caddyfile`, `docker/prometheus/alerts.yaml`, `.dockerignore` (raiz). O `docker/.env` local (gerado para a validação, com uma chave de desenvolvimento sorteada) fica fora do Git pelo `.env` que já estava no `.gitignore`.
  - Build: `build.gradle.kts` e `gradle/libs.versions.toml` (`spring-boot-starter-actuator`, `micrometer-registry-prometheus`, `spring-boot-micrometer-tracing-opentelemetry`, `micrometer-tracing-bridge-otel`; nos testes, `spring-boot-starter-actuator-test` e `spring-boot-micrometer-tracing-test`; todos sem versão, pelos BOMs: Micrometer 1.17.1, Micrometer Tracing 1.7.1, OpenTelemetry 1.62.0).
  - Configuração: `src/main/resources/application.yaml` (`management.*`, `hikari.connection-timeout: 5000`, nível do `DefaultEventPublicationRegistry`, `zeny.events.metrics.refresh-interval`, `logging.structured.format.console: ecs` no `prod`).
  - Código: `shared/infrastructure/events/OutboxMetrics.kt` (novo), `shared/web/SecurityConfiguration.kt` (libera as três rotas do actuator; KDoc).
  - Testes: `shared/web/ActuatorExposureTest` (6, novo), `events/OutboxMetricsTest` (3, novo), `events/OutboxMetricsFailureTest` (1, novo, unitário), `testsupport/IntegrationTest` (`@AutoConfigureMetrics`), `events/EventPublicationRegistryTest` (o segundo contexto sobe com `--server.port=0`).
  - Documentação: `CLAUDE.md` (estado atual, comandos do compose e da imagem, seções "Docker e compose" e "Observabilidade", exceção de ShedLock para tarefa só de leitura), `README.md` ("Ambiente completo em Docker" e "Observabilidade"), este roadmap.
- Decisões:
  - **Imagem:** build em `eclipse-temurin:25-jdk-noble` só com `bootJar` (testes, lint e contrato ficam no `./gradlew build`/CI), com o `/root/.gradle` em cache do BuildKit; runtime em `eclipse-temurin:25-jre-alpine` (o JRE Ubuntu não traz `curl` nem `wget`; o Alpine traz o `wget` do BusyBox para o `HEALTHCHECK` e deixa a imagem com ~148 MB contra ~440 MB da base Ubuntu). Camadas do Spring Boot por `java -Djarmode=tools -jar app.jar extract --layers` (`dependencies`, `spring-boot-loader`, `snapshot-dependencies`, `application`), rodando `java -jar app.jar`. Usuário `zeny` com uid/gid fixos 10001, sem shell; os arquivos ficam do root e só leitura. `HEALTHCHECK` com `wget` na readiness (`--interval=15s --timeout=10s --start-period=120s --start-interval=3s --retries=3`). Opções da JVM fixas no `ENTRYPOINT` (`-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError`), e não em `JAVA_TOOL_OPTIONS`, para a JVM não escrever "Picked up…" fora do JSON. A imagem não define profile nem segredos. O `.dockerignore` é uma lista de permissão (`gradlew`, `gradle/`, `*.gradle.kts`, `src/main/`).
  - **MinIO:** as imagens oficiais `minio/minio` e `minio/mc` não existem mais no Docker Hub nem no Quay (o `minio/minio` no GitHub está arquivado; último release em 2025-10-15). O `storage` usa a distribuição comunitária `pgsty/minio:RELEASE.2026-08-04T00-00-00Z` (fork mantido pelo Pigsty, com o `mc` embutido, que o `storage-init` usa para criar o bucket). **Decidido pelo usuário em 2026-10-13** (ver notas).
  - **Compose:** projeto `zeny`; `api` sem porta publicada e com `depends_on` do `db` saudável; `db` `postgres:18` com volume em `/var/lib/postgresql` (layout do 18); `storage` + `storage-init` (bucket `ZENY_STORAGE_BUCKET`, padrão `zeny-files`, idempotente); `proxy` `caddy:2.11-alpine` com `auto_https off` e `admin off`, em `127.0.0.1:80` por padrão (`ZENY_HTTP_ADDRESS`/`ZENY_HTTP_PORT`). O `backup` aparece comentado com a referência ao M8. O compose lê `docker/.env` (o Compose procura o `.env` na pasta do arquivo, não na raiz; testado), então o modelo é `docker/.env.example`. Nenhuma credencial tem padrão no compose: `${VAR:?…}` faz o `up` falhar nomeando a variável. Profile padrão do compose: `prod` (`ZENY_PROFILE`). O `api` ainda não recebe as credenciais do MinIO: o módulo `files` (M1) as liga.
  - **Porta 80 no proxy**, e não 8080: a 8080 é a do `bootRun` e a do segundo contexto do `EventPublicationRegistryTest`, que falhou com `PortInUseException` enquanto o compose estava de pé na 8080. O teste passou a usar `--server.port=0`.
  - **Actuator:** `management.endpoints.access.default: none` (os endpoints não expostos nem viram bean), exposição web só de `health` e `prometheus`, sem a página `/actuator` (`discovery.enabled: false`), `show-components`/`show-details: never`, grupos de probe ligados fora do Kubernetes, readiness = `readinessState` + `db`, liveness = `livenessState`. Na `SecurityConfiguration`, só `GET` das três rotas é `permitAll`; o `/actuator/health` agregado, os componentes e o resto caem no `denyAll`. O springdoc continua sem o actuator (`api/openapi.yaml` não mudou).
  - **Prometheus só na rede interna:** o app libera `/actuator/prometheus` sem autenticação (raspagem em `http://api:8080`), e o Caddy responde 404 para ele. **Decidido pelo usuário em 2026-10-13** (ver notas).
  - **Hikari `connection-timeout` de 30 s para 5 s:** com o banco fora, a readiness levava 30 s para responder `DOWN` (o indicador `db` espera uma conexão); agora leva ~5 s. Efeito colateral: uma requisição que não consegue conexão falha em 5 s em vez de 30 s. **Decidido pelo usuário em 2026-10-13** (ver notas).
  - **Logs:** `logging.structured.format.console: ecs` no `prod` (ECS 8.11, uma linha JSON por evento, com `service.name`/`service.version`, `error.*` com a pilha numa linha e o MDC no topo); no `local`, o texto padrão do Boot com o `[app,traceId,spanId]`. `traceId`/`spanId` vêm da ponte OpenTelemetry do Micrometer (`spring-boot-micrometer-tracing-opentelemetry` + `micrometer-tracing-bridge-otel`), sem exportador de spans; o `starter-opentelemetry` foi descartado porque traz o registro OTLP de métricas, que tentaria enviar para `localhost:4318`. O servidor aceita e propaga `traceparent` (W3C). O `DefaultEventPublicationRegistry` foi para `WARN` (o monitor de staleness escrevia três linhas INFO por minuto; nota da E02).
  - **Métricas do outbox:** `OutboxMetrics` (`MeterBinder`) publica `zeny_outbox_publications{state="pending"|"failed"}` e `zeny_outbox_publications_oldest_age_seconds{state=…}` (`pending` = incompletas não `FAILED`: `PUBLISHED`, `PROCESSING`, `RESUBMITTED`; idade desde a `publication_date`). Uma consulta agrupada (`WHERE completion_date IS NULL`, que usa o índice existente) a cada `zeny.events.metrics.refresh-interval` (`PT30S`, a primeira no boot); a raspagem só lê a última leitura, e com o banco fora os gauges ficam `NaN`. Sem ShedLock de propósito (só leitura, por instância; exceção registrada no `CLAUDE.md`). Tag comum `application` em todas as métricas. Alertas em `docker/prometheus/alerts.yaml` (`ZenyOutboxFailedPublications` > 0 por 15 min, `ZenyOutboxFailedPublicationTooOld` > 1 h, `ZenyOutboxPendingPublicationsStuck` > 5 min, `ZenyOutboxMetricsUnavailable` para `NaN`); ainda não há Prometheus no compose.
  - **Testes com métricas:** `@IntegrationTest` ganhou `@AutoConfigureMetrics` (o `spring-boot-micrometer-metrics-test` desliga a exportação nos testes), para todos os testes de integração compartilharem um contexto com o registro do Prometheus.
- Validações executadas:
  - Docker 29.1.3 e Compose 2.40.3 disponíveis (H3).
  - Testes escritos antes do código: `./gradlew compileTestKotlin` falhou com `Unresolved reference 'OutboxMetrics'`. A configuração do actuator foi escrita antes da primeira execução dos testes; para provar que eles pegam o erro, cinco mutações (cada uma revertida) derrubaram os testes certos: sem o `permitAll` das sondas → 3 falhas (liveness/readiness, prometheus e o prometheus do outbox); `exposure.include: "*"` com acesso `unrestricted` → `only health and prometheus are exposed on the web`; readiness sem `db` → `readiness depends on the database…`; `show-components: always` → `liveness and readiness answer… with the status only`; `/actuator/**` liberado → os dois testes de caminhos fechados (anônimo e autenticado).
  - `./gradlew clean build --no-build-cache` → `BUILD SUCCESSFUL`: `detekt`, `detektMain`, `detektTest`, `ktlint*Check`, `test` (178 testes, 0 falhas, 0 pulados: os 168 da E05 mais 6 do `ActuatorExposureTest`, 3 do `OutboxMetricsTest` e 1 do `OutboxMetricsFailureTest`), `generateOpenApi`, `checkOpenApi` (contrato sem mudança) e `koverVerify`. `./gradlew test lint --rerun-tasks` → `BUILD SUCCESSFUL`, 178 testes, 0 falhas, com o compose de pé.
  - Critério 1: `docker compose -f docker/compose.yaml up -d` (com `docker/.env` criado pelo README) → `api`, `db`, `storage` e `proxy` de pé (`api`, `db` e `storage` `healthy`), `storage-init` `Exited (0)` com "Bucket created successfully `zeny/zeny-files`"; `curl -fsS http://localhost/actuator/health/readiness` → `{"status":"UP"}` em ~8 s. Pelo proxy: liveness 200 `{"status":"UP"}`; `/actuator/health`, `/actuator`, `/actuator/env` e `/api/v1/rooms` → 401 problema `UNAUTHENTICATED`; `/actuator/prometheus` → 404 do Caddy; `/api/v1/meta/version` → 200; `/swagger-ui.html` → 401 (profile `prod`).
  - Critério 2: `docker compose stop db` → readiness `{"status":"DOWN"}` 503 em 5,0 s (eram 30 s antes do `connection-timeout`), liveness segue 200, o container `api` vira `unhealthy` e os gauges do outbox `NaN`; `docker compose start db` → readiness 200 na primeira tentativa, `api` volta a `healthy` e os gauges a `0.0`.
  - Critério 3: `docker run --rm --entrypoint sh zeny-hotel-manager:dev -c 'id -u'` → `10001`; `docker compose exec api id` → `uid=10001(zeny) gid=10001(zeny)`; `Config.User` = `10001:10001`; `touch /app/x` → `Permission denied`.
  - Critério 4: `ActuatorExposureTest` — liveness/readiness 200 anônimas, só com `status`; prometheus 200 `text/plain` com JVM, Hikari e a tag `application`; `PathMappedEndpoints.allPaths` = `{/actuator/health, /actuator/prometheus}`; nenhum bean de `env`, `heapdump`, `threaddump`, `configprops`, `beans`, `loggers` e `shutdown`; 26 caminhos (`/actuator`, `/actuator/health`, componentes, `env`, `env/ZENY_ENCRYPTION_KEYS`, `heapdump`, `configprops`, `modulith`…) → 401 anônimo e ≥ 400 com `@WithMockUser(roles = ADMIN, ACTUATOR)`; `POST` nas três rotas ≠ 200; readiness com `db` e liveness sem.
  - Critério 5: no compose (`prod`), as 78 linhas de log do `api` numa subida com o banco derrubado e religado são todas JSON válido (`json.loads` linha a linha, 0 falhas, inclusive WARN/ERROR com pilha); numa segunda subida, 52 de 52. `traceId` nas requisições: a mesma imagem com `prod` e `LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_WEB=DEBUG` (o `prod` não escreve log por requisição bem-sucedida) → as linhas do `DispatcherServlet` da requisição trazem `traceId`/`spanId`; com `traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-…`, o `traceId` do log é `4bf92f3577b34da6a3ce929d0e0e4736`; sem o cabeçalho, um id novo de 32 hexadecimais; 58 de 58 linhas JSON.
  - Critério 6: `OutboxMetricsTest` (publicações inseridas em `shared.event_publication`: 3 pendentes, 2 `FAILED`, 1 concluída → `pending` 3, `failed` 2, idades de 6 min e 2 h; o `/actuator/prometheus` traz `zeny_outbox_publications{application="zeny-hotel-manager",state="failed"} 1.0` e `zeny_outbox_publications_oldest_age_seconds`); `OutboxMetricsFailureTest` (banco fora → `NaN`). No compose: `docker compose exec proxy wget -qO- http://api:8080/actuator/prometheus` mostra as quatro séries e `http_server_requests_seconds_count`. `promtool check rules docker/prometheus/alerts.yaml` (imagem `prom/prometheus`) → `SUCCESS: 4 rules found`.
  - Critério 7: `docker compose --env-file /dev/null … config` → falha com "required variable ZENY_DB_PASSWORD is missing a value"; com o `.env.example` sem alteração → falha por `ZENY_ENCRYPTION_KEYS` vazia. O `compose.yaml` não tem nenhum valor de credencial; o `.env.example` só tem os valores de desenvolvimento (`zeny`/`zeny`, decisão (d) da revisão da E02, e `zeny`/`zeny-dev-only` do MinIO) e a chave vazia. `git check-ignore -v docker/.env` → `.gitignore:21:.env`; o snapshot não contém o `docker/.env`.
  - Critério 8: `README.md` explica criar o `docker/.env`, subir, conferir, acompanhar e derrubar (`down` e `down -v`); build e testes oficiais acima.
  - Imagem: build frio ~1 min 40 s (a maior parte no `bootJar` com o cache do Gradle vazio); com cache, ~17 s depois de mudar `src/main`.
  - `docker compose -f docker/compose.yaml down` ao final: containers e rede removidos; os volumes `zeny_db-data`, `zeny_storage-data`, `zeny_proxy-data` e `zeny_proxy-config` ficaram (o `down -v` os apaga).
- Parecer do verificador: **APROVADO** (verificador independente disparado pelo orquestrador, snapshot `d5f30297008bfdbe814d7341be5870fa317b6120`). Os 8 critérios foram atendidos com evidência reproduzida e não há problema grave. `./gradlew clean build --no-build-cache` passou com 178 testes e 0 falhas. Com `up -d --build`, a readiness pelo proxy respondeu `{"status":"UP"}`. Com o `db` parado, respondeu `DOWN` 503 em ~5 s, a liveness seguiu 200 e o `api` ficou unhealthy; com o banco de volta, a readiness voltou em ~2 s. `id -u` respondeu 10001, e a pasta `/app` é só leitura. Pelo proxy, `env`, `heapdump`, `configprops` e o health agregado dão 401. As tentativas de contornar o bloqueio do `/actuator/prometheus` no Caddy (maiúsculas, `//`, `%70`, `%2F`, `..`, `./`) deram 404. No `prod`, todas as linhas de log são JSON, com `traceId` propagado do `traceparent`. `promtool` aceitou 4 regras. O compose e o `.env.example` não têm segredo, e o `docker/.env` é ignorado pelo Git. Os pontos médios eram as cinco decisões do usuário, resolvidas na entrevista. Pontos baixos: (1) os logs JSON com `traceId` e a readiness `DOWN` com o banco realmente fora só foram validados à mão; o teste confere a configuração; (2) o `OutboxMetricsTest` apaga toda a `shared.event_publication` do container compartilhado, o que hoje é seguro porque os testes não rodam em paralelo; (3) o `storage-init` passa a senha root do MinIO como argumento do `mc alias set`, apenas em desenvolvimento; (4) o `prod` não escreve log por requisição bem-sucedida, então o `traceId` só aparece em logs emitidos durante uma requisição.
- Notas para a próxima etapa:
  - **Decisões do usuário** (entrevista conduzida pelo orquestrador em 2026-10-13): o usuário escolheu a opção recomendada nas cinco, ou seja, o que já estava implementado, e o código não mudou. As alternativas ficam abaixo como registro.
    1. Imagem do MinIO: `pgsty/minio` com tag fixa, fork comunitário mantido (**recomendado**: tags versionadas, amd64 e arm64, `mc` embutido) × `cgr.dev/chainguard/minio` (fornecedor conhecido, mas sem tag de versão no plano gratuito, só `latest`) × trocar de servidor S3 (RustFS, Garage, SeaweedFS), o que mexe no texto da D14 e no Testcontainers MinIO previsto na ARCHITECTURE §17.
    2. `/actuator/prometheus`: aberto no app e bloqueado no proxy, raspado na rede interna (**recomendado**) × liberado também pelo proxy × protegido por credencial ou por uma porta de gerenciamento separada (`management.server.port`).
    3. Hikari `connection-timeout` de 5 s (**recomendado**: readiness `DOWN` rápida e falha rápida com o banco fora) × manter os 30 s do padrão (readiness lenta; o `HEALTHCHECK` marca `unhealthy` por timeout).
    4. Profile padrão do compose `prod` (**recomendado**: o compose é o formato de implantação, RNF-01) × `local` (Swagger UI e logs legíveis; basta `ZENY_PROFILE=local` no `docker/.env`).
    5. Runtime `eclipse-temurin:25-jre-alpine` (**recomendado**: menor e com `wget` para o `HEALTHCHECK`) × `25-jre-noble` (glibc, igual ao JDK do build e dos testes) com uma sonda em Java compilada no build.
  - E07 (CI): o build da imagem é `docker build -f docker/Dockerfile .` na raiz (BuildKit; o cache do Gradle no `--mount=type=cache` não passa entre execuções da CI sem `cache-to`/`cache-from`). Para conferir o compose na CI, crie o `docker/.env` a partir do `.env.example` com uma chave sorteada. Atenção à porta 80 do runner.
  - M1 (`files`): passar `ZENY_STORAGE_*` (endpoint `http://storage:9000`, credenciais e bucket) ao `api` no compose; trocar o usuário root do MinIO por um usuário de serviço com política só do bucket. M1 também acrescenta usuário e dispositivo ao MDC dos logs (ARCHITECTURE §16) e mascara dados pessoais.
  - M8: TLS no Caddy (tirar `auto_https off`, domínio, portas 80/443), `server.forward-headers-strategy` para confiar no `X-Forwarded-*` do proxy, serviço `backup`, Prometheus/alertas de verdade e a decisão nuvem × local. O Tomcat ainda responde em HTML a URL malformada (`/api/v1/%zz`), nota da E04.
  - Avisos conhecidos: os `ERROR` "Unexpected error occurred in scheduled task" e os `WARN` do Hikari só aparecem com o banco fora (jobs agendados sem conexão); com o banco de volta somem.

---

## E07 — Pipeline de CI — #concluida

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

- Snapshot inicial: `df868b853efaae063bafbff11d6b9de221665e4f` (recebido do orquestrador; inclui as mudanças da E05 e da E06 ainda não commitadas). Snapshot ao fim da implementação: `79f3091fcbff17024ca03041c23881fc688d751f` (antes de preencher este handoff). `git diff --stat` entre os dois: só os 5 arquivos abaixo. Snapshot depois das decisões do usuário: `dac86fe1716f62024df31e30b94ab0df7f8714f1` (antes de atualizar este handoff; acrescenta `.github/dependabot.yml` e `docs/ARCHITECTURE.md`).
- Arquivos alterados:
  - `.github/workflows/ci.yml` (novo): workflow `CI`, em `push` na `main` e em `pull_request`, com `permissions: contents: read`, `concurrency` (cancela a execução anterior só em PR) e três jobs paralelos em `ubuntu-24.04`. **`build`**: checkout, `actions/setup-java` (Temurin 25), `gradle/actions/setup-gradle` (cache `basic`, gravado só na `main`; valida o wrapper), `./gradlew build`, `./gradlew checkOpenApi`, artefatos `spring-modulith-docs` (`build/spring-modulith-docs/`) e `openapi` (`api/openapi.yaml`), ambos com `if-no-files-found: error`, e `test-reports` só em falha. **`api-compatibility`**: checkout com histórico completo e o script do oasdiff contra a base. **`image`**: Buildx + `docker/build-push-action` com `push: false` e cache `type=gha`.
  - `.github/scripts/check-api-breaking.sh` (novo, executável; o modo 755 precisa ir no commit): roda o oasdiff pela imagem oficial `tufin/oasdiff:v1.33.0`, fixada também pelo digest (`sha256:6263a96d…`), com `--network none` e só leitura. Compara o `api/openapi.yaml` do merge-base entre a referência recebida (padrão `origin/main`) e o `HEAD` com o da árvore de trabalho, usando `--match-path '^/api/v1(/|$)' --fail-on ERR --allow-external-refs=false --lang pt-br`. Base sem contrato → mensagem e saída 0; referência inexistente → saída 2.
  - `.github/dependabot.yml` (novo, depois da entrevista): Dependabot mensal só para `github-actions`, com um grupo único (um PR por mês) e prefixo `ci` nos commits.
  - `docs/ARCHITECTURE.md` §7 (linha "Contrato", depois da entrevista): registra a exceção do oasdiff, que permite remover um endpoint já `deprecated` no contrato anterior (e só depois do `x-sunset`, se houver).
  - `CLAUDE.md`: estado atual (E07), linha na tabela de comandos e seção "CI" (jobs, base da comparação, regra de *breaking change* e de depreciação, actions por SHA, sem segredos, como validar com o actionlint).
  - `README.md`: selo da CI, comando local do oasdiff e seção "Integração contínua".
  - Este roadmap: tag e handoff.
- Decisões (as marcadas com "decidido pelo usuário" vieram da entrevista conduzida pelo orquestrador em 2026-10-13):
  - **oasdiff pela imagem Docker oficial e não pela ação `oasdiff/oasdiff-action`** (H5; **decidido pelo usuário em 2026-10-13**: fica como está). A ação (v0.1.18) não tem filtro de caminho (`--match-path`) e, por padrão (`review: true`), envia os dois contratos a oasdiff.com e comenta no PR. A imagem roda igual na CI e na máquina local e fica fixada por tag e digest. Na CI, o formato `githubactions` gera anotações que apontam para `api/openapi.yaml`, com linha e coluna quando a mudança está no lado da revisão. Quando ela está no lado da base (endpoint removido), o oasdiff escreveria `file=base/api/openapi.yaml`, um caminho que não existe no repositório (ponto baixo do verificador). O script troca isso por `file=api/openapi.yaml`, sem linha, porque a linha seria a da base.
  - **Regra de *breaking change***: falha em nível `ERR` (`--fail-on ERR`) só nos caminhos de `/api/v1` (ARCHITECTURE §7: "Mudança incompatível vai para `/api/v2`"; "O `oasdiff` bloqueia *breaking changes* em `v1`"). Avisos `WARN` aparecem no log, mas não reprovam (**decidido pelo usuário em 2026-10-13**: `ERR`, como está). Remover um endpoint passa se ele já estava `deprecated` na base e falha antes do `x-sunset`, se houver (testado). **Decidido pelo usuário em 2026-10-13:** essa exceção, que é o padrão do oasdiff, é permitida e ficou registrada na ARCHITECTURE §7 e no `CLAUDE.md`.
  - **Base da comparação:** num PR, `pull_request.base.sha`; o checkout é o commit de merge, então o merge-base é a ponta da branch de destino e a comparação é "destino × resultado do merge", sem falso positivo por mudança que entrou na `main` depois. Num push na `main`, `github.event.before`. **Decidido pelo usuário em 2026-10-13:** se ele faltar, for só zeros (branch nova) ou não existir no checkout (push forçado), a base é o pai do `HEAD` (`HEAD~1`, garantido pelo `fetch-depth: 0`), e não mais a `origin/main`, que num push é o próprio `HEAD` e faria o oasdiff comparar o contrato consigo mesmo. Sem commit pai (commit raiz), o passo informa e sai com 0. Num PR sem `base.sha` (não deveria acontecer), a base é a `origin/` da branch de destino. O script também avisa quando a base é o próprio `HEAD`, o que localmente é normal na `main` sem commits novos, porque aí só as mudanças não commitadas são comparadas.
  - **Gatilhos:** `push` só na `main` e `pull_request` de qualquer branch, para não rodar duas vezes cada push de uma branch com PR. O trabalho é direto na `main` (CLAUDE.md) (**decidido pelo usuário em 2026-10-13**: fica como está). **Consequência registrada no `CLAUDE.md` e no `README.md`:** uma quebra de API (ou qualquer outra falha) que entra por push direto na `main` só deixa a CI vermelha depois do fato, sem ser bloqueada. O bloqueio de verdade exigiria trabalhar com PR e proteger a `main` exigindo os três checks.
  - **Cache do Gradle:** `cache-provider: basic` (MIT, sobre o `actions/cache`) em vez do padrão `enhanced` do `setup-gradle` v6, que é proprietário, gratuito para repositório público e "Free Preview" para privado (**decidido pelo usuário em 2026-10-13**: fica como está). `cache-read-only` explícito fora da `main`. Na imagem, cache das camadas no Actions (`type=gha`, escopo `image`); o `--mount=type=cache` do Gradle no Dockerfile não persiste entre execuções.
  - **Actions fixadas por SHA de commit**, com a versão num comentário: `actions/checkout` v7.0.1, `actions/setup-java` v6.0.1, `gradle/actions/setup-gradle` v6.4.0, `actions/upload-artifact` v7.0.2, `docker/setup-buildx-action` v4.4.1 e `docker/build-push-action` v7.4.0, as versões mais recentes em 2026-10-13. **Decidido pelo usuário em 2026-10-13:** o Dependabot mensal só para `github-actions` (`.github/dependabot.yml`) abre um PR agrupado por mês e troca o SHA e o comentário de versão juntos.
  - **`checkOpenApi` como passo próprio**, além de rodar dentro do `build`: deixa a falha do contrato visível no resumo e, depois do `build`, custa ~1 s (`generateOpenApi` fica `UP-TO-DATE`).
  - Jobs separados para que o oasdiff e a imagem não esperem os testes e para que cada falha apareça com nome próprio. `persist-credentials: false` nos checkouts, `timeout-minutes` em todos os jobs, nenhum `continue-on-error` e nenhum `secrets.*`. O cache `gha` do Buildx usa o token de runtime do próprio Actions, não um segredo do repositório.
- Validações executadas:
  - `actionlint` 1.7.12 (imagem `rhysd/actionlint:1.7.12`, que traz o shellcheck 0.11 e o aplica aos blocos `run`): `.github/workflows/ci.yml` → 0 erros, saída 0. Prova de que ele pega erro: uma cópia com `continue-on-error: maybe` → 3 erros, saída 1. `shellcheck -x .github/scripts/check-api-breaking.sh` → sem apontamentos.
  - `./gradlew clean build --no-build-cache` → `BUILD SUCCESSFUL` em 52 s: `detekt`, `detektMain`, `detektTest`, `ktlint*Check`, `test` (178 testes, 0 falhas, 0 erros, 0 pulados), `generateOpenApi`, `checkOpenApi` e `koverVerify`. A documentação do Modulith saiu em `build/spring-modulith-docs/` (`all-docs.adoc`, `components.puml`, `module-shared.adoc`, `module-shared.puml`), a pasta que o artefato publica.
  - `./gradlew checkOpenApi` → `BUILD SUCCESSFUL` (1 s).
  - `docker build --no-cache -f docker/Dockerfile -t zeny-hotel-manager:ci .` → sucesso em 1 min 31 s (`bootJar` 83 s); `id -u` na imagem → `10001`; imagem removida em seguida. Com cache: 1,7 s.
  - oasdiff contra a `main` real: `.github/scripts/check-api-breaking.sh` (base `6105f69`, o commit da E04) × contrato atual (que ganhou os componentes da E05) → "No breaking changes to report, but the specs are different", saída 0.
  - Passo `run` do job `api-compatibility` extraído do YAML e rodado no repositório com `BASE_SHA` vazio, só zeros, `6105f69` e um SHA inexistente: nos quatro casos, base `6105f6949484` e saída 0 (os três inválidos caíram para `origin/main`).
  - Oasdiff reprova *breaking change* (repositório git descartável no scratchpad, com o contrato atual como base; o `api/openapi.yaml` real não foi tocado): endpoint `/api/v1/meta/version` removido → `api-path-removed-without-deprecation`, saída 1; `version` da resposta de `string` para `integer` → `response-property-type-changed`, saída 1; `version` deixando de ser obrigatório → `response-property-became-optional`, saída 1; endpoint novo em `/api/v1` → saída 0; `/api/v2/x` e `/internal/y` removidos → saída 0 (fora de `/api/v1`); os mesmos com a remoção de `/api/v1/meta/version` → saída 1; endpoint já `deprecated` na base e removido → saída 0; `deprecated` com `x-sunset: 2027-12-31` e removido → "removido antes da data de expiração", saída 1; base sem contrato → mensagem e saída 0; referência inexistente → saída 2. No formato `githubactions`: `::error title=response-property-type-changed,file=api/openapi.yaml,line=156,col=11::…`, saída 1.
  - `grep` por `continue-on-error`, `secrets.`, `GITHUB_TOKEN` e `push: true` no workflow → nada encontrado. `git diff --check` → limpo. `git status --short` → só os arquivos das etapas E05 a E07, sem artefatos de build.
  - Depois das decisões do usuário (2026-10-13):
    - `actionlint` → 0 erros; `shellcheck -x` no script → sem apontamentos.
    - `.github/dependabot.yml` validado contra o schema do SchemaStore (`dependabot-2.0.json`, draft-07, com `jsonschema`) → válido. Uma cópia com `interval: mensal` e `package-ecosystem: github-action` → 3 erros, o que prova que a validação pega erro.
    - Nada de Gradle mudou: `./gradlew checkOpenApi` → `BUILD SUCCESSFUL`. `.github/scripts/check-api-breaking.sh` contra a `origin/main` → base `6105f69`, "No breaking changes", saída 0.
    - Lógica de base do passo da CI, extraída do YAML e rodada num repositório descartável em que o `HEAD` (C2) remove `/api/v1/meta/version` em relação ao pai (C1). Se algum caso comparasse o contrato consigo mesmo, daria saída 0. Push com `PUSH_BEFORE` vazio, só zeros, o SHA real de C1 e um SHA inexistente → nos quatro, base C1 (≠ `HEAD`), `::error title=api-path-removed-without-deprecation,file=api/openapi.yaml::…` e saída 1. PR com `PR_BASE_SHA` = C1 → base C1, saída 1. `HEAD` compatível (endpoint novo) com `PUSH_BEFORE` vazio → base C1, saída 0. Commit raiz → "O HEAD não tem commit pai…", saída 0.
    - O mesmo passo no repositório real (`HEAD` = `6105f69`) com os quatro valores de `PUSH_BEFORE`: a base é sempre `9ba21b7` (o pai, ≠ `HEAD`), que ainda não tinha contrato (E02), então a saída é "não há contrato anterior" e 0.
    - `grep` por `continue-on-error`/`secrets.` → nada; `git diff --check` → limpo.
  - Não validado: a execução real no GitHub Actions. Ela só acontece depois do push, que fica a critério do usuário (risco previsto na etapa; o gate E08 pede a confirmação). Também não foram exercitados localmente o cache do `setup-gradle`, os artefatos e o cache `gha` do Buildx, que só existem no Actions.
- Parecer do verificador: **APROVADO** (verificador independente disparado pelo orquestrador), sem problema grave. O ponto baixo da anotação `file=base/api/openapi.yaml` foi corrigido depois (ver Decisões). As mudanças posteriores à entrevista ainda não passaram por nova verificação. **Segunda verificação (verificador novo, snapshot `c011779099837bb5ee927483356f9c3a8625d6bb`): APROVADO**, com os 6 critérios atendidos e sem problema grave ou médio. O Dependabot é válido pelo schema do SchemaStore. O verificador montou repositórios descartáveis para a base da comparação. Num push, `before` vazio, só zeros, SHA real ou SHA inexistente resolvem sempre para o commit pai, e a quebra sai com 1. Num PR (commit de merge), a comparação é destino × merge, sem falso positivo. Remover um endpoint `deprecated` passa; com `x-sunset` futuro, reprova. `./gradlew clean build --no-build-cache` passou com 178 testes e 0 falhas; actionlint e shellcheck deram 0. A imagem foi construída com cache e roda com uid 10001. Pontos baixos, não bloqueantes: (1) se a base resolvida for o próprio HEAD, o script só avisa e sai com 0; isso é raro num push, e transformar o aviso em falha na CI fecharia a brecha; (2) quando `before` é inválido, o recurso ao `HEAD~1` só olha o último commit, e uma quebra num commit intermediário de um push com vários commits passa; (3) a evidência acima sobre a lógica antiga ("os três inválidos caíram para `origin/main`") foi substituída pela de `HEAD~1`, e "a base é sempre `9ba21b7`" não vale quando o SHA real é o próprio HEAD `6105f69`; (4) a execução real no GitHub Actions (cache, artefatos, `gha`, Dependabot) depende do push e fica para a E08. Revisão humana: o usuário confirmou a H4 (GitHub Actions) em 2026-10-13.
- Revisão humana (2026-10-13, entrevista conduzida pelo orquestrador): (1) oasdiff pela imagem fixada, chamado por script — mantido; (2) `--fail-on ERR` — mantido; (3) push só na `main` mais todo PR — mantido; (4) cache `basic` — mantido; (5) **Dependabot mensal só para `github-actions`** — criado `.github/dependabot.yml`; (6) **remover endpoint v1 já `deprecated` é permitido** — registrado na ARCHITECTURE §7; (7) **base de um push sem `before` válido é o commit pai, não a `origin/main`** — workflow alterado, e registrado que um push direto na `main` não é bloqueado.
- Notas para a próxima etapa:
  - E08: a primeira execução real depende do push do usuário. Confira na aba Actions os três jobs verdes e os artefatos `spring-modulith-docs` e `openapi`. No primeiro push, `github.event.before` é o `6105f69` da `origin/main`, que tem contrato, então o oasdiff compara de verdade. O primeiro PR do Dependabot sai no próximo ciclo mensal depois do push do `.github/dependabot.yml`; confira se ele troca o SHA e o comentário juntos. Se o runner reclamar do Docker ou do Testcontainers, o job `build` é o primeiro suspeito. Recomendado depois do primeiro verde: proteger a `main` no GitHub, exigindo os três checks (`Build, testes e contrato`, `Compatibilidade da API (oasdiff)` e `Imagem Docker (sem push)`). Isso é configuração do repositório, não do código.
  - Mudança de API incompatível de propósito em `/api/v1`: não contorne o oasdiff. Crie a rota em `/api/v2` ou deprecie antes (`@Deprecated`/`deprecated: true` no contrato e, se quiser prazo, `x-sunset`). Se for inevitável, o oasdiff aceita `--err-ignore` com um arquivo de exceções, que hoje não está configurado.
  - Atualizar uma action: o Dependabot faz isso por mês; à mão, troque o SHA e o comentário de versão juntos. Atualizar o oasdiff: troque a tag e o digest no script (`OASDIFF_IMAGE` sobrescreve localmente).
  - Não há smoke test do compose na CI (fora do escopo). Se for incluir, siga a nota da E06: `docker/.env` a partir do `.env.example` com uma chave sorteada e atenção à porta 80 do runner.

---

## E08 — Gate do marco M0 — #concluida

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

- [x] Todos os itens da "Definição global de pronto" estão marcados, cada um com evidência numa cópia limpa.
- [x] A execução da CI no GitHub foi confirmada pelo usuário, ou a ausência dela está registrada como pendência explícita.
- [x] `CLAUDE.md` e `README.md` estão coerentes com o código (comandos rodam como descritos).
- [x] `docs/roadmaps/README.md` registra a conclusão do M0 no histórico de revisões.

**Validação obrigatória:**

- Comandos oficiais de build, teste e verificação do contrato numa cópia limpa → sucesso.
- `docker compose -f docker/compose.yaml up -d` na cópia limpa → readiness `UP`; `down` em seguida.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Diferenças entre a máquina local e a CI (versão do Docker, arquitetura do processador): registrar no handoff.

**Evidências e handoff:**

- Snapshot inicial: `3777a7b470df87ecd370d141203a044b1b02ea21` (recebido do orquestrador; é a árvore do `HEAD` `6e9bd9c`, com a árvore de trabalho limpa).
- Arquivos alterados (só documentação):
  - Este roadmap: tag da E08, "Definição global de pronto" marcada com evidência, critérios de aceite, este handoff e o histórico de revisões.
  - `docs/roadmaps/README.md`: conclusão do M0 no histórico de revisões.
  - `CLAUDE.md`: uma frase em "Estado atual e comandos" registrando o gate do M0 e o próximo passo (sincronizar o roadmap do M1).
  - `README.md`: nenhuma mudança necessária; conferido contra o código.
- Cópia limpa e ambiente:
  - `git clone` do repositório local para o scratchpad (`HEAD` `6e9bd9c`, árvore igual ao snapshot, `git status` vazio, sem `build/`, `.gradle/`, `.kotlin/` nem `docker/.env`). Modo 755 de `gradlew` e de `.github/scripts/check-api-breaking.sh` conferido no índice.
  - Gradle sem estado local: `GRADLE_USER_HOME` novo e vazio dentro do scratchpad (o build baixou o Gradle 9.8.1 e todas as dependências) e `--no-build-cache`; nas execuções seguintes, também `--rerun-tasks`.
  - Máquina: x86_64, OpenJDK 25.0.4.1, Docker 29.1.3, Compose 2.40.3. A CI roda em `ubuntu-24.04` (x86_64) com Temurin 25; nenhuma diferença de comportamento observada. As imagens base do Docker (`eclipse-temurin`, `postgres:18`, `pgsty/minio`, `caddy`) vieram do cache local de imagens; o build da imagem da aplicação foi `--no-cache`.
- Verificações e resultados (cópia limpa):
  - `./gradlew build --no-build-cache` → `BUILD SUCCESSFUL` em 2 min 1 s, 26 tarefas executadas: `compileKotlin`, `detekt`, `bootJar`, `compileTestKotlin`, `detektMain`, `generateOpenApi` (`OpenApiContractExport` 1/1), `checkOpenApi`, `detektTest`, `test`, `koverVerify` e `ktlint*Check`. Testes: 178 em 30 classes, 0 falhas, 0 erros, 0 pulados (entre eles `ModularityTest` 3, `LayerArchitectureTest` 5, `LayerRulesTest` 9, `MigrationConventionTest` 11, `DatabaseMigrationsTest` 8, `ApiSecurityTest` 8, `ActuatorExposureTest` 6, `IdempotencyTest` 21, `EventPublicationRegistryTest` 5), com o PostgreSQL 18 do Testcontainers.
  - `./gradlew test --no-build-cache --rerun-tasks` → sucesso; `./gradlew test --tests 'com.zeny.hotel.ModularityTest'` → sucesso; `./gradlew lint --rerun-tasks` → sucesso; `./gradlew checkOpenApi --rerun-tasks` → `BUILD SUCCESSFUL`.
  - Documentação do Modulith: `build/spring-modulith-docs/` com `all-docs.adoc`, `components.puml`, `module-shared.adoc` e `module-shared.puml`.
  - Contrato: o `build/openapi/openapi.yaml` gerado é idêntico ao `api/openapi.yaml` versionado (`cmp`). Negativos: título editado à mão → `checkOpenApi` falha com "api/openapi.yaml está desatualizado (primeira diferença na linha 8)…", saída 1; `@GetMapping("/version")` trocado por `"/server-version"` sem regenerar → falha na linha 17, saída 1; `./gradlew updateOpenApi` → regrava 1 linha e o `checkOpenApi` passa; tudo restaurado com `git checkout` e o `checkOpenApi` passa de novo.
  - Outros comandos da tabela do `CLAUDE.md`: `./gradlew koverHtmlReport` → `build/reports/kover/html/index.html`; `./gradlew ktlintFormat` → sucesso sem alterar nenhum arquivo; `bootRun --args='--spring.profiles.active=local'` com o `docker run … postgres:18` documentado (container de nome próprio) e as chaves geradas pelo comando documentado → "Started ZenyHotelApplicationKt in 2.5 seconds", `GET /api/v1/meta/version` 200, Swagger UI 200 e readiness `UP` na 8080; encerrado em seguida (fica em primeiro plano até Ctrl+C, como documentado).
  - CI local: `docker run rhysd/actionlint:1.7.12 .github/workflows/ci.yml` → 0 erros; `.github/scripts/check-api-breaking.sh HEAD~1` → base `6105f69` × árvore, "Nenhuma mudança incompatível (nível ERR) em /api/v1", saída 0.
  - Compose (projeto `-p zeny-e08`, para não tocar os volumes `zeny_*` do usuário): `docker/.env` criado a partir do `docker/.env.example` com chave de cifragem, senha do banco e senha do MinIO sorteadas na hora (só na cópia limpa, ignorado pelo Git: `.gitignore:21:.env`). `docker compose -p zeny-e08 -f docker/compose.yaml build --no-cache api` → 1 min 12 s; `up -d` → readiness `{"status":"UP"}` pelo proxy em 4 s depois do `up`; `api` (healthy), `db` (healthy), `storage` (healthy) e `proxy` de pé; `storage-init` `Exited (0)` com "Bucket created successfully `zeny/zeny-files`". Pelo proxy: liveness e readiness 200 `{"status":"UP"}`, `/api/v1/meta/version` 200, `/actuator/prometheus` 404 (Caddy), `/actuator/health`, `/actuator/env`, `/api/v1/rooms` e `/swagger-ui.html` 401 problema (profile `prod`). `exec api id` → `uid=10001(zeny)`. `http://api:8080/actuator/prometheus` pela rede interna → as quatro séries `zeny_outbox_*`. Logs do `api`: 33 linhas, todas JSON válido. Histórico do Flyway no banco do compose: as 4 migrações do `shared` com sucesso. Sem `docker/.env` (`--env-file /dev/null`), o `config` falha nomeando `ZENY_DB_USERNAME`. `down -v` → containers, rede e os volumes `zeny-e08_*` removidos.
  - Estado local preservado: a imagem `zeny-hotel-manager:dev` do usuário (`f06537eed912`) foi guardada numa tag provisória antes do build e restaurada depois; a imagem construída no teste foi apagada. Os volumes `zeny_*` do usuário não foram tocados. O daemon do Gradle do `GRADLE_USER_HOME` provisório foi parado, e a cópia limpa e esse `GRADLE_USER_HOME` foram apagados no fim.
  - CI no GitHub: **confirmada pelo usuário em 2026-10-14** (repassado pelo orquestrador). `gh run list` mostra o workflow "CI" do commit `6e9bd9c` (push na `main`, 2026-10-14T02:40:20Z, run `38502250745`) concluído com sucesso em 4 min 2 s. Conferido também nesta etapa: os três jobs (`Build, testes e contrato`, `Compatibilidade da API (oasdiff)` e `Imagem Docker (sem push)`) com `success` e os artefatos `spring-modulith-docs` e `openapi` publicados. O Dependabot já rodou duas vezes ("Dependabot Updates", sucesso); nenhum PR dele foi aberto até agora.
  - Coerência da documentação: os caminhos, classes, tarefas e valores citados no `CLAUDE.md` e no `README.md` foram conferidos no código (versões em `gradle/libs.versions.toml` e no wrapper; tarefas `lint`, `generateOpenApi`, `checkOpenApi`, `updateOpenApi` no `build.gradle.kts`; `HALF_EVEN` em `Money.ROUNDING_MODE`; limite 50/200 em `Pagination.kt`; escopo `user:<login>` no `IdempotencyFilter`; `ClockConfiguration` em `shared/infrastructure/time/`; `spring-modulith-moments` excluído; serviços, variáveis e portas do compose, do `.env.example` e do Caddyfile; jobs e artefatos do `ci.yml`). Nenhuma divergência encontrada.
- Decisões:
  - O projeto do compose foi `zeny-e08` em vez do padrão `zeny`, e as senhas de teste foram sorteadas, para não reutilizar estado local (volumes e `.env`) do usuário. O comando em si é o documentado.
  - Nenhum problema exigiu devolver uma etapa anterior.
- Parecer do verificador: **APROVADO** (verificador independente disparado pelo orquestrador, snapshot da verificação `bd630106c273628ce963bc36b9b1b24fbfc9e610`). Os 4 critérios foram aprovados com evidência reproduzida numa cópia limpa própria (clone + diff da árvore de trabalho, `GRADLE_USER_HOME` vazio, `--no-build-cache`). `./gradlew build` passou com 178 testes e 0 falhas; `ModularityTest` gerou a documentação; o contrato gerado é idêntico ao versionado, e o `checkOpenApi` falhou como devia com o yaml editado à mão e com a rota do `MetaController` trocada; `bootRun` subiu como documentado; no compose (projeto `zeny-e08v`, `build --no-cache`), readiness `UP` pelo proxy, serviços healthy, uid 10001, logs JSON sem segredo e `down -v` limpo; actionlint e `check-api-breaking.sh` com saída 0; `gh run view 38502250745` com os 3 jobs em `success`. Nenhum problema grave ou médio, e nenhuma divergência entre `CLAUDE.md`/`README.md` e o código. Pontos menores: (1) a data de revisão no cabeçalho deste roadmap estava em 2026-10-13 (corrigida pelo orquestrador); (2) `docs/roadmaps/README.md` declarava o M0 concluído antes da troca da tag, resolvido com a tag `#concluida`; (3) informativo: o aviso de recursos do Gradle incompatíveis com o Gradle 10 vem de plugin e não foi investigado; (4) as imagens base do Docker vieram do cache local, mas o job `image` da CI as construiu do zero num runner limpo.
- Notas para o marco M1:
  - Antes da primeira etapa do M1, sincronizar `docs/roadmaps/roadmap-m1-acesso-configuracao.md` com o código real (skill `development-roadmap`, modo atualização), como manda o `CLAUDE.md`.
  - Recomendado agora que a CI ficou verde: proteger a `main` no GitHub exigindo os três checks, ou passar a trabalhar com PR (hoje uma quebra que entra por push direto só aparece depois).
  - Pendências herdadas dos handoffs, a tratar no M1: JWT na `SecurityConfiguration` e lista `PUBLIC_API_ENDPOINTS` do `ApiSecurityTest`; escopo do `Idempotency-Key` pelo id estável do usuário (`IdempotencyFilter.scopeOfCurrentUser`); usuário de migração distinto do usuário da aplicação antes das tabelas imutáveis (RN-03); `DataIntegrityViolationException` → `ConflictException`; teste do 412 por lock otimista; usuário e dispositivo no MDC dos logs; `files` com cifragem de bytes e credenciais `ZENY_STORAGE_*` no `api` do compose (com usuário de serviço do MinIO); conferir as regras de `LayerRules` e o `TypedIdConverter` com o primeiro módulo real; falsos positivos conhecidos do `MigrationConventions` com `GRANT … ON FUNCTION f(numeric(14,2))` e `ALTER DEFAULT PRIVILEGES`.
  - Avisos conhecidos e inofensivos no build: "Deprecated Gradle features… incompatible with Gradle 10", vindo de plugin.

---

## Histórico de revisões

- 2026-10-13 — roadmap criado.
- 2026-10-13 — H3 confirmada: JDK 25 e Docker instalados.
- 2026-10-13 — `CLAUDE.md` criado antes de E01; E01 passa a atualizá-lo em vez de criá-lo.
- 2026-10-14 — E08 (gate do M0): definição global de pronto conferida numa cópia limpa e marcada com evidência; primeira execução da CI no GitHub confirmada.
