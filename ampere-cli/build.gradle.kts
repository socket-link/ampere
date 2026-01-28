@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.gradle.jvm.application.tasks.CreateStartScripts
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("maven-publish")
    id("signing")
}

val ampereVersion: String by project

group = "link.socket"
version = ampereVersion

// === PUBLISHING CONFIGURATION ===
publishing {
    publications.withType<MavenPublication>().configureEach {
        groupId = "link.socket"
        artifactId = when (name) {
            "kotlinMultiplatform" -> "ampere-cli"
            else -> "ampere-cli-${name.lowercase()}"
        }

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

    repositories {
        mavenLocal()
        maven {
            name = "ossrh"
            url = if (version.toString().endsWith("-SNAPSHOT")) {
                uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
            } else {
                uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            }
            credentials {
                username = findProperty("ossrhUsername")?.toString() ?: System.getenv("OSSRH_USERNAME") ?: ""
                password = findProperty("ossrhPassword")?.toString() ?: System.getenv("OSSRH_PASSWORD") ?: ""
            }
        }
    }
}

// === SIGNING CONFIGURATION ===
signing {
    val signingKeyId = findProperty("signing.keyId")?.toString() ?: System.getenv("SIGNING_KEY_ID")
    val signingKey = findProperty("signing.key")?.toString() ?: System.getenv("SIGNING_KEY")
    val signingPassword = findProperty("signing.password")?.toString() ?: System.getenv("SIGNING_PASSWORD")

    if (signingKey != null && signingPassword != null) {
        // CI: Use in-memory key
        if (signingKeyId != null) {
            useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
        } else {
            useInMemoryPgpKeys(signingKey, signingPassword)
        }
    } else {
        // Local: Use GPG agent
        useGpgCmd()
    }

    // Only require signing when publishing to OSSRH
    setRequired {
        gradle.taskGraph.allTasks.any { it.name.contains("publishAllPublicationsToOssrhRepository") }
    }

    sign(publishing.publications)
}

// Ensure proper task ordering
tasks.withType<Sign>().configureEach {
    dependsOn(tasks.withType<Jar>())
}

kotlin {
    jvmToolchain(21)

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }

        binaries {
            executable {
                mainClass.set("link.socket.ampere.MainKt")
            }
        }
    }

    sourceSets {
        val jvmMain by getting {
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

// Configure JVM arguments for the start scripts to suppress JNA warnings
tasks.named<CreateStartScripts>("startScriptsForJvm") {
    defaultJvmOpts = listOf("--enable-native-access=ALL-UNNAMED")
}
