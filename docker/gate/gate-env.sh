#!/usr/bin/env bash
# Gera o env do compose isolado do gate (M5 E12), com segredos sorteados só para ele.
#
#   docker/gate/gate-env.sh <dir> <porta>
#
# Grava <dir>/gate.env (modo 600) a partir do docker/.env.example (achado pelo caminho deste script, sem git: funciona
# numa cópia limpa), com as chaves de cifragem e do JWT, as senhas do banco, do MinIO e do admin sorteadas pelo
# openssl, o proxy em 127.0.0.1:<porta>, o profile prod e o push desligado. Imprime só o caminho do arquivo, nunca
# valores. Recusa (exit 2) um <dir> dentro da raiz do projeto ou de qualquer repositório git (um ancestral com .git,
# o que pega o checkout principal acima de um worktree): o env do gate nunca fica num repositório, e o docker/.env do
# usuário nunca é lido nem tocado. Requer bash, openssl e o realpath do coreutils (GNU).
set -euo pipefail
umask 077

usage() {
    echo "uso: $0 <dir fora do repositório> <porta do proxy>" >&2
    exit 2
}

[ "$#" -eq 2 ] || usage
target_dir=$1
port=$2

if ! [[ "$port" =~ ^[0-9]{1,5}$ ]]; then
    echo "porta inválida: $port" >&2
    exit 2
fi
# Em base 10 ("0080" vira 80, nunca octal): o env e a URL pública levam a porta sem zeros à esquerda.
port=$((10#$port))
if [ "$port" -lt 1 ] || [ "$port" -gt 65535 ]; then
    echo "porta inválida: $port" >&2
    exit 2
fi

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
project_root=$(realpath "$script_dir/../..")
example="$project_root/docker/.env.example"
[ -f "$example" ] || { echo "não achei $example" >&2; exit 1; }

# Compara caminhos reais (links resolvidos), antes de criar qualquer coisa no destino.
target_real=$(realpath -m -- "$target_dir")
case "$target_real/" in
    "$project_root"/*)
        echo "recusado: o env do gate não pode ficar dentro do projeto ($project_root)" >&2
        exit 2
        ;;
esac
# Nem dentro de outro repositório: um worktree fica dentro do checkout principal (.claude/worktrees/…), e a raiz
# acima só enxerga o worktree. Recusa o destino se ele ou um ancestral tem um .git (diretório ou arquivo).
dir=$target_real
while :; do
    if [ -e "$dir/.git" ]; then
        echo "recusado: o env do gate não pode ficar dentro de um repositório git ($dir)" >&2
        exit 2
    fi
    [ "$dir" = / ] && break
    dir=$(dirname -- "$dir")
done

mkdir -p -- "$target_real"
env_file="$target_real/gate.env"

encryption_key=$(openssl rand -base64 32)
jwt_key=$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 2>/dev/null |
    openssl pkcs8 -topk8 -nocrypt -outform DER | base64 | tr -d '\n')
# Senhas em hexadecimal: sem caractere que precise de escape no env, no SQL do initdb ou no JSON do roteiro.
db_migration_password=$(openssl rand -hex 24)
db_password=$(openssl rand -hex 24)
admin_password=$(openssl rand -hex 16)
storage_root_password=$(openssl rand -hex 24)
storage_secret_key=$(openssl rand -hex 24)

# As variáveis que este script define saem do modelo e vão, com os valores do gate, no bloco do fim.
generated='^(ZENY_PROFILE|ZENY_HTTP_PORT|ZENY_HTTP_ADDRESS|ZENY_DB_MIGRATION_PASSWORD|ZENY_DB_PASSWORD|'
generated+='ZENY_ENCRYPTION_KEYS|ZENY_ENCRYPTION_ACTIVE_KEY_ID|ZENY_JWT_KEYS|ZENY_JWT_ACTIVE_KEY_ID|'
generated+='ZENY_ADMIN_PASSWORD|ZENY_STORAGE_ROOT_PASSWORD|ZENY_STORAGE_SECRET_KEY|ZENY_STORAGE_PUBLIC_ENDPOINT|'
generated+='ZENY_FCM_CREDENTIALS)='

tmp_file=$(mktemp "$target_real/.gate.env.XXXXXX")
trap 'rm -f -- "$tmp_file"' EXIT
{
    grep -Ev "$generated" "$example"
    printf '\n# ---- gerado por docker/gate/gate-env.sh (segredos sorteados só para este gate)\n'
    printf 'ZENY_PROFILE=prod\n'
    printf 'ZENY_HTTP_PORT=%s\n' "$port"
    printf 'ZENY_HTTP_ADDRESS=127.0.0.1\n'
    printf 'ZENY_STORAGE_PUBLIC_ENDPOINT=http://localhost:%s\n' "$port"
    printf 'ZENY_DB_MIGRATION_PASSWORD=%s\n' "$db_migration_password"
    printf 'ZENY_DB_PASSWORD=%s\n' "$db_password"
    printf 'ZENY_ENCRYPTION_KEYS=gate:%s\n' "$encryption_key"
    printf 'ZENY_ENCRYPTION_ACTIVE_KEY_ID=gate\n'
    printf 'ZENY_JWT_KEYS=gate:%s\n' "$jwt_key"
    printf 'ZENY_JWT_ACTIVE_KEY_ID=gate\n'
    printf 'ZENY_ADMIN_PASSWORD=%s\n' "$admin_password"
    printf 'ZENY_STORAGE_ROOT_PASSWORD=%s\n' "$storage_root_password"
    printf 'ZENY_STORAGE_SECRET_KEY=%s\n' "$storage_secret_key"
    printf 'ZENY_FCM_CREDENTIALS=\n'
} >"$tmp_file"
chmod 600 -- "$tmp_file"
mv -f -- "$tmp_file" "$env_file"
trap - EXIT

echo "$env_file"
