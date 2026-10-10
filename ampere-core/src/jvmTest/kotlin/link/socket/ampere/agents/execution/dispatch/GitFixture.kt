package link.socket.ampere.agents.execution.dispatch

import java.nio.file.Files
import java.nio.file.Path

/**
 * Real git repositories for the reconciliation pass's tests.
 *
 * Every recovery sequence in [WorktreeRepair] was established by observing git, and
 * the value of the step is that the exact sequence is the one that works — so these
 * tests drive the real binary rather than a model of it. `git` is a hard
 * prerequisite of this repository's own workflow, so a missing one is a failure
 * here, not a skip.
 */
internal object GitFixture {

    /**
     * A repository with one commit on `main`, and `origin/main` pointing at it.
     *
     * The remote-tracking ref has no remote behind it, which is all that is needed:
     * "is this branch pushed" is answered from `refs/remotes` locally, and a
     * repository with none would read every branch as unpushed and hold everything.
     */
    fun repository(parent: Path, name: String = "repo"): Path {
        val repo = parent.resolve(name)
        Files.createDirectories(repo)
        git(repo, "init", "-q", "-b", "main")
        Files.writeString(repo.resolve("README.md"), "ampere\n")
        git(repo, "add", "README.md")
        git(repo, "commit", "-q", "-m", "init")
        git(repo, "update-ref", "refs/remotes/origin/main", "HEAD")
        return repo
    }

    /** A worktree of [repo] at [path] on a new branch [branch]. */
    fun addWorktree(repo: Path, path: Path, branch: String): Path {
        git(repo, "worktree", "add", "-q", path.toString(), "-b", branch)
        return path
    }

    /** Runs git with a pinned identity, and fails the test if it does not succeed. */
    fun git(directory: Path, vararg args: String): String {
        val command = listOf(
            "git",
            "-c", "user.email=reconcile@example.invalid",
            "-c", "user.name=Reconciler",
            "-c", "commit.gpgsign=false",
        ) + args
        val process = ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        check(exit == 0) { "git ${args.joinToString(" ")} failed ($exit): $output" }
        return output
    }

    /** git's own exit code, for pinning the sequences that have to fail. */
    fun gitExit(directory: Path, vararg args: String): Int {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .start()
        process.inputStream.readAllBytes()
        return process.waitFor()
    }

    /** The absolute git admin directory of [worktree]. */
    fun gitDirOf(worktree: Path): Path = Path.of(git(worktree, "rev-parse", "--absolute-git-dir").trim())

    /** The directory shared by every worktree of [worktree]'s repository. */
    fun commonDirOf(worktree: Path): Path = Path.of(git(worktree, "rev-parse", "--git-common-dir").trim())

    /** Commits [name] inside [worktree], producing work no remote ref contains. */
    fun commitUnpushed(worktree: Path, name: String = "done.txt") {
        Files.writeString(worktree.resolve(name), "finished work\n")
        git(worktree, "add", name)
        git(worktree, "commit", "-q", "-m", "work")
    }
}
