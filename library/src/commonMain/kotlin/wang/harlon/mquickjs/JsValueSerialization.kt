package wang.harlon.mquickjs

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

/**
 * Encodes [value] into a [JsValue]: primitives and null become their native counterparts,
 * objects and arrays become [JsValue.Json] text.
 */
fun <T> Json.encodeToJsValue(serializer: SerializationStrategy<T>, value: T): JsValue =
    encodeToJsonElement(serializer, value).toJsValue()

inline fun <reified T> Json.encodeToJsValue(value: T): JsValue =
    encodeToJsValue(serializersModule.serializer<T>(), value)

/**
 * Decodes [value] with [deserializer]. `undefined` decodes like `null`; a [JsRef] is decoded from
 * its `JSON.stringify` output.
 * @throws SerializationException when the value is not JSON-serializable (a function, for example)
 * or does not match [deserializer].
 */
fun <T> Json.decodeFromJsValue(deserializer: DeserializationStrategy<T>, value: JsValue): T {
    val element = when (value) {
        is JsValue.Json -> return decodeFromString(deserializer, value.json ?: throw notSerializable())
        is JsRef -> return decodeFromString(deserializer, value.toJson() ?: throw notSerializable())
        JsValue.Undefined, JsValue.Null -> JsonNull
        is JsValue.Bool -> JsonPrimitive(value.value)
        is JsValue.Str -> JsonPrimitive(value.value)
        is JsValue.Num -> value.value.toJsonPrimitive()
    }
    return decodeFromJsonElement(deserializer, element)
}

inline fun <reified T> Json.decodeFromJsValue(value: JsValue): T =
    decodeFromJsValue(serializersModule.serializer<T>(), value)

/** [Json.decodeFromJsValue] with [json], which defaults to [Json.Default]. */
inline fun <reified T> JsValue.decode(json: Json = Json.Default): T = json.decodeFromJsValue(this)

private fun notSerializable() = SerializationException("JS value is not JSON-serializable")

// 整数值按 Long 编码，否则 3.0 的 content 是 "3.0"，解成 Int 会失败；
// 2^63 经 toLong() 饱和后相等判断仍成立，所以还要卡在 Long 范围内
private fun Double.toJsonPrimitive(): JsonPrimitive {
    val whole = this >= Long.MIN_VALUE.toDouble() && this < Long.MAX_VALUE.toDouble() && this == toLong().toDouble()
    return if (whole) JsonPrimitive(toLong()) else JsonPrimitive(this)
}

private fun JsonElement.toJsValue(): JsValue = when (this) {
    JsonNull -> JsValue.Null
    is JsonPrimitive -> when {
        isString -> JsValue.Str(content)
        content == "true" -> JsValue.Bool(true)
        content == "false" -> JsValue.Bool(false)
        else -> JsValue.Num(content.toDouble())
    }
    is JsonObject, is JsonArray -> JsValue.Json(toString())
}
