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
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
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
        // Fallback only — scan is the primary path now (Garmin HR broadcast can use a different address)
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

    fun connect() {
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
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE_UUID)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanning = true
        scanner!!.startScan(filters, settings, scanCallback)
        handler.postDelayed({
            if (scanning) {
                scanning = false
                try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
                Log.d(TAG, "Scan timed out, falling back to hardcoded MAC")
                connectByAddress(GARMIN_ADDR)
            }
        }, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun connectByAddress(address: String) {
        try {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            val device = adapter.getRemoteDevice(address)
            Log.d(TAG, "Direct connect to $address")
            gatt = device.connectGatt(context, false, gattCallback)
        } catch (e: Exception) {
            listener.onError("Connect failed: ${e.message}")
        }
    }

    private val scanCallback: ScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            scanning = false
            try { scanner?.stopScan(this) } catch (_: Exception) {}
            val device = result.device
            Log.d(TAG, "Found HR device: ${device.address} name=${device.name}")
            gatt = device.connectGatt(context, false, gattCallback)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            Log.d(TAG, "Scan failed code=$errorCode, falling back to hardcoded MAC")
            connectByAddress(GARMIN_ADDR)
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
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected, discovering services")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected")
                    listener.onDisconnected()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("Service discovery failed (status=$status)")
                return
            }

            val hrChar = gatt.getService(HR_SERVICE_UUID)?.getCharacteristic(HR_MEASUREMENT_UUID)
            if (hrChar == null) {
                listener.onError("HR service not found. Enable heart rate broadcast mode on the watch.")
                return
            }

            // Garmin requires writing to its own CCCD first. Enable notifications on
            // every characteristic that has a CCCD (covers Garmin's proprietary ones too).
            // For the HR measurement char, subscribe to BOTH notification and indication:
            // Garmin broadcasts often arrive as indications, and a notification-only
            // subscribe can connect silently but never deliver a value.
            for (service in gatt.services) {
                for (char in service.characteristics) {
                    val cccd = char.getDescriptor(CCCD_UUID) ?: continue
                    gatt.setCharacteristicNotification(char, true)
                    val isHr = char.uuid == HR_MEASUREMENT_UUID
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
            if (descriptor.characteristic?.uuid == HR_MEASUREMENT_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) listener.onConnected()
                else listener.onError("Failed to enable HR notifications (status=$status)")
            }
            executeNext()
        }

        // API 33+
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == HR_MEASUREMENT_UUID) handleHrValue(value)
        }

        // API 32 and below
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (characteristic.uuid == HR_MEASUREMENT_UUID) handleHrValue(characteristic.value)
            }
        }
    }

    private fun handleHrValue(value: ByteArray) {
        val (hr, rr) = parseHrMeasurement(value)
        if (hr in 30..220) listener.onHrData(hr, rr)
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
