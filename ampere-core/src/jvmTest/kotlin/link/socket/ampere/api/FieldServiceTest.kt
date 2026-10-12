package link.socket.ampere.api

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.knowledge.KnowledgeEntry
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.domain.memory.AgentMemoryService
import link.socket.ampere.agents.environment.EnvironmentService
import link.socket.ampere.agents.events.InMemoryEventApi
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.api.internal.DefaultFieldService
import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonPlace
import link.socket.ampere.canon.CanonProvenance
import link.socket.ampere.canon.CanonType
import link.socket.ampere.canon.SourceHandle
import link.socket.ampere.domain.arc.GoalNode
import link.socket.ampere.domain.arc.GoalTree
import link.socket.ampere.dsl.config.AnthropicConfig
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
 * the predicate must sit ahead of the limit, and end to end through the snapshot.
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
    private lateinit var service: DefaultFieldService

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
        service = DefaultFieldService.over(handle.database, repository, handle.repository)
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
    fun `a negative belief limit is refused`() = runBlocking<Unit> {
        val failure = service.snapshot(viewer = null, beliefLimit = -1).exceptionOrNull()

        assertIs<IllegalArgumentException>(failure)
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
