package link.socket.ampere.agents.execution.executor.acp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [AcpConnection] against a [ScriptedAcpAgent] over an in-memory transport (AMPR-490): the
 * handshake, a turn that streams, the agent's own requests answered mid-turn, the stream
 * closing, and the cancel handshake.
 *
 * `runTest` with every coroutine on `backgroundScope`, so the whole exchange runs on one
 * scheduler and the grace timeouts are virtual.
 */
class AcpConnectionTest {

    private class RecordingHandler : AcpAgentRequestHandler {
        val permissions = mutableListOf<RequestPermissionParams>()
        val writes = mutableListOf<WriteTextFileParams>()
        val reads = mutableListOf<ReadTextFileParams>()

        override suspend fun requestPermission(params: RequestPermissionParams): RequestPermissionResult {
            permissions += params
            val allow = params.options.first { it.kind == PermissionOptionKind.ALLOW_ONCE }
            return RequestPermissionResult(PermissionOutcome.selected(allow.optionId))
        }

        override suspend fun readTextFile(params: ReadTextFileParams): ReadTextFileResult {
            reads += params
            return ReadTextFileResult("the contents of ${params.path}")
        }

        override suspend fun writeTextFile(params: WriteTextFileParams) {
            writes += params
        }
    }

    private class Harness(
        scope: TestScope,
        script: suspend ScriptedAcpAgent.ScriptedTurn.() -> StopReason,
    ) {
        val transport = InMemoryAcpTransport()
        val agent = ScriptedAcpAgent(transport, script = script)
        val handler = RecordingHandler()
        val connection = AcpConnection(transport, handler, scope.backgroundScope)

        init {
            agent.start(scope.backgroundScope)
        }

        suspend fun openSession(): String {
            connection.open().getOrThrow()
            val initialized = connection.initialize(
                capabilities = ClientCapabilities(fs = FsCapabilities(readTextFile = true, writeTextFile = true)),
                clientInfo = Implementation(name = "test"),
            )
            assertEquals(ACP_PROTOCOL_VERSION, initialized.protocolVersion)
            return connection.newSession(cwd = "/work").sessionId
        }
    }

    @Test
    fun `a turn streams its updates and ends with the agent's stop reason`() = runTest {
        val harness = Harness(this) {
            say("Looking")
            update(SessionUpdate.ToolCall(toolCallId = "c1", title = "Read a file", kind = ToolKind.READ))
            StopReason.END_TURN
        }
        val sessionId = harness.openSession()
        val updates = mutableListOf<SessionUpdate>()

        val result = harness.connection.prompt(sessionId, listOf(ContentBlock.text("go")), 1.seconds) { updates += it }

        assertEquals(StopReason.END_TURN, result.reason)
        assertEquals(2, updates.size)
        assertEquals("Looking", assertIs<SessionUpdate.AgentMessageChunk>(updates[0]).content.text)
        assertEquals("c1", assertIs<SessionUpdate.ToolCall>(updates[1]).toolCallId)
        assertEquals("/work", harness.agent.sessions.single().cwd)
        val advertised = harness.agent.initializations.single().clientCapabilities.fs
        assertTrue(advertised.writeTextFile && advertised.readTextFile, "the fs capability was advertised")
        assertEquals("go", harness.agent.lastPromptText())
    }

    @Test
    fun `an agent request inside a turn is answered by the handler while the prompt is still pending`() = runTest {
        val harness = Harness(this) {
            val answer = askPermission(PermissionToolCall(toolCallId = "c1", kind = ToolKind.EDIT))
            writeFile("/work/a.kt", "fun a() {}").getOrThrow()
            val read = readFile("/work/a.kt").getOrThrow()
            say("permission ${answer.outcome.optionId}; read '$read'")
            StopReason.END_TURN
        }
        val sessionId = harness.openSession()
        val said = mutableListOf<String>()

        harness.connection.prompt(sessionId, listOf(ContentBlock.text("go")), 1.seconds) { update ->
            (update as? SessionUpdate.AgentMessageChunk)?.content?.text?.let { said += it }
        }

        assertEquals("c1", harness.handler.permissions.single().toolCall.toolCallId)
        assertEquals("/work/a.kt", harness.handler.writes.single().path)
        assertEquals("permission allow; read 'the contents of /work/a.kt'", said.single())
    }

    @Test
    fun `a method this client does not serve is answered with METHOD_NOT_FOUND and the turn goes on`() = runTest {
        var code: Int? = null
        val harness = Harness(this) {
            val failure = rawRequest("terminal/create", buildJsonObject { put("sessionId", sessionId) })
            code = (failure.exceptionOrNull() as? AcpRemoteException)?.error?.code
            StopReason.END_TURN
        }
        val sessionId = harness.openSession()

        val result = harness.connection.prompt(sessionId, listOf(ContentBlock.text("go")), 1.seconds) {}

        assertEquals(JsonRpcError.METHOD_NOT_FOUND, code)
        assertEquals(StopReason.END_TURN, result.reason)
    }

    @Test
    fun `the agent closing its stream fails the pending prompt rather than hanging it`() = runTest {
        val harness = Harness(this) {
            say("about to die")
            exit()
            StopReason.END_TURN
        }
        val sessionId = harness.openSession()

        val failure = assertFailsWith<AcpException> {
            harness.connection.prompt(sessionId, listOf(ContentBlock.text("go")), 1.seconds) {}
        }

        assertTrue(failure.message!!.contains("closed its stream"), failure.message)
        assertFalse(harness.connection.isOpen)
    }

    @Test
    fun `cancelling the prompt sends session cancel and an agent that answers keeps the connection`() = runTest {
        val harness = Harness(this) {
            awaitCancel()
            StopReason.CANCELLED
        }
        val sessionId = harness.openSession()

        val turn = launch {
            harness.connection.prompt(sessionId, listOf(ContentBlock.text("go")), 1.seconds) {}
        }
        awaitUntil("the agent to receive the prompt") { harness.agent.prompts.size == 1 }
        turn.cancelAndJoin()

        assertEquals(listOf(sessionId), harness.agent.cancels)
        assertTrue(harness.connection.isOpen, "the agent answered within the grace, so the process lives on")
        assertEquals(0, harness.transport.closeCount)
    }

    @Test
    fun `an agent that ignores session cancel has the connection closed after the grace`() = runTest {
        val harness = Harness(this) {
            awaitCancel()
            awaitCancellation()
        }
        val sessionId = harness.openSession()

        val turn = launch {
            harness.connection.prompt(sessionId, listOf(ContentBlock.text("go")), 200.milliseconds) {}
        }
        awaitUntil("the agent to receive the prompt") { harness.agent.prompts.size == 1 }
        turn.cancelAndJoin()

        assertEquals(listOf(sessionId), harness.agent.cancels)
        assertFalse(harness.connection.isOpen)
        assertEquals(1, harness.transport.closeCount)
    }

    @Test
    fun `a transport that cannot start is the connection's failure`() = runTest {
        val transport = InMemoryAcpTransport().apply { startFailure = IllegalStateException("no such binary") }
        val connection = AcpConnection(transport, RecordingHandler(), backgroundScope)

        val opened = connection.open()

        assertEquals("no such binary", opened.exceptionOrNull()?.message)
        assertFalse(connection.isOpen)
    }

    /**
     * Lets the scheduler run until [condition] holds. `advanceUntilIdle` is not enough here:
     * it stops once no foreground task is left, which is before the agent's background
     * coroutine has read the line the test just sent.
     */
    private suspend fun awaitUntil(what: String, condition: () -> Boolean) {
        repeat(10_000) {
            if (condition()) return
            yield()
        }
        error("timed out waiting for $what")
    }
}
