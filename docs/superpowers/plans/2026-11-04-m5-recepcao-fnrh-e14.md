# M5 E14 — Correção das vulnerabilidades High/Critical do scanner — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** levar às versões corrigidas as dependências com vulnerabilidade High ou Critical apontadas pelo `osv-scanner` (Tomcat, Jackson 3 e 2 no runtime; freemarker, Jackson 3 no classpath dos plugins), regravar os lockfiles e pôr na CI a conferência lockfile × catálogo.

**Architecture:** o caminho preferido da etapa resolve a maior parte: o patch do Spring Boot 4.1.1 → 4.1.2 (plugin e BOM, mesma `version.ref` no catálogo) traz Tomcat 11.0.27 e Jackson 3.1.7 no runtime e Jackson 3.1.7 no plugin. O que o patch não alcança vira restrição de versão com o motivo no comentário: o Jackson 2 (vem do springdoc 3.1.1, que não tem patch; o BOM do Boot fica em 2.21.x) entra como plataforma `jackson-bom` 2.x no catálogo; o freemarker (vem do kover 0.9.11, a última versão publicada, por `kover-features-jvm` → `intellij-coverage-reporter`) entra como restrição no `buildscript { dependencies { constraints } }`. A CI ganha um passo que regrava os lockfiles e falha se algum mudou.

**Tech Stack:** Gradle 9.8 (wrapper, dependency locking estrito), version catalog, GitHub Actions, `osv-scanner` 2.7.0.

**Spec:** `docs/roadmaps/roadmap-m5-recepcao-fnrh.md`, seção "E14 — Correção das vulnerabilidades High/Critical apontadas pelo scanner", com o handoff da E13 (mesma página) e a captura `00 Inbox/2026-10-28 - ZenyHotelManager - atualização m5-recepcao-fnrh E13 - 7a6ce25a.md` do Second Brain.

## Levantamento (2026-11-04, pelo orquestrador)

Scanner numa cópia limpa de `15bbcdb` (`git archive`): "Scanned …/gradle.lockfile file and found 415 packages", "Scanned …/buildscript-gradle.lockfile file and found 74 packages", "Total 11 packages affected by 31 known vulnerabilities (5 Critical, 15 High, 8 Medium, 3 Low, 0 Unknown)". Mesma lista da E13.

| Pacote | Versão | Onde | Severidade | Corrigida em | Caminho |
|---|---|---|---|---|---|
| `org.apache.tomcat.embed:tomcat-embed-core` | 11.0.24 | runtime | Critical 9,8 / 9,1 | 11.0.25 | Boot 4.1.2 (`tomcat.version` 11.0.27) |
| `tools.jackson.core:jackson-core`/`-databind` | 3.1.5 | runtime e plugins | High 7,5 | 3.1.7 | Boot 4.1.2 (BOM `jackson-bom.version` 3.1.7; `spring-boot-buildpack-platform` 4.1.2 exige `jackson-databind` 3.1.7) |
| `com.fasterxml.jackson.core:jackson-core`/`-databind` | 2.22.1 | runtime (springdoc) | High 7,5 | 2.22.3 | restrição: plataforma `com.fasterxml.jackson:jackson-bom` 2.22.4 (último patch da linha 2.22) |
| `org.freemarker:freemarker` | 2.3.32 | plugins (kover 0.9.11) | Critical 9,1 | 2.3.35 | restrição no classpath dos plugins, 2.3.35 |
| `io.opentelemetry:opentelemetry-api` | 1.62.0 | runtime | Medium | 1.67.0 | fora (o Boot 4.1.2 mantém 1.62.0; não está no caminho) |
| `org.apache.commons:commons-lang3` | 3.16.0 | plugins (Boot plugin) | Medium | 3.18.0 | só se o Boot 4.1.2 arrastar; senão fica registrado |
| `ch.qos.logback:logback-core` | 1.3.16 | `ktlint` | Low | 1.5.x | fora; fica registrado |

O springdoc só publica 3.2.0 depois da 3.1.1 (subida de minor, fora de escopo). O kover não tem versão posterior à 0.9.11.

## Global Constraints

- Versões só em `gradle/libs.versions.toml`; nenhuma versão literal no `build.gradle.kts` (nota "Ambiente, banco, chaves e build" do brain; critério 2 da E14).
- Lockfiles regravados pelo Gradle no **mesmo commit** da mudança de versão: `./gradlew dependencies --write-locks` (os três: `gradle.lockfile`, `buildscript-gradle.lockfile`, `settings-gradle.lockfile`). Nunca editados à mão.
- BOMs como `platform(...)` nativo do Gradle, sem o plugin `io.spring.dependency-management`; propriedades do BOM do Boot (`tomcat.version`) não valem aqui.
- Toda restrição de versão leva comentário com o motivo, o advisory (GHSA) e quando sai (quando o BOM do Boot, o springdoc ou o kover alcançar a versão).
- Fora de escopo: subir versões sem High/Critical (salvo as que o caminho arrastar), o `osv-scanner` no CI, `gradle/verification-metadata.xml`, trocar de biblioteca, o Spring Modulith 2.1.2.
- O scanner não vê nada dentro de `.claude/worktrees/` (o `.gitignore` do checkout principal ignora a pasta): rode-o numa cópia (`git archive HEAD` extraído no scratchpad) ou com `--no-ignore`. Use `osv-scanner -r <dir>` (equivale a `osv-scanner scan source -r <dir>`).
- Documentação, comentários e commits em pt-BR; commits com o trailer `Soul: <soul>`.

## Review Focus

1. **Lockfile defasado em alguma configuração** (detekt, ktlint, kover, compilador Kotlin, `classpath` dos plugins): a regravação é completa e uma segunda execução de `--write-locks` não muda nada.
2. **Passo da CI com falso positivo ou falso negativo:** passa com a árvore em dia (duas vezes seguidas), falha com uma versão rebaixada no catálogo e falha também se surgir um lockfile **novo não rastreado** (o `git diff` sozinho não vê arquivo novo).
3. **Plataforma do Jackson 2 desalinhando módulos** (`jackson-annotations` sem patch desde a 2.20, `jackson-module-kotlin` 2, `jackson-datatype-*`): todos os `com.fasterxml.jackson.*` do `productionRuntimeClasspath` na mesma linha, e o contrato gerado pelo springdoc (`checkOpenApi`) idêntico.
4. **Mudança de comportamento do patch do Boot/Tomcat** (parsing de cabeçalhos, multipart, SSE): suíte inteira, `docker build` e oasdiff sem mudança.
5. **Restrição do freemarker sem efeito** (constraint declarada mas o lockfile do buildscript ainda com 2.3.32): o `buildscript-gradle.lockfile` mostra `org.freemarker:freemarker:2.3.35=classpath`.

---

### Task 1: Versões corrigidas e lockfiles regravados

**Files:**
- Modify: `gradle/libs.versions.toml` (versão `spring-boot`; novas `jackson2` e `freemarker` em `[versions]` e as libs `jackson2-bom` e `freemarker` em `[libraries]`, cada uma com comentário de motivo)
- Modify: `build.gradle.kts` (bloco `buildscript` com a restrição do freemarker; `dependencies` com a plataforma do Jackson 2)
- Modify: `gradle.lockfile`, `buildscript-gradle.lockfile`, `settings-gradle.lockfile` (regravados)
- Modify: `README.md` só se algum texto ficar falso (não deve).

**Interfaces:**
- Consumes: nada de tarefa anterior.
- Produces: catálogo e lockfiles em dia, que a Task 2 usa para provar o passo da CI.

- [ ] **Step 1: Registrar o estado vermelho**

Numa cópia limpa do HEAD (`git archive HEAD` extraído num diretório novo do scratchpad), rode `osv-scanner -r <cópia>`.
Esperado: exit 1 e as linhas de `tomcat-embed-core 11.0.24`, `tools.jackson.core 3.1.5`, `com.fasterxml.jackson.core 2.22.1` e `org.freemarker 2.3.32` com CVSS ≥ 7,0. Guarde a saída para o relatório.

- [ ] **Step 2: Subir o Spring Boot para 4.1.2**

`spring-boot = "4.1.2"` no catálogo (plugin e BOM usam a mesma `version.ref`).

- [ ] **Step 3: Plataforma do Jackson 2**

Catálogo: `jackson2 = "2.22.4"` e `jackson2-bom = { module = "com.fasterxml.jackson:jackson-bom", version.ref = "jackson2" }`, com o comentário: o springdoc 3.1.1 puxa o Jackson 2 na 2.22.1 (GHSA-7hhh-6rmp-j9qf, GHSA-p6pp-m3f8-5c89, GHSA-cxp5-3px4-pw24, GHSA-wv8q-qhhj-9h54, GHSA-q4xh-88c3-wmh7) e o BOM do Boot 4.1.2 fica na 2.21.x; sai quando o springdoc ou o BOM do Boot trouxer ≥ 2.22.3. No `build.gradle.kts`, `implementation(platform(libs.jackson2.bom))` junto dos outros BOMs, com uma linha de comentário apontando o catálogo.

- [ ] **Step 4: Restrição do freemarker no classpath dos plugins**

Catálogo: `freemarker = "2.3.35"` e `freemarker = { module = "org.freemarker:freemarker", version.ref = "freemarker" }`, com o comentário: chega pelo kover 0.9.11 (`kover-features-jvm` → `intellij-coverage-reporter`), Critical GHSA-27j2-h3m2-8237; sai quando o kover trouxer ≥ 2.3.35. No `build.gradle.kts`, dentro do `buildscript { }` existente: `dependencies { constraints { classpath(<lib do catálogo>) { because("…") } } }`. A versão vem do catálogo: use o acessor `libs` se ele compilar dentro do `buildscript`; se não compilar, leia pelo `VersionCatalogsExtension` (`findLibrary("freemarker")`). Nunca um literal.

- [ ] **Step 5: Regravar os lockfiles**

Run, nesta ordem: `./gradlew dependencies --write-locks`; `git add gradle.lockfile buildscript-gradle.lockfile settings-gradle.lockfile`; `./gradlew dependencies --write-locks` de novo; `git diff --exit-code -- '*.lockfile'`; `git status --porcelain -- '*.lockfile'`.
Esperado: a primeira execução muda os lockfiles; depois da segunda, o `git diff --exit-code` (árvore × índice) sai com exit 0 e o `git status --porcelain` não tem linha `?? ` nem ` M` (só as `M  ` do stage). Confira no diff: `tomcat-embed-core:11.0.27`, `tools.jackson.core:jackson-core:3.1.7` e `jackson-databind:3.1.7` (nos dois lockfiles), `com.fasterxml.jackson.core:jackson-core`/`jackson-databind` ≥ 2.22.3 e `org.freemarker:freemarker:2.3.35=classpath`. Nenhuma linha removida sem explicação (descreva no relatório o que o Boot 4.1.2 arrastou).

- [ ] **Step 6: Build completo**

Run: `./gradlew build`
Esperado: `BUILD SUCCESSFUL`, 0 falhas, `checkOpenApi` verde e `api/openapi.yaml` sem mudança (`git status` limpo depois do build).

- [ ] **Step 6b: Relatório HTML do kover (único consumidor do freemarker)**

Run: `./gradlew koverHtmlReport`
Esperado: `BUILD SUCCESSFUL` e `build/reports/kover/html/index.html` gerado. O `build` roda só o `koverVerify`, que não prova o reporter; este comando oficial é o que usa o freemarker restringido. Anexe ao relatório também o trecho de `./gradlew buildEnvironment` com o `org.freemarker:freemarker` e a cadeia acima dele (prova de que o kover é o único caminho), já com a 2.3.35.

- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts gradle.lockfile buildscript-gradle.lockfile settings-gradle.lockfile
git commit -m "Sobe o Spring Boot para 4.1.2 e restringe Jackson 2 e freemarker para fechar as High/Critical (M5 E14)" -m "Soul: desenvolvedor"
```

(o trailer `Soul: desenvolvedor` precisa sair como trailer: linha final do corpo, depois de uma linha em branco.)

- [ ] **Step 8: Verde no scanner e na imagem**

Numa cópia nova do HEAD: `osv-scanner -r <cópia>`.
Esperado: nenhuma linha com CVSS ≥ 7,0; o que sobrar (esperados: `opentelemetry-api` Medium, `commons-lang3` Medium se não foi arrastado, `logback-core` Low) vai no relatório. Depois, `docker build -f docker/Dockerfile .` → exit 0. E `.github/scripts/check-api-breaking.sh 15bbcdb` → sem mudança `ERR`.

### Task 2: Passo da CI que confere lockfiles × catálogo

**Files:**
- Modify: `.github/workflows/ci.yml` (job `build`)
- Modify: `README.md` (uma frase no parágrafo dos lockfiles, linha 34: a CI regrava e falha se mudar)
- Modify: `build.gradle.kts` (comentário do bloco `dependencyLocking` do projeto: a versão menor no catálogo agora é pega pela CI)
- Modify: `docs/ARCHITECTURE.md` (§17, a frase da CI)

**Interfaces:**
- Consumes: os lockfiles em dia da Task 1.
- Produces: o passo `Lockfiles em dia com o catálogo` no job `build`.

- [ ] **Step 1: Provar o passo vermelho num clone descartável**

Num `git clone` do worktree no scratchpad: rebaixe `shedlock = "7.10.0"` no catálogo, sem regravar; rode `./gradlew dependencies --write-locks` e `git diff --exit-code -- '*.lockfile'`.
Esperado: o `git diff` sai com exit 1 mostrando `shedlock` 7.10.1 → 7.10.0. Apague o clone depois.

- [ ] **Step 2: Acrescentar o passo**

No job `build`, logo depois de "Gradle com cache" e antes de "Build completo", um passo `Lockfiles em dia com o catálogo` que roda `./gradlew dependencies --write-locks` e depois falha se algum lockfile mudou **ou** apareceu sem rastreio. O `run` do Actions usa `bash -e`, então a conferência fica num `if` para a mensagem sair antes da falha:

```bash
./gradlew dependencies --write-locks
if ! git diff --exit-code -- '*.lockfile' || [ -n "$(git status --porcelain -- '*.lockfile')" ]; then
  echo "::error::Lockfiles fora de dia com o catálogo. Rode ./gradlew dependencies --write-locks e commite os lockfiles."
  exit 1
fi
```

Comentário acima do passo: pega lockfile esquecido e a versão menor no catálogo, que o modo estrito ignora (M5 E13, ressalva do critério 3). Ações já fixadas por SHA no arquivo; nenhuma ação nova.

- [ ] **Step 3: Provar o passo verde e vermelho**

Num clone novo do worktree (com o commit da Task 1 e o passo novo), rode o bloco `run` do passo exatamente como está no YAML, com `bash -eo pipefail` (como o runner):
- na árvore como está → exit 0, duas vezes seguidas;
- com `shedlock = "7.10.0"` no catálogo → falha;
- com um lockfile novo não rastreado (por exemplo, `touch extra-gradle.lockfile`) → falha.
Guarde as três saídas para o relatório. Apague o clone.

- [ ] **Step 4: README e comentário**

README linha 34: acrescentar que a CI regrava os lockfiles e falha se algum mudar. `build.gradle.kts`: no comentário do `dependencyLocking` do projeto, a mesma informação numa linha. Cabeçalho do `ci.yml` (linhas 1–7): o job `build` também confere os lockfiles. `docs/ARCHITECTURE.md` §17 (linha ~628, "A CI … roda build, testes, …"): acrescentar "conferência dos lockfiles". Nenhum desses textos diz que o passo pega restrição de versão esquecida: uma restrição é piso de versão e não faz o lockfile divergir quando o BOM a ultrapassa; só o comentário da restrição avisa.

- [ ] **Step 5: Lint e commit**

Run: `./gradlew lint` → `BUILD SUCCESSFUL`.

```bash
git add .github/workflows/ci.yml README.md build.gradle.kts docs/ARCHITECTURE.md
git commit -m "Confere na CI que os lockfiles estão em dia com o catálogo (M5 E14)" -m "Soul: desenvolvedor"
```

## Notas para o fechamento (orquestrador)

- Resíduos do scanner depois da Task 1 (qualquer High/Critical sobrevivente e o `opentelemetry-api` Medium, com correção publicada mas fora do caminho) vão ao handoff da E14 como risco, em "Decisões para o usuário" (critério 1).
- Alternativas descartadas, para o handoff e a captura do brain (nota "Ambiente, banco, chaves e build", com o inventário das restrições e a condição de saída de cada uma): Jackson 2 por restrição por módulo (descartada: a plataforma alinha todos os módulos da linha) ou pelo springdoc 3.2.0 (subida de minor, fora de escopo); freemarker sem alternativa de plugin (kover 0.9.11 é a última).
- O verde do passo novo no runner só existe depois do push (decisão do usuário): o handoff registra o run da CI quando houver integração. A captura complementa também a nota "Docker, observabilidade e CI".

## Revisão de arquitetura

- **Veredito:** aprovado com notas.
- **Rodadas:** 1 (revisão inicial bloqueada por dois Importantes; re-revisão aprovada com notas).
- **Modelo:** `revisor-arquitetura` no modelo mais capaz disponível (opus).
- **Importantes resolvidos:** I1 (`koverHtmlReport` como prova do freemarker, Task 1 Step 6b); I2 (prova de idempotência do `--write-locks` refeita, Task 1 Step 5).
- **Notas Menores:** M1 (passo da CI em `if`, rodado com `bash -eo pipefail`), M3 (cabeçalho do `ci.yml` e ARCHITECTURE §17), M5 (trecho do `buildEnvironment`), M7 (o passo não pega restrição esquecida; só o comentário avisa) aplicadas no plano; M2 (run da CI só depois do push), M4 (alternativas descartadas) e M6 (resíduos do scanner em "Decisões para o usuário") nas "Notas para o fechamento"; N1 (cerca de código do Task 2 Step 2) corrigida.
- **Não verificado pelo revisor (sem Bash):** versões no Maven Central (levantadas pelo orquestrador), compilação do acessor do catálogo dentro do `buildscript`, comportamento do passo no runner.
