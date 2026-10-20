package link.socket.ampere.api

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.knowledge.KnowledgeEntry
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.domain.memory.AgentMemoryService
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.environment.EnvironmentService
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.InMemoryEventApi
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketRepository
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.ToolExecutionEngine
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.service.TicketActionService
import link.socket.ampere.api.internal.DefaultFieldService
import link.socket.ampere.api.service.FieldService
import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonPlace
import link.socket.ampere.canon.CanonProvenance
import link.socket.ampere.canon.CanonType
import link.socket.ampere.canon.SourceHandle
import link.socket.ampere.commitment.CommitmentLedger
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.data.DatabaseSchemaManager
import link.socket.ampere.db.Database
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.arc.GoalNode
import link.socket.ampere.domain.arc.GoalTree
import link.socket.ampere.dsl.config.AnthropicConfig
import link.socket.ampere.field.ActionResult
import link.socket.ampere.field.ActionState
import link.socket.ampere.field.BeliefKind
import link.socket.ampere.field.Commitment
import link.socket.ampere.field.Field
import link.socket.ampere.field.Ref
import link.socket.ampere.field.RelationshipKind
import link.socket.ampere.field.Sensitivity
import link.socket.ampere.field.Subject
import link.socket.ampere.field.observe.environmentRef
import link.socket.ampere.goal.GoalBranch
import link.socket.ampere.link.LinkId
import link.socket.ampere.observe.SqlDelightObservationStore
import link.socket.ampere.plug.spi.PerceivePage
import link.socket.ampere.plug.spi.PerceiveQuery
import link.socket.ampere.plug.spi.PerceiveSource
import link.socket.ampere.plug.spi.recordedPerceive
import link.socket.ampere.subject.SqlDelightRelationshipStore
import link.socket.ampere.subject.SqlDelightSubjectStore
import link.socket.ampere.subject.SubjectRegistry

/**
 * The Field read (AMPR-439): a snapshot over the W2 stores, for one viewer.
 *
 * Every row here is written through its real door on one in-memory database — Subjects and
 * edges through [SubjectRegistry], goals through [GoalBranch], knowledge through
 * [AgentMemoryService], Observations through a recorded perceive — so what the snapshot is
 * checked against is what production writes, events and provenance included. F12 is checked twice: at the knowledge store's own read, where
 * the predicate must sit ahead of the limit, and end to end through the snapshot. Taken Actions
 * (AMPR-448) are the tool-event pairs the real [ToolExecutionEngine] publishes through the door.
 */
@OptIn(AmpereExperimentalApi::class)
class FieldServiceTest {

    private lateinit var scope: CoroutineScope
    private lateinit var handle: InMemoryEventApi.Handle
    private lateinit var repository: KnowledgeRepositoryImpl
    private lateinit var registry: SubjectRegistry
    private lateinit var branch: GoalBranch
    private lateinit var memoryA: AgentMemoryService
    private lateinit var memoryB: AgentMemoryService
    private lateinit var service: FieldService

    private val now: Instant = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    private val runId = "run-ampr439"
    private val tree = GoalTree(
        root = GoalNode(
            id = "goal-0",
            description = "Ship login and add tests",
            children = listOf(
                GoalNode(id = "goal-0-0", description = "Ship login"),
                GoalNode(id = "goal-0-1", description = "add tests"),
            ),
        ),
    )

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        handle = InMemoryEventApi.open(agentId = AGENT_A, scope = scope)
        val doorB = AgentEventApi(agentId = AGENT_B, eventRepository = handle.repository, eventSerialBus = handle.bus)

        repository = KnowledgeRepositoryImpl(handle.database)
        registry = SubjectRegistry(
            subjects = SqlDelightSubjectStore(handle.database),
            relationships = SqlDelightRelationshipStore(handle.database),
            eventApi = handle.api,
        )
        branch = GoalBranch.over(handle.database, handle.api)
        memoryA = AgentMemoryService(AGENT_A, repository, handle.api)
        memoryB = AgentMemoryService(AGENT_B, repository, doorB)
        service = FieldService.over(handle.database, repository, handle.repository)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        handle.close()
    }

    private fun knowledge(id: String, minutesAgo: Int) = Knowledge.FromTask(
        taskId = "task-$id",
        approach = "approach $id",
        learnings = "learned $id",
        timestamp = now - minutesAgo.minutes,
    )

    /** One row straight through the repository, holder and sensitivity as given. */
    private suspend fun row(id: String, holder: String, sensitivity: Sensitivity, minutesAgo: Int): KnowledgeEntry =
        repository.storeKnowledge(
            knowledge = knowledge(id, minutesAgo),
            holder = Ref(holder),
            sensitivity = sensitivity,
            createdByEvent = "event-$id",
        ).getOrThrow()

    /** The ids [viewer] reads through the store's own viewer-scoped list. */
    private suspend fun visibleTo(viewer: String?, limit: Int = 10): List<String> =
        repository.findKnowledgeVisibleTo(viewer?.let { Ref<Subject>(it) }, limit).getOrThrow().map { it.id }

    private fun Field.beliefContents(): Set<String> = beliefs.map { it.content }.toSet()

    // ==================== The store's own viewer read ====================

    @Test
    fun `a private row is visible to its holder and to no other viewer`() = runBlocking<Unit> {
        val privateRow = row("private-a", AGENT_A, Sensitivity.PRIVATE, minutesAgo = 1)

        assertEquals(listOf(privateRow.id), visibleTo(AGENT_A))
        assertTrue(visibleTo(AGENT_B).isEmpty())
        assertTrue(visibleTo(null).isEmpty())
    }

    @Test
    fun `a protected row is visible to its holder and to no other viewer`() = runBlocking<Unit> {
        val protectedRow = row("protected-a", AGENT_A, Sensitivity.PROTECTED, minutesAgo = 1)

        assertEquals(listOf(protectedRow.id), visibleTo(AGENT_A))
        assertTrue(visibleTo(AGENT_B).isEmpty())
        assertTrue(visibleTo(null).isEmpty())
    }

    @Test
    fun `a null viewer sees public rows only and newest first`() = runBlocking<Unit> {
        val older = row("public-old", AGENT_A, Sensitivity.PUBLIC, minutesAgo = 5)
        val newer = row("public-new", AGENT_B, Sensitivity.PUBLIC, minutesAgo = 1)
        row("private-b", AGENT_B, Sensitivity.PRIVATE, minutesAgo = 2)
        row("protected-a", AGENT_A, Sensitivity.PROTECTED, minutesAgo = 3)

        assertEquals(listOf(newer.id, older.id), visibleTo(null))
    }

    @Test
    fun `the limit applies to what the viewer may see and not to the whole table`() = runBlocking<Unit> {
        val own = row("own-a", AGENT_A, Sensitivity.PRIVATE, minutesAgo = 10)
        // Newer than A's row and invisible to A: an in-memory filter after the LIMIT would hand A nothing.
        repeat(3) { row("private-b-$it", AGENT_B, Sensitivity.PRIVATE, minutesAgo = it + 1) }

        assertEquals(listOf(own.id), visibleTo(AGENT_A, limit = 1))
    }

    @Test
    fun `the latest sequence is the newest event in the log`() = runBlocking<Unit> {
        val before = handle.repository.latestSequence().getOrThrow()
        memoryA.storeKnowledge(knowledge("one", minutesAgo = 1)).getOrThrow()

        val newest = handle.repository.getEventsSinceSequence(0).getOrThrow().maxOf { it.sequence }
        assertEquals(newest, handle.repository.latestSequence().getOrThrow())
        assertTrue(newest > before)
    }

    // ==================== The snapshot ====================

    /**
     * A's run forms a three-goal tree and completes one child; A and B join a team; A stores a
     * public lesson under the run and a private one outside it; B stores a protected one.
     */
    private suspend fun populate(): Populated {
        branch.form(tree, runId).getOrThrow()
        branch.complete(runId, "goal-0-0").getOrThrow()
        registry.registerAgent(AGENT_B).getOrThrow()
        val team = registry.registerGroup("team-1", "Team", members = listOf(Ref(AGENT_A), Ref(AGENT_B))).getOrThrow()

        val publicA = memoryA.storeKnowledge(knowledge("public-a", minutesAgo = 3), runId = runId).getOrThrow()
        memoryA.storeKnowledge(knowledge("private-a", minutesAgo = 2), sensitivity = Sensitivity.PRIVATE).getOrThrow()
        memoryB.storeKnowledge(knowledge("protected-b", minutesAgo = 1), sensitivity = Sensitivity.PROTECTED)
            .getOrThrow()
        return Populated(team, publicA)
    }

    private data class Populated(val team: Subject.Group, val publicA: KnowledgeEntry)

    @Test
    fun `a snapshot holds the subjects and edges and goals that were written`() = runBlocking<Unit> {
        val written = populate()

        val field = service.snapshot(viewer = Ref(AGENT_A)).getOrThrow()

        val ids = field.subjects.map { it.id.value }
        assertTrue(ids.containsAll(listOf(AGENT_A, AGENT_B, written.team.id.value, SubjectRegistry.SYSTEM_ID)), "$ids")

        val memberships = field.relationships.filter { it.kind == RelationshipKind.MemberOf }
        assertEquals(
            setOf(AGENT_A to written.team.id.value, AGENT_B to written.team.id.value),
            memberships.map { it.from.value to it.to.value }.toSet(),
        )
        assertEquals(
            field.relationships.size,
            field.relationships.toSet().size,
            "an edge read from both ends appears once",
        )

        val states = field.goals.associate { it.goal.id.value to it.state }
        assertEquals(
            mapOf(
                "$runId/goal-0" to Field.GoalState.OPEN,
                "$runId/goal-0-0" to Field.GoalState.COMPLETED,
                "$runId/goal-0-1" to Field.GoalState.OPEN,
            ),
            states,
        )
        assertTrue(field.goals.all { it.subject == Ref<Subject>(AGENT_A) })
    }

    @Test
    fun `a snapshot shows each viewer the beliefs it may see`() = runBlocking<Unit> {
        populate()

        val seenByA = service.snapshot(viewer = Ref(AGENT_A)).getOrThrow()
        val seenByB = service.snapshot(viewer = Ref(AGENT_B)).getOrThrow()
        val seenByNobody = service.snapshot(viewer = null).getOrThrow()

        assertEquals(Ref(AGENT_A), seenByA.viewer)
        assertEquals(setOf(content("public-a"), content("private-a")), seenByA.beliefContents())
        assertEquals(setOf(content("public-a"), content("protected-b")), seenByB.beliefContents())
        assertNull(seenByNobody.viewer)
        assertEquals(setOf(content("public-a")), seenByNobody.beliefContents())
    }

    @Test
    fun `a belief is about its run's root goal or else its holder`() = runBlocking<Unit> {
        populate()

        val field = service.snapshot(viewer = Ref(AGENT_A)).getOrThrow()

        val underRun = field.beliefs.single { it.content == content("public-a") }
        val outsideRun = field.beliefs.single { it.content == content("private-a") }
        assertEquals(GoalBranch.goalRef(runId, "goal-0"), underRun.subject)
        assertEquals(Ref(AGENT_A), outsideRun.subject)
        assertEquals(Ref(AGENT_A), outsideRun.holder)
    }

    @Test
    fun `a belief's provenance is the KnowledgeStored event that recorded it`() = runBlocking<Unit> {
        val written = populate()

        val belief = service.snapshot(viewer = null).getOrThrow().beliefs.single()

        val producing = assertNotNull(written.publicA.createdByEvent)
        assertEquals(producing, belief.provenance.createdBy)
        val envelope = assertNotNull(handle.repository.getStoredEventById(producing).getOrThrow())
        assertEquals(written.publicA.id, assertIs<MemoryEvent.KnowledgeStored>(envelope.event).knowledgeId)
    }

    @Test
    fun `a snapshot anchors to the newest event in the log`() = runBlocking<Unit> {
        populate()

        val field = service.snapshot(viewer = null).getOrThrow()

        val newest = handle.repository.getEventsSinceSequence(0).getOrThrow().maxOf { it.sequence }
        assertEquals(newest, field.asOfSequence)
    }

    @Test
    fun `a visible row with no provenance is counted and not shown`() = runBlocking<Unit> {
        populate()
        repository.storeKnowledge(knowledge("pre-10-sqm", minutesAgo = 0), createdByEvent = null).getOrThrow()

        val field = service.snapshot(viewer = null).getOrThrow()

        assertEquals(1, field.unmappedBeliefs)
        assertEquals(setOf(content("public-a")), field.beliefContents())
    }

    @Test
    fun `a snapshot lists the open commitments and not the resolved ones`() = runBlocking<Unit> {
        val tickets = TicketActionService(
            ticketRepository = TicketRepository(handle.database),
            eventApi = handle.api,
            commitmentLedger = CommitmentLedger.over(handle.database),
        )
        val due = now + 3.days
        val open = tickets.createTicket("Ship login", "", TicketPriority.HIGH, dueDate = due).getOrThrow()
        val done = tickets.createTicket("Add tests", "", TicketPriority.MEDIUM, dueDate = due + 1.days).getOrThrow()
        tickets.assignTicket(open.id, AGENT_A).getOrThrow()
        tickets.assignTicket(done.id, AGENT_B).getOrThrow()
        tickets.updateStatus(done.id, TicketStatus.Done).getOrThrow()

        // B's view: a commitment carries no sensitivity, so A's is in every view.
        val field = service.snapshot(viewer = Ref(AGENT_B)).getOrThrow()

        val commitment = field.commitments.single()
        assertEquals(open.id, commitment.ticketId)
        assertEquals(Ref<Subject>(AGENT_A), commitment.committer)
        assertEquals(due, commitment.due)
        assertEquals(Commitment.Status.OPEN, commitment.status)
        assertNull(commitment.goal)
    }

    @Test
    fun `a negative belief limit is refused`() = runBlocking<Unit> {
        val failure = service.snapshot(viewer = null, beliefLimit = -1).exceptionOrNull()

        assertIs<IllegalArgumentException>(failure)
    }

    // ==================== Belief kinds (AMPR-448) ====================

    @Test
    fun `outcome knowledge reads as a lesson and every other source as its namesake`() = runBlocking<Unit> {
        memoryA.storeKnowledge(knowledge("task-row", minutesAgo = 2)).getOrThrow()
        memoryA.storeKnowledge(
            Knowledge.FromOutcome(
                outcomeId = "outcome-1",
                approach = "approach lesson",
                learnings = "learned lesson",
                timestamp = now - 1.minutes,
            ),
        ).getOrThrow()

        val field = service.snapshot(viewer = null).getOrThrow()

        assertEquals(
            mapOf(content("task-row") to BeliefKind.TASK, content("lesson") to BeliefKind.LESSON),
            field.beliefs.associate { it.content to it.kind },
        )
    }

    // ==================== Taken Actions (AMPR-448) ====================

    @Test
    fun `a successful and a failing tool step read as two taken actions`() = runBlocking<Unit> {
        branch.form(tree, runId).getOrThrow()
        val engine = toolEngine()
        engine.execute(tool("read_codebase", "Read codebase", failure = null), toolRequest(runId))
        engine.execute(tool("run_tests", "Run tests", failure = "two tests failed"), toolRequest(runId))

        val field = service.snapshot(viewer = null).getOrThrow()

        assertEquals(listOf("Run tests", "Read codebase"), field.actions.map { it.description }, "newest first")
        val (failed, succeeded) = field.actions
        assertEquals(ActionResult.Failed(reason = "two tests failed"), failed.result)
        assertEquals(ActionResult.Succeeded, succeeded.result)
        for (action in field.actions) {
            assertEquals(ActionState.TAKEN, action.state)
            assertEquals(Ref<Subject>(AGENT_A), action.actor, "the deciding agent is the event's source")
            assertEquals(FunctionExecutor.DEFAULT_ID, action.executor, "the performer is the pair's executorId")
            assertEquals(GoalBranch.goalRef(runId, "goal-0"), action.goal)
            assertNull(action.reversibility, "nothing records reversibility, so it is not guessed")
            assertNull(action.cost, "nothing records cost, so it is not guessed")
        }

        val starts = handle.repository.getEventsByType(ToolEvent.ToolExecutionStarted.EVENT_TYPE).getOrThrow()
            .filterIsInstance<ToolEvent.ToolExecutionStarted>()
        assertEquals(
            starts.associate { it.invocationId to it.eventId },
            field.actions.associate { it.id.value to it.provenance.createdBy },
            "each action is one invocation, and its provenance is the start that announced it",
        )
    }

    @Test
    fun `a tool call waiting on a person is not a taken action`() = runBlocking<Unit> {
        val engine = toolEngine()
        engine.execute(holdingTool(), toolRequest(runId = null))
        engine.execute(tool("read_codebase", "Read codebase", failure = null), toolRequest(runId = null))

        val field = service.snapshot(viewer = null).getOrThrow()

        assertEquals(listOf("Read codebase"), field.actions.map { it.description })
        assertNull(field.actions.single().goal, "a call outside a run serves no goal")
    }

    @Test
    fun `the action limit keeps the newest completed calls`() = runBlocking<Unit> {
        val engine = toolEngine()
        for (name in listOf("first", "second", "third")) {
            engine.execute(tool(name, name, failure = null), toolRequest(runId = null))
        }

        val bounded = DefaultFieldService.over(handle.database, repository, handle.repository, actionLimit = 2)
        val field = bounded.snapshot(viewer = null).getOrThrow()

        assertEquals(listOf("third", "second"), field.actions.map { it.description })
    }

    // ==================== The Environment branch ====================

    @Test
    fun `a stored perceive appears in its holder's snapshot and in no other subject's`() = runBlocking<Unit> {
        populate()
        val perception = PlaceSource(listOf("harbor", "lighthouse"))
            .recordedPerceive(PerceiveQuery(linkId = LinkId("places-link")), handle.api)
            .getOrThrow()
        assertEquals(2, perception.store(Ref(AGENT_A), SqlDelightObservationStore(handle.database)).getOrThrow())

        val seenByA = service.snapshot(viewer = Ref(AGENT_A)).getOrThrow()
        val seenByB = service.snapshot(viewer = Ref(AGENT_B)).getOrThrow()
        val seenByNobody = service.snapshot(viewer = null).getOrThrow()

        assertEquals(
            perception.page.entities.map { it.environmentRef }.toSet(),
            seenByA.observations.map { it.subject }.toSet(),
        )
        assertTrue(seenByA.observations.all { it.holder == Ref<Subject>(AGENT_A) })
        assertTrue(seenByA.observations.all { it.provenance.createdBy == perception.perceiveEventId })
        assertTrue(seenByB.observations.isEmpty())
        assertTrue(seenByNobody.observations.isEmpty())
        // Kept out of knowledge: the belief branch is exactly what it was before the perceive.
        assertEquals(setOf(content("public-a"), content("private-a")), seenByA.beliefContents())
    }

    // ==================== Outside a run (AMPR-474) ====================

    @Test
    fun `a field reader over a caller's own database needs no environment`() = runBlocking<Unit> {
        // What a caller with only its database holds: no bus, no ticket repository, no orchestrator.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            DatabaseSchemaManager.ensure(driver).getOrThrow()
            val database = Database(driver)
            val beliefs = KnowledgeRepositoryImpl(database)
            val events = EventRepository(DEFAULT_JSON, scope, database)
            val holder = Ref<Subject>(AGENT_A)
            beliefs.storeKnowledge(
                knowledge = knowledge("outside-a-run", minutesAgo = 1),
                holder = holder,
                sensitivity = Sensitivity.PRIVATE,
                createdByEvent = "event-outside-a-run",
            ).getOrThrow()

            val field = FieldService.over(database, beliefs, events).snapshot(viewer = holder).getOrThrow()

            assertEquals(1, field.beliefs.size)
            assertEquals(holder, field.beliefs.single().holder)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `the field reader is built through its public factory and nowhere else`() {
        // One construction path: every reader, the instances' included, comes from FieldService.over.
        val allowed = setOf("FieldService.kt", "DefaultFieldService.kt")
        val offenders = moduleDir().resolve("src").listFiles().orEmpty()
            .filter { it.name.endsWith("Main") }
            .flatMap { sourceSet -> sourceSet.walkTopDown().filter { it.extension == "kt" } }
            .filter { it.name !in allowed && it.readText().contains("DefaultFieldService") }
            .map { it.relativeTo(moduleDir()).invariantSeparatorsPath }
            .sorted()

        assertTrue(
            offenders.isEmpty(),
            "Build the Field reader with FieldService.over, not in:\n${offenders.joinToString("\n")}",
        )
    }

    /** `ampere-core`, found from wherever the test was launched, as the boundary tests do. */
    private fun moduleDir(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (dir.resolve("src/commonMain/kotlin").isDirectory) return dir
            if (dir.resolve("ampere-core/src/commonMain/kotlin").isDirectory) return dir.resolve("ampere-core")
            dir = dir.parentFile
        }
        error("Could not locate ampere-core from ${System.getProperty("user.dir")}")
    }

    // ==================== On the instance ====================

    @Test
    fun `fromEnvironment exposes the field only when given a database`() = runBlocking<Unit> {
        val environment = EnvironmentService.create(database = handle.database, scope = scope)

        val withoutDatabase = Ampere.fromEnvironment(environment, repository, workspace = "/tmp/ampr439-workspace")
        val withDatabase = Ampere.fromEnvironment(
            environment,
            repository,
            workspace = "/tmp/ampr439-workspace",
            database = handle.database,
        )

        assertNull(withoutDatabase.field)
        populate()
        val field = assertNotNull(withDatabase.field).snapshot(viewer = null).getOrThrow()
        assertEquals(setOf(content("public-a")), field.beliefContents())
    }

    @Test
    fun `Ampere create exposes the field`() = runBlocking<Unit> {
        val instance = Ampere.create {
            provider(AnthropicConfig())
            database(":memory:")
        }
        try {
            val field = assertNotNull(instance.field).snapshot(viewer = null).getOrThrow()
            assertTrue(field.subjects.any { it.id.value == SubjectRegistry.SYSTEM_ID })
        } finally {
            instance.close()
        }
    }

    private fun content(id: String) = "approach $id\n\nlearned $id"

    /** The production engine over A's door, with no strategy registered, so no model is asked anything. */
    private fun toolEngine(): ToolExecutionEngine = ToolExecutionEngine(
        llmService = AgentLLMService(
            AgentConfiguration(
                agentDefinition = WriteCodeAgent,
                aiConfiguration = AIConfiguration_Default(
                    provider = AIProvider_Anthropic,
                    model = AIModel_Claude.Sonnet_5,
                ),
                llmProvider = { "{}" },
            ),
        ),
        executor = FunctionExecutor.create(),
        actorId = AGENT_A,
        eventApi = handle.api,
    )

    /** A tool that succeeds, or fails with [failure] when one is given. */
    private fun tool(id: String, name: String, failure: String?) = FunctionTool<ExecutionContext.NoChanges>(
        id = id,
        name = name,
        description = name,
        requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
        executionFunction = { request ->
            val at = Clock.System.now()
            if (failure == null) {
                ExecutionOutcome.NoChanges.Success(
                    executorId = request.context.executorId,
                    ticketId = request.context.ticket.id,
                    taskId = request.context.task.id,
                    executionStartTimestamp = at,
                    executionEndTimestamp = at,
                    message = "done",
                )
            } else {
                ExecutionOutcome.NoChanges.Failure(
                    executorId = request.context.executorId,
                    ticketId = request.context.ticket.id,
                    taskId = request.context.task.id,
                    executionStartTimestamp = at,
                    executionEndTimestamp = at,
                    message = failure,
                )
            }
        },
    )

    private fun holdingTool() = FunctionTool<ExecutionContext.NoChanges>(
        id = "send_reply",
        name = "Send reply",
        description = "Sends once a person approves",
        requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
        executionFunction = { request ->
            val at = Clock.System.now()
            ExecutionOutcome.Held(
                executorId = request.context.executorId,
                ticketId = request.context.ticket.id,
                taskId = request.context.task.id,
                executionStartTimestamp = at,
                executionEndTimestamp = at,
                reason = "waiting for approval",
            )
        },
    )

    private fun toolRequest(runId: String?): ExecutionRequest<ExecutionContext.NoChanges> {
        val at = Clock.System.now()
        return ExecutionRequest(
            context = ExecutionContext.NoChanges(
                actorId = AGENT_A,
                executorId = FunctionExecutor.DEFAULT_ID,
                ticket = Ticket(
                    id = "ticket-1",
                    title = "Ship login",
                    description = "Ship login",
                    type = TicketType.TASK,
                    priority = TicketPriority.MEDIUM,
                    status = TicketStatus.InProgress,
                    assignedAgentId = AGENT_A,
                    createdByAgentId = AGENT_A,
                    createdAt = at,
                    updatedAt = at,
                ),
                task = Task.CodeChange(id = "task-1", status = TaskStatus.Pending, description = "Ship login"),
                instructions = "Ship login",
            ),
            constraints = ExecutionConstraints(requireTests = false, requireLinting = false),
            runId = runId,
        )
    }

    /** Projects a place per name, stamped with the query's perceive event id as a recording source must. */
    private inner class PlaceSource(private val names: List<String>) : PerceiveSource<CanonPlace> {
        override val emits: Set<CanonType> = setOf(CanonType.PLACE)

        override suspend fun perceive(query: PerceiveQuery): Result<PerceivePage<CanonPlace>> =
            Result.success(
                PerceivePage.unfiltered(
                    query,
                    names.map { name ->
                        CanonPlace(
                            canonId = CanonId(name),
                            provenance = CanonProvenance(
                                sourceHandle = SourceHandle(
                                    linkId = query.linkId,
                                    sourceSystem = "test.places",
                                    nativeId = name,
                                ),
                                observedAt = now,
                                perceiveEventId = query.perceiveEventId,
                            ),
                            name = name,
                        )
                    },
                ),
            )
    }

    private companion object {
        const val AGENT_A = "agent-a"
        const val AGENT_B = "agent-b"
    }
}
