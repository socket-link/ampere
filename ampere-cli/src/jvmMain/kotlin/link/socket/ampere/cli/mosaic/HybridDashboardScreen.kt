package link.socket.ampere.cli.mosaic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.text.AnnotatedString
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import kotlinx.coroutines.delay
import link.socket.ampere.cli.hybrid.HybridCellBuffer
import link.socket.ampere.cli.hybrid.HybridConfig
import link.socket.ampere.cli.hybrid.HybridDashboardRenderer
import link.socket.ampere.cli.layout.PaneRenderer
import link.socket.ampere.cli.watch.presentation.WatchViewState

/**
 * Mosaic composable wrapper for the HybridDashboardRenderer.
 *
 * The hybrid renderer uses its own cell-buffer-based compositing engine
 * (substrate animation, particle accents, three-column layout). This composable
 * bridges that engine to Mosaic by converting the cell buffer output to
 * AnnotatedStrings displayed via Mosaic's Text composable.
 */
@Composable
fun HybridDashboardScreen(
    renderer: HybridDashboardRenderer,
    leftPane: PaneRenderer,
    middlePane: PaneRenderer,
    rightPane: PaneRenderer,
    statusBar: String,
    viewState: WatchViewState?,
    deltaSeconds: Float = 0.033f,
) {
    var frame by remember { mutableStateOf(emptyList<AnnotatedString>()) }

    LaunchedEffect(viewState, statusBar) {
        val buffer = renderer.renderToBuffer(
            leftPane = leftPane,
            middlePane = middlePane,
            rightPane = rightPane,
            statusBar = statusBar,
            viewState = viewState,
            deltaSeconds = deltaSeconds,
        )
        frame = buffer?.toAnnotatedStrings() ?: emptyList()
    }

    Column {
        frame.forEach { line ->
            Text(line)
        }
    }
}

/**
 * Convert the HybridCellBuffer to a list of Mosaic AnnotatedStrings (one per row).
 *
 * Maps ANSI 256-color escape codes to Mosaic Color values.
 */
fun HybridCellBuffer.toAnnotatedStrings(): List<AnnotatedString> {
    return (0 until height).map { y ->
        buildAnnotatedString {
            var currentColor: String? = null
            var runStart = length

            for (x in 0 until width) {
                val cell = getCell(x, y)
                val cellColor = cell.ansiColor

                if (cellColor != currentColor) {
                    // Close previous styled run
                    if (currentColor != null && runStart < length) {
                        // Style was already applied via withStyle below
                    }
                    currentColor = cellColor
                }

                val mosaicColor = cellColor?.let { parseAnsiToColor(it) }
                if (mosaicColor != null) {
                    withStyle(SpanStyle(color = mosaicColor)) {
                        append(cell.char)
                    }
                } else {
                    append(cell.char)
                }
            }
        }
    }
}

/**
 * Parse an ANSI 256-color escape code to a Mosaic Color.
 *
 * Supports formats:
 * - `\u001B[38;5;Nm` (256-color foreground)
 * - `\u001B[38;2;R;G;Bm` (truecolor foreground)
 * - Basic ANSI codes (30-37, 90-97)
 */
private fun parseAnsiToColor(ansi: String): Color? {
    if (!ansi.startsWith("\u001B[")) return null

    val params = ansi.removePrefix("\u001B[").removeSuffix("m")

    // 256-color: 38;5;N
    if (params.startsWith("38;5;")) {
        val colorIndex = params.removePrefix("38;5;").toIntOrNull() ?: return null
        return ansi256ToColor(colorIndex)
    }

    // Truecolor: 38;2;R;G;B
    if (params.startsWith("38;2;")) {
        val parts = params.removePrefix("38;2;").split(";")
        if (parts.size >= 3) {
            val r = parts[0].toIntOrNull() ?: return null
            val g = parts[1].toIntOrNull() ?: return null
            val b = parts[2].toIntOrNull() ?: return null
            return Color(r, g, b)
        }
        return null
    }

    // Basic ANSI colors
    return when (params) {
        "30" -> Color.Black
        "31" -> Color.Red
        "32" -> Color.Green
        "33" -> Color.Yellow
        "34" -> Color.Blue
        "35" -> Color.Magenta
        "36" -> Color.Cyan
        "37" -> Color.White
        "90" -> Color(128, 128, 128)  // Bright black / gray
        "91" -> Color(255, 0, 0)       // Bright red
        "92" -> Color(0, 255, 0)       // Bright green
        "93" -> Color(255, 255, 0)     // Bright yellow
        "94" -> Color(0, 0, 255)       // Bright blue
        "95" -> Color(255, 0, 255)     // Bright magenta
        "96" -> Color(0, 255, 255)     // Bright cyan
        "97" -> Color(255, 255, 255)   // Bright white
        else -> null
    }
}

/**
 * Convert an ANSI 256-color index to a Mosaic Color.
 */
private fun ansi256ToColor(index: Int): Color {
    return when {
        // Standard colors (0-7)
        index == 0 -> Color.Black
        index == 1 -> Color.Red
        index == 2 -> Color.Green
        index == 3 -> Color.Yellow
        index == 4 -> Color.Blue
        index == 5 -> Color.Magenta
        index == 6 -> Color.Cyan
        index == 7 -> Color.White

        // High-intensity colors (8-15)
        index == 8 -> Color(128, 128, 128)
        index == 9 -> Color(255, 0, 0)
        index == 10 -> Color(0, 255, 0)
        index == 11 -> Color(255, 255, 0)
        index == 12 -> Color(0, 0, 255)
        index == 13 -> Color(255, 0, 255)
        index == 14 -> Color(0, 255, 255)
        index == 15 -> Color(255, 255, 255)

        // 216-color cube (16-231)
        index in 16..231 -> {
            val n = index - 16
            val r = (n / 36) * 51
            val g = ((n % 36) / 6) * 51
            val b = (n % 6) * 51
            Color(r, g, b)
        }

        // Grayscale ramp (232-255)
        index in 232..255 -> {
            val gray = 8 + (index - 232) * 10
            Color(gray, gray, gray)
        }

        else -> Color.White
    }
}
