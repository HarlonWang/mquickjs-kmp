package wang.harlon.mquickjs

/**
 * Diagnostics snapshot. [liveRefs] is the number of [JsRef.close] calls still owed: every open
 * [JsRef] counts one, a [JsRef.retain] adds one more, and transient host-function arguments count
 * during the call; [refSlots] is how many handle slots the engine has allocated so far (live plus reusable).
 */
public class JsEngineStats(public val liveRefs: Int, public val refSlots: Int) {
    override fun toString(): String = "JsEngineStats(liveRefs=$liveRefs, refSlots=$refSlots)"
}
