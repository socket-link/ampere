package link.socket.ampere.agents.domain.reasoning

import kotlinx.datetime.Clock
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome

/**
 * Evaluates execution outcomes and generates learnings.
 *
 * This component implements the "Evaluate" and "Learn" phases of the PROPEL
 * cognitive loop, analyzing outcomes to identify patterns and extract
 * actionable knowledge for future tasks.
 *
 * The evaluation process:
 * 1. Build analysis context from outcomes
 * 2. Create evaluation prompt for pattern identification
 * 3. Call LLM to generate structured insights
 * 4. Parse response into Knowledge objects
 * 5. Create summary Idea for next perception phase
 *
 * Usage:
 * ```kotlin
 * val evaluator = OutcomeEvaluator(llmService)
 * val result = evaluator.evaluate(
 *     outcomes = recentOutcomes,
 *     agentRole = "Project Manager",
 *     contextBuilder = { outcomes -> buildPMOutcomeContext(outcomes) },
 * )
 * result.knowledge.forEach { memoryService.store(it) }
 * val nextIdea = result.summaryIdea
 * ```
 *
 * @property llmService The LLM service for generating insights
 */
class OutcomeEvaluator(
    private val llmService: AgentLLMService,
) {

    /**
     * Evaluates outcomes and generates learnings.
     *
     * @param outcomes List of outcomes from recent executions
     * @param agentRole Description of the agent's role
     * @param contextBuilder Optional custom context builder for outcomes
     * @return EvaluationResult containing knowledge and summary idea
     */
    suspend fun evaluate(
        outcomes: List<Outcome>,
        agentRole: String,
        contextBuilder: ((List<Outcome>) -> String)? = null,
    ): EvaluationResult {
        // Handle empty or blank outcomes
        if (outcomes.isEmpty()) {
            return EvaluationResult(
                knowledge = emptyList(),
                summaryIdea = Idea(
                    name = "No outcomes to evaluate",
                    description = "No execution outcomes were provided for evaluation.",
                ),
            )
        }

        if (outcomes.all { it is Outcome.Blank }) {
            return EvaluationResult(
                knowledge = emptyList(),
                summaryIdea = Idea(
                    name = "Only blank outcomes",
                    description = "All provided outcomes were blank - no learnings can be extracted.",
                ),
            )
        }

        val analysisContext = contextBuilder?.invoke(outcomes)
            ?: buildDefaultOutcomeContext(outcomes)

        val prompt = buildEvaluationPrompt(analysisContext, agentRole)

        return try {
            val jsonResponse = llmService.callForJson(
                prompt = prompt,
                systemMessage = EVALUATION_SYSTEM_MESSAGE,
                maxTokens = 1000,
            )
            val knowledge = parseLearningsFromResponse(jsonResponse.rawJson, outcomes)
            val summaryIdea = createSummaryIdea(knowledge, outcomes)

            EvaluationResult(knowledge, summaryIdea)
        } catch (e: Exception) {
            createFallbackResult(outcomes, agentRole, "Evaluation failed: ${e.message}")
        }
    }

    /**
     * Builds default outcome analysis context.
     */
    private fun buildDefaultOutcomeContext(outcomes: List<Outcome>): String {
        val successfulOutcomes = outcomes.filterIsInstance<Outcome.Success>()
        val failedOutcomes = outcomes.filterIsInstance<Outcome.Failure>()

        return buildString {
            appendLine("=== Execution Outcome Analysis ===")
            appendLine()
            appendLine("Total Outcomes: ${outcomes.size}")
            appendLine("Successful: ${successfulOutcomes.size}")
            appendLine("Failed: ${failedOutcomes.size}")
            appendLine()

            if (successfulOutcomes.isNotEmpty()) {
                appendLine("Successful Executions:")
                appendLine()
                successfulOutcomes.forEach { outcome ->
                    appendOutcomeDetails(outcome, "✓")
                }
            }

            if (failedOutcomes.isNotEmpty()) {
                appendLine("Failed Executions:")
                appendLine()
                failedOutcomes.forEach { outcome ->
                    appendOutcomeDetails(outcome, "✗")
                }
            }

            // Summary statistics
            appendLine("Summary:")
            val successRate = if (outcomes.isNotEmpty()) {
                (successfulOutcomes.size.toDouble() / outcomes.size.toDouble() * 100).toInt()
            } else {
                0
            }
            appendLine("  Success Rate: $successRate%")

            // Calculate average duration for execution outcomes
            val executionOutcomes = outcomes.filterIsInstance<ExecutionOutcome>()
            if (executionOutcomes.isNotEmpty()) {
                val totalDuration = executionOutcomes.sumOf { outcome ->
                    (outcome.executionEndTimestamp - outcome.executionStartTimestamp).inWholeMilliseconds
                }
                val avgDuration = totalDuration / executionOutcomes.size
                appendLine("  Average Execution Duration: ${avgDuration}ms")
            }
        }
    }

    /**
     * Appends details for a single outcome.
     */
    private fun StringBuilder.appendOutcomeDetails(outcome: Outcome, prefix: String) {
        when (outcome) {
            is ExecutionOutcome.IssueManagement.Success -> {
                val duration = outcome.executionEndTimestamp - outcome.executionStartTimestamp
                appendLine("$prefix Issue Management Succeeded")
                appendLine("  Task: ${outcome.taskId}")
                appendLine("  Issues Created: ${outcome.response.created.size}")
                appendLine("  Duration: $duration")
                appendLine()
            }
            is ExecutionOutcome.IssueManagement.Failure -> {
                val duration = outcome.executionEndTimestamp - outcome.executionStartTimestamp
                appendLine("$prefix Issue Management Failed")
                appendLine("  Task: ${outcome.taskId}")
                appendLine("  Error: ${outcome.error.message}")
                appendLine("  Duration: $duration")
                appendLine()
            }
            is ExecutionOutcome.CodeChanged.Success -> {
                val duration = outcome.executionEndTimestamp - outcome.executionStartTimestamp
                appendLine("$prefix Code Changed Successfully")
                appendLine("  Task: ${outcome.taskId}")
                appendLine("  Files Changed: ${outcome.changedFiles.size}")
                appendLine("  Duration: $duration")
                appendLine()
            }
            is ExecutionOutcome.CodeChanged.Failure -> {
                val duration = outcome.executionEndTimestamp - outcome.executionStartTimestamp
                appendLine("$prefix Code Change Failed")
                appendLine("  Task: ${outcome.taskId}")
                appendLine("  Error: ${outcome.error}")
                appendLine("  Duration: $duration")
                appendLine()
            }
            is ExecutionOutcome.NoChanges.Success -> {
                val duration = outcome.executionEndTimestamp - outcome.executionStartTimestamp
                appendLine("$prefix No Changes (Success)")
                appendLine("  Task: ${outcome.taskId}")
                appendLine("  Message: ${outcome.message}")
                appendLine("  Duration: $duration")
                appendLine()
            }
            is ExecutionOutcome.NoChanges.Failure -> {
                val duration = outcome.executionEndTimestamp - outcome.executionStartTimestamp
                appendLine("$prefix Execution Failed")
                appendLine("  Task: ${outcome.taskId}")
                appendLine("  Message: ${outcome.message}")
                appendLine("  Duration: $duration")
                appendLine()
            }
            else -> {
                appendLine("$prefix ${outcome::class.simpleName}")
                if (outcome is ExecutionOutcome) {
                    appendLine("  Task: ${outcome.taskId}")
                }
                appendLine()
            }
        }
    }

    /**
     * Builds the evaluation prompt.
     */
    private fun buildEvaluationPrompt(analysisContext: String, agentRole: String): String = buildString {
        appendLine("You are the learning module of an autonomous $agentRole agent.")
        appendLine("Analyze execution outcomes to generate insights that will improve future performance.")
        appendLine()
        appendLine("Your goal is to identify:")
        appendLine("1. What patterns distinguish successful executions from failures")
        appendLine("2. Which approaches, tools, or strategies correlate with success")
        appendLine("3. What common failure modes exist and how to avoid them")
        appendLine("4. What meta-patterns exist (e.g., \"simple tasks succeed more than complex ones\")")
        appendLine()
        appendLine("Execution Data:")
        appendLine(analysisContext)
        appendLine()
        appendLine("Generate 2-4 specific, actionable insights. Each insight should:")
        appendLine("- Identify a clear pattern observed in the data")
        appendLine("- Explain why this pattern matters for future executions")
        appendLine("- Suggest a concrete change to behavior based on this pattern")
        appendLine("- Include confidence level (high/medium/low) based on evidence strength")
        appendLine()
        appendLine("Focus on insights that will actually improve performance, not just observations.")
        appendLine("\"Task execution sometimes fails\" is an observation.")
        appendLine("\"Tasks fail when dependencies are unmet; verify dependencies first\" is actionable.")
        appendLine()
        appendLine("Format your response as a JSON array:")
        appendLine(
            """
[
  {
    "pattern": "what pattern you identified",
    "reasoning": "why this pattern emerged and why it matters",
    "actionableAdvice": "what to do differently based on this",
    "confidence": "high|medium|low",
    "evidenceCount": number_of_supporting_examples
  }
]
            """.trimIndent(),
        )
        appendLine()
        appendLine("Respond ONLY with the JSON array, no other text.")
    }

    /**
     * Parses the LLM response into Knowledge objects.
     */
    private fun parseLearningsFromResponse(
        jsonResponse: String,
        outcomes: List<Outcome>,
    ): List<Knowledge.FromOutcome> {
        val cleanedResponse = LLMResponseParser.cleanJsonResponse(jsonResponse)
        val learningsArray = LLMResponseParser.parseJsonArray(cleanedResponse)

        if (learningsArray.isEmpty()) {
            return emptyList()
        }

        val now = Clock.System.now()

        return learningsArray.mapNotNull { element ->
            try {
                val obj = element.jsonObject
                val pattern = obj["pattern"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val reasoning = obj["reasoning"]?.jsonPrimitive?.content ?: ""
                val actionableAdvice = obj["actionableAdvice"]?.jsonPrimitive?.content ?: ""
                val confidence = obj["confidence"]?.jsonPrimitive?.content ?: "medium"
                val evidenceCount = obj["evidenceCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1

                val outcomeId = outcomes.firstOrNull { it.id.isNotBlank() }?.id
                    ?: "composite-${outcomes.hashCode()}"

                Knowledge.FromOutcome(
                    outcomeId = outcomeId,
                    approach = pattern,
                    learnings = buildString {
                        appendLine("Reasoning: $reasoning")
                        appendLine()
                        appendLine("Actionable Advice: $actionableAdvice")
                        appendLine()
                        appendLine("Confidence: $confidence")
                        appendLine("Evidence Count: $evidenceCount")
                    },
                    timestamp = now,
                )
            } catch (e: Exception) {
                null // Skip malformed entries
            }
        }
    }

    /**
     * Creates a summary Idea from the extracted knowledge.
     */
    private fun createSummaryIdea(
        knowledge: List<Knowledge.FromOutcome>,
        outcomes: List<Outcome>,
    ): Idea {
        val successCount = outcomes.count { it is Outcome.Success }
        val failureCount = outcomes.count { it is Outcome.Failure }

        val description = buildString {
            appendLine(
                "Learnings from ${outcomes.size} execution outcomes " +
                    "($successCount successful, $failureCount failed):",
            )
            appendLine()

            if (knowledge.isEmpty()) {
                appendLine("No specific learnings extracted - outcomes may be too similar or data insufficient.")
            } else {
                knowledge.forEachIndexed { index, k ->
                    appendLine("${index + 1}. ${k.approach}")
                    appendLine()
                    k.learnings.lines().forEach { line ->
                        if (line.isNotBlank()) {
                            appendLine("   $line")
                        }
                    }
                    appendLine()
                }
            }
        }

        return Idea(
            name = "Outcome evaluation: ${outcomes.size} executions analyzed",
            description = description,
        )
    }

    /**
     * Creates a fallback result when evaluation fails.
     */
    private fun createFallbackResult(
        outcomes: List<Outcome>,
        agentRole: String,
        reason: String,
    ): EvaluationResult {
        val successCount = outcomes.count { it is Outcome.Success }
        val failureCount = outcomes.count { it is Outcome.Failure }
        val successRate = if (outcomes.isNotEmpty()) {
            (successCount.toDouble() / outcomes.size.toDouble() * 100).toInt()
        } else {
            0
        }

        val description = buildString {
            appendLine("Basic outcome statistics (advanced analysis unavailable - $reason):")
            appendLine()
            appendLine("Total Outcomes: ${outcomes.size}")
            appendLine("Successful: $successCount")
            appendLine("Failed: $failureCount")
            appendLine("Success Rate: $successRate%")
            appendLine()

            if (failureCount > successCount) {
                appendLine("⚠ High failure rate suggests tasks may be too complex or dependencies unmet.")
                appendLine("Consider: Breaking tasks into smaller steps or validating preconditions.")
            } else {
                appendLine("✓ Reasonable success rate - continue with current approach.")
            }
        }

        val now = Clock.System.now()
        val outcomeId = outcomes.firstOrNull { it.id.isNotBlank() }?.id
            ?: "fallback-${outcomes.hashCode()}"

        val fallbackKnowledge = Knowledge.FromOutcome(
            outcomeId = outcomeId,
            approach = "Completed ${outcomes.size} executions with $successRate% success rate",
            learnings = if (failureCount > successCount) {
                "High failure rate indicates need for simpler tasks or better dependency validation"
            } else {
                "Continue with current approach"
            },
            timestamp = now,
        )

        return EvaluationResult(
            knowledge = listOf(fallbackKnowledge),
            summaryIdea = Idea(
                name = "Outcome evaluation (basic statistics)",
                description = description,
            ),
        )
    }

    companion object {
        private const val EVALUATION_SYSTEM_MESSAGE =
            "You are an autonomous agent learning system. Analyze outcomes and extract actionable insights. Respond only with valid JSON."
    }
}

/**
 * Result of outcome evaluation containing extracted knowledge and summary.
 *
 * @property knowledge List of knowledge entries to store in memory
 * @property summaryIdea Idea summarizing the learnings for next perception
 */
data class EvaluationResult(
    val knowledge: List<Knowledge.FromOutcome>,
    val summaryIdea: Idea,
)
