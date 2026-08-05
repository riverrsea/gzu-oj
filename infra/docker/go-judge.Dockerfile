# 固定的 GCC 14.2.0 Bookworm 工具链，提供 GNU C17 与 GNU C++17 编译、链接所需的完整依赖。
FROM gcc:14.2.0-bookworm@sha256:b99b86a28812b1e6453a231a947dc43d76fe192788a12f344a9b568bf9f5d24c AS c-toolchain

# 固定的 Python 3.13 运行时；只复制其已构建的解释器和标准库，不在最终镜像下载安装包。
FROM python:3.13-slim-bookworm@sha256:67a1e1f215ccda113cfc024e8639049257e88f273898f595b61476d128d387e8 AS python-runtime

# 固定的 Temurin OpenJDK 21；JDK 目录自包含，运行时复制到最终 Bookworm 镜像。
FROM eclipse-temurin:21-jdk-jammy@sha256:55fb9bf738f5d9b4a6c01b39337e3070d3e27370dd3c478fd1d5d3cd2233c6d8 AS java-runtime

# 固定的官方 go-judge 发行镜像，保留其静态二进制及默认挂载配置。
FROM criyle/go-judge:v1.12.2@sha256:47806fc59c9b414ffd9256ca9578887a5e880dd1fdcc748e0ddd59a41a17d516 AS go-judge-runtime

# 以完整 GCC Bookworm 镜像为最终基础，避免跨发行版复制 C/C++ 运行库。
FROM c-toolchain

# 记录定制镜像的语言运行时版本，便于部署审计与排障。
LABEL org.opencontainers.image.title="gzu-oj-go-judge" \
      org.opencontainers.image.description="包含 C17、C++17、OpenJDK 21 和 CPython 3 的 go-judge 镜像" \
      org.opencontainers.image.version="1.12.2-toolchains"

# 复制已完成构建的 Python、JDK 与 go-judge 文件；本 Dockerfile 不执行 apt 或其他包下载命令。
COPY --from=python-runtime /usr/local /usr/local
COPY --from=java-runtime /opt/java/openjdk /usr/lib/jvm/temurin-21
COPY --from=go-judge-runtime /opt/go-judge /opt/go-judge
COPY infra/docker/go-judge.mount.yaml /opt/mount.yaml

# Worker 固定使用 /usr/bin 路径，统一创建链接并设置运行时环境。
ENV JAVA_HOME=/usr/lib/jvm/temurin-21 \
    PATH=/usr/lib/jvm/temurin-21/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8
RUN ln -sf /usr/local/bin/gcc /usr/bin/gcc \
    && ln -sf /usr/local/bin/g++ /usr/bin/g++ \
    && ln -sf /usr/local/bin/python3 /usr/bin/python3 \
    && ln -sf /usr/lib/jvm/temurin-21/bin/java /usr/bin/java \
    && ln -sf /usr/lib/jvm/temurin-21/bin/javac /usr/bin/javac \
    && ln -sf /usr/lib/jvm/temurin-21/bin/jar /usr/bin/jar

# 与官方镜像保持一致，由 Compose 传入鉴权、资源限制与 no-fallback 参数。
WORKDIR /opt
ENTRYPOINT ["/opt/go-judge"]
