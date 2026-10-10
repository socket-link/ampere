package link.socket.ampere.agents.domain.reasoning

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI

/**
 * AMPR-403: what the Perceive phase actually puts in front of the model.
 *
 * The phase used to render the state as `"State: $state"` and drop
 * [Perception.ideas] on the floor. Role states are only ever constructed as
 * `.blank`, so that rendering was a constant and every iteration asked the same
 * question about nothing. These tests pin the three things the prompt now
 * carries: the current task's description, every idea handed in, and the host's
 * own observations when it supplies a context builder.
 */
class PerceptionPromptTest {

    @Test
    fun `the rendered prompt carries the description of the current task`() = runTest {
        val capture = PromptCapture()
        val state = stateWithTask("Add a retry to the dispatch journal writer")

        reasoningWith(capture).evaluatePerception(state.toPerception())

        assertContains(assertNotNull(capture.prompt), "Add a retry to the dispatch journal writer")
    }

    @Test
    fun `the rendered prompt carries the name and description of every idea handed in`() = runTest {
        val capture = PromptCapture()
        val state = stateWithTask("Ship the perception fix")
        val ideas = arrayOf(
            Idea(name = "Assigned goal", description = "Put the goal in front of the model"),
            Idea(name = "Tool awareness", description = "write_code_file is available"),
        )

        reasoningWith(capture).evaluatePerception(state.toPerception(*ideas))

        val prompt = assertNotNull(capture.prompt)
        ideas.forEach { idea ->
            assertContains(prompt, idea.name)
            assertContains(prompt, idea.description)
        }
    }

    @Test
    fun `an idea carrying only a name still reaches the prompt`() = runTest {
        val capture = PromptCapture()
        val state = stateWithTask("Ship the perception fix")

        reasoningWith(capture).evaluatePerception(
            state.toPerception(Idea(name = "Recent failures cluster in the writer")),
        )

        assertContains(assertNotNull(capture.prompt), "Recent failures cluster in the writer")
    }

    @Test
    fun `a blank idea opens no section`() = runTest {
        val capture = PromptCapture()
        val state = stateWithTask("Ship the perception fix")

        // `AutonomousAgent.runtimeLoop` hands the previous iteration's idea in
        // unconditionally, so on the first pass the list is one `Idea.blank`.
        reasoningWith(capture).evaluatePerception(state.toPerception(Idea.blank))

        assertFalse(
            assertNotNull(capture.prompt).contains("Ideas In Play"),
            "a blank idea should not open an Ideas In Play section",
        )
    }

    @Test
    fun `the default context no longer renders the state by toString`() = runTest {
        val capture = PromptCapture()
        val state = stateWithTask("Ship the perception fix")

        reasoningWith(capture).evaluatePerception(state.toPerception())

        val prompt = assertNotNull(capture.prompt)
        assertFalse(
            prompt.contains("State: $state"),
            "the Perceive prompt still renders toString() of the state",
        )
        assertContains(prompt, "Current Task:")
    }

    @Test
    fun `a state with no task says so rather than rendering nothing`() = runTest {
        val capture = PromptCapture()

        reasoningWith(capture).evaluatePerception(AgentState().toPerception())

        assertContains(assertNotNull(capture.prompt), "(none assigned yet)")
    }

    @Test
    fun `a context builder supplied by the host replaces the default`() = runTest {
        val capture = PromptCapture()
        val state = stateWithTask("the task in memory")

        val reasoning = reasoningWith(capture) {
            perceptionContextBuilder = { "Inbox: 3 unread escalations" }
        }
        reasoning.evaluatePerception(state.toPerception())

        val prompt = assertNotNull(capture.prompt)
        assertContains(prompt, "Inbox: 3 unread escalations")
        assertFalse(
            prompt.contains("the task in memory"),
            "a host-supplied builder replaces the default rather than extending it",
        )
    }

    @Test
    fun `a context builder supplied by the host sees the state it is given`() = runTest {
        val capture = PromptCapture()
        val state = stateWithTask("Reconcile the ready queue")

        val reasoning = reasoningWith(capture) {
            perceptionContextBuilder = { observed ->
                "Host sees task: ${(observed.getCurrentMemory().task as Task.CodeChange).description}"
            }
        }
        reasoning.evaluatePerception(state.toPerception())

        assertContains(assertNotNull(capture.prompt), "Host sees task: Reconcile the ready queue")
    }

    // ========================================================================
    // Fixtures
    // ========================================================================

    private class PromptCapture {
        var prompt: String? = null
    }

    private fun stateWithTask(description: String): AgentState =
        AgentState().apply {
            setNewTask(
                Task.CodeChange(
                    id = "ampr-403-task",
                    status = TaskStatus.Pending,
                    description = description,
                ),
            )
        }

    /**
     * An [AgentReasoning] whose transport is a [capture]ing
     * [LlmProvider][link.socket.ampere.domain.llm.LlmProvider]. That seam
     * short-circuits before routing and is handed the combined system + user
     * prompt, which is exactly the rendered text under test.
     */
    private fun reasoningWith(
        capture: PromptCapture,
        configure: ReasoningSettingsBuilder.() -> Unit = {},
    ): AgentReasoning =
        AgentReasoning.create(
            config = AgentConfiguration(
                agentDefinition = WriteCodeAgent,
                aiConfiguration = AIConfiguration_Default(AIProvider_OpenAI, AIModel_OpenAI.GPT_4_1),
                llmProvider = { prompt ->
                    capture.prompt = prompt
                    INSIGHTS_JSON
                },
            ),
            executorId = "ampr-403-perception",
        ) {
            agentRole = "Test Agent"
            configure()
        }

    private companion object {
        val INSIGHTS_JSON = """
            [
              {
                "observation": "the task is stated",
                "implication": "planning can proceed",
                "confidence": "high"
              }
            ]
        """.trimIndent()
    }
}
