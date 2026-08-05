#!/usr/bin/env sh
set -eu
umask 077

# 数据库与 LocalArtifactStore 必须在同一个维护窗口内配套备份。
target_root=${1:?用法：backup.sh <备份根目录>}
artifact_root=${GZU_OJ_ARTIFACT_ROOT:?请设置 GZU_OJ_ARTIFACT_ROOT}
timestamp=$(date -u +%Y%m%dT%H%M%SZ)
mkdir -p "$target_root"
temporary=$(mktemp -d "$target_root/.gzu-oj-backup.XXXXXX")
trap 'rm -rf "$temporary"' EXIT INT TERM

pg_dump --format=custom --no-owner --no-acl "${GZU_OJ_DB_URL:?请设置 libpq 格式的 GZU_OJ_DB_URL}" > "$temporary/database.dump"
tar -C "$artifact_root" -czf "$temporary/artifacts.tar.gz" .
(cd "$temporary" && sha256sum database.dump artifacts.tar.gz > SHA256SUMS)
printf '%s\n' "backupVersion=1" "createdAt=$timestamp" > "$temporary/manifest.properties"
destination="$target_root/$timestamp"
mv "$temporary" "$destination"
trap - EXIT INT TERM
echo "备份完成：$destination"
