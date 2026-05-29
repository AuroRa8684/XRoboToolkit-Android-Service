package com.xrobotoolkit.androidservice.network

import android.util.Log
import com.xrobotoolkit.androidservice.protocol.XrtPacket
import com.xrobotoolkit.androidservice.protocol.XrtPacketReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException

class TcpClientSession(
    private val socket: Socket,
    private val onPacket: (String, Int, XrtPacket) -> Unit,
    private val onClosed: (String, Int) -> Unit,
    private val onReadError: (String, Int, String) -> Unit
) {
    private val reader = XrtPacketReader()
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            val remoteIp = socket.inetAddress?.hostAddress ?: "-"
            val remotePort = socket.port
            Log.i(TAG, "TCP session started from $remoteIp:$remotePort")
            socket.keepAlive = true
            socket.tcpNoDelay = true
            socket.soTimeout = 2000
            val input = socket.getInputStream()
            val buffer = ByteArray(8192)
            try {
                while (isActive) {
                    val read = try {
                        input.read(buffer)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    if (read < 0) break
                    Log.d(TAG, "TCP read $read bytes from $remoteIp:$remotePort")
                    val packets = reader.append(buffer, read)
                    for (packet in packets) {
                        onPacket(remoteIp, remotePort, packet)
                    }
                }
            } catch (t: IOException) {
                val msg = "${t.javaClass.simpleName}: ${t.message}"
                Log.e(TAG, "TCP session read error from $remoteIp:$remotePort $msg", t)
                onReadError(remoteIp, remotePort, msg)
            } finally {
                stop()
                onClosed(remoteIp, remotePort)
                Log.i(TAG, "TCP session closed from $remoteIp:$remotePort")
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        private const val TAG = "XrtTcpSession"
    }
}
