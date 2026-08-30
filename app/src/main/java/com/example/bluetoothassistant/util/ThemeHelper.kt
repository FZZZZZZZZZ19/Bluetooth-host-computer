package com.example.bluetoothassistant.util

import android.content.Context
import com.example.bluetoothassistant.R

object ThemeHelper {
    /** 根据设置的主题颜色返回主题资源 */
    fun themeResId(context: Context): Int = when (SettingsStore(context).themeColor) {
        1 -> R.style.Theme_BluetoothAssistant_Green
        2 -> R.style.Theme_BluetoothAssistant_Purple
        3 -> R.style.Theme_BluetoothAssistant_Orange
        else -> R.style.Theme_BluetoothAssistant
    }
}
