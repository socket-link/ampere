package link.socket.ampere.agents.execution.dispatch

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Git surgery against real repositories (AMPR-310 step 3).
 *
 * Fixtures, not mocks, because every recovery sequence here was established by
 * *observing* git in the AMPR-291 recon and the whole value of the step is that the
 * exact sequence is the one that works: a plain `--force` really does refuse a
 * locked worktree, `prune` really does ignore it, and an `index.lock` really does
 * block every subsequent write until something deletes the file.
 */
class WorktreeRepairTest {

    private val temporary = mutableListOf<Path>()

    @AfterTest
    fun tearDown() {
        temporary.forEach { it.toFile().deleteRecursively() }
    }

    private fun tempDir(): Path =
        Files.createTempDirectory("ampere-worktree").toRealPath().also { temporary.add(it) }

    private fun repository(): Path = GitFixture.repository(tempDir())

    private fun git(directory: Path, vararg args: String): String = GitFixture.git(directory, *args)

    private fun gitExit(directory: Path, vararg args: String): Int = GitFixture.gitExit(directory, *args)

    private fun repair(repo: Path) = WorktreeRepair(repo)

    @Test
    fun `the porcelain listing parses bare and valued attributes`() {
        val listing = """
            worktree /repo
            HEAD 1111111111111111111111111111111111111111
            branch refs/heads/main

            worktree /repo/../wt-initializing
            HEAD 2222222222222222222222222222222222222222
            branch refs/heads/miley/ampr-310
            locked initializing

            worktree /repo/../wt-gone
            HEAD 3333333333333333333333333333333333333333
            detached
            locked
            prunable gitdir file points to non-existent location

            worktree /repo/../bare
            bare
        """.trimIndent()

        val entries = WorktreeRepair.parseWorktreeList(listing)

        assertEquals(4, entries.size)
        assertEquals("main", entries[0].branch)
        assertFalse(entries[0].locked)

        assertEquals("miley/ampr-310", entries[1].branch)
        assertTrue(entries[1].lockedInitializing)

        // A bare `locked` with no reason is still a lock — and, with no reason to
        // read, still the initializing case as far as recovery can tell.
        assertTrue(entries[2].locked)
        assertNull(entries[2].lockReason)
        assertTrue(entries[2].detached)
        assertTrue(entries[2].prunable)
        assertTrue(entries[3].bare)
        assertFalse(entries[3].locked)
    }

    @Test
    fun `a stale index lock is deleted and git works again`() = runBlocking<Unit> {
        val repo = repository()
        val worktree = repo.resolveSibling("wt-locked")
        git(repo, "worktree", "add", "-q", worktree.toString(), "-b", "feat")
        val adminDir = GitFixture.gitDirOf(worktree)
        Files.createFile(adminDir.resolve("index.lock"))
        Files.writeString(worktree.resolve("new.txt"), "work\n")
        assertEquals(128, gitExit(worktree, "add", "new.txt"), "the lock must really block a write")

        val cleared = repair(repo).clearStaleLocks(worktree).getOrThrow()

        assertEquals(1, cleared.size, "$cleared")
        assertTrue(cleared.single().endsWith("index.lock"), cleared.single())
        assertFalse(Files.exists(adminDir.resolve("index.lock")))
        assertEquals(0, gitExit(worktree, "add", "new.txt"), "and deleting it must really unblock one")
    }

    @Test
    fun `a stale lock in the shared directory is deleted too`() = runBlocking<Unit> {
        // packed-refs.lock is held in the common directory and blocks ref updates
        // for every worktree at once, so sweeping only the worktree's own admin
        // directory would leave the branch deletion in step 3 unable to run.
        val repo = repository()
        val worktree = repo.resolveSibling("wt-shared")
        git(repo, "worktree", "add", "-q", worktree.toString(), "-b", "feat")
        val common = GitFixture.commonDirOf(worktree)
        Files.createFile(common.resolve("packed-refs.lock"))
        Files.createDirectories(common.resolve("refs/heads"))
        Files.createFile(common.resolve("refs/heads/feat.lock"))

        val cleared = repair(repo).clearStaleLocks(worktree).getOrThrow()

        assertTrue(cleared.any { it.endsWith("packed-refs.lock") }, "$cleared")
        assertTrue(cleared.any { it.endsWith("feat.lock") }, "a per-ref lock blocks branch deletion")
    }

    @Test
    fun `a worktree locked initializing needs remove -f -f`() = runBlocking<Unit> {
        val repo = repository()
        val worktree = repo.resolveSibling("wt-initializing")
        git(repo, "worktree", "add", "-q", worktree.toString(), "-b", "half-made")
        git(repo, "worktree", "lock", "--reason", "initializing", worktree.toString())
        val subject = repair(repo)

        // The two commands the recon found do not work, pinned so a later
        // "simplification" to either one fails here instead of in production.
        assertFalse(subject.remove(worktree).succeeded, "an unforced remove refuses a locked worktree")
        assertEquals(
            128,
            gitExit(repo, "worktree", "remove", "--force", worktree.toString()),
            "and so does a single --force",
        )
        assertTrue(subject.prune().succeeded)
        assertNotNull(subject.entryFor(worktree.toString()).getOrThrow(), "and prune leaves it in place")

        assertTrue(subject.forceRemove(worktree).succeeded)

        assertNull(subject.entryFor(worktree.toString()).getOrThrow())
        assertFalse(Files.exists(worktree))
    }

    @Test
    fun `a clean worktree on a pushed branch is removable`() = runBlocking<Unit> {
        val repo = repository()
        val worktree = repo.resolveSibling("wt-clean")
        git(repo, "worktree", "add", "-q", worktree.toString(), "-b", "clean")
        val subject = repair(repo)

        assertEquals(WorktreeCondition.CLEAN, subject.condition(worktree))
        assertTrue(subject.remove(worktree).succeeded)
        assertFalse(Files.exists(worktree))
    }

    @Test
    fun `an untracked file is uncommitted work and holds the worktree`() = runBlocking<Unit> {
        // A redispatch starts from a fresh checkout, so an untracked file is exactly
        // as lost as a modified one.
        val repo = repository()
        val worktree = repo.resolveSibling("wt-dirty")
        git(repo, "worktree", "add", "-q", worktree.toString(), "-b", "dirty")
        Files.writeString(worktree.resolve("scratch.txt"), "half an idea\n")

        assertEquals(WorktreeCondition.UNCOMMITTED, repair(repo).condition(worktree))
    }

    @Test
    fun `a commit no remote ref contains is unpushed work and holds the worktree`() = runBlocking<Unit> {
        val repo = repository()
        val worktree = repo.resolveSibling("wt-unpushed")
        git(repo, "worktree", "add", "-q", worktree.toString(), "-b", "unpushed")
        GitFixture.commitUnpushed(worktree)

        val subject = repair(repo)

        assertEquals(WorktreeCondition.UNPUSHED, subject.condition(worktree))
        assertEquals(false, subject.branchIsPushed("unpushed"))
    }

    @Test
    fun `a branch whose tip a remote ref contains is deletable`() = runBlocking<Unit> {
        val repo = repository()
        git(repo, "branch", "orphan")
        val subject = repair(repo)

        assertTrue(subject.branchExists("orphan"))
        assertEquals(true, subject.branchIsPushed("orphan"))
        assertTrue(subject.deleteBranch("orphan").succeeded)
        assertFalse(subject.branchExists("orphan"))
    }

    @Test
    fun `prune tidies a worktree whose directory was deleted`() = runBlocking<Unit> {
        val repo = repository()
        val worktree = repo.resolveSibling("wt-vanished")
        git(repo, "worktree", "add", "-q", worktree.toString(), "-b", "vanished")
        worktree.toFile().deleteRecursively()
        val subject = repair(repo)

        assertTrue(subject.entryFor(worktree.toString()).getOrThrow()?.prunable == true)
        assertTrue(subject.prune().succeeded)

        assertNull(subject.entryFor(worktree.toString()).getOrThrow())
    }

    @Test
    fun `a path git does not know about is not found`() = runBlocking<Unit> {
        val repo = repository()

        assertNull(repair(repo).entryFor(repo.resolveSibling("never-existed").toString()).getOrThrow())
    }

    @Test
    fun `sweeping locks for a path that is gone is not an error`() = runBlocking<Unit> {
        val repo = repository()

        val cleared = repair(repo).clearStaleLocks(repo.resolveSibling("gone")).getOrThrow()

        assertTrue(cleared.isEmpty())
    }
}
