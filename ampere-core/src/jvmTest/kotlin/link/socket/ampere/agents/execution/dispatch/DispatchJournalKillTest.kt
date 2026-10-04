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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.execution.process.CancellationAddress
import link.socket.ampere.agents.execution.process.GroupedProcess
import link.socket.ampere.agents.execution.process.ProcessGroups

/**
 * The crash half of the journal's contract (AMPR-307 task 2): a supervisor killed
 * with no chance to clean up still leaves a journal a restarted one can read.
 *
 * This is the failure the whole mechanism exists for. AMPR-291's fate table found
 * that a SIGKILLed supervisor leaves claims nothing will clear and agent subprocesses
 * nothing can enumerate; the journal is only worth having if it survives exactly that
 * signal, at exactly the worst moment.
 *
 * Not a unit test by nature — it spawns a real JVM, kills its process group, and reads
 * what is on the disk afterwards. `DispatchJournalTest` covers the reader's quarantine
 * behaviour against hand-built torn fixtures; this test covers the writer's claim that
 * a torn line cannot arise from a kill in the first place.
 */
class DispatchJournalKillTest {

    private val posix = File.separatorChar == '/'
    private val spawned = mutableListOf<CancellationAddress>()
    private var directory: Path? = null

    @AfterTest
    fun tearDown() {
        spawned.forEach { ProcessGroups.terminate(it, grace = 200.milliseconds) }
        directory?.toFile()?.deleteRecursively()
    }

    @Test
    fun `a writer SIGKILLed mid-append leaves a journal with no torn record`() = runBlocking<Unit> {
        if (!posix) return@runBlocking
        val dir = Files.createTempDirectory("ampere-journal-kill").also { directory = it }
        val writer = spawnWriter(dir)
        val pgid = assertNotNull(writer.address.processGroupId, "the writer must lead its own group")

        // Every append rewrites the whole journal and fsyncs it, and with this payload
        // the file is megabytes by the time the kill arrives — so the writer spends
        // nearly all of its life inside that write and the signal lands there.
        // Waiting for a run of records first also rules out killing it before it wrote
        // anything. If the kill does land between two appends the assertions below
        // still hold; the test is weaker that run, never flaky.
        waitUntil(90.seconds) { lineCount(dir) >= RECORDS_BEFORE_KILL }
        sigkill(pgid)
        waitUntil(10.seconds) { !ProcessGroups.isAlive(writer.address) }

        // A staged write left behind is the evidence that the signal landed inside one.
        // Reported rather than asserted: the writer is inside `writeAtomically` for most
        // but not all of its life, so requiring it here would make this test flaky — and
        // the invariant below holds either way.
        val stagedLeftBehind = dir.toFile().list().orEmpty().filter { it.endsWith(".tmp") }
        val interleaving = if (stagedLeftBehind.isEmpty()) {
            "between appends"
        } else {
            "mid-append (staged writes left: $stagedLeftBehind)"
        }

        // Read it the way a restarted supervisor does: a new instance, scanning the
        // directory. Nothing re-opens the dead instance's own journal, so the staged
        // writes it left behind are still there for the scan to ignore.
        val reads = DispatchJournal.open(dir, "recovery-instance").scanAll()
        val killed = reads.single { it.instanceId == WRITER_INSTANCE }
        println("SIGKILL landed $interleaving; ${killed.entries.size} records survived")

        assertTrue(
            killed.quarantined.isEmpty(),
            "a SIGKILL landing $interleaving must not leave a line a reader cannot parse, but found: " +
                killed.quarantined.joinToString { "line ${it.lineNumber} (${it.reason})" },
        )
        assertTrue(
            killed.entries.size >= RECORDS_BEFORE_KILL,
            "expected at least $RECORDS_BEFORE_KILL records, read ${killed.entries.size}",
        )
        assertEquals(
            List(killed.entries.size) { "AMPR-$it" },
            killed.entries.map { it.ticketId },
            "the surviving journal must be a prefix of what the writer appended: the rename " +
                "either happened or it did not, so no record can be partly there",
        )
        assertFalse(killed.cleanShutdown, "the writer was killed before it could mark one")
        assertEquals(
            killed.entries.size,
            killed.suspectDispatches().size,
            "every dispatch the killed writer recorded is still CLAIMED, so every one is suspect",
        )
    }

    /** Starts [JournalWriterProcess] in its own process group, with this JVM's classpath. */
    private fun spawnWriter(dir: Path): GroupedProcess {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val builder = ProcessBuilder(
            java,
            "-Xmx96m",
            "-XX:TieredStopAtLevel=1",
            "-cp",
            testClasspath(),
            JournalWriterProcess::class.java.name,
            dir.toString(),
            WRITER_INSTANCE,
            PAYLOAD_BYTES.toString(),
        )
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            // Inherited, so a writer that fails to start says why in the test log.
            .redirectError(ProcessBuilder.Redirect.INHERIT)
        return ProcessGroups.start(builder).also { spawned += it.address }
    }

    /**
     * This JVM's test classpath, for the writer to run against.
     *
     * `java.class.path` alone is not enough: a Gradle test worker boots with only its
     * own jar there and loads the test classpath into a separate `URLClassLoader`. Both
     * sources are combined so this works under Gradle and under a plain IDE run.
     */
    private fun testClasspath(): String {
        val fromLoaders = generateSequence(javaClass.classLoader) { it.parent }
            .filterIsInstance<URLClassLoader>()
            .flatMap { it.getURLs().asSequence() }
            .mapNotNull { runCatching { File(it.toURI()).path }.getOrNull() }
        val fromProperty = System.getProperty("java.class.path").orEmpty()
            .split(File.pathSeparator)
            .filter { it.isNotEmpty() }
        val entries = (fromLoaders + fromProperty).distinct().toList()
        check(entries.isNotEmpty()) { "could not determine the test classpath for the writer process" }
        return entries.joinToString(File.pathSeparator)
    }

    private fun sigkill(pgid: Long) {
        val kill = ProcessBuilder("kill", "-s", "KILL", "--", "-$pgid")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        assertEquals(0, kill.waitFor(), "kill -KILL on the writer's process group failed")
    }

    /**
     * Lines currently in the writer's journal.
     *
     * Safe to call while the writer runs: a read either sees the file before the
     * rename or after it, and both are whole.
     */
    private fun lineCount(dir: Path): Int {
        val file = dir.resolve("$WRITER_INSTANCE.${DispatchJournal.EXTENSION}")
        if (!Files.isRegularFile(file)) return 0
        return runCatching {
            String(Files.readAllBytes(file), StandardCharsets.UTF_8).count { it == '\n' }
        }.getOrDefault(0)
    }

    private fun waitUntil(timeout: Duration, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met within $timeout" }
            Thread.sleep(25)
        }
    }

    private companion object {
        const val WRITER_INSTANCE = "killed-supervisor"

        /** Padding per record, so one whole-file rewrite is long enough to be interrupted. */
        const val PAYLOAD_BYTES = 65_536

        const val RECORDS_BEFORE_KILL = 25
    }
}
