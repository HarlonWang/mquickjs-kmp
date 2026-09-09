package wang.harlon.mquickjs

internal object NativeBridge {
    init {
        System.loadLibrary("mquickjs_kmp")
        val abi = nativeAbiVersion()
        check(abi == ABI_VERSION) { "libmquickjs_kmp ABI $abi does not match Kotlin side $ABI_VERSION" }
    }

    const val ABI_VERSION = 1

    @JvmStatic external fun nativeAbiVersion(): Int
    @JvmStatic external fun nativeCreate(memBytes: Int, target: Any): Long
    @JvmStatic external fun nativeDestroy(ptr: Long)
    @JvmStatic external fun nativeEval(ptr: Long, code: ByteArray, fileName: ByteArray): NativeValue?
    @JvmStatic external fun nativeDefineFunction(ptr: Long, name: ByteArray, id: Int): NativeValue?
    @JvmStatic external fun nativeInterrupt(ptr: Long)

    @JvmStatic
    fun onHostCall(target: Any, id: Int, args: Array<NativeValue?>): NativeValue {
        val engine = target as NativeEngine
        return try {
            val list = args.map { it?.toJsValue() ?: JsValue.Undefined }
            engine.host.onHostCall(id, list).toNative()
        } catch (t: Throwable) {
            NativeValue(NativeTag.EXCEPTION, 0.0, Wtf8.encode(t.hostErrorMessage()), null)
        }
    }

    @JvmStatic
    fun onLog(target: Any, message: ByteArray) {
        (target as NativeEngine).host.onLog(Wtf8.decode(message))
    }
}

internal fun NativeValue.toJsValue(): JsValue =
    decodeNativeValue(tag, num, str?.let(Wtf8::decode), stack?.let(Wtf8::decode))

internal fun JsValue.toNative(): NativeValue {
    val encoded = encodeNativeValue(this)
    return NativeValue(encoded.tag, encoded.num, encoded.str?.let(Wtf8::encode), null)
}
