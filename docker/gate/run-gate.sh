#!/usr/bin/env bash
# Ciclo completo do gate do M5 (E12): env sorteado, compose isolado, readiness, roteiro e down -v, sempre.
#
#   docker/gate/run-gate.sh <workdir> <porta> [projeto]
#
#   <workdir>  diretório de trabalho fora do repositório (o gate-env.sh recusa um dentro do projeto ou de um repositório
#              git); recebe o gate.env (apagado no fim) e o m5-roteiro.out (a saída do roteiro, sem segredo)
#   <porta>    porta livre do proxy em 127.0.0.1, ex.: 18112
#   [projeto]  o projeto Compose do gate (padrão zeny-gate); nunca zeny, o do usuário
#
# Gera o env pelo gate-env.sh, confere a composição (config -q), sobe com up -d --build, espera o readiness, roda o
# m5-roteiro.sh e, na saída (também em falha, erro ou Ctrl+C, por trap EXIT), faz down -v, apaga o env e conta as
# sobras do projeto (contêineres, volumes e redes). Recusa (saída 2) um projeto que já tenha contêiner, volume ou rede:
# o roteiro precisa de um banco novo e o down -v do fim nunca apaga o que não subiu. Sai com o código do roteiro (0 sem
# falhas, 1 com falhas) ou 1 se o ambiente não subiu. Requer docker com o Compose v2, curl, jq, openssl e o coreutils.
set -uo pipefail
umask 077

usage() {
    echo "uso: $0 <workdir fora do repositório> <porta> [projeto]" >&2
    exit 2
}

[ "$#" -ge 2 ] && [ "$#" -le 3 ] || usage
WORKDIR=$1
PORT=$2
PROJECT=${3:-zeny-gate}

if ! [[ $PROJECT =~ ^[a-z0-9][a-z0-9_-]*$ ]] || [ "$PROJECT" = zeny ]; then
    echo "recusado: projeto inválido ou do usuário: $PROJECT" >&2
    exit 2
fi

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
ROOT=$(cd "$script_dir/../.." && pwd -P)

leftovers() {
    local label="label=com.docker.compose.project=$PROJECT"
    printf 'contêineres %s, volumes %s, redes %s' \
        "$(docker ps -aq --filter "$label" | wc -l)" \
        "$(docker volume ls -q --filter "$label" | wc -l)" \
        "$(docker network ls -q --filter "$label" | wc -l)"
}

# Antes de gerar o env e de armar o trap: um projeto já existente fica intocado.
existing=$(leftovers) || { echo "não consegui consultar o docker" >&2; exit 2; }
if [ "$existing" != "contêineres 0, volumes 0, redes 0" ]; then
    echo "recusado: o projeto $PROJECT já existe ($existing); use outro nome ou derrube-o antes" >&2
    exit 2
fi

ENV_FILE=$("$script_dir/gate-env.sh" "$WORKDIR" "$PORT") || exit 2
# A porta como o gate-env.sh a gravou (em base 10).
PORT=$(grep -m1 '^ZENY_HTTP_PORT=' "$ENV_FILE" | cut -d= -f2-)
DC=(docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f "$ROOT/docker/compose.yaml" -f "$ROOT/docker/gate/compose.gate.yaml")

cleanup() {
    local rc=$?
    trap - EXIT INT TERM
    echo "== down -v"
    "${DC[@]}" down -v --remove-orphans
    echo "down exit=$?"
    rm -f -- "$ENV_FILE"
    echo "sobras: $(leftovers)"
    exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

echo "== config -q"
"${DC[@]}" config -q || exit 1
echo "config ok"

echo "== up -d --build"
"${DC[@]}" up -d --build || exit 1

echo "== readiness"
BASE=http://localhost:$PORT
started=$SECONDS
deadline=$((SECONDS + 300))
until curl -fsS -o /dev/null --max-time 5 "$BASE/actuator/health/readiness"; do
    if [ "$SECONDS" -ge "$deadline" ]; then
        echo "readiness sem 200 em 300 s"
        "${DC[@]}" ps --format 'table {{.Service}}\t{{.State}}\t{{.Status}}'
        exit 1
    fi
    sleep 5
done
echo "readiness HTTP 200 após ~$((SECONDS - started))s"
"${DC[@]}" ps -a --format 'table {{.Service}}\t{{.State}}\t{{.Status}}'

echo "== roteiro"
"$script_dir/m5-roteiro.sh" "$BASE" "$PROJECT" "$ENV_FILE"
rc=$?
echo "roteiro exit=$rc"

echo "== logs da api: níveis"
"${DC[@]}" logs --no-color api 2>/dev/null | grep -oE ' (TRACE|DEBUG|INFO|WARN|ERROR) ' | sort | uniq -c

exit "$rc"
