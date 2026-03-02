package link.socket.ampere.api.service.stub

import link.socket.ampere.api.internal.DefaultPricingService
import link.socket.ampere.api.service.PricingService

/**
 * Stub [PricingService] backed by bundled in-memory pricing data.
 */
class StubPricingService(
    private val delegate: PricingService = DefaultPricingService(),
) : PricingService by delegate
