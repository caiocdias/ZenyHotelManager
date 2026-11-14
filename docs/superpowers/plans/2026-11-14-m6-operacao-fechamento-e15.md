# M6 E15 — Rótulos da FNRH na ficha e descrição da permissão do check-in — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a ficha de registro (PDF e ESC/POS) mostra o motivo de viagem e o meio de transporte pelo rótulo do catálogo de domínios da FNRH, por uma porta do `frontdesk` implementada pelo `fnrh`; a permissão `frontdesk.stays.checkin` passa a dizer que abre a ficha.

**Architecture:** inversão por porta (decisão do usuário, M5 D-E11-11): a porta `RegistrationCardLabels` fica na raiz do `frontdesk`, que continua sem importar o `fnrh`; o `fnrh` a implementa lendo `FnrhDomainCatalog` (só leitura, sem Serpro, sem trilha) e ganha a aresta `fnrh` → `frontdesk`, restrita à porta por uma regra ArchUnit no `ModularityTest` e registrada na ARCHITECTURE §3.1. É o mesmo padrão da porta `FolioOwnerDescriber` (`billing`, implementada pelo `reservations`, M5 D-E11-12).

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1 (`ModularityTest`), ArchUnit, Thymeleaf (templates do `printing`), PostgreSQL 18 (Testcontainers).

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E15 — Rótulos da FNRH na ficha e descrição da permissão do check-in", mais "Contexto confirmado" (H11) e "Restrições e invariantes"; decisões E11 (f) e (g) do lote E11 em `docs/roadmaps/roadmap-m5-recepcao-fnrh.md`; ARCHITECTURE §3.1 e §3.2; notas do brain `ZenyHotelManager - Hospedagem e recepção`, `ZenyHotelManager - Módulo fnrh` e `ZenyHotelManager - Segurança, autenticação e autorização`.

## Decisões do plano

- **D-E15-1 — Porta:** `com.zeny.hotel.frontdesk.RegistrationCardLabels` (arquivo `frontdesk/RegistrationCardLabels.kt`), interface com `fun travelReason(code: String): String?` e `fun transportMode(code: String): String?`: o rótulo do catálogo, ou `null` se o código não está nele. KDoc: porta da ficha (D-E11-11), implementada pelo `fnrh`; exatamente uma implementação no contexto; só leitura.
- **D-E15-2 — Implementação:** `com.zeny.hotel.fnrh.application.FnrhRegistrationCardLabels` (`@Component`), por `FnrhDomainCatalog.values(FnrhDomain.TRAVEL_REASONS | TRANSPORT_MODES)` e busca do código exato (maiúsculas contam, como em `contains`). Sem cache: lê o catálogo a cada ficha (são até 30 UHs e um punhado de hóspedes; o `refresh` aparece na ficha seguinte, critério 3). Sem trilha, sem Serpro.
- **D-E15-3 — Ficha:** o `RegistrationCardService` monta no modelo o texto final de cada campo: o rótulo, ou `"<código> (código FNRH)"` quando a porta devolve `null` (código fora do catálogo). Os templates `registration-card.xhtml` e `.txt` deixam de acrescentar `(código FNRH)` e mostram o texto do modelo. Código nulo continua sem a linha.
- **D-E15-4 — Grafo:** `FnrhModule.allowedDependencies` passa a `shared, guests, reservations, notifications, frontdesk`; o `ModularityTest` do `fnrh` exige exatamente esses cinco, o comentário "nunca … frontdesk" passa a "do `frontdesk`, só a porta dos rótulos (M6 E15)", e uma regra ArchUnit nova afirma que nenhuma classe de `..fnrh..` depende de classe de `..frontdesk..` além de `RegistrationCardLabels`. O teste do `frontdesk` continua sem `fnrh`. A tabela da ARCHITECTURE §3.1 ganha a aresta (linha do `fnrh`: "`frontdesk` (só a porta `RegistrationCardLabels`, M6 E15)"). A divergência anterior da §3.1 (lista `hotel` no `fnrh`, que o código não declara) não é corrigida aqui: fica com o `arquiteto`.
- **D-E15-5 — Descrição da permissão:** `"Fazer o check-in de uma reserva confirmada (com a caução opcional) e abrir a ficha de registro (PDF e impressão)"`. Código, sensibilidade, escopos, modelos de role e concessões não mudam.
- **D-E15-6 — Teste existente que fixa o código cru:** `RegistrationCardApiTest` afirma `"AUTOMOVEL (código FNRH)"` (linhas ~128 e ~144), e `AUTOMOVEL` está no catálogo semeado (`Automóvel`). A asserção passa a `"Automóvel"` e a ausência de `"AUTOMOVEL (código FNRH)"`; `"LAZER (código FNRH)"` continua (o código semeado é `LAZER_FERIAS`, então `LAZER` é fora do catálogo e cobre o critério 2). Ruling do orquestrador: autorizado ao desenvolvedor na Task 2; as asserções de RN-19 e de documento mascarado não mudam.

## Global Constraints

- O `frontdesk` não depende do `fnrh` (ARCHITECTURE §3.1 e §3.2: Integração governamental acima da Hospedagem); a aresta nova é só `fnrh` → `frontdesk`, pela porta, e entra na tabela §3.1 na mesma mudança.
- A ficha nunca leva dado de RN-19 (raça/cor, deficiência) nem número inteiro de documento; os testes da M5 E11 sobre isso passam sem mudança de asserção.
- Rótulo com caractere fora do IBM850 no ESC/POS sai pela limpeza de texto do `printing` (D-E11-4); um rótulo acentuado (`Automóvel`, `Lazer/Férias`) aparece correto nos dois formatos.
- `api/openapi.yaml` não muda (`./gradlew checkOpenApi` passa); `.github/scripts/check-api-breaking.sh` sem `ERR`.
- Banco compartilhado: teste que troca rótulo em `fnrh.domain_value` devolve o original no fim (`finally`).
- Máquina de 14 GB: um Gradle por vez, nunca `./gradlew --stop`.
- Commits em pt-BR com o trailer no último parágrafo, sem linha em branco entre `Soul: <soul>` e `Co-Authored-By: …`.

## Review Focus

1. **Código com caixa ou espaço diferente do catálogo** (`automovel`, `AUTOMOVEL `): sai cru com `(código FNRH)`, sem erro — teste na Task 1.
2. **Catálogo trocado pelo `refresh`** (rótulo novo; código removido): a ficha seguinte mostra o rótulo novo, ou o código cru se ele saiu — teste na Task 2.
3. **Rótulo com caracteres de controle ou marca bidi** vindo do Serpro pelo `refresh`: a ficha sai limpa (limpeza do `printing`) — teste na Task 2.
4. **Mais de uma implementação da porta** no contexto (um bean de teste esquecido): a ficha não pode escolher ao acaso — teste na Task 1 (exatamente um bean `RegistrationCardLabels`, e é o do `fnrh`).
5. **Descrição nova muda a versão das autorizações:** sessões abertas continuam válidas e as concessões não mudam — teste na Task 3.

---

### Task 1: Porta `RegistrationCardLabels` e a implementação do `fnrh`

Estimativa: 35 min · Depende de: nenhuma · Risco: alto (fronteira de módulo)

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/frontdesk/RegistrationCardLabels.kt`
- Create: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhRegistrationCardLabels.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/FnrhModule.kt` (`allowedDependencies` e KDoc)
- Modify (teste): `src/test/kotlin/com/zeny/hotel/ModularityTest.kt` (teste do `fnrh` e regra ArchUnit da porta)
- Modify (doc): `docs/ARCHITECTURE.md` §3.1 (linha do `fnrh`)
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/FnrhRegistrationCardLabelsTest.kt` (`@IntegrationTest`)

**Interfaces:**
- Produces: `interface RegistrationCardLabels { fun travelReason(code: String): String?; fun transportMode(code: String): String? }` em `com.zeny.hotel.frontdesk`; bean único `FnrhRegistrationCardLabels`.

- [ ] **Step 1: Testes que falham:**
  - `FnrhRegistrationCardLabelsTest`: `seeded codes come with their labels` (`travelReason("LAZER_FERIAS") == "Lazer/Férias"`, `transportMode("AUTOMOVEL") == "Automóvel"`); `a code outside the catalog has no label` (`travelReason("LAZER")`, `transportMode("automovel")`, `transportMode("AUTOMOVEL ")`, `travelReason("")` → `null`); `a label changed in the catalog is read on the next call` (troca o rótulo de `NEGOCIOS` em `fnrh.domain_value`, lê o novo; devolve o original no `finally`); `the context has exactly one implementation and it is the fnrh one` (`context.getBeansOfType(RegistrationCardLabels::class.java)` tem 1 entrada, do tipo `FnrhRegistrationCardLabels`).
  - `ModularityTest`: o teste do `fnrh` exige `shared, guests, reservations, notifications, frontdesk` (dependências diretas e `allowedDependencies`); novo `fnrh uses only the registration card labels port from frontdesk` (ArchUnit: classes de `com.zeny.hotel.fnrh..` só dependem, dentro de `com.zeny.hotel.frontdesk..`, de `com.zeny.hotel.frontdesk.RegistrationCardLabels`); o teste do `frontdesk` continua sem `fnrh`.
- [ ] **Step 2:** `./gradlew test --tests 'com.zeny.hotel.fnrh.FnrhRegistrationCardLabelsTest' --tests 'com.zeny.hotel.ModularityTest'` → vermelho.
- [ ] **Step 3: Implementar** D-E15-1, D-E15-2 e D-E15-4 (inclusive a linha da ARCHITECTURE §3.1).
- [ ] **Step 4:** `./gradlew test --tests 'com.zeny.hotel.fnrh.*' --tests 'com.zeny.hotel.ModularityTest' --tests 'com.zeny.hotel.ArchitectureTest*'` (ou o nome real dos testes ArchUnit do projeto) → verde.
- [ ] **Step 5: Commit** `feat(fnrh): porta dos rótulos da ficha no frontdesk, implementada pelo fnrh` com `Soul: desenvolvedor`.

### Task 2: A ficha mostra os rótulos

Estimativa: 30 min · Depende de: Task 1 · Risco: alto (núcleo da etapa; documento com dado pessoal)

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/frontdesk/application/RegistrationCardService.kt` (injeta `RegistrationCardLabels`; `guestModel` monta o texto de D-E15-3; KDoc da classe)
- Modify: `src/main/resources/templates/frontdesk/registration-card.xhtml`
- Modify: `src/main/resources/templates/frontdesk/registration-card.txt`
- Modify (teste, D-E15-6): `src/test/kotlin/com/zeny/hotel/frontdesk/RegistrationCardApiTest.kt`
- Test: `src/test/kotlin/com/zeny/hotel/frontdesk/RegistrationCardLabelsApiTest.kt` (`@IntegrationTest`, no modelo de `RegistrationCardApiTest`; extrai o texto do PDF com `PdfText`)

**Interfaces:**
- Consumes: Task 1 (`RegistrationCardLabels`).

- [ ] **Step 1: Testes que falham** em `RegistrationCardLabelsApiTest` (hóspedes com motivo e meio de transporte gravados no check-in do próprio teste):
  - `the PDF and the thermal print show the catalog labels`: com `LAZER_FERIAS` e `AUTOMOVEL`, o texto do PDF (`GET /api/v1/reservations/{id}/registration-card/pdf`) contém `Lazer/Férias` e `Automóvel` e não contém `código FNRH`; o ESC/POS (pela impressão numa impressora térmica do teste, decodificado em IBM850, como os testes da M5 E11) contém `Lazer/Férias` e `Automóvel`.
  - `a code outside the catalog comes out raw with the FNRH note`: com `LAZER` e `AUTOMOVEL`, a ficha sai `200`, com `LAZER (código FNRH)` e `Automóvel`.
  - `a label changed in the catalog shows on the next card`: troca o rótulo de `NEGOCIOS` para `Negócios e eventos`, gera a ficha e vê o novo; devolve o original no `finally`.
  - `a label with control characters comes out clean`: rótulo de `SAUDE` trocado para `Saúde‮\u0007` → a ficha não contém `‮` nem `\u0007` e contém `Saúde`; devolve o original.
  - D-E15-6: atualize `RegistrationCardApiTest` (`AUTOMOVEL (código FNRH)` → `Automóvel`, e `shouldNotContain "AUTOMOVEL (código FNRH)"`); nenhuma outra asserção muda.
- [ ] **Step 2:** `./gradlew test --tests 'com.zeny.hotel.frontdesk.RegistrationCard*'` → vermelho.
- [ ] **Step 3: Implementar** D-E15-3 no serviço e nos dois templates.
- [ ] **Step 4:** `./gradlew test --tests 'com.zeny.hotel.frontdesk.*' --tests 'com.zeny.hotel.printing.*'` → verde (os testes de RN-19 e documento mascarado da M5 E11 passam sem mudança).
- [ ] **Step 5: Commit** `feat(frontdesk): ficha com os rótulos da FNRH de motivo de viagem e meio de transporte` com `Soul: desenvolvedor`.

### Task 3: Descrição de `frontdesk.stays.checkin` e o README

Estimativa: 30 min · Depende de: Task 2 · Risco: alto (autorização)

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/frontdesk/FrontdeskPermissions.kt` (descrição de D-E15-5; KDoc de `STAYS_CHECK_IN`)
- Modify: `README.md` (seção "Documentos impressos": a ficha mostra o rótulo do motivo de viagem e do meio de transporte, ou o código com "(código FNRH)" quando fora do catálogo, em vez de "os códigos FNRH como guardados"; a permissão descrita como a que abre a ficha)
- Test: `src/test/kotlin/com/zeny/hotel/frontdesk/FrontdeskCheckInPermissionDescriptionTest.kt` (`@IntegrationTest`)

- [ ] **Step 1: Testes que falham:**
  - `the permission catalog describes the registration card`: `GET /api/v1/permissions` traz `frontdesk.stays.checkin` com a descrição de D-E15-5, `sensitive = false` e os mesmos escopos de antes.
  - `the role templates and grants do not change`: os modelos `MANAGER` e `RECEPTIONIST` concedem `frontdesk.stays.checkin` como antes, e nenhum outro modelo passa a concedê-la (compare com a declaração de `FrontdeskPermissions`).
  - `a description change keeps open sessions and grants`: com um token de recepcionista obtido antes, rode `PermissionCatalogSynchronizer.synchronize(declarações com a descrição antiga de frontdesk.stays.checkin)` e depois `synchronize()` (a descrição nova): o resultado da segunda traz `frontdesk.stays.checkin` em `updated`, e o token antigo continua abrindo `GET /api/v1/reservations/{id}/registration-card/pdf` (`200`), com as concessões da role iguais. Termine com `synchronize()` das declarações reais.
- [ ] **Step 2:** `./gradlew test --tests 'com.zeny.hotel.frontdesk.FrontdeskCheckInPermissionDescriptionTest'` → vermelho.
- [ ] **Step 3: Implementar** D-E15-5 e o README.
- [ ] **Step 4:** `./gradlew test --tests 'com.zeny.hotel.frontdesk.*' --tests 'com.zeny.hotel.iam.*'` e `./gradlew checkOpenApi` → verde (o contrato não muda).
- [ ] **Step 5: Commit** `feat(frontdesk): a permissão do check-in descreve a ficha de registro` com `Soul: desenvolvedor`.

## Validação da etapa

- `./gradlew build` → BUILD SUCCESSFUL (inclui `ModularityTest` e `checkOpenApi`).
- `.github/scripts/check-api-breaking.sh main` → nenhuma mudança `ERR` em `/api/v1`; `api/openapi.yaml` sem diff.
- Critérios de aceite da E15: (1) Task 2; (2) Task 2 e D-E15-6; (3) Tasks 1 e 2; (4) Task 1 e ARCHITECTURE §3.1; (5) Task 3; (6) Task 2 (testes da M5 E11 sem mudança de asserção de RN-19); (7) Task 3 e `checkOpenApi`.
