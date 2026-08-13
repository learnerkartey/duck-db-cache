# Build stage
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

COPY gradlew gradle.properties settings.gradle build.gradle ./
COPY gradle ./gradle
COPY data-cache-core ./data-cache-core
COPY data-cache-app ./data-cache-app

RUN chmod +x gradlew && \
    ./gradlew :data-cache-app:bootJar --no-daemon

# Runtime stage
FROM eclipse-temurin:17-jre-jammy

RUN groupadd --gid 10001 datacache && \
    useradd --uid 10001 --gid datacache --no-create-home --shell /usr/sbin/nologin datacache && \
    mkdir -p /data/cache /data/cache/temp && \
    chown -R datacache:datacache /data/cache

WORKDIR /app
COPY --from=build /workspace/data-cache-app/build/libs/data-cache-app.jar /app/data-cache-app.jar

USER datacache:datacache

ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=60"

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/data-cache-app.jar"]
