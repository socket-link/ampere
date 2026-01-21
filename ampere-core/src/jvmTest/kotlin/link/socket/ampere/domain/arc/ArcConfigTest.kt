package link.socket.ampere.domain.arc

import com.charleskorn.kaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class ArcConfigTest {

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
    }

    private val yaml = Yaml.default

    @Test
    fun `serialize and deserialize ArcConfig to JSON`() {
        val config = ArcConfig(
            name = "startup-saas",
            description = "PM → Code → QA pipeline for product tickets",
            agents = listOf(
                ArcAgentConfig(role = "pm"),
                ArcAgentConfig(role = "code"),
                ArcAgentConfig(role = "qa"),
            ),
            orchestration = OrchestrationConfig(
                type = OrchestrationType.SEQUENTIAL,
                order = listOf("pm", "code", "qa"),
            ),
        )

        val jsonString = json.encodeToString(ArcConfig.serializer(), config)
        val decoded = json.decodeFromString(ArcConfig.serializer(), jsonString)

        assertEquals(config, decoded)
    }

    @Test
    fun `serialize and deserialize ArcConfig to YAML`() {
        val config = ArcConfig(
            name = "devops-pipeline",
            description = "Infrastructure deployment and monitoring",
            agents = listOf(
                ArcAgentConfig(role = "planner"),
                ArcAgentConfig(role = "executor"),
                ArcAgentConfig(role = "monitor"),
            ),
            orchestration = OrchestrationConfig(
                type = OrchestrationType.SEQUENTIAL,
                order = listOf("planner", "executor", "monitor"),
            ),
        )

        val yamlString = yaml.encodeToString(ArcConfig.serializer(), config)
        val decoded = yaml.decodeFromString(ArcConfig.serializer(), yamlString)

        assertEquals(config, decoded)
    }

    @Test
    fun `deserialize minimal YAML config`() {
        val yamlInput = """
            name: my-arc
            agents:
              - role: worker
        """.trimIndent()

        val config = yaml.decodeFromString(ArcConfig.serializer(), yamlInput)

        assertEquals("my-arc", config.name)
        assertEquals(null, config.description)
        assertEquals(1, config.agents.size)
        assertEquals("worker", config.agents[0].role)
        assertEquals(emptyList(), config.agents[0].sparks)
        assertEquals(OrchestrationType.SEQUENTIAL, config.orchestration.type)
        assertEquals(emptyList(), config.orchestration.order)
    }

    @Test
    fun `deserialize YAML config with agent sparks`() {
        val yamlInput = """
            name: startup-saas
            agents:
              - role: pm
              - role: code
                sparks:
                  - rust-expert
                  - testing
              - role: qa
        """.trimIndent()

        val config = yaml.decodeFromString(ArcConfig.serializer(), yamlInput)

        assertEquals("startup-saas", config.name)
        assertEquals(3, config.agents.size)
        assertEquals(emptyList(), config.agents[0].sparks)
        assertEquals(listOf("rust-expert", "testing"), config.agents[1].sparks)
        assertEquals(emptyList(), config.agents[2].sparks)
    }

    @Test
    fun `deserialize YAML config with parallel orchestration`() {
        val yamlInput = """
            name: parallel-arc
            agents:
              - role: worker1
              - role: worker2
            orchestration:
              type: parallel
        """.trimIndent()

        val config = yaml.decodeFromString(ArcConfig.serializer(), yamlInput)

        assertEquals(OrchestrationType.PARALLEL, config.orchestration.type)
    }

    @Test
    fun `ArcAgentConfig defaults sparks to empty list`() {
        val agent = ArcAgentConfig(role = "code")

        assertEquals("code", agent.role)
        assertEquals(emptyList(), agent.sparks)
    }

    @Test
    fun `OrchestrationConfig defaults to sequential with empty order`() {
        val orchestration = OrchestrationConfig()

        assertEquals(OrchestrationType.SEQUENTIAL, orchestration.type)
        assertEquals(emptyList(), orchestration.order)
    }

    @Test
    fun `OrchestrationType serializes to lowercase in YAML`() {
        val config = OrchestrationConfig(type = OrchestrationType.PARALLEL)
        val yamlString = yaml.encodeToString(OrchestrationConfig.serializer(), config)

        assertEquals(true, yamlString.contains("parallel"))
    }

    @Test
    fun `full arc config roundtrip with all fields`() {
        val original = ArcConfig(
            name = "security-audit",
            description = "Vulnerability scanning and remediation",
            agents = listOf(
                ArcAgentConfig(role = "scanner", sparks = listOf("security-tools")),
                ArcAgentConfig(role = "analyst", sparks = listOf("cve-expert")),
                ArcAgentConfig(role = "remediator"),
            ),
            orchestration = OrchestrationConfig(
                type = OrchestrationType.SEQUENTIAL,
                order = listOf("scanner", "analyst", "remediator"),
            ),
        )

        val yamlString = yaml.encodeToString(ArcConfig.serializer(), original)
        val decoded = yaml.decodeFromString(ArcConfig.serializer(), yamlString)

        assertEquals(original, decoded)
        assertEquals(original.name, decoded.name)
        assertEquals(original.description, decoded.description)
        assertEquals(original.agents.size, decoded.agents.size)
        assertEquals(original.agents[0].sparks, decoded.agents[0].sparks)
        assertEquals(original.orchestration.type, decoded.orchestration.type)
        assertEquals(original.orchestration.order, decoded.orchestration.order)
    }
}
