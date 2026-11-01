# Trackhop server: the sync engine of the Android app behind a small web page, for a server or a NAS.
# Build from the repository root:  docker build -f server/Dockerfile -t trackhop .
# Guide: docs/guide/docker.md (https://trackhop.org/guide/docker)

# The build stage runs on the builder's own platform: its output is JVM bytecode, the same for every
# architecture, so a multi-platform image (amd64, arm64 for a NAS or a Raspberry Pi) compiles once.
FROM --platform=$BUILDPLATFORM gradle:8.14.3-jdk17 AS build
WORKDIR /src
# Only what the server build reads: the engine, the YouTube Music client, the Spotify and Deezer
# web clients (server/build.gradle picks them out of :bridge's sources), the app's texts and
# version, the server itself.
COPY android/core android/core
COPY android/ytm android/ytm
COPY android/bridge/src/main/java android/bridge/src/main/java
COPY android/app/build.gradle android/app/build.gradle
COPY android/app/src/main/res android/app/src/main/res
COPY server server
RUN gradle -p server installDist --no-daemon --quiet

FROM eclipse-temurin:17-jre
LABEL org.opencontainers.image.title="Trackhop server (beta)" \
      org.opencontainers.image.description="Beta. Keeps playlists in sync between music services, on your own server" \
      org.opencontainers.image.source="https://github.com/xlollx/Trackhop" \
      org.opencontainers.image.licenses="GPL-3.0-only"
# The system user, the install path and the SONGPORT_* variables keep their names from before the
# rename to Trackhop: existing data volumes and compose files rely on them.
RUN groupadd --system songport && useradd --system --gid songport --home-dir /data songport \
    && mkdir -p /data && chown songport:songport /data
COPY --from=build /src/server/build/install/songport-server /opt/songport
USER songport
# UTF-8 file names (a playlist file "Città.csv").
# bin/ on the PATH: `docker exec <container> trackhop …` is the command line (Cli.kt).
ENV SONGPORT_DATA=/data SONGPORT_PORT=8080 LANG=C.UTF-8 LC_ALL=C.UTF-8 PATH="/opt/songport/bin:${PATH}"
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["/opt/songport/bin/songport-server"]
