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
| H2 | Até o M7, `TransactionCode` guarda uma classificação fiscal opcional em texto livre; o vínculo com `FiscalProfile` chega no M7 sem migração destrutiva. | Coluna renomeada ou convertida no M7. | E04 |
| H3 | A exportação de dados do titular usa uma porta definida em `guests` (contribuidores de dados pessoais) que módulos acima implementam. No M3 só `guests` e os anexos contribuem: o `billing` não liga lançamento a hóspede (o dono é `FolioOwner`); M4/M5 acrescentam os seus. | Exportação incompleta até os demais módulos contribuírem; registrado como pendência nos marcos seguintes. | E03 |
| H4 | **Confirmada no código em 2026-10-16:** `HotelApi.policy()` traz `childMaxAge` e `childAgeBands`; `HotelApi.taxSettings()` traz `serviceChargeRate` (a **padrão** do hotel), `issRate` e a taxa de turismo; o comentário de `TaxSettings` diz que pontos de venda e códigos de lançamento "guardam a própria, quando diferente, e caem nesta". Logo: `TransactionCode` tem `serviceChargeRate` anulável (cai na do hotel) e a API de lançamento aceita uma taxa informada pelo módulo de origem (o `pos`, no M8, passa a do ponto de venda); nenhuma configuração nova no `hotel`. | — | E04, E06 |
| H5 | SPEC Q-08 segue o padrão adotado: taxa de serviço só em A&B por configuração (códigos padrão de A&B com `serviceChargeApplies`, diária sem), retenção para encargos fora do M3; o motor em `billing` é genérico por código de lançamento. | Mudança de política é só configuração. | E04, E06 |
| H6 | Mesclagem de duplicados mantém o perfil sobrevivente, marca o absorvido como mesclado (sem excluir) e publica `GuestsMerged`, que módulos acima de `guests` escutam para repontar referências. No M3 não há consumidor de produção (o primeiro é `reservations`, M4). | Sem o evento, reservas antigas apontariam para o perfil absorvido; o evento evita reescrever outros schemas. | E02 |
| H7 | **Confirmada na documentação** (ARCHITECTURE §9, "Recepção, contas e PDV exigem conexão", e §10, sem tópico de contas): o M3 não registra `OfflineSource`, `OfflineCommandHandler` nem `RealtimeTopic`. | Se um cliente pedir atualização em tempo real da conta, um tópico entra depois sem mudar o modelo. | Todas |
| H8 | **Decidida pelo usuário em 2026-10-16** (entrevista da sincronização, P1, a opção recomendada): busca por documento cifrado por **índice cego** numa porta nova do `shared` (ex.: `BlindIndex`, ao lado de `TokenSealer`): HMAC-SHA256 do valor normalizado (CPF só dígitos; demais documentos em maiúsculas, sem espaços nem pontuação, com tipo e país) com chave **derivada por HKDF de cada chave do anel `ZENY_ENCRYPTION_KEYS`** (como o `HkdfTokenSealer`, `info` = finalidade); grava-se o id da chave junto do hash e a busca calcula com todas as chaves do anel, então a rotação segue a do anel sem segredo novo. | Alternativas: chave própria `ZENY_BLIND_INDEX_KEY` (sem rotação: trocar = reindexar) ou busca sem índice (descarta o critério de não decifrar a tabela). `fnrh` e `reservations` vão buscar por documento. | E01, E02 |
| H9 | **Decidida pelo usuário em 2026-10-16** (entrevista da sincronização, P2, a opção recomendada): as consultas devolvem o documento **mascarado** (tipo, país e só os últimos caracteres); o número inteiro só por uma ação própria (`POST /api/v1/guests/{id}/documents/{documentId}/reveal` ou equivalente) com permissão própria e cada acesso na trilha (RNF-09, "log de acesso a dados"). | Alternativas: número inteiro em toda leitura com a permissão de leitura (sem registro de acesso) ou toda leitura de perfil auditada. Muda DTOs, permissões e testes da E01 e a exportação da E03. | E01, E03 |
| H10 | **Decidida pelo usuário em 2026-10-16** (entrevista da sincronização, P3, a opção recomendada): os retratos da trilha do `guests` (e os do `billing` que citem pessoas) **não levam dado pessoal em claro**: só ids, situação, flags (VIP, restrição), tipo do documento e a lista dos campos alterados (`changedFields`); nome, contatos, endereço, número de documento, motivo da restrição e observações ficam fora. Assim a anonimização (E03) não deixa dado pessoal na trilha *append-only*. | Alternativa: retratos completos visíveis a quem tem `audit.read`, aceitando que a anonimização não alcança a trilha (registrar como limitação para o M10). | E01, E02, E03, E04, E07 |
| H11 | **Decidida pelo usuário em 2026-10-16** (entrevista da sincronização, P4, a opção recomendada): a porta `FileAccessPolicy` **não é criada** e a Q1 do M1 fica encerrada: as finalidades de documento de hóspede são sensíveis (cifradas, servidas pelo servidor, emissão e cada download na trilha), com upload e download por permissões próprias do `guests` declaradas só com escopo `ALL`; a anonimização apaga os anexos (`FilesApi.delete`). | Alternativa: porta no `files` implementada pelo dono para regras por registro (ex.: recusar anexo de perfil mesclado). Custo aceito: a regra de acesso é só a permissão da finalidade. | E03 |
| H12 | **Decidida pelo usuário em 2026-10-16** (entrevista da sincronização, P5, a opção recomendada): a exportação do titular devolve o pacote **na própria resposta** (JSON com os dados de cada contribuidor e a lista de anexos com ids e metadados; o conteúdo dos anexos sai pelo `GET /api/v1/files/{id}/download-url` com a permissão da finalidade), sem guardar arquivo e sem `@Idempotent` (a resposta guardada teria os dados pessoais). A `FilesApi` que grava conteúdo pelo servidor fica para o M5 (`printing`). | Alternativa: criar `FilesApi.store` numa etapa nova antes da E03 e guardar o pacote como anexo sensível órfão (expurgado em 24 h), exigindo um tipo `JSON`/`ZIP` novo no `files`. | E03 |
| H13 | **Decidida pelo usuário em 2026-10-16** (entrevista da sincronização, P7, a opção recomendada): no máximo **um turno de caixa aberto por usuário** (em qualquer ponto de caixa), garantido por índice único parcial; recebimentos, devoluções e capturas usam o turno aberto do próprio usuário, sem informá-lo na requisição (`requireOpenShift(user)`). | Alternativa: um turno aberto por usuário **e** ponto (a requisição informa o ponto ou o turno). Muda E07, E08, E09 e o PDV (M8). | E07, E08, E09 |
| H14 | **Decidida pelo usuário em 2026-10-16** (entrevista da sincronização, P8, a opção recomendada): a E07 traz o primeiro disparo real de `NotificationsApi.notify`: fechar um turno com diferença em alguma forma de pagamento notifica quem tem uma permissão só `ALL` de conferência de turnos (sugerida a Gerente e Financeiro), com um tipo novo `billing.cashier-shift-difference` num `ModuleNotificationTypes` do `billing` e texto sem dado pessoal (ponto de caixa, data e valor). A aresta `billing` → `notifications` entra na ARCHITECTURE §3.1 e no `ModularityTest`. O gate (E11) mostra a notificação de ponta a ponta no compose (painel e tópico SSE `notifications`). | Alternativa: nenhum disparo no M3, e a prova de ponta a ponta fica para o gate do M4/M5. | E07, E11 |
| H15 | O aviso `HV000271` (`@Valid` num `List`) é corrigido na E01: os DTOs novos usam `List<@Valid T>` e os dez existentes (`grants`, `limits`, `applicability`, `sections`, `items`, `options`, `actions`, `commands`, `answers`, `preferences`) são convertidos, com um teste que prova que um item inválido dentro da lista ainda dá `400` (o Kotlin precisa emitir a anotação de uso de tipo no argumento genérico). | Se o Kotlin 2.4 não emitir a anotação no bytecode sem opção de compilador, a E01 registra a opção usada ou mantém `@Valid` no `List` e relata; nunca perde a validação em cascata. | E01 |
| H16 | Nomes de permissões sugeridos, no padrão módulo, recurso e ação separados por ponto (segmentos minúsculos, sem hífen): `guests.profiles.read`/`manage`, `guests.documents.reveal`, `guests.restrictions.manage`, `guests.profiles.merge`, `guests.companies.read`/`manage`, `guests.attachments.read`/`manage`, `guests.data.export` (sensível), `guests.data.anonymize`; `billing.codes.manage`, `billing.folios.read`/`manage`, `billing.postings.create`, `billing.postings.reverse`/`adjust` (sensíveis, limite `maxAmount` em `MONEY`), `billing.routing.manage`, `billing.folios.reopen` (sensível), `billing.cashier.operate`, `billing.shifts.read` (`OWN`/`ALL`), `billing.shifts.review` (só `ALL`), `billing.payments.receive`, `billing.payments.refund` (sensível). Modelos de role pela SPEC §3.1: Recepcionista, Gerente, Financeiro e Caixa A&B. | Nomes são confirmados na etapa; um código publicado não muda depois. | E01, E02, E03, E04, E05, E07, E08, E10 |

**Decisões abertas para a entrevista de cada etapa** (dependem da investigação; a sessão da etapa devolve os pontos ao orquestrador, que entrevista o usuário):

- **E01:** endereço de hóspede estrangeiro (o `Address` do `shared` é só Brasil, com IBGE); tipos de documento aceitos além de CPF e passaporte; unicidade de CNPJ entre empresas; busca por nome sem acento (se exigir `unaccent`/`pg_trgm`, criar no `shared` como o `btree_gist`).
- **E02:** regra de escolha dos campos na mesclagem; restrição e VIP herdados pelo sobrevivente.
- **E03:** anonimização como ação sensível (step-up) ou só com permissão; o que se apaga e o que fica (consentimentos como prova, RN-20); finalidades de anexo (foto de documento com reencode; PDF numa finalidade à parte, sem reencode); se entra uma view de empresas.
- **E04:** lista dos códigos padrão e grupos de receita; tipos de `FolioOwner`; semântica do ajuste (sinal, código próprio) e chaves de limite.
- **E05:** precedência entre regras de roteamento sobrepostas.
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
| E02 | Detecção e mesclagem de duplicados | #pendente | E01 | Sugestão de duplicados e mesclagem sem perda de histórico, com evento `GuestsMerged` |
| E03 | LGPD: consentimentos, anexos e direitos do titular | #pendente | E01 | Consentimentos, anexos sensíveis, exportação auditada, correção e anonimização sob pedido, view de leitura |

**Contas e caixa (`billing`, `payments`)**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E04 | Códigos de lançamento, contas e lançamentos imutáveis | #pendente | E01 | Módulo `billing` com `TransactionCode`, `Folio`, `Posting`, estorno e ajuste sob aprovação |
| E05 | Roteamento, transferência e divisão | #pendente | E04 | Regras de roteamento aplicadas no lançamento; transferência e divisão manuais |
| E06 | Taxa de serviço | #pendente | E04 | Taxa de serviço automática por código, separada na conta e removível com registro |
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

## E02 — Detecção e mesclagem de duplicados — #pendente

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

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E03 — LGPD: consentimentos, anexos e direitos do titular — #pendente

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

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E04 — Códigos de lançamento, contas e lançamentos imutáveis — #pendente

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

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E05 — Roteamento, transferência e divisão — #pendente

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

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

---

## E06 — Taxa de serviço — #pendente

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

- Snapshot inicial: —
- Arquivos alterados: —
- Decisões: —
- Validações executadas: —
- Parecer do verificador: —
- Notas para a próxima etapa: —

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
