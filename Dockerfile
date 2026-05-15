# penpot — Cocoon AI base fork.
#
# Container posture per cai-portal ADR 0003 + foreign-apps rule:
# - Multi-stage; runtime base is Google distroless `java21-debian12:nonroot`.
# - No shell at runtime; exec-form CMD.
# - No package manager in the runtime layer.
# - Trivy CRITICAL gate via .trivyignore (allowlist-by-exception).
#
# Upstream Penpot's build (under ./docker/main/) is Alpine-based and
# bundles multiple services. We build only the backend uberjar here;
# the frontend ships as static assets via the upstream CDN path or a
# sibling container. This Dockerfile is the **deploy artifact** the
# tenant forks build from — never `docker.io/penpotapp/*`.

# Stage 1 — build the backend uberjar with Clojure tooling.
FROM eclipse-temurin:21-jdk AS builder

# Install Clojure CLI (penpot's build expects it).
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl ca-certificates rlwrap \
    && rm -rf /var/lib/apt/lists/* \
    && curl -sL https://download.clojure.org/install/linux-install-1.11.1.1413.sh \
       -o /tmp/install-clojure.sh \
    && chmod +x /tmp/install-clojure.sh \
    && /tmp/install-clojure.sh

WORKDIR /build
COPY . .

# TODO(0004-phase-5a): wire the actual backend build command. Penpot's
# upstream build script is at backend/scripts/build; the output is
# target/uberjar/penpot-backend.jar (subject to upstream change).
# RUN cd backend && ./scripts/build

# Stage 2 — runtime. Distroless JRE.
FROM gcr.io/distroless/java21-debian12:nonroot
WORKDIR /app

# OTel auto-instrumentation agent (ADR D10 Pattern 2). The agent emits
# OTLP to the cai-otel-collector sidecar at localhost:4317; the sidecar
# decorates + forwards to otel-collector.observability.local.
#
# TODO(0004-phase-5a): copy a pinned javaagent.jar version into
# /opt/otel/javaagent.jar at build time. The agent is published at
# https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases.
# COPY --from=builder /tmp/opentelemetry-javaagent.jar /opt/otel/javaagent.jar

ENV JAVA_TOOL_OPTIONS="-javaagent:/opt/otel/javaagent.jar"
ENV OTEL_EXPORTER_OTLP_ENDPOINT="http://localhost:4317"
ENV OTEL_SERVICE_NAME="penpot"
ENV OTEL_RESOURCE_ATTRIBUTES="foreign_app=true"

# TODO(0004-phase-5a): copy the built uberjar.
# COPY --from=builder /build/backend/target/uberjar/penpot-backend.jar /app/penpot-backend.jar

USER nonroot:nonroot
EXPOSE 6060

# Exec-form CMD per ADR 0003.
CMD ["/app/penpot-backend.jar"]
