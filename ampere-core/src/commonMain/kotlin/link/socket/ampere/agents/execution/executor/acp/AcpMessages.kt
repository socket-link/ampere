@file:OptIn(ExperimentalSerializationApi::class)

package link.socket.ampere.agents.execution.executor.acp

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The one `Json` for the Agent Client Protocol wire (AMPR-490).
 *
 * Lenient on the way in — an agent may send fields this client has no type for, and a
 * field it leaves out decodes as null — and quiet on the way out: a default is not written,
 * so a request carries only what was set. The envelope's `jsonrpc` is the one default that
 * is always written, by its own annotation.
 */
val AcpJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

/** The protocol version this client speaks. An integer, incremented only for breaking changes. */
const val ACP_PROTOCOL_VERSION: Int = 1

/** The JSON-RPC method names ACP defines, in both directions. */
object AcpMethods {
    // client -> agent
    const val INITIALIZE: String = "initialize"
    const val SESSION_NEW: String = "session/new"
    const val SESSION_PROMPT: String = "session/prompt"
    const val SESSION_CANCEL: String = "session/cancel"

    // agent -> client
    const val SESSION_UPDATE: String = "session/update"
    const val SESSION_REQUEST_PERMISSION: String = "session/request_permission"
    const val FS_READ_TEXT_FILE: String = "fs/read_text_file"
    const val FS_WRITE_TEXT_FILE: String = "fs/write_text_file"
}

// ==================== JSON-RPC 2.0 envelope ====================

/**
 * One JSON-RPC 2.0 message as it travels on one line.
 *
 * One shape for the three kinds, told apart by which fields are present: a request has a
 * `method` and an `id`, a notification a `method` and no `id`, a response an `id` and no
 * `method`. The `id` is kept as a [JsonElement] because the agent's own request ids are
 * whatever it chose, and a response has to echo them exactly.
 *
 * Field order is the wire order: `jsonrpc`, `id`, `method`, then the payload. A line-oriented
 * reader on the other side may rely on it.
 */
@Serializable
data class JsonRpcMessage(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val jsonrpc: String = JSON_RPC_VERSION,
    val id: JsonElement? = null,
    val method: String? = null,
    val params: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
) {

    val isRequest: Boolean get() = method != null && id != null
    val isNotification: Boolean get() = method != null && id == null
    val isResponse: Boolean get() = method == null && id != null

    companion object {
        const val JSON_RPC_VERSION: String = "2.0"

        fun request(id: JsonElement, method: String, params: JsonElement?): JsonRpcMessage =
            JsonRpcMessage(id = id, method = method, params = params)

        fun notification(method: String, params: JsonElement?): JsonRpcMessage =
            JsonRpcMessage(method = method, params = params)

        fun result(id: JsonElement, result: JsonElement): JsonRpcMessage =
            JsonRpcMessage(id = id, result = result)

        fun error(id: JsonElement, error: JsonRpcError): JsonRpcMessage =
            JsonRpcMessage(id = id, error = error)
    }
}

/** A JSON-RPC error object. */
@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
) {
    companion object {
        const val PARSE_ERROR: Int = -32700
        const val INVALID_REQUEST: Int = -32600
        const val METHOD_NOT_FOUND: Int = -32601
        const val INVALID_PARAMS: Int = -32602
        const val INTERNAL_ERROR: Int = -32603

        /** This client's own code: the file access scope refused the path (AMPR-492). */
        const val FENCE_REFUSED: Int = -32001
    }
}

// ==================== initialize ====================

@Serializable
data class FsCapabilities(
    val readTextFile: Boolean = false,
    val writeTextFile: Boolean = false,
)

@Serializable
data class ClientCapabilities(
    val fs: FsCapabilities = FsCapabilities(),
    val terminal: Boolean = false,
)

/** `clientInfo` and `agentInfo`: who is on each end of the connection. */
@Serializable
data class Implementation(
    val name: String,
    val title: String? = null,
    val version: String? = null,
)

@Serializable
data class InitializeParams(
    val protocolVersion: Int,
    val clientCapabilities: ClientCapabilities = ClientCapabilities(),
    val clientInfo: Implementation? = null,
)

@Serializable
data class PromptCapabilities(
    val image: Boolean = false,
    val audio: Boolean = false,
    val embeddedContext: Boolean = false,
)

@Serializable
data class AgentCapabilities(
    val loadSession: Boolean = false,
    val promptCapabilities: PromptCapabilities = PromptCapabilities(),
)

@Serializable
data class InitializeResult(
    val protocolVersion: Int,
    val agentCapabilities: AgentCapabilities = AgentCapabilities(),
    val agentInfo: Implementation? = null,
)

// ==================== session/new ====================

@Serializable
data class NewSessionParams(
    /** Absolute path. The agent must use it as its working directory wherever it was spawned. */
    val cwd: String,
    /** MCP servers the agent should connect to. Required by the spec, always empty from this client. */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val mcpServers: List<JsonElement> = emptyList(),
)

@Serializable
data class NewSessionResult(
    val sessionId: String,
)

// ==================== session/prompt ====================

/**
 * One block of a prompt or of a streamed message. Only `text` is ever sent by this client;
 * the other fields exist so an agent's block of another kind still decodes.
 */
@Serializable
data class ContentBlock(
    val type: String,
    val text: String? = null,
    val data: String? = null,
    val mimeType: String? = null,
    val uri: String? = null,
    val name: String? = null,
) {
    companion object {
        const val TEXT: String = "text"

        fun text(text: String): ContentBlock = ContentBlock(type = TEXT, text = text)
    }
}

@Serializable
data class PromptParams(
    val sessionId: String,
    val prompt: List<ContentBlock>,
)

/**
 * Why a turn ended. Kept as the wire string so a value this client does not know decodes
 * rather than fails; [StopReason.of] names the ones it does.
 */
@Serializable
data class PromptResult(
    val stopReason: String,
) {
    val reason: StopReason? get() = StopReason.of(stopReason)
}

enum class StopReason(val wire: String) {
    END_TURN("end_turn"),
    MAX_TOKENS("max_tokens"),
    MAX_TURN_REQUESTS("max_turn_requests"),
    REFUSAL("refusal"),
    CANCELLED("cancelled"),
    ;

    companion object {
        fun of(wire: String): StopReason? = entries.firstOrNull { it.wire == wire }
    }
}

@Serializable
data class CancelParams(
    val sessionId: String,
)

// ==================== session/update ====================

@Serializable
data class SessionNotificationParams(
    val sessionId: String,
    val update: SessionUpdate,
)

/**
 * What an agent streams during a turn, discriminated on `sessionUpdate`.
 *
 * Four kinds are typed because the executor reads them; every other kind — thought chunks,
 * user echoes, mode and command updates, kinds added after this was written — decodes as
 * [Other] and is ignored, never fatal. `kind` and `status` on a tool call are kept as
 * strings for the same reason: a value this client does not know is still a value.
 */
@Serializable(with = SessionUpdateSerializer::class)
sealed interface SessionUpdate {

    val sessionUpdate: String

    @Serializable
    data class AgentMessageChunk(
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        override val sessionUpdate: String = AGENT_MESSAGE_CHUNK,
        val content: ContentBlock,
    ) : SessionUpdate

    @Serializable
    data class ToolCall(
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        override val sessionUpdate: String = TOOL_CALL,
        val toolCallId: String,
        val title: String,
        val kind: String? = null,
        val status: String? = null,
        val content: List<ToolCallContent> = emptyList(),
        val locations: List<ToolCallLocation> = emptyList(),
        val rawInput: JsonElement? = null,
        val rawOutput: JsonElement? = null,
    ) : SessionUpdate

    /** Only the fields that changed are sent; everything but the id is optional. */
    @Serializable
    data class ToolCallUpdate(
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        override val sessionUpdate: String = TOOL_CALL_UPDATE,
        val toolCallId: String,
        val title: String? = null,
        val kind: String? = null,
        val status: String? = null,
        val content: List<ToolCallContent>? = null,
        val locations: List<ToolCallLocation>? = null,
        val rawInput: JsonElement? = null,
        val rawOutput: JsonElement? = null,
    ) : SessionUpdate

    @Serializable
    data class Plan(
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        override val sessionUpdate: String = PLAN,
        val entries: List<PlanEntry> = emptyList(),
    ) : SessionUpdate

    /** A kind this client has no type for. */
    @Serializable
    data class Other(
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        override val sessionUpdate: String = "unknown",
    ) : SessionUpdate

    companion object {
        const val AGENT_MESSAGE_CHUNK: String = "agent_message_chunk"
        const val TOOL_CALL: String = "tool_call"
        const val TOOL_CALL_UPDATE: String = "tool_call_update"
        const val PLAN: String = "plan"
    }
}

/** Picks the typed update for a `sessionUpdate` value, and [SessionUpdate.Other] for the rest. */
object SessionUpdateSerializer : JsonContentPolymorphicSerializer<SessionUpdate>(SessionUpdate::class) {

    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<SessionUpdate> =
        when ((element as? JsonObject)?.get("sessionUpdate")?.jsonPrimitive?.contentOrNull) {
            SessionUpdate.AGENT_MESSAGE_CHUNK -> SessionUpdate.AgentMessageChunk.serializer()
            SessionUpdate.TOOL_CALL -> SessionUpdate.ToolCall.serializer()
            SessionUpdate.TOOL_CALL_UPDATE -> SessionUpdate.ToolCallUpdate.serializer()
            SessionUpdate.PLAN -> SessionUpdate.Plan.serializer()
            else -> SessionUpdate.Other.serializer()
        }
}

/** The `kind` values ACP defines for a tool call. The fence reads them (AMPR-492). */
object ToolKind {
    const val READ: String = "read"
    const val EDIT: String = "edit"
    const val DELETE: String = "delete"
    const val MOVE: String = "move"
    const val SEARCH: String = "search"
    const val EXECUTE: String = "execute"
    const val THINK: String = "think"
    const val FETCH: String = "fetch"
    const val SWITCH_MODE: String = "switch_mode"
    const val OTHER: String = "other"

    /** The kinds that change the working tree. */
    val WRITING: Set<String> = setOf(EDIT, DELETE, MOVE)

    /** The kinds that only read it. */
    val READING: Set<String> = setOf(READ, SEARCH)
}

/** The `status` values ACP defines for a tool call. */
object ToolCallStatus {
    const val PENDING: String = "pending"
    const val IN_PROGRESS: String = "in_progress"
    const val COMPLETED: String = "completed"
    const val FAILED: String = "failed"
}

@Serializable
data class ToolCallLocation(
    /** Absolute. */
    val path: String,
    val line: Int? = null,
)

/**
 * One item of a tool call's `content`, flattened over its three variants: `content` carries
 * a [ContentBlock], `diff` carries [path], [oldText] (null for a new file) and [newText],
 * `terminal` carries [terminalId].
 */
@Serializable
data class ToolCallContent(
    val type: String,
    val content: ContentBlock? = null,
    val path: String? = null,
    val oldText: String? = null,
    val newText: String? = null,
    val terminalId: String? = null,
) {
    companion object {
        const val CONTENT: String = "content"
        const val DIFF: String = "diff"
        const val TERMINAL: String = "terminal"
    }
}

@Serializable
data class PlanEntry(
    val content: String,
    val priority: String? = null,
    val status: String? = null,
)

// ==================== session/request_permission ====================

/** The tool call a permission request is about: a [SessionUpdate.ToolCallUpdate] without the discriminator. */
@Serializable
data class PermissionToolCall(
    val toolCallId: String,
    val title: String? = null,
    val kind: String? = null,
    val status: String? = null,
    val content: List<ToolCallContent>? = null,
    val locations: List<ToolCallLocation>? = null,
    val rawInput: JsonElement? = null,
)

@Serializable
data class PermissionOption(
    val optionId: String,
    val name: String,
    val kind: String,
)

/** The `kind` values a permission option can carry. The fence chooses by kind, never by id. */
object PermissionOptionKind {
    const val ALLOW_ONCE: String = "allow_once"
    const val ALLOW_ALWAYS: String = "allow_always"
    const val REJECT_ONCE: String = "reject_once"
    const val REJECT_ALWAYS: String = "reject_always"
}

@Serializable
data class RequestPermissionParams(
    val sessionId: String,
    val toolCall: PermissionToolCall,
    val options: List<PermissionOption>,
)

@Serializable
data class PermissionOutcome(
    val outcome: String,
    val optionId: String? = null,
) {
    companion object {
        const val SELECTED: String = "selected"
        const val CANCELLED: String = "cancelled"

        fun selected(optionId: String): PermissionOutcome = PermissionOutcome(SELECTED, optionId)

        fun cancelled(): PermissionOutcome = PermissionOutcome(CANCELLED)
    }
}

@Serializable
data class RequestPermissionResult(
    val outcome: PermissionOutcome,
)

// ==================== fs/… ====================

@Serializable
data class ReadTextFileParams(
    val sessionId: String,
    /** Absolute. */
    val path: String,
    /** 1-based line to start from. */
    val line: Int? = null,
    /** Maximum number of lines. */
    val limit: Int? = null,
)

@Serializable
data class ReadTextFileResult(
    val content: String,
)

@Serializable
data class WriteTextFileParams(
    val sessionId: String,
    /** Absolute. The client creates the file when it does not exist. */
    val path: String,
    val content: String,
)
