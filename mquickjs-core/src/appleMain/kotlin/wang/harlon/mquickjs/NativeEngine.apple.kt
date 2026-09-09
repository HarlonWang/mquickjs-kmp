package wang.harlon.mquickjs

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import platform.posix.memcpy
import wang.harlon.mquickjs.cinterop.KMPJS_ABI_VERSION
import wang.harlon.mquickjs.cinterop.kmpjs_abi_version
import wang.harlon.mquickjs.cinterop.kmpjs_alloc
import wang.harlon.mquickjs.cinterop.kmpjs_create
import wang.harlon.mquickjs.cinterop.kmpjs_define_function
import wang.harlon.mquickjs.cinterop.kmpjs_destroy
import cnames.structs.kmpjs_engine
import wang.harlon.mquickjs.cinterop.kmpjs_eval
import wang.harlon.mquickjs.cinterop.kmpjs_interrupt
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

    actual fun evaluate(script: String, fileName: String): JsValue = memScoped {
        val out = alloc<kmpjs_value>()
        val name = fileName.cstr.ptr
        val bytes = script.encodeToByteArray()
        if (bytes.isEmpty()) {
            kmpjs_eval(handle(), null, 0, name, out.ptr)
        } else {
            bytes.usePinned { pinned ->
                kmpjs_eval(handle(), pinned.addressOf(0), bytes.size, name, out.ptr)
            }
        }
        out.toJsValue()
    }

    actual fun defineFunction(name: String, id: Int): Unit = memScoped {
        val out = alloc<kmpjs_value>()
        if (kmpjs_define_function(handle(), name, id, out.ptr) != 0) {
            out.toJsValue()
        }
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

    private companion object {
        val hostCallback = staticCFunction { user: COpaquePointer?, id: Int, args: CPointer<kmpjs_value>?, argc: Int, result: CPointer<kmpjs_value>? ->
            val engine = user!!.asStableRef<NativeEngine>().get()
            val out = result!!.pointed
            try {
                val list = List(argc) { args!![it].toJsValue() }
                engine.host.onHostCall(id, list).writeTo(out)
                0
            } catch (t: Throwable) {
                out.tag = NativeTag.EXCEPTION
                out.writeStr(t.hostErrorMessage())
                1
            }
        }

        val logCallback = staticCFunction { user: COpaquePointer?, msg: CPointer<kotlinx.cinterop.ByteVar>?, len: Int ->
            val engine = user!!.asStableRef<NativeEngine>().get()
            val text = if (msg == null || len <= 0) "" else msg.readBytes(len).decodeToString()
            engine.host.onLog(text)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun kmpjs_value.toJsValue(): JsValue = decodeNativeValue(
    tag,
    num,
    str?.readBytes(str_len)?.decodeToString(),
    stack?.readBytes(stack_len)?.decodeToString(),
)

@OptIn(ExperimentalForeignApi::class)
private fun JsValue.writeTo(out: kmpjs_value) {
    val encoded = encodeNativeValue(this)
    out.tag = encoded.tag
    out.num = encoded.num
    out.str = null
    out.str_len = 0
    out.stack = null
    out.stack_len = 0
    encoded.str?.let { out.writeStr(it) }
}

@OptIn(ExperimentalForeignApi::class)
private fun kmpjs_value.writeStr(text: String) {
    val bytes = text.encodeToByteArray()
    val buf = kmpjs_alloc(bytes.size) ?: return
    if (bytes.isNotEmpty()) {
        bytes.usePinned { memcpy(buf, it.addressOf(0), bytes.size.toULong()) }
    }
    str = buf
    str_len = bytes.size
}
