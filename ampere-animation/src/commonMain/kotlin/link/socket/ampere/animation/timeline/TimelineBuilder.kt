package link.socket.ampere.animation.timeline

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * DSL builder for creating phases.
 */
class PhaseBuilder(
    private val name: String,
    private val duration: Duration
) {
    private val keyframes = mutableListOf<Keyframe>()
    private var onStart: (() -> Unit)? = null
    private var onComplete: (() -> Unit)? = null
    private var easing: (Float) -> Float = Easing.linear

    /**
     * Add a keyframe at a specific time within the phase.
     *
     * @param time Time relative to phase start
     * @param action Action to execute
     */
    fun at(time: Duration, action: () -> Unit) {
        keyframes.add(Keyframe(time, action))
    }

    /**
     * Add a keyframe at a specific time in seconds.
     *
     * @param seconds Time in seconds relative to phase start
     * @param action Action to execute
     */
    fun at(seconds: Double, action: () -> Unit) {
        at((seconds * 1000).toLong().milliseconds, action)
    }

    /**
     * Add a keyframe at a progress point (0.0 to 1.0).
     *
     * @param progress Progress through the phase (0.0 to 1.0)
     * @param action Action to execute
     */
    fun atProgress(progress: Float, action: () -> Unit) {
        val time = (duration.inWholeMilliseconds * progress).toLong().milliseconds
        at(time, action)
    }

    /**
     * Callback when phase starts.
     */
    fun onStart(action: () -> Unit) {
        onStart = action
    }

    /**
     * Callback when phase completes.
     */
    fun onComplete(action: () -> Unit) {
        onComplete = action
    }

    /**
     * Set the easing function for this phase.
     */
    fun easing(easingFn: (Float) -> Float) {
        easing = easingFn
    }

    /**
     * Set easing by name.
     */
    fun easing(name: String) {
        Easing.byName(name)?.let { easing = it }
    }

    /**
     * Build the phase.
     */
    internal fun build(): Phase = Phase(
        name = name,
        duration = duration,
        keyframes = keyframes.sortedBy { it.time },
        onStart = onStart,
        onComplete = onComplete,
        easing = easing
    )
}

/**
 * DSL builder for creating timelines.
 */
class TimelineBuilder {
    private val phases = mutableListOf<Phase>()

    /**
     * Add a phase to the timeline.
     *
     * @param name Unique name for the phase
     * @param duration Duration of the phase
     * @param block Configuration block for the phase
     */
    fun phase(
        name: String,
        duration: Duration,
        block: PhaseBuilder.() -> Unit = {}
    ) {
        val builder = PhaseBuilder(name, duration)
        builder.block()
        phases.add(builder.build())
    }

    /**
     * Add a phase with duration in seconds.
     *
     * @param name Unique name for the phase
     * @param durationSeconds Duration in seconds
     * @param block Configuration block for the phase
     */
    fun phase(
        name: String,
        durationSeconds: Double,
        block: PhaseBuilder.() -> Unit = {}
    ) {
        phase(name, (durationSeconds * 1000).toLong().milliseconds, block)
    }

    /**
     * Add a pause (empty phase with no events).
     *
     * @param duration Duration of the pause
     */
    fun pause(duration: Duration) {
        phases.add(Phase(
            name = "_pause_${phases.size}",
            duration = duration
        ))
    }

    /**
     * Add a pause in seconds.
     */
    fun pause(seconds: Double) {
        pause((seconds * 1000).toLong().milliseconds)
    }

    /**
     * Build the timeline.
     */
    internal fun build(): Timeline = Timeline(phases)
}

/**
 * Create a timeline using the DSL.
 *
 * Example:
 * ```kotlin
 * val timeline = timeline {
 *     phase("genesis", 2.seconds) {
 *         at(0.0) { println("Start") }
 *         at(1.0) { println("Middle") }
 *         onComplete { println("Done") }
 *     }
 *     phase("awakening", 2.seconds) {
 *         at(0.5) { println("Wake up") }
 *     }
 * }
 * ```
 */
fun timeline(block: TimelineBuilder.() -> Unit): Timeline {
    val builder = TimelineBuilder()
    builder.block()
    return builder.build()
}

/**
 * Extension property for creating Duration from seconds.
 */
val Int.seconds: Duration get() = (this * 1000L).milliseconds

/**
 * Extension property for creating Duration from milliseconds.
 */
val Int.milliseconds: Duration get() = this.toLong().milliseconds

/**
 * Extension property for creating Duration from seconds (Double).
 */
val Double.seconds: Duration get() = (this * 1000).toLong().milliseconds
