package link.socket.ampere.roster

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.execution.tools.ToolId

/**
 * A tool id scoped to the seat that runs it: `<seat>/<tool>` (AMPR-413).
 *
 * A roster's seats declare their tools bare, and two seats may need the same one — a
 * Scout and an Inspector both searching the web. A bare id in a plan step then says
 * what to run without saying who runs it, which is a step no dispatcher can place.
 * Namespacing by seat makes the pair the identity: `scout/web_search` and
 * `inspector/web_search` are two runnable things backed by one tool.
 *
 * This is a convention over [ToolId], which is a `String`, rather than a new id type:
 * the qualified form has to survive a prompt, a model's echo of it, and a JSON round
 * trip, all as text. [parse] is the exact inverse of [id], and the two are the only
 * place that knows the shape.
 *
 * @property seat The seat that runs [tool].
 * @property tool The bare tool id, as the seat declares it in [RoleConfig.tools].
 */
@Serializable
data class SeatTool(
    val seat: RoleId,
    val tool: ToolId,
) {

    /** The `<seat>/<tool>` id a plan step names, and what [parse] reads back. */
    val id: ToolId get() = "${seat.value}$SEPARATOR$tool"

    companion object {

        /** What separates the seat from the tool. */
        const val SEPARATOR: String = "/"

        /**
         * A [SeatTool] built from plain ids, for a caller that cannot write a [RoleId] —
         * see [RoleConfig.of] for why Swift is one.
         */
        fun of(seat: String, tool: ToolId): SeatTool = SeatTool(RoleId(seat), tool)

        /**
         * The seat and the tool in [qualifiedToolId], or null when it names no seat.
         *
         * Split at the *first* separator, so a tool id carrying one of its own
         * (`github/issues/create`) arrives whole and the seat is always the first
         * segment. A bare id is not an error — most tool ids in the repo are bare — it
         * simply names no seat, and null says exactly that. So does a half-written id
         * (`/web_search`, `scout/`): a seat or a tool, but not both.
         */
        fun parse(qualifiedToolId: ToolId): SeatTool? {
            val separator = qualifiedToolId.indexOf(SEPARATOR)
            if (separator <= 0 || separator == qualifiedToolId.lastIndex) return null
            return SeatTool(
                seat = RoleId(qualifiedToolId.substring(0, separator)),
                tool = qualifiedToolId.substring(separator + SEPARATOR.length),
            )
        }
    }
}
