package link.socket.ampere.agents.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import link.socket.ampere.agents.events.tasks.Task

class CoreAgentTypesTest {

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
        val idea1 = Idea(name = "Test", description = "Desc")
        val idea2 = Idea(name = "Test", description = "Desc")
        val idea3 = Idea(name = "Different", description = "Desc")

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
        val plan = Plan(
            estimatedComplexity = 5,
            tasks = listOf(Task.blank),
        )
        assertEquals(5, plan.estimatedComplexity)
        assertEquals(1, plan.tasks.size)
    }

    @Test
    fun `Plan can have multiple tasks`() {
        val plan = Plan(
            estimatedComplexity = 8,
            tasks = listOf(Task.blank, Task.blank, Task.blank),
        )
        assertEquals(8, plan.estimatedComplexity)
        assertEquals(3, plan.tasks.size)
    }

    @Test
    fun `Plan equality works correctly`() {
        val plan1 = Plan(estimatedComplexity = 3, tasks = emptyList())
        val plan2 = Plan(estimatedComplexity = 3, tasks = emptyList())
        val plan3 = Plan(estimatedComplexity = 5, tasks = emptyList())

        assertEquals(plan1, plan2)
        assertNotEquals(plan1, plan3)
    }

    // ==================== OUTCOME TESTS ====================

    @Test
    fun `Outcome blank returns Blank instance`() {
        val blank = Outcome.blank
        assertIs<Outcome.Blank>(blank)
    }

    @Test
    fun `Outcome Success Full can be created`() {
        val task = Task.blank
        val outcome = Outcome.Success.Full(task, "Result value")

        assertIs<Outcome.Success.Full>(outcome)
        assertEquals("Result value", outcome.value)
        assertEquals(task, outcome.task)
    }

    @Test
    fun `Outcome Success Partial can be created with unfinished tasks`() {
        val task = Task.blank
        val unfinished = listOf(Task.blank)
        val outcome = Outcome.Success.Partial(task, unfinished)

        assertIs<Outcome.Success.Partial>(outcome)
        assertEquals(unfinished, outcome.unfinishedTasks)
    }

    @Test
    fun `Outcome Success Partial can be created without unfinished tasks`() {
        val task = Task.blank
        val outcome = Outcome.Success.Partial(task)

        assertIs<Outcome.Success.Partial>(outcome)
        assertEquals(null, outcome.unfinishedTasks)
    }

    @Test
    fun `Outcome Failure can be created with error message`() {
        val task = Task.blank
        val outcome = Outcome.Failure(task, "Something went wrong")

        assertIs<Outcome.Failure>(outcome)
        assertEquals("Something went wrong", outcome.errorMessage)
        assertEquals(task, outcome.task)
    }

    @Test
    fun `Outcome types are distinguishable`() {
        val task = Task.blank
        val success = Outcome.Success.Full(task, "value")
        val partial = Outcome.Success.Partial(task)
        val failure = Outcome.Failure(task, "error")
        val blank = Outcome.Blank

        assertIs<Outcome.Success>(success)
        assertIs<Outcome.Success>(partial)
        assertIs<Outcome.Failure>(failure)
        assertIs<Outcome.Blank>(blank)
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
        val idea = Idea(name = "Current Idea")
        val plan = Plan(estimatedComplexity = 5, tasks = emptyList())
        val state = AgentState(
            currentIdea = idea,
            currentPlan = plan,
        )
        val perception = Perception(
            ideas = listOf(idea),
            currentState = state,
            timestamp = Clock.System.now(),
        )

        val retrievedState = perception.currentState as AgentState
        assertEquals(idea, retrievedState.currentIdea)
        assertEquals(plan, retrievedState.currentPlan)
    }

    // ==================== SIGNAL TESTS ====================

    @Test
    fun `Signal can be created with value`() {
        val signal = Signal(value = "Test signal message")
        assertEquals("Test signal message", signal.value)
    }

    @Test
    fun `Signal equality works correctly`() {
        val signal1 = Signal(value = "Message")
        val signal2 = Signal(value = "Message")
        val signal3 = Signal(value = "Different")

        assertEquals(signal1, signal2)
        assertNotEquals(signal1, signal3)
    }

    @Test
    fun `Signal can have empty value`() {
        val signal = Signal(value = "")
        assertEquals("", signal.value)
    }

    // ==================== AGENT STATE TESTS ====================

    @Test
    fun `AgentState default values are correct`() {
        val state = AgentState()

        assertEquals(Idea.blank, state.currentIdea)
        assertEquals(Plan.blank, state.currentPlan)
        assertTrue(state.ideaHistory.isEmpty())
        assertTrue(state.planHistory.isEmpty())
        assertTrue(state.taskHistory.isEmpty())
        assertTrue(state.outcomeHistory.isEmpty())
        assertTrue(state.perceptionHistory.isEmpty())
    }

    @Test
    fun `AgentState can be created with custom values`() {
        val idea = Idea(name = "Custom Idea")
        val plan = Plan(estimatedComplexity = 3, tasks = emptyList())
        val ideaHistory = listOf(Idea(name = "Past Idea"))

        val state = AgentState(
            currentIdea = idea,
            currentPlan = plan,
            ideaHistory = ideaHistory,
        )

        assertEquals(idea, state.currentIdea)
        assertEquals(plan, state.currentPlan)
        assertEquals(1, state.ideaHistory.size)
        assertEquals("Past Idea", state.ideaHistory[0].name)
    }

    @Test
    fun `AgentState copy works correctly`() {
        val state = AgentState()
        val newIdea = Idea(name = "New Idea")

        val updatedState = state.copy(currentIdea = newIdea)

        assertEquals(newIdea, updatedState.currentIdea)
        assertEquals(Plan.blank, updatedState.currentPlan)
        assertTrue(updatedState.ideaHistory.isEmpty())
    }
}
