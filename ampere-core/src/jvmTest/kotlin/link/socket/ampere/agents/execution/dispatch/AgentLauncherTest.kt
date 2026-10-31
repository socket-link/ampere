package link.socket.ampere.agents.execution.dispatch

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import link.socket.ampere.agents.execution.process.CancellationAddress
import link.socket.ampere.agents.execution.process.ProcessGroups

/**
 * The launch gate and the worktree pin (AMPR-308, task 3).
 */
class AgentLauncherTest {

    private val posix = File.separatorChar == '/'
    private val temporary = mutableListOf<Path>()
    private val spawned = mutableListOf<CancellationAddress>()

    @AfterTest
    fun tearDown() {
        spawned.forEach { ProcessGroups.terminate(it, grace = 100.milliseconds) }
        temporary.forEach { it.toFile().deleteRecursively() }
    }

    private fun tempDir(): Path = Files.createTempDirectory("ampere-launcher").toRealPath().also { temporary.add(it) }

    private fun gated(dir: Path): GatedAgent {
        val launcher = AgentLauncher(
            AgentLaunchSpec(
                command = listOf("/bin/sh", "-c", "touch \"\$PROBE/ran\""),
                environment = mapOf("PROBE" to dir.toString()),
            ),
        )
        val launch = AgentLaunch(
            ticketId = "AMPR-1",
            worktree = dir,
            contextPath = ".context/attachments/linear-1/[LINEAR]-AMPR-1.md",
            model = ModelChoice("sonnet", "low"),
        )
        return launcher.startGated(launch).also { spawned += it.grouped.address }
    }

    @Test
    fun `an agent whose gate is closed without release never runs`() {
        if (!posix) return
        val dir = tempDir()
        val agent = gated(dir)
        assertNotNull(agent.grouped.address.processGroupId, "the agent must lead its own group")

        // What a supervisor's death does to the gate: its stdin closes.
        agent.grouped.process.outputStream.close()

        assertTrue(agent.grouped.process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(GatedAgent.ABANDONED_EXIT, agent.grouped.process.exitValue())
        assertFalse(Files.exists(dir.resolve("ran")), "an unreleased agent must not have done anything")
    }

    @Test
    fun `a released agent runs in place of its gate`() {
        if (!posix) return
        val dir = tempDir()
        val agent = gated(dir)

        agent.release()

        assertTrue(agent.grouped.process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(0, agent.grouped.process.exitValue())
        assertTrue(Files.exists(dir.resolve("ran")))
    }

    @Test
    fun `a worktree whose Ampere config keeps the workspace inside it is pinned`() {
        val worktree = tempDir()
        assertNull(AgentLauncher.checkPinned(worktree), "no config at all falls back to the working directory")

        Files.writeString(worktree.resolve("ampere.yaml"), "workspace: .\nai:\n  provider: anthropic\n")
        assertNull(AgentLauncher.checkPinned(worktree))

        Files.writeString(worktree.resolve("ampere.yaml"), "workspace: build/agent\n")
        assertNull(AgentLauncher.checkPinned(worktree))
    }

    @Test
    fun `a worktree whose Ampere config points the workspace elsewhere is refused`() {
        val worktree = tempDir()
        Files.createDirectories(worktree.resolve(".ampere"))

        Files.writeString(worktree.resolve(".ampere/config.yaml"), "workspace: /tmp/elsewhere\n")
        assertNotNull(AgentLauncher.checkPinned(worktree))

        Files.writeString(worktree.resolve(".ampere/config.yaml"), "workspace: ~/.ampere/Workspaces/Ampere\n")
        assertNotNull(AgentLauncher.checkPinned(worktree), "the core default workspace is the AMPR-300 escape")

        Files.writeString(worktree.resolve(".ampere/config.yaml"), "workspace: ../sibling\n")
        assertNotNull(AgentLauncher.checkPinned(worktree))
    }

    @Test
    fun `the launch is pinned to the worktree whatever the supervisor's own directory`() {
        val worktree = tempDir()
        val launcher = AgentLauncher(AgentLaunchSpec.claudeCode())
        val builder = launcher.builder(
            AgentLaunch(
                ticketId = "AMPR-1",
                worktree = worktree,
                contextPath = ".context/attachments/linear-1/[LINEAR]-AMPR-1.md",
                model = ModelChoice("opus", "high"),
            ),
        )

        assertEquals(worktree.toFile(), builder.directory())
        assertEquals(worktree.toString(), builder.environment()["PWD"])
        val command = builder.command()
        assertEquals("opus", command[command.indexOf("--model") + 1])
        assertEquals("high", command[command.indexOf("--effort") + 1])
        assertEquals(
            "The user has attached these files. Read them before proceeding.\n" +
                "- .context/attachments/linear-1/[LINEAR]-AMPR-1.md",
            command.last(),
            "the kickoff is the attachment line and nothing else",
        )
    }

    @Test
    fun `a repair run's prompt attaches the verification report after the ticket, and nothing else`() {
        val launcher = AgentLauncher(AgentLaunchSpec.claudeCode())
        val prompt = launcher.prompt(
            AgentLaunch(
                ticketId = "AMPR-1",
                worktree = Path.of("/tmp/worktree"),
                contextPath = ".context/attachments/linear-1/[LINEAR]-AMPR-1.md",
                model = ModelChoice("opus", "high"),
                attachments = listOf(".context/verification/attempt-1.md"),
            ),
        )

        assertEquals(
            "The user has attached these files. Read them before proceeding.\n" +
                "- .context/attachments/linear-1/[LINEAR]-AMPR-1.md\n" +
                "- .context/verification/attempt-1.md",
            prompt,
        )
    }
}
