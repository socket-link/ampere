package link.socket.ampere.agents.domain.reasoning

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.execution.tools.Tool

/**
 * Evaluates an agent's perception and generates actionable insights.
 *
 * This component implements the "Perceive" phase of the PROPEL cognitive loop,
 * analyzing the agent's current state and generating ideas about what to focus on.
 *
 * The evaluation process:
 * 1. Build a context description from the current state
 * 2. Create a perception prompt asking for insights
 * 3. Call the LLM to generate structured insights
 * 4. Parse the response into an Idea
 *
 * Usage:
 * ```kotlin
 * val evaluator = PerceptionEvaluator(llmService)
 * val idea = evaluator.evaluate(
 *     perception = currentPerception,
 *     contextBuilder = { state -> buildContextForMyAgent(state) },
 *     agentRole = "Project Manager",
 *     availableTools = myTools,
 * )
 * ```
 *
 * @property llmService The LLM service for generating insights
 */
class PerceptionEvaluator(
    private val llmService: AgentLLMService,
) {

    /**
     * Evaluates a perception and generates insights as an Idea.
     *
     * @param perception The current perception containing agent state
     * @param contextBuilder Function to build context description from state
     * @param agentRole Description of the agent's role (e.g., "Project Manager", "Code Writer")
     * @param availableTools Tools available to the agent (for context in insights)
     * @return An Idea containing insights about the current situation
     */
    suspend fun <S : AgentState> evaluate(
        perception: Perception<S>,
        contextBuilder: (S) -> String,
        agentRole: String,
        availableTools: Set<Tool<*>> = emptySet(),
    ): Idea {
        val state = perception.currentState
        val contextDescription = contextBuilder(state)

        val prompt = buildPerceptionPrompt(
            agentRole = agentRole,
            contextDescription = contextDescription,
            availableTools = availableTools,
        )

        return try {
            val jsonResponse = llmService.callForJson(
                prompt = prompt,
                systemMessage = PERCEPTION_SYSTEM_MESSAGE,
                maxTokens = 500,
            )
            parseInsightsIntoIdea(jsonResponse.rawJson, agentRole)
        } catch (e: Exception) {
            createFallbackIdea(agentRole, "Evaluation failed: ${e.message}")
        }
    }

    /**
     * Builds the perception evaluation prompt.
     */
    private fun buildPerceptionPrompt(
        agentRole: String,
        contextDescription: String,
        availableTools: Set<Tool<*>>,
    ): String = buildString {
        appendLine("You are the perception module of an autonomous $agentRole agent.")
        appendLine("Analyze the current state and generate insights that will inform planning and execution.")
        appendLine()
        appendLine("Consider:")
        appendLine("- Is there a task that needs attention?")
        appendLine("- Are there patterns in recent successes or failures?")
        appendLine("- Are the necessary tools available for the current task?")
        appendLine("- What context from past outcomes should inform the current approach?")
        appendLine("- Are there warning signs (e.g., blocked tasks, missing information)?")
        appendLine()
        appendLine("Current State:")
        appendLine(contextDescription)
        appendLine()

        if (availableTools.isNotEmpty()) {
            appendLine("Available Tools:")
            availableTools.forEach { tool ->
                appendLine("  - ${tool.id}: ${tool.description}")
            }
            appendLine()
        }

        appendLine("Generate 1-3 specific, actionable insights about this situation.")
        appendLine("Each insight should identify something important and suggest why it matters.")
        appendLine()
        appendLine("Format your response as a JSON array of insight objects:")
        appendLine(
            """
[
  {
    "observation": "what you noticed",
    "implication": "why it matters",
    "confidence": "high|medium|low"
  }
]
            """.trimIndent(),
        )
        appendLine()
        appendLine("Respond ONLY with the JSON array, no other text.")
    }

    /**
     * Parses LLM insights JSON into an Idea.
     */
    private fun parseInsightsIntoIdea(jsonResponse: String, agentRole: String): Idea {
        val cleanedResponse = LLMResponseParser.cleanJsonResponse(jsonResponse)
        val insightsArray = LLMResponseParser.parseJsonArray(cleanedResponse)

        if (insightsArray.isEmpty()) {
            return createFallbackIdea(agentRole, "No insights generated")
        }

        val insights = insightsArray.map { element ->
            val obj = element.jsonObject
            val observation = obj["observation"]?.jsonPrimitive?.content ?: "No observation"
            val implication = obj["implication"]?.jsonPrimitive?.content ?: "No implication"
            val confidence = obj["confidence"]?.jsonPrimitive?.content ?: "medium"

            "$observation → $implication (confidence: $confidence)"
        }

        return Idea(
            name = "Perception analysis for $agentRole",
            description = insights.joinToString("\n\n"),
        )
    }

    /**
     * Creates a fallback Idea when evaluation fails.
     */
    private fun createFallbackIdea(agentRole: String, reason: String): Idea {
        return Idea(
            name = "Basic perception (fallback)",
            description = """
                Agent: $agentRole

                Note: Advanced perception analysis unavailable - $reason

                Please proceed with available information.
            """.trimIndent(),
        )
    }

    companion object {
        private const val PERCEPTION_SYSTEM_MESSAGE =
            "You are an analytical agent perception system. Respond only with valid JSON."
    }
}

/**
 * Builder for creating perception context descriptions.
 *
 * Provides a fluent API for building structured context that can be passed
 * to the PerceptionEvaluator.
 *
 * Usage:
 * ```kotlin
 * val context = PerceptionContextBuilder()
 *     .header("Project Manager State Analysis")
 *     .section("Current Tasks") {
 *         line("Active: ${tasks.size}")
 *         tasks.forEach { line("- ${it.title}") }
 *     }
 *     .section("Blockers") {
 *         blockers.forEach { line("! ${it.description}") }
 *     }
 *     .build()
 * ```
 */
class PerceptionContextBuilder {
    private val content = StringBuilder()

    fun header(title: String): PerceptionContextBuilder {
        content.appendLine("=== $title ===")
        content.appendLine()
        return this
    }

    fun section(title: String, block: SectionBuilder.() -> Unit): PerceptionContextBuilder {
        content.appendLine("$title:")
        val builder = SectionBuilder()
        builder.block()
        content.append(builder.build())
        content.appendLine()
        return this
    }

    fun sectionIf(
        condition: Boolean,
        title: String,
        block: SectionBuilder.() -> Unit,
    ): PerceptionContextBuilder {
        if (condition) {
            section(title, block)
        }
        return this
    }

    fun line(text: String): PerceptionContextBuilder {
        content.appendLine(text)
        return this
    }

    fun build(): String = content.toString()

    class SectionBuilder {
        private val content = StringBuilder()

        fun line(text: String) {
            content.appendLine("  $text")
        }

        fun field(name: String, value: Any?) {
            content.appendLine("  $name: $value")
        }

        fun build(): String = content.toString()
    }
}
