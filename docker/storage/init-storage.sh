#!/bin/sh
# Prepara o MinIO para o servidor (ARCHITECTURE §14, D14): cria o bucket dos anexos e um usuário de
# serviço com política só desse bucket, que a aplicação usa no lugar do root. Roda no serviço
# storage-init do docker/compose.yaml e no container MinIO dos testes (MinioContainer); é
# idempotente: rodar de novo mantém o bucket e os objetos e regrava a senha e a política do usuário.
#
# Variáveis (nenhuma tem valor padrão aqui):
#   ZENY_STORAGE_INIT_ENDPOINT                   URL do MinIO (ex.: http://storage:9000)
#   MINIO_ROOT_USER / MINIO_ROOT_PASSWORD         credencial root, usada só por este script
#   ZENY_STORAGE_BUCKET                           bucket dos anexos
#   ZENY_STORAGE_ACCESS_KEY / ZENY_STORAGE_SECRET_KEY   usuário de serviço da aplicação
set -eu

: "${ZENY_STORAGE_INIT_ENDPOINT:?defina ZENY_STORAGE_INIT_ENDPOINT}"
: "${MINIO_ROOT_USER:?defina MINIO_ROOT_USER}"
: "${MINIO_ROOT_PASSWORD:?defina MINIO_ROOT_PASSWORD}"
: "${ZENY_STORAGE_BUCKET:?defina ZENY_STORAGE_BUCKET}"
: "${ZENY_STORAGE_ACCESS_KEY:?defina ZENY_STORAGE_ACCESS_KEY}"
: "${ZENY_STORAGE_SECRET_KEY:?defina ZENY_STORAGE_SECRET_KEY}"

# O proxy (docker/caddy/Caddyfile) repassa ao MinIO o caminho /<bucket>/…: um bucket com o nome de um
# caminho da aplicação (/api, /actuator) tomaria esse caminho.
case "$ZENY_STORAGE_BUCKET" in
  api | actuator)
    echo "ZENY_STORAGE_BUCKET não pode ser '$ZENY_STORAGE_BUCKET': o proxy confundiria o bucket com um caminho da aplicação" >&2
    exit 1
    ;;
esac

if [ "$ZENY_STORAGE_ACCESS_KEY" = "$MINIO_ROOT_USER" ]; then
  echo "ZENY_STORAGE_ACCESS_KEY precisa ser diferente do usuário root do MinIO" >&2
  exit 1
fi

export MC_CONFIG_DIR="${MC_CONFIG_DIR:-/tmp/zeny-mc}"
policy_name="zeny-app-$ZENY_STORAGE_BUCKET"
policy_file="$(mktemp)"
trap 'rm -f "$policy_file"; rm -rf "$MC_CONFIG_DIR"' EXIT

# A credencial root não vai na linha de comando (ficaria visível em `ps`): o mc a lê da entrada padrão.
printf '%s\n%s\n' "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" | mc alias set zeny "$ZENY_STORAGE_INIT_ENDPOINT" >/dev/null

mc mb --ignore-existing "zeny/$ZENY_STORAGE_BUCKET"
# Sem acesso anônimo: tudo passa por credencial ou por URL assinada.
mc anonymous set none "zeny/$ZENY_STORAGE_BUCKET" >/dev/null

# Só os objetos do bucket: ler, gravar, apagar (compensação de upload desfeito) e listar o bucket.
# Nada de criar ou apagar buckets, mudar políticas ou administrar o servidor.
cat >"$policy_file" <<POLICY
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetBucketLocation", "s3:ListBucket"],
      "Resource": ["arn:aws:s3:::$ZENY_STORAGE_BUCKET"]
    },
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:PutObject",
        "s3:DeleteObject",
        "s3:AbortMultipartUpload",
        "s3:ListMultipartUploadParts"
      ],
      "Resource": ["arn:aws:s3:::$ZENY_STORAGE_BUCKET/*"]
    }
  ]
}
POLICY

mc admin policy create zeny "$policy_name" "$policy_file" >/dev/null
# O segredo do usuário de serviço também não vai na linha de comando: o mc o lê da entrada padrão.
printf '%s\n%s\n' "$ZENY_STORAGE_ACCESS_KEY" "$ZENY_STORAGE_SECRET_KEY" | mc admin user add zeny >/dev/null
# Anexar de novo uma política já anexada falha no mc; a conferência abaixo é o que importa.
mc admin policy attach zeny "$policy_name" --user "$ZENY_STORAGE_ACCESS_KEY" >/dev/null 2>&1 || true
# A imagem do MinIO não traz grep nem sed: a conferência é pelo `case` do shell.
user_info="$(mc admin user info zeny "$ZENY_STORAGE_ACCESS_KEY" --json)"
case "$user_info" in
  *"\"$policy_name\""*) ;;
  *)
    echo "A política $policy_name não ficou anexada ao usuário de serviço" >&2
    exit 1
    ;;
esac

echo "Bucket $ZENY_STORAGE_BUCKET e usuário de serviço $ZENY_STORAGE_ACCESS_KEY prontos (política $policy_name)"
