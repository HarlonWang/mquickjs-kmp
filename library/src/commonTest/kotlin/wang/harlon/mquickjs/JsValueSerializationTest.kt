package wang.harlon.mquickjs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class JsValueSerializationTest {
    private val lenient = Json { ignoreUnknownKeys = true }
    private val withDefaults = Json { encodeDefaults = true }

    @Test
    fun encodesPrimitivesAsNativeValues() {
        assertEquals(JsValue.Num(3.0), Json.encodeToJsValue(3))
        assertEquals(JsValue.Num(2.5), Json.encodeToJsValue(2.5))
        assertEquals(JsValue.Str("x"), Json.encodeToJsValue("x"))
        assertEquals(JsValue.Bool(true), Json.encodeToJsValue(true))
        assertEquals(JsValue.Null, Json.encodeToJsValue<String?>(null))
        assertEquals(JsValue.Str("ACTIVE"), Json.encodeToJsValue(Status.ACTIVE))
    }

    @Test
    fun encodesStructuresAsJson() {
        assertEquals(JsValue.Json("""{"id":1,"name":"a","tags":["x"]}"""), Json.encodeToJsValue(User(1, "a", listOf("x"))))
        assertEquals(JsValue.Json("[1,2]"), Json.encodeToJsValue(listOf(1, 2)))
        assertEquals(JsValue.Json("""{"k":1.5}"""), Json.encodeToJsValue(mapOf("k" to 1.5)))
    }

    @Test
    fun decodesPrimitives() {
        assertEquals(3, JsValue.Num(3.0).decode())
        assertEquals(3L, JsValue.Num(3.0).decode())
        assertEquals(2.5, JsValue.Num(2.5).decode())
        assertEquals("x", JsValue.Str("x").decode())
        assertEquals(true, JsValue.Bool(true).decode())
        assertEquals(Status.ACTIVE, JsValue.Str("ACTIVE").decode())
        assertEquals(0, JsValue.Num(-0.0).decode())
        assertEquals(9223372036854774784L, JsValue.Num(9223372036854774784.0).decode())
        assertEquals(9.223372036854775807E18, JsValue.Num(9.223372036854775807E18).decode())
        assertEquals(1e19, JsValue.Num(1e19).decode())
        assertNull(JsValue.Null.decode<String?>())
        assertNull(JsValue.Undefined.decode<User?>())
    }

    @Test
    fun decodesJsonText() {
        assertEquals(User(1, "a", listOf("x")), JsValue.Json("""{"id":1,"name":"a","tags":["x"]}""").decode())
        assertEquals(listOf(User(1, "a"), User(2, "b")), JsValue.Json("""[{"id":1,"name":"a"},{"id":2,"name":"b"}]""").decode())
    }

    @Test
    fun rejectsNonSerializableAndMismatchedValues() {
        assertFailsWith<SerializationException> { JsValue.Json(null).decode<User>() }
        assertFailsWith<SerializationException> { JsValue.Str("x").decode<Int>() }
        assertFailsWith<SerializationException> { JsValue.Null.decode<User>() }
        assertFailsWith<SerializationException> { JsValue.Json("""{"id":"one"}""").decode<User>() }
    }

    @Test
    fun honoursJsonConfiguration() {
        val text = JsValue.Json("""{"id":1,"name":"a","extra":true}""")
        assertFailsWith<SerializationException> { text.decode<User>() }
        assertEquals(User(1, "a"), text.decode(lenient))
        assertEquals(JsValue.Json("""{"id":1,"name":"a","tags":[]}"""), withDefaults.encodeToJsValue(User(1, "a")))
    }

    @Test
    fun decodesRefsThroughJson() = JsEngine().use { engine ->
        val ref = engine.evaluate("({id: 7, name: 'ref'})", objects = ObjectTransport.REF) as JsRef
        ref.use { assertEquals(User(7, "ref"), it.decode()) }
        val fn = engine.evaluate("(function () {})", objects = ObjectTransport.REF) as JsRef
        fn.use { assertFailsWith<SerializationException> { it.decode<User>() } }
        Unit
    }
}
