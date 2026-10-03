package link.socket.ampere.domain.ai.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import link.socket.ampere.domain.ai.model.AIModel_OnDevice

/**
 * [AIProvider_OnDevice] has no host it could legitimately reach (AMPR-371), so
 * its [AIProvider.client] must fail on read rather than default to OpenAI's.
 */
class AIProviderOnDeviceTest {

    @Test
    fun `reading the client throws instead of building one for a real host`() {
        assertFailsWith<OnDeviceProviderHasNoClientException> { AIProvider_OnDevice.client }
    }

    @Test
    fun `identity and catalog are unaffected by the missing client`() {
        assertEquals("apple-on-device", AIProvider_OnDevice.id)
        assertEquals("", AIProvider_OnDevice.apiToken)
        assertEquals(AIModel_OnDevice.ALL_MODELS, AIProvider_OnDevice.availableModels)
    }
}
