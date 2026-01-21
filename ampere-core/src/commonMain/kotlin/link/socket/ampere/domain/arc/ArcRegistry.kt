package link.socket.ampere.domain.arc

/**
 * Registry of built-in arcs for Ampere.
 */
object ArcRegistry {

    private const val DEFAULT_ARC_NAME = "startup-saas"

    private val builtInArcs: List<ArcConfig> = listOf(
        ArcConfig(
            name = "startup-saas",
            description = "PM -> Code -> QA pipeline for product tickets",
            agents = listOf(
                ArcAgentConfig(role = "pm"),
                ArcAgentConfig(role = "code"),
                ArcAgentConfig(role = "qa"),
            ),
            orchestration = OrchestrationConfig(
                type = OrchestrationType.SEQUENTIAL,
                order = listOf("pm", "code", "qa"),
            ),
        ),
        ArcConfig(
            name = "devops-pipeline",
            description = "Infrastructure deployment and monitoring pipeline",
            agents = listOf(
                ArcAgentConfig(role = "planner"),
                ArcAgentConfig(role = "executor"),
                ArcAgentConfig(role = "monitor"),
            ),
            orchestration = OrchestrationConfig(
                type = OrchestrationType.SEQUENTIAL,
                order = listOf("planner", "executor", "monitor"),
            ),
        ),
        ArcConfig(
            name = "research-paper",
            description = "Academic and technical writing pipeline",
            agents = listOf(
                ArcAgentConfig(role = "scholar"),
                ArcAgentConfig(role = "writer"),
                ArcAgentConfig(role = "critic"),
            ),
            orchestration = OrchestrationConfig(
                type = OrchestrationType.SEQUENTIAL,
                order = listOf("scholar", "writer", "critic"),
            ),
        ),
        ArcConfig(
            name = "data-pipeline",
            description = "Data processing and validation pipeline",
            agents = listOf(
                ArcAgentConfig(role = "analyst"),
                ArcAgentConfig(role = "engineer"),
                ArcAgentConfig(role = "validator"),
            ),
            orchestration = OrchestrationConfig(
                type = OrchestrationType.SEQUENTIAL,
                order = listOf("analyst", "engineer", "validator"),
            ),
        ),
        ArcConfig(
            name = "security-audit",
            description = "Security vulnerability detection and remediation pipeline",
            agents = listOf(
                ArcAgentConfig(role = "scanner"),
                ArcAgentConfig(role = "analyst"),
                ArcAgentConfig(role = "remediator"),
            ),
            orchestration = OrchestrationConfig(
                type = OrchestrationType.SEQUENTIAL,
                order = listOf("scanner", "analyst", "remediator"),
            ),
        ),
        ArcConfig(
            name = "content-creation",
            description = "Content research, writing, and editing pipeline",
            agents = listOf(
                ArcAgentConfig(role = "researcher"),
                ArcAgentConfig(role = "writer"),
                ArcAgentConfig(role = "editor"),
            ),
            orchestration = OrchestrationConfig(
                type = OrchestrationType.SEQUENTIAL,
                order = listOf("researcher", "writer", "editor"),
            ),
        ),
    )

    private val arcsByName = builtInArcs.associateBy { it.name.lowercase() }

    fun get(name: String): ArcConfig? = arcsByName[name.lowercase()]

    fun getDefault(): ArcConfig =
        arcsByName[DEFAULT_ARC_NAME] ?: error("Default arc '$DEFAULT_ARC_NAME' not found")

    fun list(): List<ArcConfig> = builtInArcs.toList()
}
