package link.socket.ampere.agents.events.surface

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.AgentSurfaceEvent
import link.socket.ampere.agents.domain.event.EventRegistry
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.EventSubscription
import link.socket.ampere.agents.events.utils.generateUUID

class AgentSurfaceEventTest {

    @Test
    fun `Requested event has expected type and summary`() {
        val event = AgentSurfaceEvent.Requested(
            eventId = "evt-1",
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("plugin-x"),
            urgency = Urgency.MEDIUM,
            surface = AgentSurface.Confirmation(
                correlationId = "c-1",
                prompt = "Continue?",
            ),
        )

        assertEquals("AgentSurfaceRequested", event.eventType)
        assertEquals("c-1", event.correlationId)
        val summary = event.getSummary(
            formatUrgency = { "[${it.name}]" },
            formatSource = { (it as? EventSource.Agent)?.agentId ?: "?" },
        )
        assertTrue(summary.contains("Confirmation"))
        assertTrue(summary.contains("c-1"))
    }

    @Test
    fun `Responded event surfaces outcome in summary`() {
        val event = AgentSurfaceEvent.Responded(
            eventId = "evt-2",
            timestamp = Clock.System.now(),
            eventSource = EventSource.Human,
            response = AgentSurfaceResponse.Cancelled(correlationId = "c-1"),
        )

        assertEquals("AgentSurfaceResponded", event.eventType)
        val summary = event.getSummary(
            formatUrgency = { "[${it.name}]" },
            formatSource = { "human" },
        )
        assertTrue(summary.contains("cancelled"))
    }

    @Test
    fun `EventRegistry includes both AgentSurface event types`() {
        assertTrue(AgentSurfaceEvent.Requested.EVENT_TYPE in EventRegistry.allEventTypes)
        assertTrue(AgentSurfaceEvent.Responded.EVENT_TYPE in EventRegistry.allEventTypes)
    }

    @Test
    fun `plugin can emit a surface request and receive a stub response via the bus`() = runTest {
        coroutineScope {
            val bus = EventSerialBus(scope = this)
            val pluginAgent = "plugin-agent"
            val rendererAgent = "renderer-stub"
            val correlationId = "corr-flow-1"
            val seenRequest = CompletableDeferred<AgentSurface>()

            bus.subscribe<AgentSurfaceEvent.Requested, EventSubscription.ByEventClassType>(
                agentId = rendererAgent,
                eventType = AgentSurfaceEvent.Requested.EVENT_TYPE,
            ) { event, _ ->
                if (event.correlationId == correlationId && !seenRequest.isCompleted) {
                    seenRequest.complete(event.surface)
                    bus.publish(
                        AgentSurfaceEvent.Responded(
                            eventId = generateUUID(correlationId, rendererAgent),
                            timestamp = Clock.System.now(),
                            eventSource = EventSource.Agent(rendererAgent),
                            response = AgentSurfaceResponse.Submitted(
                                correlationId = correlationId,
                                values = mapOf(
                                    "branchName" to AgentSurfaceFieldValue.TextValue("feature/test"),
                                ),
                                chosenAction = "submit",
                            ),
                        ),
                    )
                }
            }

            val awaiter = async {
                bus.awaitSurfaceResponse(
                    awaiterAgentId = pluginAgent,
                    correlationId = correlationId,
                    timeout = 5.seconds,
                )
            }

            bus.emitSurfaceRequest(
                surface = AgentSurface.Form(
                    correlationId = correlationId,
                    title = "Branch",
                    fields = listOf(
                        AgentSurfaceField.Text(id = "branchName", label = "Branch name", required = true),
                    ),
                ),
                eventSource = EventSource.Agent(pluginAgent),
            )

            val rendered = seenRequest.await()
            assertIs<AgentSurface.Form>(rendered)
            assertEquals("Branch", rendered.title)

            val response = awaiter.await()
            val submitted = assertIs<AgentSurfaceResponse.Submitted>(response)
            assertEquals(correlationId, submitted.correlationId)
            val text = assertIs<AgentSurfaceFieldValue.TextValue>(submitted.values["branchName"])
            assertEquals("feature/test", text.value)
        }
    }

    @Test
    fun `awaitSurfaceResponse returns TimedOut when the renderer never replies`() = runTest {
        coroutineScope {
            val bus = EventSerialBus(scope = this)
            val response = bus.awaitSurfaceResponse(
                awaiterAgentId = "plugin-agent",
                correlationId = "missing",
                timeout = 50.milliseconds,
            )
            val timedOut = assertIs<AgentSurfaceResponse.TimedOut>(response)
            assertEquals("missing", timedOut.correlationId)
            assertEquals(50, timedOut.timeoutMillis)
        }
    }
}
