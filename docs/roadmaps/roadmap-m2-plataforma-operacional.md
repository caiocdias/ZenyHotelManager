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
| H6 | `change_seq` é uma *sequence* global no schema `shared` (o papel da aplicação já recebe `USAGE, SELECT` nas sequences de `shared` por `ALTER DEFAULT PRIVILEGES`); cada tabela sincronizável tem a coluna atualizada pela aplicação a cada escrita e as exclusões viram *tombstones* numa tabela de `shared`. O cursor não pode passar de mudanças de transações ainda abertas (sequences não seguem a ordem de commit). | Se a atualização pela aplicação se mostrar frágil, usar *trigger* por tabela. **Continua aberta** (decisão do usuário em 2026-10-15): decidida na entrevista da própria E05, antes da implementação. | E05 |
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
| E01 | Cadastro de tipos de UH, UHs e áreas | #pendente | — | Módulo `rooms` com tipos, UHs, características e áreas comuns |
| E02 | Status de UH, histórico e discrepâncias | #pendente | E01 | Dimensões de status, transições da SPEC §8.4, histórico, RN-05, discrepâncias e `RoomStatusChanged` |
| E03 | OOO e OOS | #pendente | E02 | Períodos fora de ordem e fora de serviço com step-up, retorno e `OutOfOrderChanged` |

**Tempo real, offline e outbox**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E04 | Tempo real (SSE) | #pendente | E02 | `GET /api/v1/stream` com tópicos, filtro por permissão, `Last-Event-ID` e tópico `rooms` |
| E05 | Sincronização offline | #pendente | E02 | `GET /sync/changes` por cursor e `POST /sync/commands` idempotente, com `rooms` como primeira fonte |
| E12 | Limite de tentativas e arquivamento do outbox | #pendente | — | Backoff, abandono com alerta e expurgo das publicações concluídas |

**Checklists e anexos**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E06 | Modelos de checklist | #pendente | — | Modelos, versões imutáveis, itens, condicionais, aplicabilidade e ações configuradas |
| E11 | Ciclo de vida dos anexos | #pendente | — | `detach`/`delete`, expurgo de órfãos, upload com `clientFileId` e reencode de imagens |
| E07 | Execução de checklists e rotinas | #pendente | E05, E06, E11, E12 | Execução online e offline com fotos, `ChecklistCompleted` e rotinas por setor e escala |
| E08 | Relatórios de checklists | #pendente | E07 | Conformidade por modelo, itens mais reprovados e histórico por local |

**Notificações e gate**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E09 | Notificações internas e push | #pendente | E04, E12 | Notificações, preferências, tópico SSE e adaptador FCM |
| E10 | Gate do marco M2 | #pendente | E03, E08, E09 | Definição global de pronto verificada do zero |

---

## E01 — Cadastro de tipos de UH, UHs e áreas — #pendente

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

- [ ] Os quatro recursos têm lista paginada por cursor, detalhe com `ETag`, criação, edição com `If-Match` e desativação, cobertos por testes de integração.
- [ ] Número de UH duplicado → `409` com `code` estável (nunca 500).
- [ ] Ocupação máxima menor que a padrão → `400` com `code` estável.
- [ ] Não existe exclusão física de UH: nenhum `DELETE` no contrato e o trigger recusa `DELETE` do papel da aplicação (teste); UH desativada some das listas de UHs ativas da API pública.
- [ ] Sem permissão → `403`; escritas auditadas (`LayerArchitectureTest` e entradas na trilha).
- [ ] `ModularityTest` mostra `rooms` dependendo só de `shared` (e `hotel`, se usado), pela API pública; `CLAUDE.md` atualizado; contrato regenerado; build passa.

**Validação obrigatória:**

- `./gradlew build` → sucesso (inclui testes, lint, `checkOpenApi` e Kover).
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Cadastro inicial manual de até 30 UHs (SPEC §1): aceitável; não há importação.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E02 — Status de UH, histórico e discrepâncias — #pendente

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

- [ ] Testes de domínio cobrem cada transição permitida da máquina de status de governança da SPEC §8.4 e rejeitam as não previstas (ex.: DIRTY → INSPECTED) com `code` estável.
- [ ] `markClean(requiresInspection = true)` deixa a UH em CLEAN com `inspectionPending`; `markInspected()` remove a pendência; `rejectInspection()` volta a DIRTY.
- [ ] `isReadyForCheckIn` só devolve verdadeiro para UH vaga e Inspecionada, ou Limpa sem inspeção pendente (RN-05).
- [ ] Toda mudança, manual ou pela API pública, grava `RoomStatusChange` com usuário, instante e origem; `GET /api/v1/rooms/{id}/status-history` a devolve.
- [ ] Observação da governança divergente da ocupação ou do número de hóspedes aparece em `GET /api/v1/rooms/discrepancies`.
- [ ] Duas mudanças simultâneas na mesma UH não se sobrescrevem silenciosamente (lock otimista ou trava, teste concorrente; `412` pela API com `If-Match` desatualizado).
- [ ] `RoomStatusChanged` é publicado pelo outbox com ID, dimensões e versão, sem dados de hóspede.
- [ ] Contrato regenerado; build e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Transições de origem automática (check-out, fechamento) ainda sem chamador: cobrir pela API pública em testes de módulo.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E03 — OOO e OOS — #pendente

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

- [ ] Abrir OOO sem a permissão devolve `403 APPROVAL_REQUIRED` (com `action`, `permission` e `resourceRef` sem dado pessoal); com `X-Approval-Token` válido é concluído e auditado com o `approval_id`.
- [ ] OOO em UH ocupada → erro com `code` estável (RN-07); OOS em UH ocupada é aceito.
- [ ] Período sobreposto ao de outro OOO/OOS da mesma UH → erro com `code` estável, também por SQL direto (constraint).
- [ ] Liberar o período aplica o status de governança de retorno e grava o histórico.
- [ ] `isReadyForCheckIn` devolve falso para UH em OOO.
- [ ] `OutOfOrderChanged` é publicado ao abrir, alterar e liberar (teste de módulo).
- [ ] Contrato regenerado; build e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- OOO futuro sobre reserva já alocada: o alerta só existe em M4; registrar no handoff que `OutOfOrderChanged` carrega o período inteiro para o consumidor decidir.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E04 — Tempo real (SSE) — #pendente

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

- [ ] Um cliente em `GET /api/v1/stream?topics=rooms` recebe a mudança de status de uma UH em menos de 2 s (teste com cliente SSE real).
- [ ] Inscrição num tópico sem a permissão → `403 PERMISSION_DENIED`; tópico desconhecido → `400` com `code` estável; com escopo `OWN`, o cliente só recebe eventos dos próprios registros.
- [ ] Reconexão com `Last-Event-ID` dentro do buffer entrega os eventos perdidos na ordem; fora do buffer, o cliente recebe `reset`.
- [ ] Mudança feita numa transação revertida não gera evento.
- [ ] O payload contém só `type`, `id` e `version`.
- [ ] Revogar a sessão (ou desativar o usuário) encerra a conexão SSE dela; a conexão também termina no `exp` do access token.
- [ ] `CLAUDE.md` documenta como registrar um tópico; `ApplicationModules.verify()` passa sem `shared` depender de módulos; contrato regenerado; build e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.
- Inspeção manual: `curl -N` contra o compose, através do proxy, recebe eventos e *heartbeat* sem corte (projeto isolado com `-p` e `--env-file` no scratchpad; não tocar no `docker/.env` do usuário; `down -v` no fim).

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Proxy com *buffering* atrasando eventos: desligar o buffer (`flush_interval -1`) para `/api/v1/stream` no Caddyfile e registrar no handoff.
- Cada conexão SSE ocupa um slot assíncrono do Tomcat: limitar conexões por sessão e registrar o limite.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E05 — Sincronização offline — #pendente

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

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E12 — Limite de tentativas e arquivamento do outbox — #pendente

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

- [ ] Um listener que falha duas vezes e depois funciona é reenviado em intervalos crescentes e a publicação fica concluída (teste com relógio controlado).
- [ ] Um listener que sempre falha tem a publicação abandonada depois do prazo configurado: ela continua no banco, conta em `zeny_outbox_publications{state="abandoned"}` e não é reenviada pelo job, pelo staleness nem no reinício.
- [ ] Publicações concluídas mais antigas que a retenção são apagadas; as recentes e as incompletas ficam.
- [ ] O reenvio manual documentado no `CLAUDE.md` faz uma publicação abandonada ser processada de novo (teste).
- [ ] A regra de alerta nova passa no `promtool check rules`; build passa.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `promtool check rules docker/prometheus/alerts.yaml` (pela imagem `prom/prometheus`, como no M0 E06) → `SUCCESS`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O `republish-outstanding-events-on-restart` reenvia todas as incompletas no boot, inclusive as abandonadas: se a API do Modulith não permitir filtrar, desligar a opção e cobrir o boot pelo próprio job, registrando a escolha.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E06 — Modelos de checklist — #pendente

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

- [ ] Publicar um rascunho cria a versão 1; editar o modelo publicado e publicar cria a versão 2, e a versão 1 continua idêntica (teste).
- [ ] Os sete tipos de item da SPEC §8.2.11 são aceitos; foto não é obrigatória por padrão.
- [ ] Condição que referencia item posterior, inexistente ou com valor incompatível → `400` com `code` estável.
- [ ] Ação de baixa de estoque fora de item de quantidade → `400` com `code` estável.
- [ ] `resolveTemplate` devolve o modelo cuja aplicabilidade casa com os atributos informados e, sem regra específica, o modelo geral do contexto (teste de módulo).
- [ ] Escritas com permissão e auditadas; `ModularityTest` mostra `checklists` dependendo só da API pública de módulos da lista da ARCHITECTURE §3.1.
- [ ] Contrato regenerado; build e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Linguagem de condição complexa demais: limitar a "item X tem resposta Y" e registrar como decisão.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E11 — Ciclo de vida dos anexos — #pendente

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

- [ ] `FilesApi.detach` deixa o anexo sem dono e grava `files.file.detach`; `FilesApi.delete` remove o objeto do bucket só depois do commit, marca a linha e grava `files.file.delete`; depois, `find` devolve `null` e metadados e URL dão `404 FILE_NOT_FOUND`.
- [ ] O job apaga o objeto e marca a linha de um órfão mais antigo que a retenção, com entrada `files.file.purge-orphan` de ator sistema; órfão recente e anexo vinculado ficam (teste com relógio controlado).
- [ ] Upload com o mesmo `clientFileId`, o mesmo usuário e o mesmo conteúdo devolve `200` com o mesmo anexo e deixa uma só linha e um só objeto; com outro conteúdo, outra finalidade ou outro usuário → `409` com `code` estável.
- [ ] Numa finalidade com reencode, uma foto JPEG com EXIF e GPS é gravada sem esses metadados e com o lado máximo respeitado; o SHA-256 gravado é o do conteúdo reencodado; o boot recusa finalidade com reencode que aceite WebP ou PDF.
- [ ] `FilesApiTest` não usa `Thread.sleep` para ver a URL vencer; o logotipo do hotel continua com o mesmo comportamento (`HotelLogoTest` sem mudança de asserção).
- [ ] `CLAUDE.md` e `README.md` atualizados; contrato regenerado; build e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1` (a parte nova é opcional).

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Memória ao decodificar fotos grandes: recusar imagem cujas dimensões declaradas passem de um teto antes de decodificar e registrar o teto e o heap no handoff.

**Rollback / migração:** a coluna nova é anulável e os anexos existentes continuam válidos; o expurgo só alcança anexos sem vínculo, então o logotipo vinculado nunca é apagado.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E07 — Execução de checklists e rotinas — #pendente

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

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

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
