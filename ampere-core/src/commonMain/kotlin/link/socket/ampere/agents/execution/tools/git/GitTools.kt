package link.socket.ampere.agents.execution.tools.git

import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.tools.FunctionTool

/**
 * Platform-specific implementation for Git operations.
 * This expect function must be implemented for each platform (JVM, Android, iOS).
 */
expect suspend fun executeGitOperation(
    context: ExecutionContext.GitOperation,
): ExecutionOutcome.GitOperation

// ============================================================================
// Tool: Create Branch
// ============================================================================

private const val CREATE_BRANCH_ID = "git_create_branch"
private const val CREATE_BRANCH_NAME = "Create Branch"
private const val CREATE_BRANCH_DESCRIPTION = """
Creates a new Git branch from a base branch.
Supports naming conventions like feature/ISSUE-123-description.
"""

/**
 * Creates a FunctionTool that creates a new Git branch.
 *
 * @param requiredAgentAutonomy The minimum autonomy level required to use this tool.
 *        Defaults to ACT_WITH_NOTIFICATION since branch creation is reversible
 *        (branches can be deleted) but should notify stakeholders.
 * @return A FunctionTool configured to create Git branches.
 */
fun ToolCreateBranch(
    requiredAgentAutonomy: AgentActionAutonomy = AgentActionAutonomy.ACT_WITH_NOTIFICATION,
): FunctionTool<ExecutionContext.GitOperation> {
    return FunctionTool(
        id = CREATE_BRANCH_ID,
        name = CREATE_BRANCH_NAME,
        description = CREATE_BRANCH_DESCRIPTION,
        requiredAgentAutonomy = requiredAgentAutonomy,
        executionFunction = { executionRequest ->
            require(executionRequest.context.gitRequest.createBranch != null) {
                "ToolCreateBranch requires a createBranch request in the GitOperationRequest"
            }
            executeGitOperation(executionRequest.context)
        },
    )
}

// ============================================================================
// Tool: Stage Files
// ============================================================================

private const val STAGE_FILES_ID = "git_stage"
private const val STAGE_FILES_NAME = "Stage Files"
private const val STAGE_FILES_DESCRIPTION = """
Stages files for commit. Can stage specific files or all modified files.
"""

/**
 * Creates a FunctionTool that stages files for commit.
 *
 * @param requiredAgentAutonomy The minimum autonomy level required to use this tool.
 *        Defaults to FULLY_AUTONOMOUS since staging is a local, reversible operation.
 * @return A FunctionTool configured to stage files.
 */
fun ToolStageFiles(
    requiredAgentAutonomy: AgentActionAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
): FunctionTool<ExecutionContext.GitOperation> {
    return FunctionTool(
        id = STAGE_FILES_ID,
        name = STAGE_FILES_NAME,
        description = STAGE_FILES_DESCRIPTION,
        requiredAgentAutonomy = requiredAgentAutonomy,
        executionFunction = { executionRequest ->
            require(executionRequest.context.gitRequest.stageFiles != null) {
                "ToolStageFiles requires a stageFiles list in the GitOperationRequest"
            }
            executeGitOperation(executionRequest.context)
        },
    )
}

// ============================================================================
// Tool: Commit
// ============================================================================

private const val COMMIT_ID = "git_commit"
private const val COMMIT_NAME = "Commit Changes"
private const val COMMIT_DESCRIPTION = """
Commits staged changes with a message.
Supports conventional commit format and issue references.
"""

/**
 * Creates a FunctionTool that commits staged changes.
 *
 * @param requiredAgentAutonomy The minimum autonomy level required to use this tool.
 *        Defaults to ACT_WITH_NOTIFICATION since commits create history
 *        that should be tracked.
 * @return A FunctionTool configured to commit changes.
 */
fun ToolCommit(
    requiredAgentAutonomy: AgentActionAutonomy = AgentActionAutonomy.ACT_WITH_NOTIFICATION,
): FunctionTool<ExecutionContext.GitOperation> {
    return FunctionTool(
        id = COMMIT_ID,
        name = COMMIT_NAME,
        description = COMMIT_DESCRIPTION,
        requiredAgentAutonomy = requiredAgentAutonomy,
        executionFunction = { executionRequest ->
            require(executionRequest.context.gitRequest.commit != null) {
                "ToolCommit requires a commit request in the GitOperationRequest"
            }
            executeGitOperation(executionRequest.context)
        },
    )
}

// ============================================================================
// Tool: Push
// ============================================================================

private const val PUSH_ID = "git_push"
private const val PUSH_NAME = "Push to Remote"
private const val PUSH_DESCRIPTION = """
Pushes commits to a remote repository.
Can set up tracking for new branches.
"""

/**
 * Creates a FunctionTool that pushes commits to remote.
 *
 * @param requiredAgentAutonomy The minimum autonomy level required to use this tool.
 *        Defaults to ACT_WITH_NOTIFICATION since pushing makes changes
 *        visible to the team.
 * @return A FunctionTool configured to push to remote.
 */
fun ToolPush(
    requiredAgentAutonomy: AgentActionAutonomy = AgentActionAutonomy.ACT_WITH_NOTIFICATION,
): FunctionTool<ExecutionContext.GitOperation> {
    return FunctionTool(
        id = PUSH_ID,
        name = PUSH_NAME,
        description = PUSH_DESCRIPTION,
        requiredAgentAutonomy = requiredAgentAutonomy,
        executionFunction = { executionRequest ->
            require(executionRequest.context.gitRequest.push != null) {
                "ToolPush requires a push request in the GitOperationRequest"
            }
            executeGitOperation(executionRequest.context)
        },
    )
}

// ============================================================================
// Tool: Create Pull Request
// ============================================================================

private const val CREATE_PR_ID = "git_create_pr"
private const val CREATE_PR_NAME = "Create Pull Request"
private const val CREATE_PR_DESCRIPTION = """
Creates a pull request on GitHub.
Supports linking to issues, requesting reviewers, and applying labels.
"""

/**
 * Creates a FunctionTool that creates a pull request.
 *
 * @param requiredAgentAutonomy The minimum autonomy level required to use this tool.
 *        Defaults to ACT_WITH_NOTIFICATION since PRs require team visibility.
 * @return A FunctionTool configured to create pull requests.
 */
fun ToolCreatePullRequest(
    requiredAgentAutonomy: AgentActionAutonomy = AgentActionAutonomy.ACT_WITH_NOTIFICATION,
): FunctionTool<ExecutionContext.GitOperation> {
    return FunctionTool(
        id = CREATE_PR_ID,
        name = CREATE_PR_NAME,
        description = CREATE_PR_DESCRIPTION,
        requiredAgentAutonomy = requiredAgentAutonomy,
        executionFunction = { executionRequest ->
            require(executionRequest.context.gitRequest.createPullRequest != null) {
                "ToolCreatePullRequest requires a createPullRequest request in the GitOperationRequest"
            }
            executeGitOperation(executionRequest.context)
        },
    )
}

// ============================================================================
// Tool: Git Status
// ============================================================================

private const val STATUS_ID = "git_status"
private const val STATUS_NAME = "Git Status"
private const val STATUS_DESCRIPTION = """
Gets the current Git status: branch, staged/modified/untracked files,
and whether there are conflicts.
"""

/**
 * Creates a FunctionTool that gets Git status.
 *
 * @param requiredAgentAutonomy The minimum autonomy level required to use this tool.
 *        Defaults to FULLY_AUTONOMOUS since status is a read-only operation.
 * @return A FunctionTool configured to get Git status.
 */
fun ToolGitStatus(
    requiredAgentAutonomy: AgentActionAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
): FunctionTool<ExecutionContext.GitOperation> {
    return FunctionTool(
        id = STATUS_ID,
        name = STATUS_NAME,
        description = STATUS_DESCRIPTION,
        requiredAgentAutonomy = requiredAgentAutonomy,
        executionFunction = { executionRequest ->
            require(executionRequest.context.gitRequest.getStatus) {
                "ToolGitStatus requires getStatus=true in the GitOperationRequest"
            }
            executeGitOperation(executionRequest.context)
        },
    )
}

// ============================================================================
// Tool: Checkout
// ============================================================================

private const val CHECKOUT_ID = "git_checkout"
private const val CHECKOUT_NAME = "Checkout Branch"
private const val CHECKOUT_DESCRIPTION = """
Checks out an existing branch or creates and checks out a new branch.
"""

/**
 * Creates a FunctionTool that checks out a branch.
 *
 * @param requiredAgentAutonomy The minimum autonomy level required to use this tool.
 *        Defaults to FULLY_AUTONOMOUS since checkout is a local operation.
 * @return A FunctionTool configured to checkout branches.
 */
fun ToolCheckout(
    requiredAgentAutonomy: AgentActionAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
): FunctionTool<ExecutionContext.GitOperation> {
    return FunctionTool(
        id = CHECKOUT_ID,
        name = CHECKOUT_NAME,
        description = CHECKOUT_DESCRIPTION,
        requiredAgentAutonomy = requiredAgentAutonomy,
        executionFunction = { executionRequest ->
            require(executionRequest.context.gitRequest.checkout != null) {
                "ToolCheckout requires a checkout request in the GitOperationRequest"
            }
            executeGitOperation(executionRequest.context)
        },
    )
}

/**
 * Returns all Git-related tools.
 * Useful for registering all Git capabilities with an agent.
 */
fun allGitTools(
    requiredAgentAutonomy: AgentActionAutonomy = AgentActionAutonomy.ACT_WITH_NOTIFICATION,
): List<FunctionTool<ExecutionContext.GitOperation>> = listOf(
    ToolCreateBranch(requiredAgentAutonomy),
    ToolStageFiles(requiredAgentAutonomy),
    ToolCommit(requiredAgentAutonomy),
    ToolPush(requiredAgentAutonomy),
    ToolCreatePullRequest(requiredAgentAutonomy),
    ToolGitStatus(requiredAgentAutonomy),
    ToolCheckout(requiredAgentAutonomy),
)
