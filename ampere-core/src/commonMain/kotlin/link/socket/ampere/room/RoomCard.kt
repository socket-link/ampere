package link.socket.ampere.room

import kotlin.time.Duration
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.probe.ProbeId
import link.socket.ampere.probe.Verdict as ProbeVerdict

/**
 * The structured part of a Room post (AMPR-379): what a renderer draws as a card
 * beside the prose. Primitives and Ampere-owned types only; the consumer decides
 * how a card looks.
 *
 * A card is persisted with its message (as JSON in `Message.metadata`) and carried
 * on `RoomEvent.Posted`, so a transcript rebuilt from either source has it.
 */
@Serializable
sealed interface RoomCard {

    /** The Planner's proposed revision, released by the Inspector's review. */
    @Serializable
    @SerialName("RoomCard.PlanRevision")
    data class PlanRevision(
        val revision: Int,
        val remainingItems: Int,
        val remaining: Duration,
        val projectedFinish: Instant?,
        val changes: List<String> = emptyList(),
    ) : RoomCard

    /** One Probe's judgement of one subject, posted by the verifier. */
    @Serializable
    @SerialName("RoomCard.Verdict")
    data class Verdict(
        val probeId: ProbeId,
        val subjectId: String,
        val verdict: ProbeVerdict,
    ) : RoomCard

    /** The Coordinator's standup status: what moved, what is projected, what is blocked. */
    @Serializable
    @SerialName("RoomCard.Status")
    data class Status(
        val headline: String,
        val completed: Int,
        val remaining: Int,
        val blocked: Int,
        val projectedFinish: Instant?,
    ) : RoomCard
}
