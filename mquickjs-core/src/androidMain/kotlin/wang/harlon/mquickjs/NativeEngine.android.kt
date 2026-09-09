package wang.harlon.mquickjs

internal actual class NativeEngine actual constructor(memoryBytes: Int, internal val host: HostCallbacks) {
    private var ptr: Long = NativeBridge.nativeCreate(memoryBytes, this)

    init {
        if (ptr == 0L) throw JsException("failed to create engine with $memoryBytes bytes")
    }

    actual fun evaluate(script: String, fileName: String): JsValue {
        val result = NativeBridge.nativeEval(ptr, Wtf8.encode(script), Wtf8.encode(fileName))
            ?: throw JsException("native evaluation failed")
        return result.toJsValue()
    }

    actual fun defineFunction(name: String, id: Int) {
        val result = NativeBridge.nativeDefineFunction(ptr, name.encodeToByteArray(), id)
            ?: throw JsException("native define failed")
        result.toJsValue()
    }

    actual fun interrupt() {
        val p = ptr
        if (p != 0L) NativeBridge.nativeInterrupt(p)
    }

    actual fun close() {
        val p = ptr
        if (p != 0L) {
            ptr = 0L
            NativeBridge.nativeDestroy(p)
        }
    }
}
