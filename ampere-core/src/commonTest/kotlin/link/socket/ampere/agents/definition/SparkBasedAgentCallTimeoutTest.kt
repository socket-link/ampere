package link.socket.ampere.agents.definition

import com.aallam.openai.api.chat.ChatChoice
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.memory.AgentMemoryCell
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.llm.UpstreamLlmClient

/**
 * AMPR-456: the timed overloads of [SparkBasedAgent]'s phase methods and `callLLM` bound
 * each model call by the caller's `timeout`, on the caller's clock.
 *
 * Every timing case runs against all five overloads, because each one wraps its own call
 * and a bound that reached four of them would still leave a host cut off at 60 s on the
 * fifth. The twin cases pin the other half of the contract: a timed overload leaves the
 * agent's memory as the unbounded method beside it does, so a host that adopts one gives
 * up nothing the loop relied on.
 *
 * All of it runs on `runTest`'s virtual clock, which is the point: the unbounded methods'
 * 60 s runs inside `runBlockingCompat` on the real one, where a 75 s reply costs 75 s.
 */
class SparkBasedAgentCallTimeoutTest {

    @Test
    fun `a reply at 75 s completes under a 135 s bound`() = runTest {
        val clock = VirtualClockHold(this)
        BOUNDED_CALLS.forEach { call ->
            val transport = DelayedUpstreamLlmClient(clock, replyAfter = 75.seconds, answer = TOOL_ANSWER)
            val agent = agent(transport)

            val started = testScheduler.timeSource.markNow()
            call.make(agent, 135.seconds)

            assertEquals(75.seconds, started.elapsedNow(), "${call.name} returned at the wrong instant")
            assertEquals(1, transport.answered, "${call.name} never received the reply")
        }
    }

    @Test
    fun `a reply at 75 s times out at exactly 60 s under a 60 s bound`() = runTest {
        val clock = VirtualClockHold(this)
        BOUNDED_CALLS.forEach { call ->
            val transport = DelayedUpstreamLlmClient(clock, replyAfter = 75.seconds, answer = TOOL_ANSWER)
            val agent = agent(transport)

            val started = testScheduler.timeSource.markNow()
            assertFailsWith<TimeoutCancellationException>("${call.name} outlived its bound") {
                call.make(agent, 60.seconds)
            }

            assertEquals(60.seconds, started.elapsedNow(), "${call.name} timed out at the wrong instant")
            assertEquals(0, transport.answered, "${call.name} received a reply it should not have")
        }
    }

    @Test
    fun `a reply at 300 s ends at its own 255 s bound`() = runTest {
        val clock = VirtualClockHold(this)
        BOUNDED_CALLS.forEach { call ->
            val transport = DelayedUpstreamLlmClient(clock, replyAfter = 300.seconds, answer = TOOL_ANSWER)
            val agent = agent(transport)

            val started = testScheduler.timeSource.markNow()
            assertFailsWith<TimeoutCancellationException>("${call.name} outlived its bound") {
                call.make(agent, 255.seconds)
            }

            assertEquals(255.seconds, started.elapsedNow(), "${call.name} timed out at the wrong instant")
        }
    }

    @Test
    fun `the timed perceiveState remembers what the unbounded one does`() = runTest {
        val clock = VirtualClockHold(this)
        val bounded = agent(DelayedUpstreamLlmClient(clock, answer = PERCEPTION_ANSWER))
        val unbounded = agent(DelayedUpstreamLlmClient(clock, answer = PERCEPTION_ANSWER))
        val earlier = Idea(name = "earlier", id = "idea-1", description = "what came before")

        val boundedResult = bounded.perceiveState(bounded.getCurrentState(), earlier, timeout = 135.seconds)
        val unboundedResult = unbounded.perceiveState(unbounded.getCurrentState(), earlier)

        assertEquals(unboundedResult.ideas.map(Idea::content), boundedResult.ideas.map(Idea::content))
        // rememberNewPerception: the perception as assembled, before it was evaluated.
        assertEquals(listOf(earlier), bounded.memory().perception.ideas)
        assertEquals(unbounded.memory().perception.ideas, bounded.memory().perception.ideas)
        // rememberNewIdea: what the evaluation concluded.
        assertEquals(unbounded.memory().idea.content(), bounded.memory().idea.content())
        assertEquals(boundedResult.ideas.single(), bounded.memory().idea)
    }

    @Test
    fun `the timed determinePlanForTask remembers what the unbounded one does`() = runTest {
        val clock = VirtualClockHold(this)
        val bounded = agent(DelayedUpstreamLlmClient(clock, answer = TOOL_ANSWER))
        val unbounded = agent(DelayedUpstreamLlmClient(clock, answer = TOOL_ANSWER))

        val boundedPlan = bounded.determinePlanForTask(TASK, timeout = 135.seconds)
        val unboundedPlan = unbounded.determinePlanForTask(TASK)

        // rememberNewPlan. A plan's own id is minted per instance, so the steps are compared.
        assertSame(boundedPlan, bounded.memory().plan)
        assertEquals(unbounded.memory().plan.tasks, bounded.memory().plan.tasks)
        assertEquals(unboundedPlan.estimatedComplexity, boundedPlan.estimatedComplexity)
    }

    @Test
    fun `the timed runTool remembers what the unbounded one does`() = runTest {
        val clock = VirtualClockHold(this)
        val bounded = agent(DelayedUpstreamLlmClient(clock, answer = TOOL_ANSWER))
        val unbounded = agent(DelayedUpstreamLlmClient(clock, answer = TOOL_ANSWER))

        val boundedOutcome = bounded.runTool(SEARCH_TOOL, request(), timeout = 135.seconds)
        unbounded.runTool(SEARCH_TOOL, request())

        // rememberNewOutcome.
        assertSame(boundedOutcome, bounded.memory().outcome)
        val boundedRemembered = assertIs<ExecutionOutcome.NoChanges.Success>(bounded.memory().outcome)
        val unboundedRemembered = assertIs<ExecutionOutcome.NoChanges.Success>(unbounded.memory().outcome)
        assertEquals(unboundedRemembered.message, boundedRemembered.message)
    }

    @Test
    fun `the timed evaluateNextIdeaFromOutcomes remembers what the unbounded one does`() = runTest {
        val clock = VirtualClockHold(this)
        val bounded = agent(DelayedUpstreamLlmClient(clock, answer = TOOL_ANSWER))
        val unbounded = agent(DelayedUpstreamLlmClient(clock, answer = TOOL_ANSWER))

        val boundedIdea = bounded.evaluateNextIdeaFromOutcomes(OUTCOME, timeout = 135.seconds)
        val unboundedIdea = unbounded.evaluateNextIdeaFromOutcomes(OUTCOME)

        // rememberNewIdea.
        assertSame(boundedIdea, bounded.memory().idea)
        assertEquals(unboundedIdea.content(), boundedIdea.content())
    }

    @Test
    fun `the timed callLLM answers what the unbounded one does and remembers nothing`() = runTest {
        val clock = VirtualClockHold(this)
        val bounded = agent(DelayedUpstreamLlmClient(clock, answer = TOOL_ANSWER))
        val unbounded = agent(DelayedUpstreamLlmClient(clock, answer = TOOL_ANSWER))

        val boundedText = bounded.callLLM("reason it through", timeout = 135.seconds)
        val unboundedText = unbounded.callLLM("reason it through")

        assertEquals(unboundedText, boundedText)
        assertEquals(TOOL_ANSWER, boundedText)
        assertEquals(AgentMemoryCell.Current.blank.idea, bounded.memory().idea)
    }

    @Test
    fun `an unnamed bound is still 60 s`() {
        assertEquals(60.seconds, SparkBasedAgent.DEFAULT_LLM_CALL_TIMEOUT)
    }

    private fun agent(transport: UpstreamLlmClient): SparkBasedAgent<CodeState> =
        SparkBasedAgent(
            agentId = "timeout-agent",
            cognitiveAffinity = CognitiveAffinity.ANALYTICAL,
            initialState = CodeState.blank,
            _additionalTools = setOf(SEARCH_TOOL),
            _aiConfiguration = AIConfiguration_Default(
                provider = AIProvider_Anthropic,
                model = AIModel_Claude.Sonnet_5,
            ),
            _upstreamLlmClient = transport,
            // Without an executor `runTool` refuses before any model call, which would leave
            // its bound nothing to bound.
            _executor = FunctionExecutor.create(),
        )

    private fun SparkBasedAgent<CodeState>.memory(): AgentMemoryCell.Current = getCurrentState().getCurrentMemory()

    /** One timed overload, called the way a host calls it. */
    private class BoundedCall(
        val name: String,
        val make: suspend (SparkBasedAgent<CodeState>, Duration) -> Any,
    )

    private companion object {
        /** A schema tool, so `runTool`'s model call is the parameter fill. */
        val SEARCH_TOOL = FunctionTool<ExecutionContext>(
            id = "search",
            name = "Search",
            description = "Searches a mailbox for messages matching a query",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            argumentSchema = buildJsonObject {
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "What to search for")
                    }
                }
                putJsonArray("required") { add("query") }
            },
            executionFunction = { executionRequest ->
                val now = Clock.System.now()
                ExecutionOutcome.NoChanges.Success(
                    executorId = executionRequest.context.executorId,
                    ticketId = executionRequest.context.ticket.id,
                    taskId = executionRequest.context.task.id,
                    executionStartTimestamp = now,
                    executionEndTimestamp = now,
                    message = "searched",
                )
            },
        )

        /** Fills [SEARCH_TOOL]'s parameters; read by every other phase as a reply it falls back from. */
        const val TOOL_ANSWER = """{"query": "quarterly report"}"""

        const val PERCEPTION_ANSWER = """[{"observation": "the report is due", "implication": "find it first"}]"""

        val TASK = Task.CodeChange(
            id = "task-1",
            status = TaskStatus.Pending,
            description = "Find last quarter's report",
        )

        val OUTCOME = ExecutionOutcome.NoChanges.Success(
            executorId = "test-executor",
            ticketId = "ticket-1",
            taskId = TASK.id,
            executionStartTimestamp = Instant.fromEpochSeconds(0),
            executionEndTimestamp = Instant.fromEpochSeconds(1),
            message = "found it",
        )

        val BOUNDED_CALLS = listOf(
            BoundedCall("perceiveState") { agent, timeout ->
                agent.perceiveState(agent.getCurrentState(), timeout = timeout)
            },
            BoundedCall("determinePlanForTask") { agent, timeout ->
                agent.determinePlanForTask(TASK, timeout = timeout)
            },
            BoundedCall("runTool") { agent, timeout ->
                agent.runTool(SEARCH_TOOL, request(), timeout = timeout)
            },
            BoundedCall("evaluateNextIdeaFromOutcomes") { agent, timeout ->
                agent.evaluateNextIdeaFromOutcomes(OUTCOME, timeout = timeout)
            },
            BoundedCall("callLLM") { agent, timeout ->
                agent.callLLM("reason it through", timeout = timeout)
            },
        )

        fun request(): ExecutionRequest<ExecutionContext.NoChanges> {
            val now = Clock.System.now()
            return ExecutionRequest(
                context = ExecutionContext.NoChanges(
                    actorId = "timeout-agent",
                    executorId = "test-executor",
                    ticket = Ticket(
                        id = "ticket-1",
                        title = "Test ticket",
                        description = "Test ticket description",
                        type = TicketType.TASK,
                        priority = TicketPriority.MEDIUM,
                        status = TicketStatus.InProgress,
                        assignedAgentId = "timeout-agent",
                        createdByAgentId = "timeout-agent",
                        createdAt = now,
                        updatedAt = now,
                        dueDate = null,
                    ),
                    task = TASK,
                    instructions = TASK.description,
                ),
            )
        }
    }
}

private fun Idea.content(): Pair<String, String> = name to description

/**
 * Keeps `runTest`'s virtual clock still while a call is away on a dispatcher the test
 * scheduler cannot see.
 *
 * `AgentLLMService` makes the transport call under `withContext(ioDispatcher)`. The moment
 * nothing on the test scheduler is ready to run, `runTest` jumps to the next queued event —
 * and while the call is in transit to or from that dispatcher the only one queued is the
 * call's own timeout, so the scheduler would fire it at once. Held (the initial state), a
 * coroutine here keeps one task ready at the current instant, so the clock moves only while
 * [DelayedUpstreamLlmClient] has released it to wait for its reply. One per test: a second
 * hold would keep the clock still for the first's wait.
 */
private class VirtualClockHold(scope: TestScope) {
    private val held = MutableStateFlow(true)

    /** The test's scheduler, for a wait that has to run on its clock rather than the real one. */
    val dispatcher = StandardTestDispatcher(scope.testScheduler)

    init {
        scope.backgroundScope.launch {
            while (true) {
                held.first { it }
                yield()
            }
        }
    }

    fun hold() {
        held.value = true
    }

    fun release() {
        held.value = false
    }
}

/**
 * A transport that answers [answer] after [replyAfter] of the test's virtual time, or at once
 * when [replyAfter] is zero.
 *
 * The wait is taken back onto the test scheduler, because the call reaches this transport on
 * `ioDispatcher`, where `delay` would run on the real clock. The clock is held again however
 * the wait ends — with the reply or with the caller's timeout — so the way back out to the
 * caller is covered as well.
 */
private class DelayedUpstreamLlmClient(
    private val clock: VirtualClockHold,
    private val replyAfter: Duration = Duration.ZERO,
    private val answer: String,
) : UpstreamLlmClient {

    /** How many replies were delivered: a call cut off at its bound gets none. */
    var answered: Int = 0
        private set

    override suspend fun call(
        request: ChatCompletionRequest,
        configuration: AIConfiguration,
    ): ChatCompletion {
        if (replyAfter > Duration.ZERO) {
            withContext(clock.dispatcher) {
                clock.release()
                try {
                    delay(replyAfter)
                } finally {
                    clock.hold()
                }
            }
        }
        answered++
        return ChatCompletion(
            id = "delayed",
            created = 0L,
            model = ModelId(configuration.model.name),
            choices = listOf(
                ChatChoice(
                    index = 0,
                    message = ChatMessage(role = ChatRole.Assistant, content = answer),
                ),
            ),
        )
    }
}
