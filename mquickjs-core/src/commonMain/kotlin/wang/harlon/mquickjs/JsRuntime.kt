package wang.harlon.mquickjs

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Coroutine-friendly wrapper that gives one [JsEngine] exclusive access under a [Mutex], runs the
 * work on [dispatcher], and turns cancellation and timeouts into engine interrupts. [JsRef]s
 * obtained inside [withEngine] must only be used inside [withEngine] as well.
 *
 * Script evaluation is CPU-bound, so the default dispatcher is a single lane of
 * [Dispatchers.Default]; any dispatcher works because exclusion comes from the mutex, not from it.
 */
@OptIn(ExperimentalAtomicApi::class)
public class JsRuntime(
    config: JsEngineConfig = JsEngineConfig(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : AutoCloseable {
    private val engine = JsEngine(config)
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    // close() 遇到引擎被占用时置位，持锁的 withEngine 结束后补关
    private val pendingClose = AtomicBoolean(false)

    /**
     * Runs [block] with exclusive access to the engine. Cancelling the calling coroutine interrupts
     * a script that is still running; the block then completes with [CancellationException].
     */
    public suspend fun <T> withEngine(block: JsEngine.() -> T): T = mutex.withLock {
        check(!closed.load()) { "JsRuntime is closed" }
        try {
            runExclusive(block)
        } finally {
            if (pendingClose.load()) engine.close()
        }
    }

    private suspend fun <T> runExclusive(block: JsEngine.() -> T): T =
        withContext(dispatcher) {
            coroutineScope {
                val done = AtomicBoolean(false)
                val watchdog = launch(Dispatchers.Default) {
                    try {
                        awaitCancellation()
                    } finally {
                        if (!done.load()) engine.interrupt()
                    }
                }
                try {
                    engine.block()
                } catch (e: JsException) {
                    if (isActive) throw e
                    throw CancellationException("evaluation interrupted", e)
                } finally {
                    done.store(true)
                    watchdog.cancel()
                }
            }
        }

    /**
     * [JsEngine.evaluate] with exclusive access. With [timeout] the script is interrupted and
     * a [kotlinx.coroutines.TimeoutCancellationException] is thrown when it runs too long.
     */
    public suspend fun evaluate(
        script: String,
        fileName: String = "<eval>",
        objects: ObjectTransport = ObjectTransport.JSON,
        timeout: Duration? = null,
    ): JsValue {
        val run: suspend () -> JsValue = { withEngine { evaluate(script, fileName, objects) } }
        return if (timeout == null) run() else withTimeout(timeout) { run() }
    }

    public suspend fun registerFunction(
        name: String,
        objects: ObjectTransport = ObjectTransport.JSON,
        function: JsHostFunction,
    ): Unit = withEngine { registerFunction(name, objects, function) }

    /** Interrupts running work, waits for it to release the engine, then closes it. */
    public suspend fun shutdown() {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        engine.interrupt()
        withContext(NonCancellable) {
            mutex.withLock { engine.close() }
        }
    }

    /**
     * Non-suspending close for [AutoCloseable] users. Interrupts running work and closes the engine
     * once it is idle; when work is still holding the engine, the close completes as that work ends.
     */
    override fun close() {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        engine.interrupt()
        if (mutex.tryLock()) {
            try {
                engine.close()
            } finally {
                mutex.unlock()
            }
        } else {
            pendingClose.store(true)
        }
    }
}
