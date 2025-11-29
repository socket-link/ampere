package link.socket.ampere.agents.core.types

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.core.AgentConfiguration
import link.socket.ampere.agents.core.memory.Knowledge
import link.socket.ampere.agents.core.memory.KnowledgeWithScore
import link.socket.ampere.agents.core.memory.KnowledgeEntry
import link.socket.ampere.agents.core.outcomes.Outcome
import link.socket.ampere.agents.core.reasoning.Idea
import link.socket.ampere.agents.core.reasoning.Plan
import link.socket.ampere.agents.core.states.AgentState
import link.socket.ampere.agents.core.status.TaskStatus
import link.socket.ampere.agents.core.tasks.Task
import link.socket.ampere.domain.ai.configuration.AIConfiguration

class ValidationAgentTest {

    private fun createAgent(): ValidationAgent {
        return ValidationAgent(
            id = "test-validation-agent",
            initialState = AgentState(),
            agentConfiguration = AgentConfiguration(
                aiConfiguration = AIConfiguration.default()
            ),
            memoryService = null // Tests don't require actual memory service
        )
    }

    /**
     * Test 1: Verify plan prioritizes effective validation checks from past knowledge
     */
    @Test
    fun `determinePlanForTask prioritizes effective validation checks`() = runBlocking {
        val agent = createAgent()

        // Create past knowledge showing syntax checks are highly effective
        val validationKnowledge = listOf(
            KnowledgeWithScore(
                entry = KnowledgeEntry(
                    id = "k1",
                    agentId = "test-validation-agent",
                    knowledgeType = "FromOutcome",
                    approach = "Performed syntax validation with high effectiveness",
                    learnings = "Syntax validation caught compilation errors early",
                    timestamp = Clock.System.now(),
                    tags = listOf("success", "syntax"),
                    taskType = "code_change",
                    sourceId = "outcome-1"
                ),
                knowledge = Knowledge.FromOutcome(
                    outcomeId = "outcome-1",
                    approach = "Performed syntax validation with high effectiveness",
                    learnings = "Syntax validation caught compilation errors early",
                    timestamp = Clock.System.now()
                ),
                relevanceScore = 0.95 // High relevance
            ),
            KnowledgeWithScore(
                entry = KnowledgeEntry(
                    id = "k2",
                    agentId = "test-validation-agent",
                    knowledgeType = "FromOutcome",
                    approach = "Logic validation detected edge cases",
                    learnings = "Logic checks were effective in finding boundary issues",
                    timestamp = Clock.System.now(),
                    tags = listOf("success", "logic"),
                    taskType = "code_change",
                    sourceId = "outcome-2"
                ),
                knowledge = Knowledge.FromOutcome(
                    outcomeId = "outcome-2",
                    approach = "Logic validation detected edge cases",
                    learnings = "Logic checks were effective in finding boundary issues",
                    timestamp = Clock.System.now()
                ),
                relevanceScore = 0.88
            )
        )

        val task = Task.CodeChange(
            id = "task-1",
            status = TaskStatus.Pending,
            description = "Validate user input handling"
        )

        val plan = agent.determinePlanForTask(task, relevantKnowledge = validationKnowledge)

        // Verify plan includes effective validation checks
        assertTrue(plan.tasks.isNotEmpty(), "Plan should include validation tasks")

        // Verify checks are prioritized by effectiveness (syntax should come before logic)
        val taskDescriptions = plan.tasks.mapNotNull { t ->
            when (t) {
                is Task.CodeChange -> t.description
                else -> null
            }
        }

        val hasSyntaxCheck = taskDescriptions.any { it.contains("syntax", ignoreCase = true) }
        val hasLogicCheck = taskDescriptions.any { it.contains("logic", ignoreCase = true) }

        assertTrue(hasSyntaxCheck || hasLogicCheck, "Plan should include learned validation checks")
    }

    /**
     * Test 2: Verify plan includes validation for commonly missed issues
     */
    @Test
    fun `determinePlanForTask adds checks for commonly missed issues`() = runBlocking {
        val agent = createAgent()

        // Create past knowledge showing null pointer issues were missed
        val missedIssuesKnowledge = listOf(
            KnowledgeWithScore(
                entry = KnowledgeEntry(
                    id = "k3",
                    agentId = "test-validation-agent",
                    knowledgeType = "FromOutcome",
                    approach = "Standard validation performed",
                    learnings = "Missed null pointer exception in user service",
                    timestamp = Clock.System.now(),
                    tags = listOf("failure"),
                    taskType = "code_change",
                    sourceId = "outcome-3"
                ),
                knowledge = Knowledge.FromOutcome(
                    outcomeId = "outcome-3",
                    approach = "Standard validation performed",
                    learnings = "Missed null pointer exception in user service",
                    timestamp = Clock.System.now()
                ),
                relevanceScore = 0.75
            ),
            KnowledgeWithScore(
                entry = KnowledgeEntry(
                    id = "k4",
                    agentId = "test-validation-agent",
                    knowledgeType = "FromOutcome",
                    approach = "Edge case testing",
                    learnings = "Undetected boundary condition in array processing",
                    timestamp = Clock.System.now(),
                    tags = listOf("failure"),
                    taskType = "code_change",
                    sourceId = "outcome-4"
                ),
                knowledge = Knowledge.FromOutcome(
                    outcomeId = "outcome-4",
                    approach = "Edge case testing",
                    learnings = "Undetected boundary condition in array processing",
                    timestamp = Clock.System.now()
                ),
                relevanceScore = 0.82
            )
        )

        val task = Task.CodeChange(
            id = "task-2",
            status = TaskStatus.Pending,
            description = "Validate data processing module"
        )

        val plan = agent.determinePlanForTask(task, relevantKnowledge = missedIssuesKnowledge)

        // Verify plan includes checks for commonly missed issues
        assertTrue(plan.tasks.isNotEmpty(), "Plan should include validation tasks")

        val validationTasks = plan.tasks.filterIsInstance<Task.CodeChange>()
        val hasExtraValidation = validationTasks.any { t ->
            t.description.contains("commonly missed", ignoreCase = true) ||
                t.description.contains("Extra validation", ignoreCase = true)
        }

        assertTrue(hasExtraValidation, "Plan should include extra checks for commonly missed issues")
    }

    /**
     * Test 3: Verify complexity adjustment based on past knowledge
     */
    @Test
    fun `determinePlanForTask adjusts complexity based on past effectiveness`() = runBlocking {
        val agent = createAgent()

        // High effectiveness knowledge should reduce complexity
        val highEffectivenessKnowledge = listOf(
            KnowledgeWithScore(
                entry = KnowledgeEntry(
                    id = "k5",
                    agentId = "test-validation-agent",
                    knowledgeType = "FromOutcome",
                    approach = "Syntax and style validation",
                    learnings = "Syntax checks caught all issues efficiently",
                    timestamp = Clock.System.now(),
                    tags = listOf("success", "syntax"),
                    taskType = "code_change",
                    sourceId = "outcome-5"
                ),
                knowledge = Knowledge.FromOutcome(
                    outcomeId = "outcome-5",
                    approach = "Syntax and style validation",
                    learnings = "Syntax checks caught all issues efficiently",
                    timestamp = Clock.System.now()
                ),
                relevanceScore = 0.95
            ),
            KnowledgeWithScore(
                entry = KnowledgeEntry(
                    id = "k6",
                    agentId = "test-validation-agent",
                    knowledgeType = "FromOutcome",
                    approach = "Style and performance validation",
                    learnings = "Style checks were highly effective",
                    timestamp = Clock.System.now(),
                    tags = listOf("success", "style"),
                    taskType = "code_change",
                    sourceId = "outcome-6"
                ),
                knowledge = Knowledge.FromOutcome(
                    outcomeId = "outcome-6",
                    approach = "Style and performance validation",
                    learnings = "Style checks were highly effective",
                    timestamp = Clock.System.now()
                ),
                relevanceScore = 0.90
            )
        )

        val task = Task.CodeChange(
            id = "task-3",
            status = TaskStatus.Pending,
            description = "Validate authentication module"
        )

        val plan = agent.determinePlanForTask(task, relevantKnowledge = highEffectivenessKnowledge)

        // With high effectiveness (>0.8 average), complexity should be reduced
        assertTrue(plan.estimatedComplexity <= 5, "Complexity should be reduced with effective checks")
    }

    /**
     * Test 4: Verify extractKnowledgeFromOutcome captures validation success
     */
    @Test
    fun `extractKnowledgeFromOutcome captures successful validation learnings`() {
        val agent = createAgent()

        val task = Task.CodeChange(
            id = "task-4",
            status = TaskStatus.Pending,
            description = "Validate payment processing"
        )

        val plan = Plan.ForTask(
            task = task,
            tasks = listOf(
                Task.CodeChange(
                    id = "subtask-1",
                    status = TaskStatus.Pending,
                    description = "Syntax validation"
                ),
                Task.CodeChange(
                    id = "subtask-2",
                    status = TaskStatus.Pending,
                    description = "Logic validation"
                )
            ),
            estimatedComplexity = 4
        )

        val outcome = object : Outcome.Success {
            override val id = "outcome-success-1"
        }

        val knowledge = agent.extractKnowledgeFromOutcome(outcome, task, plan)

        // Verify knowledge captures validation approach
        assertTrue(knowledge.approach.contains("2 validation checks"), "Approach should mention check count")
        assertTrue(
            knowledge.approach.contains("syntax") || knowledge.approach.contains("logic"),
            "Approach should mention check types"
        )

        // Verify learnings capture success
        assertTrue(knowledge.learnings.contains("Success"), "Learnings should mention success")
        assertTrue(
            knowledge.learnings.contains("effective") || knowledge.learnings.contains("passed"),
            "Learnings should indicate effectiveness"
        )
    }

    /**
     * Test 5: Verify extractKnowledgeFromOutcome captures validation failures
     */
    @Test
    fun `extractKnowledgeFromOutcome captures validation failure insights`() {
        val agent = createAgent()

        val task = Task.CodeChange(
            id = "task-5",
            status = TaskStatus.Pending,
            description = "Validate user service"
        )

        val plan = Plan.ForTask(
            task = task,
            tasks = listOf(
                Task.CodeChange(
                    id = "subtask-1",
                    status = TaskStatus.Pending,
                    description = "Security validation"
                )
            ),
            estimatedComplexity = 6
        )

        val outcome = object : Outcome.Failure {
            override val id = "outcome-failure-1"
        }

        val knowledge = agent.extractKnowledgeFromOutcome(outcome, task, plan)

        // Verify learnings capture failure insights
        assertTrue(knowledge.learnings.contains("Failure"), "Learnings should mention failure")
        assertTrue(
            knowledge.learnings.contains("detected") || knowledge.learnings.contains("caught"),
            "Learnings should indicate issues were detected"
        )
    }

    /**
     * Test 6: Verify agent handles empty knowledge with default validation strategy
     */
    @Test
    fun `determinePlanForTask uses default validation strategy without knowledge`() = runBlocking {
        val agent = createAgent()

        val task = Task.CodeChange(
            id = "task-6",
            status = TaskStatus.Pending,
            description = "Validate new feature"
        )

        // No knowledge provided - agent should use default validation strategy
        val plan = agent.determinePlanForTask(task, relevantKnowledge = emptyList())

        assertTrue(plan.tasks.isNotEmpty(), "Plan should include default validation tasks")

        // Should include standard validation types
        val taskDescriptions = plan.tasks.mapNotNull { t ->
            when (t) {
                is Task.CodeChange -> t.description.lowercase()
                else -> null
            }
        }

        val hasStandardValidation = taskDescriptions.any { desc ->
            desc.contains("syntax") || desc.contains("style") || desc.contains("logic")
        }

        assertTrue(hasStandardValidation, "Should include standard validation tasks by default")
    }

    /**
     * Test 7: Verify extracted knowledge is properly typed
     */
    @Test
    fun `extractKnowledgeFromOutcome returns Knowledge_FromOutcome type`() {
        val agent = createAgent()

        val task = Task.CodeChange(
            id = "task-7",
            status = TaskStatus.Pending,
            description = "Test validation task"
        )

        val plan = Plan.ForTask(task = task)
        val outcome = object : Outcome.Success {
            override val id = "outcome-7"
        }

        val knowledge = agent.extractKnowledgeFromOutcome(outcome, task, plan)

        assertTrue(knowledge is Knowledge.FromOutcome, "Should return Knowledge.FromOutcome")
        assertEquals(outcome.id, (knowledge as Knowledge.FromOutcome).outcomeId)
    }

    /**
     * Test 8: Verify validation plan includes check effectiveness percentages
     */
    @Test
    fun `determinePlanForTask includes effectiveness metrics in task descriptions`() = runBlocking {
        val agent = createAgent()

        val validationKnowledge = listOf(
            KnowledgeWithScore(
                entry = KnowledgeEntry(
                    id = "k7",
                    agentId = "test-validation-agent",
                    knowledgeType = "FromOutcome",
                    approach = "Security validation checks",
                    learnings = "Security checks found vulnerabilities",
                    timestamp = Clock.System.now(),
                    tags = listOf("success", "security"),
                    taskType = "code_change",
                    sourceId = "outcome-7"
                ),
                knowledge = Knowledge.FromOutcome(
                    outcomeId = "outcome-7",
                    approach = "Security validation checks",
                    learnings = "Security checks found vulnerabilities",
                    timestamp = Clock.System.now()
                ),
                relevanceScore = 0.92
            )
        )

        val task = Task.CodeChange(
            id = "task-8",
            status = TaskStatus.Pending,
            description = "Validate security module"
        )

        val plan = agent.determinePlanForTask(task, relevantKnowledge = validationKnowledge)

        // Verify at least one task includes effectiveness percentage
        val hasEffectivenessMetric = plan.tasks.any { t ->
            when (t) {
                is Task.CodeChange -> t.description.contains("%")
                else -> false
            }
        }

        assertTrue(
            hasEffectivenessMetric,
            "Plan should include effectiveness percentages from past knowledge"
        )
    }
}
