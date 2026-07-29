package link.socket.ampere.agents.domain.routing.capability

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * Ordinal capability tier for a model, independent of billing/quota tiers
 * ([link.socket.ampere.domain.billing.Tier] et al.). Higher ordinal = higher rung.
 *
 * Five rungs are defined as companion constants. Names carry no quality adjectives
 * ("fast", "premium", etc.) so they remain stable as the landscape evolves — a rung
 * is placed by what a model can *do*, not by its current reputation or price:
 *
 * - [ZERO] — 0W, on-device generation (e.g. Apple Foundation Models). Always the
 *   cheapest candidate via [ModelDescriptor.routingCostPerWatt] when its
 *   [ModelDescriptor.cost] is [CostPolicy.Free].
 * - [ONE] — baseline hosted capability: general instruction-following without a
 *   claim to strong reasoning, large context, or tool use.
 * - [TWO] — adds one differentiating axis over [ONE] (e.g. reliable tool/function
 *   calling, or a materially larger context window), but not multiple at once.
 * - [THREE] — reliable multi-step reasoning (agentic tool use, planning) plus at
 *   least one other strong axis (context, multimodal input).
 * - [FOUR] — frontier-class: strong on essentially every axis (reasoning, context,
 *   tool use, multimodal input) with no notable gap.
 *
 * A new model is placed by matching this list, top-down from [FOUR], to the
 * highest rung whose bar it clears — not by comparison to [MODEL_RUNGS] entries.
 *
 * ## Compatibility contract
 *
 * - Ordinals are a stable, released API: [ZERO] is always `0`, [FOUR] is always
 *   `4`, and so on. Once a caller declares a floor against one of these ordinals
 *   (e.g. [CapabilityRequirement.minRung]), that ordinal is a commitment other
 *   code and stored configuration may depend on.
 * - No rung may ever be inserted between two existing ones. Because ordinals are
 *   a plain [Int] scale, "insert a rung between [TWO] and [THREE]" has no
 *   non-breaking representation — every existing rung at or above the insertion
 *   point would have to shift, silently invalidating any floor already declared
 *   against it. A finer distinction must be a new rung *above* [FOUR] or a
 *   separate axis on [ModelDescriptor], never a squeeze between named constants.
 * - The space is intentionally open below [ONE] and above [FOUR]: the backing
 *   `Int` is constructible for any value (`CapabilityRung(-1)`, `CapabilityRung(7)`
 *   both compile), but only [ZERO] through [FOUR] are named and rated today. An
 *   unnamed ordinal is not itself a contract violation, but nothing in
 *   [MODEL_RUNGS] or [ModelDescriptor] currently assigns models there.
 * - Renaming or removing an existing constant, or changing the number of rungs,
 *   is a breaking API change and out of scope for ordinary rung additions.
 *
 * ## Floors
 *
 * A [CapabilityRequirement.minRung] (or any other floor expressed as a
 * [CapabilityRung]) is a *minimum*, not a target: a model at or above the floor
 * satisfies it, and routing is free to pick any satisfying model on other
 * grounds (cost, availability). When two or more floors apply to the same call
 * (e.g. an agent-level policy floor and a per-call requirement), they compose
 * with `maxOf` — the effective floor is the highest of the inputs, never an
 * average or the most recently applied one.
 *
 * An unmet floor is terminal, not a downgrade: if no candidate model satisfies
 * the requested [CapabilityRequirement.minRung], routing fails closed (see
 * [RoutingResolution.FloorUnmet]) rather than silently serving a sub-floor
 * model. This holds for every route — a rule matching on an unrelated axis
 * (phase, agent, role, tag) does not bypass the floor check.
 */
@Serializable
@JvmInline
value class CapabilityRung(val ordinal: Int) : Comparable<CapabilityRung> {

    override fun compareTo(other: CapabilityRung): Int =
        ordinal.compareTo(other.ordinal)

    companion object {
        val ZERO = CapabilityRung(0)
        val ONE = CapabilityRung(1)
        val TWO = CapabilityRung(2)
        val THREE = CapabilityRung(3)
        val FOUR = CapabilityRung(4)
    }
}
