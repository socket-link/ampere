package link.socket.ampere.field

import kotlin.time.Duration
import kotlinx.datetime.Instant

/**
 * Knowledge held by a [Subject] about something, with a confidence, a sensitivity, a
 * decay and provenance to the event that produced it.
 *
 * Two shapes, chosen by whether the holder has taken a position:
 *
 * - [Settled] — the holder acts on this. Carries a [confidence] that is always a
 *   number (F10), because a belief with no stated confidence cannot be ranked against
 *   one that has it.
 * - [Unsettled] — the holder has evidence but no position: contradictory observations,
 *   a question raised and not yet answered, a hypothesis awaiting a probe. Its
 *   [confidence] is `null` by construction, so no caller can accidentally rank an open
 *   question as a weakly-held fact.
 *
 * What is *not* here: a `source` on the confidence. F10 as amended puts
 * `Confidence.source { Measured, SelfReported }` on the cognition-layer enum
 * (AMPR-384), and the number stored here is the already-mapped value — `0.25 / 0.5 /
 * 0.75` for a self-reported level, the raw probability for a measured one. Re-declaring
 * the source here would let the two drift.
 *
 * @property holder Who holds the belief. Every belief has one (F9); there is no
 *   holder-less knowledge in the Field, and old rows are backfilled to a `system`
 *   pseudo-Subject rather than left anonymous.
 * @property subject What the belief is about: a Subject, a Goal or an Environment
 *   entity. Never another Belief, by the bound on [Ref].
 * @property content The belief itself, as prose. Structure beyond prose is a later
 *   decision; today's `Knowledge.content` is prose and this does not widen it.
 * @property sensitivity Who may see it; see [Sensitivity].
 * @property decay How the belief ages; see [Decay].
 * @property provenance The event whose fold step formed the belief (F11).
 */
sealed interface Belief {

    val holder: Ref<Subject>
    val subject: Ref<Primitive>
    val content: String
    val sensitivity: Sensitivity
    val decay: Decay
    val provenance: Provenance

    /**
     * Confidence in `[0, 1]`, or `null` when the holder has not settled. Nullable on
     * the interface so a fold can read it uniformly; [Settled] narrows it to `Double`.
     */
    val confidence: Double?

    /** A belief the holder acts on. */
    data class Settled(
        override val holder: Ref<Subject>,
        override val subject: Ref<Primitive>,
        override val content: String,
        override val confidence: Double,
        override val sensitivity: Sensitivity,
        override val decay: Decay = Decay.None,
        override val provenance: Provenance,
    ) : Belief

    /**
     * A belief the holder has not settled. [confidence] is `null` and cannot be set:
     * giving an open question a number is how `Undetermined` becomes a soft pass.
     */
    data class Unsettled(
        override val holder: Ref<Subject>,
        override val subject: Ref<Primitive>,
        override val content: String,
        override val sensitivity: Sensitivity,
        override val decay: Decay = Decay.None,
        override val provenance: Provenance,
    ) : Belief {
        override val confidence: Double?
            get() = null
    }
}

/**
 * Who may read a [Belief]. Present from the first migration (F12) because retrofitting
 * it onto provenance-linked rows later is the most expensive mistake available.
 */
enum class Sensitivity {

    /** Visible to every Subject's Field view and to any replay or bake. */
    PUBLIC,

    /** Visible to the holder's Field view only. */
    PRIVATE,

    /**
     * A belief about a real person. Excluded from other Subjects' Field views and from
     * every replay and bake; may be *judged* only where inference is on-device, and
     * with no on-device judge bound the question resolves to `Undetermined` rather than
     * travelling to a cloud model (F12 as amended by AMPR-381 J11).
     */
    PROTECTED,
}

/**
 * How a [Belief] ages. Data only: the current strength is derived at read time from
 * the decay, the belief's [Provenance.createdAt] and the Field's clock (F29), never
 * stored and never updated in place.
 */
sealed interface Decay {

    /** The belief does not weaken with time. Facts about structure; registrations. */
    data object None : Decay

    /**
     * Strength halves every [halfLife]. Observations of a moving world: a page fetched,
     * a build that passed.
     */
    data class HalfLife(val halfLife: Duration) : Decay

    /** Full strength until [at], nothing after. Deadlines; a token valid until a time. */
    data class ExpiresAt(val at: Instant) : Decay
}
