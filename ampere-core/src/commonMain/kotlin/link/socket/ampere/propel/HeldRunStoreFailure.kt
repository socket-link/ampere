package link.socket.ampere.propel

import link.socket.ampere.agents.domain.RunId

/**
 * [HeldRunStore.hold] was handed a record for a run that already has a *different* unclaimed
 * record.
 *
 * An identical re-hold succeeds instead: a writer that could not tell whether its write landed
 * retries, and that retry is indistinguishable from the first attempt. What is refused is
 * overwriting a record nobody has taken up yet — it is the only copy of what the run was going to
 * do, and two different accounts of one held run cannot both be right.
 */
class HeldRunInUseException(
    val runId: RunId,
) : Exception("Run '$runId' already has a different unclaimed held record")

/**
 * A stored held-run row exists but this build cannot decode its payload — version skew at the
 * held-run boundary (AMPR-364's rule applied to `HeldRunStore`).
 *
 * The record carries a plan, a goal and step outcomes, every one a sealed family a later build may
 * extend, so an older binary reading a database a newer one wrote hits this without anyone
 * extending anything. [HeldRunStore.get] fails this way, and so does [HeldRunStore.claim] — which
 * leaves the row unclaimed for a build that can read it. [HeldRunStore.list] and
 * [HeldRunStore.delete] never do: they work from the scalar columns.
 */
class UndecodableHeldRunException(
    val runId: RunId,
    val reason: String,
    cause: Throwable? = null,
) : Exception("Stored held run '$runId' could not be decoded: $reason", cause)
