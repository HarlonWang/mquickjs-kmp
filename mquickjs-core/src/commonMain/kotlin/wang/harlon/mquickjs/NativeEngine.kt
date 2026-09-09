package wang.harlon.mquickjs

internal interface HostCallbacks {
    fun onHostCall(id: Int, args: List<JsValue>): JsValue
    fun onLog(message: String)
}

internal expect class NativeEngine(memoryBytes: Int, host: HostCallbacks) {
    fun evaluate(script: String, fileName: String): JsValue
    fun defineFunction(name: String, id: Int)
    fun interrupt()
    fun close()
}

/** Tags mirror KMPJS_TAG_* in native/shim/mquickjs_kmp.h. */
internal object NativeTag {
    const val UNDEFINED = 0
    const val NULL = 1
    const val BOOL = 2
    const val NUMBER = 3
    const val STRING = 4
    const val OBJECT = 5
    const val EXCEPTION = 6
}

internal fun decodeNativeValue(tag: Int, num: Double, str: String?, stack: String?): JsValue = when (tag) {
    NativeTag.UNDEFINED -> JsValue.Undefined
    NativeTag.NULL -> JsValue.Null
    NativeTag.BOOL -> JsValue.Bool(num != 0.0)
    NativeTag.NUMBER -> JsValue.Num(num)
    NativeTag.STRING -> JsValue.Str(str ?: "")
    NativeTag.OBJECT -> JsValue.Json(str)
    NativeTag.EXCEPTION -> throw JsException(str ?: "unknown exception", stack)
    else -> error("unknown native tag $tag")
}

internal class EncodedValue(val tag: Int, val num: Double, val str: String?)

internal fun encodeNativeValue(value: JsValue): EncodedValue = when (value) {
    JsValue.Undefined -> EncodedValue(NativeTag.UNDEFINED, 0.0, null)
    JsValue.Null -> EncodedValue(NativeTag.NULL, 0.0, null)
    is JsValue.Bool -> EncodedValue(NativeTag.BOOL, if (value.value) 1.0 else 0.0, null)
    is JsValue.Num -> EncodedValue(NativeTag.NUMBER, value.value, null)
    is JsValue.Str -> EncodedValue(NativeTag.STRING, 0.0, value.value)
    is JsValue.Json -> EncodedValue(NativeTag.OBJECT, 0.0, value.json)
}

internal fun Throwable.hostErrorMessage(): String = message ?: this::class.simpleName ?: "host error"
