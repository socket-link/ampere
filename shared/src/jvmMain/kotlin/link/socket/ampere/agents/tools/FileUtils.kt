package link.socket.ampere.agents.tools

import java.io.File

internal fun resolveFileSafely(
    rootDirectory: String,
    path: String,
): File {
    val base = File(rootDirectory).canonicalFile
    val target = File(base, path).canonicalFile

    // Ensure that the target path stays within the base directory of the workspace
    if (!target.path.startsWith(base.path + File.separator) && target != base) {
        throw SecurityException("Access outside root directory is not allowed: $path")
    }

    return target
}
