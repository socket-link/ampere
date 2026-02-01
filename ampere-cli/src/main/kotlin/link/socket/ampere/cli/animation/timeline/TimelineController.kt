package link.socket.ampere.cli.animation.timeline

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Playback state of a timeline controller.
 */
enum class PlaybackState {
    /** Not yet started */
    STOPPED,
    /** Currently playing */
    PLAYING,
    /** Temporarily paused */
    PAUSED,
    /** Completed playback */
    COMPLETED
}

/**
 * Controller for timeline playback with play, pause, seek, and speed control.
 *
 * Usage:
 * ```kotlin
 * val controller = TimelineController(timeline)
 * controller.play()
 *
 * // In game/animation loop:
 * controller.update(deltaTime)
 *
 * // Get current state
 * println(controller.currentPhase)
 * println(controller.progress)
 * ```
 *
 * @property timeline The timeline to control
 */
class TimelineController(
    val timeline: Timeline
) {
    private val phaseStates = timeline.phases.map { PhasePlaybackState(it) }
    private val eventListeners = mutableListOf<TimelineEventListener>()

    /** Current playback state */
    var playbackState: PlaybackState = PlaybackState.STOPPED
        private set

    /** Current absolute time in the timeline */
    var currentTime: Duration = Duration.ZERO
        private set

    /** Playback speed multiplier (1.0 = normal) */
    var speed: Float = 1f

    /** Current phase index (0-based) */
    val currentPhaseIndex: Int
        get() = timeline.getPhaseIndexAtTime(currentTime)

    /** Current phase name */
    val currentPhase: String
        get() = timeline.phases.getOrNull(currentPhaseIndex)?.name ?: ""

    /** Progress through entire timeline (0.0 to 1.0) */
    val progress: Float
        get() {
            if (timeline.totalDuration == Duration.ZERO) return 0f
            return (currentTime.inWholeMilliseconds.toFloat() /
                timeline.totalDuration.inWholeMilliseconds).coerceIn(0f, 1f)
        }

    /** Progress through current phase (0.0 to 1.0) */
    val phaseProgress: Float
        get() {
            val (phase, localTime) = timeline.getPhaseAtTime(currentTime) ?: return 0f
            if (phase.duration == Duration.ZERO) return 0f
            val raw = (localTime.inWholeMilliseconds.toFloat() /
                phase.duration.inWholeMilliseconds).coerceIn(0f, 1f)
            return phase.easedProgress(raw)
        }

    /** Whether playback is currently active */
    val isPlaying: Boolean get() = playbackState == PlaybackState.PLAYING

    /** Whether timeline has completed */
    val isCompleted: Boolean get() = playbackState == PlaybackState.COMPLETED

    /**
     * Start or resume playback.
     */
    fun play() {
        when (playbackState) {
            PlaybackState.STOPPED -> {
                playbackState = PlaybackState.PLAYING
                emit(TimelineEvent.Started)
                // Trigger first phase start if at beginning
                if (currentTime == Duration.ZERO && phaseStates.isNotEmpty()) {
                    startPhase(0)
                }
            }
            PlaybackState.PAUSED -> {
                playbackState = PlaybackState.PLAYING
                emit(TimelineEvent.Resumed)
            }
            PlaybackState.COMPLETED -> {
                // Restart from beginning
                reset()
                play()
            }
            PlaybackState.PLAYING -> {
                // Already playing
            }
        }
    }

    /**
     * Pause playback.
     */
    fun pause() {
        if (playbackState == PlaybackState.PLAYING) {
            playbackState = PlaybackState.PAUSED
            emit(TimelineEvent.Paused)
        }
    }

    /**
     * Stop playback and reset to beginning.
     */
    fun stop() {
        reset()
    }

    /**
     * Reset to beginning without starting playback.
     */
    fun reset() {
        currentTime = Duration.ZERO
        playbackState = PlaybackState.STOPPED
        phaseStates.forEach { state ->
            state.state = PhaseState.PENDING
            state.localTime = Duration.ZERO
            state.firedKeyframes.clear()
        }
        emit(TimelineEvent.Reset)
    }

    /**
     * Seek to a specific time.
     *
     * @param time Absolute time to seek to
     */
    fun seek(time: Duration) {
        val clampedTime = time.coerceIn(Duration.ZERO, timeline.totalDuration)
        val previousTime = currentTime
        currentTime = clampedTime

        // Update phase states based on new position
        updatePhaseStatesForSeek(previousTime, clampedTime)

        emit(TimelineEvent.Seeked(clampedTime))
    }

    /**
     * Seek to a specific progress (0.0 to 1.0).
     */
    fun seekProgress(progress: Float) {
        val targetTime = (timeline.totalDuration.inWholeMilliseconds * progress.coerceIn(0f, 1f))
            .toLong().milliseconds
        seek(targetTime)
    }

    /**
     * Seek to the start of a phase by name.
     */
    fun seekToPhase(phaseName: String) {
        timeline.getPhaseStartTime(phaseName)?.let { seek(it) }
    }

    /**
     * Seek to the start of a phase by index.
     */
    fun seekToPhase(index: Int) {
        seek(timeline.getPhaseStartTime(index))
    }

    /**
     * Change playback speed.
     *
     * @param multiplier Speed multiplier (1.0 = normal, 2.0 = 2x, 0.5 = half speed)
     */
    fun changeSpeed(multiplier: Float) {
        speed = multiplier.coerceIn(0.1f, 10f)
    }

    /**
     * Update the timeline by a time delta.
     *
     * Call this in your animation/game loop.
     *
     * @param deltaTime Time elapsed since last update
     */
    fun update(deltaTime: Duration) {
        if (playbackState != PlaybackState.PLAYING) return

        val adjustedDelta = (deltaTime.inWholeMilliseconds * speed).toLong().milliseconds
        val previousTime = currentTime
        currentTime += adjustedDelta

        // Check if we've completed
        if (currentTime >= timeline.totalDuration) {
            currentTime = timeline.totalDuration
            completeTimeline()
            return
        }

        // Process phases and keyframes
        processTimeAdvance(previousTime, currentTime)
    }

    /**
     * Update the timeline by a delta in seconds.
     */
    fun update(deltaSeconds: Float) {
        update((deltaSeconds * 1000).toLong().milliseconds)
    }

    /**
     * Add an event listener.
     */
    fun addEventListener(listener: TimelineEventListener) {
        eventListeners.add(listener)
    }

    /**
     * Remove an event listener.
     */
    fun removeEventListener(listener: TimelineEventListener) {
        eventListeners.remove(listener)
    }

    /**
     * Get the current phase state.
     */
    fun getCurrentPhaseState(): PhasePlaybackState? {
        return phaseStates.getOrNull(currentPhaseIndex)
    }

    private fun emit(event: TimelineEvent) {
        eventListeners.forEach { it(event) }
    }

    private fun startPhase(index: Int) {
        val state = phaseStates.getOrNull(index) ?: return
        if (state.state != PhaseState.PENDING) return

        state.state = PhaseState.PLAYING
        state.localTime = Duration.ZERO
        state.phase.onStart?.invoke()
        emit(TimelineEvent.PhaseStarted(state.phase, index))
    }

    private fun completePhase(index: Int) {
        val state = phaseStates.getOrNull(index) ?: return
        if (state.state == PhaseState.COMPLETED) return

        state.state = PhaseState.COMPLETED
        state.localTime = state.phase.duration
        state.phase.onComplete?.invoke()
        emit(TimelineEvent.PhaseCompleted(state.phase, index))
    }

    private fun processTimeAdvance(previousTime: Duration, currentTime: Duration) {
        var accumulated = Duration.ZERO

        for ((index, state) in phaseStates.withIndex()) {
            val phaseStart = accumulated
            val phaseEnd = accumulated + state.phase.duration

            // Check if we entered this phase
            if (previousTime < phaseStart && currentTime >= phaseStart) {
                startPhase(index)
            }

            // If we're in this phase, check keyframes
            if (currentTime >= phaseStart && currentTime < phaseEnd) {
                val previousLocal = (previousTime - phaseStart).coerceAtLeast(Duration.ZERO)
                val currentLocal = currentTime - phaseStart

                state.localTime = currentLocal

                // Fire keyframes in range
                state.phase.keyframes.forEachIndexed { kfIndex, keyframe ->
                    if (kfIndex !in state.firedKeyframes &&
                        keyframe.time > previousLocal &&
                        keyframe.time <= currentLocal
                    ) {
                        keyframe.action()
                        state.firedKeyframes.add(kfIndex)
                        emit(TimelineEvent.KeyframeTriggered(state.phase, keyframe))
                    }
                }
            }

            // Check if we completed this phase
            if (previousTime < phaseEnd && currentTime >= phaseEnd) {
                // Fire any remaining keyframes
                state.phase.keyframes.forEachIndexed { kfIndex, keyframe ->
                    if (kfIndex !in state.firedKeyframes) {
                        keyframe.action()
                        state.firedKeyframes.add(kfIndex)
                        emit(TimelineEvent.KeyframeTriggered(state.phase, keyframe))
                    }
                }
                completePhase(index)
            }

            accumulated = phaseEnd
        }
    }

    private fun updatePhaseStatesForSeek(previousTime: Duration, newTime: Duration) {
        var accumulated = Duration.ZERO

        for ((index, state) in phaseStates.withIndex()) {
            val phaseEnd = accumulated + state.phase.duration

            when {
                // Seeking backwards before this phase
                newTime < accumulated -> {
                    state.state = PhaseState.PENDING
                    state.localTime = Duration.ZERO
                    state.firedKeyframes.clear()
                }
                // Seeking into this phase
                newTime >= accumulated && newTime < phaseEnd -> {
                    state.state = PhaseState.PLAYING
                    state.localTime = newTime - accumulated
                    // Mark keyframes before current position as fired
                    state.firedKeyframes.clear()
                    state.phase.keyframes.forEachIndexed { kfIndex, keyframe ->
                        if (keyframe.time <= state.localTime) {
                            state.firedKeyframes.add(kfIndex)
                        }
                    }
                }
                // Seeking after this phase
                newTime >= phaseEnd -> {
                    state.state = PhaseState.COMPLETED
                    state.localTime = state.phase.duration
                    state.firedKeyframes = state.phase.keyframes.indices.toMutableSet()
                }
            }

            accumulated = phaseEnd
        }
    }

    private fun completeTimeline() {
        // Complete any remaining phases
        phaseStates.forEachIndexed { index, state ->
            if (state.state != PhaseState.COMPLETED) {
                // Fire any remaining keyframes
                state.phase.keyframes.forEachIndexed { kfIndex, keyframe ->
                    if (kfIndex !in state.firedKeyframes) {
                        keyframe.action()
                        state.firedKeyframes.add(kfIndex)
                    }
                }
                completePhase(index)
            }
        }

        playbackState = PlaybackState.COMPLETED
        emit(TimelineEvent.Completed)
    }
}
