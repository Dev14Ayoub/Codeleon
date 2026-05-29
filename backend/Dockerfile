# syntax=docker/dockerfile:1.6
#
# Multi-stage build for the Codeleon Spring Boot backend.
# Stage 1: compile with Maven + JDK 23 against the source tree.
# Stage 2: ship the fat jar on a slim JRE 21 image (the bytecode target).
#
# Two-stage keeps the final image at ~250 MB instead of ~1 GB.

# ---------------------------------------------------------------------------
# Stage 1 — build
# ---------------------------------------------------------------------------
FROM eclipse-temurin:23-jdk AS build

WORKDIR /workspace

# Cache the Maven dependencies layer separately from the source so that
# editing application code does not re-download every dependency on
# every build.
COPY pom.xml ./
RUN apt-get update && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/* \
    && mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests clean package \
    && mv target/codeleon-backend-*.jar target/codeleon-backend.jar

# ---------------------------------------------------------------------------
# Stage 2 — runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy

# Run as a non-root user so a compromised JVM cannot escalate.
RUN groupadd -r codeleon && useradd -r -g codeleon -d /app codeleon

# Docker CLI for the in-app code runner. The backend shells out to
# `docker run ...` to sandbox user code; the host's Docker daemon is
# reached via /var/run/docker.sock bind-mounted at runtime. We grab the
# static binary (~70 MB) rather than docker-ce-cli — no apt repo dance,
# no daemon pulled in as a transitive dependency.
ARG DOCKER_VERSION=26.1.4
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && curl -fsSL "https://download.docker.com/linux/static/stable/x86_64/docker-${DOCKER_VERSION}.tgz" -o /tmp/docker.tgz \
    && tar -xzf /tmp/docker.tgz -C /tmp \
    && mv /tmp/docker/docker /usr/local/bin/docker \
    && rm -rf /tmp/docker /tmp/docker.tgz \
    && rm -rf /var/lib/apt/lists/*

# Match the host's docker group GID so the non-root codeleon user can
# talk to the bind-mounted socket without root. Override via build-arg
# on hosts where the docker group has a different GID (check with
# `getent group docker` on the host).
ARG DOCKER_GID=983
RUN groupadd -g ${DOCKER_GID} docker && usermod -aG docker codeleon

WORKDIR /app

COPY --from=build /workspace/target/codeleon-backend.jar /app/codeleon-backend.jar

RUN chown -R codeleon:codeleon /app
USER codeleon

# Healthcheck — Spring Boot Actuator endpoint if exposed, else just check
# the HTTP server answers. Tomcat returns 401 on protected endpoints; that
# still proves the JVM is up and serving.
HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=3 \
    CMD wget -q --spider http://localhost:8080/api/v1/auth/providers || exit 1

EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Dnet.bytebuddy.experimental=true", "-jar", "/app/codeleon-backend.jar"]
