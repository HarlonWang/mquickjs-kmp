package wang.harlon.mquickjs

/**
 * Entry point metadata for the MQuickJS Kotlin Multiplatform bindings.
 */
public object MQuickJs {
    /** Version of this SDK, as published to Maven Central. */
    public val sdkVersion: String = BuildInfo.SDK_VERSION

    /** Full git commit of the vendored bellard/mquickjs engine this SDK was built from. */
    public val upstreamCommit: String = BuildInfo.UPSTREAM_COMMIT
}
