#!/usr/bin/env bash
# Barra mudança incompatível (breaking change) no contrato da API v1 (ARCHITECTURE §7, D6, RNF-03).
#
# Compara o api/openapi.yaml da árvore de trabalho com o da base, que é o ponto em que o código
# atual saiu da referência indicada (git merge-base): numa branch, o ponto de partida dela; na
# main, o estado anterior ao push. Usa o oasdiff pela imagem oficial, com versão e digest fixos.
#
# Uso (na raiz do repositório; precisa de git e Docker):
#
#   .github/scripts/check-api-breaking.sh [REFERÊNCIA]     # padrão: origin/main
#
# Sai com 1 se houver mudança de nível ERR em /api/v1 (endpoint, parâmetro ou propriedade
# removidos, tipo trocado, campo que vira obrigatório...). Mudança incompatível vai para /api/v2.
# Variáveis opcionais: OASDIFF_FORMAT (text; a CI usa githubactions) e OASDIFF_IMAGE.
set -euo pipefail

readonly contract="api/openapi.yaml"
readonly image="${OASDIFF_IMAGE:-tufin/oasdiff:v1.33.0@sha256:6263a96dd2ef0726c54e21fea9b8e1607eac4841add0079324b424c1f52b819c}"
readonly format="${OASDIFF_FORMAT:-text}"
readonly reference="${1:-origin/main}"

cd "$(git rev-parse --show-toplevel)"

if ! git rev-parse --verify --quiet "${reference}^{commit}" >/dev/null; then
    echo "Referência '${reference}' não encontrada no repositório (faça git fetch)." >&2
    exit 2
fi
base_commit="$(git merge-base "${reference}" HEAD)"
if [[ "${base_commit}" == "$(git rev-parse HEAD)" ]]; then
    echo "A base é o próprio HEAD: só as mudanças não commitadas do contrato entram na comparação."
fi

if [[ ! -f "${contract}" ]]; then
    echo "${contract} não existe na árvore de trabalho." >&2
    exit 2
fi
if ! git cat-file -e "${base_commit}:${contract}" 2>/dev/null; then
    echo "A base ${base_commit:0:12} não tem ${contract}: não há contrato anterior para comparar."
    exit 0
fi

specs="$(mktemp -d)"
trap 'rm -rf "${specs}"' EXIT
# Mesmos caminhos relativos do repositório: na CI, as anotações apontam para o api/openapi.yaml.
mkdir -p "${specs}/base/api" "${specs}/api"
git show "${base_commit}:${contract}" >"${specs}/base/${contract}"
cp "${contract}" "${specs}/${contract}"
chmod -R a+rX "${specs}"

echo "oasdiff: ${contract} da base ${base_commit:0:12} (merge-base com ${reference}) × árvore de trabalho"
# No formato githubactions, uma mudança achada no lado da base (endpoint removido) viria com
# file=base/api/openapi.yaml, que não existe no repositório: a anotação passa a apontar para o
# api/openapi.yaml, sem linha (a linha é a da base). O pipefail preserva a saída do oasdiff.
docker run --rm --network none -v "${specs}:/specs:ro" -w /specs "${image}" breaking \
    "base/${contract}" "${contract}" \
    --match-path '^/api/v1(/|$)' \
    --fail-on ERR \
    --allow-external-refs=false \
    --format "${format}" \
    --lang pt-br |
    sed -E "s#file=base/${contract}(,line=[0-9]+)?(,col=[0-9]+)?#file=${contract}#"
echo "Nenhuma mudança incompatível (nível ERR) em /api/v1."
