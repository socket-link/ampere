package link.socket.ampere.agents.definition.qa

import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.task.Task

/**
 * Parameter strategies for Quality Assurance Agent tools.
 *
 * QA Agent typically uses test execution and code analysis tools.
 * This sealed class is ready for extension when QA-specific tools are added.
 */
sealed class QualityParams {
    // Ready for QA-specific parameter strategies when tools are added
}

/**
 * Prompt templates for Quality Assurance Agent's LLM-driven decision making.
 */
object QualityPrompts {

    const val SYSTEM_PROMPT = """You are a Quality Assurance Agent responsible for:
- Validating code quality and correctness
- Running and analyzing test results
- Identifying potential bugs and issues
- Ensuring adherence to coding standards
- Learning from past validation outcomes"""

    /**
     * Generates a perception context prompt from the agent's state.
     */
    fun perceptionContext(state: QualityState): String = buildString {
        appendLine("# Quality Assurance State Analysis")
        appendLine()
        appendLine(state.toPerceptionText())
    }

    /**
     * Generates a planning prompt for validation tasks.
     */
    fun planning(
        task: Task,
        ideas: List<Idea>,
        insights: ValidationInsights,
    ): String = buildString {
        appendLine("You are the planning module of an autonomous Quality Assurance agent.")
        appendLine()
        appendLine("## Task to Validate")
        when (task) {
            is Task.CodeChange -> {
                appendLine("- Type: Code Validation")
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
            appendLine("## Learned Patterns from Past Validations")

            if (insights.effectiveChecks.isNotEmpty()) {
                appendLine("- Effective validation checks:")
                insights.effectiveChecks.entries
                    .sortedByDescending { it.value }
                    .take(5)
                    .forEach { (check, effectiveness) ->
                        appendLine("  * $check: ${(effectiveness * 100).toInt()}% effectiveness")
                    }
            }

            if (insights.commonlyMissedIssues.isNotEmpty()) {
                appendLine("- Commonly missed issues to watch for:")
                insights.commonlyMissedIssues.forEach { issue ->
                    appendLine("  * $issue")
                }
            }
            appendLine()
        }

        appendLine("## Guidelines")
        appendLine("- Prioritize high-effectiveness validation checks")
        appendLine("- Add extra validation for commonly missed issues")
        appendLine("- Standard checks: syntax, style, logic, security, performance")
        appendLine("- Adjust complexity based on past outcomes")
        appendLine()
        appendLine("## Output Format")
        appendLine("Respond with ONLY valid JSON:")
        appendLine("""{"steps": [{"description": "...",""")
        appendLine(""" "checkType": "syntax|style|logic|security|performance|testing",""")
        appendLine(""" "priority": 1-10}], "estimatedComplexity": 1-10}""")
    }

    /**
     * Generates an outcome evaluation context.
     */
    fun outcomeContext(outcomes: List<Outcome>): String = buildString {
        appendLine("# Quality Assurance Outcome Analysis")
        appendLine()
        appendLine("Total validations: ${outcomes.size}")
        appendLine("Passed: ${outcomes.count { it is Outcome.Success }}")
        appendLine("Failed: ${outcomes.count { it is Outcome.Failure }}")
        appendLine()

        outcomes.forEachIndexed { i, outcome ->
            when (outcome) {
                is Outcome.Success -> appendLine("${i + 1}. [PASS] Validation passed")
                is Outcome.Failure -> appendLine("${i + 1}. [FAIL] Issues detected")
                else -> appendLine("${i + 1}. ${outcome::class.simpleName}")
            }
        }
    }
}

/**
 * Structured validation insights extracted from past knowledge.
 *
 * Captures which validation approaches work best and what issues are commonly missed.
 */
data class ValidationInsights(
    /** Effectiveness score for each check type (0.0 to 1.0) */
    val effectiveChecks: Map<String, Double> = emptyMap(),
    /** Types of issues that are commonly missed */
    val commonlyMissedIssues: List<String> = emptyList(),
) {
    /**
     * Returns true if this insights object has any meaningful data.
     */
    fun hasData(): Boolean =
        effectiveChecks.isNotEmpty() || commonlyMissedIssues.isNotEmpty()

    companion object {
        /** Standard check types used in validation */
        val CHECK_TYPES = listOf("syntax", "style", "logic", "security", "performance", "testing")

        /**
         * Analyzes past knowledge to extract validation insights.
         */
        fun fromKnowledge(knowledge: List<KnowledgeWithScore>): ValidationInsights {
            if (knowledge.isEmpty()) {
                return ValidationInsights()
            }

            val relevantKnowledge = knowledge.filter { it.relevanceScore > 0.5 }

            // Extract effective validation check types
            val effectiveChecks = CHECK_TYPES.mapNotNull { checkType ->
                val relevantChecks = relevantKnowledge.filter { scored ->
                    scored.knowledge.approach.contains(checkType, ignoreCase = true) ||
                        scored.knowledge.learnings.contains(checkType, ignoreCase = true)
                }
                if (relevantChecks.isNotEmpty()) {
                    checkType to relevantChecks.map { it.relevanceScore }.average()
                } else {
                    null
                }
            }.toMap()

            // Extract commonly missed issue types
            val missedIssues = relevantKnowledge
                .filter {
                    it.knowledge.learnings.contains("missed", ignoreCase = true) ||
                        it.knowledge.learnings.contains("undetected", ignoreCase = true) ||
                        it.knowledge is Knowledge.FromOutcome
                }
                .mapNotNull { scored ->
                    val learnings = scored.knowledge.learnings.lowercase()
                    when {
                        learnings.contains("null") || learnings.contains("npe") -> "null pointer issues"
                        learnings.contains("boundary") || learnings.contains("edge case") -> "boundary conditions"
                        learnings.contains("concurrency") || learnings.contains("race") -> "concurrency issues"
                        learnings.contains("security") -> "security vulnerabilities"
                        learnings.contains("memory") || learnings.contains("leak") -> "memory issues"
                        else -> null
                    }
                }
                .distinct()

            return ValidationInsights(
                effectiveChecks = effectiveChecks,
                commonlyMissedIssues = missedIssues,
            )
        }
    }
}
