package link.socket.ampere.domain.ai.provider

import kotlin.test.Test
import kotlin.test.assertTrue
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OnDevice
import link.socket.ampere.domain.ai.model.AIModel_OpenAI

/**
 * Guards [OpenRouterModelSlugs] against drifting from the bundled catalogues
 * (AMPR-434), in the manner of `ModelDescriptorRegistryDriftGuardTest`. The table
 * is keyed by exact model name with nothing else tying it to `ALL_MODELS`, so a new
 * model would otherwise be silently unreachable through OpenRouter and a renamed
 * one would leave a key that matches nothing.
 */
class OpenRouterModelSlugsDriftGuardTest {

    private val allModelNames: Set<String> =
        listOf(
            AIModel_Claude.ALL_MODELS,
            AIModel_Gemini.ALL_MODELS,
            AIModel_OpenAI.ALL_MODELS,
            AIModel_OnDevice.ALL_MODELS,
        )
            .flatten()
            .map { it.name }
            .toSet()

    @Test
    fun everySlugKeyIsABundledModel() {
        val stale = OpenRouterModelSlugs.SLUGS.keys - allModelNames
        assertTrue(stale.isEmpty(), "slug key(s) matching no bundled model (stale rename?): $stale")
    }

    @Test
    fun everyBundledModelHasASlugOrIsDeclaredNotOffered() {
        val unaccounted = allModelNames - OpenRouterModelSlugs.SLUGS.keys - OpenRouterModelSlugs.NOT_OFFERED
        assertTrue(unaccounted.isEmpty(), "bundled model(s) with neither a slug nor a NOT_OFFERED entry: $unaccounted")
    }

    @Test
    fun noModelIsBothOfferedAndNotOffered() {
        val both = OpenRouterModelSlugs.SLUGS.keys intersect OpenRouterModelSlugs.NOT_OFFERED
        assertTrue(both.isEmpty(), "model(s) both given a slug and declared not offered: $both")
    }

    @Test
    fun noTwoModelsShareASlug() {
        val shared = OpenRouterModelSlugs.SLUGS.entries
            .groupBy({ it.value }, { it.key })
            .filterValues { names -> names.size > 1 }
        assertTrue(shared.isEmpty(), "slug(s) shared by more than one model: $shared")
    }

    @Test
    fun openRouterListsExactlyTheModelsWithASlug() {
        val listed = AIProvider_OpenRouter.availableModels.map { it.name }.toSet()
        assertTrue(listed == OpenRouterModelSlugs.SLUGS.keys, "listed: $listed")
    }

    @Test
    fun openRouterIsNotAMaker() {
        assertTrue(AIProvider.ALL_PROVIDERS.none { it.id == AIProvider_OpenRouter.id })
    }
}
