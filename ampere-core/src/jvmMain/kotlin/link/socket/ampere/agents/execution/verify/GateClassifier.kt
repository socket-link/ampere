package link.socket.ampere.agents.execution.verify

/**
 * Everything the runner observed about one gate execution that may bear on whose
 * problem a failure is. Structural facts only: there is deliberately no field for
 * what the command printed.
 *
 * @property exitCode The exit code that decided the run, or null when there was none.
 * @property timedOut The gate ran past `timeout_minutes` and its group was terminated.
 * @property unsetVariables `${env.VAR}` names the gate references that were unset, so
 *   it was never started.
 * @property notStarted Why the command could not be started at all, when it could not.
 * @property brokenPreconditions Preconditions the gate depends on that passed the
 *   pre-check and were absent when re-checked after it failed.
 * @property reproduced For a failure re-run to see whether it reproduces: whether the
 *   re-run failed too. Null when there was no re-run.
 * @property interrupted The supervisor shut down while the gate ran.
 */
data class GateEvidence(
    val exitCode: Int?,
    val timedOut: Boolean = false,
    val unsetVariables: List<String> = emptyList(),
    val notStarted: String? = null,
    val brokenPreconditions: List<String> = emptyList(),
    val reproduced: Boolean? = null,
    val interrupted: Boolean = false,
)

/** An outcome and why, composed from fixed text and manifest identifiers. */
data class Classification(val outcome: GateOutcome, val reason: String)

/**
 * The infra-versus-agent decision, as one explicit step (AMPR-309, Expected Outcome 3).
 *
 * ### The heuristics, in the order they are applied
 *
 * 1. **Exit 0 is a pass.** Nothing else is consulted: the exit code is the only
 *    pass/fail contract, and a pass needs no explanation.
 * 2. **Interrupted** — the supervisor shut down mid-gate. No verdict about the code.
 * 3. **Never started** — an unset `${env.VAR}`, or a command the host could not start.
 *    The code was never asked anything.
 * 4. **Timed out** — `timeout_minutes` is wall clock, and running out of it produced
 *    no verdict about the code. A test that hangs forever is still the agent's to fix,
 *    and the escalation says which gate hung; but the schema puts a timeout on the
 *    host's side, so this does.
 * 5. **Exit 126 or 127** — the shell's "found but not executable" and "not found". The
 *    tool itself is missing, which is the host. Read from the exit code, not the log.
 * 6. **A precondition broke** — a facility the gate declares (`requires`/`needs`) was
 *    healthy at the pre-check and absent when re-checked after the failure. The
 *    failure is the host's, structurally, with no guessing.
 * 7. **Did not reproduce** — a `retry_safe` gate re-run from the same tree passed.
 *    Wrong code fails every time; a host that hiccuped usually does not. The
 *    strongest discriminator available, and it needs no log at all.
 * 8. **Anything else is the agent's.** Unknown defaults to `failed`, the
 *    conservative path: misclassifying a host problem as the agent's wastes one
 *    repair attempt and still escalates; misclassifying the agent's failure as the
 *    host's never surfaces anything either. Only `passed` can surface a merge
 *    request, so no misclassification here can.
 *
 * ### What is never consulted
 *
 * The log. Failure text was measured non-deterministic across retries of the same
 * failing gate under memory pressure — worst exactly where classification matters —
 * so a Kotlin/JS backend exception or a daemon heap exhaustion is classified by what
 * the runner can observe structurally (it does not reproduce, it times out), never by
 * matching its message. `baseline` is recorded beside every result but decides
 * nothing here: checking a `ci` gate's off-host green needs a forge, and the v1
 * runner is local-host only.
 */
object GateClassifier {

    fun classify(evidence: GateEvidence): Classification {
        val code = evidence.exitCode
        return when {
            code == 0 && !evidence.timedOut && !evidence.interrupted ->
                Classification(GateOutcome.PASSED, "exited 0")

            evidence.interrupted ->
                infrastructure("the supervisor shut down while the gate ran")

            evidence.unsetVariables.isNotEmpty() ->
                infrastructure(
                    "never started: ${evidence.unsetVariables.joinToString { "\${env.$it}" }} " +
                        (if (evidence.unsetVariables.size == 1) "is" else "are") + " unset on this host",
                )

            evidence.notStarted != null ->
                infrastructure("never started: ${evidence.notStarted}")

            evidence.timedOut ->
                infrastructure("ran past its timeout and was terminated")

            code == EXIT_NOT_EXECUTABLE || code == EXIT_NOT_FOUND ->
                infrastructure("exited $code: the command or a tool it runs is missing from this host")

            evidence.brokenPreconditions.isNotEmpty() ->
                infrastructure(
                    "exited ${code ?: "without a status"} while precondition " +
                        evidence.brokenPreconditions.joinToString { "`$it`" } + " was absent",
                )

            evidence.reproduced == false ->
                infrastructure("exited ${code ?: "without a status"}, then passed when re-run from the same tree")

            else -> Classification(
                GateOutcome.FAILED,
                buildString {
                    append("exited ${code ?: "without a status"}")
                    if (evidence.reproduced == true) append(", and again when re-run")
                },
            )
        }
    }

    /** POSIX shells: found, but not executable. */
    const val EXIT_NOT_EXECUTABLE: Int = 126

    /** POSIX shells: not found. `ProcessGroups.start` reports a missing executable this way. */
    const val EXIT_NOT_FOUND: Int = 127

    private fun infrastructure(reason: String) = Classification(GateOutcome.INFRASTRUCTURE, reason)
}
