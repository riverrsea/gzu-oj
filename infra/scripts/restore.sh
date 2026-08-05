#!/usr/bin/env sh
set -eu
umask 077

# 恢复会覆盖目标业务库和制品目录，因此需要显式确认并在停服状态执行。
backup_dir=${1:?用法：restore.sh <备份目录>}
artifact_root=${GZU_OJ_ARTIFACT_ROOT:?请设置 GZU_OJ_ARTIFACT_ROOT}
[ "${GZU_OJ_RESTORE_CONFIRM:-}" = "restore-database-and-artifacts" ] || { echo "请设置 GZU_OJ_RESTORE_CONFIRM=restore-database-and-artifacts" >&2; exit 1; }
[ -f "$backup_dir/database.dump" ] && [ -f "$backup_dir/artifacts.tar.gz" ] || { echo "备份文件不完整" >&2; exit 1; }
(cd "$backup_dir" && sha256sum -c SHA256SUMS)
mkdir -p "$artifact_root"
find "$artifact_root" -mindepth 1 -maxdepth 1 | grep -q . && { echo "制品恢复目录必须为空" >&2; exit 1; }
pg_restore --clean --if-exists --no-owner --no-acl --dbname "${GZU_OJ_DB_URL:?请设置 libpq 格式的 GZU_OJ_DB_URL}" "$backup_dir/database.dump"
tar -C "$artifact_root" -xzf "$backup_dir/artifacts.tar.gz"
echo "数据库与制品恢复完成；启动 API 前请执行一致性抽查"
