package link.socket.ampere.agents.domain.routing.capability

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeReasoning
import link.socket.ampere.domain.ai.model.AIModelFeatures.SupportedInputs
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_Google
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI
import link.socket.ampere.domain.ai.provider.ProviderId

/**
 * Lookup of [ProviderDescriptor]s the relay consults to choose a provider.
 *
 * The registry is separate from the hardcoded `AIProvider.ALL_PROVIDERS` list so
 * platform modules can [register] additional descriptors at runtime (e.g. a
 * device-gated local provider) without modifying the sealed provider hierarchy.
 */
interface ProviderDescriptorRegistry {

    /** The descriptor for [providerId], or `null` if none is registered. */
    suspend fun descriptorFor(providerId: ProviderId): ProviderDescriptor?

    /** Every registered descriptor. */
    suspend fun all(): List<ProviderDescriptor>

    /** Register (or replace) the descriptor for its `providerId`. */
    suspend fun register(descriptor: ProviderDescriptor)
}

/**
 * In-memory, mutex-guarded [ProviderDescriptorRegistry]. Safe for concurrent
 * access from multiple reasoning sessions.
 *
 * Seeded by default with descriptors for the three bundled cloud providers.
 */
class InMemoryProviderDescriptorRegistry(
    seed: List<ProviderDescriptor> = DEFAULT_CLOUD_DESCRIPTORS,
) : ProviderDescriptorRegistry {

    private val mutex = Mutex()
    private val descriptors: MutableMap<ProviderId, ProviderDescriptor> =
        seed.associateByTo(mutableMapOf()) { it.providerId }

    override suspend fun descriptorFor(providerId: ProviderId): ProviderDescriptor? =
        mutex.withLock { descriptors[providerId] }

    override suspend fun all(): List<ProviderDescriptor> =
        mutex.withLock { descriptors.values.toList() }

    override suspend fun register(descriptor: ProviderDescriptor) {
        mutex.withLock { descriptors[descriptor.providerId] = descriptor }
    }

    companion object {

        /**
         * Default capability set shared by the bundled cloud providers. Refined
         * per-provider as routing matures; minimal-but-honest for now.
         */
        private val CLOUD_CAPABILITIES = setOf(
            ProviderCapability.WORLD_KNOWLEDGE,
            ProviderCapability.TOOL_CALLING,
            ProviderCapability.LONG_CONTEXT,
        )

        private fun cloudDescriptor(providerId: ProviderId): ProviderDescriptor =
            ProviderDescriptor(
                providerId = providerId,
                capabilities = CLOUD_CAPABILITIES,
                reasoning = RelativeReasoning.HIGH,
                maxContextTokens = 200_000,
                supportedInputs = SupportedInputs.TEXT_AND_IMAGE,
                cost = CostPolicy.Metered,
                availabilityGated = false,
            )

        /** Descriptors for the three bundled cloud providers. */
        val DEFAULT_CLOUD_DESCRIPTORS: List<ProviderDescriptor> = listOf(
            cloudDescriptor(AIProvider_Anthropic.id),
            cloudDescriptor(AIProvider_Google.id),
            cloudDescriptor(AIProvider_OpenAI.id),
        )
    }
}
