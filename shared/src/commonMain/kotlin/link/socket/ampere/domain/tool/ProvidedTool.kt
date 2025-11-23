@file:Suppress("ClassName")

package link.socket.ampere.domain.tool

import kotlinx.serialization.Serializable

@Serializable
sealed class ProvidedTool<T : AITool> (open val definition: T) {
    data class Bash<T : AITool> (override val definition: T) : ProvidedTool<T>(definition)
    data class CodeExecution<T : AITool> (override val definition: T) : ProvidedTool<T>(definition)
    data class ComputerUse<T : AITool> (override val definition: T) : ProvidedTool<T>(definition)
    data class Connector<T : AITool> (override val definition: T) : ProvidedTool<T>(definition)
    data class FileSearch<T : AITool> (override val definition: T) : ProvidedTool<T>(definition)
    data class ImageGeneration<T : AITool> (override val definition: T) : ProvidedTool<T>(definition)
    data class MCP<T : AITool> (override val definition: T) : ProvidedTool<T>(definition)
    data class TextEditor<T : AITool> (override val definition: T) : ProvidedTool<T>(definition)
    data class UrlContext<T : AITool> (override val definition: T) : ProvidedTool<T>(definition)
    data class WebSearch<T : AITool> (override val definition: T) : ProvidedTool<T>(definition)
}
