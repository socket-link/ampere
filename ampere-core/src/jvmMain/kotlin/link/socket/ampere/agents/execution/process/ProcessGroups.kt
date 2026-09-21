package link.socket.ampere.agents.execution.process

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant

/**
 * A subprocess spawned as the leader of its own process group, with the
 * [CancellationAddress] needed to stop it and everything it spawned.
 *
 * On the bash path [process] is a thin `bash` wrapper that stays in the caller's
 * group, waits for the leader, and relays its stdin, stdout, stderr and exit code.
 * Stop the work with [terminate], not [Process.destroy]: destroying the wrapper
 * leaves the group running.
 */
class GroupedProcess internal constructor(
    val process: Process,
    val address: CancellationAddress,
) {
    /**
     * SIGTERM the group, wait up to [grace], then SIGKILL; afterwards reap the wrapper.
     */
    fun terminate(grace: Duration = ProcessGroups.DEFAULT_GRACE): TerminationOutcome {
        val outcome = ProcessGroups.terminate(address, grace)
        if (!process.waitFor(grace.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
        }
        return outcome
    }
}

/**
 * Spawn-with-cancellation-address for JVM subprocesses (AMPR-299, D4).
 *
 * Every subprocess that can outlive its spawner goes through [start] or [run], so
 * that it is placed in its own process group at spawn and its [CancellationAddress]
 * is known to the caller. Termination is always SIGTERM to the group, a bounded
 * wait, then SIGKILL — and works from the address alone, so a restarted supervisor
 * can reap orphans it recorded before it died.
 *
 * How the group is created, in order of preference:
 * 1. `/bin/bash` with job control: the command runs as a background job, which bash
 *    puts in a new group whose ID is the job's PID. `bash` rather than `sh` because
 *    dash silently ignores `set -m` without a terminal.
 * 2. `setsid` on the PATH (bash-less Linux): the command becomes a session and group
 *    leader in place.
 * 3. Neither (Windows): a plain spawn with no group. The address carries no group
 *    ID and termination walks the leader's live process tree instead, which cannot
 *    reach descendants once the leader itself has died.
 */
object ProcessGroups {

    val DEFAULT_GRACE: Duration = 5.seconds

    /** How long [start] waits for the bash wrapper to report the group ID. */
    private val SPAWN_REPORT_TIMEOUT: Duration = 10.seconds

    /** How long to wait for the group to disappear after SIGKILL. */
    private val KILL_SETTLE: Duration = 2.seconds

    private val POLL_INTERVAL: Duration = 20.milliseconds

    /**
     * Runs `"$@"` as a job-controlled background job, writes its PID (= its new
     * group ID) to `$1`, and exits with its status. The wrapper's own stderr goes to
     * /dev/null so bash's job notices ("[1]+ Done") never reach the caller; the job
     * gets the original stderr through fd 3.
     */
    private const val BASH_WRAPPER =
        "f=\$1; shift; exec 3>&2 2>/dev/null; set -m; \"\$@\" 2>&3 3>&- & echo \"\$!\" > \"\$f\"; wait \"\$!\""

    private val bashPath: String? = File("/bin/bash").takeIf { it.canExecute() }?.path

    private val setsidPath: String? by lazy { findOnPath("setsid") }

    /**
     * Start [builder]'s command as the leader of a new process group. The builder's
     * directory, environment and redirects apply to the spawned command; its command
     * list is read but not modified.
     *
     * A missing executable is reported as exit code 127 with the shell's message on
     * stderr, not as an [IOException].
     *
     * Blocks until the group ID is known.
     */
    fun start(builder: ProcessBuilder): GroupedProcess {
        val command = builder.command().toList()
        require(command.isNotEmpty()) { "Cannot spawn an empty command" }
        val bash = bashPath
        val setsid = setsidPath
        return when {
            bash != null -> startWithBash(builder, bash, command)
            setsid != null -> startWithSetsid(builder, setsid, command)
            else -> startWithoutGroup(builder, command)
        }
    }

    /**
     * Start [builder] with [start] and pass it to [block] on an IO thread.
     *
     * If the calling coroutine is cancelled, or [block] throws, the group is
     * terminated with [grace] before this returns — which also closes its pipes, so
     * a [block] blocked reading output is released. On normal return the group is
     * left alone: anything it deliberately left running (a build daemon, say) keeps
     * running.
     */
    suspend fun <T> run(
        builder: ProcessBuilder,
        grace: Duration = DEFAULT_GRACE,
        block: (GroupedProcess) -> T,
    ): T = withContext(Dispatchers.IO) {
        coroutineScope {
            val grouped = start(builder)
            val abandoned = AtomicBoolean(true)
            // UNDISPATCHED so the finally is armed before any suspension point: a
            // cancellation that lands immediately after spawn still reaps the group.
            val reaper = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    if (abandoned.get()) grouped.terminate(grace)
                }
            }
            try {
                runInterruptible { block(grouped) }.also { abandoned.set(false) }
            } finally {
                reaper.cancel()
            }
        }
    }

    /** Whether anything at [address] is still running. */
    fun isAlive(address: CancellationAddress): Boolean {
        val group = address.processGroupId
        return if (group != null) {
            signalGroup(group, "0")
        } else {
            ProcessHandle.of(address.leaderPid).map { it.isAlive }.orElse(false)
        }
    }

    /**
     * SIGTERM everything at [address], wait up to [grace], then SIGKILL.
     *
     * Needs only the address, so it is the recovery path for a supervisor that
     * restarted and is reaping what it recorded before it died.
     */
    fun terminate(
        address: CancellationAddress,
        grace: Duration = DEFAULT_GRACE,
    ): TerminationOutcome {
        if (!isAlive(address)) return TerminationOutcome.ALREADY_GONE
        if (leaderWasReplaced(address)) return TerminationOutcome.REFUSED_ADDRESS_REUSED

        val group = address.processGroupId
        if (group != null) {
            signalGroup(group, "TERM")
        } else {
            destroyTree(address.leaderPid, forcibly = false)
        }
        if (awaitGone(address, grace)) return TerminationOutcome.TERMINATED

        if (group != null) {
            signalGroup(group, "KILL")
        } else {
            destroyTree(address.leaderPid, forcibly = true)
        }
        return if (awaitGone(address, KILL_SETTLE)) {
            TerminationOutcome.KILLED
        } else {
            TerminationOutcome.SURVIVED
        }
    }

    private fun startWithBash(
        builder: ProcessBuilder,
        bash: String,
        command: List<String>,
    ): GroupedProcess {
        val report = Files.createTempFile("ampere-pgid-", ".txt").toFile()
        try {
            val process = withCommand(builder, listOf(bash, "-c", BASH_WRAPPER, "ampere-spawn", report.path) + command)
            val groupId = try {
                awaitReportedGroupId(report, process)
            } catch (e: Exception) {
                process.destroyForcibly()
                throw e
            }
            return GroupedProcess(process, addressOf(leaderPid = groupId, processGroupId = groupId))
        } finally {
            report.delete()
        }
    }

    private fun startWithSetsid(
        builder: ProcessBuilder,
        setsid: String,
        command: List<String>,
    ): GroupedProcess {
        // A ProcessBuilder child is never a group leader, so setsid execs in place
        // rather than forking: the leader's PID is the process's PID.
        val process = withCommand(builder, listOf(setsid) + command)
        return GroupedProcess(process, addressOf(leaderPid = process.pid(), processGroupId = process.pid()))
    }

    private fun startWithoutGroup(builder: ProcessBuilder, command: List<String>): GroupedProcess {
        val process = withCommand(builder, command)
        return GroupedProcess(process, addressOf(leaderPid = process.pid(), processGroupId = null))
    }

    /** Start [builder] with [command], restoring the caller's command list afterwards. */
    private fun withCommand(builder: ProcessBuilder, command: List<String>): Process {
        val original = builder.command().toList()
        return try {
            builder.command(command).start()
        } finally {
            builder.command(original)
        }
    }

    private fun awaitReportedGroupId(report: File, process: Process): Long {
        val deadline = TimeSource.Monotonic.markNow() + SPAWN_REPORT_TIMEOUT
        while (true) {
            // `echo` writes the PID and newline in one write(); a line without its
            // newline is still being written.
            val text = report.readText()
            if (text.endsWith("\n")) {
                return text.trim().toLongOrNull()
                    ?: throw IOException("Spawn wrapper reported a malformed group ID: '$text'")
            }
            if (!process.isAlive) {
                throw IOException("Spawn wrapper exited (${process.exitValue()}) before reporting a group ID")
            }
            if (deadline.hasPassedNow()) {
                throw IOException("Spawn wrapper did not report a group ID within $SPAWN_REPORT_TIMEOUT")
            }
            Thread.sleep(POLL_INTERVAL.inWholeMilliseconds)
        }
    }

    private fun addressOf(leaderPid: Long, processGroupId: Long?): CancellationAddress =
        CancellationAddress(
            leaderPid = leaderPid,
            processGroupId = processGroupId,
            leaderStartedAt = startInstantOf(leaderPid),
        )

    private fun startInstantOf(pid: Long): Instant? =
        ProcessHandle.of(pid)
            .flatMap { it.info().startInstant() }
            .map { Instant.fromEpochMilliseconds(it.toEpochMilli()) }
            .orElse(null)

    /**
     * True when the leader PID is alive but started at a different time than
     * recorded — the PID was reused, so the address no longer points at our work.
     *
     * A dead leader with a live group is still ours: POSIX does not hand out a PID
     * that is the ID of an existing process group.
     */
    private fun leaderWasReplaced(address: CancellationAddress): Boolean {
        val recorded = address.leaderStartedAt ?: return false
        val current = startInstantOf(address.leaderPid) ?: return false
        return (current - recorded).absoluteValue > 1.seconds
    }

    private fun awaitGone(address: CancellationAddress, timeout: Duration): Boolean {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        while (isAlive(address)) {
            if (deadline.hasPassedNow()) return false
            Thread.sleep(POLL_INTERVAL.inWholeMilliseconds)
        }
        return true
    }

    /** `kill -s <signal> -- -<group>`; true if the group existed and was signalled. */
    private fun signalGroup(group: Long, signal: String): Boolean {
        val kill = ProcessBuilder("kill", "-s", signal, "--", "-$group")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        return kill.waitFor() == 0
    }

    private fun destroyTree(pid: Long, forcibly: Boolean) {
        val leader = ProcessHandle.of(pid).orElse(null) ?: return
        (leader.descendants().toList() + leader).forEach {
            if (forcibly) it.destroyForcibly() else it.destroy()
        }
    }

    private fun findOnPath(name: String): String? =
        System.getenv("PATH")
            ?.split(File.pathSeparator)
            ?.map { File(it, name) }
            ?.firstOrNull { it.isFile && it.canExecute() }
            ?.path
}
