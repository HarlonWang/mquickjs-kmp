package wang.harlon.mquickjs

internal object NativeBridge {
    init {
        System.loadLibrary("mquickjs_kmp")
        val abi = nativeAbiVersion()
        check(abi == ABI_VERSION) { "libmquickjs_kmp ABI $abi does not match Kotlin side $ABI_VERSION" }
    }

    const val ABI_VERSION = 2

    @JvmStatic external fun nativeAbiVersion(): Int
    @JvmStatic external fun nativeCreate(memBytes: Int, target: Any): Long
    @JvmStatic external fun nativeDestroy(ptr: Long)
    @JvmStatic external fun nativeEval(ptr: Long, code: ByteArray, fileName: ByteArray, flags: Int): NativeValue?
    @JvmStatic external fun nativeDefineFunction(ptr: Long, name: ByteArray, id: Int, flags: Int): NativeValue?
    @JvmStatic external fun nativeInterrupt(ptr: Long)
    @JvmStatic external fun nativeRefRetain(ptr: Long, ref: Long)
    @JvmStatic external fun nativeRefRelease(ptr: Long, ref: Long)
    @JvmStatic external fun nativeRefGet(ptr: Long, ref: Long, name: ByteArray, flags: Int): NativeValue?
    @JvmStatic external fun nativeRefGetIndex(ptr: Long, ref: Long, index: Int, flags: Int): NativeValue?
    @JvmStatic external fun nativeRefSet(ptr: Long, ref: Long, name: ByteArray, value: NativeValue): NativeValue?
    @JvmStatic external fun nativeRefCall(ptr: Long, ref: Long, thisRef: Long, args: Array<NativeValue>, flags: Int): NativeValue?
    @JvmStatic external fun nativeRefToJson(ptr: Long, ref: Long): NativeValue?

    @JvmStatic
    fun onHostCall(target: Any, id: Int, args: Array<NativeValue?>): NativeValue {
        val engine = target as NativeEngine
        return try {
            engine.host.onHostCall(id, args.map { it?.toRaw() ?: RawValue(NativeTag.UNDEFINED) }).toNative()
        } catch (t: Throwable) {
            t.toHostError().toNative()
        }
    }

    @JvmStatic
    fun onLog(target: Any, message: ByteArray) {
        (target as NativeEngine).host.onLog(Wtf8.decode(message))
    }
}

internal fun NativeValue.toRaw(): RawValue =
    RawValue(tag, ref, num, str?.let(Wtf8::decode), stack?.let(Wtf8::decode))

internal fun RawValue.toNative(): NativeValue =
    NativeValue(tag, ref, num, str?.let(Wtf8::encode), stack?.let(Wtf8::encode))
