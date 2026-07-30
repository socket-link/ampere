package link.socket.ampere.canon.adapter

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import link.socket.ampere.canon.CanonEmailMessage
import link.socket.ampere.canon.NativePayload
import link.socket.ampere.canon.NativeSchema

class MailMessageAdapterContractTest : WritableCanonAdapterContract<CanonEmailMessage>() {

    override fun adapter(store: FakeNativeStore): MailMessageAdapter = MailMessageAdapter(store)

    override fun fixturePayload(): NativePayload = NativePayload(
        schema = NativeSchema("MailMessageEntity"),
        fields = JsonObject(
            mapOf(
                "subject" to JsonPrimitive("Quarterly review"),
                "bodyText" to JsonPrimitive("See attached."),
                "isRead" to JsonPrimitive(false),
                "mimeStructure" to JsonPrimitive("multipart/mixed"),
                "rawHeaders" to JsonPrimitive("Received: from mx.example"),
                "providerLabels" to JsonPrimitive("IMPORTANT,CATEGORY_UPDATES"),
                "threadId" to JsonPrimitive("thread-7"),
            ),
        ),
    )

    override val requiredField: String = "subject"
    override val unprojectedField: String = "threadId"
}
