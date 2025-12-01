package link.socket.ampere.agents.execution.executor

import kotlinx.serialization.Serializable

/**
 * Universal interface for code execution engines.
 * Implementations can wrap any coding agent or tool (Junie, Claude Code, Aider, etc.)
 *
 * This is the motor cortex interface - agents send execution requests through
 * this abstraction without knowing which muscles (tools) perform the work.
 *
 * Think of this as the electrical relay specification: it defines what it means
 * to "execute code" without caring about the underlying switching mechanism.
 */
@Serializable
sealed interface CodeExecutor : Executor
