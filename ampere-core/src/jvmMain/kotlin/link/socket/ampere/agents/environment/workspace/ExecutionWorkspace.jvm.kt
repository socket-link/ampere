package link.socket.ampere.agents.environment.workspace

import java.io.File
import link.socket.ampere.agents.execution.tools.resolveFileSafely

/**
 * Resolves [relativePath] to a [File] that is guaranteed to lie inside this
 * workspace's [ExecutionWorkspace.baseDirectory] (AMPR-300).
 *
 * This is the one supported way for JVM callers outside `ampere-core` to turn
 * an agent-supplied path into a filesystem location: `../` segments, symlinks
 * that point out of the workspace, and any other path whose canonical form
 * leaves the root are rejected.
 *
 * @throws SecurityException when the resolved path is outside the workspace.
 *   Nothing is created or written before the check runs.
 */
fun ExecutionWorkspace.containedFile(relativePath: String): File =
    resolveFileSafely(baseDirectory, relativePath)
