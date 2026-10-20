import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jlleitschuh.gradle.ktlint")
    kotlin("plugin.compose")
}

val phosphorVersion: String by project

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":ampere-core"))
                implementation(project(":ampere-compose"))
                implementation(project(":ampere-cli"))
                // The on-device session's event store (AMPR-469): `createJvmDriver` returns this driver.
                implementation("app.cash.sqldelight:sqlite-driver:2.2.1")
                implementation("link.socket:phosphor-core:$phosphorVersion")
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "link.socket.ampere.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            // What `suggestRuntimeModules` names. The packaged runtime is jlinked down to these, and
            // without `java.sql` the on-device session's event store cannot open (AMPR-477).
            modules("java.instrument", "java.management", "java.sql", "jdk.unsupported")
            packageName = "Ampere"
            packageVersion = "1.0.0"
        }
    }
}

// === macOS on-device helper (AMPR-469) ===
// The app reaches Apple's on-device model through `ampere-helper`, the Swift process the CLI uses
// (see ampere-cli/macos-helper/README.md). It looks for the binary in its Compose resources
// directory, which Compose names in `compose.application.resources.dir` for `run` and for every
// packaged format. `prepareAppResources` is the one task that puts it there, so `swift build` runs
// for that alone, up-to-date-checked, and never for `assemble`. A host that is not a Mac, or a Mac
// that could not build Swift, has no binary to copy, and the app runs without an on-device model.
val isMacHost = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
val macHelper = rootProject.layout.projectDirectory.file("ampere-cli/macos-helper/.build/release/ampere-helper")

if (isMacHost) {
    tasks.withType<Sync>().matching { it.name == "prepareAppResources" }.configureEach {
        dependsOn(":ampere-cli:buildMacHelper")
        from(macHelper)
    }

    // The packaged formats copy the app resources with Kotlin's `File.copyTo`, which drops the
    // execute bit, and on a Mac every format is built from this app image. Without this the
    // packaged app finds a helper it cannot run, and reports it missing.
    tasks.matching { it.name == "createDistributable" }.configureEach {
        doLast {
            outputs.files.asFileTree
                .matching { include("**/${macHelper.asFile.name}") }
                .forEach { it.setExecutable(true, false) }
        }
    }
}
