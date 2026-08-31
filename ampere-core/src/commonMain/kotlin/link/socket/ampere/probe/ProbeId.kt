package link.socket.ampere.probe

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/** Identifier for a [Probe]. Stable across runs — it keys [ProbeReport]s and [ProbeRegistry] lookups. */
@JvmInline
@Serializable
value class ProbeId(val value: String)
