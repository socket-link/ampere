package link.socket.ampere.room

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.events.messages.MessageThreadId
import link.socket.ampere.canon.CanonId
import link.socket.ampere.probe.safety.HazardCategory

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

    /**
     * One hazard finding the plan carries, keyed by the Task or manifest line it
     * was found on and the category found there (AMPR-380).
     *
     * A hazard is `Warn` — decided, and not disqualifying — so it opens no
     * [Verdict] thread: a Room that shouted every `Warn` would be a Room nobody
     * reads. It gets its own subject instead, because a hazard is a different
     * conversation from a failed check: the plan is right and still needs a step
     * that makes it safe, and the mitigation Task is what the thread is about.
     * One thread per finding, so two hazards on one Task are two conversations.
     */
    @Serializable
    @SerialName("ThreadSubject.Hazard")
    data class Hazard(val subjectId: String, val category: HazardCategory) : ThreadSubject {
        override val key: String get() = "hazard:${category.name.lowercase()}:$subjectId"
    }
}

/**
 * The two verdict values that open a [ThreadSubject.Verdict] thread. `Holds`
 * closes one; `Warn` opens none — a hazard `Warn` opens a
 * [ThreadSubject.Hazard], which is a different subject and not a verdict.
 */
@Serializable
enum class VerdictKind {
    VIOLATED,
    UNDETERMINED,
}

/** The id of the thread for [subject] in [roomId]: deterministic, so the lookup is the id. */
fun roomThreadId(roomId: RoomId, subject: ThreadSubject): MessageThreadId = "${roomId.value}/${subject.key}"
