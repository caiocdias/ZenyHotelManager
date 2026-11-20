# E23 do M6 — Snapshot diário e arquivo dos PDFs: plano de implementação

> **Para agentes:** execução por `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde, não o código.

**Goal:** dar ao `reporting` a API pública que o fechamento (E13) chama: `snapshotDailyStatistics(date)` (SPEC §8.2.9, regras de Q-15 pela H9) e `archiveDayReports(date)` (PDFs da data renderizados por `printing` e guardados em `files` como anexo sensível, H6 e H23 c), mais a listagem e o download do arquivo por data.

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E23", H6, H7, H9, H23; handoff da E09; SPEC §8.2.9 (`DailyStatistics`), RF-REL-07, RF-FEC-02, RN-13; ARCHITECTURE §3.1 (linha `reporting`), §7 ("Documentos impressos"), §11. Brain: `ZenyHotelManager - Módulo files` (finalidades, sensível, `readContent` só de comum, D-E11-7), `ZenyHotelManager - Módulo printing` (documento do dono, `ModuleDocumentTemplates`, `render`), `ZenyHotelManager - Schemas, migrações e views de relatório`, `ZenyHotelManager - Testes de integração` e a captura da E09.

**Architecture:** o `reporting` ganha uma API pública (`ReportingApi`, raiz do módulo) com `snapshotDailyStatistics(date)` e `archiveDayReports(date)`, ambas idempotentes por data e sem conferir permissão (quem chama, o `endofday`, já se autorizou; padrão da H3). O snapshot é calculado só das views `report_*` e gravado em `reporting.daily_statistics` (uma linha por data). O arquivo monta o modelo do PDF da data no `reporting` (dono do documento), pede o PDF ao `printing` (`PrintingApi.render`, templates `templates/reporting/day-report.xhtml` e `.txt` registrados por `ModuleDocumentTemplates`) e o grava em `files` por uma operação nova da `FilesApi` (gravação pelo servidor, acréscimo mínimo da H6), com finalidade sensível própria do `reporting` marcada como **só do servidor** (sem upload por `POST /api/v1/files`); o índice `reporting.archived_report` liga data, tipo e anexo. Arestas `reporting → printing` e `reporting → files` já constam na linha da §3.1 ("desde a E23"); o `ModularityTest` e a regra ArchUnit passam a aceitá-las.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1, Flyway 12, openhtmltopdf (via `printing`), Testcontainers.

**Riscos:**
- **autenticação ou autorização:** finalidade só do servidor que ninguém envia nem o Administrador; permissão do arquivo e do download sensível (Tasks 2 e 3);
- **dado pessoal ou segredo:** anexo sensível cifrado no bucket; o PDF não tem nome de hóspede (as views não o publicam), mas tem códigos de reserva, saldos e ids de operador (Tasks 2 e 3);
- **cálculo de valor monetário:** receitas por departamento, ADR e RevPAR (Task 1);
- **concorrência:** duas chamadas simultâneas do snapshot ou do arquivo da mesma data (Tasks 1 e 3);
- **migração ou escrita destrutiva de dados:** primeiro schema do `reporting` e operação nova de escrita no `files` (Tasks 1 a 3).

## Global Constraints

- Grafo §3.1 com o `ModularityTest` exato: `reporting` depende de `shared`, `hotel`, `printing` e `files` ao fim da etapa (entram na Task 3); a regra ArchUnit da E09 passa a aceitar as raízes de `printing` e `files`.
- `reporting` lê só views `report_*` (prova pelo `ReportingViewsOnlyTest`, estendido a cada consulta nova).
- `files`: as mudanças são (1) a operação `FilesApi.store` e (2) o sinalizador `FilePurpose.serverOnly` (padrão `false`, as finalidades atuais não mudam), que o catálogo aceita e o upload recusa; nada mais. Anexo sensível cifrado pelo `ContentCipher` como o upload, com SHA-256, trilha `files.file.store` sem conteúdo nem nome; nenhum caminho novo de leitura de sensível pelo servidor.
- Primeira migração do `reporting` (`db/migration/reporting/`), com os privilégios do schema para o papel da aplicação no padrão das demais (precedente do `files`); timestamps posteriores a `maintenance/V2026_11_19_2101`; tabelas do `reporting` só de inserção (`REVOKE UPDATE, DELETE`, `AppendOnlyTables`, `DatabaseRolesTest`).
- Contrato regenerado; `checkOpenApi` e `check-api-breaking.sh` sem `ERR`.
- Código em inglês; KDoc e commits em pt-BR; commits com `Soul:` e `Co-Authored-By` no mesmo bloco final.
- Um Gradle por vez; `timeout` em tudo; sem laço de espera; nunca `--stop`.
- Testes: banco compartilhado; snapshot e arquivo de datas próprias do teste (faixa de ano própria da classe, como a E09), afirmando por marcador ou por diferença o que é estado corrente (UHs por status, pendências); quem cria reserva espera `InventoryListenersQuiescence.await(jdbc)`; dado gravado por SQL sempre válido (o banco compartilhado quebra com dado inválido).
- Verificação da fatia: os testes focados citados mais `timeout 900 ./gradlew checkOpenApi lint`.

## Review Focus

1. **Snapshot chamado duas vezes, e duas vezes ao mesmo tempo, para a mesma data:** um registro só, igual ao primeiro. Teste na Task 1.
2. **Cortesia, uso da casa, day use, cancelada e no-show:** cortesia conta na ocupação e não no ADR; uso da casa em nenhum dos dois; day use, cancelada e no-show fora das noites (`sold AND NOT day_use`). Teste na Task 1.
3. **Data sem nenhum movimento:** snapshot com zeros e ocupação, ADR e RevPAR nulos (denominador 0). Teste na Task 1.
4. **Arquivo chamado de novo, em paralelo, ou depois de falha no meio:** um anexo e uma linha de índice por (data, tipo), sem anexo órfão vinculado. Teste na Task 3.
5. **Upload pelo cliente na finalidade só do servidor, inclusive pelo Administrador; download do PDF por quem não tem a permissão:** recusados. Testes nas Tasks 2 e 3.

## Decisões do plano

- **D1 — snapshot** `ReportingApi.snapshotDailyStatistics(date: LocalDate): DailyStatisticsSnapshot`, na transação de quem chama (`REQUIRED`), grava `reporting.daily_statistics` (`business_date` único; `INSERT … ON CONFLICT DO NOTHING` e releitura). Contagens **disjuntas** das noites `sold AND NOT day_use` de `report_room_nights` na data: `rooms_sold` (`rate_plan_category = NORMAL`), `rooms_complimentary` (`COURTESY`), `rooms_house_use` (`HOUSE_USE`); `rooms_available` (`total − out_of_order` de `report_inventory`, somado por tipo), `rooms_out_of_order`; `arrivals`, `departures` (as definições do painel da E09); `no_shows` (`status = NO_SHOW` com `arrival` na data); `cancellations` (`cancelled_at` na data, no fuso do hotel; só reservas que seguem `CANCELLED`: decisão registrada); receitas de `report_postings` com `business_date` = data, sem `pass_through`: `room_revenue` (`revenue_group = ROOM`), `food_beverage_revenue` (`FOOD_BEVERAGE`), `events_revenue` (zero até o M9), `other_revenue` (os demais grupos, sem `TAXES`). Derivadas gravadas: `occupancy` = (`rooms_sold` + `rooms_complimentary`) ÷ `rooms_available` (`Percent`, `null` com denominador 0 ou acima de 999,9999%, como a E09), `adr` = `room_revenue` ÷ `rooms_sold` (`Money`, 2 casas, `HALF_EVEN`; `null` com 0), `revpar` = `room_revenue` ÷ `rooms_available` (idem). Uma fonte só de receita de hospedagem (os lançamentos), para ADR × ocupação e RevPAR fecharem entre si. Existe → devolve o gravado (não recalcula). Sem guarda de data no código: o KDoc da `ReportingApi` diz "só para a data que fecha ou anteriores; o snapshot não é recalculado", e quem garante é a E13, que chama com a data do fechamento (uma guarda impediria os testes em datas próprias futuras). Fonte única de receita: `room_revenue` inclui toda a receita do grupo `ROOM` da data (day use e o que mais for desse grupo), então o ADR sai um pouco acima da diária média das noites pagas — decisão registrada. Sem endpoint HTTP nesta etapa (KPIs são do M10).
- **D2 — gravação pelo servidor no `files`:** `FilesApi.store(purpose: FilePurposeCode, ownerEntityId: String, name: String, content: ByteArray): FileSummary` — confere a finalidade (tipo detectado pela assinatura, tamanho; violação → `IllegalArgumentException`, erro de programação), cifra se sensível, grava metadados e objeto como o upload (objeto apagado se a transação desfizer), vincula ao dono e audita `files.file.store`; `createdBy` = `currentActor.currentOrNull()?.userId` (nulo no fechamento automático, trilha do sistema). `FilePurpose.serverOnly: Boolean = false`: o catálogo aceita; `POST /api/v1/files` com finalidade `serverOnly` → `400 UNKNOWN_FILE_PURPOSE` para qualquer usuário, inclusive o Administrador (não revela a existência), conferido **antes** do `authorization.require`. Finalidade `reporting.day-report` (PDF, até 5 MB, **sensível**, `serverOnly`, `uploadPermission` = `reporting.archive.read` — exigida pelo tipo, nunca usada —, download `reporting.archive.read`), publicada como bean `ModuleFilePurposes` em `reporting/application/ReportingConfiguration.kt`.
- **D3 — PDF da data:** um documento só, `reporting.day-report` (templates `day-report.xhtml` e `.txt`), com o snapshot (D1), o painel do dia da E09 e as pendências da E09 no momento do arquivo. Sem nome de hóspede. Renderizado por `PrintingApi.render(..., PDF)`. Mantido **sensível** mesmo sem nome (códigos de reserva, saldos, ids de operador; passar de sensível a comum é configuração, o contrário não recifra): decisão registrada, a premissa "com nome de hóspede" da H23 c não se confirmou.
- **D4 — arquivo** `ReportingApi.archiveDayReports(date: LocalDate): List<ArchivedReport>`: para cada tipo de D3, numa transação própria por tipo (`REQUIRES_NEW`, declarado no KDoc da `ReportingApi`), toma trava consultiva por (data, tipo) **antes** de conferir; obtém o snapshot chamando `snapshotDailyStatistics(date)` dentro da própria transação (cria se faltar; na ordem da E13 ele já existe); se já há linha em `reporting.archived_report` (`id`, `business_date`, `kind`, `file_id`, `created_at`; único por data e tipo), devolve a gravada; senão gera o id da linha, renderiza, grava pela `FilesApi.store` com `ownerEntityId` = esse id e insere a linha. Falha de render ou de gravação propaga (o fechamento fica retomável, E13). PDF acima do limite trava a etapa de forma retomável: registrado.
- **D5 — consulta** `GET /api/v1/reports/archive?date=` (`reporting.archive.read`, nova: Gerente e Financeiro; sensível? não — o download sensível tem a trilha do `files`): lista os PDFs da data (tipo, id do anexo, criado em). O download é pelo `GET /api/v1/files/{id}/download-url` do `files` (URL do servidor, com trilha). Desvio do roadmap ("devolve as URLs assinadas"): a lista não emite URLs em massa nem amplia a `FilesApi`; decisão registrada.

## Revisão de arquitetura

(preenchida depois da revisão)

---

### Task 1: Snapshot `DailyStatistics`

**Objetivo:** D1 e a `ReportingApi` com `snapshotDailyStatistics`.

**Critérios de aceite:**
- `DailyStatisticsSnapshotTest` (`@IntegrationTest`, novo): numa data própria futura da classe (faixa de ano própria), com reservas próprias pela API (normal, cortesia, uso da casa, day use, cancelada) e, porque a API lança na data corrente, lançamentos de hospedagem e A&B e um no-show gravados por SQL **válido** nas views de origem (linhas só de inserção do `billing` com `business_date` = a data própria, códigos de transação existentes; status de reserva coerente), o snapshot traz as contagens disjuntas, as receitas e as derivadas pelo D1 (Review Focus 2); chamado duas vezes e em paralelo, um registro só (Review Focus 1).
- `DailyStatisticsCalculatorTest` (unidade): data sem movimento → zeros e derivadas `null` (Review Focus 3); arredondamento `HALF_EVEN` do ADR e do RevPAR; ocupação acima do teto → `null`.
- `ReportingViewsOnlyTest` estendido; `DatabaseRolesTest` com a tabela nova (só de inserção). Verificação: `timeout 900 ./gradlew test --tests '*DailyStatistics*' --tests '*ReportingViewsOnlyTest' --tests '*DatabaseRolesTest'` e `timeout 900 ./gradlew checkOpenApi lint`.

**Áreas tocadas:** `reporting/ReportingApi.kt` (novo), `reporting/application/DailyStatisticsService.kt` (novo), `reporting/domain/DailyStatisticsCalculator.kt` (novo), `reporting/application/ReportingQueries.kt`, `reporting/infrastructure/JdbcReportingQueries.kt`, `reporting/infrastructure/JdbcDailyStatisticsStore.kt` (novo), `reporting/application/ReportingConfiguration.kt` (`AppendOnlyTables`), migrações `db/migration/reporting/` (schema, privilégios, tabela).

**Tamanho:** ~55 min; 7 arquivos de produção, mais migrações.

**Depende de:** nenhuma dentro do plano.

**Interfaces (produz):** `ReportingApi.snapshotDailyStatistics(date: LocalDate): DailyStatisticsSnapshot`; schema `reporting` com privilégios.

### Task 2: Gravação pelo servidor no `files` e finalidade só do servidor

**Objetivo:** D2.

**Critérios de aceite:**
- `FilesServerStoreTest` (`@IntegrationTest`, novo, no `files`): `store` de um PDF numa finalidade sensível de teste grava cifrado no bucket (o objeto não tem o conteúdo em claro), vinculado ao dono, com SHA-256 e trilha `files.file.store` sem nome nem conteúdo; o download pela URL do servidor devolve o mesmo conteúdo; tipo que não confere → `IllegalArgumentException`; transação desfeita apaga o objeto; uma finalidade comum também funciona; **sem ator** (fora de requisição) grava com `createdBy` nulo e trilha do sistema.
- `FilesServerOnlyPurposeTest` (`@IntegrationTest`, novo): `POST /api/v1/files` com a finalidade `serverOnly` de teste → `400 UNKNOWN_FILE_PURPOSE`, inclusive para o Administrador; o catálogo aceita finalidade `serverOnly`; as finalidades existentes seguem `serverOnly = false`.
- Os testes do `files` seguem verdes: `timeout 900 ./gradlew test --tests 'com.zeny.hotel.files.*' --tests '*FilesServer*'` e `timeout 900 ./gradlew checkOpenApi lint` (o contrato não muda).

**Áreas tocadas:** `files/FilesApi.kt`, `files/FilePurposes.kt` (`serverOnly`), `files/application/FilesService.kt` (upload recusa `serverOnly`; `store`), `files/application/FilesConfiguration.kt` (catálogo), `src/test/kotlin/filesexample/` (finalidade sensível `serverOnly` de teste).

**Tamanho:** ~45 min; 4 arquivos de produção.

**Depende de:** nenhuma.

**Interfaces (produz):** `FilesApi.store(purpose: FilePurposeCode, ownerEntityId: String, name: String, content: ByteArray): FileSummary`; `FilePurpose.serverOnly`.

### Task 3: PDF da data, arquivo e consulta

**Objetivo:** D3, D4 e D5.

**Critérios de aceite:**
- `DayReportsArchiveTest` (`@IntegrationTest`, novo): `archiveDayReports` de uma data própria grava o PDF (assinatura `%PDF`, texto com os números do snapshot e marcadores próprios) como anexo sensível, uma vez por data mesmo chamado duas vezes e em paralelo, com um único `stored_file` vinculado ao dono e uma linha de índice (Review Focus 4); `GET /api/v1/reports/archive?date=` o lista; a URL de download do `files` devolve o PDF e grava trilha; sem `reporting.archive.read` → `403` na lista e no download (Review Focus 5); nenhum nome de hóspede no PDF.
- `ModularityTest` (`reporting depends only on shared, hotel, printing, files`), regra ArchUnit ajustada, `ReportingPermissionsTest` (a permissão nova no D2), `AuthorizationTest` (listas do testador, se falharem, vão ao testador), `DatabaseRolesTest` (índice só de inserção), `ReportingViewsOnlyTest`. Verificação: `timeout 900 ./gradlew test --tests '*DayReportsArchiveTest' --tests '*ModularityTest' --tests '*LayerArchitectureTest' --tests '*ReportingViewsOnlyTest' --tests '*ReportingPermissionsTest' --tests '*AuthorizationTest' --tests '*DatabaseRolesTest'`.
- Contrato regenerado; `timeout 900 ./gradlew checkOpenApi lint` e `timeout 600 .github/scripts/check-api-breaking.sh` sem `ERR`.

**Áreas tocadas:** `reporting/ReportingApi.kt`, `reporting/ReportingModule.kt`, `reporting/ReportingPermissions.kt` (`reporting.archive.read`; KDoc), `reporting/ReportingFilePurposes.kt` (novo), `reporting/application/ReportingConfiguration.kt` (bean da finalidade), `reporting/application/DayReportsArchive.kt` (novo, com o bean `ModuleDocumentTemplates`), `reporting/infrastructure/JdbcArchivedReportStore.kt` (novo), `reporting/web/ReportsController.kt`, templates `src/main/resources/templates/reporting/day-report.xhtml` e `day-report.txt`, migração da tabela do índice, `ModularityTest`, a regra ArchUnit, `api/openapi.yaml`.

**Tamanho:** ~60 min; 8 arquivos Kotlin de produção, 2 templates e 1 migração (11), mais o contrato.

**Depende de:** Tasks 1 e 2.
