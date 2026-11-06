# M5 E15 — Descrição de gênero no perfil do hóspede: plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** guardar no perfil do hóspede a descrição de gênero que a API da FNRH exige quando o gênero é `OUTRO` e levá-la aos dados da FNRH, para esse hóspede poder ser enviado sem falsear o dado.

**Architecture:** coluna nova e anulável `guests.guest.gender_description`, cifrada pelo `EncryptedStringConverter`, com a regra no agregado `Guest` e repetida em `CHECK`. O campo entra no contrato de hóspedes, na exportação do titular, na anonimização e em `FnrhGuestData`; o `FnrhPersonMapper` o passa ao `FnrhPersonData.genderDescription`, que o adaptador da E07 já envia como `GeneroDescricao`.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Hibernate 7.4, Flyway 12, WireMock, Testcontainers.

**Spec:** `docs/roadmaps/roadmap-m5-recepcao-fnrh.md`, seção "E15", mais a R5 de `docs/superpowers/specs/2026-11-06-revisao-arquitetura-roadmaps-design.md`.

## Decisões desta etapa

| # | Decisão | Por quê | Custo se errada |
|---|---|---|---|
| D-E15-1 | O valor fica **cifrado** (`EncryptedStringConverter`, como `dietary_restrictions`). Decisão que a etapa manda registrar. | Autodeclaração de identidade de gênero em texto livre é dado pessoal e pode revelar dado sensível (LGPD art. 5º II). Ninguém busca nem indexa o campo, então a cifra não custa função. | Mais um valor na lista da re-cifragem (M8 E15). Tirar a cifra depois é uma migração simples. |
| D-E15-2 | Até **100 caracteres**, sem `pattern`. | A API v2.4 não documenta limite (`docs/fnrh/api-fnrh-v2.4.md`, linha 54), e 100 cobre uma autodeclaração. Sem `pattern`: texto livre com acento; o JSON escapa o resto, como em `socialName`. | A E09 (homologação) pode revelar limite menor; reduzir o limite é mudança de contrato a tratar lá. |
| D-E15-3 | Descrição com gênero diferente de `OTHER` → `400 GENDER_DESCRIPTION_REQUIRES_OTHER_GENDER`, com o campo `genderDescription` e sem repetir o valor. | Mesma forma de `400 ADDRESS_REQUIRES_BRAZIL_RESIDENCE`: incoerência entre dois campos do corpo. | Só o código; publicado, não muda. |
| D-E15-4 | O campo volta nas respostas de perfil (`GuestResponse`), com a permissão `guests.profiles.read`, e **não** entra em `GuestSummary`. | O `PUT` troca o perfil inteiro: o cliente precisa ler para reenviar, como `dietaryRestrictions`. Os outros módulos não precisam dele. | Ampliar depois é aditivo. |
| D-E15-5 | Na mesclagem, a descrição anda **com o gênero** (`GuestMergeField.GENDER`), como o endereço anda com o país de residência. | Tomar o gênero de um perfil e a descrição de outro quebraria a regra e o `CHECK`. | Regra local a `Guest.mergedProfile`. |

## Global Constraints

- Respeitar o grafo de módulos da ARCHITECTURE §3.1; `fnrh` já depende de `guests`. Nenhuma aresta nova.
- Dados sensíveis da FNRH e o número inteiro dos documentos só são lidos pelo `fnrh` (`GuestsApi.fnrhData`), só em memória durante o envio. A descrição segue a H10 do M3: nunca em log, trilha (só o nome do campo em `changedFields`), `toString`, evento, notificação nem view.
- Migração com timestamp posterior a `V2026_11_04_1030`; coluna nova e anulável, sem reescrever linhas.
- Contrato regenerado por `./gradlew updateOpenApi` e `.github/scripts/check-api-breaking.sh` sem mudança `ERR` em `/api/v1`. O campo é opcional.
- Código em inglês; mensagens e comentários em pt-BR, como no resto do módulo.
- Todo commit leva o trailer `Soul: <soul>`.
- Antes de commitar: `./gradlew ktlintFormat` e os testes da tarefa.

## Review Focus

1. `PUT` que troca o gênero de `OTHER` para `MALE` reenviando a descrição antiga: recusa `400`, sem gravar nada (teste na Task 1).
2. Mesclagem em que o gênero vem de um perfil e o outro tinha descrição: o sobrevivente nunca fica com descrição sem `OTHER`, e o `CHECK` não derruba a mesclagem (teste na Task 1).
3. Descrição só com espaços e gênero `OTHER`: vira nula, e a validação da FNRH continua acusando `REQUIRED` (testes nas Tasks 1 e 4).
4. Anonimização de um perfil com descrição, inclusive absorvido da cadeia: a coluna fica nula, e o `CHECK` confirma (teste na Task 3).
5. Mensagem de erro do Serpro que ecoa a descrição: o saneador mascara, e nem exceção nem log trazem o valor (teste na Task 4).

---

### Task 1: Coluna, regra no agregado e mesclagem

**Estimativa:** 30 min. **Depende de:** nenhuma. **Risco:** alto (persistência, migração, núcleo).

**Files:**
- Create: `src/main/resources/db/migration/guests/V2026_11_06_1000__add_guest_gender_description.sql`
- Modify: `src/main/kotlin/com/zeny/hotel/guests/domain/Guest.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/guests/GuestsErrorCodes.kt`
- Test: `src/test/kotlin/com/zeny/hotel/guests/GuestsDomainTest.kt`, `GuestMergeDomainTest.kt`, `GuestPrivacyDomainTest.kt`

**Interfaces:**
- Produces: `GuestProfile.genderDescription: String? = null`; `Guest.genderDescription: String?` (`protected set`); `Guest.MAX_GENDER_DESCRIPTION_LENGTH = 100`; `GuestsErrorCodes.GENDER_DESCRIPTION_REQUIRES_OTHER_GENDER`.

- [ ] **Step 1: Escreva os testes de domínio que falham**

Em `GuestsDomainTest`: `gender description is accepted with OTHER` (grava e aparece em `changedFields` como `"genderDescription"`); `gender description is refused with another gender` (para `MALE`, `FEMALE`, `NOT_INFORMED` e `null`: `400`, código `GENDER_DESCRIPTION_REQUIRES_OTHER_GENDER`, campo `genderDescription`, mensagem sem o valor, e o perfil continua como estava); `blank gender description becomes null`; `gender description over 100 characters is refused` (`GUESTS_DATA_INVALID`, 100 aceita, 101 recusa); `to string never shows the gender description` (`Guest` e `GuestProfile`).

Em `GuestMergeDomainTest`:
- sobrevivente `OTHER`+descrição, absorvido `MALE`, sem `GENDER` em `takeFromMerged`: fica `OTHER` com a descrição do sobrevivente;
- o mesmo com `GENDER` em `takeFromMerged`: fica `MALE`, descrição nula;
- sobrevivente sem gênero, absorvido `OTHER`+descrição: recebe os dois;
- sobrevivente `OTHER` sem descrição, absorvido `OTHER`+descrição: recebe a descrição;
- sobrevivente `OTHER`+descrição, absorvido `OTHER` sem descrição, com `GENDER` em `takeFromMerged`: mantém a descrição (nunca troca valor por vazio);
- sobrevivente `MALE`, absorvido `OTHER`+descrição, sem `GENDER`: fica `MALE`, descrição nula.

Em `GuestPrivacyDomainTest`: `anonymize clears the gender description`.

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

- [ ] **Step 5: Rode os testes de domínio e o de migração**

Run: `./gradlew test --tests 'com.zeny.hotel.guests.GuestsDomainTest' --tests 'com.zeny.hotel.guests.GuestMergeDomainTest' --tests 'com.zeny.hotel.guests.GuestPrivacyDomainTest' --tests '*MigrationConventionTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

`git add` dos arquivos acima; mensagem em pt-BR com o trailer `Soul: desenvolvedor`.

---

### Task 2: O campo na API de hóspedes e no contrato

**Estimativa:** 20 min. **Depende de:** Task 1. **Risco:** alto (contrato público, entrada externa).

Exceção registrada ao limite de 3 arquivos: são 4 arquivos de produção, mas só um campo atravessando as camadas. Dividir deixaria uma parte sem teste próprio.

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/guests/application/GuestsPorts.kt` (`GuestCommand`, `GuestView`)
- Modify: `src/main/kotlin/com/zeny/hotel/guests/application/GuestService.kt` (linha ~318, comando → perfil)
- Modify: `src/main/kotlin/com/zeny/hotel/guests/application/GuestsSupport.kt` (linha ~72, agregado → view)
- Modify: `src/main/kotlin/com/zeny/hotel/guests/web/GuestsDtos.kt` (`GuestProfileRequest`, `GuestResponse`)
- Modify: `api/openapi.yaml` (gerado)
- Test: `src/test/kotlin/com/zeny/hotel/guests/GuestsApiTest.kt`

**Interfaces:**
- Consumes: `GuestProfile.genderDescription`, `Guest.genderDescription` e o código da Task 1.
- Produces: `GuestCommand.genderDescription`, `GuestView.genderDescription`, `genderDescription` opcional nas requisições e nas respostas de perfil.

- [ ] **Step 1: Escreva os testes de API que falham**

Em `GuestsApiTest`:
- `POST` e `PUT` com `gender: OTHER` e `genderDescription`: `201`/`200`, e o `GET` devolve o valor;
- a coluna lida por `JdbcClient` começa com `v1:` e não contém o texto claro;
- `POST` com `gender: MALE` e descrição: `400`, `code` = `GENDER_DESCRIPTION_REQUIRES_OTHER_GENDER`, `errors[]`/campo `genderDescription`, corpo sem o valor;
- `PUT` de `OTHER` para `MALE` reenviando a descrição: `400`, e o `GET` continua com `OTHER` e a descrição;
- `PUT` de `OTHER` para `MALE` sem descrição: `200`, e a coluna fica nula;
- 101 caracteres: `400` (`VALIDATION_FAILED`, pelo `@Size`);
- a trilha do `PUT` traz `"genderDescription"` em `changedFields` e não contém o valor; o log capturado (`OutputCaptureExtension`, como nos testes existentes de log sem dado pessoal) também não;
- `report_guests` não tem coluna com `gender` no nome (`information_schema.columns`).

- [ ] **Step 2: Rode e veja falhar**

Run: `./gradlew test --tests 'com.zeny.hotel.guests.GuestsApiTest'`
Expected: falha de compilação ou dos casos novos.

- [ ] **Step 3: Passe o campo pelas camadas**

No DTO de requisição: `@field:Size(max = 100)` e `@field:Schema` em pt-BR dizendo que é a descrição exigida pela FNRH para o gênero `OTHER`, recusada com outro gênero, e gravada cifrada. Sem `pattern`. Os `toString` mascarados já existentes continuam sem mostrar o campo.

- [ ] **Step 4: Regenere o contrato**

Run: `./gradlew updateOpenApi` e confira que o diff de `api/openapi.yaml` só acrescenta o campo.

- [ ] **Step 5: Rode**

Run: `./gradlew test --tests 'com.zeny.hotel.guests.GuestsApiTest' --tests 'com.zeny.hotel.guests.GuestMergeApiTest' && ./gradlew checkOpenApi`
Expected: PASS.

- [ ] **Step 6: Commit** com o trailer `Soul: desenvolvedor`.

---

### Task 3: Exportação do titular e anonimização

**Estimativa:** 15 min. **Depende de:** Task 2. **Risco:** alto (privacidade, LGPD).

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/guests/application/GuestsOwnPersonalData.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/guests/application/GuestDataSubjectService.kt` (`ANONYMIZED_FIELDS`)
- Test: `src/test/kotlin/com/zeny/hotel/guests/GuestPrivacyApiTest.kt`

**Interfaces:**
- Consumes: `Guest.genderDescription`; `genderDescription` na API (Task 2).
- Produces: `genderDescription` na seção `guests` da exportação.

- [ ] **Step 1: Escreva os testes que falham**

Em `GuestPrivacyApiTest`:
- a exportação de um perfil `OTHER` com descrição traz `genderDescription` na seção `guests`, e a trilha da exportação não traz o valor;
- a anonimização zera a coluna `gender_description` (leitura por `JdbcClient`) do perfil e de um absorvido da cadeia, e a trilha lista `"genderDescription"` nos campos, sem o valor;
- perfil, `PUT`, mesclagem e anonimização com um marcador na descrição: nenhuma linha das tabelas de `notifications` contém o marcador.

- [ ] **Step 2: Rode e veja falhar**

Run: `./gradlew test --tests 'com.zeny.hotel.guests.GuestPrivacyApiTest'`
Expected: FAIL nos casos novos.

- [ ] **Step 3: Implemente**

`genderDescription` ao lado de `gender` nos dados de `GuestsOwnPersonalData`; `"genderDescription"` em `ANONYMIZED_FIELDS`, depois de `"gender"`.

- [ ] **Step 4: Rode**

Run: `./gradlew test --tests 'com.zeny.hotel.guests.GuestPrivacyApiTest'`
Expected: PASS.

- [ ] **Step 5: Commit** com o trailer `Soul: desenvolvedor`.

---

### Task 4: Descrição nos dados da FNRH e no envio ao Serpro

**Estimativa:** 20 min. **Depende de:** Task 2. **Risco:** alto (integração externa, dado pessoal).

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/guests/GuestsApi.kt` (`FnrhGuestData`)
- Modify: `src/main/kotlin/com/zeny/hotel/guests/application/GuestsApiService.kt` (linha ~98)
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/domain/FnrhPersonMapper.kt`
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/domain/FnrhPersonMapperTest.kt`, `FnrhLocalValidatorTest.kt`, `src/test/kotlin/com/zeny/hotel/fnrh/infrastructure/SerproFnrhGatewayTest.kt`, `src/test/kotlin/com/zeny/hotel/guests/GuestsApiTest.kt`

**Interfaces:**
- Consumes: `Guest.genderDescription` (Task 1); `FnrhPersonData.genderDescription` e o `SerproFnrhWire` (E07, já leva `GeneroDescricao`); `personSensitiveValues` (E07, já lista `genderDescription`).
- Produces: `FnrhGuestData.genderDescription: String? = null`, logo depois de `gender`.

- [ ] **Step 1: Escreva os testes que falham**

- `FnrhPersonMapperTest`: `OTHER` com descrição gera `gender = "OUTRO"` e `genderDescription` igual; outro gênero gera `genderDescription = null`.
- `FnrhLocalValidatorTest`, a partir do `FnrhGuestData` mapeado: `OTHER` com descrição não acusa `person.genderDescription`; `OTHER` sem descrição continua acusando `person.genderDescription`/`REQUIRED`.
- `SerproFnrhGatewayTest`: `registerPerson` com a descrição leva `pessoa.GeneroDescricao` no corpo (WireMock `matchingJsonPath`, nome como na API v2.4); um `400` do Serpro que ecoa a descrição não a traz na exceção nem no log capturado.
- `GuestsApiTest`: `GuestsApi.fnrhData` devolve a descrição decifrada; `FnrhGuestData.toString()` não a mostra; `GuestsApi.guest(id)` (`GuestSummary`) não tem o campo.

- [ ] **Step 2: Rode e veja falhar**

Run: `./gradlew test --tests 'com.zeny.hotel.fnrh.domain.FnrhPersonMapperTest'`
Expected: falha de compilação.

- [ ] **Step 3: Implemente**

`FnrhGuestData.genderDescription`; no `GuestsApiService.fnrhData`, `genderDescription = guest.genderDescription`; no mapeador, `genderDescription = guest.genderDescription.takeIf { guest.gender == Gender.OTHER }`.

- [ ] **Step 4: Rode**

Run: `./gradlew test --tests 'com.zeny.hotel.fnrh.domain.*' --tests 'com.zeny.hotel.fnrh.infrastructure.SerproFnrhGatewayTest' --tests 'com.zeny.hotel.guests.GuestsApiTest'`
Expected: PASS.

- [ ] **Step 5: Commit** com o trailer `Soul: desenvolvedor`.
