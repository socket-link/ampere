package link.socket.ampere.room

import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventType
import link.socket.ampere.agents.events.InMemoryEventApi
import link.socket.ampere.agents.events.messages.MessageRepository
import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonMilestone
import link.socket.ampere.canon.CanonProject
import link.socket.ampere.canon.CanonProvenance
import link.socket.ampere.canon.CanonWorkGraph
import link.socket.ampere.canon.CanonWorkItem
import link.socket.ampere.canon.CanonWorkStatus
import link.socket.ampere.canon.NativePayload
import link.socket.ampere.canon.NativeSchema
import link.socket.ampere.canon.SourceHandle
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.link.LinkId
import link.socket.ampere.roster.BlueprintRoster

/** A clock a test moves by hand. */
class FixedClock(var current: Instant) : Clock {
    override fun now(): Instant = current
}

/**
 * Everything a Room test needs: a real in-memory door, the message repository over
 * the same database, a pinned clock, sequential ids, and a [DefaultRoomService]
 * hosted by the Blueprint coordinator.
 */
internal class RoomTestRig(agentId: String = "room-test") : AutoCloseable {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clock = FixedClock(Instant.parse("2026-09-28T09:00:00Z"))
    val door = InMemoryEventApi.open(agentId = agentId, clock = clock, scope = scope)
    val repository = MessageRepository(DEFAULT_JSON, scope, door.database)

    private val counter = AtomicInteger(0)
    val ids: () -> String = { "id-${counter.incrementAndGet()}" }

    val room = DefaultRoomService(
        messageRepository = repository,
        eventApi = door.api,
        host = Author.Role(BlueprintRoster.host),
        clock = clock,
        idGenerator = ids,
    )

    suspend fun events(type: EventType): List<Event> = door.api.getEventHistory(eventType = type)

    suspend fun awaitUntil(timeout: Duration = 10.seconds, condition: suspend () -> Boolean): Boolean =
        withTimeoutOrNull(timeout) {
            while (!condition()) delay(20)
            true
        } ?: false

    override fun close() {
        scope.cancel()
        door.close()
    }
}

/** A small vent project: four milestones and a dependency chain, the shape the ticket's fixture has. */
internal object VentGraph {

    val observedAt: Instant = Instant.parse("2026-09-21T09:00:00Z")

    val provenance = CanonProvenance(
        sourceHandle = SourceHandle(
            linkId = LinkId("link-1"),
            sourceSystem = "reminders",
            nativeId = "n-1",
            etag = null,
        ),
        observedAt = observedAt,
        nativePayload = NativePayload(schema = NativeSchema("Reminder"), fields = JsonObject(emptyMap())),
    )

    val project =
        CanonProject(
            CanonId("vent-42"),
            provenance,
            name = "Bathroom exhaust vent",
            status = CanonWorkStatus.IN_PROGRESS,
        )

    val milestoneIds = listOf("ms-measure", "ms-source", "ms-build", "ms-verify")

    fun milestone(id: String, name: String) = CanonMilestone(
        CanonId(id),
        provenance,
        name = name,
        projectId = project.canonId,
    )

    fun item(id: String, status: CanonWorkStatus = CanonWorkStatus.TODO, vararg dependsOn: String) = CanonWorkItem(
        CanonId(id),
        provenance,
        title = id,
        status = status,
        projectId = project.canonId,
        dependsOn = dependsOn.map(::CanonId),
    )

    fun graph(items: List<CanonWorkItem> = defaultItems()): CanonWorkGraph = CanonWorkGraph(
        project = project,
        milestones = listOf(
            milestone("ms-measure", "Measure and spec"),
            milestone("ms-source", "Source parts"),
            milestone("ms-build", "Fabricate and assemble"),
            milestone("ms-verify", "Install and verify"),
        ),
        items = items,
    )

    fun defaultItems(): List<CanonWorkItem> = listOf(
        item("measure-opening", CanonWorkStatus.DONE),
        item("pick-duct"),
        item("pick-fan"),
        item("order-parts", CanonWorkStatus.TODO, "pick-duct", "pick-fan"),
        item("cut-duct", CanonWorkStatus.TODO, "order-parts"),
        item("fit-grille", CanonWorkStatus.TODO, "cut-duct"),
    )

    val subjectIds: Set<String> = setOf("vent-42", "duct", "fan", "grille", "sequence") + defaultItems().map {
        it.canonId.value
    }
}
