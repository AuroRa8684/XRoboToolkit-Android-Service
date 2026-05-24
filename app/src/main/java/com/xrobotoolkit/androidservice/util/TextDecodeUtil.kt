package com.xrobotoolkit.androidservice.util

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object TextDecodeUtil {
    fun decodeUtf8IfValid(data: ByteArray): String? {
        if (data.isEmpty()) return ""
        val decoder = StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(data)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    fun ByteArray.toHexString(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            out.append(((b.toInt() and 0xFF) ushr 4).toString(16))
            out.append((b.toInt() and 0x0F).toString(16))
        }
        return out.toString()
    }
}
