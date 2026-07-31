package link.socket.ampere.canon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import link.socket.ampere.link.LinkId

/**
 * [CanonAssetRef] is a field type, not a [CanonEntity] variant, so it is not
 * exercised by [CanonSerializationTest]'s exhaustiveness check. Its
 * `@SerialName`s are pinned here the same way, since they land in traces
 * immediately once a Plug starts emitting populated `artwork`/`thumbnail`/
 * `logo` fields.
 */
class CanonAssetRefSerializationTest {

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    private val provenance = CanonProvenance(
        sourceHandle = SourceHandle(
            linkId = LinkId("link-1"),
            sourceSystem = "apple.mail",
            nativeId = "native-1",
            etag = "etag-1",
        ),
        observedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        nativePayload = NativePayload(
            schema = NativeSchema("MailMessageEntity"),
            fields = JsonObject(mapOf("subject" to JsonPrimitive("hi"))),
        ),
    )

    private fun refSamples(): Map<String, CanonAssetRef> = mapOf(
        "canon_asset_ref.url" to CanonAssetRef.Url(
            template = "https://img.example/{w}x{h}.jpg",
            width = null,
            height = null,
        ),
        "canon_asset_ref.native_handle" to CanonAssetRef.NativeHandle(
            linkId = LinkId("link-1"),
            nativeId = "PHAsset/abc123",
        ),
    )

    @Test
    fun `every CanonAssetRef variant round-trips through the sealed serializer`() {
        refSamples().forEach { (discriminator, ref) ->
            val encoded = json.encodeToString(CanonAssetRef.serializer(), ref)
            val decoded = json.decodeFromString(CanonAssetRef.serializer(), encoded)

            assertEquals(ref, decoded, "round-trip changed $discriminator")
        }
    }

    @Test
    fun `every CanonAssetRef variant writes its pinned discriminator`() {
        refSamples().forEach { (discriminator, ref) ->
            val encoded = json.encodeToString(CanonAssetRef.serializer(), ref)

            assertTrue(
                encoded.contains("\"type\":\"$discriminator\""),
                "expected discriminator $discriminator, got $encoded",
            )
        }
    }

    @Test
    fun `the four widened canon types round-trip with a populated asset ref`() {
        val url = CanonAssetRef.Url(template = "https://img.example/{w}x{h}.jpg")
        val handle = CanonAssetRef.NativeHandle(linkId = LinkId("link-1"), nativeId = "PHAsset/abc123")

        val widened: Map<String, CanonEntity> = mapOf(
            "canon.media_item" to CanonMediaItem(CanonId("mi"), provenance, title = "Track", artwork = url),
            "canon.photo" to CanonPhoto(CanonId("ph"), provenance, thumbnail = handle),
            "canon.document" to CanonDocument(
                CanonId("doc"),
                provenance,
                title = "Spec",
                kind = DocumentKind.WORD_PROCESSOR,
                thumbnail = url,
            ),
            "canon.pass" to CanonPass(CanonId("pa"), provenance, description = "Boarding", logo = handle),
        )

        widened.forEach { (discriminator, entity) ->
            val encoded = json.encodeToString(CanonEntity.serializer(), entity)
            val decoded = json.decodeFromString(CanonEntity.serializer(), encoded)

            assertEquals(entity, decoded, "round-trip changed $discriminator")
        }
    }
}
