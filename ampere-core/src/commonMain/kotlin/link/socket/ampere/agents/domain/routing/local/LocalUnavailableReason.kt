package link.socket.ampere.agents.domain.routing.local

/**
 * The reason codes an on-device engine reports in [LocalCapacity.reason], and
 * that reach a person as the explanation for a call leaving the device
 * (AMPR-327).
 *
 * Codes rather than sentences because they travel: into
 * [RouteFallback.failureReason][link.socket.ampere.agents.domain.event.RoutingEvent.RouteFallback.failureReason],
 * into the event store, and across the Swift boundary. They are declared once,
 * here, so the engine that writes one and the surface that translates it cannot
 * drift apart. Wording belongs to the surface.
 *
 * Not a closed set. An engine may report a reason this object does not list —
 * a thermal state, a vendor's own code — and a surface must be prepared to show
 * one it does not recognise.
 */
object LocalUnavailableReason {

    /** The hardware cannot run the model. Permanent for this device. */
    const val DEVICE_NOT_ELIGIBLE: String = "apple_intelligence_device_not_eligible"

    /** The model is supported but the person has Apple Intelligence turned off. */
    const val NOT_ENABLED: String = "apple_intelligence_not_enabled"

    /** The model is supported and enabled but not yet downloaded or loaded. Transient. */
    const val MODEL_NOT_READY: String = "apple_intelligence_model_not_ready"

    /** The system reported the model unavailable without a reason this build knows. */
    const val UNAVAILABLE: String = "apple_intelligence_unavailable"

    /** The operating system predates the on-device model framework. */
    const val OS_TOO_OLD: String = "on_device_model_requires_newer_os"

    /** No engine is bound on this platform, so there is nothing to ask. */
    const val NO_ENGINE: String = "on_device_engine_not_bound"
}
