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

    actual fun refRetain(ref: Long) = NativeBridge.nativeRefRetain(ptr, ref)

    actual fun refRelease(ref: Long) = NativeBridge.nativeRefRelease(ptr, ref)

    actual fun refGet(ref: Long, name: String, flags: Int): RawValue =
        result(NativeBridge.nativeRefGet(ptr, ref, Wtf8.encode(name), flags))

    actual fun refGetIndex(ref: Long, index: Int, flags: Int): RawValue =
        result(NativeBridge.nativeRefGetIndex(ptr, ref, index, flags))

    actual fun refSet(ref: Long, name: String, value: RawValue): RawValue =
        result(NativeBridge.nativeRefSet(ptr, ref, Wtf8.encode(name), value.toNative()))

    actual fun refCall(ref: Long, thisRef: Long, args: List<RawValue>, flags: Int): RawValue =
        result(NativeBridge.nativeRefCall(ptr, ref, thisRef, Array(args.size) { args[it].toNative() }, flags))

    actual fun refToJson(ref: Long): RawValue = result(NativeBridge.nativeRefToJson(ptr, ref))

    actual fun stats(): IntArray = NativeBridge.nativeStats(ptr) ?: throw JsException("native call failed")

    actual fun dumpMemory(): RawValue = result(NativeBridge.nativeDumpMemory(ptr))

    actual fun loadBytecode(bytes: ByteArray): RawValue = result(NativeBridge.nativeLoadBytecode(ptr, bytes))

    actual fun runProgram(ref: Long, flags: Int): RawValue = result(NativeBridge.nativeRunProgram(ptr, ref, flags))
}

internal actual object NativeCompiler {
    actual fun wordSize(): Int = NativeBridge.nativeWordSize()

    actual fun compile(script: String, fileName: String, wordSize: Int, flags: Int): Any =
        when (val r = NativeBridge.nativeCompile(Wtf8.encode(script), Wtf8.encode(fileName), wordSize, flags)) {
            is ByteArray -> r
            is NativeValue -> r.toRaw()
            else -> throw JsException("native compile failed")
        }
}
