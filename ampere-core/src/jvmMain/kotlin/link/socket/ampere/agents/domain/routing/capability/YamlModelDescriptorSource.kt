package link.socket.ampere.agents.domain.routing.capability

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A [ModelDescriptorSource] backed by a YAML file of model→rung (and optional
 * cost/capability) overrides — the one concrete source that makes runtime
 * catalog refresh usable without writing Kotlin (AMPR-235).
 *
 * Overrides *layer over* [base] rather than replacing it, mirroring the
 * `.ampere/… → registry → default` precedence [link.socket.ampere.domain.arc.ArcConfigLoader]
 * uses for Arcs: a model named in [file] gets its listed fields replaced; every
 * other field on that descriptor, and every model [file] doesn't mention, keeps
 * exactly what [base] returned. This lets a consumer re-rung one model without
 * restating the catalog.
 *
 * Per the [ModelDescriptorSource] contract, a malformed file surfaces as
 * [Result.failure] and never touches the registry's live catalog; an absent or
 * empty file reproduces [base] exactly, which is current behavior unchanged.
 *
 * @param file The overrides YAML file, e.g. `.ampere/model-rungs.yaml`.
 * @param base The catalog overrides are layered onto. Defaults to the bundled
 *   cloud + on-device catalog.
 */
class YamlModelDescriptorSource(
    private val file: File,
    private val base: () -> List<ModelDescriptor> = InMemoryModelDescriptorRegistry::defaultModelDescriptors,
) : ModelDescriptorSource {

    override suspend fun load(): Result<List<ModelDescriptor>> = runCatching {
        val baseCatalog = base()

        if (!file.exists()) return@runCatching baseCatalog

        val content = file.readText()
        if (content.isBlank()) return@runCatching baseCatalog

        val overrides = yaml.decodeFromString(YamlModelOverridesFile.serializer(), content).modelOverrides
        if (overrides.isEmpty()) return@runCatching baseCatalog

        val baseModelNames = baseCatalog.mapTo(mutableSetOf()) { it.modelName }
        val unknown = overrides.keys - baseModelNames
        require(unknown.isEmpty()) {
            "model-overrides names model(s) not in the base catalog: $unknown"
        }

        baseCatalog.map { descriptor ->
            overrides[descriptor.modelName]?.applyTo(descriptor) ?: descriptor
        }
    }

    companion object {
        private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))
    }
}

@Serializable
internal data class YamlModelOverridesFile(
    @SerialName("model-overrides")
    val modelOverrides: Map<String, YamlModelOverride> = emptyMap(),
)

/**
 * One model's overrides. Every field is optional: a config author re-rungs a
 * model by naming only `rung`, and every other field on that model's base
 * [ModelDescriptor] survives untouched.
 */
@Serializable
internal data class YamlModelOverride(
    val rung: String? = null,
    @SerialName("cost-per-watt")
    val costPerWatt: Double? = null,
    val capabilities: List<ProviderCapability>? = null,
) {
    fun applyTo(descriptor: ModelDescriptor): ModelDescriptor = descriptor.copy(
        rung = rung?.let(::parseRung) ?: descriptor.rung,
        costPerWatt = costPerWatt ?: descriptor.costPerWatt,
        capabilities = capabilities?.toSet() ?: descriptor.capabilities,
    )

    private fun parseRung(name: String): CapabilityRung = when (name.trim().uppercase()) {
        "ZERO" -> CapabilityRung.ZERO
        "ONE" -> CapabilityRung.ONE
        "TWO" -> CapabilityRung.TWO
        "THREE" -> CapabilityRung.THREE
        "FOUR" -> CapabilityRung.FOUR
        else -> throw IllegalArgumentException(
            "Unknown rung '$name'. Supported: ZERO, ONE, TWO, THREE, FOUR",
        )
    }
}
