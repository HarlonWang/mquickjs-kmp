package wang.harlon.mquickjs

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * One MicroQuickJS context. Not thread-safe: use it from a single thread, or serialize access.
 * [interrupt] is the only member safe to call from another thread.
 */
@OptIn(ExperimentalAtomicApi::class)
public class JsEngine(private val config: JsEngineConfig = JsEngineConfig()) : AutoCloseable {
    private val functions = ArrayList<JsHostFunction>()
    private val closed = AtomicBoolean(false)
    private val inFlightInterrupts = AtomicInt(0)

    private val callbacks = object : HostCallbacks {
        override fun onHostCall(id: Int, args: List<JsValue>): JsValue = functions[id].invoke(args)
        // logger 异常不能穿回原生回调（Kotlin/Native 会直接终止进程），三端统一吞掉
        override fun onLog(message: String) {
            try {
                config.logger?.invoke(message)
            } catch (_: Throwable) {
            }
        }
    }

    private val native = NativeEngine(config.memoryBytes, callbacks)

    /**
     * Compiles and runs [script], returning the value of its last expression statement.
     * @throws JsException when the script throws, fails to parse, or exhausts memory.
     */
    public fun evaluate(script: String, fileName: String = "<eval>"): JsValue {
        checkOpen()
        return native.evaluate(script, fileName)
    }

    /** Exposes [function] to scripts as the global [name]. */
    public fun registerFunction(name: String, function: JsHostFunction) {
        checkOpen()
        require(isIdentifier(name)) { "'$name' is not a valid JavaScript identifier" }
        functions.add(function)
        try {
            native.defineFunction(name, functions.size - 1)
        } catch (e: JsException) {
            functions.removeAt(functions.size - 1)
            throw e
        }
    }

    /**
     * Asks running script code to stop; the pending [evaluate] then throws [JsException].
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

    override fun close() {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        // an interrupt that passed the closed check must finish before the handle is freed
        while (inFlightInterrupts.load() != 0) {
        }
        native.close()
    }

    private fun checkOpen() {
        check(!closed.load()) { "JsEngine is closed" }
    }

    private fun isIdentifier(name: String): Boolean =
        name.isNotEmpty() &&
            (name[0].isLetter() || name[0] == '_' || name[0] == '$') &&
            name.all { it.isLetterOrDigit() || it == '_' || it == '$' }
}
