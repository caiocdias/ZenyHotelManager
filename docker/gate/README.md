# Compose isolado do gate

Ferramentas do gate de marco (a partir do M5 E12): sobem o ambiente completo do `docker/compose.yaml`, no profile `prod`, num projeto Compose próprio, com segredos sorteados só para ele e uma FNRH de mentira (WireMock) no lugar do Serpro. Ficam fora da imagem (o `.dockerignore` é uma lista de permissão) e fora da CI.

> **Nunca use o `docker/.env`.** O gate não lê nem toca o env do usuário: o `gate-env.sh` gera outro, fora do repositório (ele recusa, com saída 2, um destino dentro do projeto). Use sempre `-p` próprio, a tag de imagem do gate (`zeny-hotel-manager:gate`, nunca a `:dev`) e `down -v` no fim, também quando algo falhar.

## Requisitos

`docker` (com o Compose v2), `bash`, `curl`, `jq`, `openssl` e o `realpath` do coreutils (GNU). O `up --build` compila a aplicação dentro do Docker: rode um build pesado por vez e pare os daemons do Gradle antes (`./gradlew --stop`).

## Arquivos

| Arquivo | Para quê |
|---|---|
| `gate-env.sh <dir> <porta>` | Grava `<dir>/gate.env` (modo 600) a partir do `docker/.env.example`, com as chaves de cifragem e do JWT e as senhas do banco, do MinIO e do admin sorteadas pelo `openssl`; `ZENY_PROFILE=prod`, proxy em `127.0.0.1:<porta>`, `ZENY_STORAGE_PUBLIC_ENDPOINT=http://localhost:<porta>` e `ZENY_FCM_CREDENTIALS` vazia. Imprime só o caminho, nunca valores. |
| `compose.gate.yaml` | Override do `docker/compose.yaml`: a `api` com a imagem `zeny-hotel-manager:gate` e as duas URLs da FNRH (`zeny.fnrh.homologation-url` e `zeny.fnrh.production-url`, pelas variáveis `ZENY_FNRH_HOMOLOGATIONURL` e `ZENY_FNRH_PRODUCTIONURL`) apontadas para `http://fnrh-mock:8080/FNRH_API/rest/v2`: o compose do gate nunca chama o Serpro real, em nenhum ambiente; o serviço `fnrh-mock` (WireMock 3.13.2 fixado por digest, sem porta publicada, com `--global-response-templating`). |
| `fnrh-mock/mappings/*.json` | Os stubs da API v2.4, com os mesmos contratos do `FnrhTestServer` dos testes (ver abaixo). |

## Uso

Na raiz do projeto (ou da cópia limpa), com um diretório de trabalho **fora** do repositório e uma porta livre:

```bash
GATE_DIR=/caminho/fora/do/repositorio      # ex.: o scratchpad da sessão
PORT=18112
ENV_FILE=$(docker/gate/gate-env.sh "$GATE_DIR" "$PORT")
DC=(docker compose -p zeny-gate --env-file "$ENV_FILE" -f docker/compose.yaml -f docker/gate/compose.gate.yaml)

"${DC[@]}" config -q                       # confere a composição, sem imprimir valores
"${DC[@]}" up -d --build
curl -fsS "http://localhost:$PORT/actuator/health/readiness"   # {"status":"UP"}

# ... roteiro do gate (docker/gate/m5-roteiro.sh), pelo proxy em http://localhost:$PORT

"${DC[@]}" down -v                         # sempre, também em falha (num script, por trap)
rm -f "$ENV_FILE"
```

Não use `"${DC[@]}" config` sem `-q`: ele imprime o env resolvido, com os segredos. O login inicial do admin é `admin`, com a senha `ZENY_ADMIN_PASSWORD` do `gate.env`; um roteiro a lê do arquivo para uma variável e nunca a imprime.

O `fnrh-mock` só é alcançável na rede do projeto (`<projeto>_default`), em `http://fnrh-mock:8080`. Para conferir o que ele recebeu, leia o diário do WireMock de dentro da rede, pelo `wget` da imagem da `api` (o mesmo do `HEALTHCHECK`), com a saída tratada pelo `jq`:

```bash
"${DC[@]}" exec -T api wget -qO- http://fnrh-mock:8080/__admin/requests | jq '.requests[].request | "\(.method) \(.url)"'
```

O diário vem do mais novo para o mais antigo. O cliente HTTP da `api` (o do JDK) negocia HTTP/2 em texto puro com o WireMock depois da primeira chamada: nos pedidos seguintes, os nomes dos cabeçalhos aparecem em minúsculas no diário (`authorization`, `cpf_solicitante`, `content-type`). Ao conferir cabeçalhos com o `jq`, compare os nomes sem diferenciar maiúsculas (o casamento dos stubs já é assim).

## O que a FNRH de mentira aceita

Todos os caminhos ficam sob `/FNRH_API/rest/v2`, como na URL de homologação do Serpro. Pedido fora destes contratos cai no `404` do WireMock, e a submissão fica com a falha visível na API (`GET /api/v1/fnrh/submissions`).

- **Configuração da FNRH** (`PUT /api/v1/fnrh/settings`, administrador): `environment` **`HOMOLOGATION`** (as duas URLs apontam para o mock, então `PRODUCTION` também não sai da rede do projeto; o roteiro usa homologação, como a operação real antes da E09), `enabled: true`, `defaultRequesterCpf` **`52998224725`** (o CPF de teste dos testes de integração, `DEFAULT_CPF`) e qualquer credencial: o mock confere só que o `Authorization` é `Basic` (`^Basic .+`). Sorteie usuário (sem `:`) e senha no roteiro, guarde-os em variáveis e nunca os imprima.
- **CPF solicitante:** `GET /pessoas/documento/…` e `POST /pessoas` exigem `cpf_solicitante: 52998224725`. O envio usa o CPF solicitante do operador, se ele tiver um (`/api/v1/fnrh/requesters`), e o padrão senão: não cadastre outro CPF solicitante no gate (ou cadastre este).
- **Ids remotos:** `reserva_id`, `pessoa_id` e `hospede_id` são UUIDs sorteados a cada pedido (`{{randomValue type='UUID'}}`): duas reservas nunca recebem o mesmo `reserva_id`, e o titular e o menor recebem `pessoa_id` diferentes.
- **Operações:** `POST /reservas` (exige `numero_reserva`; devolve a reserva `CRIADA` com `link_precheckin`), `PUT /reservas/{id}`, `POST /reservas/{id}/cancelar`, `GET /reservas?page_number=1&codigo_reserva=…` (a conciliação depois de uma escrita em curso: um item com o código pedido, por templating, e um `reserva_id` sorteado), `GET /reservas/{id}/hospedes` (lista vazia: a conciliação do hóspede repete o `POST`), `GET /pessoas/documento/{CPF|PASSAPORTE}/{numero}` (pessoa sem `id` neste meio de hospedagem, então o envio a cadastra), `POST /pessoas` (exige `pessoa.nome`), `POST /reservas/{id}/hospedes` (exige `pessoa_id` e `situacao_hospede_id` `CHECKIN_REALIZADO`), `PATCH /hospedes/{id}/checkout` (`text/plain` com o instante `AAAA-MM-DDThh:mm:ss.mmmZ`) e os dez domínios (`/dominios/…`, com os valores de `docs/fnrh/api-fnrh-v2.4.md`). Os pedidos sem `cpf_solicitante` na API exigem que ele venha ausente, como no `FnrhTestServer`.

Nenhum dado real fica nos stubs. **Os logs e o diário do `fnrh-mock` contêm os dados enviados, por desenho** (ele faz o papel do Serpro e recebe nome, documento e contato): a varredura de vazamento dos logs do gate olha `api`, `db`, `proxy` e `storage`, nunca o `fnrh-mock`, e o `down -v` apaga tudo no fim.
