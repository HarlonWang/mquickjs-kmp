package wang.harlon.mquickjs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MQuickJsTest {
    @Test
    fun sdkVersionIsPresent() {
        assertTrue(MQuickJs.sdkVersion.isNotBlank())
    }

    @Test
    fun upstreamCommitIsFullSha() {
        assertEquals(40, MQuickJs.upstreamCommit.length)
        assertTrue(MQuickJs.upstreamCommit.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
