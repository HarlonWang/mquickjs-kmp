package wang.harlon.mquickjs

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class Wtf8Test {
    @Test
    fun plainTextMatchesUtf8() {
        val text = "héllo 世界 😀"
        assertContentEquals(text.encodeToByteArray(), Wtf8.encode(text))
        assertEquals(text, Wtf8.decode(Wtf8.encode(text)))
    }

    @Test
    fun loneSurrogatesRoundTrip() {
        val text = "a\uD800b\uDFFFc"
        val bytes = Wtf8.encode(text)
        assertContentEquals(byteArrayOf(0x61, 0xED.toByte(), 0xA0.toByte(), 0x80.toByte(), 0x62, 0xED.toByte(), 0xBF.toByte(), 0xBF.toByte(), 0x63), bytes)
        assertEquals(text, Wtf8.decode(bytes))
    }

    @Test
    fun truncatedInputDoesNotThrow() {
        assertEquals("a�", Wtf8.decode(byteArrayOf(0x61, 0xED.toByte())))
    }
}
