package wang.harlon.mquickjs

/**
 * Diagnostics snapshot. [liveRefs] is the number of [JsRef]s the host still holds, including
 * transient host-function arguments during a call; [refSlots] is how many handle slots the engine
 * has allocated so far (live plus reusable).
 */
public class JsEngineStats(public val liveRefs: Int, public val refSlots: Int) {
    override fun toString(): String = "JsEngineStats(liveRefs=$liveRefs, refSlots=$refSlots)"
}
