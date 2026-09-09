package wang.harlon.mquickjs

/**
 * A loaded bytecode program. [run] executes its top level like [JsEngine.evaluate] would for the
 * source, and may be called more than once. Close it when done; the engine keeps the bytecode
 * itself for its whole lifetime.
 */
class JsProgram internal constructor(private val engine: JsEngine, private val id: Long) : AutoCloseable {
    private var closed = false

    fun run(objects: ObjectTransport = ObjectTransport.JSON): JsValue {
        check(!closed) { "JsProgram is closed" }
        return engine.refOp { native.runProgram(id, objects.flags) }
    }

    override fun close() {
        if (closed) return
        closed = true
        engine.releaseRef(id)
    }
}
