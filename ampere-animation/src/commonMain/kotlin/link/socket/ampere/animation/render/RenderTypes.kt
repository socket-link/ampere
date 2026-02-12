package link.socket.ampere.animation.render

/**
 * Layer priority for compositing.
 */
enum class RenderLayer(val priority: Int) {
    SUBSTRATE(0),
    FLOW(1),
    PARTICLES(2),
    AGENTS(3),
    LOGO(4),
    UI_OVERLAY(5)
}

/**
 * A rendered cell with character and optional color.
 *
 * The color field holds an ANSI escape code string for terminal rendering,
 * or can be mapped to platform-native colors by UI consumers.
 */
data class RenderCell(
    val char: Char,
    val color: String? = null,
    val layer: RenderLayer = RenderLayer.SUBSTRATE
)
