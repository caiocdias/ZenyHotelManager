# M6 E17 — Corpo mínimo nas escritas de restrição e mesclagem do `guests` — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `POST /api/v1/guests/{id}/restrict`, `POST …/lift-restriction` e `POST …/merge` só devolvem o perfil inteiro (`GuestResponse`) a quem também tem `guests.profiles.read`; sem ela, devolvem um corpo mínimo sem dado pessoal, e o contrato descreve os dois corpos sem mudança `ERR` no oasdiff.

**Architecture:** a decisão fica nos casos de uso (camada `application`, ARCHITECTURE §5 item 2 e §8.2), como o turno cego do caixa (`billing/application/CashierService.kt:435-436`, `grantOrNull(SHIFTS_REVIEW)`): `GuestService.restrict`, `GuestService.liftRestriction` e `GuestMergeService.merge` continuam exigindo só a própria permissão, gravam e auditam como hoje e, na mesma transação, perguntam `authorization.grantOrNull(GuestsPermissions.PROFILES_READ)`; devolvem um `GuestWriteResult` com o perfil (`GuestView`) só para quem lê perfis — sem a permissão, o `GuestView` nem é montado (nada é decifrado). O controller só traduz: com perfil, `GuestResponse`; sem, `GuestWriteSummaryResponse`. O contrato declara, só nessas três operações, `oneOf: [GuestResponse, GuestWriteSummaryResponse]` em `200 application/json`; os ramos são disjuntos porque o mínimo exige `restricted` (que o `GuestResponse` não tem) e o inteiro exige `fullName`.

**Decisões de contrato registradas (vão à revisão humana da etapa):**
- `oneOf` sem discriminador, o primeiro do contrato. Descartados: discriminador (exigiria propriedade nova no `GuestResponse`, que é a resposta de todas as operações de perfil); `anyOf` (os ramos não se sobrepõem, e `anyOf` diz menos ao gerador); outro status (`204`) ou outro media type (mudam o contrato de sucesso; outro media type dá `ERR`). Experimento do orquestrador com o oasdiff v1.33.0 do script: 3 `warning` `response-body-wrapped-in-one-of-original-preserved`, 0 `ERR`.
- Sem `additionalProperties: false`: seria a única restrição do tipo no contrato, contra a evolução aditiva da §7; a disjunção vem do `required`.
- Corpo mínimo `{id, restricted}`: `id` identifica o recurso; `restricted` é o resultado da operação (na mesclagem também, porque a restrição do absorvido passa ao sobrevivente, `Guest.kt:576-581`). `status` sai (num `200` dessas rotas é sempre `ACTIVE`: `requireActive()` e `requireMergeable`); `updatedAt` sai (sem consumidor; a versão vai no `ETag`). Acrescentar campo depois é aditivo.
- SDK do front (ogen 0.16.1, ADR 0007 do `ZenyHotelManager-DesktopFront`): o orquestrador gerou um SDK de prova no scratchpad com o contrato alterado (`--offline`); as três operações saem como `HttpResponseTyped<JsonElement>` (sem tipo), as demais não mudam. O front não usa as três operações hoje (busca vazia no repositório dele). Ruling do orquestrador: mantém o `oneOf` (as alternativas tipadas pedem propriedade nova no `GuestResponse` de todas as operações ou dão `ERR`); vai à entrevista do fim do lote como decisão de contrato público. A captura do brain registra o primeiro `oneOf` do contrato.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1 (Spring MVC), springdoc 3.1 (`io.swagger.v3.oas.annotations`), Jackson 3 (`tools.jackson`), Kotest matchers, `@IntegrationTest` com Testcontainers.

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E17 — Corpo mínimo nas escritas de restrição e mesclagem do `guests`", mais o handoff da M5 E15 (decisão E15 (h), `docs/roadmaps/roadmap-m5-recepcao-fnrh.md`) e a nota do brain `ZenyHotelManager - Módulo guests` (lista de restrição, mesclagem, permissões).

## Global Constraints

- Grafo de módulos da ARCHITECTURE §3.1: `guests` depende só de `shared` e `files`; nada de `iam` no `guests` — use `com.zeny.hotel.shared.Authorization`. Autorização na camada `application` (§8.2), nunca no `web`.
- Toda mudança de API regenera `api/openapi.yaml` com `./gradlew updateOpenApi` na mesma mudança (nunca à mão); `./gradlew checkOpenApi` e `.github/scripts/check-api-breaking.sh` passam, sem mudança `ERR` em `/api/v1`. A API não expõe `DELETE`.
- Código, API e nomes em inglês; KDoc, descrições do contrato, README e commits em pt-BR (D17).
- Fora de escopo: outras rotas do `guests`; mudar permissões ou modelos de role; migração.
- A trilha das três escritas não muda (ação, `user_id`, `before_state`, `after_state`, `changedFields`): o `audit.record` dos casos de uso não depende do retorno.
- Máquina de 14 GB com outra sessão ativa: um Gradle por vez, nunca `./gradlew --stop`; rode os testes da tarefa com `--tests` e o build completo só onde o plano pede.

## Review Focus

1. Corpo inteiro vazando por outro caminho: o `ETag` e o corpo de erro (`Problem`) não carregam dado do perfil; as recusas (`403`, `409`, `412`, `428`) continuam `application/problem+json`.
2. Ramos sobrepostos no `oneOf`: o JSON do mínimo tem exatamente `id` e `restricted`; o `GuestResponse` não ganha `restricted` nem perde `fullName` do `required`.
3. `lift-restriction` sem efeito (perfil fora da lista) devolve o mínimo com `restricted: false` e continua sem entrada de trilha (`audit.noChanges()`).
4. O `springdoc` mudar outras operações ou o próprio `GuestResponse` (`allOf` por herança, o `GET /guests/{id}` virando `oneOf`): o diff de `api/openapi.yaml` só toca as três respostas, o componente novo e descrições.
5. Usuário com `guests.profiles.read` e sem a permissão da escrita: `403 PERMISSION_DENIED`, sem corpo de perfil e sem escrita.

Os cinco têm teste na Task 1.

---

### Task 1: Corpo mínimo decidido nos casos de uso, resposta e contrato

**Estimativa:** 45 min · **Depende de:** nenhuma · **Risco:** alto (contrato público e exposição de dado pessoal)

Ruling do plano: são 4 arquivos de produção (acima de 3), porque a troca de retorno dos casos de uso obriga o controller e o DTO a mudarem juntos e a resposta nova obriga o contrato; qualquer corte deixa o código sem compilar ou o `checkOpenApi` vermelho. Os textos e o README ficam na Task 2.

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/guests/application/GuestService.kt` (`restrict` e `liftRestriction`, linhas 252–290; `GuestWriteResult` no mesmo arquivo)
- Modify: `src/main/kotlin/com/zeny/hotel/guests/application/GuestMergeService.kt` (`merge`, linhas 86–~135)
- Modify: `src/main/kotlin/com/zeny/hotel/guests/web/GuestsDtos.kt` (novo `GuestWriteSummaryResponse`)
- Modify: `src/main/kotlin/com/zeny/hotel/guests/web/GuestsController.kt` (`merge` linhas 122–149; `restrict` e `liftRestriction` linhas 269–290)
- Modify: `api/openapi.yaml` (gerado por `./gradlew updateOpenApi`)
- Modify: `src/test/kotlin/com/zeny/hotel/guests/GuestMergeApiTest.kt:392` (o cenário do Modulith usa o retorno de `merges.merge`, que deixa de ser `GuestView`)
- Test: `src/test/kotlin/com/zeny/hotel/guests/GuestWriteBodyApiTest.kt` (novo, `@IntegrationTest`; siga os apoios de `GuestsApiTest.kt` e `GuestMergeApiTest.kt`: `IamTestSupport.createUserWith(TestGrant(definition(…), ALL))`, `asTestUser`, `auditEntries`/`auditAfter`; o `/v3/api-docs` como em `FnrhSubmissionsApiAttackTest.kt:705-720`)

**Interfaces:**
- Consumes: `Authorization.grantOrNull(permission: PermissionCode): GrantedPermission?` (`shared/Authorization.kt:85`); `GuestsPermissions.PROFILES_READ`, `RESTRICTIONS_MANAGE`, `PROFILES_MERGE`; `GuestView`.
- Produces:
  - `data class GuestWriteResult(val id: GuestId, val restricted: Boolean, val version: Long, val profile: GuestView?)` em `GuestService.kt`, com KDoc em pt-BR: resultado das escritas de restrição e mesclagem; `profile` só para quem tem `guests.profiles.read` (decisão da M6 E17).
  - `GuestService.restrict(id: GuestId, reason: String): GuestWriteResult`, `GuestService.liftRestriction(id: GuestId): GuestWriteResult`, `GuestMergeService.merge(survivorId, mergedId, expectedVersion, takeFromMerged): GuestWriteResult` (no merge, o resultado é do sobrevivente). `version` é a mesma que hoje vai no `ETag` (a do `GuestView` que a rota devolve hoje); `profile` é montado (`guest.view()`) só quando `authorization.grantOrNull(GuestsPermissions.PROFILES_READ) != null`, perguntado dentro do caso de uso, depois do `require` da escrita.
  - `data class GuestWriteSummaryResponse(val id: UUID, val restricted: Boolean)` em `GuestsDtos.kt`, com `companion object { fun of(result: GuestWriteResult) }` e `@Schema(name = "GuestWriteSummaryResponse", description = …)` em pt-BR: corpo das escritas de restrição e mesclagem para quem não tem `guests.profiles.read`; sem nome, documento, contato, descrição de gênero, restrição alimentar nem o motivo da restrição; a versão vai no `ETag`.
  - No `GuestsController`: as três rotas devolvem `ResponseEntity<Any>` por um `private fun written(result: GuestWriteResult): ResponseEntity<Any>` com `ETag` = `ETags.of(result.version)` e corpo `result.profile?.let(GuestResponse::of) ?: GuestWriteSummaryResponse.of(result)`. O controller **não** recebe `Authorization`. O `ok(view)` das demais rotas fica.
  - Em cada uma das três: `@ApiResponse(responseCode = "200", description = …, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(oneOf = [GuestResponse::class, GuestWriteSummaryResponse::class]))])`. O media type continua `application/json` (outro media type é `ERR` no oasdiff). `GuestResponse` não implementa interface nem herda de nada. Na `description` de cada operação, acrescente: "Com `guests.profiles.read`, devolve o perfil inteiro (`GuestResponse`); sem ela, só `GuestWriteSummaryResponse` (o id e se está na lista de restrição); a versão vai no `ETag`."

- [ ] **Step 1: Escreva os testes de comportamento (vermelhos)** em `GuestWriteBodyApiTest`. O perfil de teste é criado por um usuário com todas as permissões do `guests`, com `gender = OTHER`, `genderDescription`, e-mail, telefone, um documento e restrições alimentares com o consentimento `DIETARY_RESTRICTIONS`; nome, e-mail, telefone, descrição de gênero, restrição alimentar e motivo levam um sufixo de letras único (procure só esses textos no corpo cru; não procure dígitos de documento, que podem aparecer em UUID).

```kotlin
private val summaryKeys = setOf("id", "restricted")

@Test
fun `restrict and lift without profiles read write the change and return only the minimal body`() {
    // usuário só com guests.restrictions.manage
    val restricted = call(POST, "/api/v1/guests/$id/restrict", asTestUser(restrictionsOnly), mapOf("reason" to reason))
    restricted.status shouldBe 200
    restricted.json.propertyNames().toSet() shouldBe summaryKeys
    restricted.json["id"].asString() shouldBe id
    restricted.json["restricted"].asBoolean() shouldBe true
    restricted.header(HttpHeaders.ETAG).shouldNotBeNull()
    for (secret in listOf(fullName, email, phone, genderDescription, dietary, reason)) restricted.raw shouldNotContain secret
    guestsApi.restrictionAlert(guestId).shouldNotBeNull()   // a escrita aconteceu

    val lifted = call(POST, "/api/v1/guests/$id/lift-restriction", asTestUser(restrictionsOnly))
    lifted.json.propertyNames().toSet() shouldBe summaryKeys
    lifted.json["restricted"].asBoolean() shouldBe false
    guestsApi.restrictionAlert(guestId).shouldBeNull()
}

@Test
fun `merge without profiles read merges and returns only the minimal body`() {
    // usuário só com guests.profiles.merge; If-Match do sobrevivente obtido por outro usuário
    // (essa role não lê perfis: ver "Notas para a revisão humana")
    // 200, chaves == summaryKeys, id == sobrevivente, restricted == a restrição herdada do absorvido (monte o absorvido
    // restrito), ETag novo != o enviado; corpo cru sem nenhum texto pessoal dos dois perfis;
    // GET do absorvido (usuário com profiles.read) -> status MERGED e mergedInto == sobrevivente
}

@Test
fun `with the write permission and profiles read the three routes return the full profile`() {
    // restrictions.manage + profiles.read: restrict e lift devolvem fullName, genderDescription, documents e restriction;
    // profiles.merge + profiles.read: merge devolve fullName e documents do sobrevivente
}

@Test
fun `the trail of the three writes is the same with either body`() {
    // restrict, lift e merge feitos por um usuário sem profiles.read num perfil e por um com profiles.read noutro:
    // mesma ação, user_id de quem escreveu, mesmo conjunto de chaves em before_state/after_state e os mesmos changedFields
}

@Test
fun `a lift on a profile outside the list returns the minimal body and writes no trail entry`() {
    // restricted == false, chaves == summaryKeys, nenhuma entrada guests.guest.lift-restriction
}

@Test
fun `profiles read without the write permission is refused with a problem and no profile`() {
    // só profiles.read: restrict, lift e merge -> 403 PERMISSION_DENIED, Content-Type application/problem+json,
    // corpo cru sem fullName; nada gravado (restrictionAlert nulo; o absorvido continua ACTIVE)
}

@Test
fun `refusals of the writes stay problems without profile data`() {
    // só restrictions.manage: restrict num perfil MERGED -> 409 GUEST_ALREADY_MERGED;
    // só profiles.merge: merge sem If-Match -> 428, com versão velha -> 412, o mesmo perfil -> 409 GUEST_MERGE_SAME_PROFILE;
    // todos application/problem+json e sem fullName no corpo cru
}
```

- [ ] **Step 2: Escreva o teste do contrato (vermelho)** no mesmo arquivo:

```kotlin
@Test
fun `the contract describes both bodies only on the three writes and keeps them disjoint`() {
    for (path in listOf("/api/v1/guests/{id}/restrict", "/api/v1/guests/{id}/lift-restriction", "/api/v1/guests/{id}/merge")) {
        val content = doc["paths"][path]["post"]["responses"]["200"]["content"]
        content.propertyNames().toSet() shouldBe setOf("application/json")
        content["application/json"]["schema"]["oneOf"].map { it["\$ref"].asString() } shouldContainExactlyInAnyOrder
            listOf("#/components/schemas/GuestResponse", "#/components/schemas/GuestWriteSummaryResponse")
    }
    val summary = doc["components"]["schemas"]["GuestWriteSummaryResponse"]
    summary["properties"].propertyNames().toSet() shouldBe setOf("id", "restricted")
    summary["required"].map { it.asString() } shouldContain "restricted"
    val full = doc["components"]["schemas"]["GuestResponse"]
    full["properties"].has("restricted") shouldBe false
    full["required"].map { it.asString() } shouldContain "fullName"
    full.has("allOf") shouldBe false
    doc["paths"]["/api/v1/guests/{id}"]["get"]["responses"]["200"]["content"]["application/json"]["schema"]["\$ref"]
        .asString() shouldBe "#/components/schemas/GuestResponse"
}
```

- [ ] **Step 3: Rode e confirme o vermelho**

Run: `./gradlew test --tests 'com.zeny.hotel.guests.GuestWriteBodyApiTest'`
Expected: FAIL — os testes sem `profiles.read` recebem o perfil inteiro e o do contrato não acha `oneOf`; os de recusa e de `403` podem já passar (guarda de regressão).

- [ ] **Step 4: Ajuste o teste que usa o retorno do caso de uso** — em `GuestMergeApiTest` (linha 392, dentro de `toArriveAndVerify { event, view -> … }`), troque `event.mergedAt shouldBe view.updatedAt` por `event.mergedAt shouldBe view.profile.shouldNotBeNull().updatedAt`. O ator é o admin, que tem `profiles.read`: a asserção continua a mesma e ainda prova que ele recebe o perfil. A chamada da linha 412 ignora o retorno e não muda.

- [ ] **Step 5: Implemente** `GuestWriteResult` e os três casos de uso, o `GuestWriteSummaryResponse`, o `written(result)` e as anotações (Interfaces acima).

- [ ] **Step 6: Regenere o contrato e confira o diff**

Run: `./gradlew updateOpenApi && git diff --stat api/openapi.yaml && git diff api/openapi.yaml`
Expected: só mudam as respostas `200` e as descrições de `restrictGuest`, `liftGuestRestriction` e `mergeGuests`, e entra o componente `GuestWriteSummaryResponse`. Qualquer outra mudança: corrija a anotação antes de seguir.

- [ ] **Step 7: Rode os testes do `guests`, o contrato e o lint**

Run: `./gradlew test --tests 'com.zeny.hotel.guests.*' checkOpenApi lint`
Expected: PASS, inclusive `GuestsApiTest` e `GuestGenderDescriptionApiTest` sem mudança e `GuestMergeApiTest` só com o ajuste do Step 4 (usam usuários com `profiles.read`).

- [ ] **Step 8: Confira o oasdiff**

Run: `.github/scripts/check-api-breaking.sh main`
Expected: no máximo os três `warning` `response-body-wrapped-in-one-of-original-preserved` e "Nenhuma mudança incompatível (nível ERR) em /api/v1." Cole a saída no relatório.

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/com/zeny/hotel/guests/application/GuestService.kt src/main/kotlin/com/zeny/hotel/guests/application/GuestMergeService.kt src/main/kotlin/com/zeny/hotel/guests/web/GuestsDtos.kt src/main/kotlin/com/zeny/hotel/guests/web/GuestsController.kt api/openapi.yaml src/test/kotlin/com/zeny/hotel/guests/GuestWriteBodyApiTest.kt src/test/kotlin/com/zeny/hotel/guests/GuestMergeApiTest.kt
git commit -m "feat(guests): corpo mínimo em restrict, lift-restriction e merge sem guests.profiles.read

Soul: desenvolvedor"
```

### Task 2: Textos que prometem o perfil inteiro nas escritas, README e build completo

**Estimativa:** 20 min · **Depende de:** Task 1 · **Risco:** alto (troca a promessa de privacidade do contrato sobre a descrição de gênero e altera uma asserção de teste de privacidade)

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/guests/web/GuestsDtos.kt` (descrição de `GuestResponse.genderDescription`, linhas 287–293: hoje "Vem em toda resposta de perfil, inclusive nas das escritas (criação, alteração, restrição e mesclagem)")
- Modify: `src/main/kotlin/com/zeny/hotel/guests/application/GuestsPorts.kt:317` (KDoc de `GuestView.genderDescription`: "presente em toda resposta de perfil, até nas escritas")
- Modify: `src/main/kotlin/com/zeny/hotel/guests/web/GuestsController.kt` (KDoc da classe: uma frase sobre o corpo das escritas de restrição e mesclagem)
- Modify: `api/openapi.yaml` (gerado)
- Modify: `README.md` (seção "API", item "Hóspedes e empresas", linha 222)
- Modify: `src/test/kotlin/com/zeny/hotel/guests/GuestGenderDescriptionApiTest.kt:616`

**Interfaces:**
- Consumes: `GuestWriteSummaryResponse` e o comportamento da Task 1.
- Produces: nada novo.

- [ ] **Step 1: Ajuste o teste do texto (vermelho)** — em `GuestGenderDescriptionApiTest`, teste "the contract exposes the description in profile responses only…", troque `field shouldContain "inclusive nas das escritas"` por asserções sobre termos sem espaço, que não se partem na quebra de linha do YAML gerado: `field shouldContain "GuestWriteSummaryResponse"` e `field shouldContain "guests.profiles.read"`. Para a negativa, normalize antes o bloco, tirando a continuação de linha do gerador (`\` no fim da linha, a quebra, a indentação e o `\ ` opcional da linha seguinte; ver `api/openapi.yaml:19498-19501`), e afirme `normalized shouldNotContain "inclusive nas das escritas"`; confira que a negativa fica vermelha com o texto antigo. Não é enfraquecer o teste: a spec da E17 manda trocar a promessa, e a asserção nova fixa a regra nova.

Run: `./gradlew test --tests 'com.zeny.hotel.guests.GuestGenderDescriptionApiTest'` → FAIL no teste do contrato.

- [ ] **Step 2: Troque os textos** — `genderDescription` (DTO): "Descrição do gênero `OTHER` (FNRH): dado pessoal, gravado cifrado. Vem em toda resposta de perfil; nas escritas de restrição e mesclagem, só com `guests.profiles.read` (sem ela, a resposta é `GuestWriteSummaryResponse`). Nula com qualquer outro gênero."; KDoc de `GuestView.genderDescription` no mesmo sentido; KDoc da classe `GuestsController`. Regenere: `./gradlew updateOpenApi` (o diff só toca descrições).

- [ ] **Step 3: README** — no item "Hóspedes e empresas" (linha 222), troque "devolvida em toda resposta de perfil, inclusive nas das escritas" por: devolvida em toda resposta de perfil; `restrict`, `lift-restriction` e `merge` devolvem o perfil inteiro só a quem tem `guests.profiles.read` e, sem ela, um corpo mínimo (`id` e `restricted`, com a versão no `ETag`). Registre que a mudança de forma só alcança roles customizadas sem `guests.profiles.read` (os modelos padrão não mudam).

- [ ] **Step 4: Build completo e oasdiff**

Run: `./gradlew build` e depois `.github/scripts/check-api-breaking.sh main`
Expected: `BUILD SUCCESSFUL`; oasdiff só com os três `warning` da Task 1 e "Nenhuma mudança incompatível (nível ERR) em /api/v1." Cole as linhas finais das duas saídas no relatório.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/zeny/hotel/guests/web/GuestsDtos.kt src/main/kotlin/com/zeny/hotel/guests/application/GuestsPorts.kt src/main/kotlin/com/zeny/hotel/guests/web/GuestsController.kt api/openapi.yaml README.md src/test/kotlin/com/zeny/hotel/guests/GuestGenderDescriptionApiTest.kt
git commit -m "docs(guests): textos e README do corpo mínimo das escritas de restrição e mesclagem

Soul: desenvolvedor"
```

## Notas para a revisão humana

- Uma role com `guests.profiles.merge` e sem `guests.profiles.read` não obtém o `ETag` do sobrevivente pela API (`GET /guests/{id}` e `duplicate-candidates` exigem `profiles.read`): o cenário quase não existe na prática; o teste busca o `If-Match` com outro usuário.
- Primeiro `oneOf` do contrato; o resultado da geração do SDK do front com o ogen 0.16.1 entra no handoff.
