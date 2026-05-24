package com.xrobotoolkit.androidservice.protocol

class XrtPacketReader {
    private var buffer = ByteArray(0)
    private val maxBodyLength = 2 * 1024 * 1024

    @Synchronized
    fun append(data: ByteArray, count: Int): List<XrtPacket> {
        if (count <= 0) return emptyList()
        val incoming = data.copyOf(count)
        buffer += incoming
        val packets = mutableListOf<XrtPacket>()

        while (true) {
            if (buffer.size < 6) break

            val headIndex = findHead(buffer)
            if (headIndex < 0) {
                buffer = ByteArray(0)
                break
            }

            if (headIndex > 0) {
                buffer = buffer.copyOfRange(headIndex, buffer.size)
                if (buffer.size < 6) break
            }

            val length = readIntLE(buffer, 2)
            if (length < 0 || length > maxBodyLength) {
                buffer = buffer.copyOfRange(1, buffer.size)
                continue
            }

            val total = 15 + length
            if (buffer.size < total) break

            val tail = buffer[total - 1].toInt() and 0xFF
            if (tail != XrtPacketCodec.TAIL) {
                buffer = buffer.copyOfRange(1, buffer.size)
                continue
            }

            val raw = buffer.copyOfRange(0, total)
            val head = raw[0].toInt() and 0xFF
            val cmd = raw[1].toInt() and 0xFF
            val body = raw.copyOfRange(6, 6 + length)
            val timestamp = readLongLE(raw, 6 + length)
            packets.add(
                XrtPacket(
                    head = head,
                    cmd = cmd,
                    length = length,
                    body = body,
                    timestamp = timestamp,
                    tail = tail,
                    rawBytes = raw
                )
            )
            buffer = buffer.copyOfRange(total, buffer.size)
        }

        return packets
    }

    private fun findHead(bytes: ByteArray): Int {
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            if (v == XrtPacketCodec.HEAD_CLIENT || v == XrtPacketCodec.HEAD_SERVER) {
                return i
            }
        }
        return -1
    }

    private fun readIntLE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readLongLE(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = result or ((bytes[offset + i].toLong() and 0xFFL) shl (8 * i))
        }
        return result
    }
}
