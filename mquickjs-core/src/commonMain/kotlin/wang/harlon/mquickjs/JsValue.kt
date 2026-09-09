package wang.harlon.mquickjs

/**
 * A JavaScript value crossing the engine boundary. Primitives are carried as-is;
 * objects and arrays travel as JSON text.
 */
public sealed interface JsValue {
    public object Undefined : JsValue {
        override fun toString(): String = "undefined"
    }

    public object Null : JsValue {
        override fun toString(): String = "null"
    }

    public data class Bool(val value: Boolean) : JsValue

    public data class Num(val value: Double) : JsValue {
        public constructor(value: Int) : this(value.toDouble())
    }

    public data class Str(val value: String) : JsValue

    /**
     * An object or array. [json] is null when the value cannot be serialized,
     * for example a function.
     */
    public data class Json(val json: String?) : JsValue
}
