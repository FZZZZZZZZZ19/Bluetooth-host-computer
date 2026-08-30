package com.example.bluetoothassistant.model

/** 连接模式 */
enum class ConnectionMode(val label: String) {
    CLASSIC("经典蓝牙"),
    BLE("BLE")
}

/** 连接状态 */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val deviceName: String) : ConnectionState
    data class Connected(val deviceName: String, val mode: ConnectionMode) : ConnectionState
    data class Error(val message: String) : ConnectionState
}
