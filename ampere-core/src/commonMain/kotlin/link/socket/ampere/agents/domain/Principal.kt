package link.socket.ampere.agents.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The identity on whose authority something was done.
 *
 * This is a carrier, not a policy. What a principal *is* — the device owner, a particular
 * human, an identity delegated to a spawned agent — belongs to the D5 principal contract
 * (AMPR-274). Until D5 locks, the only variant is [Ambient], because it is the only true
 * answer: nothing in AMPERE resolves an acting identity, so everything runs under the
 * process's ambient authority (AMPR-279).
 *
 * The type exists ahead of its semantics so that provenance records authority at the moment
 * of production rather than having it backfilled. Every place that stamps [Ambient] today is
 * a place D5 has to decide about, and `Principal.Ambient` is greppable in a way that an
 * omitted field is not.
 *
 * Adding a variant is a wire-format change: give it a stable `@SerialName` in the
 * `Principal.<Name>` form, never rename an existing one, and update the Emission concept cell.
 */
@Serializable
sealed interface Principal {

    /**
     * No principal was resolved. The work ran under whatever authority the process holds.
     *
     * This is an honest record of the current state, not a default to reach for: a caller
     * that has a real identity to hand must not stamp `Ambient` for convenience.
     */
    @Serializable
    @SerialName("Principal.Ambient")
    data object Ambient : Principal
}
