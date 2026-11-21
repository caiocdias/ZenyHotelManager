# Biblioteca comum dos roteiros curl do gate de marco (M5 E12, M6 E12): os argumentos e as recusas, o check/redact,
# o HTTP, o psql do dono, o diário do fnrh-mock, os operadores, os dados de hóspede, a varredura de vazamento e o laço
# das seções. Um lugar só para os controles de segredo: uma correção aqui vale para todos os roteiros.
#
# Uso, no começo de um roteiro (docker/gate/<roteiro>-roteiro.sh), depois do set -uo pipefail e do umask 077:
#
#   ROTEIRO=m6
#   script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
#   . "$script_dir/lib-roteiro.sh" "$@"
#
# O source confere os argumentos (<base-url> <projeto> <env-file>) e recusa (saída 2, sem chamar o docker) o projeto
# zeny ou um nome inválido e um env dentro do projeto ou de um repositório git; define BASE, API, PROJECT, ENV_FILE,
# ROOT, DC, T (diretório temporário, apagado na saída) e OUT (<dir do env>/<roteiro>-roteiro.out, ou a variável
# <ROTEIRO>_ROTEIRO_OUT, ex.: M6_ROTEIRO_OUT) e manda a saída inteira também para o OUT. Pontos de variação: ROTEIRO
# (nome do roteiro, do .out, do diretório temporário e do dispositivo do login), OPERATORS (quem o ensure_tokens
# renova; padrão admin recepcao gerente, o roteiro pode trocar depois do source), o segundo argumento do new_guest
# (o perfil extra, em JSON) e o limiar do leak_sweep. O roteiro termina com SECTIONS=(...) e run_sections.

[ -n "${ROTEIRO:-}" ] || { echo "lib-roteiro.sh: defina ROTEIRO antes do source" >&2; exit 2; }

if [ "$#" -ne 3 ]; then
    echo "uso: $0 <base-url> <projeto> <env-file>" >&2
    exit 2
fi
BASE=${1%/}
PROJECT=$2
ENV_FILE=$3
[ -r "$ENV_FILE" ] || { echo "não consigo ler o env: $ENV_FILE" >&2; exit 2; }
API=$BASE/api/v1

lib_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
ROOT=$(cd "$lib_dir/../.." && pwd -P)

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

T=$(mktemp -d "${TMPDIR:-/tmp}/$ROTEIRO-roteiro.XXXXXX")
trap 'rm -rf -- "$T"' EXIT

out_override=${ROTEIRO^^}_ROTEIRO_OUT
OUT=${!out_override:-$(dirname -- "$ENV_FILE")/$ROTEIRO-roteiro.out}
exec > >(tee -- "$OUT") 2>&1

# --- Conferência, segredos e resumo (um lugar só) ---------------------------------------------------------------

N=0
F=0
SECRETS=()
SECRET_KINDS=()

# secret <valor> [tipo]: guarda um valor que nunca pode sair na tela (segredo ou dado de hóspede): o redact o troca
# por ***, e a varredura (leak_sweep) o procura nos logs e na própria saída (e, achando, diz só o tipo).
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

mock_up() {
    if mock health >/dev/null 2>&1; then echo true; else echo false; fi
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
# Quem o ensure_tokens renova (o roteiro pode trocar a lista depois do source).
OPERATORS=(admin recepcao gerente)
declare -A LOGIN_OF PASSWORD_OF

# login <quem> <login> <senha>: deixa o token de <quem> pronto se der 200.
login() {
    LOGIN_NAME=$2 LOGIN_PASSWORD=$3 DEVICE_ID="gate-$ROTEIRO" DEVICE_NAME="Roteiro do gate ${ROTEIRO^^}" jq -n \
        '{login: env.LOGIN_NAME, password: env.LOGIN_PASSWORD, device: {id: env.DEVICE_ID, name: env.DEVICE_NAME}}' \
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
# Os dados fixos de endereço e contato dos hóspedes do gate (o nome, o CPF e o e-mail são sorteados).
GUEST_PHONE="+55 11 98765-4321"
GUEST_STREET="Rua das Acácias"
GUEST_POSTAL_CODE=01407-000
GUEST_BIRTH_DATE=1990-07-21

# guest_fixed_secrets <data de nascimento>: registra como dado de hóspede o que todo hóspede do gate leva fixo
# (telefone, rua, CEP e a data de nascimento): o redact os troca por *** e a varredura os procura nos logs.
guest_fixed_secrets() {
    secret "$GUEST_PHONE" hóspede
    secret "$GUEST_STREET" hóspede
    secret "$GUEST_POSTAL_CODE" hóspede
    secret "$1" hóspede
}

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
    guest_fixed_secrets "$GUEST_BIRTH_DATE"
    G_NAME=$name G_EMAIL=$email G_CPF=$cpf G_EXTRA=${2:-'{}'} G_PHONE=$GUEST_PHONE G_STREET=$GUEST_STREET \
        G_POSTAL=$GUEST_POSTAL_CODE G_BIRTH=$GUEST_BIRTH_DATE G_NOTES="Gate do ${ROTEIRO^^}" jq -n \
        '{profile: ({fullName: env.G_NAME, socialName: null, nationality: "BR", birthDate: env.G_BIRTH,
            gender: "FEMALE", email: env.G_EMAIL, phone: env.G_PHONE, residenceCountry: "BR",
            address: {street: env.G_STREET, number: "742", complement: "Apto 12", district: "Jardim Paulista",
                      city: "São Paulo", state: "SP", postalCode: env.G_POSTAL, ibgeCityCode: "3550308"},
            preferences: {floor: "Alto", bed: "Casal", pillow: "Baixo"}, vip: false, notes: env.G_NOTES,
            companyId: null} + (env.G_EXTRA | fromjson)),
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

# O começo de todo JWT ({"alg"… em base64url). Fica numa variável: os rótulos das conferências não o levam, senão a
# varredura da própria saída o acharia nos rótulos.
JWT_PREFIX=eyJ

# leak_sweep <seção> <mínimo de valores>: a varredura, sempre a última seção. Os logs da api, do db, do proxy, do
# storage e do storage-init (o fnrh-mock fica de fora: faz o papel do Serpro) e a própria saída do roteiro não contêm
# nenhum valor registrado com secret nem o prefixo de um JWT; na falha, só o tipo do valor achado.
leak_sweep() {
    local n=$1 min=$2
    section "$n. Varredura: logs do compose (sem o fnrh-mock) e a própria saída, sem segredo nem dado de hóspede"
    check "[$n] fnrh-mock: nenhum pedido não casado até o fim" 0 "$(mock_unmatched)"
    check "[$n] valores procurados (≥ $min)" true "$(bool [ "${#SECRETS[@]}" -ge "$min" ])"
    local svc rc
    for svc in api db proxy storage storage-init; do
        "${DC[@]}" logs --no-color "$svc" >"$T/log-$svc" 2>"$T/compose.out"
        rc=$?
        check "[$n] logs do $svc lidos" 0 "$rc"
        check "[$n] logs do $svc: nenhum segredo nem dado de hóspede" nenhum "$(leaked_kinds "$T/log-$svc")"
        check "[$n] logs do $svc: nenhum JWT (prefixo dos access tokens)" 0 "$(grep -c "$JWT_PREFIX" "$T/log-$svc")"
    done
    check "[$n] logs da api não vazios" true "$(bool [ -s "$T/log-api" ])"
    check "[$n] logs do db não vazios" true "$(bool [ -s "$T/log-db" ])"
    rm -f -- "$T"/log-*
    check "[$n] saída do roteiro não vazia" true "$(bool [ -s "$OUT" ])"
    check "[$n] saída do roteiro: nenhum segredo nem dado de hóspede" nenhum "$(leaked_kinds "$OUT")"
    check "[$n] saída do roteiro: nenhum JWT (prefixo dos access tokens)" 0 "$(grep -c "$JWT_PREFIX" "$OUT")"
}

# run_sections: registra os segredos do env, roda as seções de SECTIONS em ordem e sai com o resumo (1 com falha).
run_sections() {
    local s
    register_env_secrets
    printf 'Roteiro do gate do %s em %s (projeto %s), %s\n' "${ROTEIRO^^}" "$BASE" "$PROJECT" \
        "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    for s in "${SECTIONS[@]}"; do
        "$s"
    done
    summary
    exit $?
}
