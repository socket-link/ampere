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
 * - **RECALL**: Memory retrieval context for surfacing prior knowledge
 * - **OBSERVE**: State-monitoring context for tracking environment changes
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
     *
     * For built-in sparks this is the single phase they belong to. For declarative
     * sparks that span multiple phases, this returns the first eligible phase as a
     * back-compat anchor; prefer [eligiblePhases] when reasoning about applicability.
     */
    abstract val phase: CognitivePhase

    /**
     * The set of phases for which this Spark should be considered eligible by
     * selection algorithms. Defaults to the single [phase] for built-in sparks.
     */
    open val eligiblePhases: Set<CognitivePhase>
        get() = setOf(phase)

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
     * RECALL phase: Memory retrieval and prior-knowledge surfacing.
     *
     * Emphasizes pulling relevant context from memory, past
     * interactions, and learned patterns before forming a plan.
     */
    @Serializable
    @SerialName("PhaseSpark.Recall")
    data object Recall : PhaseSpark() {

        override val phase: CognitivePhase = CognitivePhase.RECALL

        override val name: String = "Phase:Recall"

        override val promptContribution: String = """
## Cognitive Phase: RECALL

You are in the **recall phase** of your cognitive cycle.

### Focus
- Surface relevant prior knowledge, memory, and past interactions
- Identify analogous situations you've handled before
- Retrieve learned patterns, conventions, and project context
- Distinguish what you know from what you only assume

### Approach
- Search memory for similar tasks, past decisions, and prior outcomes
- Cite specific sources when recalling project conventions
- Note where memory may be stale and needs verification
- Bring forward only what is load-bearing for the current task

### Output
Produce a grounded picture of prior knowledge that informs planning.
        """.trimIndent()
    }

    /**
     * OBSERVE phase: State monitoring and change detection.
     *
     * Emphasizes tracking ongoing state, watching for drift, and
     * detecting changes in the environment that demand attention.
     */
    @Serializable
    @SerialName("PhaseSpark.Observe")
    data object Observe : PhaseSpark() {

        override val phase: CognitivePhase = CognitivePhase.OBSERVE

        override val name: String = "Phase:Observe"

        override val promptContribution: String = """
## Cognitive Phase: OBSERVE

You are in the **observation phase** of your cognitive cycle.

### Focus
- Monitor the current state of the environment and the work
- Detect changes, anomalies, and drift from expected behavior
- Track the effects of prior actions
- Distinguish signal from noise

### Approach
- Compare current state against expectations from PERCEIVE and RECALL
- Note what changed, what stayed the same, and what is unexpected
- Quantify deltas where possible; describe them qualitatively otherwise
- Surface anything that should re-trigger planning

### Output
Produce a clear read on current state that grounds the next planning step.
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
            CognitivePhase.RECALL -> Recall
            CognitivePhase.OBSERVE -> Observe
            CognitivePhase.PLAN -> Plan
            CognitivePhase.EXECUTE -> Execute
            CognitivePhase.LEARN -> Learn
        }
    }
}

/**
 * The six canonical cognitive phases of the PROPEL cycle:
 * **P**erceive → **R**ecall → **O**bserve → **P**lan → **E**xecute → **L**earn.
 *
 * Declaration order matches the PROPEL acronym so that
 * `enumValues<CognitivePhase>().toList()` yields the cycle in canonical order.
 */
@Serializable
enum class CognitivePhase {
    PERCEIVE,
    RECALL,
    OBSERVE,
    PLAN,
    EXECUTE,
    LEARN,
}
