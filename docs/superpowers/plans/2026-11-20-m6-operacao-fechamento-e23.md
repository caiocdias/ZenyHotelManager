# E23 do M6 — Snapshot diário e arquivo dos PDFs: plano de implementação

> **Para agentes:** execução por `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde, não o código.

**Goal:** dar ao `reporting` a API pública que o fechamento (E13) chama: `snapshotDailyStatistics(date)` (SPEC §8.2.9, regras de Q-15 pela H9) e `archiveDayReports(date)` (PDFs da data renderizados por `printing` e guardados em `files` como anexo sensível, H6 e H23 c), mais a listagem e o download do arquivo por data.

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E23", H6, H7, H9, H23; handoff da E09; SPEC §8.2.9 (`DailyStatistics`), RF-REL-07, RF-FEC-02, RN-13; ARCHITECTURE §3.1 (linha `reporting`), §7 ("Documentos impressos"), §11. Brain: `ZenyHotelManager - Módulo files` (finalidades, sensível, `readContent` só de comum, D-E11-7), `ZenyHotelManager - Módulo printing` (documento do dono, `ModuleDocumentTemplates`, `render`), `ZenyHotelManager - Schemas, migrações e views de relatório`, `ZenyHotelManager - Testes de integração` e a captura da E09.

**Architecture:** o `reporting` ganha uma API pública (`ReportingApi`, raiz do módulo) com `snapshotDailyStatistics(date)` e `archiveDayReports(date)`, ambas idempotentes por data e sem conferir permissão (quem chama, o `endofday`, já se autorizou). O snapshot é calculado só das views `report_*` (as da E09 e as anteriores) e gravado em `reporting.daily_statistics` (uma linha por data). O arquivo monta os modelos dos PDFs no `reporting` (dono do documento), pede o PDF ao `printing` (`PrintingApi.render`, templates em `templates/reporting/` registrados por `ModuleDocumentTemplates`) e o grava em `files` por uma operação nova da `FilesApi` (gravação pelo servidor, acréscimo mínimo da H6), com finalidade sensível própria do `reporting`; um índice `reporting.archived_report` liga data, tipo e anexo. Arestas `reporting → printing` e `reporting → files` (já na linha da §3.1 desde a E09; o `ModularityTest` passa a incluí-las).

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1, Flyway 12, openhtmltopdf (via `printing`), Testcontainers.

**Riscos:**
- **autenticação ou autorização:** permissão do arquivo e do download sensível (Task 3);
- **dado pessoal ou segredo:** anexo sensível cifrado no bucket; PDFs sem dado de hóspede além do que as views dão (nenhum nome: `guests.report_guests` não publica nome) (Tasks 2 e 3);
- **cálculo de valor monetário:** receitas por departamento, ADR e RevPAR do snapshot (Task 1);
- **concorrência:** duas chamadas simultâneas do snapshot ou do arquivo da mesma data (Tasks 1 e 3);
- **migração ou escrita destrutiva de dados:** tabelas novas no `reporting` e operação nova de escrita no `files` (Tasks 1 a 3).

## Global Constraints

- Grafo §3.1 com o `ModularityTest` exato: `reporting` depende de `shared`, `hotel`, `printing` e `files` ao fim da etapa (`printing` e `files` entram nas fatias que os usam); a regra ArchUnit da E09 passa a aceitar as raízes de `printing` e `files`.
- `reporting` lê só views `report_*` (prova pelo `ReportingViewsOnlyTest`, estendido a cada consulta nova).
- `files`: a gravação pelo servidor é a única mudança; anexo sensível cifrado pelo `ContentCipher` como o upload, com SHA-256, trilha `files.file.store` sem conteúdo nem nome; nenhum caminho novo de leitura de sensível pelo servidor (o download continua pela URL assinada do servidor, com trilha).
- Idempotência por data: índice único; segunda chamada não grava outra linha nem outro anexo; corrida serializada (trava consultiva por data ou `ON CONFLICT`).
- Migrações com timestamp posterior à última do projeto no momento; tabelas do `reporting` sem `DELETE` para o papel da aplicação (`NO_DELETE_TABLES`).
- Contrato regenerado; `checkOpenApi` e `check-api-breaking.sh` sem `ERR`.
- Código em inglês; KDoc e commits em pt-BR; commits com `Soul:`.
- Um Gradle por vez; `timeout` em tudo; sem laço de espera; nunca `--stop`.
- Testes: banco compartilhado; snapshot e arquivo de uma data própria do teste (data futura ou passada sem colisão) e afirmações sobre os próprios dados ou por diferença; quem cria reserva espera `InventoryListenersQuiescence.await(jdbc)`.
- Verificação da fatia: os testes focados citados mais `timeout 900 ./gradlew checkOpenApi lint`.

## Review Focus

1. **Snapshot chamado duas vezes, e duas vezes ao mesmo tempo, para a mesma data:** um registro só, igual ao primeiro. Teste na Task 1.
2. **Cortesia e uso da casa:** cortesia conta na ocupação e não no ADR; uso da casa em nenhum dos dois (H9, `counts_in_occupancy`/`counts_in_adr` de `report_room_nights`). Teste na Task 1.
3. **Data sem nenhum movimento:** snapshot com zeros e ocupação/ADR/RevPAR nulos ou zero conforme D1, sem erro. Teste na Task 1.
4. **Arquivo chamado de novo, ou depois de falha no meio (um PDF gravado, outro não):** completa o que falta sem duplicar. Teste na Task 3.
5. **Download do PDF arquivado por quem não tem a permissão:** `403`; por quem tem, URL do servidor com trilha e conteúdo decifrado igual ao renderizado. Teste na Task 3.

## Decisões do plano

- **D1 — snapshot** `ReportingApi.snapshotDailyStatistics(date: LocalDate): DailyStatisticsSnapshot` grava `reporting.daily_statistics` (`business_date` único): `rooms_available` (`total − out_of_order` de `report_inventory`, somado por tipo), `rooms_out_of_order`, `rooms_sold` (noites `sold AND NOT day_use` de `report_room_nights`), `rooms_complimentary` (`rate_plan_category = COMPLIMENTARY`), `rooms_house_use` (`HOUSE_USE`), `arrivals`, `departures` (como o painel da E09), `no_shows` (`status = NO_SHOW` com `arrival` na data), `cancellations` (reservas com `cancelled_at` na data, convertido no fuso do hotel; a coluna já está em `report_reservations`), `room_revenue`, `food_beverage_revenue`, `events_revenue` (zero até o M9), `other_revenue` (por `revenue_group` de `report_postings` com `business_date` = data, sem `pass_through`, `ROOM` → hospedagem, `FOOD_BEVERAGE` → A&B, o resto → outras, sem `TAXES`), e as derivadas `occupancy` (noites `counts_in_occupancy` ÷ disponíveis, `null` se 0), `adr` (receita de hospedagem das noites `counts_in_adr` ÷ noites `counts_in_adr`, `null` se 0) e `revpar` (receita de hospedagem ÷ disponíveis, `null` se 0), gravadas no snapshot. Idempotente: existe → devolve o gravado (não recalcula; recálculo é decisão futura). Leitura `GET /api/v1/reports/daily-statistics?date=` com `reporting.dashboard.read`.
- **D2 — gravação pelo servidor no `files`:** `FilesApi.store(purpose: FilePurposeCode, ownerEntityId: UUID, name: String, content: ByteArray): FileSummary` — confere a finalidade (tipo detectado pela assinatura, tamanho), cifra se sensível, grava metadados e objeto como o upload (objeto apagado se a transação desfizer), vincula ao dono e audita `files.file.store`; `createdBy` = o ator corrente (o sistema no fechamento). Finalidade `reporting.day-report` (PDF, até 5 MB, **sensível**, upload: nenhuma permissão de usuário — a finalidade não aceita `POST /api/v1/files`; download `reporting.archive.read`).
- **D3 — PDFs da data** (templates `templates/reporting/`): `reporting.day-summary` (o snapshot e o painel do dia: chegadas, saídas, hospedados, ocupação, UHs por status) e `reporting.day-pending` (as pendências da E09: contas com saldo, caixas abertos, FNRH pendentes). Sem nome de hóspede (as views não o publicam). Renderizados por `PrintingApi.render(..., PDF)`.
- **D4 — arquivo** `ReportingApi.archiveDayReports(date: LocalDate): List<ArchivedReport>`: para cada tipo de D3 sem linha em `reporting.archived_report` (`business_date`, `kind`, `file_id`, `created_at`; único por data e tipo), renderiza, grava pela `FilesApi.store` e insere o índice na mesma transação por tipo; os já arquivados não são refeitos (Review Focus 4). Uma falha de render ou de gravação propaga (o fechamento fica retomável, E13).
- **D5 — consulta** `GET /api/v1/reports/archive?date=` (`reporting.archive.read`, nova: Gerente e Financeiro): lista os PDFs da data (tipo, id do anexo, criado em); o download é pelo `GET /api/v1/files/{id}/download-url` do `files` (sensível: URL do servidor, com trilha).

---

### Task 1: Snapshot `DailyStatistics`

**Objetivo:** D1 e a `ReportingApi` com `snapshotDailyStatistics`.

**Critérios de aceite:**
- `DailyStatisticsSnapshotTest` (`@IntegrationTest`, novo): com reservas próprias numa data própria (cortesia, uso da casa, day use, no-show, cancelamento, lançamentos de hospedagem e A&B), o snapshot traz as contagens e receitas esperadas e as derivadas pela H9 (Review Focus 2); chamado duas vezes e em paralelo, um registro só (Review Focus 1).
- `DailyStatisticsCalculatorTest` (unidade): data sem movimento → zeros e derivadas `null` (Review Focus 3); arredondamento do ADR e do RevPAR.
- `ReportingViewsOnlyTest` estendido; `DatabaseRolesTest` com a tabela nova. Verificação: `timeout 900 ./gradlew test --tests '*DailyStatistics*' --tests '*ReportingViewsOnlyTest' --tests '*DatabaseRolesTest'`.
- `GET /api/v1/reports/daily-statistics?date=` (`reporting.dashboard.read`; data sem snapshot → `404 DAILY_STATISTICS_NOT_FOUND`); contrato regenerado; `timeout 900 ./gradlew checkOpenApi lint` verde.

**Áreas tocadas:** `reporting/ReportingApi.kt` (novo), `reporting/application/DailyStatisticsService.kt` (novo), `reporting/domain/DailyStatisticsCalculator.kt` (novo), `reporting/application/ReportingQueries.kt`, `reporting/infrastructure/JdbcReportingQueries.kt`, `reporting/infrastructure/JdbcDailyStatisticsStore.kt` (novo), `reporting/web/ReportsController.kt`, migração `db/migration/reporting/` (tabela e grants), `api/openapi.yaml`.

**Tamanho:** ~55 min; 7 arquivos de produção, mais migração e contrato.

**Depende de:** nenhuma dentro do plano (a E09 está concluída).

**Interfaces (produz):** `ReportingApi.snapshotDailyStatistics(date: LocalDate): DailyStatisticsSnapshot`.

### Task 2: Gravação pelo servidor no `files`

**Objetivo:** D2.

**Critérios de aceite:**
- `FilesServerStoreTest` (`@IntegrationTest`, novo, no `files`): `store` de um PDF numa finalidade sensível de teste grava cifrado no bucket (o objeto não tem o conteúdo em claro), vinculado ao dono, com SHA-256 e trilha `files.file.store` sem nome nem conteúdo; o download pela URL do servidor devolve o mesmo conteúdo; tipo que não confere com a finalidade → `IllegalArgumentException` (erro de programação) ou o erro de domínio do upload, conforme o padrão do módulo; transação desfeita apaga o objeto; uma finalidade comum também funciona.
- Os testes do `files` seguem verdes: `timeout 900 ./gradlew test --tests 'com.zeny.hotel.files.*'`.
- `timeout 900 ./gradlew checkOpenApi lint` verde (o contrato não muda).

**Áreas tocadas:** `files/FilesApi.kt`, `files/application/` (caso de uso do upload, reaproveitado), `src/test/kotlin/filesexample/` (finalidade sensível de teste, se faltar).

**Tamanho:** ~40 min; 2 a 3 arquivos de produção.

**Depende de:** nenhuma.

**Interfaces (produz):** `FilesApi.store(purpose: FilePurposeCode, ownerEntityId: UUID, name: String, content: ByteArray): FileSummary`.

### Task 3: PDFs, arquivo por data e consulta

**Objetivo:** D3, D4 e D5.

**Critérios de aceite:**
- `DayReportsArchiveTest` (`@IntegrationTest`, novo): `archiveDayReports` de uma data própria grava os dois PDFs (assinatura `%PDF`, texto com os números esperados) como anexo sensível, uma vez por data mesmo chamado duas vezes e em paralelo; com um dos tipos já arquivado, grava só o outro (Review Focus 4); `GET /api/v1/reports/archive?date=` lista os dois; a URL de download do `files` devolve o PDF e grava trilha; sem `reporting.archive.read` → `403` na lista e no download (Review Focus 5); nenhum nome de hóspede no PDF.
- `ModularityTest` (`reporting depends only on shared, hotel, printing, files`), regra ArchUnit ajustada, `AuthorizationTest` (listas do testador, se falharem, vão ao testador). Verificação: `timeout 900 ./gradlew test --tests '*DayReportsArchiveTest' --tests '*ModularityTest' --tests '*LayerArchitectureTest' --tests '*ReportingViewsOnlyTest'`.
- Contrato regenerado; `timeout 900 ./gradlew checkOpenApi lint` e `timeout 600 .github/scripts/check-api-breaking.sh` sem `ERR`.

**Áreas tocadas:** `reporting/ReportingApi.kt`, `reporting/ReportingModule.kt`, `reporting/ReportingPermissions.kt` (`reporting.archive.read`), `reporting/ReportingFilePurposes.kt` (novo), `reporting/application/DayReportsArchive.kt` (novo, com o bean `ModuleDocumentTemplates`), `reporting/application/ReportingConfiguration.kt`, `reporting/infrastructure/JdbcArchivedReportStore.kt` (novo), `reporting/web/ReportsController.kt`, templates `src/main/resources/templates/reporting/day-summary.*` e `day-pending.*`, migração da tabela do índice, `ModularityTest`, a regra ArchUnit, `api/openapi.yaml`.

**Tamanho:** ~60 min; 8 arquivos Kotlin de produção, mais templates, migração e contrato.

**Depende de:** Tasks 1 e 2.
