# Task 1 (E10, M6) - relatório do testador

Status: VERDE (nenhum teste vermelho; nenhum defeito achado em `EndOfDayIntegrationTest.kt`).

## O que analisei
Diff 8dc7d85..e68808c (só código de teste), brief, restrições globais, `IntegrationTest.kt`, `PostgresContainer.kt` e `docker/postgres/initdb/10-create-app-role.sh`.

Hipóteses atacadas:
- Contexto de preparo do banco compartilhado liga jobs agendados? Não: passa as 9 propriedades de adiamento como argumentos (`--...`), a mesma lista do `IntegrationTest` e do `EventPublicationRegistryTest`; fecha com `.close()` e o `lazy` repete a tentativa se o boot falhar (nada a fechar). Sem achado.
- Banco isolado sobrevive a dois contextos na mesma JVM: `EndOfDayDatabase.jdbcUrl` é `lazy` sincronizado (uma criação por JVM, sem corrida entre threads). Coberto por teste de segundo contexto.
- Permissões do script: o script faz 3 coisas por banco (REVOKE TEMPORARY de PUBLIC, GRANT CONNECT, REVOKE CREATE no schema public); todas replicadas. Provado por comparação de ACL com o banco compartilhado.
- Teste das propriedades iguais: pega divergência de `properties` do `@SpringBootTest`, mas não de profile, inicializadores ou propriedades do inicializador; acrescentei testes para isso.

Observação não bloqueante: `EndOfDayDatabase.jdbcUrl` não é idempotente a falha parcial (se `CREATE DATABASE` passa e um GRANT falha, a repetição dá "already exists"); só importa em falha de ambiente. Com container reaproveitado entre JVMs (`withReuse`) o CREATE também falharia; hoje o container é por JVM, sem reuso.

## O que acrescentei
Arquivo novo `src/test/kotlin/com/zeny/hotel/testsupport/EndOfDayDatabaseHardeningTest.kt` (`@EndOfDayIntegrationTest` + `@Import` de uma configuração vazia, o que força um segundo contexto fora do cache, além do de `EndOfDayIsolationTest`), 7 testes:
1. `a second context in the same JVM boots against the same isolated database`: o segundo contexto sobe (Flyway idempotente), aponta para `zeny_endofday` e existe um único banco com esse nome.
2. `database level privileges of the isolated database equal those of the shared one`: ACL do banco (`aclexplode(datacl)`) igual à do banco compartilhado.
3. `public schema privileges ... equal`: ACL do schema `public` igual nos dois bancos.
4. `the app role only has CONNECT on the isolated database`: `has_database_privilege` verdadeiro só para CONNECT (CREATE e TEMPORARY falsos).
5. `the app role cannot create schemas nor objects in public`.
6. `the initializer sets the same properties as the shared one except the database url`: usuário, senha e credenciais do Flyway iguais; URL termina em `/zeny_endofday`.
7. `both annotations carry the same profiles and initializers except the database one`.

Nenhum teste existente foi alterado.

## Comandos e saídas
`./gradlew test --tests 'com.zeny.hotel.testsupport.EndOfDay*'` -> `BUILD SUCCESSFUL in 1m 16s`; XML: `EndOfDayDatabaseHardeningTest tests="7" skipped="0" failures="0" errors="0"`; `EndOfDayIsolationTest tests="4" skipped="0" failures="0" errors="0"`.
`./gradlew ktlintFormat lint` -> `BUILD SUCCESSFUL in 2m 36s` (antes, detekt pegou `MapGetWithNotNullAssertionOperator` no meu teste 6; corrigido).
Suíte inteira não rodada (regra da máquina de 14 GB; só testes focados e lint).

## Testes vermelhos
Nenhum. Testes existentes que parecem errados: nenhum. Ressalva de força: os testes 2 e 3 dependem de as ACLs não serem vazias nos dois bancos; o script garante `datacl` não nula no compartilhado e o teste 4 prova os privilégios efetivos do papel.
