package link.socket.ampere.eval.decide

import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.llm.UpstreamLlmClient

/**
 * AMPR-422 task 4 validation, and the live run itself.
 *
 * The first two tests are offline and run in plain `jvmTest`: with the directory property
 * unset the runner touches no transport, and a dry run prints the request it would send. The
 * last is the live run; it does nothing unless `decideBenchLive` set the property.
 */
class DecideBenchLiveTest {

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
        val http = HttpClient(
            MockEngine {
                posts++
                respond("{}", HttpStatusCode.OK)
            },
        )
        val chat = CountingTransport()
        val env = mapOf("OPENROUTER_API_KEY" to "k", "CLOUDFLARE_ACCOUNT_ID" to "a", "CLOUDFLARE_AUTH_TOKEN" to "t")
        val lines = mutableListOf<String>()

        val outcome = DecideBenchLive.run(
            DecideBenchLive.Settings(
                outputDirectory = null,
                env = env::get,
                httpClient = http,
                chatTransport = chat,
                log = lines::add,
            ),
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

        val outcome = DecideBenchLive.run(
            DecideBenchLive.Settings(
                outputDirectory = kotlin.io.path.createTempDirectory("decide-dry-run"),
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
        assertTrue("Find the latest version of the quarterly budget review" in printed, printed)
        assertTrue("\"noul\"" in printed && "\"choice\"" in printed, "both forms are printed: $printed")
        assertTrue("jev-1.13.noul" in printed, "OpenRouter arms would run: $printed")
        assertTrue("CLOUDFLARE" in printed, "Cloudflare arms are named as skipped: $printed")
        assertTrue(outcome.skipped.keys.any { it.startsWith("clef") })
    }

    @Test
    fun `every arm id is a model and a form and the request names the shipped question`() {
        val specs = DecideBenchLive.armSpecs(DecideBenchLive.Settings(outputDirectory = null, env = { null }))
        assertEquals(10, specs.size)
        assertEquals(
            setOf("claude-sonnet-5", "claude-haiku-4-5", "jev-1.13", "clef", "clef-flash"),
            specs.map { it.model }.toSet(),
        )
        assertTrue(specs.all { it.id.endsWith(".noul") || it.id.endsWith(".choice") })
        val body = DecideBenchLive.systemOneBody(GoalMetCorpus.load().first(), wireModel = "typesafe/jev-1.13")
        assertTrue("\"model\":\"typesafe/jev-1.13\"" in body)
        assertTrue("\"goal_met\":{\"type\":\"noul\"" in body, body)
    }

    /** The live run. `./gradlew :ampere-eval:decideBenchLive`. */
    @Test
    fun `records every live arm when asked`() = runBlocking {
        val outcome = DecideBenchLive.run(DecideBenchLive.settingsFromProperties())
        if (outcome.ran.isNotEmpty() || outcome.skipped.isNotEmpty()) {
            println("[decide-bench-live] ran ${outcome.ran}; skipped ${outcome.skipped}")
        }
    }
}
