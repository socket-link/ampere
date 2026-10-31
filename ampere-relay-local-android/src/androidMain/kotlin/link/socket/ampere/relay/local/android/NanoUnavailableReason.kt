package link.socket.ampere.relay.local.android

/**
 * The reason codes [MlKitLocalInferenceEngine] reports in
 * [LocalCapacity.reason][link.socket.ampere.agents.domain.routing.local.LocalCapacity.reason].
 *
 * Codes, not sentences, for the reason `LocalUnavailableReason` gives in ampere-core: they
 * travel into routing events and the event store, and wording belongs to the surface. They
 * live here rather than beside Apple's codes until the shipping engine (Phase 2) moves them
 * into ampere-core, where a surface can translate them.
 */
object NanoUnavailableReason {

    /**
     * ML Kit says the model is unavailable: the device does not support it, or has not yet
     * fetched the configuration that says it does. ML Kit does not tell the two apart.
     */
    const val UNAVAILABLE: String = "gemini_nano_unavailable"

    /** Supported here but not downloaded. Nothing downloads it until a host asks ML Kit to. */
    const val NOT_DOWNLOADED: String = "gemini_nano_not_downloaded"

    /** Supported here and downloading now. Transient. */
    const val DOWNLOADING: String = "gemini_nano_downloading"

    /**
     * Asking the model's status failed: the system service that runs it could not be
     * reached, or needs an update. Transient until proven otherwise; the next probe asks again.
     */
    const val STATUS_FAILED: String = "gemini_nano_status_failed"
}
