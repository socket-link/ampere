package link.socket.ampere.domain.arc

import com.aallam.openai.api.chat.ChatChoice
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import link.socket.ampere.agents.config.CognitiveConfig
import link.socket.ampere.agents.config.PhaseSparkConfig
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.CognitivePhaseEvent
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventId
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.event.ProviderCallStartedEvent
import link.socket.ampere.agents.domain.knowledge.BeliefConfidence
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.knowledge.KnowledgeEntry
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepository
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.events.EventStore
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Sensitivity
import link.socket.ampere.field.Subject
import link.socket.ampere.llm.UpstreamLlmClient
import link.socket.ampere.time.MutableClock
import link.socket.ampere.trace.ArcTraceProjection
import okio.Path
import okio.Path.Companion.toPath

/**
 * AMPR-441 (F18): an Arc run enters all six PROPEL phases, and nobody has to opt in.
 *
 * Flow's tick brackets PERCEIVE, RECALL, OBSERVE, PLAN and EXECUTE through the agent's own
 * `PhaseSparkManager`, Pulse brackets LEARN around the learning write, and Charge spawns the
 * agents with brackets on and phase guidance off. So a run's trace shows six phases under its
 * run id, and every prompt the run sends is the one it would send with phases off.
 *
 * Driven through the production wiring — the real runtime and phases, real doors onto one
 * in-memory store, the real phase services behind a scripted transport — so what is asserted is
 * what a run records. `runBlocking`, not `runTest`: every bracket crosses the door's IO hop, which
 * a virtual clock walks straight past.
 */
class ArcPhaseBracketsTest {

    private val clock = MutableClock(START)

    private lateinit var scope: CoroutineScope
    private lateinit var door: InMemoryEventDoor.Handle

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        door = InMemoryEventDoor.open(agentId = DOOR_ID, scope = scope, clock = clock)
    }

    @AfterTest
    fun tearDown() {
        // Cancel before closing: an agent's spark event still in flight would otherwise write to
        // a closed driver.
        scope.cancel()
        door.close()
    }

    @Test
    fun `a one-tick Arc enters all six phases once each in PROPEL order under its run`() = runBlocking<Unit> {
        val runId = "ampr441-six-phases"

        val completed = assertIs<ArcOutcome.Completed>(runtime().execute(GOAL, runId = runId))
        assertEquals(1, completed.flowResult.finalTick, "one tick, so one pass through each phase")
        val agentId = completed.chargeResult.agents.single().id

        // `getEventsByRunId` matches the envelope's `run_id` and nothing else, so every bracket
        // found here was stamped with the run.
        val rows = rowsUnder(runId)
        val bracketRows = rows.filter { decode(it) is CognitivePhaseEvent }
        val brackets = bracketRows.map { decode(it) as CognitivePhaseEvent }
        assertEquals(
            PROPEL.flatMap { listOf("enter $it", "exit $it") },
            brackets.map { it.bracket() },
            "each phase is entered and left exactly once, in PROPEL order, with LEARN after EXECUTE",
        )
        assertTrue(
            brackets.all { it.nestingDepth == 0 },
            "no phase is bracketed inside another; got depths ${brackets.map { it.nestingDepth }}",
        )
        assertTrue(
            bracketRows.all { it.source_id == agentId },
            "every bracket leaves through the agent's own door; got ${bracketRows.map { it.source_id }.toSet()}",
        )

        // Each bracket holds its own phase's work, not a neighbour's.
        val events = rows.map { decode(it) }
        assertTrue(
            events.inside(CognitivePhase.PERCEIVE).any { it.isModelCallIn(CognitivePhase.PERCEIVE) },
            "PERCEIVE's model call is inside PERCEIVE",
        )
        assertTrue(
            events.inside(CognitivePhase.RECALL).any { it is MemoryEvent.KnowledgeRecalled },
            "the recall is inside RECALL",
        )
        assertTrue(
            events.inside(CognitivePhase.OBSERVE).none { it is ProviderCallStartedEvent },
            "OBSERVE reads nothing on the Arc path yet, and calls nothing",
        )
        assertTrue(
            events.inside(CognitivePhase.PLAN).any { it.isModelCallIn(CognitivePhase.PLAN) },
            "PLAN's model call is inside PLAN",
        )
        assertTrue(
            events.inside(CognitivePhase.EXECUTE).any { it is PlanEvent.PlanStepStarted },
            "the step Execute ran is inside EXECUTE",
        )
        assertTrue(
            events.inside(CognitivePhase.LEARN).any { it is MemoryEvent.KnowledgeStored },
            "Pulse's learning write is inside LEARN",
        )

        // And the trace reads all six back as phases of the run.
        val trace = ArcTraceProjection(door.database).project(runId).getOrThrow()
        PROPEL.forEach { phase ->
            val types = trace.phases.singleOrNull { it.name == phase.name }?.events.orEmpty().map { it.eventType }
            assertTrue(
                CognitivePhaseEvent.PhaseEntered.EVENT_TYPE in types &&
                    CognitivePhaseEvent.PhaseExited.EVENT_TYPE in types,
                "the trace files both of ${phase.name}'s brackets under ${phase.name}; got $types",
            )
        }
    }

    @Test
    fun `brackets leave every prompt of the tick byte-identical`() = runBlocking<Unit> {
        // One project directory for every tick: the project spark renders its path into the
        // system message.
        val projectDir = projectDir()

        val unbracketed = ScriptedArcClient()
        tickOnce(
            ChargePhase(
                arcConfig = ARC_CONFIG,
                projectDir = projectDir,
                agentScope = scope,
                upstreamLlmClient = unbracketed,
                runId = UNBRACKETED_RUN,
                eventApiFactory = door::doorFor,
                // A caller's own configuration is the one its agents get — here, phases off.
                cognitiveConfig = CognitiveConfig(),
            ),
        )

        val bracketed = ScriptedArcClient()
        tickOnce(
            ChargePhase(
                arcConfig = ARC_CONFIG,
                projectDir = projectDir,
                agentScope = scope,
                upstreamLlmClient = bracketed,
                runId = BRACKETED_RUN,
                eventApiFactory = door::doorFor,
            ),
        )

        // The comparison below says something only if one tick bracketed and the other did not.
        assertTrue(
            bracketsUnder(UNBRACKETED_RUN).isEmpty(),
            "a configuration that turns phases off is honoured",
        )
        assertEquals(
            (PROPEL - CognitivePhase.LEARN).flatMap { listOf("enter $it", "exit $it") },
            bracketsUnder(BRACKETED_RUN).map { it.bracket() },
            "the Arc's default brackets the tick without being asked",
        )

        assertTrue(unbracketed.planningPrompts().isNotEmpty(), "the tick reached PLAN")
        assertEquals(
            unbracketed.planningPrompts(),
            bracketed.planningPrompts(),
            "the PLAN prompt, system message included, is byte-identical with brackets on",
        )
        assertEquals(
            unbracketed.prompts(),
            bracketed.prompts(),
            "and so is every other prompt of the tick, with no call added",
        )

        // The capture can tell a changed prompt from an unchanged one: phase guidance, which the
        // Arc's default leaves off, does change it.
        val guided = ScriptedArcClient()
        tickOnce(
            ChargePhase(
                arcConfig = ARC_CONFIG,
                projectDir = projectDir,
                agentScope = scope,
                upstreamLlmClient = guided,
                runId = GUIDED_RUN,
                eventApiFactory = door::doorFor,
                cognitiveConfig = CognitiveConfig(phaseSparks = PhaseSparkConfig(enabled = true)),
            ),
        )
        assertNotEquals(
            unbracketed.planningPrompts(),
            guided.planningPrompts(),
            "with phase guidance injected the PLAN prompt changes, so the equality above is not vacuous",
        )
    }

    @Test
    fun `a run cancelled while Pulse is writing still leaves LEARN's exit on the record`() = runBlocking<Unit> {
        val runId = "ampr441-cancelled-in-learn"
        val writing = CompletableDeferred<Unit>()
        val runtime = runtime(
            knowledge = WriteThatNeverLands(KnowledgeRepositoryImpl(door.database, door.driver), writing),
        )

        val outcome = withTimeout(TIMEOUT_MS) {
            val run = async { runtime.execute(GOAL, runId = runId) }
            // Pulse has entered LEARN and is suspended inside its write.
            writing.await()
            runtime.cancel()
            run.await()
        }

        assertIs<ArcOutcome.Cancelled>(outcome, "cancelling mid-write cancels the run")
        val events = rowsUnder(runId).map { decode(it) }
        assertEquals(
            PROPEL.flatMap { listOf("enter $it", "exit $it") },
            events.filterIsInstance<CognitivePhaseEvent>().map { it.bracket() },
            "the cancellation landed inside LEARN, and LEARN was still left",
        )
        assertTrue(
            events.none { it is MemoryEvent.KnowledgeStored },
            "the write the cancellation abandoned is not on the record: a cut-short run closes no loop",
        )
    }

    /** The whole Arc, Charge through Pulse, one tick long, with each agent given memory. */
    private fun runtime(
        knowledge: KnowledgeRepository = KnowledgeRepositoryImpl(door.database, door.driver),
    ): AmpereRuntime = AmpereRuntime(
        arcConfig = ARC_CONFIG,
        projectDir = projectDir(),
        agentScope = scope,
        maxFlowTicks = 1,
        upstreamLlmClient = ScriptedArcClient(),
        eventApiFactory = door::doorFor,
        knowledgeRepository = knowledge,
        clock = clock,
    )

    /** Charge with [charge], then one Flow tick over the agents it spawned. */
    private suspend fun tickOnce(charge: ChargePhase) {
        val charged = charge.execute(GOAL)
        FlowPhase(
            arcConfig = ARC_CONFIG,
            agents = charged.agents,
            goalTree = charged.goalTree,
            maxTicks = 1,
            clock = clock,
        ).execute()
    }

    private fun rowsUnder(runId: RunId): List<EventStore> =
        door.database.eventStoreQueries.getEventsByRunId(runId).executeAsList()

    private fun bracketsUnder(runId: RunId): List<CognitivePhaseEvent> =
        rowsUnder(runId).map { decode(it) }.filterIsInstance<CognitivePhaseEvent>()

    private fun decode(row: EventStore): Event =
        DEFAULT_JSON.decodeFromString(Event.serializer(), row.payload)

    /** The events strictly between [phase]'s entry and its exit, in the order they were stored. */
    private fun List<Event>.inside(phase: CognitivePhase): List<Event> {
        val entered = indexOfFirst { it is CognitivePhaseEvent.PhaseEntered && it.newPhase == phase }
        val exited = indexOfFirst { it is CognitivePhaseEvent.PhaseExited && it.exitedPhase == phase }
        assertTrue(entered in 0 until exited, "$phase is entered before it is left")
        return subList(entered + 1, exited)
    }

    private fun Event.isModelCallIn(phase: CognitivePhase): Boolean =
        this is ProviderCallStartedEvent && cognitivePhase == phase

    private fun CognitivePhaseEvent.bracket(): String = when (this) {
        is CognitivePhaseEvent.PhaseEntered -> "enter $newPhase"
        is CognitivePhaseEvent.PhaseExited -> "exit $exitedPhase"
    }

    /** A project [ChargePhase] accepts: sources present, with a tech stack, architecture and conventions. */
    private fun projectDir(): Path = createTempDirectory("ampr441-phase-brackets").also { dir ->
        dir.resolve("README.md").writeText("# PhaseBracketsProject\n\nA test project for the Arc's phase brackets.")
        dir.resolve("AGENTS.md").writeText(
            """
            # AGENTS

            ## Dependencies
            - Kotlin

            ## Conventions
            - Use suspend functions

            ## Architecture
            - Layered
            """.trimIndent(),
        )
    }.toString().toPath()

    private companion object {
        /** Carries no " and " / " then " / ";", so the goal tree is one node and one tick meets it. */
        const val GOAL = "Add a health check endpoint"

        const val DOOR_ID = "ampr441-door"
        const val UNBRACKETED_RUN = "ampr441-unbracketed"
        const val BRACKETED_RUN = "ampr441-bracketed"
        const val GUIDED_RUN = "ampr441-guided"
        const val TIMEOUT_MS = 30_000L

        val START: Instant = Instant.parse("2026-10-11T00:00:00Z")

        val PROPEL = listOf(
            CognitivePhase.PERCEIVE,
            CognitivePhase.RECALL,
            CognitivePhase.OBSERVE,
            CognitivePhase.PLAN,
            CognitivePhase.EXECUTE,
            CognitivePhase.LEARN,
        )

        val ARC_CONFIG = ArcConfig(
            name = "phase-brackets-arc",
            agents = listOf(ArcAgentConfig(role = "code")),
            orchestration = OrchestrationConfig(
                type = OrchestrationType.SEQUENTIAL,
                order = listOf("code"),
            ),
        )
    }
}

/**
 * An [UpstreamLlmClient] that answers each phase's call with what that phase parses, and keeps
 * every request it was sent — the system message as well as the user prompt, because phase
 * guidance would land in the system message.
 *
 * Its one plan step nominates no tool, so EXECUTE carries the step out as a reasoning step (one
 * model call) and succeeds, and the goal is met on the first tick.
 */
private class ScriptedArcClient : UpstreamLlmClient {

    private class Recorded(val userPrompt: String, val messages: List<String>)

    /** Appended from the agents' IO dispatcher, read from the test thread. */
    private val recorded = CopyOnWriteArrayList<Recorded>()

    /** Every request, in the order it was sent, each as its rendered messages. */
    fun prompts(): List<List<String>> = recorded.map { it.messages }

    fun planningPrompts(): List<List<String>> =
        recorded.filter { it.userPrompt.startsWith(PLANNING_OPENING) }.map { it.messages }

    override suspend fun call(
        request: ChatCompletionRequest,
        configuration: AIConfiguration,
    ): ChatCompletion {
        val userPrompt = request.messages.last { it.role == ChatRole.User }.content.orEmpty()
        recorded += Recorded(
            userPrompt = userPrompt,
            messages = request.messages.map { "${it.role.role}: ${it.content}" },
        )

        return ChatCompletion(
            id = "scripted",
            created = 0L,
            model = ModelId(configuration.model.name),
            choices = listOf(
                ChatChoice(
                    index = 0,
                    message = ChatMessage(role = ChatRole.Assistant, content = answerTo(userPrompt)),
                ),
            ),
        )
    }

    private fun answerTo(userPrompt: String): String = when {
        userPrompt.startsWith(PLANNING_OPENING) -> PLAN_JSON
        userPrompt.startsWith(REASONING_STEP_OPENING) -> "The step is done."
        else -> PERCEPTION_JSON
    }

    private companion object {
        /** `PlanGenerator`'s opening line; the perception prompt says "perception module". */
        const val PLANNING_OPENING = "You are the planning module"

        /** `SparkBasedAgent.buildReasoningStepPrompt`'s opening line. */
        const val REASONING_STEP_OPENING = "You are carrying out one step of a plan"

        const val PERCEPTION_JSON =
            """[{"observation":"A task is assigned","implication":"Plan it","confidence":"high"}]"""

        const val PLAN_JSON =
            """{"steps":[{"description":"Carry out the single step","requiresPreviousStep":false}],""" +
                """"estimatedComplexity":1,"requiresHumanInput":false}"""
    }
}

/**
 * A knowledge store whose write never lands: it reports that the write has begun, then waits to
 * be cancelled. Every read goes to [delegate], so Flow's RECALL sees an ordinary store.
 */
private class WriteThatNeverLands(
    private val delegate: KnowledgeRepository,
    private val writing: CompletableDeferred<Unit>,
) : KnowledgeRepository by delegate {

    override suspend fun storeKnowledge(
        knowledge: Knowledge,
        tags: List<String>,
        taskType: String?,
        complexityLevel: String?,
        runId: RunId?,
        holder: Ref<Subject>,
        confidence: BeliefConfidence?,
        sensitivity: Sensitivity,
        createdByEvent: EventId?,
    ): Result<KnowledgeEntry> {
        writing.complete(Unit)
        awaitCancellation()
    }
}
