package wang.harlon.mquickjs.serialization

import kotlinx.serialization.json.Json
import wang.harlon.mquickjs.JsEngine
import wang.harlon.mquickjs.JsValue

/** [JsEngine.evaluate] followed by [JsValue.decode]. */
inline fun <reified T> JsEngine.evaluateAs(
    script: String,
    fileName: String = "<eval>",
    json: Json = Json.Default,
): T = evaluate(script, fileName).decode(json)

/**
 * Registers a host function whose arguments are decoded and whose result is encoded with [json].
 * A missing argument decodes like `undefined`; a decoding failure surfaces in JS as an `Error`.
 * Returning [Unit] yields `undefined`.
 */
inline fun <reified A, reified R> JsEngine.registerFunction(
    name: String,
    json: Json = Json.Default,
    crossinline function: (A) -> R,
) = registerFunction(name) { args ->
    json.encodeResult(function(json.argument<A>(args, 0)))
}

inline fun <reified A, reified B, reified R> JsEngine.registerFunction(
    name: String,
    json: Json = Json.Default,
    crossinline function: (A, B) -> R,
) = registerFunction(name) { args ->
    json.encodeResult(function(json.argument<A>(args, 0), json.argument<B>(args, 1)))
}

inline fun <reified A, reified B, reified C, reified R> JsEngine.registerFunction(
    name: String,
    json: Json = Json.Default,
    crossinline function: (A, B, C) -> R,
) = registerFunction(name) { args ->
    json.encodeResult(function(json.argument<A>(args, 0), json.argument<B>(args, 1), json.argument<C>(args, 2)))
}

@PublishedApi
internal inline fun <reified T> Json.argument(args: List<JsValue>, index: Int): T =
    decodeFromJsValue(args.getOrElse(index) { JsValue.Undefined })

@PublishedApi
internal inline fun <reified R> Json.encodeResult(result: R): JsValue =
    if (result is Unit) JsValue.Undefined else encodeToJsValue(result)
