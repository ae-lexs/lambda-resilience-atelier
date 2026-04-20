plugins {
    java
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("io.github.aelexs.infra.InfraApp")
}

dependencies {
    // CDK CLI (`aws-cdk` on npm, pinned in docker/cdk.Dockerfile) and the Java library
    // (`aws-cdk-lib` on Maven Central) use independent version schemes. Wire compat is
    // governed by the cloud assembly schema, which is stable across both current versions.
    implementation("software.amazon.awscdk:aws-cdk-lib:2.250.0")
    implementation("software.constructs:constructs:10.6.0")
}

tasks.named<JavaExec>("run") {
    // CDK invokes this task via `cdk.json` from the `cdk/` directory, then reads
    // `cdk.out/` relative to that same directory. Pinning workingDir here keeps the
    // asset paths in stacks (e.g. `../api/build/libs/api.jar`) resolvable regardless
    // of where Gradle itself was invoked from.
    workingDir = projectDir
    standardInput = System.`in`
}
