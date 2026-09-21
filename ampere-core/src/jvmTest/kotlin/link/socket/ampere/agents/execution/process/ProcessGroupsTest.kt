package link.socket.ampere.agents.execution.process

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

class ProcessGroupsTest {

    private val posix = File.separatorChar == '/'
    private val spawned = mutableListOf<CancellationAddress>()

    @AfterTest
    fun reapStragglers() {
        spawned.forEach { ProcessGroups.terminate(it, grace = 100.milliseconds) }
    }

    private fun start(vararg command: String, directory: File? = null): GroupedProcess =
        ProcessGroups.start(ProcessBuilder(*command).directory(directory))
            .also { spawned += it.address }

    private fun groupOf(pid: Long): Long {
        val ps = ProcessBuilder("ps", "-o", "pgid=", "-p", pid.toString()).start()
        return ps.inputStream.bufferedReader().readText().trim().toLong().also { ps.waitFor() }
    }

    @Test
    fun `spawned command leads its own group apart from the spawner's`() {
        if (!posix) return
        val grouped = start("sleep", "30")
        val group = assertNotNull(grouped.address.processGroupId)

        assertEquals(grouped.address.leaderPid, group)
        assertEquals(group, groupOf(grouped.address.leaderPid))
        assertNotEquals(groupOf(ProcessHandle.current().pid()), group)
        assertNotNull(grouped.address.leaderStartedAt)
    }

    @Test
    fun `stdin stdout stderr and exit code pass through unchanged`() {
        if (!posix) return
        val grouped = start("sh", "-c", "read line; echo \"out:\$line\"; echo err-line >&2; exit 7")
        grouped.process.outputStream.bufferedWriter().use { it.write("hello\n") }

        val stdout = grouped.process.inputStream.bufferedReader().readText()
        val stderr = grouped.process.errorStream.bufferedReader().readText()

        assertEquals("out:hello\n", stdout)
        assertEquals("err-line\n", stderr, "job-control notices must not leak into the command's stderr")
        assertEquals(7, grouped.process.waitFor())
    }

    @Test
    fun `working directory from the builder applies to the command`() {
        if (!posix) return
        val dir = File(System.getProperty("java.io.tmpdir")).canonicalFile
        val grouped = start("pwd", directory = dir)

        assertEquals(dir.path, File(grouped.process.inputStream.bufferedReader().readText().trim()).canonicalPath)
        assertEquals(0, grouped.process.waitFor())
    }

    @Test
    fun `missing executable exits 127 instead of throwing`() {
        if (!posix) return
        val grouped = start("ampere-definitely-not-a-command")

        assertEquals(127, grouped.process.waitFor())
    }

    @Test
    fun `terminate reaches grandchildren the leader spawned`() {
        if (!posix) return
        val grouped = start("sh", "-c", "sleep 30 & sleep 30 & wait")
        val leader = ProcessHandle.of(grouped.address.leaderPid).orElseThrow()
        waitUntil { leader.descendants().count() >= 2L }
        val grandchildren = leader.descendants().toList()

        assertEquals(TerminationOutcome.TERMINATED, grouped.terminate(grace = 2.seconds))

        assertFalse(ProcessGroups.isAlive(grouped.address))
        grandchildren.forEach { waitUntil { !it.isAlive } }
        assertFalse(grouped.process.isAlive, "the wrapper exits once its group is gone")
    }

    @Test
    fun `terminate escalates to SIGKILL when the group ignores SIGTERM`() {
        if (!posix) return
        val grouped = start("sh", "-c", "trap '' TERM; sleep 30 & wait")
        val leader = ProcessHandle.of(grouped.address.leaderPid).orElseThrow()
        waitUntil { leader.descendants().count() >= 1L }

        assertEquals(TerminationOutcome.KILLED, grouped.terminate(grace = 300.milliseconds))
        assertFalse(ProcessGroups.isAlive(grouped.address))
    }

    @Test
    fun `address alone is enough to terminate after the handle is lost`() {
        if (!posix) return
        // Round-trip through JSON, as a supervisor journal would after a restart.
        val recorded = Json.encodeToString(CancellationAddress.serializer(), start("sleep", "30").address)
        val recovered = Json.decodeFromString(CancellationAddress.serializer(), recorded)

        assertTrue(ProcessGroups.isAlive(recovered))
        assertEquals(TerminationOutcome.TERMINATED, ProcessGroups.terminate(recovered, grace = 2.seconds))
        assertEquals(TerminationOutcome.ALREADY_GONE, ProcessGroups.terminate(recovered))
    }

    @Test
    fun `reused leader PID is refused and left running`() {
        if (!posix) return
        val grouped = start("sleep", "30")
        val startedAt = assertNotNull(grouped.address.leaderStartedAt)
        val stale = grouped.address.copy(leaderStartedAt = startedAt - 1_000.seconds)

        assertEquals(TerminationOutcome.REFUSED_ADDRESS_REUSED, ProcessGroups.terminate(stale))
        assertTrue(ProcessGroups.isAlive(grouped.address))
    }

    @Test
    fun `run terminates the group when the caller is cancelled`() = runBlocking {
        if (!posix) return@runBlocking
        val started = CompletableDeferred<CancellationAddress>()
        val job = async(Dispatchers.Default) {
            ProcessGroups.run(ProcessBuilder("sh", "-c", "sleep 30 & wait"), grace = 2.seconds) { grouped ->
                spawned += grouped.address
                started.complete(grouped.address)
                // Blocks on the pipe; only killing the group releases it.
                grouped.process.inputStream.bufferedReader().readText()
            }
        }
        val address = withTimeout(10.seconds) { started.await() }

        job.cancel()
        withTimeout(10.seconds) { job.join() }

        assertTrue(job.isCancelled)
        assertFalse(ProcessGroups.isAlive(address))
    }

    @Test
    fun `run leaves a finished group alone and returns the block's result`() = runBlocking {
        if (!posix) return@runBlocking
        val output = ProcessGroups.run(ProcessBuilder("echo", "done")) { grouped ->
            spawned += grouped.address
            grouped.process.inputStream.bufferedReader().readText().also { grouped.process.waitFor() }
        }

        assertEquals("done\n", output)
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5.seconds.inWholeNanoseconds
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met within 5s" }
            Thread.sleep(20)
        }
    }
}
