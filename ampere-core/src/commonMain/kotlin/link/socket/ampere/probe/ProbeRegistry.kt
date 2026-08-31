package link.socket.ampere.probe

/**
 * Open registry of Probes, modeled on `ToolRegistry.registerTool`
 * ([link.socket.ampere.agents.tools.registry.ToolRegistry]), not on the
 * closed `ArcRegistry` object. Manual construction; no DI framework.
 *
 * Exists for discovery and observability (Oscilloscope listing), not for
 * dispatch — suites are composed explicitly as [ProbeSuite]s. Registering a
 * Probe with an [ProbeId] that is already present replaces the earlier one,
 * matching `ToolRegistry` semantics.
 */
class ProbeRegistry {

    private val probes = linkedMapOf<ProbeId, Probe<*>>()

    fun register(probe: Probe<*>) {
        probes[probe.id] = probe
    }

    fun all(): List<Probe<*>> = probes.values.toList()

    fun get(id: ProbeId): Probe<*>? = probes[id]
}
