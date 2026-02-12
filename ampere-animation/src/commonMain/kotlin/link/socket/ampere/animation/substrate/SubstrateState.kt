package link.socket.ampere.animation.substrate

/**
 * Core animation model for the substrate visualization.
 *
 * The substrate is a 2D density field that represents background processing
 * activity and serves as the connective medium between agents. Unlike random
 * ASCII shimmer, every glyph represents actual system state.
 *
 * @property width Width of the density field in cells
 * @property height Height of the density field in cells
 * @property densityField Density values from 0.0 (sparse) to 1.0 (dense)
 * @property flowField Directional vectors at each cell
 * @property activityHotspots High-activity focal points
 * @property time Animation time for ambient motion
 */
data class SubstrateState(
    val width: Int,
    val height: Int,
    val densityField: FloatArray,
    val flowField: Array<Vector2>,
    val activityHotspots: List<Point> = emptyList(),
    val time: Float = 0f
) {
    init {
        require(densityField.size == width * height) {
            "Density field size (${densityField.size}) must equal width * height ($width * $height = ${width * height})"
        }
        require(flowField.size == width * height) {
            "Flow field size (${flowField.size}) must equal width * height ($width * $height = ${width * height})"
        }
    }

    /**
     * Get density at a specific cell.
     */
    fun getDensity(x: Int, y: Int): Float {
        if (x < 0 || x >= width || y < 0 || y >= height) return 0f
        return densityField[y * width + x]
    }

    /**
     * Set density at a specific cell.
     */
    fun setDensity(x: Int, y: Int, value: Float) {
        if (x < 0 || x >= width || y < 0 || y >= height) return
        densityField[y * width + x] = value.coerceIn(0f, 1f)
    }

    /**
     * Get flow vector at a specific cell.
     */
    fun getFlow(x: Int, y: Int): Vector2 {
        if (x < 0 || x >= width || y < 0 || y >= height) return Vector2.ZERO
        return flowField[y * width + x]
    }

    /**
     * Set flow vector at a specific cell.
     */
    fun setFlow(x: Int, y: Int, value: Vector2) {
        if (x < 0 || x >= width || y < 0 || y >= height) return
        flowField[y * width + x] = value
    }

    /**
     * Create a copy with updated time.
     */
    fun withTime(newTime: Float): SubstrateState = copy(time = newTime)

    /**
     * Create a copy with updated hotspots.
     */
    fun withHotspots(newHotspots: List<Point>): SubstrateState = copy(activityHotspots = newHotspots)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SubstrateState) return false

        if (width != other.width) return false
        if (height != other.height) return false
        if (!densityField.contentEquals(other.densityField)) return false
        if (!flowField.contentEquals(other.flowField)) return false
        if (activityHotspots != other.activityHotspots) return false
        if (time != other.time) return false

        return true
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + densityField.contentHashCode()
        result = 31 * result + flowField.contentHashCode()
        result = 31 * result + activityHotspots.hashCode()
        result = 31 * result + time.hashCode()
        return result
    }

    companion object {
        /**
         * Create an empty substrate with uniform base density.
         */
        fun create(width: Int, height: Int, baseDensity: Float = 0.3f): SubstrateState {
            val size = width * height
            return SubstrateState(
                width = width,
                height = height,
                densityField = FloatArray(size) { baseDensity },
                flowField = Array(size) { Vector2.ZERO }
            )
        }
    }
}

/**
 * Glyph vocabulary for substrate density visualization.
 *
 * Maps density ranges to semantic glyphs that represent activity levels.
 */
object SubstrateGlyphs {
    /** Density thresholds for glyph selection */
    private val THRESHOLDS = floatArrayOf(0.2f, 0.4f, 0.6f, 0.8f)

    /** Glyphs ordered by density (low to high) */
    private val GLYPHS = charArrayOf(
        '\u00B7', // · Minimal activity (0.0-0.2)
        '\u2218', // ∘ Low activity (0.2-0.4)
        '\u25E6', // ◦ Medium activity (0.4-0.6)
        '\u223F', // ∿ High activity (0.6-0.8)
        '\u224B'  // ≋ Peak activity (0.8-1.0)
    )

    /** ASCII fallbacks for terminals without Unicode support */
    private val ASCII_GLYPHS = charArrayOf(
        '.', // Minimal
        'o', // Low
        'O', // Medium
        '*', // High
        '#'  // Peak
    )

    /**
     * Get the appropriate glyph for a density value.
     *
     * @param density Value from 0.0 to 1.0
     * @param useUnicode Whether to use Unicode glyphs
     * @return The glyph character representing this density
     */
    fun forDensity(density: Float, useUnicode: Boolean = true): Char {
        val glyphs = if (useUnicode) GLYPHS else ASCII_GLYPHS
        val index = when {
            density < THRESHOLDS[0] -> 0
            density < THRESHOLDS[1] -> 1
            density < THRESHOLDS[2] -> 2
            density < THRESHOLDS[3] -> 3
            else -> 4
        }
        return glyphs[index]
    }

    /**
     * Get all density thresholds.
     */
    fun getThresholds(): FloatArray = THRESHOLDS.copyOf()
}
