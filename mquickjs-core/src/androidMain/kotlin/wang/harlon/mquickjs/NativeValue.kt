package wang.harlon.mquickjs

/** Constructed and read by native/jni/mquickjs_jni.c; field names and the constructor signature are ABI. */
internal class NativeValue(
    @JvmField val tag: Int,
    @JvmField val num: Double,
    @JvmField val str: ByteArray?,
    @JvmField val stack: ByteArray?,
)
