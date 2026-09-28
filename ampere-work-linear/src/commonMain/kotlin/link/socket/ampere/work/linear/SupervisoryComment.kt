package link.socket.ampere.work.linear

/**
 * A comment the supervisor writes for the supervisor to read: the claim that
 * arbitrates a race, and the escalation that hands a ticket to a human.
 *
 * ## Why a comment carries this at all
 *
 * The work source's write surface has no compare-and-swap and no precondition
 * parameter of any kind — verified in the AMPR-289 probe, where two writes to
 * the same issue both succeeded, last-write-wins, with no conflict reported.
 * Comments, by contrast, are append-only with a server-assigned total order.
 * That asymmetry is the whole design: the *state transition* cannot be raced
 * safely, so the *comment* decides who owns it, and the transition follows.
 *
 * ## The format
 *
 * One header line, three colon-separated fields:
 *
 * ```
 * claim:AMPR-305:supervisor-7f3a
 * esc:AMPR-305:supervisor-7f3a
 *
 * The verification gate needs a decision a machine must not make.
 * ```
 *
 * [Escalation] may carry a body after one blank line; [Claim] never does. Both
 * round-trip exactly: `parse(render(comment)) == comment` for every
 * constructible value, including a body with leading, trailing or repeated
 * newlines, because [render] separates the header from the body with the first
 * `"\n\n"` in the text and [parse] splits on that same first occurrence.
 *
 * The prefixes are constants rather than literals at the call sites because
 * they are a *wire format between two supervisor processes* — a renamed prefix
 * makes every in-flight claim invisible to the process that has to arbitrate
 * it, and reads as "nobody claimed this" rather than as a parse error.
 *
 * ## The public-mirror constraint
 *
 * Every comment written here syncs to a public GitHub issue (verified,
 * AMPR-289). Neither form carries prose the framework composes, and a body
 * handed to [Escalation] is screened by
 * [WorkSourceIssueSink.forbiddenTerms] before it leaves.
 */
sealed interface SupervisoryComment {

    /** The literal prefix this form's header starts with, separator included. */
    val prefix: String

    /** The issue identifier the comment is about, e.g. `AMPR-305`. */
    val issue: String

    /** The supervisor process that wrote it. */
    val instanceId: SupervisorInstanceId

    /** The comment body, exactly as it should be written to the work source. */
    fun render(): String

    /**
     * "I am taking this ticket." Posted first, before the transition, so the
     * server's timestamp on it is the claim's position in the total order.
     */
    data class Claim(
        override val issue: String,
        override val instanceId: SupervisorInstanceId,
    ) : SupervisoryComment {

        init {
            validateIssue(issue)
        }

        override val prefix: String get() = CLAIM_PREFIX

        override fun render(): String = "$CLAIM_PREFIX$issue$FIELD_SEPARATOR$instanceId"
    }

    /**
     * "This ticket needs a human." Paired with
     * [WorkSourceLabels.GATE_ESCALATED] so the ready-queue stops offering it —
     * the label is what a query can filter on, the comment is what carries the
     * reason.
     *
     * @property body Free-form markdown. Markdown round-trips byte-identically
     *   through this work source (verified: headings, tables, task lists, code
     *   fences and unicode all survived), so the body needs no escaping.
     */
    data class Escalation(
        override val issue: String,
        override val instanceId: SupervisorInstanceId,
        val body: String = "",
    ) : SupervisoryComment {

        init {
            validateIssue(issue)
        }

        override val prefix: String get() = ESCALATION_PREFIX

        override fun render(): String {
            val header = "$ESCALATION_PREFIX$issue$FIELD_SEPARATOR$instanceId"
            return if (body.isEmpty()) header else header + BODY_SEPARATOR + body
        }
    }

    companion object {

        const val FIELD_SEPARATOR: String = ":"

        const val CLAIM_PREFIX: String = "claim$FIELD_SEPARATOR"

        const val ESCALATION_PREFIX: String = "esc$FIELD_SEPARATOR"

        /** One blank line between the header and an [Escalation]'s body. */
        const val BODY_SEPARATOR: String = "\n\n"

        /**
         * Reads a comment body as a supervisory comment, or null when it is
         * anything else.
         *
         * **Never throws and never guesses.** Every comment on a ticket goes
         * through here — human prose, bot notices, the GitHub sync's own
         * mirror notice — so "not one of ours" has to be an ordinary answer.
         * A header with the right prefix but the wrong field count, a blank
         * field, or a claim with a body attached are all null: a malformed
         * claim that parsed anyway would enter arbitration with a field it
         * guessed at.
         */
        fun parse(body: String): SupervisoryComment? {
            val header = body.substringBefore(BODY_SEPARATOR)
            if ('\n' in header) return null

            val rest = if (header.length == body.length) {
                ""
            } else {
                body.substring(header.length + BODY_SEPARATOR.length)
            }

            val fields = header.split(FIELD_SEPARATOR)
            if (fields.size != FIELD_COUNT) return null
            val (kind, issue, instance) = fields
            if (issue.isBlank() || instance.isBlank()) return null

            return when ("$kind$FIELD_SEPARATOR") {
                CLAIM_PREFIX -> if (rest.isEmpty()) Claim(issue, SupervisorInstanceId(instance)) else null
                ESCALATION_PREFIX -> Escalation(issue, SupervisorInstanceId(instance), rest)
                else -> null
            }
        }

        private const val FIELD_COUNT = 3

        private fun validateIssue(issue: String) {
            require(issue.isNotBlank()) { "A supervisory comment's issue must not be blank" }
            require(FIELD_SEPARATOR !in issue) {
                "A supervisory comment's issue must not contain '$FIELD_SEPARATOR': $issue"
            }
            require('\n' !in issue) {
                "A supervisory comment's issue must not contain a newline: $issue"
            }
        }
    }
}
