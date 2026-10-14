# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Projeto

Servidor do Zeny Hotel Manager: PMS e operações de **um único hotel** de até 30 UHs. Este repositório contém **só o servidor**. Os clientes (desktop, mobile, PDV, tela de produção e agente de impressão) ficam em outros repositórios e geram seus SDKs a partir do contrato OpenAPI.

- `docs/SPEC.md` diz **o quê**: requisitos `RF-<MÓDULO>-NN`, regras `RN-NN`, modelo de domínio (§8), fases (§9) e questões em aberto `Q-NN` (§10).
- `docs/ARCHITECTURE.md` diz **como**: decisões D1–D17 (§2), módulos e dependências (§3), convenções de API (§7), segurança (§8), offline (§9), persistência (§11) e integrações (§12).
- `docs/roadmaps/` traz o plano de execução: `README.md` com o mapa dos 11 marcos (M0–M10) e um `roadmap-mN-*.md` por marco.

Documentação, mensagens ao usuário e commits ficam em pt-BR. Código, API e banco ficam em inglês (D17); o glossário da SPEC §4 faz o mapeamento dos termos.

## Estado atual e comandos

O esqueleto existe desde o M0 E01: projeto Gradle único (Kotlin DSL), aplicação Spring Boot, módulo `shared`, verificação de módulos, regras ArchUnit de camadas, detekt, ktlint e Kover. Desde a E02 a aplicação usa PostgreSQL 18 com Flyway, JPA e `JdbcClient`, o outbox do Modulith (Event Publication Registry em JDBC) e jobs com ShedLock, e há testes de integração com Testcontainers. Desde a E03 o `shared` tem os tipos do kernel (dinheiro, percentual, IDs, números legíveis, CPF/CNPJ, endereço, cifragem de campo e relógio). Desde a E04 há Spring MVC com as convenções de API em `shared` (prefixo `/api/v1`, erros RFC 9457, paginação por cursor, `ETag`/`If-Match`), segurança mínima até o M1, um endpoint público (`GET /api/v1/meta/version`) e o contrato `api/openapi.yaml` gerado e conferido no build. Ainda não há `Idempotency-Key` (E05), Docker (E06) nem CI (E07).

Stack: Kotlin 2.4 sobre JDK 25, Spring Boot 4.1, Spring Modulith 2.1, Spring Security 7, springdoc 3.1, Hibernate 7.4, Flyway 12, ShedLock 7, Testcontainers 2, Gradle 9.8 pelo wrapper. Use sempre `./gradlew`; não use o `gradle` do apt, que é antigo demais. Docker é obrigatório para `./gradlew build`/`test` (os testes de integração sobem um `postgres:18` pelo Testcontainers) e para o `docker/compose.yaml` (E06).

**Comandos oficiais** (na raiz do repositório):

| Para quê | Comando | O que roda |
|---|---|---|
| Build completo | `./gradlew build` | compilação, testes (inclui `ModularityTest` e ArchUnit), detekt com e sem resolução de tipos, ktlint, `checkOpenApi` e `koverVerify` |
| Testes | `./gradlew test` | todos os testes; um só: `./gradlew test --tests 'com.zeny.hotel.ModularityTest'` |
| Lint | `./gradlew lint` | `detekt`, `detektMain`, `detektTest` e `ktlintCheck` |
| Corrigir formatação | `./gradlew ktlintFormat` | aplica o ktlint nos fontes |
| Cobertura | `./gradlew koverHtmlReport` | relatório em `build/reports/kover/html/` |
| Conferir o contrato | `./gradlew checkOpenApi` | gera o contrato da aplicação (`generateOpenApi`) e falha se `api/openapi.yaml` estiver diferente; já roda no `build` |
| Regenerar o contrato | `./gradlew updateOpenApi` | regrava `api/openapi.yaml`; versione-o junto com a mudança da API |
| Subir a aplicação | `./gradlew bootRun --args='--spring.profiles.active=local'` | fica rodando até Ctrl+C; precisa de um PostgreSQL e das chaves de cifragem (abaixo) |

Profiles: `local` (desenvolvimento) e `prod` (implantação), em `src/main/resources/application.yaml`. Não há profile padrão: informe sempre o profile.

**Banco:** o datasource vem de `ZENY_DB_URL`, `ZENY_DB_USERNAME` e `ZENY_DB_PASSWORD`. Em `prod` não há valor padrão (sem as variáveis a aplicação não sobe); em `local` o padrão é `jdbc:postgresql://localhost:5432/zeny` com usuário e senha `zeny`, só de desenvolvimento. Até o compose da E06, suba o banco local com `docker run -d --name zeny-db -e POSTGRES_DB=zeny -e POSTGRES_USER=zeny -e POSTGRES_PASSWORD=zeny -p 5432:5432 postgres:18`. Toda conexão roda em UTC (`SET TIME ZONE 'UTC'` no Hikari) e o Hibernate grava em UTC (`hibernate.jdbc.time_zone`); `open-in-view` está desligado e `ddl-auto` é `validate` (o schema é do Flyway).

**Chaves de cifragem** (ARCHITECTURE §8.3): `ZENY_ENCRYPTION_KEYS` (`id:base64[,id:base64…]`, 32 bytes por chave, AES-256) e `ZENY_ENCRYPTION_ACTIVE_KEY_ID` (a chave que cifra os valores novos) são obrigatórias em todos os profiles, inclusive `local`, e não têm valor padrão no código nem no YAML: sem elas a aplicação não sobe. Para desenvolvimento, gere uma chave e guarde-a enquanto usar o mesmo banco local (o que ela cifrou só se lê com ela): `export ZENY_ENCRYPTION_KEYS="dev:$(openssl rand -base64 32)" ZENY_ENCRYPTION_ACTIVE_KEY_ID=dev`. Rotação: acrescente a chave nova à lista, aponte a ativa para ela e mantenha a antiga até re-cifrar os valores que ainda a usam (`FieldCipher.isEncryptedWithActiveKey`).

**Versões** de dependências e plugins ficam só em `gradle/libs.versions.toml`. Os BOMs do Spring Boot e do Spring Modulith entram como `platform(...)`; libs gerenciadas por eles ficam no catálogo sem versão. Nunca escreva versão literal no `build.gradle.kts`. O detekt está na linha 2.0 (alpha), a única que entende Kotlin 2.4; sua configuração fica em `config/detekt/detekt.yml`, sobre os padrões. O estilo do ktlint (`ktlint_official`, 120 colunas) fica no `.editorconfig`.

**Estrutura de um módulo** (pacote base `com.zeny.hotel`):

```
com/zeny/hotel/<módulo>/
├── <Módulo>Api.kt, <Módulo>Events.kt   # raiz = API pública: interfaces, DTOs e eventos
├── domain/          # agregados e regras; sem Spring; de JPA só anotações (e seus enums)
├── application/     # casos de uso, transações, autorização
├── infrastructure/  # JPA, adaptadores, jobs
└── web/             # controllers; só fala com application (e com os DTOs da raiz)
```

- `ModularityTest` roda `ApplicationModules.verify()` (sem ciclos, sem acesso a internos de outro módulo) e gera a documentação dos módulos em `build/spring-modulith-docs/`.
- `LayerArchitectureTest` aplica as regras de `architecture/LayerRules.kt` (código de teste) ao código de produção: `domain` sem Spring, sem a API JPA/Hibernate e sem `application`/`infrastructure`/`web`; `web` sem `domain`/`infrastructure`; nenhum módulo usa `domain`/`application`/`infrastructure`/`web` de outro módulo.
- `LayerRulesTest` prova que cada regra falha diante de uma violação, usando as classes de exemplo em `src/test/kotlin/archfixtures/`. Ao mudar uma regra, mantenha ou acrescente o exemplo que a viola.
- `shared` é declarado em `shared/SharedModule.kt` (`@PackageInfo` + `@ApplicationModule`, o equivalente Kotlin do `package-info.java`) e como módulo compartilhado em `@Modulithic(sharedModules = ["shared"])`. Ele não pode depender de nenhum outro módulo (`allowedDependencies = []`). Um módulo novo segue o mesmo padrão quando precisar declarar metadados.
- Plugins `kotlin-spring`, `kotlin-jpa` e `allopen` já estão ligados: entidades `@Entity`, `@MappedSuperclass` e `@Embeddable` ficam abertas e com construtor sem argumentos.

**Schemas e migrações** (ARCHITECTURE §11): um schema por módulo, com o nome do módulo, e uma pasta de migrações por módulo.

- As migrações ficam em `src/main/resources/db/migration/<módulo>/V<aaaa>_<mm>_<dd>_<hhmm>__<descricao>.sql` (ex.: `V2026_10_14_0930__create_room.sql`), com descrição em inglês e `snake_case`. Só migrações versionadas (`V…`); arquivo aplicado nunca muda, corrige-se com uma migração nova.
- O `ModuleSchemasFlywayCustomizer` (`shared/infrastructure/persistence/`) lê as pastas e passa cada nome como schema ao Flyway, que cria o schema antes de migrar. Há um único histórico, `shared.flyway_schema_history`, e as versões são globais: a ordem de aplicação é a do timestamp, nunca a das pastas. Duas migrações com a mesma versão, em qualquer pasta, impedem o boot.
- `outOfOrder` está desligado: uma migração com timestamp anterior à última já aplicada (de uma branch longa, por exemplo) impede o boot com "Detected resolved migration not applied to database". A correção é renomeá-la para um timestamp novo antes do merge, nunca ligar `outOfOrder`.
- `MigrationConventionTest` barra no build: arquivo fora de uma pasta de módulo, pasta que não é módulo do Modulith, nome fora do padrão ou com data inválida, versão repetida entre pastas (inclusive as de teste) e SQL que crie, altere, remova (inclusive cada nome de `DROP TABLE a, b`), referencie por FK (`REFERENCES`) ou trigger (`CREATE TRIGGER … ON`), popule (`INSERT INTO`) ou dê permissão (`GRANT`/`REVOKE … ON`) sobre objeto fora do schema do próprio módulo ou sem schema, além de `CREATE SCHEMA`. São expressões regulares, não um parser SQL: cobrem as formas usuais.
- `shared` guarda a infraestrutura comum: `btree_gist` (no schema `public`), `shared.event_publication` (outbox do Modulith, estrutura v2 do `spring-modulith-events-jdbc`) e `shared.shedlock`.

Passo a passo para um módulo novo persistir dados:

1. Crie a pasta `src/main/resources/db/migration/<módulo>/`, com o mesmo nome do pacote do módulo (minúsculo, sem hífen).
2. Crie a primeira migração com o timestamp atual em UTC (`date -u +%Y_%m_%d_%H%M`), qualificando todo objeto com o schema: `CREATE TABLE <módulo>.<tabela> (…)`, `CREATE INDEX … ON <módulo>.<tabela> …`. Não crie o schema; o Flyway cria a partir da pasta. Sem FK para outro schema.
3. Nas entidades, use `@Table(name = "...", schema = "<módulo>")`; nas consultas com `JdbcClient`, qualifique as tabelas. Instantes em `timestamptz`.
4. Rode `./gradlew test`: `MigrationConventionTest` confere a convenção e `DatabaseMigrationsTest` aplica tudo do zero num PostgreSQL 18 e confere que o schema existe.

**Tipos do kernel** (RN-02, D15, D16, ARCHITECTURE §7, §8.3 e §11): a API pública fica na raiz de `com.zeny.hotel.shared`; nenhum módulo cria tipo próprio para estes conceitos.

- **Dinheiro:** sempre `Money` (BRL, `BigDecimal` com 2 casas); nunca `Double`/`Float` nem `BigDecimal` solto. `Money.of("12.50")` é exato e recusa mais de 2 casas. Arredondar só com `Money.round(...)`, a regra única (`Money.ROUNDING_MODE`, hoje `HALF_EVEN`); `money * fator` e `money * percent` já passam por ela. Para repartir um valor, use `allocate(pesos)` ou `split(n)` (método do maior resto: as partes somam exatamente o total). Coluna `numeric(14,2)`; em JSON, string `"123.45"`. O `MoneyConverter` é aplicado automaticamente.
- **Percentual:** `Percent` em pontos percentuais com 4 casas (`12.5` = 12,5%), coluna `numeric(7,4)`, string em JSON; `percent.of(money)` arredonda pela regra de `Money`.
- **IDs:** cada agregado declara `@JvmInline value class XId(override val value: UUID) : TypedId` e gera com `UuidV7.generate()`; coluna `uuid`. Na entidade o id fica desembrulhado e o Hibernate o mapeia direto, então busque com `em.find(X::class.java, id.value)` e declare `JpaRepository<X, UUID>`. Numa coleção (`Set<XId>`), use `@Convert` com uma subclasse de `TypedIdConverter`; sem ela, o contexto não sobe.
- **Números legíveis:** `DocumentNumberGenerator.next(DocumentNumberType("R", "<módulo>.<sequence>"), ano)` devolve `R-2026-000123`. O módulo cria a sequence na sua migração, e o ano vem da data operacional. A sequência não reinicia por ano e pode ter buracos (rollback), então não serve para numeração fiscal. Coluna `varchar`, com conversor automático.
- **Documentos e endereço:** `Cpf` e `Cnpj` (inclusive o alfanumérico) validam o dígito verificador e guardam só dígitos/caracteres (`varchar(11)`/`varchar(14)`, conversores automáticos). `Address` é `@Embeddable`: endereço no Brasil com o código IBGE do município (7 dígitos, coerente com a UF).
- **Dados sensíveis** (credenciais de integração, sensíveis da FNRH, número de documento): `@Convert(converter = EncryptedStringConverter::class)` num atributo `String` com coluna `text`, ou `FieldCipher` injetado. O valor gravado é `v1:<id da chave>:<base64url>`, com nonce aleatório por valor, e por isso a coluna cifrada não serve para busca nem índice. CPF cifrado: grave `cpf.digits` numa coluna cifrada.
- **Tempo:** injete `java.time.Clock` (bean UTC em `shared/infrastructure/time/`) em vez de chamar `Instant.now()`; um teste fixa o tempo declarando o seu próprio bean `Clock`.

**Convenções de API** (ARCHITECTURE §5 e §7, RNF-03, RNF-13). A infraestrutura fica em `shared`: a API pública na raiz (`ApiV1Controller`, `DomainErrors.kt`, `Pagination.kt`, `Concurrency.kt`) e a implementação em `shared/web/`.

- **Controller novo:** fica em `<módulo>/web/`, anotado com `@ApiV1Controller` (no lugar de `@RestController`) e `@RequestMapping("/<recurso>")`; o prefixo `/api/v1` é aplicado sozinho. Recursos em inglês, no plural e em kebab-case; transições de estado como ação (`POST /reservations/{id}/check-in`). Dê a cada operação um `@Operation(operationId = "...")` único (o teste do contrato barra repetidos) e um `@Tag` com o nome do módulo. DTOs são `data class` com `Instant` para instantes (saem em UTC com `Z`), `LocalDate` para datas de negócio (`YYYY-MM-DD`) e `Money`/`Percent` para valores (string decimal); nunca `OffsetDateTime`, `LocalDateTime` ou número para dinheiro. Validação de formato com Bean Validation (`@field:NotBlank`, `@field:Size`…) e `@Valid` no parâmetro. `ApiSecurityTest` barra controller da aplicação fora de `/api/v1`.
- **Erro de domínio:** lance uma subclasse de `DomainException` (sem Spring, pode sair de `domain` ou `application`) com um `ErrorCode` estável em `UPPER_SNAKE_CASE` e mensagem em pt-BR para o usuário final, sem dado pessoal: `throw ConflictException(ErrorCode("ROOM_NOT_READY"), "A UH 12 ainda não está pronta.")`. Famílias e status: `InvalidInputException` 400 (aceita `violations`), `PermissionDeniedException` 403, `NotFoundException` 404, `ConflictException` 409, `PreconditionFailedException` 412, `BusinessRuleException` 422, `PreconditionRequiredException` 428. Declare os códigos do módulo na API pública dele; um código publicado não muda de nome nem de sentido. O `ApiExceptionHandler` converte tudo em `application/problem+json` com `title` (frase do status em pt-BR), `status`, `detail`, `instance`, `code` e, na validação, `errors[]` (`field`, `code`, `message`). Exceção inesperada vira `500 INTERNAL_ERROR` genérico, com a causa só no log.
- **Catálogo de códigos comuns** (`ErrorCodes`): `VALIDATION_FAILED` 400 (com `errors[]`), `MALFORMED_REQUEST` 400 (JSON ilegível), `INVALID_CURSOR` 400, `UNAUTHENTICATED` 401, `ACCESS_DENIED` 403, `NOT_FOUND` 404, `METHOD_NOT_ALLOWED` 405, `NOT_ACCEPTABLE` 406, `CONFLICT` 409, `PRECONDITION_FAILED` 412, `PAYLOAD_TOO_LARGE` 413, `UNSUPPORTED_MEDIA_TYPE` 415, `BUSINESS_RULE_VIOLATED` 422, `PRECONDITION_REQUIRED` 428, `REQUEST_REJECTED` (outro 4xx), `INTERNAL_ERROR` 500, `SERVICE_UNAVAILABLE` 503. Códigos por campo (`FieldViolationCodes`): `REQUIRED`, `INVALID_FORMAT`, `INVALID_VALUE` e o nome da restrição em `UPPER_SNAKE_CASE` (`NOT_BLANK`, `SIZE`, `MIN`…). As mensagens saem em pt-BR (`spring.web.locale=pt_BR`, resolvedor fixo).
- **Paginação:** receba `@Valid page: PageParams` (`?cursor=…&limit=…`, limite padrão 50 e máximo 200), busque `page.limit + 1` linhas depois do cursor numa ordem total e estável (termine pelo id UUIDv7) e devolva `CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.id.toString()) }.map(::toDto)`. O JSON é `{"items": [...], "nextCursor": "..."}`, com `nextCursor` nulo na última página. Leia as chaves com `page.decodedCursor()?.uuid(0)` (`instant`, `long`, `string`): cursor ilegível ou adulterado vira `400 INVALID_CURSOR`. O cursor é opaco, mas não assinado: as chaves são entrada não confiável.
- **Concorrência:** leituras de um agregado devolvem `ResponseEntity.ok().eTag(ETags.of(entity.version))`; alterações recebem `@RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?`, chamam `ETags.expectedVersion(ifMatch)` (ausente: `428 PRECONDITION_REQUIRED`; etag fraca, `*` ou lista: `412`) e passam a versão ao caso de uso, que chama `requireCurrentVersion(expected, entity.version)` (diferente: `412 PRECONDITION_FAILED`). Conflito de lock otimista do JPA no commit também vira 412.
- **Contrato:** `api/openapi.yaml` (OpenAPI 3.0, chaves ordenadas) é gerado do código pelo teste `OpenApiContractExport` (tag `openapi-contract`, fora do `test`), que sobe o contexto de produção num PostgreSQL do Testcontainers. Mudou a API: rode `./gradlew updateOpenApi` e versione o arquivo na mesma mudança; o `build` falha com "api/openapi.yaml está desatualizado" se esquecer. Cada operação recebe a resposta `default` com o schema `Problem`; `Money`, `Percent`, `Cpf`, `Cnpj` e `DocumentNumber` aparecem como `string` (`OpenApiConfiguration`). O endpoint de exemplo das convenções (`ExampleItemsController`) é só de teste, fora do pacote base, e não entra no contrato.
- **Segurança até o M1** (`shared/web/SecurityConfiguration.kt`): só `GET /api/v1/meta/version` é público; o resto de `/api/**` exige autenticação e não há como autenticar (sem usuário, senha, HTTP Basic nem formulário; o `UserDetailsServiceAutoConfiguration` está excluído). Swagger UI e `/v3/api-docs` ficam abertos fora de `prod` e desligados em `prod`; qualquer outro caminho é negado. Recusas saem como problema: `401 UNAUTHENTICATED` e `403 ACCESS_DENIED`. Endpoint público novo exige mudar a configuração e a lista `PUBLIC_API_ENDPOINTS` do `ApiSecurityTest`, que testa todo endpoint de `/api/**` sem autenticação. Nos testes de API, autentique com `@WithMockUser`; `@IntegrationTest` já expõe um `MockMvc` com a cadeia de segurança.

**Eventos e jobs** (ARCHITECTURE §5 e §13):

- Eventos publicados dentro da transação do caso de uso são gravados em `shared.event_publication` no mesmo commit. Listeners `@ApplicationModuleListener` rodam depois, assíncronos e em transação própria; se falharem, a publicação fica `FAILED`.
- O `EventPublicationResubmissionJob` reenvia as publicações `FAILED` a cada minuto (`zeny.events.resubmission.*`), e no boot o Modulith reenvia as incompletas (`republish-outstanding-events-on-restart`). Publicação parada há mais de 10 min vira `FAILED` (`spring.modulith.events.staleness.*`). Por isso todo listener precisa ser idempotente.
- Jobs são `@Scheduled` com `@SchedulerLock(name = "<nome-único>")`; o ShedLock trava em `shared.shedlock` usando a hora do banco. `@EnableScheduling` e o `LockProvider` ficam em `shared/infrastructure/scheduling/SchedulingConfiguration.kt`.
- O `spring-modulith-moments` está excluído do build: a data operacional só avança no fechamento do dia (`hotel`, M1).

**Testes de integração:** anote a classe com `@IntegrationTest` (`src/test/kotlin/com/zeny/hotel/testsupport/`). Ela sobe o contexto web (servlet simulado, com `MockMvc` e a cadeia do Spring Security) com o profile `local` ligado a um único `postgres:18` compartilhado por todos os testes da JVM, acrescenta as migrações só de teste de `src/test/resources/db/testmigration/` (schema `test_support`), entrega uma chave de cifragem sorteada por JVM (`TestEncryptionKeys`) e adia o job de reprocessamento. Os testes rodam com a JVM em `America/Sao_Paulo` de propósito (`build.gradle.kts`), para pegar código que dependa do fuso da JVM.

## Como trabalhar com os roadmaps

- Cada etapa tem um prompt pronto e segue o "Protocolo de execução" do próprio roadmap. Ao executar uma etapa, atualize a tag dela (`#pendente`, `#em-andamento`, `#concluida` ou `#bloqueada`) e preencha "Evidências e handoff".
- Um marco só começa quando o gate do marco anterior estiver `#concluida`. As exceções estão em `docs/roadmaps/README.md`.
- Os roadmaps de M1 em diante foram escritos antes de existir código. Antes da primeira etapa de um marco, sincronize o roadmap com o código real usando a skill `development-roadmap` no modo atualização.
- Valide qualquer roadmap editado com `python3 ~/.claude/skills/development-roadmap/scripts/check_roadmap.py <arquivo>`.

## Arquitetura em uma página

- **Monólito modular** com Spring Modulith. Cada pacote de primeiro nível sob o pacote base (`com.zeny.hotel`, adotado no M0 E01) é um módulo, correspondente a um contexto da SPEC.
  - O que fica na raiz do pacote do módulo é a API pública: interfaces, DTOs e eventos.
  - Os subpacotes `domain/`, `application/`, `infrastructure/` e `web/` são internos.
  - `domain` não depende de Spring; `web` só fala com `application`.
- **Dependências entre módulos seguem a regra por camadas** (ARCHITECTURE §3.1 e §3.2).
  - Um módulo pode depender de qualquer módulo de camada inferior e de qualquer módulo da Plataforma, desde que sem ciclo. Ao criar uma dependência nova, acrescente-a à tabela da §3.1 na mesma mudança.
  - Para reagir a algo de uma camada acima, inverta a dependência com um evento ou com uma porta definida no módulo de baixo.
  - `notifications` e `printing` nunca escutam eventos de módulos de negócio: quem gera o aviso, ou é dono do documento, chama esses módulos.
- **Fluxo de escrita** (ARCHITECTURE §5):
  1. O controller converte o DTO em comando.
  2. O caso de uso em `application` abre a transação e checa autorização (permissão, escopo `OWN`/`ALL`, limites, step-up).
  3. O agregado aplica as regras e registra eventos de domínio.
  4. No commit, os eventos vão para o *Event Publication Registry* (outbox) e a auditoria é gravada na mesma transação.
  5. Os listeners `@ApplicationModuleListener` rodam depois, de forma assíncrona e com reprocessamento.
- **Persistência:** um schema PostgreSQL por módulo, sem FK entre schemas. Migrações Flyway ficam numa pasta por módulo, com versão por timestamp (`V2026_10_09_1200__…`). Use JPA para escrita e `JdbcClient` para leitura. O módulo `reporting` lê só as views `report_*` publicadas pelos outros módulos.
- **Invariantes que o banco garante:**
  - Nunca há UH em dobro: `EXCLUDE USING gist` sobre as alocações.
  - A disponibilidade usa contadores por tipo e data com `SELECT … FOR UPDATE`.
  - Lançamentos e pagamentos são imutáveis (RN-03): o usuário da aplicação não tem `UPDATE` nem `DELETE` nessas tabelas, e a auditoria é *append-only*.
- **Tipos:**
  - IDs são UUIDv7 gerados na aplicação.
  - Dinheiro usa `Money` sobre `BigDecimal` / `numeric(14,2)`, nunca ponto flutuante.
  - Instantes ficam em `timestamptz` UTC; datas de negócio em `date`, seguindo a data operacional do hotel, que só avança no fechamento do dia.
- **API** (ARCHITECTURE §7):
  - Endpoints sob `/api/v1`, com erros RFC 9457 e `code` estável.
  - `Idempotency-Key` em operações financeiras e externas; `ETag`/`If-Match`; paginação por cursor.
  - O `api/openapi.yaml` é gerado no build e versionado. Nunca edite à mão: regenere com `./gradlew updateOpenApi` e commite junto da mudança.
- **Offline e tempo real:**
  - O app operacional sincroniza por `/api/v1/sync/changes` (cursor) e `/api/v1/sync/commands`, com idempotência por `commandId`. Cada módulo registra seus `OfflineCommandHandler`.
  - Mudanças de estado chegam aos clientes por SSE em `/api/v1/stream`.
- **Integrações** (FNRH, channel manager, PSP Pix, adquirente, middleware fiscal, FCM): porta no módulo e adaptador por provedor, com outbox, retry e log redigido, testados com WireMock. Os fornecedores ainda não foram escolhidos (Q-05).

## Decisões do usuário

Quando houver pontos que cabem ao usuário decidir (hipóteses não confirmadas, trade-offs, escolhas de versão ou ferramenta, itens de "Revisão humana" de uma etapa), não os deixe só listados num relatório: conduza uma entrevista com `AskUserQuestion`.

- Cada pergunta é de múltipla escolha e tem uma opção recomendada, que vem em primeiro lugar com o rótulo "(Recomendado)" e uma justificativa curta.
- Agrupe até 4 perguntas por chamada.
- Depois, registre as decisões onde o projeto as documenta: handoff da etapa, hipóteses do roadmap, ARCHITECTURE ou este arquivo.
- Subagentes não fazem a entrevista: devolvem os pontos ao orquestrador ou à sessão principal, que entrevista o usuário.

## Git

Trabalhe direto na branch `main`, sem criar branch de feature, salvo pedido explícito. Commit e push só quando o usuário pedir.
