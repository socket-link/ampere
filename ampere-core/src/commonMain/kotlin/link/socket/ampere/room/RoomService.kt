package link.socket.ampere.room

import kotlinx.coroutines.flow.Flow
import link.socket.ampere.agents.domain.event.EventId
import link.socket.ampere.agents.events.messages.MessageId
import link.socket.ampere.agents.events.messages.MessageThreadId
import link.socket.ampere.canon.CanonWorkGraph
import link.socket.ampere.roster.RoleId

/**
 * The Room: one channel per project, a thread per subject, and the human reading
 * and posting alongside the roster (AMPR-379).
 *
 * Bound to Ampere's existing thread primitive — every Room thread is a
 * `MessageThread` on a `MessageChannel.Room`, every post a `Message` — so the
 * SDK's `ThreadService`, the CLI's `thread list`, and `MessageEvent` subscribers
 * see a Room without knowing the word. What the Room adds is identity (a thread is
 * *about* something, and there is one per subject), authorship by role, cards, and
 * the `RoomEvent` family that makes those facts legible in the trace.
 *
 * Every operation returns `Result`: a [RoomFailure] in a [RoomException] for the
 * failures the Room defines, the repository's or the door's own exception for a
 * write that did not land.
 */
interface RoomService {

    /**
     * Open the Room for [graph]'s project, with one thread per milestone in the graph.
     *
     * Idempotent: the Room id is derived from the project id and every thread id from
     * its subject, so a second call finds what the first created and publishes
     * nothing new. `RoomEvent.RoomOpened` fires once, the first time.
     */
    suspend fun open(graph: CanonWorkGraph, causedBy: EventId? = null): Result<RoomId>

    /**
     * The thread for [subject] in [roomId], opened if it does not exist.
     *
     * Idempotent per subject. [assignedTo] and [title] are read only when the thread
     * is created; an existing thread keeps its assignment.
     */
    suspend fun thread(
        roomId: RoomId,
        subject: ThreadSubject,
        assignedTo: RoleId? = null,
        title: String? = null,
        causedBy: EventId? = null,
    ): Result<MessageThreadId>

    /**
     * Post into a Room thread as [author].
     *
     * A role may not post into a thread that is waiting for the human or resolved;
     * the human may post into either, which reopens it. The message is persisted,
     * then `MessageEvent.MessagePosted` and `RoomEvent.Posted` go through the door.
     */
    suspend fun post(
        threadId: MessageThreadId,
        author: Author,
        body: String,
        card: RoomCard? = null,
        causedBy: EventId? = null,
    ): Result<MessageId>

    /** Close a thread. Idempotent on an already-resolved thread. */
    suspend fun resolve(
        threadId: MessageThreadId,
        by: Author,
        reason: String,
        causedBy: EventId? = null,
    ): Result<Unit>

    /** Every thread of the Room, in the order they were opened. */
    suspend fun threads(roomId: RoomId): Result<List<RoomThread>>

    /** Every message in the Room so far, in the order they were posted. */
    suspend fun history(roomId: RoomId): Result<List<RoomMessage>>

    /**
     * The Room's posts as they happen. Per-collector subscription with an explicit
     * overflow policy, like `EventSerialBus.emissions`; nothing posted before
     * collection starts is replayed — read [history] for that.
     */
    fun transcript(roomId: RoomId): Flow<RoomMessage>
}
