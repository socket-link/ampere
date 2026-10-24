package link.socket.ampere.agents.execution.executor.acp

import link.socket.ampere.agents.domain.cognition.FileAccessScope

/**
 * The deterministic answer to everything an ACP agent asks the client for during a turn
 * (AMPR-492, AMPR-444 D4 layers 3 and 4).
 *
 * Two questions, one rule. A path may be touched when it lies inside the workspace, escapes
 * it by no `..`, and passes the spark's [FileAccessScope] for the access asked — reading for
 * a read, writing for an edit, delete or move. A permission request is judged by the paths
 * its tool call names and by its kind; a `fs/read_text_file` or `fs/write_text_file` by its
 * one path. A shell command is the [HarnessPolicy]'s to allow. Nothing here asks a person,
 * and nothing here remembers: every request is decided on its own.
 *
 * Allowing picks the option of kind `allow_once` and never `allow_always`: a standing grant
 * would let the agent's next edit in the same session skip this gate, and the only layer
 * left would be the post-turn audit, which can report an out-of-scope write but not prevent
 * it. An agent that offers no `allow_once` has its turn cancelled instead.
 *
 * A null [scope] is unconstrained inside the workspace — the meaning a null
 * `ExecutionRequest.fileAccessScope` has for every file tool (AMPR-414).
 *
 * @param workspaceRoot The absolute directory the session is rooted at; `cwd` of
 *   `session/new`. Paths the agent names are absolute (ACP), and a relative one is read as
 *   workspace-relative.
 */
class HarnessFence(
    workspaceRoot: String,
    private val scope: FileAccessScope?,
    private val policy: HarnessPolicy,
) {

    val root: String = workspaceRoot.trimEnd('/').ifEmpty { "/" }

    private val rootPrefix: String = if (root == "/") "/" else "$root/"

    /** Whether one path may be touched, and if not, why. */
    sealed interface Verdict {
        data object Allowed : Verdict

        data class Refused(val reason: String) : Verdict
    }

    /** The answer to a `session/request_permission`. */
    sealed interface Decision {

        /**
         * Answer with [optionId]. [refusedBecause] is set when the option chosen is a
         * rejection, so the caller can say why.
         */
        data class Select(
            val optionId: String,
            val refusedBecause: String? = null,
        ) : Decision

        /** The options offered leave no acceptable answer: cancel the turn and say why. */
        data class CancelTurn(val reason: String) : Decision
    }

    /**
     * The workspace-relative form of [path], or null when it is outside the workspace or
     * would escape it. `.` segments and doubled separators are dropped; a `..` anywhere is a
     * refusal, because [FileAccessScope.matches] does not resolve it and a pattern match on a
     * path that climbs out of the root would bless the escape.
     */
    fun relativize(path: String): String? {
        val relative = when {
            path == root -> ""
            path.startsWith(rootPrefix) -> path.removePrefix(rootPrefix)
            !path.startsWith("/") -> path
            else -> return null
        }
        val segments = relative.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) return null
        return segments.joinToString("/")
    }

    /** [path] as an absolute path: itself when it already is one, else rooted at the workspace. */
    fun absolutize(path: String): String = if (path.startsWith("/")) path else "$rootPrefix$path"

    fun checkRead(path: String): Verdict = check(path, reading = true)

    fun checkWrite(path: String): Verdict = check(path, reading = false)

    /**
     * The option to answer [request] with, chosen by the kind of its tool call and the paths
     * the call names.
     */
    fun decide(request: RequestPermissionParams): Decision {
        val refusal = refusalFor(request.toolCall)
        return if (refusal == null) allow(request.options) else refuse(request.options, refusal)
    }

    /** Why [call] may not run, or null when it may. */
    fun refusalFor(call: PermissionToolCall): String? {
        val kind = call.kind
        val paths = call.locations.orEmpty().map { it.path } +
            call.content.orEmpty().filter { it.type == ToolCallContent.DIFF }.mapNotNull { it.path }
        val writes = (kind != null && kind in ToolKind.WRITING) || (kind == null && paths.isNotEmpty())
        val reads = kind != null && kind in ToolKind.READING
        return when {
            writes -> when {
                scope != null && scope.writePatterns.isEmpty() -> "the file access scope permits no writes"
                else -> paths.firstNotNullOfOrNull { (checkWrite(it) as? Verdict.Refused)?.reason }
            }
            reads -> paths.firstNotNullOfOrNull { (checkRead(it) as? Verdict.Refused)?.reason }
            kind == ToolKind.EXECUTE -> when (policy.shell) {
                HarnessPolicy.ShellAccess.Allow -> null
                HarnessPolicy.ShellAccess.Deny -> "shell commands are not permitted by the harness policy"
            }
            else -> null
        }
    }

    private fun check(path: String, reading: Boolean): Verdict {
        val relative = relativize(path)
            ?: return Verdict.Refused("\"$path\" is outside the workspace $root")
        if (relative.isEmpty()) return Verdict.Refused("\"$path\" is the workspace root, not a file")
        val scope = scope ?: return Verdict.Allowed
        val allowed = if (reading) scope.allowsRead(relative) else scope.allowsWrite(relative)
        if (allowed) return Verdict.Allowed
        val reason = scope.forbiddingPattern(relative)?.let { "blocked by forbidden pattern \"$it\"" }
            ?: if (reading) "no read pattern permits it" else "no write pattern permits it"
        return Verdict.Refused("\"$relative\" $reason")
    }

    private fun allow(options: List<PermissionOption>): Decision =
        options.firstOrNull { it.kind == PermissionOptionKind.ALLOW_ONCE }
            ?.let { Decision.Select(it.optionId) }
            ?: Decision.CancelTurn("the agent offered no allow_once option, and this client never grants allow_always")

    private fun refuse(options: List<PermissionOption>, reason: String): Decision {
        val option = options.firstOrNull { it.kind == PermissionOptionKind.REJECT_ONCE }
            ?: options.firstOrNull { it.kind == PermissionOptionKind.REJECT_ALWAYS }
        return option?.let { Decision.Select(it.optionId, refusedBecause = reason) }
            ?: Decision.CancelTurn("$reason, and the agent offered no reject option")
    }
}
