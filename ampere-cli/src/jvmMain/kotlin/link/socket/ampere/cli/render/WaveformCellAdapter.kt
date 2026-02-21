package link.socket.ampere.cli.render

import link.socket.ampere.animation.render.AsciiCell
import link.socket.ampere.cli.hybrid.HybridCellBuffer

/**
 * Converts the renderer-agnostic AsciiCell grid into the CLI's
 * HybridCellBuffer format, applying ANSI color codes.
 *
 * This is the final optic nerve fiber — translating the brain's
 * internal visual representation into the specific signal format
 * that the terminal "retina" (Mosaic) can display.
 */
class WaveformCellAdapter {

    /**
     * Write AsciiCell grid into an existing HybridCellBuffer region.
     *
     * @param cells The rasterized waveform output [row][col]
     * @param buffer The target cell buffer
     * @param offsetX Column offset within the buffer (for pane positioning)
     * @param offsetY Row offset within the buffer
     * @param layer The layer to write at (default 1 = PANE_CONTENT)
     */
    fun writeToBuffer(
        cells: Array<Array<AsciiCell>>,
        buffer: HybridCellBuffer,
        offsetX: Int,
        offsetY: Int,
        layer: Int = 1
    ) {
        for (row in cells.indices) {
            val y = offsetY + row
            if (y >= buffer.height) break
            val rowCells = cells[row]
            for (col in rowCells.indices) {
                val x = offsetX + col
                if (x >= buffer.width) break
                val cell = rowCells[col]
                if (cell.char == ' ' && cell.fgColor == 7) continue // Skip empty cells
                val ansiColor = toAnsiColor(cell)
                buffer.writeChar(x, y, cell.char, ansiColor, layer)
            }
        }
    }

    companion object {
        /**
         * Convert an AsciiCell's color attributes to an ANSI escape sequence.
         *
         * Produces ANSI 256-color foreground codes, with optional bold
         * and background color support.
         */
        fun toAnsiColor(cell: AsciiCell): String? {
            if (cell.fgColor == 7 && !cell.bold && cell.bgColor == null) return null

            return buildString {
                append("\u001B[")
                val parts = mutableListOf<String>()
                if (cell.bold) parts.add("1")
                parts.add("38;5;${cell.fgColor}")
                if (cell.bgColor != null) {
                    parts.add("48;5;${cell.bgColor}")
                }
                append(parts.joinToString(";"))
                append("m")
            }
        }
    }
}
