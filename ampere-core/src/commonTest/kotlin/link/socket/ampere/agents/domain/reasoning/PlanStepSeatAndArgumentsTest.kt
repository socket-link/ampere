package link.socket.ampere.agents.domain.reasoning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.AssignedTo
import link.socket.ampere.agents.domain.task.EffortLevel
import link.socket.ampere.agents.domain.task.ExecutionAssignment
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.provider.AIProvider_Google

/**
 * AMPR-410 (AMPR-385 row H11): the planner emits a generic [Task.Step] that
 * names the seat running it and carries a schema-described tool's arguments
 * inline.
 *
 * Two halves, both pinned here because they are one bill: the prompt asks for
 * `seat` and `arguments`, and the parser reads them back. PropelLoop's
 * "everything a phase prompt asks for is parsed" invariant is the reason the
 * prompt assertions sit beside the parse assertions rather than in their own
 * file — a key added to the schema with no reader is output the run paid for
 * and discarded, which is what AMPR-398 found.
 */
class PlanStepSeatAndArgumentsTest {

    // ========================================================================
    // The roster-aware prompt
    // ========================================================================

    @Test
    fun `the prompt lists each seat with the tools it owns`() = runTest {
        val prompt = promptFor(seats = twoSeats, availableTools = allTools)

        assertTrue(prompt.contains("Seats and the tools each one owns:"), prompt)
        assertTrue(prompt.contains("- scout:"), prompt)
        assertTrue(prompt.contains("- inspector:"), prompt)
        // Each tool appears under the seat that owns it.
        assertTrue(
            prompt.substringAfter("- scout:").substringBefore("- inspector:").contains(READ_TOOL_ID),
            "the scout's own tool is listed under the scout\n$prompt",
        )
        assertTrue(
            prompt.substringAfter("- inspector:").contains(VERIFY_TOOL_ID),
            "the inspector's own tool is listed under the inspector\n$prompt",
        )
        assertTrue(
            prompt.contains("A step may nominate only a tool its own seat owns."),
            prompt,
        )
    }

    @Test
    fun `a tool no seat owns is still offered and named as unassigned`() = runTest {
        val prompt = promptFor(seats = twoSeats, availableTools = allTools + orphanTool)

        assertTrue(prompt.contains("Unassigned tools (no seat owns these):"), prompt)
        assertTrue(
            prompt.substringAfter("Unassigned tools").contains(ORPHAN_TOOL_ID),
            "an unclaimed tool is listed rather than silently withdrawn\n$prompt",
        )
    }

    @Test
    fun `the prompt asks for a seat only when the plan has seats`() = runTest {
        val withSeats = promptFor(seats = twoSeats, availableTools = allTools)
        val withoutSeats = promptFor(seats = emptyList(), availableTools = allTools)

        assertTrue(withSeats.contains("\"seat\""), "the roster-aware schema asks for one\n$withSeats")
        assertTrue(
            withSeats.contains("\"scout | inspector\""),
            "the seat key enumerates the seats that exist\n$withSeats",
        )
        assertFalse(
            withoutSeats.contains("seat"),
            "a single-seat plan is never asked which seat runs a step\n$withoutSeats",
        )
        assertTrue(
            withoutSeats.contains("Available Tools:"),
            "and still gets the flat tool list it always got\n$withoutSeats",
        )
    }

    @Test
    fun `the prompt renders a schema only for the tool that declares one`() = runTest {
        val prompt = promptFor(seats = twoSeats, availableTools = allTools)
        val schemas = prompt.substringAfter("Argument schemas.").substringBefore("Create a step-by-step")

        assertTrue(prompt.contains("Argument schemas."), prompt)
        assertTrue(schemas.contains("$VERIFY_TOOL_ID:"), "the schema'd tool is named\n$prompt")
        assertFalse(
            schemas.contains(READ_TOOL_ID),
            "a tool with no schema is not listed among the schemas\n$prompt",
        )
        // AMPR-411's own renderer, so the two phases that ask for this object describe it
        // the same way — and so the model is shown fields rather than a schema to echo.
        assertTrue(schemas.contains("`path` (string"), "the field and its type\n$prompt")
        assertTrue(schemas.contains("required"), "and which fields are required\n$prompt")
        assertTrue(schemas.contains("strict"), "and a field's permitted values\n$prompt")
        assertTrue(prompt.contains("\"arguments\""), "and the step schema asks for them\n$prompt")
    }

    @Test
    fun `the prompt asks for arguments only when some tool declares a schema`() = runTest {
        val prompt = promptFor(seats = twoSeats, availableTools = setOf(readTool))

        assertFalse(prompt.contains("Argument schemas."), prompt)
        assertFalse(
            prompt.contains("\"arguments\""),
            "asking for arguments nothing declared buys output the engine throws away\n$prompt",
        )
    }

    // ========================================================================
    // The parsed plan
    // ========================================================================

    @Test
    fun `every step names a seat that exists and a tool that seat owns`() = runTest {
        val plan = planFrom(TWO_SEAT_PLAN_JSON, seats = twoSeats, availableTools = allTools)

        assertEquals(2, plan.tasks.size)
        val seatNames = twoSeats.associateBy { it.assignedTo }
        plan.tasks.forEach { task ->
            val step = assertNotNull(task as? Task.Step, "a non-code goal plans generic steps")
            val assignedTo = assertNotNull(
                step.assignedTo,
                "a plan with seats assigns every step it can place: ${step.description}",
            )
            val seat = assertNotNull(
                seatNames[assignedTo],
                "step ${step.id} names a seat that exists",
            )
            assertTrue(
                seat.owns(step.toolId),
                "step ${step.id} nominates ${step.toolId} which seat ${seat.seat} must own",
            )
        }
        assertEquals(
            listOf(AssignedTo.Agent("scout-agent"), AssignedTo.Agent("inspector-agent")),
            plan.tasks.map { (it as Task.Step).assignedTo },
        )
    }

    @Test
    fun `a step pairing a real seat with another seat's tool is placed with the owner`() = runTest {
        val plan = planFrom(MISPLACED_TOOL_PLAN_JSON, seats = twoSeats, availableTools = allTools)

        val step = assertNotNull(plan.tasks.single() as? Task.Step)
        assertEquals(VERIFY_TOOL_ID, step.toolId, "the nominated tool is left alone")
        assertEquals(
            AssignedTo.Agent("inspector-agent"),
            step.assignedTo,
            "the seat that owns the tool wins over the seat the planner typed",
        )
    }

    @Test
    fun `a step naming an unknown seat and no placeable tool is left unassigned`() = runTest {
        val plan = planFrom(UNKNOWN_SEAT_PLAN_JSON, seats = twoSeats, availableTools = allTools)

        val step = assertNotNull(plan.tasks.single() as? Task.Step)
        assertNull(
            step.assignedTo,
            "a reasoning step naming a seat the roster does not have is unassigned rather " +
                "than dropped — the run decides what to do with it",
        )
        assertEquals("Think about it", step.description, "and keeps everything else it said")
    }

    @Test
    fun `a schema-described tool carries the planner's arguments inline`() = runTest {
        val plan = planFrom(TWO_SEAT_PLAN_JSON, seats = twoSeats, availableTools = allTools)

        val verifyStep = assertNotNull(
            plan.tasks.map { it as Task.Step }.firstOrNull { it.toolId == VERIFY_TOOL_ID },
        )
        val arguments = assertNotNull(verifyStep.arguments as? JsonObject, "the step carries them")
        assertEquals("src/Main.kt", arguments.getValue("path").jsonPrimitive.content)
        assertEquals("strict", arguments.getValue("mode").jsonPrimitive.content)
    }

    @Test
    fun `a tool declaring no schema carries no inline arguments`() = runTest {
        val plan = planFrom(TWO_SEAT_PLAN_JSON, seats = twoSeats, availableTools = allTools)

        val readStep = assertNotNull(
            plan.tasks.map { it as Task.Step }.firstOrNull { it.toolId == READ_TOOL_ID },
        )
        assertNull(
            readStep.arguments,
            "nothing declared which fields are right for this tool, so the model's guess " +
                "is dropped and its strategy generates them as before",
        )
    }

    @Test
    fun `the seat's execution assignment lands on the step`() = runTest {
        val plan = planFrom(TWO_SEAT_PLAN_JSON, seats = twoSeats, availableTools = allTools)

        assertEquals(
            listOf(
                ExecutionAssignment(model = "small", effort = EffortLevel.LOW),
                ExecutionAssignment(model = "large", effort = EffortLevel.HIGH),
            ),
            plan.tasks.map { (it as Task.Step).execution },
            "the tier comes off the seat the step was placed with and is never asked of the model",
        )
    }

    // ========================================================================
    // Which Task type a plan step is
    // ========================================================================

    @Test
    fun `a code goal still plans code changes`() = runTest {
        val plan = generatorRespondingWith(ONE_STEP_PLAN_JSON).generate(
            task = Task.CodeChange(
                id = "code-goal",
                status = TaskStatus.Pending,
                description = "Rename a helper",
                assignedTo = AssignedTo.Agent("code-agent"),
            ),
            ideas = emptyList(),
            agentRole = "Engineer",
        )

        val step = assertNotNull(
            plan.tasks.single() as? Task.CodeChange,
            "a code agent's steps stay code changes so every reader of one keeps working",
        )
        assertEquals("Rename it", step.description)
        assertEquals(
            AssignedTo.Agent("code-agent"),
            step.assignedTo,
            "with no seats declared the goal's own assignee carries down as it always did",
        )
    }

    @Test
    fun `a non-code goal plans generic steps`() = runTest {
        val plan = generatorRespondingWith(ONE_STEP_PLAN_JSON).generate(
            task = genericGoal,
            ideas = emptyList(),
            agentRole = "Scout",
        )

        val step = assertNotNull(
            plan.tasks.single() as? Task.Step,
            "a step that changes no code is no longer called a code change",
        )
        assertEquals("Rename it", step.description)
        assertNull(step.assignedTo, "no seats were declared so nothing assigned it")
    }

    @Test
    fun `a fallback plan for a non-code goal is a generic step too`() = runTest {
        val plan = generatorRespondingWith("not json at all").generate(
            task = genericGoal,
            ideas = emptyList(),
            agentRole = "Scout",
        )

        assertTrue(
            plan.tasks.single() is Task.Step,
            "the fallback goes through the same factory, so it agrees with the parsed path",
        )
    }

    // ========================================================================
    // Fixtures
    // ========================================================================

    /**
     * A goal that is not a code change. `Task.Step` rather than `Task.Blank`:
     * a blank task short-circuits to `Plan.blank` before any factory runs.
     */
    private val genericGoal = Task.Step(
        id = "generic-goal",
        status = TaskStatus.Pending,
        description = "Check the report",
    )

    private val readTool = testTool(READ_TOOL_ID, "Reads a file")

    private val verifyTool = testTool(
        id = VERIFY_TOOL_ID,
        description = "Verifies a file",
        argumentSchema = buildJsonObject {
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "File to verify")
                }
                putJsonObject("mode") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add("strict")
                        add("lenient")
                    }
                }
            }
            putJsonArray("required") { add("path") }
        },
    )

    private val orphanTool = testTool(ORPHAN_TOOL_ID, "Owned by nobody")

    private val allTools: Set<Tool<*>> = setOf(readTool, verifyTool)

    private val twoSeats = listOf(
        PlanSeat(
            seat = "scout",
            assignedTo = AssignedTo.Agent("scout-agent"),
            toolIds = setOf(READ_TOOL_ID),
            execution = ExecutionAssignment(model = "small", effort = EffortLevel.LOW),
        ),
        PlanSeat(
            seat = "inspector",
            assignedTo = AssignedTo.Agent("inspector-agent"),
            toolIds = setOf(VERIFY_TOOL_ID),
            execution = ExecutionAssignment(model = "large", effort = EffortLevel.HIGH),
        ),
    )

    private fun testTool(
        id: String,
        description: String,
        argumentSchema: JsonObject? = null,
    ): FunctionTool<ExecutionContext> = FunctionTool(
        id = id,
        name = id,
        description = description,
        requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
        executionFunction = { request ->
            val now = Clock.System.now()
            ExecutionOutcome.NoChanges.Success(
                executorId = request.context.executorId,
                ticketId = request.context.ticket.id,
                taskId = request.context.task.id,
                executionStartTimestamp = now,
                executionEndTimestamp = now,
                message = "ran $id",
            )
        },
        argumentSchema = argumentSchema,
    )

    private suspend fun planFrom(
        response: String,
        seats: List<PlanSeat>,
        availableTools: Set<Tool<*>>,
    ): Plan = generatorRespondingWith(response).generate(
        task = genericGoal,
        ideas = emptyList(),
        agentRole = "Host",
        availableTools = availableTools,
        seats = seats,
    )

    /** The prompt the planner actually sent, captured off the provider. */
    private suspend fun promptFor(
        seats: List<PlanSeat>,
        availableTools: Set<Tool<*>>,
    ): String {
        var sent: String? = null
        val generator = PlanGenerator(
            AgentLLMService(
                agentConfiguration = AgentConfiguration(
                    agentDefinition = WriteCodeAgent,
                    aiConfiguration = AIConfiguration_Default(AIProvider_Google, AIModel_Gemini.Flash_2_5),
                    llmProvider = { prompt ->
                        sent = prompt
                        ONE_STEP_PLAN_JSON
                    },
                ),
            ),
        )

        generator.generate(
            task = genericGoal,
            ideas = emptyList(),
            agentRole = "Host",
            availableTools = availableTools,
            seats = seats,
        )

        // `System: …\n\nUser: …`, and the planning prompt is the user half.
        return assertNotNull(sent, "the planner called the provider").substringAfterLast("User: ")
    }

    private fun generatorRespondingWith(response: String): PlanGenerator = PlanGenerator(
        AgentLLMService(
            agentConfiguration = AgentConfiguration(
                agentDefinition = WriteCodeAgent,
                aiConfiguration = AIConfiguration_Default(AIProvider_Google, AIModel_Gemini.Flash_2_5),
                llmProvider = { response },
            ),
        ),
    )

    private companion object {

        const val READ_TOOL_ID = "read_report"
        const val VERIFY_TOOL_ID = "verify_report"
        const val ORPHAN_TOOL_ID = "orphan_tool"

        const val ONE_STEP_PLAN_JSON = """
            {
              "steps": [
                { "description": "Rename it", "toolToUse": null, "requiresPreviousStep": false }
              ],
              "estimatedComplexity": 4,
              "requiresHumanInput": false
            }
        """

        /** Two steps, each naming its own seat; only the second tool has a schema. */
        const val TWO_SEAT_PLAN_JSON = """
            {
              "steps": [
                {
                  "description": "Read the report",
                  "toolToUse": "read_report",
                  "seat": "scout",
                  "arguments": { "path": "src/Main.kt" },
                  "requiresPreviousStep": false
                },
                {
                  "description": "Verify the report",
                  "toolToUse": "verify_report",
                  "seat": "inspector",
                  "arguments": { "path": "src/Main.kt", "mode": "strict" },
                  "requiresPreviousStep": true
                }
              ],
              "estimatedComplexity": 5,
              "requiresHumanInput": false
            }
        """

        /** A real seat paired with the other seat's tool. */
        const val MISPLACED_TOOL_PLAN_JSON = """
            {
              "steps": [
                {
                  "description": "Verify the report",
                  "toolToUse": "verify_report",
                  "seat": "scout",
                  "arguments": { "path": "src/Main.kt" },
                  "requiresPreviousStep": false
                }
              ],
              "estimatedComplexity": 2,
              "requiresHumanInput": false
            }
        """

        /** A tool-less step naming a seat the roster does not have. */
        const val UNKNOWN_SEAT_PLAN_JSON = """
            {
              "steps": [
                {
                  "description": "Think about it",
                  "toolToUse": null,
                  "seat": "archivist",
                  "requiresPreviousStep": false
                }
              ],
              "estimatedComplexity": 1,
              "requiresHumanInput": false
            }
        """
    }
}
