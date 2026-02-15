package link.socket.ampere.animation.timeline

import link.socket.ampere.animation.agent.AgentActivityState
import link.socket.ampere.animation.agent.AgentLayer
import link.socket.ampere.animation.agent.AgentVisualState
import link.socket.ampere.animation.agent.CognitivePhase
import link.socket.ampere.animation.flow.FlowLayer
import link.socket.ampere.animation.substrate.Vector2

/**
 * Builds a scripted timeline demonstrating a complete PROPEL cognitive cycle.
 *
 * The timeline walks through observable cognition with two agents:
 * 1. Spark spawns and enters PERCEIVE (sensory gathering)
 * 2. Spark transitions to RECALL (memory activation)
 * 3. Spark enters PLAN, Jazz spawns (strategy + delegation)
 * 4. Jazz enters EXECUTE (committed action)
 * 5. Both enter EVALUATE (reflection)
 * 6. Cycle completes
 *
 * Suitable for recording the demo GIF showing "you can watch AI think."
 */
object CognitiveDemoTimeline {

    /**
     * Build a timeline demonstrating a complete cognitive cycle.
     *
     * @param agents The agent layer to populate during the timeline
     * @param flow The flow layer for inter-agent connections
     * @return A timeline with phases matching the PROPEL cycle
     */
    fun build(
        agents: AgentLayer,
        flow: FlowLayer
    ): Timeline = timeline {
        phase("spawn", 2.0) {
            onStart {
                agents.addAgent(
                    AgentVisualState(
                        id = "spark",
                        name = "Spark",
                        role = "reasoning",
                        position = Vector2(20f, 15f),
                        state = AgentActivityState.SPAWNING,
                    )
                )
            }
            atProgress(0.8f) {
                agents.updateAgentState("spark", AgentActivityState.PROCESSING)
            }
        }

        phase("perceive", 3.0) {
            onStart {
                agents.updateAgentCognitivePhase("spark", CognitivePhase.PERCEIVE)
            }
        }

        phase("recall", 2.0) {
            onStart {
                agents.updateAgentCognitivePhase("spark", CognitivePhase.RECALL)
            }
        }

        phase("plan-and-delegate", 3.0) {
            onStart {
                agents.updateAgentCognitivePhase("spark", CognitivePhase.PLAN)
                agents.addAgent(
                    AgentVisualState(
                        id = "jazz",
                        name = "Jazz",
                        role = "codegen",
                        position = Vector2(60f, 15f),
                        state = AgentActivityState.SPAWNING,
                    )
                )
            }
            at(1.0) {
                agents.updateAgentState("jazz", AgentActivityState.PROCESSING)
                flow.createConnectionsFromAgents(agents, listOf("spark" to "jazz"))
            }
            at(1.5) {
                flow.startHandoff("spark", "jazz")
            }
        }

        phase("execute", 3.0) {
            onStart {
                agents.updateAgentCognitivePhase("spark", CognitivePhase.EXECUTE)
                agents.updateAgentCognitivePhase("jazz", CognitivePhase.EXECUTE)
            }
        }

        phase("evaluate", 2.0) {
            onStart {
                agents.updateAgentCognitivePhase("spark", CognitivePhase.EVALUATE)
                agents.updateAgentCognitivePhase("jazz", CognitivePhase.EVALUATE)
            }
        }

        phase("complete", 1.0) {
            onStart {
                agents.updateAgentCognitivePhase("spark", CognitivePhase.LOOP)
                agents.updateAgentCognitivePhase("jazz", CognitivePhase.LOOP)
                agents.updateAgentState("spark", AgentActivityState.COMPLETE)
                agents.updateAgentState("jazz", AgentActivityState.COMPLETE)
            }
        }
    }

    /** Expected phase names in order. */
    val PHASE_NAMES = listOf(
        "spawn", "perceive", "recall", "plan-and-delegate",
        "execute", "evaluate", "complete"
    )

    /** Total expected duration in seconds. */
    const val TOTAL_DURATION_SECONDS = 16.0
}
