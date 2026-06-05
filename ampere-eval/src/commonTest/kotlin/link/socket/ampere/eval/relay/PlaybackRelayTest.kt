package link.socket.ampere.eval.relay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.encodeToJsonElement
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.ProviderCallCompletedEvent
import link.socket.ampere.agents.domain.event.ProviderCallStartedEvent
import link.socket.ampere.agents.domain.routing.CognitiveRelay
import link.socket.ampere.agents.domain.routing.RelayConfig
import link.socket.ampere.agents.domain.routing.RoutingContext
import link.socket.ampere.agents.domain.routing.RoutingResolution
import link.socket.ampere.api.model.TokenUsage
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.eval.trace.Trace
import link.socket.ampere.eval.trace.TraceEvent
import link.socket.ampere.trace.WattCost

/** AMPR-184 tasks 2.1–2.4 validation. */
class PlaybackRelayTest {

    private val source = EventSource.Agent("agent-1")
    private val fallback: AIConfiguration =
        AIConfiguration_Default(provider = AIProvider_Anthropic, model = AIModel_Claude.Sonnet_4)
    private val ctx = RoutingContext(phase = CognitivePhase.PLAN, agentId = "agent-1", workflowId = "wf")

    // region — task 2.1: extraction

    @Test
    fun `modelCalls enumerates pairs in call order and ignores non-model events`() {
        val trace = traceOf(
            taskCreated(ts = 0),
            startedCall(model = "m1", reason = "r1", ts = 1),
            completedCall(model = "m1", ts = 2),
            questionRaised(ts = 3),
            startedCall(model = "m2", reason = "r2", ts = 4),
            completedCall(model = "m2", ts = 5),
        )

        val calls = trace.modelCalls()

        assertEquals(2, calls.size)
        assertEquals(listOf("m1", "m2"), calls.map { it.modelId })
        assertEquals(listOf("r1", "r2"), calls.map { it.routingReason })
    }

    @Test
    fun `modelCalls tolerates a completion with no recorded start`() {
        val trace = traceOf(completedCall(model = "m1", ts = 2))

        val calls = trace.modelCalls()

        assertEquals(1, calls.size)
        assertNull(calls[0].started)
        assertNull(calls[0].routingReason)
        assertEquals("m1", calls[0].modelId)
    }

    // endregion

    // region — task 2.2: ordered replay, zero Watts

    @Test
    fun `replays recorded selections in order with playback reason`() = runTest {
        val relay = PlaybackRelay(traceOfCalls(2))

        val first = relay.resolveWithMetadata(ctx, fallback)
        val second = relay.resolveWithMetadata(ctx, fallback)

        assertEquals(PlaybackRelay.PLAYBACK_REASON, first.reason)
        assertEquals(PlaybackRelay.PLAYBACK_REASON, second.reason)
        assertEquals(fallback, first.configuration)
        assertEquals(2, relay.recordedCallCount)
        // Recorded selection is preserved in call order for inspection.
        assertEquals("m1", relay.recordedCallAt(0)?.modelId)
        assertEquals("m2", relay.recordedCallAt(1)?.modelId)
    }

    @Test
    fun `replayed calls charge zero Watts and never touch a delegate`() = runTest {
        val spy = SpyRelay()
        // branchIndex defaults past the end, so both calls are pure replays.
        val relay = PlaybackRelay(traceOfCalls(2), liveDelegate = spy)

        relay.resolveWithMetadata(ctx, fallback)
        relay.resolveWithMetadata(ctx, fallback)

        assertEquals(WattCost(), relay.replayedWattCost)
        assertEquals(0, spy.calls)
    }

    // endregion

    // region — task 2.3: miss policy

    @Test
    fun `strict miss returns a typed PlaybackMiss`() = runTest {
        val relay = PlaybackRelay(traceOfCalls(1), missPolicy = MissPolicy.Error)

        assertEquals(PlaybackRelay.PLAYBACK_REASON, relay.replay(ctx, fallback).getOrThrow().reason)

        val miss = relay.replay(ctx, fallback)
        assertTrue(miss.isFailure)
        val error = miss.exceptionOrNull()
        assertTrue(error is PlaybackMiss)
        assertEquals(1, error.callIndex)
        assertEquals(1, error.recordedCallCount)
    }

    @Test
    fun `strict miss throws PlaybackMiss through the CognitiveRelay interface`() = runTest {
        val relay = PlaybackRelay(traceOfCalls(1), missPolicy = MissPolicy.Error)

        relay.resolveWithMetadata(ctx, fallback) // call 0 replays
        assertFailsWith<PlaybackMiss> { relay.resolveWithMetadata(ctx, fallback) } // call 1 diverges
    }

    @Test
    fun `delegate miss policy routes the missing call live`() = runTest {
        val spy = SpyRelay(reason = "live")
        val relay = PlaybackRelay(traceOfCalls(1), missPolicy = MissPolicy.Delegate, liveDelegate = spy)

        val replayed = relay.resolveWithMetadata(ctx, fallback)
        val delegated = relay.resolveWithMetadata(ctx, fallback)

        assertEquals(PlaybackRelay.PLAYBACK_REASON, replayed.reason)
        assertEquals("live", delegated.reason)
        assertEquals(1, spy.calls)
    }

    @Test
    fun `delegate miss policy without a delegate fails`() = runTest {
        val relay = PlaybackRelay(traceOfCalls(1), missPolicy = MissPolicy.Delegate, liveDelegate = null)

        relay.replay(ctx, fallback).getOrThrow() // call 0 replays
        val result = relay.replay(ctx, fallback) // call 1 has no delegate to route to

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    // endregion

    // region — task 2.4: branch wiring

    @Test
    fun `branchIndex replays the first k calls then delegates`() = runTest {
        val spy = SpyRelay(reason = "live")
        val relay = PlaybackRelay(traceOfCalls(3), liveDelegate = spy, branchIndex = 2)

        val reasons = listOf(
            relay.resolveWithMetadata(ctx, fallback).reason, // 0 -> replay
            relay.resolveWithMetadata(ctx, fallback).reason, // 1 -> replay
            relay.resolveWithMetadata(ctx, fallback).reason, // 2 -> delegate
        )

        assertEquals(listOf(PlaybackRelay.PLAYBACK_REASON, PlaybackRelay.PLAYBACK_REASON, "live"), reasons)
        assertEquals(1, spy.calls)
    }

    @Test
    fun `branch without a delegate fails`() = runTest {
        val relay = PlaybackRelay(traceOfCalls(2), branchIndex = 1, liveDelegate = null)

        relay.replay(ctx, fallback).getOrThrow() // call 0 replays
        val result = relay.replay(ctx, fallback) // call 1 branches, but no delegate

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    // endregion

    // region — fixtures

    /** Counts invocations and stamps a fixed reason; stands in for a live relay. */
    private class SpyRelay(private val reason: String = "live") : CognitiveRelay {
        var calls: Int = 0
            private set

        override val config: RelayConfig = RelayConfig()

        override suspend fun resolve(
            context: RoutingContext,
            fallbackConfiguration: AIConfiguration,
        ): AIConfiguration = resolveWithMetadata(context, fallbackConfiguration).configuration

        override suspend fun resolveWithMetadata(
            context: RoutingContext,
            fallbackConfiguration: AIConfiguration,
        ): RoutingResolution {
            calls++
            return RoutingResolution(configuration = fallbackConfiguration, reason = reason)
        }

        override suspend fun updateConfig(newConfig: RelayConfig) = Unit
    }

    private fun traceOf(vararg events: Event): Trace {
        val traceEvents = events.mapIndexed { i, event ->
            TraceEvent(
                index = i,
                timestamp = event.timestamp.toEpochMilliseconds(),
                type = event.eventType,
                payload = DEFAULT_JSON.encodeToJsonElement(Event.serializer(), event),
            )
        }
        return Trace(id = "t", runId = "r", arcId = "a", createdAt = 0L, events = traceEvents)
    }

    /** A trace of [n] sequential model calls, models `m1..mn`, reasons `r1..rn`. */
    private fun traceOfCalls(n: Int): Trace = traceOf(
        *(0 until n).flatMap { i ->
            val model = "m${i + 1}"
            listOf(
                startedCall(model = model, reason = "r${i + 1}", ts = (2 * i + 1).toLong()),
                completedCall(model = model, ts = (2 * i + 2).toLong()),
            )
        }.toTypedArray(),
    )

    private fun startedCall(model: String, reason: String, ts: Long): ProviderCallStartedEvent =
        ProviderCallStartedEvent(
            eventId = "start-$model-$ts",
            timestamp = Instant.fromEpochMilliseconds(ts),
            eventSource = source,
            workflowId = "wf",
            agentId = "agent-1",
            cognitivePhase = CognitivePhase.PLAN,
            providerId = "anthropic",
            modelId = model,
            routingReason = reason,
        )

    private fun completedCall(model: String, ts: Long): ProviderCallCompletedEvent =
        ProviderCallCompletedEvent(
            eventId = "done-$model-$ts",
            timestamp = Instant.fromEpochMilliseconds(ts),
            eventSource = source,
            workflowId = "wf",
            agentId = "agent-1",
            cognitivePhase = CognitivePhase.PLAN,
            providerId = "anthropic",
            modelId = model,
            usage = TokenUsage(),
            latencyMs = 10,
            success = true,
        )

    private fun taskCreated(ts: Long): Event = Event.TaskCreated(
        eventId = "task-$ts",
        urgency = Urgency.LOW,
        timestamp = Instant.fromEpochMilliseconds(ts),
        eventSource = source,
        taskId = "T-$ts",
        description = "task",
        assignedTo = null,
    )

    private fun questionRaised(ts: Long): Event = Event.QuestionRaised(
        eventId = "q-$ts",
        urgency = Urgency.LOW,
        timestamp = Instant.fromEpochMilliseconds(ts),
        eventSource = source,
        questionText = "why?",
        context = "",
    )

    // endregion
}
