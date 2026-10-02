package link.socket.ampere.room

import kotlin.jvm.JvmInline
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.event.EventId
import link.socket.ampere.agents.domain.event.RoomEvent
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.messages.MessageId
import link.socket.ampere.agents.events.messages.MessageThreadId
import link.socket.ampere.probe.ProbeReport
import link.socket.ampere.probe.Verdict as ProbeVerdict
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.Roster
import link.socket.ampere.util.randomUUID

/** Identity of one pending review. */
@JvmInline
@Serializable
value class ReviewId(val value: String)

/**
 * The open-for-review path for Room cards (AMPR-379): a card from a role the
 * roster says is reviewed is held until the reviewer posts a verdict on it.
 *
 * The reviewer's judgement is a Probe's: the Inspector reviews a plan revision by
 * running the sequence Probe over it and a part by running its checks, and the
 * [ProbeReport] it hands back is what releases or withholds the card. `Holds` and
 * `Warn` release; `Violated` and `Undetermined` withhold — an undetermined review
 * is not a soft pass here either.
 *
 * Both outcomes post the reviewer's `Verdict` card into the thread, so the Room
 * shows *why* a revision appeared or did not. A released card is posted as its
 * original author.
 *
 * Pending reviews are held in memory: a review is decided inside the run that
 * submitted it (the Standup submits and reviews in one pass), and the request is
 * on the bus as `RoomEvent.ReviewRequested` for anything that needs to know it was
 * made.
 */
class ReviewGate(
    private val room: RoomService,
    private val roster: Roster,
    private val eventApi: AgentEventApi,
    private val clock: Clock = Clock.System,
    private val idGenerator: () -> String = { randomUUID() },
) {

    data class PendingReview(
        val reviewId: ReviewId,
        val roomId: RoomId,
        val threadId: MessageThreadId,
        val author: Author.Role,
        val body: String,
        val card: RoomCard,
        val reviewer: RoleId,
    )

    sealed interface Submission {
        /** No reviewer for this author: the card reached the Room directly. */
        data class Released(val messageId: MessageId) : Submission

        /** Held until [reviewer] answers [review] for [reviewId]. */
        data class Pending(val reviewId: ReviewId, val reviewer: RoleId) : Submission
    }

    sealed interface ReviewOutcome {
        val verdictMessageId: MessageId

        data class Released(val messageId: MessageId, override val verdictMessageId: MessageId) : ReviewOutcome

        data class Withheld(override val verdictMessageId: MessageId, val reason: String?) : ReviewOutcome
    }

    private val mutex = Mutex()
    private val pending = mutableMapOf<ReviewId, PendingReview>()

    fun pending(): List<PendingReview> = pending.values.toList()

    suspend fun submit(
        roomId: RoomId,
        threadId: MessageThreadId,
        author: Author.Role,
        body: String,
        card: RoomCard,
        causedBy: EventId? = null,
    ): Result<Submission> {
        val reviewer = roster.reviewerOf(author.id)
            ?: return room.post(threadId, author, body, card, causedBy).map { Submission.Released(it) }

        val review = PendingReview(
            reviewId = ReviewId(idGenerator()),
            roomId = roomId,
            threadId = threadId,
            author = author,
            body = body,
            card = card,
            reviewer = reviewer,
        )
        mutex.withLock { pending[review.reviewId] = review }

        return eventApi.publish(
            RoomEvent.ReviewRequested(
                eventId = idGenerator(),
                timestamp = clock.now(),
                eventSource = author.eventSource(),
                roomId = roomId.value,
                threadId = threadId,
                reviewId = review.reviewId,
                author = author.id,
                reviewer = reviewer,
                card = card,
            ),
            causedBy = causedBy,
        ).map { Submission.Pending(review.reviewId, reviewer) }
    }

    suspend fun review(
        reviewId: ReviewId,
        reviewer: Author.Role,
        report: ProbeReport,
        causedBy: EventId? = null,
    ): Result<ReviewOutcome> {
        val review = mutex.withLock { pending[reviewId] }
            ?: return roomFailure(RoomFailure.ReviewNotFound(reviewId))
        if (reviewer.id != review.reviewer) {
            return roomFailure(RoomFailure.NotTheReviewer(reviewId, expected = review.reviewer, actual = reviewer.id))
        }

        val released = when (report.verdict) {
            is ProbeVerdict.Holds, is ProbeVerdict.Warn -> true
            is ProbeVerdict.Violated, is ProbeVerdict.Undetermined -> false
        }

        val verdictCard = RoomCard.Verdict(report.probeId, report.subjectId, report.verdict)
        val verdictBody = if (released) {
            "Review of ${review.author.id.value}'s card: ${report.probeId.value} holds; released"
        } else {
            "Review of ${review.author.id.value}'s card: ${report.probeId.value} " +
                "${report.verdict.reason ?: "did not hold"}; withheld"
        }
        val verdictMessageId = room.post(review.threadId, reviewer, verdictBody, verdictCard, causedBy)
            .getOrElse { return Result.failure(it) }

        val releasedMessageId = if (released) {
            room.post(review.threadId, review.author, review.body, review.card, causedBy)
                .getOrElse { return Result.failure(it) }
        } else {
            null
        }

        mutex.withLock { pending.remove(reviewId) }

        eventApi.publish(
            RoomEvent.ReviewCompleted(
                eventId = idGenerator(),
                timestamp = clock.now(),
                eventSource = reviewer.eventSource(),
                roomId = review.roomId.value,
                threadId = review.threadId,
                reviewId = reviewId,
                reviewer = reviewer.id,
                probeId = report.probeId,
                verdict = report.verdict,
                released = released,
                messageId = releasedMessageId,
            ),
            causedBy = causedBy,
        ).onFailure { return Result.failure(it) }

        return Result.success(
            if (releasedMessageId != null) {
                ReviewOutcome.Released(releasedMessageId, verdictMessageId)
            } else {
                ReviewOutcome.Withheld(verdictMessageId, report.verdict.reason)
            },
        )
    }
}
