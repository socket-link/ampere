package link.socket.ampere.cli.watch

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import link.socket.ampere.agents.domain.routing.local.LocalCapacity
import link.socket.ampere.agents.domain.routing.local.LocalInferenceEngine
import link.socket.ampere.agents.domain.routing.local.LocalUnavailableReason
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.agents.events.relay.EventRelayServiceImpl
import link.socket.ampere.agents.events.utils.SilentEventLogger
import link.socket.ampere.cli.launch.DashboardCommands
import link.socket.ampere.cli.launch.intercept
import link.socket.ampere.cli.layout.DemoInputHandler
import link.socket.ampere.cli.ondevice.OnDeviceAssistant
import link.socket.ampere.cli.watch.presentation.WatchPresenter
import link.socket.ampere.domain.ai.model.AIModel_OnDevice
import link.socket.ampere.domain.ai.provider.AIProvider_OnDevice
import link.socket.ampere.llm.OnDeviceInferenceSession
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `:ask`, the TUI's display-text path onto the on-device model (AMPR-375). The answer is shown
 * and goes nowhere else; when the device cannot serve, the person is told why and that nothing
 * was sent.
 */
class CommandExecutorAskTest {

    private lateinit var scope: CoroutineScope
    private lateinit var door: InMemoryEventDoor.Handle

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        door = InMemoryEventDoor.open(
            agentId = OnDeviceInferenceSession.AGENT_ID,
            scope = scope,
            logger = SilentEventLogger(),
        )
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        door.close()
    }

    @Test
    fun `ask without a question says how to use it`() = runBlocking<Unit> {
        val result = executor(OnDeviceAssistant.Unbound("unused")).execute("ask")

        assertTrue(assertIs<CommandResult.Error>(result).message.startsWith("Usage: :ask <question>"))
    }

    @Test
    fun `ask on a host without a helper says nothing was sent and why`() = runBlocking<Unit> {
        val unbound = OnDeviceAssistant.Unbound("The on-device model helper only runs on macOS.")

        val result = executor(unbound).execute("ask Hello?")

        val message = assertIs<CommandResult.Error>(result).message
        assertTrue(message.startsWith("No on-device model is connected. Nothing was sent."), message)
        assertTrue(message.contains("only runs on macOS"), message)
    }

    @Test
    fun `ask shows the device's answer and where it came from`() = runBlocking<Unit> {
        val engine = ScriptedEngine(available = true, answer = "An Arc is a run's shape.")

        OnDeviceAssistant.Bound(engine = engine, eventApi = door.api).use { assistant ->
            val result = executor(assistant).execute("ask What is an Arc?")

            val output = assertIs<CommandResult.Success>(result).output
            assertTrue(output.startsWith("What is an Arc?"), output)
            assertTrue(output.contains("An Arc is a run's shape."), output)
            assertTrue(output.contains("Answered on this device · Apple Foundation Models (on-device) · "), output)
            assertEquals(1, engine.generations)
        }
    }

    @Test
    fun `ask on a device that cannot serve says why and that nothing was sent`() = runBlocking<Unit> {
        val engine = ScriptedEngine(available = false, answer = "never")

        OnDeviceAssistant.Bound(engine = engine, eventApi = door.api).use { assistant ->
            val result = executor(assistant).execute("ask What is an Arc?")

            assertEquals(
                "Apple Intelligence is turned off in Settings. Nothing was sent.",
                assertIs<CommandResult.Error>(result).message,
            )
            assertEquals(0, engine.generations)
        }
    }

    @Test
    fun `ESC cancels an ask that is still waiting and the engine sees the cancellation`() = runBlocking<Unit> {
        val engine = HeldEngine()

        OnDeviceAssistant.Bound(engine = engine, eventApi = door.api).use { assistant ->
            val executor = executor(assistant)
            val commands = DashboardCommands(
                scope = scope,
                execute = executor::execute,
                describePending = executor::describePending,
            )

            assertNull(commands.submit("ask What is an Arc?"))
            withTimeout(5.seconds) { engine.generating.await() }
            assertEquals(
                CommandResult.Pending("Asking the on-device model… [ESC] cancel"),
                commands.screen,
            )

            // ESC with no command line open, as the dashboard hands it over.
            assertTrue(commands.intercept('\u001B', DemoInputHandler.DemoViewConfig()))

            withTimeout(5.seconds) { engine.cancelled.await() }
            commands.awaitIdle()
            assertNull(commands.screen, "a cancelled ask left a screen in place of the dashboard")
        }
    }

    private fun executor(onDevice: OnDeviceAssistant) = CommandExecutor(
        presenter = WatchPresenter(EventRelayServiceImpl(door.bus, door.repository)),
        onDevice = onDevice,
    )

    /** An available engine whose generation waits until it is cancelled. */
    private class HeldEngine : LocalInferenceEngine {
        val generating = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()

        override suspend fun probe(): LocalCapacity = LocalCapacity(
            available = true,
            modelId = AIModel_OnDevice.AppleFoundationModels.name,
            maxContextTokens = 4_096,
            providerId = AIProvider_OnDevice.id,
        )

        override suspend fun generate(prompt: String): Result<String> {
            generating.complete(Unit)
            try {
                awaitCancellation()
            } catch (cancellation: CancellationException) {
                cancelled.complete(Unit)
                throw cancellation
            }
        }
    }

    /** The on-device engine, scripted: available or turned off, and one answer. */
    private class ScriptedEngine(private val available: Boolean, private val answer: String) : LocalInferenceEngine {
        var generations: Int = 0
            private set

        override suspend fun probe(): LocalCapacity =
            if (available) {
                LocalCapacity(
                    available = true,
                    modelId = AIModel_OnDevice.AppleFoundationModels.name,
                    maxContextTokens = 4_096,
                    providerId = AIProvider_OnDevice.id,
                )
            } else {
                LocalCapacity(
                    available = false,
                    providerId = AIProvider_OnDevice.id,
                    reason = LocalUnavailableReason.NOT_ENABLED,
                )
            }

        override suspend fun generate(prompt: String): Result<String> {
            generations++
            return Result.success(answer)
        }
    }
}
