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
| `lib-roteiro.sh` | A biblioteca comum dos roteiros (carregada por `source`, não se roda sozinha): os argumentos e as recusas, o `check`/`redact`, o HTTP, o `psql`, o diário do mock, os operadores, os dados fixos de hóspede registrados como `secret`, a revisão dos `WARN` e `ERROR` do log da `api` contra a lista permitida do roteiro (`log_review`, `WARN_ALLOWED`), a varredura (`leak_sweep`) e o laço das seções (`run_sections`). Os controles de segredo ficam num lugar só. |
| `m5-roteiro.sh <base-url> <projeto> <env-file>` | O roteiro `curl` do gate do M5, pelo proxy (ver [Roteiro do M5](#roteiro-do-m5)). |
| `m6-roteiro.sh <base-url> <projeto> <env-file>` | O roteiro `curl` do gate do M6 (cenário da Fase 1), pelo proxy, com as mesmas regras do M5 (ver [Roteiro do M6](#roteiro-do-m6)). |
| `run-gate.sh <workdir> <porta> [projeto] [roteiro]` | O ciclo completo: gera o env, sobe, espera o readiness, roda o roteiro (`m5`, o padrão, ou `m6`) e faz `down -v` por `trap`, também em falha (ver [Uso](#uso)). |

## Uso

Na raiz do projeto (ou da cópia limpa), com um diretório de trabalho **fora** do repositório e uma porta livre:

```bash
docker/gate/run-gate.sh /caminho/fora/do/repositorio 18112 zeny-gate      # o roteiro do M5 (padrão)
docker/gate/run-gate.sh /caminho/fora/do/repositorio 18120 zeny-gate m6   # o roteiro do M6
```

O `run-gate.sh`:

1. recusa (saída 2) o projeto `zeny` (o do usuário), um nome de projeto inválido, um projeto que já tenha contêiner, volume ou rede (o roteiro precisa de um banco novo, e o `down -v` do fim nunca apaga o que ele não subiu) e um `docker` que não responda às consultas do projeto (cada consulta tem o código de saída conferido: uma falha nunca vira 0);
2. gera `<workdir>/gate.env` pelo `gate-env.sh` (que recusa um `<workdir>` dentro do projeto ou de um repositório git);
3. arma o `trap … EXIT` (também em erro e em Ctrl+C) que faz `down -v --remove-orphans`, apaga o `gate.env` e conta as sobras do projeto (`sobras: contêineres 0, volumes 0, redes 0`; se o `docker` não responder, diz isso e sai com 2);
4. roda `config -q` e `up -d --build`, espera o readiness (até 300 s) e mostra os serviços;
5. roda o roteiro escolhido, `m5-roteiro.sh` (padrão) ou `m6-roteiro.sh` (a saída fica em `<workdir>/<roteiro>-roteiro.out`), e conta os níveis do log da `api`;
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
13. **Varredura:** a lista dos `WARN` e `ERROR` distintos do log da `api`, nenhum `ERROR` e nenhum `WARN` fora da lista permitida do roteiro (ver [WARN e ERROR esperados](#warn-e-error-esperados)); `docker compose logs --no-color` da `api`, do `db`, do `proxy`, do `storage` e do `storage-init` (o `fnrh-mock` fica de fora: faz o papel do Serpro) e a própria saída do roteiro (`m5-roteiro.out`) não contêm nenhum valor registrado com `secret`: nomes, CPFs (com e sem máscara), e-mails, telefone, rua, CEP e data de nascimento dos hóspedes, a descrição de gênero, o texto do recado, o usuário, a senha e o Basic da FNRH, o token do agente, os access e refresh tokens, o token de aprovação, o PIN (como palavra inteira), as senhas do admin e dos usuários, as senhas do banco e do MinIO e as chaves de cifragem e do JWT do env; nem `eyJ`. Na falha, a conferência diz só o tipo do valor achado, nunca o valor. O diário do mock termina sem pedido não casado. O mínimo de valores procurados conta valores distintos (os dados fixos de endereço e contato se repetem por hóspede).

Os 42 `WARN` do log da `api` no M5 são os grupos esperados descritos em [WARN e ERROR esperados](#warn-e-error-esperados): 39 do PDFBox (os três PDFs da seção 6 e 7), 2 da queda da FNRH na seção 11 e 1 da leitura das métricas do fechamento no boot. A varredura procura valores, não exige zero `WARN`. O `run-gate.sh` mostra a contagem dos níveis (`log.level` do JSON do profile `prod`) no fim.

## Roteiro do M6

`m6-roteiro.sh <base-url> <projeto> <env-file>` faz o cenário da Fase 1 (D1 do plano da E12) pelo proxy e, depois dele, o que só o ambiente completo mostra (D2 do plano): o arquivo do fechamento baixado pelo `files`, as pendências, os relatórios com o CSV e o XLSX, a OS com OOO, o achado e o dano e um terceiro fechamento com pendência. Roda no ambiente **recém-subido**, com as mesmas recusas, o mesmo `check`/`redact` e a mesma varredura do `m5-roteiro.sh`, pela `lib-roteiro.sh` (a saída vai para `<dir do env>/m6-roteiro.out` ou `M6_ROTEIRO_OUT`). Ele fecha três dias de verdade: a data operacional do banco do gate avança três vezes (D0, D1 e D2), então não roda duas vezes no mesmo banco. Os operadores são os dos modelos de role (`recepcao`, `gerente` com PIN, `governanta`, `camareira` e `manutencao`); o admin inicial só muda o horário do fechamento e liga a FNRH. Os comandos offline levam um `commandId` UUIDv7 gerado no roteiro.

Como rodar: pelo `run-gate.sh`, com `m6` no **4º argumento** (sem ele, o `run-gate.sh` roda o roteiro do M5, o padrão, para não quebrar o uso documentado), num projeto e numa porta próprios, com o diretório de trabalho fora do repositório:

```bash
docker/gate/run-gate.sh /caminho/fora/do/repositorio 18120 zeny-e12-gate m6
```

O fim esperado é `N conferências, 0 falhas`, `roteiro exit=0`, a contagem dos níveis do log da `api` e `sobras: contêineres 0, volumes 0, redes 0`. Seções (a varredura fica por último; seção nova entra antes dela em `SECTIONS`):

1. **Públicos:** readiness, `meta/version`, `/actuator/prometheus` `404` e `/end-of-day/runs` sem token `401`.
2. **Usuários:** a troca obrigatória da senha do admin; os cinco operadores pelos modelos de role (com a `manutencao`), cada um com a troca de senha.
3. **Configuração mínima:** o horário do fechamento `06:00` pelo admin (o Gerente recebe `403`): antes do meio-dia, o automático de D só vence em D+1, ao menos 6 h depois de qualquer hora em que o roteiro rode; o tipo de UH e a UH 1301 inspecionada, as políticas, o plano e os preços (Gerente); os créditos da permanência e da saída no tipo de UH, os modelos de governança e de inspeção e a rotina diária na UH (Governanta); a Camareira como colaboradora da governança, escalada em D1 e D2 (Gerente); a FNRH em homologação contra o mock (admin).
4. **D0:** a reserva D0–D2 de 1 adulto com a hora prevista de chegada (E22), duas noites a `300.00`; check-in na 1301; registro e check-in `SENT` na FNRH.
5. **Fechamento de D0:** a Recepcionista recebe `403 APPROVAL_REQUIRED` (sem execução criada); o Gerente aprova com login e PIN na sessão dela; a execução `MANUAL` termina `COMPLETED` com as 11 etapas; a diária de D0 uma vez (`END_OF_DAY`, e um lançamento por noite pelo `psql`), a UH Suja, a data em D1, nenhuma pendência da reserva, da conta ou da UH e a execução da rotina de D1.
6. **Permanência de D1:** gerada pelo avanço, com o tipo e os créditos configurados; o plano sugerido, ajustado e publicado pela Governanta; a atribuição no painel da Camareira; início e conclusão pelo `POST /sync/commands` (`APPLIED`), checklist concluído com o modelo do gate; a inspeção voluntária deixa a UH Inspecionada.
7. **Fechamento de D1:** pelo Gerente (com a permissão); a diária de D1 uma vez, a UH Suja, a data em D2 e nenhuma permanência para quem sai em D2.
8. **Check-out em D2:** turno de caixa, pagamento do saldo (as duas diárias), check-out sem diária a mais com a conta encerrada e saldo zero, a UH Vaga e Suja, o turno fechado sem diferença.
9. **Tarefa de saída:** gerada pelo `GuestCheckedOut`, executada offline (`AWAITING_INSPECTION`), inspecionada e aprovada pela tarefa: `DONE` e a UH Vaga e Inspecionada.
10. **FNRH:** registro, check-in e check-out `SENT`, o `GuestCheckedOut` `COMPLETED` no listener do `fnrh` e o diário do mock sem pedido não casado, com `Basic` em todos; a sequência dos pedidos da estadia (registro, consulta e cadastro da pessoa, inclusão do hóspede e o check-out por último, com ids e documento trocados por `{id}` e `{doc}`) e os ids encadeados (a inclusão na `reserva_id` devolvida, com o `pessoa_id` devolvido, e o check-out do `hospede_id` devolvido; o `jq` só responde `true`/`false`).
11. **Arquivo e pendências do fechamento (E13, E23):** pelo `psql`, um snapshot (`reporting.daily_statistics`) por data, D0 e D1, e o de D0 com 1 UH vendida, 1 disponível, 1 chegada, 0 saída e a receita de diárias de uma noite; `GET /reports/archive?date=D0` (e `D1`) pelo Gerente com um `DAY_REPORT`; a Camareira recebe `403` no arquivo e na URL de download; o anexo é `application/pdf` e sensível; a URL de download (`GET /files/{id}/download-url`) é do próprio servidor (relativa ou no `<base-url>` do gate), com o token (registrado como segredo e passado ao `curl` por arquivo de configuração `-K`, nunca por argumento); só então o roteiro baixa (uma URL de outra origem nunca recebe o token), e o download sem token de acesso dá `200 application/pdf` com `%PDF-`; as execuções de D0 e D1 fecham sem pendência (`GET …/runs/{id}/issues` com 0 itens, o esperado no cenário; a seção 15 prova a pendência com conteúdo).
12. **Relatórios (E09):** o painel do dia (`days=3`) na data D2, com a UH vaga e inspecionada, sem OOO, e D2 com 0 chegada, 1 saída e 0 hospedado, sem dado de hóspede; a Camareira recebe `403`; o relatório de governança de D0 a D2 com a Camareira (2 UHs e os créditos da permanência mais os da saída) e a inspeção voluntária sem reprovação; o mesmo relatório em CSV (`text/csv`, UTF-8 com BOM, sem dado de hóspede) e em XLSX (o tipo do Office, um zip `PK`, `Cache-Control: no-store`).
13. **OS corretiva com OOO (E07):** a Governanta abre a OS da UH 1301 (`OS-AAAA-NNNNNN`); a Manutenção pede o OOO de D2 a D3 e recebe `403 APPROVAL_REQUIRED` (ação `maintenance.work-order.block-room`, permissão `rooms.outoforder.manage`, recurso com a OS e a UH), sem bloqueio; o Gerente aprova com login e PIN na sessão dela; com o `X-Approval-Token`, o OOO abre (retorno Suja) e o painel conta uma UH em OOO; a Manutenção inicia e conclui a OS com os custos, sem aprovação; o OOO é liberado em D2 e a UH volta Vaga e Suja.
14. **Achado e perdido e dano (E06):** a UH 1302 e uma segunda reserva própria (D2 a D3), com check-in nela; a Camareira registra um achado na 1302 (com o local de guarda), que a Governanta vê na lista; a Camareira registra um dano na UH ocupada (`REPORTED`, sem o vínculo na resposta dela); o Gerente vê o vínculo sugerido com a segunda reserva; a Camareira recebe `403` na revisão; o Gerente revisa (`120.00`, o código `MISCELLANEOUS` do tipo `CHARGE`, mantendo o vínculo) e lança na conta da segunda reserva (`CHARGED`); a conta tem um lançamento "Dano na UH 1302" de `120.00`, sem taxa de serviço, na data D2.
15. **Fechamento de D2 com pendência (E13):** com um segundo ponto de caixa e um turno deixado aberto pela Recepcionista, depois de o registro e o check-in da segunda reserva saírem para a FNRH (`SENT`, até 3 min; senão, eles viram pendências `FNRH_SUBMISSION_PENDING` com a referência da reserva e a lista depende do relógio do job), o Gerente fecha D2 (as 11 etapas `COMPLETED`, a data em D3); as pendências da execução trazem `CASHIER_SHIFT_OPEN` da etapa `end-of-day-pending` com o id do turno, nenhuma cita a segunda reserva nem a conta dela, e nenhuma tem segredo nem dado de hóspede; o arquivo de D2 tem um `DAY_REPORT`.
16. **Varredura:** a lista dos `WARN` e `ERROR` distintos do log da `api`, nenhum `ERROR` e nenhum `WARN` fora da lista permitida do roteiro (ver [WARN e ERROR esperados](#warn-e-error-esperados)); os logs da `api`, do `db`, do `proxy`, do `storage` e do `storage-init` e a própria saída sem segredo (inclusive o token de download e o de aprovação), dado de hóspede (dos dois hóspedes) nem `eyJ`.

`psql` só onde a API não mostra: a contagem de lançamentos de diária por noite, a conclusão, pelo listener da governança, do avanço para D2, e o snapshot diário (sem endpoint: os KPIs são do M10).

## WARN e ERROR esperados

Antes da varredura, os dois roteiros listam na saída os `WARN` e `ERROR` distintos do log JSON da `api` (`log_review` da `lib-roteiro.sh`): a contagem, o nível, o logger e a mensagem, com os segredos e os dados de hóspede trocados por `***` antes de qualquer normalização e, depois, os UUIDs trocados por `{uuid}` e os números por `{n}` (para agrupar). A pilha de exceção fica de fora. Como o `down -v` apaga os logs, essa lista é o registro do que a `api` avisou. Os grupos esperados são estes; **um `WARN` ou `ERROR` fora desta lista é achado** e reprova o gate: a varredura confere `log da api: nenhum ERROR` (0) e `log da api: nenhum WARN fora da lista permitida do roteiro` (0), contra o `WARN_ALLOWED` de cada roteiro (o logger exato e o começo da mensagem crua, a mesma tabela abaixo), e mostra os grupos que ficaram de fora. A saída também diz quantas linhas do log da `api` não são JSON (como os `WARNING:` da JVM), só como contagem. Nenhum dos dois roteiros produz `ERROR`.

| Grupo (logger: mensagem) | M5 | M6 | Por quê |
|---|---|---|---|
| `org.apache.pdfbox.pdmodel.font.PDType1Font: Using fallback font LiberationSans for base font <fonte>` (13 fontes base: Times, Helvetica e Courier nas quatro variantes, e Symbol) | 39 (13 × 3) | 39 (13 × 3) | Decisão D-E11-4: os PDFs não embutem fontes e o PDFBox avisa a troca das 13 fontes padrão pela LiberationSans da imagem: nas rodadas do gate, 13 avisos por PDF renderizado. No M5, a ficha de registro (seção 6), o extrato e a confirmação (seção 7); no M6, o `DAY_REPORT` de cada um dos três fechamentos. Não é vazamento (só o nome da fonte). |
| `com.zeny.hotel.fnrh.infrastructure.SerproFnrhGateway: FNRH registerReservation: falha no envio (ResourceAccessException)` e `… FNRH registerReservation HOMOLOGATION UNAVAILABLE (HTTP -), {n} ms, ref {uuid}` | 1 + 1 | 0 | Só no M5: a seção 11 para o `fnrh-mock` de propósito e a primeira tentativa de envio da reserva nova falha por indisponibilidade (o log leva só a operação, o ambiente, o desfecho, a duração e a referência da submissão, sem dado do hóspede); o retry a entrega na volta. |
| `com.zeny.hotel.endofday.infrastructure.EndOfDayMetrics: Não foi possível ler as métricas do fechamento do dia (IllegalStateException)` | 1 | 1 | No boot de um banco novo: o `refresh()` das métricas do fechamento (`@Scheduled`, sem atraso inicial, a cada `zeny.endofday.metrics.refresh-interval`, 30 s) roda antes de o `ApplicationRunner` do `hotel` criar o hotel e a data operacional, e o `HotelApi` responde `IllegalStateException` (hotel não inicializado). Os gauges ficam `NaN` até a leitura seguinte, 30 s depois, e o alerta `ZenyEndOfDayMetricsUnavailable` só dispara com 5 min de `NaN` (`for: 5m`). Num banco já inicializado, não aparece. |
