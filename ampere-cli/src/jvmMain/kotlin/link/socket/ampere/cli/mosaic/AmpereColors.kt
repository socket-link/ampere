package link.socket.ampere.cli.mosaic

import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.TextStyle

/**
 * Mosaic color mappings for the Ampere TUI.
 *
 * Maps the existing Mordant TextColors/TextStyles usage to Mosaic equivalents.
 * Also maps AmperePalette 256-color ANSI codes to Mosaic Color values.
 */
object AmpereColors {

    // Basic palette — maps to Mordant TextColors equivalents
    val cyan = Color.Cyan
    val green = Color.Green
    val red = Color.Red
    val yellow = Color.Yellow
    val blue = Color.Blue
    val magenta = Color.Magenta
    val white = Color.White
    val black = Color.Black

    // Bright variants (using RGB approximations of xterm-256 bright colors)
    val brightCyan = Color(0, 255, 255)
    val brightGreen = Color(0, 255, 0)
    val brightRed = Color(255, 0, 0)
    val brightYellow = Color(255, 255, 0)

    // AmperePalette 256-color equivalents (from ANSI 256-color codes)
    // Substrate colors
    val substrateDim = Color(78, 78, 78)       // ANSI 239 - dark gray
    val substrateMid = Color(95, 175, 175)     // ANSI 73 - teal
    val substrateBright = Color(135, 215, 255) // ANSI 117 - cyan

    // Agent colors
    val agentIdle = Color(128, 128, 128)       // ANSI 244 - gray
    val agentActive = Color(255, 215, 0)       // ANSI 220 - gold
    val agentProcessing = Color(255, 135, 0)   // ANSI 208 - orange
    val agentComplete = Color(0, 255, 0)       // ANSI 46 - green

    // Flow colors
    val flowDormant = Color(78, 78, 78)        // ANSI 239 - dark
    val flowActive = Color(175, 95, 255)       // ANSI 135 - purple
    val flowToken = Color(255, 255, 0)         // ANSI 226 - yellow

    // Accent colors
    val sparkAccent = Color(255, 95, 95)       // ANSI 203 - coral
    val successGreen = Color(95, 255, 95)      // ANSI 83 - green
    val logoBolt = Color(255, 255, 0)          // ANSI 226 - bright yellow
    val logoText = Color(0, 215, 255)          // ANSI 45 - cyan

    // Affinity colors (mapping SparkColors cognitive affinities)
    val analytical = Color.Cyan
    val exploratory = Color.Green
    val operational = Color.Yellow
    val integrative = Color.Magenta

    fun forAffinityName(affinityName: String): Color = when (affinityName.uppercase()) {
        "ANALYTICAL" -> analytical
        "EXPLORATORY" -> exploratory
        "OPERATIONAL" -> operational
        "INTEGRATIVE" -> integrative
        else -> white
    }

    fun forSubstrateDensity(density: Float): Color = when {
        density < 0.3f -> substrateDim
        density < 0.6f -> substrateMid
        else -> substrateBright
    }
}

/** SpanStyle helpers for common styling patterns. */
object AmpereStyles {
    fun bold(color: Color = Color.Unspecified) = SpanStyle(
        color = color,
        textStyle = TextStyle.Bold,
    )

    fun dim(color: Color = Color.Unspecified) = SpanStyle(
        color = color,
        textStyle = TextStyle.Dim,
    )

    fun colored(color: Color) = SpanStyle(color = color)

    fun boldColored(color: Color) = SpanStyle(
        color = color,
        textStyle = TextStyle.Bold,
    )
}
