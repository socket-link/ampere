package link.socket.ampere.agents.execution.dispatch

import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import link.socket.ampere.agents.execution.process.CancellationAddress
import link.socket.ampere.agents.execution.process.GroupedProcess
import link.socket.ampere.agents.execution.process.ProcessGroups
import link.socket.ampere.agents.execution.process.TerminationOutcome

/**
 * How one agent run ended.
 *
 * @property exitCode The agent's exit status, or null when none could be read.
 * @property termination What the silence rule's stop did, when it fired.
 */
data class AgentRun(
    val stop: AgentStop,
    val exitCode: Int?,
    val termination: TerminationOutcome? = null,
)

/**
 * Watches one agent run until it stops (AMPR-308, Context §6).
 *
 * The operator's rule is to leave a run alone until it stops, with exactly one
 * automated kill: a step producing no output for ten minutes (operator answer 9 on
 * AMPR-288: "only kill trigger is a Gradle build hanging 10+ minutes"). This class is
 * that rule and nothing else. It never inspects what the agent says, never nudges it
 * and never retries it.
 *
 * ### What "no output" measures
 *
 * The agent's own stdout and stderr, byte by byte — partial lines count. A streaming
 * agent writes a line per message and tool call, so ten silent minutes means one step
 * has run for ten minutes without finishing, which in practice is a build or a
 * verification command that hung. That is why the default launch asks for a
 * streaming format ([AgentLaunchSpec.claudeCode]): the rule is only as good as the
 * stream it watches.
 *
 * ### When the run is over
 *
 * When the agent itself — the group's leader — exits. Not when its group empties: a
 * build daemon the agent started may outlive it in the group, and normal completion
 * leaves the group alone (CancellationAddress). Not when the spawn wrapper exits
 * either: a terminal's Ctrl+C reaches the wrapper, which shares the supervisor's
 * group, while the agent in its own group runs on.
 *
 * Everything the agent writes goes to a log file for a person to read afterwards.
 */
class RunSupervisor(
    val silenceLimit: Duration = DEFAULT_SILENCE_LIMIT,
    private val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    private val grace: Duration = ProcessGroups.DEFAULT_GRACE,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {

    init {
        require(silenceLimit.isPositive()) { "The silence limit must be positive" }
        require(pollInterval.isPositive()) { "The poll interval must be positive" }
    }

    /**
     * Watch [agent] until its leader exits, killing its group if it goes silent for
     * the limit. [log] receives every byte it writes.
     *
     * @param stopping Whether the supervisor is shutting down. A run that ends while it
     *   is reads as [AgentStop.SHUTDOWN]: the shutdown path terminated it.
     */
    suspend fun supervise(agent: GroupedProcess, log: Path, stopping: () -> Boolean): AgentRun {
        val lastOutput = AtomicReference(timeSource.markNow())
        // A daemon thread rather than a coroutine: a descendant that outlives the agent
        // can hold the pipe open, and a read blocked on it cannot be relied on to wake
        // when the stream is closed. A coroutine stuck there would hold this run open;
        // a daemon thread is simply left behind.
        val reader = thread(isDaemon = true, name = "switchboard-output-${agent.address.leaderPid}") {
            pump(agent.process.inputStream, log, lastOutput)
        }
        var termination: TerminationOutcome? = null

        try {
            while (leaderAlive(agent.address)) {
                if (lastOutput.get().elapsedNow() >= silenceLimit) {
                    termination = withContext(Dispatchers.IO) { ProcessGroups.terminate(agent.address, grace) }
                    break
                }
                delay(pollInterval)
            }
        } catch (e: CancellationException) {
            // Nothing cancels a supervision in the Switchboard; if something does, the run
            // must not be left behind without a watcher.
            withContext(NonCancellable + Dispatchers.IO) { agent.terminate(grace) }
            throw e
        }

        val exitCode = withContext(Dispatchers.IO) {
            val exited = agent.process.waitFor(grace.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            reader.join(DRAIN_TIMEOUT.inWholeMilliseconds)
            if (reader.isAlive) runCatching { agent.process.inputStream.close() }
            if (exited) agent.process.exitValue() else null
        }

        return AgentRun(
            stop = when {
                termination != null -> AgentStop.SILENCED
                stopping() -> AgentStop.SHUTDOWN
                else -> AgentStop.EXITED
            },
            exitCode = exitCode,
            termination = termination,
        )
    }

    private fun pump(input: InputStream, log: Path, lastOutput: AtomicReference<TimeMark>) {
        Files.createDirectories(log.parent)
        Files.newOutputStream(log, StandardOpenOption.CREATE, StandardOpenOption.APPEND).use { out ->
            val buffer = ByteArray(BUFFER_BYTES)
            try {
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    lastOutput.set(timeSource.markNow())
                    out.write(buffer, 0, read)
                    out.flush()
                }
            } catch (closed: IOException) {
                // The stream was closed under the reader after the run ended.
            }
        }
    }

    /**
     * Whether the agent itself is still running. A recorded start time that no longer
     * matches means the PID was reused, so the agent is gone.
     */
    private fun leaderAlive(address: CancellationAddress): Boolean {
        val leader = ProcessHandle.of(address.leaderPid).orElse(null) ?: return false
        if (!leader.isAlive) return false
        val recorded = address.leaderStartedAt ?: return true
        val started = leader.info().startInstant().orElse(null) ?: return true
        return kotlin.math.abs(started.toEpochMilli() - recorded.toEpochMilliseconds()) <= 1_000
    }

    companion object {

        /** The ratified kill rule: ten silent minutes. */
        val DEFAULT_SILENCE_LIMIT: Duration = 10.minutes

        val DEFAULT_POLL_INTERVAL: Duration = 1.seconds

        private val DRAIN_TIMEOUT: Duration = 2.seconds

        private const val BUFFER_BYTES = 8192
    }
}
