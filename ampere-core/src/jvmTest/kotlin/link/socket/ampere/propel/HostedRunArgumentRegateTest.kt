package link.socket.ampere.propel

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepositoryImpl
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.memory.MemoryStore
import link.socket.ampere.memory.memoryStoreOf
import link.socket.ampere.roster.PromptRef
import link.socket.ampere.roster.RoleConfig
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.RosterConfig
import link.socket.ampere.roster.SeatTool

/**
 * AMPR-454 (AMPR-385 row H25): the plan gate sees arguments, and is asked again if they
 * change.
 *
 * Each step of `HostedRun.plan` carries its arguments and their hash over canonical JSON.
 * A step whose tool is about to receive arguments other than the ones the gate approved —
 * here because the planner left a required one out and the schema strategy's model call
 * filled it in — goes back to [PlanGate.reviewChangedStep] before the tool runs, and runs
 * only if the gate approves exactly those arguments.
 *
 * Driven through the production wiring like [HostedRunHeldTest] — real doors onto one
 * store, a real `FunctionExecutor`, the real `SchemaParameterStrategy` behind a counting
 * fake transport — because the comparison has to happen on the arguments the tool will
 * actually receive, which only exist once every layer above the tool has run.
 *
 * `runBlocking`, not `runTest`: a door's publish hops to the IO dispatcher and a virtual
 * clock walks straight past it.
 */
class HostedRunArgumentRegateTest {

    private lateinit var scope: CoroutineScope
    private lateinit var door: InMemoryEventDoor.Handle
    private lateinit var store: MemoryStore

    /** Requests the send tool was dispatched with. */
    private val sent = CopyOnWriteArrayList<ExecutionRequest<*>>()

    /** Requests the search tool was dispatched with. */
    private val searched = CopyOnWriteArrayList<ExecutionRequest<*>>()

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        door = InMemoryEventDoor.open(agentId = SDK_ID, scope = scope)
        store = memoryStoreOf(
            knowledge = KnowledgeRepositoryImpl(door.database, door.driver),
            outcomes = OutcomeMemoryRepositoryImpl(door.database),
        )
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        door.close()
    }

    // ========================================================================
    // The hash
    // ========================================================================

    @Test
    fun `a key-order change keeps the hash and a value change does not`() {
        val written = json("""{"to": "ops@example.com", "meta": {"b": 2, "a": [1, {"y": 1, "x": 2}]}}""")
        val reordered = json("""{"meta": {"a": [1, {"x": 2, "y": 1}], "b": 2}, "to": "ops@example.com"}""")
        val changed = json("""{"to": "ops@example.com", "meta": {"b": 3, "a": [1, {"y": 1, "x": 2}]}}""")
        val arrayReordered = json("""{"to": "ops@example.com", "meta": {"b": 2, "a": [{"y": 1, "x": 2}, 1]}}""")

        assertEquals(StepArguments.hash(written), StepArguments.hash(reordered))
        assertTrue(StepArguments.same(written, reordered))
        assertNotEquals(StepArguments.hash(written), StepArguments.hash(changed))
        assertNotEquals(
            StepArguments.hash(written),
            StepArguments.hash(arrayReordered),
            "array order is part of the value",
        )
        assertEquals(
            """{"meta":{"a":[1,{"x":2,"y":1}],"b":2},"to":"ops@example.com"}""",
            StepArguments.canonical(written),
        )
    }

    @Test
    fun `no arguments and an empty object hash the same`() {
        val none = StepArguments.hash(null)
        assertEquals(none, StepArguments.hash(JsonNull))
        assertEquals(none, StepArguments.hash(JsonObject(emptyMap())))
        assertEquals(none, Task.Step(id = "s", status = GOAL.status, description = "d").argumentsHash)
    }

    // ========================================================================
    // The gate
    // ========================================================================

    @Test
    fun `the gate sees each step's arguments and unchanged inline arguments are not reviewed again`() =
        runBlocking<Unit> {
            val gate = RecordingGate()
            // Keys in another order than the schema validates them into: the same arguments.
            val inline = """{"body": "The deploy is done", "to": "ops@example.com"}"""
            val host = seat(HOST_ID, HOST_ROLE) { planJson(sendStep(inline)) }
            val doer = seat(DOER_ID, DOER_ROLE) { "unused" }

            val run = openRun(host, doer, gate)
            val gated = assertIs<Task.Step>(run.plan.tasks.single())
            val result = run.execute()
            run.close()

            val shown = assertIs<Task.Step>(gate.reviewed.single().tasks.single())
            assertEquals(json(inline), shown.arguments, "the gate was shown the step's arguments")
            assertEquals(StepArguments.hash(json(inline)), gated.argumentsHash, "and the plan exposes their hash")

            assertTrue(gate.changed.isEmpty(), "unchanged arguments are not reviewed again")
            assertEquals(1, gate.reviewed.size)
            assertEquals(json(inline), sent.single().arguments, "the tool ran with the approved arguments")
            assertEquals(0, host.parameterCalls(), "and no parameter call was made")
            assertEquals(RunStatus.Succeeded, result.status)
        }

    @Test
    fun `a strategy that changes an argument sends the step back to the gate once`() = runBlocking<Unit> {
        val gate = RecordingGate()
        val host = seat(HOST_ID, HOST_ROLE, parameters = GENERATED) { planJson(sendStep(PARTIAL)) }
        val doer = seat(DOER_ID, DOER_ROLE) { "unused" }

        val run = openRun(host, doer, gate)
        val result = run.execute()
        run.close()

        val (step, approved, actual) = gate.changed.single()
        assertEquals(SeatTool(HOST_ROLE, SEND_TOOL).id, step.toolId)
        assertEquals(json(PARTIAL), approved, "the arguments the gate approved")
        assertEquals(json(GENERATED), actual, "the arguments the tool would receive")

        assertEquals(1, host.parameterCalls(), "the schema strategy's one parameter call")
        assertEquals(json(GENERATED), sent.single().arguments, "the step ran with the approved values")
        assertEquals(RunStatus.Succeeded, result.status)
        assertEquals(
            StepArguments.hash(json(GENERATED)),
            assertIs<Task.Step>(run.plan.tasks.single()).argumentsHash,
            "the plan now carries the arguments the step ran with",
        )
    }

    @Test
    fun `a gate that stops on the changed step ends the run Stopped and nothing after it runs`() =
        runBlocking<Unit> {
            val gate = RecordingGate { _, _, _ -> PlanDecision.Stopped("not with that body") }
            val host = seat(HOST_ID, HOST_ROLE, parameters = GENERATED) { planJson(sendStep(PARTIAL), searchStep()) }
            val doer = seat(DOER_ID, DOER_ROLE) { "unused" }

            val run = openRun(host, doer, gate, policy = RunPolicy(cycles = 3, planGate = gate))
            val result = run.execute()
            run.close()

            assertEquals(RunStatus.Stopped, result.status)
            assertTrue(sent.isEmpty(), "the changed step did not run")
            assertTrue(searched.isEmpty(), "and neither did the step after it")
            assertEquals(2, result.stepOutcomes.size)
            result.stepOutcomes.forEach { assertIs<StepOutcome.Skipped>(it, "a stop is not a fault") }
            assertTrue(
                (result.stepOutcomes[0] as StepOutcome.Skipped).reason.contains("not with that body"),
                "the gate's reason is on the record",
            )
            assertEquals(1, result.cyclesUsed, "no further cycle is planned")
            assertEquals(1, host.planCalls())
            assertTrue(run.plan.tasks.isEmpty(), "a stopped run has nothing about to run")
            assertTrue(
                events(run.runId).none { it is ToolEvent.ToolExecutionStarted },
                "no tool was dispatched",
            )
        }

    @Test
    fun `a gate that removes the changed step skips it and the run goes on`() = runBlocking<Unit> {
        val gate = RecordingGate { _, _, _ -> PlanDecision.Approved(Plan.blank) }
        val host = seat(HOST_ID, HOST_ROLE, parameters = GENERATED) { planJson(sendStep(PARTIAL), searchStep()) }
        val doer = seat(DOER_ID, DOER_ROLE) { "unused" }

        val run = openRun(host, doer, gate)
        val result = run.execute()
        run.close()

        assertTrue(sent.isEmpty(), "the removed step did not run")
        val skipped = assertIs<StepOutcome.Skipped>(result.stepOutcomes[0])
        assertEquals("Skipped: ${StepArgumentGate.REMOVED}", skipped.reason)
        assertIs<StepOutcome.Success>(result.stepOutcomes[1], "the next step ran")
        assertEquals(1, searched.size)
        assertEquals(RunStatus.Succeeded, result.status)
    }

    @Test
    fun `a gate that only implements review is asked about a one-step plan with the new arguments`() =
        runBlocking<Unit> {
            val reviewed = CopyOnWriteArrayList<Plan>()
            val gate = object : PlanGate {
                override suspend fun review(plan: Plan): PlanDecision {
                    reviewed += plan
                    return PlanDecision.Approved(plan)
                }
            }
            val host = seat(HOST_ID, HOST_ROLE, parameters = GENERATED) { planJson(sendStep(PARTIAL), searchStep()) }
            val doer = seat(DOER_ID, DOER_ROLE) { "unused" }

            val run = openRun(host, doer, gate)
            val result = run.execute()
            run.close()

            assertEquals(2, reviewed.size, "the plan, then the changed step")
            val again = assertIs<Task.Step>(reviewed[1].tasks.single())
            assertEquals(json(GENERATED), again.arguments)
            assertEquals(json(GENERATED), sent.single().arguments)
            assertEquals(RunStatus.Succeeded, result.status)
        }

    @Test
    fun `a gate that rewrites the arguments does not get them run`() = runBlocking<Unit> {
        val rewritten = json("""{"to": "ops@example.com", "body": "Something else"}""")
        val gate = RecordingGate { step, _, _ ->
            val swapped = step.copy(arguments = rewritten)
            PlanDecision.Approved(Plan.ForTask(task = swapped, tasks = listOf(swapped)))
        }
        val host = seat(HOST_ID, HOST_ROLE, parameters = GENERATED) { planJson(sendStep(PARTIAL)) }
        val doer = seat(DOER_ID, DOER_ROLE) { "unused" }

        val run = openRun(host, doer, gate)
        val result = run.execute()
        run.close()

        assertTrue(sent.isEmpty(), "neither the generated nor the rewritten arguments ran")
        assertIs<StepOutcome.Failure>(result.stepOutcomes.single())
        assertEquals(RunStatus.Failed, result.status)
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    /** A gate that approves every plan and records what it was asked. */
    private class RecordingGate(
        private val onChanged: ((Task.Step, JsonElement?, JsonElement) -> PlanDecision)? = null,
    ) : PlanGate {

        val reviewed = CopyOnWriteArrayList<Plan>()
        val changed = CopyOnWriteArrayList<Triple<Task.Step, JsonElement?, JsonElement>>()

        override suspend fun review(plan: Plan): PlanDecision {
            reviewed += plan
            return PlanDecision.Approved(plan)
        }

        override suspend fun reviewChangedStep(
            step: Task.Step,
            approved: JsonElement?,
            actual: JsonElement,
        ): PlanDecision {
            changed += Triple(step, approved, actual)
            return onChanged?.invoke(step, approved, actual) ?: super.reviewChangedStep(step, approved, actual)
        }
    }

    private class Seat(
        val descriptor: HostedAgent,
        val transport: CountingUpstreamLlmClient,
    ) {
        fun planCalls(): Int = transport.prompts.count { it.contains("\"steps\"") }

        fun parameterCalls(): Int = transport.prompts.count { it.contains(PARAMETER_PROMPT) }
    }

    /**
     * A seat whose transport answers PERCEIVE, PLAN with [answering], and the schema
     * strategy's parameter call with [parameters]. The send tool is the host seat's, so
     * the host's transport is the one its parameter call reaches.
     */
    private fun seat(
        id: String,
        role: RoleId,
        parameters: String = "unused",
        answering: () -> String,
    ): Seat {
        val transport = CountingUpstreamLlmClient { prompt ->
            when {
                prompt.contains(PARAMETER_PROMPT) -> parameters
                role == HOST_ROLE -> hostAnswers(answering)(prompt)
                else -> answering()
            }
        }
        return Seat(
            descriptor = HostedAgent(
                id = id,
                role = role,
                charter = "You are the ${role.value} of this run.",
                aiConfiguration = AI_CONFIGURATION,
                upstreamLlmClient = transport,
                memory = store,
            ),
            transport = transport,
        )
    }

    private suspend fun openRun(
        host: Seat,
        doer: Seat,
        gate: PlanGate,
        policy: RunPolicy = RunPolicy(planGate = gate),
    ): HostedRun = rosterRunHost(createEventApi = door::doorFor, agentScope = scope).open(
        roster = RosterConfig(
            host = HOST_ROLE,
            roles = listOf(
                // The gated tool is the host's: the planner is shown the argument schemas
                // of the host seat's own tools, so only those keep inline arguments.
                RoleConfig(id = HOST_ROLE, title = "Host", instructions = PROMPT, tools = setOf(SEND_TOOL)),
                RoleConfig(id = DOER_ROLE, title = "Doer", instructions = PROMPT, tools = setOf(SEARCH_TOOL)),
            ),
        ),
        seats = mapOf(HOST_ROLE to host.descriptor, DOER_ROLE to doer.descriptor),
        goal = GOAL,
        tools = setOf<Tool<*>>(
            sendTool(),
            recordingTool(SEARCH_TOOL, "found it", searched),
        ),
        policy = policy,
    )

    /** A tool that declares its arguments, records what it was dispatched with, and succeeds. */
    private fun sendTool(): FunctionTool<ExecutionContext.NoChanges> = FunctionTool(
        id = SEND_TOOL,
        name = "Send a message",
        description = "sends a message on the person's behalf",
        requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
        executionFunction = { request ->
            sent += request
            val now = Clock.System.now()
            ExecutionOutcome.NoChanges.Success(
                executorId = request.context.executorId,
                ticketId = request.context.ticket.id,
                taskId = request.context.task.id,
                executionStartTimestamp = now,
                executionEndTimestamp = now,
                message = "sent",
            )
        },
        argumentSchema = json(
            """
            {
              "properties": {
                "to":   { "type": "string", "description": "Who receives it" },
                "body": { "type": "string", "description": "What it says" }
              },
              "required": ["to", "body"]
            }
            """,
        ),
    )

    /** One plan step for the send tool, with its arguments inline as the planner wrote them. */
    private fun sendStep(arguments: String): String =
        """{"description": "Send the update", "toolToUse": "${SeatTool(HOST_ROLE, SEND_TOOL).id}", """ +
            """"seat": "${HOST_ROLE.value}", "requiresPreviousStep": false, "arguments": $arguments}"""

    private fun searchStep(): String =
        planStep("Search the archive", SeatTool(DOER_ROLE, SEARCH_TOOL).id, DOER_ROLE.value)

    private fun events(runId: String): List<Event> =
        door.database.eventStoreQueries.getEventsByRunId(runId).executeAsList()
            .map { DEFAULT_JSON.decodeFromString(Event.serializer(), it.payload) }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private companion object {
        const val SDK_ID = "hosted-run-argument-regate-test-sdk"
        const val HOST_ID = "seat-host"
        const val DOER_ID = "seat-doer"
        const val SEND_TOOL = "send_message"
        const val SEARCH_TOOL = "search"

        /** How the schema strategy's parameter prompt introduces itself. */
        const val PARAMETER_PROMPT = "Fill in the call arguments for one tool invocation."

        /** The planner left `body` out, so the schema strategy has to fill it in. */
        const val PARTIAL = """{"to": "ops@example.com"}"""

        /** What the doer seat's parameter call answers. */
        const val GENERATED = """{"to": "ops@example.com", "body": "The deploy is done"}"""

        val GOAL = goal("Tell ops the deploy is done", id = "goal-ampr-454")
        val HOST_ROLE = RoleId("planner")
        val DOER_ROLE = RoleId("scout")
        val PROMPT = PromptRef(id = "test-prompt", version = 1)
        val AI_CONFIGURATION = AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = AIModel_Claude.Sonnet_5,
        )
    }
}
