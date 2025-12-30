package link.socket.ampere.agents.definition.product

import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.task.Task

/**
 * Parameter strategies for Product Manager Agent tools.
 *
 * Product Agent typically doesn't have custom tools, so this sealed class
 * is ready for extension when product-specific tools are added.
 */
sealed class ProductParams {
    // Ready for product-specific parameter strategies when tools are added
}

/**
 * Prompt templates for Product Manager Agent's LLM-driven decision making.
 */
object ProductPrompts {

    const val SYSTEM_PROMPT = """You are a Product Manager Agent responsible for:
- Breaking down features into actionable tasks
- Prioritizing work based on value and effort
- Analyzing backlog health and agent workloads
- Identifying blocked work and escalation needs
- Learning from past decomposition outcomes"""

    /**
     * Generates a perception context prompt from the agent's state.
     */
    fun perceptionContext(state: ProductAgentState): String = buildString {
        appendLine("# Product Manager State Analysis")
        appendLine()
        appendLine(state.toPerceptionText())
    }

    /**
     * Generates a planning prompt for feature decomposition.
     */
    fun planning(
        task: Task,
        ideas: List<Idea>,
        insights: PlanningInsights,
    ): String = buildString {
        appendLine("You are the planning module of an autonomous Product Manager agent.")
        appendLine()
        appendLine("## Task to Plan")
        when (task) {
            is Task.CodeChange -> {
                appendLine("- Type: Feature Implementation")
                appendLine("- Description: ${task.description}")
                appendLine("- Status: ${task.status}")
            }
            else -> {
                appendLine("- Type: ${task::class.simpleName}")
                appendLine("- ID: ${task.id}")
            }
        }
        appendLine()

        if (ideas.isNotEmpty()) {
            appendLine("## Current Insights")
            ideas.forEach { idea ->
                appendLine("- ${idea.name}: ${idea.description}")
            }
            appendLine()
        }

        // Include knowledge-derived insights
        if (insights.hasData()) {
            appendLine("## Learned Patterns from Past Experience")

            if (insights.testFirstSuccessRate > 0.5) {
                appendLine("- Test-first approach success rate: ${(insights.testFirstSuccessRate * 100).toInt()}%")
                if (insights.testFirstLearnings.isNotBlank()) {
                    appendLine("  Learnings: ${insights.testFirstLearnings.take(100)}")
                }
            }

            insights.optimalTaskCount?.let { count ->
                appendLine("- Optimal task count for similar features: $count tasks")
            }

            if (insights.commonFailures.isNotEmpty()) {
                appendLine("- Common failure patterns to avoid:")
                insights.commonFailures.entries.take(3).forEach { (pattern, _) ->
                    appendLine("  * $pattern")
                }
            }

            if (insights.decompositionLearnings.isNotBlank()) {
                appendLine("- Decomposition learnings: ${insights.decompositionLearnings.take(100)}")
            }
            appendLine()
        }

        appendLine("## Guidelines")
        appendLine("- Break features into 3-8 specific, actionable tasks")
        appendLine("- Each task should be completable by a single agent")
        appendLine("- Identify dependencies between tasks")
        appendLine("- Consider test-first approaches for complex features")
        appendLine("- Add validation steps for known failure patterns")
        appendLine()
        appendLine("## Output Format")
        appendLine("Respond with ONLY valid JSON:")
        appendLine(
            """{"steps": [{"description": "...", "priority": 1-10, "dependsOn": []}], "estimatedComplexity": 1-10}""",
        )
    }

    /**
     * Generates an outcome evaluation context.
     */
    fun outcomeContext(outcomes: List<Outcome>): String = buildString {
        appendLine("# Product Manager Outcome Analysis")
        appendLine()
        appendLine("Total: ${outcomes.size}")
        appendLine("Success: ${outcomes.count { it is Outcome.Success }}")
        appendLine("Failed: ${outcomes.count { it is Outcome.Failure }}")
        appendLine()

        outcomes.forEachIndexed { i, outcome ->
            when (outcome) {
                is Outcome.Success -> appendLine("${i + 1}. [SUCCESS] Task completed successfully")
                is Outcome.Failure -> appendLine("${i + 1}. [FAILURE] Task failed")
                else -> appendLine("${i + 1}. ${outcome::class.simpleName}")
            }
        }
    }
}

/**
 * Structured insights extracted from past knowledge.
 *
 * This is the "lesson summary" that informs future planning decisions.
 */
data class PlanningInsights(
    /** Success rate of test-first approaches (0.0 to 1.0) */
    val testFirstSuccessRate: Double = 0.0,
    /** Learnings about test-first approaches */
    val testFirstLearnings: String = "",
    /** Common failure patterns and their learnings */
    val commonFailures: Map<String, String> = emptyMap(),
    /** Optimal number of tasks based on past successes */
    val optimalTaskCount: Int? = null,
    /** Learnings about task decomposition */
    val decompositionLearnings: String = "",
) {
    /**
     * Returns true if this insights object has any meaningful data.
     */
    fun hasData(): Boolean =
        testFirstSuccessRate > 0 ||
            testFirstLearnings.isNotBlank() ||
            commonFailures.isNotEmpty() ||
            optimalTaskCount != null ||
            decompositionLearnings.isNotBlank()

    companion object {
        /**
         * Analyzes past knowledge to extract actionable insights.
         */
        fun fromKnowledge(knowledge: List<KnowledgeWithScore>): PlanningInsights {
            if (knowledge.isEmpty()) {
                return PlanningInsights()
            }

            // Filter to high-relevance knowledge (score > 0.5)
            val relevantKnowledge = knowledge.filter { it.relevanceScore > 0.5 }

            // Analyze learnings for test-first patterns
            val testFirstKnowledge = relevantKnowledge.filter { scored ->
                scored.knowledge.approach.contains("test", ignoreCase = true) ||
                    scored.knowledge.learnings.contains("test", ignoreCase = true)
            }
            val testFirstSuccessRate = if (testFirstKnowledge.isNotEmpty()) {
                testFirstKnowledge.map { it.relevanceScore }.average()
            } else {
                0.0
            }

            // Extract common failure patterns
            val failures = relevantKnowledge
                .filter {
                    it.knowledge.learnings.contains("failed", ignoreCase = true) ||
                        it.knowledge.learnings.contains("failure", ignoreCase = true)
                }
                .associate { scored ->
                    val failurePattern = scored.knowledge.learnings
                        .substringAfter("failure", "")
                        .substringAfter("failed", "")
                        .substringBefore(".")
                        .trim()
                        .take(50)
                    failurePattern to scored.knowledge.learnings
                }

            // Determine optimal task count
            val taskCountPattern = Regex("""(\d+)\s*tasks?""")
            val taskCounts = relevantKnowledge.mapNotNull { scored ->
                taskCountPattern.find(scored.knowledge.learnings)?.groupValues?.get(1)?.toIntOrNull()
            }
            val optimalTaskCount = if (taskCounts.isNotEmpty()) {
                taskCounts.average().toInt()
            } else {
                null
            }

            return PlanningInsights(
                testFirstSuccessRate = testFirstSuccessRate,
                testFirstLearnings = testFirstKnowledge.firstOrNull()?.knowledge?.learnings ?: "",
                commonFailures = failures,
                optimalTaskCount = optimalTaskCount,
                decompositionLearnings = relevantKnowledge
                    .firstOrNull { it.knowledge.learnings.contains("task", ignoreCase = true) }
                    ?.knowledge?.learnings ?: "",
            )
        }
    }
}
