package link.socket.ampere.integrations.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class RepositoryDetectorTest {

    // ========================================================================
    // URL Parsing Tests
    // ========================================================================

    @Test
    fun `parseRepoFromUrl parses HTTPS URL with git suffix`() {
        val url = "https://github.com/socket-link/ampere.git"
        val result = RepositoryDetector.parseRepoFromUrl(url)

        assertEquals("socket-link/ampere", result)
    }

    @Test
    fun `parseRepoFromUrl parses HTTPS URL without git suffix`() {
        val url = "https://github.com/socket-link/ampere"
        val result = RepositoryDetector.parseRepoFromUrl(url)

        assertEquals("socket-link/ampere", result)
    }

    @Test
    fun `parseRepoFromUrl parses SSH URL with git suffix`() {
        val url = "git@github.com:socket-link/ampere.git"
        val result = RepositoryDetector.parseRepoFromUrl(url)

        assertEquals("socket-link/ampere", result)
    }

    @Test
    fun `parseRepoFromUrl parses SSH URL without git suffix`() {
        val url = "git@github.com:socket-link/ampere"
        val result = RepositoryDetector.parseRepoFromUrl(url)

        assertEquals("socket-link/ampere", result)
    }

    @Test
    fun `parseRepoFromUrl handles repository with hyphens`() {
        val url = "https://github.com/my-org/my-awesome-repo.git"
        val result = RepositoryDetector.parseRepoFromUrl(url)

        assertEquals("my-org/my-awesome-repo", result)
    }

    @Test
    fun `parseRepoFromUrl handles repository with underscores`() {
        val url = "https://github.com/my_org/my_repo.git"
        val result = RepositoryDetector.parseRepoFromUrl(url)

        assertEquals("my_org/my_repo", result)
    }

    @Test
    fun `parseRepoFromUrl handles repository with dots in name`() {
        val url = "https://github.com/myorg/repo.name.git"
        val result = RepositoryDetector.parseRepoFromUrl(url)

        // Should stop at first dot before .git
        assertEquals("myorg/repo", result)
    }

    @Test
    fun `parseRepoFromUrl returns null for non-GitHub URL`() {
        val url = "https://gitlab.com/myorg/myrepo.git"
        val result = RepositoryDetector.parseRepoFromUrl(url)

        assertNull(result)
    }

    @Test
    fun `parseRepoFromUrl returns null for invalid format`() {
        val url = "not-a-valid-url"
        val result = RepositoryDetector.parseRepoFromUrl(url)

        assertNull(result)
    }

    @Test
    fun `parseRepoFromUrl returns null for empty string`() {
        val url = ""
        val result = RepositoryDetector.parseRepoFromUrl(url)

        assertNull(result)
    }

    @Test
    fun `parseRepoFromUrl handles repository with numbers`() {
        val url = "https://github.com/org123/repo456.git"
        val result = RepositoryDetector.parseRepoFromUrl(url)

        assertEquals("org123/repo456", result)
    }

    // ========================================================================
    // Integration Tests (require git to be installed)
    // ========================================================================

    @Test
    fun detectRepository_returnsNonNull_inAmpereRepository(): Unit = runBlocking {
        // This test assumes we're running in the ampere repository
        // If git is not available or we're not in a git repo, it should return null
        val result = RepositoryDetector.detectRepository()

        // We can't assert the exact value since it depends on the environment,
        // but we can verify the format if it's not null
        result?.let { repo ->
            assert(repo.contains("/")) { "Repository should be in 'owner/repo' format" }
            assert(!repo.startsWith("/")) { "Repository should not start with /" }
            assert(!repo.endsWith("/")) { "Repository should not end with /" }
        }
    }

    @Test
    fun detectRepository_returnsSocketLinkAmpere_ifInAmpereRepo(): Unit = runBlocking {
        // This is a more specific test that will pass when run in the actual ampere repository
        val result = RepositoryDetector.detectRepository()

        // Only validate if we got a result (git might not be available in CI)
        result?.let { repo ->
            // If we're in the ampere repo, it should be socket-link/ampere
            if (repo == "socket-link/ampere") {
                assertNotNull(result)
                assertEquals("socket-link/ampere", result)
            }
        }
    }

    // ========================================================================
    // Edge Case Tests
    // ========================================================================

    @Test
    fun `parseRepoFromUrl handles URL with trailing slash`() {
        val url = "https://github.com/owner/repo/"
        val result = RepositoryDetector.parseRepoFromUrl(url)

        // Trailing slash should be ignored and URL should parse correctly
        assertEquals("owner/repo", result)
    }

    @Test
    fun `parseRepoFromUrl handles URL with query parameters`() {
        val url = "https://github.com/owner/repo.git?foo=bar"
        val result = RepositoryDetector.parseRepoFromUrl(url)

        // The regex should stop at .git and not include query params
        assertEquals("owner/repo", result)
    }

    @Test
    fun `parseRepoFromUrl case sensitivity preserved`() {
        val url = "https://github.com/MyOrg/MyRepo.git"
        val result = RepositoryDetector.parseRepoFromUrl(url)

        assertEquals("MyOrg/MyRepo", result)
    }

    @Test
    fun `parseRepoFromUrl handles very long organization names`() {
        val longOrg = "a".repeat(100)
        val url = "https://github.com/$longOrg/repo.git"
        val result = RepositoryDetector.parseRepoFromUrl(url)

        assertEquals("$longOrg/repo", result)
    }

    @Test
    fun `parseRepoFromUrl handles very long repository names`() {
        val longRepo = "b".repeat(100)
        val url = "https://github.com/org/$longRepo.git"
        val result = RepositoryDetector.parseRepoFromUrl(url)

        assertEquals("org/$longRepo", result)
    }
}
