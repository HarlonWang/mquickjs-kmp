package wang.harlon.mquickjs.serialization

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import wang.harlon.mquickjs.JsRuntime

class JsRuntimeSerializationTest {
    // 走 Dispatchers.Default 跳出 runTest 的虚拟时间，超时才能对上真实阻塞求值
    private fun realTime(block: suspend () -> Unit) = runTest(timeout = 60.seconds) {
        withContext(Dispatchers.Default) { block() }
    }

    @Test
    fun evaluatesAndRegistersTyped() = realTime {
        val runtime = JsRuntime()
        try {
            runtime.registerFunction("save") { user: User -> Reply(ok = true, user = user) }
            runtime.registerFunction("pair") { a: Int, b: Int -> a * b }
            runtime.registerFunction("join") { a: String, b: String, c: String -> a + b + c }
            assertEquals(Reply(ok = true, user = User(1, "a")), runtime.evaluateAs("save({id: 1, name: 'a'})"))
            assertEquals(12, runtime.evaluateAs("pair(3, 4)"))
            assertEquals("abc", runtime.evaluateAs("join('a', 'b', 'c')"))
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun evaluateAsHonoursTimeout() = realTime {
        val runtime = JsRuntime()
        try {
            assertFailsWith<TimeoutCancellationException> { runtime.evaluateAs<Int>("for (;;) {}", timeout = 200.milliseconds) }
            assertEquals(2, runtime.evaluateAs("1 + 1"))
        } finally {
            runtime.shutdown()
        }
    }
}
