package link.socket.ampere.domain.arc

import link.socket.ampere.agents.definition.Agent
import link.socket.ampere.agents.definition.SparkAgentFactory
import link.socket.ampere.agents.definition.SparkBasedAgent
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.cognition.Spark
import link.socket.ampere.agents.domain.cognition.sparks.DefaultSparkCatalog
import link.socket.ampere.agents.domain.cognition.sparks.LanguageSparkIds
import link.socket.ampere.agents.domain.cognition.sparks.ProjectSpark
import link.socket.ampere.agents.domain.cognition.sparks.RoleSparkIds
import link.socket.ampere.agents.domain.cognition.sparks.SparkRegistry
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.util.systemFileSystem
import okio.FileSystem
import okio.Path

data class ChargeResult(
    val projectContext: ProjectContext,
    val goalTree: GoalTree,
    val agents: List<Agent<*>>,
)

data class ProjectContext(
    val projectId: String,
    val description: String,
    val repositoryRoot: Path,
    val architecture: String,
    val conventions: String,
    val techStack: List<String>,
    val sources: List<ProjectContextSource>,
) {
    fun toProjectSpark(): ProjectSpark = ProjectSpark(
        projectId = projectId,
        projectDescription = description,
        repositoryRoot = repositoryRoot.toString(),
        conventions = conventions,
    )
}

data class ProjectContextSource(
    val path: Path,
    val content: String,
)

data class GoalTree(
    val root: GoalNode,
) {
    fun allNodes(): List<GoalNode> {
        val nodes = mutableListOf<GoalNode>()
        fun walk(node: GoalNode) {
            nodes.add(node)
            node.children.forEach { walk(it) }
        }
        walk(root)
        return nodes
    }
}

data class GoalNode(
    val id: String,
    val description: String,
    val children: List<GoalNode> = emptyList(),
)

class ChargePhase(
    private val arcConfig: ArcConfig,
    private val projectDir: Path,
    private val fileSystem: FileSystem = systemFileSystem,
) {
    suspend fun execute(userGoal: String): ChargeResult {
        require(userGoal.isNotBlank()) { "ChargePhase requires a non-empty user goal." }

        val projectContext = ProjectContextExtractor(projectDir, fileSystem).extract()
        require(isValidContext(projectContext)) {
            "ChargePhase produced invalid project context. Sources=${projectContext.sources.map { it.path }}"
        }

        val goalTree = GoalTreeBuilder().build(userGoal)
        require(isValidGoalTree(goalTree)) {
            "ChargePhase produced invalid goal tree for goal: $userGoal"
        }

        val agents = ArcAgentSpawner().spawn(arcConfig, projectContext)

        return ChargeResult(
            projectContext = projectContext,
            goalTree = goalTree,
            agents = agents,
        )
    }

    fun isValidContext(projectContext: ProjectContext): Boolean = projectContext.sources.isNotEmpty() &&
        projectContext.techStack.isNotEmpty() &&
        projectContext.architecture.isNotBlank() &&
        projectContext.conventions.isNotBlank()

    fun isValidGoalTree(goalTree: GoalTree): Boolean =
        goalTree.root.description.isNotBlank() &&
            goalTree.allNodes().all { it.description.isNotBlank() }
}

internal class ProjectContextExtractor(
    private val projectDir: Path,
    private val fileSystem: FileSystem,
) {
    fun extract(): ProjectContext {
        val sources = readSources()
        val sections = sources.flatMap { parseSections(it.content) }

        val projectId = extractProjectId(sources) ?: projectDir.name
        val description = extractDescription(sources)

        val techStack = extractTechStack(sections)
        val conventions = extractConventions(sections)
        val architecture = extractArchitecture(sections)

        return ProjectContext(
            projectId = projectId,
            description = description,
            repositoryRoot = projectDir,
            architecture = architecture,
            conventions = conventions,
            techStack = techStack,
            sources = sources,
        )
    }

    private fun readSources(): List<ProjectContextSource> {
        val candidates = listOf("AGENTS.md", "README.md")
        return candidates.mapNotNull { fileName ->
            val path = projectDir / fileName
            if (!fileSystem.exists(path)) {
                return@mapNotNull null
            }
            val content = fileSystem.read(path) { readUtf8() }
            ProjectContextSource(path, content)
        }
    }

    private fun extractProjectId(sources: List<ProjectContextSource>): String? {
        val readme = sources.firstOrNull { it.path.name.equals("README.md", ignoreCase = true) }
        val lines = readme?.content?.lines().orEmpty()
        return lines.firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")
            ?.trim()
            ?.ifBlank { null }
    }

    private fun extractDescription(sources: List<ProjectContextSource>): String {
        val readme = sources.firstOrNull { it.path.name.equals("README.md", ignoreCase = true) }
        val lines = readme?.content?.lines().orEmpty()
        var inCodeBlock = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                inCodeBlock = !inCodeBlock
                continue
            }
            if (inCodeBlock || trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("[")) {
                continue
            }
            return trimmed
        }
        return "Project at ${projectDir.name}"
    }

    private fun extractTechStack(sections: List<MarkdownSection>): List<String> {
        val techLines = collectLinesByKeywords(
            sections,
            setOf(
                "dependencies",
                "technologies",
                "tech",
                "providers",
                "core",
                "persistence",
                "utilities",
            ),
        )
        val bullets = extractBulletItems(techLines)
        return bullets.distinct()
    }

    private fun extractConventions(sections: List<MarkdownSection>): String {
        val conventionLines = collectLinesByKeywords(
            sections,
            setOf("conventions", "style", "guidelines", "testing", "documentation"),
        )
        return normalizeLines(conventionLines).joinToString("\n").trim()
    }

    private fun extractArchitecture(sections: List<MarkdownSection>): String {
        val architectureLines = collectLinesByKeywords(
            sections,
            setOf("architecture", "structure", "directory"),
        )
        return normalizeLines(architectureLines).joinToString("\n").trim()
    }

    private fun collectLinesByKeywords(
        sections: List<MarkdownSection>,
        keywords: Set<String>,
    ): List<String> {
        val lowered = keywords.map { it.lowercase() }
        return sections
            .filter { section ->
                lowered.any { keyword -> section.title.lowercase().contains(keyword) }
            }
            .flatMap { it.contentLines }
    }

    private fun extractBulletItems(lines: List<String>): List<String> =
        lines.map { it.trim() }
            .filter { it.startsWith("- ") || it.startsWith("* ") || it.matches(Regex("^\\d+\\..*")) }
            .map { it.removePrefix("- ").removePrefix("* ").trim() }
            .filter { it.isNotBlank() }

    private fun normalizeLines(lines: List<String>): List<String> =
        lines.map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("```") }

    private fun parseSections(content: String): List<MarkdownSection> {
        val sections = mutableListOf<MarkdownSection>()
        var currentTitle: String? = null
        var currentLevel = 0
        var currentLines = mutableListOf<String>()

        fun flush() {
            val title = currentTitle ?: return
            sections.add(MarkdownSection(title, currentLevel, currentLines.toList()))
        }

        for (line in content.lines()) {
            val match = Regex("^(#+)\\s+(.*)$").find(line)
            if (match != null) {
                if (currentTitle != null) {
                    flush()
                }
                currentTitle = match.groupValues[2].trim()
                currentLevel = match.groupValues[1].length
                currentLines = mutableListOf()
            } else {
                currentLines.add(line)
            }
        }

        if (currentTitle != null) {
            flush()
        }

        return sections
    }
}

internal data class MarkdownSection(
    val title: String,
    val level: Int,
    val contentLines: List<String>,
)

internal class GoalTreeBuilder {
    fun build(userGoal: String): GoalTree {
        val parts = splitGoal(userGoal)
        val rootId = "goal-0"
        val children = if (parts.size <= 1) {
            emptyList()
        } else {
            parts.mapIndexed { index, part ->
                GoalNode(id = "goal-0-$index", description = part)
            }
        }
        return GoalTree(
            root = GoalNode(
                id = rootId,
                description = userGoal.trim(),
                children = children,
            ),
        )
    }

    private fun splitGoal(goal: String): List<String> {
        val separatorRegex = Regex("\\s+(?:and then|then|and)\\s+|;|\\n")
        val parts = goal.split(separatorRegex).map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.isEmpty()) listOf(goal.trim()) else parts
    }
}

internal class ArcAgentSpawner(
    private val agentFactory: SparkAgentFactory = SparkAgentFactory(),
    private val sparkRegistry: SparkRegistry = DefaultSparkCatalog.registry,
) {
    fun spawn(arcConfig: ArcConfig, projectContext: ProjectContext): List<SparkBasedAgent<CodeState>> {
        val projectSpark = projectContext.toProjectSpark()

        return arcConfig.agents.map { agentConfig ->
            val roleProfile = RoleSparkResolver.resolve(agentConfig.role)
            val roleSpark = sparkRegistry.roleSparkById(roleProfile.sparkId)
                ?: error("Missing bundled role spark '${roleProfile.sparkId}' for role '${agentConfig.role}'")
            val additionalSparkResolver = AdditionalSparkResolver(sparkRegistry)

            val agent = agentFactory.createAgent(
                id = generateUUID("ArcAgent-${agentConfig.role}"),
                affinity = roleProfile.affinity,
            )

            agent.spark<SparkBasedAgent<CodeState>>(projectSpark)
            agent.spark<SparkBasedAgent<CodeState>>(roleSpark)

            agentConfig.sparks
                .map { additionalSparkResolver.resolve(it) }
                .forEach { spark -> agent.spark<SparkBasedAgent<CodeState>>(spark) }

            agent
        }
    }
}

internal data class RoleSparkProfile(
    val sparkId: String,
    val affinity: CognitiveAffinity,
)

internal object RoleSparkResolver {
    private val planning = RoleSparkProfile(RoleSparkIds.PLANNING, CognitiveAffinity.INTEGRATIVE)
    private val code = RoleSparkProfile(RoleSparkIds.CODE, CognitiveAffinity.ANALYTICAL)
    private val operations = RoleSparkProfile(RoleSparkIds.OPERATIONS, CognitiveAffinity.OPERATIONAL)
    private val research = RoleSparkProfile(RoleSparkIds.RESEARCH, CognitiveAffinity.EXPLORATORY)

    private val mapping: Map<String, RoleSparkProfile> = mapOf(
        "pm" to planning,
        "product" to planning,
        "project" to planning,
        "planner" to planning,
        "code" to code,
        "qa" to code,
        "quality" to code,
        "engineer" to code,
        "validator" to code,
        "remediator" to code,
        "executor" to operations,
        "monitor" to operations,
        "scanner" to operations,
        "ops" to operations,
        "scholar" to research,
        "writer" to research,
        "critic" to research,
        "analyst" to research,
        "researcher" to research,
        "editor" to research,
    )

    fun resolve(role: String): RoleSparkProfile =
        mapping[role.lowercase()] ?: research
}

internal class AdditionalSparkResolver(
    private val sparkRegistry: SparkRegistry = DefaultSparkCatalog.registry,
) {
    fun resolve(name: String): Spark {
        val normalized = name.trim()
        if (normalized.isBlank()) {
            return CustomSpark("custom")
        }
        val lower = normalized.lowercase()
        return when {
            lower.contains("kotlin") -> requireLanguageSpark(LanguageSparkIds.KOTLIN)
            lower.contains("java") -> requireLanguageSpark(LanguageSparkIds.JAVA)
            lower.contains("typescript") || lower == "ts" -> requireLanguageSpark(LanguageSparkIds.TYPESCRIPT)
            lower.contains("python") -> requireLanguageSpark(LanguageSparkIds.PYTHON)
            else -> CustomSpark(normalized)
        }
    }

    private fun requireLanguageSpark(id: String): Spark =
        sparkRegistry.languageSparkById(id)
            ?: error("Missing bundled language spark '$id'")
}

internal data class CustomSpark(
    private val label: String,
) : Spark {
    override val name: String = "Custom:$label"

    override val promptContribution: String = """
## Additional Focus: $label

Apply this specialization while working, in addition to your core role.
    """.trimIndent()

    override val allowedTools: Set<String>? = null

    override val fileAccessScope = null
}
