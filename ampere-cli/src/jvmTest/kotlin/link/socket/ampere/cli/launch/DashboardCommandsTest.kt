package link.socket.ampere.cli.launch

import com.github.ajalt.mordant.terminal.Terminal
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import link.socket.ampere.cli.layout.DemoInputHandler
import link.socket.ampere.cli.watch.CommandResult
import org.jline.terminal.impl.DumbTerminal
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The dashboard's command line runs off its input loop (AMPR-468): keys are read while a command
 * waits, ESC cancels it, and its result replaces the waiting screen when it returns.
 */
class DashboardCommandsTest {

    private val tick = 5.milliseconds
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = DemoInputHandler(
        terminal = Terminal(),
        jlineTerminal = DumbTerminal(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream()),
    )

    @AfterEach
    fun tearDown() {
        scope.cancel()
        handler.close()
    }

    /** A command that waits until the test releases it, and records whether it was cancelled. */
    private class HeldCommand {
        val started = CompletableDeferred<String>()
        val release = CompletableDeferred<CommandResult>()
        val cancelled = CompletableDeferred<Unit>()
        val runs = AtomicInteger()

        suspend fun execute(command: String): CommandResult {
            runs.incrementAndGet()
            started.complete(command)
            return try {
                release.await()
            } catch (cancellation: CancellationException) {
                cancelled.complete(Unit)
                throw cancellation
            }
        }
    }

    private fun commands(execute: suspend (String) -> CommandResult) = DashboardCommands(
        scope = scope,
        execute = execute,
        describePending = { CommandResult.Pending("Running :$it… [ESC] cancel") },
    )

    /** The dashboard's key handling as `AmpereCommand` wires it: the command line first, then the view. */
    private class Dashboard(private val handler: DemoInputHandler, val commands: DashboardCommands) {
        @Volatile
        var config = DemoInputHandler.DemoViewConfig()

        fun onKey(key: Char): DashboardExit? {
            if (commands.intercept(key, config)) return null
            return when (val result = handler.processKey(key, config)) {
                is DemoInputHandler.KeyResult.ConfigChange -> {
                    config = result.newConfig
                    null
                }
                is DemoInputHandler.KeyResult.ExecuteCommand -> {
                    config = result.newConfig
                    commands.submit(result.command)
                }
                is DemoInputHandler.KeyResult.EscalationResponse -> {
                    config = result.newConfig
                    null
                }
                else -> result.requestedExit()
            }
        }

        fun type(keys: String): DashboardExit? = keys.map(::onKey).lastOrNull()
    }

    private suspend fun eventually(what: String, condition: () -> Boolean) {
        withTimeout(5.seconds) {
            while (!condition()) delay(tick)
        }
        assertTrue(condition(), what)
    }

    @Test
    fun `the input loop reads keys while a command waits and draws its result once it returns`() = runBlocking<Unit> {
        val held = HeldCommand()
        val dashboard = Dashboard(handler, commands(held::execute))
        val keys = ConcurrentLinkedQueue<Char>()
        val screens = CopyOnWriteArrayList<CommandResult?>()
        val exit = CompletableDeferred<DashboardExit>()

        val loops = async {
            withTimeout(10.seconds) {
                runDashboardLoops(
                    exit = exit,
                    readKey = { keys.poll() },
                    onKey = { dashboard.onKey(it) },
                    renderFrame = { screens += dashboard.commands.screen },
                    releaseInput = {},
                    tick = tick,
                )
            }
        }

        ":slow\r".forEach(keys::add)
        assertEquals("slow", withTimeout(5.seconds) { held.started.await() })
        eventually("the waiting screen was never drawn") { screens.any { it is CommandResult.Pending } }

        // A view key, read and applied while the command is still waiting.
        keys.add('v')
        eventually("the input loop stopped reading keys while the command waited") { dashboard.config.verboseMode }
        assertTrue(dashboard.commands.isPending)

        held.release.complete(CommandResult.Success("slow answer"))
        eventually("the result was never drawn") {
            screens.any { it == CommandResult.Success("slow answer") }
        }

        // Any key returns to the dashboard.
        keys.add('x')
        eventually("a key did not dismiss the result") { dashboard.commands.screen == null }

        exit.complete(DashboardExit.QUIT)
        assertEquals(DashboardExit.QUIT, loops.await())
        assertEquals(1, held.runs.get())
    }

    @Test
    fun `ESC cancels a waiting command as cancellation and returns to the dashboard`() = runBlocking<Unit> {
        val held = HeldCommand()
        val dashboard = Dashboard(handler, commands(held::execute))

        dashboard.type(":slow\r")
        withTimeout(5.seconds) { held.started.await() }
        assertIs<CommandResult.Pending>(dashboard.commands.screen)

        assertNull(dashboard.type("\u001B"))

        withTimeout(5.seconds) { held.cancelled.await() }
        assertNull(dashboard.commands.screen)
        assertFalse(dashboard.commands.isPending)
    }

    @Test
    fun `a result that arrives after its command was cancelled is not shown`() = runBlocking<Unit> {
        // A command that swallows its own cancellation and answers anyway.
        val started = CompletableDeferred<Unit>()
        val swallowed = CompletableDeferred<Unit>()
        val commands = commands { _ ->
            started.complete(Unit)
            try {
                awaitCancellation()
            } catch (cancellation: CancellationException) {
                swallowed.complete(Unit)
                CommandResult.Error("Failed: cancelled")
            }
        }

        commands.submit("slow")
        // Its body must be running before the cancel: `isPending` is true from `submit` on, and a
        // command cancelled before it is dispatched never runs at all.
        withTimeout(5.seconds) { started.await() }
        assertTrue(commands.cancel())
        withTimeout(5.seconds) { swallowed.await() }
        commands.awaitIdle()
        delay(tick * 4)

        assertNull(commands.screen)
    }

    @Test
    fun `a command typed while another waits is refused with a notice, not queued`() = runBlocking<Unit> {
        val held = HeldCommand()
        val dashboard = Dashboard(handler, commands(held::execute))

        dashboard.type(":slow\r")
        withTimeout(5.seconds) { held.started.await() }
        assertNull(dashboard.type(":agents\r"))

        val pending = assertIs<CommandResult.Pending>(dashboard.commands.screen)
        assertTrue(pending.notice!!.startsWith(":agents was not run"), pending.notice)
        assertTrue(pending.message.startsWith("Running :slow"), pending.message)

        held.release.complete(CommandResult.Success("done"))
        dashboard.commands.awaitIdle()
        assertEquals(CommandResult.Success("done"), dashboard.commands.screen)
        assertEquals(1, held.runs.get())
    }

    @Test
    fun `ESC while typing over a waiting command closes the command line only`() = runBlocking<Unit> {
        val held = HeldCommand()
        val dashboard = Dashboard(handler, commands(held::execute))

        dashboard.type(":slow\r")
        withTimeout(5.seconds) { held.started.await() }
        dashboard.type(":ag\u001B")

        assertEquals(DemoInputHandler.InputMode.NORMAL, dashboard.config.inputMode)
        assertTrue(dashboard.commands.isPending)
        assertFalse(held.cancelled.isCompleted)
    }

    @Test
    fun `q, the quit command and Ctrl+C still exit while a command waits`() = runBlocking<Unit> {
        val held = HeldCommand()
        val dashboard = Dashboard(handler, commands(held::execute))

        dashboard.type(":slow\r")
        withTimeout(5.seconds) { held.started.await() }

        assertEquals(DashboardExit.QUIT, dashboard.type("q"))
        assertEquals(DashboardExit.QUIT, dashboard.type(":quit\r"))
        assertEquals(DashboardExit.INTERRUPTED, dashboard.type("\u0003"))
        assertEquals(1, held.runs.get(), "a quit was run as a command")
    }

    @Test
    fun `a command that throws shows an error`() = runBlocking<Unit> {
        val commands = commands { error("broke") }

        commands.submit("boom")
        commands.awaitIdle()

        assertEquals(CommandResult.Error("Command failed: broke"), commands.screen)
    }

    @Test
    fun `a command that returns at once still shows its result`() = runBlocking<Unit> {
        val commands = commands { CommandResult.Success("ran $it") }

        assertNull(commands.submit("agents"))
        eventually("the result never replaced the waiting screen") { commands.screen !is CommandResult.Pending }

        assertEquals(CommandResult.Success("ran agents"), commands.screen)
        assertTrue(commands.dismiss())
        assertNull(commands.screen)
        assertFalse(commands.cancel())
    }
}
