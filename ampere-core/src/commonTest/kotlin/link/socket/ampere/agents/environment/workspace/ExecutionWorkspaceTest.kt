package link.socket.ampere.agents.environment.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import link.socket.ampere.data.DEFAULT_JSON

class ExecutionWorkspaceTest {

    private val json = DEFAULT_JSON

    private val acquiredAt = Instant.parse("2026-09-27T12:00:00Z")
    private val expiresAt = acquiredAt + 30.minutes
    private val duringLease = acquiredAt + 10.minutes

    private fun leasedTo(holder: String, directory: String = "/work/ampere-a") = ExecutionWorkspace(
        baseDirectory = directory,
        repository = "socket-link/ampere",
        branch = "miley/ampr-369",
        lease = WorkspaceLease(holder = holder, acquiredAt = acquiredAt, expiresAt = expiresAt),
    )

    // ==================== Serialization ====================

    @Test
    fun `round-trips with repository branch and lease`() {
        val original = leasedTo("run-1")

        val encoded = json.encodeToString(ExecutionWorkspace.serializer(), original)
        val decoded = json.decodeFromString(ExecutionWorkspace.serializer(), encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun `round-trips with a lease that never expires`() {
        val original = ExecutionWorkspace(
            baseDirectory = "/work/ampere-a",
            lease = WorkspaceLease(holder = "run-1", acquiredAt = acquiredAt),
        )

        val encoded = json.encodeToString(ExecutionWorkspace.serializer(), original)
        val decoded = json.decodeFromString(ExecutionWorkspace.serializer(), encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun `round-trips with only a base directory`() {
        val original = ExecutionWorkspace(baseDirectory = "/work/ampere-a")

        val encoded = json.encodeToString(ExecutionWorkspace.serializer(), original)
        val decoded = json.decodeFromString(ExecutionWorkspace.serializer(), encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun `decodes a workspace persisted before it had an identity`() {
        val decoded = json.decodeFromString(
            ExecutionWorkspace.serializer(),
            """{"baseDirectory":"/work/ampere-a"}""",
        )

        assertEquals(ExecutionWorkspace(baseDirectory = "/work/ampere-a"), decoded)
        assertNull(decoded.repository)
        assertNull(decoded.branch)
        assertNull(decoded.lease)
    }

    @Test
    fun `the single-argument constructor still compiles positionally`() {
        val positional = ExecutionWorkspace("/work/ampere-a")

        assertEquals(ExecutionWorkspace(baseDirectory = "/work/ampere-a"), positional)
    }

    // ==================== Lease ====================

    @Test
    fun `a lease is active from acquisition until it expires`() {
        val lease = WorkspaceLease(holder = "run-1", acquiredAt = acquiredAt, expiresAt = expiresAt)

        assertFalse(lease.isActiveAt(acquiredAt - 1.minutes))
        assertTrue(lease.isActiveAt(acquiredAt))
        assertTrue(lease.isActiveAt(duringLease))
        assertFalse(lease.isActiveAt(expiresAt))
    }

    @Test
    fun `a lease without an expiry stays active`() {
        val lease = WorkspaceLease(holder = "run-1", acquiredAt = acquiredAt)

        assertTrue(lease.isActiveAt(acquiredAt + 10_000.minutes))
    }

    @Test
    fun `holderAt names the run while its lease is active`() {
        val workspace = leasedTo("run-1")

        assertEquals("run-1", workspace.holderAt(duringLease))
        assertNull(workspace.holderAt(expiresAt))
        assertNull(ExecutionWorkspace("/work/ampere-a").holderAt(duringLease))
    }

    @Test
    fun `a leased workspace is available only to its holder`() {
        val workspace = leasedTo("run-1")

        assertTrue(workspace.isAvailableTo("run-1", duringLease))
        assertFalse(workspace.isAvailableTo("run-2", duringLease))
    }

    @Test
    fun `a workspace becomes available to anyone once its lease expires`() {
        val workspace = leasedTo("run-1")

        assertTrue(workspace.isAvailableTo("run-2", expiresAt))
    }

    @Test
    fun `an unleased workspace is available to anyone`() {
        assertTrue(ExecutionWorkspace("/work/ampere-a").isAvailableTo("run-2", duringLease))
    }

    @Test
    fun `one directory held by two runs is a conflict`() {
        val first = leasedTo("run-1")
        val second = leasedTo("run-2")

        assertTrue(first.conflictsWith(second, duringLease))
        assertTrue(second.conflictsWith(first, duringLease))
    }

    @Test
    fun `the same run holding a directory twice is not a conflict`() {
        assertFalse(leasedTo("run-1").conflictsWith(leasedTo("run-1"), duringLease))
    }

    @Test
    fun `different directories never conflict`() {
        val first = leasedTo("run-1", directory = "/work/ampere-a")
        val second = leasedTo("run-2", directory = "/work/ampere-b")

        assertFalse(first.conflictsWith(second, duringLease))
    }

    @Test
    fun `an expired or absent lease does not conflict`() {
        val first = leasedTo("run-1")
        val second = leasedTo("run-2")

        assertFalse(first.conflictsWith(second, expiresAt))
        assertFalse(first.conflictsWith(ExecutionWorkspace("/work/ampere-a"), duringLease))
    }
}
