# ── build ──────────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies change far less often than sources: resolve them in their own
# layer so a code-only change doesn't re-download the world.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package \
 && mv target/ArmenianAlias-*.jar /build/app.jar

# ── run ────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

# Don't run the game as root.
RUN useradd --create-home --shell /usr/sbin/nologin alias
USER alias

COPY --from=build --chown=alias:alias /build/app.jar app.jar

# Free tiers are memory-tight; let the JVM size itself from the container limit.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC"

EXPOSE 8080
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
