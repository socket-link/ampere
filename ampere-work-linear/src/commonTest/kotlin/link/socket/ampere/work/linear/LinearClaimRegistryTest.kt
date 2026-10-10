package link.socket.ampere.work.linear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import link.socket.ampere.agents.execution.dispatch.ClaimRelease
import link.socket.ampere.agents.tools.mcp.protocol.McpToolDescriptor
import link.socket.ampere.agents.tools.mcp.protocol.ToolCallResult
import link.socket.ampere.link.LinkId

/**
 * The binding between this work source and the reconciliation pass's
 * `ClaimRegistry` port (AMPR-310).
 *
 * The port is narrow so that every judgement stays on this side of it, and these
 * tests are about the two that cross: a release outcome arriving as a verdict the
 * pass can act on, and a cold-start scan that says what it may have missed rather
 * than reporting a short list as a complete one.
 */
class LinearClaimRegistryTest {

    private val linkId = LinkId("work-source-link")
    private val dead = SupervisorInstanceId("supervisor-dead")
    private val reconciler = SupervisorInstanceId("reconcile-1")

    private fun seeded(vararg identifiers: String): FakeWorkSource = FakeWorkSource().apply {
        identifiers.forEach { add(FakeWorkSource.Issue(it, labels = mutableListOf("wave:w2"))) }
    }

    private fun registry(tools: WorkSourceToolCaller) = LinearClaimRegistry(
        LinearWorkSource(tools = tools, linkId = linkId, instanceId = reconciler),
    )

    private suspend fun claimedBy(tools: WorkSourceToolCaller, issue: String, instance: SupervisorInstanceId) {
        LinearWorkSource(tools = tools, linkId = linkId, instanceId = instance).claim(issue).getOrThrow()
    }

    @Test
    fun `a released claim arrives as a requeue the pass can report`() = runTest {
        val fake = seeded("AMPR-310")
        claimedBy(fake, "AMPR-310", dead)

        val release = registry(fake).releaseClaim("AMPR-310", dead.value).getOrThrow()

        val released = assertIs<ClaimRelease.Released>(release)
        assertTrue(released.commentPosted)
        assertEquals("Todo", released.revertedTo)
        assertEquals("Todo", fake.issue("AMPR-310").status)
    }

    @Test
    fun `a deferral arrives with the evidence spelled out`() = runTest {
        val fake = seeded("AMPR-310")
        claimedBy(fake, "AMPR-310", dead)
        LinearWorkSource(tools = fake, linkId = linkId, instanceId = reconciler)
            .transition("AMPR-310", "Done")
            .getOrThrow()

        val release = registry(fake).releaseClaim("AMPR-310", dead.value).getOrThrow()

        val deferred = assertIs<ClaimRelease.Deferred>(release)
        assertEquals("Done", deferred.observedState)
        assertTrue(deferred.reason.contains("Done"), deferred.reason)
        assertTrue(deferred.commentPosted)
    }

    @Test
    fun `an unusable instance id fails instead of being coerced`() = runTest {
        // Instance ids become claim-comment fields, so one carrying the separator
        // could never have matched a claim comment in the first place.
        val result = registry(seeded("AMPR-310")).releaseClaim("AMPR-310", "bad:id")

        assertTrue(result.isFailure)
    }

    @Test
    fun `the cold-start scan finds live claims across every in-flight state`() = runTest {
        val fake = seeded("AMPR-310", "AMPR-311", "AMPR-312")
        claimedBy(fake, "AMPR-310", dead)
        claimedBy(fake, "AMPR-311", dead)
        // A dispatch that got as far as verification is still a claimed ticket.
        LinearWorkSource(tools = fake, linkId = linkId, instanceId = dead)
            .transition("AMPR-311", "In Review")
            .getOrThrow()

        val scan = registry(fake).claimedTickets().getOrThrow()

        assertEquals(listOf("AMPR-310", "AMPR-311"), scan.tickets.map { it.ticketId })
        assertEquals(listOf(dead.value), scan.tickets.first().holders)
        assertEquals("In Review", scan.tickets.last().state)
        assertTrue(scan.incomplete.isEmpty())
    }

    @Test
    fun `a claim a previous pass retracted is not residue`() = runTest {
        // Otherwise the degraded path raises the same ticket on every startup for
        // the rest of the repository's life.
        val fake = seeded("AMPR-310")
        claimedBy(fake, "AMPR-310", dead)
        registry(fake).releaseClaim("AMPR-310", dead.value).getOrThrow()
        // Someone else moved it back into progress without claiming it.
        LinearWorkSource(tools = fake, linkId = linkId, instanceId = reconciler)
            .transition("AMPR-310", "In Progress")
            .getOrThrow()

        val scan = registry(fake).claimedTickets().getOrThrow()

        assertTrue(scan.tickets.isEmpty(), "${scan.tickets}")
    }

    @Test
    fun `an unreadable comment thread is reported rather than read as unclaimed`() = runTest {
        val fake = seeded("AMPR-310")
        claimedBy(fake, "AMPR-310", dead)

        val scan = registry(SilentOn("AMPR-310", fake)).claimedTickets().getOrThrow()

        assertTrue(scan.tickets.isEmpty())
        assertEquals(1, scan.incomplete.size)
        assertTrue(scan.incomplete.single().contains("AMPR-310"), scan.incomplete.single())
    }

    /** A work source whose comment thread for one issue cannot be read. */
    private class SilentOn(
        private val issue: String,
        private val delegate: FakeWorkSource,
    ) : WorkSourceToolCaller {

        override suspend fun listTools(): Result<List<McpToolDescriptor>> = delegate.listTools()

        override suspend fun call(tool: String, arguments: JsonObject): Result<ToolCallResult> =
            if (tool == WorkSourceToolPins.LIST_COMMENTS && arguments.toString().contains(issue)) {
                Result.failure(IllegalStateException("the comment thread of $issue is unavailable"))
            } else {
                delegate.call(tool, arguments)
            }
    }
}
