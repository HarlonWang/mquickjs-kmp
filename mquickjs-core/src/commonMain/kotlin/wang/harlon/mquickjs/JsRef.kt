package wang.harlon.mquickjs

/**
 * A live handle to a JS object, array or function owned by [engine]. The object stays alive in the
 * engine's fixed memory until [close]; leaking refs therefore leaks JS heap. Bound to the engine's
 * threading rules like every other engine call.
 *
 * Refs received as host-function arguments are valid only for the duration of that call;
 * call [retain] to keep one.
 */
public class JsRef internal constructor(
    internal val engine: JsEngine,
    internal val id: Int,
    kind: Int,
) : JsValue, AutoCloseable {
    public val isFunction: Boolean = kind and NativeTag.REF_FUNCTION != 0
    public val isArray: Boolean = kind and NativeTag.REF_ARRAY != 0

    private var closed = false

    public fun get(name: String, objects: ObjectTransport = ObjectTransport.REF): JsValue =
        engine.refOp { native.refGet(id, name, objects.flags) }

    public fun get(index: Int, objects: ObjectTransport = ObjectTransport.REF): JsValue =
        engine.refOp { native.refGetIndex(id, index, objects.flags) }

    public fun set(name: String, value: JsValue) {
        engine.refOp { native.refSet(id, name, encode(value)) }
    }

    /** Calls this function with `this` undefined. */
    public fun call(vararg args: JsValue): JsValue = invoke(null, args.toList())

    public fun invoke(
        thisArg: JsRef?,
        args: List<JsValue>,
        objects: ObjectTransport = ObjectTransport.REF,
    ): JsValue = engine.refOp {
        thisArg?.let { checkOwned(it) }
        native.refCall(id, thisArg?.id ?: 0, args.map { encode(it) }, objects.flags)
    }

    /** `JSON.stringify` of the object, or null when it cannot be serialized. */
    public fun toJson(): String? = (engine.refOp { native.refToJson(id) } as JsValue.Json).json

    /** Keeps a transient host-function argument alive beyond the call; the caller now owns a close. */
    public fun retain(): JsRef {
        engine.refOp { native.refRetain(id); null }
        return JsRef(engine, id, (if (isFunction) NativeTag.REF_FUNCTION else 0) or (if (isArray) NativeTag.REF_ARRAY else 0))
    }

    override fun close() {
        if (closed) return
        closed = true
        engine.releaseRef(id)
    }

    override fun toString(): String = "JsRef(id=$id, function=$isFunction, array=$isArray)"
}

internal val ObjectTransport.flags: Int
    get() = if (this == ObjectTransport.REF) NativeTag.FLAG_REF_OBJECTS else 0
