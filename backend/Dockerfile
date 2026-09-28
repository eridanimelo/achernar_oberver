# ---------- build ----------
FROM maven:3.9-eclipse-temurin-26 AS build

WORKDIR /build

# Camada de dependências (cacheada enquanto pom.xml não mudar)
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src

# Pule os testes aqui (rode com `mvn verify` fora do Docker);
# para rodar os testes no build: --build-arg SKIP_TESTS=false
ARG SKIP_TESTS=true
RUN mvn -q -DskipTests=${SKIP_TESTS} package

# ---------- runtime ----------
FROM eclipse-temurin:26-jre

# curl = healthcheck contra o actuator
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Nome do jar genérico: sobrevive a bump de versão no pom.xml
COPY --from=build /build/target/*.jar app.jar

# Não rodar como root
RUN addgroup --system app && adduser --system --ingroup app app \
 && chown app:app app.jar
USER app

# Permite tunar a JVM sem rebuildar (ex.: debug remoto via JDWP)
ENV JAVA_OPTS=""

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=5 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar app.jar"]
