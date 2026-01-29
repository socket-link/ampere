package link.socket.ampere.agents.environment.workspace

import platform.Foundation.NSHomeDirectory

actual fun defaultWorkspace(): ExecutionWorkspace =
    ExecutionWorkspace(
        baseDirectory = "${NSHomeDirectory()}/.ampere/Workspaces/Ampere",
    )
