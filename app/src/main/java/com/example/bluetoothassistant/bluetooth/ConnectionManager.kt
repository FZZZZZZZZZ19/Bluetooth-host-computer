package com.example.bluetoothassistant.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import com.example.bluetoothassistant.model.ConnectionMode
import com.example.bluetoothassistant.model.ConnectionState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * 全局连接管理器：同时管理经典蓝牙与 BLE 会话，
 * 对外暴露连接状态 StateFlow 与接收数据 SharedFlow。
 */
class ConnectionManager(private val context: Context) {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()

    private var classic: ClassicSession? = null
    private var ble: BleSession? = null

    /** 当前连接的设备地址（用于主界面判断点击的设备是否已连接） */
    var connectedAddress: String? = null
        private set

    val mode: ConnectionMode
        get() = if (ble != null) ConnectionMode.BLE else ConnectionMode.CLASSIC

    val bleServices: List<BluetoothGattService>
        get() = ble?.services ?: emptyList()

    fun connectClassic(device: BluetoothDevice) {
        cancelDiscovery()
        disconnect()
        connectedAddress = device.address
        var session: ClassicSession? = null
        session = ClassicSession(
            device = device,
            onData = { data -> _incoming.tryEmit(data) },
            onState = { s -> if (classic === session) _state.value = s }
        )
        classic = session
        session?.start()
    }

    fun connectBle(device: BluetoothDevice) {
        cancelDiscovery()
        disconnect()
        connectedAddress = device.address
        var session: BleSession? = null
        session = BleSession(
            context = context,
            device = device,
            onData = { data -> _incoming.tryEmit(data) },
            onState = { s -> if (ble === session) _state.value = s }
        )
        ble = session
        session?.start()
    }

    /** 连接前必须取消设备搜索，否则系统会中断连接（HC-06 常见 read failed 的根源之一） */
    private fun cancelDiscovery() {
        runCatching {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            manager?.adapter?.cancelDiscovery()
        }
    }

    fun bleConfigureTx(serviceUuid: UUID, txUuid: UUID) {
        val session = ble ?: return
        val current = session.currentConfig()
        session.configure(serviceUuid, txUuid, current.rx)
    }

    fun bleConfigureRx(serviceUuid: UUID, rxUuid: UUID) {
        val session = ble ?: return
        val current = session.currentConfig()
        session.configure(serviceUuid, current.tx ?: return, rxUuid)
    }

    /** 发送数据；返回是否已进入发送流程 */
    fun send(bytes: ByteArray): Boolean {
        return when {
            ble != null -> {
                ble!!.send(bytes)
                true
            }
            classic != null -> {
                classic!!.send(bytes)
                true
            }
            else -> {
                _state.value = ConnectionState.Error("当前未连接任何设备")
                false
            }
        }
    }

    fun disconnect() {
        classic?.disconnect()
        ble?.disconnect()
        classic = null
        ble = null
        connectedAddress = null
        if (_state.value != ConnectionState.Disconnected) {
            _state.value = ConnectionState.Disconnected
        }
    }
}
