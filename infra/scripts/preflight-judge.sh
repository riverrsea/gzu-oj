#!/usr/bin/env sh
set -eu

# 判题端必须运行在支持 cgroup v2 的 Linux/WSL2 发行版中。
[ "$(uname -s)" = "Linux" ] || { echo "判题端只支持 Linux" >&2; exit 1; }
[ -r /sys/fs/cgroup/cgroup.controllers ] || { echo "未检测到 cgroup v2" >&2; exit 1; }
controllers=" $(cat /sys/fs/cgroup/cgroup.controllers) "
for controller in cpu memory pids; do
  case "$controllers" in *" $controller "*) ;; *) echo "缺少 cgroup 控制器：$controller" >&2; exit 1 ;; esac
done
worker_token=${GZU_OJ_WORKER_TOKEN:-}
go_judge_token=${GZU_OJ_GO_JUDGE_TOKEN:-}
[ "${#worker_token}" -ge 40 ] || { echo "Worker Token 至少需要 40 位" >&2; exit 1; }
[ "${#go_judge_token}" -ge 40 ] || { echo "go-judge Token 至少需要 40 位" >&2; exit 1; }
case "${GZU_OJ_CONTROL_BASE_URL:-}" in https://*) ;; *) echo "异地控制端地址必须使用 HTTPS" >&2; exit 1 ;; esac
echo "判题端主机预检通过；Worker 启动后还会执行真实 CPU、内存和 PID 限制探针"
