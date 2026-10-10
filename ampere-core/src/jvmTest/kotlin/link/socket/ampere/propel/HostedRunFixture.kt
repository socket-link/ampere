package link.socket.ampere.propel

import com.aallam.openai.api.chat.ChatChoice
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.llm.UpstreamLlmClient

/**
 * A counting fake [link.socket.ampere.llm.UpstreamLlmClient] (AMPR-393).
 *
 * `UpstreamLlmClient` is the only model seam a [HostedAgent] has — the
 * `AgentConfiguration.llmProvider` short-circuit is not on it, because a run builds
 * its own agents — so this is both the way to canned answers and the way to count
 * what a run billed for. [prompts] is every call that reached it, in order, which is
 * what makes "the deterministic LEARN makes no model call" an assertion rather than a
 * claim.
 *
 * [answer] picks the reply from the prompt. One seat, one client: a seat whose only
 * job is reasoning steps answers the same text every time, while the host's has to
 * tell PERCEIVE's question from PLAN's.
 */
class CountingUpstreamLlmClient(
    private val answer: (String) -> String,
) : UpstreamLlmClient {

    val prompts: MutableList<String> = CopyOnWriteArrayList()

    val callCount: Int get() = prompts.size

    override suspend fun call(
        request: ChatCompletionRequest,
        configuration: AIConfiguration,
    ): ChatCompletion {
        val prompt = request.messages.joinToString("\n") { it.content.orEmpty() }
        prompts += prompt
        return ChatCompletion(
            id = generateUUID("fake-completion"),
            created = 0L,
            model = ModelId(configuration.model.name),
            choices = listOf(
                ChatChoice(
                    index = 0,
                    message = ChatMessage(role = ChatRole.Assistant, content = answer(prompt)),
                ),
            ),
            usage = null,
        )
    }
}

/**
 * The host seat's answers: an insight for PERCEIVE, [steps] for PLAN.
 *
 * Told apart by what the prompt says about itself — `PerceptionEvaluator` opens with
 * "You are the perception module", `PlanGenerator` asks for a `steps` array — rather
 * than by call order, so a test that adds a cycle does not have to re-count.
 */
fun hostAnswers(steps: () -> String): (String) -> String = { prompt ->
    if (prompt.contains("perception module")) PERCEPTION_JSON else steps()
}

/**
 * What `PerceptionEvaluator` parses into one
 * [link.socket.ampere.agents.domain.reasoning.Idea]: a JSON *array* of insights, which
 * is the shape its own prompt asks for.
 */
const val PERCEPTION_JSON: String = """
[
  {
    "observation": "the goal is stated and the seats are declared",
    "implication": "the host can plan over them",
    "confidence": "high"
  }
]
"""

/** One plan step, as `PlanGenerator` parses it. */
fun planStep(description: String, toolToUse: String?, seat: String?): String = buildString {
    append("{")
    append("\"description\": \"$description\", ")
    append("\"toolToUse\": ${toolToUse?.let { "\"$it\"" } ?: "null"}, ")
    append("\"seat\": ${seat?.let { "\"$it\"" } ?: "null"}, ")
    append("\"requiresPreviousStep\": false")
    append("}")
}

/** A plan, as `PlanGenerator` parses it. An empty [steps] is a plan it refuses. */
fun planJson(vararg steps: String): String = """
{
  "steps": [${steps.joinToString(",")}],
  "estimatedComplexity": ${steps.size},
  "requiresHumanInput": false
}
"""

/** A tool that records what it was dispatched with and succeeds, with no parameter strategy. */
fun recordingTool(
    id: String,
    result: String,
    dispatched: MutableList<ExecutionRequest<*>>,
): FunctionTool<ExecutionContext.NoChanges> = FunctionTool(
    id = id,
    name = "Recording $id",
    description = "records its request and succeeds",
    requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
    executionFunction = { request ->
        dispatched += request
        val now = Clock.System.now()
        ExecutionOutcome.NoChanges.Success(
            executorId = request.context.executorId,
            ticketId = request.context.ticket.id,
            taskId = request.context.task.id,
            executionStartTimestamp = now,
            executionEndTimestamp = now,
            message = result,
        )
    },
)

/** The goal a hosted-run test opens over. */
fun goal(description: String, id: String = "goal-under-test"): Task.Step = Task.Step(
    id = id,
    status = TaskStatus.Pending,
    description = description,
)
