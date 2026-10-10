package link.socket.ampere.roster

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.task.ExecutionAssignment
import link.socket.ampere.agents.execution.tools.ToolId
import link.socket.ampere.probe.ProbeId

/**
 * One seat on a roster: what it is called, what it is told, what it may use, whose
 * output it reviews (AMPR-379), and how its calls should be run (AMPR-413).
 *
 * @property id The role's identity; also its sender id in a Room.
 * @property title The word the user sees.
 * @property instructions The versioned prompt this role runs under.
 * @property tools Tool ids the role may dispatch. Ids a consumer binds (a search
 *   plug, a calendar plug) are declared here even when Ampere ships no tool by that
 *   id, the way the role sparks already declare `web_search`; the roster states
 *   what the seat needs, the consumer wires it. Declared bare — the seat-namespaced
 *   form a plan step names is derived, see [seatTools].
 * @property reviews Roles whose card-bearing posts this role reviews before they
 *   reach the Room. The graph these edges form must be acyclic — see
 *   [reviewsAreAcyclic].
 * @property execution Which model, at what effort, this seat's calls should run
 *   (AMPR-413). Null — the default — declares no preference and whatever fills the
 *   seat keeps its own. It reaches routing as tags on a
 *   [RoutingContext][link.socket.ampere.agents.domain.routing.RoutingContext]
 *   rather than as a routing rule of its own: see [withSeat].
 */
@Serializable
data class RoleConfig(
    val id: RoleId,
    val title: String,
    val instructions: PromptRef,
    val tools: Set<ToolId> = emptySet(),
    val reviews: Set<RoleId> = emptySet(),
    val execution: ExecutionAssignment? = null,
) {

    /**
     * This seat's [tools], each namespaced to it.
     *
     * The form a plan step names, so that sharing a tool with another seat stays
     * unambiguous about who runs it. See [SeatTool].
     */
    fun seatTools(): Set<SeatTool> = tools.mapTo(mutableSetOf()) { SeatTool(id, it) }

    companion object {

        /**
         * A [RoleConfig] built from plain ids, for a caller that cannot write a [RoleId].
         *
         * [RoleId] is an inline value class, which the Objective-C export erases to
         * `Any?` and whose companion it does not export at all, so Swift can neither
         * construct one nor name the type. A roster the consumer cannot author on the
         * platform it renders on is not authorable, so every parameter here is a type
         * Swift can build. Kotlin callers should use the constructor.
         */
        fun of(
            id: String,
            title: String,
            instructions: PromptRef,
            tools: Set<ToolId> = emptySet(),
            reviews: Set<String> = emptySet(),
            execution: ExecutionAssignment? = null,
        ): RoleConfig = RoleConfig(
            id = RoleId(id),
            title = title,
            instructions = instructions,
            tools = tools,
            reviews = reviews.mapTo(mutableSetOf()) { RoleId(it) },
            execution = execution,
        )
    }
}

/**
 * The set of roles that plans and tends one project, and the seats a binding over a
 * Room names.
 *
 * A roster is a value, not a service: it says who the seats are and how they relate.
 * Which agent fills a seat, and what it does when it gets there, is the consumer's.
 *
 * Only [all] and [host] are a roster's to answer. A reviewing seat is optional
 * (AMPR-409): a roster with one seat, or whose consumer runs no Probes, has nobody to
 * review and nothing to review, and should not have to invent an Inspector to satisfy
 * this interface. [RosterConfig] is the authorable implementation.
 */
interface Roster {

    /** Every role, in a stable order. */
    fun all(): List<RoleConfig>

    /** The role that hosts the Room: opens its threads, posts status, and DMs the human. */
    val host: RoleId

    /**
     * The role that runs Probes and posts `Verdict` cards; it reviews the roles it
     * names. Null — the default — when the roster has no reviewing seat, and a Room
     * reads that as "no verdict threads": there is no seat to post the card or close
     * the thread, so there is no verdict conversation to hold.
     */
    val verifier: RoleId? get() = null

    /**
     * The role that can resolve a verdict reached by [probeId] — the seat a verdict
     * thread is assigned to when it opens. Null — the default — when no seat can
     * settle that Probe's verdicts, and a thread nobody owns does not open either.
     */
    fun resolverFor(probeId: ProbeId): RoleId? = null

    fun byId(id: RoleId): RoleConfig? = all().firstOrNull { it.id == id }

    /** The role that reviews [author]'s cards, or null when they reach the Room directly. */
    fun reviewerOf(author: RoleId): RoleId? = all().firstOrNull { author in it.reviews }?.id

    /**
     * The seat that runs [qualifiedToolId] — the `<seat>/<tool>` form a plan step
     * names (AMPR-413) — or null when no seat on this roster does.
     *
     * Null covers three different mistakes deliberately: a bare id that names no seat,
     * a seat this roster does not hold, and a tool the named seat never declared. All
     * three mean the step cannot be dispatched as written, which is the only answer the
     * caller can act on.
     */
    fun seatRunning(qualifiedToolId: ToolId): RoleConfig? =
        SeatTool.parse(qualifiedToolId)?.let { seatTool ->
            byId(seatTool.seat)?.takeIf { seatTool.tool in it.tools }
        }
}

/**
 * True when following [RoleConfig.reviews] edges never leads back to the role you
 * started from. A cycle would mean two roles each waiting on the other's review,
 * and no card ever reaching the Room.
 *
 * Edges to roles not on the list are ignored: a dangling reviewer is a different
 * defect (nobody fills the seat), not a cycle.
 */
fun List<RoleConfig>.reviewsAreAcyclic(): Boolean {
    val byId = associateBy { it.id }
    val visiting = mutableSetOf<RoleId>()
    val done = mutableSetOf<RoleId>()

    fun cyclesFrom(role: RoleId): Boolean {
        if (role in done) return false
        if (!visiting.add(role)) return true
        val cyclic = byId[role]?.reviews.orEmpty().any { reviewed -> reviewed in byId && cyclesFrom(reviewed) }
        visiting -= role
        done += role
        return cyclic
    }

    return none { cyclesFrom(it.id) }
}
