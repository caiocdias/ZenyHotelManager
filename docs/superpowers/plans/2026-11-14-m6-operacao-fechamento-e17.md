# M6 E17 — Corpo mínimo nas escritas de restrição e mesclagem do `guests` — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `POST /api/v1/guests/{id}/restrict`, `POST …/lift-restriction` e `POST …/merge` só devolvem o perfil inteiro (`GuestResponse`) a quem também tem `guests.profiles.read`; sem ela, devolvem um corpo mínimo sem dado pessoal, e o contrato descreve os dois corpos sem mudança `ERR` no oasdiff.

**Architecture:** a escrita e a trilha não mudam: os casos de uso (`GuestService.restrict`, `GuestService.liftRestriction`, `GuestMergeService.merge`) continuam exigindo só a própria permissão e devolvendo `GuestView`. A escolha do corpo fica no `GuestsController`, que recebe `Authorization` do kernel (`shared`, sem `iam`) e pergunta `grantOrNull(GuestsPermissions.PROFILES_READ)` depois da escrita. O contrato passa a declarar, só nessas três operações, `oneOf: [GuestResponse, GuestWriteSummaryResponse]` em `200 application/json`; o esquema mínimo tem `additionalProperties: false`, o que torna os dois ramos disjuntos (o corpo inteiro não casa com o mínimo, e o mínimo não tem `fullName`, obrigatório no inteiro).

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1 (Spring MVC), springdoc 3.1 (`io.swagger.v3.oas.annotations`), Jackson 3 (`tools.jackson`), Kotest matchers, `@IntegrationTest` com Testcontainers.

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E17 — Corpo mínimo nas escritas de restrição e mesclagem do `guests`", mais o handoff da M5 E15 (decisão E15 (h), `docs/roadmaps/roadmap-m5-recepcao-fnrh.md`) e a nota do brain `ZenyHotelManager - Módulo guests` (lista de restrição, mesclagem, permissões).

## Global Constraints

- Grafo de módulos da ARCHITECTURE §3.1: `guests` depende só de `shared` e `files`; nada de `iam` no `guests` — use `com.zeny.hotel.shared.Authorization`.
- Toda mudança de API regenera `api/openapi.yaml` com `./gradlew updateOpenApi` na mesma mudança (nunca à mão); `./gradlew checkOpenApi` e `.github/scripts/check-api-breaking.sh` passam, sem mudança `ERR` em `/api/v1`. A API não expõe `DELETE`.
- Código, API e nomes em inglês; KDoc, descrições do contrato, README e commits em pt-BR (D17).
- Fora de escopo: outras rotas do `guests`; mudar permissões ou modelos de role; mudar os casos de uso, a trilha ou a migração. Sem migração.
- A trilha das três escritas não muda (ação, `user_id`, `before_state`, `after_state`, `changedFields`).
- Máquina de 14 GB com outra sessão ativa: um Gradle por vez, nunca `./gradlew --stop`; rode os testes da tarefa com `--tests` e o build completo uma vez no fim da tarefa.

## Review Focus

1. Corpo inteiro vazando por outro caminho: o `ETag` e o corpo de erro (`Problem`) não podem carregar dado do perfil; uma recusa (`403`, `409`, `412`, `428`) continua sendo `application/problem+json`, igual a hoje.
2. Ramos sobrepostos no `oneOf`: um cliente que valida o corpo contra o contrato rejeita um payload que casa com os dois ramos; o mínimo precisa sair com `additionalProperties: false` e o JSON serializado precisa ter exatamente as quatro chaves (nenhuma nula omitida ou extra).
3. `lift-restriction` sem efeito (perfil fora da lista) devolve o corpo mínimo com `restricted: false` e continua sem entrada de trilha (`audit.noChanges()`).
4. O `springdoc` mudar outras operações ou o próprio `GuestResponse` (por exemplo, `allOf` por herança, ou o `GET /guests/{id}` passando a `oneOf`): o diff de `api/openapi.yaml` só pode tocar as três respostas, o componente novo e descrições.
5. Usuário com `guests.profiles.read` e sem a permissão da escrita: `403 PERMISSION_DENIED`, sem corpo de perfil e sem escrita.

Cada item tem teste na Task 1 (passos 1 e 2).

---

### Task 1: Corpo mínimo nas três escritas, contrato e documentação

**Estimativa:** 45 min · **Depende de:** nenhuma · **Risco:** alto (contrato público e exposição de dado pessoal)

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/guests/web/GuestsDtos.kt` (novo `GuestWriteSummaryResponse`; descrição de `GuestResponse.genderDescription`, hoje linhas 287–293, que promete a descrição "nas das escritas (criação, alteração, restrição e mesclagem)")
- Modify: `src/main/kotlin/com/zeny/hotel/guests/web/GuestsController.kt` (construtor; `merge` linhas 122–149; `restrict` e `liftRestriction` linhas 269–290; KDoc da classe)
- Modify: `api/openapi.yaml` (gerado por `./gradlew updateOpenApi`)
- Modify: `README.md` (seção "API", item "Hóspedes e empresas", linha 222)
- Test: `src/test/kotlin/com/zeny/hotel/guests/GuestWriteBodyApiTest.kt` (novo, `@IntegrationTest`; siga o estilo e os apoios de `GuestsApiTest.kt` e `GuestMergeApiTest.kt`: `IamTestSupport.createUserWith(TestGrant(definition(…), ALL))`, `asTestUser`, as consultas de trilha `auditEntries`/`auditAfter`)

**Interfaces:**
- Consumes: `Authorization.grantOrNull(permission: PermissionCode): GrantedPermission?` (`shared/Authorization.kt`); `GuestsPermissions.PROFILES_READ`, `RESTRICTIONS_MANAGE`, `PROFILES_MERGE`; `GuestView` (`guests/application/GuestsPorts.kt`: `id`, `status`, `restriction`, `updatedAt`, `version`).
- Produces:
  - `data class GuestWriteSummaryResponse(val id: UUID, val status: GuestStatus, val restricted: Boolean, val updatedAt: Instant)` com `companion object { fun of(view: GuestView): GuestWriteSummaryResponse }` (`restricted = view.restriction != null`) e `@Schema(name = "GuestWriteSummaryResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE, description = …)` (descrição em pt-BR: corpo das escritas de restrição e mesclagem para quem não tem `guests.profiles.read`; sem nome, documento, contato, descrição de gênero, restrição alimentar nem o motivo da restrição; leia o perfil com `GET /api/v1/guests/{id}` se tiver a permissão).
  - No `GuestsController`: as três rotas passam a devolver `ResponseEntity<Any>`, por um `private fun written(view: GuestView): ResponseEntity<Any>` que mantém o `ETag` (`ETags.of(view.version)`) e escolhe `GuestResponse.of(view)` com `authorization.grantOrNull(GuestsPermissions.PROFILES_READ) != null`, senão `GuestWriteSummaryResponse.of(view)`. O `ok(view)` das demais rotas fica como está.
  - Em cada uma das três: `@ApiResponse(responseCode = "200", description = …, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(oneOf = [GuestResponse::class, GuestWriteSummaryResponse::class]))])`. O media type tem de continuar `application/json` (trocar ou acrescentar media type é `ERR` no oasdiff). Não faça `GuestResponse` implementar interface nem herdar de nada.

- [ ] **Step 1: Escreva os testes de comportamento (vermelhos)** em `GuestWriteBodyApiTest`. O perfil de teste é criado por um usuário com todas as permissões do `guests`, com `gender = OTHER`, `genderDescription`, e-mail, telefone, um documento e restrições alimentares com o consentimento `DIETARY_RESTRICTIONS`; guarde os valores (com sufixo único) para procurar no corpo cru.

```kotlin
private val summaryKeys = setOf("id", "status", "restricted", "updatedAt")

@Test
fun `restrict and lift without profiles read write the change and return only the minimal body`() {
    // usuário só com guests.restrictions.manage
    val restricted = call(POST, "/api/v1/guests/$id/restrict", asTestUser(restrictionsOnly), mapOf("reason" to reason))
    restricted.status shouldBe 200
    restricted.json.propertyNames().toSet() shouldBe summaryKeys
    restricted.json["id"].asString() shouldBe id
    restricted.json["status"].asString() shouldBe "ACTIVE"
    restricted.json["restricted"].asBoolean() shouldBe true
    restricted.header(HttpHeaders.ETAG).shouldNotBeNull()
    for (secret in listOf(fullName, email, phone, genderDescription, dietary, documentSuffix, reason)) {
        restricted.raw shouldNotContain secret
    }
    guestsApi.restrictionAlert(guestId).shouldNotBeNull()   // a escrita aconteceu

    val lifted = call(POST, "/api/v1/guests/$id/lift-restriction", asTestUser(restrictionsOnly))
    lifted.json.propertyNames().toSet() shouldBe summaryKeys
    lifted.json["restricted"].asBoolean() shouldBe false
    guestsApi.restrictionAlert(guestId).shouldBeNull()
}

@Test
fun `merge without profiles read merges and returns only the minimal body`() {
    // usuário só com guests.profiles.merge; If-Match do sobrevivente
    // 200, chaves == summaryKeys, id == sobrevivente, status ACTIVE, ETag novo != o enviado,
    // corpo cru sem nome/e-mail/telefone/descrição de gênero/restrição alimentar de nenhum dos dois perfis;
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

- [ ] **Step 2: Escreva o teste do contrato (vermelho)** no mesmo arquivo, lendo `/v3/api-docs` como `FnrhSubmissionsApiAttackTest` (linhas 705–720):

```kotlin
@Test
fun `the contract describes both bodies only on the three writes`() {
    for ((path, op) in listOf("/api/v1/guests/{id}/restrict" to "post", "/api/v1/guests/{id}/lift-restriction" to "post", "/api/v1/guests/{id}/merge" to "post")) {
        val schema = doc["paths"][path][op]["responses"]["200"]["content"]["application/json"]["schema"]
        schema["oneOf"].map { it["\$ref"].asString() } shouldContainExactlyInAnyOrder
            listOf("#/components/schemas/GuestResponse", "#/components/schemas/GuestWriteSummaryResponse")
        doc["paths"][path][op]["responses"]["200"]["content"].propertyNames().toSet() shouldBe setOf("application/json")
    }
    val summary = doc["components"]["schemas"]["GuestWriteSummaryResponse"]
    summary["additionalProperties"].asBoolean() shouldBe false
    summary["required"].map { it.asString() }.toSet() shouldBe setOf("id", "status", "restricted", "updatedAt")
    summary["properties"].propertyNames().toSet() shouldBe setOf("id", "status", "restricted", "updatedAt")
    doc["paths"]["/api/v1/guests/{id}"]["get"]["responses"]["200"]["content"]["application/json"]["schema"]["\$ref"]
        .asString() shouldBe "#/components/schemas/GuestResponse"
    doc["components"]["schemas"]["GuestResponse"].has("allOf") shouldBe false
}
```

- [ ] **Step 3: Rode e confirme o vermelho**

Run: `./gradlew test --tests 'com.zeny.hotel.guests.GuestWriteBodyApiTest'`
Expected: FAIL — os testes sem `profiles.read` recebem o perfil inteiro (chaves além das quatro, `fullName` no corpo) e o do contrato não acha `oneOf`; os de recusa e de `403` podem já passar (são guarda de regressão).

- [ ] **Step 4: Implemente** o `GuestWriteSummaryResponse` em `GuestsDtos.kt` e o `written(view)` com as anotações `@ApiResponse` nas três rotas de `GuestsController.kt` (Interfaces acima). Atualize a `description` das três operações (acrescente: "Com `guests.profiles.read`, devolve o perfil inteiro; sem ela, só `GuestWriteSummaryResponse` (id, situação, se está na lista de restrição e a última alteração).") e o KDoc da classe; troque a descrição de `GuestResponse.genderDescription` para dizer que vem em toda resposta de perfil, e que nas escritas de restrição e mesclagem só vem com `guests.profiles.read`.

- [ ] **Step 5: Regenere o contrato e confira o diff**

Run: `./gradlew updateOpenApi && git diff --stat api/openapi.yaml && git diff api/openapi.yaml`
Expected: só mudam as respostas `200` de `restrictGuest`, `liftGuestRestriction` e `mergeGuests`, as descrições delas e de `genderDescription`, e entra o componente `GuestWriteSummaryResponse`. Qualquer outra mudança: corrija a anotação antes de seguir.

- [ ] **Step 6: Rode os testes da tarefa e os do `guests` que tocam as rotas**

Run: `./gradlew test --tests 'com.zeny.hotel.guests.*'`
Expected: PASS, inclusive `GuestsApiTest` e `GuestMergeApiTest` sem mudança (eles usam usuários com `profiles.read`, e o corpo inteiro continua igual).

- [ ] **Step 7: README** — no item "Hóspedes e empresas" da seção "API" (linha 222), troque "devolvida em toda resposta de perfil, inclusive nas das escritas" por texto que diga que `restrict`, `lift-restriction` e `merge` devolvem o perfil inteiro só a quem tem `guests.profiles.read`, e, sem ela, um corpo mínimo (`id`, `status`, `restricted`, `updatedAt`); registre que é uma mudança de forma da resposta para roles customizadas sem `guests.profiles.read` (os modelos padrão não mudam).

- [ ] **Step 8: Verificação completa**

Run: `./gradlew build` e depois `.github/scripts/check-api-breaking.sh main`
Expected: `BUILD SUCCESSFUL`; o oasdiff lista no máximo os três `warning` `response-body-wrapped-in-one-of-original-preserved` (esperados) e termina com "Nenhuma mudança incompatível (nível ERR) em /api/v1." Cole as duas saídas no relatório.

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/com/zeny/hotel/guests/web/GuestsDtos.kt src/main/kotlin/com/zeny/hotel/guests/web/GuestsController.kt api/openapi.yaml README.md src/test/kotlin/com/zeny/hotel/guests/GuestWriteBodyApiTest.kt
git commit -m "feat(guests): corpo mínimo em restrict, lift-restriction e merge sem guests.profiles.read

Soul: desenvolvedor"
```
