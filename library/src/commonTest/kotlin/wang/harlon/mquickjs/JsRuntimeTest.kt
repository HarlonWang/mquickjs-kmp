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
    fun multiLaneDispatcherStillSerializes() = realTime {
        val runtime = JsRuntime(dispatcher = Dispatchers.Default)
        try {
            runtime.evaluate("var counter = 0; var active = 0; var overlap = false;")
            coroutineScope {
                List(50) {
                    async {
                        runtime.withEngine {
                            evaluate("active++; if (active > 1) overlap = true;")
                            evaluate("for (var i = 0; i < 20000; i++) {} counter++; active--;")
                        }
                    }
                }.awaitAll()
            }
            assertEquals(JsValue.Num(50), runtime.evaluate("counter"))
            assertEquals(JsValue.Bool(false), runtime.evaluate("overlap"))
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun shutdownWaitsForRunningWorkAndInterruptsIt() = realTime {
        val runtime = JsRuntime()
        coroutineScope {
            val job = launch {
                val e = assertFailsWith<JsException> { runtime.evaluate("for (;;) {}") }
                assertTrue(e.message.orEmpty().contains("interrupted"), "message was: ${e.message}")
            }
            delay(200)
            runtime.shutdown()
            job.join()
        }
        assertFailsWith<IllegalStateException> { runtime.evaluate("1") }
    }

    @Test
    fun closeWhileBusyCompletesWhenWorkEnds() = realTime {
        // 多轮压竞态窗口：close() 与持锁工作收尾谁后到都必须把引擎关掉
        repeat(20) {
            val runtime = JsRuntime()
            coroutineScope {
                val job = launch { runCatching { runtime.evaluate("var i = 0; while (i < 3000000) { i++; }") } }
                delay(it.toLong() * 3)
                runtime.close()
                job.join()
            }
            assertFailsWith<IllegalStateException> { runtime.evaluate("1") }
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
