package link.socket.ampere.eval.decide

import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.eval.decide.DecideBenchLive.MeteredDecisionClient
import link.socket.ampere.eval.decide.DecideBenchLive.MeteringChatTransport
import link.socket.ampere.eval.decide.DecideBenchLive.Retrying
import link.socket.ampere.eval.decide.DecideBenchLive.Settings
import link.socket.ampere.llm.UpstreamLlmClient
import link.socket.ampere.llm.decide.DecisionModel
import link.socket.ampere.llm.decide.DecisionModels
import link.socket.ampere.llm.decide.DecisionRoute
import link.socket.ampere.llm.decide.DecisionRoutes
import link.socket.ampere.llm.decide.HostedSystemOneDecisionClient
import link.socket.ampere.llm.decide.ModelBackedDecisionClient
import link.socket.ampere.llm.decide.Question
import link.socket.ampere.llm.decide.UpstreamDecisionClient

/**
 * The live perception-drift run (AMPR-482 task 3): every hosted arm, through the adapter as
 * shipped, over the committed corpus, three repeats, two question forms, recorded to
 * `recordings/<arm>.jsonl` so [PerceptionDriftRecordedBenchTest] can replay it offline.
 *
 * ```
 * OPENROUTER_API_KEY=… CLOUDFLARE_ACCOUNT_ID=… CLOUDFLARE_AUTH_TOKEN=… \
 *   ./gradlew :ampere-eval:perceptionDriftBenchLive
 * ./gradlew :ampere-eval:perceptionDriftBenchLive -PdecideDryRun=true        # print one request, send nothing
 * ./gradlew :ampere-eval:perceptionDriftBenchLive -PdecideArms=jev-1.13.noul -PdecideLimit=5
 * ```
 *
 * Nothing here runs unless the task set [PerceptionDriftCorpus.DIR_PROPERTY]; CI never does.
 * Running the task is the explicit approval for calls to these endpoints and no others, and an
 * arm whose credential is absent from the environment is skipped and named, never faked.
 *
 * ### The arms
 *
 * | arm | adapter | credential |
 * | -- | -- | -- |
 * | `claude-sonnet-5` | [ModelBackedDecisionClient] over the bundled chat client | the generative provider's key |
 * | `jev-1.13` | [HostedSystemOneDecisionClient] via OpenRouter | `OPENROUTER_API_KEY` |
 * | `clef`, `clef-flash` | [HostedSystemOneDecisionClient] on Workers AI | `CLOUDFLARE_ACCOUNT_ID`,
 *   `CLOUDFLARE_AUTH_TOKEN` |
 *
 * Each in both [PerceptionDriftForm]s, so eight arm ids: `<model>.<form>`.
 *
 * The adapters are untouched, and the two wrappers around them — [MeteringChatTransport] so the
 * tokens the provider reported reach the record, [Retrying] on a transient transport failure
 * and never on a malformed answer — are the goal-met run's own ([DecideBenchLive]), as are the
 * [Settings] and the Gradle property names.
 */
internal object PerceptionDriftBenchLive {

    const val DEFAULT_REPEATS: Int = DecideBenchLive.DEFAULT_REPEATS
    const val PARALLELISM: Int = DecideBenchLive.PARALLELISM

    /** The id the question is asked under: the run's own. */
    const val QUESTION_ID: String = "perception_drift"

    /** Where a run's records go, under the corpus directory. */
    const val RECORDED_DIR: String = "recordings"

    /** One arm the run could bind, and why it might not. */
    class ArmSpec(
        val model: String,
        val form: PerceptionDriftForm,
        val bind: () -> UpstreamDecisionClient?,
        val missingCredential: String?,
    ) {
        val id: String get() = "$model.${form.wireName}"
    }

    /** The settings the Gradle task's system properties describe. */
    fun settingsFromProperties(): Settings = Settings(
        outputDirectory = PerceptionDriftCorpus.outputDirectory(),
        dryRun = System.getProperty(DecideBenchLive.DRY_RUN_PROPERTY)?.toBooleanStrictOrNull() ?: false,
        arms = System.getProperty(DecideBenchLive.ARMS_PROPERTY)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet(),
        limit = System.getProperty(DecideBenchLive.LIMIT_PROPERTY)?.toIntOrNull(),
        repeats = System.getProperty(DecideBenchLive.REPEATS_PROPERTY)?.toIntOrNull() ?: DEFAULT_REPEATS,
        parallelism = System.getProperty(DecideBenchLive.PARALLELISM_PROPERTY)?.toIntOrNull() ?: PARALLELISM,
    )

    private val configuration: AIConfiguration = AIConfiguration_Default(AIProvider_Anthropic, AIModel_Claude.Sonnet_5)

    /** Every arm the run knows, bound lazily so naming one costs nothing. */
    fun armSpecs(settings: Settings): List<ArmSpec> {
        val anthropicKey = AIProvider_Anthropic.apiToken.takeIf { it.isNotBlank() && it != "placeholder" }
        val openRouterKey = settings.env("OPENROUTER_API_KEY")?.takeIf { it.isNotBlank() }
        val cloudflareAccount = settings.env("CLOUDFLARE_ACCOUNT_ID")?.takeIf { it.isNotBlank() }
        val cloudflareToken = settings.env("CLOUDFLARE_AUTH_TOKEN")?.takeIf { it.isNotBlank() }

        val specs = mutableListOf<ArmSpec>()
        for (form in PerceptionDriftForm.entries) {
            val model = AIModel_Claude.Sonnet_5
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
                        val price = DecideBenchLive.PRICES.getValue(model.name)
                        Retrying(MeteredDecisionClient(ModelBackedDecisionClient(service), price))
                    }
                },
                missingCredential = if (anthropicKey == null) DecideBenchLive.MISSING_GENERATIVE_KEY else null,
            )
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
        form: PerceptionDriftForm,
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
                "[perception-drift-live] skipped: -D${PerceptionDriftCorpus.DIR_PROPERTY} is not set. " +
                    "Run ./gradlew :ampere-eval:perceptionDriftBenchLive to record the live arms.",
            )
            return Outcome(ran = emptyList(), skipped = emptyMap(), dryRun = false)
        }
        val corpus = PerceptionDriftCorpus.load().let { all -> settings.limit?.let { all.take(it) } ?: all }
        val specs = armSpecs(settings).filter { settings.arms == null || it.id in settings.arms }
        require(specs.isNotEmpty()) { "No arm matches ${settings.arms}; known: ${armSpecs(settings).map { it.id }}" }

        if (settings.dryRun) {
            val case = corpus.first()
            settings.log("[perception-drift-live] dry run: nothing is sent. The first case, in each form:")
            for (form in PerceptionDriftForm.entries) {
                settings.log("--- ${form.wireName}: System One body (what HostedSystemOneDecisionClient POSTs) ---")
                settings.log(systemOneBody(form.of(case), wireModel = "<route's model id>"))
            }
            settings.log(
                "--- model-backed arms render ModelBackedDecisionClient.renderPrompt over the same state " +
                    "and question; the prompt is the shipped one and is not reproduced here ---",
            )
            val skipped = specs.filter { it.missingCredential != null }.associate { it.id to it.missingCredential!! }
            val runnable = specs.filter { it.missingCredential == null }.map { it.id }
            settings.log("[perception-drift-live] arms that would run: $runnable")
            settings.log("[perception-drift-live] arms that would be skipped: $skipped")
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
                settings.log("[perception-drift-live] ${spec.id}: skipped, missing ${spec.missingCredential}")
                continue
            }
            val cases = corpus.map(spec.form::of)
            val file = recordedDirectory.resolve("${spec.id}.jsonl")
            Files.deleteIfExists(file)
            settings.log(
                "[perception-drift-live] ${spec.id}: ${cases.size} case(s) × ${settings.repeats} repeat(s) -> $file",
            )
            var done = 0
            val report = DecisionBench(QUESTION_ID).run(
                cases = cases,
                arms = listOf(DecisionArm(spec.id, client, configuration)),
                repeats = settings.repeats,
                positiveKeys = spec.form.settledKeys,
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
                        if (done % 100 == 0) settings.log("[perception-drift-live] ${spec.id}: $done")
                    }
                },
            )
            // Rewrite in a stable order: the file is committed, and a parallel run appends out of order.
            val order = cases.withIndex().associate { (index, case) -> case.id to index }
            val ordered = report.records.sortedWith(compareBy({ it.repeat }, { order.getValue(it.caseId) }))
            Files.writeString(file, DecisionRecord.encodeLines(ordered))
            val confusion = PerceptionDriftReport.confusion(report, spec.id)
            settings.log(
                "[perception-drift-live] ${spec.id}: " +
                    "false settled ${confusion.falseSettled}/${confusion.bearsCases}, " +
                    "false moved ${confusion.falseMoved}/${confusion.besideCases}, missing ${confusion.missing}",
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
}

/**
 * AMPR-482 task 3 validation, and the live run itself.
 *
 * The first three tests are offline and run in plain `jvmTest`: with the directory property
 * unset the runner touches no transport, a dry run prints the request it would send, and the
 * arm list is the ticket's. The last is the live run; it does nothing unless
 * `perceptionDriftBenchLive` set the property.
 */
class PerceptionDriftBenchLiveTest {

    private class CountingTransport : UpstreamLlmClient {
        var calls = 0
        override suspend fun call(request: ChatCompletionRequest, configuration: AIConfiguration): ChatCompletion {
            calls++
            fail("the chat transport must not be called")
        }
    }

    @Test
    fun `with the property unset the runner makes no network call`() = runBlocking {
        var posts = 0
        val http = HttpClient(MockEngine { posts++; respond("{}", HttpStatusCode.OK) })
        val chat = CountingTransport()
        val env = mapOf("OPENROUTER_API_KEY" to "k", "CLOUDFLARE_ACCOUNT_ID" to "a", "CLOUDFLARE_AUTH_TOKEN" to "t")
        val lines = mutableListOf<String>()

        val outcome = PerceptionDriftBenchLive.run(
            Settings(outputDirectory = null, env = env::get, httpClient = http, chatTransport = chat, log = lines::add),
        )

        assertEquals(0, posts)
        assertEquals(0, chat.calls)
        assertTrue(outcome.ran.isEmpty())
        assertTrue(lines.single().contains("skipped"), lines.toString())
    }

    @Test
    fun `a dry run prints the request it would send and sends nothing`() = runBlocking {
        var posts = 0
        val http = HttpClient(MockEngine { posts++; respond("{}", HttpStatusCode.OK) })
        val chat = CountingTransport()
        val lines = mutableListOf<String>()
        val env = mapOf("OPENROUTER_API_KEY" to "k")

        val outcome = PerceptionDriftBenchLive.run(
            Settings(
                outputDirectory = kotlin.io.path.createTempDirectory("perception-drift-dry-run"),
                env = env::get,
                httpClient = http,
                chatTransport = chat,
                dryRun = true,
                log = lines::add,
            ),
        )

        assertEquals(0, posts)
        assertEquals(0, chat.calls)
        assertTrue(outcome.dryRun)
        val printed = lines.joinToString("\n")
        assertTrue("\"state\":" in printed, printed)
        assertTrue("perceived" in printed, "the drift state is what is sent: $printed")
        assertTrue("\"noul\"" in printed && "\"choice\"" in printed, "both forms are printed: $printed")
        assertTrue("\"cannot_tell\"" in printed, "the choice form carries its third option: $printed")
        assertTrue("jev-1.13.noul" in printed, "OpenRouter arms would run: $printed")
        assertTrue("CLOUDFLARE" in printed, "Cloudflare arms are named as skipped: $printed")
        assertTrue(outcome.skipped.keys.any { it.startsWith("clef") })
    }

    @Test
    fun `every arm id is a model and a form and the request names the shipped question`() {
        val specs = PerceptionDriftBenchLive.armSpecs(Settings(outputDirectory = null, env = { null }))
        assertEquals(8, specs.size)
        assertEquals(setOf("claude-sonnet-5", "jev-1.13", "clef", "clef-flash"), specs.map { it.model }.toSet())
        assertTrue(specs.all { it.id.endsWith(".noul") || it.id.endsWith(".choice") })
        val first = PerceptionDriftCorpus.load().first()
        val body = PerceptionDriftBenchLive.systemOneBody(first, wireModel = "typesafe/jev-1.13")
        assertTrue("\"model\":\"typesafe/jev-1.13\"" in body)
        assertTrue("\"perception_drift\":{\"type\":\"noul\"" in body, body)
    }

    /** The live run. `./gradlew :ampere-eval:perceptionDriftBenchLive`. */
    @Test
    fun `records every live arm when asked`() = runBlocking {
        val outcome = PerceptionDriftBenchLive.run(PerceptionDriftBenchLive.settingsFromProperties())
        if (outcome.ran.isNotEmpty() || outcome.skipped.isNotEmpty()) {
            println("[perception-drift-live] ran ${outcome.ran}; skipped ${outcome.skipped}")
        }
    }
}
