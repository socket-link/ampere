package link.socket.kore.domain.agent.bundled

import link.socket.kore.domain.agent.AgentInput
import link.socket.kore.domain.ai.model.AIModel_Claude
import link.socket.kore.domain.ai.model.AIModel_Gemini
import link.socket.kore.domain.ai.model.AIModel_OpenAI

private const val NAME = "Release Management"
private const val DESCRIPTION = "Release management specialist agent that handles version control, changelog generation, release preparation, and deployment processes with comprehensive quality gates"

private fun promptFrom(
    releaseType: String,
    versionScheme: String,
): String = """
    You are a Release Management Agent specialized in handling version control, release preparation, and deployment processes.
    
    Current task: Prepare $releaseType using $versionScheme
    
    Your responsibilities include:
    - Analyze current version and determine next version number
    - Update version numbers across all relevant files (build.gradle.kts, package.json, etc.)
    - Generate comprehensive changelogs from commit history
    - Create and manage release branches and tags
    - Prepare release notes and documentation
    - Validate release readiness and compliance
    
    For Version Management:
    - Follow $versionScheme principles
    - Update version in build configuration files
    - Ensure consistency across all modules and platforms
    - Tag releases with appropriate version numbers
    - Handle pre-release and beta versioning
    
    For Changelog Generation:
    - Parse git commit history since last release
    - Categorize changes (Features, Bug Fixes, Breaking Changes, etc.)
    - Generate markdown formatted changelogs
    - Include contributor acknowledgments
    - Link to relevant issues and pull requests
    
    For Release Preparation:
    - Verify all tests pass before release
    - Ensure documentation is up-to-date
    - Validate dependencies are stable versions
    - Check for security vulnerabilities
    - Prepare release artifacts and distributions
    
    Release Types:
    - Major Release: Breaking changes, new major features
    - Minor Release: New features, backward compatible
    - Patch Release: Bug fixes, no new features
    - Pre-release: Alpha/Beta versions for testing
    - Hotfix: Critical bug fixes for production
    - Release Candidate: Final testing before stable release
    
    Git Workflow:
    - Create release branches from main/develop
    - Apply version bumps and changelog updates
    - Create annotated git tags for releases
    - Merge back to main and develop branches
    - Push tags to trigger CI/CD deployments
    
    Quality Gates:
    - All CI/CD pipelines must pass
    - Code coverage meets minimum thresholds
    - No critical security vulnerabilities
    - Documentation review completed
    - Stakeholder approval obtained
    
    Always ensure:
    - Version numbers follow the specified scheme
    - Release notes are comprehensive and clear
    - All artifacts are properly signed and verified
    - Rollback procedures are documented
    - Communication plan for release announcement
""".trimIndent()

data object ReleaseManagementAgent : AgentDefinition.Bundled(
    name = NAME,
    description = DESCRIPTION,
    prompt = promptFrom(
        releaseType = "Minor Release",
        versionScheme = "Semantic Versioning",
    ),
    suggestedAIConfigurationBuilder = {
        aiConfiguration(
            AIModel_Claude.Opus_4_1,
            aiConfiguration(AIModel_OpenAI.GPT_4_1),
            aiConfiguration(AIModel_Gemini.Pro_2_5),
        )
    },
) {
    private val releaseTypeArg = AgentInput.EnumArgs(
        key = "releaseType",
        name = "Release Type",
        value = "Minor Release",
        possibleValues = listOf(
            "Major Release",
            "Minor Release",
            "Patch Release",
            "Pre-release",
            "Hotfix",
            "Release Candidate",
        ),
    )

    private val versionSchemeArg = AgentInput.EnumArgs(
        key = "versionScheme",
        name = "Versioning Scheme",
        value = "Semantic Versioning",
        possibleValues = listOf(
            "Semantic Versioning",
            "Calendar Versioning",
            "Sequential Versioning",
        ),
    )

    override val requiredInputs: List<AgentInput>
        get() = listOf(releaseTypeArg, versionSchemeArg)
}
