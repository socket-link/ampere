package link.socket.ampere.llm.decide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Each named route against the vendors' documentation as read on 2026-10-11:
 * OpenRouter's Jev guide, TypeSafe's API and models pages, and Cloudflare's
 * Workers AI model pages for Clef and Clef-flash.
 */
class DecisionRoutesTest {

    @Test
    fun `Jev via OpenRouter posts to the Decisions API with the gateway slug`() {
        val route = DecisionRoutes.jevViaOpenRouter()

        assertEquals("openrouter", route.via)
        assertEquals("https://openrouter.ai/api/alpha/decisions", route.endpoint)
        assertEquals("typesafe/jev-1.13", route.wireModelId)
        assertTrue(route.bodyExtras.isEmpty())
    }

    @Test
    fun `Jev via TypeSafe posts to System One with the pinned model id by default`() {
        val route = DecisionRoutes.jevViaTypeSafe()

        assertEquals("typesafe", route.via)
        assertEquals("https://api.typesafe.ai/v1/systemone", route.endpoint)
        assertEquals("jev-1.13.0", route.wireModelId)
        assertTrue(route.bodyExtras.isEmpty())
    }

    @Test
    fun `Jev via TypeSafe sends an alias as given`() {
        assertEquals("jev-latest", DecisionRoutes.jevViaTypeSafe(modelId = "jev-latest").wireModelId)
    }

    @Test
    fun `Clef on Workers AI runs under the account the caller names`() {
        val route = DecisionRoutes.clefOnWorkersAi(accountId = "acct-123")

        assertEquals("cloudflare", route.via)
        assertEquals(
            "https://api.cloudflare.com/client/v4/accounts/acct-123/ai/run/@cf/cloudflare/clef",
            route.endpoint,
        )
        assertEquals("clef", route.wireModelId)
        assertTrue(route.bodyExtras.isEmpty())
    }

    @Test
    fun `Clef-flash on Workers AI has its own path and model field`() {
        val route = DecisionRoutes.clefOnWorkersAi(accountId = "acct-123", flash = true)

        assertEquals(
            "https://api.cloudflare.com/client/v4/accounts/acct-123/ai/run/@cf/cloudflare/clef-flash",
            route.endpoint,
        )
        assertEquals("clef-flash", route.wireModelId)
    }

    @Test
    fun `Workers AI refuses a blank account id`() {
        assertFailsWith<IllegalArgumentException> { DecisionRoutes.clefOnWorkersAi(accountId = " ") }
    }

    @Test
    fun `the named models name their makers`() {
        assertEquals(DecisionModel("typesafe", "jev-1.13.0"), DecisionModels.Jev_1_13)
        assertEquals(DecisionModel("cloudflare", "clef"), DecisionModels.Clef)
        assertEquals(DecisionModel("cloudflare", "clef-flash"), DecisionModels.ClefFlash)
    }

    @Test
    fun `the direct route is the one the maker carries`() {
        assertEquals(DecisionModels.Jev_1_13.makerId, DecisionRoutes.jevViaTypeSafe().via)
        assertEquals(DecisionModels.Clef.makerId, DecisionRoutes.clefOnWorkersAi("acct-123").via)
        assertTrue(DecisionModels.Jev_1_13.makerId != DecisionRoutes.jevViaOpenRouter().via)
    }
}
