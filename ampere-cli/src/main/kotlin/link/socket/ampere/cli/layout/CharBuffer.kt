package link.socket.ampere.cli.layout

import com.github.ajalt.mordant.rendering.TextColors

/**
 * A 2D character buffer for composing ASCII art and terminal layouts.
 *
 * This buffer allows writing text at specific coordinates and
 * handles overlapping writes by keeping the last written character.
 * Supports ANSI color codes for styled output.
 *
 * @property width Width of the buffer in characters
 * @property height Height of the buffer in lines
 */
class CharBuffer(
    private val width: Int,
    private val height: Int,
) {
    private data class Cell(
        val char: Char,
        val color: TextColors? = null,
    )

    private val buffer: Array<Array<Cell?>> = Array(height) { arrayOfNulls(width) }

    /**
     * Write text at the specified position.
     *
     * @param x Horizontal position (column)
     * @param y Vertical position (row)
     * @param text Text to write
     * @param color Optional color for the text
     */
    fun write(x: Int, y: Int, text: String, color: TextColors? = null) {
        if (y < 0 || y >= height) return

        text.forEachIndexed { index, char ->
            val writeX = x + index
            if (writeX >= 0 && writeX < width) {
                buffer[y][writeX] = Cell(char, color)
            }
        }
    }

    /**
     * Write a pre-styled ANSI string at the specified position.
     * This handles strings that already contain ANSI escape codes.
     *
     * @param x Horizontal position (column)
     * @param y Vertical position (row)
     * @param styledText Text that may contain ANSI escape codes
     */
    fun writeStyled(x: Int, y: Int, styledText: String) {
        if (y < 0 || y >= height) return

        var writeX = x
        var i = 0
        while (i < styledText.length && writeX < width) {
            if (styledText[i] == '\u001B' && i + 1 < styledText.length && styledText[i + 1] == '[') {
                // Skip ANSI escape sequence - find the end (letter)
                var j = i + 2
                while (j < styledText.length && !styledText[j].isLetter()) {
                    j++
                }
                if (j < styledText.length) j++ // Include the letter
                i = j
            } else {
                if (writeX >= 0) {
                    buffer[y][writeX] = Cell(styledText[i], null)
                }
                writeX++
                i++
            }
        }
    }

    /**
     * Get a single line from the buffer.
     *
     * @param y Row index
     * @return The line as a string with ANSI color codes applied
     */
    fun getLine(y: Int): String {
        if (y < 0 || y >= height) return ""
        return buildLineString(buffer[y])
    }

    /**
     * Get all lines from the buffer.
     *
     * @return List of lines with ANSI color codes applied
     */
    fun getLines(): List<String> {
        return buffer.map { row -> buildLineString(row) }
    }

    private fun buildLineString(row: Array<Cell?>): String {
        return buildString {
            var currentColor: TextColors? = null
            var runStart = length

            row.forEach { cell ->
                if (cell != null) {
                    if (cell.color != currentColor) {
                        // If we accumulated colored text, apply the color now
                        if (currentColor != null && length > runStart) {
                            val textToColor = substring(runStart, length)
                            delete(runStart, length)
                            append(currentColor.invoke(textToColor))
                        }
                        currentColor = cell.color
                        runStart = length
                    }
                    append(cell.char)
                } else {
                    // Flush any pending colored text
                    if (currentColor != null && length > runStart) {
                        val textToColor = substring(runStart, length)
                        delete(runStart, length)
                        append(currentColor.invoke(textToColor))
                    }
                    currentColor = null
                    runStart = length
                    append(' ')
                }
            }
            // Flush any remaining colored text
            if (currentColor != null && length > runStart) {
                val textToColor = substring(runStart, length)
                delete(runStart, length)
                append(currentColor.invoke(textToColor))
            }
        }
    }

    /**
     * Clear the buffer.
     */
    fun clear() {
        for (y in 0 until height) {
            for (x in 0 until width) {
                buffer[y][x] = null
            }
        }
    }

    /**
     * Convert the buffer to a string representation.
     *
     * @return Multi-line string with the buffer contents
     */
    override fun toString(): String {
        return getLines().joinToString("\n").trimEnd()
    }
}
