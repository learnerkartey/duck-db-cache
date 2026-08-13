# Build stage
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

COPY gradlew gradle.properties settings.gradle build.gradle ./
COPY gradle ./gradle
COPY src ./src

RUN chmod +x gradlew && \
    ./gradlew bootJar --no-daemon

# Runtime stage
FROM eclipse-temurin:17-jre-jammy

RUN groupadd --gid 10001 datacache && \
    useradd --uid 10001 --gid datacache --no-create-home --shell /usr/sbin/nologin datacache && \
    mkdir -p /data/cache /data/cache/temp && \
    chown -R datacache:datacache /data/cache

WORKDIR /app
COPY --from=build /workspace/build/libs/data-cache-service.jar /app/data-cache-service.jar

USER datacache:datacache

ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=60"

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/data-cache-service.jar"]
