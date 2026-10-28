import org.jetbrains.kotlin.gradle.dsl.JvmTarget

buildscript {
    // Os plugins rodam no build com acesso total; o travamento do classpath deles
    // (buildscript-gradle.lockfile) deixa o osv-scanner vê-los. Estrito, como o do projeto: sem o
    // arquivo (ou com dependência fora dele) o build falha em vez de seguir sem trava.
    dependencyLocking {
        lockAllConfigurations()
        lockMode = LockMode.STRICT
    }
}

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
        // Grava no bytecode as anotações de uso de tipo (`List<@Valid T>`), que o Hibernate Validator lê
        // para validar em cascata os itens da lista (H15 do M3: `@Valid` no `List` é obsoleto, HV000271).
        freeCompilerArgs.add("-Xemit-jvm-type-annotations")
    }
}

// Entidades JPA precisam de classes abertas e construtor sem argumentos
// (ARCHITECTURE §18). O plugin kotlin-jpa gera o construtor; o allopen abre as classes.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

// Travamento de dependências (M5 E13, decisão D3): o grafo resolvido fica em gradle.lockfile, e o
// osv-scanner da revisão final lê o arquivo, ou seja, o que o build de fato usa. O modo estrito
// falha o build para versão maior que a travada, dependência nova fora do lockfile e configuração
// sem lock state (inclusive na imagem Docker). Versão menor no catálogo, ao contrário, é ignorada
// em favor da travada, sem aviso; por isso regrave os lockfiles no mesmo commit que muda o catálogo
// ou um plugin: gradle.lockfile, buildscript-gradle.lockfile e settings-gradle.lockfile (este só é
// gerado pelo `--write-locks` e versionado para a regeneração não sujar a árvore; hoje não tem
// dependências), com `./gradlew dependencies --write-locks`.
dependencyLocking {
    lockAllConfigurations()
    lockMode = LockMode.STRICT
}

dependencies {
    // BOMs como plataformas nativas do Gradle: todas as versões vêm do version catalog.
    implementation(platform(libs.spring.boot.dependencies))
    implementation(platform(libs.spring.modulith.bom))
    implementation(platform(libs.aws.sdk.bom))

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
    // Trilha de auditoria (ARCHITECTURE §5): o aspecto de `@Audited` (shared) roda dentro da transação.
    implementation(libs.spring.boot.starter.aspectj)
    // Compilação também: o iam lê o nome da restrição violada no PSQLException (409 por unicidade).
    implementation(libs.postgresql)

    // API REST (ARCHITECTURE §7): Spring MVC, validação de DTOs, Spring Security e o springdoc, que gera o contrato api/openapi.yaml e serve o Swagger UI fora de prod.
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.springdoc.openapi.starter.webmvc.ui)
    // Autenticação (ARCHITECTURE §8.1, módulo `iam`): access token JWT (RS256) validado pelo
    // Resource Server e emitido pelo próprio servidor (Nimbus, que vem com ele); senha e PIN em
    // Argon2id pelo Argon2PasswordEncoder, que usa a implementação do Bouncy Castle.
    implementation(libs.spring.boot.starter.security.oauth2.resource.server)
    implementation(libs.bouncycastle.bcprov)
    // O springdoc (swagger-core) ainda usa Jackson 2; com o módulo Kotlin dele, o contrato marca
    // como `required` as propriedades não nulas dos DTOs e como `nullable` as anuláveis.
    runtimeOnly(libs.jackson2.module.kotlin)
    // Anexos (ARCHITECTURE §14, D14, módulo `files`): API S3 (MinIO no compose, S3/R2/B2 na nuvem)
    // com o cliente HTTP síncrono padrão do SDK (Apache 5); o assíncrono (Netty) não é usado.
    implementation(libs.aws.sdk.s3) {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }

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
    testImplementation(libs.testcontainers.minio)
    testImplementation(libs.spring.modulith.docs)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
    // Testes de contrato das integrações (ARCHITECTURE §12): o FCM HTTP v1 do módulo notifications.
    testImplementation(libs.wiremock.standalone)
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
    // O cache do Spring guarda um contexto por combinação de configuração de teste (cada um com o seu
    // pool do Hikari e o seu cliente S3); com os do módulo files, os 512 MB padrão do Gradle acabavam, e com
    // uns 22 contextos de ~55 MB o 1 GB também (OOM intermitente do executor, M3 E05/E06). O cache é limitado a 16
    // contextos em src/test/resources/spring.properties.
    maxHeapSize = "2g"
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
