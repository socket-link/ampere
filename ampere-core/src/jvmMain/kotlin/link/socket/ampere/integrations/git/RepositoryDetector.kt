package link.socket.ampere.integrations.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Detects the GitHub repository from git remote configuration.
 *
 * This utility extracts repository information from the git remote origin URL,
 * supporting both HTTPS and SSH URL formats.
 */
object RepositoryDetector {

    /**
     * Detects repository in format "owner/repo" from git remote origin.
     *
     * Executes `git remote get-url origin` and parses the result to extract
     * the repository owner and name.
     *
     * @return Repository in "owner/repo" format, or null if:
     *   - Not in a git repository
     *   - No origin remote configured
     *   - Origin is not a GitHub URL
     *   - Any error occurs during detection
     */
    suspend fun detectRepository(): String? = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("git", "remote", "get-url", "origin")
                .redirectErrorStream(false)
                .start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            if (exitCode != 0 || output.isBlank()) {
                return@withContext null
            }

            parseRepoFromUrl(output)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parses owner/repo from various GitHub URL formats.
     *
     * Supported formats:
     * - HTTPS: `https://github.com/owner/repo.git`
     * - HTTPS (no .git): `https://github.com/owner/repo`
     * - SSH: `git@github.com:owner/repo.git`
     * - SSH (no .git): `git@github.com:owner/repo`
     *
     * @param url The git remote URL to parse
     * @return Repository in "owner/repo" format, or null if not a GitHub URL
     */
    internal fun parseRepoFromUrl(url: String): String? {
        // Regex pattern matches both HTTPS and SSH formats
        // github.com[:/] matches both "github.com:" (SSH) and "github.com/" (HTTPS)
        // ([^/]+) captures owner name (everything up to next /)
        // ([^/\.]+) captures repo name (everything up to / or . or end)
        // (?:\\.git)? optionally matches .git suffix
        val pattern = Regex("github\\.com[:/]([^/]+)/([^/\\.]+)(?:\\.git)?")

        pattern.find(url)?.let { match ->
            val owner = match.groupValues[1]
            val repo = match.groupValues[2]
            return "$owner/$repo"
        }

        return null
    }
}
