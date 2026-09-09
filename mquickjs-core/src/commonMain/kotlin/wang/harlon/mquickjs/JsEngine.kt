package wang.harlon.mquickjs

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * One MicroQuickJS context. Not thread-safe: use it from a single thread, or serialize access
 * (see [JsRuntime]). [interrupt] is the only member safe to call from another thread.
 */
@OptIn(ExperimentalAtomicApi::class)
public class JsEngine(private val config: JsEngineConfig = JsEngineConfig()) : AutoCloseable {
    private val functions = ArrayList<JsHostFunction>()
    private val closed = AtomicBoolean(false)
    private val inFlightInterrupts = AtomicInt(0)

    private val callbacks = object : HostCallbacks {
        // 传给宿主函数的 ref 只在本次调用内有效：返回后失效，原生侧随即释放；retain() 的副本不受影响
        override fun onHostCall(id: Int, args: List<RawValue>): RawValue {
            val decoded = args.map { decode(it) }
            try {
                return encode(functions[id].invoke(decoded))
            } finally {
                decoded.forEach { (it as? JsRef)?.invalidate() }
            }
        }

        // logger 异常不能穿回原生回调（Kotlin/Native 会直接终止进程），三端统一吞掉
        override fun onLog(message: String) {
            try {
                config.logger?.invoke(message)
            } catch (_: Throwable) {
            }
        }
    }

    internal val native = NativeEngine(config.memoryBytes, callbacks)

    /**
     * Compiles and runs [script], returning the value of its last expression statement.
     * @throws JsException when the script throws, fails to parse, or exhausts memory.
     */
    public fun evaluate(
        script: String,
        fileName: String = "<eval>",
        objects: ObjectTransport = ObjectTransport.JSON,
    ): JsValue {
        checkOpen()
        return decode(native.evaluate(script, fileName, objects.flags))
    }

    /**
     * Exposes [function] to scripts as the global [name]. With [ObjectTransport.REF] the function
     * receives object arguments as [JsRef]s that live only for the duration of the call.
     */
    public fun registerFunction(
        name: String,
        objects: ObjectTransport = ObjectTransport.JSON,
        function: JsHostFunction,
    ) {
        checkOpen()
        require(isIdentifier(name)) { "'$name' is not a valid JavaScript identifier" }
        functions.add(function)
        try {
            decode(native.defineFunction(name, functions.size - 1, objects.flags))
        } catch (e: JsException) {
            functions.removeAt(functions.size - 1)
            throw e
        }
    }

    /**
     * Asks running script code to stop; the pending evaluation or call then throws [JsException].
     * Safe to call from any thread, including concurrently with [close].
     */
    public fun interrupt() {
        inFlightInterrupts.incrementAndFetch()
        try {
            if (!closed.load()) native.interrupt()
        } finally {
            inFlightInterrupts.decrementAndFetch()
        }
    }

    /** Releases every [JsRef] as well: the engine's whole memory goes away with it. */
    override fun close() {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        // an interrupt that passed the closed check must finish before the handle is freed
        while (inFlightInterrupts.load() != 0) {
        }
        native.close()
    }

    internal fun refOp(block: JsEngine.() -> RawValue?): JsValue {
        checkOpen()
        return block()?.let { decode(it) } ?: JsValue.Undefined
    }

    internal fun releaseRef(id: Int) {
        if (!closed.load()) native.refRelease(id)
    }

    internal fun checkOwned(ref: JsRef) {
        require(ref.engine === this) { "JsRef belongs to another engine" }
    }

    internal fun decode(raw: RawValue): JsValue = when (raw.tag) {
        NativeTag.UNDEFINED -> JsValue.Undefined
        NativeTag.NULL -> JsValue.Null
        NativeTag.BOOL -> JsValue.Bool(raw.num != 0.0)
        NativeTag.NUMBER -> JsValue.Num(raw.num)
        NativeTag.STRING -> JsValue.Str(raw.str ?: "")
        NativeTag.OBJECT -> JsValue.Json(raw.str)
        NativeTag.REF -> JsRef(this, raw.ref, raw.num.toInt())
        NativeTag.EXCEPTION -> throw JsException(raw.str ?: "unknown exception", raw.stack)
        else -> error("unknown native tag ${raw.tag}")
    }

    internal fun encode(value: JsValue): RawValue = when (value) {
        JsValue.Undefined -> RawValue(NativeTag.UNDEFINED)
        JsValue.Null -> RawValue(NativeTag.NULL)
        is JsValue.Bool -> RawValue(NativeTag.BOOL, num = if (value.value) 1.0 else 0.0)
        is JsValue.Num -> RawValue(NativeTag.NUMBER, num = value.value)
        is JsValue.Str -> RawValue(NativeTag.STRING, str = value.value)
        is JsValue.Json -> RawValue(NativeTag.OBJECT, str = value.json)
        is JsRef -> {
            checkOwned(value)
            check(value.isValid) { "JsRef is closed" }
            RawValue(NativeTag.REF, ref = value.id)
        }
    }

    private fun checkOpen() {
        check(!closed.load()) { "JsEngine is closed" }
    }

    private fun isIdentifier(name: String): Boolean =
        name.isNotEmpty() &&
            (name[0].isLetter() || name[0] == '_' || name[0] == '$') &&
            name.all { it.isLetterOrDigit() || it == '_' || it == '$' }
}

internal fun Throwable.toHostError(): RawValue = RawValue(NativeTag.EXCEPTION, str = hostErrorMessage())
