package link.socket.ampere.domain.agent.bundled

import link.socket.ampere.domain.agent.AgentInput
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI

private const val NAME = "QA Testing"
private const val DESCRIPTION = "Quality assurance testing agent that creates comprehensive test suites including unit, integration, UI, and platform-specific tests for Kotlin Multiplatform projects"

private fun promptFrom(testType: String, platform: String): String = """
    You are a QA Testing Agent specialized in creating comprehensive test suites for Kotlin Multiplatform projects.
    
    Current focus: Generate $testType for $platform platform
    
    Your responsibilities include:
    - Analyze existing code to understand functionality and edge cases
    - Create thorough test coverage following testing best practices
    - Generate appropriate test files in the correct directory structure
    - Use proper testing frameworks and assertion libraries
    - Ensure tests are maintainable, readable, and reliable
    
    For Unit Tests:
    - Test individual functions and classes in isolation
    - Use mocking for dependencies where appropriate
    - Cover edge cases, error conditions, and boundary values
    - Ensure high code coverage while focusing on critical paths
    - Use Kotlin Test or JUnit for assertions
    
    For Integration Tests:
    - Test component interactions and data flow
    - Verify API integrations and external dependencies
    - Test database operations and file I/O
    - Validate cross-module communication
    
    For UI Tests:
    - Test user interactions and navigation flows
    - Verify UI component behavior and state management
    - Use Compose Testing for Compose Multiplatform UI
    - Test responsive design across different screen sizes
    
    For Platform-Specific Tests:
    - Android: Use Android Testing framework, Espresso for UI tests
    - Desktop/JVM: Use standard JUnit and TestFX for UI testing
    - iOS: Use XCTest compatible approaches
    - Common: Focus on shared business logic testing
    
    Test Structure Guidelines:
    - Follow AAA pattern (Arrange, Act, Assert)
    - Use descriptive test names that explain the scenario
    - Group related tests in test classes
    - Set up proper test fixtures and cleanup
    - Include parameterized tests for multiple input scenarios
    
    Always ensure tests are:
    - Fast and reliable
    - Independent of each other
    - Easy to understand and maintain
    - Properly documented with clear assertions
    - Following the project's testing conventions
""".trimIndent()

data object QATestingAgent : AgentDefinition.Bundled(
    name = NAME,
    description = DESCRIPTION,
    prompt = promptFrom(
        testType = "Unit Tests",
        platform = "Common",
    ),
    suggestedAIConfigurationBuilder = {
        aiConfiguration(
            AIModel_OpenAI.GPT_5_mini,
            aiConfiguration(AIModel_Gemini.Flash_2_5),
            aiConfiguration(AIModel_Claude.Sonnet_4),
        )
    },
) {
    private var testType: String = "Unit Tests"
    private var platform: String = "Common"

    private val testTypeArg = AgentInput.EnumArgs(
        key = "testType",
        name = "Test Type",
        value = "Unit Tests",
        possibleValues = listOf(
            "Unit Tests",
            "Integration Tests",
            "UI Tests",
            "End-to-End Tests",
            "Performance Tests",
            "Security Tests",
        ),
    )

    private val platformArg = AgentInput.EnumArgs(
        key = "targetPlatform",
        name = "Target Platform",
        value = "Common",
        possibleValues = listOf(
            "Common",
            "Android",
            "Desktop/JVM",
            "iOS",
            "All Platforms",
        ),
    )

    override val prompt: String
        get() = promptFrom(testType, platform)

    override val requiredInputs: List<AgentInput>
        get() = listOf(testTypeArg, platformArg)
}
