package link.socket.ampere.probe.safety

import kotlinx.serialization.Serializable

/**
 * The kinds of physical hazard a plan of work can contain (AMPR-380).
 *
 * **Closed at the enum level, and generic to physical work.** Ampere owns this
 * vocabulary because every consumer that plans physical work needs the same
 * seven words — a vent build, a sensor build and a garden job each reach for a
 * subset, and a software Blueprint (CLI Rung 4) reaches for none. Nothing here
 * names a duct, a resin or a tiller; that vocabulary lives in the consumer's
 * prompts and in the eval fixtures, the same split
 * `link.socket.ampere.roster.RosterPrompts` already makes.
 *
 * Closed rather than open on purpose: an open vocabulary would let a consumer
 * invent a category that no Probe, renderer or disclaimer knows how to treat,
 * and a hazard nobody can render is a hazard nobody sees. Widening the set is a
 * versioned change, like admitting a canon noun — the same decision shape as
 * the manifest *interface* kinds this probe reads, which are open today and
 * normalized into these categories by [KeywordHazardClassifier].
 */
@Serializable
enum class HazardCategory {
    /** Mains or line voltage, wiring, circuits, breakers. */
    ELECTRICAL,

    /** Solvents, resins, adhesives, paints, fuels — anything whose vapour is the risk. */
    FUMES_OR_CHEMICALS,

    /** Load paths and lifts: joists, studs, beams, anything heavy enough to need two people. */
    STRUCTURAL_OR_LOAD,

    /** Blades and powered tools: saws, drills, grinders, tillers, mowers. */
    CUTTING_OR_POWER_TOOLS,

    /** Ladders, roofs, scaffolds — work where a fall is the hazard. */
    WORKING_AT_HEIGHT,

    /** Soldering, welding, torches, heat guns, anything that can start a fire. */
    HEAT_OR_FIRE,

    /** Gas lines, propane, compressed air, pressure vessels. */
    PRESSURE_OR_GAS,
}

/**
 * What to do about a hazard before the hazardous work starts (AMPR-380).
 *
 * One hint becomes one mitigation Task ([MitigationPlan]), so the set is small
 * and every member is reachable from at least one classifier rule — a hint no
 * rule can produce is vocabulary that never renders.
 *
 * [imperative] is the *fallback* title of the mitigation Task it creates:
 * generic to physical work and free of any consumer's domain words. A renderer
 * that wants "consider an electrician for the mains connection" reads
 * [HazardCategory] off the Task's labels and writes its own copy; Socket owns
 * that wording (SCKT-747), Ampere owns the checked invariant underneath it.
 */
@Serializable
enum class MitigationHint(val imperative: String) {
    /** The manifest carries something whose vapour matters; prove the air moves first. */
    CONFIRM_VENTILATION("Confirm the ventilation path"),

    /** The work is regulated where the person lives; Ampere never guesses a jurisdiction. */
    CHECK_LOCAL_CODE("Check local code and consider a licensed trade"),

    /** Make the circuit dead before touching it. */
    DISCONNECT_POWER_FIRST("Disconnect power at the breaker and confirm it is dead"),

    /** Eyes, hands, lungs, ears — whichever the hazard takes. */
    USE_PPE("Put on the protective equipment this step needs"),

    /** A load one person should not take alone. */
    TWO_PERSON_LIFT("Arrange a second person for the lift"),

    /** Falls are the hazard, and the footing is the fix. */
    SECURE_LADDER("Secure the ladder and clear its footing"),

    /** The product's own sheet is the authority on the product. */
    FOLLOW_MANUFACTURER_SDS("Read the manufacturer's safety data sheet"),
}
