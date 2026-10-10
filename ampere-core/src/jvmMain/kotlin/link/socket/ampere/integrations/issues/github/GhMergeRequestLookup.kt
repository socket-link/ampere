package link.socket.ampere.integrations.issues.github

import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.execution.dispatch.MergeRequestLookup
import link.socket.ampere.agents.execution.dispatch.MergeRequestRef
import link.socket.ampere.agents.execution.dispatch.MergeRequestState
import link.socket.ampere.agents.execution.process.ProcessGroups

/**
 * [MergeRequestLookup] over `gh pr list --head`, for the startup reconciliation
 * pass (AMPR-310, step 5).
 *
 * ### Why a list and not a create-and-see
 *
 * Opening a pull request is not idempotent: a supervisor killed around the call
 * cannot know whether it succeeded, and retrying opens a second one for the same
 * branch (AMPR-291 recon finding, cell note 16). `gh pr list --head <branch>` is
 * the question that has a safe answer, and it is asked `--state all`, because
 * *merged or closed* still proves creation happened even though only an *open*
 * request owns the branch.
 *
 * ### An unparseable answer is a failure, not an empty list
 *
 * Every non-zero exit, and every body this build cannot decode, comes back as
 * `Result.failure`. The pass deletes branches on an empty answer, so "no merge
 * request" must never be the fallback for "gh did not work" — an unauthenticated
 * `gh` would otherwise license deleting the branch of every open review.
 *
 * @param directory The repository to ask from; `gh` resolves the remote from it.
 * @param repository `owner/name`, when the remote should not be trusted or the
 *   directory is not a checkout of the repository in question.
 */
class GhMergeRequestLookup(
    private val directory: Path,
    private val repository: String? = null,
    private val limit: Int = DEFAULT_LIMIT,
) : MergeRequestLookup {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun forHeadBranch(branch: String): Result<List<MergeRequestRef>> {
        require(branch.isNotBlank()) { "A merge-request lookup needs a head branch" }

        val args = buildList {
            addAll(listOf("pr", "list", "--head", branch, "--state", "all"))
            addAll(listOf("--limit", limit.toString()))
            addAll(listOf("--json", FIELDS))
            repository?.let { addAll(listOf("--repo", it)) }
        }

        val result = runCatching { executeGh(args) }.getOrElse { return Result.failure(it) }
        if (result.exitCode != 0) {
            return Result.failure(
                IllegalStateException(
                    "gh pr list --head $branch exited ${result.exitCode}: " +
                        result.stderr.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                ),
            )
        }

        return runCatching {
            json.decodeFromString<List<GhPullRequest>>(result.stdout.ifBlank { "[]" })
                .map { it.toRef() }
                // Open first, so `owningBranch` and `mostRelevant` do not depend on
                // the order gh happens to return.
                .sortedBy { if (it.state.ownsBranch) 0 else 1 }
        }
    }

    private suspend fun executeGh(args: List<String>): GhResult =
        ProcessGroups.run(
            ProcessBuilder(listOf("gh") + args)
                .directory(directory.toFile())
                .redirectErrorStream(false)
                .also { it.environment()["GH_PROMPT_DISABLED"] = "1" },
        ) { grouped ->
            val out = grouped.process.inputStream.bufferedReader().readText()
            val err = grouped.process.errorStream.bufferedReader().readText()
            GhResult(grouped.process.waitFor(), out, err)
        }

    private data class GhResult(val exitCode: Int, val stdout: String, val stderr: String)

    private companion object {

        /** Enough to decide; `headRefName` is not asked for because `--head` pinned it. */
        const val FIELDS = "number,url,state"

        /**
         * One branch cannot plausibly head more than a handful of requests, and a
         * cap keeps a misparsed answer from turning into an unbounded read.
         */
        const val DEFAULT_LIMIT = 20
    }
}

/** `gh pr list --json number,url,state` output. */
@Serializable
internal data class GhPullRequest(
    val number: Int? = null,
    val url: String = "",
    val state: String? = null,
) {
    fun toRef(): MergeRequestRef = MergeRequestRef(
        url = url,
        number = number,
        // An unrecognised state is UNKNOWN, which still owns the branch: a forge
        // vocabulary this build does not know must not read as "safe to delete".
        state = when (state?.uppercase()) {
            "OPEN" -> MergeRequestState.OPEN
            "MERGED" -> MergeRequestState.MERGED
            "CLOSED" -> MergeRequestState.CLOSED
            else -> MergeRequestState.UNKNOWN
        },
    )
}
