package link.socket.ampere.agents.domain.cognition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import link.socket.ampere.agents.domain.cognition.sparks.AmpereProjectSpark
import link.socket.ampere.agents.domain.cognition.sparks.ProjectSpark

/**
 * Tests for AmpereProjectSpark configuration.
 *
 * Validates the acceptance criteria from Ticket #227:
 * - Configuration object exists and is accessible
 * - Project description accurately reflects AMPERE's architecture
 * - Conventions reflect actual coding standards
 * - Repository root resolves correctly
 * - Prompt contribution renders cleanly in system prompts
 */
class AmpereProjectSparkTest {

    // ==================== CONFIGURATION OBJECT TESTS ====================

    @Test
    fun `AmpereProjectSpark spark instance is accessible`() {
        val spark = AmpereProjectSpark.spark
        assertNotNull(spark)
    }

    @Test
    fun `AmpereProjectSpark has correct project ID`() {
        val spark = AmpereProjectSpark.spark
        assertEquals("ampere", spark.projectId)
    }

    @Test
    fun `AmpereProjectSpark name follows convention`() {
        val spark = AmpereProjectSpark.spark
        assertEquals("Project:ampere", spark.name)
    }

    // ==================== PROJECT DESCRIPTION TESTS ====================

    @Test
    fun `project description mentions Kotlin Multiplatform`() {
        val spark = AmpereProjectSpark.spark
        assertTrue(
            spark.projectDescription.contains("Kotlin Multiplatform"),
            "Description should mention Kotlin Multiplatform"
        )
    }

    @Test
    fun `project description mentions autonomous AI agents`() {
        val spark = AmpereProjectSpark.spark
        assertTrue(
            spark.projectDescription.contains("autonomous", ignoreCase = true) ||
                spark.projectDescription.contains("AI agent", ignoreCase = true),
            "Description should mention autonomous AI agents"
        )
    }

    @Test
    fun `project description mentions key components`() {
        val spark = AmpereProjectSpark.spark
        assertTrue(
            spark.projectDescription.contains("Agents"),
            "Description should mention Agents"
        )
        assertTrue(
            spark.projectDescription.contains("Events"),
            "Description should mention Events"
        )
        assertTrue(
            spark.projectDescription.contains("Spark"),
            "Description should mention Sparks"
        )
        assertTrue(
            spark.projectDescription.contains("Memory"),
            "Description should mention Memory"
        )
    }

    @Test
    fun `project description mentions PROPEL cognitive loop`() {
        val spark = AmpereProjectSpark.spark
        assertTrue(
            spark.projectDescription.contains("PERCEIVE") ||
                spark.projectDescription.contains("PLAN") ||
                spark.projectDescription.contains("EXECUTE"),
            "Description should mention cognitive loop phases"
        )
    }

    @Test
    fun `project description mentions architecture modules`() {
        val spark = AmpereProjectSpark.spark
        assertTrue(
            spark.projectDescription.contains("ampere-core"),
            "Description should mention ampere-core"
        )
    }

    // ==================== CONVENTIONS TESTS ====================

    @Test
    fun `conventions are not empty`() {
        val spark = AmpereProjectSpark.spark
        assertTrue(
            spark.conventions.isNotBlank(),
            "Conventions should not be empty"
        )
    }

    @Test
    fun `conventions mention Kotlin style`() {
        val spark = AmpereProjectSpark.spark
        assertTrue(
            spark.conventions.contains("Kotlin", ignoreCase = true),
            "Conventions should mention Kotlin"
        )
    }

    @Test
    fun `conventions mention package structure`() {
        val spark = AmpereProjectSpark.spark
        assertTrue(
            spark.conventions.contains("Package Structure") ||
                spark.conventions.contains("agents."),
            "Conventions should describe package structure"
        )
    }

    @Test
    fun `conventions mention testing guidelines`() {
        val spark = AmpereProjectSpark.spark
        assertTrue(
            spark.conventions.contains("Test") || spark.conventions.contains("test"),
            "Conventions should mention testing"
        )
    }

    @Test
    fun `conventions mention event handling`() {
        val spark = AmpereProjectSpark.spark
        assertTrue(
            spark.conventions.contains("Event") || spark.conventions.contains("event"),
            "Conventions should mention event handling"
        )
    }

    @Test
    fun `conventions mention serialization`() {
        val spark = AmpereProjectSpark.spark
        assertTrue(
            spark.conventions.contains("serialization") ||
                spark.conventions.contains("Serializable") ||
                spark.conventions.contains("serializable"),
            "Conventions should mention serialization"
        )
    }

    // ==================== REPOSITORY ROOT TESTS ====================

    @Test
    fun `repository root is not empty`() {
        val spark = AmpereProjectSpark.spark
        assertTrue(
            spark.repositoryRoot.isNotBlank(),
            "Repository root should not be empty"
        )
    }

    @Test
    fun `withRepositoryRoot creates spark with specified root`() {
        val customRoot = "/custom/project/path"
        val spark = AmpereProjectSpark.withRepositoryRoot(customRoot)

        assertEquals(customRoot, spark.repositoryRoot)
        assertEquals("ampere", spark.projectId)
    }

    // ==================== PROMPT CONTRIBUTION TESTS ====================

    @Test
    fun `prompt contribution is properly formatted`() {
        val spark = AmpereProjectSpark.spark
        val prompt = spark.promptContribution

        assertTrue(
            prompt.contains("## Project Context"),
            "Prompt should include 'Project Context' header"
        )
        assertTrue(
            prompt.contains("### Description"),
            "Prompt should include 'Description' section"
        )
        assertTrue(
            prompt.contains("### Repository Root"),
            "Prompt should include 'Repository Root' section"
        )
        assertTrue(
            prompt.contains("### Conventions"),
            "Prompt should include 'Conventions' section"
        )
    }

    @Test
    fun `prompt contribution includes project name`() {
        val spark = AmpereProjectSpark.spark
        val prompt = spark.promptContribution

        assertTrue(
            prompt.contains("ampere"),
            "Prompt should mention project name"
        )
    }

    @Test
    fun `prompt contribution is under 4000 characters`() {
        val spark = AmpereProjectSpark.spark
        val prompt = spark.promptContribution

        assertTrue(
            prompt.length < 4000,
            "Prompt contribution should be under 4000 characters (was ${prompt.length})"
        )
    }

    // ==================== FILE ACCESS TESTS ====================

    @Test
    fun `file access allows reading everywhere`() {
        val spark = AmpereProjectSpark.spark
        val fileAccess = spark.fileAccessScope

        assertTrue(
            fileAccess.readPatterns.contains("**/*"),
            "Should allow reading all files"
        )
    }

    @Test
    fun `file access blocks writing by default`() {
        val spark = AmpereProjectSpark.spark
        val fileAccess = spark.fileAccessScope

        assertTrue(
            fileAccess.writePatterns.isEmpty(),
            "ProjectSpark should not enable writes (RoleSparksprovide write access)"
        )
    }

    @Test
    fun `file access blocks sensitive files`() {
        val spark = AmpereProjectSpark.spark
        val fileAccess = spark.fileAccessScope

        assertTrue(
            fileAccess.forbiddenPatterns.isNotEmpty(),
            "Should have forbidden patterns for sensitive files"
        )
    }

    // ==================== ALLOWED TOOLS TESTS ====================

    @Test
    fun `allowed tools is null for inheritance`() {
        val spark = AmpereProjectSpark.spark

        assertEquals(
            null,
            spark.allowedTools,
            "ProjectSpark should not restrict tools (inherits from affinity)"
        )
    }

    // ==================== CUSTOM PROJECT SPARK TESTS ====================

    @Test
    fun `ProjectSpark simple factory creates minimal spark`() {
        val spark = ProjectSpark.simple(
            projectId = "test-project",
            description = "A test project",
            repositoryRoot = "/test/path"
        )

        assertEquals("test-project", spark.projectId)
        assertEquals("A test project", spark.projectDescription)
        assertEquals("/test/path", spark.repositoryRoot)
        assertEquals("", spark.conventions)
    }

    @Test
    fun `ProjectSpark can be created with custom conventions`() {
        val conventions = "Use tabs, not spaces"
        val spark = ProjectSpark(
            projectId = "custom",
            projectDescription = "Custom project",
            repositoryRoot = "/custom",
            conventions = conventions
        )

        assertEquals(conventions, spark.conventions)
    }
}
