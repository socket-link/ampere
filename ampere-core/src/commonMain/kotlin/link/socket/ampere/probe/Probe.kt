package link.socket.ampere.probe

/**
 * A predicate over a subject: evaluates one static artifact — a plan graph, a
 * manifest, a recalled fact — and returns a [Verdict]. This is *not* a grader
 * of a recorded trace; that job belongs to the eval harness
 * (`link.socket.ampere.eval.bench.EvalCase` and its `Meter`s).
 *
 * Contravariant in [S] so a `Probe<Observed>` can run over any subject that
 * implements `Observed`. [S] is deliberately unconstrained, for the same
 * reason [link.socket.ampere.plug.spi.PerceiveSource] leaves its `T`
 * unconstrained: binding the interface to an Ampere type would leave
 * downstream Probes that check foreign subjects (e.g. a consumer's own plan
 * type that Ampere must never import) with no base to extend, growing a
 * parallel path.
 */
interface Probe<in S> {
    val id: ProbeId

    suspend fun evaluate(subject: S): Verdict
}
