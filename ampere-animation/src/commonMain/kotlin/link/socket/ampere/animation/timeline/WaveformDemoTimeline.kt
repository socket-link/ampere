package link.socket.ampere.animation.timeline

import link.socket.ampere.animation.agent.AgentActivityState
import link.socket.ampere.animation.agent.AgentLayer
import link.socket.ampere.animation.agent.AgentVisualState
import link.socket.ampere.animation.agent.CognitivePhase
import link.socket.ampere.animation.emitter.EmitterEffect
import link.socket.ampere.animation.emitter.EmitterManager
import link.socket.ampere.animation.flow.FlowLayer
import link.socket.ampere.animation.math.Vector3
import link.socket.ampere.animation.render.CognitiveColorRamp
import link.socket.ampere.animation.substrate.Vector2

/**
 * Builds a scripted timeline demonstrating the 3D waveform's full visual range.
 *
 * The sequence exercises every rendering capability across eight phases:
 *
 * 1. **Silence** (2s): Empty surface with gentle Perlin undulation, camera orbiting.
 * 2. **Spark Arrives** (2s): First agent spawns with SparkBurst + HeightPulse.
 *    Surface bulges upward. PERCEIVE palette (cool blues).
 * 3. **Memory Activation** (2s): Agent enters RECALL. ColorWash with warm amber
 *    spreading from center. Surface settles into new shape.
 * 4. **Planning** (3s): Agent enters PLAN. Surface shows competing ridges.
 *    Second agent spawns at different depth. PLAN palette (teal, structured).
 * 5. **Delegation** (2s): Flow connection forms between agents. Ridge rises.
 *    SparkBurst at receiving agent.
 * 6. **Execution** (3s): Both agents in EXECUTE. Surface peaks sharply.
 *    EXECUTE palette (red to yellow to white). Dense characters, high energy.
 * 7. **Completion** (2s): Task completes. Confetti at both agents.
 *    Surface gradually settles.
 * 8. **Reflection** (2s): EVALUATE phase. EVALUATE palette (purple, diffuse).
 *    Surface returns to gentle undulation. Camera continues orbiting.
 *
 * Suitable for recording the demo GIF showing the 3D cognitive waveform in action.
 */
object WaveformDemoTimeline {

    /** World-space position for Spark (center of the waveform surface). */
    private val SPARK_WORLD_POS = Vector2(10f, 7.5f)

    /** World-space position for Jazz (right-forward, different depth). */
    private val JAZZ_WORLD_POS = Vector2(14f, 5f)

    /** 3D position for Spark (centered, slight elevation). */
    private val SPARK_3D = Vector3(SPARK_WORLD_POS.x, 0f, SPARK_WORLD_POS.y)

    /** 3D position for Jazz (offset, different depth plane). */
    private val JAZZ_3D = Vector3(JAZZ_WORLD_POS.x, 0f, JAZZ_WORLD_POS.y)

    /**
     * Build the waveform demo timeline.
     *
     * @param agents The agent layer to populate during the timeline
     * @param flow The flow layer for inter-agent connections
     * @param emitters The emitter manager for firing visual effects
     * @return A timeline with 8 phases totaling 18 seconds
     */
    fun build(
        agents: AgentLayer,
        flow: FlowLayer,
        emitters: EmitterManager
    ): Timeline = timeline {

        // Phase 1: Silence — empty surface, gentle Perlin undulation, camera orbiting
        phase("silence", 2.0) {
            // No agents, no effects. The waveform shows only substrate noise
            // and the camera orbit provides the dimensionality cue.
        }

        // Phase 2: Spark Arrives — first agent spawns with visual burst
        phase("spark-arrives", 2.0) {
            onStart {
                agents.addAgent(
                    AgentVisualState(
                        id = "spark",
                        name = "Spark",
                        role = "reasoning",
                        position = SPARK_WORLD_POS,
                        position3D = SPARK_3D,
                        state = AgentActivityState.SPAWNING,
                    )
                )
                // SparkBurst: concentric rings expanding outward
                emitters.emit(EmitterEffect.SparkBurst(), SPARK_3D)
                // HeightPulse: surface bulges upward at agent center
                emitters.emit(EmitterEffect.HeightPulse(), SPARK_3D)
            }
            atProgress(0.5f) {
                agents.updateAgentState("spark", AgentActivityState.PROCESSING)
                agents.updateAgentCognitivePhase("spark", CognitivePhase.PERCEIVE)
            }
            atProgress(0.8f) {
                // ColorWash with PERCEIVE palette (cool blues)
                emitters.emit(
                    EmitterEffect.ColorWash(colorRamp = CognitiveColorRamp.PERCEIVE),
                    SPARK_3D
                )
            }
        }

        // Phase 3: Memory Activation — RECALL phase with warm amber wash
        phase("memory-activation", 2.0) {
            onStart {
                agents.updateAgentCognitivePhase("spark", CognitivePhase.RECALL)
                // ColorWash with RECALL palette (warm amber spreading from center)
                emitters.emit(
                    EmitterEffect.ColorWash(
                        duration = 1.8f,
                        radius = 10f,
                        colorRamp = CognitiveColorRamp.RECALL,
                        waveFrontSpeed = 5f
                    ),
                    SPARK_3D
                )
            }
            atProgress(0.6f) {
                // Subtle HeightPulse as memory settles into shape
                emitters.emit(
                    EmitterEffect.HeightPulse(
                        duration = 1.0f,
                        radius = 5f,
                        maxHeightBoost = 2f
                    ),
                    SPARK_3D
                )
            }
        }

        // Phase 4: Planning — competing ridges, second agent spawns
        phase("planning", 3.0) {
            onStart {
                agents.updateAgentCognitivePhase("spark", CognitivePhase.PLAN)
                // ColorWash with PLAN palette (teal, structured)
                emitters.emit(
                    EmitterEffect.ColorWash(colorRamp = CognitiveColorRamp.PLAN),
                    SPARK_3D
                )
            }
            at(1.0) {
                // Second agent spawns at different depth
                agents.addAgent(
                    AgentVisualState(
                        id = "jazz",
                        name = "Jazz",
                        role = "codegen",
                        position = JAZZ_WORLD_POS,
                        position3D = JAZZ_3D,
                        state = AgentActivityState.SPAWNING,
                    )
                )
                emitters.emit(EmitterEffect.SparkBurst(), JAZZ_3D)
                emitters.emit(EmitterEffect.HeightPulse(), JAZZ_3D)
            }
            at(2.0) {
                agents.updateAgentState("jazz", AgentActivityState.PROCESSING)
                agents.updateAgentCognitivePhase("jazz", CognitivePhase.PLAN)
            }
        }

        // Phase 5: Delegation — flow connection, ridge between agents
        phase("delegation", 2.0) {
            onStart {
                // Create connection and start handoff
                flow.createConnectionsFromAgents(agents, listOf("spark" to "jazz"))
                flow.startHandoff("spark", "jazz")
            }
            atProgress(0.5f) {
                // SparkBurst at receiving agent when delegation arrives
                emitters.emit(
                    EmitterEffect.SparkBurst(
                        duration = 1.0f,
                        radius = 4f,
                        expansionSpeed = 6f
                    ),
                    JAZZ_3D
                )
            }
        }

        // Phase 6: Execution — both agents active, high energy, dense characters
        phase("execution", 3.0) {
            onStart {
                agents.updateAgentCognitivePhase("spark", CognitivePhase.EXECUTE)
                agents.updateAgentCognitivePhase("jazz", CognitivePhase.EXECUTE)
                // ColorWash with EXECUTE palette (red->yellow->white)
                emitters.emit(
                    EmitterEffect.ColorWash(
                        duration = 2.0f,
                        radius = 12f,
                        colorRamp = CognitiveColorRamp.EXECUTE,
                        waveFrontSpeed = 8f
                    ),
                    SPARK_3D
                )
            }
            at(0.5) {
                // HeightPulse at both agents — surface peaks sharply
                emitters.emit(
                    EmitterEffect.HeightPulse(
                        duration = 2.0f,
                        radius = 5f,
                        maxHeightBoost = 4f,
                        riseSpeed = 6f
                    ),
                    SPARK_3D
                )
                emitters.emit(
                    EmitterEffect.HeightPulse(
                        duration = 2.0f,
                        radius = 5f,
                        maxHeightBoost = 4f,
                        riseSpeed = 6f
                    ),
                    JAZZ_3D
                )
            }
            at(2.0) {
                // Turbulence during peak execution for visual intensity
                emitters.emit(
                    EmitterEffect.Turbulence(
                        duration = 1.5f,
                        radius = 8f,
                        noiseAmplitude = 1.0f
                    ),
                    Vector3(
                        (SPARK_3D.x + JAZZ_3D.x) / 2f,
                        0f,
                        (SPARK_3D.z + JAZZ_3D.z) / 2f
                    )
                )
            }
        }

        // Phase 7: Completion — confetti, surface settles
        phase("completion", 2.0) {
            onStart {
                agents.updateAgentCognitivePhase("spark", CognitivePhase.LOOP)
                agents.updateAgentCognitivePhase("jazz", CognitivePhase.LOOP)
                agents.updateAgentState("spark", AgentActivityState.COMPLETE)
                agents.updateAgentState("jazz", AgentActivityState.COMPLETE)
                // Confetti at both agents
                emitters.emit(EmitterEffect.Confetti(duration = 1.5f, radius = 4f), SPARK_3D)
                emitters.emit(EmitterEffect.Confetti(duration = 1.5f, radius = 4f), JAZZ_3D)
            }
        }

        // Phase 8: Reflection — EVALUATE palette, surface returns to gentle undulation
        phase("reflection", 2.0) {
            onStart {
                agents.updateAgentCognitivePhase("spark", CognitivePhase.EVALUATE)
                agents.updateAgentCognitivePhase("jazz", CognitivePhase.EVALUATE)
                agents.updateAgentState("spark", AgentActivityState.IDLE)
                agents.updateAgentState("jazz", AgentActivityState.IDLE)
                // ColorWash with EVALUATE palette (purple, diffuse)
                emitters.emit(
                    EmitterEffect.ColorWash(
                        duration = 2.0f,
                        radius = 12f,
                        colorRamp = CognitiveColorRamp.EVALUATE,
                        waveFrontSpeed = 4f
                    ),
                    Vector3(
                        (SPARK_3D.x + JAZZ_3D.x) / 2f,
                        0f,
                        (SPARK_3D.z + JAZZ_3D.z) / 2f
                    )
                )
            }
        }
    }

    /** Expected phase names in order. */
    val PHASE_NAMES = listOf(
        "silence", "spark-arrives", "memory-activation", "planning",
        "delegation", "execution", "completion", "reflection"
    )

    /** Total expected duration in seconds. */
    const val TOTAL_DURATION_SECONDS = 18.0
}
