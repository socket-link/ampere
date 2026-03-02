package link.socket.ampere.domain.ai.pricing

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull

class ProviderPricingCatalogClasspathTest {

    @Test
    fun `classpath fallback can read bundled pricing resource`() {
        val json = readBundledProviderPricingFromClasspath(
            resourcePath = "composeResources/link.socket.ampere.resources/files/provider_pricing.v1.json",
            fallbackPath = "files/provider_pricing.v1.json",
        )

        assertNotNull(json)
        assertContains(json, "\"providerId\": \"openai\"")
    }
}
