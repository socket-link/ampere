package link.socket.ampere.agents.execution.tools.planning

import kotlinx.datetime.Clock
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.domain.expectation.Expectations
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.DefaultTaskFactory
import link.socket.ampere.agents.domain.reasoning.LLMResponseParser
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.reasoning.TaskFactory
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.execution.ParameterStrategy
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool

/**
 * The tool id under which `ToolPlanSteps` registers itself.
 *
 * Surfaced as a public constant so agents can reference it when describing
 * the tool in their planning instructions without hard-coding the string.
 */
const val PLAN_STEPS_TOOL_ID: String = "plan_steps"

private const val PLAN_STEPS_NAME = "Plan Steps"
private const val PLAN_STEPS_DESCRIPTION =
    "Decomposes a task into an ordered list of executable steps. " +
        "Each step nominates exactly one tool (or none) so the agent's " +
        "executor can route by id without keyword fallback."

/**
 * Agent-neutral planning tool.
 *
 * The JSON shape and parsing rules live with the tool's [PlanStepsStrategy],
 * not on any agent profile or spark. Every `SparkBasedAgent` includes this
 * tool by default so a freshly-stacked agent already knows how to produce a
 * structured plan — independent of which language, framework, or domain
 * sparks have been layered on top.
 *
 * @param taskFactory how to materialise each plan step into a concrete
 *   [Task] subtype. Defaults to [DefaultTaskFactory] (which emits
 *   `Task.CodeChange`); agents that need a different task shape pass in
 *   their own factory.
 */
fun ToolPlanSteps(
    taskFactory: TaskFactory = DefaultTaskFactory,
    requiredAgentAutonomy: AgentActionAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
): FunctionTool<ExecutionContext.Planning> = FunctionTool(
    id = PLAN_STEPS_TOOL_ID,
    name = PLAN_STEPS_NAME,
    description = PLAN_STEPS_DESCRIPTION,
    requiredAgentAutonomy = requiredAgentAutonomy,
    parameterStrategy = PlanStepsStrategy(taskFactory),
    executionFunction = { request ->
        val startTime = Clock.System.now()
        val context = request.context
        val plan = context.parsedPlan
        if (plan == null) {
            ExecutionOutcome.Planning.Failure(
                executorId = context.executorId,
                ticketId = context.ticket.id,
                taskId = context.task.id,
                executionStartTimestamp = startTime,
                executionEndTimestamp = Clock.System.now(),
                error = ExecutionError(
                    type = ExecutionError.Type.UNEXPECTED,
                    message = "plan_steps was invoked without a parsed plan — " +
                        "the parameter strategy must populate Planning.parsedPlan " +
                        "before execute() runs.",
                ),
            )
        } else {
            ExecutionOutcome.Planning.Success(
                executorId = context.executorId,
                ticketId = context.ticket.id,
                taskId = context.task.id,
                executionStartTimestamp = startTime,
                executionEndTimestamp = Clock.System.now(),
                plan = plan,
            )
        }
    },
)

/**
 * Parameter strategy for the `plan_steps` tool.
 *
 * Owns the planning prompt and the JSON schema that the planning LLM must
 * produce. The schema is **load-bearing**: the executor routes steps by
 * their `toolToUse` id with no keyword fallback, so the strategy emphasises
 * exact tool-id usage and fails fast when the response is malformed.
 */
class PlanStepsStrategy(
    private val taskFactory: TaskFactory = DefaultTaskFactory,
) : ParameterStrategy {

    override val systemMessage: String =
        "You are an autonomous agent planning system. " +
            "Generate structured execution plans. Respond only with valid JSON."

    override val maxTokens: Int = 1000

    override fun buildPrompt(
        tool: Tool<*>,
        request: ExecutionRequest<*>,
        intent: String,
    ): String {
        val context = request.context as? ExecutionContext.Planning
            ?: error(
                "plan_steps requires ExecutionContext.Planning, got " +
                    request.context::class.simpleName,
            )

        return buildString {
            appendLine(
                "You are the planning module of an autonomous ${context.agentRole} agent.",
            )
            appendLine(
                "Your task is to create a concrete, executable plan to accomplish the given task.",
            )
            appendLine()
            appendLine("Task: $intent")
            appendLine()
            appendLine("Insights from Perception:")
            appendLine(context.ideaSummary.ifBlank { "No insights available from perception phase." })
            appendLine()
            appendLine("Past Knowledge:")
            appendLine(context.knowledgeSummary.ifBlank { "No relevant past knowledge available." })
            appendLine()

            if (context.availableToolDescriptors.isNotEmpty()) {
                appendLine("Available Tools:")
                context.availableToolDescriptors.forEach { descriptor ->
                    appendLine("- ${descriptor.id}: ${descriptor.description}")
                }
                appendLine()
            }

            appendLine("Create a step-by-step plan where each step is a concrete task that can be executed.")
            appendLine("Each step must:")
            appendLine("1. Have a clear, actionable description.")
            appendLine(
                "2. Populate `toolToUse` with the exact tool id from the available-tools list, " +
                    "or null when the step is pure reasoning with no tool invocation.",
            )
            appendLine("3. Be sequentially ordered with `requiresPreviousStep` flagging dependencies.")
            appendLine()
            appendLine("For simple tasks, create a 1-2 step plan.")
            appendLine("For complex tasks, break down into logical phases (3-5 steps typically).")
            appendLine("Avoid excessive granularity - focus on meaningful phases of work.")
            appendLine()
            appendLine("Format your response as a JSON object with exactly this shape:")
            appendLine(
                """
{
  "steps": [
    {
      "description": "what this step accomplishes",
      "toolToUse": "<exact tool id from available-tools, or null>",
      "requiresPreviousStep": true/false
    }
  ],
  "estimatedComplexity": 1-10
}
                """.trimIndent(),
            )
            appendLine()
            appendLine(
                "Use only tool ids that appeared in the Available Tools list above. " +
                    "The executor will fail fast on unrecognised ids.",
            )
            appendLine("Respond ONLY with the JSON object, no other text.")
        }
    }

    override fun parseAndEnrichRequest(
        jsonResponse: String,
        originalRequest: ExecutionRequest<*>,
    ): ExecutionRequest<*> {
        val originalContext = originalRequest.context as? ExecutionContext.Planning
            ?: error(
                "plan_steps requires ExecutionContext.Planning, got " +
                    originalRequest.context::class.simpleName,
            )

        val cleaned = LLMResponseParser.cleanJsonResponse(jsonResponse)
        val planJson = LLMResponseParser.parseJsonObject(cleaned)

        val stepsArray = planJson["steps"]?.jsonArray
            ?: error("plan_steps response is missing 'steps' array")
        if (stepsArray.isEmpty()) {
            error("plan_steps response contains an empty 'steps' array")
        }

        val complexity = LLMResponseParser.getInt(planJson, "estimatedComplexity", 5)
        val originalTask = originalContext.task

        val planTasks: List<Task> = stepsArray.mapIndexed { index, element ->
            val stepObj = element.jsonObject
            val description = stepObj["description"]?.jsonPrimitive?.content
                ?: "Step ${index + 1}"
            val toolToUse = stepObj["toolToUse"]?.jsonPrimitive?.content
            taskFactory.create(
                id = "step-${index + 1}-${originalTask.id}",
                description = description,
                toolId = toolToUse,
                originalTask = originalTask,
            )
        }

        val plan: Plan = Plan.ForTask(
            task = originalTask,
            tasks = planTasks,
            estimatedComplexity = complexity,
            expectations = Expectations.blank,
        )

        return ExecutionRequest(
            context = originalContext.copy(parsedPlan = plan),
            constraints = originalRequest.constraints,
        )
    }
}

/**
 * Pure-helper conversion from a set of `Tool<*>` to the serialisable
 * [ExecutionContext.ToolDescriptor] view the planning context expects.
 *
 * Lives in the same file as the planning tool so callers can stage a
 * `Planning` request without depending on planning internals.
 */
fun Iterable<Tool<*>>.toPlanningDescriptors(): List<ExecutionContext.ToolDescriptor> =
    map { tool ->
        ExecutionContext.ToolDescriptor(
            id = tool.id,
            name = tool.name,
            description = tool.description,
        )
    }

/**
 * Coerce a planning [Outcome] back to a [Plan]. Returns [Plan.Blank] when the
 * outcome is anything other than a planning success, which callers can treat
 * as a fail-safe (the agent's loop will fall through to a no-op execution).
 */
fun Outcome.planOrBlank(): Plan = when (this) {
    is ExecutionOutcome.Planning.Success -> plan
    else -> Plan.blank
}
