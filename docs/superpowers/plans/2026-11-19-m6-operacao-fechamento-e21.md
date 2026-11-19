# E21 do M6 — Erros de trava e logs restantes: plano de implementação

> **Para agentes:** execução por `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde, não o código.

**Goal:** fechar as pendências de erro e de log deixadas pela E18 e pela E01: deadlock e `lock_timeout` que escapam de um caso de uso dão `409 CONCURRENT_UPDATE` retentável; os quatro jobs restantes, o FCM e a trava do ShedLock deixam de logar a mensagem da exceção, e o `OFF` do ShedLock dá lugar a um filtro.

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E21", com H20 (c) e H21 (c); handoff da E18 ("Decisões para o usuário" (d) e "Notas para a próxima etapa") e da E01 (exemplos de `40P01`/`55P03`); ARCHITECTURE §5 (item 6), §7 (linhas "Concorrência" e "Idempotência"), §11, §13 e §16. Brain: `ZenyHotelManager - Eventos e jobs`, `ZenyHotelManager - Docker, observabilidade e CI` ("Valores do banco fora do log"), `ZenyHotelManager - Convenções de API`, e as capturas pendentes `atualização m6-operacao-fechamento E18 (refação)`, `E01 (refação)` e `E20`.

**Architecture:** tudo no `shared`, salvo o ajuste local de cada job e do gateway do FCM. O `ApiExceptionHandler` reconhece o SQLSTATE em qualquer `java.sql.SQLException` da cadeia de causas e responde com o código estável; o `IdempotencyFilter` libera a chave nesse caso (D4). A cadeia de classes da exceção, que o agendador da E18 já monta (`JobErrorLogging.classChain`, privada), vira um utilitário público do `shared` usado pelo agendador, pelos jobs e pelo FCM. A trava do ShedLock passa a ter um filtro de log no lugar do `OFF`.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1 (Spring MVC, Logback), PostgreSQL 18 (SQLSTATE `40P01` e `55P03`), HikariCP, ShedLock 7, Micrometer, Nimbus JOSE, Testcontainers.

**Riscos:**
- **concorrência:** deadlock e espera de trava viram `409` retentável; `lock_timeout` da aplicação (D1); liberação da chave de idempotência no `409` (D4); teste determinístico de trava cruzada (Task 1);
- **dado pessoal ou segredo:** a mensagem do JDBC pode trazer valores do banco, e a da assinatura do FCM pode trazer material da chave; nenhuma delas pode chegar ao log nem à resposta nos caminhos desta etapa (Tasks 1, 2 e 3).

## Global Constraints

- Respeitar o grafo de módulos e a regra por camadas da ARCHITECTURE §3.1; esta etapa não cria aresta. Utilitário novo usado por outros módulos fica no pacote público do `shared` (`com.zeny.hotel.shared`).
- Sem migração.
- Toda mudança de API regenera o contrato `api/openapi.yaml` com `./gradlew updateOpenApi` na mesma mudança (nunca editado à mão); `./gradlew checkOpenApi` passa, e `.github/scripts/check-api-breaking.sh` não acusa `ERR` em `/api/v1`.
- Nos caminhos que esta etapa toca (o `409 CONCURRENT_UPDATE`, os quatro jobs, o FCM e o filtro do ShedLock), nenhum log nem resposta leva a mensagem de uma exceção do banco ou da assinatura: só a classe (e, no tratador, o SQLSTATE). O caminho `500` do `ApiExceptionHandler` não muda nesta etapa. ARCHITECTURE §13 e §16.
- Nenhum controller, rota ou bean de teste pode entrar no contrato: o que o teste expõe fica aninhado na classe de teste ou num `@TestConfiguration` importado (precedente `ServiceCredentialChainTest`), nunca um `@RestController` de nível superior em `testsupport`.
- Biblioteca nova só pelo catálogo `gradle/libs.versions.toml`, com os lockfiles regravados (não se espera nenhuma).
- Código, nomes e testes em inglês; KDoc, comentários, ARCHITECTURE e mensagens de commit em pt-BR (D17).
- Commits por fatia com o trailer `Soul: <soul>` no mesmo parágrafo do `Co-Authored-By`.
- Máquina de 14 GB: um Gradle por vez; nunca `./gradlew --stop`; todo comando longo com `timeout` (testes focados: `timeout 900`); nunca laço de espera (`while pgrep …`, `sleep` em laço).
- A verificação da fatia são os testes focados que ela cita mais `timeout 900 ./gradlew lint`; a suíte inteira roda no gate de integração do orquestrador.
- Os testes de integração usam `@IntegrationTest` (banco compartilhado, que nunca avança a data operacional) e afirmam só sobre os próprios dados; testes de trava são determinísticos por `pg_blocking_pids` (padrão de `ScheduledCleaningsTypeLockTest`, E01), nunca por `sleep`.
- Sentinela: os testes de log forçam a falha com um valor sentinela na mensagem **e** na causa e afirmam, sobre a saída capturada, a classe presente e o sentinela ausente em qualquer lugar (mensagem, stack, suprimidas).

## Review Focus

1. **`40P01`/`55P03` embrulhados** (ex.: `CannotAcquireLockException`, `PessimisticLockingFailureException`, `JpaSystemException` com a `SQLException` na causa, ou o SQLSTATE só no `getNextException` de um lote): o tratador acha o SQLSTATE em qualquer `java.sql.SQLException` da cadeia e responde `409 CONCURRENT_UPDATE`; outro SQLSTATE segue `500`. A `UnexpectedRollbackException` de uma transação unida **não** traz a causa (quem marcou *rollback-only* engoliu a falha, ARCHITECTURE §5 item 6) e segue `500`. Teste na Task 1.
2. **O `409` não esconde a falha:** o log do tratador leva a classe e o SQLSTATE, sem a mensagem, e um contador conta as ocorrências por SQLSTATE. Teste na Task 1.
3. **Reenvio com a mesma `Idempotency-Key` depois de um `409 CONCURRENT_UPDATE`:** executa de novo (a chave foi liberada) e, sem conflito, responde o sucesso; não devolve o `409` guardado. Teste na Task 1.
4. **Mensagem sem exceção do `JdbcTemplateStorageAccessor`** (o "Unlock failed after 10 attempts") volta a aparecer, e um evento com exceção desse logger some inteiro (nem stack nem causa), também quando a exceção vem como último parâmetro do log (num `TurboFilter`, ela chega em `params`, com o `Throwable` nulo). Teste na Task 3.
5. **Falha do FCM na assinatura:** a `JOSEException` (ou uma `RuntimeException` da montagem da asserção) não escapa do gateway; o envio segue o caminho de falha que já existe, sem a mensagem nem material de chave no log. Teste na Task 2.

## Decisões do plano

- **D1 — `lock_timeout` da aplicação: 10 s.** Hoje só o `endofday` limita a espera por trava (`SET LOCAL`), e uma requisição presa atrás de uma transação longa espera sem limite, segurando uma das conexões do pool (o `connection-timeout` do Hikari é 5 s, `application.yaml`). Toda conexão do datasource da aplicação passa a abrir com `lock_timeout = 10s` (por `connection-init-sql`, junto do `SET TIME ZONE 'UTC'` que já existe, ou por `data-source-properties.options=-c lock_timeout=10s`; o desenvolvedor escolhe e o teste com `SHOW lock_timeout` cobre as duas). 10 s é o mesmo prazo do avanço da data (§11). Esgotado o prazo, o `55P03` vira `409 CONCURRENT_UPDATE` (retentável). Consequências, registradas na ARCHITECTURE: a etapa do fechamento que esperar mais de 10 s por uma trava falha e fica retomável (no item, vira pendência), como já acontece com o `55P03` do `endofday`; as travas consultivas também passam pelo `lock_timeout`; num ouvinte do Modulith, a falha vira publicação reprocessada; num job, sai pelo agendador da E18. Os `SET LOCAL` do `endofday` valem por cima. O Flyway abre conexão própria com o usuário de migração: o relatório da Task 1 confirma como o Boot 4.1 monta esse `DataSource` e se ele herda a inicialização do pool. Alternativas descartadas: não definir (a espera segue sem limite) e um `SET LOCAL` por caso de uso (cada módulo teria de lembrar). Registrada na ARCHITECTURE §11 pela Task 1.
- **D2 — utilitário da cadeia de classes.** A função `classChain` do agendador da E18 vira pública no `shared` (`com.zeny.hotel.shared.ExceptionClasses.chain(failure: Throwable): String`), com a proteção contra ciclo e um teto de elos (resolve o Menor da E18, "cadeia sem teto", `ScheduledJobErrors.kt:92-98`), e é usada pelo agendador, pelos jobs e pelo FCM. Sem duplicar a lógica. Entra na Task 2.
- **D3 — métrica.** O tratador conta cada `409 CONCURRENT_UPDATE` no contador `zeny.api.concurrent.updates` do Micrometer, com a etiqueta `sqlstate`, para um deadlock recorrente aparecer sem a mensagem (risco do roadmap; ARCHITECTURE §16).
- **D4 — idempotência no `409 CONCURRENT_UPDATE` (ruling do orquestrador, achado I2 da revisão de arquitetura; vai a "Decisões para o usuário").** O `IdempotencyFilter` guarda a resposta "qualquer que seja o status", e o `409` retentável ficaria guardado 24 h: o reenvio com a mesma chave devolveria o mesmo `409` para sempre. Decisão: no `409 CONCURRENT_UPDATE`, o filtro **libera a chave**, como já faz na recusa de segurança, e o reenvio com a mesma chave executa de novo. O sinal é **explícito**: o tratador do `CONCURRENT_UPDATE` grava um atributo da requisição (ex.: `IdempotencyFilter.RELEASE_KEY_ATTRIBUTE`, constante do `shared`) e o filtro, depois do `chain.doFilter`, libera a chave só quando o atributo está presente; nunca pelo status nem pela leitura do corpo. Qualquer outro `409` (de domínio, como `ROOM_ALREADY_ASSIGNED`, ou o de trava de domínio) continua guardado. A transação do caso de uso foi desfeita pela exceção. Alternativa descartada: manter guardado e documentar que o reenvio pede chave nova; isso contradiz "retentável" e tem o mesmo risco de efeito parcial num caso de uso com mais de uma transação. Registrada na ARCHITECTURE §7, linhas "Idempotência" e "Concorrência", pela Task 1.

---

### Task 1: `409 CONCURRENT_UPDATE` para deadlock e `lock_timeout`, a chave de idempotência liberada e o `lock_timeout` da aplicação

**Objetivo:** um `40P01` ou `55P03` que escapa de um caso de uso e chega ao `ApiExceptionHandler` dá `409` com o código estável `CONCURRENT_UPDATE`, sem a mensagem nem o detalhe do banco na resposta e no log, contado por SQLSTATE (D3); o `IdempotencyFilter` libera a chave nesse caso (D4). Toda conexão da aplicação tem `lock_timeout` de 10 s (D1). O tratamento próprio do `endofday` continua. No protocolo offline, a mesma exceção segue `COMMAND_FAILED`.

**Critérios de aceite:**
- `ConcurrentUpdateApiTest` (novo, `@IntegrationTest`; o caso de uso e o controller de teste ficam aninhados na classe de teste ou num `@TestConfiguration` importado):
  - **deadlock:** duas **requisições** que travam duas linhas em ordem cruzada, coordenadas por `pg_blocking_pids` (sem `sleep`): uma responde `409` com `code` `CONCURRENT_UPDATE` e a outra `2xx` (o teste não supõe qual das duas o PostgreSQL derruba); o corpo do `409` não contém `deadlock`, `40P01`, nome de tabela nem o texto do PostgreSQL; o relatório traz o vermelho antes da correção (`500`);
  - **`lock_timeout`:** uma requisição que espera uma linha travada por outra transação além do `lock_timeout` responde `409 CONCURRENT_UPDATE`; o caso de uso de teste baixa o prazo com `SET LOCAL`, para não esperar 10 s;
  - os casos embrulhados do Review Focus 1 (ao menos `CannotAcquireLockException` com `55P03` na causa, um `40P01` dentro de outra exceção e um SQLSTATE só no `getNextException`) dão `409`; uma `DataAccessException` com outro SQLSTATE e uma `UnexpectedRollbackException` sem causa seguem `500`;
  - o log capturado do tratador traz a classe e o SQLSTATE, sem a mensagem; o contador `zeny.api.concurrent.updates` sobe uma vez por resposta, com a etiqueta `sqlstate` certa;
  - **idempotência:** numa rota de teste `@Idempotent` cuja primeira chamada falha de forma determinística com `55P03` (o teste trava a linha e o caso de uso usa `SET LOCAL lock_timeout` curto; ou um `55P03`/`40P01` sintético só na primeira chamada), o reenvio com a mesma `Idempotency-Key`, já sem a trava, executa de novo e responde o sucesso (não o `409` guardado);
  - **idempotência, caso negativo:** numa rota de teste `@Idempotent` que responde um `409` de **outro** código (uma `DomainException` de conflito), o reenvio com a mesma chave devolve a resposta guardada com `Idempotent-Replayed: true`, e o caso de uso não executa de novo (contador da rota).
  - Verificação: `timeout 900 ./gradlew test --tests '*ConcurrentUpdateApiTest'`.
- **`lock_timeout` da aplicação:** um teste lê `SHOW lock_timeout` numa conexão do datasource da aplicação e obtém `10s`. Pode ficar no `ConcurrentUpdateApiTest`.
- **`endofday` e idempotência existentes:** `timeout 1200 ./gradlew test --tests 'com.zeny.hotel.endofday.*' --tests '*Idempotency*'` verde (a retomada mantém a resposta própria do `55P03`; os testes que comparam o `lock_timeout` com a linha de base seguem).
- **Offline:** em `OfflineSyncTest`, um handler de teste que lança uma exceção com `40P01` volta `COMMAND_FAILED` (comportamento atual, retentável), não `REJECTED`. Verificação: `timeout 900 ./gradlew test --tests '*OfflineSyncTest'`.
- **Contrato:** `CONCURRENT_UPDATE` entra na lista de códigos estáveis do problema (descrição do `code` em `OpenApiConfiguration`) e nas mensagens do `Problems`; o contrato é regenerado com `timeout 900 ./gradlew updateOpenApi`; o diff do `api/openapi.yaml` só muda essa descrição (o relatório cola o `git diff --stat` e o hunk); `timeout 900 ./gradlew checkOpenApi` passa, e `.github/scripts/check-api-breaking.sh` não acusa `ERR` (saída no relatório).
- A ARCHITECTURE registra a D1 (valor, onde é posto, o porquê, as consequências, o Flyway e as alternativas) na §11, e a D3 e a D4 na §7 (linhas "Concorrência" e "Idempotência"); a linha "Concorrência" passa a dizer "implementado pela M6 E21". Verificação: `grep -n 'implementado pela M6 E21' docs/ARCHITECTURE.md` mostra a linha "Concorrência", e `grep -n 'CONCURRENT_UPDATE' docs/ARCHITECTURE.md` mostra também a linha "Idempotência".
- `timeout 900 ./gradlew lint` verde.

**Áreas tocadas:** `src/main/kotlin/com/zeny/hotel/shared/web/ApiExceptionHandler.kt` (grava o atributo da D4), `src/main/kotlin/com/zeny/hotel/shared/web/Problems.kt`, `src/main/kotlin/com/zeny/hotel/shared/DomainErrors.kt` (`ErrorCodes.CONCURRENT_UPDATE`), `src/main/kotlin/com/zeny/hotel/shared/web/OpenApiConfiguration.kt`, `src/main/kotlin/com/zeny/hotel/shared/web/IdempotencyFilter.kt`, `src/main/resources/application.yaml`, `api/openapi.yaml` (regenerado), `docs/ARCHITECTURE.md`. Testes: `src/test/kotlin/com/zeny/hotel/shared/web/ConcurrentUpdateApiTest.kt` (novo), `OfflineSyncTest`.

**Tamanho:** ~60 min; 6 arquivos de produção, mais o contrato regenerado.

**Depende de:** nenhuma.

**Interfaces (produz):** `ErrorCodes.CONCURRENT_UPDATE` e o contador `zeny.api.concurrent.updates` (etiqueta `sqlstate`).

### Task 2: Utilitário da cadeia de classes; os quatro jobs e o FCM logam só a classe

**Objetivo:** a cadeia de classes vira utilitário público do `shared` (D2). `OutboxMetrics.refresh`, `FileOrphanPurgeJob`, `ProvisionalReservations` (expiração das pré-reservas) e `InventoryHorizonJob` logam a própria falha com o nome do job (ou o id do item) e só a cadeia de classes, sem a mensagem nem o stack. No FCM, a exceção da assinatura do JWT (`JOSEException` de `SignedJWT.sign`, e qualquer `RuntimeException` da montagem da asserção) é capturada no gateway, vira a falha do pedido de token sem a mensagem nem material de chave no log, e o envio segue o caminho de falha que o `notifications` já tem.

**Critérios de aceite:**
- `ExceptionClasses.chain` (com teste de unidade): `A <- B <- C` com os nomes de classe, para na primeira causa repetida e num teto de elos; o `JobErrorLoggingTaskScheduler` passa a usá-lo, e o `ScheduledJobErrorLogTest` segue verde. Verificação: `timeout 900 ./gradlew test --tests '*ExceptionClassesTest' --tests '*ScheduledJobErrorLogTest'`.
- `JobFailureLogTest` (novo; `@IntegrationTest` **ou** teste sem contexto que monta os jobs com dublês, à escolha do desenvolvedor, para não criar contextos novos na máquina de 14 GB): para cada um dos quatro jobs, a falha forçada (o item ou a leitura que falha com uma exceção cuja mensagem e cuja causa levam um sentinela) gera log, na saída capturada, com a classe da exceção e da causa e sem o sentinela em lugar nenhum; o relatório traz o vermelho antes da correção. A falha de um item continua não interrompendo os demais (os contadores atuais seguem). Verificação: `timeout 900 ./gradlew test --tests '*JobFailureLogTest'`.
- **FCM:** o gateway ganha um ponto de injeção `internal` para a fábrica do assinador (ex.: um parâmetro do construtor `(RSAPrivateKey) -> JWSSigner`, com o `RSASSASigner` como padrão), ajustado em `NotificationsTestSupport.testFcmPushGateway`. `FcmPushGatewayTest` (novo): com um assinador que lança `JOSEException` com sentinela (e outro que lança `IllegalArgumentException` com sentinela), `send` não lança, devolve a falha que o gateway já usa para token indisponível, e o log não traz o sentinela nem a mensagem; o relatório traz o vermelho antes da correção (a exceção escapando). Verificação: `timeout 900 ./gradlew test --tests '*FcmPushGatewayTest' --tests '*NotificationsPushTest'`.
- As suítes atuais dos módulos tocados seguem verdes: `timeout 1800 ./gradlew test --tests 'com.zeny.hotel.files.*' --tests 'com.zeny.hotel.reservations.*' --tests 'com.zeny.hotel.notifications.*' --tests '*Outbox*'`.
- Nenhum log com a exceção ou a mensagem dela sobra nos cinco arquivos: `grep -nE 'log\.(trace|debug|info|warn|error)\(.*(, (it|e|ex|t|failure)\)|mostSpecificCause|\.message|toString\(\))' src/main/kotlin/com/zeny/hotel/shared/infrastructure/events/OutboxMetrics.kt src/main/kotlin/com/zeny/hotel/files/infrastructure/FileOrphanPurgeJob.kt src/main/kotlin/com/zeny/hotel/reservations/application/ProvisionalReservations.kt src/main/kotlin/com/zeny/hotel/reservations/infrastructure/InventoryHorizonJob.kt src/main/kotlin/com/zeny/hotel/notifications/infrastructure/FcmPushGateway.kt` sai vazio (o relatório cola a saída; um falso positivo, se houver, é explicado linha a linha).
- `timeout 900 ./gradlew lint` verde.

**Áreas tocadas:** `src/main/kotlin/com/zeny/hotel/shared/ExceptionClasses.kt` (novo), `src/main/kotlin/com/zeny/hotel/shared/infrastructure/scheduling/ScheduledJobErrors.kt`, `src/main/kotlin/com/zeny/hotel/shared/infrastructure/events/OutboxMetrics.kt`, `src/main/kotlin/com/zeny/hotel/files/infrastructure/FileOrphanPurgeJob.kt`, `src/main/kotlin/com/zeny/hotel/reservations/application/ProvisionalReservations.kt`, `src/main/kotlin/com/zeny/hotel/reservations/infrastructure/InventoryHorizonJob.kt`, `src/main/kotlin/com/zeny/hotel/notifications/infrastructure/FcmPushGateway.kt`. Testes: `ExceptionClassesTest`, `JobFailureLogTest` (novos), `src/test/kotlin/com/zeny/hotel/notifications/FcmPushGatewayTest.kt` (novo), `NotificationsTestSupport.kt`.

**Tamanho:** ~60 min; 7 arquivos de produção.

**Depende de:** nenhuma.

**Interfaces (produz):** `com.zeny.hotel.shared.ExceptionClasses.chain(failure: Throwable): String` — `A <- B <- C`, os nomes de classe da falha e das causas, parando na primeira causa repetida ou no teto de elos.

### Task 3: Filtro no lugar do `OFF` do ShedLock

**Objetivo:** o logger `net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateStorageAccessor` deixa de estar em `OFF`. Um filtro de log descarta só os eventos desse logger que trazem exceção (a mensagem do JDBC pode levar valores do banco), venha ela como `Throwable` do evento ou como último parâmetro; as mensagens sem exceção, como o "Unlock failed after 10 attempts", voltam a aparecer.

**Critérios de aceite:**
- `application.yaml` sem `JdbcTemplateStorageAccessor: OFF`. O filtro fica no `shared/infrastructure/scheduling/` (`ShedLockLogFilter`). O registro sobrevive à reinicialização do logging pelo Boot (num `TurboFilter` programático, um contexto novo limpa os filtros): o desenvolvedor escolhe o mecanismo (ex.: `logback-spring.xml` com o filtro, ou o registro repetido a cada inicialização do logging) e justifica no relatório; se escolher `logback-spring.xml`, a saída estruturada atual (ECS) não muda. Verificação: `grep -n 'JdbcTemplateStorageAccessor: OFF' src/main/resources/application.yaml` vazio.
- `ShedLockLogFilterTest` (novo, `@IntegrationTest`, sobre o logging do contexto da aplicação):
  - eventos emitidos pelo logger `net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateStorageAccessor` com uma exceção com sentinela na mensagem e na causa, nas duas formas (`error(msg, t)` e a exceção como último parâmetro de uma mensagem com `{}`), não aparecem na saída capturada, e o sentinela não aparece em lugar nenhum;
  - um `JdbcTemplateLockProvider` real, montado no teste, falhando com sentinela num ramo em que o `JdbcTemplateStorageAccessor` loga a exceção (a falha precisa ocorrer na execução do comando, não só no `getConnection`, que sobe sem log dele; o desenvolvedor confere no fonte do ShedLock do lockfile qual ramo loga e o cita no relatório), não deixa o sentinela no log ao tentar travar; esse caso também tem o vermelho antes da correção (com o `OFF` retirado e sem o filtro, o sentinela aparece);
  - uma mensagem sem exceção emitida por esse logger aparece na saída capturada;
  - o relatório traz o vermelho antes da correção (com o `OFF` retirado e sem o filtro, o sentinela aparece).
  - Verificação: `timeout 900 ./gradlew test --tests '*ShedLockLogFilterTest' --tests '*ScheduledJobErrorLogTest'`.
- A ARCHITECTURE §13 troca a decisão (d) da E18 (`OFF`) pelo filtro, com o custo que deixa de existir. Verificação: `grep -n 'ShedLockLogFilter' docs/ARCHITECTURE.md` mostra a linha.
- `timeout 900 ./gradlew lint` verde.

**Áreas tocadas:** `src/main/kotlin/com/zeny/hotel/shared/infrastructure/scheduling/ShedLockLogFilter.kt` (novo) e o registro dele (`SchedulingConfiguration.kt` ou `src/main/resources/logback-spring.xml`), `src/main/resources/application.yaml`, `docs/ARCHITECTURE.md`. Testes: `src/test/kotlin/com/zeny/hotel/shared/ShedLockLogFilterTest.kt` (novo).

**Tamanho:** ~45 min; 2 a 3 arquivos de produção.

**Depende de:** nenhuma.

**Interfaces:** nenhuma API nova.
