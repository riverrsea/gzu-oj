# 官方 go-judge 固定版本上补齐首版四语言工具链。
FROM criyle/go-judge:v1.12.2
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
       gcc g++ openjdk-21-jdk-headless python3 ca-certificates \
    && rm -rf /var/lib/apt/lists/*
