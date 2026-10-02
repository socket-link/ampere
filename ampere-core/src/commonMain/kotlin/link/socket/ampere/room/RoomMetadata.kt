package link.socket.ampere.room

import kotlinx.serialization.json.Json
import link.socket.ampere.agents.events.messages.Message
import link.socket.ampere.agents.events.messages.MessageThread
import link.socket.ampere.roster.RoleId

/**
 * How the Room keeps its facts inside the thread primitive's own storage
 * (AMPR-379).
 *
 * `Message.metadata` is a persisted `Map<String, String>`, so a thread's subject and
 * assignment ride on its opening message and a post's card rides on the post. No
 * schema change, and a row read by something that does not know the Room is still
 * an ordinary message. The keys are a wire contract: a stored Room decodes through
 * them.
 */
internal object RoomMetadata {

    const val SUBJECT_KEY: String = "room.subject"
    const val ASSIGNED_KEY: String = "room.assigned"
    const val CARD_KEY: String = "room.card"

    fun opening(subject: ThreadSubject, assignedTo: RoleId?, json: Json): Map<String, String> = buildMap {
        put(SUBJECT_KEY, json.encodeToString(ThreadSubject.serializer(), subject))
        assignedTo?.let { put(ASSIGNED_KEY, it.value) }
    }

    fun card(card: RoomCard?, json: Json): Map<String, String>? =
        card?.let { mapOf(CARD_KEY to json.encodeToString(RoomCard.serializer(), it)) }

    fun subjectOf(thread: MessageThread, json: Json): ThreadSubject? =
        thread.messages.firstOrNull()?.metadata?.get(SUBJECT_KEY)?.let { encoded ->
            runCatching { json.decodeFromString(ThreadSubject.serializer(), encoded) }.getOrNull()
        }

    fun assignedTo(thread: MessageThread): RoleId? =
        thread.messages.firstOrNull()?.metadata?.get(ASSIGNED_KEY)?.let(::RoleId)

    fun cardOf(message: Message, json: Json): RoomCard? =
        message.metadata?.get(CARD_KEY)?.let { encoded ->
            runCatching { json.decodeFromString(RoomCard.serializer(), encoded) }.getOrNull()
        }
}
