package link.socket.ampere.agents.domain.emission

import kotlin.time.Duration

/** Thrown by the DSL's `ask` builder when no reply arrives within the declared timeout. */
class EmissionTimeout(
    val emissionId: EmissionId,
    val timeout: Duration,
) : RuntimeException("Emission $emissionId timed out after $timeout")
