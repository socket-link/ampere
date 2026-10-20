package link.socket.ampere.cli.launch

import com.github.ajalt.mordant.terminal.Terminal
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import link.socket.ampere.cli.layout.DemoInputHandler
import link.socket.ampere.cli.watch.CommandResult
import org.jline.terminal.impl.DumbTerminal
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardLoopTest {

    private val tick = 5.milliseconds

    /** Scripted keys, then nothing; counts reads, frames and releases so a test can see the loops stop. */
    private class Harness(keys: String) {
        val keys = ConcurrentLinkedQueue(keys.toList())
        val reads = AtomicInteger()
        val frames = AtomicInteger()
        val releases = AtomicInteger()

        fun readKey(): Char? {
            reads.incrementAndGet()
            return keys.poll()
        }
    }

    private fun inputHandler() = DemoInputHandler(
        terminal = Terminal(),
        jlineTerminal = DumbTerminal(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream()),
    )

    /**
     * The dashboard's own key handling, reduced to what decides an exit: keys through the real
     * [DemoInputHandler.processKey], and a command executor that answers `quit` the way
     * `CommandExecutor` does.
     */
    private fun dashboardOnKey(handler: DemoInputHandler): suspend (Char) -> DashboardExit? {
        var config = DemoInputHandler.DemoViewConfig()
        return { key ->
            when (val result = handler.processKey(key, config)) {
                is DemoInputHandler.KeyResult.ConfigChange -> {
                    config = result.newConfig
                    null
                }
                is DemoInputHandler.KeyResult.ExecuteCommand -> {
                    config = result.newConfig
                    val commandResult = if (result.command == "quit") {
                        CommandResult.Quit
                    } else {
                        CommandResult.Success("ran ${result.command}")
                    }
                    commandResult.requestedExit()
                }
                else -> result.requestedExit()
            }
        }
    }

    private suspend fun runLoops(
        harness: Harness,
        exit: CompletableDeferred<DashboardExit>,
        onKey: suspend (Char) -> DashboardExit?,
    ) = withTimeout(5.seconds) {
        runDashboardLoops(
            exit = exit,
            readKey = harness::readKey,
            onKey = onKey,
            renderFrame = { harness.frames.incrementAndGet() },
            releaseInput = { harness.releases.incrementAndGet() },
            tick = tick,
        )
    }

    /** Neither loop reads or draws once [runDashboardLoops] has returned. */
    private suspend fun assertBothLoopsStopped(harness: Harness) {
        val reads = harness.reads.get()
        val frames = harness.frames.get()
        delay(tick * 20)
        assertEquals(reads, harness.reads.get(), "the input loop kept reading after the exit")
        assertEquals(frames, harness.frames.get(), "the render loop kept drawing after the exit")
    }

    @Test
    fun `q ends both loops and releases the input once`() = runBlocking<Unit> {
        val harness = Harness("dvq")
        val handler = inputHandler()

        val exit = runLoops(harness, CompletableDeferred(), dashboardOnKey(handler))

        assertEquals(DashboardExit.QUIT, exit)
        assertBothLoopsStopped(harness)
        assertEquals(1, harness.releases.get())
        handler.close()
    }

    @Test
    fun `a quit command result ends both loops and releases the input once`() = runBlocking<Unit> {
        // `:status` runs and returns, so only the `:quit` that follows can end the dashboard.
        val harness = Harness(":status\r:quit\r")
        val handler = inputHandler()

        val exit = runLoops(harness, CompletableDeferred(), dashboardOnKey(handler))

        assertEquals(DashboardExit.QUIT, exit)
        assertTrue(harness.keys.isEmpty(), "the dashboard ended before reading the whole `:quit`")
        assertBothLoopsStopped(harness)
        assertEquals(1, harness.releases.get())
        handler.close()
    }

    @Test
    fun `a Ctrl+C byte ends both loops as interrupted`() = runBlocking<Unit> {
        val harness = Harness("\u0003")
        val handler = inputHandler()

        val exit = runLoops(harness, CompletableDeferred(), dashboardOnKey(handler))

        assertEquals(DashboardExit.INTERRUPTED, exit)
        assertBothLoopsStopped(harness)
        assertEquals(1, harness.releases.get())
        handler.close()
    }

    @Test
    fun `an exit requested from outside the loops ends them as interrupted`() = runBlocking<Unit> {
        // What the SIGINT handler does: complete the exit from another thread while no key arrives.
        val harness = Harness("")
        val exit = CompletableDeferred<DashboardExit>()
        val interrupter = Thread {
            Thread.sleep(50)
            exit.complete(DashboardExit.INTERRUPTED)
        }.apply { start() }

        val result = runLoops(harness, exit) { null }
        interrupter.join()

        assertEquals(DashboardExit.INTERRUPTED, result)
        assertBothLoopsStopped(harness)
        assertEquals(1, harness.releases.get())
    }

    @Test
    fun `an exit requested before the loops start ends them at once`() = runBlocking<Unit> {
        val harness = Harness("")
        val exit = CompletableDeferred(DashboardExit.INTERRUPTED)

        assertEquals(DashboardExit.INTERRUPTED, runLoops(harness, exit) { null })
        assertEquals(1, harness.releases.get())
    }

    @Test
    fun `the first exit wins`() = runBlocking<Unit> {
        val harness = Harness("q")
        val exit = CompletableDeferred<DashboardExit>()

        val result = runLoops(harness, exit) { DashboardExit.QUIT }
        exit.complete(DashboardExit.INTERRUPTED)

        assertEquals(DashboardExit.QUIT, result)
        assertEquals(DashboardExit.QUIT, exit.await())
    }

    @Test
    fun `a key read in flight is released before the loops are waited on`() = runBlocking<Unit> {
        // `readKey` blocks until the input is closed, and does not answer to cancellation: if the
        // loops were joined before `releaseInput`, this would never return.
        val released = CountDownLatch(1)
        val reading = CountDownLatch(1)
        val exit = CompletableDeferred<DashboardExit>()

        val started = TimeSource.Monotonic.markNow()

        val result = runDashboardLoops(
            exit = exit,
            readKey = {
                reading.countDown()
                // Bounded, so a regression fails the assertion below instead of hanging the suite.
                released.await(10, TimeUnit.SECONDS)
                null
            },
            onKey = { null },
            renderFrame = {
                if (reading.count == 0L) exit.complete(DashboardExit.INTERRUPTED)
            },
            releaseInput = { released.countDown() },
            tick = tick,
        )

        assertEquals(DashboardExit.INTERRUPTED, result)
        assertTrue(started.elapsedNow() < 5.seconds, "the loops were waited on before the input was released")
    }

    @Test
    fun `a loop that throws propagates and still releases the input`() = runBlocking<Unit> {
        val harness = Harness("")

        val failure = assertFailsWith<IllegalStateException> {
            withTimeout(5.seconds) {
                runDashboardLoops(
                    exit = CompletableDeferred(),
                    readKey = harness::readKey,
                    onKey = { null },
                    renderFrame = { error("render failed") },
                    releaseInput = { harness.releases.incrementAndGet() },
                    tick = tick,
                )
            }
        }

        assertEquals("render failed", failure.message)
        assertEquals(1, harness.releases.get())
    }

    @Test
    fun `exit statuses are 0 for quit and 130 for an interrupt`() {
        assertEquals(0, DashboardExit.QUIT.status)
        assertEquals(130, DashboardExit.INTERRUPTED.status)
    }

    @Test
    fun `only exit keys and the quit command request an exit`() {
        val config = DemoInputHandler.DemoViewConfig()
        assertEquals(DashboardExit.QUIT, DemoInputHandler.KeyResult.Exit.requestedExit())
        assertEquals(DashboardExit.INTERRUPTED, DemoInputHandler.KeyResult.Interrupt.requestedExit())
        assertNull(DemoInputHandler.KeyResult.NoChange.requestedExit())
        assertNull(DemoInputHandler.KeyResult.ConfigChange(config).requestedExit())
        assertNull(DemoInputHandler.KeyResult.ExecuteCommand("quit", config).requestedExit())

        assertEquals(DashboardExit.QUIT, CommandResult.Quit.requestedExit())
        assertNull(CommandResult.Success("done").requestedExit())
        assertNull(CommandResult.Error("nope").requestedExit())
    }
}
