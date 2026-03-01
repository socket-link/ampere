package link.socket.ampere.cli.render

import kotlin.math.log10

/**
 * Normalizes provider telemetry into 0.0-1.0 metadata channels for Phosphor.
 */
object CostNormalizer {
    fun normalizeCost(costUsd: Double): Float {
        if (costUsd <= 0.0) return 0f
        return normalizeLog1p(value = costUsd, pivot = 0.0001, ceiling = 0.2)
    }

    fun normalizeLatency(latencyMs: Long): Float {
        if (latencyMs <= 0L) return 0f
        return normalizeLog1p(value = latencyMs.toDouble(), pivot = 100.0, ceiling = 15_000.0)
    }

    fun normalizeTokens(totalTokens: Int): Float {
        if (totalTokens <= 0) return 0f
        return normalizeLog1p(value = totalTokens.toDouble(), pivot = 250.0, ceiling = 150_000.0)
    }

    private fun normalizeLog1p(
        value: Double,
        pivot: Double,
        ceiling: Double
    ): Float {
        val capped = value.coerceIn(0.0, ceiling)
        if (capped == 0.0) return 0f

        val normalized = log10(1.0 + capped / pivot) / log10(1.0 + ceiling / pivot)
        return normalized.toFloat().coerceIn(0f, 1f)
    }
}
