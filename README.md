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

PostgreSQL 18, com um schema por módulo e migrações Flyway em `src/main/resources/db/migration/<módulo>/V<aaaa>_<mm>_<dd>_<hhmm>__<descricao>.sql` (passo a passo para um módulo novo no [`CLAUDE.md`](CLAUDE.md)).

O banco tem **dois papéis** (ARCHITECTURE §11):

- o **dono dos schemas**, usado só pelo Flyway para migrar (`ZENY_DB_MIGRATION_USERNAME` e `ZENY_DB_MIGRATION_PASSWORD`);
- o **papel da aplicação** (`ZENY_DB_USERNAME` e `ZENY_DB_PASSWORD`), com que o servidor conecta. Ele só lê e escreve linhas: não cria, altera nem remove tabelas (nem temporárias), e na trilha de auditoria só lê e insere.

A URL vem de `ZENY_DB_URL` e vale para os dois. O papel da aplicação é criado fora das migrações, que só concedem privilégios a ele, e nenhuma senha de papel fica no repositório. Se o servidor conectar com o dono, ou com um papel que altere a trilha, ele não sobe.

No profile `local`, o padrão é o banco `zeny` em `localhost:5432`, com o dono `zeny`/`zeny` e o papel da aplicação `zeny_app`/`zeny_app` (valores só de desenvolvimento). Para rodar a aplicação fora do compose (`bootRun`), suba só esse banco. O script [`docker/postgres/initdb/10-create-app-role.sh`](docker/postgres/initdb/10-create-app-role.sh) cria o papel da aplicação na primeira inicialização:

```bash
docker run -d --name zeny-db -e POSTGRES_DB=zeny -e POSTGRES_USER=zeny -e POSTGRES_PASSWORD=zeny \
  -e ZENY_DB_USERNAME=zeny_app -e ZENY_DB_PASSWORD=zeny_app \
  -v "$PWD/docker/postgres/initdb:/docker-entrypoint-initdb.d:ro" -p 5432:5432 postgres:18
```

O script só roda com o volume de dados vazio. Num banco criado antes da separação dos papéis, há duas saídas:

- recriá-lo: `docker rm -f zeny-db` e o comando acima, ou `docker compose -f docker/compose.yaml down -v` no compose;
- criar o papel uma vez à mão, com a senha que estiver em `ZENY_DB_PASSWORD`, e reiniciar a aplicação, cujas migrações concedem os privilégios:

  ```bash
  docker exec -it zeny-db psql -U zeny -d zeny \
    -c "CREATE ROLE zeny_app LOGIN PASSWORD '<senha>' NOSUPERUSER NOCREATEDB NOCREATEROLE" \
    -c "REVOKE TEMPORARY ON DATABASE zeny FROM PUBLIC" -c "GRANT CONNECT ON DATABASE zeny TO zeny_app"
  ```

  No compose, troque `zeny-db` por `docker compose -f docker/compose.yaml exec db`.

Uma implantação nova segue o mesmo passo: o administrador do banco cria o papel da aplicação, sem privilégios de criação, antes do primeiro boot.

## Cifragem de campo

Dados sensíveis são cifrados com AES-256-GCM (ARCHITECTURE §8.3). As chaves vêm de `ZENY_ENCRYPTION_KEYS` (`id:base64[,id:base64…]`, 32 bytes por chave) e `ZENY_ENCRYPTION_ACTIVE_KEY_ID`, obrigatórias em todos os profiles e sem valor padrão: sem elas a aplicação não sobe. Para desenvolvimento, gere uma chave e mantenha a mesma enquanto usar o banco local:

```bash
export ZENY_ENCRYPTION_KEYS="dev:$(openssl rand -base64 32)" ZENY_ENCRYPTION_ACTIVE_KEY_ID=dev
```

As mesmas chaves derivam (por HKDF) as chaves dos índices cegos, que permitem buscar por igualdade num campo cifrado (o número de documento do hóspede) sem decifrar a tabela. Na rotação, acrescente a chave nova e aponte a ativa para ela; um job re-cifra e reindexa os documentos de hóspede a cada hora (terminou quando a métrica `zeny_guests_documents_rekey_pending` chega a zero). Os outros valores cifrados ainda não têm job: mantenha a chave antiga até re-cifrá-los (passo a passo em "Chaves de cifragem" no [`CLAUDE.md`](CLAUDE.md)).

## Anexos (armazenamento S3)

Os anexos (logotipo do hotel, fotos de checklist e, nos próximos marcos, documentos de hóspede e XMLs fiscais) ficam num armazenamento compatível com S3: MinIO no compose, S3/R2/B2 na nuvem (ARCHITECTURE §14, D14). Os metadados ficam no schema `files`. O envio é `POST /api/v1/files` (multipart com `purpose` e `file`), e cada finalidade tem tamanho máximo, tipos aceitos (detectados pelo conteúdo, não pela extensão) e as permissões de envio e de download. O download é só por URL assinada de curta duração (`GET /api/v1/files/{id}/download-url`, 5 min por padrão). Anexo sensível é cifrado com AES-256-GCM, com as mesmas chaves de `ZENY_ENCRYPTION_KEYS`, antes de ir ao bucket; ele é baixado só pelo servidor, que o decifra, e cada URL gerada e cada download vão para a trilha de auditoria.

Ciclo de vida (M2 E11):

- **Envio repetível:** a parte opcional `clientFileId` (um UUIDv7 gerado pelo app, que vira o id do anexo) torna o envio seguro de repetir, para o app offline: o mesmo usuário reenviando o mesmo arquivo, com a mesma finalidade e o mesmo id, recebe `200` com o anexo já gravado, sem gravar de novo. O mesmo id com outro arquivo, outra finalidade, de outro usuário ou de um anexo já apagado dá `409 CLIENT_FILE_ID_CONFLICT`, e um id fora do formato dá `400 CLIENT_FILE_ID_INVALID`. Sem `clientFileId`, cada envio cria um anexo (`201`).
- **Reencode:** nas finalidades marcadas (as fotos de checklist, `checklists.photo`), a imagem JPEG ou PNG é regravada no servidor com o `javax.imageio` do JDK, sem metadados (EXIF, GPS, comentários) e com o lado maior de até 2048 px, e a orientação do EXIF é aplicada à imagem. Imagem ilegível dá `400 FILE_IMAGE_UNREADABLE`, e acima de 50 megapixels, `400 FILE_IMAGE_TOO_LARGE`. Essas finalidades não aceitam WebP. O logotipo não é reencodado.
- **Vínculo, exclusão e expurgo:** o módulo dono vincula o anexo ao registro dele e pode desvinculá-lo ou apagá-lo. O anexo apagado sai do bucket depois do commit e passa a responder `404 FILE_NOT_FOUND`, mas a linha fica, porque a trilha a referencia. Um anexo sem vínculo há mais de `zeny.files.orphan-retention` (24 h por padrão), contado do envio ou do último desvínculo, é apagado por um job a cada hora (`files-orphans-purge`), e cada expurgo vai para a trilha de auditoria.

| Variável | Para quê |
|---|---|
| `ZENY_STORAGE_ENDPOINT` | URL do S3 visto pelo servidor (no compose, `http://storage:9000`) |
| `ZENY_STORAGE_PUBLIC_ENDPOINT` | URL que os clientes alcançam, usada nas URLs assinadas dos anexos comuns. Vazia, vale a anterior. No compose é o proxy, `http://localhost`, com a porta se não for a 80 |
| `ZENY_STORAGE_BUCKET` | bucket dos anexos (`zeny-files`) |
| `ZENY_STORAGE_ACCESS_KEY`, `ZENY_STORAGE_SECRET_KEY` | usuário de **serviço** com política só do bucket, nunca o root do MinIO |
| `ZENY_STORAGE_REGION` | região (`us-east-1`, que o MinIO aceita) |

No `prod`, as variáveis não têm valor padrão: sem elas, a aplicação não sobe. No `local`, o padrão é um MinIO em `localhost:9000` com o bucket `zeny-files` e o usuário de serviço `zeny-app`/`zeny-app-dev-only`. Para o `bootRun`, suba-o e prepare-o com o mesmo script do compose:

```bash
docker run -d --name zeny-storage -e MINIO_ROOT_USER=zeny -e MINIO_ROOT_PASSWORD=zeny-dev-only   -p 127.0.0.1:9000:9000 pgsty/minio:RELEASE.2026-08-04T00-00-00Z server /data
docker run --rm --network container:zeny-storage -v "$PWD/docker/storage:/zeny:ro"   -e ZENY_STORAGE_INIT_ENDPOINT=http://127.0.0.1:9000 -e MINIO_ROOT_USER=zeny -e MINIO_ROOT_PASSWORD=zeny-dev-only   -e ZENY_STORAGE_BUCKET=zeny-files -e ZENY_STORAGE_ACCESS_KEY=zeny-app -e ZENY_STORAGE_SECRET_KEY=zeny-app-dev-only   --entrypoint sh pgsty/minio:RELEASE.2026-08-04T00-00-00Z /zeny/init-storage.sh
```

O script [`docker/storage/init-storage.sh`](docker/storage/init-storage.sh) cria o bucket privado e o usuário de serviço, com política só para ler, gravar, apagar e listar objetos desse bucket. Ele é idempotente e lê as credenciais da entrada padrão, sem passá-las pela linha de comando.

## Notificações e push (FCM)

As notificações internas (RF-NOT-01) aparecem no painel do destinatário (`GET /api/v1/notifications`) e no tópico pessoal `notifications` do canal de tempo real (`GET /api/v1/stream?topics=notifications`), e cada usuário escolhe por tipo se quer o painel e o push (`/api/v1/notification-preferences`). O app registra o token do FCM do aparelho na sessão em que está logado (`POST /api/v1/push-devices`); a sessão revogada ou vencida deixa de receber push, e o token que o FCM recusa é desativado. O envio é assíncrono, pelo outbox: uma falha temporária do FCM é reenviada com o backoff de 1 min a 1 h e abandonada, com alerta, depois de 24 h.

| Variável | Para quê |
|---|---|
| `ZENY_FCM_CREDENTIALS` | opcional: o JSON da conta de serviço do projeto Firebase, em base64 numa linha (`base64 -w0 conta-de-servico.json`). O `project_id` sai do JSON. Vazia, o push fica desligado e só o painel é entregue. Uma credencial ilegível impede o boot |

A conta de serviço sai do console do Firebase (Configurações do projeto > Contas de serviço > Gerar nova chave privada). Ela manda push em nome do projeto: guarde-a só no `docker/.env` (ou no cofre de segredos da implantação), nunca no repositório, e apague o arquivo JSON depois de copiá-la.

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

O [`docker/compose.yaml`](docker/compose.yaml) sobe o servidor como em produção (RNF-01): `api` (a imagem de [`docker/Dockerfile`](docker/Dockerfile)), `db` (PostgreSQL 18), `storage` (MinIO, com o bucket e o usuário de serviço da `api` criados pelo serviço `storage-init`) e `proxy` (Caddy, só HTTP local; repassa ao MinIO só o `GET`/`HEAD` de um objeto do bucket com a assinatura na URL e sem cabeçalho `Authorization`, as URLs pré-assinadas dos anexos; a listagem, a raiz do bucket e a assinatura por cabeçalho vão para a `api` e dão 401; o canal de tempo real, `/api/v1/stream`, vai à `api` sem buffer, para cada evento SSE chegar na hora). Backup e TLS público ficam para o M8.

1. Crie o `docker/.env` a partir do modelo. Ele traz o nome do banco (`ZENY_DB_NAME`, padrão `zeny`) e os dois papéis do banco: `ZENY_DB_MIGRATION_*`, o dono dos schemas e `POSTGRES_USER` do container, e `ZENY_DB_USERNAME`/`ZENY_DB_PASSWORD`, o papel da aplicação, criado na primeira inicialização do volume. Um `docker/.env` antigo precisa das duas variáveis novas e de um `ZENY_DB_USERNAME` diferente do dono; o volume antigo precisa ser recriado ou receber o papel à mão (seção "Banco de dados"). Gere também a chave de cifragem (guarde-a enquanto usar o mesmo volume do banco: o que ela cifrou só se lê com ela), a chave JWT e a senha do administrador inicial:

   ```bash
   cp docker/.env.example docker/.env
   sed -i "s|^ZENY_ENCRYPTION_KEYS=.*|ZENY_ENCRYPTION_KEYS=dev:$(openssl rand -base64 32)|" docker/.env
   sed -i "s|^ZENY_JWT_KEYS=.*|ZENY_JWT_KEYS=dev:$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 | openssl pkcs8 -topk8 -nocrypt -outform DER | base64 | tr -d '\n')|" docker/.env
   sed -i "s|^ZENY_ADMIN_PASSWORD=.*|ZENY_ADMIN_PASSWORD=$(openssl rand -base64 18)|" docker/.env
   sed -i "s|^ZENY_STORAGE_ROOT_PASSWORD=.*|ZENY_STORAGE_ROOT_PASSWORD=$(openssl rand -base64 24)|" docker/.env
   sed -i "s|^ZENY_STORAGE_SECRET_KEY=.*|ZENY_STORAGE_SECRET_KEY=$(openssl rand -base64 24)|" docker/.env
   ```

   O `docker/.env` é ignorado pelo Git. Os segredos (chaves, senha do administrador e as duas senhas do MinIO) vêm vazios no modelo, e o compose não sobe sem eles; os demais valores são só de desenvolvimento. Num ambiente real, troque todos.

   O MinIO tem dois usuários. `ZENY_STORAGE_ROOT_USER`/`ZENY_STORAGE_ROOT_PASSWORD` é o root, usado só pelo `storage` e pelo `storage-init`. `ZENY_STORAGE_ACCESS_KEY`/`ZENY_STORAGE_SECRET_KEY` é o usuário de serviço que a `api` usa, criado pelo `storage-init` com política só do bucket. Quem conhece a senha dele lê o bucket inteiro, então gere as duas senhas (comandos acima), sobretudo antes de abrir o proxy à rede com `ZENY_HTTP_ADDRESS=0.0.0.0`. O bucket (`ZENY_STORAGE_BUCKET`) não pode se chamar `api` nem `actuator`. Num `docker/.env` de antes da E08 do M1, o `ZENY_STORAGE_ACCESS_KEY`/`ZENY_STORAGE_SECRET_KEY` era o root. Mova esses valores para `ZENY_STORAGE_ROOT_*`, para o root continuar o mesmo, e defina um usuário de serviço diferente. Se o proxy não estiver na porta 80, ajuste também o `ZENY_STORAGE_PUBLIC_ENDPOINT` (ex.: `http://localhost:8000`).

2. Suba (a primeira vez compila a imagem, o que leva alguns minutos) e confira a readiness pelo proxy:

   ```bash
   docker compose -f docker/compose.yaml up -d --build
   curl http://localhost/actuator/health/readiness    # {"status":"UP"}
   curl http://localhost/api/v1/meta/version
   curl -X POST http://localhost/api/v1/auth/login -H 'Content-Type: application/json' \
     -d '{"login": "admin", "password": "<ZENY_ADMIN_PASSWORD>", "device": {"id": "meu-pc", "name": "Meu PC"}}'
   ```

   O primeiro login do administrador volta com `passwordChangeRequired: true`: troque a senha em `POST /api/v1/auth/password` (com o `accessToken` em `Authorization: Bearer …`) e renove os tokens em `POST /api/v1/auth/refresh`.

   Para enviar e baixar um anexo (o logotipo, por exemplo), use o access token:

   ```bash
   curl -X POST http://localhost/api/v1/files -H "Authorization: Bearer $TOKEN" \
     -F purpose=hotel.logo -F file=@logo.png              # {"id": "…", "contentType": "image/png", …}
   curl http://localhost/api/v1/files/<id>/download-url -H "Authorization: Bearer $TOKEN"   # {"url": "…", "expiresAt": "…"}
   curl -o logo-baixado.png "<url>"                       # sem token: a URL assinada vale por 5 min
   ```

   Para um envio que pode ser repetido sem duplicar o anexo, acrescente `-F clientFileId=<uuid v7>`: a repetição devolve `200` com o mesmo anexo. Um logotipo enviado e não salvo no hotel em 24 h é apagado pelo expurgo de órfãos.

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
- Métricas: `GET /actuator/prometheus`, só na rede interna do compose (`http://api:8080/actuator/prometheus`; o proxy responde 404). Além das técnicas (JVM, HTTP, pool do banco), `zeny_outbox_publications{state="pending|failed|abandoned"}` e `zeny_outbox_publications_oldest_age_seconds` medem o outbox de eventos (reenvio com backoff de 1 min a 1 h, abandono depois de 24 h e expurgo das concluídas depois de 7 dias), `zeny_realtime_connections` conta as conexões do canal de tempo real (uma publicação abandonada só volta pelo reenvio manual descrito no `CLAUDE.md`, "Eventos e jobs"); regras de alerta em [`docker/prometheus/alerts.yaml`](docker/prometheus/alerts.yaml).
- Logs: no profile `prod`, uma linha JSON por evento (formato ECS), com `traceId` e `spanId` nas requisições e, nas autenticadas, `userId` e `deviceSessionId` (nunca login, senha ou token); o servidor aceita e propaga o cabeçalho `traceparent` (W3C). No `local`, texto legível.
- Valores do banco não vão para o log: numa violação de restrição (login repetido, por exemplo), nem o servidor (`db` do compose com `log_error_verbosity=terse` e `log_min_error_statement=panic`) nem a aplicação (driver com `logServerErrorDetail=false`) registram o DETAIL do PostgreSQL, que traz os valores da linha.

## API

REST/JSON sob `/api/v1` (ARCHITECTURE §7). O contrato [`api/openapi.yaml`](api/openapi.yaml) é gerado do código no build e versionado; o build falha se ele estiver desatualizado, e nunca se edita à mão. Erros seguem a RFC 9457 (`application/problem+json`) com `code` estável e `errors[]` por campo; listas paginam por cursor (`?cursor=…&limit=…`); alterações usam `ETag`/`If-Match` (`412` em conflito, `428` sem `If-Match`); operações com efeito financeiro ou externo exigem o cabeçalho `Idempotency-Key`, e a resposta fica guardada por 24h para as repetições; instantes saem em UTC com `Z`, datas como `YYYY-MM-DD` e dinheiro como string decimal. Fora do profile `prod`, o Swagger UI fica em `/swagger-ui.html`. Todo `/api/v1` exige o access token (`Authorization: Bearer …`), exceto o login, o refresh, `GET /api/v1/meta/version` e o download de anexo sensível pela URL assinada pelo servidor (`GET /api/v1/files/{id}/content?token=…`, autorizado pelo token da URL); sem token, a resposta é `401 UNAUTHENTICATED`. O que cada usuário pode fazer vem das roles atribuídas a ele, compostas a partir do catálogo de permissões (`GET /api/v1/permissions`), com escopo `OWN`/`ALL` e limites; as permissões efetivas não vão no token e aparecem em `GET /api/v1/auth/me`, e a falta de uma permissão responde `403 PERMISSION_DENIED`. No boot, o servidor sincroniza o catálogo declarado no código e cria os modelos de role da SPEC §3.1. O administrador gere usuários (`/api/v1/users`: criar, editar, atribuir roles, desativar, reativar, redefinir senha e PIN, ver e revogar sessões) e roles (`/api/v1/roles`: criar, editar, duplicar, desativar e reativar); ninguém concede permissão, escopo ou limite acima dos seus, só um administrador gere administradores e nada é apagado, só desativado. Toda escrita vai para a trilha de auditoria *append-only*, na mesma transação da escrita, e o papel do banco da aplicação não altera nem apaga a trilha. Cada entrada registra:

- quem agiu, quando e de qual sessão de dispositivo;
- a ação, o resultado (inclusive as falhas de login) e a entidade;
- os valores antes e depois, com senha, PIN, tokens e campos cifrados mascarados;
- a aprovação de superior associada, quando houver.

A trilha é consultada em `GET /api/v1/audit-entries`, filtrada por usuário, entidade e período, e exige a permissão `audit.read`.

Ações sensíveis (hoje, as escritas de usuários e roles, abrir, alterar e liberar um período fora de ordem, OOO, de uma UH, exportar e anonimizar os dados de um hóspede, estornar e ajustar um lançamento, reembolsar um pagamento e reabrir uma conta encerrada) pedem **aprovação de superior** quando o usuário não tem a permissão ou o limite: a resposta é `403 APPROVAL_REQUIRED`, com `action`, `permission`, `resourceRef` e, se houver, `limit` no problema. Outro usuário, com a permissão e um PIN, aprova em `POST /api/v1/auth/approvals` (login e PIN do aprovador e o motivo), e o solicitante repete a requisição com o cabeçalho `X-Approval-Token`, de uso único, válido por 2 min e só na mesma sessão. A ação concluída fica na trilha com a aprovação.

A configuração do hotel único fica em `/api/v1/hotel` (dados cadastrais e logotipo), `/policies`, `/tax-settings` e `/operational-settings`, com `If-Match` nas alterações (`hotel.settings.read` para consultar, `hotel.settings.manage` para editar), e a data operacional em `GET /api/v1/hotel/business-date`, que só avança no fechamento do dia (M6). Setores, colaboradores, turnos, padrões de escala (12x36, 6x1 e fixo), a geração da escala por período e as exceções lançadas à mão (folga, falta, férias, troca), que a geração nunca sobrescreve, ficam em `/api/v1/departments`, `/employees`, `/shifts`, `/schedule-patterns` e `/schedule` (`staff.employees.*` e `staff.schedule.*`; com escopo `OWN`, cada colaborador vê só a própria escala).

Operação (M2):

- **UHs e áreas:** tipos de UH, características, UHs (número único) e áreas comuns em `/api/v1/room-types`, `/room-features`, `/rooms` e `/areas`, nada apagado, só desativado. O status da UH tem dimensões independentes (ocupação, governança com a inspeção pendente e sinalização do hóspede), com as transições da SPEC §8.4, o histórico de toda mudança (quem, quando e origem) e as discrepâncias entre a recepção e a governança (`/api/v1/rooms/status`, `/rooms/{id}/status`, `/status-history`, `/discrepancies`). Os períodos fora de ordem (OOO, que tira a UH da venda e pede aprovação de superior) e fora de serviço (OOS, que a mantém vendável) ficam em `/api/v1/rooms/{id}/out-of-order` e `/api/v1/out-of-order`.
- **Tempo real:** `GET /api/v1/stream?topics=rooms,notifications` (Server-Sent Events, com o mesmo `Authorization: Bearer`) avisa as mudanças com só o tipo, o id e a versão do registro; na reconexão, o cabeçalho `Last-Event-ID` repõe o que se perdeu, ou o servidor responde `reset` e o cliente recarrega o estado.
- **Sincronização offline:** o app operacional baixa o que mudou em `GET /api/v1/sync/changes` (cursor opaco, com *tombstones* e sem dado pessoal de hóspede) e envia lotes de comandos em `POST /api/v1/sync/commands`, idempotentes pelo `commandId` (o reenvio devolve o resultado guardado sem aplicar de novo) e com o horário do aparelho corrigido pela defasagem do relógio.
- **Checklists:** modelos com versões publicadas imutáveis, itens condicionais, regras de aplicabilidade e ações por item (`/api/v1/checklist-templates`), execução online e offline com fotos (`/api/v1/checklist-executions`, comandos `checklists.start-execution` e `checklists.complete-execution`), rotinas por setor e escala geradas na virada da data operacional (`/api/v1/checklist-routines`) e relatórios de conformidade, itens mais reprovados e histórico por local (`/api/v1/checklist-reports`).
- **Notificações:** painel, preferências e push, na seção "Notificações e push (FCM)".

Hóspedes e contas (M3):

- **Hóspedes e empresas:** perfis com os campos da FNRH, preferências, VIP e lista de restrição em `/api/v1/guests` (busca por nome sem acento e por e-mail; por documento em `POST /api/v1/guests/search-by-document`, com o número no corpo), documentos com o número cifrado, buscado por índice cego e mostrado mascarado (o número inteiro só em `POST …/documents/{id}/reveal`, com permissão própria e cada acesso na trilha), raça/cor e deficiência da FNRH só gravadas (cifradas, nunca devolvidas) e empresas com CNPJ único em `/api/v1/companies`. Duplicados (o mesmo documento, pelo índice cego, ou o mesmo e-mail) aparecem em `GET /api/v1/guests/duplicate-candidates` e são mesclados em `POST /api/v1/guests/{id}/merge`: o perfil do caminho sobrevive, recebe o que lhe faltava, os documentos, o VIP e a restrição do outro, e o absorvido fica marcado como mesclado (nunca apagado), com o evento `GuestsMerged` para os módulos que guardam o id do hóspede. Nada é apagado, e a trilha não guarda dado pessoal em claro.
- **LGPD no perfil do hóspede:** consentimentos por finalidade, com o meio e o instante, em `/api/v1/guests/{id}/consents` (as restrições alimentares só se gravam com o consentimento ativo e saem quando ele é revogado); anexos (foto de documento ou de autorização de menor, reencodadas, e PDF) enviados por `POST /api/v1/files` com as finalidades sensíveis do `guests`, vinculados em `/api/v1/guests/{id}/attachments` e baixados só pela URL de curta duração do servidor, com cada acesso na trilha; e os direitos do titular, `POST /api/v1/guests/{id}/export` (o pacote completo, com os dados de cada módulo, os documentos inteiros, os dados sensíveis da FNRH e a lista dos anexos, na resposta) e `POST /api/v1/guests/{id}/anonymize` (apaga os dados pessoais, os documentos com o índice cego e os anexos, e mantém o id, os consentimentos e os dados agregados), as duas ações sensíveis, que sem a permissão pedem a aprovação de um superior. Para relatórios, a view `guests.report_guests` não tem dado pessoal.
- **Contas e lançamentos:** códigos de lançamento em `/api/v1/transaction-codes` (oito pré-carregados: diária, multas de cancelamento e de no-show, taxa de serviço, A&B, lavanderia, taxa de turismo e avulso; o tipo e o grupo de receita são fixos, nada é apagado) e contas em `/api/v1/folios` para qualquer dono (reserva, grupo, evento, empresa ou avulsa, por um id opaco), com empresa pagadora e limite de crédito opcionais e o saldo. Os lançamentos (`POST /api/v1/folios/{id}/postings`) levam a data operacional do hotel e nunca mudam nem são apagados (o banco recusa); a correção é por estorno (`…/postings/{postingId}/reverse`: o inverso de tudo o que resta do lançamento, uma vez só) ou ajuste parcial (`…/adjust`), vinculados e com motivo, ações sensíveis com valor máximo por role, que acima dele pedem a aprovação de um superior. Abrir conta, lançar, estornar e ajustar exigem `Idempotency-Key`.
- **Roteamento, transferência e divisão:** regras de roteamento em `/api/v1/routing-rules` (conta de origem, conta de destino, um conjunto de códigos **ou** um grupo de receita e o período em datas de negócio; editadas com `If-Match`, desativadas e reativadas, nunca apagadas) levam o lançamento, no momento em que é feito (pela API ou pelos outros módulos), à conta de destino, com a origem registrada; a regra por código prevalece sobre a por grupo, a cadeia A → B → C segue até a última conta, e regras sobrepostas do mesmo nível (`409`), que formariam ciclo (`422`) ou com destino encerrado (`409`) são recusadas. A transferência (`POST /api/v1/folios/{id}/postings/transfer`) e a divisão por percentual ou por valor (`…/postings/{postingId}/split`, sem perder centavos) nunca mudam o lançamento: cada parte movida vira uma saída na conta de origem e uma entrada na de destino, vinculadas; as duas exigem `Idempotency-Key`.
- **Taxa de serviço:** o item lançado num código que aceita a taxa (de saída, o de alimentos e bebidas) leva junto, separada na conta e vinculada a ele, a taxa de serviço, com o percentual informado pelo módulo de origem, senão o do código, senão o padrão do hotel (o percentual aplicado fica guardado). A taxa acompanha o item no estorno, no ajuste, na transferência e na divisão, e o cliente pode pedir para tirá-la: `POST /api/v1/folios/{id}/postings/{postingId}/remove-service-charge` a estorna com o motivo registrado, sem aprovação de superior. A taxa é repasse aos empregados, não receita do hotel.
- **Caixa:** pontos de caixa em `/api/v1/cash-points` (recepção, restaurante, bar…; nome único, fechamento cego ligado por padrão, nada é apagado) e turnos de caixa em `/api/v1/cashier-shifts`: cada usuário abre o próprio turno num ponto, com o fundo de troco, e tem no máximo um aberto (em qualquer ponto); registra sangrias e suprimentos em dinheiro, com motivo, que nunca mudam (`…/{id}/movements`); e fecha com a contagem declarada por forma de pagamento (`…/{id}/close`), que devolve o relatório de fechamento com o esperado e a diferença por forma (também em `…/{id}/report`). No fechamento cego, o operador só vê o valor esperado depois de contar; quem confere os turnos o vê sempre e, quando o fechamento tem diferença, recebe uma notificação no painel (sem dado pessoal; o operador não a recebe). Quem só opera vê apenas os próprios turnos. Abrir, movimentar e fechar exigem `Idempotency-Key`; todo recebimento e devolução exige o turno aberto do usuário.
- **Pagamentos, depósitos e reembolsos:** `POST /api/v1/folios/{id}/payments` registra um pagamento feito fora do sistema (dinheiro, Pix, cartão de crédito ou débito numa maquininha não integrada, transferência ou outra forma), com o NSU e a autorização e, no cartão, só a bandeira e os 4 últimos dígitos (o número completo e o CVV nunca entram, e um NSU com cara de número de cartão é recusado). Uma conta aceita vários pagamentos, o valor pode passar do devido (a conta fica credora), o troco é calculado no dinheiro a partir do valor entregue e `deposit: true` marca o depósito feito antes da chegada. Todo recebimento sai do turno de caixa aberto de quem recebe (sem turno, `422 CASHIER_SHIFT_REQUIRED`) e entra no esperado do turno, na forma. O reembolso (`…/payments/{paymentId}/refund`) é sempre na forma do pagamento, com motivo, até o que ainda não foi devolvido, e sai do turno de quem devolve; é uma ação sensível com valor máximo por role, que sem a permissão ou acima dele pede a aprovação de um superior. Pagamentos e reembolsos nunca mudam nem são apagados, e os dois POSTs exigem `Idempotency-Key`. Por dentro, cada recebimento passa por uma intenção de pagamento do módulo `payments` (provedor `MANUAL`, confirmado na hora), que publica `PaymentSucceeded`: é por aí que o Pix e o cartão integrados entram no M7.
- **Caução:** `POST /api/v1/folios/{id}/card-pre-authorizations` registra a caução (pré-autorização de cartão de crédito) feita numa maquininha não integrada: valor, validade, autorização, NSU e, do cartão, só a bandeira e os 4 últimos dígitos. A caução não entra no saldo da conta. A captura (`…/{preAuthorizationId}/capture`), total ou parcial e repetível até o valor autorizado, gera um pagamento no cartão de crédito, que entra no saldo e no turno de caixa aberto de quem captura (sem turno, `422 CASHIER_SHIFT_REQUIRED`); acima do autorizado, `422 CAPTURE_EXCEEDS_AUTHORIZED`. A liberação (`…/release`) registra que a sobra foi liberada e encerra a caução. Vencida a validade, a caução aparece como `EXPIRED` e não é mais capturada (`422 PRE_AUTHORIZATION_EXPIRED`), mas a sobra ainda pode ser liberada. `GET /api/v1/card-pre-authorizations/expiring?from=&to=` lista as que vencem no período. Os POSTs exigem `Idempotency-Key`.
- **Encerramento, reabertura e extrato:** `POST /api/v1/folios/{id}/close` encerra a conta só com o saldo exatamente zero (`409 FOLIO_BALANCE_NOT_ZERO`, mesmo por um centavo, devedor ou credor) e sem caução ativa ainda dentro da validade (`409 FOLIO_HAS_ACTIVE_PRE_AUTHORIZATION`: capture ou libere antes). Encerrada, a conta não recebe lançamento, pagamento, reembolso, caução nem transferência e não troca o pagador nem o limite (`409 FOLIO_CLOSED`). A reabertura (`POST …/reopen`, com o motivo) é uma ação sensível: sem a permissão, pede a aprovação de um superior, e fica registrada com o motivo e o aprovador. `GET /api/v1/folios/{id}/statement` devolve o extrato em JSON, com os lançamentos (inclusive estornos, ajustes, transferências e taxa de serviço) e os pagamentos agrupados por data operacional, os totais do dia, as cauções e o saldo, que é exatamente a soma das linhas; com `?audience=GUEST`, a prévia para o hóspede, sem usuários, motivos, aprovações, NSU nem autorização. O PDF do extrato vem no M5. Para relatórios, as views `billing.report_folios`, `report_postings`, `report_payments` (com a marca de depósito), `report_cashier_shifts` e `report_cashier_counts` não têm texto livre nem dado de cartão além da bandeira e dos 4 últimos dígitos.
- **Planos tarifários, políticas e pacotes** (M4 E01, módulo `rates`): políticas de cancelamento em `/api/v1/cancellation-policies` (prazo em horas antes do horário de check-in da data de chegada e a multa em percentual da estadia, número de diárias ou valor fixo), políticas de garantia em `/api/v1/guarantee-policies` (nenhuma, cartão ou depósito de X% até N dias antes da chegada), pacotes em `/api/v1/packages` (preço por noite, por estadia ou por pessoa/noite, lançado todo dia ou na chegada, incluso ou adicional, sempre ligado a um código de lançamento de consumo ativo do `billing`) e planos tarifários em `/api/v1/rate-plans` (código, nome, descrição, regime de alimentação `ROOM_ONLY`/`BREAKFAST`/`HALF_BOARD`, canais `DIRECT`/`WEBSITE`/`OTA`, tipos de UH, pacotes, as duas políticas e a categoria `NORMAL`/`COURTESY`/`HOUSE_USE`, fixa desde a criação). Todos com lista por cursor, `ETag`/`If-Match` na edição, desativação e reativação por ação e nada apagado (sem `DELETE`; o banco recusa). Tipo de UH ou código de lançamento inexistente: `400` com o campo; desativado: `422`. Política ou pacote em uso por um plano ativo não é desativado (`422 …_IN_USE`). As políticas não são versionadas: a edição vale na hora para as reservas criadas depois, e cada reserva guarda os termos da política vigente quando foi feita (RN-15, E07). Consultar exige `rates.plans.read`; escrever, `rates.plans.manage`. O catálogo já tem a permissão do desconto (`rates.discounts.apply`, sensível, com o percentual máximo `maxPercent`: Gerente sem teto, Recepcionista até 10%), conferida nas reservas a partir da E07.
- **Preços, planos derivados e temporadas** (M4 E02, módulo `rates`): preços por tipo de UH e data (diária de 1 e 2 adultos, adulto extra e criança por faixa etária do hotel, ou um preço único de criança se o hotel não diferencia idades), gravados em massa por período de até 366 dias ou por temporada, só nos dias da semana escolhidos (`POST /api/v1/rate-plans/{id}/prices/bulk-update` e `/bulk-remove`), e lidos em `GET /api/v1/rate-plans/{id}/prices?from=&to=`. Quando o hotel muda as faixas etárias, os preços de criança gravados nas faixas antigas aparecem como obsoletos e deixam de valer até serem regravados. Temporadas nomeadas em `/api/v1/seasons` servem de atalho de período. Um plano derivado (`PUT /api/v1/rate-plans/{id}/derivation`) tem os preços do plano base com um percentual ou um valor (por UH ou por pessoa), calculados na leitura: a mudança do base aparece nele na hora; ciclo de derivação é recusado (`422 RATE_PLAN_DERIVATION_CYCLE`). Gravar preço onde não havia exige `rates.prices.manage`; alterar ou retirar uma tarifa já publicada (ou trocar a derivação de um plano em venda) é ação sensível: sem `rates.prices.override`, pede a aprovação de um superior. Toda mudança publica o evento `RatesChanged`, com os planos derivados afetados, para o channel manager do M8.
- **Restrições de venda** (M4 E03, módulo `rates`): por plano, data e tipo de UH, ou gerais do plano (valem para todos os tipos): fechado para venda, CTA (fechado para chegada), CTD (fechado para saída), estadia mínima e máxima (noites) e antecedência mínima e máxima (dias). Gravadas em massa por período de até 366 dias ou por temporada, só nos dias da semana escolhidos, mexendo só nos campos pedidos (`set`) ou retirando-os (`clear`) em `POST /api/v1/rate-plans/{id}/restrictions/bulk-update`, e lidas em `GET /api/v1/rate-plans/{id}/restrictions?from=&to=`, com a restrição efetiva de cada tipo. A restrição do tipo substitui a geral campo a campo (um campo não informado herda o da geral; `closed: false` no tipo abre uma data fechada na geral). Semântica: fechado vale em qualquer noite da estadia; CTA e CTD só na data exata de chegada ou de saída; estadia e antecedência pelas da data de chegada. Mínimo maior que o máximo é recusado (`400 RESTRICTION_RANGE_INVALID` no pedido; `422 RESTRICTION_RANGE_CONFLICT` com o que já está gravado). Consultar exige `rates.plans.read`; gravar, `rates.restrictions.manage`. Toda mudança publica o evento `RestrictionsChanged` para o channel manager do M8.
- **Preço e vendabilidade de uma estadia** (M4 E04, módulo `rates`, sem endpoint próprio: a cotação da E06 e as reservas da E07 o usam pela API pública `RatesApi`): o preço de uma estadia (plano, tipo de UH, chegada, saída, adultos e idade de cada criança) noite a noite, com a diária de 1 ou 2 adultos, o adulto extra, cada criança pelo preço da faixa etária atual do hotel e os pacotes do plano (o incluso é parte da diária e aparece separado da hospedagem; o adicional soma ao total; o por estadia entra uma vez, na chegada), com o total sempre igual à soma das noites; e se a estadia pode ser vendida num canal, com todos os motivos de recusa em código estável (plano desativado, canal, tipo fora do plano ou desativado, ocupação acima da máxima do tipo, chegada antes da data operacional, antecedência, CTA na chegada, CTD na saída, estadia mínima e máxima da chegada, noite fechada e falta de preço). A antecedência conta da data operacional do hotel, nunca da data do relógio.
- **Inventário por tipo de UH e data** (M4 E05, módulo `reservations`): contadores por tipo e data, mantidos da data operacional até 730 dias à frente (um job diário estende o horizonte): o total de UHs ativas, as vendidas a reservas e as bloqueadas a grupos (a partir da E07 e da E11), as UHs em OOO (o OOS não conta) e o limite de overbooking, que nasce zero (overbooking proibido). O total acompanha o cadastro de UH e o OOO acompanha os períodos fora de ordem, ambos refeitos a partir do cadastro de UHs logo depois de cada mudança (por eventos), e toda mudança publica o evento `AvailabilityChanged` para o channel manager do M8. A disponibilidade (total − vendidas − bloqueadas − OOO + limite) pode ficar negativa, e a data aparece como sobrevendida. Consulta em `GET /api/v1/room-type-inventory?roomTypeId=&from=&to=` (`reservations.availability.read`); o limite de overbooking por tipo e data em `POST /api/v1/room-type-inventory/overbooking-limit` e a recontagem, que repara qualquer divergência, em `POST /api/v1/room-type-inventory/recount` (`reservations.inventory.manage`). Duas reservas simultâneas pela última unidade: só uma consegue (`409 ROOM_TYPE_UNAVAILABLE` para a outra).
- **Disponibilidade em calendário e cotação** (M4 E06, módulo `reservations`, com os preços e as restrições do `rates`): `GET /api/v1/availability?from=&to=&roomTypeIds=&ratePlanId=` mostra, por tipo de UH e data (até 366 dias), os contadores do inventário (UHs ativas, vendidas, bloqueadas, em OOO, o limite de overbooking e o disponível), as datas sobrevendidas (disponível negativo) e, com um plano, os preços efetivos (diária de 1 e 2 adultos, adulto extra e cada faixa de criança; num plano derivado, já ajustados) e as restrições efetivas de cada data. `GET /api/v1/quotes?arrival=&departure=&adults=&childAges=&channel=` cota a estadia em todos os tipos de UH e planos ativos (ou nos pedidos em `roomTypeIds` e `ratePlanIds`): só oferece o plano **vendável** no canal (ativo, no canal, com o tipo, a ocupação, a antecedência, as restrições e o preço) no tipo com ao menos uma UH livre em **todas** as noites, com o total e o detalhe por noite (diária, adultos extras, crianças, pacotes inclusos e adicionais), sempre somando o total; com `includeUnavailable=true`, também as combinações de fora com os motivos (`SALES_CHANNEL_NOT_ALLOWED`, `CLOSED_TO_ARRIVAL`, `ROOM_TYPE_UNAVAILABLE`…). As duas exigem `reservations.availability.read`. A cotação de 7 noites de 5 tipos e 5 planos num hotel de 30 UHs com 730 dias de dados leva de 20 a 35 ms (p95 abaixo de 50 ms no teste local; o RNF-06 pede menos de 300 ms).
- **Reservas** (M4 E07, módulo `reservations`): `POST /api/v1/bookings` (exige `Idempotency-Key` e `reservations.reservations.manage`) cria uma reserva agrupadora com uma reserva por UH, sob o mesmo titular (um hóspede do cadastro) e o canal que a origem define (`FRONT_DESK`, `PHONE`, `EMAIL`, `WHATSAPP` e `OTHER` são venda direta; `WEBSITE` o site; `OTA` o channel manager). Cada reserva tem o tipo de UH, o plano, a chegada e a saída (iguais = **day use**, que não ocupa a noite no inventário e usa a tarifa da data), os adultos e a idade das crianças, o segmento (`LEISURE`, `BUSINESS`, `EVENT`, `OTHER`), os pedidos especiais e os hóspedes (o principal e os acompanhantes; sem eles, o titular). O plano precisa ser vendável no canal (senão `422` com o motivo do `rates`); o preço é calculado e **congelado por noite** (mudar a tarifa depois não muda a reserva), e a diária de uma noite pode ser editada: abaixo da calculada é **desconto**, que exige `rates.discounts.apply` dentro do limite `maxPercent` (a Recepcionista até 10%) ou a aprovação de um superior (`403 APPROVAL_REQUIRED`). Os termos das políticas de cancelamento e de garantia são copiados na reserva (editar a política depois não a muda). Cada noite toma uma UH do tipo no inventário, na mesma transação (sem UH livre, `409 ROOM_TYPE_UNAVAILABLE` e nada é gravado), e cada reserva ganha a sua conta no `billing`. Sem garantia exigida pela política, a reserva nasce confirmada; com garantia, pré-reserva com a validade do hotel (48 h por padrão). O hóspede mesclado vira o sobrevivente, o anonimizado é recusado e a resposta traz o alerta da lista de restrição. `GET /api/v1/bookings/{id}` e `GET /api/v1/reservations/{id}` (`reservations.reservations.read`) consultam. Quando dois perfis de hóspede são mesclados, as reservas passam ao sobrevivente; o hóspede com reserva ativa não pode ser anonimizado, e a exportação dos dados do titular traz as reservas dele.
- **Alteração, cancelamento e reativação de reservas** (M4 E08, módulo `reservations`): `PUT /api/v1/reservations/{id}` (com `If-Match` e `reservations.reservations.manage`) troca as datas, o tipo de UH, a ocupação, o plano, o segmento e os pedidos especiais de uma pré-reserva ou confirmada; recalcula **só as noites afetadas** (com só as datas mudadas, as noites novas; com o tipo, o plano ou a ocupação, todas), mantendo o preço congelado das outras, confere a venda só no que mudou e devolve as UHs antigas e toma as novas no inventário na mesma transação (sem UH livre, `409` e nada muda). `POST /api/v1/reservations/{id}/cancel` (exige `Idempotency-Key`) cancela: a multa segue a política copiada na reserva (a partir do prazo antes do horário de check-in da chegada: percentual das diárias, número de diárias ou valor fixo) e é lançada na conta da reserva no código de multa de cancelamento, uma vez só (reativar e cancelar de novo não cobra outra), ou isentada por quem tem `reservations.penalties.waive` ou com a aprovação de um superior (`403 APPROVAL_REQUIRED`); as UHs voltam ao inventário e a conta continua aberta (a devolução de um depósito é um reembolso na conta). `POST /api/v1/reservations/{id}/reactivate` (exige `Idempotency-Key`) volta uma reserva cancelada ou no-show a confirmada, com `reservations.reservations.reactivate` ou a aprovação de um superior, se houver UH livre (`409` senão). Transição fora da máquina de estados: `409 RESERVATION_TRANSITION_NOT_ALLOWED`. Cada mudança fica no histórico da reserva, que não muda depois (`GET /api/v1/reservations/{id}/history`, `reservations.reservations.read`), e publica os eventos `ReservationChanged`, `ReservationCancelled` ou `ReservationReactivated`.
- **Pré-reserva, garantia e expiração** (M4 E09, módulo `reservations`): a reserva cuja política de garantia pede cartão ou depósito nasce pré-reserva, válida por `preReservationExpiry` (48 h por padrão) desde a criação. `POST /api/v1/reservations/{id}/confirm` (exige `Idempotency-Key` e `reservations.reservations.manage`) confirma a pré-reserva ainda válida com a garantia que a política copiada pede: `CARD` (o cartão informado pelo hóspede; nenhum dado do cartão entra no sistema até a integração do M7) ou `DEPOSIT`, conferido na conta da reserva: os pagamentos marcados como depósito menos os reembolsos deles precisam cobrir o percentual da política sobre o total da reserva (`422 DEPOSIT_INSUFFICIENT` com o exigido e o recebido; outra garantia, `422 GUARANTEE_METHOD_NOT_ACCEPTED`; validade vencida, `422 PROVISIONAL_RESERVATION_EXPIRED`). A cada 5 minutos, um job cancela as pré-reservas vencidas, sem multa, com o motivo `EXPIRED`, e devolve as UHs ao inventário. Uma pré-reserva vencida também não é mais alterada. Uma reserva confirmada cuja política exige garantia mas que ficou sem ela (trocada para um plano com garantia, ou reativada depois de expirar) recebe a garantia pelo mesmo `POST …/confirm`, sem mudar de situação. `GET /api/v1/reservations/pending-guarantees?from=&to=` (`reservations.reservations.read`) lista as pré-reservas e as reservas sem garantia que vencem no intervalo (a pré-reserva na validade; a sem garantia no horário de check-in do dia do prazo do depósito ou, no cartão, da chegada; sem o intervalo, até agora mais a antecedência do alerta do hotel), com o depósito exigido e o já recebido.
- **Alocação de UH** (M4 E10, módulo `reservations`): `POST /api/v1/reservations/{id}/assign-room` (`reservations.assignments.manage`, que o Gerente e a Recepcionista recebem) aloca uma UH do tipo da reserva a uma pré-reserva ou confirmada, pela estadia inteira, ou troca a UH já alocada; a UH precisa estar ativa e sem OOO em nenhuma noite (o OOS não impede). Uma UH nunca fica com duas reservas no mesmo período: a recusa é `409 ROOM_ALREADY_ASSIGNED`, e o banco garante a regra mesmo contornando a aplicação. A saída de uma reserva e a chegada de outra no mesmo dia dividem a UH; o day use também divide a UH com quem sai e com quem chega na data, e só não cabe com uma estadia que atravessa a data ou com outro day use. `POST …/unassign-room` solta a UH; `POST …/do-not-move` marca "não mover" (a UH não é trocada nem solta, nem por uma alteração da reserva, até a marca sair). `GET …/room-suggestions?featureIds=` sugere as UHs livres do tipo, sem OOO, com as de OOS por último e, antes, as que têm as características pedidas e o andar preferido pelo hóspede principal. O cancelamento e a expiração soltam a UH; a alteração das datas a mantém se ela continuar ativa, do tipo e livre (senão a solta) e a do tipo a solta. Quando uma UH alocada entra em OOO, as reservas atingidas ficam marcadas (até nenhum OOO da UH atingir mais a estadia) "requer realocação" (`GET /api/v1/room-assignments/reallocation-required`) e quem pode alocar recebe uma notificação com o número da UH, as datas e os códigos das reservas, sem dado de hóspede. `GET …/room-assignments` mostra o histórico das UHs de uma reserva.
- **Grupos e bloqueios** (M4 E11, módulo `reservations`): `POST /api/v1/room-blocks` (`reservations.blocks.manage`, Gerente e Comercial/Eventos; `Idempotency-Key`) cria o bloqueio de um grupo: o nome, o titular (um hóspede **ou** uma empresa), o plano tarifário (de onde vêm as políticas de cancelamento e de garantia), a data de corte e as unidades por tipo de UH e período de noites, com a tarifa do grupo por UH e noite. As unidades saem da disponibilidade na hora (sem disponibilidade, `409 ROOM_TYPE_UNAVAILABLE` e nada é gravado), todo tipo de UH das unidades precisa estar no plano do bloqueio (`422 ROOM_TYPE_NOT_IN_PLAN`), e a conta master do grupo é aberta no `billing` (dono `GROUP`; a empresa titular como pagadora; o roteamento das contas das reservas para ela segue pela API de regras do M3). `GET /api/v1/room-blocks` e `GET …/{id}` (`reservations.blocks.read`) mostram as unidades por noite com o pick-up (`pickedUp`, `remaining`); `PUT …/{id}` (`If-Match`) troca o nome, a data de corte e as unidades das noites a partir da data operacional, nunca abaixo do já convertido (`422 ROOM_BLOCK_BELOW_PICK_UP`). `POST …/{id}/pick-up` e `POST …/{id}/rooming-list` (`reservations.blocks.pickup`, Gerente, Recepcionista e Comercial/Eventos; `Idempotency-Key`: a mesma lista repetida com a mesma chave não cria nada) convertem unidades em reservas até a data de corte: cada noite passa uma unidade do bloqueio para vendida (a disponibilidade não muda), a diária é a tarifa do grupo congelada e a reserva traz `blockId`; o tipo de UH, o plano e as datas dela não mudam depois (só a ocupação). O cancelamento (ou a expiração) de uma reserva do pick-up devolve a unidade ao bloqueio enquanto ele está ativo. A liberação do não utilizado depois da data de corte é da `ReservationsApi.releaseUnused`, que o fechamento do dia (M6) vai chamar; antes da data de corte, `409 ROOM_BLOCK_CUTOFF_NOT_REACHED`.

Como escrever um controller, lançar erro de domínio, paginar, marcar um endpoint idempotente, declarar e checar permissões e auditar um caso de uso: [`CLAUDE.md`](CLAUDE.md).

## Integração contínua

O workflow [`.github/workflows/ci.yml`](.github/workflows/ci.yml) (GitHub Actions) roda em todo push na `main` e em todo pull request. São três jobs em paralelo, e qualquer falha reprova a execução:

- **Build, testes e contrato:** com JDK 25 e cache do Gradle, roda `./gradlew build` (compilação, testes unitários e de integração com PostgreSQL e MinIO pelo Testcontainers, verificação de módulos do Spring Modulith, ArchUnit, detekt, ktlint, cobertura e conferência do `api/openapi.yaml`) e `./gradlew checkOpenApi`. Publica como artefatos a documentação dos módulos (`spring-modulith-docs`) e o contrato (`openapi`); se falhar, também os relatórios dos testes.
- **Compatibilidade da API:** o [oasdiff](https://github.com/oasdiff/oasdiff) compara o `api/openapi.yaml` com o da `main` (num PR, a ponta da branch de destino; num push, o commit anterior ao push ou, sem ele, o commit pai) e reprova *breaking changes* em `/api/v1`, como endpoint, parâmetro ou propriedade removidos, tipo trocado ou campo que passa a ser obrigatório. Mudança incompatível vai para `/api/v2`; remover um endpoint v1 só passa se ele já estava marcado como `deprecated`. O mesmo teste roda localmente com `.github/scripts/check-api-breaking.sh`.
- **Imagem Docker:** constrói `docker/Dockerfile` sem publicar.

A CI não publica imagem nem faz deploy (M8) e não usa segredos. As actions ficam fixadas por SHA de commit, e o Dependabot ([`.github/dependabot.yml`](.github/dependabot.yml)) abre uma vez por mês um PR que as atualiza. Como o trabalho é direto na `main`, uma falha que entra por push só aparece depois, com a CI vermelha, sem ser bloqueada: rode `./gradlew build` e o `check-api-breaking.sh` antes de subir.

## Convenções de módulo

O pacote base é `com.zeny.hotel`. Cada pacote de primeiro nível abaixo dele é um módulo do Spring Modulith, correspondente a um contexto da SPEC (ARCHITECTURE §3 e §4). `shared` é o kernel compartilhado.

```
src/main/kotlin/com/zeny/hotel/
├── ZenyHotelApplication.kt
├── shared/                  # kernel compartilhado: Money, Percent, IDs, CPF/CNPJ, Address, cifragem
├── iam/ audit/ hotel/ staff/ files/   # módulos de plataforma do M1 (ARCHITECTURE §3.1)
├── rooms/ checklists/ notifications/  # módulos do M2
├── guests/ billing/ payments/  # módulos do M3 (hóspedes, empresas e LGPD; contas, lançamentos, roteamento, taxa de serviço, caixa, pagamentos, caução, encerramento, extrato e views; intenção de pagamento)
├── rates/                   # módulo do M4 (planos tarifários, políticas, pacotes, preços por data e ocupação, derivação, temporadas, restrições de venda e o preço e a vendabilidade de uma estadia)
├── reservations/            # módulo do M4 (inventário por tipo de UH e data, disponibilidade em calendário e cotação, reservas agrupadoras e reservas, alteração, cancelamento e reativação, pré-reserva com garantia e expiração, alocação de UH, grupos e bloqueios)
└── <módulo>/
    ├── <Módulo>Api.kt       # raiz do pacote = API pública: interfaces, DTOs e eventos
    ├── domain/              # agregados e regras, sem Spring
    ├── application/         # casos de uso, transações e autorização
    ├── infrastructure/      # JPA, adaptadores e jobs
    └── web/                 # controllers REST, que só falam com application
```

Os limites são verificados em teste a cada build:

- `ModularityTest`: `ApplicationModules.verify()` barra ciclos e acesso a pacotes internos de outro módulo, e gera a documentação dos módulos em `build/spring-modulith-docs/`.
- `LayerArchitectureTest`: regras ArchUnit das camadas internas. `domain` não depende de Spring, da API JPA/Hibernate (só anotações) nem das outras camadas; `web` não usa `domain` nem `infrastructure`; um módulo não usa as camadas internas de outro; todo caso de uso de escrita de `application` leva `@Audited`.
- `LayerRulesTest`: prova, com classes de exemplo em `src/test/kotlin/archfixtures/`, que cada regra falha diante de uma violação.

## Roadmaps

O trabalho segue os roadmaps em [`docs/roadmaps/`](docs/roadmaps/README.md). Cada etapa tem um prompt pronto para o Claude Code, critérios de aceite e uma seção "Evidências e handoff" que registra o que foi feito.
