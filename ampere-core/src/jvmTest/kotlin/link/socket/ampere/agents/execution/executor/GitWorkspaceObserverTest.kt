package link.socket.ampere.agents.execution.executor

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.execution.executor.acp.Fingerprint
import link.socket.ampere.agents.execution.executor.acp.GitWorkspaceObserver
import link.socket.ampere.agents.execution.executor.acp.WorkspaceObserver

/**
 * [GitWorkspaceObserver] on a real checkout (AMPR-493): two snapshots around a set of edits
 * name exactly the paths that changed, and a directory git does not know is a failure.
 */
class GitWorkspaceObserverTest {

    private val posix = File.separatorChar == '/'
    private val root: File = Files.createTempDirectory("git-observer-test").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `the paths that changed between two snapshots are the ones the turn touched`() = runBlocking<Unit> {
        if (!posix) return@runBlocking
        gitInit()
        File(root, "tracked.txt").writeText("one\n")
        File(root, "gone.txt").writeText("bye\n")
        File(root, "same-size.txt").writeText("aaaa\n")
        git("add", "-A")
        git("commit", "-q", "-m", "init")
        File(root, "dirty-before.txt").writeText("untracked and untouched\n")
        val observer = GitWorkspaceObserver(root)

        val before = observer.snapshot().getOrThrow()
        File(root, "tracked.txt").writeText("one\ntwo\n")
        File(root, "gone.txt").delete()
        File(root, "same-size.txt").writeText("bbbb\n")
        File(root, "sub").mkdirs()
        File(root, "sub/new.txt").writeText("fresh\n")
        val after = observer.snapshot().getOrThrow()

        assertEquals(setOf("dirty-before.txt"), before.keys, "only the untracked file was dirty before")
        assertEquals(
            setOf("tracked.txt", "gone.txt", "same-size.txt", "sub/new.txt"),
            WorkspaceObserver.changedBetween(before, after),
        )
        assertEquals(Fingerprint.DELETED, after["gone.txt"])
        assertNotEquals(before["dirty-before.txt"]?.digest, null)
        assertEquals(before["dirty-before.txt"], after["dirty-before.txt"], "untouched dirty file is unchanged")
    }

    @Test
    fun `a directory that is not a checkout is a failure the executor can fall back from`() = runBlocking<Unit> {
        if (!posix) return@runBlocking

        val snapshot = GitWorkspaceObserver(root).snapshot()

        assertTrue(snapshot.isFailure)
        val message = snapshot.exceptionOrNull()!!.message!!
        assertTrue(message.contains("git status failed"), message)
    }

    @Test
    fun `porcelain output is parsed entry by entry and a rename names both paths`() {
        val output = "R  new.txt\u0000old.txt\u0000?? x.txt\u0000 M sub/y.txt\u0000D  z.txt\u0000"

        assertEquals(
            setOf("new.txt", "old.txt", "x.txt", "sub/y.txt", "z.txt"),
            GitWorkspaceObserver.parsePorcelain(output),
        )
        assertEquals(emptySet(), GitWorkspaceObserver.parsePorcelain(""))
    }

    private fun gitInit() {
        git("init", "-q")
        git("config", "user.email", "test@example.com")
        git("config", "user.name", "Test")
        git("config", "commit.gpgsign", "false")
    }

    private fun git(vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args).directory(root).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
    }
}
