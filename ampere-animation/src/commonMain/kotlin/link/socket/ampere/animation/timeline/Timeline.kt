package link.socket.ampere.animation.timeline

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A keyframe event that triggers at a specific time within a phase.
 *
 * @property time Relative time within the phase
 * @property action The action to execute
 */
data class Keyframe(
    val time: Duration,
    val action: () -> Unit
)

/**
 * A phase in the timeline with a duration and keyframe events.
 *
 * @property name Unique name for this phase
 * @property duration Total duration of this phase
 * @property keyframes Events to trigger at specific times
 * @property onStart Callback when phase starts
 * @property onComplete Callback when phase completes
 * @property easing Easing function for phase progress
 */
data class Phase(
    val name: String,
    val duration: Duration,
    val keyframes: List<Keyframe> = emptyList(),
    val onStart: (() -> Unit)? = null,
    val onComplete: (() -> Unit)? = null,
    val easing: (Float) -> Float = Easing.linear
) {
    /**
     * Get keyframes that should fire at or before the given time
     * but after the previous time.
     */
    fun getKeyframesInRange(previousTime: Duration, currentTime: Duration): List<Keyframe> {
        return keyframes.filter { kf ->
            kf.time > previousTime && kf.time <= currentTime
        }
    }

    /**
     * Get the eased progress for a raw progress value.
     */
    fun easedProgress(rawProgress: Float): Float = easing(rawProgress.coerceIn(0f, 1f))
}

/**
 * State of a timeline phase.
 */
enum class PhaseState {
    /** Not yet started */
    PENDING,
    /** Currently playing */
    PLAYING,
    /** Completed */
    COMPLETED
}

/**
 * Internal state tracking for a phase.
 */
data class PhasePlaybackState(
    val phase: Phase,
    var state: PhaseState = PhaseState.PENDING,
    var localTime: Duration = Duration.ZERO,
    var firedKeyframes: MutableSet<Int> = mutableSetOf()
)

/**
 * A timeline composed of sequential phases.
 *
 * Timeline orchestrates multi-phase animations with precise timing,
 * easing, and event callbacks.
 *
 * @property phases The phases in playback order
 */
class Timeline(
    val phases: List<Phase>
) {
    /** Total duration of all phases combined */
    val totalDuration: Duration = phases.fold(Duration.ZERO) { acc, phase -> acc + phase.duration }

    /** Number of phases */
    val phaseCount: Int = phases.size

    /**
     * Get a phase by name.
     */
    fun getPhase(name: String): Phase? = phases.find { it.name == name }

    /**
     * Get the phase at a given absolute time.
     *
     * @return Pair of (phase, localTime within phase)
     */
    fun getPhaseAtTime(time: Duration): Pair<Phase, Duration>? {
        var accumulated = Duration.ZERO
        for (phase in phases) {
            if (time < accumulated + phase.duration) {
                return phase to (time - accumulated)
            }
            accumulated += phase.duration
        }
        // Past end - return last phase at its end
        return phases.lastOrNull()?.let { it to it.duration }
    }

    /**
     * Get the phase index at a given absolute time.
     */
    fun getPhaseIndexAtTime(time: Duration): Int {
        var accumulated = Duration.ZERO
        for ((index, phase) in phases.withIndex()) {
            if (time < accumulated + phase.duration) {
                return index
            }
            accumulated += phase.duration
        }
        return phases.lastIndex.coerceAtLeast(0)
    }

    /**
     * Get the start time of a phase by index.
     */
    fun getPhaseStartTime(index: Int): Duration {
        return phases.take(index).fold(Duration.ZERO) { acc, phase -> acc + phase.duration }
    }

    /**
     * Get the start time of a phase by name.
     */
    fun getPhaseStartTime(name: String): Duration? {
        var accumulated = Duration.ZERO
        for (phase in phases) {
            if (phase.name == name) return accumulated
            accumulated += phase.duration
        }
        return null
    }

    companion object {
        /**
         * Create an empty timeline.
         */
        fun empty(): Timeline = Timeline(emptyList())
    }
}

/**
 * Event emitted by a timeline during playback.
 */
sealed class TimelineEvent {
    /** Timeline started playing */
    object Started : TimelineEvent()

    /** Timeline paused */
    object Paused : TimelineEvent()

    /** Timeline resumed */
    object Resumed : TimelineEvent()

    /** Timeline completed all phases */
    object Completed : TimelineEvent()

    /** Timeline was reset */
    object Reset : TimelineEvent()

    /** A new phase started */
    data class PhaseStarted(val phase: Phase, val index: Int) : TimelineEvent()

    /** A phase completed */
    data class PhaseCompleted(val phase: Phase, val index: Int) : TimelineEvent()

    /** A keyframe was triggered */
    data class KeyframeTriggered(val phase: Phase, val keyframe: Keyframe) : TimelineEvent()

    /** Timeline position changed (via seek) */
    data class Seeked(val time: Duration) : TimelineEvent()
}

/**
 * Listener for timeline events.
 */
typealias TimelineEventListener = (TimelineEvent) -> Unit
