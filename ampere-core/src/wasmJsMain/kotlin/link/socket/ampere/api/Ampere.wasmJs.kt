package link.socket.ampere.api

internal actual fun createInstance(config: AmpereConfig): AmpereInstance =
    throw UnsupportedOperationException("AMPERE SDK is not yet supported on WASM. JVM-only for now.")
