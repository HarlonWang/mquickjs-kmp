package wang.harlon.mquickjs

internal interface HostCallbacks {
    fun onHostCall(id: Int, args: List<RawValue>): RawValue
    fun onLog(message: String)
}

/** Mirror of `kmpjs_value` (native/shim/mquickjs_kmp.h); the only shape that crosses the native boundary. */
internal class RawValue(
    val tag: Int,
    val ref: Long = 0L,
    val num: Double = 0.0,
    val str: String? = null,
    val stack: String? = null,
)

/** Tags and flags mirror KMPJS_TAG_* / KMPJS_FLAG_* / KMPJS_REF_* in native/shim/mquickjs_kmp.h. */
internal object NativeTag {
    const val UNDEFINED = 0
    const val NULL = 1
    const val BOOL = 2
    const val NUMBER = 3
    const val STRING = 4
    const val OBJECT = 5
    const val EXCEPTION = 6
    const val REF = 7

    const val FLAG_REF_OBJECTS = 1
    const val REF_FUNCTION = 1
    const val REF_ARRAY = 2
    const val COMPILE_STRIP_COLUMNS = 1
}

internal expect class NativeEngine(memoryBytes: Int, host: HostCallbacks) {
    fun evaluate(script: String, fileName: String, flags: Int): RawValue
    fun defineFunction(name: String, id: Int, flags: Int): RawValue
    fun interrupt()
    fun close()

    fun refRetain(ref: Long)
    fun refRelease(ref: Long)
    fun refGet(ref: Long, name: String, flags: Int): RawValue
    fun refGetIndex(ref: Long, index: Int, flags: Int): RawValue
    fun refSet(ref: Long, name: String, value: RawValue): RawValue
    fun refCall(ref: Long, thisRef: Long, args: List<RawValue>, flags: Int): RawValue
    fun refToJson(ref: Long): RawValue

    /** [liveRefs, refSlots] as in kmpjs_stats. */
    fun stats(): IntArray
    fun dumpMemory(): RawValue

    fun loadBytecode(bytes: ByteArray): RawValue
    fun runProgram(ref: Long, flags: Int): RawValue
}

internal expect object NativeCompiler {
    fun wordSize(): Int

    /** The bytecode on success, or a [RawValue] carrying the error. */
    fun compile(script: String, fileName: String, wordSize: Int, flags: Int): Any
}

internal fun Throwable.hostErrorMessage(): String = message ?: this::class.simpleName ?: "host error"
