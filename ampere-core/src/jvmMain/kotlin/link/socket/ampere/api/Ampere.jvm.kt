package link.socket.ampere.api

import link.socket.ampere.api.internal.DefaultAmpereInstance

internal actual fun createInstance(config: AmpereConfig): AmpereInstance =
    DefaultAmpereInstance(config)
