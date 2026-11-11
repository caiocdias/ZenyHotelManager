# M5 E11 — Documentos: ficha, extrato e confirmação: plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** gerar a ficha de registro (`frontdesk`), o extrato de conta (`billing`, RF-CTA-12) e a confirmação de reserva (`reservations`, RF-RES-12 com os textos da RN-11) em PDF A4 e em ESC/POS, para download (RF-NOT-04) e para impressão pela fila da E10 (RF-NOT-03), no modelo dono + `printing` (D2).

**Architecture:** o `printing` ganha a API pública de documentos: cada módulo dono registra os próprios templates (Thymeleaf, um HTML para o PDF e um texto para o ESC/POS, versionados nos recursos do módulo) num catálogo conferido na partida, monta um modelo só de mapas, listas e valores simples e chama `PrintingApi.render` (PDF ou ESC/POS) ou `PrintingApi.print` (cria o `PrintJob` no formato da impressora). O `printing` limpa o texto do modelo, compõe o cabeçalho e o rodapé padrão com os dados do hotel (`HotelApi.profile()`, novo) e o logotipo (`FilesApi.readContent`, novo, que confere finalidade e dono), converte o HTML em PDF (openhtmltopdf sobre PDFBox, sem recurso externo) ou o texto em bytes ESC/POS (IBM850, 48 colunas, corte). Os donos expõem `GET …/pdf` e `POST …/print` com a própria permissão; o extrato descreve o dono da conta por uma porta do `billing` implementada pelo `reservations` (inversão por porta, sem aresta nova).

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1 (Thymeleaf 3.1.5 pelo BOM, `thymeleaf-spring6` sem o starter), openhtmltopdf (fork `io.github.openhtmltopdf`) com PDFBox 3, Spring MVC, springdoc 3.1, Testcontainers.

**Spec:** `docs/roadmaps/roadmap-m5-recepcao-fnrh.md`, seção "E11" (e D2, H8, H11, H17, H18 em "Hipóteses e decisões em aberto", "Restrições e invariantes" e os handoffs de E02 e E10), `docs/SPEC.md` RF-REC-01, RF-CTA-12, RF-RES-12, RN-11, RF-CFG-01/02, RF-NOT-03/04, `docs/ARCHITECTURE.md` §3.1, §3.2, §7, §8.3 e §14, e as notas do brain `ZenyHotelManager - Módulos hotel e staff`, `ZenyHotelManager - Módulo files`, `ZenyHotelManager - Convenções de API`, `ZenyHotelManager - Módulo billing (caixa, pagamentos, caução e encerramento)`, `ZenyHotelManager - Hospedagem e recepção`, `ZenyHotelManager - Módulo guests` e a captura pendente `atualização m5-recepcao-fnrh E10` (nota nova `ZenyHotelManager - Módulo printing`).

## Decisões desta etapa

| # | Decisão | Por quê | Custo se errada |
|---|---|---|---|
| D-E11-1 | **Bibliotecas** (H8): `org.thymeleaf:thymeleaf-spring6` (versão do BOM do Boot, 3.1.5; **sem** `spring-boot-starter-thymeleaf`, para não registrar `ViewResolver` no MVC da API) com um `SpringTemplateEngine` próprio do `printing` (SpEL; `ClassLoaderTemplateResolver`); `io.github.openhtmltopdf:openhtmltopdf-pdfbox` na última versão estável do Maven Central no dia (o fork mantido; o `com.openhtmltopdf` original está abandonado), que traz o PDFBox 3; `org.apache.pdfbox:pdfbox` declarado só em `testImplementation`, na versão que o openhtmltopdf resolve, para os testes extraírem o texto. Versões no catálogo, lockfiles regravados (`./gradlew dependencies --write-locks`) e `osv-scanner` sem High/Critical novo. Descartadas: iText (AGPL), JasperReports (pesado, XML próprio), PDFBox direto (layout à mão em cada documento), FreeMarker (já está no classpath dos plugins, mas a linha do Boot para templates é o Thymeleaf e o escape padrão do Thymeleaf é o de HTML). | H8; um template HTML por documento é revisável e o visual fica no `printing`. | Troca local ao `printing`. Tamanho da imagem Docker: medido na Task 11. **Vai às "Decisões para o usuário".** |
| D-E11-2 | **API de documentos no `printing`** (raiz): `PrintingApi.render(document: DocumentRequest, format: DocumentFormat): RenderedDocument` e `PrintingApi.print(document: DocumentRequest, printerId: UUID): UUID` (o id do job; o formato sai do `PrinterKind`: `A4_PDF` → PDF, `THERMAL_ESCPOS` → ESC/POS; o `documentType` do job é o código do template). Templates registrados pelo dono como bean `ModuleDocumentTemplates(module, templates)`, cada `DocumentTemplate(code, title, htmlTemplate, textTemplate)`, com `code` no padrão do `documentType` da E10 (`<módulo>.<documento>`, prefixo = o módulo) e os caminhos no classpath **do pacote de recursos do dono** (`templates/<módulo>/…`). O `DocumentTemplateCatalog` recusa a partida com código repetido, prefixo de outro módulo, código fora do padrão ou template inexistente. O modelo é `Map<String, Any?>` com folhas `String`, `Number`, `Boolean`, `LocalDate`, `LocalTime`, `Instant`, `Money`, `Percent` ou `null`, e mapas e listas aninhados; outro tipo é `IllegalArgumentException` (erro de programação). O `printing` não conhece os modelos de negócio (D2). | D2 e o escopo da E11 ("`render(template, model, format)`", "`print(template, model, printerId)`", templates registrados pelos donos). Um modelo de mapas deixa o `printing` limpar todo texto num lugar só. | Templates sem tipagem: um campo renomeado no dono só aparece no teste do dono. |
| D-E11-3 | **Cabeçalho, rodapé e formatação padrão:** o `printing` envolve o corpo do dono num layout próprio (`templates/printing/layout.html` e `layout.txt`): cabeçalho com nome fantasia, razão social, CNPJ formatado, Cadastur, inscrição municipal, endereço e o logotipo; rodapé com o título do documento, "Emitido em dd/MM/yyyy HH:mm" no fuso do hotel e "Página X de Y" (CSS paginado do openhtmltopdf). O corpo do dono entra por fragmento (`th:insert`/`th:replace` com o template do catálogo), **nunca** por `th:utext`; nenhum template usa `th:utext`. Utilitário `fmt` no contexto (datas `dd/MM/yyyy`, horas `HH:mm`, instantes no fuso do hotel, `Money` como `R$ 1.234,56`, `Percent` como `10%`, CNPJ `00.000.000/0000-00`). Hotel não configurado (`profile()` nulo): cabeçalho só com "Hotel" e sem logotipo, sem erro. | Risco do roadmap: "o `printing` oferece o cabeçalho e o rodapé padrão, e os módulos só preenchem o corpo". | Visual revisável. |
| D-E11-4 | **Texto do modelo limpo:** antes de renderizar, toda `String` do modelo perde os caracteres de controle (Unicode `Cc`, inclusive `\n` e `\t`, trocados por espaço) e de formatação (`Cf`: marcas bidi, largura zero, `U+FEFF`), com os espaços repetidos colapsados. No PDF (fontes base-14, WinAnsi) o caractere que o `windows-1252` não codifica vira `?`; no ESC/POS (página de código IBM850, `ESC t 2`) o texto é decomposto (NFD), perde as marcas combinantes só onde o IBM850 não codifica o caractere composto, e o que sobrar vira `?`. Sem fonte embutida. | Nota do brain (`Módulo guests`): "a renderização da ficha e dos PDFs precisa tratar" controle e formatação Unicode; o escape do Thymeleaf protege contra HTML. | Nome em escrita não latina sai com `?`; embutir uma fonte TTF (~700 KB) resolve depois. **Vai às "Decisões para o usuário".** |
| D-E11-5 | **Sem recurso externo no PDF:** o renderizador usa um `FSUriResolver`/`FSStreamFactory` que só aceita `data:` URIs; qualquer `http:`, `https:`, `file:`, `jar:` ou caminho relativo é recusado sem acesso (sem SSRF nem leitura de arquivo local). O logotipo entra como `data:image/png;base64,…`/`image/jpeg`. | ARCHITECTURE §8.3; defesa em profundidade além do escape. | — |
| D-E11-6 | **ESC/POS:** `ESC @`, `ESC t 2` (IBM850), cabeçalho centralizado (`ESC a 1`) só com texto (nome fantasia, razão social, CNPJ, Cadastur, endereço; **sem logotipo** na térmica), corpo do template de texto quebrado por palavra em 48 colunas (80 mm, fonte A; constante `ESC_POS_COLUMNS`, sem propriedade enquanto não houver impressora de 58 mm), rodapé com o título e o instante de emissão, 4 avanços de linha e corte parcial (`GS V 66 0`). Linha que o template marca com `---` vira uma régua de `-` na largura. | RF-NOT-03 (térmicas ESC/POS); critério "ESC/POS ou PDF conforme o tipo da impressora". | O logotipo na térmica (raster `GS v 0`) fica para o M8 (comandas), se o hotel pedir. |
| D-E11-7 | **Logotipo pela `FilesApi`** (H18): `FilesApi.readContent(id: FileId, purpose: FilePurposeCode, ownerEntityId: String): FileContent` devolve o conteúdo de um anexo **não apagado, da finalidade e vinculado ao dono** informados (`404 FILE_NOT_FOUND`, `422 FILE_PURPOSE_MISMATCH`, `409 FILE_ALREADY_ATTACHED` como nas outras operações; órfão também `409`), conferindo o SHA-256 gravado. **Recusa anexo sensível** (`422 FILE_SENSITIVE_CONTENT_NOT_READABLE`): o único consumidor é o logotipo, e a leitura de um sensível pelo servidor precisaria de trilha própria; quem precisar dela depois a acrescenta com a trilha. Sem trilha (leitura de anexo comum, como a URL direta ao bucket). O `printing` lê o logotipo com `HotelFilePurposes.LOGO` e o id do hotel de `HotelApi.profile()`. Logotipo `WEBP` (aceito pela finalidade `hotel.logo`, mas sem leitor no openhtmltopdf/JDK), anexo inexistente ou falha do armazenamento: documento **sem logotipo**, com `WARN` no log (sem id de hóspede), nunca erro. | H18 decide "a `FilesApi` ganha a leitura do conteúdo (decifrando o sensível)"; a nota da E11 pede que a leitura "não sirva anexo sem conferir quem chama". Recusar o sensível é mais estreito que a H18 e não tira nada da E11. | Desvio da letra da H18 (sem decifrar o sensível). **Vai às "Decisões para o usuário".** |
| D-E11-8 | **`HotelApi.profile(): CurrentHotelProfile?`** com `data class CurrentHotelProfile(val hotelId: UUID, val profile: HotelProfile)`; `null` enquanto o hotel não está configurado (`Hotel.configured` falso). O `HotelProfile` existente (tipo da raiz) não muda. | H18 ("a `HotelApi` ganha `profile()` com os dados cadastrais e o `logoFileId`"); o id do hotel é o dono do vínculo do logotipo. | — |
| D-E11-9 | **Caminhos** (decisão aberta da E11): rotas próprias, não negociação por `Accept`. `GET /api/v1/reservations/{id}/registration-card/pdf` e `POST /api/v1/reservations/{id}/registration-card/print` (`frontdesk`); `GET /api/v1/folios/{id}/statement/pdf?audience=` e `POST /api/v1/folios/{id}/statement/print?audience=` (`billing`; `audience` padrão **`GUEST`** nos dois, porque o documento vai ao hóspede; o JSON segue com padrão `INTERNAL`); `GET /api/v1/reservations/{id}/confirmation/pdf` e `POST /api/v1/reservations/{id}/confirmation/print` (`reservations`). `GET`: `200 application/pdf` (`format: binary` no contrato), `Content-Disposition: attachment; filename="<documento>-<código>.pdf"` (`ficha`, `extrato`, `confirmacao`; código da reserva ou número da conta), `Cache-Control: no-store`. `POST …/print`: corpo `PrintDocumentRequest {printerId}`, `@Idempotent`, `202` com `PrintJobAccepted {printJobId}` (DTOs na raiz do `printing`, reaproveitados pelos três). Erros do `printing` passam como estão (`400 UNKNOWN_PRINTER`, `422 PRINTER_INACTIVE`). Erro num `GET …/pdf` sai como `application/problem+json` mesmo com `Accept: application/pdf`. | Convenções de API: recurso por subcaminho, um `operationId` por operação e o contrato descrevendo a resposta binária; negociação por `Accept` no mesmo caminho daria duas respostas num `operationId` e confunde os geradores de SDK. `@Idempotent` em POST com efeito externo (ARCHITECTURE §7). | Rotas publicadas não mudam. **Vai às "Decisões para o usuário"** (decisão aberta da E11). |
| D-E11-10 | **Permissões:** a ficha usa `frontdesk.stays.checkin` (quem faz o check-in imprime a ficha; o `frontdesk` não tem permissão de leitura e criar uma só para isto não muda quem a tem); o extrato, `billing.folios.read`; a confirmação, `reservations.reservations.read`. As mesmas no `GET` e no `POST …/print`; o `printing` não confere permissão (D2). | Escopo da E11 e D2. | Um código novo depois (`frontdesk.stays.read`) muda os modelos de role. |
| D-E11-11 | **O que a ficha mostra** (decisão aberta da E11): estadia (código da reserva, UH, chegada, saída, entrada efetiva, adultos e crianças) e, por hóspede, o nome (o **nome social** quando houver, senão o nome civil), nacionalidade, data de nascimento, endereço se houver (`GuestIdentification.address`, acrescentado), cada documento **mascarado** (tipo, país emissor, `maskedNumber`; nunca decifrado), o motivo de viagem e o meio de transporte **como os códigos FNRH guardados**, com o rótulo "(código FNRH)", e uma linha de assinatura. Nada de RN-19 (raça/cor, deficiência), número inteiro de documento, contato, notas ou restrição. Situações: `CONFIRMED`, `IN_HOUSE` e `CHECKED_OUT`; outras → `409 REGISTRATION_CARD_UNAVAILABLE`. | H11 ("o mascarado também serve à ficha"); RN-19. Os rótulos dos códigos de viagem vivem no `fnrh`, uma camada acima do `frontdesk`. | Os rótulos exigem uma porta `frontdesk` → implementada pelo `fnrh` (inversão por porta): **decisão de arquitetura que vai ao usuário**; o nome civil ao lado do social também. |
| D-E11-12 | **Extrato:** o corpo vem do `FolioStatementService.statement(id, audience)` existente (sem refazer cálculo): número e situação da conta, abertura, pagador (empresa pela `GuestsApi.company`, se houver), o dono descrito, as linhas por dia com o saldo do dia, os totais, o saldo e as cauções; no `INTERNAL`, também as reaberturas e o bloco interno (usuário, motivo) das linhas. O dono é descrito por uma **porta do `billing`**, `FolioOwnerDescriber` (raiz: `val ownerType: FolioOwnerType; fun describe(ownerId: UUID): FolioOwnerDescription?`, com `FolioOwnerDescription(label: String, guestName: String?, roomNumber: String?, arrival: LocalDate?, departure: LocalDate?)`), implementada pelo `reservations` para `RESERVATION` (Task 10); dono sem descritor: só o tipo. | RF-CTA-12; o `billing` não conhece reservas (ARCHITECTURE §3.1, "inversão por porta"); a aresta `reservations` → `billing` já existe. | Uma porta nova; sem ela o extrato do hóspede não traria nome nem UH. |
| D-E11-13 | **Confirmação:** código da reserva, situação, titular (nome social ou civil), tipo de UH, plano, chegada e saída, noites, adultos e idades das crianças, diária por noite e total, os termos de cancelamento e de garantia **copiados na reserva** (RN-15) e, da `HotelApi.policy()`, os horários de check-in e check-out, a janela de arrumação, as ofertas de early/late com o preço (ou "não oferecido") e `stayPolicyText`/`additionalPolicyText` (RN-11). Situações: `PROVISIONAL`, `CONFIRMED`, `IN_HOUSE` e `CHECKED_OUT`; `CANCELLED` e `NO_SHOW` → `409 CONFIRMATION_UNAVAILABLE`. | RF-RES-12, RN-11, RF-CFG-02. | — |
| D-E11-14 | **Trilha:** o `print` de cada dono é caso de uso `@Audited` (`frontdesk.registration-card.print`, `billing.statement.print`, `reservations.confirmation.print`) com retrato só de ids (reserva ou conta, impressora, job), nunca texto do documento; o `GET …/pdf` é leitura, sem trilha (como o extrato JSON). | `LayerRules.writeUseCasesAreAudited`; RN-13. | — |

## Global Constraints

- Grafo (ARCHITECTURE §3.1 e §3.2): `printing` depende só de `shared`, `hotel` e `files` (`allowedDependencies` e teste exato no `ModularityTest`) e nunca escuta eventos; `frontdesk`, `billing` e `reservations` ganham a aresta para `printing` (já na tabela §3.1 para os três) na task que a usa, com o `ModularityTest` de cada um; nenhum módulo lê tabela de outro schema; nenhum importa `iam`.
- O `printing` não conhece modelos de negócio: só `DocumentRequest(templateCode, model)`; cada dono monta os dados, confere a **própria** permissão e chama o `printing` (D2).
- RN-19 e RN-13: nenhum documento, log, trilha ou resposta de erro contém raça/cor, deficiência ou número inteiro de documento; a ficha só usa `GuestsApi.identification` (mascarado); `GuestsApi.fnrhData` nunca é chamada fora do `fnrh`.
- Nenhum template usa `th:utext`; todo texto do modelo passa pela limpeza de D-E11-4; nenhum recurso externo é lido na renderização (D-E11-5).
- RN-01: datas de negócio pela `HotelApi`, instantes pelo `Clock`, exibidos no fuso do hotel.
- Estrutura do módulo: tipos da API pública na raiz; `domain` sem Spring; escrita da `application` `@Audited` + `@Transactional`; `web` só com a `application` e as raízes.
- Convenções de API: `@ApiV1Controller`, `@Operation(operationId)` único, `@Tag` do módulo dono, RFC 9457, `@Idempotent` nos `POST …/print`; resposta binária com `Content(mediaType = "application/pdf", schema = Schema(type = "string", format = "binary"))` (modelo: `FilesController.content`); `api/openapi.yaml` só por `./gradlew updateOpenApi`.
- Lockfiles: mudança de catálogo ou dependência regrava `gradle.lockfile` e `buildscript-gradle.lockfile` no mesmo commit (`./gradlew dependencies --write-locks`).
- Limite de tarefa: até 3 arquivos de produção Kotlin/SQL/YAML (templates `.html`/`.txt`, `api/openapi.yaml`, lockfiles e a linha de `allowedDependencies` do `…Module.kt` não contam).
- Testes: banco compartilhado (cada teste cria os próprios dados; quem muda os dados do hotel pela API devolve o original num `finally`); textos dos PDFs conferidos extraindo o texto com o `PDFTextStripper` do PDFBox; nenhum `Thread.sleep`.
- Documentação, mensagens e commits em pt-BR; código, API e banco em inglês; commits com o trailer `Soul: <soul>`.
- `./gradlew build` verde e `.github/scripts/check-api-breaking.sh main` sem `ERR` em `/api/v1`.

## Review Focus

1. **`GET …/pdf` com erro e `Accept: application/pdf`** (reserva inexistente, sem permissão): o cliente recebe `404`/`403` em `application/problem+json`, nunca `406` nem `500` (teste na Task 8).
2. **Texto hostil no perfil** (nome com `<script>`, `<img src="http://…">`, `&`, marcas bidi `U+202E`, largura zero, `\n`): o PDF e o ESC/POS saem com o texto literal limpo, sem recurso externo lido e sem falha (testes nas Tasks 1, 2 e 6).
3. **Caracteres fora do WinAnsi/IBM850** (`Łukasz`, `Ñandú`, `李`, emoji): documento gerado, o que não cabe vira `?`, os acentos do português ficam (testes nas Tasks 1 e 2).
4. **Hotel não configurado, logotipo `WEBP`, logotipo apagado ou armazenamento fora do ar:** o documento sai, sem logotipo, com `WARN` (testes nas Tasks 4 e 6).
5. **Extrato longo** (80 lançamentos em vários dias): PDF com várias páginas e "Página X de Y", abaixo de 5 MiB; no ESC/POS as linhas longas quebram em 48 colunas (teste na Task 8).

---

### Task 1: Bibliotecas e conversão HTML → PDF

**Estimativa:** 40 min. **Depende de:** nenhuma. **Risco:** alto (cadeia de suprimentos, *parsing*).

**Files:**
- Modify: `gradle/libs.versions.toml` (`openhtmltopdf`, `thymeleaf-spring6` sem versão, `pdfbox` de teste), `build.gradle.kts` (`implementation` dos dois, `testImplementation(libs.pdfbox)`), `gradle.lockfile`, `buildscript-gradle.lockfile` (regravados)
- Create: `src/main/kotlin/com/zeny/hotel/printing/infrastructure/HtmlPdfRenderer.kt`
- Test: `src/test/kotlin/com/zeny/hotel/printing/HtmlPdfRendererTest.kt`, `src/test/kotlin/com/zeny/hotel/testsupport/PdfText.kt` (`fun pdfText(bytes: ByteArray): String` e `fun pdfPageCount(bytes): Int` e `fun pdfImageCount(bytes): Int`, pelo PDFBox; usado pelas Tasks 6, 8, 9 e 10)

**Interfaces:**
- Produces: `class HtmlPdfRenderer { fun render(xhtml: String): ByteArray }` (`internal` ao módulo; base-14, resolvedor que só aceita `data:` — D-E11-5); `PdfText.kt` acima.

- [ ] **Step 1:** testes (unitários, sem Spring): um XHTML com "Ficha de hóspede — João Ação" vira PDF (`%PDF-` no início) cujo texto extraído contém a frase; `<img src="http://127.0.0.1:<porta>/x.png">` com um `HttpServer` do JDK local contando acessos e `<img src="file:///etc/hostname">` geram PDF e o servidor recebe **0** acessos; `<img src="data:image/png;base64,…">` (PNG 2×2 gerado no teste) gera 1 imagem (`pdfImageCount`); texto com `Łukasz 李 😀` gera PDF sem exceção (Review Focus 3); 300 parágrafos geram mais de uma página (`pdfPageCount > 1`).
- [ ] **Step 2:** ver falhar: `./gradlew test --tests 'com.zeny.hotel.printing.HtmlPdfRendererTest'`.
- [ ] **Step 3:** catálogo, build e `HtmlPdfRenderer` (`PdfRendererBuilder` com `useFastMode()`, `useUriResolver`/`useHttpStreamImplementation` que recusam tudo que não é `data:`; nenhum `withUri` de base); `./gradlew dependencies --write-locks`.
- [ ] **Step 4:** ver passar; `./gradlew build -x test` (detekt, ktlint, lockfiles estritos); anotar no relatório a versão escolhida do openhtmltopdf e do PDFBox e o resultado de `osv-scanner scan source -r --no-ignore .` só para os pacotes novos.
- [ ] **Step 5:** commit "Acrescenta openhtmltopdf e Thymeleaf e a conversão de HTML em PDF sem recurso externo (M5 E11, Task 1)".

---

### Task 2: Limpeza do texto e codificação ESC/POS

**Estimativa:** 35 min. **Depende de:** nenhuma. **Risco:** alto (entrada externa, codificação).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/printing/domain/DocumentText.kt` (`object DocumentText { fun clean(text: String): String; fun toWinAnsi(text: String): String }`)
- Create: `src/main/kotlin/com/zeny/hotel/printing/domain/EscPosDocument.kt` (`class EscPosDocument(columns: Int) { fun encode(header: List<String>, body: String, footer: List<String>): ByteArray }`)
- Test: `src/test/kotlin/com/zeny/hotel/printing/DocumentTextTest.kt`, `EscPosDocumentTest.kt`

**Interfaces:**
- Produces: `DocumentText.clean` (D-E11-4: `Cc` → espaço, `Cf` removido, espaços colapsados, sem `trim` de linha do meio), `DocumentText.toWinAnsi` (o que o `windows-1252` não codifica → `?`); `EscPosDocument.encode` (D-E11-6; `columns` de 32 a 64, senão `IllegalArgumentException`).

- [ ] **Step 1:** testes: `clean("Jo‮ão​\nSilva\t")` = `"Joao Silva"`-equivalente sem as marcas (`"João Silva"`); `toWinAnsi("Łukasz Ñandú 李")` mantém `Ñandú`, troca `Ł` e `李` por `?`; `encode` começa com `1B 40 1B 74 02`, termina com `1D 56 42 00`, codifica `ã õ ç é` em IBM850 (`C6 E4 87 82`), troca `李` e emoji por `?`, quebra uma linha de 120 caracteres em linhas de no máximo 48 por palavra (palavra maior que a largura é cortada), centraliza o cabeçalho (`1B 61 01` … `1B 61 00`), troca a linha `---` por 48 `-`; `columns = 20` falha.
- [ ] **Step 2–4:** ver falhar (`./gradlew test --tests 'com.zeny.hotel.printing.DocumentTextTest' --tests 'com.zeny.hotel.printing.EscPosDocumentTest'`), implementar (charset `IBM850`; `Normalizer.Form.NFD` só para o caractere que o IBM850 não codifica), ver passar.
- [ ] **Step 5:** commit "Acrescenta a limpeza de texto e a codificação ESC/POS dos documentos (M5 E11, Task 2)".

---

### Task 3: `HotelApi.profile()`

**Estimativa:** 20 min. **Depende de:** nenhuma. **Risco:** normal.

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/hotel/HotelApi.kt` (`fun profile(): CurrentHotelProfile?` e `data class CurrentHotelProfile(val hotelId: UUID, val profile: HotelProfile)`)
- Modify: `src/main/kotlin/com/zeny/hotel/hotel/application/HotelApiService.kt`
- Test: `src/test/kotlin/com/zeny/hotel/hotel/HotelApiProfileTest.kt`

**Interfaces:**
- Produces: `HotelApi.profile(): CurrentHotelProfile?` (D-E11-8; `@Transactional(readOnly = true)`, sem permissão).

- [ ] **Step 1:** teste (`@IntegrationTest`): depois de `PUT /api/v1/hotel` com dados e logotipo (`FilesTestSupport`), `profile()` devolve o id do hotel, os dados normalizados e o `logoFileId`; o original é devolvido no `finally`. O caso `null` (não configurado) é conferido num teste de unidade do serviço com o `HotelStore` simulado.
- [ ] **Step 2–4:** ver falhar, implementar, ver passar (`./gradlew test --tests 'com.zeny.hotel.hotel.*'`).
- [ ] **Step 5:** commit "Acrescenta HotelApi.profile() com o id do hotel (M5 E11, Task 3)".

---

### Task 4: `FilesApi.readContent`

**Estimativa:** 35 min. **Depende de:** nenhuma. **Risco:** alto (segurança: leitura de conteúdo pelo servidor).

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/files/FilesApi.kt` (método e `class FileContent(val summary: FileSummary, val content: ByteArray)` com `toString` sem o conteúdo)
- Modify: `src/main/kotlin/com/zeny/hotel/files/application/FilesApiService.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/files/FilesErrorCodes.kt` (`FILE_SENSITIVE_CONTENT_NOT_READABLE`, 422)
- Test: `src/test/kotlin/com/zeny/hotel/files/FilesApiReadContentTest.kt`

**Interfaces:**
- Produces: `FilesApi.readContent(id: FileId, purpose: FilePurposeCode, ownerEntityId: String): FileContent` (D-E11-7; `@Transactional(readOnly = true)`, sem trilha, confere o SHA-256 do objeto contra a linha; divergência é `IllegalStateException`, que vira `500`).

- [ ] **Step 1:** testes no contexto dos testes de anexos (`FilesTestSupport`, finalidades de `filesexample`): anexo comum vinculado ao dono devolve os bytes enviados; outro dono → `409 FILE_ALREADY_ATTACHED`; órfão → `409`; outra finalidade → `422 FILE_PURPOSE_MISMATCH`; apagado ou inexistente → `404 FILE_NOT_FOUND`; sensível vinculado → `422 FILE_SENSITIVE_CONTENT_NOT_READABLE` e nenhum objeto lido; objeto adulterado no bucket (sobrescrito pelo cliente S3 do teste) → exceção e nenhum conteúdo devolvido; nenhuma entrada nova na trilha.
- [ ] **Step 2–4:** ver falhar, implementar, ver passar (`./gradlew test --tests 'com.zeny.hotel.files.*'`).
- [ ] **Step 5:** commit "Acrescenta a leitura do conteúdo de anexo comum pela FilesApi (M5 E11, Task 4)".

---

### Task 5: Catálogo de templates e motor

**Estimativa:** 40 min. **Depende de:** Task 1. **Risco:** alto (motor de templates).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/printing/PrintingDocuments.kt` (raiz: `DocumentTemplate(code: String, title: String, htmlTemplate: String, textTemplate: String)`, `interface ModuleDocumentTemplates { val module: String; val templates: List<DocumentTemplate> }`, `DocumentRequest(templateCode: String, model: Map<String, Any?>)` com `toString` sem o modelo, `enum class DocumentFormat { PDF, ESCPOS }`, `class RenderedDocument(val format: DocumentFormat, val mediaType: String, val content: ByteArray)` com `toString` sem o conteúdo, `data class PrintDocumentRequest(val printerId: UUID)` e `data class PrintJobAccepted(val printJobId: UUID)` com `@Schema`)
- Create: `src/main/kotlin/com/zeny/hotel/printing/application/DocumentTemplateCatalog.kt` (catálogo conferido na partida + `SpringTemplateEngine` com dois `ClassLoaderTemplateResolver`, HTML e TEXT, prefixo `templates/`, cache ligado, `UTF-8`)
- Test: `src/test/kotlin/com/zeny/hotel/printing/DocumentTemplateCatalogTest.kt`, `src/test/kotlin/printingexample/PrintingExamplesConfiguration.kt` e `src/test/resources/templates/printingexample/` (finalidade de teste, como o `filesexample`)

**Interfaces:**
- Consumes: Task 1 (`thymeleaf-spring6` no classpath).
- Produces: os tipos da raiz acima; `DocumentTemplateCatalog.template(code): DocumentTemplate` (código desconhecido → `IllegalArgumentException`) e `DocumentTemplateCatalog.engine: SpringTemplateEngine`.

- [ ] **Step 1:** testes de unidade do catálogo: código repetido, código `frontdesk.x` registrado pelo módulo `billing`, código fora de `[a-z][a-z0-9]*(\.[a-z][a-z0-9-]*)+` e template inexistente no classpath recusam a criação com mensagem que cita o código; o template de exemplo renderiza `${guest.name}` com `<b>&` escapado e uma lista com `th:each`; um template que use `th:utext` é recusado pelo teste `DocumentTemplatesUseNoUtextTest`, que varre `src/main/resources/templates/**` (vale para as Tasks 6, 8, 9 e 10).
- [ ] **Step 2–4:** ver falhar, implementar, ver passar (`./gradlew test --tests 'com.zeny.hotel.printing.*'`).
- [ ] **Step 5:** commit "Acrescenta o catálogo de templates de documentos do printing (M5 E11, Task 5)".

---

### Task 6: Renderização e impressão pela `PrintingApi`

**Estimativa:** 45 min. **Depende de:** Tasks 2, 3, 4 e 5. **Risco:** alto (núcleo da etapa).

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/printing/PrintingApi.kt` (`render` e `print`, D-E11-2)
- Create: `src/main/kotlin/com/zeny/hotel/printing/application/DocumentRenderingService.kt` (modelo validado e limpo, contexto com `document`, `hotel`, `logo`, `fmt` e `issuedAt`; layout; PDF pelo `HtmlPdfRenderer`; ESC/POS pelo `EscPosDocument(ESC_POS_COLUMNS)`; `print` lê o `PrinterKind` e chama o `enqueue` existente com `documentType` = código)
- Modify: `src/main/kotlin/com/zeny/hotel/printing/PrintingModule.kt` (`allowedDependencies = ["shared", "hotel", "files"]`)
- Create: `src/main/resources/templates/printing/layout.html`, `layout.txt`
- Modify: `src/test/kotlin/com/zeny/hotel/ModularityTest.kt` (`printing` → `shared`, `hotel`, `files`)
- Test: `src/test/kotlin/com/zeny/hotel/printing/DocumentRenderingTest.kt`

**Interfaces:**
- Consumes: `HotelApi.profile()` (Task 3), `FilesApi.readContent` (Task 4), catálogo (Task 5), `DocumentText`/`EscPosDocument` (Task 2), `HtmlPdfRenderer` e `PdfText` (Task 1).
- Produces: `PrintingApi.render(document: DocumentRequest, format: DocumentFormat): RenderedDocument` e `PrintingApi.print(document: DocumentRequest, printerId: UUID): UUID`, ambos sem permissão; `print` herda os erros do `enqueue` (`UNKNOWN_PRINTER`, `PRINTER_INACTIVE`, `PRINT_CONTENT_TOO_LARGE`).

- [ ] **Step 1:** testes (`@IntegrationTest`, template de exemplo da Task 5): o PDF traz a razão social, o CNPJ formatado, o Cadastur e o endereço que o teste gravou por `PUT /api/v1/hotel` (original devolvido no `finally`), o título, "Emitido em" e "Página 1 de 1", e uma imagem com o logotipo PNG enviado; o mesmo com logotipo WEBP, com o logotipo apagado e com o hotel sem configuração (se o banco compartilhado não permitir desconfigurar, pelo serviço com `HotelApi` simulado) sai sem imagem e sem erro (Review Focus 4); nome `"<script>alert(1)</script> & ‮João"` aparece literal e sem a marca bidi no texto extraído (Review Focus 2); `print` numa impressora `A4_PDF` cria job `PENDING` com `documentType` = código e conteúdo `%PDF-` (lido pelo agente de teste como na E10), e numa `THERMAL_ESCPOS` com conteúdo começando por `1B 40`; impressora desativada → `422 PRINTER_INACTIVE`; modelo com um tipo não permitido (`UUID` cru, um objeto qualquer) → `IllegalArgumentException`.
- [ ] **Step 2–4:** ver falhar, implementar, ver passar (`./gradlew test --tests 'com.zeny.hotel.printing.*' --tests 'com.zeny.hotel.ModularityTest'`).
- [ ] **Step 5:** commit "Acrescenta a renderização em PDF e ESC/POS e a impressão de documentos à PrintingApi (M5 E11, Task 6)".

---

### Task 7: Endereço na identificação do hóspede

**Estimativa:** 15 min. **Depende de:** nenhuma. **Risco:** normal.

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/guests/GuestsApi.kt` (`GuestIdentification` ganha `val address: Address? = null`, fora do `toString`)
- Modify: a implementação de `GuestsApi.identification` (o serviço da `application` do `guests` que a monta)
- Test: o teste existente de `identification` (procure `identification(` em `src/test/kotlin/com/zeny/hotel/guests/`)

**Interfaces:**
- Produces: `GuestIdentification.address` (o endereço do perfil corrente; num mesclado, o do sobrevivente; anonimizado: `null`).

- [ ] **Step 1:** teste: perfil com endereço devolve o endereço; mesclado devolve o do sobrevivente; anonimizado devolve `null`; `toString` não contém a rua.
- [ ] **Step 2–4:** ver falhar, implementar, ver passar (`./gradlew test --tests 'com.zeny.hotel.guests.*'`).
- [ ] **Step 5:** commit "Acrescenta o endereço à identificação do hóspede para a ficha (M5 E11, Task 7)".

---

### Task 8: Extrato em PDF e impressão (`billing`)

**Estimativa:** 45 min. **Depende de:** Task 6. **Risco:** alto (dado pessoal, contrato público).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/billing/BillingDocuments.kt` (raiz: a porta `FolioOwnerDescriber` e `FolioOwnerDescription` de D-E11-12, o código `billing.statement` e o bean de templates)
- Create: `src/main/kotlin/com/zeny/hotel/billing/application/FolioStatementDocuments.kt` (`pdf(id, audience)` e `print(id, audience, printerId)`, `@Audited("billing.statement.print")` (constante no objeto de auditoria do `billing`) + `@Transactional` no `print`, permissão `billing.folios.read`; modelo a partir do `FolioStatementService` existente)
- Create: `src/main/kotlin/com/zeny/hotel/billing/web/FolioStatementDocumentsController.kt` (as duas rotas de D-E11-9, `@Tag` do `billing`)
- Create: `src/main/resources/templates/billing/statement.html`, `statement.txt`
- Modify: `src/main/kotlin/com/zeny/hotel/billing/BillingModule.kt` (`printing`), `src/test/kotlin/com/zeny/hotel/ModularityTest.kt` (`billing` → … + `printing`), `api/openapi.yaml` (gerado)
- Test: `src/test/kotlin/com/zeny/hotel/billing/FolioStatementDocumentsApiTest.kt`

**Interfaces:**
- Consumes: `PrintingApi.render/print`, `PrintDocumentRequest`, `PrintJobAccepted` (Tasks 5 e 6); `PdfText` (Task 1).
- Produces: `interface FolioOwnerDescriber { val ownerType: FolioOwnerType; fun describe(ownerId: UUID): FolioOwnerDescription? }` e `data class FolioOwnerDescription(val label: String, val guestName: String?, val roomNumber: String?, val arrival: LocalDate?, val departure: LocalDate?)` (com `toString` sem o nome), implementados na Task 10.

- [ ] **Step 1:** testes (cenário do `FolioStatementApiTest`: lançamentos de dois dias, estorno, pagamento): o PDF `GUEST` (padrão) traz o número da conta, as descrições e os valores das linhas, os totais e o saldo formatados (`R$`), e **não** traz o usuário nem o motivo internos; o `INTERNAL` traz; sem descritor para o tipo do dono, só o tipo; com um `FolioOwnerDescriber` de teste, o nome e a UH aparecem; `POST …/print?audience=GUEST` com `Idempotency-Key` cria job (`202`, `printJobId`) e a repetição devolve o mesmo id; impressora térmica → conteúdo ESC/POS com as linhas quebradas em 48 colunas; usuário sem `billing.folios.read` → `403` nas duas; conta inexistente com `Accept: application/pdf` → `404` em `application/problem+json` (Review Focus 1); 80 lançamentos → PDF com mais de uma página, "Página 2 de" no texto e menos de 5 MiB (Review Focus 5); a trilha do `print` só com ids.
- [ ] **Step 2–4:** ver falhar, implementar, ver passar; `./gradlew updateOpenApi`.
- [ ] **Step 5:** commit "Acrescenta o extrato da conta em PDF e na fila de impressão (M5 E11, Task 8)".

---

### Task 9: Ficha de registro em PDF e impressão (`frontdesk`)

**Estimativa:** 45 min. **Depende de:** Tasks 6, 7 e 8. **Risco:** alto (dado pessoal, RN-19).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/frontdesk/application/RegistrationCardService.kt` (`pdf(reservationId)` e `print(reservationId, printerId)`, `@Audited("frontdesk.registration-card.print")` + `@Transactional` no `print`, permissão `frontdesk.stays.checkin`, situações de D-E11-11 com `FrontdeskErrorCodes.REGISTRATION_CARD_UNAVAILABLE`; dados por `ReservationsApi.reservation`, `RoomsApi.room`, `GuestsApi.guest` e `GuestsApi.identification`; bean de templates `frontdesk.registration-card`)
- Create: `src/main/kotlin/com/zeny/hotel/frontdesk/web/RegistrationCardController.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/frontdesk/FrontdeskErrorCodes.kt`, `FrontdeskModule.kt` (`printing`), `src/test/kotlin/com/zeny/hotel/ModularityTest.kt`, `api/openapi.yaml` (gerado)
- Create: `src/main/resources/templates/frontdesk/registration-card.html`, `registration-card.txt`
- Test: `src/test/kotlin/com/zeny/hotel/frontdesk/RegistrationCardApiTest.kt`

**Interfaces:**
- Consumes: `PrintingApi`, `PrintDocumentRequest`, `PrintJobAccepted` (Tasks 5 e 6); `GuestIdentification.address` (Task 7); `PdfText` (Task 1).

- [ ] **Step 1:** testes (reserva em casa com dois hóspedes pelo apoio do check-in da E02, um com nome social, CPF completo, endereço, raça/cor e deficiência gravadas, e um menor com autorização): o PDF traz o código da reserva, a UH, as datas, o nome social (e não o civil), a nacionalidade, a data de nascimento, o endereço, o CPF mascarado (`***` + sufixo) e os códigos de motivo e meio de transporte com "(código FNRH)"; o texto do PDF e os bytes do ESC/POS (decodificados em IBM850) **não** contêm o CPF completo (com e sem pontuação), o rótulo da raça/cor nem o código da deficiência; reserva `PROVISIONAL` ou cancelada → `409 REGISTRATION_CARD_UNAVAILABLE`; sem `frontdesk.stays.checkin` → `403`; `POST …/print` com `Idempotency-Key` → `202` e job na impressora; a trilha do `print` só com ids; endereço com `\n` e marca bidi sai limpo (Review Focus 2).
- [ ] **Step 2–4:** ver falhar, implementar, ver passar; `./gradlew updateOpenApi`.
- [ ] **Step 5:** commit "Acrescenta a ficha de registro em PDF e na fila de impressão (M5 E11, Task 9)".

---

### Task 10: Confirmação de reserva e descritor do dono da conta (`reservations`)

**Estimativa:** 45 min. **Depende de:** Tasks 8 e 9. **Risco:** alto (contrato público, RN-11).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/reservations/application/ReservationDocuments.kt` (a confirmação: `pdf(id)` e `print(id, printerId)`, `@Audited("reservations.confirmation.print")` + `@Transactional` no `print`, permissão `reservations.reservations.read`, situações de D-E11-13 com `ReservationsErrorCodes.CONFIRMATION_UNAVAILABLE`, bean de templates `reservations.confirmation`; e o `@Component` `ReservationFolioOwnerDescriber : FolioOwnerDescriber` para `RESERVATION`: rótulo "Reserva <código>", titular, UH alocada, chegada e saída)
- Create: `src/main/kotlin/com/zeny/hotel/reservations/web/ReservationConfirmationController.kt`
- Modify: `ReservationsErrorCodes` (onde estiverem os códigos do módulo), `src/main/kotlin/com/zeny/hotel/reservations/ReservationsModule.kt` (`printing`), `src/test/kotlin/com/zeny/hotel/ModularityTest.kt`, `api/openapi.yaml` (gerado)
- Create: `src/main/resources/templates/reservations/confirmation.html`, `confirmation.txt`
- Test: `src/test/kotlin/com/zeny/hotel/reservations/ReservationConfirmationApiTest.kt`, e um caso no `FolioStatementDocumentsApiTest` (Task 8) com o descritor real: o extrato da conta da reserva traz o nome do titular, a UH e as datas

**Interfaces:**
- Consumes: `FolioOwnerDescriber`/`FolioOwnerDescription` (Task 8), `PrintingApi` e DTOs (Tasks 5 e 6), `PdfText` (Task 1).

- [ ] **Step 1:** testes: o PDF traz o código, o titular, o tipo de UH, o plano, as datas, as noites, a diária e o total, o nome e o prazo da política de cancelamento copiada, os horários de check-in e check-out, a janela de arrumação, o preço do early e do late (ou "não oferecido") e os dois textos da política, que o teste muda por `PUT /api/v1/hotel/policies` e vê no PDF (original devolvido no `finally`; critério da RN-11); a política de cancelamento editada depois da reserva **não** muda o PDF (RN-15); cancelada → `409 CONFIRMATION_UNAVAILABLE`; sem `reservations.reservations.read` → `403`; `POST …/print` → `202` com job; o extrato da conta da reserva traz o titular, a UH e as datas pelo descritor.
- [ ] **Step 2–4:** ver falhar, implementar, ver passar; `./gradlew updateOpenApi`.
- [ ] **Step 5:** commit "Acrescenta a confirmação de reserva em PDF e na fila de impressão e descreve a reserva no extrato (M5 E11, Task 10)".

---

### Task 11: Documentação e medição da imagem

**Estimativa:** 30 min. **Depende de:** Task 10. **Risco:** normal (documentação).

**Files:**
- Modify: `docs/ARCHITECTURE.md` (§3.1: `printing` → `shared, hotel, files`; §14: a API de documentos, templates por dono, layout padrão, limpeza do texto, sem recurso externo, ESC/POS IBM850 e 48 colunas, logotipo; §7: rotas `…/pdf` e `…/print` como convenção de documento)
- Modify: `docs/SPEC.md` (§8.2.18, se precisar: documentos como `PrintJob.documentType`)
- Modify: `README.md` (seção "API": parágrafo "Documentos (M5 E11)" com as seis rotas, permissões, `audience`, códigos `REGISTRATION_CARD_UNAVAILABLE` e `CONFIRMATION_UNAVAILABLE`, as 48 colunas do ESC/POS e "como registrar um template")

- [ ] **Step 1:** escrever os trechos.
- [ ] **Step 2:** `docker build -f docker/Dockerfile -t zeny-hotel-manager:e11 .` e o mesmo no commit anterior à Task 1 (extraído por `git archive` num diretório do scratchpad, sem mexer na árvore de trabalho); anotar no relatório o tamanho das duas imagens (`docker image inspect -f '{{.Size}}'`) e o tempo do build (risco do roadmap); apagar as imagens de medição.
- [ ] **Step 3:** `./gradlew build` → BUILD SUCCESSFUL; `.github/scripts/check-api-breaking.sh main` → sem `ERR`.
- [ ] **Step 4:** commit "Documenta os documentos impressos e a API de renderização (M5 E11, Task 11)".

## Ondas

- **Onda 1:** Tasks 1, 2 e 3 (arquivos disjuntos: a 1 mexe no build, nos lockfiles e em `printing/infrastructure`; a 2 em `printing/domain`; a 3 no `hotel`; testes em arquivos próprios).
- **Onda 2:** Tasks 4, 5 e 7 (`files`, `printing` raiz/`application`, `guests`; a 5 precisa da onda 1 integrada).
- **Em série:** 6 → 8 → 9 → 10 → 11 (todas mexem no `ModularityTest` e no `api/openapi.yaml`).

## Notas para o handoff

- "Decisões para o usuário" (revisão humana da E11): D-E11-1 (bibliotecas e tamanho da imagem), D-E11-4 (sem fonte embutida: `?` fora do WinAnsi/IBM850), D-E11-7 (leitura de conteúdo só de anexo comum, mais estreita que a H18), D-E11-9 (rotas `…/pdf` e `…/print`, `audience` padrão `GUEST` no PDF), D-E11-11 (nome social sem o civil; códigos FNRH sem rótulo — os rótulos pedem uma porta `frontdesk` implementada pelo `fnrh`, decisão de arquitetura) e D-E11-12 (porta `FolioOwnerDescriber`).
- Da E10, fica para depois: `claimPending`/`unackedSent` ainda carregam o conteúdo cifrado (com PDFs de até ~2 MB pelo logotipo, reavaliar no M8).
