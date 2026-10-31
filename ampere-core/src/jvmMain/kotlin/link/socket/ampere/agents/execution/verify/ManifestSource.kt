package link.socket.ampere.agents.execution.verify

import java.nio.file.Path
import link.socket.ampere.agents.execution.dispatch.GitRunner
import link.socket.ampere.agents.execution.dispatch.ProcessGroupGitRunner

/** Where the manifest a dispatch is verified against comes from. */
fun interface ManifestSource {

    /**
     * The manifest for work in [worktree], or a failure: [ManifestInvalid] for a
     * manifest that breaks schema v1, [ManifestMissing] when there is none.
     */
    suspend fun load(worktree: Path): Result<VerifyManifest>
}

/** The repository declares no definition of done, so nothing can be verified. */
class ManifestMissing(where: String) : Exception("no ${VerifyManifest.PATH} at $where")

/**
 * The manifest as the dispatch's base revision declares it — never as the worktree
 * has it.
 *
 * The worktree is the agent's to write, and the manifest is the definition of done
 * the agent is graded against: an agent that deleted a red gate from its own copy
 * would pass the gate it deleted. Reading `<base>:.ampere/verify.yml` out of the
 * repository's object store puts the manifest out of the agent's reach. The price is
 * that a ticket which legitimately changes the manifest is verified against the old
 * one until it merges — the conservative direction, because the old definition of
 * done is the one a person already reviewed.
 *
 * @param repository The repository the worktrees belong to.
 * @param base The revision whose manifest applies, e.g. `origin/main` — the same
 *   commit-ish the worktrees are cut from.
 */
class BaseRevisionManifestSource(
    private val repository: Path,
    private val base: String,
    private val git: GitRunner = ProcessGroupGitRunner,
) : ManifestSource {

    override suspend fun load(worktree: Path): Result<VerifyManifest> {
        val shown = git.run(repository, listOf("show", "$base:${VerifyManifest.PATH}"))
        if (!shown.succeeded) return Result.failure(ManifestMissing(base))
        return VerifyManifestParser.parse(shown.stdout)
    }
}
