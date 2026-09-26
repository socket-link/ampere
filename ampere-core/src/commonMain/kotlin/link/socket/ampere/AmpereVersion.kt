package link.socket.ampere

/**
 * The Ampere version this build *is*.
 *
 * Kept in step with `ampereVersion` in `gradle.properties` — the published Maven
 * coordinate — by the `verifyAmpereVersionConstant` Gradle task, which fails the
 * build when the two drift. `KotlinConfig` is generated from `local.properties`
 * and carries developer-machine values, so it is not a home for a released fact.
 *
 * This is the **host runtime** version: the Ampere whose [link.socket.ampere.canon.CanonType]
 * entries the calling code compiled against. Per the version-skew contract in
 * `docs/concepts/domain-canon.md` it is the only version a canon consumer needs
 * — nothing carries a `canonVersion` and nothing should — which is why a
 * published bundle pins a minimum Ampere version rather than a canon revision.
 * See [link.socket.ampere.bundle.BundleManifest.minimumAmpereVersion].
 *
 * One constant, one fact: a second copy of the running version anywhere in the
 * tree is a drift bug waiting to happen. Callers that need to report the running
 * version (bundle import today, trace/store envelope tolerance next) reuse this
 * one.
 */
const val AMPERE_RUNTIME_VERSION: String = "0.15.0"

/**
 * Orders two Ampere versions, or returns `null` when either is not a version
 * this can order. Negative, zero, and positive have the usual [Comparator]
 * meaning: `compareAmpereVersions("0.16.0", "0.15.0") > 0`.
 *
 * Deliberately narrower than full semver:
 *
 * - Numeric release components are compared left to right and a missing
 *   component reads as `0`, so `"0.15"` and `"0.15.0"` compare equal. Manifests
 *   are hand-written; a two-component pin is a plausible thing for a plug author
 *   to write and means what it looks like.
 * - Pre-release and build metadata after the first `-` or `+` is **ignored**, so
 *   `"0.16.0-rc.1"` and `"0.16.0"` compare equal. A pin names the minimum
 *   *release* whose canon a bundle uses, and `0.16.0-rc.1` has the same
 *   `CanonType.entries` as `0.16.0`. Ordering them the semver way would make a
 *   host running its own release candidate reject a bundle pinned to the
 *   version that host is about to be.
 *
 * Returns `null` rather than throwing on anything else (`"latest"`, `"1.x"`,
 * `""`), leaving the caller to report a malformed version as a typed reason —
 * see [link.socket.ampere.plug.ManifestValidationReason.MalformedAmpereVersion].
 */
internal fun compareAmpereVersions(left: String, right: String): Int? {
    val leftParts = parseAmpereVersion(left) ?: return null
    val rightParts = parseAmpereVersion(right) ?: return null

    repeat(maxOf(leftParts.size, rightParts.size)) { index ->
        val comparison = (leftParts.getOrNull(index) ?: 0).compareTo(rightParts.getOrNull(index) ?: 0)
        if (comparison != 0) return comparison
    }
    return 0
}

/**
 * Splits the release components off a version string, or returns `null` when any
 * of them is not a non-negative integer.
 */
private fun parseAmpereVersion(value: String): List<Int>? {
    val release = value.trim().substringBefore('-').substringBefore('+')
    if (release.isEmpty()) return null
    return release.split('.').map { component ->
        component.toIntOrNull()?.takeIf { it >= 0 } ?: return null
    }
}
