package link.socket.ampere.agents.execution.dispatch

/**
 * Recognises a recorded human verdict on a ticket — the evidence the strict
 * research-blocker rule needs (AMPR-308, ratified 2026-08-23).
 *
 * ### Why the body, not the author
 *
 * Every write to this work source shares one API identity (verified, AMPR-289), so
 * a verdict a person relays and a findings comment an agent posts carry the same
 * author. What distinguishes a verdict is how it opens: every recorded verdict on the
 * recon tickets begins `**Human verdict — recorded <date> …**` (AMPR-288, AMPR-291).
 * So a comment is a verdict when its first non-blank line, with markdown emphasis,
 * heading and quote markers stripped, starts with [HEADER].
 *
 * ### Why the Switchboard cannot author one
 *
 * Everything the Switchboard writes opens with a supervisory header — `claim:`,
 * `release:` or `esc:` — on its first line, and [DispatchWorkSource] offers no way to
 * post anything else. A body passed to an escalation sits *under* the `esc:` header,
 * so it can never be a comment's first line. `HumanVerdictsTest` pins that for every
 * shape the Switchboard can write.
 */
object HumanVerdicts {

    /** How a recorded verdict opens. */
    const val HEADER: String = "Human verdict"

    /** Whether [body] is a recorded human verdict. */
    fun isVerdict(body: String): Boolean {
        val first = body.lineSequence().firstOrNull { it.isNotBlank() } ?: return false
        val opening = first.trim().trimStart(*MARKUP).trimStart()
        return opening.startsWith(HEADER, ignoreCase = true)
    }

    /** Whether any of [comments] is a recorded human verdict. */
    fun anyVerdict(comments: List<TicketComment>): Boolean = comments.any { isVerdict(it.body) }

    private val MARKUP = charArrayOf('#', '*', '_', '>', ' ', '\t')
}
