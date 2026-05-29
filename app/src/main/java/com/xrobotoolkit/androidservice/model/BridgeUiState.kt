package com.xrobotoolkit.androidservice.model

data class BridgeUiState(
    val serviceRunning: Boolean = false,
    val androidIp: String = "-",
    val picoIp: String = "-",
    val sn: String = "-",
    val uid: String = "-",
    val lastHeartbeatMs: Long = 0L,
    val packetCount: Long = 0L,
    val sessionPath: String = "-",
    val statusMessage: String = "idle",
    val manualPicoIp: String = "",
    val allIpv4Interfaces: List<String> = emptyList(),
    val serviceIp: String = "-",
    val tcpBindRequested: String = "-",
    val udpBroadcastAddress: String = "-",
    val serverSocketLocalAddress: String = "-",
    val tcpServerStarted: Boolean = false,
    val acceptCount: Long = 0L,
    val lastAcceptError: String = "-",
    val lastReadError: String = "-",
    val udpBroadcastCount: Long = 0L,
    val loopbackSelfTest: String = "-",
    val wlanIpSelfTest: String = "-",
    val selfTestAcceptObserved: String = "-",
    val rejectedConnectionCount: Long = 0L,
    val recentEvents: List<String> = emptyList()
)
