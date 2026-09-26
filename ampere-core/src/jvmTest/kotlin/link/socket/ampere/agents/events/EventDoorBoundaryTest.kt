package link.socket.ampere.agents.events

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Boundary test for the one event door (AMPR-340, F-register rows F1 and F4).
 *
 * `EventSerialBus.publish` and `publishAsync` are `internal`, which the compiler enforces
 * across modules. Inside `ampere-core` the compiler cannot help, so this test walks every
 * *main* source set of every module in the repository and fails on any direct bus publish
 * outside `AgentEventApi.kt` — the only file allowed to put an event on the bus, because it
 * is the only one that persists the event first.
 *
 * Test source sets are deliberately not scanned: an `ampere-core` test may drive a bus
 * directly, and tests in other modules cannot (the visibility does that).
 *
 * jvmTest because it walks the source tree with `java.io.File`.
 */
class EventDoorBoundaryTest {

    @Test
    fun `the scan pattern matches a direct bus publish`() {
        val samples = listOf(
            "eventSerialBus.publish(event)",
            "bus.publish(",
            "        eventBus?.publishAsync(",
            "handle.bus.publish(x)",
        )
        samples.forEach { assertTrue(BUS_PUBLISH.containsMatchIn(it), "expected a match: $it") }
        assertTrue(!BUS_PUBLISH.containsMatchIn("api.publish(event)"), "the door itself must not match")
    }

    @Test
    fun `the door file is scanned and does publish on the bus`() {
        val door = File(repoRoot(), DOOR_FILE)
        assertTrue(door.isFile, "door file moved? $DOOR_FILE")
        assertTrue(
            door.readLines().any { BUS_PUBLISH.containsMatchIn(it) },
            "AgentEventApi.kt no longer publishes on the bus; the scan would pass vacuously",
        )
    }

    @Test
    fun `no main source outside AgentEventApi publishes on a bus`() {
        val root = repoRoot()
        val offenders = mainSourceSets(root)
            .flatMap { sourceSet -> sourceSet.walkTopDown().filter { it.isFile && it.extension == "kt" } }
            .filterNot { it.relativeTo(root).invariantSeparatorsPath == DOOR_FILE }
            .flatMap { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                file.readLines().mapIndexedNotNull { index, line ->
                    if (BUS_PUBLISH.containsMatchIn(line)) "$relative:${index + 1}: ${line.trim()}" else null
                }
            }
            .sorted()

        assertTrue(
            offenders.isEmpty(),
            "Events enter the system only through AgentEventApi.publish, which persists them " +
                "before the bus sees them. Route these through an AgentEventApi " +
                "(EnvironmentService.createEventApi) instead of publishing on the bus:\n" +
                offenders.joinToString("\n"),
        )
    }

    /** Every `<module>/src/<sourceSet>` directory whose source set name is not a test one. */
    private fun mainSourceSets(root: File): List<File> =
        root.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull { module -> File(module, "src").takeIf { it.isDirectory } }
            .flatMap { src -> src.listFiles().orEmpty().filter { it.isDirectory } }
            .filterNot { it.name.endsWith("Test") }
            .sortedBy { it.path }

    /**
     * Gradle runs jvmTest with the module directory as the working directory. Walk up from
     * there to the repository root so the test also works when launched from the root or an
     * IDE with a different working directory.
     */
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, "ampere-core").isDirectory) return dir
            dir = dir.parentFile
        }
        error("Could not locate the repository root from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val DOOR_FILE =
            "ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/events/api/AgentEventApi.kt"

        /** `<anything>bus.publish(` or `<anything>Bus.publishAsync(`, with or without `?.`. */
        val BUS_PUBLISH = Regex("""\b\w*[bB]us\??\.(publish|publishAsync)\(""")
    }
}
