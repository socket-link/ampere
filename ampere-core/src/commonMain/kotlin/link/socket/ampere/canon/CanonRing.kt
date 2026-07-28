package link.socket.ampere.canon

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where a canon type's provenance comes from.
 *
 * A ring is a statement about *binding provenance*, not about support level. A
 * [PLATFORM] type is every bit as usable by an Arc as an [INTERCHANGE] one; what
 * the ring records is whether the type can travel through the operating system's
 * own cross-app registry, or whether Ampere is the only thing that knows it is a
 * canonical noun.
 *
 * Ring membership was settled by the SDK pass documented in
 * `.context/issue-586-domain-type-canon-v1.md`. Six of the originally proposed
 * Ring 1 types demoted because the shipped Apple assistant-schema catalog is
 * documents-and-content shaped: it has no calendar, messages, reminders, notes,
 * alarm, or music/video noun.
 */
@Serializable
enum class CanonRing {

    /**
     * Maps to an Apple assistant-schema entity or an Apple system value type
     * without lossy contortion, so the entity can cross app boundaries through
     * the platform's own registry.
     */
    @SerialName("interchange")
    INTERCHANGE,

    /**
     * Reachable only through a native framework (EventKit, HealthKit, AlarmKit,
     * …). Outside the assistant vocabulary, inside the OS.
     */
    @SerialName("platform")
    PLATFORM,

    /**
     * Arrives through a non-OS transport — `Mcp`, `OAuthRest`, `FolderMount`,
     * or `Cli`.
     *
     * The original ticket wrote this as "Mcp/OAuthRest only". Recon widened it:
     * a Note read out of an Obsidian vault arrives over `FolderMount` and was
     * otherwise unhoused by the taxonomy.
     */
    @SerialName("service")
    SERVICE,
}
