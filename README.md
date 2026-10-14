# Zeny Hotel Manager — servidor

[![CI](https://github.com/caiocdias/ZenyHotelManager/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/caiocdias/ZenyHotelManager/actions/workflows/ci.yml)

Servidor do Zeny Hotel Manager: PMS e operações de um único hotel de até 30 UHs, construído como monólito modular em Kotlin com Spring Boot e Spring Modulith. Os clientes (desktop, mobile, PDV, tela de produção e agente de impressão) ficam em outros repositórios.

## Documentação

- [`docs/SPEC.md`](docs/SPEC.md): requisitos, regras de negócio, modelo de domínio, fases e questões em aberto.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md): decisões arquiteturais, módulos, convenções de API, segurança, persistência e integrações.
- [`docs/roadmaps/`](docs/roadmaps/README.md): plano de execução em 11 marcos (M0–M10), com um roadmap por marco.
- [`CLAUDE.md`](CLAUDE.md): guia de trabalho para o Claude Code, com os mesmos comandos e convenções deste arquivo.

## Pré-requisitos

- JDK 25. O Gradle vem pelo wrapper (`./gradlew`); não use o `gradle` instalado pelo sistema.
- Docker com o Compose v2: os testes de integração sobem um `postgres:18` pelo Testcontainers (`./gradlew build` e `./gradlew test` precisam dele), e o ambiente completo roda em `docker compose` (abaixo).

## Comandos

| Para quê | Comando |
|---|---|
| Build completo (compilação, testes, verificação de módulos, ArchUnit, detekt, ktlint, contrato OpenAPI) | `./gradlew build` |
| Só os testes | `./gradlew test` |
| Lint (detekt e ktlint) | `./gradlew lint` |
| Corrigir formatação | `./gradlew ktlintFormat` |
| Relatório de cobertura (Kover) | `./gradlew koverHtmlReport` |
| Conferir se `api/openapi.yaml` está atualizado (já roda no build) | `./gradlew checkOpenApi` |
| Regenerar `api/openapi.yaml` depois de mudar a API | `./gradlew updateOpenApi` |
| Procurar breaking change na API v1 contra a `main` (precisa de git e Docker) | `.github/scripts/check-api-breaking.sh` |
| Subir a aplicação localmente (precisa do banco, das chaves de cifragem e JWT e, no primeiro boot, do administrador inicial, abaixo) | `./gradlew bootRun --args='--spring.profiles.active=local'` |

Profiles disponíveis: `local` e `prod`. As versões de dependências e plugins ficam todas em [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Banco de dados

PostgreSQL 18, com um schema por módulo e migrações Flyway em `src/main/resources/db/migration/<módulo>/V<aaaa>_<mm>_<dd>_<hhmm>__<descricao>.sql` (passo a passo para um módulo novo no [`CLAUDE.md`](CLAUDE.md)). A conexão vem das variáveis `ZENY_DB_URL`, `ZENY_DB_USERNAME` e `ZENY_DB_PASSWORD`; no profile `local` o padrão é um banco `zeny` em `localhost:5432`, com usuário e senha `zeny`. Para rodar a aplicação fora do compose (`bootRun`), suba só esse banco:

```bash
docker run -d --name zeny-db -e POSTGRES_DB=zeny -e POSTGRES_USER=zeny -e POSTGRES_PASSWORD=zeny -p 5432:5432 postgres:18
```

## Cifragem de campo

Dados sensíveis são cifrados com AES-256-GCM (ARCHITECTURE §8.3). As chaves vêm de `ZENY_ENCRYPTION_KEYS` (`id:base64[,id:base64…]`, 32 bytes por chave) e `ZENY_ENCRYPTION_ACTIVE_KEY_ID`, obrigatórias em todos os profiles e sem valor padrão: sem elas a aplicação não sobe. Para desenvolvimento, gere uma chave e mantenha a mesma enquanto usar o banco local:

```bash
export ZENY_ENCRYPTION_KEYS="dev:$(openssl rand -base64 32)" ZENY_ENCRYPTION_ACTIVE_KEY_ID=dev
```

## Autenticação: chaves JWT e administrador inicial

O login (`POST /api/v1/auth/login`) devolve um access token JWT de 15 min, assinado em RS256, e um refresh token rotativo da sessão do dispositivo (ARCHITECTURE §8.1). As chaves de assinatura vêm de `ZENY_JWT_KEYS` (`kid:base64[,kid:base64…]`, cada uma uma chave privada RSA de 2048 bits ou mais em PKCS#8 DER, em base64) e `ZENY_JWT_ACTIVE_KEY_ID` (o `kid` que assina), obrigatórias em todos os profiles e sem valor padrão: sem elas a aplicação não sobe. Para desenvolvimento:

```bash
export ZENY_JWT_KEYS="dev:$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 | openssl pkcs8 -topk8 -nocrypt -outform DER | base64 | tr -d '\n')" ZENY_JWT_ACTIVE_KEY_ID=dev
```

Todas as chaves da lista valem na validação, pelo `kid`; para rotacionar, acrescente a nova, aponte a ativa para ela e retire a antiga depois de 15 min.

No primeiro boot, com o banco sem nenhum usuário, o servidor cria o administrador inicial a partir de `ZENY_ADMIN_LOGIN` e `ZENY_ADMIN_PASSWORD` (12 a 128 caracteres), com a role Administrador (todas as permissões), que precisa trocar a senha no primeiro login (`POST /api/v1/auth/password`). Sem usuários e sem essas variáveis, a aplicação não sobe; depois do primeiro boot elas são ignoradas e podem ser apagadas:

```bash
export ZENY_ADMIN_LOGIN=admin ZENY_ADMIN_PASSWORD="$(openssl rand -base64 18)"
```

## Ambiente completo em Docker

O [`docker/compose.yaml`](docker/compose.yaml) sobe o servidor como em produção (RNF-01): `api` (a imagem de [`docker/Dockerfile`](docker/Dockerfile)), `db` (PostgreSQL 18), `storage` (MinIO, com o bucket criado pelo serviço `storage-init`) e `proxy` (Caddy, só HTTP local). Backup e TLS público ficam para o M8.

1. Crie o `docker/.env` a partir do modelo e gere a chave de cifragem (guarde-a enquanto usar o mesmo volume do banco: o que ela cifrou só se lê com ela), a chave JWT e a senha do administrador inicial:

   ```bash
   cp docker/.env.example docker/.env
   sed -i "s|^ZENY_ENCRYPTION_KEYS=.*|ZENY_ENCRYPTION_KEYS=dev:$(openssl rand -base64 32)|" docker/.env
   sed -i "s|^ZENY_JWT_KEYS=.*|ZENY_JWT_KEYS=dev:$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 | openssl pkcs8 -topk8 -nocrypt -outform DER | base64 | tr -d '\n')|" docker/.env
   sed -i "s|^ZENY_ADMIN_PASSWORD=.*|ZENY_ADMIN_PASSWORD=$(openssl rand -base64 18)|" docker/.env
   ```

   O `docker/.env` é ignorado pelo Git. Os valores do modelo são só de desenvolvimento; num ambiente real, troque todos.

2. Suba (a primeira vez compila a imagem, o que leva alguns minutos) e confira a readiness pelo proxy:

   ```bash
   docker compose -f docker/compose.yaml up -d --build
   curl http://localhost/actuator/health/readiness    # {"status":"UP"}
   curl http://localhost/api/v1/meta/version
   curl -X POST http://localhost/api/v1/auth/login -H 'Content-Type: application/json' \
     -d '{"login": "admin", "password": "<ZENY_ADMIN_PASSWORD>", "device": {"id": "meu-pc", "name": "Meu PC"}}'
   ```

   O primeiro login do administrador volta com `passwordChangeRequired: true`: troque a senha em `POST /api/v1/auth/password` (com o `accessToken` em `Authorization: Bearer …`) e renove os tokens em `POST /api/v1/auth/refresh`.

   A porta e o endereço do proxy vêm de `ZENY_HTTP_PORT` (80) e `ZENY_HTTP_ADDRESS` (`127.0.0.1`; use `0.0.0.0` para abrir à rede local). O profile padrão é `prod` (logs em JSON, sem Swagger UI); com `ZENY_PROFILE=local` no `docker/.env`, o Swagger UI fica em `/swagger-ui.html`.

3. Acompanhe e derrube:

   ```bash
   docker compose -f docker/compose.yaml ps                # estado e health de cada serviço
   docker compose -f docker/compose.yaml logs -f api
   docker compose -f docker/compose.yaml down              # mantém os volumes (banco e arquivos)
   docker compose -f docker/compose.yaml down -v           # apaga também os volumes
   ```

Para só construir a imagem: `docker build -f docker/Dockerfile -t zeny-hotel-manager .` (na raiz; ela roda como o usuário não-root `zeny` e não traz profile nem segredos, que vêm do ambiente).

## Observabilidade

- Health: `GET /actuator/health/liveness` (processo vivo) e `GET /actuator/health/readiness` (pronto para tráfego; `DOWN` com o banco fora). São os únicos caminhos do actuator abertos, junto com o `/actuator/prometheus`; os demais endpoints não existem.
- Métricas: `GET /actuator/prometheus`, só na rede interna do compose (`http://api:8080/actuator/prometheus`; o proxy responde 404). Além das técnicas (JVM, HTTP, pool do banco), `zeny_outbox_publications{state="pending|failed"}` e `zeny_outbox_publications_oldest_age_seconds` medem o outbox de eventos; regras de alerta em [`docker/prometheus/alerts.yaml`](docker/prometheus/alerts.yaml).
- Logs: no profile `prod`, uma linha JSON por evento (formato ECS), com `traceId` e `spanId` nas requisições e, nas autenticadas, `userId` e `deviceSessionId` (nunca login, senha ou token); o servidor aceita e propaga o cabeçalho `traceparent` (W3C). No `local`, texto legível.

## API

REST/JSON sob `/api/v1` (ARCHITECTURE §7). O contrato [`api/openapi.yaml`](api/openapi.yaml) é gerado do código no build e versionado; o build falha se ele estiver desatualizado, e nunca se edita à mão. Erros seguem a RFC 9457 (`application/problem+json`) com `code` estável e `errors[]` por campo; listas paginam por cursor (`?cursor=…&limit=…`); alterações usam `ETag`/`If-Match` (`412` em conflito, `428` sem `If-Match`); operações com efeito financeiro ou externo exigem o cabeçalho `Idempotency-Key`, e a resposta fica guardada por 24h para as repetições; instantes saem em UTC com `Z`, datas como `YYYY-MM-DD` e dinheiro como string decimal. Fora do profile `prod`, o Swagger UI fica em `/swagger-ui.html`. Todo `/api/v1` exige o access token (`Authorization: Bearer …`), exceto o login, o refresh e `GET /api/v1/meta/version`; sem token, a resposta é `401 UNAUTHENTICATED`. O que cada usuário pode fazer vem das roles atribuídas a ele, compostas a partir do catálogo de permissões (`GET /api/v1/permissions`), com escopo `OWN`/`ALL` e limites; as permissões efetivas não vão no token e aparecem em `GET /api/v1/auth/me`, e a falta de uma permissão responde `403 PERMISSION_DENIED`. No boot, o servidor sincroniza o catálogo declarado no código e cria os modelos de role da SPEC §3.1. Como escrever um controller, lançar erro de domínio, paginar, marcar um endpoint idempotente e declarar e checar permissões: [`CLAUDE.md`](CLAUDE.md).

## Integração contínua

O workflow [`.github/workflows/ci.yml`](.github/workflows/ci.yml) (GitHub Actions) roda em todo push na `main` e em todo pull request. São três jobs em paralelo, e qualquer falha reprova a execução:

- **Build, testes e contrato:** com JDK 25 e cache do Gradle, roda `./gradlew build` (compilação, testes unitários e de integração com PostgreSQL pelo Testcontainers, verificação de módulos do Spring Modulith, ArchUnit, detekt, ktlint, cobertura e conferência do `api/openapi.yaml`) e `./gradlew checkOpenApi`. Publica como artefatos a documentação dos módulos (`spring-modulith-docs`) e o contrato (`openapi`); se falhar, também os relatórios dos testes.
- **Compatibilidade da API:** o [oasdiff](https://github.com/oasdiff/oasdiff) compara o `api/openapi.yaml` com o da `main` (num PR, a ponta da branch de destino; num push, o commit anterior ao push ou, sem ele, o commit pai) e reprova *breaking changes* em `/api/v1`, como endpoint, parâmetro ou propriedade removidos, tipo trocado ou campo que passa a ser obrigatório. Mudança incompatível vai para `/api/v2`; remover um endpoint v1 só passa se ele já estava marcado como `deprecated`. O mesmo teste roda localmente com `.github/scripts/check-api-breaking.sh`.
- **Imagem Docker:** constrói `docker/Dockerfile` sem publicar.

A CI não publica imagem nem faz deploy (M8) e não usa segredos. As actions ficam fixadas por SHA de commit, e o Dependabot ([`.github/dependabot.yml`](.github/dependabot.yml)) abre uma vez por mês um PR que as atualiza. Como o trabalho é direto na `main`, uma falha que entra por push só aparece depois, com a CI vermelha, sem ser bloqueada: rode `./gradlew build` e o `check-api-breaking.sh` antes de subir.

## Convenções de módulo

O pacote base é `com.zeny.hotel`. Cada pacote de primeiro nível abaixo dele é um módulo do Spring Modulith, correspondente a um contexto da SPEC (ARCHITECTURE §3 e §4). `shared` é o kernel compartilhado.

```
src/main/kotlin/com/zeny/hotel/
├── ZenyHotelApplication.kt
├── shared/                  # kernel compartilhado: Money, Percent, IDs, CPF/CNPJ, Address, cifragem
└── <módulo>/
    ├── <Módulo>Api.kt       # raiz do pacote = API pública: interfaces, DTOs e eventos
    ├── domain/              # agregados e regras, sem Spring
    ├── application/         # casos de uso, transações e autorização
    ├── infrastructure/      # JPA, adaptadores e jobs
    └── web/                 # controllers REST, que só falam com application
```

Os limites são verificados em teste a cada build:

- `ModularityTest`: `ApplicationModules.verify()` barra ciclos e acesso a pacotes internos de outro módulo, e gera a documentação dos módulos em `build/spring-modulith-docs/`.
- `LayerArchitectureTest`: regras ArchUnit das camadas internas. `domain` não depende de Spring, da API JPA/Hibernate (só anotações) nem das outras camadas; `web` não usa `domain` nem `infrastructure`; um módulo não usa as camadas internas de outro.
- `LayerRulesTest`: prova, com classes de exemplo em `src/test/kotlin/archfixtures/`, que cada regra falha diante de uma violação.

## Roadmaps

O trabalho segue os roadmaps em [`docs/roadmaps/`](docs/roadmaps/README.md). Cada etapa tem um prompt pronto para o Claude Code, critérios de aceite e uma seção "Evidências e handoff" que registra o que foi feito.
