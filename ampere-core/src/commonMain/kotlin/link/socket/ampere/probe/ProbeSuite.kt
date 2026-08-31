package link.socket.ampere.probe

/**
 * Runs an ordered list of Probes over one subject.
 *
 * The caller supplies [evaluate]'s `subjectId` because [S] is unconstrained
 * and the SPI cannot ask the subject for its own identity.
 */
class ProbeSuite<in S>(
    private val probes: List<Probe<S>>,
) {

    suspend fun evaluate(subjectId: String, subject: S): List<ProbeReport> =
        probes.map { probe ->
            ProbeReport(
                probeId = probe.id,
                subjectId = subjectId,
                verdict = probe.evaluate(subject),
            )
        }
}
