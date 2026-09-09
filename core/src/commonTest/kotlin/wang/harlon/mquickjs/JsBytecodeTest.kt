package wang.harlon.mquickjs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsBytecodeTest {
    private val program = "var base = 20; function twice(n) { return n * 2; } twice(base) + report(1) + 1"

    @Test
    fun compileLoadRegisterRun() = JsEngine().use { engine ->
        val bytes = JsBytecode.compile(program, "prog.js")
        assertTrue(bytes.size > 52)
        engine.loadBytecode(bytes).use { prog ->
            engine.registerFunction("report") { it[0] }
            assertEquals(JsValue.Num(42), prog.run())
            assertEquals(JsValue.Num(10), engine.evaluate("twice(5)"))
            assertEquals(JsValue.Num(42), prog.run())
        }
        assertEquals(0, engine.stats().liveRefs)
    }

    @Test
    fun oneProgramPerEngine() = JsEngine().use { engine ->
        val a = engine.loadBytecode(JsBytecode.compile("var fromA = 1;"))
        val e = assertFailsWith<JsException> { engine.loadBytecode(JsBytecode.compile("var fromB = 2;")) }
        assertTrue(e.message.orEmpty().contains("one program per engine"), "message was: ${e.message}")
        a.run()
        assertEquals(JsValue.Num(1), engine.evaluate("fromA"))
        a.close()
    }

    @Test
    fun loadingAfterScriptRanIsRejected() = JsEngine().use { engine ->
        val bytes = JsBytecode.compile("1")
        engine.evaluate("var early = 1;")
        val e = assertFailsWith<JsException> { engine.loadBytecode(bytes) }
        assertTrue(e.message.orEmpty().contains("before any script"), "message was: ${e.message}")
    }

    @Test
    fun loadingAfterRegisterFunctionIsRejected() = JsEngine().use { engine ->
        val bytes = JsBytecode.compile("1")
        engine.registerFunction("early") { JsValue.Undefined }
        assertFailsWith<JsException> { engine.loadBytecode(bytes) }
        Unit
    }

    @Test
    fun wrongWordSizeIsRejected() = JsEngine().use { engine ->
        val other = if (JsBytecode.wordSize == 64) 32 else 64
        if (other == 64 && JsBytecode.wordSize == 32) return@use // a 32-bit engine cannot even produce it
        val bytes = JsBytecode.compile("1", wordSize = other, stripColumns = true)
        val e = assertFailsWith<JsException> { engine.loadBytecode(bytes) }
        assertTrue(e.message.orEmpty().contains("-bit engine"), "message was: ${e.message}")
    }

    @Test
    fun foreignEngineCommitIsRejected() = JsEngine().use { engine ->
        val bytes = JsBytecode.compile("1")
        for (i in 12 until 22) bytes[i] = '0'.code.toByte()
        val e = assertFailsWith<JsException> { engine.loadBytecode(bytes) }
        assertTrue(e.message.orEmpty().contains("built for engine"), "message was: ${e.message}")
        assertTrue(e.message.orEmpty().contains(MQuickJs.upstreamCommit.take(12)), "message was: ${e.message}")
    }

    @Test
    fun garbageIsRejected() = JsEngine().use { engine ->
        val e = assertFailsWith<JsException> { engine.loadBytecode("not bytecode".encodeToByteArray()) }
        assertTrue(e.message.orEmpty().contains("not MQuickJS bytecode"), "message was: ${e.message}")
        assertFailsWith<JsException> { engine.loadBytecode(ByteArray(0)) }
        Unit
    }

    @Test
    fun syntaxErrorsSurfaceAtCompileTime() {
        val e = assertFailsWith<JsException> { JsBytecode.compile("var x = ;", "bad.js") }
        // 编译上下文里没有类原型，错误对象打印成 "Error: …"，只核对正文与位置
        assertTrue(e.message.orEmpty().contains("unexpected character"), "message was: ${e.message}")
        assertTrue(e.jsStack.orEmpty().contains("bad.js"), "stack was: ${e.jsStack}")
    }

    @Test
    fun runtimeErrorsInProgramsCarryStack() = JsEngine().use { engine ->
        val bytes = JsBytecode.compile("function f() { return missing.x; } f()", "rules.js")
        engine.loadBytecode(bytes).use { prog ->
            val e = assertFailsWith<JsException> { prog.run() }
            assertTrue(e.message.orEmpty().startsWith("ReferenceError"), "message was: ${e.message}")
            assertTrue(e.jsStack.orEmpty().contains("rules.js"), "stack was: ${e.jsStack}")
        }
    }

    @Test
    fun closedProgramCannotRun() = JsEngine().use { engine ->
        val prog = engine.loadBytecode(JsBytecode.compile("1"))
        prog.close()
        prog.close()
        assertFailsWith<IllegalStateException> { prog.run() }
        Unit
    }
}
