#!/usr/bin/env bash
# Roteiro curl do gate do M5 (E12): o cenário do marco pelo proxy do compose isolado do gate, no profile prod.
#
#   docker/gate/m5-roteiro.sh <base-url> <projeto> <env-file>
#
#   <base-url>  o proxy do gate, ex.: http://localhost:18112
#   <projeto>   o projeto Compose do gate (o mesmo -p do up), ex.: zeny-e12-gate
#   <env-file>  o gate.env gerado pelo gate-env.sh (a senha do admin é lida dele, sem eco)
#
# Pré-requisito: o ambiente recém-subido (up -d --build depois de um down -v) e com o readiness UP; o roteiro faz a
# troca obrigatória da senha do admin inicial, então não roda duas vezes no mesmo banco. Ele não sobe nem derruba nada:
# o up e o down -v (sempre, também em falha) ficam com quem o chama (docker/gate/README.md).
#
# Ferramentas: curl, jq, openssl e o psql do contêiner db (docker compose exec, como o dono dos schemas), além do bash
# e do coreutils. Toda conferência passa por check, que imprime o rótulo e OK/FALHA e, na falha, o esperado e o obtido
# com os segredos e os dados de hóspede trocados por ***. Senhas, PIN, tokens e a credencial da FNRH ficam só em
# variáveis e em arquivos temporários (modo 600, apagados na saída), nunca em argumentos de processo nem na saída. A
# saída inteira vai também para <dir do env>/m5-roteiro.out (ou M5_ROTEIRO_OUT), para a varredura de vazamento.
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

T=$(mktemp -d "${TMPDIR:-/tmp}/m5-roteiro.XXXXXX")
trap 'rm -rf -- "$T"' EXIT

OUT=${M5_ROTEIRO_OUT:-$(dirname -- "$ENV_FILE")/m5-roteiro.out}
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

# As submissões da FNRH da reserva, por ordem de criação: "OPERAÇÃO:SITUAÇÃO,…".
fnrh_submissions() {
    req GET "/fnrh/submissions?limit=100" recepcao
    jq -r --arg r "$RES_ID" \
        '[.items[] | select(.reservationId == $r)] | sort_by(.createdAt) | map("\(.operation):\(.status)") | join(",")' \
        "$T/body" 2>/dev/null
}

# Espera (até 3 min, teto do gate) as submissões da reserva serem <esperado>; devolve as últimas lidas.
await_fnrh_submissions() {
    local expected=$1 deadline=$((SECONDS + 180)) got
    while :; do
        got=$(fnrh_submissions)
        [ "$got" = "$expected" ] || [ "$SECONDS" -ge "$deadline" ] && break
        sleep 5
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

# true se o mock recebeu um POST /pessoas com genero_id OUTRO e a descrição de gênero G_DESC (o jq só responde
# true/false: o corpo, com nome e documento, nunca sai).
mock_person_other() {
    mock requests | G_DESC=$G_DESC jq '[.requests[].request
        | select(.method == "POST" and (.url | test("^/FNRH_API/rest/v2/pessoas$")))
        | (.body | fromjson? // {}) | .pessoa // {}
        | select(.genero_id == "OUTRO" and .GeneroDescricao == env.G_DESC)] | length >= 1'
}

mock_up() {
    if mock health >/dev/null 2>&1; then echo true; else echo false; fi
}

# A situação das submissões da FNRH da reserva <id>, em ordem alfabética ("OPERAÇÃO:SITUAÇÃO,…"): o walk-in grava as
# duas na mesma transação, sem ordem de criação.
fnrh_states() {
    req GET "/fnrh/submissions?limit=100" recepcao
    jq -r --arg r "$1" '[.items[] | select(.reservationId == $r) | "\(.operation):\(.status)"] | sort | join(",")' \
        "$T/body" 2>/dev/null
}

# A submissão <operação> da reserva <id> pelo filtro jq <expr> (vazio se não há).
fnrh_item() {
    req GET "/fnrh/submissions?limit=100" recepcao
    jq -r --arg r "$1" --arg o "$2" \
        "[.items[] | select(.reservationId == \$r and .operation == \$o)][0] | if . == null then \"\" else $3 end" \
        "$T/body" 2>/dev/null
}

fnrh_pending_count() {
    req GET "/fnrh/submissions?limit=100" recepcao
    jq '[.items[] | select(.status == "PENDING")] | length' "$T/body" 2>/dev/null
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

# "solicitante|aprovador|ação|recurso" da aprovação <id> em iam.approval (o id conferido antes do SQL).
approval_row() {
    require_uuid approvalId "$1" || return 1
    sql <<SQL
SELECT requester_id || '|' || approver_id || '|' || action || '|' || resource_ref FROM iam.approval WHERE id = '$1';
SQL
}

# --- Usuários -----------------------------------------------------------------------------------------------------

LOGIN_AT=0
declare -A LOGIN_OF PASSWORD_OF

# login <quem> <login> <senha>: deixa o token de <quem> pronto se der 200.
login() {
    LOGIN_NAME=$2 LOGIN_PASSWORD=$3 jq -n \
        '{login: env.LOGIN_NAME, password: env.LOGIN_PASSWORD, device: {id: "gate-m5", name: "Roteiro do gate M5"}}' \
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
    for who in admin recepcao gerente; do
        login "$who" "${LOGIN_OF[$who]}" "${PASSWORD_OF[$who]}"
    done
    LOGIN_AT=$SECONDS
}

# --- Dados de teste -----------------------------------------------------------------------------------------------

REQUESTER_CPF=52998224725
EARLY_FEE=85.00
LATE_FEE=70.00
LATE_LIMIT=18:00
LAUNDRY=30.00
# Os preços do plano (bulk-update da seção 3): a diária de 2 adultos é a âncora das noites da reserva da seção 4.
ONE_ADULT=300.00
TWO_ADULTS=400.00
# Uma foto PNG 64×48 lisa (a comprovação do menor).
PROOF_PNG='iVBORw0KGgoAAAANSUhEUgAAAEAAAAAwCAIAAAAuKetIAAAAQ0lEQVR42u3PQQkAAAgEsItjfwxhLCv4FQYrsEzXaxEQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQuFoFK7EeBoF7bgAAAABJRU5ErkJggg=='

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

# "<ocupação> <governança> <hóspedes>" da UH (a vaga com 0 hóspedes).
room_status() {
    req GET "/rooms/$1/status" admin
    jb '"\(.occupancy) \(.housekeepingStatus) \(.guestCount // 0)"'
}

# "<ocupação> <hóspedes>" da UH ocupada.
room_occupancy() {
    req GET "/rooms/$1/status" admin
    jb '"\(.occupancy) \(.guestCount // 0)"'
}

reservation() {
    req GET "/reservations/$RES_ID" recepcao
}

# O PUT da reserva pela Recepcionista mudando só a saída para <data>, com o If-Match.
change_departure() {
    reservation
    local tag
    tag=$(hdr etag)
    jq --arg d "$1" '.reservation | {roomTypeId, ratePlanId, arrival, departure: $d, adults, childAges, segment,
        specialRequests, nightlyRates: []}' "$T/body" >"$T/req.json"
    req PUT "/reservations/$RES_ID" recepcao "$T/req.json" "If-Match: $tag"
}

# O pedido de check-in com o titular e o menor (com a autorização), e a caução, em $T/checkin.json.
check_in_body() {
    jq -n --arg h "$HOLDER_ID" --arg m "$MINOR_ID" --arg f "$PROOF_ID" --arg e "$(date -u -d '+5 days' +%Y-%m-%dT%H:%M:%SZ)" \
        '{guests: [
            {guestId: $h, primary: true, travelReason: "LAZER_FERIAS", transportMode: "AUTOMOVEL",
             minorAuthorization: null},
            {guestId: $m, primary: false, travelReason: "LAZER_FERIAS", transportMode: "AUTOMOVEL",
             minorAuthorization: {proofType: "WRITTEN_AUTHORIZATION", attachmentId: $f, responsibleGuestId: $h,
                                  relationship: "PAI"}}],
          preAuthorization: {amount: "500.00", expiresAt: $e, authorizationCode: "A1B2C3", cardBrand: "VISA",
                             cardLast4: "1234"}}' >"$T/checkin.json"
}

pay() {
    jq -n --arg a "$2" '{method: "CASH", amount: $a, deposit: false}' >"$T/req.json"
    req POST "/folios/$1/payments" recepcao "$T/req.json"
}

# new_guest <rótulo ASCII> [perfil extra em JSON]: a Recepcionista cadastra um hóspede adulto brasileiro com CPF
# sorteado e os dados que a FNRH exige. Nome, CPF e e-mail viram dado de hóspede (secret) e entram no jq pelo ambiente.
# Deixa o id em GUEST_ID e o status em ST.
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
    G_NAME=$name G_EMAIL=$email G_CPF=$cpf G_EXTRA=${2:-'{}'} jq -n \
        '{profile: ({fullName: env.G_NAME, socialName: null, nationality: "BR", birthDate: "1990-07-21",
            gender: "FEMALE", email: env.G_EMAIL, phone: "+55 11 98765-4321", residenceCountry: "BR",
            address: {street: "Rua das Acácias", number: "742", complement: "Apto 12", district: "Jardim Paulista",
                      city: "São Paulo", state: "SP", postalCode: "01407-000", ibgeCityCode: "3550308"},
            preferences: {floor: "Alto", bed: "Casal", pillow: "Baixo"}, vip: false, notes: "Gate do M5",
            companyId: null} + (env.G_EXTRA | fromjson)),
          documents: [{type: "CPF", issuingCountry: null, number: env.G_CPF}],
          sensitiveData: {raceColor: "BROWN"}}' >"$T/req.json"
    req POST /guests recepcao "$T/req.json"
    GUEST_ID=$(jb .id)
}

# O hóspede <id> como único (e principal) da estadia, sem comprovação de menor.
single_guest() {
    jq -nc --arg g "$1" \
        '{guestId: $g, primary: true, travelReason: "LAZER_FERIAS", transportMode: "AUTOMOVEL", minorAuthorization: null}'
}

# book_single <hóspede>: a Recepcionista reserva de BD a BD+2, 1 adulto, sem UH. Deixa o id em BOOKED_ID.
book_single() {
    jq -n --arg h "$1" --arg t "$TYPE_ID" --arg p "$PLAN_ID" --arg a "$BD" --arg d "$(date -d "$BD +2 days" +%F)" \
        '{holderGuestId: $h, origin: "PHONE", externalId: null,
          reservations: [{roomTypeId: $t, ratePlanId: $p, arrival: $a, departure: $d, adults: 1, childAges: [],
            segment: "LEISURE", specialRequests: null, guests: [{guestId: $h, primary: true}], nightlyRates: []}]}' \
        >"$T/req.json"
    req POST /bookings recepcao "$T/req.json"
    BOOKED_ID=$(jb '.reservations[0].id')
}

# O pedido de check-in só com o hóspede <id> (sem caução e sem early: sem valor cotado, nada de early).
single_check_in_body() {
    jq -n --argjson g "$(single_guest "$1")" '{guests: [$g]}' >"$T/req.json"
}

# new_rooms <seção> <número...>: o Gerente cria as UHs do tipo do roteiro, espera o inventário e as limpa e inspeciona.
# Deixa os ids em NEW_ROOMS.
new_rooms() {
    local tag=$1 number id total deadline
    shift
    NEW_ROOMS=()
    for number in "$@"; do
        jq -n --arg n "$number" --arg t "$TYPE_ID" \
            '{number: $n, floor: "12º andar", roomTypeId: $t, featureIds: [], notes: "Gate do M5"}' >"$T/req.json"
        req POST /rooms gerente "$T/req.json"
        check_http "[$tag] UH $number pelo Gerente" 201
        NEW_ROOMS+=("$(jb .id)")
    done
    deadline=$((SECONDS + 60))
    while :; do
        req GET "/room-type-inventory?roomTypeId=$TYPE_ID&from=$BD&to=$BD" gerente
        total=$(jb '.items[0].total')
        [ "$total" = $((2 + $#)) ] || [ "$SECONDS" -ge "$deadline" ] && break
        sleep 2
    done
    check "[$tag] inventário do tipo com as $((2 + $#)) UHs" $((2 + $#)) "$total"
    for id in "${NEW_ROOMS[@]}"; do
        housekeeping "$id" MARK_CLEAN
        check_http "[$tag] UH nova limpa pelo Gerente" 200
        housekeeping "$id" MARK_INSPECTED
        check_http "[$tag] UH nova inspecionada pelo Gerente" 200
        check "[$tag] UH nova pronta para check-in" true "$(jb .readyForCheckIn)"
    done
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

# ==================================================================================================================
# Seções. Cada uma é uma função; o estado que passa de uma a outra fica em variáveis globais (ids da reserva, das
# UHs, da conta, do agente…). Para acrescentar uma seção, escreva a função e a ponha em SECTIONS (no fim do arquivo).
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
    req GET /reservations -
    check_http "[1] GET /reservations sem token" 401
}

s2_users() {
    section "2. Administrador inicial e operadores dos modelos de role"
    local admin_initial admin_login
    admin_login=$(grep -m1 '^ZENY_ADMIN_LOGIN=' "$ENV_FILE" | cut -d= -f2-)
    admin_login=${admin_login:-admin}
    admin_initial=$(grep -m1 '^ZENY_ADMIN_PASSWORD=' "$ENV_FILE" | cut -d= -f2-)
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
    login admin "$admin_login" "$admin_initial"
    check_http "[2] login com a senha inicial depois da troca" 401
    login admin "$admin_login" "${PASSWORD_OF[admin]}"
    check_http "[2] login do admin com a senha nova" 200
    check "[2] admin sem troca pendente" false "$(jb .passwordChangeRequired)"

    req GET "/roles?limit=100" admin
    check_http "[2] GET /roles" 200
    local receptionist_role manager_role
    receptionist_role=$(jb '[.items[] | select(.template == "RECEPTIONIST")][0].id')
    manager_role=$(jb '[.items[] | select(.template == "MANAGER")][0].id')
    check "[2] modelo Recepcionista no boot" true "$(bool is_uuid "$receptionist_role")"
    check "[2] modelo Gerente no boot" true "$(bool is_uuid "$manager_role")"

    MANAGER_PIN=$(printf '%06d' $((16#$(openssl rand -hex 3) % 1000000)))
    secret "$MANAGER_PIN" PIN
    local who role provisional
    for who in recepcao gerente; do
        role=$receptionist_role
        [ "$who" = gerente ] && role=$manager_role
        provisional=$(openssl rand -hex 16)
        secret "$provisional" "senha de usuário"
        LOGIN_OF[$who]=$who
        PASSWORD_OF[$who]=$(openssl rand -hex 16)
        secret "${PASSWORD_OF[$who]}" "senha de usuário"
        if [ "$who" = gerente ]; then
            PROVISIONAL=$provisional PIN=$MANAGER_PIN jq -n --arg r "$role" \
                '{login: "gerente", name: "Gerente do gate", password: env.PROVISIONAL, pin: env.PIN, roleIds: [$r]}' \
                >"$T/req.json"
        else
            PROVISIONAL=$provisional jq -n --arg r "$role" \
                '{login: "recepcao", name: "Recepção do gate", password: env.PROVISIONAL, roleIds: [$r]}' \
                >"$T/req.json"
        fi
        req POST /users admin "$T/req.json"
        check_http "[2] usuário $who criado pelo admin" 201
        check "[2] $who com a role do modelo" "$role" "$(jb '.roles[0].id')"
        check "[2] $who com troca de senha obrigatória" true "$(jb .passwordChangeRequired)"
        if [ "$who" = gerente ]; then
            check "[2] gerente com PIN" true "$(jb .hasPin)"
            MANAGER_ID=$(jb .id)
        else
            RECEPTIONIST_ID=$(jb .id)
        fi
        login "$who" "$who" "$provisional"
        check_http "[2] login de $who com a senha provisória" 200
        check "[2] $who precisa trocar a senha" true "$(jb .passwordChangeRequired)"
        change_password "$who" "$provisional" "${PASSWORD_OF[$who]}"
        check_http "[2] troca da senha de $who" 204
        login "$who" "$who" "${PASSWORD_OF[$who]}"
        check_http "[2] login de $who com a senha nova" 200
        check "[2] $who sem troca pendente" false "$(jb .passwordChangeRequired)"
    done
    LOGIN_AT=$SECONDS
}

s3_setup() {
    section "3. Cadastro: Gerente (UHs, plano, preços) e administrador (política, FNRH, agente e impressora)"
    business_date gerente
    check_http "[3] data operacional" 200
    check "[3] data operacional é uma data" true "$(bool is_date "$BD")"
    local bd=$BD

    jq -n '{code: "GATE", name: "Standard do gate", description: "Quarto com varanda", standardAdults: 2,
        standardChildren: 0, maxAdults: 3, maxChildren: 1, bedSetup: "1 casal + 1 solteiro", featureIds: [],
        displayOrder: 10}' >"$T/req.json"
    req POST /room-types gerente "$T/req.json"
    check_http "[3] tipo de UH pelo Gerente" 201
    TYPE_ID=$(jb .id)
    local number rooms=()
    for number in 1201 1202; do
        jq -n --arg n "$number" --arg t "$TYPE_ID" \
            '{number: $n, floor: "12º andar", roomTypeId: $t, featureIds: [], notes: "Gate do M5"}' >"$T/req.json"
        req POST /rooms gerente "$T/req.json"
        check_http "[3] UH $number pelo Gerente" 201
        rooms+=("$(jb .id)")
    done
    ROOM1=${rooms[0]}
    ROOM2=${rooms[1]}

    # O inventário do tipo é refeito pelo listener do cadastro de UH.
    local deadline=$((SECONDS + 60)) total
    while :; do
        req GET "/room-type-inventory?roomTypeId=$TYPE_ID&from=$bd&to=$bd" gerente
        total=$(jb '.items[0].total')
        [ "$total" = 2 ] || [ "$SECONDS" -ge "$deadline" ] && break
        sleep 2
    done
    check "[3] inventário do tipo com as 2 UHs" 2 "$total"

    housekeeping "$ROOM2" MARK_CLEAN
    check_http "[3] UH 1202 limpa pelo Gerente" 200
    housekeeping "$ROOM2" MARK_INSPECTED
    check_http "[3] UH 1202 inspecionada pelo Gerente" 200
    check "[3] UH 1202 pronta para check-in" true "$(jb .readyForCheckIn)"
    check "[3] UH 1201 continua suja (sem inspeção)" "VACANT DIRTY 0" "$(room_status "$ROOM1")"

    jq -n '{name: "Flexível do gate", deadlineHours: 48, penaltyType: "NIGHTS", penaltyPercent: null,
        penaltyNights: 1, penaltyAmount: null}' >"$T/req.json"
    req POST /cancellation-policies gerente "$T/req.json"
    check_http "[3] política de cancelamento" 201
    local cancellation guarantee
    cancellation=$(jb .id)
    jq -n '{name: "Sem garantia do gate", type: "NONE", depositPercent: null, dueDaysBefore: null}' >"$T/req.json"
    req POST /guarantee-policies gerente "$T/req.json"
    check_http "[3] política de garantia" 201
    guarantee=$(jb .id)
    jq -n --arg t "$TYPE_ID" --arg c "$cancellation" --arg g "$guarantee" \
        '{code: "GATEBAL", name: "Balcão do gate", description: "Tarifa de balcão com café", mealPlan: "BREAKFAST",
          category: "NORMAL", channels: ["DIRECT", "WEBSITE"], roomTypeIds: [$t], packageIds: [],
          cancellationPolicyId: $c, guaranteePolicyId: $g}' >"$T/req.json"
    req POST /rate-plans gerente "$T/req.json"
    check_http "[3] plano tarifário" 201
    PLAN_ID=$(jb .id)
    jq -n --arg t "$TYPE_ID" --arg f "$bd" --arg u "$(date -d "$bd +30 days" +%F)" --arg one "$ONE_ADULT" \
        --arg two "$TWO_ADULTS" \
        '{roomTypeIds: [$t], from: $f, to: $u, seasonId: null, weekdays: [],
          prices: {oneAdult: $one, twoAdults: $two, extraAdult: "120.00", children: []}}' >"$T/req.json"
    req POST "/rate-plans/$PLAN_ID/prices/bulk-update" gerente "$T/req.json"
    check_http "[3] preços de 31 dias" 200

    # Política de early/late (hotel.settings.manage, só do administrador): os dois oferecidos, com valor.
    req GET /hotel/policies admin
    check_http "[3] política do hotel" 200
    local tag cit early
    tag=$(hdr etag)
    cit=$(jb .checkInTime)
    early=$(printf '%02d:%s' $((10#${cit%%:*} - 3)) "${cit#*:}")
    jq --arg el "$early" --arg ef "$EARLY_FEE" --arg ll "$LATE_LIMIT" --arg lf "$LATE_FEE" \
        '. + {earlyCheckIn: {limitTime: $el, fee: $ef}, lateCheckOut: {limitTime: $ll, fee: $lf}}' \
        "$T/body" >"$T/policy.json"
    req PUT /hotel/policies gerente "$T/policy.json" "If-Match: $tag"
    check_http "[3] o Gerente não altera a política do hotel" 403
    req PUT /hotel/policies admin "$T/policy.json" "If-Match: $tag"
    check_http "[3] política de early/late pelo admin" 200
    check "[3] early oferecido com valor" "$EARLY_FEE" "$(jb .earlyCheckIn.fee)"
    check "[3] late oferecido com valor" "$LATE_FEE" "$(jb .lateCheckOut.fee)"

    # FNRH em homologação (o mock), com a credencial de teste sorteada aqui e o CPF solicitante de teste.
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
    check "[3] FNRH em homologação" HOMOLOGATION "$(jb .environment)"
    check "[3] FNRH ligada" true "$(jb .enabled)"
    check "[3] senha da FNRH guardada" true "$(jb .passwordSet)"
    check "[3] usuário da FNRH guardado" true "$(bool [ "$(jb .username)" = "$FNRH_USER" ])"
    local response
    response=$(<"$T/body")
    check "[3] resposta sem a senha da FNRH" false "$(bool [ "${response//"$FNRH_PASSWORD"/}" != "$response" ])"
    check "[3] CPF solicitante padrão mascarado" "***.***.***-25" "$(jb .defaultRequesterCpf)"
    jq -n --arg cpf "$REQUESTER_CPF" '{cpf: $cpf}' >"$T/req.json"
    req PUT "/fnrh/requesters/$RECEPTIONIST_ID" admin "$T/req.json"
    check_http "[3] CPF solicitante de teste da Recepcionista" 200

    # Agente de impressão (o token só em variável) e a impressora A4_PDF dele.
    jq -n '{name: "Agente do gate"}' >"$T/req.json"
    req POST /print-agents admin "$T/req.json"
    check_http "[3] agente de impressão pelo admin" 201
    AGENT_ID=$(jb .id)
    local agent_token
    agent_token=$(jb .token)
    set_token agent "$agent_token"
    : >"$T/body"
    check "[3] token do agente com o prefixo zpa_" true "$(bool [ "${agent_token:0:4}" = zpa_ ])"
    jq -n --arg a "$AGENT_ID" '{name: "A4 da recepção", kind: "A4_PDF", address: "a4-recepcao", agentId: $a}' \
        >"$T/req.json"
    req POST /printers admin "$T/req.json"
    check_http "[3] impressora A4_PDF pelo admin" 201
    PRINTER_ID=$(jb .id)
    check "[3] impressora A4_PDF" A4_PDF "$(jb .kind)"

    # O token não fica em nenhuma coluna do schema printing; só o SHA-256 dele, no agente.
    local token_hash found
    token_hash=$(printf '%s' "$agent_token" | openssl dgst -sha256 -r | cut -d' ' -f1)
    found=$(agent_token_in_printing "$agent_token")
    check "[3] psql: tabelas do schema printing examinadas" 3 "${found%% *}"
    check "[3] psql: token zpa_ em nenhuma coluna do schema printing" 0 "${found#* }"
    check "[3] psql: o agente guarda só o SHA-256 do token" 1 "$(agent_token_hash_count "$token_hash")"
}

# "<tabelas examinadas> <linhas com o token>" no schema printing. O token (base64url, conferido antes) vai no SQL pela
# entrada padrão do psql, nunca num argumento.
agent_token_in_printing() {
    [[ $1 =~ ^zpa_[A-Za-z0-9_-]+$ ]] || { echo "token do agente fora do formato para o SQL" >&2; return 1; }
    sql <<SQL
WITH t AS (
    SELECT c.relname,
           (xpath('/row/n/text()', query_to_xml(format('SELECT count(*) AS n FROM printing.%I r WHERE r::text LIKE %L',
                   c.relname, '%' || \$tok\$$1\$tok\$ || '%'), false, true, '')))[1]::text::int AS n
    FROM pg_class c JOIN pg_namespace s ON s.oid = c.relnamespace
    WHERE s.nspname = 'printing' AND c.relkind IN ('r', 'p')
)
SELECT count(*) || ' ' || coalesce(sum(n), 0) FROM t;
SQL
}

# Quantos agentes AGENT_ID guardam o SHA-256 <hash> (o id e o hash conferidos antes de entrar no SQL).
agent_token_hash_count() {
    require_uuid AGENT_ID "$AGENT_ID" || return 1
    [[ $1 =~ ^[0-9a-f]{64}$ ]] || { echo "SHA-256 fora do formato para o SQL" >&2; return 1; }
    sql <<SQL
SELECT count(*) FROM printing.print_agent WHERE id = '$AGENT_ID' AND token_hash = '$1';
SQL
}

s4_check_in() {
    section "4. Reserva com menor, recusa da UH suja, alocação, cotação e check-in"
    business_date recepcao
    check_http "[4] data operacional" 200
    check "[4] data operacional é uma data" true "$(bool is_date "$BD")"
    local bd=$BD suffix
    suffix=$(openssl rand -hex 3)
    HOLDER_CPF=$(new_cpf)
    MINOR_CPF=$(new_cpf)
    HOLDER_NAME="Titular Gate $suffix"
    MINOR_NAME="Menor Gate $suffix"
    local who cpf name birth email ids=()
    for who in holder minor; do
        if [ "$who" = holder ]; then
            cpf=$HOLDER_CPF name=$HOLDER_NAME birth=1988-03-14
        else
            cpf=$MINOR_CPF name=$MINOR_NAME birth=$(date -d "$bd -10 years" +%F)
        fi
        email="$who.$suffix@example.com"
        secret "$cpf" hóspede
        secret "${cpf:0:3}.${cpf:3:3}.${cpf:6:3}-${cpf:9:2}" hóspede
        secret "$name" hóspede
        secret "$email" hóspede
        jq -n --arg name "$name" --arg birth "$birth" --arg email "$email" --arg cpf "$cpf" \
            '{profile: {fullName: $name, socialName: null, nationality: "BR", birthDate: $birth, gender: "FEMALE",
                email: $email, phone: "+55 11 98765-4321", residenceCountry: "BR",
                address: {street: "Rua das Acácias", number: "742", complement: "Apto 12", district: "Jardim Paulista",
                          city: "São Paulo", state: "SP", postalCode: "01407-000", ibgeCityCode: "3550308"},
                preferences: {floor: "Alto", bed: "Casal", pillow: "Baixo"}, vip: false,
                notes: "Prefere quarto silencioso", companyId: null},
              documents: [{type: "CPF", issuingCountry: null, number: $cpf}],
              sensitiveData: {raceColor: "BROWN"}}' >"$T/req.json"
        req POST /guests recepcao "$T/req.json"
        check_http "[4] hóspede ($who) pela Recepcionista" 201
        ids+=("$(jb .id)")
    done
    HOLDER_ID=${ids[0]}
    MINOR_ID=${ids[1]}

    ARRIVAL=$bd
    DEPARTURE=$(date -d "$bd +2 days" +%F)
    jq -n --arg h "$HOLDER_ID" --arg m "$MINOR_ID" --arg t "$TYPE_ID" --arg p "$PLAN_ID" --arg a "$ARRIVAL" \
        --arg d "$DEPARTURE" \
        '{holderGuestId: $h, origin: "PHONE", externalId: null,
          reservations: [{roomTypeId: $t, ratePlanId: $p, arrival: $a, departure: $d, adults: 2, childAges: [],
            segment: "LEISURE", specialRequests: null,
            guests: [{guestId: $h, primary: true}, {guestId: $m, primary: false}], nightlyRates: []}]}' \
        >"$T/req.json"
    req POST /bookings recepcao "$T/req.json"
    check_http "[4] reserva do titular com o menor pela Recepcionista" 201
    RES_ID=$(jb '.reservations[0].id')
    FOLIO_ID=$(jb '.reservations[0].folioId')
    RES_CODE=$(jb '.reservations[0].code')
    check "[4] reserva confirmada" CONFIRMED "$(jb '.reservations[0].status')"

    # A comprovação do menor: o upload pelo files e o vínculo ao perfil dele.
    printf '%s' "$PROOF_PNG" | openssl base64 -d -A >"$T/proof.png"
    ST=$(curl -s --max-time 60 -o "$T/body" -D "$T/headers" -w '%{http_code}' -H "@$T/auth-recepcao" \
        -H "Idempotency-Key: $(uuid)" -F "file=@$T/proof.png;type=image/png;filename=autorizacao.png" \
        -F "purpose=guests.minor-authorization-photo" "$API/files") || true
    check_http "[4] upload da autorização do menor" 201
    PROOF_ID=$(jb .id)
    jq -n --arg f "$PROOF_ID" '{fileId: $f}' >"$T/req.json"
    req POST "/guests/$MINOR_ID/attachments" recepcao "$T/req.json"
    check_http "[4] autorização vinculada ao perfil do menor" 201

    # O pedido completo (sem o early, cotado depois), na UH 1201 ainda suja: 409 ROOM_NOT_READY e nada gravado.
    check_in_body
    jq --arg r "$ROOM1" '. + {roomId: $r}' "$T/checkin.json" >"$T/req.json"
    req POST "/reservations/$RES_ID/check-in" recepcao "$T/req.json"
    check_http "[4] check-in na UH suja" 409
    check "[4] code ROOM_NOT_READY" ROOM_NOT_READY "$(problem_code)"
    check "[4] recusa com a UH e a governança" "$ROOM1 DIRTY" "$(jb '"\(.roomId) \(.housekeepingStatus)"')"
    reservation
    check "[4] reserva continua confirmada e sem UH" "CONFIRMED null" "$(jb '"\(.reservation.status) \(.reservation.roomAssignment.roomId)"')"
    req GET "/folios/$FOLIO_ID/card-pre-authorizations" admin
    check_http "[4] cauções da conta" 200
    check "[4] nenhuma caução gravada" 0 "$(jb '.items | length')"
    req GET "/folios/$FOLIO_ID/postings" admin
    check_http "[4] lançamentos da conta" 200
    check "[4] nenhum lançamento gravado" 0 "$(jb '.items | length')"
    check "[4] UH 1201 vaga e suja" "VACANT DIRTY 0" "$(room_status "$ROOM1")"

    housekeeping "$ROOM1" MARK_CLEAN
    check_http "[4] UH 1201 limpa pelo Gerente" 200
    housekeeping "$ROOM1" MARK_INSPECTED
    check_http "[4] UH 1201 inspecionada pelo Gerente" 200

    jq -n --arg r "$ROOM1" '{roomId: $r}' >"$T/req.json"
    req POST "/reservations/$RES_ID/assign-room" recepcao "$T/req.json"
    check_http "[4] alocação da UH 1201 pela Recepcionista" 200
    check "[4] reserva alocada na 1201" "$ROOM1" "$(jb .reservation.roomAssignment.roomId)"

    req GET "/reservations/$RES_ID/early-late-quote" recepcao
    check_http "[4] cotação de early/late" 200
    check "[4] early oferecido" true "$(jb .earlyCheckIn.offered)"
    local fee
    fee=$(jb .earlyCheckIn.fee)
    check "[4] valor do early cotado" "$EARLY_FEE" "$fee"

    jq --arg fee "$fee" '. + {earlyCheckInFee: $fee}' "$T/checkin.json" >"$T/req.json"
    req POST "/reservations/$RES_ID/check-in" recepcao "$T/req.json"
    check_http "[4] check-in com o menor autorizado, caução e early" 200
    check "[4] reserva em casa na 1201" "IN_HOUSE $ROOM1" "$(jb '"\(.reservation.status) \(.reservation.roomId)"')"
    check "[4] check-in feito pela Recepcionista" "$RECEPTIONIST_ID" "$(jb .reservation.checkIn.by)"
    check "[4] UH ocupada com 2 hóspedes" "OCCUPIED 2" "$(jb '"\(.room.occupancy) \(.room.guestCount)"')"
    check "[4] caução ativa" ACTIVE "$(jb .preAuthorization.status)"
    check "[4] early lançado" "$EARLY_FEE" "$(jb .earlyCheckIn.amount)"
    check "[4] autorização do menor na estadia" "$PROOF_ID" \
        "$(jq -r --arg m "$MINOR_ID" '.reservation.guests[] | select(.guestId == $m) | .minorAuthorization.attachmentId' "$T/body")"
    check "[4] UH 1201 ocupada com 2 hóspedes (status)" "OCCUPIED 2" "$(room_occupancy "$ROOM1")"
}

s5_fnrh() {
    section "5. FNRH: envio ao mock, outbox do listener e status"
    ensure_tokens
    local got
    got=$(await_fnrh_submissions "REGISTER_RESERVATION:SENT,CHECK_IN:SENT")
    check "[5] submissões da reserva enviadas (≤ 3 min)" "REGISTER_RESERVATION:SENT,CHECK_IN:SENT" "$got"
    check "[5] outbox: ReservationCreated no listener do fnrh" COMPLETED "$(await_fnrh_publication ReservationCreated)"
    check "[5] outbox: GuestCheckedIn no listener do fnrh" COMPLETED "$(await_fnrh_publication GuestCheckedIn)"
    req GET /fnrh/status recepcao
    check_http "[5] GET /fnrh/status" 200
    check "[5] FNRH configurada, ligada, em homologação" "true true HOMOLOGATION" \
        "$(jb '"\(.configured) \(.enabled) \(.environment)"')"
    check "[5] lastSuccessAt preenchido" true "$(jb '.lastSuccessAt != null')"
    check "[5] fnrh-mock: nenhum pedido não casado" 0 "$(mock_unmatched)"
    check "[5] fnrh-mock: reserva registrada" true \
        "$(bool [ "$(mock_count POST '^/FNRH_API/rest/v2/reservas$')" -ge 1 ])"
    check "[5] fnrh-mock: titular e menor na reserva" true \
        "$(bool [ "$(mock_count POST '^/FNRH_API/rest/v2/reservas/[^/]+/hospedes$')" -ge 2 ])"
    check "[5] fnrh-mock: todos os pedidos com Basic" true "$(mock_all_basic)"
}

s6_registration_card() {
    section "6. Ficha de registro pelo agente de impressão (SSE)"
    ensure_tokens
    jq -n --arg p "$PRINTER_ID" '{printerId: $p}' >"$T/req.json"
    req POST "/reservations/$RES_ID/registration-card/print" recepcao "$T/req.json"
    check_http "[6] ficha enviada à impressora" 202
    local job event
    job=$(jb .printJobId)

    ST=$(curl -sN --max-time 6 -o "$T/sse" -w '%{http_code}' -H "@$T/auth-agent" -H 'Accept: text/event-stream' \
        "$API/print-agents/me/jobs/stream") || true
    check "[6] stream SSE do agente → 200" 200 "$ST"
    event=$(tr -d '\r' <"$T/sse" | awk '/^event: ?job$/ { f = 1; next } /^data:/ { if (f) { sub(/^data: ?/, ""); print; exit } }
        /^$/ { f = 0 }')
    check "[6] evento job com o id do job" "$job" "$(jq -r .id <<<"$event" 2>/dev/null)"
    check "[6] evento na impressora A4" "$PRINTER_ID application/pdf" \
        "$(jq -r '"\(.printerId) \(.mediaType)"' <<<"$event" 2>/dev/null)"

    req GET "/print-agents/me/jobs/$job/content" agent
    check_http "[6] conteúdo baixado pelo agente" 200
    check "[6] conteúdo em PDF" true "$(bool starts_with_pdf)"
    jq -n '{result: "PRINTED"}' >"$T/req.json"
    NO_IDEM=1 req POST "/print-agents/me/jobs/$job/ack" agent "$T/req.json"
    check_http "[6] ack PRINTED do agente" 204
    req GET "/print-jobs?printerId=$PRINTER_ID" recepcao
    check_http "[6] lista de jobs da impressora" 200
    check "[6] job PRINTED" "$job:PRINTED" "$(jb '[.items[] | "\(.id):\(.status)"] | join(",")')"

    req GET /print-agents/me/identity agent
    check_http "[6] identidade do agente com o token dele" 200
    check "[6] identidade é o agente cadastrado" "$AGENT_ID" "$(jb .id)"
    req GET /print-agents/me/identity recepcao
    check_http "[6] JWT de usuário em /print-agents/me/identity" 401
    req GET /reservations agent
    check_http "[6] token do agente em /reservations" 401
}

s7_stay() {
    section "7. Troca de UH, extensão e redução, lançamento, caixa, pagamento e documentos"
    ensure_tokens
    reservation
    local tag nights
    tag=$(hdr etag)
    nights=$(jbc '[.reservation.nightlyRates[] | {date, amount}]')
    # Âncora das comparações de noites abaixo: as duas noites da reserva, cada uma com a diária de 2 adultos do plano.
    check "[7] duas noites a $TWO_ADULTS antes da troca" "$TWO_ADULTS,$TWO_ADULTS" \
        "$(jb '[.reservation.nightlyRates[].amount] | join(",")')"
    jq -n --arg r "$ROOM2" '{roomId: $r, reason: "GUEST_REQUEST", tariff: "KEEP"}' >"$T/req.json"
    req POST "/reservations/$RES_ID/room-moves" recepcao "$T/req.json" "If-Match: $tag"
    check_http "[7] troca para a UH 1202 com If-Match" 200
    check "[7] reserva em casa na 1202" "IN_HOUSE $ROOM2" "$(jb '"\(.reservation.status) \(.reservation.roomId)"')"
    check "[7] tarifa mantida, sem diferença" "KEEP 0.00" "$(jb '"\(.tariff) \(.difference)"')"
    check "[7] UH de origem vaga e suja" "VACANT DIRTY" "$(jb '"\(.fromRoom.occupancy) \(.fromRoom.housekeepingStatus)"')"
    check "[7] UH 1202 ocupada com 2 hóspedes" "OCCUPIED 2" "$(room_occupancy "$ROOM2")"
    check "[7] UH 1201 vaga e suja" "VACANT DIRTY 0" "$(room_status "$ROOM1")"
    reservation
    check "[7] noites inalteradas pela troca" "$nights" "$(jbc '[.reservation.nightlyRates[] | {date, amount}]')"

    local extended
    extended=$(date -d "$DEPARTURE +1 day" +%F)
    change_departure "$extended"
    check_http "[7] extensão da saída pelo PUT com If-Match" 200
    check "[7] saída estendida, UH 1202 acompanha" "$extended $ROOM2 $extended" \
        "$(jb '"\(.reservation.departure) \(.reservation.roomAssignment.roomId) \(.reservation.roomAssignment.endsOn)"')"
    check "[7] três noites" 3 "$(jb '.reservation.nightlyRates | length')"
    change_departure "$DEPARTURE"
    check_http "[7] redução da saída pelo PUT com If-Match" 200
    check "[7] saída de volta, UH 1202 acompanha" "$DEPARTURE $ROOM2 $DEPARTURE" \
        "$(jb '"\(.reservation.departure) \(.reservation.roomAssignment.roomId) \(.reservation.roomAssignment.endsOn)"')"
    check "[7] noites de volta às originais" "$nights" "$(jbc '[.reservation.nightlyRates[] | {date, amount}]')"
    check "[7] UH 1202 segue ocupada" "OCCUPIED 2" "$(room_occupancy "$ROOM2")"

    jq -n '{name: "Caixa da recepção", blindClose: false}' >"$T/req.json"
    req POST /cash-points gerente "$T/req.json"
    check_http "[7] ponto de caixa pelo Gerente" 201
    jq -n --arg c "$(jb .id)" '{cashPointId: $c, openingFloat: "0.00"}' >"$T/req.json"
    req POST /cashier-shifts recepcao "$T/req.json"
    check_http "[7] turno de caixa aberto pela Recepcionista" 201

    req GET "/transaction-codes?limit=100" admin
    check_http "[7] códigos de lançamento" 200
    jq -n --arg c "$(jb '[.items[] | select(.key == "LAUNDRY")][0].id')" --arg v "$LAUNDRY" \
        '{transactionCodeId: $c, quantity: 1, unitPrice: $v}' >"$T/req.json"
    req POST "/folios/$FOLIO_ID/postings" recepcao "$T/req.json"
    check_http "[7] lançamento de lavanderia pela Recepcionista" 201
    check "[7] valor lançado" "$LAUNDRY" "$(jb .amount)"
    req GET "/folios/$FOLIO_ID" recepcao
    local due
    due=$(jb .balance)
    check "[7] saldo: early + lavanderia" "$(money $(($(cents "$EARLY_FEE") + $(cents "$LAUNDRY"))))" "$due"
    pay "$FOLIO_ID" "$due"
    check_http "[7] pagamento do saldo em dinheiro" 201
    req GET "/folios/$FOLIO_ID" recepcao
    check "[7] saldo zerado" 0.00 "$(jb .balance)"

    req GET "/folios/$FOLIO_ID/statement/pdf" recepcao
    check_http "[7] extrato em PDF" 200
    check "[7] extrato application/pdf" application/pdf "$(hdr content-type | cut -d';' -f1)"
    check "[7] extrato é um PDF" true "$(bool starts_with_pdf)"
    req GET "/reservations/$RES_ID/confirmation/pdf" recepcao
    check_http "[7] confirmação em PDF" 200
    check "[7] confirmação é um PDF" true "$(bool starts_with_pdf)"
}

s8_check_out() {
    section "8. Check-out com late, contas encerradas, caução liberada e FNRH"
    ensure_tokens
    req GET "/folios/$FOLIO_ID/card-pre-authorizations" admin
    local hold late arrival_night total
    hold=$(jb '.items[0].id')
    check "[8] caução com id" true "$(bool is_uuid "$hold")"
    check "[8] caução ativa antes do check-out" "1 ACTIVE" "$(jb '"\(.items | length) \(.items[0].status)"')"
    req GET "/reservations/$RES_ID/early-late-quote" recepcao
    check_http "[8] cotação do late" 200
    check "[8] late oferecido" true "$(jb .lateCheckOut.offered)"
    late=$(jb .lateCheckOut.fee)
    check "[8] valor do late cotado" "$LATE_FEE" "$late"
    reservation
    arrival_night=$(jb '.reservation.nightlyRates[0].amount')
    check "[8] noite da chegada com a diária do plano" "$TWO_ADULTS" "$arrival_night"

    jq -n --arg l "$late" '{lateCheckOutFee: $l}' >"$T/checkout.json"
    req POST "/reservations/$RES_ID/check-out" recepcao "$T/checkout.json"
    check_http "[8] check-out com saldo" 409
    check "[8] code FOLIO_BALANCE_NOT_ZERO" FOLIO_BALANCE_NOT_ZERO "$(problem_code)"
    check "[8] conta da reserva na recusa" "$FOLIO_ID" "$(jb '[.folios[].folioId] | join(",")')"
    total=$(jb .total)
    check "[8] total da recusa é um valor" true "$(bool is_money "$total")"
    check "[8] total: noite da chegada + late" "$(money $(($(cents "$arrival_night") + $(cents "$late"))))" "$total"
    reservation
    check "[8] reserva segue em casa" IN_HOUSE "$(jb .reservation.status)"

    pay "$FOLIO_ID" "$total"
    check_http "[8] pagamento do saldo antes do check-out" 201
    req POST "/reservations/$RES_ID/check-out" recepcao "$T/checkout.json"
    check_http "[8] check-out com o late cotado" 200
    check "[8] reserva encerrada" CHECKED_OUT "$(jb .reservation.status)"
    check "[8] late lançado" "$LATE_FEE" "$(jb .lateCheckOut.amount)"
    check "[8] UH 1202 vaga e suja" "$ROOM2 VACANT DIRTY" \
        "$(jb '"\(.room.roomId) \(.room.occupancy) \(.room.housekeepingStatus)"')"
    check "[8] caução liberada" "$hold:RELEASED" "$(jb '[.releasedPreAuthorizations[] | "\(.id):\(.status)"] | join(",")')"
    check "[8] conta encerrada" "$FOLIO_ID:CLOSED" "$(jb '[.closedFolios[] | "\(.folioId):\(.status)"] | join(",")')"
    req GET "/folios/$FOLIO_ID" recepcao
    check "[8] conta fechada com saldo zero" "CLOSED 0.00" "$(jb '"\(.status) \(.balance)"')"
    req GET "/folios/$FOLIO_ID/card-pre-authorizations" admin
    check "[8] caução RELEASED na conta" RELEASED "$(jb '.items[0].status')"
    check "[8] UH 1202 vaga e suja (status)" "VACANT DIRTY 0" "$(room_status "$ROOM2")"

    local expected="REGISTER_RESERVATION:SENT,CHECK_IN:SENT,UPDATE_RESERVATION:SENT,UPDATE_RESERVATION:SENT,CHECK_OUT:SENT"
    check "[8] submissões até o check-out enviadas (≤ 3 min)" "$expected" "$(await_fnrh_submissions "$expected")"
    check "[8] outbox: GuestCheckedOut no listener do fnrh" COMPLETED "$(await_fnrh_publication GuestCheckedOut)"
    check "[8] fnrh-mock: check-out de cada hóspede" true \
        "$(bool [ "$(mock_count PATCH '^/FNRH_API/rest/v2/hospedes/[^/]+/checkout$')" -ge 2 ])"
    check "[8] fnrh-mock: nenhum pedido não casado" 0 "$(mock_unmatched)"
}

s9_walk_in_and_undo() {
    section "9. Walk-in (gênero OUTRO com descrição) e desfazer check-in com a aprovação do Gerente"
    ensure_tokens
    business_date recepcao
    check_http "[9] data operacional" 200
    check "[9] data operacional é uma data" true "$(bool is_date "$BD")"
    # 1203 (walk-in), 1204 (desfazer) e 1205 (seção 11), prontas para o check-in.
    new_rooms 9 1203 1204 1205
    ROOM3=${NEW_ROOMS[0]}
    ROOM4=${NEW_ROOMS[1]}
    ROOM5=${NEW_ROOMS[2]}

    # --- Walk-in pela Recepcionista: hóspede de gênero OUTRO com a descrição (E15) -------------------------------
    G_DESC="Pessoa não binária $(openssl rand -hex 3)"
    secret "$G_DESC" hóspede
    new_guest Avulso "$(G_DESC=$G_DESC jq -nc '{gender: "OTHER", genderDescription: env.G_DESC}')"
    check_http "[9] hóspede do walk-in (OUTRO com descrição) pela Recepcionista" 201
    WALK_GUEST=$GUEST_ID
    req GET "/guests/$WALK_GUEST" recepcao
    check_http "[9] hóspede do walk-in lido" 200
    check "[9] gênero OUTRO gravado" OTHER "$(jb .gender)"
    check "[9] descrição de gênero gravada" true "$(bool [ "$(jb .genderDescription)" = "$G_DESC" ])"

    jq -n --arg r "$ROOM3" --arg p "$PLAN_ID" --arg d "$(date -d "$BD +2 days" +%F)" \
        --argjson g "$(single_guest "$WALK_GUEST")" \
        '{roomId: $r, ratePlanId: $p, departure: $d, adults: 1, childAges: [], segment: "LEISURE", guests: [$g]}' \
        >"$T/req.json"
    req POST /walk-ins recepcao "$T/req.json"
    check_http "[9] walk-in na UH 1203 pela Recepcionista" 201
    WALK_ID=$(jb .reservation.id)
    check "[9] walk-in em casa na 1203" "IN_HOUSE $ROOM3" "$(jb '"\(.reservation.status) \(.reservation.roomId)"')"
    check "[9] titular do walk-in é o hóspede" "$WALK_GUEST" "$(jb .holderGuestId)"
    check "[9] UH 1203 ocupada" OCCUPIED "$(jb .room.occupancy)"
    check "[9] UH 1203 ocupada com 1 hóspede (status)" "OCCUPIED 1" "$(room_occupancy "$ROOM3")"
    check "[9] walk-in com id" true "$(bool is_uuid "$WALK_ID")"
    poll "CHECK_IN:SENT,REGISTER_RESERVATION:SENT" 180 5 fnrh_states "$WALK_ID"
    check "[9] submissões do walk-in enviadas (≤ 3 min)" "CHECK_IN:SENT,REGISTER_RESERVATION:SENT" "$GOT"
    check "[9] fnrh-mock: pessoa com gênero OUTRO e a descrição" true "$(mock_person_other)"
    check "[9] fnrh-mock: nenhum pedido não casado" 0 "$(mock_unmatched)"

    # --- Desfazer o check-in de outra reserva: a Recepcionista precisa da aprovação do Gerente (PIN) ------------
    new_guest Desfazer
    check_http "[9] hóspede da reserva a desfazer" 201
    local guest=$GUEST_ID
    book_single "$guest"
    check_http "[9] reserva a desfazer pela Recepcionista" 201
    UNDO_ID=$BOOKED_ID
    check "[9] reserva a desfazer com id" true "$(bool is_uuid "$UNDO_ID")"
    jq -n --arg r "$ROOM4" '{roomId: $r}' >"$T/req.json"
    req POST "/reservations/$UNDO_ID/assign-room" recepcao "$T/req.json"
    check_http "[9] alocação da UH 1204" 200
    single_check_in_body "$guest"
    req POST "/reservations/$UNDO_ID/check-in" recepcao "$T/req.json"
    check_http "[9] check-in na 1204 pela Recepcionista" 200
    check "[9] reserva em casa na 1204" "IN_HOUSE $ROOM4" "$(jb '"\(.reservation.status) \(.reservation.roomId)"')"

    NO_IDEM=1 req POST "/reservations/$UNDO_ID/undo-check-in" recepcao
    check_http "[9] desfazer pela Recepcionista sem aprovação" 403
    check "[9] code APPROVAL_REQUIRED" APPROVAL_REQUIRED "$(problem_code)"
    check "[9] ação, permissão e recurso da aprovação" \
        "frontdesk.stay.undo-check-in frontdesk.stays.undocheckin reservations.reservation:$UNDO_ID" \
        "$(jb '"\(.action) \(.permission) \(.resourceRef)"')"
    PIN=$MANAGER_PIN jq '{action, permission, resourceRef, approverLogin: "gerente", approverPin: env.PIN,
        reason: "Check-in feito na reserva errada"}' "$T/body" >"$T/approval.json"
    reservation_status "$UNDO_ID"
    check "[9] reserva segue em casa sem a aprovação" IN_HOUSE "$GOT"

    # A aprovação na sessão da Recepcionista (o token vale só nela, uma vez, 2 min): nada de ensure_tokens até o uso.
    NO_IDEM=1 req POST /auth/approvals recepcao "$T/approval.json"
    rm -f -- "$T/approval.json"
    check_http "[9] aprovação do Gerente com login e PIN" 201
    local approval_id approval_token
    approval_id=$(jb .approvalId)
    approval_token=$(jb .approvalToken)
    secret "$approval_token" "token de aprovação"
    check "[9] aprovador é o Gerente" "$MANAGER_ID" "$(jb .approverId)"
    check "[9] aprovação com id" true "$(bool is_uuid "$approval_id")"
    printf 'X-Approval-Token: %s\n' "$approval_token" >"$T/approval.hdr"
    : >"$T/body"
    NO_IDEM=1 req POST "/reservations/$UNDO_ID/undo-check-in" recepcao "" "@$T/approval.hdr"
    rm -f -- "$T/approval.hdr"
    check_http "[9] desfazer com a aprovação" 200
    check "[9] reserva confirmada, ainda na 1204" "CONFIRMED $ROOM4" "$(jb '"\(.reservation.status) \(.reservation.roomId)"')"
    check "[9] UH 1204 vaga e inspecionada (sem sujar)" "VACANT INSPECTED" \
        "$(jb '"\(.room.occupancy) \(.room.housekeepingStatus)"')"
    check "[9] UH 1204 vaga e inspecionada (status)" "VACANT INSPECTED 0" "$(room_status "$ROOM4")"

    req GET "/audit-entries?entityType=frontdesk.stay&entityId=$UNDO_ID&limit=50" admin
    check_http "[9] trilha da estadia" 200
    check "[9] trilha do desfazer: a Recepcionista e a aprovação" "$RECEPTIONIST_ID $approval_id" \
        "$(jb '[.items[] | select(.action == "frontdesk.stay.undo-check-in")]
            | if length == 1 then "\(.[0].userId) \(.[0].approvalId)" else "\(length) entradas" end')"
    check "[9] psql: iam.approval com a Recepcionista e o Gerente" \
        "$RECEPTIONIST_ID|$MANAGER_ID|frontdesk.stay.undo-check-in|reservations.reservation:$UNDO_ID" \
        "$(approval_row "$approval_id")"
}

# A situação da reserva <id>, em GOT (fora de subshell).
reservation_status() {
    req GET "/reservations/$1" recepcao
    GOT=$(jb .reservation.status)
}

s10_traces() {
    section "10. Recados para um setor, vinculados à reserva"
    ensure_tokens
    business_date recepcao
    check_http "[10] data operacional" 200
    local bd=$BD dept trace
    req GET "/departments?limit=100" gerente
    check_http "[10] setores pelo Gerente" 200
    dept=$(jb '[.items[] | select(.key == "HOUSEKEEPING")][0].id')
    check "[10] setor da governança pré-carregado" true "$(bool is_uuid "$dept")"

    TRACE_TEXT="Berço na UH $(openssl rand -hex 3)"
    secret "$TRACE_TEXT" "texto do recado"
    TEXT=$TRACE_TEXT jq -n --arg d "$dept" --arg due "$bd" --arg r "$UNDO_ID" \
        '{departmentId: $d, dueOn: $due, text: env.TEXT, reservationId: $r, roomId: null, assigneeUserId: null}' \
        >"$T/req.json"
    req POST /traces recepcao "$T/req.json"
    check_http "[10] recado para a governança pela Recepcionista" 201
    trace=$(jb .id)
    check "[10] recado aberto, da governança, na reserva, para hoje" "OPEN $dept $UNDO_ID $bd" \
        "$(jb '"\(.status) \(.departmentId) \(.reservationId) \(.dueOn)"')"
    check "[10] recado criado pela Recepcionista" "$RECEPTIONIST_ID" "$(jb .createdBy)"
    check "[10] texto gravado" true "$(bool [ "$(jb .text)" = "$TRACE_TEXT" ])"

    req GET "/traces?reservationId=$UNDO_ID&limit=50" recepcao
    check_http "[10] lista dos recados da reserva" 200
    check "[10] lista com o recado aberto" "$trace:OPEN" "$(jb '[.items[] | "\(.id):\(.status)"] | join(",")')"
    req GET "/traces?departmentId=$dept&dueOn=$bd&status=OPEN&limit=200" gerente
    check_http "[10] lista do setor no dia pelo Gerente" 200
    check "[10] recado na lista do setor" true \
        "$(jq -r --arg t "$trace" '[.items[].id] | index($t) != null' "$T/body" 2>/dev/null)"

    req POST "/traces/$trace/resolve" recepcao
    check_http "[10] recado resolvido pela Recepcionista" 200
    check "[10] resolvido por quem e quando" "RESOLVED $RECEPTIONIST_ID true" \
        "$(jb '"\(.status) \(.resolvedBy) \(.resolvedAt != null)"')"
    req POST "/traces/$trace/resolve" recepcao
    check_http "[10] segunda resolução" 409
    check "[10] code TRACE_ALREADY_RESOLVED" TRACE_ALREADY_RESOLVED "$(problem_code)"
    req GET "/traces?reservationId=$UNDO_ID&status=RESOLVED&limit=50" recepcao
    check "[10] lista dos resolvidos com o recado" "$trace:RESOLVED" "$(jb '[.items[] | "\(.id):\(.status)"] | join(",")')"
}

s11_serpro_down() {
    section "11. Serpro fora do ar: o check-in conclui e a submissão espera a volta"
    ensure_tokens
    # Antes da queda, a fila sem pendência e o diário sem pedido não casado: o que falhar a seguir é desta seção.
    poll 0 180 5 fnrh_pending_count
    check "[11] nenhuma submissão pendente antes da queda (≤ 3 min)" 0 "$GOT"
    check "[11] fnrh-mock: nenhum pedido não casado antes da queda" 0 "$(mock_unmatched)"
    "${DC[@]}" stop fnrh-mock >"$T/compose.out" 2>&1
    check "[11] fnrh-mock parado (compose stop)" 0 "$?"
    check "[11] fnrh-mock fora do ar" false "$(mock_up)"

    business_date recepcao
    check_http "[11] data operacional" 200
    new_guest Queda
    check_http "[11] hóspede da reserva na queda" 201
    local guest=$GUEST_ID
    book_single "$guest"
    check_http "[11] reserva na queda pela Recepcionista" 201
    DOWN_ID=$BOOKED_ID
    check "[11] reserva com id" true "$(bool is_uuid "$DOWN_ID")"
    jq -n --arg r "$ROOM5" '{roomId: $r}' >"$T/req.json"
    req POST "/reservations/$DOWN_ID/assign-room" recepcao "$T/req.json"
    check_http "[11] alocação da UH 1205" 200
    single_check_in_body "$guest"
    req POST "/reservations/$DOWN_ID/check-in" recepcao "$T/req.json"
    check_http "[11] check-in com o Serpro fora do ar" 200
    check "[11] reserva em casa na 1205" "IN_HOUSE $ROOM5" "$(jb '"\(.reservation.status) \(.reservation.roomId)"')"
    reservation_status "$DOWN_ID"
    check "[11] reserva segue em casa (leitura)" IN_HOUSE "$GOT"

    poll "PENDING gasta" 180 5 fnrh_item "$DOWN_ID" REGISTER_RESERVATION \
        '"\(.status) \(if .attempts >= 1 then "gasta" else "nenhuma" end)"'
    check "[11] registro da reserva pendente, com tentativa gasta (≤ 3 min)" "PENDING gasta" "$GOT"
    check "[11] falha de indisponibilidade na submissão" true \
        "$(fnrh_item "$DOWN_ID" REGISTER_RESERVATION '[.lastFailure] | inside(["UNAVAILABLE", "UNKNOWN_OUTCOME", "SERVER_ERROR"])')"
    check "[11] check-in na FNRH ainda não enviado" PENDING "$(fnrh_item "$DOWN_ID" CHECK_IN .status)"

    "${DC[@]}" start fnrh-mock >"$T/compose.out" 2>&1
    check "[11] fnrh-mock de volta (compose start)" 0 "$?"
    poll true 120 3 mock_up
    check "[11] fnrh-mock respondendo (≤ 2 min)" true "$GOT"
    poll "CHECK_IN:SENT,REGISTER_RESERVATION:SENT" 420 10 fnrh_states "$DOWN_ID"
    check "[11] na volta, registro e check-in enviados (≤ 7 min)" "CHECK_IN:SENT,REGISTER_RESERVATION:SENT" "$GOT"
    check "[11] fnrh-mock: nenhum pedido não casado na volta" 0 "$(mock_unmatched)"
    check "[11] fnrh-mock: hóspede incluído na volta" true \
        "$(bool [ "$(mock_count POST '^/FNRH_API/rest/v2/reservas/[^/]+/hospedes$')" -ge 1 ])"
    printf '       (na volta, conciliações pelo código: %s)\n' \
        "$(mock_count GET '^/FNRH_API/rest/v2/reservas[?]')"
}

s12_clear_credentials() {
    section "12. E16: limpar a credencial da FNRH"
    ensure_tokens
    req GET /fnrh/settings admin
    check_http "[12] configuração da FNRH" 200
    local tag entries
    tag=$(hdr etag)
    check "[12] credencial gravada e integração ligada antes" "true true" "$(jb '"\(.passwordSet) \(.enabled)"')"
    req POST /fnrh/settings/clear-credentials gerente "" "If-Match: $tag"
    check_http "[12] o Gerente não limpa a credencial" 403
    req POST /fnrh/settings/clear-credentials admin
    check_http "[12] limpar sem If-Match" 428
    req POST /fnrh/settings/clear-credentials admin "" "If-Match: $tag"
    check_http "[12] credencial limpa pelo admin com If-Match" 200
    check "[12] sem senha" false "$(jb .passwordSet)"
    check "[12] integração desligada" false "$(jb .enabled)"
    check "[12] sem usuário nem CPF padrão" "null null" "$(jb '"\(.username) \(.defaultRequesterCpf)"')"
    check "[12] ambiente mantido" HOMOLOGATION "$(jb .environment)"
    req GET /fnrh/status recepcao
    check_http "[12] status da FNRH" 200
    check "[12] status: integração desligada" false "$(jb .enabled)"

    req GET "/audit-entries?entityType=fnrh.settings&limit=100" admin
    check_http "[12] trilha da configuração da FNRH" 200
    entries=$(jbc '[.items[] | select(.action == "fnrh.settings.clear-credentials")]')
    check "[12] trilha fnrh.settings.clear-credentials" 1 "$(jq 'length' <<<"$entries" 2>/dev/null)"
    check "[12] trilha: antes com usuário e senha, depois sem e desligada" "true true false false false" \
        "$(jq -r '.[0] | "\(.before.usernameSet) \(.before.passwordSet) \(.after.usernameSet) \(.after.passwordSet) \(.after.enabled)"' \
            <<<"$entries" 2>/dev/null)"
    check "[12] trilha com quem limpou" true "$(jq -r '.[0].userId != null' <<<"$entries" 2>/dev/null)"
    check "[12] trilha sem o usuário da FNRH" false "$(bool [ "${entries//"$FNRH_USER"/}" != "$entries" ])"
    check "[12] trilha sem a senha da FNRH" false "$(bool [ "${entries//"$FNRH_PASSWORD"/}" != "$entries" ])"
    check "[12] trilha não vazia" true "$(bool [ "${#entries}" -gt 2 ])"
}

# O começo de todo JWT ({"alg"… em base64url). Fica numa variável: os rótulos das conferências não o levam, senão a
# varredura da própria saída o acharia nos rótulos.
JWT_PREFIX=eyJ

s13_leak_sweep() {
    section "13. Varredura: logs do compose (sem o fnrh-mock) e a própria saída, sem segredo nem dado de hóspede"
    check "[13] fnrh-mock: nenhum pedido não casado até o fim" 0 "$(mock_unmatched)"
    check "[13] valores procurados (≥ 40)" true "$(bool [ "${#SECRETS[@]}" -ge 40 ])"
    local svc rc
    for svc in api db proxy storage storage-init; do
        "${DC[@]}" logs --no-color "$svc" >"$T/log-$svc" 2>"$T/compose.out"
        rc=$?
        check "[13] logs do $svc lidos" 0 "$rc"
        check "[13] logs do $svc: nenhum segredo nem dado de hóspede" nenhum "$(leaked_kinds "$T/log-$svc")"
        check "[13] logs do $svc: nenhum JWT (prefixo dos access tokens)" 0 "$(grep -c "$JWT_PREFIX" "$T/log-$svc")"
    done
    check "[13] logs da api não vazios" true "$(bool [ -s "$T/log-api" ])"
    check "[13] logs do db não vazios" true "$(bool [ -s "$T/log-db" ])"
    rm -f -- "$T"/log-*
    check "[13] saída do roteiro não vazia" true "$(bool [ -s "$OUT" ])"
    check "[13] saída do roteiro: nenhum segredo nem dado de hóspede" nenhum "$(leaked_kinds "$OUT")"
    check "[13] saída do roteiro: nenhum JWT (prefixo dos access tokens)" 0 "$(grep -c "$JWT_PREFIX" "$OUT")"
}

# ==================================================================================================================

SECTIONS=(s1_public s2_users s3_setup s4_check_in s5_fnrh s6_registration_card s7_stay s8_check_out
    s9_walk_in_and_undo s10_traces s11_serpro_down s12_clear_credentials s13_leak_sweep)

register_env_secrets
printf 'Roteiro do gate do M5 em %s (projeto %s), %s\n' "$BASE" "$PROJECT" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
for s in "${SECTIONS[@]}"; do
    "$s"
done
summary
exit $?
