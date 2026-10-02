package link.socket.ampere.standup

import link.socket.ampere.room.RoomId

/**
 * Why a standup did not conclude (AMPR-379). Closed, like every typed failure in
 * the repo, and carried across `kotlin.Result` by [StandupException].
 */
sealed interface StandupFailure {

    /** The Room for the project was never opened; a standup has nowhere to post. */
    data class RoomNotOpen(val roomId: RoomId) : StandupFailure

    /** A Room write or an event publish refused; the standup stopped there. */
    data class RoomWrite(val step: String, val reason: String) : StandupFailure

    /** The Inspector's review of the revision could not be recorded. */
    data class ReviewFailed(val reason: String) : StandupFailure
}

/** Carries a [StandupFailure] through [Result.failure]. */
class StandupException(
    val failure: StandupFailure,
    cause: Throwable? = null,
) : Exception("Standup failed: $failure", cause)

/** Shorthand for the Result-typed failure path. */
fun <T> standupFailure(failure: StandupFailure, cause: Throwable? = null): Result<T> =
    Result.failure(StandupException(failure, cause))
