package link.socket.ampere.api

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import java.io.File
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.executor.HarnessConfig
import link.socket.ampere.agents.execution.executor.HarnessKind
import link.socket.ampere.agents.execution.executor.HarnessShell
import link.socket.ampere.agents.execution.executor.OPENCODE_EXECUTOR_ID
import link.socket.ampere.agents.execution.executor.RoutedExecutor
import link.socket.ampere.agents.execution.executor.acp.HarnessPolicy
import link.socket.ampere.agents.execution.executor.build
import link.socket.ampere.agents.execution.executor.routedOver
import link.socket.ampere.agents.execution.tools.HARNESS_CODE_CHANGE_TOOL_ID
import link.socket.ampere.agents.execution.tools.ToolHarnessCodeChange

/**
 * The YAML route to a harness (AMPR-497): the `harnesses:` section read, checked, and turned
 * into a [RoutedExecutor] — or into the default executor itself when there is nothing to route.
 */
class AmpereConfigYamlHarnessTest {

    private val root: File = Files.createTempDirectory("harness-yaml-test").toFile()
    private val workspace = ExecutionWorkspace(baseDirectory = root.absolutePath)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private val twoHarnesses = """
        ai:
          provider: anthropic
          model: sonnet-5
        harnesses:
          opencode:
            binary: /opt/homebrew/bin/opencode
            prompt-timeout-seconds: 600
            environment:
              OPENCODE_API_KEY: ${'$'}{OPENCODE_API_KEY}
          my-agent:
            kind: acp
            binary: my-agent
            arguments: [--acp, --quiet]
            shell: allow
            cancel-grace-seconds: 2
            tools: [scout/harness_code_change]
    """.trimIndent()

    @Test
    fun `the section reads one opencode entry and one generic entry with ids filled from their keys`() {
        val harnesses = readFromFile(twoHarnesses)

        assertEquals(2, harnesses.size)
        val (openCode, generic) = harnesses

        assertEquals(HarnessKind.OpenCode, openCode.kind)
        assertEquals(OPENCODE_EXECUTOR_ID, openCode.executorId)
        assertEquals("/opt/homebrew/bin/opencode", openCode.resolvedBinary)
        assertEquals(listOf("acp"), openCode.arguments)
        assertEquals(HarnessShell.Deny, openCode.shell, "shell is denied unless the entry allows it")
        assertEquals(listOf(HARNESS_CODE_CHANGE_TOOL_ID), openCode.tools)
        assertEquals(HarnessPolicy(promptTimeout = 600.seconds), openCode.policy)
        assertEquals(mapOf("OPENCODE_API_KEY" to "\${OPENCODE_API_KEY}"), openCode.environment, "kept unresolved")

        assertEquals(HarnessKind.Acp, generic.kind)
        assertEquals("my-agent", generic.executorId)
        assertEquals(listOf("--acp", "--quiet"), generic.arguments)
        assertEquals(
            HarnessPolicy(shell = HarnessPolicy.ShellAccess.Allow, cancelGrace = 2.seconds),
            generic.policy,
        )
        assertEquals(listOf("scout/harness_code_change"), generic.tools)
    }

    @Test
    fun `the section round-trips through YAML`() {
        val harnesses = decodeHarnesses(twoHarnesses)
        val encoded = yaml.encodeToString(
            MapSerializer(String.serializer(), HarnessConfig.serializer()),
            harnesses.associateBy { checkNotNull(it.executorId) },
        )

        assertEquals(harnesses, decodeHarnesses("harnesses:\n" + encoded.prependIndent("  ")))
    }

    @Test
    fun `a file without the section has no harnesses`() {
        assertEquals(emptyList(), readFromFile("ai:\n  provider: anthropic\n  model: sonnet-5\n"))
    }

    @Test
    fun `two entries binding the same tool id are refused`() {
        val error = assertFailsWith<IllegalArgumentException> {
            decodeHarnesses(
                """
                harnesses:
                  opencode: {}
                  other:
                    kind: acp
                    binary: other-agent
                """.trimIndent(),
            )
        }

        assertTrue("harness_code_change" in error.message.orEmpty(), error.message)
        assertTrue("opencode" in error.message.orEmpty() && "other" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `an entry missing what its kind needs is refused`() {
        val noBinary = assertFailsWith<IllegalArgumentException> {
            decodeHarnesses("harnesses:\n  my-agent:\n    kind: acp\n")
        }
        assertTrue("needs a binary" in noBinary.message.orEmpty(), noBinary.message)

        val renamed = assertFailsWith<IllegalArgumentException> {
            decodeHarnesses("harnesses:\n  oc:\n    id: not-opencode\n")
        }
        assertTrue("recorded as 'opencode'" in renamed.message.orEmpty(), renamed.message)

        val relaunched = assertFailsWith<IllegalArgumentException> {
            decodeHarnesses("harnesses:\n  opencode:\n    arguments: [serve]\n")
        }
        assertTrue("must begin with 'acp'" in relaunched.message.orEmpty(), relaunched.message)
    }

    @Test
    fun `no harnesses routes over the default executor itself`() {
        val default = FunctionExecutor.create()

        assertSame(default, emptyList<HarnessConfig>().routedOver(default, workspace))
    }

    @Test
    fun `each harness is built with its id and bound to the tools it lists`() = runBlocking<Unit> {
        val bound = decodeHarnesses(twoHarnesses).build(workspace, mapOf("OPENCODE_API_KEY" to "secret"))
        try {
            val router = assertIs<RoutedExecutor>(bound.routedOver(FunctionExecutor.create()))

            assertEquals(listOf(OPENCODE_EXECUTOR_ID, "my-agent"), bound.map { it.executor.id })
            assertEquals("my-agent (ACP)", bound[1].executor.displayName)
            assertEquals(OPENCODE_EXECUTOR_ID, router.performerFor(ToolHarnessCodeChange()))
            val askHuman = ToolHarnessCodeChange().copy(id = "ask_human")
            assertEquals(FunctionExecutor.DEFAULT_ID, router.performerFor(askHuman))
            assertSame(
                bound[1].executor,
                router.executorFor(ToolHarnessCodeChange().copy(id = "scout/harness_code_change")),
                "the exact seat-namespaced binding wins over the bare one",
            )
        } finally {
            bound.forEach { it.executor.close() }
        }
    }

    @Test
    fun `an environment variable that is not set is named and its value never shown`() {
        val config = HarnessConfig(environment = mapOf("OPENCODE_API_KEY" to "\${OPENCODE_API_KEY}"))

        val error = assertFailsWith<IllegalArgumentException> {
            config.toExecutor(workspace, processEnvironment = emptyMap())
        }

        assertTrue("OPENCODE_API_KEY" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `a missing binary is an unavailable health with its reason`() = runBlocking<Unit> {
        val executor = HarnessConfig(binary = File(root, "no-such-opencode").absolutePath)
            .toExecutor(workspace)
        try {
            val health = executor.performHealthCheck().getOrThrow()

            assertFalse(health.isAvailable)
            assertTrue(health.issues.isNotEmpty())
            assertNull(executor.agentInfo)
        } finally {
            executor.close()
        }
    }

    private fun readFromFile(content: String): List<HarnessConfig> {
        val file = Files.createTempFile("ampere-config", ".yaml")
        file.writeText(content)
        return try {
            harnessesFromYaml(file.toString())
        } finally {
            file.deleteIfExists()
        }
    }

    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))
}
