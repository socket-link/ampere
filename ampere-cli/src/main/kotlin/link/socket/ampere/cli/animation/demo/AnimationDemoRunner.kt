package link.socket.ampere.cli.animation.demo

import link.socket.ampere.cli.animation.agent.AgentActivityState
import link.socket.ampere.cli.animation.agent.AgentLayer
import link.socket.ampere.cli.animation.agent.AgentLayoutOrientation
import link.socket.ampere.cli.animation.agent.AgentVisualState
import link.socket.ampere.cli.animation.flow.FlowLayer
import link.socket.ampere.cli.animation.logo.LogoCrystallizer
import link.socket.ampere.cli.animation.particle.ParticleSystem
import link.socket.ampere.cli.animation.render.CompositeRenderer
import link.socket.ampere.cli.animation.substrate.SubstrateState
import link.socket.ampere.cli.animation.substrate.Vector2
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Listener for demo runner events.
 */
typealias DemoEventListener = (DemoRunnerEvent) -> Unit

/**
 * Events emitted by the demo runner.
 */
sealed class DemoRunnerEvent {
    data class Started(val scenario: DemoScenario) : DemoRunnerEvent()
    data class ActionExecuted(val action: DemoAction) : DemoRunnerEvent()
    data class FrameRendered(val frame: Int, val elapsedMs: Long) : DemoRunnerEvent()
    object Completed : DemoRunnerEvent()
}

/**
 * Orchestrates demo playback with all animation components.
 *
 * AnimationDemoRunner drives scripted demos using mock events
 * to showcase Ampere's TUI capabilities without LLM calls.
 *
 * Components:
 * - CompositeRenderer: Terminal output
 * - AgentLayer: Agent node visualization
 * - FlowLayer: Handoff animations
 * - ParticleSystem: Visual effects
 * - LogoCrystallizer: Logo animation
 * - SubstrateState: Background energy grid
 *
 * @property scenario The demo scenario to run
 * @property config Configuration for playback
 */
class AnimationDemoRunner(
    private val scenario: DemoScenario,
    private val config: DemoConfig = DemoConfig()
) {
    // Terminal dimensions
    private var width: Int = 80
    private var height: Int = 24

    // Animation components
    private lateinit var renderer: CompositeRenderer
    private lateinit var substrate: SubstrateState
    private lateinit var particles: ParticleSystem
    private lateinit var agents: AgentLayer
    private lateinit var flow: FlowLayer
    private var logoCrystallizer: LogoCrystallizer? = null

    // Timing
    private var elapsedTime: Duration = Duration.ZERO
    private var nextEventIndex: Int = 0
    private var frameCount: Int = 0
    private var isRunning: Boolean = false
    private var isComplete: Boolean = false

    // Output text accumulator
    private val outputBuffer = StringBuilder()

    // Status message
    private var statusMessage: String = ""

    // Listeners
    private val listeners = mutableListOf<DemoEventListener>()

    /** Whether the demo has completed */
    val completed: Boolean get() = isComplete

    /** Current elapsed time */
    val currentTime: Duration get() = elapsedTime

    /** Current frame count */
    val currentFrame: Int get() = frameCount

    /** Progress through the demo (0.0 to 1.0) */
    val progress: Float get() {
        val total = scenario.totalDuration
        if (total == Duration.ZERO) return 1f
        return (elapsedTime / total).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Initialize the demo runner with terminal dimensions.
     *
     * @param width Terminal width in characters
     * @param height Terminal height in characters
     */
    fun initialize(width: Int = 80, height: Int = 24) {
        this.width = width
        this.height = height

        // Create components
        renderer = CompositeRenderer.forTerminal(
            width = width,
            height = height,
            useColor = config.useColor,
            useUnicode = config.useUnicode
        )

        substrate = SubstrateState.create(width, height, baseDensity = 0.2f)
        particles = ParticleSystem()
        agents = AgentLayer(width, height, AgentLayoutOrientation.HORIZONTAL)
        flow = FlowLayer(width, height)

        // Reset state
        elapsedTime = Duration.ZERO
        nextEventIndex = 0
        frameCount = 0
        isRunning = false
        isComplete = false
        outputBuffer.clear()
        statusMessage = ""
        logoCrystallizer = null
    }

    /**
     * Add an event listener.
     */
    fun addListener(listener: DemoEventListener) {
        listeners.add(listener)
    }

    /**
     * Remove an event listener.
     */
    fun removeListener(listener: DemoEventListener) {
        listeners.remove(listener)
    }

    private fun emit(event: DemoRunnerEvent) {
        listeners.forEach { it(event) }
    }

    /**
     * Start demo playback.
     */
    fun start(): String {
        if (!::renderer.isInitialized) {
            initialize()
        }

        isRunning = true
        emit(DemoRunnerEvent.Started(scenario))

        // Return initial frame
        return buildString {
            append(renderer.hideCursor())
            append(renderer.clearScreen())
        }
    }

    /**
     * Update and render one frame.
     *
     * Call this at your target frame rate (e.g., every 50ms for 20fps).
     *
     * @param deltaMs Real time elapsed since last frame in milliseconds
     * @return ANSI string to output to terminal
     */
    fun updateAndRender(deltaMs: Long): String {
        if (!isRunning || isComplete) return ""

        // Apply speed multiplier to elapsed time
        val adjustedDelta = (deltaMs * config.speed).toLong().milliseconds
        elapsedTime += adjustedDelta

        // Process events that should have fired by now
        processEvents()

        // Update animation components
        val deltaSeconds = adjustedDelta.inWholeMilliseconds / 1000f
        updateAnimations(deltaSeconds)

        // Render frame
        frameCount++
        val frame = renderFrame()
        emit(DemoRunnerEvent.FrameRendered(frameCount, elapsedTime.inWholeMilliseconds))

        return frame
    }

    /**
     * Process events that should fire based on elapsed time.
     */
    private fun processEvents() {
        val events = scenario.events

        while (nextEventIndex < events.size) {
            val event = events[nextEventIndex]

            // Adjust event time by speed
            if (elapsedTime >= event.time) {
                executeAction(event.action)
                emit(DemoRunnerEvent.ActionExecuted(event.action))
                nextEventIndex++
            } else {
                break
            }
        }
    }

    /**
     * Execute a demo action.
     */
    private fun executeAction(action: DemoAction) {
        when (action) {
            is DemoAction.LogoStart -> {
                if (config.showLogo) {
                    logoCrystallizer = LogoCrystallizer.create(width, height, particles)
                }
            }

            is DemoAction.AgentSpawned -> {
                val agent = AgentVisualState(
                    id = action.agentId,
                    name = action.displayName,
                    role = action.role,
                    position = Vector2.ZERO, // Will be positioned by layout
                    state = AgentActivityState.SPAWNING
                )
                agents.addAgent(agent)
            }

            is DemoAction.AgentActivated -> {
                agents.updateAgentState(action.agentId, AgentActivityState.PROCESSING)
            }

            is DemoAction.AgentIdle -> {
                agents.updateAgentState(action.agentId, AgentActivityState.IDLE)
            }

            is DemoAction.AgentComplete -> {
                agents.updateAgentState(action.agentId, AgentActivityState.COMPLETE)
            }

            is DemoAction.AgentStatusChanged -> {
                agents.updateAgentStatus(action.agentId, action.status)
            }

            is DemoAction.TaskCreated -> {
                // Update status of assigned agent
                agents.updateAgentStatus(action.assignedTo, action.description)
            }

            is DemoAction.TaskCompleted -> {
                // No visual change needed
            }

            is DemoAction.HandoffStarted -> {
                // Ensure connection exists
                val source = agents.getAgent(action.fromAgentId)
                val target = agents.getAgent(action.toAgentId)

                if (source != null && target != null) {
                    flow.createConnection(
                        sourceAgentId = action.fromAgentId,
                        targetAgentId = action.toAgentId,
                        sourcePosition = source.position,
                        targetPosition = target.position
                    )
                    flow.startHandoff(action.fromAgentId, action.toAgentId)
                }
            }

            is DemoAction.HandoffCompleted -> {
                // Handoff completes automatically via FlowLayer update
            }

            is DemoAction.OutputChunk -> {
                outputBuffer.append(action.text)
            }

            is DemoAction.SubstratePulse -> {
                // Boost density at pulse point
                val x = (action.x * width).toInt().coerceIn(0, width - 1)
                val y = (action.y * height).toInt().coerceIn(0, height - 1)
                substrate.setDensity(x, y, 1f)
            }

            is DemoAction.ShowStats -> {
                statusMessage = action.summary
            }

            is DemoAction.DemoComplete -> {
                isComplete = true
                isRunning = false
                emit(DemoRunnerEvent.Completed)
            }
        }
    }

    /**
     * Update all animation systems.
     */
    private fun updateAnimations(deltaSeconds: Float) {
        // Update logo crystallizer if active
        logoCrystallizer?.update(deltaSeconds)

        // Update agents
        agents.update(deltaSeconds)

        // Update flow layer
        flow.update(deltaSeconds)

        // Update particles if enabled
        if (config.showParticles) {
            particles.update(deltaSeconds)
        }

        // Update substrate
        if (config.showSubstrate) {
            // Decay substrate density over time
            decaySubstrate(deltaSeconds * 0.5f)
            flow.updateSubstrate(substrate)
        }
    }

    /**
     * Render the current frame.
     */
    private fun renderFrame(): String {
        val statusBar = buildStatusBar()

        return renderer.render(
            substrate = if (config.showSubstrate) substrate else null,
            particles = if (config.showParticles) particles else null,
            agents = agents,
            flow = flow,
            logoCrystallizer = logoCrystallizer,
            statusBar = statusBar
        )
    }

    /**
     * Build the status bar text.
     */
    private fun buildStatusBar(): String {
        val parts = mutableListOf<String>()

        // Progress
        val progressPercent = (progress * 100).toInt()
        parts.add("[$progressPercent%]")

        // Scenario name
        parts.add(scenario.name)

        // Agent count
        if (agents.agentCount > 0) {
            parts.add("${agents.agentCount} agents")
        }

        // Custom status message
        if (statusMessage.isNotEmpty()) {
            parts.add(statusMessage)
        }

        // Speed indicator
        if (config.speed != 1.0f) {
            parts.add("${config.speed}x")
        }

        return parts.joinToString(" · ")
    }

    /**
     * Decay substrate density over time.
     */
    private fun decaySubstrate(rate: Float) {
        val baseDensity = 0.2f
        for (y in 0 until substrate.height) {
            for (x in 0 until substrate.width) {
                val current = substrate.getDensity(x, y)
                if (current > baseDensity) {
                    val decayed = (current - rate).coerceAtLeast(baseDensity)
                    substrate.setDensity(x, y, decayed)
                }
            }
        }
    }

    /**
     * Stop the demo.
     */
    fun stop(): String {
        isRunning = false
        return buildString {
            append(renderer.clearScreen())
            append(renderer.showCursor())
        }
    }

    /**
     * Get the accumulated output text.
     */
    fun getOutput(): String = outputBuffer.toString()

    /**
     * Reset and prepare for another run.
     */
    fun reset() {
        initialize(width, height)
    }

    companion object {
        /**
         * Create a runner for a scenario by name.
         *
         * @param name Scenario name (e.g., "release-notes", "code-review")
         * @param config Playback configuration
         * @return Runner or null if scenario not found
         */
        fun forScenario(name: String, config: DemoConfig = DemoConfig()): AnimationDemoRunner? {
            val scenario = DemoScenario.byName(name) ?: return null
            return AnimationDemoRunner(scenario, config)
        }

        /**
         * Create a runner for the default scenario.
         */
        fun default(config: DemoConfig = DemoConfig()): AnimationDemoRunner {
            return AnimationDemoRunner(DemoScenario.default, config)
        }
    }
}
