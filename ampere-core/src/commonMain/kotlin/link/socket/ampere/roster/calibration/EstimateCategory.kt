package link.socket.ampere.roster.calibration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The kind of time a task consumes, which is the grain calibration is kept at
 * (AMPR-379).
 *
 * A person who underestimates physical work by half may estimate waiting exactly,
 * because waiting is a shipping quote and physical work is optimism. One multiplier
 * per person would average the two into a number that is wrong for both.
 *
 * Wire names are snake_case, matching the `lifecycle.*` enums; the category is
 * stored per sample on the consumer side and must decode across releases.
 */
@Serializable
enum class EstimateCategory {

    /** Cutting, drilling, sanding, painting — hands on the work. */
    @SerialName("physical_work")
    PHYSICAL_WORK,

    /** Fitting parts together; usually faster than it looks and slower than hoped. */
    @SerialName("assembly")
    ASSEMBLY,

    /** Curing, drying, shipping — time nobody can shorten. */
    @SerialName("waiting")
    WAITING,

    /** Finding the part, reading the guide, checking the spec. */
    @SerialName("research")
    RESEARCH,

    /** Ordering, permits, returns. */
    @SerialName("admin")
    ADMIN,

    /** Going to get the thing. */
    @SerialName("travel")
    TRAVEL,
}
