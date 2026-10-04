package link.socket.ampere.agents.execution.dispatch

import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock

/**
 * A supervisor that does nothing but append, so `DispatchJournalKillTest` has
 * something real to SIGKILL in the middle of a write.
 *
 * It has to be a separate process: the point is that the writer dies without
 * running any cleanup, and a kill inside the test JVM would take the test with it.
 *
 * Usage: `java -cp <test classpath> …JournalWriterProcess <dir> <instanceId> <payloadBytes>`
 *
 * Appends until it is killed. The payload pads [DispatchRecord.worktreePath] so that
 * each whole-file rewrite is large enough for the kill to land inside one.
 */
object JournalWriterProcess {

    @JvmStatic
    fun main(args: Array<String>) {
        val directory = Path.of(args[0])
        val instanceId = args[1]
        val padding = "w".repeat(args[2].toInt())

        runBlocking {
            val journal = DispatchJournal.open(directory, instanceId)
            var next = 0
            while (true) {
                journal.append(
                    DispatchRecord(
                        ticketId = "AMPR-${next++}",
                        supervisorInstanceId = instanceId,
                        worktreePath = "/tmp/worktrees/$padding",
                        branchName = "miley/ampr-$next",
                        phase = DispatchPhase.CLAIMED,
                        recordedAt = Clock.System.now(),
                    ),
                )
            }
        }
    }
}
