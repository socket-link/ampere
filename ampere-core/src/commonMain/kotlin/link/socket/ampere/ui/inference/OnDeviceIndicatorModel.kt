package link.socket.ampere.ui.inference

import link.socket.ampere.agents.domain.routing.RoutingRule
import link.socket.ampere.agents.domain.routing.local.InferenceLocality
import link.socket.ampere.agents.domain.routing.local.LocalUnavailableReason
import link.socket.ampere.agents.domain.routing.local.OnDeviceAvailability
import link.socket.ampere.agents.domain.routing.local.OnDeviceInferenceState
import link.socket.ampere.domain.ai.model.AIModel_OnDevice
import link.socket.ampere.llm.LocalFirstAnswer
import link.socket.ampere.llm.LocalFirstOutcome

/** What the indicator is saying, which is what picks its colour. */
enum class OnDeviceIndicatorTone {
    /** The on-device model is generating right now. */
    IN_USE,

    /** The device can serve; nothing is running. */
    READY,

    /** The device cannot serve right now. */
    UNAVAILABLE,

    /** Nothing has reported on the device yet. */
    UNKNOWN,
}

/**
 * The words on the on-device indicator, derived from an
 * [OnDeviceInferenceState] and nothing else.
 *
 * Separate from the composable so the wording — which is the part a person
 * relies on — is a pure function with tests, and so a non-Compose surface can
 * render the same sentences.
 *
 * @property tone What is being said.
 * @property title The short label, always shown.
 * @property detail The supporting line: the model, the reason, or the tally.
 */
data class OnDeviceIndicatorModel(
    val tone: OnDeviceIndicatorTone,
    val title: String,
    val detail: String?,
)

/** The indicator for this state. In use wins over everything: it is the claim being made. */
fun OnDeviceInferenceState.toIndicatorModel(): OnDeviceIndicatorModel {
    val model = describeOnDeviceModel(modelId)

    if (isInUse) {
        val calls = inFlight.size
        return OnDeviceIndicatorModel(
            tone = OnDeviceIndicatorTone.IN_USE,
            title = "Running on this device",
            detail = if (calls > 1) "$model · $calls calls" else model,
        )
    }

    return when (val availability = availability) {
        is OnDeviceAvailability.Available -> OnDeviceIndicatorModel(
            tone = OnDeviceIndicatorTone.READY,
            title = "On-device ready",
            detail = listOfNotNull(model, describeTally()).joinToString(" · "),
        )

        is OnDeviceAvailability.Unavailable -> OnDeviceIndicatorModel(
            tone = OnDeviceIndicatorTone.UNAVAILABLE,
            title = "On-device unavailable",
            detail = describeUnavailableReason(availability.reason),
        )

        OnDeviceAvailability.Unknown -> OnDeviceIndicatorModel(
            tone = OnDeviceIndicatorTone.UNKNOWN,
            title = "On-device",
            detail = "Checking availability",
        )
    }
}

/** "3 answered here" once the device has served something; null before. */
private fun OnDeviceInferenceState.describeTally(): String? =
    when {
        servedOnDevice == 0 -> null
        servedInCloud == 0 -> "$servedOnDevice answered here"
        else -> "$servedOnDevice answered here, $servedInCloud in the cloud"
    }

/**
 * The name to show for [modelId]: the catalog's display name where the model is
 * one Ampere bundles, the raw id where it is not, and a generic label when the
 * engine has not named one.
 */
fun describeOnDeviceModel(modelId: String?): String =
    AIModel_OnDevice.ALL_MODELS.firstOrNull { it.name == modelId }?.displayName
        ?: modelId
        ?: "On-device model"

/**
 * A sentence for an engine's reason code. A code this build does not recognise
 * is shown as it came: an unfamiliar reason is more use to a person than a
 * familiar sentence that may be wrong.
 */
fun describeUnavailableReason(reason: String?): String = when (reason) {
    null -> "The on-device model cannot run right now"
    LocalUnavailableReason.DEVICE_NOT_ELIGIBLE -> "This device does not support Apple Intelligence"
    LocalUnavailableReason.NOT_ENABLED -> "Apple Intelligence is turned off in Settings"
    LocalUnavailableReason.MODEL_NOT_READY -> "The on-device model is still downloading"
    LocalUnavailableReason.UNAVAILABLE -> "Apple Intelligence is not available right now"
    LocalUnavailableReason.OS_TOO_OLD -> "This version of the operating system has no on-device model"
    LocalUnavailableReason.NO_ENGINE,
    RoutingRule.ByCapability.DEFAULT_UNAVAILABLE_REASON,
    -> "No on-device model is connected"
    else -> reason
}

/**
 * The provenance line under an answer: where it ran, on what, and how long it
 * took. The locality comes first because it is the part that matters.
 */
fun LocalFirstAnswer.describeProvenance(): String {
    val where = when (locality) {
        InferenceLocality.ON_DEVICE -> "Answered on this device"
        InferenceLocality.CLOUD -> "Answered in the cloud"
    }
    val model = when (locality) {
        InferenceLocality.ON_DEVICE -> describeOnDeviceModel(modelId)
        InferenceLocality.CLOUD -> modelId
    }
    return "$where · $model · ${describeLatency(latencyMs)}"
}

/** A sentence for a call that produced no answer. */
fun LocalFirstOutcome.Failed.describeFailure(): String = when (reason) {
    LocalFirstOutcome.FailureReason.EMPTY_PROMPT -> "Type something to ask."
    LocalFirstOutcome.FailureReason.ON_DEVICE_UNAVAILABLE ->
        "${describeUnavailableReason(detail)}. Nothing was sent."
    LocalFirstOutcome.FailureReason.PROMPT_TOO_LARGE ->
        "That is too long for the on-device model. Nothing was sent."
    LocalFirstOutcome.FailureReason.ON_DEVICE_GENERATION_FAILED ->
        "The on-device model could not answer" + (detail?.let { ": $it" } ?: ".")
    LocalFirstOutcome.FailureReason.CLOUD_GENERATION_FAILED ->
        "The cloud model could not answer" + (detail?.let { ": $it" } ?: ".")
}

private fun describeLatency(latencyMs: Long): String =
    if (latencyMs < 1_000) {
        "$latencyMs ms"
    } else {
        val tenths = (latencyMs + 50) / 100
        "${tenths / 10}.${tenths % 10} s"
    }
