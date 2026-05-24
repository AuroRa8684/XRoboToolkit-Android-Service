package com.xrobotoolkit.androidservice.storage

import android.content.Context
import com.xrobotoolkit.androidservice.protocol.XrtPacket
import com.xrobotoolkit.androidservice.util.TextDecodeUtil.decodeUtf8IfValid
import com.xrobotoolkit.androidservice.util.TextDecodeUtil.toHexString
import com.xrobotoolkit.androidservice.util.TimeUtil
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

class PacketLogger(
    context: Context,
    sessionName: String
) {
    private val lock = Any()
    private val sessionDir = File(
        context.getExternalFilesDir(null),
        "xrt_logs/session_$sessionName"
    )
    private val rawDir = File(sessionDir, "raw")
    private val eventsDir = File(sessionDir, "events")
    private val rawOutputFile = File(rawDir, "raw_packets.jsonl")
    private val eventsOutputFile = File(eventsDir, "network_events.log")
    private val rawWriter: BufferedWriter
    private val eventWriter: BufferedWriter

    init {
        rawDir.mkdirs()
        eventsDir.mkdirs()
        rawWriter = BufferedWriter(FileWriter(rawOutputFile, true))
        eventWriter = BufferedWriter(FileWriter(eventsOutputFile, true))
    }

    fun sessionPath(): String = sessionDir.absolutePath

    fun logPacket(
        packet: XrtPacket,
        remoteIp: String,
        remotePort: Int,
        sn: String?,
        uid: String?
    ) {
        val json = JSONObject()
            .put("received_at_ms", System.currentTimeMillis())
            .put("remote_ip", remoteIp)
            .put("remote_port", remotePort)
            .put("head", packet.head)
            .put("cmd", packet.cmd)
            .put("length", packet.length)
            .put("timestamp", packet.timestamp)
            .put("tail", packet.tail)
            .put("packet_hex", packet.rawBytes.toHexString())
            .put("body_hex", packet.body.toHexString())
            .put("sn", sn ?: "")
            .put("uid", uid ?: "")

        val bodyText = decodeUtf8IfValid(packet.body)
        if (bodyText != null) {
            json.put("body_text", bodyText)
        }

        synchronized(lock) {
            rawWriter.append(json.toString())
            rawWriter.newLine()
            rawWriter.flush()
        }
    }

    fun logEvent(message: String) {
        val line = "${TimeUtil.formatReadable(System.currentTimeMillis())} $message"
        synchronized(lock) {
            eventWriter.append(line)
            eventWriter.newLine()
            eventWriter.flush()
        }
    }

    fun close() {
        synchronized(lock) {
            rawWriter.flush()
            rawWriter.close()
            eventWriter.flush()
            eventWriter.close()
        }
    }
}
