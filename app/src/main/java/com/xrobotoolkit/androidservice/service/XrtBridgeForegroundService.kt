package com.xrobotoolkit.androidservice.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.xrobotoolkit.androidservice.R
import com.xrobotoolkit.androidservice.core.BridgeRepository
import com.xrobotoolkit.androidservice.core.BridgeSupervisor
import com.xrobotoolkit.androidservice.network.BindMode

class XrtBridgeForegroundService : Service() {
    private var supervisor: BridgeSupervisor? = null
    private var started = false
    private var pendingBindMode: BindMode = BindMode.ANY_IPV4
    private var pendingManualPicoIp: String = "192.168.123.22"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopBridge()
            ACTION_START, null -> startBridge()
            ACTION_SET_BIND_MODE -> {
                val bindModeName = intent.getStringExtra(EXTRA_BIND_MODE)
                val bindMode = BindMode.fromName(bindModeName)
                pendingBindMode = bindMode
                supervisor?.setBindMode(bindMode)
                BridgeRepository.update { it.copy(bindMode = bindMode.name) }
                Log.i(TAG, "Bind mode intent: ${bindMode.name}")
            }
            ACTION_SET_MANUAL_PICO_IP -> {
                val picoIp = intent.getStringExtra(EXTRA_MANUAL_PICO_IP).orEmpty().trim()
                if (picoIp.isBlank()) return START_STICKY
                pendingManualPicoIp = picoIp
                supervisor?.setManualPicoIp(picoIp)
                BridgeRepository.update { it.copy(manualPicoIp = picoIp) }
                Log.i(TAG, "Manual PICO IP intent: $picoIp")
            }
            ACTION_RUN_SELF_TEST -> supervisor?.runTcpSelfTests()
            ACTION_SEND_UDP_PROBE -> supervisor?.sendUdpProbeToManualPico()
            ACTION_SEND_UDP_PRIMING -> supervisor?.sendUdpPrimingToManualPico()
            ACTION_DISCOVERY_NOW -> supervisor?.triggerDiscoveryNow()
        }
        return START_STICKY
    }

    private fun startBridge() {
        if (started) return
        started = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        supervisor = BridgeSupervisor(applicationContext).also {
            it.setBindMode(pendingBindMode)
            it.setManualPicoIp(pendingManualPicoIp)
            it.start()
        }
        BridgeRepository.update { it.copy(serviceRunning = true) }
    }

    private fun stopBridge() {
        supervisor?.stop()
        supervisor = null
        started = false
        BridgeRepository.update { it.copy(serviceRunning = false) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopBridge()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "XRoboToolkit Bridge",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.xrobotoolkit.androidservice.action.START"
        const val ACTION_STOP = "com.xrobotoolkit.androidservice.action.STOP"
        const val ACTION_SET_BIND_MODE = "com.xrobotoolkit.androidservice.action.SET_BIND_MODE"
        const val ACTION_SET_MANUAL_PICO_IP = "com.xrobotoolkit.androidservice.action.SET_MANUAL_PICO_IP"
        const val ACTION_RUN_SELF_TEST = "com.xrobotoolkit.androidservice.action.RUN_SELF_TEST"
        const val ACTION_SEND_UDP_PROBE = "com.xrobotoolkit.androidservice.action.SEND_UDP_PROBE"
        const val ACTION_SEND_UDP_PRIMING = "com.xrobotoolkit.androidservice.action.SEND_UDP_PRIMING"
        const val ACTION_DISCOVERY_NOW = "com.xrobotoolkit.androidservice.action.DISCOVERY_NOW"
        const val EXTRA_BIND_MODE = "extra_bind_mode"
        const val EXTRA_MANUAL_PICO_IP = "extra_manual_pico_ip"

        private const val TAG = "XrtBridgeService"
        private const val CHANNEL_ID = "xrt_bridge_channel"
        private const val NOTIFICATION_ID = 10001
    }
}
