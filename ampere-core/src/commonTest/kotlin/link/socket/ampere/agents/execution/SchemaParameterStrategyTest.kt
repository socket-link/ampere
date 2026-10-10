package link.socket.ampere.agents.execution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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
import link.socket.ampere.agents.domain.cognition.FileAccessScope
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool

/**
 * AMPR-411: `SchemaParameterStrategy` over the ticket's three-field schema.
 *
 * Two halves are pinned here. The prompt, because a schema the model is never shown is a
 * schema the model cannot satisfy — and because the facets that matter (which argument is
 * required, which values an `enum` permits) are the ones a strategy is cheapest to get wrong.
 * And the enrichment, because this strategy is the one that *copies* the request instead of
 * rebuilding it: the hand-written strategies promote a context and can afford to drop the
 * rest, while this one has nothing to promote and must leave the dispatcher's stamps alone.
 */
class SchemaParameterStrategyTest {

    private val strategy = SchemaParameterStrategy(THREE_FIELD_SCHEMA)

    @Test
    fun `the prompt names the tool and the step intent`() {
        val prompt = strategy.buildPrompt(TOOL, request(), "Find last quarter's report")

        assertTrue("`search`" in prompt)
        assertTrue("Searches a mailbox" in prompt)
        assertTrue("Find last quarter's report" in prompt)
    }

    @Test
    fun `the prompt renders every argument facet the schema declares`() {
        val prompt = strategy.buildPrompt(TOOL, request(), "Find last quarter's report")

        assertTrue("- `query` (string, required) — What to search for" in prompt)
        assertTrue("Allowed values: \"inbox\", \"all\"." in prompt)
        assertTrue("default: 20" in prompt)
    }

    @Test
    fun `the prompt renders what the earlier steps produced`() {
        val prompt = strategy.buildPrompt(
            TOOL,
            request(
                priorResults = listOf(
                    StepOutcome.Success(
                        id = "step-1",
                        stepDescription = "list the mailboxes",
                        startTimestamp = Instant.fromEpochSeconds(0),
                        endTimestamp = Instant.fromEpochSeconds(1),
                        details = "found: inbox, archive",
                    ),
                ),
            ),
            "Find last quarter's report",
        )

        // AMPR-412 (H17): the same block every hand-written strategy renders.
        assertTrue("## Results of earlier steps" in prompt)
        assertTrue("Results of the steps already executed in this plan" in prompt)
        assertTrue("list the mailboxes" in prompt)
        assertTrue("found: inbox, archive" in prompt)
        assertTrue(
            prompt.indexOf("found: inbox, archive") < prompt.indexOf("## Arguments"),
            "the results sit inside the context, before the arguments to fill",
        )
    }

    @Test
    fun `the prompt renders what Recall found`() {
        val prompt = strategy.buildPrompt(
            TOOL,
            request(
                knowledge = listOf(
                    Knowledge.FromOutcome(
                        outcomeId = "outcome-1",
                        approach = "listed the mailboxes",
                        learnings = "the archive mailbox is empty",
                        timestamp = Clock.System.now(),
                    ),
                ),
            ),
            "Find last quarter's report",
        )

        assertTrue("## Recalled context" in prompt)
        assertTrue("listed the mailboxes — the archive mailbox is empty" in prompt)
    }

    @Test
    fun `the earlier-steps and recalled sections are absent when there is nothing to show`() {
        val prompt = strategy.buildPrompt(TOOL, request(), "Find last quarter's report")

        assertTrue("## Results of earlier steps" !in prompt)
        assertTrue("## Recalled context" !in prompt)
    }

    @Test
    fun `a valid answer reaches the request as arguments`() {
        val enriched = strategy.parseAndEnrichRequest(
            jsonResponse = """{"query": "quarterly report", "scope": "inbox"}""",
            originalRequest = request(),
        )

        val arguments = assertNotNull(enriched.arguments)
        assertEquals("quarterly report", arguments.content("query"))
        assertEquals("inbox", arguments.content("scope"))
        assertEquals("20", arguments.content("limit"))
    }

    @Test
    fun `an answer wrapped in a markdown fence is still parsed`() {
        val enriched = strategy.parseAndEnrichRequest(
            jsonResponse = "```json\n{\"query\": \"quarterly report\"}\n```",
            originalRequest = request(),
        )

        assertEquals("quarterly report", assertNotNull(enriched.arguments).content("query"))
    }

    @Test
    fun `the enriched request keeps the run id workspace and file access scope`() {
        val original = request()

        val enriched = strategy.parseAndEnrichRequest(
            jsonResponse = """{"query": "quarterly report"}""",
            originalRequest = original,
        )

        assertEquals(original.runId, enriched.runId)
        assertEquals(original.fileAccessScope, enriched.fileAccessScope)
        assertEquals(original.context, enriched.context)
        assertEquals(original.constraints, enriched.constraints)
    }

    @Test
    fun `a missing required argument refuses with the argument named`() {
        val failure = assertFailsWith<SchemaArgumentException> {
            strategy.parseAndEnrichRequest(
                jsonResponse = """{"scope": "inbox"}""",
                originalRequest = request(),
            )
        }

        val violation = assertIs<SchemaViolation.MissingRequired>(failure.violations.single())
        assertEquals("query", violation.field)
        assertTrue("query" in failure.message.orEmpty())
    }

    @Test
    fun `an answer outside a declared enum refuses with the permitted values`() {
        val failure = assertFailsWith<SchemaArgumentException> {
            strategy.parseAndEnrichRequest(
                jsonResponse = """{"query": "quarterly report", "scope": "archive"}""",
                originalRequest = request(),
            )
        }

        assertIs<SchemaViolation.NotInEnum>(failure.violations.single())
        assertTrue("\"inbox\"" in failure.message.orEmpty())
    }

    @Test
    fun `an argument the schema does not name never reaches the request`() {
        val enriched = strategy.parseAndEnrichRequest(
            jsonResponse = """{"query": "quarterly report", "mailbox": "/var/mail"}""",
            originalRequest = request(),
        )

        assertEquals(setOf("query", "limit"), enriched.arguments?.keys)
    }

    @Test
    fun `an answer that is not a JSON object refuses before validation`() {
        val failure = assertFailsWith<IllegalStateException> {
            strategy.parseAndEnrichRequest(
                jsonResponse = """["quarterly report"]""",
                originalRequest = request(),
            )
        }

        assertTrue(failure !is SchemaArgumentException)
    }

    @Test
    fun `the system message and token ceiling are overridable`() {
        val custom = SchemaParameterStrategy(
            schema = THREE_FIELD_SCHEMA,
            systemMessage = "Answer in JSON.",
            maxTokens = 64,
        )

        assertEquals("Answer in JSON.", custom.systemMessage)
        assertEquals(64, custom.maxTokens)
    }

    private fun request(
        knowledge: List<Knowledge> = emptyList(),
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
                knowledgeFromPastMemory = knowledge,
            ),
            constraints = ExecutionConstraints(requireTests = false, requireLinting = false),
            runId = "arc-run-1",
            fileAccessScope = FileAccessScope.Permissive,
            priorResults = priorResults,
        )
    }

    private companion object {

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

        val TOOL = FunctionTool<ExecutionContext>(
            id = "search",
            name = "Search",
            description = "Searches a mailbox for messages matching a query",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            argumentSchema = THREE_FIELD_SCHEMA,
            executionFunction = { executionRequest ->
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
    }
}

/** The primitive content of one enriched argument, for a terse assertion. */
private fun JsonObject.content(key: String): String = (getValue(key) as JsonPrimitive).content
