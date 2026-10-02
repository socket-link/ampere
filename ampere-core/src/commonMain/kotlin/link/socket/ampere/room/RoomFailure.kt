package link.socket.ampere.room

import link.socket.ampere.agents.events.messages.MessageThreadId
import link.socket.ampere.roster.RoleId

/**
 * Why a Room operation did not happen (AMPR-379).
 *
 * The list is closed; callers can rely on `when` being exhaustive. Carried across a
 * `kotlin.Result` boundary by [RoomException], the shape every typed failure in the
 * repo uses (`ExecuteFailure`/`ExecuteException`, `LinkResolutionFailure`), since
 * no two-parameter `Result` exists here.
 */
sealed interface RoomFailure {

    /** No thread has this id. A Room thread id is deterministic, so this usually means it was never opened. */
    data class ThreadNotFound(val threadId: MessageThreadId) : RoomFailure

    /** The thread exists but is on a channel that is not a Room. */
    data class NotARoomThread(val threadId: MessageThreadId) : RoomFailure

    /**
     * A role tried to post into a thread that is waiting for the human. The human's
     * answer is the next message; a role talking over it would bury the question.
     */
    data class ThreadWaitingForHuman(val threadId: MessageThreadId) : RoomFailure

    /** A role tried to post into a resolved thread. The human may; a role reopens nothing. */
    data class ThreadResolved(val threadId: MessageThreadId) : RoomFailure

    /** No pending review has this id — it was already decided, or never submitted. */
    data class ReviewNotFound(val reviewId: ReviewId) : RoomFailure

    /** The role that answered a review is not the role the roster names as reviewer. */
    data class NotTheReviewer(val reviewId: ReviewId, val expected: RoleId, val actual: RoleId) : RoomFailure

    /** The repository or the event door refused a write; the operation stopped there. */
    data class Persistence(val operation: String, val reason: String) : RoomFailure
}

/** Carries a [RoomFailure] through [Result.failure]. */
class RoomException(
    val failure: RoomFailure,
    cause: Throwable? = null,
) : Exception("Room operation failed: $failure", cause)

/** Shorthand for the Result-typed failure path. */
fun <T> roomFailure(failure: RoomFailure, cause: Throwable? = null): Result<T> =
    Result.failure(RoomException(failure, cause))
