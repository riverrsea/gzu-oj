# 前端构建使用 lockfile，确保依赖解析可复现。
FROM node:24.13.0-alpine AS web-build
WORKDIR /workspace/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# Caddy 同时提供静态文件、API 反向代理和自动 HTTPS。
FROM caddy:2.10.2-alpine
COPY infra/caddy/Caddyfile /etc/caddy/Caddyfile
COPY --from=web-build /workspace/web/dist /srv
