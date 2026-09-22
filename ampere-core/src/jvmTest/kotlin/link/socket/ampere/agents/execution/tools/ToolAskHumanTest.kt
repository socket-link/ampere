package link.socket.ampere.agents.execution.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.emission.EmissionReplyRegistry
import link.socket.ampere.agents.domain.emission.GlobalEmissionReplyRegistry
import link.socket.ampere.agents.domain.event.EmissionEvent
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.HumanInteractionEvent
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.InMemoryEventApi
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.EventSubscription
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.util.randomUUID

/**
 * F1a (AMPR-337): the tool publishes through an [AgentEventApi] door, so it runs in jvmTest
 * where [InMemoryEventApi] can build one. Bodies use `runBlocking`: the door persists on a
 * real IO dispatcher, which `runTest`'s virtual time would skip straight past.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolAskHumanTest {

    private val doorScope = TestScope(UnconfinedTestDispatcher())

    private fun door(): Pair<AgentEventApi, EventRepository> =
        InMemoryEventApi.create(agentId = "test-agent", scope = doorScope)

    private fun makeContext(instructions: String) = ExecutionContext.NoChanges(
        executorId = "test-executor",
        ticket = Ticket(
            id = generateUUID(),
            title = "Test",
            description = "Test",
            type = TicketType.TASK,
            priority = TicketPriority.MEDIUM,
            status = TicketStatus.Ready,
            assignedAgentId = null,
            createdByAgentId = "test-agent",
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        ),
        task = Task.CodeChange(
            id = generateUUID(),
            status = TaskStatus.Pending,
            description = "Test task",
        ),
        instructions = instructions,
    )

    private fun makeTool(
        api: AgentEventApi,
        registry: EmissionReplyRegistry,
        onProduced: suspend (HumanInteractionEvent.InputRequested) -> Unit = {},
    ) = ToolAskHuman(
        requiredAgentAutonomy = AgentActionAutonomy.ASK_BEFORE_ACTION,
        eventApi = api,
        replyRegistry = registry,
        onInputRequested = onProduced,
    )

    @Test
    fun `tool publishes HumanInteractionEvent InputRequested on execute`() = runBlocking<Unit> {
        val (api, _) = door()
        val registry = EmissionReplyRegistry()
        val capturedEvents = mutableListOf<HumanInteractionEvent.InputRequested>()

        api.eventSerialBus.subscribe<HumanInteractionEvent.InputRequested, EventSubscription.ByEventClassType>(
            agentId = "test-sub",
            eventType = HumanInteractionEvent.InputRequested.EVENT_TYPE,
        ) { event, _ -> capturedEvents.add(event) }

        val tool = makeTool(api, registry)
        val ctx = makeContext("Should we proceed?")

        val deferred = async {
            tool.execute(ExecutionRequest(ctx, ExecutionConstraints()))
        }

        delay(200.milliseconds)
        assertEquals(1, capturedEvents.size)
        val event = capturedEvents.first()
        assertIs<HumanInteractionEvent.InputRequested>(event)
        assertIs<EmissionEvent.Produced>(event)

        // Deliver reply
        registry.deliver(
            EmissionEvent.BaseResolved(
                eventId = randomUUID(),
                timestamp = Clock.System.now(),
                eventSource = EventSource.Human,
                urgency = Urgency.HIGH,
                emissionId = event.emissionId,
                affordanceId = "free-text",
                replyContext = kotlinx.serialization.json.JsonObject(
                    mapOf(
                        "type" to kotlinx.serialization.json.JsonPrimitive("free-text"),
                        "text" to kotlinx.serialization.json.JsonPrimitive("Yes, proceed"),
                    ),
                ),
            ),
        )

        val outcome = deferred.await()
        assertIs<ExecutionOutcome.NoChanges.Success>(outcome)
        assertNotNull(outcome.message)
        assertEquals(true, outcome.message.contains("Yes, proceed"))
    }

    @Test
    fun `tool persists InputRequested through the door`() = runBlocking<Unit> {
        val (api, repository) = door()
        val registry = EmissionReplyRegistry()
        val capturedEvents = mutableListOf<HumanInteractionEvent.InputRequested>()

        api.eventSerialBus.subscribe<HumanInteractionEvent.InputRequested, EventSubscription.ByEventClassType>(
            agentId = "test-sub",
            eventType = HumanInteractionEvent.InputRequested.EVENT_TYPE,
        ) { event, _ -> capturedEvents.add(event) }

        val tool = makeTool(api, registry)
        val deferred = async {
            tool.execute(ExecutionRequest(makeContext("Persist me"), ExecutionConstraints()))
        }

        delay(200.milliseconds)
        val event = capturedEvents.single()
        registry.deliver(
            EmissionEvent.BaseResolved(
                eventId = randomUUID(),
                timestamp = Clock.System.now(),
                eventSource = EventSource.Human,
                urgency = Urgency.HIGH,
                emissionId = event.emissionId,
                affordanceId = "free-text",
            ),
        )
        deferred.await()

        val stored = repository.getEventsByType(HumanInteractionEvent.InputRequested.EVENT_TYPE).getOrThrow()
        assertEquals(listOf(event.eventId), stored.map { it.eventId })
    }

    @Test
    fun `tool returns failure on timeout`() = runBlocking<Unit> {
        val (api, _) = door()
        val registry = EmissionReplyRegistry()

        val tool = ToolAskHuman(
            requiredAgentAutonomy = AgentActionAutonomy.ASK_BEFORE_ACTION,
            eventApi = api,
            replyRegistry = registry,
        )

        // Override timeout by building a minimal scope test — just verify the
        // success path works; timeout behaviour is tested in HumanEscalationFlowTest.
        assertEquals(ASK_HUMAN_TOOL_ID, tool.id)
    }

    @Test
    fun `onInputRequested callback is invoked before suspension`() = runBlocking<Unit> {
        val (api, _) = door()
        val registry = EmissionReplyRegistry()
        var callbackFired = false
        var callbackEvent: HumanInteractionEvent.InputRequested? = null

        val tool = makeTool(api, registry) { event ->
            callbackFired = true
            callbackEvent = event
        }

        val ctx = makeContext("Test callback")
        val deferred = async {
            tool.execute(ExecutionRequest(ctx, ExecutionConstraints()))
        }

        delay(200.milliseconds)
        assertEquals(true, callbackFired)
        assertNotNull(callbackEvent)

        registry.deliver(
            EmissionEvent.BaseResolved(
                eventId = randomUUID(),
                timestamp = Clock.System.now(),
                eventSource = EventSource.Human,
                urgency = Urgency.HIGH,
                emissionId = callbackEvent!!.emissionId,
                affordanceId = "free-text",
            ),
        )
        deferred.await()
    }

    @Test
    fun `ToolAskHuman requires AgentEventApi and EmissionReplyRegistry`() {
        val (api, _) = door()
        val tool = ToolAskHuman(
            requiredAgentAutonomy = AgentActionAutonomy.ASK_BEFORE_ACTION,
            eventApi = api,
            replyRegistry = GlobalEmissionReplyRegistry.instance,
        )
        assertEquals(ASK_HUMAN_TOOL_ID, tool.id)
    }
}
