package link.socket.ampere.agents.domain.emission

/**
 * Random identity for a published [Emission]. Generated via the platform
 * `randomUUID()` helper. Dedup must never be overloaded onto this id — see
 * the `EmissionDedup` concept cell.
 */
typealias EmissionId = String

/**
 * Random identity for an [Affordance] attached to an [Emission]. Stable for
 * the lifetime of the emission so a corresponding `EmissionEvent.Resolved`
 * can causally link to the chosen affordance.
 */
typealias AffordanceId = String
