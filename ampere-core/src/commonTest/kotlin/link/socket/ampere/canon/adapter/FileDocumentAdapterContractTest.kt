package link.socket.ampere.canon.adapter

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import link.socket.ampere.canon.CanonDocument
import link.socket.ampere.canon.NativePayload
import link.socket.ampere.canon.NativeSchema

class FileDocumentAdapterContractTest : WritableCanonAdapterContract<CanonDocument>() {

    override fun adapter(store: FakeNativeStore): FileDocumentAdapter = FileDocumentAdapter(store)

    override fun fixturePayload(): NativePayload = NativePayload(
        schema = NativeSchema("FileEntity"),
        fields = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Roadmap"),
                "documentKind" to JsonPrimitive("presentation"),
                "mimeType" to JsonPrimitive("application/vnd.apple.keynote"),
                "revisionHistory" to JsonPrimitive("r1,r2,r3"),
            ),
        ),
    )

    override val requiredField: String = "title"
    override val unprojectedField: String = "revisionHistory"
}
