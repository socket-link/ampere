package link.socket.ampere.llm

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.aallam.openai.api.chat.ChatChoice
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.core.Usage
import com.aallam.openai.api.model.ModelId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.config.CognitiveConfig
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.ProviderCallCompletedEvent
import link.socket.ampere.agents.domain.event.ProviderCallStartedEvent
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.agents.domain.routing.RoutingContext
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI
import link.socket.ampere.domain.llm.LlmProvider

/**
 * AMPR-242: cancelling an in-flight LLM call must still settle a cost record.
 *
 * These assert against the persisted event store rather than the event bus, because the
 * defect being guarded is precisely that no row was ever *written* — a bus-only assertion
 * could pass on an emission that never reached SQLDelight.
 */
class AgentLlmCancellationTelemetryTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var eventRepository: EventRepository
    private lateinit var eventApi: AgentEventApi

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(driver)

        eventRepository = EventRepository(DEFAULT_JSON, scope, database)
        eventApi = AgentEventApi(
            agentId = "cancellation-agent",
            eventRepository = eventRepository,
            eventSerialBus = EventSerialBus(scope),
        )
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `cancelling an in-flight upstream call still persists start and completion rows`() = runBlocking {
        val client = HangingUpstreamLlmClient()
        val llmService = AgentLLMService(
            agentConfiguration = upstreamConfig(),
            eventApi = eventApi,
            upstreamLlmClient = client,
        )

        val job = scope.launch { llmService.call(prompt = PROMPT, systemMessage = SYSTEM_MESSAGE) }
        client.entered.await()
        job.cancelAndJoin()

        val events = persistedEvents()
        assertEquals(1, events.filterIsInstance<ProviderCallStartedEvent>().size)

        val completed = events.filterIsInstance<ProviderCallCompletedEvent>().single()
        assertFalse(completed.success)
        assertEquals(AgentLLMService.CANCELLED_ERROR_TYPE, completed.errorType)
    }

    @Test
    fun `a cancelled call books the input tokens it sent rather than zero`() = runBlocking {
        val client = HangingUpstreamLlmClient()
        val llmService = AgentLLMService(
            agentConfiguration = upstreamConfig(),
            eventApi = eventApi,
            upstreamLlmClient = client,
        )

        val job = scope.launch { llmService.call(prompt = PROMPT, systemMessage = SYSTEM_MESSAGE) }
        client.entered.await()
        job.cancelAndJoin()

        val completed = persistedEvents().filterIsInstance<ProviderCallCompletedEvent>().single()
        val inputTokens = assertNotNull(completed.usage.inputTokens)

        assertTrue(inputTokens > 0, "cancelled call booked $inputTokens input tokens")
        assertEquals(0, completed.usage.outputTokens)
        // Zero output is what makes the cost computable at all: the pricing calculator
        // returns null the moment either count is null, which would re-zero the record.
        assertTrue(assertNotNull(completed.usage.estimatedCost) > 0.0)
    }

    @Test
    fun `cancellation after the provider answered books the reported actuals`() = runBlocking {
        val callerJob = Job()
        val client = SelfCancellingUpstreamLlmClient { callerJob }
        val llmService = AgentLLMService(
            agentConfiguration = upstreamConfig(),
            eventApi = eventApi,
            upstreamLlmClient = client,
        )

        val job = scope.launch(callerJob) { llmService.call(prompt = PROMPT, systemMessage = SYSTEM_MESSAGE) }
        job.join()

        val completed = persistedEvents().filterIsInstance<ProviderCallCompletedEvent>().single()
        assertEquals(AgentLLMService.CANCELLED_ERROR_TYPE, completed.errorType)
        assertEquals(REPORTED_PROMPT_TOKENS, completed.usage.inputTokens)
        assertEquals(REPORTED_COMPLETION_TOKENS, completed.usage.outputTokens)
    }

    @Test
    fun `cancelling a custom provider call persists a cancelled completion row`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val provider: LlmProvider = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val llmService = AgentLLMService(
            agentConfiguration = upstreamConfig(customProvider = provider),
            eventApi = eventApi,
        )

        val job = scope.launch {
            llmService.call(
                prompt = PROMPT,
                systemMessage = SYSTEM_MESSAGE,
                routingContext = RoutingContext(
                    phase = CognitivePhase.EXECUTE,
                    agentId = eventApi.agentId,
                    agentRole = "Cancellation Test Agent",
                    workflowId = "wf-cancel",
                ),
            )
        }
        entered.await()
        job.cancelAndJoin()

        val completed = persistedEvents().filterIsInstance<ProviderCallCompletedEvent>().single()
        assertFalse(completed.success)
        assertEquals(AgentLLMService.CANCELLED_ERROR_TYPE, completed.errorType)
        assertEquals("wf-cancel", completed.workflowId)
    }

    @Test
    fun `a completed call is unaffected by the cancellation handling`() = runBlocking {
        val llmService = AgentLLMService(
            agentConfiguration = upstreamConfig(),
            eventApi = eventApi,
            upstreamLlmClient = SelfCancellingUpstreamLlmClient(cancelTarget = null),
        )

        val response = llmService.call(prompt = PROMPT, systemMessage = SYSTEM_MESSAGE)

        val completed = persistedEvents().filterIsInstance<ProviderCallCompletedEvent>().single()
        assertEquals(RESPONSE, response)
        assertTrue(completed.success)
        assertEquals(null, completed.errorType)
        assertEquals(REPORTED_PROMPT_TOKENS, completed.usage.inputTokens)
        assertEquals(REPORTED_COMPLETION_TOKENS, completed.usage.outputTokens)
    }

    private suspend fun persistedEvents(): List<Event> =
        eventRepository.getAllEvents().getOrThrow()

    private fun upstreamConfig(customProvider: LlmProvider? = null): AgentConfiguration =
        AgentConfiguration(
            agentDefinition = WriteCodeAgent,
            aiConfiguration = AIConfiguration_Default(
                provider = AIProvider_OpenAI,
                model = AIModel_OpenAI.GPT_4_1,
            ),
            cognitiveConfig = CognitiveConfig(),
            llmProvider = customProvider,
        )

    /** Never answers, so cancellation always lands before any provider-reported usage exists. */
    private class HangingUpstreamLlmClient : UpstreamLlmClient {
        val entered = CompletableDeferred<Unit>()

        override suspend fun call(
            request: ChatCompletionRequest,
            configuration: AIConfiguration,
        ): ChatCompletion {
            entered.complete(Unit)
            awaitCancellation()
        }
    }

    /**
     * Answers with real usage and cancels [cancelTarget] on the way out, reproducing the
     * window where the tokens were genuinely spent but cancellation beat the settle.
     */
    private class SelfCancellingUpstreamLlmClient(
        private val cancelTarget: (() -> Job)?,
    ) : UpstreamLlmClient {
        override suspend fun call(
            request: ChatCompletionRequest,
            configuration: AIConfiguration,
        ): ChatCompletion {
            cancelTarget?.invoke()?.cancel()
            return ChatCompletion(
                id = "completion",
                created = 0L,
                model = ModelId(configuration.model.name),
                choices = listOf(
                    ChatChoice(
                        index = 0,
                        message = ChatMessage(role = ChatRole.Assistant, content = RESPONSE),
                    ),
                ),
                usage = Usage(
                    promptTokens = REPORTED_PROMPT_TOKENS,
                    completionTokens = REPORTED_COMPLETION_TOKENS,
                    totalTokens = REPORTED_PROMPT_TOKENS + REPORTED_COMPLETION_TOKENS,
                ),
            )
        }
    }

    companion object {
        private const val PROMPT = "Summarize the attached specification in three bullets."
        private const val SYSTEM_MESSAGE = "You are a precise technical summarizer."
        private const val RESPONSE = "Summarized."
        private const val REPORTED_PROMPT_TOKENS = 1_234
        private const val REPORTED_COMPLETION_TOKENS = 56
    }
}
