package link.socket.ampere.agents.execution.dispatch

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

/**
 * A Switchboard in its own JVM, so `SwitchboardShutdownProcessTest` can send it a real
 * SIGTERM in the middle of a dispatch.
 *
 * Usage: `java -cp <test classpath> …SwitchboardProcess <dir>`
 *
 * Dispatches one ticket to an agent that sleeps, in `--watch` mode, with the shutdown
 * hook the CLI installs. Every settlement is appended to `<dir>/settled.txt`, because
 * the fake work source's record of writes dies with this process.
 */
object SwitchboardProcess {

    const val INSTANCE: String = "switchboard-process"

    @JvmStatic
    fun main(args: Array<String>) {
        val root = Path.of(args[0])
        val repo = GitFixture.repository(root)
        val settled = root.resolve("settled.txt")

        val source = FakeDispatchWorkSource()
        source.add(FakeDispatchWorkSource.ticket("AMPR-1"))

        val switchboard = Switchboard(
            config = SwitchboardConfig(
                wave = "w1",
                logDirectory = root.resolve("logs"),
                pollInterval = 1.seconds,
                shutdownGrace = 2.seconds,
            ),
            workSource = source,
            journal = DispatchJournal.open(root.resolve("journal"), INSTANCE),
            worktrees = WorktreeProvisioner(repository = repo, root = root.resolve("worktrees"), base = "main"),
            launcher = AgentLauncher(AgentLaunchSpec(listOf("/bin/sh", "-c", "echo started; exec sleep 600"))),
            listener = object : SwitchboardListener {
                override fun onSettled(ticketId: String, phase: DispatchPhase?, detail: String) {
                    Files.writeString(
                        settled,
                        "$ticketId ${phase?.name ?: "RELEASED"}\n",
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND,
                    )
                }
            },
        )

        SwitchboardShutdownHook.install(switchboard, timeout = 60.seconds)
        runBlocking { switchboard.run(watch = true) }
    }
}
