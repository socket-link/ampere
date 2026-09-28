package link.socket.ampere.work.linear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import link.socket.ampere.agents.tools.mcp.protocol.ToolCallResult
import link.socket.ampere.canon.adapter.CanonConversionFailure

/**
 * The response half of the schema pin: recorded bodies, decoded.
 *
 * `inputSchema` describes requests and says nothing about responses, so the only
 * way to pin a response shape is to decode a recording of one. A renamed
 * response field fails here instead of producing a page that is silently empty —
 * the failure mode that would have the ready queue report "no work" forever.
 */
class WorkSourceDecodingTest {

    @Test
    fun `a recorded fetched issue decodes every field the adapter reads`() {
        val issue = WorkSourceDecoding.issue(Recorded.body(Recorded.GET_ISSUE)).getOrThrow()

        assertEquals("AMPR-305", issue.identifier)
        assertEquals("e6611e74-52f1-4076-8de8-8a4d612fd059", issue.uuid)
        assertEquals("In Progress", issue.statusName)
        assertEquals(WorkItemStatusType.STARTED, issue.statusType)
        assertEquals(listOf("wave:w0", "api", "integration", "cli"), issue.labels)
        assertEquals("2383a667-44c6-458c-9c5d-5c31855040aa", issue.projectId)
        assertEquals("Act 7 - CLI Dispatch Loop", issue.projectName)
        assertEquals("5dbd9ca7-68f5-4692-a4e7-da1c09092f69", issue.teamId)
        assertEquals("Ampere", issue.teamName)
        assertEquals(Instant.parse("2026-09-26T06:14:50.333Z"), issue.updatedAt)
    }

    @Test
    fun `a recorded fetched issue decodes its state history in order`() {
        val history = assertNotNull(
            WorkSourceDecoding.issue(Recorded.body(Recorded.GET_ISSUE)).getOrThrow().stateHistory,
        )

        assertEquals(listOf("Backlog", "In Progress"), history.map { it.stateName })
        assertEquals(
            listOf(WorkItemStatusType.BACKLOG, WorkItemStatusType.STARTED),
            history.map { it.stateType },
        )
        assertEquals(Instant.parse("2026-08-24T03:30:16.284Z"), history.first().startedAt)
        assertEquals(Instant.parse("2026-09-26T06:14:50.328Z"), history.first().endedAt)
        assertNull(history.last().endedAt, "only the current span is open")
    }

    @Test
    fun `a fetched issue with no blockers decodes an empty list and not a null one`() {
        // The recorded response asked for relations and found no blockers. That
        // is a different fact from never having asked, and the blocker rule
        // reads them differently: empty is trusted, null forces a fetch.
        val issue = WorkSourceDecoding.issue(Recorded.body(Recorded.GET_ISSUE)).getOrThrow()

        assertEquals(emptyList(), issue.blockedBy)
    }

    @Test
    fun `a listed issue carries no relations and no history at all`() {
        val page = WorkSourceDecoding.issuePage(Recorded.body(Recorded.LIST_ISSUES))

        assertEquals(listOf("AMPR-305", "AMPR-333"), page.entities.map { it.identifier })
        page.entities.forEach { issue ->
            assertNull(issue.blockedBy, "a listed issue never carried relations")
            assertNull(issue.stateHistory, "a listed issue never carried state history")
        }
    }

    @Test
    fun `a recorded list envelope decodes its continuation cursor`() {
        val page = WorkSourceDecoding.issuePage(Recorded.body(Recorded.LIST_ISSUES))

        assertEquals("f7b3c117-d8a2-47fc-bb75-d0283d3c0dcc", page.nextCursor)
        assertEquals(emptyList(), page.failures)
    }

    @Test
    fun `a cursor is ignored unless the server said there is another page`() {
        // A server that started echoing a cursor on the last page would page
        // forever; the `hasNextPage` check is what makes the scan terminate.
        val lastPage = Recorded.body(
            """{ "issues": [], "hasNextPage": false, "cursor": "stale-cursor" }""",
        )

        assertNull(WorkSourceDecoding.issuePage(lastPage).nextCursor)
    }

    @Test
    fun `a recorded comment envelope decodes the two facts the arbiter needs`() {
        val page = WorkSourceDecoding.commentPage(Recorded.body(Recorded.LIST_COMMENTS))

        assertEquals(2, page.entities.size)
        val claim = page.entities.first()
        assertEquals("f0e41cac-70c7-44a8-bcf2-752b28be5755", claim.id)
        assertEquals(Instant.parse("2026-08-24T02:27:16.214Z"), claim.createdAt)
        assertEquals("claim:AMPR-305:supervisor-a", claim.body)
        assertEquals("Miley Chandonnet", claim.authorName)
        assertNull(page.entities.last().authorName, "a bot comment has no author")
    }

    @Test
    fun `an unknown status type is a typed failure and does not shorten the page`() {
        // A status type this build does not know is vendor drift, and the honest
        // answer is a named failure rather than a guessed lifecycle position.
        val body = Recorded.body(
            """
            {
              "issues": [
                { "id": "AMPR-1", "title": "fine", "status": "Todo", "statusType": "unstarted" },
                { "id": "AMPR-2", "title": "drifted", "status": "Paused", "statusType": "paused" },
                { "id": "AMPR-3", "title": "fine", "status": "Done", "statusType": "completed" }
              ],
              "hasNextPage": false
            }
            """.trimIndent(),
        )

        val page = WorkSourceDecoding.issuePage(body)

        assertEquals(listOf("AMPR-1", "AMPR-3"), page.entities.map { it.identifier })
        val failure = assertIs<CanonConversionFailure.MalformedField>(page.failures.single())
        assertEquals("statusType", failure.field)
        assertTrue("paused" in failure.reason, failure.reason)
    }

    @Test
    fun `an issue missing a required field is a typed failure`() {
        val body = Recorded.body(
            """{ "issues": [{ "id": "AMPR-1", "statusType": "unstarted" }], "hasNextPage": false }""",
        )

        val failure = assertIs<CanonConversionFailure.MissingRequiredField>(
            WorkSourceDecoding.issuePage(body).failures.single(),
        )

        assertEquals("title", failure.field)
    }

    @Test
    fun `an unreadable body is a malformed response and never an empty page`() {
        val notAnObject = textResult("""["not", "an", "object"]""")

        assertIs<WorkSourceFailure.MalformedResponse>(
            notAnObject.jsonBody("list_issues").workSourceFailure(),
        )
    }

    @Test
    fun `an error result reports the server's own text`() {
        val failed = textResult("""{"error":"rate limited"}""", isError = true)

        val failure = assertIs<WorkSourceFailure.ToolCallFailed>(
            failed.jsonBody("list_issues").workSourceFailure(),
        )

        assertTrue("rate limited" in failure.reason, failure.reason)
    }

    @Test
    fun `a result with no text content is a malformed response`() {
        val empty = ToolCallResult(content = emptyList())

        assertIs<WorkSourceFailure.MalformedResponse>(empty.jsonBody("get_issue").workSourceFailure())
    }

    private fun Result<JsonObject>.workSourceFailure(): WorkSourceFailure =
        (exceptionOrNull() as? WorkSourceException)?.failure
            ?: throw AssertionError("expected a WorkSourceException, got ${exceptionOrNull()}")
}
