package link.socket.ampere.agents.events.tickets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EscalationTest {

    // ==================== EscalationProcess Tests ====================

    @Test
    fun `AgentMeeting requires meeting but not human`() {
        val process = EscalationProcess.AgentMeeting
        assertTrue(process.requiresMeeting)
        assertFalse(process.requiresHuman)
    }

    @Test
    fun `HumanApproval requires human but not meeting`() {
        val process = EscalationProcess.HumanApproval
        assertFalse(process.requiresMeeting)
        assertTrue(process.requiresHuman)
    }

    @Test
    fun `HumanMeeting requires both meeting and human`() {
        val process = EscalationProcess.HumanMeeting
        assertTrue(process.requiresMeeting)
        assertTrue(process.requiresHuman)
    }

    @Test
    fun `ExternalDependency requires neither meeting nor human`() {
        val process = EscalationProcess.ExternalDependency
        assertFalse(process.requiresMeeting)
        assertFalse(process.requiresHuman)
    }

    // ==================== Discussion Escalation Tests ====================

    @Test
    fun `Discussion CodeReview uses AgentMeeting process`() {
        val escalation = Escalation.Discussion.CodeReview
        assertEquals(EscalationProcess.AgentMeeting, escalation.process)
        assertTrue(escalation.description.isNotBlank())
    }

    @Test
    fun `Discussion Design uses AgentMeeting process`() {
        val escalation = Escalation.Discussion.Design
        assertEquals(EscalationProcess.AgentMeeting, escalation.process)
    }

    @Test
    fun `Discussion Architecture uses AgentMeeting process`() {
        val escalation = Escalation.Discussion.Architecture
        assertEquals(EscalationProcess.AgentMeeting, escalation.process)
    }

    @Test
    fun `Discussion Requirements uses HumanMeeting process`() {
        val escalation = Escalation.Discussion.Requirements
        assertEquals(EscalationProcess.HumanMeeting, escalation.process)
    }

    // ==================== Decision Escalation Tests ====================

    @Test
    fun `Decision Technical uses AgentMeeting process`() {
        val escalation = Escalation.Decision.Technical
        assertEquals(EscalationProcess.AgentMeeting, escalation.process)
    }

    @Test
    fun `Decision Product uses HumanMeeting process`() {
        val escalation = Escalation.Decision.Product
        assertEquals(EscalationProcess.HumanMeeting, escalation.process)
    }

    @Test
    fun `Decision Authorization uses HumanApproval process`() {
        val escalation = Escalation.Decision.Authorization
        assertEquals(EscalationProcess.HumanApproval, escalation.process)
    }

    // ==================== Budget Escalation Tests ====================

    @Test
    fun `Budget ResourceAllocation uses HumanMeeting process`() {
        val escalation = Escalation.Budget.ResourceAllocation
        assertEquals(EscalationProcess.HumanMeeting, escalation.process)
    }

    @Test
    fun `Budget CostApproval uses HumanApproval process`() {
        val escalation = Escalation.Budget.CostApproval
        assertEquals(EscalationProcess.HumanApproval, escalation.process)
    }

    @Test
    fun `Budget Timeline uses HumanMeeting process`() {
        val escalation = Escalation.Budget.Timeline
        assertEquals(EscalationProcess.HumanMeeting, escalation.process)
    }

    // ==================== Priorities Escalation Tests ====================

    @Test
    fun `Priorities Conflict uses HumanMeeting process`() {
        val escalation = Escalation.Priorities.Conflict
        assertEquals(EscalationProcess.HumanMeeting, escalation.process)
    }

    @Test
    fun `Priorities Reprioritization uses HumanApproval process`() {
        val escalation = Escalation.Priorities.Reprioritization
        assertEquals(EscalationProcess.HumanApproval, escalation.process)
    }

    @Test
    fun `Priorities Dependency uses AgentMeeting process`() {
        val escalation = Escalation.Priorities.Dependency
        assertEquals(EscalationProcess.AgentMeeting, escalation.process)
    }

    // ==================== Scope Escalation Tests ====================

    @Test
    fun `Scope Expansion uses HumanMeeting process`() {
        val escalation = Escalation.Scope.Expansion
        assertEquals(EscalationProcess.HumanMeeting, escalation.process)
    }

    @Test
    fun `Scope Reduction uses HumanMeeting process`() {
        val escalation = Escalation.Scope.Reduction
        assertEquals(EscalationProcess.HumanMeeting, escalation.process)
    }

    @Test
    fun `Scope Clarification uses HumanMeeting process`() {
        val escalation = Escalation.Scope.Clarification
        assertEquals(EscalationProcess.HumanMeeting, escalation.process)
    }

    // ==================== External Escalation Tests ====================

    @Test
    fun `External Vendor uses ExternalDependency process`() {
        val escalation = Escalation.External.Vendor
        assertEquals(EscalationProcess.ExternalDependency, escalation.process)
    }

    @Test
    fun `External Customer uses HumanApproval process`() {
        val escalation = Escalation.External.Customer
        assertEquals(EscalationProcess.HumanApproval, escalation.process)
    }

    // ==================== Companion Object Tests ====================

    @Test
    fun `allTypes returns all escalation types`() {
        val allTypes = Escalation.allTypes()
        assertEquals(18, allTypes.size)

        // Verify each category is represented
        assertTrue(allTypes.any { it is Escalation.Discussion })
        assertTrue(allTypes.any { it is Escalation.Decision })
        assertTrue(allTypes.any { it is Escalation.Budget })
        assertTrue(allTypes.any { it is Escalation.Priorities })
        assertTrue(allTypes.any { it is Escalation.Scope })
        assertTrue(allTypes.any { it is Escalation.External })
    }

    @Test
    fun `allTypesForPrompt returns formatted string`() {
        val prompt = Escalation.allTypesForPrompt()

        assertTrue(prompt.contains("Available escalation types:"))
        assertTrue(prompt.contains("## Discussion"))
        assertTrue(prompt.contains("## Decision"))
        assertTrue(prompt.contains("## Budget"))
        assertTrue(prompt.contains("## Priorities"))
        assertTrue(prompt.contains("## Scope"))
        assertTrue(prompt.contains("## External"))
        assertTrue(prompt.contains("CodeReview:"))
        assertTrue(prompt.contains("Authorization:"))
    }

    // ==================== fromIdentifier Tests ====================

    @Test
    fun `fromIdentifier parses Discussion types`() {
        assertIs<Escalation.Discussion.CodeReview>(Escalation.fromIdentifier("Discussion.CodeReview"))
        assertIs<Escalation.Discussion.Design>(Escalation.fromIdentifier("Design"))
        assertIs<Escalation.Discussion.Architecture>(Escalation.fromIdentifier("architecture"))
        assertIs<Escalation.Discussion.Requirements>(Escalation.fromIdentifier("REQUIREMENTS"))
    }

    @Test
    fun `fromIdentifier parses Decision types`() {
        assertIs<Escalation.Decision.Technical>(Escalation.fromIdentifier("Technical"))
        assertIs<Escalation.Decision.Product>(Escalation.fromIdentifier("product"))
        assertIs<Escalation.Decision.Authorization>(Escalation.fromIdentifier("AUTHORIZATION"))
    }

    @Test
    fun `fromIdentifier parses Budget types`() {
        assertIs<Escalation.Budget.ResourceAllocation>(Escalation.fromIdentifier("ResourceAllocation"))
        assertIs<Escalation.Budget.CostApproval>(Escalation.fromIdentifier("costapproval"))
        assertIs<Escalation.Budget.Timeline>(Escalation.fromIdentifier("Timeline"))
    }

    @Test
    fun `fromIdentifier parses Priorities types`() {
        assertIs<Escalation.Priorities.Conflict>(Escalation.fromIdentifier("Conflict"))
        assertIs<Escalation.Priorities.Reprioritization>(Escalation.fromIdentifier("reprioritization"))
        assertIs<Escalation.Priorities.Dependency>(Escalation.fromIdentifier("Dependency"))
    }

    @Test
    fun `fromIdentifier parses Scope types`() {
        assertIs<Escalation.Scope.Expansion>(Escalation.fromIdentifier("Expansion"))
        assertIs<Escalation.Scope.Reduction>(Escalation.fromIdentifier("reduction"))
        assertIs<Escalation.Scope.Clarification>(Escalation.fromIdentifier("Clarification"))
    }

    @Test
    fun `fromIdentifier parses External types`() {
        assertIs<Escalation.External.Vendor>(Escalation.fromIdentifier("Vendor"))
        assertIs<Escalation.External.Customer>(Escalation.fromIdentifier("customer"))
    }

    @Test
    fun `fromIdentifier returns null for unknown identifiers`() {
        assertNull(Escalation.fromIdentifier("Unknown"))
        assertNull(Escalation.fromIdentifier(""))
        assertNull(Escalation.fromIdentifier("SomeRandomString"))
    }

    @Test
    fun `fromIdentifier is case insensitive`() {
        assertEquals(
            Escalation.fromIdentifier("codereview"),
            Escalation.fromIdentifier("CODEREVIEW"),
        )
        assertEquals(
            Escalation.fromIdentifier("Timeline"),
            Escalation.fromIdentifier("timeline"),
        )
    }

    @Test
    fun `fromIdentifier handles whitespace`() {
        assertNotNull(Escalation.fromIdentifier("  CodeReview  "))
        assertNotNull(Escalation.fromIdentifier("\tDesign\n"))
    }

    // ==================== All Escalations Have Descriptions ====================

    @Test
    fun `all escalation types have non-empty descriptions`() {
        Escalation.allTypes().forEach { escalation ->
            assertTrue(
                escalation.description.isNotBlank(),
                "Escalation ${escalation::class.simpleName} should have a description",
            )
        }
    }

    // ==================== Process Categories Tests ====================

    @Test
    fun `agent-only escalations use AgentMeeting process`() {
        val agentOnlyEscalations = listOf(
            Escalation.Discussion.CodeReview,
            Escalation.Discussion.Design,
            Escalation.Discussion.Architecture,
            Escalation.Decision.Technical,
            Escalation.Priorities.Dependency,
        )

        agentOnlyEscalations.forEach { escalation ->
            assertEquals(
                EscalationProcess.AgentMeeting,
                escalation.process,
                "${escalation::class.simpleName} should use AgentMeeting",
            )
            assertTrue(escalation.process.requiresMeeting)
            assertFalse(escalation.process.requiresHuman)
        }
    }

    @Test
    fun `human approval escalations use HumanApproval process`() {
        val humanApprovalEscalations = listOf(
            Escalation.Decision.Authorization,
            Escalation.Budget.CostApproval,
            Escalation.Priorities.Reprioritization,
            Escalation.External.Customer,
        )

        humanApprovalEscalations.forEach { escalation ->
            assertEquals(
                EscalationProcess.HumanApproval,
                escalation.process,
                "${escalation::class.simpleName} should use HumanApproval",
            )
            assertFalse(escalation.process.requiresMeeting)
            assertTrue(escalation.process.requiresHuman)
        }
    }

    @Test
    fun `human meeting escalations use HumanMeeting process`() {
        val humanMeetingEscalations = listOf(
            Escalation.Discussion.Requirements,
            Escalation.Decision.Product,
            Escalation.Budget.ResourceAllocation,
            Escalation.Budget.Timeline,
            Escalation.Priorities.Conflict,
            Escalation.Scope.Expansion,
            Escalation.Scope.Reduction,
            Escalation.Scope.Clarification,
        )

        humanMeetingEscalations.forEach { escalation ->
            assertEquals(
                EscalationProcess.HumanMeeting,
                escalation.process,
                "${escalation::class.simpleName} should use HumanMeeting",
            )
            assertTrue(escalation.process.requiresMeeting)
            assertTrue(escalation.process.requiresHuman)
        }
    }
}
