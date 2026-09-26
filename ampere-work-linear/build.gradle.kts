import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.vanniktech.maven.publish")
    id("org.jlleitschuh.gradle.ktlint")
}

val ampereVersion: String by project

group = "link.socket"
version = ampereVersion

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty()))

    coordinates("link.socket", "ampere-work-linear", version.toString())

    pom {
        name.set("Ampere Work Source — Linear over MCP")
        description.set(
            "The first operational Chassis SPI adapter: a Plug binding Transport.MCP to " +
                "Linear as a work source. PerceiveSource for the over-approximating " +
                "ready-queue and issue reads, ExecuteSink for transitions, labels and " +
                "comments, and the comment-arbitrated claim-by-write-then-verify protocol " +
                "for a provider with no conditional-write surface.",
        )
        url.set("https://github.com/socket-link/ampere")
        inceptionYear.set("2026")

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
    // JVM only, deliberately. The supervisor that drives a work source runs on a
    // developer machine or in CI (AMPR-305: "JVM target is the operative platform
    // for v1"), and every extra target here is compile time every contributor pays
    // for a wire no mobile build can reach. The code is in `commonMain` rather than
    // `jvmMain` so adding a target later is a build-file change, not a move.
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    jvmToolchain(21)

    sourceSets {
        val commonMain by getting {
            dependencies {
                // The adapter is built on ampere-core's chassis SPI, canon and MCP
                // client — this module depends on ampere-core, never the reverse.
                api(project(":ampere-core"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            }
        }
        val commonTest by getting {
            dependencies {
                // kotlin-test / kotlin-test-junit come transitively via
                // ampere-core-test-fixtures's `api` deps, named as explicit GAV
                // coordinates there rather than the `kotlin("test")` alias — the alias
                // can resolve to a conflicting JUnit5 variant alongside test-fixtures'
                // JUnit4 one. Same reasoning as ampere-bindings-apple; this module has
                // no `useJUnitPlatform()` for that reason.
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
                implementation(project(":ampere-core-test-fixtures"))
            }
        }
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
