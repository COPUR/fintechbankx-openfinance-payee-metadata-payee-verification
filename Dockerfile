# syntax=docker/dockerfile:1.7
# svc-of-payee-verification container image.
# Build: docker build -t payee-verification-service:dev .
# The image is built from source with the Gradle wrapper so CI and local
# builds produce the same artifact; tests run in ci/test, not here.

FROM eclipse-temurin:23-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle gradle
COPY src/main src/main
RUN ./gradlew --no-daemon bootJar -x test \
 && java -Djarmode=tools -jar build/libs/payee-verification-service.jar \
      extract --layers --launcher --destination /workspace/extracted

FROM eclipse-temurin:23-jre AS runtime
RUN groupadd --system --gid 10001 payee \
 && useradd --system --uid 10001 --gid payee --no-create-home --shell /usr/sbin/nologin payee
WORKDIR /app
# Layers ordered from least to most frequently changed for cache reuse.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER 10001:10001
EXPOSE 8080 8081
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
