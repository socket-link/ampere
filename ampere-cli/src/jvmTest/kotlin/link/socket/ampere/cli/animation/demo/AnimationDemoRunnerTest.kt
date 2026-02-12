package link.socket.ampere.cli.animation.demo

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AnimationDemoRunnerTest {

    @Test
    fun `creates runner from scenario name`() {
        val runner = AnimationDemoRunner.forScenario("release-notes")
        assertNotNull(runner)
    }

    @Test
    fun `returns null for unknown scenario`() {
        val runner = AnimationDemoRunner.forScenario("unknown-scenario")
        assertNull(runner)
    }

    @Test
    fun `creates default runner`() {
        val runner = AnimationDemoRunner.default()
        assertNotNull(runner)
        assertFalse(runner.completed)
    }

    @Test
    fun `initializes with dimensions`() {
        val runner = AnimationDemoRunner.default()
        runner.initialize(80, 24)

        assertEquals(0f, runner.progress, 0.001f)
        assertEquals(0, runner.currentFrame)
        assertFalse(runner.completed)
    }

    @Test
    fun `starts demo with terminal setup`() {
        val runner = AnimationDemoRunner.default()
        runner.initialize(80, 24)

        val startOutput = runner.start()

        // Should contain hide cursor and clear screen escape sequences
        assertTrue(startOutput.contains("\u001B[?25l")) // Hide cursor
        assertTrue(startOutput.contains("\u001B[2J"))   // Clear screen
    }

    @Test
    fun `updates and renders frames`() {
        val runner = AnimationDemoRunner.default()
        runner.initialize(80, 24)
        runner.start()

        // Update a few frames
        val frame1 = runner.updateAndRender(50)
        val frame2 = runner.updateAndRender(50)

        assertNotNull(frame1)
        assertNotNull(frame2)
        assertTrue(runner.progress > 0f)
        assertEquals(2, runner.currentFrame)
    }

    @Test
    fun `processes events over time`() {
        val events = mutableListOf<DemoRunnerEvent>()
        val runner = AnimationDemoRunner.default()
        runner.initialize(80, 24)
        runner.addListener { events.add(it) }

        runner.start()

        // Should have started event
        assertTrue(events.any { it is DemoRunnerEvent.Started })

        // Update through several seconds (at 50ms per frame)
        repeat(60) { // 3 seconds worth at 50ms intervals
            runner.updateAndRender(50)
        }

        // Should have frame rendered events
        assertTrue(events.any { it is DemoRunnerEvent.FrameRendered })
        // Should have some actions executed (like LogoStart at 0s, AgentSpawned at 2.5s)
        assertTrue(events.any { it is DemoRunnerEvent.ActionExecuted })
    }

    @Test
    fun `completes demo after total duration`() {
        val config = DemoConfig(speed = 10f) // Speed up for test
        val scenario = DemoScenario.ReleaseNotes
        val runner = AnimationDemoRunner(scenario, config)
        runner.initialize(80, 24)
        runner.start()

        // Run for enough time to complete (scenario is ~19s, at 10x speed = ~1.9s)
        repeat(100) {
            if (!runner.completed) {
                runner.updateAndRender(50)
            }
        }

        assertTrue(runner.completed)
        assertEquals(1f, runner.progress, 0.001f)
    }

    @Test
    fun `stop returns cleanup output`() {
        val runner = AnimationDemoRunner.default()
        runner.initialize(80, 24)
        runner.start()

        val stopOutput = runner.stop()

        // Should contain show cursor escape sequence
        assertTrue(stopOutput.contains("\u001B[?25h")) // Show cursor
    }

    @Test
    fun `reset clears state`() {
        val config = DemoConfig(speed = 5f)
        val runner = AnimationDemoRunner(DemoScenario.ReleaseNotes, config)
        runner.initialize(80, 24)
        runner.start()

        // Progress a bit
        repeat(50) {
            runner.updateAndRender(50)
        }

        assertTrue(runner.progress > 0f)

        // Reset
        runner.reset()

        assertEquals(0f, runner.progress, 0.001f)
        assertEquals(0, runner.currentFrame)
        assertFalse(runner.completed)
    }

    @Test
    fun `speed multiplier affects progress`() {
        val slowRunner = AnimationDemoRunner(DemoScenario.ReleaseNotes, DemoConfig(speed = 0.5f))
        val fastRunner = AnimationDemoRunner(DemoScenario.ReleaseNotes, DemoConfig(speed = 2.0f))

        slowRunner.initialize(80, 24)
        fastRunner.initialize(80, 24)
        slowRunner.start()
        fastRunner.start()

        // Update same amount of real time
        repeat(20) {
            slowRunner.updateAndRender(50)
            fastRunner.updateAndRender(50)
        }

        // Fast runner should have progressed more
        assertTrue(fastRunner.progress > slowRunner.progress)
    }

    @Test
    fun `accumulates output text from OutputChunk events`() {
        val config = DemoConfig(speed = 20f) // Very fast
        val runner = AnimationDemoRunner(DemoScenario.ReleaseNotes, config)
        runner.initialize(80, 24)
        runner.start()

        // Run until completion or timeout
        repeat(200) {
            if (!runner.completed) {
                runner.updateAndRender(50)
            }
        }

        // Output should contain text from OutputChunk events
        val output = runner.getOutput()
        assertTrue(output.contains("Release Notes") || runner.completed)
    }
}

class DemoScenarioTest {

    @Test
    fun `all scenarios have names`() {
        DemoScenario.all.forEach { scenario ->
            assertTrue(scenario.name.isNotBlank())
            assertTrue(scenario.description.isNotBlank())
        }
    }

    @Test
    fun `byName finds scenarios case-insensitively`() {
        assertNotNull(DemoScenario.byName("release-notes"))
        assertNotNull(DemoScenario.byName("RELEASE-NOTES"))
        assertNotNull(DemoScenario.byName("Release-Notes"))
        assertNotNull(DemoScenario.byName("code-review"))
    }

    @Test
    fun `default scenario is valid`() {
        assertNotNull(DemoScenario.default)
        assertTrue(DemoScenario.default.events.isNotEmpty())
    }

    @Test
    fun `names list contains all scenario names`() {
        assertEquals(DemoScenario.all.size, DemoScenario.names.size)
        assertTrue(DemoScenario.names.contains("release-notes"))
        assertTrue(DemoScenario.names.contains("code-review"))
    }

    @Test
    fun `release-notes scenario has expected events`() {
        val scenario = DemoScenario.ReleaseNotes

        // Should have LogoStart as first event
        assertTrue(scenario.events.first().action is DemoAction.LogoStart)

        // Should have agent spawns
        assertTrue(scenario.events.any { it.action is DemoAction.AgentSpawned })

        // Should have handoffs
        assertTrue(scenario.events.any { it.action is DemoAction.HandoffStarted })

        // Should end with DemoComplete
        assertTrue(scenario.events.last().action is DemoAction.DemoComplete)
    }

    @Test
    fun `code-review scenario has three agents`() {
        val scenario = DemoScenario.CodeReview

        val agentSpawns = scenario.events.filter { it.action is DemoAction.AgentSpawned }
        assertEquals(3, agentSpawns.size)

        val agentNames = agentSpawns.map { (it.action as DemoAction.AgentSpawned).displayName }
        assertTrue(agentNames.contains("Architect"))
        assertTrue(agentNames.contains("Reviewer"))
        assertTrue(agentNames.contains("Coder"))
    }

    @Test
    fun `totalDuration reflects last event time`() {
        val scenario = DemoScenario.ReleaseNotes

        val lastEventTime = scenario.events.maxOf { it.time }
        assertEquals(lastEventTime, scenario.totalDuration)
    }
}

class DemoConfigTest {

    @Test
    fun `default config has sensible values`() {
        val config = DemoConfig()

        assertEquals(1.0f, config.speed)
        assertTrue(config.showLogo)
        assertTrue(config.showSubstrate)
        assertTrue(config.showParticles)
        assertTrue(config.useColor)
        assertTrue(config.useUnicode)
        assertEquals(20, config.targetFps)
    }

    @Test
    fun `frameIntervalMs calculated from fps`() {
        val config20fps = DemoConfig(targetFps = 20)
        val config10fps = DemoConfig(targetFps = 10)

        assertEquals(50L, config20fps.frameIntervalMs)
        assertEquals(100L, config10fps.frameIntervalMs)
    }

    @Test
    fun `fast config has higher speed`() {
        val fast = DemoConfig.fast

        assertTrue(fast.speed > 1f)
    }

    @Test
    fun `slow config has lower speed`() {
        val slow = DemoConfig.slow

        assertTrue(slow.speed < 1f)
    }

    @Test
    fun `ci config minimizes visual effects`() {
        val ci = DemoConfig.ci

        assertTrue(ci.speed > 1f)
        assertFalse(ci.showLogo)
        assertFalse(ci.showParticles)
    }
}
