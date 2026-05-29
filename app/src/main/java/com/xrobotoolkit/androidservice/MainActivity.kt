package com.xrobotoolkit.androidservice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.xrobotoolkit.androidservice.core.BridgeRepository
import com.xrobotoolkit.androidservice.service.XrtBridgeForegroundService
import com.xrobotoolkit.androidservice.util.TimeUtil
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var serviceRunningText: TextView
    private lateinit var androidIpText: TextView
    private lateinit var picoIpText: TextView
    private lateinit var snText: TextView
    private lateinit var lastHeartbeatText: TextView
    private lateinit var packetCountText: TextView
    private lateinit var statusText: TextView
    private lateinit var sessionPathText: TextView
    private lateinit var batteryOptimizationText: TextView
    private lateinit var interfacesText: TextView
    private lateinit var serviceIpText: TextView
    private lateinit var tcpBindRequestedText: TextView
    private lateinit var udpBroadcastText: TextView
    private lateinit var serverSocketLocalText: TextView
    private lateinit var tcpStartedText: TextView
    private lateinit var acceptCountText: TextView
    private lateinit var rejectedCountText: TextView
    private lateinit var lastAcceptErrorText: TextView
    private lateinit var lastReadErrorText: TextView
    private lateinit var udpBroadcastCountText: TextView
    private lateinit var selfTestLoopbackText: TextView
    private lateinit var selfTestWlanText: TextView
    private lateinit var selfTestAcceptText: TextView
    private lateinit var recentEventsText: TextView
    private lateinit var manualPicoIpInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serviceRunningText = findViewById(R.id.serviceRunningText)
        androidIpText = findViewById(R.id.androidIpText)
        picoIpText = findViewById(R.id.picoIpText)
        snText = findViewById(R.id.snText)
        lastHeartbeatText = findViewById(R.id.lastHeartbeatText)
        packetCountText = findViewById(R.id.packetCountText)
        statusText = findViewById(R.id.statusText)
        sessionPathText = findViewById(R.id.sessionPathText)
        batteryOptimizationText = findViewById(R.id.batteryOptimizationText)
        interfacesText = findViewById(R.id.interfacesText)
        serviceIpText = findViewById(R.id.serviceIpText)
        tcpBindRequestedText = findViewById(R.id.tcpBindRequestedText)
        udpBroadcastText = findViewById(R.id.udpBroadcastText)
        serverSocketLocalText = findViewById(R.id.serverSocketLocalText)
        tcpStartedText = findViewById(R.id.tcpStartedText)
        acceptCountText = findViewById(R.id.acceptCountText)
        rejectedCountText = findViewById(R.id.rejectedCountText)
        lastAcceptErrorText = findViewById(R.id.lastAcceptErrorText)
        lastReadErrorText = findViewById(R.id.lastReadErrorText)
        udpBroadcastCountText = findViewById(R.id.udpBroadcastCountText)
        selfTestLoopbackText = findViewById(R.id.selfTestLoopbackText)
        selfTestWlanText = findViewById(R.id.selfTestWlanText)
        selfTestAcceptText = findViewById(R.id.selfTestAcceptText)
        recentEventsText = findViewById(R.id.recentEventsText)
        manualPicoIpInput = findViewById(R.id.manualPicoIpInput)
        manualPicoIpInput.setOnFocusChangeListener { _, hasFocus ->
            manualPicoIpInput.isCursorVisible = hasFocus
        }
        manualPicoIpInput.isCursorVisible = false
        restoreManualPicoIpFromPrefs()

        findViewById<Button>(R.id.startButton).setOnClickListener { startBridgeService() }
        findViewById<Button>(R.id.stopButton).setOnClickListener { stopBridgeService() }
        findViewById<Button>(R.id.applyManualPicoIpButton).setOnClickListener { applyManualPicoIp() }
        findViewById<Button>(R.id.batteryOptimizationButton).setOnClickListener { requestIgnoreBatteryOptimization() }
        findViewById<Button>(R.id.runSelfTestButton).setOnClickListener { sendAction(XrtBridgeForegroundService.ACTION_RUN_SELF_TEST) }
        findViewById<Button>(R.id.discoveryNowButton).setOnClickListener { sendAction(XrtBridgeForegroundService.ACTION_DISCOVERY_NOW) }
        findViewById<Button>(R.id.sendUdpProbeButton).setOnClickListener { sendAction(XrtBridgeForegroundService.ACTION_SEND_UDP_PROBE) }
        findViewById<Button>(R.id.sendUdpPrimingButton).setOnClickListener { sendAction(XrtBridgeForegroundService.ACTION_SEND_UDP_PRIMING) }

        maybeRequestNotificationPermission()
        observeUiState()
    }

    private fun startBridgeService() {
        val intent = Intent(this, XrtBridgeForegroundService::class.java).apply {
            action = XrtBridgeForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopBridgeService() {
        val intent = Intent(this, XrtBridgeForegroundService::class.java).apply {
            action = XrtBridgeForegroundService.ACTION_STOP
        }
        startService(intent)
    }

    private fun applyManualPicoIp() {
        val ipText = manualPicoIpInput.text?.toString()?.trim().orEmpty()
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MANUAL_PICO_IP, ipText)
            .apply()
        BridgeRepository.update { it.copy(manualPicoIp = ipText) }
        val intent = Intent(this, XrtBridgeForegroundService::class.java).apply {
            action = XrtBridgeForegroundService.ACTION_SET_MANUAL_PICO_IP
            putExtra(XrtBridgeForegroundService.EXTRA_MANUAL_PICO_IP, ipText)
        }
        startService(intent)
        hideKeyboardAndClearInputFocus()
    }

    private fun sendAction(action: String) {
        val intent = Intent(this, XrtBridgeForegroundService::class.java).apply {
            this.action = action
        }
        startService(intent)
    }

    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        try {
            if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } else {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } catch (_: Throwable) {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun batteryOptimizationStatusText(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return "NOT_APPLICABLE"
        }
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return "UNKNOWN"
        return if (powerManager.isIgnoringBatteryOptimizations(packageName)) "IGNORED" else "OPTIMIZED"
    }

    private fun restoreManualPicoIpFromPrefs() {
        val stored = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MANUAL_PICO_IP, "")
            .orEmpty()
            .trim()
        manualPicoIpInput.setText(stored)
        BridgeRepository.update { it.copy(manualPicoIp = stored) }
    }

    private fun hideKeyboardAndClearInputFocus() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(manualPicoIpInput.windowToken, 0)
        manualPicoIpInput.clearFocus()
        manualPicoIpInput.isCursorVisible = false
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                BridgeRepository.state.collect { state ->
                    serviceRunningText.text = getString(
                        R.string.service_running_fmt,
                        if (state.serviceRunning) "true" else "false"
                    )
                    androidIpText.text = getString(R.string.android_ip_fmt, state.androidIp)
                    picoIpText.text = getString(R.string.pico_ip_fmt, state.picoIp)
                    snText.text = getString(R.string.sn_fmt, state.sn)
                    lastHeartbeatText.text = getString(
                        R.string.last_heartbeat_fmt,
                        TimeUtil.formatReadable(state.lastHeartbeatMs)
                    )
                    packetCountText.text = getString(R.string.packet_count_fmt, state.packetCount)
                    statusText.text = getString(R.string.status_fmt, state.statusMessage)
                    sessionPathText.text = getString(R.string.session_path_fmt, state.sessionPath)
                    batteryOptimizationText.text = getString(
                        R.string.battery_optimization_fmt,
                        batteryOptimizationStatusText()
                    )
                    interfacesText.text = getString(
                        R.string.interfaces_fmt,
                        if (state.allIpv4Interfaces.isEmpty()) "-" else state.allIpv4Interfaces.joinToString("\n")
                    )
                    serviceIpText.text = getString(R.string.service_ip_fmt, state.serviceIp)
                    tcpBindRequestedText.text = getString(R.string.tcp_bind_requested_fmt, state.tcpBindRequested)
                    udpBroadcastText.text = getString(R.string.udp_broadcast_fmt, state.udpBroadcastAddress)
                    serverSocketLocalText.text = getString(R.string.server_socket_local_fmt, state.serverSocketLocalAddress)
                    tcpStartedText.text = getString(
                        R.string.tcp_started_fmt,
                        if (state.tcpServerStarted) "true" else "false"
                    )
                    acceptCountText.text = getString(R.string.accept_count_fmt, state.acceptCount)
                    rejectedCountText.text = getString(R.string.rejected_count_fmt, state.rejectedConnectionCount)
                    lastAcceptErrorText.text = getString(R.string.last_accept_error_fmt, state.lastAcceptError)
                    lastReadErrorText.text = getString(R.string.last_read_error_fmt, state.lastReadError)
                    udpBroadcastCountText.text = getString(R.string.udp_broadcast_count_fmt, state.udpBroadcastCount)
                    selfTestLoopbackText.text = getString(R.string.loopback_self_test_fmt, state.loopbackSelfTest)
                    selfTestWlanText.text = getString(R.string.wlan_self_test_fmt, state.wlanIpSelfTest)
                    selfTestAcceptText.text = getString(R.string.self_test_accept_fmt, state.selfTestAcceptObserved)
                    recentEventsText.text = getString(
                        R.string.recent_events_fmt,
                        if (state.recentEvents.isEmpty()) "-" else state.recentEvents.joinToString("\n")
                    )
                    if (!manualPicoIpInput.isFocused && manualPicoIpInput.text.toString() != state.manualPicoIp) {
                        manualPicoIpInput.setText(state.manualPicoIp)
                    }
                }
            }
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
    }

    companion object {
        private const val PREFS_NAME = "xrt_bridge_prefs"
        private const val KEY_MANUAL_PICO_IP = "manual_pico_ip"
    }
}
