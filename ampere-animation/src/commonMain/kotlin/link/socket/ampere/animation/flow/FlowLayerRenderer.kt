package link.socket.ampere.animation.flow

import link.socket.ampere.animation.substrate.Vector2
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Renders flow connections and tokens to a character grid.
 *
 * @property useUnicode Whether to use Unicode box-drawing characters
 * @property showDormantConnections Whether to show dormant connections
 */
class FlowLayerRenderer(
    private val useUnicode: Boolean = true,
    private val showDormantConnections: Boolean = false
) {
    /**
     * Rendered item for a flow connection.
     */
    data class FlowRenderItem(
        val connectionId: String,
        val pathChars: List<Pair<Vector2, Char>>,
        val tokenPosition: Vector2?,
        val tokenChar: Char?,
        val trailPositions: List<Pair<Vector2, Char>>,
        val color: String?
    )

    /**
     * Box-drawing characters for different path directions.
     */
    object BoxDrawing {
        // Basic line characters
        const val HORIZONTAL = '─'
        const val VERTICAL = '│'

        // Corner characters
        const val TOP_LEFT = '┌'
        const val TOP_RIGHT = '┐'
        const val BOTTOM_LEFT = '└'
        const val BOTTOM_RIGHT = '┘'

        // T-junctions
        const val T_DOWN = '┬'
        const val T_UP = '┴'
        const val T_RIGHT = '├'
        const val T_LEFT = '┤'

        // Cross
        const val CROSS = '┼'

        // Arrow heads
        const val ARROW_RIGHT = '▸'
        const val ARROW_LEFT = '◂'
        const val ARROW_UP = '▴'
        const val ARROW_DOWN = '▾'

        // Curved corners (alternative style)
        const val CURVE_TOP_LEFT = '╭'
        const val CURVE_TOP_RIGHT = '╮'
        const val CURVE_BOTTOM_LEFT = '╰'
        const val CURVE_BOTTOM_RIGHT = '╯'

        // Double lines for emphasis
        const val DOUBLE_HORIZONTAL = '═'
        const val DOUBLE_VERTICAL = '║'

        // Active/highlighted path
        const val ACTIVE_HORIZONTAL = '━'
        const val ACTIVE_VERTICAL = '┃'

        // Dormant/faded path (using lighter characters)
        const val DORMANT_HORIZONTAL = '╌'
        const val DORMANT_VERTICAL = '╎'

        // ASCII alternatives
        const val ASCII_HORIZONTAL = '-'
        const val ASCII_VERTICAL = '|'
        const val ASCII_CORNER = '+'
        const val ASCII_ARROW_RIGHT = '>'
        const val ASCII_ARROW_LEFT = '<'
        const val ASCII_ARROW_UP = '^'
        const val ASCII_ARROW_DOWN = 'v'

        /**
         * Get the appropriate character for a path segment based on direction.
         */
        fun forDirection(
            fromDir: Direction,
            toDir: Direction,
            useUnicode: Boolean = true,
            active: Boolean = false
        ): Char {
            if (!useUnicode) {
                return when {
                    fromDir == Direction.LEFT && toDir == Direction.RIGHT -> ASCII_HORIZONTAL
                    fromDir == Direction.RIGHT && toDir == Direction.LEFT -> ASCII_HORIZONTAL
                    fromDir == Direction.UP && toDir == Direction.DOWN -> ASCII_VERTICAL
                    fromDir == Direction.DOWN && toDir == Direction.UP -> ASCII_VERTICAL
                    else -> ASCII_CORNER
                }
            }

            val horizontal = if (active) ACTIVE_HORIZONTAL else HORIZONTAL
            val vertical = if (active) ACTIVE_VERTICAL else VERTICAL

            return when {
                // Straight lines
                (fromDir == Direction.LEFT && toDir == Direction.RIGHT) ||
                    (fromDir == Direction.RIGHT && toDir == Direction.LEFT) -> horizontal

                (fromDir == Direction.UP && toDir == Direction.DOWN) ||
                    (fromDir == Direction.DOWN && toDir == Direction.UP) -> vertical

                // Corners (using curved style for smooth appearance)
                (fromDir == Direction.DOWN && toDir == Direction.RIGHT) ||
                    (fromDir == Direction.LEFT && toDir == Direction.UP) -> CURVE_TOP_LEFT

                (fromDir == Direction.DOWN && toDir == Direction.LEFT) ||
                    (fromDir == Direction.RIGHT && toDir == Direction.UP) -> CURVE_TOP_RIGHT

                (fromDir == Direction.UP && toDir == Direction.RIGHT) ||
                    (fromDir == Direction.LEFT && toDir == Direction.DOWN) -> CURVE_BOTTOM_LEFT

                (fromDir == Direction.UP && toDir == Direction.LEFT) ||
                    (fromDir == Direction.RIGHT && toDir == Direction.DOWN) -> CURVE_BOTTOM_RIGHT

                else -> if (active) ACTIVE_HORIZONTAL else HORIZONTAL
            }
        }

        /**
         * Get arrow character for path end.
         */
        fun arrowForDirection(dir: Direction, useUnicode: Boolean = true): Char {
            return if (useUnicode) {
                when (dir) {
                    Direction.RIGHT -> ARROW_RIGHT
                    Direction.LEFT -> ARROW_LEFT
                    Direction.UP -> ARROW_UP
                    Direction.DOWN -> ARROW_DOWN
                    Direction.NONE -> ARROW_RIGHT
                }
            } else {
                when (dir) {
                    Direction.RIGHT -> ASCII_ARROW_RIGHT
                    Direction.LEFT -> ASCII_ARROW_LEFT
                    Direction.UP -> ASCII_ARROW_UP
                    Direction.DOWN -> ASCII_ARROW_DOWN
                    Direction.NONE -> ASCII_ARROW_RIGHT
                }
            }
        }
    }

    /**
     * Direction of path segment.
     */
    enum class Direction {
        UP, DOWN, LEFT, RIGHT, NONE;

        companion object {
            fun fromVector(from: Vector2, to: Vector2): Direction {
                val dx = to.x - from.x
                val dy = to.y - from.y

                return when {
                    abs(dx) > abs(dy) -> if (dx > 0) RIGHT else LEFT
                    abs(dy) > abs(dx) -> if (dy > 0) DOWN else UP
                    else -> NONE
                }
            }
        }
    }

    /**
     * Render a flow layer to a list of render items.
     */
    fun render(flowLayer: FlowLayer): List<FlowRenderItem> {
        return flowLayer.allConnections
            .filter { showDormantConnections || it.isActive }
            .map { connection -> renderConnection(connection, flowLayer) }
    }

    /**
     * Render a single connection.
     */
    private fun renderConnection(
        connection: FlowConnection,
        flowLayer: FlowLayer
    ): FlowRenderItem {
        val pathChars = mutableListOf<Pair<Vector2, Char>>()
        val isActive = connection.state == FlowState.TRANSMITTING

        // Render path with box-drawing characters
        if (connection.path.size >= 2) {
            for (i in connection.path.indices) {
                val current = connection.path[i]
                val prev = if (i > 0) connection.path[i - 1] else null
                val next = if (i < connection.path.size - 1) connection.path[i + 1] else null

                val fromDir = if (prev != null) Direction.fromVector(prev, current) else Direction.NONE
                val toDir = if (next != null) Direction.fromVector(current, next) else Direction.NONE

                val char = if (i == connection.path.size - 1 && toDir == Direction.NONE) {
                    // End of path - show arrow pointing to destination
                    BoxDrawing.arrowForDirection(fromDir, useUnicode)
                } else {
                    BoxDrawing.forDirection(fromDir, toDir, useUnicode, isActive)
                }

                pathChars.add(current to char)
            }
        }

        // Get token info
        val tokenPosition = connection.taskToken?.position
        val tokenChar = connection.taskToken?.glyph

        // Get trail particles for this connection
        val trailPositions = flowLayer.allTrailParticles.map { particle ->
            particle.position to particle.glyph
        }

        // Determine color based on state
        val color = when (connection.state) {
            FlowState.DORMANT -> "\u001B[38;5;240m"   // Gray
            FlowState.ACTIVATING -> "\u001B[38;5;226m" // Yellow
            FlowState.TRANSMITTING -> "\u001B[38;5;45m" // Cyan
            FlowState.RECEIVED -> "\u001B[38;5;46m"   // Green
        }

        return FlowRenderItem(
            connectionId = connection.id,
            pathChars = pathChars,
            tokenPosition = tokenPosition,
            tokenChar = tokenChar,
            trailPositions = trailPositions,
            color = color
        )
    }

    /**
     * Render the flow layer to a character grid.
     *
     * @param flowLayer The flow layer to render
     * @param width Grid width
     * @param height Grid height
     * @param background Background character
     * @return List of rows as strings
     */
    fun renderToGrid(
        flowLayer: FlowLayer,
        width: Int,
        height: Int,
        background: Char = ' '
    ): List<String> {
        val grid = Array(height) { CharArray(width) { background } }

        val items = render(flowLayer)

        // Render all paths
        items.forEach { item ->
            item.pathChars.forEach { (pos, char) ->
                val x = pos.x.roundToInt()
                val y = pos.y.roundToInt()
                if (x in 0 until width && y in 0 until height) {
                    grid[y][x] = char
                }
            }
        }

        // Render trail particles (they go under tokens)
        items.forEach { item ->
            item.trailPositions.forEach { (pos, char) ->
                val x = pos.x.roundToInt()
                val y = pos.y.roundToInt()
                if (x in 0 until width && y in 0 until height) {
                    // Only render if not already occupied by a token
                    if (grid[y][x] == background || grid[y][x] in listOf('─', '│', '╌', '╎')) {
                        grid[y][x] = char
                    }
                }
            }
        }

        // Render tokens (on top of everything)
        items.forEach { item ->
            if (item.tokenPosition != null && item.tokenChar != null) {
                val x = item.tokenPosition.x.roundToInt()
                val y = item.tokenPosition.y.roundToInt()
                if (x in 0 until width && y in 0 until height) {
                    grid[y][x] = item.tokenChar
                }
            }
        }

        return grid.map { it.concatToString() }
    }

    /**
     * Render with ANSI colors.
     */
    fun renderToGridColored(
        flowLayer: FlowLayer,
        width: Int,
        height: Int,
        background: Char = ' '
    ): List<String> {
        val grid = Array(height) { Array<Pair<Char, String?>>(width) { background to null } }

        val items = render(flowLayer)

        // Render all paths with colors
        items.forEach { item ->
            val pathColor = when {
                item.connectionId.contains("->") -> item.color
                else -> null
            }

            item.pathChars.forEach { (pos, char) ->
                val x = pos.x.roundToInt()
                val y = pos.y.roundToInt()
                if (x in 0 until width && y in 0 until height) {
                    grid[y][x] = char to pathColor
                }
            }
        }

        // Render trail particles
        items.forEach { item ->
            item.trailPositions.forEach { (pos, char) ->
                val x = pos.x.roundToInt()
                val y = pos.y.roundToInt()
                if (x in 0 until width && y in 0 until height) {
                    val existing = grid[y][x]
                    if (existing.first == background) {
                        grid[y][x] = char to TaskToken.Companion.Colors.TRAIL
                    }
                }
            }
        }

        // Render tokens with highlight color
        items.forEach { item ->
            if (item.tokenPosition != null && item.tokenChar != null) {
                val x = item.tokenPosition.x.roundToInt()
                val y = item.tokenPosition.y.roundToInt()
                if (x in 0 until width && y in 0 until height) {
                    grid[y][x] = item.tokenChar to TaskToken.Companion.Colors.ACTIVE
                }
            }
        }

        return grid.map { row ->
            buildString {
                row.forEach { (char, color) ->
                    if (color != null) {
                        append(color)
                        append(char)
                        append("\u001B[0m")
                    } else {
                        append(char)
                    }
                }
            }
        }
    }
}
