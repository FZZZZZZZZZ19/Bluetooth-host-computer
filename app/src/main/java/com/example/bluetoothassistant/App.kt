package com.example.bluetoothassistant

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.example.bluetoothassistant.bluetooth.ConnectionManager
import com.example.bluetoothassistant.util.SettingsStore

class App : Application() {
    lateinit var connectionManager: ConnectionManager
        private set

    override fun onCreate() {
        super.onCreate()
        connectionManager = ConnectionManager(this)
        // 应用用户选择的主题模式（浅色/深色/跟随系统）
        AppCompatDelegate.setDefaultNightMode(SettingsStore(this).nightMode)
    }
}
