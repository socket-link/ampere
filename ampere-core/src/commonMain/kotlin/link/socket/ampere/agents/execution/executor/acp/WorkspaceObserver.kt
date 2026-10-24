package link.socket.ampere.agents.execution.executor.acp

/**
 * What a workspace looks like at one instant, so two looks can say what changed between them
 * (AMPR-492, AMPR-444 D4 layer 5).
 *
 * The post-turn audit is the fence that holds: a harness may write to disk from its own
 * process, bypassing every request this client could have refused, so what actually changed
 * is read off the workspace rather than off what the agent said. An observer reports the
 * paths that are modified relative to the workspace's baseline — on the JVM, what
 * `git status` lists — each with a [Fingerprint]; the executor diffs a snapshot taken before
 * the turn against one taken after.
 *
 * Paths are workspace-relative. A failed snapshot is the observer saying it cannot see this
 * workspace (not a git checkout, no observer bound); the executor then falls back to what the
 * agent reported and says so in the outcome.
 */
interface WorkspaceObserver {

    suspend fun snapshot(): Result<Map<String, Fingerprint>>

    /** An observer that sees nothing, for a workspace nothing can observe. */
    object None : WorkspaceObserver {

        override suspend fun snapshot(): Result<Map<String, Fingerprint>> =
            Result.failure(UnsupportedOperationException("no workspace observer is bound"))
    }

    companion object {

        /**
         * The paths that differ between [before] and [after]: new, gone, or with another
         * fingerprint. A path dirty before the turn and still dirty after it with the same
         * fingerprint did not change during the turn and is not reported.
         */
        fun changedBetween(
            before: Map<String, Fingerprint>,
            after: Map<String, Fingerprint>,
        ): Set<String> {
            val changed = linkedSetOf<String>()
            after.forEach { (path, fingerprint) -> if (before[path] != fingerprint) changed += path }
            before.keys.forEach { path -> if (path !in after) changed += path }
            return changed
        }
    }
}

/**
 * Enough of a file to tell whether it changed: its size, its modification time when the
 * platform reports one, and a content digest when the observer computed one. Two files with
 * equal fingerprints are taken to be the same file unchanged.
 */
data class Fingerprint(
    val size: Long,
    val modifiedAtMillis: Long? = null,
    val digest: String? = null,
) {
    companion object {
        /** A path the baseline knows that is no longer on disk. */
        val DELETED: Fingerprint = Fingerprint(size = -1)
    }
}
