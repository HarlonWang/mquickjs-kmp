package wang.harlon.mquickjs

/**
 * Ahead-of-time compilation. The output is bound to the engine embedded in this SDK version
 * (see [MQuickJs.upstreamCommit]) and to a word size; [JsEngine.loadBytecode] rejects anything else.
 * Bytecode is not validated beyond that header: only load what this SDK produced.
 */
public object JsBytecode {
    /** 64 on every 64-bit target, 32 on `armeabi-v7a`. Pick the matching file at runtime. */
    public val wordSize: Int
        get() = NativeCompiler.wordSize()

    /**
     * Compiles [script] to bytecode for a [wordSize]-bit engine. A 32-bit engine can only produce
     * 32-bit output. [stripColumns] drops column numbers from stack traces to save space.
     * @throws JsException on syntax errors; the message reads `Error: …` rather than
     * `SyntaxError: …` because the compile context has no class prototypes, [JsException.jsStack]
     * carries the location
     */
    public fun compile(
        script: String,
        fileName: String = "<bytecode>",
        wordSize: Int = this.wordSize,
        stripColumns: Boolean = false,
    ): ByteArray {
        val flags = if (stripColumns) NativeTag.COMPILE_STRIP_COLUMNS else 0
        return when (val result = NativeCompiler.compile(script, fileName, wordSize, flags)) {
            is ByteArray -> result
            is RawValue -> throw JsException(result.str ?: "compilation failed", result.stack)
            else -> error("unexpected compiler result $result")
        }
    }
}
