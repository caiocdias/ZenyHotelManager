#!/usr/bin/env bash
# Roteiro curl do gate do M6 (E12): o cenário da Fase 1 pelo proxy do compose isolado do gate, no profile prod.
#
#   docker/gate/m6-roteiro.sh <base-url> <projeto> <env-file>
#
#   <base-url>  o proxy do gate, ex.: http://localhost:18120
#   <projeto>   o projeto Compose do gate (o mesmo -p do up), ex.: zeny-e12-gate
#   <env-file>  o gate.env gerado pelo gate-env.sh (a senha do admin é lida dele, sem eco)
#
# Pré-requisito: o ambiente recém-subido (up -d --build depois de um down -v) e com o readiness UP; o roteiro faz a
# troca obrigatória da senha do admin inicial e fecha dois dias de verdade (a data operacional avança no banco do
# gate), então não roda duas vezes no mesmo banco. Ele não sobe nem derruba nada: o up e o down -v (sempre, também em
# falha) ficam com quem o chama (docker/gate/run-gate.sh, com o roteiro m6; docker/gate/README.md).
#
# O cabeçalho, as recusas, o check/redact, o HTTP, o psql, o diário do fnrh-mock e a varredura são cópia fiel do
# m5-roteiro.sh (as mesmas regras de segredo do docker/gate/README.md). Toda conferência passa por check, que imprime
# o rótulo e OK/FALHA e, na falha, o esperado e o obtido com os segredos e os dados de hóspede trocados por ***.
# Senhas, PIN, tokens e a credencial da FNRH ficam só em variáveis e em arquivos temporários (modo 600, apagados na
# saída), nunca em argumentos de processo nem na saída. A saída inteira vai também para <dir do env>/m6-roteiro.out
# (ou M6_ROTEIRO_OUT), para a varredura de vazamento.
#
# Fim: "N conferências, F falhas"; sai com 1 se F > 0 (2 em uso errado).
set -uo pipefail
umask 077

if [ "$#" -ne 3 ]; then
    echo "uso: $0 <base-url> <projeto> <env-file>" >&2
    exit 2
fi
BASE=${1%/}
PROJECT=$2
ENV_FILE=$3
[ -r "$ENV_FILE" ] || { echo "não consigo ler o env: $ENV_FILE" >&2; exit 2; }
API=$BASE/api/v1

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
ROOT=$(cd "$script_dir/../.." && pwd -P)

# Os argumentos antes de tocar em qualquer coisa: o projeto do usuário (zeny) nunca, e o env só o do gate-env.sh,
# fora do projeto e de qualquer repositório git (a mesma regra do gate-env.sh: o docker/.env do usuário fica de fora).
if ! [[ $PROJECT =~ ^[a-z0-9][a-z0-9_-]*$ ]] || [ "$PROJECT" = zeny ]; then
    echo "recusado: projeto inválido ou do usuário: $PROJECT (use o -p do gate, ex.: zeny-gate)" >&2
    exit 2
fi
env_real=$(realpath -m -- "$ENV_FILE")
case "$env_real" in
    "$ROOT"/*)
        echo "recusado: o env do gate não pode ficar dentro do projeto ($ROOT)" >&2
        exit 2
        ;;
esac
guard_dir=$(dirname -- "$env_real")
while :; do
    if [ -e "$guard_dir/.git" ]; then
        echo "recusado: o env do gate não pode ficar dentro de um repositório git ($guard_dir)" >&2
        exit 2
    fi
    [ "$guard_dir" = / ] && break
    guard_dir=$(dirname -- "$guard_dir")
done

DC=(docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f "$ROOT/docker/compose.yaml" -f "$ROOT/docker/gate/compose.gate.yaml")

T=$(mktemp -d "${TMPDIR:-/tmp}/m6-roteiro.XXXXXX")
trap 'rm -rf -- "$T"' EXIT

OUT=${M6_ROTEIRO_OUT:-$(dirname -- "$ENV_FILE")/m6-roteiro.out}
exec > >(tee -- "$OUT") 2>&1

# --- Conferência, segredos e resumo (um lugar só) ---------------------------------------------------------------

N=0
F=0
SECRETS=()
SECRET_KINDS=()

# secret <valor> [tipo]: guarda um valor que nunca pode sair na tela (segredo ou dado de hóspede): o redact o troca
# por ***, e a varredura da seção 13 o procura nos logs e na própria saída (e, achando, diz só o tipo).
secret() {
    if [ -n "$1" ]; then
        SECRETS+=("$1")
        SECRET_KINDS+=("${2:-segredo}")
    fi
    return 0
}

redact() {
    local s=$1 x
    for x in "${SECRETS[@]}"; do
        s=${s//"$x"/***}
    done
    printf '%s' "$s"
}

# check <rótulo> <esperado> <obtido>
check() {
    N=$((N + 1))
    if [ "$2" == "$3" ]; then
        printf 'OK     %s\n' "$1"
    else
        F=$((F + 1))
        printf 'FALHA  %s\n' "$1"
        # Redige antes de cortar: um segredo que atravessa o corte nunca sai pela metade.
        local expected got
        expected=$(redact "$2")
        got=$(redact "$3")
        printf '       esperado: %s\n' "${expected:0:300}"
        printf '       obtido:   %s\n' "${got:0:300}"
    fi
}

# check_http <rótulo> <status esperado>: confere o status da última requisição; na falha, mostra o code do problema.
check_http() {
    local got=$ST
    [ "$got" == "$2" ] || got="$got $(problem_code)"
    check "$1 → $2" "$2" "$got"
}

# bool <teste...>: true/false, para conferir condições com o check.
bool() {
    if "$@"; then echo true; else echo false; fi
}

is_uuid() {
    [[ $1 =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]]
}

is_money() {
    [[ $1 =~ ^[0-9]+\.[0-9]{2}$ ]]
}

# require_uuid <nome> <valor>: um id só entra no SQL do dono se for um UUID (senão nada roda e a conferência falha).
require_uuid() {
    is_uuid "$2" && return 0
    printf 'id inválido para o SQL: %s\n' "$1" >&2
    return 1
}

section() {
    printf '\n== %s\n' "$1"
}

summary() {
    printf '\n%d conferências, %d falhas\n' "$N" "$F"
    [ "$F" -eq 0 ]
}

# --- HTTP ---------------------------------------------------------------------------------------------------------

uuid() {
    local h
    h=$(openssl rand -hex 16)
    printf '%s-%s-4%s-%x%s-%s\n' "${h:0:8}" "${h:8:4}" "${h:13:3}" $(((16#${h:16:1} & 3) | 8)) "${h:17:3}" "${h:20:12}"
}

# O token de <quem> num arquivo de cabeçalho (curl -H @arquivo): nunca em argumento de processo.
set_token() {
    if [ "$1" = agent ]; then secret "$2" "token do agente"; else secret "$2" "access token"; fi
    printf 'Authorization: Bearer %s\n' "$2" >"$T/auth-$1"
}

# req <método> <caminho ou URL> <quem|-> [arquivo do corpo JSON] [cabeçalho...]
# Deixa o status em ST, o corpo em $T/body e os cabeçalhos em $T/headers. POST leva Idempotency-Key própria, salvo
# com NO_IDEM=1 (login, troca de senha e as chamadas do agente).
req() {
    local method=$1 path=$2 who=$3 body=${4:-} url
    shift 3
    [ "$#" -gt 0 ] && shift
    case $path in
        http*) url=$path ;;
        *) url=$API$path ;;
    esac
    local args=(-s --max-time 60 -o "$T/body" -D "$T/headers" -w '%{http_code}' -X "$method")
    [ "$who" != - ] && args+=(-H "@$T/auth-$who")
    [ -n "$body" ] && args+=(-H 'Content-Type: application/json' --data-binary "@$body")
    if [ "$method" = POST ] && [ -z "${NO_IDEM:-}" ]; then
        args+=(-H "Idempotency-Key: $(uuid)")
    fi
    local h
    for h in "$@"; do
        args+=(-H "$h")
    done
    ST=$(curl "${args[@]}" "$url") || true
    [ -n "$ST" ] || ST=000
}

jb() {
    jq -r "$1" "$T/body" 2>/dev/null
}

jbc() {
    jq -c "$1" "$T/body" 2>/dev/null
}

problem_code() {
    jq -r '.code // empty' "$T/body" 2>/dev/null
}

hdr() {
    grep -i "^$1:" "$T/headers" | tail -n 1 | cut -d: -f2- | sed 's/^ *//' | tr -d '\r'
}

starts_with_pdf() {
    [ "$(head -c 5 "$T/body")" = "%PDF-" ]
}

# --- Banco (psql do dono, SQL pela entrada padrão) e o diário do fnrh-mock ------------------------------------------

# O stderr do psql (e do compose) nunca sai cru: num erro, o psql ecoa o comando (LINE n:, QUERY:), que pode levar
# um segredo (o token do agente vai no SQL); ele passa pelo redact antes de ir para a saída (e para o .out).
sql() {
    local rc
    "${DC[@]}" exec -T db sh -c 'psql -X -q -At -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
        2>"$T/sql.err"
    rc=$?
    if [ -s "$T/sql.err" ]; then
        # Redige o stderr inteiro e só depois corta: o corte nunca deixa um pedaço de segredo.
        local err
        err=$(redact "$(<"$T/sql.err")")
        printf 'psql (stderr redigido): %s\n' "${err:0:2000}" >&2
    fi
    : >"$T/sql.err"
    return "$rc"
}

mock() {
    "${DC[@]}" exec -T api wget -qO- "http://fnrh-mock:8080/__admin/$1"
}

# A situação da publicação do evento <tipo> da reserva no listener do fnrh (vazio se não há).
fnrh_publication() {
    require_uuid RES_ID "$RES_ID" || return 1
    sql <<SQL
SELECT string_agg(status, ',' ORDER BY publication_date) FROM shared.event_publication
WHERE event_type LIKE '%.$1' AND listener_id LIKE '%fnrh%FnrhSubmissionListeners%'
  AND serialized_event LIKE '%$RES_ID%';
SQL
}

# Espera (até 30 s) a publicação do evento <tipo> da reserva no listener do fnrh ficar COMPLETED.
await_fnrh_publication() {
    local deadline=$((SECONDS + 30)) got
    while :; do
        got=$(fnrh_publication "$1")
        [ "$got" = COMPLETED ] || [ "$SECONDS" -ge "$deadline" ] && break
        sleep 2
    done
    printf '%s' "$got"
}

# Os pedidos do diário do mock: total, sem casar, e quantos casam com <método> e o regex do caminho.
mock_unmatched() {
    mock requests/unmatched | jq '.requests | length'
}

mock_count() {
    mock requests | jq --arg m "$1" --arg p "$2" '[.requests[].request | select(.method == $m and (.url | test($p)))] | length'
}

mock_all_basic() {
    mock requests | jq '[.requests[].request | [.headers // {} | to_entries[]
        | select(.key | ascii_downcase == "authorization") | .value | tostring | startswith("Basic ")] | any]
        | (length > 0 and all)'
}

# As submissões da FNRH da reserva, por ordem de criação: "OPERAÇÃO:SITUAÇÃO,…".
fnrh_submissions() {
    req GET "/fnrh/submissions?limit=100" recepcao
    jq -r --arg r "$RES_ID" \
        '[.items[] | select(.reservationId == $r)] | sort_by(.createdAt) | map("\(.operation):\(.status)") | join(",")' \
        "$T/body" 2>/dev/null
}

# poll <esperado> <segundos> <intervalo> <comando...>: repete o comando até a saída ser <esperado> ou o prazo vencer,
# com a última saída em GOT. Fora de subshell e com o ensure_tokens a cada volta: esperas longas não perdem o token.
poll() {
    local expected=$1 deadline=$((SECONDS + $2)) interval=$3
    shift 3
    while :; do
        ensure_tokens
        GOT=$("$@")
        [ "$GOT" = "$expected" ] || [ "$SECONDS" -ge "$deadline" ] && break
        sleep "$interval"
    done
}

# --- Usuários -----------------------------------------------------------------------------------------------------

LOGIN_AT=0
OPERATORS=(admin recepcao gerente governanta camareira)
declare -A LOGIN_OF PASSWORD_OF

# login <quem> <login> <senha>: deixa o token de <quem> pronto se der 200.
login() {
    LOGIN_NAME=$2 LOGIN_PASSWORD=$3 jq -n \
        '{login: env.LOGIN_NAME, password: env.LOGIN_PASSWORD, device: {id: "gate-m6", name: "Roteiro do gate M6"}}' \
        >"$T/req.json"
    NO_IDEM=1 req POST /auth/login - "$T/req.json"
    if [ "$ST" = 200 ]; then
        set_token "$1" "$(jb .accessToken)"
        secret "$(jb .refreshToken)" "refresh token"
    fi
}

# change_password <quem> <atual> <nova>
change_password() {
    CURRENT=$2 NEW=$3 jq -n '{currentPassword: env.CURRENT, newPassword: env.NEW}' >"$T/req.json"
    NO_IDEM=1 req POST /auth/password "$1" "$T/req.json"
}

# O access token vale 15 min: depois de 10, cada operador entra de novo (sem conferência; a sessão é outra).
ensure_tokens() {
    [ $((SECONDS - LOGIN_AT)) -lt 600 ] && return
    local who
    for who in "${OPERATORS[@]}"; do
        login "$who" "${LOGIN_OF[$who]}" "${PASSWORD_OF[$who]}"
    done
    LOGIN_AT=$SECONDS
}

# --- Dados de teste -----------------------------------------------------------------------------------------------

REQUESTER_CPF=52998224725
# Os preços do plano (bulk-update da seção 3): a reserva é de 1 adulto, então cada noite vale ONE_ADULT.
ONE_ADULT=300.00
TWO_ADULTS=400.00
# A hora prevista de chegada (E22) e o horário do fechamento: antes do meio-dia, o automático da data D vence só em
# D+1 nesse horário, ao menos 6 h depois de qualquer hora de D em que o roteiro rode (os fechamentos são manuais).
EXPECTED_ARRIVAL=14:30
END_OF_DAY_TIME=06:00
# Os créditos que a Governanta configura no tipo de UH do gate, por tipo de limpeza.
STAYOVER_CREDITS=2
CHECKOUT_CREDITS=3
# As 11 etapas do fechamento real, em ordem de posição.
STEPS="no-show,pending-departures,room-charges,block-release,provisional-expiry,card-pre-authorizations,"
STEPS+="occupied-rooms-dirty,daily-statistics,end-of-day-reports,end-of-day-pending,advance-business-date"

# Um CPF válido sorteado (nunca o CPF solicitante de teste).
new_cpf() {
    local d i s r cpf
    while :; do
        d=()
        for i in 0 1 2 3 4 5 6 7 8; do
            d[i]=$((RANDOM % 10))
        done
        s=0
        for i in 0 1 2 3 4 5 6 7 8; do
            s=$((s + d[i] * (10 - i)))
        done
        r=$((s * 10 % 11))
        [ "$r" -eq 10 ] && r=0
        d[9]=$r
        s=0
        for i in 0 1 2 3 4 5 6 7 8 9; do
            s=$((s + d[i] * (11 - i)))
        done
        r=$((s * 10 % 11))
        [ "$r" -eq 10 ] && r=0
        d[10]=$r
        cpf=$(printf '%s' "${d[@]}")
        [ -z "${cpf//${cpf:0:1}/}" ] || [ "$cpf" = "$REQUESTER_CPF" ] || break
    done
    printf '%s\n' "$cpf"
}

cents() {
    local v=$1
    echo $((10#${v%.*} * 100 + 10#${v#*.}))
}

money() {
    printf '%d.%02d\n' $(($1 / 100)) $(($1 % 100))
}

# Um UUIDv7 (o commandId dos comandos offline, gerado "no dispositivo"): 48 bits do instante em ms, versão 7,
# variante 10 e o resto sorteado.
uuid7() {
    local ms r
    ms=$(printf '%012x' "$(date +%s%3N)")
    r=$(openssl rand -hex 10)
    printf '%s-%s-7%s-%x%s-%s\n' "${ms:0:8}" "${ms:8:4}" "${r:0:3}" $(((16#${r:3:1} & 3) | 8)) "${r:4:3}" "${r:7:12}"
}

# A data operacional (RN-01) lida como <quem>, em BD (fora de subshell: o ST fica para o check_http).
business_date() {
    req GET /hotel/business-date "$1"
    BD=$(jb .businessDate)
}

is_date() {
    [[ $1 =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]
}

# housekeeping <UH> <transição>: pelo Gerente, com o If-Match do status da UH.
housekeeping() {
    req GET "/rooms/$1/status" gerente
    local tag
    tag=$(hdr etag)
    jq -n --arg t "$2" '{transition: $t}' >"$T/req.json"
    req POST "/rooms/$1/housekeeping-status" gerente "$T/req.json" "If-Match: $tag"
}

# "<ocupação> <governança>" da UH.
room_status() {
    req GET "/rooms/$1/status" admin
    jb '"\(.occupancy) \(.housekeepingStatus)"'
}

pay() {
    jq -n --arg a "$2" '{method: "CASH", amount: $a, deposit: false}' >"$T/req.json"
    req POST "/folios/$1/payments" recepcao "$T/req.json"
}

# new_guest <rótulo ASCII>: a Recepcionista cadastra um hóspede adulto brasileiro com CPF sorteado e os dados que a
# FNRH exige. Nome, CPF e e-mail viram dado de hóspede (secret) e entram no jq pelo ambiente. Deixa o id em GUEST_ID e
# o status em ST.
new_guest() {
    local suffix cpf name email
    suffix=$(openssl rand -hex 3)
    cpf=$(new_cpf)
    name="$1 Gate $suffix"
    email="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]').$suffix@example.com"
    secret "$cpf" hóspede
    secret "${cpf:0:3}.${cpf:3:3}.${cpf:6:3}-${cpf:9:2}" hóspede
    secret "$name" hóspede
    secret "$email" hóspede
    G_NAME=$name G_EMAIL=$email G_CPF=$cpf jq -n \
        '{profile: {fullName: env.G_NAME, socialName: null, nationality: "BR", birthDate: "1988-03-14",
            gender: "FEMALE", email: env.G_EMAIL, phone: "+55 11 98765-4321", residenceCountry: "BR",
            address: {street: "Rua das Acácias", number: "742", complement: "Apto 12", district: "Jardim Paulista",
                      city: "São Paulo", state: "SP", postalCode: "01407-000", ibgeCityCode: "3550308"},
            preferences: {floor: "Alto", bed: "Casal", pillow: "Baixo"}, vip: false, notes: "Gate do M6",
            companyId: null},
          documents: [{type: "CPF", issuingCountry: null, number: env.G_CPF}],
          sensitiveData: {raceColor: "BROWN"}}' >"$T/req.json"
    req POST /guests recepcao "$T/req.json"
    GUEST_ID=$(jb .id)
}

env_value() {
    grep -m1 "^$1=" "$ENV_FILE" | cut -d= -f2-
}

# Os segredos do env do gate (banco, MinIO, chaves de cifragem e do JWT): redigidos na saída e procurados na varredura.
register_env_secrets() {
    local v
    secret "$(env_value ZENY_DB_MIGRATION_PASSWORD)" "senha do banco"
    secret "$(env_value ZENY_DB_PASSWORD)" "senha do banco"
    secret "$(env_value ZENY_STORAGE_ROOT_PASSWORD)" "senha do MinIO"
    secret "$(env_value ZENY_STORAGE_SECRET_KEY)" "chave do MinIO"
    v=$(env_value ZENY_ENCRYPTION_KEYS)
    secret "${v#*:}" "chave de cifragem"
    v=$(env_value ZENY_JWT_KEYS)
    secret "${v#*:}" "chave do JWT"
}

# leaked_kinds <arquivo>: os tipos (nunca os valores) dos segredos e dados de hóspede achados no arquivo, ou "nenhum".
# Cada valor vai ao grep por um arquivo de padrões (process substitution), nunca por argumento; o PIN, de 6 dígitos,
# só como palavra inteira.
leaked_kinds() {
    local i opts found=()
    [ -r "$1" ] || { echo "arquivo ilegível"; return; }
    for i in "${!SECRETS[@]}"; do
        opts=-qF
        [ "${SECRET_KINDS[i]}" = PIN ] && opts=-qwF
        if grep "$opts" -f <(printf '%s\n' "${SECRETS[i]}") -- "$1"; then
            found+=("${SECRET_KINDS[i]}")
        fi
    done
    if [ "${#found[@]}" -eq 0 ]; then
        echo nenhum
    else
        printf '%s\n' "${found[@]}" | sort -u | paste -sd, -
    fi
}

# --- Fechamento, conta e governança (M6) -------------------------------------------------------------------------

# close_day <quem> <data> [arquivo de cabeçalho da aprovação]: o POST /end-of-day/runs da data, com Idempotency-Key
# própria (o req a põe em todo POST).
close_day() {
    jq -n --arg d "$2" '{businessDate: $d}' >"$T/req.json"
    if [ -n "${3:-}" ]; then
        req POST /end-of-day/runs "$1" "$T/req.json" "@$3"
    else
        req POST /end-of-day/runs "$1" "$T/req.json"
    fi
}

# "RUNNING" enquanto a execução <id> roda, "fim" quando ela termina (COMPLETED ou FAILED).
run_finished() {
    req GET "/end-of-day/runs/$1" gerente
    if [ "$(jb .status)" = RUNNING ]; then echo RUNNING; else echo fim; fi
}

# await_run <seção> <id>: espera (até 5 min) a execução terminar e a deixa lida em $T/body, conferindo as 11 etapas.
await_run() {
    poll fim 300 3 run_finished "$2"
    check "[$1] execução terminou (≤ 5 min)" fim "$GOT"
    req GET "/end-of-day/runs/$2" gerente
    check_http "[$1] execução lida" 200
    check "[$1] execução COMPLETED" COMPLETED "$(jb .status)"
    check "[$1] as 11 etapas em ordem, todas COMPLETED" \
        "$(tr ',' '\n' <<<"$STEPS" | sed 's/$/:COMPLETED/' | paste -sd, -)" \
        "$(jb '[.steps | sort_by(.position)[] | "\(.name):\(.status)"] | join(",")')"
}

# Quantas execuções do fechamento há para a data <data> (a lista inteira cabe numa página: o banco do gate é novo).
runs_on() {
    req GET "/end-of-day/runs?limit=100" gerente
    jq --arg d "$1" '[.items[] | select(.businessDate == $d)] | length' "$T/body" 2>/dev/null
}

# As pendências da execução <id> que citam a reserva, a conta, o código dela ou a UH (devem ser 0).
issues_about_stay() {
    req GET "/end-of-day/runs/$1/issues?limit=100" gerente
    jq --arg r "$RES_ID" --arg f "$FOLIO_ID" --arg c "$RES_CODE" --arg u "$ROOM_ID" \
        '[.items[] | select(.reference == $r or .reference == $f or .reference == $c or .reference == $u)] | length' \
        "$T/body" 2>/dev/null
}

# As diárias da conta pela API: "data:valor:origem,…" em ordem de data (só o código ROOM_CHARGE).
room_charges() {
    req GET "/folios/$FOLIO_ID/postings?limit=200" admin
    jq -r --arg c "$ROOM_CHARGE_CODE" \
        '[.items[] | select(.transactionCodeId == $c)] | sort_by(.businessDate)
         | map("\(.businessDate):\(.amount):\(.source)") | join(",")' "$T/body" 2>/dev/null
}

# Quantos lançamentos de diária a conta tem por noite, pelo psql do dono: "data:n,…" (a conta conferida como UUID).
room_charges_per_night() {
    require_uuid FOLIO_ID "$FOLIO_ID" || return 1
    sql <<SQL
SELECT coalesce(string_agg(d || ':' || n, ',' ORDER BY d), '') FROM (
    SELECT p.business_date::text AS d, count(*) AS n FROM billing.posting p
    JOIN billing.transaction_code c ON c.id = p.transaction_code_id
    WHERE p.folio_id = '$FOLIO_ID' AND c.system_key = 'ROOM_CHARGE' GROUP BY p.business_date) t;
SQL
}

# As tarefas de governança da UH do roteiro de D0 a D2: "data:origem,…" em ordem de data. A listagem traz só as
# tarefas de uma data (sem date, a operacional corrente), então pede cada data.
room_tasks() {
    local date all=()
    for date in "$D0" "$D1" "$D2"; do
        req GET "/housekeeping-tasks?date=$date&roomId=$ROOM_ID&limit=100" governanta
        mapfile -t -O "${#all[@]}" all < <(jb '.items[] | "\(.date):\(.source)"')
    done
    [ "${#all[@]}" -gt 0 ] && printf '%s\n' "${all[@]}" | sort | paste -sd, -
}

# A tarefa <origem> da UH do roteiro na data <data>, pelo filtro jq <expr>.
room_task() {
    req GET "/housekeeping-tasks?date=$2&roomId=$ROOM_ID&limit=100" governanta
    jq -r --arg s "$1" "[.items[] | select(.source == \$s)][0] | $3" "$T/body" 2>/dev/null
}

task_field() {
    req GET "/housekeeping-tasks/$1" governanta
    jb "$2"
}

# As execuções da rotina do roteiro na data <data>, pela API da Governanta.
routine_executions() {
    req GET "/checklist-executions?routineId=$ROUTINE_ID&businessDate=$1&limit=100" governanta
    jb '.items | length'
}

# "<modelo> <situação>" do checklist da tarefa <id> (a execução aberta pela governança).
task_checklist() {
    local execution
    execution=$(task_field "$1" .checklistExecutionId)
    req GET "/checklist-executions/$execution" governanta
    jb '"\(.execution.templateId) \(.execution.status)"'
}

# 1 se o listener da governança concluiu o BusinessDateAdvanced para a data <data>, pelo psql (a data conferida antes).
housekeeping_advanced() {
    is_date "$1" || { echo "data inválida para o SQL" >&2; return 1; }
    sql <<SQL
SELECT count(*) FROM shared.event_publication WHERE event_type = 'com.zeny.hotel.hotel.BusinessDateAdvanced'
  AND serialized_event LIKE '%"current":"$1"%'
  AND listener_id LIKE '%housekeeping.application.HousekeepingTaskListeners%' AND completion_date IS NOT NULL;
SQL
}

# Os tipos das notificações da Camareira no painel dela.
attendant_panel() {
    req GET "/notifications?limit=100" camareira
    jb '[.items[].type] | join(",")'
}

# publish_plan <seção> <data> <tarefa>: a Governanta sugere o plano da data só com a Camareira, tira as tarefas alheias
# e publica.
publish_plan() {
    local tag="" suggestion foreign path="/assignment-plans/$2"
    req GET "$path" governanta
    [ "$ST" = 200 ] && tag=$(hdr etag)
    jq -n --arg e "$EMPLOYEE_ID" '{employeeIds: [$e]}' >"$T/req.json"
    if [ -n "$tag" ]; then
        req POST "$path/suggest" governanta "$T/req.json" "If-Match: $tag"
    else
        req POST "$path/suggest" governanta "$T/req.json"
    fi
    check_http "[$1] sugestão do plano de $2 pela Governanta" 200
    suggestion=$(hdr etag)
    foreign=$(jq -c --arg t "$3" '[(.items[]?.taskId), (.unassigned[]?)] | map(select(. != $t)) | unique' "$T/body")
    jq -n --arg t "$3" --arg e "$EMPLOYEE_ID" --argjson f "${foreign:-[]}" \
        '{items: [{taskId: $t, employeeId: $e}], remove: $f}' >"$T/req.json"
    req PATCH "$path" governanta "$T/req.json" "If-Match: $suggestion"
    check_http "[$1] ajuste do plano de $2 (só a tarefa da UH do roteiro)" 200
    check "[$1] plano com a tarefa da Camareira" "$3:$EMPLOYEE_ID:1" \
        "$(jb '[.items[] | "\(.taskId):\(.employeeId):\(.position)"] | join(",")')"
    tag=$(hdr etag)
    req POST "$path/publish" governanta "" "If-Match: $tag"
    check_http "[$1] plano de $2 publicado" 200
    check "[$1] plano PUBLISHED" PUBLISHED "$(jb .status)"
}

# executed_offline <seção> <tarefa>: a Camareira inicia e conclui a tarefa por um lote de comandos offline, com as
# respostas do checklist de governança.
executed_offline() {
    local now
    now=$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)
    jq -n --arg t "$2" --arg s "$(uuid7)" --arg c "$(uuid7)" --arg now "$now" \
        '{commands: [
            {commandId: $s, type: "housekeeping.start-task", payload: {taskId: $t}, occurredAt: $now, deviceClock: $now},
            {commandId: $c, type: "housekeeping.complete-task",
             payload: {taskId: $t, answers: [{itemKey: "bed", check: "OK"}, {itemKey: "towels", check: "OK"}]},
             occurredAt: $now, deviceClock: $now}]}' >"$T/req.json"
    req POST /sync/commands camareira "$T/req.json"
    check_http "[$1] lote offline da Camareira (POST /sync/commands)" 200
    check "[$1] início e conclusão APPLIED" "APPLIED,APPLIED" "$(jb '[.results[].status] | join(",")')"
}

# publish_template <contexto> <aplicabilidade JSON>: a Governanta cria e publica um modelo com "bed" (obrigatório) e
# "towels". Deixa o id em TEMPLATE_ID.
publish_template() {
    jq -n --arg c "$1" --argjson a "$2" --arg n "Gate M6 $1 $(openssl rand -hex 3)" \
        '{name: $n, context: $c, applicability: [$a],
          sections: [{title: "Quarto", items: [{key: "bed", label: "BED", type: "CHECK", required: true},
                                               {key: "towels", label: "TOWELS", type: "CHECK"}]}]}' >"$T/req.json"
    req POST /checklist-templates governanta "$T/req.json"
    check_http "[3] modelo $1 pela Governanta" 201
    TEMPLATE_ID=$(jb .id)
    req GET "/checklist-templates/$TEMPLATE_ID" governanta
    local tag
    tag=$(hdr etag)
    req POST "/checklist-templates/$TEMPLATE_ID/publish" governanta "" "If-Match: $tag"
    check_http "[3] modelo $1 publicado" 201
}

# set_credits <id do tipo de limpeza> <créditos>: a Governanta põe os créditos do tipo de UH do roteiro (a lista
# inteira, mantidos os de outros tipos de UH).
set_credits() {
    req GET "/cleaning-types/$1" governanta
    local tag
    tag=$(hdr etag)
    jq --arg t "$TYPE_ID" --argjson n "$2" \
        '{code, name, requiresInspection, defaultPriority, linenChangeEveryNights, checklistTemplateId,
          credits: ([.credits[] | select(.roomTypeId != $t) | {roomTypeId, credits}] + [{roomTypeId: $t, credits: $n}])}' \
        "$T/body" >"$T/req.json"
    req PUT "/cleaning-types/$1" governanta "$T/req.json" "If-Match: $tag"
}

# ==================================================================================================================
# Seções. Cada uma é uma função; o estado que passa de uma a outra fica em variáveis globais (as datas D0 a D2, os ids
# da reserva, da conta, da UH, da Camareira, da rotina, dos modelos e das execuções do fechamento). Para acrescentar uma
# seção, escreva a função e a ponha em SECTIONS (no fim do arquivo), antes da varredura, que fica por último.
# ==================================================================================================================

s1_public() {
    section "1. Públicos"
    req GET "$BASE/actuator/health/readiness" -
    check_http "[1] readiness" 200
    check "[1] readiness UP" UP "$(jb .status)"
    req GET /meta/version -
    check_http "[1] GET /meta/version" 200
    req GET "$BASE/actuator/prometheus" -
    check_http "[1] /actuator/prometheus pelo proxy" 404
    req GET /end-of-day/runs -
    check_http "[1] GET /end-of-day/runs sem token" 401
}

s2_users() {
    section "2. Administrador inicial e operadores dos modelos de role (Recepcionista, Gerente, Governanta, Camareira)"
    local admin_initial admin_login
    admin_login=$(env_value ZENY_ADMIN_LOGIN)
    admin_login=${admin_login:-admin}
    admin_initial=$(env_value ZENY_ADMIN_PASSWORD)
    secret "$admin_initial" "senha do admin"
    LOGIN_OF[admin]=$admin_login
    PASSWORD_OF[admin]=$(openssl rand -hex 16)
    secret "${PASSWORD_OF[admin]}" "senha do admin"

    login admin "$admin_login" "$admin_initial"
    check_http "[2] login do admin com a senha inicial" 200
    check "[2] troca de senha obrigatória" true "$(jb .passwordChangeRequired)"
    req GET /users admin
    check_http "[2] GET /users antes da troca" 403
    check "[2] code PASSWORD_CHANGE_REQUIRED" PASSWORD_CHANGE_REQUIRED "$(problem_code)"
    change_password admin "$admin_initial" "${PASSWORD_OF[admin]}"
    check_http "[2] troca da senha do admin" 204
    login admin "$admin_login" "${PASSWORD_OF[admin]}"
    check_http "[2] login do admin com a senha nova" 200
    check "[2] admin sem troca pendente" false "$(jb .passwordChangeRequired)"

    req GET "/roles?limit=100" admin
    check_http "[2] GET /roles" 200
    declare -A role_of template_of=([recepcao]=RECEPTIONIST [gerente]=MANAGER [governanta]=HOUSEKEEPER
        [camareira]=ROOM_ATTENDANT)
    local who
    for who in recepcao gerente governanta camareira; do
        role_of[$who]=$(jq -r --arg t "${template_of[$who]}" '[.items[] | select(.template == $t)][0].id' "$T/body")
        check "[2] modelo ${template_of[$who]} no boot" true "$(bool is_uuid "${role_of[$who]}")"
    done

    MANAGER_PIN=$(printf '%06d' $((16#$(openssl rand -hex 3) % 1000000)))
    secret "$MANAGER_PIN" PIN
    local provisional
    for who in recepcao gerente governanta camareira; do
        provisional=$(openssl rand -hex 16)
        secret "$provisional" "senha de usuário"
        LOGIN_OF[$who]=$who
        PASSWORD_OF[$who]=$(openssl rand -hex 16)
        secret "${PASSWORD_OF[$who]}" "senha de usuário"
        if [ "$who" = gerente ]; then
            PROVISIONAL=$provisional PIN=$MANAGER_PIN jq -n --arg r "${role_of[$who]}" \
                '{login: "gerente", name: "Gerente do gate", password: env.PROVISIONAL, pin: env.PIN, roleIds: [$r]}' \
                >"$T/req.json"
        else
            PROVISIONAL=$provisional jq -n --arg l "$who" --arg r "${role_of[$who]}" \
                '{login: $l, name: "Operador do gate (\($l))", password: env.PROVISIONAL, roleIds: [$r]}' \
                >"$T/req.json"
        fi
        req POST /users admin "$T/req.json"
        check_http "[2] usuário $who criado pelo admin" 201
        check "[2] $who com a role do modelo" "${role_of[$who]}" "$(jb '.roles[0].id')"
        case $who in
            recepcao) RECEPTIONIST_ID=$(jb .id) ;;
            gerente)
                check "[2] gerente com PIN" true "$(jb .hasPin)"
                MANAGER_ID=$(jb .id)
                ;;
            governanta) HOUSEKEEPER_ID=$(jb .id) ;;
            camareira) ATTENDANT_ID=$(jb .id) ;;
        esac
        login "$who" "$who" "$provisional"
        check_http "[2] login de $who com a senha provisória" 200
        change_password "$who" "$provisional" "${PASSWORD_OF[$who]}"
        check_http "[2] troca da senha de $who" 204
        login "$who" "$who" "${PASSWORD_OF[$who]}"
        check_http "[2] login de $who com a senha nova" 200
        check "[2] $who sem troca pendente" false "$(jb .passwordChangeRequired)"
    done
    check "[2] ids dos quatro operadores" "true true true true" \
        "$(bool is_uuid "$RECEPTIONIST_ID") $(bool is_uuid "$MANAGER_ID") $(bool is_uuid "$HOUSEKEEPER_ID") $(bool is_uuid "$ATTENDANT_ID")"
    LOGIN_AT=$SECONDS
}

s3_setup() {
    section "3. Configuração mínima: horário do fechamento, UH, plano, governança, escala e FNRH"
    business_date gerente
    check_http "[3] data operacional" 200
    check "[3] data operacional é uma data" true "$(bool is_date "$BD")"
    D0=$BD
    D1=$(date -d "$D0 +1 day" +%F)
    D2=$(date -d "$D0 +2 days" +%F)

    # O horário do fechamento (hotel.settings.manage, só do administrador).
    req GET /hotel/operational-settings admin
    check_http "[3] parâmetros operacionais" 200
    local tag
    tag=$(hdr etag)
    jq --arg t "$END_OF_DAY_TIME" '. + {endOfDayTime: $t}' "$T/body" >"$T/settings.json"
    req PUT /hotel/operational-settings gerente "$T/settings.json" "If-Match: $tag"
    check_http "[3] o Gerente não muda o horário do fechamento" 403
    req PUT /hotel/operational-settings admin "$T/settings.json" "If-Match: $tag"
    check_http "[3] horário do fechamento pelo admin" 200
    check "[3] horário do fechamento gravado" "$END_OF_DAY_TIME" "$(jb '.endOfDayTime | .[0:5]')"

    # O tipo de UH, a UH 1301 (limpa e inspecionada), as políticas, o plano e os preços, pelo Gerente.
    jq -n '{code: "GATE6", name: "Standard do gate M6", description: "Quarto com varanda", standardAdults: 2,
        standardChildren: 0, maxAdults: 3, maxChildren: 1, bedSetup: "1 casal", featureIds: [], displayOrder: 10}' \
        >"$T/req.json"
    req POST /room-types gerente "$T/req.json"
    check_http "[3] tipo de UH pelo Gerente" 201
    TYPE_ID=$(jb .id)
    jq -n --arg t "$TYPE_ID" '{number: "1301", floor: "13º andar", roomTypeId: $t, featureIds: [], notes: "Gate do M6"}' \
        >"$T/req.json"
    req POST /rooms gerente "$T/req.json"
    check_http "[3] UH 1301 pelo Gerente" 201
    ROOM_ID=$(jb .id)
    local deadline=$((SECONDS + 60)) total
    while :; do
        req GET "/room-type-inventory?roomTypeId=$TYPE_ID&from=$D0&to=$D0" gerente
        total=$(jb '.items[0].total')
        [ "$total" = 1 ] || [ "$SECONDS" -ge "$deadline" ] && break
        sleep 2
    done
    check "[3] inventário do tipo com a UH" 1 "$total"
    housekeeping "$ROOM_ID" MARK_CLEAN
    check_http "[3] UH 1301 limpa pelo Gerente" 200
    housekeeping "$ROOM_ID" MARK_INSPECTED
    check_http "[3] UH 1301 inspecionada pelo Gerente" 200
    check "[3] UH 1301 pronta para check-in" true "$(jb .readyForCheckIn)"

    jq -n '{name: "Flexível do gate M6", deadlineHours: 48, penaltyType: "NIGHTS", penaltyPercent: null,
        penaltyNights: 1, penaltyAmount: null}' >"$T/req.json"
    req POST /cancellation-policies gerente "$T/req.json"
    check_http "[3] política de cancelamento" 201
    local cancellation guarantee
    cancellation=$(jb .id)
    jq -n '{name: "Sem garantia do gate M6", type: "NONE", depositPercent: null, dueDaysBefore: null}' >"$T/req.json"
    req POST /guarantee-policies gerente "$T/req.json"
    check_http "[3] política de garantia" 201
    guarantee=$(jb .id)
    jq -n --arg t "$TYPE_ID" --arg c "$cancellation" --arg g "$guarantee" \
        '{code: "GATE6BAL", name: "Balcão do gate M6", description: "Tarifa de balcão com café", mealPlan: "BREAKFAST",
          category: "NORMAL", channels: ["DIRECT", "WEBSITE"], roomTypeIds: [$t], packageIds: [],
          cancellationPolicyId: $c, guaranteePolicyId: $g}' >"$T/req.json"
    req POST /rate-plans gerente "$T/req.json"
    check_http "[3] plano tarifário" 201
    PLAN_ID=$(jb .id)
    jq -n --arg t "$TYPE_ID" --arg f "$D0" --arg u "$(date -d "$D0 +30 days" +%F)" --arg one "$ONE_ADULT" \
        --arg two "$TWO_ADULTS" \
        '{roomTypeIds: [$t], from: $f, to: $u, seasonId: null, weekdays: [],
          prices: {oneAdult: $one, twoAdults: $two, extraAdult: "120.00", children: []}}' >"$T/req.json"
    req POST "/rate-plans/$PLAN_ID/prices/bulk-update" gerente "$T/req.json"
    check_http "[3] preços de 31 dias" 200

    # Os tipos de limpeza da carga inicial e os créditos do tipo de UH do gate, pela Governanta.
    req GET "/cleaning-types?limit=100" governanta
    check_http "[3] tipos de limpeza" 200
    STAYOVER_TYPE=$(jb '[.items[] | select(.key == "STAYOVER")][0].id')
    CHECKOUT_TYPE=$(jb '[.items[] | select(.key == "CHECKOUT")][0].id')
    check "[3] permanência e saída na carga inicial" "true true" \
        "$(bool is_uuid "$STAYOVER_TYPE") $(bool is_uuid "$CHECKOUT_TYPE")"
    check "[3] a permanência não pede inspeção e a saída pede (D5 da E01)" "false true" \
        "$(jb '"\([.items[] | select(.key == "STAYOVER")][0].requiresInspection) \([.items[] | select(.key == "CHECKOUT")][0].requiresInspection)"')"
    set_credits "$STAYOVER_TYPE" "$STAYOVER_CREDITS"
    check_http "[3] créditos da permanência no tipo de UH" 200
    check "[3] permanência sem tipo de UH sem crédito do gate" false \
        "$(jq --arg t "$TYPE_ID" '.missingCreditRoomTypeIds | index($t) != null' "$T/body")"
    set_credits "$CHECKOUT_TYPE" "$CHECKOUT_CREDITS"
    check_http "[3] créditos da saída no tipo de UH" 200
    check "[3] créditos da saída gravados" "$CHECKOUT_CREDITS" \
        "$(jq --arg t "$TYPE_ID" '[.credits[] | select(.roomTypeId == $t)][0].credits' "$T/body")"

    # Os modelos de checklist da Governanta (governança e inspeção, só no tipo de UH do gate) e a rotina diária.
    publish_template HOUSEKEEPING \
        "$(jq -nc --arg t "$TYPE_ID" --arg s "$STAYOVER_TYPE" --arg c "$CHECKOUT_TYPE" \
            '{roomTypeIds: [$t], cleaningTypeIds: [$s, $c]}')"
    HK_TEMPLATE=$TEMPLATE_ID
    publish_template INSPECTION "$(jq -nc --arg t "$TYPE_ID" '{roomTypeIds: [$t]}')"
    req GET "/departments?limit=100" gerente
    check_http "[3] setores" 200
    HK_DEPARTMENT=$(jb '[.items[] | select(.key == "HOUSEKEEPING")][0].id')
    check "[3] setor de governança pré-carregado" true "$(bool is_uuid "$HK_DEPARTMENT")"
    jq -n --arg tpl "$HK_TEMPLATE" --arg d "$HK_DEPARTMENT" --arg r "$ROOM_ID" \
        '{name: "Rotina do gate M6", templateId: $tpl, departmentId: $d, frequency: "DAILY", weekdays: [],
          dueAt: "08:00", location: {type: "rooms.room", id: $r}}' >"$T/req.json"
    req POST /checklist-routines governanta "$T/req.json"
    check_http "[3] rotina diária pela Governanta" 201
    ROUTINE_ID=$(jb .id)

    # A Camareira como colaboradora da governança, escalada em D1 e D2, pelo Gerente.
    jq -n --arg d "$HK_DEPARTMENT" --arg u "$ATTENDANT_ID" \
        '{name: "Camareira do gate", position: "Camareira", departmentId: $d, userId: $u}' >"$T/req.json"
    req POST /employees gerente "$T/req.json"
    check_http "[3] colaboradora (Camareira) pelo Gerente" 201
    EMPLOYEE_ID=$(jb .id)
    jq -n --arg d "$HK_DEPARTMENT" '{departmentId: $d, name: "Manhã do gate M6", start: "07:00", end: "15:00"}' \
        >"$T/req.json"
    req POST /shifts gerente "$T/req.json"
    check_http "[3] turno das 7h às 15h" 201
    local shift date
    shift=$(jb .id)
    for date in "$D1" "$D2"; do
        jq -n --arg e "$EMPLOYEE_ID" --arg s "$shift" --arg d "$date" \
            '{employeeId: $e, kind: "SHIFT", from: $d, to: $d, shiftId: $s}' >"$T/req.json"
        req POST /schedule/entries gerente "$T/req.json"
        check_http "[3] Camareira escalada em $date" 200
    done

    # FNRH em homologação (o mock), com a credencial sorteada aqui e o CPF solicitante de teste.
    FNRH_USER="gate-$(openssl rand -hex 4)"
    FNRH_PASSWORD=$(openssl rand -hex 16)
    secret "$FNRH_USER" "usuário da FNRH"
    secret "$FNRH_PASSWORD" "senha da FNRH"
    secret "$(printf '%s:%s' "$FNRH_USER" "$FNRH_PASSWORD" | openssl base64 -A)" "Basic da FNRH"
    req GET /fnrh/settings admin
    check_http "[3] configuração da FNRH" 200
    tag=$(hdr etag)
    FNRH_U=$FNRH_USER FNRH_P=$FNRH_PASSWORD jq -n --arg cpf "$REQUESTER_CPF" \
        '{environment: "HOMOLOGATION", username: env.FNRH_U, password: env.FNRH_P, defaultRequesterCpf: $cpf,
          enabled: true}' >"$T/req.json"
    req PUT /fnrh/settings gerente "$T/req.json" "If-Match: $tag"
    check_http "[3] o Gerente não configura a FNRH" 403
    req PUT /fnrh/settings admin "$T/req.json" "If-Match: $tag"
    check_http "[3] FNRH configurada pelo admin" 200
    check "[3] FNRH em homologação e ligada" "HOMOLOGATION true true" \
        "$(jb '"\(.environment) \(.enabled) \(.passwordSet)"')"
    jq -n --arg cpf "$REQUESTER_CPF" '{cpf: $cpf}' >"$T/req.json"
    req PUT "/fnrh/requesters/$RECEPTIONIST_ID" admin "$T/req.json"
    check_http "[3] CPF solicitante de teste da Recepcionista" 200

    req GET "/transaction-codes?limit=100" admin
    check_http "[3] códigos de lançamento" 200
    ROOM_CHARGE_CODE=$(jb '[.items[] | select(.key == "ROOM_CHARGE")][0].id')
    check "[3] código da diária" true "$(bool is_uuid "$ROOM_CHARGE_CODE")"
}

s4_check_in() {
    section "4. D0: reserva de duas noites com hora prevista de chegada, check-in e FNRH"
    ensure_tokens
    new_guest Titular
    check_http "[4] hóspede titular pela Recepcionista" 201
    HOLDER_ID=$GUEST_ID
    jq -n --arg h "$HOLDER_ID" --arg t "$TYPE_ID" --arg p "$PLAN_ID" --arg a "$D0" --arg d "$D2" \
        --arg eta "$EXPECTED_ARRIVAL" \
        '{holderGuestId: $h, origin: "PHONE", externalId: null,
          reservations: [{roomTypeId: $t, ratePlanId: $p, arrival: $a, departure: $d, adults: 1, childAges: [],
            segment: "LEISURE", specialRequests: null, expectedArrivalTime: $eta,
            guests: [{guestId: $h, primary: true}], nightlyRates: []}]}' >"$T/req.json"
    req POST /bookings recepcao "$T/req.json"
    check_http "[4] reserva D0 a D2 pela Recepcionista" 201
    RES_ID=$(jb '.reservations[0].id')
    FOLIO_ID=$(jb '.reservations[0].folioId')
    RES_CODE=$(jb '.reservations[0].code')
    check "[4] reserva confirmada" CONFIRMED "$(jb '.reservations[0].status')"
    check "[4] reserva e conta com id" "true true" "$(bool is_uuid "$RES_ID") $(bool is_uuid "$FOLIO_ID")"
    req GET "/reservations/$RES_ID" recepcao
    check_http "[4] reserva lida" 200
    check "[4] hora prevista de chegada (E22)" "$EXPECTED_ARRIVAL" "$(jb '.reservation.expectedArrivalTime | .[0:5]')"
    check "[4] duas noites, D0 e D1, a $ONE_ADULT" "$D0:$ONE_ADULT,$D1:$ONE_ADULT" \
        "$(jb '[.reservation.nightlyRates[] | "\(.date):\(.amount)"] | join(",")')"

    jq -n --arg r "$ROOM_ID" '{roomId: $r}' >"$T/req.json"
    req POST "/reservations/$RES_ID/assign-room" recepcao "$T/req.json"
    check_http "[4] alocação da UH 1301" 200
    jq -n --arg h "$HOLDER_ID" \
        '{guests: [{guestId: $h, primary: true, travelReason: "LAZER_FERIAS", transportMode: "AUTOMOVEL",
                    minorAuthorization: null}]}' >"$T/req.json"
    req POST "/reservations/$RES_ID/check-in" recepcao "$T/req.json"
    check_http "[4] check-in em D0 pela Recepcionista" 200
    check "[4] reserva em casa na 1301" "IN_HOUSE $ROOM_ID" "$(jb '"\(.reservation.status) \(.reservation.roomId)"')"
    check "[4] UH ocupada" OCCUPIED "$(jb .room.occupancy)"
    check "[4] nenhuma diária antes do fechamento" "" "$(room_charges)"

    poll "REGISTER_RESERVATION:SENT,CHECK_IN:SENT" 180 5 fnrh_submissions
    check "[4] FNRH: registro e check-in enviados ao mock (≤ 3 min)" "REGISTER_RESERVATION:SENT,CHECK_IN:SENT" "$GOT"
}

s5_close_d0() {
    section "5. Fechamento de D0 pela Recepcionista, com a aprovação do Gerente (PIN)"
    ensure_tokens
    close_day recepcao "$D0"
    check_http "[5] fechamento pela Recepcionista sem aprovação" 403
    check "[5] code APPROVAL_REQUIRED" APPROVAL_REQUIRED "$(problem_code)"
    check "[5] ação, permissão e recurso da aprovação" \
        "endofday.run.start endofday.runs.execute endofday.business-date:$D0" \
        "$(jb '"\(.action) \(.permission) \(.resourceRef)"')"
    PIN=$MANAGER_PIN jq '{action, permission, resourceRef, approverLogin: "gerente", approverPin: env.PIN,
        reason: "Fechamento do dia pela recepção"}' "$T/body" >"$T/approval.json"
    check "[5] a recusa não cria execução" 0 "$(runs_on "$D0")"

    # A aprovação na sessão da Recepcionista (o token vale só nela, uma vez, 2 min): nada de ensure_tokens até o uso.
    NO_IDEM=1 req POST /auth/approvals recepcao "$T/approval.json"
    rm -f -- "$T/approval.json"
    check_http "[5] aprovação do Gerente com login e PIN" 201
    local approval_token
    approval_token=$(jb .approvalToken)
    secret "$approval_token" "token de aprovação"
    check "[5] aprovador é o Gerente" "$MANAGER_ID" "$(jb .approverId)"
    printf 'X-Approval-Token: %s\n' "$approval_token" >"$T/approval.hdr"
    : >"$T/body"
    close_day recepcao "$D0" "$T/approval.hdr"
    rm -f -- "$T/approval.hdr"
    check_http "[5] fechamento de D0 com a aprovação" 201
    RUN_D0=$(jb .id)
    check "[5] execução manual pela Recepcionista" "MANUAL $RECEPTIONIST_ID" "$(jb '"\(.trigger) \(.triggeredBy)"')"
    await_run 5 "$RUN_D0"

    check "[5] a diária da noite D0, uma vez, pelo fechamento" "$D0:$ONE_ADULT:END_OF_DAY" "$(room_charges)"
    check "[5] psql: um lançamento de diária por noite" "$D0:1" "$(room_charges_per_night)"
    check "[5] UH ocupada e Suja pelo fechamento" "OCCUPIED DIRTY" "$(room_status "$ROOM_ID")"
    business_date recepcao
    check "[5] data operacional em D1" "$D1" "$BD"
    check "[5] nenhuma pendência da reserva, da conta ou da UH" 0 "$(issues_about_stay "$RUN_D0")"
    poll 1 60 3 routine_executions "$D1"
    check "[5] rotina de checklist de D1 gerada pelo avanço (≤ 1 min)" 1 "$GOT"
}

s6_stayover() {
    section "6. D1: permanência gerada pelo avanço, distribuída, executada offline e a UH inspecionada"
    ensure_tokens
    poll "$D1:STAYOVER" 60 3 room_tasks
    check "[6] tarefa de permanência de D1 na UH (≤ 1 min)" "$D1:STAYOVER" "$GOT"
    STAYOVER_TASK=$(room_task STAYOVER "$D1" .id)
    check "[6] tarefa com id" true "$(bool is_uuid "$STAYOVER_TASK")"
    check "[6] permanência com o tipo e os créditos configurados" "$STAYOVER_TYPE $STAYOVER_CREDITS" \
        "$(room_task STAYOVER "$D1" '"\(.cleaningTypeId) \(.credits)"')"
    publish_plan 6 "$D1" "$STAYOVER_TASK"
    check "[6] tarefa atribuída à Camareira" "ASSIGNED $EMPLOYEE_ID" \
        "$(task_field "$STAYOVER_TASK" '"\(.status) \(.assignedEmployeeId)"')"
    poll notifications.task-assigned 30 2 attendant_panel
    check "[6] painel da Camareira com a atribuição (≤ 30 s)" notifications.task-assigned "$GOT"

    executed_offline 6 "$STAYOVER_TASK"
    check "[6] permanência DONE" DONE "$(task_field "$STAYOVER_TASK" .status)"
    check "[6] checklist da permanência concluído com o modelo do gate" "$HK_TEMPLATE COMPLETED" \
        "$(task_checklist "$STAYOVER_TASK")"
    check "[6] UH ocupada e Limpa" "OCCUPIED CLEAN" "$(room_status "$ROOM_ID")"

    jq -n --arg r "$ROOM_ID" '{roomId: $r}' >"$T/req.json"
    req POST /room-inspections governanta "$T/req.json"
    check_http "[6] inspeção voluntária da UH pela Governanta" 200
    check "[6] inspeção concluída e aprovada" "COMPLETED APPROVED" "$(jb '"\(.inspection.status) \(.inspection.result)"')"
    check "[6] UH ocupada e Inspecionada" "OCCUPIED INSPECTED" "$(room_status "$ROOM_ID")"
}

s7_close_d1() {
    section "7. Fechamento de D1 pelo Gerente: a segunda diária e a data em D2"
    ensure_tokens
    close_day gerente "$D1"
    check_http "[7] fechamento de D1 pelo Gerente (com a permissão)" 201
    RUN_D1=$(jb .id)
    check "[7] execução manual pelo Gerente" "MANUAL $MANAGER_ID" "$(jb '"\(.trigger) \(.triggeredBy)"')"
    await_run 7 "$RUN_D1"
    check "[7] as diárias de D0 e D1, uma vez cada" "$D0:$ONE_ADULT:END_OF_DAY,$D1:$ONE_ADULT:END_OF_DAY" \
        "$(room_charges)"
    check "[7] psql: um lançamento de diária por noite" "$D0:1,$D1:1" "$(room_charges_per_night)"
    check "[7] UH ocupada e Suja de novo pelo fechamento" "OCCUPIED DIRTY" "$(room_status "$ROOM_ID")"
    business_date recepcao
    check "[7] data operacional em D2" "$D2" "$BD"
    check "[7] nenhuma pendência da reserva, da conta ou da UH" 0 "$(issues_about_stay "$RUN_D1")"
    poll 1 60 3 housekeeping_advanced "$D2"
    check "[7] psql: governança tratou o avanço para D2 (≤ 1 min)" 1 "$GOT"
    check "[7] quem sai em D2 não tem permanência em D2" "$D1:STAYOVER" "$(room_tasks)"
}

s8_check_out() {
    section "8. D2: pagamento do saldo e check-out com saldo zero, sem diária a mais"
    ensure_tokens
    jq -n '{name: "Caixa do gate M6", blindClose: false}' >"$T/req.json"
    req POST /cash-points gerente "$T/req.json"
    check_http "[8] ponto de caixa pelo Gerente" 201
    jq -n --arg c "$(jb .id)" '{cashPointId: $c, openingFloat: "0.00"}' >"$T/req.json"
    req POST /cashier-shifts recepcao "$T/req.json"
    check_http "[8] turno de caixa aberto pela Recepcionista" 201
    local shift due
    shift=$(jb .id)
    req GET "/folios/$FOLIO_ID" recepcao
    check_http "[8] conta da reserva" 200
    due=$(jb .balance)
    check "[8] saldo: as duas diárias" "$(money $((2 * $(cents "$ONE_ADULT"))))" "$due"
    pay "$FOLIO_ID" "$due"
    check_http "[8] pagamento do saldo em dinheiro" 201

    req POST "/reservations/$RES_ID/check-out" recepcao
    check_http "[8] check-out em D2 com saldo zero" 200
    check "[8] reserva encerrada" CHECKED_OUT "$(jb .reservation.status)"
    check "[8] nenhuma diária lançada no check-out" 0 "$(jb '.stayCharges | length')"
    check "[8] conta encerrada" "$FOLIO_ID:CLOSED" "$(jb '[.closedFolios[] | "\(.folioId):\(.status)"] | join(",")')"
    check "[8] UH vaga e Suja" "VACANT DIRTY" "$(jb '"\(.room.occupancy) \(.room.housekeepingStatus)"')"
    req GET "/folios/$FOLIO_ID" recepcao
    check "[8] conta fechada com saldo zero" "CLOSED 0.00" "$(jb '"\(.status) \(.balance)"')"
    jq -n --arg a "$due" '{counts: [{method: "CASH", amount: $a}]}' >"$T/req.json"
    req POST "/cashier-shifts/$shift/close" recepcao "$T/req.json"
    check_http "[8] turno fechado pela Recepcionista" 200
    check "[8] turno sem diferença" false "$(jb .shift.hasDifference)"
    check "[8] diárias inalteradas pelo check-out" "$D0:$ONE_ADULT:END_OF_DAY,$D1:$ONE_ADULT:END_OF_DAY" \
        "$(room_charges)"
    check "[8] psql: um lançamento de diária por noite" "$D0:1,$D1:1" "$(room_charges_per_night)"
}

s9_departure_task() {
    section "9. D2: tarefa de saída gerada pelo check-out, executada offline e inspecionada"
    ensure_tokens
    poll "$D1:STAYOVER,$D2:CHECKOUT" 60 3 room_tasks
    check "[9] tarefa de saída de D2 pelo GuestCheckedOut (≤ 1 min)" "$D1:STAYOVER,$D2:CHECKOUT" "$GOT"
    DEPARTURE_TASK=$(room_task CHECKOUT "$D2" .id)
    check "[9] tarefa com id" true "$(bool is_uuid "$DEPARTURE_TASK")"
    check "[9] saída com o tipo e os créditos configurados" "$CHECKOUT_TYPE $CHECKOUT_CREDITS" \
        "$(room_task CHECKOUT "$D2" '"\(.cleaningTypeId) \(.credits)"')"
    publish_plan 9 "$D2" "$DEPARTURE_TASK"
    executed_offline 9 "$DEPARTURE_TASK"
    check "[9] saída aguardando inspeção" AWAITING_INSPECTION "$(task_field "$DEPARTURE_TASK" .status)"
    check "[9] UH vaga e Limpa" "VACANT CLEAN" "$(room_status "$ROOM_ID")"

    req GET "/housekeeping-tasks/$DEPARTURE_TASK" governanta
    local tag
    tag=$(hdr etag)
    req POST "/housekeeping-tasks/$DEPARTURE_TASK/inspection/start" governanta "" "If-Match: $tag"
    check_http "[9] inspeção da saída iniciada pela Governanta" 200
    tag=$(hdr etag)
    jq -n '{result: "APPROVED", answers: [{itemKey: "bed", check: "OK"}]}' >"$T/req.json"
    req POST "/housekeeping-tasks/$DEPARTURE_TASK/inspection/complete" governanta "$T/req.json" "If-Match: $tag"
    check_http "[9] inspeção da saída concluída" 200
    check "[9] inspeção aprovada" APPROVED "$(jb .inspection.result)"
    check "[9] saída DONE" DONE "$(task_field "$DEPARTURE_TASK" .status)"
    check "[9] checklist da saída concluído com o modelo do gate" "$HK_TEMPLATE COMPLETED" \
        "$(task_checklist "$DEPARTURE_TASK")"
    check "[9] UH vaga e Inspecionada" "VACANT INSPECTED" "$(room_status "$ROOM_ID")"
}

s10_fnrh() {
    section "10. FNRH: registro, check-in e check-out entregues ao mock"
    ensure_tokens
    poll "REGISTER_RESERVATION:SENT,CHECK_IN:SENT,CHECK_OUT:SENT" 180 5 fnrh_submissions
    check "[10] submissões da reserva enviadas (≤ 3 min)" "REGISTER_RESERVATION:SENT,CHECK_IN:SENT,CHECK_OUT:SENT" "$GOT"
    check "[10] outbox: GuestCheckedOut no listener do fnrh" COMPLETED "$(await_fnrh_publication GuestCheckedOut)"
    check "[10] fnrh-mock: nenhum pedido não casado" 0 "$(mock_unmatched)"
    check "[10] fnrh-mock: reserva registrada" true \
        "$(bool [ "$(mock_count POST '^/FNRH_API/rest/v2/reservas$')" -ge 1 ])"
    check "[10] fnrh-mock: hóspede incluído na reserva" true \
        "$(bool [ "$(mock_count POST '^/FNRH_API/rest/v2/reservas/[^/]+/hospedes$')" -ge 1 ])"
    check "[10] fnrh-mock: check-out do hóspede" true \
        "$(bool [ "$(mock_count PATCH '^/FNRH_API/rest/v2/hospedes/[^/]+/checkout$')" -ge 1 ])"
    check "[10] fnrh-mock: todos os pedidos com Basic" true "$(mock_all_basic)"
}

# O começo de todo JWT ({"alg"… em base64url). Fica numa variável: os rótulos das conferências não o levam, senão a
# varredura da própria saída o acharia nos rótulos.
JWT_PREFIX=eyJ

s11_leak_sweep() {
    section "11. Varredura: logs do compose (sem o fnrh-mock) e a própria saída, sem segredo nem dado de hóspede"
    check "[11] fnrh-mock: nenhum pedido não casado até o fim" 0 "$(mock_unmatched)"
    check "[11] valores procurados (≥ 25)" true "$(bool [ "${#SECRETS[@]}" -ge 25 ])"
    local svc rc
    for svc in api db proxy storage storage-init; do
        "${DC[@]}" logs --no-color "$svc" >"$T/log-$svc" 2>"$T/compose.out"
        rc=$?
        check "[11] logs do $svc lidos" 0 "$rc"
        check "[11] logs do $svc: nenhum segredo nem dado de hóspede" nenhum "$(leaked_kinds "$T/log-$svc")"
        check "[11] logs do $svc: nenhum JWT (prefixo dos access tokens)" 0 "$(grep -c "$JWT_PREFIX" "$T/log-$svc")"
    done
    check "[11] logs da api não vazios" true "$(bool [ -s "$T/log-api" ])"
    check "[11] logs do db não vazios" true "$(bool [ -s "$T/log-db" ])"
    rm -f -- "$T"/log-*
    check "[11] saída do roteiro não vazia" true "$(bool [ -s "$OUT" ])"
    check "[11] saída do roteiro: nenhum segredo nem dado de hóspede" nenhum "$(leaked_kinds "$OUT")"
    check "[11] saída do roteiro: nenhum JWT (prefixo dos access tokens)" 0 "$(grep -c "$JWT_PREFIX" "$OUT")"
}

# ==================================================================================================================

SECTIONS=(s1_public s2_users s3_setup s4_check_in s5_close_d0 s6_stayover s7_close_d1 s8_check_out
    s9_departure_task s10_fnrh s11_leak_sweep)

register_env_secrets
printf 'Roteiro do gate do M6 em %s (projeto %s), %s\n' "$BASE" "$PROJECT" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
for s in "${SECTIONS[@]}"; do
    "$s"
done
summary
exit $?
