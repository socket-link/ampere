@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
    kotlin("multiplatform")
    id("com.android.library")
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

    coordinates("link.socket", "ampere-core-test-fixtures", version.toString())

    pom {
        name.set("Ampere Core Test Fixtures")
        description.set(
            "Shared CanonAdapter test fixtures: FakeNativeStore plus an inheritable " +
                "Readable/WritableCanonAdapterContract test suite.",
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
    applyDefaultHierarchyTemplate()

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        publishLibraryVariants("release", "debug")
        publishLibraryVariantsGroupedByFlavor = true
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    // ampere-core also targets js/wasmJs, and its commonTest depends on this
    // module — every ampere-core target needs a matching variant here for
    // that dependency to resolve.
    js(IR) {
        browser()
    }

    wasmJs {
        browser()
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    jvmToolchain(21)

    sourceSets {
        val commonMain by getting {
            dependencies {
                // `api` — consumers extend CanonAdapterContract, so they need
                // ampere-core's adapter/canon types and kotlin-test/coroutines-test
                // (used by the inherited @Test methods) on their own classpath.
                api(project(":ampere-core"))
                // `kotlin("test")` only substitutes the per-target test
                // artifact for a *test* source set; a main source set needs
                // the common `kotlin-test` artifact named explicitly.
                api("org.jetbrains.kotlin:kotlin-test:${findProperty("kotlin.version")}")
                api("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            }
        }
        val jvmMain by getting {
            dependencies {
                // The bare `kotlin-test` jvm variant has no `@Test` annotation —
                // that comes from the JUnit4 actual-provider artifact.
                api("org.jetbrains.kotlin:kotlin-test-junit:${findProperty("kotlin.version")}")
            }
        }
        val androidMain by getting {
            dependencies {
                api("org.jetbrains.kotlin:kotlin-test-junit:${findProperty("kotlin.version")}")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }
    }
}

android {
    compileSdk = (findProperty("android.compileSdk") as String).toInt()
    namespace = "link.socket.ampere.core.testfixtures"

    defaultConfig {
        minSdk = (findProperty("android.minSdk") as String).toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        jvmToolchain(21)
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
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
