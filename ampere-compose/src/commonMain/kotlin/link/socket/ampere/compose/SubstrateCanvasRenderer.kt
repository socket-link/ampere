package link.socket.ampere.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import link.socket.phosphor.field.SubstrateState

/**
 * Renders the substrate density field onto a Compose Canvas.
 *
 * Maps each cell's density to a colored rectangle, creating the
 * background energy grid that represents ambient processing activity.
 */
object SubstrateCanvasRenderer {

    /** Threshold below which cells are not rendered. */
    internal fun shouldRenderCell(density: Float): Boolean = density > 0.05f

    /** Map density to alpha for rendering. */
    internal fun cellAlpha(density: Float): Float = density * 0.6f

    /**
     * Render substrate density field.
     *
     * @param drawScope The Compose DrawScope to render into
     * @param substrate Current substrate state
     * @param cellWidth Width of each cell in pixels
     * @param cellHeight Height of each cell in pixels
     */
    fun render(
        drawScope: DrawScope,
        substrate: SubstrateState,
        cellWidth: Float,
        cellHeight: Float
    ) {
        with(drawScope) {
            for (y in 0 until substrate.height) {
                for (x in 0 until substrate.width) {
                    val density = substrate.getDensity(x, y)
                    if (shouldRenderCell(density)) {
                        drawRect(
                            color = CognitivePalette.forDensity(density),
                            topLeft = Offset(x * cellWidth, y * cellHeight),
                            size = Size(cellWidth, cellHeight),
                            alpha = cellAlpha(density)
                        )
                    }
                }
            }
        }
    }
}
