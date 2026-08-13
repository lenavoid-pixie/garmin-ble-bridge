package com.garmin.ble.mcp

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
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
        const val GARMIN_ADDR = "E0:48:24:A1:A0:3E"
        private val HR_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val HR_MEASUREMENT_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var gatt: BluetoothGatt? = null
    private val writeQueue = ArrayDeque<() -> Unit>()
    private var writeInProgress = false

    fun connect() {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val device = adapter.getRemoteDevice(GARMIN_ADDR)
        gatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
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

            // Garmin は独自 CCCD への書き込みが先に必要。
            // handle ベースのリフレクションは新しい Android で動かないため、
            // 全サービスの CCCD をまとめて有効化する（Garmin 独自のものも含まれる）。
            for (service in gatt.services) {
                for (char in service.characteristics) {
                    val cccd = char.getDescriptor(CCCD_UUID) ?: continue
                    gatt.setCharacteristicNotification(char, true)
                    enqueueWrite {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(cccd)
                    }
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            // HR CCCD の書き込み完了 = 通知受信の準備完了
            if (descriptor.characteristic?.uuid == HR_MEASUREMENT_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) listener.onConnected()
                else listener.onError("Failed to enable HR notifications (status=$status)")
            }
            executeNext()
        }

        // API 33 以上
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == HR_MEASUREMENT_UUID) handleHrValue(value)
        }

        // API 32 以下
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
