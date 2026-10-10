package link.socket.ampere.agents.definition

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.cognition.FileAccessScope
import link.socket.ampere.agents.domain.cognition.Spark
import link.socket.ampere.agents.domain.cognition.ToolId
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.reasoning.AgentReasoning
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider

/**
 * AMPR-414: the request a plan step dispatches carries the agent's
 * [AutonomousAgent.effectiveFileAccess].
 *
 * `effectiveFileAccess` has been computed since the spark system was written
 * and read by nothing — the same shape of bug AMPR-400 fixed for
 * `allowedTools`. The scope rides on the request for the same reason the
 * workspace (AMPR-300) and the run id (AMPR-351) do: the request is the only
 * value that reaches a tool's execution function, and a tool has no business
 * knowing what an `AutonomousAgent` is.
 */
class SparkBasedAgentFileAccessTest {

    private companion object {
        const val TOOL = "tool_alpha"

        val KOTLIN_ONLY = FileAccessScope(
            readPatterns = setOf("**/*.kt", "**/*.kts"),
            writePatterns = setOf("**/*.kt"),
            forbiddenPatterns = setOf("**/build/**"),
        )
    }

    private class FakeAIConfiguration : AIConfiguration {
        override val provider: AIProvider<*, *>
            get() = throw NotImplementedError("Provider should not be called")
        override val model: AIModel
            get() = AIModel_OpenAI.GPT_4_1

        override fun getAvailableModels(): List<Pair<AIProvider<*, *>, AIModel>> = emptyList()
    }

    private data class ScopingSpark(
        override val fileAccessScope: FileAccessScope?,
    ) : Spark {
        override val name: String = "Role:Scoped"
        override val promptContribution: String = "Stay within your granted file access."
        override val allowedTools: Set<ToolId>? = null
    }

    @Test
    fun `the dispatched request carries the composed file access scope`() {
        val dispatched = CopyOnWriteArrayList<ExecutionRequest<*>>()
        val agent = agentWith(dispatched, ScopingSpark(KOTLIN_ONLY))

        agent.runLLMToExecuteTask(step(), emptyList())
        val scope = dispatched.single().fileAccessScope
        assertEquals(KOTLIN_ONLY, scope)
        assertEquals(agent.effectiveFileAccess, scope)
    }

    @Test
    fun `the scope on the request is the composition rather than the topmost spark`() {
        val dispatched = CopyOnWriteArrayList<ExecutionRequest<*>>()
        val agent = agentWith(
            dispatched,
            ScopingSpark(
                FileAccessScope(
                    readPatterns = setOf("**/*"),
                    writePatterns = setOf("**/*"),
                    forbiddenPatterns = setOf("**/.env"),
                ),
            ),
            ScopingSpark(KOTLIN_ONLY),
        )

        agent.runLLMToExecuteTask(step(), emptyList())
        val scope = requireNotNull(dispatched.single().fileAccessScope)
        assertTrue(scope.allowsWrite("src/Thing.kt"))
        assertFalse(scope.allowsWrite("README.md"), "the narrower spark's write list wins")
        assertFalse(scope.allowsWrite("build/Thing.kt"), "the first spark's deny-list still holds")
        assertFalse(scope.allowsWrite(".env"), "deny-lists union across the stack")
    }

    @Test
    fun `an unsparked agent dispatches a permissive scope rather than none`() {
        val dispatched = CopyOnWriteArrayList<ExecutionRequest<*>>()
        val agent = agentWith(dispatched)

        agent.runLLMToExecuteTask(step(), emptyList())
        assertEquals(
            FileAccessScope.Permissive,
            dispatched.single().fileAccessScope,
            "no spark constrains files, so the composition is permissive — the request still " +
                "states a scope, it just does not narrow anything",
        )
    }

    @Test
    fun `a spark pushed after construction narrows the next dispatch`() {
        val dispatched = CopyOnWriteArrayList<ExecutionRequest<*>>()
        val agent = agentWith(dispatched)

        agent.runLLMToExecuteTask(step(), emptyList())
        agent.spark<SparkBasedAgent<CodeState>>(ScopingSpark(KOTLIN_ONLY))
        agent.runLLMToExecuteTask(step(), emptyList())
        assertEquals(FileAccessScope.Permissive, dispatched.first().fileAccessScope)
        assertEquals(
            KOTLIN_ONLY,
            dispatched.last().fileAccessScope,
            "the stack is mutable for the agent's lifetime, so the scope has to be read live",
        )
    }

    /**
     * The step `runLLMToExecuteTask` is handed and dispatches directly. Since
     * AMPR-396 it no longer re-plans the task into a sub-plan first, so the
     * task itself has to nominate the tool.
     */
    private fun step(): Task.CodeChange = Task.CodeChange(
        id = "step-1-parent-task",
        status = TaskStatus.Pending,
        description = "touch a file",
        toolId = TOOL,
    )

    private fun agentWith(
        dispatched: MutableList<ExecutionRequest<*>>,
        vararg sparks: Spark,
    ): SparkBasedAgent<CodeState> {
        val tool = FunctionTool<ExecutionContext.NoChanges>(
            id = TOOL,
            name = "Recording $TOOL",
            description = "test recording tool",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { request -> succeed(request) },
        )
        val reasoning = AgentReasoning.createForTesting(executorId = "file-access-test") {
            onPlanning { _, _ -> error("executing a step must not re-plan it (AMPR-396)") }
            onToolExecution { _, request ->
                dispatched += request
                succeed(request)
            }
        }
        val agent = SparkBasedAgent(
            agentId = "file-access-agent",
            cognitiveAffinity = CognitiveAffinity.ANALYTICAL,
            initialState = CodeState.blank,
            _additionalTools = setOf(tool),
            _aiConfiguration = FakeAIConfiguration(),
            _reasoningOverride = reasoning,
        )
        sparks.forEach { agent.spark<SparkBasedAgent<CodeState>>(it) }
        return agent
    }

    private fun succeed(request: ExecutionRequest<*>): ExecutionOutcome {
        val now = Clock.System.now()
        return ExecutionOutcome.NoChanges.Success(
            executorId = request.context.executorId,
            ticketId = request.context.ticket.id,
            taskId = request.context.task.id,
            executionStartTimestamp = now,
            executionEndTimestamp = now,
            message = "recorded",
        )
    }
}
