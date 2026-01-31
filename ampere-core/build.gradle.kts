
import java.io.FileInputStream
import java.util.Properties
import com.vanniktech.maven.publish.SonatypeHost
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.JavadocJar
import org.jetbrains.dokka.gradle.tasks.DokkaGenerateTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization") version "2.1.20"
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.vanniktech.maven.publish")
    id("signing")
    id("org.jetbrains.dokka") version "2.1.0"
    id("app.cash.sqldelight") version "2.2.1"
    id("org.jlleitschuh.gradle.ktlint")
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

    configure(KotlinMultiplatform(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml")))

    coordinates("link.socket", "ampere-core", version.toString())

    pom {
        name.set("Ampere")
        description.set("A Kotlin Multiplatform library for building AI agent systems with built-in observability and transparent cognition.")
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


sqldelight {
    databases {
        create("Database") {
            packageName.set("link.socket.ampere.db")
        }
    }
}

kotlin {
    applyDefaultHierarchyTemplate()

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    js(IR) {
        browser {
            commonWebpackConfig {
                cssSupport {
                    enabled.set(true)
                }
            }
        }
        binaries.executable()
    }

    wasmJs {
        browser {
            commonWebpackConfig {
                cssSupport {
                    enabled.set(true)
                }
            }
        }
        binaries.executable()
    }

    val xcf = XCFramework()
    val iosTargets = listOf(iosX64(), iosArm64(), iosSimulatorArm64())

    iosTargets.forEach {
        it.binaries.framework {
            baseName = "shared"
            xcf.add(this)
        }
    }

    sourceSets {
        all {
            languageSettings.enableLanguageFeature("ExpectActualClasses")
        }

        val commonMain by getting {
            dependencies {
                implementation(kotlin("reflect"))

                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material)
                implementation(compose.components.resources)
                implementation(compose.materialIconsExtended)

                implementation("ai.koog:koog-agents:0.5.4")
                implementation("app.cash.sqldelight:coroutines-extensions:2.2.1")
                implementation("app.cash.sqldelight:runtime:2.2.1")
                implementation("com.aallam.openai:openai-client:4.0.1")
                implementation("com.squareup.okio:okio:3.11.0")
                implementation("com.mikepenz:multiplatform-markdown-renderer:0.33.0")
                implementation("co.touchlab:kermit:2.0.6")
                implementation("io.ktor:ktor-client-core:3.2.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(compose.uiTooling)

                api("androidx.activity:activity-compose:1.11.0")
                api("androidx.appcompat:appcompat:1.7.1")
                api("androidx.core:core-ktx:1.17.0")
                implementation("app.cash.sqldelight:android-driver:2.2.1")
                implementation("com.lordcodes.turtle:turtle:0.10.0")
                implementation("io.ktor:ktor-client-okhttp:3.2.2")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.common)

                implementation("app.cash.sqldelight:sqlite-driver:2.2.1")
                implementation("com.lordcodes.turtle:turtle:0.10.0")
                implementation("io.ktor:ktor-client-okhttp:3.2.2")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("io.mockk:mockk:1.13.14")
                implementation("com.charleskorn.kaml:kaml:0.72.0")
            }
        }
        val jsMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-js:3.2.2")
                implementation("com.squareup.okio:okio-fakefilesystem:3.11.0")
            }
        }
        val wasmJsMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-js:3.2.2")
                implementation("com.squareup.okio:okio-fakefilesystem:3.11.0")
            }
        }
        val iosX64Main by getting
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        val iosMain by getting {
            dependencies {
                implementation("app.cash.sqldelight:native-driver:2.2.1")
                implementation("io.ktor:ktor-client-darwin:3.2.2")
            }
        }
    }

    androidTarget {
        publishLibraryVariants("release", "debug")
        publishLibraryVariantsGroupedByFlavor = true
    }

    // https://kotlinlang.org/docs/native-objc-interop.html#export-of-kdoc-comments-to-generated-objective-c-headers
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget> {
        compilations.get("main").compilerOptions.options.freeCompilerArgs.add("-Xexport-kdoc")
    }
}

android {
    compileSdk = (findProperty("android.compileSdk") as String).toInt()
    namespace = "link.socket.ampere"

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")
    sourceSets["main"].resources.srcDirs("src/commonMain/resources")

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

tasks.register("kotlinConfiguration") {
    val generatedSources = File(layout.buildDirectory.asFile.get(), "generated/kotlin/config")
    generatedSources.mkdirs()
    kotlin.sourceSets.commonMain.get().kotlin.srcDirs(generatedSources)

    val localProperties = Properties().apply {
        load(FileInputStream(File(rootProject.rootDir, "local.properties")))
    }

    val properties = localProperties.entries
        .filter { (key, _) ->
            (key as? String)?.contains(".") == false
        }
        .joinToString("\n") { (key, value) ->
            "\tconst val $key = \"$value\""
        }

    val kotlinConfig = File(generatedSources, "KotlinConfig.kt")
    kotlinConfig.writeText(
        "package link.socket.ampere.core.config\n\nobject KotlinConfig {\n$properties\n}\n",
    )
}

tasks.findByName("build")?.dependsOn(
    tasks.findByName("kotlinConfiguration"),
)

ktlint {
    android.set(true)
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
