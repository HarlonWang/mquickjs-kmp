package wang.harlon.mquickjs

/**
 * One MicroQuickJS context. Not thread-safe: use it from a single thread, or serialize access.
 * [interrupt] is the only member safe to call from another thread.
 */
public class JsEngine(private val config: JsEngineConfig = JsEngineConfig()) : AutoCloseable {
    private val functions = ArrayList<JsHostFunction>()
    private var closed = false

    private val callbacks = object : HostCallbacks {
        override fun onHostCall(id: Int, args: List<JsValue>): JsValue = functions[id].invoke(args)
        override fun onLog(message: String) {
            config.logger?.invoke(message)
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

    /** Asks running script code to stop; the pending [evaluate] then throws [JsException]. */
    public fun interrupt() {
        native.interrupt()
    }

    override fun close() {
        if (closed) return
        closed = true
        native.close()
    }

    private fun checkOpen() {
        check(!closed) { "JsEngine is closed" }
    }

    private fun isIdentifier(name: String): Boolean =
        name.isNotEmpty() &&
            (name[0].isLetter() || name[0] == '_' || name[0] == '$') &&
            name.all { it.isLetterOrDigit() || it == '_' || it == '$' }
}
