package link.socket.ampere.agents.execution

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * AMPR-420: [ToolExecutionEngine] is the only way a tool call is dispatched.
 *
 * Two classes once stood beside it as a second route to a tool: a standalone single-tool
 * wrapper that bracketed a call in the same `ToolExecutionStarted` / `ToolExecutionCompleted`
 * pair, and a selector that matched a tool to an executor. Neither had a production caller,
 * and both were deleted. A second route is a second place a call can skip what the engine does
 * for every one: the permission gate in front of the dispatch, the outcome written to episodic
 * memory (AMPR-406), and the one pair per dispatch that `ArcTraceProjection` joins into a
 * run's tool calls (AMPR-389). A wrapper that published its own pair around a tool the engine
 * also dispatched would show one tool run as two calls.
 *
 * This is the grep that settled the deletion, kept as a test so the answer stays settled: no
 * text file under any module's `src/` may name either class. Test source sets are scanned as
 * well as main ones, because two of the last three references were tests.
 *
 * The names are assembled from halves, so this file does not contain them. That is why the
 * scan needs no exemption for itself, and why a plain case-insensitive `grep -r` over every
 * `src/` comes back empty, which is what the gate says. Matching ignores case because one
 * reference survived the case-sensitive grep: a lowercase variable in a KDoc example.
 *
 * A source scan rather than a compile check, for the reason
 * [link.socket.ampere.agents.events.EventDoorBoundaryTest] and
 * [link.socket.ampere.propel.PropelPackagePurityTest] are ones: a class that is never
 * constructed compiles forever, and nothing in the type system says "do not add a second
 * route". jvmTest because it walks the tree with `java.io.File`.
 */
class RetiredToolDispatchPathsTest {

    @Test
    fun `the scan reaches main and test sources`() {
        val root = repoRoot()
        val scanned = scannedFiles(root).map { it.relativeTo(root).invariantSeparatorsPath }

        assertTrue(
            ENGINE_FILE in scanned,
            "$ENGINE_FILE is not among the ${scanned.size} files scanned; did it move? Every " +
                "check below would pass vacuously.",
        )
        assertTrue(
            THIS_FILE in scanned,
            "$THIS_FILE is not among the ${scanned.size} files scanned: test source sets are " +
                "meant to be covered, and this file is the one that proves they are.",
        )
    }

    @Test
    fun `the scan pattern matches what it is meant to catch`() {
        RETIRED_NAMES.forEach { name ->
            val lowercaseFirst = name.replaceFirstChar { it.lowercase() }
            listOf(
                "val x = $name(tool)",
                "import link.socket.ampere.agents.execution.$name",
                "$lowercaseFirst.invoke(request)",
                "class Fancy${name}Impl",
                " * [$name][link.socket.ampere.agents.execution.$name] emits",
            ).forEach { assertTrue(RETIRED_PATTERN.containsMatchIn(it), "expected a match: $it") }
        }

        listOf(
            "ToolExecutionEngine(llmService, executor, executorId)",
            "FunctionExecutor.create()",
            "ToolInvocation",
            "val executorId = id",
            "// the tool's executor is chosen once",
        ).forEach { assertTrue(!RETIRED_PATTERN.containsMatchIn(it), "not a retired name: $it") }
    }

    @Test
    fun `no source names a retired dispatch path`() {
        val root = repoRoot()
        val offenders = scannedFiles(root)
            .flatMap { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                file.readLines().mapIndexedNotNull { index, line ->
                    if (RETIRED_PATTERN.containsMatchIn(line)) "$relative:${index + 1}: ${line.trim()}" else null
                }
            }
            .sorted()

        assertTrue(
            offenders.isEmpty(),
            "A retired tool-dispatch path is named in source. ToolExecutionEngine is the only " +
                "path a tool call takes: it runs the permission gate, writes the outcome to " +
                "episodic memory and publishes the one Started/Completed pair per dispatch " +
                "that the trace joins (AMPR-420 deleted the others). Point the reference at the " +
                "engine; if reintroducing a second route is deliberate, change this gate in " +
                "the same PR and say why:\n" + offenders.joinToString("\n"),
        )
    }

    /** Every text file under `<module>/src/`, whatever the source set. */
    private fun scannedFiles(root: File): List<File> =
        root.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull { module -> File(module, "src").takeIf { it.isDirectory } }
            .flatMap { src -> src.walkTopDown().filter { it.isFile && isText(it) }.toList() }
            .sortedBy { it.path }

    /** The way `grep -I` decides: a NUL byte in the first block means binary (a `.png`, a `.db`). */
    private fun isText(file: File): Boolean =
        file.inputStream().use { stream ->
            val head = ByteArray(BINARY_SNIFF_BYTES)
            val read = stream.read(head)
            (0 until maxOf(read, 0)).none { head[it] == 0.toByte() }
        }

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
        const val BINARY_SNIFF_BYTES = 8000

        const val ENGINE_FILE =
            "ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/execution/ToolExecutionEngine.kt"
        const val THIS_FILE =
            "ampere-core/src/jvmTest/kotlin/link/socket/ampere/agents/execution/RetiredToolDispatchPathsTest.kt"

        /** Built from halves; see the class KDoc for why this file must not spell them out. */
        val RETIRED_NAMES = listOf("Tool" + "Invoker", "Executor" + "Selector")

        val RETIRED_PATTERN = Regex(
            RETIRED_NAMES.joinToString("|") { Regex.escape(it) },
            RegexOption.IGNORE_CASE,
        )
    }
}
