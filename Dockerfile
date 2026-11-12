# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

ARG MODULE
COPY pom.xml ./
COPY auth-server/pom.xml auth-server/
COPY demo-client/pom.xml demo-client/
COPY auth-server/src/ auth-server/src/
COPY demo-client/src/ demo-client/src/

RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -ntp -pl "$MODULE" -am package \
    && cp "$MODULE"/target/"$MODULE"-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-noble
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app \
    && install -d -m 0750 -o app -g app /app/data \
    && install -m 0640 -o app -g app /dev/null /app/data/.volume-seed

COPY --from=build /workspace/app.jar /app/app.jar
USER app
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
