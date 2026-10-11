package link.socket.ampere.agents.domain.emission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.Principal
import link.socket.ampere.agents.domain.emission.kinds.recipe.RecipeKind
import link.socket.ampere.agents.domain.emission.kinds.recipe.carbonara

class ConsoleSurfaceIOTest {

    @Test
    fun `a Domain Emission prints its fallback text`() {
        val payload = RecipeKind.encode(carbonara())
        val emission = Emission(
            id = "id",
            kind = RecipeKind.kind,
            payload = payload,
            provenance = EmissionProvenance(
                inputDigest = inputDigest(payload),
                parentEmissionId = null,
                principal = Principal.Ambient,
            ),
            producedAt = Instant.fromEpochMilliseconds(0),
        )

        assertEquals("Spaghetti carbonara (6 ingredients, 3 steps)", ConsoleSurfaceIO.promptText(emission))
    }
}
