package wang.harlon.mquickjs

/** A Kotlin function callable from JavaScript. Throwing propagates into JS as an `Error`. */
public fun interface JsHostFunction {
    public fun invoke(args: List<JsValue>): JsValue
}
