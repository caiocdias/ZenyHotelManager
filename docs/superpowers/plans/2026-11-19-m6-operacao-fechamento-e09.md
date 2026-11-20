# E09 do M6 — Relatórios operacionais e painel do dia: plano de implementação

> **Para agentes:** execução por `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde, não o código.

**Goal:** criar o módulo `reporting` com o painel do dia (RF-REL-01), os relatórios operacionais de governança (RF-GOV-11, RF-REL-06) e de manutenção, a exportação CSV/XLSX (RF-REL-07, fastexcel pela H23 b) e o relatório de pendências do fechamento (RF-FEC-04: contas com saldo de hóspedes que saíram, caixas não fechados, FNRH pendentes), lendo só views `report_*` publicadas pelos módulos (ARCHITECTURE §11).

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E09" (depois da divisão da H23), H7, H9, H23; SPEC RF-REL-01, RF-REL-06, RF-REL-07, RF-GOV-11, RF-FEC-04, RN-13, §8.2.9; ARCHITECTURE §3.1 (linha `reporting`), §7, §11 ("Leitura para relatórios"). Brain: `ZenyHotelManager - Schemas, migrações e views de relatório` (convenção das views, privilégios, prova "só as views" com papel próprio), `ZenyHotelManager - Arquitetura e estrutura de módulos`, `ZenyHotelManager - Convenções de API`, `ZenyHotelManager - Segurança, autenticação e autorização`, `ZenyHotelManager - Ambiente, banco, chaves e build` (catálogo, lockfiles, scanner), `ZenyHotelManager - Módulo housekeeping`, `ZenyHotelManager - Módulo maintenance` (captura da E07) e `ZenyHotelManager - Testes de integração`.

**Architecture:** as views novas nascem nos módulos donos, como API de leitura deles (`rooms.report_room_status`, `rooms.report_out_of_order`, `housekeeping.report_tasks`, `housekeeping.report_inspections`, `maintenance.report_work_orders`), testadas no próprio módulo, só sobre tabelas do próprio schema; as de `reservations`, `billing` e `fnrh` já existem e bastam. O `reporting` (camada Gestão) não importa classe de outro módulo de negócio: consulta só `report_*` por um adaptador `JdbcClient` em `infrastructure`, atrás de portas em `application`, e chama só o `hotel` (data operacional e fuso, `HotelApi`; aresta nova para camada inferior, acrescentada à linha `reporting` da §3.1 na mesma mudança) e o `shared`. A resposta JSON de cada relatório é um DTO tipado (dinheiro como string decimal pelo `Money`, `@field:ExtensibleEnum` nos enums que crescem: status e categoria da OS, origem e status da tarefa), e o mesmo resultado é convertido num modelo tabular interno (`ReportTable`: colunas tipadas e linhas, nunca exposto no contrato) que a exportação CSV/XLSX (`org.dhatim:fastexcel`) serializa; a exportação segue a convenção da ARCHITECTURE §7 "Documentos impressos" (rota própria por formato, sem `?format=` nem negociação por `Accept`).

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1, Flyway 12, fastexcel (novo), Testcontainers.

**Riscos:**
- **autenticação ou autorização:** permissões novas de relatório, separadas por assunto; quem vê pendências (contas e caixas) (Tasks 1 e 2);
- **dado pessoal ou segredo:** relatórios sem dado de hóspede (RN-13) nem texto livre; pendências só com números e saldos; views sem texto livre; exportação com `Cache-Control: no-store` (Tasks 1 a 3);
- **cálculo de valor monetário:** saldo das contas nas pendências (Task 2), custos de OS (Task 3);
- **parsing de entrada externa não confiável:** filtros de período e `days` (Tasks 1 a 3);
- **migração ou escrita destrutiva de dados:** views novas e seus privilégios (Task 1).

## Global Constraints

- Grafo §3.1 com o `ModularityTest` exato: `reporting` depende só de `shared` e `hotel` nesta etapa; a linha `reporting` da §3.1 fica "shared, hotel, files, printing (estes dois desde a E23), mais as *views*…" na mesma mudança. Regra ArchUnit nova: nenhuma classe de `com.zeny.hotel.reporting..` depende de classe de outro módulo além de `shared` e da raiz do `hotel`.
- Views pela convenção do brain: `<módulo>.report_<assunto>`, só sobre tabelas do próprio schema (nada de ler `hotel.business_day` nem `CURRENT_DATE`), uma linha por fato (sem filtrar inativos), ids opacos, sem texto livre nem dado de hóspede, contrato no comentário da migração; `GRANT SELECT` e `REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER` ao papel da aplicação, um comando por view; `DatabaseRolesTest`. Migrações com timestamp posterior a `maintenance/V2026_11_19_1921__…`.
- Prova "só as views": `ReportingViewsOnlyTest` roda **todo** método da porta de consultas com um papel próprio, sem login, com só `USAGE` nos schemas lidos e `SELECT` nas views (`SET ROLE`), como o `ChecklistReportsTest`; cada fatia que acrescenta consulta o estende.
- Período em datas (data local no fuso do hotel) com a entrada estrita da E20; `from > to` ou mais de 366 dias → `400`.
- Biblioteca nova só pelo catálogo `gradle/libs.versions.toml`, sem versão literal no build, com os lockfiles regravados e o `osv-scanner` sem High/Critical novo (H7, H23 b).
- Exportação pela ARCHITECTURE §7 "Documentos impressos": operação própria por formato (`…/csv`, `…/xlsx`), erros em `application/problem+json` mesmo com outro `Accept`, `Cache-Control: no-store`, media type declarado no contrato.
- Contrato regenerado; `checkOpenApi` e `check-api-breaking.sh` sem `ERR`.
- RN-13: nenhum relatório expõe nome, documento ou contato de hóspede, nem texto livre.
- Código em inglês; KDoc e commits em pt-BR; commits com `Soul:`.
- Um Gradle por vez; `timeout` em tudo; sem laço de espera; nunca `--stop`.
- Testes: banco compartilhado, com a data operacional que outros testes avançam. Totais do hotel (painel, manutenção, pendências) são afirmados **por diferença** (lê antes, semeia, espera `InventoryListenersQuiescence.await(jdbc)` quando cria reserva, lê depois); relatórios por camareira ou tipo afirmam só sobre camareiras e tipos criados pelo teste; casos de zero e de `null` (dia sem movimento, tipo sem inspeção) são testados no caso de uso com porta falsa (teste de unidade).
- Verificação da fatia: os testes focados citados mais `timeout 900 ./gradlew checkOpenApi lint`.

## Review Focus

1. **Dia sem movimento e datas futuras sem inventário:** zeros, não erro (caso de uso com porta falsa). Teste na Task 2.
2. **Camareira sem tarefa concluída e tipo sem inspeção:** linha com zero; taxa de reprovação `null` (não 0) sem inspeção (caso de uso com porta falsa). Teste na Task 1.
3. **Cortesia, uso da casa e day use na ocupação:** cortesia conta, uso da casa e day use não (H9, contrato de `report_room_nights`). Teste na Task 2.
4. **Texto com `;`, aspas, quebra de linha e prefixo `=`/`+`/`-`/`@`/TAB/CR num campo de texto exportado; saldo negativo:** CSV com escape RFC 4180 e neutralização só em colunas de texto (prefixo `'`); número negativo sai como número no CSV e célula numérica no XLSX. Teste na Task 3.
5. **Conta com saldo de reserva `CHECKED_OUT`, inclusive por transferência, reembolso ou depósito; caixa aberto de outro dia; FNRH `INVALID`:** aparecem nas pendências; saldo zero e caixa fechado não. Teste na Task 2.

## Decisões do plano

- **D1 — views novas** (Task 1), cada uma com o contrato no comentário:
  - `rooms.report_room_status`: uma linha por UH (ativa ou não), com `room_id`, `room_type_id`, `floor`, `active`, `occupancy`, `housekeeping_status`, `inspection_pending`, `status_changed_at`.
  - `rooms.report_out_of_order`: uma linha por período OOO/OOS, com `out_of_order_id`, `room_id`, `kind`, `starts_on`, `ends_on`, `released_on` (sem `reason` nem `responsible`, texto livre). O `reporting` decide o "em vigor" com a data da `HotelApi`.
  - `housekeeping.report_tasks`: uma linha por tarefa, com `task_id`, `business_date`, `room_id`, `area_id`, `cleaning_type_id`, `source`, `status`, `credits`, `assigned_employee_id` (colaborador do `staff`), `started_at`, `finished_at`, `worked_seconds` (soma dos trechos em andamento, sem pausas); o contrato diz quais status contam como limpeza feita (`AWAITING_INSPECTION`, `DONE`). Sem `notes`.
  - `housekeeping.report_inspections`: uma linha por inspeção concluída, com `inspection_id`, `task_id` (nulo na voluntária), `room_id`, `result`, `completed_at`, `inspector_id`.
  - `maintenance.report_work_orders`: uma linha por OS, com `work_order_id`, `number`, `room_id`, `area_id`, `category`, `priority`, `origin`, `status`, `opened_at`, `started_at`, `closed_at`, `labor_cost`, `third_party_cost`, `parts_cost` (sem descrição, motivos, notas nem terceiro).
  - O saldo das contas **não** ganha view: sai de `billing.report_postings.amount` menos `billing.report_payments.net_amount` (contrato de `billing/V2026_10_20_0347`), em `numeric`.
- **D2 — permissões** (`ReportingPermissions`), todas só `ALL`, não sensíveis: `reporting.dashboard.read` (painel; Gerente, Recepcionista, Governanta), `reporting.housekeeping.read` (Gerente, Governanta), `reporting.maintenance.read` (Gerente, Manutenção) e `reporting.pending.read` (pendências; Gerente, Financeiro e Recepcionista, como `endofday.runs.read`, porque ela recebe o aviso de pendência).
- **D3 — painel** `GET /api/v1/reports/day-panel?days=N` (N de 1 a 30, padrão 7): para a data operacional e as N−1 seguintes, chegadas (reservas `PROVISIONAL`/`CONFIRMED`/`IN_HOUSE`/`CHECKED_OUT` com `arrival` na data; day use incluído; a que já chegou e saiu hoje continua contando como chegada do dia), saídas (`PROVISIONAL`/`CONFIRMED`/`IN_HOUSE`/`CHECKED_OUT` com `departure` na data, a mesma lista de "vendida" de `report_room_nights`), hospedados (UHs com noite vendida na data, `report_room_nights` `sold AND NOT day_use`), ocupação = noites `sold AND counts_in_occupancy` ÷ (`total − out_of_order` de `report_inventory`), em percentual com 2 casas (`null` se o denominador for 0); só para hoje, UHs ativas por status de governança e ocupação, e UHs em OOO/OOS em vigor. Sem nome de hóspede (contagens). `CANCELLED` e `NO_SHOW` não contam.
- **D4 — pendências** `GET /api/v1/reports/end-of-day-pending` (estado corrente, sem parâmetro de data): contas **abertas** com saldo ≠ 0 de reservas `CHECKED_OUT` (todas as contas da reserva: `owner_type = 'RESERVATION' AND owner_id = reservation_id`; número da conta, código da reserva, saldo, data de saída; sem nome), caixas `OPEN` (número, ponto, aberto em, operador id) e submissões FNRH `PENDING`, `FAILED` e `INVALID` (operação, reserva, tentativas, última falha). Notas rejeitadas e ARI: M7/M8.
- **D5 — relatórios operacionais** (governança na Task 1, manutenção na Task 3): `GET /api/v1/reports/housekeeping?from=&to=` pelo `business_date` da tarefa — por camareira (`assigned_employee_id`): UHs (tarefas de UH concluídas) e créditos; por tipo de limpeza × tipo de UH (só tarefas de UH): tempo médio em minutos de `worked_seconds`; taxa de reprovação = inspeções reprovadas ÷ inspeções concluídas de tarefas do período (voluntárias à parte, sem tipo), `null` sem inspeção. `GET /api/v1/reports/maintenance?from=&to=` pelo `opened_at` (data local): OS por status e por categoria; tempo médio de atendimento (`opened_at` → `closed_at`, em horas com 1 casa) das concluídas no período; soma dos custos das concluídas.
- **D6 — exportação** (Task 3): para cada relatório e o painel, `GET …/csv` e `GET …/xlsx` com os mesmos parâmetros, sobre o mesmo resultado do caso de uso que a resposta JSON (DTO tipado), convertido no `ReportTable` só para a exportação; CSV em UTF-8 com BOM, separador `;`, decimal com `,` (pt-BR), datas ISO, RFC 4180, neutralização (prefixo `'`) só em colunas de texto que começam com `=`, `+`, `-`, `@`, TAB ou CR; XLSX pela fastexcel, uma aba por tabela, números como célula numérica, cabeçalhos em pt-BR; `Content-Disposition: attachment; filename="<relatorio>-<data>.<ext>"`; `Cache-Control: no-store`; erros em problem+json.

## Revisão de arquitetura

- **Veredito:** aprovado com notas, na rodada 2 (`revisor-arquitetura`, modelo opus).
- **Rodada 0:** bloqueado por 12 Importantes: `report_room_status` lendo a data operacional e filtrando UHs; ocupação fora da H9; view de saldo condicional; FNRH `INVALID`; `?date=` sem semântica; `?format=` contra o §7; neutralização que estragava negativos; agregados no banco compartilhado; "só as views" sem prova na Task 3; tempo com pausas; permissão única; tamanho das fatias. Havia também 7 Menores. Corrigido em `6406b92`, pelas opções recomendadas (autorização do usuário de 2026-11-19).
- **Rodada 1:** bloqueado por 3 Importantes: Task 1 acima do limite; saídas futuras subcontadas; JSON sem tipo. Havia também 3 Menores. Corrigido em `17a3828`.
- **Notas Menores da rodada 2, aplicadas neste texto:** `ReportTable` só da exportação nos objetivos (N4); tasks do cabeçalho `Riscos:` (N5).
- **Para o usuário (sem bloquear):** exportação por rota própria (§7); permissões separadas por assunto e pendências também na Recepcionista.

---

### Task 1: Views, módulo `reporting` e relatório de governança

**Objetivo:** D1 (todas as views), D2 (todas as permissões), o relatório de governança do D5, o esqueleto do módulo (porta, adaptador, `ReportTable` interno, controller), a aresta na §3.1 e as regras de módulo.

**Critérios de aceite:**
- `RoomsReportViewsTest`, `HousekeepingReportViewsTest` e `MaintenanceReportViewsTest` (`@IntegrationTest`, novos, cada um no seu módulo): dados semeados aparecem com as colunas do contrato; nenhuma coluna de texto livre (`information_schema.columns`); o papel da aplicação só tem `SELECT`. `DatabaseRolesTest` verde.
- `HousekeepingReportApiTest` (`@IntegrationTest`, novo): relatório de governança (UHs e créditos por camareira criada no teste; tempo médio por `worked_seconds` sem as pausas; taxa de reprovação) conferido; `from > to` e período > 366 dias → `400`; sem `reporting.housekeeping.read` → `403`; nenhum texto livre nem dado de hóspede; a resposta é DTO tipado (sem array de array no contrato).
- `HousekeepingReportServiceTest` (unidade, porta falsa): camareira sem tarefa concluída com zeros e tipo sem inspeção com taxa `null` (Review Focus 2).
- `ReportingViewsOnlyTest` (`@IntegrationTest`, novo): todos os métodos da porta rodam sob o papel que só tem `SELECT` nas views (a `maintenance.report_work_orders` é provada pelo `MaintenanceReportViewsTest` nesta fatia e pelo `ReportingViewsOnlyTest` na Task 3).
- `ModularityTest` (`reporting depends only on shared, hotel`), a regra ArchUnit nova, `LayerArchitectureTest` e `AuthorizationTest` (o desenvolvedor só acrescenta o que for dele; listas exatas do testador vão ao testador). Verificação: `timeout 900 ./gradlew test --tests '*ReportViewsTest' --tests '*DatabaseRolesTest' --tests '*HousekeepingReport*' --tests '*ReportingViewsOnlyTest' --tests '*ModularityTest' --tests '*LayerArchitectureTest' --tests '*AuthorizationTest'`.
- Contrato regenerado; `timeout 900 ./gradlew checkOpenApi lint` verde.

**Áreas tocadas:** migrações em `db/migration/rooms/`, `housekeeping/`, `maintenance/` (uma por módulo); `reporting/` novo: `ReportingModule.kt`, `ReportingPermissions.kt`, `application/ReportingConfiguration.kt`, `application/ReportTable.kt`, `application/ReportingQueries.kt` (porta), `application/HousekeepingReportService.kt`, `infrastructure/JdbcReportingQueries.kt`, `web/ReportsController.kt` (com DTOs); `docs/ARCHITECTURE.md` (§3.1); `ModularityTest`; a regra ArchUnit; `api/openapi.yaml`.

**Tamanho:** ~55 min; 8 arquivos Kotlin de produção, 3 migrações, a ARCHITECTURE e o contrato.

**Depende de:** nenhuma.

**Interfaces (produz):** as views (D1); `ReportingQueries` (porta), `ReportTable(columns: List<ReportColumn>, rows: List<List<Any?>>)` com `ReportColumn(key, label, type: TEXT|INTEGER|DECIMAL|DATE|INSTANT)`; `ReportsController`; `ReportingPermissions`.

### Task 2: Painel do dia e pendências do fechamento

**Objetivo:** D3 e D4, com resposta em DTO tipado, pela porta da Task 1 (o `ReportTable` é só da exportação).

**Critérios de aceite:**
- `DayPanelApiTest` (`@IntegrationTest`, novo, por diferença): chegadas, saídas, hospedados e ocupação de hoje e dos próximos dias mudam como esperado depois de semear reservas próprias, inclusive uma reserva futura (`CONFIRMED`) cuja saída cai dentro da janela e um day use (chegada, sem hospedado); cortesia conta na ocupação, uso da casa e day use não (Review Focus 3); UHs por status e OOO de hoje; `days` fora de 1..30 → `400`; sem `reporting.dashboard.read` → `403`; DTO tipado no contrato.
- `DayPanelServiceTest` (unidade, porta falsa): dia sem movimento e data sem inventário dão zeros e ocupação `null` (Review Focus 1).
- `EndOfDayPendingApiTest` (`@IntegrationTest`, novo): conta aberta com saldo de reserva `CHECKED_OUT` (com transferência, reembolso e depósito), caixa aberto de outro dia e submissões FNRH `PENDING`, `FAILED` e `INVALID` aparecem; saldo zero e caixa fechado não (Review Focus 5); nenhum nome de hóspede; sem `reporting.pending.read` → `403`; DTO tipado no contrato (saldo como string decimal).
- `ReportingViewsOnlyTest` estendido com as consultas novas. Verificação: `timeout 900 ./gradlew test --tests '*DayPanel*' --tests '*EndOfDayPendingApiTest' --tests '*ReportingViewsOnlyTest'`.
- Contrato regenerado; `timeout 900 ./gradlew checkOpenApi lint` verde.

**Áreas tocadas:** `reporting/application/DayPanelService.kt` (novo), `reporting/application/EndOfDayPendingService.kt` (novo), `reporting/application/ReportingQueries.kt`, `reporting/infrastructure/JdbcReportingQueries.kt`, `reporting/web/ReportsController.kt`, `api/openapi.yaml`.

**Tamanho:** ~55 min; 5 arquivos de produção, mais o contrato.

**Depende de:** Task 1.

### Task 3: Relatório de manutenção e exportação CSV/XLSX

**Objetivo:** o relatório de manutenção do D5 e o D6 para os quatro relatórios (governança, manutenção, painel, pendências).

**Critérios de aceite:**
- `MaintenanceReportApiTest` (`@IntegrationTest`, novo, por diferença): OS por status e por categoria, tempo médio de atendimento e custos das concluídas conferidos; `from > to` e período > 366 dias → `400`; sem `reporting.maintenance.read` → `403`; nenhum texto livre; DTO tipado. `ReportingViewsOnlyTest` estendido com a consulta nova.
- `ReportExportTest` (`@IntegrationTest`, novo): `…/csv` e `…/xlsx` dos quatro relatórios, relidos no teste (fastexcel-reader só em teste), têm as mesmas linhas da resposta JSON; `Content-Type`, `Content-Disposition` e `Cache-Control: no-store` corretos; parâmetro inválido → `400` em problem+json mesmo com `Accept: text/csv`.
- `ReportExportFormatTest` (unidade): escape RFC 4180, BOM, `;` e decimal `,`; neutralização só em texto (Review Focus 4: prefixos `=`, `+`, `-`, `@`, TAB, CR); saldo negativo como número no CSV e célula numérica no XLSX.
- Biblioteca pelo catálogo, lockfiles regravados, `timeout 600 osv-scanner scan source -L gradle.lockfile -L buildscript-gradle.lockfile -L "gradle.lockfile:settings-gradle.lockfile"` sem Critical/High (saída no relatório). Verificação: `timeout 900 ./gradlew test --tests '*MaintenanceReportApiTest' --tests '*ReportingViewsOnlyTest' --tests '*ReportExport*'` e `timeout 900 ./gradlew checkOpenApi lint`.

**Áreas tocadas:** `gradle/libs.versions.toml`, `build.gradle.kts` (dependências pelo catálogo), lockfiles, `reporting/application/MaintenanceReportService.kt` (novo), `reporting/application/ReportingQueries.kt` e `reporting/infrastructure/JdbcReportingQueries.kt` (consulta da manutenção), `reporting/application/ReportExport.kt` (novo: CSV e XLSX), `reporting/web/ReportsController.kt` (as operações `…/csv` e `…/xlsx`), `api/openapi.yaml`.

**Tamanho:** ~60 min; 6 arquivos de produção, mais catálogo, lockfiles e contrato.

**Depende de:** Task 2.
