package link.socket.ampere.agents.domain.routing.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.ProviderCallCompletedEvent
import link.socket.ampere.agents.domain.event.ProviderCallStartedEvent
import link.socket.ampere.agents.domain.event.RoutingEvent
import link.socket.ampere.agents.domain.routing.RoutingDecision
import link.socket.ampere.agents.domain.routing.capability.InMemoryModelDescriptorRegistry
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_OnDevice
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_OnDevice

/**
 * The read model behind the on-device indicator (AMPR-327). These pin what a
 * surface is allowed to claim: that a call is on the device only while one is,
 * and that a cloud call never lights the indicator.
 */
class OnDeviceInferenceProjectionTest {

    private val classifier = CatalogLocalityClassifier(InMemoryModelDescriptorRegistry())

    private val onDeviceModel = AIModel_OnDevice.AppleFoundationModels.name
    private val cloudModel = AIModel_Claude.Sonnet_5.name

    @Test
    fun `an on-device call is in use from its start to its completion`() = runTest {
        val started = fold(OnDeviceInferenceState(), onDeviceStarted(at = 10))

        assertTrue(started.isInUse)
        assertEquals(1, started.inFlight.size)
        assertEquals(onDeviceModel, started.modelId)

        val completed = fold(started, onDeviceCompleted(at = 20))

        assertFalse(completed.isInUse)
        assertTrue(completed.inFlight.isEmpty())
        assertEquals(1, completed.servedOnDevice)
        assertEquals(0, completed.failedOnDevice)
    }

    @Test
    fun `a cloud call never reads as in use`() = runTest {
        val started = fold(OnDeviceInferenceState(), cloudStarted(at = 10))
        val completed = fold(started, cloudCompleted(at = 20))

        assertFalse(started.isInUse)
        assertFalse(completed.isInUse)
        assertEquals(0, completed.servedOnDevice)
        assertEquals(1, completed.servedInCloud)
        assertIs<OnDeviceAvailability.Unknown>(completed.availability)
    }

    @Test
    fun `a completion folded before its start leaves nothing in flight`() = runTest {
        // The bus dispatches each handler on its own coroutine so this order is real.
        val completedFirst = fold(OnDeviceInferenceState(), onDeviceCompleted(at = 20))

        assertFalse(completedFirst.isInUse)

        val thenStarted = fold(completedFirst, onDeviceStarted(at = 10))

        assertFalse(thenStarted.isInUse)
        assertTrue(thenStarted.inFlight.isEmpty())
        assertEquals(1, thenStarted.servedOnDevice)
    }

    @Test
    fun `the same facts fold to the same state in either order`() = runTest {
        val events = listOf(
            onDeviceStarted(at = 10),
            onDeviceCompleted(at = 20),
            fallback(at = 30, reason = LocalUnavailableReason.MODEL_NOT_READY),
            cloudCompleted(at = 40),
            onDeviceStarted(at = 50, workflowId = "run-2"),
        )

        val forward = OnDeviceInferenceProjection.replay(events, classifier)
        val backward = OnDeviceInferenceProjection.replay(events.reversed(), classifier)

        assertEquals(forward, backward)
        assertTrue(forward.isInUse)
        assertEquals("run-2", forward.inFlight.single().workflowId)
    }

    @Test
    fun `two concurrent calls with the same identity are both counted`() = runTest {
        var state = fold(OnDeviceInferenceState(), onDeviceStarted(at = 10))
        state = fold(state, onDeviceStarted(at = 11))

        assertEquals(2, state.inFlight.size)

        state = fold(state, onDeviceCompleted(at = 20))

        assertTrue(state.isInUse)
        assertEquals(1, state.inFlight.size)

        state = fold(state, onDeviceCompleted(at = 21))

        assertFalse(state.isInUse)
    }

    @Test
    fun `a failed on-device call is counted as failed and clears in use`() = runTest {
        val started = fold(OnDeviceInferenceState(), onDeviceStarted(at = 10))
        val failed = fold(started, onDeviceCompleted(at = 20, success = false, errorType = "LocalInferenceException"))

        assertFalse(failed.isInUse)
        assertEquals(0, failed.servedOnDevice)
        assertEquals(1, failed.failedOnDevice)
        assertEquals("LocalInferenceException", failed.lastCall?.errorType)
        // A failure proves nothing about availability.
        assertIs<OnDeviceAvailability.Unknown>(failed.availability)
    }

    @Test
    fun `a successful on-device call proves the device available`() = runTest {
        val state = fold(OnDeviceInferenceState(), onDeviceCompleted(at = 20))

        val availability = assertIs<OnDeviceAvailability.Available>(state.availability)
        assertEquals(onDeviceModel, availability.modelId)
        assertEquals(Instant.fromEpochMilliseconds(20), state.availabilityAsOf)
    }

    @Test
    fun `a fallback around the device records why it was skipped`() = runTest {
        val state = fold(
            OnDeviceInferenceState(),
            fallback(at = 30, reason = LocalUnavailableReason.NOT_ENABLED),
        )

        val availability = assertIs<OnDeviceAvailability.Unavailable>(state.availability)
        assertEquals(LocalUnavailableReason.NOT_ENABLED, availability.reason)
        assertEquals(LocalUnavailableReason.NOT_ENABLED, state.lastFallback?.reason)
        assertEquals(cloudModel, state.lastFallback?.servedBy)
    }

    @Test
    fun `a fallback between two cloud models is not about the device`() = runTest {
        val state = fold(
            OnDeviceInferenceState(),
            fallback(
                at = 30,
                reason = "rate_limited",
                failedProvider = AIProvider_Anthropic.id,
                failedModel = cloudModel,
            ),
        )

        assertIs<OnDeviceAvailability.Unknown>(state.availability)
        assertNull(state.lastFallback)
    }

    @Test
    fun `a probe reports availability with the figures the engine gave`() {
        val state = OnDeviceInferenceProjection.fold(
            state = OnDeviceInferenceState(),
            capacity = LocalCapacity(
                available = true,
                modelId = onDeviceModel,
                maxContextTokens = 8_192,
                providerId = AIProvider_OnDevice.id,
            ),
            observedAt = Instant.fromEpochMilliseconds(5),
        )

        val availability = assertIs<OnDeviceAvailability.Available>(state.availability)
        assertEquals(onDeviceModel, availability.modelId)
        assertEquals(8_192, availability.maxContextTokens)
        assertEquals(onDeviceModel, state.modelId)
    }

    @Test
    fun `a stale probe does not overwrite a newer fact`() = runTest {
        val served = fold(OnDeviceInferenceState(), onDeviceCompleted(at = 100))

        val afterStaleProbe = OnDeviceInferenceProjection.fold(
            state = served,
            capacity = LocalCapacity(available = false, reason = LocalUnavailableReason.MODEL_NOT_READY),
            observedAt = Instant.fromEpochMilliseconds(50),
        )

        assertIs<OnDeviceAvailability.Available>(afterStaleProbe.availability)

        val afterFreshProbe = OnDeviceInferenceProjection.fold(
            state = served,
            capacity = LocalCapacity(available = false, reason = LocalUnavailableReason.MODEL_NOT_READY),
            observedAt = Instant.fromEpochMilliseconds(150),
        )

        assertIs<OnDeviceAvailability.Unavailable>(afterFreshProbe.availability)
    }

    @Test
    fun `the share on the device is null until something completes`() = runTest {
        assertNull(OnDeviceInferenceState().onDeviceShare)

        var state = fold(OnDeviceInferenceState(), onDeviceCompleted(at = 10))
        state = fold(state, cloudCompleted(at = 20))
        state = fold(state, cloudCompleted(at = 30))
        state = fold(state, cloudCompleted(at = 40))

        assertEquals(0.25, state.onDeviceShare)
    }

    @Test
    fun `an unknown model from the on-device provider is still on the device`() = runTest {
        val locality = classifier.localityOf(AIProvider_OnDevice.id, "a-model-the-catalog-dropped")

        assertEquals(InferenceLocality.ON_DEVICE, locality)
    }

    @Test
    fun `an unknown model from any other provider is in the cloud`() = runTest {
        val locality = classifier.localityOf("some-new-provider", "some-new-model")

        assertEquals(InferenceLocality.CLOUD, locality)
    }

    private suspend fun fold(state: OnDeviceInferenceState, event: Event): OnDeviceInferenceState =
        OnDeviceInferenceProjection.fold(state, event, classifier)

    private fun onDeviceStarted(at: Long, workflowId: String = "run-1") = started(
        at = at,
        workflowId = workflowId,
        providerId = AIProvider_OnDevice.id,
        modelId = onDeviceModel,
    )

    private fun onDeviceCompleted(
        at: Long,
        workflowId: String = "run-1",
        success: Boolean = true,
        errorType: String? = null,
    ) = completed(
        at = at,
        workflowId = workflowId,
        providerId = AIProvider_OnDevice.id,
        modelId = onDeviceModel,
        success = success,
        errorType = errorType,
    )

    private fun cloudStarted(at: Long) = started(
        at = at,
        workflowId = "run-1",
        providerId = AIProvider_Anthropic.id,
        modelId = cloudModel,
    )

    private fun cloudCompleted(at: Long) = completed(
        at = at,
        workflowId = "run-1",
        providerId = AIProvider_Anthropic.id,
        modelId = cloudModel,
        success = true,
        errorType = null,
    )

    private fun started(
        at: Long,
        workflowId: String,
        providerId: String,
        modelId: String,
    ) = ProviderCallStartedEvent(
        eventId = "start-$providerId-$at",
        timestamp = Instant.fromEpochMilliseconds(at),
        eventSource = EventSource.Agent(AGENT_ID),
        workflowId = workflowId,
        agentId = AGENT_ID,
        cognitivePhase = CognitivePhase.PERCEIVE,
        providerId = providerId,
        modelId = modelId,
        routingReason = "capability:$providerId",
    )

    private fun completed(
        at: Long,
        workflowId: String,
        providerId: String,
        modelId: String,
        success: Boolean,
        errorType: String?,
    ) = ProviderCallCompletedEvent(
        eventId = "complete-$providerId-$at",
        timestamp = Instant.fromEpochMilliseconds(at),
        eventSource = EventSource.Agent(AGENT_ID),
        workflowId = workflowId,
        agentId = AGENT_ID,
        cognitivePhase = CognitivePhase.PERCEIVE,
        providerId = providerId,
        modelId = modelId,
        latencyMs = 10,
        success = success,
        errorType = errorType,
    )

    private fun fallback(
        at: Long,
        reason: String,
        failedProvider: String = AIProvider_OnDevice.id,
        failedModel: String = onDeviceModel,
    ) = RoutingEvent.RouteFallback(
        eventId = "fallback-$at",
        timestamp = Instant.fromEpochMilliseconds(at),
        eventSource = EventSource.Agent(AGENT_ID),
        agentId = AGENT_ID,
        phase = CognitivePhase.PERCEIVE,
        failedProvider = failedProvider,
        failedModel = failedModel,
        fallbackDecision = RoutingDecision(
            providerName = AIProvider_Anthropic.name,
            modelName = cloudModel,
            matchedRule = "capability:${AIProvider_Anthropic.id}",
            isFallback = true,
        ),
        failureReason = reason,
    )

    private companion object {
        const val AGENT_ID = "projection-test-agent"
    }
}
