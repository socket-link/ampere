package link.socket.kore.agents.events.tickets

import link.socket.ampere.agents.events.tickets.EscalationKeyword
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EscalationKeywordTest {

    // ==================== Enum Properties Tests ====================

    @Test
    fun `meeting-only keywords have correct properties`() {
        val meetingOnlyKeywords = listOf(
            EscalationKeyword.DECISION,
            EscalationKeyword.DISCUSS,
            EscalationKeyword.MEETING,
            EscalationKeyword.REVIEW,
            EscalationKeyword.CLARIFICATION,
            EscalationKeyword.ARCHITECTURE,
            EscalationKeyword.DESIGN,
            EscalationKeyword.SCOPE,
            EscalationKeyword.PRIORITY,
            EscalationKeyword.RESOURCE,
            EscalationKeyword.BUDGET,
            EscalationKeyword.TIMELINE,
        )

        meetingOnlyKeywords.forEach { keyword ->
            assertTrue(keyword.requiresMeeting, "${keyword.name} should require meeting")
            assertFalse(keyword.requiresHuman, "${keyword.name} should not require human")
        }
    }

    @Test
    fun `human-only keywords have correct properties`() {
        val humanOnlyKeywords = listOf(
            EscalationKeyword.PERMISSION,
            EscalationKeyword.AUTHORIZE,
            EscalationKeyword.SIGN_OFF,
            EscalationKeyword.SIGNOFF,
            EscalationKeyword.MANAGER,
            EscalationKeyword.STAKEHOLDER,
            EscalationKeyword.CUSTOMER,
            EscalationKeyword.USER,
            EscalationKeyword.EXTERNAL,
        )

        humanOnlyKeywords.forEach { keyword ->
            assertFalse(keyword.requiresMeeting, "${keyword.name} should not require meeting")
            assertTrue(keyword.requiresHuman, "${keyword.name} should require human")
        }
    }

    @Test
    fun `keywords requiring both meeting and human have correct properties`() {
        val bothKeywords = listOf(
            EscalationKeyword.HUMAN,
            EscalationKeyword.APPROVAL,
        )

        bothKeywords.forEach { keyword ->
            assertTrue(keyword.requiresMeeting, "${keyword.name} should require meeting")
            assertTrue(keyword.requiresHuman, "${keyword.name} should require human")
        }
    }

    // ==================== Companion Object Method Tests ====================

    @Test
    fun `meetingKeywords returns all keywords that require meetings`() {
        val meetingKeywords = EscalationKeyword.meetingKeywords()

        // Should include meeting-only and both
        assertTrue(meetingKeywords.contains(EscalationKeyword.DECISION))
        assertTrue(meetingKeywords.contains(EscalationKeyword.DISCUSS))
        assertTrue(meetingKeywords.contains(EscalationKeyword.HUMAN))
        assertTrue(meetingKeywords.contains(EscalationKeyword.APPROVAL))

        // Should not include human-only
        assertFalse(meetingKeywords.contains(EscalationKeyword.PERMISSION))
        assertFalse(meetingKeywords.contains(EscalationKeyword.MANAGER))
    }

    @Test
    fun `humanKeywords returns all keywords that require human involvement`() {
        val humanKeywords = EscalationKeyword.humanKeywords()

        // Should include human-only and both
        assertTrue(humanKeywords.contains(EscalationKeyword.PERMISSION))
        assertTrue(humanKeywords.contains(EscalationKeyword.MANAGER))
        assertTrue(humanKeywords.contains(EscalationKeyword.HUMAN))
        assertTrue(humanKeywords.contains(EscalationKeyword.APPROVAL))

        // Should not include meeting-only
        assertFalse(humanKeywords.contains(EscalationKeyword.DECISION))
        assertFalse(humanKeywords.contains(EscalationKeyword.DISCUSS))
    }

    // ==================== reasonNeedsMeeting Tests ====================

    @Test
    fun `reasonNeedsMeeting returns true for meeting keywords`() {
        val meetingReasons = listOf(
            "Need to discuss the approach",
            "Waiting for decision from team",
            "Architecture review required",
            "Meeting scheduled for clarification",
            "Budget approval needed",
            "Timeline is unclear",
        )

        meetingReasons.forEach { reason ->
            assertTrue(
                EscalationKeyword.reasonNeedsMeeting(reason),
                "Should detect meeting need in: $reason",
            )
        }
    }

    @Test
    fun `reasonNeedsMeeting returns true for human and approval keywords`() {
        val humanInMeetingReasons = listOf(
            "Needs human review",
            "Waiting for approval",
        )

        humanInMeetingReasons.forEach { reason ->
            assertTrue(
                EscalationKeyword.reasonNeedsMeeting(reason),
                "Should detect meeting need in: $reason",
            )
        }
    }

    @Test
    fun `reasonNeedsMeeting returns false for human-only keywords`() {
        val humanOnlyReasons = listOf(
            "Manager sign-off required",
            "Permission from stakeholder",
            "Authorize the deployment",
        )

        humanOnlyReasons.forEach { reason ->
            assertFalse(
                EscalationKeyword.reasonNeedsMeeting(reason),
                "Should not detect meeting need in: $reason",
            )
        }
    }

    @Test
    fun `reasonNeedsMeeting returns false for unrelated reasons`() {
        val unrelatedReasons = listOf(
            "Compilation error",
            "Test failure",
            "Network timeout",
            "Missing dependencies",
        )

        unrelatedReasons.forEach { reason ->
            assertFalse(
                EscalationKeyword.reasonNeedsMeeting(reason),
                "Should not detect meeting need in: $reason",
            )
        }
    }

    @Test
    fun `reasonNeedsMeeting is case insensitive`() {
        assertTrue(EscalationKeyword.reasonNeedsMeeting("DECISION needed"))
        assertTrue(EscalationKeyword.reasonNeedsMeeting("Decision Needed"))
        assertTrue(EscalationKeyword.reasonNeedsMeeting("decision needed"))
    }

    // ==================== reasonNeedsHuman Tests ====================

    @Test
    fun `reasonNeedsHuman returns true for human keywords`() {
        val humanReasons = listOf(
            "Need human input",
            "Waiting for approval",
            "Permission required",
            "Manager sign-off needed",
            "Stakeholder feedback pending",
            "Customer clarification needed",
            "External dependency",
        )

        humanReasons.forEach { reason ->
            assertTrue(
                EscalationKeyword.reasonNeedsHuman(reason),
                "Should detect human need in: $reason",
            )
        }
    }

    @Test
    fun `reasonNeedsHuman returns false for meeting-only keywords`() {
        val meetingOnlyReasons = listOf(
            "Need to discuss",
            "Decision required",
            "Architecture question",
            "Design review",
        )

        meetingOnlyReasons.forEach { reason ->
            assertFalse(
                EscalationKeyword.reasonNeedsHuman(reason),
                "Should not detect human need in: $reason",
            )
        }
    }

    @Test
    fun `reasonNeedsHuman returns false for unrelated reasons`() {
        val unrelatedReasons = listOf(
            "Compilation error",
            "Test failure",
            "Network timeout",
            "Missing dependencies",
        )

        unrelatedReasons.forEach { reason ->
            assertFalse(
                EscalationKeyword.reasonNeedsHuman(reason),
                "Should not detect human need in: $reason",
            )
        }
    }

    @Test
    fun `reasonNeedsHuman is case insensitive`() {
        assertTrue(EscalationKeyword.reasonNeedsHuman("HUMAN input needed"))
        assertTrue(EscalationKeyword.reasonNeedsHuman("Human Input Needed"))
        assertTrue(EscalationKeyword.reasonNeedsHuman("human input needed"))
    }

    // ==================== findKeywordsInReason Tests ====================

    @Test
    fun `findKeywordsInReason returns all matching keywords`() {
        val reason = "Need human approval for architecture decision"
        val keywords = EscalationKeyword.findKeywordsInReason(reason)

        assertTrue(keywords.contains(EscalationKeyword.HUMAN))
        assertTrue(keywords.contains(EscalationKeyword.APPROVAL))
        assertTrue(keywords.contains(EscalationKeyword.ARCHITECTURE))
        assertTrue(keywords.contains(EscalationKeyword.DECISION))
        assertEquals(4, keywords.size)
    }

    @Test
    fun `findKeywordsInReason returns empty list for no matches`() {
        val reason = "Compilation error in build"
        val keywords = EscalationKeyword.findKeywordsInReason(reason)

        assertTrue(keywords.isEmpty())
    }

    @Test
    fun `findKeywordsInReason is case insensitive`() {
        val reason = "DECISION and APPROVAL needed"
        val keywords = EscalationKeyword.findKeywordsInReason(reason)

        assertTrue(keywords.contains(EscalationKeyword.DECISION))
        assertTrue(keywords.contains(EscalationKeyword.APPROVAL))
    }

    // ==================== Edge Case Tests ====================

    @Test
    fun `empty reason returns false for both checks`() {
        assertFalse(EscalationKeyword.reasonNeedsMeeting(""))
        assertFalse(EscalationKeyword.reasonNeedsHuman(""))
    }

    @Test
    fun `reason with only whitespace returns false for both checks`() {
        assertFalse(EscalationKeyword.reasonNeedsMeeting("   "))
        assertFalse(EscalationKeyword.reasonNeedsHuman("   "))
    }

    @Test
    fun `keyword as substring is detected`() {
        // "user" is part of "users"
        assertTrue(EscalationKeyword.reasonNeedsHuman("waiting for users"))
        // "review" is part of "reviewing"
        assertTrue(EscalationKeyword.reasonNeedsMeeting("currently reviewing"))
    }

    @Test
    fun `all enum entries have non-empty keywords`() {
        EscalationKeyword.entries.forEach { keyword ->
            assertTrue(
                keyword.keyword.isNotBlank(),
                "${keyword.name} should have non-empty keyword",
            )
        }
    }

    @Test
    fun `meetingKeywords and humanKeywords cover all entries`() {
        val meetingKeywords = EscalationKeyword.meetingKeywords().toSet()
        val humanKeywords = EscalationKeyword.humanKeywords().toSet()

        EscalationKeyword.entries.forEach { keyword ->
            val inMeeting = keyword in meetingKeywords
            val inHuman = keyword in humanKeywords
            assertTrue(
                inMeeting || inHuman,
                "${keyword.name} should be in at least one category",
            )
        }
    }
}
