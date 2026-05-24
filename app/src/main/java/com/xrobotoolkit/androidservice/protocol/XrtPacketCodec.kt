package com.xrobotoolkit.androidservice.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

object XrtPacketCodec {
    const val HEAD_CLIENT = 0x3F
    const val HEAD_SERVER = 0xCF
    const val TAIL = 0xA5

    const val CMD_CONNECT = 0x19
    const val CMD_BATTERY = 0x1A
    const val CMD_SENSOR = 0x1B
    const val CMD_HEARTBEAT = 0x23
    const val CMD_STATE_JSON = 0x6D
    const val CMD_DISCOVERY = 0x7E

    fun buildPacket(head: Int, cmd: Int, body: ByteArray, timestampSec: Long): ByteArray {
        val totalLength = 1 + 1 + 4 + body.size + 8 + 1
        val buffer = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(head.toByte())
        buffer.put(cmd.toByte())
        buffer.putInt(body.size)
        buffer.put(body)
        buffer.putLong(timestampSec)
        buffer.put(TAIL.toByte())
        return buffer.array()
    }
}
