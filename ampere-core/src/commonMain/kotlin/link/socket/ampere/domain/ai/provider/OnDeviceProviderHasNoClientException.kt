package link.socket.ampere.domain.ai.provider

/**
 * Raised when something reads [AIProvider_OnDevice.client]. The on-device
 * provider executes through a bound `LocalInferenceEngine`, never through an
 * HTTP client, so there is no host this client could legitimately reach.
 */
class OnDeviceProviderHasNoClientException : IllegalStateException(
    "AIProvider_OnDevice has no network client: its models run on a bound " +
        "LocalInferenceEngine via DispatchingUpstreamLlmClient. A caller reading " +
        "'client' would send the prompt off the device (AMPR-371).",
)
