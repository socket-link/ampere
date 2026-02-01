package link.socket.ampere.cli.animation.substrate

import kotlin.math.floor
import kotlin.random.Random

/**
 * Perlin noise generator for organic animation patterns.
 *
 * Creates smooth, natural-looking variation for ambient substrate animation.
 * Based on Ken Perlin's improved noise algorithm.
 */
class PerlinNoise(seed: Long = System.currentTimeMillis()) {
    private val permutation: IntArray

    init {
        val random = Random(seed)
        val p = IntArray(256) { it }
        // Shuffle permutation table
        for (i in 255 downTo 1) {
            val j = random.nextInt(i + 1)
            val temp = p[i]
            p[i] = p[j]
            p[j] = temp
        }
        // Duplicate for overflow handling
        permutation = IntArray(512) { p[it and 255] }
    }

    /**
     * Sample 2D Perlin noise at the given coordinates.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @return Noise value in range [-1, 1]
     */
    fun sample(x: Float, y: Float): Float {
        // Find unit grid cell containing point
        val xi = floor(x).toInt() and 255
        val yi = floor(y).toInt() and 255

        // Get relative position within cell
        val xf = x - floor(x)
        val yf = y - floor(y)

        // Compute fade curves
        val u = fade(xf)
        val v = fade(yf)

        // Hash coordinates of the 4 square corners
        val aa = permutation[permutation[xi] + yi]
        val ab = permutation[permutation[xi] + yi + 1]
        val ba = permutation[permutation[xi + 1] + yi]
        val bb = permutation[permutation[xi + 1] + yi + 1]

        // Blend results from 4 corners
        val x1 = lerp(
            grad(aa, xf, yf),
            grad(ba, xf - 1, yf),
            u
        )
        val x2 = lerp(
            grad(ab, xf, yf - 1),
            grad(bb, xf - 1, yf - 1),
            u
        )

        return lerp(x1, x2, v)
    }

    /**
     * Sample 3D Perlin noise (for time-varying 2D animation).
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate (often used for time)
     * @return Noise value in range [-1, 1]
     */
    fun sample(x: Float, y: Float, z: Float): Float {
        val xi = floor(x).toInt() and 255
        val yi = floor(y).toInt() and 255
        val zi = floor(z).toInt() and 255

        val xf = x - floor(x)
        val yf = y - floor(y)
        val zf = z - floor(z)

        val u = fade(xf)
        val v = fade(yf)
        val w = fade(zf)

        val aaa = permutation[permutation[permutation[xi] + yi] + zi]
        val aba = permutation[permutation[permutation[xi] + yi + 1] + zi]
        val aab = permutation[permutation[permutation[xi] + yi] + zi + 1]
        val abb = permutation[permutation[permutation[xi] + yi + 1] + zi + 1]
        val baa = permutation[permutation[permutation[xi + 1] + yi] + zi]
        val bba = permutation[permutation[permutation[xi + 1] + yi + 1] + zi]
        val bab = permutation[permutation[permutation[xi + 1] + yi] + zi + 1]
        val bbb = permutation[permutation[permutation[xi + 1] + yi + 1] + zi + 1]

        val x1 = lerp(grad3(aaa, xf, yf, zf), grad3(baa, xf - 1, yf, zf), u)
        val x2 = lerp(grad3(aba, xf, yf - 1, zf), grad3(bba, xf - 1, yf - 1, zf), u)
        val y1 = lerp(x1, x2, v)

        val x3 = lerp(grad3(aab, xf, yf, zf - 1), grad3(bab, xf - 1, yf, zf - 1), u)
        val x4 = lerp(grad3(abb, xf, yf - 1, zf - 1), grad3(bbb, xf - 1, yf - 1, zf - 1), u)
        val y2 = lerp(x3, x4, v)

        return lerp(y1, y2, w)
    }

    /**
     * Sample fractal Brownian motion (multiple octaves of noise).
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param octaves Number of noise layers
     * @param persistence Amplitude multiplier per octave
     * @return Summed noise value
     */
    fun fbm(x: Float, y: Float, octaves: Int = 4, persistence: Float = 0.5f): Float {
        var total = 0f
        var amplitude = 1f
        var frequency = 1f
        var maxValue = 0f

        for (i in 0 until octaves) {
            total += sample(x * frequency, y * frequency) * amplitude
            maxValue += amplitude
            amplitude *= persistence
            frequency *= 2f
        }

        return total / maxValue
    }

    private fun fade(t: Float): Float {
        // 6t^5 - 15t^4 + 10t^3
        return t * t * t * (t * (t * 6 - 15) + 10)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float {
        return a + t * (b - a)
    }

    private fun grad(hash: Int, x: Float, y: Float): Float {
        return when (hash and 3) {
            0 -> x + y
            1 -> -x + y
            2 -> x - y
            else -> -x - y
        }
    }

    private fun grad3(hash: Int, x: Float, y: Float, z: Float): Float {
        return when (hash and 15) {
            0 -> x + y
            1 -> -x + y
            2 -> x - y
            3 -> -x - y
            4 -> x + z
            5 -> -x + z
            6 -> x - z
            7 -> -x - z
            8 -> y + z
            9 -> -y + z
            10 -> y - z
            11 -> -y - z
            12 -> y + x
            13 -> -y + z
            14 -> y - x
            else -> -y - z
        }
    }
}
