package link.socket.ampere.agents.execution.executor.acp

import okio.FileSystem
import okio.Path

/**
 * A [WorkspaceObserver] that walks every regular file under [root] on [fileSystem] and
 * fingerprints it by size and content digest. For tests: a `FakeFileSystem` in `commonTest`,
 * the system filesystem on a temp directory in `jvmTest`. The production observer asks git.
 */
class WalkingWorkspaceObserver(
    private val fileSystem: FileSystem,
    private val root: Path,
) : WorkspaceObserver {

    override suspend fun snapshot(): Result<Map<String, Fingerprint>> = runCatching {
        if (!fileSystem.exists(root)) return Result.success(emptyMap())
        fileSystem.listRecursively(root)
            .filter { fileSystem.metadata(it).isRegularFile }
            .associate { path ->
                val bytes = fileSystem.read(path) { readByteString() }
                path.relativeTo(root).toString() to Fingerprint(
                    size = bytes.size.toLong(),
                    modifiedAtMillis = fileSystem.metadata(path).lastModifiedAtMillis,
                    digest = bytes.sha256().hex(),
                )
            }
    }
}
