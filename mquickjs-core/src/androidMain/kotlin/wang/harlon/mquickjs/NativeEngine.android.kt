package wang.harlon.mquickjs

internal actual class NativeEngine actual constructor(memoryBytes: Int, internal val host: HostCallbacks) {
    private var ptr: Long = NativeBridge.nativeCreate(memoryBytes, this)

    init {
        if (ptr == 0L) throw JsException("failed to create engine with $memoryBytes bytes")
    }

    private fun result(value: NativeValue?): RawValue = value?.toRaw() ?: throw JsException("native call failed")

    actual fun evaluate(script: String, fileName: String, flags: Int): RawValue =
        result(NativeBridge.nativeEval(ptr, Wtf8.encode(script), Wtf8.encode(fileName), flags))

    actual fun defineFunction(name: String, id: Int, flags: Int): RawValue =
        result(NativeBridge.nativeDefineFunction(ptr, name.encodeToByteArray(), id, flags))

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

    actual fun refRetain(ref: Int) = NativeBridge.nativeRefRetain(ptr, ref)

    actual fun refRelease(ref: Int) = NativeBridge.nativeRefRelease(ptr, ref)

    actual fun refGet(ref: Int, name: String, flags: Int): RawValue =
        result(NativeBridge.nativeRefGet(ptr, ref, Wtf8.encode(name), flags))

    actual fun refGetIndex(ref: Int, index: Int, flags: Int): RawValue =
        result(NativeBridge.nativeRefGetIndex(ptr, ref, index, flags))

    actual fun refSet(ref: Int, name: String, value: RawValue): RawValue =
        result(NativeBridge.nativeRefSet(ptr, ref, Wtf8.encode(name), value.toNative()))

    actual fun refCall(ref: Int, thisRef: Int, args: List<RawValue>, flags: Int): RawValue =
        result(NativeBridge.nativeRefCall(ptr, ref, thisRef, Array(args.size) { args[it].toNative() }, flags))

    actual fun refToJson(ref: Int): RawValue = result(NativeBridge.nativeRefToJson(ptr, ref))
}
