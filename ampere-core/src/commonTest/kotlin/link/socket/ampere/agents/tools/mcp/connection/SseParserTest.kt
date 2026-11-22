package link.socket.ampere.agents.tools.mcp.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The `text/event-stream` framing rules [SseParser] follows (AMPR-514). */
class SseParserTest {

    private fun events(vararg lines: String): List<SseEvent> {
        val parser = SseParser()
        return lines.mapNotNull(parser::feed)
    }

    @Test
    fun `a blank line ends an event`() {
        val parser = SseParser()

        assertNull(parser.feed("event: message"))
        assertNull(parser.feed("data: {}"))

        assertEquals(SseEvent(type = "message", data = "{}", id = null), parser.feed(""))
    }

    @Test
    fun `an event that names no type is a message`() {
        assertEquals(listOf(SseEvent("message", "one", null)), events("data: one", ""))
    }

    @Test
    fun `data lines are joined with newlines`() {
        assertEquals(
            listOf(SseEvent("message", "{\n\"a\": 1\n}", null)),
            events("data: {", "data: \"a\": 1", "data: }", ""),
        )
    }

    @Test
    fun `only one space after the colon belongs to the framing`() {
        assertEquals(
            listOf(SseEvent("message", " indented", null), SseEvent("message", "tight", null)),
            events("data:  indented", "", "data:tight", ""),
        )
    }

    @Test
    fun `a comment line is not part of any event`() {
        assertEquals(
            listOf(SseEvent("message", "one", null)),
            events(": keep-alive", "data: one", ": another", ""),
        )
    }

    @Test
    fun `an event with no data line is not an event`() {
        assertEquals(emptyList(), events("event: ping", "", "retry: 3000", "", ""))
    }

    @Test
    fun `an event may carry an empty data line and an id`() {
        // What a server sends to give a client something to resume from. It is an event; it
        // holds no message, which is for the reader of the event to notice.
        assertEquals(listOf(SseEvent("message", "", "evt-1")), events("id: evt-1", "data:", ""))
    }

    @Test
    fun `an id stays in force for the events after it`() {
        assertEquals(
            listOf(SseEvent("message", "one", "evt-1"), SseEvent("message", "two", "evt-1")),
            events("id: evt-1", "data: one", "", "data: two", ""),
        )
    }

    @Test
    fun `the event type does not carry over to the next event`() {
        assertEquals(
            listOf(SseEvent("endpoint", "/messages", null), SseEvent("message", "two", null)),
            events("event: endpoint", "data: /messages", "", "data: two", ""),
        )
    }

    @Test
    fun `a field with no colon has an empty value`() {
        assertEquals(listOf(SseEvent("message", "", null)), events("data", ""))
    }

    @Test
    fun `a byte order mark at the start of the stream is not part of the first field`() {
        assertEquals(listOf(SseEvent("message", "one", null)), events("﻿data: one", ""))
    }

    @Test
    fun `an event left unfinished when the stream ends is still returned`() {
        val parser = SseParser()
        parser.feed("data: answered and closed")

        assertEquals(SseEvent("message", "answered and closed", null), parser.finish())
        assertNull(parser.finish())
    }
}
