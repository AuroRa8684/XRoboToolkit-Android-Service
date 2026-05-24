package com.xrobotoolkit.androidservice.core

import android.content.Context
import android.util.Log
import com.xrobotoolkit.androidservice.network.BindMode
import com.xrobotoolkit.androidservice.network.DiscoveryBroadcaster
import com.xrobotoolkit.androidservice.network.NetworkInterfaceSelector
import com.xrobotoolkit.androidservice.network.SelectedInterface
import com.xrobotoolkit.androidservice.network.TcpDeviceServer
import com.xrobotoolkit.androidservice.protocol.XrtPacket
import com.xrobotoolkit.androidservice.protocol.XrtPacketCodec
import com.xrobotoolkit.androidservice.storage.PacketLogger
import com.xrobotoolkit.androidservice.util.TextDecodeUtil.decodeUtf8IfValid
import com.xrobotoolkit.androidservice.util.TimeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

class BridgeSupervisor(
    private val appContext: Context
) {
    private val stateLock = Any()
    private val networkSelector = NetworkInterfaceSelector()
    private val recentEvents = CopyOnWriteArrayList<String>()

    private var scope: CoroutineScope? = null
    private var selected: SelectedInterface? = null
    private var broadcaster: DiscoveryBroadcaster? = null
    private var tcpServer: TcpDeviceServer? = null
    private var logger: PacketLogger? = null
    private var heartbeatJob: Job? = null
    private var autoSelfTestJob: Job? = null

    private var packetCount: Long = 0L
    private var acceptCount: Long = 0L
    private var currentPicoIp: String = "-"
    private var currentSn: String = "-"
    private var currentUid: String = "-"
    private var lastHeartbeatMs: Long = 0L
    private var udpBroadcastCount: Long = 0L
    private var bindMode: BindMode = BindMode.ANY_IPV4
    private var manualPicoIp: String = "192.168.123.22"

    fun start() {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sessionName = TimeUtil.sessionNameNow()
        logger = PacketLogger(appContext, sessionName)
        resetRuntimeCounters()

        BridgeRepository.update {
            it.copy(
                serviceRunning = true,
                bindMode = bindMode.name,
                manualPicoIp = manualPicoIp,
                sessionPath = logger?.sessionPath() ?: "-",
                statusMessage = "Bridge starting"
            )
        }
        logEvent("Bridge start requested")
        startNetworkComponents()
    }

    fun stop() {
        logEvent("Bridge stop requested")
        stopNetworkComponents()
        scope?.cancel()
        scope = null
        logger?.close()
        logger = null
        BridgeRepository.update {
            it.copy(
                serviceRunning = false,
                tcpServerStarted = false,
                statusMessage = "Bridge stopped"
            )
        }
    }

    fun setBindMode(newMode: BindMode) {
        bindMode = newMode
        BridgeRepository.update { it.copy(bindMode = newMode.name) }
        logEvent("Bind mode switched to ${newMode.name}")
        if (scope != null) {
            restartNetworkComponents("Bind mode changed to ${newMode.name}")
        }
    }

    fun setManualPicoIp(ip: String) {
        manualPicoIp = ip.trim().ifEmpty { manualPicoIp }
        BridgeRepository.update { it.copy(manualPicoIp = manualPicoIp) }
        logEvent("Manual PICO IP set to $manualPicoIp")
    }

    fun triggerDiscoveryNow() {
        if (scope == null) return
        broadcaster?.sendDiscoveryNow()
        logEvent("Manual discovery broadcast trigger requested")
    }

    fun sendUdpProbeToManualPico() {
        if (scope == null) return
        val selectedInterface = selected ?: return
        sendUdpToManualPico(
            name = "UDP probe",
            buildPayload = {
                val body = (selectedInterface.localAddress.hostAddress ?: "").toByteArray(StandardCharsets.UTF_8)
                XrtPacketCodec.buildPacket(
                    head = XrtPacketCodec.HEAD_SERVER,
                    cmd = XrtPacketCodec.CMD_DISCOVERY,
                    body = body,
                    timestampSec = System.currentTimeMillis() / 1000L
                )
            }
        )
    }

    fun sendUdpPrimingToManualPico() {
        if (scope == null) return
        sendUdpToManualPico(
            name = "UDP priming",
            buildPayload = {
                "xrt-udp-priming-${System.currentTimeMillis()}".toByteArray(StandardCharsets.UTF_8)
            }
        )
    }

    fun runTcpSelfTests() {
        val runningScope = scope ?: return
        val localIp = selected?.localAddress?.hostAddress ?: return
        val beforeAccept = synchronized(stateLock) { acceptCount }
        logEvent("TCP self-test started")
        runningScope.launch(Dispatchers.IO) {
            val loopbackOk = runSingleTcpSelfTest("127.0.0.1")
            val wlanOk = runSingleTcpSelfTest(localIp)
            delay(500L)
            val acceptObserved = synchronized(stateLock) { acceptCount > beforeAccept }
            BridgeRepository.update {
                it.copy(
                    loopbackSelfTest = if (loopbackOk) "PASS" else "FAIL",
                    wlanIpSelfTest = if (wlanOk) "PASS" else "FAIL",
                    selfTestAcceptObserved = if (acceptObserved) "YES" else "NO"
                )
            }
            logEvent(
                "TCP self-test finished loopback=${if (loopbackOk) "PASS" else "FAIL"} " +
                    "wlan=${if (wlanOk) "PASS" else "FAIL"} acceptObserved=${if (acceptObserved) "YES" else "NO"}"
            )
        }
    }

    private fun startNetworkComponents() {
        val runningScope = scope ?: return
        val interfaceInfos = networkSelector.listAllIpv4Interfaces()
        val interfaceLines = networkSelector.formatForDisplay(interfaceInfos)
        val chosen = networkSelector.selectPreferredInterface("192.168.14.")
        selected = chosen

        BridgeRepository.update {
            it.copy(
                allIpv4Interfaces = interfaceLines,
                lastAcceptError = "-",
                lastReadError = "-",
                loopbackSelfTest = "-",
                wlanIpSelfTest = "-",
                selfTestAcceptObserved = "-"
            )
        }

        if (chosen == null) {
            logEvent("No IPv4 interface available for bridge")
            BridgeRepository.update {
                it.copy(
                    serviceRunning = true,
                    statusMessage = "No IPv4 interface available for broadcast/listen"
                )
            }
            return
        }

        val requestedBindAddress = when (bindMode) {
            BindMode.SELECTED_IP -> chosen.localAddress
            BindMode.ANY_IPV4 -> InetAddress.getByName("0.0.0.0")
        }

        logEvent(
            "Network start: if=${chosen.interfaceName} serviceIp=${chosen.localAddress.hostAddress} " +
                "bcast=${chosen.broadcastAddress.hostAddress} bindMode=${bindMode.name} requestedBind=${requestedBindAddress.hostAddress}"
        )

        BridgeRepository.update {
            it.copy(
                androidIp = chosen.localAddress.hostAddress ?: "-",
                serviceIp = chosen.localAddress.hostAddress ?: "-",
                udpBroadcastAddress = "${chosen.broadcastAddress.hostAddress}:29888",
                tcpBindRequested = "${requestedBindAddress.hostAddress}:63901",
                serverSocketLocalAddress = "-",
                tcpServerStarted = false,
                statusMessage = "Bridge started on ${chosen.interfaceName} ${chosen.localAddress.hostAddress}"
            )
        }

        broadcaster = DiscoveryBroadcaster(
            selectedInterface = chosen,
            onStarted = { local ->
                logEvent("UDP broadcaster bound local=$local")
            },
            onSent = { count, target, bodyIp ->
                synchronized(stateLock) { udpBroadcastCount = count }
                BridgeRepository.update {
                    it.copy(udpBroadcastCount = count, statusMessage = "UDP discovery sent to $target body=$bodyIp")
                }
            },
            onError = { error ->
                BridgeRepository.update { it.copy(statusMessage = error) }
                logEvent(error)
            }
        ).also { it.start(runningScope) }

        tcpServer = TcpDeviceServer(
            bindAddress = requestedBindAddress,
            tcpPort = 63901,
            onPacket = ::onPacketReceived,
            onClientConnected = { ip, port ->
                synchronized(stateLock) { currentPicoIp = ip }
                BridgeRepository.update { state ->
                    state.copy(
                        picoIp = ip,
                        statusMessage = "PICO connected from $ip:$port"
                    )
                }
                logEvent("TCP client connected from $ip:$port")
            },
            onClientDisconnected = { ip, port ->
                BridgeRepository.update { state ->
                    state.copy(statusMessage = "PICO disconnected from $ip:$port")
                }
                logEvent("TCP client disconnected from $ip:$port")
            },
            onServerStarted = { requested, actual, port ->
                BridgeRepository.update { state ->
                    state.copy(
                        tcpServerStarted = true,
                        tcpBindRequested = "$requested:$port",
                        serverSocketLocalAddress = actual,
                        statusMessage = "TCP listening at $actual"
                    )
                }
                logEvent("TCP server started bindMode=${bindMode.name} requested=$requested:$port actual=$actual")
            },
            onAccept = { ip, port, count ->
                synchronized(stateLock) { acceptCount = count }
                BridgeRepository.update { it.copy(acceptCount = count) }
                logEvent("TCP accept #$count from $ip:$port")
            },
            onAcceptError = { error ->
                BridgeRepository.update { it.copy(lastAcceptError = error, statusMessage = error) }
                logEvent(error)
            },
            onReadError = { ip, port, error ->
                BridgeRepository.update { it.copy(lastReadError = "$ip:$port $error", statusMessage = "Read error from $ip:$port") }
                logEvent("TCP read error from $ip:$port $error")
            }
        ).also { it.start(runningScope) }

        heartbeatJob = runningScope.launch(Dispatchers.IO) {
            while (isActive) {
                val stale = synchronized(stateLock) {
                    lastHeartbeatMs > 0L && System.currentTimeMillis() - lastHeartbeatMs > 20_000L
                }
                if (stale) {
                    BridgeRepository.update {
                        it.copy(statusMessage = "Heartbeat timeout (>20s)")
                    }
                }
                delay(1000L)
            }
        }

        autoSelfTestJob?.cancel()
        autoSelfTestJob = runningScope.launch(Dispatchers.IO) {
            delay(1200L)
            runTcpSelfTests()
        }
    }

    private fun stopNetworkComponents() {
        autoSelfTestJob?.cancel()
        autoSelfTestJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        broadcaster?.stop()
        broadcaster = null
        tcpServer?.stop()
        tcpServer = null
    }

    private fun restartNetworkComponents(reason: String) {
        logEvent(reason)
        stopNetworkComponents()
        resetRuntimeCounters()
        startNetworkComponents()
    }

    private fun sendUdpToManualPico(name: String, buildPayload: () -> ByteArray) {
        val chosen = selected ?: return
        val runningScope = scope ?: return
        runningScope.launch(Dispatchers.IO) {
            val destinationIp = manualPicoIp.trim()
            if (destinationIp.isEmpty()) return@launch
            val payload = buildPayload()
            try {
                DatagramSocket(null).use { socket ->
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(chosen.localAddress, 0))
                    val target = InetAddress.getByName(destinationIp)
                    val packet = DatagramPacket(payload, payload.size, target, 29888)
                    socket.send(packet)
                    val localAddress = socket.localSocketAddress?.toString().orEmpty()
                    logEvent("$name sent local=$localAddress dst=$destinationIp:29888 bytes=${payload.size}")
                    BridgeRepository.update { it.copy(statusMessage = "$name sent to $destinationIp:29888") }
                }
            } catch (t: Throwable) {
                val msg = "$name failed: ${t.javaClass.simpleName}: ${t.message}"
                BridgeRepository.update { it.copy(statusMessage = msg) }
                logEvent(msg)
            }
        }
    }

    private fun runSingleTcpSelfTest(targetIp: String): Boolean {
        return try {
            Socket().use { socket ->
                socket.soTimeout = 1000
                socket.connect(InetSocketAddress(targetIp, 63901), 1000)
                socket.getOutputStream().write("xrt-self-test-${System.currentTimeMillis()}".toByteArray(StandardCharsets.UTF_8))
                socket.getOutputStream().flush()
            }
            true
        } catch (t: Throwable) {
            val msg = "TCP self-test to $targetIp failed: ${t.javaClass.simpleName}: ${t.message}"
            logEvent(msg)
            false
        }
    }

    private fun onPacketReceived(remoteIp: String, remotePort: Int, packet: XrtPacket) {
        synchronized(stateLock) {
            packetCount += 1L
            currentPicoIp = remoteIp
        }

        if (packet.head == XrtPacketCodec.HEAD_CLIENT) {
            when (packet.cmd) {
                XrtPacketCodec.CMD_HEARTBEAT -> {
                    synchronized(stateLock) { lastHeartbeatMs = System.currentTimeMillis() }
                    BridgeRepository.update {
                        it.copy(
                            picoIp = remoteIp,
                            lastHeartbeatMs = lastHeartbeatMs,
                            packetCount = packetCount,
                            statusMessage = "Heartbeat received cmd=0x23"
                        )
                    }
                    logEvent("Heartbeat received from $remoteIp:$remotePort")
                }

                XrtPacketCodec.CMD_CONNECT,
                XrtPacketCodec.CMD_SENSOR,
                XrtPacketCodec.CMD_BATTERY -> {
                    val bodyText = decodeUtf8IfValid(packet.body)
                    if (!bodyText.isNullOrBlank()) {
                        val parts = bodyText.split("|")
                        val sn = parts.getOrNull(0)?.trim().orEmpty()
                        val uid = parts.getOrNull(1)?.trim().orEmpty()
                        synchronized(stateLock) {
                            if (sn.isNotEmpty()) currentSn = sn
                            if (uid.isNotEmpty()) currentUid = uid
                        }
                    }
                    BridgeRepository.update {
                        it.copy(
                            picoIp = remoteIp,
                            sn = currentSn,
                            uid = currentUid,
                            packetCount = packetCount,
                            statusMessage = "Identity packet cmd=0x${packet.cmd.toString(16)}"
                        )
                    }
                    logEvent("Identity packet cmd=0x${packet.cmd.toString(16)} from $remoteIp:$remotePort sn=$currentSn")
                }

                else -> {
                    BridgeRepository.update {
                        it.copy(
                            picoIp = remoteIp,
                            sn = currentSn,
                            packetCount = packetCount,
                            statusMessage = "Packet cmd=0x${packet.cmd.toString(16)}"
                        )
                    }
                    if (packet.cmd == XrtPacketCodec.CMD_STATE_JSON) {
                        logEvent("Tracking/state packet cmd=0x6d from $remoteIp:$remotePort length=${packet.length}")
                    }
                }
            }
        } else {
            BridgeRepository.update {
                it.copy(packetCount = packetCount)
            }
        }

        logger?.logPacket(packet, remoteIp, remotePort, currentSn, currentUid)
    }

    private fun resetRuntimeCounters() {
        synchronized(stateLock) {
            packetCount = 0L
            acceptCount = 0L
            currentPicoIp = "-"
            currentSn = "-"
            currentUid = "-"
            lastHeartbeatMs = 0L
            udpBroadcastCount = 0L
        }
        recentEvents.clear()
        BridgeRepository.update {
            it.copy(
                picoIp = "-",
                sn = "-",
                uid = "-",
                packetCount = 0L,
                acceptCount = 0L,
                udpBroadcastCount = 0L,
                lastHeartbeatMs = 0L,
                recentEvents = emptyList()
            )
        }
    }

    private fun logEvent(message: String) {
        Log.i(TAG, message)
        logger?.logEvent(message)
        val line = "${TimeUtil.formatReadable(System.currentTimeMillis())} $message"
        recentEvents.add(line)
        while (recentEvents.size > 20) {
            recentEvents.removeAt(0)
        }
        BridgeRepository.update { it.copy(recentEvents = recentEvents.toList()) }
    }

    companion object {
        private const val TAG = "XrtBridgeSupervisor"
    }
}
