package link.socket.ampere.agents.definition.code

import kotlinx.serialization.Serializable

/**
 * Issue workflow status tracking for CodeAgent.
 *
 * Represents the lifecycle of an issue as it moves through the development workflow:
 * 1. CLAIMED - Agent has claimed the issue
 * 2. IN_PROGRESS - Implementation work is underway
 * 3. BLOCKED - Work is blocked by an error or dependency
 * 4. IN_REVIEW - PR created, awaiting code review
 * 5. CHANGES_REQUESTED - Review feedback requires changes
 * 6. APPROVED - PR approved, ready for merge
 *
 * DONE status is automatic when PR is merged (issue closes via "Closes #N")
 *
 * Each status defines which labels to add and remove, providing GitHub-visible
 * progress tracking.
 */
@Serializable
enum class IssueWorkflowStatus(
    /** Labels to add when transitioning to this status */
    val addLabels: List<String>,

    /** Labels to remove when transitioning to this status */
    val removeLabels: List<String>,

    /** Emoji prefix for status update comments */
    val emoji: String,
) {
    /**
     * Agent has claimed the issue and will start work.
     *
     * Removes availability labels and marks as assigned.
     */
    CLAIMED(
        addLabels = listOf("assigned"),
        removeLabels = listOf("available", "help-wanted"),
        emoji = "👋",
    ),

    /**
     * Active implementation work is in progress.
     *
     * Indicates coding, testing, and commits are happening.
     */
    IN_PROGRESS(
        addLabels = listOf("in-progress"),
        removeLabels = listOf("assigned", "blocked"),
        emoji = "🤖",
    ),

    /**
     * Work is blocked by an error, missing dependency, or external factor.
     *
     * Requires intervention (human or another agent) to unblock.
     */
    BLOCKED(
        addLabels = listOf("blocked"),
        removeLabels = listOf("in-progress"),
        emoji = "⚠️",
    ),

    /**
     * Pull request created, awaiting code review.
     *
     * Reviewers (QA, Security, etc.) should examine the changes.
     */
    IN_REVIEW(
        addLabels = listOf("in-review"),
        removeLabels = listOf("in-progress", "blocked"),
        emoji = "📝",
    ),

    /**
     * Reviewers have requested changes to the PR.
     *
     * Agent should address feedback and update the PR.
     */
    CHANGES_REQUESTED(
        addLabels = listOf("changes-requested"),
        removeLabels = listOf("in-review"),
        emoji = "🔄",
    ),

    /**
     * PR has been approved by reviewers.
     *
     * Ready for merge. Once merged, issue closes automatically.
     */
    APPROVED(
        addLabels = listOf("approved"),
        removeLabels = listOf("in-review", "changes-requested"),
        emoji = "✅",
    ),
    ;

    companion object {
        /**
         * Parse status from label name.
         *
         * Useful for determining current status from issue labels.
         */
        fun fromLabel(label: String): IssueWorkflowStatus? {
            return entries.find { status ->
                status.addLabels.any { it.equals(label, ignoreCase = true) }
            }
        }

        /**
         * Determine status from a set of labels.
         *
         * Returns the "highest" status if multiple workflow labels are present.
         */
        fun fromLabels(labels: List<String>): IssueWorkflowStatus? {
            // Priority order: later statuses override earlier ones
            val statusOrder = listOf(
                CLAIMED,
                IN_PROGRESS,
                BLOCKED,
                IN_REVIEW,
                CHANGES_REQUESTED,
                APPROVED,
            )

            return statusOrder.lastOrNull { status ->
                status.addLabels.any { label ->
                    labels.contains(label)
                }
            }
        }
    }
}
