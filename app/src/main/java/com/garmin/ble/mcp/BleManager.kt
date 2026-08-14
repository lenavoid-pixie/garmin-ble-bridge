package com.garmin.ble.mcp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

class BleManager(private val context: Context, private val listener: HrListener) {

    interface HrListener {
        fun onConnected()
        fun onDisconnected()
        fun onHrData(hr: Int, rrIntervals: List<Int>)
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "BleManager"
        // Fallback only — scan is the primary path (Garmin HR broadcast can use a different address)
        const val GARMIN_ADDR = "E0:48:24:A1:A0:3E"
        private val HR_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val HR_MEASUREMENT_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_TIMEOUT_MS = 20000L
    }

    private var gatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private val writeQueue = ArrayDeque<() -> Unit>()
    private var writeInProgress = false
    private val handler = Handler(Looper.getMainLooper())

    var logListener: ((String) -> Unit)? = null

    private fun log(msg: String) {
        Log.d(TAG, msg)
        logListener?.invoke(msg)
    }

    fun connect() {
        log("connect() — scanning for HR broadcast…")
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        startScan(adapter)
    }

    @SuppressLint("MissingPermission")
    private fun startScan(adapter: BluetoothAdapter) {
        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            listener.onError("Bluetooth scanner unavailable")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanning = true
        // No filter — we want to SEE everything and pick the HR advertiser ourselves.
        scanner!!.startScan(null, settings, scanCallback)
        log("Scanning (20s window)…")
        handler.postDelayed({
            if (scanning) {
                scanning = false
                try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
                log("Scan timeout — falling back to hardcoded MAC $GARMIN_ADDR")
                connectByAddress(GARMIN_ADDR)
            }
        }, SCAN_TIMEOUT_MS)
    }

    private val scanCallback: ScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: "(no name)"
            val addr = result.device.address
            val uuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
            val hasHr = uuids.any { it == HR_SERVICE_UUID }
            log("Seen: $name @ $addr — HRservice=$hasHr uuids=${uuids.take(6)}")
            if (!hasHr) return
            if (!scanning) return
            scanning = false
            try { scanner?.stopScan(this) } catch (_: Exception) {}
            log("Connecting to HR broadcaster: $name @ $addr")
            gatt = result.device.connectGatt(context, false, gattCallback)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            log("Scan failed code=$errorCode — falling back to hardcoded MAC")
            connectByAddress(GARMIN_ADDR)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectByAddress(address: String) {
        try {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            val device = adapter.getRemoteDevice(address)
            log("Direct connect to $address")
            gatt = device.connectGatt(context, false, gattCallback)
        } catch (e: Exception) {
            log("Connect failed: ${e.message}")
            listener.onError("Connect failed: ${e.message}")
        }
    }

    fun disconnect() {
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    private fun enqueueWrite(action: () -> Unit) {
        writeQueue.add(action)
        if (!writeInProgress) executeNext()
    }

    private fun executeNext() {
        if (writeQueue.isEmpty()) { writeInProgress = false; return }
        writeInProgress = true
        writeQueue.removeFirst().invoke()
    }

    @Suppress("DEPRECATION")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            log("Connection state: status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("Connected. Discovering services…")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("Disconnected")
                    listener.onDisconnected()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery failed (status=$status)")
                listener.onError("Service discovery failed (status=$status)")
                return
            }
            val hrChar = gatt.getService(HR_SERVICE_UUID)?.getCharacteristic(HR_MEASUREMENT_UUID)
            log("Services discovered. HR service present=${hrChar != null}. Full list:")
            for (service in gatt.services) {
                for (char in service.characteristics) {
                    log("  svc=${service.uuid} char=${char.uuid}")
                }
            }
            if (hrChar == null) {
                log("HR service NOT found — is Broadcast HR on?")
                listener.onError("HR service not found. Enable heart rate broadcast mode on the watch.")
                return
            }

            // Enable notifications/indications on every CCCD we can find (covers Garmin proprietary too).
            for (service in gatt.services) {
                for (char in service.characteristics) {
                    val cccd = char.getDescriptor(CCCD_UUID) ?: continue
                    gatt.setCharacteristicNotification(char, true)
                    val isHr = char.uuid == HR_MEASUREMENT_UUID
                    log("Subscribing: char=${char.uuid} isHR=$isHr")
                    enqueueWrite {
                        @Suppress("DEPRECATION")
                        cccd.value = if (isHr) byteArrayOf(0x03, 0x00) else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(cccd)
                    }
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val uuid = descriptor.characteristic?.uuid
            log("Descriptor write: char=$uuid status=$status")
            if (uuid == HR_MEASUREMENT_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    log("HR notifications enabled — waiting for data…")
                    listener.onConnected()
                } else {
                    listener.onError("Failed to enable HR notifications (status=$status)")
                }
            }
            executeNext()
        }

        // API 33+
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            log("CHAR CHANGED (api33): uuid=${characteristic.uuid} bytes=${value.joinToString(":") { "%02x".format(it) }}")
            if (characteristic.uuid == HR_MEASUREMENT_UUID) handleHrValue(value)
        }

        // API 32 and below
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            log("CHAR CHANGED (legacy): uuid=${characteristic.uuid}")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (characteristic.uuid == HR_MEASUREMENT_UUID) handleHrValue(characteristic.value)
            }
        }
    }

    private fun handleHrValue(value: ByteArray) {
        val (hr, rr) = parseHrMeasurement(value)
        log("Parsed HR=$hr rr=$rr")
        if (hr in 30..220) listener.onHrData(hr, rr)
        else log("HR out of range ($hr) — ignored")
    }

    private fun parseHrMeasurement(value: ByteArray): Pair<Int, List<Int>> {
        if (value.isEmpty()) return Pair(0, emptyList())
        val flags = value[0].toInt() and 0xFF
        var offset = 1

        val hr = if (flags and 0x01 != 0) {
            val v = ((value[offset + 1].toInt() and 0xFF) shl 8) or (value[offset].toInt() and 0xFF)
            offset += 2; v
        } else {
            val v = value[offset].toInt() and 0xFF
            offset += 1; v
        }

        if (flags and 0x08 != 0) offset += 2 // energy expended

        val rrIntervals = mutableListOf<Int>()
        if (flags and 0x10 != 0) {
            while (offset + 1 < value.size) {
                val raw = ((value[offset + 1].toInt() and 0xFF) shl 8) or (value[offset].toInt() and 0xFF)
                val rrMs = raw * 1000 / 1024
                if (rrMs in 300..2000) rrIntervals.add(rrMs)
                offset += 2
            }
        }

        return Pair(hr, rrIntervals)
    }
}
