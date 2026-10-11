package link.socket.ampere.propel

import kotlinx.coroutines.CancellationException
import link.socket.ampere.agents.domain.event.PermissionDeniedReason
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.execution.executor.ExecutorId
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
 * @param userGrants The consent a plug tool is checked against — the same provider the
 *   agent's `ToolExecutionEngine` was built with, so the Breaker and the engine can never
 *   disagree about a step.
 * @param eventApi Where a Hard access trip is reported as a `PermissionDeniedEvent`, so a
 *   step the Breaker stops is as visible as one the engine used to stop. Null publishes
 *   nothing.
 * @param executorId The agent the steps are admitted for; names the denial event.
 */
class Breaker(
    private val userGrants: suspend (PlugManifest) -> UserGrants,
    private val eventApi: AgentEventApi? = null,
    private val executorId: ExecutorId,
) {

    /**
     * Folds [rules] over the step that proposes to run [tool].
     *
     * A Hard trip from a `Rule.AccessPolicy` also publishes the `PermissionDeniedEvent`
     * the engine would have published, once, before the step is reported failed. A Soft
     * access breach publishes nothing: the step goes on to the engine, whose own gate will
     * report it if it still stands.
     */
    suspend fun admit(
        tool: Tool<*>,
        rules: List<Pair<Rule, Hardness>>,
    ): Result<Admission> {
        var resistance = 0.0
        for ((rule, hardness) in rules) {
            val evaluated = try {
                evaluate(tool, rule)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Result.failure(
                    IllegalStateException(
                        "Could not evaluate $rule for tool '${tool.id}': ${e::class.simpleName}: ${e.message}",
                        e,
                    ),
                )
            }
            val breach = evaluated ?: continue

            when (hardness) {
                Hardness.HARD -> {
                    breach.denial?.let { denial ->
                        publishPermissionDenied(
                            eventApi = eventApi,
                            executorId = executorId,
                            manifest = denial.manifest,
                            tool = tool,
                            permission = denial.permission,
                            reason = denial.reason,
                        )
                    }
                    return Result.success(Admission.Trip(breach.reason))
                }
                Hardness.SOFT -> resistance += SOFT_BREACH.value
            }
        }
        return Result.success(
            if (resistance > 0.0) Admission.Resist(Resistance(resistance)) else Admission.Pass,
        )
    }

    /** The breach [rule] finds in running [tool], or null when it holds. Throws when it cannot say. */
    private suspend fun evaluate(tool: Tool<*>, rule: Rule): Breach? = when (rule) {
        is Rule.AccessPolicy -> evaluateAccess(tool, rule)
        else -> throw UnsupportedOperationException(
            "the Breaker has no evaluation for ${rule::class.simpleName} yet",
        )
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
    private class Breach(val reason: String, val denial: Denial?) {
        companion object {
            fun denied(
                manifest: PlugManifest,
                tool: Tool<*>,
                permission: PlugPermission,
                reason: PermissionDeniedReason,
            ) = Breach(
                reason = permissionDeniedMessage(manifest, tool, permission, reason),
                denial = Denial(manifest, permission, reason),
            )
        }
    }

    private class Denial(
        val manifest: PlugManifest,
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
         * The rules a step that runs [tool] is admitted against today: one Hard
         * `Rule.AccessPolicy` over the tool's plug manifest, or none for a tool with no
         * manifest. The rest of F14 (quotas, transitions, requirements) joins this list
         * when it is decided.
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
    }
}
