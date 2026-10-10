package link.socket.ampere.agents.execution.dispatch

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import link.socket.ampere.agents.execution.process.ProcessGroups

/**
 * One git invocation's result. Never a throw: a repair pass that aborted on the
 * first non-zero exit would stop in the middle of a recovery sequence.
 */
data class GitInvocation(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val succeeded: Boolean get() = exitCode == 0

    /** The first line of whatever git said about a failure, for a report. */
    val complaint: String
        get() = (stderr.ifBlank { stdout }).lineSequence().firstOrNull { it.isNotBlank() }
            ?: "git exited $exitCode"
}

/**
 * Runs one git command in one directory.
 *
 * A seam rather than a hard call to `git` so a test can drive the repair logic
 * without a repository, and so the production path can insist on
 * [ProcessGroups] — a `git` child that outlives its spawner is the AMPR-291
 * experiment E1 finding, where an orphaned git process finished a checkout after
 * its parent was killed.
 */
fun interface GitRunner {
    suspend fun run(directory: Path, args: List<String>): GitInvocation
}

/**
 * The production [GitRunner]: `git` in its own process group, non-interactive.
 *
 * Non-interactive is not optional. On the bash path a grouped command is a
 * background job of the caller's session, so a `git` that tries to read the
 * terminal — a credential prompt — is stopped with SIGTTIN until something kills
 * it (see the `CancellationAddress` concept's anti-patterns). Recovery must never
 * be the thing that hangs.
 */
object ProcessGroupGitRunner : GitRunner {

    override suspend fun run(directory: Path, args: List<String>): GitInvocation {
        val builder = ProcessBuilder(listOf("git") + args)
            .directory(directory.toFile())
            .redirectErrorStream(false)
        builder.environment()["GIT_TERMINAL_PROMPT"] = "0"
        builder.environment()["GIT_OPTIONAL_LOCKS"] = "0"
        return ProcessGroups.run(builder) { grouped ->
            val out = grouped.process.inputStream.bufferedReader().readText()
            val err = grouped.process.errorStream.bufferedReader().readText()
            GitInvocation(grouped.process.waitFor(), out, err)
        }
    }
}

/** What a surviving worktree holds, and therefore whether it can be deleted. */
enum class WorktreeCondition {

    /** Nothing uncommitted, nothing a remote-tracking ref does not already have. */
    CLEAN,

    /** Modified, staged or untracked files. Deleting it would lose them. */
    UNCOMMITTED,

    /** Commits no remote-tracking ref contains. Deleting it would lose them. */
    UNPUSHED,

    /** git could not say. Treated as a hold, because the safe error is to keep. */
    UNKNOWN,
}

/**
 * One line-item of `git worktree list --porcelain`.
 *
 * @property locked Whether git holds the worktree's metadata locked.
 *   `git worktree add` locks a worktree *while it is being created*, with reason
 *   `initializing`, and a supervisor killed inside that window leaves the lock
 *   behind — verified residue, and the case that needs `remove -f -f`.
 * @property prunable Whether git considers the entry removable, usually because
 *   its directory is gone.
 */
data class WorktreeEntry(
    val path: String,
    val head: String? = null,
    val branch: String? = null,
    val detached: Boolean = false,
    val bare: Boolean = false,
    val locked: Boolean = false,
    val lockReason: String? = null,
    val prunable: Boolean = false,
    val prunableReason: String? = null,
) {

    /** Whether this is the lock a supervisor killed mid-`worktree add` leaves. */
    val lockedInitializing: Boolean
        get() = locked && (lockReason == null || INITIALIZING in lockReason.lowercase())

    private companion object {
        const val INITIALIZING = "initializing"
    }
}

/**
 * The local half of the startup reconciliation pass: git surgery on the residue a
 * killed supervisor leaves in and around a worktree (AMPR-310, step 3).
 *
 * ### The residue classes, and the exact sequence each one needs
 *
 * Every one of these was verified in the AMPR-291 recon, and each needs a
 * different command — which is why the pass is a sequence and not a `prune`:
 *
 * - **`.git/index.lock` after a kill mid-`git add`** — delete the lock file. Nothing
 *   else can, and every later git operation in that worktree fails until it is gone.
 * - **Worktree metadata locked with reason `initializing`** —
 *   `git worktree remove -f -f`. Plain `--force` refuses and `prune` will not touch it.
 * - **A worktree directory that is simply gone** — `git worktree prune`.
 * - **A branch left by an interrupted creation** — `git branch -D`, but only with no
 *   surviving worktree, no merge request, and nothing unmerged.
 *
 * ### Never while a writer may be alive
 *
 * Deleting a lock file that a *live* git process is holding is how you corrupt a
 * repository. The caller must have reaped the dispatch's process group first —
 * step 2 before step 3 — which is the whole reason the pass is ordered. This class
 * does not check, because it cannot: liveness is the journal's and
 * [ProcessGroups]'s question.
 *
 * ### The safe error
 *
 * Every classification here fails towards *keep*. An unreadable status, a git
 * command that errors, a branch whose merge state cannot be determined — all of
 * them hold the worktree for a person instead of deleting it. The asymmetry is the
 * same one rule B4 states for the work source: visible mess is recoverable, and
 * destroyed work is not.
 *
 * @property repository The repository whose worktrees this pass may touch. A
 *   journaled path outside it is reported, never deleted — one pass speaks for one
 *   repository, and nothing in a [DispatchRecord] says which one a path belongs to.
 */
class WorktreeRepair(
    val repository: Path,
    private val git: GitRunner = ProcessGroupGitRunner,
) {

    /**
     * Every worktree of [repository], the main one included.
     *
     * The evidence for step 3: the journal says which path a dispatch was given,
     * and this says what git currently believes about it.
     */
    suspend fun worktrees(): Result<List<WorktreeEntry>> {
        val result = git.run(repository, listOf("worktree", "list", "--porcelain"))
        if (!result.succeeded) return Result.failure(GitFailure("worktree list", result))
        return Result.success(parseWorktreeList(result.stdout))
    }

    /** The entry for [path], matched by resolved path so a symlink cannot hide it. */
    suspend fun entryFor(path: String): Result<WorktreeEntry?> =
        worktrees().map { entries ->
            val wanted = resolve(Paths.get(path))
            entries.firstOrNull { resolve(Paths.get(it.path)) == wanted }
        }

    /**
     * Delete the stale `*.lock` files in and around [worktree]'s git directory.
     *
     * ### What it deletes, and why exactly that
     *
     * A worktree's `.git` is a file pointing at `<repo>/.git/worktrees/<name>`, and
     * the lock that blocks every later git operation — `index.lock` — lives there,
     * not in the working tree. Some locks are *shared*: `packed-refs.lock` in the
     * common directory blocks ref updates for every worktree at once. So the sweep
     * covers both directories, and in each: `*.lock` at the top level, plus `*.lock`
     * anywhere under `refs/` and `logs/`, where a per-ref lock
     * (`refs/heads/x.lock`) would otherwise defeat the branch deletion in step 3.
     *
     * Bounded on purpose. A blanket recursive sweep would also delete the lock of a
     * concurrent *legitimate* operation elsewhere in the object store, and those are
     * not residue this pass can reason about.
     *
     * @return the lock files deleted, as paths, oldest residue first. Empty is the
     *   ordinary answer — most dispatches do not die holding a lock.
     */
    suspend fun clearStaleLocks(worktree: Path): Result<List<String>> {
        val gitDirs = gitDirectoriesOf(worktree).getOrElse { return Result.failure(it) }
        return runCatching {
            withContext(Dispatchers.IO) {
                gitDirs.flatMap { dir -> lockFilesUnder(dir) }
                    .distinct()
                    .filter { runCatching { Files.deleteIfExists(it) }.getOrDefault(false) }
                    .map { it.toString() }
            }
        }
    }

    /**
     * `git worktree remove --force --force` — the only sequence that clears a
     * worktree locked with reason `initializing`.
     *
     * Both `--force` flags are load-bearing and were verified: one `--force`
     * removes a *dirty* worktree but still refuses a *locked* one, and
     * `git worktree prune` ignores it entirely. Only ever call this for the
     * initializing lock: the dispatch was killed before the worktree was finished,
     * so there is nothing in it to lose.
     */
    suspend fun forceRemove(worktree: Path): GitInvocation =
        git.run(repository, listOf("worktree", "remove", "--force", "--force", worktree.toString()))

    /**
     * `git worktree remove` on a worktree classified [WorktreeCondition.CLEAN].
     *
     * Deliberately without `--force`: if the classification was wrong, git refuses
     * and the pass reports a failure, rather than deleting work this pass promised
     * to hold.
     */
    suspend fun remove(worktree: Path): GitInvocation =
        git.run(repository, listOf("worktree", "remove", worktree.toString()))

    /** `git worktree prune` — tidies the metadata of worktrees whose directory is gone. */
    suspend fun prune(): GitInvocation = git.run(repository, listOf("worktree", "prune"))

    /**
     * Whether [worktree] holds anything a redispatch would destroy.
     *
     * Two questions, both local. *Uncommitted* is `git status --porcelain`,
     * untracked files included — a redispatch starts from a fresh checkout, so an
     * untracked file is exactly as lost as a modified one. *Unpushed* is whether any
     * remote-tracking ref contains the tip, which needs no network and so cannot
     * make recovery wait on one.
     *
     * The remote-tracking test is as fresh as the last fetch, and it errs the safe
     * way: work that *was* pushed by a remote this clone has not fetched reads as
     * unpushed and is held, rather than the reverse.
     */
    suspend fun condition(worktree: Path): WorktreeCondition {
        val status = git.run(worktree, listOf("status", "--porcelain"))
        if (!status.succeeded) return WorktreeCondition.UNKNOWN
        if (status.stdout.isNotBlank()) return WorktreeCondition.UNCOMMITTED

        val head = git.run(worktree, listOf("rev-parse", "HEAD"))
        if (!head.succeeded) return WorktreeCondition.UNKNOWN
        return when (containedByRemote(head.stdout.trim())) {
            true -> WorktreeCondition.CLEAN
            false -> WorktreeCondition.UNPUSHED
            null -> WorktreeCondition.UNKNOWN
        }
    }

    /** Whether [branch] is a local branch of this repository. */
    suspend fun branchExists(branch: String): Boolean =
        git.run(repository, listOf("rev-parse", "--verify", "--quiet", "refs/heads/$branch")).succeeded

    /**
     * Whether any remote-tracking ref contains [branch]'s tip — null when git could
     * not say, which the caller must treat as a hold.
     */
    suspend fun branchIsPushed(branch: String): Boolean? = containedByRemote("refs/heads/$branch")

    /** `git branch -D` — unconditional, because the caller has already earned it. */
    suspend fun deleteBranch(branch: String): GitInvocation =
        git.run(repository, listOf("branch", "-D", branch))

    /**
     * Whether a remote-tracking ref contains [commitish]. Null means git failed, and
     * "we could not tell" is not "no".
     */
    private suspend fun containedByRemote(commitish: String): Boolean? {
        val contains = git.run(
            repository,
            listOf("for-each-ref", "--contains", commitish, "--count=1", "--format=%(refname)", "refs/remotes"),
        )
        return if (contains.succeeded) contains.stdout.isNotBlank() else null
    }

    /**
     * [worktree]'s own git directory and the repository's common one, deduplicated.
     *
     * Asked of git rather than assembled from `<repo>/.git/worktrees/<name>`,
     * because the name git gave the admin directory is not derivable from the path
     * (it disambiguates collisions) and a wrong guess means sweeping the wrong
     * directory.
     */
    private suspend fun gitDirectoriesOf(worktree: Path): Result<List<Path>> {
        if (!Files.exists(worktree)) return Result.success(emptyList())
        val gitDir = git.run(worktree, listOf("rev-parse", "--absolute-git-dir"))
        if (!gitDir.succeeded) return Result.failure(GitFailure("rev-parse --absolute-git-dir", gitDir))
        val own = Paths.get(gitDir.stdout.trim())

        // --git-common-dir answers relative to the working directory on some git
        // versions, so it is resolved against the worktree rather than trusted.
        val common = git.run(worktree, listOf("rev-parse", "--git-common-dir"))
        val shared = if (common.succeeded && common.stdout.isNotBlank()) {
            worktree.resolve(common.stdout.trim()).normalize()
        } else {
            own
        }
        return Result.success(listOf(own, shared).map { resolve(it) }.distinct())
    }

    /** `*.lock` at the top of [dir], and under its `refs/` and `logs/` trees. */
    private fun lockFilesUnder(dir: Path): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        val top = dir.toFile().listFiles().orEmpty().filter { it.isFile && it.isLock }
        // walkTopDown yields nothing for a directory that is not there, so a
        // repository with no reflogs needs no special case.
        val nested = LOCK_SUBTREES.flatMap { name ->
            dir.resolve(name).toFile().walkTopDown().filter { it.isFile && it.isLock }.toList()
        }
        return (top + nested).map { it.toPath() }
    }

    private val File.isLock: Boolean get() = name.endsWith(LOCK_SUFFIX)

    /** The real path when it exists, so a symlinked temp directory still matches. */
    private fun resolve(path: Path): Path =
        runCatching { path.toRealPath() }.getOrElse { path.toAbsolutePath().normalize() }

    companion object {

        private const val LOCK_SUFFIX = ".lock"

        private val LOCK_SUBTREES = listOf("refs", "logs")

        /**
         * Parse `git worktree list --porcelain`: blank-line-separated stanzas, one
         * `worktree <path>` line each, then zero or more attribute lines that are
         * either bare (`detached`, `bare`) or `<key> <value>` (`HEAD`, `branch`,
         * `locked <reason>`, `prunable <reason>`).
         *
         * `locked` and `prunable` appear both ways — bare when git has no reason to
         * give — so presence and reason are tracked separately rather than reading
         * an absent reason as an absent lock.
         */
        internal fun parseWorktreeList(porcelain: String): List<WorktreeEntry> {
            val entries = mutableListOf<WorktreeEntry>()
            var current: WorktreeEntry? = null

            fun flush() {
                current?.let { entries += it }
                current = null
            }

            porcelain.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty()) {
                    flush()
                    return@forEach
                }
                val key = line.substringBefore(' ')
                val value = line.substringAfter(' ', missingDelimiterValue = "").trim().ifBlank { null }
                when (key) {
                    "worktree" -> {
                        flush()
                        current = WorktreeEntry(path = value ?: "")
                    }
                    "HEAD" -> current = current?.copy(head = value)
                    "branch" -> current = current?.copy(branch = value?.removePrefix("refs/heads/"))
                    "detached" -> current = current?.copy(detached = true)
                    "bare" -> current = current?.copy(bare = true)
                    "locked" -> current = current?.copy(locked = true, lockReason = value)
                    "prunable" -> current = current?.copy(prunable = true, prunableReason = value)
                    else -> Unit
                }
            }
            flush()
            return entries.filter { it.path.isNotBlank() }
        }
    }
}

/** A git command the repair pass needed and did not get. */
class GitFailure(
    command: String,
    val invocation: GitInvocation,
) : Exception("git $command failed (${invocation.exitCode}): ${invocation.complaint}")
