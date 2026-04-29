package link.socket.ampere.knowledge

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EmbeddingVectorTest {

    @Test
    fun `dimension reflects underlying float array size`() {
        val vector = EmbeddingVector(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f))
        assertEquals(4, vector.dimension)
    }

    @Test
    fun `cosine similarity of identical unit vectors is 1`() {
        val a = EmbeddingVector(floatArrayOf(1f, 0f, 0f))
        val b = EmbeddingVector(floatArrayOf(1f, 0f, 0f))
        assertApproximatelyEqual(1f, a.cosineSimilarity(b))
    }

    @Test
    fun `cosine similarity of orthogonal vectors is 0`() {
        val a = EmbeddingVector(floatArrayOf(1f, 0f))
        val b = EmbeddingVector(floatArrayOf(0f, 1f))
        assertApproximatelyEqual(0f, a.cosineSimilarity(b))
    }

    @Test
    fun `cosine similarity of opposite vectors is -1`() {
        val a = EmbeddingVector(floatArrayOf(1f, 2f, 3f))
        val b = EmbeddingVector(floatArrayOf(-1f, -2f, -3f))
        assertApproximatelyEqual(-1f, a.cosineSimilarity(b))
    }

    @Test
    fun `cosine similarity matches reference value for known vectors`() {
        // a · b = 1*4 + 2*5 + 3*6 = 32
        // |a| = sqrt(14), |b| = sqrt(77)
        // cos = 32 / (sqrt(14) * sqrt(77))
        val a = EmbeddingVector(floatArrayOf(1f, 2f, 3f))
        val b = EmbeddingVector(floatArrayOf(4f, 5f, 6f))
        val expected = 32f / (sqrt(14f) * sqrt(77f))
        assertApproximatelyEqual(expected, a.cosineSimilarity(b))
    }

    @Test
    fun `cosine similarity returns 0 when either vector is all zeros`() {
        val zero = EmbeddingVector(floatArrayOf(0f, 0f, 0f))
        val nonZero = EmbeddingVector(floatArrayOf(1f, 2f, 3f))
        assertEquals(0f, zero.cosineSimilarity(nonZero))
        assertEquals(0f, nonZero.cosineSimilarity(zero))
    }

    @Test
    fun `cosine similarity throws when dimensions differ`() {
        val a = EmbeddingVector(floatArrayOf(1f, 2f))
        val b = EmbeddingVector(floatArrayOf(1f, 2f, 3f))
        assertFailsWith<IllegalArgumentException> { a.cosineSimilarity(b) }
    }

    @Test
    fun `dot product matches reference value`() {
        val a = EmbeddingVector(floatArrayOf(1f, 2f, 3f))
        val b = EmbeddingVector(floatArrayOf(4f, -5f, 6f))
        // 1*4 + 2*(-5) + 3*6 = 4 - 10 + 18 = 12
        assertApproximatelyEqual(12f, a.dotProduct(b))
    }

    @Test
    fun `dot product throws when dimensions differ`() {
        val a = EmbeddingVector(floatArrayOf(1f, 2f))
        val b = EmbeddingVector(floatArrayOf(1f, 2f, 3f))
        assertFailsWith<IllegalArgumentException> { a.dotProduct(b) }
    }

    @Test
    fun `empty vector is rejected`() {
        assertFailsWith<IllegalArgumentException> { EmbeddingVector(floatArrayOf()) }
    }

    @Test
    fun `toBlob round-trips via fromBlob`() {
        val original = EmbeddingVector(
            floatArrayOf(0f, -1.5f, 3.14159f, Float.MIN_VALUE, Float.MAX_VALUE, 42f),
        )
        val blob = original.toBlob()
        val restored = EmbeddingVector.fromBlob(blob)
        assertEquals(original, restored)
        assertEquals(original.dimension * 4, blob.size)
    }

    @Test
    fun `fromBlob rejects malformed input`() {
        assertFailsWith<IllegalArgumentException> { EmbeddingVector.fromBlob(ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { EmbeddingVector.fromBlob(ByteArray(3)) }
        assertFailsWith<IllegalArgumentException> { EmbeddingVector.fromBlob(ByteArray(7)) }
    }

    @Test
    fun `equality is structural and based on contents`() {
        val a = EmbeddingVector(floatArrayOf(1f, 2f, 3f))
        val b = EmbeddingVector(floatArrayOf(1f, 2f, 3f))
        val c = EmbeddingVector(floatArrayOf(1f, 2f, 4f))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun `cosine similarity on 1000-element vector completes quickly`() {
        val a = EmbeddingVector(FloatArray(1000) { i -> (i + 1).toFloat() / 1000f })
        val b = EmbeddingVector(FloatArray(1000) { i -> (1000 - i).toFloat() / 1000f })

        // Warm up to allow the JIT (or Kotlin/Native loop optimizer) to settle.
        repeat(WARMUP_ITERATIONS) { a.cosineSimilarity(b) }

        val mark = kotlin.time.TimeSource.Monotonic.markNow()
        repeat(MEASURED_ITERATIONS) { a.cosineSimilarity(b) }
        val elapsed = mark.elapsedNow()
        val perCallMicros = elapsed.inWholeMicroseconds.toDouble() / MEASURED_ITERATIONS

        assertTrue(
            perCallMicros < PER_CALL_BUDGET_MICROS,
            "Cosine similarity took ${perCallMicros}us per call (budget ${PER_CALL_BUDGET_MICROS}us)",
        )
    }

    private fun assertApproximatelyEqual(expected: Float, actual: Float, epsilon: Float = 1e-5f) {
        assertTrue(
            abs(expected - actual) < epsilon,
            "Expected $expected, got $actual (epsilon=$epsilon)",
        )
    }

    companion object {
        private const val WARMUP_ITERATIONS = 1_000
        private const val MEASURED_ITERATIONS = 1_000

        // Ticket budget is 1ms (1000us) per call. Headroom for slow CI hosts.
        private const val PER_CALL_BUDGET_MICROS = 1_000.0
    }
}
