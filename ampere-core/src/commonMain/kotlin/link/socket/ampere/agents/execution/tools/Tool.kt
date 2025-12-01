package link.socket.ampere.agents.execution.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import link.socket.ampere.agents.core.actions.AgentActionAutonomy
import link.socket.ampere.agents.core.outcomes.Outcome
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest

typealias ToolId = String
typealias McpServerId = String

/**
 * Base contract for executable tools used by autonomous agents.
 *
 * This is a sealed interface with two subtypes:
 * - [FunctionTool]: Local, in-process tools that execute immediately
 * - [McpTool]: Remote, protocol-based tools exposed by MCP servers
 *
 * The sealed nature forces explicit handling of both execution models,
 * preventing subtle bugs from treating fundamentally different execution
 * contexts (local vs. remote) identically.
 */
@Serializable
sealed interface Tool<Context : ExecutionContext> {

    /** Unique identifier for this tool instance. */
    val id: ToolId

    /**
     * Unique title for this tool.
     * Should be stable across versions to allow referencing and auditing.
     */
    val name: String

    /**
     * Human-readable description of what this tool does.
     * Keep concise but specific enough to support selection and auditing.
     */
    val description: String

    /**
     * The minimum autonomy level an agent must have to use this tool without
     * human approval. Agents below this level should request human oversight
     * before execution.
     */
    val requiredAgentAutonomy: AgentActionAutonomy

    /**
     * Executes the tool with the given request parameters.
     *
     * @param executionRequest parameters, context, and instructions for the execution.
     * @return [Outcome] describing success/failure and any resulting payload.
     */
    suspend fun execute(executionRequest: ExecutionRequest<Context>): Outcome
}

/**
 * A locally-defined, in-process tool that executes immediately.
 *
 * FunctionTools wrap actual executable functions and are context-specific.
 * They represent the "synapses" of the agent system—fast, deterministic,
 * and fully controlled.
 *
 * @param Context The specific execution context this tool operates on.
 * @property executionFunction The actual function to invoke when this tool executes.
 */
@Serializable
data class FunctionTool<Context : ExecutionContext>(
    override val id: ToolId,
    override val name: String,
    override val description: String,
    override val requiredAgentAutonomy: AgentActionAutonomy,

    /**
     * The actual executable function wrapped by this tool.
     * This function is invoked when execute() is called.
     */
    @Serializable(with = ExecutionFunctionSerializer::class)
    val executionFunction: suspend (ExecutionRequest<Context>) -> Outcome,
) : Tool<Context> {

    override suspend fun execute(executionRequest: ExecutionRequest<Context>): Outcome {
        return executionFunction(executionRequest)
    }
}

/**
 * A remote tool exposed by an MCP (Model Context Protocol) server.
 *
 * McpTools don't contain the actual execution logic—that lives in the MCP server.
 * Instead, they carry metadata about how to invoke the remote tool.
 * They represent "external sensory organs"—eyes on GitHub, ears on CI/CD, etc.
 *
 * McpTool is constrained to [ExecutionContext] (not generic) because MCP is
 * protocol-based and serializable—it doesn't know about application-specific
 * context types.
 *
 * @property serverId Identifier of the MCP server that exposes this tool.
 * @property remoteToolName The name of the tool as registered in the MCP server.
 * @property inputSchema JSON schema describing the expected input parameters (nullable).
 */
@Serializable
data class McpTool(
    override val id: ToolId,
    override val name: String,
    override val description: String,
    override val requiredAgentAutonomy: AgentActionAutonomy,

    /**
     * Identifier of the MCP server that exposes this tool.
     * Used to route execution requests to the correct server.
     */
    val serverId: McpServerId,

    /**
     * The name of this tool as registered in the MCP server.
     * May differ from [name] which is the local display name.
     */
    val remoteToolName: String,

    /**
     * Optional JSON schema describing the input parameters expected by this tool.
     * Null if the tool accepts no parameters or schema is not provided.
     */
    val inputSchema: JsonElement? = null,
) : Tool<ExecutionContext> {

    override suspend fun execute(executionRequest: ExecutionRequest<ExecutionContext>): Outcome {
        // MCP tool execution will be implemented in a later task (AMP-203.4)
        // For now, this is a placeholder that will be filled in when MCP server
        // integration is complete.
        throw NotImplementedError(
            "MCP tool execution is not yet implemented. " +
            "This will be added in task AMP-203.4 (MCP Server Integration)."
        )
    }
}

/**
 * Custom serializer for execution functions.
 * Note: Functions cannot be truly serialized, so this serializer is a placeholder.
 * In practice, FunctionTools should be registered in-memory and referenced by ID.
 */
// TODO: Implement proper serialization strategy for FunctionTool (may need to store by ID reference)
internal object ExecutionFunctionSerializer :
    kotlinx.serialization.KSerializer<suspend (ExecutionRequest<*>) -> Outcome> {

    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
        "ExecutionFunction",
        kotlinx.serialization.descriptors.PrimitiveKind.STRING
    )

    override fun serialize(
        encoder: kotlinx.serialization.encoding.Encoder,
        value: suspend (ExecutionRequest<*>) -> Outcome
    ) {
        // Functions cannot be serialized directly
        encoder.encodeString("<<function>>")
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): suspend (ExecutionRequest<*>) -> Outcome {
        decoder.decodeString()
        // Return a placeholder function - in practice, tools should be looked up by ID
        return { _ ->
            throw IllegalStateException("Cannot deserialize function directly. Tools should be looked up by ID.")
        }
    }
}
