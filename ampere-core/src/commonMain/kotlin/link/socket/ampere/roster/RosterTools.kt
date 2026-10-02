package link.socket.ampere.roster

import link.socket.ampere.agents.execution.tools.ToolId

/**
 * Tool ids a roster declares that Ampere ships no tool for (AMPR-379).
 *
 * The Scout finds parts, guides, and lead times on the web. Ampere has no search or
 * fetch `Tool` — `web_search` exists only as a provider built-in — and the
 * dependency direction is Socket → Ampere, so the consumer binds these ids to its
 * own plug (Socket's Antenna). Declaring them here is the roster saying what the
 * seat needs; the role sparks already do the same for `web_search`.
 */
object RosterTools {

    /** Search the web for a part, a guide, a spec. Bound by the consumer. */
    const val WEB_SEARCH: ToolId = "web_search"

    /** Fetch one page or document the Scout already found. Bound by the consumer. */
    const val WEB_FETCH: ToolId = "web_fetch"
}
