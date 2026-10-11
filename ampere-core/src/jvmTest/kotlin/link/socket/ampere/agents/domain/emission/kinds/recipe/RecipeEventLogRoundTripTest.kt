package link.socket.ampere.agents.domain.emission.kinds.recipe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.Principal
import link.socket.ampere.agents.domain.emission.Emission
import link.socket.ampere.agents.domain.emission.EmissionKind
import link.socket.ampere.agents.domain.emission.EmissionPayload
import link.socket.ampere.agents.domain.emission.EmissionProvenance
import link.socket.ampere.agents.domain.emission.EmissionReplyRegistry
import link.socket.ampere.agents.domain.emission.emission
import link.socket.ampere.agents.domain.emission.inputDigest
import link.socket.ampere.agents.domain.event.EmissionEvent
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.events.InMemoryEventApi
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.EventSubscription
import link.socket.ampere.data.DEFAULT_JSON

/**
 * The event-log path for a domain kind (AMPR-427): a `Recipe` published through
 * `present` reaches a bus subscriber and the `EventStore` intact, and both decode
 * it through the descriptor. `DEFAULT_JSON` is what the store writes with.
 *
 * `runBlocking`, not `runTest`: the door persists on a real IO dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeEventLogRoundTripTest {

    private val source = EventSource.Agent("chef")
    private val doorScope = TestScope(UnconfinedTestDispatcher())

    @Test
    fun `present publishes a recipe that a subscriber and the store both decode`() = runBlocking<Unit> {
        val (api, repository) = InMemoryEventApi.create(agentId = "chef", clock = Clock.System, scope = doorScope)
        val received = CompletableDeferred<EmissionEvent.BaseProduced>()
        api.eventSerialBus.subscribe<EmissionEvent.BaseProduced, EventSubscription.ByEventClassType>(
            agentId = "renderer",
            eventType = EmissionEvent.Produced.EVENT_TYPE,
        ) { event, _ -> received.complete(event) }

        val recipe = carbonara()
        val produced = emission(
            eventSource = source,
            eventApi = api,
            replyRegistry = EmissionReplyRegistry(),
            principal = Principal.Ambient,
            parentEmissionId = null,
        ) {
            present(RecipeKind, recipe)
        }

        val event = withTimeout(5.seconds) { received.await() }
        assertEquals(produced, event.emission)
        assertEquals(EmissionKind.Domain("recipe"), event.emission.kind)
        assertEquals(recipe, RecipeKind.decodeOrNull(event.emission.payload))
        assertEquals(inputDigest(event.emission.payload), event.emission.provenance.inputDigest)

        val stored = repository.getEventsByType(EmissionEvent.Produced.EVENT_TYPE).getOrThrow()
        val storedEvent = assertIs<EmissionEvent.BaseProduced>(stored.single())
        assertEquals(produced, storedEvent.emission)
        assertEquals(recipe, RecipeKind.decodeOrNull(storedEvent.emission.payload))
    }

    @Test
    fun `a Produced event carrying a recipe round-trips through DEFAULT_JSON`() {
        val payload = RecipeKind.encode(carbonara())
        val event = EmissionEvent.BaseProduced(
            eventId = "evt-1",
            timestamp = Instant.fromEpochMilliseconds(1_700_000_000_000),
            eventSource = source,
            emission = Emission(
                id = "emission-1",
                kind = RecipeKind.kind,
                payload = payload,
                provenance = EmissionProvenance(
                    inputDigest = inputDigest(payload),
                    parentEmissionId = null,
                    principal = Principal.Ambient,
                ),
                producedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
            ),
        )

        val encoded = DEFAULT_JSON.encodeToString(Event.serializer(), event)
        val decoded = DEFAULT_JSON.decodeFromString(Event.serializer(), encoded)

        val decodedProduced = assertIs<EmissionEvent.BaseProduced>(decoded)
        assertEquals(event, decodedProduced)
        assertIs<EmissionPayload.Domain>(decodedProduced.emission.payload)
        assertEquals(carbonara(), RecipeKind.decodeOrNull(decodedProduced.emission.payload))
        assertEquals(
            "Emission produced: Domain(recipe) (id=emission-1) [MEDIUM] from chef",
            decodedProduced.getSummary({ "[$it]" }, { "chef" }),
        )
    }
}
