package wang.harlon.mquickjs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JsRefLeakTest {
    private fun JsEngine.ref(script: String): JsRef = assertIs<JsRef>(evaluate(script, objects = ObjectTransport.REF))

    @Test
    fun closedRefsReturnTheirSlots() = JsEngine().use { engine ->
        assertEquals(0, engine.stats().liveRefs)
        val refs = List(200) { engine.ref("({i: $it})") }
        assertEquals(200, engine.stats().liveRefs)
        refs.forEach { it.close() }
        assertEquals(0, engine.stats().liveRefs)
        repeat(2000) { engine.ref("({})").close() }
        val stats = engine.stats()
        assertEquals(0, stats.liveRefs)
        assertTrue(stats.refSlots <= 200, "slots grew to ${stats.refSlots}")
    }

    @Test
    fun transientHostArgumentsAreReleasedAfterTheCall() = JsEngine().use { engine ->
        var seenDuringCall = -1
        var kept: JsRef? = null
        engine.registerFunction("inspect", ObjectTransport.REF) { args ->
            seenDuringCall = engine.stats().liveRefs
            JsValue.Undefined
        }
        engine.registerFunction("keep", ObjectTransport.REF) { args ->
            kept = (args[0] as JsRef).retain()
            JsValue.Undefined
        }
        engine.evaluate("inspect({}, [], function () {})")
        assertEquals(3, seenDuringCall)
        assertEquals(0, engine.stats().liveRefs)
        engine.evaluate("keep({k: 1})")
        assertEquals(1, engine.stats().liveRefs)
        val again = kept!!.retain()
        assertEquals(2, engine.stats().liveRefs)
        again.close()
        kept!!.close()
        assertEquals(0, engine.stats().liveRefs)
    }

    @Test
    fun refsReturnedToScriptDoNotLeak() = JsEngine().use { engine ->
        val shared = engine.ref("({shared: true})")
        engine.registerFunction("give") { shared }
        assertEquals(JsValue.Bool(true), engine.evaluate("give().shared && give() === give()"))
        assertEquals(1, engine.stats().liveRefs)
        shared.close()
        assertEquals(0, engine.stats().liveRefs)
    }

    @Test
    fun leakedRefsAreBoundedByTheEngineAndRecoverable() = JsEngine(JsEngineConfig(memoryBytes = 32 * 1024)).use { engine ->
        val leaked = ArrayList<JsRef>()
        val e = assertFailsWith<JsException> {
            while (true) leaked += engine.ref("({padding: 'xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx'})")
        }
        // 内存耗尽时解析器可能先撞到栈检查，报 stack overflow 而非 out of memory
        val message = e.message.orEmpty()
        assertTrue(message.contains("out of memory") || message.contains("stack overflow"), "message was: $message")
        assertTrue(leaked.size > 10, "leaked only ${leaked.size}")
        assertEquals(leaked.size, engine.stats().liveRefs)
        leaked.forEach { it.close() }
        assertEquals(0, engine.stats().liveRefs)
        assertEquals(JsValue.Num(50), engine.evaluate("var a = []; for (var i = 0; i < 50; i++) a.push({i: i}); a.length"))
    }

    @Test
    fun dumpMemoryDescribesTheHeap() = JsEngine().use { engine ->
        engine.evaluate("var keep = [1, 2, 3];")
        val dump = engine.dumpMemory()
        assertTrue(dump.isNotBlank() && dump.contains("\n"), "dump was: $dump")
    }
}
