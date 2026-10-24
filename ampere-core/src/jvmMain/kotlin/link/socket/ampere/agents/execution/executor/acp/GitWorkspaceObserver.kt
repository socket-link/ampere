package link.socket.ampere.agents.execution.executor.acp

import java.io.File
import kotlinx.coroutines.CancellationException
import link.socket.ampere.agents.execution.process.ProcessGroups
import okio.ByteString.Companion.toByteString

/**
 * A [WorkspaceObserver] that asks git what is dirty (AMPR-493, AMPR-444 D4 layer 5).
 *
 * `git status --porcelain=v1 -z --untracked-files=all` lists every modified, added, deleted,
 * renamed and untracked path relative to the repository's baseline, each untracked file on
 * its own line. Each listed path is fingerprinted by size and a SHA-256 of its content, so an
 * edit that keeps a file's size is still seen; a path git lists that is no longer on disk is
 * [Fingerprint.DELETED]. Two snapshots around a turn then say exactly which paths the turn
 * touched, whether or not the agent said so.
 *
 * Known edge: a path that `.gitignore` covers is never listed, so a write into an ignored
 * directory (a build output) is not audited. A directory that is not a git checkout makes
 * [snapshot] fail, which the executor reads as "fall back to what the agent reported".
 *
 * Runs git through [ProcessGroups.run] like every other git invocation in the module.
 */
class GitWorkspaceObserver(
    private val root: File,
) : WorkspaceObserver {

    override suspend fun snapshot(): Result<Map<String, Fingerprint>> = try {
        val (output, exitCode, error) = ProcessGroups.run(
            ProcessBuilder("git", "status", "--porcelain=v1", "-z", "--untracked-files=all")
                .directory(root)
                .redirectErrorStream(false)
                .also { it.environment()["GIT_TERMINAL_PROMPT"] = "0" },
        ) { grouped ->
            val stdout = grouped.process.inputStream.readBytes()
            val stderr = grouped.process.errorStream.bufferedReader().readText()
            Triple(stdout, grouped.process.waitFor(), stderr)
        }
        if (exitCode != 0) {
            Result.failure(IllegalStateException("git status failed in ${root.path} (exit $exitCode): ${error.trim()}"))
        } else {
            Result.success(parsePorcelain(output.toString(Charsets.UTF_8)).associateWith { fingerprintOf(it) })
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun fingerprintOf(relativePath: String): Fingerprint {
        val file = File(root, relativePath)
        if (!file.isFile) return Fingerprint.DELETED
        val bytes = file.readBytes()
        return Fingerprint(
            size = bytes.size.toLong(),
            modifiedAtMillis = file.lastModified(),
            digest = bytes.toByteString().sha256().hex(),
        )
    }

    companion object {

        /**
         * The paths in `git status --porcelain=v1 -z` output: each entry is two status
         * characters, a space, the path, NUL; a rename or copy entry (`R` or `C` in either
         * column) is followed by the original path and another NUL, and both paths are
         * reported, because the original is a path that changed too.
         */
        fun parsePorcelain(output: String): Set<String> {
            val paths = linkedSetOf<String>()
            val entries = output.split('\u0000').filter { it.isNotEmpty() }
            var index = 0
            while (index < entries.size) {
                val entry = entries[index]
                if (entry.length < 3) {
                    index++
                    continue
                }
                val status = entry.substring(0, 2)
                paths += entry.substring(3)
                val renamedOrCopied = status.any { it == 'R' || it == 'C' }
                if (renamedOrCopied && index + 1 < entries.size) {
                    paths += entries[index + 1]
                    index++
                }
                index++
            }
            return paths
        }
    }
}
