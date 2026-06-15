@file:Suppress("EnumEntryName")

package link.socket.ampere.domain.limits

import kotlinx.serialization.Serializable

@Serializable
enum class TokenCount(val label: String) {
    _4k("4k"),
    _5k("5k"),
    _4096("4,096"),
    _8k("8k"),
    _8192("8,192"),
    _10k("10k"),
    _15k("15k"),
    _16k("16k"),
    _20k("20k"),
    _25k("25k"),
    _30k("30k"),
    _32k("32k"),
    _35k("35k"),
    _40k("40k"),
    _50k("50k"),
    _64k("64k"),
    _80k("80k"),
    _90k("90k"),
    _100k("100k"),
    _128k("128k"),
    _160k("160k"),
    _200k("200k"),
    _250k("250k"),
    _400k("400k"),
    _450k("450k"),
    _800k("800k"),
    _1m("1 million"),
    _2m("2 million"),
    _3m("3 million"),
    _4m("4 million"),
    _5m("5 million"),
    _8m("8 million"),
    _10m("10 million"),
    _30m("30 million"),
    _40m("40 million"),
    _150m("150 million"),
    _180m("180 million"),
    _400m("400 million"),
    _500m("500 million"),
    _1b("1 billion"),
    _5b("50 billion"),
}

/**
 * The approximate token count this enum stands for, as a number. Lets callers
 * reason numerically about a [TokenCount] (e.g. projecting a model's context
 * window into a comparable `Int`) instead of parsing [label].
 */
val TokenCount.numericValue: Long
    get() = when (this) {
        TokenCount._4k -> 4_000L
        TokenCount._5k -> 5_000L
        TokenCount._4096 -> 4_096L
        TokenCount._8k -> 8_000L
        TokenCount._8192 -> 8_192L
        TokenCount._10k -> 10_000L
        TokenCount._15k -> 15_000L
        TokenCount._16k -> 16_000L
        TokenCount._20k -> 20_000L
        TokenCount._25k -> 25_000L
        TokenCount._30k -> 30_000L
        TokenCount._32k -> 32_000L
        TokenCount._35k -> 35_000L
        TokenCount._40k -> 40_000L
        TokenCount._50k -> 50_000L
        TokenCount._64k -> 64_000L
        TokenCount._80k -> 80_000L
        TokenCount._90k -> 90_000L
        TokenCount._100k -> 100_000L
        TokenCount._128k -> 128_000L
        TokenCount._160k -> 160_000L
        TokenCount._200k -> 200_000L
        TokenCount._250k -> 250_000L
        TokenCount._400k -> 400_000L
        TokenCount._450k -> 450_000L
        TokenCount._800k -> 800_000L
        TokenCount._1m -> 1_000_000L
        TokenCount._2m -> 2_000_000L
        TokenCount._3m -> 3_000_000L
        TokenCount._4m -> 4_000_000L
        TokenCount._5m -> 5_000_000L
        TokenCount._8m -> 8_000_000L
        TokenCount._10m -> 10_000_000L
        TokenCount._30m -> 30_000_000L
        TokenCount._40m -> 40_000_000L
        TokenCount._150m -> 150_000_000L
        TokenCount._180m -> 180_000_000L
        TokenCount._400m -> 400_000_000L
        TokenCount._500m -> 500_000_000L
        TokenCount._1b -> 1_000_000_000L
        TokenCount._5b -> 5_000_000_000L
    }
