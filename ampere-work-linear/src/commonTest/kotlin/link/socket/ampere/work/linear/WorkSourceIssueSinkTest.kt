package link.socket.ampere.work.linear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import link.socket.ampere.agents.tools.mcp.protocol.McpToolDescriptor
import link.socket.ampere.agents.tools.mcp.protocol.ToolCallResult
import link.socket.ampere.link.LinkId
import link.socket.ampere.plug.spi.ExecuteException
import link.socket.ampere.plug.spi.ExecuteFailure
import link.socket.ampere.plug.spi.WritePrecondition
import link.socket.ampere.time.MutableClock

/** The Execute half: which tool each command reaches, and what a receipt says. */
class WorkSourceIssueSinkTest {

    private val linkId = LinkId("work-source-link")
    private val clock = MutableClock(Instant.parse("2026-09-26T12:00:00Z"))

    private fun fixture(
        forbiddenTerms: Set<String> = emptySet(),
    ): Pair<FakeWorkSource, WorkSourceIssueSink> {
        val fake = FakeWorkSource().apply {
            add(FakeWorkSource.Issue("AMPR-101", labels = mutableListOf("wave:w0")))
        }
        return fake to WorkSourceIssueSink(fake, linkId, forbiddenTerms, clock)
    }

    @Test
    fun `a transition writes the state through the issue-save tool`() = runTest {
        val (fake, sink) = fixture()

        sink.execute(WorkSourceCommand.Transition("AMPR-101", "In Progress")).getOrThrow()

        val call = fake.callsTo(WorkSourceToolPins.SAVE_ISSUE).single()
        assertEquals("AMPR-101", call.arguments.text("id"))
        assertEquals("In Progress", call.arguments.text("state"))
        assertEquals("In Progress", fake.issue("AMPR-101").status)
    }

    @Test
    fun `a comment writes the body through the comment-save tool`() = runTest {
        val (fake, sink) = fixture()

        sink.execute(WorkSourceCommand.PostComment("AMPR-101", "claim:AMPR-101:a")).getOrThrow()

        val call = fake.callsTo(WorkSourceToolPins.SAVE_COMMENT).single()
        assertEquals("AMPR-101", call.arguments.text("issueId"))
        assertEquals("claim:AMPR-101:a", call.arguments.text("body"))
    }

    @Test
    fun `labels are edited additively and never replaced wholesale`() = runTest {
        val (fake, sink) = fixture()

        sink.execute(
            WorkSourceCommand.AddLabels("AMPR-101", listOf(WorkSourceLabels.GATE_AWAITING_VERDICT)),
        ).getOrThrow()

        val call = fake.callsTo(WorkSourceToolPins.SAVE_ISSUE).single()
        assertEquals(listOf("gate:awaiting-verdict"), call.arguments.texts("addLabels"))
        assertNull(
            call.arguments.text("labels"),
            "the wholesale `labels` argument would have dropped the ticket's wave tag",
        )
        assertEquals(
            listOf("wave:w0", "gate:awaiting-verdict"),
            fake.issue("AMPR-101").labels,
        )
    }

    @Test
    fun `removing a label leaves the rest alone`() = runTest {
        val (fake, sink) = fixture()
        fake.issue("AMPR-101").labels += WorkSourceLabels.GATE_ESCALATED

        sink.execute(
            WorkSourceCommand.RemoveLabels("AMPR-101", listOf(WorkSourceLabels.GATE_ESCALATED)),
        ).getOrThrow()

        assertEquals(listOf("wave:w0"), fake.issue("AMPR-101").labels)
    }

    @Test
    fun `a receipt names the Link and carries no version token`() = runTest {
        val (_, sink) = fixture()

        val receipt = sink.execute(WorkSourceCommand.Transition("AMPR-101", "In Review")).getOrThrow()

        assertEquals(linkId, receipt.linkId)
        assertEquals(clock.now(), receipt.executedAt)
        val handle = assertNotNull(receipt.handle)
        assertEquals("AMPR-101", handle.nativeId)
        assertEquals(WorkItemCanonAdapter.SOURCE_SYSTEM, handle.sourceSystem)
        assertNull(
            handle.etag,
            "this provider issues no version token, and `updatedAt` is not one — nothing " +
                "would check it at write time",
        )
    }

    @Test
    fun `a receipt carries the post-write state when the provider returns one`() = runTest {
        val (_, sink) = fixture()

        val receipt = sink.execute(WorkSourceCommand.Transition("AMPR-101", "In Review")).getOrThrow()

        val written = assertNotNull(receipt.writtenIssue, "the fake echoes the written issue")
        assertEquals("In Review", written.statusName)
    }

    @Test
    fun `a comment receipt is not readable as an issue`() = runTest {
        val (_, sink) = fixture()

        val receipt = sink.execute(WorkSourceCommand.PostComment("AMPR-101", "hello")).getOrThrow()

        assertEquals(
            WorkSourceIssueSink.COMMENT_SCHEMA,
            assertNotNull(receipt.postWriteState).schema,
        )
        assertNull(
            receipt.writtenIssue,
            "a created comment tagged as an issue would schema-check and then be misread",
        )
    }

    @Test
    fun `a write whose response cannot be read still reports success`() = runTest {
        // The write has landed by the time the response is parsed. Reporting a
        // failure would have a caller re-post a claim comment it already posted.
        val sink = WorkSourceIssueSink(UnreadableResponses(), linkId, emptySet(), clock)

        val receipt = sink.execute(WorkSourceCommand.Transition("AMPR-101", "Done")).getOrThrow()

        assertNull(receipt.postWriteState)
        assertEquals("AMPR-101", assertNotNull(receipt.handle).nativeId)
    }

    @Test
    fun `a server error fails the write`() = runTest {
        val (_, sink) = fixture()

        // save_issue looks the issue up, so an unknown issue is the error path.
        val failure = sink.execute(WorkSourceCommand.Transition("AMPR-404", "Done"))

        val cause = assertIs<WorkSourceException>(failure.exceptionOrNull())
        assertIs<WorkSourceFailure.ToolCallFailed>(cause.failure)
    }

    /** A server that answers every call with something that is not JSON. */
    private class UnreadableResponses : WorkSourceToolCaller {
        override suspend fun listTools(): Result<List<McpToolDescriptor>> =
            Result.success(Recorded.TOOL_SURFACE)

        override suspend fun call(tool: String, arguments: JsonObject): Result<ToolCallResult> =
            Result.success(textResult("ok, but not JSON"))
    }

    @Test
    fun `conditional writes refuse loudly because this provider has none`() = runTest {
        val (fake, sink) = fixture()

        val refused = sink.executeIf(
            command = WorkSourceCommand.Transition("AMPR-101", "Done"),
            precondition = WritePrecondition.MatchVersion("v1"),
        )

        assertEquals(emptySet(), sink.supportedPreconditions)
        val cause = assertIs<ExecuteException>(refused.exceptionOrNull())
        assertIs<ExecuteFailure.PreconditionUnsupported>(cause.failure)
        assertTrue(fake.calls.isEmpty(), "the refusal happens before the transport is touched")
        assertEquals("Todo", fake.issue("AMPR-101").status, "and nothing was written")
    }

    @Test
    fun `a forbidden term blocks the comment before it reaches a public mirror`() = runTest {
        val (fake, sink) = fixture(forbiddenTerms = setOf("Skunkworks"))

        val refused = sink.execute(
            WorkSourceCommand.PostComment("AMPR-101", "Blocked on the skunkworks rollout."),
        )

        val cause = assertIs<WorkSourceException>(refused.exceptionOrNull())
        assertEquals(WorkSourceFailure.ForbiddenTerm("Skunkworks"), cause.failure)
        assertTrue(
            fake.calls.isEmpty(),
            "a comment cannot be unpublished from a public mirror, so nothing may be sent",
        )
    }

    @Test
    fun `screening is off by default because naming the terms is not the adapter's business`() = runTest {
        val (fake, sink) = fixture()

        sink.execute(WorkSourceCommand.PostComment("AMPR-101", "Anything at all.")).getOrThrow()

        assertEquals(1, fake.callsTo(WorkSourceToolPins.SAVE_COMMENT).size)
    }

    @Test
    fun `the sink consumes no canon`() {
        val (_, sink) = fixture()

        assertEquals(emptySet(), sink.consumes)
    }
}
