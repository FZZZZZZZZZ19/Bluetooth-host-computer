package com.example.bluetoothassistant.base

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.bluetoothassistant.util.ThemeHelper

/** 所有 Activity 的基类：在创建前应用用户选择的主题颜色 */
abstract class BaseActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeHelper.themeResId(this))
        super.onCreate(savedInstanceState)
    }
}
