package link.socket.ampere.domain.arc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArcConfigLoaderTest {

    @Test
    fun `load with null user config returns default arc`() {
        val config = ArcConfigLoader.load(userConfig = null)

        assertEquals("startup-saas", config.name)
        assertEquals(3, config.agents.size)
    }

    @Test
    fun `load with user config matching registry merges correctly`() {
        val userConfig = ArcConfig(
            name = "startup-saas",
            description = "Custom description",
            agents = listOf(
                ArcAgentConfig(role = "code", sparks = listOf("rust-expert")),
            ),
        )

        val config = ArcConfigLoader.load(userConfig)

        assertEquals("startup-saas", config.name)
        assertEquals("Custom description", config.description)
        assertEquals(3, config.agents.size)

        val codeAgent = config.agents.find { it.role == "code" }
        assertNotNull(codeAgent)
        assertEquals(listOf("software-engineer", "rust-expert"), codeAgent.sparks)
    }

    @Test
    fun `load with unknown arc name returns user config unchanged`() {
        val userConfig = ArcConfig(
            name = "custom-arc",
            description = "My custom arc",
            agents = listOf(
                ArcAgentConfig(role = "custom-role", sparks = listOf("custom-spark")),
            ),
        )

        val config = ArcConfigLoader.load(userConfig)

        assertEquals("custom-arc", config.name)
        assertEquals("My custom arc", config.description)
        assertEquals(1, config.agents.size)
        assertEquals("custom-role", config.agents[0].role)
        assertEquals(listOf("custom-spark"), config.agents[0].sparks)
    }

    @Test
    fun `load by arc name returns registry arc`() {
        val config = ArcConfigLoader.load("devops-pipeline")

        assertEquals("devops-pipeline", config.name)
        assertEquals(3, config.agents.size)
        assertEquals(listOf("planner", "executor", "monitor"), config.orchestration.order)
    }

    @Test
    fun `load by unknown arc name returns default`() {
        val config = ArcConfigLoader.load("unknown-arc")

        assertEquals("startup-saas", config.name)
    }

    @Test
    fun `load by arc name with user override merges correctly`() {
        val userOverride = ArcConfig(
            name = "ignored",
            agents = listOf(
                ArcAgentConfig(role = "planner", sparks = listOf("terraform-expert")),
            ),
        )

        val config = ArcConfigLoader.load("devops-pipeline", userOverride)

        assertEquals("devops-pipeline", config.name)

        val plannerAgent = config.agents.find { it.role == "planner" }
        assertNotNull(plannerAgent)
        assertEquals(listOf("infrastructure-planner", "terraform-expert"), plannerAgent.sparks)
    }

    @Test
    fun `merge preserves base name`() {
        val base = ArcConfig(
            name = "base-arc",
            agents = listOf(ArcAgentConfig(role = "worker")),
        )
        val override = ArcConfig(
            name = "override-arc",
            agents = listOf(ArcAgentConfig(role = "worker")),
        )

        val merged = ArcConfigLoader.merge(base, override)

        assertEquals("base-arc", merged.name)
    }

    @Test
    fun `merge uses override description when provided`() {
        val base = ArcConfig(
            name = "arc",
            description = "Base description",
            agents = listOf(ArcAgentConfig(role = "worker")),
        )
        val override = ArcConfig(
            name = "arc",
            description = "Override description",
            agents = emptyList(),
        )

        val merged = ArcConfigLoader.merge(base, override)

        assertEquals("Override description", merged.description)
    }

    @Test
    fun `merge uses base description when override is null`() {
        val base = ArcConfig(
            name = "arc",
            description = "Base description",
            agents = listOf(ArcAgentConfig(role = "worker")),
        )
        val override = ArcConfig(
            name = "arc",
            description = null,
            agents = emptyList(),
        )

        val merged = ArcConfigLoader.merge(base, override)

        assertEquals("Base description", merged.description)
    }

    @Test
    fun `merge agents combines sparks with role default`() {
        val base = ArcConfig(
            name = "arc",
            agents = listOf(
                ArcAgentConfig(role = "code"),
            ),
        )
        val override = ArcConfig(
            name = "arc",
            agents = listOf(
                ArcAgentConfig(role = "code", sparks = listOf("testing", "security")),
            ),
        )

        val merged = ArcConfigLoader.merge(base, override)

        assertEquals(1, merged.agents.size)
        assertEquals("code", merged.agents[0].role)
        assertEquals(listOf("software-engineer", "testing", "security"), merged.agents[0].sparks)
    }

    @Test
    fun `merge agents preserves base agents not in override`() {
        val base = ArcConfig(
            name = "arc",
            agents = listOf(
                ArcAgentConfig(role = "pm"),
                ArcAgentConfig(role = "code"),
                ArcAgentConfig(role = "qa"),
            ),
        )
        val override = ArcConfig(
            name = "arc",
            agents = listOf(
                ArcAgentConfig(role = "code", sparks = listOf("rust-expert")),
            ),
        )

        val merged = ArcConfigLoader.merge(base, override)

        assertEquals(3, merged.agents.size)
        assertTrue(merged.agents.any { it.role == "pm" })
        assertTrue(merged.agents.any { it.role == "qa" })
    }

    @Test
    fun `merge agents adds new agents from override`() {
        val base = ArcConfig(
            name = "arc",
            agents = listOf(
                ArcAgentConfig(role = "code"),
            ),
        )
        val override = ArcConfig(
            name = "arc",
            agents = listOf(
                ArcAgentConfig(role = "code"),
                ArcAgentConfig(role = "security", sparks = listOf("pentest")),
            ),
        )

        val merged = ArcConfigLoader.merge(base, override)

        assertEquals(2, merged.agents.size)

        val securityAgent = merged.agents.find { it.role == "security" }
        assertNotNull(securityAgent)
        assertEquals(listOf("pentest"), securityAgent.sparks)
    }

    @Test
    fun `merge orchestration uses override when non-default`() {
        val base = ArcConfig(
            name = "arc",
            agents = emptyList(),
            orchestration = OrchestrationConfig(
                type = OrchestrationType.SEQUENTIAL,
                order = listOf("a", "b", "c"),
            ),
        )
        val override = ArcConfig(
            name = "arc",
            agents = emptyList(),
            orchestration = OrchestrationConfig(
                type = OrchestrationType.PARALLEL,
            ),
        )

        val merged = ArcConfigLoader.merge(base, override)

        assertEquals(OrchestrationType.PARALLEL, merged.orchestration.type)
    }

    @Test
    fun `merge orchestration preserves base when override is default`() {
        val base = ArcConfig(
            name = "arc",
            agents = emptyList(),
            orchestration = OrchestrationConfig(
                type = OrchestrationType.SEQUENTIAL,
                order = listOf("a", "b", "c"),
            ),
        )
        val override = ArcConfig(
            name = "arc",
            agents = emptyList(),
            orchestration = OrchestrationConfig(),
        )

        val merged = ArcConfigLoader.merge(base, override)

        assertEquals(OrchestrationType.SEQUENTIAL, merged.orchestration.type)
        assertEquals(listOf("a", "b", "c"), merged.orchestration.order)
    }

    @Test
    fun `merge handles role case insensitively`() {
        val base = ArcConfig(
            name = "arc",
            agents = listOf(
                ArcAgentConfig(role = "Code"),
            ),
        )
        val override = ArcConfig(
            name = "arc",
            agents = listOf(
                ArcAgentConfig(role = "CODE", sparks = listOf("extra")),
            ),
        )

        val merged = ArcConfigLoader.merge(base, override)

        assertEquals(1, merged.agents.size)
        assertEquals("Code", merged.agents[0].role)
        assertEquals(listOf("software-engineer", "extra"), merged.agents[0].sparks)
    }

    @Test
    fun `full merge scenario with startup-saas`() {
        val userConfig = ArcConfig(
            name = "startup-saas",
            description = "Our team's customized pipeline",
            agents = listOf(
                ArcAgentConfig(role = "code", sparks = listOf("kotlin-expert", "testing")),
                ArcAgentConfig(role = "security", sparks = listOf("owasp")),
            ),
            orchestration = OrchestrationConfig(
                type = OrchestrationType.SEQUENTIAL,
                order = listOf("pm", "code", "security", "qa"),
            ),
        )

        val config = ArcConfigLoader.load(userConfig)

        assertEquals("startup-saas", config.name)
        assertEquals("Our team's customized pipeline", config.description)
        assertEquals(4, config.agents.size)

        val codeAgent = config.agents.find { it.role == "code" }
        assertNotNull(codeAgent)
        assertEquals(listOf("software-engineer", "kotlin-expert", "testing"), codeAgent.sparks)

        val securityAgent = config.agents.find { it.role == "security" }
        assertNotNull(securityAgent)
        assertEquals(listOf("owasp"), securityAgent.sparks)

        assertEquals(listOf("pm", "code", "security", "qa"), config.orchestration.order)
    }
}
