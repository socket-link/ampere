package link.socket.ampere.agents.execution.executor.acp

/**
 * One line in, one line out: the byte-level seam under [AcpConnection] (AMPR-490).
 *
 * ACP frames its JSON-RPC as one JSON object per newline-terminated line on the agent's
 * stdin and stdout. This interface is that framing and nothing else, so the connection —
 * and the executor over it — are `commonMain` and testable against an in-memory pair,
 * while the one implementation that owns a process is `jvmMain`
 * (`StdioAcpTransport`, AMPR-493).
 */
interface AcpTransport {

    /** Whether the far side can still be written to. False once it exited or was closed. */
    val isAlive: Boolean

    /**
     * Brings the far side up — spawns the agent, opens the streams. Idempotent. A failure
     * is the reason the agent could not start, never an exception.
     */
    suspend fun start(): Result<Unit>

    /** Writes one message, without its newline. Throws when the far side is gone. */
    suspend fun send(line: String)

    /**
     * The next message, without its newline, or null once the far side has closed its
     * stream. Suspends until one arrives.
     */
    suspend fun receive(): String?

    /**
     * Ends the far side and releases everything. Idempotent. A blocked [receive] returns
     * null after this.
     */
    suspend fun close()
}
