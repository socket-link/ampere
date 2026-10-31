package link.socket.ampere.agents.execution.verify

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The production manifest-v1 parser (AMPR-309, task 1): both committed manifests
 * parse, and every way a manifest can break schema v1 is refused with the field it is
 * about named.
 */
class VerifyManifestParserTest {

    private fun parse(text: String): VerifyManifest = VerifyManifestParser.parse(text).getOrThrow()

    private fun violations(text: String): List<ManifestViolation> {
        val failure = VerifyManifestParser.parse(text).exceptionOrNull()
        return assertIs<ManifestInvalid>(failure, "expected the manifest to be refused").violations
    }

    private fun assertViolation(text: String, field: String, containing: String) {
        val found = violations(text)
        assertTrue(
            found.any { it.field == field && containing in it.message },
            "expected a violation at `$field` mentioning \"$containing\", got:\n${found.joinToString("\n")}",
        )
    }

    @Test
    fun `this repository's committed manifest parses`() {
        val manifest = parse(repositoryRoot().resolve(VerifyManifest.PATH).readText())

        assertEquals(1, manifest.version)
        assertEquals(listOf("jdk-21", "local-properties"), manifest.requires.map { it.id })
        assertEquals(listOf("simulator", "xcode"), manifest.optional.map { it.id })
        assertEquals(19, manifest.gates.size)
        assertEquals(manifest.gates.size, manifest.gates.map { it.id }.toSet().size)

        val core = manifest.gates.single { it.id == "jvm-test-ampere-core" }
        assertEquals(listOf(GateStep(id = null, run = "./gradlew :ampere-core:jvmTest")), core.steps)
        assertTrue(core.fresh)
        assertEquals(20.minutes, core.timeout)
        assertEquals(SideEffects.BUILD_DIR, core.sideEffects)
        assertEquals(Baseline.CI, core.baseline)
        assertEquals(listOf("jdk-21", "local-properties"), core.requires)

        val bridge = manifest.gates.single { it.id == "ios-swift-bridge" }
        assertTrue(bridge.sequenced)
        assertEquals(listOf("link-framework", "build-for-testing", "test-without-building"), bridge.steps.map { it.id })
        assertEquals(listOf("simulator", "xcode"), bridge.needs)
        assertEquals(listOf("SIMULATOR_UDID"), GateCommand.environmentReferences(bridge.steps.last().run))

        assertEquals(Baseline.NONE, manifest.gates.single { it.id == "core-neutrality" }.baseline)
        assertEquals(Baseline.RELEASE, manifest.gates.single { it.id == "metadata-compile-ampere-core" }.baseline)
        assertIs<PreconditionPredicate.Simulator>(manifest.preconditions.getValue("simulator").predicate)
    }

    /**
     * Repo B's manifest has the two constructs this repository's does not: an empty
     * `optional: []` and a gate whose one task carries five `-x` exclusions, folded
     * over several lines. That manifest belongs to another repository and is not
     * copied here; this fixture has its shape.
     */
    @Test
    fun `a manifest shaped like repo B's — empty optional, -x exclusions, folded run — parses`() {
        val manifest = parse(
            """
            version: 1
            env:
              requires:
                - id: jdk-21
                  toolchain: "21"
                  reason: Gradle toolchains resolve 21.
                - id: local-properties
                  file: local.properties
              optional: []
            gates:
              - id: ktlint
                run: >-
                  ./gradlew ktlintCheck
                  -x :common:runKtlintCheckOverAndroidUnitTestDebugSourceSet
                  -x :common:runKtlintCheckOverJvmTestSourceSet
                fresh: false
                timeout_minutes: 30
                retry_safe: true
                side_effects: build-dir
                parallel_safe: false
                requires: [jdk-21, local-properties]
                baseline: ci
              - id: common-jvm-test
                run: ./gradlew :common:jvmTest
                fresh: true
                timeout_minutes: 30
                retry_safe: true
                side_effects: build-dir
                parallel_safe: false
                requires: [jdk-21, local-properties]
                baseline: ci
            """.trimIndent(),
        )

        assertTrue(manifest.optional.isEmpty())
        val ktlint = manifest.gates.first()
        assertEquals(listOf("ktlintCheck"), GateCommand.gradleTasks(GateCommand.argv(ktlint.steps.single().run)))
    }

    /**
     * The real repo B manifest, and any other, when a developer points at it:
     * `AMPERE_EXTRA_VERIFY_MANIFESTS=/path/a.yml:/path/b.yml`. Not set on CI, where
     * this checks nothing — the fixture above carries the shape.
     */
    @Test
    fun `manifests named in AMPERE_EXTRA_VERIFY_MANIFESTS parse`() {
        val paths = System.getenv("AMPERE_EXTRA_VERIFY_MANIFESTS")?.split(File.pathSeparatorChar).orEmpty()
        paths.filter { it.isNotBlank() }.forEach { path ->
            val result = VerifyManifestParser.parse(File(path).readText())
            assertTrue(result.isSuccess, "$path: ${result.exceptionOrNull()?.message}")
            println("parsed $path: ${result.getOrThrow().gates.size} gates")
        }
    }

    @Test
    fun `a bundled multi-task gate is refused, naming the gate and its tasks`() {
        assertViolation(
            manifest(gate("common-tests", run = "./gradlew :common:jvmTest :common:testDebugUnitTest")),
            field = "gates[0] (common-tests).run",
            containing = "names 2 Gradle tasks (:common:jvmTest :common:testDebugUnitTest)",
        )
    }

    @Test
    fun `a bundled step is refused too`() {
        val text = manifest(
            """
              - id: bridge
                steps:
                  - id: one
                    run: ./gradlew :a:link
                  - id: two
                    run: xcodebuild clean test -scheme x
                fresh: false
                timeout_minutes: 5
                retry_safe: true
                side_effects: none
                parallel_safe: false
                baseline: ci
            """,
        )
        assertViolation(text, field = "gates[0] (bridge).steps[1].run", containing = "2 xcodebuild actions")
    }

    @Test
    fun `an unknown field is refused, naming it`() {
        assertViolation(
            manifest(gate("lint", extra = "fersh: true")),
            field = "gates[0] (lint).fersh",
            containing = "unknown field",
        )
        assertViolation(manifest(gate("lint")) + "\nstages: []\n", field = "stages", containing = "unknown field")
        assertViolation(
            manifest(gate("lint")).replace("toolchain: \"21\"", "toolchain: \"21\"\n      vendor: temurin"),
            field = "env.requires[0].vendor",
            containing = "unknown field",
        )
    }

    @Test
    fun `a version this runner does not implement is refused rather than read optimistically`() {
        assertViolation(manifest(gate("lint")).replace("version: 1", "version: 2"), "version", "not a schema version")
    }

    @Test
    fun `every missing required field is reported at once`() {
        val found = violations(
            manifest(
                """
                  - id: lint
                    run: ./gradlew ktlintCheck
                """,
            ),
        ).map { it.field }

        listOf("timeout_minutes", "retry_safe", "side_effects", "parallel_safe", "baseline").forEach { key ->
            assertTrue("gates[0] (lint).$key" in found, "missing $key in $found")
        }
    }

    @Test
    fun `references must resolve to a precondition of the right kind`() {
        assertViolation(
            manifest(gate("lint", requires = "[jdk-21, android-sdk]")),
            "gates[0] (lint).requires[1]",
            "not a declared precondition",
        )
        assertViolation(
            manifest(gate("lint", requires = "[simulator]")),
            "gates[0] (lint).requires[0]",
            "not under env.requires",
        )
        assertViolation(
            manifest(gate("lint", extra = "needs: [jdk-21]")),
            "gates[0] (lint).needs[0]",
            "not under env.optional",
        )
    }

    @Test
    fun `run and steps are exclusive, ids unique, and a sequence has at least two steps`() {
        assertViolation(manifest(gate("lint", extra = "steps: []")), "gates[0] (lint)", "exactly one of run, steps")
        assertViolation(manifest(gate("lint") + gate("lint")), "gates[1] (lint).id", "not unique")
        assertViolation(
            manifest(
                """
                  - id: single
                    steps:
                      - id: only
                        run: ./gradlew :a:test
                    timeout_minutes: 5
                    retry_safe: true
                    side_effects: none
                    parallel_safe: false
                    baseline: ci
                """,
            ),
            "gates[0] (single).steps",
            "at least two steps",
        )
    }

    @Test
    fun `nothing may mask an exit code or interpolate beyond env`() {
        assertViolation(manifest(gate("lint", run = "./gradlew ktlintCheck || true")), "gates[0] (lint).run", "`||`")
        assertViolation(manifest(gate("lint", run = "./gradlew ktlintCheck | tee out")), "gates[0] (lint).run", "`|`")
        assertViolation(manifest(gate("lint", run = "./gradlew ktlintCheck; echo done")), "gates[0] (lint).run", "`;`")
        assertViolation(manifest(gate("lint", run = "./gradlew -p \${HOME} check")), "gates[0] (lint).run", "\${HOME}")
        // Quoted, an operator is a literal argument, not a pipeline.
        parse(manifest(gate("lint", run = "./gradlew check --tests '*a|b*'")))
    }

    @Test
    fun `booleans are true or false and enums are the schema's spellings`() {
        assertViolation(manifest(gate("lint", extra = "fresh: yes")), "gates[0] (lint).fresh", "not true or false")
        assertViolation(
            manifest(gate("lint").replace("side_effects: build-dir", "side_effects: disk")),
            "gates[0] (lint).side_effects",
            "`disk` is not one of none, build-dir, device, network",
        )
        assertViolation(
            manifest(gate("lint").replace("baseline: ci", "baseline: nightly")),
            "gates[0] (lint).baseline",
            "`nightly`",
        )
        assertViolation(
            manifest(gate("lint").replace("timeout_minutes: 10", "timeout_minutes: 0")),
            "gates[0] (lint).timeout_minutes",
            "positive",
        )
    }

    @Test
    fun `a precondition has exactly one predicate`() {
        assertViolation(
            manifest(gate("lint")).replace("toolchain: \"21\"", "toolchain: \"21\"\n      file: x"),
            "env.requires[0]",
            "exactly one predicate",
        )
    }

    @Test
    fun `a document that is not YAML is refused as a whole`() {
        val found = violations("version: 1\ngates: [unclosed")
        assertEquals("(file)", found.single().field)
    }

    @Test
    fun `fresh defaults to false and needs to nothing`() {
        val gate = parse(manifest(gate("lint"))).gates.single()
        assertEquals(false, gate.fresh)
        assertTrue(gate.needs.isEmpty())
        assertNull(gate.steps.single().id)
    }

    // -----------------------------------------------------------------

    private fun manifest(gates: String): String = buildString {
        appendLine("version: 1")
        appendLine("env:")
        appendLine("  requires:")
        appendLine("    - id: jdk-21")
        appendLine("      toolchain: \"21\"")
        appendLine("  optional:")
        appendLine("    - id: simulator")
        appendLine("      simulator: \"iPhone *\"")
        appendLine("gates:")
        append(gates.trimIndent().prependIndent("  "))
        appendLine()
    }

    private fun gate(
        id: String,
        run: String = "./gradlew ktlintCheck",
        requires: String = "[jdk-21]",
        extra: String? = null,
    ): String = buildString {
        appendLine("- id: $id")
        appendLine("  run: \"${run.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        appendLine("  timeout_minutes: 10")
        appendLine("  retry_safe: true")
        appendLine("  side_effects: build-dir")
        appendLine("  parallel_safe: false")
        appendLine("  requires: $requires")
        appendLine("  baseline: ci")
        extra?.let { appendLine("  $it") }
    }

    private fun repositoryRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, VerifyManifest.PATH).isFile) return dir
            dir = dir.parentFile
        }
        error("Could not locate the repository root from ${System.getProperty("user.dir")}")
    }
}
