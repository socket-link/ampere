package link.socket.ampere.domain.limits

import kotlinx.serialization.Serializable

@Serializable
sealed class Tier(
    val type: TierType,
    val tokenRate: TokenRate,
    val requestsPerMinute: Int,
    val requestsPerDay: Int?,
) {
    @Serializable
    data class FreeTier(
        private val _tokenRate: TokenRate,
        private val _requestsPerMinute: Int,
        private val _requestsPerDay: Int? = null,
    ) : Tier(
        type = TierType.FREE,
        tokenRate = _tokenRate,
        requestsPerMinute = _requestsPerMinute,
        requestsPerDay = _requestsPerMinute,
    )

    @Serializable
    data class Tier1(
        private val _tokenRate: TokenRate,
        private val _requestsPerMinute: Int,
        private val _requestsPerDay: Int? = null,
    ) : Tier(
        type = TierType.TIER_1,
        tokenRate = _tokenRate,
        requestsPerMinute = _requestsPerMinute,
        requestsPerDay = _requestsPerDay,
    )

    @Serializable
    data class Tier2(
        private val _tokenRate: TokenRate,
        private val _requestsPerMinute: Int,
        private val _requestsPerDay: Int? = null,
    ) : Tier(
        type = TierType.TIER_2,
        tokenRate = _tokenRate,
        requestsPerMinute = _requestsPerMinute,
        requestsPerDay = _requestsPerDay,
    )

    @Serializable
    data class Tier3(
        private val _tokenRate: TokenRate,
        private val _requestsPerMinute: Int,
        private val _requestsPerDay: Int? = null,
    ) : Tier(
        type = TierType.TIER_3,
        tokenRate = _tokenRate,
        requestsPerMinute = _requestsPerMinute,
        requestsPerDay = _requestsPerDay,
    )

    @Serializable
    data class Tier4(
        private val _tokenRate: TokenRate,
        private val _requestsPerMinute: Int,
        private val _requestsPerDay: Int? = null,
    ) : Tier(
        type = TierType.TIER_4,
        tokenRate = _tokenRate,
        requestsPerMinute = _requestsPerMinute,
        requestsPerDay = _requestsPerDay,
    )

    @Serializable
    data class Tier5(
        private val _tokenRate: TokenRate,
        private val _requestsPerMinute: Int,
        private val _requestsPerDay: Int? = null,
    ) : Tier(
        type = TierType.TIER_5,
        tokenRate = _tokenRate,
        requestsPerMinute = _requestsPerMinute,
        requestsPerDay = _requestsPerDay,
    )
}
