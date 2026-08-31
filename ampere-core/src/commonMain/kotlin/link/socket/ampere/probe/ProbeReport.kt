package link.socket.ampere.probe

import kotlinx.serialization.Serializable

/**
 * One Probe's verdict on one identified subject.
 *
 * [subjectId] is caller-supplied (see [ProbeSuite]) because `S` is
 * unconstrained and the SPI cannot ask the subject for its own identity.
 */
@Serializable
data class ProbeReport(
    val probeId: ProbeId,
    val subjectId: String,
    val verdict: Verdict,
)
