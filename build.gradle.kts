plugins {
    `java-library`
    id("org.springframework.boot") version "3.5.15"
    id("io.spring.dependency-management") version "1.1.6"
    id("org.owasp.dependencycheck") version "10.0.4"
}

dependencyCheck {
    failBuildOnCVSS = 7.0f
    nvd {
        apiKey = System.getenv("NVD_API_KEY") ?: ""
    }
    analyzers {
        assemblyEnabled = false
    }
}

group = "kerosene"
version = "PRE-ALPHA"
description = "Kerosene Financial Engine service module"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.5.15")
    }
}

configurations.configureEach {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
}

dependencies {
    api("io.kerosene.contracts:kerosene-contracts:0.2.0-SNAPSHOT")
    implementation("kerosene:kerosene-shared:PRE-ALPHA")

    implementation("io.jsonwebtoken:jjwt-api:0.13.0")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-cbor")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.bitcoinj:bitcoinj-core:0.15.10") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    implementation("org.zeromq:jeromq:0.6.0")
    implementation("org.flywaydb:flyway-core")
    implementation("org.postgresql:postgresql")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    compileOnly("jakarta.annotation:jakarta.annotation-api:2.1.1")
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveClassifier.set("boot")
    mainClass.set("com.kerosene.kfe.bootstrap.KfeServiceApplication")
}

tasks.named<Jar>("jar") {
    enabled = true
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.register<Test>("paymentCancellationPostgresTest") {
    description = "Verify cancellation fencing against a dedicated disposable PostgreSQL database."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("*PaymentCancellationPostgresTest")
    }
    // Each invocation uses a fresh external fixture; never reuse another database's result.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    doFirst {
        require(!System.getenv("KFE_TEST_POSTGRES_URL").isNullOrBlank()) {
            "Run bash scripts/test-payment-cancellation-postgres.sh to create the isolated database."
        }
    }
}

tasks.register<Test>("ledgerPostgresTest") {
    description = "Verify extracted ledger adapters against a disposable PostgreSQL database."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("*LedgerPostgresTest") }
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    doFirst {
        require(!System.getenv("KFE_LEDGER_POSTGRES_URL").isNullOrBlank()) {
            "Set KFE_LEDGER_POSTGRES_URL to a disposable PostgreSQL JDBC URL."
        }
    }
}
