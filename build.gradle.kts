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

    testImplementation(libs.spring.boot.starter.test)
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

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // MockK/ByteBuddy carregam agente dinamicamente; o JDK 25 avisa sem esta flag.
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    // Os testes rodam de propósito num fuso diferente de UTC: assim pegam qualquer código que
    // dependa do fuso da JVM em vez de gravar instantes em UTC (RN-01, ARCHITECTURE §11).
    systemProperty("user.timezone", "America/Sao_Paulo")
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
    dependsOn(detektWithTypeResolution)
}

tasks.register("lint") {
    group = "verification"
    description = "Roda todo o lint do projeto: detekt (com e sem resolução de tipos) e ktlint."
    dependsOn("detekt", detektWithTypeResolution, "ktlintCheck")
}
