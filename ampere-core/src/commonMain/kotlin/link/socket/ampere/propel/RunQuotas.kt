package link.socket.ampere.propel

import kotlin.time.Duration
import link.socket.ampere.api.AmpereStableApi
import link.socket.ampere.field.Hardness
import link.socket.ampere.field.Rule

/**
 * The ceilings a consumer puts on one hosted run (AMPR-445, F14).
 *
 * Each quota is optional, and a run with none set — [None], the default — is capped by
 * nothing but [RunPolicy.cycles], exactly as before quotas existed. Every quota that is set
 * becomes one Hard `Rule.Quota` the Breaker admits each of the run's steps against, so a
 * reached quota stops a step the same way a denied permission does: the step fails with the
 * quota, its limit and the run's spend as its reason. Unlike an ordinary failure, which
 * skips only its dependents (AMPR-450), a failure once a quota is reached stops the walk:
 * the steps after it in that plan are skipped, and the run ends `Partial` if an earlier
 * step succeeded, `Failed` otherwise.
 *
 * **Checked before a step, never during one.** The Breaker reads the run's spend as each
 * step is admitted. A step admitted under its limit runs to the end, so a run can finish
 * over a quota by whatever its last admitted step spent; what a quota guarantees is that no
 * step *starts* once the limit is reached. A step at exactly the limit does not start.
 *
 * **Read from the record.** Spend is never a counter the run keeps beside its events: every
 * quota is read from the events persisted under the run id, so a quota and the trace cannot
 * disagree. A step the Breaker could not read the record for fails closed.
 *
 * @property tokens The most tokens the run may spend across every model call filed under
 *   it, from PERCEIVE on: the sum of `inputTokens + outputTokens` over the run's
 *   `ProviderCallCompletedEvent`s. A call whose transport reported no counts adds nothing,
 *   so this caps only what was reported. Decision calls are not generative calls and leave
 *   no such event, so they are not counted.
 * @property wallClock How long the run may go on: from its first persisted event to now,
 *   both read off the seat's door clock — the clock that stamps `recorded_at` — so a test
 *   that pins time pins this too.
 * @property steps The most plan steps the run may execute across all of its cycles: the
 *   run's `PlanStepCompleted` events. A step the Breaker stopped is on the record as a
 *   completed, failed step, and counts.
 */
@AmpereStableApi
data class RunQuotas(
    val tokens: Long? = null,
    val wallClock: Duration? = null,
    val steps: Int? = null,
) {

    init {
        require(tokens == null || tokens > 0) { "a token quota allows at least one token; got $tokens" }
        require(wallClock == null || wallClock.isPositive()) {
            "a wall-clock quota allows some time; got $wallClock"
        }
        require(steps == null || steps > 0) { "a step quota allows at least one step; got $steps" }
    }

    /**
     * The quotas as the Breaker's rules: one Hard `Rule.Quota` per quota set, in the order
     * tokens, time, steps. Empty for [None].
     */
    internal fun asRules(): List<Pair<Rule, Hardness>> = buildList {
        tokens?.let { add(Rule.Quota.Tokens(it) to Hardness.HARD) }
        wallClock?.let { add(Rule.Quota.Time(it) to Hardness.HARD) }
        steps?.let { add(Rule.Quota.Count(limit = it, of = STEPS) to Hardness.HARD) }
    }

    companion object {

        /** No quota. The default: a run capped by its cycles alone. */
        val None: RunQuotas = RunQuotas()

        /**
         * The label a `Rule.Quota.Count` over a run's executed steps carries, and the only
         * one the Breaker can count today.
         */
        const val STEPS: String = "steps"
    }
}
