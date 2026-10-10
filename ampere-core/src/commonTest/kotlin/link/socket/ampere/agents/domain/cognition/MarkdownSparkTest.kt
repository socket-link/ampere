package link.socket.ampere.agents.domain.cognition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase

/**
 * Covers the public markdown entry point added in AMPR-392: [Spark.fromMarkdown] and
 * the preamble slot on [SparkStack.buildSystemPrompt].
 *
 * The contract under test is a *consumer's*, so it is asserted through the public
 * surface only — a consumer with its own `.spark.md` catalogue must be able to reach
 * Ampere's heading rules without re-implementing them.
 */
class MarkdownSparkTest {

    @Test
    fun `all six phase headings round-trip into phaseContributions`() {
        val body = """
            |Always-on guidance.
            |
            |## When Perceiving
            |
            |Read first.
            |
            |## When Recalling
            |
            |Search memory.
            |
            |## When Observing
            |
            |Diff the state.
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

        val spark = Spark.fromMarkdown(id = "Consumer:charter", body = body)

        assertEquals("Consumer:charter", spark.name)
        assertEquals("Always-on guidance.", spark.promptContribution)
        assertEquals(
            mapOf(
                CognitivePhase.PERCEIVE to "Read first.",
                CognitivePhase.RECALL to "Search memory.",
                CognitivePhase.OBSERVE to "Diff the state.",
                CognitivePhase.PLAN to "Sequence steps.",
                CognitivePhase.EXECUTE to "Run the tools.",
                CognitivePhase.LEARN to "Extract knowledge.",
            ),
            spark.phaseContributions,
        )
    }

    @Test
    fun `phase headings match case-insensitively`() {
        val spark = Spark.fromMarkdown(
            id = "Consumer:casing",
            body = "Base.\n\n## when EXECUTING\n\nGo.",
        )

        assertEquals("Base.", spark.promptContribution)
        assertEquals("Go.", spark.phaseContributions[CognitivePhase.EXECUTE])
    }

    @Test
    fun `a When heading that is not one of the six stays in the body`() {
        val body = """
            |Base guidance.
            |
            |## When nothing matches
            |
            |This is prose and belongs to the body.
        """.trimMargin()

        val spark = Spark.fromMarkdown(id = "Consumer:prose", body = body)

        assertEquals(emptyMap(), spark.phaseContributions)
        assertTrue(spark.promptContribution.contains("## When nothing matches"))
        assertTrue(spark.promptContribution.contains("This is prose and belongs to the body."))
    }

    @Test
    fun `a non-phase level-two heading closes the open phase section`() {
        val body = """
            |Base.
            |
            |## When Planning
            |
            |Plan carefully.
            |
            |## Appendix
            |
            |Back to always-on content.
        """.trimMargin()

        val spark = Spark.fromMarkdown(id = "Consumer:appendix", body = body)

        assertEquals("Plan carefully.", spark.phaseContributions[CognitivePhase.PLAN])
        assertTrue(spark.promptContribution.contains("Base."))
        assertTrue(spark.promptContribution.contains("## Appendix"))
        assertTrue(spark.promptContribution.contains("Back to always-on content."))
        assertFalse(spark.promptContribution.contains("Plan carefully."))
    }

    @Test
    fun `a level-three heading does not close the open phase section`() {
        val body = """
            |Base.
            |
            |## When Executing
            |
            |### Step shape
            |
            |One tool per step.
        """.trimMargin()

        val spark = Spark.fromMarkdown(id = "Consumer:nested", body = body)

        assertEquals("Base.", spark.promptContribution)
        assertEquals(
            "### Step shape\n\nOne tool per step.",
            spark.phaseContributions[CognitivePhase.EXECUTE],
        )
    }

    @Test
    fun `a body with no phase headings is entirely the prompt contribution`() {
        val spark = Spark.fromMarkdown(id = "Consumer:flat", body = "# Charter\n\nBe careful.")

        assertEquals("# Charter\n\nBe careful.", spark.promptContribution)
        assertEquals(emptyMap(), spark.phaseContributions)
    }

    @Test
    fun `the tools frontmatter key becomes requestedToolIds`() {
        val spark = Spark.fromMarkdown(
            id = "Consumer:tools",
            body = "Base.",
            frontmatter = mapOf("tools" to "read_code_file, write_code_file  plan_steps"),
        )

        assertEquals(
            setOf("read_code_file", "write_code_file", "plan_steps"),
            spark.requestedToolIds,
        )
    }

    @Test
    fun `a markdown spark narrows nothing`() {
        val spark = Spark.fromMarkdown(
            id = "Consumer:narrowing",
            body = "Base.",
            frontmatter = mapOf("tools" to "read_code_file"),
        )

        // requestedToolIds is a request, not a permission; a prose body must not be able
        // to express either an allow-list or a file scope.
        assertNull(spark.allowedTools)
        assertNull(spark.fileAccessScope)
    }

    @Test
    fun `the role frontmatter key is ignored unless the caller opts in`() {
        val frontmatter = mapOf("role" to "Cooking Domain")

        assertNull(Spark.fromMarkdown(id = "Consumer:role", body = "Base.", frontmatter = frontmatter).agentRole)
        assertEquals(
            "Cooking Domain",
            Spark.fromMarkdown(
                id = "Consumer:role",
                body = "Base.",
                frontmatter = frontmatter,
                contributeRole = true,
            ).agentRole,
        )
    }

    @Test
    fun `unrecognised frontmatter keys are ignored`() {
        val spark = Spark.fromMarkdown(
            id = "Consumer:extra",
            body = "Base.",
            frontmatter = mapOf("whenToUse" to "anything", "allowedTools" to "nothing"),
        )

        assertEquals(emptySet(), spark.requestedToolIds)
        assertNull(spark.agentRole)
        assertNull(spark.allowedTools)
    }

    @Test
    fun `a markdown spark contributes its phase section to the stack prompt`() {
        val spark = Spark.fromMarkdown(
            id = "Consumer:charter",
            body = "Always-on guidance.\n\n## When Recalling\n\nSearch memory.",
        )
        val stack = SparkStack.withAffinity(CognitiveAffinity.ANALYTICAL).push(spark)

        val recalling = stack.buildSystemPrompt(CognitivePhase.RECALL)
        assertTrue(recalling.contains("Always-on guidance."))
        assertTrue(recalling.contains("Search memory."))

        val planning = stack.buildSystemPrompt(CognitivePhase.PLAN)
        assertTrue(planning.contains("Always-on guidance."))
        assertFalse(planning.contains("Search memory."))
    }

    @Test
    fun `a preamble renders ahead of the cognitive context header`() {
        val stack = SparkStack.withAffinity(CognitiveAffinity.ANALYTICAL)
            .push(Spark.fromMarkdown(id = "Consumer:charter", body = "Spark guidance."))

        val prompt = stack.buildSystemPrompt(preamble = "# Host Charter\n\nSpeak plainly.")

        assertTrue(prompt.startsWith("# Host Charter"), "preamble must come first: $prompt")
        val preambleEnd = prompt.indexOf("Speak plainly.")
        val headerStart = prompt.indexOf("# Cognitive Context")
        assertTrue(preambleEnd in 0 until headerStart, "preamble must precede the header: $prompt")
        assertTrue(
            prompt.substring(preambleEnd, headerStart).contains("---"),
            "a separator must sit between preamble and header: $prompt",
        )
        // Everything the stack contributes is unchanged behind the preamble.
        assertTrue(prompt.endsWith(stack.buildSystemPrompt()))
    }

    @Test
    fun `a null or blank preamble leaves the prompt unchanged`() {
        val stack = SparkStack.withAffinity(CognitiveAffinity.ANALYTICAL)
            .push(Spark.fromMarkdown(id = "Consumer:charter", body = "Spark guidance."))

        val baseline = stack.buildSystemPrompt(CognitivePhase.PLAN)
        assertEquals(baseline, stack.buildSystemPrompt(CognitivePhase.PLAN, preamble = null))
        assertEquals(baseline, stack.buildSystemPrompt(CognitivePhase.PLAN, preamble = "   \n  "))
    }
}
