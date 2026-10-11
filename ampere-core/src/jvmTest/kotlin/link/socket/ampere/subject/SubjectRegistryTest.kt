package link.socket.ampere.subject

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.SubjectEvent
import link.socket.ampere.agents.events.InMemoryEventApi
import link.socket.ampere.field.Ref
import link.socket.ampere.field.RelationshipKind
import link.socket.ampere.field.Subject

/**
 * [SubjectRegistry] (AMPR-416, F30): registration goes through the one door and the row points
 * back at the event; the Principal and `system` are there before anything registers; resolution
 * is against the store and fails loudly for what nothing registered.
 */
class SubjectRegistryTest {

    private lateinit var scope: CoroutineScope
    private lateinit var handle: InMemoryEventApi.Handle
    private lateinit var registry: SubjectRegistry

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        handle = InMemoryEventApi.open(agentId = "sdk", scope = scope)
        registry = SubjectRegistry(
            subjects = SqlDelightSubjectStore(handle.database),
            relationships = SqlDelightRelationshipStore(handle.database),
            eventApi = handle.api,
        )
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        handle.close()
    }

    private suspend fun events(): List<SubjectEvent> =
        handle.repository.getAllEvents().getOrThrow().filterIsInstance<SubjectEvent>()

    // ==================== The seeded rows ====================

    @Test
    fun `the Principal is the first Human row and EventSource Human resolves to it`() = runBlocking<Unit> {
        val principal = registry.principal().getOrThrow()

        assertEquals(EventSource.Human.ID, principal.id.value)
        assertEquals(SubjectRegistry.SEED_PROVENANCE, principal.provenance.createdBy)
        assertEquals(principal, registry.resolve(EventSource.Human).getOrThrow())
        assertEquals(SubjectRegistry.PRINCIPAL_REF, EventSource.Human.subjectRef())
        assertTrue(events().isEmpty(), "a seed is not a registration: nothing went through the door")
    }

    @Test
    fun `system exists as a pseudo-subject and is not listed among the agents`() = runBlocking<Unit> {
        val system = registry.system().getOrThrow()

        assertIs<Subject.Agent>(system)
        assertEquals(SubjectRegistry.SYSTEM_ID, system.id.value)
        assertTrue(registry.agents().getOrThrow().isEmpty(), "a fresh registry lists no agents")
    }

    // ==================== Registration through the door ====================

    @Test
    fun `registering an agent publishes the event first and the row points back at it`() = runBlocking<Unit> {
        val agent = registry.registerAgent("engineer", displayName = "Engineer").getOrThrow()

        val registered = assertIs<SubjectEvent.SubjectRegistered>(events().single())
        assertEquals("engineer", registered.subjectId)
        assertEquals("AGENT", registered.kind)
        assertEquals("Engineer", registered.displayName)
        assertEquals(EventSource.Agent("sdk"), registered.eventSource)
        assertEquals(registered.eventId, agent.provenance.createdBy, "Provenance.createdBy is the registering event")
        assertEquals(Ref<Subject.Agent>("engineer"), agent.id)
        assertEquals(agent, registry.resolve(EventSource.Agent("engineer")).getOrThrow())
        assertEquals(listOf(agent), registry.agents().getOrThrow())
    }

    @Test
    fun `an AgentId is its own subject ref`() {
        assertEquals(Ref<Subject.Agent>("engineer"), "engineer".asSubjectRef())
        assertEquals(Ref<Subject>("engineer"), EventSource.Agent("engineer").subjectRef())
    }

    @Test
    fun `re-registering a known agent returns the first row and publishes nothing`() = runBlocking<Unit> {
        val first = registry.registerAgent("engineer").getOrThrow()

        val again = registry.registerAgent("engineer", displayName = "Someone else").getOrThrow()

        assertEquals(first, again)
        assertEquals(1, events().size)
    }

    @Test
    fun `an id registered under another kind is refused`() = runBlocking<Unit> {
        val failure = registry.registerAgent(SubjectRegistry.PRINCIPAL_ID).exceptionOrNull()

        val inUse = assertIs<SubjectIdInUseException>(failure)
        assertEquals(SubjectKind.HUMAN, inUse.existing)
        assertEquals(SubjectKind.AGENT, inUse.requested)
        assertTrue(events().isEmpty(), "a refused registration publishes nothing")
    }

    @Test
    fun `registering a group writes one MemberOf edge per member and announces each`() = runBlocking<Unit> {
        val engineer = registry.registerAgent("engineer").getOrThrow()
        val qa = registry.registerAgent("qa").getOrThrow()

        val team = registry.registerGroup("team-1", "team", listOf(engineer.id, qa.id)).getOrThrow()

        assertEquals(Ref<Subject.Group>("team-1"), team.id)
        assertEquals(listOf(engineer.id, qa.id), registry.membersOf(team.id).getOrThrow())
        assertEquals(listOf(team.id), registry.groupsOf(engineer.id).getOrThrow())
        assertEquals(listOf(team), registry.groups().getOrThrow())

        val recorded = events().filterIsInstance<SubjectEvent.RelationshipRecorded>()
        assertEquals(setOf("engineer", "qa"), recorded.map { it.fromId }.toSet())
        assertTrue(recorded.all { it.toId == "team-1" && it.kind == RelationshipKindCodec.MEMBER_OF })
        val edges = SqlDelightRelationshipStore(handle.database).to(team.id).getOrThrow()
        assertEquals(
            recorded.map { it.eventId }.toSet(),
            edges.map { it.provenance.createdBy }.toSet(),
            "each edge points back at the event that recorded it",
        )
    }

    @Test
    fun `relating the same pair twice is one edge`() = runBlocking<Unit> {
        val team = registry.registerGroup("team-1", "team", emptyList()).getOrThrow()
        val first = registry.relate(Ref("engineer"), team.id, RelationshipKind.MemberOf).getOrThrow()

        val second = registry.relate(Ref("engineer"), team.id, RelationshipKind.MemberOf).getOrThrow()

        assertEquals(first, second)
        assertEquals(1, events().filterIsInstance<SubjectEvent.RelationshipRecorded>().size)
    }

    // ==================== Resolution ====================

    @Test
    fun `resolving an unregistered source fails rather than inventing a subject`() = runBlocking<Unit> {
        val failure = registry.resolve(EventSource.Agent("ghost")).exceptionOrNull()

        assertEquals("ghost", assertIs<UnknownSubjectException>(failure).id)
    }

    @Test
    fun `a null responder is the Principal and a named one must be a registered human`() = runBlocking<Unit> {
        assertEquals(registry.principal().getOrThrow(), registry.resolveResponder(null).getOrThrow())
        assertIs<UnknownSubjectException>(registry.resolveResponder("alice").exceptionOrNull())

        val alice = registry.registerHuman("alice", "Alice").getOrThrow()
        assertEquals(alice, registry.resolveResponder("alice").getOrThrow())

        registry.registerAgent("engineer").getOrThrow()
        assertIs<UnknownSubjectException>(
            registry.resolveResponder("engineer").exceptionOrNull(),
            "an agent is not a human responder",
        )
    }
}
