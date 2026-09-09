package wang.harlon.mquickjs

/**
 * A JavaScript exception that escaped to the host. [message] is the result of the
 * engine's `toString()` on the thrown value; [jsStack] is the `stack` property for Error objects.
 */
public class JsException(message: String, public val jsStack: String? = null) : RuntimeException(message)
