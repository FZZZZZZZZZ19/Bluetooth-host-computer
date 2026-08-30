package com.example.bluetoothassistant.storage

import android.content.Context
import java.util.UUID

/** BLE 收发特征配置（按设备地址记忆） */
class BlePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("ble_config", Context.MODE_PRIVATE)

    data class Config(val service: UUID?, val tx: UUID?, val rx: UUID?)

    fun load(address: String): Config = Config(
        service = prefs.getString("${address}_service", null)?.let(UUID::fromString),
        tx = prefs.getString("${address}_tx", null)?.let(UUID::fromString),
        rx = prefs.getString("${address}_rx", null)?.let(UUID::fromString)
    )

    fun save(address: String, service: UUID, tx: UUID, rx: UUID?) {
        prefs.edit()
            .putString("${address}_service", service.toString())
            .putString("${address}_tx", tx.toString())
            .putString("${address}_rx", rx?.toString())
            .apply()
    }

    fun clear(address: String) {
        prefs.edit()
            .remove("${address}_service")
            .remove("${address}_tx")
            .remove("${address}_rx")
            .apply()
    }
}
