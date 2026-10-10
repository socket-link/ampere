package link.socket.ampere.propel

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Task 6 of AMPR-393: `propel/` stays hostable on every target.
 *
 * A hosted run is the consumer-facing entry into the PROPEL loop, and the consumer's
 * Web target cannot host one until three things are true of this package: it is
 * `commonMain` only, it declares no `expect`/`actual`, and it never calls
 * `runBlockingCompat`. The last is the load-bearing one — `runBlockingCompat` throws
 * outright on JS and wasm rather than degrading, so one call anywhere on the run's
 * path makes the whole entry point unusable there. The agent's own `runLLMTo*` lambdas
 * are exactly that call, which is why the run drives
 * [AgentReasoning][link.socket.ampere.agents.domain.reasoning.AgentReasoning]'s
 * suspending functions instead.
 *
 * A source scan rather than a compile check, for the reason
 * [link.socket.ampere.agents.events.EventDoorBoundaryTest] is one: nothing in the type
 * system says "do not block here", and the JS/wasm targets this protects are not
 * compiled by the JVM gate that most changes run. jvmTest because it walks the tree
 * with `java.io.File`.
 */
class PropelPackagePurityTest {

    @Test
    fun `the scanned package exists and is not empty`() {
        val sources = propelSources()
        assertTrue(
            sources.isNotEmpty(),
            "no sources under $PROPEL_PACKAGE — did the package move? Every check below " +
                "would pass vacuously.",
        )
        assertTrue(
            sources.any { it.name == "RunHost.kt" },
            "RunHost.kt is the entry point this package exists for; found " +
                "${sources.map { it.name }}",
        )
    }

    @Test
    fun `the scan patterns match what they are meant to catch`() {
        listOf(
            "    runBlockingCompat(ioDispatcher) {",
            "return runBlockingCompat {",
            "x = runBlockingCompat(d) { 1 }",
        ).forEach { assertTrue(BLOCKING_CALL.containsMatchIn(it), "expected a match: $it") }
        assertTrue(
            !BLOCKING_CALL.containsMatchIn("// no runBlockingCompat in this package"),
            "a mention in prose is not a call",
        )
        listOf("expect fun foo()", "internal expect class Bar", "actual fun foo() = 1")
            .forEach { assertTrue(PLATFORM_DECLARATION.containsMatchIn(it), "expected a match: $it") }
        assertTrue(
            !PLATFORM_DECLARATION.containsMatchIn("val expected = 1"),
            "`expected` is not `expect`",
        )
    }

    @Test
    fun `no source in propel blocks a thread`() {
        val offenders = offendersMatching(BLOCKING_CALL)
        assertTrue(
            offenders.isEmpty(),
            "A hosted run is suspend-only: the caller owns the scope, and " +
                "`runBlockingCompat` throws on JS and wasm, which is where the embedding " +
                "consumer needs to host. Reach the phase services through " +
                "`SparkBasedAgent.reasoningUnit` (suspending) rather than through the " +
                "agent's `runLLMTo*` lambdas:\n" + offenders.joinToString("\n"),
        )
    }

    @Test
    fun `no source in propel declares expect or actual`() {
        val offenders = offendersMatching(PLATFORM_DECLARATION)
        assertTrue(
            offenders.isEmpty(),
            "`propel/` is common code on every target. An `expect` here would need an " +
                "`actual` per target, and the target that would be left out is the one " +
                "this package exists to reach:\n" + offenders.joinToString("\n"),
        )
    }

    @Test
    fun `propel ships only from commonMain`() {
        val root = repoRoot()
        val elsewhere = File(root, "ampere-core/src").listFiles().orEmpty()
            .filter { it.isDirectory && it.name != "commonMain" }
            // Test source sets are deliberately not scanned, for the reason
            // `EventDoorBoundaryTest` gives: this package's own tests live in `jvmTest`,
            // and the invariant is about what ships, not about what checks it.
            .filterNot { it.name.endsWith("Test") }
            .map { File(it, PROPEL_RELATIVE) }
            .filter { it.isDirectory }
            .map { it.relativeTo(root).invariantSeparatorsPath }

        assertTrue(
            elsewhere.isEmpty(),
            "a platform source set holds part of the run, so the run is not the same run " +
                "everywhere: $elsewhere",
        )
    }

    private fun offendersMatching(pattern: Regex): List<String> {
        val root = repoRoot()
        return propelSources().flatMap { file ->
            val relative = file.relativeTo(root).invariantSeparatorsPath
            file.readLines().mapIndexedNotNull { index, line ->
                if (pattern.containsMatchIn(line)) "$relative:${index + 1}: ${line.trim()}" else null
            }
        }.sorted()
    }

    private fun propelSources(): List<File> =
        File(repoRoot(), PROPEL_PACKAGE)
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .toList()

    /** See [link.socket.ampere.agents.events.EventDoorBoundaryTest] for why this walks up. */
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, "ampere-core").isDirectory) return dir
            dir = dir.parentFile
        }
        error("Could not locate the repository root from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val PROPEL_RELATIVE = "kotlin/link/socket/ampere/propel"
        const val PROPEL_PACKAGE = "ampere-core/src/commonMain/$PROPEL_RELATIVE"

        /** A `runBlockingCompat(` call, not a mention of the name in prose. */
        val BLOCKING_CALL = Regex("""(^|[^\w*/])runBlockingCompat\s*[({]""")

        /** An `expect` or `actual` declaration at the start of a declaration. */
        val PLATFORM_DECLARATION = Regex("""(^|\s)(expect|actual)\s+(fun|val|var|class|object|interface)\b""")
    }
}
