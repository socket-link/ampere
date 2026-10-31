package link.socket.ampere.agents.execution.dispatch

import java.io.File
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import link.socket.ampere.agents.execution.process.CancellationAddress
import link.socket.ampere.agents.execution.process.GroupedProcess
import link.socket.ampere.agents.execution.process.ProcessGroups

/**
 * Graceful shutdown under a real signal (AMPR-308, task 5's validation): SIGTERM
 * during an active dispatch leaves no live process group and a clean-shutdown marker.
 *
 * Not a unit test by nature, for the same reason as `DispatchJournalKillTest`: the
 * signal has to reach a separate JVM, and the shutdown hook has to do its work while
 * that JVM is exiting. `SwitchboardTest` covers the same path in-process, where the
 * individual writes can be asserted.
 */
class SwitchboardShutdownProcessTest {

    private val posix = File.separatorChar == '/'
    private val spawned = mutableListOf<CancellationAddress>()
    private var directory: Path? = null

    @AfterTest
    fun tearDown() {
        spawned.forEach { ProcessGroups.terminate(it, grace = 200.milliseconds) }
        directory?.toFile()?.deleteRecursively()
    }

    @Test
    fun `SIGTERM during an active dispatch leaves no live process group and a clean-shutdown marker`() {
        if (!posix) return
        val dir = Files.createTempDirectory("ampere-switchboard-sigterm").toRealPath().also { directory = it }
        val supervisor = spawnSupervisor(dir)
        val journal = dir.resolve("journal").resolve("${SwitchboardProcess.INSTANCE}.${DispatchJournal.EXTENSION}")
        val log = dir.resolve("logs").resolve(SwitchboardProcess.INSTANCE).resolve("ampr-1.log")

        // Wait until the agent is actually running — past the gate, writing output — so
        // the signal lands in an active dispatch rather than before one.
        waitUntil(90.seconds, "the agent to start (supervisor log: ${dir.resolve("supervisor.log")})") {
            Files.isRegularFile(log) && "started" in Files.readString(log)
        }
        val agent = requireNotNull(read(journal).entries.last { it.agentAddress != null }.agentAddress)
        spawned += agent
        assertTrue(ProcessGroups.isAlive(agent))

        signal("TERM", supervisor.address.leaderPid)
        waitUntil(90.seconds, "the supervisor to exit") { !supervisor.process.isAlive }

        assertFalse(ProcessGroups.isAlive(agent), "SIGTERM must leave no live agent process group")
        val read = read(journal)
        assertTrue(read.cleanShutdown, "the graceful path must end with the clean-shutdown marker")
        assertTrue(read.quarantined.isEmpty())
        assertEquals(DispatchPhase.ESCALATED, read.entries.last().phase, "the interrupted run is handed to a person")
        assertEquals("AMPR-1 ESCALATED", Files.readString(dir.resolve("settled.txt")).trim())
    }

    private fun read(journal: Path): JournalRead =
        DispatchJournal.parse(
            SwitchboardProcess.INSTANCE,
            String(Files.readAllBytes(journal), StandardCharsets.UTF_8).lines(),
        )

    /** Starts [SwitchboardProcess] in its own process group, with this JVM's classpath. */
    private fun spawnSupervisor(dir: Path): GroupedProcess {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val builder = ProcessBuilder(
            java,
            "-Xmx192m",
            "-XX:TieredStopAtLevel=1",
            "-cp",
            testClasspath(),
            SwitchboardProcess::class.java.name,
            dir.toString(),
        )
            .redirectErrorStream(true)
            .redirectOutput(dir.resolve("supervisor.log").toFile())
        return ProcessGroups.start(builder).also { spawned += it.address }
    }

    /** As in `DispatchJournalKillTest`: a Gradle worker's classpath lives in its class loaders. */
    private fun testClasspath(): String {
        val fromLoaders = generateSequence(javaClass.classLoader) { it.parent }
            .filterIsInstance<URLClassLoader>()
            .flatMap { it.getURLs().asSequence() }
            .mapNotNull { runCatching { File(it.toURI()).path }.getOrNull() }
        val fromProperty = System.getProperty("java.class.path").orEmpty()
            .split(File.pathSeparator)
            .filter { it.isNotEmpty() }
        val entries = (fromLoaders + fromProperty).distinct().toList()
        check(entries.isNotEmpty()) { "could not determine the test classpath for the supervisor process" }
        return entries.joinToString(File.pathSeparator)
    }

    private fun signal(name: String, pid: Long) {
        val kill = ProcessBuilder("kill", "-s", name, pid.toString())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        assertEquals(0, kill.waitFor(), "kill -$name $pid failed")
    }

    private fun waitUntil(timeout: Duration, what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (!runCatching(condition).getOrDefault(false)) {
            check(System.nanoTime() < deadline) { "timed out after $timeout waiting for $what" }
            Thread.sleep(50)
        }
    }
}
