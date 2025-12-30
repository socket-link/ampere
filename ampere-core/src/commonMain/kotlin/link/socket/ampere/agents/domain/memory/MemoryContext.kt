package link.socket.ampere.agents.domain.memory

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.knowledge.Knowledge

/**
 * Represents the current context an agent is operating within.
 *
 * This is used to query for relevant past Knowledge entries—by capturing
 * the current situation, we can find learnings from similar past situations.
 */
@Serializable
data class MemoryContext(
    /** What kind of task is being attempted (e.g., "database_migration", "api_endpoint") */
    val taskType: String,

    /** Thematic tags for this context (e.g., "backend", "kotlin", "testing") */
    val tags: Set<String>,

    /** Human-readable description of what's being attempted */
    val description: String,

    /** Optional: which project/repository this relates to */
    val projectContext: String? = null,

    /** Optional: temporal filter for "show me recent learnings" queries */
    val timeConstraint: TimeRange? = null,

    /** Optional: complexity estimate to find similarly-complex past attempts */
    val complexity: ComplexityLevel? = null,
) {
    /**
     * Calculate a similarity score with a Knowledge entry based on overlapping context.
     * Higher score = more relevant to current situation.
     *
     * This is a simple initial implementation—can be enhanced with more sophisticated
     * similarity measures (embeddings, learned weights, etc.)
     *
     * @param knowledge The knowledge entry to compare against
     * @param currentTime The current timestamp (defaults to now, but can be overridden for testing)
     * @return A similarity score between 0.0 (completely unrelated) and 1.0 (highly relevant)
     */
    fun similarityScore(knowledge: Knowledge, currentTime: Instant = Clock.System.now()): Double {
        var score = 0.0

        // Temporal proximity: more recent knowledge is often more relevant
        val ageInDays = (currentTime - knowledge.timestamp).inWholeDays
        val recencyScore = when {
            ageInDays < 7 -> 1.0 // Very recent
            ageInDays < 30 -> 0.7 // Recent
            ageInDays < 90 -> 0.4 // Moderately recent
            else -> 0.2 // Older
        }
        score += recencyScore * 0.3 // Weight recency at 30%

        // Textual similarity in descriptions/learnings (simple keyword matching initially)
        val descriptionWords = description.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }.toSet()
        val approachWords = knowledge.approach.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }.toSet()
        val learningWords = knowledge.learnings.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }.toSet()

        if (descriptionWords.isNotEmpty()) {
            val overlapWithApproach = descriptionWords.intersect(approachWords).size.toDouble()
            val overlapWithLearnings = descriptionWords.intersect(learningWords).size.toDouble()
            val totalOverlap = (overlapWithApproach + overlapWithLearnings) / descriptionWords.size

            score += totalOverlap * 0.7 // Weight textual similarity at 70%
        }

        return score.coerceIn(0.0, 1.0)
    }
}

/**
 * Represents a time range for temporal filtering of memories.
 */
@Serializable
data class TimeRange(
    val start: Instant,
    val end: Instant,
) {
    init {
        require(end >= start) { "Time range end must be at or after start" }
    }

    /**
     * Check if a given timestamp falls within this time range.
     */
    fun contains(timestamp: Instant): Boolean = timestamp in start..end
}

/**
 * Complexity classification for tasks.
 * Helps find past attempts at similarly-complex problems.
 */
@Serializable
enum class ComplexityLevel {
    /** Single-step, well-defined */
    TRIVIAL,

    /** Multi-step but straightforward */
    SIMPLE,

    /** Requires some decision-making */
    MODERATE,

    /** Many interdependent steps */
    COMPLEX,

    /** No clear established pattern */
    NOVEL,
}
