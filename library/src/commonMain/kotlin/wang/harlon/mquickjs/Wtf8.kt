package wang.harlon.mquickjs

/**
 * WTF-8 codec: UTF-8 that also round-trips unpaired UTF-16 surrogates, which is how the engine
 * stores strings. Kotlin's built-in codec would replace them with U+FFFD.
 */
internal object Wtf8 {
    fun encode(text: String): ByteArray {
        if (text.none { it.isSurrogate() }) return text.encodeToByteArray()
        val out = ByteArray(text.length * 3)
        var n = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val cp: Int
            if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                cp = 0x10000 + ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00)
                i += 2
            } else {
                cp = c.code
                i++
            }
            when {
                cp < 0x80 -> out[n++] = cp.toByte()
                cp < 0x800 -> {
                    out[n++] = (0xC0 or (cp shr 6)).toByte()
                    out[n++] = (0x80 or (cp and 0x3F)).toByte()
                }
                cp < 0x10000 -> {
                    out[n++] = (0xE0 or (cp shr 12)).toByte()
                    out[n++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                    out[n++] = (0x80 or (cp and 0x3F)).toByte()
                }
                else -> {
                    out[n++] = (0xF0 or (cp shr 18)).toByte()
                    out[n++] = (0x80 or ((cp shr 12) and 0x3F)).toByte()
                    out[n++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                    out[n++] = (0x80 or (cp and 0x3F)).toByte()
                }
            }
        }
        return out.copyOf(n)
    }

    fun decode(bytes: ByteArray): String {
        // 0xED leads every encoded surrogate; without it the input is plain UTF-8
        if (bytes.none { it == 0xED.toByte() }) return bytes.decodeToString()
        val sb = StringBuilder(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            when {
                b0 < 0x80 -> {
                    sb.append(b0.toChar())
                    i++
                }
                b0 and 0xE0 == 0xC0 && i + 1 < bytes.size -> {
                    sb.append((((b0 and 0x1F) shl 6) or cont(bytes[i + 1])).toChar())
                    i += 2
                }
                b0 and 0xF0 == 0xE0 && i + 2 < bytes.size -> {
                    sb.append((((b0 and 0x0F) shl 12) or (cont(bytes[i + 1]) shl 6) or cont(bytes[i + 2])).toChar())
                    i += 3
                }
                b0 and 0xF8 == 0xF0 && i + 3 < bytes.size -> {
                    val cp = ((b0 and 0x07) shl 18) or (cont(bytes[i + 1]) shl 12) or
                        (cont(bytes[i + 2]) shl 6) or cont(bytes[i + 3])
                    val v = cp - 0x10000
                    sb.append((0xD800 + (v shr 10)).toChar())
                    sb.append((0xDC00 + (v and 0x3FF)).toChar())
                    i += 4
                }
                else -> {
                    sb.append('�')
                    i++
                }
            }
        }
        return sb.toString()
    }

    private fun cont(b: Byte): Int = b.toInt() and 0x3F
}
