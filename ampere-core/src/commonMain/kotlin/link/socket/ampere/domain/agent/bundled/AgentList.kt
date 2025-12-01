package link.socket.ampere.domain.agent.bundled

import link.socket.ampere.domain.agent.AgentInput

val codeAgents =
    listOf(
        APIDesignAgent,
        CleanJsonAgent,
        DocumentationAgent,
        PerformanceOptimizationAgent,
        PlatformCompatibilityAgent,
        QATestingAgent,
        ReleaseManagementAgent,
        SecurityReviewAgent,
        WriteCodeAgent,
    )

val generalAgents =
    listOf(
        BusinessAgent,
        CareerAgent,
        CookingAgent,
        DIYAgent,
        EmailAgent,
        FinancialAgent,
        HealthAgent,
        LanguageAgent,
        MediaAgent,
        StudyAgent,
        TechAgent,
        TravelAgent,
    )

val promptAgents =
    listOf(
        ComparePromptsAgent,
        TestAgentAgent(""),
        WritePromptAgent,
    )

val reasoningAgents =
    listOf(
        DelegateTasksAgent,
        ReActAgent,
    )

val agentList: List<AgentDefinition> =
    listOf(
        *codeAgents.toTypedArray(),
        *generalAgents.toTypedArray(),
        *promptAgents.toTypedArray(),
        *reasoningAgents.toTypedArray(),
    )

val agentArgsList: List<String> =
    agentList.map { agent ->
        "${agent.name}(" + (
            agent.requiredInputs.joinToString(", ") { input ->
                input.key + ": " +
                    when (input) {
                        is AgentInput.EnumArgs -> "Enum"
                        is AgentInput.StringArg -> "String"
                        is AgentInput.ListArg -> "List<String>"
                    }
            }
            ) + ")"
    }

fun String?.getAgentDefinition(prompt: String?): AgentDefinition =
    when (this) {
        APIDesignAgent.name -> APIDesignAgent
        BusinessAgent.name -> BusinessAgent
        CareerAgent.name -> CareerAgent
        CleanJsonAgent.name -> CleanJsonAgent
        ComparePromptsAgent.name -> ComparePromptsAgent
        CookingAgent.name -> CookingAgent
        DelegateTasksAgent.name -> DelegateTasksAgent
        DIYAgent.name -> DIYAgent
        DocumentationAgent.name -> DocumentationAgent
        EmailAgent.name -> EmailAgent
        FinancialAgent.name -> FinancialAgent
        HealthAgent.name -> HealthAgent
        LanguageAgent.name -> LanguageAgent
        MediaAgent.name -> MediaAgent
        PerformanceOptimizationAgent.name -> PerformanceOptimizationAgent
        PlatformCompatibilityAgent.name -> PlatformCompatibilityAgent
        QATestingAgent.name -> QATestingAgent
        ReActAgent.name -> ReActAgent
        ReleaseManagementAgent.name -> ReleaseManagementAgent
        SecurityReviewAgent.name -> SecurityReviewAgent
        StudyAgent.name -> StudyAgent
        TechAgent.name -> TechAgent
        TravelAgent.name -> TravelAgent
        WriteCodeAgent.name -> WriteCodeAgent
        WritePromptAgent.name -> WritePromptAgent
        else -> TestAgentAgent(prompt.orEmpty())
    }
