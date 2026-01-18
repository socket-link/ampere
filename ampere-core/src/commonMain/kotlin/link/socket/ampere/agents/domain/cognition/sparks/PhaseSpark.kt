package link.socket.ampere.agents.domain.cognition.sparks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.cognition.FileAccessScope
import link.socket.ampere.agents.domain.cognition.Spark
import link.socket.ampere.agents.domain.cognition.ToolId

/**
 * A Spark that provides phase-specific cognitive guidance during the PROPEL cycle.
 *
 * **Ticket #230**: PhaseSpark provides temporary, focused context that refines
 * the agent's approach during each cognitive phase. When enabled, these Sparks
 * are automatically applied at phase entry and removed at phase exit.
 *
 * Each phase has distinct characteristics:
 * - **PERCEIVE**: Exploratory micro-context for information gathering
 * - **PLAN**: Strategic thinking for task breakdown and sequencing
 * - **EXECUTE**: Focused operational context for task completion
 * - **LEARN**: Integrative thinking for reflection and knowledge extraction
 *
 * PhaseSparks are:
 * - Optional and disabled by default (for backward compatibility)
 * - Transient (applied/removed with phase boundaries)
 * - Non-restrictive (don't narrow tools or file access)
 *
 * @see PhaseSparkManager for lifecycle management
 */
@Serializable
sealed class PhaseSpark : Spark {

    /**
     * The cognitive phase this Spark represents.
     */
    abstract val phase: CognitivePhase

    // PhaseSparks don't restrict tools or file access - they only add context
    override val allowedTools: Set<ToolId>? = null
    override val fileAccessScope: FileAccessScope? = null

    /**
     * PERCEIVE phase: Exploratory information gathering.
     *
     * Emphasizes curiosity, breadth of exploration, and identification
     * of relevant signals in the environment.
     */
    @Serializable
    @SerialName("PhaseSpark.Perceive")
    data object Perceive : PhaseSpark() {

        override val phase: CognitivePhase = CognitivePhase.PERCEIVE

        override val name: String = "Phase:Perceive"

        override val promptContribution: String = """
## Cognitive Phase: PERCEIVE

You are in the **perception phase** of your cognitive cycle.

### Focus
- Gather and analyze information about the current state
- Identify patterns, anomalies, and significant signals
- Build understanding before taking action
- Question assumptions and explore alternatives

### Approach
- Cast a wide net: consider multiple information sources
- Look for what's present AND what's missing
- Note uncertainties and areas requiring clarification
- Generate hypotheses about what you observe

### Output
Produce insights that will inform planning and action.
        """.trimIndent()
    }

    /**
     * PLAN phase: Strategic task decomposition.
     *
     * Emphasizes systematic breakdown, dependency analysis,
     * and sequencing of work.
     */
    @Serializable
    @SerialName("PhaseSpark.Plan")
    data object Plan : PhaseSpark() {

        override val phase: CognitivePhase = CognitivePhase.PLAN

        override val name: String = "Phase:Plan"

        override val promptContribution: String = """
## Cognitive Phase: PLAN

You are in the **planning phase** of your cognitive cycle.

### Focus
- Break down the task into concrete, actionable steps
- Identify dependencies between steps
- Sequence work for optimal execution
- Consider risks and failure modes

### Approach
- Start with the end goal, work backwards
- Make each step small enough to verify
- Include validation checkpoints
- Plan for both success and failure paths

### Output
Produce a clear, executable plan with well-defined steps.
        """.trimIndent()
    }

    /**
     * EXECUTE phase: Focused task completion.
     *
     * Emphasizes action, precision, and completion
     * of planned work.
     */
    @Serializable
    @SerialName("PhaseSpark.Execute")
    data object Execute : PhaseSpark() {

        override val phase: CognitivePhase = CognitivePhase.EXECUTE

        override val name: String = "Phase:Execute"

        override val promptContribution: String = """
## Cognitive Phase: EXECUTE

You are in the **execution phase** of your cognitive cycle.

### Focus
- Complete the current task accurately
- Follow the plan while adapting to realities
- Produce high-quality output
- Handle errors gracefully

### Approach
- Focus on one step at a time
- Verify each step before moving on
- Document what you do and why
- Escalate blockers immediately

### Output
Produce concrete results that advance the plan.
        """.trimIndent()
    }

    /**
     * LEARN phase: Reflective knowledge extraction.
     *
     * Emphasizes synthesis, integration, and capturing
     * insights for future use.
     */
    @Serializable
    @SerialName("PhaseSpark.Learn")
    data object Learn : PhaseSpark() {

        override val phase: CognitivePhase = CognitivePhase.LEARN

        override val name: String = "Phase:Learn"

        override val promptContribution: String = """
## Cognitive Phase: LEARN

You are in the **learning phase** of your cognitive cycle.

### Focus
- Reflect on what happened and why
- Extract reusable knowledge and patterns
- Identify what worked and what didn't
- Update mental models

### Approach
- Compare outcomes to expectations
- Look for root causes, not just symptoms
- Generalize learnings where appropriate
- Note what to do differently next time

### Output
Produce insights and knowledge that improve future performance.
        """.trimIndent()
    }

    companion object {
        /**
         * Gets the PhaseSpark for a given cognitive phase.
         */
        fun forPhase(phase: CognitivePhase): PhaseSpark = when (phase) {
            CognitivePhase.PERCEIVE -> Perceive
            CognitivePhase.PLAN -> Plan
            CognitivePhase.EXECUTE -> Execute
            CognitivePhase.LEARN -> Learn
        }
    }
}

/**
 * Enum representing the four cognitive phases in the PROPEL cycle.
 */
@Serializable
enum class CognitivePhase {
    PERCEIVE,
    PLAN,
    EXECUTE,
    LEARN,
}
