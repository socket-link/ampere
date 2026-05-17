package link.socket.ampere.agents.domain.cognition.sparks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the public [parseSpark] dispatch path introduced in AMPR-165:
 * `---json` / `---` fenced documents decoded via kotlinx-serialization,
 * with typed errors for the failure modes the schema can produce.
 */
class SparkParserJsonTest {

    @Test
    fun `phase variant happy path parses all fields`() {
        val raw = """
            |---json
            |{
            |  "type": "phase",
            |  "id": "cooking-domain",
            |  "name": "Cooking Domain",
            |  "whenToUse": "tasks involving recipes",
            |  "phases": ["PLAN", "EXECUTE"],
            |  "tags": ["cooking", "recipes"],
            |  "modelPreference": "gpt-4o-mini",
            |  "agentRole": "Chef",
            |  "requestedToolIds": ["read_recipe", "convert_units"]
            |}
            |---
            |
            |# Cooking Domain
            |
            |Apply culinary reasoning.
        """.trimMargin()

        val result = parseSpark(raw)
        assertIs<SparkParseResult.Ok>(result)
        val source = assertIs<DeclarativeSparkSource.Phase>(result.source)
        assertEquals("cooking-domain", source.frontmatter.id)
        assertEquals("Cooking Domain", source.frontmatter.name)
        assertEquals("tasks involving recipes", source.frontmatter.whenToUse)
        assertEquals(setOf(CognitivePhase.PLAN, CognitivePhase.EXECUTE), source.frontmatter.phases)
        assertEquals(setOf("cooking", "recipes"), source.frontmatter.tags)
        assertEquals("gpt-4o-mini", source.frontmatter.modelPreference)
        assertEquals("Chef", source.frontmatter.agentRole)
        assertEquals(setOf("read_recipe", "convert_units"), source.frontmatter.requestedToolIds)
        assertTrue(source.body.startsWith("# Cooking Domain"))
        assertTrue(source.body.contains("Apply culinary reasoning."))
    }

    @Test
    fun `phase variant extracts per-phase sections from body`() {
        val raw = """
            |---json
            |{
            |  "type": "phase",
            |  "id": "code-agent",
            |  "name": "Code Agent",
            |  "whenToUse": "writing code",
            |  "phases": ["PERCEIVE", "PLAN", "EXECUTE", "LEARN"]
            |}
            |---
            |
            |You are a code agent.
            |
            |## When Perceiving
            |
            |Read existing code first.
            |
            |## When Planning
            |
            |Sequence steps.
            |
            |## When Executing
            |
            |Run the tools.
            |
            |## When Learning
            |
            |Extract knowledge.
        """.trimMargin()

        val result = parseSpark(raw)
        assertIs<SparkParseResult.Ok>(result)
        val source = assertIs<DeclarativeSparkSource.Phase>(result.source)
        assertEquals("You are a code agent.", source.body)
        assertEquals("Read existing code first.", source.phaseContributions[CognitivePhase.PERCEIVE])
        assertEquals("Sequence steps.", source.phaseContributions[CognitivePhase.PLAN])
        assertEquals("Run the tools.", source.phaseContributions[CognitivePhase.EXECUTE])
        assertEquals("Extract knowledge.", source.phaseContributions[CognitivePhase.LEARN])
    }

    @Test
    fun `role variant happy path parses all fields`() {
        val raw = """
            |---json
            |{
            |  "type": "role",
            |  "id": "code",
            |  "name": "Role:Code",
            |  "agentRole": "Code Writer",
            |  "requestedToolIds": ["read_code_file", "write_code_file"],
            |  "allowedTools": ["read_code_file", "write_code_file"],
            |  "fileAccessScope": {
            |    "read": ["**/*"],
            |    "write": ["**/*.kt"],
            |    "forbidden": ["**/.git/**"]
            |  }
            |}
            |---
            |
            |## Role: Code
            |
            |You are operating in a code-focused capacity.
        """.trimMargin()

        val result = parseSpark(raw)
        assertIs<SparkParseResult.Ok>(result)
        val source = assertIs<DeclarativeSparkSource.Role>(result.source)
        assertEquals("code", source.frontmatter.id)
        assertEquals("Role:Code", source.frontmatter.name)
        assertEquals("Code Writer", source.frontmatter.agentRole)
        assertEquals(setOf("read_code_file", "write_code_file"), source.frontmatter.requestedToolIds)
        assertEquals(setOf("read_code_file", "write_code_file"), source.frontmatter.allowedTools)
        val scope = source.frontmatter.fileAccessScope
        assertEquals(setOf("**/*"), scope?.read)
        assertEquals(setOf("**/*.kt"), scope?.write)
        assertEquals(setOf("**/.git/**"), scope?.forbidden)
        assertTrue(source.body.startsWith("## Role: Code"))
    }

    @Test
    fun `role variant keeps body as a single block`() {
        // Role guidance applies uniformly across phases — `## When <Phase>` headers
        // in the body must be preserved verbatim, not extracted into phaseContributions.
        val raw = """
            |---json
            |{
            |  "type": "role",
            |  "id": "code",
            |  "name": "Role:Code",
            |  "agentRole": "Code Writer"
            |}
            |---
            |
            |Role body.
            |
            |## When Planning
            |
            |This header should stay inline, not become a phase section.
        """.trimMargin()

        val result = parseSpark(raw)
        assertIs<SparkParseResult.Ok>(result)
        val source = assertIs<DeclarativeSparkSource.Role>(result.source)
        assertTrue(source.body.contains("## When Planning"))
        assertTrue(source.body.contains("This header should stay inline"))
    }

    @Test
    fun `role variant defaults allowedTools and fileAccessScope to null when omitted`() {
        val raw = """
            |---json
            |{
            |  "type": "role",
            |  "id": "code",
            |  "name": "Role:Code",
            |  "agentRole": "Code Writer"
            |}
            |---
            |
            |Body.
        """.trimMargin()

        val result = parseSpark(raw)
        assertIs<SparkParseResult.Ok>(result)
        val source = assertIs<DeclarativeSparkSource.Role>(result.source)
        assertNull(source.frontmatter.allowedTools)
        assertNull(source.frontmatter.fileAccessScope)
        assertEquals(emptySet(), source.frontmatter.requestedToolIds)
    }

    @Test
    fun `phase variant defaults phases to PLAN and tags to empty when omitted`() {
        val raw = """
            |---json
            |{
            |  "type": "phase",
            |  "id": "minimal-edge",
            |  "name": "Minimal Edge",
            |  "whenToUse": "smoke test"
            |}
            |---
            |
            |Body text.
        """.trimMargin()

        val result = parseSpark(raw)
        assertIs<SparkParseResult.Ok>(result)
        val source = assertIs<DeclarativeSparkSource.Phase>(result.source)
        assertEquals(setOf(CognitivePhase.PLAN), source.frontmatter.phases)
        assertEquals(emptySet(), source.frontmatter.tags)
        assertNull(source.frontmatter.modelPreference)
        assertNull(source.frontmatter.agentRole)
    }

    @Test
    fun `missing frontmatter fence is reported`() {
        val result = parseSpark("just some markdown\nwith no fence")
        assertIs<SparkParseResult.Failed>(result)
        assertEquals(SparkParseError.MissingFrontmatter, result.error)
    }

    @Test
    fun `deprecated YAML fence is reported`() {
        val raw = """
            |---
            |id: legacy
            |name: Legacy
            |---
            |
            |Body.
        """.trimMargin()

        val result = parseSpark(raw)
        assertIs<SparkParseResult.Failed>(result)
        assertEquals(SparkParseError.DeprecatedYamlFrontmatter, result.error)
    }

    @Test
    fun `missing closing JSON fence is reported`() {
        val raw = """
            |---json
            |{ "type": "phase", "id": "x", "name": "X", "whenToUse": "y" }
            |
            |No closing fence.
        """.trimMargin()

        val result = parseSpark(raw)
        assertIs<SparkParseResult.Failed>(result)
        assertEquals(SparkParseError.MissingFrontmatter, result.error)
    }

    @Test
    fun `invalid JSON is reported as InvalidJson`() {
        val raw = """
            |---json
            |{ not valid json at all }
            |---
            |
            |Body.
        """.trimMargin()

        val result = parseSpark(raw)
        assertIs<SparkParseResult.Failed>(result)
        assertIs<SparkParseError.InvalidJson>(result.error)
    }

    @Test
    fun `unknown discriminator is reported as UnknownDiscriminator`() {
        val raw = """
            |---json
            |{
            |  "type": "language",
            |  "id": "kotlin",
            |  "name": "Kotlin"
            |}
            |---
            |
            |Body.
        """.trimMargin()

        val result = parseSpark(raw)
        assertIs<SparkParseResult.Failed>(result)
        assertIs<SparkParseError.UnknownDiscriminator>(result.error)
    }

    @Test
    fun `unknown field is reported`() {
        // ignoreUnknownKeys = false → unknown keys must fail loudly so misspelled
        // capability-bearing fields (e.g. `requestedToolId` singular) are surfaced.
        val raw = """
            |---json
            |{
            |  "type": "phase",
            |  "id": "x",
            |  "name": "X",
            |  "whenToUse": "y",
            |  "totallyUnknownField": "boom"
            |}
            |---
            |
            |Body.
        """.trimMargin()

        val result = parseSpark(raw)
        assertIs<SparkParseResult.Failed>(result)
        // Either InvalidJson or one of the more specific variants; the parser
        // surfaces the kotlinx message intact so callers can diagnose. The
        // architectural property under test is "must fail", not the specific
        // classification of unknown-key errors.
        assertTrue(
            result.error is SparkParseError.InvalidJson ||
                result.error is SparkParseError.MissingRequiredField,
            "expected unknown field to fail parsing; got ${result.error}",
        )
    }

    @Test
    fun `invalid phase value is reported`() {
        val raw = """
            |---json
            |{
            |  "type": "phase",
            |  "id": "bad-phase",
            |  "name": "Bad Phase",
            |  "whenToUse": "y",
            |  "phases": ["PLAN", "MEDITATE"]
            |}
            |---
            |
            |Body.
        """.trimMargin()

        val result = parseSpark(raw)
        assertIs<SparkParseResult.Failed>(result)
        assertTrue(
            result.error is SparkParseError.InvalidPhase ||
                result.error is SparkParseError.InvalidJson,
            "expected invalid phase to fail parsing; got ${result.error}",
        )
    }

    @Test
    fun `missing required field is reported`() {
        val raw = """
            |---json
            |{
            |  "type": "phase",
            |  "id": "no-when",
            |  "name": "No When"
            |}
            |---
            |
            |Body.
        """.trimMargin()

        val result = parseSpark(raw)
        assertIs<SparkParseResult.Failed>(result)
        assertTrue(
            result.error is SparkParseError.MissingRequiredField ||
                result.error is SparkParseError.InvalidJson,
            "expected missing required field to fail parsing; got ${result.error}",
        )
    }

    @Test
    fun `empty body is reported`() {
        val raw = """
            |---json
            |{
            |  "type": "phase",
            |  "id": "empty",
            |  "name": "Empty",
            |  "whenToUse": "y"
            |}
            |---
            |
        """.trimMargin()

        val result = parseSpark(raw)
        assertIs<SparkParseResult.Failed>(result)
        assertEquals(SparkParseError.EmptyBody, result.error)
    }
}
