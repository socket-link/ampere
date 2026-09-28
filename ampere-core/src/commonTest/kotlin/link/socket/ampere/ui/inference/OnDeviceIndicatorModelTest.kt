package link.socket.ampere.ui.inference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.routing.RoutingRule
import link.socket.ampere.agents.domain.routing.local.InferenceLocality
import link.socket.ampere.agents.domain.routing.local.LocalCapacity
import link.socket.ampere.agents.domain.routing.local.LocalUnavailableReason
import link.socket.ampere.agents.domain.routing.local.OnDeviceCallKey
import link.socket.ampere.agents.domain.routing.local.OnDeviceInferenceProjection
import link.socket.ampere.agents.domain.routing.local.OnDeviceInferenceState
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_OnDevice
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_OnDevice
import link.socket.ampere.llm.LocalFirstAnswer
import link.socket.ampere.llm.LocalFirstOutcome

/**
 * The words on the on-device indicator (AMPR-327). The wording is the part a
 * person relies on, so it is pinned here rather than left to the composable.
 */
class OnDeviceIndicatorModelTest {

    private val onDeviceModel = AIModel_OnDevice.AppleFoundationModels

    @Test
    fun `nothing reported yet reads as checking rather than unavailable`() {
        val model = OnDeviceInferenceState().toIndicatorModel()

        assertEquals(OnDeviceIndicatorTone.UNKNOWN, model.tone)
        assertEquals("Checking availability", model.detail)
    }

    @Test
    fun `an available device at rest reads as ready and names the model`() {
        val model = available().toIndicatorModel()

        assertEquals(OnDeviceIndicatorTone.READY, model.tone)
        assertEquals("On-device ready", model.title)
        assertEquals(onDeviceModel.displayName, model.detail)
    }

    @Test
    fun `a call in flight reads as running on this device`() {
        val model = available().copy(ledger = mapOf(callKey() to 1)).toIndicatorModel()

        assertEquals(OnDeviceIndicatorTone.IN_USE, model.tone)
        assertEquals("Running on this device", model.title)
        assertEquals(onDeviceModel.displayName, model.detail)
    }

    @Test
    fun `in use wins over an unavailable reading`() {
        val state = unavailable(LocalUnavailableReason.MODEL_NOT_READY)
            .copy(ledger = mapOf(callKey() to 2))

        val model = state.toIndicatorModel()

        assertEquals(OnDeviceIndicatorTone.IN_USE, model.tone)
        assertTrue(model.detail.orEmpty().endsWith("2 calls"))
    }

    @Test
    fun `the ready tally counts what stayed and what left`() {
        val onlyHere = available().copy(servedOnDevice = 3).toIndicatorModel()
        val split = available().copy(servedOnDevice = 3, servedInCloud = 2).toIndicatorModel()

        assertTrue(onlyHere.detail.orEmpty().endsWith("3 answered here"))
        assertTrue(split.detail.orEmpty().endsWith("3 answered here, 2 in the cloud"))
    }

    @Test
    fun `every known reason code has its own sentence`() {
        val known = listOf(
            LocalUnavailableReason.DEVICE_NOT_ELIGIBLE,
            LocalUnavailableReason.NOT_ENABLED,
            LocalUnavailableReason.MODEL_NOT_READY,
            LocalUnavailableReason.UNAVAILABLE,
            LocalUnavailableReason.OS_TOO_OLD,
            LocalUnavailableReason.NO_ENGINE,
            RoutingRule.ByCapability.DEFAULT_UNAVAILABLE_REASON,
        )

        known.forEach { reason ->
            val sentence = describeUnavailableReason(reason)
            assertTrue(sentence != reason, "expected a sentence for $reason but got the code back")
            assertTrue(sentence.isNotBlank())
        }
    }

    @Test
    fun `an unrecognised reason is shown as it came`() {
        val model = unavailable("thermal_throttle").toIndicatorModel()

        assertEquals(OnDeviceIndicatorTone.UNAVAILABLE, model.tone)
        assertEquals("thermal_throttle", model.detail)
    }

    @Test
    fun `an answer says where it ran before anything else`() {
        val onDevice = answer(InferenceLocality.ON_DEVICE, onDeviceModel.name, latencyMs = 812)
        val cloud = answer(InferenceLocality.CLOUD, AIModel_Claude.Sonnet_5.name, latencyMs = 2_340)

        assertEquals(
            "Answered on this device · ${onDeviceModel.displayName} · 812 ms",
            onDevice.describeProvenance(),
        )
        assertEquals(
            "Answered in the cloud · ${AIModel_Claude.Sonnet_5.name} · 2.3 s",
            cloud.describeProvenance(),
        )
    }

    @Test
    fun `a failure that sent nothing says that nothing was sent`() {
        val unavailable = LocalFirstOutcome.Failed(
            reason = LocalFirstOutcome.FailureReason.ON_DEVICE_UNAVAILABLE,
            detail = LocalUnavailableReason.NOT_ENABLED,
        )
        val tooLarge = LocalFirstOutcome.Failed(LocalFirstOutcome.FailureReason.PROMPT_TOO_LARGE)

        assertTrue(unavailable.describeFailure().endsWith("Nothing was sent."))
        assertTrue(tooLarge.describeFailure().endsWith("Nothing was sent."))
    }

    private fun available(): OnDeviceInferenceState = OnDeviceInferenceProjection.fold(
        state = OnDeviceInferenceState(),
        capacity = LocalCapacity(
            available = true,
            modelId = onDeviceModel.name,
            providerId = AIProvider_OnDevice.id,
        ),
        observedAt = Instant.fromEpochMilliseconds(1),
    )

    private fun unavailable(reason: String): OnDeviceInferenceState = OnDeviceInferenceProjection.fold(
        state = OnDeviceInferenceState(),
        capacity = LocalCapacity(available = false, reason = reason),
        observedAt = Instant.fromEpochMilliseconds(1),
    )

    private fun callKey() = OnDeviceCallKey(
        workflowId = "run-1",
        agentId = "indicator-test-agent",
        providerId = AIProvider_OnDevice.id,
        modelId = onDeviceModel.name,
        cognitivePhase = null,
    )

    private fun answer(locality: InferenceLocality, modelId: String, latencyMs: Long) = LocalFirstAnswer(
        text = "An answer.",
        locality = locality,
        providerId = if (locality == InferenceLocality.ON_DEVICE) AIProvider_OnDevice.id else AIProvider_Anthropic.id,
        modelId = modelId,
        routingReason = "capability",
        latencyMs = latencyMs,
        runId = "run-1",
    )
}
