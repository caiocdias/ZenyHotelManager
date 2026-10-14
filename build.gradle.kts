import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
}

group = "com.zeny"
version = "0.0.1-SNAPSHOT"
description = "Servidor do Zeny Hotel Manager"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

// Entidades JPA precisam de classes abertas e construtor sem argumentos
// (ARCHITECTURE §18). O plugin kotlin-jpa gera o construtor; o allopen abre as classes.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

dependencies {
    // BOMs como plataformas nativas do Gradle: todas as versões vêm do version catalog.
    implementation(platform(libs.spring.boot.dependencies))
    implementation(platform(libs.spring.modulith.bom))

    implementation(libs.spring.boot.starter)
    implementation(libs.spring.modulith.starter.core)
    implementation(libs.kotlin.reflect)

    // Persistência (ARCHITECTURE §11): JPA para escrita, JdbcClient para leitura, Flyway para
    // migrações e o Event Publication Registry do Modulith em JDBC (outbox, §5).
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.spring.modulith.starter.jdbc)
    // O registro serializa os eventos em JSON; o módulo Kotlin desserializa data classes no reprocessamento.
    implementation(libs.spring.boot.starter.jackson)
    implementation(libs.jackson.module.kotlin)
    // Jobs agendados com trava no banco (ARCHITECTURE §13).
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.provider.jdbc.template)
    runtimeOnly(libs.postgresql)

    // API REST (ARCHITECTURE §7): Spring MVC, validação de DTOs, segurança mínima até o M1
    // e o springdoc, que gera o contrato api/openapi.yaml e serve o Swagger UI fora de prod.
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.springdoc.openapi.starter.webmvc.ui)
    // O springdoc (swagger-core) ainda usa Jackson 2; com o módulo Kotlin dele, o contrato marca
    // como `required` as propriedades não nulas dos DTOs e como `nullable` as anuláveis.
    runtimeOnly(libs.jackson2.module.kotlin)

    // Observabilidade (ARCHITECTURE §16): actuator só com liveness, readiness e prometheus;
    // métricas Micrometer no formato Prometheus; tracing pela ponte OpenTelemetry do Micrometer,
    // que põe `traceId`/`spanId` no MDC dos logs e propaga o `traceparent` (W3C). Nenhum
    // exportador de spans está ligado: o OpenTelemetry fica opcional.
    implementation(libs.spring.boot.starter.actuator)
    runtimeOnly(libs.micrometer.registry.prometheus)
    implementation(libs.spring.boot.micrometer.tracing.opentelemetry)
    implementation(libs.micrometer.tracing.bridge.otel)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.spring.boot.starter.actuator.test)
    testImplementation(libs.spring.boot.micrometer.tracing.test)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.spring.modulith.docs)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)
}

configurations.configureEach {
    // O spring-modulith-moments (vem com o starter core) publica HourHasPassed/DayHasPassed
    // pelo relógio. A data operacional do hotel só avança no fechamento do dia (módulo `hotel`,
    // M1), então esses eventos não têm uso e só acrescentariam uma tarefa agendada sem trava.
    exclude(group = "org.springframework.modulith", module = "spring-modulith-moments")
}

// Gera META-INF/build-info.properties (versão do servidor em GET /api/v1/meta/version). Sem o
// horário do build, para a tarefa não ficar desatualizada a cada execução.
springBoot {
    buildInfo {
        excludes.add("time")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // MockK/ByteBuddy carregam agente dinamicamente; o JDK 25 avisa sem esta flag.
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    // Os testes rodam de propósito num fuso diferente de UTC: assim pegam qualquer código que
    // dependa do fuso da JVM em vez de gravar instantes em UTC (RN-01, ARCHITECTURE §11).
    systemProperty("user.timezone", "America/Sao_Paulo")
}

// Contrato OpenAPI (ARCHITECTURE §7, D6). O teste com a tag `openapi-contract` sobe a aplicação
// (profile `local`, PostgreSQL do Testcontainers) e grava o YAML do springdoc; ele não roda no
// `test`, só aqui. `checkOpenApi` entra no `check` (logo no `build`) e falha se o api/openapi.yaml
// versionado difere do gerado; `updateOpenApi` regrava o arquivo versionado.
val openApiContractTag = "openapi-contract"
val generatedOpenApi = layout.buildDirectory.file("openapi/openapi.yaml")
val committedOpenApi = layout.projectDirectory.file("api/openapi.yaml")

tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags(openApiContractTag) }
}

val generateOpenApi =
    tasks.register<Test>("generateOpenApi") {
        group = "documentation"
        description = "Gera o contrato OpenAPI em build/openapi/openapi.yaml a partir da aplicação."
        testClassesDirs =
            sourceSets.test
                .get()
                .output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnitPlatform { includeTags(openApiContractTag) }
        val output = generatedOpenApi.get().asFile
        systemProperty("zeny.openapi.output", output.absolutePath)
        outputs.file(output)
        doFirst { output.delete() }
    }

val checkOpenApi =
    tasks.register("checkOpenApi") {
        group = "verification"
        description = "Falha se api/openapi.yaml difere do contrato gerado pela aplicação."
        dependsOn(generateOpenApi)
        val generated = generatedOpenApi.get().asFile
        val committed = committedOpenApi.asFile
        inputs.file(generated)
        inputs.files(committed)
        doLast {
            if (!generated.isFile) throw GradleException("O contrato não foi gerado em $generated.")
            val expected = generated.readText()
            val actual = if (committed.isFile) committed.readText().replace("\r\n", "\n") else null
            if (actual != expected) {
                val firstDifference =
                    actual
                        ?.lines()
                        ?.zip(expected.lines())
                        ?.indexOfFirst { (a, b) -> a != b }
                        ?.takeIf { it >= 0 }
                        ?: minOf(actual?.lines()?.size ?: 0, expected.lines().size)
                throw GradleException(
                    "api/openapi.yaml está desatualizado (primeira diferença na linha ${firstDifference + 1}). " +
                        "Rode ./gradlew updateOpenApi e versione o arquivo junto com a mudança da API.",
                )
            }
        }
    }

tasks.register<Copy>("updateOpenApi") {
    group = "documentation"
    description = "Regrava api/openapi.yaml com o contrato gerado pela aplicação."
    dependsOn(generateOpenApi)
    from(generatedOpenApi)
    into(layout.projectDirectory.dir("api"))
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("config/detekt/detekt.yml"))
}

ktlint {
    version = libs.versions.ktlint.cli
}

// O plugin do detekt liga ao `check` só a análise sem resolução de tipos; as tarefas com
// resolução de tipos (detektMain/detektTest) pegam mais problemas e também entram no `check`.
val detektWithTypeResolution = listOf("detektMain", "detektTest")

tasks.named("check") {
    dependsOn(detektWithTypeResolution, checkOpenApi)
}

tasks.register("lint") {
    group = "verification"
    description = "Roda todo o lint do projeto: detekt (com e sem resolução de tipos) e ktlint."
    dependsOn("detekt", detektWithTypeResolution, "ktlintCheck")
}
