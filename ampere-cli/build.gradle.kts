
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.vanniktech.maven.publish")
    id("signing")
    application
}

val ampereVersion: String by project

group = "link.socket"
version = ampereVersion

// === SIGNING CONFIGURATION ===
signing {
    useGpgCmd()
}

// === PUBLISHING CONFIGURATION ===
mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    configure(KotlinJvm(javadocJar = JavadocJar.Empty()))

    coordinates("link.socket", "ampere-cli", version.toString())

    pom {
        name.set("Ampere CLI")
        description.set("Command-line interface for Ampere, a Kotlin Multiplatform library for building AI agent systems.")
        url.set("https://github.com/socket-link/ampere")
        inceptionYear.set("2024")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("socket-link")
                name.set("Socket Link")
                url.set("https://github.com/socket-link")
            }
        }

        scm {
            connection.set("scm:git:git://github.com/socket-link/ampere.git")
            developerConnection.set("scm:git:ssh://git@github.com:socket-link/ampere.git")
            url.set("https://github.com/socket-link/ampere")
        }

        issueManagement {
            system.set("GitHub Issues")
            url.set("https://github.com/socket-link/ampere/issues")
        }
    }
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("link.socket.ampere.MainKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    implementation(project(":ampere-core"))

    // CLI argument parsing
    implementation("com.github.ajalt.clikt:clikt:4.4.0")

    // Terminal rendering with colors and styles
    implementation("com.github.ajalt.mordant:mordant:2.7.2")

    // REPL terminal handling
    implementation("org.jline:jline:3.25.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // SQLDelight driver for JVM
    implementation("app.cash.sqldelight:sqlite-driver:2.2.1")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // YAML parsing for config files
    implementation("com.charleskorn.kaml:kaml:0.72.0")

    // DateTime
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")

    // SLF4J no-op implementation to suppress warnings
    implementation("org.slf4j:slf4j-nop:2.0.16")

    // Test dependencies
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.github.ajalt.clikt:clikt:4.4.0")
}

tasks.test {
    useJUnitPlatform()
}
