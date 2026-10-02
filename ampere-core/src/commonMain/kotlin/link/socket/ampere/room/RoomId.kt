package link.socket.ampere.room

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.events.messages.MessageChannel
import link.socket.ampere.canon.CanonId

/**
 * Identity of a Room: one per project, derived from the project's [CanonId]
 * (AMPR-379).
 *
 * Deterministic on purpose. "One Room per Blueprint Project" is an identity rule,
 * not a lookup, so [forProject] is a pure function and opening a Room twice names
 * the same one. The value is also the Room's [MessageChannel.Room] id, and it
 * carries [MessageChannel.ROOM_PREFIX] so the channel survives a save and a load.
 */
@JvmInline
@Serializable
value class RoomId(val value: String) {

    /** The project this Room belongs to. */
    val projectId: CanonId get() = CanonId(value.removePrefix(MessageChannel.ROOM_PREFIX))

    /** The channel every thread in this Room is on. */
    fun channel(): MessageChannel.Room = MessageChannel.Room(value)

    companion object {
        fun forProject(projectId: CanonId): RoomId = RoomId(MessageChannel.ROOM_PREFIX + projectId.value)

        /** The Room a channel belongs to, or null for any channel that is not a Room. */
        fun of(channel: MessageChannel): RoomId? = (channel as? MessageChannel.Room)?.let { RoomId(it.roomId) }
    }
}
