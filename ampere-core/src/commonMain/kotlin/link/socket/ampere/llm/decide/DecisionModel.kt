package link.socket.ampere.llm.decide

import kotlinx.serialization.json.JsonElement
import link.socket.ampere.api.AmpereStableApi

/**
 * A decision model, named by the one who made it (AMPR-435).
 *
 * The maker is not the route: Jev is TypeSafe's whether the call travels on a
 * TypeSafe key or an OpenRouter one. [makerId] and [modelId] are what a
 * judgment's [ModelSnapshot] records as `providerId` and `modelId`, so records
 * of one model reached two ways join on them. The route the call travelled is
 * a [DecisionRoute], recorded as [ModelSnapshot.via].
 *
 * @property makerId Who made the model: `typesafe`, `cloudflare`.
 * @property modelId The maker's own id for the model, not a gateway's slug.
 */
@AmpereStableApi
data class DecisionModel(
    val makerId: String,
    val modelId: String,
) {
    init {
        require(makerId.isNotBlank()) { "A decision model needs a maker" }
        require(modelId.isNotBlank()) { "A decision model needs an id" }
    }
}

/** The decision models AMPERE has a name for. Naming one binds nothing. */
@AmpereStableApi
object DecisionModels {

    /** Jev 1.13, TypeSafe's System One model. TypeSafe's own id is `jev-1.13.0`. */
    val Jev_1_13: DecisionModel = DecisionModel(makerId = "typesafe", modelId = "jev-1.13.0")

    /** Clef, Cloudflare's 27B decision model, served on Workers AI. */
    val Clef: DecisionModel = DecisionModel(makerId = "cloudflare", modelId = "clef")

    /** Clef-flash, Cloudflare's fast 9B sibling of [Clef], served on Workers AI. */
    val ClefFlash: DecisionModel = DecisionModel(makerId = "cloudflare", modelId = "clef-flash")
}

/**
 * The way a decision call travels to its model (AMPR-435): adapter
 * configuration for [HostedSystemOneDecisionClient], never a second request
 * type. Every route POSTs the same System One body.
 *
 * A route whose [via] equals the model's [DecisionModel.makerId] is the direct
 * route; any other is a gateway.
 *
 * @property via Who carries the call: `openrouter`, `typesafe`, `cloudflare`.
 *   Recorded as [ModelSnapshot.via].
 * @property endpoint The full URL to POST to.
 * @property wireModelId The body's `"model"` field as this route spells it, or
 *   null when the route takes none. It wins over a `"model"` in [bodyExtras].
 * @property bodyExtras Any other fields this route wants in the body.
 */
@AmpereStableApi
data class DecisionRoute(
    val via: String,
    val endpoint: String,
    val wireModelId: String?,
    val bodyExtras: Map<String, JsonElement> = emptyMap(),
) {
    init {
        require(via.isNotBlank()) { "A decision route needs a carrier" }
        require(endpoint.isNotBlank()) { "A decision route needs an endpoint" }
    }
}

/**
 * The routes AMPERE has a name for, as each vendor documents them (read
 * 2026-10-11). A route holds no credential; the caller supplies one at bind.
 */
@AmpereStableApi
object DecisionRoutes {

    private const val OPENROUTER = "openrouter"
    private const val TYPESAFE = "typesafe"
    private const val CLOUDFLARE = "cloudflare"

    private const val OPENROUTER_DECISIONS = "https://openrouter.ai/api/alpha/decisions"
    private const val TYPESAFE_SYSTEM_ONE = "https://api.typesafe.ai/v1/systemone"
    private const val CLOUDFLARE_ACCOUNTS = "https://api.cloudflare.com/client/v4/accounts"

    /** [DecisionModels.Jev_1_13] through OpenRouter's Decisions API, billed to an OpenRouter key. */
    fun jevViaOpenRouter(): DecisionRoute = DecisionRoute(
        via = OPENROUTER,
        endpoint = OPENROUTER_DECISIONS,
        wireModelId = "typesafe/jev-1.13",
    )

    /**
     * Jev direct from TypeSafe, on a TypeSafe key.
     *
     * @param modelId TypeSafe's model id: the pinned `jev-1.13.0`, or the
     *   `jev-latest` / `jev-preview` aliases. The response's own `model` string
     *   lands on [ModelSnapshot.revision], so an alias is still resolved there.
     */
    fun jevViaTypeSafe(modelId: String = "jev-1.13.0"): DecisionRoute = DecisionRoute(
        via = TYPESAFE,
        endpoint = TYPESAFE_SYSTEM_ONE,
        wireModelId = modelId,
    )

    /**
     * [DecisionModels.Clef] (or [DecisionModels.ClefFlash] when [flash]) on
     * Cloudflare Workers AI, wrapped in Cloudflare's REST envelope.
     *
     * @param accountId The Cloudflare account the call is billed to. Never
     *   committed; the caller supplies it.
     */
    fun clefOnWorkersAi(accountId: String, flash: Boolean = false): DecisionRoute {
        require(accountId.isNotBlank()) { "Workers AI needs an account id" }
        val model = if (flash) DecisionModels.ClefFlash else DecisionModels.Clef
        return DecisionRoute(
            via = CLOUDFLARE,
            endpoint = "$CLOUDFLARE_ACCOUNTS/$accountId/ai/run/@cf/cloudflare/${model.modelId}",
            wireModelId = model.modelId,
        )
    }
}
