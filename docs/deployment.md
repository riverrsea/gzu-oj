# 部署说明

## 控制端

公网 2 核 2 GB 主机默认只运行 `compose.control.yml`：PostgreSQL、API、Caddy 和静态前端。先复制 `.env.example`，设置域名、数据库强密码，并将 `GZU_OJ_SECURE_COOKIE=true`。

```bash
set -a
. ./.env
set +a
infra/scripts/preflight-control.sh
docker compose -f infra/compose/compose.control.yml up -d --build
```

Caddy 自动申请并续签 HTTPS 证书。仅开放 80/443；PostgreSQL 和 API 只在控制网络暴露。健康检查地址为 `/actuator/health`。

首个账号完成邮箱验证后，可在维护窗口通过 SQL 将该账号角色改为 `ADMIN`；不要在镜像、迁移或环境示例中写入默认管理员密码。管理员再通过 `/api/v1/admin/workers` 创建判题节点，明文 Token 只返回一次。

## 独立 WSL 判题端

创建专用 WSL2 发行版，启用 systemd 和 cgroup v2，并关闭 Windows 私人目录自动挂载。判题发行版只保存 Worker Token，不保存数据库凭据或完整制品。

```bash
set -a
. ./.env
set +a
infra/scripts/preflight-judge.sh
docker compose -f infra/compose/compose.judge.yml up -d --build
```

`go-judge:5050` 只存在于 `judge-internal` 网络，没有宿主机端口。Worker 同时连接内部网络和出站网络，通过 HTTPS 访问控制端。初始 `GZU_OJ_WORKER_SLOTS=4`；主机需要日常使用时可以停止 Worker，未领取任务继续保留在 PostgreSQL，过期租约会重新入队。

### 定制 go-judge 镜像

`infra/docker/go-judge.Dockerfile` 不执行 `apt`、`apk` 或其他包管理器下载。它固定拉取 GCC 14.2.0 Bookworm、CPython 3.13 Bookworm、Temurin OpenJDK 21 和官方 go-judge 的内容摘要，在多阶段构建中复制已经安装好的语言工具链。构建产物的默认标签为 `gzu-oj/go-judge:v1.12.2-toolchains`。

```bash
docker compose -f infra/compose/compose.judge.yml build --pull go-judge
docker run --rm --entrypoint /bin/sh gzu-oj/go-judge:v1.12.2-toolchains -ceu '
  gcc --version
  g++ --version
  javac --version
  python3 --version
'
```

若要在镜像仓库或另一台 WSL 主机复用已构建产物，推送该标签后设置 `GZU_OJ_GO_JUDGE_IMAGE=registry.example/gzu-oj/go-judge:v1.12.2-toolchains`。Compose 仍保留 `build` 定义，首次部署或本地无此镜像时可复现构建；生产环境建议先在受控构建机生成并签名镜像。

## 一体化

一体化适合开发或资源充足的 Linux 主机：

```bash
docker compose -f infra/compose/compose.all-in-one.yml up -d --build
```

低配公网机只建议设置一槽。容器化 go-judge 需要 `privileged` 和宿主 cgroup v2，因此不要在共享、不可信宿主上部署判题端。
