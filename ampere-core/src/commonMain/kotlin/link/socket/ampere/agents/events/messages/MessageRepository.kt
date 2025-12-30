package link.socket.ampere.agents.events.messages

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.domain.status.EventStatus
import link.socket.ampere.data.Repository
import link.socket.ampere.db.Database
import link.socket.ampere.db.messages.MessageStoreQueries

/**
 * Repository responsible for persisting and querying Messages using SQLDelight.
 *
 * This lives in common code and works across KMP targets. Callers are responsible for
 * providing a platform-specific SQLDelight [SqlDriver] to construct the generated [link.socket.ampere.agents.messages.Database]
 * instance and then pass it into this repository.
 */
// TODO: Remove duplication
class MessageRepository(
    override val json: Json,
    override val scope: CoroutineScope,
    private val database: Database,
) : Repository<MessageId, Message>(json, scope) {

    override val tag: String = "Message${super.tag}"

    private val queries: MessageStoreQueries
        get() = database.messageStoreQueries

    suspend fun saveThread(thread: MessageThread): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                queries.insertConversation(
                    id = thread.id,
                    channelId = thread.channel.getIdentifier(),
                    createdById = thread.createdBy.getIdentifier(),
                    status = thread.status.name,
                    createdAt = thread.createdAt.toEpochMilliseconds(),
                    updatedAt = thread.updatedAt.toEpochMilliseconds(),
                )
                thread.participants.forEach { participant ->
                    queries.insertParticipant(
                        threadId = thread.id,
                        participantId = participant.getIdentifier(),
                    )
                }
                thread.messages.forEach { message ->
                    queries.insertMessage(
                        id = message.id,
                        threadId = thread.id,
                        senderId = message.sender.getIdentifier(),
                        content = message.content,
                        timestamp = message.timestamp.toEpochMilliseconds(),
                        metadata = message.metadata?.let { metadata ->
                            json.encodeToString(metadata)
                        },
                    )
                }
            }
        }

    suspend fun findThreadById(threadId: String): Result<MessageThread> =
        withContext(Dispatchers.IO) {
            runCatching {
                val messageThread = queries
                    .selectMessageThreadById(threadId)
                    .executeAsOneOrNull()

                requireNotNull(messageThread) {
                    throw IllegalArgumentException("Thread not found: $threadId")
                }

                val participants = queries
                    .selectParticipantsByThreadId(threadId)
                    .executeAsList()

                val messages = queries
                    .selectMessagesByThreadId(threadId)
                    .executeAsList()
                    .map { message ->
                        Message(
                            id = message.id,
                            threadId = message.threadId,
                            sender = MessageSender.fromSenderId(message.senderId),
                            content = message.content,
                            timestamp = Instant.Companion.fromEpochMilliseconds(message.timestamp),
                            metadata = message.metadata?.let { metadata ->
                                json.decodeFromString<Map<String, String>>(metadata)
                            },
                        )
                    }

                MessageThread(
                    id = messageThread.id,
                    channel = MessageChannel.fromMessageChannelId(messageThread.channelId),
                    createdBy = MessageSender.fromSenderId(messageThread.createdById),
                    participants = participants.map { participantId ->
                        MessageSender.fromSenderId(participantId)
                    }.toSet(),
                    messages = messages,
                    status = EventStatus.fromName(messageThread.status),
                    createdAt = Instant.Companion.fromEpochMilliseconds(messageThread.createdAt),
                    updatedAt = Instant.Companion.fromEpochMilliseconds(messageThread.updatedAt),
                )
            }
        }

    suspend fun findAllThreads(): Result<List<MessageThread>> =
        withContext(Dispatchers.IO) {
            runCatching {
                queries
                    .selectAllMessageThreads()
                    .executeAsList()
                    .map { messageThread ->
                        val participants = queries
                            .selectParticipantsByThreadId(messageThread.id)
                            .executeAsList()

                        val messages = queries
                            .selectMessagesByThreadId(messageThread.id)
                            .executeAsList()
                            .map { message ->
                                Message(
                                    id = message.id,
                                    threadId = message.threadId,
                                    sender = MessageSender.fromSenderId(message.senderId),
                                    content = message.content,
                                    timestamp = Instant.Companion.fromEpochMilliseconds(message.timestamp),
                                    metadata = message.metadata?.let { metadata ->
                                        json.decodeFromString<Map<String, String>>(metadata)
                                    },
                                )
                            }

                        MessageThread(
                            id = messageThread.id,
                            channel = MessageChannel.fromMessageChannelId(messageThread.channelId),
                            createdBy = MessageSender.fromSenderId(messageThread.createdById),
                            participants = participants.map { participantId ->
                                MessageSender.fromSenderId(participantId)
                            }.toSet(),
                            messages = messages,
                            status = EventStatus.fromName(messageThread.status),
                            createdAt = Instant.Companion.fromEpochMilliseconds(messageThread.createdAt),
                            updatedAt = Instant.Companion.fromEpochMilliseconds(messageThread.updatedAt),
                        )
                    }
            }
        }

    suspend fun addMessageToThread(threadId: MessageThreadId, message: Message): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                queries.insertMessage(
                    id = message.id,
                    threadId = threadId,
                    senderId = message.sender.getIdentifier(),
                    content = message.content,
                    timestamp = message.timestamp.toEpochMilliseconds(),
                    metadata = message.metadata?.let { metadata ->
                        json.encodeToString(metadata)
                    },
                )

                // Nested catching in case the participant already exists; ignore if already present
                runCatching {
                    queries.insertParticipant(
                        threadId = threadId,
                        participantId = message.sender.getIdentifier(),
                    )
                }

                queries.updateMessageThreadStatus(
                    id = threadId,
                    status = EventStatus.Open.name, // keep status unless external change; OPEN ensures active
                    updatedAt = message.timestamp.toEpochMilliseconds(),
                )
            }.map { }
        }

    suspend fun updateStatus(threadId: MessageThreadId, newStatus: EventStatus): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                queries.updateMessageThreadStatus(
                    id = threadId,
                    status = newStatus.name,
                    updatedAt = Clock.System.now().toEpochMilliseconds(),
                )
            }.map { }
        }

    suspend fun delete(threadId: MessageThreadId): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                queries.deleteMessageThread(threadId)
            }.map { }
        }
}
