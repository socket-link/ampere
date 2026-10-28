package link.socket.ampere.agents.execution.executor

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.execution.executor.acp.HarnessPolicy

/**
 * The OpenCode factory (AMPR-493, D8): the id the record carries, a binary that is not
 * there reported as unavailable with its reason, never thrown, and the ask-mode config the
 * agent is spawned with, without which OpenCode never asks the fence anything (AMPR-504).
 */
class OpenCodeExecutorTest {

    private val posix = File.separatorChar == '/'
    private val root: File = Files.createTempDirectory("opencode-executor-test").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `the factory binds the opencode id and a missing binary is an unavailable health`() = runBlocking<Unit> {
        if (!posix) return@runBlocking
        val executor = AcpCodeExecutor.openCode(
            binary = File(root, "no-such-opencode").absolutePath,
            workspace = ExecutionWorkspace(baseDirectory = root.absolutePath),
            policy = HarnessPolicy(startTimeout = 10.seconds),
        )

        val health = executor.performHealthCheck().getOrThrow()
        executor.close()

        assertEquals(OPENCODE_EXECUTOR_ID, executor.id)
        assertEquals("opencode", executor.id)
        assertEquals("OpenCode (ACP)", executor.displayName)
        assertFalse(health.isAvailable)
        assertTrue(health.issues.isNotEmpty(), "the reason is reported: ${health.status}")
        assertTrue(executor.capabilities.supportsLanguages.isEmpty(), "language-agnostic")
    }

    @Test
    fun `the agent is spawned asking before every edit and shell command with auto-update off`() = runBlocking<Unit> {
        if (!posix) return@runBlocking
        val dump = File(root, "spawn-env.txt")
        val d = "$"
        val scripts = FakeAcpAgentScript(File(root, ".scripts").also { it.mkdirs() }.toPath())
        val variables = "\"${d}OPENCODE_CONFIG_CONTENT\" \"${d}OPENCODE_DISABLE_AUTOUPDATE\""
        val agent = scripts.agent(preamble = "printf '%s\\n%s\\n' $variables > \"${dump.absolutePath}\"")
        val executor = AcpCodeExecutor.openCode(
            binary = agent.absolutePath,
            workspace = ExecutionWorkspace(baseDirectory = root.absolutePath),
            policy = HarnessPolicy(startTimeout = 10.seconds),
        )

        val health = executor.performHealthCheck().getOrThrow()
        executor.close()

        assertTrue(health.isAvailable, "the fake agent answered initialize: ${health.issues}")
        val (config, autoUpdate) = dump.readLines()
        assertEquals(
            Json.parseToJsonElement("""{"permission":{"edit":"ask","bash":"ask"}}"""),
            Json.parseToJsonElement(config),
        )
        assertEquals("1", autoUpdate)
    }

    @Test
    fun `a caller's config is merged over the asks and wins on a colliding key`() {
        val spawned = openCodeEnvironment(
            mapOf(
                "ANTHROPIC_API_KEY" to "k",
                OPENCODE_CONFIG_CONTENT to
                    """{"model":"anthropic/claude-haiku-4-5","permission":{"webfetch":"ask","edit":"allow"}}""",
            ),
        )

        val config = Json.parseToJsonElement(spawned.getValue(OPENCODE_CONFIG_CONTENT)).jsonObject
        assertEquals("anthropic/claude-haiku-4-5", config.getValue("model").jsonPrimitive.content)
        val permission = config.getValue("permission").jsonObject
        assertEquals("allow", permission.getValue("edit").jsonPrimitive.content, "the caller wins")
        assertEquals("ask", permission.getValue("bash").jsonPrimitive.content, "the ask the caller left alone stays")
        assertEquals("ask", permission.getValue("webfetch").jsonPrimitive.content)
        assertEquals("k", spawned["ANTHROPIC_API_KEY"])
        assertEquals("1", spawned["OPENCODE_DISABLE_AUTOUPDATE"])
    }

    @Test
    fun `no caller config spawns exactly the asks`() {
        val spawned = openCodeEnvironment(emptyMap())

        assertEquals(setOf(OPENCODE_CONFIG_CONTENT, "OPENCODE_DISABLE_AUTOUPDATE"), spawned.keys)
        val config: JsonObject = Json.parseToJsonElement(spawned.getValue(OPENCODE_CONFIG_CONTENT)).jsonObject
        assertEquals(OPENCODE_ASK_PERMISSIONS, config)
    }

    @Test
    fun `a caller config that is not a JSON object is refused at construction`() {
        assertFailsWith<IllegalArgumentException> {
            AcpCodeExecutor.openCode(environment = mapOf(OPENCODE_CONFIG_CONTENT to "[1]"))
        }
        assertFailsWith<IllegalArgumentException> {
            AcpCodeExecutor.openCode(environment = mapOf(OPENCODE_CONFIG_CONTENT to "not json"))
        }
    }
}
