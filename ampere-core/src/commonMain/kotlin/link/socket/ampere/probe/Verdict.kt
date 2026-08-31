package link.socket.ampere.probe

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The outcome of one [Probe] evaluation, fixed at four values.
 *
 * A Boolean was rejected twice over: two independent recons each needed a
 * third value beyond pass/fail, and they needed *different* thirds — so both
 * are here. [Warn] is decided-and-bad but not disqualifying (a 210 CFM filter
 * on a 226 CFM fan). [Undetermined] is *not* decided, because the evidence
 * does not exist (a grille that publishes no CFM); it must never render as a
 * soft pass — convict-but-not-acquit.
 */
@Serializable
sealed interface Verdict {

    val reason: String?

    /** The check holds. [reason] is optional — a clean pass needs no explanation. */
    @Serializable
    @SerialName("verdict.holds")
    data class Holds(override val reason: String? = null) : Verdict

    /** Decided, and bad, but not disqualifying. */
    @Serializable
    @SerialName("verdict.warn")
    data class Warn(override val reason: String) : Verdict

    /** Decided, and disqualifying. */
    @Serializable
    @SerialName("verdict.violated")
    data class Violated(override val reason: String) : Verdict

    /**
     * Not decided, because the evidence does not exist or could not be used.
     * Carries a machine-readable [cause] because "this part has no published
     * spec" (remedy: ask the human) and "this page needed a JS engine"
     * (remedy: re-fetch elsewhere) need different next actions.
     */
    @Serializable
    @SerialName("verdict.undetermined")
    data class Undetermined(override val reason: String, val cause: UndeterminedCause) : Verdict
}

/** Machine-readable cause for [Verdict.Undetermined], so consumers can route to the right remedy. */
@Serializable
enum class UndeterminedCause {
    /** The subject carries no evidence for this check (e.g. a spec the manufacturer never published). */
    EVIDENCE_ABSENT,

    /** Evidence exists but could not be read (e.g. a client-rendered page, an image-only spec). */
    EVIDENCE_UNREADABLE,

    /** Evidence exists but is older than the Probe's tolerance. */
    STALE,
}
