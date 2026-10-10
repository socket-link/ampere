package link.socket.ampere.agents.domain.memory

/**
 * The `task_type` vocabulary the loop files [link.socket.ampere.agents.domain.knowledge.Knowledge]
 * under, and recalls it by.
 *
 * Recall's first strategy matches [MemoryContext.taskType] against the stored `task_type`
 * ([AgentMemoryService.recallRelevantKnowledge]), so the write side and the read side have to
 * agree on the exact strings. They were loose literals on the read side and absent on the Arc
 * write side, which is half of why a learning captured by an Arc run was invisible to the next
 * one (AMPR-402): an entry filed under a task type nothing recalls with is stored but unreachable.
 *
 * Deliberately coarse. These are retrieval buckets, not a taxonomy of work — a type so specific
 * that only one run ever writes it defeats the cross-run pattern matching Recall exists for.
 */
object MemoryTaskTypes {

    /** Work that changes code: `Task.CodeChange`, `ExecutionOutcome.CodeChanged`. */
    const val CODE_CHANGE: String = "code_change"

    /** Everything the loop has no narrower bucket for. */
    const val GENERIC: String = "generic"
}
