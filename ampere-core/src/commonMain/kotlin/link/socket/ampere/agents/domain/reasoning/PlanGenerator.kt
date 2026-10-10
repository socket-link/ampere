package link.socket.ampere.agents.domain.reasoning

import co.touchlab.kermit.Logger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.expectation.Expectations
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.domain.routing.RoutingContext
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.AssignedTo
import link.socket.ampere.agents.domain.task.ExecutionAssignment
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.execution.ToolArgumentSchema
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.llm.MissingUpstreamLlmClientException
import link.socket.ampere.util.logWith

/**
 * Generates executable plans for accomplishing tasks.
 *
 * This component implements the "Plan" phase of the PROPEL cognitive loop,
 * transforming high-level understanding (task + ideas) into concrete,
 * sequential steps that can be executed by the agent.
 *
 * The planning process:
 * 1. Extract task description and synthesize ideas
 * 2. Build a planning prompt with task context and available tools
 * 3. Call LLM to generate structured plan steps
 * 4. Parse LLM response into Plan object with Task objects, including whether
 *    the planner asked for a person ([Plan.requiresHumanInput]), which seat runs
 *    each step, and a schema-described tool's arguments inline ([PlanSeat])
 * 5. Fall back to simple plan if LLM call or parsing fails
 *
 * Usage:
 * ```kotlin
 * val generator = PlanGenerator(llmService)
 * val plan = generator.generate(
 *     task = currentTask,
 *     ideas = perceptionIdeas,
 *     agentRole = "Project Manager",
 *     availableTools = myTools,
 *     relevantKnowledge = recalledKnowledge,
 *     taskFactory = { id, description, toolId, _ -> PMTask.SomeTask(id, description, toolId) },
 * )
 * ```
 *
 * @property llmService The LLM service for generating plans
 */
class PlanGenerator(
    private val llmService: AgentLLMService,
) {

    private val logger: Logger = logWith("PlanGenerator")

    /**
     * Generates a plan for accomplishing the given task.
     *
     * @param task The task to plan for
     * @param ideas Insights from perception that inform planning
     * @param agentRole Description of the agent's role
     * @param availableTools Tools available to the agent
     * @param seats The seats the plan may assign steps to, and the tools each owns
     *   (AMPR-410). Non-empty makes the prompt roster-aware — `seat: tools` rather than
     *   one flat tool list — and makes every parsed step name a seat. Empty is the
     *   single-seat case: the prompt and the plan are what they were, and
     *   [availableTools] is the whole tool list.
     * @param relevantKnowledge Past knowledge Recall retrieved for this task. Rendered into
     *   the prompt by `synthesizeKnowledge`; an empty list renders the no-knowledge block
     *   and is the only case in which the prompt carries no past experience.
     * @param taskFactory Factory function to create domain-specific task objects
     * @param customPromptBuilder Optional custom prompt builder for agent-specific planning
     * @return A Plan containing sequential steps to accomplish the goal
     */
    suspend fun generate(
        task: Task,
        ideas: List<Idea>,
        agentRole: String,
        availableTools: Set<Tool<*>> = emptySet(),
        seats: List<PlanSeat> = emptyList(),
        relevantKnowledge: List<KnowledgeWithScore> = emptyList(),
        taskFactory: TaskFactory = DefaultTaskFactory,
        customPromptBuilder: ((Task, List<Idea>, Set<Tool<*>>, List<KnowledgeWithScore>) -> String)? = null,
        runId: RunId? = null,
    ): Plan {
        // Handle blank task
        if (task is Task.Blank) {
            return Plan.blank
        }

        val taskDescription = extractTaskDescription(task)
        val ideaSummary = synthesizeIdeas(ideas)
        val knowledgeSummary = synthesizeKnowledge(relevantKnowledge)

        val prompt = customPromptBuilder?.invoke(task, ideas, availableTools, relevantKnowledge)
            ?: buildPlanningPrompt(
                taskDescription = taskDescription,
                ideaSummary = ideaSummary,
                knowledgeSummary = knowledgeSummary,
                agentRole = agentRole,
                availableTools = availableTools,
                seats = seats,
            )

        return try {
            val jsonResponse = llmService.callForJson(
                prompt = prompt,
                systemMessage = PLANNING_SYSTEM_MESSAGE,
                maxTokens = 1000,
                routingContext = RoutingContext(
                    phase = CognitivePhase.PLAN,
                    agentId = llmService.agentId,
                    agentRole = agentRole,
                    workflowId = runId ?: task.id,
                ),
            )
            parsePlanFromResponse(jsonResponse.rawJson, task, taskFactory, availableTools, seats)
        } catch (e: MissingUpstreamLlmClientException) {
            // No transport means no model was reached at all, so there is nothing to degrade
            // to: `createFallbackPlan`'s step nominates no tool, which the executor treats as
            // a successful "reasoning step" and so reports work that no model did. A blank
            // plan executes to `Outcome.blank` instead — not a success — which is what keeps
            // a run with no transport from completing a goal (AMPR-395). It also makes the
            // outcome agree with the telemetry, which already books the attempt as a failed
            // call carrying this exception's name as its `errorType`.
            logger.w(e) { "[PLAN] No upstream transport configured; planning nothing" }
            Plan.blank
        } catch (e: Exception) {
            createFallbackPlan(task, taskFactory, "Plan generation failed: ${e.message}")
        }
    }

    /**
     * Extracts a description string from a task.
     */
    private fun extractTaskDescription(task: Task): String {
        return when (task) {
            is Task.CodeChange -> task.description
            is Task.Step -> task.description
            is Task.Blank -> ""
            else -> "Task ${task.id}"
        }
    }

    /**
     * Synthesizes ideas into a summary for planning context.
     */
    private fun synthesizeIdeas(ideas: List<Idea>): String {
        return if (ideas.isNotEmpty()) {
            ideas.joinToString("\n\n") { idea ->
                "${idea.name}:\n${idea.description}"
            }
        } else {
            "No insights available from perception phase."
        }
    }

    /**
     * Synthesizes relevant knowledge into a summary for planning context.
     */
    private fun synthesizeKnowledge(knowledge: List<KnowledgeWithScore>): String {
        if (knowledge.isEmpty()) {
            return "No relevant past knowledge available."
        }

        return buildString {
            appendLine("Relevant past experiences (${knowledge.size} entries):")
            knowledge.take(5).forEach { scored ->
                appendLine("- Approach: ${scored.knowledge.approach}")
                appendLine("  Learnings: ${scored.knowledge.learnings.take(200)}...")
                appendLine("  Relevance: ${(scored.relevanceScore * 100).toInt()}%")
                appendLine()
            }
        }
    }

    /**
     * Builds the planning prompt.
     */
    private fun buildPlanningPrompt(
        taskDescription: String,
        ideaSummary: String,
        knowledgeSummary: String,
        agentRole: String,
        availableTools: Set<Tool<*>>,
        seats: List<PlanSeat>,
    ): String = buildString {
        appendLine("You are the planning module of an autonomous $agentRole agent.")
        appendLine("Your task is to create a concrete, executable plan to accomplish the given task.")
        appendLine()
        appendLine("Task: $taskDescription")
        appendLine()
        appendLine("Insights from Perception:")
        appendLine(ideaSummary)
        appendLine()
        appendLine("Past Knowledge:")
        appendLine(knowledgeSummary)
        appendLine()

        if (seats.isNotEmpty()) {
            appendSeatRoster(seats, availableTools)
        } else if (availableTools.isNotEmpty()) {
            appendLine("Available Tools:")
            availableTools.forEach { tool ->
                appendLine("- ${tool.id}: ${tool.description}")
            }
            appendLine()
        }

        appendSchemas(availableTools)

        appendLine("Create a step-by-step plan where each step is a concrete task that can be executed.")
        appendLine("Each step should:")
        appendLine("1. Have a clear, actionable description")
        appendLine("2. Specify which tool to use (if applicable)")
        appendLine("3. Be sequentially ordered with clear dependencies")
        appendLine("4. Include validation/verification steps where appropriate")
        if (seats.isNotEmpty()) {
            appendLine("5. Name the seat that runs it in `seat`, using one of the seat names above")
        }
        appendLine()
        appendLine("For simple tasks, create a 1-2 step plan.")
        appendLine("For complex tasks, break down into logical phases (3-5 steps typically).")
        appendLine("Avoid excessive granularity - focus on meaningful phases of work.")
        appendLine()
        appendLine(
            "Set requiresHumanInput to true only when the plan cannot be carried out " +
                "without a decision, an approval, or information that only a person can supply. " +
                "A plan you can execute with the tools and context above sets it to false.",
        )
        appendLine()
        appendLine("Format your response as a JSON object:")
        appendLine(responseSchema(seats = seats, availableTools = availableTools))
        appendLine()
        appendLine("Respond ONLY with the JSON object, no other text.")
    }

    /**
     * The roster block: one line per seat, naming the tools that seat owns.
     *
     * Each seat's tools are rendered with their descriptions under the seat, so
     * the model reads a tool and the seat that can run it in one place and
     * cannot pair a tool with a seat that does not own it by reading down a
     * flat list. A tool that no seat claims is listed under "Unassigned tools":
     * leaving it out would silently narrow the agent's own tool set, and the
     * parser's seat resolution has a defined answer for a step that nominates
     * one (no seat).
     */
    private fun StringBuilder.appendSeatRoster(
        seats: List<PlanSeat>,
        availableTools: Set<Tool<*>>,
    ) {
        val describe = availableTools.associate { it.id to it.description }
        appendLine("Seats and the tools each one owns:")
        seats.forEach { seat ->
            appendLine("- ${seat.seat}:")
            if (seat.toolIds.isEmpty()) {
                appendLine("    (no tools — reasoning steps only)")
            } else {
                seat.toolIds.forEach { toolId ->
                    val description = describe[toolId]
                    appendLine("    - $toolId${description?.let { ": $it" } ?: ""}")
                }
            }
        }

        val claimed = seats.flatMapTo(mutableSetOf()) { it.toolIds }
        val unclaimed = availableTools.filter { it.id !in claimed }
        if (unclaimed.isNotEmpty()) {
            appendLine("Unassigned tools (no seat owns these):")
            unclaimed.forEach { tool ->
                appendLine("- ${tool.id}: ${tool.description}")
            }
        }
        appendLine()
        appendLine("A step may nominate only a tool its own seat owns.")
        appendLine()
    }

    /**
     * The arguments of whichever tools declare a schema (AMPR-410).
     *
     * This is what makes a step's inline `arguments` answerable: the model is
     * shown the fields and asked to fill them in the plan, and
     * `ToolExecutionEngine` then spends no second model call on that step's
     * parameters (AMPR-411). A tool with no schema is not mentioned here —
     * asking for arguments nobody declared would buy output the engine throws
     * away, which is the bill PropelLoop's "everything a phase prompt asks for
     * is parsed" invariant exists to stop.
     *
     * Rendered through [ToolArgumentSchema.describe], the same prose the
     * [SchemaParameterStrategy][link.socket.ampere.agents.execution.SchemaParameterStrategy]
     * prompt uses, rather than by pasting the schema JSON: a model shown a
     * schema and asked for an instance tends to answer with a schema, and two
     * phases asking for the same object should not describe it two ways.
     */
    private fun StringBuilder.appendSchemas(availableTools: Set<Tool<*>>) {
        val described = availableTools.mapNotNull { tool ->
            tool.declaredArgumentSchema()?.let { tool.id to it }
        }
        if (described.isEmpty()) {
            return
        }

        appendLine("Argument schemas. For a step nominating one of these tools, fill the")
        appendLine("step's `arguments` with an object holding exactly these fields:")
        described.forEach { (toolId, schema) ->
            appendLine("$toolId:")
            appendLine(ToolArgumentSchema.describe(schema))
        }
        appendLine()
    }

    /**
     * The response shape, with the `seat` and `arguments` keys present only when
     * something would read them back — a plan with no seats parses no seat, and
     * a toolset with no schemas parses no arguments.
     */
    private fun responseSchema(
        seats: List<PlanSeat>,
        availableTools: Set<Tool<*>>,
    ): String {
        val stepKeys = buildList {
            add("\"description\": \"what this step accomplishes\"")
            add("\"toolToUse\": \"tool ID or null if no specific tool\"")
            if (seats.isNotEmpty()) {
                add("\"seat\": \"${seats.joinToString(" | ") { it.seat }}\"")
            }
            if (availableTools.any { it.declaredArgumentSchema() != null }) {
                add("\"arguments\": { \"<field>\": \"<value>\" }")
            }
            add("\"requiresPreviousStep\": true/false")
        }
        return buildString {
            appendLine("{")
            appendLine("  \"steps\": [")
            appendLine("    {")
            stepKeys.forEachIndexed { index, key ->
                appendLine("      $key${if (index == stepKeys.lastIndex) "" else ","}")
            }
            appendLine("    }")
            appendLine("  ],")
            appendLine("  \"estimatedComplexity\": 1-10,")
            appendLine("  \"requiresHumanInput\": true/false")
            append("}")
        }
    }

    /**
     * Parses the LLM response into a Plan object.
     */
    private fun parsePlanFromResponse(
        jsonResponse: String,
        originalTask: Task,
        taskFactory: TaskFactory,
        availableTools: Set<Tool<*>>,
        seats: List<PlanSeat>,
    ): Plan {
        val cleanedResponse = LLMResponseParser.cleanJsonResponse(jsonResponse)
        val planJson = LLMResponseParser.parseJsonObject(cleanedResponse)

        val stepsArray = planJson["steps"]?.jsonArray
            ?: throw IllegalStateException("No steps in plan")

        val complexity = LLMResponseParser.getInt(planJson, "estimatedComplexity", 5)
        val requiresHumanInput = LLMResponseParser.getBoolean(planJson, "requiresHumanInput", false)

        // Validate steps
        if (stepsArray.isEmpty()) {
            throw IllegalStateException("Plan must contain at least one step")
        }

        // Convert steps into Task objects
        val planTasks = stepsArray.mapIndexed { index, stepElement ->
            val stepObj = stepElement.jsonObject
            val description = stepObj.stringOrNull("description")
                ?: "Step ${index + 1}"
            val toolToUse = stepObj.stringOrNull("toolToUse")
            val seat = seats.resolveSeat(named = stepObj.stringOrNull("seat"), toolId = toolToUse)

            taskFactory.create(
                PlanStepDraft(
                    id = "step-${index + 1}-${originalTask.id}",
                    description = description,
                    toolId = toolToUse,
                    assignedTo = seat?.assignedTo,
                    arguments = stepObj.argumentsFor(toolToUse, availableTools),
                    execution = seat?.execution,
                    originalTask = originalTask,
                ),
            )
        }

        return Plan.ForTask(
            task = originalTask,
            tasks = planTasks,
            estimatedComplexity = complexity,
            expectations = Expectations.blank,
            requiresHumanInput = requiresHumanInput,
        )
    }

    /**
     * Creates a fallback plan when generation fails.
     */
    private fun createFallbackPlan(
        task: Task,
        taskFactory: TaskFactory,
        reason: String,
    ): Plan {
        if (task is Task.Blank) {
            return Plan.blank
        }

        val taskDescription = extractTaskDescription(task)

        val fallbackTask = taskFactory.create(
            id = "step-1-${task.id}",
            description = "Execute: $taskDescription (Note: Advanced planning unavailable - $reason)",
            toolId = null,
            originalTask = task,
        )

        return Plan.ForTask(
            task = task,
            tasks = listOf(fallbackTask),
            estimatedComplexity = 3,
            expectations = Expectations.blank,
            // No planner ran, so no planner asked for a person.
            requiresHumanInput = false,
        )
    }

    /**
     * The string at [key], or null when the key is absent, its value is JSON
     * `null`, or the value is blank or the four characters `null`.
     *
     * `jsonPrimitive.content` cannot be used directly here: [JsonNull] *is* a
     * [JsonPrimitive], and its `content` is the string `"null"`. A step the
     * model wrote as `"toolToUse": null` — the shape the planning prompt asks
     * for — would otherwise nominate a tool literally named `null` and fail
     * the executor's strict tool-id dispatch, rather than reading as the
     * tool-less reasoning step it is.
     */
    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)
            ?.takeUnless { it is JsonNull }
            ?.content
            ?.takeUnless { it.isBlank() || it == "null" }

    /**
     * The seat a step belongs to: one that exists, and one that owns the tool
     * the step nominates (AMPR-410).
     *
     * Those two conditions are the whole contract, and both halves are the
     * planner's to get wrong — it answers with free text, so it can name a seat
     * that is not in the roster, or pair a real seat with a tool another seat
     * owns. The precedence, in order:
     *
     * 1. The seat the step named, when it exists *and* owns the nominated tool
     *    (a step nominating no tool is a reasoning step any seat can run).
     * 2. Otherwise the one seat that owns the nominated tool. This is a
     *    correction rather than an invention: the tool is the executable fact,
     *    and exactly one seat can run it.
     * 3. Otherwise null — no seat. Reached when the roster has no seat for the
     *    tool, when more than one seat owns it, or when a reasoning step named a
     *    seat that does not exist. The step survives unassigned, because a plan
     *    thrown away over one mislabelled step costs the whole PLAN call.
     *
     * Rule 3 is also why a step may still carry a null `assignedTo` with seats
     * declared: the roster-hosted run decides what to do with an unassigned step
     * (AMPR-393), and it can only do that if the step reaches it.
     */
    private fun List<PlanSeat>.resolveSeat(named: String?, toolId: String?): PlanSeat? {
        if (isEmpty()) {
            return null
        }
        val namedSeat = named?.let { name -> firstOrNull { it.seat.equals(name, ignoreCase = true) } }
        if (namedSeat != null && namedSeat.owns(toolId)) {
            return namedSeat
        }
        return toolId?.let { filter { seat -> seat.owns(it) }.singleOrNull() }
    }

    /**
     * The step's inline `arguments`, kept only when the nominated tool publishes
     * a schema for them (AMPR-410).
     *
     * Dropped otherwise, rather than carried on the chance something downstream
     * can use them: with no schema there is nothing to check them against, and a
     * tool whose arguments are a hand-written
     * [ParameterStrategy][link.socket.ampere.agents.execution.ParameterStrategy]'s
     * business was never shown the fields the model would be guessing at. A
     * reasoning step (no tool) has no arguments by construction.
     */
    private fun JsonObject.argumentsFor(
        toolId: String?,
        availableTools: Set<Tool<*>>,
    ): JsonObject? {
        val declaresSchema = toolId != null &&
            availableTools.any { it.id == toolId && it.declaredArgumentSchema() != null }
        return if (declaresSchema) this["arguments"] as? JsonObject else null
    }

    /**
     * The argument schema this tool declares, or null (AMPR-411).
     *
     * Only a [FunctionTool] declares one. An [McpTool][link.socket.ampere.agents.execution.tools.McpTool]
     * publishes an `inputSchema`, and it is deliberately *not* read here: AMPR-411 scoped
     * both the engine's skip and [SchemaParameterStrategy][link.socket.ampere.agents.execution.SchemaParameterStrategy]
     * to `FunctionTool.argumentSchema`, and an MCP call's arguments are the envelope
     * `McpCallArguments` derives from the request's context. Offering an MCP schema here
     * would ask the planner to fill fields no dispatch reads.
     */
    private fun Tool<*>.declaredArgumentSchema(): JsonObject? =
        (this as? FunctionTool<*>)?.argumentSchema

    companion object {
        private const val PLANNING_SYSTEM_MESSAGE =
            "You are an autonomous agent planning system. " +
                "Generate structured execution plans. " +
                "Respond only with valid JSON."
    }
}

/**
 * Everything one parsed plan step says about itself, before it becomes a [Task]
 * (AMPR-410).
 *
 * The planner learned to answer more per step than an id, a description and a
 * tool — a seat, the seat's execution tier, and the tool's arguments inline —
 * and will learn more still. Those arrive here rather than as further
 * parameters on [TaskFactory.create] so that a factory reads the fields it
 * understands and the next field costs no call site a change.
 *
 * @property id Unique identifier for the step.
 * @property description What the step accomplishes.
 * @property toolId The tool the step nominates, or null for a reasoning step.
 * @property assignedTo The seat the step was resolved to, or null when the plan
 *   named no seats.
 * @property arguments The nominated tool's arguments inline, when the planner
 *   was shown that tool's schema and filled it; see
 *   [Task.Step.arguments][link.socket.ampere.agents.domain.task.Task.Step.arguments].
 * @property execution The assigned seat's model and effort, or null.
 * @property originalTask The task being planned for.
 */
data class PlanStepDraft(
    val id: String,
    val description: String,
    val toolId: String? = null,
    val assignedTo: AssignedTo? = null,
    val arguments: JsonElement? = null,
    val execution: ExecutionAssignment? = null,
    val originalTask: Task,
)

/**
 * Factory interface for creating domain-specific task objects.
 *
 * Each agent can provide its own implementation to create the appropriate
 * task types for its domain.
 */
fun interface TaskFactory {
    /**
     * Creates a task from plan step information.
     *
     * @param id Unique identifier for the task
     * @param description What the task accomplishes
     * @param toolId Optional tool to use for this task
     * @param originalTask The original task being planned
     * @return A Task object appropriate for the agent's domain
     */
    fun create(
        id: String,
        description: String,
        toolId: String?,
        originalTask: Task,
    ): Task

    /**
     * Creates a task from everything [draft] says about the step (AMPR-410).
     *
     * This is the overload [PlanGenerator] calls, and the one a factory that
     * cares about the seat or the inline arguments overrides. It is not the
     * abstract member, so a factory written as a lambda — the shape a
     * `fun interface` exists for — keeps compiling and keeps behaving exactly as
     * it did: the default drops the fields it never knew about rather than
     * failing to build.
     */
    fun create(draft: PlanStepDraft): Task = create(
        id = draft.id,
        description = draft.description,
        toolId = draft.toolId,
        originalTask = draft.originalTask,
    )
}

/**
 * The factory the loop uses when a caller names none: `Task.CodeChange` for a
 * code agent's plan, [Task.Step] for everything else (AMPR-410).
 *
 * The discriminator is what the agent was asked to plan *for*, not a flag: a
 * code agent is handed a `Task.CodeChange` goal — by `CodeIssueWorkflow`, by the
 * CLI's `--goal`, by `AmpereRuntime` — and its steps stay code changes so every
 * `when (task)` that reads a description, builds a `MemoryContext` or renders a
 * perception keeps reading it. A planner given anything else (a `Task.Blank`
 * goal, a `PMTask`, a `TicketTask`, a consumer's own) used to get
 * `Task.CodeChange` steps too, which is the thing H11 names: every step was a
 * "code change" whether or not it changed code.
 *
 * [Task.CodeChange] has nowhere to put a seat's inline arguments or execution
 * tier, so a code plan silently drops them. That is the right trade for now —
 * the fields exist for the roster-hosted run, which does not plan code — and it
 * is why [PlanSeat] is not offered to a code agent by anything in the loop.
 */
object DefaultTaskFactory : TaskFactory {
    override fun create(
        id: String,
        description: String,
        toolId: String?,
        originalTask: Task,
    ): Task = create(
        PlanStepDraft(
            id = id,
            description = description,
            toolId = toolId,
            originalTask = originalTask,
        ),
    )

    override fun create(draft: PlanStepDraft): Task = when (val originalTask = draft.originalTask) {
        is Task.CodeChange -> Task.CodeChange(
            id = draft.id,
            status = TaskStatus.Pending,
            description = draft.description,
            // The seat the planner named wins over the goal's own assignee: the
            // goal says who owns the work, a step says who runs this part of it.
            assignedTo = draft.assignedTo ?: originalTask.assignedTo,
            toolId = draft.toolId,
        )
        else -> Task.Step(
            id = draft.id,
            status = TaskStatus.Pending,
            description = draft.description,
            toolId = draft.toolId,
            assignedTo = draft.assignedTo,
            arguments = draft.arguments,
            execution = draft.execution,
        )
    }
}
