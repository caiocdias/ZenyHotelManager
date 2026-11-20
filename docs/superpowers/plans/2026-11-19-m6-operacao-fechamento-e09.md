# E09 do M6 — Relatórios operacionais e painel do dia: plano de implementação

> **Para agentes:** execução por `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde, não o código.

**Goal:** criar o módulo `reporting` com o painel do dia (RF-REL-01), os relatórios operacionais de governança (RF-GOV-11, RF-REL-06) e de manutenção, a exportação CSV/XLSX (RF-REL-07, fastexcel pela H23 b) e o relatório de pendências do fechamento (RF-FEC-04: contas com saldo de hóspedes que saíram, caixas não fechados, FNRH pendentes), lendo só views `report_*` publicadas pelos módulos (ARCHITECTURE §11).

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E09" (depois da divisão da H23), H7, H9, H23; SPEC RF-REL-01, RF-REL-06, RF-REL-07, RF-GOV-11, RF-FEC-04, RN-13, §8.2.9; ARCHITECTURE §3.1 (linha `reporting`), §7, §11 ("Leitura para relatórios"). Brain: `ZenyHotelManager - Schemas, migrações e views de relatório` (convenção das views, privilégios, prova "só as views" com papel próprio), `ZenyHotelManager - Arquitetura e estrutura de módulos`, `ZenyHotelManager - Convenções de API`, `ZenyHotelManager - Segurança, autenticação e autorização`, `ZenyHotelManager - Ambiente, banco, chaves e build` (catálogo, lockfiles, scanner), `ZenyHotelManager - Módulo housekeeping`, `ZenyHotelManager - Módulo maintenance` (captura da E07) e `ZenyHotelManager - Testes de integração`.

**Architecture:** as views novas nascem nos módulos donos, como API de leitura deles (`rooms.report_room_status`, `housekeeping.report_tasks`, `housekeeping.report_inspections`, `maintenance.report_work_orders`), testadas no próprio módulo; as de `reservations`, `billing` e `fnrh` já existem. O `reporting` (camada Gestão) não importa classe de outro módulo de negócio: consulta só `report_*` por um adaptador `JdbcClient` em `infrastructure`, atrás de portas em `application`, e chama só o `hotel` (data operacional e fuso, `HotelApi`; aresta nova para camada inferior, acrescentada à linha `reporting` da §3.1 na mesma mudança) e o `shared`. A exportação reaproveita a mesma consulta da resposta JSON e serializa em CSV (sem biblioteca) ou XLSX (`org.dhatim:fastexcel`).

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1, Flyway 12, fastexcel (novo), Testcontainers.

**Riscos:**
- **autenticação ou autorização:** permissões novas de relatório; quem vê o relatório de pendências (contas e caixas) (Tasks 2 e 3);
- **dado pessoal ou segredo:** relatórios operacionais sem dado de hóspede (RN-13); o de pendências mostra só o número da conta e o saldo, sem nome; views sem texto livre (Tasks 1 a 3);
- **cálculo de valor monetário:** saldo das contas no relatório de pendências (Task 2);
- **parsing de entrada externa não confiável:** filtros de período e `?format=` (Tasks 2 e 3);
- **migração ou escrita destrutiva de dados:** views novas e seus privilégios (Task 1).

## Global Constraints

- Grafo §3.1 com o `ModularityTest` exato: `reporting` depende só de `shared` e `hotel` nesta etapa (`files` e `printing` entram na E23); a linha da §3.1 ganha `hotel` na mesma mudança. Regra ArchUnit nova: nenhuma classe de `com.zeny.hotel.reporting..` depende de classe de outro módulo além de `shared` e da raiz do `hotel`.
- Views pela convenção do brain: `<módulo>.report_<assunto>`, só sobre tabelas do próprio schema, uma linha por fato, ids opacos, sem texto livre nem dado de hóspede, contrato no comentário da migração; `GRANT SELECT` e `REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER` ao papel da aplicação, um comando por view; `DatabaseRolesTest`.
- Prova "só as views": o teste de integração do `reporting` roda as consultas com um papel próprio, sem login, com só `USAGE` nos schemas e `SELECT` nas views (`SET ROLE`), como o `ChecklistReportsTest`.
- Período em datas convertido em instantes no fuso do hotel pelo caso de uso; filtros de data com a entrada estrita da E20; período máximo de 366 dias.
- Biblioteca nova só pelo catálogo `gradle/libs.versions.toml`, sem versão literal no build, com os lockfiles regravados (`--write-locks`) e o `osv-scanner` sem High/Critical novo (H7, H23 b).
- Contrato regenerado; `checkOpenApi` e `check-api-breaking.sh` sem `ERR`; a exportação declara `text/csv` e `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` no contrato.
- RN-13: nenhum relatório de governança ou manutenção expõe nome, documento ou contato de hóspede, nem texto livre (descrição de OS, notas).
- Código em inglês; KDoc e commits em pt-BR; commits com `Soul:`.
- Um Gradle por vez; `timeout` em tudo; sem laço de espera; nunca `--stop`.
- Testes: banco compartilhado, só os próprios dados (filtrar pelos ids criados no teste ou por UHs próprias); a data operacional do banco compartilhado pode ter avançado (usar `HotelApi.currentBusinessDate()` no momento e `InventoryListenersQuiescence.await(jdbc)` antes de criar reservas).
- Verificação da fatia: os testes focados citados mais `timeout 900 ./gradlew checkOpenApi lint`.

## Review Focus

1. **Painel de um dia sem nenhum movimento** e **datas futuras sem inventário:** zeros, não erro. Teste na Task 2.
2. **Relatório de governança de um período em que uma camareira não tem tarefa concluída** e **tipo sem inspeção:** a linha aparece com zero, e a taxa de reprovação é `null` (não 0) quando não houve inspeção. Teste na Task 3.
3. **`?format=` desconhecido, `?format=xlsx` com `Accept: application/json`:** `400` no parâmetro; o formato pedido manda no corpo e no `Content-Type`. Teste na Task 3.
4. **Texto com `;`, aspas, quebra de linha e prefixo `=`/`+`/`-`/`@` (fórmula) num campo exportado:** CSV com escape RFC 4180 e neutralização de fórmula (prefixo `'`), XLSX como texto. Teste na Task 3.
5. **Conta com saldo de reserva `CHECKED_OUT` há muito tempo e caixa aberto de outro dia:** aparecem no relatório de pendências; conta com saldo zero e caixa fechado não. Teste na Task 2.

## Decisões do plano

- **D1 — views novas nos módulos donos** (Task 1), cada uma com o contrato no comentário:
  - `rooms.report_room_status`: uma linha por UH ativa com `room_id`, `room_type_id`, `floor`, `occupancy`, `housekeeping_status`, `out_of_order` (em vigor na data operacional) e `updated_at`.
  - `housekeeping.report_tasks`: uma linha por tarefa, com `task_id`, `business_date`, `room_id`, `area_id`, `cleaning_type_id`, `source`, `status`, `credits`, `assignee_employee_id`, `started_at`, `completed_at` (sem `notes`).
  - `housekeeping.report_inspections`: uma linha por inspeção concluída, com `inspection_id`, `task_id`, `room_id`, `result`, `completed_at`, `inspector_id`.
  - `maintenance.report_work_orders`: uma linha por OS, com `work_order_id`, `number`, `room_id`, `area_id`, `category`, `priority`, `origin`, `status`, `opened_at`, `started_at`, `closed_at`, `labor_cost`, `third_party_cost`, `parts_cost` (sem descrição, motivos, notas nem terceiro).
  Se a forma de alguma tabela interna não der a coluna, a view a deriva ou a omite, com nota no contrato.
- **D2 — permissões** (`ReportingPermissions`): `reporting.dashboard.read` (painel; Gerente, Recepcionista, Governanta), `reporting.operations.read` (relatórios de governança e manutenção; Gerente, Governanta, Manutenção) e `reporting.pending.read` (pendências do fechamento; Gerente e Financeiro), todas só `ALL`, não sensíveis.
- **D3 — painel** `GET /api/v1/reports/day-panel?days=N` (N de 1 a 30, padrão 7): para a data operacional e as N−1 seguintes, chegadas, saídas e hospedados (contagens de `reservations.report_reservations`/`report_room_nights`), ocupação (UHs vendidas ÷ UHs disponíveis, com o OOO de `rooms.report_room_status`/`reservations.report_inventory`), e, só para hoje, as UHs por status de governança e ocupação. Sem nome de hóspede (contagens).
- **D4 — pendências** `GET /api/v1/reports/end-of-day-pending?date=` (padrão: a data operacional): contas abertas com saldo ≠ 0 de reservas já saídas (`billing.report_folios` de dono `RESERVATION` × `reservations.report_reservations` com `status = CHECKED_OUT`; o saldo é calculado só das views do `billing`, `report_postings` menos `report_payments`, pela mesma regra do extrato; se as views não bastarem para o saldo, a Task 1 acrescenta ao `billing` a view `billing.report_folio_balances` (uma linha por conta com o saldo); número da conta, número da reserva, saldo, data de saída; sem nome), caixas não fechados (`billing.report_cashier_shifts`: número, aberto em, operador id) e FNRH pendentes (`fnrh.report_submissions` com status pendente ou falho: operação, reserva, tentativas, última falha). Notas rejeitadas e ARI ficam para o M7/M8 (fora de escopo).
- **D5 — relatórios operacionais** `GET /api/v1/reports/housekeeping?from=&to=` (por camareira: UHs, créditos; por tipo de limpeza × tipo de UH: tempo médio entre `started_at` e `completed_at` em minutos; taxa de reprovação = reprovadas ÷ inspeções concluídas, `null` sem inspeção) e `GET /api/v1/reports/maintenance?from=&to=` (por status, por categoria e tempo médio de atendimento, `opened_at` → `closed_at`, das concluídas no período). Período por data local no fuso do hotel.
- **D6 — exportação:** os três relatórios e o painel aceitam `?format=json|csv|xlsx` (padrão `json`); CSV em UTF-8 com BOM, separador `;` (Excel pt-BR), RFC 4180, neutralização de fórmula; XLSX pela fastexcel, uma aba por tabela do relatório, cabeçalhos em pt-BR; o arquivo tem as mesmas linhas da resposta JSON. `Content-Disposition: attachment; filename="<relatorio>-<data>.<ext>"`.

---

### Task 1: Views novas nos módulos donos

**Objetivo:** D1.

**Critérios de aceite:**
- `RoomsReportViewsTest`, `HousekeepingReportViewsTest` e `MaintenanceReportViewsTest` (`@IntegrationTest`, novos, cada um no seu módulo): dados semeados aparecem na view com as colunas do contrato; nenhuma coluna de texto livre (conferido pelas colunas de `information_schema.columns`); o papel da aplicação só tem `SELECT` na view. Verificação: `timeout 900 ./gradlew test --tests '*ReportViewsTest'`.
- `DatabaseRolesTest` verde. Verificação: `timeout 900 ./gradlew test --tests '*DatabaseRolesTest'`.
- `timeout 900 ./gradlew checkOpenApi lint` verde.

**Áreas tocadas:** migrações em `db/migration/rooms/`, `db/migration/housekeeping/` e `db/migration/maintenance/` (uma por módulo) e, se o saldo não sair das views atuais (D4), uma em `db/migration/billing/` com `billing.report_folio_balances` e o teste dela; os testes novos.

**Tamanho:** ~40 min; 0 arquivos Kotlin de produção, 3 migrações.

**Depende de:** nenhuma.

**Interfaces (produz):** as quatro views e suas colunas (D1).

### Task 2: Módulo `reporting`, painel e pendências

**Objetivo:** D2 a D4 (sem exportação).

**Critérios de aceite:**
- `DayPanelApiTest` (`@IntegrationTest`, novo): com reservas, UHs e status semeados, `GET /api/v1/reports/day-panel` devolve chegadas, saídas, hospedados, UHs por status e ocupação de hoje e dos próximos dias conferidos contra os dados; dia sem movimento dá zeros (Review Focus 1); `days` fora de 1..30 → `400`; sem `reporting.dashboard.read` → `403`.
- `EndOfDayPendingApiTest` (`@IntegrationTest`, novo): conta com saldo de hóspede que saiu, caixa aberto e submissão FNRH pendente aparecem; saldo zero e caixa fechado não (Review Focus 5); nenhum nome de hóspede na resposta; sem `reporting.pending.read` → `403`.
- `ReportingViewsOnlyTest` (`@IntegrationTest`, novo): as consultas do adaptador rodam com o papel que só tem `SELECT` nas views (`SET ROLE`).
- `ModularityTest` (`reporting depends only on shared, hotel`) e a regra ArchUnit nova; a linha `reporting` da §3.1 com `hotel`. Verificação: `timeout 900 ./gradlew test --tests '*DayPanelApiTest' --tests '*EndOfDayPendingApiTest' --tests '*ReportingViewsOnlyTest' --tests '*ModularityTest' --tests '*LayerArchitectureTest' --tests '*AuthorizationTest'`.
- Contrato regenerado; `timeout 900 ./gradlew checkOpenApi lint` verde.

**Áreas tocadas:** `reporting/` novo (`ReportingModule.kt`, `ReportingPermissions.kt`, `application/DayPanelService.kt`, `application/EndOfDayPendingService.kt`, `application/ReportingQueries.kt` (portas), `application/ReportingConfiguration.kt`, `infrastructure/JdbcReportingQueries.kt`, `web/ReportsController.kt`), `docs/ARCHITECTURE.md` (§3.1), `ModularityTest`, a regra ArchUnit, `api/openapi.yaml`.

**Tamanho:** ~60 min; 9 arquivos de produção, mais a ARCHITECTURE e o contrato.

**Depende de:** Task 1.

**Interfaces (produz):** portas `ReportingQueries` e `ReportsController`, para a Task 3 acrescentar os relatórios operacionais e a exportação.

### Task 3: Relatórios operacionais e exportação CSV/XLSX

**Objetivo:** D5 e D6.

**Critérios de aceite:**
- `OperationalReportsApiTest` (`@IntegrationTest`, novo): relatório de governança (UHs e créditos por camareira, tempo médio por tipo de limpeza × tipo de UH, taxa de reprovação, `null` sem inspeção; Review Focus 2) e de manutenção (OS por status, categoria e tempo médio) conferidos contra dados semeados; período acima de 366 dias ou `from > to` → `400`; nenhum texto livre nem dado de hóspede.
- `ReportExportTest` (`@IntegrationTest`, novo): `?format=csv` e `?format=xlsx` dos três relatórios e do painel, relidos no teste (fastexcel-reader só em teste), têm as mesmas linhas da resposta JSON; escape e neutralização de fórmula (Review Focus 4); formato desconhecido → `400` (Review Focus 3); `Content-Type` e `Content-Disposition` corretos.
- Biblioteca pelo catálogo, lockfiles regravados, `timeout 600 osv-scanner scan source -L gradle.lockfile -L buildscript-gradle.lockfile -L "gradle.lockfile:settings-gradle.lockfile"` sem Critical/High (saída no relatório). Verificação: `timeout 900 ./gradlew test --tests '*OperationalReportsApiTest' --tests '*ReportExportTest'` e `timeout 900 ./gradlew checkOpenApi lint`.

**Áreas tocadas:** `gradle/libs.versions.toml`, `build.gradle.kts` (dependência pelo catálogo), lockfiles, `reporting/application/OperationalReportsService.kt` (novo), `reporting/application/ReportExport.kt` (novo: CSV e XLSX), `reporting/application/ReportingQueries.kt`, `reporting/infrastructure/JdbcReportingQueries.kt`, `reporting/web/ReportsController.kt`, `api/openapi.yaml`.

**Tamanho:** ~60 min; 7 arquivos de produção, mais catálogo, lockfiles e contrato.

**Depende de:** Task 2.
