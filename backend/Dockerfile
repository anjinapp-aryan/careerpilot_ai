FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# ── Runtime ────────────────────────────────────────────────────────────────────
# Phase 12A — base image changed from `eclipse-temurin:25-jre-alpine` to Debian
# bookworm carrying a copied Temurin JRE. This is NOT a preference; the Alpine
# image could not run browser automation at all, for three independently fatal
# reasons, all verified on the production VM (Oracle Linux 9.8, aarch64):
#
#   1. musl vs glibc — Playwright's Chromium and its Node driver are glibc builds.
#      Alpine is musl. Officially unsupported, and it does not work.
#   2. No browser — nothing installed Chromium, and nothing populated
#      ~/.cache/ms-playwright. Verified on the live container: both absent.
#   3. ARM — Playwright publishes NO Chromium build for linux-arm64, so its
#      normal "download on first use" path cannot succeed here regardless of libc.
#      The supported route is the distribution's own Chromium, which Debian
#      bookworm provides for arm64. Verified: `chromium 151.0.7922.71` installs
#      cleanly at /usr/bin/chromium on this exact hardware.
#
# The JRE is copied rather than apt-installed so the Java runtime stays byte-identical
# to what was previously shipped (Temurin 25). Verified on the VM: Temurin 25.0.3
# runs correctly on bookworm's glibc 2.36.
FROM eclipse-temurin:25-jre-noble AS jre

FROM debian:bookworm-slim
WORKDIR /app

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="/opt/java/openjdk/bin:${PATH}"
COPY --from=jre /opt/java/openjdk /opt/java/openjdk

# chromium              — the browser Playwright drives (see BrowserLaunchOptionsFactory)
# fonts-liberation      — without any font, headless Chromium renders blank/garbled text,
#                         which would silently corrupt the form screenshots that the
#                         human FORM_SCREENSHOT approval gate depends on
# ca-certificates       — TLS to employer/ATS sites and to Neon/MinIO
# tini                  — PID 1 that reaps zombies. Chromium spawns child processes; without
#                         a reaper, crashed renderers accumulate as zombies inside the container
# wget                  — used by this service's compose healthcheck (BusyBox provided it on
#                         Alpine; Debian slim does not ship it by default)
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      chromium \
      fonts-liberation \
      ca-certificates \
      tini \
      wget \
 && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/target/*.jar app.jar

# Non-root. Chromium loading untrusted third-party job pages with --no-sandbox
# (required: the container has no user namespace) is the weakest point in this
# design; running it as root would mean a renderer exploit starts with root inside
# the container. HOME must exist and be writable — Chromium writes its user-data
# directory there.
RUN useradd --system --create-home --home-dir /home/careerpilot --shell /usr/sbin/nologin careerpilot \
 && chown -R careerpilot:careerpilot /app /home/careerpilot
ENV HOME=/home/careerpilot
USER careerpilot

# Tells BrowserLaunchOptionsFactory to use the distro Chromium instead of trying to
# resolve Playwright's own (non-existent on arm64) download.
ENV BROWSER_AUTOMATION_LAUNCH_EXECUTABLE_PATH=/usr/bin/chromium

EXPOSE 8080

# Heap sizing — explicit -Xmx replaces MaxRAMPercentage=50. Measured on the VM:
# 5.5GiB total, backend RSS 621MiB. With a container mem_limit now set (2048m, see
# docker-compose.yml) MaxRAMPercentage=50 would grant a 1024m heap; adding non-heap
# (~450m) plus a Chromium child process (~600m peak, launched INSIDE this container
# by Playwright) exceeds that limit and gets the container OOM-killed. An explicit
# 768m heap leaves verified room for both. Chromium is a child of this process, so
# this container's limit must cover the JVM AND the browser.
#
# MaxMetaspaceSize=320m is retained deliberately: 160m caused a real production
# crash-loop (OutOfMemoryError: Metaspace) after hours of uptime.
# SerialGC is retained and is now clearly correct — the VM has exactly 1 vCPU,
# where parallel/concurrent collectors cost more than they return.
ENV JAVA_OPTS="-Xms256m -Xmx768m -XX:+UseContainerSupport -XX:+UseSerialGC -Xss256k -XX:MaxMetaspaceSize=320m -XX:CompressedClassSpaceSize=96m -XX:MaxDirectMemorySize=32m -XX:ReservedCodeCacheSize=64m -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["/usr/bin/tini", "--", "sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
