package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.events.utils.generateUUID

/**
 * Helper functions to create GitEvents from ExecutionOutcome.GitOperation results.
 *
 * These functions enable agents and coordinators to emit fine-grained Git-specific
 * events based on tool execution outcomes. Use these at the agent coordination level
 * after Git tools complete execution.
 *
 * Example usage in agent code:
 * ```kotlin
 * val outcome = toolInvoker.invoke(gitRequest)
 * if (outcome is ExecutionOutcome.GitOperation.Success) {
 *     val gitEvents = createGitEventsFromOutcome(outcome, agentId)
 *     gitEvents.forEach { eventApi.publish(it) }
 * }
 * ```
 */

/**
 * Creates appropriate GitEvents from a successful Git operation outcome.
 *
 * Examines the response to determine which operations completed and creates
 * corresponding events (BranchCreated, Committed, Pushed, PullRequestCreated, etc.).
 *
 * @param outcome The successful Git operation outcome
 * @param agentId The ID of the agent that performed the operation
 * @param urgency The urgency level for the events (default: LOW for routine operations)
 * @return List of GitEvents representing what occurred
 */
fun createGitEventsFromOutcome(
    outcome: ExecutionOutcome.GitOperation.Success,
    agentId: AgentId,
    urgency: Urgency = Urgency.LOW,
): List<GitEvent> {
    val events = mutableListOf<GitEvent>()
    val timestamp = Clock.System.now()
    val eventSource = EventSource.Agent(agentId)

    outcome.response.createdBranch?.let { branch ->
        events.add(
            GitEvent.BranchCreated(
                eventId = generateUUID(),
                timestamp = timestamp,
                eventSource = eventSource,
                urgency = urgency,
                branchName = branch.branchName,
                baseBranch = branch.baseBranch,
                commitSha = branch.commitSha,
                issueNumber = null, // Would need to extract from context
            ),
        )
    }

    outcome.response.createdCommit?.let { commit ->
        events.add(
            GitEvent.Committed(
                eventId = generateUUID(),
                timestamp = timestamp,
                eventSource = eventSource,
                urgency = urgency,
                commitSha = commit.commitSha,
                message = commit.message,
                filesCommitted = commit.filesCommitted,
                issueNumber = null, // Would need to extract from context
            ),
        )
    }

    outcome.response.pushResult?.let { push ->
        events.add(
            GitEvent.Pushed(
                eventId = generateUUID(),
                timestamp = timestamp,
                eventSource = eventSource,
                urgency = urgency,
                branchName = push.branchName,
                remoteName = push.remoteName,
                upstreamSet = push.upstreamSet,
            ),
        )
    }

    outcome.response.createdPullRequest?.let { pr ->
        events.add(
            GitEvent.PullRequestCreated(
                eventId = generateUUID(),
                timestamp = timestamp,
                eventSource = eventSource,
                urgency = urgency,
                prNumber = pr.number,
                url = pr.url,
                title = "", // Would need to extract from context
                headBranch = pr.headBranch,
                baseBranch = pr.baseBranch,
                issueNumber = null, // Would need to extract from context
                reviewers = emptyList(), // Would need to extract from context
                draft = false, // Would need to extract from context
            ),
        )
    }

    outcome.response.stagedFiles?.let { staged ->
        events.add(
            GitEvent.FilesStaged(
                eventId = generateUUID(),
                timestamp = timestamp,
                eventSource = eventSource,
                urgency = urgency,
                stagedFiles = staged.stagedFiles,
            ),
        )
    }

    return events
}

/**
 * Creates a GitEvent.OperationFailed from a failed Git operation outcome.
 *
 * @param outcome The failed Git operation outcome
 * @param agentId The ID of the agent that attempted the operation
 * @param operation The name of the operation that failed
 * @param urgency The urgency level for the event (default: MEDIUM for failures)
 * @return GitEvent.OperationFailed describing what went wrong
 */
fun createGitFailureEvent(
    outcome: ExecutionOutcome.GitOperation.Failure,
    agentId: AgentId,
    operation: String,
    urgency: Urgency = Urgency.MEDIUM,
): GitEvent.OperationFailed {
    return GitEvent.OperationFailed(
        eventId = generateUUID(),
        timestamp = Clock.System.now(),
        eventSource = EventSource.Agent(agentId),
        urgency = urgency,
        operation = operation,
        errorMessage = outcome.error.message,
        isRetryable = outcome.error.isRetryable,
    )
}

/**
 * Determines the operation name from a GitOperationRequest.
 *
 * @param context The Git operation context
 * @return Human-readable operation name
 */
fun getGitOperationName(context: link.socket.ampere.agents.execution.request.ExecutionContext.GitOperation): String {
    return when {
        context.gitRequest.createBranch != null -> "createBranch"
        context.gitRequest.commit != null -> "commit"
        context.gitRequest.push != null -> "push"
        context.gitRequest.createPullRequest != null -> "createPullRequest"
        context.gitRequest.stageFiles != null -> "stageFiles"
        context.gitRequest.getStatus -> "getStatus"
        context.gitRequest.checkout != null -> "checkout"
        else -> "unknownGitOperation"
    }
}
