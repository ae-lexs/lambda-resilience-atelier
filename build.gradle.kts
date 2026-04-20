// Root build configuration shared by every subproject. Plugins and per-module
// dependencies live in the subproject build scripts (`api/build.gradle.kts`,
// `cdk/build.gradle.kts`).

allprojects {
    group = "io.github.aelexs"
    version = "1.0.0"

    repositories {
        mavenCentral()
    }
}
