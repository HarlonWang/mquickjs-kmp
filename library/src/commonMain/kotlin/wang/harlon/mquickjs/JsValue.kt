package wang.harlon.mquickjs

/**
 * A JavaScript value crossing the engine boundary. Primitives are carried as-is;
 * objects and arrays travel as JSON text ([Json]) or as live handles ([JsRef]).
 */
sealed interface JsValue {
    object Undefined : JsValue {
        override fun toString(): String = "undefined"
    }

    object Null : JsValue {
        override fun toString(): String = "null"
    }

    data class Bool(val value: Boolean) : JsValue

    data class Num(val value: Double) : JsValue {
        constructor(value: Int) : this(value.toDouble())
    }

    data class Str(val value: String) : JsValue

    /**
     * An object or array. [json] is null when the value cannot be serialized,
     * for example a function.
     */
    data class Json(val json: String?) : JsValue
}
