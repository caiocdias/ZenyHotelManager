# M5 E13 — Travamento de dependências do Gradle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ligar o travamento de dependências do Gradle (dependency locking) em modo estrito, versionar os lockfiles gerados pelo próprio Gradle e fazer o build local, a CI e a imagem Docker usarem esses lockfiles, para que o `osv-scanner` enxergue o grafo resolvido do projeto.

**Architecture:** `dependencyLocking { lockAllConfigurations(); lockMode = STRICT }` no `build.gradle.kts` trava todas as configurações resolvidas do projeto (compilação, runtime, testes e as ferramentas: detekt, ktlint, kover, compilador Kotlin) em `gradle.lockfile`; o classpath dos plugins (`buildscript.configurations.classpath`) é travado em `buildscript-gradle.lockfile`. Os dois são gerados por `./gradlew dependencies --write-locks` e entram no contexto da imagem Docker. Nenhuma versão muda.

**Tech Stack:** Gradle 9.8.1 (wrapper), Kotlin DSL, version catalog `gradle/libs.versions.toml`, Docker/BuildKit, osv-scanner 2.7.0.

**Spec:** `docs/roadmaps/roadmap-m5-recepcao-fnrh.md`, seção "E13 — Travamento de dependências do Gradle para o scanner" (decisão D3 do usuário, 2026-10-27). Documentação oficial: https://docs.gradle.org/current/userguide/dependency_locking.html

## Global Constraints

- Versões de dependências e plugins ficam só em `gradle/libs.versions.toml`; nunca versão literal no `build.gradle.kts`. Esta etapa **não** muda nenhuma versão.
- Os lockfiles são gerados pelo próprio Gradle (`--write-locks`); nunca editados à mão.
- Fora de escopo: atualizar versões, corrigir vulnerabilidades apontadas pelo scanner, plugin OWASP Dependency-Check, rodar o `osv-scanner` no CI.
- A convenção "toda mudança de dependência atualiza o lockfile no mesmo commit" vai para a captura do brain (nota `ZenyHotelManager - Ambiente, banco, chaves e build`), **nunca** para o `CLAUDE.md`.
- `./gradlew build` e `./gradlew checkOpenApi` verdes; nenhuma mudança em `api/openapi.yaml`.
- Comentários e documentação em pt-BR; commits em pt-BR com o trailer `Soul: <soul>`.
- O `.dockerignore` continua uma lista de permissão (`*` seguido de `!…`): só entram os lockfiles além do que já entra.

## Review Focus

1. **Imagem Docker sem os lockfiles:** o `.dockerignore` é lista de permissão e o `Dockerfile` copia os arquivos do build um a um; se os lockfiles não entrarem, o modo estrito precisa fazer o `docker build` falhar (e não resolver sem trava). Esperado: a imagem constrói com os lockfiles, e falha sem eles.
2. **Versão trocada no catálogo sem regenerar o lockfile:** `./gradlew build` falha na resolução, com a mensagem do Gradle sobre o lock state.
3. **Dependência nova no `build.gradle.kts` sem regenerar:** `./gradlew build` falha.
4. **Versão de plugin trocada sem regenerar:** falha pelo `buildscript-gradle.lockfile`.
5. **Regenerar sem mudança nenhuma:** `./gradlew dependencies --write-locks` de novo deixa os lockfiles idênticos (sem diff espúrio a cada execução).

Os cinco são conferidos pelo testador numa cópia descartável (Step 6 da Task 1); nada disso é teste JUnit, porque o comportamento é do Gradle e do Docker, não do código da aplicação.

---

### Task 1: Travamento estrito, lockfiles e imagem Docker

**Files:**
- Modify: `build.gradle.kts` (bloco `buildscript` antes de `plugins`; bloco `dependencyLocking` depois de `allOpen`)
- Create (gerados pelo Gradle): `gradle.lockfile`, `buildscript-gradle.lockfile`
- Modify: `.dockerignore` (permitir os dois lockfiles)
- Modify: `docker/Dockerfile` (copiar os dois lockfiles junto de `settings.gradle.kts` e `build.gradle.kts`, antes do `./gradlew --quiet --version`)
- Modify: `README.md` (tabela "Comandos": linha "Atualizar os lockfiles depois de mudar dependência ou plugin" → `./gradlew dependencies --write-locks`; e o parágrafo logo abaixo da tabela, que fala do catálogo, ganha uma frase: o grafo resolvido fica travado em `gradle.lockfile` e `buildscript-gradle.lockfile`, que mudam no mesmo commit do catálogo, e o build falha se ficarem desatualizados)

**Interfaces:**
- Consumes: nada de outras tarefas.
- Produces: `gradle.lockfile` e `buildscript-gradle.lockfile` na raiz; comando de atualização `./gradlew dependencies --write-locks`; o scanner lê os dois.

- [ ] **Step 1: Prova de que hoje falta o que a etapa entrega**

Run: `test -f gradle.lockfile; echo $?` e `osv-scanner scan -r .` (o `scan` sem subcomando é o `scan source`)
Expected: `1` e "No package sources found".

- [ ] **Step 2: Ligar o travamento no `build.gradle.kts`**

- Antes de `plugins { … }`: `buildscript { configurations.classpath { resolutionStrategy.activateDependencyLocking() } }`, com um comentário de uma linha (os plugins rodam no build com acesso total; o scanner precisa vê-los).
- Depois de `allOpen { … }`: `dependencyLocking { lockAllConfigurations(); lockMode = LockMode.STRICT }` (no Kotlin DSL do Gradle 9 a propriedade é `lockMode.set(LockMode.STRICT)` ou a atribuição `=`; use a que compila), com um comentário curto: por que (scanner, D3), o modo estrito (configuração sem lock state falha, inclusive na imagem) e o comando de atualização `./gradlew dependencies --write-locks`, que regrava os dois arquivos.

- [ ] **Step 3: Gerar os lockfiles**

Run: `./gradlew dependencies --write-locks`
Expected: BUILD SUCCESSFUL; `gradle.lockfile` e `buildscript-gradle.lockfile` criados na raiz. Confira que o `gradle.lockfile` traz linhas das configurações `compileClasspath`, `runtimeClasspath`, `testCompileClasspath`, `testRuntimeClasspath` e das ferramentas (`detekt`, `ktlint…`, `kover…`) e a linha `empty=`; e que o `buildscript-gradle.lockfile` traz `org.jetbrains.kotlin:kotlin-gradle-plugin` e `org.springframework.boot:spring-boot-gradle-plugin`. Nenhuma versão muda: `git diff gradle/libs.versions.toml` vazio.

- [ ] **Step 4: Imagem com os lockfiles**

Acrescente `!gradle.lockfile` e `!buildscript-gradle.lockfile` ao `.dockerignore` (com o comentário do topo atualizado se precisar) e copie os dois no `COPY` do `Dockerfile` que já leva `gradlew settings.gradle.kts build.gradle.kts`; ajuste o comentário vizinho (as dependências travadas vêm dos lockfiles).

Run: `docker build -f docker/Dockerfile -t zeny-hotel-manager:e13 .`
Expected: imagem construída. Depois, `docker image rm zeny-hotel-manager:e13`.

- [ ] **Step 5: Build completo e contrato**

Run: `./gradlew build` e `./gradlew checkOpenApi`
Expected: BUILD SUCCESSFUL nos dois; `git status --porcelain api/` vazio. Se o modo estrito recusar alguma configuração resolvida só durante o build (mensagem "Locking strict mode: Configuration ':…' is locked but does not have lock state"), regenere com o comando que a resolve junto (`./gradlew dependencies <tarefa> --write-locks`), documente no comentário do `build.gradle.kts` o comando final e diga isso no relatório; só caia para o modo padrão se não houver comando que a trave, e diga por quê.

- [ ] **Step 6: Conferências do Review Focus numa cópia descartável (feitas pelo testador, depois do commit da Step 8)**

Numa cópia fora do repositório (`git archive HEAD | tar -x -C <scratch>/e13-copy/`), sem tocar no worktree real:
1. trocar `shedlock = "7.10.1"` por outra versão publicada (ex.: `7.10.0`) em `gradle/libs.versions.toml` → `./gradlew build` falha com mensagem de lock state;
2. restaurar; acrescentar `implementation("org.apache.commons:commons-lang3")` (gerenciada pelo BOM) → falha;
3. restaurar; trocar `kover = "0.9.11"` por outra versão publicada (ex.: `0.9.10`) → falha pelo classpath dos plugins;
4. restaurar; `./gradlew dependencies --write-locks` de novo → `diff` dos dois lockfiles contra os do worktree real sem diferença;
5. apagar os lockfiles da cópia → `docker build -f docker/Dockerfile .` na cópia falha (modo estrito).
Guarde as saídas relevantes (últimas linhas de cada falha) para o relatório. Apague a cópia no fim.

- [ ] **Step 7: Scanner**

Run: `osv-scanner scan -r .`
Expected: lista pacotes Maven do projeto lidos de `gradle.lockfile` (e de `buildscript-gradle.lockfile`); não responde "No package sources found". Exit 1 com vulnerabilidades é aceitável (corrigir está fora de escopo): ponha o resumo no relatório.

- [ ] **Step 8: Commit**

```bash
git add build.gradle.kts gradle.lockfile buildscript-gradle.lockfile .dockerignore docker/Dockerfile README.md
git commit -m "Liga o travamento de dependências do Gradle e versiona os lockfiles (M5 E13)" -m "Soul: desenvolvedor"
```
(Mantenha o trailer `Soul: desenvolvedor` na última linha de trailers, junto do `Co-Authored-By` se houver.)
