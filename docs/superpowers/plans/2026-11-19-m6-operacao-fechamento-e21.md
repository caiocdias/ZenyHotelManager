# E21 do M6 — Erros de trava e logs restantes: plano de implementação

> **Para agentes:** execução por `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde, não o código.

**Goal:** fechar as pendências de erro e de log deixadas pela E18 e pela E01: deadlock e `lock_timeout` que escapam de um caso de uso dão `409 CONCURRENT_UPDATE`; os quatro jobs restantes, o FCM e a trava do ShedLock deixam de logar a mensagem da exceção, e o `OFF` do ShedLock dá lugar a um filtro.

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E21", com H20 (c) e H21 (c); handoff da E18 ("Decisões para o usuário" (d) e "Notas para a próxima etapa") e da E01 (exemplos de `40P01`/`55P03`); ARCHITECTURE §7 (linha "Concorrência", com o `CONCURRENT_UPDATE` já decidido), §13 e §16. Brain: `ZenyHotelManager - Eventos e jobs`, `ZenyHotelManager - Docker, observabilidade e CI` ("Valores do banco fora do log"), `ZenyHotelManager - Convenções de API`, e as capturas pendentes `atualização m6-operacao-fechamento E18 (refação)` e `E01 (refação)`.

**Architecture:** tudo no `shared`, salvo o ajuste local de cada job e do gateway do FCM. O `ApiExceptionHandler` reconhece o SQLSTATE na cadeia de causas e responde com o código estável. A cadeia de classes da exceção, que o agendador da E18 já monta (`JobErrorLogging.classChain`, privada), vira um utilitário público do `shared` usado pelo agendador, pelos jobs, pelo FCM e pelo tratador. A trava do ShedLock passa a ter um filtro de log no lugar do `OFF`.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1 (Spring MVC, Logback), PostgreSQL 18 (SQLSTATE `40P01` e `55P03`), ShedLock 7, Micrometer, Testcontainers.

**Riscos:**
- **concorrência:** deadlock e espera de trava viram `409` retentável; `lock_timeout` da aplicação (D1); teste determinístico de trava cruzada (Task 1);
- **dado pessoal ou segredo:** a mensagem do JDBC pode trazer valores do banco, e a da assinatura do FCM pode trazer material da chave; nenhuma delas pode chegar ao log nem à resposta (Tasks 1, 2 e 3).

## Global Constraints

- Respeitar o grafo de módulos e a regra por camadas da ARCHITECTURE §3.1; esta etapa não cria aresta. Utilitário novo usado por outros módulos fica no pacote público do `shared` (`com.zeny.hotel.shared`).
- Sem migração.
- Toda mudança de API regenera o contrato `api/openapi.yaml` com `./gradlew updateOpenApi` na mesma mudança (nunca editado à mão); `./gradlew checkOpenApi` passa, e `.github/scripts/check-api-breaking.sh` não acusa `ERR` em `/api/v1`.
- Nenhum log nem resposta leva a mensagem de uma exceção do banco ou da assinatura: só a classe (e, no tratador da API, o SQLSTATE). ARCHITECTURE §13 e §16.
- Biblioteca nova só pelo catálogo `gradle/libs.versions.toml`, com os lockfiles regravados (não se espera nenhuma).
- Código, nomes e testes em inglês; KDoc, comentários, ARCHITECTURE e mensagens de commit em pt-BR (D17).
- Commits por fatia com o trailer `Soul: <soul>` no mesmo parágrafo do `Co-Authored-By`.
- Máquina de 14 GB: um Gradle por vez; nunca `./gradlew --stop`; todo comando longo com `timeout` (testes focados: `timeout 900`); nunca laço de espera (`while pgrep …`, `sleep` em laço).
- A verificação da fatia são os testes focados que ela cita mais `timeout 900 ./gradlew lint`; a suíte inteira roda no gate de integração do orquestrador.
- Os testes de integração usam `@IntegrationTest` (banco compartilhado, que nunca avança a data operacional) e afirmam só sobre os próprios dados; testes de trava são determinísticos por `pg_blocking_pids` (padrão de `ScheduledCleaningsTypeLockTest`, E01), nunca por `sleep`.
- Sentinela: os testes de log forçam a falha com um valor sentinela na mensagem **e** na causa e afirmam, sobre a saída capturada, a classe presente e o sentinela ausente em qualquer lugar (mensagem, stack, suprimidas).

## Review Focus

1. **`40P01`/`55P03` embrulhados** (ex.: `CannotAcquireLockException`, `PessimisticLockingFailureException`, `JpaSystemException` com a `PSQLException` na causa, `UnexpectedRollbackException` depois de um deadlock numa transação unida): o tratador acha o SQLSTATE na cadeia inteira e responde `409 CONCURRENT_UPDATE`; outro SQLSTATE segue `500`. Teste na Task 1.
2. **O `409` não esconde a falha:** o log do tratador leva a classe e o SQLSTATE, sem a mensagem, e um contador conta as ocorrências por SQLSTATE. Teste na Task 1.
3. **Retomada do `endofday`:** o `55P03` dela continua com o tratamento próprio (a resposta atual), não o `409` genérico. Teste existente, na Task 1.
4. **Mensagem sem exceção do `JdbcTemplateStorageAccessor`** (o "Unlock failed after 10 attempts") volta a aparecer, e um evento com exceção desse logger some inteiro: nem o stack nem a causa saem. Teste na Task 3.
5. **Falha do FCM com a chave inválida:** a `JOSEException` (ou outra exceção da montagem da asserção) não escapa do gateway; o envio segue o caminho de falha que já existe, sem a mensagem nem material de chave no log. Teste na Task 2.

## Decisões do plano

- **D1 — `lock_timeout` da aplicação: 10 s.** Hoje só o `endofday` limita a espera por trava (`SET LOCAL`), e uma requisição presa atrás de uma transação longa espera sem limite, segurando uma conexão do pool. Toda conexão do datasource da aplicação passa a abrir com `lock_timeout = '10s'` (junto do `SET TIME ZONE 'UTC'` que já existe na inicialização da conexão). O Flyway usa conexão própria e não é afetado. Os `SET LOCAL` do `endofday` continuam valendo por cima. Esgotado o prazo, o `55P03` vira `409 CONCURRENT_UPDATE` (retentável). Alternativa descartada: não definir (a espera segue sem limite, e o critério do `lock_timeout` só seria provado com um `SET LOCAL` de teste). Registrada na ARCHITECTURE pela Task 1.
- **D2 — utilitário da cadeia de classes.** A função `classChain` do agendador da E18 vira pública no `shared` (`com.zeny.hotel.shared.ExceptionClasses.chain(failure: Throwable): String`, com a mesma proteção contra ciclo) e é usada pelo agendador, pelos jobs, pelo FCM e pelo tratador da API. Sem duplicar a lógica.
- **D3 — métrica.** O tratador conta cada `409 CONCURRENT_UPDATE` num contador do Micrometer com a etiqueta do SQLSTATE (`zeny.api.concurrent.updates{sqlstate}`), para um deadlock recorrente aparecer sem a mensagem (risco do roadmap).

---

### Task 1: `409 CONCURRENT_UPDATE` para deadlock e `lock_timeout`, e o `lock_timeout` da aplicação

**Objetivo:** um `40P01` ou `55P03` que escapa de um caso de uso e chega ao `ApiExceptionHandler` dá `409` com o código estável `CONCURRENT_UPDATE`, sem a mensagem nem o detalhe do banco na resposta e no log, contado por SQLSTATE. Toda conexão da aplicação tem `lock_timeout` de 10 s (D1). O tratamento próprio do `endofday` continua. No protocolo offline, a mesma exceção segue `COMMAND_FAILED`.

**Critérios de aceite:**
- `ConcurrentUpdateApiTest` (novo, `@IntegrationTest`, com um caso de uso de teste exposto por um controller de teste em `testsupport`, como as sondas da E18):
  - **deadlock:** duas transações que travam duas linhas em ordem cruzada, coordenadas por `pg_blocking_pids` (sem `sleep`), fazem uma delas receber `40P01`; a requisição que perde responde `409` com `code` `CONCURRENT_UPDATE`, e o corpo não contém `deadlock`, `40P01`, nome de tabela nem o texto do PostgreSQL; o relatório traz o vermelho antes da correção (`500`);
  - **`lock_timeout`:** uma requisição que espera uma linha travada por outra transação além do `lock_timeout` responde `409 CONCURRENT_UPDATE`; a sonda baixa o prazo com `SET LOCAL`, para não esperar 10 s;
  - os casos embrulhados do Review Focus 1 (ao menos `CannotAcquireLockException` com `55P03` na causa e um `40P01` dentro de outra exceção) dão `409`, e uma `DataAccessException` com outro SQLSTATE segue `500`;
  - o log capturado do tratador traz a classe e o SQLSTATE, sem a mensagem; o contador da D3 sobe uma vez por resposta, com a etiqueta certa.
  - Verificação: `timeout 900 ./gradlew test --tests '*ConcurrentUpdateApiTest'`.
- **`lock_timeout` da aplicação:** um teste lê `SHOW lock_timeout` numa conexão do datasource da aplicação e obtém `10s`. Pode ficar no `ConcurrentUpdateApiTest`.
- **`endofday`:** `timeout 900 ./gradlew test --tests 'com.zeny.hotel.endofday.*'` verde (a retomada mantém a resposta própria do `55P03`).
- **Offline:** em `OfflineSyncTest`, um handler de teste que lança uma exceção com `40P01` volta `COMMAND_FAILED` (comportamento atual, retentável), não `REJECTED`. Verificação: `timeout 900 ./gradlew test --tests '*OfflineSyncTest'`.
- **Contrato:** `CONCURRENT_UPDATE` entra na lista de códigos estáveis do problema (descrição do `code` em `OpenApiConfiguration`) e nas mensagens do `Problems`; o contrato é regenerado com `timeout 900 ./gradlew updateOpenApi`; `timeout 900 ./gradlew checkOpenApi` passa, e `.github/scripts/check-api-breaking.sh` não acusa `ERR` (saída no relatório).
- A ARCHITECTURE registra a D1 (valor, onde é posto, Flyway fora, `SET LOCAL` por cima) e a D3, e a linha "Concorrência" da §7 passa a dizer "implementado pela M6 E21". Verificação: `grep -n "lock_timeout = '10s'\|10 s" docs/ARCHITECTURE.md` mostra a D1.
- `timeout 900 ./gradlew lint` verde.

**Áreas tocadas:** `src/main/kotlin/com/zeny/hotel/shared/web/ApiExceptionHandler.kt`, `src/main/kotlin/com/zeny/hotel/shared/web/Problems.kt`, `src/main/kotlin/com/zeny/hotel/shared/DomainErrors.kt` (`ErrorCodes.CONCURRENT_UPDATE`), `src/main/kotlin/com/zeny/hotel/shared/web/OpenApiConfiguration.kt`, `src/main/kotlin/com/zeny/hotel/shared/ExceptionClasses.kt` (novo, D2), `src/main/kotlin/com/zeny/hotel/shared/infrastructure/scheduling/ScheduledJobErrors.kt` (passa a usar o utilitário), `src/main/resources/application.yaml`, `api/openapi.yaml` (regenerado), `docs/ARCHITECTURE.md`. Testes: `src/test/kotlin/com/zeny/hotel/shared/web/ConcurrentUpdateApiTest.kt` (novo), a sonda em `src/test/kotlin/com/zeny/hotel/testsupport/`, `OfflineSyncTest`.

**Tamanho:** ~60 min; 7 arquivos de produção, mais o contrato regenerado.

**Depende de:** nenhuma.

**Interfaces (produz):**
- `com.zeny.hotel.shared.ExceptionClasses.chain(failure: Throwable): String` — `A <- B <- C`, os nomes de classe da falha e das causas, parando na primeira causa repetida.
- `ErrorCodes.CONCURRENT_UPDATE` e o contador `zeny.api.concurrent.updates` (etiqueta `sqlstate`).

### Task 2: Os quatro jobs e o FCM logam só a classe

**Objetivo:** `OutboxMetrics.refresh`, `FileOrphanPurgeJob`, `ProvisionalReservations` (expiração das pré-reservas) e `InventoryHorizonJob` logam a própria falha com o nome do job (ou o id do item) e só a cadeia de classes (D2), sem a mensagem nem o stack. No FCM, a exceção da assinatura do JWT (`JOSEException` de `SignedJWT.sign`, e qualquer outra da montagem da asserção) é capturada no gateway, vira a falha do pedido de token sem a mensagem nem material de chave no log, e o envio segue o caminho de falha que o `notifications` já tem.

**Critérios de aceite:**
- `JobFailureLogTest` (novo, `@IntegrationTest`): para cada um dos quatro jobs, a falha forçada (o item ou a leitura que falha com uma exceção cuja mensagem e cuja causa levam um sentinela) gera log, na saída capturada, com a classe da exceção e da causa e sem o sentinela em lugar nenhum; o relatório traz o vermelho antes da correção. A falha de um item continua não interrompendo os demais (os contadores atuais seguem). Verificação: `timeout 900 ./gradlew test --tests '*JobFailureLogTest'`.
- Teste do gateway do FCM (`FcmPushGatewayTest`, novo se não houver; hoje o gateway é exercitado por: ver o grep do relatório): com uma conta cuja assinatura falha com `JOSEException` com sentinela, `send` não lança, devolve a falha que o gateway já usa para token indisponível, e o log não traz o sentinela nem a mensagem; o relatório traz o vermelho antes da correção (a exceção escapando). Verificação: `timeout 900 ./gradlew test --tests '*FcmPushGateway*'`.
- As suítes atuais dos módulos tocados seguem verdes: `timeout 1800 ./gradlew test --tests 'com.zeny.hotel.files.*' --tests 'com.zeny.hotel.reservations.*' --tests 'com.zeny.hotel.notifications.*' --tests '*Outbox*'`.
- Nenhuma chamada de log com a exceção como último argumento sobra nos quatro jobs e no FCM; o relatório cola o grep que prova isso nos cinco arquivos.
- `timeout 900 ./gradlew lint` verde.

**Áreas tocadas:** `src/main/kotlin/com/zeny/hotel/shared/infrastructure/events/OutboxMetrics.kt`, `src/main/kotlin/com/zeny/hotel/files/infrastructure/FileOrphanPurgeJob.kt`, `src/main/kotlin/com/zeny/hotel/reservations/application/ProvisionalReservations.kt`, `src/main/kotlin/com/zeny/hotel/reservations/infrastructure/InventoryHorizonJob.kt`, `src/main/kotlin/com/zeny/hotel/notifications/infrastructure/FcmPushGateway.kt`. Testes: `src/test/kotlin/com/zeny/hotel/shared/JobFailureLogTest.kt` (novo; ou um por módulo, se o apoio de teste de cada um pedir) e o teste do gateway do FCM.

**Tamanho:** ~50 min; 5 arquivos de produção.

**Depende de:** Task 1 (usa `ExceptionClasses.chain`).

**Interfaces:** consome `com.zeny.hotel.shared.ExceptionClasses.chain` (Task 1). Não produz API nova.

### Task 3: Filtro no lugar do `OFF` do ShedLock

**Objetivo:** o logger `net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateStorageAccessor` deixa de estar em `OFF`. Um filtro de log descarta só os eventos desse logger que trazem exceção (a mensagem do JDBC pode levar valores do banco); as mensagens sem exceção, como o "Unlock failed after 10 attempts", voltam a aparecer.

**Critérios de aceite:**
- `application.yaml` sem `JdbcTemplateStorageAccessor: OFF`; o filtro fica no `shared` (um `TurboFilter` ou filtro equivalente do Logback, registrado pela aplicação, sem um `logback-spring.xml` novo, salvo se o relatório justificar). Verificação: `grep -n 'JdbcTemplateStorageAccessor: OFF' src/main/resources/application.yaml` vazio.
- `ShedLockLogFilterTest` (novo, `@IntegrationTest`):
  - com o `LockProvider` falhando com sentinela na mensagem e na causa (a sonda de trava da E18 em `testsupport`), nenhum evento de log do ShedLock traz o sentinela, e o evento com exceção do `JdbcTemplateStorageAccessor` não aparece;
  - uma mensagem sem exceção emitida por esse logger aparece na saída capturada;
  - a falha do job continua saindo pelo agendador da E18 (`Job … falhou: …`), só com as classes.
  - Verificação: `timeout 900 ./gradlew test --tests '*ShedLockLogFilterTest' --tests '*ScheduledJobErrorLogTest'`.
- A ARCHITECTURE §13 troca a decisão (d) da E18 (`OFF`) pelo filtro, com o custo que deixa de existir. Verificação: `grep -n 'JdbcTemplateStorageAccessor' docs/ARCHITECTURE.md` mostra o filtro.
- `timeout 900 ./gradlew lint` verde.

**Áreas tocadas:** `src/main/kotlin/com/zeny/hotel/shared/infrastructure/scheduling/` (o filtro, num arquivo novo, e o registro dele), `src/main/resources/application.yaml`, `docs/ARCHITECTURE.md`. Testes: `src/test/kotlin/com/zeny/hotel/shared/ShedLockLogFilterTest.kt` (novo).

**Tamanho:** ~40 min; 2 a 3 arquivos de produção.

**Depende de:** nenhuma.

**Interfaces:** nenhuma API nova.
