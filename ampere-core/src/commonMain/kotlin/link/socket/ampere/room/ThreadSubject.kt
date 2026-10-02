package link.socket.ampere.room

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.events.messages.MessageThreadId
import link.socket.ampere.canon.CanonId

/**
 * What a Room thread is about (AMPR-379).
 *
 * `MessageThread` has no subject field and random ids, so "a thread per Milestone"
 * and "a thread per verdict" had nowhere to live. The subject supplies both: its
 * [key] is stable, and [roomThreadId] turns it into the thread's id, which is what
 * makes `RoomService.thread` idempotent per subject with no index and no new table.
 */
@Serializable
sealed interface ThreadSubject {

    /** Stable, unique within a Room; the tail of the thread id. */
    val key: String

    /** The Room's own thread: status posts and standup narratives land here. */
    @Serializable
    @SerialName("ThreadSubject.General")
    data object General : ThreadSubject {
        override val key: String get() = "general"
    }

    /** One milestone of the project, opened when the Room is. */
    @Serializable
    @SerialName("ThreadSubject.Milestone")
    data class Milestone(val milestoneId: CanonId) : ThreadSubject {
        override val key: String get() = "milestone:${milestoneId.value}"
    }

    /**
     * One Probe verdict that needs resolving, keyed by the subject the Probe judged
     * and the kind of verdict. A `Violated` and an `Undetermined` on the same subject
     * are different conversations: one has evidence that fails, the other has none.
     */
    @Serializable
    @SerialName("ThreadSubject.Verdict")
    data class Verdict(val subjectId: String, val kind: VerdictKind) : ThreadSubject {
        override val key: String get() = "verdict:${kind.name.lowercase()}:$subjectId"
    }
}

/** The two verdict values that open a thread. `Holds` closes one; `Warn` opens none. */
@Serializable
enum class VerdictKind {
    VIOLATED,
    UNDETERMINED,
}

/** The id of the thread for [subject] in [roomId]: deterministic, so the lookup is the id. */
fun roomThreadId(roomId: RoomId, subject: ThreadSubject): MessageThreadId = "${roomId.value}/${subject.key}"
