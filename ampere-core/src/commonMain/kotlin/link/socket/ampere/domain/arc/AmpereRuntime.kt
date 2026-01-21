package link.socket.ampere.domain.arc

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Result of a complete Arc lifecycle execution.
 */
data class ArcExecutionResult(
    val chargeResult: ChargeResult,
    val flowResult: FlowResult,
    val pulseResult: PulseResult,
) {
    val success: Boolean get() = pulseResult.success
}

/**
 * Runtime for executing Arc workflows through the Charge → Flow → Pulse lifecycle.
 *
 * The runtime orchestrates the three phases:
 * - **Charge**: Project analysis, goal decomposition, agent spawning
 * - **Flow**: Agent execution loop (perceive → remember → plan → execute)
 * - **Pulse**: Evaluation, learning capture, and delivery
 *
 * Example usage:
 * ```kotlin
 * val runtime = AmpereRuntime(
 *     arcConfig = ArcRegistry.get("startup-saas")!!,
 *     projectDir = Path("/path/to/project"),
 * )
 * val result = runtime.execute("Implement user authentication")
 * ```
 */
class AmpereRuntime(
    private val arcConfig: ArcConfig,
    private val projectDir: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val maxFlowTicks: Int = 100,
) {
    private var chargeResult: ChargeResult? = null
    private var flowResult: FlowResult? = null
    private var isRunning = false

    /**
     * Execute the full Arc lifecycle for a given goal.
     *
     * @param userGoal The goal to accomplish
     * @return ArcExecutionResult containing results from all three phases
     * @throws IllegalStateException if already running
     * @throws IllegalArgumentException if goal is blank
     */
    suspend fun execute(userGoal: String): ArcExecutionResult {
        require(!isRunning) { "Runtime is already executing" }
        require(userGoal.isNotBlank()) { "User goal cannot be blank" }

        isRunning = true

        try {
            // Phase 1: Charge - Initialize project context and spawn agents
            val charge = executeCharge(userGoal)
            chargeResult = charge

            // Phase 2: Flow - Execute agent loop
            val flow = executeFlow(charge)
            flowResult = flow

            // Phase 3: Pulse - Evaluate and capture learnings
            val pulse = executePulse(charge, flow)

            return ArcExecutionResult(
                chargeResult = charge,
                flowResult = flow,
                pulseResult = pulse,
            )
        } finally {
            isRunning = false
        }
    }

    /**
     * Execute only the Charge phase.
     * Useful for testing or when you need to inspect the project context before proceeding.
     */
    suspend fun executeChargeOnly(userGoal: String): ChargeResult {
        require(userGoal.isNotBlank()) { "User goal cannot be blank" }

        val chargePhase = ChargePhase(
            arcConfig = arcConfig,
            projectDir = projectDir,
            fileSystem = fileSystem,
        )
        return chargePhase.execute(userGoal)
    }

    private suspend fun executeCharge(userGoal: String): ChargeResult {
        val chargePhase = ChargePhase(
            arcConfig = arcConfig,
            projectDir = projectDir,
            fileSystem = fileSystem,
        )
        return chargePhase.execute(userGoal)
    }

    private suspend fun executeFlow(chargeResult: ChargeResult): FlowResult {
        val flowPhase = FlowPhase(
            arcConfig = arcConfig,
            agents = chargeResult.agents,
            goalTree = chargeResult.goalTree,
            maxTicks = maxFlowTicks,
        )
        return flowPhase.execute()
    }

    private suspend fun executePulse(chargeResult: ChargeResult, flowResult: FlowResult): PulseResult {
        val pulsePhase = PulsePhase(
            arcConfig = arcConfig,
            flowResult = flowResult,
            projectContext = chargeResult.projectContext,
            goalTree = chargeResult.goalTree,
        )
        return pulsePhase.execute()
    }

    /**
     * Stop a running execution.
     * This will stop at the next safe point (between phases or between ticks).
     */
    fun stop() {
        isRunning = false
    }

    /**
     * Check if the runtime is currently executing.
     */
    fun isRunning(): Boolean = isRunning

    /**
     * Get the current Arc configuration.
     */
    fun getArcConfig(): ArcConfig = arcConfig

    companion object {
        /**
         * Create a runtime from an Arc configuration and a project directory path string.
         *
         * @param arcConfig The Arc configuration to use
         * @param projectDirPath The project directory as a string path
         * @param maxFlowTicks Maximum ticks for the flow phase
         * @return AmpereRuntime configured with the specified Arc
         */
        fun create(
            arcConfig: ArcConfig,
            projectDirPath: String,
            maxFlowTicks: Int = 100,
        ): AmpereRuntime {
            return AmpereRuntime(
                arcConfig = arcConfig,
                projectDir = projectDirPath.toPath(),
                maxFlowTicks = maxFlowTicks,
            )
        }

        /**
         * Create a runtime from team configuration for backward compatibility.
         *
         * This converts existing `ampere.yaml` team config to the Arc system:
         * ```yaml
         * team:
         *   - role: product-manager
         *   - role: engineer
         *   - role: qa-tester
         * ```
         *
         * Maps to `startup-saas` arc with the specified roles.
         *
         * @param teamRoles List of role names from team config
         * @param projectDir Project directory path
         * @param fileSystem File system to use
         * @return AmpereRuntime configured with equivalent Arc config
         */
        fun fromTeamConfig(
            teamRoles: List<String>,
            projectDir: Path,
            fileSystem: FileSystem = FileSystem.SYSTEM,
        ): AmpereRuntime {
            val arcConfig = teamConfigToArcConfig(teamRoles)
            return AmpereRuntime(
                arcConfig = arcConfig,
                projectDir = projectDir,
                fileSystem = fileSystem,
            )
        }

        /**
         * Convert team configuration roles to an equivalent ArcConfig.
         *
         * Role name mappings:
         * - product-manager, pm → pm
         * - engineer, developer, dev → code
         * - qa-tester, qa, tester → qa
         * - architect → planner
         * - security-reviewer → scanner
         * - technical-writer → writer
         */
        fun teamConfigToArcConfig(teamRoles: List<String>): ArcConfig {
            val agents = teamRoles.map { role ->
                val normalizedRole = normalizeTeamRole(role)
                ArcAgentConfig(role = normalizedRole)
            }

            return ArcConfig(
                name = "team-config",
                description = "Arc generated from team configuration",
                agents = agents,
                orchestration = OrchestrationConfig(
                    type = OrchestrationType.SEQUENTIAL,
                    order = agents.map { it.role },
                ),
            )
        }

        private fun normalizeTeamRole(role: String): String {
            val lower = role.lowercase().replace("-", "").replace("_", "")
            return when {
                lower in setOf("productmanager", "pm", "product") -> "pm"
                lower in setOf("engineer", "developer", "dev", "coder") -> "code"
                lower in setOf("qatester", "qa", "tester", "quality") -> "qa"
                lower in setOf("architect", "planner") -> "planner"
                lower in setOf("securityreviewer", "security") -> "scanner"
                lower in setOf("technicalwriter", "writer", "docs") -> "writer"
                lower in setOf("analyst", "dataanalyst") -> "analyst"
                lower in setOf("monitor", "monitoring") -> "monitor"
                else -> role.lowercase().replace("-", "")
            }
        }
    }
}
