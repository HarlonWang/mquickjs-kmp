package wang.harlon.mquickjs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class JsRuntimeTest {
    // 走 Dispatchers.Default 跳出 runTest 的虚拟时间，超时与真实阻塞求值才能对上
    private fun realTime(block: suspend () -> Unit) = runTest(timeout = 60.seconds) {
        withContext(Dispatchers.Default) { block() }
    }

    @Test
    fun evaluatesOnDispatcher() = realTime {
        val runtime = JsRuntime()
        try {
            assertEquals(JsValue.Num(3), runtime.evaluate("1 + 2"))
            runtime.registerFunction("twice") { JsValue.Num((it[0] as JsValue.Num).value * 2) }
            assertEquals(JsValue.Num(8), runtime.evaluate("twice(4)"))
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun serializesConcurrentAccess() = realTime {
        val runtime = JsRuntime()
        try {
            runtime.evaluate("var counter = 0;")
            coroutineScope {
                List(50) {
                    async { runtime.withEngine { evaluate("counter = counter + 1") } }
                }.awaitAll()
            }
            assertEquals(JsValue.Num(50), runtime.evaluate("counter"))
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun timeoutInterruptsRunningScript() = realTime {
        val runtime = JsRuntime()
        try {
            assertFailsWith<TimeoutCancellationException> {
                runtime.evaluate("for (;;) {}", timeout = 200.milliseconds)
            }
            assertEquals(JsValue.Num(1), runtime.evaluate("1"))
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun cancellationInterruptsRunningScript() = realTime {
        val runtime = JsRuntime()
        try {
            coroutineScope {
                val job = launch { runtime.evaluate("for (;;) {}") }
                delay(200)
                job.cancel()
                job.join()
                assertTrue(job.isCancelled)
            }
            assertEquals(JsValue.Num(2), runtime.evaluate("2"))
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun refsWorkInsideWithEngine() = realTime {
        val runtime = JsRuntime()
        try {
            val total = runtime.withEngine {
                val obj = assertIs<JsRef>(evaluate("({a: 1, b: 2})", objects = ObjectTransport.REF))
                obj.use { (it.get("a") as JsValue.Num).value + (it.get("b") as JsValue.Num).value }
            }
            assertEquals(3.0, total)
        } finally {
            runtime.shutdown()
        }
    }
}
