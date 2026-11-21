# Roadmap — M7 Pagamentos integrados e fiscal

> Arquivo: `docs/roadmaps/roadmap-m7-pagamentos-fiscal.md` · branch de execução: `roadmap/m7-pagamentos-fiscal` · criado em 2026-10-13 · última revisão 2026-11-21
>
> Marco 7 de 11 · mapa dos marcos em [`docs/roadmaps/README.md`](README.md) · anterior: [M6 — Operação diária e fechamento](roadmap-m6-operacao-fechamento.md) · próximo: [M8 — Restaurante, distribuição e inauguração](roadmap-m8-restaurante-distribuicao.md)
>
> **Pré-requisito:** o gate do marco M6 (última etapa de `docs/roadmaps/roadmap-m6-operacao-fechamento.md`) está `#concluida`. **Atendido:** o gate E12 do M6 está `#concluida`, com a revisão humana aprovada pelo usuário em 2026-11-20 (H24 d do M6), e foi integrado na `main` em `93b3124` e `8450920`. A homologação da FNRH (M5 E09) segue `#bloqueada` pela Q-12, cobrada pelo gate de inauguração (M8 E13).

## Como usar

Inicie uma sessão limpa do Claude Code na raiz do repositório com `claude --agent orquestrador` e cole o "Prompt de execução". Ele executa até 3 etapas em sequência na branch `roadmap/m7-pagamentos-fiscal`, cada uma planejada pelo plugin superpowers e executada pelas souls (subagentes do Second Brain), e para para revisão. No fim do lote, o orquestrador integra a branch na `main` com merge local e deixa pronto o `git push origin main`, que fica com você. Para não parar em cada pedido de permissão, libere antes os comandos de build/teste do projeto nas permissões do Claude Code (os comandos oficiais estão na tabela do `CLAUDE.md`; o Docker é necessário para os testes de integração).

**Sincronizado com o código em 2026-11-21** (`main` em `8450920`, depois do gate do M6), pela skill `development-roadmap` no modo atualização (regra 2 de `docs/roadmaps/README.md`). O que o M6 deixou e foi conferido no código está em "Base técnica herdada do M6", em "Contexto confirmado". Se a `main` mudar antes do primeiro lote, confira de novo os itens citados.

## Objetivo

Integrar os meios de pagamento e a emissão fiscal ao servidor: cobrança Pix com confirmação automática e devolução, cartão integrado (TEF ou Smart POS) com pré-autorização, captura e cancelamento, caução no check-in, tokenização para garantia, conciliação, e emissão de NFS-e e NFC-e por middleware fiscal com IBS/CBS. Tudo atrás de portas neutras, com adaptadores falsos testados por WireMock primeiro; os adaptadores reais de cada fornecedor só são escritos depois que a SPEC Q-05 for decidida. Ao fim do marco, contas e check-out emitem nota e recebem pagamentos sem digitação.

## Contexto confirmado

- Requisitos: SPEC §5.11 (RF-PAG-01 a 06), §5.12 (RF-FIS-01 a 09), §5.10 (RF-CTA-06 a 08, RF-CTA-10 e RF-CTA-15: pagamentos, caução, caixa e reembolso).
- Modelo de domínio: SPEC §8.2.7 (`Folio`, `Payment`, `CashierShift`, `CardPreAuthorization` em contas) e §8.2.8 (`PaymentIntent`, `PixCharge`, `CardTransaction`, `CardToken`, `PaymentProvider`, `FiscalProfile`, `FiscalDocument`, `FiscalDocumentItem`, `TaxAmount`, `FiscalProvider`).
- Máquina de estados do documento fiscal: SPEC §8.4. Eventos `PaymentSucceeded` e `FiscalDocumentAuthorized`/`Rejected`: SPEC §8.3.
- Regras: RN-02 (arredondamento único validado com o middleware), RN-03 (imutabilidade financeira), RN-12 (ações sensíveis), RNF-02 (sem portas abertas com servidor local), RNF-10 (PCI-DSS).
- Revisão de arquitetura dos roadmaps: `docs/superpowers/specs/2026-11-06-revisao-arquitetura-roadmaps-design.md`.
- Arquitetura: módulos `payments` (depende de `shared` e `notifications`; o código ainda só de `shared`), `fiscal` (depende de `shared`, `hotel`, `files`, `notifications`), `billing` (depende de `payments` e `fiscal`), `frontdesk` (depende de `payments`, `fiscal`, `billing`) e `endofday` (depende de `payments`, `billing`) — ARCHITECTURE §3.1. Padrão de integração, tabela de integrações e conciliação de TEF: ARCHITECTURE §12. Jobs de polling e renovação de pré-autorizações: §13. Idempotência: §7. PCI e cifragem de credenciais: §8.3. XMLs fiscais no armazenamento de arquivos: §14. Regra por camadas da §3.1: qualquer módulo pode chamar os serviços de plataforma (`files`, `notifications`, `printing`, `hotel`) e depender de camadas inferiores sem aprovação, desde que o grafo siga acíclico e a aresta nova seja acrescentada à tabela §3.1 na mesma mudança. **No código (conferido em 2026-11-21)**, as arestas que o M7 usa ainda não estão nas `allowedDependencies`: `PaymentsModule` só tem `shared`; `BillingModule` não tem `fiscal`; `FrontdeskModule` não tem `payments` nem `fiscal`; `EndOfDayModule` não tem `payments`. Cada etapa que usar uma delas a acrescenta ao módulo e ao `ModularityTest`, que confere as arestas exatas.
- Convenções herdadas (ler nos handoffs e nas notas de tema do brain): M0 (API, erros, `Money`, IDs, `Idempotency-Key`, outbox, ShedLock, CI), M1 (autorização, step-up por PIN, auditoria, `hotel`, `files`), M2 (notificações), M3 (`billing` e `payments` com registro manual e `PaymentIntent` neutro), M5 (padrão de integração porta + adaptador + outbox + WireMock da FNRH, credenciais de serviço), M6 (etapas do fechamento do dia, trava da data operacional, entrada estrita, `409 CONCURRENT_UPDATE`, gate em `docker/gate/`).
- Caminhos (pacote base `com.zeny.hotel`, em `src/main/kotlin/com/zeny/hotel/`): `payments/` existe desde o M3, com `PaymentsApi` (`start`, `intent`), `PaymentSucceeded` em `PaymentsEvents.kt`, `application/` (`PaymentIntentService`, porta `PaymentProvider` em `PaymentProviders.kt`), `domain/PaymentIntent.kt` e `infrastructure/` (`ManualPaymentProvider`, `PaymentsPersistence`), sem `web/`; nenhum commit em `payments` desde o gate do M5. `fiscal/` (a criar). Migrações em `src/main/resources/db/migration/payments` (duas, de 2026-10-20) e na pasta `fiscal` (a criar).
- Comandos de build, teste e lint: os oficiais da tabela do `CLAUDE.md` (definidos em M0 E01).
- **Base técnica herdada do M6** (conferida no código em 2026-11-21, `main` em `8450920`):
  - **Fechamento do dia:** 11 etapas em `endofday/application/steps/`, nas posições `no-show` 100, `pending-departures` 200, `room-charges` 300, `block-release` 400, `provisional-expiry` 500, `card-pre-authorizations` 600, `occupied-rooms-dirty` 700, `daily-statistics` 800, `end-of-day-reports` 900, `end-of-day-pending` 950 e `advance-business-date` 1000 (ARCHITECTURE §13). A `CardPreAuthorizationsStep` (600) termina sempre `NOT_APPLICABLE` e o KDoc dela diz que a renovação do M7 entra ali, com `BillingApi.cardPreAuthorizationsExpiring(from, to)`. O KDoc do contrato de etapa (`EndOfDayStepHandler`, em `endofday/application/EndOfDaySteps.kt`) registra o espaçamento de 100 em 100 e a exceção da `end-of-day-pending` (950). Testes que rodam o fechamento com as etapas reais usam `@EndOfDayIntegrationTest` (banco próprio, H10 do M6), nunca o banco compartilhado.
  - **`ReportingApi`** (raiz do `reporting`, sem conferir permissão): `snapshotDailyStatistics(date)`, `archiveDayReports(date)` e `endOfDayPendingItems()`. A última devolve `EndOfDayPendingItem` com `EndOfDayPendingKind` (`FOLIO_OPEN_BALANCE`, `CASHIER_SHIFT_OPEN`, `FNRH_SUBMISSION_PENDING`), referência opaca (um id) e `detail` só com número, código e status; lê o mesmo que `GET /api/v1/reports/end-of-day-pending`, pelas views `report_*`. A etapa 950 transforma cada item numa `EndOfDayIssue`. As notas rejeitadas da RF-FEC-04 entram aqui quando o `fiscal` existir (E08). A aresta `endofday → reporting` fica com essas três operações (H24 a do M6, ARCHITECTURE §3.1): um tipo novo de pendência não amplia a aresta.
  - **`files`:** `FilesApi.store(purpose, ownerEntityId, name, content)` grava conteúdo gerado pelo servidor na transação de quem chama (cifra se a finalidade é sensível, apaga o objeto no rollback, trilha `files.file.store`, autor nulo fora de requisição). `FilePurpose.serverOnly` marca a finalidade que só o servidor grava: o `POST /api/v1/files` a recusa com `400 UNKNOWN_FILE_PURPOSE`. O tamanho de cada finalidade não passa de `zeny.files.max-upload-size` (20 MB). Precedente: `reporting.day-report` (PDF, 5 MB, sensível, `serverOnly`).
  - **Trava da data operacional (RF-FEC-05, M6 E11):** todo lançamento do `billing` (`BillingOperations.stamp`) e a abertura, os movimentos e o fechamento de turno de caixa chamam `HotelApi.lockBusinessDate()` (`FOR SHARE` até o commit), e o avanço da data espera por eles. Um pagamento registrado por evento (Pix confirmado de forma assíncrona, E02) também passa por essa trava.
  - **API (M6 E20 e E21, ARCHITECTURE §7):** entrada estrita no corpo, na query e no caminho (datas, instantes, `LocalTime`, inteiros e UUID) e `required` em todo campo exigido nos requests; `40P01` e `55P03` viram `409 CONCURRENT_UPDATE`, retentável, e liberam a `Idempotency-Key`.
  - **Gate em `docker/gate/`:** `run-gate.sh` (argumentos: workdir fora do repositório, porta, projeto opcional e o roteiro, `m5` ou `m6`; o `m7` é a acrescentar), `lib-roteiro.sh` comum aos roteiros (`check`/`redact`, varredura `leak_sweep`, revisão do log `log_review`), um `mN-roteiro.sh` por marco com a sua lista `WARN_ALLOWED`, e `docker/gate/README.md` com a tabela dos `WARN` esperados. O gate reprova com `ERROR` no log da `api` ou `WARN` fora da lista. Modelo do teste do marco: `MilestoneM6EndToEndTest` (raiz do pacote de testes, `@EndOfDayIntegrationTest`, operadores pelos modelos de role, FNRH em WireMock).
  - **Última migração:** `V2026_11_20_1002__create_archived_report.sql` (pasta `reporting`); a próxima precisa de versão posterior.
  - **Pendências do M6 com dono neste roadmap:** o `WARN` do `EndOfDayMetrics` no primeiro boot e os Menores dos roteiros do gate (E13, H8). Os advisories do `osv-scanner` anteriores ao M7 (0 Critical, 0 High; `commons-lang3` 3.16.0 no buildscript, `logback-core` 1.3.16 na configuração do `ktlint` e `opentelemetry-api` 1.62.0 no runtime) ficam com a M8 E17 (H8). O agrupamento das pendências da FNRH desligada fica com a M8 E19 (H24 c do M6).
  - **Gates nesta máquina:** de 21 a 31 min ociosa e até 54 min com outra sessão; `timeout 3600`, um Gradle ou `docker build` por vez e o resolvedor ajustado só no processo (`LOCALDOMAIN=" " RES_OPTIONS="timeout:1 attempts:1"`, `--no-daemon`; handoffs da M6 E23 e E12).
- Conhecimento do projeto no Second Brain: hub `ZenyHotelManager` e as notas de tema `ZenyHotelManager - Módulo billing (caixa, pagamentos, caução e encerramento)` (inclui o módulo `payments`: `PaymentIntent`, porta `PaymentProvider`, provedor `MANUAL`, `PaymentSucceeded`, caução manual, reembolso), `ZenyHotelManager - Módulo billing (contas, roteamento e taxa de serviço)` (lançamentos, códigos de lançamento, roteamento, taxa de serviço), `ZenyHotelManager - Hospedagem e recepção` (check-in e check-out no `frontdesk`), `ZenyHotelManager - Módulo guests` (documentos cifrados do tomador, empresas), `ZenyHotelManager - Módulo files` (XML e PDF, URL assinada), `ZenyHotelManager - Módulos hotel e staff` (data operacional, fuso, taxas), `ZenyHotelManager - Módulo notifications` (alertas de falha e de rejeição), `ZenyHotelManager - Segurança, autenticação e autorização` (permissões, step-up), `ZenyHotelManager - Auditoria`, `ZenyHotelManager - Eventos e jobs` (outbox, listeners, ShedLock), `ZenyHotelManager - Convenções de API` (idempotência, enum extensível, contrato), `ZenyHotelManager - Tipos do kernel` (`Money`, cifragem de campo), `ZenyHotelManager - Schemas, migrações e views de relatório`, `ZenyHotelManager - Arquitetura e estrutura de módulos`, `ZenyHotelManager - Ambiente, banco, chaves e build` (variáveis e segredos), `ZenyHotelManager - Docker, observabilidade e CI` (compose, métricas, alertas), `ZenyHotelManager - Testes de integração`, `ZenyHotelManager - Estado por marco`, `ZenyHotelManager - Módulo fnrh` (padrão de integração do M5), `ZenyHotelManager - Módulo endofday` (fechamento do dia) e `ZenyHotelManager - Módulo printing`. Desde 2026-11-21, as capturas do M6 ainda pendentes na Inbox (E01 a E23, fim de lote e "E12 (revisão humana)") são mais novas que essas notas e propõem a nota nova `ZenyHotelManager - Módulo reporting`: leia as que o hook listar.

## Escopo

**Incluído:** portas `PaymentProvider` e `FiscalProvider` com adaptadores falsos; Pix (cobrança, confirmação por webhook e por polling, devolução); cartão TEF reportado pelo cliente e Smart POS pela API do adquirente; pré-autorização, captura, cancelamento; tokenização para garantia; caução no check-in e renovação no fechamento; reembolso com aprovação; conciliação; perfis fiscais; ciclo de vida do documento fiscal com fila e reprocessamento; NFS-e da hospedagem no check-out; NFC-e de A&B como serviço para o PDV; relatórios e exportação de XMLs; adaptadores reais (bloqueados até Q-05); manutenção herdada do M6 (métricas do fechamento no primeiro boot e Menores dos roteiros do gate, H8).

**Fora de escopo:**

- PDV, comandas e chamada de NFC-e no fechamento de comanda: M8.
- Channel manager e cartão virtual de OTA: M8.
- Contas a receber e faturas (RF-CTA-13) e parcelas de evento: M10.
- Exportação contábil (RF-REL-08): M10. Aqui só a exportação de XMLs (RF-FIS-09).
- Biblioteca TEF nos clientes desktop/PDV: repositórios dos clientes (ARCHITECTURE §1); o servidor só cria o `PaymentIntent` e recebe o resultado.

## Restrições e invariantes

- **PCI-DSS (RNF-10):** o servidor nunca recebe nem grava número de cartão ou CVV. Só token, bandeira, 4 últimos dígitos, NSU e autorização. DTOs e logs rejeitam/mascaram qualquer campo parecido com PAN.
- **Imutabilidade (RN-03):** pagamentos, estornos e documentos fiscais não são editados nem excluídos; correções são novas linhas.
- **Grafo de módulos (ARCHITECTURE §3.1):** `payments` não chama `billing`, `frontdesk` nem `pos`; quem reage a pagamento escuta `PaymentSucceeded`. `fiscal` não chama `billing`; `billing` escuta `FiscalDocumentAuthorized`/`Rejected`. `notifications` nunca escuta eventos de `fiscal` nem de `payments`: os alertas de RF-NOT-01 (falha de integração, rejeição de nota) saem do módulo que detecta o problema (`fiscal`, `payments`, `billing` ou `endofday`), que chama `notifications` diretamente, com o tipo `notifications.integration-failure` (H2).
- **Idempotência:** POSTs com efeito financeiro ou externo exigem `Idempotency-Key` (ARCHITECTURE §7); confirmações recebidas por webhook ou polling são idempotentes pelo identificador do provedor (txid, NSU, chave de acesso).
- **Webhook e polling:** toda confirmação funciona nos dois modos, selecionados por configuração (RNF-02, Q-06). Nenhum fluxo depende só de webhook.
- **Resultado incerto (ARCHITECTURE §12):** um timeout de leitura numa escrita não diz se o provedor gravou. Dinheiro e nota nunca são reenviados às cegas: reenvio só com a mesma referência idempotente do provedor ou depois de conciliar pela consulta.
- **Segredos:** credenciais de provedores cifradas em campo (ARCHITECTURE §8.3) e fora do repositório; nunca na trilha, nem o usuário; removíveis por uma ação com trilha, como a M5 E16; ambiente (homologação ou produção) escolhido na configuração, com as URLs em propriedade e a credencial pedida de novo ao trocar.
- **Registro de integração:** log com payload redigido; tabela *append-only* sem texto livre do provedor, porque não admite expurgo; a mensagem saneada vai só para o log e para a exceção.
- **Saúde:** status por endpoint e métricas; a readiness não depende de provedor externo, sem `HealthIndicator` novo (ARCHITECTURE §16).
- **Contrato:** enum de resposta que cresce sai como `x-extensible-enum`; `pattern` válido em ECMA-262; sem `DELETE`. Biblioteca nova entra pelo catálogo, com os lockfiles regravados e sem High ou Critical novo no scanner.
- **Arredondamento:** só a função única de `Money` (M0 E03); nenhum módulo arredonda por conta própria.

## Hipóteses e decisões em aberto

| # | Hipótese ou decisão | Impacto se errada / sem decisão | Etapa afetada |
|---|---|---|---|
| H1 | M3 entregou `PaymentIntent` neutro, registro manual e evento `PaymentSucceeded` consumido por `billing`; `CardPreAuthorization` vive em `billing` (SPEC §8.2.7). **Reconfirmada em 2026-11-21:** nenhum commit em `payments` desde o gate do M5; o `PaymentSucceededListener` (`billing/application/PaymentService.kt`) continua falhando de propósito com intenção da conta confirmada sem pagamento, à espera do registro por evento do M7. | Se o modelo divergir, E01 adapta a porta ao que existe e registra no handoff. | E01 |
| H2 | Reconfirmada em 2026-11-21 (o padrão segue só em `fnrh`). Conferida em 2026-11-06 até a M5 E07: o padrão é porta e adaptador, classificação de falhas com resultado incerto, saneador, registro *append-only* sem texto livre, status por endpoint e `MeterBinder`, sem `HealthIndicator`. **Não existe** evento genérico de falha: o alerta é uma chamada direta a `notifications`. O envio com retry é da M5 E08. Tudo está em `fnrh`, sem nada em `shared`. | E01 decide, com a segunda integração à frente, o que extrair para `shared`, e registra no handoff. | E01, E06 |
| H3 | Webhook não tem padrão pronto: o M1 não criou credencial de serviço, e a cadeia própria da M5 E10 é só do agente de impressão. O recebimento usa a assinatura ou a credencial do provedor, num endpoint fora do access token de usuário. | E02 fixa o padrão, com revisão humana; E06 e M8 E08 o seguem. | E02, E06 |
| H4 | Pix confirmado de forma assíncrona é registrado no turno de caixa de quem criou a cobrança (RF-CTA-10). | Se o turno já tiver fechado, o pagamento vai para o turno aberto do mesmo ponto de caixa ou fica pendente de alocação; E02 registra a regra como decisão. | E02 |
| H5 | Decidido em 2026-10-13: `fiscal` depende de `files` para guardar XML e PDF (ARCHITECTURE §14); a aresta já consta na tabela §3.1. | Nenhum: deixou de ser hipótese. | E07 |
| H6 | Q-04 segue o padrão adotado na SPEC §10: NFC-e do consumo de A&B lançado no quarto emitida no check-out, agrupada; café incluso entra na NFS-e da hospedagem. | Mudar depois troca o momento de emissão; a API de E08 aceita os dois. | E07, E08 |
| H7 | Q-02/Q-03: regime tributário e regras municipais ficam em perfis fiscais configuráveis; o middleware resolve o layout. | Sem a decisão, a homologação real não fecha (E11). | E06, E11 |
| H8 | **Decidida pelo usuário em 2026-11-21 (entrevista do `arquiteto` na sincronização), pelas opções recomendadas:** (a) o `WARN` do `EndOfDayMetrics` no primeiro boot com banco novo e os Menores dos roteiros do gate deixados pela M6 E12 entram numa **etapa nova do M7, antes da E01** (**E13**, bloco "Manutenção herdada do M6"), para as métricas de `payments` e `fiscal` (E01, E06) já nascerem tratando "hotel ainda não criado" como "sem dado" e o `m7-roteiro.sh` do gate nascer sobre a `lib-roteiro.sh` corrigida; (b) os advisories do `osv-scanner` anteriores ao M7 (`commons-lang3` 3.16.0 no buildscript, `logback-core` 1.3.16 na configuração do `ktlint`) entram na **M8 E17**, que já é dona do `opentelemetry-api` 1.62.0, para as dependências ficarem numa etapa só. Alternativas descartadas: (a) distribuir o `WARN` na E04 e os Menores no gate E12, que depende da Q-05 sem data, ou adiar tudo ao M8; (b) corrigir as três dependências na E13 ou aceitar as duas de build como risco sem etapa. | (a) só muda a organização; (b) até a M8 E17, o scanner segue com 2 Medium e 3 Low, nenhum High ou Critical. | E13, M8 E17 |
| D1 | Q-05: qual PSP Pix. | Sem o fornecedor não há credenciais nem documentação para o adaptador real. | E09 (bloqueada) |
| D2 | Q-05 e Q-13: adquirente e modo de cartão (TEF com biblioteca no cliente ou Smart POS com API em nuvem). | Define qual fluxo de cartão vai para produção e o adaptador real. | E10 (bloqueada) |
| D3 | Q-05, Q-02 e Q-03: middleware fiscal, regime tributário, município (emissor nacional ou próprio, ISS, taxa de turismo). | Sem eles não há homologação de NFS-e/NFC-e nem validação do arredondamento (RN-02). | E11 (bloqueada) |

## Definição global de pronto

- [ ] Pix: cobrança, confirmação por webhook e por polling, e devolução funcionam ponta a ponta com o PSP escolhido em homologação, e o pagamento aparece na conta.
- [ ] Cartão: venda, pré-autorização, captura e cancelamento funcionam no modo escolhido (TEF ou Smart POS) em homologação, sem número de cartão no servidor.
- [ ] Caução: pré-autorização no check-in, captura total ou parcial no check-out, liberação da sobra e renovação no fechamento.
- [ ] Conciliação aponta divergências entre transações do provedor e pagamentos registrados.
- [ ] NFS-e da hospedagem emitida no check-out e NFC-e de A&B disponível para o PDV, com IBS/CBS, autorizadas pelo middleware escolhido em homologação.
- [ ] Rejeições e falhas de integração ficam na fila, são reprocessadas e geram alerta.
- [ ] Manutenção herdada do M6 (E13): o primeiro boot com banco novo não registra `WARN` das métricas do fechamento, e os Menores dos roteiros do gate estão corrigidos.
- [ ] Build, testes (incluindo WireMock e Testcontainers), verificação de módulos e verificação do contrato passam; `api/openapi.yaml` atualizado sem *breaking change*.

## Protocolo de execução

Vale para toda execução deste roadmap. O planejamento e a execução de cada etapa são feitos pelo plugin superpowers; este protocolo diz como encadear as etapas e manter o roadmap em dia.

**Papéis**

| Papel | Soul | Usa |
|---|---|---|
| Orquestrador | `orquestrador` (a sessão principal, iniciada com `claude --agent orquestrador`) | este protocolo; atualiza tags e handoffs; roda a verificação mecânica dos testes |
| Planejador | `orquestrador` | `superpowers:writing-plans` |
| Implementador | `desenvolvedor`, subagente por tarefa; tarefas independentes em ondas paralelas | `superpowers:subagent-driven-development`, prompt implementer |
| Testador | `testador`, subagente nas tarefas `Risco: alto`, nas lacunas da revisão e uma vez por etapa | testes, sem tocar em código de produção |
| Revisor de plano | `revisor-arquitetura`, subagente novo por etapa, modelo mais capaz | revisão do plano antes da execução |
| Revisor de tarefa | `revisor-seguranca`, subagente novo por tarefa | revisão da `subagent-driven-development` |
| Revisor final | `revisor-seguranca`, subagente novo, modelo mais capaz | revisão final da `subagent-driven-development` |
| Captura no brain | `brain-escriba` | publica a captura de atualização em `00 Inbox/` |

Souls: este protocolo exige `orquestrador`, `desenvolvedor`, `testador`, `revisor-seguranca` e `revisor-arquitetura`. Não há fallback: se a sessão não roda como `orquestrador` ou alguma dessas souls não está instalada, pare e diga ao usuário para configurar a máquina (skill `configurar-maquina`) ou iniciar com `claude --agent orquestrador`. O caminho inline (`superpowers:executing-plans`) não vale aqui.

**Branch**

- Toda execução acontece no worktree/branch `roadmap/m7-pagamentos-fiscal`, criado ou retomado com `superpowers:using-git-worktrees`. Nunca execute na branch principal (`main`).
- Se a branch já existir com trabalho não integrado, continue nela. Se o lote anterior já foi integrado, crie a branch de novo a partir da `main` atualizada.
- Este roadmap é atualizado dentro da branch: tags e handoffs viajam com o código.

**Por etapa**

1. **Escolha:** a primeira etapa, na ordem do documento, marcada `#pendente` e com todas as dependências `#concluida`. Etapas `#bloqueada` e as que dependem delas ficam para trás.
2. **Conferência:** leia a seção da etapa, os handoffs das dependências e o código que eles citam. Se o código divergiu do que a etapa supõe, pare e relate.
3. **Início:** troque a tag para `#em-andamento` (índice e título).
4. **Plano:** use `superpowers:writing-plans`. A spec é a seção desta etapa mais os documentos citados em "Contexto confirmado"; as "Restrições e invariantes" entram como Global Constraints. Salve em `docs/superpowers/plans/AAAA-MM-DD-m7-pagamentos-fiscal-eNN.md` (AAAA-MM-DD = a data do dia). Antes de executar, o plano passa pela revisão de arquitetura do `orquestrador` (`revisor-arquitetura`); as regras (rodadas, veredito, seção no plano) vivem só nele. A execução já está definida (abaixo): não pergunte ao usuário o método, apenas siga.
5. **Execução:** use `superpowers:subagent-driven-development` com esse plano, executado pelas souls conforme o ciclo por tarefa, a verificação mecânica dos testes e a revisão final da etapa definidos no `orquestrador` (as regras de encaixe vivem só nele). Não invoque `superpowers:finishing-a-development-branch` ao fim da etapa: ele roda uma vez, no fim do lote.
6. **Fechamento:** publique a captura de atualização do brain (bloco "Second Brain"). Marque `#concluida` (índice e título) só com a revisão final limpa e cada critério de aceite verificado com evidência. Diante de impedimento real, marque `#bloqueada` e preencha "Bloqueios" com causa, evidência, impacto e ação de desbloqueio. Preencha "Evidências e handoff". Rulings do ledger que mudam contrato, escopo, segurança, dados ou algo caro de desfazer vão para "Decisões para o usuário".

**Second Brain**

Vale se o `CLAUDE.md` tiver a seção "Conhecimento do projeto no Second Brain"; senão, ignore este bloco.

- O contexto da sessão precisa trazer a linha `BRAIN: carregado (ZenyHotelManager)`, injetada pelo hook. Sem ela, pare e relate.
- Na conferência de cada etapa, leia as notas de tema do brain ligadas a ela (pelo hub `ZenyHotelManager`) e as capturas pendentes do projeto que tratem do assunto: são a referência técnica do projeto. Num handoff antigo, "seção X do `CLAUDE.md`" é a nota de tema correspondente.
- No fechamento de cada etapa que mudou algo descrito no brain (inclusive o estado do roadmap), o orquestrador manda o `brain-escriba` publicar em `00 Inbox/` do vault a captura `ZenyHotelManager - atualização m7-pagamentos-fiscal Eyy`, com o texto novo ou alterado de cada nota afetada e qual nota ele substitui ou complementa; o contrato de agente externo do vault fica com o `brain-escriba`. Nunca edite notas do brain nem registre referência técnica no `CLAUDE.md`.

**Paradas**

Qualquer outra necessidade de intervenção é decidida pelo orquestrador pela opção recomendada, depois de avaliar prós e contras, com a ruling registrada; só decisão de arquitetura ou system design espera o usuário (regras em "Decisões" do `orquestrador`). O lote para ao concluir uma etapa com **Revisão humana: sim**, diante de decisão de arquitetura sem a qual nada mais anda, nas paradas que ficam com o usuário (push, deploy, publicação, instalação na máquina, apagar trabalho fora da etapa, segredo exposto), quando não resta etapa elegível e ao atingir o limite de etapas do prompt. Etapa que fica `#bloqueada` não para o lote: o orquestrador segue para a próxima elegível que não depende dela.

**Fim do lote**

1. Use `superpowers:finishing-a-development-branch` com a integração já decidida: merge local na `main`, conforme o passo de fechamento do `orquestrador` (branch atualizada e com gate verde, `merge --no-ff`, repositório limpo e pronto para o próximo orquestrador). Push e deploy ficam com o usuário: o relatório termina com o comando `git push origin main` pronto.
2. Liste as "Decisões para o usuário" acumuladas, que já foram tomadas pela opção recomendada, com prós e contras, para revisão. As de arquitetura que esperam o usuário vão como entrevista: perguntas de múltipla escolha, cada uma com uma opção recomendada e justificada (no Claude Code, `AskUserQuestion` com a recomendada em primeiro lugar e o rótulo "(Recomendado)"). Registre as respostas no handoff das etapas e em "Hipóteses e decisões em aberto".

## Prompt de execução

```text
Execute o roadmap docs/roadmaps/roadmap-m7-pagamentos-fiscal.md deste repositório seguindo a seção "Protocolo de execução" dele, com o plugin superpowers.

Limite desta execução: no máximo 3 etapas concluídas.

1. Confira a pré-condição do protocolo: esta sessão roda como orquestrador e as souls desenvolvedor, testador, revisor-seguranca e revisor-arquitetura estão disponíveis; se não, pare e diga como resolver. Leia CLAUDE.md (e AGENTS.md, se existir) e, no roadmap, o cabeçalho inteiro, o protocolo e o índice. Se o CLAUDE.md exigir o Second Brain, confira a linha BRAIN: carregado no contexto e leia o hub. Rode git status e git branch -a.
2. Se alguma etapa estiver #em-andamento, retome-a a partir do ledger do superpowers e do handoff; não recomece tarefas já commitadas.
3. Crie ou retome o worktree/branch roadmap/m7-pagamentos-fiscal com superpowers:using-git-worktrees. Não trabalhe na branch principal.
4. Repita, uma etapa por vez: escolha a próxima etapa elegível, marque #em-andamento, planeje com superpowers:writing-plans (spec = seção da etapa + documentos citados), execute com superpowers:subagent-driven-development, feche a etapa (com a captura de atualização do brain) e preencha o handoff conforme o protocolo.
5. Pare nas paradas do protocolo ou ao atingir o limite.
6. No fim, rode superpowers:finishing-a-development-branch com merge local na main, conforme o fechamento do orquestrador: tudo commitado, pronto para git push origin main e o repositório pronto para o próximo orquestrador. Depois, liste as decisões tomadas e apresente como entrevista com opção recomendada só as de arquitetura que esperam por mim.

Qualquer necessidade de intervenção: escolha a opção recomendada depois de uma avaliação crítica dos prós e contras e registre a ruling; intervenção humana só para decisão de arquitetura ou system design. Nunca faça push ou deploy. Não altere tags de etapas que não executou.

Ao parar, responda com: etapas concluídas e o resultado da revisão final de cada uma, a etapa atual e seu estado, o motivo da parada, as etapas #bloqueada com a ação de desbloqueio, a branch e os commits do lote, as capturas do brain publicadas e o próximo passo sugerido.
```

## Índice de etapas

**Manutenção herdada do M6**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E13 | Métricas do fechamento no primeiro boot e Menores dos roteiros do gate | #pendente | — | Boot com banco novo sem `WARN` das métricas e roteiros do gate sem os Menores da M6 E12 |

**Pagamentos**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E01 | Porta de pagamentos e provedor falso | #pendente | — | `PaymentProvider` neutro, ciclo de vida do `PaymentIntent` integrado e adaptador falso testado por WireMock |
| E02 | Pix: cobrança, confirmação e devolução | #pendente | E01 | Cobrança com QR Code, confirmação por webhook e polling, devolução com aprovação |
| E03 | Cartão integrado e tokenização | #pendente | E01 | Fluxos TEF e Smart POS, pré-autorização, captura, cancelamento e token de garantia sem PAN |
| E04 | Caução, renovação e reembolso | #pendente | E02, E03 | Caução no check-in, captura no check-out, renovação no fechamento, reembolso com aprovação |
| E05 | Conciliação de pagamentos | #pendente | E02, E03 | Transações do provedor × pagamentos, com divergências destacadas |

**Fiscal**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E06 | Núcleo fiscal: perfis, documento e fila de emissão | #pendente | E02 | `FiscalProfile`, `FiscalDocument` com máquina de estados, `FiscalProvider` falso, fila com reprocessamento |
| E07 | NFS-e da hospedagem no check-out | #pendente | E06 | Emissão por conta ou seleção de lançamentos, IBS/CBS, XML e PDF guardados, vínculo na conta |
| E08 | NFC-e de A&B, relatórios e exportação de XMLs | #pendente | E07 | Serviço de NFC-e para o PDV, NFC-e agrupada no check-out, relatórios e exportação |

**Fornecedores reais**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E09 | Adaptador real do PSP Pix | #bloqueada | E02 | Adaptador do PSP escolhido homologado |
| E10 | Adaptador real do adquirente | #bloqueada | E03 | Adaptador TEF ou Smart POS do adquirente escolhido homologado |
| E11 | Adaptador real do middleware fiscal | #bloqueada | E08 | NFS-e e NFC-e homologadas no middleware escolhido |

**Encerramento**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E12 | Gate do marco M7 | #pendente | E04, E05, E09, E10, E11, E13 | Definição global de pronto verificada ponta a ponta em homologação |

---

## E13 — Métricas do fechamento no primeiro boot e Menores dos roteiros do gate — #pendente

**Objetivo:** o primeiro boot com banco novo deixa de registrar o `WARN` das métricas do fechamento do dia, e os roteiros do gate em `docker/gate/` deixam de ter os Menores apontados na M6 E12 e herdados do M5.

**Por que agora:** decisão do usuário H8 a. As métricas de integração de `payments` (E01) e `fiscal` (E06) seguem o mesmo padrão de leitura agendada e devem nascer tratando "hotel ainda não criado" como "sem dado"; o gate do M7 (E12) escreve o `m7-roteiro.sh` sobre a `lib-roteiro.sh`, que precisa estar corrigida antes.

**Dependências:** nenhuma dentro deste roadmap; exige o gate do M6 concluído (atendido).

**Escopo:**

- `endofday`: o `refresh` agendado do `EndOfDayMetrics` roda antes de o hotel existir e cai em `IllegalStateException`, com um `WARN` e os gauges `NaN` por até 30 s (`zeny.endofday.metrics.refresh-interval`). O hotel ainda não criado passa a ser "sem dado", sem `WARN`; qualquer outra falha da leitura continua `WARN`, só com a classe da exceção. O como (atraso inicial ou consulta explícita) é decisão do plano, registrada no handoff como o padrão das métricas de integração do M7.
- `docker/gate/`: a entrada do `EndOfDayMetrics` sai da `WARN_ALLOWED` do `m5-roteiro.sh` e do `m6-roteiro.sh` e da tabela "WARN e ERROR esperados" do `docker/gate/README.md`.
- Menores dos roteiros (handoffs da M6 E12 e da M5 E12):
  - a varredura procura os bytes crus e não acharia um valor não-ASCII escapado pelo encoder do log JSON (como `Rua das Acácias` com `á`);
  - o `m6-roteiro.sh` tem um laço de espera próprio, fora da `lib-roteiro.sh`, e um `RES_ID` global;
  - as linhas do log da `api` fora do JSON só são contadas, sem conferência;
  - o `gate.env` é sobrescrito quando duas execuções usam o mesmo workdir;
  - o mock da FNRH monta a resposta por templating sem escape JSON;
  - o `@tsv` dobra as barras (Menor da M6 E12).

**Fora de escopo:** o `WARN` do PDFBox (fonte substituta), que continua esperado; o `m7-roteiro.sh` e o `run-gate.sh … m7` (E12); os Menores de teste da M6 E13 (filtros por `step_name`, asserções do `EndOfDayReportingIdentityTest` e do `EndOfDayPreE13VariantsTest`, `readTimeout` do `SerproFnrhGatewayAttackTest`), que ficam para quem tocar nesses testes; as dependências (M8 E17, H8 b).

**Critérios de aceite:**

- [ ] Com banco novo, o log da `api` do boot até o hotel existir não tem `WARN` do `EndOfDayMetrics`, e os gauges ficam `NaN` até a primeira leitura válida (teste).
- [ ] Uma falha real da leitura (por exemplo, o banco indisponível) continua dando `WARN` com a classe da exceção e sem mensagem do banco (teste).
- [ ] A `WARN_ALLOWED` dos dois roteiros e a tabela do `docker/gate/README.md` não têm mais a entrada do `EndOfDayMetrics`, e os roteiros do M5 e do M6 rodam num banco novo com 0 falhas, 0 `ERROR`, nenhum `WARN` fora da lista e sobras 0/0/0.
- [ ] A varredura acusa um valor registrado como `secret` também na forma escapada pelo encoder JSON; a prova (um valor não-ASCII semeado de propósito e acusado) fica no handoff.
- [ ] O `m6-roteiro.sh` usa a espera da `lib-roteiro.sh` e não tem mais `RES_ID` global.
- [ ] As linhas fora do JSON no log da `api` passam pela revisão do log e pela varredura, não só pela contagem.
- [ ] Duas execuções do `run-gate.sh` no mesmo workdir não sobrescrevem o `gate.env` uma da outra (recusa ou separação, registrada no handoff).
- [ ] O mock da FNRH escapa em JSON os valores que copia do pedido, e o `@tsv` não dobra as barras.
- [ ] Build oficial passa.

**Validação obrigatória:**

- `./gradlew build` (tabela do `CLAUDE.md`) → sucesso.
- `docker/gate/run-gate.sh` com o roteiro `m5` e depois com o `m6` (workdir fora do repositório, um projeto por execução) → 0 falhas, 0 `ERROR`, nenhum `WARN` fora da lista, sobras 0/0/0.

**Revisão humana:** não

**Notas para o plano:**

- Leia o handoff da M6 E12 ("Decisões para o usuário", item do `EndOfDayMetrics`, e "Menores para o handoff"), o da M5 E12 (Menores do gate) e o `docker/gate/README.md`. O `WARN` aparece nos dois roteiros: o README conta 1 por gate.
- Os testes do componente são o `EndOfDayMetricsTest` e o `EndOfDayMetricsAttackTest`. A primeira leitura antes do hotel não se reproduz no banco compartilhado dos testes, onde o hotel já existe: o plano escolhe como provar o boot com banco novo (banco próprio ou o compose do gate) e registra.
- Os roteiros são scripts do gate, sem teste Gradle: a prova é rodar os dois gates. Os gates nesta máquina levam de 21 a 54 min (ver "Base técnica herdada do M6").

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Esconder uma falha real junto com a do boot: só "hotel ainda não criado" vira "sem dado"; as demais falhas continuam `WARN`, com teste.
- Uma varredura mais larga acusar falso positivo nos gates do M5 e do M6: rodar os dois e registrar no handoff.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E01 — Porta de pagamentos e provedor falso — #pendente

**Objetivo:** estender o módulo `payments` de M3 com a porta neutra `PaymentProvider`, o ciclo de vida completo do `PaymentIntent` integrado e um adaptador falso que simula um provedor, testado com WireMock, reaproveitando o padrão de integração de M5.

**Por que agora:** Pix, cartão, caução e conciliação usam a mesma porta e o mesmo ciclo de vida. Fixá-los primeiro evita que cada meio invente o seu.

**Dependências:** nenhuma dentro deste roadmap; exige o gate de M6 concluído. Independente de E06 (podem rodar em branches separadas); sem conflito de arquivos de domínio, conflito possível em `api/openapi.yaml`.

**Escopo:**

- `payments/` (existente desde M3; arquivos novos a criar): porta `PaymentProvider` (criar cobrança Pix, consultar status, devolver, iniciar operação de cartão em terminal remoto, consultar transações para conciliação), seleção do provedor por configuração, credenciais cifradas.
- `PaymentIntent`: estados (criado, pendente, sucesso, falha, expirado, reembolsado parcial/total), expiração, `markSucceeded`/`markFailed`/`refund` (SPEC §8.2.8), idempotência por identificador do provedor; publica `PaymentSucceeded` (já consumido por `billing` desde M3) e eventos de falha/reembolso.
- Infraestrutura de integração (padrão de M5, H2): pedido idempotente e envio por job, retry com backoff, classificação de falhas com resultado incerto, log de integração redigido, status e métricas, e alerta direto por `notifications`.
- Adaptador falso com cliente HTTP real apontado para WireMock nos testes.
- Migração na pasta `payments`.

**Fora de escopo:** fluxos Pix e cartão específicos (E02, E03); adaptadores reais (E09, E10).

**Critérios de aceite:**

- [ ] Testes de domínio cobrem todas as transições do `PaymentIntent`, incluindo transição inválida rejeitada com `code` estável e expiração.
- [ ] Uma confirmação repetida com o mesmo identificador do provedor não muda o estado nem publica um segundo `PaymentSucceeded` (teste).
- [ ] O adaptador falso, testado com WireMock, cobre sucesso, falha temporária com retry e backoff, e erro permanente que gera a notificação `notifications.integration-failure` a quem tem a permissão de gerir pagamentos.
- [ ] Timeout de leitura numa escrita vira resultado incerto: nada é confirmado nem reenviado sem conciliar pela consulta ou repetir a mesma referência (teste).
- [ ] Credenciais do provedor ficam cifradas no banco, fora da trilha e removíveis por uma ação com trilha; o log de integração não contém credenciais nem dados pessoais (teste).
- [ ] Status e métricas da integração de pagamentos seguem o padrão do M5; a readiness não depende do provedor (`ActuatorExposureTest` intacto).
- [ ] O desenho da porta consta numa matriz em `docs/`, que cruza cada operação com a documentação pública de ao menos um PSP Pix e um adquirente.
- [ ] `ApplicationModules.verify()` passa com `payments` dependendo só de `shared` e `notifications`, como na tabela da ARCHITECTURE §3.1.
- [ ] Build, testes e verificação do contrato oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste (tabela do `CLAUDE.md`) → sucesso.
- Verificação do contrato `api/openapi.yaml` → sem diff pendente e sem *breaking change*.

**Revisão humana:** não

**Notas para o plano:**

- Leia o handoff da M3 E08 (`docs/roadmaps/roadmap-m3-hospedes-contas.md`, "Notas para o marco M7"): a porta `PaymentProvider`, o `PaymentProviderCode` e o provedor `MANUAL` já existem no `payments` e o `PaymentIntentService.start` já trata `ProviderOutcome.Pending` e `Failed`; falta o caminho de confirmação posterior (caso de uso que trava a intenção, `markSucceeded`/`markFailed` e publica o evento) e o `PaymentSucceededListener` do `billing` hoje **falha** com intenção sem pagamento (H1).
- H2: o padrão do M5 está em `fnrh`; leia os handoffs da M5 E07 e E08. Extrair para `shared` é decisão desta etapa, com registro.
- A aresta `payments` → `notifications` já está na tabela da ARCHITECTURE §3.1 e entra no `PaymentsModule` e no `ModularityTest` nesta etapa.
- As métricas da integração seguem o padrão que a E13 registrar no handoff para a leitura agendada antes de o hotel existir (sem `WARN` no primeiro boot); se a E13 ainda não tiver rodado, siga o critério dela.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Porta desenhada para um único provedor: validar contra dois provedores conhecidos (documentação pública de um PSP e de um adquirente) na investigação.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E02 — Pix: cobrança, confirmação e devolução — #pendente

**Objetivo:** permitir cobrar por Pix (QR Code e copia-e-cola) para conta, sinal de reserva ou outro alvo, confirmar o pagamento automaticamente por webhook ou por consulta periódica e devolver valores com aprovação.

**Por que agora:** Pix é o meio integrado mais usado e o mais simples de homologar; valida a porta de E01 ponta a ponta.

**Dependências:** E01. Independente de E03 (podem rodar em branches separadas); conflito de edição na configuração do provedor em `payments` e em `api/openapi.yaml`.

**Escopo:**

- Endpoints em `/api/v1` para criar cobrança Pix (com `Idempotency-Key`), consultar status e solicitar devolução (ação sensível com step-up, RF-CTA-15).
- `PixCharge` (txid, copia-e-cola, vencimento, endToEndId) e cobrança com vencimento (`cobv`) para sinal de reserva.
- Endpoint de webhook do PSP (H3), idempotente pelo txid/endToEndId; job de polling com ShedLock (ARCHITECTURE §13) para modo sem webhook, selecionado por configuração.
- Turno de caixa da confirmação assíncrona (H4); integração com `billing` só via `PaymentSucceeded` (o alvo segue no evento).
- Expiração de cobranças não pagas.

**Fora de escopo:** caução via Pix (E04); adaptador real (E09); cobrança de comanda (M8, usa este serviço).

**Critérios de aceite:**

- [ ] `POST` de cobrança Pix exige `Idempotency-Key` e devolve txid, copia-e-cola e vencimento.
- [ ] Confirmação por webhook registra exatamente um pagamento na conta-alvo (via `PaymentSucceeded`), no turno de caixa definido pela regra registrada (H4).
- [ ] Com o modo polling ativo e o webhook desligado, o job confirma a cobrança paga no provedor falso (teste).
- [ ] Webhook e polling simultâneos para a mesma cobrança geram um único pagamento (teste concorrente).
- [ ] Webhook sem autenticação/assinatura válida → `401` ou `403`, sem efeito.
- [ ] Cobrança vencida passa a expirada e não aceita confirmação posterior sem registro de divergência.
- [ ] Devolução sem aprovação → `403 APPROVAL_REQUIRED`; com aprovação, gera reembolso auditado; valor acima do pago → erro com `code` estável.
- [ ] O endpoint de webhook consta em `SecurityConfiguration` e no `ApiSecurityTest` como exceção explícita, sem abrir outro caminho (teste).
- [ ] Build, testes e verificação do contrato oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Verificação do contrato → sem diff pendente e sem *breaking change*.

**Revisão humana:** sim

**Notas para o plano:**

- A revisão humana vem do webhook: é o primeiro endpoint chamado por terceiro, e o `fiscal` (E06) e o `distribution` (M8 E08) repetem o padrão.
- H3 (autenticação do webhook, sem padrão pronto) e H4 (turno de caixa da confirmação assíncrona): registre a regra do turno como decisão no handoff, inclusive o caso de o turno já ter fechado antes da confirmação (pendência da H13 do M3, citada nas "Notas para o marco M7" do handoff da M3 E08).
- O handoff da M3 E08 descreve o encaixe esperado no `billing`: o recebimento integrado aceita a intenção `PENDING` e guarda o pedido (conta, forma, turno, depósito) até a confirmação, e o listener registra o `Payment` com `INSERT … ON CONFLICT (payment_intent_id) DO NOTHING`; a devolução integrada passa por um `PaymentsApi.refund` do provedor da intenção original, e o reembolso manual continua para o `MANUAL`.
- Modele txid, endToEndId e estados pelo padrão da API Pix do Banco Central (cob, cobv, webhook, devolução).
- Trava da data (RF-FEC-05, M6 E11): o registro do pagamento no `billing` carimba a data operacional por `HotelApi.lockBusinessDate()`, e os turnos de caixa também a travam. O pagamento, a devolução e a caução também carimbam por ela (KDoc de `BillingOperations.stamp`): o avanço da data espera a confirmação em curso, e nenhuma cai numa data já fechada. A regra do turno (H4) precisa valer também quando a confirmação chega no meio do fechamento. Teste a confirmação concorrente ao avanço no caminho `@EndOfDayIntegrationTest`, nunca no banco compartilhado.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Confirmação tardia de cobrança expirada: registrar como divergência para a conciliação (E05) em vez de descartar.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E03 — Cartão integrado e tokenização — #pendente

**Objetivo:** suportar cartão integrado nos dois modos da ARCHITECTURE §12 — TEF, em que o cliente executa a transação e reporta o resultado, e Smart POS, em que o servidor envia o pedido ao terminal pela API do adquirente — com venda, pré-autorização, captura e cancelamento, e tokenização de cartão para garantia de reserva, sem que o servidor jamais receba número de cartão ou CVV.

**Por que agora:** a caução (E04) e o PDV (M8) dependem desses fluxos. A decisão TEF × Smart POS ainda está aberta (D2), então os dois modos ficam prontos atrás da porta.

**Dependências:** E01. Independente de E02 (podem rodar em branches separadas); conflito de edição na configuração do provedor em `payments` e em `api/openapi.yaml`.

**Escopo:**

- `CardTransaction` (terminal, tipo, NSU, autorização, bandeira, 4 últimos) e `CardToken` (token do provedor, bandeira, 4 últimos, titular, validade).
- TEF: endpoint para criar o `PaymentIntent` de cartão e endpoint para o cliente reportar o resultado (idempotente por NSU + terminal), marcado como "não verificado" até a conciliação (ARCHITECTURE §12).
- Smart POS: envio do pedido ao terminal via porta, resultado por webhook ou polling.
- Operações: venda, pré-autorização, captura total ou parcial, cancelamento (ação sensível com step-up).
- Tokenização (RF-PAG-04): endpoint que recebe só o token gerado pelo componente do provedor e o associa a um titular/reserva.
- Guardas PCI: validação que rejeita qualquer campo com sequência que pareça PAN (Luhn, 13–19 dígitos) nos DTOs de pagamento, e máscara no log.

**Fora de escopo:** caução e renovação (E04); biblioteca TEF nos clientes; adaptador real (E10).

**Critérios de aceite:**

- [ ] TEF: o cliente reporta o resultado de um `PaymentIntent` de cartão; o reporte repetido com o mesmo NSU + terminal não gera segundo pagamento; o pagamento fica marcado como "não verificado" até a conciliação.
- [ ] Smart POS: pedido enviado ao terminal pelo adaptador falso, com resultado recebido por webhook e, em outro teste, por polling.
- [ ] Pré-autorização seguida de captura parcial registra o valor capturado e libera a sobra; captura acima do pré-autorizado → erro com `code` estável.
- [ ] Cancelamento sem aprovação → `403 APPROVAL_REQUIRED`; com aprovação, auditado com solicitante e aprovador.
- [ ] Tokenização grava só token, bandeira, 4 últimos, titular e validade.
- [ ] Qualquer DTO de pagamento com campo que passe no Luhn com 13–19 dígitos é rejeitado com `code` estável e nada é gravado nem logado em claro (teste).
- [ ] Build, testes e verificação do contrato oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Verificação do contrato → sem diff pendente e sem *breaking change*.
- Inspeção: busca por colunas `pan`, `card_number` ou `cvv` nas migrações → nenhuma.

**Revisão humana:** não

**Notas para o plano:**

- D2 em aberto: os dois modos (TEF e Smart POS) ficam prontos atrás da porta; modele o reporte de NSU, autorização e bandeira pelo que integradores TEF conhecidos (SiTef, PayGo) e Smart POS devolvem.
- O M3 já recusa NSU e autorização com mais de 12 dígitos e só guarda bandeira e 4 últimos dígitos (regras de cartão do pagamento manual, nota de tema `ZenyHotelManager - Módulo billing (caixa, pagamentos, caução e encerramento)`); a guarda por Luhn desta etapa deve ser coerente com essas regras, sem afrouxá-las.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- TEF reportado pelo cliente não é verificável pelo servidor: marcação "não verificado" e conciliação (E05) cobrem o risco, conforme ARCHITECTURE §12.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E04 — Caução, renovação e reembolso — #pendente

**Objetivo:** implementar a caução da RF-CTA-08: pré-autorização de cartão (ou cobrança Pix) no check-in, captura total ou parcial no check-out com liberação da sobra, renovação de pré-autorizações perto do vencimento como etapa do fechamento do dia, e reembolsos com aprovação (RF-CTA-15).

**Por que agora:** depende dos fluxos de Pix (E02) e cartão (E03) e é o que liga pagamentos integrados à recepção.

**Dependências:** E02, E03. Independente de E05 (podem rodar em branches separadas); conflito de edição em `api/openapi.yaml`.

**Escopo:**

- `billing`: `CardPreAuthorization` (existente ou a criar conforme handoff de M3) com validade, valor, captura e liberação; caução via Pix como cobrança seguida de devolução.
- `frontdesk`: check-in passa a oferecer caução integrada; check-out captura o necessário e libera a sobra (`frontdesk` → `payments`, `billing`, permitido pelo grafo).
- `endofday`: nova etapa idempotente que renova pré-autorizações perto do vencimento (ARCHITECTURE §13), com alerta em falha.
- Reembolso de pagamento (devolução Pix ou estorno de cartão) a partir da conta, com step-up e auditoria.

**Fora de escopo:** contas a receber (M10); PDV (M8).

**Critérios de aceite:**

- [ ] Check-in com caução por cartão cria pré-autorização vinculada à conta da reserva (teste de módulo).
- [ ] Caução por Pix gera cobrança e, no check-out, devolução do excedente.
- [ ] Check-out captura só o saldo devido (limitado ao pré-autorizado) e libera a sobra; o saldo da conta fica zero (RN-06).
- [ ] A etapa de renovação do fechamento renova pré-autorizações que vencem antes da próxima data configurada e, retomada após falha, não renova de novo as já renovadas (teste).
- [ ] Falha na renovação gera notificação via `notifications` (RF-NOT-01).
- [ ] Reembolso sem aprovação → `403 APPROVAL_REQUIRED`; com aprovação, auditado e refletido na conta como nova linha (RN-03).
- [ ] `ApplicationModules.verify()` passa; toda dependência nova segue a regra por camadas e está na tabela da ARCHITECTURE §3.1.
- [ ] Build, testes e verificação do contrato oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Verificação do contrato → sem diff pendente e sem *breaking change*.

**Revisão humana:** não

**Notas para o plano:**

- Leia os handoffs da M3 E09 (caução manual: `CardPreAuthorization` já existe no `billing`, com a expiração calculada na leitura, sem job, validade máxima de 90 dias e `BillingApi.cardPreAuthorizationsExpiring` feita para o fechamento do dia e a renovação do M7), do M5 (check-in, que já registra a caução manual pela `BillingApi.registerCardPreAuthorization`, e check-out no `frontdesk`) e do M6 (como uma etapa do fechamento do dia é definida e tornada idempotente).
- Os testes de módulo seguem o `Scenario` do Spring Modulith, como nos marcos anteriores.
- **Renovação no fechamento (M6):** a etapa já existe, `CardPreAuthorizationsStep` (`card-pre-authorizations`, posição 600, nome publicado que nunca muda), e hoje termina sempre `NOT_APPLICABLE`; a renovação entra nela, sem etapa nova nem posição nova. Ela age sobre o hotel inteiro: os testes que a rodam com as etapas reais usam `@EndOfDayIntegrationTest` (H10 do M6), e os que afirmam pendências filtram pelas próprias referências ou pelo `step_name`, porque a etapa 950 levanta as pendências que outros testes deixam no banco isolado (handoff da M6 E13). A aresta `endofday → payments` está na ARCHITECTURE §3.1, mas não no `EndOfDayModule`: se a renovação passar pelo `payments`, entra no módulo e no `ModularityTest` nesta etapa. O KDoc do `EndOfDayModule` já justifica `billing` pelas pré-autorizações.
- Falha de renovação: decida no plano se vira `EndOfDayIssue` da etapa 600, notificação ou as duas, sem parar o fechamento por uma pré-autorização (a etapa que lança exceção deixa a execução `FAILED` e a data parada).
- Check-in e check-out: `frontdesk` ainda não depende de `payments` (só da `BillingApi`); a aresta está na §3.1 e entra no `FrontdeskModule` e no `ModularityTest` se a etapa a usar.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Prazo de validade de pré-autorização varia por adquirente: tornar a antecedência de renovação configurável e revisar em E10.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E05 — Conciliação de pagamentos — #pendente

**Objetivo:** confrontar as transações informadas pelo provedor com os pagamentos registrados (RF-PAG-05) e destacar divergências: pagamento sem transação, transação sem pagamento, valor diferente e TEF "não verificado" sem correspondência.

**Por que agora:** é o controle que cobre o risco do TEF reportado pelo cliente (ARCHITECTURE §12) e de confirmações tardias de Pix (E02).

**Dependências:** E02, E03. Independente de E04 (podem rodar em branches separadas); conflito de edição em `api/openapi.yaml`.

**Escopo:**

- `payments`: consulta de transações do provedor por período via porta; importação manual de extrato (CSV) como alternativa quando o provedor não oferecer API.
- Execução de conciliação por período e provedor, com resultado persistido e divergências classificadas; marcação de TEF como verificado quando conciliado.
- Endpoint de consulta e de resolução manual de divergência (com motivo, auditada).
- Job diário opcional com ShedLock e alerta quando houver divergência.

**Fora de escopo:** conciliação bancária e financeiro administrativo (SPEC Q-01, fora da v1).

**Critérios de aceite:**

- [ ] Testes cobrem os quatro tipos de divergência com dados falsos.
- [ ] Pagamento TEF com transação correspondente passa a "verificado".
- [ ] A importação de CSV produz o mesmo resultado que a consulta pela porta para o mesmo conjunto de transações (teste).
- [ ] Transações perto da meia-noite são comparadas no fuso do hotel, sem falso positivo (teste).
- [ ] Resolução manual exige motivo, é auditada e não altera o pagamento original.
- [ ] Build, testes e verificação do contrato oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Verificação do contrato → sem diff pendente e sem *breaking change*.

**Revisão humana:** não

**Notas para o plano:**

- A marcação "não verificado" do TEF vem de E03 e a confirmação tardia de cobrança Pix expirada fica registrada como divergência em E02: leia os handoffs das duas.
- O handoff da M3 E08 ("Notas para o marco M7") indica a chave da comparação: as intenções do provedor contra `billing.payment` pelo `payment_intent_id` e pela referência do provedor (`payments.payment_intent.provider_reference`); o TEF reportado pelo cliente entra como um provedor cuja confirmação vem pela API.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Formato do extrato depende do fornecedor: importação de CSV com mapeamento configurável, revisada em E09 e E10.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E06 — Núcleo fiscal: perfis, documento e fila de emissão — #pendente

**Objetivo:** criar o módulo `fiscal` com perfis fiscais (RF-FIS-03), o agregado `FiscalDocument` com a máquina de estados da SPEC §8.4, a porta neutra `FiscalProvider` com adaptador falso testado por WireMock, a fila de emissão com reprocessamento (RF-FIS-08) e status por webhook ou polling.

**Por que agora:** NFS-e (E07) e NFC-e (E08) usam o mesmo núcleo; o contrato fiscal precisa ser revisado antes de se espalhar por contas, recepção e PDV.

**Dependências:** E02, que fixa o padrão de webhook e, com a E01, o de integração. Conflito possível só em `api/openapi.yaml` e na configuração de integrações.

**Escopo:**

- Módulo `fiscal/` (a criar, depende de `shared`, `hotel` e `files`; `notifications` para alertas), schema e migrações próprios.
- `FiscalProfile` (código de serviço municipal/NBS, NCM, CFOP, ISS, CST e cClassTrib de IBS/CBS, regime específico) com CRUD protegido por permissão; vínculo por código de lançamento e por produto de venda (referência por ID).
- `FiscalDocument`, `FiscalRecipient`, `FiscalDocumentItem`, `TaxAmount` (SPEC §8.2.8); estados pendente → processando → autorizado/rejeitado → cancelado; correção e reenvio de rejeitado; cancelamento sensível (step-up) e dentro do prazo legal configurável.
- Porta `FiscalProvider` (`submit`, `queryStatus`, `cancel`); adaptador falso com WireMock; webhook de status e job de polling com ShedLock; fila com retry e backoff quando o middleware, a SEFAZ ou a prefeitura estiverem indisponíveis.
- Eventos `FiscalDocumentAuthorized` e `FiscalDocumentRejected`; alerta de rejeição (RF-NOT-01) enviado por `fiscal`, chamando `notifications` diretamente (aresta já na tabela §3.1).
- API pública do módulo para solicitar emissão a partir de itens já calculados (sem conhecer `billing` nem `pos`).

**Fora de escopo:** montagem da NFS-e a partir da conta (E07); NFC-e e relatórios (E08); middleware real (E11).

**Critérios de aceite:**

- [ ] Testes de domínio cobrem todas as transições da SPEC §8.4 e rejeitam as inválidas com `code` estável.
- [ ] Documento autorizado não pode ser alterado; correção só por cancelamento e nova emissão.
- [ ] Adaptador falso testado com WireMock: autorizado publica `FiscalDocumentAuthorized`; rejeitado publica `FiscalDocumentRejected` com motivo; indisponível fica na fila e é reprocessado com backoff até autorizar.
- [ ] Reenvio após timeout usa a mesma referência idempotente e não cria segundo documento no provedor (teste).
- [ ] Status chega por webhook autenticado e, com o webhook desligado, por polling.
- [ ] Cancelamento sem aprovação → `403 APPROVAL_REQUIRED`; fora do prazo configurado → erro com `code` estável; cancelamento válido é auditado.
- [ ] CRUD de perfis fiscais exige permissão própria e audita alterações.
- [ ] `ApplicationModules.verify()` passa; `fiscal` depende só de `shared`, `hotel`, `files` e `notifications`, como na tabela da ARCHITECTURE §3.1.
- [ ] O desenho da porta consta numa matriz em `docs/`, que cruza cada operação com a documentação pública de ao menos dois middlewares.
- [ ] Nota rejeitada gera notificação via `notifications` (RF-NOT-01).
- [ ] Build, testes e verificação do contrato oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Verificação do contrato → sem diff pendente e sem *breaking change*.

**Revisão humana:** sim

**Notas para o plano:**

- H2, H3 e H7; leia o handoff de E01 e os da M5 E07 e E08 sobre o padrão de integração.
- Modele a porta `FiscalProvider` a partir de ao menos dois middlewares conhecidos (PlugNotas, Focus NFe, NFE.io): envio, status, cancelamento e webhooks; os campos de IBS/CBS (CST, cClassTrib) seguem o que a NFS-e nacional exige desde 01/10/2026 (RF-FIS-04), no perfil fiscal, sem regra fixa em código.
- Módulo novo: a captura de atualização do brain desta etapa propõe a nota de tema nova `ZenyHotelManager - Módulo fiscal`; as arestas novas do `fiscal` entram na tabela da ARCHITECTURE §3.1 na mesma mudança.
- O `billing` já guarda em `TransactionCode` uma classificação fiscal em texto livre (`fiscal_classification`, H2 do M3), à espera do vínculo com `FiscalProfile` sem migração destrutiva.
- Migrações na pasta `fiscal` (a criar), com versão posterior à última da `main` (hoje `V2026_11_20_1002`). As métricas da fila fiscal seguem o padrão registrado pela E13.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Porta acoplada a um middleware específico: modelar a partir de dois middlewares na investigação.
- Mudanças de IBS/CBS (ARCHITECTURE §18): campos no perfil fiscal, sem regra fixa em código.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E07 — NFS-e da hospedagem no check-out — #pendente

**Objetivo:** emitir NFS-e para hospedagem e demais serviços a partir de uma conta inteira ou de uma seleção de lançamentos (RF-FIS-01, RF-FIS-05), com separação de receitas e destaque de IBS/CBS (RF-FIS-04), guardar XML e PDF (RF-FIS-07) e vincular a nota à conta.

**Por que agora:** com o núcleo pronto (E06), é a emissão de maior volume e a que encerra a emissão manual no portal da prefeitura (SPEC §9, Fase 1).

**Dependências:** E06. Não rodar junto com E08 (mesmo código de emissão em `billing` e `fiscal`).

**Escopo:**

- `billing`: montagem dos itens da nota a partir dos lançamentos (perfil fiscal por código de lançamento), tomador pessoa física (CPF ou estrangeiro) ou empresa, separação por regime (hospedagem, A&B, eventos, outros); chamada à API pública de `fiscal`; consumo de `FiscalDocumentAuthorized`/`Rejected` para vincular a nota aos lançamentos.
- `frontdesk`: check-out oferece a emissão da NFS-e (padrão: emitir; configurável), sem bloquear o check-out se o middleware estiver fora (a nota fica na fila).
- `fiscal`: guarda de XML e PDF no armazenamento de arquivos (H5), com download por URL assinada e reimpressão.
- Café incluso na NFS-e da hospedagem (H6).

**Fora de escopo:** NFC-e (E08); contas a receber e faturas (M10).

**Critérios de aceite:**

- [ ] Emissão por conta inteira e por seleção de lançamentos, com tomador CPF, estrangeiro ou CNPJ (testes).
- [ ] Itens separados por regime (hospedagem, A&B, eventos, outros), cada um com o perfil fiscal do código de lançamento e `TaxAmount` de ISS e IBS/CBS.
- [ ] Um lançamento já vinculado a nota autorizada ou em processamento não entra em nova nota → erro com `code` estável.
- [ ] Check-out com o middleware indisponível conclui; a nota fica pendente na fila e é emitida quando ele volta (teste com WireMock).
- [ ] `FiscalDocumentAuthorized` vincula a nota aos lançamentos na conta; `FiscalDocumentRejected` gera alerta.
- [ ] XML e PDF ficam no armazenamento de arquivos e são baixados só por URL assinada; o acesso é auditado.
- [ ] Totais calculados só com a função de arredondamento de `Money`.
- [ ] `ApplicationModules.verify()` passa; a ARCHITECTURE §3.1 reflete qualquer aresta nova.
- [ ] Build, testes e verificação do contrato oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Verificação do contrato → sem diff pendente e sem *breaking change*.

**Revisão humana:** não

**Notas para o plano:**

- H5 e H6; leia RF-FIS-01, RF-FIS-04, RF-FIS-05 e RF-FIS-07 (SPEC §5.12) e os handoffs do M3 (lançamentos, roteamento, transferências e taxa de serviço), do M5 (check-out) e do M1 (`files`, URL assinada).
- Tomador pessoa física: o número do documento do hóspede é cifrado no `guests`, e a `GuestsApi` só decifra os dados da FNRH (`fnrhData`, exclusiva do `fnrh`, RN-19); obter o CPF do tomador exige uma leitura nova e auditada na `GuestsApi`, decisão de privacidade a registrar (até o M7, a Recepcionista revela o CPF à mão, M3).
- XML e PDF da nota (H5): o servidor os grava pela `FilesApi.store` (M6 E23) numa finalidade `serverOnly`, como a `reporting.day-report`. O XML traz o documento do tomador, então a finalidade sensível (cifrada no bucket, servida só pelo servidor com trilha) é o ponto de partida; decida no plano e registre. Cada finalidade fica no máximo em `zeny.files.max-upload-size` (20 MB).
- Armadilhas do `billing`: um lançamento pode ter sido roteado, transferido (pares `TRANSFER_OUT`/`TRANSFER_IN`), dividido, ajustado ou estornado; a taxa de serviço é um lançamento separado do grupo `SERVICE_CHARGE`, repasse aos empregados e não receita do hotel (`RevenueGroup.passThrough`) — decidir como ela entra (ou não) na nota.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Regra fiscal da hotelaria ainda em transição (LC 214/2025): perfis configuráveis e revisão com o contador em E11.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E08 — NFC-e de A&B, relatórios e exportação de XMLs — #pendente

**Objetivo:** disponibilizar a emissão de NFC-e (modelo 65) para A&B — consumidor final sem identificação para o PDV (M8) e NFC-e agrupada no check-out para o consumo lançado no quarto (H6) — e entregar os relatórios fiscais e a exportação de XMLs para a contabilidade (RF-FIS-09).

**Por que agora:** o PDV de M8 depende deste serviço; os relatórios fecham o ciclo fiscal do marco.

**Dependências:** E07.

**Escopo:**

- `fiscal`: API pública de NFC-e a partir de itens com perfil fiscal de produto (NCM, CFOP), consumidor identificado ou não; reutiliza fila, porta e eventos de E06.
- `billing`: no check-out, NFC-e agrupada para lançamentos de A&B lançados no quarto (H6), configurável para emitir no consumo.
- Relatórios fiscais: notas por período, tipo, status e regime; totais de ISS e IBS/CBS; rejeitadas pendentes.
- Exportação de XMLs por período em arquivo compactado, com download por URL assinada e auditoria.

**Fora de escopo:** comandas e chamada de NFC-e no fechamento de comanda (M8); exportação contábil completa (M10).

**Critérios de aceite:**

- [ ] NFC-e para consumidor final sem identificação emitida pela API pública de `fiscal` (teste com adaptador falso).
- [ ] No check-out, o consumo de A&B lançado no quarto gera uma NFC-e agrupada; com a configuração "emitir no consumo", a emissão acontece no lançamento.
- [ ] Nenhum lançamento entra em NFS-e e NFC-e ao mesmo tempo (teste).
- [ ] Relatório fiscal por período, tipo, status e regime com totais de ISS e IBS/CBS.
- [ ] Notas rejeitadas pendentes aparecem em `GET /api/v1/reports/end-of-day-pending` e em `ReportingApi.endOfDayPendingItems()` como um tipo novo de `EndOfDayPendingKind`, lidas pelo `reporting` numa view `fiscal.report_*`, e a etapa `end-of-day-pending` (950) as registra como `EndOfDayIssue` sem mudança no `endofday` (RF-FEC-04; teste com o fechamento real em `@EndOfDayIntegrationTest`).
- [ ] Exportação de XMLs por período gera arquivo compactado, baixado por URL assinada, com acesso auditado.
- [ ] A API de NFC-e que o PDV deve chamar está descrita no handoff.
- [ ] Build, testes e verificação do contrato oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Verificação do contrato → sem diff pendente e sem *breaking change*.

**Revisão humana:** não

**Notas para o plano:**

- H6 e Q-04 (a configuração "emitir no consumo" cobre a alternativa); o núcleo está nos handoffs de E06 e E07.
- Relatórios fiscais seguem a convenção das views de relatório (`fiscal.report_*`, só `SELECT` para o papel da aplicação, sem dado pessoal de hóspede nem texto livre; nota `ZenyHotelManager - Schemas, migrações e views de relatório`).
- A exportação compactada de XMLs vai ao `files`, pela `FilesApi.store` numa finalidade `serverOnly` (como os XMLs da E07): o tamanho máximo de uma finalidade não passa de `zeny.files.max-upload-size` (20 MB) e os limites do multipart acompanham; um período longo pode passar disso.
- **Pendências do fechamento (M6 E13, H24 a do M6):** o caminho já existe e não se amplia. O `reporting` lê só views (`ReportingViewsOnlyTest` e ArchUnit), então o `fiscal` publica a view e o `reporting` acrescenta o tipo e a leitura em `endOfDayPendingItems()` e no relatório da E09 do M6; a aresta `endofday → reporting` continua com as três operações, e o `reporting` não ganha dependência de módulo em `fiscal` (views são da §11). O `detail` segue a regra do `EndOfDayPendingItem`: só número, código e status, sem nome do tomador nem a mensagem da rejeição. Os testes do fechamento que afirmam a lista exata de pendências já filtram pela etapa ou pelas próprias referências (handoff da M6 E13).

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Q-04 decidida diferente do padrão: a configuração "emitir no consumo" já cobre a alternativa.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E09 — Adaptador real do PSP Pix — #bloqueada

**Objetivo:** implementar e homologar o adaptador do PSP Pix escolhido atrás da porta `PaymentProvider`, com cobrança, webhook, consulta e devolução reais em ambiente de homologação.

**Por que agora:** é a primeira integração real de pagamento; sem ela a definição de pronto do marco não fecha.

**Dependências:** E02. Independente de E10 e E11 (podem rodar em branches separadas); conflito de edição na configuração de integrações.

**Escopo:** adaptador do PSP (a criar em `payments/infrastructure/`), autenticação (OAuth2/mTLS, conforme o PSP), mapeamento de erros, testes de contrato com WireMock gravados a partir da documentação, teste manual em homologação, configuração por variáveis de ambiente, atualização do mapeamento de CSV da conciliação (E05).

**Fora de escopo:** troca de PSP no futuro (a porta já permite).

**Critérios de aceite:**

- [ ] Testes de contrato com WireMock cobrem cobrança, consulta, webhook, devolução e os erros documentados do PSP.
- [ ] Roteiro de homologação executado: cobrança criada, pagamento confirmado (webhook ou polling) e refletido na conta, devolução concluída — evidências no handoff.
- [ ] Credenciais e certificados só por variável de ambiente ou secret; nada no repositório.
- [ ] Build e testes oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Roteiro de homologação → evidências registradas (IDs de transação de homologação, sem dados pessoais).

**Revisão humana:** sim

**Notas para o plano:**

- D1 registrada e credenciais de homologação fora do repositório antes de começar; os testes de contrato com WireMock partem da documentação oficial do PSP.
- O webhook real valida a origem (H3) e todo erro do PSP vira falha ou retry, nunca sucesso; se o PSP exigir mTLS, o certificado vai como Docker secret, documentado no `README.md`.

**Bloqueios:**

- **Causa:** o PSP Pix não foi escolhido (SPEC Q-05).
- **Evidência:** SPEC §10 Q-05 e RF-PAG-06 dizem que o PSP "será definido depois"; não há credenciais de homologação.
- **Impacto:** impede o adaptador real e a homologação do Pix (critérios 1 a 3) e, por consequência, o gate E12.
- **Ação de desbloqueio:** o usuário escolhe o PSP e obtém credenciais de homologação; registrar a decisão em D1 e trocar a tag desta etapa para `#pendente` (índice e título).

**Riscos e mitigação:**

- PSP exige mTLS com certificado: guardar como Docker secret e documentar no `README.md`.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E10 — Adaptador real do adquirente — #bloqueada

**Objetivo:** implementar e homologar a integração de cartão com o adquirente escolhido, no modo decidido (TEF com biblioteca no cliente ou Smart POS com API em nuvem), incluindo pré-autorização, captura, cancelamento e tokenização.

**Por que agora:** é a primeira integração real com adquirente; caução e PDV dependem dela em produção.

**Dependências:** E03. Independente de E09 e E11 (podem rodar em branches separadas); conflito de edição na configuração de integrações.

**Escopo:** Smart POS: adaptador (a criar em `payments/infrastructure/`) com testes de contrato e homologação. TEF: ajustes no contrato de reporte do cliente conforme a biblioteca escolhida (SiTef, PayGo ou outra) e documentação para o repositório do cliente; importação do extrato do adquirente para a conciliação (E05). Tokenização pelo componente do provedor. Validade real da pré-autorização para a renovação (E04).

**Fora de escopo:** implementação da biblioteca TEF no cliente (repositório do cliente).

**Critérios de aceite:**

- [ ] Testes de contrato cobrem as operações e erros documentados do adquirente no modo escolhido.
- [ ] Roteiro de homologação executado: venda, pré-autorização, captura parcial, cancelamento e tokenização — evidências no handoff.
- [ ] Extrato do adquirente importado na conciliação de E05 sem divergência para as transações de homologação.
- [ ] Antecedência de renovação da pré-autorização configurada conforme o prazo do adquirente.
- [ ] Nenhum dado de cartão além de token, bandeira e 4 últimos chega ao servidor; nenhum segredo no repositório.
- [ ] Build e testes oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Roteiro de homologação → evidências registradas.

**Revisão humana:** sim

**Notas para o plano:**

- D2 (adquirente e modo) registrada e credenciais de homologação disponíveis antes de começar; Q-13 afeta a biblioteca TEF dos clientes.
- A caução manual do M3 limita a validade a 90 dias (`CardPreAuthorization.MAX_VALIDITY`): o prazo real do adquirente precisa caber nela e na antecedência de renovação de E04.

**Bloqueios:**

- **Causa:** adquirente e modo de cartão não foram escolhidos (SPEC Q-05; Q-13 afeta a biblioteca TEF nos clientes).
- **Evidência:** SPEC §10 Q-05 e Q-13 e ARCHITECTURE §18 ("TEF vs. Smart POS") estão em aberto; não há credenciais de homologação.
- **Impacto:** impede o adaptador real e a homologação de cartão e caução, e por consequência o gate E12.
- **Ação de desbloqueio:** o usuário escolhe o adquirente e o modo (TEF ou Smart POS) e obtém credenciais de homologação; registrar a decisão em D2 e trocar a tag desta etapa para `#pendente` (índice e título).

**Riscos e mitigação:**

- TEF depende do cliente desktop/PDV ainda não pronto: homologar o contrato de reporte com um cliente de teste e registrar a pendência no handoff.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E11 — Adaptador real do middleware fiscal — #bloqueada

**Objetivo:** implementar e homologar o adaptador do middleware fiscal escolhido atrás da porta `FiscalProvider`, emitindo NFS-e da hospedagem e NFC-e de A&B em homologação com as regras do município e do regime tributário do hotel.

**Por que agora:** é a primeira integração fiscal real; sem ela o hotel não emite nota pelo sistema.

**Dependências:** E08. Independente de E09 e E10 (podem rodar em branches separadas); conflito de edição na configuração de integrações.

**Escopo:** adaptador (a criar em `fiscal/infrastructure/`), autenticação, envio, consulta, cancelamento, webhook; perfis fiscais iniciais do hotel conforme o regime e o município; validação da regra de arredondamento (RN-02) com o middleware; testes de contrato; roteiro de homologação de NFS-e e NFC-e (autorização, rejeição corrigida e reenviada, cancelamento).

**Fora de escopo:** certificados e cadastros na prefeitura/SEFAZ (responsabilidade do hotel).

**Critérios de aceite:**

- [ ] Testes de contrato com WireMock cobrem envio, consulta, cancelamento, webhook e erros documentados.
- [ ] Em homologação: NFS-e de hospedagem autorizada, NFC-e de A&B autorizada, uma rejeição corrigida e reenviada até autorizar e um cancelamento — evidências no handoff.
- [ ] Os totais calculados pelo servidor batem com os do middleware nas notas de homologação; a regra de arredondamento está registrada.
- [ ] Perfis fiscais iniciais do hotel configurados conforme D3.
- [ ] Nenhuma credencial ou certificado no repositório; build e testes oficiais passam.

**Validação obrigatória:**

- Comandos oficiais de build e teste → sucesso.
- Roteiro de homologação → evidências registradas (números e chaves de homologação).

**Revisão humana:** sim

**Notas para o plano:**

- D3 (middleware, regime e município) e H7; investigue se o município do hotel usa o emissor nacional de NFS-e.
- A regra única de arredondamento é `Money.round` (`Money.ROUNDING_MODE`, hoje `HALF_EVEN`): se a validação com o middleware (RN-02) exigir mudá-la, a mudança é transversal a todos os módulos e vale como decisão do usuário.

**Bloqueios:**

- **Causa:** middleware fiscal, regime tributário e regras do município não foram definidos (SPEC Q-05, Q-02, Q-03).
- **Evidência:** SPEC §10 Q-02, Q-03 e Q-05 estão em aberto; não há credenciais de homologação.
- **Impacto:** impede a emissão real de NFS-e e NFC-e e a validação do arredondamento (RN-02), e por consequência o gate E12.
- **Ação de desbloqueio:** o usuário escolhe o middleware, confirma regime e município com o contador e obtém credenciais de homologação; registrar a decisão em D3 e trocar a tag desta etapa para `#pendente` (índice e título).

**Riscos e mitigação:**

- Regras de IBS/CBS mudando em 2027 para o Simples (RF-FIS-04): perfis configuráveis e revisão anual com o contador.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E12 — Gate do marco M7 — #pendente

**Objetivo:** confirmar, a partir de uma cópia limpa do repositório, que toda a definição global de pronto do M7 é atendida, incluindo os roteiros de homologação com os fornecedores reais.

**Por que agora:** é o critério de conclusão do marco; o gate final de M8 (inauguração) exige este gate concluído.

**Dependências:** E04, E05, E09, E10, E11, E13 (que cobrem transitivamente todas as demais).

**Escopo:**

- Rodar build, testes, verificação de módulos e do contrato numa cópia limpa.
- Cenário ponta a ponta em homologação: reserva com sinal por Pix, check-in com caução por cartão, consumo lançado no quarto, check-out com captura parcial, NFS-e e NFC-e autorizadas, conciliação sem divergências.
- Conferir cada item da definição global de pronto com evidência.
- Atualizar `docs/roadmaps/README.md` registrando a conclusão do M7 no histórico de revisões.

**Fora de escopo:** funcionalidade nova. Problemas voltam para a etapa de origem (reabrir com `#em-andamento` e registrar no handoff dela), salvo ajustes triviais de documentação.

**Critérios de aceite:**

- [ ] Todos os itens da "Definição global de pronto" estão marcados, cada um com evidência numa cópia limpa ou em homologação.
- [ ] O cenário ponta a ponta foi executado com os adaptadores reais, sem divergências na conciliação.
- [ ] Logs da homologação não contêm dado de cartão nem dados pessoais em claro.
- [ ] `docs/roadmaps/README.md` registra a conclusão do M7 no histórico de revisões.

**Validação obrigatória:**

- Comandos oficiais de build, teste e verificação do contrato numa cópia limpa → sucesso.
- Cenário ponta a ponta em homologação → evidências registradas.

**Revisão humana:** sim

**Notas para o plano:**

- Os gates do M3 e do M4 conferiram tudo numa cópia limpa (`git archive`, Gradle baixado do zero, `--no-daemon --no-build-cache`), com um roteiro `curl` num `docker compose` isolado e o cenário do marco também como teste de integração na raiz do pacote de testes (`MilestoneM3EndToEndTest`, `MilestoneM4EndToEndTest`); siga o mesmo padrão, além do cenário real em homologação.
- **Do gate do M6 (handoff da M6 E12):** o roteiro do compose é um `docker/gate/m7-roteiro.sh` sobre a `lib-roteiro.sh`, com a sua lista `WARN_ALLOWED` e o `run-gate.sh … m7` (o `run-gate.sh` hoje só aceita `m5` e `m6`); o teste do marco segue o `MilestoneM6EndToEndTest` (raiz do pacote de testes, `@EndOfDayIntegrationTest` se o cenário roda o fechamento com a renovação, operadores pelos modelos de role, provedores falsos em WireMock e relógio devolvido no `finally`). Os roteiros do M5 e do M6 rodam de novo, porque compartilham a biblioteca. Os gates nesta máquina levam até 54 min: `timeout 3600` e o resolvedor ajustado (ver "Base técnica herdada do M6").
- A captura de atualização do brain do gate registra o M7 concluído em `ZenyHotelManager - Estado por marco`.

**Bloqueios:** Nenhum bloqueio conhecido. Esta etapa só fica disponível depois que E09, E10 e E11 forem desbloqueadas e concluídas.

**Riscos e mitigação:**

- Indisponibilidade dos ambientes de homologação dos fornecedores: repetir o cenário em outro horário e registrar as tentativas.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## Histórico de revisões

- 2026-10-13 — roadmap criado.
- 2026-10-13 — aplicadas as decisões da entrevista SPEC × ARCHITECTURE (regra por camadas na ARCHITECTURE §3.1): H5 (`fiscal` → `files`) passa a fato; alertas de rejeição fiscal e falha de integração (RF-NOT-01) saem do módulo que detecta o problema via `notifications`; E06, E07 e E04 ajustados (escopo, prompt e critérios).
- 2026-10-22 — migrado para o formato da skill development-roadmap com superpowers (branch `roadmap/m7-pagamentos-fiscal`, protocolo e prompt de execução únicos, etapas sem prompt e estratégia) e para o Second Brain (notas de tema no lugar das seções do CLAUDE.md, captura de atualização por etapa); decisão do usuário.
- 2026-10-27 — protocolo de execução migrado para as souls do Second Brain: a sessão roda como `orquestrador-etapa` (`claude --agent orquestrador-etapa`), e as tarefas passam por `desenvolvedor` → `testador` → verificação mecânica dos testes → `revisor-seguranca`; a captura do brain é publicada pelo `brain-escriba`. Sem fallback para os prompts padrão do superpowers nem para `executing-plans`. Nenhuma etapa, tag, evidência ou handoff mudou.
- 2026-10-29 — protocolo de execução alinhado ao template atual da skill `development-roadmap`: o orquestrador passou de `orquestrador-etapa` para `orquestrador` (`claude --agent orquestrador`), entrou o papel "Revisor de plano" (`revisor-arquitetura`, pré-condição das souls e passo 1 do prompt) e o passo "Plano" diz que o plano passa pela revisão de arquitetura do orquestrador antes de executar. Só "Como usar", "Protocolo de execução" e "Prompt de execução" mudaram; etapas, handoffs e entradas anteriores do histórico seguem como estavam.
- 2026-11-06 — revisão de arquitetura (`docs/superpowers/specs/2026-11-06-revisao-arquitetura-roadmaps-design.md`). Protocolo e prompt alinhados ao template de 2026-11-05. H2 e H3 reescritas pelo que o M5 provou: não existe mecanismo genérico de falha, e o alerta é chamada direta a `notifications`; a saúde é status e métricas, sem `HealthIndicator`; webhook não tem padrão pronto. Novas restrições: resultado incerto, credenciais, registro de integração, saúde e contrato. A tensão sobre `payments` → `notifications` foi resolvida pela tabela §3.1. E01 e E06 ganharam a matriz da porta como critério; E02 e o gate E12 passaram a ter revisão humana. Depois da revisão do `revisor-arquitetura`: E06 depende da E02, e notas rejeitadas entram nas pendências do fechamento (E08). Nenhuma etapa nova, renumerada ou com tag alterada.
- 2026-11-21 — sincronizado com o código depois do gate do M6 (`main` em `8450920`) pelo `arquiteto` (skill `development-roadmap`, modo atualização; regra 2 de `docs/roadmaps/README.md`). Pré-requisito marcado como atendido. "Contexto confirmado" ganhou a "Base técnica herdada do M6", conferida no código: as 11 etapas do fechamento, com a `card-pre-authorizations` (600) `NOT_APPLICABLE` à espera da renovação e a exceção da 950 no KDoc do contrato de etapa; a `ReportingApi` com as três operações; `FilesApi.store` e `FilePurpose.serverOnly`; a trava da data operacional (RF-FEC-05); a entrada estrita e o `409 CONCURRENT_UPDATE`; o gate em `docker/gate/` (`lib-roteiro.sh`, `run-gate.sh … [m5|m6]`, `WARN_ALLOWED` por roteiro, `MilestoneM6EndToEndTest`); a última migração `V2026_11_20_1002`; e as arestas da §3.1 que o M7 usa e o código ainda não tem. Caminhos do `payments` corrigidos para os reais. H1 e H2 reconfirmadas. **H8 decidida pelo usuário** (entrevista desta sincronização, pelas opções recomendadas): etapa nova **E13** (métricas do fechamento no primeiro boot e Menores dos roteiros do gate), no bloco novo "Manutenção herdada do M6", antes da E01, e os advisories do `osv-scanner` anteriores ao M7 na M8 E17. A definição global de pronto ganhou o item da E13, e o gate E12 passou a depender dela. Notas novas em E01 e E06 (padrão das métricas da E13), E02 (trava da data e confirmação durante o fechamento), E04 (etapa 600 existente, `@EndOfDayIntegrationTest`, arestas `endofday → payments` e `frontdesk → payments`), E07 (`FilesApi.store` e finalidade `serverOnly` para XML e PDF), E08 (critério das notas rejeitadas reescrito pelo caminho da `ReportingApi.endOfDayPendingItems` e da etapa 950, sem ampliar a aresta) e E12 (padrão do gate do M6). O M7 passa a ter 13 etapas. Nenhuma etapa renumerada nem removida; tags existentes inalteradas.
