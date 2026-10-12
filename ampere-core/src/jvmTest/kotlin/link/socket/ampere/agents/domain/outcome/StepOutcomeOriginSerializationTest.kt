package link.socket.ampere.agents.domain.outcome

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.McpTool
import link.socket.ampere.data.DEFAULT_JSON

/**
 * AMPR-453: `StepOutcome.origin` is additive on the wire, and its default is the safe one.
 *
 * An `Own` outcome round-trips as `Own`. An `External` one encodes exactly as it did before
 * the field existed, with no `origin` key — `DEFAULT_JSON` encodes defaults, so that takes
 * `@EncodeDefault(NEVER)`. And an outcome stored before the field existed decodes as
 * `External`, because a result nobody marked has to be read as one from outside the run.
 */
class StepOutcomeOriginSerializationTest {

    private val json = DEFAULT_JSON
    private val at = Instant.fromEpochMilliseconds(1_700_000_000_000)

    @Test
    fun `an Own outcome round-trips as Own`() {
        val original: StepOutcome = StepOutcome.Success(
            id = "step-1",
            stepDescription = "decide the approach",
            startTimestamp = at,
            endTimestamp = at,
            details = "retry with a capped backoff",
            origin = ContentOrigin.Own,
        )

        val encoded = json.encodeToString(StepOutcome.serializer(), original)
        val decoded = json.decodeFromString(StepOutcome.serializer(), encoded)

        assertTrue("\"origin\":\"Own\"" in encoded.replace(" ", ""), "an Own origin is written: $encoded")
        assertEquals(original, decoded)
        assertEquals(ContentOrigin.Own, decoded.origin)
    }

    @Test
    fun `every variant round-trips its origin`() {
        val outcomes: List<StepOutcome> = listOf(
            StepOutcome.PartialSuccess("s", "d", at, at, 1, 1, "half", origin = ContentOrigin.Own),
            StepOutcome.Failure("s", "d", at, at, "boom", origin = ContentOrigin.Own),
            StepOutcome.Held("s", "d", at, at, "waiting", origin = ContentOrigin.Own),
            StepOutcome.Skipped("s", "d", at, "skipped", origin = ContentOrigin.Own),
        )

        outcomes.forEach { original ->
            val decoded = json.decodeFromString(
                StepOutcome.serializer(),
                json.encodeToString(StepOutcome.serializer(), original),
            )
            assertEquals(original, decoded)
        }
    }

    @Test
    fun `an External outcome is written without the key`() {
        val outcome: StepOutcome = StepOutcome.Success(
            id = "step-1",
            stepDescription = "search the notes",
            startTimestamp = at,
            endTimestamp = at,
            details = "found it",
        )

        val encoded = json.encodeToString(StepOutcome.serializer(), outcome)

        assertEquals(ContentOrigin.External, outcome.origin, "External is the default")
        assertFalse("origin" in encoded, "so it encodes as it did before the field existed: $encoded")
    }

    @Test
    fun `an outcome stored before the field existed decodes as External`() {
        val stored = """
            {
              "type": "link.socket.ampere.agents.domain.outcome.StepOutcome.Success",
              "id": "step-1",
              "stepDescription": "search the notes",
              "startTimestamp": "2023-11-14T22:13:20Z",
              "endTimestamp": "2023-11-14T22:13:20Z",
              "details": "found it"
            }
        """.trimIndent()

        val decoded = json.decodeFromString(StepOutcome.serializer(), stored)

        assertEquals(ContentOrigin.External, decoded.origin)
    }

    @Test
    fun `a folded outcome's origin is additive on the wire too`() {
        val own: ExecutionOutcome = ExecutionOutcome.NoChanges.Success(
            executorId = "agent",
            ticketId = "ticket",
            taskId = "step-1",
            executionStartTimestamp = at,
            executionEndTimestamp = at,
            message = "the step's report",
            origin = ContentOrigin.Own,
        )
        val external = (own as ExecutionOutcome.NoChanges.Success).copy(origin = ContentOrigin.External)

        val ownJson = json.encodeToString(ExecutionOutcome.serializer(), own)
        val externalJson = json.encodeToString(ExecutionOutcome.serializer(), external)

        assertEquals(own, json.decodeFromString(ExecutionOutcome.serializer(), ownJson))
        assertEquals(ContentOrigin.Own, json.decodeFromString(ExecutionOutcome.serializer(), ownJson).contentOrigin())
        assertFalse("origin" in externalJson, "an External fold encodes as it did before: $externalJson")
        assertEquals(
            ContentOrigin.External,
            json.decodeFromString(ExecutionOutcome.serializer(), externalJson).contentOrigin(),
        )
    }

    @Test
    fun `a tool's results are External unless it declares otherwise`() {
        val function = FunctionTool<ExecutionContext.NoChanges>(
            id = "search",
            name = "Search",
            description = "searches",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { error("not called") },
        )
        val mcp = McpTool(
            id = "remote",
            name = "Remote",
            description = "a server's tool",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            serverId = "server",
            remoteToolName = "remote",
        )

        assertEquals(ContentOrigin.External, function.resultOrigin)
        assertEquals(ContentOrigin.External, mcp.resultOrigin, "a server's answer is outside the run")
        assertEquals(
            ContentOrigin.Own,
            function.copy(resultOrigin = ContentOrigin.Own).copy(id = "seat/search").resultOrigin,
            "a declared origin survives the copy a hosted run makes to namespace the id",
        )
    }
}
