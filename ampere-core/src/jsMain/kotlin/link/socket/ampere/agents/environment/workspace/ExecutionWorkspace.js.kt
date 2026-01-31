package link.socket.ampere.agents.environment.workspace

/**
 * JS implementation of default workspace.
 * In browser environments, we use a virtual path since filesystem access is limited.
 */
actual fun defaultWorkspace(): ExecutionWorkspace =
    ExecutionWorkspace(
        baseDirectory = "/ampere/workspace",
    )
