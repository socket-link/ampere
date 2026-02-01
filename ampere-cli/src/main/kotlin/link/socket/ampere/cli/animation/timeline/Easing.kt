package link.socket.ampere.cli.animation.timeline

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Common easing functions for smooth animations.
 *
 * Each function takes a value t in [0, 1] and returns
 * an eased value, also in [0, 1] (except for elastic/bounce
 * which may overshoot).
 */
object Easing {

    /**
     * Linear interpolation - no acceleration.
     */
    val linear: (Float) -> Float = { t -> t }

    /**
     * Quadratic ease in - slow start.
     */
    val easeIn: (Float) -> Float = { t -> t * t }

    /**
     * Quadratic ease out - slow end.
     */
    val easeOut: (Float) -> Float = { t -> t * (2 - t) }

    /**
     * Quadratic ease in-out - slow start and end.
     */
    val easeInOut: (Float) -> Float = { t ->
        if (t < 0.5f) 2 * t * t else 1 - (-2 * t + 2).pow(2) / 2
    }

    /**
     * Cubic ease in - slow start with more acceleration.
     */
    val easeInCubic: (Float) -> Float = { t -> t * t * t }

    /**
     * Cubic ease out - slow end with more deceleration.
     */
    val easeOutCubic: (Float) -> Float = { t ->
        val t1 = t - 1
        t1 * t1 * t1 + 1
    }

    /**
     * Cubic ease in-out.
     */
    val easeInOutCubic: (Float) -> Float = { t ->
        if (t < 0.5f) {
            4 * t * t * t
        } else {
            val t1 = 2 * t - 2
            0.5f * t1 * t1 * t1 + 1
        }
    }

    /**
     * Quartic ease in.
     */
    val easeInQuart: (Float) -> Float = { t -> t * t * t * t }

    /**
     * Quartic ease out.
     */
    val easeOutQuart: (Float) -> Float = { t ->
        val t1 = t - 1
        1 - t1 * t1 * t1 * t1
    }

    /**
     * Elastic ease out - overshoot and bounce back.
     *
     * Creates a spring-like effect at the end.
     */
    val easeOutElastic: (Float) -> Float = { t ->
        if (t == 0f || t == 1f) {
            t
        } else {
            2f.pow(-10 * t) * sin((t * 10 - 0.75f) * (2 * PI.toFloat() / 3)) + 1
        }
    }

    /**
     * Elastic ease in - spring-like start.
     */
    val easeInElastic: (Float) -> Float = { t ->
        if (t == 0f || t == 1f) {
            t
        } else {
            -(2f.pow(10 * t - 10)) * sin((t * 10 - 10.75f) * (2 * PI.toFloat() / 3))
        }
    }

    /**
     * Back ease out - slight overshoot.
     */
    val easeOutBack: (Float) -> Float = { t ->
        val c1 = 1.70158f
        val c3 = c1 + 1
        val t1 = t - 1
        1 + c3 * t1 * t1 * t1 + c1 * t1 * t1
    }

    /**
     * Back ease in - pull back before starting.
     */
    val easeInBack: (Float) -> Float = { t ->
        val c1 = 1.70158f
        val c3 = c1 + 1
        c3 * t * t * t - c1 * t * t
    }

    /**
     * Bounce ease out - bouncing effect at end.
     */
    val easeOutBounce: (Float) -> Float = { t ->
        val n1 = 7.5625f
        val d1 = 2.75f
        when {
            t < 1 / d1 -> n1 * t * t
            t < 2 / d1 -> {
                val t1 = t - 1.5f / d1
                n1 * t1 * t1 + 0.75f
            }
            t < 2.5 / d1 -> {
                val t1 = t - 2.25f / d1
                n1 * t1 * t1 + 0.9375f
            }
            else -> {
                val t1 = t - 2.625f / d1
                n1 * t1 * t1 + 0.984375f
            }
        }
    }

    /**
     * Circular ease out.
     */
    val easeOutCirc: (Float) -> Float = { t ->
        sqrt(1 - (t - 1).pow(2))
    }

    /**
     * Circular ease in.
     */
    val easeInCirc: (Float) -> Float = { t ->
        1 - sqrt(1 - t.pow(2))
    }

    /**
     * Exponential ease out.
     */
    val easeOutExpo: (Float) -> Float = { t ->
        if (t == 1f) 1f else 1 - 2f.pow(-10 * t)
    }

    /**
     * Exponential ease in.
     */
    val easeInExpo: (Float) -> Float = { t ->
        if (t == 0f) 0f else 2f.pow(10 * t - 10)
    }

    /**
     * Get an easing function by name.
     */
    fun byName(name: String): ((Float) -> Float)? = when (name.lowercase()) {
        "linear" -> linear
        "easein", "ease-in" -> easeIn
        "easeout", "ease-out" -> easeOut
        "easeinout", "ease-in-out" -> easeInOut
        "easeincubic" -> easeInCubic
        "easeoutcubic" -> easeOutCubic
        "easeinoutcubic" -> easeInOutCubic
        "easeinquart" -> easeInQuart
        "easeoutquart" -> easeOutQuart
        "easeinelastic" -> easeInElastic
        "easeoutelastic" -> easeOutElastic
        "easeinback" -> easeInBack
        "easeoutback" -> easeOutBack
        "easeoutbounce" -> easeOutBounce
        "easeincirc" -> easeInCirc
        "easeoutcirc" -> easeOutCirc
        "easeinexpo" -> easeInExpo
        "easeoutexpo" -> easeOutExpo
        else -> null
    }

    /**
     * All available easing function names.
     */
    val availableNames = listOf(
        "linear",
        "easeIn", "easeOut", "easeInOut",
        "easeInCubic", "easeOutCubic", "easeInOutCubic",
        "easeInQuart", "easeOutQuart",
        "easeInElastic", "easeOutElastic",
        "easeInBack", "easeOutBack",
        "easeOutBounce",
        "easeInCirc", "easeOutCirc",
        "easeInExpo", "easeOutExpo"
    )
}
