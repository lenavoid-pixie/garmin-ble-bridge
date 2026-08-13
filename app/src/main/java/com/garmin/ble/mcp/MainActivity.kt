package com.garmin.ble.mcp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.garmin.ble.mcp.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var bleService: BleService? = null
    private var isBound = false

    companion object {
        private const val REQUEST_PERMISSIONS = 1
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            bleService = (service as BleService.LocalBinder).getService().also { svc ->
                isBound = true
                svc.statusListener = { status -> runOnUiThread { binding.tvStatus.text = status } }
                svc.hrListener = { hr -> runOnUiThread { binding.tvHr.text = "$hr BPM" } }
                svc.clientCountListener = { count -> runOnUiThread { binding.tvClients.text = "PC clients: $count" } }
            }
            updateButtons(running = true)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            isBound = false
            bleService = null
            runOnUiThread { updateButtons(running = false) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvWsPort.text = "WebSocket: port 8765"
        updateButtons(running = false)

        binding.btnStart.setOnClickListener { requestPermissionsAndStart() }

        binding.btnStop.setOnClickListener {
            stopService(Intent(this, BleService::class.java))
            binding.tvStatus.text = "Stopped"
            binding.tvHr.text = "-- BPM"
            updateButtons(running = false)
        }
    }

    override fun onStart() {
        super.onStart()
        // サービスが起動済みなら再バインド（BIND_AUTO_CREATEなしで既存サービスにのみ接続）
        bindService(Intent(this, BleService::class.java), connection, 0)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            bleService?.statusListener = null
            bleService?.hrListener = null
            bleService?.clientCountListener = null
            unbindService(connection)
            isBound = false
            bleService = null
        }
    }

    private fun requestPermissionsAndStart() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startBleService()
        else ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startBleService()
        }
    }

    private fun startBleService() {
        val intent = Intent(this, BleService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        binding.tvStatus.text = "Connecting..."
    }

    private fun updateButtons(running: Boolean) {
        binding.btnStart.isEnabled = !running
        binding.btnStop.isEnabled = running
    }
}
