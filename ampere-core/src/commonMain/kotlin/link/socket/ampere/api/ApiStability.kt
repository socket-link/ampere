package link.socket.ampere.api

/**
 * Marks a public API element as stable.
 *
 * Stable APIs follow semantic versioning: breaking changes only occur
 * in major version bumps. Source- and binary-compatible additions
 * (new methods with defaults, new optional parameters) may happen in
 * minor releases.
 */
@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
)
annotation class AmpereStableApi

/**
 * Marks a public API element as experimental.
 *
 * Experimental APIs may change or be removed in any release without
 * notice. Callers must opt in explicitly.
 */
@MustBeDocumented
@RequiresOptIn(
    message = "This API is experimental and may change without notice.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
)
annotation class AmpereExperimentalApi
