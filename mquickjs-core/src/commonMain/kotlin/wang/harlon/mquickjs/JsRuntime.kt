package wang.harlon.mquickjs

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Coroutine-friendly wrapper that serializes every access to one [JsEngine] on [dispatcher] and
 * turns cancellation and timeouts into engine interrupts. [JsRef]s obtained inside [withEngine]
 * must only be used inside [withEngine] as well.
 *
 * Script evaluation is CPU-bound, so the default dispatcher is a single lane of
 * [Dispatchers.Default]; pass an IO-backed dispatcher when host functions block on I/O.
 */
@OptIn(ExperimentalAtomicApi::class)
public class JsRuntime(
    config: JsEngineConfig = JsEngineConfig(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : AutoCloseable {
    private val engine = JsEngine(config)

    /**
     * Runs [block] with exclusive access to the engine. Cancelling the calling coroutine interrupts
     * a script that is still running; the block then completes with [CancellationException].
     */
    public suspend fun <T> withEngine(block: JsEngine.() -> T): T = withContext(dispatcher) {
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
     * [JsEngine.evaluate] on the engine's dispatcher. With [timeout] the script is interrupted and
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

    /** Closes the engine on its dispatcher, after any work already queued there. */
    public suspend fun shutdown(): Unit = withContext(dispatcher) { engine.close() }

    /** Closes immediately; prefer [shutdown] when work may still be queued. */
    override fun close() {
        engine.close()
    }
}
