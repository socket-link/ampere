package link.socket.ampere.knowledge

import kotlin.math.sqrt

/**
 * A dense vector representation of a document chunk produced by an embedding model.
 *
 * Provides similarity primitives ([cosineSimilarity], [dotProduct]) used by the
 * on-device knowledge retrieval pipeline, plus a stable byte serialization
 * ([toBlob] / [fromBlob]) for SQLDelight BLOB persistence.
 *
 * Values use structural equality based on the underlying [FloatArray] contents.
 */
class EmbeddingVector(
    val values: FloatArray,
) {
    init {
        require(values.isNotEmpty()) { "EmbeddingVector must have at least one dimension" }
    }

    /** Number of dimensions in the vector. */
    val dimension: Int get() = values.size

    /**
     * Sum of element-wise products with [other].
     *
     * @throws IllegalArgumentException if [dimension] does not match.
     */
    fun dotProduct(other: EmbeddingVector): Float {
        require(dimension == other.dimension) {
            "Dimension mismatch: $dimension vs ${other.dimension}"
        }
        var sum = 0f
        val a = values
        val b = other.values
        for (i in a.indices) {
            sum += a[i] * b[i]
        }
        return sum
    }

    /**
     * Cosine similarity in `[-1, 1]`. Returns `0` when either vector has zero magnitude.
     *
     * @throws IllegalArgumentException if [dimension] does not match.
     */
    fun cosineSimilarity(other: EmbeddingVector): Float {
        require(dimension == other.dimension) {
            "Dimension mismatch: $dimension vs ${other.dimension}"
        }
        var dot = 0f
        var magA = 0f
        var magB = 0f
        val a = values
        val b = other.values
        for (i in a.indices) {
            val x = a[i]
            val y = b[i]
            dot += x * y
            magA += x * x
            magB += y * y
        }
        val denominator = sqrt(magA) * sqrt(magB)
        return if (denominator == 0f) 0f else dot / denominator
    }

    /**
     * Serialize to a fixed-size big-endian byte array (`dimension * 4` bytes) for BLOB storage.
     *
     * Format: each [Float] is encoded via [Float.toRawBits] in big-endian order so the
     * payload is portable across all Kotlin Multiplatform targets.
     */
    fun toBlob(): ByteArray {
        val bytes = ByteArray(values.size * BYTES_PER_FLOAT)
        for (i in values.indices) {
            val bits = values[i].toRawBits()
            val offset = i * BYTES_PER_FLOAT
            bytes[offset] = (bits ushr 24).toByte()
            bytes[offset + 1] = (bits ushr 16).toByte()
            bytes[offset + 2] = (bits ushr 8).toByte()
            bytes[offset + 3] = bits.toByte()
        }
        return bytes
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddingVector) return false
        return values.contentEquals(other.values)
    }

    override fun hashCode(): Int = values.contentHashCode()

    override fun toString(): String = "EmbeddingVector(dimension=$dimension)"

    companion object {
        private const val BYTES_PER_FLOAT = 4

        /**
         * Decode a vector previously written by [toBlob].
         *
         * @throws IllegalArgumentException if [bytes] length is not a positive multiple of 4.
         */
        fun fromBlob(bytes: ByteArray): EmbeddingVector {
            require(bytes.isNotEmpty() && bytes.size % BYTES_PER_FLOAT == 0) {
                "Embedding blob must be a positive multiple of $BYTES_PER_FLOAT bytes, got ${bytes.size}"
            }
            val floats = FloatArray(bytes.size / BYTES_PER_FLOAT)
            for (i in floats.indices) {
                val offset = i * BYTES_PER_FLOAT
                val bits = (bytes[offset].toInt() and 0xff shl 24) or
                    (bytes[offset + 1].toInt() and 0xff shl 16) or
                    (bytes[offset + 2].toInt() and 0xff shl 8) or
                    (bytes[offset + 3].toInt() and 0xff)
                floats[i] = Float.fromBits(bits)
            }
            return EmbeddingVector(floats)
        }
    }
}
