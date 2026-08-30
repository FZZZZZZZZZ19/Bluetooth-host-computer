package com.example.bluetoothassistant

import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatDelegate
import com.example.bluetoothassistant.base.BaseActivity
import com.example.bluetoothassistant.databinding.ActivitySettingsBinding
import com.example.bluetoothassistant.model.LineEnding
import com.example.bluetoothassistant.util.SettingsStore

/**
 * 设置中心：外观（主题模式 / 主题颜色）+ 蓝牙配置（默认换行 / HEX 日志 / 自动进终端）+ 关于
 */
class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val store by lazy { SettingsStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        initNightMode()
        initColorPicker()
        initLineEnding()
        initSwitches()
        binding.aboutVersion.text = BuildConfig.VERSION_NAME
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun initNightMode() {
        val checkedId = when (store.nightMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> binding.rbLight.id
            AppCompatDelegate.MODE_NIGHT_YES -> binding.rbDark.id
            else -> binding.rbSystem.id
        }
        binding.nightModeGroup.check(checkedId)
        binding.nightModeGroup.setOnCheckedChangeListener { _, id ->
            val mode = when (id) {
                binding.rbLight.id -> AppCompatDelegate.MODE_NIGHT_NO
                binding.rbDark.id -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            if (mode != store.nightMode) {
                store.nightMode = mode
                AppCompatDelegate.setDefaultNightMode(mode)
                recreate()
            }
        }
    }

    private fun initColorPicker() {
        val swatches = listOf(
            Pair(binding.colorBlue, 0),
            Pair(binding.colorGreen, 1),
            Pair(binding.colorPurple, 2),
            Pair(binding.colorOrange, 3)
        )
        swatches.forEach { sw ->
            val card = sw.first
            val index = sw.second
            card.isChecked = store.themeColor == index
            card.setOnClickListener {
                if (store.themeColor != index) {
                    store.themeColor = index
                    recreate()
                }
            }
        }
    }

    private fun initLineEnding() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            LineEnding.entries.map { it.label }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.lineEndingSpinner.adapter = adapter
        val current = runCatching { LineEnding.valueOf(store.defaultLineEnding) }
            .getOrDefault(LineEnding.NONE)
        binding.lineEndingSpinner.setSelection(LineEnding.entries.indexOf(current))
        binding.lineEndingSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    store.defaultLineEnding = LineEnding.entries[position].name
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
    }

    private fun initSwitches() {
        binding.swHex.isChecked = store.defaultHexDisplay
        binding.swHex.setOnCheckedChangeListener { _, checked ->
            store.defaultHexDisplay = checked
        }
        binding.swAutoTerminal.isChecked = store.autoOpenTerminal
        binding.swAutoTerminal.setOnCheckedChangeListener { _, checked ->
            store.autoOpenTerminal = checked
        }
        binding.swAutoSend.isChecked = store.autoSendEnabled
        binding.swAutoSend.setOnCheckedChangeListener { _, checked ->
            store.autoSendEnabled = checked
        }
        // 发送间隔
        val intervals = listOf(100, 200, 500, 1000, 2000)
        val intervalAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            intervals.map { "${it} ms" }
        )
        intervalAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.intervalSpinner.adapter = intervalAdapter
        val currentIndex = intervals.indexOf(store.autoSendIntervalMs).coerceAtLeast(0)
        binding.intervalSpinner.setSelection(currentIndex)
        binding.intervalSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    store.autoSendIntervalMs = intervals[position]
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
    }
}
