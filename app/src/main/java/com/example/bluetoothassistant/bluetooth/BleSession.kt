package com.example.bluetoothassistant.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_INDICATE
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
import android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
import android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
import android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.bluetoothassistant.model.ConnectionMode
import com.example.bluetoothassistant.model.ConnectionState
import com.example.bluetoothassistant.storage.BlePrefs
import java.util.UUID

/**
 * BLE（GATT）会话。
 * 连接成功后自动发现服务，优先使用已保存的收发特征配置，
 * 否则自动挑选第一个可写特征（TX）与第一个通知特征（RX）。
 * 带连接超时看门狗与失败原因提示，避免"点了没反应"。
 */
class BleSession(
    private val context: Context,
    private val device: BluetoothDevice,
    private val onData: (ByteArray) -> Unit,
    private val onState: (ConnectionState) -> Unit
) {
    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var rx: BluetoothGattCharacteristic? = null

    private var serviceUuid: UUID? = null
    private var txUuid: UUID? = null
    private var rxUuid: UUID? = null

    /** 是否曾连接成功（区分"正常断开"与"连接失败"） */
    private var connected = false

    private val handler = Handler(Looper.getMainLooper())

    /** 连接超时看门狗：避免 connectGatt 无回调时界面卡在"正在连接" */
    private val timeoutRunnable = Runnable {
        if (!connected) {
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
            gatt = null
            onState(ConnectionState.Error("BLE 连接超时：设备无响应，请确认设备已开启广播且距离较近"))
        }
    }

    val services: List<BluetoothGattService>
        get() = gatt?.services ?: emptyList()

    data class Config(val service: UUID?, val tx: UUID?, val rx: UUID?)

    fun currentConfig(): Config = Config(serviceUuid, txUuid, rxUuid)

    fun start() {
        onState(ConnectionState.Connecting(device.name ?: device.address))
        connected = false
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, CONNECT_TIMEOUT_MS)
        try {
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(context, false, gattCallback)
            }
            if (gatt == null) {
                handler.removeCallbacks(timeoutRunnable)
                onState(ConnectionState.Error("BLE 连接失败：无法创建 GATT 连接，请重试"))
            }
        } catch (e: SecurityException) {
            handler.removeCallbacks(timeoutRunnable)
            onState(ConnectionState.Error("缺少蓝牙权限，无法连接（请在系统设置中授予「附近的设备」权限）"))
        } catch (e: Exception) {
            handler.removeCallbacks(timeoutRunnable)
            onState(ConnectionState.Error("BLE 连接失败：${e.message ?: "未知错误"}"))
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true
                handler.removeCallbacks(timeoutRunnable)
                g.requestMtu(517)
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.removeCallbacks(timeoutRunnable)
                runCatching { g.close() }
                if (gatt === g) gatt = null
                if (connected) {
                    // 已连接后断开
                    connected = false
                    onState(ConnectionState.Disconnected)
                } else {
                    // 从未连上：把失败原因告诉用户
                    onState(ConnectionState.Error("BLE 连接失败：${gattStatusText(status)}"))
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onState(ConnectionState.Error("服务发现失败（${gattStatusText(status)}）"))
                return
            }
            applyConfiguration(g)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            onData(characteristic.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            onData(value)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onState(ConnectionState.Error("写入失败（${gattStatusText(status)}）"))
            }
        }
    }

    private fun applyConfiguration(g: BluetoothGatt) {
        val saved = BlePrefs(context).load(device.address)

        var svc: BluetoothGattService? = null
        var txChar: BluetoothGattCharacteristic? = null
        var rxChar: BluetoothGattCharacteristic? = null

        // 1. 使用已保存的配置
        if (saved.service != null && saved.tx != null) {
            svc = g.getService(saved.service)
            txChar = svc?.getCharacteristic(saved.tx)
            rxChar = saved.rx?.let { svc?.getCharacteristic(it) }
        }

        // 2. 未保存或失效时自动挑选第一个可写特征
        if (txChar == null) {
            outer@ for (s in g.services) {
                val write = s.characteristics.firstOrNull { c ->
                    c.properties and (PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE) != 0
                }
                if (write != null) {
                    svc = s
                    txChar = write
                    break@outer
                }
            }
        }

        if (txChar == null || svc == null) {
            onState(ConnectionState.Error("未找到可写的 BLE 特征，请在终端菜单中手动配置"))
            return
        }

        // 3. 自动挑选第一个通知/指示特征作为 RX
        if (rxChar == null) {
            rxChar = svc.characteristics.firstOrNull { c ->
                c.properties and (PROPERTY_NOTIFY or PROPERTY_INDICATE) != 0
            }
        }

        tx = txChar
        rx = rxChar
        serviceUuid = svc.uuid
        txUuid = txChar.uuid
        rxUuid = rxChar?.uuid

        // 自动选择结果也保存，下次连接直接应用
        if (saved.service == null) {
            BlePrefs(context).save(device.address, svc.uuid, txChar.uuid, rxChar?.uuid)
        }

        rxChar?.let { subscribe(g, it) }
        onState(ConnectionState.Connected(device.name ?: device.address, ConnectionMode.BLE))
    }

    /** 手动指定收发特征（由终端界面的 BLE 设置触发） */
    fun configure(serviceUuid: UUID, txUuid: UUID, rxUuid: UUID?) {
        val g = gatt
        if (g == null) {
            onState(ConnectionState.Error("尚未连接"))
            return
        }
        val svc = g.getService(serviceUuid)
        if (svc == null) {
            onState(ConnectionState.Error("未找到服务 $serviceUuid"))
            return
        }
        val txChar = svc.getCharacteristic(txUuid)
        if (txChar == null) {
            onState(ConnectionState.Error("未找到 TX 特征 $txUuid"))
            return
        }
        val rxChar = rxUuid?.let { svc.getCharacteristic(it) }

        this.serviceUuid = serviceUuid
        this.txUuid = txUuid
        this.rxUuid = rxUuid
        tx = txChar
        rx = rxChar

        BlePrefs(context).save(device.address, serviceUuid, txUuid, rxUuid)
        rxChar?.let { subscribe(g, it) }
        onState(ConnectionState.Connected(device.name ?: device.address, ConnectionMode.BLE))
    }

    private fun subscribe(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(CCCD_UUID) ?: return
        val value = if (characteristic.properties and PROPERTY_INDICATE != 0) {
            ENABLE_INDICATION_VALUE
        } else {
            ENABLE_NOTIFICATION_VALUE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
    }

    fun send(bytes: ByteArray) {
        val g = gatt
        val c = tx
        if (g == null || c == null) {
            onState(ConnectionState.Error(if (g == null) "设备未连接" else "未配置发送特征"))
            return
        }
        val writeType = if (c.properties and PROPERTY_WRITE_NO_RESPONSE != 0) {
            WRITE_TYPE_NO_RESPONSE
        } else {
            WRITE_TYPE_DEFAULT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, bytes, writeType)
        } else {
            @Suppress("DEPRECATION")
            c.value = bytes
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
    }

    fun disconnect() {
        handler.removeCallbacks(timeoutRunnable)
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        tx = null
        rx = null
        connected = false
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        /** 常见 GATT status 的中文提示 */
        private fun gattStatusText(status: Int): String = when (status) {
            BluetoothGatt.GATT_SUCCESS -> "成功"
            8 -> "认证失败（设备可能需要配对）"
            133 -> "连接被拒绝（确认设备已开启广播、距离较近、未被其他设备占用，稍后重试）"
            137 -> "连接超时"
            else -> "错误码 $status"
        }
    }
}
