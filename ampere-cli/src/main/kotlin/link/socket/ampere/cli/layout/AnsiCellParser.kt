package link.socket.ampere.cli.layout

/**
 * A single parsed cell from an ANSI-styled string.
 * Holds the visible character and its associated ANSI color state.
 */
data class ParsedCell(
    val char: Char,
    val ansiPrefix: String?
)

/**
 * Parses ANSI-coded strings into sequences of ParsedCells.
 *
 * This enables compositing PaneRenderer output into a cell buffer
 * by extracting the (character, color) pairs from styled terminal strings.
 */
object AnsiCellParser {

    private val ANSI_REGEX = Regex("\u001B\\[[0-9;]*[a-zA-Z]")

    /**
     * Parse a single ANSI-styled line into a list of ParsedCells.
     * Each cell represents one visible character with its accumulated style.
     *
     * @param line The ANSI-styled string to parse
     * @return List of ParsedCells, one per visible character
     */
    fun parseLine(line: String): List<ParsedCell> {
        val cells = mutableListOf<ParsedCell>()
        var currentColor: String? = null
        var i = 0

        while (i < line.length) {
            if (line[i] == '\u001B' && i + 1 < line.length && line[i + 1] == '[') {
                // Parse ANSI escape sequence
                val seqStart = i
                i += 2 // Skip ESC[
                while (i < line.length && !line[i].isLetter()) {
                    i++
                }
                if (i < line.length) {
                    i++ // Skip the final letter
                }
                val sequence = line.substring(seqStart, i)

                // Check if this is a reset sequence
                if (sequence == "\u001B[0m" || sequence == "\u001B[m") {
                    currentColor = null
                } else {
                    // Accumulate color - for simplicity, replace with latest
                    currentColor = sequence
                }
            } else {
                cells.add(ParsedCell(line[i], currentColor))
                i++
            }
        }

        return cells
    }

    /**
     * Parse a line and return exactly [width] cells, padding or truncating.
     *
     * @param line The ANSI-styled string to parse
     * @param width The exact number of visible cells to return
     * @return List of exactly [width] ParsedCells
     */
    fun parseLineToWidth(line: String, width: Int): List<ParsedCell> {
        val parsed = parseLine(line)
        return when {
            parsed.size > width -> parsed.take(width)
            parsed.size < width -> parsed + List(width - parsed.size) { ParsedCell(' ', null) }
            else -> parsed
        }
    }

    /**
     * Strip all ANSI escape codes from a string, returning only visible text.
     */
    fun stripAnsi(text: String): String {
        return text.replace(ANSI_REGEX, "")
    }
}
