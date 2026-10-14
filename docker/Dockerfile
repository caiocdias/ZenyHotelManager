# syntax=docker/dockerfile:1

# Imagem do servidor Zeny (RNF-01, ARCHITECTURE §15.1). O contexto do build é a raiz do
# repositório (o .dockerignore de lá só deixa entrar o necessário para compilar):
#
#   docker build -f docker/Dockerfile -t zeny-hotel-manager .
#
# Estágio de build com o JDK 25; runtime com o JRE 25 em Alpine, nas camadas do Spring Boot
# (dependências mudam pouco, o jar da aplicação muda a cada build), rodando como usuário
# não-root e com HEALTHCHECK na readiness. A imagem não define profile nem segredos: o
# profile (`SPRING_PROFILES_ACTIVE`), o banco (`ZENY_DB_*`) e as chaves de cifragem
# (`ZENY_ENCRYPTION_*`) vêm do ambiente, como no docker/compose.yaml.

ARG JDK_IMAGE=eclipse-temurin:25-jdk-noble
ARG JRE_IMAGE=eclipse-temurin:25-jre-alpine

FROM ${JDK_IMAGE} AS build
WORKDIR /workspace
ENV GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.welcome=never -Dkotlin.compiler.execution.strategy=in-process"

# Wrapper e scripts do build primeiro: a distribuição do Gradle e as dependências ficam no
# cache do BuildKit (/root/.gradle) e só são baixadas de novo quando o catálogo muda.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --quiet --version

# Só o código de produção: testes, lint e o contrato rodam no ./gradlew build (e na CI).
COPY src/main src/main
RUN --mount=type=cache,target=/root/.gradle ./gradlew --quiet bootJar \
    && cp "$(find build/libs -name '*.jar' ! -name '*-plain.jar')" app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --destination build/extracted

FROM ${JRE_IMAGE}
LABEL org.opencontainers.image.title="zeny-hotel-manager" \
      org.opencontainers.image.description="Servidor do Zeny Hotel Manager" \
      org.opencontainers.image.source="https://github.com/caiocdias/ZenyHotelManager"

# Usuário sem privilégios e sem shell de login, com id fixo (útil para runAsNonRoot e volumes).
RUN addgroup -S -g 10001 zeny \
    && adduser -S -D -H -u 10001 -G zeny -s /sbin/nologin zeny

WORKDIR /app
# Os arquivos ficam do root e só leitura para o processo; o Tomcat escreve só em /tmp.
COPY --from=build /workspace/build/extracted/dependencies/ ./
COPY --from=build /workspace/build/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/build/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/build/extracted/application/ ./

USER 10001:10001
EXPOSE 8080

# Readiness: UP só com a aplicação pronta e o banco respondendo (management.endpoint.health.group.readiness).
HEALTHCHECK --interval=15s --timeout=10s --start-period=120s --start-interval=3s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/readiness || exit 1

# Opções fixas da JVM no ENTRYPOINT (e não em JAVA_TOOL_OPTIONS), para a JVM não escrever
# "Picked up ..." fora do JSON dos logs. Para mudar, sobrescreva o entrypoint.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
