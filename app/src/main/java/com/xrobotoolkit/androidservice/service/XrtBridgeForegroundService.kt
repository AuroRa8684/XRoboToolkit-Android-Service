package com.xrobotoolkit.androidservice.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.xrobotoolkit.androidservice.R
import com.xrobotoolkit.androidservice.core.BridgeRepository
import com.xrobotoolkit.androidservice.core.BridgeSupervisor

class XrtBridgeForegroundService : Service() {
    private var supervisor: BridgeSupervisor? = null
    private var started = false
    private var pendingManualPicoIp: String = ""
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        pendingManualPicoIp = prefs.getString(KEY_MANUAL_PICO_IP, "").orEmpty().trim()
        BridgeRepository.update { it.copy(manualPicoIp = pendingManualPicoIp) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopBridge()
            ACTION_START, null -> startBridge()
            ACTION_SET_MANUAL_PICO_IP -> {
                val picoIp = intent.getStringExtra(EXTRA_MANUAL_PICO_IP).orEmpty().trim()
                pendingManualPicoIp = picoIp
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_MANUAL_PICO_IP, picoIp)
                    .apply()
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
        acquireDeviceLocks()
        supervisor = BridgeSupervisor(applicationContext).also {
            it.setManualPicoIp(pendingManualPicoIp)
            it.start()
        }
        BridgeRepository.update { it.copy(serviceRunning = true) }
    }

    private fun stopBridge() {
        if (!started && supervisor == null) return
        supervisor?.stop()
        supervisor = null
        started = false
        releaseDeviceLocks()
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

    private fun acquireDeviceLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:BridgeWakeLock"
            )?.apply {
                setReferenceCounted(false)
                if (!isHeld) {
                    acquire()
                }
            }
            Log.i(TAG, "WakeLock acquired=${wakeLock?.isHeld == true}")
        } catch (t: Throwable) {
            Log.e(TAG, "WakeLock acquire failed: ${t.message}", t)
        }

        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wifiManager?.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "$packageName:BridgeWifiLock"
            )?.apply {
                setReferenceCounted(false)
                if (!isHeld) {
                    acquire()
                }
            }
            Log.i(TAG, "WifiLock acquired=${wifiLock?.isHeld == true}")
        } catch (t: Throwable) {
            Log.e(TAG, "WifiLock acquire failed: ${t.message}", t)
        }
    }

    private fun releaseDeviceLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            Log.i(TAG, "WakeLock released")
        } catch (t: Throwable) {
            Log.e(TAG, "WakeLock release failed: ${t.message}", t)
        } finally {
            wakeLock = null
        }

        try {
            wifiLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            Log.i(TAG, "WifiLock released")
        } catch (t: Throwable) {
            Log.e(TAG, "WifiLock release failed: ${t.message}", t)
        } finally {
            wifiLock = null
        }
    }

    companion object {
        const val ACTION_START = "com.xrobotoolkit.androidservice.action.START"
        const val ACTION_STOP = "com.xrobotoolkit.androidservice.action.STOP"
        const val ACTION_SET_MANUAL_PICO_IP = "com.xrobotoolkit.androidservice.action.SET_MANUAL_PICO_IP"
        const val ACTION_RUN_SELF_TEST = "com.xrobotoolkit.androidservice.action.RUN_SELF_TEST"
        const val ACTION_SEND_UDP_PROBE = "com.xrobotoolkit.androidservice.action.SEND_UDP_PROBE"
        const val ACTION_SEND_UDP_PRIMING = "com.xrobotoolkit.androidservice.action.SEND_UDP_PRIMING"
        const val ACTION_DISCOVERY_NOW = "com.xrobotoolkit.androidservice.action.DISCOVERY_NOW"
        const val EXTRA_MANUAL_PICO_IP = "extra_manual_pico_ip"

        private const val TAG = "XrtBridgeService"
        private const val CHANNEL_ID = "xrt_bridge_channel"
        private const val NOTIFICATION_ID = 10001
        private const val PREFS_NAME = "xrt_bridge_prefs"
        private const val KEY_MANUAL_PICO_IP = "manual_pico_ip"
    }
}
