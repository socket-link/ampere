package link.socket.ampere.agents.execution

import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.execution.tools.git.GitOperationResponse

/*
 * How a plan's earlier steps are rendered for the step that comes next (AMPR-408).
 *
 * The Execute phase dispatches one step at a time, and each step's parameters are
 * *generated* by a model call a `ParameterStrategy` prompts for. Until AMPR-408 that
 * prompt was built from the tool, the request and `request.context.instructions`
 * alone — the step's own description and nothing else — so "search for X, then
 * summarise what you found" could not run: step two's parameters were generated
 * without step one's result. The results now ride on
 * `ExecutionRequest.priorResults`, and this file is the one place that turns them
 * into prompt text, so every strategy renders them the same way.
 *
 * Two functions, one per half of the problem:
 *
 * - `describeResult` is what a step's *outcome* actually said. It is what makes a
 *   threaded result worth threading: `"tool=search outcome=Success"` names the tool
 *   and tells the next step nothing it can act on.
 * - `priorResultsSection` is the prompt block built from the resulting
 *   `StepOutcome`s.
 */

/**
 * The per-result cap, in characters.
 *
 * A read of three source files is a legitimate step result and would otherwise be
 * pasted whole into every later step's parameter prompt. The cap is per result
 * rather than on the section as a whole so the *last* step — usually the one the
 * next step depends on — is never the one dropped.
 */
private const val MAX_RESULT_CHARS = 1200

/** Truncation marker, long enough to be obvious in a prompt and in a test. */
private const val TRUNCATION_MARKER = "… (truncated)"

/**
 * What this outcome has to say to whatever runs next, as plain text.
 *
 * Rendered from the outcome's own payload — the message, the files, the issue
 * numbers, the commit — because that payload is the only thing a later step can
 * actually use. Failures render their error for the same reason: the next step's
 * parameters often depend on *why* the last one failed.
 *
 * Exhaustive over [ExecutionOutcome]'s leaves on purpose, with no `else` branch: a
 * new outcome variant should make this stop compiling, so that the person adding
 * it decides what the next step gets to see. An `else` here would silently render
 * the new variant as nothing, which looks like a tool that returned nothing.
 */
fun ExecutionOutcome.describeResult(): String = when (this) {
    is ExecutionOutcome.Blank -> ""
    is ExecutionOutcome.NoChanges.Success -> message
    is ExecutionOutcome.NoChanges.Failure -> message
    is ExecutionOutcome.CodeReading.Success -> readFiles.renderFiles()
    is ExecutionOutcome.CodeReading.Failure -> listOfNotNull(
        error.render(),
        partiallyReadFiles?.takeIf { it.isNotEmpty() }?.renderFiles(),
    ).joinToString("\n")
    is ExecutionOutcome.CodeChanged.Success -> "changed files: ${changedFiles.joinToString()}"
    is ExecutionOutcome.CodeChanged.Failure -> listOfNotNull(
        error.render(),
        partiallyChangedFiles?.takeIf { it.isNotEmpty() }
            ?.let { "partially changed files: ${it.joinToString()}" },
    ).joinToString("\n")
    is ExecutionOutcome.IssueManagement.Success ->
        response.created
            .joinToString { "${it.localId} -> #${it.issueNumber}" }
            .ifBlank { "no issues created" }
    is ExecutionOutcome.IssueManagement.Failure -> error.render()
    is ExecutionOutcome.GitOperation.Success -> renderGitResponse(response)
    is ExecutionOutcome.GitOperation.Failure -> error.render()
    is ExecutionOutcome.Planning.Success -> renderPlan(plan)
    is ExecutionOutcome.Planning.Failure -> error.render()
}

/**
 * The prompt block describing [priorResults], or the empty string when there are
 * none — the first step of a plan reads exactly the prompt it read before AMPR-408.
 *
 * Each result names the step it came from and what that step produced, oldest
 * first, so a model can refer to "the result of step 2". The closing line is what
 * turns the block from context into an instruction: without it a model tends to
 * re-do the earlier step rather than build on it.
 */
fun priorResultsSection(priorResults: List<StepOutcome>): String {
    if (priorResults.isEmpty()) return ""

    return buildString {
        appendLine("Results of the steps already executed in this plan, oldest first:")
        priorResults.forEachIndexed { index, result ->
            appendLine("${index + 1}. ${result.verdict()} ${result.stepDescription}")
            result.detailText().takeIf { it.isNotBlank() }?.let { detail ->
                detail.truncateForPrompt().lines().forEach { line -> appendLine("   $line") }
            }
        }
        appendLine()
        appendLine(
            "Use these results as the input to this step wherever it depends on them, " +
                "and do not repeat work they have already done.",
        )
    }
}

/** The step's verdict, as a short tag a model reads without a legend. */
private fun StepOutcome.verdict(): String = when (this) {
    is StepOutcome.Success -> "[succeeded]"
    is StepOutcome.PartialSuccess -> "[partially succeeded: $successCount ok, $failureCount failed]"
    is StepOutcome.Failure -> "[failed]"
    is StepOutcome.Skipped -> "[skipped]"
}

/**
 * What the step reported, independent of which variant carries it: the details of a
 * success, the error of a failure, the reason of a skip.
 *
 * Public because the step's own report is also what a one-step plan's aggregate
 * outcome has to carry — see `SparkBasedAgent.dispatchStepAsPlan` (AMPR-408).
 */
fun StepOutcome.detailText(): String = when (this) {
    is StepOutcome.Success -> details
    is StepOutcome.PartialSuccess -> details
    is StepOutcome.Failure -> error
    is StepOutcome.Skipped -> reason
}

private fun ExecutionError.render(): String = "$type: $message"

private fun List<Pair<String, String>>.renderFiles(): String =
    joinToString("\n") { (path, contents) -> "$path:\n$contents" }

private fun renderGitResponse(response: GitOperationResponse): String =
    listOfNotNull(
        response.createdBranch?.let { "created branch ${it.branchName} from ${it.baseBranch}" },
        response.createdCommit?.let { "committed ${it.commitSha}: ${it.message}" },
        response.createdPullRequest?.let { "opened PR #${it.number} at ${it.url}" },
        response.pushResult?.let { "pushed ${it.branchName} to ${it.remoteName}" },
        response.stagedFiles?.let { "staged ${it.stagedFiles.joinToString()}" },
        response.statusResult?.let { "on branch ${it.branch}" },
        response.error,
    ).joinToString("\n").ifBlank { if (response.success) "git operation succeeded" else "git operation failed" }

private fun renderPlan(plan: Plan): String =
    plan.tasks
        .joinToString("\n") { task -> (task as? Task.CodeChange)?.description ?: "step ${task.id}" }
        .ifBlank { "plan with no steps" }

/**
 * [MAX_RESULT_CHARS] of this text, with [TRUNCATION_MARKER] appended when there was
 * more. Cut at the last line break inside the budget where there is one, so a
 * truncated file listing does not end mid-path.
 */
private fun String.truncateForPrompt(): String {
    if (length <= MAX_RESULT_CHARS) return this
    val head = substring(0, MAX_RESULT_CHARS)
    val cut = head.lastIndexOf('\n').takeIf { it > MAX_RESULT_CHARS / 2 } ?: MAX_RESULT_CHARS
    return head.substring(0, cut).trimEnd() + "\n$TRUNCATION_MARKER"
}
