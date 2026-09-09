package wang.harlon.mquickjs

/** How JS objects and arrays cross into Kotlin. */
public enum class ObjectTransport {
    /** Serialized with `JSON.stringify` into [JsValue.Json]; nothing to release. */
    JSON,

    /** Handed out as a live [JsRef] that must be closed when no longer needed. */
    REF,
}
