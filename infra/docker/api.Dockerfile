# 构建阶段固定 Gradle 与 JDK 21，产物不携带构建缓存。
FROM gradle:9.6.1-jdk21-alpine AS build
WORKDIR /workspace
COPY --chown=gradle:gradle . .
RUN gradle --no-daemon :api:bootJar

# 运行阶段只保留 JRE 和 API 可执行包。
FROM eclipse-temurin:21.0.8_9-jre-alpine
RUN addgroup -S gzuoj && adduser -S -G gzuoj gzuoj
WORKDIR /app
COPY --from=build /workspace/api/build/libs/api-*.jar /app/api.jar
RUN mkdir -p /data/artifacts && chown -R gzuoj:gzuoj /data
USER gzuoj
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/api.jar"]
