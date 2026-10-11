package link.socket.ampere.api

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
import link.socket.ampere.agents.domain.event.SubjectEvent
import link.socket.ampere.agents.events.InMemoryEventApi
import link.socket.ampere.agents.service.AgentActionService
import link.socket.ampere.api.internal.DefaultAgentService
import link.socket.ampere.api.model.AgentState
import link.socket.ampere.dsl.agent.Engineer
import link.socket.ampere.dsl.agent.QATester
import link.socket.ampere.field.Ref
import link.socket.ampere.subject.SqlDelightRelationshipStore
import link.socket.ampere.subject.SqlDelightSubjectStore
import link.socket.ampere.subject.SubjectRegistry

/**
 * [DefaultAgentService] over a [SubjectRegistry] (AMPR-416, F30): `listAll` and `inspect` read
 * registered Subjects rather than the declared team's role strings, and a declared team becomes
 * a registered `Subject.Group` with one `MemberOf` edge per role the first time it is used.
 *
 * `team` and `AgentTeam.pursue` are deprecated for starting no work; the registry is what gives
 * the declaration a durable record, so these tests go on exercising them.
 */
@Suppress("DEPRECATION")
class AgentServiceRegistryTest {

    private lateinit var scope: CoroutineScope
    private lateinit var handle: InMemoryEventApi.Handle
    private lateinit var registry: SubjectRegistry
    private lateinit var agentService: DefaultAgentService

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        handle = InMemoryEventApi.open(agentId = "sdk-test", scope = scope)
        registry = SubjectRegistry(
            subjects = SqlDelightSubjectStore(handle.database),
            relationships = SqlDelightRelationshipStore(handle.database),
            eventApi = handle.api,
        )
        agentService = DefaultAgentService(
            agentActionService = AgentActionService(eventApi = handle.api),
            subjectRegistry = registry,
        )
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        handle.close()
    }

    private suspend fun subjectEvents(): List<SubjectEvent> =
        handle.repository.getAllEvents().getOrThrow().filterIsInstance<SubjectEvent>()

    @Test
    fun `a fresh instance lists no agents even though system is registered`() = runBlocking<Unit> {
        assertTrue(agentService.listAll().isEmpty())
        assertTrue(agentService.inspect("nonexistent").isFailure)
    }

    @Test
    fun `a declared team is registered as a group with one MemberOf edge per role`() = runBlocking<Unit> {
        val team = agentService.team {
            agent(Engineer)
            agent(QATester)
        }

        val listed = agentService.listAll()

        assertEquals(setOf(Engineer.name, QATester.name), listed.map { it.id }.toSet())
        assertTrue(listed.all { it.state == AgentState.Idle }, "an un-pursued team is Idle")
        val group = registry.groups().getOrThrow().single()
        assertEquals(Ref(team.id), group.id)
        assertEquals(
            setOf(Engineer.name, QATester.name),
            registry.membersOf(group.id).getOrThrow().map { it.value }.toSet(),
        )

        val registered = subjectEvents().filterIsInstance<SubjectEvent.SubjectRegistered>()
        assertEquals(setOf(Engineer.name, QATester.name, team.id), registered.map { it.subjectId }.toSet())
        assertEquals(2, subjectEvents().filterIsInstance<SubjectEvent.RelationshipRecorded>().size)
    }

    @Test
    fun `the team is registered once however often it is read`() = runBlocking<Unit> {
        agentService.team {
            agent(Engineer)
            agent(QATester)
        }

        agentService.listAll()
        agentService.inspect(Engineer.name).getOrThrow()
        agentService.listAll()

        assertEquals(5, subjectEvents().size, "three registrations and two edges, said once")
    }

    @Test
    fun `listAll reports registered agents that are not on the team`() = runBlocking<Unit> {
        agentService.team { agent(Engineer) }
        registry.registerAgent("archivist", displayName = "Archivist").getOrThrow()

        val byId = agentService.listAll().associateBy { it.id }

        assertEquals(setOf(Engineer.name, "archivist"), byId.keys)
        val archivist = byId.getValue("archivist")
        assertEquals("Archivist", archivist.role, "a registered agent with no role reports its display name")
        assertEquals(AgentState.Idle, archivist.state)
        assertTrue(archivist.sparkStack.isEmpty())
    }

    @Test
    fun `inspect resolves against the registry`() = runBlocking<Unit> {
        agentService.team { agent(Engineer) }
        registry.registerAgent("archivist").getOrThrow()

        val engineer = agentService.inspect(Engineer.name).getOrThrow()
        assertEquals(Engineer.name, engineer.id)
        assertEquals(Engineer.capabilities.map { it.name }, engineer.sparkStack)

        val archivist = agentService.inspect("archivist").getOrThrow()
        assertEquals(AgentState.Idle, archivist.state)

        val unknown = agentService.inspect("ghost")
        assertIs<IllegalArgumentException>(unknown.exceptionOrNull())
    }

    @Test
    fun `inspect refuses an id that names a group or a human`() = runBlocking<Unit> {
        val team = agentService.team { agent(Engineer) }
        agentService.listAll()

        val group = agentService.inspect(team.id)
        assertIs<IllegalArgumentException>(group.exceptionOrNull())
        assertTrue(group.exceptionOrNull()?.message.orEmpty().contains("GROUP"))

        val principal = agentService.inspect(SubjectRegistry.PRINCIPAL_ID)
        assertTrue(principal.exceptionOrNull()?.message.orEmpty().contains("HUMAN"))
    }

    @Test
    fun `team flags still drive the state of a registered member`() = runBlocking<Unit> {
        val team = agentService.team {
            agent(Engineer)
            agent(QATester)
        }
        team.pursue("Build authentication system")

        agentService.pause(Engineer.name).getOrThrow()

        val byId = agentService.listAll().associateBy { it.id }
        assertEquals(AgentState.Paused, byId.getValue(Engineer.name).state)
        assertEquals(AgentState.Active, byId.getValue(QATester.name).state)
        assertEquals(AgentState.Paused, agentService.inspect(Engineer.name).getOrThrow().state)
    }

    @Test
    fun `a second team is registered as a second group and the roles are not re-registered`() = runBlocking<Unit> {
        val first = agentService.team { agent(Engineer) }
        agentService.listAll()

        val second = agentService.team {
            agent(Engineer)
            agent(QATester)
        }
        agentService.listAll()

        assertEquals(setOf(first.id, second.id), registry.groups().getOrThrow().map { it.id.value }.toSet())
        val registeredAgents = subjectEvents()
            .filterIsInstance<SubjectEvent.SubjectRegistered>()
            .filter { it.kind == "AGENT" }
            .map { it.subjectId }
        assertEquals(listOf(Engineer.name, QATester.name).sorted(), registeredAgents.sorted(), "each role once")
    }
}
