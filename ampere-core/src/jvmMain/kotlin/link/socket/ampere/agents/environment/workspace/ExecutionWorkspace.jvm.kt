package link.socket.ampere.agents.environment.workspace

import java.io.File

/** The default workspace path in the user's home directory, returns "~/.ampere/Workspaces/Ampere" */
private fun defaultWorkspacePath(): String {
    val homeDir = System.getProperty("user.home") ?: System.getProperty("user.dir") ?: "."
    return File(homeDir, ".ampere/Workspaces/Ampere").absolutePath
}

actual fun defaultWorkspace(): ExecutionWorkspace =
    ExecutionWorkspace(
        baseDirectory = defaultWorkspacePath(),
    )
