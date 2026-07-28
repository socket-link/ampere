package link.socket.ampere.agents.domain.routing.capability

import kotlin.test.Test
import kotlin.test.assertTrue
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI

/**
 * Guards [InMemoryModelDescriptorRegistry.MODEL_RUNGS] against drifting out of
 * sync with the bundled model catalogs (AMPR-233). The map is keyed by exact
 * model-name string with no cross-check against `ALL_MODELS`, so a new model,
 * or a renamed `*_NAME` constant, would otherwise land silently: a new model
 * projects as unrated, and a stale key just sits there unused.
 */
class ModelDescriptorRegistryDriftGuardTest {

    private val allModelNames: Set<String> =
        (AIModel_Claude.ALL_MODELS + AIModel_Gemini.ALL_MODELS + AIModel_OpenAI.ALL_MODELS)
            .map { it.name }
            .toSet()

    @Test
    fun everyBundledModelHasARungEntry() {
        val missing = allModelNames - InMemoryModelDescriptorRegistry.MODEL_RUNGS.keys
        assertTrue(missing.isEmpty(), "models with no MODEL_RUNGS entry (would silently project as unrated): $missing")
    }

    @Test
    fun everyRungEntryMatchesAKnownModel() {
        val stale = InMemoryModelDescriptorRegistry.MODEL_RUNGS.keys - allModelNames
        assertTrue(stale.isEmpty(), "MODEL_RUNGS key(s) matching no known model (stale rename?): $stale")
    }
}
