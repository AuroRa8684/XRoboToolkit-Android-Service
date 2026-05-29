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
    private val keepRawPacketLog = false
    private val maxSessionRetention = 8
    private val lock = Any()
    private val logsRootDir = File(context.getExternalFilesDir(null), "xrt_logs")
    private val sessionDir = File(logsRootDir, "session_$sessionName")
    private val rawDir = File(sessionDir, "raw")
    private val eventsDir = File(sessionDir, "events")
    private val rawOutputFile = File(rawDir, "raw_packets.jsonl")
    private val eventsOutputFile = File(eventsDir, "network_events.log")
    private var rawWriter: BufferedWriter? = null
    private val eventWriter: BufferedWriter

    init {
        logsRootDir.mkdirs()
        if (!keepRawPacketLog) {
            cleanupLegacyRawLogs()
        }
        cleanupOldSessions()
        if (keepRawPacketLog) {
            rawDir.mkdirs()
            rawWriter = BufferedWriter(FileWriter(rawOutputFile, true))
        }
        eventsDir.mkdirs()
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
        if (!keepRawPacketLog) return
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
            rawWriter?.append(json.toString())
            rawWriter?.newLine()
            rawWriter?.flush()
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
            rawWriter?.flush()
            rawWriter?.close()
            eventWriter.flush()
            eventWriter.close()
        }
    }

    private fun cleanupLegacyRawLogs() {
        val sessions = logsRootDir.listFiles().orEmpty().filter { it.isDirectory }
        sessions.forEach { session ->
            File(session, "raw_packets.jsonl").delete()
            File(session, "raw/raw_packets.jsonl").delete()
            deleteRecursively(File(session, "raw"))
        }
    }

    private fun cleanupOldSessions() {
        val sessions = logsRootDir.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("session_") }
            .sortedByDescending { it.lastModified() }
        sessions.drop(maxSessionRetention).forEach { deleteRecursively(it) }
    }

    private fun deleteRecursively(file: File) {
        if (!file.exists()) return
        if (file.isDirectory) {
            file.listFiles()?.forEach { child -> deleteRecursively(child) }
        }
        file.delete()
    }
}
