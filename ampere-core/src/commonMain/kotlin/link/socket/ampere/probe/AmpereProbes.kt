package link.socket.ampere.probe

/**
 * The Probes Ampere itself ships, in one place so a host that builds a
 * [ProbeRegistry] for an Oscilloscope listing registers them all with one
 * call. Consumers add their own Probes to the same registry afterwards.
 *
 * Returns the receiver so it composes:
 * `ProbeRegistry().registerAmpereProbes().also { it.register(myProbe) }`.
 */
fun ProbeRegistry.registerAmpereProbes(): ProbeRegistry = apply {
    register(SequenceProbe())
}
