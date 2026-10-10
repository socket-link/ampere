@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package link.socket.ampere.agents.definition

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.domain.memory.AgentMemoryService
import link.socket.ampere.agents.domain.memory.MemoryContext
import link.socket.ampere.agents.domain.memory.MemoryTaskTypes
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.AgentReasoning
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.InMemoryEventApi
import link.socket.ampere.db.Database
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider
import link.socket.ampere.domain.llm.LlmProvider

/**
 * AMPR-388: the knowledge Recall retrieves reaches the planning prompt.
 *
 * `propel-loop.md`'s *Recall precedes Plan* invariant says a `Plan` is generated from
 * what Recall found. Two call sites used to drop it on the way —
 * [AutonomousAgent.determinePlanForTask] called `runLLMToPlan(task, ideas)`, and the step
 * re-plan planned with no knowledge at all — so every `KnowledgeRecalled` the loop recorded
 * described knowledge the planner never saw. These tests read the prompt that actually leaves
 * for the model, through the [LlmProvider] seam, and assert the recalled entry is in it.
 *
 * The re-plan lives in `SparkBasedAgent.runSubPlanForTask` since AMPR-396 took it out of
 * `runLLMToExecuteTask`, which now dispatches the step it was handed rather than planning
 * again (a step that nominates no tool is carried out by one EXECUTE call — AMPR-407 — which
 * is why the sub-plan tests below count two calls and read the first).
 *
 * The last two pin the other half of the contract: when Recall finds nothing, the prompt is
 * byte-for-byte the one `PlanGenerator` built before this change.
 */
class PlanPromptKnowledgeTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var door: InMemoryEventApi.Handle
    private lateinit var memoryService: AgentMemoryService

    /** Every prompt the planner handed to the (fake) model, in order. */
    private val prompts = CopyOnWriteArrayList<String>()

    private val agentId = "ampr388-planner"

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        door = InMemoryEventApi.open(agentId = agentId, scope = testScope)
        // No driver handed to the repository: `findSimilarKnowledge` then uses its LIKE
        // fallback rather than FTS5, which keeps recall deterministic for this fixture.
        memoryService = AgentMemoryService(
            agentId = agentId,
            knowledgeRepository = KnowledgeRepositoryImpl(Database(driver)),
            eventApi = door.api,
        )
    }

    @AfterTest
    fun tearDown() {
        door.close()
        driver.close()
    }

    // ==================== Drop site 1: determinePlanForTask ====================

    @Test
    fun `knowledge recalled for a task reaches the planning prompt`() = runBlocking<Unit> {
        storeOneEntryForCodeChange()
        val agent = agent(memoryService = memoryService)
        val codeChange = task()

        val recalled = memoryService.recallRelevantKnowledge(
            context = MemoryContext(
                taskType = MemoryTaskTypes.CODE_CHANGE,
                tags = emptySet(),
                description = codeChange.description,
            ),
            limit = 10,
        ).getOrThrow()
        assertEquals(1, recalled.size, "fixture should recall exactly the one stored entry")

        val plan = agent.determinePlanForTask(
            task = codeChange,
            ideas = arrayOf(IDEA),
            relevantKnowledge = recalled,
        )

        assertPlannedFromResponse(plan)
        val prompt = assertSinglePrompt()
        assertTrue(
            prompt.contains("Approach: $STORED_APPROACH"),
            "planning prompt should carry the recalled entry's approach; got:\n$prompt",
        )
        assertTrue(
            prompt.contains("Learnings: $STORED_LEARNINGS"),
            "planning prompt should carry the recalled entry's learnings; got:\n$prompt",
        )
    }

    // ==================== Drop site 2: the sub-plan cycle ====================

    @Test
    fun `the sub-plan cycle recalls its own knowledge into the prompt`() {
        runBlocking { storeOneEntryForCodeChange() }
        val agent = agent(memoryService = memoryService)

        // Nothing is handed in here: `runSubPlanForTask` takes a Task and nothing else, so
        // the knowledge in the prompt can only have come from its own Recall.
        val outcome = agent.runSubPlanForTask(task())

        assertTrue(
            outcome is Outcome.Success,
            "the tool-less step should succeed; got ${outcome::class.simpleName}",
        )
        val prompt = assertPlanningPromptOf(expectedCalls = 2)
        assertTrue(
            prompt.contains("Approach: $STORED_APPROACH"),
            "the sub-plan's prompt should carry the recalled entry's approach; got:\n$prompt",
        )
        assertTrue(
            prompt.contains("Learnings: $STORED_LEARNINGS"),
            "the sub-plan's prompt should carry the recalled entry's learnings; got:\n$prompt",
        )
    }

    @Test
    fun `an empty recall leaves the sub-plan prompt exactly as it was`() {
        // No memory service: Recall fails, yields an empty list, and the prompt must be
        // indistinguishable from the pre-AMPR-388 one.
        val agent = agent(memoryService = null)

        val outcome = agent.runSubPlanForTask(task())

        assertTrue(
            outcome is Outcome.Success,
            "the tool-less step should succeed; got ${outcome::class.simpleName}",
        )
        val prompt = assertPlanningPromptOf(expectedCalls = 2)
        assertTrue(
            prompt.contains("Past Knowledge:\nNo relevant past knowledge available.\n\n"),
            "the no-knowledge block should render exactly as before; got:\n$prompt",
        )
        listOf("Relevant past experiences", "Approach:", "Learnings:", "Relevance:").forEach { marker ->
            assertFalse(
                prompt.contains(marker),
                "an empty recall should contribute no knowledge markers, found \"$marker\" in:\n$prompt",
            )
        }
    }

    // ==================== Regression: the no-knowledge prompt, byte for byte ====================

    @Test
    fun `PlanGenerator builds the same prompt for an empty recall as it did before`() = runBlocking<Unit> {
        val reasoning = AgentReasoning.create(
            config = AgentConfiguration(
                agentDefinition = WriteCodeAgent,
                aiConfiguration = FakeAIConfiguration(),
                llmProvider = capturingProvider,
            ),
            executorId = "ampr388-bare",
        ) {
            agentRole = "Test Planner"
            availableTools = { emptySet() }
        }

        val plan = reasoning.generatePlan(task = task(), ideas = listOf(IDEA), relevantKnowledge = emptyList())

        assertPlannedFromResponse(plan)
        assertEquals(EXPECTED_NO_KNOWLEDGE_PROMPT, assertSinglePrompt())
    }

    // ==================== Fixture ====================

    /**
     * Returns a plan with a single tool-less step, so execution dispatches no tool. Since
     * AMPR-407 such a step is still carried out by one model call of its own, which this
     * same provider answers — so a cycle that executes its plan sees two calls, not one.
     *
     * `toolToUse` is omitted entirely. AMPR-396 made `"toolToUse": null` read as a tool-less
     * step too (`PlanGenerator.stringOrNull` rejects `JsonNull`, whose `content` is the string
     * `"null"`), so either shape works now; an absent key keeps this fixture independent of
     * that parser detail.
     */
    private val capturingProvider: LlmProvider = { prompt ->
        prompts += prompt
        """
        {
          "steps": [
            { "description": "$PLANNED_STEP_DESCRIPTION", "requiresPreviousStep": false }
          ],
          "estimatedComplexity": 1,
          "requiresHumanInput": false
        }
        """.trimIndent()
    }

    /**
     * Asserts the plan is the one the fake model returned, not `PlanGenerator`'s fallback.
     *
     * Plan generation swallows any failure and falls back to a one-step "Execute: …" plan, so
     * without this a broken fixture would still satisfy every prompt assertion below.
     */
    private fun assertPlannedFromResponse(plan: Plan) {
        val step = plan.tasks.single()
        assertEquals(
            PLANNED_STEP_DESCRIPTION,
            (step as Task.CodeChange).description,
            "plan should come from the fake model's JSON, not the fallback path",
        )
    }

    private fun agent(memoryService: AgentMemoryService?): SparkBasedAgent<CodeState> =
        SparkBasedAgent(
            agentId = agentId,
            cognitiveAffinity = CognitiveAffinity.ANALYTICAL,
            initialState = CodeState.blank,
            _memoryService = memoryService,
            _aiConfiguration = FakeAIConfiguration(),
            _llmProvider = capturingProvider,
        )

    private fun task(): Task.CodeChange = Task.CodeChange(
        id = "ampr388-task",
        status = TaskStatus.Pending,
        description = TASK_DESCRIPTION,
    )

    private suspend fun storeOneEntryForCodeChange() {
        memoryService.storeKnowledge(
            knowledge = Knowledge.FromOutcome(
                outcomeId = "ampr388-outcome",
                approach = STORED_APPROACH,
                learnings = STORED_LEARNINGS,
                timestamp = Clock.System.now(),
            ),
            tags = listOf("code"),
            taskType = MemoryTaskTypes.CODE_CHANGE,
        ).getOrThrow()
    }

    /**
     * The one prompt the planner sent, with the system half stripped.
     *
     * A custom [LlmProvider] receives `"System: <system message>\n\nUser: <prompt>"`
     * (`AgentLLMService.buildCombinedPrompt`), and the system half here carries the agent's
     * spark stack ahead of the planning system message — so the delimiter is the last such
     * marker, not the first.
     */
    private fun assertSinglePrompt(): String {
        assertEquals(1, prompts.size, "expected exactly one model call, got: $prompts")
        return prompts.single().substringAfterLast("\n\nUser: ")
    }

    /**
     * The planning prompt of a cycle that also *executed* the plan it generated.
     *
     * The plan call comes first, and since AMPR-407 each tool-less step the plan produced
     * makes one EXECUTE call of its own after it — so a one-step sub-plan is two calls, not
     * one, and the planning prompt is the first.
     */
    private fun assertPlanningPromptOf(expectedCalls: Int): String {
        assertEquals(
            expectedCalls,
            prompts.size,
            "expected one planning call plus one per reasoning step, got: $prompts",
        )
        return prompts.first().substringAfterLast("\n\nUser: ")
    }

    private class FakeAIConfiguration : AIConfiguration {
        override val provider: AIProvider<*, *>
            get() = throw NotImplementedError("No provider should be reached behind a custom LlmProvider")
        override val model: AIModel
            get() = AIModel_OpenAI.GPT_4_1

        override fun getAvailableModels(): List<Pair<AIProvider<*, *>, AIModel>> = emptyList()
    }

    private companion object {
        const val TASK_DESCRIPTION = "Add a retry to the uploader"
        const val PLANNED_STEP_DESCRIPTION = "think about it"
        const val STORED_APPROACH = "Wrapped the upload call in an exponential backoff"
        const val STORED_LEARNINGS = "Retry only on transient network errors; a 4xx will never succeed"

        val IDEA = Idea(
            name = "Uploader flakiness",
            id = "ampr388-idea",
            description = "The uploader fails on transient network errors",
        )

        /**
         * What `PlanGenerator.buildPlanningPrompt` produces for [IDEA] and [TASK_DESCRIPTION]
         * with no tools and no recalled knowledge. Pinned byte for byte because AMPR-388's
         * constraint is that the no-knowledge case is unchanged: editing the planning prompt
         * is allowed, but it has to be a deliberate edit to this golden too.
         */
        val EXPECTED_NO_KNOWLEDGE_PROMPT = """
            You are the planning module of an autonomous Test Planner agent.
            Your task is to create a concrete, executable plan to accomplish the given task.

            Task: Add a retry to the uploader

            Insights from Perception:
            Uploader flakiness:
            The uploader fails on transient network errors

            Past Knowledge:
            No relevant past knowledge available.

            Create a step-by-step plan where each step is a concrete task that can be executed.
            Each step should:
            1. Have a clear, actionable description
            2. Specify which tool to use (if applicable)
            3. Be sequentially ordered with clear dependencies
            4. Include validation/verification steps where appropriate

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
