# Roadmap — M3 Hóspedes, contas e caixa

> Arquivo: `docs/roadmaps/roadmap-m3-hospedes-contas.md` · criado em 2026-10-13 · última revisão 2026-10-16
>
> Marco 3 de 11 · mapa dos marcos em [`docs/roadmaps/README.md`](README.md) · marco anterior: [M2 — Plataforma operacional](roadmap-m2-plataforma-operacional.md) · próximo marco: [M4 — Tarifas, disponibilidade e reservas](roadmap-m4-tarifas-reservas.md)
>
> **Pré-requisito:** etapa E10 ("Gate do marco M2") de `docs/roadmaps/roadmap-m2-plataforma-operacional.md` marcada `#concluida` — atendido em 2026-10-16 (commits `5ab91f9`, `15b7ec7` e `8d163b2`, CI verde no run `38954633425`).

## Como usar

Duas formas, ambas na raiz do repositório:

- **Manual:** cole o prompt de uma etapa numa sessão limpa do Claude Code. Uma etapa por sessão.
- **Automática:** cole o prompt da seção "Execução automática" numa sessão limpa; ela executa até 3 etapas em sequência, um subagente por etapa mais um verificador, e para para revisão. Para não parar em cada pedido de permissão, libere antes os comandos de build/teste do projeto nas permissões do Claude Code (os comandos oficiais estão no `CLAUDE.md`, e o Docker é necessário para os testes de integração).

Etapas marcadas como paralelizáveis só devem rodar ao mesmo tempo em worktrees git separados; na mesma pasta, rode-as uma de cada vez.

Em ambas, quem executa segue o "Protocolo de execução", atualiza a tag da etapa e preenche "Evidências e handoff". Não pule dependências. Revise e faça commit entre etapas ou lotes — nenhuma sessão faz commit por conta própria.

**Sincronizado com o código em 2026-10-16**, depois do gate do M2 (commit `8d163b2`): caminhos, comandos, convenções, APIs públicas e pendências vêm do `CLAUDE.md`, do código e dos handoffs de `docs/roadmaps/roadmap-m2-plataforma-operacional.md` (principalmente as "Notas para o marco M3" da E10). Nenhuma etapa nova, removida ou renumerada; todas seguem `#pendente`. As pendências do M2 entraram nas etapas que as usam (E01, E03, E07 e E11). As hipóteses H1 e H8 a H14 foram decididas pelo usuário em 2026-10-16, na entrevista da sincronização, todas pela opção recomendada; H15 e H16 continuam hipóteses, e as decisões que dependem da investigação de cada etapa ficam para a entrevista da própria etapa. As decisões que dependem da investigação de cada etapa ficam para a entrevista da própria etapa (lista no fim de "Hipóteses e decisões em aberto").

## Objetivo

Entregar os módulos `guests`, `payments` (só o registro manual) e `billing` (sem contas a receber). Ao fim do marco, a API permite cadastrar hóspedes e empresas com proteção de dados pessoais (LGPD), abrir contas para qualquer pagador, lançar débitos imutáveis com estorno e ajuste sob aprovação, rotear e transferir lançamentos, operar turnos de caixa, registrar pagamentos manuais, depósitos, reembolsos e caução, e encerrar contas com saldo zero. É a base financeira que as reservas (M4) e a recepção (M5) vão usar. Nenhuma nota fiscal é emitida neste marco: até M7 as notas são emitidas manualmente no portal da prefeitura (SPEC §9). Recepção e contas exigem conexão: nada deste marco entra na sincronização offline nem num tópico SSE (H7).

## Contexto confirmado

- Requisitos: SPEC §5.5 (HOS, exceto RF-HOS-03, histórico, que vem com o `reporting` no M6), §5.10 (CTA, exceto RF-CTA-13, contas a receber, Fase 4), §5.11 RF-PAG-03 (registro manual) e RF-PAG-06 (provedores intercambiáveis); regras RN-01, RN-02, RN-03, RN-06, RN-10, RN-12, RN-13, RN-19, RN-20 e RN-24 (SPEC §6); RNF-09, RNF-10 e RNF-11 (SPEC §7); ações sensíveis de RF-ACS-08 deste marco: estorno/ajuste de lançamento, reabrir conta fechada e exportar dados pessoais (o reembolso é "mediante aprovação", RF-CTA-15; o desconto de tarifa é do M4).
- Modelo de domínio: SPEC §8.2.4 (`Guest`, `IdDocument`, `SensitiveGuestData`, `Company`, `Consent`, `Attachment`) e §8.2.7 (`Folio`, `FolioOwner`, `Posting`, `TransactionCode`, `RoutingRule`, `Payment`, `PaymentMethod`, `CardPreAuthorization`, `CashPoint`, `CashierShift`, `CashMovement`, `PaymentIntent`). Evento `PaymentSucceeded` (Pagamentos → Contas, SPEC §8.3).
- Módulos e dependências (ARCHITECTURE §3.1 e §3.2): `guests` (camada Cadastros) → `shared`, `files`; `payments` (Financeiro) → `shared`, `notifications` (o `notifications` só a partir do M7); `billing` (Financeiro) → `shared`, `hotel`, `guests`, `payments`, `fiscal`, `printing` (`fiscal` e `printing` só a partir do M5/M7). Vale a **regra por camadas**: um módulo pode depender de camada inferior e da Plataforma (`hotel`, `files`, `notifications`…) sem ciclo, acrescentando a aresta à tabela da §3.1 na mesma mudança (ex.: `billing` → `notifications` se a H14 for confirmada). Uma conta referencia seu dono por `FolioOwner(type, id)` e não conhece reservas.
- Persistência: ARCHITECTURE §11 (schema por módulo, sem FK entre schemas, `numeric(14,2)`, imutabilidade financeira pelos dois papéis do banco, views `report_*` como API de leitura).
- Segurança e privacidade: ARCHITECTURE §6.3 (step-up), §8.2 (permissões, escopo, limites) e §8.3 (cifragem de campo para número de documento e dados sensíveis da FNRH, anexos sensíveis cifrados e servidos pelo servidor com acesso auditado, exportação auditada, PCI-DSS).
- API: ARCHITECTURE §7 (`Idempotency-Key` em POSTs com efeito financeiro; `ETag`/`If-Match`; erros com `code` estável; paginação por cursor).
- Base técnica herdada de M0, M1 e M2 (fonte: `CLAUDE.md`, código e handoffs; conferida no código em 2026-10-16):
  - **Módulos existentes** em `src/main/kotlin/com/zeny/hotel/`: `shared`, `iam`, `audit`, `hotel`, `staff`, `files`, `rooms`, `checklists` e `notifications`, cada um com `domain/`, `application/`, `infrastructure/` e `web/` e um `…Module.kt` na raiz com `@ApplicationModule(allowedDependencies = …)` (ex.: `rooms/RoomsModule.kt` com `["shared", "hotel"]`). O `ModularityTest` (`src/test/kotlin/com/zeny/hotel/ModularityTest.kt`) tem um teste por módulo com as dependências diretas exatas e só pela API pública, e o teste `no module other than staff and notifications depends on iam, only on the ports of shared` barra qualquer outro módulo que importe `iam`: `guests`, `billing` e `payments` usam só `CurrentActor` e `Authorization`. As regras de camadas ficam em `src/test/kotlin/com/zeny/hotel/architecture/LayerRules.kt` (inclui `writeUseCasesAreAudited` e `offlineCommandHandlersAreAudited`).
  - **Comandos oficiais** na tabela do `CLAUDE.md`: `./gradlew build` (compilação, testes, `ModularityTest`, ArchUnit, detekt, ktlint, `checkOpenApi` e `koverVerify`), `test`, `lint`, `ktlintFormat`, `checkOpenApi`, `updateOpenApi`, `docker compose -f docker/compose.yaml up -d --build` e `.github/scripts/check-api-breaking.sh`. Versões só em `gradle/libs.versions.toml`.
  - **Migrações:** uma pasta por módulo em `src/main/resources/db/migration/` (hoje `shared`, `iam`, `audit`, `hotel`, `staff`, `files`, `rooms`, `checklists`, `notifications`), arquivos com versão por data e hora em UTC (ex.: `V2026_10_16_1741__grant_notifications_to_app_role.sql`), cada pasta com uma migração de `GRANT` ao `"${app_role}"` (modelo: `staff/V2026_10_14_2321__grant_staff_to_app_role.sql`). A última versão aplicada é `V2026_10_16_1741` (`notifications`); com `outOfOrder` desligado, toda migração nova precisa de timestamp posterior. `MigrationConventionTest` barra objeto fora do schema do módulo (o `INSERT INTO` de dados iniciais no próprio schema é permitido). Tabela *append-only* recebe só `SELECT, INSERT` e vai num bean `AppendOnlyTables` (`shared/Auditing.kt`) do módulo, conferido no boot pelo `DatabaseRoleGuard` e pelo `DatabaseRolesTest`. Exclusão física proibida por trigger segue o `staff.refuse_delete`/`rooms.refuse_delete`.
  - **Views `report_*`** (M2 E08; seção "Schemas e migrações" do `CLAUDE.md`; modelo `checklists/V2026_10_16_1700__create_checklist_report_views.sql`): nome `report_` seguido do assunto, no schema do módulo, uma linha por fato, ids opacos, **sem texto livre digitado por quem opera nem dado de hóspede**; contrato no comentário da migração; `GRANT SELECT` e `REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER` por view; consultas só pelas views, provadas com um papel que só as lê (`ChecklistReportsTest`).
  - **Portas do kernel** (raiz de `com.zeny.hotel.shared`): `CurrentActor` (`Actors.kt`), `Authorization` (`require`, `grantOrNull`, `scopeFilter`, `limit`, `requireOrApproval`; sempre sobre o ator da requisição corrente), `SensitiveAction`/`LimitAmount`/`APPROVAL_TOKEN_HEADER` (`Approvals.kt`), `@Audited`/`AuditContext`/`AppendOnlyTables` (`Auditing.kt`), `ModulePermissions` com `LimitDefinition` (`LimitUnit.MONEY`) e `templates` para os modelos `RoleTemplate` (`RECEPTIONIST`, `MANAGER`, `FINANCE`, `FNB_CASHIER`…), `FieldCipher`/`EncryptedStringConverter`/`ContentCipher`/`TokenSealer` (`FieldEncryption.kt`; a derivação HKDF por chave do anel está em `shared/infrastructure/crypto/HkdfTokenSealer.kt`), `Money` (`allocate`, `split`, `round`), `Percent`, `Cpf`/`Cnpj` (`TaxIds.kt`), `Address` (só Brasil, com IBGE), `DocumentNumberGenerator`, `UuidV7`/`TypedId`, `PageParams`/`CursorPage`, `ETags`, `@Idempotent` (`Idempotency.kt`) e `Clock` UTC. **Não há** índice cego (HMAC) para busca em coluna cifrada (H8).
  - **APIs públicas para o M3:** `HotelApi` (`currentBusinessDate()`, `timezone()`, `policy()` com `childMaxAge`/`childAgeBands`, `taxSettings()` com `serviceChargeRate` **padrão do hotel**, `issRate` e `tourismFeePerPersonNight`; nada por ponto de venda nem por código, H4); `FilesApi` (só `find`, `attach`, `detach` e `delete`; **não grava conteúdo pelo servidor** e **não tem a porta `FileAccessPolicy`**, H11 e H12; upload em `POST /api/v1/files` multipart com `clientFileId` opcional, sem `@Idempotent`; download só por `GET /api/v1/files/{id}/download-url` com a `downloadPermission` da finalidade, e o sensível servido pelo servidor com emissão e download na trilha; finalidades por `ModuleFilePurposes`, tipos `PNG`, `JPEG`, `WEBP`, `PDF` detectados pelo conteúdo, reencode só de JPEG/PNG; órfão expurgado em 24 h); `NotificationsApi.notify(tipo, título, texto, NotificationRecipients)` (no próprio caso de uso; tipos num `ModuleNotificationTypes` do módulo; destinatários por usuário ou por permissão em **qualquer escopo**; **nunca dado pessoal de hóspede no texto**); `RoomsApi`, `ChecklistsApi`, `StaffApi` (sem uso previsto no M3); `IamApi` só para `staff` e `notifications`.
  - **Eventos e jobs:** listeners de produção `ChecklistRoutineListener` e `PushDispatchListener`. O outbox só grava a publicação quando há listener: um evento sem consumidor de produção (`GuestsMerged`) é testado com `@EnableScenarios` e um listener de teste (modelo `BusinessDateListenerConfiguration` em `src/test/kotlin/com/zeny/hotel/hotel/BusinessDateTest.kt`). Reenvio com backoff de 1 min a 1 h e abandono depois de 24 h (M2 E12): todo listener é idempotente.
  - **Idempotência:** `@Idempotent` no método do controller exige `Idempotency-Key`, guarda a resposta (inclusive erro e `403 APPROVAL_REQUIRED`) por 24 h, cifrada; a repetição com `X-Approval-Token` usa **outra** chave. Só JSON síncrono (multipart recusado).
  - **Web e contrato:** controllers com `@ApiV1Controller`, `@Operation(operationId)` único e `@Tag` do módulo; DTOs com `Instant`, `LocalDate`, `Money`/`Percent` como string; `api/openapi.yaml` regenerado por `./gradlew updateOpenApi`. O boot avisa `HV000271` (`@Valid` num `List`, obsoleto) em dez DTOs existentes (H15).
  - **Apoio de testes:** `src/test/kotlin/com/zeny/hotel/testsupport/` (`IntegrationTest`, `asUser(userId, sessionId)` em `TestAuth.kt`, `MutableClockConfiguration` em `MutableClock.kt`, `PostgresContainer`, `MinioContainer`) e, nos pacotes de cada módulo, `IamTestSupport` (roles, usuários, `setPin`, `call(…, approvalToken = …)`), `FilesTestSupport` (envio com `clientFileId`, URL, leitura do bucket e da trilha) e `NotificationsTestSupport`. A data operacional só avança +1 dia (trigger em `hotel.business_day`; o `BusinessDateTest` a avança no banco compartilhado): os testes partem de `HotelApi.currentBusinessDate()` e, para provar "data operacional ≠ data do relógio", movem o relógio com o `MutableClock`.
  - **Compose e gates:** os gates do M1 e do M2 conferiram tudo numa cópia limpa (`git archive`, `GRADLE_USER_HOME` novo, `--no-daemon --no-build-cache`) e num `docker compose` isolado (`-p`, `--env-file` no scratchpad, porta própria, `prod`), por roteiro `curl` pelo proxy, com oasdiff contra o gate anterior.
- **Pendências herdadas do M2 e onde entram aqui:**
  - primeira finalidade sensível de produção (documentos de hóspede) → **E03**;
  - porta opcional `FileAccessPolicy` por finalidade (Q1 do M1, adiada pela H15 do M2) → **E03**, decisão H11;
  - `FilesApi` gravando conteúdo pelo servidor (o pacote de exportação era o primeiro consumidor) → decisão H12 (recomendado: fora do M3; primeiro consumidor passa a ser o `printing`, M5);
  - aviso `HV000271` do Hibernate Validator → **E01** (H15);
  - notificação de ponta a ponta no compose no gate do primeiro marco com disparo real de `NotificationsApi.notify` (decisão do usuário em 2026-10-16) → **E07** cria o disparo e **E11** mostra no compose (H14).
- **Pendências herdadas que não são do M3:** expurgo de notificações e registros de envio (M10 ou antes); push real quando houver projeto Firebase; consumidores de `ChecklistCompleted` (M6/M9) e de `OutOfOrderChanged` (M4/M8); `idle_in_transaction_session_timeout` e o restante da configuração do PostgreSQL de produção, `server.forward-headers-strategy`, URL pública única dos anexos, recifragem de objetos sensíveis, métrica de falha do S3 (M8); logotipo anterior vinculado (M10); ressalvas baixas sem destino listadas no "Contexto confirmado" do roadmap do M2.

## Escopo

**Incluído:**

- `guests`: perfis de hóspede com campos da FNRH, documentos cifrados com busca por índice cego, dados sensíveis da FNRH cifrados e nunca expostos, preferências, VIP, lista de restrição, empresas, detecção e mesclagem de duplicados, consentimentos, anexos sensíveis (primeira finalidade sensível de produção), exportação auditada dos dados do titular, correção e anonimização sob pedido, view de leitura.
- `payments`: `PaymentIntent` neutro com porta de provedor e um provedor `MANUAL`; evento `PaymentSucceeded`.
- `billing`: códigos de lançamento, contas por pagador, lançamentos imutáveis, estorno e ajuste com aprovação e limites, roteamento, transferência e divisão, taxa de serviço removível, pontos de caixa e turnos (com a notificação de diferença, H14), pagamentos manuais com troco, depósitos, reembolsos, caução registrada manualmente, encerramento e reabertura, extrato em JSON e views de leitura.
- Correção do aviso `HV000271` nos DTOs existentes (H15).

**Fora de escopo:**

- Histórico consolidado do hóspede (RF-HOS-03): depende de reservas e consumo; vem com `reporting` em M6, a partir das views `report_*`.
- Anonimização automática por retenção e expurgo dos dados sensíveis da FNRH (RN-19, RN-20, automação): M10.
- Contas a receber, faturas e método `CITY_LEDGER` (RF-CTA-13): M10.
- Pix, cartão integrado, tokenização, conciliação (RF-PAG-01, 02, 04, 05) e renovação de caução: M7.
- Emissão fiscal, perfil fiscal dos códigos de lançamento e vínculo de notas: M7.
- Extrato e confirmação em PDF (RF-CTA-12, parte PDF): M5. O dono do documento monta os dados e chama `printing` (`billing` → `printing` para o extrato); por isso o extrato em JSON de E10 já deve ter os dados completos.
- `FilesApi` gravando conteúdo pelo servidor (se a H12 for confirmada): M5, com o `printing`.
- Abertura de conta a partir de reserva, multas de cancelamento e no-show: M4 e M6 usam a API deste marco.
- Lançamentos vindos de PDV, fechamento, lavanderia ou eventos (e a taxa de serviço por ponto de venda, do `pos`): marcos desses módulos.
- Sincronização offline e tópicos SSE para hóspedes, contas e caixa (H7).

## Restrições e invariantes

- Respeitar o grafo de módulos da ARCHITECTURE §3.1 e a regra por camadas: `guests` não chama `billing`, `payments` não chama `billing`, `billing` não conhece reservas. Reações entre módulos vão por evento ou porta. `guests`, `billing` e `payments` não importam `iam`.
- RN-03: lançamentos, pagamentos e movimentos de caixa nunca são editados nem excluídos; correção só por estorno ou ajuste vinculado. As tabelas ficam só com `SELECT, INSERT` para o papel da aplicação e declaradas num bean `AppendOnlyTables` do módulo (mesmo mecanismo de `audit.audit_entry`, `iam.approval` e `rooms.room_status_change`).
- RN-02: dinheiro só com `Money` (`numeric(14,2)`, string em JSON) e percentual só com `Percent`; arredondar só pela regra de `Money`; repartir com `allocate`/`split`.
- RN-01: todo lançamento leva `HotelApi.currentBusinessDate()`; instantes pelo `Clock` injetado.
- RN-19 e RNF-09: raça/cor e deficiência ficam cifradas e não aparecem em nenhuma resposta de consulta, log, trilha, view `report_*` nem no contrato de leitura. Número de documento é cifrado e só se busca por índice cego (H8).
- RN-13 e a regra das notificações: nenhum texto de notificação, mensagem SSE ou fonte offline leva nome, documento ou contato de hóspede.
- RNF-10 (PCI-DSS): nunca receber nem guardar número de cartão ou CVV; só bandeira, 4 últimos dígitos, NSU e autorização.
- Toda escrita é auditada (`@Audited`, regra `writeUseCasesAreAudited`); retratos sem dado pessoal em claro conforme a H10. Ações sensíveis deste marco usam `authorization.requireOrApproval(SensitiveAction(...), approvalToken)` com permissão `sensitive = true` (e `LimitAmount` quando houver limite); o controller repassa o cabeçalho `X-Approval-Token`.
- `@Idempotent` (cabeçalho `Idempotency-Key`) em todo POST que cria lançamento, pagamento, reembolso, caução ou movimento de caixa.
- Migrações novas com timestamp posterior a `V2026_10_16_1741`; nenhum objeto, `GRANT` ou `INSERT` fora do schema do próprio módulo.
- DTOs novos com listas validadas usam `List<@Valid T>` (H15).
- Convenções de API, erros, migrações e tipos seguem o `CLAUDE.md`; não criar convenção paralela. Cada etapa atualiza o "Estado atual" e a seção do módulo no `CLAUDE.md`, a seção "API" do `README.md` e, se criar aresta entre módulos, a tabela da ARCHITECTURE §3.1 e o `ModularityTest`.
- Contrato `api/openapi.yaml` regenerado e versionado a cada etapa que mexe na API; `.github/scripts/check-api-breaking.sh` sem mudança `ERR`.

## Hipóteses e decisões em aberto

| # | Hipótese ou decisão | Impacto se errada / sem decisão | Etapa afetada |
|---|---|---|---|
| H1 | **Decidida pelo usuário em 2026-10-16** (entrevista da sincronização, P6, a opção recomendada): todo pagamento passa por um `PaymentIntent` do `payments`. No registro manual, `billing` chama a API pública do `payments` de forma síncrona na mesma transação (provedor `MANUAL`, confirmado na hora); `payments` publica `PaymentSucceeded` pelo outbox; o `@ApplicationModuleListener` de `billing` é idempotente e ignora intents já registrados, para que o M7 só acrescente provedores assíncronos. O `PaymentIntent` (mutável, com status) fica no `payments`; o `Payment` da conta (imutável) só nasce do intent confirmado. | Se a revisão humana preferir outro desenho (só `billing` no M3, ou tudo assíncrono), muda só E08 e a forma de o M7 integrar. | E08 |
| H2 | Até o M7, `TransactionCode` guarda uma classificação fiscal opcional em texto livre; o vínculo com `FiscalProfile` chega no M7 sem migração destrutiva. **Aplicada na E04** (2026-10-17): coluna `billing.transaction_code.fiscal_classification` (`varchar(60)`, anulável, texto livre). | Coluna renomeada ou convertida no M7. | E04 |
| H3 | **Confirmada na E03** (2026-10-17): a exportação de dados do titular usa uma porta definida em `guests` (`GuestPersonalDataContributor`, contribuidores de dados pessoais) que módulos acima implementam, e a anonimização consulta a porta `GuestActiveStayCheck` (estadia ativa). No M3 só `guests` contribui (a seção `guests`; os anexos vão numa lista à parte do pacote) e nenhum módulo implementa a estadia ativa: o `billing` não liga lançamento a hóspede (o dono é `FolioOwner`); M4/M5 acrescentam os seus. | Exportação incompleta até os demais módulos contribuírem; registrado como pendência nos marcos seguintes. | E03 |
| H4 | **Confirmada no código em 2026-10-16:** `HotelApi.policy()` traz `childMaxAge` e `childAgeBands`; `HotelApi.taxSettings()` traz `serviceChargeRate` (a **padrão** do hotel), `issRate` e a taxa de turismo; o comentário de `TaxSettings` diz que pontos de venda e códigos de lançamento "guardam a própria, quando diferente, e caem nesta". Logo: `TransactionCode` tem `serviceChargeRate` anulável (cai na do hotel) e a API de lançamento aceita uma taxa informada pelo módulo de origem (o `pos`, no M8, passa a do ponto de venda); nenhuma configuração nova no `hotel`. **Aplicada na E06** (2026-10-19): `NewPosting.serviceChargeRate` (de 0 a 100; só num código com `serviceChargeApplies`, senão ignorado), senão `TransactionCode.serviceChargeRate`, senão `HotelApi.taxSettings().serviceChargeRate`; o percentual aplicado fica no lançamento da taxa. | — | E04, E06 |
| H5 | SPEC Q-08 segue o padrão adotado: taxa de serviço só em A&B por configuração (códigos padrão de A&B com `serviceChargeApplies`, diária sem), retenção para encargos fora do M3; o motor em `billing` é genérico por código de lançamento. **Confirmada na E04** (2026-10-17, decisão do usuário): oito códigos padrão pré-carregados (`DIARIA` sem taxa, `MULTA-CANC`, `MULTA-NOSHOW`, `TX-SERVICO`, `AB` com `serviceChargeApplies`, `LAVANDERIA`, `TX-TURISMO`, `AVULSO`), com a chave estável `TransactionCodeKey`; grupos de receita num enum fixo (`ROOM`, `FOOD_BEVERAGE`, `SERVICE_CHARGE`, `FEES`, `TAXES`, `LAUNDRY`, `OTHER`). **Na E06** (2026-10-19) o motor ficou genérico por código (lançamento `SERVICE_CHARGE` separado no `TX-SERVICO` para todo item de código com `serviceChargeApplies`), e o grupo `SERVICE_CHARGE` é o único de repasse (`RevenueGroup.passThrough`); a retenção para encargos e o relatório de rateio continuam fora do M3. | Mudança de política é só configuração. | E04, E06 |
| H6 | **Confirmada na E02** (2026-10-17): a mesclagem de duplicados mantém o perfil sobrevivente, marca o absorvido como mesclado (`status` `MERGED`, `mergedInto`; sem excluir) e publica `GuestsMerged` pelo outbox, que módulos acima de `guests` escutam para repontar referências. No M3 não há consumidor de produção (o primeiro é `reservations`, M4). Regras decididas pelo usuário em 2026-10-17 (entrevista do orquestrador, todas pela opção recomendada): (1) vale o que o sobrevivente tem, os vazios dele recebem os do absorvido e os campos listados em `takeFromMerged` vêm do absorvido; (2) restrito se qualquer um estiver (motivo do sobrevivente, ou o do absorvido se só ele estava) e VIP se qualquer um for, sem nunca apagar um alerta; (3) documentos iguais (mesmo índice cego) uma vez só, os demais do absorvido passam para o sobrevivente, e o mesmo tipo e país com números diferentes é `409 GUEST_MERGE_DOCUMENT_CONFLICT`. | Sem o evento, reservas antigas apontariam para o perfil absorvido; o evento evita reescrever outros schemas. | E02 |
| H7 | **Confirmada na documentação** (ARCHITECTURE §9, "Recepção, contas e PDV exigem conexão", e §10, sem tópico de contas): o M3 não registra `OfflineSource`, `OfflineCommandHandler` nem `RealtimeTopic`. | Se um cliente pedir atualização em tempo real da conta, um tópico entra depois sem mudar o modelo. | Todas |
| H8 | **Decidida pelo usuário em 2026-10-16** (entrevista da sincronização, P1, a opção recomendada): busca por documento cifrado por **índice cego** numa porta nova do `shared` (ex.: `BlindIndex`, ao lado de `TokenSealer`): HMAC-SHA256 do valor normalizado (CPF só dígitos; demais documentos em maiúsculas, sem espaços nem pontuação, com tipo e país) com chave **derivada por HKDF de cada chave do anel `ZENY_ENCRYPTION_KEYS`** (como o `HkdfTokenSealer`, `info` = finalidade); grava-se o id da chave junto do hash e a busca calcula com todas as chaves do anel, então a rotação segue a do anel sem segredo novo. | Alternativas: chave própria `ZENY_BLIND_INDEX_KEY` (sem rotação: trocar = reindexar) ou busca sem índice (descarta o critério de não decifrar a tabela). `fnrh` e `reservations` vão buscar por documento. | E01, E02 |
| H9 | **Decidida pelo usuário em 2026-10-16** (entrevista da sincronização, P2, a opção recomendada): as consultas devolvem o documento **mascarado** (tipo, país e só os últimos caracteres); o número inteiro só por uma ação própria (`POST /api/v1/guests/{id}/documents/{documentId}/reveal` ou equivalente) com permissão própria e cada acesso na trilha (RNF-09, "log de acesso a dados"). | Alternativas: número inteiro em toda leitura com a permissão de leitura (sem registro de acesso) ou toda leitura de perfil auditada. Muda DTOs, permissões e testes da E01 e a exportação da E03. | E01, E03 |
| H10 | **Decidida pelo usuário em 2026-10-16** (entrevista da sincronização, P3, a opção recomendada): os retratos da trilha do `guests` (e os do `billing` que citem pessoas) **não levam dado pessoal em claro**: só ids, situação, flags (VIP, restrição), tipo do documento e a lista dos campos alterados (`changedFields`); nome, contatos, endereço, número de documento, motivo da restrição e observações ficam fora. Assim a anonimização (E03) não deixa dado pessoal na trilha *append-only*. | Alternativa: retratos completos visíveis a quem tem `audit.read`, aceitando que a anonimização não alcança a trilha (registrar como limitação para o M10). | E01, E02, E03, E04, E07 |
| H11 | **Decidida pelo usuário em 2026-10-16** (entrevista da sincronização, P4, a opção recomendada): a porta `FileAccessPolicy` **não é criada** e a Q1 do M1 fica encerrada: as finalidades de documento de hóspede são sensíveis (cifradas, servidas pelo servidor, emissão e cada download na trilha), com upload e download por permissões próprias do `guests` declaradas só com escopo `ALL`; a anonimização apaga os anexos (`FilesApi.delete`). | Alternativa: porta no `files` implementada pelo dono para regras por registro (ex.: recusar anexo de perfil mesclado). Custo aceito: a regra de acesso é só a permissão da finalidade. | E03 |
| H12 | **Decidida pelo usuário em 2026-10-16** (entrevista da sincronização, P5, a opção recomendada): a exportação do titular devolve o pacote **na própria resposta** (JSON com os dados de cada contribuidor e a lista de anexos com ids e metadados; o conteúdo dos anexos sai pelo `GET /api/v1/files/{id}/download-url` com a permissão da finalidade), sem guardar arquivo e sem `@Idempotent` (a resposta guardada teria os dados pessoais). A `FilesApi` que grava conteúdo pelo servidor fica para o M5 (`printing`). | Alternativa: criar `FilesApi.store` numa etapa nova antes da E03 e guardar o pacote como anexo sensível órfão (expurgado em 24 h), exigindo um tipo `JSON`/`ZIP` novo no `files`. | E03 |
| H13 | **Decidida pelo usuário em 2026-10-16** (entrevista da sincronização, P7, a opção recomendada): no máximo **um turno de caixa aberto por usuário** (em qualquer ponto de caixa), garantido por índice único parcial; recebimentos, devoluções e capturas usam o turno aberto do próprio usuário, sem informá-lo na requisição (`requireOpenShift(user)`). | Alternativa: um turno aberto por usuário **e** ponto (a requisição informa o ponto ou o turno). Muda E07, E08, E09 e o PDV (M8). | E07, E08, E09 |
| H14 | **Decidida pelo usuário em 2026-10-16** (entrevista da sincronização, P8, a opção recomendada): a E07 traz o primeiro disparo real de `NotificationsApi.notify`: fechar um turno com diferença em alguma forma de pagamento notifica quem tem uma permissão só `ALL` de conferência de turnos (sugerida a Gerente e Financeiro), com um tipo novo `billing.cashier-shift-difference` num `ModuleNotificationTypes` do `billing` e texto sem dado pessoal (ponto de caixa, data e valor). A aresta `billing` → `notifications` entra na ARCHITECTURE §3.1 e no `ModularityTest`. O gate (E11) mostra a notificação de ponta a ponta no compose (painel e tópico SSE `notifications`). | Alternativa: nenhum disparo no M3, e a prova de ponta a ponta fica para o gate do M4/M5. | E07, E11 |
| H15 | O aviso `HV000271` (`@Valid` num `List`) é corrigido na E01: os DTOs novos usam `List<@Valid T>` e os dez existentes (`grants`, `limits`, `applicability`, `sections`, `items`, `options`, `actions`, `commands`, `answers`, `preferences`) são convertidos, com um teste que prova que um item inválido dentro da lista ainda dá `400` (o Kotlin precisa emitir a anotação de uso de tipo no argumento genérico). | Se o Kotlin 2.4 não emitir a anotação no bytecode sem opção de compilador, a E01 registra a opção usada ou mantém `@Valid` no `List` e relata; nunca perde a validação em cascata. | E01 |
| H16 | Nomes de permissões sugeridos, no padrão módulo, recurso e ação separados por ponto (segmentos minúsculos, sem hífen): `guests.profiles.read`/`manage`, `guests.documents.reveal`, `guests.restrictions.manage`, `guests.profiles.merge`, `guests.companies.read`/`manage`, `guests.attachments.read`/`manage`, `guests.data.export` (sensível), `guests.data.anonymize`; `billing.codes.manage`, `billing.folios.read`/`manage`, `billing.postings.create`, `billing.postings.reverse`/`adjust` (sensíveis, limite `maxAmount` em `MONEY`), `billing.routing.manage`, `billing.folios.reopen` (sensível), `billing.cashier.operate`, `billing.shifts.read` (`OWN`/`ALL`), `billing.shifts.review` (só `ALL`), `billing.payments.receive`, `billing.payments.refund` (sensível). Modelos de role pela SPEC §3.1: Recepcionista, Gerente, Financeiro e Caixa A&B. **Na E04** (2026-10-17) foram publicados `billing.codes.read` (acrescentada pelo implementador: consultar códigos), `billing.codes.manage`, `billing.folios.read`/`manage`, `billing.postings.create` e `billing.postings.reverse`/`adjust` (sensíveis, `maxAmount` em `MONEY`; Gerente e Financeiro sem teto, Recepcionista e Caixa A&B sem elas, decisão do usuário). **Na E05** (2026-10-19) foram publicadas `billing.routing.manage` e `billing.postings.transfer` (não sensíveis, só `ALL`; Gerente, Financeiro e Recepcionista, decisão do usuário); consultar as regras usa `billing.folios.read`. **Na E06** (2026-10-19) foi publicada `billing.servicecharge.remove` (remoção da taxa de serviço a pedido do cliente; não sensível, só `ALL`; Gerente, Financeiro e Recepcionista, decisão do implementador a revisar). | Nomes são confirmados na etapa; um código publicado não muda depois. | E01, E02, E03, E04, E05, E07, E08, E10 |

**Decisões abertas para a entrevista de cada etapa** (dependem da investigação; a sessão da etapa devolve os pontos ao orquestrador, que entrevista o usuário):

- **E01:** endereço de hóspede estrangeiro (o `Address` do `shared` é só Brasil, com IBGE); tipos de documento aceitos além de CPF e passaporte; unicidade de CNPJ entre empresas; busca por nome sem acento (se exigir `unaccent`/`pg_trgm`, criar no `shared` como o `btree_gist`).
- **E02:** ~~regra de escolha dos campos na mesclagem; restrição e VIP herdados pelo sobrevivente~~ — decididas pelo usuário em 2026-10-17 (ver H6 e o handoff da E02).
- **E03:** ~~anonimização como ação sensível (step-up) ou só com permissão; o que se apaga e o que fica (consentimentos como prova, RN-20); finalidades de anexo (foto de documento com reencode; PDF numa finalidade à parte, sem reencode); se entra uma view de empresas~~ — decididas pelo usuário em 2026-10-17 (entrevista do orquestrador, todas pela opção recomendada; ver o handoff da E03): anonimização sensível (`guests.data.anonymize`, step-up); fica o id, a situação `ANONYMIZED` (data e autor), os consentimentos, a nacionalidade, o país de residência, a UF e o município e o VIP, e sai todo o resto (inclusive documentos, índices cegos e anexos); três finalidades sensíveis (`guests.id-document-photo` e `guests.minor-authorization-photo` com reencode, `guests.document-pdf` sem); sem view de empresas; exportação e anonimização pedidas no sobrevivente abrangem a cadeia de absorvidos (pedido num mesclado, `409 GUEST_ALREADY_MERGED`).
- **E04:** ~~lista dos códigos padrão e grupos de receita; tipos de `FolioOwner`; semântica do ajuste (sinal, código próprio) e chaves de limite~~ — decididas pelo usuário em 2026-10-17 (entrevista do orquestrador, todas pela opção recomendada; ver H5, H16 e o handoff da E04): grupos de receita num enum fixo e oito códigos padrão; `FolioOwner` com cinco tipos fixos (`RESERVATION`, `GROUP`, `EVENT`, `COMPANY`, `WALK_IN`) e id opaco, só `COMPANY` conferido; ajuste parcial no mesmo código com sinal oposto e motivo, estorno do que resta e um só por lançamento; `maxAmount` (`MONEY`) sobre o valor absoluto, Gerente e Financeiro sem teto.
- **E05:** ~~precedência entre regras de roteamento sobrepostas~~ — decidida pelo usuário em 2026-10-19 (entrevista do orquestrador, todas pela opção recomendada; ver o handoff da E05): a regra que cita o código prevalece sobre a que cobre o grupo de receita; duas regras do mesmo nível da mesma conta de origem que cobrem o mesmo código ou grupo em períodos que se cruzam são recusadas (`409 ROUTING_RULE_OVERLAP`); com A → B e B → C vigentes, o lançamento em A segue a cadeia até C, com a origem registrada; regra que formaria ciclo `422 ROUTING_RULE_CYCLE`; destino encerrado `409 ROUTING_TARGET_CLOSED`; `billing.routing.manage` e `billing.postings.transfer` não sensíveis, só `ALL`, sugeridas a Gerente, Financeiro e Recepcionista.
- **E08:** reembolso com limite de valor além da aprovação; identificação de depósito.
- **E09:** expiração da caução calculada na leitura ou marcada por job.
- **E10:** nomes e colunas das views `report_*` do `billing`.

## Definição global de pronto

- [ ] Hóspedes e empresas podem ser cadastrados, buscados (inclusive por documento, sem decifrar a tabela), corrigidos, mesclados, exportados e anonimizados pela API, com permissões e auditoria; nenhum endpoint, log, trilha ou view devolve raça/cor, deficiência ou número de documento em claro fora do uso autorizado (H9, H10).
- [ ] Uma conta aberta para qualquer `FolioOwner` aceita lançamentos, estornos, ajustes, roteamento, transferências, taxa de serviço, pagamentos manuais, reembolsos e caução, e só fecha com saldo zero.
- [ ] `UPDATE` ou `DELETE` em lançamentos, pagamentos e movimentos de caixa com o papel da aplicação falha no banco (teste de integração e `DatabaseRolesTest`), e as tabelas estão num `AppendOnlyTables`.
- [ ] Recebimento sem turno de caixa aberto do usuário é recusado com `code` estável.
- [ ] Estorno, ajuste acima do limite, reabertura de conta, reembolso e exportação de dados pessoais exigem permissão ou aprovação de superior (`403 APPROVAL_REQUIRED`) e ficam auditados com solicitante e aprovador.
- [ ] As views `report_*` de `guests` e `billing` existem, o papel da aplicação só tem `SELECT` nelas, e nenhuma tem texto livre, dado de hóspede ou dado de cartão além de bandeira e 4 últimos dígitos.
- [ ] Um disparo real de `NotificationsApi.notify` do M3 aparece de ponta a ponta no compose (painel e tópico SSE `notifications` do destinatário), se a H14 for confirmada.
- [ ] `ApplicationModules.verify()` passa, sem dependência fora da ARCHITECTURE §3.1 (`ModularityTest` com um teste por módulo novo).
- [ ] Contrato `api/openapi.yaml` atualizado; `./gradlew build` e `.github/scripts/check-api-breaking.sh` passam numa cópia limpa; o roteiro ponta a ponta passa num `docker compose` isolado.

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
Você é o verificador independente da etapa ETAPA do roadmap docs/roadmaps/roadmap-m3-hospedes-contas.md. Você não participou da implementação e não deve editar nenhum arquivo.
1. Leia CLAUDE.md (se existir), o cabeçalho do roadmap e a seção ETAPA inteira (objetivo, escopo, fora de escopo, estratégia, critérios de aceite, validação obrigatória).
2. Veja o que mudou desde o início da etapa: gere um snapshot do estado atual com o comando da seção "Protocolo de execução" e rode git diff --stat HASH_INICIAL seguido do hash gerado; depois leia o diff completo dos arquivos relevantes.
3. Rode todos os comandos da validação obrigatória da etapa e guarde a saída relevante.
4. Para cada critério de aceite, decida APROVADO ou REPROVADO com evidência concreta (arquivo:linha, nome do teste, saída de comando). Critério sem evidência é REPROVADO.
5. Ataque os pontos listados em "Foco do verificador" na etapa e aponte também: mudanças fora do escopo, testes que não exercitam o comportamento (passariam mesmo com o código errado), segredos ou dados pessoais expostos e violações das restrições do roadmap.
Responda com: parecer geral (APROVADO só se todos os critérios forem APROVADO e não houver problema grave), tabela critério → resultado → evidência, problemas por gravidade e comandos executados.
```

## Execução automática

Cole o prompt abaixo numa sessão limpa do Claude Code, na raiz do repositório. Ajuste o limite de etapas se quiser.

```text
Você é o orquestrador do roadmap docs/roadmaps/roadmap-m3-hospedes-contas.md neste repositório. Seu papel é coordenar subagentes, não implementar: não edite código e mantenha seu contexto enxuto (leia o roadmap e os relatórios dos subagentes, não o código).

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

**Hóspedes e clientes (`guests`)**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E01 | Perfis de hóspedes e empresas | #concluida | — | Módulo `guests` com hóspedes, documentos cifrados com índice cego, dados sensíveis protegidos, VIP, lista de restrição e empresas; `HV000271` corrigido |
| E02 | Detecção e mesclagem de duplicados | #concluida | E01 | Sugestão de duplicados e mesclagem sem perda de histórico, com evento `GuestsMerged` |
| E03 | LGPD: consentimentos, anexos e direitos do titular | #concluida | E01 | Consentimentos, anexos sensíveis, exportação auditada, correção e anonimização sob pedido, view de leitura |

**Contas e caixa (`billing`, `payments`)**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E04 | Códigos de lançamento, contas e lançamentos imutáveis | #concluida | E01 | Módulo `billing` com `TransactionCode`, `Folio`, `Posting`, estorno e ajuste sob aprovação |
| E05 | Roteamento, transferência e divisão | #concluida | E04 | Regras de roteamento aplicadas no lançamento; transferência e divisão manuais |
| E06 | Taxa de serviço | #concluida | E04 | Taxa de serviço automática por código, separada na conta e removível com registro |
| E07 | Pontos e turnos de caixa | #pendente | E04 | Abertura, sangria, suprimento e fechamento (inclusive cego) de turnos; notificação de diferença |
| E08 | Pagamentos manuais, depósitos e reembolsos | #pendente | E07 | Módulo `payments` com `PaymentIntent` e provedor `MANUAL`; pagamentos na conta com troco |
| E09 | Caução registrada manualmente | #pendente | E08 | `CardPreAuthorization` manual com captura total ou parcial e liberação |
| E10 | Encerramento, reabertura, extrato e views de leitura | #pendente | E05, E06, E09 | Ciclo de vida da conta, extrato em JSON e views `report_*` de `billing` |
| E11 | Gate do marco M3 | #pendente | E02, E03, E10 | Definição global de pronto verificada do zero, com o compose |

---

## E01 — Perfis de hóspedes e empresas — #concluida

**Objetivo:** criar o módulo `guests` com o cadastro de hóspedes (pessoa física) com os campos exigidos pela FNRH, documentos com número cifrado e busca por índice cego, dados sensíveis da FNRH cifrados e invisíveis na API de consulta, preferências, VIP, lista de restrição e cadastro de empresas; e corrigir o aviso `HV000271` antes de os DTOs do marco se multiplicarem.

**Por que agora:** `billing` (E04) usa empresas como pagadoras, e reservas (M4) e recepção (M5) dependem de hóspedes. É o primeiro módulo com dados pessoais em volume; o padrão de proteção definido aqui (índice cego, documento mascarado, retratos sem dado pessoal) se repete nos marcos seguintes.

**Dependências e paralelismo:** nenhuma dentro deste roadmap; exige o gate do M2 `#concluida` (atendido). Não paralelizável.

**Escopo:**

- Pacote `com.zeny.hotel.guests` (a criar): `GuestsModule.kt` com `@ApplicationModule(allowedDependencies = ["shared"])` (`files` entra na E03; `hotel` só se a etapa usar, e então acrescentar à ARCHITECTURE §3.1), `GuestsPermissions` (bean numa `@Configuration` de `application`), `GuestsErrorCodes` e a API pública `GuestsApi` na raiz (consultas por id e busca, alerta da lista de restrição para os módulos de cima, `company(id)` para o `billing` e a leitura dos dados exigidos pela FNRH, inclusive os sensíveis decifrados, documentada como exclusiva do `fnrh`, M5); subpacotes `domain`, `application`, `infrastructure`, `web`. Teste novo no `ModularityTest` com as dependências exatas.
- Pasta `src/main/resources/db/migration/guests/` (timestamp posterior a `V2026_10_16_1741`) com as tabelas, a migração de `GRANT` ao `"${app_role}"` e um trigger que recusa `DELETE` (perfis e empresas nunca são apagados; a anonimização é da E03).
- `Guest` (RF-HOS-01, RF-HOS-02): nome, nome social, nacionalidade ISO, data de nascimento, gênero, e-mail (normalizado para a busca), telefone, país de residência, endereço (`Address` do `shared` para endereço no Brasil; o de estrangeiro é decisão da entrevista da etapa); `isMinorOn(date)` (18 anos, RN-24; as faixas de criança de `HotelApi.policy()` são tarifa, do M4); preferências, VIP e observações. Restrições alimentares só com consentimento (o consentimento chega na E03: até lá o campo é recusado com `code` estável).
- `IdDocument` (CPF, passaporte e outros) com número cifrado (`EncryptedStringConverter`; CPF validado com `Cpf` e gravado como `cpf.digits` cifrado). Busca por documento pelo índice cego da H8: porta nova no `shared` (raiz, ao lado do `TokenSealer`; implementação em `shared/infrastructure/crypto/`, derivando por HKDF de cada chave do anel como o `HkdfTokenSealer`), coluna com o id da chave e o hash, indexada, busca com todas as chaves do anel. Respostas com o documento mascarado e ação própria para revelá-lo, com permissão e trilha (H9).
- `SensitiveGuestData` (raça/cor, deficiência) cifrado, gravável pela API de cadastro e **nunca** devolvido nas respostas de consulta, na trilha nem em log (RN-19).
- Lista de restrição (RF-HOS-04) com motivo, como ação com permissão própria (não sensível: RF-ACS-08 não a lista); método da `GuestsApi` para consultar o alerta.
- `Company` (RF-HOS-06): razão social, nome fantasia, `Cnpj`, IE/IM, endereço, contatos, e-mail de cobrança, limite de crédito em `Money`.
- Endpoints em `/api/v1/guests` e `/api/v1/companies` (`@ApiV1Controller`, `@Tag("guests")`, `operationId` únicos) com busca por nome, documento e e-mail, paginação por cursor (`PageParams`/`CursorPage`, ordem estável terminando pelo id), `ETag`/`If-Match` e desativação de empresa por ação (sem `DELETE`).
- Permissões no padrão da H16, com `templates` para Recepcionista, Gerente e Financeiro; auditoria `@Audited` em toda escrita (ações como `guests.guest.create` e `guests.company.update`), com retratos conforme a H10.
- `HV000271` (H15): DTOs novos com `List<@Valid T>`; converter os dez DTOs existentes que o aviso lista, com um teste que prova que um item inválido dentro de uma lista ainda dá `400 VALIDATION_FAILED`.
- `CLAUDE.md` (estado atual e seção do `guests`, inclusive o índice cego como convenção para coluna cifrada pesquisável) e seção "API" do `README.md`.

**Fora de escopo:** mesclagem (E02); consentimentos, anexos e direitos do titular (E03); histórico de estadias (M6); qualquer fonte offline ou tópico SSE (H7).

**Passos de implementação:**

1. Confirmar no `CLAUDE.md` as convenções de permissões (`ModulePermissions`, `templates`), escopo, auditoria, cifragem e `report_*`, e no código como o `HkdfTokenSealer` deriva as chaves.
2. Escrever testes: domínio (`isMinorOn` nos limites, normalização de documento e e-mail), índice cego (mesmo valor → mesmo índice; rotação do anel ainda encontra), integração da API (cadastro, busca por documento, ausência dos dados sensíveis e do número inteiro nas respostas, revelar com e sem permissão e com a trilha, 403 sem permissão) e a cascata de `List<@Valid T>`.
3. Implementar a porta do `shared`, migração, domínio, casos de uso, controllers e permissões; converter os DTOs do `HV000271`.
4. Regenerar `api/openapi.yaml` e atualizar a documentação.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "quais campos e regras a SPEC §5.5 (RF-HOS-01, 02, 04, 06), RN-19, RN-24 e §8.2.4 definem para hóspede, documento e empresa?"; "quais campos a FNRH exige do hóspede segundo a SPEC §5.9 e §8.2.17?"; "como `shared/infrastructure/crypto/HkdfTokenSealer.kt` e o `EncryptionKeyRing` derivam e escolhem chaves, e como o `EncryptedStringConverter` grava o valor?"; "o Kotlin 2.4 do build emite `@Valid` de uso de tipo em `List<@Valid T>` no bytecode, de modo que o Hibernate Validator o veja?".
- Partes paralelas: nenhuma.
- Foco do verificador: raça/cor, deficiência ou número de documento aparecendo em resposta, log, mensagem de erro, trilha ou contrato; busca por documento que decifra tudo; índice cego com chave fixa no código ou sem rotação; validação em cascata perdida na troca do `HV000271`; escrita sem auditoria; empresa com CNPJ inválido aceita; `guests` importando `iam`.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E01 — "Perfis de hóspedes e empresas" do roadmap docs/roadmaps/roadmap-m3-hospedes-contas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo "Contexto confirmado", restrições, as hipóteses H8, H9, H10, H15 e H16 e o "Protocolo de execução" —, a seção E01 inteira e docs/SPEC.md §5.5, RN-19, RN-24 e §8.2.4. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que a etapa E10 (gate) de docs/roadmaps/roadmap-m2-plataforma-operacional.md está #concluida e que as hipóteses H8, H9 e H10 já foram decididas (se a tabela ainda disser "a decidir na sincronização", pare e peça a decisão); se não, ou se o código divergiu do que este roadmap assume, pare e relate sem implementar.

Objetivo: criar o módulo guests com hóspedes (campos da FNRH, preferências, VIP), documentos com número cifrado e busca por índice cego numa porta nova do shared (H8), documento mascarado nas consultas e revelado só por ação auditada (H9), dados sensíveis da FNRH (raça/cor, deficiência) cifrados e nunca devolvidos, lista de restrição com permissão própria e cadastro de empresas, em /api/v1/guests e /api/v1/companies, com retratos da trilha conforme a H10; e corrigir o aviso HV000271 nos DTOs existentes (H15). Não implemente mesclagem, consentimentos, anexos nem exportação (E02 e E03), e guests não importa iam.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E01 para #em-andamento no índice e no título;
- investigação: um subagente Explore responde às perguntas da seção E01 (SPEC, campos da FNRH, derivação HKDF do HkdfTokenSealer, emissão de List<@Valid T> pelo Kotlin);
- leve ao usuário antes de decidir sozinho (numa sessão manual, por entrevista; como subagente, no relatório ao orquestrador) as decisões abertas da E01 listadas no cabeçalho (endereço de estrangeiro, tipos de documento, unicidade de CNPJ, busca por nome sem acento);
- escreva os testes de domínio e de integração primeiro, implemente, atualize CLAUDE.md e README.md, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em dado sensível ou número de documento exposto, busca que decifra tudo, índice cego sem rotação, cascata de validação perdida e escrita sem auditoria.

Critérios de aceite (detalhes na seção E01):
- cadastro, busca por nome, documento e e-mail e edição com ETag cobertos por testes de integração;
- busca por documento pelo índice cego, que sobrevive à rotação do anel; documento mascarado nas consultas e revelado só com permissão e trilha;
- nenhuma resposta, trilha, log nem o contrato expõem raça/cor, deficiência ou documento em claro; escrita sem permissão → 403; toda escrita auditada;
- CPF e CNPJ inválidos → 400 com code estável; HV000271 sumiu do boot sem perder a validação em cascata; build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E01 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] `POST`, `GET`, `PUT` (com `If-Match`) e busca paginada de `/api/v1/guests` e `/api/v1/companies` cobertos por testes de integração com Testcontainers.
- [ ] Busca por número de documento encontra o hóspede pelo índice cego, sem decifrar os registros (teste e inspeção da consulta), e continua encontrando depois de uma chave nova ativa no anel (teste).
- [ ] As consultas devolvem o documento mascarado; revelar o número inteiro sem a permissão → `403`; com ela, `200` e uma entrada na trilha (H9).
- [ ] Raça/cor e deficiência são gravadas cifradas (conferido no banco) e não aparecem em nenhuma resposta de consulta, no `api/openapi.yaml` de leitura, na trilha nem em logs (teste).
- [ ] CPF e CNPJ inválidos → `400` com `code` estável.
- [ ] Incluir ou retirar da lista de restrição sem a permissão própria → `403`; com ela, auditado; a `GuestsApi` devolve o alerta.
- [ ] Toda escrita gera entrada de auditoria, e os retratos seguem a H10 (teste que lê `audit.audit_entry` e não acha nome, contato nem documento em claro).
- [ ] `isMinorOn(date)` testado nos limites de idade.
- [ ] O boot não emite mais `HV000271`, e um item inválido dentro de uma lista validada ainda responde `400 VALIDATION_FAILED` (teste).
- [ ] `ModularityTest` com o teste do `guests`; contrato regenerado; `CLAUDE.md` e `README.md` atualizados; `./gradlew build` e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.
- Inspeção manual: `grep` por `raceColor`/`disability` (ou nomes equivalentes) nos DTOs de resposta e no `api/openapi.yaml` → nenhum campo de leitura; log do boot sem `HV000271`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Índice cego e rotação: a busca com todas as chaves do anel custa uma consulta com N hashes; com uma ou duas chaves é desprezível. Registrar no handoff a convenção, porque `fnrh` e `reservations` vão buscar por documento.
- Converter os DTOs do `HV000271` mexe em `iam`, `checklists`, `notifications` e `shared`: a suíte existente de validação desses módulos cobre a regressão; rodar o build completo.

**Evidências e handoff:**

- Snapshot inicial: `929ef636386734e8f4652abde44dd10e532cee44` (tirado pelo orquestrador; árvore limpa no commit `23faefd`).
- Arquivos alterados:
  - `shared`: `FieldEncryption.kt` (porta `BlindIndex` e `BlindIndexValue`), `infrastructure/crypto/HmacBlindIndex.kt` (novo), `FieldEncryptionConfiguration.kt` (bean), migração `shared/V2026_10_17_0200__enable_pg_trgm.sql`; `web/SyncController.kt` (H15).
  - Módulo `guests` (novo): `GuestsModule.kt`, `GuestsPermissions.kt`, `GuestsErrorCodes.kt`, `GuestsApi.kt`; `domain/` (`Guest.kt`, `GuestDocument.kt`, `Company.kt`, `IdDocuments.kt`, `SensitiveGuestData.kt`, `GuestsRules.kt`); `application/` (`GuestService.kt`, `CompanyService.kt`, `GuestsApiService.kt`, `GuestsPorts.kt`, `GuestsAudit.kt`, `GuestsSupport.kt`); `infrastructure/` (`GuestsPersistence.kt`, `JdbcGuestsDirectory.kt`); `web/` (`GuestsController.kt`, `CompaniesController.kt`, `GuestsDtos.kt`); migrações `guests/V2026_10_17_0201__create_guests.sql` e `V2026_10_17_0202__grant_guests_to_app_role.sql`.
  - H15: `build.gradle.kts` (`-Xemit-jvm-type-annotations`) e `List<@Valid T>` em `iam/web/RolesController.kt`, `checklists/web/ChecklistsDtos.kt`, `checklists/web/ChecklistExecutionsDtos.kt`, `checklists/application/ChecklistOfflineHandlers.kt`, `hotel/web/HotelController.kt`, `notifications/web/NotificationsDtos.kt` e `shared/web/SyncController.kt` (os 11 elementos do aviso: `grants`, `limits`, `applicability` ×2, `sections` ×2, `items`, `options`, `actions`, `answers` ×2, `childAgeBands`, `commands`, `preferences`).
  - Testes: `guests/GuestsDomainTest.kt`, `guests/GuestsApiTest.kt`, `guests/CompaniesApiTest.kt`, `guests/GuestsTestSupport.kt`, `shared/infrastructure/crypto/HmacBlindIndexTest.kt`, `architecture/ListCascadeValidationTest.kt` (novos); `ModularityTest.kt` (teste do `guests`), `iam/AuthorizationTest.kt` (o Gerente recebe as seis permissões do `guests`), `testsupport/TestEncryptionKeys.kt` (anel com a chave ativa `test` e a anterior `previous`).
  - Documentação e contrato: `api/openapi.yaml` (só acréscimos: 13 caminhos e 17 schemas), `CLAUDE.md` (estado atual, índice cego nos tipos do kernel, `List<@Valid T>` nas convenções de API, `pg_trgm` no `shared` e a seção "Hóspedes e empresas"), `README.md` (seção "API", cifragem e estrutura), `docs/ARCHITECTURE.md` §8.3 (busca em coluna cifrada só por índice cego); este roadmap.
- Decisões:
  - **Do usuário (2026-10-16, entrevista do orquestrador):** (1) hóspede estrangeiro informa **só o país** de residência; o endereço completo (o `Address` do `shared`, com IBGE) é só de residente no Brasil (`400 ADDRESS_REQUIRES_BRAZIL_RESIDENCE`) — o usuário não escolheu a opção recomendada de dois formatos; (2) tipos de documento da **lista da FNRH**: `CPF`, `RG`, `CNH`, `PASSPORT`, `RNM`, `FOREIGN_ID`, cada um com país emissor; (3) **CNPJ único sempre** (`company_cnpj_uk`, 409; a desativada é reativada); (4) busca por nome por **coluna normalizada + `pg_trgm`** (extensão no `shared`, schema `public`, índice GIN).
  - **Do implementador (padrões, a revisar na revisão humana):**
    - Documentos opcionais no cadastro (a SPEC §8.2.4 diz `1..*`): reserva por telefone nasce sem documento; a exigência para a FNRH fica no check-in (M5). Data de nascimento também opcional, com `isMinorOn` devolvendo `null` sem ela (o M5 exige a data no check-in de menor, RN-24).
    - País emissor: documentos brasileiros sempre `BR` (omitido = `BR`); passaporte exige o país; identidade estrangeira nunca `BR`. Índice cego sobre o tipo, o país e o número normalizado, unidos por `:` (H8; ex.: `CPF:BR:52998224725`): a busca exige o tipo (e o país, nos estrangeiros); o mesmo número de outro país não casa.
    - Busca por documento em `POST /api/v1/guests/search-by-document` com o número **no corpo** (nunca na URL, que pode ir a logs de proxy); nome e e-mail seguem em `GET` (query).
    - Máscara: `***` + os 4 últimos caracteres (2 se o número tiver menos de 8). Revelar (`guests.documents.reveal`) sugerido a Gerente **e Recepcionista** (cada acesso na trilha, H9).
    - Raça/cor como enum das categorias do IBGE (`RaceColor`); deficiência como **código** do domínio da FNRH (`[A-Z0-9_]{1,60}`), conferido contra o domínio no M5. Os dois vão juntos num texto de **tamanho fixo** (96) cifrado numa coluna só: o AES-GCM preserva o tamanho, e um enum curto cifrado sozinho revelaria a resposta pelo tamanho.
    - Documentos e dados sensíveis por ações próprias (`POST …/documents`, `…/remove`, `PUT …/sensitive-data`), fora do `PUT` do perfil (o cliente não conhece o número nem os dados sensíveis para reenviá-los); o `PUT …/sensitive-data` define o valor sem `If-Match` (nada a conferir: nunca é lido).
    - Retratos da trilha (H10): do perfil, só `vip`, `restricted`, `companyId`, `hasSensitiveData` e documentos com id, tipo e país, mais `changedFields`; da empresa, só CNPJ, situação e limite de crédito (nomes, contatos e endereço fora, por coerência com a H10).
    - `GuestsApi.fnrhData` é leitura e **não grava na trilha**; o M5 decide se o envio à FNRH registra o acesso.
    - Escape do `LIKE` com `!`; e-mail gravado em minúsculas; perfil pode citar uma empresa (contato, SPEC §8.2.4).
    - H15: o Kotlin 2.4 **não** grava a anotação de uso de tipo sem opção (conferido com `javap`: só no metadado Kotlin); com `-Xemit-jvm-type-annotations` ela vai ao campo, ao getter e ao construtor, e o Hibernate Validator não duplica as violações (testado).
    - O anel de chaves dos testes passou a ter duas chaves (a ativa e a anterior), como depois de uma rotação, para o teste de integração gravar um índice com a chave anterior.
- Validações executadas:
  - `./gradlew build` (com `checkOpenApi`, detekt, ktlint e `koverVerify`) → `BUILD SUCCESSFUL in 4m 37s`; 671 testes em 86 classes, 0 falhas. Do `guests`: `GuestsApiTest` 12/12, `CompaniesApiTest` 3/3, `GuestsDomainTest` 10/10, `HmacBlindIndexTest` 4/4, `ListCascadeValidationTest` 3/3; `ModularityTest` 12/12, `LayerArchitectureTest` 7/7, `DatabaseRolesTest` 7/7, `MigrationConventionTest` 11/11, `DatabaseMigrationsTest` 8/8, `AuthorizationTest` 15/15. Uma rodada anterior falhou só em `AuthorizationTest` (a lista esperada do modelo Gerente não tinha as permissões novas; corrigida).
  - `./gradlew updateOpenApi` → contrato regenerado; diferença semântica com o de `HEAD` (comparação dos YAML) só com acréscimos: 13 caminhos (`/api/v1/guests…`, `/api/v1/companies…`), 17 schemas e a tag `guests`.
  - `.github/scripts/check-api-breaking.sh` → "No breaking changes to report… Nenhuma mudança incompatível (nível ERR) em /api/v1".
  - Inspeção: nos schemas do `api/openapi.yaml`, `raceColor`/`disability` só em `GuestSensitiveDataRequest` (entrada) e `number` de documento só nas entradas (`GuestDocumentRequest`, `GuestDocumentSearchRequest`) e em `RevealedDocumentResponse` (a ação autorizada); `GuestResponse` e `GuestDocumentResponse` só com `hasSensitiveData` e `maskedNumber`. `HV000271` não aparece em nenhum resultado de teste (os contextos sobem sem ele); a única ocorrência é o controle positivo do `ListCascadeValidationTest` (`DeprecatedForm.items`), que prova que a captura do aviso funciona.
  - `javap -v` de `NotificationPreferencesRequest`: sem a opção, nenhum `RuntimeVisibleTypeAnnotations`; com `-Xemit-jvm-type-annotations`, `@Valid` em `TYPE_ARGUMENT(0)` no campo, no getter e no construtor.
- Parecer do verificador (orquestrador, 2026-10-16, verificador independente novo, snapshot atual `0e4a240844bd7fe5869558a7d9698793c1ada82e`): **APROVADO** — os 10 critérios de aceite aprovados com evidência; `./gradlew build` verde (671 testes, 0 falhas), oasdiff sem `ERR`, as quatro decisões do usuário implementadas como tomadas e as migrações `V2026_10_17_0200`/`0201`/`0202` na convenção e sem colisão. Nenhum problema grave ou médio. Ressalvas baixas:
  1. O KDoc de `GuestsApi.findByDocument` diz que `issuingCountry` é ignorado nos documentos brasileiros, mas um país diferente de `BR` dá `400 DOCUMENT_COUNTRY_INVALID` (`IdDocuments.kt:75`).
  2. O teste de log não confere a ausência do RG (normalizado ou formatado) na saída; na trilha está conferida.
  3. Nome e e-mail vão na query string da busca, e o cursor carrega o `search_name` em base64 (não assinado); hoje não há log de acesso no Caddy nem na aplicação (ponto da revisão humana).
  4. A listagem e a busca decifram os documentos dos perfis da página só para mascarar, com carga LAZY (uma consulta por perfil, até 200): evitável guardando o sufixo mascarado à parte; só desempenho e superfície.
  Pontos para a revisão humana: documentos e data de nascimento opcionais no cadastro (a SPEC diz `1..*`), revelar documento sugerido também à Recepcionista, `fnrhData` sem entrada na trilha e o modelo Financeiro com `guests.profiles.read`.
- Parecer do verificador dos ajustes da revisão humana (orquestrador, 2026-10-17, verificador independente novo, snapshot `69f92a77dea7846043530e4437993483f46130fc`): **APROVADO** — os 10 critérios continuam atendidos e os ajustes a–d conferidos (sufixo coerente com a máscara e com o `CHECK`, nenhum caminho grava o número em claro, `JOIN FETCH` sobre ids já limitados pelo cursor, sem `HHH90003004`; job de reindexação correto, com a linha travada, log só com o id e o teste provando a troca de chave). Ressalvas baixas: (1) três contextos montados à mão (`RealtimeStreamTest`, `ProductionProfileApiTest`, `EventPublicationRegistryTest`) não adiavam o job — **corrigido pelo orquestrador** com `GUESTS_REKEY_DEFERRED`; (2) a listagem ainda decifra `sensitive_data` de cada perfil só para calcular `hasSensitiveData` (o valor nunca sai; candidato a uma coluna booleana à parte numa etapa futura); (3) a ARCHITECTURE §8.3 generalizava o job a todos os valores cifrados — **corrigido pelo orquestrador**. Depois das correções: `./gradlew build` → `BUILD SUCCESSFUL in 4m 41s`, 673 testes, 0 falhas; `.github/scripts/check-api-breaking.sh` sem `ERR`.
- Notas para a próxima etapa:
  - E02 (duplicados): o mesmo documento em dois perfis é permitido (sem unicidade); detecte pelo índice cego (`guests.guest_document.number_index`, comparando os hashes da mesma chave, ou recalculando com `BlindIndex.candidates`) e pelo e-mail normalizado (`guests.guest.email`, já em minúsculas, com índice). A mesclagem precisa mover os documentos (respeitando `guest_document_kind_uk`) e manter os retratos da H10.
  - E03 (LGPD): a anonimização limpa nome, `search_name`, contatos, endereço, preferências, observações, `restriction_reason`, os documentos (linhas inteiras, inclusive o índice) e `sensitive_data`; a trilha já não tem dado pessoal. As restrições alimentares hoje são recusadas (`422 DIETARY_RESTRICTIONS_REQUIRE_CONSENT`): com o consentimento, crie a coluna (cifrada) e troque a recusa. `guests` passa a depender de `files` (ARCHITECTURE §3.1 já lista).
  - E04 (`billing`): use `GuestsApi.company(id)` (`CompanySummary` com `creditLimit` nulo = sem crédito e `active`).
  - Convenção para `fnrh` e `reservations`: busca por documento só pela `GuestsApi.findByDocument` (índice cego), nunca lendo a coluna cifrada; a rotação do anel exige reindexar antes de retirar a chave antiga — o `GuestDocumentRekeyJob` veio nos ajustes da revisão humana (abaixo).
  - Pontos para a revisão humana: documentos e data de nascimento opcionais (SPEC `1..*`); revelar documento sugerido também à Recepcionista; `fnrhData` sem entrada na trilha; nome e e-mail de hóspede na query string da busca.


**Revisão humana (2026-10-16):** decisões do usuário, conduzidas pelo orquestrador depois do parecer APROVADO, e os ajustes feitos em seguida (tag inalterada).

- Decisões confirmadas:
  1. Documentos e data de nascimento **opcionais** no cadastro; a exigência fica para o check-in (M5). SPEC §8.2.4 corrigida (`Guest "1" *-- "0..*" IdDocument`, com a nota abaixo do diagrama); nota para o M5 em "Notas herdadas do M3 E01" de `roadmap-m5-recepcao-fnrh.md` (E02/E03 conferem CPF ou passaporte e a data de nascimento do menor).
  2. `guests.documents.reveal` sugerida a Gerente e Recepcionista: até o M7 a nota é emitida à mão no portal da prefeitura e pede o CPF.
  3. `GuestsApi.fnrhData` continua sem entrada na trilha; o envio da FNRH (M5 E08) é que é auditado (nota no roadmap do M5).
  4. O modelo Financeiro mantém `guests.profiles.read`.
  5. Nome e e-mail continuam na query string da busca, e o cursor com o `search_name`; nota para o M8 em "Notas herdadas" de `roadmap-m8-restaurante-distribuicao.md` e no `CLAUDE.md`: não ligar log de acesso com query string no Caddy nem na aplicação.
- Correções das ressalvas do verificador:
  - (a) KDoc de `GuestsApi.findByDocument`: documento brasileiro com outro país dá `400 DOCUMENT_COUNTRY_INVALID` (e país ausente nos estrangeiros, `BR` na identidade estrangeira e `INVALID_COUNTRY`).
  - (b) Teste de log (`GuestsApiTest`, "every write is audited…"): a saída é conferida contra **todos** os valores da lista, inclusive o RG normalizado e formatado (`123456789`, `12.345.678-9`) e o nome (antes havia duas exceções).
  - (c) **Sufixo mascarado numa coluna à parte** (`guests.guest_document.number_suffix`, `varchar(4)`, `CHECK` `^[A-Z0-9]{2,4}$`). Decisão: **ajustei a própria migração da E01** (`V2026_10_17_0201__create_guests.sql`) em vez de criar outra: ela não foi commitada nem aplicada fora dos testes, e uma migração separada não conseguiria preencher as linhas existentes (o sufixo exige decifrar o número, impossível em SQL). Um banco local que tenha aplicado a versão anterior, não commitada, precisa ser recriado (checksum diferente). A entidade `GuestDocument` não decifra mais nada: guarda o texto cifrado opaco (`encryptedNumber`, sem conversor) e o sufixo; o caso de uso cifra ao gravar (`FieldCipher.encrypt`) e só o reveal (o documento pedido) e a `GuestsApi.fnrhData` decifram. A listagem e a busca carregam os perfis e os documentos da página numa consulta só (`JOIN FETCH` em `GuestJpaRepository.findAllWithDocuments`, sem N+1). Teste: `GuestDocumentKeysTest` — com o número de um documento trocado por um valor ilegível (`v1:missing:…`), a listagem, o detalhe e a busca por documento respondem 200 com o `maskedNumber` do sufixo.
  - (d) **Job de reindexação** `GuestDocumentRekeyJob` (`guests/infrastructure/`, `@Scheduled` + `@SchedulerLock("guests-documents-rekey")`, `zeny.guests.document-rekey.interval` 1 h e `initial-delay` 10 min, adiado nos testes por `GUESTS_REKEY_DEFERRED` no `@IntegrationTest`): seleciona os documentos com o índice ou o número numa chave que não é a ativa (sem `LIKE`, porque `_` é válido no id da chave), em lotes de 100 (até 10 por execução, paginados por id), e cada um numa transação com a linha travada (`FOR UPDATE`): decifra, **re-cifra o número** com a chave ativa (se preciso) e recalcula o índice com ela. Decisão: **escrita técnica, fora da trilha**, como os expurgos (o valor e o documento não mudam, só a chave que os protege, e nenhuma pessoa age); a falha (chave já retirada, valor corrompido) vai ao log só com o id. Para saber que terminou: a métrica `zeny_guests_documents_rekey_pending` (medida no fim de cada execução; `NaN` antes da primeira) ou a consulta do `CLAUDE.md`. A porta `BlindIndex` ganhou `activeKeyId`. Procedimento de rotação documentado no `CLAUDE.md` ("Chaves de cifragem"): acrescentar a chave e apontar a ativa, esperar o job, re-cifrar os demais valores (que ainda não têm job: `guests.guest.sensitive_data`, `files.stored_file.original_name`, `notifications.push_device.token`, os anexos sensíveis; pendência do M8, registrada no roadmap do M8) e só então retirar a antiga. Teste: `GuestDocumentKeysTest` — um documento com o número cifrado e o índice calculado com a chave anterior passa para a ativa (número `v1:test:…`, índice da ativa e diferente do da anterior), a métrica zera, o reveal devolve o mesmo número, a busca o encontra e uma segunda passada não muda nada.
- Arquivos alterados nestes ajustes: `guests/GuestsApi.kt`, `guests/domain/GuestDocument.kt`, `guests/domain/Guest.kt`, `guests/domain/IdDocuments.kt`, `guests/application/GuestService.kt`, `guests/application/GuestsApiService.kt`, `guests/infrastructure/GuestsPersistence.kt`, `guests/infrastructure/GuestDocumentRekeyJob.kt` (novo), `shared/FieldEncryption.kt`, `shared/infrastructure/crypto/HmacBlindIndex.kt`, `src/main/resources/application.yaml` (`zeny.guests.document-rekey.*`), `db/migration/guests/V2026_10_17_0201__create_guests.sql`; testes `guests/GuestDocumentKeysTest.kt` (novo), `guests/GuestsApiTest.kt`, `guests/GuestsDomainTest.kt`, `testsupport/IntegrationTest.kt`; documentação `docs/SPEC.md` (§8.2.4), `CLAUDE.md`, `README.md`, `docs/ARCHITECTURE.md` (§8.3), `docs/roadmaps/roadmap-m5-recepcao-fnrh.md`, `docs/roadmaps/roadmap-m8-restaurante-distribuicao.md` e este roadmap.
- Validações dos ajustes: `./gradlew test --tests 'com.zeny.hotel.guests.*'` → 27 testes, 0 falhas; `./gradlew updateOpenApi` → contrato **sem mudança** pelos ajustes (a diferença com `HEAD` é a mesma da E01); `./gradlew build` → `BUILD SUCCESSFUL in 3m 14s`, 673 testes em 87 classes, 0 falhas (`GuestsApiTest` 12/12, `GuestDocumentKeysTest` 2/2, `GuestsDomainTest` 10/10, `CompaniesApiTest` 3/3, `DatabaseRolesTest` 7/7, `LayerArchitectureTest` 7/7, `ModularityTest` 12/12); `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`; `check_roadmap.py` nos roadmaps do M3, M5 e M8 → sem problema estrutural.

---

## E02 — Detecção e mesclagem de duplicados — #concluida

**Objetivo:** detectar perfis de hóspede duplicados (mesmo documento ou e-mail) e mesclá-los num perfil sobrevivente sem excluir o absorvido e sem perder histórico, publicando `GuestsMerged` para os módulos que referenciam hóspedes (RF-HOS-05, H6).

**Por que agora:** reservas (M4) e contas passam a referenciar hóspedes; o evento de mesclagem precisa existir antes que essas referências se multipliquem.

**Dependências e paralelismo:** E01. Pode correr em paralelo com E03 e com E04 (em worktrees separados); conflito de edição em `api/openapi.yaml`, `CLAUDE.md`, `README.md` e, com E03, no agregado `Guest`.

**Escopo:**

- Consulta de candidatos a duplicado por documento (índice cego da E01, H8) e e-mail normalizado.
- Comando de mesclagem com permissão própria (`guests.profiles.merge`, H16; não sensível): o sobrevivente recebe documentos, contatos e preferências escolhidos; o absorvido fica com status "mesclado" e referência ao sobrevivente; leituras do absorvido informam o sobrevivente. Os dois perfis são travados (`SELECT … FOR UPDATE`) em ordem de id.
- Evento `GuestsMerged(survivorId, mergedId, …)` em `guests/GuestsEvents.kt`, publicado na transação (outbox), documentado na API pública. Sem consumidor de produção no M3: o teste usa `@EnableScenarios` e um listener de teste (modelo `BusinessDateListenerConfiguration`).
- Auditoria da mesclagem com o estado anterior dos dois perfis, com retratos conforme a H10.

**Fora de escopo:** repontar referências em outros módulos (cada módulo acima escuta o evento no seu marco); mesclagem de empresas.

**Passos de implementação:**

1. Testes de domínio da mesclagem (regras de escolha de campos, absorvido não excluído) e de integração (candidatos, endpoint, evento registrado no outbox, corrida de duas mesclagens).
2. Implementar consulta, comando, evento e endpoint.
3. Regenerar o contrato e documentar no `CLAUDE.md` que todo módulo que guarda id de hóspede escuta `GuestsMerged`.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regra em RF-HOS-05 e H6 e no handoff da E01.
- Partes paralelas: nenhuma.
- Foco do verificador: perfil absorvido excluído fisicamente; mesclagem de um perfil com ele mesmo ou de um já mesclado; evento publicado fora da transação; documento duplicado no sobrevivente depois da mesclagem; dado pessoal em claro na trilha.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E02 — "Detecção e mesclagem de duplicados" do roadmap docs/roadmaps/roadmap-m3-hospedes-contas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo as hipóteses H6, H8 e H10 e o "Protocolo de execução" —, a seção E02 inteira, o "Evidências e handoff" de E01 e RF-HOS-05 em docs/SPEC.md §5.5. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: listar candidatos a duplicado por documento (índice cego) e e-mail e mesclar dois perfis de hóspede num sobrevivente, marcando o absorvido como mesclado (sem excluí-lo), auditando o estado anterior e publicando GuestsMerged pelo outbox na mesma transação. Não altere outros módulos: quem referencia hóspedes escuta o evento no seu próprio marco.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E02 para #em-andamento no índice e no título;
- sem investigação delegada; leve ao usuário antes de decidir sozinho (numa sessão manual, por entrevista; como subagente, no relatório ao orquestrador) a regra de escolha dos campos e a herança de restrição e VIP (decisões abertas da E02 no cabeçalho);
- escreva os testes de domínio e de integração primeiro (o evento com @EnableScenarios e um listener de teste), implemente, atualize CLAUDE.md e README.md, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em exclusão física do absorvido, mesclagem de perfil consigo mesmo ou já mesclado e evento fora da transação.

Critérios de aceite (detalhes na seção E02):
- candidatos a duplicado por documento e por e-mail retornados pela API;
- mesclagem mantém o absorvido com status mesclado e referência ao sobrevivente, auditada;
- GuestsMerged registrado no outbox na mesma transação;
- mesclar perfil consigo mesmo ou já mesclado → 409 com code estável; build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E02 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Endpoint de candidatos devolve pares com mesmo documento ou mesmo e-mail normalizado (teste de integração).
- [ ] Mesclagem com permissão: sobrevivente atualizado, absorvido com status mesclado e referência ao sobrevivente, nenhuma linha excluída (teste).
- [ ] Sem permissão → `403`; mesclar consigo mesmo ou perfil já mesclado → `409` com `code` estável; duas mesclagens simultâneas do mesmo absorvido → uma `200` e uma `409`.
- [ ] `GuestsMerged` registrado no *Event Publication Registry* na mesma transação (teste com `Scenario` e listener de teste).
- [ ] Mesclagem auditada com o estado anterior dos dois perfis, sem dado pessoal em claro (H10).
- [ ] Contrato regenerado; `CLAUDE.md` documenta o evento; `./gradlew build` e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Consumidores futuros esquecerem de escutar `GuestsMerged`: registrar no handoff e no `CLAUDE.md` que todo módulo que guarda id de hóspede escuta o evento (o primeiro é `reservations`, M4).

**Evidências e handoff:**

- Snapshot inicial: `5a97e8cb3bb91ae91511a4e45226c628d1d267a2` (tirado pelo orquestrador; árvore limpa no commit `5322341`). Preparação: E01 `#concluida` e o código batia com o handoff dela (módulo `guests`, índice cego, `GuestDocumentRekeyJob`, `number_suffix`).
- Arquivos alterados:
  - Raiz do `guests`: `GuestsApi.kt` (`GuestStatus`, `GuestMergeField`; `GuestSummary` com `status` e `mergedInto`; KDoc das leituras de um mesclado), `GuestsErrorCodes.kt` (`GUEST_MERGE_SAME_PROFILE`, `GUEST_ALREADY_MERGED`, `GUEST_MERGE_DOCUMENT_CONFLICT`), `GuestsPermissions.kt` (`guests.profiles.merge`, só Gerente), `GuestsEvents.kt` (novo: `GuestsMerged`).
  - `domain/Guest.kt`: colunas `status`, `merged_into`, `merged_at`, `merged_by`; `requireActive` em toda escrita; `requireMergeable`, `absorb` (regras de campo, VIP, restrição e plano dos documentos) e `GuestMergeResult`.
  - `application/`: `GuestMergeService.kt` (novo: candidatos e mesclagem), `GuestsPorts.kt` (`guestsForUpdate`, `moveDocuments`, `refresh`, `duplicatePairs`, `DuplicatePairRow`, `DuplicateReason`, `DuplicateCandidateView`; `GuestView` com `status`, `mergedInto`, `mergedAt`), `GuestsSupport.kt`, `GuestsAudit.kt` (`guests.guest.merge`; retrato com `status` e `mergedInto`), `GuestsApiService.kt` (resumo com a situação; `restrictionAlert`, `isMinorOn` e `fnrhData` seguem o sobrevivente).
  - `infrastructure/`: `GuestsPersistence.kt` (trava em ordem, troca do dono dos documentos por `JdbcClient`, `refresh`), `JdbcGuestsDirectory.kt` (`duplicatePairs`; buscas só de perfis ativos).
  - `web/`: `GuestsController.kt` (`GET /api/v1/guests/duplicate-candidates`, `POST /api/v1/guests/{id}/merge`; `409 GUEST_ALREADY_MERGED` nas descrições das escritas), `GuestsDtos.kt` (`MergeGuestRequest`, `DuplicateCandidateResponse`; `GuestResponse` com `status`, `mergedInto`, `mergedAt`).
  - Migração `guests/V2026_10_17_0345__add_guest_merge.sql` (colunas, `guest_status_ck`, FK `guest_merged_into_fk` no próprio schema, índice parcial).
  - Testes: `guests/GuestMergeDomainTest.kt` e `guests/GuestMergeApiTest.kt` (novos), `iam/AuthorizationTest.kt` (o Gerente recebe `guests.profiles.merge`).
  - Documentação e contrato: `api/openapi.yaml` (2 operações novas, 3 schemas novos e 3 propriedades novas em `GuestResponse`; o resto só descrições), `CLAUDE.md` (estado atual e "Duplicados e mesclagem" na seção de hóspedes, com a regra de que todo módulo que guarda id de hóspede escuta `GuestsMerged`), `README.md` (seção "API"), `docs/ARCHITECTURE.md` §3.2 (exemplo do `GuestsMerged`), este roadmap (H6, decisões abertas e este handoff).
- Decisões:
  - **Do usuário (2026-10-17, entrevista do orquestrador, todas pela opção recomendada):** (1) escolha dos campos: vale o que o sobrevivente tem, os vazios dele recebem os do absorvido e os campos listados em `takeFromMerged` vêm do absorvido; (2) restrição e VIP: restrito se qualquer um estiver (motivo do sobrevivente; se só o absorvido estava, o dele), VIP se qualquer um for, nunca se apaga um alerta; (3) documentos: iguais (mesmo índice cego) deduplicados, os demais do absorvido passam para o sobrevivente, o mesmo tipo e país com números diferentes é `409 GUEST_MERGE_DOCUMENT_CONFLICT`.
  - **Do implementador** (a opção mais conservadora em cada ponto; **confirmadas pelo usuário em 2026-10-17**, entrevista do orquestrador antes da E03, que também corrige as ressalvas 2 e 3 do verificador):
    1. Lista dos campos selecionáveis (`GuestMergeField`): `FULL_NAME`, `SOCIAL_NAME`, `NATIONALITY`, `BIRTH_DATE`, `GENDER`, `EMAIL`, `PHONE`, `RESIDENCE` (país de residência **com** o endereço, juntos, porque o endereço completo é só de residente no Brasil), `PREFERENCES` (as três juntas), `NOTES`, `COMPANY`, `SENSITIVE_DATA`.
    2. Um campo listado em `takeFromMerged` que está vazio no absorvido **mantém o do sobrevivente** (a mesclagem nunca troca um valor por vazio).
    3. Restrição herdada do absorvido leva também a data e o autor dele (`since`, `restrictedBy`); o absorvido mantém a própria restrição (histórico).
    4. A cópia de um documento igual **fica no absorvido** (nenhuma linha apagada, como pede o critério); os movidos mantêm o id (só muda `guest_id`). Igualdade pelo índice cego quando os dois têm a mesma chave; no meio de uma rotação, decifrando só os dois documentos em memória.
    5. `POST /{id}/merge` exige o `If-Match` do sobrevivente (mescla-se o que se viu); a situação "já mesclado" é conferida **antes** da versão, para a repetição dar `409 GUEST_ALREADY_MERGED`, nunca `412`.
    6. Um perfil mesclado não é editado (`409 GUEST_ALREADY_MERGED` em `PUT`, documentos, dados sensíveis e restrição), continua legível pelo id (o reveal também) e **sai das buscas** (nome, e-mail, documento, `GuestsApi.findByDocument`) e dos candidatos.
    7. Candidatos exigem só `guests.profiles.read` (consulta, útil na reserva e no check-in); mesclar exige `guests.profiles.merge`, sugerida **só ao Gerente**, não sensível.
    8. `GuestsApi`: `guest(id)` devolve o perfil pedido (com `status` e `mergedInto`); `restrictionAlert`, `isMinorOn` e `fnrhData` de um mesclado leem o **sobrevivente** (seguindo a cadeia), para um alerta não se perder antes de o consumidor repontar.
    9. O sobrevivente pode ser absorvido depois por outro (cadeia A → B → C, um evento por mesclagem). `GuestsMerged` leva só `survivorId`, `mergedId` e `mergedAt` (o instante gravado, na precisão do banco).
    10. Documentos indexados com chaves diferentes do anel (no meio de uma rotação) só aparecem como candidatos depois da reindexação do `GuestDocumentRekeyJob` (a consulta compara o hash, sem decifrar).
- Validações executadas:
  - `./gradlew test --tests 'com.zeny.hotel.guests.*' --tests 'com.zeny.hotel.iam.AuthorizationTest' --tests 'com.zeny.hotel.ModularityTest' --tests 'com.zeny.hotel.architecture.*' --tests '*DatabaseRolesTest' --tests '*MigrationConventionTest' --tests '*DatabaseMigrationsTest'` → 0 falhas (`GuestMergeApiTest` 8/8, `GuestMergeDomainTest` 6/6, `GuestsApiTest` 12/12, `GuestsDomainTest` 10/10, `CompaniesApiTest` 3/3, `GuestDocumentKeysTest` 2/2, `AuthorizationTest` 15/15, `ModularityTest` 12/12, `LayerArchitectureTest` 7/7, `DatabaseRolesTest` 7/7, `MigrationConventionTest` 11/11, `DatabaseMigrationsTest` 8/8).
  - `./gradlew updateOpenApi` → contrato regenerado (acréscimos e descrições).
  - `./gradlew build` (com `checkOpenApi`, detekt, ktlint e `koverVerify`) → `BUILD SUCCESSFUL in 4m 2s`; 687 testes em 89 classes, 0 falhas.
  - `.github/scripts/check-api-breaking.sh` → "No breaking changes to report… Nenhuma mudança incompatível (nível ERR) em /api/v1".
  - Observação de processo: os testes foram escritos junto com a implementação (não foram rodados vermelhos antes); o teste do rollback (publicação registrada dentro da transação e nenhuma depois de desfeita) e o da corrida (`409 GUEST_ALREADY_MERGED`, não `412`, só possível com a trava) exercitam o comportamento e não passariam com o evento fora da transação ou sem a trava.
- Parecer do verificador (orquestrador, 2026-10-17, verificador independente novo, snapshot atual `f7e88a7c2a7cc2cfff4e1224306dbc61aadaf127`): **APROVADO** — os 6 critérios de aceite aprovados com evidência (`GuestMergeApiTest` 8/8, `GuestMergeDomainTest` 6/6); `./gradlew build` verde (687 testes, 0 falhas), oasdiff sem `ERR`; as três decisões do usuário de 2026-10-17 implementadas como tomadas; absorvido nunca apagado, 409 em todas as variantes de mesmo perfil/já mesclado, evento só na transação (teste de rollback) e trilha sem dado pessoal. Nenhum problema grave ou médio. Ressalvas baixas: (1) a empresa trazida do absorvido não passa pela validação de empresa ativa (coerente com "só vínculo novo é validado", a confirmar); (2) no `GuestService.update` a versão é conferida antes de `requireActive`, então um `PUT` num perfil mesclado com `ETag` velho dá `412` em vez de `409 GUEST_ALREADY_MERGED` (na mesclagem a ordem está certa); (3) no teste de API, `takeFromMerged: ["PHONE"]` não verifica nada (os dois perfis sem telefone; o comportamento está coberto no teste de domínio); (4) os testes não foram rodados vermelhos antes da implementação.
- Notas para a próxima etapa:
  - E03 (LGPD): a anonimização de um perfil mesclado precisa decidir se segue para o sobrevivente (o titular é o mesmo) — hoje um mesclado recusa escrita com `409 GUEST_ALREADY_MERGED`, então a anonimização terá de abrir uma exceção explícita para ele ou anonimizar os dois; a exportação do titular deve incluir os perfis absorvidos (`merged_into` = o sobrevivente). Os retratos da trilha agora têm `status` e `mergedInto`. O agregado `Guest` ganhou `status`/`merged_*` e `requireActive` em todas as escritas (conflito de edição previsto com a E03).
  - M4 (`reservations`, H8 do M4): escutar `GuestsMerged` com `@ApplicationModuleListener` idempotente e repontar `mergedId` → `survivorId`; ler `GuestsApi.guest(id).mergedInto` ao receber um id antigo.
  - `GET /api/v1/guests/duplicate-candidates` compara todos os documentos e e-mails a cada chamada (auto-junção indexada); para o porte de um hotel (até 30 UHs) é desprezível; se crescer, filtrar por `guestId` ou materializar.

---

## E03 — LGPD: consentimentos, anexos e direitos do titular — #concluida

**Objetivo:** completar `guests` com consentimentos por finalidade, anexos de hóspede como a primeira finalidade sensível de produção do `files`, e os direitos do titular atendidos sob pedido: exportação auditada (ação sensível), correção e anonimização respeitando retenções legais, mais a view de leitura `report_*` do módulo (RF-HOS-07, RF-HOS-08, RF-ACS-08).

**Por que agora:** a LGPD vale desde o primeiro hóspede cadastrado. A porta de contribuidores (H3) precisa existir para que os marcos seguintes contribuam com seus dados, e a decisão sobre a `FileAccessPolicy` (pendente desde o M1) fecha aqui.

**Dependências e paralelismo:** E01. Pode correr em paralelo com E02 e com E04 (em worktrees separados); conflito de edição em `api/openapi.yaml`, `CLAUDE.md`, `README.md`, na ARCHITECTURE (não deve mudar: a aresta `guests` → `files` já está na §3.1) e, com E02, no agregado `Guest`.

**Escopo:**

- `Consent` com finalidade, concedido/revogado, instante e origem; restrições alimentares (RF-HOS-02) passam a ser aceitas só com consentimento ativo.
- Anexos (documento de identidade, autorização de menor): `GuestsFilePurposes` (`ModuleFilePurposes`, bean numa `@Configuration` de `application`), finalidades **sensíveis** (cifradas pelo `ContentCipher`, servidas só pelo servidor, emissão e cada download na trilha pelo próprio `files`) com upload e download pelas permissões do `guests` (H16), declaradas só com escopo `ALL` (o `OWN` do `files` significa "quem enviou"); foto de documento com `ImageReencoding()` (só JPEG/PNG) e PDF numa finalidade à parte, conforme a entrevista da etapa. Upload pelo `POST /api/v1/files` existente (com `clientFileId` opcional); vínculo ao hóspede por `FilesApi.find` (finalidade conferida) e `attach` (dono = id do hóspede), `detach`/`delete` ao retirar; download pelo `GET /api/v1/files/{id}/download-url` existente. `GuestsModule` passa a depender de `files` (`ModularityTest` atualizado). A porta `FileAccessPolicy` não é criada (H11).
- Porta de contribuidores de dados pessoais (H3) na API pública de `guests`, registrada como beans (lista injetada); implementação para os dados do próprio `guests` e os metadados dos anexos.
- Exportação dos dados de um titular (H12): ação sensível (`authorization.requireOrApproval`, permissão `guests.data.export` com `sensitive = true`, ação `guests.guest.export`, recurso `guests.guest:` seguido do id), resposta com o pacote JSON montado pelos contribuidores e a lista de anexos, auditada sem os dados no retrato, sem `@Idempotent`.
- Correção de dados a pedido do titular (já coberta pela edição da E01): registrar a origem "pedido do titular" na trilha.
- Anonimização sob pedido: remove ou substitui dados pessoais e sensíveis, documentos, índices cegos e anexos (`FilesApi.delete`), mantém o ID e o que a retenção legal exige (RN-20); bloqueada se houver estadia ativa informada por uma porta do `guests` implementada por módulos acima (M4/M5; no M3 a lista de implementações é vazia e não bloqueia, registrado como pendência). Ação sensível ou só com permissão, conforme a entrevista da etapa.
- View `guests.report_guests` pela convenção do `CLAUDE.md` (uma linha por perfil, ids, situação — ativo, mesclado, anonimizado —, nacionalidade, país, UF/município, VIP e datas; **sem** nome, contato, documento, endereço de rua, texto livre nem dado sensível), com `GRANT SELECT`/`REVOKE` por view e consulta provada com um papel que só lê a view.
- `CLAUDE.md` (seção do `guests`: porta de contribuidores, porta de estadia ativa, finalidades; "Anexos": a `FileAccessPolicy` descartada, se a H11 for confirmada) e `README.md`.

**Fora de escopo:** anonimização automática por prazo e expurgo dos dados sensíveis da FNRH (M10); `FilesApi` gravando conteúdo pelo servidor (M5, se a H12 for confirmada).

**Passos de implementação:**

1. Testes de integração: consentimento e restrição alimentar; upload, vínculo e download de anexo sensível com a trilha do `files`; anexo de outra finalidade recusado; exportação exige aprovação e devolve o pacote auditado; anonimização remove dados pessoais, documentos, índices e anexos e preserva o ID; view sem dados sensíveis e consulta só pela view.
2. Implementar consentimentos, finalidades, porta de contribuidores, exportação, anonimização e view.
3. Regenerar o contrato e documentar.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação (`Explore`): "o que a SPEC §5.5 (RF-HOS-02, 07, 08), RN-19, RN-20, RNF-09 e a ARCHITECTURE §8.3 e §14 exigem de consentimento, anexos, exportação e anonimização?"; "como o `checklists` registra a finalidade `checklists.photo` (`ChecklistsFilePurposes`) e confere, vincula e desvincula fotos pela `FilesApi`, e como o `files` audita a emissão e o download de um anexo sensível?"; "como o `ChecklistReportsTest` prova que a consulta só usa as views?".
- Partes paralelas: nenhuma.
- Foco do verificador: exportação sem aprovação ou sem auditoria; anexo sensível servido sem trilha ou por URL longa; anexo de hóspede baixável com `OWN` por quem não enviou; anonimização que deixa dado pessoal em outra coluna, no índice cego, num anexo, na trilha ou na view; view `report_*` com documento, nome ou dado sensível; resposta da exportação guardada pela idempotência.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E03 — "LGPD: consentimentos, anexos e direitos do titular" do roadmap docs/roadmaps/roadmap-m3-hospedes-contas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo as hipóteses H3, H10, H11 e H12 e o "Protocolo de execução" —, a seção E03 inteira e o "Evidências e handoff" de E01. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 está #concluida e que o código bate com o handoff dela, e que H11 e H12 já foram decididas (se a tabela ainda disser "a decidir na sincronização", pare e peça a decisão); se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: acrescentar ao módulo guests consentimentos por finalidade (restrição alimentar só com consentimento), as finalidades sensíveis de anexo do hóspede (GuestsFilePurposes, vínculo pela FilesApi, download pela URL do files com cada acesso auditado), a porta de contribuidores de dados pessoais, a exportação dos dados do titular como ação sensível com requireOrApproval e auditoria (conforme H12), a anonimização sob pedido que preserva o ID e as retenções legais e apaga documentos, índices cegos e anexos, e a view guests.report_guests sem dados pessoais. Não implemente anonimização automática nem expurgo por prazo (M10), não crie a porta FileAccessPolicy se a H11 confirmar isso, e não faça guests depender de módulos acima dele.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E03 para #em-andamento no índice e no título;
- investigação: um subagente Explore responde às perguntas da seção E03 (SPEC e ARCHITECTURE, finalidade checklists.photo como modelo, auditoria de anexo sensível, prova de consulta só pela view);
- leve ao usuário antes de decidir sozinho (numa sessão manual, por entrevista; como subagente, no relatório ao orquestrador) as decisões abertas da E03 listadas no cabeçalho (anonimização sensível ou não, o que fica depois dela, finalidades de anexo, view de empresas);
- escreva os testes de integração primeiro, implemente, atualize CLAUDE.md e README.md, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em exportação sem aprovação, anexo acessível sem trilha, anonimização incompleta e view com dado pessoal.

Critérios de aceite (detalhes na seção E03):
- restrição alimentar recusada sem consentimento ativo e aceita com ele;
- anexo sensível do hóspede só baixado pela URL assinada do files, com emissão e download na trilha;
- exportação sem permissão nem aprovação → 403 APPROVAL_REQUIRED; com aprovação devolve o pacote e fica auditada com solicitante e aprovador;
- anonimização remove dados pessoais, documentos, índices e anexos e mantém o ID; view sem documento, nome nem dado sensível; build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E03 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Consentimento concedido e revogado por finalidade, com instante e origem; restrição alimentar sem consentimento ativo → `422` com `code` estável (teste).
- [ ] Anexo de hóspede: finalidade sensível registrada; upload, vínculo pela `FilesApi` e download pela URL assinada de curta duração, com `files.file.issue-download-url` e `files.file.download` na trilha (teste); anexo de outra finalidade → recusado com `code` estável do `guests`.
- [ ] Exportação sem permissão nem aprovação → `403 APPROVAL_REQUIRED`; com `X-Approval-Token` válido, devolve o pacote com os dados de cada contribuidor e a lista de anexos, e a trilha tem solicitante e aprovador sem os dados exportados.
- [ ] A porta de contribuidores de dados pessoais está na API pública de `guests` e documentada no `CLAUDE.md`.
- [ ] Anonimização sob pedido apaga ou substitui nome, contatos, documento (e o índice cego), endereço, dados sensíveis e anexos, mantém o ID e é auditada; leitura posterior não devolve nenhum dado pessoal (teste).
- [ ] `guests.report_guests` existe, o papel da aplicação só tem `SELECT` nela, a consulta roda com um papel que só lê a view, e ela não contém nome, contato, documento, raça/cor nem deficiência (teste que lê a view).
- [ ] Contrato regenerado; `./gradlew build` e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- A trilha guarda antes e depois: com a H10 confirmada, os retratos do `guests` já não têm dado pessoal; se a H10 for recusada, registrar no handoff que a anonimização não alcança a trilha e abrir pendência para o M10.
- A exportação na resposta (H12) pode ficar grande com muitos contribuidores nos marcos futuros: registrar o tamanho no handoff; se crescer, o M5 reavalia com a `FilesApi` que grava conteúdo.

**Evidências e handoff:**

- Snapshot inicial: `e84d2af001c1616c786a2b0b37d236700ac0d316` (tirado pelo orquestrador; árvore com a E02 concluída e ainda não commitada sobre `5322341`). Preparação: E01 `#concluida` e o código batia com o handoff dela e com o da E02 (agregado `Guest` com `status`/`merged_*` e `requireActive`); H11 e H12 decididas em 2026-10-16.
- Arquivos alterados:
  - Raiz do `guests`: `GuestsModule.kt` (`allowedDependencies = ["shared", "files"]`), `GuestsApi.kt` (`GuestStatus.ANONYMIZED`), `GuestsErrorCodes.kt` (`GUEST_ANONYMIZED`, `GUEST_HAS_ACTIVE_STAY`, `CONSENT_NOT_FOUND`, `GUEST_ATTACHMENT_INVALID`, `GUEST_ATTACHMENT_NOT_FOUND`), `GuestsPermissions.kt` (`guests.attachments.read`/`manage`, `guests.data.export`/`anonymize` sensíveis), `GuestsPrivacy.kt` (novo: `GuestConsentId`, `ConsentPurpose`, `ConsentSource`, as portas `GuestPersonalDataContributor` e `GuestActiveStayCheck`), `GuestsFilePurposes.kt` (novo).
  - `domain/`: `Guest.kt` (restrições alimentares cifradas com o consentimento, consentimentos no agregado, `requireActive` com `ANONYMIZED`, `anonymize`, `ConsentGrant`, `ConsentChange`), `GuestConsent.kt` (novo).
  - `application/`: `GuestService.kt` (consentimentos no cadastro, `requireActive` antes da versão no `update`, `dataSubjectRequest` no `update` e nos documentos), `GuestsPorts.kt`, `GuestsSupport.kt` (bean das finalidades), `GuestsAudit.kt` (ações novas, `origin`, retratos com `anonymized` e `hasDietaryRestrictions`), `GuestConsentService.kt`, `GuestAttachmentService.kt`, `GuestDataSubjectService.kt` e `GuestsOwnPersonalData.kt` (novos).
  - `infrastructure/`: `JdbcGuestsDirectory.kt` (`absorbedChain`), `JdbcGuestAttachments.kt` (novo). `web/`: `GuestsController.kt`, `GuestsDtos.kt`, `GuestPrivacyController.kt` e `GuestPrivacyDtos.kt` (novos).
  - `files`: `domain/StoredFile.kt` (o nome original sai na exclusão e no expurgo).
  - Migrações `guests/V2026_10_17_0440__add_guest_privacy.sql` (colunas de restrição alimentar e anonimização, `guest_status_ck` com `ANONYMIZED`, `guest_anonymized_ck`, `guest_consent` com trigger contra `DELETE`, `guest_attachment`) e `guests/V2026_10_17_0441__create_guest_report_view.sql` (view `guests.report_guests` com `GRANT SELECT`/`REVOKE`).
  - Testes: `guests/GuestPrivacyApiTest.kt` e `guests/GuestPrivacyDomainTest.kt` (novos); `guests/GuestsApiTest.kt` (modelos e sensíveis), `guests/GuestMergeApiTest.kt` (ajustes a e b), `ModularityTest.kt` (`guests` → `shared`, `files`), `iam/AuthorizationTest.kt` (o Gerente recebe as quatro permissões novas).
  - Documentação e contrato: `api/openapi.yaml` (8 operações novas e propriedades novas, só acréscimos), `CLAUDE.md` (estado atual, seção de hóspedes com consentimentos, anexos, direitos do titular e a view; "Anexos" com a `FileAccessPolicy` descartada e o nome original apagado), `README.md` (seção "API"), este roadmap (H3, decisões abertas da E03 e este handoff). A ARCHITECTURE não mudou (a aresta `guests` → `files` já estava na §3.1).
- Decisões:
  - **Do usuário (2026-10-17, entrevista do orquestrador, todas pela opção recomendada):** (1) anonimização **sensível** (`guests.data.anonymize`, `sensitive = true`, `requireOrApproval`, como a exportação); (2) depois da anonimização ficam o id, a situação `ANONYMIZED` (data e autor), os consentimentos (prova, RN-20), a nacionalidade, o país de residência, a UF e o município e o VIP, e sai todo o resto (nome e nome social, contatos, endereço de rua, data de nascimento, documentos com número cifrado, índice cego e sufixo, dados sensíveis da FNRH, preferências, observações, restrição com o motivo e anexos pelo `FilesApi.delete`), conferido no banco por `guest_anonymized_ck` e nos testes contra outras colunas, o `search_name`, o índice cego, a trilha e a view; (3) três finalidades sensíveis, cifradas, servidas pelo servidor, só `ALL`, upload `guests.attachments.manage` e download `guests.attachments.read`: `guests.id-document-photo` e `guests.minor-authorization-photo` (JPEG/PNG com `ImageReencoding()`) e `guests.document-pdf` (PDF sem reencode), com os nomes como propostos (no padrão `<módulo>.<nome-kebab>`); (4) sem view de empresas, só `guests.report_guests`; (5) exportação e anonimização pedidas no sobrevivente abrangem a cadeia de absorvidos (os anonimiza também; a exportação os inclui) e o pedido num mesclado é `409 GUEST_ALREADY_MERGED`.
  - **Ajustes da E02 aprovados pelo usuário:** (a) `GuestService.update` confere `requireActive` antes da versão: `PUT` num perfil mesclado com `ETag` velho dá `409 GUEST_ALREADY_MERGED` (coberto no `GuestMergeApiTest` com `If-Match: "0"`); a mesclagem já conferia nessa ordem e é a única outra escrita com versão. (b) No `GuestMergeApiTest`, o caso `takeFromMerged: ["PHONE"]` agora tem telefone nos dois perfis e confere que o sobrevivente fica com o do absorvido.
  - **Do implementador** (a opção mais conservadora em cada ponto; **confirmadas pelo usuário em 2026-10-17**, entrevista do orquestrador depois da verificação, exceto a 1, trocada, e a parte da 8 sobre consentimentos; ver "Correções depois da verificação"):
    1. ~~**Exportação com documentos mascarados e sem os dados sensíveis da FNRH**~~ — **trocada pelo usuário**: o pacote é completo (abaixo).
    2. Consentimentos com as finalidades `MARKETING` e `DIETARY_RESTRICTIONS` e a origem num enum fechado (`IN_PERSON`, `PAPER_FORM`, `PHONE`, `EMAIL`, `WEBSITE`), sem texto livre; dar e revogar exigem `guests.profiles.manage` e listar `guests.profiles.read` (sem permissão nova); a revogação marca a linha (nunca apaga) e um consentimento novo é outra linha; um ativo por finalidade (índice único parcial, com o perfil travado).
    3. **Revogar o consentimento de restrições alimentares apaga as restrições gravadas** (sem a base legal, o dado sai).
    4. O nome completo do anonimizado vira o texto fixo "Hóspede anonimizado" (a coluna é `NOT NULL` e a API publica `fullName` obrigatório; mudar isso quebraria o contrato), e o `search_name` o dele; o perfil anonimizado sai das buscas (só `ACTIVE` volta). Gênero e o vínculo com a empresa também saem (não estão na lista do que fica).
    5. O perfil absorvido anonimizado com o sobrevivente **continua `MERGED`** (com `merged_into`, para quem guarda o id antigo seguir repontando), marcado por `anonymized_at`; a view tem a coluna `anonymized`.
    6. A autorização (`requireOrApproval`) vem antes de carregar o perfil (não revela se ele existe); uma aprovação obtida para um perfil mesclado é desperdiçada (o `409` desfaz a transação e o token volta a valer até vencer).
    7. A exportação de um perfil anonimizado é permitida (devolve o que ficou: consentimentos e dados agregados).
    8. Vincular um anexo exige só `guests.attachments.manage` (não confere quem enviou); retirar **apaga** o anexo (não só desvincula) e vale também num perfil mesclado (apagar dado pessoal não é recusado). A mesclagem não move anexos (ficam no absorvido e a cadeia os alcança); os consentimentos passaram a ser movidos (abaixo).
    9. O `files` passou a **apagar o nome original** (`original_name`, cifrado) na exclusão e no expurgo de órfãos: o nome pode citar o titular ("rg-maria.jpg"), e a anonimização não deixaria nada dele no `files` além dos metadados técnicos (tipo, tamanho, SHA-256).
    10. A remoção de anexo responde `204`; vincular de novo o mesmo anexo e registrar de novo um consentimento ativo respondem `200` com o existente.
    11. A correção a pedido do titular é o parâmetro `?dataSubjectRequest=true` no `PUT /{id}` e nas ações de documento, que grava `origin: DATA_SUBJECT_REQUEST` no retrato.
- Validações executadas:
  - `./gradlew test --tests 'com.zeny.hotel.guests.*'` (com os focados de `ModularityTest`, `architecture.*`, `DatabaseRolesTest`, `MigrationConventionTest`, `DatabaseMigrationsTest`, `AuthorizationTest` e `files.*` numa rodada anterior) → 0 falhas depois de dois ajustes de expectativa (o modelo da Recepcionista no `GuestsApiTest` ganhou as duas de anexo; o resultado da trilha do download é `SUCCEEDED`). Os testes de integração da E03 foram escritos antes da implementação dos serviços, mas rodados só depois de ela compilar (não houve rodada vermelha registrada).
  - `./gradlew updateOpenApi` → contrato regenerado (8 operações novas: `listGuestConsents`, `grantGuestConsent`, `revokeGuestConsent`, `listGuestAttachments`, `attachGuestFile`, `removeGuestAttachment`, `exportGuestData` e `anonymizeGuest`, com o parâmetro opcional `dataSubjectRequest`; `consents` opcional no cadastro). A primeira rodada do oasdiff acusou `new-required-request-property` (`consents` obrigatório pelo Kotlin não nulo): corrigido com `requiredMode = NOT_REQUIRED`.
  - `./gradlew build` (com `checkOpenApi`, detekt, ktlint e `koverVerify`) → `BUILD SUCCESSFUL in 3m 36s`; 696 testes em 91 classes, 0 falhas (`GuestPrivacyApiTest` 6/6, `GuestPrivacyDomainTest` 3/3, `GuestsApiTest` 12/12, `GuestMergeApiTest` 8/8, `GuestMergeDomainTest` 6/6, `GuestsDomainTest` 10/10, `GuestDocumentKeysTest` 2/2, `CompaniesApiTest` 3/3, `FilesApiTest` 8/8, `FilesLifecycleTest` 9/9, `ModularityTest` 12/12, `LayerArchitectureTest` 7/7, `DatabaseRolesTest` 7/7, `MigrationConventionTest` 11/11, `DatabaseMigrationsTest` 8/8, `AuthorizationTest` 15/15). Uma rodada anterior falhou só no detekt (condição complexa, retornos, linhas longas, `Void`), corrigido.
  - `.github/scripts/check-api-breaking.sh` → "No breaking changes to report… Nenhuma mudança incompatível (nível ERR) em /api/v1".
  - `check_roadmap.py` neste roadmap → sem problema estrutural (dois avisos de `<módulo>` literal, da convenção).
- **Correções depois da verificação** (orquestrador, 2026-10-17: parecer APROVADO com ressalvas; problema médio M1 — um consentimento que ficava no absorvido não podia ser revogado, e a restrição alimentar dele continuava legível — e entrevista com o usuário, todas as decisões pela opção recomendada):
  1. **Mesclagem move consentimentos e restrição alimentar:** todos os consentimentos do absorvido passam para o sobrevivente mudando o dono da linha (`guest_id`, por `JdbcClient` depois do `flush`, como os documentos; nenhuma linha apagada nem recriada, a prova fica intacta); o ativo do absorvido numa finalidade em que o sobrevivente já tem um ativo fica **substituído** (colunas novas `superseded_at` e `superseded_by` em `guests.guest_consent`, com `CHECK`, e o índice de ativos `guest_consent_active_uk` passou a ignorar os substituídos; deixa de valer sem fingir uma revogação do titular). A restrição alimentar virou o campo `DIETARY_RESTRICTIONS` de `GuestMergeField` (regra da E02: vale a do sobrevivente, a vazia recebe a do absorvido, pedida em `takeFromMerged` vem do absorvido) e só fica se o sobrevivente terminar com o consentimento dela ativo; o absorvido fica sem consentimento e sem restrição alimentar. Trilha: cada consentimento movido com o `guestId` de antes e de depois e `supersededBy`; o retrato do absorvido com `dietaryRestrictions`/`consents` em `changedFields` (sem dado pessoal). **Forma escolhida pelo implementador:** mudar o dono (em vez de revogar e recriar), e **editei a própria migração `V2026_10_17_0440`** (não commitada, como no precedente da E01) em vez de criar outra.
  2. **Revogar nunca é recusado** (LGPD art. 8º §5º, ressalva B4): `Guest.revokeConsent` não confere mais a situação; vale num perfil anonimizado e num mesclado, e o pedido pelo id de um mesclado procura o consentimento também no sobrevivente (seguindo a cadeia). Dar consentimento num mesclado ou anonimizado continua `409`.
  3. **Exportação com o pacote completo** (direito de acesso, LGPD art. 18): a seção `guests` leva o número inteiro dos documentos (`number`) e raça/cor e deficiência decifradas (`raceColor`, `disability`) da cadeia inteira. Continua ação sensível com aprovação, trilha sem os dados, sem `@Idempotent` e com `Cache-Control: no-store`. A exceção à RN-19 (o próprio titular recebe os dados) está no `CLAUDE.md`.
  4. As restrições alimentares continuam em claro no `GET` para quem tem `guests.profiles.read` (sem mudança).
  5. `?dataSubjectRequest=true` também em `PUT /{id}/sensitive-data` (lacuna B2).
  6. Teste novo do expurgo de órfãos apagando `original_name` (`FilesLifecycleTest`), ressalva do verificador.
  - Arquivos destas correções: `guests/GuestsApi.kt` (`GuestMergeField.DIETARY_RESTRICTIONS`), `guests/domain/Guest.kt` (`absorb` com consentimentos e dieta, `ConsentMove`, revogação sem `requireActive`, `REFRESH` nos consentimentos), `guests/domain/GuestConsent.kt` (`supersededAt`/`supersededBy`), `guests/application/GuestsPorts.kt` (`moveConsents`, `ConsentView` com a substituição), `GuestMergeService.kt` (move e audita), `GuestConsentService.kt` (revogação pela cadeia), `GuestsOwnPersonalData.kt` (pacote completo), `GuestService.kt`, `GuestsSupport.kt`, `GuestsAudit.kt`, `guests/infrastructure/GuestsPersistence.kt`, `guests/web/GuestsController.kt`, `GuestPrivacyDtos.kt`, `GuestPrivacyController.kt`, a migração `V2026_10_17_0440__add_guest_privacy.sql`; testes `GuestPrivacyApiTest.kt` (teste novo da mesclagem com consentimento e dieta só no absorvido e nos dois, revogação num mesclado, inclusive um consentimento antigo deixado nele, e num anonimizado; exportação completa; origem nos dados sensíveis), `GuestPrivacyDomainTest.kt` (dois testes novos), `files/FilesLifecycleTest.kt`; `CLAUDE.md`, `README.md`, `api/openapi.yaml`.
  - Validações das correções: `./gradlew test --tests 'com.zeny.hotel.guests.*' --tests 'com.zeny.hotel.files.FilesLifecycleTest'` → 0 falhas depois de dois ajustes nos dados do teste novo (perfis sem o CPF padrão, para não conflitarem na mesclagem; passaporte em maiúsculas, como fica gravado — sem isso, a conferência "nenhum dado pessoal sobra" do passaporte passaria à toa). `./gradlew updateOpenApi` → contrato com `supersededAt`/`supersededBy` no consentimento, `DIETARY_RESTRICTIONS` em `GuestMergeField`, `dataSubjectRequest` no `PUT …/sensitive-data` e descrições. `./gradlew build` → `BUILD SUCCESSFUL in 5m 32s`, 699 testes em 91 classes, 0 falhas (`GuestPrivacyApiTest` 7/7, `GuestPrivacyDomainTest` 5/5, `GuestMergeApiTest` 8/8, `GuestMergeDomainTest` 6/6, `GuestsApiTest` 12/12, `FilesLifecycleTest` 9/9, `DatabaseRolesTest` 7/7, `MigrationConventionTest` 11/11, `ModularityTest` 12/12, `LayerArchitectureTest` 7/7); a rodada anterior falhou só no detekt (`TooManyFunctions` em `GuestsStore`/`JpaGuestsStore`, suprimido com justificativa). `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`. `check_roadmap.py` → sem problema estrutural.
- **Ajustes das ressalvas da 2ª verificação** (orquestrador, 2026-10-17, parecer APROVADO com quatro ressalvas menores):
  1. Contrato do `revokeGuestConsent`: a descrição dizia `409` num perfil mesclado ou anonimizado; agora diz que a revogação nunca é recusada e que, pelo id de um mesclado, o consentimento é procurado na cadeia até o sobrevivente (`./gradlew updateOpenApi`).
  2. `CLAUDE.md`, "Anonimização": o perfil anonimizado recusa as escritas com `409 GUEST_ANONYMIZED`, **exceto** a revogação de consentimento e a retirada de anexo.
  3. Deadlock: a revogação lê a cadeia sem trava (`GuestsDirectory.survivorChain`, `WITH RECURSIVE` subindo por `merged_into`) e trava todos os perfis dela juntos por `guestsForUpdate`, em ordem de id, a mesma da mesclagem e da anonimização (antes travava o mesclado e depois o sobrevivente, ordem que podia cruzar e dar `40P01`). Se uma mesclagem concluída entre a leitura e a trava estendeu a cadeia, responde `409 CONFLICT` (repita o pedido) em vez de travar fora de ordem.
  4. **A migração `V2026_10_17_0440__add_guest_privacy.sql` foi editada antes do commit** (colunas `superseded_at`/`superseded_by` e o índice de ativos): um banco local ou um volume do compose que já tenha aplicado a versão anterior falha no boot com o checksum diferente e precisa ser recriado (`docker rm -f zeny-db` no `bootRun`, ou `docker compose -f docker/compose.yaml down -v`). Os bancos dos testes nascem do zero a cada execução.
  - Validações destes ajustes: `./gradlew updateOpenApi` → contrato com a descrição nova do `revokeGuestConsent`; `./gradlew build` → `BUILD SUCCESSFUL in 5m 7s`, 699 testes, 0 falhas (`GuestPrivacyApiTest` 7/7, inclusive a revogação pelo id do mesclado, que agora passa pela trava em ordem de id); `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`. Não há teste de concorrência para o deadlock: a garantia é a ordem única das travas, a mesma do `guestsForUpdate` usado pela mesclagem e pela anonimização.
- Parecer do verificador (orquestrador, 2026-10-17): 1ª verificação independente (snapshot `0a0f76216a6e0426c9429bc837990a078b1522f4`) **APROVADO com ressalvas**: os 7 critérios aprovados e um problema médio (M1: consentimento e restrição alimentar presos no perfil absorvido, sem revogação possível), corrigido conforme a entrevista do usuário de 2026-10-17 (ver "Correções depois da verificação"). 2ª verificação independente, com verificador novo (snapshot `1961381f657d9f6c56a1698fb511d8f26baa911d`): **APROVADO**. Os 7 critérios e as cinco correções foram conferidos (consentimentos e dieta movidos na mesclagem com `superseded_*`, revogação nunca recusada, exportação completa, `dataSubjectRequest` no `sensitive-data`, migração 0440 nunca commitada). `./gradlew build` passou com 699 testes e 0 falhas, e o oasdiff não acusou `ERR`. Nenhum problema grave ou médio. Das ressalvas menores, quatro foram ajustadas pelo implementador depois da aprovação: a descrição do contrato do `revokeGuestConsent`; a frase da anonimização no `CLAUDE.md`; as travas da revogação, que passam a seguir a ordem de UUID por `guestsForUpdate`, com `409 CONFLICT` se a cadeia crescer entre a leitura e a trava (decisão do implementador a confirmar); e a nota sobre bancos com a 0440 antiga. Depois dos ajustes, `./gradlew build` passou com 699 testes e 0 falhas, e o oasdiff não acusou `ERR`, sem nova verificação independente. Ficaram sem ajuste: revogar um consentimento substituído responde 200 sem efeito (documentado), e `superseded_by` não tem FK (opcional).
- Notas para a próxima etapa:
  - **M4/M5 (`reservations`, `frontdesk`, `fnrh`):** implementar `GuestPersonalDataContributor` (seção `<módulo>`, só leitura, sem conferir permissão) com os dados de hóspede que guardam, e `GuestActiveStayCheck` (estadia ativa bloqueia a anonimização) — **no M3 nenhuma implementação existe e nada bloqueia** (pendência). A FNRH (M5) lê `GuestsApi.fnrhData`; de um perfil anonimizado, recebe só o texto fixo e nenhum documento.
  - **Tamanho do pacote (H12):** hoje a seção `guests` tem poucos KB por perfil (os perfis da cadeia e os consentimentos) e a lista de anexos só metadados; com os contribuidores dos marcos seguintes, se crescer, o M5 reavalia com a `FilesApi` que grava conteúdo.
  - **M10:** a anonimização automática por prazo (RN-20) reusa `Guest.anonymize` e a mesma cadeia; o expurgo dos dados sensíveis da FNRH também.
  - Fora do alcance do `guests`: o motivo digitado pelo aprovador em `POST /api/v1/auth/approvals` (`iam.approval.reason`) e textos livres de outros módulos podem citar o titular; o SHA-256 e o tamanho dos anexos apagados ficam no `files` (não identificam ninguém).
  - E04 (`billing`): sem mudança na `GuestsApi` usada (`company(id)`).

---

## E04 — Códigos de lançamento, contas e lançamentos imutáveis — #concluida

**Objetivo:** criar o módulo `billing` com códigos de lançamento, contas (`Folio`) para qualquer `FolioOwner`, lançamentos imutáveis na data operacional e correção por estorno ou ajuste vinculados, com permissão, limites e step-up (RF-CTA-01, 02, 03; RN-01, RN-02, RN-03).

**Por que agora:** é o núcleo financeiro: tudo o que vem depois (roteamento, caixa, pagamentos, reservas, PDV, fiscal) lança nesta conta. A garantia de imutabilidade no banco precisa nascer com as tabelas.

**Dependências e paralelismo:** E01 (empresa como pagadora, pela `GuestsApi`). Pode correr em paralelo com E02 e E03 (em worktrees separados); conflito de edição em `api/openapi.yaml`, `CLAUDE.md`, `README.md` e `ModularityTest`.

**Escopo:**

- Pacote `com.zeny.hotel.billing` (a criar): `BillingModule.kt` com `allowedDependencies = ["shared", "hotel", "guests"]` (`payments` entra na E08; `notifications` na E07, se a H14 for confirmada), `BillingPermissions`, `BillingErrorCodes`, `BillingApi`; pasta `src/main/resources/db/migration/billing/` com `GRANT` ao `"${app_role}"`; teste novo no `ModularityTest`.
- `TransactionCode`: código, nome, tipo (débito, crédito, multa, taxa…), grupo de receita, `serviceChargeApplies`, `serviceChargeRate` anulável (cai em `HotelApi.taxSettings().serviceChargeRate`, H4), classificação fiscal opcional em texto (H2), ativo. Códigos padrão pré-carregados por `INSERT INTO billing.…` na migração (diária, multa de cancelamento, multa de no-show, taxa de serviço, A&B com taxa, lavanderia, avulso; H5), editáveis por quem tem a permissão de códigos; nunca apagados.
- `Folio` com número legível (`DocumentNumberGenerator`, sequence `billing.…` na migração, ano da data operacional), `FolioOwner(type, id)` com tipos iniciais (reserva, grupo, evento, empresa, avulsa; id opaco, só a empresa é conferida pela `GuestsApi`), pagador opcional (`Company` pela `GuestsApi`), limite de crédito em `Money`, status aberta/fechada, `balance()`, `version` para `ETag`.
- `Posting` imutável: código, quantidade, valor unitário, valor, descrição, origem (manual, PDV, fechamento, lavanderia, evento), data operacional (`HotelApi.currentBusinessDate()`), usuário, instante (`Clock`). Tabela só com `SELECT, INSERT` para o papel da aplicação e declarada num `AppendOnlyTables` do `billing`.
- Estorno (lançamento inverso vinculado; índice único parcial impede o segundo estorno do mesmo lançamento) e ajuste com motivo: ações sensíveis por `authorization.requireOrApproval`, com uma `SensitiveAction` de ação `billing.posting.reverse` ou `billing.posting.adjust`, a permissão sensível correspondente, o recurso `billing.posting:` seguido do id e `LimitAmount("maxAmount", valor)` (RF-ACS-06), com o cabeçalho `X-Approval-Token`; auditoria com solicitante e aprovador (a porta já liga o `approval_id`).
- Endpoints em `/api/v1/folios` e `/api/v1/transaction-codes` (`@Tag("billing")`); `@Idempotent` nos POSTs de lançamento, estorno e ajuste (a repetição com aprovação usa outra `Idempotency-Key`).
- API pública `BillingApi` para módulos acima abrirem contas e lançarem (`openFolio`, `post`), já pensando em M4, sem checar permissão (quem chama se autorizou), auditada.
- `CLAUDE.md` (seção do `billing`: como abrir conta e lançar) e `README.md`.

**Fora de escopo:** roteamento e transferência (E05); taxa de serviço (E06); pagamentos (E08); encerramento (E10).

**Passos de implementação:**

1. Testes de domínio de `Folio` (saldo, estorno vinculado, ajuste) e de integração (imutabilidade no banco, data operacional ≠ data do relógio com o `MutableClock`, idempotência, step-up com `IamTestSupport.setPin` e sessão fixa, auditoria).
2. Implementar migração com privilégios, domínio, casos de uso, controllers e permissões no catálogo.
3. Regenerar o contrato e documentar.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "o que SPEC §5.10 (RF-CTA-01 a 03, 11), §8.2.7, RN-01, RN-02, RN-03 e ARCHITECTURE §6.3, §8.2 e §11 exigem de contas, lançamentos, estorno e aprovação?"; "como o `rooms` implementa o OOO sensível (`OutOfOrderOperations`, `requireOrApproval`, `X-Approval-Token`) e como o `ApprovalTest`/`OutOfOrderApiTest` testam a aprovação?"; "como `rooms` e `iam` declaram as tabelas *append-only* (`AppendOnlyTables`) e revogam `UPDATE, DELETE, TRUNCATE` na migração?".
- Partes paralelas: nenhuma.
- Foco do verificador: `UPDATE`/`DELETE` possível em lançamentos com o papel da aplicação; estorno de estorno ou segundo estorno sem regra; ajuste acima do limite sem aprovação; lançamento com data de calendário em vez da data operacional; POST repetido gerando dois lançamentos; `billing` conhecendo reservas ou importando `iam`.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E04 — "Códigos de lançamento, contas e lançamentos imutáveis" do roadmap docs/roadmaps/roadmap-m3-hospedes-contas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo restrições, as hipóteses H2, H4, H5, H10 e H16 e o "Protocolo de execução" —, a seção E04 inteira e o "Evidências e handoff" de E01. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 está #concluida e que o código bate com o handoff dela, e que a H10 já foi decidida (se a tabela ainda disser "a decidir na sincronização", pare e peça a decisão); se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar o módulo billing com TransactionCode (códigos padrão pré-carregados, taxa de serviço própria anulável), Folio para qualquer FolioOwner(type, id) com pagador empresa opcional, Posting imutável na data operacional do HotelApi (tabela append-only declarada em AppendOnlyTables), estorno e ajuste vinculados com motivo por requireOrApproval com limite maxAmount, @Idempotent nos POSTs e a API pública BillingApi (openFolio, post). billing não conhece reservas, não chama fiscal e não importa iam. Não implemente roteamento, taxa de serviço, pagamentos nem encerramento.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E04 para #em-andamento no índice e no título;
- investigação: um subagente Explore responde às perguntas da seção E04 (SPEC e ARCHITECTURE, OOO sensível do rooms como modelo de requireOrApproval, tabelas append-only);
- leve ao usuário antes de decidir sozinho (numa sessão manual, por entrevista; como subagente, no relatório ao orquestrador) as decisões abertas da E04 listadas no cabeçalho (códigos padrão e grupos de receita, tipos de FolioOwner, semântica do ajuste e chaves de limite);
- escreva os testes de domínio e de integração primeiro, implemente, atualize CLAUDE.md e README.md, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em UPDATE/DELETE possível no banco, ajuste acima do limite sem aprovação, data de calendário no lugar da data operacional e POST repetido gerando dois lançamentos.

Critérios de aceite (detalhes na seção E04):
- UPDATE e DELETE em lançamentos com o papel da aplicação falham (teste de integração) e a tabela está num AppendOnlyTables;
- estorno cria lançamento inverso vinculado; estorno ou ajuste sem permissão ou acima do limite → 403 APPROVAL_REQUIRED, e com X-Approval-Token válido → 201 auditado com solicitante e aprovador;
- lançamento grava a data operacional do hotel; repetição com a mesma Idempotency-Key não duplica;
- BillingApi openFolio/post documentada no CLAUDE.md; build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E04 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] `UPDATE` e `DELETE` na tabela de lançamentos com o papel da aplicação falham no banco (teste de integração); a tabela está num bean `AppendOnlyTables` e o `DatabaseRolesTest` passa.
- [ ] Lançamento grava a data operacional corrente do `hotel`, não a data do relógio (teste com o `MutableClock` numa data diferente).
- [ ] Estorno cria lançamento inverso vinculado ao original; estornar duas vezes o mesmo lançamento → `409` com `code` estável, inclusive em duas requisições simultâneas.
- [ ] Estorno ou ajuste sem permissão ou acima do limite → `403 APPROVAL_REQUIRED` (com `action`, `permission`, `resourceRef` e `limit`); com `X-Approval-Token` válido → `201` e auditoria com solicitante e aprovador.
- [ ] POST de lançamento sem `Idempotency-Key` → `400 IDEMPOTENCY_KEY_REQUIRED`; repetição com a mesma chave não duplica.
- [ ] Saldo da conta calculado corretamente em cenário com lançamentos, estornos e ajustes (teste de domínio).
- [ ] Códigos padrão presentes após as migrações.
- [ ] `ModularityTest` com o teste do `billing` (dependências exatas, sem `iam`); contrato regenerado; `./gradlew build` e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- A imutabilidade depende dos dois papéis do banco, que existem desde o M1 E04 (Flyway com o dono; aplicação com papel restrito; `DatabaseRoleGuard` na partida): reutilizar `AppendOnlyTables`, nunca um trigger no lugar do privilégio.
- Estado da conta (status, versão) muda, o lançamento não: manter o que muda na tabela da conta e o que é fato em tabelas *append-only*.

**Rollback / migração:** tabelas novas; reverter em desenvolvimento é recriar o schema `billing`. Depois de existir dado real, nenhuma migração pode alterar lançamentos.

**Evidências e handoff:**

- Snapshot inicial: `1364e18c733efc960a03c07bf92306cd8a356253` (tirado pelo orquestrador; a árvore já tinha as mudanças não commitadas da E02 e da E03, preservadas). Snapshot ao fim da implementação: `5817a3d6137eb9c6271d25b8183839e466141601` (antes deste handoff).
- Arquivos alterados:
  - Novos, produção: `src/main/kotlin/com/zeny/hotel/billing/` — raiz `BillingModule.kt`, `BillingApi.kt` (API pública, ids, `TransactionCodeKey`, `TransactionCodeKind`, `RevenueGroup`, `FolioOwner`/`FolioOwnerType`, `FolioStatus`, `PostingSource`, `PostingType`, resumos e `NewFolio`/`NewPosting`), `BillingErrorCodes.kt`, `BillingPermissions.kt`; `domain/` (`BillingRules.kt`, `TransactionCode.kt`, `Folio.kt`, `Posting.kt` com `PostingCorrections` e `FolioBalance`); `application/` (`BillingPorts.kt`, `BillingSupport.kt` com as visões, a trilha e o bean das permissões, `BillingOperations.kt`, `TransactionCodeService.kt`, `FolioService.kt` com o `PostingService`, `BillingApiService.kt`); `infrastructure/` (`BillingPersistence.kt`: JPA de códigos e contas e as listagens por SQL; `JdbcPostingLedger.kt`: lançamentos só por `INSERT`/`SELECT` e o bean `AppendOnlyTables`); `web/` (`BillingDtos.kt`, `TransactionCodesController.kt`, `FoliosController.kt`); migrações `src/main/resources/db/migration/billing/V2026_10_17_0545__create_billing.sql` e `V2026_10_17_0546__grant_billing_to_app_role.sql`.
  - Novos, testes: `src/test/kotlin/com/zeny/hotel/billing/` (`BillingDomainTest.kt`, `BillingTestSupport.kt`, `TransactionCodesApiTest.kt`, `FoliosApiTest.kt`, `PostingsApiTest.kt`).
  - Alterados: `src/test/kotlin/com/zeny/hotel/ModularityTest.kt` (teste do `billing`: dependências exatas `shared`, `hotel`, `guests`, só pelas APIs públicas), `src/test/kotlin/com/zeny/hotel/iam/AuthorizationTest.kt` (modelo Gerente com as sete permissões do `billing`), `api/openapi.yaml` (regenerado: 10 caminhos novos e a tag `billing`), `CLAUDE.md` ("Estado atual", seção "Contas e lançamentos", nota das tabelas *append-only*), `README.md` (seção "API" e a árvore de módulos) e este roadmap (tag, H2, H5, H16, decisões abertas e este handoff). A ARCHITECTURE não mudou: a aresta `billing` → `hotel`, `guests` já está na §3.1.
- Decisões:
  - Do usuário (entrevista do orquestrador em 2026-10-17, todas pela opção recomendada; registradas também em H5, H16, nas decisões abertas e no `CLAUDE.md`):
    1. Grupos de receita num enum fixo (`ROOM`, `FOOD_BEVERAGE`, `SERVICE_CHARGE`, `FEES`, `TAXES`, `LAUNDRY`, `OTHER`; um grupo novo exige migração) e oito códigos pré-carregados, editáveis e nunca apagados: `DIARIA` (`CHARGE`/`ROOM`, sem taxa de serviço), `MULTA-CANC` e `MULTA-NOSHOW` (`PENALTY`/`FEES`), `TX-SERVICO` (`SERVICE_CHARGE`/`SERVICE_CHARGE`), `AB` (`CHARGE`/`FOOD_BEVERAGE`, `serviceChargeApplies`), `LAVANDERIA` (`CHARGE`/`LAUNDRY`), `TX-TURISMO` (`TAX`/`TAXES`) e `AVULSO` (`CHARGE`/`OTHER`), com a chave estável `TransactionCodeKey` (`ROOM_CHARGE`, `CANCELLATION_FEE`, `NO_SHOW_FEE`, `SERVICE_CHARGE`, `FOOD_BEVERAGE`, `LAUNDRY`, `TOURISM_FEE`, `MISCELLANEOUS`) para os outros módulos acharem o código (como a `RoomFeatureKey`). Tipos (`TransactionCodeKind`): `CHARGE` (consumo), `PENALTY` (multa), `SERVICE_CHARGE`, `TAX` e `CREDIT` (lança valor negativo).
    2. `FolioOwnerType` fixo com `RESERVATION`, `GROUP`, `EVENT`, `COMPANY` e `WALK_IN`, id opaco; só `COMPANY` conferido pela `GuestsApi`.
    3. Ajuste: lançamento novo vinculado, no mesmo código, sinal oposto ao do original, motivo obrigatório, parcial (até o que resta). Estorno: o inverso de tudo o que ainda resta do original (depois de ajustes, inverte o resto; com os ajustes já zerando, `422 POSTING_NOTHING_TO_REVERSE`), um só por original (índice único parcial `posting_reversal_uk`); depois do estorno, nenhum ajuste; estorno ou ajuste de um estorno ou de um ajuste é recusado (`422 POSTING_NOT_CORRECTABLE`, a opção conservadora).
    4. `billing.postings.reverse` e `billing.postings.adjust` sensíveis, limite `maxAmount` (`MONEY`) sobre o valor absoluto; Gerente e Financeiro sem teto; Recepcionista e Caixa A&B sem elas (sempre pedem aprovação).
  - Do implementador, **confirmadas na revisão humana** (2026-10-17, opção recomendada, abaixo):
    1. **Tipo e grupo de receita fixos** depois da criação do código (mudar reclassificaria lançamentos antigos; para outra natureza, cria-se outro código). Os pré-carregados também.
    2. **Permissão nova `billing.codes.read`** (fora da H16) para consultar códigos (Gerente, Financeiro, Recepcionista, Caixa A&B); `billing.codes.manage` sugerida a Gerente e Financeiro; `billing.folios.manage` e `billing.postings.create` a Gerente, Financeiro e Recepcionista; Caixa A&B só lê códigos e contas (o PDV lança pela `BillingApi` no M8).
    3. **Conta avulsa sem id**: o dono passa a ser a própria conta (`FolioOwner(WALK_IN, id da conta)`); os outros tipos exigem o id.
    4. **Número da conta** com o prefixo `C` (`C-2026-000123`).
    5. **Limite de crédito não bloqueia** lançamento no M3 (a resposta traz `creditLimitExceeded`); o pagador e o limite mudam por `PUT` com `If-Match`; o dono nunca muda.
    6. **`@Idempotent` também na abertura de conta** (`POST /folios`), além dos POSTs de lançamento, estorno e ajuste.
    7. **Regras da correção antes da autorização**: com a conta travada, o caso de uso confere que o lançamento é corrigível e calcula o valor (que o limite precisa) antes do `requireOrApproval`; por isso `409`/`422`/`404` saem antes de `403 APPROVAL_REQUIRED`.
    8. **Forma das correções**: quantidade 1, valor unitário = valor, a descrição e o código do original, origem `MANUAL`; a aprovação usada fica no próprio lançamento (`approval_id`, `approved_by`) além da trilha.
    9. **Trigger `billing.posting_immutable`** contra `UPDATE`/`DELETE` também para o dono, **além** do privilégio (não no lugar dele), e a FK `(corrects_id, folio_id)` que prende a correção à conta do original.
    10. **Versão da conta avança a cada lançamento** (`PESSIMISTIC_FORCE_INCREMENT` na conta já travada), para o `ETag` mudar com o saldo; o `updated_at` da conta só muda com o pagador e o limite.
    11. **Retratos da trilha sem texto livre** (H10): o do lançamento não leva a descrição nem o motivo (ficam no próprio lançamento).
    12. A `BillingApi` não tem estorno nem ajuste (ações sensíveis de quem opera); `foliosOf` lê até 200 contas por dono.
- Validações executadas:
  - `./gradlew test --tests 'com.zeny.hotel.billing.*' …` (com `ModularityTest`, `architecture.*`, `persistence.*`, `AuthorizationTest`) → 112 testes, 1 falha na primeira rodada (o `ETag` da conta não mudava com um lançamento quando o relógio de teste não andava; corrigido forçando o incremento da versão); depois, todos verdes.
  - `./gradlew lint` → `BUILD SUCCESSFUL` (depois de ajustar formatação e regras do detekt).
  - `./gradlew updateOpenApi` → `BUILD SUCCESSFUL`; contrato com 136 caminhos (eram 126) e a tag `billing`.
  - `./gradlew build` → `BUILD SUCCESSFUL in 3m 26s`; 729 testes em 95 classes, 0 falhas. Do `billing`: `BillingDomainTest` 7/7, `TransactionCodesApiTest` 5/5, `FoliosApiTest` 6/6, `PostingsApiTest` 11/11; `ModularityTest` 13/13, `LayerArchitectureTest` 7/7, `DatabaseRolesTest` 7/7 (inclui `billing.posting` só com `SELECT, INSERT`), `AuthorizationTest` 15/15. Nenhum aviso novo nos logs dos testes do `billing`.
  - `.github/scripts/check-api-breaking.sh` → "No breaking changes to report… Nenhuma mudança incompatível (nível ERR) em /api/v1".
  - Critérios de aceite, com a evidência: imutabilidade — `PostingsApiTest` "postings cannot be updated nor deleted…" (`UPDATE`, `DELETE` e `TRUNCATE` recusados ao papel da aplicação com `permission denied`, e `UPDATE`/`DELETE` ao dono pelo trigger) e `DatabaseRolesTest`; data operacional — "a posting is recorded on the hotel business date…" (relógio movido para outra data no fuso do hotel; `businessDate` e a coluna = `HotelApi.currentBusinessDate()`); estorno vinculado e `409` inclusive simultâneo — "a reversal creates the inverse posting…" e "two simultaneous reversals…"; step-up e limite — "a reversal without the permission asks for approval…" (problema com `action`, `permission`, `resourceRef` e `limit`; `201` com o token; trilha com `approval_id`, solicitante e sessão; entrada `iam.approval` com o aprovador) e "an adjustment within the limit needs no approval…" (dentro do teto sem aprovação, acima `APPROVAL_REQUIRED`, aprovador com limite insuficiente `APPROVER_NOT_ALLOWED`, token preso ao valor `APPROVAL_TOKEN_MISMATCH`); idempotência — "a posting needs an Idempotency-Key…" (`400 IDEMPOTENCY_KEY_REQUIRED`, repetição com `Idempotent-Replayed: true` e uma linha só, `422 IDEMPOTENCY_KEY_REUSED`) e a abertura de conta em `FoliosApiTest`; saldo — `BillingDomainTest` "the balance sums postings, reversals and adjustments" (570,00 com lançamentos, crédito, ajuste e estornos); códigos padrão — `TransactionCodesApiTest` "the eight default codes exist…"; `ModularityTest` "billing depends only on shared, hotel and guests…".
- Parecer do verificador (orquestrador, 2026-10-17): 1ª verificação independente (snapshot `8651e0b1661f7003fdee67f3c0c9fc6f49a6aba9`) **APROVADO**: os 8 critérios aprovados com evidência, `./gradlew build` verde com 729 testes, oasdiff sem `ERR` e as quatro decisões do usuário conferidas. Ficaram dois pontos médios para a revisão humana: M1, regras conferidas antes da autorização, que vazavam a existência e o valor restante a quem não tinha permissão; e M2, limite contornável por fracionamento. Ficaram também os baixos B1 a B5. A revisão humana seguiu a opção recomendada pelo orquestrador, por delegação do usuário, ausente: `billing.folios.read` passou a ser exigida antes de ler a conta, o limite passou a ser cumulativo por lançamento, os testes B1 e B2 foram acrescentados e as 12 decisões do implementador foram aceitas (ver "Revisão humana"). 2ª verificação independente, com verificador novo (snapshot `c3112b24daf861c0758f92bf3e14b2a19c260af4`): **APROVADO**. Os 8 critérios e os ajustes M1, M2, B1 e B2 foram conferidos: a nova ordem não abre vazamento, os sinais e o estorno depois de ajustes estão certos, a aprovação fica vinculada ao total e a corrida fica sob a trava da conta. `./gradlew build` passou com 732 testes e oasdiff sem `ERR`. As ressalvas menores foram ajustadas depois pelo implementador: os textos do limite (KDoc e a descrição do `maxAmount` em `GET /api/v1/permissions`), um teste de dois ajustes simultâneos (um 201 e um 422 ou 403, com o total nunca acima do original) e o registro de que o total conta as correções de qualquer usuário. Depois disso, `./gradlew build` passou com 733 testes e 0 falhas, e o oasdiff não acusou `ERR`.
- Notas para a próxima etapa:
  - Revisão humana: feita (seção abaixo); as 12 decisões do implementador ficaram aceitas.
  - E10: `PUT /api/v1/folios/{id}` (pagador e limite) hoje aceita uma conta `CLOSED` (B4 do verificador); decidir no encerramento se a conta encerrada recusa a edição.
  - E05 (roteamento e transferência): o lançamento passa por `BillingOperations.post` (e `record`), que trava a conta de destino; para duas contas, trave em ordem de id (`BillingStore.folioForUpdate`). A transferência não pode usar `corrects_id` (a FK o prende à mesma conta): use uma coluna de vínculo própria e um `PostingType` novo (o `CHECK` `posting_type_ck` e o `posting_correction_ck` precisam de migração nova; nunca altere a de E04). O `CHECK` do `REGULAR` exige `quantity > 0` e `unit_price > 0`.
  - E06 (taxa de serviço): o código de taxa é `BillingApi.transactionCode(TransactionCodeKey.SERVICE_CHARGE)`; o percentual vem do informado na `BillingApi.post` (acrescente o parâmetro em `NewPosting`), senão `TransactionCode.serviceChargeRate`, senão `HotelApi.taxSettings().serviceChargeRate`. O estorno do item hoje inverte só o item: estornar a taxa vinculada é da E06. A "remoção a pedido" é um estorno da taxa com a permissão própria (não sensível): use `PostingCorrections.reverse` sem o `requireOrApproval`.
  - E07/E08: novas tabelas *append-only* do `billing` (movimentos de caixa, pagamentos) precisam do `REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER` na própria migração (o `ALTER DEFAULT PRIVILEGES` da pasta dá DML) e de entrar no bean `BillingTablesConfiguration.billingAppendOnlyTables`. O saldo hoje é só a soma dos lançamentos (`PostingLedger.balances`): os pagamentos entram nele na E08.
  - E10 (encerramento): `Folio.requireOpen` já recusa lançamento e correção em conta `CLOSED`; o encerramento precisa da conta travada (`folioForUpdate`) e do saldo zero.


**Revisão humana (2026-10-17, opção recomendada pelo orquestrador por delegação do usuário):** a verificação independente deu APROVADO, sem problema grave; o usuário, ausente, pediu a opção recomendada em cada ponto. Tag inalterada (`#em-andamento`).

- Decisões: as 12 decisões do implementador ficam **aceitas**; B3 e B5 do verificador ficam aceitos como estão; B4 (`PUT` numa conta `CLOSED`) vira nota para a E10.
- Ajustes aplicados:
  1. **(M1) Autorização antes de ler a conta:** `PostingService.reverse` e `adjust` exigem `billing.folios.read` (`authorization.require`) antes de validar o motivo, travar ou ler a conta; sem ela, `403 PERMISSION_DENIED`, nunca 404/409/422 nem `APPROVAL_REQUIRED` (e nenhum `FOR UPDATE`). Teste: `PostingsApiTest` "without billing folios read a correction is refused with 403 before the folio is read" (lançamento já estornado, ajuste acima do que resta e conta/lançamento inexistentes: todos 403 `PERMISSION_DENIED`).
  2. **(M2) Limite cumulativo por lançamento:** o valor conferido contra `maxAmount`, e o `LimitAmount` que a aprovação vincula, é o total das correções do lançamento original (valor absoluto das já feitas mais o desta; `PostingCorrections.cumulativeWith`). O estorno do resto confere, portanto, o valor do original inteiro. Testes: `PostingsApiTest` "an adjustment within the limit…" (teto 50: ajuste de 30 passa; o segundo de 30 pede aprovação com `limit.amount` 60; aprovador com 59,99 recusado, com 60 aceito; o token não serve a um ajuste de 31, total 61; quem não tem a permissão vê o total 61; o estorno do resto pede com 500) e `BillingDomainTest` (`cumulativeWith`).
  3. **(B1)** `PostingsApiTest` "the database refuses a second reversal of the same posting, even inserted directly": um `INSERT` de um segundo `REVERSAL` pelo dono (`flyway.configuration.dataSource`) falha em `posting_reversal_uk`.
  4. **(B2)** `PostingsApiTest` "a reversal above the reverse limit of the user asks for approval, within it does not": com `reverse` e teto 100, estornar 150 pede aprovação (`limit.amount` 150) e estornar 100 não.
- Consequência aceita do limite cumulativo (registrada a pedido do orquestrador depois da 2ª verificação, que deu APROVADO): o total conta as correções de **qualquer usuário**, então um operador com teto pode precisar de aprovação para estornar o resto de um lançamento que o Gerente já ajustou (o estorno confere o valor do original inteiro).
- Ressalvas menores da 2ª verificação, corrigidas: os textos que ainda falavam do "valor absoluto da correção" (KDoc de `BillingPermissions` e `PostingService` e a descrição do limite `maxAmount` no catálogo, agora "Valor máximo do total das correções de um lançamento (estornos e ajustes), em reais", visível em `GET /api/v1/permissions`); e um teste de dois ajustes simultâneos no mesmo lançamento (`PostingsApiTest` "two simultaneous adjustments never go beyond what remains nor beyond the ceiling of the user": 60 + 60 num lançamento de 100 → um 201 e um `422 ADJUSTMENT_EXCEEDS_REMAINING`, total gravado 60; 30 + 30 com teto 50 → um 201 e um `403 APPROVAL_REQUIRED`, total gravado 30).
  Validações destas correções: `./gradlew build` → `BUILD SUCCESSFUL in 4m 49s`, 733 testes, 0 falhas (`PostingsApiTest` 15/15; contrato sem mudança, `checkOpenApi` verde); `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.
- Arquivos: `billing/domain/Posting.kt` (`cumulativeWith`), `billing/application/FolioService.kt` (`PostingService`), `billing/web/FoliosController.kt` (descrição), `api/openapi.yaml` (só as descrições do estorno e do ajuste), testes `PostingsApiTest.kt` e `BillingDomainTest.kt`, `CLAUDE.md` (seção "Contas e lançamentos") e este roadmap.
- Validações: `./gradlew lint` → sucesso; `./gradlew updateOpenApi` → contrato regenerado (só as descrições de `reversePosting` e `adjustPosting` mudaram); `./gradlew build` → `BUILD SUCCESSFUL in 3m 29s`, 732 testes em 95 classes, 0 falhas (`PostingsApiTest` 14/14, `BillingDomainTest` 7/7, `FoliosApiTest` 6/6, `TransactionCodesApiTest` 5/5); `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

---

## E05 — Roteamento, transferência e divisão — #concluida

**Objetivo:** aplicar regras de roteamento que enviam lançamentos, por código ou grupo de códigos e período, para outra conta no momento do lançamento, e permitir transferência manual e divisão de lançamentos entre contas por valor ou percentual (RF-CTA-04, RF-CTA-05).

**Por que agora:** reservas com conta da empresa e contas master de grupo (M4) dependem do roteamento; precisa estar pronto antes de as diárias serem lançadas.

**Dependências e paralelismo:** E04. Pode correr em paralelo com E06 e E07 (em worktrees separados); conflito de edição no agregado `Folio` e no serviço de lançamento com E06 — preferir executar em sequência.

**Escopo:**

- `RoutingRule` com conta de origem, conta de destino, conjunto de códigos ou grupo de receita e período em datas de negócio; destino pode ser qualquer conta aberta.
- No lançamento (inclusive pela `BillingApi.post`), a regra vigente na data operacional redireciona o lançamento para a conta de destino, registrando a origem.
- Transferência manual de lançamentos entre contas preservando a imutabilidade (lançamentos de saída e entrada vinculados, *append-only*) e divisão por valor ou percentual com `Money.allocate` (sem perder centavos).
- Permissões (H16) e auditoria; `@Idempotent` nos POSTs; as duas contas travadas em ordem de id.

**Fora de escopo:** transferência a contas a receber (M10).

**Passos de implementação:**

1. Testes de domínio (seleção da regra vigente, divisão sem perda de centavos) e de integração (lançamento roteado, transferência, conta de destino fechada, transferência concorrente sem deadlock).
2. Implementar regras, aplicação no fluxo de lançamento, transferência e divisão.
3. Regenerar o contrato e documentar.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em RF-CTA-04 e RF-CTA-05 e no handoff de E04.
- Partes paralelas: nenhuma.
- Foco do verificador: lançamento original alterado na transferência; centavos perdidos na divisão; roteamento em cadeia ou em ciclo (A → B → A); transferência para conta fechada; deadlock entre duas transferências opostas.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E05 — "Roteamento, transferência e divisão" do roadmap docs/roadmaps/roadmap-m3-hospedes-contas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E05 inteira, o "Evidências e handoff" de E04 e RF-CTA-04 e RF-CTA-05 em docs/SPEC.md §5.10. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E04 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: implementar em billing as regras de roteamento (origem, destino, códigos ou grupo, período) aplicadas no momento do lançamento, inclusive pela BillingApi, a transferência manual de lançamentos entre contas e a divisão por valor ou percentual com Money.allocate, sempre por lançamentos novos vinculados, sem alterar lançamentos existentes. Não implemente transferência para contas a receber (marco M10).

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E05 para #em-andamento no índice e no título;
- sem investigação delegada; leve ao usuário antes de decidir sozinho (numa sessão manual, por entrevista; como subagente, no relatório ao orquestrador) a precedência entre regras sobrepostas (decisão aberta da E05 no cabeçalho);
- escreva os testes primeiro, implemente, atualize CLAUDE.md e README.md, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em lançamento original alterado, centavos perdidos na divisão, roteamento em ciclo, destino fechado e deadlock.

Critérios de aceite (detalhes na seção E05):
- lançamento com código coberto por regra vigente vai para a conta de destino, com origem registrada;
- transferência e divisão criam lançamentos vinculados e preservam a soma exata;
- regra que formaria ciclo → 422 e destino fechado → 409, com code estável;
- build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E05 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Lançamento cujo código está numa regra vigente é gravado na conta de destino com referência à conta de origem; fora do período da regra, fica na origem (teste de integração).
- [ ] Transferência cria lançamentos de saída e entrada vinculados; o lançamento original continua intacto.
- [ ] Divisão por percentual e por valor soma exatamente o original em casos com dízima (teste de domínio).
- [ ] Regra que criaria ciclo de roteamento → `422`; transferência ou roteamento para conta fechada → `409`, ambos com `code` estável.
- [ ] Duas transferências opostas simultâneas entre as mesmas contas terminam sem deadlock (teste).
- [ ] Escritas auditadas; `Idempotency-Key` exigido; contrato regenerado; `./gradlew build` e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Lock entre duas contas na transferência: travar sempre em ordem de id para evitar deadlock; teste concorrente simples.

**Evidências e handoff:**

- Snapshot inicial: `897ef7219506ed8c897711ca307f35f05f8594c5` (tirado pelo orquestrador; árvore limpa sobre o commit `7aa5259`). Snapshot ao fim da implementação: `a0583c0314d80ea659510dc3059f9f959dc05059` (antes deste handoff).
- Arquivos alterados:
  - Novos, produção: `src/main/kotlin/com/zeny/hotel/billing/domain/Routing.kt` (`BusinessPeriod`, `RoutingCriteria`, a entidade `RoutingRule`, `TransactionCodeIdConverter` e `RoutingNetwork` com precedência, cadeia, sobreposição e ciclo), `application/RoutingRuleService.kt` (cadastro de regras, `RoutingRuleView`, retrato da trilha), `application/PostingTransferService.kt` (transferência, divisão, `SplitBy`, `SplitPartCommand`, `TransferView`), `web/RoutingRulesController.kt` e a migração `src/main/resources/db/migration/billing/V2026_10_19_2045__add_routing_and_transfers.sql` (tabelas `routing_rule` e `routing_rule_code`, colunas `routed_from_folio_id`, `routing_rule_id` e `transferred_from_id` em `billing.posting`, os `CHECK` recriados, `posting_transfer_in_uk`, o trigger `posting_transfer_in_matches_out` e o *constraint trigger* adiado `posting_transfer_out_has_in`).
  - Alterados, produção: `billing/BillingApi.kt` (`RoutingRuleId`, `PostingType.TRANSFER_OUT`/`TRANSFER_IN`, `PostingSummary` com `routedFromFolioId`, `routingRuleId` e `transferredFromId`, KDoc do roteamento em `post`), `BillingErrorCodes.kt` (11 códigos novos), `BillingPermissions.kt` (`billing.routing.manage`, `billing.postings.transfer`), `domain/Posting.kt` (`PostingRouting`, `transferredFromId`, `CORRECTABLE`, `PostingCorrections.transferable`/`transfer`, `cumulativeWith` só com estornos e ajustes, `SplitShares`, `PostingSplit`), `domain/Folio.kt` (`requireOpenAsRoutingTarget`), `application/BillingOperations.kt` (roteamento no `post`, as duas contas travadas juntas, `record` com extra na trilha), `BillingPorts.kt` (`foliosForUpdate`, as portas das regras, `PostingRow` e `RoutingRuleFilter`), `BillingSupport.kt` (ações e entidade da trilha, `PostingView` e retrato do lançamento com os vínculos), `infrastructure/BillingPersistence.kt` (`RoutingRuleJpaRepository`, `foliosForUpdate` em ordem de id, `folioForUpdate` por `find` + `refresh(PESSIMISTIC_WRITE)`, trava consultiva, listagem das regras), `JdbcPostingLedger.kt` (colunas novas e os vínculos de transferência na leitura), `web/BillingDtos.kt` (DTOs de regra, transferência e divisão; `PostingResponse` com os vínculos e o `type` como `x-extensible-enum`), `web/FoliosController.kt` (transferir e dividir), `web/TransactionCodesController.kt` (descrição da tag).
  - Testes: novos `src/test/kotlin/com/zeny/hotel/billing/RoutingDomainTest.kt`, `RoutingApiTest.kt`, `TransfersApiTest.kt`; alterados `BillingTestSupport.kt` (regra, transferência, divisão, saldo) e `iam/AuthorizationTest.kt` (o Gerente com as duas permissões novas).
  - Documentação e contrato: `api/openapi.yaml` (regenerado: 142 caminhos, eram 136; o `type` do lançamento passou a `x-extensible-enum`), `CLAUDE.md` ("Estado atual", a seção nova "Roteamento, transferência e divisão" e o estorno da entrada de transferência em "Contas e lançamentos"), `README.md` (seção "API" e a árvore) e este roadmap (tag, H16, decisão aberta da E05 e este handoff). A ARCHITECTURE não mudou (nenhuma aresta nova entre módulos).
- Decisões:
  - Do usuário (entrevista do orquestrador em 2026-10-19, todas pela opção recomendada; registradas também nas decisões abertas do cabeçalho, na H16 e no `CLAUDE.md`):
    1. **Precedência:** a regra que cita o código prevalece sobre a que cobre o grupo de receita; duas regras do mesmo nível (as duas por código ou as duas por grupo) da mesma conta de origem que cobrem o mesmo código ou grupo em períodos que se cruzam são recusadas na criação, na edição e na reativação com `409 ROUTING_RULE_OVERLAP`.
    2. **Cadeia e ciclo:** com A → B e B → C vigentes, o lançamento pedido em A segue até a conta sem regra aplicável (C), com a origem registrada (a conta pedida); regra que formaria ciclo `422 ROUTING_RULE_CYCLE`; destino encerrado `409 ROUTING_TARGET_CLOSED`.
    3. **Permissões:** `billing.routing.manage` (regras) e `billing.postings.transfer` (transferência e divisão), não sensíveis, só `ALL`, sugeridas a Gerente, Financeiro e Recepcionista; tudo auditado.
  - Do implementador (a opção mais conservadora e coerente com o código), **todas as 14 aceitas pelo usuário** na entrevista do orquestrador em 2026-10-19 (opção recomendada); a 1 foi ampliada para convenção do projeto (abaixo, "Ajustes depois da verificação"):
    1. **`type` do lançamento como `x-extensible-enum` no contrato:** acrescentar `TRANSFER_OUT`/`TRANSFER_IN` ao `enum` de resposta do `type` era `ERR` no oasdiff (`response-property-enum-value-added`: um cliente gerado do contrato anterior falharia no valor novo). O `PostingResponse.type` passou a `string` com `x-extensible-enum` (os cinco valores) e a descrição pede que o cliente trate valor desconhecido; o oasdiff não acusa nada (nem `WARN`). Ainda não há SDK publicado. Alternativas: `/api/v2` para os lançamentos, ou representar as transferências sem tipo novo (com um campo à parte), o que deixaria `REGULAR`/`ADJUSTMENT` com sentido duplo. A E06 (taxa de serviço) e as seguintes que precisarem de um tipo novo já não quebram o contrato.
    2. **Transferência por pares novos de tipos próprios:** a saída `TRANSFER_OUT` (na origem, `corrects_id` = o lançamento, o que a FK `(corrects_id, folio_id)` já permite) e a entrada `TRANSFER_IN` (no destino, `transferred_from_id` = a saída); a entrada vale como lançamento normal no destino (estornada, ajustada e transferida de novo), a saída não é corrigida nem transferida; desfazer = transferir de volta. A saída não conta no limite `maxAmount` (não é correção).
    3. **Transferência e divisão movem o que ainda resta** do lançamento (depois de ajustes e outras saídas); lançamento estornado `409 POSTING_ALREADY_REVERSED`; nada restando `422 POSTING_NOTHING_TO_TRANSFER`; a transferência de vários lançamentos é tudo ou nada.
    4. **Divisão cobrindo exatamente o que resta:** por percentual, os percentuais somam 100 (`Money.allocate`, maior resto); por valor, as partes somam exatamente o que resta (`422 SPLIT_TOTAL_MISMATCH`); a parte que fica é a própria conta de origem citada como parte; de 2 a 10 partes, sem conta repetida; parte que arredonda a zero não move nada.
    5. **Transferência manual vai exatamente para o destino escolhido** (as regras de roteamento do destino não se aplicam); só o lançamento novo pedido numa conta é roteado.
    6. **Motivo opcional** na transferência e na divisão (até 500), guardado nos dois lançamentos do par; fora da trilha (texto livre, H10).
    7. **Período da regra com fim opcional** (nulo = sem fim) e início livre (pode ser no passado: a regra só decide lançamentos novos, na data operacional corrente).
    8. **Conferência de ciclo conservadora:** a busca segue as regras ativas cujo critério e período se cruzam com o da regra nova ao longo do caminho, mas ignora a precedência no meio dele (uma regra por grupo encoberta por uma por código ainda conta): pode recusar uma regra que nunca formaria ciclo de fato, nunca aceita uma que forme.
    9. **Serialização das escritas de regras** por uma trava consultiva única (`pg_advisory_xact_lock(hashtext('billing.routing-rules'))`), antes das travas das contas, em vez de uma restrição de banco: as conferências de sobreposição e de ciclo cruzam várias contas, e duas regras criadas ao mesmo tempo em contas diferentes poderiam fechar um ciclo sem se verem.
    10. **Código desativado aceito numa regra** (só a existência é conferida): o lançamento nele já é recusado.
    11. **Conta pedida encerrada** recusa o lançamento (`409 FOLIO_CLOSED`) mesmo que uma regra o desviasse; só a conta final é conferida como destino (`409 ROUTING_TARGET_CLOSED`), não as intermediárias da cadeia.
    12. **Editar uma regra desativada** é aceito (as conferências de origem, destino, sobreposição e ciclo valem só para a regra ativa e são refeitas na reativação); a desativação nunca é recusada.
    13. **`folioForUpdate` passou a `find` + `refresh(PESSIMISTIC_WRITE)`** em vez da consulta `findForUpdate`: com a conta já carregada na transação (o lançamento roteado a lê antes de travar), a consulta com trava que trazia outra versão era recusada pelo Hibernate 7 ("conflicting version of entity already held in persistence context") e virava `412`. Vale para todos os caminhos de escrita do `billing`.
    14. **Rotas no contrato:** a transferência em `POST /api/v1/folios/{id}/postings/transfer` e a divisão em `…/postings/{postingId}/split`, as duas com `201` e o corpo `{transfers[{originalId, outgoing, incoming}]}` (sem `Location`, porque criam vários lançamentos); as regras em `/api/v1/routing-rules` (recurso próprio, com a origem no corpo da criação).
- Validações executadas:
  - Testes escritos antes da implementação (domínio, roteamento e transferência); a primeira rodada dos testes do `billing` com `ModularityTest`, `architecture.*`, `persistence.*` e `AuthorizationTest` deu 141 testes e 1 falha (lançamentos roteados opostos simultâneos: `412` pelo conflito de versão do Hibernate 7 ao travar uma conta já carregada; corrigido pela decisão 13); depois, todos verdes.
  - `./gradlew lint` → `BUILD SUCCESSFUL` (depois do `ktlintFormat` e de supressões do detekt com motivo: `TooManyFunctions` no `BillingStore`/`JpaBillingStore`/`FoliosController`, `ThrowsCount` e `LongParameterList` pontuais).
  - `./gradlew updateOpenApi` → contrato regenerado (142 caminhos; a tag `billing` com a descrição nova; `PostingResponse.type` como `x-extensible-enum`).
  - `.github/scripts/check-api-breaking.sh` → primeira rodada com `ERR` (`response-property-enum-value-added` em `type`); depois do `x-extensible-enum`, "No breaking changes to report… Nenhuma mudança incompatível (nível ERR) em /api/v1" (e o `oasdiff changelog --level WARN` também sem nada).
  - `./gradlew build` (final, depois do último ajuste: conta repetida na divisão comparada só pelo id) → `BUILD SUCCESSFUL in 5m 55s`; 759 testes em 98 classes, 0 falhas (eram 733). Do `billing`: `RoutingDomainTest` 10/10, `RoutingApiTest` 10/10, `TransfersApiTest` 6/6, `BillingDomainTest` 7/7, `PostingsApiTest` 15/15, `FoliosApiTest` 6/6, `TransactionCodesApiTest` 5/5; `ModularityTest` 13/13, `LayerArchitectureTest` 7/7, `DatabaseRolesTest` 7/7, `MigrationConventionTest` 11/11, `AuthorizationTest` 15/15; `checkOpenApi` verde.
  - Critérios de aceite, com a evidência: lançamento roteado com a origem e fora do período na origem — `RoutingApiTest` "a posting whose code is in a rule in force goes to the target folio…" (pela API HTTP e pela `BillingApi`, `routedFromFolioId`, `routingRuleId`, coluna `routed_from_folio_id`, `Location` da conta final, trilha com `routingRuleIds`) e "outside the period of the rule the posting stays on the origin"; transferência e divisão vinculadas e soma exata — `TransfersApiTest` "a transfer creates linked outgoing and incoming postings and leaves the original intact" (linha do original idêntica no banco antes e depois) e "a split by percent with repeating decimals and by amount adds up to exactly the original" (33,3333/33,3333/33,3334 de 100,00 e de 10,00; por valor só com a soma exata), `RoutingDomainTest` "a split by percent always adds up to exactly what remains…" (sete totais, inclusive negativos e de um centavo) e "a split by amount must add up to exactly…"; ciclo `422` e destino encerrado `409` — `RoutingApiTest` "overlapping rules … 409, a rule forming a cycle with 422" (B → A, B → C → A e A → A) e "a closed target refuses a new rule and a routed posting with 409 ROUTING_TARGET_CLOSED", `TransfersApiTest` "transfers and splits are refused with stable codes…" (`409 FOLIO_CLOSED`); sem deadlock — `TransfersApiTest` "two opposite transfers between the same folios at the same time finish without deadlock" (4 + 4 transferências opostas simultâneas, todas `201`) e `RoutingApiTest` "routed postings in opposite directions at the same time finish without deadlock" (4 + 4 lançamentos roteados opostos, todos `201`) e "two opposite rules created at the same time never form a cycle…" (uma `201`, a outra `422`); escritas auditadas, `Idempotency-Key`, contrato, build e oasdiff — `RoutingApiTest` "rules need billing routing manage… idempotent and audited", `TransfersApiTest` "a transfer is idempotent…" e o `IDEMPOTENCY_KEY_REQUIRED` da transferência, `LayerArchitectureTest` (`writeUseCasesAreAudited`), os comandos acima.
- Parecer do verificador (orquestrador, 2026-10-19): verificação independente **APROVADO** com ressalvas (snapshot `eb0039740cc4eea06d6a4fefc0ee04168ee4c0bb`; `./gradlew build` verde com 759 testes; oasdiff sem `ERR`). Ressalvas: (1, médio) o teto `maxAmount` era contornável dividindo antes de estornar (o total ignorava as saídas e cada entrada tinha o próprio total: teto 50, lançamento de 100 dividido 50/50, cada metade estornada sem aprovação); (2) o `x-extensible-enum` precisava virar convenção registrada, com teste que confira a lista; (3, baixo) a mensagem de `POSTING_NOTHING_TO_REVERSE` falava só de ajustes num lançamento todo transferido; (4) as 14 decisões do implementador para a revisão. O usuário decidiu cada ponto (entrevista do orquestrador, 2026-10-19, opção recomendada em todos).
- Ajustes depois da verificação (decisões do usuário em 2026-10-19; etapa continua `#em-andamento`):
  1. **Limite pela linhagem:** o total conferido no `maxAmount` (e vinculado à aprovação) soma as correções de toda a linhagem do lançamento original — o normal de origem, as entradas de transferência que descendem dele, recursivamente, e os estornos e ajustes de cada uma — mais a nova. `PostingLedger.lineageRoot` (sobe das entradas pelas saídas, `WITH RECURSIVE`), `lineageCorrections` (desce pelas saídas e entradas) e `lockLineage` (trava consultiva pelo id do normal de origem, tomada depois da conta, para duas correções simultâneas em contas diferentes da mesma linhagem); a regra no domínio é `CorrectionLimit.cumulative` (substitui `PostingCorrections.cumulativeWith`). Teste `TransfersApiTest` "the correction ceiling adds up the whole lineage…": teto 50, lançamento de 100 dividido 50/50, a primeira metade estornada sem aprovação e a segunda com `403 APPROVAL_REQUIRED`, `limit.amount` 100; e A → B → C com ajuste de 40 sem aprovação e o de 20 pedindo com `limit.amount` 60. Documentado no `CLAUDE.md` (seção "Roteamento, transferência e divisão" e "Contas e lançamentos").
  2. **`x-extensible-enum` como convenção do projeto** para enums de resposta que vão crescer: anotação `@field:ExtensibleEnum` (`shared/ExtensibleEnum.kt`) e o `extensibleEnumCustomizer` (`PropertyCustomizer` em `OpenApiConfiguration`), que gera a lista das constantes do `enum` (a constante `POSTING_TYPES` escrita à mão saiu). Registrada na ARCHITECTURE §7 (linha "Enums de resposta que crescem") e no `CLAUDE.md` ("Convenções de API": quando usar, como declarar e que o cliente precisa tolerar valores desconhecidos). Teste novo `shared/web/ExtensibleEnumContractTest`: toda propriedade marcada sai no `api/openapi.yaml` sem `enum`, como `string` e com `x-extensible-enum` igual às constantes do `enum`, e `PostingResponse.type` = `PostingType.entries`.
  3. **Mensagem** de `422 POSTING_NOTHING_TO_REVERSE`: "Não resta nada deste lançamento a estornar: os ajustes ou as transferências já o zeraram." (código inalterado; conferida no mesmo teste do item 1).
  4. As 14 decisões do implementador ficaram aceitas.
  - Validações: `./gradlew updateOpenApi` → contrato igual em conteúdo ao anterior para o `type` (a lista agora vem do `enum`; 142 caminhos); `./gradlew build` → uma rodada com o executor de testes do Gradle interrompido ("Gradle Test Executor 28 FAILED", sem nenhum teste com falha registrada; intermitente), depois `./gradlew test` e `./gradlew build` verdes: `BUILD SUCCESSFUL in 5m 23s`, 762 testes em 99 classes, 0 falhas (`TransfersApiTest` 7/7, `ExtensibleEnumContractTest` 2/2); `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`. Snapshot depois dos ajustes: `438d5fcff6ebfadfb338c3804c09c69282a80914` (antes desta atualização do handoff).
- Parecer da 2ª verificação independente (orquestrador, 2026-10-19): **APROVADO** (snapshot `a04f8591d7963be5e506973c6ec53dd8110c7eed`; 762 testes, oasdiff sem `ERR`, sem queda do executor de testes), com ajustes pequenos pedidos.
- Ajustes depois da 2ª verificação (feitos com a etapa ainda `#em-andamento`):
  1. **Teste de concorrência da trava da linhagem:** `TransfersApiTest` "two simultaneous corrections in different folios of the same lineage never escape the ceiling" (teto 50, lançamento de 100 dividido 50/50 entre as contas A e B, estorno simultâneo do original em A e da entrada em B, em cinco rodadas: sempre um `201` e um `403 APPROVAL_REQUIRED` com `limit.amount` 100, e um estorno só gravado). Com `ledger.lockLineage(root)` comentado localmente o teste falhou ("[201, 201]"); a trava foi restaurada.
  2. `CLAUDE.md`: a citação de `PostingCorrections.cumulativeWith` (removido) passou a `CorrectionLimit.cumulative`.
  3. A nota para a E06 deixou de citar a constante `POSTING_TYPES` (saiu): a lista vem do `enum` pela `@field:ExtensibleEnum`.
  4. **Regras lidas antes de travar as contas — corrigido** (a correção era simples): o `BillingOperations.post` agora toma a trava consultiva das regras em modo compartilhado (`BillingStore.lockRoutingRulesShared`, `pg_advisory_xact_lock_shared`) antes de ler as regras e de travar as contas; as escritas de regra já tomavam a mesma em modo exclusivo, antes das contas, então a ordem das travas continua a mesma (sem deadlock) e lançamentos não se bloqueiam entre si. Uma regra editada ou desativada ao mesmo tempo já não desvia o lançamento pela versão anterior: ele espera a escrita confirmar e segue a regra confirmada. Teste `RoutingApiTest` "a posting waits for a rule write in progress and follows the rule as it is committed" (uma transação com a trava exclusiva desativa a regra; o lançamento não termina em 700 ms; depois do commit, ele fica na conta de origem). Com a trava compartilhada comentada localmente o teste falhou (o lançamento terminou na hora, desviado); restaurada. Nenhuma pendência baixa ficou deste ponto.
  - Validações: `./gradlew build` → `BUILD SUCCESSFUL in 5m 43s`, 764 testes em 99 classes, 0 falhas, sem queda do executor (`RoutingApiTest` 11/11, `TransfersApiTest` 8/8; a primeira rodada barrou dois avisos do detekt nos testes, `LongParameterList` e `NoNameShadowing`, corrigidos); `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1` (o contrato não mudou nesta rodada). Snapshot depois destes ajustes: `2861afa5fd6ed76d79a834fc81aa7de6bc0521fe` (antes desta linha).
- Fechamento (orquestrador, 2026-10-19): os ajustes depois da 2ª verificação (teste da trava da linhagem, a trava compartilhada das regras no `post` com o teste, e as duas referências da documentação) foram conferidos pelo relatório do implementador, com `./gradlew build` verde (764 testes) e oasdiff sem `ERR`, sem nova verificação independente. Etapa marcada `#concluida`.
- Notas para a próxima etapa:
  - E06 (taxa de serviço): o lançamento passa por `BillingOperations.post`, que agora roteia; decida se a taxa de serviço lançada automaticamente acompanha o item na conta final do roteamento (o mais natural: lançá-la na mesma conta do item, `posting.folioId`, sem rotear de novo) ou segue as regras do código `TX-SERVICO`. Um tipo novo de lançamento entra só no `PostingType` (a lista do `x-extensible-enum` do contrato é gerada do `enum` pela `@field:ExtensibleEnum` do `PostingResponse.type`; rode `./gradlew updateOpenApi`), e os `CHECK` de `billing.posting` precisam de migração nova (veja a da E05). A transferência de um item com taxa hoje move só o item.
  - E10 (encerramento): decida o que acontece com as regras ativas que apontam para uma conta que vai ser encerrada (hoje o lançamento roteado é recusado com `409 ROUTING_TARGET_CLOSED`; o encerramento pode desativar as regras de origem e recusar ou avisar as de destino). O encerramento trava a conta por `folioForUpdate` (agora `find` + `refresh` com trava). O extrato deve mostrar os vínculos de transferência (`transferredFrom…`/`transferredTo…`) e a origem dos roteados.
  - E07/E08: as escritas que travarem mais de uma conta usam `BillingStore.foliosForUpdate` (ordem de id); as de regra tomam antes `lockRoutingRules`.
  - M4: o roteamento é configurado por quem opera, pela API HTTP (a `BillingApi` não cria regras); se o M4 precisar criar regras (conta master de grupo), acrescente à `BillingApi` sobre o `RoutingRuleService` sem a permissão.

---

## E06 — Taxa de serviço — #concluida

**Objetivo:** lançar automaticamente a taxa de serviço sobre lançamentos cujo código a aceita, com o percentual do código ou o padrão do hotel, separada na conta e removível a pedido do cliente com registro (RF-CTA-14, RN-10).

**Por que agora:** o PDV (M8) e as diárias (M4, se a configuração pedir) lançam em contas; a taxa precisa ser um comportamento do `billing`, não de cada módulo.

**Dependências e paralelismo:** E04. Pode correr em paralelo com E05 e E07 (em worktrees separados); conflito de edição no serviço de lançamento com E05 — preferir executar em sequência.

**Escopo:**

- Percentual resolvido assim (H4): o informado pelo módulo de origem na `BillingApi.post` (o `pos`, no M8, passa o do ponto de venda), senão o `serviceChargeRate` do `TransactionCode`, senão `HotelApi.taxSettings().serviceChargeRate`. Nenhuma configuração nova no `hotel`.
- Ao lançar um item com `serviceChargeApplies`, gerar lançamento separado de taxa de serviço vinculado ao item (código próprio da E04), com o arredondamento de `Money` (`percent.of(money)`).
- Remoção a pedido do cliente: estorno do lançamento de taxa com motivo "removida a pedido", registrado e auditado; exige só a permissão própria (não é ação sensível de RF-ACS-08).
- Estorno do item estorna também a taxa vinculada, sem nova taxa.
- Taxa marcada como repasse (não receita) no grupo de receita, para o relatório de arrecadação em M6/M10.

**Fora de escopo:** relatório de rateio aos colaboradores e retenção para encargos (M6/M10, Q-08); taxa por ponto de venda (o `pos` a informa no M8).

**Passos de implementação:**

1. Testes: lançamento com e sem taxa conforme código e taxa informada; fallback para o padrão do hotel; remoção registrada; estorno do item; arredondamento único de `Money`.
2. Implementar a regra no fluxo de lançamento e o endpoint de remoção.
3. Regenerar o contrato e documentar.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em RF-CTA-14, RN-10, H4 e H5 e no handoff de E04.
- Partes paralelas: nenhuma.
- Foco do verificador: taxa calculada com arredondamento próprio em vez do de `Money`; taxa somada ao item em vez de lançamento separado; remoção sem registro; taxa sobre o estorno de um item; configuração de taxa duplicada no `billing` além do código.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E06 — "Taxa de serviço" do roadmap docs/roadmaps/roadmap-m3-hospedes-contas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo as hipóteses H4 e H5 e o "Protocolo de execução" —, a seção E06 inteira, o "Evidências e handoff" de E04 e RF-CTA-14 e RN-10 em docs/SPEC.md. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E04 está #concluida e que o código bate com o handoff dela (TransactionCode com serviceChargeRate anulável e HotelApi.taxSettings().serviceChargeRate como padrão); se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: no billing, gerar automaticamente um lançamento separado de taxa de serviço, vinculado ao item, quando o código de lançamento aceita a taxa, com o percentual informado pelo módulo de origem, senão o do código, senão o padrão do hotel, e permitir a remoção a pedido do cliente por estorno registrado e auditado. Não implemente o relatório de rateio, a retenção para encargos nem configuração por ponto de venda.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E06 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes primeiro, implemente, atualize CLAUDE.md e README.md, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em arredondamento fora de Money, taxa embutida no item, remoção sem registro e taxa sobre estorno.

Critérios de aceite (detalhes na seção E06):
- item com código que aceita taxa gera lançamento separado com o percentual resolvido; item sem taxa não gera;
- remoção a pedido cria estorno da taxa com motivo, auditado;
- estorno do item estorna também a taxa vinculada, sem nova taxa;
- build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E06 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Lançamento de item com `serviceChargeApplies` gera lançamento separado de taxa, vinculado ao item, com o percentual informado, senão o do código, senão o padrão do hotel, calculado com o arredondamento de `Money` (teste de integração para cada fonte do percentual).
- [ ] Código sem `serviceChargeApplies` não gera taxa.
- [ ] Remoção a pedido cria estorno da taxa com motivo "removida a pedido", auditado.
- [ ] Estorno do item estorna a taxa vinculada e não gera nova taxa.
- [ ] Taxa marcada como repasse no grupo de receita.
- [ ] Contrato regenerado; `./gradlew build` e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- A taxa do hotel muda com o tempo: o lançamento de taxa guarda o percentual aplicado, para o extrato e o relatório não recalcularem com a taxa nova.

**Evidências e handoff:**

- Snapshot inicial: `340f37a529a367f01f18508322128e415142e6d1` (tirado pelo orquestrador; árvore limpa sobre o commit `7472975`, a E05). Snapshot ao fim da implementação: `13efde42c11e591dabd50001051e49df8e51604b` (antes deste handoff e das notas no cabeçalho). Preparação: E04 `#concluida` e o código batia com o handoff dela (`TransactionCode.serviceChargeRate` anulável, `HotelApi.taxSettings().serviceChargeRate` como padrão, `TX-SERVICO` pré-carregado com a chave `SERVICE_CHARGE`, `AB` com `serviceChargeApplies`); a E05 `#concluida`, com o lançamento passando por `BillingOperations.post`.
- Arquivos alterados:
  - Novos, produção: `src/main/kotlin/com/zeny/hotel/billing/domain/ServiceCharge.kt` (percentual, a taxa sobre o item, o ajuste que acompanha, a repartição na divisão, o motivo da remoção) e a migração `src/main/resources/db/migration/billing/V2026_10_19_2230__add_service_charge.sql` (colunas `service_charge_for_id`, `service_charge_rate`, `triggered_by_id`, as FKs na mesma conta, os `CHECK`, `posting_service_charge_uk` e o trigger `posting_service_charge_coherent`).
  - Alterados, produção: `billing/BillingApi.kt` (`PostingType.SERVICE_CHARGE`, `RevenueGroup.passThrough`, `NewPosting.serviceChargeRate`, `PostingSummary` com `serviceChargeForId`, `serviceChargeRate` e `serviceCharge`, KDoc de `post`), `BillingErrorCodes.kt` (`SERVICE_CHARGE_FOLLOWS_ITEM`, `POSTING_NOT_SERVICE_CHARGE`), `BillingPermissions.kt` (`billing.servicecharge.remove`), `domain/Posting.kt` (`ServiceChargeLink`, `triggeredById`, `requireNotServiceCharge`, `SERVICE_CHARGE` em `CORRECTABLE`, `followable`, `FollowedItem` e os vínculos no estorno, no ajuste e na transferência), `application/BillingOperations.kt` (a taxa no `post`, `PostedItem`, `serviceChargeOf`), `FolioService.kt` (`PostingService`: estorno e ajuste com a taxa junto e o limite sobre o item com a taxa, `removeServiceCharge`), `PostingTransferService.kt` (a taxa na transferência e na divisão), `BillingApiService.kt`, `BillingPorts.kt` (`serviceChargeOf`, `PostingRow.serviceChargeId`), `BillingSupport.kt` (ação da trilha, visão e retrato com os vínculos), `infrastructure/JdbcPostingLedger.kt` (colunas novas, a taxa do item na leitura, `lineageRoot`/`lineageCorrections` com as taxas), `web/BillingDtos.kt` (`passThrough`, os vínculos no `PostingResponse`, `serviceChargeOutgoing`/`Incoming` na transferência), `web/FoliosController.kt` (`POST …/postings/{postingId}/remove-service-charge` e as descrições).
  - Testes: novos `src/test/kotlin/com/zeny/hotel/billing/ServiceChargeDomainTest.kt` e `ServiceChargeApiTest.kt`; alterado `iam/AuthorizationTest.kt` (o Gerente com `billing.servicecharge.remove`).
  - Documentação e contrato: `api/openapi.yaml` (regenerado: o caminho novo `removeServiceCharge`, os campos novos e `SERVICE_CHARGE` na lista do `x-extensible-enum`), `CLAUDE.md` ("Estado atual", a seção nova "Taxa de serviço" e as referências da E04/E05), `README.md` (seção "API" e a árvore) e este roadmap (tag, H4, H5, H16 e este handoff). A ARCHITECTURE não mudou (nenhuma aresta nova).
- Decisões:
  - Do usuário (entrevista do orquestrador em 2026-10-19, todas pela opção recomendada; registradas também no `CLAUDE.md`):
    1. **Item roteado:** a taxa vai para a conta final onde o item ficou (`posting.folioId`), sem rotear de novo pelas regras do `TX-SERVICO` (teste "a routed item gets the charge on the final folio…", com regras do `TX-SERVICO` na conta pedida e na final que não a desviam).
    2. **Transferência e divisão:** a taxa acompanha na mesma proporção (na transferência, tudo o que resta dela; na divisão, `Money.allocate` pelas partes do item), num par próprio vinculado ao par do item; item e taxa ficam juntos em cada conta.
    3. **Ajuste parcial do item:** gera o ajuste da taxa com o percentual guardado nela, arredondado por `Money`, limitado ao que resta dela. Forma escolhida (sem deriva de centavos): o ajuste deixa o que resta da taxa igual a `percentual × o que resta do item` (`ServiceCharge.followingAdjustment`), nunca aumentando a taxa; o item zerado zera a taxa. **Interação com o `maxAmount`** (avaliada como pedido; a mais conservadora coerente): o item com a taxa é uma unidade — o total conferido soma as correções da linhagem do item **e das taxas vinculadas a ela, inclusive as remoções a pedido**, mais a parte da taxa que acompanha a correção. Assim, estornar 100 + 10 confere 110, e remover a taxa antes e estornar depois confere 10 + 100 = 110 também: a ordem não muda nada, nada conta em dobro e o total nunca passa do item mais a taxa. Alternativas descartadas: não contar a taxa (o estorno de 110 caberia num teto de 100) ou contar só a taxa que acompanha (a remoção antes baixaria o total conferido, dependendo da ordem).
  - Do implementador (a opção mais conservadora e coerente com o código) — **a revisar**:
    1. **Lançamento `SERVICE_CHARGE` próprio**, com as colunas `service_charge_for_id` (o item, na mesma conta), `service_charge_rate` e `triggered_by_id` (a correção ou a saída do item que a da taxa acompanha), em vez de reaproveitar `corrects_id` (que faria a taxa contar como correção do item e baixar o que resta dele).
    2. **A taxa nunca se corrige nem se move à parte** pelos endpoints gerais (`422 SERVICE_CHARGE_FOLLOWS_ITEM` no estorno, ajuste, transferência e divisão da taxa); à parte, só a remoção a pedido (um estorno). O banco repete: a correção ou saída de uma taxa precisa acompanhar a do item, do mesmo tipo, salvo um estorno sem `triggered_by_id`.
    3. **Remoção pelo id da taxa** (`POST /api/v1/folios/{id}/postings/{postingId}/remove-service-charge`, sem corpo, `@Idempotent`, motivo fixo "Taxa de serviço removida a pedido do cliente."), não pelo id do item; a remoção de uma taxa transferida usa a entrada dela na conta de destino.
    4. **Permissão nova `billing.servicecharge.remove`** (não sensível, só `ALL`), sugerida a Gerente, Financeiro e Recepcionista (os mesmos das transferências); o Caixa A&B não a recebe (o PDV do M8 informa a taxa na `BillingApi.post`, e pode passar 0).
    5. **O código decide** se há taxa: o percentual informado num código sem `serviceChargeApplies` é ignorado (o PDV pode mandar o do ponto de venda em todo item); fora de 0 a 100 é `400 POSTING_INVALID` (campo `serviceChargeRate`). Percentual zero ou valor que arredonda a zero: nenhuma taxa.
    6. **Lançamento manual pela API HTTP não informa percentual** (vale o do código, senão o do hotel); para tirar a taxa, a remoção.
    7. **`TX-SERVICO` desativado recusa o item** com taxa (`422 TRANSACTION_CODE_INACTIVE`) em vez de lançá-lo sem a taxa; para deixar de cobrar, desliga-se `serviceChargeApplies` no código do item.
    8. **A taxa herda a origem (`source`), a data, o instante e o autor do item**, quantidade 1 e a descrição = o nome do código `TX-SERVICO`; correções da taxa repetem o motivo e a aprovação da correção do item.
    9. **Repasse no grupo de receita** como propriedade do enum (`RevenueGroup.passThrough`, só `SERVICE_CHARGE`), exposta como `passThrough` na resposta dos códigos, sem coluna nova (o grupo é fixo por código).
- Validações executadas:
  - Testes escritos antes da implementação (`ServiceChargeDomainTest`, `ServiceChargeApiTest`); antes dela, não compilavam (as APIs não existiam). Depois, `./gradlew test --tests 'com.zeny.hotel.billing.ServiceCharge*'` → 19 testes, 0 falhas; o teste do banco passou a conferir a mensagem de cada recusa (e a taxa num código errado usa um item sem taxa, para não cair no índice único antes do trigger).
  - `./gradlew lint` → `BUILD SUCCESSFUL` (depois do `ktlintFormat`, de um `TooManyFunctions` com motivo no `JdbcPostingLedger` e de um `NoNameShadowing` corrigido).
  - `./gradlew updateOpenApi` → contrato regenerado (+123/−8 linhas: o caminho `removeServiceCharge`, os campos `passThrough`, `serviceChargeForPostingId`, `serviceChargeRate`, `serviceChargePostingId`, `triggeredByPostingId`, `serviceChargeOutgoing`/`serviceChargeIncoming` e `SERVICE_CHARGE` no `x-extensible-enum`).
  - `.github/scripts/check-api-breaking.sh` → "No breaking changes to report… Nenhuma mudança incompatível (nível ERR) em /api/v1".
  - `./gradlew build` → 1ª rodada interrompida pelo executor de testes do Gradle ("Gradle Test Executor 56 FAILED", `OutOfMemoryError` no heap de 1 GB, depois de 94 classes, nenhuma falha registrada; parecido com a queda intermitente registrada na E05); a 2ª rodada, sem mudança de código, `BUILD SUCCESSFUL in 5m 16s`, 783 testes em 101 classes, 0 falhas (eram 764). Do `billing`: `ServiceChargeApiTest` 13/13, `ServiceChargeDomainTest` 6/6, `PostingsApiTest` 15/15, `TransfersApiTest` 8/8, `RoutingApiTest` 11/11, `BillingDomainTest` 7/7, `FoliosApiTest` 6/6, `TransactionCodesApiTest` 5/5; `ModularityTest` 13/13, `LayerArchitectureTest` 7/7, `DatabaseRolesTest` 7/7, `MigrationConventionTest` 11/11, `AuthorizationTest` 15/15, `ExtensibleEnumContractTest` 2/2, `ApiSecurityTest` 9/9; `checkOpenApi` verde.
  - Critérios de aceite, com a evidência: item com código que aceita a taxa gera lançamento separado e vinculado com o percentual de cada fonte, pela regra de `Money` — `ServiceChargeApiTest` "an item whose code accepts the charge gets a separate linked posting with the rate of the code" (12,5% de 99,99 = 12,50; o item fica 99,99; trilha com o vínculo), "without a rate of its own the code falls back to the hotel default, rounded by Money" (7,5% de 10,05 = 0,75; 10% de 0,25 = 0,02 em `HALF_EVEN`; o percentual guardado não muda com o padrão novo) e "the rate informed by the module of origin wins over the code and the hotel" (15% de 20,10 = 3,02 pela `BillingApi`), e `ServiceChargeDomainTest`; código sem a taxa não gera — "a code without the service charge generates none, even with a rate informed"; remoção a pedido com motivo e auditada — "the removal at the guest request reverses the charge with the reason, audited, without approval" (motivo "Taxa de serviço removida a pedido do cliente.", `billing.posting.remove-service-charge` com `serviceChargeRemoval`, sem aprovação, com permissão própria e `Idempotency-Key`); estorno do item estorna a taxa sem nova — "reversing the item reverses the linked charge too and posts no new charge" (o estorno da taxa com `triggeredByPostingId`; um só `SERVICE_CHARGE`) e o banco: "the database refuses an incoherent charge…" (taxa sobre um estorno recusada pelo trigger); taxa como repasse — o teste do código (`passThrough` verdadeiro só no `TX-SERVICO`) e "only the service charge revenue group is a pass-through…"; contrato, build e oasdiff — os comandos acima. Também: "adjusting the item adjusts the charge…", "the correction ceiling counts the item with its charge, removals included", "transferring and splitting the item take the charge along…", "the charge is never corrected nor moved apart from the item", "a routed item gets the charge on the final folio…" e "an inactive service charge code refuses the item…".
- Parecer do verificador (orquestrador, 2026-10-19): verificação independente **APROVADO** com ressalvas (snapshot `a12ccfd53173fd9298c66e1dd274d47080822239`; 783 testes; oasdiff sem `ERR`). Ressalvas: o OOM intermitente do executor de testes (anterior à E06: uns 22 contextos em cache de ~55 MB num heap de 1 GB); o `TX-SERVICO` desativado recusando até o item com a taxa zero; o Caixa A&B sem a permissão de remoção; e dois pontos baixos: faltava teste da remoção simultânea ao estorno do item, e o trigger não conferia que a entrada da taxa transferida cita a entrada do item **do mesmo par**. O usuário decidiu cada ponto (entrevista do orquestrador em 2026-10-19, opção recomendada em todos); as demais decisões do implementador ficaram aceitas.
- Ajustes depois da verificação (decisões do usuário em 2026-10-19; etapa continua `#em-andamento`):
  1. **Correção paralela, fora do escopo da E06 (o orquestrador a commita à parte):** o heap dos testes subiu para 2 GB (`maxHeapSize` em `build.gradle.kts`) e o cache de contextos do Spring ficou limitado a 16 (`spring.test.context.cache.maxSize=16` em `src/test/resources/spring.properties`, novo), com uma frase no parágrafo "Testes de integração" do `CLAUDE.md`. Só esses três pontos; nada da E06 neles.
  2. **`TX-SERVICO` desativado com a taxa zero:** o item passa sem taxa e sem conferir o código (`ServiceCharge.on` calcula o valor antes de `requireActive`); com taxa, a recusa `422 TRANSACTION_CODE_INACTIVE` continua. Testes: `ServiceChargeDomainTest` (percentual zero e valor que arredonda a zero com o código desativado) e `ServiceChargeApiTest` "an inactive service charge code refuses the item instead of dropping the charge, unless it is zero" (10% de 0,04 pela API HTTP e percentual 0 pela `BillingApi` passam; 10% de 10,00 é recusado).
  3. **`billing.servicecharge.remove`** fica com Gerente, Financeiro e Recepcionista (decisão do implementador 4 confirmada); nota para o M8 abaixo.
  4. **Remoção simultânea ao estorno do item:** `ServiceChargeIntegrityTest` "the removal and the reversal of the item at the same time reverse the charge once, without deadlock" (cinco rodadas: o estorno do item sempre `201`, a remoção `201` ou `409 POSTING_ALREADY_REVERSED`, um só estorno da taxa e saldo zero; as duas travam a conta primeiro).
  5. **Trigger do mesmo par:** `posting_service_charge_coherent` passou a recusar a entrada de uma taxa transferida cujo `service_charge_for_id` não é a entrada do item do mesmo par (a entrada cuja saída é o `triggered_by_id` da saída da taxa). Alterada a própria migração da E06 (`V2026_10_19_2230__add_service_charge.sql`), ainda não commitada nem aplicada fora dos testes: o mais limpo. Teste `ServiceChargeIntegrityTest` "the database refuses the entry of a transferred charge citing the entry of another item" (os pares de dois itens inseridos pelo dono numa transação: com a entrada do mesmo par o banco aceita, desfeito no fim; com a do outro item recusa com "do mesmo par"). Com a conferência retirada localmente o teste falhou ("no exception was thrown"); a migração foi restaurada.
  6. Para o detekt (`LargeClass`), os testes do banco e da concorrência saíram do `ServiceChargeApiTest` para o `ServiceChargeIntegrityTest` (novo; inclui "the database refuses an incoherent charge…"), e os helpers `createCode`, `reverse`, `removeCharge` e `uuid` foram para o `BillingTestSupport`.
  - Validações destes ajustes: `./gradlew lint` → sucesso (primeiro o detekt barrou `LargeClass` no `ServiceChargeApiTest`, resolvido pela divisão do item 6; depois, um helper `reverse` no `BillingTestSupport` encobria o do `PostingsApiTest` dentro de `with(billing)` e quebrou um teste dele, renomeado para `reverseWithReason`); `./gradlew updateOpenApi` → só as descrições do item com a taxa zero mudaram; `./gradlew build` duas vezes seguidas, sem mudança entre elas → `BUILD SUCCESSFUL in 5m 21s` e `in 5m 28s`, 785 testes em 102 classes, 0 falhas, sem queda do executor (`ServiceChargeApiTest` 12/12, `ServiceChargeIntegrityTest` 3/3, `ServiceChargeDomainTest` 6/6); `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.
- Parecer da 2ª verificação independente (orquestrador, 2026-10-19, verificador novo, snapshot `8da9e9b183e48b214e3bca95f5b184e5c8aadbc7`): **APROVADO** — os 6 critérios de aceite e os ajustes A, B, D1 e D2 conferidos (correção do OOM só no `maxHeapSize`, no `spring.properties` e numa frase do CLAUDE.md, sem estado em memória que dependa do cache; taxa zero passa com o TX-SERVICO desativado e taxa > 0 continua `422`; remoção × estorno simultâneos com a taxa estornada uma vez; trigger do mesmo par com teste de banco). `./gradlew build` verde sem OOM (785 testes em 102 classes, 0 falhas) e oasdiff sem `ERR`. Nenhum problema grave ou médio. Ressalvas baixas: (1) um banco local de `bootRun` que tenha aplicado a versão anterior, não commitada, da `V2026_10_19_2230` dá erro de checksum no Flyway (recrie-o ou rode `flyway repair`); (2) o teste de concorrência não confere que as duas ordens ocorreram nas cinco rodadas. Etapa marcada `#concluida` pelo orquestrador.
- Notas para a próxima etapa:
  - E10 (encerramento, extrato e views): o extrato deve mostrar a taxa de serviço separada, com o percentual (`service_charge_rate`) e o vínculo ao item, e a remoção a pedido (estorno da taxa sem `triggered_by_id`, motivo fixo). A view de lançamentos pode expor `service_charge_for_id`, `service_charge_rate`, `triggered_by_id` e o grupo de receita com `passThrough` (o grupo `SERVICE_CHARGE` é repasse), para o relatório de arrecadação e rateio do M6/M10 (que separa removidas de estornadas por `triggered_by_id`).
  - E08 (pagamentos): a taxa é um lançamento como os outros no saldo (`FolioBalance`); nada a fazer.
  - M8 (`pos`): informe o percentual do ponto de venda em `NewPosting.serviceChargeRate` (0 para não cobrar; com 0 o item passa mesmo com o `TX-SERVICO` desativado); a taxa volta em `PostingSummary.serviceCharge`. Decida com o PDV se o modelo Caixa A&B recebe `billing.servicecharge.remove` (decisão do usuário em 2026-10-19: hoje só Gerente, Financeiro e Recepcionista).
  - M4 (diárias): o `DIARIA` não tem a taxa; se a política mudar (Q-08), basta ligar `serviceChargeApplies` no código.

---

## E07 — Pontos e turnos de caixa — #pendente

**Objetivo:** permitir abrir turno de caixa por usuário e ponto de caixa com fundo de troco, registrar sangrias e suprimentos e fechar com contagem por forma de pagamento, opcionalmente cega, com diferenças registradas, relatório de fechamento e a notificação de diferença, o primeiro disparo real de notificação (RF-CTA-09, H13, H14).

**Por que agora:** todo recebimento exige turno aberto (RF-CTA-10); pagamentos (E08) dependem desta etapa. A decisão do usuário no gate do M2 pede a notificação de ponta a ponta no gate do primeiro marco com disparo real.

**Dependências e paralelismo:** E04. Pode correr em paralelo com E05 e E06 (em worktrees separados); conflito de edição em `api/openapi.yaml`, `CLAUDE.md`, `README.md`, `BillingModule.kt` e `ModularityTest`.

**Escopo:**

- `CashPoint` (recepção, restaurante, bar…) cadastrável, desativável, nunca apagado.
- `CashierShift`: abertura com fundo de troco; no máximo um turno aberto por usuário (H13; índice único parcial); `CashMovement` (sangria, suprimento) imutável com motivo, em tabela *append-only* (`AppendOnlyTables`).
- Fechamento com contagem declarada por forma de pagamento; modo cego não mostra o esperado antes da contagem; diferença por forma registrada.
- `expectedByMethod()` calculado a partir de fundo, movimentos e pagamentos do turno (pagamentos chegam na E08; aqui a soma considera zero pagamentos e o teste da E08 completa o cenário).
- Relatório de fechamento do turno em JSON (PDF em M5).
- `@Idempotent` nos POSTs; permissões (H16) com escopo `OWN` na leitura (operador vê só os próprios turnos sem `ALL`; filtro na SQL com `authorization.scopeFilter`).
- API pública `requireOpenShift(user)` na `BillingApi` (H13) para a E08 e módulos futuros (`pos`, M8).
- Notificação de diferença (H14): ao fechar com diferença, `NotificationsApi.notify` no mesmo caso de uso, tipo `billing.cashier-shift-difference` num `ModuleNotificationTypes` do `billing` (bean numa `@Configuration` de `application`), destinatários pela permissão só `ALL` de conferência (`NotificationRecipients.withPermission`), texto sem dado pessoal (ponto, data operacional e valores). `BillingModule` passa a depender de `notifications`; aresta na ARCHITECTURE §3.1 e no `ModularityTest`.

**Fora de escopo:** pagamentos (E08); reabertura de turno fechado; PDF do relatório (M5).

**Passos de implementação:**

1. Testes de domínio (esperado, diferença, modo cego) e de integração (turno único por usuário sob concorrência, imutabilidade, escopo `OWN`, notificação criada só com diferença e só para quem tem a permissão de conferência, rollback sem notificação).
2. Implementar migração, domínio, casos de uso, controllers, permissões e o tipo de notificação.
3. Regenerar o contrato e documentar.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em RF-CTA-09 e RF-CTA-10, no handoff de E04 e na seção "Notificações e push" do `CLAUDE.md`.
- Partes paralelas: nenhuma.
- Foco do verificador: dois turnos abertos para o mesmo usuário sob concorrência; valor esperado vazando no fechamento cego; movimento editável; operador vendo turno de outro sem `ALL`; notificação para o próprio operador ou com dado pessoal; notificação enviada numa transação desfeita.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E07 — "Pontos e turnos de caixa" do roadmap docs/roadmaps/roadmap-m3-hospedes-contas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo as hipóteses H13, H14 e H16 e o "Protocolo de execução" —, a seção E07 inteira, o "Evidências e handoff" de E04 e RF-CTA-09 e RF-CTA-10 em docs/SPEC.md §5.10. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E04 está #concluida e que o código bate com o handoff dela, e que H13 e H14 já foram decididas (se a tabela ainda disser "a decidir na sincronização", pare e peça a decisão); se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: implementar em billing os pontos de caixa e os turnos de caixa: abertura com fundo de troco (no máximo um turno aberto por usuário, conforme H13), sangria e suprimento imutáveis (append-only), fechamento com contagem por forma de pagamento (opcionalmente cego) e diferenças registradas, relatório de fechamento em JSON, a API pública requireOpenShift para os pagamentos e, conforme H14, a notificação de diferença pela NotificationsApi.notify com tipo próprio do billing e texto sem dado pessoal. Não implemente pagamentos (E08) nem PDF.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E07 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva os testes primeiro, implemente, atualize CLAUDE.md, README.md e, com a aresta billing → notifications, a ARCHITECTURE §3.1 e o ModularityTest; rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em turno duplicado sob concorrência, esperado visível no fechamento cego, movimento editável, escopo OWN e notificação com dado pessoal ou para o destinatário errado.

Critérios de aceite (detalhes na seção E07):
- segundo turno aberto para o mesmo usuário → 409 com code estável, inclusive em requisições simultâneas;
- fechamento cego não devolve o esperado antes da contagem e registra a diferença por forma;
- UPDATE e DELETE em movimentos falham no banco; operador sem ALL só vê os próprios turnos;
- fechamento com diferença notifica só quem tem a permissão de conferência; build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E07 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Abrir segundo turno para o mesmo usuário → `409` com `code` estável, inclusive com duas requisições simultâneas (teste).
- [ ] Sangria e suprimento registrados com motivo; `UPDATE`/`DELETE` em movimentos falham no banco (tabela num `AppendOnlyTables`).
- [ ] Fechamento cego: a API não devolve o valor esperado antes da contagem; após o fechamento, diferença por forma registrada e relatório disponível.
- [ ] Operador sem `ALL` lista só os próprios turnos (escopo `OWN`, filtro na SQL).
- [ ] `requireOpenShift` documentado na API pública de `billing`.
- [ ] Fechamento com diferença cria uma notificação `billing.cashier-shift-difference` para cada usuário com a permissão de conferência e nenhuma para os demais; sem diferença, nenhuma; o texto não tem dado pessoal; transação desfeita não notifica (teste).
- [ ] ARCHITECTURE §3.1 e `ModularityTest` com `billing` → `notifications`; contrato regenerado; `./gradlew build` e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Turno esquecido aberto: fica para o fechamento do dia (M6) listar como pendência; registrar no handoff.
- Se a H14 for recusada, o critério da notificação sai e a E07 não cria a aresta com `notifications`.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E08 — Pagamentos manuais, depósitos e reembolsos — #pendente

**Objetivo:** criar o módulo `payments` com `PaymentIntent` neutro, porta de provedor e provedor `MANUAL`, e registrar pagamentos na conta por dinheiro, Pix, crédito, débito e transferência informados manualmente (forma, valor, NSU/autorização), com vários pagamentos por conta, troco, depósitos antes da chegada e reembolso mediante aprovação (RF-PAG-03, RF-PAG-06, RF-CTA-06, 07, 10, 15).

**Por que agora:** sem pagamento não há check-out (M5). O desenho do `PaymentIntent` define como Pix e cartão entram em M7.

**Dependências e paralelismo:** E07 (turno de caixa; E04 transitivamente). Não paralelizável com E09.

**Escopo:**

- Pacote `com.zeny.hotel.payments` (a criar): `PaymentsModule.kt` com `allowedDependencies = ["shared"]` (o `notifications` da tabela §3.1 só entra no M7), schema próprio; `PaymentIntent` (finalidade, valor, provedor, status, referência opaca do destino), porta `PaymentProvider` e adaptador `MANUAL` que confirma na hora com os dados informados; `PaymentSucceeded` em `payments/PaymentsEvents.kt` (H1). Teste novo no `ModularityTest`.
- `billing`: `BillingModule` passa a depender de `payments`; `Payment` na conta (tabela *append-only*) com método, valor, NSU, autorização, bandeira e 4 últimos dígitos quando informados; turno aberto obrigatório (`requireOpenShift`); troco em dinheiro; vários pagamentos por conta; os pagamentos entram no esperado do turno da E07.
- Fluxo do registro manual e `@ApplicationModuleListener` idempotente de `PaymentSucceeded` no `billing` (H1).
- Depósito: pagamento em conta antes da chegada, identificado para o razão de depósitos (a ligação com reserva chega em M4 pelo `FolioOwner`).
- Reembolso manual (devolução registrada) como ação sensível (`requireOrApproval`, permissão `billing.payments.refund` sensível, ação `billing.payment.refund`), vinculado ao pagamento original, sem exceder o valor pago menos reembolsos anteriores (conta travada); sai do turno aberto do usuário.
- `CITY_LEDGER` recusado com `code` estável até M10.
- `@Idempotent` nos POSTs de pagamento e reembolso.
- RNF-10: nenhum campo de número de cartão completo nem CVV no contrato e nos DTOs; últimos dígitos com exatamente 4 dígitos; NSU e autorização com formato restrito, recusando uma sequência com cara de número de cartão (13 a 19 dígitos).

**Fora de escopo:** Pix com PSP, cartão integrado, tokenização e conciliação (M7); caução (E09); alertas de falha de integração do `payments` (M7).

**Passos de implementação:**

1. Testes de integração: pagamento sem turno aberto recusado; vários pagamentos e troco; reembolso com e sem aprovação e acima do reembolsável; idempotência; `PaymentSucceeded` registrado e listener idempotente (reentrega não duplica); imutabilidade no banco; campo com cara de número de cartão recusado.
2. Implementar `payments`, adaptador manual, integração com `billing` e endpoints.
3. Regenerar o contrato; documentar no `CLAUDE.md` como um provedor novo se encaixa (para M7).

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação (`Explore`): "o que SPEC §5.10 (RF-CTA-06, 07, 10, 15), §5.11, §8.2.7, §8.2.8, §8.3 (`PaymentSucceeded`) e ARCHITECTURE §8.3 (PCI-DSS) e §12 (padrão de integração) exigem de pagamentos e reembolsos?"; "como os listeners de produção (`ChecklistRoutineListener`, `PushDispatchListener`) garantem idempotência na reentrega do outbox?".
- Partes paralelas: nenhuma.
- Foco do verificador: pagamento registrado duas vezes (síncrono e pelo listener, ou na reentrega); reembolso acima do pago, inclusive com dois reembolsos simultâneos; recebimento sem turno aberto; campo de cartão completo ou CVV aceito; `payments` chamando `billing` (violação do grafo).

**Prompt para uma sessão limpa:**

```text
Execute a etapa E08 — "Pagamentos manuais, depósitos e reembolsos" do roadmap docs/roadmaps/roadmap-m3-hospedes-contas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo restrições, as hipóteses H1, H13 e H16 e o "Protocolo de execução" —, a seção E08 inteira e o "Evidências e handoff" de E04 e E07. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E07 está #concluida e que o código bate com o handoff dela e de E04, e que a H1 já foi decidida (se a tabela ainda disser "a decidir na sincronização", pare e peça a decisão); se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: criar o módulo payments com PaymentIntent neutro, porta PaymentProvider, provedor MANUAL e evento PaymentSucceeded, e registrar em billing pagamentos manuais na conta (dinheiro, Pix, crédito, débito, transferência; NSU, autorização, bandeira e 4 últimos dígitos quando informados) em tabela append-only, com troco, vários pagamentos por conta, turno de caixa aberto obrigatório, depósitos antes da chegada e reembolso por requireOrApproval, conforme H1. payments não chama billing; CITY_LEDGER é recusado até o marco M10; nunca aceitar número de cartão completo nem CVV.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E08 para #em-andamento no índice e no título;
- investigação: um subagente Explore responde às perguntas da seção E08 (SPEC e ARCHITECTURE de pagamentos, idempotência dos listeners de produção);
- leve ao usuário antes de decidir sozinho (numa sessão manual, por entrevista; como subagente, no relatório ao orquestrador) as decisões abertas da E08 listadas no cabeçalho (limite de valor no reembolso, identificação de depósito);
- escreva os testes de integração primeiro, implemente, atualize CLAUDE.md, README.md e o ModularityTest, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em pagamento duplicado pelo listener, reembolso acima do pago, recebimento sem turno, dado de cartão completo e payments chamando billing.

Critérios de aceite (detalhes na seção E08):
- pagamento sem turno aberto → 409 com code estável; com turno, registrado na conta e no esperado do turno;
- PaymentSucceeded registrado no outbox e o listener de billing não duplica o pagamento;
- reembolso sem aprovação → 403 APPROVAL_REQUIRED; acima do saldo reembolsável → 422;
- UPDATE e DELETE em pagamentos falham no banco; nenhum campo de cartão completo ou CVV no contrato; build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E08 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa e para o marco M7) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Pagamento sem turno de caixa aberto do usuário → `409` com `code` estável; com turno, o pagamento aparece na conta e no esperado do turno (teste de integração).
- [ ] Vários pagamentos numa conta e troco em dinheiro calculados corretamente; saldo da conta atualizado.
- [ ] `PaymentSucceeded` registrado no outbox; o listener de `billing` é idempotente e não duplica o pagamento já registrado, nem na reentrega (teste).
- [ ] Reembolso sem permissão nem aprovação → `403 APPROVAL_REQUIRED`; com aprovação, vinculado ao pagamento original e auditado com solicitante e aprovador; acima do saldo reembolsável → `422`, inclusive com dois reembolsos simultâneos.
- [ ] Depósito registrado em conta antes da chegada fica identificável como depósito.
- [ ] `CITY_LEDGER` → `422` com `code` estável.
- [ ] `UPDATE`/`DELETE` em pagamentos falham no banco (`AppendOnlyTables`); `Idempotency-Key` exigido.
- [ ] Nenhum DTO nem o `api/openapi.yaml` têm campo de número de cartão completo ou CVV; NSU ou autorização com 13 a 19 dígitos → `400`.
- [ ] `ModularityTest` com o teste do `payments` (`payments` não depende de `billing`); contrato regenerado; `./gradlew build` e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O desenho da H1 pode não servir ao Pix assíncrono de M7: revisão humana nesta etapa; registrar no handoff as notas para M7.

**Rollback / migração:** tabelas novas; após dados reais, pagamentos nunca são alterados por migração.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E09 — Caução registrada manualmente — #pendente

**Objetivo:** registrar na conta a pré-autorização de cartão feita em maquininha não integrada (valor, validade, NSU, autorização, bandeira, 4 últimos dígitos), capturá-la total ou parcialmente como pagamento e liberar a sobra (RF-CTA-08, recorte manual).

**Por que agora:** a recepção (M5) exige caução no check-in; até M7 ela é registrada manualmente.

**Dependências e paralelismo:** E08. Não paralelizável.

**Escopo:**

- `CardPreAuthorization` na conta com status (ativa, capturada, parcialmente capturada, liberada, expirada), `expiresAt` e dados não sensíveis do cartão (mesmas regras de RNF-10 da E08). O que muda (status) fica na linha da caução; capturas e liberações são fatos registrados.
- Captura total ou parcial gera `Payment` vinculado pelo fluxo da E08 (exige turno aberto, H13); liberação registrada; captura acima do autorizado recusada; a caução travada contra capturas simultâneas.
- Consulta de cauções a vencer (para o fechamento do dia em M6 listar e para M7 renovar).
- Auditoria e `@Idempotent`.

**Fora de escopo:** pré-autorização integrada, renovação automática e caução via Pix (M7).

**Passos de implementação:**

1. Testes: registro, captura parcial e total, liberação, captura acima do autorizado, caução expirada (relógio com o `MutableClock`), duas capturas simultâneas, consulta de cauções a vencer.
2. Implementar domínio, casos de uso e endpoints.
3. Regenerar o contrato e documentar.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em RF-CTA-08 e no handoff de E08.
- Partes paralelas: nenhuma.
- Foco do verificador: captura acima do autorizado ou de caução expirada; soma de capturas simultâneas acima do autorizado; captura sem turno aberto; dado de cartão completo; caução contada no saldo da conta como pagamento antes da captura.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E09 — "Caução registrada manualmente" do roadmap docs/roadmaps/roadmap-m3-hospedes-contas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo a hipótese H13 e o "Protocolo de execução" —, a seção E09 inteira, o "Evidências e handoff" de E08 e RF-CTA-08 em docs/SPEC.md §5.10. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E08 está #concluida e que o código bate com o handoff dela; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: registrar em billing a caução feita em maquininha não integrada (valor, validade, NSU, autorização, bandeira, 4 últimos dígitos), capturá-la total ou parcialmente gerando pagamento pelo fluxo de E08, liberar a sobra e consultar cauções a vencer. Não implemente pré-autorização integrada, renovação automática nem caução via Pix (marco M7).

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E09 para #em-andamento no índice e no título;
- sem investigação delegada; leve ao usuário antes de decidir sozinho (numa sessão manual, por entrevista; como subagente, no relatório ao orquestrador) se a expiração é calculada na leitura ou marcada por job (decisão aberta da E09 no cabeçalho);
- escreva os testes primeiro, implemente, atualize CLAUDE.md e README.md, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em captura acima do autorizado ou expirada, capturas simultâneas, captura sem turno, dado de cartão completo e caução contada como pagamento antes da captura.

Critérios de aceite (detalhes na seção E09):
- captura parcial gera pagamento do valor capturado e libera a sobra; captura acima do autorizado ou de caução expirada → 422;
- caução não capturada não altera o saldo da conta;
- consulta de cauções a vencer retorna as que vencem no período;
- build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E09 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Caução registrada não altera o saldo da conta (teste).
- [ ] Captura parcial gera `Payment` do valor capturado vinculado à caução e registra a liberação da sobra; captura total encerra a caução.
- [ ] Captura acima do autorizado ou de caução expirada → `422` com `code` estável; duas capturas simultâneas não ultrapassam o autorizado; captura sem turno aberto → `409`.
- [ ] Consulta de cauções a vencer devolve as que expiram no intervalo pedido.
- [ ] Escritas auditadas e com `Idempotency-Key`; contrato regenerado; `./gradlew build` e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- M7 vai integrar a pré-autorização real: manter o agregado neutro quanto ao provedor e registrar no handoff.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E10 — Encerramento, reabertura, extrato e views de leitura — #pendente

**Objetivo:** completar o ciclo de vida da conta (encerrar só com saldo zero, reabrir como ação sensível), oferecer o extrato da conta em JSON e publicar as views `report_*` de `billing` para os relatórios de M6 (RF-CTA-11, RF-CTA-12 parte de dados, RN-06).

**Por que agora:** o check-out (M5) depende do encerramento com saldo zero, e o `reporting` (M6) lê só as views publicadas.

**Dependências e paralelismo:** E05, E06, E09. Não paralelizável.

**Escopo:**

- Encerrar conta: exige saldo zero e nenhuma caução ativa não resolvida; conta fechada recusa lançamentos e pagamentos (inclusive pela `BillingApi`).
- Reabrir conta fechada: ação sensível (`requireOrApproval`, permissão `billing.folios.reopen` sensível, ação `billing.folio.reopen`, recurso `billing.folio:` seguido do id), auditada com solicitante e aprovador.
- API pública de saldo por dono (`BillingApi`, ex.: `balanceOf(owner)`/`isSettled(owner)`), para o check-out de M5 aplicar RN-06 sem `billing` conhecer reservas.
- Extrato em JSON: lançamentos, estornos, taxa de serviço, pagamentos, saldo, agrupamento por data operacional; prévia para o hóspede sem dados internos (sem usuário, motivos internos nem dados de aprovação).
- Views pela convenção do `CLAUDE.md` (nomes a confirmar na etapa: `billing.report_postings`, `billing.report_payments`, `billing.report_cashier_shifts`, `billing.report_deposits`): uma linha por fato, ids opacos, valores, datas operacionais, códigos e grupos de receita, situações; **sem** descrição ou motivo digitados, dado de hóspede nem dado de cartão além de bandeira e 4 últimos dígitos; `GRANT SELECT`/`REVOKE` por view; contrato no comentário da migração; consulta provada com um papel que só lê as views.

**Fora de escopo:** extrato em PDF (M5); razão de contas a receber (M10); relatórios em si (M6).

**Passos de implementação:**

1. Testes: encerrar com saldo diferente de zero recusado; conta fechada recusa lançamento e pagamento; reabertura com step-up; extrato consistente com o saldo; views legíveis com os dados esperados, só `SELECT` para o papel da aplicação e sem texto livre.
2. Implementar ciclo de vida, extrato e views (migração).
3. Regenerar o contrato; documentar as views no `CLAUDE.md` como API de leitura.

**Estratégia de execução:** `padrão` · Revisão humana: não

- Investigação: nenhuma; regras em RF-CTA-11, RF-CTA-12, RN-06, ARCHITECTURE §11 ("Leitura para relatórios") e a convenção de views do `CLAUDE.md` (modelo `checklists/V2026_10_16_1700__create_checklist_report_views.sql` e `ChecklistReportsTest`).
- Partes paralelas: nenhuma.
- Foco do verificador: conta encerrada com saldo diferente de zero por arredondamento; lançamento aceito em conta fechada pela `BillingApi`; reabertura sem aprovação; view expondo descrição digitada, documento de hóspede ou dado de cartão; view com `INSERT`/`UPDATE` para o papel da aplicação.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E10 — "Encerramento, reabertura, extrato e views de leitura" do roadmap docs/roadmaps/roadmap-m3-hospedes-contas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir) — inclusive a convenção de views report_* em "Schemas e migrações" —, o cabeçalho do roadmap — incluindo o "Protocolo de execução" —, a seção E10 inteira e o "Evidências e handoff" de E04 a E09. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E05, E06 e E09 estão #concluida e que o código bate com o handoff delas; se não, ou se o código divergiu do plano, pare e relate sem implementar.

Objetivo: em billing, encerrar conta só com saldo zero e sem caução ativa, recusar lançamentos e pagamentos em conta fechada (também pela BillingApi), reabrir por requireOrApproval, expor a API pública de saldo por dono para o check-out do marco M5, entregar o extrato da conta em JSON e publicar as views report_* de lançamentos, pagamentos, turnos de caixa e depósitos pela convenção do CLAUDE.md. Não gere PDF nem relatórios.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E10 para #em-andamento no índice e no título;
- sem investigação delegada; leve ao usuário antes de decidir sozinho (numa sessão manual, por entrevista; como subagente, no relatório ao orquestrador) os nomes e colunas das views (decisão aberta da E10 no cabeçalho);
- escreva os testes primeiro, implemente, atualize CLAUDE.md e README.md, rode ./gradlew updateOpenApi, ./gradlew build e .github/scripts/check-api-breaking.sh;
- verificação independente com o Prompt do verificador, com foco em encerramento com saldo residual, lançamento em conta fechada, reabertura sem aprovação e view com texto livre, dado pessoal ou de cartão.

Critérios de aceite (detalhes na seção E10):
- encerrar com saldo diferente de zero ou caução ativa → 409 com code estável;
- lançamento ou pagamento em conta fechada → 409; reabertura sem aprovação → 403 APPROVAL_REQUIRED;
- extrato em JSON soma exatamente o saldo da conta;
- views report_* criadas, documentadas, só com SELECT para o papel da aplicação e sem texto livre, dado pessoal ou de cartão; build e oasdiff passam.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E10 (snapshot, arquivos alterados, decisões, comandos e resultados, parecer do verificador, notas para a próxima etapa) e responda com um resumo curto de mudanças, validações e pendências. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] Encerrar conta com saldo diferente de zero ou caução ativa → `409` com `code` estável; com saldo zero, conta fechada e auditada.
- [ ] Lançamento ou pagamento em conta fechada → `409`, pela API HTTP e pela `BillingApi`.
- [ ] Reabertura sem permissão nem aprovação → `403 APPROVAL_REQUIRED`; com aprovação, conta reaberta e auditada com solicitante e aprovador.
- [ ] API pública de saldo por `FolioOwner` documentada e testada.
- [ ] Extrato em JSON: soma de lançamentos menos pagamentos igual ao saldo, em cenário com estorno, taxa de serviço, transferência e caução capturada; a prévia para o hóspede não tem usuário, motivo interno nem aprovação.
- [ ] Views `report_*` de `billing` existem, são lidas num teste de integração com um papel que só as lê, o papel da aplicação só tem `SELECT` nelas, e não contêm texto livre, documento de hóspede nem dado de cartão além de bandeira e 4 últimos dígitos.
- [ ] Contrato regenerado; `./gradlew build` e oasdiff passam.

**Validação obrigatória:**

- `./gradlew build` → sucesso.
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Views acopladas às tabelas internas: tratá-las como contrato do módulo (coluna publicada não muda; mudança incompatível cria `report_x_v2`) e cobrir com teste, para que mudanças de tabela não quebrem `reporting` em silêncio.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E11 — Gate do marco M3 — #pendente

**Objetivo:** confirmar, a partir de uma cópia limpa do repositório e de um `docker compose` isolado, que toda a definição global de pronto do M3 é atendida, inclusive a notificação de ponta a ponta, e deixar o roadmap do M4 pronto para ser sincronizado.

**Por que agora:** é o critério de entrada do marco M4.

**Dependências e paralelismo:** E02, E03, E10 (as etapas-folha, que cobrem transitivamente as demais). Não paralelizável.

**Escopo:**

- Rodar `./gradlew clean build`, `clean test`, `lint` e `checkOpenApi` do zero numa cópia limpa (`git archive` do snapshot, `GRADLE_USER_HOME` novo no scratchpad, `--no-daemon --no-build-cache`), e `.github/scripts/check-api-breaking.sh` contra o gate do M2 (`8d163b2`).
- Conferir cada item da "Definição global de pronto" com evidência.
- Cenário ponta a ponta automatizado (teste de integração): cadastrar hóspede e empresa, abrir conta com pagador empresa, lançar com roteamento e taxa de serviço, abrir turno, receber pagamento manual, registrar e capturar caução, estornar com aprovação, zerar saldo e encerrar.
- Roteiro com `curl` contra um `docker compose` isolado (`-p` próprio, `--env-file` no scratchpad gerado do `docker/.env.example`, porta própria, profile `prod`, pelo proxy; `down -v` no fim, sem tocar no `docker/.env` do usuário): o mesmo cenário, a busca por documento, a exportação com aprovação, a anonimização, o banco recusando `UPDATE`/`DELETE` nas tabelas *append-only* e, com a H14, o fechamento de turno com diferença aparecendo no painel e no tópico SSE `notifications` do destinatário (e no push, se houver `ZENY_FCM_CREDENTIALS`); logs do `prod` sem documento, dado sensível, nome de hóspede, senha, PIN nem token.
- Revisar `CLAUDE.md` (API pública de `guests`, `billing` e `payments`, eventos, portas e views) e a seção "API" do `README.md`.
- Registrar a conclusão do M3 no histórico de revisões de `docs/roadmaps/README.md`.

**Fora de escopo:** funcionalidade nova. Problemas voltam para a etapa de origem (reabrir com `#em-andamento` e registrar no handoff dela), salvo ajustes triviais de documentação.

**Passos de implementação:**

1. Escrever o teste ponta a ponta do marco.
2. Rodar tudo do zero numa cópia limpa e o roteiro no compose isolado, registrando a evidência de cada item.
3. Revisar documentação e atualizar o README dos marcos.

**Estratégia de execução:** `padrão` · Revisão humana: sim

- Investigação: nenhuma; o modelo do roteiro é o handoff da E10 do roadmap do M2.
- Partes paralelas: nenhuma.
- Foco do verificador: item da definição de pronto sem evidência; teste ponta a ponta que não exercita a imutabilidade nem a aprovação; notificação não mostrada no compose (com a H14); dado sensível exposto em algum endpoint ou log do marco; dependência entre módulos fora da ARCHITECTURE §3.1.

**Prompt para uma sessão limpa:**

```text
Execute a etapa E11 — "Gate do marco M3" do roadmap docs/roadmaps/roadmap-m3-hospedes-contas.md, neste repositório.

Antes de alterar qualquer coisa, leia CLAUDE.md (e AGENTS.md, se existir), o cabeçalho do roadmap — incluindo o "Protocolo de execução", a hipótese H14 e a "Definição global de pronto" —, a seção E11 inteira, o "Evidências e handoff" de E01 a E10 e, como modelo de roteiro, o handoff da E10 de docs/roadmaps/roadmap-m2-plataforma-operacional.md. Rode git status e preserve mudanças preexistentes que não sejam suas. Confirme que E01 a E10 estão #concluida; se não, ou se o código divergiu do plano, pare e relate.

Objetivo: escrever o teste ponta a ponta do marco (hóspede e empresa, conta com pagador empresa, lançamento com roteamento e taxa de serviço, turno de caixa, pagamento manual, caução capturada, estorno com aprovação, saldo zero e encerramento), verificar numa cópia limpa, sem caches nem estado local, cada item da definição global de pronto do M3, e rodar o roteiro num docker compose isolado pelo proxy, inclusive a notificação de diferença de caixa de ponta a ponta (H14). Não implemente funcionalidade nova: problemas são devolvidos à etapa de origem, salvo ajustes triviais de documentação.

Siga o Protocolo de execução com a estratégia padrão:
- tire o snapshot inicial e troque a tag de E11 para #em-andamento no índice e no título;
- sem investigação delegada;
- escreva o teste ponta a ponta, rode do zero numa cópia limpa ./gradlew clean build, clean test, lint e checkOpenApi, rode .github/scripts/check-api-breaking.sh contra 8d163b2, suba o compose isolado (-p próprio, env no scratchpad, porta própria, prod) e registre a evidência de cada item da definição global de pronto; derrube com down -v;
- revise o CLAUDE.md e o README.md e registre a conclusão do M3 no histórico de docs/roadmaps/README.md;
- verificação independente com o Prompt do verificador, com foco em item sem evidência, teste ponta a ponta fraco, notificação não mostrada, dado sensível exposto e dependência fora da ARCHITECTURE §3.1.

Critérios de aceite (detalhes na seção E11):
- teste ponta a ponta do marco passa;
- todos os itens da definição global de pronto marcados com evidência numa cópia limpa e no compose;
- CLAUDE.md e README.md coerentes com o código; docs/roadmaps/README.md registra a conclusão do M3.

Não declare sucesso com validação falhando ou não executada. Marque #concluida só com todos os critérios atendidos e parecer APROVADO do verificador; diante de impedimento real, marque #bloqueada e registre causa, evidência, impacto e ação de desbloqueio em "Bloqueios".

No fim, preencha "Evidências e handoff" de E11 (snapshot, verificações, resultados, parecer do verificador, notas para o marco M4) e responda com um resumo curto. Não faça commit, push ou deploy.
```

**Critérios de aceite:**

- [ ] O teste ponta a ponta do marco passa e exercita imutabilidade, aprovação, turno de caixa e encerramento com saldo zero.
- [ ] Todos os itens da "Definição global de pronto" estão marcados, cada um com evidência numa cópia limpa e, onde o compose alcança, no roteiro pelo proxy (com a H14, a notificação no painel e no tópico SSE do destinatário).
- [ ] `CLAUDE.md` documenta a API pública de `guests`, `billing` e `payments`, os eventos `GuestsMerged` e `PaymentSucceeded`, as portas (contribuidores, estadia ativa, índice cego) e as views; o `README.md` cobre a API do marco.
- [ ] `docs/roadmaps/README.md` registra a conclusão do M3 no histórico de revisões.

**Validação obrigatória:**

- `./gradlew clean build`, `clean test`, `lint` e `checkOpenApi` numa cópia limpa, sem cache → sucesso; `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR`.
- `docker compose -p` isolado `-f docker/compose.yaml up -d --build` → readiness `UP` e roteiro ponta a ponta concluído; `down -v` em seguida.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Lacunas descobertas só no ponta a ponta: devolver à etapa de origem em vez de remendar no gate.
- O que o compose não alcança (eventos sem listener de produção, como `GuestsMerged`; caução expirada, que depende do tempo): evidência nos testes da cópia limpa, registrada no handoff, como no gate do M2.

**Evidências e handoff:**

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para o marco M4: —

---

## Histórico de revisões

- 2026-10-13 — roadmap criado.
- 2026-10-13 — aplicada a regra por camadas da ARCHITECTURE §3.1 (decisão do usuário): contexto e restrições citam a regra; `billing` → `printing` registrado para o extrato em PDF de M5 (dono + printing). Nenhuma etapa mudou de escopo.
- 2026-10-15 — notas herdadas do M2 acrescentadas em "Hipóteses e decisões em aberto" (`FileAccessPolicy` e gravação de conteúdo pela `FilesApi` na E03; `clientFileId`, reencode, `DeviceSessionsRevoked` e abandono do outbox para o contexto). Nenhuma etapa reescrita; tags inalteradas.
- 2026-10-16 — sincronizado com o código depois do gate do M2 (commits `5ab91f9`, `15b7ec7` e `8d163b2`, CI verde no run `38954633425`). "Contexto confirmado" reescrito com os módulos, caminhos, comandos, portas e APIs públicas reais (`HotelApi`, `FilesApi` sem gravação de conteúdo nem `FileAccessPolicy`, `NotificationsApi.notify`, `IamApi` restrita a `staff` e `notifications`), a convenção das views `report_*`, `AppendOnlyTables`, `requireOrApproval`, `@Idempotent`, o apoio de testes e os gates com compose; as "Notas herdadas do M2" viraram a lista de pendências com a etapa de cada uma. H4 confirmada no código (taxa de serviço padrão no `hotel`; a do código fica no `billing`; a do ponto de venda vem do `pos` no M8) e H7 confirmada na documentação (sem offline nem SSE no M3). H1 passou a "a decidir na sincronização"; hipóteses novas H8 a H14 (índice cego, documento mascarado, retratos sem dado pessoal, `FileAccessPolicy`, exportação na resposta, turno único por usuário, notificação de diferença de caixa), escritas com a opção recomendada e a confirmar pelo usuário, H15 (`HV000271` na E01) e H16 (nomes de permissões); lista de decisões abertas para a entrevista de cada etapa. Etapas alteradas: E01 (índice cego no `shared`, documento mascarado, `HV000271`), E03 (finalidades sensíveis pelo `files` existente, exportação na resposta, anonimização que apaga anexos e índices, view pela convenção), E04 (`AppendOnlyTables`, `requireOrApproval` com `LimitAmount`, taxa do código anulável, riscos dos dois papéis resolvidos), E06 (fonte do percentual pela H4, sem configuração por ponto de venda), E07 (turno único por usuário e a notificação de diferença com a aresta `billing` → `notifications`), E08 (módulo `payments` só com `shared`, reembolso por `requireOrApproval`, formato de NSU), E10 (views pela convenção, sem texto livre) e E11 (cópia limpa, oasdiff contra `8d163b2` e roteiro no compose isolado com a notificação de ponta a ponta); validações trocadas pelos comandos oficiais (`./gradlew build` e o oasdiff). Nenhuma etapa nova, removida ou renumerada; todas seguem `#pendente`.
- 2026-10-16 — entrevista da sincronização: o usuário decidiu H1 e H8 a H14 pela opção recomendada (P1 índice cego por HMAC derivado do anel; P2 documento mascarado, inteiro só por ação própria auditada; P3 trilha sem dado pessoal do hóspede; P4 sem `FileAccessPolicy`, finalidade sensível só `ALL`; P5 exportação na própria resposta; P6 pagamento manual síncrono pelo `payments` com `PaymentSucceeded` pelo outbox; P7 um turno de caixa aberto por usuário; P8 notificação de diferença no fechamento do turno, `billing` → `notifications`). Texto das etapas inalterado (já seguia as recomendadas); tags inalteradas.
