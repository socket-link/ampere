package link.socket.ampere.work.linear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.socket.ampere.canon.CanonType
import link.socket.ampere.link.LinkDirection
import link.socket.ampere.link.LinkId
import link.socket.ampere.link.PlatformTarget
import link.socket.ampere.link.Transport
import link.socket.ampere.link.TransportRole
import link.socket.ampere.plug.ManifestValidationResult
import link.socket.ampere.plug.PlugManifestValidator
import link.socket.ampere.plug.permission.PlugPermission

/**
 * The Plug half: a manifest the framework validator accepts, bound to the one
 * transport that has an implementation behind it.
 */
class LinearWorkSourcePlugTest {

    private val serverUri = "https://example.invalid/mcp"

    @Test
    fun `the manifest passes the framework validator`() {
        val result = PlugManifestValidator.validate(LinearWorkSource.manifest(serverUri))

        assertEquals(ManifestValidationResult.Valid, result)
    }

    @Test
    fun `the Link requirement binds MCP as a read-write consumer`() {
        val requirement = LinearWorkSource.manifest(serverUri).requiredLinks.single()

        assertEquals(LinearWorkSource.DEPENDENCY_NAME, requirement.name)
        assertEquals(Transport.MCP, requirement.transport)
        assertEquals(TransportRole.CONSUMER, requirement.role)
        assertEquals(
            LinkDirection.READ_WRITE,
            requirement.direction,
            "a read-only Link must fail resolution rather than fail at the first claim",
        )
        assertEquals(setOf(CanonType.WORK_ITEM), requirement.minimumScope)
    }

    @Test
    fun `the bound transport is the one with an implementation behind it`() {
        // Transport.MCP is the only member with hasImplementation true, and it is
        // bidirectional on the desktop targets a supervisor runs on.
        assertTrue(Transport.MCP.hasImplementation)
        assertTrue(Transport.MCP.capability(PlatformTarget.JVM_DESKTOP).permits(TransportRole.CONSUMER))
    }

    @Test
    fun `the MCP dependency and the Link requirement share a name`() {
        // PlugContext.create resolves a dependency's Link by looking up the
        // requirement of the same name; a mismatch surfaces as a per-server
        // failure rather than a build error.
        val manifest = LinearWorkSource.manifest(serverUri)

        assertEquals(
            manifest.mcpServers.single().name,
            manifest.requiredLinks.single().name,
        )
    }

    @Test
    fun `the manifest declares the canon it emits and the canon it does not consume`() {
        val manifest = LinearWorkSource.manifest(serverUri)

        assertEquals(setOf(CanonType.WORK_ITEM), manifest.emits)
        assertEquals(emptySet(), manifest.consumes, "every command is native, not canon")
        assertFalse(
            manifest.isCanonExternal,
            "the Perceive side really does produce a canon type, so the flag would be a lie",
        )
    }

    @Test
    fun `the MCP server grant is lifted to the manifest`() {
        val manifest = LinearWorkSource.manifest(serverUri)

        assertTrue(PlugPermission.MCPServer(serverUri) in manifest.requiredPermissions)
        assertTrue(
            manifest.mcpServers.single().requiredPermissions.all { it in manifest.requiredPermissions },
        )
    }

    @Test
    fun `a supervisor built on a verified surface answers reads and writes`() = runTest {
        // The end-to-end shape, short of a live server: pins verified, then the
        // full claim cycle.
        val fake = FakeWorkSource().apply {
            add(FakeWorkSource.Issue("AMPR-101", labels = mutableListOf("wave:w0")))
        }
        assertTrue(WorkSourceToolPins.verifyDescriptors(fake.listTools().getOrThrow()).isSuccess)

        val supervisor = LinearWorkSource(
            tools = fake,
            linkId = LinkId("work-source-link"),
            instanceId = SupervisorInstanceId("supervisor-a"),
        )

        assertEquals(listOf("AMPR-101"), supervisor.readyQueue("w0").getOrThrow().items.map { it.identifier })
        assertIs<ClaimOutcome.Won>(supervisor.claim("AMPR-101").getOrThrow())
        supervisor.postComment("AMPR-101", "## Findings\n\nAll gates green.").getOrThrow()
        supervisor.requestVerdict("AMPR-101").getOrThrow()

        assertEquals("In Review", fake.issue("AMPR-101").status)
        assertTrue(WorkSourceLabels.GATE_AWAITING_VERDICT in fake.issue("AMPR-101").labels)
        assertEquals(
            emptyList(),
            supervisor.readyQueue("w0").getOrThrow().items,
            "a ticket at a verdict gate is never ready again until the gate clears",
        )
    }

    @Test
    fun `escalation marks the gate and leaves the state where the work stopped`() = runTest {
        val fake = FakeWorkSource().apply {
            add(
                FakeWorkSource.Issue(
                    identifier = "AMPR-101",
                    status = "In Progress",
                    statusType = WorkItemStatusType.STARTED,
                    labels = mutableListOf("wave:w0"),
                ),
            )
        }
        val supervisor = LinearWorkSource(
            tools = fake,
            linkId = LinkId("work-source-link"),
            instanceId = SupervisorInstanceId("supervisor-a"),
        )

        supervisor.escalate("AMPR-101", "The verification gate needs a human decision.").getOrThrow()

        assertEquals(
            "In Progress",
            fake.issue("AMPR-101").status,
            "where the work stopped is the most useful thing about an escalated ticket",
        )
        assertTrue(WorkSourceLabels.GATE_ESCALATED in fake.issue("AMPR-101").labels)
        assertEquals(
            SupervisoryComment.Escalation(
                issue = "AMPR-101",
                instanceId = SupervisorInstanceId("supervisor-a"),
                body = "The verification gate needs a human decision.",
            ),
            SupervisoryComment.parse(fake.comments("AMPR-101").single().body),
        )
    }

    @Test
    fun `the state history read is the one the claim protocol relies on`() = runTest {
        val fake = FakeWorkSource().apply {
            add(FakeWorkSource.Issue("AMPR-101", labels = mutableListOf("wave:w0")))
        }
        val supervisor = LinearWorkSource(
            tools = fake,
            linkId = LinkId("work-source-link"),
            instanceId = SupervisorInstanceId("supervisor-a"),
        )

        supervisor.transition("AMPR-101", "In Progress").getOrThrow()
        val history = supervisor.readStateHistory("AMPR-101").getOrThrow()

        assertEquals(listOf("Todo", "In Progress"), history.map { it.stateName })
        assertTrue(history.first().endedAt != null, "the previous span closes when the next opens")
    }

    @Test
    fun `a work source with no state history fails the history read loudly`() = runTest {
        val fake = FakeWorkSource().apply {
            add(FakeWorkSource.Issue("AMPR-101"))
            omitStateHistory = true
        }
        val supervisor = LinearWorkSource(
            tools = fake,
            linkId = LinkId("work-source-link"),
            instanceId = SupervisorInstanceId("supervisor-a"),
        )

        val cause = assertIs<WorkSourceException>(
            supervisor.readStateHistory("AMPR-101").exceptionOrNull(),
        )

        assertIs<WorkSourceFailure.MalformedResponse>(cause.failure)
    }

    @Test
    fun `every comment page is read so the arbiter cannot miss the earliest claim`() = runTest {
        val fake = FakeWorkSource().apply {
            add(FakeWorkSource.Issue("AMPR-101"))
            commentPageSize = 3
            repeat(7) { addComment("AMPR-101", "note $it") }
        }
        val supervisor = LinearWorkSource(
            tools = fake,
            linkId = LinkId("work-source-link"),
            instanceId = SupervisorInstanceId("supervisor-a"),
        )

        val comments = supervisor.readComments("AMPR-101").getOrThrow()

        assertEquals(7, comments.size, "pages of three, three and one — and every comment from all of them")
        assertEquals((0..6).map { "note $it" }, comments.map { it.body })
        assertEquals(3, fake.callsTo(WorkSourceToolPins.LIST_COMMENTS).size)
    }
}
