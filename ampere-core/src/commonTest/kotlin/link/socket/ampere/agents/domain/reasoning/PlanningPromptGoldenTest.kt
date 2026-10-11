package link.socket.ampere.agents.domain.reasoning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.knowledge.KnowledgeEntry
import link.socket.ampere.agents.domain.knowledge.KnowledgeType
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.AssignedTo
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.provider.AIProvider_Google

/**
 * The first cycle's planning prompt, byte for byte (AMPR-424, task 1).
 *
 * AMPR-424 teaches `PlanGenerator` about later cycles: a prompt block of the work
 * earlier cycles did, an empty `steps` array as a valid answer, and cycle-prefixed step
 * ids. None of that may move a *first* cycle's prompt by a byte, and this test is the
 * proof: the golden string below was captured from `main` before any of those edits,
 * and is not edited again in the PR that makes them.
 *
 * One fixed task, one idea, one knowledge entry and two seats — enough to render every
 * block the prompt has (the seat roster, the knowledge block, the seat key in the
 * response schema) so that a change to any of them shows up here.
 */
class PlanningPromptGoldenTest {

    @Test
    fun `a first cycle renders exactly the prompt it rendered before later cycles existed`() = runTest {
        var captured: String? = null
        val generator = PlanGenerator(
            AgentLLMService(
                agentConfiguration = AgentConfiguration(
                    agentDefinition = WriteCodeAgent,
                    aiConfiguration = AIConfiguration_Default(AIProvider_Google, AIModel_Gemini.Flash_2_5),
                    llmProvider = { sent ->
                        captured = sent
                        PLAN_JSON
                    },
                ),
            ),
        )

        generator.generate(
            task = GOAL,
            ideas = listOf(IDEA),
            agentRole = "Engineer",
            availableTools = setOf(SEARCH_TOOL),
            seats = listOf(PLANNER_SEAT, SCOUT_SEAT),
            relevantKnowledge = listOf(RECALLED),
        )

        // A custom provider receives `"System: <system message>\n\nUser: <prompt>"`,
        // and the system half carries the agent's own prompt ahead of the planning one,
        // so the delimiter is the last such marker.
        val prompt = captured.orEmpty().substringAfterLast("\n\nUser: ")
        assertEquals(GOLDEN_PROMPT, prompt)
    }

    private companion object {
        val EPOCH: Instant = Instant.fromEpochSeconds(0)

        val GOAL = Task.Step(
            id = "goal-1",
            status = TaskStatus.Pending,
            description = "Add a retry to the uploader",
        )

        val IDEA = Idea(
            name = "Retry is missing",
            id = "idea-1",
            description = "The uploader gives up on the first 503",
        )

        val RECALLED = KnowledgeWithScore(
            entry = KnowledgeEntry(
                id = "knowledge-1",
                knowledgeType = KnowledgeType.FROM_OUTCOME,
                approach = "Capped the backoff at three attempts",
                learnings = "Three attempts was enough for a flaky mirror",
                timestamp = EPOCH,
            ),
            knowledge = Knowledge.FromOutcome(
                outcomeId = "outcome-1",
                approach = "Capped the backoff at three attempts",
                learnings = "Three attempts was enough for a flaky mirror",
                timestamp = EPOCH,
            ),
            relevanceScore = 0.75,
        )

        val SEARCH_TOOL = FunctionTool<ExecutionContext.NoChanges>(
            id = "scout/search",
            name = "Search",
            description = "searches the codebase",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { error("the planner never dispatches a tool") },
        )

        val PLANNER_SEAT = PlanSeat(
            seat = "planner",
            assignedTo = AssignedTo.Agent("seat-planner"),
        )

        val SCOUT_SEAT = PlanSeat(
            seat = "scout",
            assignedTo = AssignedTo.Agent("seat-scout"),
            toolIds = setOf("scout/search"),
        )

        const val PLAN_JSON: String = """
            {
              "steps": [
                { "description": "Search for the uploader", "toolToUse": "scout/search", "seat": "scout", "requiresPreviousStep": false }
              ],
              "estimatedComplexity": 2,
              "requiresHumanInput": false
            }
        """

        /** Captured from `main` at c4e02791, before AMPR-424. Do not edit. */
        val GOLDEN_PROMPT: String = """
            You are the planning module of an autonomous Engineer agent.
            Your task is to create a concrete, executable plan to accomplish the given task.

            Task: Add a retry to the uploader

            Insights from Perception:
            Retry is missing:
            The uploader gives up on the first 503

            Past Knowledge:
            Relevant past experiences (1 entries):
            - Approach: Capped the backoff at three attempts
              Learnings: Three attempts was enough for a flaky mirror...
              Relevance: 75%



            Seats and the tools each one owns:
            - planner:
                (no tools — reasoning steps only)
            - scout:
                - scout/search: searches the codebase

            A step may nominate only a tool its own seat owns.

            Create a step-by-step plan where each step is a concrete task that can be executed.
            Each step should:
            1. Have a clear, actionable description
            2. Specify which tool to use (if applicable)
            3. Be sequentially ordered with clear dependencies
            4. Include validation/verification steps where appropriate
            5. Name the seat that runs it in `seat`, using one of the seat names above

            For simple tasks, create a 1-2 step plan.
            For complex tasks, break down into logical phases (3-5 steps typically).
            Avoid excessive granularity - focus on meaningful phases of work.

            Set requiresHumanInput to true only when the plan cannot be carried out without a decision, an approval, or information that only a person can supply. A plan you can execute with the tools and context above sets it to false.

            Format your response as a JSON object:
            {
              "steps": [
                {
                  "description": "what this step accomplishes",
                  "toolToUse": "tool ID or null if no specific tool",
                  "seat": "planner | scout",
                  "requiresPreviousStep": true/false
                }
              ],
              "estimatedComplexity": 1-10,
              "requiresHumanInput": true/false
            }

            Respond ONLY with the JSON object, no other text.
        """.trimIndent() + "\n"
    }
}
