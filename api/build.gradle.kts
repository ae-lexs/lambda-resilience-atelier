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
    implementation("org.springframework.boot:spring-boot-starter-web:3.4.1") {
        // Lambda has no servlet container of its own and the aws-serverless-java-container
        // library bridges Spring MVC directly to the Lambda runtime — Tomcat would be dead
        // weight and bloat the cold start.
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    }
    implementation("org.springframework.boot:spring-boot-starter-undertow:3.4.1")
    implementation("com.amazonaws.serverless:aws-serverless-java-container-springboot3:2.1.5")
}

tasks.shadowJar {
    archiveBaseName.set("api")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()

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
