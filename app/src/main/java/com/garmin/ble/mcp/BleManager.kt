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
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

        // Garmin proprietary GFDI V2 protocol
        private const val SERVICE_CODE = 0x2800
        private const val RX_CODE = 0x2812
        private const val TX_CODE = 0x2822
        private const val GFDI = 1
        private const val REALTIME_HR = 6

        private const val RESP_REGISTER_ML = 1
        private const val RESP_CLOSE_ALL = 6

        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val HR_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private const val SCAN_TIMEOUT_MS = 20000L

        private fun garminUuid(code: Int): UUID =
            UUID.fromString(String.format("6a4e%04x-667b-11e3-949a-0800200c9a66", code))
        private val SERVICE_UUID = garminUuid(SERVICE_CODE)
        private val RX_UUID = garminUuid(RX_CODE)
        private val TX_UUID = garminUuid(TX_CODE)
    }

    private var gatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private val handleToService = mutableMapOf<Int, Int>()
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
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
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
            log("Seen: $name @ $addr — HRservice=$hasHr")
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
            rxChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(RX_UUID)
            txChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(TX_UUID)
            val hrPresent = gatt.getService(HR_SERVICE_UUID) != null
            log("Services discovered. RX=${rxChar != null} TX=${txChar != null} HRservice=$hrPresent")
            if (rxChar == null || txChar == null) {
                log("Garmin proprietary chars NOT found")
                listener.onError("Garmin proprietary characteristic not found")
                return
            }
            val cccd = rxChar!!.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                log("RX CCCD missing")
                listener.onError("RX CCCD missing")
                return
            }
            gatt.setCharacteristicNotification(rxChar, true)
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
            log("Subscribing to Garmin RX char…")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            log("Descriptor write: char=${descriptor.characteristic?.uuid} status=$status")
            if (descriptor.characteristic?.uuid == RX_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    log("RX subscribed — starting Garmin handshake…")
                    listener.onConnected()
                    startHandshake()
                } else {
                    listener.onError("Failed to enable RX notifications (status=$status)")
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == RX_UUID) onRxData(value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (characteristic.uuid == RX_UUID) onRxData(characteristic.value)
            }
        }
    }

    // ── Garmin GFDI V2 handshake + decode ───────────────────────────────────

    // Handshake order (from garmin-ble reference): CLOSE_ALL → register GFDI(1) → register HR(6) → start(0x01)
    private fun startHandshake() {
        val tx = txChar ?: return
        log("Handshake: CLOSE_ALL")
        writeNoResponse(tx, buildCloseAllRequest())
    }

    private fun buildCloseAllRequest(): ByteArray {
        val bb = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(0)            // control handle 0
        bb.put(5)            // CLOSE_ALL_REQ
        bb.putLong(0)        // q = 0
        bb.putShort(2)       // h = CLIENT_ID = 2
        bb.put(0)            // b = 0
        return bb.array()
    }

    private fun buildRegisterMlRequest(service: Int): ByteArray {
        val bb = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(0)                  // control handle 0
        bb.put(0)                  // REGISTER_ML_REQ
        bb.putLong(2)              // q = CLIENT_ID = 2
        bb.putShort(service.toShort()) // h = service code
        bb.put(0)                  // b = 0
        return bb.array()
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeNoResponse(char: BluetoothGattCharacteristic, data: ByteArray) {
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        char.value = data
        val ok = gatt?.writeCharacteristic(char) ?: false
        log("TX write (${data.size} bytes) ok=$ok")
    }

    private fun startService(handle: Int) {
        val tx = txChar ?: return
        writeNoResponse(tx, byteArrayOf(handle.toByte(), 0x01))
        log("Start command sent on handle $handle")
    }

    private fun onRxData(value: ByteArray) {
        if (value.isEmpty()) return
        val b0 = value[0].toInt() and 0xFF
        if (b0 and 0x80 != 0) {
            val handle = (b0 and 0x70) shr 4
            route(handle, value)
        } else if (b0 == 0) {
            if (value.size < 2) return
            when (value[1].toInt() and 0xFF) {
                RESP_CLOSE_ALL -> {
                    log("CLOSE_ALL accepted — registering GFDI(1)")
                    writeNoResponse(txChar!!, buildRegisterMlRequest(GFDI))
                }
                RESP_REGISTER_ML -> {
                    if (value.size >= 14) {
                        val service = (value[10].toInt() and 0xFF) or ((value[11].toInt() and 0xFF) shl 8)
                        val st = value[12].toInt() and 0xFF
                        val handle = value[13].toInt() and 0xFF
                        if (st == 0) {
                            handleToService[handle] = service
                            log("REGISTER_ML_RESP: service=$service -> handle=$handle")
                            when (service) {
                                GFDI -> {
                                    log("GFDI open — registering HR(6)")
                                    writeNoResponse(txChar!!, buildRegisterMlRequest(REALTIME_HR))
                                }
                                REALTIME_HR -> {
                                    log("HR registered — starting stream on handle $handle")
                                    startService(handle)
                                }
                            }
                        } else {
                            log("REGISTER_ML_RESP refused: service=$service status=$st")
                        }
                    }
                }
                else -> log("Control msg type=${value[1].toInt() and 0xFF}")
            }
        } else {
            route(b0, value)
        }
    }

    private fun route(handle: Int, value: ByteArray) {
        val service = handleToService[handle] ?: return
        when (service) {
            REALTIME_HR -> {
                // wire format after routing byte: [padding, hr, resting_hr]; bpm = value[2]
                if (value.size >= 3) {
                    val hr = value[2].toInt() and 0xFF
                    val resting = if (value.size >= 4) value[3].toInt() and 0xFF else 0
                    log("❤️ HR=$hr bpm (resting=$resting)")
                    if (hr in 30..220) listener.onHrData(hr, emptyList())
                }
            }
            else -> log("route: unknown service=$service handle=$handle hex=${value.joinToString(":"){"%02x".format(it)}}")
        }
    }
}
