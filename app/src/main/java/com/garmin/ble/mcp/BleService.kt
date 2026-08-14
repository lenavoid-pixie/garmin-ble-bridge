package com.garmin.ble.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class BleService : Service(), BleManager.HrListener {

    inner class LocalBinder : Binder() {
        fun getService() = this@BleService
    }

    private val binder = LocalBinder()
    private lateinit var bleManager: BleManager
    private lateinit var wsServer: HrWebSocketServer
    private var clientCount = 0
    private val logPoster = Executors.newSingleThreadExecutor()

    var statusListener: ((String) -> Unit)? = null
    var hrListener: ((Int) -> Unit)? = null
    var clientCountListener: ((Int) -> Unit)? = null
    var logListener: ((String) -> Unit)? = null

    companion object {
        private const val CHANNEL_ID = "garmin_ble_channel"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "com.garmin.ble.mcp.STOP"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        bleManager = BleManager(this, this).also { bm ->
            bm.logListener = { line ->
                logListener?.invoke(line)
                postLog(line)
            }
        }
        wsServer = HrWebSocketServer(8765).apply {
            onClientConnected = {
                clientCount++
                clientCountListener?.invoke(clientCount)
            }
            onClientDisconnected = {
                clientCount--
                clientCountListener?.invoke(clientCount)
            }
            start()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification("Connecting..."))
        bleManager.connect()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        bleManager.disconnect()
        wsServer.stop()
        super.onDestroy()
    }

    private fun postLog(line: String) {
        logPoster.execute {
            try {
                val conn = URL("http://127.0.0.1:8090/api/bridge/log").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "text/plain")
                conn.outputStream.use { it.write(line.toByteArray()) }
                conn.inputStream.close()
            } catch (_: Exception) {
                // server may be down; log still shows on screen
            }
        }
    }

    override fun onConnected() {
        updateNotification("Connected — HR broadcasting")
        statusListener?.invoke("Connected — HR broadcasting")
    }

    override fun onDisconnected() {
        updateNotification("Disconnected")
        statusListener?.invoke("Disconnected")
    }

    override fun onHrData(hr: Int, rrIntervals: List<Int>) {
        updateNotification("HR: $hr BPM")
        hrListener?.invoke(hr)
        wsServer.sendHrData(hr, rrIntervals)
    }

    override fun onError(message: String) {
        updateNotification("Error: $message")
        statusListener?.invoke("Error: $message")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Garmin BLE Bridge",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val stopPendingIntent = PendingIntent.getService(
            this, 0,
            Intent(this, BleService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val openPendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Garmin BLE Bridge")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(status))
    }
}
