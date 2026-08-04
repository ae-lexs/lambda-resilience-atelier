import com.github.jengelman.gradle.plugins.shadow.transformers.PropertiesFileTransformer

plugins {
    java
    id("com.gradleup.shadow") version "8.3.0"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    // Canonical dependencies for aws-serverless-java-container-springboot3 2.1.x when
    // using `SpringBootLambdaContainerHandler.getAwsProxyHandler(...)` — matches the
    // `pet-store` sample at
    // https://github.com/aws/serverless-java-container/tree/main/samples/springboot3/pet-store.
    //
    // Tomcat is excluded because aws-serverless-java-container replaces the embedded
    // servlet container at runtime. Do not add an alternative servlet container
    // (Undertow / Jetty) — the library registers its own WebServerFactory.
    implementation("org.springframework.boot:spring-boot-starter-web:3.5.0") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    }
    implementation("com.amazonaws.serverless:aws-serverless-java-container-springboot3:2.1.5")

    // CRaC API — Coordinated Restore at Checkpoint
    implementation("org.crac:crac:1.5.0")

    // JDBC + HikariCP (HikariCP is bundled with spring-boot-starter-jdbc).
    // Version pinned to match `spring-boot-starter-web:3.5.0` above. The
    // project does not use the `io.spring.dependency-management` plugin or
    // a Spring Boot BOM, so every Spring Boot starter needs an explicit
    // version on its declaration.
    implementation("org.springframework.boot:spring-boot-starter-jdbc:3.5.0")

    // PostgreSQL JDBC driver
    runtimeOnly("org.postgresql:postgresql:42.7.4")

    // AWS SDK v2 — RDS module includes RdsUtilities for IAM auth token generation.
    // Use 2.x 'rds' artifact (not the deprecated v1 sdk-java).
    implementation("software.amazon.awssdk:rds:2.28.0")

    // resilience4j — circuit breaker at the connection-pool boundary
    // (BreakerDbController). The core module only, used programmatically:
    // the `resilience4j-spring-boot3` starter would pull in Actuator and
    // Spring AOP to support @CircuitBreaker annotations, adding beans and
    // proxying to a cold start this atelier spends its time measuring, in
    // exchange for annotation sugar over a single call site. One
    // explicitly-constructed breaker is also easier to read as a codelab
    // exhibit than an annotation whose configuration lives in a YAML file.
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.2.0")
}

tasks.shadowJar {
    archiveBaseName.set("api")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()

    // CRITICAL for Spring Boot + aws-serverless-java-container. Shadow's default merge
    // for META-INF/spring.factories is last-one-wins, which silently drops entries from
    // every dependency except the last — breaking Spring Boot's web-environment
    // detection and producing a cryptic ClassCastException at
    // SpringBootLambdaContainerHandler.initialize:200 (class ... cannot be cast to
    // AnnotationConfigServletWebServerApplicationContext). Using PropertiesFileTransformer
    // with "append" merge strategy preserves all entries.
    //
    // See: https://github.com/GradleUp/shadow/issues/899
    // See: https://github.com/aws/serverless-java-container/issues/904
    transform(PropertiesFileTransformer::class.java) {
        paths = listOf("META-INF/spring.factories")
        mergeStrategy = "append"
    }

    // Spring Boot autoconfig files use a last-one-wins merge by default in Shadow, which
    // silently discards entries when multiple dependencies ship the same path. Every
    // `META-INF/spring/*.imports` file Spring Boot reads MUST be appended explicitly here
    // — missing an append produces a JAR that loads but fails to autoconfigure at runtime
    // with no useful error. Revisit this list when bumping Spring Boot. See ADR Decision 2.
    append("META-INF/spring.handlers")
    append("META-INF/spring.schemas")
    append("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
    append("META-INF/spring/org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration.imports")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
