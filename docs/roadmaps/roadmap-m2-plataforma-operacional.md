# Roadmap — M2 Plataforma operacional

> Arquivo: `docs/roadmaps/roadmap-m2-plataforma-operacional.md` · criado em 2026-10-13 · última revisão 2026-10-15
>
> Marco 2 de 11 · mapa dos marcos em [`docs/roadmaps/README.md`](README.md) · marco anterior: [M1 — Acesso, auditoria e configuração](roadmap-m1-acesso-configuracao.md) · próximo marco: [M3 — Hóspedes, contas e caixa](roadmap-m3-hospedes-contas.md)
>
> **Pré-requisito:** etapa E09 ("Gate do marco M1") de `docs/roadmaps/roadmap-m1-acesso-configuracao.md` marcada `#concluida` — atendido em 2026-10-15 (commits `fe4e91f` e `372701d`, CI verde no run `38783591046`).

## Como usar

Duas formas, ambas na raiz do repositório:

- **Manual:** cole o prompt de uma etapa numa sessão limpa do Claude Code. Uma etapa por sessão.
- **Automática:** cole o prompt da seção "Execução automática" numa sessão limpa; ela executa até 3 etapas em sequência, um subagente por etapa mais um verificador, e para para revisão. Para não parar em cada pedido de permissão, libere antes os comandos de build/teste do projeto nas permissões do Claude Code (os comandos oficiais estão no `CLAUDE.md`).

Etapas marcadas como paralelizáveis só devem rodar ao mesmo tempo em worktrees git separados; na mesma pasta, rode-as uma de cada vez.

Em ambas, quem executa segue o "Protocolo de execução", atualiza a tag da etapa e preenche "Evidências e handoff". Não pule dependências. Revise e faça commit entre etapas ou lotes — nenhuma sessão faz commit por conta própria.

**Sincronizado com o código em 2026-10-15**, depois do gate do M1 (commit `372701d`): caminhos, comandos, convenções, APIs públicas e pendências vêm do `CLAUDE.md`, do código e dos handoffs de `docs/roadmaps/roadmap-m1-acesso-configuracao.md` (principalmente as "Notas para o marco M2" da E09). Entraram duas etapas novas, E11 (ciclo de vida dos anexos) e E12 (limite de tentativas do outbox), para as pendências que o M1 deixou para cá. O usuário confirmou em 2026-10-15, numa entrevista, as hipóteses H8 a H15 como decisões e manteve E11 e E12 como etapas separadas (D2); H6 e H7 continuam abertas e são decididas na entrevista da etapa que as usa (E05 e E09).

## Objetivo

Entregar a base operacional sobre a qual governança, manutenção, recepção e restaurante vão rodar: cadastro de UHs e áreas com status em várias dimensões, histórico e OOO/OOS; canal de tempo real por SSE; protocolo de sincronização offline para o app operacional; motor genérico de checklists com versões, execução online e offline, fotos e rotinas; e notificações internas com push. Ao fim do marco, uma mudança de status de UH chega aos clientes conectados, e um checklist concluído offline é sincronizado sem perda nem duplicidade e publica `ChecklistCompleted` com as ações configuradas.

## Contexto confirmado

- Requisitos: SPEC §5.4 (UH, RF-UH-01 a 08), §5.15 (CHK, RF-CHK-01 a 09; ações de estoque e enxoval só são consumidas na Fase 3), §5.22 (NOT, RF-NOT-01 e 02; impressão RF-NOT-03 e 04 fica para M5); regras RN-05, RN-07, RN-08, RN-12, RN-13, RN-22 (SPEC §6); RNF-04 e RNF-05 (SPEC §7).
- Modelo de domínio: SPEC §8.2.3 (RoomType, Room, RoomFeature, Area, Location, OutOfOrder, RoomStatusChange), §8.2.11 (ChecklistTemplate, ChecklistVersion, ChecklistSection, ChecklistItem, ItemAction, ApplicabilityRule, ChecklistExecution, ChecklistAnswer, RecurringChecklist) e §8.2.18 (Notification, NotificationPreference, PushDevice). Eventos em SPEC §8.3 (`RoomStatusChanged`, `OutOfOrderChanged`, `ChecklistCompleted`) e máquina de estados do status de governança em SPEC §8.4.
- Arquitetura: módulos `rooms` (depende de `shared`, `hotel`; camada Cadastros), `checklists` (depende de `shared`, `hotel`, `staff`, `files`; Plataforma) e `notifications` (depende de `shared`, `iam`; Plataforma); tempo real e sincronização ficam em `shared` (ARCHITECTURE §3.1 e §3.2). Vale a **regra por camadas** da §3.1: um módulo pode depender de módulos de camada inferior e da Plataforma sem ciclo, acrescentando a aresta à tabela na mesma mudança; `notifications` nunca escuta eventos de módulos de negócio — quem gera o aviso chama a API pública dele. `checklists` (Plataforma) não pode depender de `rooms` (Cadastros). Offline: ARCHITECTURE §9 e D11; tempo real: ARCHITECTURE §10 e D10; fluxo de checklist offline: ARCHITECTURE §6.2; push FCM: ARCHITECTURE §12; anexos: ARCHITECTURE §14.
- Base técnica herdada de M0 e M1 (fonte: `CLAUDE.md`, código e handoffs; conferida no código em 2026-10-15):
  - **Módulos existentes** em `src/main/kotlin/com/zeny/hotel/`: `shared`, `iam`, `audit`, `hotel`, `staff` e `files`, cada um com `domain/`, `application/`, `infrastructure/` e `web/` e um arquivo `…Module.kt` na raiz com `@ApplicationModule(allowedDependencies = …)` (ex.: `staff/StaffModule.kt` com `["shared", "iam", "hotel"]`). O `ModularityTest` (`src/test/kotlin/com/zeny/hotel/`) tem um teste por módulo com as dependências diretas exatas e só pela API pública, e o teste `no module other than staff depends on iam` barra qualquer outro módulo que importe `iam`. As regras de camadas ficam em `src/test/kotlin/com/zeny/hotel/architecture/LayerRules.kt` (inclui `writeUseCasesAreAudited`).
  - **Comandos oficiais** na tabela do `CLAUDE.md`: `./gradlew build`, `test`, `lint`, `ktlintFormat`, `checkOpenApi`, `updateOpenApi`, `docker compose -f docker/compose.yaml up -d --build` e `.github/scripts/check-api-breaking.sh`. Versões só em `gradle/libs.versions.toml`; o catálogo **ainda não tem** WireMock nem biblioteca de imagens além do `javax.imageio` do JDK: cada etapa acrescenta o que usar.
  - **Migrações:** uma pasta por módulo em `src/main/resources/db/migration/` (hoje `shared`, `iam`, `audit`, `hotel`, `staff`, `files`), arquivos `V<data>_<hora>__<descricao>.sql` em UTC, cada pasta com uma migração de `GRANT` ao `"${app_role}"` (modelo: `staff/V2026_10_14_2321__grant_staff_to_app_role.sql`). A última versão aplicada é `V2026_10_15_1446` (`files`); com `outOfOrder` desligado, toda migração nova precisa de timestamp posterior. `MigrationConventionTest` barra objeto fora do schema do módulo; tabelas *append-only* vão num bean `AppendOnlyTables`. Exclusão física proibida por trigger segue o `staff.refuse_delete`.
  - **Portas do kernel** (raiz de `com.zeny.hotel.shared`): `CurrentActor` (`Actors.kt`, com `UserId` e `DeviceSessionId`), `Authorization` (`require`, `grantOrNull`, `scopeFilter`, `limit`, `requireOrApproval` — **sempre sobre o ator da requisição corrente**: não há consulta de permissão de outro usuário), `SensitiveAction`/`APPROVAL_TOKEN_HEADER` (`Approvals.kt`), `@Audited`/`AuditContext` (`Auditing.kt`), `ModulePermissions` com `PermissionDefinition.templates` para os modelos `RoleTemplate` (`HOUSEKEEPER`, `ROOM_ATTENDANT`, `MAINTENANCE`, `MANAGER`…), `FieldCipher`/`ContentCipher`/`TokenSealer` (`FieldEncryption.kt`), paginação, `ETags`, `@Idempotent` e `Clock` UTC.
  - **APIs públicas para o M2:** `HotelApi` (`currentBusinessDate()`, `timezone()`; `BusinessDateAdvanced` em `hotel/HotelEvents.kt`, publicado só por `advanceBusinessDate`, que nenhum endpoint chama: até o M6 a data não avança em produção); `StaffApi` (`daySchedule(date, departmentId)`, `departments()` com `DepartmentKey.HOUSEKEEPING` etc., `employeeOfUser(userId)`); `FilesApi` (só `find` e `attach`; finalidades por `ModuleFilePurposes`; tipos `PNG`, `JPEG`, `WEBP`, `PDF` detectados pelo conteúdo; upload `POST /api/v1/files` multipart **sem** idempotência, porque o `IdempotencyFilter` recusa multipart); `IamApi` (só `findUser`, documentada como exclusiva do `staff`).
  - **Eventos:** nenhum `@ApplicationModuleListener` no código de produção ainda. O outbox só grava a publicação quando há listener (o `BusinessDateTest` usa `@EnableScenarios` e um listener de teste). O `EventPublicationResubmissionJob` (`shared/infrastructure/events/`) reenvia as `FAILED` a cada minuto, **sem limite de tentativas** e sem backoff; as publicações concluídas ficam para sempre em `shared.event_publication` (modo de conclusão padrão); `OutboxMetrics` e `docker/prometheus/alerts.yaml` alertam `FAILED`.
  - **Web:** Spring MVC (`spring-boot-starter-webmvc`, sem WebFlux); controllers com `@ApiV1Controller`; `open-in-view` desligado. O `@IntegrationTest` sobe um servlet **simulado** (`MockMvc`): um teste com cliente SSE real precisa de um contexto próprio com `webEnvironment = RANDOM_PORT`. A tabela `shared.idempotency_key` não serve para `commandId` (CLAUDE.md).
  - **Compose:** `docker/caddy/Caddyfile` (métricas → 404, `GET`/`HEAD` pré-assinados do bucket → MinIO, resto → `api:8080`, sem regra para streams); variáveis novas obrigatórias entram com `${VAR:?…}` e no `docker/.env.example` sem segredo.
  - **Apoio de testes:** `testsupport/` (`IntegrationTest`, `asUser(userId, sessionId)`, `MutableClockConfiguration`, `PostgresContainer`, `MinioContainer`), `IamTestSupport` (roles, usuários, PIN e step-up), `StaffTestSupport` (setor, colaborador, turno e padrão pela API) e `FilesTestSupport` (envio, URL e leitura do bucket).
- **Pendências herdadas do M1 e onde entram aqui:**
  - limite de tentativas e arquivamento do outbox, antes do primeiro listener real → **E12**;
  - `FilesApi.detach`/`delete` e expurgo de anexos órfãos (sem vínculo depois de 24 h), prontos antes da primeira finalidade sensível do M3 (revisão humana da E09 do M1, Q2) → **E11**;
  - upload idempotente para o offline (id gerado pelo cliente) → **E11**, usado pela **E07**;
  - reencodar as fotos de checklist no servidor (sem EXIF/GPS, neutraliza poliglotas) → **E11**, usado pela **E07**;
  - `FilesApiTest` com TTL de 4 s e `Thread.sleep` (instável numa CI lenta) → **E11**;
  - porta opcional `FileAccessPolicy` por finalidade (Q1) → fora do M2 (H15, confirmada: fotos de checklist não precisam); vai para o M3 E03;
  - `notifications` dependendo de `iam` (hoje barrado pelo `ModularityTest` e pela frase do `CLAUDE.md`) → **E09** (H9);
  - fechamento da conexão SSE na revogação da sessão (o kernel não sabe de revogações) → **E04** (H8).
- **Pendências herdadas que não são do M2:** `FilesApi` lendo e gravando conteúdo pelo servidor (primeiro consumidor no M3 E03, depois `printing` M5, `reporting` M6 e `fiscal` M7); `server.forward-headers-strategy` com o TLS, URL pública única dos anexos, `log_error_verbosity=terse` no banco de produção, recifragem dos objetos sensíveis, métrica de falha do S3 e usuário de serviço antigo do MinIO (M8); logotipo anterior que continua vinculado (M10); ressalvas baixas sem destino: `GET /files/{id}` 404/403 revela existência, 400 antes do 403, `APPROVER_LOCKED`/`APPROVER_IS_REQUESTER` revelam login, `hotel.business_day` contornável por SQL manual, teste do HKDF contra a chave mestra.

## Escopo

**Incluído:** tipos de UH, UHs, características e áreas comuns; status de ocupação, governança, sinalização do hóspede e OOO/OOS com histórico e discrepâncias; API pública de status e de OOO para os módulos futuros; SSE com tópicos, filtro por permissão e reconexão; sincronização offline (download por cursor e upload de comandos idempotentes); limite de tentativas e arquivamento do outbox; ciclo de vida dos anexos (desvincular, apagar, expurgo de órfãos, upload idempotente e fotos reencodadas); modelos de checklist com versões, condicionais, aplicabilidade e ações; execução de checklist online e offline com fotos; rotinas; relatórios de checklists; notificações internas, preferências e push.

**Fora de escopo:**

- Ocupação derivada da recepção (check-in/out): o `frontdesk` (M5) chama a API pública de `rooms` criada aqui.
- Inventário vendável e alerta de reserva alocada em UH que entra em OOO: `reservations` (M4) escuta `OutOfOrderChanged`.
- "Em limpeza" a partir da tarefa em andamento, tarefas de governança e tipos de limpeza: M6.
- Consumidores de `ChecklistCompleted`: baixa de estoque e troca de enxoval (M9), ordem de serviço, achados e danos (M6).
- Impressão e agente de impressão: M5.
- Tópicos SSE `tasks`, `tabs` e `production`: M6 e M8 os registram usando a infraestrutura daqui.
- Avanço da data operacional por endpoint: só o `endofday` (M6) a avança; as rotinas do M2 são exercitadas por teste.
- Porta `FileAccessPolicy`, leitura e gravação de conteúdo de anexo pelo servidor e retenção LGPD de anexos com dono (M3, M5–M7, M10).

## Restrições e invariantes

- Respeitar o grafo de módulos da ARCHITECTURE §3.1. `rooms` não conhece reservas nem governança; `checklists` não conhece estoque, rouparia, manutenção nem `rooms` (referências a UH, área, tipo de UH, tipo de limpeza e ativo são IDs opacos); `shared` não depende de nenhum módulo nem escuta eventos de módulos — tempo real e sincronização recebem dados por portas que os módulos implementam ou chamam.
- `notifications` é o único módulo, além de `staff`, que pode importar `iam`, e só a `IamApi` (H9). Os demais checam permissão pela porta `Authorization`.
- RN-13: nada que viaje pela sincronização offline ou pelos tópicos SSE contém nome, documento ou contato de hóspede.
- O servidor tem a palavra final na sincronização, mas registra os fatos (RN-22): execução offline de tarefa reatribuída ou cancelada é registrada e sinalizada, nunca descartada.
- Versões publicadas de checklist são imutáveis (RF-CHK-06).
- Toda escrita é auditada (`@Audited`, regra `writeUseCasesAreAudited`) e toda checagem de permissão passa pela porta `Authorization`; permissões declaradas por `ModulePermissions` com `templates` para os modelos de role.
- Listeners `@ApplicationModuleListener` são idempotentes (o outbox reentrega).
- Migrações novas com timestamp posterior a `V2026_10_15_1446`; nenhum `GRANT` fora do schema do próprio módulo.
- Convenções de API, erros, migrações e tipos seguem o `CLAUDE.md`; não criar convenção paralela. Cada etapa atualiza o "Estado atual" e a seção do módulo no `CLAUDE.md`.
- Nenhum segredo (credencial FCM, chaves) no repositório.

## Hipóteses e decisões em aberto

| # | Hipótese ou decisão | Impacto se errada / sem decisão | Etapa afetada |
|---|---|---|---|
| H1 | Pacote base `com.zeny.hotel`. **Confirmado** (M0 E01; conferido no código em 2026-10-15). | — | Todas |
| D1 | **Decidido em 2026-10-13:** as rotinas de checklist são geradas por um listener de `BusinessDateAdvanced` em `checklists`, que depende de `hotel` (regra por camadas; aresta já registrada na ARCHITECTURE §3.1; sem ciclo, pois `hotel` depende só de `shared` e `files`). | — | E07 |
| D2 | **Decidido pelo usuário em 2026-10-15:** as pendências herdadas do M1 sobre anexos e outbox ficam em etapas próprias, E11 e E12, e não embutidas na E07. | — | E07, E11, E12 |
| H3 | A aplicabilidade de modelos (RF-CHK-04) guarda IDs opacos de tipos de UH, tipos de limpeza, áreas e ativos; quem pede o modelo de uma tarefa informa os atributos do sujeito. `checklists` não valida esses IDs. | Validar exigiria dependência de `rooms`, proibida pelas camadas; se necessário, por porta. | E06 |
| H4 | O número de hóspedes informado pela recepção chega a `rooms` pela API pública de marcação de ocupação (usada pelo `frontdesk` em M5), o que permite a discrepância "número de hóspedes diverge" (RF-UH-08) sem `rooms` depender de reservas. | Se a recepção não informar, essa discrepância fica sem dados até M5. | E02 |
| H5 | Buffer de reconexão SSE em memória com tamanho configurável (instância única, ARCHITECTURE §10). | Reinício do servidor responde `reset` a todos, o que a ARCHITECTURE já prevê. | E04 |
| H6 | **Confirmada pelo usuário em 2026-10-15** (entrevista da E05). `change_seq` é uma *sequence* global no schema `shared`, gravada por **trigger por tabela**: cada módulo cria, na própria migração, um `BEFORE INSERT OR UPDATE` que executa a função genérica `shared.mark_offline_change()`, que grava `change_seq = nextval('shared.change_seq')` e `change_xid = pg_current_xact_id()`. O cursor é opaco e leva `(xid, seq)`: o download serve só linhas de transações já terminadas (`change_xid < pg_snapshot_xmin(pg_current_snapshot())`), em ordem `(change_xid, change_seq)`, então nenhuma mudança fica atrás do cursor. Exclusões viram *tombstones* (derivados da linha desativada pela fonte, ou na tabela `shared.sync_tombstone`, gravada pela aplicação). Mitigação documentada: uma transação de escrita parada segura o download (sem perda) até terminar; `idle_in_transaction_session_timeout` limita isso. | Alternativas descartadas: `change_seq` gravado pela aplicação (frágil), trava consultiva (o download espera a escrita mais lenta), atraso por tempo (perde mudança) e contador serializado (ponto quente e deadlock). | E05 |
| H7 | Push via FCM HTTP v1 com credencial de conta de serviço por variável de ambiente (`ZENY_FCM_*`, a criar, vazias por padrão); sem credencial configurada, o adaptador fica desligado e só a notificação no painel é entregue. O projeto Firebase do hotel ainda não existe. | Nenhum teste depende da credencial real (WireMock); a primeira entrega real fica para quando houver projeto Firebase. **Continua aberta** (decisão do usuário em 2026-10-15): decidida na entrevista da própria E09, antes da implementação. | E09 |
| H8 | **Confirmada pelo usuário em 2026-10-15.** O SSE autentica com o mesmo `Authorization: Bearer` do resto da API. A permissão e o escopo de cada tópico são avaliados na inscrição (a porta `Authorization` só vale na requisição) e guardados na conexão. O servidor encerra a conexão no `exp` do access token (o cliente reconecta com o token renovado e `Last-Event-ID`), e na hora quando a sessão é revogada ou o usuário desativado, por um evento do kernel (`DeviceSessionsRevoked`, tipo em `shared`, publicado pelo `iam` em logout, revogação, troca/reset de senha, reuso de refresh e desativação, e escutado em processo depois do commit). | Alternativas descartadas: só encerrar no `exp` (sessão revogada recebe eventos por até 15 min) e conferir a sessão a cada heartbeat. Uma permissão retirada só vale na reconexão (até 15 min). | E04 |
| H9 | **Confirmada pelo usuário em 2026-10-15.** `notifications` usa a `IamApi`, ampliada com `usersWithPermission(code)` (usuários ativos com a permissão efetiva vigente) e uma consulta de sessões ativas, para destinatários por permissão e para não mandar push a sessão revogada. O teste `no module other than staff depends on iam` passa a aceitar `notifications`, e o `CLAUDE.md` muda a frase "Nenhum módulo além de `staff` importa `iam`". | Alternativa descartada: portas novas em `shared` implementadas pelo `iam` (a ARCHITECTURE §3.1 já registra `notifications` → `iam`). | E09 |
| H10 | **Confirmada pelo usuário em 2026-10-15.** Upload idempotente: `POST /api/v1/files` aceita a parte opcional `clientFileId` (UUIDv7 gerado pelo app), que vira o id do anexo; repetir com o mesmo id, pelo mesmo usuário e com o mesmo SHA-256, devolve o anexo existente (200) sem gravar de novo; o mesmo id com outro conteúdo ou de outro usuário é recusado (`409`, código estável). O app envia as fotos antes do comando; comando que cita foto inexistente é aplicado com alerta, sem a foto (a foto é opcional, RF-CHK-02). | Alternativas descartadas: `Idempotency-Key` em multipart e fotos dentro do comando de sync. | E11, E07 |
| H11 | **Confirmada pelo usuário em 2026-10-15.** Finalidades marcadas para reencode (as fotos de checklist) têm a imagem decodificada e regravada no servidor com `javax.imageio` (JPEG e PNG, sem metadados EXIF/GPS) e lado máximo configurável (padrão 2048 px). WebP fica fora dessas finalidades, porque o JDK não lê WebP sem biblioteca. | Alternativas descartadas: biblioteca de WebP e só remover o EXIF sem reencodar. O app não envia WebP nas fotos de checklist. | E11, E07 |
| H12 | **Confirmada pelo usuário em 2026-10-15.** `FilesApi.detach` desfaz o vínculo (o anexo volta a órfão) e `FilesApi.delete` apaga o objeto e marca a linha com `deleted_at` (a linha fica, porque a trilha a referencia). Um job com ShedLock apaga, a cada hora, o objeto dos anexos sem vínculo há mais de `zeny.files.orphan-retention` (padrão 24 h) e marca a linha; cada expurgo vai para a trilha com ator sistema (`files.file.purge-orphan`), porque o anexo é dado de usuário e não dado técnico. | Alternativas descartadas: apagar a linha e expurgar sem trilha. | E11 |
| H13 | **Confirmada pelo usuário em 2026-10-15.** Outbox: reenvio com backoff exponencial (1 min dobrando até 1 h, pelo `completion_attempts` e `last_resubmission_date` de `shared.event_publication`) e abandono depois de 24 h de falhas: a publicação continua `FAILED`, sai do reenvio automático, aparece na métrica (`state="abandoned"`) e num alerta, e é reenviável à mão (procedimento documentado). As concluídas são apagadas depois de 7 dias por um job com ShedLock. | Alternativas descartadas: limite fixo sem backoff e o modo `archive`/`delete` do Modulith no lugar do job de expurgo (se a investigação mostrar que o modo de conclusão faz exatamente o expurgo de 7 dias, a E12 registra a escolha). | E12, E07, E09 |
| H14 | **Confirmada pelo usuário em 2026-10-15.** Rotina (RF-CHK-08): uma execução pendente por rotina e data operacional, com os candidatos = colaboradores com usuário vinculado escalados no setor da rotina num turno que cobre o horário dela (`StaffApi.daySchedule`); o primeiro candidato que inicia a assume; o escopo `OWN` vale para candidatos e executor (`OwnedResource.of(owners)`). A geração é só pelo listener de `BusinessDateAdvanced`, idempotente por rotina e data; como nada avança a data em produção até o M6, a evidência do marco é de teste. | Alternativas descartadas: uma execução por colaborador escalado e um endpoint de geração manual. No compose, rotinas geradas só aparecem a partir do M6. | E07 |
| H15 | **Confirmada pelo usuário em 2026-10-15.** A porta `FileAccessPolicy` (Q1 do M1) não entra no M2: a foto de checklist tem como `downloadPermission` a leitura de execuções (`OWN` = quem enviou, que é o executor; `ALL` para governanta e gerente). A porta vai para o M3 E03 (documentos de hóspede). | Alternativa descartada: criar a porta na E11. Custo aceito: numa execução reatribuída, quem assume não vê a foto enviada por outro. | E11, E07 |

## Definição global de pronto

- [ ] Tipos de UH, UHs e áreas são cadastrados; número de UH duplicado é rejeitado.
- [ ] O status de governança segue as transições da SPEC §8.4; toda mudança fica no histórico com quem, quando e origem; RN-05 é respondida pela API pública.
- [ ] OOO exige step-up, é rejeitado em UH ocupada e publica `OutOfOrderChanged`; OOS mantém a UH vendável.
- [ ] Um cliente inscrito em `GET /api/v1/stream?topics=rooms` recebe a mudança de status em até 2 s, inclusive pelo proxy do compose, e reconecta com `Last-Event-ID` sem perder eventos do buffer.
- [ ] `GET /api/v1/sync/changes` devolve mudanças incrementais com *tombstones* e sem dados pessoais; `POST /api/v1/sync/commands` processa lotes idempotentes por `commandId`.
- [ ] O outbox tem limite de tentativas com backoff, alerta das publicações abandonadas e arquivamento das concluídas.
- [ ] Anexos podem ser desvinculados e apagados; órfãos são expurgados; o upload com `clientFileId` repetido não duplica; foto de checklist é reencodada sem metadados.
- [ ] Um checklist concluído offline e sincronizado duas vezes gera uma única execução e um único `ChecklistCompleted` com as ações configuradas.
- [ ] Rotinas são geradas por setor e escala; relatórios de checklists respondem.
- [ ] Notificações aparecem no painel e no tópico SSE do destinatário, respeitam preferências e são enviadas por push quando habilitado.
- [ ] `ApplicationModules.verify()`, build, testes, lint, verificação do contrato e o oasdiff passam.

## Protocolo de execução

Vale para toda etapa, executada manualmente ou pelo orquestrador. Cada etapa declara sua estratégia em "Estratégia de execução".

**Papéis**

- **Sessão principal** (no modo automático, o subagente implementador): a única que edita código e este roadmap. Implementa, testa, atualiza tags e handoff.
- **Investigação** (subagente `Explore`, somente leitura): para material extenso que será lido mas não editado. Recebe as perguntas listadas na etapa e devolve um resumo com caminhos e linhas. Arquivos que serão editados são lidos diretamente pela sessão principal.
- **Implementação paralela** (subagentes `general-purpose`, só na estratégia `paralela`): cada um recebe uma parte com os arquivos listados na etapa, disjuntos dos demais; roda só os testes focados da sua parte; não toca arquivos compartilhados (build, configuração, este roadmap).
- **Verificação independente** (subagente `general-purpose` novo, que não participou da implementação): confere a etapa contra os critérios de aceite. Não edita nada.

**Estratégias:** `direta` — sem subagentes, a sessão principal valida · `padrão` — investigação quando indicada + verificação independente obrigatória · `paralela` — `padrão` + partes paralelas.

**Passo a passo**

1. **Preparação:** leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho deste roadmap, a seção da etapa e o handoff das dependências; rode `git status`. Confirme que as dependências estão `#concluida`, que a etapa não está `#bloqueada` e que o código bate com o handoff das dependências; se não, pare e relate.
2. **Snapshot inicial:** registre o estado da árvore de trabalho, sem commit e sem alterar o índice do git:

   ```bash
   idx=$(mktemp -u); cp "$(git rev-parse --git-path index)" "$idx" 2>/dev/null; GIT_INDEX_FILE="$idx" git add -A && GIT_INDEX_FILE="$idx" git write-tree; rm -f "$idx"
   ```

   Anote o hash impresso como "Snapshot inicial" no handoff da etapa (no modo automático, use o hash recebido do orquestrador em vez de tirar outro). Para ver tudo o que a etapa mudou, rode o mesmo comando de novo e compare: `git diff HASH_INICIAL HASH_ATUAL`. Se `git status` mostrar artefatos de build não ignorados, corrija o `.gitignore` antes do snapshot — senão o diff fica lento e poluído.
3. **Início:** troque a tag da etapa para `#em-andamento` no índice e no título.
4. **Investigação** (se a etapa listar perguntas): dispare um subagente `Explore` com essas perguntas.
5. **Implementação:** quando os critérios descrevem comportamento verificável, escreva os testes primeiro e confirme que falham. Implemente em passos pequenos; na estratégia `paralela`, dispare as partes paralelas e integre-as. Rode as validações obrigatórias da etapa.
6. **Verificação independente** (`padrão` e `paralela`): dispare um subagente `general-purpose` novo com o "Prompt do verificador" abaixo, trocando ETAPA pelo ID da etapa e HASH_INICIAL pelo snapshot. Se o parecer for REPROVADO, corrija e verifique de novo com um verificador novo. Após duas reprovações, pare e relate, mantendo `#em-andamento`.
7. **Fechamento:** marque `#concluida` (índice e título) só com todos os critérios atendidos e, quando houver verificação, parecer APROVADO. Diante de impedimento real, marque `#bloqueada` e preencha "Bloqueios" com causa, evidência, impacto e ação de desbloqueio. Preencha "Evidências e handoff" e responda com um resumo curto. Não faça commit, push ou deploy.

**Prompt do verificador**

```text
Você é o verificador independente da etapa ETAPA do roadmap docs/roadmaps/roadmap-m2-plataforma-operacional.md. Você não participou da implementação e não deve editar nenhum arquivo.
1. Leia CLAUDE.md, o cabeçalho do roadmap e a seção ETAPA inteira (objetivo, escopo, fora de escopo, estratégia, critérios de aceite, validação obrigatória).
2. Veja o que mudou desde o início da etapa: gere um snapshot do estado atual com o comando da seção "Protocolo de execução" e rode git diff --stat HASH_INICIAL seguido do hash gerado; depois leia o diff completo dos arquivos relevantes.
3. Rode todos os comandos da validação obrigatória da etapa e guarde a saída relevante.
4. Para cada critério de aceite, decida APROVADO ou REPROVADO com evidência concreta (arquivo:linha, nome do teste, saída de comando). Critério sem evidência é REPROVADO.
5. Ataque os pontos listados em "Foco do verificador" na etapa e aponte também: mudanças fora do escopo, testes que não exercitam o comportamento (passariam mesmo com o código errado), segredos ou dados pessoais expostos e violações das restrições do roadmap.
Responda com: parecer geral (APROVADO só se todos os critérios forem APROVADO e não houver problema grave), tabela critério → resultado → evidência, problemas por gravidade e comandos executados.
```

## Execução automática

Cole o prompt abaixo numa sessão limpa do Claude Code, na raiz do repositório. Ajuste o limite de etapas se quiser.

```text
Você é o orquestrador do roadmap docs/roadmaps/roadmap-m2-plataforma-operacional.md neste repositório. Seu papel é coordenar subagentes, não implementar: não edite código e mantenha seu contexto enxuto (leia o roadmap e os relatórios dos subagentes, não o código).

Limite desta execução: no máximo 3 etapas concluídas.

Preparação:
1. Leia CLAUDE.md (e AGENTS.md, se existir) e, no roadmap, o cabeçalho, o "Protocolo de execução" e o índice.
2. Rode git status. Se alguma etapa estiver #em-andamento, pare e pergunte ao usuário como prosseguir — provavelmente é uma execução interrompida.

Repita, uma etapa por vez (nunca em paralelo):
1. Escolha a primeira etapa, na ordem do documento, marcada #pendente e com todas as dependências #concluida. Etapas #bloqueada e as que dependem delas ficam para trás. Se não houver candidata, pare.
2. Gere o snapshot inicial com o comando do protocolo e guarde o hash.
3. Dispare um subagente general-purpose com o prompt da etapa copiado literalmente da seção dela, acrescido deste adendo: "Você está rodando como subagente do orquestrador; este adendo prevalece sobre o prompt acima. O snapshot inicial já foi tirado: use HASH e não tire outro. Não dispare subagentes: faça a investigação você mesmo, implemente em sequência as partes paralelas, se houver, e pule a verificação independente, que o orquestrador fará. Não marque a etapa como #concluida: ao terminar, deixe-a #em-andamento com 'Evidências e handoff' preenchido e relate o resultado das validações. Diante de bloqueio real, marque #bloqueada como o prompt manda." Substitua HASH pelo hash do passo 2.
4. Se o subagente relatar bloqueio, divergência do plano ou validação falhando que não conseguiu resolver, pare.
5. Qualquer que seja a estratégia, dispare um subagente general-purpose novo com o "Prompt do verificador" do protocolo (ETAPA e HASH_INICIAL preenchidos). Se REPROVADO, mande os problemas ao mesmo implementador, se puder retomá-lo; senão, dispare um implementador novo com o prompt da etapa, o adendo, o parecer do verificador e o handoff atual. Verifique de novo com um verificador novo; após duas reprovações, pare.
6. Com o aceite, troque a tag da etapa para #concluida no índice e no título e acrescente ao handoff o parecer do verificador.
7. Se a etapa tiver "Revisão humana: sim", pare para o usuário revisar.

Pare também ao atingir o limite desta execução. Nunca faça commit, push ou deploy e não altere tags de etapas que não executou.

Ao parar, responda com: etapas concluídas e seus pareceres, a etapa atual e seu estado, o motivo da parada, as etapas #bloqueada com a ação de desbloqueio de cada uma, os arquivos alterados (git status --short) e o próximo passo sugerido — normalmente revisar e fazer commit antes da próxima execução.
```

## Índice de etapas

**UHs e status**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E01 | Cadastro de tipos de UH, UHs e áreas | #concluida | — | Módulo `rooms` com tipos, UHs, características e áreas comuns |
| E02 | Status de UH, histórico e discrepâncias | #concluida | E01 | Dimensões de status, transições da SPEC §8.4, histórico, RN-05, discrepâncias e `RoomStatusChanged` |
| E03 | OOO e OOS | #concluida | E02 | Períodos fora de ordem e fora de serviço com step-up, retorno e `OutOfOrderChanged` |

**Tempo real, offline e outbox**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E04 | Tempo real (SSE) | #concluida | E02 | `GET /api/v1/stream` com tópicos, filtro por permissão, `Last-Event-ID` e tópico `rooms` |
| E05 | Sincronização offline | #concluida | E02 | `GET /sync/changes` por cursor e `POST /sync/commands` idempotente, com `rooms` como primeira fonte |
| E12 | Limite de tentativas e arquivamento do outbox | #concluida | — | Backoff, abandono com alerta e expurgo das publicações concluídas |

**Checklists e anexos**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E06 | Modelos de checklist | #concluida | — | Modelos, versões imutáveis, itens, condicionais, aplicabilidade e ações configuradas |
| E11 | Ciclo de vida dos anexos | #concluida | — | `detach`/`delete`, expurgo de órfãos, upload com `clientFileId` e reencode de imagens |
| E07 | Execução de checklists e rotinas | #concluida | E05, E06, E11, E12 | Execução online e offline com fotos, `ChecklistCompleted` e rotinas por setor e escala |
| E08 | Relatórios de checklists | #pendente | E07 | Conformidade por modelo, itens mais reprovados e histórico por local |

**Notificações e gate**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E09 | Notificações internas e push | #pendente | E04, E12 | Notificações, preferências, tópico SSE e adaptador FCM |
| E10 | Gate do marco M2 | #pendente | E03, E08, E09 | Definição global de pronto verificada do zero |

---

## E01 — Cadastro de tipos de UH, UHs e áreas — #concluida

**Objetivo:** criar o módulo `rooms` com o cadastro de tipos de UH, características, UHs e áreas comuns (RF-UH-01 a 03), com endpoints, permissões e auditoria.

**Por que agora:** status, OOO, sincronização, checklists e todos os marcos de hospedagem referenciam UHs e áreas.

**Dependências e paralelismo:** nenhuma dentro deste marco; o gate do M1 está concluído. Pode correr em paralelo com E06, E11 e E12 (em worktree separado); conflito de edição em `api/openapi.yaml`, `ModularityTest.kt` e `CLAUDE.md`.

**Escopo:**

- Módulo `rooms` (a criar) em `src/main/kotlin/com/zeny/hotel/rooms/`: `RoomsModule.kt` com `@ApplicationModule(allowedDependencies = ["shared", "hotel"])`, `RoomsPermissions` (`ModulePermissions`, com `templates` para `MANAGER`, `HOUSEKEEPER` e `RECEPTIONIST` conforme a SPEC §3.1) e o bean numa `@Configuration` de `application`, `RoomsErrorCodes` e `RoomsApi` na raiz.
- Migrações em `src/main/resources/db/migration/rooms/` (a criar): tabelas no schema `rooms`, a migração de `GRANT` ao `"${app_role}"` no modelo da do `staff` e trigger que recusa `DELETE` (como `staff.refuse_delete`).
- `RoomType` (código, nome, descrição, ocupação padrão e máxima de adultos e crianças, configuração de camas, características, ordem de exibição), `RoomFeature`, `Room` (número único, andar/ala, tipo, características como vista, acessível, conjugada, ativa/inativa, observações) e `Area` (nome, tipo: lobby, piscina, cozinha, salão, corredor, outro) — SPEC §8.2.3.
- Endpoints `@ApiV1Controller`: `/api/v1/room-types`, `/api/v1/room-features`, `/api/v1/rooms`, `/api/v1/areas`, com lista paginada por cursor, detalhe com `ETag`, criação (201 com `Location`), edição com `If-Match` e desativação/reativação por ação (`POST …/{id}/deactivate`); sem `DELETE`.
- Unicidade (número de UH, código de tipo) no banco, com `saveAndFlush` e a violação daquela constraint convertida em `409` no adaptador, seguindo o `StaffPersistence` (não importe o `UniqueConstraints` do `iam`).
- API pública de leitura (UH, tipo, área, UHs ativas por tipo) para os módulos futuros.
- `ModularityTest`: teste `rooms depends only on shared and hotel, and only on their public APIs`.
- `CLAUDE.md`: "Estado atual" e seção do módulo `rooms`.

**Fora de escopo:** status e histórico (E02); OOO/OOS (E03).

**Passos de implementação:**

1. Escrever testes de integração (`@IntegrationTest`, com `IamTestSupport` para usuários e roles) dos endpoints, incluindo número de UH duplicado, ocupação máxima menor que a padrão, desativação de tipo com UHs ativas e escrita sem permissão.
2. Implementar módulo, migrações, casos de uso `@Audited`, controllers e API pública.
3. Regenerar o contrato com `./gradlew updateOpenApi`.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em SPEC §5.4 e §8.2.3 e convenções no `CLAUDE.md` (o módulo `staff` é o modelo mais próximo: cadastro com `If-Match`, desativação e trigger contra exclusão).
- Partes paralelas: nenhuma.
- Foco do verificador: número de UH duplicado aceito ou violação virando 500; exclusão física possível por API ou SQL do papel da aplicação; `rooms` dependendo de algo além de `shared` e `hotel`; escrita sem permissão ou sem auditoria.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E01 — "Cadastro de tipos de UH, UHs e áreas" do roadmap docs/roadmaps/roadmap-m2-plataforma-operacional.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Contexto confirmado", "Restrições e invariantes" e o "Protocolo de execução" —, a seção E01 inteira, docs/SPEC.md §5.4 e §8.2.3 e as "Notas para o marco M2" da etapa E09 de docs/roadmaps/roadmap-m1-acesso-configuracao.md. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que a etapa E09 (gate) de docs/roadmaps/roadmap-m1-acesso-configuracao.md está #concluida e que o código bate com o "Contexto confirmado"; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar o módulo rooms (src/main/kotlin/com/zeny/hotel/rooms/, allowedDependencies shared e hotel) com tipos de UH, características, UHs (número único) e áreas comuns; endpoints /api/v1/room-types, /room-features, /rooms e /areas com cursor, ETag, If-Match e desativação por ação, sem DELETE e com trigger que recusa exclusão; RoomsPermissions com templates, @Audited, unicidade convertida em 409 como no StaffPersistence e API pública de leitura. Não implemente status, histórico nem OOO/OOS.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E01 para #em-andamento no índice e no título;
- sem investigação delegada; use o módulo staff como modelo;
- escreva os testes de integração primeiro, implemente, acrescente o teste do rooms ao ModularityTest, atualize o CLAUDE.md, rode ./gradlew updateOpenApi e depois ./gradlew build e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em número duplicado, exclusão física, dependência indevida e escrita sem permissão ou auditoria.

Critérios de aceite (detalhes na seção E01):
- CRUD dos quatro recursos coberto por testes de integração;
- número de UH duplicado → 409 com code estável; sem permissão → 403;
- nenhuma exclusão física de UH (API nem SQL); escritas auditadas;
- ModularityTest com o rooms; contrato regenerado; build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E01 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [x] Os quatro recursos têm lista paginada por cursor, detalhe com `ETag`, criação, edição com `If-Match` e desativação, cobertos por testes de integração.
- [x] Número de UH duplicado → `409` com `code` estável (nunca 500).
- [x] Ocupação máxima menor que a padrão → `400` com `code` estável.
- [x] Não existe exclusão física de UH: nenhum `DELETE` no contrato e o trigger recusa `DELETE` do papel da aplicação (teste); UH desativada some das listas de UHs ativas da API pública.
- [x] Sem permissão → `403`; escritas auditadas (`LayerArchitectureTest` e entradas na trilha).
- [x] `ModularityTest` mostra `rooms` dependendo só de `shared` (e `hotel`, se usado), pela API pública; `CLAUDE.md` atualizado; contrato regenerado; build passa.

**Validação obrigatória:**

- `./gradlew build` → sucesso (inclui testes, lint, `checkOpenApi` e Kover).
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:**

- **Resolvido em 2026-10-15:** o usuário instalou o OpenJDK 25.0.4.1 (no `PATH`) e o Docker 29.1.3 (sem `sudo`, com compose e buildx); a etapa voltou a `#em-andamento` na retomada, que rodou as validações pendentes (ver "Evidências e handoff"). O registro original do bloqueio fica abaixo.
- **Causa:** o ambiente da execução de 2026-10-15 não tem Docker (nem `docker` no `PATH`, nem `/var/run/docker.sock`) nem JDK instalado. O Testcontainers dos testes de integração, o `./gradlew updateOpenApi` (o `OpenApiContractExport` sobe um PostgreSQL), o `./gradlew build` e o `.github/scripts/check-api-breaking.sh` (oasdiff em contêiner) exigem Docker.
- **Evidência:** `./gradlew updateOpenApi` → `OpenApiContractExport > exports the openapi contract() FAILED … IllegalStateException at DockerClientProviderStrategy.java:274` (sem ambiente Docker); `which docker java` sem resultado. Para compilar, rodar o lint e os testes sem Docker, a sessão baixou um JDK Temurin 25.0.4.1 (SHA-256 conferido) para o diretório temporário da sessão; o Gradle wrapper baixou a distribuição 9.8.1 para `~/.gradle`.
- **Impacto:** o código da etapa está implementado, compila e passa no lint e nos testes que não precisam de Docker, mas `RoomsApiTest` (os critérios de CRUD, 409, 403, trigger e auditoria), `DatabaseMigrationsTest`/`DatabaseRolesTest` com as migrações novas e o resto da suíte não rodaram; `api/openapi.yaml` **não foi regenerado** (está desatualizado: o `checkOpenApi` do `build` vai falhar até rodar o `updateOpenApi`); o oasdiff não rodou. Nenhum critério que dependa deles pode ser dado como atendido.
- **Ação de desbloqueio:** o usuário decidiu (2026-10-15) instalar Docker e JDK 25 nesta máquina. Com eles instalados: implementar o pré-carregamento das três características decidido na entrevista (ver "Evidências e handoff"), rodar na raiz `./gradlew updateOpenApi`, depois `./gradlew build` e `.github/scripts/check-api-breaking.sh`; corrigir o que falhar; versionar o `api/openapi.yaml` regenerado; voltar a tag para `#em-andamento` e seguir para a verificação independente.

**Riscos e mitigação:**

- Cadastro inicial manual de até 30 UHs (SPEC §1): aceitável; não há importação.

**Evidências e handoff:**

- Snapshot inicial: `44b15542af164d7b1468b3a8bc24f146ad4adf4d` (recebido do orquestrador; `HEAD` `346af17`, árvore limpa). Snapshot ao fim da primeira execução (bloqueada): `ee60ec4d75069c924622cc83186dcd5fd03b7245`. Snapshot ao fim da retomada, antes deste handoff: `011e73da0ed00e292af5ccf9676e02bd99a5f23f` (27 arquivos, +4847/−120 contra o inicial). Preparação: a E09 do M1 está `#concluida`, e o código bate com o "Contexto confirmado" (módulos `shared`, `iam`, `audit`, `hotel`, `staff` e `files`; última migração `V2026_10_15_1446`; `StaffPersistence` com `unlessViolated`; `staff.refuse_delete`; `RoleTemplate` com `MANAGER`, `RECEPTIONIST` e `HOUSEKEEPER`; ARCHITECTURE §3.1 já com `rooms` → `shared, hotel`).
- Retomada (2026-10-15): bloqueio resolvido (JDK 25 e Docker instalados); a sessão do orquestrador caiu no meio da retomada e ela foi continuada: `git status` conferido, `./gradlew --stop`, nenhum container nem processo Gradle órfão (`docker ps` vazio).
- Arquivos alterados:
  - novos, módulo `rooms` (`src/main/kotlin/com/zeny/hotel/rooms/`): raiz `RoomsModule.kt` (`allowedDependencies = ["shared", "hotel"]`), `RoomsApi.kt` (porta, ids, `AreaKind`, `Occupancy`, `RoomFeatureKey` e resumos), `RoomsPermissions.kt`, `RoomsErrorCodes.kt`; `domain/` `RegistryRecord.kt` (`@MappedSuperclass` com ativo, `updated_at` e versão; `RoomFeatureIdConverter`), `RoomFeature.kt` (com a `key`, `updatable = false`), `RoomType.kt`, `Room.kt`, `Area.kt`, `RoomsRules.kt`; `application/` `RoomsRegistry.kt` (`RoomsRegistryService` e o bean das permissões), `RoomsPorts.kt` (comandos, `RoomsStore`, `RoomsDirectory`, views e `RoomsConstraints`), `RoomsAudit.kt`, `RoomsApiService.kt`; `infrastructure/` `RoomsPersistence.kt` (`JpaRoomsStore`, conversão da unicidade em `false` como no `StaffPersistence`, sem importar o `iam`), `JdbcRoomsDirectory.kt`; `web/` `RoomsRegistryController.kt` (quatro controllers) e `RoomsDtos.kt`;
  - novas migrações `src/main/resources/db/migration/rooms/V2026_10_15_1957__create_rooms.sql` (tabelas, `CHECK`s, unicidades, a pré-carga das três características e o trigger `rooms.refuse_delete` em `room_feature`, `room_type`, `room` e `area`) e `V2026_10_15_1958__grant_rooms_to_app_role.sql`;
  - testes: novos `src/test/kotlin/com/zeny/hotel/rooms/RoomsApiTest.kt` (11 testes) e `RoomsTestSupport.kt`; `ModularityTest.kt` com `rooms depends only on shared and hotel, and only on their public APIs`; `iam/AuthorizationTest.kt` (o Gerente passa a ter `rooms.registry.read` e `rooms.registry.manage` no teste dos modelos de role);
  - `api/openapi.yaml` regenerado (só acréscimos: `/api/v1/room-features`, `/room-types`, `/rooms`, `/areas` e os schemas);
  - `CLAUDE.md` ("Estado atual" e a seção "UHs e áreas"); este roadmap (tag, bloqueio e handoff).
- Decisões:
  1. Permissões `rooms.registry.read` (sugerida a Gerente, Recepcionista e Governanta) e `rooms.registry.manage` (só Gerente, além do Administrador automático), ambas só `ALL` e não sensíveis — **confirmado pelo usuário** na entrevista de 2026-10-15.
  2. Desativar um tipo com UHs ativas é recusado (`422 ROOM_TYPE_HAS_ACTIVE_ROOMS`), e UH ativa nunca fica em tipo desativado (`422 ROOM_TYPE_INACTIVE` ao criar, mudar de tipo ou reativar), com a linha do tipo travada nas duas pontas — **confirmado pelo usuário**.
  3. **Decidido pelo usuário (2026-10-15), implementado na retomada:** "vista", "acessível" e "conjugada" vêm pré-carregadas pela migração, como os setores do `staff`: coluna `system_key` (única, `CHECK` com `VIEW`, `ACCESSIBLE`, `CONNECTING`, `updatable = false` na entidade) e o enum público `RoomFeatureKey`. Linhas: `VIEW` "Vista", `ACCESSIBLE` "Acessível" e `CONNECTING` "Conjugada" (código inicial = a chave; ids UUIDv7 fixos `019b76da-b00{0,1,2}-…`). "Conjugada" é característica da UH, não vínculo entre UHs. O gestor troca código e nome, desativa e reativa as pré-carregadas (a chave não muda; o trigger recusa a exclusão); as criadas pela API têm `key` nula. A resposta de `/room-features` ganhou `key` (nulável) e a `RoomsApi` ganhou `feature(id)` e `feature(key)`; outros módulos acham "acessível" pela chave, nunca pelo código. **Migração:** a pré-carga entrou na migração de criação existente (`V2026_10_15_1957`), não numa nova, porque ela nunca foi aplicada em ambiente nenhum nem commitada (o mesmo arquivo do `staff` cria e popula `department`).
  4. Nome de área único sem diferenciar maiúsculas (`409 AREA_NAME_ALREADY_IN_USE`) — **confirmado pelo usuário**.
  5. Códigos de tipo e característica normalizados para maiúsculas (letras, dígitos, `_`, `-`, até 20) e editáveis; número de UH com até 10 letras, dígitos ou `-`, único sem diferenciar maiúsculas (índice em `lower(number)`), com a caixa preservada; o número de uma UH desativada continua reservado.
  6. Ocupação: ao menos 1 adulto na padrão, máximas de 0 a 20 e nunca menores que a padrão (`400 ROOM_TYPE_OCCUPANCY_INVALID` no campo); camas e descrição opcionais; ordem de exibição de 0 a 9999 (padrão 0).
  7. Característica desativada não é atribuída de novo (`422 ROOM_FEATURE_INACTIVE`), mas os vínculos existentes ficam e mantê-la numa edição não falha.
  8. A desativação e a reativação não exigem `If-Match` (idempotentes, como no `staff`); os `PUT` exigem.
  9. `hotel` fica permitido em `allowedDependencies`, mas o `rooms` ainda não o usa; o teste do `ModularityTest` aceita `shared` e, se usado, `hotel`.
  10. Leituras de lista por `JdbcClient` com as características agregadas por `ARRAY(…)`; o cursor das UHs e áreas usa `lowercase()` do Kotlin, como o dos colaboradores do `staff` (mesma ressalva para letras fora do ASCII).
- Validações executadas (retomada, 2026-10-15, OpenJDK 25.0.4.1 e Docker 29.1.3):
  - `./gradlew test --tests 'com.zeny.hotel.rooms.*'` antes da pré-carga → `RoomsApiTest` 10/10; depois da pré-carga (teste novo `view, accessible and connecting come preloaded with stable keys, and new features have none`), com `--tests 'com.zeny.hotel.persistence.*' --tests 'com.zeny.hotel.ModularityTest'` → `RoomsApiTest` 11/11, `ModularityTest` 9/9, `DatabaseMigrationsTest` 8/8, `DatabaseRolesTest` 6/6, `MigrationConventionTest` 11/11.
  - `./gradlew updateOpenApi` → `BUILD SUCCESSFUL`; `api/openapi.yaml` regenerado (+1302/−110 linhas de diff, a maior parte reordenação do diff textual; o oasdiff só vê acréscimos).
  - `./gradlew build` (1ª) → falhou em `AuthorizationTest > the role templates of SPEC 3_1 exist…` (a lista exata do Gerente não tinha as duas permissões novas do `rooms`); teste ajustado.
  - `./gradlew build` (2ª) → `BUILD SUCCESSFUL in 3m 3s`: 425 testes, 0 falhas, 0 ignorados; `detekt`, `detektMain`, `detektTest`, `ktlint*Check`, `checkOpenApi` e `koverVerify` passaram.
  - `.github/scripts/check-api-breaking.sh` → "No breaking changes to report, but the specs are different. Nenhuma mudança incompatível (nível ERR) em /api/v1." (base `346af17`, merge-base com `origin/main`).
- Decisão do usuário na retomada (2026-10-15, entrevista do orquestrador): o código inicial das três características pré-carregadas fica igual à chave, em inglês (`VIEW`, `ACCESSIBLE`, `CONNECTING`), com os nomes em pt-BR; o gestor pode trocá-lo pela API sem afetar a chave.
- Parecer do verificador (2026-10-15, verificador independente, snapshot `9c7b6a03a3548fd785093e53cb7d7e1b65c84db7`): **APROVADO**. Os seis critérios e a pré-carga têm evidência:
  - critérios 1 e 2: `RoomsApiTest` 11/11, inclusive a corrida de dois números iguais (409, nunca 500, via `JpaRoomsStore.unlessViolated`);
  - critério 4: o contrato não tem nenhum `delete:`, e o trigger `rooms.refuse_delete` está nas quatro tabelas, testado com o papel da aplicação;
  - critério 5: `LayerArchitectureTest` 6/6 e os testes de 403 e da trilha;
  - critério 6: `ModularityTest` 9/9 (`rooms` só usa `shared`);
  - `./gradlew build` passou com 425 testes e 0 falhas, e `.github/scripts/check-api-breaking.sh` não acusou mudança incompatível contra a base `346af178969a`.

  Nenhum problema grave nem médio. Ressalvas baixas, sem ação nesta etapa:
  1. quem não tem permissão e omite o `If-Match` recebe 428 antes do 403, como no `staff`;
  2. o cursor de áreas usa o `lowercase()` do Kotlin e a ordem usa o `lower()` do PostgreSQL, o que pode divergir com nomes fora do ASCII, como no `staff`;
  3. a corrida entre desativar um tipo e criar uma UH nele não tem teste (as duas pontas travam a linha do tipo, mas isso só foi conferido lendo o código);
  4. mover uma UH inativa para um tipo inativo também é recusado.

  Informativo: a pré-carga entrou na migração de criação, ainda não versionada nem aplicada, que precisa ir ao commit assim.
- Notas para a próxima etapa:
  - E02: o status entra numa migração nova de `rooms` (timestamp depois de `V2026_10_15_1958`) acrescentando colunas a `rooms.room`; a `Room` já tem `version` (lock otimista) e herda de `RegistryRecord`. `RoomsApi` ganha os métodos de status; o teste `no rooms endpoint deletes anything` do `RoomsApiTest` lista exatamente os caminhos dos controllers do pacote `rooms` e precisa ganhar os novos (`/api/v1/rooms/status`, `/rooms/{id}/status-history`…). Permissões novas de status entram em `RoomsPermissions` (o teste de templates do `RoomsApiTest` confere só as duas de cadastro pelo catálogo `rooms.%`, e o `AuthorizationTest` lista exatamente as do Gerente e da Camareira: os dois precisam ser ajustados).
  - E03 e M4: `RoomsApi.activeRooms(roomTypeId)` e `activeRoomTypes()` já existem para o inventário; UH desativada sai delas. M4/M5 acham UHs acessíveis ou conjugadas por `RoomsApi.feature(RoomFeatureKey.ACCESSIBLE|CONNECTING).id` contra `RoomSummary.featureIds`.
  - O banco de testes é compartilhado: um teste que edite uma característica pré-carregada devolve o código e o nome originais no fim (o `RoomsApiTest` faz isso com `ACCESSIBLE`).

---

## E02 — Status de UH, histórico e discrepâncias — #concluida

**Objetivo:** implementar o status da UH em dimensões independentes (ocupação, governança com "inspeção pendente", sinalização do hóspede), as transições da máquina de estados da SPEC §8.4, o histórico de mudanças, a regra RN-05 de UH pronta para check-in, a detecção de discrepâncias entre recepção e governança e o evento `RoomStatusChanged`.

**Por que agora:** recepção (M5), governança (M6) e o tempo real (E04) dependem do status; a sincronização (E05) usa a UH como primeira fonte de mudanças.

**Dependências e paralelismo:** E01. Não paralelizável.

**Escopo:**

- Em `Room`: `occupancy` (VACANT/OCCUPIED), `hkStatus` (DIRTY/CLEAN/INSPECTED), `inspectionPending`, `serviceFlag` (nenhum, não perturbe, arrumar agora), número de hóspedes informado pela recepção (H4) e `version` para lock otimista.
- Regras de transição da SPEC §8.4 no domínio (sem Spring), incluindo origem (manual, tarefa, check-out, fechamento) e RN-08 (toda mudança manual registrada).
- `RoomStatusChange` (campo, de, para, quando, quem, origem) — RF-UH-06; o autor vem do `CurrentActor` (nulo = sistema).
- API pública em `RoomsApi` para os módulos futuros, sem checar permissão (quem chama se autorizou): `markDirty(source)`, `markClean(requiresInspection)`, `markInspected()`, `rejectInspection()`, `markOccupied(guestCount)`, `markVacant()`, `setServiceFlag(...)`, `isReadyForCheckIn(room)` (RN-05; a parte de OOO é completada em E03).
- Endpoints: `GET /api/v1/rooms/status` (painel, RF-UH-07), `GET /api/v1/rooms/{id}/status-history` (cursor), `POST /api/v1/rooms/{id}/housekeeping-status` (mudança manual com permissão própria) e `POST /api/v1/rooms/{id}/service-flag`.
- Discrepâncias (RF-UH-08): `POST /api/v1/rooms/{id}/housekeeping-observations` (governança informa se achou a UH ocupada ou vazia e quantas pessoas) e `GET /api/v1/rooms/discrepancies`.
- Evento `RoomStatusChanged` em `rooms/RoomsEvents.kt`, com payload mínimo (ID da UH, dimensões novas, versão), publicado na transação (outbox).

**Fora de escopo:** OOO/OOS (E03); "em limpeza" (M6); check-in e check-out reais (M5); envio ao SSE (E04).

**Passos de implementação:**

1. Escrever testes de domínio de todas as transições permitidas e proibidas da SPEC §8.4 e de RN-05; testes de integração dos endpoints, do histórico, das discrepâncias, da corrida entre duas mudanças e da publicação do evento (com `@EnableScenarios` e um listener de teste, como o `BusinessDateTest`, porque o outbox só grava com listener).
2. Implementar domínio, migração, casos de uso, API pública e controllers.
3. Regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "quais transições a máquina 'Status de governança da UH' da SPEC §8.4 permite, e o que RF-UH-04 a 08, RN-05, RN-08 e RN-09 exigem?".
- Partes paralelas: nenhuma.
- Foco do verificador: transição proibida aceita (ex.: DIRTY → INSPECTED direto); mudança sem histórico (inclusive pela API pública); evento publicado com dados pessoais; condição de corrida em duas mudanças simultâneas.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E02 — "Status de UH, histórico e discrepâncias" do roadmap docs/roadmaps/roadmap-m2-plataforma-operacional.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Restrições e invariantes", a hipótese H4 e o "Protocolo de execução" —, a seção E02 inteira e o "Evidências e handoff" de E01. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: implementar em rooms as dimensões de status (ocupação, governança com inspeção pendente, sinalização do hóspede), as transições da SPEC §8.4 com origem, o histórico RoomStatusChange, a API pública RoomsApi (markDirty, markClean, markInspected, rejectInspection, markOccupied com número de hóspedes, markVacant, setServiceFlag, isReadyForCheckIn segundo RN-05), os endpoints de painel, histórico, mudança manual, sinalização e discrepâncias (RF-UH-08) e o evento RoomStatusChanged em RoomsEvents.kt com payload mínimo, pelo outbox. Não implemente OOO/OOS, check-in real nem o envio ao SSE.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E02 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume a máquina de status de governança da docs/SPEC.md §8.4 e RF-UH-04 a 08, RN-05, RN-08 e RN-09;
- escreva primeiro os testes de domínio de todas as transições e de RN-05 e os de integração dos endpoints, histórico, discrepâncias, corrida e evento (com @EnableScenarios e listener de teste, como o BusinessDateTest); implemente, atualize o CLAUDE.md, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em transição proibida aceita, mudança sem histórico, evento com dados pessoais e corrida entre mudanças simultâneas.

Critérios de aceite (detalhes na seção E02):
- todas as transições permitidas e proibidas da SPEC §8.4 cobertas por testes;
- toda mudança gera RoomStatusChange com quem, quando e origem;
- discrepâncias de ocupação e de número de hóspedes listadas;
- RoomStatusChanged publicado sem dados pessoais; contrato regenerado; build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E02 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [x] Testes de domínio cobrem cada transição permitida da máquina de status de governança da SPEC §8.4 e rejeitam as não previstas (ex.: DIRTY → INSPECTED) com `code` estável.
- [x] `markClean(requiresInspection = true)` deixa a UH em CLEAN com `inspectionPending`; `markInspected()` remove a pendência; `rejectInspection()` volta a DIRTY.
- [x] `isReadyForCheckIn` só devolve verdadeiro para UH vaga e Inspecionada, ou Limpa sem inspeção pendente (RN-05).
- [x] Toda mudança, manual ou pela API pública, grava `RoomStatusChange` com usuário, instante e origem; `GET /api/v1/rooms/{id}/status-history` a devolve.
- [x] Observação da governança divergente da ocupação ou do número de hóspedes aparece em `GET /api/v1/rooms/discrepancies`.
- [x] Duas mudanças simultâneas na mesma UH não se sobrescrevem silenciosamente (lock otimista ou trava, teste concorrente; `412` pela API com `If-Match` desatualizado).
- [x] `RoomStatusChanged` é publicado pelo outbox com ID, dimensões e versão, sem dados de hóspede.
- [x] Contrato regenerado; build e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Transições de origem automática (check-out, fechamento) ainda sem chamador: cobrir pela API pública em testes de módulo.

**Evidências e handoff:**

- Snapshot inicial: `d949ae0974fe78358175562352f2d429b2b7cb29` (recebido do orquestrador; `HEAD` `91b1ad8`, árvore limpa). Snapshot ao fim da implementação, antes deste handoff: `cc86a468434595f068c725101f8ecc3fc8bf37a3`. Preparação: E01 `#concluida`; o código bate com o handoff dela (`Room` com `version` herdada de `RegistryRecord`, última migração `V2026_10_15_1958`, teste `no rooms endpoint deletes anything` com a lista exata de caminhos, `AuthorizationTest` com a lista exata do Gerente).
- Investigação feita pela própria sessão (adendo do orquestrador): SPEC §8.4 (máquina "Status de governança da UH": Sujo → Limpo / Limpo-inspeção pendente; pendente → Inspecionado / Sujo; Limpo → Inspecionado; Limpo e Inspecionado → Sujo por check-out, fechamento ou uso), RF-UH-04 a 08, RN-05, RN-08, RN-09 e SPEC §8.2.3/§8.3.
- Arquivos alterados:
  - novos: `src/main/kotlin/com/zeny/hotel/rooms/RoomsEvents.kt` (`RoomStatusChanged`); `rooms/domain/RoomStatusChanges.kt` (`RoomStatusFieldChange`, `CheckInReadiness`); `rooms/application/RoomStatusPorts.kt` (`HousekeepingTransition`, `RoomDiscrepancyKind`, views e a porta `RoomStatusRecords`); `rooms/application/RoomStatusService.kt` (`RoomStatusChanger` e `RoomStatusService`); `rooms/infrastructure/JdbcRoomStatusRecords.kt` (histórico, observações, painel e discrepâncias pelo `JdbcClient`; bean `AppendOnlyTables` do `rooms`); `rooms/web/RoomStatusController.kt` (endpoints e DTOs); migração `src/main/resources/db/migration/rooms/V2026_10_15_2130__add_room_status.sql`; testes `src/test/kotlin/com/zeny/hotel/rooms/RoomStatusDomainTest.kt` (12) e `RoomStatusApiTest.kt` (11);
  - alterados: `rooms/RoomsApi.kt` (enums `OccupancyStatus`, `HousekeepingStatus`, `GuestServiceFlag`, `RoomStatusSource`, `RoomStatusField`, o `RoomStatus` e os métodos de status), `RoomsErrorCodes.kt` (`HOUSEKEEPING_TRANSITION_NOT_ALLOWED`, `GUEST_COUNT_INVALID`, `ROOM_INACTIVE`, `ROOM_NOT_OCCUPIED`, `ROOM_OCCUPIED`), `RoomsPermissions.kt` (`rooms.status.read`, `.manage`, `.flag`, `.observe`), `domain/Room.kt` (dimensões e operações), `application/RoomsApiService.kt`, `RoomsAudit.kt`, `RoomsPorts.kt` (`roomForUpdate`), `RoomsRegistry.kt` (desativar UH ocupada: `422 ROOM_OCCUPIED`), `infrastructure/RoomsPersistence.kt` (`findForUpdate` + `refresh`); testes `rooms/RoomsApiTest.kt` (caminhos novos e permissões) e `iam/AuthorizationTest.kt` (Gerente com as quatro permissões de status); `api/openapi.yaml` regenerado (+574 linhas, só acréscimos: sete caminhos novos sob `/api/v1/rooms`); `CLAUDE.md` ("Estado atual" e a subseção "Status" de "UHs e áreas"); este roadmap.
- Decisões (adotadas como a opção mais conservadora; as marcadas com ⚑ vão para a entrevista do orquestrador):
  1. ⚑ "Limpo — inspeção pendente" é um Limpo com a marca de pendência (RF-UH-04 e o modelo `hkStatus` + `inspectionPending`), então `markDirty` (check-out, fechamento, uso) também o leva a Sujo, além da reprovação. Sem isso, o fechamento do dia (RN-09) não sujaria uma UH ocupada que estivesse aguardando inspeção.
  2. ⚑ Repetir a operação do estado atual (sujar uma UH suja, inspecionar uma inspecionada, `markClean` com a mesma pendência, reprovar uma UH já suja) não muda nada e não grava histórico, entidade de auditoria nem evento, em vez de `422`: os listeners dos marcos seguintes são reentregues pelo outbox e precisam ser idempotentes. Toda transição fora da SPEC §8.4 continua `422 HOUSEKEEPING_TRANSITION_NOT_ALLOWED` (um código só, mensagem por caso).
  3. ⚑ RN-09 no domínio: `markVacant(CHECK_OUT)` deixa a UH Vaga **e** Suja na mesma mudança (um evento), mesmo que ela já estivesse vaga; com outra origem, só a ocupação muda. Ficar vaga também retira o número de hóspedes e a sinalização do hóspede; a sinalização (não perturbe, arrumar agora) só vale numa UH ocupada (`422 ROOM_NOT_OCCUPIED`; `NONE` vale sempre). O "fechamento: ocupadas passam a Sujo" fica para o `endofday` (M6), chamando `markDirty(END_OF_DAY)`.
  4. ⚑ Permissões novas, todas só `ALL` e não sensíveis: `rooms.status.read` (Gerente, Recepcionista, Governanta), `rooms.status.manage` (Gerente, Governanta), `rooms.status.flag` (Gerente, Recepcionista, Governanta) e `rooms.status.observe` (Gerente, Governanta). A Camareira não recebe `observe` de saída (o administrador pode conceder). O código de permissão não aceita hífen, por isso `flag`/`observe` e não `service-flag`.
  5. ⚑ O status fica em colunas da própria `rooms.room`, com a mesma `version` do cadastro (como o roadmap previa): uma mudança de status muda o `ETag` da UH, e um `PUT /rooms/{id}` com o `ETag` de antes responde 412. Alternativa: tabela de status com versão própria.
  6. ⚑ `POST /rooms/{id}/service-flag` não exige `If-Match` (define o valor, como as ações de desativação do E01); `POST /rooms/{id}/housekeeping-status` exige.
  7. Discrepâncias calculadas na consulta, sem registro de resolução: vale só a **última** observação da UH, e só se feita depois da última mudança da ocupação ou do número de hóspedes (`occupancy_changed_at`); `GUEST_COUNT_MISMATCH` só quando a governança contou. A observação não tem texto livre (nada de dado de hóspede), aceita número só com a UH ocupada (`400 GUEST_COUNT_INVALID`) e é recusada em UH desativada (`422 ROOM_INACTIVE`).
  8. `markOccupied` numa UH desativada: `422 ROOM_INACTIVE`; desativar uma UH ocupada: `422 ROOM_OCCUPIED` (uma ocupação simultânea muda a versão e a desativação responde 412). Mudanças de governança e sinalização em UH desativada são aceitas; o painel mostra só as ativas; RN-05 exige a UH ativa.
  9. Número de hóspedes de 1 a 40 (o dobro do teto de adultos de um tipo), `400 GUEST_COUNT_INVALID`; não é conferido contra a ocupação máxima do tipo.
  10. `RoomStatusSource` tem `CHECK_IN` além das quatro origens da RF-UH-06 (manual, tarefa, check-out, fechamento), para a ocupação vinda da recepção.
  11. Histórico com um registro por campo que mudou (`OCCUPANCY`, `GUEST_COUNT`, `HOUSEKEEPING_STATUS`, `INSPECTION_PENDING`, `SERVICE_FLAG`) e a versão da UH depois da mudança; `rooms.room_status_change` e `rooms.housekeeping_observation` são *append-only* para o papel da aplicação (`SELECT, INSERT`, bean `AppendOnlyTables`). O autor é o `CurrentActor` (nulo = sistema, como nas chamadas da API pública fora de requisição).
  12. Evento mínimo: `roomId` como `UUID` (evita depender da serialização de *value class* no outbox), `occupancy`, `housekeepingStatus`, `inspectionPending`, `serviceFlag`, `version` e `changedAt`. Sem número de hóspedes, autor nem `readyForCheckIn` (o OOO da E03 entra na prontidão).
  13. Cada operação da API pública é um caso de uso `@Audited` próprio (`rooms.room-status.<mark-dirty|mark-clean|mark-inspected|reject-inspection|mark-occupied|mark-vacant|set-service-flag>`), entidade `rooms.room-status`; a mudança manual é `rooms.room-status.change-housekeeping`; a observação, `rooms.housekeeping-observation.record` (entidade = a observação).
  14. Concorrência: toda mudança trava a linha (`SELECT … FOR UPDATE` e `refresh`, para não usar um estado lido antes da trava na mesma transação); a mudança manual ainda confere o `If-Match` depois da trava (412).
  15. A migração mantém os padrões no banco (`VACANT`, `DIRTY`, `false`, `NONE`, `now()`): o teste de corrida do E01 insere UHs por SQL sem as colunas novas, e a UH nasce Vaga e Suja pela SPEC §8.4.
- Validações executadas (OpenJDK 25.0.4.1, Docker 29.1.3):
  - Testes escritos antes da implementação (não compilavam); a primeira execução de `./gradlew test --tests 'com.zeny.hotel.rooms.*'` travou no teste de corrida do E01 (`two rooms with the same number…`): o `INSERT` por SQL falhava com as colunas novas sem padrão, a thread morria e o `CountDownLatch` nunca abria (a execução foi encerrada à mão). Corrigido mantendo os padrões no banco (decisão 15).
  - `./gradlew test --tests 'com.zeny.hotel.rooms.*'` → `RoomsApiTest` 11/11, `RoomStatusApiTest` 11/11, `RoomStatusDomainTest` 12/12.
  - Prova de que os testes pegam a regressão (mutação temporária, revertida): sem a trava (`findById` no lugar de `findForUpdate`) e aceitando Sujo → Inspecionado, 4 testes falharam (`every transition outside SPEC 8_4 is refused…`, `a manual housekeeping change…`, `every change through the public API…` e `a change through the public API waits for a concurrent one…`).
  - `./gradlew ktlintFormat`, depois `ktlintCheck detekt detektMain detektTest` → ajustes (dois nomes de teste longos, `ThrowsCount` resolvido movendo a validação da observação para o domínio, `LongParameterList` suprimido com justificativa, `UnnecessaryFilter`) → sem problemas.
  - `./gradlew updateOpenApi` → `api/openapi.yaml` +574/−0 (só acréscimos).
  - `./gradlew build` → `BUILD SUCCESSFUL in 2m 9s`: 448 testes, 0 falhas, 0 ignorados; `checkOpenApi`, `detekt*`, `ktlint*Check` e `koverVerify` passaram.
  - `.github/scripts/check-api-breaking.sh` → "No breaking changes to report… Nenhuma mudança incompatível (nível ERR) em /api/v1." contra a base `346af178969a` (merge-base com `origin/main`) e também contra `HEAD` (`91b1ad8`).
- Critérios de aceite (evidência para o verificador): transições permitidas e proibidas — `RoomStatusDomainTest` (tabela estado × operação com os 20 casos, conferida contra as 7 arestas do diagrama); `markClean(true)`/`markInspected`/`rejectInspection` — `markClean with inspection leaves it pending…` e o teste da API pública; RN-05 — `a room is ready for check-in only when…` (todas as combinações de estado, ocupação e ativa); histórico com usuário, instante e origem — `a manual housekeeping change…` (autor = o usuário) e `every change through the public API…` (autor nulo = sistema, origem de cada chamada); discrepâncias — `housekeeping observations that differ…`; corrida — `two manual changes with the same If-Match…` (200 + 412) e `a change through the public API waits…`; evento — `RoomStatusChanged carries the room…` (Scenario e as chaves exatas do JSON gravado no outbox) e `the event and the history are written in the changing transaction…`.
- Entrevista com o usuário (2026-10-15, orquestrador). O usuário confirmou as seis decisões que o implementador deixou em aberto, todas como já estavam implementadas, sem mudança de código:
  1. UH limpa aguardando inspeção pode voltar a suja também por `markDirty` (check-out, fechamento, uso), não só pela inspeção reprovada.
  2. Pedir o status que a UH já tem não faz nada: sem histórico, sem evento e sem erro.
  3. RN-09 fica no domínio: `markVacant(CHECK_OUT)` deixa a UH vaga e suja e limpa o número de hóspedes e a sinalização; a sinalização só vale com a UH ocupada.
  4. Modelos de permissão como implementados: `rooms.status.observe` vai só para Gerente e Governanta, não para a Camareira.
  5. O status compartilha a versão (o `ETag`) do cadastro da UH.
  6. `POST /rooms/{id}/service-flag` não exige `If-Match`; a mudança de governança exige.
- Parecer do verificador (2026-10-15, verificador independente, snapshot `cca2e3ddb72d4d281d1fd910f071dfd2f5587124`): **APROVADO**, com os oito critérios atendidos:
  - transições: `RoomStatusDomainTest` (tabela de 20 casos, com as 7 arestas exatas da SPEC §8.4) e `RoomStatusApiTest`;
  - RN-05: `CheckInReadiness`;
  - histórico com autor, instante e origem: todas as mutações passam por `RoomStatusChanger`;
  - discrepâncias de ocupação e de número de hóspedes;
  - corrida: `SELECT … FOR UPDATE`, com 200 + 412 e um único registro no histórico;
  - `RoomStatusChanged` pelo outbox, sem número de hóspedes nem autor, desfeito no rollback;
  - `./gradlew build` passou em 2m49s com 448 testes e 0 falhas, sem travar; `.github/scripts/check-api-breaking.sh` não acusou mudança incompatível nem contra `346af178969a` nem contra `91b1ad819013`.

  O verificador aplicou mutações numa cópia descartável: gravar o histórico só da origem `MANUAL` derrubou 3 testes; tirar a trava pessimista e acrescentar `guestCount` ao evento derrubou 2. Nenhum problema crítico, alto ou médio. Ressalvas baixas:
  1. o teste da corrida de números da E01 (`RoomsApiTest`) espera o latch sem prazo e não propaga a exceção da thread;
  2. `roomForUpdate` faz `refresh`, que descarta alterações ainda sem flush na mesma `Room`; documentar para quem combinar edição de cadastro e status numa transação (M5/M6);
  3. `recordObservation` não trava a UH, então uma mudança de ocupação simultânea pode afetar a lista de discrepâncias numa janela estreita;
  4. "UH inativa ⇒ vaga" é garantido pela aplicação e pelo lock otimista, não pelo banco;
  5. (informativo) o teste de corrida com o mesmo `If-Match` passaria só com o lock otimista; a trava pessimista é provada pelo outro teste de corrida.
- Notas para a próxima etapa:
  - E03: completar RN-05 com o OOO em `CheckInReadiness`/`Room.isReadyForCheckIn` (o painel calcula `readyForCheckIn` pela `RoomStatusView`, que também precisará do OOO) e recusar OOO em UH com `occupancy = OCCUPIED` (RN-07); o `RoomStatusSource` e o histórico podem ganhar campos/origens novos (o `CHECK` de `room_status_change` lista os valores: altere numa migração nova).
  - E04: o tópico `rooms` escuta `RoomStatusChanged` (payload já sem dado de hóspede; `version` para descartar eventos velhos). Como o listener de teste mostra, o outbox só grava a publicação quando há listener.
  - E05: a UH muda a `version` a cada mudança de status; a sincronização pode usar o painel (`RoomStatusView`) como formato.
  - M5/M6: `frontdesk` confere `isReadyForCheckIn` antes do check-in e chama `markOccupied(guestCount, CHECK_IN)` e `markVacant(CHECK_OUT)`; a governança chama `markClean`/`markInspected`/`rejectInspection` com `TASK`; o `endofday`, `markDirty(END_OF_DAY)` nas ocupadas. Os listeners podem reentregar: as operações são idempotentes no estado de destino.
  - Ressalva sem ação: o teste de corrida do E01 (`RoomsApiTest`) trava se o `INSERT` da thread falhar (a exceção da thread não é propagada e o latch não abre); um `await` com prazo o tornaria mais robusto.

---

## E03 — OOO e OOS — #concluida

**Objetivo:** implementar períodos fora de ordem (OOO) e fora de serviço (OOS) por UH (RF-UH-05, RN-07): período, motivo, responsável e status de retorno; OOO exige step-up e não se aplica a UH ocupada; liberação devolve a UH ao status de retorno; o evento `OutOfOrderChanged` avisa os módulos de inventário.

**Por que agora:** o inventário vendável (M4) e o channel manager (M8) dependem desse evento; RF-ACS-08 lista "colocar UH em OOO" como ação sensível.

**Dependências e paralelismo:** E02. Pode correr em paralelo com E04 e E05 (em worktree separado); conflito de edição em `api/openapi.yaml`, `CLAUDE.md` e na API pública de `rooms`.

**Escopo:**

- `OutOfOrder` (tipo OOO/OOS, de, até em datas de negócio, motivo, responsável, status de retorno, liberado em/por) — SPEC §8.2.3; "período atual" pela `HotelApi.currentBusinessDate()`.
- Endpoints: `POST /api/v1/rooms/{id}/out-of-order` (abrir), `POST /api/v1/rooms/{id}/out-of-order/{oooId}/release`, edição de período com `If-Match` e `GET /api/v1/out-of-order` (por período, cursor).
- Regras: OOO é ação sensível — permissão com `sensitive = true` e caso de uso com `authorization.requireOrApproval(SensitiveAction("rooms.out-of-order.open", …, "rooms.room:<id da UH>"), approvalToken)`, cabeçalho `APPROVAL_TOKEN_HEADER` no controller (passo a passo no `CLAUDE.md`); OOO não se aplica a UH ocupada no período atual (RN-07); períodos de OOO/OOS da mesma UH não se sobrepõem (`EXCLUDE USING gist` com `btree_gist`, como o `staff`); OOS mantém a UH vendável.
- `isReadyForCheckIn` passa a considerar OOO (RN-05); o painel de status mostra a sobreposição OOO/OOS.
- Evento `OutOfOrderChanged` (UH, tipo, período, ação: aberto, alterado, liberado).
- API pública: períodos OOO/OOS por UH e intervalo de datas (para reservas em M4) e abertura/liberação sem checar permissão (para a manutenção em M6, que faz o próprio step-up).

**Fora de escopo:** recálculo de inventário e alerta de reserva alocada (M4, ao escutar o evento); ordem de serviço ligada ao OOO (M6).

**Passos de implementação:**

1. Escrever testes de domínio (sobreposição, UH ocupada, retorno) e de integração (step-up com `IamTestSupport.setPin` e `asUser` com sessão fixa, evento, painel).
2. Implementar domínio, migração, casos de uso e controllers.
3. Regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em RF-UH-05, RN-05, RN-07 e RF-ACS-08, e o step-up no `CLAUDE.md` ("Aprovação de superior") e no `ApprovalTest`.
- Partes paralelas: nenhuma.
- Foco do verificador: OOO aplicado sem aprovação ou a UH ocupada; `resourceRef` com dado pessoal; períodos sobrepostos aceitos pelo banco; evento não publicado na liberação.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E03 — "OOO e OOS" do roadmap docs/roadmaps/roadmap-m2-plataforma-operacional.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Restrições e invariantes" e o "Protocolo de execução" —, a seção E03 inteira, o "Evidências e handoff" de E02, RF-UH-05, RN-05, RN-07 e RF-ACS-08 em docs/SPEC.md e o handoff de E05 (step-up) de docs/roadmaps/roadmap-m1-acesso-configuracao.md. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E02 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: implementar em rooms os períodos OOO e OOS com motivo, responsável e status de retorno; OOO é ação sensível com authorization.requireOrApproval e X-Approval-Token e é rejeitado em UH ocupada no período atual (data operacional da HotelApi); períodos da mesma UH não se sobrepõem (EXCLUDE USING gist); liberação devolve a UH ao status de retorno; isReadyForCheckIn considera OOO; OutOfOrderChanged é publicado ao abrir, alterar e liberar; API pública com os períodos por UH e datas e com abertura e liberação sem checar permissão. Não recalcule inventário nem toque em reservas, que são do marco M4.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E03 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de domínio e de integração primeiro, implemente, atualize o CLAUDE.md, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em OOO sem aprovação ou em UH ocupada, períodos sobrepostos e evento ausente na liberação.

Critérios de aceite (detalhes na seção E03):
- OOO sem permissão → 403 APPROVAL_REQUIRED e concluído com X-Approval-Token, auditado com o aprovador;
- OOO em UH ocupada e período sobreposto rejeitados com code estável;
- liberação aplica o status de retorno; OutOfOrderChanged publicado nas três ações;
- contrato regenerado; build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E03 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [x] Abrir OOO sem a permissão devolve `403 APPROVAL_REQUIRED` (com `action`, `permission` e `resourceRef` sem dado pessoal); com `X-Approval-Token` válido é concluído e auditado com o `approval_id`.
- [x] OOO em UH ocupada → erro com `code` estável (RN-07); OOS em UH ocupada é aceito.
- [x] Período sobreposto ao de outro OOO/OOS da mesma UH → erro com `code` estável, também por SQL direto (constraint).
- [x] Liberar o período aplica o status de governança de retorno e grava o histórico.
- [x] `isReadyForCheckIn` devolve falso para UH em OOO.
- [x] `OutOfOrderChanged` é publicado ao abrir, alterar e liberar (teste de módulo).
- [x] Contrato regenerado; build e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- OOO futuro sobre reserva já alocada: o alerta só existe em M4; registrar no handoff que `OutOfOrderChanged` carrega o período inteiro para o consumidor decidir.

**Evidências e handoff:**

- Snapshot inicial: `044c6bbc930be99c4d49d94943d5a004502c8252` (recebido do orquestrador; `HEAD` `1bccb20`, árvore limpa). Snapshot ao fim da implementação, antes deste handoff: `367d720dccf1dce49067e7752e604678607369bd` (27 arquivos, +3021/−73). Preparação: E02 `#concluida`; o código bate com o handoff dela (`Room` com as dimensões e a versão do cadastro, `RoomStatusChanger` com `roomForUpdate` + `refresh`, `CheckInReadiness` sem o OOO, `RoomStatusView.readyForCheckIn`, `CHECK` de origens em `room_status_change`, última migração `V2026_10_15_2130`). Investigação feita pela própria sessão (sem subagentes): RF-UH-04/05, RN-05, RN-07, RF-ACS-08, RF-MAN-03, SPEC §3.1 (Manutenção "solicitar OOO/OOS"), §4 e §8.2.3, o handoff da E05 do M1 e o `ApprovalTest`.
- Arquivos alterados:
  - novos: `rooms/domain/OutOfOrder.kt` (`OutOfOrderDetails`, `OutOfOrderRelease`, entidade `OutOfOrder`); `rooms/application/OutOfOrderPorts.kt` (`OutOfOrderCommand`, `OutOfOrderOverlay`, portas `OutOfOrderStore`/`OutOfOrderRecords`); `rooms/application/OutOfOrderService.kt` (`OutOfOrderOverlays`, `OutOfOrderOperations` e o caso de uso `OutOfOrderService`); `rooms/infrastructure/OutOfOrderPersistence.kt` (JPA e `JdbcClient`); `rooms/web/OutOfOrderController.kt`; migração `db/migration/rooms/V2026_10_15_2236__create_out_of_order.sql`; testes `rooms/OutOfOrderDomainTest.kt` (9) e `rooms/OutOfOrderApiTest.kt` (10);
  - alterados: `rooms/RoomsApi.kt` (`OutOfOrderKind`, `OutOfOrderId`, `OutOfOrderRequest`, `OutOfOrderPeriod`, `RoomStatus.outOfOrder` e os quatro métodos de OOO/OOS), `RoomsEvents.kt` (`OutOfOrderAction`, `OutOfOrderChanged`), `RoomsErrorCodes.kt` (seis códigos `OUT_OF_ORDER_*`), `RoomsPermissions.kt` (três permissões), `domain/Room.kt` (`isReadyForCheckIn(outOfOrder)`, `applyReturnStatus`), `domain/RoomStatusChanges.kt` (`CheckInReadiness` com o OOO), `application/RoomStatusPorts.kt` e `RoomStatusService.kt` (a sobreposição no status e no painel), `RoomsApiService.kt`, `RoomsAudit.kt`, `infrastructure/JdbcRoomStatusRecords.kt` (painel com `LEFT JOIN` do período em vigor), `infrastructure/RoomsPersistence.kt` (`unlessViolated` virou função do pacote e aceita `23P01` além de `23505`), `web/RoomStatusController.kt` (`outOfOrder` na resposta); testes `RoomsApiTest.kt` (caminhos e permissões), `RoomStatusDomainTest.kt` (`isReadyForCheckIn(null)`) e `iam/AuthorizationTest.kt` (Gerente com as três permissões novas); `api/openapi.yaml` regenerado (+468/−3; as 3 linhas retiradas são descrições do painel e do `readyForCheckIn`); `CLAUDE.md` ("Estado atual", cabeçalho de "UHs e áreas", RN-05, a subseção nova "OOO e OOS" e os testes); este roadmap.
- Decisões (adotadas como a opção mais conservadora; as marcadas com ⚑ vão para a entrevista do orquestrador):
  1. ⚑ **Liberação e dias bloqueados:** liberar grava a data operacional (`releasedOn`); o período bloqueia até a véspera dela (`blockedUntil`). O status de retorno só se aplica se o período está em vigor na data operacional (`RETURNED`); liberar um período que ainda não começou o cancela (`CANCELLED`, não bloqueia nada) e um que já terminou só o encerra (`ENDED`), sem mexer no status (a UH pode ter sido limpa ou ocupada depois). Liberar de novo não faz nada (sem trilha de entidade nem evento). Alternativa: aplicar o retorno sempre.
  2. ⚑ **Sem liberação automática:** um período não liberado deixa de bloquear depois do `endsOn`, mas o status de retorno não é aplicado sozinho (não há listener de `BusinessDateAdvanced` no `rooms`). Alternativa: um listener que libere os vencidos (o roadmap não pede; ficaria para o M6, junto do fechamento).
  3. ⚑ **RN-07 no "período atual":** OOO é recusado em UH ocupada só se o período inclui a data operacional (`422 OUT_OF_ORDER_ROOM_OCCUPIED`); um OOO que começa depois de hoje é aceito numa UH ocupada hoje (a reserva no período é do M4, que escuta `OutOfOrderChanged`). Uma edição que faz um OOO passar a incluir hoje numa UH ocupada também é recusada. `markOccupied` não recusa UH em OOO (quem faz o check-in confere `isReadyForCheckIn` antes, como no E02).
  4. ⚑ **Datas:** o período não começa antes da data operacional (sem OOO retroativo, `422 OUT_OF_ORDER_PERIOD_IN_PAST`), tem até 365 dias (`400 OUT_OF_ORDER_PERIOD_INVALID`, repetido em `CHECK`), e a edição não leva um início ou fim **alterado** para antes de hoje (encurtar um período em vigor para antes de hoje é liberar). Alternativas: aceitar início retroativo; sem teto de duração.
  5. ⚑ **Responsável como texto** (até 120 caracteres: pessoa ou empresa terceirizada), além de quem abriu e quem liberou (`UserId`, do `CurrentActor`). Alternativas: `UserId` (o `rooms` não pode validar sem depender do `iam`) ou id de colaborador (`staff`) opaco e não validado.
  6. ⚑ **Permissões:** `rooms.outoforder.read` (Gerente, Recepcionista, Governanta, Manutenção), `rooms.outoforder.manage` (**sensível**, Gerente) e `rooms.outofservice.manage` (Gerente, Manutenção; não sensível). Abrir, alterar **e liberar** um OOO usam a mesma permissão sensível, com `requireOrApproval` (ações `rooms.out-of-order.open` com recurso `rooms.room:<id da UH>`, `.update` e `.release` com `rooms.out-of-order:<id>`). Alternativas: liberar OOO sem step-up (outra permissão, não sensível); a Governanta abrindo OOS.
  7. ⚑ **Tipo imutável na edição:** para trocar OOS ↔ OOO, libera-se e abre-se outro (evita uma edição de OOS que vira OOO sem passar pelo step-up da abertura).
  8. **Status de retorno:** `returnStatus` é `DIRTY` (padrão, RF-MAN-03), `CLEAN` ou `INSPECTED`, aplicado direto e sem inspeção pendente (`Room.applyReturnStatus`), a única mudança de governança fora da SPEC §8.4, com histórico (origem `MANUAL` pela API; a `RoomStatusSource` informada pela API pública) e `RoomStatusChanged`. Abrir um período não mexe no status de governança.
  9. **Sobreposição:** vale entre OOO e OOS da mesma UH (qualquer tipo), pelos dias bloqueados de fato. A aplicação confere depois de travar a UH; o banco repete com a coluna gerada `blocked_period` e `EXCLUDE USING gist (room_id WITH =, blocked_period WITH &&)`, e a violação (`23P01`, reconhecida pelo nome da restrição) vira o mesmo `409 OUT_OF_ORDER_OVERLAP`, nunca `500`.
  10. **Travas:** toda escrita trava a UH (`roomForUpdate`) e depois o período (`findForUpdate` com `refresh`), sempre nessa ordem; abrir, alterar e liberar se enfileiram com as mudanças de ocupação da mesma UH. O período tem versão própria (`ETag` do período, `If-Match` no `PUT`; a liberação, como as ações do E01, não exige `If-Match`). Abrir um período não muda a versão da UH.
  11. **Ordem das recusas:** formato e período (`400`) → UH ou período inexistente (`404`) → autorização (`403`, inclusive `APPROVAL_REQUIRED`) → versão (`412`) → sobreposição (`409`) → regras do período (`422`). Uma aprovação concedida para uma ação que depois cai numa regra não é consumida (a transação desfaz), e vale até vencer.
  12. **A aprovação vincula ação e registro, não as datas** (decisão 4 da E05 do M1): a aprovação de "abrir OOO na UH X" serve, por 2 min e uma vez, para qualquer período nessa UH.
  13. **Consulta:** `GET /api/v1/out-of-order` e `RoomsApi.outOfOrderPeriods` listam os períodos pelos dias que **bloqueiam**; um período cancelado (liberado antes de começar) não aparece nas listas, só por id e na trilha. Alternativa: um filtro para incluir liberados.
  14. **Evento** mínimo (RN-13): ids em UUID, tipo, ação, período planejado, `releasedOn`, `blockedUntil`, o período anterior numa alteração, versão e instante; sem motivo, responsável nem autor. Só publicado quando algo mudou.
  15. Pela API pública não há edição (o roadmap pede abrir e liberar); a manutenção do M6 que precisar alterar um período pede uma operação nova.
- Validações executadas (OpenJDK 25, Docker sem sudo; todos os comandos longos com `timeout`):
  - Testes escritos antes da implementação; como dependem dos tipos novos da API pública, não compilavam sem ela, então não houve uma rodada "falhando" de verdade (como na E05 do M1). A primeira execução, já com a implementação, passou: `./gradlew test --tests 'com.zeny.hotel.rooms.*'` → `OutOfOrderApiTest` 9/9, `OutOfOrderDomainTest` 9/9, `RoomStatusApiTest` 11/11, `RoomStatusDomainTest` 12/12, `RoomsApiTest` 11/11. Depois entrou o teste de corrida (10/10 no `OutOfOrderApiTest`).
  - Mutações temporárias (cada uma revertida em seguida; `OutOfOrder*` rodados a cada uma), todas pegas: OOO com `require` no lugar de `requireOrApproval` → 2 falhas; OOO aceito em UH ocupada → 2; sem `OutOfOrderChanged` na liberação → 1; liberação sem status de retorno → 1; RN-05 ignorando o OOO → 2; sobreposição conferida só pelo banco → 2 falhas no domínio e nenhuma na API (o banco recusa e a aplicação devolve o mesmo `409`, o que prova a conversão da restrição).
  - `./gradlew ktlintFormat` e detekt: ajustes (`ThrowsCount` com `flushOrRefuseOverlap`, `ComplexCondition` com `movesIntoPast`, `LongMethod` suprimido num teste, linhas longas).
  - `./gradlew updateOpenApi` → `api/openapi.yaml` +468/−3 (quatro caminhos novos: `/api/v1/out-of-order`, `/rooms/{id}/out-of-order`, `/rooms/{id}/out-of-order/{periodId}` e `/release`; `outOfOrder` no status; 3 linhas de descrição trocadas).
  - `./gradlew build` → `BUILD SUCCESSFUL in 3m 40s`: 467 testes em 60 classes, 0 falhas, 0 ignorados; `detekt*`, `ktlint*Check`, `checkOpenApi` e `koverVerify` passaram.
  - `.github/scripts/check-api-breaking.sh` → "No breaking changes to report… Nenhuma mudança incompatível (nível ERR) em /api/v1." contra a base `346af178969a` (merge-base com `origin/main`) e contra `HEAD` (`1bccb20886f4`).
- Critérios de aceite (evidência para o verificador):
  - step-up: `OutOfOrderApiTest` › `an OOO without the permission asks for approval, and the approved request is audited with the approver` (`403 APPROVAL_REQUIRED` com `action` `rooms.out-of-order.open`, `permission` `rooms.outoforder.manage`, `resourceRef` `rooms.room:<uuid>`, sem período gravado; aprovação com PIN; 201 com o token; token reutilizado `APPROVAL_TOKEN_USED`; entradas `rooms.out-of-order.open` com `approval_id`, autor e sessão do solicitante, e a entrada `iam.approval` com `approverId`); `whoever holds the permission opens an OOO without approval, and OOS has its own permission`; a edição de OOO também pede aprovação (`a period is edited with If-Match…`);
  - RN-07: `an OOO is refused in an occupied room in the current period, an OOS is accepted` (API e API pública, `422 OUT_OF_ORDER_ROOM_OCCUPIED`; OOS 201; OOO futuro 201) e o teste de domínio equivalente;
  - sobreposição: `overlapping periods of the same room are refused, also by the database` (API, edição, API pública, `INSERT` direto como o papel da aplicação recusado por `out_of_order_overlap_ex`; período liberado deixa de bloquear) e `two overlapping periods opened at the same time - one is opened, the other gets 409`;
  - liberação: `releasing a period in effect applies the return status, records it and makes the room ready again` (CLEAN → INSPECTED no histórico, origem `MANUAL`, autor = quem liberou; segunda liberação sem efeito) e `an OOS keeps the room ready, and releasing a period not yet started leaves the status alone`;
  - RN-05: o mesmo teste da liberação (`isReadyForCheckIn` falso em OOO, `RoomStatus.outOfOrder`, painel com `outOfOrder`) e `a room in OOO is never ready for check-in, a room in OOS may be (RN-05)`;
  - evento: `OutOfOrderChanged is published on open, update and release with the whole period` (`Scenario` na abertura; `UPDATED` com o período anterior e `RELEASED` com `releasedOn` e `blockedUntil` pelo listener de teste; nenhum evento na segunda liberação; as chaves exatas do JSON no outbox, sem motivo nem responsável);
  - contrato, build e oasdiff: acima.
- Entrevista com o usuário (2026-10-15, orquestrador). O usuário confirmou as oito decisões, todas como já estavam implementadas, sem mudança de código:
  1. A liberação só aplica o status de retorno com o período em vigor; antes do início cancela, depois do fim só encerra.
  2. Não há liberação automática dos vencidos: o período deixa de bloquear depois do fim, e um listener de `BusinessDateAdvanced` fica para o M6.
  3. RN-07: um OOO numa UH ocupada só é recusado quando cobre a data operacional; o conflito com reservas futuras fica com o M4.
  4. Datas: sem início retroativo e com teto de 365 dias.
  5. Responsável é texto livre de até 120 caracteres.
  6. Liberar um OOO também é sensível. Modelos como implementados: a leitura vai para Gerente, Recepcionista, Governanta e Manutenção; OOO só para o Gerente; OOS para Gerente e Manutenção.
  7. O tipo não muda na edição.
  8. A listagem traz só os períodos que bloqueiam dias.
- Parecer do verificador (2026-10-15, verificador independente, snapshot `701de33842fbb69cd8a614e02c0be4f4a685b7c4`): **APROVADO**. Os sete critérios foram atendidos:
  - step-up: `requireOrApproval` para OOO, `403 APPROVAL_REQUIRED` só com ids e auditoria com `approval_id`, conferidos por `OutOfOrderApiTest`;
  - RN-07: `422 OUT_OF_ORDER_ROOM_OCCUPIED`, com a UH travada antes de conferir;
  - sobreposição: `409 OUT_OF_ORDER_OVERLAP`, também por SQL direto, que esbarra em `out_of_order_overlap_ex`, e numa corrida;
  - liberação: aplica o status de retorno, com histórico;
  - RN-05: UH em OOO não fica pronta para check-in;
  - `OutOfOrderChanged` sai nas três ações pelo outbox, com 12 chaves e nenhum dado pessoal;
  - `./gradlew build` passou com 467 testes e 0 falhas, e os dois oasdiff (contra `346af178969a` e contra `1bccb20886f4`) não acusaram mudança incompatível.

  Uma mutação numa cópia descartável tirou as checagens de sobreposição da aplicação, e a API continuou devolvendo 409 pela restrição do banco, nunca 500. Nenhum problema grave nem médio. Ressalvas menores:
  1. Sem mutação, nenhum teste passa pela conversão de `23P01` em 409, porque a checagem da aplicação sempre chega antes; vale um teste que provoque a recusa só pelo banco.
  2. A recusa da RN-07 ao editar um OOO para cobrir hoje só tem teste de domínio.
  3. O índice `out_of_order_period_idx (starts_on, id)` não atende o filtro por `blocked_period`; o impacto é desprezível com 30 UHs.
- Notas para a próxima etapa:
  - E04 (SSE): o tópico `rooms` deve escutar também `OutOfOrderChanged` (abrir e liberar sem mudar a governança não publicam `RoomStatusChanged`, mas mudam a sobreposição e o `readyForCheckIn` do painel). O payload já não tem dado pessoal.
  - E05 (sync): a sobreposição vem da data operacional; o período tem versão própria, separada da UH.
  - M4 (inventário): `RoomsApi.outOfOrderPeriods(from, to)` dá os dias bloqueados (só OOO tira do inventário; OOS fica vendável como última opção); o listener de `OutOfOrderChanged` recalcula a união do período anterior e do novo e alerta as reservas alocadas (RN-07). O evento carrega o período inteiro para o consumidor decidir (risco listado nesta etapa).
  - M6 (manutenção e fechamento): a OS chama `openOutOfOrder`/`releaseOutOfOrder` depois do próprio step-up; não há edição pela API pública nem liberação automática dos períodos vencidos (decisão 2).
  - Ressalva sem ação, herdada: o teste de corrida de números da E01 (`RoomsApiTest`) ainda espera o latch sem prazo; o teste de corrida novo desta etapa usa `await` com prazo e `Future.get` com prazo.

---

## E04 — Tempo real (SSE) — #concluida

**Objetivo:** implementar em `shared` o canal de tempo real da ARCHITECTURE §10: `GET /api/v1/stream?topics=…` por SSE, eventos com payload mínimo filtrados por permissão e escopo do usuário, reconexão com `Last-Event-ID` a partir de um buffer circular e `reset` quando o ID saiu do buffer; e registrar o tópico `rooms`, alimentado pelas mudanças de status.

**Por que agora:** RNF-04 exige mudanças de status em até 2 s; notificações (E09), governança (M6), PDV (M8) e o agente de impressão (M5) usam o mesmo canal.

**Dependências e paralelismo:** E02. Pode correr em paralelo com E03 e E05 (em worktree separado); conflito de edição em `api/openapi.yaml`, `shared/web/SecurityConfiguration.kt`, `CLAUDE.md` e na API pública de `rooms`.

**Escopo:**

- Em `shared` (raiz pública e `shared/infrastructure/` ou `shared/web/`): broadcaster em processo sobre `SseEmitter` do Spring MVC; porta para um módulo declarar um tópico (nome, permissão exigida, filtro por escopo `OWN` quando aplicável) e publicar uma mensagem `type`, `id`, `version` dentro da transação, com envio só após o commit (sincronização da transação, não o outbox: o buffer é em memória). `shared` não escuta eventos de módulos.
- Endpoint `GET /api/v1/stream` autenticado pelo Bearer (H8), com `topics` validados contra os tópicos registrados (desconhecido → `400`) e a permissão de cada um (sem ela → `403 PERMISSION_DENIED`) na inscrição; a conexão não segura conexão de banco nem transação.
- Buffer circular por tópico (H5), `Last-Event-ID`, evento `reset`, *heartbeat* periódico para proxies, encerramento no `exp` do access token e na revogação da sessão ou desativação do usuário (H8: evento `DeviceSessionsRevoked` do kernel, publicado pelo `iam`).
- Tópico `rooms` registrado por `rooms`, com a permissão de leitura de status de E02; as mudanças de status da E02 passam a publicar no canal.
- Proxy: conferir que o Caddy (`docker/caddy/Caddyfile`) entrega o stream sem buffer e sem cortar a conexão longa; ajustar se preciso.
- Teste com cliente SSE real num contexto próprio com `webEnvironment = RANDOM_PORT` (o `@IntegrationTest` é servlet simulado).
- `CLAUDE.md`: como um módulo registra e publica num tópico.

**Fora de escopo:** tópicos `tasks`, `tabs`, `production` (M6, M8) e `notifications` (E09); `LISTEN/NOTIFY` para várias instâncias.

**Passos de implementação:**

1. Escrever testes de integração com cliente SSE real: recebimento, tópico sem permissão, tópico desconhecido, escopo `OWN`, reconexão com `Last-Event-ID`, `reset`, latência, evento não enviado em rollback, encerramento na revogação e no vencimento do token.
2. Implementar broadcaster, porta, endpoint, o evento de revogação no `iam` e o tópico `rooms`.
3. Documentar no `CLAUDE.md` e regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: sim (protocolo entre cliente e servidor que M5, M6 e M8 repetem)

- Investigação (`Explore`): "o que ARCHITECTURE §10 e D10 e RNF-04 exigem do canal SSE?"; "como o `SseEmitter` do Spring MVC 7 (Spring Boot 4.1) trata `Last-Event-ID`, tempo limite, conexão desfeita e o despacho assíncrono na cadeia do Spring Security 7 (`DispatcherType.ASYNC`)?"; "em quais pontos do `iam` (`Sessions.kt`, `UserManagement.kt`, `Authentication.kt`, `TokenRefreshService.kt`) uma sessão é revogada?".
- Partes paralelas: nenhuma.
- Foco do verificador: usuário recebendo evento de tópico sem permissão ou fora do escopo `OWN`; evento enviado antes do commit ou em rollback; conexão de sessão revogada ou de token vencido que continua aberta; vazamento de conexões abertas; payload com dados além de `type`, `id` e `version`; `shared` dependendo de `rooms` ou de `iam`.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E04 — "Tempo real (SSE)" do roadmap docs/roadmaps/roadmap-m2-plataforma-operacional.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Contexto confirmado", "Restrições e invariantes", a hipótese H5, a decisão confirmada H8 e o "Protocolo de execução" —, a seção E04 inteira e o "Evidências e handoff" de E02. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E02 está #concluida e que o código bate com o handoff de E02; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: implementar em shared o canal GET /api/v1/stream?topics=… por SSE (SseEmitter do Spring MVC): porta para módulos registrarem tópicos com permissão e filtro OWN e publicarem mensagens na transação, com envio só após o commit; payload mínimo (type, id, version); buffer circular com Last-Event-ID e reset; heartbeat; inscrição recusada com 400 para tópico desconhecido e 403 sem permissão; conexão encerrada no exp do access token e na revogação da sessão, pelo evento DeviceSessionsRevoked do kernel, publicado pelo iam (decisão H8, confirmada pelo usuário); registrar o tópico rooms alimentado pelas mudanças de status. shared não pode depender de rooms nem de iam. Não implemente outros tópicos.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E04 para #em-andamento no índice e no título;
- investigação: um subagente Explore responde às perguntas da seção E04 (ARCHITECTURE §10, D10 e RNF-04; SseEmitter, Last-Event-ID e despacho assíncrono no Spring Security; pontos de revogação de sessão no iam);
- escreva primeiro os testes com cliente SSE real num contexto RANDOM_PORT, implemente, documente no CLAUDE.md, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh e confira o stream com curl -N pelo proxy do docker compose;
- verificação independente com o Prompt do verificador, com foco em evento sem permissão, envio antes do commit ou em rollback, conexão de sessão revogada aberta, conexões vazando e payload além do mínimo.

Critérios de aceite (detalhes na seção E04):
- cliente inscrito em rooms recebe a mudança em menos de 2 s; sem permissão recebe 403;
- reconexão com Last-Event-ID entrega os eventos perdidos; ID fora do buffer recebe reset;
- evento de transação revertida não é enviado; revogar a sessão encerra a conexão;
- CLAUDE.md documenta o registro de tópicos; contrato regenerado; build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E04 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [x] Um cliente em `GET /api/v1/stream?topics=rooms` recebe a mudança de status de uma UH em menos de 2 s (teste com cliente SSE real).
- [x] Inscrição num tópico sem a permissão → `403 PERMISSION_DENIED`; tópico desconhecido → `400` com `code` estável; com escopo `OWN`, o cliente só recebe eventos dos próprios registros.
- [x] Reconexão com `Last-Event-ID` dentro do buffer entrega os eventos perdidos na ordem; fora do buffer, o cliente recebe `reset`.
- [x] Mudança feita numa transação revertida não gera evento.
- [x] O payload contém só `type`, `id` e `version`.
- [x] Revogar a sessão (ou desativar o usuário) encerra a conexão SSE dela; a conexão também termina no `exp` do access token.
- [x] `CLAUDE.md` documenta como registrar um tópico; `ApplicationModules.verify()` passa sem `shared` depender de módulos; contrato regenerado; build e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.
- Inspeção manual: `curl -N` contra o compose, através do proxy, recebe eventos e *heartbeat* sem corte (projeto isolado com `-p` e `--env-file` no scratchpad; não tocar no `docker/.env` do usuário; `down -v` no fim).

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Proxy com *buffering* atrasando eventos: desligar o buffer (`flush_interval -1`) para `/api/v1/stream` no Caddyfile e registrar no handoff.
- Cada conexão SSE ocupa um slot assíncrono do Tomcat: limitar conexões por sessão e registrar o limite.

**Evidências e handoff:**

- Snapshot inicial: `a7a8575000f322bb5cb8db63736a25f2673992c6` (recebido do orquestrador; `HEAD` `7c9773a`, árvore limpa). Snapshot ao fim da implementação, antes deste handoff: `7d98a6532cbe35d16287eaaa9d52f06e4dfd596b`. Preparação: E02 `#concluida`; o código bate com o handoff dela (`RoomStatusChanger` publica `RoomStatusChanged` com `version`, `RoomsPermissions.STATUS_READ`, `RoomsEvents.kt`), e a E03 também está concluída (`OutOfOrderOperations.publish`).
- Investigação feita pela própria sessão (adendo do orquestrador): ARCHITECTURE §10 e D10 (SSE por tópico, payload `type`/`id`/`version`, filtro por permissão e escopo, `Last-Event-ID` com buffer circular e `reset`, broadcaster em processo, `LISTEN/NOTIFY` só com várias instâncias), RNF-04 (até 2 s); `SseEmitter` com tempo limite explícito (sem ele vale o do contêiner), envio de outra thread, `complete()` disparando o despacho `ASYNC` pela cadeia de filtros (o `AuthorizationFilter` roda em todo tipo de despacho) e o *graceful shutdown* padrão do Boot, que esperaria as conexões longas; revogações de sessão no `iam`: `SessionService.logout`/`revoke`, `UserSessionsService.revoke`, `AuthenticationService.login` (mesmo dispositivo) e `changePassword`, `TokenRefreshService.refresh` (usuário desativado e reuso) e `UserManagementService.revokeAllSessions` (desativação e reset de senha).
- Arquivos alterados:
  - novos: `shared/Realtime.kt` (`RealtimeTopic`, `RealtimeMessage`, porta `RealtimePublisher`); `shared/application/realtime/RealtimeStreams.kt` (`RealtimeProperties`, quadros, `StreamSink`, `StreamSubscription`), `RealtimeHub.kt` (broadcaster, `SmartLifecycle`), `RealtimeHistory.kt` (sequência, época e buffers) e `StreamService.kt` (inscrição); `shared/web/StreamController.kt` (`GET /api/v1/stream`); `shared/infrastructure/realtime/RealtimeMetrics.kt` (`zeny_realtime_connections`); `iam/application/SessionRevocations.kt`; `rooms/RoomsRealtime.kt`; testes `realtime/RealtimeStreamTest.kt` (13), `iam/SessionRevocationEventsTest.kt` (5), `testsupport/SseTestClient.kt` e `src/test/kotlin/realtimeexample/RealtimeExample.kt` (tópico só de teste com `OWN`);
  - alterados: `shared/Actors.kt` (porta `DeviceSessionStatus`, evento `DeviceSessionsRevoked`), `DomainErrors.kt` (`UNKNOWN_STREAM_TOPIC`), `SharedModule.kt` (KDoc), `shared/web/SecurityConfiguration.kt` (libera o despacho `ASYNC`); `iam/application/Sessions.kt`, `Authentication.kt`, `TokenRefreshService.kt`, `UserManagement.kt` (revogação só pela `SessionRevocations`), `iam/infrastructure/Persistence.kt` (`JdbcDeviceSessionStatus`); `rooms/application/RoomStatusService.kt` e `OutOfOrderService.kt` (publicam no tópico), `RoomsRegistry.kt` (bean do tópico); `application.yaml` (`zeny.realtime.*`); `docker/caddy/Caddyfile` (`/api/v1/stream` com `flush_interval -1`); `api/openapi.yaml` (+45 linhas, só acréscimos: `GET /api/v1/stream` e a tag `stream`); `CLAUDE.md` ("Estado atual", nova subseção "Tempo real", ator corrente, autenticação no `iam`, `rooms`, compose e métricas); este roadmap.
- Decisões (adotadas como a opção mais conservadora pelo implementador; **confirmadas pelo usuário em 2026-10-15**, numa entrevista do orquestrador: 1, 2, 4 e 5 com a opção recomendada; a 3 é a própria H8, já confirmada):
  1. Protocolo: cada mensagem é um evento SSE com `event: <tópico>`, `id: <época>-<sequência>` (sequência global crescente entre os tópicos; época de 12 dígitos hexadecimais sorteada a cada boot) e `data` JSON com só `type`, `id` e `version`. Toda conexão começa por `event: ready` (sem `Last-Event-ID`, ou depois da reposição) ou `event: reset` (id de outro boot, ilegível, à frente da posição ou anterior a uma mensagem que já saiu do buffer de algum tópico inscrito), ambos com `data: {}` e a posição corrente no `id`, para o cliente sempre ter de onde reconectar. *Heartbeat* como comentário `: heartbeat`.
  2. Limite de 3 conexões por sessão (`zeny.realtime.max-connections-per-session`): a nova encerra a mais antiga (o cliente que reconecta depois de perder a rede não espera a velha morrer), em vez de recusar a nova. Sem limite global.
  3. `DeviceSessionsRevoked(userId, sessionIds)` (UUIDs) é publicado pelo `iam` com `ApplicationEventPublisher` e escutado pelo hub com `@EventListener` comum, que registra a sincronização da transação e encerra as conexões no `afterCommit`: **sem outbox** (o efeito é só em memória; não entra em `shared.event_publication` nem no reenvio). Toda revogação passa pela `SessionRevocations` (um evento por chamada que revoga algo; sessão já revogada não entra), e o `SessionRevocationEventsTest` barra com ArchUnit outro chamador de `DeviceSession.revoke`.
  4. Além do H8: a inscrição consulta a porta `DeviceSessionStatus` (implementada pelo `iam`) e recusa sessão revogada com `401 UNAUTHENTICATED`, porque o access token dela ainda vale até 15 min e o cliente reconectaria na hora. Um conjunto em memória das sessões revogadas nos últimos 10 min fecha a corrida entre a consulta e o registro da conexão.
  5. O tópico `rooms` carrega também `rooms.out-of-order` (`id` = o período, `version` = a versão dele) ao abrir, alterar e liberar OOO/OOS, porque a sobreposição e a prontidão do painel mudam sem mudar a versão da UH; `rooms.room-status` leva `id` = a UH e `version` = a versão dela (o `ETag` do status). Mudanças de cadastro e observações da governança não vão ao canal.
  6. Publicar fora de uma transação de escrita falha (`IllegalStateException`), como a trilha de auditoria; tópico não registrado, `IllegalArgumentException`. O boot recusa tópico repetido ou com permissão que nenhum `ModulePermissions` declara.
  7. Ordem das recusas da inscrição: sem tópico `400 VALIDATION_FAILED` (campo `topics`), tópico desconhecido `400 UNKNOWN_STREAM_TOPIC` (código novo no catálogo comum `ErrorCodes`), sessão revogada `401`, sem permissão `403 PERMISSION_DENIED`. O problema sai em `application/problem+json` mesmo com `Accept: text/event-stream` (conferido no teste).
  8. Envio por conexão numa fila própria esvaziada por *virtual thread*: quem publica (no commit de uma requisição) nunca espera um cliente lento; fila acima de `buffer-size × tópicos + 64` quadros encerra a conexão (o cliente volta com o `Last-Event-ID`). O encerramento (`complete()`) também roda numa *virtual thread*.
  9. O hub é um `SmartLifecycle` (fase padrão, para antes do *graceful shutdown* do Tomcat): na parada encerra as conexões, sem esperar os 30 s do *graceful shutdown*; no `start` recria os executores, porque o cache de contextos dos testes do Spring 7 pausa e retoma contextos (a primeira versão quebrou 17 classes de teste por isso).
  10. A segurança passa a liberar o despacho `DispatcherType.ASYNC` (o fim da resposta assíncrona, quando o servidor encerra o stream), como já liberava o `ERROR`: a requisição original já passou pela autorização, e o token pode ter vencido desde então.
  11. Tempo limite do `SseEmitter` = até o `exp` do token + 30 s (o canal encerra no `exp`, por um agendamento por conexão; o *heartbeat* também fecha a que passou do `exp`). Padrões: buffer de 1000 mensagens por tópico, *heartbeat* de 15 s.
  12. O Caddy já não bufferiza `text/event-stream`; a rota `/api/v1/stream` ganhou `flush_interval -1` explícito, sem tempo limite para a conexão longa.
- Validações executadas (OpenJDK 25, Docker 29.1.3):
  - Testes escritos antes da implementação (não compilavam sem a API pública). Primeira execução de `RealtimeStreamTest` + `SessionRevocationEventsTest`: os 13 do canal passaram; os 5 do `iam` falharam por injetar `ApplicationEvents` no construtor (corrigido para campo).
  - Prova de que os testes pegam a regressão (mutação temporária no `RealtimeHub`, revertida): despachar sem esperar o commit, aceitar `OWN` para qualquer dono, tirar o `@EventListener` da revogação e o conjunto de sessões revogadas → 4 falhas (`with the OWN scope…`, `nothing is sent before the commit…`, `revoking a session closes…`, `deactivating the user closes…`). Depois disso, o teste de revogação ganhou o caso da sessão revogada por SQL direto (sem evento), que só a porta `DeviceSessionStatus` recusa.
  - `./gradlew ktlintFormat` e `./gradlew lint` → ajustes (`TooManyFunctions` no hub: extraído o `RealtimeHistory` e suprimido com justificativa; `ReturnCount`, complexidade do cliente de teste, `ImplicitDefaultLocale`, `LongParameterList` e `VarCouldBeVal` nos testes) → sem problemas.
  - `./gradlew updateOpenApi` → `api/openapi.yaml` +45/−0.
  - Primeiro `./gradlew build` → 17 classes de teste com `initializationError` (`Failed to start bean 'realtimeHub'`: `RejectedExecutionException` ao retomar um contexto pausado pelo cache); corrigido (decisão 9). Segundo `./gradlew build` → `BUILD SUCCESSFUL in 3m 22s`: 485 testes, 0 falhas, 0 ignorados (`RealtimeStreamTest` 13/13 em 15 s, `SessionRevocationEventsTest` 5/5); `checkOpenApi`, `detekt*`, `ktlint*Check` e `koverVerify` passaram; `ModularityTest` passou sem `shared` depender de módulo.
  - `.github/scripts/check-api-breaking.sh` → "No breaking changes to report… Nenhuma mudança incompatível (nível ERR) em /api/v1." contra `7c9773a66675` (merge-base com `origin/main`), antes e depois da última correção.
  - Lacuna apontada pelo verificador (reposição sem o filtro de escopo passava): novo teste `an OWN subscriber reconnecting with Last-Event-ID gets only its own missed messages, in order` no `RealtimeStreamTest`. Com a mutação `missed?.filter(connection::receives)?.map` → `missed?.map` (`RealtimeHub.kt:208`), só ele falha (14 testes, 1 falha); revertida, passa. `./gradlew build` em seguida → `BUILD SUCCESSFUL in 3m 46s`, 486 testes, 0 falhas.
  - Inspeção manual pelo proxy do compose (projeto isolado `-p zeny-e04`, `--env-file` no scratchpad com segredos sorteados, porta 8089; `down -v` no fim e o arquivo de variáveis apagado): `curl -N` em `http://localhost:8089/api/v1/stream?topics=rooms` recebeu `ready`, a mudança de governança (`rooms.room-status`, versão 1 e depois 2) cerca de 30 ms depois do `POST` e dois `: heartbeat` em 30 s sem corte; `Last-Event-ID` do `ready` repôs as duas mensagens e o `ready`; `Last-Event-ID: outroboot-5` recebeu `reset`; tópico desconhecido `400 application/problem+json`; o logout encerrou as duas conexões abertas e a reconexão com o mesmo token recebeu `401`; nenhum WARN/ERROR no log da `api`. Efeito colateral: o `up --build` regravou a tag local `zeny-hotel-manager:dev` com esta árvore.
- Critérios de aceite (evidência para o verificador):
  - menos de 2 s — `a subscriber of rooms receives a status change in under 2 seconds…` (mede o tempo e confere as chaves exatas do JSON);
  - `403`/`400`/`OWN` — `subscribing needs a token, known topics and the permission of each one`, `with the OWN scope the subscriber only receives…`;
  - `Last-Event-ID` e `reset` — `a reconnection with Last-Event-ID replays the missed events in order` e `a Last-Event-ID that left the buffer, from another boot or malformed receives a reset`;
  - rollback e antes do commit — `nothing is sent before the commit, and a rolled back transaction sends nothing` (inclusive a mudança de status da UH numa transação revertida);
  - payload — o primeiro e `the rooms topic also carries out-of-order periods…` (só `type`, `id`, `version`);
  - revogação, desativação e `exp` — `revoking a session closes its connections…`, `deactivating the user closes all of its connections`, `the connection ends when the access token expires`, e o `SessionRevocationEventsTest` para cada ponto de revogação do `iam`;
  - vazamento — `connections closed by the client are released` (métrica volta a zero) e `a session keeps at most the configured connections, closing the oldest`;
  - documentação e módulos — `CLAUDE.md` (subseção "Tempo real") e `ModularityTest`.
- Parecer do verificador: **APROVADO** (verificador independente, 2026-10-15; snapshot verificado `6223194d245190e44180716528d2fd9d6dca6500`). Os 7 critérios aprovados com evidência: `RealtimeStreamTest` 13/13, `SessionRevocationEventsTest` 5/5, `ModularityTest` 9/9, `./gradlew build` com 485 testes e 0 falhas, oasdiff sem `ERR` (base `7c9773a66675`) e inspeção pelo proxy num compose isolado (`zeny-e04v`, `down -v`): evento cerca de 60 ms depois da mudança, *heartbeat* a cada 15 s, reposição e `reset`, 400/401, logout fechando o stream, métrica de 3 para 0 conexões. Nenhum problema grave.
  - Médio, **M1**: a reposição por `Last-Event-ID` com escopo `OWN` não tem teste. A mutação que tira o filtro em `RealtimeHub.kt:208` (`missed?.filter(connection::receives)`) passou no `RealtimeStreamTest`. O código está certo, mas falta um caso de reconexão de assinante `OWN`; corrija antes do commit.
  - Baixos:
    - **B1**: nenhum teste prova que uma revogação desfeita não fecha as conexões; está correto por inspeção.
    - **B2**: o caminho `USER_DISABLED` do `TokenRefreshService.kt:53` não tem teste de evento próprio; só a regra ArchUnit o cobre.
    - **B3**: `dispatcherTypeMatchers(ASYNC).permitAll()` vale para todas as rotas, não só para o stream.
    - **B4**: o limite é só por sessão, sem limite por usuário nem global.
    - **B5**: o tópico `rooms` entrega id e versão dos períodos OOO/OOS a quem tem `rooms.status.read` sem `rooms.outoforder.read`; aceitável, porque o painel já mostra isso ao mesmo público.
  - As decisões 1 a 5 foram confirmadas pelo usuário em 2026-10-15 (protocolo `ready`/`reset` e formato do id, 3 conexões por sessão encerrando a mais antiga, 401 na inscrição de sessão revogada, `rooms.out-of-order` no tópico `rooms`).
- Notas para a próxima etapa:
  - E09 (notificações): registrar o tópico `notifications` com uma permissão de leitura com `OWN` e publicar com `owners = setOf(destinatário)`; o `DeviceSessionsRevoked` também serve para não mandar push a sessão revogada (escutar com `@ApplicationModuleListener` passaria a gravar no outbox: o evento já usa UUIDs por isso).
  - M5/M6/M8 (`tasks`, `tabs`, `production`, agente de impressão): mesmo padrão do `rooms` (`RoomsRealtime` + bean na `@Configuration` das permissões + `publish` na transação, só quando algo mudou).
  - Permissão retirada só vale na reconexão (até o `exp`, 15 min), como decidido no H8.
  - Com mais de uma instância, o broadcaster precisa de `LISTEN/NOTIFY` (ARCHITECTURE §10); hoje o reinício responde `reset` a todos.

## E05 — Sincronização offline — #concluida

**Objetivo:** implementar em `shared` o protocolo de sincronização da ARCHITECTURE §9: download incremental `GET /api/v1/sync/changes?cursor=…` por `change_seq` com *tombstones*, upload `POST /api/v1/sync/commands` com lote ordenado de comandos idempotentes por `commandId`, correção da defasagem de relógio do dispositivo e portas para cada módulo registrar fontes de mudança e handlers de comando; e registrar `rooms` como primeira fonte (número e status, sem dados pessoais).

**Por que agora:** RNF-05 exige o app operacional offline por um turno. Checklists (E07), governança e manutenção (M6) e rouparia (M9) registram seus handlers e fontes sobre este protocolo.

**Dependências e paralelismo:** E02. Pode correr em paralelo com E03 e E04 (em worktree separado); conflito de edição em `api/openapi.yaml`, nas migrações de `shared`, `CLAUDE.md` e na API pública de `rooms`.

**Escopo:**

- Em `shared`: *sequence* global `change_seq` e tabela de *tombstones* (H6), numa migração nova em `src/main/resources/db/migration/shared/`; porta `OfflineChangeSource` (o que o usuário pode ver no escopo offline, mudado desde o cursor) e porta `OfflineCommandHandler` (tipo de comando, handler); registro por bean, sem `shared` conhecer os módulos.
- `GET /api/v1/sync/changes`: devolve mudanças por fonte, *tombstones* e o novo cursor, paginado; respeita permissões e escopo pela porta `Authorization`; RN-13.
- `POST /api/v1/sync/commands`: lote ordenado `{commandId (UUIDv7), type, payload, occurredAt, deviceClock}`; processa em ordem, cada comando na sua transação, com o resultado gravado na mesma transação do efeito; tabela própria de idempotência por `commandId` em `shared` (não a `shared.idempotency_key`), ligada ao usuário (o mesmo `commandId` de outro usuário é recusado); resultado por comando (aplicado, rejeitado com motivo, aplicado com alerta); repetição devolve o resultado anterior. O endpoint não é `@Idempotent` (a idempotência é por comando).
- Relógio: defasagem calculada no início de cada sincronização a partir do `deviceClock` e do `Clock` do servidor; `occurredAt` corrigido dentro de uma tolerância configurada (`zeny.sync.*`); acima dela, aplicado com alerta.
- `rooms` registra uma fonte (UHs ativas: ID, número, andar, status e sinalização) e grava `change_seq` nas mudanças.
- Comando de exemplo em código de teste (pacote fora de `com.zeny.hotel`, como `src/test/kotlin/apiexample/`) para validar o pipeline.
- `CLAUDE.md`: como um módulo registra fonte de mudanças e handler de comando.

**Fora de escopo:** handlers de checklists (E07), governança e manutenção (M6) e enxoval (M9); armazenamento local no app (repositório do cliente); upload de fotos (E11).

**Passos de implementação:**

1. Escrever testes de integração: download inicial e incremental com *tombstone*, ausência de dados pessoais, lote com comando repetido, comando rejeitado sem interromper o lote, ordem de processamento, correção de relógio, `commandId` de outro usuário e cursor com permissões diferentes, e transações concorrentes durante o download.
2. Implementar migrações, portas, endpoints e a fonte de `rooms`.
3. Documentar no `CLAUDE.md` e regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "o que ARCHITECTURE §9, §6.2 e D11, RNF-05, RN-13 e RN-22 exigem da sincronização?"; "como a idempotência HTTP (`shared/web/IdempotencyFilter.kt`, `shared/infrastructure/idempotency/JdbcIdempotencyRecords.kt`) reserva a chave de forma atômica, para reaproveitar a técnica na tabela de `commandId`?".
- Partes paralelas: nenhuma.
- Entrevista: H6 continua aberta; a etapa a decide com o usuário depois da investigação e antes de implementar.
- Foco do verificador: comando aplicado duas vezes (inclusive em requisições simultâneas); mudança perdida entre duas páginas do download (cursor não monotônico sob transações concorrentes); dados pessoais no download; `shared` dependendo de `rooms`; handler sem `@Audited`.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E05 — "Sincronização offline" do roadmap docs/roadmaps/roadmap-m2-plataforma-operacional.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Restrições e invariantes", a hipótese H6 e o "Protocolo de execução" —, a seção E05 inteira e o "Evidências e handoff" de E02. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E02 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

A hipótese H6 (change_seq gravado pela aplicação ou por trigger, e a regra que impede o cursor de passar de transações abertas) continua aberta e é decidida na entrevista desta etapa: depois da investigação e antes de implementar, apresente as opções ao usuário com AskUserQuestion, como manda o CLAUDE.md (se você for um subagente, devolva a pergunta ao orquestrador e pare), e registre a decisão na tabela de hipóteses.

Objetivo: implementar em shared o protocolo offline: GET /api/v1/sync/changes por cursor change_seq com tombstones, filtrado por permissão e sem dados pessoais (RN-13); POST /api/v1/sync/commands com lote ordenado, cada comando na sua transação, idempotência por commandId numa tabela própria (não a shared.idempotency_key) ligada ao usuário, resultado por comando (aplicado, rejeitado com motivo, aplicado com alerta) e correção da defasagem de relógio dentro de tolerância; portas OfflineChangeSource e OfflineCommandHandler registradas pelos módulos; rooms como primeira fonte (ID, número, andar, status, sinalização). shared não pode depender de módulos. Não implemente handlers de checklists, governança nem enxoval.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E05 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume docs/ARCHITECTURE.md §9, §6.2 e D11, RNF-05, RN-13 e RN-22 e como o IdempotencyFilter e o JdbcIdempotencyRecords reservam a chave de forma atômica;
- escreva os testes de integração primeiro, inclusive o de transações concorrentes durante o download; implemente, documente no CLAUDE.md, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em comando aplicado duas vezes, mudança perdida entre páginas, dados pessoais no download e shared dependendo de rooms.

Critérios de aceite (detalhes na seção E05):
- download inicial e incremental com tombstones; nenhuma mudança perdida com transações concorrentes;
- comando repetido devolve o resultado anterior sem reaplicar; rejeição não interrompe o lote;
- occurredAt corrigido pela defasagem; acima da tolerância, aplicado com alerta;
- CLAUDE.md documenta fontes e handlers; contrato regenerado; build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E05 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Download sem cursor devolve o estado completo visível ao usuário; com cursor devolve só o que mudou, incluindo *tombstones* de exclusões.
- [ ] Um teste com transações concorrentes demonstra que nenhuma mudança commitada fica para trás do cursor devolvido.
- [ ] A resposta do download não contém nome, documento nem contato de hóspede (RN-13).
- [ ] Um lote com o mesmo `commandId` repetido (no mesmo lote, em lotes seguidos e em requisições simultâneas) aplica o comando uma vez e devolve o mesmo resultado nas repetições; o mesmo `commandId` de outro usuário é recusado.
- [ ] Um comando rejeitado devolve motivo com `code` estável e não impede os seguintes do lote.
- [ ] `occurredAt` é corrigido pela defasagem calculada; acima da tolerância, o resultado é "aplicado com alerta".
- [ ] `CLAUDE.md` documenta como registrar fonte e handler; `ApplicationModules.verify()` passa sem `shared` depender de módulos.
- [ ] Contrato regenerado; build e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Sequências do PostgreSQL não respeitam a ordem de commit: limitar o cursor ao menor `change_seq` de transações ainda abertas (por exemplo, guardando o `pg_current_xact_id()` e comparando com `pg_snapshot_xmin`) ou técnica equivalente, e registrar a escolha.

**Evidências e handoff:**

- Snapshot inicial: `fc4efc010f1499b98e7751ed617268962eac411e` (recebido do orquestrador; `HEAD` `e12cc91`, árvore limpa). Snapshot ao fim da implementação, antes deste handoff: `7576d44edb1fac3196eed1468fd880eadb58a640`. Preparação: E02 `#concluida`; o código bate com o handoff dela (`RoomsApi` com `status`/`mark*`/`setServiceFlag`/`isReadyForCheckIn`, migração `V2026_10_15_2130__add_room_status.sql`, `RoomStatusDomainTest`/`RoomStatusApiTest`).
- Investigação feita pela própria sessão (adendo do orquestrador): ARCHITECTURE §9, §6.2 e D11, SPEC RNF-05, RN-13 e RN-22, `JdbcIdempotencyRecords`/`IdempotencyService` (reserva atômica por `INSERT … ON CONFLICT DO NOTHING` sobre `UNIQUE (scope, idempotency_key)`, em `REQUIRES_NEW`) e `MigrationConventionTest` (um trigger de módulo não pode `INSERT INTO shared.*`). Conferido num `postgres:18`: `pg_current_xact_id()` (`xid8`), `pg_snapshot_xmin(pg_current_snapshot())`, índice btree em `(xid8, bigint)` e comparação de linha.
- Arquivos alterados:
  - novos em `shared`: `src/main/kotlin/com/zeny/hotel/shared/OfflineSync.kt` (API pública: `OfflineSource`, `OfflinePosition`, `OfflineChangeRequest` com `window`/`positionColumns`/`sqlParams`, `OfflineChange`, as portas `OfflineChangeSource`, `OfflineTombstones` e `OfflineCommandHandler`, `OfflineCommandType`, `OfflineCommand`, `OfflineCommandOutcome`/`OfflineCommandWarning` e `OfflineSyncCodes`); `shared/application/sync/OfflineChanges.kt` (`OfflineChangesService`, porta `OfflineSyncStore`, `SyncCursor`); `shared/application/sync/OfflineCommands.kt` (`OfflineCommandService`, `OfflineSyncProperties` `zeny.sync.*`, porta `OfflineCommandRecords`); `shared/infrastructure/sync/JdbcOfflineSync.kt` (horizonte, *tombstones*, resultados, `OfflineCommandPurgeJob`, bean `AppendOnlyTables` de `shared.sync_tombstone`); `shared/web/SyncController.kt` (`GET /api/v1/sync/changes`, `POST /api/v1/sync/commands` e DTOs); migração `src/main/resources/db/migration/shared/V2026_10_16_0110__create_offline_sync.sql` (sequence `shared.change_seq`, função `shared.mark_offline_change()`, `shared.sync_tombstone` *append-only* com o trigger, `shared.offline_command`);
  - novos em `rooms`: `rooms/RoomsOffline.kt` (`SOURCE`), `rooms/infrastructure/RoomsOfflineSource.kt` e a migração `rooms/V2026_10_16_0111__add_room_offline_change.sql` (colunas, trigger `room_offline_change`, preenchimento das UHs existentes, índice);
  - alterados: `rooms/RoomsPermissions.kt` (`rooms.offline.read`), `src/main/resources/application.yaml` (`zeny.sync.*`), `api/openapi.yaml` (regenerado, +251/−0, só acréscimos: `/api/v1/sync/changes` e `/api/v1/sync/commands`), `CLAUDE.md` ("Estado atual", nova subseção "Sincronização offline", fonte do `rooms`, jobs e infraestrutura do `shared`), `docs/ARCHITECTURE.md` (§9: "Cursor sem perda") e este roadmap (H6 e handoff);
  - testes: novos `src/test/kotlin/com/zeny/hotel/sync/OfflineSyncTest.kt` (19) e `OfflineSyncCatalogTest.kt` (3), o módulo só de teste `src/test/kotlin/syncexample/SyncExample.kt` (fonte `example-notes` com `OWN` e quatro handlers), a migração de teste `src/test/resources/db/testmigration/V2026_10_16_0112__create_sync_example_note.sql`, a regra ArchUnit `offlineCommandHandlersAreAudited` (`architecture/LayerRules.kt`, aplicada em `LayerArchitectureTest` à produção e ao `syncexample`, com o exemplo negativo `src/test/kotlin/archfixtures/offlinehandlers/` e o teste em `LayerRulesTest`); ajustados `iam/AuthorizationTest.kt` e `rooms/RoomsApiTest.kt` (listas exatas dos modelos e do catálogo com `rooms.offline.read`).
- Decisões:
  - Entrevista com o usuário (2026-10-15, conduzida pelo orquestrador; ele escolheu a opção recomendada em todas):
    1. H6, gravação: trigger por tabela com a função genérica `shared.mark_offline_change()` (grava `nextval('shared.change_seq')` e `pg_current_xact_id()`), criado pelo módulo na própria migração.
    2. H6, cursor: `(xid, seq)` com o horizonte `pg_snapshot_xmin(pg_current_snapshot())`; só linhas com `xid` abaixo dele, em ordem `(xid, seq)`; cursor opaco. Mitigação documentada: `idle_in_transaction_session_timeout`.
    3. Relógio: sempre corrigir o `occurredAt` pela defasagem, limitado ao recebimento; acima de `zeny.sync.clock-tolerance` (5 min), aplicado com o alerta `CLOCK_SKEW_EXCEEDED`.
    4. Permissão nova `rooms.offline.read` (só `ALL`, não sensível), modelos Gerente, Governanta, Camareira e Manutenção.
    5. Retenção de 30 dias (`zeny.sync.command-retention`) com expurgo por job com ShedLock; `commandId` com instante UUIDv7 mais antigo que a retenção é `REJECTED COMMAND_EXPIRED`.
  - Decisões técnicas apresentadas ao usuário sem objeção: o cursor leva a impressão do usuário e das fontes visíveis com o escopo (mudou, `reset` e download completo); rejeição por `DomainException` é desfeita e gravada numa transação nova (a repetição devolve a mesma); `commandId` de outro usuário é `COMMAND_ID_CONFLICT` só naquele comando; exceção inesperada é `FAILED` sem nada gravado e os seguintes `NOT_PROCESSED`; handler `@Audited` + `@Transactional` na transação do protocolo; na fonte `rooms`, a UH desativada é o *tombstone* e o OOO fica fora.
  - Decisões tomadas na implementação (para o verificador e para revisão):
    1. A idempotência não tem estado "em andamento" visível: a linha do `commandId` nasce na transação do comando (`INSERT … ON CONFLICT DO NOTHING`); a repetição simultânea espera o commit da primeira e devolve o resultado dela. O `status = 'PROCESSING'` só existe dentro da transação (o `find` o ignora).
    2. Recusas do protocolo (`COMMAND_ID_INVALID`, `COMMAND_EXPIRED`, `UNKNOWN_COMMAND_TYPE`, `COMMAND_PAYLOAD_INVALID`) não ficam guardadas; o mesmo `commandId` com outro tipo é `COMMAND_ID_REUSED` (o payload não é comparado: o app pode reserializá-lo).
    3. No download completo, o `shared` descarta os *tombstones* com `xid` anterior ao horizonte da primeira página (guardado no cursor), em vez de cada fonte filtrar: o que sumir depois tem `xid` maior e chega como *tombstone*, mesmo no meio do download completo.
    4. `OfflineTombstones` com `leftScopeOf` vazio = apagado (todos); com usuários = saída do escopo `OWN` deles (só eles, com `OWN`, recebem). Tabela `shared.sync_tombstone` *append-only*; ainda sem expurgo.
    5. `deviceClock` é por comando, como na ARCHITECTURE §9 (o relógio do dispositivo no envio); a defasagem é `recebimento do lote − deviceClock`. O `occurredAt` corrigido fica em microssegundos (a precisão do banco), para a repetição devolver o mesmo instante.
    6. Download e upload exigem só autenticação; o download usa `PageParams` (padrão 50, máximo 200); o lote vai de 1 a 100 comandos.
    7. A fonte `rooms` entrega `number`, `floor`, `occupancy`, `housekeepingStatus`, `inspectionPending` e `serviceFlag` (sem `guestCount`, `notes` nem o tipo de UH); o trigger muda a posição a cada `UPDATE` da linha, mesmo sem mudar esses campos (só reenvia a UH).
    8. A gravação do resultado do comando é técnica (sem `@Audited` no processador, como a chave de idempotência); o handler é auditado, e a regra ArchUnit nova `offlineCommandHandlersAreAudited` exige `@Audited` e `@Transactional` sem `readOnly` em todo `handle`.
- Validações executadas (OpenJDK 25, Docker):
  - Os testes foram escritos depois do esqueleto da implementação (não antes, como pede o protocolo); para compensar, provei com mutações temporárias, revertidas, que eles pegam as regressões do foco do verificador:
    - cursor ingênuo (sem o horizonte, avançando até a última mudança entregue): falharam `a change committed after a later one is not left behind the cursor` e `no committed change is lost while concurrent transactions write during the download` (na primeira versão, o teste de carga atualizava linhas e não pegava a regressão, porque só a última versão de cada linha conta; foi reescrito com inserções);
    - sem a consulta do resultado guardado e ignorando o conflito da reserva: falharam `a repeated command id is applied once…`, `simultaneous requests with the same command id apply it once` e `a command id used by another user, or with another type, is refused`.
  - `./gradlew test --tests 'com.zeny.hotel.sync.OfflineSyncTest'` → 19/19, três execuções seguidas (duas com `--rerun`).
  - `./gradlew ktlintFormat` e `./gradlew lint` → ajustes (formatação, `ReturnCount`, `ComplexCondition`, `LongParameterList` suprimido com justificativa, linhas longas) → sem problemas.
  - `./gradlew updateOpenApi` → `api/openapi.yaml` +251/−0.
  - `./gradlew build` → `BUILD SUCCESSFUL in 4m 2s`: 510 testes, 0 falhas, 0 ignorados (`ModularityTest`, ArchUnit, `MigrationConventionTest`, `DatabaseMigrationsTest`, `DatabaseRolesTest`, `ApiSecurityTest`, `checkOpenApi`, `detekt*`, `ktlint*Check` e `koverVerify` incluídos). A primeira execução falhou só nas listas exatas dos modelos (`AuthorizationTest`, `RoomsApiTest`), ajustadas para a permissão nova.
  - `.github/scripts/check-api-breaking.sh` → "No breaking changes to report… Nenhuma mudança incompatível (nível ERR) em /api/v1." contra a base `7c9773a66675` (merge-base com `origin/main`) e contra `HEAD` (`e12cc911d44b`).
- Critérios de aceite (evidência para o verificador; marcados pelo orquestrador depois do parecer):
  - download completo e incremental com *tombstones*: `the first download brings every active room and later ones only what changed, with tombstones`, `a deleted note arrives as a tombstone recorded by the application, but not in a full download`, `with the OWN scope only the user's notes are downloaded, and a reassigned note leaves as a tombstone`, `pages follow the cursor without losing or repeating changes`;
  - transações concorrentes: `a change committed after a later one is not left behind the cursor` (determinístico) e `no committed change is lost while concurrent transactions write during the download` (4 escritores × 40 inserções com pausa dentro da transação);
  - RN-13: `the rooms source carries only number, floor and status, never guest data (RN-13)` (chaves exatas, sem observações nem número de hóspedes);
  - mesmo `commandId` no lote, em lotes seguidos, simultâneo e de outro usuário: `a repeated command id is applied once…` (com a trilha de auditoria contada), `simultaneous requests with the same command id apply it once`, `a command id used by another user, or with another type, is refused`;
  - rejeição com `code` sem interromper o lote e em ordem: `a batch is processed in order and a rejection does not stop it`, `a handler without the permission rejects the command`, `protocol refusals carry a stable code and are not stored`, `an unexpected failure stores nothing and leaves the rest of the batch for a retry`;
  - relógio: `occurredAt is corrected by the device clock skew, with a warning above the tolerance`; retenção: `stored results are purged after the retention, and an older command id is refused`;
  - `shared` sem depender de módulos: `ModularityTest` no build; handler auditado: `LayerArchitectureTest.offline command handlers are audited write use cases` e `LayerRulesTest.offline command handler without an audited write transaction fails`; `CLAUDE.md` documenta fontes e handlers; contrato, build e oasdiff acima.
- Parecer do verificador (independente, orquestrador, 2026-10-15): **APROVADO**. Snapshot da verificação `229ffaae4bfdcb3a23f43ddbce463d7812170da5` (25 arquivos, +3171/−15, todos no escopo). Todos os critérios APROVADOS. Validações: `./gradlew build` BUILD SUCCESSFUL (510 testes, 0 falhas, com `checkOpenApi` e `koverVerify`); `.github/scripts/check-api-breaking.sh` (contra `7c9773a` e `HEAD`) sem mudança ERR; contrato só com acréscimos. Ele conferiu que as decisões H6 e Q1 a Q5 foram seguidas. Mutações próprias numa cópia: horizonte `xmax` com cursor na última entregue derruba os dois testes de concorrência do download; reserva sem conferir o conflito derruba o teste do `commandId` simultâneo. Nenhum problema grave. Observações para revisão humana:
  - **Médio:** o `COMMAND_EXPIRED` usa o instante cru do UUIDv7 gerado no aparelho, sem a correção da defasagem (`OfflineCommands.kt:258`). Um aparelho com o relógio atrasado mais de 30 dias tem os comandos recusados para sempre, o que contraria o RNF-05. Um aparelho adiantado gera ids "do futuro", que poderiam ser reaplicados depois do expurgo. Sugestão: comparar o instante do id mais a defasagem com o limite.
  - **Baixo:**
    - O teste simultâneo depende da PK da nota de exemplo para contar um efeito só; um handler com efeito não idempotente, como um contador, tornaria a evidência independente.
    - O horizonte `xmin` vale para o cluster inteiro.
    - `shared.sync_tombstone` ainda não tem expurgo.
    - O expurgo de `shared.offline_command` grava por `TransactionTemplate` em `application`, fora da regra de auditoria, como escrita técnica (decisão 8).
    - `OfflineTombstones.record(..., leftScopeOf)` não impede incluir o dono atual do registro.
- Ajustes da revisão humana (2026-10-15; o usuário decidiu corrigir a observação média e a primeira baixa do verificador antes do commit; snapshot depois dos ajustes `5c5f2124c1a236310796c30827326b325f6cdbb8`):
  1. **`COMMAND_EXPIRED` pelo instante corrigido:** a expiração passa a comparar o instante do UUIDv7 do `commandId` **mais a defasagem** (a mesma do `occurredAt`) com o limite da retenção (`OfflineCommandService.protocolRefusal`). Um aparelho atrasado mais de 30 dias, com `deviceClock` coerente, tem o comando novo aceito (com o alerta `CLOCK_SKEW_EXCEEDED`).
  2. **Id "do futuro" (mesmo corrigido, além da tolerância), decisão desta sessão pelo tratamento mais seguro contra reaplicação sem descartar o fato (RN-22):** o comando é aceito e o resultado fica retido mais tempo. Nova coluna `shared.offline_command.retain_from` = o mais tardio entre o processamento, o instante cru do id e o instante corrigido; o expurgo (`deleteRetainedBefore`) apaga só quando a retenção passa de `retain_from` (índice trocado de `processed_at` para `retain_from`). Depois disso, o instante corrigido de qualquer reenvio coerente também passou da retenção e vence (`COMMAND_EXPIRED`). Alternativa descartada: recusar o id do futuro, que perderia o comando de um aparelho cujo relógio foi acertado depois de gerar o id. Risco residual registrado no `CLAUDE.md`: um reenvio depois do expurgo com o relógio do aparelho atrasado em relação ao do primeiro envio pode trazer o instante corrigido de volta para dentro da retenção. A coluna entrou na migração `shared/V2026_10_16_0110__create_offline_sync.sql`, ainda não commitada nem aplicada fora dos containers de teste (sem migração nova).
  3. **Teste simultâneo independente da PK:** o `RecordNoteHandler` de exemplo conta, em memória, as próprias execuções por `commandId` (inclusive as desfeitas); `simultaneous requests with the same command id apply it once` confere `invocations == 1` antes de tudo, e o teste de repetição no lote e entre lotes também.
  4. Testes novos: `a device whose clock is far behind still applies a new command, expiring by the corrected instant` (aparelho 40 dias atrasado: comando novo aplicado com alerta e `occurredAt` corrigido; um comando de fato antigo continua `COMMAND_EXPIRED`) e `a device whose clock is ahead does not get its command applied again after the purge` (aparelho 40 dias adiantado e id do futuro com relógio já acertado: depois de 31 dias e do expurgo, os dois voltam `replayed`; depois de mais 40 dias, os resultados saem e os dois vencem; o handler rodou uma vez para cada).
  5. Documentação: `CLAUDE.md` (regra de expiração e de retenção, risco residual, testes e o job), KDoc de `OfflineSyncCodes.COMMAND_EXPIRED`, `OfflineSyncProperties`, `CommandRetention`, `OfflineCommandPurgeJob`, `application.yaml` e a descrição de `POST /api/v1/sync/commands` no contrato.
  - Validações:
    - com o código antigo, os dois testes novos falharam (`expected:<APPLIED_WITH_WARNINGS> but was:<REJECTED>` no atrasado; `replayed` `[false, false]` no adiantado, porque o expurgo apagou e o comando foi reaplicado);
    - com a mutação que ignora o conflito da reserva (revertida), o teste simultâneo falhou já no contador (`expected:<1> but was:<4>`);
    - `./gradlew ktlintFormat` e `./gradlew lint` → sem problemas;
    - `./gradlew updateOpenApi` → só a descrição de `submitSyncCommands` mudou (contrato da etapa: +247/−0 contra `HEAD`);
    - `./gradlew build` → `BUILD SUCCESSFUL in 3m 25s`, 512 testes, 0 falhas, 0 ignorados;
    - `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`, contra `7c9773a66675` (merge-base com `origin/main`) e contra `HEAD`.
  - Continuam pendentes (já listadas): expurgo de `shared.sync_tombstone`, `idle_in_transaction_session_timeout` no banco de produção e a porta de *tombstones* sem trava contra incluir o dono atual em `leftScopeOf`.
- Ajustes da revisão humana — 2ª rodada (2026-10-15; snapshot antes da rodada `ebc221bdc3cb067fdc190be95a7d6af27eab7b5e`, depois `6d615a462dacf1561121f681e668fe7b7a3487b3`). O verificador aprovou a 1ª rodada com ressalvas, e o usuário decidiu:
  1. **Só o id depois da retenção** (decisão do usuário: confirmar a aceitação do id do futuro com `retain_from` e fechar o risco residual guardando o id). Quando `retain_from` passa de `zeny.sync.command-retention`, o `OfflineCommandPurgeJob` esvazia a linha (`status = 'EXPIRED'`, sem código, mensagem, alertas nem `occurredAt`; ficam id, usuário, tipo, `processed_at` e `retain_from`) e só a apaga depois de `zeny.sync.command-id-retention`. Reenvio de um id esvaziado é `REJECTED COMMAND_EXPIRED` (de outro usuário, `COMMAND_ID_CONFLICT`), qualquer que seja o `deviceClock`, e nunca chega ao handler. **Decisões do implementador:** esvaziar a linha em vez de criar uma tabela enxuta (a mesma chave primária continua barrando a reserva `ON CONFLICT`, sem corrida entre duas tabelas nem migração de linha; `CHECK offline_command_expired_ck` garante que nada do resultado sobra) e 365 dias para o id (cobre com folga qualquer relógio dentro da faixa plausível; o risco residual passa a exigir um reenvio depois de 365 dias com o aparelho uns 335 dias mais atrasado que no primeiro envio). A coluna, o status e o `CHECK` entraram de novo na migração ainda não commitada `shared/V2026_10_16_0110__create_offline_sync.sql`.
  2. **`deviceClock` absurdo recusado no protocolo** (decisão do implementador sobre a faixa): `deviceClock` e `occurredAt` (por comando, como na ARCHITECTURE §9) entre 2020-01-01T00:00Z (`OfflineSyncProperties.EARLIEST_DEVICE_TIME`, constante: antes do sistema existir) e o recebimento mais `zeny.sync.max-device-clock-ahead` (366 dias, configurável, até 3660; cobre um aparelho com o ano adiantado), senão `REJECTED DEVICE_TIME_INVALID` (código novo em `OfflineSyncCodes`); o instante do `commandId` na mesma faixa, senão `REJECTED COMMAND_ID_INVALID`. As conferências rodam antes de tudo (`OfflineCommandService.envelopeRefusal`), então nada chega a `Instant.plus` com valor implausível: antes, um `deviceClock` do ano -300000 virava `FAILED`. Um valor que nem cabe no `Instant` (`+1000000001-…`) é `400 VALIDATION_FAILED` no lote, pelo Jackson.
  3. **Teste do termo corrigido do `retain_from`.**
  4. **`CLAUDE.md`:** a idempotência por `commandId` virou uma lista (retenção em dois tempos, expiração pelo instante corrigido, a **assimetria** — id gerado com o relógio 30 dias ou mais atrasado e enviado depois de acertar o relógio é `COMMAND_EXPIRED` definitivo —, o risco residual e a **atribuição** corrigida: do usuário, a expiração pelo instante corrigido e, na 2ª rodada, a aceitação do id do futuro com `retain_from` e a retenção só do id; do implementador, a forma de guardar o id, o período de 365 dias e a faixa plausível). O "Relógio" registra que a defasagem inclui a latência da rede e a faixa plausível; recusas, testes, job e infraestrutura do `shared` atualizados. Também o KDoc (`OfflineSyncProperties`, `OfflineSyncCodes`, `OfflineCommandPurgeJob`), o `application.yaml` (`command-id-retention`, `max-device-clock-ahead`) e a descrição de `POST /api/v1/sync/commands`.
  - Testes novos ou mudados em `OfflineSyncTest`:
    - `after the retention only the command id is kept, and it is removed after the id retention` (substitui o teste de expurgo anterior);
    - `a command resent after the purge with the device clock further behind is never applied again` (o cenário pedido: aplicado; 31 dias e o expurgo; reenvio com o aparelho 2 dias mais atrasado → `COMMAND_EXPIRED`, contador 1);
    - `an id from the right clock sent with the device behind keeps its result for the corrected term` (id com o relógio certo, aparelho 10 dias atrasado no envio; 31 dias e o expurgo → `replayed`, contador 1);
    - `device times outside the plausible range are refused with a stable code` (ano -300000, `Instant.MIN` + 1 s, 400 dias à frente e antes de 2020, no `deviceClock`, no `occurredAt` e no id; o último comando do lote é aplicado);
    - `a device whose clock is ahead…` passou a conferir o esvaziamento em vez da exclusão;
    - `the batch envelope is validated` ganhou o horário além do `Instant`.
  - Validações:
    - com o código da 1ª rodada, falharam os testes novos: `a command resent after the purge…` (`expected:<REJECTED> but was:<FAILED>`: o handler rodou de novo e bateu na PK da nota), `device times outside the plausible range…` (`FAILED` e `NOT_PROCESSED` no lote), `after the retention only the command id is kept…` e o do aparelho adiantado (linha apagada em vez de esvaziada);
    - com `retain_from = max(processamento, id cru)` (mutação revertida), falhou `an id from the right clock sent with the device behind keeps its result for the corrected term`;
    - `./gradlew ktlintFormat` e `./gradlew lint` → sem problemas (o `processOne` foi dividido por `CyclomaticComplexMethod`);
    - `./gradlew updateOpenApi` → só a descrição de `submitSyncCommands` mudou (contrato da etapa: +250/−0 contra `HEAD`);
    - `./gradlew build` → `BUILD SUCCESSFUL in 2m 37s`, 515 testes, 0 falhas, 0 ignorados;
    - `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`, contra `7c9773a66675` (merge-base com `origin/main`) e contra `HEAD` (`e12cc911d44b`).
- Acabamento final (2026-10-15). Parecer do 3º verificador sobre a 2ª rodada: **APROVADO** (snapshot `09386e7f9d44db917b36b6e097ec9a3a3d7cff61`), sem problema grave nem médio, com 5 observações baixas: 3 corrigidas aqui e 2 mantidas como notas.
  1. **Corrigido: id conhecido responde antes da faixa plausível.** No `OfflineCommandService`, `knownOrImplausible` agora segue esta ordem:
     - confere o formato do id;
     - procura o id guardado (resultado, id esvaziado ou de outro usuário);
     - só para um id desconhecido, aplica a faixa plausível (`implausibleTime`).
     A defasagem (`skewOf`) e todo `Instant.plus` só rodam depois disso. Um id já aplicado, reenviado com `deviceClock` implausível, devolve o resultado guardado; depois de esvaziado, `COMMAND_EXPIRED`. Isso vale "qualquer que seja o `deviceClock`", como o usuário decidiu.
     - Teste novo: `a known command id is answered from what is stored whatever the device clock` (reenvio com o ano -300000: `replayed`; depois do expurgo, `COMMAND_EXPIRED`; contador 1). Com o código anterior, falhou com `expected:<APPLIED> but was:<REJECTED>`.
  2. **Corrigido: KDoc de `OfflineCommands.kt`:**
     - `OfflineCommandService`: os passos agora incluem a faixa plausível, `DEVICE_TIME_INVALID`/`COMMAND_ID_INVALID`, o id esvaziado que vira `COMMAND_EXPIRED` e a nova ordem.
     - `CommandRetention`: o expurgo esvazia o resultado e só depois apaga a linha.
     - `commandIdRetention`: a contagem parte de `retain_from`.
  3. **Corrigido: `CLAUDE.md`.**
     - A linha esvaziada guarda id, usuário, tipo, `processed_at` e `retain_from`.
     - O "Upload" registra que o id guardado responde antes da faixa plausível.
     - A lista de testes inclui o teste novo.
  4. **Nota, sem mudança de código:** os três horários (`deviceClock`, `occurredAt` e o instante do id) não são conferidos entre si. Cada um só precisa estar na faixa plausível, então `retain_from` pode ficar até cerca de 8 anos à frente: o id com 366 dias à frente, mais a defasagem do envio, dentro do limite de `max-device-clock-ahead`. Efeito: a linha é retida por mais tempo, sem risco de reaplicar.
  5. **Nota, sem mudança de código:** a assimetria do `COMMAND_EXPIRED` definitivo (id gerado com o relógio 30 dias ou mais atrasado e enviado depois de acertar o relógio) só é definitiva para um relógio honesto. Um aparelho que reenvie com o relógio atrasado de novo traz o instante corrigido de volta para a retenção e o comando é aceito, se o id não estiver guardado.
  - Validações:
    - `./gradlew ktlintFormat` e `./gradlew lint`: sem problemas.
    - `./gradlew build`: `BUILD SUCCESSFUL in 3m 16s`, 516 testes, 0 falhas, 0 ignorados. O `checkOpenApi` passou: a API não mudou, então o `updateOpenApi` não foi necessário.
    - `.github/scripts/check-api-breaking.sh`: nenhuma mudança `ERR` em `/api/v1`, contra `7c9773a66675` (merge-base com `origin/main`) e contra `HEAD` (`e12cc911d44b`).
- Notas para a próxima etapa:
  - E07 (checklists offline) e M6/M9: registrar handlers (`OfflineCommandHandler` em `application`, `@Audited` + `@Transactional`) e fontes (`OfflineSource` na raiz + `OfflineChangeSource` em `infrastructure` + trigger `shared.mark_offline_change()` na migração do módulo); usar o `commandId` como id do registro criado offline; RN-22 com `OfflineCommandOutcome(warnings)`; reatribuição com `OfflineTombstones.record(source, id, setOf(anterior))`.
  - O processador chama o handler na mesma thread da requisição: o ator (`CurrentActor`) e a `Authorization` são os do usuário da sincronização.
  - Pendências sem destino nesta etapa: expurgo de `shared.sync_tombstone` (com a regra de `reset` para cursores mais antigos que ele) e `idle_in_transaction_session_timeout` no banco de produção (M8, junto da configuração do PostgreSQL); métrica do atraso do horizonte, se o M6 mostrar transações longas.
  - Ressalva: a regra `offlineCommandHandlersAreAudited` olha o método `handle` da classe que implementa a porta; um handler que delegue a outro bean continua precisando das anotações no próprio `handle`.

---

## E12 — Limite de tentativas e arquivamento do outbox — #concluida

**Objetivo:** dar ao reprocessamento do outbox (`shared.event_publication`) um limite de tentativas com backoff, um estado de abandono com métrica e alerta, um procedimento de reenvio manual e o expurgo das publicações concluídas, antes do primeiro listener real do sistema (H13).

**Por que agora:** pendência herdada do M0 E02 e do M1 (handoff da E09): o `EventPublicationResubmissionJob` reenvia sem limite e sem backoff, e o primeiro `@ApplicationModuleListener` de produção chega na E07 (rotinas) e na E09 (push, com falhas externas).

**Dependências e paralelismo:** nenhuma. Pode correr em paralelo com E01 a E06 e E11 (em worktree separado); conflito de edição em `src/main/resources/application.yaml`, `docker/prometheus/alerts.yaml` e `CLAUDE.md`.

**Escopo:**

- `shared/infrastructure/events/EventPublicationResubmissionJob.kt`: só reenvia as `FAILED` cuja próxima tentativa já venceu (backoff exponencial pelo `completion_attempts` e pelo `last_resubmission_date`, de `zeny.events.resubmission.initial-backoff` a `max-backoff`) e deixa de reenviar as que passaram de `zeny.events.resubmission.give-up-after` (padrões da H13).
- `OutboxMetrics`: estado `abandoned` em `zeny_outbox_publications` e na idade da mais antiga; regra nova em `docker/prometheus/alerts.yaml` (conferida com `promtool check rules`).
- Expurgo das publicações concluídas há mais de `zeny.events.completed-retention` (padrão 7 dias), por um job com ShedLock (`events-purge-completed`), ou o modo de conclusão do Spring Modulith equivalente, se a investigação mostrar que ele cobre o caso; a escolha fica no handoff.
- Procedimento de reenvio manual de uma publicação abandonada (SQL ou método documentado no `CLAUDE.md`), sem endpoint.
- Testes com um listener de teste que falha N vezes (o `src/test/kotlin/com/zeny/hotel/events/ProbeListener.kt` é o modelo) e o `MutableClockConfiguration`.

**Fora de escopo:** listeners de negócio (E07, E09); reenvio por endpoint; mudança do schema v2 do `spring-modulith-events-jdbc` (só uma migração nova, se precisar de índice).

**Passos de implementação:**

1. Escrever testes: falha transitória reenviada com intervalos crescentes; falha permanente abandonada no prazo e contada na métrica; publicação abandonada não é reenviada pelo job nem pelo staleness; concluída antiga expurgada e recente mantida; reenvio manual volta a processar.
2. Implementar job, métrica, alerta, expurgo e documentação.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "na versão do Spring Modulith do catálogo (2.1.1), o que `FailedEventPublications`, `ResubmissionOptions` (filtro por `EventPublication`), `completion_attempts`, `last_resubmission_date`, `spring.modulith.events.completion-mode` e o staleness oferecem para backoff, abandono e expurgo?".
- Partes paralelas: nenhuma.
- Foco do verificador: publicação abandonada que volta a ser reenviada (pelo job, pelo staleness ou pelo `republish-outstanding-events-on-restart`); publicação incompleta apagada pelo expurgo; backoff medido pelo relógio da JVM em vez do `Clock`/banco; alerta sem regra conferida.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E12 — "Limite de tentativas e arquivamento do outbox" do roadmap docs/roadmaps/roadmap-m2-plataforma-operacional.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Contexto confirmado", a decisão confirmada H13 e o "Protocolo de execução" — e a seção E12 inteira. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que a etapa E09 (gate) de docs/roadmaps/roadmap-m1-acesso-configuracao.md está #concluida e que o EventPublicationResubmissionJob ainda reenvia sem limite; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: no shared, fazer o EventPublicationResubmissionJob reenviar as publicações FAILED com backoff exponencial de 1 min dobrando até 1 h e abandoná-las depois de 24 h de falhas (decisão H13, confirmada pelo usuário), com o estado abandoned no OutboxMetrics e uma regra em docker/prometheus/alerts.yaml; expurgar as publicações concluídas há mais de 7 dias com um job com ShedLock (ou o modo de conclusão do Spring Modulith, se cobrir o caso); documentar no CLAUDE.md o reenvio manual. Não crie listeners de negócio nem endpoints.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E12 para #em-andamento no índice e no título;
- investigação: um subagente Explore responde à pergunta da seção E12 sobre o Spring Modulith 2.1.1;
- escreva os testes primeiro (listener de teste que falha N vezes, MutableClockConfiguration), implemente, rode ./gradlew build, promtool check rules e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em publicação abandonada que volta, publicação incompleta expurgada e backoff pelo relógio errado.

Critérios de aceite (detalhes na seção E12):
- falha transitória é reenviada com intervalos crescentes e concluída;
- falha permanente é abandonada no prazo, contada como abandoned e não volta sozinha;
- concluídas antigas são expurgadas e as incompletas nunca;
- alerta conferido com promtool; CLAUDE.md documenta o ciclo e o reenvio manual; build passa.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E12 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [x] Um listener que falha duas vezes e depois funciona é reenviado em intervalos crescentes e a publicação fica concluída (teste com relógio controlado).
- [x] Um listener que sempre falha tem a publicação abandonada depois do prazo configurado: ela continua no banco, conta em `zeny_outbox_publications{state="abandoned"}` e não é reenviada pelo job, pelo staleness nem no reinício.
- [x] Publicações concluídas mais antigas que a retenção são apagadas; as recentes e as incompletas ficam.
- [x] O reenvio manual documentado no `CLAUDE.md` faz uma publicação abandonada ser processada de novo (teste).
- [x] A regra de alerta nova passa no `promtool check rules`; build passa.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `promtool check rules docker/prometheus/alerts.yaml` (pela imagem `prom/prometheus`, como no M0 E06) → `SUCCESS`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O `republish-outstanding-events-on-restart` reenvia todas as incompletas no boot, inclusive as abandonadas: se a API do Modulith não permitir filtrar, desligar a opção e cobrir o boot pelo próprio job, registrando a escolha.

**Evidências e handoff:**

- Snapshot inicial: `af617ff0a93d389167a9230e15d55d0d2e3570bd` (tirado pelo orquestrador; árvore limpa, sem mudanças preexistentes).
- Arquivos alterados:
  - Novos (`src/main/kotlin/com/zeny/hotel/shared/infrastructure/events/`): `OutboxProperties.kt` (`zeny.events.resubmission.{initial-backoff, max-backoff, give-up-after, batch-size}` e `zeny.events.completed-retention`, validados no boot), `OutboxRetryPolicy.kt` (backoff, vencimento e abandono; a condição de abandono também em SQL, `ABANDONED_CONDITION_SQL`), `CompletedEventPublicationPurgeJob.kt` (ShedLock `events-purge-completed`, a cada hora).
  - Alterados: `EventPublicationResubmissionJob.kt` (só reenvia as vencidas e não abandonadas, até `batch-size` por execução), `OutboxMetrics.kt` (estado `abandoned`), `src/main/resources/application.yaml` (padrões da H13, `purge.*` e `republish-outstanding-events-on-restart: false`), `docker/prometheus/alerts.yaml` (regra `ZenyOutboxAbandonedPublications`, comentários das outras), `CLAUDE.md` ("Estado atual", métricas e "Eventos e jobs" com o ciclo, o reenvio manual e o expurgo), `README.md` (métricas), `docs/ARCHITECTURE.md` (§5 passo 5 e tabela de jobs da §13).
  - Testes: novos `OutboxRetryTest`, `OutboxRetryPolicyTest` e `CompletedEventPublicationPurgeTest`; alterados `EventPublicationRegistryTest` (o boot de um segundo processo não reenvia nada e o job dele pula a abandonada; o teste do job antecipa a publicação, porque a 1ª nova tentativa vence 1 min depois), `OutboxMetricsTest` e `OutboxMetricsFailureTest` (estado `abandoned`), `ProbeListener.kt` (`ProbeState.failAlways` e `invocationsOf`), `testsupport/IntegrationTest.kt` (constante `OUTBOX_PURGE_DEFERRED`, também em `RealtimeStreamTest` e `ProductionProfileApiTest`).
- Decisões (do implementador, dentro da H13 confirmada):
  - **Investigação (Spring Modulith 2.1.1, fontes dos jars `spring-modulith-events-{api,core,jdbc}`):** `FailedEventPublications.resubmit(ResubmissionOptions)` lê as `FAILED` em ordem de publicação com `LIMIT batchSize` (e `maxInFlight`) e **só depois** aplica o filtro (`Predicate<EventPublication>`, que vê `status`, `completionAttempts`, `lastResubmissionDate` e `publicationDate` sem desserializar o evento); ao reenviar, `markResubmitted` grava `RESUBMITTED`, `completion_attempts + 1` e `last_resubmission_date` com o `Clock` do registro (o bean `Clock` da aplicação, `ObjectProvider.getIfAvailable`). Na criação, `completion_attempts = 1` e `last_resubmission_date` = data da publicação; a falha (`markFailed`) não grava data nem conta. O staleness só passa `PUBLISHED`/`PROCESSING`/`RESUBMITTED` antigos (pela `publication_date`) a `FAILED`, nunca reenvia. `republish-outstanding-events-on-restart` chama `resubmitIncompletePublications(__ -> true)`: todas as incompletas, sem filtro configurável. `completion-mode`: `update` (padrão), `delete` (apaga ao concluir) e `archive` (move para `event_publication_archive`, sem expurgo); nenhum faz a retenção de 7 dias, mas `CompletedEventPublications.deletePublicationsOlderThan(Duration)` apaga `completion_date < corte AND status = 'COMPLETED'` pelo mesmo `Clock`.
  - **Backoff:** depois de `n = completion_attempts − 1` reenvios, a próxima tentativa vence em `last_resubmission_date + min(1 min × 2ⁿ, 1 h)`, comparado com o `Clock` da aplicação (o mesmo que grava as datas; nunca o relógio da JVM direto nem o `now()` do banco).
  - **Abandono:** `FAILED` com `last_resubmission_date ≥ publication_date + 24 h`, ou seja, uma tentativa feita depois do prazo falhou (29º reenvio, 24 h 3 min depois da publicação). A regra só depende das colunas da linha, então nada automático a desfaz; o prazo conta da publicação (com a aplicação fora do ar, o tempo parado conta, mas sempre há ao menos uma tentativa depois do prazo). Alternativa descartada: contar só tentativas (independente do tempo parado), porque a H13 fala em "24 h de falhas" com `give-up-after` em duração.
  - **Leitura sem limite:** com o lote do Modulith, as abandonadas (que se acumulam) e as que esperam o backoff ocupariam o `LIMIT` e esconderiam as vencidas mais novas. O job passa `withBatchSize(Int.MAX_VALUE)` (só linhas `FAILED`, evento desserializado só no reenvio) e aplica o limite de `batch-size` (100) no filtro, que o Modulith chama em sequência.
  - **Boot:** `republish-outstanding-events-on-restart` **desligado** (risco previsto na etapa), porque reenviaria as abandonadas; o boot fica coberto pelo job (1ª execução 1 min depois, com o backoff) e pelo staleness (as deixadas em andamento viram `FAILED` aos 10 min).
  - **Expurgo:** job próprio com ShedLock chamando a API do Modulith, e não o modo de conclusão (nenhum faz a retenção); `completion-mode` continua `update`. Nenhuma migração: o índice `event_publication_by_completion_date_idx` já serve ao `DELETE`.
  - **Reenvio manual:** SQL documentado no `CLAUDE.md` ("Eventos e jobs"): `UPDATE shared.event_publication SET last_resubmission_date = publication_date WHERE id = '<id>' AND status = 'FAILED' AND last_resubmission_date >= publication_date + interval '24 hours'`, que devolve a linha ao estado de criação; o job a reenvia na execução seguinte e, se falhar, ela volta a ficar abandonada depois dessa tentativa. Também documentados a listagem das abandonadas e a desistência (marcar `COMPLETED`, depois expurgada).
  - **Alerta:** `ZenyOutboxAbandonedPublications` (`zeny_outbox_publications{state="abandoned"} > 0`, `critical`, sem `for`); as regras de `failed` continuam (agora sem as abandonadas).
  - **Testes:** o expurgo agendado fica adiado nos testes (`OUTBOX_PURGE_DEFERRED`), porque um contexto em cache com o `MutableClock` adiantado apagaria, no banco compartilhado, publicações de outro teste.
- Validações executadas:
  - Testes primeiro: `./gradlew test --tests 'com.zeny.hotel.events.OutboxRetryTest'` contra o job antigo → 4 de 5 falharam (o que não falhou é o de que o reenvio manual não mexe em publicação não abandonada).
  - `./gradlew test --tests 'com.zeny.hotel.events.*'` → 22 testes, 0 falhas.
  - `./gradlew build` → `BUILD SUCCESSFUL` (529 testes, 0 falhas; detekt, ktlint, `checkOpenApi` e `koverVerify`). Uma execução anterior falhou no `detektTest` (`LongParameterList` e `!!` desnecessário no `OutboxRetryTest`), corrigido; `./gradlew lint` passou.
  - `docker run --rm -v "$PWD/docker/prometheus:/rules:ro" --entrypoint promtool prom/prometheus check rules /rules/alerts.yaml` → `SUCCESS: 5 rules found`.
  - `.github/scripts/check-api-breaking.sh` → "Nenhuma mudança incompatível (nível ERR) em /api/v1" (a etapa não muda a API; o contrato não mudou).
  - Critérios: (1) `OutboxRetryTest` "a transient failure is resubmitted at growing intervals and completes" (não reenvia aos 59 s, reenvia a 1 min, não aos 1 min 59 s depois, reenvia aos 2 min e conclui, `completion_attempts` 3); (2) "a permanent failure is abandoned…" (esperas 1, 2, 4, 8, 16, 32, 60… min, 29 reenvios, a última tentativa depois de 24 h, `abandoned` 1 e `failed` 0, nada com o relógio 3 dias à frente, nem pelo job nem pela tarefa do staleness) e `EventPublicationRegistryTest` "a new boot resubmits nothing by itself…"; (3) `CompletedEventPublicationPurgeTest` (concluídas há 8 dias e há 7 dias + 1 s apagadas; há 6 dias, há 7 dias − 1 min e as `FAILED`, abandonadas, `PUBLISHED`, `PROCESSING` e `RESUBMITTED` de 30 dias ficam; uma entrega real é apagada só depois de o `Clock` passar a retenção; agendado a cada hora, com ShedLock); (4) "the documented manual resubmission makes an abandoned publication run again" e "…fails again is abandoned again…"; (5) promtool e build acima.
- Parecer do verificador: **APROVADO** (2026-10-16, verificador independente disparado pelo orquestrador; snapshot verificado `153aedc59acbe27bdd9b12e59132e034f5d0c0f1`). Os 5 critérios APROVADOS com evidência: `OutboxRetryTest`, `EventPublicationRegistryTest` e `CompletedEventPublicationPurgeTest`; `./gradlew build` BUILD SUCCESSFUL com 529 testes e 0 falhas; `promtool` `SUCCESS: 5 rules found`; oasdiff sem mudança incompatível. O verificador conferiu as alegações da investigação no código-fonte do Spring Modulith 2.1.1. Nenhum problema crítico, alto ou médio. Problemas baixos:
  1. A staleness mede `RESUBMITTED` pela `publication_date`, então a tentativa feita depois das 24 h pode contar como `abandoned` enquanto o listener ainda roda, e o alerta `ZenyOutboxAbandonedPublications` (`critical`, sem `for`) pode disparar sem necessidade. Um `for: 5m` resolveria; fica para o gate (E10) ou a E09.
  2. O job lê todas as `FAILED` a cada minuto, inclusive as abandonadas, sem teto (irrelevante para 30 UHs).
  3. Risco pequeno de teste instável: a tarefa de staleness do contexto com o `MutableClock` adiantado não é adiada e pode passar a `FAILED` uma linha `PUBLISHED` de outro teste no banco compartilhado.
  4. Informativo: sem o republish no boot, uma `PUBLISHED` deixada por uma queda só é reenviada uns 11 min depois.
- Notas para a próxima etapa:
  - E07 e E09 (primeiros listeners reais): todo `@ApplicationModuleListener` precisa ser idempotente; o staleness do Modulith pode passar a `FAILED` um reenvio ainda em execução (ele mede pela `publication_date`), e o backoff evita o reenvio em dobro enquanto o listener levar menos que a espera.
  - Uma falha que não deve ser reenviada (ex.: token FCM inválido na E09) deve ser tratada dentro do listener (registrar e concluir), não lançada: lançar faz o outbox reenviar por 24 h.
  - Com `give-up-after` diferente de 24 h, o intervalo do SQL de reenvio manual no `CLAUDE.md` muda junto.
  - Os testes que publicam eventos continuam apagando `shared.event_publication` inteira no `@BeforeEach` (banco compartilhado, testes sequenciais).

---

## E06 — Modelos de checklist — #concluida

**Objetivo:** criar o módulo `checklists` com modelos por contexto, versões publicadas imutáveis, seções e itens ordenáveis dos tipos da SPEC, itens obrigatórios e condicionais, regras de aplicabilidade e ações configuradas por item, mais a API pública que escolhe o modelo de um sujeito.

**Por que agora:** governança, manutenção, áreas comuns, restaurante e eventos usam o mesmo motor (SPEC §1.1). A execução (E07) depende dos modelos.

**Dependências e paralelismo:** nenhuma dentro deste marco; o gate do M1 está concluído. Pode correr em paralelo com E01 a E05, E11 e E12 (em worktree separado); conflito de edição em `api/openapi.yaml`, `ModularityTest.kt` e `CLAUDE.md`.

**Escopo:**

- Módulo `checklists` (a criar) em `src/main/kotlin/com/zeny/hotel/checklists/`: `ChecklistsModule.kt` com `@ApplicationModule(allowedDependencies = ["shared", "hotel", "staff", "files"])` (ARCHITECTURE §3.1), `ChecklistsPermissions` (modelos: `checklists.templates.read`/`manage`, com `templates` para `HOUSEKEEPER` e `MANAGER`), `ChecklistsErrorCodes` e `ChecklistsApi` na raiz; migrações em `src/main/resources/db/migration/checklists/` (a criar), com a de `GRANT`.
- `ChecklistTemplate` (nome, contexto: governança, inspeção, manutenção, área comum, restaurante, evento, outro; ativo), `ChecklistVersion` (número, publicada em), `ChecklistSection`, `ChecklistItem` (rótulo, tipo, obrigatório, ordem, condição `visibleWhen`, opções de escolha, unidade de medida), `ApplicabilityRule` (H3) e `ItemAction` (baixa de estoque, troca de enxoval, abrir ocorrência, com IDs opacos) — SPEC §8.2.11.
- Regras: rascunho editável; publicar congela a versão; editar um modelo publicado cria nova versão (RF-CHK-06); condição só referencia item anterior com resposta compatível; ação de baixa só em item de quantidade; foto nunca obrigatória por padrão.
- Endpoints: `/api/v1/checklist-templates` (CRUD do rascunho com `If-Match`, `POST …/{id}/publish`, `GET …/{id}/versions/{number}`); sem `DELETE` (desativação).
- API pública: `resolveTemplate(context, subjectAttributes)` (RF-CHK-04) e leitura de uma versão.
- `ModularityTest`: teste que confere que `checklists` só usa a API pública e, por enquanto, só `shared` (a E07 passa à lista exata da ARCHITECTURE).
- `@Audited` em toda escrita; `CLAUDE.md` com o módulo.

**Fora de escopo:** execução, fotos e rotinas (E07); consumidores das ações (M6, M9).

**Passos de implementação:**

1. Escrever testes de domínio (versionamento, validação de condições e ações, resolução por aplicabilidade) e de integração dos endpoints.
2. Implementar módulo, migrações, casos de uso, controllers e API pública.
3. Regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "o que SPEC §5.15 (RF-CHK-01 a 06) e §8.2.11 definem para modelos, itens, condicionais, aplicabilidade e ações?".
- Partes paralelas: nenhuma.
- Foco do verificador: versão publicada alterável (pela API ou pelo papel da aplicação via SQL, se a etapa decidir proteger no banco); condição circular ou apontando para item posterior; `checklists` importando `rooms`, `inventory` ou outro módulo fora de `shared`, `hotel`, `staff` e `files`.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E06 — "Modelos de checklist" do roadmap docs/roadmaps/roadmap-m2-plataforma-operacional.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Contexto confirmado", "Restrições e invariantes", a hipótese H3 e o "Protocolo de execução" —, a seção E06 inteira e as "Notas para o marco M2" da etapa E09 de docs/roadmaps/roadmap-m1-acesso-configuracao.md. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que a etapa E09 (gate) de docs/roadmaps/roadmap-m1-acesso-configuracao.md está #concluida e que o código bate com o "Contexto confirmado"; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar o módulo checklists (src/main/kotlin/com/zeny/hotel/checklists/, allowedDependencies shared, hotel, staff e files) com modelos por contexto, rascunho editável, versões publicadas imutáveis (editar publicado cria nova versão), seções e itens ordenáveis dos tipos da SPEC, obrigatórios e condicionais, regras de aplicabilidade com IDs opacos, ações configuradas por item (baixa de estoque, troca de enxoval, abrir ocorrência), endpoints em /api/v1/checklist-templates com If-Match e sem DELETE, ChecklistsPermissions com templates e a API pública resolveTemplate(context, subjectAttributes). Não implemente execução, fotos, rotinas nem consumidores das ações.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E06 para #em-andamento no índice e no título;
- investigação: um subagente Explore resume docs/SPEC.md §5.15 (RF-CHK-01 a 06) e §8.2.11;
- escreva os testes de domínio e de integração primeiro, implemente, acrescente o checklists ao ModularityTest, atualize o CLAUDE.md, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em versão publicada alterável, condição inválida e dependência indevida de módulos.

Critérios de aceite (detalhes na seção E06):
- editar modelo publicado cria nova versão e a anterior permanece idêntica;
- condição apontando para item posterior ou com resposta incompatível e ação de baixa fora de item de quantidade rejeitadas com code estável;
- resolveTemplate escolhe o modelo pela aplicabilidade;
- contrato regenerado; build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E06 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [x] Publicar um rascunho cria a versão 1; editar o modelo publicado e publicar cria a versão 2, e a versão 1 continua idêntica (teste).
- [x] Os sete tipos de item da SPEC §8.2.11 são aceitos; foto não é obrigatória por padrão.
- [x] Condição que referencia item posterior, inexistente ou com valor incompatível → `400` com `code` estável.
- [x] Ação de baixa de estoque fora de item de quantidade → `400` com `code` estável.
- [x] `resolveTemplate` devolve o modelo cuja aplicabilidade casa com os atributos informados e, sem regra específica, o modelo geral do contexto (teste de módulo).
- [x] Escritas com permissão e auditadas; `ModularityTest` mostra `checklists` dependendo só da API pública de módulos da lista da ARCHITECTURE §3.1.
- [x] Contrato regenerado; build e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Linguagem de condição complexa demais: limitar a "item X tem resposta Y" e registrar como decisão.

**Evidências e handoff:**

- Snapshot inicial: `365269eac7bce8d607738cc64f2b705be41ff477` (recebido do orquestrador). Snapshot ao fim da implementação: `12b283fb83e04d8a4bb6e8ef9c2a119091df492d`.
- Preparação: M1 E09 `#concluida`; o código bate com o "Contexto confirmado" (módulos `shared`, `iam`, `audit`, `hotel`, `staff`, `files`, `rooms`; última migração `V2026_10_16_0112`, de teste; E12 commitada em `270f820`, não tocada). A investigação de SPEC §5.15 e §8.2.11 foi feita pelo próprio subagente (adendo do orquestrador), sem `Explore`.
- Arquivos alterados:
  - novo módulo `src/main/kotlin/com/zeny/hotel/checklists/`: raiz `ChecklistsModule.kt` (`allowedDependencies = ["shared", "hotel", "staff", "files"]`), `ChecklistsPermissions.kt`, `ChecklistsErrorCodes.kt`, `ChecklistContent.kt` (contexto, tipos de item, conteúdo, aplicabilidade, condição, ações), `ChecklistsApi.kt` (API pública, ids, `ChecklistVersionView`); `domain/ChecklistTemplate.kt` (agregado e `ChecklistVersion`), `domain/ChecklistContentRules.kt` (validação), `domain/TemplateResolution.kt`; `application/ChecklistTemplates.kt` (casos de uso, `ChecklistsApiService`, bean das permissões), `ChecklistsPorts.kt`, `ChecklistsAudit.kt`; `infrastructure/JdbcChecklists.kt` (persistência `JdbcClient` + `jsonb`, bean `AppendOnlyTables`); `web/ChecklistTemplatesController.kt`, `web/ChecklistsDtos.kt`;
  - migrações `src/main/resources/db/migration/checklists/V2026_10_16_0340__create_checklist_templates.sql` e `V2026_10_16_0341__grant_checklists_to_app_role.sql`;
  - testes `src/test/kotlin/com/zeny/hotel/checklists/ChecklistDomainTest.kt` (12), `ChecklistTemplatesApiTest.kt` (10), `ChecklistsTestSupport.kt`; `ModularityTest.kt` (teste novo do `checklists`); `iam/AuthorizationTest.kt` (o modelo Gerente ganha as duas permissões novas);
  - `api/openapi.yaml` (regenerado), `CLAUDE.md` ("Estado atual" e seção "Modelos de checklist"), este roadmap (tags e handoff).
- Decisões (todas **decididas pela opção recomendada, por autorização do usuário em 2026-10-16**, para revisão posterior):
  1. **Persistência:** o conteúdo do modelo (aplicabilidade, seções, itens, condições, ações) é um documento `jsonb` — o rascunho em `checklists.checklist_template.draft`, cada versão em `checklists.checklist_version.content` —, com o agregado em Kotlin puro e `JdbcClient` (como o catálogo de permissões do `iam`). Descartadas: tabelas normalizadas com JPA para seção, item e ação (imutabilidade teria de ser garantida em cada tabela) e um mapeamento JSON do Hibernate.
  2. **Imutabilidade das versões:** a tabela é *append-only* para o papel da aplicação (`REVOKE UPDATE, DELETE, TRUNCATE`, bean `AppendOnlyTables`, conferido pelo `DatabaseRoleGuard`) e o trigger `checklist_version_immutable` recusa `UPDATE`/`DELETE` até do dono. FK composta do modelo para a última versão publicada. Descartada: só uma das duas proteções.
  3. **Rascunho no próprio modelo**, número da versão atribuído na publicação (`ultima + 1`, único por `checklist_version_number_uk`). Descartada: uma linha de versão "rascunho" com `published_at` nulo.
  4. **Nome** do modelo não versionado (vale na hora; a versão guarda o nome da publicação), **contexto** fixo desde a criação, **aplicabilidade versionada** junto com o conteúdo (a escolha usa a da última versão publicada). Descartada: aplicabilidade no modelo, valendo sem publicar (SPEC §8.2.11 a desenha no modelo; aqui ela é um conteúdo do modelo que também passa pela publicação, para "o que vale é o publicado").
  5. **Identidade do item:** `key` informada pelo cliente (slug, única no modelo, estável entre versões), citada pelas condições e, na E07, pelas respostas. Descartado: UUID gerado pelo servidor (a condição sobre um item novo no mesmo `PUT` não teria como citá-lo).
  6. **Linguagem de condição** (risco da etapa): só "o item X, anterior na ordem do modelo, tem a resposta Y", sobre `CHECK` e itens de escolha (na múltipla, "a opção está marcada"); três códigos (`CHECKLIST_CONDITION_FORWARD_REFERENCE`, `_UNKNOWN_ITEM`, `_INCOMPATIBLE`). Descartadas: comparações numéricas e expressões compostas.
  7. **Ações:** `CONSUME_STOCK` e `LINEN_EXCHANGE` só em item `QUANTITY`; `OPEN_OCCURRENCE` só em `CHECK` (dispara no `NOT_OK`); `LINEN_EXCHANGE` ganhou `movement` (`REMOVED`/`PLACED`, para "peças retiradas e colocadas" da RF-CHK-05; extensão da SPEC); `occurrenceKind` `MAINTENANCE`/`LOST_AND_FOUND`/`DAMAGE`; na API, objeto plano com `type` e só os parâmetros do tipo (`CHECKLIST_ACTION_INVALID` para faltante ou de outro tipo). Descartados: `oneOf` polimórfico no contrato e uma troca de enxoval com duas quantidades no mesmo item.
  8. **Foto:** `required` é `false` por padrão em todo tipo; um item `PHOTO` pode ser marcado obrigatório explicitamente ("não exigida por padrão", RF-CHK-02). Descartado: proibir foto obrigatória.
  9. **Escolha do modelo:** regra mais específica (mais critérios restritos) entre as que casam; sem nenhuma, o modelo geral (sem regras); empate, o criado primeiro; regra sem critério recusada (`CHECKLIST_APPLICABILITY_INVALID`); modelos inativos ou nunca publicados não entram. Descartados: recusar a ambiguidade na publicação e desempatar pela publicação mais recente.
  10. **Publicação com `If-Match` obrigatório** (publica-se o rascunho que se viu) e `422 CHECKLIST_NOTHING_TO_PUBLISH` sem diferença; modelo desativado não é editado nem publicado (`422 CHECKLIST_TEMPLATE_INACTIVE`).
  11. **Permissões:** `checklists.templates.read` e `.manage`, só `ALL`, não sensíveis, sugeridas a Gerente e Governanta (SPEC §3.1).
  12. **`ModularityTest`:** o `checklists` depende hoje exatamente de `shared`, dentro da lista declarada (`shared`, `hotel`, `staff`, `files`, conferida na anotação), e de nenhum tipo interno de outro módulo.
- Validações executadas:
  - `./gradlew test --tests 'com.zeny.hotel.checklists.*' --tests 'com.zeny.hotel.ModularityTest'` → 32 testes, 0 falhas (a primeira rodada pegou um `500` na corrida de duas publicações: o `SELECT … FOR UPDATE` com `LEFT JOIN` da versão reavaliava só o modelo; corrigido travando num comando à parte, e o teste passou a dar `201` + `412`).
  - `./gradlew updateOpenApi` → contrato regenerado (só acréscimos: `/api/v1/checklist-templates…` e os schemas).
  - `./gradlew ktlintCheck detekt detektMain detektTest` → sem violações.
  - `./gradlew build` → **BUILD SUCCESSFUL** (552 testes, 0 falhas; inclui `checkOpenApi`, `ModularityTest`, ArchUnit, `DatabaseRolesTest`, `MigrationConventionTest` e `koverVerify`). Duas rodadas anteriores falharam: a 1ª no `AuthorizationTest` (lista exata das concessões do Gerente, atualizada com as duas permissões novas) e a 2ª no `HkdfTokenSealerTest.tampered, relabeled or malformed tokens fail`, **instável e preexistente** (trocar o último caractere base64url às vezes só mexe nos bits de preenchimento e o token decodifica igual), sem relação com a etapa e não alterado; a 3ª passou inteira.
  - `.github/scripts/check-api-breaking.sh` → "Nenhuma mudança incompatível (nível ERR) em /api/v1" (base `7c9773a`, merge-base com `origin/main`).
- Critérios de aceite (autoavaliação, pendente do verificador):
  - versão 1 → edição → versão 2, com a 1 idêntica pela API (JSON igual) e no banco (`to_jsonb` da linha igual): `ChecklistTemplatesApiTest` "a draft is published as version 1, an edit as version 2, and version 1 stays identical" e `ChecklistDomainTest`;
  - os sete tipos e foto não obrigatória por padrão: "the seven item types are accepted, and nothing (photo included) is required by default" (API e domínio);
  - condição posterior, inexistente ou incompatível → `400` com `code` e campo: "a condition on a later, unknown or incompatible item…" (API) e dois testes de domínio;
  - baixa de estoque fora de item de quantidade → `400 CHECKLIST_ACTION_ITEM_TYPE_MISMATCH`: "a stock action outside a quantity item…" (API, cada tipo) e domínio;
  - `resolveTemplate` pela aplicabilidade e o geral sem regra específica: "resolveTemplate picks the published template…" (bean `ChecklistsApi` no contexto) e dois testes do `TemplateResolution`;
  - escritas com permissão (403 para quem só lê) e auditadas (`checklists.template.*` e `checklists.version`); `ModularityTest` "checklists depends only on shared for now…";
  - contrato regenerado; build e oasdiff passam.
- Parecer do verificador: **APROVADO** (2026-10-16, verificador independente disparado pelo orquestrador; snapshot verificado `22eaffddfbb1b4ea73d7a64768a38dc273495923`). Os 7 critérios APROVADOS com evidência: `ChecklistTemplatesApiTest` (a v1 idêntica pela API e por `to_jsonb` no banco; os sete tipos; condição e ação inválidas; `resolveTemplate` sobre o bean real), `ChecklistDomainTest`, `ModularityTest` com dependências exatamente `[shared]`; `./gradlew build` BUILD SUCCESSFUL com 552 testes e 0 falhas; oasdiff sem `ERR`, contrato só com acréscimos. A versão publicada não muda pela API, nem pelo papel da aplicação (REVOKE + `AppendOnlyTables`), nem pelo dono via UPDATE/DELETE (trigger); duas publicações simultâneas dão 201 + 412. Sem problema grave ou médio. Ressalvas baixas:
  1. O dono ainda contorna a imutabilidade com `TRUNCATE` ou `DISABLE TRIGGER` (aceitável; vale citar no CLAUDE.md).
  2. Os parâmetros das ações são validados no controller, então dá 400 antes do 403 (mesmo padrão do M1).
  3. O cursor das versões trunca `long` → `Int` em silêncio em vez de `400 INVALID_CURSOR`.
  4. O teste do `resolveTemplate` desativa por SQL todos os modelos `EVENT` do banco compartilhado (contexto reservado ao teste; frágil).
  5. Desativar e reativar não pedem `If-Match` (coerente com os outros módulos).
  6. `HkdfTokenSealerTest` instável, anterior à etapa: trocar o último caractere base64url às vezes só mexe nos bits de preenchimento. Fica como pendência própria.
- Notas para a próxima etapa (E07):
  - A resposta cita o item pela `key` da versão (`ChecklistVersionView.content.items`); a condição de visibilidade e a obrigatoriedade são avaliadas com as regras da E06 (`required` só vale com o item visível; condição sobre item oculto conta como não atendida).
  - O gatilho das ações é derivável do tipo: `CONSUME_STOCK`/`LINEN_EXCHANGE` com a quantidade respondida (> 0) e `OPEN_OCCURRENCE` com `NOT_OK`.
  - Foto pode ser obrigatória por configuração: decidir na E07 o comando offline com foto obrigatória ainda não enviada (H10 fala em "aplicado com alerta").
  - Fonte offline das versões aplicáveis: `checklist_version` precisa de `change_seq`/`change_xid` e do trigger `shared.mark_offline_change()`; o passo `UPDATE … SET change_seq = NULL` do modelo do `rooms` esbarra no trigger `checklist_version_immutable` e no `REVOKE` (a migração roda como dono, mas o trigger recusa): desligue o trigger na própria migração (`ALTER TABLE … DISABLE TRIGGER checklist_version_immutable` e `ENABLE` depois) ou crie as colunas antes de haver linhas em produção. O `BEFORE INSERT` do `mark_offline_change` não conflita com o `BEFORE UPDATE OR DELETE` da imutabilidade.
  - A E07 acrescenta as dependências de `hotel`, `staff` e `files` e troca o teste do `ModularityTest` pela lista exata.
  - Pendência fora da etapa: o `HkdfTokenSealerTest` instável (acima) merece correção própria (trocar um caractere do meio do token, não o último).

---

## E11 — Ciclo de vida dos anexos — #concluida

**Objetivo:** completar o módulo `files` com o que as fotos de checklist (E07) e a primeira finalidade sensível (M3) precisam: desvincular e apagar anexos pela `FilesApi`, expurgar os órfãos, aceitar um upload idempotente com id gerado pelo cliente para o app offline e reencodar as imagens das finalidades que pedirem (H10, H11, H12).

**Por que agora:** decisão da revisão humana do M1 (handoff da E09, Q2): expurgo de órfãos e `detach`/`delete` no M2, junto com as fotos de checklist, prontos antes do M3. Hoje repetir um upload cria outro anexo, que fica órfão para sempre.

**Dependências e paralelismo:** nenhuma dentro deste marco. Pode correr em paralelo com E01 a E06 e E12 (em worktree separado); conflito de edição em `api/openapi.yaml`, `src/main/resources/application.yaml`, `CLAUDE.md` e `README.md`.

**Escopo:**

- `files/FilesApi.kt`: `detach(id, purpose, ownerEntityId)` (o anexo volta a órfão; auditado `files.file.detach`) e `delete(id, purpose, ownerEntityId)` (apaga o objeto depois do commit e marca a linha com `deleted_at`; auditado `files.file.delete`), conforme H12; anexo apagado some de `find`, da URL de download e dos metadados (`404 FILE_NOT_FOUND`).
- Expurgo: job em `files/infrastructure/` com `@SchedulerLock` (`files-orphans-purge`), a cada hora, para anexos sem vínculo há mais de `zeny.files.orphan-retention` (padrão `PT24H`), auditado com ator sistema (`files.file.purge-orphan`, H12). Migração nova em `src/main/resources/db/migration/files/` para `deleted_at` (e índice, se preciso).
- Upload idempotente (H10): parte opcional `clientFileId` em `POST /api/v1/files`; mesmo id, mesmo usuário e mesmo SHA-256 → `200` com o anexo existente, sem gravar; mesmo id com outro conteúdo, outra finalidade ou de outro usuário → `409` com `code` estável; id fora do formato UUID → `400`. Sem `clientFileId`, o comportamento atual (201, id do servidor).
- Reencode (H11): atributo novo na `FilePurpose` (ex.: `reencodeImage`, com lado máximo); a imagem é decodificada e regravada com `javax.imageio` antes de calcular o SHA-256 e de ir ao bucket, sem metadados; o boot recusa finalidade com reencode que aceite tipo não reencodável (WebP, PDF). Nenhuma finalidade de produção muda de comportamento nesta etapa (o logotipo continua como está); a primeira com reencode é a foto de checklist da E07.
- `FilesApiTest`: trocar o TTL de 4 s com `Thread.sleep` por relógio controlado ou folga que não falhe numa CI lenta.
- Finalidade só de teste com reencode em `src/test/kotlin/filesexample/` para os testes.
- `CLAUDE.md` (seção "Anexos") e `README.md` (API de anexos).

**Fora de escopo:** finalidade de foto de checklist (E07); porta `FileAccessPolicy` (H15); leitura e gravação de conteúdo pelo servidor (M3 em diante); retenção LGPD de anexos com dono (M10).

**Passos de implementação:**

1. Escrever testes: `detach` e `delete` (trilha, objeto removido, linha marcada, 404 depois); expurgo de órfão antigo e preservação de órfão recente e de anexo vinculado (relógio controlado); upload repetido com `clientFileId` (200, um só objeto e uma só linha), conflito de conteúdo e de usuário; imagem com EXIF/GPS sem metadados depois do reencode; poliglota PNG+outro formato neutralizado; boot recusando reencode com WebP.
2. Implementar API, job, migração, upload idempotente e reencode.
3. Documentar e regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "como `FilesService.kt`, `FilesApiService.kt`, `FilePurposes.kt` e `FileContentRulesTest`/`FilesApiTest` gravam metadados, objeto e trilha hoje, e onde o `afterCompletion` apaga o objeto em rollback?"; "como `javax.imageio` do JDK 25 lê e grava JPEG e PNG sem copiar metadados, e qual o custo de memória de decodificar uma foto de 12 MP?".
- Partes paralelas: nenhuma.
- Foco do verificador: anexo vinculado apagado pelo expurgo; objeto apagado antes do commit (rollback deixa linha sem objeto); upload repetido criando segundo objeto ou devolvendo anexo de outro usuário; metadados EXIF/GPS que sobrevivem ao reencode; expurgo sem trilha; mudança de comportamento do logotipo.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E11 — "Ciclo de vida dos anexos" do roadmap docs/roadmaps/roadmap-m2-plataforma-operacional.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Contexto confirmado", as decisões confirmadas H10, H11, H12 e H15 e o "Protocolo de execução" —, a seção E11 inteira e o "Evidências e handoff" das etapas E08 e E09 de docs/roadmaps/roadmap-m1-acesso-configuracao.md. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que a etapa E09 (gate) de docs/roadmaps/roadmap-m1-acesso-configuracao.md está #concluida e que a FilesApi ainda tem só find e attach; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: no módulo files, acrescentar FilesApi.detach e FilesApi.delete (objeto apagado depois do commit, linha marcada com deleted_at, auditados), um job com ShedLock que expurga anexos órfãos após zeny.files.orphan-retention, 24 h por padrão (auditado com ator sistema, H12), o upload idempotente pela parte opcional clientFileId (repetição idêntica → 200 sem gravar; conflito → 409) (H10) e o reencode de imagens nas finalidades marcadas (javax.imageio só para JPEG e PNG, sem metadados, lado máximo de 2048 px; WebP fora dessas finalidades, H11); trocar o Thread.sleep do FilesApiTest por relógio controlado. Não crie a finalidade de foto de checklist nem a porta FileAccessPolicy, e não mude o comportamento do logotipo.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E11 para #em-andamento no índice e no título;
- investigação: um subagente Explore responde às perguntas da seção E11 (fluxo atual do files e javax.imageio sem metadados);
- escreva os testes primeiro, implemente, atualize CLAUDE.md e README.md, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em anexo vinculado expurgado, objeto apagado antes do commit, upload repetido duplicando, metadados que sobrevivem e expurgo sem trilha.

Critérios de aceite (detalhes na seção E11):
- detach e delete funcionam, auditados, e o anexo apagado dá 404;
- órfão antigo expurgado e auditado; recente e vinculado preservados;
- upload repetido com clientFileId não duplica; conflito → 409;
- imagem reencodada sem EXIF/GPS; FilesApiTest sem Thread.sleep; contrato regenerado; build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E11 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [x] `FilesApi.detach` deixa o anexo sem dono e grava `files.file.detach`; `FilesApi.delete` remove o objeto do bucket só depois do commit, marca a linha e grava `files.file.delete`; depois, `find` devolve `null` e metadados e URL dão `404 FILE_NOT_FOUND`.
- [x] O job apaga o objeto e marca a linha de um órfão mais antigo que a retenção, com entrada `files.file.purge-orphan` de ator sistema; órfão recente e anexo vinculado ficam (teste com relógio controlado).
- [x] Upload com o mesmo `clientFileId`, o mesmo usuário e o mesmo conteúdo devolve `200` com o mesmo anexo e deixa uma só linha e um só objeto; com outro conteúdo, outra finalidade ou outro usuário → `409` com `code` estável.
- [x] Numa finalidade com reencode, uma foto JPEG com EXIF e GPS é gravada sem esses metadados e com o lado máximo respeitado; o SHA-256 gravado é o do conteúdo reencodado; o boot recusa finalidade com reencode que aceite WebP ou PDF.
- [x] `FilesApiTest` não usa `Thread.sleep` para ver a URL vencer; o logotipo do hotel continua com o mesmo comportamento (`HotelLogoTest` sem mudança de asserção).
- [x] `CLAUDE.md` e `README.md` atualizados; contrato regenerado; build e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1` (a parte nova é opcional).

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Memória ao decodificar fotos grandes: recusar imagem cujas dimensões declaradas passem de um teto antes de decodificar e registrar o teto e o heap no handoff.

**Rollback / migração:** a coluna nova é anulável e os anexos existentes continuam válidos; o expurgo só alcança anexos sem vínculo, então o logotipo vinculado nunca é apagado.

**Evidências e handoff:**

- Snapshot inicial: `b5033440250e860543216c2edfbe9152042279cd` (recebido do orquestrador; `HEAD` `282f356`, árvore limpa; E12 e E06 já commitadas e não tocadas). Snapshot ao fim da implementação: `5c5610623f7a5e6ca7ef2c0b3b7718ec67bcefe1` (35 arquivos, +2131/−130, antes deste handoff). Preparação: a E09 do M1 está `#concluida` e a `FilesApi` tinha só `find` e `attach`, como no plano. A investigação (fluxo do `files`, `afterCompletion` do upload e `javax.imageio`) foi feita direto, sem subagente (adendo do orquestrador).
- Arquivos alterados:
  - `files`, raiz: `FilesApi.kt` (`detach` e `delete`, KDoc do ciclo de vida), `FilePurposes.kt` (`FilePurpose.reencode` e `ImageReencoding`, com a recusa de WebP/PDF no `init`), `FilesErrorCodes.kt` (`FILE_IMAGE_UNREADABLE`, `FILE_IMAGE_TOO_LARGE`, `CLIENT_FILE_ID_INVALID`, `CLIENT_FILE_ID_CONFLICT`).
  - `files/domain/`: `StoredFile.kt` (`received_sha256`, `detached_at`, `deleted_at`, `detachFrom`, `deleteFor`, `isOrphanSince`, `purgeOrphan`), `FileContentRules.kt` (`ValidatedUpload.receivedSha256` e `withContent`) e `ImageReencoder.kt` (novo: reencode e `ExifOrientation`).
  - `files/application/`: `FilesService.kt` (upload com `clientFileId`, trava consultiva, repetição e reencode; `created_at` em microssegundos), `FilesApiService.kt` (`detach`, `delete`), `FileOrphanPurge.kt` (novo, `@Audited("files.file.purge-orphan")`), `StoredObjectRemovals.kt` (novo: remoção do objeto no rollback do upload e depois do commit da exclusão), `FilePorts.kt`, `FilesAudit.kt` e `FilesConfiguration.kt` (`zeny.files.orphan-retention`).
  - `files/infrastructure/`: `FilesPersistence.kt` (consultas só dos não apagados, órfãos vencidos, `pg_advisory_xact_lock`) e `FileOrphanPurgeJob.kt` (novo, ShedLock `files-orphans-purge`); `files/web/FilesController.kt` (parte `clientFileId`, `200` na repetição, descrições).
  - Banco: `db/migration/files/V2026_10_16_0430__add_stored_file_lifecycle.sql` (colunas anuláveis, `CHECK` do `received_sha256` e o índice parcial `stored_file_orphan_idx`). Configuração: `application.yaml` (`orphan-retention: PT24H`, `orphan-purge.interval`/`initial-delay`).
  - Testes: novos `FilesLifecycleTest` (9), `ImageReencoderTest` (6) e `TestImages`; alterados `FilesApiTest` (relógio controlado, URL do bucket assinada no passado, sem `Thread.sleep`), `FileContentRulesTest` (+2), `FilesTestSupport` (`clientFileId`, `presignedAt`, `objectExists`), `filesexample/FileExamples.kt` (finalidade `fileexamples.reencoded-photo`), `HotelLogoTest` (só o `@Import` com o `MutableClockConfiguration`, para continuar no contexto dos anexos; nenhuma asserção mudou), `IntegrationTest.kt` (`FILES_PURGE_DEFERRED`) e os três contextos montados à mão (`EventPublicationRegistryTest`, `RealtimeStreamTest`, `ProductionProfileApiTest`), que passam a adiar o expurgo também.
  - Contrato e documentação: `api/openapi.yaml` (parte `clientFileId`, resposta `200`, descrições), `CLAUDE.md` (estado atual, seção "Anexos" com reencode, upload repetível, `detach`/`delete` e expurgo, testes, jobs, regra da auditoria e testes de integração), `README.md` (ciclo de vida dos anexos e o `curl` com `clientFileId`), `docs/ARCHITECTURE.md` (§14) e este roadmap.
- Decisões (todas **decididas pela opção recomendada, por autorização do usuário em 2026-10-16**, para revisão posterior; alternativas descartadas entre parênteses):
  1. **Idade do órfão:** conta de `COALESCE(detached_at, created_at)`, com a coluna nova `detached_at` gravada pelo `detach` (o expurgo não apaga um anexo recém-desvinculado). (Contar só do `created_at`, que apagaria na hora uma foto trocada de uma execução antiga.)
  2. **`detach` e `delete` sobre outro dono:** `409 FILE_ALREADY_ATTACHED`, o código que já existia ("vinculado a outro registro"); `detach` de um órfão não muda nada; `delete` aceita o anexo do registro ou um órfão da finalidade. (Código novo para "não é deste registro"; `delete` só de anexo vinculado.)
  3. **Anexo apagado:** some de `find`, `GET /files/{id}`, `download-url`, `attach`, `detach` e `delete` (`404 FILE_NOT_FOUND`); a linha fica com o último dono. O download do servidor com um token ainda válido de um anexo apagado segue o caminho de anexo inexistente que já havia (`403 DOWNLOAD_URL_INVALID`). (Apagar a linha: H12 a mantém.)
  4. **Falha do armazenamento depois do commit:** só log (`WARN` com a chave, sem dado pessoal); o objeto fica no bucket sem linha viva que o sirva. (Job de nova tentativa ou coluna de "objeto removido": fica para o M8, com a métrica de falha do S3 já prevista.)
  5. **`clientFileId`:** UUIDv7 na forma canônica (senão `400 CLIENT_FILE_ID_INVALID`, campo `clientFileId`); um código só, `409 CLIENT_FILE_ID_CONFLICT`, para outro conteúdo, outra finalidade, outro usuário e anexo já apagado (o cliente faz o mesmo nos quatro: gera outro id); a repetição compara o SHA-256 **do recebido** (coluna nova `received_sha256`), porque o regravado pode mudar com a versão do JDK; a repetição não grava entrada na trilha (nada mudou); dois envios simultâneos se enfileiram por `pg_advisory_xact_lock` e o segundo recebe `200`. (Comparar o SHA-256 gravado; deixar a violação da chave primária virar `409`.)
  6. **Reencode:** atributo por finalidade, `ImageReencoding(maxSidePixels = 2048)` (de 16 a 8192), em vez de uma propriedade global; JPEG com qualidade 0,85; orientação EXIF aplicada aos pixels antes de descartar os metadados (sem isso, as fotos do celular sairiam deitadas); transparência do PNG mantida; teto de 50 MP conferido no cabeçalho (`400 FILE_IMAGE_TOO_LARGE`) e subamostragem na decodificação quando o lado passa do dobro do máximo; conteúdo ilegível `400 FILE_IMAGE_UNREADABLE`; o limite de tamanho da finalidade vale para o recebido. Memória (risco da etapa): pico do raster decodificado mais a imagem final, uns 36 MB + 12 MB numa foto de 12 MP e no máximo uns 64 MB + 16 MB; o heap da imagem é `-XX:MaxRAMPercentage=75.0` (Dockerfile). (Propriedade global; não aplicar a orientação; sem teto de pixels.)
  7. **Retenção e job:** `zeny.files.orphan-retention` de ao menos 1 h (o boot recusa menos), lotes de 100, até 10 por execução, um anexo por transação, conferido de novo com a linha travada (um `attach` simultâneo o preserva). O método do job não devolve nada: o ShedLock recusa (na chamada) método com retorno primitivo.
  8. **`created_at` do upload na precisão do banco** (microssegundos): a resposta do upload e a da repetição, lida do banco, ficam iguais.
  9. **Testes sem `Thread.sleep`:** `FILES_TEST_URL_TTL` passou de 4 s a 2 min (folga) e os testes de anexos (`FilesApiTest`, `FilesLifecycleTest`, `HotelLogoTest`) compartilham um contexto com o `MutableClockConfiguration`. A URL do servidor vence avançando o relógio da aplicação; a do bucket (o S3 assina pelo relógio da JVM, sem como injetar o `Clock`) é conferida pela janela assinada (`X-Amz-Date` dentro do pedido e `X-Amz-Expires` = TTL, os dois cobertos pela assinatura: mexer neles dá 403) e por uma URL legítima assinada no passado com as mesmas credenciais (`presignedAt`, `AwsV4HttpSigner` com `SIGNING_CLOCK`), que o MinIO recusa como "expired", com a de controle assinada agora dando 200. O relógio desses testes nunca fica à frente da hora real: eles recuam antes e voltam no fim. A primeira versão o deixava adiantado e o `HotelSettingsApiTest` falhou, porque a entrada da trilha do `HotelLogoTest` parecia mais nova (`lastAudit` ordena por `occurred_at`). Pelo mesmo motivo o `@IntegrationTest` adia o expurgo de órfãos (`FILES_PURGE_DEFERRED`).
  10. **Logotipo:** sem reencode e com o mesmo fluxo; o vinculado nunca é expurgado. Um logotipo enviado e não salvo no hotel em 24 h passa a ser expurgado, como qualquer órfão (H12).
  11. **Desvio de protocolo:** os testes foram escritos junto com a implementação e não rodaram vermelhos antes dela. Em compensação, as mutações abaixo mostram que eles pegam as falhas do foco do verificador.
- Validações executadas:
  - `./gradlew build` → `BUILD SUCCESSFUL` em 3 min 25 s (detekt com e sem tipos, ktlint, `test`, `generateOpenApi`, `checkOpenApi`, `koverVerify`): 569 testes em 71 classes, 0 falhas, 0 pulados. O `HkdfTokenSealerTest` não falhou nesta execução. Antes, duas rodadas falharam por `MaxLineLength`/`LongParameterList` (corrigidos) e uma pelo `HotelSettingsApiTest` (decisão 9, corrigida).
  - `./gradlew updateOpenApi` regravou o contrato (+25/−5: parte `clientFileId` opcional, resposta `200`, descrições). `.github/scripts/check-api-breaking.sh` (base `origin/main`, `7c9773a`) e `… HEAD` (`282f356`) → "Nenhuma mudança incompatível (nível ERR) em /api/v1".
  - Critério 1: `FilesLifecycleTest.detach turns an attached file into an orphan again…` (vínculo desfeito, `detached_at`, trilha `upload`/`attach`/`detach`, nada novo ao repetir, 409/422/404) e `delete marks the row and removes the object only after the commit…` (dentro da transação o `find` já é `null`, mas o objeto continua no bucket; depois do commit o objeto sumiu, `deleted_at` gravado, `GET /files/{id}` e `download-url` `404 FILE_NOT_FOUND`, `attach`/`detach`/`delete` 404, `files.file.delete` com `deletedAt`); `a delete in a transaction that rolls back keeps the row and the object…` (rollback: objeto, linha e download intactos, sem entrada).
  - Critério 2: `the purge deletes orphans older than the retention with a system audit entry, and keeps the others` (relógio recuado 48 h: órfão comum e sensível de 25 h apagados, objeto removido, `files.file.purge-orphan` `SUCCEEDED` sem usuário nem sessão; vinculado, recente de 2 h e desvinculado há 2 h preservados; `purge.purge` de um vinculado devolve `false`; 23 h depois, o recente e o desvinculado saem e o vinculado fica).
  - Critério 3: `an upload repeated with the same clientFileId returns the same file without storing it again` (201 com `Location` do id do cliente; repetição 200 com o mesmo corpo, uma linha, um objeto e uma entrada; outro conteúdo, outra finalidade e outro usuário 409 `CLIENT_FILE_ID_CONFLICT`; apagado, 409), `a clientFileId that is not a canonical uuid v7…` (`abc`, UUIDv4, `1-1-1-1-1` e sem hífens → 400 no campo) e `two simultaneous uploads with the same clientFileId store the file once` (201 e 200, uma linha, um objeto).
  - Critério 4: `ImageReencoderTest` (JPEG 3000 × 1000 com APP1 `Exif` com GPS e descrição → JPEG 2048 × 683 só com o APP0 JFIF, sem `Exif` nem o marcador; orientações 6, 8 e 3 aplicadas; PNG com `tEXt`, `eXIf` e HTML depois do IEND → só os chunks da imagem, sem sobra, transparência mantida; JPEG com comentário HTML e bytes depois do EOI → limpo; imagem menor não ampliada; 5000 px subamostrado; teto de 50 MP; ilegíveis) e `FilesLifecycleTest.a photo of a purpose with reencode is stored without exif or gps…` (no bucket, sem APP1 nem marcador, 2048 × 683; `sha256` e `size_bytes` do regravado, `received_sha256` do original; download entrega o regravado; repetição com `clientFileId` 200; poliglota PNG limpo) e `…refuses unreadable images and webp, and other purposes are not reencoded` (400 `FILE_IMAGE_UNREADABLE`, `FILE_IMAGE_TOO_LARGE`, `FILE_TYPE_NOT_ACCEPTED`; a foto comum guarda o conteúdo como chegou). `FileContentRulesTest.the boot refuses a purpose that reencodes images but accepts webp or pdf`.
  - Critério 5: `grep Thread.sleep` nos testes de anexos → 0; `FilesApiTest` com o relógio controlado (8/8); `HotelLogoTest` 3/3, diff só no `@Import`.
  - Mutações (cada uma revertida em seguida, `--tests 'com.zeny.hotel.files.*'`), todas pegas: objeto apagado antes do commit → 2 falhas; expurgo de anexo vinculado → 1; idade do órfão pelo `created_at` na consulta e no domínio → 1 (só no domínio, 0: a consulta também protege); expurgo sem retrato na trilha → 1; repetição aceita de qualquer usuário → 1; sem reencode → 2; sem a trava consultiva → 1 (a corrida); `find` enxergando apagados → 3; sem a orientação → 1.
  - Imagem do runtime: um programa de prova com o `ImageReencoder` compilado rodou em `eclipse-temurin:25-jre-alpine` (headless): JPEG e PNG de 3000 × 1000 → 2048 × 683.
- Parecer do verificador: **APROVADO** (2026-10-16, verificador independente disparado pelo orquestrador; snapshot verificado `7c3e7f2bae4d2a2154db7d4e65a1b4032c07344d`). Os 6 critérios APROVADOS com evidência:
  - `FilesLifecycleTest` (9/9): detach e delete auditados; objeto só removido no `afterCompletion` `COMMITTED`, conferido dentro da transação e no rollback; expurgo com ator sistema e relógio controlado; `clientFileId` com 201/200, 409 e corrida.
  - `ImageReencoderTest` (6/6): sem APP1, "Exif", GPS nem bytes após IEND/EOI.
  - `FileContentRulesTest`: o boot recusa WebP/PDF com reencode.
  - `FilesApiTest` sem `Thread.sleep`; `HotelLogoTest` sem mudança de asserção.
  - `./gradlew build` BUILD SUCCESSFUL com 569 testes e 0 falhas; oasdiff sem `ERR` (bases `origin/main` e `HEAD`).

  Sem problema grave ou médio. Baixos:
  1. Corrida improvável no rollback de um upload com `clientFileId`: a trava consultiva sai antes do `afterCompletion` apagar o objeto, e o `DELETE` atrasado de T1 pode apagar o objeto que T2 gravou com a mesma chave (nota para a E07 ou o M8).
  2. O teste da reconferência do expurgo não simula o vínculo entre a busca e o expurgo.
  3. Falta teste do download de um anexo sensível apagado (403 `DOWNLOAD_URL_INVALID`; o código cumpre).
  4. O vencimento da URL do bucket é provado de forma indireta (o SDK assina pelo relógio da JVM).
  5. Orientações EXIF 2, 4, 5 e 7 sem teste (conferidas à mão).

  Informativo: as 11 decisões foram tomadas pela opção recomendada, sem entrevista, por autorização do usuário ao orquestrador nesta execução (2026-10-16). Elas ficam para a revisão humana.
- Notas para a próxima etapa:
  - E07 (fotos de checklist): declarar `ChecklistsFilePurposes` com `checklists.photo`, `acceptedTypes = setOf(JPEG, PNG)` e `reencode = ImageReencoding()` (WebP é recusado no boot). O app envia a foto com `clientFileId` (UUIDv7) antes do comando; a repetição dá 200 e o conflito 409 (gerar outro id). O handler confere a foto com `FilesApi.find` (apagada ou expurgada = `null`: aplicar com alerta, sem a foto, H10), vincula com `attach` na transação e, ao trocar ou retirar a foto, chama `detach` (o expurgo a apaga 24 h depois) ou `delete`.
  - Uma foto enviada e não vinculada em 24 h é expurgada: o comando offline que a cita mais de 24 h depois do envio cai no caso "foto inexistente" da H10.
  - M8: falha do S3 depois do commit só vai para o log (decisão 4); entra na métrica de falha do S3 e, se preciso, numa nova tentativa.

---

## E07 — Execução de checklists e rotinas — #concluida

**Objetivo:** permitir executar checklists online e offline, com fotos, preservando os horários reais, publicar `ChecklistCompleted` com as respostas que têm ações, e gerar rotinas recorrentes por setor e escala após `BusinessDateAdvanced`.

**Por que agora:** é o fluxo de ponta a ponta do marco (ARCHITECTURE §6.2) e a base das tarefas de governança e manutenção em M6.

**Dependências e paralelismo:** E05, E06, E11, E12. Não paralelizável.

**Escopo:**

- `ChecklistExecution` (versão, sujeito por tipo e ID opaco, executor, início, conclusão, dispositivo, `clientCommandId`) e `ChecklistAnswer` — SPEC §8.2.11.
- Permissões novas em `ChecklistsPermissions` (executar e ler execuções, com `OWN` sugerido a `ROOM_ATTENDANT`, `MAINTENANCE` e `WAITER` e `ALL` a `HOUSEKEEPER` e `MANAGER`).
- Online: `POST /api/v1/checklist-executions` (iniciar, com sujeito e contexto), `PUT …/{id}/answers`, `POST …/{id}/complete`; validação de obrigatórios considerando condicionais.
- Fotos: `ChecklistsFilePurposes` (`checklists.photo`, JPEG e PNG, com reencode da E11, `uploadPermission` = executar, `downloadPermission` = ler execuções, H15) com o bean; a resposta de foto guarda o `FileId`, conferido com `FilesApi.find` e vinculado com `FilesApi.attach` na transação; foto trocada ou retirada é desvinculada (`FilesApi.detach`).
- Offline: handlers `StartChecklistExecution` e `CompleteChecklistExecution` (com respostas, fotos por `clientFileId` e horários) registrados na porta da E05, usando o UUID do cliente como ID; foto ainda não enviada → aplicado com alerta, sem a foto (H10); fonte de mudanças com as versões aplicáveis ao usuário e as rotinas atribuídas a ele.
- `ChecklistCompleted` em `checklists/ChecklistsEvents.kt`, pelo outbox, com execução, sujeito, versão e as respostas que disparam ações (tipo da ação e parâmetros). A API pública também permite que um módulo dono do sujeito (governança em M6) inicie e conclua execuções.
- Rotinas (RF-CHK-08, H14): `RecurringChecklist` (modelo, local opcional, setor `DepartmentId`, frequência, horário); listener `@ApplicationModuleListener` de `BusinessDateAdvanced` (D1), idempotente por rotina e data, que usa `StaffApi.daySchedule(current, departmentId)` para os candidatos.
- Escopo `OWN` para o executor e os candidatos verem as próprias execuções (`OwnedResource.of(owners)` e `scopeFilter` na SQL).
- `ModularityTest`: `checklists depends only on shared, hotel, staff and files, and only on their public APIs`.

**Fora de escopo:** tarefas de governança e inspeção (M6); consumidores das ações (M6, M9); relatórios (E08).

**Passos de implementação:**

1. Escrever testes: execução online com obrigatórios e condicionais, foto vinculada e desvinculada, conclusão offline repetida (idempotência), foto ausente com alerta, horários preservados com correção de relógio, `ChecklistCompleted` com as ações (teste `Scenario`), geração de rotinas a partir da escala (com `StaffTestSupport` e `HotelApi.advanceBusinessDate`) e a mesma virada de data entregue duas vezes.
2. Implementar domínio, migrações, casos de uso, finalidade de foto, handlers, fonte de mudanças, listener de `BusinessDateAdvanced` e controllers.
3. Regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "o que RF-CHK-05, 07 e 08, RN-22, ARCHITECTURE §6.2, §9 e §13 e SPEC §8.3 dizem sobre execução offline, ações e rotinas?"; "como registrar handler e fonte de mudanças segundo o `CLAUDE.md` (handoff da E05) e como a E11 expõe `clientFileId`, reencode e `detach`?".
- Partes paralelas: nenhuma.
- Foco do verificador: `ChecklistCompleted` publicado duas vezes para a mesma execução; conclusão com obrigatório visível sem resposta; horário do servidor no lugar do horário real; rotina gerada para colaborador de folga ou duplicada na reentrega do evento; foto de outra finalidade ou de outro usuário aceita; dependência de módulo não registrada na ARCHITECTURE.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E07 — "Execução de checklists e rotinas" do roadmap docs/roadmaps/roadmap-m2-plataforma-operacional.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Restrições e invariantes", as decisões D1, D2, H10, H14 e H15 (confirmadas pelo usuário) e o "Protocolo de execução" —, a seção E07 inteira, o "Evidências e handoff" de E05, E06, E11 e E12 e o handoff de E07 (escala) de docs/roadmaps/roadmap-m1-acesso-configuracao.md. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E05, E06, E11 e E12 estão #concluida e que o código bate com os handoffs; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: executar checklists online (/api/v1/checklist-executions: iniciar, responder, concluir com validação de obrigatórios e condicionais) e offline (handlers StartChecklistExecution e CompleteChecklistExecution e fonte de mudanças registrados na sincronização), com fotos da finalidade checklists.photo (reencode, FilesApi.find, attach e detach; foto ausente → aplicado com alerta), preservando os horários reais; publicar ChecklistCompleted uma única vez com as respostas que têm ações; gerar rotinas por setor e escala por um listener idempotente de BusinessDateAdvanced (D1), uma execução por rotina e data, com os candidatos escalados pela StaffApi.daySchedule e sem endpoint de geração (H14). Não implemente tarefas de governança nem consumidores das ações.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E07 para #em-andamento no índice e no título;
- investigação: um subagente Explore responde às perguntas da seção E07 (RF-CHK-05, 07 e 08, RN-22, ARCHITECTURE §6.2, §9 e §13, SPEC §8.3; handlers, fontes e anexos conforme os handoffs de E05 e E11);
- escreva os testes primeiro (online, fotos, offline repetido, horários, ChecklistCompleted com Scenario, rotinas e evento reentregue), implemente, atualize o ModularityTest e o CLAUDE.md, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em evento duplicado, obrigatório sem resposta, horário do servidor no lugar do real, rotina para colaborador de folga ou duplicada, foto indevida aceita e dependência não registrada.

Critérios de aceite (detalhes na seção E07):
- conclusão sem item obrigatório visível → erro com code estável; item oculto por condição não é exigido;
- CompleteChecklistExecution sincronizado duas vezes gera uma execução e um ChecklistCompleted;
- completedAt reflete o horário do dispositivo corrigido; fotos vinculadas e reencodadas;
- rotinas geradas só para colaboradores escalados, uma vez por data; contrato regenerado; build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E07 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Concluir sem resposta a item obrigatório visível → erro com `code` estável; item obrigatório oculto por condição não é exigido.
- [ ] `CompleteChecklistExecution` enviado duas vezes (mesmo `commandId`) gera uma única execução e um único `ChecklistCompleted`.
- [ ] `startedAt` e `completedAt` refletem os horários do dispositivo corrigidos pela defasagem, não o horário de chegada ao servidor.
- [ ] `ChecklistCompleted` contém as respostas com ações configuradas (tipo e parâmetros) e é observado num teste `Scenario`.
- [ ] Uma foto enviada com `clientFileId` é vinculada à execução e gravada sem metadados; foto de outra finalidade é recusada com `code` estável; foto citada e ainda não enviada deixa o comando "aplicado com alerta".
- [ ] Após `BusinessDateAdvanced`, as rotinas do dia são geradas para os colaboradores escalados no setor e não para os de folga; o mesmo evento entregue de novo não duplica.
- [ ] O executor com escopo `OWN` vê só as próprias execuções (e as rotinas em que é candidato).
- [ ] `ModularityTest` mostra `checklists` dependendo só de `shared`, `hotel`, `staff` e `files`, pela API pública; `ApplicationModules.verify()` passa.
- [ ] Contrato regenerado; build e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Execução offline sobre versão de modelo já substituída: aceitar e guardar a versão usada (RF-CHK-06), com teste.
- Rotinas não aparecem em produção até o M6 (nada avança a data): registrar no handoff para o gate e para o M6.

**Evidências e handoff:**

- Snapshot inicial: `412af5f6b61264882c43d6a18e6d9e806a7ee70f` (recebido do orquestrador; `HEAD` `00700aa`, árvore limpa). Snapshot ao fim da implementação, antes deste handoff: `2087103d0c74cf7c069ce48452140a4ca55c068f` (36 arquivos, +6776/−78 contra o inicial, com a troca da tag). Preparação: E05, E06, E11 e E12 estão `#concluida` e o código bate com os handoffs (`OfflineSync.kt` com `OfflineCommandHandler`/`OfflineChangeSource`/`OfflineCommand.deviceOccurredAt`; `ChecklistsApi` com `resolveTemplate` e `version`; `FilesApi` com `find`, `attach`, `detach` e `delete`, `FilePurpose.reencode`, `clientFileId`; outbox com backoff e `republish-outstanding-events-on-restart` desligado; nenhum `@ApplicationModuleListener` de produção). A investigação (SPEC §5.15, §6 RN-22, §8.2.11 e §8.3; ARCHITECTURE §3.1, §6.2, §9 e §13; handoffs de E05 e E11; `OfflineCommandService`, `FilesApi`, `StaffApi`, `HotelApi`) foi feita pela própria sessão, sem `Explore` (adendo do orquestrador). A ARCHITECTURE §3.1 já registrava `checklists` → `shared, hotel, staff, files`: nenhuma aresta nova.
- Arquivos alterados:
  - `checklists`, raiz (API pública): `ChecklistExecutions.kt` (novo: `ChecklistExecutionId`, `ChecklistRoutineId`, `ChecklistExecutionStatus`, `ChecklistSubject`, `ChecklistAnswerInput`/`ChecklistAnswerView`, `ChecklistExecutionView`, `ChecklistCompletion`, `RoutineFrequency`, `RoutineDefinition`, `ChecklistRoutineView`), `ChecklistsEvents.kt` (novo: `ChecklistCompleted`, `ChecklistTriggeredAction`), `ChecklistsFilePurposes.kt` (novo: `checklists.photo`), `ChecklistsOffline.kt` (novo: fontes e tipos de comando), `ChecklistsApi.kt` (`execution`, `startExecution`, `completeExecution`), `ChecklistsPermissions.kt` (4 permissões novas), `ChecklistsErrorCodes.kt` (códigos e alertas novos), `ChecklistsModule.kt` (KDoc).
  - `domain/`: `ChecklistExecution.kt` (agregado, `RequiredItemsMissingException`), `ChecklistAnswers.kt` (`ChecklistAnswer`, `ChecklistAnswerRules`: formato, visibilidade, obrigatórios, ações), `ChecklistRoutine.kt` (agregado, `RoutineRules`, candidatos pela escala).
  - `application/`: `ExecutionOperations.kt` (passos compartilhados: criar, assumir, registrar à parte, fotos, trocar respostas, concluir e publicar), `ChecklistExecutions.kt` (casos de uso online e os da API pública), `ChecklistOfflineHandlers.kt` (os dois handlers e os payloads), `ChecklistRoutines.kt` (cadastro, geração e o listener), `ChecklistsPorts.kt`, `ChecklistsAudit.kt` (ações e retratos), `ChecklistTemplates.kt` (bean da finalidade; `ChecklistsApiService` delega as execuções).
  - `infrastructure/`: `JdbcChecklistExecutions.kt` (execuções, respostas e rotinas por `JdbcClient`), `ChecklistsOfflineSources.kt` (as duas fontes). `web/`: `ChecklistExecutionsController.kt`, `ChecklistRoutinesController.kt`, `ChecklistExecutionsDtos.kt`.
  - Banco: `db/migration/checklists/V2026_10_16_1440__create_checklist_executions.sql` (`checklist_routine`, `checklist_execution`, `checklist_answer`, `CHECK`s, `checklist_execution_routine_day_uk` parcial, `refuse_delete` nas duas, triggers `shared.mark_offline_change()` na execução e no modelo, com o preenchimento dos modelos existentes). Privilégios pelo `ALTER DEFAULT PRIVILEGES` da E06 (nenhuma tabela *append-only* nova).
  - Testes: novos `checklists/ChecklistExecutionDomainTest.kt` (9), `ChecklistExecutionsApiTest.kt` (9), `ChecklistOfflineTest.kt` (5), `ChecklistRoutinesTest.kt` (3) e `ChecklistExecutionsTestSupport.kt` (apoio, `ChecklistExecutionsTestConfiguration` com o listener de teste de `ChecklistCompleted`); ajustados `ModularityTest.kt` (lista exata `shared`, `hotel`, `staff`, `files`), `checklists/ChecklistTemplatesApiTest.kt` (catálogo com as 6 permissões e os caminhos novos, sem endpoint de geração), `iam/AuthorizationTest.kt` (concessões de Gerente e Camareira), `hotel/BusinessDateTest.kt` (a publicação conferida é a do listener de teste: o das rotinas também recebe o evento).
  - Contrato e documentação: `api/openapi.yaml` (regenerado, só acréscimos: `/api/v1/checklist-executions…`, `/api/v1/checklist-routines…` e os esquemas), `CLAUDE.md` ("Estado atual", seção nova "Execução de checklists e rotinas", "Modelos de checklist" e "Eventos e jobs"), este roadmap (tag e handoff).
- Decisões (tomadas pelo implementador dentro de D1, H10, H14 e H15; **para revisão humana**, alternativas descartadas entre parênteses):
  1. **Persistência:** `JdbcClient`, com as respostas numa tabela própria (`checklist_answer`, uma linha por item, valores em colunas), pensando nos relatórios da E08 (itens mais reprovados); a execução e a rotina nunca são apagadas. (Respostas em `jsonb` na execução.)
  2. **Dois comandos, como no escopo:** `start-execution` cria (id = `commandId`) ou assume a pendente; `complete-execution` exige a execução iniciada e traz **todas** as respostas. O início offline exige o `versionId` que o app usou (RF-CHK-06): versão já substituída é aceita e guardada, com o alerta `CHECKLIST_VERSION_OUTDATED`. (Um só comando que inicia e conclui; o servidor escolher a versão mais nova.)
  3. **Foto obrigatória ainda não enviada (nota da E06):** offline e pela API pública, o comando é aplicado com o alerta `CHECKLIST_PHOTO_MISSING`, a resposta fica com `photoMissing` e conta como respondida no obrigatório (H10, RN-22); online, `422 CHECKLIST_PHOTO_NOT_FOUND` (o usuário reenvia). (Rejeitar o comando, perdendo o checklist feito.)
  4. **Foto inválida:** outra finalidade, enviada por outro usuário (o dono tem de ser o executor) ou vinculada a outro registro → `422 CHECKLIST_PHOTO_INVALID` online, conferida antes de chamar `attach`, para o código ser do `checklists`. **Substituída na revisão humana** (abaixo): offline e pela API pública, o comando passou a ser aplicado sem a foto, com alerta.
  5. **Item oculto pela condição:** a resposta dele sai na conclusão (a foto é desvinculada) e não dispara ação; antes da conclusão, a troca de respostas aceita respostas a itens ainda ocultos. (Recusar a resposta a item oculto.)
  6. **Só o executor responde e conclui**, mesmo com `ALL` (`403 CHECKLIST_EXECUTOR_REQUIRED`): o executor é um fato da execução. Com `OWN`, os donos são o executor e os candidatos (os candidatos continuam vendo a rotina assumida por outro).
  7. **Rotina já assumida por outra pessoa, offline (RN-22):** a execução do usuário é registrada à parte (`duplicate_of`, id = `commandId`, mesma rotina, data e candidatos), com o alerta `CHECKLIST_ROUTINE_ALREADY_TAKEN`; o `complete-execution` que cita a original conclui a dele. Online, `409 CHECKLIST_EXECUTION_ALREADY_STARTED`. (Rejeitar e descartar o checklist feito.)
  8. **Versão da rotina na partida**, não na geração: a pendente guarda só o modelo; quem assume online usa a última publicada, o app offline cita a que tem. Rotina de modelo desativado ou nunca publicado não gera. Pendente sem candidato também é gerada (só quem tem `ALL` a vê).
  9. **Candidatos (H14):** colaborador ativo, com usuário, escalado no setor num turno que cobre o horário; o turno da véspera que atravessa a meia-noite cobre a madrugada (a escala dá o turno na data em que começa). `due_at` = a data operacional no horário da rotina, no fuso do hotel.
  10. **Permissões novas de rotina** (`checklists.routines.read`/`manage`, só `ALL`, Gerente e Governanta), separadas das de modelo. Execuções: `read` e `execute` com `OWN` (Camareira, Manutenção, Garçom) e `ALL` (Gerente, Governanta). Nenhuma sensível. Frequências só `DAILY` e `WEEKLY`.
  11. **Fontes offline:** `checklist-templates` por modelo (a última versão publicada no formato do contrato; desativado é *tombstone*), com os textos do modelo (rótulos escritos por quem o mantém, necessários para executar; nunca dado de hóspede, RN-13); `checklist-executions` sem as respostas, concluída é *tombstone*; ambas pedem `checklists.executions.execute`. O conteúdo de uma versão antiga em andamento vem do `GET /checklist-executions/{id}` (com a versão usada).
  12. **Evento:** `ChecklistTriggeredAction` plano (tipo e só os parâmetros dele), porque o outbox desserializa o evento no reenvio e o `ItemAction` *sealed* não tem informação de tipo; ação com quantidade maior que zero ou `NOT_OK`, com a observação.
  13. **Contrato:** `checklistVersion` some do JSON na pendente (`NON_NULL`) em vez de `nullable`: no OpenAPI 3.0 o `nullable` num `$ref` marcava o `ChecklistVersionResponse` inteiro como anulável, e o oasdiff acusou `response-body-became-nullable` em duas operações da E06 (corrigido antes do fim). Online sem `Idempotency-Key` (não é financeiro nem externo).
  14. **Auditoria:** retrato com o valor discreto das respostas; texto e observação só como "tem". Geração com ator sistema, uma entrada por execução criada (a reentrega grava só a ação).
  15. **Decididas pelo usuário em 2026-10-16** (entrevista da revisão humana, conduzida pelo orquestrador):
      - (a) A execução registrada à parte de uma rotina (`duplicate_of`) continua publicando o próprio `ChecklistCompleted` com as ações; o evento passou a levar `duplicateOf` (já levava `routineId`), e os consumidores do M6/M9 decidem por eles (notas abaixo).
      - (b) A fonte `checklist-templates` leva os rótulos e títulos do modelo: exceção aceita à regra "nada de texto livre" das fontes (texto de configuração do gestor, nunca dado de hóspede), registrada no `CLAUDE.md` (sincronização offline e checklists).
      - (c) O `note` da resposta continua em claro em `checklist_answer.note` e no `ChecklistCompleted`.
      - (d) O colaborador ativo cujo usuário foi desativado continua candidato das rotinas (impacto aceito).
      - (e) As decisões 1, 2, 3, 5 e 6 acima estão aceitas como implementadas; a 4 mudou (item seguinte).
      - Foto inválida no `complete-execution` (e pela API pública): aplicado sem a foto, com o alerta `CHECKLIST_PHOTO_INVALID`, sem vincular nada (RN-22, H10); online continua `422`. **Decisão do implementador** sobre o item de foto obrigatório: a resposta fica com `photoMissing` (agora "a foto citada não ficou com a resposta": não chegou ou não servia; o alerta diz qual) e conta como respondida, como a foto que não chegou; a API pública devolve as chaves em `ChecklistCompletion.rejectedPhotos`.
      - `ChecklistsApi.startExecution` com id já usado: `409 CHECKLIST_EXECUTION_ALREADY_EXISTS` (violação de `checklist_execution_pk` reconhecida pelo nome da restrição na cadeia de causas, como os adaptadores do `iam`, `staff` e `rooms`), em vez de `500`.
  16. **Desvio de protocolo:** os testes foram escritos depois do esqueleto da implementação, não antes. Para compensar, as mutações abaixo (cada uma revertida) mostram que eles pegam as falhas do foco do verificador.
- Validações executadas (OpenJDK 25, Docker):
  - `./gradlew test --tests 'com.zeny.hotel.checklists.*'` → 48 testes (22 da E06 + 26 novos), 0 falhas.
  - Mutações (`/tmp`, cada uma revertida; todas pegas):
    - obrigatório sem considerar a visibilidade → 12 falhas (domínio, API e offline);
    - conclusão de execução já concluída aceita (evento em dobro) → 4 (domínio, `ALREADY_COMPLETED` na API e no offline, com a contagem das publicações);
    - horário do servidor no `complete-execution` e no `start-execution` → 1 cada (`a checklist done offline and synchronized twice…`);
    - candidatos sem conferir o turno → 2 (domínio e `after BusinessDateAdvanced…`);
    - geração sem `ON CONFLICT` (rotina duplicada na reentrega) → 2 (`DuplicateKeyException`);
    - foto sem conferir a finalidade → 1 (`FILE_PURPOSE_MISMATCH` no lugar do código estável); sem conferir o autor → 1; sem conferir outro dono → 1 (a primeira versão do teste não isolava as três: o logotipo era do administrador e a foto do outro usuário já estava vinculada; o teste foi reescrito com o executor podendo enviar o logotipo, a foto alheia sem dono e a própria foto vinculada a outra execução);
    - filtro `OWN` da listagem → 2; filtro `OWN` da fonte de execuções → 1.
  - `./gradlew ktlintFormat` e `./gradlew lint` → ajustes (linhas longas, `ReturnCount`, `LongMethod`, supressões com justificativa) → sem problemas.
  - `./gradlew updateOpenApi` → contrato só com acréscimos.
  - `./gradlew build` → `BUILD SUCCESSFUL in 4m 28s`: 595 testes, 0 falhas, 0 ignorados (`ModularityTest`, ArchUnit, `MigrationConventionTest`, `DatabaseMigrationsTest`, `DatabaseRolesTest`, `ApiSecurityTest`, `checkOpenApi`, `detekt*`, `ktlint*Check` e `koverVerify`). Antes, uma rodada falhou nos dois testes da E06 com listas exatas (catálogo de permissões e caminhos), ajustados, e outra só no `ktlintMainSourceSetCheck` (ordem de importação), corrigida.
  - `.github/scripts/check-api-breaking.sh` → "Nenhuma mudança incompatível (nível ERR) em /api/v1", contra `00700aabe477` (merge-base com `origin/main`) e contra `HEAD`.
- Critérios de aceite (evidência para o verificador; marcados pelo orquestrador depois do parecer):
  - obrigatório visível e oculto: `ChecklistExecutionsApiTest.a required visible item blocks the completion, and a hidden one is not required` (`422 CHECKLIST_REQUIRED_ITEMS_MISSING` com `missingItems`; o detalhe oculto não exigido e descartado) e `ChecklistExecutionDomainTest.only visible required items without an answer are missing`;
  - repetido offline: `ChecklistOfflineTest.a checklist done offline and synchronized twice is applied once, with the device times corrected` (`replayed`, uma execução, um evento recebido e uma publicação) e `offline refusals carry stable codes and never publish twice`;
  - horários: o mesmo teste (`startedAt`/`completedAt` = os `occurredAt` corrigidos, defasagem de ~2 h, fim uns 5 min antes do recebimento, `answeredAt` corrigido);
  - `ChecklistCompleted` com as ações pelo `Scenario`: `completing publishes ChecklistCompleted once, with the answers that trigger actions` (as três ações com tipo e parâmetros) e `nothing is triggered by a zero quantity or a passing check`;
  - fotos: `a photo is reencoded, attached to the execution and detached when replaced` (sem APP1 nem o marcador de GPS, 2048 px, `attach`/`detach`), a foto pelo `clientFileId` no teste offline, `a photo of another purpose, of another user or that does not exist is refused` e `a photo not yet uploaded leaves the command applied with a warning, without the photo`;
  - rotinas: `ChecklistRoutinesTest.after BusinessDateAdvanced the routines of the day go to the scheduled staff only, once` (escalado sim; folga, outro turno, outro setor e sem usuário não; o turno da véspera; o evento republicado e a geração repetida não duplicam) e `an offline start of a routine someone else took is recorded apart and completed, never discarded`;
  - `OWN`: `with OWN scope the executor sees and changes only their own executions` e o trecho de `OWN` do teste das rotinas (o candidato vê e assume; quem não é candidato, `404`/`403`);
  - `ModularityTest.checklists depends only on shared, hotel, staff and files, and only on their public APIs` e `module structure is valid`; contrato, build e oasdiff acima.
- Parecer do verificador: **APROVADO** (2026-10-16, verificador independente disparado pelo orquestrador; snapshot verificado `1bd741f8753c759704d5bd761b6ac30bff8df64f`, 36 arquivos, +6830/−84, todos no escopo). Os 9 critérios APROVADOS com evidência: `ChecklistExecutionsApiTest` (obrigatório visível → `422 CHECKLIST_REQUIRED_ITEMS_MISSING`, oculto não exigido; `ChecklistCompleted` uma vez num `Scenario` com as três ações; foto reencodada, sem metadados, e desvinculada; foto de outra finalidade, de outro usuário ou inexistente recusada), `ChecklistOfflineTest` (o mesmo `commandId` duas vezes dá uma execução e uma publicação no outbox; horários do aparelho corrigidos pela defasagem; foto ausente → `APPLIED_WITH_WARNINGS CHECKLIST_PHOTO_MISSING`), `ChecklistRoutinesTest` (só os escalados no turno, nada para quem está de folga, sem duplicar na reentrega; `OWN` para candidato e executor), `ModularityTest` com a lista exata `shared, hotel, staff, files`. `./gradlew build` BUILD SUCCESSFUL (595 testes, 0 falhas); oasdiff sem `ERR` contra `00700aa`. Nenhum problema grave. Pontos menores para a revisão humana:
  1. A execução registrada à parte (`duplicate_of`, RN-22) também publica o próprio `ChecklistCompleted` com as ações: uma rotina feita por duas pessoas offline gera dois eventos e pode baixar estoque ou enxoval em dobro. Os consumidores do M6/M9 precisam tratar `duplicateOf`/`routineId`.
  2. O complete offline com foto de outra finalidade ou de outro usuário é `REJECTED` inteiro, e com isso o checklist feito se perde. Isso conflita um pouco com RN-22; a alternativa é aplicar sem a foto, com alerta.
  3. A fonte `checklist-templates` leva os rótulos do modelo ao aparelho. O `CLAUDE.md` diz "nada de texto livre" nas fontes; a exceção precisa de aceite explícito.
  4. O `note` da resposta "não ok" vai em claro no `ChecklistCompleted` (`shared.event_publication`) e em `checklist_answer.note`. Pode conter dado pessoal digitado.
  5. `ChecklistsApi.startExecution` com um `id` já existente provavelmente dá 500 (`DuplicateKeyException`) em vez de um código estável. Ainda não há chamador (M6).
  6. Um colaborador ativo com o usuário desativado no `iam` continua candidato das rotinas. O impacto é baixo, porque o usuário não tem permissões.
  7. Desvio de protocolo declarado: os testes vieram depois do esqueleto, compensados por mutações. A leitura confirma que os testes exercitam o comportamento.
  8. `ChecklistExecutionsApiTest` reserva o contexto `RESTAURANT` (desativa esses modelos no banco compartilhado).
- Parecer da 2ª verificação, depois dos ajustes da revisão humana: **APROVADO** (2026-10-16, verificador independente novo, disparado pelo orquestrador). O snapshot verificado é `db1e5be522b7bc3f0e3a324e44652565f6fdc5ad`: 36 arquivos e +7040/−85 contra `412af5f`, e +277/−76 contra `6850c24`, que são as duas mudanças pedidas mais o `duplicateOf`. Os 9 critérios estão APROVADOS. `./gradlew build` deu BUILD SUCCESSFUL (597 testes, 0 falhas), e `check-api-breaking.sh`, contra o merge-base e contra `HEAD`, não achou mudança `ERR`.
  - **Foto inválida:** não fica vinculada nem aparece na resposta, pelo `withoutPhoto()` e pelo `CHECK checklist_answer_photo_ck`. O caminho online continua com `422`.
  - **Id repetido:** o `409 CHECKLIST_EXECUTION_ALREADY_EXISTS` só converte `23505` em `checklist_execution_pk`; as outras violações são relançadas.
  - **Graves:** nenhum. **Menores, para o M6:**
    1. O teste offline da foto inválida não isola "só outra finalidade" nem "foto do executor já vinculada a outra execução". A classificação é a mesma do caminho online, que isola os três casos.
    2. No caminho tolerante, qualquer `photoFileId` satisfaz um item de foto obrigatório, porque `photoMissing` conta como respondido (decisão aceita).
    3. `ChecklistsApi.startExecution` com id repetido, dentro da transação de quem chama, deixa essa transação abortada no PostgreSQL. Capturar o `409` e seguir não funciona; está documentado no KDoc de `ChecklistExecutionStore.add`.
- Ajustes da revisão humana (2026-10-16; snapshot antes `6850c242695045c54dc9bc3f081e8270b04fc630`, depois `a962a98df6bb927457f2efc8aaf89ab8b147f1aa`, 14 arquivos, +262/−74):
  - Arquivos: `application/ExecutionOperations.kt` (`PhotoPolicy` no lugar de `MissingPhotoPolicy`, `PhotoIssues` com as fotos que não chegaram e as recusadas; a inválida é tolerada com `TOLERATE`; inserção com `409` no id repetido), `application/ChecklistOfflineHandlers.kt` (alerta `CHECKLIST_PHOTO_INVALID`), `application/ChecklistExecutions.kt` (`rejectedPhotos`), `application/ChecklistsPorts.kt` e `infrastructure/JdbcChecklistExecutions.kt` (`add` devolve `false` na violação de `checklist_execution_pk`), `ChecklistExecutions.kt` (`ChecklistCompletion.rejectedPhotos`, KDoc do `photoMissing`), `ChecklistsEvents.kt` (`duplicateOf`, com padrão nulo), `ChecklistsErrorCodes.kt` (`CHECKLIST_EXECUTION_ALREADY_EXISTS`; `CHECKLIST_PHOTO_INVALID` também como alerta), `web/ChecklistExecutionsDtos.kt` (descrição do `photoMissing`), `api/openapi.yaml` (só essa descrição), `CLAUDE.md` (fotos, offline, evento, fontes e a exceção do texto livre, candidatos, API pública e testes) e os testes.
  - Testes: `ChecklistOfflineTest.an invalid photo leaves the offline command applied with a warning, without the photo` (novo: o logotipo do administrador e a foto de checklist de outro usuário, num item de foto obrigatório, dão `APPLIED_WITH_WARNINGS CHECKLIST_PHOTO_INVALID`, `photoMissing`, execução concluída e nada vinculado); `offline refusals carry stable codes and never publish twice` sem o caso da foto; `ChecklistExecutionsApiTest.the public API refuses an id already used with 409, and tolerates an invalid photo` (novo); `ChecklistRoutinesTest.an offline start of a routine someone else took…` confere `duplicateOf` e `routineId` no evento da registrada à parte.
  - Validações: com a foto inválida recusada de novo no `TOLERATE` (mutação revertida), os dois testes novos falharam (`expected:<APPLIED_WITH_WARNINGS> but was:<REJECTED>` e a `BusinessRuleException`); sem reconhecer a violação da chave primária, o teste do `409` falhou com `DuplicateKeyException`. `./gradlew test --tests 'com.zeny.hotel.checklists.*'` → 50 testes, 0 falhas; `./gradlew ktlintFormat` e `lint` → sem problemas (o `attachPhoto` foi reescrito por `ReturnCount`); `./gradlew updateOpenApi` → só a descrição do `photoMissing`; `./gradlew build` → `BUILD SUCCESSFUL in 4m 29s`, 597 testes, 0 falhas, 0 ignorados; `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`, contra `00700aabe477` (merge-base) e contra `HEAD`.
- Notas para a próxima etapa:
  - M6/M9 (consumidores de `ChecklistCompleted`, decisão (a) do usuário): a execução registrada à parte de uma rotina chega com `duplicateOf` (a original) e `routineId`; decidir ali se as ações dela se aplicam (não baixar estoque nem movimentar enxoval em dobro quando a original também for concluída).
  - E08 (relatórios): as respostas estão em `checklists.checklist_answer` (uma linha por item, `check_value` para "não ok", `item_type`), a execução em `checklist_execution` (versão usada, sujeito, rotina, `business_date`, `due_at`, horários reais); as pendentes de rotina não feitas ficam `PENDING` com a data. Publicar as `report_*` sobre elas.
  - M6 (governança e manutenção): iniciar e concluir pela `ChecklistsApi` (`startExecution`/`completeExecution`, sem checagem de permissão; a foto que não chegou volta em `missingPhotos`); os consumidores de `ChecklistCompleted` (M6, M9) deduplicam por `executionId` + `itemKey` + tipo da ação. As rotinas só aparecem no compose quando o fechamento (M6) avançar a data (H14); até lá, a evidência é de teste.
  - Testes com `Scenario.stimulate` rodam o estímulo numa transação do Modulith: o caso de uso chamado pelo `MockMvc` entra nela (o commit é no fim do estímulo).
  - Pendências sem destino: o `HkdfTokenSealerTest` instável (E06) não falhou nas rodadas desta etapa; a corrida improvável do rollback com `clientFileId` (E11) segue anotada para o M8.

---

## E08 — Relatórios de checklists — #pendente

**Objetivo:** entregar os relatórios do RF-CHK-09 — conformidade por modelo, itens mais reprovados e histórico por local — a partir de views de leitura publicadas por `checklists`, no padrão que o `reporting` usará em M6.

**Por que agora:** fecha o requisito de checklists da Fase 0 e estabelece o primeiro exemplo de view `report_*` (ARCHITECTURE §11).

**Dependências e paralelismo:** E07. Pode correr em paralelo com E09 (em worktree separado); conflito de edição em `api/openapi.yaml` e `CLAUDE.md`.

**Escopo:**

- Views SQL `checklists.report_*` (a criar) para conformidade, itens reprovados e histórico por local, numa migração nova de `checklists`, documentadas como API de leitura do módulo; o papel da aplicação recebe `SELECT` nelas (os `ALTER DEFAULT PRIVILEGES` de tabelas da pasta cobrem views; conferir no teste).
- Endpoints de leitura em `/api/v1/checklist-reports` com filtros de período, modelo e local, via `JdbcClient` (D5), com permissão própria (`checklists.reports.read`).
- Exportação CSV opcional se simples; caso contrário, fica para M6 (RF-REL-07).

**Fora de escopo:** o módulo `reporting` e painéis gerenciais (M6, M10).

**Passos de implementação:**

1. Escrever testes de integração com dados de execuções conhecidos e resultados esperados.
2. Implementar migração das views, consultas e endpoints.
3. Documentar no `CLAUDE.md` o padrão de view `report_*`.
4. Regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regra em RF-CHK-09 e ARCHITECTURE §11 ("Leitura para relatórios").
- Partes paralelas: nenhuma.
- Foco do verificador: conformidade calculada errado com itens "não se aplica"; consulta às tabelas internas em vez das views; relatório sem permissão; view com dado pessoal.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E08 — "Relatórios de checklists" do roadmap docs/roadmaps/roadmap-m2-plataforma-operacional.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E08 inteira, o "Evidências e handoff" de E07, RF-CHK-09 em docs/SPEC.md §5.15 e "Leitura para relatórios" em docs/ARCHITECTURE.md §11. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E07 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar views checklists.report_* para conformidade por modelo, itens mais reprovados e histórico por local, com SELECT para o papel da aplicação, e endpoints de leitura em /api/v1/checklist-reports com filtros de período, modelo e local, via JdbcClient e com permissão própria. Os endpoints consultam só as views. Não crie o módulo reporting.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E08 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes de integração com dados conhecidos primeiro, implemente, documente no CLAUDE.md o padrão de view report_*, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em itens "não se aplica" na conformidade, consulta a tabelas internas e falta de permissão.

Critérios de aceite (detalhes na seção E08):
- os três relatórios devolvem os valores esperados para o conjunto de dados de teste;
- itens "não se aplica" ficam fora do denominador da conformidade;
- sem permissão → 403; endpoints leem só as views;
- contrato regenerado; build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E08 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Conformidade por modelo, itens mais reprovados e histórico por local devolvem os valores esperados para o conjunto de dados do teste.
- [ ] Respostas "não se aplica" ficam fora do denominador da conformidade.
- [ ] As consultas dos endpoints usam só as views `checklists.report_*`, e o papel da aplicação lê cada view (teste).
- [ ] Sem permissão → `403`.
- [ ] `CLAUDE.md` documenta o padrão de view `report_*`; contrato regenerado; build e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Views quebradas por migrações futuras do módulo: o teste de integração cobre cada view.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E09 — Notificações internas e push — #pendente

**Objetivo:** criar o módulo `notifications` com notificações internas para funcionários (painel e tópico SSE), preferências por usuário e tipo, registro de dispositivos de push vinculados à sessão e envio por FCM HTTP v1 atrás de uma porta, testado com WireMock.

**Por que agora:** RF-NOT-01 lista avisos de vários módulos (tarefa atribuída, UH liberada, OS urgente, falha de integração…); os marcos seguintes só chamam a API pública criada aqui.

**Dependências e paralelismo:** E04, E12. Pode correr em paralelo com E08 (em worktree separado); conflito de edição em `api/openapi.yaml`, `CLAUDE.md` e `ModularityTest.kt`.

**Escopo:**

- Módulo `notifications` (a criar) em `src/main/kotlin/com/zeny/hotel/notifications/`, com `@ApplicationModule(allowedDependencies = ["shared", "iam"])`, schema e migrações próprios; `Notification`, `NotificationPreference`, `PushDevice` (vinculado à `DeviceSessionId` do kernel) — SPEC §8.2.18.
- Catálogo de tipos de notificação em código, por módulo (como `ModulePermissions` e `ModuleFilePurposes`), com os sete de RF-NOT-01 e os tipos obrigatórios documentados.
- `iam` (H9): `IamApi` ganha a consulta de usuários ativos com uma permissão efetiva e a de sessões ativas; `ModularityTest` aceita `notifications` no teste `no module other than staff depends on iam` (renomeado) e ganha `notifications depends only on shared and iam`; `CLAUDE.md` troca a frase sobre quem importa `iam`.
- API pública: `notify(type, title, body, recipients)` com destinatários por usuário ou por permissão. Pela regra por camadas, qualquer módulo de negócio pode chamá-la; `notifications` não escuta eventos de outros módulos.
- Endpoints: `GET /api/v1/notifications` (próprias, cursor), `POST …/{id}/read`, `POST …/read-all`, `GET/PUT /api/v1/notification-preferences`, `POST /api/v1/push-devices` e `POST /api/v1/push-devices/{id}/deactivate` (sem `DELETE`, como o resto da API).
- Tópico SSE `notifications` (só as próprias), registrado pela porta da E04.
- Porta de push com adaptador FCM HTTP v1 (H7) em `notifications/infrastructure/`: envio assíncrono por um listener do próprio módulo pelo outbox (com o backoff e o abandono da E12), token inválido desativa o dispositivo, sessão revogada (conferida na `IamApi`) não recebe, log sem dados pessoais; WireMock acrescentado ao `gradle/libs.versions.toml` (versão só lá).
- Compose e documentação: `ZENY_FCM_*` opcionais (vazias = push desligado) no `docker/compose.yaml`, no `docker/.env.example` e no `README.md`; `CLAUDE.md` explica como um módulo notifica.

**Fora de escopo:** e-mail SMTP (opcional, ARCHITECTURE §12; fica para M6 com os relatórios do fechamento); disparo das notificações pelos módulos consumidores.

**Passos de implementação:**

1. Escrever testes: entrega no painel e no SSE, preferências respeitadas, destinatários por permissão, push com WireMock simulando sucesso, token inválido e erro temporário, sessão revogada sem push, falha do FCM sem afetar quem notificou.
2. Implementar a extensão da `IamApi`, módulo, migrações, API pública, controllers, tópico e adaptador.
3. Documentar e regenerar o contrato.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "o que RF-NOT-01 e 02, SPEC §8.2.18 e ARCHITECTURE §12 exigem de notificações e push?"; "qual o formato de requisição, autenticação OAuth da conta de serviço e de erro do FCM HTTP v1 (token não registrado, cota, erro temporário)?"; "como o `iam` calcula permissões efetivas (`EffectivePermissionService`) e sessões ativas, para a extensão da `IamApi`?".
- Partes paralelas: nenhuma.
- Entrevista: H7 continua aberta; a etapa a decide com o usuário depois da investigação e antes de implementar.
- Foco do verificador: usuário recebendo notificação de outro (painel ou SSE); push enviado contra a preferência ou para sessão revogada; credencial FCM no repositório ou no log; falha do FCM derrubando a transação de quem notificou; outro módulo além de `staff` e `notifications` importando `iam`.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E09 — "Notificações internas e push" do roadmap docs/roadmaps/roadmap-m2-plataforma-operacional.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Restrições e invariantes", a hipótese H7, as decisões confirmadas H9 e H13 e o "Protocolo de execução" —, a seção E09 inteira e o "Evidências e handoff" de E04 e E12. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E04 e E12 estão #concluida e que o código bate com os handoffs; se não, ou se o código divergiu do plano, pare e relate sem implementar.

A hipótese H7 (FCM HTTP v1 com conta de serviço em ZENY_FCM_*, push desligado sem credencial, sem projeto Firebase ainda) continua aberta e é decidida na entrevista desta etapa: depois da investigação e antes de implementar, apresente as opções ao usuário com AskUserQuestion, como manda o CLAUDE.md (se você for um subagente, devolva a pergunta ao orquestrador e pare), e registre a decisão na tabela de hipóteses.

Objetivo: criar o módulo notifications (allowedDependencies shared e iam) com notificações internas (painel e tópico SSE notifications, só as próprias), preferências por usuário e tipo, catálogo de tipos de RF-NOT-01 por módulo, API pública notify com destinatários por usuário ou permissão (pela IamApi ampliada, H9), registro de dispositivos de push vinculados à sessão e envio por FCM HTTP v1 atrás de uma porta, assíncrono por um listener do próprio módulo pelo outbox, com desativação de token inválido e sem push para sessão revogada. Sem ZENY_FCM_* configuradas, o push fica desligado. Não implemente e-mail nem os disparos dos módulos consumidores.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E09 para #em-andamento no índice e no título;
- investigação: um subagente Explore responde às perguntas da seção E09 (RF-NOT-01 e 02, SPEC §8.2.18, ARCHITECTURE §12, FCM HTTP v1 e permissões e sessões no iam);
- escreva os testes primeiro (painel, SSE, preferências, destinatários por permissão, FCM com WireMock em sucesso, token inválido e erro temporário, sessão revogada, falha isolada), implemente, atualize ModularityTest, CLAUDE.md, README.md, docker/compose.yaml e docker/.env.example, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em notificação de outro usuário, push contra preferência ou para sessão revogada, credencial no repositório e falha do FCM afetando quem notificou.

Critérios de aceite (detalhes na seção E09):
- notificação aparece no painel e no SSE só do destinatário; preferências respeitadas;
- push enviado ao FCM (WireMock); token inválido desativa o dispositivo; erro temporário é reprocessado;
- falha do FCM não reverte a transação de quem chamou notify; nenhuma credencial no repositório;
- só staff e notifications importam iam; contrato regenerado; build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E09 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] `notify` para um usuário cria a notificação no painel dele e a envia no tópico SSE `notifications` só para ele; outro usuário não a vê.
- [ ] `notify` por permissão alcança exatamente os usuários ativos com a permissão efetiva vigente.
- [ ] Preferência com push desligado para um tipo impede o push, mantendo o painel; com painel desligado, nada é criado para aquele tipo, salvo tipos obrigatórios documentados.
- [ ] Com WireMock simulando o FCM: sucesso registra envio; token não registrado desativa o `PushDevice`; erro temporário é reprocessado pelo outbox com o backoff da E12.
- [ ] Sessão de dispositivo revogada deixa de receber push.
- [ ] Uma falha do FCM não reverte a transação de quem chamou `notify` (teste).
- [ ] Nenhuma credencial FCM no repositório; `README.md` e `docker/.env.example` listam as variáveis; `CLAUDE.md` explica como notificar.
- [ ] `ModularityTest` mostra `notifications` dependendo só de `shared` e da API pública do `iam`, e nenhum módulo além de `staff` e `notifications` importa `iam`; contrato regenerado; build e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.
- `docker compose -f docker/compose.yaml config` com as `ZENY_FCM_*` vazias → sucesso (push desligado não impede a subida).

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Sem projeto Firebase (H7), o push real não é testado neste marco: registrar no handoff como pendência operacional.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E10 — Gate do marco M2 — #pendente

**Objetivo:** confirmar, numa cópia limpa do repositório, que toda a definição global de pronto do M2 é atendida, exercitando ponta a ponta o status em tempo real, a sincronização offline de um checklist com foto e as notificações.

**Por que agora:** é o critério de entrada do marco M3.

**Dependências e paralelismo:** E03, E08, E09 (que cobrem transitivamente as demais, inclusive E11 e E12). Não paralelizável.

**Escopo:**

- Rodar build, testes, lint, verificação do contrato e oasdiff numa cópia limpa (`git archive` do snapshot, `GRADLE_USER_HOME` novo, `--no-build-cache`, como no gate do M1) e o compose num projeto isolado (`-p` e `--env-file` no scratchpad, sem ler nem tocar o `docker/.env` do usuário).
- Roteiro ponta a ponta contra o compose, pelo proxy: cadastrar tipo, UH e área; inscrever um cliente SSE em `rooms` e `notifications`; mudar status e abrir OOO com step-up; enviar uma foto com `clientFileId` duas vezes; sincronizar um checklist concluído offline duas vezes; consultar relatórios; enviar uma notificação. Rotinas e expurgos, que dependem da virada da data ou do tempo, têm a evidência nos testes.
- Conferir cada item da "Definição global de pronto".
- Revisar `CLAUDE.md` e `README.md`; registrar a conclusão do M2 no histórico de `docs/roadmaps/README.md`, com a CI pendente até o commit do usuário.

**Fora de escopo:** funcionalidade nova; problemas encontrados voltam à etapa de origem, salvo ajustes triviais de documentação.

**Passos de implementação:**

1. Cópia limpa e validações completas.
2. Roteiro ponta a ponta com `curl`, registrado no handoff.
3. Evidência de cada item da definição de pronto.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação: nenhuma.
- Partes paralelas: nenhuma.
- Foco do verificador: item da definição de pronto sem evidência; dados pessoais na sincronização, no SSE, nos logs ou nas fotos; dependência entre módulos fora da ARCHITECTURE §3.1; documentação desatualizada.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E10 — "Gate do marco M2" do roadmap docs/roadmaps/roadmap-m2-plataforma-operacional.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Definição global de pronto" e o "Protocolo de execução" —, a seção E10 inteira e o "Evidências e handoff" de E01 a E09, E11 e E12. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 a E09, E11 e E12 estão #concluida; se não, ou se o código divergiu do plano, pare e relate.

Objetivo: verificar numa cópia limpa do repositório, sem caches nem estado local, cada item da definição global de pronto do M2, e exercitar ponta a ponta contra um docker compose isolado, pelo proxy: cadastro de tipo, UH e área; cliente SSE em rooms e notifications; mudança de status e OOO com step-up; foto com clientFileId enviada duas vezes; checklist concluído offline sincronizado duas vezes; relatórios; notificação. Não leia nem altere o docker/.env do usuário. Não implemente funcionalidade nova: problemas voltam à etapa de origem, salvo ajustes triviais de documentação.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E10 para #em-andamento no índice e no título;
- sem investigação delegada;
- rode do zero ./gradlew build, test, lint e checkOpenApi na cópia limpa e .github/scripts/check-api-breaking.sh, depois o roteiro ponta a ponta com curl contra o compose isolado; registre a evidência de cada item;
- registre a conclusão do M2 no histórico de docs/roadmaps/README.md, com a CI pendente até o commit;
- verificação independente com o Prompt do verificador, com foco em item sem evidência, dados pessoais na sincronização, no SSE ou nas fotos, dependência fora da ARCHITECTURE §3.1 e documentação desatualizada.

Critérios de aceite (detalhes na seção E10):
- todos os itens da definição global de pronto marcados com evidência;
- roteiro ponta a ponta registrado no handoff;
- CLAUDE.md e README.md coerentes com o código; docs/roadmaps/README.md atualizado.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E10 (snapshot, verificações, resultados, parecer do verificador, notas para o marco M3) e responda com um resumo curto. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Todos os itens da "Definição global de pronto" estão marcados, cada um com evidência numa cópia limpa.
- [ ] O roteiro ponta a ponta contra o compose está registrado no handoff com comandos e respostas relevantes.
- [ ] `CLAUDE.md` e `README.md` estão coerentes com o código (tópicos SSE, sincronização, outbox, anexos, checklists, notificações, variáveis do FCM).
- [ ] `docs/roadmaps/README.md` registra a conclusão do M2 no histórico de revisões.

**Validação obrigatória:**

- `./gradlew build`, `test`, `lint` e `checkOpenApi` numa cópia limpa, sem cache → sucesso; `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR`.
- `docker compose -p` isolado `-f docker/compose.yaml up -d --build` → readiness `UP` e roteiro ponta a ponta concluído; `down -v` em seguida.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- SSE pelo proxy do compose se comportando diferente do teste: o roteiro passa pelo proxy de propósito.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para o marco M3: —

---

## Histórico de revisões

- 2026-10-13 — roadmap criado.
- 2026-10-13 — aplicada a regra por camadas da ARCHITECTURE §3.1 (decisão do usuário): H2 virou a decisão D1 (`checklists` → `hotel`, listener de `BusinessDateAdvanced`); E07 não pergunta mais ao usuário nem edita a ARCHITECTURE; E09 explicita que módulos chamam `notifications` e que ele não escuta eventos.
- 2026-10-15 — sincronizado com o código depois do gate do M1 (commits `fe4e91f` e `372701d`, CI verde no run `38783591046`). "Contexto confirmado" passa a citar os módulos existentes, os caminhos, os comandos, as portas e APIs públicas reais (`HotelApi`, `StaffApi`, `FilesApi`, `IamApi`), o estado do outbox, do SSE e dos testes, e as pendências herdadas do M1 com a etapa de cada uma. Etapas novas: **E11** (ciclo de vida dos anexos: `detach`/`delete`, expurgo de órfãos, upload com `clientFileId`, reencode de imagens e o `FilesApiTest` sem `Thread.sleep`) e **E12** (limite de tentativas, abandono com alerta e expurgo do outbox). E07 passa a depender de E11 e E12 e a cuidar das fotos; E09 passa a depender de E12, amplia a `IamApi` e muda o `ModularityTest` (H9); E04 ganha o encerramento por evento de revogação e no vencimento do token (H8) e pede revisão humana; validações das etapas trocadas pelos comandos oficiais (`./gradlew build` e o oasdiff). Hipóteses novas H8 a H15, a confirmar pelo usuário; H1 confirmada. Nenhuma etapa removida ou renumerada; todas seguem `#pendente`.
- 2026-10-15 — decisões do usuário (entrevista): H8 a H15 confirmadas com a opção recomendada e registradas na tabela; E11 e E12 mantidas como etapas separadas (D2); H6 e H7 continuam abertas e serão decididas na entrevista da E05 e da E09. Os prompts de E04, E05, E07, E09, E11 e E12 passam a tratar as decisões como confirmadas. Tags inalteradas.
