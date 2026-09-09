package wang.harlon.mquickjs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsEngineTest {
    @Test
    fun evaluatesArithmetic() = JsEngine().use { engine ->
        assertEquals(JsValue.Num(3), engine.evaluate("1 + 2"))
    }

    @Test
    fun mapsPrimitiveTypes() = JsEngine().use { engine ->
        assertEquals(JsValue.Str("héllo 世界"), engine.evaluate("'héllo ' + '世界'"))
        assertEquals(JsValue.Bool(true), engine.evaluate("1 < 2"))
        assertEquals(JsValue.Null, engine.evaluate("null"))
        assertEquals(JsValue.Undefined, engine.evaluate("undefined"))
        assertEquals(JsValue.Num(1.5), engine.evaluate("3 / 2"))
    }

    @Test
    fun serializesObjectsAsJson() = JsEngine().use { engine ->
        assertEquals(JsValue.Json("""{"a":1,"b":[1,2,"x"]}"""), engine.evaluate("({a: 1, b: [1, 2, 'x']})"))
        assertEquals(JsValue.Json(null), engine.evaluate("(function () {})"))
    }

    @Test
    fun keepsStateBetweenEvaluations() = JsEngine().use { engine ->
        engine.evaluate("var counter = 41;")
        assertEquals(JsValue.Num(42), engine.evaluate("counter + 1"))
    }

    @Test
    fun callsHostFunctionWithTypedArguments() = JsEngine().use { engine ->
        var received: List<JsValue> = emptyList()
        engine.registerFunction("record") { args ->
            received = args
            JsValue.Str("ok:" + args.size)
        }
        assertEquals(JsValue.Str("ok:5"), engine.evaluate("record(1, 'two', true, null, {k: [3]})"))
        assertEquals(
            listOf(JsValue.Num(1), JsValue.Str("two"), JsValue.Bool(true), JsValue.Null, JsValue.Json("""{"k":[3]}""")),
            received,
        )
    }

    @Test
    fun hostReturnValuesRoundTrip() = JsEngine().use { engine ->
        engine.registerFunction("obj") { JsValue.Json("""{"n": 7, "s": "x"}""") }
        engine.registerFunction("num") { JsValue.Num(2.5) }
        engine.registerFunction("nothing") { JsValue.Undefined }
        assertEquals(JsValue.Num(9.5), engine.evaluate("obj().n + num()"))
        assertEquals(JsValue.Str("x"), engine.evaluate("obj().s"))
        assertEquals(JsValue.Str("undefined"), engine.evaluate("typeof nothing()"))
    }

    @Test
    fun hostExceptionBecomesJsError() = JsEngine().use { engine ->
        engine.registerFunction("boom") { throw IllegalStateException("host failed") }
        assertEquals(JsValue.Str("caught: host failed"), engine.evaluate("try { boom() } catch (e) { 'caught: ' + e.message }"))
        val uncaught = assertFailsWith<JsException> { engine.evaluate("boom()") }
        assertEquals("Error: host failed", uncaught.message)
    }

    @Test
    fun jsExceptionCarriesMessageAndStack() = JsEngine().use { engine ->
        val e = assertFailsWith<JsException> { engine.evaluate("null.x", "rules.js") }
        assertEquals("TypeError: cannot read property 'x' of null", e.message)
        assertTrue(e.jsStack.orEmpty().contains("rules.js"), "stack was: ${e.jsStack}")
    }

    @Test
    fun syntaxErrorIsReported() = JsEngine().use { engine ->
        val e = assertFailsWith<JsException> { engine.evaluate("var x = ;") }
        assertTrue(e.message.orEmpty().startsWith("SyntaxError"), "message was: ${e.message}")
    }

    @Test
    fun thrownNonErrorValuesAreStringified() = JsEngine().use { engine ->
        val e = assertFailsWith<JsException> { engine.evaluate("throw 42") }
        assertEquals("42", e.message)
    }

    @Test
    fun consoleLogReachesLogger() {
        val lines = ArrayList<String>()
        JsEngine(JsEngineConfig(logger = { lines += it })).use { engine ->
            engine.evaluate("console.log('a', 1, {x: true}); print('b')")
        }
        assertEquals(listOf("a 1 { x: true }", "b"), lines)
    }

    @Test
    fun loggerExceptionsAreSwallowed() {
        JsEngine(JsEngineConfig(logger = { throw IllegalStateException("logger broke") })).use { engine ->
            assertEquals(JsValue.Num(1), engine.evaluate("console.log('a'); 1"))
        }
    }

    @Test
    fun preservesUnpairedSurrogates() = JsEngine().use { engine ->
        engine.registerFunction("echo") { it[0] }
        assertEquals(JsValue.Str("\uD800"), engine.evaluate("'\\uD800'"))
        assertEquals(JsValue.Str("a\uDC00b"), engine.evaluate("echo('a\\uDC00b')"))
        assertEquals(JsValue.Num(3), engine.evaluate("echo('a\\uDC00b').length"))
        assertEquals(JsValue.Str("\uD83D\uDE00!"), engine.evaluate("echo('\uD83D\uDE00!')"))
    }

    @Test
    fun interruptStopsInfiniteLoop() = JsEngine().use { engine ->
        engine.registerFunction("stop") {
            engine.interrupt()
            JsValue.Undefined
        }
        val e = assertFailsWith<JsException> { engine.evaluate("stop(); for (;;) {}") }
        assertTrue(e.message.orEmpty().contains("interrupted"), "message was: ${e.message}")
        assertEquals(JsValue.Num(1), engine.evaluate("1"))
    }

    @Test
    fun interruptWhileIdleIsIgnored() = JsEngine().use { engine ->
        engine.interrupt()
        assertEquals(JsValue.Num(2), engine.evaluate("1 + 1"))
    }

    @Test
    fun nestedEvaluationFromHostFunction() = JsEngine().use { engine ->
        engine.registerFunction("inner") { engine.evaluate("21 * 2") }
        assertEquals(JsValue.Num(43), engine.evaluate("inner() + 1"))
        // 中断只在循环与调用点被检查；有界循环保证状态机若被嵌套求值重置也不会挂死测试
        engine.registerFunction("stopInner") {
            engine.interrupt()
            engine.evaluate("1")
        }
        val e = assertFailsWith<JsException> { engine.evaluate("stopInner(); for (var i = 0; i < 10000000; i++) {}") }
        assertTrue(e.message.orEmpty().contains("interrupted"), "message was: ${e.message}")
        assertEquals(JsValue.Num(5), engine.evaluate("5"))
    }

    @Test
    fun outOfMemoryIsAnException() = JsEngine(JsEngineConfig(memoryBytes = 16 * 1024)).use { engine ->
        val e = assertFailsWith<JsException> {
            engine.evaluate("var a = []; for (var i = 0; i < 100000; i++) a.push({i: i});")
        }
        assertTrue(e.message.orEmpty().contains("out of memory"), "message was: ${e.message}")
    }

    @Test
    fun rejectsInvalidFunctionNames() {
        JsEngine().use { engine ->
            assertFailsWith<IllegalArgumentException> { engine.registerFunction("not valid") { JsValue.Undefined } }
        }
    }

    @Test
    fun closedEngineRejectsCalls() {
        val engine = JsEngine()
        engine.close()
        engine.close()
        engine.interrupt()
        assertFailsWith<IllegalStateException> { engine.evaluate("1") }
    }

    @Test
    fun fileNameWithLoneSurrogateSurvivesInStack() = JsEngine().use { engine ->
        val e = assertFailsWith<JsException> { engine.evaluate("null.x", "a\uD800.js") }
        assertTrue(e.jsStack.orEmpty().contains("a\uD800.js"), "stack was: ${e.jsStack}")
    }
}
