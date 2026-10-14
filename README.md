# Zeny Hotel Manager — servidor

Servidor do Zeny Hotel Manager: PMS e operações de um único hotel de até 30 UHs, construído como monólito modular em Kotlin com Spring Boot e Spring Modulith. Os clientes (desktop, mobile, PDV, tela de produção e agente de impressão) ficam em outros repositórios.

## Documentação

- [`docs/SPEC.md`](docs/SPEC.md): requisitos, regras de negócio, modelo de domínio, fases e questões em aberto.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md): decisões arquiteturais, módulos, convenções de API, segurança, persistência e integrações.
- [`docs/roadmaps/`](docs/roadmaps/README.md): plano de execução em 11 marcos (M0–M10), com um roadmap por marco.
- [`CLAUDE.md`](CLAUDE.md): guia de trabalho para o Claude Code, com os mesmos comandos e convenções deste arquivo.

## Pré-requisitos

- JDK 25. O Gradle vem pelo wrapper (`./gradlew`); não use o `gradle` instalado pelo sistema.
- Docker: os testes de integração sobem um `postgres:18` pelo Testcontainers (`./gradlew build` e `./gradlew test` precisam dele), e a etapa E06 do M0 traz o ambiente em `docker compose`.

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
| Subir a aplicação localmente (precisa do banco e das chaves de cifragem, abaixo) | `./gradlew bootRun --args='--spring.profiles.active=local'` |

Profiles disponíveis: `local` e `prod`. As versões de dependências e plugins ficam todas em [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Banco de dados

PostgreSQL 18, com um schema por módulo e migrações Flyway em `src/main/resources/db/migration/<módulo>/V<aaaa>_<mm>_<dd>_<hhmm>__<descricao>.sql` (passo a passo para um módulo novo no [`CLAUDE.md`](CLAUDE.md)). A conexão vem das variáveis `ZENY_DB_URL`, `ZENY_DB_USERNAME` e `ZENY_DB_PASSWORD`; no profile `local` o padrão é um banco `zeny` em `localhost:5432`, com usuário e senha `zeny`. Para subir esse banco antes do compose da E06:

```bash
docker run -d --name zeny-db -e POSTGRES_DB=zeny -e POSTGRES_USER=zeny -e POSTGRES_PASSWORD=zeny -p 5432:5432 postgres:18
```

## Cifragem de campo

Dados sensíveis são cifrados com AES-256-GCM (ARCHITECTURE §8.3). As chaves vêm de `ZENY_ENCRYPTION_KEYS` (`id:base64[,id:base64…]`, 32 bytes por chave) e `ZENY_ENCRYPTION_ACTIVE_KEY_ID`, obrigatórias em todos os profiles e sem valor padrão: sem elas a aplicação não sobe. Para desenvolvimento, gere uma chave e mantenha a mesma enquanto usar o banco local:

```bash
export ZENY_ENCRYPTION_KEYS="dev:$(openssl rand -base64 32)" ZENY_ENCRYPTION_ACTIVE_KEY_ID=dev
```

## API

REST/JSON sob `/api/v1` (ARCHITECTURE §7). O contrato [`api/openapi.yaml`](api/openapi.yaml) é gerado do código no build e versionado; o build falha se ele estiver desatualizado, e nunca se edita à mão. Erros seguem a RFC 9457 (`application/problem+json`) com `code` estável e `errors[]` por campo; listas paginam por cursor (`?cursor=…&limit=…`); alterações usam `ETag`/`If-Match` (`412` em conflito, `428` sem `If-Match`); instantes saem em UTC com `Z`, datas como `YYYY-MM-DD` e dinheiro como string decimal. Fora do profile `prod`, o Swagger UI fica em `/swagger-ui.html`. Até o M1 (autenticação JWT), só `GET /api/v1/meta/version` responde sem autenticação; o resto da API recusa com `401`. Como escrever um controller, lançar erro de domínio e paginar: [`CLAUDE.md`](CLAUDE.md).

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
