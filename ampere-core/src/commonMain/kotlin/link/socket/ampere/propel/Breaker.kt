package link.socket.ampere.propel

import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.event.PermissionDeniedReason
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.event.ProviderCallCompletedEvent
import link.socket.ampere.agents.events.DecodedRows
import link.socket.ampere.agents.events.StoredEvent
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.execution.executor.RoutedExecutor
import link.socket.ampere.agents.execution.permissionDeniedMessage
import link.socket.ampere.agents.execution.publishPermissionDenied
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.field.Admission
import link.socket.ampere.field.Hardness
import link.socket.ampere.field.Resistance
import link.socket.ampere.field.Rule
import link.socket.ampere.plug.PlugManifest
import link.socket.ampere.plug.permission.GateResult
import link.socket.ampere.plug.permission.PlugPermission
import link.socket.ampere.plug.permission.PlugPermissionGate
import link.socket.ampere.plug.permission.PlugToolCall
import link.socket.ampere.plug.permission.UserGrants

/**
 * Admits a plan step before its tool runs (F13, F15, F16).
 *
 * A plan step is the model's proposed Action, and the tool it nominates is what it
 * proposes to do; the Breaker decides whether that may happen. It folds `(Rule, Hardness)`
 * pairs in order:
 *
 * - the first [Hardness.HARD] breach trips, and nothing after it is evaluated — a Hard
 *   trip cannot be outweighed;
 * - each [Hardness.SOFT] breach adds [SOFT_BREACH] to the step's resistance;
 * - no breach at all is [Admission.Pass].
 *
 * Every rule is deterministic and none is model-evaluated (F15, AMPR-381 J7). There is no
 * judgment parameter: when a judgment reaches the Breaker it will arrive as one more rule
 * a caller adds, which can resist or trip and has no way to clear a Hard trip.
 *
 * The result is a [Result] for the same reason a `StepResult` and a `GateResult` are not
 * exceptions (F6 amendment): a breached rule is an answer, so it is an [Admission]; the
 * failure arm is reserved for not being able to answer — a grant provider that threw, or
 * a rule this Breaker has no evaluation for. Neither is ever read as a pass.
 *
 * The pairs are not persisted `Constraint`s: no store holds them yet, and a rule derived
 * from a manifest has no binding event to name as its provenance. Whoever adds the store
 * maps a `Constraint` to `rule to hardness` and calls [admit] unchanged.
 *
 * The rule set (F14, AMPR-445):
 *
 * - `Rule.AccessPolicy` — `PlugPermissionGate`, unchanged (F15). See [rulesFor].
 * - `Rule.Quota` — a run's token, wall-clock and step spend, read from the events persisted
 *   under [runId] and never from a counter. A quota is breached once the spend *reaches* its
 *   limit. See [RunQuotas].
 * - `Rule.Transition` — a status change, checked by [admitTransition]. A plan step proposes
 *   no status change, so one handed to [admit] cannot be evaluated.
 * - `Rule.Window` — a recurring time-of-day window, read on [clock] in the rule's own zone
 *   and never the device's, over the tool ids it names. A step with no tool is inside a
 *   window only when the window names no tools. See [evaluateWindow].
 * - `Rule.Requirements` is not the Breaker's. It states what a finished execution must
 *   satisfy — tests, lint, compatibility — and nothing that has not run yet can be checked
 *   against it. Verification checks it; handed here, it cannot be evaluated.
 *
 * Every other rule (`Dependency`, `Deadline`, `Cadence`) has no evaluation yet. A rule this
 * Breaker cannot evaluate fails the admission, which is never read as a pass. [canEvaluate]
 * says which kinds those are, so a caller handed rules from outside — a hosted run's
 * `RunPolicy.rules` — can refuse one before any step depends on it.
 *
 * @param userGrants The consent a plug tool is checked against — the same provider the
 *   agent's `ToolExecutionEngine` was built with, so the Breaker and the engine can never
 *   disagree about a step.
 * @param eventApi Where a Hard access trip is reported as a `PermissionDeniedEvent`, so a
 *   step the Breaker stops is as visible as one the engine used to stop. Null publishes
 *   nothing. `dispatchPlanStep` records each step's admission through the same door
 *   (AMPR-472), which is why it is visible inside the module.
 * @param actorId The agent the steps are admitted for; names the denial event. Called
 *   `executorId` until AMPR-443 (F17), though it always held the agent's id.
 * @param runId The run whose spend a `Rule.Quota` is read against. Null — an agent outside
 *   any run — makes a quota unevaluable.
 * @param runEvents Reads the events persisted under a run, in sequence order. Defaults to
 *   [eventApi]'s [AgentEventApi.getRunEvents]: the door is the record.
 * @param clock What "now" is for a `Rule.Quota.Time` and a `Rule.Window` — by default
 *   [eventApi]'s own clock, the one that stamped the `recorded_at` the run's elapsed time is
 *   measured from (F29).
 */
class Breaker(
    private val userGrants: suspend (PlugManifest) -> UserGrants,
    internal val eventApi: AgentEventApi? = null,
    private val actorId: AgentId,
    private val runId: RunId? = null,
    private val runEvents: (suspend (RunId) -> Result<DecodedRows<StoredEvent>>)? =
        eventApi?.let { api -> api::getRunEvents },
    private val clock: Clock = eventApi?.clock ?: Clock.System,
) {

    /**
     * Folds [rules] over the step that proposes to run [tool].
     *
     * [tool] is null for a step that nominates none — a reasoning step, which is one model
     * call and so spends against a run's quotas like any other step. Such a step has no
     * manifest, so a `Rule.AccessPolicy` cannot be evaluated against it.
     *
     * A Hard trip from a `Rule.AccessPolicy` also publishes the `PermissionDeniedEvent`
     * the engine would have published, once, before the step is reported failed. A Soft
     * access breach publishes nothing: the step goes on to the engine, whose own gate will
     * report it if it still stands.
     *
     * The run's record is read at most once per admission, however many quotas are in
     * [rules], so every quota in one admission is measured against the same spend.
     *
     * [admitDetailed] is the same fold with the breached rules kept beside the answer.
     */
    suspend fun admit(
        tool: Tool<*>?,
        rules: List<Pair<Rule, Hardness>>,
    ): Result<Admission> = admitDetailed(tool, rules).map { it.admission }

    /**
     * [admit], with the rules that breached beside the answer (AMPR-472, F33).
     *
     * The same fold over the same rules in the same order, stopping at the same Hard trip,
     * publishing the same denial. All this adds is what [admit] used to drop once it had
     * built the [Admission]: which rules breached. A consumer can then be told which rule
     * stopped a step without matching the reason's text.
     */
    suspend fun admitDetailed(
        tool: Tool<*>?,
        rules: List<Pair<Rule, Hardness>>,
    ): Result<Admitted> {
        val record = RunRecordRead()
        var resistance = 0.0
        val breached = mutableListOf<Pair<Rule, Hardness>>()
        for ((rule, hardness) in rules) {
            val evaluated = try {
                evaluate(tool, rule, record)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Result.failure(
                    IllegalStateException(
                        "Could not evaluate $rule for ${tool.describe()}: ${e::class.simpleName}: ${e.message}",
                        e,
                    ),
                )
            }
            val breach = evaluated ?: continue
            breached += rule to hardness

            when (hardness) {
                Hardness.HARD -> {
                    breach.denial?.let { denial ->
                        publishPermissionDenied(
                            eventApi = eventApi,
                            actorId = actorId,
                            manifest = denial.manifest,
                            tool = denial.tool,
                            permission = denial.permission,
                            reason = denial.reason,
                        )
                    }
                    return Result.success(Admitted(Admission.Trip(breach.reason), breached.toList()))
                }
                Hardness.SOFT -> resistance += SOFT_BREACH.value
            }
        }
        val admission = if (resistance > 0.0) Admission.Resist(Resistance(resistance)) else Admission.Pass
        return Result.success(Admitted(admission, breached.toList()))
    }

    /**
     * What [admitDetailed] decided, and which rules decided it (AMPR-472, F33).
     *
     * @property admission The answer, exactly what [admit] returns for the same call.
     * @property breached Every rule the step breached, with its hardness, in the order the
     *   fold met them. Empty for a [Admission.Pass]. A [Admission.Resist]'s are all Soft. A
     *   [Admission.Trip]'s may start with Soft ones and always end with the Hard rule that
     *   tripped, because nothing after it is evaluated.
     */
    data class Admitted(
        val admission: Admission,
        val breached: List<Pair<Rule, Hardness>>,
    ) {

        /** The Hard rule a trip names: the last one breached. Null for a pass or a resist. */
        val tripped: Rule?
            get() = if (admission is Admission.Trip) breached.lastOrNull()?.first else null
    }

    /** The breach [rule] finds in running [tool], or null when it holds. Throws when it cannot say. */
    private suspend fun evaluate(tool: Tool<*>?, rule: Rule, record: RunRecordRead): Breach? = when (rule) {
        is Rule.AccessPolicy -> evaluateAccess(
            tool = tool ?: error(
                "an access policy is checked against a tool's plug manifest, and this step nominates no tool",
            ),
            rule = rule,
        )
        is Rule.Quota -> evaluateQuota(rule, record.events())
        is Rule.Transition -> throw UnsupportedOperationException(
            "a plan step proposes no status change for a Transition to check; " +
                "a status change is admitted with Breaker.admitTransition",
        )
        is Rule.Requirements -> throw UnsupportedOperationException(
            "Requirements are post-conditions on a finished execution and are checked by verification, " +
                "not before a step runs",
        )
        is Rule.Window -> evaluateWindow(tool, rule)
        else -> throw UnsupportedOperationException(
            "the Breaker has no evaluation for ${rule::class.simpleName} yet",
        )
    }

    /**
     * A run's spend against [rule], read from [events] — the run's persisted record — and
     * breached once the spend reaches the limit.
     *
     * - Tokens: `inputTokens + outputTokens` over every `ProviderCallCompletedEvent`; a count
     *   the transport did not report adds nothing.
     * - Time: from the first event's `recorded_at` to [clock]. A run with nothing on the
     *   record yet has spent no time.
     * - Count: every `PlanStepCompleted`, when the label is [RunQuotas.STEPS]. No other
     *   label has anything on the record to count.
     */
    private fun evaluateQuota(rule: Rule.Quota, events: List<StoredEvent>): Breach? = when (rule) {
        is Rule.Quota.Tokens -> {
            val spent = events
                .mapNotNull { stored -> (stored.event as? ProviderCallCompletedEvent)?.usage }
                .sumOf { usage -> (usage.inputTokens ?: 0).toLong() + (usage.outputTokens ?: 0).toLong() }
            if (spent >= rule.limit) {
                Breach(
                    "token quota reached: run '${requireRunId()}' has spent $spent tokens " +
                        "against a limit of ${rule.limit}",
                )
            } else {
                null
            }
        }
        is Rule.Quota.Time -> {
            val elapsed = events.minByOrNull { it.sequence }
                ?.let { first -> clock.now() - first.recordedAt }
                ?: Duration.ZERO
            if (elapsed >= rule.limit) {
                Breach(
                    "wall-clock quota reached: run '${requireRunId()}' has run for $elapsed " +
                        "against a limit of ${rule.limit}",
                )
            } else {
                null
            }
        }
        is Rule.Quota.Count -> {
            require(rule.of == RunQuotas.STEPS) {
                "a count quota can only count '${RunQuotas.STEPS}'; nothing on a run's record counts '${rule.of}'"
            }
            val executed = events.count { it.event is PlanEvent.PlanStepCompleted }
            if (executed >= rule.limit) {
                Breach(
                    "step quota reached: run '${requireRunId()}' has executed $executed steps " +
                        "against a limit of ${rule.limit}",
                )
            } else {
                null
            }
        }
    }

    private fun requireRunId(): RunId =
        runId ?: error("a quota is read from a run's record, and this Breaker admits steps for no run")

    /**
     * Whether running [tool] now falls inside [rule]'s window: [clock] read in the rule's
     * own zone, never the device's (F29).
     *
     * - The selector first. An empty [Rule.Window.appliesTo] makes every step a candidate,
     *   one with no tool included. Otherwise only a tool named in it is — by its own id or,
     *   for a hosted run's `<seat>/<tool>`, by the bare id the consumer handed the run — and
     *   a step with no tool never is, so a run-level check that admits `tool = null` reads a
     *   Window over named tools as holding, and its trip stays one step's failure.
     * - Then the window. When `from < until`, inside is `from <= time < until` on a day in
     *   `days`. When `until <= from` it wraps midnight: `time >= from` on a day in `days`, or
     *   `time < until` on the day after one.
     *
     * A zone the platform cannot resolve throws, which [admit] reports as a failure.
     */
    private fun evaluateWindow(tool: Tool<*>?, rule: Rule.Window): Breach? {
        val zone = try {
            TimeZone.of(rule.timezone)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("'${rule.timezone}' is not a time zone this platform can resolve", e)
        }
        val candidate = when {
            rule.appliesTo.isEmpty() -> true
            tool == null -> false
            else -> tool.id in rule.appliesTo || RoutedExecutor.bareIdOf(tool.id) in rule.appliesTo
        }
        if (!candidate) return null

        val now = clock.now().toLocalDateTime(zone)
        val time = now.time
        val inside = if (rule.from < rule.until) {
            time >= rule.from && time < rule.until && now.dayOfWeek in rule.days
        } else {
            (time >= rule.from && now.dayOfWeek in rule.days) ||
                (time < rule.until && now.date.minus(1, DateTimeUnit.DAY).dayOfWeek in rule.days)
        }
        if (!inside) return null

        val what = if (tool == null) "this step" else "'${tool.id}'"
        return Breach(
            "quiet window: it is ${time.clockText()} ${rule.timezone}, " +
                "inside ${rule.from.clockText()}–${rule.until.clockText()}; $what may not run",
        )
    }

    /**
     * One admission's read of the run's record: made on the first quota that needs it and
     * reused by the rest, so nothing is read for an admission with no quota in it.
     *
     * A read that failed, or that skipped a row it could not decode, cannot say what the
     * run has spent, and throws — which [admit] reports as a failure, never a pass.
     */
    private inner class RunRecordRead {
        private var read: List<StoredEvent>? = null

        suspend fun events(): List<StoredEvent> {
            read?.let { return it }
            val id = requireRunId()
            val reader = runEvents
                ?: error("a quota is read from a run's record, and this Breaker has no door to read it through")
            val rows = reader(id).getOrElse { e ->
                throw IllegalStateException("the record of run '$id' could not be read: ${e.message}", e)
            }
            check(rows.undecodable.isEmpty()) {
                "the record of run '$id' has ${rows.undecodableCount} row(s) this build cannot decode, " +
                    "so its spend is unknown"
            }
            return rows.toList().also { read = it }
        }
    }

    /**
     * `PlugPermissionGate.check`, unchanged (F15), over the tool's own manifest.
     *
     * The rule's labels are resolved back to the typed permissions the manifest declares —
     * never parsed from the strings — and handed to the gate as the call's requested
     * permissions. The gate unions them with the manifest's, so the check is the same one
     * the engine makes; a label the manifest does not declare is a rule this step cannot
     * be checked against, and so a failure.
     */
    private suspend fun evaluateAccess(tool: Tool<*>, rule: Rule.AccessPolicy): Breach? {
        val manifest = tool.plugManifest
            ?: error("an access policy needs a plug manifest to check against, and tool '${tool.id}' has none")
        val declared = manifest.requiredPermissions.associateBy { accessLabel(it) }
        val unknown = rule.required - declared.keys
        require(unknown.isEmpty()) {
            "plug '${manifest.id.value}' declares no permission labelled ${unknown.joinToString()}"
        }

        val gateResult = PlugPermissionGate.check(
            toolCall = PlugToolCall(
                plugId = manifest.id,
                toolId = tool.id,
                requestedPermissions = rule.required.map { declared.getValue(it) },
            ),
            manifest = manifest,
            userGrants = userGrants(manifest),
        )
        return when (gateResult) {
            GateResult.Allow -> null
            is GateResult.DenyMissing ->
                Breach.denied(manifest, tool, gateResult.permission, PermissionDeniedReason.MISSING_GRANT)
            is GateResult.DenyRevoked ->
                Breach.denied(manifest, tool, gateResult.permission, PermissionDeniedReason.REVOKED_GRANT)
        }
    }

    /** A breached rule: why, and — for an access policy — what to report it as. */
    private class Breach(val reason: String, val denial: Denial? = null) {
        companion object {
            fun denied(
                manifest: PlugManifest,
                tool: Tool<*>,
                permission: PlugPermission,
                reason: PermissionDeniedReason,
            ) = Breach(
                reason = permissionDeniedMessage(manifest, tool, permission, reason),
                denial = Denial(manifest, tool, permission, reason),
            )
        }
    }

    private class Denial(
        val manifest: PlugManifest,
        val tool: Tool<*>,
        val permission: PlugPermission,
        val reason: PermissionDeniedReason,
    )

    companion object {

        /**
         * What one breached Soft rule adds to a step's resistance. Every rule weighs the
         * same until F28 settles how resistance is weighed, so a [Admission.Resist]'s
         * degree counts the Soft rules a step breached.
         */
        val SOFT_BREACH: Resistance = Resistance(1.0)

        /**
         * The rules a step that runs [tool] is admitted against on the tool's account: one
         * Hard `Rule.AccessPolicy` over the tool's plug manifest, or none for a tool with no
         * manifest. A run's quotas are the run's, not the tool's, and are added by whoever
         * drives the run (`RunQuotas`); a transition is admitted by [admitTransition]; and
         * `Rule.Requirements` belongs to verification (F14).
         */
        fun rulesFor(tool: Tool<*>): List<Pair<Rule, Hardness>> {
            val manifest = tool.plugManifest ?: return emptyList()
            val required = manifest.requiredPermissions.mapTo(linkedSetOf()) { accessLabel(it) }
            return listOf(Rule.AccessPolicy(required) to Hardness.HARD)
        }

        /**
         * The label a permission is carried under in a `Rule.AccessPolicy`: its own
         * `toString`, the same text a denial message and `PermissionDeniedEvent`'s summary
         * print. `field/` cannot name `PlugPermission`, so the rule holds labels and the
         * Breaker resolves them back against the manifest, never the other way round.
         */
        fun accessLabel(permission: PlugPermission): String = permission.toString()

        /**
         * The name a breached [rule] goes on the record under (AMPR-472, F33): the `Rule`
         * case as `field/` spells it, plus whatever tells two rules of that case apart.
         *
         * - `AccessPolicy:<plug id>`, the plug whose manifest [tool] carries. A policy over
         *   a tool with no manifest cannot breach, and is `AccessPolicy` alone.
         * - `Quota.Tokens`, `Quota.Time`, and `Quota.Count:<what is counted>` —
         *   `Quota.Count:steps` for a run's [RunQuotas.steps].
         * - `Transition`, `Requirements`, `Dependency`, `Deadline`, `Cadence`, `Window`.
         *
         * Each name is written out here rather than read off the class, so renaming a case
         * does not rename what a consumer matches on. A limit is never part of a name: it
         * says how far the rule was exceeded, which the trip's reason already says, not
         * which rule it was.
         */
        fun ruleName(rule: Rule, tool: Tool<*>?): String = when (rule) {
            is Rule.AccessPolicy -> tool?.plugManifest?.let { "AccessPolicy:${it.id.value}" } ?: "AccessPolicy"
            is Rule.Quota.Tokens -> "Quota.Tokens"
            is Rule.Quota.Time -> "Quota.Time"
            is Rule.Quota.Count -> "Quota.Count:${rule.of}"
            is Rule.Transition -> "Transition"
            is Rule.Requirements -> "Requirements"
            is Rule.Dependency -> "Dependency"
            is Rule.Deadline -> "Deadline"
            is Rule.Cadence -> "Cadence"
            is Rule.Window -> "Window"
        }

        /**
         * Whether [admit] has an evaluation for [rule]'s kind (AMPR-512), so a caller can
         * refuse a rule up front rather than have every step it guards fail closed.
         *
         * - `AccessPolicy` — yes, against the step's tool; a step that nominates no tool, or
         *   whose tool carries no plug manifest, still cannot be checked against one.
         * - `Quota.Tokens`, `Quota.Time` — yes. `Quota.Count` — only when it counts
         *   [RunQuotas.STEPS], the one label a run's record has anything to count for.
         * - `Transition` — no: a plan step proposes no status change ([admitTransition]).
         * - `Requirements` — no: a post-condition, checked by verification.
         * - `Dependency`, `Deadline`, `Cadence` — not yet.
         *
         * Beside [ruleName], and a `when` with no `else` like it, so a `Rule` case added later
         * is given an answer in both before this compiles.
         */
        fun canEvaluate(rule: Rule): Boolean = when (rule) {
            is Rule.AccessPolicy -> true
            is Rule.Quota.Tokens -> true
            is Rule.Quota.Time -> true
            is Rule.Quota.Count -> rule.of == RunQuotas.STEPS
            is Rule.Transition -> false
            is Rule.Requirements -> false
            is Rule.Dependency -> false
            is Rule.Deadline -> false
            is Rule.Cadence -> false
        }

        /**
         * Admits a move from state [from] to state [to] under [rule] (F14).
         *
         * The one evaluation of a `Rule.Transition`: a move is allowed when [to] is among the
         * states [rule] lists for [from], and a state the rule does not list may move nowhere.
         * Deterministic, and decided from the rule alone, which is why it needs no instance.
         * `Ticket.transitionTo` goes through here with `TicketStatus.asTransitionRule()`, so a
         * ticket's lifecycle and the Breaker share one implementation of the rule.
         *
         * @return [Admission.Pass], or a [Admission.Trip] naming the move and what [from] may
         *   move to instead. A transition rule is always Hard, so there is no `Resist`.
         */
        fun admitTransition(rule: Rule.Transition, from: String, to: String): Admission {
            val allowed = rule.allowed[from].orEmpty()
            if (to in allowed) return Admission.Pass
            val instead = if (allowed.isEmpty()) "nothing" else allowed.joinToString()
            return Admission.Trip("transition not allowed: '$from' may not move to '$to'; it may move to $instead")
        }

        /** `22:14`, or `22:14:30` when there are seconds: what a clock face would show. */
        private fun LocalTime.clockText(): String {
            val hhmm = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
            return if (second == 0) hhmm else "$hhmm:${second.toString().padStart(2, '0')}"
        }

        private fun Tool<*>?.describe(): String =
            if (this == null) "a step that nominates no tool" else "tool '$id'"
    }
}
