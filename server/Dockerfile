# denonmusic-server: the headless control server. Build from the repository root:
#
#   docker build -f server/Dockerfile -t denonmusic-server:local .
#
# The build stage has no Android SDK, and must not need one: settings.gradle.kts leaves the Android
# modules out when none is present, and :server depends only on the pure-JVM protocol modules.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY core core
COPY server server
RUN ./gradlew --no-daemon :server:installDist

FROM eclipse-temurin:21-jre
COPY --from=build /src/server/build/install/denonmusic-server /opt/denonmusic-server

# Unraid's own convention for a container that writes to appdata (nobody:users), so the saved index
# is not left owned by root on the array.
RUN mkdir /data && chown 99:100 /data
USER 99:100
VOLUME /data
EXPOSE 8901

# A plain TCP connect: the JRE image has no curl, and whether the receiver is reachable is /health's
# business to report, not a reason for Docker to call the container unhealthy.
HEALTHCHECK --interval=60s --timeout=5s --start-period=20s \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${DENON_HTTP_PORT:-8901}' || exit 1

ENTRYPOINT ["/opt/denonmusic-server/bin/denonmusic-server"]
