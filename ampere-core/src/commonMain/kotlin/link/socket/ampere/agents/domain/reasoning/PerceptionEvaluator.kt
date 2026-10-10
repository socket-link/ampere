package link.socket.ampere.agents.domain.reasoning

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.routing.RoutingContext
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.task.Task
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
     * @param perception The current perception containing agent state *and the
     *   ideas handed to it*. Both reach the prompt: the state through
     *   [contextBuilder], the ideas verbatim (AMPR-403). An idea is how a host
     *   puts its goal in front of the Perceive phase.
     * @param contextBuilder Function to build context description from state.
     *   Defaults to [defaultPerceptionContext], which renders the task the agent
     *   is on rather than `toString()` of the state.
     * @param agentRole Description of the agent's role (e.g., "Project Manager", "Code Writer")
     * @param availableTools Tools available to the agent (for context in insights)
     * @return An Idea containing insights about the current situation
     */
    suspend fun <S : AgentState> evaluate(
        perception: Perception<S>,
        contextBuilder: (S) -> String = { state -> defaultPerceptionContext(state) },
        agentRole: String,
        availableTools: Set<Tool<*>> = emptySet(),
        runId: RunId? = null,
    ): Idea {
        val state = perception.currentState
        val contextDescription = contextBuilder(state)

        val prompt = buildPerceptionPrompt(
            agentRole = agentRole,
            contextDescription = contextDescription,
            ideas = perception.ideas,
            availableTools = availableTools,
        )

        return try {
            val jsonResponse = llmService.callForJson(
                prompt = prompt,
                systemMessage = PERCEPTION_SYSTEM_MESSAGE,
                maxTokens = 500,
                routingContext = RoutingContext(
                    phase = CognitivePhase.PERCEIVE,
                    agentId = llmService.agentId,
                    agentRole = agentRole,
                    workflowId = runId ?: perception.id,
                ),
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
        ideas: List<Idea>,
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

        // AMPR-403: the ideas the caller handed to this perception are the
        // channel a host has for saying what it wants perceived. Dropping them
        // here is what made every iteration ask the same question about nothing.
        val statedIdeas = ideas.filterNot { it.isBlank() }
        if (statedIdeas.isNotEmpty()) {
            appendLine("Ideas In Play:")
            statedIdeas.forEach { idea ->
                appendLine("  - ${idea.name}")
                if (idea.description.isNotBlank()) {
                    idea.description.lineSequence().forEach { line ->
                        appendLine("    $line")
                    }
                }
            }
            appendLine()
        }

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
            val confidence = Confidence.parseOrDefault(obj["confidence"]?.jsonPrimitive?.content)

            "$observation → $implication (confidence: ${confidence.name.lowercase()})"
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
 * Whether an [Idea] carries nothing worth putting in a prompt.
 *
 * [Idea.blank] is what a memory cell holds before the first Perceive, and
 * `AutonomousAgent.runtimeLoop` hands the previous iteration's idea in
 * unconditionally — so on the first pass the list it passes is one blank.
 */
private fun Idea.isBlank(): Boolean = name.isBlank() && description.isBlank()

/**
 * The default `contextBuilder` for the Perceive phase (AMPR-403).
 *
 * Renders what the agent is actually on — the current task's description, the
 * plan under way, the latest outcome, and the most recent learnings — by reading
 * the state's current and past memory cells. Those cells are the live channel:
 * `Agent.rememberNewTask` writes the task there, and the role states
 * (`CodeState`, `QualityState`, …) are only ever constructed as `.blank`, so
 * their own fields say nothing.
 *
 * This replaced `{ state -> "State: $state" }`, which rendered a constant for
 * exactly that reason: every iteration asked the model the same question about
 * nothing.
 *
 * A host with more to say supplies its own builder through
 * `AgentReasoning.create { perceptionContextBuilder = { … } }`. That replaces
 * this default rather than extending it.
 */
fun defaultPerceptionContext(state: AgentState): String = buildString {
    val current = state.getCurrentMemory()
    val past = state.getPastMemory()

    appendLine("Current Task:")
    when (val task = current.task) {
        is Task.Blank -> appendLine("  (none assigned yet)")
        is Task.CodeChange -> {
            appendLine("  Description: ${task.description}")
            appendLine("  Id: ${task.id}")
            appendLine("  Status: ${task.status}")
            task.assignedTo?.let { appendLine("  Assigned To: $it") }
        }
        // `PMTask`, `TicketTask` and `MeetingTask` are open families with no
        // shared description field. Their members are data classes, so their
        // own `toString()` names their fields — unlike a role state's, which
        // is an identity hash.
        else -> {
            appendLine("  Kind: ${task::class.simpleName}")
            appendLine("  Id: ${task.id}")
            appendLine("  Status: ${task.status}")
            appendLine("  Detail: $task")
        }
    }

    appendLine()
    appendLine("Latest Outcome:")
    appendLine("  ${current.outcome.perceptionSummary()}")

    val planTasks = current.plan.tasks
    if (planTasks.isNotEmpty()) {
        appendLine()
        appendLine("Current Plan (${planTasks.size} steps):")
        planTasks.forEach { step ->
            val description = (step as? Task.CodeChange)?.description ?: step.id
            appendLine("  - [${step.status}] $description")
        }
    }

    val learnings = past.knowledgeFromOutcomes.takeLast(3)
    if (learnings.isNotEmpty()) {
        appendLine()
        appendLine("Recent Learnings:")
        learnings.forEach { knowledge ->
            appendLine("  - Approach: ${knowledge.approach}")
            appendLine("    Learnings: ${knowledge.learnings}")
        }
    }

    val earlierTasks = past.tasks.size
    if (earlierTasks > 0) {
        appendLine()
        appendLine("Tasks Seen Earlier: $earlierTasks")
    }
}

private fun Outcome.perceptionSummary(): String = when {
    this is Outcome.Blank -> "none yet"
    this is Outcome.Success -> "success (${this::class.simpleName})"
    this is Outcome.Failure -> "failure (${this::class.simpleName})"
    else -> this::class.simpleName ?: "unknown"
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
