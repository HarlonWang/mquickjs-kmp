package wang.harlon.mquickjs

/** A Kotlin function callable from JavaScript. Throwing propagates into JS as an `Error`. */
fun interface JsHostFunction {
    fun invoke(args: List<JsValue>): JsValue
}
