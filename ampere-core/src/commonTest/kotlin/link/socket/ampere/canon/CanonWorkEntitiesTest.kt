package link.socket.ampere.canon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import link.socket.ampere.link.LinkId

/**
 * The knowledge-work wave (AMPR-262): `WORK_ITEM`, `PROJECT`, `MILESTONE`, `TABLE`.
 *
 * Two things beyond the shared round-trip coverage in [CanonSerializationTest]
 * need pinning here. First, these are the only canon types whose realistic
 * sources are `Mcp` and `FolderMount` Links, so provenance from both is
 * exercised. Second, [CanonTable] is the first canon type that could carry bulk
 * content, and the bulk rule — schema and counts in canon, content by reference
 * — is only real if something asserts it.
 */
class CanonWorkEntitiesTest {

    /**
     * The serialized budget for a canon *projection*, established by AMPR-262.
     *
     * There was no trace budget to inherit: `TraceRecorder` buffers on
     * `Channel.UNLIMITED` and `Trace.sq` stores an unbounded `TEXT` blob, so
     * nothing bounded a recording. 32 KiB keeps a hundred-entity trace under
     * 3.2 MiB worst case and near 1 MiB in practice — a comfortable SQLite
     * value, and a cheap full decode, which `PlaybackRelay` pays on every
     * construction because `RecordedModelCall` deserializes every event just to
     * find the provider calls.
     *
     * **Scoped to the entity's own fields, deliberately.** The budget cannot
     * cover [CanonProvenance.nativePayload] — that is an unbounded `JsonObject`
     * by design, and its own KDoc already tells adapters to drop it for large
     * payloads. The bulk rule governs what the canon projection carries; the
     * native payload is a separate, already-documented escape hatch. So the
     * fixtures here carry provenance with no native payload.
     */
    private val projectionBudgetBytes = 32 * 1024

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    private fun provenanceFrom(sourceSystem: String, nativeId: String) = CanonProvenance(
        sourceHandle = SourceHandle(
            linkId = LinkId("link-1"),
            sourceSystem = sourceSystem,
            nativeId = nativeId,
        ),
        observedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
    )

    /** A work item as it would arrive over an `Mcp` Link. */
    private val mcpProvenance = provenanceFrom("mcp:linear", "AMPR-262")

    /** A table as it would arrive over a `FolderMount` Link. */
    private val folderMountProvenance = provenanceFrom("folder:~/data", "q3-forecast.csv")

    private fun roundTrip(entity: CanonEntity): CanonEntity =
        json.decodeFromString(
            CanonEntity.serializer(),
            json.encodeToString(CanonEntity.serializer(), entity),
        )

    @Test
    fun `a work item round-trips over an Mcp link`() {
        val entity = CanonWorkItem(
            canonId = CanonId("wi-1"),
            provenance = mcpProvenance,
            title = "Knowledge-work canon wave",
            status = CanonWorkStatus.IN_PROGRESS,
            providerStatus = "In Review",
            assignee = CanonPerson(CanonId("p-1"), mcpProvenance, displayName = "Miley"),
            projectId = CanonId("pj-1"),
            dueAt = Instant.fromEpochMilliseconds(1_700_086_400_000),
            labels = listOf("api", "spike", "architecture"),
        )

        assertEquals(entity, roundTrip(entity))
        assertEquals(CanonRing.SERVICE, entity.ring)
    }

    @Test
    fun `a project round-trips over an Mcp link`() {
        val entity = CanonProject(
            canonId = CanonId("pj-1"),
            provenance = mcpProvenance,
            name = "Chassis & Canon",
            status = CanonWorkStatus.BACKLOG,
            providerStatus = "Backlog",
            targetDate = Instant.fromEpochMilliseconds(1_700_086_400_000),
            lead = CanonPerson(CanonId("p-1"), mcpProvenance, displayName = "Miley"),
            summary = "Chassis SPI and domain-type canon v1.",
        )

        assertEquals(entity, roundTrip(entity))
        assertEquals(CanonRing.SERVICE, entity.ring)
    }

    @Test
    fun `a milestone round-trips over an Mcp link`() {
        val entity = CanonMilestone(
            canonId = CanonId("ml-1"),
            provenance = mcpProvenance,
            name = "Beta",
            targetDate = Instant.fromEpochMilliseconds(1_700_086_400_000),
            projectId = CanonId("pj-1"),
            progressFraction = 0.42,
        )

        assertEquals(entity, roundTrip(entity))
        assertEquals(CanonRing.SERVICE, entity.ring)
    }

    @Test
    fun `a table round-trips over a FolderMount link`() {
        val entity = CanonTable(
            canonId = CanonId("tbl-1"),
            provenance = folderMountProvenance,
            title = "q3-forecast.csv",
            columnNames = listOf("region", "revenue"),
            rowCount = 412,
            preview = CanonTablePreview.bounded(listOf(listOf("EMEA", "120"))),
            documentId = CanonId("doc-1"),
            contentRef = CanonAssetRef.NativeHandle(LinkId("link-1"), "q3-forecast.csv"),
        )

        assertEquals(entity, roundTrip(entity))
        assertEquals(CanonRing.SERVICE, entity.ring)
    }

    @Test
    fun `work status wire names are stable`() {
        // These land in traces; a rename breaks PlaybackRelay replay.
        assertEquals("\"backlog\"", json.encodeToString(CanonWorkStatus.serializer(), CanonWorkStatus.BACKLOG))
        assertEquals("\"todo\"", json.encodeToString(CanonWorkStatus.serializer(), CanonWorkStatus.TODO))
        assertEquals(
            "\"in_progress\"",
            json.encodeToString(CanonWorkStatus.serializer(), CanonWorkStatus.IN_PROGRESS),
        )
        assertEquals("\"done\"", json.encodeToString(CanonWorkStatus.serializer(), CanonWorkStatus.DONE))
        assertEquals(
            "\"cancelled\"",
            json.encodeToString(CanonWorkStatus.serializer(), CanonWorkStatus.CANCELLED),
        )
    }

    @Test
    fun `a small preview survives bounding untouched`() {
        val rows = listOf(listOf("EMEA", "120"), listOf("APAC", "88"))

        val preview = CanonTablePreview.bounded(rows)

        assertEquals(rows, preview.rows)
        assertFalse(preview.truncated, "nothing was cut; truncated must not claim otherwise")
        assertTrue(preview.isWithinBounds)
    }

    @Test
    fun `bounding truncates rows and columns and cells`() {
        val wideLongTable = List(500) { row ->
            List(40) { column -> "r${row}c$column-" + "x".repeat(400) }
        }

        val preview = CanonTablePreview.bounded(wideLongTable)

        assertEquals(CanonTablePreview.MAX_ROWS, preview.rows.size)
        preview.rows.forEach { row ->
            assertEquals(CanonTablePreview.MAX_COLUMNS, row.size)
            row.forEach { cell -> assertEquals(CanonTablePreview.MAX_CELL_CHARS, cell.length) }
        }
        assertTrue(preview.truncated, "rows columns and cells were all cut")
        assertTrue(preview.isWithinBounds)
    }

    @Test
    fun `a hand-built oversized preview reports itself out of bounds`() {
        // The bound is a write-side factory rule, not a decode-time reject: an
        // oversized preview must still decode, or a recorded trace carrying one
        // becomes permanently unreplayable. isWithinBounds is how it stays visible.
        val oversized = CanonTablePreview(rows = List(50) { listOf("x".repeat(500)) })

        assertFalse(oversized.isWithinBounds)
        assertEquals(oversized, roundTrip(tableCarrying(oversized)).let { (it as CanonTable).preview })
    }

    @Test
    fun `a worst case table serializes within the projection budget`() {
        // Worst byte-per-unit ratio in UTF-8 is a three-byte BMP character, not
        // a four-byte one: a four-byte codepoint costs two UTF-16 units, so it
        // averages two bytes per unit against this character's three.
        val worstCaseChar = "漢"
        val maxed = tableCarrying(
            CanonTablePreview.bounded(
                List(CanonTablePreview.MAX_ROWS * 100) {
                    List(CanonTablePreview.MAX_COLUMNS * 4) {
                        worstCaseChar.repeat(CanonTablePreview.MAX_CELL_CHARS * 3)
                    }
                },
            ),
        )

        val encoded = json.encodeToString(CanonEntity.serializer(), maxed)

        val byteCount = encoded.encodeToByteArray().size
        assertTrue(
            byteCount <= projectionBudgetBytes,
            "a worst-case canon.table serialized to $byteCount bytes over a $projectionBudgetBytes byte budget",
        )
        assertEquals(maxed, roundTrip(maxed), "worst-case cells must survive the round trip intact")
    }

    @Test
    fun `bounding never splits a surrogate pair`() {
        // A lone high surrogate is not valid UTF-8; it would encode as a
        // replacement character and break the round trip these bounds protect.
        // The leading "a" shifts the pairs so the cut index lands on a high
        // surrogate; without the offset the boundary falls harmlessly between
        // whole pairs and the hazard never fires.
        val cell = "a" + "𝄞".repeat(CanonTablePreview.MAX_CELL_CHARS)
        assertTrue(cell[CanonTablePreview.MAX_CELL_CHARS - 1].isHighSurrogate(), "fixture must straddle the bound")

        val preview = CanonTablePreview.bounded(listOf(listOf(cell)))
        val kept = preview.rows.single().single()

        assertFalse(kept.last().isHighSurrogate(), "bounding left a lone high surrogate")
        assertEquals(preview, roundTrip(tableCarrying(preview)).let { (it as CanonTable).preview })
    }

    @Test
    fun `a table carries counts and a window but never its bulk rows`() {
        val allRows = List(50_000) { row -> listOf("row-$row", "payload-$row") }
        val table = CanonTable(
            canonId = CanonId("tbl-1"),
            provenance = folderMountProvenance,
            title = "q3-forecast.csv",
            columnNames = listOf("region", "revenue"),
            rowCount = allRows.size,
            preview = CanonTablePreview.bounded(allRows),
            contentRef = CanonAssetRef.NativeHandle(LinkId("link-1"), "q3-forecast.csv"),
        )

        val encoded = json.encodeToString(CanonEntity.serializer(), table)

        // The count rides the entity; the rows behind it do not.
        assertTrue(encoded.contains("\"rowCount\":50000"))
        assertTrue(encoded.contains("payload-0"), "the bounded window is present")
        assertFalse(
            encoded.contains("payload-${CanonTablePreview.MAX_ROWS}"),
            "content past the preview bound rode the entity; the bulk rule is not holding",
        )
        assertFalse(encoded.contains("payload-49999"), "the tail of the table rode the entity")
    }

    private fun tableCarrying(preview: CanonTablePreview) = CanonTable(
        canonId = CanonId("tbl-1"),
        provenance = folderMountProvenance,
        title = "q3-forecast.csv",
        columnNames = List(CanonTablePreview.MAX_COLUMNS) { "column-$it" },
        rowCount = 50_000,
        preview = preview,
        documentId = CanonId("doc-1"),
        contentRef = CanonAssetRef.NativeHandle(LinkId("link-1"), "q3-forecast.csv"),
    )
}
