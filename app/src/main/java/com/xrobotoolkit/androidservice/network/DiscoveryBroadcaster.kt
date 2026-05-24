package com.xrobotoolkit.androidservice.network

import android.util.Log
import com.xrobotoolkit.androidservice.protocol.XrtPacketCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

class DiscoveryBroadcaster(
    private val selectedInterface: SelectedInterface,
    private val udpPort: Int = 29888,
    private val intervalMs: Long = 5000L,
    private val onStarted: (String) -> Unit = {},
    private val onSent: (Long, String, String) -> Unit = { _, _, _ -> },
    private val onError: (String) -> Unit = {}
) {
    private var job: Job? = null
    private var socket: DatagramSocket? = null
    private var sendCount = 0L

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            try {
                val bindAddress = InetSocketAddress(selectedInterface.localAddress, 0)
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(bindAddress)
                }
                val local = socket?.localSocketAddress?.toString().orEmpty()
                Log.i(TAG, "UDP broadcaster started: local=$local target=${selectedInterface.broadcastAddress.hostAddress}:$udpPort")
                onStarted(local)
            } catch (t: Throwable) {
                val msg = "UDP broadcaster start failed: ${t.javaClass.simpleName}: ${t.message}"
                Log.e(TAG, msg, t)
                onError(msg)
                return@launch
            }
            while (isActive) {
                sendDiscoveryNow()
                delay(intervalMs)
            }
        }
    }

    fun sendDiscoveryNow() {
        val currentSocket = socket
        if (currentSocket == null || currentSocket.isClosed) {
            val msg = "UDP send skipped: socket not ready"
            Log.w(TAG, msg)
            onError(msg)
            return
        }
        val ipText = selectedInterface.localAddress.hostAddress ?: ""
        val body = ipText.toByteArray(Charsets.UTF_8)
        val packetBytes = XrtPacketCodec.buildPacket(
            head = XrtPacketCodec.HEAD_SERVER,
            cmd = XrtPacketCodec.CMD_DISCOVERY,
            body = body,
            timestampSec = System.currentTimeMillis() / 1000L
        )
        val datagram = DatagramPacket(
            packetBytes,
            packetBytes.size,
            selectedInterface.broadcastAddress,
            udpPort
        )
        try {
            currentSocket.send(datagram)
            sendCount += 1L
            val target = "${selectedInterface.broadcastAddress.hostAddress}:$udpPort"
            Log.i(TAG, "UDP discovery sent #$sendCount target=$target bodyIp=$ipText")
            onSent(sendCount, target, ipText)
        } catch (t: Throwable) {
            val msg = "UDP send failed: ${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, msg, t)
            onError(msg)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        socket?.close()
        socket = null
        Log.i(TAG, "UDP broadcaster stopped")
    }

    companion object {
        private const val TAG = "XrtDiscovery"
    }
}
