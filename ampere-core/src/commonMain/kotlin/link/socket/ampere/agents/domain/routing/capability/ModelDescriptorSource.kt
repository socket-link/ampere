package link.socket.ampere.agents.domain.routing.capability

/**
 * Where a [ModelDescriptorRegistry] gets its catalog.
 *
 * The framework ships this interface and one implementation of it — the bundled
 * cloud catalog ([DefaultModelDescriptorSource]) — and nothing more. A consumer
 * supplies its own source (HTTP, a file, remote config, a hand-built list) and
 * refreshes model→rung assignments while the process runs, without waiting on a
 * framework release. No backend, transport, or host application is assumed here.
 *
 * [load] returns a [Result] because reading a catalog crosses an I/O boundary.
 * A source that cannot produce a catalog must return [Result.failure] rather
 * than an empty list: a failed load leaves the registry's previous catalog
 * intact, whereas an empty *successful* load is taken at its word and empties
 * the registry.
 */
fun interface ModelDescriptorSource {

    /** The full catalog this source describes, or the reason it could not be read. */
    suspend fun load(): Result<List<ModelDescriptor>>
}

/**
 * The catalog Ampere ships with: one [ModelDescriptor] per model across the
 * bundled cloud providers, projected from each model's own metadata.
 *
 * This is what a no-argument [InMemoryModelDescriptorRegistry] holds, and the
 * source to compose with when a consumer wants its own catalog layered over the
 * bundled one rather than replacing it.
 */
object DefaultModelDescriptorSource : ModelDescriptorSource {

    override suspend fun load(): Result<List<ModelDescriptor>> =
        Result.success(InMemoryModelDescriptorRegistry.defaultModelDescriptors())
}
