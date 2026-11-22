package link.socket.ampere.agents.tools.mcp.connection

/**
 * One server-sent event.
 *
 * @property type The `event:` field, or `message` when the event named none.
 * @property data The `data:` lines joined with newlines. May be empty: a server
 *   may send an event that carries only an [id].
 * @property id The last `id:` seen on the stream, which an event inherits when
 *   it names none of its own.
 */
internal data class SseEvent(
    val type: String,
    val data: String,
    val id: String?,
)

/**
 * The `text/event-stream` framing, one line at a time (AMPR-514).
 *
 * Follows the WHATWG event-stream interpretation: a blank line ends an event,
 * a line starting with `:` is a comment, one space after a field's colon is
 * not part of its value, and an event with no `data:` line is not an event.
 *
 * `retry:` is read and ignored. It tells a client how long to wait before
 * reconnecting to a stream, and this client does not resume streams.
 */
internal class SseParser {

    private var type: String? = null
    private val data = StringBuilder()
    private var hasData = false
    private var lastEventId: String? = null
    private var atStart = true

    /**
     * Take the next line of the stream, without its terminator. Returns the
     * event this line completed, or null when it did not complete one.
     */
    fun feed(line: String): SseEvent? {
        val content = if (atStart) line.removePrefix(BYTE_ORDER_MARK) else line
        atStart = false

        if (content.isEmpty()) return dispatch()
        if (content.startsWith(':')) return null

        val colon = content.indexOf(':')
        val field = if (colon < 0) content else content.substring(0, colon)
        val value = if (colon < 0) "" else content.substring(colon + 1).removePrefix(" ")

        when (field) {
            "event" -> type = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            // An id containing NUL is ignored, per the interpretation rules.
            "id" -> if ('\u0000' !in value) lastEventId = value
        }
        return null
    }

    /**
     * The event left unfinished when the stream ended, or null.
     *
     * A browser discards it. This returns it instead: a server that writes its
     * answer and closes without the final blank line has still answered.
     */
    fun finish(): SseEvent? = dispatch()

    private fun dispatch(): SseEvent? {
        val event = if (hasData) {
            SseEvent(
                type = type?.takeIf { it.isNotEmpty() } ?: DEFAULT_EVENT_TYPE,
                data = data.toString(),
                id = lastEventId,
            )
        } else {
            null
        }
        type = null
        data.clear()
        hasData = false
        return event
    }

    companion object {
        const val DEFAULT_EVENT_TYPE = "message"
        private const val BYTE_ORDER_MARK = "﻿"
    }
}
