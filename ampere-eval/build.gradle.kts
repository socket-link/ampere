import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("app.cash.sqldelight")
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

    coordinates("link.socket", "ampere-eval", version.toString())

    pom {
        name.set("Ampere Eval")
        description.set(
            "Measurement substrate for AMPERE: capturable, partially-replayable " +
                "Trace of an EventSerialBus run stream, persisted via SQLDelight.",
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

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    jvmToolchain(21)

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":ampere-core"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
                implementation("app.cash.sqldelight:runtime:2.2.1")
                implementation("com.squareup.okio:okio:3.11.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("app.cash.sqldelight:sqlite-driver:2.2.1")
                // MutableClock (AMPR-335). The fixtures module pins the JUnit4 kotlin-test
                // artifact for its inheritable contract suites; this module runs on the JUnit
                // Platform, so drop it rather than put two kotlin-test frameworks on the path.
                implementation(project(":ampere-core-test-fixtures")) {
                    exclude(group = "org.jetbrains.kotlin", module = "kotlin-test-junit")
                }
                // AMPR-422: the live goal-met run binds the hosted System One adapter over a Ktor
                // client (a mock engine in its offline tests) and meters the chat transport under
                // the model-backed adapter. Test classpath only; commonMain gains no dependency.
                implementation("io.ktor:ktor-client-core:3.2.2")
                implementation("io.ktor:ktor-client-mock:3.2.2")
                implementation("com.aallam.openai:openai-client:4.0.1")
            }
        }
    }
}

sqldelight {
    databases {
        create("EvalDatabase") {
            packageName.set("link.socket.ampere.eval.db")
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}

// region — AMPR-187: the Ampere-first eval suite
//
// Both tasks below are `Test` tasks over the jvmTest classpath rather than `JavaExec` over a new
// source set. The suite needs a JDBC driver for the event store and the trace store, and jvmTest is
// the only configuration that already has one — putting it on jvmMain instead would ship a JDBC
// dependency in the published `ampere-eval` POM for the sake of two developer commands.

// Read the compilation, not the `jvmTest` task: deriving a classpath from `tasks.named<Test>
// ("jvmTest").map { it.classpath }` makes that task the provider's producer, so Gradle runs the
// whole jvmTest suite before either task below — which is exactly the duplicated work a narrow
// gate exists to avoid.
val jvmTestCompilation = kotlin.jvm().compilations.getByName("test")

/**
 * The per-commit regression gate (task 5.4), as a named command.
 *
 * Runs only the Replay suite, so CI has a step whose failure means "an Arc's behavior changed"
 * rather than "something in ampere-eval broke". `upToDateWhen { false }` because a gate that can
 * report UP-TO-DATE or FROM-CACHE is not a gate — Gradle would happily skip it on a rerun of an
 * unchanged tree, which is exactly the run a flaky regression hides in.
 */
val evalReplay by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs the Ampere eval suite in Replay mode against its committed golden traces."

    testClassesDirs = jvmTestCompilation.output.classesDirs
    classpath = jvmTestCompilation.output.allOutputs + jvmTestCompilation.runtimeDependencyFiles
    useJUnitPlatform()

    filter {
        includeTestsMatching("link.socket.ampere.eval.suite.AmpereEvalSuiteTest")
        // AMPR-225: the Rung 0 routing bench, graded against its own golden traces.
        includeTestsMatching("link.socket.ampere.eval.suite.Rung0RoutingSuiteTest")
    }

    outputs.upToDateWhen { false }
}

/**
 * Re-records every probe's golden trace (task 5.5).
 *
 * Writes into the *source* resources directory, not the build one, so the result is something to
 * review and commit. `GoldenTraceRecorderTest` does nothing unless this property is set, which is
 * what keeps a plain `jvmTest` run from overwriting the traces it is checking against.
 */
val recordGoldenTraces by tasks.registering(Test::class) {
    group = "verification"
    description = "Re-records the Ampere eval suite's golden traces from a Live Bench run."

    testClassesDirs = jvmTestCompilation.output.classesDirs
    classpath = jvmTestCompilation.output.allOutputs + jvmTestCompilation.runtimeDependencyFiles
    useJUnitPlatform()

    filter {
        includeTestsMatching("link.socket.ampere.eval.suite.GoldenTraceRecorderTest")
    }

    systemProperty(
        "ampere.eval.goldenDir",
        layout.projectDirectory.dir("src/jvmTest/resources/golden").asFile.absolutePath,
    )

    // Recording is the point of the task; there is no such thing as an up-to-date re-record.
    outputs.upToDateWhen { false }

    testLogging {
        showStandardStreams = true
    }
}

// endregion

// region — AMPR-422: the goal-met decision bench

val decideResourcesDir = layout.projectDirectory.dir("src/jvmTest/resources/decide")

/**
 * Regenerates the constructed goal-met corpus into the source resources directory. Deterministic:
 * an empty `git diff` afterwards is the proof. `GoalMetCorpusGeneratorTest` does nothing unless
 * this property is set, so a plain `jvmTest` never rewrites the corpus it validates.
 */
val generateGoalMetCorpus by tasks.registering(Test::class) {
    group = "verification"
    description = "Regenerates ampere-eval/src/jvmTest/resources/decide/goal-met/cases.json from its generator."

    testClassesDirs = jvmTestCompilation.output.classesDirs
    classpath = jvmTestCompilation.output.allOutputs + jvmTestCompilation.runtimeDependencyFiles
    useJUnitPlatform()

    filter {
        includeTestsMatching("link.socket.ampere.eval.decide.GoalMetCorpusGeneratorTest")
    }

    systemProperty("ampere.eval.decideDir", decideResourcesDir.asFile.absolutePath)
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
}

/**
 * The live goal-met run: every hosted decision arm over the committed corpus, recorded to
 * `decide/goal-met/recorded/<arm>.jsonl` for offline replay. Never runs in CI; `DecideBenchLiveTest`
 * does nothing unless this task set the directory property. Credentials come from the
 * environment (`OPENROUTER_API_KEY`, `CLOUDFLARE_ACCOUNT_ID`, `CLOUDFLARE_AUTH_TOKEN`) and from the
 * generative provider's key as the bundled client reads it; an arm with no credential is skipped
 * and named. Running this task is the approval for calls to those endpoints.
 *
 * `-PdecideDryRun=true` prints the request for one case and sends nothing. `-PdecideArms=a,b`,
 * `-PdecideLimit=n` and `-PdecideRepeats=n` narrow a run; `-PdecideParallelism=n` sets calls in flight.
 */
val decideBenchLive by tasks.registering(Test::class) {
    group = "verification"
    description = "Records the live goal-met decision arms (network; credentials from the environment)."

    testClassesDirs = jvmTestCompilation.output.classesDirs
    classpath = jvmTestCompilation.output.allOutputs + jvmTestCompilation.runtimeDependencyFiles
    useJUnitPlatform()

    filter {
        includeTestsMatching("link.socket.ampere.eval.decide.DecideBenchLiveTest")
    }

    systemProperty("ampere.eval.decideDir", decideResourcesDir.asFile.absolutePath)
    listOf(
        "decideDryRun" to "ampere.eval.decide.dryRun",
        "decideArms" to "ampere.eval.decide.arms",
        "decideLimit" to "ampere.eval.decide.limit",
        "decideRepeats" to "ampere.eval.decide.repeats",
        "decideParallelism" to "ampere.eval.decide.parallelism",
    ).forEach { (gradleProperty, systemPropertyName) ->
        providers.gradleProperty(gradleProperty).orNull?.let { systemProperty(systemPropertyName, it) }
    }

    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
}

// endregion

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
