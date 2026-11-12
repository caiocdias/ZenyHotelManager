# Compose isolado do gate

Ferramentas do gate de marco (a partir do M5 E12): sobem o ambiente completo do `docker/compose.yaml`, no profile `prod`, num projeto Compose próprio, com segredos sorteados só para ele e uma FNRH de mentira (WireMock) no lugar do Serpro. Ficam fora da imagem (o `.dockerignore` é uma lista de permissão) e fora da CI.

> **Nunca use o `docker/.env`.** O gate não lê nem toca o env do usuário: o `gate-env.sh` gera outro, fora do repositório (ele recusa, com saída 2, um destino dentro do projeto ou de qualquer repositório git, como o checkout principal acima de um worktree). Use sempre `-p` próprio, a tag de imagem do gate (`zeny-hotel-manager:gate`, nunca a `:dev`) e `down -v` no fim, também quando algo falhar.

## Requisitos

`docker` (com o Compose v2), `bash`, `curl`, `jq`, `openssl` e o `realpath` do coreutils (GNU). O `up --build` compila a aplicação dentro do Docker: rode um build pesado por vez: antes do `up --build`, confira a memória disponível (`free -g`) e que não há build pesado em andamento (Gradle ou `docker build`). Nunca rode `./gradlew --stop`: ele derruba os daemons de outras sessões do usuário.

## Arquivos

| Arquivo | Para quê |
|---|---|
| `gate-env.sh <dir> <porta>` | Grava `<dir>/gate.env` (modo 600) a partir do `docker/.env.example`, com as chaves de cifragem e do JWT e as senhas do banco, do MinIO e do admin sorteadas pelo `openssl`; `ZENY_PROFILE=prod`, proxy em `127.0.0.1:<porta>`, `ZENY_STORAGE_PUBLIC_ENDPOINT=http://localhost:<porta>` e `ZENY_FCM_CREDENTIALS` vazia. Imprime só o caminho, nunca valores. |
| `compose.gate.yaml` | Override do `docker/compose.yaml`: a `api` com a imagem `zeny-hotel-manager:gate` e as duas URLs da FNRH (`zeny.fnrh.homologation-url` e `zeny.fnrh.production-url`, pelas variáveis `ZENY_FNRH_HOMOLOGATIONURL` e `ZENY_FNRH_PRODUCTIONURL`) apontadas para `http://fnrh-mock:8080/FNRH_API/rest/v2`: o compose do gate nunca chama o Serpro real, em nenhum ambiente; o serviço `fnrh-mock` (WireMock 3.13.2 fixado por digest, sem porta publicada, com `--global-response-templating`). |
| `fnrh-mock/mappings/*.json` | Os stubs da API v2.4, com os mesmos contratos do `FnrhTestServer` dos testes (ver abaixo). |
| `m5-roteiro.sh <base-url> <projeto> <env-file>` | O roteiro `curl` do gate do M5, pelo proxy (ver [Roteiro do M5](#roteiro-do-m5)). |
| `run-gate.sh <workdir> <porta> [projeto]` | O ciclo completo: gera o env, sobe, espera o readiness, roda o roteiro e faz `down -v` por `trap`, também em falha (ver [Uso](#uso)). |

## Uso

Na raiz do projeto (ou da cópia limpa), com um diretório de trabalho **fora** do repositório e uma porta livre:

```bash
docker/gate/run-gate.sh /caminho/fora/do/repositorio 18112 zeny-gate   # ex.: o scratchpad da sessão
```

O `run-gate.sh`:

1. recusa (saída 2) o projeto `zeny` (o do usuário), um nome de projeto inválido e um projeto que já tenha contêiner, volume ou rede (o roteiro precisa de um banco novo, e o `down -v` do fim nunca apaga o que ele não subiu);
2. gera `<workdir>/gate.env` pelo `gate-env.sh` (que recusa um `<workdir>` dentro do projeto ou de um repositório git);
3. arma o `trap … EXIT` (também em erro e em Ctrl+C) que faz `down -v --remove-orphans`, apaga o `gate.env` e conta as sobras do projeto (`sobras: contêineres 0, volumes 0, redes 0`);
4. roda `config -q` e `up -d --build`, espera o readiness (até 300 s) e mostra os serviços;
5. roda o `m5-roteiro.sh` (a saída fica em `<workdir>/m5-roteiro.out`) e conta os níveis do log da `api`;
6. sai com o código do roteiro (`0` sem falhas, `1` com falhas) ou `1` se o ambiente não subiu.

Passo a passo, à mão (o `down -v` fica com você, também em falha):

```bash
GATE_DIR=/caminho/fora/do/repositorio
PORT=18112
ENV_FILE=$(docker/gate/gate-env.sh "$GATE_DIR" "$PORT")
DC=(docker compose -p zeny-gate --env-file "$ENV_FILE" -f docker/compose.yaml -f docker/gate/compose.gate.yaml)
trap '"${DC[@]}" down -v --remove-orphans; rm -f -- "$ENV_FILE"' EXIT

"${DC[@]}" config -q                       # confere a composição, sem imprimir valores
"${DC[@]}" up -d --build
curl -fsS "http://localhost:$PORT/actuator/health/readiness"   # {"status":"UP"}
docker/gate/m5-roteiro.sh "http://localhost:$PORT" zeny-gate "$ENV_FILE"   # o roteiro, pelo proxy
```

Não use `"${DC[@]}" config` sem `-q`: ele imprime o env resolvido, com os segredos. O login inicial do admin é `admin`, com a senha `ZENY_ADMIN_PASSWORD` do `gate.env`; um roteiro a lê do arquivo para uma variável e nunca a imprime.

O `fnrh-mock` só é alcançável na rede do projeto (`<projeto>_default`), em `http://fnrh-mock:8080`. Para conferir o que ele recebeu, leia o diário do WireMock de dentro da rede, pelo `wget` da imagem da `api` (o mesmo do `HEALTHCHECK`), e deixe o `jq` contar e casar, imprimindo só o método e um prefixo fixo do caminho:

```bash
"${DC[@]}" exec -T api wget -qO- http://fnrh-mock:8080/__admin/requests |
    jq -r '.requests[].request | "\(.method) \(.url | capture("^/FNRH_API/rest/v2/(?<p>[a-z_]+)").p)"' | sort | uniq -c
"${DC[@]}" exec -T api wget -qO- http://fnrh-mock:8080/__admin/requests/unmatched | jq '.requests | length'   # 0
```

> **O diário guarda a credencial Basic da FNRH** (no cabeçalho `Authorization` de cada pedido), além dos dados enviados. Nunca imprima a URL inteira (as consultas de pessoa levam o número do documento no caminho, `/pessoas/documento/…`) nem os cabeçalhos: filtre e conte com o `jq`, como acima.

O diário vem do mais novo para o mais antigo. O cliente HTTP da `api` (o do JDK) negocia HTTP/2 em texto puro com o WireMock depois da primeira chamada: nos pedidos seguintes, os nomes dos cabeçalhos aparecem em minúsculas no diário (`authorization`, `cpf_solicitante`, `content-type`). Ao conferir cabeçalhos com o `jq`, compare os nomes sem diferenciar maiúsculas (o casamento dos stubs já é assim).

## O que a FNRH de mentira aceita

Todos os caminhos ficam sob `/FNRH_API/rest/v2`, como na URL de homologação do Serpro. Pedido fora destes contratos cai no `404` do WireMock, e a submissão fica com a falha visível na API (`GET /api/v1/fnrh/submissions`).

- **Configuração da FNRH** (`PUT /api/v1/fnrh/settings`, administrador): `environment` **`HOMOLOGATION`** (as duas URLs apontam para o mock, então `PRODUCTION` também não sai da rede do projeto; o roteiro usa homologação, como a operação real antes da E09), `enabled: true`, `defaultRequesterCpf` **`52998224725`** (o CPF de teste dos testes de integração, `DEFAULT_CPF`) e qualquer credencial: o mock confere só que o `Authorization` é `Basic` (`^Basic .+`). Sorteie usuário (sem `:`) e senha no roteiro, guarde-os em variáveis e nunca os imprima.
- **CPF solicitante:** `GET /pessoas/documento/…` e `POST /pessoas` exigem `cpf_solicitante: 52998224725`. O envio usa o CPF solicitante do operador, se ele tiver um (`/api/v1/fnrh/requesters`), e o padrão senão: não cadastre outro CPF solicitante no gate (ou cadastre este).
- **Ids remotos:** `reserva_id`, `pessoa_id` e `hospede_id` são UUIDs sorteados a cada pedido (`{{randomValue type='UUID'}}`): duas reservas nunca recebem o mesmo `reserva_id`, e o titular e o menor recebem `pessoa_id` diferentes.
- **Operações:** `POST /reservas` (exige `numero_reserva`; devolve a reserva `CRIADA` com `link_precheckin`), `PUT /reservas/{id}`, `POST /reservas/{id}/cancelar`, `GET /reservas?page_number=1&codigo_reserva=…` (a conciliação depois de uma escrita em curso: um item com o código pedido, por templating, e um `reserva_id` sorteado, **diferente** do que o `POST /reservas` devolveu: o mock não guarda estado, então nunca compare os dois), `GET /reservas/{id}/hospedes` (lista vazia: a conciliação do hóspede repete o `POST`), `GET /pessoas/documento/{CPF|PASSAPORTE}/{numero}` (pessoa sem `id` neste meio de hospedagem, então o envio a cadastra), `POST /pessoas` (exige `pessoa.nome`), `POST /reservas/{id}/hospedes` (exige `pessoa_id` e `situacao_hospede_id` `CHECKIN_REALIZADO`), `PATCH /hospedes/{id}/checkout` (`text/plain` com o instante `AAAA-MM-DDThh:mm:ss.mmmZ`) e os dez domínios (`/dominios/…`, com os valores de `docs/fnrh/api-fnrh-v2.4.md`). Os pedidos sem `cpf_solicitante` na API exigem que ele venha ausente, como no `FnrhTestServer`.

Nenhum dado real fica nos stubs. **Os logs e o diário do `fnrh-mock` contêm os dados enviados, por desenho** (ele faz o papel do Serpro e recebe nome, documento e contato): a varredura de vazamento dos logs do gate olha `api`, `db`, `proxy` e `storage`, nunca o `fnrh-mock`, e o `down -v` apaga tudo no fim.

## Roteiro do M5

`m5-roteiro.sh <base-url> <projeto> <env-file>` faz o cenário do marco pelo proxy do gate (`<base-url>`, ex.: `http://localhost:18112`), no ambiente **recém-subido** (`up -d --build` depois de um `down -v`, com o readiness `UP`): ele faz a troca obrigatória da senha do admin inicial, então não roda duas vezes no mesmo banco. Não sobe nem derruba nada; o `up` e o `down -v` (por `trap`, também em falha) ficam com quem o chama, normalmente o `run-gate.sh` (ver [Uso](#uso)). Antes de tudo, recusa (saída 2, sem chamar o `docker`) o projeto `zeny` ou um nome de projeto inválido e um `<env-file>` dentro do projeto ou de um repositório git (a mesma regra do `gate-env.sh`: o `docker/.env` do usuário nunca entra). Os ids que entram no SQL do `psql` (reserva, agente, aprovação) são conferidos como UUID antes, e o token do agente pelo formato `zpa_` + base64url. O `<projeto>` e o `<env-file>` são os mesmos do `up`: o roteiro lê a senha do admin do env (sem eco) e usa o `docker compose exec` para o `psql` do `db` (o dono dos schemas) e para o diário do `fnrh-mock` (de dentro da rede, pelo `wget` da `api`).

Ferramentas: `curl`, `jq`, `openssl` e o `psql` do contêiner, além do `bash` e do coreutils. Toda conferência passa por `check <rótulo> <esperado> <obtido>`, que imprime `OK` ou `FALHA` e o rótulo; na falha, o esperado e o obtido saem com os segredos e os dados de hóspede trocados por `***` (redigidos antes do corte em 300 caracteres; o stderr do `psql` também é redigido inteiro antes do corte em 2000). Senhas, PIN, tokens (dos usuários e do agente) e a credencial da FNRH ficam só em variáveis e em arquivos temporários (modo 600, apagados na saída), nunca em argumentos de processo nem na saída. Cada `POST` leva uma `Idempotency-Key` própria. A saída inteira vai também para `<dir do env>/m5-roteiro.out` (ou `M5_ROTEIRO_OUT`), para a varredura de vazamento. O fim é `N conferências, F falhas`, com saída 1 se houver falha.

As operações da recepção são feitas pelos operadores dos modelos de role do boot (`recepcao`, Recepcionista; `gerente`, Gerente, com PIN); o que é só do Administrador (política de early/late, FNRH, CPF solicitante, agente e impressora) é feito pelo admin inicial. Seções (cada uma é uma função; o estado passa entre elas por variáveis globais e a lista fica em `SECTIONS`, no fim do arquivo):

1. **Públicos:** readiness `200`, `meta/version` `200`, `/actuator/prometheus` `404` pelo proxy, `/api/v1/reservations` sem token `401`.
2. **Usuários:** o admin inicial com a troca obrigatória (`403 PASSWORD_CHANGE_REQUIRED` antes dela, a senha inicial recusada depois); `recepcao` e `gerente` criados com as roles dos modelos e a troca de senha de cada um.
3. **Cadastro:** o Gerente cria o tipo de UH, as UHs 1201 e 1202 (inventário pelo listener), inspeciona a 1202, cria as políticas, o plano e os preços; o admin oferece o early e o late com valor (o Gerente recebe `403`), configura a FNRH (`HOMOLOGATION`, credencial sorteada, ligada, CPF solicitante padrão de teste; o Gerente recebe `403`) e o CPF solicitante da Recepcionista, cria o agente (token só em variável) e a impressora `A4_PDF`; pelo `psql`, o token `zpa_` não está em nenhuma coluna das tabelas do schema `printing` e o agente guarda só o SHA-256 dele.
4. **Check-in:** os hóspedes titular e menor (CPF e data de nascimento), a reserva com os dois; a autorização do menor pelo upload do `files`, vinculada ao perfil dele; o check-in na 1201 suja → `409 ROOM_NOT_READY` sem nada gravado; o Gerente inspeciona, a Recepcionista aloca, cota o early e faz o check-in com o menor autorizado, a caução e o `earlyCheckInFee`.
5. **FNRH:** espera (até 3 min) as submissões da reserva ficarem `SENT` (`GET /api/v1/fnrh/submissions`); `shared.event_publication` com o listener do `fnrh` `COMPLETED` para `ReservationCreated` e `GuestCheckedIn`; `GET /api/v1/fnrh/status` com `lastSuccessAt`; o diário do mock sem pedido não casado, com o titular e o menor na reserva e todos os pedidos com `Basic`.
6. **Ficha pelo agente:** `POST …/registration-card/print` → `202`; o agente abre o stream SSE (`curl -N`, tempo limite), lê o evento `job`, baixa o conteúdo (`%PDF-`) e confirma `PRINTED`; a lista de jobs mostra `PRINTED`; o JWT de usuário em `/print-agents/me/identity` → `401` e o token do agente em `/reservations` → `401`.
7. **Estadia:** troca para a 1202 com `If-Match` (tarifa mantida), extensão e redução da saída pelo `PUT` com `If-Match` (as noites voltam às originais), ponto de caixa do Gerente, turno da Recepcionista, lançamento de lavanderia, pagamento do saldo, extrato e confirmação em PDF.
8. **Check-out:** cota o late; o check-out com saldo → `409 FOLIO_BALANCE_NOT_ZERO` (a noite da chegada e o late); pago o total, o check-out encerra a conta, libera a caução e deixa a 1202 Vaga e Suja; as submissões até o check-out `SENT` e o `GuestCheckedOut` `COMPLETED` no listener do `fnrh`.
9. **Walk-in e desfazer:** o Gerente cria e inspeciona as UHs 1203 a 1205; o walk-in de um hóspede de gênero `OTHER` com descrição (E15) na 1203 → `201`, as duas submissões `SENT` e, no diário do mock, a pessoa com `genero_id` `OUTRO` e a descrição (o `jq` só responde `true`/`false`). Outra reserva, com check-in na 1204: a Recepcionista recebe `403 APPROVAL_REQUIRED` no desfazer; o Gerente aprova com login e PIN (`POST /auth/approvals`, na sessão da Recepcionista; o token vai por arquivo de cabeçalho, nunca por argumento); o desfazer com `X-Approval-Token` → `200`, reserva `CONFIRMED` na 1204 e a UH Vaga e Inspecionada; a trilha `frontdesk.stay.undo-check-in` com a Recepcionista e a aprovação, e `iam.approval` (pelo `psql`) com a Recepcionista e o Gerente.
10. **Recados:** a Recepcionista cria um recado para a governança (setor pré-carregado `HOUSEKEEPING`, lido pelo Gerente) vinculado à reserva desfeita; a lista da reserva e a do setor no dia o mostram; resolvido → `200` com quem resolveu; a segunda resolução → `409 TRACE_ALREADY_RESOLVED`.
11. **Serpro fora do ar:** com a fila sem pendência, `docker compose stop fnrh-mock`; a reserva nova faz check-in na 1205 → `200` e `IN_HOUSE`; o registro dela fica `PENDING` com a tentativa gasta e a falha de indisponibilidade, e o check-in na FNRH não sai; `docker compose start fnrh-mock` e, na volta, as duas submissões `SENT` (até 7 min, pelo backoff), sem pedido não casado no diário novo (inclusive a conciliação `GET /reservas?…&codigo_reserva=…`, que o mapeamento responde com o código consultado).
12. **E16:** o Gerente recebe `403` e o admin sem `If-Match` `428`; o admin limpa a credencial (`POST /fnrh/settings/clear-credentials` com `If-Match`) → `200`, sem senha, sem usuário nem CPF padrão, desligada, ambiente mantido; a trilha `fnrh.settings.clear-credentials` existe (antes com usuário e senha, depois sem) e não contém o usuário nem a senha da FNRH.
13. **Varredura:** `docker compose logs --no-color` da `api`, do `db`, do `proxy`, do `storage` e do `storage-init` (o `fnrh-mock` fica de fora: faz o papel do Serpro) e a própria saída do roteiro (`m5-roteiro.out`) não contêm nenhum valor registrado com `secret`: nomes, CPFs (com e sem máscara) e e-mails dos hóspedes, a descrição de gênero, o texto do recado, o usuário, a senha e o Basic da FNRH, o token do agente, os access e refresh tokens, o token de aprovação, o PIN (como palavra inteira), as senhas do admin e dos usuários, as senhas do banco e do MinIO e as chaves de cifragem e do JWT do env; nem `eyJ`. Na falha, a conferência diz só o tipo do valor achado, nunca o valor. O diário do mock termina sem pedido não casado.

Os 39 `WARN` do PDFBox ("Using fallback font LiberationSans", decisão D-E11-4: sem fontes embutidas) no log da `api` são esperados e não são vazamento, assim como os `WARN` da falha de envio à FNRH durante a queda da seção 11: a varredura procura valores, não exige zero `WARN`. O `run-gate.sh` mostra a contagem dos níveis (`log.level` do JSON do profile `prod`) no fim.
