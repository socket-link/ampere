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
import link.socket.ampere.agents.events.InMemoryEventApi
import link.socket.ampere.agents.service.AgentActionService
import link.socket.ampere.api.internal.DefaultAgentService
import link.socket.ampere.api.model.AgentState
import link.socket.ampere.dsl.agent.Engineer
import link.socket.ampere.dsl.agent.QATester
import link.socket.ampere.dsl.team.AgentTeam

/**
 * Tests for [link.socket.ampere.api.service.AgentService.pause], which pauses the one named
 * agent — not the whole team — and fails rather than reporting success it did not deliver.
 */
class AgentServicePauseTest {

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

    /** A two-member team that is running, so a paused member is distinguishable from an idle one. */
    private fun runningTeam(): AgentTeam =
        agentService.team {
            agent(Engineer)
            agent(QATester)
        }.also { it.pursue("Build authentication system") }

    @Test
    fun `pause pauses only the named agent`() = runBlocking<Unit> {
        runningTeam()

        agentService.pause(Engineer.name).getOrThrow()

        val byRole = agentService.listAll().associateBy { it.role }
        assertEquals(AgentState.Paused, byRole[Engineer.name]?.state)
        assertEquals(AgentState.Active, byRole[QATester.name]?.state)
    }

    @Test
    fun `inspect reports the paused agent as Paused`() = runBlocking<Unit> {
        runningTeam()

        agentService.pause(Engineer.name).getOrThrow()

        assertEquals(AgentState.Paused, agentService.inspect(Engineer.name).getOrThrow().state)
    }

    @Test
    fun `pause fails for an agent that is not on the team`() = runBlocking<Unit> {
        runningTeam()

        val result = agentService.pause("engineer-agent")

        assertTrue(result.isFailure)
        assertIs<IllegalArgumentException>(result.exceptionOrNull())
        assertTrue(agentService.listAll().all { it.state == AgentState.Active })
    }

    @Test
    fun `pause fails when no team is configured`() = runBlocking<Unit> {
        val result = agentService.pause(Engineer.name)

        assertTrue(result.isFailure)
        assertIs<IllegalStateException>(result.exceptionOrNull())
    }

    @Test
    fun `resumeMember returns a paused agent to active`() = runBlocking<Unit> {
        val team = runningTeam()
        agentService.pause(Engineer.name).getOrThrow()

        assertTrue(team.resumeMember(Engineer.name))

        assertEquals(AgentState.Active, agentService.inspect(Engineer.name).getOrThrow().state)
    }

    @Test
    fun `team pause leaves a per-agent pause in place`() = runBlocking<Unit> {
        val team = runningTeam()
        agentService.pause(Engineer.name).getOrThrow()

        team.pause()
        team.resume()

        val byRole = agentService.listAll().associateBy { it.role }
        assertEquals(AgentState.Paused, byRole[Engineer.name]?.state)
        assertEquals(AgentState.Active, byRole[QATester.name]?.state)
    }
}
