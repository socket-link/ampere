package link.socket.ampere.animation.timeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class EasingTest {

    @Test
    fun `linear returns input unchanged`() {
        assertEquals(0f, Easing.linear(0f), 0.001f)
        assertEquals(0.5f, Easing.linear(0.5f), 0.001f)
        assertEquals(1f, Easing.linear(1f), 0.001f)
    }

    @Test
    fun `easeInOut is slow at start and end`() {
        val early = Easing.easeInOut(0.1f)
        val middle = Easing.easeInOut(0.5f)
        val late = Easing.easeInOut(0.9f)

        assertTrue(early < 0.1f, "Should be slower at start")
        assertEquals(0.5f, middle, 0.05f)
        assertTrue(late > 0.9f, "Should be slower at end")
    }

    @Test
    fun `all easing functions have correct boundaries`() {
        val easings = listOf(
            Easing.linear,
            Easing.easeIn,
            Easing.easeOut,
            Easing.easeInOut,
            Easing.easeInCubic,
            Easing.easeOutCubic,
            Easing.easeInOutCubic,
            Easing.easeInQuart,
            Easing.easeOutQuart,
            Easing.easeOutCirc,
            Easing.easeInCirc,
            Easing.easeOutExpo,
            Easing.easeInExpo
        )

        easings.forEach { easing ->
            assertEquals(0f, easing(0f), 0.01f, "Should return 0 at t=0")
            assertEquals(1f, easing(1f), 0.01f, "Should return 1 at t=1")
        }
    }

    @Test
    fun `easeOutElastic overshoots and settles`() {
        val at90 = Easing.easeOutElastic(0.9f)
        assertTrue(at90 > 0.95f || at90 < 1.05f, "Should be near 1 at 90%")
        assertEquals(1f, Easing.easeOutElastic(1f), 0.001f)
    }

    @Test
    fun `easeOutBack overshoots slightly`() {
        val late = Easing.easeOutBack(0.8f)
        assertTrue(late > 0.95f, "Should overshoot past target")
    }

    @Test
    fun `byName returns correct easing function`() {
        assertNotNull(Easing.byName("linear"))
        assertNotNull(Easing.byName("easeIn"))
        assertNotNull(Easing.byName("ease-in-out"))
        assertNull(Easing.byName("nonexistent"))
    }

    @Test
    fun `availableNames contains all easing names`() {
        assertTrue(Easing.availableNames.contains("linear"))
        assertTrue(Easing.availableNames.contains("easeInOut"))
        assertTrue(Easing.availableNames.contains("easeOutElastic"))
        assertTrue(Easing.availableNames.size >= 15)
    }
}

class KeyframeTest {

    @Test
    fun `keyframe stores time and action`() {
        var called = false
        val keyframe = Keyframe(500.milliseconds) { called = true }

        assertEquals(500.milliseconds, keyframe.time)
        keyframe.action()
        assertTrue(called)
    }
}

class PhaseTest {

    @Test
    fun `phase with default values`() {
        val phase = Phase(
            name = "test",
            duration = 2.seconds
        )

        assertEquals("test", phase.name)
        assertEquals(2.seconds, phase.duration)
        assertTrue(phase.keyframes.isEmpty())
        assertNull(phase.onStart)
        assertNull(phase.onComplete)
    }

    @Test
    fun `getKeyframesInRange returns correct keyframes`() {
        val keyframes = listOf(
            Keyframe(100.milliseconds) {},
            Keyframe(200.milliseconds) {},
            Keyframe(500.milliseconds) {},
            Keyframe(800.milliseconds) {}
        )
        val phase = Phase("test", 1.seconds, keyframes)

        val inRange = phase.getKeyframesInRange(100.milliseconds, 500.milliseconds)

        assertEquals(2, inRange.size)
        assertEquals(200.milliseconds, inRange[0].time)
        assertEquals(500.milliseconds, inRange[1].time)
    }

    @Test
    fun `easedProgress applies easing function`() {
        val phase = Phase(
            name = "test",
            duration = 1.seconds,
            easing = Easing.easeIn
        )

        val eased = phase.easedProgress(0.5f)

        assertTrue(eased < 0.5f, "easeIn should be slower than linear at midpoint")
    }
}

class TimelineTest {

    @Test
    fun `timeline calculates total duration`() {
        val timeline = timeline {
            phase("a", 2.seconds)
            phase("b", 3.seconds)
            phase("c", 1.seconds)
        }

        assertEquals(6.seconds, timeline.totalDuration)
    }

    @Test
    fun `timeline has correct phase count`() {
        val timeline = timeline {
            phase("a", 1.seconds)
            phase("b", 1.seconds)
        }

        assertEquals(2, timeline.phaseCount)
    }

    @Test
    fun `getPhase returns phase by name`() {
        val timeline = timeline {
            phase("genesis", 2.seconds)
            phase("awakening", 3.seconds)
        }

        val phase = timeline.getPhase("awakening")

        assertNotNull(phase)
        assertEquals("awakening", phase.name)
        assertEquals(3.seconds, phase.duration)
    }

    @Test
    fun `getPhase returns null for nonexistent phase`() {
        val timeline = timeline {
            phase("a", 1.seconds)
        }

        assertNull(timeline.getPhase("nonexistent"))
    }

    @Test
    fun `getPhaseAtTime returns correct phase and local time`() {
        val timeline = timeline {
            phase("a", 2.seconds)
            phase("b", 3.seconds)
        }

        val (phase, localTime) = timeline.getPhaseAtTime(3500.milliseconds)!!

        assertEquals("b", phase.name)
        assertEquals(1500.milliseconds, localTime)
    }

    @Test
    fun `getPhaseIndexAtTime returns correct index`() {
        val timeline = timeline {
            phase("a", 2.seconds)
            phase("b", 3.seconds)
            phase("c", 1.seconds)
        }

        assertEquals(0, timeline.getPhaseIndexAtTime(1.seconds))
        assertEquals(1, timeline.getPhaseIndexAtTime(3.seconds))
        assertEquals(2, timeline.getPhaseIndexAtTime(5500.milliseconds))
    }

    @Test
    fun `getPhaseStartTime returns correct start time`() {
        val timeline = timeline {
            phase("a", 2.seconds)
            phase("b", 3.seconds)
            phase("c", 1.seconds)
        }

        assertEquals(Duration.ZERO, timeline.getPhaseStartTime(0))
        assertEquals(2.seconds, timeline.getPhaseStartTime(1))
        assertEquals(5.seconds, timeline.getPhaseStartTime(2))

        assertEquals(Duration.ZERO, timeline.getPhaseStartTime("a"))
        assertEquals(2.seconds, timeline.getPhaseStartTime("b"))
        assertEquals(5.seconds, timeline.getPhaseStartTime("c"))
    }
}

class TimelineBuilderTest {

    @Test
    fun `DSL creates timeline with phases`() {
        val timeline = timeline {
            phase("genesis", 2.seconds) {
                at(0.0) { }
                at(1.0) { }
            }
            phase("awakening", 3.seconds) {
                onStart { }
                onComplete { }
            }
        }

        assertEquals(2, timeline.phaseCount)
        assertEquals("genesis", timeline.phases[0].name)
        assertEquals(2, timeline.phases[0].keyframes.size)
        assertEquals("awakening", timeline.phases[1].name)
        assertNotNull(timeline.phases[1].onStart)
        assertNotNull(timeline.phases[1].onComplete)
    }

    @Test
    fun `at with Duration adds keyframe`() {
        val timeline = timeline {
            phase("test", 2.seconds) {
                at(500.milliseconds) { }
            }
        }

        assertEquals(500.milliseconds, timeline.phases[0].keyframes[0].time)
    }

    @Test
    fun `atProgress converts to time correctly`() {
        val timeline = timeline {
            phase("test", 2.seconds) {
                atProgress(0.5f) { }
            }
        }

        assertEquals(1.seconds, timeline.phases[0].keyframes[0].time)
    }

    @Test
    fun `pause adds empty phase`() {
        val timeline = timeline {
            phase("a", 1.seconds)
            pause(500.milliseconds)
            phase("b", 1.seconds)
        }

        assertEquals(3, timeline.phaseCount)
        assertEquals(2500.milliseconds, timeline.totalDuration)
    }

    @Test
    fun `easing can be set by name or function`() {
        val timeline = timeline {
            phase("a", 1.seconds) {
                easing("easeIn")
            }
            phase("b", 1.seconds) {
                easing(Easing.easeOutCubic)
            }
        }

        // Test that easing is applied
        val phaseA = timeline.phases[0]
        val phaseB = timeline.phases[1]

        // easeIn is slower at midpoint (0.5 -> 0.25)
        assertTrue(phaseA.easedProgress(0.5f) < 0.5f, "Phase A easeIn should be slower at midpoint")
        // easeOutCubic is faster at midpoint (0.5 -> ~0.875)
        assertTrue(phaseB.easedProgress(0.5f) > 0.5f, "Phase B easeOut should be faster at midpoint")
    }
}

class TimelineControllerTest {

    @Test
    fun `controller starts in stopped state`() {
        val timeline = timeline { phase("a", 1.seconds) }
        val controller = TimelineController(timeline)

        assertEquals(PlaybackState.STOPPED, controller.playbackState)
        assertFalse(controller.isPlaying)
    }

    @Test
    fun `play starts playback`() {
        val timeline = timeline { phase("a", 1.seconds) }
        val controller = TimelineController(timeline)

        controller.play()

        assertEquals(PlaybackState.PLAYING, controller.playbackState)
        assertTrue(controller.isPlaying)
    }

    @Test
    fun `pause pauses playback`() {
        val timeline = timeline { phase("a", 1.seconds) }
        val controller = TimelineController(timeline)

        controller.play()
        controller.pause()

        assertEquals(PlaybackState.PAUSED, controller.playbackState)
        assertFalse(controller.isPlaying)
    }

    @Test
    fun `update advances time`() {
        val timeline = timeline { phase("a", 2.seconds) }
        val controller = TimelineController(timeline)

        controller.play()
        controller.update(500.milliseconds)

        assertEquals(500.milliseconds, controller.currentTime)
    }

    @Test
    fun `update does not advance when paused`() {
        val timeline = timeline { phase("a", 2.seconds) }
        val controller = TimelineController(timeline)

        controller.play()
        controller.update(500.milliseconds)
        controller.pause()
        controller.update(500.milliseconds)

        assertEquals(500.milliseconds, controller.currentTime)
    }

    @Test
    fun `update respects speed multiplier`() {
        val timeline = timeline { phase("a", 4.seconds) }
        val controller = TimelineController(timeline)

        controller.play()
        controller.changeSpeed(2f)
        controller.update(500.milliseconds)

        assertEquals(1.seconds, controller.currentTime)
    }

    @Test
    fun `update triggers keyframes`() {
        var triggered = false
        val timeline = timeline {
            phase("a", 2.seconds) {
                at(500.milliseconds) { triggered = true }
            }
        }
        val controller = TimelineController(timeline)

        controller.play()
        controller.update(600.milliseconds)

        assertTrue(triggered)
    }

    @Test
    fun `update triggers onStart callback`() {
        var started = false
        val timeline = timeline {
            phase("a", 1.seconds) {
                onStart { started = true }
            }
        }
        val controller = TimelineController(timeline)

        controller.play()

        assertTrue(started)
    }

    @Test
    fun `update triggers onComplete callback`() {
        var completed = false
        val timeline = timeline {
            phase("a", 1.seconds) {
                onComplete { completed = true }
            }
        }
        val controller = TimelineController(timeline)

        controller.play()
        controller.update(1500.milliseconds)

        assertTrue(completed)
    }

    @Test
    fun `progress tracks timeline progress`() {
        val timeline = timeline { phase("a", 2.seconds) }
        val controller = TimelineController(timeline)

        controller.play()
        assertEquals(0f, controller.progress, 0.01f)

        controller.update(1.seconds)
        assertEquals(0.5f, controller.progress, 0.01f)

        controller.update(1.seconds)
        assertEquals(1f, controller.progress, 0.01f)
    }

    @Test
    fun `phaseProgress tracks progress within current phase`() {
        val timeline = timeline {
            phase("a", 2.seconds)
            phase("b", 2.seconds)
        }
        val controller = TimelineController(timeline)

        controller.play()
        controller.update(2500.milliseconds) // 500ms into phase b

        assertEquals("b", controller.currentPhase)
        assertEquals(0.25f, controller.phaseProgress, 0.01f)
    }

    @Test
    fun `seek jumps to time`() {
        val timeline = timeline {
            phase("a", 2.seconds)
            phase("b", 2.seconds)
        }
        val controller = TimelineController(timeline)

        controller.play()
        controller.seek(3.seconds)

        assertEquals(3.seconds, controller.currentTime)
        assertEquals("b", controller.currentPhase)
    }

    @Test
    fun `seekToPhase jumps to phase start`() {
        val timeline = timeline {
            phase("genesis", 2.seconds)
            phase("awakening", 2.seconds)
        }
        val controller = TimelineController(timeline)

        controller.play()
        controller.seekToPhase("awakening")

        assertEquals(2.seconds, controller.currentTime)
        assertEquals("awakening", controller.currentPhase)
    }

    @Test
    fun `reset returns to beginning`() {
        val timeline = timeline { phase("a", 2.seconds) }
        val controller = TimelineController(timeline)

        controller.play()
        controller.update(1.seconds)
        controller.reset()

        assertEquals(Duration.ZERO, controller.currentTime)
        assertEquals(PlaybackState.STOPPED, controller.playbackState)
    }

    @Test
    fun `completes after timeline duration`() {
        val timeline = timeline { phase("a", 1.seconds) }
        val controller = TimelineController(timeline)

        controller.play()
        controller.update(2.seconds)

        assertEquals(PlaybackState.COMPLETED, controller.playbackState)
        assertTrue(controller.isCompleted)
    }

    @Test
    fun `events are emitted correctly`() {
        val events = mutableListOf<TimelineEvent>()
        val timeline = timeline {
            phase("a", 500.milliseconds) {
                at(250.milliseconds) { }
            }
        }
        val controller = TimelineController(timeline)

        controller.addEventListener { events.add(it) }
        controller.play()
        controller.update(300.milliseconds)
        controller.update(300.milliseconds)

        assertTrue(events.any { it is TimelineEvent.Started })
        assertTrue(events.any { it is TimelineEvent.PhaseStarted })
        assertTrue(events.any { it is TimelineEvent.KeyframeTriggered })
        assertTrue(events.any { it is TimelineEvent.PhaseCompleted })
        assertTrue(events.any { it is TimelineEvent.Completed })
    }

    @Test
    fun `keyframes fire in order`() {
        val order = mutableListOf<Int>()
        val timeline = timeline {
            phase("a", 1.seconds) {
                at(100.milliseconds) { order.add(1) }
                at(300.milliseconds) { order.add(2) }
                at(500.milliseconds) { order.add(3) }
            }
        }
        val controller = TimelineController(timeline)

        controller.play()
        controller.update(600.milliseconds)

        assertEquals(listOf(1, 2, 3), order)
    }

    @Test
    fun `seeking backwards resets keyframe state`() {
        var count = 0
        val timeline = timeline {
            phase("a", 1.seconds) {
                at(500.milliseconds) { count++ }
            }
        }
        val controller = TimelineController(timeline)

        controller.play()
        controller.update(600.milliseconds)
        assertEquals(1, count)

        controller.seek(Duration.ZERO)
        controller.update(600.milliseconds)
        assertEquals(2, count)
    }

    @Test
    fun `multiple phases execute in order`() {
        val phases = mutableListOf<String>()
        val timeline = timeline {
            phase("genesis", 500.milliseconds) {
                onStart { phases.add("genesis-start") }
                onComplete { phases.add("genesis-end") }
            }
            phase("awakening", 500.milliseconds) {
                onStart { phases.add("awakening-start") }
                onComplete { phases.add("awakening-end") }
            }
        }
        val controller = TimelineController(timeline)

        controller.play()
        // Use multiple smaller updates to ensure proper ordering
        controller.update(300.milliseconds) // in genesis
        controller.update(300.milliseconds) // crosses to awakening
        controller.update(500.milliseconds) // completes awakening

        assertEquals(
            listOf("genesis-start", "genesis-end", "awakening-start", "awakening-end"),
            phases
        )
    }
}
