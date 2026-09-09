package wang.harlon.mquickjs

/**
 * @property memoryBytes size of the single buffer the engine allocates from. The engine never
 * grows it; running out raises a [JsException].
 * @property logger receives each `console.log` / `print` line; exceptions it throws are swallowed.
 */
public class JsEngineConfig(
    public val memoryBytes: Int = DEFAULT_MEMORY_BYTES,
    public val logger: ((String) -> Unit)? = null,
) {
    init {
        require(memoryBytes >= MIN_MEMORY_BYTES) { "memoryBytes must be at least $MIN_MEMORY_BYTES" }
    }

    public companion object {
        public const val DEFAULT_MEMORY_BYTES: Int = 256 * 1024
        public const val MIN_MEMORY_BYTES: Int = 8 * 1024
    }
}
