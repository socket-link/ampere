package link.socket.ampere.agents.execution.verify

/**
 * The anchored lines of a failed gate's log: what an escalation may quote (AMPR-309,
 * rule 5).
 *
 * The tools the manifests run print machine-anchorable markers — Gradle's
 * `> Task :x:y FAILED` and `* What went wrong:`, xcodebuild's `** TEST EXECUTE
 * FAILED **` — and only those are taken. They decide nothing (the exit code already
 * has); they tell the person reading an escalation where to look.
 *
 * ### Why so little
 *
 * An escalation lands on a public mirror, and the Switchboard's rule is that an
 * escalation body never carries a command's output. The anchors are the narrowest
 * reconciliation of that rule with the ticket's "anchored log excerpt": a task path
 * and a status marker are structure, not output, and the one free-text line taken —
 * the sentence after `What went wrong:` — has every absolute path, home-relative path
 * and URL credential replaced, and is cut to [MAX_LINE] characters. The whole log
 * stays on the supervisor host, and a repairing agent gets its tail locally.
 */
object LogExcerpts {

    /** At most this many anchored lines are quoted. */
    const val MAX_LINES: Int = 12

    /** A quoted line is cut to this many characters. */
    const val MAX_LINE: Int = 200

    /** The anchored, sanitised lines of [log], in the order they appeared. */
    fun anchored(log: List<String>): List<String> {
        val excerpt = mutableListOf<String>()
        var i = 0
        while (i < log.size && excerpt.size < MAX_LINES) {
            val line = log[i].trimEnd()
            val trimmed = line.trim()
            GRADLE_TASK_FAILED.matchEntire(trimmed)?.let { match ->
                excerpt += "> Task ${match.groupValues[1]} FAILED"
            }
            XCODEBUILD_MARKER.matchEntire(trimmed)?.let { excerpt += it.value }
            if (WHAT_WENT_WRONG.matches(trimmed)) {
                excerpt += "* What went wrong:"
                val next = (i + 1 until log.size).firstOrNull { log[it].isNotBlank() }
                if (next != null && excerpt.size < MAX_LINES) {
                    excerpt += sanitize(log[next].trim())
                    i = next
                }
            }
            i++
        }
        return excerpt.distinct()
    }

    /** [line] with paths and URL credentials replaced, cut to [MAX_LINE]. */
    fun sanitize(line: String): String {
        val cleaned = line
            .replace(URL_CREDENTIALS, "$1://")
            .replace(FILE_URL, "<path>")
            .replace(ABSOLUTE_PATH, "$1<path>")
            .replace(HOME_PATH, "$1<path>")
        return if (cleaned.length <= MAX_LINE) cleaned else cleaned.take(MAX_LINE - 1) + "…"
    }

    private val GRADLE_TASK_FAILED = Regex("""> Task (:[A-Za-z0-9_.:-]+) FAILED""")

    private val WHAT_WENT_WRONG = Regex("""\*?\s*What went wrong:""")

    private val XCODEBUILD_MARKER = Regex("""\*\* [A-Z][A-Z -]*[A-Z] \*\*""")

    private val URL_CREDENTIALS = Regex("""([A-Za-z][A-Za-z0-9+.-]*)://[^/\s@]+@""")

    private val FILE_URL = Regex("""file:\S+""")

    private val ABSOLUTE_PATH = Regex("""(^|[\s'"(=:,\[])/[^\s'"),\]]+""")

    private val HOME_PATH = Regex("""(^|[\s'"(=:,\[])~/[^\s'"),\]]*""")
}
