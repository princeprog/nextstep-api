# syntax=docker/dockerfile:1
# Container image for the NextStep API, for any host that runs containers. It starts the production profile, so it
# refuses to start until the production settings are set (README, "Container image"). Base images are pinned by digest,
# and Dependabot proposes updates.

FROM eclipse-temurin:25-jdk@sha256:83f4a7fc27df94dcc7b8de04d14cbbba8da14406f921e966969c3b09000aa507 AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn/ .mvn/
COPY src/ src/
COPY openapi/ openapi/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package \
    && cp "$(ls target/nextstep-api-*.jar | head -n 1)" /tmp/app.jar

FROM eclipse-temurin:25-jre@sha256:ecdc0adb37bde1a281c4d245a3cd535004b1fc8322d560f5b09463b248c81df0
RUN groupadd --system --gid 10001 nextstep \
    && useradd --system --uid 10001 --gid nextstep --no-create-home --shell /usr/sbin/nologin nextstep
WORKDIR /app
# The jar stays owned by root, so code running in the API can't rewrite it.
COPY --from=build /tmp/app.jar /app/app.jar
USER 10001
ENV SPRING_PROFILES_ACTIVE=production
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
