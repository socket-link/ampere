package link.socket.ampere.domain.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentDescribableTest {

    // ==================== Test Implementations ====================

    /**
     * Simple implementation for testing basic functionality.
     */
    private data class SimpleDescribable(
        override val description: String
    ) : AgentDescribable

    /**
     * Implementation with custom typeName for testing.
     */
    private data class CustomNameDescribable(
        override val typeName: String,
        override val description: String
    ) : AgentDescribable

    /**
     * Implementation with properties for testing describeProperties.
     */
    private data class DescribableWithProperties(
        override val description: String,
        val property1: String,
        val property2: Int
    ) : AgentDescribable {
        override fun describeProperties(): Map<String, String> = mapOf(
            "property1" to property1,
            "property2" to property2.toString()
        )
    }

    /**
     * Implementation with nested AgentDescribable for testing.
     */
    private data class NestedDescribable(
        override val description: String,
        val nested: AgentDescribable
    ) : AgentDescribable {
        override fun describeProperties(): Map<String, String> = mapOf(
            "nested" to nested.typeName
        )
    }

    // ==================== typeName Tests ====================

    @Test
    fun `typeName defaults to class simple name`() {
        val describable = SimpleDescribable("A simple type")
        assertEquals("SimpleDescribable", describable.typeName)
    }

    @Test
    fun `typeName can be overridden`() {
        val describable = CustomNameDescribable("CustomName", "A custom named type")
        assertEquals("CustomName", describable.typeName)
    }

    // ==================== description Tests ====================

    @Test
    fun `description returns provided value`() {
        val describable = SimpleDescribable("Test description")
        assertEquals("Test description", describable.description)
    }

    // ==================== describeProperties Tests ====================

    @Test
    fun `describeProperties returns empty map by default`() {
        val describable = SimpleDescribable("Simple")
        assertTrue(describable.describeProperties().isEmpty())
    }

    @Test
    fun `describeProperties returns custom properties`() {
        val describable = DescribableWithProperties(
            description = "With properties",
            property1 = "value1",
            property2 = 42
        )
        val properties = describable.describeProperties()

        assertEquals(2, properties.size)
        assertEquals("value1", properties["property1"])
        assertEquals("42", properties["property2"])
    }

    @Test
    fun `describeProperties shows nested typeName for AgentDescribable properties`() {
        val nested = SimpleDescribable("Nested description")
        val parent = NestedDescribable(
            description = "Parent description",
            nested = nested
        )

        val properties = parent.describeProperties()
        assertEquals("SimpleDescribable", properties["nested"])
    }

    // ==================== toPromptString Tests ====================

    @Test
    fun `toPromptString formats type without properties`() {
        val describable = SimpleDescribable("A simple description")
        val result = describable.toPromptString()

        assertEquals("- SimpleDescribable: A simple description", result)
    }

    @Test
    fun `toPromptString formats type with properties`() {
        val describable = DescribableWithProperties(
            description = "With properties",
            property1 = "value1",
            property2 = 42
        )
        val result = describable.toPromptString()

        assertTrue(result.startsWith("- DescribableWithProperties: With properties ["))
        assertTrue(result.contains("property1: value1"))
        assertTrue(result.contains("property2: 42"))
        assertTrue(result.endsWith("]"))
    }

    @Test
    fun `toPromptString uses custom typeName`() {
        val describable = CustomNameDescribable("MyType", "Custom type")
        val result = describable.toPromptString()

        assertEquals("- MyType: Custom type", result)
    }

    // ==================== AgentTypeDescriber.formatGroupedByHierarchy Tests ====================

    @Test
    fun `formatGroupedByHierarchy groups types by parent class`() {
        // Using Escalation types which implement AgentDescribable
        val types = listOf(
            link.socket.ampere.agents.events.tickets.Escalation.Discussion.CodeReview,
            link.socket.ampere.agents.events.tickets.Escalation.Discussion.Design,
            link.socket.ampere.agents.events.tickets.Escalation.Decision.Technical,
        )

        val result = AgentTypeDescriber.formatGroupedByHierarchy(types)

        assertTrue(result.contains("## Discussion"))
        assertTrue(result.contains("## Decision"))
        assertTrue(result.contains("CodeReview"))
        assertTrue(result.contains("Design"))
        assertTrue(result.contains("Technical"))
    }

    @Test
    fun `formatGroupedByHierarchy uses custom title`() {
        val types = listOf(SimpleDescribable("Test"))

        val result = AgentTypeDescriber.formatGroupedByHierarchy(
            types = types,
            title = "Custom Title:"
        )

        assertTrue(result.startsWith("Custom Title:"))
    }

    @Test
    fun `formatGroupedByHierarchy includes descriptions`() {
        val types = listOf(
            link.socket.ampere.agents.events.tickets.Escalation.Discussion.CodeReview,
        )

        val result = AgentTypeDescriber.formatGroupedByHierarchy(types)

        assertTrue(result.contains("Code needs review"))
    }

    // ==================== AgentTypeDescriber.formatGrouped Tests ====================

    @Test
    fun `formatGrouped uses custom grouper function`() {
        val types = listOf(
            SimpleDescribable("Type A"),
            SimpleDescribable("Type B"),
        )

        val result = AgentTypeDescriber.formatGrouped(
            types = types,
            grouper = { if (it.description.contains("A")) "Group A" else "Group B" }
        )

        assertTrue(result.contains("## Group A"))
        assertTrue(result.contains("## Group B"))
    }

    @Test
    fun `formatGrouped uses custom title`() {
        val types = listOf(SimpleDescribable("Test"))

        val result = AgentTypeDescriber.formatGrouped(
            types = types,
            grouper = { "Group" },
            title = "My Custom Title:"
        )

        assertTrue(result.startsWith("My Custom Title:"))
    }

    // ==================== AgentTypeDescriber.describeInDetail Tests ====================

    @Test
    fun `describeInDetail shows type name and description`() {
        val describable = SimpleDescribable("Detailed description")

        val result = AgentTypeDescriber.describeInDetail(describable)

        assertTrue(result.contains("Type: SimpleDescribable"))
        assertTrue(result.contains("Description: Detailed description"))
    }

    @Test
    fun `describeInDetail shows properties when present`() {
        val describable = DescribableWithProperties(
            description = "With props",
            property1 = "val1",
            property2 = 100
        )

        val result = AgentTypeDescriber.describeInDetail(describable)

        assertTrue(result.contains("Properties:"))
        assertTrue(result.contains("property1: val1"))
        assertTrue(result.contains("property2: 100"))
    }

    @Test
    fun `describeInDetail omits properties section when empty`() {
        val describable = SimpleDescribable("No properties")

        val result = AgentTypeDescriber.describeInDetail(describable)

        assertTrue(!result.contains("Properties:"))
    }

    // ==================== Integration Tests with Escalation ====================

    @Test
    fun `Escalation types implement AgentDescribable correctly`() {
        val escalation = link.socket.ampere.agents.events.tickets.Escalation.Discussion.CodeReview

        assertEquals("CodeReview", escalation.typeName)
        assertTrue(escalation.description.isNotBlank())

        val properties = escalation.describeProperties()
        assertEquals("AgentMeeting", properties["escalationProcess"])
    }

    @Test
    fun `EscalationProcess types implement AgentDescribable correctly`() {
        val process = link.socket.ampere.agents.events.tickets.EscalationProcess.AgentMeeting

        assertEquals("AgentMeeting", process.typeName)
        assertTrue(process.description.isNotBlank())

        val properties = process.describeProperties()
        assertEquals("true", properties["requiresMeeting"])
        assertEquals("false", properties["requiresHuman"])
    }

    @Test
    fun `allTypesForPrompt generates valid output`() {
        val result = link.socket.ampere.agents.events.tickets.Escalation.allTypesForPrompt()

        // Should have title
        assertTrue(result.contains("Available escalation types:"))

        // Should have all category groups
        assertTrue(result.contains("## Discussion"))
        assertTrue(result.contains("## Decision"))
        assertTrue(result.contains("## Budget"))
        assertTrue(result.contains("## Priorities"))
        assertTrue(result.contains("## Scope"))
        assertTrue(result.contains("## External"))

        // Should have type names
        assertTrue(result.contains("CodeReview"))
        assertTrue(result.contains("Authorization"))
        assertTrue(result.contains("CostApproval"))

        // Should have descriptions
        assertTrue(result.contains("Code needs review"))

        // Should have process info in brackets
        assertTrue(result.contains("[escalationProcess:"))
    }
}
