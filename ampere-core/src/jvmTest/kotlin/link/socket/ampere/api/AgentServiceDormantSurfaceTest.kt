package link.socket.ampere.api

import java.lang.reflect.Method
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.events.InMemoryEventApi
import link.socket.ampere.agents.service.AgentActionService
import link.socket.ampere.api.internal.DefaultAgentService
import link.socket.ampere.api.model.AgentState
import link.socket.ampere.api.service.AgentService
import link.socket.ampere.api.service.stub.StubAgentService
import link.socket.ampere.dsl.agent.Engineer
import link.socket.ampere.dsl.agent.QATester
import link.socket.ampere.dsl.events.AgentInitialized
import link.socket.ampere.dsl.events.GoalSet
import link.socket.ampere.dsl.events.Planned
import link.socket.ampere.dsl.team.AgentTeam

/**
 * AMPR-399 (B5): no method on the stable [AgentService] silently no-ops.
 *
 * [AgentService.team], [AgentService.pursue] and [AgentService.wake] start no work — the team
 * is a DSL value and the two publishers put one task event on the record that nothing consumes
 * — so each is `@Deprecated` with a message that says so. The register row (H13) chose that
 * over wiring: `RunHost` (AMPR-393) is where they are re-pointed.
 *
 * Two halves, and both matter. The reflection tests are the tripwire: when AMPR-393 re-points
 * one of these, its deprecation goes away and the test here fails, which is the reminder to
 * move the method into the "does something" list below. The behaviour tests pin what the
 * deprecated methods still do, so the messages stay true: exactly one publish each, nothing
 * after it.
 */
@Suppress("DEPRECATION")
class AgentServiceDormantSurfaceTest {

    private lateinit var scope: CoroutineScope
    private lateinit var handle: InMemoryEventApi.Handle
    private lateinit var agentService: DefaultAgentService

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        handle = InMemoryEventApi.open(agentId = "sdk-test", scope = scope)
        agentService = DefaultAgentService(
            agentActionService = AgentActionService(eventApi = handle.api),
            eventApi = handle.api,
        )
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        handle.close()
    }

    // ==================== The surface is classified, member by member ====================

    @Test
    fun `every AgentService member is either deprecated or does something`() {
        val declared = AgentService::class.java.declaredMethods
            .filterNot { it.isSynthetic }
            .map { it.kotlinName }
            .toSet()

        assertEquals(
            DORMANT_MEMBERS + OBSERVABLE_MEMBERS,
            declared,
            "AgentService gained or lost a member. Classify it: either it does something " +
                "observable (and is covered by a behaviour test here or beside it) or it is " +
                "@Deprecated saying what it does not do. A third option is the bug AMPR-399 " +
                "closed.",
        )
    }

    @Test
    fun `the dormant members are deprecated and name their successor`() {
        DORMANT_MEMBERS.forEach { name ->
            val deprecation = deprecationOf(AgentService::class.java.method(name))

            assertNotNull(deprecation, "AgentService.$name must be @Deprecated (AMPR-399)")
            assertContains(
                deprecation.message,
                "RunHost",
                message = "AgentService.$name's deprecation must point at what replaces it",
            )
        }
    }

    @Test
    fun `the observable members are not deprecated`() {
        OBSERVABLE_MEMBERS.forEach { name ->
            assertNull(
                deprecationOf(AgentService::class.java.method(name)),
                "AgentService.$name reports real state; deprecating the read surface was not " +
                    "H13's verdict",
            )
        }
    }

    @Test
    fun `the shipped and stub implementations carry the deprecation too`() {
        listOf(DefaultAgentService::class.java, StubAgentService::class.java).forEach { impl ->
            DORMANT_MEMBERS.forEach { name ->
                assertNotNull(
                    deprecationOf(impl.method(name)),
                    "${impl.simpleName}.$name must be @Deprecated: a caller holding the " +
                        "concrete type gets no warning otherwise",
                )
            }
        }
    }

    @Test
    fun `AgentTeam and the wake publisher are deprecated`() {
        assertNotNull(
            AgentTeam::class.java.getAnnotation(Deprecated::class.java),
            "AgentTeam declares a team that does not run (AMPR-399)",
        )
        assertNotNull(
            deprecationOf(AgentActionService::class.java.method("wakeAgent")),
            "AgentActionService.wakeAgent is the orphan publish behind AgentService.wake",
        )
    }

    // ==================== What the deprecated methods still do ====================

    @Test
    fun `pursue publishes one unassigned task and nothing else`() = runBlocking<Unit> {
        val goalId = agentService.pursue("Build authentication system").getOrThrow()

        val recorded = handle.repository.getAllEvents().getOrThrow()
        assertEquals(1, recorded.size, "pursue must put exactly one event on the record")

        val created = assertIs<Event.TaskCreated>(recorded.single())
        assertEquals(goalId, created.taskId, "the returned id must name the published task")
        assertEquals("Build authentication system", created.description)
        assertNull(created.assignedTo, "pursue assigns the task to no one")

        // Nothing picked it up: no run opened, so there is no agent to report on either.
        assertTrue(agentService.listAll().isEmpty())
    }

    @Test
    fun `wake publishes one task for the agent and leaves its state alone`() = runBlocking<Unit> {
        agentService.team {
            agent(Engineer)
            agent(QATester)
        }
        val before = agentService.inspect(Engineer.name).getOrThrow()

        agentService.wake(Engineer.name).getOrThrow()

        val created = assertIs<Event.TaskCreated>(
            handle.repository.getAllEvents().getOrThrow().single(),
        )
        assertEquals(Engineer.name, created.assignedTo)

        val after = agentService.inspect(Engineer.name).getOrThrow()
        assertEquals(before.state, after.state, "wake does not move an agent out of its state")
        assertEquals(AgentState.Idle, after.state, "and an un-pursued team is Idle, not woken")
    }

    @Test
    fun `a pursued team reports Active members while the record stays empty`() = runBlocking<Unit> {
        val team = agentService.team {
            agent(Engineer)
            agent(QATester)
        }

        team.pursue("Build authentication system")

        // GoalSet, one AgentInitialized per member, then the Planned placeholder: the four
        // markers AgentTeam.pursue emits before it reaches its TODO.
        val markers = withTimeout(10.seconds) { team.events.take(4).toList() }
        assertIs<GoalSet>(markers[0])
        assertIs<AgentInitialized>(markers[1])
        assertIs<AgentInitialized>(markers[2])
        assertIs<Planned>(markers[3])

        assertTrue(
            agentService.listAll().all { it.state == AgentState.Active },
            "the team reports its members as active",
        )
        assertTrue(
            handle.repository.getAllEvents().getOrThrow().isEmpty(),
            "while nothing at all reached the event record: no task, no plan, no phase",
        )
    }

    private fun Class<*>.method(name: String): Method =
        declaredMethods.filterNot { it.isSynthetic }.single { it.kotlinName == name }

    /**
     * The declared name, with JVM mangling removed.
     *
     * Four of these six return `Result`, and a function returning an inline class gets a
     * hash suffix on the JVM: `pursue` compiles to `pursue-gIAlu-s`. Matching on [Method.name]
     * finds nothing.
     */
    private val Method.kotlinName: String
        get() = name.substringBefore('-')

    private fun deprecationOf(method: Method): Deprecated? =
        method.getAnnotation(Deprecated::class.java)

    private companion object {

        /** Advertise work and start none. Each is `@Deprecated` until AMPR-393 re-points it. */
        val DORMANT_MEMBERS = setOf("team", "pursue", "wake")

        /** Report or mutate the roster the (deprecated) team declared. Covered elsewhere. */
        val OBSERVABLE_MEMBERS = setOf("inspect", "listAll", "pause")
    }
}
