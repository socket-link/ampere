package link.socket.ampere.agents.execution.verify

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.execution.dispatch.GitFixture

/** The manifest a dispatch is held to is the base revision's, out of the agent's reach. */
class BaseRevisionManifestSourceTest {

    private val temporary = mutableListOf<Path>()

    @AfterTest
    fun tearDown() {
        temporary.forEach { it.toFile().deleteRecursively() }
    }

    private val manifest = """
        version: 1
        env:
          requires: []
        gates:
          - id: real-gate
            run: ./gradlew :app:test
            timeout_minutes: 5
            retry_safe: true
            side_effects: build-dir
            parallel_safe: false
            baseline: ci
    """.trimIndent()

    @Test
    fun `an agent that rewrites its worktree's manifest is still verified against the base's`() = runBlocking<Unit> {
        val parent = Files.createTempDirectory("ampere-manifest").toRealPath().also { temporary.add(it) }
        val repo = GitFixture.repository(parent)
        Files.createDirectories(repo.resolve(".ampere"))
        Files.writeString(repo.resolve(VerifyManifest.PATH), manifest)
        GitFixture.git(repo, "add", VerifyManifest.PATH)
        GitFixture.git(repo, "commit", "-q", "-m", "manifest")
        GitFixture.git(repo, "update-ref", "refs/remotes/origin/main", "HEAD")

        val worktree = GitFixture.addWorktree(repo, parent.resolve("wt"), "agent-branch")
        Files.writeString(worktree.resolve(VerifyManifest.PATH), manifest.replace("real-gate", "agent-gate"))
        GitFixture.git(worktree, "commit", "-q", "-am", "an easier definition of done")

        val loaded = BaseRevisionManifestSource(repo, base = "origin/main").load(worktree).getOrThrow()

        assertEquals(listOf("real-gate"), loaded.gates.map { it.id })
    }

    @Test
    fun `a base with no manifest is a missing manifest, not an empty one`() = runBlocking<Unit> {
        val parent = Files.createTempDirectory("ampere-manifest").toRealPath().also { temporary.add(it) }
        val repo = GitFixture.repository(parent)

        val failure = BaseRevisionManifestSource(repo, base = "origin/main").load(repo).exceptionOrNull()

        assertIs<ManifestMissing>(failure)
    }
}
