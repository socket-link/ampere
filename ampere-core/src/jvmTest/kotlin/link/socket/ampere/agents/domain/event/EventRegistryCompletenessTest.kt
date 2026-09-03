package link.socket.ampere.agents.domain.event

import java.lang.reflect.Modifier
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tripwire for `EventRegistry.allEventTypes` (AMPR-321 task 1).
 *
 * That list is hand-maintained, and an event missing from it is silently
 * invisible: `EnvironmentService.subscribeToAll`, `EventRelayServiceImpl`, and
 * `TraceRecorder` all enumerate it, so an unregistered event reaches no
 * subscriber that did not name its type and appears in no recorded trace. This
 * test walks the sealed [Event] hierarchy instead of trusting the list, so the
 * next omission fails here rather than as a hole in a trace.
 *
 * JVM-only because it needs `sealedSubclasses`; the hierarchy it checks is
 * declared in `commonMain`, so covering it once on one target is enough.
 */
class EventRegistryCompletenessTest {

    /**
     * Concrete events that no walk of the hierarchy can reach. [SparkEvent] is a
     * plain interface, not a sealed one — its implementations are top-level
     * classes in the same file — and reflection cannot enumerate the implementors
     * of an open interface. `SparkEvent is the only Event branch a walk cannot
     * enumerate` below fails if a second such branch appears, which is what keeps
     * this hand-written list from going quietly stale.
     */
    private val openBranchEvents: List<KClass<*>> = listOf(
        SparkAppliedEvent::class,
        SparkRemovedEvent::class,
        CognitiveStateSnapshot::class,
    )

    @Test
    fun `every sealed Event subtype is registered`() {
        val missing = declaredEventTypes()
            .filterValues { it !in EventRegistry.allEventTypes }
            .map { (klass, eventType) -> "${klass.name} ($eventType)" }
            .sorted()

        assertTrue(
            missing.isEmpty(),
            "Event subtypes missing from EventRegistry.allEventTypes — they are invisible to " +
                "subscribeToAll, the relay, and every recorded trace:\n${missing.joinToString("\n")}",
        )
    }

    @Test
    fun `every registered event type is declared by an Event subtype`() {
        val declared = declaredEventTypes().values.toSet()
        val unknown = EventRegistry.allEventTypes.filterNot { it in declared }.sorted()

        assertTrue(
            unknown.isEmpty(),
            "EventRegistry.allEventTypes names types no Event subtype declares:\n${unknown.joinToString("\n")}",
        )
    }

    @Test
    fun `no event type is registered twice`() {
        val duplicates = EventRegistry.allEventTypes
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .sorted()

        assertTrue(duplicates.isEmpty(), "Duplicate entries in allEventTypes: $duplicates")
    }

    @Test
    fun `every concrete Event subtype declares an EVENT_TYPE constant`() {
        val withoutConstant = concreteEventClasses()
            .filter { it.eventTypeConstant() == null }
            .map { it.name }
            .sorted()

        assertTrue(
            withoutConstant.isEmpty(),
            "Event subtypes with no EVENT_TYPE constant on themselves or a supertype, which this " +
                "tripwire cannot check and EventRegistry cannot name:\n${withoutConstant.joinToString("\n")}",
        )
    }

    /**
     * A non-sealed interface under [Event] is a hole in the hierarchy: its
     * implementors can live anywhere, so nothing — not this test, not the
     * compiler — can enumerate them. One exists ([SparkEvent]) and its three
     * implementations are listed in [openBranchEvents]. A second one has to be
     * listed there too, or its events go unchecked.
     */
    @Test
    fun `SparkEvent is the only Event branch a walk cannot enumerate`() {
        val openBranches = Event::class.sealedSubclasses
            .filter { it.java.isInterface && !it.isSealed }
            .map { it.java.name }

        assertEquals(listOf(SparkEvent::class.java.name), openBranches)
    }

    @Test
    fun `the probe verdict event is registered`() {
        assertTrue(
            ProbeEvent.VerdictReached.EVENT_TYPE in EventRegistry.allEventTypes,
            "VerdictReached must be registered for a trace to capture it",
        )
    }

    /** Concrete [Event] classes mapped to the `EVENT_TYPE` each one resolves. */
    private fun declaredEventTypes(): Map<Class<*>, EventType> =
        concreteEventClasses().mapNotNull { klass ->
            klass.eventTypeConstant()?.let { klass to it }
        }.toMap()

    /** Every instantiable event in the hierarchy, plus the branch a walk cannot reach. */
    private fun concreteEventClasses(): List<Class<*>> =
        (sealedLeaves(Event::class) + openBranchEvents)
            .map { it.java }
            .distinct()
            .filterNot { it.isInterface || Modifier.isAbstract(it.modifiers) }

    /** Every non-sealed class in the hierarchy rooted at [root], depth-first. */
    private fun sealedLeaves(root: KClass<*>): List<KClass<*>> =
        if (root.isSealed) root.sealedSubclasses.flatMap(::sealedLeaves) else listOf(root)

    /**
     * Reads the `EVENT_TYPE` constant, which a `const val` puts in a static field
     * on its declaring class. It is not always on the event itself:
     * `EmissionEvent.BaseProduced` takes its type from the `EmissionEvent.Produced`
     * interface it implements, so supertypes are searched too.
     */
    private fun Class<*>.eventTypeConstant(): EventType? =
        declaredFields.firstOrNull { it.name == "EVENT_TYPE" }?.get(null) as? EventType
            ?: (interfaces.asList() + listOfNotNull(superclass)).firstNotNullOfOrNull { it.eventTypeConstant() }
}
