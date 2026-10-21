# syntax=docker/dockerfile:1
# Build
FROM eclipse-temurin:21-jdk AS build
# Optional: trust an extra CA during the build only (corporate/TLS-intercepting proxies).
# Pass with a build secret named extra_ca; without it this step does nothing.
RUN --mount=type=secret,id=extra_ca,required=false \
    if [ -s /run/secrets/extra_ca ]; then \
      mkdir /tmp/ca && cd /tmp/ca && \
      awk '/BEGIN CERT/{n++} {print > ("ca" n ".pem")}' /run/secrets/extra_ca && \
      for f in ca*.pem; do keytool -importcert -noprompt -cacerts -storepass changeit -alias "extra-$f" -file "$f"; done && \
      rm -rf /tmp/ca; \
    fi
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon -q bootJar

# Run
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
