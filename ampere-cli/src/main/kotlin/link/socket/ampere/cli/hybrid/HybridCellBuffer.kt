package link.socket.ampere.cli.hybrid

import link.socket.ampere.cli.layout.ParsedCell

/**
 * A single cell in the hybrid buffer.
 */
data class HybridCell(
    val char: Char = ' ',
    val ansiColor: String? = null,
    val layer: Int = 0
)

private const val ANSI_RESET = "\u001B[0m"

/**
 * Double-buffered cell buffer that supports layer compositing and differential rendering.
 *
 * Layers (higher values overwrite lower):
 *   0 - SUBSTRATE: Dim background density glyphs
 *   1 - PANE_CONTENT: PaneRenderer output (overrides substrate completely)
 *   2 - ACCENT: Sparse particles/flow in non-pane areas
 *   3 - STATUS: Status bar content
 *
 * @property width Buffer width in characters
 * @property height Buffer height in rows
 */
class HybridCellBuffer(
    val width: Int,
    val height: Int
) {
    private var currentBuffer: Array<Array<HybridCell>> = createBuffer()
    private var previousBuffer: Array<Array<HybridCell>>? = null

    private var firstFrame = true

    private fun createBuffer(): Array<Array<HybridCell>> {
        return Array(height) { Array(width) { HybridCell() } }
    }

    /**
     * Clear the current buffer to empty spaces at layer 0.
     */
    fun clear() {
        for (y in 0 until height) {
            for (x in 0 until width) {
                currentBuffer[y][x] = HybridCell()
            }
        }
    }

    /**
     * Write a single character at a position with optional color.
     * Only overwrites if the new layer is >= the existing layer.
     */
    fun writeChar(x: Int, y: Int, char: Char, ansiColor: String? = null, layer: Int = 0) {
        if (x < 0 || x >= width || y < 0 || y >= height) return
        val existing = currentBuffer[y][x]
        if (layer >= existing.layer) {
            currentBuffer[y][x] = HybridCell(char, ansiColor, layer)
        }
    }

    /**
     * Write a plain string at a position. Each character gets the same color and layer.
     */
    fun writeString(x: Int, y: Int, text: String, ansiColor: String? = null, layer: Int = 0) {
        for (i in text.indices) {
            writeChar(x + i, y, text[i], ansiColor, layer)
        }
    }

    /**
     * Write parsed pane content into a rectangular region of the buffer.
     *
     * @param startX Left column of the region
     * @param startY Top row of the region
     * @param rows Parsed cell rows from AnsiCellParser
     * @param layer Layer priority for this content
     */
    fun writePaneRegion(
        startX: Int,
        startY: Int,
        rows: List<List<ParsedCell>>,
        layer: Int = 1
    ) {
        for ((rowIdx, cells) in rows.withIndex()) {
            val y = startY + rowIdx
            if (y >= height) break
            for ((colIdx, cell) in cells.withIndex()) {
                val x = startX + colIdx
                if (x >= width) break
                writeChar(x, y, cell.char, cell.ansiPrefix, layer)
            }
        }
    }

    /**
     * Generate full ANSI output using row-based cursor positioning.
     */
    fun renderFull(): String {
        return buildString {
            for (y in 0 until height) {
                // Position cursor at start of row (1-based)
                append("\u001B[${y + 1};1H")
                var lastColor: String? = null

                for (x in 0 until width) {
                    val cell = currentBuffer[y][x]
                    if (cell.ansiColor != lastColor) {
                        if (lastColor != null) {
                            append(ANSI_RESET)
                        }
                        if (cell.ansiColor != null) {
                            append(cell.ansiColor)
                        }
                        lastColor = cell.ansiColor
                    }
                    append(cell.char)
                }
                if (lastColor != null) {
                    append(ANSI_RESET)
                }
                append("\u001B[K") // Clear rest of line
            }
        }
    }

    /**
     * Generate differential ANSI output.
     * Compares current buffer against previous buffer,
     * emits cursor-positioned writes only for changed cells.
     *
     * Falls back to renderFull on first frame or if no previous buffer exists.
     */
    fun renderDiff(): String {
        val prev = previousBuffer ?: return renderFull()

        return buildString {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val newCell = currentBuffer[y][x]
                    val oldCell = prev[y][x]

                    if (newCell != oldCell) {
                        append("\u001B[${y + 1};${x + 1}H")
                        if (newCell.ansiColor != null) {
                            append(newCell.ansiColor)
                            append(newCell.char)
                            append(ANSI_RESET)
                        } else {
                            append(newCell.char)
                        }
                    }
                }
            }
        }
    }

    /**
     * Swap buffers: save current as previous for the next diff comparison.
     */
    fun swapBuffers() {
        // Deep copy current to previous
        previousBuffer = Array(height) { y ->
            Array(width) { x -> currentBuffer[y][x] }
        }
    }

    /**
     * Get the cell at a specific position (for testing).
     */
    fun getCell(x: Int, y: Int): HybridCell {
        if (x < 0 || x >= width || y < 0 || y >= height) return HybridCell()
        return currentBuffer[y][x]
    }
}
