package link.socket.ampere.domain.limits

/**
 * The RequestLimit parameters are passed as pairs of [RequestsPerMinute, RequestsPerDay].
 */
data class RateLimitsFactory(
    val tierFreeRequestLimits: Pair<Int, Int?>? = null,
    val tier1RequestLimits: Pair<Int, Int?>? = null,
    val tier2RequestLimits: Pair<Int, Int?>? = null,
    val tier3RequestLimits: Pair<Int, Int?>? = null,
    val tier4RequestLimits: Pair<Int, Int?>? = null,
    val tier5RequestLimits: Pair<Int, Int?>? = null,
) {
    /**
     * This function creates [TokenRate] limits with combined input and output token limits.
     *
     * Tiers that are passed as null must match the null tiers that were given to [RateLimitsFactory].
     */
    fun createRateLimits(
        tierFreeTPM: TokenCount? = null,
        tier1TPM: TokenCount? = null,
        tier2TPM: TokenCount? = null,
        tier3TPM: TokenCount? = null,
        tier4TPM: TokenCount? = null,
        tier5TPM: TokenCount? = null,
    ): RateLimits = RateLimits(
        tierFree = tierFreeRequestLimits?.let { (rpm, rpd) ->
            requireNotNull(tierFreeTPM)
            Tier.FreeTier(
                _requestsPerMinute = rpm,
                _requestsPerDay = rpd,
                _tokenRate = TokenRate.Combined(
                    tokensPerMinute = tierFreeTPM,
                ),
            )
        },
        tier1 = tier1RequestLimits?.let { (rpm, rpd) ->
            requireNotNull(tier1TPM)
            Tier.Tier1(
                _requestsPerMinute = rpm,
                _requestsPerDay = rpd,
                _tokenRate = TokenRate.Combined(
                    tokensPerMinute = tier1TPM,
                ),
            )
        },
        tier2 = tier2RequestLimits?.let { (rpm, rpd) ->
            requireNotNull(tier2TPM)
            Tier.Tier2(
                _requestsPerMinute = rpm,
                _requestsPerDay = rpd,
                _tokenRate = TokenRate.Combined(
                    tokensPerMinute = tier2TPM,
                ),
            )
        },
        tier3 = tier1RequestLimits?.let { (rpm, rpd) ->
            requireNotNull(tier3TPM)
            Tier.Tier3(
                _requestsPerMinute = rpm,
                _requestsPerDay = rpd,
                _tokenRate = TokenRate.Combined(
                    tokensPerMinute = tier3TPM,
                ),
            )
        },
        tier4 = tier4RequestLimits?.let { (rpm, rpd) ->
            requireNotNull(tier4TPM)
            Tier.Tier4(
                _requestsPerMinute = rpm,
                _requestsPerDay = rpd,
                _tokenRate = TokenRate.Combined(
                    tokensPerMinute = tier4TPM,
                ),
            )
        },
        tier5 = tier5RequestLimits?.let { (rpm, rpd) ->
            requireNotNull(tier5TPM)
            Tier.Tier5(
                _requestsPerMinute = rpm,
                _requestsPerDay = rpd,
                _tokenRate = TokenRate.Combined(
                    tokensPerMinute = tier5TPM,
                ),
            )
        },
    )

    /**
     * This function creates [TokenRate] limits with separate input and output token limits.
     *
     * The TPM parameters are passed as pairs of [InputTokensPerMinute, OutputTokensPerMinute].
     *
     * Tiers that are passed as null must match the null tiers that were given to [RateLimitsFactory].
     */
    fun createSeparatedRateLimits(
        tierFreeTPMs: Pair<TokenCount, TokenCount>? = null,
        tier1TPMs: Pair<TokenCount, TokenCount>? = null,
        tier2TPMs: Pair<TokenCount, TokenCount>? = null,
        tier3TPMs: Pair<TokenCount, TokenCount>? = null,
        tier4TPMs: Pair<TokenCount, TokenCount>? = null,
        tier5TPMs: Pair<TokenCount, TokenCount>? = null,
    ): RateLimits = RateLimits(
        tierFree = tierFreeRequestLimits?.let { (rpm, rpd) ->
            requireNotNull(tierFreeTPMs)
            Tier.FreeTier(
                _requestsPerMinute = rpm,
                _requestsPerDay = rpd,
                _tokenRate = TokenRate.Separated(
                    inputTokensPerMinute = tierFreeTPMs.first,
                    outputTokensPerMinute = tierFreeTPMs.second,
                ),
            )
        },
        tier1 = tier1RequestLimits?.let { (rpm, rpd) ->
            requireNotNull(tier1TPMs)
            Tier.Tier1(
                _requestsPerMinute = rpm,
                _requestsPerDay = rpd,
                _tokenRate = TokenRate.Separated(
                    inputTokensPerMinute = tier1TPMs.first,
                    outputTokensPerMinute = tier1TPMs.second,
                ),
            )
        },
        tier2 = tier2RequestLimits?.let { (rpm, rpd) ->
            requireNotNull(tier2TPMs)
            Tier.Tier2(
                _requestsPerMinute = rpm,
                _requestsPerDay = rpd,
                _tokenRate = TokenRate.Separated(
                    inputTokensPerMinute = tier2TPMs.first,
                    outputTokensPerMinute = tier2TPMs.second,
                ),
            )
        },
        tier3 = tier3RequestLimits?.let { (rpm, rpd) ->
            requireNotNull(tier3TPMs)
            Tier.Tier3(
                _requestsPerMinute = rpm,
                _requestsPerDay = rpd,
                _tokenRate = TokenRate.Separated(
                    inputTokensPerMinute = tier3TPMs.first,
                    outputTokensPerMinute = tier3TPMs.second,
                ),
            )
        },
        tier4 = tier4RequestLimits?.let { (rpm, rpd) ->
            requireNotNull(tier4TPMs)
            Tier.Tier4(
                _requestsPerMinute = rpm,
                _requestsPerDay = rpd,
                _tokenRate = TokenRate.Separated(
                    inputTokensPerMinute = tier4TPMs.first,
                    outputTokensPerMinute = tier4TPMs.second,
                ),
            )
        },
        tier5 = tier5RequestLimits?.let { (rpm, rpd) ->
            requireNotNull(tier5TPMs)
            Tier.Tier5(
                _requestsPerMinute = rpm,
                _requestsPerDay = rpd,
                _tokenRate = TokenRate.Separated(
                    inputTokensPerMinute = tier5TPMs.first,
                    outputTokensPerMinute = tier5TPMs.second,
                ),
            )
        },
    )
}
