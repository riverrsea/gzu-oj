# Worker 与 API 共用锁定的 Gradle、Kotlin 和 JDK 版本。
FROM gradle:9.6.1-jdk21-alpine AS build
WORKDIR /workspace
COPY --chown=gradle:gradle . .
RUN gradle --no-daemon :worker:bootJar

FROM eclipse-temurin:21.0.8_9-jre-alpine
RUN addgroup -S gzuoj && adduser -S -G gzuoj gzuoj
WORKDIR /app
COPY --from=build /workspace/worker/build/libs/worker-*.jar /app/worker.jar
USER gzuoj
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/worker.jar"]
