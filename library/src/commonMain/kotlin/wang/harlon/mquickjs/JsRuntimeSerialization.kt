package wang.harlon.mquickjs

import kotlin.time.Duration
import kotlinx.serialization.json.Json

/** [JsRuntime.evaluate] followed by [JsValue.decode]. */
suspend inline fun <reified T> JsRuntime.evaluateAs(
    script: String,
    fileName: String = "<eval>",
    timeout: Duration? = null,
    json: Json = Json.Default,
): T = evaluate(script, fileName, timeout = timeout).decode(json)

/** [JsEngine.registerFunction] with typed arguments, with exclusive access. */
suspend inline fun <reified A, reified R> JsRuntime.registerFunction(
    name: String,
    json: Json = Json.Default,
    crossinline function: (A) -> R,
): Unit = withEngine { registerFunction(name, json, function) }

suspend inline fun <reified A, reified B, reified R> JsRuntime.registerFunction(
    name: String,
    json: Json = Json.Default,
    crossinline function: (A, B) -> R,
): Unit = withEngine { registerFunction(name, json, function) }

suspend inline fun <reified A, reified B, reified C, reified R> JsRuntime.registerFunction(
    name: String,
    json: Json = Json.Default,
    crossinline function: (A, B, C) -> R,
): Unit = withEngine { registerFunction(name, json, function) }
