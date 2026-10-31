package link.socket.ampere.agents.execution.dispatch

import java.nio.file.Files
import java.nio.file.Path

/** Where one dispatch's agent will work: its branch and its worktree directory. */
data class WorktreePlan(
    val branch: String,
    val path: Path,
)

/**
 * Creates the git worktree a dispatch runs in (AMPR-308, Context §3).
 *
 * `git worktree add -b <branch> <path> <base>`, with the branch name the work source
 * suggests for the ticket — the same name a person gets from the tracker's "copy
 * branch name" — and the path under the Switchboard's own worktree root, never the
 * repository's working directory and never the core default workspace (AMPR-300).
 *
 * ### What it does not repair
 *
 * An interrupted `worktree add` leaves a worktree locked "initializing" and an orphan
 * branch (AMPR-291 experiment E1). Repairing that is the reconciliation pass's job,
 * and it may only touch what a journal recorded — which is why the Switchboard
 * journals the planned path and branch *before* calling [create]. A plan whose path
 * or branch already exists is refused here rather than adopted: adopting a
 * directory nobody recorded is how a supervisor ends up writing into a person's
 * checkout.
 *
 * Every git call goes through [git], whose production value spawns in its own
 * process group (`CancellationAddress`), non-interactively.
 *
 * @param repository The repository the worktrees belong to.
 * @param root The directory worktrees are created under.
 * @param base The commit-ish each branch starts from, e.g. `origin/main`.
 */
class WorktreeProvisioner(
    private val repository: Path,
    private val root: Path,
    private val base: String = DEFAULT_BASE,
    private val git: GitRunner = ProcessGroupGitRunner,
) {

    /**
     * The branch and path [ticket] would get.
     *
     * The work source's suggested branch name when it is a safe ref name, else
     * `switchboard/<identifier>`. The path is the identifier, lowercased, under [root].
     */
    fun plan(ticket: DispatchTicket): WorktreePlan {
        val slug = ticket.identifier.lowercase().replace(UNSAFE_PATH_CHARS, "-").trim('-').ifEmpty { "ticket" }
        val suggested = ticket.branchName?.trim()?.takeIf { isSafeBranch(it) }
        return WorktreePlan(
            branch = suggested ?: "$FALLBACK_BRANCH_PREFIX$slug",
            path = root.resolve(slug).toAbsolutePath().normalize(),
        )
    }

    /**
     * Fetch [base]'s remote branch, so new worktrees start from current work.
     *
     * Best effort: returns why it could not, rather than failing the pass. A stale
     * base costs an agent a rebase; refusing to dispatch over a flaky network would
     * cost the whole wave.
     */
    suspend fun refreshBase(): String? {
        val remote = base.substringBefore('/', missingDelimiterValue = "")
        val branch = base.substringAfter('/', missingDelimiterValue = "")
        if (remote.isEmpty() || branch.isEmpty()) return null

        val fetched = git.run(repository, listOf("fetch", "--quiet", remote, branch))
        return if (fetched.succeeded) null else "could not fetch $base: ${fetched.complaint}"
    }

    /** Create [plan]'s branch and worktree. Fails, creating nothing, when either already exists. */
    suspend fun create(plan: WorktreePlan): Result<Unit> {
        if (Files.exists(plan.path)) {
            return Result.failure(IllegalStateException("the worktree path ${plan.path} already exists"))
        }
        val branchCheck = git.run(repository, listOf("rev-parse", "--verify", "--quiet", "refs/heads/${plan.branch}"))
        if (branchCheck.succeeded) {
            return Result.failure(IllegalStateException("the branch ${plan.branch} already exists"))
        }

        Files.createDirectories(plan.path.parent)
        val added = git.run(repository, listOf("worktree", "add", "-b", plan.branch, plan.path.toString(), base))
        return if (added.succeeded) {
            Result.success(Unit)
        } else {
            Result.failure(GitFailure("worktree add", added))
        }
    }

    /**
     * Remove a worktree and branch this Switchboard created and no agent ever ran in.
     *
     * Only for a dispatch released before its agent started, so the next dispatch of
     * the ticket finds neither its path nor its branch taken. `--force` because the
     * worktree holds the context files this Switchboard wrote; nothing else can be in
     * it.
     */
    suspend fun discard(plan: WorktreePlan): Result<Unit> {
        val removed = git.run(repository, listOf("worktree", "remove", "--force", plan.path.toString()))
        if (!removed.succeeded && Files.exists(plan.path)) {
            return Result.failure(GitFailure("worktree remove", removed))
        }
        val deleted = git.run(repository, listOf("branch", "-D", plan.branch))
        return if (deleted.succeeded) Result.success(Unit) else Result.failure(GitFailure("branch -D", deleted))
    }

    companion object {

        const val DEFAULT_BASE: String = "origin/main"

        /** The branch prefix when the work source suggests no usable name. */
        const val FALLBACK_BRANCH_PREFIX: String = "switchboard/"

        private val UNSAFE_PATH_CHARS = Regex("[^a-z0-9._-]+")

        private val SAFE_BRANCH = Regex("[A-Za-z0-9][A-Za-z0-9._/-]*")

        /**
         * A conservative subset of git's ref-name rules: nothing that could be read as
         * an option, a range, a path climb or a lock file. A name outside it is not
         * necessarily invalid to git, only not trusted from a public source.
         */
        internal fun isSafeBranch(name: String): Boolean =
            SAFE_BRANCH.matches(name) &&
                ".." !in name &&
                "//" !in name &&
                "/." !in name &&
                !name.endsWith("/") &&
                !name.endsWith(".") &&
                !name.endsWith(".lock") &&
                name.length <= 200
    }
}
