# M5 E15 — Descrição de gênero no perfil do hóspede: plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** guardar no perfil do hóspede a descrição de gênero que a API da FNRH exige quando o gênero é `OUTRO` e levá-la aos dados da FNRH, para esse hóspede poder ser enviado sem falsear o dado.

**Architecture:** coluna nova e anulável `guests.guest.gender_description`, cifrada pelo `EncryptedStringConverter`, com a regra no agregado `Guest` e repetida em `CHECK`. O campo entra no contrato de hóspedes, na exportação do titular, na anonimização e em `FnrhGuestData`; o `FnrhPersonMapper` o passa ao `FnrhPersonData.genderDescription`, que o adaptador da E07 já envia como `GeneroDescricao`.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Hibernate 7.4, Flyway 12, WireMock, Testcontainers.

**Spec:** `docs/roadmaps/roadmap-m5-recepcao-fnrh.md`, seção "E15", mais a R5 de `docs/superpowers/specs/2026-11-06-revisao-arquitetura-roadmaps-design.md`.

## Decisões desta etapa

| # | Decisão | Por quê | Custo se errada |
|---|---|---|---|
| D-E15-1 | O valor fica **cifrado** (`EncryptedStringConverter`, como `dietary_restrictions`). Decisão que a etapa manda registrar. | Autodeclaração de identidade de gênero em texto livre é dado pessoal e pode revelar dado sensível (LGPD art. 5º II). Ninguém busca nem indexa o campo, então a cifra não custa função. | Mais um valor na lista da re-cifragem (M8 E15). Tirar a cifra depois exige job da aplicação, porque a chave fica fora do banco. |
| D-E15-2 | Até **100 caracteres**, sem `pattern`. | A API v2.4 não documenta limite (`docs/fnrh/api-fnrh-v2.4.md`, linha 54), e 100 cobre uma autodeclaração. Sem `pattern`: texto livre com acento; o JSON escapa o resto, como em `socialName`. | A E09 pode revelar limite menor; reduzir é mudança restritiva de contrato, com valores longos já gravados. A nota vai ao handoff, para a E09. |
| D-E15-3 | Descrição com gênero diferente de `OTHER` → `400 GENDER_DESCRIPTION_REQUIRES_OTHER_GENDER`, com o campo `genderDescription` e sem repetir o valor. | Mesma forma de `400 ADDRESS_REQUIRES_BRAZIL_RESIDENCE`: incoerência entre dois campos do corpo. | Só o código; publicado, não muda. |
| D-E15-4 | O campo volta em toda resposta de perfil (`GuestResponse`: detalhe, listagem, busca por documento e candidatos a duplicado), com `guests.profiles.read` (Gerente, Recepcionista, Financeiro), e **não** entra em `GuestSummary`. Fica até a anonimização; a M8 E16 decide se segue o prazo do expurgo (roadmap do M8, linha 935). Vai às "Decisões para o usuário". | Precedente direto: `dietaryRestrictions`, dado de saúde, segue o mesmo caminho e a mesma permissão. O `PUT` troca o perfil inteiro, então o cliente precisa ler para reenviar. Descartadas: só no `GET /{id}` (a listagem devolveria nulo, e um `PUT` feito a partir dela apagaria o valor); escrita sem leitura, com `hasGenderDescription` e `PUT` que preserva (quebra a semântica do `PUT`, e a recepção não confere o dado com o hóspede). | Reduzir depois é incompatível para clientes gerados, e o Financeiro lê um dado de que não precisa. Nenhum cliente consome o campo ainda: revisar antes da E09 custa pouco. |
| D-E15-5 | Na mesclagem, a descrição anda **com o gênero** (`GuestMergeField.GENDER`), como o endereço anda com o país de residência. | Tomar o gênero de um perfil e a descrição de outro quebraria a regra e o `CHECK`. | Regra local a `Guest.mergedProfile`. |
| D-E15-6 | O saneador da E07 **não muda**: descrição de 1 ou 2 caracteres ecoada pelo Serpro não é mascarada (`MIN_VALUE_LENGTH = 3`, comportamento fixado por `FnrhMessageSanitizerTest`). Risco residual aceito; vai às "Decisões para o usuário". | Mascarar valores curtos em geral atinge artigos e preposições das mensagens (motivo do teste existente), e um parâmetro só para esse campo atravessaria o `errorMessage` e o gateway por um eco hipotético. O restante da mensagem continua saneado (nome, documento, contato), então o valor curto sai sem identificar a pessoa. Recusar valor curto impediria uma autodeclaração legítima. | Exceção literal ao critério "não aparece em log", em caso raro e hipotético. Corrigir depois é local ao adaptador; a E09 confere se o Serpro ecoa o campo. |

## Global Constraints

- Respeitar o grafo de módulos da ARCHITECTURE §3.1; `fnrh` já depende de `guests`. Nenhuma aresta nova.
- Dados sensíveis da FNRH e o número inteiro dos documentos só são lidos pelo `fnrh` (`GuestsApi.fnrhData`), só em memória durante o envio. A descrição segue a H10 do M3: nunca em log, trilha (só o nome do campo em `changedFields`), `toString`, evento, notificação nem view.
- Migração com timestamp posterior a `V2026_11_04_1030`; coluna nova e anulável, sem reescrever linhas.
- Contrato regenerado por `./gradlew updateOpenApi` e `.github/scripts/check-api-breaking.sh` sem mudança `ERR` em `/api/v1`. O campo é opcional.
- Código em inglês; mensagens e comentários em pt-BR, como no resto do módulo.
- Todo commit leva o trailer `Soul: <soul>`.
- Antes de commitar: `./gradlew ktlintFormat`, `./gradlew lint` e os testes da tarefa. O detekt usa os limites padrão (`config/detekt/detekt.yml` não redefine regras); se `Guest.apply` ou `mergedProfile` passarem do limite, extraia uma função em vez de suprimir.
- A etapa atualiza a seção "API" do `README.md` (restrição do roadmap): Task 6.
- Validação obrigatória da etapa, rodada pelo orquestrador antes da revisão final: `./gradlew build` e `.github/scripts/check-api-breaking.sh`.

## Review Focus

1. `PUT` que troca o gênero de `OTHER` para `MALE` reenviando a descrição antiga: recusa `400`, sem gravar nada (testes nas Tasks 1 e 2).
2. Mesclagem em que o gênero vem de um perfil e o outro tinha descrição: o sobrevivente nunca fica com descrição sem `OTHER`, e o `CHECK` não derruba a mesclagem (domínio na Task 1; ponta a ponta, com o banco, na Task 2).
3. Descrição só com espaços e gênero `OTHER`: vira nula, e a validação da FNRH continua acusando `REQUIRED` (testes nas Tasks 1 e 5).
4. Anonimização de um perfil com descrição, inclusive absorvido da cadeia: a coluna fica nula, e `guest_anonymized_ck` recusa o contrário (`CHECK` na Task 1; anonimização na Task 4).
5. Mensagem de erro do Serpro que ecoa a descrição de 3 ou mais caracteres: o saneador mascara, e nem exceção nem log trazem o valor (Task 5). Valor de 1 ou 2 caracteres: D-E15-6.

## Ondas

Tasks 1, 2 e 3 em sequência; Tasks 4 e 5 na mesma onda (arquivos disjuntos, inclusive os de teste); Task 6 no fechamento.

---

### Task 1: Coluna, regra no agregado e mesclagem

**Estimativa:** 30 min. **Depende de:** nenhuma. **Risco:** alto (persistência, migração, núcleo).

**Files:**
- Create: `src/main/resources/db/migration/guests/V2026_11_06_1000__add_guest_gender_description.sql`
- Modify: `src/main/kotlin/com/zeny/hotel/guests/domain/Guest.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/guests/GuestsErrorCodes.kt`
- Test: `src/test/kotlin/com/zeny/hotel/guests/GuestsDomainTest.kt`, `GuestMergeDomainTest.kt`, `GuestPrivacyDomainTest.kt`
- Create (test): `src/test/kotlin/com/zeny/hotel/guests/GuestGenderDescriptionConstraintsTest.kt` (`@IntegrationTest`)

**Interfaces:**
- Produces: `GuestProfile.genderDescription: String? = null`; `Guest.genderDescription: String?` (`protected set`); `Guest.MAX_GENDER_DESCRIPTION_LENGTH = 100`; `GuestsErrorCodes.GENDER_DESCRIPTION_REQUIRES_OTHER_GENDER`.

- [ ] **Step 1: Escreva os testes que falham**

Em `GuestsDomainTest`: `gender description is accepted with OTHER` (grava e aparece em `changedFields` como `"genderDescription"`); `gender description is refused with another gender` (para `MALE`, `FEMALE`, `NOT_INFORMED` e `null`: `400`, código `GENDER_DESCRIPTION_REQUIRES_OTHER_GENDER`, campo `genderDescription`, mensagem sem o valor, e o perfil continua como estava); `blank gender description becomes null`; `gender description over 100 characters is refused` (`GUESTS_DATA_INVALID`, 100 aceita, 101 recusa); `to string never shows the gender description` (`Guest` e `GuestProfile`).

Em `GuestMergeDomainTest`:
- sobrevivente `OTHER`+descrição, absorvido `MALE`, sem `GENDER` em `takeFromMerged`: fica `OTHER` com a descrição do sobrevivente;
- o mesmo com `GENDER` em `takeFromMerged`: fica `MALE`, descrição nula;
- sobrevivente sem gênero, absorvido `OTHER`+descrição: recebe os dois;
- sobrevivente `OTHER` sem descrição, absorvido `OTHER`+descrição: recebe a descrição;
- sobrevivente `OTHER`+descrição, absorvido `OTHER` sem descrição, com `GENDER` em `takeFromMerged`: mantém a descrição (nunca troca valor por vazio);
- sobrevivente `MALE`, absorvido `OTHER`+descrição, sem `GENDER`: fica `MALE`, descrição nula.

Em `GuestPrivacyDomainTest`: `anonymize clears the gender description`.

Em `GuestGenderDescriptionConstraintsTest`, por `JdbcClient` contra o banco migrado (a migração só roda aqui, porque o `MigrationConventionTest` confere o texto, sem banco):
- `guest_gender_description_ck` recusa `MALE` com descrição e recusa texto sem o prefixo `v1:`;
- `guest_anonymized_ck` recusa perfil anonimizado com descrição;
- `pg_get_constraintdef` de `guest_anonymized_ck` contém as cláusulas de `V2026_10_17_0440__add_guest_privacy.sql`, linhas 34 a 46 (nenhuma se perdeu ao recriar), mais `gender_description IS NULL`.

- [ ] **Step 2: Rode e veja falhar**

Run: `./gradlew test --tests 'com.zeny.hotel.guests.GuestsDomainTest' --tests 'com.zeny.hotel.guests.GuestMergeDomainTest' --tests 'com.zeny.hotel.guests.GuestPrivacyDomainTest'`
Expected: falha de compilação (`genderDescription` não existe).

- [ ] **Step 3: Escreva a migração**

`ALTER TABLE guests.guest`:
- `ADD COLUMN gender_description TEXT`;
- `ADD CONSTRAINT guest_gender_description_ck CHECK (gender_description IS NULL OR (gender = 'OTHER' AND gender_description LIKE 'v1:%'))`;
- `DROP CONSTRAINT guest_anonymized_ck` e `ADD CONSTRAINT guest_anonymized_ck`, com o texto idêntico ao de `V2026_10_17_0440__add_guest_privacy.sql` acrescido de `AND gender_description IS NULL`.

Comentário no topo dizendo que o valor é cifrado e por quê (D-E15-1).

- [ ] **Step 4: Implemente no `Guest`**

- Campo `genderDescription` com `@Convert(converter = EncryptedStringConverter::class)` e `@Column(name = "gender_description")`, como `dietaryRestrictions`.
- Em `apply`: `GuestsRules.optional("genderDescription", profile.genderDescription, MAX_GENDER_DESCRIPTION_LENGTH)`; não nula com `profile.gender != Gender.OTHER` → `GuestsRules.invalid(GENDER_DESCRIPTION_REQUIRES_OTHER_GENDER, "genderDescription", <mensagem sem o valor>)`, **antes** de qualquer `changes.set`; depois `changes.set("genderDescription", …)`.
- Em `anonymize`: `genderDescription = null`.
- Em `mergedProfile`, o gênero e a descrição saem juntos:

```kotlin
val genderFromMerged =
    if (GuestMergeField.GENDER in take) merged.gender != null else gender == null && merged.gender != null
val mergedGender = if (genderFromMerged) merged.gender else gender
val genderDescription =
    when {
        mergedGender != Gender.OTHER -> null
        genderFromMerged -> merged.genderDescription ?: this.genderDescription
        else -> this.genderDescription ?: merged.genderDescription
    }
```

Nas duas últimas linhas, a descrição do outro perfil só existe se ele for `OTHER` (o `CHECK` garante), e a regra "vazio recebe o do outro" fica igual à de `pick`.

Atualize os KDocs que listam campos: `Guest.anonymize`, `Guest.absorb` e `GuestMergeField.GENDER` (`GuestsApi.kt`, só o comentário: a descrição anda com o gênero).

- [ ] **Step 5: Rode os testes de domínio, os de `CHECK` e o de migração**

Run: `./gradlew test --tests 'com.zeny.hotel.guests.GuestsDomainTest' --tests 'com.zeny.hotel.guests.GuestMergeDomainTest' --tests 'com.zeny.hotel.guests.GuestPrivacyDomainTest' --tests 'com.zeny.hotel.guests.GuestGenderDescriptionConstraintsTest' --tests '*MigrationConventionTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

`git add` dos arquivos acima; mensagem em pt-BR com o trailer `Soul: desenvolvedor`.

---

### Task 2: Escrita pela API de hóspedes

**Estimativa:** 15 min. **Depende de:** Task 1. **Risco:** alto (contrato público, entrada externa).

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/guests/application/GuestsPorts.kt` (`GuestCommand`)
- Modify: `src/main/kotlin/com/zeny/hotel/guests/application/GuestService.kt` (linha ~318, comando → perfil)
- Modify: `src/main/kotlin/com/zeny/hotel/guests/web/GuestsDtos.kt` (`GuestProfileRequest`; `MergeGuestRequest`, só a descrição)
- Modify: `api/openapi.yaml` (gerado)
- Test: `src/test/kotlin/com/zeny/hotel/guests/GuestsApiTest.kt`, `GuestMergeApiTest.kt`

**Interfaces:**
- Consumes: `GuestProfile.genderDescription` e o código de erro da Task 1.
- Produces: `GuestCommand.genderDescription`; `genderDescription` opcional no corpo de perfil.

- [ ] **Step 1: Escreva os testes que falham**

Em `GuestsApiTest`:
- `POST` e `PUT` com `gender: OTHER` e `genderDescription`: `201`/`200`; a coluna lida por `JdbcClient` começa com `v1:` e não contém o texto claro;
- `POST` com `gender: MALE` e descrição: `400`, `code` = `GENDER_DESCRIPTION_REQUIRES_OTHER_GENDER`, campo `genderDescription`, corpo sem o valor;
- `PUT` de `OTHER` para `MALE` reenviando a descrição: `400`, e a coluna fica como estava;
- `PUT` de `OTHER` para `MALE` sem descrição: `200`, e a coluna fica nula;
- 101 caracteres: `400` (`VALIDATION_FAILED`, pelo `@Size`);
- a trilha do `PUT` traz `"genderDescription"` em `changedFields` e não contém o valor; o log capturado (`OutputCaptureExtension`, como nos testes existentes de log sem dado pessoal) também não;
- `report_guests` não tem coluna com `gender` no nome (`information_schema.columns`).

Em `GuestMergeApiTest`: sobrevivente `MALE`, absorvido `OTHER` com descrição, sem `GENDER` em `takeFromMerged`: `200`, e a coluna do sobrevivente fica nula; o mesmo com `GENDER`: `200`, sobrevivente `OTHER`, com a descrição cifrada.

- [ ] **Step 2: Rode e veja falhar**

Run: `./gradlew test --tests 'com.zeny.hotel.guests.GuestsApiTest' --tests 'com.zeny.hotel.guests.GuestMergeApiTest'`
Expected: falha nos casos novos.

- [ ] **Step 3: Implemente**

Na requisição: `@field:Size(max = 100)` e `@field:Schema` em pt-BR dizendo que é a descrição exigida pela FNRH para o gênero `OTHER`, gravada cifrada, e recusada com outro gênero (`400 GENDER_DESCRIPTION_REQUIRES_OTHER_GENDER`). Sem `pattern`. No `@Schema` de `MergeGuestRequest`, diga que a descrição acompanha o gênero. Os `toString` mascarados continuam sem mostrar o campo.

- [ ] **Step 4: Regenere o contrato**

Run: `./gradlew updateOpenApi` e confira que o diff de `api/openapi.yaml` só acrescenta o campo e as descrições.

- [ ] **Step 5: Rode**

Run: `./gradlew test --tests 'com.zeny.hotel.guests.GuestsApiTest' --tests 'com.zeny.hotel.guests.GuestMergeApiTest' && ./gradlew checkOpenApi`
Expected: PASS.

- [ ] **Step 6: Commit** com o trailer `Soul: desenvolvedor`.

---

### Task 3: Leitura pela API de hóspedes

**Estimativa:** 10 min. **Depende de:** Task 2. **Risco:** alto (contrato público, exposição de dado pessoal; D-E15-4).

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/guests/application/GuestsPorts.kt` (`GuestView`)
- Modify: `src/main/kotlin/com/zeny/hotel/guests/application/GuestsSupport.kt` (linha ~72, agregado → view)
- Modify: `src/main/kotlin/com/zeny/hotel/guests/web/GuestsDtos.kt` (`GuestResponse`)
- Modify: `api/openapi.yaml` (gerado)
- Test: `src/test/kotlin/com/zeny/hotel/guests/GuestsApiTest.kt`

**Interfaces:**
- Consumes: `Guest.genderDescription` (Task 1); escrita pela API (Task 2).
- Produces: `GuestView.genderDescription`; `genderDescription` em `GuestResponse`.

- [ ] **Step 1: Escreva os testes que falham**

Em `GuestsApiTest`: `POST` seguido de `GET /{id}` devolve o valor decifrado; a listagem e a busca por documento também; o `PUT` que reenvia o que o `GET` devolveu não muda nada (`changedFields` sem o campo); sem `guests.profiles.read`, `403`.

- [ ] **Step 2: Rode e veja falhar**

Run: `./gradlew test --tests 'com.zeny.hotel.guests.GuestsApiTest'`
Expected: falha nos casos novos.

- [ ] **Step 3: Implemente** e regenere o contrato com `./gradlew updateOpenApi`. No `@Schema` da resposta, diga que é dado pessoal, visível com `guests.profiles.read`.

- [ ] **Step 4: Rode**

Run: `./gradlew test --tests 'com.zeny.hotel.guests.GuestsApiTest' && ./gradlew checkOpenApi`
Expected: PASS.

- [ ] **Step 5: Commit** com o trailer `Soul: desenvolvedor`.

---

### Task 4: Exportação do titular e anonimização

**Estimativa:** 15 min. **Depende de:** Task 3. **Risco:** alto (privacidade, LGPD).

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/guests/application/GuestsOwnPersonalData.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/guests/application/GuestDataSubjectService.kt` (`ANONYMIZED_FIELDS`)
- Test: `src/test/kotlin/com/zeny/hotel/guests/GuestPrivacyApiTest.kt`

**Interfaces:**
- Consumes: `Guest.genderDescription`; `genderDescription` na API (Tasks 2 e 3).
- Produces: `genderDescription` na seção `guests` da exportação.

- [ ] **Step 1: Escreva os testes que falham**

Em `GuestPrivacyApiTest`:
- a exportação de um perfil `OTHER` com descrição traz `genderDescription` na seção `guests`, e a trilha da exportação não traz o valor;
- a anonimização zera a coluna `gender_description` (leitura por `JdbcClient`) do perfil e de um absorvido da cadeia, e a trilha lista `"genderDescription"` nos campos, sem o valor;
- perfil, `PUT`, mesclagem e anonimização com um marcador na descrição: nenhuma linha das tabelas de `notifications` nem de `shared.event_publication` (coluna `serialized_event`) contém o marcador.

- [ ] **Step 2: Rode e veja falhar**

Run: `./gradlew test --tests 'com.zeny.hotel.guests.GuestPrivacyApiTest'`
Expected: FAIL nos casos novos.

- [ ] **Step 3: Implemente**

`genderDescription` ao lado de `gender` nos dados de `GuestsOwnPersonalData`, atualizando o KDoc que lista os campos; `"genderDescription"` em `ANONYMIZED_FIELDS`, depois de `"gender"`.

- [ ] **Step 4: Rode**

Run: `./gradlew test --tests 'com.zeny.hotel.guests.GuestPrivacyApiTest'`
Expected: PASS.

- [ ] **Step 5: Commit** com o trailer `Soul: desenvolvedor`.

---

### Task 5: Descrição nos dados da FNRH e no envio ao Serpro

**Estimativa:** 20 min. **Depende de:** Task 3. **Risco:** alto (integração externa, dado pessoal).

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/guests/GuestsApi.kt` (`FnrhGuestData`)
- Modify: `src/main/kotlin/com/zeny/hotel/guests/application/GuestsApiService.kt` (linha ~98)
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/domain/FnrhPersonMapper.kt`
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/domain/FnrhPersonMapperTest.kt`, `FnrhLocalValidatorTest.kt`
- Create (test): `src/test/kotlin/com/zeny/hotel/guests/GuestsApiFnrhDataTest.kt` (`@IntegrationTest`)
- Create (test): `src/test/kotlin/com/zeny/hotel/fnrh/infrastructure/SerproFnrhGatewayGenderDescriptionTest.kt`, com `OutputCaptureExtension`, no modelo de `SerproFnrhGatewayRound3Test`

**Interfaces:**
- Consumes: `Guest.genderDescription` (Task 1); `FnrhPersonData.genderDescription` e o `SerproFnrhWire` (E07, já leva `GeneroDescricao`); `personSensitiveValues` (E07, já lista `genderDescription`).
- Produces: `FnrhGuestData.genderDescription: String? = null`, logo depois de `gender`.

- [ ] **Step 1: Escreva os testes que falham**

- `FnrhPersonMapperTest`: `OTHER` com descrição gera `gender = "OUTRO"` e `genderDescription` igual; outro gênero gera `genderDescription = null`.
- `FnrhLocalValidatorTest`, a partir do `FnrhGuestData` mapeado: `OTHER` com descrição não acusa `person.genderDescription`; `OTHER` sem descrição continua acusando `person.genderDescription`/`REQUIRED`.
- `SerproFnrhGatewayGenderDescriptionTest`: `registerPerson` com a descrição leva `pessoa.GeneroDescricao` no corpo (WireMock `matchingJsonPath`, nome como na API v2.4); um `400` do Serpro que ecoa uma descrição de 3 ou mais caracteres não a traz na exceção nem no log capturado.
- `GuestsApiFnrhDataTest` (com `GuestsTestSupport`, criando o hóspede pela API): `GuestsApi.fnrhData` devolve a descrição decifrada; `FnrhGuestData.toString()` não a mostra.

- [ ] **Step 2: Rode e veja falhar**

Run: `./gradlew test --tests 'com.zeny.hotel.fnrh.domain.FnrhPersonMapperTest'`
Expected: falha de compilação.

- [ ] **Step 3: Implemente**

`FnrhGuestData.genderDescription`; no `GuestsApiService.fnrhData`, `genderDescription = guest.genderDescription`; no mapeador, `genderDescription = guest.genderDescription.takeIf { guest.gender == Gender.OTHER }`.

- [ ] **Step 4: Rode**

Run: `./gradlew test --tests 'com.zeny.hotel.fnrh.domain.*' --tests 'com.zeny.hotel.fnrh.infrastructure.SerproFnrhGatewayGenderDescriptionTest' --tests 'com.zeny.hotel.guests.GuestsApiFnrhDataTest'`
Expected: PASS.

- [ ] **Step 5: Commit** com o trailer `Soul: desenvolvedor`.

---

### Task 6: Documentos de referência (orquestrador)

**Estimativa:** 15 min. **Depende de:** Tasks 4 e 5. **Risco:** normal.

**Files:** só `.md`, editados pelo orquestrador, no commit de fechamento da etapa:
- `README.md`, seção "API": o campo novo.
- `docs/ARCHITECTURE.md` §8.3: a descrição de gênero e as restrições alimentares na lista do que se cifra e dos valores sem job de re-cifragem.
- `docs/SPEC.md`: RF-HOS-01 (descrição quando o gênero é "outro") e §8.2.4 (`genderDescription`).
- `docs/roadmaps/roadmap-m5-recepcao-fnrh.md`: "Decisões do lote E15" com D-E15-1 a 6; handoff com a nota para a E09 (limite real e eco de `GeneroDescricao`).
- `docs/roadmaps/roadmap-m8-restaurante-distribuicao.md`, linha 858: `guests.guest.gender_description` nos "Conhecidos" da M8 E15.

- [ ] **Step 1:** edite os documentos.
- [ ] **Step 2:** `python3 ~/.claude/skills/development-roadmap/scripts/check_roadmap.py` nos dois roadmaps.
- [ ] **Step 3:** commit com o trailer `Soul: orquestrador`.
