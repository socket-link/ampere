package link.socket.ampere.agents.execution.verify

import java.io.BufferedWriter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import link.socket.ampere.agents.execution.process.CancellationAddress
import link.socket.ampere.agents.execution.process.GroupedProcess
import link.socket.ampere.agents.execution.process.ProcessGroups

/**
 * One gate's result.
 *
 * @property reason Why it got its [outcome]; composed from fixed text and manifest
 *   identifiers, so it may be journaled and escalated.
 * @property excerpt The anchored, sanitised lines of its log ([LogExcerpts]) — the
 *   only log text that may leave the supervisor host.
 * @property tail The last lines of its log, verbatim. **Local only**: handed to a
 *   repairing agent in its worktree, never journaled, published or escalated.
 * @property log The full log, on the supervisor host, when one was written.
 */
data class GateResult(
    val gateId: String,
    val outcome: GateOutcome,
    val reason: String,
    val baseline: Baseline,
    val duration: Duration = Duration.ZERO,
    val exitCode: Int? = null,
    val step: String? = null,
    val excerpt: List<String> = emptyList(),
    val tail: List<String> = emptyList(),
    val log: Path? = null,
) {
    /** The journal's record of this result. */
    fun record(attempt: Int): GateRecord = GateRecord(
        gateId = gateId,
        outcome = outcome,
        attempt = attempt,
        durationMillis = duration.inWholeMilliseconds,
        exitCode = exitCode,
        step = step,
        reason = reason,
    )
}

/**
 * A whole verification run.
 *
 * @property refusedBy The hard preconditions that were absent, by id, with why. Non-empty
 *   means no gate ran.
 * @property interrupted The supervisor shut down before every gate had run.
 */
data class VerificationReport(
    val gates: List<GateResult>,
    val refusedBy: Map<String, String> = emptyMap(),
    val interrupted: Boolean = false,
) {
    val verdict: VerificationVerdict
        get() = when {
            refusedBy.isNotEmpty() -> VerificationVerdict.REFUSED
            interrupted -> VerificationVerdict.INTERRUPTED
            gates.any { it.outcome == GateOutcome.INFRASTRUCTURE } -> VerificationVerdict.HOST_FAILURE
            gates.any { it.outcome == GateOutcome.FAILED } -> VerificationVerdict.AGENT_FAILURE
            gates.any { it.outcome == GateOutcome.NOT_EXECUTED } -> VerificationVerdict.INCOMPLETE
            else -> VerificationVerdict.GREEN
        }

    fun count(outcome: GateOutcome): Int = gates.count { it.outcome == outcome }

    /** The gates with [outcome], in run order. */
    fun withOutcome(outcome: GateOutcome): List<GateResult> = gates.filter { it.outcome == outcome }
}

/** What a [GateRunner] tells its caller as it works. Every method defaults to doing nothing. */
interface GateListener {

    /**
     * A gate's command is running, in its own process group at [address]. Called once
     * per step and once per re-run, so a caller can record what to stop if it dies.
     */
    suspend fun onGateStarted(gate: ManifestGate, step: GateStep, address: CancellationAddress) {}

    /** A gate reached its outcome. Called for every gate, executed or not. */
    suspend fun onGateSettled(result: GateResult) {}

    companion object {
        val NONE: GateListener = object : GateListener {}
    }
}

/**
 * Executes a [VerifyManifest] in one repository and classifies every gate (AMPR-309,
 * Expected Outcome 2).
 *
 * ### The order of a run
 *
 * 1. **Hard preconditions** (`env.requires`), all of them, before anything runs. One
 *    absent refuses the run: every gate reports `not_executed` naming the blocker.
 * 2. **Soft preconditions** (`env.optional`), once each. A gate that `needs` an
 *    absent one reports `not_executed` naming it; every other gate runs.
 * 3. **Every gate, in listed order, one at a time.** Order never short-circuits: a red
 *    gate does not stop the next one, because fail-fast between gates is the bundling
 *    bug one level up. And one at a time even where a gate is `parallel_safe` — v1
 *    never runs two gates of one repository at once (Gradle daemon and lock
 *    contention, measured in the AMPR-290 recon).
 * 4. **A failure is examined before it is charged**: the gate's own preconditions are
 *    re-checked, and a `retry_safe` gate is re-run once to see whether the failure
 *    reproduces. Then [GateClassifier] decides.
 *
 * ### Executing one gate
 *
 * Each command runs as an argument vector ([GateCommand]) in the repository root, in
 * its own process group, with stdin closed and stdout and stderr together in a log
 * file. `${env.VAR}` is substituted from [environment]; an unset one means the gate
 * never starts. A `fresh` Gradle gate runs with `--no-build-cache` and `--rerun` and
 * its own test results deleted first ([GateCommand.freshGradle]); any other tool has
 * no result cache this runner knows of, so `fresh` adds nothing for it. The timeout
 * covers every step of a gate, and running out terminates the whole group — the
 * Gradle client and everything it spawned in it — before reporting.
 *
 * ### What it never does
 *
 * Read a log to decide anything. Exit codes decide pass/fail, [GateEvidence] decides
 * whose failure it was, and the log is quoted ([LogExcerpts]) and handed back
 * ([GateResult.tail]) only.
 *
 * @param reproduceFailures Re-run a failed `retry_safe` gate to see whether it
 *   reproduces. Gates with `side_effects: network` are never re-run: running one twice
 *   is not the same as running it once.
 */
class GateRunner(
    private val preconditions: PreconditionChecker = HostPreconditions(),
    private val environment: Map<String, String> = System.getenv(),
    private val reproduceFailures: Boolean = true,
    private val grace: Duration = ProcessGroups.DEFAULT_GRACE,
    private val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {

    /**
     * Run every gate of [manifest] in [root].
     *
     * @param logDirectory Where each gate's log is written, as `<gate id>.log`. Null
     *   keeps only the in-memory tail.
     * @param stopping Whether the supervisor is shutting down. Checked between gates
     *   and while one runs; a running gate's group is terminated.
     */
    suspend fun run(
        manifest: VerifyManifest,
        root: Path,
        logDirectory: Path? = null,
        listener: GateListener = GateListener.NONE,
        stopping: () -> Boolean = { false },
    ): VerificationReport {
        val refused = linkedMapOf<String, String>()
        manifest.requires.forEach { precondition ->
            val check = preconditions.check(precondition, root)
            if (check is PreconditionCheck.Absent) refused[precondition.id] = check.why
        }
        if (refused.isNotEmpty()) {
            val blockers = refused.entries.joinToString { (id, why) -> "`$id` ($why)" }
            val gates = manifest.gates.map { gate ->
                GateResult(
                    gateId = gate.id,
                    outcome = GateOutcome.NOT_EXECUTED,
                    reason = "the run was refused: hard precondition $blockers absent",
                    baseline = gate.baseline,
                ).also { listener.onGateSettled(it) }
            }
            return VerificationReport(gates = gates, refusedBy = refused)
        }

        val absentSoft = linkedMapOf<String, String>()
        manifest.optional.forEach { precondition ->
            val check = preconditions.check(precondition, root)
            if (check is PreconditionCheck.Absent) absentSoft[precondition.id] = check.why
        }

        val results = mutableListOf<GateResult>()
        var interrupted = false
        for (gate in manifest.gates) {
            val missing = gate.needs.filter { it in absentSoft }
            val result = when {
                interrupted || stopping() -> {
                    interrupted = true
                    GateResult(
                        gateId = gate.id,
                        outcome = GateOutcome.NOT_EXECUTED,
                        reason = "the supervisor shut down before it ran",
                        baseline = gate.baseline,
                    )
                }

                missing.isNotEmpty() -> GateResult(
                    gateId = gate.id,
                    outcome = GateOutcome.NOT_EXECUTED,
                    reason = "soft precondition " +
                        missing.joinToString { "`$it` (${absentSoft.getValue(it)})" } + " absent",
                    baseline = gate.baseline,
                )

                else -> verify(gate, manifest, root, logDirectory, listener, stopping).also {
                    if (it.second) interrupted = true
                }.first
            }
            listener.onGateSettled(result)
            results += result
        }
        return VerificationReport(gates = results, interrupted = interrupted)
    }

    /** Execute [gate], examine a failure, classify. The second value says whether it was interrupted. */
    private suspend fun verify(
        gate: ManifestGate,
        manifest: VerifyManifest,
        root: Path,
        logDirectory: Path?,
        listener: GateListener,
        stopping: () -> Boolean,
    ): Pair<GateResult, Boolean> {
        val log = logDirectory?.resolve("${gate.id}.log")
        val first = execute(gate, root, log, listener, stopping, rerun = false)
        var decisive = first
        var duration = first.duration
        var evidence = first.evidence

        if (evidence.undecidedFailure()) {
            val broken = gate.dependsOn.mapNotNull { id ->
                val precondition = manifest.preconditions[id] ?: return@mapNotNull null
                id.takeIf { preconditions.check(precondition, root) is PreconditionCheck.Absent }
            }
            evidence = evidence.copy(brokenPreconditions = broken)
        }

        if (evidence.undecidedFailure() && evidence.brokenPreconditions.isEmpty() && canReproduce(gate)) {
            val again = execute(gate, root, log, listener, stopping, rerun = true)
            duration += again.duration
            evidence = when {
                again.evidence.interrupted -> evidence.copy(interrupted = true)
                // A re-run that could not finish says nothing about reproduction, so the
                // conservative reading stands: it is the agent's.
                again.evidence.timedOut || again.evidence.notStarted != null -> evidence.copy(reproduced = true)
                else -> evidence.copy(reproduced = again.evidence.exitCode != 0)
            }
            if (evidence.reproduced == true) decisive = again
        }

        val classification = GateClassifier.classify(evidence)
        val passed = classification.outcome == GateOutcome.PASSED
        val result = GateResult(
            gateId = gate.id,
            outcome = classification.outcome,
            reason = classification.reason,
            baseline = gate.baseline,
            duration = duration,
            exitCode = decisive.evidence.exitCode,
            step = decisive.step,
            excerpt = if (passed) emptyList() else LogExcerpts.anchored(decisive.tail),
            tail = if (passed) emptyList() else decisive.tail.takeLast(TAIL_LINES),
            log = log,
        )
        return result to evidence.interrupted
    }

    private fun canReproduce(gate: ManifestGate): Boolean =
        reproduceFailures && gate.retrySafe && gate.sideEffects != SideEffects.NETWORK

    private fun GateEvidence.undecidedFailure(): Boolean =
        exitCode != null && exitCode != 0 && !timedOut && !interrupted && unsetVariables.isEmpty() &&
            notStarted == null && exitCode != GateClassifier.EXIT_NOT_EXECUTABLE &&
            exitCode != GateClassifier.EXIT_NOT_FOUND

    /** One execution of [gate]: every step in order, stopping at the first non-zero. */
    private class Execution(
        val evidence: GateEvidence,
        val step: String?,
        val tail: List<String>,
        val duration: Duration,
    )

    private suspend fun execute(
        gate: ManifestGate,
        root: Path,
        log: Path?,
        listener: GateListener,
        stopping: () -> Boolean,
        rerun: Boolean,
    ): Execution = withContext(Dispatchers.IO) {
        val started = timeSource.markNow()
        val deadline = started + gate.timeout
        val tail = ArrayDeque<String>()
        val writer = log?.let { openLog(it, append = rerun) }
        fun note(line: String) = synchronized(tail) { writer?.let { runCatching { it.appendLine(line) } } }
        note(if (rerun) "--- ${gate.id}: re-run to see whether the failure reproduces ---" else "--- ${gate.id} ---")

        try {
            var lastStep: String? = null
            var exitCode: Int? = null
            for ((index, step) in gate.steps.withIndex()) {
                lastStep = step.id
                val verdictStep = index == gate.steps.lastIndex
                val parsed = GateCommand.argv(step.run)
                val argv = when (val substitution = GateCommand.substitute(parsed, environment)) {
                    is GateCommand.Substitution.Done -> substitution.argv
                    is GateCommand.Substitution.Unset -> return@withContext Execution(
                        GateEvidence(exitCode = null, unsetVariables = substitution.names),
                        step.id,
                        snapshot(tail),
                        started.elapsedNow(),
                    )
                }
                val command = if (gate.fresh && verdictStep && GateCommand.isGradle(argv)) {
                    val plan = GateCommand.freshGradle(argv)
                    plan.deletions.forEach { deleteInside(root, it) }
                    plan.argv
                } else {
                    argv
                }
                note("\$ ${command.joinToString(" ")}")

                val builder = ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
                builder.environment().clear()
                builder.environment().putAll(environment)
                builder.environment()["GIT_TERMINAL_PROMPT"] = "0"
                val grouped = try {
                    ProcessGroups.start(builder)
                } catch (e: IOException) {
                    return@withContext Execution(
                        GateEvidence(exitCode = null, notStarted = "`${parsed.first()}` could not be started"),
                        step.id,
                        snapshot(tail),
                        started.elapsedNow(),
                    )
                }
                listener.onGateStarted(gate, step, grouped.address)

                when (val ended = await(grouped, deadline, writer, tail, stopping)) {
                    Ended.TimedOut -> return@withContext Execution(
                        GateEvidence(exitCode = null, timedOut = true),
                        step.id,
                        snapshot(tail),
                        started.elapsedNow(),
                    )

                    Ended.Interrupted -> return@withContext Execution(
                        GateEvidence(exitCode = null, interrupted = true),
                        step.id,
                        snapshot(tail),
                        started.elapsedNow(),
                    )

                    is Ended.Exited -> exitCode = ended.code
                }
                if (exitCode != 0) break
            }
            Execution(GateEvidence(exitCode = exitCode), lastStep, snapshot(tail), started.elapsedNow())
        } finally {
            writer?.let { runCatching { it.close() } }
        }
    }

    private sealed interface Ended {
        data object TimedOut : Ended
        data object Interrupted : Ended
        data class Exited(val code: Int) : Ended
    }

    private suspend fun await(
        grouped: GroupedProcess,
        deadline: TimeMark,
        writer: BufferedWriter?,
        tail: ArrayDeque<String>,
        stopping: () -> Boolean,
    ): Ended {
        val process = grouped.process
        runCatching { process.outputStream.close() }
        val reader = thread(isDaemon = true, name = "ampere-gate-${grouped.address.leaderPid}") {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(tail) {
                            tail.addLast(line)
                            while (tail.size > BUFFERED_LINES) tail.removeFirst()
                            writer?.let { runCatching { it.appendLine(line) } }
                        }
                    }
                }
            }
        }

        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (process.waitFor(pollInterval.inWholeMilliseconds, TimeUnit.MILLISECONDS)) break
                if (stopping()) {
                    grouped.terminate(grace)
                    drain(reader, process)
                    return Ended.Interrupted
                }
                if (deadline.hasPassedNow()) {
                    grouped.terminate(grace)
                    drain(reader, process)
                    synchronized(tail) {
                        writer?.let { runCatching { it.appendLine("--- timed out; group terminated ---") } }
                    }
                    return Ended.TimedOut
                }
            }
        } catch (e: Throwable) {
            withContext(NonCancellable) { grouped.terminate(grace) }
            throw e
        }
        drain(reader, process)
        return Ended.Exited(process.exitValue())
    }

    /** Let the reader finish what the process wrote; a descendant holding the pipe open is not waited for. */
    private fun drain(reader: Thread, process: Process) {
        reader.join(DRAIN_TIMEOUT.inWholeMilliseconds)
        if (reader.isAlive) runCatching { process.inputStream.close() }
    }

    private fun openLog(path: Path, append: Boolean): BufferedWriter? = runCatching {
        Files.createDirectories(path.parent)
        val options = if (append) {
            arrayOf(StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } else {
            arrayOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        }
        Files.newBufferedWriter(path, *options)
    }.getOrNull()

    private fun snapshot(tail: ArrayDeque<String>): List<String> = synchronized(tail) { tail.toList() }

    private fun BufferedWriter.appendLine(line: String) {
        write(line)
        newLine()
        flush()
    }

    /** Delete [relative] under [root], refusing anything that would resolve outside it. */
    private fun deleteInside(root: Path, relative: String) {
        val base = root.toAbsolutePath().normalize()
        val target = base.resolve(relative).normalize()
        if (!target.startsWith(base) || target == base || !Files.exists(target)) return
        target.toFile().deleteRecursively()
    }

    companion object {

        val DEFAULT_POLL_INTERVAL: Duration = 200.milliseconds

        /** Lines of a gate's output held in memory, for the excerpt and the tail. */
        private const val BUFFERED_LINES = 2_000

        /** Lines of a failed gate's tail handed to a repairing agent. */
        const val TAIL_LINES: Int = 200

        private val DRAIN_TIMEOUT: Duration = 2.seconds
    }
}
