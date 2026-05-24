package com.xrobotoolkit.androidservice.network

import android.util.Log
import com.xrobotoolkit.androidservice.protocol.XrtPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.Collections

class TcpDeviceServer(
    private val bindAddress: InetAddress,
    private val tcpPort: Int = 63901,
    private val onPacket: (String, Int, XrtPacket) -> Unit,
    private val onClientConnected: (String, Int) -> Unit,
    private val onClientDisconnected: (String, Int) -> Unit,
    private val onServerStarted: (String, String, Int) -> Unit,
    private val onAccept: (String, Int, Long) -> Unit,
    private val onAcceptError: (String) -> Unit,
    private val onReadError: (String, Int, String) -> Unit
) {
    private var acceptJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private val sessions = Collections.synchronizedSet(mutableSetOf<TcpClientSession>())
    private var acceptCount: Long = 0L

    fun start(scope: CoroutineScope) {
        if (acceptJob != null) return
        acceptJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    soTimeout = 1000
                    bind(InetSocketAddress(bindAddress, tcpPort))
                }
            } catch (t: Throwable) {
                val msg = "TCP bind failed on ${bindAddress.hostAddress}:$tcpPort: ${t.javaClass.simpleName}: ${t.message}"
                Log.e(TAG, msg, t)
                onAcceptError(msg)
                return@launch
            }
            val actualLocal = serverSocket?.localSocketAddress?.toString().orEmpty()
            onServerStarted(bindAddress.hostAddress ?: "-", actualLocal, tcpPort)
            Log.i(TAG, "TCP server started requested=${bindAddress.hostAddress}:$tcpPort actual=$actualLocal")
            while (isActive) {
                val clientSocket = try {
                    serverSocket?.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (t: IOException) {
                    if (!isActive || serverSocket?.isClosed == true) {
                        break
                    }
                    val msg = "TCP accept error: ${t.javaClass.simpleName}: ${t.message}"
                    Log.e(TAG, msg, t)
                    onAcceptError(msg)
                    continue
                } ?: continue
                val holder = arrayOfNulls<TcpClientSession>(1)
                val session = TcpClientSession(
                    socket = clientSocket,
                    onPacket = onPacket,
                    onClosed = { ip, port ->
                        holder[0]?.let { sessions.remove(it) }
                        onClientDisconnected(ip, port)
                    },
                    onReadError = { ip, port, error ->
                        onReadError(ip, port, error)
                    }
                )
                holder[0] = session
                sessions.add(session)
                acceptCount += 1L
                val remoteIp = clientSocket.inetAddress?.hostAddress ?: "-"
                onAccept(remoteIp, clientSocket.port, acceptCount)
                onClientConnected(remoteIp, clientSocket.port)
                Log.i(TAG, "TCP accept #$acceptCount from $remoteIp:${clientSocket.port}")
                session.start(scope)
            }
            Log.i(TAG, "TCP accept loop exited")
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        synchronized(sessions) {
            sessions.forEach { it.stop() }
            sessions.clear()
        }
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        Log.i(TAG, "TCP server stopped")
    }

    companion object {
        private const val TAG = "XrtTcpServer"
    }
}
