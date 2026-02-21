package link.socket.ampere.animation.waveform

import link.socket.ampere.animation.math.Vector3
import kotlin.math.max
import kotlin.math.pow

/**
 * Computes luminance for each point on the waveform surface based on
 * surface normal and light direction.
 *
 * This is the bridge between 3D geometry and ASCII character selection.
 * The light direction is fixed (slightly above and to the left, like
 * natural reading light) -- what changes is the surface, not the light.
 *
 * Uses a Blinn-Phong shading model: ambient + diffuse + specular.
 *
 * @param lightDirection Direction FROM the surface TOWARD the light (normalized)
 * @param ambientLight Minimum luminance floor (even in shadow)
 * @param diffuseStrength How much surface angle affects brightness
 * @param specularStrength Intensity of specular highlights
 * @param specularPower Tightness of specular highlights (higher = sharper)
 */
class SurfaceLighting(
    lightDirection: Vector3 = Vector3(-0.5f, 1.0f, -0.3f),
    val ambientLight: Float = 0.15f,
    val diffuseStrength: Float = 0.7f,
    val specularStrength: Float = 0.15f,
    val specularPower: Float = 8f
) {
    val lightDir: Vector3 = lightDirection.normalized()

    /**
     * Compute luminance at a surface point.
     *
     * @param normal Surface normal at this point (should be normalized)
     * @param viewDir Direction from surface point to camera (should be normalized)
     * @return Luminance in 0.0-1.0 range
     */
    fun computeLuminance(normal: Vector3, viewDir: Vector3): Float {
        val n = normal.normalized()
        val v = viewDir.normalized()

        // Diffuse: max(0, N . L)
        val nDotL = max(0f, n.dot(lightDir))
        val diffuse = diffuseStrength * nDotL

        // Specular (Blinn-Phong): uses half-vector between light and view
        val halfVec = (lightDir + v).normalized()
        val nDotH = max(0f, n.dot(halfVec))
        val specular = if (nDotL > 0f) {
            specularStrength * nDotH.pow(specularPower)
        } else {
            0f
        }

        return (ambientLight + diffuse + specular).coerceIn(0f, 1f)
    }
}
