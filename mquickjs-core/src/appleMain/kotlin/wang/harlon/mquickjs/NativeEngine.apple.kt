package wang.harlon.mquickjs

import cnames.structs.kmpjs_engine
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import platform.posix.memcpy
import wang.harlon.mquickjs.cinterop.KMPJS_ABI_VERSION
import wang.harlon.mquickjs.cinterop.kmpjs_abi_version
import wang.harlon.mquickjs.cinterop.kmpjs_alloc
import wang.harlon.mquickjs.cinterop.kmpjs_create
import wang.harlon.mquickjs.cinterop.kmpjs_define_function
import wang.harlon.mquickjs.cinterop.kmpjs_destroy
import wang.harlon.mquickjs.cinterop.kmpjs_dump_memory
import wang.harlon.mquickjs.cinterop.kmpjs_get_stats
import wang.harlon.mquickjs.cinterop.kmpjs_eval
import wang.harlon.mquickjs.cinterop.kmpjs_free
import wang.harlon.mquickjs.cinterop.kmpjs_interrupt
import wang.harlon.mquickjs.cinterop.kmpjs_ref_call
import wang.harlon.mquickjs.cinterop.kmpjs_ref_get
import wang.harlon.mquickjs.cinterop.kmpjs_ref_get_index
import wang.harlon.mquickjs.cinterop.kmpjs_ref_release
import wang.harlon.mquickjs.cinterop.kmpjs_ref_retain
import wang.harlon.mquickjs.cinterop.kmpjs_ref_set
import wang.harlon.mquickjs.cinterop.kmpjs_ref_to_json
import wang.harlon.mquickjs.cinterop.kmpjs_stats
import wang.harlon.mquickjs.cinterop.kmpjs_compile
import wang.harlon.mquickjs.cinterop.kmpjs_load_bytecode
import wang.harlon.mquickjs.cinterop.kmpjs_run_program
import wang.harlon.mquickjs.cinterop.kmpjs_word_size
import wang.harlon.mquickjs.cinterop.kmpjs_value

@OptIn(ExperimentalForeignApi::class)
internal actual class NativeEngine actual constructor(memoryBytes: Int, internal val host: HostCallbacks) {
    private val ref = StableRef.create(this)
    private var engine: CPointer<kmpjs_engine>? = null

    init {
        check(kmpjs_abi_version() == KMPJS_ABI_VERSION) { "libmquickjs_kmp ABI mismatch" }
        engine = kmpjs_create(memoryBytes, ref.asCPointer(), hostCallback, logCallback)
        if (engine == null) {
            ref.dispose()
            throw JsException("failed to create engine with $memoryBytes bytes")
        }
    }

    private fun handle(): CPointer<kmpjs_engine> = engine ?: throw IllegalStateException("engine closed")

    actual fun evaluate(script: String, fileName: String, flags: Int): RawValue = memScoped {
        val out = alloc<kmpjs_value>()
        val name = cString(fileName)
        val bytes = Wtf8.encode(script)
        if (bytes.isEmpty()) {
            kmpjs_eval(handle(), null, 0, name, flags, out.ptr)
        } else {
            bytes.usePinned { pinned ->
                kmpjs_eval(handle(), pinned.addressOf(0), bytes.size, name, flags, out.ptr)
            }
        }
        out.toRaw()
    }

    actual fun defineFunction(name: String, id: Int, flags: Int): RawValue = memScoped {
        val out = alloc<kmpjs_value>()
        kmpjs_define_function(handle(), cString(name), id, flags, out.ptr)
        out.toRaw()
    }

    actual fun interrupt() {
        engine?.let { kmpjs_interrupt(it) }
    }

    actual fun close() {
        val e = engine ?: return
        engine = null
        kmpjs_destroy(e)
        ref.dispose()
    }

    actual fun refRetain(ref: Long) {
        kmpjs_ref_retain(handle(), ref)
    }

    actual fun refRelease(ref: Long) {
        engine?.let { kmpjs_ref_release(it, ref) }
    }

    actual fun refGet(ref: Long, name: String, flags: Int): RawValue = memScoped {
        val out = alloc<kmpjs_value>()
        kmpjs_ref_get(handle(), ref, cString(name), flags, out.ptr)
        out.toRaw()
    }

    actual fun refGetIndex(ref: Long, index: Int, flags: Int): RawValue = memScoped {
        val out = alloc<kmpjs_value>()
        kmpjs_ref_get_index(handle(), ref, index, flags, out.ptr)
        out.toRaw()
    }

    actual fun refSet(ref: Long, name: String, value: RawValue): RawValue = memScoped {
        val out = alloc<kmpjs_value>()
        val v = alloc<kmpjs_value>()
        value.writeTo(v)
        kmpjs_ref_set(handle(), ref, cString(name), v.ptr, out.ptr)
        v.freePayload()
        out.toRaw()
    }

    actual fun refCall(ref: Long, thisRef: Long, args: List<RawValue>, flags: Int): RawValue = memScoped {
        val out = alloc<kmpjs_value>()
        val values = allocArray<kmpjs_value>(args.size)
        args.forEachIndexed { i, raw -> raw.writeTo(values[i]) }
        kmpjs_ref_call(handle(), ref, thisRef, if (args.isEmpty()) null else values, args.size, flags, out.ptr)
        for (i in args.indices) values[i].freePayload()
        out.toRaw()
    }

    actual fun refToJson(ref: Long): RawValue = memScoped {
        val out = alloc<kmpjs_value>()
        kmpjs_ref_to_json(handle(), ref, out.ptr)
        out.toRaw()
    }

    actual fun stats(): IntArray = memScoped {
        val st = alloc<kmpjs_stats>()
        kmpjs_get_stats(handle(), st.ptr)
        intArrayOf(st.live_refs, st.ref_slots)
    }

    actual fun dumpMemory(): RawValue = memScoped {
        val out = alloc<kmpjs_value>()
        kmpjs_dump_memory(handle(), out.ptr)
        out.toRaw()
    }

    actual fun loadBytecode(bytes: ByteArray): RawValue = memScoped {
        val out = alloc<kmpjs_value>()
        if (bytes.isEmpty()) {
            kmpjs_load_bytecode(handle(), null, 0, out.ptr)
        } else {
            bytes.usePinned { kmpjs_load_bytecode(handle(), it.addressOf(0).reinterpret(), bytes.size, out.ptr) }
        }
        out.toRaw()
    }

    actual fun runProgram(ref: Long, flags: Int): RawValue = memScoped {
        val out = alloc<kmpjs_value>()
        kmpjs_run_program(handle(), ref, flags, out.ptr)
        out.toRaw()
    }

    private companion object {
        // 任何异常都不能离开 staticCFunction：Kotlin/Native 异常越过 C 边界会终止进程
        val hostCallback = staticCFunction { user: COpaquePointer?, id: Int, args: CPointer<kmpjs_value>?, argc: Int, result: CPointer<kmpjs_value>? ->
            val out = result!!.pointed
            try {
                val engine = user!!.asStableRef<NativeEngine>().get()
                val list = List(argc) { args!![it].toRaw() }
                val raw = engine.host.onHostCall(id, list)
                raw.writeTo(out)
                if (raw.tag == NativeTag.EXCEPTION) 1 else 0
            } catch (t: Throwable) {
                t.toHostError().writeTo(out)
                1
            }
        }

        val logCallback = staticCFunction { user: COpaquePointer?, msg: CPointer<kotlinx.cinterop.ByteVar>?, len: Int ->
            try {
                val engine = user!!.asStableRef<NativeEngine>().get()
                val text = if (msg == null || len <= 0) "" else Wtf8.decode(msg.readBytes(len))
                engine.host.onLog(text)
            } catch (_: Throwable) {
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual object NativeCompiler {
    actual fun wordSize(): Int = kmpjs_word_size()

    actual fun compile(script: String, fileName: String, wordSize: Int, flags: Int): Any = memScoped {
        val out = alloc<kmpjs_value>()
        val name = cString(fileName)
        val code = Wtf8.encode(script)
        val rc = if (code.isEmpty()) {
            kmpjs_compile(null, 0, name, wordSize, flags, out.ptr)
        } else {
            code.usePinned { kmpjs_compile(it.addressOf(0), code.size, name, wordSize, flags, out.ptr) }
        }
        val result: Any = if (rc == 0) {
            out.str?.readBytes(out.str_len) ?: ByteArray(0)
        } else {
            out.toRaw()
        }
        kmpjs_free(out.str)
        kmpjs_free(out.stack)
        result
    }
}

/** WTF-8 + NUL, so file names and property keys with lone surrogates reach the engine intact. */
@OptIn(ExperimentalForeignApi::class)
private fun MemScope.cString(text: String): CPointer<kotlinx.cinterop.ByteVar> {
    val bytes = Wtf8.encode(text)
    val arr = allocArray<kotlinx.cinterop.ByteVar>(bytes.size + 1)
    bytes.forEachIndexed { i, b -> arr[i] = b }
    arr[bytes.size] = 0
    return arr
}

@OptIn(ExperimentalForeignApi::class)
private fun kmpjs_value.toRaw(): RawValue = RawValue(
    tag,
    ref,
    num,
    str?.readBytes(str_len)?.let(Wtf8::decode),
    stack?.readBytes(stack_len)?.let(Wtf8::decode),
)

/** String payloads come from kmpjs_alloc; the engine frees host results, [freePayload] frees the rest. */
@OptIn(ExperimentalForeignApi::class)
private fun RawValue.writeTo(out: kmpjs_value) {
    out.tag = tag
    out.ref = ref
    out.num = num
    out.str = null
    out.str_len = 0
    out.stack = null
    out.stack_len = 0
    str?.let { text ->
        val bytes = Wtf8.encode(text)
        val buf = kmpjs_alloc(bytes.size) ?: return
        if (bytes.isNotEmpty()) {
            bytes.usePinned { memcpy(buf, it.addressOf(0), bytes.size.toULong()) }
        }
        out.str = buf
        out.str_len = bytes.size
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun kmpjs_value.freePayload() {
    kmpjs_free(str)
    kmpjs_free(stack)
    str = null
    stack = null
}
