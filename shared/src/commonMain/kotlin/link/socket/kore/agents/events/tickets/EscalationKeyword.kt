package link.socket.kore.agents.events.tickets

/**
 * Enum representing keywords that indicate different escalation requirements for blockers.
 * Each keyword specifies whether it requires a meeting and/or human involvement.
 *
 * @property keyword The string keyword to match against in blocker reasons.
 * @property requiresMeeting Whether this keyword indicates a meeting is needed.
 * @property requiresHuman Whether this keyword indicates human involvement is needed.
 */
enum class EscalationKeyword(
    val keyword: String,
    val requiresMeeting: Boolean,
    val requiresHuman: Boolean,
) {
    // Meeting-only indicators
    DECISION("decision", requiresMeeting = true, requiresHuman = false),
    DISCUSS("discuss", requiresMeeting = true, requiresHuman = false),
    MEETING("meeting", requiresMeeting = true, requiresHuman = false),
    REVIEW("review", requiresMeeting = true, requiresHuman = false),
    CLARIFICATION("clarification", requiresMeeting = true, requiresHuman = false),
    ARCHITECTURE("architecture", requiresMeeting = true, requiresHuman = false),
    DESIGN("design", requiresMeeting = true, requiresHuman = false),
    SCOPE("scope", requiresMeeting = true, requiresHuman = false),
    PRIORITY("priority", requiresMeeting = true, requiresHuman = false),
    RESOURCE("resource", requiresMeeting = true, requiresHuman = false),
    BUDGET("budget", requiresMeeting = true, requiresHuman = false),
    TIMELINE("timeline", requiresMeeting = true, requiresHuman = false),

    // Both meeting and human involvement indicators
    HUMAN("human", requiresMeeting = true, requiresHuman = true),
    APPROVAL("approval", requiresMeeting = true, requiresHuman = true),

    // Human-only indicators
    PERMISSION("permission", requiresMeeting = false, requiresHuman = true),
    AUTHORIZE("authorize", requiresMeeting = false, requiresHuman = true),
    SIGN_OFF("sign-off", requiresMeeting = false, requiresHuman = true),
    SIGNOFF("signoff", requiresMeeting = false, requiresHuman = true),
    MANAGER("manager", requiresMeeting = false, requiresHuman = true),
    STAKEHOLDER("stakeholder", requiresMeeting = false, requiresHuman = true),
    CUSTOMER("customer", requiresMeeting = false, requiresHuman = true),
    USER("user", requiresMeeting = false, requiresHuman = true),
    EXTERNAL("external", requiresMeeting = false, requiresHuman = true),
    ;

    companion object {
        /**
         * Returns all keywords that indicate a meeting is required.
         */
        fun meetingKeywords(): List<EscalationKeyword> =
            entries.filter { it.requiresMeeting }

        /**
         * Returns all keywords that indicate human involvement is required.
         */
        fun humanKeywords(): List<EscalationKeyword> =
            entries.filter { it.requiresHuman }

        /**
         * Checks if the given reason contains any keyword that requires a meeting.
         *
         * @param reason The blocker reason to check.
         * @return True if the reason contains any meeting-requiring keyword.
         */
        fun reasonNeedsMeeting(reason: String): Boolean {
            val lowerReason = reason.lowercase()
            return meetingKeywords().any { lowerReason.contains(it.keyword) }
        }

        /**
         * Checks if the given reason contains any keyword that requires human involvement.
         *
         * @param reason The blocker reason to check.
         * @return True if the reason contains any human-requiring keyword.
         */
        fun reasonNeedsHuman(reason: String): Boolean {
            val lowerReason = reason.lowercase()
            return humanKeywords().any { lowerReason.contains(it.keyword) }
        }

        /**
         * Finds all escalation keywords present in the given reason.
         *
         * @param reason The blocker reason to analyze.
         * @return List of matching escalation keywords.
         */
        fun findKeywordsInReason(reason: String): List<EscalationKeyword> {
            val lowerReason = reason.lowercase()
            return entries.filter { lowerReason.contains(it.keyword) }
        }
    }
}
