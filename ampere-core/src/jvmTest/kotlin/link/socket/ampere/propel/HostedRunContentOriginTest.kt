package link.socket.ampere.propel

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.domain.memory.MemoryTaskTypes
import link.socket.ampere.agents.domain.outcome.ContentOrigin
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepositoryImpl
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.memory.MemoryStore
import link.socket.ampere.memory.RecallSource
import link.socket.ampere.memory.Recalled
import link.socket.ampere.memory.memoryStoreOf
import link.socket.ampere.roster.PromptRef
import link.socket.ampere.roster.RoleConfig
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.RosterConfig
import link.socket.ampere.roster.SeatTool

/**
 * A hosted run says whether it has shown a model content from outside itself
 * (AMPR-453, AMPR-385 row H24).
 *
 * Every step result carries an origin — the tool's declared one, `External` by default,
 * and for a reasoning step `Own` only when everything it was shown was — and
 * [HostedRun.readExternalContent] goes up the moment an `External` result is handed to
 * a call that renders it into a prompt. These tests drive the production wiring, as
 * [HostedRunCycleTest] does.
 *
 * `runBlocking`, not `runTest`: a door's publish hops to the IO dispatcher and a
 * virtual clock walks straight past it.
 */
class HostedRunContentOriginTest {

    private lateinit var scope: CoroutineScope
    private lateinit var door: InMemoryEventDoor.Handle
    private lateinit var store: MemoryStore

    private val dispatched = CopyOnWriteArrayList<ExecutionRequest<*>>()

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

    @Test
    fun `a run of only Own steps has not read external content`() = runBlocking<Unit> {
        val host = seat(HOST_ID, HOST_ROLE, hostAnswers { searchThenReason() })
        val doer = seat(DOER_ID, DOER_ROLE, fixedAnswer { CONCLUSION })
        val ownSearch = recordingTool(SEARCH_TOOL, TOOL_RESULT, dispatched).copy(resultOrigin = ContentOrigin.Own)

        // Two cycles, so the Own results are also handed to a second PLAN.
        val run = openRun(host, doer, tools = setOf(ownSearch), policy = RunPolicy(cycles = 2))
        val result = run.execute()
        run.close()

        assertEquals(4, result.stepOutcomes.size, "two steps per cycle, two cycles")
        assertTrue(
            result.stepOutcomes.all { it.origin == ContentOrigin.Own },
            "an Own tool, and a reasoning step over Own results only: " +
                result.stepOutcomes.map { it.origin },
        )
        assertFalse(run.readExternalContent, "nothing from outside the run reached a prompt")
        assertFalse(result.readExternalContent, "and the result says the same")
    }

    @Test
    fun `one External result shown to a later step is enough`() = runBlocking<Unit> {
        val host = seat(HOST_ID, HOST_ROLE, hostAnswers { searchThenReason() })
        val doer = seat(DOER_ID, DOER_ROLE, fixedAnswer { CONCLUSION })

        val run = openRun(host, doer, tools = setOf(recordingTool(SEARCH_TOOL, TOOL_RESULT, dispatched)))
        val result = run.execute()
        run.close()

        val (searched, reasoned) = result.stepOutcomes
        assertEquals(ContentOrigin.External, searched.origin, "a tool that declares nothing is External")
        assertEquals(
            ContentOrigin.External,
            reasoned.origin,
            "a reasoning step shown an External result inherits it rather than laundering it",
        )
        assertTrue(run.readExternalContent, "the search result reached the reasoning step's prompt")
        assertTrue(result.readExternalContent)

        val reasoningPrompt = doer.transport.prompts.single { it.contains(REASON_STEP) }
        val lines = reasoningPrompt.lines()
        val entry = lines.indexOfFirst { it.contains("[external] [succeeded] $SEARCH_STEP") }
        assertTrue(
            entry in (lines.indexOf("<<<BEGIN DATA>>>") + 1) until lines.indexOf("<<<END DATA>>>"),
            "and it reached it labelled, inside the data block: $reasoningPrompt",
        )
    }

    @Test
    fun `an External result handed to a later PLAN is enough`() = runBlocking<Unit> {
        val host = seat(
            HOST_ID,
            HOST_ROLE,
            hostAnswers { planJson(planStep(SEARCH_STEP, SEARCH_TOOL.seatId(), DOER_ROLE.value)) },
        )
        val doer = seat(DOER_ID, DOER_ROLE, fixedAnswer { CONCLUSION })
        val gate = FlagReadingGate()

        val run = openRun(
            host,
            doer,
            tools = setOf(recordingTool(SEARCH_TOOL, TOOL_RESULT, dispatched)),
            policy = RunPolicy(cycles = 2, planGate = gate),
        )
        gate.run = run
        val result = run.execute()
        run.close()

        assertEquals(listOf(false, true), gate.seen, "down at the first PLAN, up by the second")
        assertTrue(result.readExternalContent)
    }

    @Test
    fun `an External result nothing is shown afterwards leaves it down`() = runBlocking<Unit> {
        val host = seat(
            HOST_ID,
            HOST_ROLE,
            hostAnswers { planJson(planStep(SEARCH_STEP, SEARCH_TOOL.seatId(), DOER_ROLE.value)) },
        )
        val doer = seat(DOER_ID, DOER_ROLE, fixedAnswer { CONCLUSION })

        val run = openRun(host, doer, tools = setOf(recordingTool(SEARCH_TOOL, TOOL_RESULT, dispatched)))
        val result = run.execute()
        run.close()

        assertEquals(ContentOrigin.External, result.stepOutcomes.single().origin)
        assertFalse(
            result.readExternalContent,
            "the run read outside content, but it was the last thing the run did: no model saw it",
        )
    }

    @Test
    fun `the flag is already up while the step shown the result runs`() = runBlocking<Unit> {
        val host = seat(
            HOST_ID,
            HOST_ROLE,
            hostAnswers {
                planJson(
                    planStep(SEARCH_STEP, SEARCH_TOOL.seatId(), DOER_ROLE.value),
                    planStep(WRITE_STEP, WRITE_TOOL.seatId(), DOER_ROLE.value),
                )
            },
        )
        val doer = seat(DOER_ID, DOER_ROLE, fixedAnswer { CONCLUSION })
        val seenByWrite = CopyOnWriteArrayList<Boolean?>()

        val run = openRun(
            host,
            doer,
            tools = setOf(recordingTool(SEARCH_TOOL, TOOL_RESULT, dispatched), flagReadingTool(seenByWrite)),
        )
        // Where the run is visible is the caller's decision; a write gate that wants to read
        // it from inside a tool puts it in the context the run executes under.
        withContext(run) { run.execute() }
        run.close()

        assertEquals(
            listOf<Boolean?>(true),
            seenByWrite.toList(),
            "the write step was dispatched with the search result, so the flag was up before it ran",
        )
    }

    @Test
    fun `a run that recalled from memory has read external content`() = runBlocking<Unit> {
        // A learning an earlier run stored. Nothing records what that run read, so it comes
        // back External — it is the channel outside text would come back through.
        store.knowledge.storeKnowledge(
            knowledge = Knowledge.FromOutcome(
                outcomeId = "an-earlier-outcome",
                approach = MEMORY_APPROACH,
                learnings = MEMORY_LEARNINGS,
                timestamp = Clock.System.now(),
            ),
            tags = listOf(SCOPE),
            taskType = MemoryTaskTypes.GENERIC,
            runId = "an-earlier-run",
        ).getOrThrow()
        val host = seat(
            HOST_ID,
            HOST_ROLE,
            hostAnswers { planJson(planStep(SEARCH_STEP, SEARCH_TOOL.seatId(), DOER_ROLE.value)) },
        )
        val doer = seat(DOER_ID, DOER_ROLE, fixedAnswer { CONCLUSION })
        val ownSearch = recordingTool(SEARCH_TOOL, TOOL_RESULT, dispatched).copy(resultOrigin = ContentOrigin.Own)

        val run = openRun(host, doer, tools = setOf(ownSearch))
        assertTrue(run.readExternalContent, "up before EXECUTE: the first PLAN was already shown it")
        val result = run.execute()
        run.close()

        assertTrue(result.readExternalContent, "every step was Own, but the recall was not")
        val planPrompt = host.transport.prompts.single { it.contains("\"steps\"") }
        assertInsideBlock(planPrompt, "- [external] $MEMORY_APPROACH", "the recalled section")
        assertInsideBlock(planPrompt, "- [external] Approach: $MEMORY_APPROACH", "the planner's past knowledge")
    }

    @Test
    fun `a run that recalled from a consumer's corpus has read external content`() = runBlocking<Unit> {
        val host = seat(
            HOST_ID,
            HOST_ROLE,
            hostAnswers { planJson(planStep(SEARCH_STEP, SEARCH_TOOL.seatId(), DOER_ROLE.value)) },
            scopes = setOf(SCOPE),
        )
        val doer = seat(DOER_ID, DOER_ROLE, fixedAnswer { CONCLUSION })
        val ownSearch = recordingTool(SEARCH_TOOL, TOOL_RESULT, dispatched).copy(resultOrigin = ContentOrigin.Own)
        val corpus = RecallSource { _, _, _ ->
            listOf(Recalled(id = "doc-1", title = DOC_TITLE, text = DOC_TEXT, score = 1.0))
        }

        val run = openRun(
            host,
            doer,
            tools = setOf(ownSearch),
            policy = RunPolicy(recall = RecallPolicy(sources = listOf(corpus))),
        )
        val result = run.execute()
        run.close()

        assertTrue(result.readExternalContent, "a consumer's corpus is outside the run")
        val planPrompt = host.transport.prompts.single { it.contains("\"steps\"") }
        assertInsideBlock(planPrompt, "- [external] $DOC_TITLE — $DOC_TEXT", "the recalled section")
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    /** [expected] is on a line of [prompt] that sits between a data block's two markers. */
    private fun assertInsideBlock(prompt: String, expected: String, where: String) {
        val lines = prompt.lines()
        val at = lines.indexOfFirst { it.contains(expected) }
        assertTrue(at >= 0, "$where: '$expected' is not in the prompt: $prompt")
        val opened = lines.subList(0, at).indexOfLast { it == "<<<BEGIN DATA>>>" }
        val closed = lines.subList(0, at).indexOfLast { it == "<<<END DATA>>>" }
        assertTrue(opened > closed, "$where: '$expected' is outside any data block: $prompt")
    }

    private class Seat(
        val descriptor: HostedAgent,
        val transport: CountingUpstreamLlmClient,
    )

    /**
     * A gate that approves every plan and notes the run's flag each time it is asked.
     *
     * The first review happens inside `open`, before the run is handed back, so there is
     * no run to read yet — and nothing has executed, so the flag could only be down.
     */
    private class FlagReadingGate : PlanGate {
        var run: HostedRun? = null
        val seen = CopyOnWriteArrayList<Boolean>()

        override suspend fun review(plan: Plan): PlanDecision {
            seen += run?.readExternalContent ?: false
            return PlanDecision.Approved(plan)
        }
    }

    /** A search on the doer seat, then a reasoning step on the same seat. */
    private fun searchThenReason(): String = planJson(
        planStep(SEARCH_STEP, SEARCH_TOOL.seatId(), DOER_ROLE.value),
        planStep(REASON_STEP, null, DOER_ROLE.value),
    )

    /** A tool that records the run's flag as its step sees it, and succeeds. */
    private fun flagReadingTool(seen: MutableList<Boolean?>): Tool<*> = FunctionTool<ExecutionContext.NoChanges>(
        id = WRITE_TOOL,
        name = "Write",
        description = "writes, after checking whether the run has read outside content",
        requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
        executionFunction = { request ->
            seen += currentCoroutineContext()[HostedRun]?.readExternalContent
            val now = Clock.System.now()
            ExecutionOutcome.NoChanges.Success(
                executorId = request.context.executorId,
                ticketId = request.context.ticket.id,
                taskId = request.context.task.id,
                executionStartTimestamp = now,
                executionEndTimestamp = now,
                message = "written",
            )
        },
    )

    private fun seat(
        id: String,
        role: RoleId,
        answer: (String) -> String,
        scopes: Set<String> = emptySet(),
    ): Seat {
        val transport = CountingUpstreamLlmClient(answer)
        return Seat(
            descriptor = HostedAgent(
                id = id,
                role = role,
                charter = "You are the ${role.value} of this run.",
                aiConfiguration = AI_CONFIGURATION,
                upstreamLlmClient = transport,
                memory = store,
                recallScopes = scopes,
            ),
            transport = transport,
        )
    }

    private suspend fun openRun(
        host: Seat,
        doer: Seat,
        tools: Set<Tool<*>>,
        policy: RunPolicy = RunPolicy(),
    ): HostedRun = rosterRunHost(createEventApi = door::doorFor, agentScope = scope).open(
        roster = RosterConfig(
            host = HOST_ROLE,
            roles = listOf(
                RoleConfig(id = HOST_ROLE, title = "Host", instructions = PROMPT),
                RoleConfig(
                    id = DOER_ROLE,
                    title = "Doer",
                    instructions = PROMPT,
                    tools = setOf(SEARCH_TOOL, WRITE_TOOL),
                ),
            ),
        ),
        seats = mapOf(HOST_ROLE to host.descriptor, DOER_ROLE to doer.descriptor),
        goal = goal(GOAL_TEXT, id = GOAL_ID),
        tools = tools,
        policy = policy,
    )

    /** The id this run offers the planner for a tool of the doer seat: `<seat>/<tool>`. */
    private fun String.seatId(): String = SeatTool(DOER_ROLE, this).id

    private companion object {
        const val SDK_ID = "hosted-run-content-origin-test-sdk"
        const val HOST_ID = "seat-host"
        const val DOER_ID = "seat-doer"
        const val SEARCH_TOOL = "search"
        const val WRITE_TOOL = "write"
        const val SEARCH_STEP = "Search for the retry policy"
        const val REASON_STEP = "Decide where the retry belongs"
        const val WRITE_STEP = "Write the retry"
        const val TOOL_RESULT = "found the retry policy in Uploader.kt"
        const val CONCLUSION = "the retry belongs in Uploader.send"
        const val GOAL_TEXT = "Add a retry to the uploader"
        const val GOAL_ID = "goal-ampr-453"
        const val SCOPE = "runbooks"
        const val MEMORY_APPROACH = "Retried the upload with a capped backoff"
        const val MEMORY_LEARNINGS = "Three attempts was enough"
        const val DOC_TITLE = "Uploader runbook"
        const val DOC_TEXT = "The uploader retries on 503"

        val HOST_ROLE = RoleId("planner")
        val DOER_ROLE = RoleId("scout")
        val PROMPT = PromptRef(id = "test-prompt", version = 1)
        val AI_CONFIGURATION = AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = AIModel_Claude.Sonnet_5,
        )
    }
}
