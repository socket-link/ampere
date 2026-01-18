package link.socket.ampere.cli.watch.presentation

import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SparkPresentationTest {

    @Test
    fun `recordApplied updates history and cognitive state`() {
        val collector = SparkHistoryCollector()
        val agentId = "agent-1"
        val timestamp = Instant.parse("2024-01-01T00:00:00Z")

        collector.recordApplied(
            agentId = agentId,
            timestamp = timestamp,
            sparkName = "Project:ampere",
            sparkType = "ProjectSpark",
            stackDepth = 2,
            stackDescription = "[ANALYTICAL] → [Project:ampere]"
        )

        val history = collector.getHistory(agentId)
        assertEquals(1, history.size)
        val transition = history.first()
        assertEquals(SparkTransitionDirection.APPLIED, transition.direction)
        assertEquals("Project:ampere", transition.sparkName)
        assertEquals(2, transition.resultingDepth)

        val state = collector.getCognitiveState(agentId)
        assertEquals("ANALYTICAL", state?.affinityName)
        assertEquals(listOf("Project:ampere"), state?.sparkNames)
        assertEquals(2, state?.depth)
    }

    @Test
    fun `recordRemoved updates history and clears spark names`() {
        val collector = SparkHistoryCollector()
        val agentId = "agent-2"
        val timestamp = Instant.parse("2024-01-01T00:01:00Z")

        collector.recordRemoved(
            agentId = agentId,
            timestamp = timestamp,
            previousSparkName = "Role:Code",
            stackDepth = 0,
            stackDescription = "[OPERATIONAL]"
        )

        val history = collector.getHistory(agentId)
        assertEquals(1, history.size)
        assertEquals(SparkTransitionDirection.REMOVED, history.first().direction)

        val state = collector.getCognitiveState(agentId)
        assertEquals("OPERATIONAL", state?.affinityName)
        assertTrue(state?.sparkNames?.isEmpty() == true)
        assertEquals(0, state?.depth)
    }

    @Test
    fun `recordSnapshot stores effective constraints`() {
        val collector = SparkHistoryCollector()
        val agentId = "agent-3"
        val timestamp = Instant.parse("2024-01-01T00:02:00Z")

        collector.recordSnapshot(
            agentId = agentId,
            affinity = "INTEGRATIVE",
            sparkNames = listOf("Project:ampere", "Role:Planning"),
            stackDepth = 3,
            effectivePromptLength = 1200,
            availableToolCount = 7,
            stackDescription = "[INTEGRATIVE] → [Project:ampere] → [Role:Planning]"
        )

        val state = collector.getCognitiveState(agentId)
        assertEquals("INTEGRATIVE", state?.affinityName)
        assertEquals(3, state?.depth)
        assertEquals(1200, state?.effectivePromptLength)
        assertEquals(7, state?.availableToolCount)
    }

    @Test
    fun `clearAgent removes history and state`() {
        val collector = SparkHistoryCollector()
        val agentId = "agent-4"

        collector.recordApplied(
            agentId = agentId,
            timestamp = Instant.parse("2024-01-01T00:03:00Z"),
            sparkName = "Focus:QA",
            sparkType = "FocusSpark",
            stackDepth = 1,
            stackDescription = "[EXPLORATORY] → [Focus:QA]"
        )

        collector.clearAgent(agentId)

        assertTrue(collector.getHistory(agentId).isEmpty())
        assertNull(collector.getCognitiveState(agentId))
    }

    @Test
    fun `recordSnapshot parses stackDescription when sparkNames is empty`() {
        val collector = SparkHistoryCollector()
        val agentId = "agent-5"

        collector.recordSnapshot(
            agentId = agentId,
            affinity = "",
            sparkNames = emptyList(),
            stackDepth = 2,
            effectivePromptLength = 800,
            availableToolCount = 5,
            stackDescription = "[ANALYTICAL] → [Project:test]"
        )

        val state = collector.getCognitiveState(agentId)
        assertEquals("ANALYTICAL", state?.affinityName)
        assertEquals(listOf("Project:test"), state?.sparkNames)
    }

    @Test
    fun `recordSnapshot uses provided values when sparkNames is not empty`() {
        val collector = SparkHistoryCollector()
        val agentId = "agent-6"

        collector.recordSnapshot(
            agentId = agentId,
            affinity = "INTEGRATIVE",
            sparkNames = listOf("Role:Planning", "Task:Design"),
            stackDepth = 3,
            effectivePromptLength = 1000,
            availableToolCount = 8,
            stackDescription = "[ANALYTICAL] → [Project:other]"
        )

        val state = collector.getCognitiveState(agentId)
        assertEquals("INTEGRATIVE", state?.affinityName)
        assertEquals(listOf("Role:Planning", "Task:Design"), state?.sparkNames)
    }

    @Test
    fun `recordSnapshot handles blank stackDescription gracefully`() {
        val collector = SparkHistoryCollector()
        val agentId = "agent-7"

        collector.recordSnapshot(
            agentId = agentId,
            affinity = "OPERATIONAL",
            sparkNames = emptyList(),
            stackDepth = 1,
            effectivePromptLength = 500,
            availableToolCount = 3,
            stackDescription = ""
        )

        val state = collector.getCognitiveState(agentId)
        assertEquals("OPERATIONAL", state?.affinityName)
        assertTrue(state?.sparkNames?.isEmpty() == true)
    }

    @Test
    fun `recordApplied parses multiple sparks from description`() {
        val collector = SparkHistoryCollector()
        val agentId = "agent-8"
        val timestamp = Instant.parse("2024-01-01T00:04:00Z")

        collector.recordApplied(
            agentId = agentId,
            timestamp = timestamp,
            sparkName = "Task:Implement",
            sparkType = "TaskSpark",
            stackDepth = 4,
            stackDescription = "[ANALYTICAL] → [Project:ampere] → [Role:Code] → [Task:Implement]"
        )

        val state = collector.getCognitiveState(agentId)
        assertEquals("ANALYTICAL", state?.affinityName)
        assertEquals(listOf("Project:ampere", "Role:Code", "Task:Implement"), state?.sparkNames)
        assertEquals(4, state?.depth)
    }
}
