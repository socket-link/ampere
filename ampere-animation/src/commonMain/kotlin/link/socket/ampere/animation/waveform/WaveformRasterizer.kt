package link.socket.ampere.animation.waveform

import link.socket.ampere.animation.math.Vector3
import link.socket.ampere.animation.projection.Camera
import link.socket.ampere.animation.projection.ScreenProjector
import link.socket.ampere.animation.render.AsciiCell
import link.socket.ampere.animation.render.AsciiLuminancePalette
import link.socket.ampere.animation.render.CognitiveColorRamp

/**
 * The optic nerve -- converts the 3D waveform into a 2D grid of AsciiCells.
 *
 * For each cell in the output grid, this determines which point on the waveform
 * surface is visible (front-to-back depth sorting), computes its lighting,
 * selects the appropriate character and color, and writes it to the cell buffer.
 *
 * The rasterization strategy is "scanline projection": iterate over the heightmap
 * grid back-to-front (painter's algorithm), project each surface point to screen
 * coordinates, and write to the buffer only if no closer point has already been written.
 *
 * @param screenWidth Width of the output character grid
 * @param screenHeight Height of the output character grid
 * @param projector Screen projector for 3D-to-2D mapping
 * @param lighting Surface lighting calculator
 */
class WaveformRasterizer(
    val screenWidth: Int,
    val screenHeight: Int,
    val projector: ScreenProjector,
    val lighting: SurfaceLighting
) {
    /** Depth buffer for occlusion -- one float per screen cell */
    private val depthBuffer = FloatArray(screenWidth * screenHeight) { Float.MAX_VALUE }

    /** Output buffer (flat, row-major) */
    private val cellBuffer = Array(screenWidth * screenHeight) { AsciiCell.EMPTY }

    /**
     * Rasterize the waveform to a 2D cell grid.
     *
     * @param waveform The heightmap to render
     * @param camera Current camera position/orientation
     * @param palette The character palette to use (phase-specific)
     * @param colorRamp The color ramp to use (phase-specific)
     * @return 2D array of AsciiCells [row][col], screenHeight rows x screenWidth cols
     */
    fun rasterize(
        waveform: CognitiveWaveform,
        camera: Camera,
        palette: AsciiLuminancePalette,
        colorRamp: CognitiveColorRamp
    ): Array<Array<AsciiCell>> {
        clear()

        // Build list of grid points with their distance from camera for back-to-front sort
        val gridPoints = buildSortedGridPoints(waveform, camera)

        // View direction for specular lighting
        val cameraPos = camera.position

        // Rasterize back-to-front
        for ((gx, gz, _) in gridPoints) {
            val worldPos = waveform.worldPosition(gx, gz)

            val screenPoint = projector.project(worldPos, camera)
            if (!screenPoint.visible) continue

            val sx = screenPoint.x
            val sy = screenPoint.y
            val bufferIndex = sy * screenWidth + sx

            // Depth test: write only if closer than what's already there
            if (screenPoint.depth < depthBuffer[bufferIndex]) {
                val normal = waveform.normalAt(gx, gz)
                val viewDir = (cameraPos - worldPos).normalized()
                val luminance = lighting.computeLuminance(normal, viewDir)

                val cell = AsciiCell.fromSurface(
                    luminance = luminance,
                    normalX = normal.x,
                    normalY = normal.z, // Z maps to screen-space vertical component
                    palette = palette,
                    colorRamp = colorRamp
                )

                cellBuffer[bufferIndex] = cell
                depthBuffer[bufferIndex] = screenPoint.depth
            }
        }

        // Convert flat buffer to 2D array
        return Array(screenHeight) { y ->
            Array(screenWidth) { x ->
                cellBuffer[y * screenWidth + x]
            }
        }
    }

    /**
     * Rasterize with phase blending -- uses a PhaseBlender to select per-point
     * palette and color ramp based on nearby agents' cognitive phases.
     *
     * @param waveform The heightmap to render
     * @param camera Current camera position/orientation
     * @param blender PhaseBlender for per-point palette selection
     * @param agents Agent layer for phase lookup
     * @param fallbackPalette Palette for regions with no nearby agents
     * @param fallbackRamp Color ramp for regions with no nearby agents
     * @return 2D array of AsciiCells [row][col]
     */
    fun rasterizeBlended(
        waveform: CognitiveWaveform,
        camera: Camera,
        blender: PhaseBlender,
        agents: link.socket.ampere.animation.agent.AgentLayer,
        fallbackPalette: AsciiLuminancePalette = AsciiLuminancePalette.STANDARD,
        fallbackRamp: CognitiveColorRamp = CognitiveColorRamp.NEUTRAL
    ): Array<Array<AsciiCell>> {
        clear()

        val gridPoints = buildSortedGridPoints(waveform, camera)
        val cameraPos = camera.position

        for ((gx, gz, _) in gridPoints) {
            val worldPos = waveform.worldPosition(gx, gz)

            val screenPoint = projector.project(worldPos, camera)
            if (!screenPoint.visible) continue

            val sx = screenPoint.x
            val sy = screenPoint.y
            val bufferIndex = sy * screenWidth + sx

            if (screenPoint.depth < depthBuffer[bufferIndex]) {
                val normal = waveform.normalAt(gx, gz)
                val viewDir = (cameraPos - worldPos).normalized()
                val luminance = lighting.computeLuminance(normal, viewDir)

                // Get blended palette and color ramp for this position
                val (palette, colorRamp) = blender.blendedPaletteAt(
                    worldPos.x, worldPos.z, agents
                ) ?: (fallbackPalette to fallbackRamp)

                val cell = AsciiCell.fromSurface(
                    luminance = luminance,
                    normalX = normal.x,
                    normalY = normal.z,
                    palette = palette,
                    colorRamp = colorRamp
                )

                cellBuffer[bufferIndex] = cell
                depthBuffer[bufferIndex] = screenPoint.depth
            }
        }

        return Array(screenHeight) { y ->
            Array(screenWidth) { x ->
                cellBuffer[y * screenWidth + x]
            }
        }
    }

    /** Clear buffers for next frame. */
    fun clear() {
        for (i in depthBuffer.indices) {
            depthBuffer[i] = Float.MAX_VALUE
        }
        for (i in cellBuffer.indices) {
            cellBuffer[i] = AsciiCell.EMPTY
        }
    }

    /**
     * Build grid points sorted back-to-front (farthest from camera first).
     */
    private fun buildSortedGridPoints(
        waveform: CognitiveWaveform,
        camera: Camera
    ): List<GridPoint> {
        val points = ArrayList<GridPoint>(waveform.gridWidth * waveform.gridDepth)

        for (gz in 0 until waveform.gridDepth) {
            for (gx in 0 until waveform.gridWidth) {
                val worldPos = waveform.worldPosition(gx, gz)
                val dist = (worldPos - camera.position).lengthSquared()
                points.add(GridPoint(gx, gz, dist))
            }
        }

        // Sort by distance descending (back-to-front for painter's algorithm)
        points.sortByDescending { it.distanceSquared }

        return points
    }

    private data class GridPoint(
        val gx: Int,
        val gz: Int,
        val distanceSquared: Float
    )
}
