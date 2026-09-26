package link.socket.ampere.agents.definition.code

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.ToolReadCodeFile
import link.socket.ampere.agents.execution.tools.ToolWriteCodeFile

/**
 * AMPR-300: the code-tool strategies root their `ExecutionContext.Code` in the
 * workspace the dispatching agent pinned on the request. The former fallback
 * to `"."` made every agent built without a workspace write into whatever
 * directory the process was started from; there is no fallback now.
 */
class CodeParamsWorkspacePinningTest {

    private val pinned = ExecutionWorkspace(baseDirectory = "/srv/worktrees/AMPR-300")

    @Test
    fun `CodeWriting roots the promoted WriteCode context in the request's pinned workspace`() {
        val enriched = CodeParams.CodeWriting().parseAndEnrichRequest(
            jsonResponse = """{"files":[{"path":"src/Hello.kt","content":"fun main() {}"}]}""",
            originalRequest = request(workspace = pinned),
        )

        val context = assertIs<ExecutionContext.Code.WriteCode>(enriched.context)
        assertEquals(pinned, context.workspace)
        assertEquals(listOf("src/Hello.kt" to "fun main() {}"), context.instructionsPerFilePath)
    }

    @Test
    fun `CodeReading roots the promoted ReadCode context in the request's pinned workspace`() {
        val enriched = CodeParams.CodeReading().parseAndEnrichRequest(
            jsonResponse = """{"filePaths":["src/Hello.kt"]}""",
            originalRequest = request(workspace = pinned),
        )

        val context = assertIs<ExecutionContext.Code.ReadCode>(enriched.context)
        assertEquals(pinned, context.workspace)
    }

    @Test
    fun `an already-Code context keeps its own workspace over the request pin`() {
        val contextWorkspace = ExecutionWorkspace(baseDirectory = "/srv/worktrees/other")
        val original = request(workspace = pinned)
        val codeRequest = ExecutionRequest(
            context = ExecutionContext.Code.WriteCode(
                executorId = original.context.executorId,
                ticket = original.context.ticket,
                task = original.context.task,
                instructions = original.context.instructions,
                workspace = contextWorkspace,
                instructionsPerFilePath = emptyList(),
            ),
            constraints = original.constraints,
            workspace = pinned,
        )

        val enriched = CodeParams.CodeWriting().parseAndEnrichRequest(
            jsonResponse = """{"files":[{"path":"a.kt","content":"x"}]}""",
            originalRequest = codeRequest,
        )

        assertEquals(contextWorkspace, assertIs<ExecutionContext.Code.WriteCode>(enriched.context).workspace)
    }

    @Test
    fun `CodeWriting refuses to build a prompt for an unpinned request instead of assuming the working directory`() {
        val failure = assertFailsWith<IllegalStateException> {
            CodeParams.CodeWriting().buildPrompt(
                tool = ToolWriteCodeFile(AgentActionAutonomy.FULLY_AUTONOMOUS),
                request = request(workspace = null),
                intent = "write a file",
            )
        }
        assertTrue(failure.message.orEmpty().contains("No workspace pinned"), failure.message)
        assertTrue(failure.message.orEmpty().contains("write_code_file"), failure.message)
    }

    @Test
    fun `CodeReading refuses to build a prompt for an unpinned request`() {
        val failure = assertFailsWith<IllegalStateException> {
            CodeParams.CodeReading().buildPrompt(
                tool = ToolReadCodeFile(),
                request = request(workspace = null),
                intent = "read a file",
            )
        }
        assertTrue(failure.message.orEmpty().contains("No workspace pinned"), failure.message)
    }

    @Test
    fun `CodeWriting refuses to enrich an unpinned request even after the LLM answered`() {
        assertFailsWith<IllegalStateException> {
            CodeParams.CodeWriting().parseAndEnrichRequest(
                jsonResponse = """{"files":[{"path":"a.kt","content":"x"}]}""",
                originalRequest = request(workspace = null),
            )
        }
    }

    @Test
    fun `the prompt names the pinned workspace so the LLM writes paths relative to it`() {
        val prompt = CodeParams.CodeWriting().buildPrompt(
            tool = ToolWriteCodeFile(AgentActionAutonomy.FULLY_AUTONOMOUS),
            request = request(workspace = pinned),
            intent = "write a file",
        )
        assertTrue(prompt.contains("Workspace: ${pinned.baseDirectory}"), prompt)
    }

    private fun request(workspace: ExecutionWorkspace?): ExecutionRequest<ExecutionContext.NoChanges> {
        val now = Clock.System.now()
        return ExecutionRequest(
            context = ExecutionContext.NoChanges(
                executorId = "agent-1",
                ticket = Ticket(
                    id = "ticket-1",
                    title = "Test ticket",
                    description = "Test ticket description",
                    type = TicketType.TASK,
                    priority = TicketPriority.MEDIUM,
                    status = TicketStatus.InProgress,
                    assignedAgentId = "agent-1",
                    createdByAgentId = "agent-1",
                    createdAt = now,
                    updatedAt = now,
                ),
                task = Task.CodeChange(
                    id = "task-1",
                    status = TaskStatus.Pending,
                    description = "Do the thing",
                ),
                instructions = "Do the thing",
            ),
            constraints = ExecutionConstraints(requireTests = false, requireLinting = false),
            workspace = workspace,
        )
    }
}
