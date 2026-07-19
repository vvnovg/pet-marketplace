import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    id("org.springframework.boot") version "4.0.4"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.petmarketplace"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(26)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // OpenAPI / Swagger
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")

    // Database migrations and driver
    implementation("org.springframework.boot:spring-boot-starter-liquibase")
    runtimeOnly("org.postgresql:postgresql")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    // MapStruct
    implementation("org.mapstruct:mapstruct:1.6.3")
    annotationProcessor("org.mapstruct:mapstruct-processor:1.6.3")
    testAnnotationProcessor("org.mapstruct:mapstruct-processor:1.6.3")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter")
}

dependencyManagement {
    imports {
        mavenBom(SpringBootPlugin.BOM_COORDINATES)
        mavenBom("org.testcontainers:testcontainers-bom:1.21.0")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Docker Desktop's engine rejects docker-java's default API version (1.32, below the engine
    // minimum 1.40), so Testcontainers cannot ping the daemon and all @Testcontainers tests are
    // skipped. docker-java only honors the CONFIG_KEYS set of names — for the API version that key
    // is literally "api.version" (NOT DOCKER_API_VERSION). Set it as a system property on the
    // forked test JVM so the shaded DefaultDockerClientConfig picks it up. The engine socket
    // itself is configured in ~/.testcontainers.properties (docker.host), kept out of the repo
    // because it is machine-specific. Override per-run with -Dapi.version=1.45 on the gradle CLI.
    systemProperty("api.version", "1.45")
    // The raw Docker Desktop engine socket (configured via docker.host in
    // ~/.testcontainers.properties) cannot be bind-mounted into the Ryuk reaper container
    // ("operation not supported"), so Ryuk fails to start and every test is skipped. Disable it;
    // containers started by @Container are still stopped by their own lifecycle, and any orphan
    // on a JVM crash can be cleaned with `docker rm`. Override per-run with the same env var.
    environment("TESTCONTAINERS_RYUK_DISABLED", "true")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
    options.compilerArgs.add("-Amapstruct.defaultComponentModel=spring")
}

// A second, parallel way to run the SAME integration tests — against an already-running external
// stand (its Postgres/Redis/app) instead of Testcontainers. The stand must be up first, e.g.:
//   docker-compose up -d && gradle bootRun
// then:
//   gradle testOnStand
// The `tests.mode=stand` system property flips IntegrationTestBase into stand mode: it skips
// Testcontainers, overlays the `stand` profile (stand DB/Redis/JWT secret/tests.base-url), points
// the RestClient at the stand, and runs stand/seed.sql + stand/cleanup.sql around each test class.
//
// Defaults target a local docker-compose + bootRun stand. For a remote stand, override via env
// vars (read by application-stand.yml): STAND_BASE_URL, STAND_DB_URL, STAND_DB_USER,
// STAND_DB_PASSWORD, STAND_REDIS_HOST, STAND_REDIS_PORT, STAND_JWT_SECRET. STAND_JWT_SECRET must
// match the stand's security.jwt.secret so tokens minted in-JVM validate on the stand.
//
// Inherits useJUnitPlatform() + the api.version/ryuk settings from the tasks.withType<Test> block
// above (the api.version/ryuk props are harmless here — no containers are started in stand mode).
val testOnStand = tasks.register<Test>("testOnStand") {
    group = "verification"
    description = "Runs integration tests against an external stand (already running) instead of Testcontainers."
    systemProperty("tests.mode", "stand")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    shouldRunAfter(tasks.test)
}
