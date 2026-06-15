package link.socket.ampere.agents.domain.routing.capability

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * Ordinal capability tier for a model, independent of billing/quota tiers
 * ([link.socket.ampere.domain.billing.Tier] et al.). Higher ordinal = higher rung.
 *
 * Four rungs are defined as companion constants. Names carry no quality adjectives
 * so they remain stable as the landscape evolves.
 */
@Serializable
@JvmInline
value class CapabilityRung(val ordinal: Int) : Comparable<CapabilityRung> {

    override fun compareTo(other: CapabilityRung): Int =
        ordinal.compareTo(other.ordinal)

    companion object {
        val ONE = CapabilityRung(1)
        val TWO = CapabilityRung(2)
        val THREE = CapabilityRung(3)
        val FOUR = CapabilityRung(4)
    }
}
