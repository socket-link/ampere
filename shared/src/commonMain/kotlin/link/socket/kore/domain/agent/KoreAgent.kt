@file:Suppress("MemberVisibilityCanBePrivate")

package link.socket.kore.domain.agent

import kotlinx.coroutines.CoroutineScope
import link.socket.kore.domain.agent.bundled.AgentDefinition
import link.socket.kore.domain.ai.configuration.AIConfiguration
import link.socket.kore.domain.capability.AgentCapability
import link.socket.kore.domain.capability.IOCapability
import link.socket.kore.domain.tool.FunctionProvider

data class KoreAgent(
    override val config: AIConfiguration,
    override val scope: CoroutineScope,
    val definition: AgentDefinition,
    val agentFactory: KoreAgentFactory,
) : LLMAgent {

    val name: String = definition.name

    override val tag: String = "Kore${name.replace(" ", "")}-${super.tag}"

    override val prompt: String
        get() = """
            ${super.prompt}
            ${definition.instructions.build()}
        """.trimIndent()

    override val availableFunctions: Map<String, FunctionProvider>
        get() =
            mapOf(
                AgentCapability.GetAgents(tag).impl,
                AgentCapability.PromptAgent(
                    agentTag = tag,
                    config = config,
                    scope = scope,
                    agentFactory = agentFactory,
                ).impl,
                IOCapability.ReadFolderContents(tag).impl,
                IOCapability.CreateFile(tag).impl,
                IOCapability.ReadFiles(tag).impl,
                IOCapability.ParseCsv(tag).impl,
            )
}
