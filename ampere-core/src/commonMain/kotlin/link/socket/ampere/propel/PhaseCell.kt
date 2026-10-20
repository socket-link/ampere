package link.socket.ampere.propel

import link.socket.ampere.llm.decide.stateDigest

/**
 * What one phase of a hosted run last produced, held in memory for the run's length
 * (AMPR-483, epic AMPR-478 row N11).
 *
 * A hosted run's PERCEIVE and RECALL used to run once, in `charge()`, and their outputs
 * lived in loose fields. Once a cycle can re-enter at PERCEIVE (`PerceiveCadence.WhenDrifted`,
 * `EveryCycle`) each of those phases can run more than once per run, and a reader of the run
 * needs to know not only *what* the phase last produced but *from what* and *when*. A cell
 * is that triple: the value, a digest of the input the phase ran over, and the cycle it ran
 * in. A held cell is a phase the cycle skipped — no bracket, no call — which hosted-run
 * invariant 4 already tells apart from a phase that was entered and found nothing.
 *
 * In memory only, by design: nothing persists a cell, nothing puts one on an event, and a
 * resumed run rebuilds its PERCEIVE value from the held record's ideas rather than from a
 * cell. The cross-run design is F25, not this.
 *
 * @property value What the phase produced.
 * @property inputDigest The SHA-256 digest of the input the phase ran over — the rendered
 *   perception context for PERCEIVE, the recall context for RECALL — never the input
 *   itself, which may hold a person's data. Two cells with one digest were asked the same
 *   question.
 * @property cycle The cycle the phase ran in, counting from 1.
 */
internal data class PhaseCell<T>(
    val value: T,
    val inputDigest: String,
    val cycle: Int,
) {

    companion object {

        /**
         * The digest of a phase's input, from the parts that make it up.
         *
         * The parts are joined with a NUL before digesting, so `digest("ab", "c")` and
         * `digest("a", "bc")` are different digests: a boundary between parts is part of the
         * input. One part digests exactly as [stateDigest] would, so a cell over a single
         * rendered string joins to a decision record over the same string.
         */
        fun digest(vararg parts: String): String = stateDigest(parts.joinToString(PART_SEPARATOR))

        private const val PART_SEPARATOR = "\u0000"
    }
}
