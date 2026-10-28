package link.socket.ampere

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.executor.OPENCODE_EXECUTOR_ID
import link.socket.ampere.agents.execution.executor.RoutedExecutor
import link.socket.ampere.agents.execution.tools.ToolHarnessCodeChange
import link.socket.ampere.config.ConfigParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The CLI's harness wiring (AMPR-497): a `harnesses:` section in `ampere.yaml` becomes a
 * [RoutedExecutor] over [FunctionExecutor] on the [AmpereContext], bound to the CLI's workspace,
 * and `ampere status` reports each harness's health.
 *
 * The harness binary is a `sh` stand-in that answers `initialize` the way an ACP agent does —
 * a real process, spawned the way `opencode acp` would be. Every context here is given its own
 * database and no monitored directory, so nothing touches the home directory.
 */
class HarnessWiringTest {

    private val posix = File.separatorChar == '/'

    @Test
    fun `the section routes harness_code_change to the configured harness and nothing else`(
        @TempDir tempDir: File,
    ) = withContext(tempDir, harnessesYaml(fakeAgent(tempDir))) { context ->
        val router = assertIs<RoutedExecutor>(context.executor)

        assertNull(context.harnessError)
        assertEquals(listOf(OPENCODE_EXECUTOR_ID, "fake-acp"), context.harnesses.map { it.executor.id })
        assertEquals(OPENCODE_EXECUTOR_ID, router.executorFor(ToolHarnessCodeChange()).id)
        assertEquals(OPENCODE_EXECUTOR_ID, router.performerFor(ToolHarnessCodeChange()))
        assertEquals("fake-acp", router.performerFor(ToolHarnessCodeChange().copy(id = "scout/harness_code_change")))
        assertEquals(FunctionExecutor.DEFAULT_ID, router.performerFor(ToolHarnessCodeChange().copy(id = "ask_human")))
    }

    @Test
    fun `status reports each harness with its id and binary and health`(@TempDir tempDir: File) {
        if (!posix) return
        val agent = fakeAgent(tempDir)
        val missing = File(tempDir, "no-such-agent").absolutePath
        val yaml = harnessesYaml(agent) + "\n" + """
            |  missing:
            |    kind: acp
            |    binary: $missing
            |    tools: [other_tool]
        """.trimMargin()

        withContext(tempDir, yaml) { context ->
            val statuses = runBlocking { StatusCommand.harnessStatuses(context.harnesses) }
                .associateBy { it.id }

            val openCode = assertNotNull(statuses[OPENCODE_EXECUTOR_ID])
            assertTrue(openCode.available, openCode.describe())
            assertEquals("opencode  ${agent.absolutePath}  available (0.1)  → harness_code_change", openCode.describe())

            val absent = assertNotNull(statuses["missing"])
            assertFalse(absent.available)
            assertEquals(missing, absent.binary)
            assertTrue(absent.health.startsWith("unavailable: "), absent.health)
            assertTrue(absent.health.length > "unavailable: ".length, "the reason is given: ${absent.health}")
        }
    }

    @Test
    fun `a config without the section keeps the function executor itself`(@TempDir tempDir: File) =
        withContext(tempDir, BASE_YAML) { context ->
            assertIs<FunctionExecutor>(context.executor)
            assertTrue(context.harnesses.isEmpty())
            assertNull(context.harnessError)
            assertTrue(runBlocking { StatusCommand.harnessStatuses(context.harnesses) }.isEmpty())
        }

    @Test
    fun `a section that cannot be bound binds nothing and says why`(@TempDir tempDir: File) {
        val yaml = BASE_YAML + """
            |harnesses:
            |  opencode:
            |    environment:
            |      OPENCODE_API_KEY: ${'$'}{AMPR_497_UNSET_VARIABLE}
        """.trimMargin()

        withContext(tempDir, yaml) { context ->
            assertIs<FunctionExecutor>(context.executor)
            assertTrue(context.harnesses.isEmpty())
            val error = assertNotNull(context.harnessError)
            assertTrue("AMPR_497_UNSET_VARIABLE" in error, error)
        }
    }

    @Test
    fun `an environment variable is read from the process environment`(@TempDir tempDir: File) {
        val yaml = BASE_YAML + """
            |harnesses:
            |  opencode:
            |    environment:
            |      OPENCODE_API_KEY: ${'$'}{AMPR_497_KEY}
        """.trimMargin()

        withContext(tempDir, yaml, processEnvironment = mapOf("AMPR_497_KEY" to "from-the-environment")) { context ->
            assertNull(context.harnessError)
            assertEquals(listOf(OPENCODE_EXECUTOR_ID), context.harnesses.map { it.executor.id })
        }
    }

    private fun withContext(
        tempDir: File,
        yaml: String,
        processEnvironment: Map<String, String> = emptyMap(),
        block: (AmpereContext) -> Unit,
    ) {
        val workspace = File(tempDir, "checkout").apply { mkdirs() }
        val context = AmpereContext(
            databasePath = File(tempDir, "test.db").absolutePath,
            workspace = ExecutionWorkspace(baseDirectory = workspace.absolutePath),
            monitoredDirectory = null,
            userConfig = ConfigParser.parse(yaml),
            processEnvironment = processEnvironment,
        )
        try {
            block(context)
        } finally {
            context.close()
        }
    }

    private fun harnessesYaml(agent: File): String = BASE_YAML + """
        |harnesses:
        |  opencode:
        |    binary: ${agent.absolutePath}
        |  fake-acp:
        |    kind: acp
        |    binary: ${agent.absolutePath}
        |    tools: [scout/harness_code_change]
    """.trimMargin()

    /** An ACP agent that answers `initialize` as `fake-acp 0.1` and reads everything else. */
    private fun fakeAgent(directory: File): File {
        val d = "$"
        val body = """
            |#!/bin/sh
            |while IFS= read -r line; do
            |  id=$d(printf '%s' "${d}line" | sed -n 's/^{"jsonrpc":"2.0","id":\([0-9][0-9]*\),.*/\1/p')
            |  case "${d}line" in
            |    *'"method":"initialize"'*)
            |      printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":1,"agentInfo":{"name":"fake-acp","version":"0.1"}}}\n' "${d}id" ;;
            |  esac
            |done
            |
        """.trimMargin()
        val file = File(directory, "fake-acp-agent.sh")
        file.writeText(body)
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        return file
    }

    private companion object {
        val BASE_YAML = """
            |ai:
            |  provider: anthropic
            |  model: sonnet-5
            |team:
            |  - role: engineer
            |
        """.trimMargin()
    }
}
