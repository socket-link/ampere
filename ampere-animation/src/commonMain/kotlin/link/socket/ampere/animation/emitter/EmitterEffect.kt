package link.socket.ampere.animation.emitter

import link.socket.ampere.animation.render.AsciiLuminancePalette
import link.socket.ampere.animation.render.CognitiveColorRamp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.PI

/**
 * A transient visual effect anchored in 3D space.
 *
 * Effects are the neurotransmitters of the visual system — brief chemical
 * signals that alter the behavior of nearby cells (characters) for a short time.
 * They don't persist in the scene; they modify it transiently and then decay.
 *
 * An EmitterEffect defines the WHAT (shape, intensity, decay).
 * An EmitterInstance is a WHEN+WHERE (triggered at a specific time and position).
 */
sealed class EmitterEffect(
    val name: String,
    val duration: Float,
    val radius: Float,
    val peakIntensity: Float = 1f
) {
    /**
     * Compute the effect's influence at a given distance from its center,
     * at a given time since activation.
     *
     * @return An EffectInfluence describing how to modify the surface/character at this point
     */
    abstract fun influence(distanceFromCenter: Float, timeSinceActivation: Float): EffectInfluence

    /** Radial burst — concentric rings expanding outward. "Spark received" effect. */
    class SparkBurst(
        duration: Float = 0.8f,
        radius: Float = 5f,
        val ringWidth: Float = 0.5f,
        val expansionSpeed: Float = 8f,
        val palette: AsciiLuminancePalette = AsciiLuminancePalette.EXECUTE,
        val ringColor: Int = 231, // bright white
        val ringCharacters: String = "\u2605\u25C6*\u26A1\u2588" // ★◆*⚡█
    ) : EmitterEffect("spark_burst", duration, radius) {

        override fun influence(distanceFromCenter: Float, timeSinceActivation: Float): EffectInfluence {
            if (timeSinceActivation >= duration || timeSinceActivation < 0f) return EffectInfluence.NONE
            if (distanceFromCenter > radius) return EffectInfluence.NONE

            val ringCenter = expansionSpeed * timeSinceActivation
            val distFromRing = abs(distanceFromCenter - ringCenter)
            val ringInfluence = if (distFromRing < ringWidth) {
                (1f - distFromRing / ringWidth)
            } else {
                0f
            }

            // Decay over time
            val timeDecay = 1f - (timeSinceActivation / duration)
            val intensity = ringInfluence * timeDecay * peakIntensity

            if (intensity < 0.1f) return EffectInfluence(intensity = intensity)

            // Pick a ring character based on distance (deterministic variation)
            val charIndex = ((distanceFromCenter * 5.3f).toInt() and 0x7FFFFFFF) % ringCharacters.length
            val ch = ringCharacters[charIndex]

            return EffectInfluence(
                luminanceModifier = intensity * 0.6f,
                characterOverride = if (intensity > 0.3f) ch else null,
                colorOverride = if (intensity > 0.3f) ringColor else null,
                paletteOverride = if (intensity > 0.3f) palette else null,
                intensity = intensity
            )
        }
    }

    /** Height pulse — the surface bulges upward briefly. "Phase transition" effect. */
    class HeightPulse(
        duration: Float = 1.2f,
        radius: Float = 4f,
        val maxHeightBoost: Float = 3f,
        val riseSpeed: Float = 4f,
        val fallSpeed: Float = 2f,
        val pulseColor: Int = 159 // bright cyan
    ) : EmitterEffect("height_pulse", duration, radius) {

        override fun influence(distanceFromCenter: Float, timeSinceActivation: Float): EffectInfluence {
            if (timeSinceActivation >= duration || timeSinceActivation < 0f) return EffectInfluence.NONE
            if (distanceFromCenter > radius) return EffectInfluence.NONE

            // Rise phase then fall phase
            val riseDuration = duration * (fallSpeed / (riseSpeed + fallSpeed))
            val heightFactor = if (timeSinceActivation < riseDuration) {
                timeSinceActivation / riseDuration
            } else {
                1f - (timeSinceActivation - riseDuration) / (duration - riseDuration)
            }.coerceIn(0f, 1f)

            // Gaussian spatial falloff from center
            val spatialFalloff = exp(-(distanceFromCenter * distanceFromCenter) / (radius * radius * 0.5f))

            val intensity = heightFactor * spatialFalloff * peakIntensity
            val heightMod = maxHeightBoost * intensity

            return EffectInfluence(
                heightModifier = heightMod,
                luminanceModifier = intensity * 0.3f,
                colorOverride = if (intensity > 0.4f) pulseColor else null,
                characterOverride = if (intensity > 0.6f) '\u2588' else null, // █ at center
                intensity = intensity
            )
        }
    }

    /** Turbulence — surface becomes noisy and jittery. "Uncertainty spike" effect. */
    class Turbulence(
        duration: Float = 2f,
        radius: Float = 6f,
        val noiseFrequency: Float = 3f,
        val noiseAmplitude: Float = 1.5f
    ) : EmitterEffect("turbulence", duration, radius) {

        override fun influence(distanceFromCenter: Float, timeSinceActivation: Float): EffectInfluence {
            if (timeSinceActivation >= duration || timeSinceActivation < 0f) return EffectInfluence.NONE
            if (distanceFromCenter > radius) return EffectInfluence.NONE

            // Envelope: ramp up quickly, sustain, then fade
            val envelope = when {
                timeSinceActivation < 0.2f -> timeSinceActivation / 0.2f
                timeSinceActivation > duration - 0.5f -> (duration - timeSinceActivation) / 0.5f
                else -> 1f
            }.coerceIn(0f, 1f)

            val spatialFalloff = (1f - distanceFromCenter / radius).coerceIn(0f, 1f)

            // Pseudo-noise using sin with multiple frequencies
            val noise = sin(distanceFromCenter * noiseFrequency + timeSinceActivation * 7f) *
                cos(timeSinceActivation * noiseFrequency * 2.3f)

            val intensity = envelope * spatialFalloff * peakIntensity
            val heightMod = noise * noiseAmplitude * intensity

            return EffectInfluence(
                heightModifier = heightMod,
                luminanceModifier = abs(noise) * intensity * 0.4f,
                intensity = intensity
            )
        }
    }

    /** Color wash — a wave of color spreading from the center. "Phase entered" effect. */
    class ColorWash(
        duration: Float = 1.5f,
        radius: Float = 8f,
        val colorRamp: CognitiveColorRamp,
        val waveFrontSpeed: Float = 6f,
        val waveFrontChars: String = "\u2593\u2592\u2591\u00B7" // ▓▒░·
    ) : EmitterEffect("color_wash", duration, radius) {

        override fun influence(distanceFromCenter: Float, timeSinceActivation: Float): EffectInfluence {
            if (timeSinceActivation >= duration || timeSinceActivation < 0f) return EffectInfluence.NONE
            if (distanceFromCenter > radius) return EffectInfluence.NONE

            val waveFront = waveFrontSpeed * timeSinceActivation

            // Has the wave reached this point yet?
            if (distanceFromCenter > waveFront) return EffectInfluence.NONE

            // Intensity fades behind the wave front and decays over time
            val behindWave = (waveFront - distanceFromCenter).coerceIn(0f, 2f) / 2f
            val timeDecay = 1f - (timeSinceActivation / duration)
            val intensity = behindWave * timeDecay * peakIntensity

            val colorCode = colorRamp.colorForLuminance(intensity.coerceIn(0f, 1f))

            // At the wave front, show distinctive characters
            val distBehind = waveFront - distanceFromCenter
            val charOverride = if (distBehind < 1.5f && intensity > 0.3f) {
                val idx = (distBehind * 2.5f).toInt().coerceIn(0, waveFrontChars.lastIndex)
                waveFrontChars[idx]
            } else null

            return EffectInfluence(
                luminanceModifier = intensity * 0.3f,
                colorOverride = colorCode,
                characterOverride = charOverride,
                intensity = intensity
            )
        }
    }

    /** Confetti — scattered bright characters. "Task completed" celebration. */
    class Confetti(
        duration: Float = 1f,
        radius: Float = 3f,
        val particleCount: Int = 15,
        val characters: String = "\u2726\u2727\u26A1\u2605\u00B7*"
    ) : EmitterEffect("confetti", duration, radius) {

        override fun influence(distanceFromCenter: Float, timeSinceActivation: Float): EffectInfluence {
            if (timeSinceActivation >= duration || timeSinceActivation < 0f) return EffectInfluence.NONE
            if (distanceFromCenter > radius) return EffectInfluence.NONE

            val timeDecay = 1f - (timeSinceActivation / duration)
            val spatialFalloff = (1f - distanceFromCenter / radius).coerceIn(0f, 1f)
            val intensity = timeDecay * spatialFalloff * peakIntensity

            // Select character based on distance (deterministic pseudo-random)
            val charIndex = ((distanceFromCenter * 7.3f + timeSinceActivation * 3.7f).toInt() and 0x7FFFFFFF) % characters.length
            val ch = characters[charIndex]

            return if (intensity > 0.2f) {
                EffectInfluence(
                    luminanceModifier = intensity * 0.5f,
                    characterOverride = ch,
                    intensity = intensity
                )
            } else {
                EffectInfluence(intensity = intensity)
            }
        }
    }
}

/**
 * The computed influence of an effect at a specific point in space and time.
 */
data class EffectInfluence(
    val heightModifier: Float = 0f,
    val luminanceModifier: Float = 0f,
    val paletteOverride: AsciiLuminancePalette? = null,
    val colorOverride: Int? = null,
    val characterOverride: Char? = null,
    val intensity: Float = 0f
) {
    companion object {
        val NONE = EffectInfluence()
    }

    /**
     * Combine two influences additively.
     */
    operator fun plus(other: EffectInfluence): EffectInfluence {
        return EffectInfluence(
            heightModifier = heightModifier + other.heightModifier,
            luminanceModifier = luminanceModifier + other.luminanceModifier,
            // Higher-intensity effect wins for discrete overrides
            paletteOverride = if ((other.intensity) > intensity) other.paletteOverride ?: paletteOverride else paletteOverride ?: other.paletteOverride,
            colorOverride = if ((other.intensity) > intensity) other.colorOverride ?: colorOverride else colorOverride ?: other.colorOverride,
            characterOverride = if ((other.intensity) > intensity) other.characterOverride ?: characterOverride else characterOverride ?: other.characterOverride,
            intensity = maxOf(intensity, other.intensity)
        )
    }
}
