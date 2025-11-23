package link.socket.ampere.domain.capability

import link.socket.ampere.domain.tool.FunctionProvider

interface Capability {

    val tag: String
        get() = "Capability"

    val impl: Pair<String, FunctionProvider>
}
