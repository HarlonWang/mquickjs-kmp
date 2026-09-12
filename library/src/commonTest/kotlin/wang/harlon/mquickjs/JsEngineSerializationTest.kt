package wang.harlon.mquickjs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class JsEngineSerializationTest {
    @Test
    fun evaluatesIntoTypedValues() = JsEngine().use { engine ->
        assertEquals(User(1, "a", listOf("x")), engine.evaluateAs("({id: 1, name: 'a', tags: ['x']})"))
        assertEquals(listOf(1, 2, 3), engine.evaluateAs("[1, 2, 3]"))
        assertEquals(42, engine.evaluateAs("6 * 7"))
        assertEquals("hi", engine.evaluateAs("'h' + 'i'"))
        assertEquals(Status.DISABLED, engine.evaluateAs("'DISABLED'"))
        assertEquals(null, engine.evaluateAs<User?>("undefined"))
        assertEquals(mapOf("a" to 1, "b" to 2), engine.evaluateAs("({a: 1, b: 2})"))
    }

    @Test
    fun evaluateAsReportsMismatch() = JsEngine().use { engine ->
        assertFailsWith<SerializationException> { engine.evaluateAs<User>("({id: 'one'})") }
        assertFailsWith<SerializationException> { engine.evaluateAs<User>("(function () {})") }
        assertFailsWith<JsException> { engine.evaluateAs<User>("null.x") }
        Unit
    }

    @Test
    fun typedHostFunctionDecodesArgumentsAndEncodesResult() = JsEngine().use { engine ->
        engine.registerFunction("save") { user: User -> Reply(ok = user.id > 0, user = user.copy(name = user.name.uppercase())) }
        engine.registerFunction("pair") { a: Int, b: String -> "$a:$b" }
        engine.registerFunction("sum") { a: Double, b: Double, c: Double -> a + b + c }
        assertEquals(JsValue.Str("A:true"), engine.evaluate("var r = save({id: 1, name: 'a'}); r.user.name + ':' + r.ok"))
        assertEquals(JsValue.Str("1:x"), engine.evaluate("pair(1, 'x')"))
        assertEquals(JsValue.Num(6), engine.evaluate("sum(1, 2, 3)"))
    }

    @Test
    fun typedHostFunctionCoversNullUnitAndCollections() = JsEngine().use { engine ->
        var seen: List<Int>? = null
        engine.registerFunction("take") { items: List<Int> -> seen = items }
        engine.registerFunction("maybe") { s: String? -> s ?: "default" }
        engine.registerFunction("status") { s: Status -> s == Status.ACTIVE }
        assertEquals(JsValue.Str("undefined"), engine.evaluate("typeof take([3, 4])"))
        assertEquals(listOf(3, 4), seen)
        assertEquals(JsValue.Str("default|default|given"), engine.evaluate("maybe(null) + '|' + maybe() + '|' + maybe('given')"))
        assertEquals(JsValue.Bool(true), engine.evaluate("status('ACTIVE')"))
    }

    @Test
    fun argumentDecodingFailureBecomesJsError() = JsEngine().use { engine ->
        engine.registerFunction("save") { user: User -> user.id }
        val message = engine.evaluate("try { save({id: 'one', name: 'a'}) } catch (e) { e.message }")
        assertTrue((message as JsValue.Str).value.contains("id"), "message was: ${message.value}")
        assertEquals(JsValue.Str("caught"), engine.evaluate("try { save() } catch (e) { 'caught' }"))
    }

    @Test
    fun customJsonAppliesToBothDirections() = JsEngine().use { engine ->
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        engine.registerFunction("echo", json) { user: User -> user }
        assertEquals(JsValue.Json("""{"id":1,"name":"a","tags":[]}"""), engine.evaluate("echo({id: 1, name: 'a', extra: 1})"))
        assertEquals(User(1, "a"), engine.evaluateAs("({id: 1, name: 'a', extra: 1})", json = json))
    }
}
