@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
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
                implementation(project(":shared"))

                // CLI argument parsing
                implementation("com.github.ajalt.clikt:clikt:4.4.0")

                // Terminal rendering with colors and styles
                implementation("com.github.ajalt.mordant:mordant:2.7.2")

                // Coroutines
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

                // SQLDelight driver for JVM
                implementation("app.cash.sqldelight:sqlite-driver:2.2.1")

                // Serialization
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

                // DateTime
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.junit.jupiter:junit-jupiter:5.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
