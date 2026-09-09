package wang.harlon.mquickjs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsRefTest {
    private fun JsEngine.ref(script: String): JsRef = assertIs<JsRef>(evaluate(script, objects = ObjectTransport.REF))

    @Test
    fun readsPropertiesAndElements() = JsEngine().use { engine ->
        engine.ref("({n: 7, s: 'x', arr: [1, 2, 3]})").use { obj ->
            assertEquals(JsValue.Num(7), obj.get("n"))
            assertEquals(JsValue.Str("x"), obj.get("s"))
            assertEquals(JsValue.Json("[1,2,3]"), obj.get("arr"))
            obj.get("arr", ObjectTransport.REF).let { it as JsRef }.use { arr ->
                assertTrue(arr.isArray)
                assertEquals(JsValue.Num(2), arr.get(1))
                assertEquals(JsValue.Num(3), arr.get("length"))
                assertEquals(JsValue.Undefined, arr.get(10))
            }
            assertEquals(JsValue.Undefined, obj.get("missing"))
        }
    }

    @Test
    fun writesPropertiesIncludingRefs() = JsEngine().use { engine ->
        engine.ref("var target = {}; target").use { target ->
            engine.ref("[9, 8]").use { arr ->
                target.set("n", JsValue.Num(1))
                target.set("s", JsValue.Str("héllo"))
                target.set("j", JsValue.Json("""{"z":[true]}"""))
                target.set("arr", arr)
                target.set("u", JsValue.Undefined)
            }
            assertEquals(JsValue.Json("""{"n":1,"s":"héllo","j":{"z":[true]},"arr":[9,8]}"""), engine.evaluate("target"))
            assertEquals(JsValue.Bool(true), engine.evaluate("target.u === undefined && 'u' in target"))
            assertEquals("""{"n":1,"s":"héllo","j":{"z":[true]},"arr":[9,8]}""", target.toJson())
        }
    }

    @Test
    fun callsFunctionsWithThisAndArguments() = JsEngine().use { engine ->
        engine.ref("({n: 10, f: function (a, b) { return a + b + this.n; }})").use { obj ->
            obj.get("f", ObjectTransport.REF).let { it as JsRef }.use { f ->
                assertTrue(f.isFunction)
                assertEquals(JsValue.Num(16), f.invoke(obj, listOf(JsValue.Num(1), JsValue.Num(5))))
                val e = assertFailsWith<JsException> { f.call(JsValue.Num(1), JsValue.Num(5)) }
                assertTrue(e.message.orEmpty().startsWith("TypeError"), "message was: ${e.message}")
            }
        }
        engine.ref("(function (arr) { return arr.length * 10; })").use { f ->
            engine.ref("[1, 2, 3]").use { arr -> assertEquals(JsValue.Num(30), f.call(arr)) }
            assertEquals(JsValue.Num(20), f.call(JsValue.Json("[1, 2]")))
        }
        engine.ref("(function () { return {made: true}; })").use { f ->
            assertEquals(JsValue.Json("""{"made":true}"""), f.call())
            val made = assertIs<JsRef>(f.invoke(null, emptyList(), ObjectTransport.REF))
            made.use { assertEquals(JsValue.Bool(true), it.get("made")) }
        }
    }

    @Test
    fun nonFunctionRefCannotBeCalled() = JsEngine().use { engine ->
        engine.ref("({})").use { obj ->
            val e = assertFailsWith<JsException> { obj.call() }
            assertTrue(e.message.orEmpty().contains("not a function"), "message was: ${e.message}")
        }
    }

    @Test
    fun functionsSerializeToNullJson() = JsEngine().use { engine ->
        engine.ref("(function () {})").use { f -> assertNull(f.toJson()) }
    }

    @Test
    fun hostFunctionReceivesTransientRefsAndCanRetain() = JsEngine().use { engine ->
        var kept: JsRef? = null
        var seenKinds = ""
        engine.registerFunction("keep", ObjectTransport.REF) { args ->
            val obj = args[0] as JsRef
            val arr = args[1] as JsRef
            seenKinds = "${obj.isArray}/${arr.isArray}/${args[2]}"
            kept = obj.retain()
            JsValue.Undefined
        }
        engine.evaluate("keep({k: 'kept'}, [1], 'str')")
        assertEquals("false/true/Str(value=str)", seenKinds)
        val ref = kept!!
        assertEquals(JsValue.Str("kept"), ref.get("k"))
        engine.registerFunction("give") { ref }
        assertEquals(JsValue.Str("kept!"), engine.evaluate("give().k + '!'"))
        ref.close()
        assertFailsWith<IllegalStateException> { ref.get("k") }
        Unit
    }

    @Test
    fun refsSurviveGarbageCollectionAndReleaseFreesSlots() = JsEngine(JsEngineConfig(memoryBytes = 64 * 1024)).use { engine ->
        val held = List(50) { i -> engine.ref("({v: $i})") }
        engine.evaluate("var junk = []; for (var i = 0; i < 500; i++) junk.push({i: i}); junk = null;")
        held.forEachIndexed { i, ref -> assertEquals(JsValue.Num(i), ref.get("v")) }
        held.forEach { it.close() }
        repeat(5000) { engine.ref("({i: 1})").close() }
        assertEquals(JsValue.Num(200), engine.evaluate("var big = []; for (var i = 0; i < 200; i++) big.push({i: i}); big.length"))
    }

    @Test
    fun transientHostArgumentIsInvalidAfterTheCall() = JsEngine().use { engine ->
        var escaped: JsRef? = null
        engine.registerFunction("grab", ObjectTransport.REF) { args ->
            escaped = args[0] as JsRef
            JsValue.Undefined
        }
        engine.evaluate("grab({k: 1})")
        val ref = escaped!!
        assertTrue(!ref.isValid)
        assertFailsWith<IllegalStateException> { ref.get("k") }
        engine.registerFunction("give") { ref }
        val e = assertFailsWith<JsException> { engine.evaluate("give()") }
        assertTrue(e.message.orEmpty().contains("JsRef is closed"), "message was: ${e.message}")
        ref.close()
    }

    @Test
    fun closedRefNeverAliasesAReusedSlot() = JsEngine().use { engine ->
        val first = engine.ref("({a: 1})")
        first.close()
        engine.ref("({b: 2})").use { second ->
            assertFailsWith<IllegalStateException> { first.get("b") }
            first.close()
            assertEquals(JsValue.Num(2), second.get("b"))
        }
    }

    @Test
    fun interruptReachesAccessors() = JsEngine().use { engine ->
        engine.registerFunction("stop") {
            engine.interrupt()
            JsValue.Undefined
        }
        engine.ref("({get y() { stop(); for (var i = 0; i < 10000000; i++) {} return 1; }})").use { obj ->
            val e = assertFailsWith<JsException> { obj.get("y") }
            assertTrue(e.message.orEmpty().contains("interrupted"), "message was: ${e.message}")
        }
    }

    @Test
    fun closeIsIdempotentAndEngineCloseInvalidatesRefs() {
        val engine = JsEngine()
        val ref = engine.ref("({})")
        ref.close()
        ref.close()
        val open = engine.ref("({})")
        engine.close()
        open.close()
        assertFailsWith<IllegalStateException> { open.get("x") }
    }

    @Test
    fun refFromAnotherEngineIsRejected() {
        JsEngine().use { a ->
            JsEngine().use { b ->
                a.ref("({})").use { ra ->
                    b.ref("(function (x) { return x; })").use { fb ->
                        assertFailsWith<IllegalArgumentException> { fb.call(ra) }
                    }
                }
            }
        }
    }
}
