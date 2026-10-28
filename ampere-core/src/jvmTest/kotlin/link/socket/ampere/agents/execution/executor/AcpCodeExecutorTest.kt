package link.socket.ampere.agents.execution.executor

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.cognition.FileAccessScope
import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.status.ExecutionStatus
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.environment.EnvironmentFileOperation
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.executor.acp.AcpRemoteException
import link.socket.ampere.agents.execution.executor.acp.HarnessPolicy
import link.socket.ampere.agents.execution.executor.acp.InMemoryAcpTransport
import link.socket.ampere.agents.execution.executor.acp.JsonRpcError
import link.socket.ampere.agents.execution.executor.acp.PermissionToolCall
import link.socket.ampere.agents.execution.executor.acp.ScriptedAcpAgent
import link.socket.ampere.agents.execution.executor.acp.SessionUpdate
import link.socket.ampere.agents.execution.executor.acp.StopReason
import link.socket.ampere.agents.execution.executor.acp.ToolCallLocation
import link.socket.ampere.agents.execution.executor.acp.ToolKind
import link.socket.ampere.agents.execution.executor.acp.WalkingWorkspaceObserver
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.HARNESS_CODE_CHANGE_TOOL_ID
import link.socket.ampere.agents.execution.tools.ToolHarnessCodeChange
import link.socket.ampere.util.systemFileSystem
import okio.Path.Companion.toPath

private typealias Script = suspend ScriptedAcpAgent.ScriptedTurn.() -> StopReason

/**
 * [AcpCodeExecutor] against a scripted ACP agent over an in-memory transport, with a fake
 * filesystem as the workspace (AMPR-492). What is pinned: the mapping from a turn to statuses
 * and an outcome, the fence in its three in-process layers, the audit, sessions per
 * `(workspace, run)`, respawn after an exit, the cancel handshake, and that everything the
 * executor produces names the executor.
 */
class AcpCodeExecutorTest {

    private val root: String = Files.createTempDirectory("acp-executor-test").toFile().absolutePath
    private val other: String = Files.createTempDirectory("acp-executor-other").toFile().absolutePath
    private val fs = systemFileSystem.also { it.createDirectories("$root/src".toPath()) }
    private val workspace = ExecutionWorkspace(baseDirectory = root)
    private val kotlinUnderSrc = FileAccessScope(
        readPatterns = setOf(FileAccessScope.AnyPath),
        writePatterns = setOf("src/**"),
    )
    private val tool = ToolHarnessCodeChange()

    @AfterTest
    fun cleanUp() {
        File(root).deleteRecursively()
        File(other).deleteRecursively()
    }

    private val transports = mutableListOf<InMemoryAcpTransport>()
    private val agents = mutableListOf<ScriptedAcpAgent>()

    /** An executor whose spawns run [scripts] in turn, the last one for every spawn after. */
    private fun TestScope.executor(
        vararg scripts: Script,
        policy: HarnessPolicy = HarnessPolicy(),
        boundWorkspace: ExecutionWorkspace? = workspace,
        protocolVersion: Int = 1,
        startFailure: Throwable? = null,
    ): AcpCodeExecutor {
        val queue = ArrayDeque(scripts.toList())
        return AcpCodeExecutor(
            id = EXECUTOR_ID,
            displayName = "Fake ACP",
            transportFactory = {
                InMemoryAcpTransport().also { transport ->
                    transport.startFailure = startFailure
                    transports += transport
                    val script = if (queue.size > 1) queue.removeFirst() else queue.first()
                    agents += ScriptedAcpAgent(transport, protocolVersion = protocolVersion, script = script)
                        .also { it.start(backgroundScope) }
                }
            },
            observerFactory = { WalkingWorkspaceObserver(fs, root.toPath()) },
            policy = policy,
            workspace = boundWorkspace,
            fileSystem = fs,
            readerScope = backgroundScope,
        )
    }

    /** The last status of one call: the terminal one. */
    private suspend fun AcpCodeExecutor.run(request: ExecutionRequest<ExecutionContext.NoChanges>): ExecutionStatus =
        execute(request, tool).toList().last()

    private fun edit(path: String) = PermissionToolCall(
        toolCallId = "c1",
        kind = ToolKind.EDIT,
        locations = listOf(ToolCallLocation(path)),
    )

    private fun writeDirectly(path: String, content: String) {
        val target = path.toPath()
        target.parent?.let { fs.createDirectories(it) }
        fs.write(target) { writeUtf8(content) }
    }

    private fun exists(path: String): Boolean = fs.exists(path.toPath())

    // ========================================================================
    // Success, and what it is made of
    // ========================================================================

    @Test
    fun `a turn that edits inside the scope is a CodeChanged success with the agent's account`() = runTest {
        val executor = executor({
            update(
                SessionUpdate.ToolCall(
                    toolCallId = "c1",
                    title = "Edit greeting",
                    kind = ToolKind.EDIT,
                    locations = listOf(ToolCallLocation("$root/src/Greeting.kt")),
                ),
            )
            val answer = askPermission(
                edit("$root/src/Greeting.kt"),
            )
            if (answer.outcome.optionId == "allow") writeDirectly("$root/src/Greeting.kt", "fun greet() = \"hi\"")
            say("Added a greeting function.")
            StopReason.END_TURN
        })

        val statuses = executor.execute(request(scope = kotlinUnderSrc), tool).toList()

        assertIs<ExecutionStatus.Started>(statuses[0])
        assertIs<ExecutionStatus.Planning>(statuses[1])
        val writing = assertIs<ExecutionStatus.Writing>(statuses[2])
        assertEquals("src/Greeting.kt", writing.filePath)
        val completed = assertIs<ExecutionStatus.Completed>(statuses.last())
        val success = assertIs<ExecutionOutcome.CodeChanged.Success>(completed.result)
        assertEquals(listOf("src/Greeting.kt"), success.changedFiles)
        assertEquals("Added a greeting function.", success.validation.codeChanges?.diffSummary)
        assertEquals(EnvironmentFileOperation.MODIFY, success.validation.codeChanges?.filesChanged?.single()?.operation)
        assertTrue(statuses.all { it.executorId == EXECUTOR_ID }, "every status names the executor")
        assertEquals(EXECUTOR_ID, success.executorId)
        assertEquals("allow", agents.single().permissionAnswers.single().outcome.optionId)
    }

    @Test
    fun `the prompt carries the step the ticket the workspace and the scope as prose`() = runTest {
        val executor = executor({ StopReason.END_TURN })

        executor.execute(request(scope = kotlinUnderSrc), tool).toList()

        val prompt = agents.single().lastPromptText()
        assertTrue(prompt.contains("Add a greeting"), prompt)
        assertTrue(prompt.contains("Test ticket"), prompt)
        assertTrue(prompt.contains("Root: $root"), prompt)
        assertTrue(prompt.contains("May write: src/**"), prompt)
        assertTrue(prompt.contains("Do not commit"), prompt)
        assertEquals(root, agents.single().sessions.single().cwd)
    }

    @Test
    fun `a code change step the agent declines in prose fails retryably with the agent's words`() = runTest {
        // AMPR-498's live turn: the scope allows src/**, the step asks for README.md, and the
        // agent ends the turn without trying.
        val declined = "I need to append a line to README.md. **I cannot complete this task** because " +
            "the file access rules only allow src/**."
        val executor = executor({
            say(declined)
            StopReason.END_TURN
        })

        val failed = assertIs<ExecutionStatus.Failed>(executor.run(request(scope = kotlinUnderSrc)))

        val failure = assertIs<ExecutionOutcome.CodeChanged.Failure>(failed.result)
        assertEquals(EXECUTOR_ID, failure.executorId)
        assertEquals(ExecutionError.Type.UNEXPECTED, failure.error.type)
        assertTrue(failure.error.isRetryable)
        assertEquals("Fake ACP ended the turn without changing any file.", failure.error.message)
        assertEquals(declined, failure.error.details)
        assertEquals(emptyList<String>(), failure.partiallyChangedFiles)
    }

    @Test
    fun `a generic step nominating the harness tool that changes nothing fails the same way`() = runTest {
        // A hosted run's steps are Task.Step, and it offers the tool seat-namespaced; the
        // nominated tool is what asks for the change.
        val executor = executor({
            say("Nothing to do: the greeting already exists.")
            StopReason.END_TURN
        })
        val step = Task.Step(
            id = "step-1",
            status = TaskStatus.Pending,
            description = "Add a greeting",
            toolId = "doer/$HARNESS_CODE_CHANGE_TOOL_ID",
        )

        val seatTool = ToolHarnessCodeChange().copy(id = "doer/$HARNESS_CODE_CHANGE_TOOL_ID")

        val failed = assertIs<ExecutionStatus.Failed>(executor.execute(request(task = step), seatTool).toList().last())

        val failure = assertIs<ExecutionOutcome.CodeChanged.Failure>(failed.result)
        assertTrue(failure.error.isRetryable)
        assertEquals("Nothing to do: the greeting already exists.", failure.error.details)
    }

    @Test
    fun `a step that asked for no code change and changed nothing is a NoChanges success`() = runTest {
        val executor = executor({
            say("The greeting reads well.")
            StopReason.END_TURN
        })
        val review = FunctionTool<ExecutionContext.NoChanges>(
            id = "review_code",
            name = "Review code",
            description = "Reviews the checkout",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { error("routed to the harness, never called") },
        )
        val step = Task.Step(id = "step-1", status = TaskStatus.Pending, description = "Review", toolId = review.id)

        val statuses = executor.execute(request(task = step), review).toList()

        val completed = assertIs<ExecutionStatus.Completed>(statuses.last())
        val success = assertIs<ExecutionOutcome.NoChanges.Success>(completed.result)
        assertEquals("The greeting reads well.", success.message)
        assertEquals(EXECUTOR_ID, success.executorId)
    }

    // ========================================================================
    // The fence
    // ========================================================================

    @Test
    fun `an edit outside the scope is refused at the permission request and the turn goes on`() = runTest {
        val executor = executor({
            val answer = askPermission(
                edit("$root/README.md"),
            )
            if (answer.outcome.optionId == "allow") writeDirectly("$root/README.md", "nope")
            StopReason.END_TURN
        })

        val failed = assertIs<ExecutionStatus.Failed>(executor.run(request(scope = kotlinUnderSrc)))

        assertEquals("reject", agents.single().permissionAnswers.single().outcome.optionId)
        assertFalse(exists("$root/README.md"))
        val details = assertIs<ExecutionOutcome.CodeChanged.Failure>(failed.result).error.details.orEmpty()
        assertTrue(details.contains("Refused 1 request"), details)
        assertTrue(details.contains("README.md"), details)
    }

    @Test
    fun `a file write through the client is served inside the scope and refused outside it`() = runTest {
        val executor = executor({
            writeFile("$root/src/New.kt", "class New")
            writeFile("$root/README.md", "nope")
            StopReason.END_TURN
        })

        val completed = assertIs<ExecutionStatus.Completed>(executor.run(request(scope = kotlinUnderSrc)))

        val answers = agents.single().writeAnswers
        assertTrue(answers[0].isSuccess)
        val refused = assertIs<AcpRemoteException>(answers[1].exceptionOrNull())
        assertEquals(JsonRpcError.FENCE_REFUSED, refused.error.code)
        assertTrue(exists("$root/src/New.kt"))
        assertFalse(exists("$root/README.md"))
        val success = assertIs<ExecutionOutcome.CodeChanged.Success>(completed.result)
        assertEquals(listOf("src/New.kt"), success.changedFiles)
        assertEquals(EnvironmentFileOperation.CREATE, success.validation.codeChanges?.filesChanged?.single()?.operation)
    }

    @Test
    fun `a file read through the client honours the read patterns and the line window`() = runTest {
        writeDirectly("$root/src/Lines.kt", "one\ntwo\nthree\nfour")
        writeDirectly("$root/.env", "SECRET=1")
        val executor = executor({
            readFile("$root/src/Lines.kt", line = 2, limit = 2)
            readFile("$root/.env")
            StopReason.END_TURN
        })
        val scope = FileAccessScope(
            readPatterns = setOf(FileAccessScope.AnyPath),
            writePatterns = setOf("src/**"),
            forbiddenPatterns = setOf("**/.env"),
        )

        executor.execute(request(scope = scope), tool).toList()

        val answers = agents.single().readAnswers
        assertEquals("two\nthree", answers[0].getOrThrow())
        assertEquals(JsonRpcError.FENCE_REFUSED, assertIs<AcpRemoteException>(answers[1].exceptionOrNull()).error.code)
    }

    @Test
    fun `a write to disk the agent never asked about fails the step when it is outside the scope`() = runTest {
        val executor = executor({
            writeDirectly("$root/src/Fine.kt", "ok")
            writeDirectly("$root/README.md", "sneaky")
            say("Done.")
            StopReason.END_TURN
        })

        val failed = assertIs<ExecutionStatus.Failed>(executor.run(request(scope = kotlinUnderSrc)))

        val failure = assertIs<ExecutionOutcome.CodeChanged.Failure>(failed.result)
        assertEquals(ExecutionError.Type.WORKSPACE_ERROR, failure.error.type)
        assertFalse(failure.error.isRetryable)
        assertTrue(failure.error.message.contains("\"README.md\""), failure.error.message)
        assertTrue(failure.error.message.contains("Nothing was reverted"), failure.error.message)
        assertEquals(listOf("README.md", "src/Fine.kt"), failure.partiallyChangedFiles)
        assertTrue(exists("$root/README.md"), "the write is reported, not undone")
    }

    @Test
    fun `a shell command is refused under the default policy and allowed when the policy says so`() = runTest {
        val executor = executor({
            askPermission(PermissionToolCall(toolCallId = "c1", kind = ToolKind.EXECUTE, title = "run tests"))
            StopReason.END_TURN
        })
        val permissive = executor({
            askPermission(PermissionToolCall(toolCallId = "c1", kind = ToolKind.EXECUTE, title = "run tests"))
            StopReason.END_TURN
        }, policy = HarnessPolicy(shell = HarnessPolicy.ShellAccess.Allow))

        executor.execute(request(), tool).toList()
        permissive.execute(request(), tool).toList()

        assertEquals("reject", agents[0].permissionAnswers.single().outcome.optionId)
        assertEquals("allow", agents[1].permissionAnswers.single().outcome.optionId)
    }

    // ========================================================================
    // How a turn can end
    // ========================================================================

    @Test
    fun `running out of tokens is a retryable timeout failure carrying what changed so far`() = runTest {
        val executor = executor({
            writeDirectly("$root/src/Half.kt", "class Hal")
            StopReason.MAX_TOKENS
        })

        val failed = assertIs<ExecutionStatus.Failed>(executor.run(request(scope = kotlinUnderSrc)))

        val failure = assertIs<ExecutionOutcome.CodeChanged.Failure>(failed.result)
        assertEquals(ExecutionError.Type.TIMEOUT, failure.error.type)
        assertTrue(failure.error.isRetryable)
        assertEquals(listOf("src/Half.kt"), failure.partiallyChangedFiles)
    }

    @Test
    fun `a refusal is a failure that is not worth retrying`() = runTest {
        val executor = executor({
            say("I will not do that.")
            StopReason.REFUSAL
        })

        val failed = assertIs<ExecutionStatus.Failed>(executor.execute(request(), tool).toList().last())

        val failure = assertIs<ExecutionOutcome.CodeChanged.Failure>(failed.result)
        assertEquals(ExecutionError.Type.UNEXPECTED, failure.error.type)
        assertFalse(failure.error.isRetryable)
        assertEquals("I will not do that.", failure.error.details)
    }

    @Test
    fun `a turn the agent cancels after a path is refused is a workspace error naming the refusal`() = runTest {
        // OpenCode 2.2.0 marks the rejected tool call failed and ends the turn `cancelled` (AMPR-505).
        val executor = executor({
            val answer = askPermission(edit("$root/README.md"))
            if (answer.outcome.optionId == "allow") writeDirectly("$root/README.md", "nope")
            StopReason.CANCELLED
        })

        val failed = assertIs<ExecutionStatus.Failed>(executor.run(request(scope = kotlinUnderSrc)))

        assertEquals("reject", agents.single().permissionAnswers.single().outcome.optionId)
        assertFalse(exists("$root/README.md"))
        val failure = assertIs<ExecutionOutcome.CodeChanged.Failure>(failed.result)
        assertEquals(ExecutionError.Type.WORKSPACE_ERROR, failure.error.type)
        assertFalse(failure.error.isRetryable)
        assertTrue(failure.error.message.contains("\"README.md\" no write pattern permits it"), failure.error.message)
        assertFalse(failure.error.message.contains("on its own"), failure.error.message)
        assertEquals(emptyList(), failure.partiallyChangedFiles)
    }

    @Test
    fun `a turn the agent cancels after a shell command is refused is unexpected and not retryable`() = runTest {
        val executor = executor({
            askPermission(PermissionToolCall(toolCallId = "c1", kind = ToolKind.EXECUTE, title = "ls -la"))
            StopReason.CANCELLED
        })

        val failed = assertIs<ExecutionStatus.Failed>(executor.execute(request(), tool).toList().last())

        val failure = assertIs<ExecutionOutcome.CodeChanged.Failure>(failed.result)
        assertEquals(ExecutionError.Type.UNEXPECTED, failure.error.type)
        assertFalse(failure.error.isRetryable)
        assertTrue(failure.error.message.contains("shell commands are not permitted"), failure.error.message)
    }

    @Test
    fun `a turn the agent cancels with nothing refused is still an unexpected retryable cancel`() = runTest {
        val executor = executor({
            say("stopping")
            StopReason.CANCELLED
        })

        val failed = assertIs<ExecutionStatus.Failed>(executor.execute(request(), tool).toList().last())

        val failure = assertIs<ExecutionOutcome.CodeChanged.Failure>(failed.result)
        assertEquals(ExecutionError.Type.UNEXPECTED, failure.error.type)
        assertTrue(failure.error.isRetryable)
        assertTrue(failure.error.message.contains("cancelled the turn on its own"), failure.error.message)
    }

    @Test
    fun `an agent that dies mid turn is TOOL_UNAVAILABLE and the next call spawns a fresh one`() = runTest {
        val executor = executor(
            {
                say("about to crash")
                exit()
                StopReason.END_TURN
            },
            {
                writeDirectly("$root/src/Ok.kt", "ok")
                say("all good now")
                StopReason.END_TURN
            },
        )

        val first = assertIs<ExecutionStatus.Failed>(executor.execute(request(), tool).toList().last())
        val second = assertIs<ExecutionStatus.Completed>(executor.execute(request(), tool).toList().last())

        val failure = assertIs<ExecutionOutcome.CodeChanged.Failure>(first.result)
        assertEquals(ExecutionError.Type.TOOL_UNAVAILABLE, failure.error.type)
        assertTrue(failure.error.isRetryable)
        assertEquals(2, transports.size, "the second call spawned again")
        assertEquals(listOf("src/Ok.kt"), assertIs<ExecutionOutcome.CodeChanged.Success>(second.result).changedFiles)
    }

    @Test
    fun `a turn past the prompt timeout is cancelled and reported as a timeout`() = runTest {
        val executor = executor(
            {
                awaitCancel()
                StopReason.CANCELLED
            },
            policy = HarnessPolicy(promptTimeout = 5.seconds),
        )

        val failed = assertIs<ExecutionStatus.Failed>(executor.execute(request(), tool).toList().last())

        val failure = assertIs<ExecutionOutcome.CodeChanged.Failure>(failed.result)
        assertEquals(ExecutionError.Type.TIMEOUT, failure.error.type)
        assertEquals(listOf("session-1"), agents.single().cancels)
    }

    @Test
    fun `cancelling the collector sends session cancel to the agent`() = runTest {
        val executor = executor({
            awaitCancel()
            StopReason.CANCELLED
        })

        val collecting = launch { executor.execute(request(), tool).toList() }
        awaitUntil("the turn to be in flight") { agents.isNotEmpty() && agents.single().prompts.size == 1 }
        collecting.cancelAndJoin()

        assertEquals(listOf("session-1"), agents.single().cancels)
    }

    // ========================================================================
    // Sessions, the workspace, health
    // ========================================================================

    @Test
    fun `steps of one run share a session and another run gets its own`() = runTest {
        val executor = executor({ StopReason.END_TURN })

        executor.execute(request(runId = "run-1"), tool).toList()
        executor.execute(request(runId = "run-1"), tool).toList()
        executor.execute(request(runId = "run-2"), tool).toList()

        val agent = agents.single()
        assertEquals(2, agent.sessions.size)
        assertEquals(listOf("session-1", "session-1", "session-2"), agent.prompts.map { it.sessionId })
        assertEquals(1, transports.size, "one process the whole way")
    }

    @Test
    fun `the request workspace wins over the bound one and none at all is refused before any spawn`() = runTest {
        val bound = executor({ StopReason.END_TURN })
        val unbound = executor({ StopReason.END_TURN }, boundWorkspace = null)

        bound.execute(request(workspace = ExecutionWorkspace(other)), tool).toList()
        val refused = unbound.execute(request(), tool).toList()

        assertEquals(other, agents.single().sessions.single().cwd)
        val failed = assertIs<ExecutionStatus.Failed>(refused.single())
        val failure = assertIs<ExecutionOutcome.CodeChanged.Failure>(failed.result)
        assertEquals(ExecutionError.Type.WORKSPACE_ERROR, failure.error.type)
        assertEquals(1, transports.size, "the unbound executor spawned nothing")
    }

    @Test
    fun `health reports the agent's version when it starts and the reason when it does not`() = runTest {
        val healthy = executor({ StopReason.END_TURN })
        val cannotStart = executor({ StopReason.END_TURN }, startFailure = IllegalStateException("no such binary"))
        val wrongVersion = executor({ StopReason.END_TURN }, protocolVersion = 2)

        val up = healthy.performHealthCheck().getOrThrow()
        val down = cannotStart.performHealthCheck().getOrThrow()
        val mismatched = wrongVersion.performHealthCheck().getOrThrow()

        assertTrue(up.isAvailable)
        assertTrue(up.status.contains("(0.1)"), up.status)
        assertEquals("0.1", healthy.agentInfo?.version)
        assertFalse(down.isAvailable)
        assertEquals(listOf("no such binary"), down.issues)
        assertFalse(mismatched.isAvailable)
        assertTrue(mismatched.issues.single().contains("version 2"), mismatched.issues.single())
    }

    @Test
    fun `close ends the agent and a call afterwards is refused`() = runTest {
        val executor = executor({ StopReason.END_TURN })
        executor.execute(request(), tool).toList()

        executor.close()
        val after = assertIs<ExecutionStatus.Failed>(executor.execute(request(), tool).toList().last())

        assertEquals(1, transports.single().closeCount)
        assertTrue(assertIs<ExecutionOutcome.CodeChanged.Failure>(after.result).error.message.contains("closed"))
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    private fun request(
        scope: FileAccessScope? = null,
        runId: String? = "run-1",
        workspace: ExecutionWorkspace? = null,
        task: Task = Task.CodeChange(id = "task-1", status = TaskStatus.Pending, description = "Add a greeting"),
    ): ExecutionRequest<ExecutionContext.NoChanges> {
        val now = Clock.System.now()
        return ExecutionRequest(
            context = ExecutionContext.NoChanges(
                actorId = "agent-1",
                executorId = EXECUTOR_ID,
                ticket = Ticket(
                    id = "ticket-1",
                    title = "Test ticket",
                    description = "Greet the user on start-up",
                    type = TicketType.TASK,
                    priority = TicketPriority.MEDIUM,
                    status = TicketStatus.InProgress,
                    assignedAgentId = "agent-1",
                    createdByAgentId = "agent-1",
                    createdAt = now,
                    updatedAt = now,
                ),
                task = task,
                instructions = "Add a greeting",
            ),
            runId = runId,
            workspace = workspace,
            fileAccessScope = scope,
        )
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

    private companion object {
        const val EXECUTOR_ID = "fake-acp"
    }
}
