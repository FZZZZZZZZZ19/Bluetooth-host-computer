package com.example.bluetoothassistant.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** 全局设置存储（外观 + 蓝牙相关配置） */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 主题模式：AppCompatDelegate.MODE_NIGHT_* */
    var nightMode: Int
        get() = prefs.getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) = prefs.edit().putInt(KEY_NIGHT_MODE, value).apply()

    /** 主题颜色：0 蓝 / 1 绿 / 2 紫 / 3 橙 */
    var themeColor: Int
        get() = prefs.getInt(KEY_THEME_COLOR, 0)
        set(value) = prefs.edit().putInt(KEY_THEME_COLOR, value).apply()

    /** 终端默认发送换行（LineEnding 枚举名） */
    var defaultLineEnding: String
        get() = prefs.getString(KEY_LINE_ENDING, "NONE") ?: "NONE"
        set(value) = prefs.edit().putString(KEY_LINE_ENDING, value).apply()

    /** 终端日志默认以 HEX 显示 */
    var defaultHexDisplay: Boolean
        get() = prefs.getBoolean(KEY_HEX, false)
        set(value) = prefs.edit().putBoolean(KEY_HEX, value).apply()

    /** 连接成功后自动进入终端 */
    var autoOpenTerminal: Boolean
        get() = prefs.getBoolean(KEY_AUTO_TERMINAL, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_TERMINAL, value).apply()

    /** 持续发送模式（静态命令按间隔重复发送；滑杆命令拖动即发） */
    var autoSendEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SEND, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SEND, value).apply()

    /** 持续发送间隔（毫秒） */
    var autoSendIntervalMs: Int
        get() = prefs.getInt(KEY_AUTO_SEND_INTERVAL, 500)
        set(value) = prefs.edit().putInt(KEY_AUTO_SEND_INTERVAL, value).apply()

    companion object {
        private const val KEY_NIGHT_MODE = "night_mode"
        private const val KEY_THEME_COLOR = "theme_color"
        private const val KEY_LINE_ENDING = "default_line_ending"
        private const val KEY_HEX = "default_hex"
        private const val KEY_AUTO_TERMINAL = "auto_open_terminal"
        private const val KEY_AUTO_SEND = "auto_send_enabled"
        private const val KEY_AUTO_SEND_INTERVAL = "auto_send_interval"
    }
}
