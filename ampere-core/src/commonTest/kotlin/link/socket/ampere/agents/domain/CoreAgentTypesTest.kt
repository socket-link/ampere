package link.socket.ampere.agents.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.reasoning.Perception
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.task.Task

class CoreAgentTypesTest {

    private val stubIdeaId = "idea1"

    private val stubIdea = Idea(
        id = stubIdeaId,
        name = "Test",
        description = "Desc",
    )

    private val stubPlan = Plan.ForIdea(
        idea = stubIdea,
        estimatedComplexity = 5,
        tasks = emptyList(),
    )

    // ==================== IDEA TESTS ====================

    @Test
    fun `Idea blank creates empty idea`() {
        val blank = Idea.blank
        assertEquals("", blank.name)
        assertEquals("", blank.description)
    }

    @Test
    fun `Idea can be created with name only`() {
        val idea = Idea(name = "Test Idea")
        assertEquals("Test Idea", idea.name)
        assertEquals("", idea.description)
    }

    @Test
    fun `Idea can be created with name and description`() {
        val idea = Idea(
            name = "Test Idea",
            description = "A detailed description",
        )
        assertEquals("Test Idea", idea.name)
        assertEquals("A detailed description", idea.description)
    }

    @Test
    fun `Idea equality works correctly`() {
        val idea1 = Idea(
            id = stubIdeaId,
            name = "Test",
            description = "Desc",
        )
        val idea2 = Idea(
            id = stubIdeaId,
            name = "Test",
            description = "Desc",
        )
        val idea3 = Idea(
            name = "Different",
            description = "Desc",
        )

        assertEquals(idea1, idea2)
        assertNotEquals(idea1, idea3)
    }

    // ==================== PLAN TESTS ====================

    @Test
    fun `Plan blank creates empty plan`() {
        val blank = Plan.blank
        assertEquals(0, blank.estimatedComplexity)
        assertTrue(blank.tasks.isEmpty())
    }

    @Test
    fun `Plan can be created with tasks`() {
        val plan = Plan.ForIdea(
            idea = stubIdea,
            estimatedComplexity = 5,
            tasks = listOf(Task.blank),
        )
        assertEquals(5, plan.estimatedComplexity)
        assertEquals(1, plan.tasks.size)
    }

    @Test
    fun `Plan can have multiple tasks`() {
        val plan = Plan.ForIdea(
            idea = stubIdea,
            estimatedComplexity = 8,
            tasks = listOf(Task.blank, Task.blank, Task.blank),
        )
        assertEquals(8, plan.estimatedComplexity)
        assertEquals(3, plan.tasks.size)
    }

    @Test
    fun `Plan equality works correctly`() {
        val plan1 = Plan.ForIdea(
            idea = stubIdea,
            estimatedComplexity = 3,
            tasks = emptyList(),
        )
        val plan2 = Plan.ForIdea(
            idea = stubIdea,
            estimatedComplexity = 3,
            tasks = emptyList(),
        )
        val plan3 = Plan.ForIdea(
            idea = Idea(name = "Different"),
            estimatedComplexity = 5,
            tasks = emptyList(),
        )

        assertEquals(plan1, plan2)
        assertNotEquals(plan1, plan3)
    }

    // ==================== PERCEPTION TESTS ====================

    @Test
    fun `Perception can be created with empty ideas`() {
        val state = AgentState()
        val timestamp = Clock.System.now()
        val perception = Perception(
            ideas = emptyList(),
            currentState = state,
            timestamp = timestamp,
        )

        assertTrue(perception.ideas.isEmpty())
        assertEquals(state, perception.currentState)
        assertEquals(timestamp, perception.timestamp)
    }

    @Test
    fun `Perception can be created with multiple ideas`() {
        val ideas = listOf(
            Idea(name = "Idea 1"),
            Idea(name = "Idea 2"),
        )
        val state = AgentState()
        val timestamp = Clock.System.now()
        val perception = Perception(
            ideas = ideas,
            currentState = state,
            timestamp = timestamp,
        )

        assertEquals(2, perception.ideas.size)
        assertEquals("Idea 1", perception.ideas[0].name)
        assertEquals("Idea 2", perception.ideas[1].name)
    }

    @Test
    fun `Perception preserves state correctly`() {
        val state = AgentState()
        state.setNewIdea(stubIdea)
        state.setNewPlan(stubPlan)

        val perception = Perception(
            ideas = listOf(stubIdea),
            currentState = state,
            timestamp = Clock.System.now(),
        )

        val retrievedState = perception.currentState
        assertEquals(stubIdea, retrievedState.getCurrentMemory().idea)
        assertEquals(stubPlan, retrievedState.getCurrentMemory().plan)
    }

    // ==================== AGENT STATE TESTS ====================

    @Test
    fun `AgentState default values are correct`() {
        val state = AgentState()

        val currentMemory = state.getCurrentMemory()
        assertEquals(Idea.blank, currentMemory.idea)
        assertEquals(Outcome.blank, currentMemory.outcome)
        assertEquals(Perception.blank, currentMemory.perception)
        assertEquals(Plan.blank, currentMemory.plan)
        assertEquals(Task.blank, currentMemory.task)

        val pastMemory = state.getPastMemory()
        assertTrue(pastMemory.ideas.isEmpty())
        assertTrue(pastMemory.outcomes.isEmpty())
        assertTrue(pastMemory.perceptions.isEmpty())
        assertTrue(pastMemory.plans.isEmpty())
        assertTrue(pastMemory.tasks.isEmpty())

        assertTrue(pastMemory.knowledgeFromIdeas.isEmpty())
        assertTrue(pastMemory.knowledgeFromOutcomes.isEmpty())
        assertTrue(pastMemory.knowledgeFromPerceptions.isEmpty())
        assertTrue(pastMemory.knowledgeFromPlans.isEmpty())
        assertTrue(pastMemory.knowledgeFromTasks.isEmpty())
    }

    @Test
    fun `AgentState can remember old values`() {
        val state = AgentState()

        state.setNewIdea(stubIdea)
        state.setNewPlan(stubPlan)

        val currentMemory1 = state.getCurrentMemory()
        assertEquals(stubIdea, currentMemory1.idea)
        assertEquals(stubPlan, currentMemory1.plan)

        val pastMemory1 = state.getPastMemory()
        assertTrue(pastMemory1.ideas.isEmpty())
        assertTrue(pastMemory1.plans.isEmpty())

        val stubIdea1 = stubIdea.copy(name = "New name")
        val stubPlan1 = Plan.ForIdea(
            idea = stubIdea1,
            estimatedComplexity = 10,
            tasks = emptyList(),
        )

        state.setNewIdea(stubIdea1)
        state.setNewPlan(stubPlan1)

        val currentMemory2 = state.getCurrentMemory()
        assertEquals(stubIdea1, currentMemory2.idea)
        assertEquals(stubPlan1, currentMemory2.plan)

        val pastMemory2 = state.getPastMemory()
        assertEquals(listOf(stubIdea.id), pastMemory2.ideas)
        assertEquals(listOf(stubPlan.id), pastMemory2.plans)
    }
}
