package link.socket.ampere.animation.waveform

import link.socket.ampere.animation.emitter.EffectInfluence
import link.socket.ampere.animation.emitter.EmitterManager
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
    companion object {
        /** Maximum expected height for normalization (agentPeakScale + ridge stacking). */
        private const val HEIGHT_CEILING = 3.5f

        /**
         * Additive luminance boost at maximum height. Peaks get brighter characters
         * while flat terrain remains unchanged from normal Blinn-Phong shading.
         */
        private const val PEAK_BRIGHTNESS_BOOST = 0.35f
    }

    /** Depth buffer for occlusion -- one float per screen cell */
    private val depthBuffer = FloatArray(screenWidth * screenHeight) { Float.MAX_VALUE }

    /** Output buffer (flat, row-major) */
    private val cellBuffer = Array(screenWidth * screenHeight) { AsciiCell.EMPTY }

    /** Diagnostic counters from last rasterize call. */
    var lastTotalCells = 0; private set
    var lastEffectCells = 0; private set
    var lastCharOverrideCells = 0; private set
    var lastWrittenCells = 0; private set

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
        colorRamp: CognitiveColorRamp,
        emitterManager: EmitterManager? = null
    ): Array<Array<AsciiCell>> {
        clear()

        // Build list of grid points with their distance from camera for back-to-front sort
        val gridPoints = buildSortedGridPoints(waveform, camera)

        // View direction for specular lighting
        val cameraPos = camera.position

        // Rasterize back-to-front
        for ((gx, gz, _) in gridPoints) {
            val baseWorldPos = waveform.worldPosition(gx, gz)

            // Apply emitter height modification before projection
            val influence = emitterManager?.aggregateInfluenceAt(baseWorldPos.x, baseWorldPos.z)
            val worldPos = if (influence != null && influence.heightModifier != 0f) {
                Vector3(baseWorldPos.x, baseWorldPos.y + influence.heightModifier, baseWorldPos.z)
            } else {
                baseWorldPos
            }

            val screenPoint = projector.project(worldPos, camera)
            if (!screenPoint.visible) continue

            val sx = screenPoint.x
            val sy = screenPoint.y
            val bufferIndex = sy * screenWidth + sx

            // Depth test: write only if closer than what's already there
            if (screenPoint.depth < depthBuffer[bufferIndex]) {
                lastTotalCells++
                val normal = waveform.normalAt(gx, gz)
                val viewDir = (cameraPos - worldPos).normalized()
                val baseLuminance = lighting.computeLuminance(normal, viewDir)

                // Height-based luminance boost: peaks glow brighter than flat terrain.
                // Without this, peak tops have flat normals and look identical to plains.
                val normalizedHeight = (worldPos.y / HEIGHT_CEILING).coerceIn(0f, 1f)
                val luminance = (baseLuminance + normalizedHeight * PEAK_BRIGHTNESS_BOOST).coerceIn(0f, 1f)

                val isEffect = influence != null && influence.intensity > 0f
                if (isEffect) {
                    lastEffectCells++
                    if (influence!!.characterOverride != null) lastCharOverrideCells++
                }
                val cell = if (isEffect) {
                    buildEffectCell(luminance, normal, influence!!, palette, colorRamp)
                } else {
                    AsciiCell.fromSurface(
                        luminance = luminance,
                        normalX = normal.x,
                        normalY = normal.z,
                        palette = palette,
                        colorRamp = colorRamp
                    )
                }

                cellBuffer[bufferIndex] = cell
                depthBuffer[bufferIndex] = screenPoint.depth
            }
        }

        lastWrittenCells = cellBuffer.count { it != AsciiCell.EMPTY }

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
        fallbackRamp: CognitiveColorRamp = CognitiveColorRamp.NEUTRAL,
        emitterManager: EmitterManager? = null
    ): Array<Array<AsciiCell>> {
        clear()

        val gridPoints = buildSortedGridPoints(waveform, camera)
        val cameraPos = camera.position

        for ((gx, gz, _) in gridPoints) {
            val baseWorldPos = waveform.worldPosition(gx, gz)

            // Apply emitter height modification before projection
            val influence = emitterManager?.aggregateInfluenceAt(baseWorldPos.x, baseWorldPos.z)
            val worldPos = if (influence != null && influence.heightModifier != 0f) {
                Vector3(baseWorldPos.x, baseWorldPos.y + influence.heightModifier, baseWorldPos.z)
            } else {
                baseWorldPos
            }

            val screenPoint = projector.project(worldPos, camera)
            if (!screenPoint.visible) continue

            val sx = screenPoint.x
            val sy = screenPoint.y
            val bufferIndex = sy * screenWidth + sx

            if (screenPoint.depth < depthBuffer[bufferIndex]) {
                lastTotalCells++
                val normal = waveform.normalAt(gx, gz)
                val viewDir = (cameraPos - worldPos).normalized()
                val baseLuminance = lighting.computeLuminance(normal, viewDir)

                // Height-based luminance boost: peaks glow brighter than flat terrain.
                val normalizedHeight = (worldPos.y / HEIGHT_CEILING).coerceIn(0f, 1f)
                val luminance = (baseLuminance + normalizedHeight * PEAK_BRIGHTNESS_BOOST).coerceIn(0f, 1f)

                // Get blended palette and color ramp for this position
                val (palette, colorRamp) = blender.blendedPaletteAt(
                    worldPos.x, worldPos.z, agents
                ) ?: (fallbackPalette to fallbackRamp)

                val isEffect = influence != null && influence.intensity > 0f
                if (isEffect) {
                    lastEffectCells++
                    if (influence!!.characterOverride != null) lastCharOverrideCells++
                }
                val cell = if (isEffect) {
                    buildEffectCell(luminance, normal, influence!!, palette, colorRamp)
                } else {
                    AsciiCell.fromSurface(
                        luminance = luminance,
                        normalX = normal.x,
                        normalY = normal.z,
                        palette = palette,
                        colorRamp = colorRamp
                    )
                }

                cellBuffer[bufferIndex] = cell
                depthBuffer[bufferIndex] = screenPoint.depth
            }
        }

        lastWrittenCells = cellBuffer.count { it != AsciiCell.EMPTY }

        return Array(screenHeight) { y ->
            Array(screenWidth) { x ->
                cellBuffer[y * screenWidth + x]
            }
        }
    }

    /**
     * Build a cell with emitter effect modifications applied.
     *
     * Applies luminance boost, palette/color/character overrides from the effect influence.
     */
    private fun buildEffectCell(
        baseLuminance: Float,
        normal: Vector3,
        influence: EffectInfluence,
        basePalette: AsciiLuminancePalette,
        baseColorRamp: CognitiveColorRamp
    ): AsciiCell {
        val modifiedLuminance = (baseLuminance + influence.luminanceModifier).coerceIn(0f, 1f)
        val effectivePalette = influence.paletteOverride ?: basePalette
        val effectiveColorRamp = baseColorRamp

        // Background glow proportional to effect intensity — makes effects visible
        // even when character/color differences are subtle.
        val bgColor = when {
            influence.intensity > 0.5f -> 17  // dark blue for strong effects
            influence.intensity > 0.2f -> 234 // very dark grey for medium effects
            else -> null
        }

        // Character override takes priority (for confetti, sparks, etc.)
        return if (influence.characterOverride != null) {
            val fg = influence.colorOverride ?: effectiveColorRamp.colorForLuminance(modifiedLuminance)
            AsciiCell(
                char = influence.characterOverride,
                fgColor = fg,
                bgColor = bgColor ?: 17, // Always show bg for character overrides
                bold = influence.intensity > 0.7f
            )
        } else {
            val fg = influence.colorOverride ?: effectiveColorRamp.colorForLuminance(modifiedLuminance)
            if (influence.colorOverride != null) {
                // Color override: use the overridden color but still pick character from palette
                val ch = effectivePalette.charForSurface(modifiedLuminance, normal.x, normal.z)
                AsciiCell(
                    char = ch,
                    fgColor = fg,
                    bgColor = bgColor,
                    bold = modifiedLuminance > 0.8f
                )
            } else {
                val base = AsciiCell.fromSurface(
                    luminance = modifiedLuminance,
                    normalX = normal.x,
                    normalY = normal.z,
                    palette = effectivePalette,
                    colorRamp = effectiveColorRamp
                )
                if (bgColor != null) base.copy(bgColor = bgColor) else base
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
        lastTotalCells = 0
        lastEffectCells = 0
        lastCharOverrideCells = 0
        lastWrittenCells = 0
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
