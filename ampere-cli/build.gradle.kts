
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    kotlin("plugin.compose")
    id("com.vanniktech.maven.publish")
    id("signing")
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

    configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty()))

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
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        mainRun {
            mainClass.set("link.socket.ampere.MainKt")
        }
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        binaries {
            executable {
                mainClass.set("link.socket.ampere.MainKt")
                applicationName.set("ampere")
            }
        }
    }

    jvmToolchain(21)

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":ampere-core"))
                implementation(project(":ampere-animation"))

                // CLI argument parsing
                implementation("com.github.ajalt.clikt:clikt:4.4.0")

                // Terminal rendering with colors and styles (kept for command output)
                implementation("com.github.ajalt.mordant:mordant:2.7.2")

                // Mosaic - Compose-based terminal UI
                implementation("com.jakewharton.mosaic:mosaic-runtime:0.18.0")

                // REPL terminal handling
                implementation("org.jline:jline:3.25.0")
                implementation("org.jline:jline-terminal-jna:3.25.0")
                implementation("net.java.dev.jna:jna:5.14.0")

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
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.junit.jupiter:junit-jupiter:5.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
                implementation("com.github.ajalt.clikt:clikt:4.4.0")
            }
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}

// Wire installDist to installJvmDist so the familiar command works
tasks.named("installDist") {
    dependsOn("installJvmDist")
}
