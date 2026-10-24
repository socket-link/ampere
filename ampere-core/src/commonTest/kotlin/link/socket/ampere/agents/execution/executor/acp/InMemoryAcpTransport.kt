package link.socket.ampere.agents.execution.executor.acp

import kotlinx.coroutines.channels.Channel

/**
 * An [AcpTransport] with no process behind it: two unbounded channels, one in each direction
 * (AMPR-490). The client side is the interface; the agent side is [agentRead], [agentWrite]
 * and [agentExit], which a [ScriptedAcpAgent] drives.
 *
 * [agentExit] closes the agent-to-client channel without closing the transport, which is what
 * a process that died looks like from the client: the next [receive] returns null and
 * [isAlive] is false, while the client has not called [close].
 */
class InMemoryAcpTransport : AcpTransport {

    private val toAgent = Channel<String>(Channel.UNLIMITED)
    private val toClient = Channel<String>(Channel.UNLIMITED)

    private var started = false
    private var closed = false
    private var agentGone = false

    /** When set, [start] fails with it, like a binary that cannot be spawned. */
    var startFailure: Throwable? = null

    /** Every line the client sent, in order. */
    val sentByClient: MutableList<String> = mutableListOf()

    var closeCount: Int = 0
        private set

    override val isAlive: Boolean
        get() = started && !closed && !agentGone

    override suspend fun start(): Result<Unit> {
        startFailure?.let { return Result.failure(it) }
        started = true
        return Result.success(Unit)
    }

    override suspend fun send(line: String) {
        if (closed || agentGone) throw IllegalStateException("the agent is gone")
        sentByClient += line
        toAgent.send(line)
    }

    override suspend fun receive(): String? = toClient.receiveCatching().getOrNull()

    override suspend fun close() {
        closed = true
        closeCount++
        toAgent.close()
        toClient.close()
    }

    // ---- the agent's side --------------------------------------------------------------------

    suspend fun agentRead(): String? = toAgent.receiveCatching().getOrNull()

    /** Writes a line to the client; false when the client has closed the transport. */
    suspend fun agentWrite(line: String): Boolean =
        runCatching { toClient.send(line) }.isSuccess

    /** The agent process dying: its stream closes, nothing else happens. */
    fun agentExit() {
        agentGone = true
        toClient.close()
    }
}
