package link.socket.ampere.agents.domain.cognition.sparks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SparkParserTest {

    @Test
    fun `happy path parses all fields`() {
        val raw = """
            |---
            |id: cooking-domain
            |name: Cooking Domain
            |whenToUse: tasks involving recipes or ingredients
            |phases: PLAN, EXECUTE
            |tags: cooking, recipes
            |modelPreference: gpt-4o-mini
            |---
            |
            |# Cooking Domain
            |
            |Apply culinary reasoning.
        """.trimMargin()

        val result = parseSpark(raw)
        assertIs<SparkParseResult.Ok>(result)
        val source = result.source
        assertEquals("cooking-domain", source.id)
        assertEquals("Cooking Domain", source.name)
        assertEquals("tasks involving recipes or ingredients", source.whenToUse)
        assertEquals(setOf(CognitivePhase.PLAN, CognitivePhase.EXECUTE), source.phases)
        assertEquals(setOf("cooking", "recipes"), source.tags)
        assertEquals("gpt-4o-mini", source.modelPreference)
        assertTrue(source.body.startsWith("# Cooking Domain"))
        assertTrue(source.body.contains("Apply culinary reasoning."))
    }

    @Test
    fun `defaults phases to PLAN and tags to empty when omitted`() {
        val raw = """
            |---
            |id: minimal-edge
            |name: Minimal Edge
            |whenToUse: edge-case fallback
            |---
            |
            |Body text.
        """.trimMargin()

        val result = parseSpark(raw)
        assertIs<SparkParseResult.Ok>(result)
        assertEquals(setOf(CognitivePhase.PLAN), result.source.phases)
        assertEquals(emptySet(), result.source.tags)
        assertEquals(null, result.source.modelPreference)
    }

    @Test
    fun `missing opening frontmatter fence is reported`() {
        val raw = "no frontmatter here\nat all"
        val result = parseSpark(raw)
        assertIs<SparkParseResult.Failed>(result)
        assertEquals(SparkParseError.MissingFrontmatter, result.error)
    }

    @Test
    fun `missing closing frontmatter fence is reported`() {
        val raw = """
            |---
            |id: orphan
            |name: Orphan
            |whenToUse: never
            |
            |Body without closing fence.
        """.trimMargin()

        val result = parseSpark(raw)
        assertIs<SparkParseResult.Failed>(result)
        assertEquals(SparkParseError.MissingFrontmatter, result.error)
    }

    @Test
    fun `missing id is reported`() {
        val raw = """
            |---
            |name: No Id
            |whenToUse: testing
            |---
            |
            |Body.
        """.trimMargin()

        val result = parseSpark(raw)
        assertIs<SparkParseResult.Failed>(result)
        assertEquals(SparkParseError.MissingRequiredField("id"), result.error)
    }

    @Test
    fun `missing name is reported`() {
        val raw = """
            |---
            |id: no-name
            |whenToUse: testing
            |---
            |
            |Body.
        """.trimMargin()

        val result = parseSpark(raw)
        assertIs<SparkParseResult.Failed>(result)
        assertEquals(SparkParseError.MissingRequiredField("name"), result.error)
    }

    @Test
    fun `missing whenToUse is reported`() {
        val raw = """
            |---
            |id: no-when
            |name: No When
            |---
            |
            |Body.
        """.trimMargin()

        val result = parseSpark(raw)
        assertIs<SparkParseResult.Failed>(result)
        assertEquals(SparkParseError.MissingRequiredField("whenToUse"), result.error)
    }

    @Test
    fun `invalid phase is reported`() {
        val raw = """
            |---
            |id: bad-phase
            |name: Bad Phase
            |whenToUse: testing
            |phases: PLAN, MEDITATE
            |---
            |
            |Body.
        """.trimMargin()

        val result = parseSpark(raw)
        assertIs<SparkParseResult.Failed>(result)
        assertEquals(SparkParseError.InvalidPhase("MEDITATE"), result.error)
    }

    @Test
    fun `empty body is reported`() {
        val raw = """
            |---
            |id: empty
            |name: Empty
            |whenToUse: testing
            |---
            |
        """.trimMargin()

        val result = parseSpark(raw)
        assertIs<SparkParseResult.Failed>(result)
        assertEquals(SparkParseError.EmptyBody, result.error)
    }

    @Test
    fun `malformed frontmatter line is reported`() {
        val raw = """
            |---
            |id: bad-line
            |name: Bad Line
            |whenToUse: testing
            |not-a-key-value-line
            |---
            |
            |Body.
        """.trimMargin()

        val result = parseSpark(raw)
        assertIs<SparkParseResult.Failed>(result)
        assertIs<SparkParseError.InvalidFrontmatter>(result.error)
    }
}
