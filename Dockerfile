# syntax=docker/dockerfile:1.7@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e

FROM maven:3.9.11-eclipse-temurin-17-noble@sha256:e4a7ace3dc0d645ed97f8d9ad0b0d3f0b14fa8d150138f27f116d7105a639b82 AS build

WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

RUN --mount=type=cache,target=/root/.m2 chmod +x mvnw \
    && ./mvnw -B -ntp dependency:go-offline

COPY src/ src/

RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp clean package

FROM eclipse-temurin:17.0.20.1_1-jre-noble@sha256:bb8d601e02cec0e22f49b6f5d803dadc7db58ab692fa77909fb604d58187c7ed AS runtime

RUN apt-get update \
    && apt-get install --no-install-recommends --yes curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system shardkv \
    && useradd --system --gid shardkv --home-dir /app --shell /usr/sbin/nologin shardkv \
    && mkdir -p /app /data \
    && chown -R shardkv:shardkv /app /data

WORKDIR /app

COPY --from=build --chown=shardkv:shardkv /workspace/target/shardkv-*.jar /app/shardkv.jar

USER shardkv:shardkv

EXPOSE 8080
VOLUME ["/data"]

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/shardkv.jar"]
