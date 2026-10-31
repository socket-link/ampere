package link.socket.ampere.agents.execution.dispatch

import link.socket.ampere.agents.execution.verify.GateOutcome
import link.socket.ampere.agents.execution.verify.VerificationReport
import link.socket.ampere.agents.execution.verify.VerifyManifest

/**
 * The verification report a repair run reads (AMPR-309, rule 5): which gates failed,
 * the commands they ran, and the tail of each one's output.
 *
 * Written into the worktree, beside the ticket's context, and attached to the repair
 * run's prompt the same way the ticket is. It is the only place a gate's verbatim
 * output goes: the file stays on the supervisor host, in a directory the repository
 * ignores, and is never journaled, published or escalated.
 */
internal object RepairReports {

    fun render(
        ticketId: String,
        manifest: VerifyManifest,
        report: VerificationReport,
        attempt: Int,
        maxAttempts: Int,
    ): String = buildString {
        val failed = report.withOutcome(GateOutcome.FAILED)
        appendLine("# Verification failed — repair attempt $attempt of $maxAttempts for $ticketId")
        appendLine()
        appendLine(
            "Your previous run exited, and the repository's verification gates (`${VerifyManifest.PATH}`) were run " +
                "against your branch on the supervisor host. ${failed.size} of ${report.gates.size} failed. " +
                "Exit codes decide pass or fail; nothing you report about your own work is read.",
        )
        appendLine()
        appendLine(
            "Fix the code so every gate below passes, then commit and push your branch and exit. The gates run again " +
                "when you exit. They are read from the base branch, not from this worktree, so editing " +
                "`${VerifyManifest.PATH}` changes nothing. " +
                if (attempt >= maxAttempts) {
                    "This is the last attempt; if a gate still fails, the ticket is escalated to a person."
                } else {
                    "${maxAttempts - attempt} more attempt(s) remain after this one."
                },
        )
        failed.forEach { result ->
            val gate = manifest.gates.firstOrNull { it.id == result.gateId }
            appendLine()
            appendLine("## `${result.gateId}`")
            appendLine()
            appendLine("- Outcome: ${result.reason}")
            result.step?.let { appendLine("- Failing step: `$it`") }
            gate?.steps?.forEach { step ->
                appendLine("- Command${step.id?.let { " (`$it`)" } ?: ""}: `${step.run}`")
            }
            if (gate?.fresh == true) appendLine("- Runs fresh: no build cache, and the gate's own task is re-run.")
            if (result.tail.isNotEmpty()) {
                appendLine()
                appendLine("Last ${result.tail.size} lines of its output:")
                appendLine()
                appendLine("````text")
                result.tail.forEach { appendLine(it) }
                appendLine("````")
            }
        }
    }
}
