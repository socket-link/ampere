package link.socket.ampere.field

/**
 * What the Breaker decides about a proposed step before it runs (F13).
 *
 * Three outcomes, and only one of them lets the step through untouched:
 *
 * - [Pass] — no rule was breached.
 * - [Resist] — only [Hardness.SOFT] rules were breached. The step may still run; the
 *   breaches are [Resistance] that ranking weighs.
 * - [Trip] — a [Hardness.HARD] rule was breached. The step is not admitted.
 *
 * **A Hard trip is final.** Nothing evaluated after it in the same admission — a later
 * rule, a Soft pass, or a judgment arriving as evidence (AMPR-381 J6/J7) — can turn a
 * [Trip] from a [Hardness.HARD] rule into a [Pass] or a [Resist]. A judgment may add
 * resistance or trip; it never clears.
 *
 * **This is not `probe.Verdict`.** A `Verdict` is what a Probe *measured* about a static
 * artifact — four-valued, serialized, and carried inside `ProbeEvent.VerdictReached` on
 * the record. An Admission is what the Breaker *decided* about an action about to happen,
 * and it protects rather than reports. The two answer different questions with different
 * value sets: `Undetermined` has no Admission (a rule the Breaker cannot evaluate is an
 * infrastructure failure, never a pass), and `Resist` has no Verdict. Folding one into the
 * other would let a measurement stand in for a decision, or a decision leak onto the
 * record under the measurement's name (F13).
 */
sealed interface Admission {

    /** No rule was breached; the step runs. */
    data object Pass : Admission

    /**
     * Only Soft rules were breached; the step may run, against [degree].
     *
     * @property degree The breached Soft rules' resistance, added together.
     */
    data class Resist(val degree: Resistance) : Admission

    /**
     * A Hard rule was breached; the step does not run.
     *
     * @property reason Why, in words a person reading the trace can act on.
     */
    data class Trip(val reason: String) : Admission
}
