package link.socket.ampere.work.linear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonProse
import link.socket.ampere.canon.CanonType
import link.socket.ampere.canon.CanonWorkStatus
import link.socket.ampere.canon.NativePayload
import link.socket.ampere.canon.NativeSchema
import link.socket.ampere.canon.adapter.CanonConversionException
import link.socket.ampere.canon.adapter.CanonConversionFailure
import link.socket.ampere.link.LinkId

/**
 * The canon projection, and the four supervisory states it deliberately cannot
 * express.
 */
class WorkItemCanonAdapterTest {

    private val linkId = LinkId("work-source-link")
    private val observedAt = Instant.parse("2026-09-26T12:00:00Z")

    private fun adapter(fake: FakeWorkSource = FakeWorkSource()) = WorkItemCanonAdapter(fake)

    private fun project(
        body: JsonObject,
        identifier: String = "AMPR-305",
    ) = adapter().project(
        payload = NativePayload(WorkItemCanonAdapter.SCHEMA, body),
        handle = WorkItemCanonAdapter.handleFor(linkId, identifier),
        observedAt = observedAt,
    )

    @Test
    fun `a recorded issue projects onto the Ring 3 work item`() {
        val item = project(Recorded.body(Recorded.GET_ISSUE)).getOrThrow()

        assertEquals(CanonType.WORK_ITEM, item.canonType)
        assertEquals(CanonId("AMPR-305"), item.canonId)
        assertEquals("Work-source adapter: Chassis SPI Plug over MCP", item.title)
        assertEquals(CanonWorkStatus.IN_PROGRESS, item.status)
        assertEquals(listOf("wave:w0", "api", "integration", "cli"), item.labels)
        assertEquals(CanonId("2383a667-44c6-458c-9c5d-5c31855040aa"), item.projectId)
        assertNull(item.dueAt)
    }

    @Test
    fun `the supervisory state rides in providerStatus and not in status`() {
        // Claimed, verifying, verdict-requested and escalated are not
        // CanonWorkStatus members (AMPR-314). The canon status stays coarse and
        // true; the work source's own state name carries the detail.
        val item = project(Recorded.body(Recorded.GET_ISSUE)).getOrThrow()

        assertEquals("In Progress", item.providerStatus)
        assertEquals(CanonWorkStatus.IN_PROGRESS, item.status)
    }

    @Test
    fun `a gated ticket carries its gate label into canon verbatim`() {
        val body = Recorded.body(
            """
            {
              "id": "AMPR-1",
              "title": "Gated",
              "status": "In Review",
              "statusType": "started",
              "labels": ["wave:w0", "gate:awaiting-verdict"]
            }
            """.trimIndent(),
        )

        val item = project(body, identifier = "AMPR-1").getOrThrow()

        assertEquals(listOf("wave:w0", "gate:awaiting-verdict"), item.labels)
        assertEquals(
            CanonWorkStatus.IN_PROGRESS,
            item.status,
            "a verdict-requested ticket is `started` to the provider, and canon has no better word",
        )
    }

    @Test
    fun `every status type maps to a canon member`() {
        val expected = mapOf(
            "triage" to CanonWorkStatus.BACKLOG,
            "backlog" to CanonWorkStatus.BACKLOG,
            "unstarted" to CanonWorkStatus.TODO,
            "started" to CanonWorkStatus.IN_PROGRESS,
            "completed" to CanonWorkStatus.DONE,
            "canceled" to CanonWorkStatus.CANCELLED,
        )

        assertEquals(
            WorkItemStatusType.entries.map { it.wireName }.toSet(),
            expected.keys,
            "a new status type must be mapped here, not silently defaulted",
        )

        expected.forEach { (wireName, canonStatus) ->
            val body = Recorded.body(
                """{ "id": "AMPR-1", "title": "t", "status": "s", "statusType": "$wireName" }""",
            )
            assertEquals(canonStatus, project(body, "AMPR-1").getOrThrow().status, wireName)
        }
    }

    @Test
    fun `an unknown status type is a typed failure and not a guess`() {
        val body = Recorded.body(
            """{ "id": "AMPR-1", "title": "t", "status": "Paused", "statusType": "paused" }""",
        )

        val cause = assertIs<CanonConversionException>(project(body, "AMPR-1").exceptionOrNull())
        val failure = assertIs<CanonConversionFailure.MalformedField>(cause.failure)

        assertEquals("statusType", failure.field)
        assertTrue("drifted" in failure.reason, failure.reason)
    }

    @Test
    fun `blocking relations become same-Link dependsOn edges`() {
        // A relation names the blocker by identifier and nothing else, which is
        // why canonId is the identifier too: a graph keyed on uuid could never
        // resolve an edge.
        val body = Recorded.body(
            """
            {
              "id": "AMPR-1",
              "title": "Blocked",
              "status": "Todo",
              "statusType": "unstarted",
              "relations": {
                "blockedBy": [{ "id": "AMPR-9", "title": "First" }, { "id": "AMPR-8", "title": "Second" }]
              }
            }
            """.trimIndent(),
        )

        val item = project(body, "AMPR-1").getOrThrow()

        assertEquals(listOf(CanonId("AMPR-9"), CanonId("AMPR-8")), item.dependsOn)
    }

    @Test
    fun `provenance carries the Link the lossless payload and the observation time`() {
        val body = Recorded.body(Recorded.GET_ISSUE)

        val item = project(body).getOrThrow()

        assertEquals(linkId, item.provenance.sourceHandle.linkId)
        assertEquals(WorkItemCanonAdapter.SOURCE_SYSTEM, item.provenance.sourceHandle.sourceSystem)
        assertEquals(observedAt, item.provenance.observedAt)
        assertEquals(body, item.provenance.nativePayload?.fields, "the native object survives verbatim")
        assertNull(
            item.provenance.sourceHandle.etag,
            "no version token exists, so claiming one would invite a precondition nothing enforces",
        )
    }

    @Test
    fun `a due date normalises to midnight UTC`() {
        val body = Recorded.body(
            """
            {
              "id": "AMPR-1", "title": "t", "status": "Todo", "statusType": "unstarted",
              "dueDate": "2026-10-01"
            }
            """.trimIndent(),
        )

        assertEquals(
            Instant.parse("2026-10-01T00:00:00Z"),
            project(body, "AMPR-1").getOrThrow().dueAt,
        )
    }

    @Test
    fun `a description is bounded rather than carried whole`() {
        val body = Recorded.body(
            """
            {
              "id": "AMPR-1", "title": "t", "status": "Todo", "statusType": "unstarted",
              "description": "${"x".repeat(CanonProse.MAX_CHARS + 100)}"
            }
            """.trimIndent(),
        )

        val prose = project(body, "AMPR-1").getOrThrow().description

        assertEquals(CanonProse.MAX_CHARS, prose?.text?.length)
        assertEquals(true, prose?.truncated)
    }

    @Test
    fun `a payload from another native shape is refused before it is read`() {
        val refused = adapter().project(
            payload = NativePayload(NativeSchema("SomeOtherIssue"), Recorded.body(Recorded.GET_ISSUE)),
            handle = WorkItemCanonAdapter.handleFor(linkId, "AMPR-305"),
            observedAt = observedAt,
        )

        val cause = assertIs<CanonConversionException>(refused.exceptionOrNull())
        assertIs<CanonConversionFailure.SchemaMismatch>(cause.failure)
    }

    @Test
    fun `an entity that shed its payload can be refetched by handle`() = runTest {
        val fake = FakeWorkSource().apply {
            add(FakeWorkSource.Issue("AMPR-101", labels = mutableListOf("wave:w0")))
        }
        val adapter = WorkItemCanonAdapter(fake)

        val item = adapter.project(
            payload = NativePayload(WorkItemCanonAdapter.SCHEMA, Recorded.body(Recorded.GET_ISSUE)),
            handle = WorkItemCanonAdapter.handleFor(linkId, "AMPR-101"),
            observedAt = observedAt,
            carryNativePayload = false,
        ).getOrThrow()

        assertNull(item.provenance.nativePayload)

        val refetched = adapter.fetchNative(item.provenance.sourceHandle).getOrThrow()

        assertEquals(WorkItemCanonAdapter.SCHEMA, refetched.schema)
        assertEquals("AMPR-101", refetched.fields.text("id"))
    }
}
