import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

// Rung ZERO on Android (AMPR-508): ML Kit's Prompt API, which runs Gemini Nano through
// AICore, bound to ampere-core's `LocalInferenceEngine`. A sibling of ampere-core rather
// than a source set inside it, because `expect`/`actual` cannot cross modules and
// ampere-core stays Android-SDK-free at minSdk 24. Not published yet: the shipping engine,
// and the publish.yml lines that go with a `mavenPublishing` block, are Phase 2.
plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("org.jlleitschuh.gradle.ktlint")
}

val ampereVersion: String by project

group = "link.socket"
version = ampereVersion

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    jvmToolchain(21)

    sourceSets {
        val androidMain by getting {
            dependencies {
                // The engine implements ampere-core's contract; ampere-core never depends on this.
                api(project(":ampere-core"))
                // beta4, not the beta2 AMPR-226 checked: beta2 has no system-instruction field,
                // and beta5 is built against Kotlin 2.4 while this repo compiles with 2.3.
                implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
                implementation("junit:junit:4.13.2")
            }
        }
    }
}

android {
    compileSdk = (findProperty("android.compileSdk") as String).toInt()
    namespace = "link.socket.ampere.relay.local.android"

    defaultConfig {
        // ML Kit's GenAI APIs need 26; the rest of the repo stays at android.minSdk (24).
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

ktlint {
    verbose.set(true)
    outputToConsole.set(true)
    debug.set(true)

    version.set("0.49.1")

    additionalEditorconfig.set(
        mapOf(
            "ktlint_code_style" to "intellij_idea",
        ),
    )

    filter {
        exclude { element -> element.file.path.contains("build/") }
        exclude { element -> element.file.path.contains("generated/") }
    }

    reporters {
        reporter(ReporterType.PLAIN)
        reporter(ReporterType.CHECKSTYLE)
    }
}
