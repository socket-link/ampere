package link.socket.ampere.agents.execution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic

/**
 * AMPR-411: which of the engine's four parameter sources a dispatch lands on, counted in model
 * calls.
 *
 * The count is the assertion that matters. A schema-described tool whose arguments the plan
 * already carries must cost *zero* parameter calls — that saving is the point of the row, and
 * it is invisible to an outcome-shaped assertion because a redundant call produces the same
 * success. Each test records both the prompts sent and the arguments the tool was dispatched
 * with, so a path that quietly asks the model anyway fails here rather than only on a bill.
 */
class ToolExecutionEngineSchemaStrategyTest {

    @Test
    fun `a tool with a schema and no strategy is filled by one parameter call`() = runTest {
        val harness = harness(answer = """{"query": "quarterly report", "scope": "all"}""")

        val outcome = harness.engine.execute(harness.tool, request())

        assertIs<ExecutionOutcome.NoChanges.Success>(outcome)
        assertEquals(1, harness.prompts.size)
        val dispatched = assertNotNull(harness.dispatchedArguments.single())
        assertEquals("quarterly report", dispatched.argumentContent("query"))
        assertEquals("all", dispatched.argumentContent("scope"))
        assertEquals("20", dispatched.argumentContent("limit"))
    }

    @Test
    fun `the parameter prompt renders the tool's own schema`() = runTest {
        val harness = harness(answer = """{"query": "quarterly report"}""")

        harness.engine.execute(harness.tool, request())

        val prompt = harness.prompts.single()
        assertTrue("- `query` (string, required) — What to search for" in prompt)
        assertTrue("Allowed values: \"inbox\", \"all\"." in prompt)
    }

    @Test
    fun `the parameter prompt renders the earlier steps' results`() = runTest {
        val harness = harness(answer = """{"query": "quarterly report"}""")
        val earlier = StepOutcome.Success(
            id = "step-1",
            stepDescription = "list the mailboxes",
            startTimestamp = Instant.fromEpochSeconds(0),
            endTimestamp = Instant.fromEpochSeconds(1),
            details = "found: inbox, archive",
        )

        harness.engine.execute(harness.tool, request(priorResults = listOf(earlier)))

        // AMPR-412 (H17): the engine's own schema strategy is told what came before, the way
        // a hand-written strategy is — and still through one parameter call.
        val prompt = harness.prompts.single()
        assertTrue("## Results of earlier steps" in prompt)
        assertTrue("list the mailboxes" in prompt)
        assertTrue("found: inbox, archive" in prompt)
    }

    @Test
    fun `valid inline arguments dispatch with no parameter call at all`() = runTest {
        val harness = harness(answer = """{"query": "the model was asked anyway"}""")

        val outcome = harness.engine.execute(
            harness.tool,
            request(
                arguments = buildJsonObject {
                    put("query", "quarterly report")
                    put("scope", "inbox")
                },
            ),
        )

        assertIs<ExecutionOutcome.NoChanges.Success>(outcome)
        assertEquals(emptyList<String>(), harness.prompts)
        val dispatched = assertNotNull(harness.dispatchedArguments.single())
        assertEquals("quarterly report", dispatched.argumentContent("query"))
        assertEquals("inbox", dispatched.argumentContent("scope"))
    }

    @Test
    fun `inline arguments are normalised and defaulted before dispatch`() = runTest {
        val harness = harness(answer = "{}")

        harness.engine.execute(
            harness.tool,
            request(
                arguments = buildJsonObject {
                    put("query", "quarterly report")
                    put("stray", "not in the schema")
                },
            ),
        )

        assertEquals(emptyList<String>(), harness.prompts)
        val dispatched = assertNotNull(harness.dispatchedArguments.single())
        assertEquals(setOf("query", "limit"), dispatched.keys)
        assertEquals("20", dispatched.argumentContent("limit"))
    }

    @Test
    fun `inline arguments that fail the schema fall back to the parameter call`() = runTest {
        val harness = harness(answer = """{"query": "quarterly report"}""")

        val outcome = harness.engine.execute(
            harness.tool,
            // `scope` is outside the declared enum, so these cannot be dispatched as they are.
            request(arguments = buildJsonObject { put("scope", "archive") }),
        )

        assertIs<ExecutionOutcome.NoChanges.Success>(outcome)
        assertEquals(1, harness.prompts.size)
        // The model's answer replaces the inline set wholesale rather than merging into it:
        // half-planner, half-model arguments would belong to no one validated object.
        val dispatched = assertNotNull(harness.dispatchedArguments.single())
        assertEquals(setOf("query", "limit"), dispatched.keys)
        assertEquals("quarterly report", dispatched.argumentContent("query"))
    }

    @Test
    fun `a schema that declares no arguments dispatches with no parameter call`() = runTest {
        val harness = harness(answer = """{"query": "the model was asked anyway"}""")
        val argumentless = harness.tool.copy(argumentSchema = JsonObject(emptyMap()))

        val outcome = harness.engine.execute(argumentless, request())

        assertIs<ExecutionOutcome.NoChanges.Success>(outcome)
        assertEquals(emptyList<String>(), harness.prompts)
        assertTrue(assertNotNull(harness.dispatchedArguments.single()).isEmpty())
    }

    @Test
    fun `an answer missing a required argument fails the dispatch without running the tool`() = runTest {
        val harness = harness(answer = """{"scope": "inbox"}""")

        val outcome = harness.engine.execute(harness.tool, request())

        val failure = assertIs<ExecutionOutcome.NoChanges.Failure>(outcome)
        assertTrue("query" in failure.message)
        assertEquals(1, harness.prompts.size)
        assertEquals(emptyList<JsonObject?>(), harness.dispatchedArguments)
    }

    @Test
    fun `a registered strategy wins over the tool's schema`() = runTest {
        val harness = harness(answer = """{"query": "quarterly report"}""")
        harness.engine.registerStrategy(TOOL_ID, HandWrittenStrategy)

        harness.engine.execute(harness.tool, request())

        // `llmProvider` is handed the combined "System: … User: …" prompt, so the strategy's
        // own text is the tail of it.
        val prompt = harness.prompts.single()
        assertTrue(prompt.endsWith(HandWrittenStrategy.PROMPT))
        assertTrue("`query`" !in prompt)
        // The hand-written strategy promotes a context and states no arguments; the engine
        // must not quietly add the schema's defaults on top of what it produced.
        assertNull(harness.dispatchedArguments.single())
    }

    @Test
    fun `a tool with neither a strategy nor a schema is dispatched unchanged`() = runTest {
        val harness = harness(answer = "{}")
        val schemaless = harness.tool.copy(argumentSchema = null)

        val outcome = harness.engine.execute(schemaless, request())

        assertIs<ExecutionOutcome.NoChanges.Success>(outcome)
        assertEquals(emptyList<String>(), harness.prompts)
        assertNull(harness.dispatchedArguments.single())
    }

    /**
     * The engine under test, the prompts its parameter calls sent, and the arguments each
     * dispatch reached the tool with.
     */
    private class Harness(
        val engine: ToolExecutionEngine,
        val tool: FunctionTool<ExecutionContext>,
        val prompts: List<String>,
        val dispatchedArguments: List<JsonObject?>,
    )

    private fun harness(answer: String): Harness {
        val prompts = mutableListOf<String>()
        val dispatchedArguments = mutableListOf<JsonObject?>()
        val executor = FunctionExecutor.create()
        val llmService = AgentLLMService(
            AgentConfiguration(
                agentDefinition = WriteCodeAgent,
                aiConfiguration = AIConfiguration_Default(
                    provider = AIProvider_Anthropic,
                    model = AIModel_Claude.Sonnet_5,
                ),
                llmProvider = { prompt ->
                    prompts += prompt
                    answer
                },
            ),
        )
        return Harness(
            engine = ToolExecutionEngine(
                llmService = llmService,
                executor = executor,
                executorId = executor.id,
            ),
            tool = recordingTool(dispatchedArguments),
            prompts = prompts,
            dispatchedArguments = dispatchedArguments,
        )
    }

    private fun recordingTool(dispatched: MutableList<JsonObject?>) =
        FunctionTool<ExecutionContext>(
            id = TOOL_ID,
            name = "Search",
            description = "Searches a mailbox for messages matching a query",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            argumentSchema = THREE_FIELD_SCHEMA,
            executionFunction = { executionRequest ->
                dispatched += executionRequest.arguments
                val now = Clock.System.now()
                ExecutionOutcome.NoChanges.Success(
                    executorId = executionRequest.context.executorId,
                    ticketId = executionRequest.context.ticket.id,
                    taskId = executionRequest.context.task.id,
                    executionStartTimestamp = now,
                    executionEndTimestamp = now,
                    message = "searched",
                )
            },
        )

    /** A strategy of the hand-written kind: its own prompt and its own promoted context. */
    private object HandWrittenStrategy : ParameterStrategy {

        const val PROMPT = "fill in the search by hand"

        override fun buildPrompt(
            tool: Tool<*>,
            request: ExecutionRequest<*>,
            intent: String,
        ): String = PROMPT

        override fun parseAndEnrichRequest(
            jsonResponse: String,
            originalRequest: ExecutionRequest<*>,
        ): ExecutionRequest<*> = ExecutionRequest(
            context = ExecutionContext.NoChanges(
                executorId = originalRequest.context.executorId,
                ticket = originalRequest.context.ticket,
                task = originalRequest.context.task,
                instructions = "enriched by hand",
            ),
            constraints = originalRequest.constraints,
        )
    }

    private fun request(
        arguments: JsonObject? = null,
        priorResults: List<StepOutcome> = emptyList(),
    ): ExecutionRequest<ExecutionContext.NoChanges> {
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
                    dueDate = null,
                ),
                task = Task.CodeChange(
                    id = "task-1",
                    status = TaskStatus.Pending,
                    description = "Find last quarter's report",
                ),
                instructions = "Find last quarter's report",
            ),
            constraints = ExecutionConstraints(requireTests = false, requireLinting = false),
            arguments = arguments,
            priorResults = priorResults,
        )
    }

    private companion object {

        const val TOOL_ID = "search"

        val THREE_FIELD_SCHEMA = buildJsonObject {
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "What to search for")
                }
                putJsonObject("scope") {
                    put("type", "string")
                    put("description", "Where to look")
                    putJsonArray("enum") {
                        add("inbox")
                        add("all")
                    }
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Maximum results")
                    put("default", 20)
                }
            }
            putJsonArray("required") { add("query") }
        }
    }
}

/** The primitive content of one dispatched argument, for a terse assertion. */
private fun JsonObject.argumentContent(key: String): String = (getValue(key) as JsonPrimitive).content
