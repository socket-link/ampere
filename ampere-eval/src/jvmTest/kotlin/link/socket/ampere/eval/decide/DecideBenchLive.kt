package link.socket.ampere.eval.decide

import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import io.ktor.client.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.api.model.TokenUsage
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.llm.BundledUpstreamLlmClient
import link.socket.ampere.llm.UpstreamCompletion
import link.socket.ampere.llm.UpstreamLlmClient
import link.socket.ampere.llm.decide.DecisionModel
import link.socket.ampere.llm.decide.DecisionModels
import link.socket.ampere.llm.decide.DecisionRequest
import link.socket.ampere.llm.decide.DecisionResponse
import link.socket.ampere.llm.decide.DecisionRoute
import link.socket.ampere.llm.decide.DecisionRoutes
import link.socket.ampere.llm.decide.HostedSystemOneDecisionClient
import link.socket.ampere.llm.decide.ModelBackedDecisionClient
import link.socket.ampere.llm.decide.Question
import link.socket.ampere.llm.decide.UpstreamDecisionClient

/**
 * The live goal-met run (AMPR-422 tasks 4 and 5): every hosted arm, through the adapter as
 * shipped, over the committed corpus, three repeats, two question forms, recorded to
 * `recorded/<arm>.jsonl` so [GoalMetRecordedBenchTest] can replay it offline.
 *
 * ```
 * OPENROUTER_API_KEY=… CLOUDFLARE_ACCOUNT_ID=… CLOUDFLARE_AUTH_TOKEN=… \
 *   ./gradlew :ampere-eval:decideBenchLive
 * ./gradlew :ampere-eval:decideBenchLive -PdecideDryRun=true        # print one request, send nothing
 * ./gradlew :ampere-eval:decideBenchLive -PdecideArms=claude-haiku-4-5.noul -PdecideLimit=5 -PdecideParallelism=8
 * ```
 *
 * Nothing here runs unless the task set [GoalMetCorpus.DIR_PROPERTY]; CI never does. Running
 * the task is the explicit approval for calls to these endpoints and no others (the ticket's
 * constraint), and an arm whose credential is absent from the environment is skipped and
 * named, never faked.
 *
 * ### The arms
 *
 * | arm | adapter | credential |
 * | -- | -- | -- |
 * | `claude-sonnet-5`, `claude-haiku-4-5` | [ModelBackedDecisionClient] over [BundledUpstreamLlmClient] |
 *   the generative provider's key, as the bundled client reads it |
 * | `jev-1.13` | [HostedSystemOneDecisionClient] via OpenRouter | `OPENROUTER_API_KEY` |
 * | `clef`, `clef-flash` | [HostedSystemOneDecisionClient] on Workers AI | `CLOUDFLARE_ACCOUNT_ID`,
 *   `CLOUDFLARE_AUTH_TOKEN` |
 *
 * Each in both [GoalMetForm]s, so ten arm ids: `<model>.<form>`.
 *
 * ### What is wrapped and what is not
 *
 * The adapters are untouched: the hosted client POSTs the body `HostedSystemOneDecisionClient`
 * builds, and the model-backed client renders the prompt `ModelBackedDecisionClient` renders.
 * Two things sit *around* them, neither of which changes a request:
 *
 * - [MeteringChatTransport] wraps the chat transport under the model-backed adapter so the
 *   tokens the provider reported reach the record. The model-backed adapter itself reports no
 *   usage on its response (it books usage on telemetry events), and a bench has no event door.
 * - [Retrying] retries a call that failed for a transient reason — rate limit, overload,
 *   timeout — up to twice. A malformed answer is never retried: it is a finding.
 */
internal object DecideBenchLive {

    /** `-PdecideDryRun=true`: print the request for one case and send nothing. */
    const val DRY_RUN_PROPERTY: String = "ampere.eval.decide.dryRun"

    /** `-PdecideArms=a,b`: run only these arm ids. */
    const val ARMS_PROPERTY: String = "ampere.eval.decide.arms"

    /** `-PdecideLimit=n`: run only the first n cases (a smoke run). */
    const val LIMIT_PROPERTY: String = "ampere.eval.decide.limit"

    /** `-PdecideRepeats=n`: override the three repeats. */
    const val REPEATS_PROPERTY: String = "ampere.eval.decide.repeats"

    /** `-PdecideParallelism=n`: calls in flight per arm. */
    const val PARALLELISM_PROPERTY: String = "ampere.eval.decide.parallelism"

    const val DEFAULT_REPEATS: Int = 3

    /** Why the model-backed arms are skipped when the bundled client has no key. */
    const val MISSING_GENERATIVE_KEY: String = "the generative provider's key (local.properties)"
    const val PARALLELISM: Int = 4
    const val QUESTION_ID: String = "goal_met"

    /** Where a run's records go, under the corpus directory. */
    const val RECORDED_DIR: String = "recorded"

    /** A price per million tokens, for the cost line of the report. */
    data class Price(val inputUsdPerMillion: Double, val outputUsdPerMillion: Double, val source: String)

    /**
     * Prices for the arms that report tokens but no cost, from this repository's own catalog
     * (`ampere-core/src/commonMain/composeResources/files/provider_pricing.v1.json`,
     * `publishedAt` 2026-09-20). The hosted System One arms report `cost` on the wire and need
     * no entry.
     */
    val PRICES: Map<String, Price> = mapOf(
        AIModel_Claude.Sonnet_5.name to Price(2.0, 10.0, "provider_pricing.v1.json, 2026-09-20"),
        AIModel_Claude.Haiku_4_5.name to Price(1.0, 5.0, "provider_pricing.v1.json, 2026-09-20"),
    )

    /** One arm the run could bind, and why it might not. */
    class ArmSpec(
        val model: String,
        val form: GoalMetForm,
        val bind: () -> UpstreamDecisionClient?,
        val missingCredential: String?,
    ) {
        val id: String get() = "$model.${form.wireName}"
    }

    /** Everything a run reads from outside: the environment, and the two transports a test can stub. */
    class Settings(
        val outputDirectory: Path?,
        val env: (String) -> String? = System::getenv,
        val httpClient: HttpClient = HttpClient(),
        val chatTransport: UpstreamLlmClient = BundledUpstreamLlmClient,
        val dryRun: Boolean = false,
        val arms: Set<String>? = null,
        val limit: Int? = null,
        val repeats: Int = DEFAULT_REPEATS,
        val parallelism: Int = PARALLELISM,
        val log: (String) -> Unit = ::println,
    )

    /** The settings the Gradle task's system properties describe. */
    fun settingsFromProperties(): Settings = Settings(
        outputDirectory = GoalMetCorpus.outputDirectory(),
        dryRun = System.getProperty(DRY_RUN_PROPERTY)?.toBooleanStrictOrNull() ?: false,
        arms = System.getProperty(ARMS_PROPERTY)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet(),
        limit = System.getProperty(LIMIT_PROPERTY)?.toIntOrNull(),
        repeats = System.getProperty(REPEATS_PROPERTY)?.toIntOrNull() ?: DEFAULT_REPEATS,
        parallelism = System.getProperty(PARALLELISM_PROPERTY)?.toIntOrNull() ?: PARALLELISM,
    )

    private val configuration: AIConfiguration = AIConfiguration_Default(AIProvider_Anthropic, AIModel_Claude.Sonnet_5)

    /** Every arm the run knows, bound lazily so naming one costs nothing. */
    fun armSpecs(settings: Settings): List<ArmSpec> {
        val anthropicKey = AIProvider_Anthropic.apiToken.takeIf { it.isNotBlank() && it != "placeholder" }
        val openRouterKey = settings.env("OPENROUTER_API_KEY")?.takeIf { it.isNotBlank() }
        val cloudflareAccount = settings.env("CLOUDFLARE_ACCOUNT_ID")?.takeIf { it.isNotBlank() }
        val cloudflareToken = settings.env("CLOUDFLARE_AUTH_TOKEN")?.takeIf { it.isNotBlank() }

        val specs = mutableListOf<ArmSpec>()
        for (form in GoalMetForm.entries) {
            for (model in listOf(AIModel_Claude.Sonnet_5, AIModel_Claude.Haiku_4_5)) {
                specs += ArmSpec(
                    model = model.name,
                    form = form,
                    bind = {
                        anthropicKey?.let {
                            val service = AgentLLMService(
                                agentConfiguration = AgentConfiguration(
                                    agentDefinition = WriteCodeAgent,
                                    aiConfiguration = AIConfiguration_Default(AIProvider_Anthropic, model),
                                    upstreamLlmClient = MeteringChatTransport(settings.chatTransport),
                                ),
                            )
                            val price = PRICES.getValue(model.name)
                            Retrying(MeteredDecisionClient(ModelBackedDecisionClient(service), price))
                        }
                    },
                    missingCredential = if (anthropicKey == null) MISSING_GENERATIVE_KEY else null,
                )
            }
            specs += hosted(
                model = DecisionModels.Jev_1_13,
                label = "jev-1.13",
                form = form,
                route = { DecisionRoutes.jevViaOpenRouter() },
                credential = openRouterKey,
                missing = "OPENROUTER_API_KEY",
                settings = settings,
            )
            specs += hosted(
                model = DecisionModels.Clef,
                label = "clef",
                form = form,
                route = { DecisionRoutes.clefOnWorkersAi(cloudflareAccount.orEmpty()) },
                credential = cloudflareToken?.takeIf { cloudflareAccount != null },
                missing = "CLOUDFLARE_ACCOUNT_ID and CLOUDFLARE_AUTH_TOKEN",
                settings = settings,
            )
            specs += hosted(
                model = DecisionModels.ClefFlash,
                label = "clef-flash",
                form = form,
                route = { DecisionRoutes.clefOnWorkersAi(cloudflareAccount.orEmpty(), flash = true) },
                credential = cloudflareToken?.takeIf { cloudflareAccount != null },
                missing = "CLOUDFLARE_ACCOUNT_ID and CLOUDFLARE_AUTH_TOKEN",
                settings = settings,
            )
        }
        return specs
    }

    private fun hosted(
        model: DecisionModel,
        label: String,
        form: GoalMetForm,
        route: () -> DecisionRoute,
        credential: String?,
        missing: String,
        settings: Settings,
    ): ArmSpec = ArmSpec(
        model = label,
        form = form,
        bind = {
            credential?.let { Retrying(HostedSystemOneDecisionClient.of(model, route(), it, settings.httpClient)) }
        },
        missingCredential = if (credential == null) missing else null,
    )

    /** What one run did, for the task's log and the test's assertions. */
    data class Outcome(val ran: List<String>, val skipped: Map<String, String>, val dryRun: Boolean)

    /**
     * Run it. Returns without touching a transport when [Settings.outputDirectory] is null or
     * [Settings.dryRun] is set.
     */
    suspend fun run(settings: Settings): Outcome {
        val directory = settings.outputDirectory
        if (directory == null) {
            settings.log(
                "[decide-bench-live] skipped: -D${GoalMetCorpus.DIR_PROPERTY} is not set. " +
                    "Run ./gradlew :ampere-eval:decideBenchLive to record the live arms.",
            )
            return Outcome(ran = emptyList(), skipped = emptyMap(), dryRun = false)
        }
        val corpus = GoalMetCorpus.load().let { all -> settings.limit?.let { all.take(it) } ?: all }
        val specs = armSpecs(settings).filter { settings.arms == null || it.id in settings.arms }
        require(specs.isNotEmpty()) { "No arm matches ${settings.arms}; known: ${armSpecs(settings).map { it.id }}" }

        if (settings.dryRun) {
            val case = corpus.first()
            settings.log("[decide-bench-live] dry run: nothing is sent. The first case, in each form:")
            for (form in GoalMetForm.entries) {
                settings.log("--- ${form.wireName}: System One body (what HostedSystemOneDecisionClient POSTs) ---")
                settings.log(systemOneBody(form.of(case), wireModel = "<route's model id>"))
            }
            settings.log(
                "--- model-backed arms render ModelBackedDecisionClient.renderPrompt over the same state " +
                    "and question; the prompt is the shipped one and is not reproduced here ---",
            )
            val skipped = specs.filter { it.missingCredential != null }.associate { it.id to it.missingCredential!! }
            val runnable = specs.filter { it.missingCredential == null }.map { it.id }
            settings.log("[decide-bench-live] arms that would run: $runnable")
            settings.log("[decide-bench-live] arms that would be skipped: $skipped")
            return Outcome(ran = emptyList(), skipped = skipped, dryRun = true)
        }

        val ran = mutableListOf<String>()
        val skipped = mutableMapOf<String, String>()
        val recordedDirectory = directory.resolve(RECORDED_DIR)
        Files.createDirectories(recordedDirectory)
        for (spec in specs) {
            val client = spec.bind()
            if (client == null) {
                skipped[spec.id] = spec.missingCredential ?: "could not bind"
                settings.log("[decide-bench-live] ${spec.id}: skipped, missing ${spec.missingCredential}")
                continue
            }
            val cases = corpus.map(spec.form::of)
            val file = recordedDirectory.resolve("${spec.id}.jsonl")
            Files.deleteIfExists(file)
            settings.log(
                "[decide-bench-live] ${spec.id}: ${cases.size} case(s) × ${settings.repeats} repeat(s) -> $file",
            )
            var done = 0
            val report = DecisionBench(QUESTION_ID).run(
                cases = cases,
                arms = listOf(DecisionArm(spec.id, client, configuration)),
                repeats = settings.repeats,
                positiveKeys = spec.form.positiveKeys,
                parallelism = settings.parallelism,
                onRecord = { record ->
                    synchronized(file) {
                        Files.writeString(
                            file,
                            DecisionCaseJson.encodeToString(DecisionRecord.serializer(), record) + "\n",
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND,
                        )
                        done++
                        if (done % 100 == 0) settings.log("[decide-bench-live] ${spec.id}: $done")
                    }
                },
            )
            // Rewrite in a stable order: the file is committed, and a parallel run appends out of order.
            val order = cases.withIndex().associate { (index, case) -> case.id to index }
            val ordered = report.records.sortedWith(compareBy({ it.repeat }, { order.getValue(it.caseId) }))
            Files.writeString(file, DecisionRecord.encodeLines(ordered))
            val confusion = report.confusion(spec.id)
            settings.log(
                "[decide-bench-live] ${spec.id}: false met ${confusion.falseMet}/${confusion.unmetCases}, " +
                    "false not met ${confusion.falseNotMet}/${confusion.metCases}, missing ${confusion.missing}",
            )
            ran += spec.id
        }
        return Outcome(ran = ran, skipped = skipped, dryRun = false)
    }

    /** The System One request body for [case], as the hosted adapter would POST it. */
    fun systemOneBody(case: DecisionCase, wireModel: String): String {
        val body = buildJsonObject {
            put("model", wireModel)
            put("state", case.state)
            put(
                "questions",
                DecisionCaseJson.encodeToJsonElement(
                    MapSerializer(String.serializer(), Question.serializer()),
                    mapOf(QUESTION_ID to case.question),
                ),
            )
        }
        return DecisionCaseJson.encodeToString(JsonObject.serializer(), body)
    }

    /**
     * Carries the tokens a chat call reported up to the decision client that caused it. Lives
     * on the coroutine context, which `AgentLLMService`'s `withContext(ioDispatcher)` keeps.
     */
    class UsageBox : AbstractCoroutineContextElement(UsageBox) {
        companion object Key : CoroutineContext.Key<UsageBox>

        var inputTokens: Int = 0
        var outputTokens: Int = 0
        var calls: Int = 0
    }

    /** A chat transport that reports what the inner one's completions said about tokens. */
    class MeteringChatTransport(private val inner: UpstreamLlmClient) : UpstreamLlmClient {
        override suspend fun call(request: ChatCompletionRequest, configuration: AIConfiguration): ChatCompletion =
            callDetailed(request, configuration).completion

        override suspend fun callDetailed(
            request: ChatCompletionRequest,
            configuration: AIConfiguration,
        ): UpstreamCompletion {
            val upstream = inner.callDetailed(request, configuration)
            coroutineContext[UsageBox]?.let { box ->
                box.calls++
                upstream.completion.usage?.let { usage ->
                    box.inputTokens += usage.promptTokens ?: 0
                    box.outputTokens += usage.completionTokens ?: 0
                }
            }
            return upstream
        }
    }

    /** Puts the metered tokens, priced by [price], on the decision response. */
    class MeteredDecisionClient(
        private val inner: UpstreamDecisionClient,
        private val price: Price,
    ) : UpstreamDecisionClient {
        override suspend fun decide(request: DecisionRequest, configuration: AIConfiguration): DecisionResponse {
            val box = UsageBox()
            val response = withContext(box) { inner.decide(request, configuration) }
            if (box.calls == 0) return response
            val cost = box.inputTokens * price.inputUsdPerMillion / 1_000_000 +
                box.outputTokens * price.outputUsdPerMillion / 1_000_000
            return response.copy(
                usage = TokenUsage(
                    inputTokens = box.inputTokens,
                    outputTokens = box.outputTokens,
                    estimatedCost = cost,
                ),
            )
        }
    }

    /** Retries transient transport failures; never a malformed answer. */
    class Retrying(
        private val inner: UpstreamDecisionClient,
        private val attempts: Int = 3,
        private val backoffMs: Long = 2_000,
    ) : UpstreamDecisionClient {
        override suspend fun decide(request: DecisionRequest, configuration: AIConfiguration): DecisionResponse {
            var attempt = 0
            while (true) {
                attempt++
                try {
                    return inner.decide(request, configuration)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    if (attempt >= attempts || !failure.isTransient()) throw failure
                    delay(backoffMs * attempt)
                }
            }
        }

        private fun Throwable.isTransient(): Boolean {
            val text = (message ?: "") + (cause?.message ?: "")
            return TRANSIENT.any { it in text.lowercase() }
        }

        private companion object {
            val TRANSIENT = listOf(
                "429", "502", "503", "504", "overloaded", "rate limit", "timeout", "timed out", "reset",
            )
        }
    }
}
