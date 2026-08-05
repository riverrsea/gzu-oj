#!/usr/bin/env sh
set -eu

# 控制端预检只读取配置和目录，不修改数据库。
for command_name in docker openssl; do
  command -v "$command_name" >/dev/null 2>&1 || { echo "缺少命令：$command_name" >&2; exit 1; }
done
docker compose version >/dev/null
[ -n "${GZU_OJ_DB_PASSWORD:-}" ] || { echo "GZU_OJ_DB_PASSWORD 未设置" >&2; exit 1; }
[ "${#GZU_OJ_DB_PASSWORD}" -ge 16 ] || { echo "数据库密码至少需要 16 位" >&2; exit 1; }
case "${GZU_OJ_SITE_ADDRESS:-}" in
  https://*|http://localhost|http://127.0.0.1*) ;;
  *) echo "正式环境必须使用 HTTPS 域名" >&2; exit 1 ;;
esac
echo "控制端预检通过"
