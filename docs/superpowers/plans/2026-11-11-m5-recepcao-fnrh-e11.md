# M5 E11 — Documentos: ficha, extrato e confirmação: plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** gerar a ficha de registro (`frontdesk`), o extrato de conta (`billing`, RF-CTA-12) e a confirmação de reserva (`reservations`, RF-RES-12 com os textos da RN-11) em PDF A4 e em ESC/POS, para download (RF-NOT-04) e para impressão pela fila da E10 (RF-NOT-03), no modelo dono + `printing` (D2).

**Architecture:** o `printing` ganha a API pública de documentos: cada módulo dono registra os próprios templates (um XHTML para o PDF e um texto para o ESC/POS, versionados nos recursos do módulo) num catálogo conferido na partida, monta um modelo só de mapas, listas e valores simples e chama `PrintingApi.render` (PDF ou ESC/POS) ou `PrintingApi.print` (cria o `PrintJob` no formato da impressora). O `printing` limpa o texto, compõe o cabeçalho e o rodapé padrão com os dados do hotel (`HotelApi.profile()`, novo) e o logotipo reduzido (`FilesApi.readContent`, novo, que confere finalidade e dono, lido numa transação própria), converte o XHTML em PDF (openhtmltopdf sobre PDFBox, sem recurso externo nem DTD) ou o texto em bytes ESC/POS (IBM850, 48 colunas, corte). Os donos expõem `GET …/pdf` e `POST …/print` com a própria permissão; o extrato descreve o dono da conta por uma porta do `billing` implementada pelo `reservations` (inversão por porta, sem aresta nova).

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1 (Thymeleaf 3.1.5 pelo BOM, `thymeleaf-spring6` sem o starter), openhtmltopdf (fork `io.github.openhtmltopdf`) com PDFBox 3, `javax.imageio`, Spring MVC, springdoc 3.1, Testcontainers.

**Spec:** `docs/roadmaps/roadmap-m5-recepcao-fnrh.md`, seção "E11" (e D2, H8, H11, H17, H18 em "Hipóteses e decisões em aberto", "Restrições e invariantes" e os handoffs de E02 e E10), `docs/SPEC.md` RF-REC-01, RF-CTA-12, RF-RES-12, RN-11, RF-CFG-01/02, RF-NOT-03/04, `docs/ARCHITECTURE.md` §3.1, §3.2, §7, §8.3 e §14, e as notas do brain `ZenyHotelManager - Módulos hotel e staff`, `ZenyHotelManager - Módulo files`, `ZenyHotelManager - Convenções de API`, `ZenyHotelManager - Módulo billing (caixa, pagamentos, caução e encerramento)`, `ZenyHotelManager - Hospedagem e recepção`, `ZenyHotelManager - Módulo guests` e a captura pendente `atualização m5-recepcao-fnrh E10` (nota nova `ZenyHotelManager - Módulo printing`).

## Decisões desta etapa

| # | Decisão | Por quê | Custo se errada |
|---|---|---|---|
| D-E11-1 | **Bibliotecas** (H8): `org.thymeleaf:thymeleaf-spring6` (versão do BOM do Boot, 3.1.5; **sem** `spring-boot-starter-thymeleaf`, para não registrar `ViewResolver` no MVC da API) com um `SpringTemplateEngine` próprio do `printing` (SpEL; `ClassLoaderTemplateResolver`); `io.github.openhtmltopdf:openhtmltopdf-pdfbox` na última versão estável do Maven Central no dia (o fork mantido; o `com.openhtmltopdf` original está abandonado), que traz o PDFBox 3; `org.apache.pdfbox:pdfbox` declarado só em `testImplementation`, na versão que o openhtmltopdf resolve, para os testes extraírem o texto. Versões no catálogo, lockfiles regravados e `osv-scanner` sem High/Critical novo. Descartadas: iText (AGPL), JasperReports (pesado, XML próprio), PDFBox direto (layout à mão em cada documento), FreeMarker (já no classpath dos plugins, mas o Boot padroniza o Thymeleaf e o escape padrão dele é o de marcação). | H8; um template por documento é revisável e o visual fica no `printing`. | Troca local ao `printing`. Tamanho da imagem Docker: medido na Task 14. **Vai às "Decisões para o usuário".** |
| D-E11-2 | **API de documentos no `printing`** (raiz): `PrintingApi.render(document: DocumentRequest, format: DocumentFormat): RenderedDocument` e `PrintingApi.print(document: DocumentRequest, printerId: UUID): UUID` (o id do job; o formato sai do `PrinterKind`: `A4_PDF` → PDF, `THERMAL_ESCPOS` → ESC/POS; o `documentType` do job é o código do template). **Bean único da `PrintingApi`:** uma fachada `PrintingApiService` (`application`) implementa a interface inteira; o `enqueue` delega ao `PrintJobsService`, que deixa de implementar a `PrintingApi` e mantém o `@Audited(JOB_CREATE)`. Templates registrados pelo dono como bean de `interface ModuleDocumentTemplates { val module: String; val templates: List<DocumentTemplate> }` (o objeto na raiz do dono, o `@Bean` numa `@Configuration` da `application` do dono, como as `ModuleFilePurposes`), cada `DocumentTemplate(code, title, xhtmlTemplate, textTemplate)` com `code` no padrão do `documentType` da E10 (`<módulo>.<documento>`, prefixo = o módulo) e caminhos sob `templates/<módulo>/`. O `DocumentTemplateCatalog` recusa a partida com código repetido, prefixo de outro módulo, código fora do padrão, caminho fora de `templates/<módulo>/` ou template inexistente. O modelo é `Map<String, Any?>` com folhas `String`, `Number`, `Boolean`, `LocalDate`, `LocalTime`, `Instant`, `Money`, `Percent` ou `null`, e mapas e listas aninhados; outro tipo é `IllegalArgumentException`. Alternativas descartadas: um modelo tipado por documento (o `printing` passaria a conhecer tipos de negócio, contra D2, ou exigiria reflexão sobre classes alheias para limpar o texto) e templates no próprio `printing` (D2: o dono versiona os seus). | D2 e o escopo da E11 (`render(template, model, format)`, `print(template, model, printerId)`, templates registrados pelos donos). Um modelo de mapas deixa o `printing` limpar todo texto num lugar só. | Templates sem tipagem: um campo renomeado no dono só aparece no teste do dono. |
| D-E11-3 | **Layout padrão e contexto:** o `printing` envolve o corpo do dono no layout `templates/printing/layout.xhtml` (só PDF): cabeçalho com nome fantasia, razão social, CNPJ formatado, Cadastur, inscrição municipal, endereço e o logotipo; rodapé com o título, "Emitido em dd/MM/yyyy HH:mm" no fuso do hotel e "Página X de Y" (CSS paginado do openhtmltopdf). O contexto de renderização tem **só** as variáveis postas pelo `printing`: `document` (o modelo do dono, limpo), `hotel` (os textos do perfil, limpos), `logo` (`data:` URI ou nulo), `fmt`, `issuedAt`, `title` e `bodyTemplate` (o caminho do catálogo); o modelo só é alcançável como `document.*`. O corpo entra por **um único** `th:insert="~{${bodyTemplate} :: body}"` no layout. Nenhum template usa `th:utext`, o inline sem escape `[( … )]`, o pré-processamento `__…__` nem `th:insert`/`th:replace`/`th:include` com `${` (salvo aquela linha do layout); um teste varre os templates. O ESC/POS não tem layout em template: o cabeçalho e o rodapé são compostos pelo `EscPosDocument` (D-E11-6). `fmt` (`DocumentFormatter`): datas `dd/MM/yyyy`, horas `HH:mm`, instantes no fuso do hotel, `Money` como `R$ 1.234,56`, `Percent` como `10%`, CNPJ `00.000.000/0000-00`. Hotel não configurado (`profile()` nulo): cabeçalho só com "Hotel" e sem logotipo, sem erro. | Risco do roadmap ("o `printing` oferece o cabeçalho e o rodapé padrão, e os módulos só preenchem o corpo"); I4 da revisão (SSTI pelo pré-processamento e por fragmento variável). | Visual revisável. |
| D-E11-4 | **Texto limpo:** antes de renderizar, toda `String` do modelo **e dos textos do perfil do hotel** perde os caracteres de controle (Unicode `Cc`, inclusive `\n`, `\t`, `ESC` 0x1B e `GS` 0x1D, trocados por espaço) e de formatação (`Cf`: marcas bidi, largura zero, `U+FEFF`), com os espaços repetidos colapsados. No PDF (fontes base-14, WinAnsi) o caractere que o `windows-1252` não codifica vira `?`; no ESC/POS (IBM850, `ESC t 2`) o caractere que o IBM850 não codifica é decomposto (NFD) e perde as marcas combinantes, e o que sobrar vira `?`. Sem fonte embutida. | Nota do brain (`Módulo guests`): "a renderização da ficha e dos PDFs precisa tratar" controle e formatação Unicode; o escape do Thymeleaf protege a marcação, a limpeza protege o ESC/POS contra injeção de comando. | Nome em escrita não latina sai com `?`; embutir uma fonte TTF (~700 KB) resolve depois. **Vai às "Decisões para o usuário".** |
| D-E11-5 | **Sem recurso externo no PDF:** os templates são XHTML renderizados em `TemplateMode.XML`; o `HtmlPdfRenderer` faz o *parse* com um `DocumentBuilderFactory` seguro (`disallow-doctype-decl`, sem entidades externas, `FEATURE_SECURE_PROCESSING`) e entrega o DOM ao openhtmltopdf (`withW3cDocument`), com um resolvedor de URI que só aceita `data:`; qualquer `http:`, `https:`, `file:`, `jar:`, caminho relativo ou DTD é recusado sem acesso (sem SSRF nem leitura de arquivo local). O log do openhtmltopdf (`XRLog`) fica restrito a `WARN` sem conteúdo do documento. | ARCHITECTURE §8.3; M4 e M7 da revisão (a DTD passa pelo *parser* XML, não pelo resolvedor). | Templates precisam ser XML bem formado (o teste de cada dono prova). |
| D-E11-6 | **ESC/POS:** `ESC @`, `ESC t 2` (IBM850), cabeçalho centralizado (`ESC a 1`) só com texto (nome fantasia, razão social, CNPJ, Cadastur, endereço; **sem logotipo** na térmica), corpo do template de texto quebrado por palavra em 48 colunas (80 mm, fonte A; constante `ESC_POS_COLUMNS`, sem propriedade enquanto não houver impressora de 58 mm), rodapé com o título e o instante de emissão, 4 avanços de linha e corte parcial (`GS V 66 0`). Linha `---` do template vira uma régua de `-` na largura. Composição num só lugar: `EscPosDocument.encode(header, body, footer)`. | RF-NOT-03; critério "ESC/POS ou PDF conforme o tipo da impressora"; M3 da revisão. | O logotipo na térmica (raster `GS v 0`) fica para o M8, se o hotel pedir. |
| D-E11-7 | **Logotipo pela `FilesApi`** (H18): `FilesApi.readContent(id: FileId, purpose: FilePurposeCode, ownerEntityId: String): FileContent` devolve o conteúdo de um anexo **não apagado, da finalidade e vinculado ao dono** informados (`404 FILE_NOT_FOUND`, `422 FILE_PURPOSE_MISMATCH`, `409 FILE_ALREADY_ATTACHED` como nas outras operações; órfão também `409`), conferindo o SHA-256. **Anexo sensível é recusado** com `IllegalArgumentException` (erro de programação; sem código público): o único consumidor é o logotipo, e a leitura de um sensível pelo servidor precisaria de trilha própria. Sem trilha. O `printing` lê o logotipo com `HotelFilePurposes.LOGO` e o id do hotel de `HotelApi.profile()` **numa transação própria** (`TransactionTemplate`, `PROPAGATION_REQUIRES_NEW`, `readOnly`), para que a falha não marque a transação de quem chama como *rollback-only*; qualquer exceção ali, logotipo `WEBP` (sem leitor no JDK), imagem ilegível ou maior que 2 MB: documento **sem logotipo**, com `WARN` (sem id de hóspede), nunca erro. O logotipo é **reduzido** antes do `data:` URI (`DocumentLogo`: lado maior até 400 px, regravado em PNG; menor não é ampliado), o que limita o peso por página. | H18 e a nota da E11 ("não servir anexo sem conferir quem chama"); I2 e I3 da revisão. Recusar o sensível é mais estreito que a H18 e não tira nada da E11. | Desvio da letra da H18; uma conexão a mais durante a leitura do logotipo. **Vai às "Decisões para o usuário".** |
| D-E11-8 | **`HotelApi.profile(): CurrentHotelProfile?`** com `data class CurrentHotelProfile(val hotelId: UUID, val profile: HotelProfile)`; `null` enquanto o hotel não está configurado. O `HotelProfile` existente não muda. | H18; o id do hotel é o dono do vínculo do logotipo. | — |
| D-E11-9 | **Caminhos** (decisão aberta da E11): rotas próprias, não negociação por `Accept`. `GET /api/v1/reservations/{id}/registration-card/pdf` e `POST /api/v1/reservations/{id}/registration-card/print` (`frontdesk`); `GET /api/v1/folios/{id}/statement/pdf?audience=` e `POST /api/v1/folios/{id}/statement/print?audience=` (`billing`; `audience` padrão **`GUEST`** nos dois, porque o documento vai ao hóspede; o JSON segue com padrão `INTERNAL`); `GET /api/v1/reservations/{id}/confirmation/pdf` e `POST /api/v1/reservations/{id}/confirmation/print` (`reservations`). `GET`: `200 application/pdf` (`format: binary`), `Content-Disposition: attachment; filename="<documento>-<código>.pdf"` (`ficha`, `extrato`, `confirmacao`; código da reserva ou número da conta), `Cache-Control: no-store`. `POST …/print`: corpo `PrintDocumentRequest {printerId}`, `@Idempotent`, `202` com `PrintJobAccepted {printJobId}` (DTOs na raiz do `printing`). Erros do `printing` passam como estão (`400 UNKNOWN_PRINTER`, `422 PRINTER_INACTIVE`). Erro num `GET …/pdf` sai em `application/problem+json` mesmo com `Accept: application/pdf`. Consequência de D2: quem tem a permissão do dono imprime em qualquer impressora ativa pelo `printerId`. | Convenções de API (subrecurso, um `operationId` por operação, resposta binária descrita); negociação por `Accept` no mesmo caminho daria duas respostas num `operationId`. `@Idempotent` em POST com efeito externo (ARCHITECTURE §7). | Rotas publicadas não mudam. **Vai às "Decisões para o usuário".** |
| D-E11-10 | **Permissões:** a ficha usa `frontdesk.stays.checkin`; o extrato, `billing.folios.read`; a confirmação, `reservations.reservations.read`; as mesmas no `GET` e no `POST …/print`; o `printing` não confere permissão (D2). | Escopo da E11 e D2; o `frontdesk` não tem permissão de leitura, e quem faz o check-in imprime a ficha. | Um `frontdesk.stays.read` depois muda os modelos de role. **Vai às "Decisões para o usuário".** |
| D-E11-11 | **O que a ficha mostra** (decisão aberta da E11): estadia (código da reserva, UH, chegada, saída, entrada efetiva, adultos e crianças) e, por hóspede, o nome (o **nome social** quando houver, senão o civil), nacionalidade, data de nascimento, endereço se houver (`GuestIdentification.address`, acrescentado), cada documento **mascarado** (tipo, país emissor, `maskedNumber`), o motivo de viagem e o meio de transporte **como os códigos FNRH guardados**, com "(código FNRH)", e uma linha de assinatura. Nada de RN-19, número inteiro de documento, contato, notas ou restrição. Situações: `CONFIRMED`, `IN_HOUSE` e `CHECKED_OUT`; outras → `409 REGISTRATION_CARD_UNAVAILABLE`. | H11; RN-19. Os rótulos dos códigos de viagem vivem no `fnrh`, uma camada acima do `frontdesk`. | Os rótulos exigem uma porta `frontdesk` implementada pelo `fnrh`: **decisão de arquitetura que vai ao usuário**; o nome civil ao lado do social também. |
| D-E11-12 | **Extrato:** o corpo vem do `FolioStatementService.statement(id, audience)` existente: número e situação da conta, abertura, pagador (empresa pela `GuestsApi.company`, se houver), o dono descrito, as linhas por dia com o saldo do dia, os totais, o saldo e as cauções; no `INTERNAL`, também as reaberturas e o bloco interno das linhas. O dono é descrito por uma **porta do `billing`**, `FolioOwnerDescriber` (raiz: `val ownerType: FolioOwnerType; fun describe(ownerId: UUID): FolioOwnerDescription?`, com `FolioOwnerDescription(label, guestName?, roomNumber?, arrival?, departure?)`), implementada pelo `reservations` para `RESERVATION`; dono sem descritor: só o tipo; dois descritores do mesmo tipo recusam a partida. Alternativas descartadas: um rótulo do dono gravado na conta ao abri-la (fica velho com a troca de UH e o nome social, e muda o schema do `billing`); o `reservations` como dono de um "extrato da reserva" lendo o `billing` (duplica o extrato e a conta avulsa ficaria sem PDF); extrato sem nome nem UH (o hóspede não reconhece o documento). M6 a M8 implementam `GROUP`, `EVENT` e `COMPANY`. | RF-CTA-12; inversão por porta da ARCHITECTURE §3.1; a aresta `reservations` → `billing` já existe. | Uma porta nova do `billing`, registrada na §3.1. **Vai às "Decisões para o usuário".** |
| D-E11-13 | **Confirmação:** código da reserva, situação, titular (nome social ou civil), tipo de UH, plano, chegada e saída, noites, adultos e idades das crianças, diária por noite e total, os termos de cancelamento e de garantia **copiados na reserva** (RN-15) e, da `HotelApi.policy()`, os horários de check-in e check-out, a janela de arrumação, early/late com o preço (ou "não oferecido") e `stayPolicyText`/`additionalPolicyText` (RN-11). Situações: `PROVISIONAL`, `CONFIRMED`, `IN_HOUSE` e `CHECKED_OUT`; `CANCELLED` e `NO_SHOW` → `409 CONFIRMATION_UNAVAILABLE`. | RF-RES-12, RN-11, RF-CFG-02. | — |
| D-E11-14 | **Trilha:** o `print` de cada dono é caso de uso `@Audited` + `@Transactional` (`frontdesk.registration-card.print`, `billing.statement.print`, `reservations.confirmation.print`, cada constante no próprio arquivo do serviço do documento) com retrato só de ids (reserva ou conta, impressora, job), nunca texto do documento; o `GET …/pdf` é leitura, sem trilha. | `LayerRules.writeUseCasesAreAudited`; RN-13. | A renderização acontece dentro da transação de escrita do dono (uma conexão presa por ~1 s); aceitável num hotel de 30 UHs. |

## Global Constraints

- Grafo (ARCHITECTURE §3.1 e §3.2): `printing` depende só de `shared`, `hotel` e `files` (teste exato no `ModularityTest`, linha da tabela §3.1 na mesma task) e nunca escuta eventos; `frontdesk`, `billing` e `reservations` ganham a aresta para `printing` (já na tabela §3.1) na task que a usa, com o `ModularityTest`; nenhum módulo lê tabela de outro schema; nenhum importa `iam`.
- O `printing` não conhece modelos de negócio: só `DocumentRequest(templateCode, model)`; cada dono monta os dados, confere a **própria** permissão e chama o `printing` (D2).
- RN-19 e RN-13: nenhum documento, log, trilha ou resposta de erro contém raça/cor, deficiência ou número inteiro de documento; a ficha só usa `GuestsApi.identification` (mascarado); `GuestsApi.fnrhData` nunca é chamada fora do `fnrh`.
- Templates: só as construções permitidas por D-E11-3; todo texto passa pela limpeza de D-E11-4; nenhum recurso externo nem DTD na renderização (D-E11-5).
- RN-01: datas de negócio pela `HotelApi`, instantes pelo `Clock`, exibidos no fuso do hotel.
- Estrutura do módulo: tipos da API pública na raiz; `domain` sem Spring; escrita da `application` `@Audited` + `@Transactional`; `web` só com a `application` e as raízes.
- Convenções de API: `@ApiV1Controller`, `@Operation(operationId)` único, `@Tag` do módulo dono, RFC 9457, `@Idempotent` nos `POST …/print`; resposta binária com `Content(mediaType = "application/pdf", schema = Schema(type = "string", format = "binary"))` (modelo: `FilesController.content`); `api/openapi.yaml` só por `./gradlew updateOpenApi`.
- Lockfiles: mudança de catálogo ou dependência regrava `gradle.lockfile` e `buildscript-gradle.lockfile` no mesmo commit (`./gradlew dependencies --write-locks`).
- Limite de tarefa: até 3 arquivos de produção Kotlin/SQL/YAML (templates `.xhtml`/`.txt`, documentação, `api/openapi.yaml`, lockfiles e a linha de `allowedDependencies` do `…Module.kt` não contam).
- Testes: banco compartilhado (cada teste cria os próprios dados; quem muda os dados do hotel pela API devolve o original num `finally`); textos dos PDFs pelo `PdfText` (Task 1); nenhum `Thread.sleep`.
- Documentação, mensagens e commits em pt-BR; código, API e banco em inglês; commits com o trailer `Soul: <soul>`.
- `./gradlew build` verde e `.github/scripts/check-api-breaking.sh main` sem `ERR` em `/api/v1`.

## Review Focus

1. **`GET …/pdf` com erro e `Accept: application/pdf`** (conta ou reserva inexistente, sem permissão): `404`/`403` em `application/problem+json`, nunca `406` nem `500` (teste na Task 10).
2. **Texto hostil no perfil do hóspede e do hotel** (`<script>`, `<img src="http://…">`, `&`, `U+202E`, largura zero, `\n`, `ESC`/`GS`): PDF e ESC/POS com o texto literal limpo, sem recurso externo lido, sem comando ESC/POS injetado e sem falha (Tasks 1, 2 e 8).
3. **Caracteres fora do WinAnsi/IBM850** (`Łukasz`, `Ñandú`, `李`, emoji): documento gerado, o que não cabe vira `?`, os acentos do português ficam (Tasks 1 e 2).
4. **Logotipo indisponível** (hotel não configurado, `WEBP`, apagado, objeto ausente no bucket, imagem ilegível, PNG de 2 MB e 4000 px) **dentro do `print` transacional do dono**: `202`/`200` sem logotipo (ou com ele reduzido), nunca `500` nem `422 PRINT_CONTENT_TOO_LARGE` (Tasks 7, 8 e 10).
5. **Extrato longo** (80 lançamentos em vários dias) com logotipo: PDF com várias páginas e "Página X de Y", abaixo de 5 MiB; no ESC/POS as linhas longas quebram em 48 colunas (Tasks 9 e 10).

---

### Task 1: Bibliotecas e conversão XHTML → PDF

**Estimativa:** 40 min. **Depende de:** nenhuma. **Risco:** alto (cadeia de suprimentos, *parsing*).

**Files:**
- Modify: `gradle/libs.versions.toml` (`openhtmltopdf`, `thymeleaf-spring6` sem versão, `pdfbox` de teste), `build.gradle.kts` (`implementation` dos dois, `testImplementation(libs.pdfbox)`), `gradle.lockfile`, `buildscript-gradle.lockfile` (regravados)
- Create: `src/main/kotlin/com/zeny/hotel/printing/infrastructure/HtmlPdfRenderer.kt`
- Test: `src/test/kotlin/com/zeny/hotel/printing/HtmlPdfRendererTest.kt`, `src/test/kotlin/com/zeny/hotel/testsupport/PdfText.kt`

**Interfaces:**
- Produces: `class HtmlPdfRenderer { fun render(xhtml: String): ByteArray }` (bean da `infrastructure`; D-E11-5); em `PdfText.kt`: `fun pdfText(bytes: ByteArray): String`, `fun pdfPageCount(bytes: ByteArray): Int`, `fun pdfImageCount(bytes: ByteArray): Int` (PDFBox), usados pelas Tasks 8 a 13.

- [ ] **Step 1:** testes (unitários): XHTML com "Ficha de hóspede — João Ação" vira PDF (`%PDF-`) cujo texto contém a frase; com um `HttpServer` do JDK local contando acessos, `<img src="http://127.0.0.1:<porta>/x.png">`, um `<!DOCTYPE html SYSTEM "http://127.0.0.1:<porta>/x.dtd">`, `<img src="file:<png temporário>">`, `<img src="jar:file:…!/x.png">` e `<img src="x.png">` não geram acesso nem imagem (`pdfImageCount == 0`; o DOCTYPE pode recusar o documento com exceção, sem acesso); `<img src="data:image/png;base64,…">` (PNG 2×2) gera 1 imagem; `Łukasz 李 😀` gera PDF sem exceção; 300 parágrafos geram `pdfPageCount > 1`; com o `OutputCaptureExtension`, um nome do documento não aparece no log.
- [ ] **Step 2:** ver falhar: `./gradlew test --tests 'com.zeny.hotel.printing.HtmlPdfRendererTest'`.
- [ ] **Step 3:** catálogo, build e `HtmlPdfRenderer` (`DocumentBuilderFactory` seguro → `withW3cDocument`; `useFastMode()`; resolvedor e `useHttpStreamImplementation` que só aceitam `data:`; `XRLog` em `WARN`); `./gradlew dependencies --write-locks`.
- [ ] **Step 4:** ver passar; `./gradlew build -x test`; anotar no relatório as versões escolhidas do openhtmltopdf e do PDFBox e a saída de `osv-scanner scan source -r --no-ignore .` filtrada pelos pacotes novos.
- [ ] **Step 5:** commit "Acrescenta openhtmltopdf e Thymeleaf e a conversão de XHTML em PDF sem recurso externo (M5 E11, Task 1)".

---

### Task 2: Limpeza do texto, formatação e codificação ESC/POS

**Estimativa:** 40 min. **Depende de:** nenhuma. **Risco:** alto (entrada externa, codificação).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/printing/domain/DocumentText.kt` (`object DocumentText { fun clean(text: String): String; fun toWinAnsi(text: String): String }`)
- Create: `src/main/kotlin/com/zeny/hotel/printing/domain/EscPosDocument.kt` (`class EscPosDocument(columns: Int) { fun encode(header: List<String>, body: String, footer: List<String>): ByteArray }` e `const val ESC_POS_COLUMNS = 48`)
- Create: `src/main/kotlin/com/zeny/hotel/printing/domain/DocumentFormatter.kt` (`class DocumentFormatter(zone: ZoneId)` com `date(LocalDate?)`, `time(LocalTime?)`, `instant(Instant?)`, `money(Money?)`, `percent(Percent?)`, `cnpj(Cnpj?)`, todos `String`, vazio para nulo)
- Test: `src/test/kotlin/com/zeny/hotel/printing/DocumentTextTest.kt`, `EscPosDocumentTest.kt`, `DocumentFormatterTest.kt`

**Interfaces:**
- Produces: `DocumentText.clean` e `toWinAnsi` (D-E11-4); `EscPosDocument.encode` (D-E11-6; `columns` de 32 a 64, senão `IllegalArgumentException`); `DocumentFormatter` (D-E11-3), exposto como `fmt` pela Task 8.

- [ ] **Step 1:** testes: `clean("Jo‮ão​\nSilva\t\u001B\u001D")` = `"João Silva"`; `toWinAnsi("Łukasz Ñandú 李")` mantém `Ñandú` e troca `Ł` e `李` por `?`; `encode` começa com `1B 40 1B 74 02`, termina com `1D 56 42 00`, codifica `ã õ ç é` em IBM850 (`C6 E4 87 82`), troca `李` e emoji por `?`, quebra uma linha de 120 caracteres em linhas de até 48 por palavra (palavra maior que a largura é cortada), centraliza o cabeçalho (`1B 61 01` … `1B 61 00`), troca `---` por 48 `-`, e um `body` com `0x1B`/`0x1D` já limpo pelo `clean` não produz nenhum `1B`/`1D` fora dos comandos do próprio encoder; `columns = 20` falha; `DocumentFormatter(ZoneId.of("America/Sao_Paulo"))`: `money(Money("1234.5"))` = `"R$ 1.234,50"`, `instant(2026-11-11T13:05:00Z)` = `"11/11/2026 10:05"`, `cnpj` formatado, nulo = `""`.
- [ ] **Step 2–4:** ver falhar (`./gradlew test --tests 'com.zeny.hotel.printing.Document*' --tests 'com.zeny.hotel.printing.EscPosDocumentTest'`), implementar (charset `IBM850`), ver passar.
- [ ] **Step 5:** commit "Acrescenta a limpeza de texto, a formatação e a codificação ESC/POS dos documentos (M5 E11, Task 2)".

---

### Task 3: `HotelApi.profile()`

**Estimativa:** 20 min. **Depende de:** nenhuma. **Risco:** alto (contrato entre módulos).

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/hotel/HotelApi.kt` (`fun profile(): CurrentHotelProfile?` e `data class CurrentHotelProfile(val hotelId: UUID, val profile: HotelProfile)`)
- Modify: `src/main/kotlin/com/zeny/hotel/hotel/application/HotelApiService.kt`
- Test: `src/test/kotlin/com/zeny/hotel/hotel/HotelApiProfileTest.kt`

**Interfaces:**
- Produces: `HotelApi.profile(): CurrentHotelProfile?` (D-E11-8; `@Transactional(readOnly = true)`, sem permissão).

- [ ] **Step 1:** teste (`@IntegrationTest`): depois de `PUT /api/v1/hotel` com dados e logotipo (`FilesTestSupport`), `profile()` devolve o id do hotel, os dados normalizados e o `logoFileId`; original devolvido no `finally`. O caso `null` num teste de unidade do serviço com o `HotelStore` simulado.
- [ ] **Step 2–4:** ver falhar, implementar, ver passar (`./gradlew test --tests 'com.zeny.hotel.hotel.*'`).
- [ ] **Step 5:** commit "Acrescenta HotelApi.profile() com o id do hotel (M5 E11, Task 3)".

---

### Task 4: `FilesApi.readContent`

**Estimativa:** 35 min. **Depende de:** nenhuma. **Risco:** alto (segurança).

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/files/FilesApi.kt` (método e `class FileContent(val summary: FileSummary, val content: ByteArray)` com `toString` sem o conteúdo)
- Modify: `src/main/kotlin/com/zeny/hotel/files/application/FilesApiService.kt`
- Test: `src/test/kotlin/com/zeny/hotel/files/FilesApiReadContentTest.kt`

**Interfaces:**
- Produces: `FilesApi.readContent(id: FileId, purpose: FilePurposeCode, ownerEntityId: String): FileContent` (D-E11-7; `@Transactional(readOnly = true)`, sem trilha; SHA-256 divergente é `IllegalStateException`; sensível é `IllegalArgumentException` antes de ler o objeto).

- [ ] **Step 1:** testes no contexto dos testes de anexos (`FilesTestSupport`, finalidades de `filesexample`): anexo comum vinculado ao dono devolve os bytes; outro dono → `409 FILE_ALREADY_ATTACHED`; órfão → `409`; outra finalidade → `422 FILE_PURPOSE_MISMATCH`; apagado ou inexistente → `404 FILE_NOT_FOUND`; sensível vinculado → `IllegalArgumentException` e nenhum objeto lido; objeto adulterado no bucket → `IllegalStateException`; nenhuma entrada nova na trilha.
- [ ] **Step 2–4:** ver falhar, implementar, ver passar (`./gradlew test --tests 'com.zeny.hotel.files.*'`).
- [ ] **Step 5:** commit "Acrescenta a leitura do conteúdo de anexo comum pela FilesApi (M5 E11, Task 4)".

---

### Task 5: Catálogo de templates e motor

**Estimativa:** 40 min. **Depende de:** Task 1. **Risco:** alto (motor de templates).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/printing/PrintingDocuments.kt` (raiz: `DocumentTemplate(code: String, title: String, xhtmlTemplate: String, textTemplate: String)`, `interface ModuleDocumentTemplates { val module: String; val templates: List<DocumentTemplate> }`, `DocumentRequest(templateCode: String, model: Map<String, Any?>)` com `toString` sem o modelo, `enum class DocumentFormat { PDF, ESCPOS }`, `class RenderedDocument(val format: DocumentFormat, val mediaType: String, val content: ByteArray)` com `toString` sem o conteúdo, `data class PrintDocumentRequest(val printerId: UUID)` e `data class PrintJobAccepted(val printJobId: UUID)` com `@Schema`)
- Create: `src/main/kotlin/com/zeny/hotel/printing/application/DocumentTemplateCatalog.kt` (catálogo conferido na partida + `SpringTemplateEngine` com dois `ClassLoaderTemplateResolver`, `XML` para `.xhtml` e `TEXT` para `.txt`, cache ligado, `UTF-8`)
- Test: `src/test/kotlin/com/zeny/hotel/printing/DocumentTemplateCatalogTest.kt`, `src/test/kotlin/com/zeny/hotel/printing/DocumentTemplatesSafetyTest.kt`, `src/test/kotlin/printingexample/PrintingExamplesConfiguration.kt` e `src/test/resources/templates/printingexample/` (como o `filesexample`)

**Interfaces:**
- Consumes: Task 1 (`thymeleaf-spring6`).
- Produces: os tipos da raiz acima; `DocumentTemplateCatalog.template(code): DocumentTemplate` (desconhecido → `IllegalArgumentException`) e `DocumentTemplateCatalog.engine`.

- [ ] **Step 1:** testes do catálogo: código repetido, `frontdesk.x` registrado pelo módulo `billing`, código fora de `[a-z][a-z0-9]*(\.[a-z][a-z0-9-]*)+`, caminho fora de `templates/<módulo>/` e template inexistente recusam a criação com mensagem que cita o código; o template de exemplo renderiza `${document.guest.name}` com `<b>&` escapado e uma lista com `th:each`. `DocumentTemplatesSafetyTest` varre `templates/**/*.xhtml` e `*.txt` do classpath **principal** e recusa `th:utext`, `[(`, `__` e `th:insert`/`th:replace`/`th:include` com `${`, salvo a linha única `th:insert="~{${bodyTemplate} :: body}"` em `templates/printing/layout.xhtml`; o teste prova a si mesmo com amostras ruins em memória.
- [ ] **Step 2–4:** ver falhar, implementar, ver passar (`./gradlew test --tests 'com.zeny.hotel.printing.*'`).
- [ ] **Step 5:** commit "Acrescenta o catálogo de templates de documentos do printing (M5 E11, Task 5)".

---

### Task 6: Endereço na identificação do hóspede

**Estimativa:** 20 min. **Depende de:** nenhuma. **Risco:** alto (dado pessoal num contrato consumido pelo check-in e pelo `fnrh`).

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/guests/GuestsApi.kt` (`GuestIdentification` ganha `val address: Address? = null`, fora do `toString`)
- Modify: o serviço da `application` do `guests` que monta `identification`
- Test: `src/test/kotlin/com/zeny/hotel/guests/GuestIdentificationAddressTest.kt` (arquivo novo)

**Interfaces:**
- Produces: `GuestIdentification.address` (do perfil corrente; num mesclado, do sobrevivente; anonimizado: `null`). Consumidores atuais (`CheckInService`, `FnrhCheckInStep`, `FnrhCheckOutSteps`) não mudam.

- [ ] **Step 1:** teste: perfil com endereço devolve o endereço; mesclado devolve o do sobrevivente; anonimizado devolve `null`; `toString` não contém a rua.
- [ ] **Step 2–4:** ver falhar, implementar, ver passar (`./gradlew test --tests 'com.zeny.hotel.guests.*' --tests 'com.zeny.hotel.frontdesk.*' --tests 'com.zeny.hotel.fnrh.*'`).
- [ ] **Step 5:** commit "Acrescenta o endereço à identificação do hóspede para a ficha (M5 E11, Task 6)".

---

### Task 7: Logotipo reduzido

**Estimativa:** 25 min. **Depende de:** nenhuma. **Risco:** alto (*parsing* de imagem enviada).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/printing/domain/DocumentLogo.kt` (`object DocumentLogo { fun prepare(content: ByteArray, contentType: String): ByteArray? }`)
- Test: `src/test/kotlin/com/zeny/hotel/printing/DocumentLogoTest.kt`

**Interfaces:**
- Produces: `DocumentLogo.prepare` — PNG ou JPEG até 2 MB (`2_097_152` bytes), lado maior reduzido a até `400` px (menor não é ampliado), saída PNG; `WEBP`, outro tipo, acima de 2 MB, acima de 50 MP declarados no cabeçalho ou ilegível → `null`.

- [ ] **Step 1:** testes: PNG 4000×1000 vira PNG de 400×100; JPEG 300×300 vira PNG 300×300; WEBP, bytes aleatórios, PNG truncado e um cabeçalho que declara 20000×20000 → `null` sem decodificar o raster inteiro.
- [ ] **Step 2–4:** ver falhar, implementar (`ImageIO` com leitura do cabeçalho antes, como o `ImageReencoder` do `files`), ver passar.
- [ ] **Step 5:** commit "Acrescenta a redução do logotipo para os documentos (M5 E11, Task 7)".

---

### Task 8: Renderização e impressão pela `PrintingApi`

**Estimativa:** 45 min. **Depende de:** Tasks 2 a 7 (exceto a 6). **Risco:** alto (núcleo da etapa).

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/printing/PrintingApi.kt` (`render` e `print`, D-E11-2)
- Modify: `src/main/kotlin/com/zeny/hotel/printing/application/PrintJobsService.kt` (deixa de implementar a `PrintingApi`; `enqueue` continua público e `@Audited(JOB_CREATE)`)
- Create: `src/main/kotlin/com/zeny/hotel/printing/application/PrintingApiService.kt` (a fachada, bean único da `PrintingApi`: `enqueue` delega; `render` valida e limpa o modelo e o perfil, monta o contexto de D-E11-3, lê o logotipo em `REQUIRES_NEW` e o reduz, renderiza pelo catálogo; `print` lê o `PrinterKind` e chama o `PrintJobsService.enqueue` com `documentType` = código)
- Modify: `PrintingModule.kt` (`["shared", "hotel", "files"]`), `src/test/kotlin/com/zeny/hotel/ModularityTest.kt`, `docs/ARCHITECTURE.md` §3.1 (linha do `printing`: `shared, hotel, files`)
- Create: `src/main/resources/templates/printing/layout.xhtml`
- Test: `src/test/kotlin/com/zeny/hotel/printing/DocumentRenderingTest.kt`

**Interfaces:**
- Consumes: Tasks 1 a 5 e 7.
- Produces: `PrintingApi.render(document: DocumentRequest, format: DocumentFormat): RenderedDocument` e `PrintingApi.print(document: DocumentRequest, printerId: UUID): UUID`, sem permissão; `print` herda os erros do `enqueue` (`UNKNOWN_PRINTER`, `PRINTER_INACTIVE`, `PRINT_CONTENT_TOO_LARGE`).

- [ ] **Step 1:** testes (`@IntegrationTest`, template de exemplo da Task 5): o PDF traz a razão social, o CNPJ formatado, o Cadastur e o endereço gravados por `PUT /api/v1/hotel` (original no `finally`), o título, "Emitido em" e "Página 1 de 1", e uma imagem com o logotipo PNG; com logotipo WEBP, apagado, com o objeto removido do bucket e com o hotel sem configuração (pelo serviço com `HotelApi` simulado) sai sem imagem e sem erro, **inclusive chamado de dentro de um `TransactionTemplate` de escrita que commita depois** (Review Focus 4); PNG de 2 MB e 4000 px → PDF com a imagem de até 400 px e menos de 1 MB; razão social do hotel e nome do modelo com `"<script>alert(1)</script> & ‮João\u001B"` aparecem literais e limpos no texto (Review Focus 2); um modelo com chave `hotel` não sombreia o cabeçalho; `print` numa `A4_PDF` cria job `PENDING` com `documentType` = código e conteúdo `%PDF-`, e numa `THERMAL_ESCPOS` com conteúdo começando por `1B 40` e o nome do hotel no cabeçalho; impressora desativada → `422 PRINTER_INACTIVE`; tipo não permitido no modelo (`UUID` cru) → `IllegalArgumentException`; o `PrintingStageEndToEndTest` da E10 segue verde.
- [ ] **Step 2–4:** ver falhar, implementar, ver passar (`./gradlew test --tests 'com.zeny.hotel.printing.*' --tests 'com.zeny.hotel.ModularityTest'`).
- [ ] **Step 5:** commit "Acrescenta a renderização em PDF e ESC/POS e a impressão de documentos à PrintingApi (M5 E11, Task 8)".

---

### Task 9: Extrato — porta do dono, serviço e templates (`billing`)

**Estimativa:** 40 min. **Depende de:** Task 8. **Risco:** alto (dado pessoal, porta nova).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/billing/BillingDocuments.kt` (raiz: `interface FolioOwnerDescriber { val ownerType: FolioOwnerType; fun describe(ownerId: UUID): FolioOwnerDescription? }`, `data class FolioOwnerDescription(val label: String, val guestName: String?, val roomNumber: String?, val arrival: LocalDate?, val departure: LocalDate?)` com `toString` sem o nome, e `object BillingDocumentTemplates : ModuleDocumentTemplates` com `billing.statement`)
- Create: `src/main/kotlin/com/zeny/hotel/billing/application/FolioStatementDocuments.kt` (`pdf(id, audience): RenderedDocument` e `print(id, audience, printerId): UUID` com `@Audited(STATEMENT_PRINT = "billing.statement.print")` + `@Transactional`, constante no próprio arquivo; permissão `billing.folios.read`; modelo a partir do `FolioStatementService`; descritores por tipo, dois do mesmo tipo recusam a partida; e a `@Configuration` com o `@Bean` dos templates)
- Modify: `BillingModule.kt` (`printing`), `src/test/kotlin/com/zeny/hotel/ModularityTest.kt`, `docs/ARCHITECTURE.md` §3.1 (exemplo de inversão: "`billing` descreve o dono da conta no extrato pela porta `FolioOwnerDescriber`, implementada pelo `reservations`")
- Create: `src/main/resources/templates/billing/statement.xhtml`, `statement.txt`
- Test: `src/test/kotlin/com/zeny/hotel/billing/FolioStatementDocumentsTest.kt` (pelo serviço, com `asUser`)

**Interfaces:**
- Consumes: `PrintingApi`, `ModuleDocumentTemplates`, `RenderedDocument` (Tasks 5 e 8); `PdfText` (Task 1).
- Produces: `FolioOwnerDescriber`/`FolioOwnerDescription` (implementados na Task 12); `FolioStatementDocuments.pdf/print` (usados pela Task 10).

- [ ] **Step 1:** testes (cenário do `FolioStatementApiTest`: lançamentos de dois dias, estorno, pagamento): o PDF `GUEST` traz o número da conta, as descrições e os valores das linhas, os totais e o saldo (`R$`), e **não** traz o usuário nem o motivo internos; o `INTERNAL` traz; sem descritor, só o tipo do dono; com um `FolioOwnerDescriber` de teste, o nome e a UH aparecem; dois descritores do mesmo tipo recusam a criação do serviço; 80 lançamentos com logotipo → mais de uma página, "Página 2 de" e menos de 5 MiB (Review Focus 5); ESC/POS com as linhas em até 48 colunas; sem `billing.folios.read` → `PermissionDeniedException`; a trilha do `print` só com ids.
- [ ] **Step 2–4:** ver falhar, implementar, ver passar (`./gradlew test --tests 'com.zeny.hotel.billing.FolioStatementDocumentsTest' --tests 'com.zeny.hotel.ModularityTest'`).
- [ ] **Step 5:** commit "Acrescenta o extrato da conta em PDF e ESC/POS e a porta que descreve o dono da conta (M5 E11, Task 9)".

---

### Task 10: Extrato — rotas e contrato (`billing`)

**Estimativa:** 35 min. **Depende de:** Task 9. **Risco:** alto (contrato público).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/billing/web/FolioStatementDocumentsController.kt` (as duas rotas de D-E11-9, `@Tag` do `billing`)
- Modify (contingência do Review Focus 1, só se o teste falhar): `src/main/kotlin/com/zeny/hotel/shared/web/ApiExceptionHandler.kt`
- Modify: `api/openapi.yaml` (gerado)
- Test: `src/test/kotlin/com/zeny/hotel/billing/FolioStatementDocumentsApiTest.kt`

**Interfaces:**
- Consumes: `FolioStatementDocuments` (Task 9), `PrintDocumentRequest`/`PrintJobAccepted` (Task 5).

- [ ] **Step 1:** testes: `GET …/statement/pdf` → `200 application/pdf`, `Content-Disposition` com `extrato-<número>.pdf`, `Cache-Control: no-store`; padrão `GUEST`; `POST …/print?audience=GUEST` com `Idempotency-Key` → `202` com `printJobId`, e a repetição devolve o mesmo id; sem `Idempotency-Key` → `400`; impressora inexistente → `400 UNKNOWN_PRINTER`; sem `billing.folios.read` → `403` nas duas; conta inexistente e usuário sem permissão com `Accept: application/pdf` → `404`/`403` em `application/problem+json` (Review Focus 1); com o objeto do logotipo removido do bucket, `GET` → `200` e `POST` → `202` (Review Focus 4); o `api/openapi.yaml` descreve a resposta binária.
- [ ] **Step 2–4:** ver falhar, implementar, ver passar; `./gradlew updateOpenApi`.
- [ ] **Step 5:** commit "Acrescenta as rotas do extrato em PDF e da impressão do extrato (M5 E11, Task 10)".

---

### Task 11: Ficha de registro em PDF e impressão (`frontdesk`)

**Estimativa:** 45 min. **Depende de:** Tasks 6 e 10. **Risco:** alto (dado pessoal, RN-19).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/frontdesk/application/RegistrationCardService.kt` (`pdf(reservationId)` e `print(reservationId, printerId)`, `@Audited(REGISTRATION_CARD_PRINT = "frontdesk.registration-card.print")` + `@Transactional` no `print`, constante no próprio arquivo; permissão `frontdesk.stays.checkin`; situações de D-E11-11; dados por `ReservationsApi.reservation`, `RoomsApi.room`, `GuestsApi.guest` e `GuestsApi.identification`; o objeto de templates `frontdesk.registration-card` e a `@Configuration` com o `@Bean`)
- Create: `src/main/kotlin/com/zeny/hotel/frontdesk/web/RegistrationCardController.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/frontdesk/FrontdeskErrorCodes.kt` (`REGISTRATION_CARD_UNAVAILABLE`, 409), `FrontdeskModule.kt` (`printing`), `src/test/kotlin/com/zeny/hotel/ModularityTest.kt`, `api/openapi.yaml` (gerado)
- Create: `src/main/resources/templates/frontdesk/registration-card.xhtml`, `registration-card.txt`
- Test: `src/test/kotlin/com/zeny/hotel/frontdesk/RegistrationCardApiTest.kt`

**Interfaces:**
- Consumes: `PrintingApi` e DTOs (Tasks 5 e 8); `GuestIdentification.address` (Task 6); `PdfText` (Task 1).

- [ ] **Step 1:** testes (reserva em casa com dois hóspedes pelo apoio do check-in da E02, um com nome social, CPF completo, endereço, raça/cor e deficiência gravadas, e um menor com autorização): o PDF traz o código, a UH, as datas, o nome social (e não o civil), a nacionalidade, a data de nascimento, o endereço, o CPF mascarado e os códigos de motivo e meio de transporte com "(código FNRH)"; o texto do PDF e os bytes do ESC/POS (decodificados em IBM850) **não** contêm o CPF completo (com e sem pontuação), o rótulo da raça/cor nem o código da deficiência; `PROVISIONAL` ou cancelada → `409 REGISTRATION_CARD_UNAVAILABLE`; sem `frontdesk.stays.checkin` → `403`; `POST …/print` com `Idempotency-Key` → `202` e job na impressora; a trilha do `print` só com ids; endereço com `\n` e marca bidi sai limpo.
- [ ] **Step 2–4:** ver falhar, implementar, ver passar; `./gradlew updateOpenApi`.
- [ ] **Step 5:** commit "Acrescenta a ficha de registro em PDF e na fila de impressão (M5 E11, Task 11)".

---

### Task 12: Descritor da reserva como dono da conta (`reservations`)

**Estimativa:** 25 min. **Depende de:** Task 11. **Risco:** alto (implementa porta do `billing`).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/reservations/application/ReservationFolioOwnerDescriber.kt` (`@Component`, `FolioOwnerDescriber` para `RESERVATION`: rótulo "Reserva <código>", titular (nome social ou civil, por `GuestsApi.guest`), UH alocada (`RoomsApi.room`), chegada e saída; reserva inexistente → `null`)
- Test: `src/test/kotlin/com/zeny/hotel/reservations/ReservationFolioOwnerDescriberTest.kt` e um caso novo no `FolioStatementDocumentsApiTest` (Task 10): o extrato da conta de uma reserva traz o titular, a UH e as datas

**Interfaces:**
- Consumes: `FolioOwnerDescriber`/`FolioOwnerDescription` (Task 9).

- [ ] **Step 1:** testes: descrição de uma reserva com UH alocada; sem UH, `roomNumber` nulo; inexistente, `null`; o caso no extrato.
- [ ] **Step 2–4:** ver falhar, implementar, ver passar (`./gradlew test --tests 'com.zeny.hotel.reservations.ReservationFolioOwnerDescriberTest' --tests 'com.zeny.hotel.billing.FolioStatementDocumentsApiTest' --tests 'com.zeny.hotel.ModularityTest'`).
- [ ] **Step 5:** commit "Descreve a reserva como dona da conta no extrato (M5 E11, Task 12)".

---

### Task 13: Confirmação de reserva (`reservations`)

**Estimativa:** 45 min. **Depende de:** Task 12. **Risco:** alto (contrato público, RN-11).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/reservations/application/ReservationConfirmations.kt` (`pdf(id)` e `print(id, printerId)`, `@Audited(CONFIRMATION_PRINT = "reservations.confirmation.print")` + `@Transactional` no `print`, constante no próprio arquivo; permissão `reservations.reservations.read`; situações de D-E11-13; o objeto de templates `reservations.confirmation` e a `@Configuration` com o `@Bean`)
- Create: `src/main/kotlin/com/zeny/hotel/reservations/web/ReservationConfirmationController.kt`
- Modify: o arquivo dos códigos de erro do `reservations` (`CONFIRMATION_UNAVAILABLE`, 409), `ReservationsModule.kt` (`printing`), `src/test/kotlin/com/zeny/hotel/ModularityTest.kt`, `api/openapi.yaml` (gerado)
- Create: `src/main/resources/templates/reservations/confirmation.xhtml`, `confirmation.txt`
- Test: `src/test/kotlin/com/zeny/hotel/reservations/ReservationConfirmationApiTest.kt`

**Interfaces:**
- Consumes: `PrintingApi` e DTOs (Tasks 5 e 8); `PdfText` (Task 1).

- [ ] **Step 1:** testes: o PDF traz o código, o titular, o tipo de UH, o plano, as datas, as noites, a diária e o total, o nome e o prazo da política de cancelamento copiada, os horários de check-in e check-out, a janela de arrumação, o preço do early e do late (ou "não oferecido") e os dois textos da política, que o teste muda por `PUT /api/v1/hotel/policies` e vê no PDF (original no `finally`); a política de cancelamento editada depois da reserva **não** muda o PDF (RN-15); cancelada → `409 CONFIRMATION_UNAVAILABLE`; sem `reservations.reservations.read` → `403`; `POST …/print` → `202` com job; trilha só com ids.
- [ ] **Step 2–4:** ver falhar, implementar, ver passar; `./gradlew updateOpenApi`.
- [ ] **Step 5:** commit "Acrescenta a confirmação de reserva em PDF e na fila de impressão (M5 E11, Task 13)".

---

### Task 14: Documentação e medição da imagem

**Estimativa:** 45 min (o build e os dois `docker build` rodam enquanto a documentação é escrita). **Depende de:** Task 13. **Risco:** normal (documentação).

**Files:**
- Modify: `docs/ARCHITECTURE.md` (§14: a API de documentos, templates por dono, layout padrão, construções proibidas nos templates, limpeza do texto, sem recurso externo, ESC/POS IBM850 e 48 colunas, logotipo reduzido e lido em transação própria; §7: rotas `…/pdf` e `…/print` como convenção de documento)
- Modify: `docs/SPEC.md` (§8.2.18, se precisar: documentos como `PrintJob.documentType`)
- Modify: `README.md` (seção "API": parágrafo "Documentos (M5 E11)" com as seis rotas, permissões, `audience`, `REGISTRATION_CARD_UNAVAILABLE` e `CONFIRMATION_UNAVAILABLE`, as 48 colunas do ESC/POS e "como registrar um template e imprimir")

- [ ] **Step 1:** escrever os trechos.
- [ ] **Step 2:** `docker build -f docker/Dockerfile` desta ponta e do commit anterior à Task 1 (extraído por `git archive` num diretório do scratchpad); anotar no relatório o tamanho das duas imagens (`docker image inspect -f '{{.Size}}'`) e o tempo de cada build; apagar as imagens de medição.
- [ ] **Step 3:** `./gradlew build` → BUILD SUCCESSFUL; `.github/scripts/check-api-breaking.sh main` → sem `ERR`.
- [ ] **Step 4:** commit "Documenta os documentos impressos e a API de renderização (M5 E11, Task 14)".

## Ondas

- **Onda 1:** Tasks 1, 2 e 7 (build e lockfiles + `printing/infrastructure`; `printing/domain` em arquivos distintos; testes próprios).
- **Onda 2:** Tasks 3, 4 e 5 (`hotel`, `files`, `printing` raiz/`application`; a 5 precisa da onda 1 integrada).
- **Onda 3:** Tasks 6 e 8 (`guests`; `printing`, `ModularityTest` e §3.1 só na 8).
- **Em série:** 9 → 10 → 11 → 12 → 13 → 14 (todas mexem no `ModularityTest`, no `api/openapi.yaml` ou no teste do extrato).

## Notas para o handoff

- "Decisões para o usuário" (revisão humana da E11): D-E11-1 (bibliotecas e tamanho da imagem), D-E11-4 (sem fonte embutida), D-E11-7 (leitura de conteúdo só de anexo comum, mais estreita que a H18; logotipo em transação própria), D-E11-9 (rotas `…/pdf` e `…/print`, `audience` padrão `GUEST` no PDF, impressão em qualquer impressora ativa), D-E11-10 (ficha sob `frontdesk.stays.checkin`), D-E11-11 (nome social sem o civil; códigos FNRH sem rótulo — os rótulos pedem uma porta `frontdesk` implementada pelo `fnrh`, decisão de arquitetura) e D-E11-12 (porta `FolioOwnerDescriber`).
- A captura do brain explica como registrar um template (objeto `ModuleDocumentTemplates` na raiz do dono, `@Bean` na `application`, arquivos em `templates/<módulo>/`, construções proibidas) e imprimir (`PrintingApi.render`/`print`, `PrintDocumentRequest`/`PrintJobAccepted`, `GET …/pdf` e `POST …/print`) — critério de aceite.
- Da E10, fica para depois: `claimPending`/`unackedSent` ainda carregam o conteúdo cifrado (com o logotipo reduzido, os PDFs ficam pequenos; reavaliar no M8).
