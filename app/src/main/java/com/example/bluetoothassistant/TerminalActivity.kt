package com.example.bluetoothassistant

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Intent
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.bluetoothassistant.base.BaseActivity
import com.example.bluetoothassistant.bluetooth.ConnectionManager
import com.example.bluetoothassistant.bluetooth.HexUtils
import com.example.bluetoothassistant.databinding.ActivityTerminalBinding
import com.example.bluetoothassistant.databinding.DialogSliderControlBinding
import com.example.bluetoothassistant.databinding.ItemSliderControlBinding
import com.example.bluetoothassistant.model.CommandEncoding
import com.example.bluetoothassistant.model.CommandItem
import com.example.bluetoothassistant.model.CommandType
import com.example.bluetoothassistant.model.ConnectionMode
import com.example.bluetoothassistant.model.ConnectionState
import com.example.bluetoothassistant.model.LineEnding
import com.example.bluetoothassistant.model.SliderCommand
import com.example.bluetoothassistant.model.SliderValueFormat
import com.example.bluetoothassistant.storage.CommandStore
import com.example.bluetoothassistant.util.SettingsStore
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 终端界面：收发日志、快捷命令、文本/HEX 发送、换行设置、BLE 特征配置。
 */
class TerminalActivity : BaseActivity() {

    private lateinit var binding: ActivityTerminalBinding
    private val connection: ConnectionManager get() = (application as App).connectionManager
    private val commandStore by lazy { CommandStore(this) }
    private val settings by lazy { SettingsStore(this) }

    private var displayHex = false
    private var lineEnding = LineEnding.NONE
    private var userInitiatedDisconnect = false

    /** 持续发送任务与正在循环发送的命令 id */
    private var autoSendJob: Job? = null
    private var autoSendingCommandId: Long? = null

    private val logEntries = ArrayDeque<LogEntry>()
    private val logBuilder = StringBuilder()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private data class LogEntry(val incoming: Boolean, val bytes: ByteArray, val time: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: getString(R.string.unknown_device)
        binding.toolbar.title = deviceName
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // 应用设置中的默认显示与换行
        displayHex = settings.defaultHexDisplay
        lineEnding = runCatching { LineEnding.valueOf(settings.defaultLineEnding) }
            .getOrDefault(LineEnding.NONE)

        setupLineEndingSpinner()
        setupInputActions()
        binding.btnSend.setOnClickListener { sendInput() }

        lifecycleScope.launch {
            connection.state.collect { onConnectionState(it) }
        }
        lifecycleScope.launch {
            connection.incoming.collect { data -> appendLog(true, data) }
        }

        refreshQuickCommands()
    }

    override fun onResume() {
        super.onResume()
        refreshQuickCommands()
    }

    override fun onPause() {
        super.onPause()
        // 离开终端页时停止持续发送
        stopAutoSend()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoSend()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_terminal, menu)
        menu.findItem(R.id.action_toggle_hex).title =
            getString(if (displayHex) R.string.show_ascii else R.string.show_hex)
        menu.findItem(R.id.action_ble_config).isVisible = connection.mode == ConnectionMode.BLE
        menu.findItem(R.id.action_auto_send).isChecked = settings.autoSendEnabled
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_toggle_hex -> {
                displayHex = !displayHex
                item.title = getString(if (displayHex) R.string.show_ascii else R.string.show_hex)
                rerenderLog()
                true
            }
            R.id.action_auto_send -> {
                val enabled = !item.isChecked
                item.isChecked = enabled
                settings.autoSendEnabled = enabled
                if (enabled) {
                    Snackbar.make(
                        binding.root,
                        getString(R.string.auto_send_running, settings.autoSendIntervalMs),
                        Snackbar.LENGTH_SHORT
                    ).show()
                } else {
                    stopAutoSend()
                    Snackbar.make(binding.root, R.string.auto_send_stopped, Snackbar.LENGTH_SHORT).show()
                }
                true
            }
            R.id.action_clear_log -> {
                clearLog()
                true
            }
            R.id.action_commands -> {
                startActivity(Intent(this, CommandActivity::class.java))
                true
            }
            R.id.action_ble_config -> {
                showBleSettings()
                true
            }
            R.id.action_disconnect -> {
                userInitiatedDisconnect = true
                connection.disconnect()
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun onConnectionState(s: ConnectionState) {
        when (s) {
            is ConnectionState.Connected ->
                binding.toolbar.subtitle = getString(R.string.status_connected, s.deviceName)
            is ConnectionState.Connecting ->
                binding.toolbar.subtitle = getString(R.string.status_connecting, s.deviceName)
            is ConnectionState.Error -> {
                binding.toolbar.subtitle = s.message
                Snackbar.make(binding.root, s.message, Snackbar.LENGTH_SHORT).show()
            }
            ConnectionState.Disconnected -> {
                binding.toolbar.subtitle = getString(R.string.status_disconnected)
                stopAutoSend()
                if (!userInitiatedDisconnect) {
                    Snackbar.make(binding.root, R.string.connection_lost, Snackbar.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun appendLog(incoming: Boolean, data: ByteArray) {
        val entry = LogEntry(incoming, data, System.currentTimeMillis())
        logEntries.addLast(entry)
        while (logEntries.size > MAX_LOG_ENTRIES) logEntries.removeFirst()
        logBuilder.append(formatEntry(entry)).append('\n')
        if (logBuilder.length > MAX_LOG_CHARS) {
            logBuilder.delete(0, logBuilder.length / 2)
        }
        binding.logView.text = colorizeLog(logBuilder.toString())
        binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun rerenderLog() {
        logBuilder.setLength(0)
        logEntries.forEach { logBuilder.append(formatEntry(it)).append('\n') }
        binding.logView.text = colorizeLog(logBuilder.toString())
        binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun clearLog() {
        logEntries.clear()
        logBuilder.setLength(0)
        binding.logView.text = ""
    }

    private fun formatEntry(entry: LogEntry): String {
        val time = timeFormat.format(Date(entry.time))
        val direction = if (entry.incoming) "RX" else "TX"
        val body = if (displayHex) HexUtils.bytesToHex(entry.bytes) else decodeText(entry.bytes)
        return "[$time] $direction: $body"
    }

    private fun decodeText(bytes: ByteArray): String {
        val text = String(bytes, Charsets.UTF_8)
        return buildString {
            for (ch in text) {
                when {
                    ch == '\r' || ch == '\n' -> append(" ↵ ")
                    ch.code < 0x20 -> append('.')
                    else -> append(ch)
                }
            }
        }
    }

    private fun colorizeLog(text: String): SpannableString {
        val spannable = SpannableString(text)
        var start = 0
        while (start < text.length) {
            val end = text.indexOf('\n', start).let { if (it == -1) text.length else it }
            val line = text.substring(start, end)
            val color = if (line.contains("RX:")) rxColor() else txColor()
            spannable.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            start = end + 1
        }
        return spannable
    }

    private fun rxColor(): Int = ContextCompat.getColor(this, R.color.rx_color)
    private fun txColor(): Int = ContextCompat.getColor(this, R.color.tx_color)

    private fun setupLineEndingSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            LineEnding.entries.map { it.label }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.lineEndingSpinner.adapter = adapter
        binding.lineEndingSpinner.setSelection(LineEnding.entries.indexOf(lineEnding))
        binding.lineEndingSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    lineEnding = LineEnding.entries[position]
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
    }

    private fun setupInputActions() {
        binding.inputModeToggle.check(binding.btnText.id)
        binding.inputEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendInput()
                true
            } else {
                false
            }
        }
    }

    private fun sendInput() {
        val text = binding.inputEdit.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val textMode = binding.inputModeToggle.checkedButtonId == binding.btnText.id
        val payload: ByteArray = if (textMode) {
            text.toByteArray(Charsets.UTF_8)
        } else {
            try {
                HexUtils.hexToBytes(text)
            } catch (e: IllegalArgumentException) {
                Toast.makeText(
                    this,
                    e.message ?: getString(R.string.hex_invalid),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
        }
        sendPayload(payload)
    }

    private fun sendPayload(payload: ByteArray) {
        val full = payload + lineEnding.bytes
        if (connection.send(full)) {
            appendLog(false, full)
        }
    }

    private fun sendCommand(item: CommandItem) {
        // 滑杆命令：打开滑杆控制面板
        if (item.type == CommandType.SLIDER) {
            showSliderDialog(item)
            return
        }
        // 持续发送模式：静态命令进入循环发送（再点一次停止）
        if (settings.autoSendEnabled) {
            toggleAutoSendStatic(item)
            return
        }
        sendStaticCommand(item)
    }

    /** 单次发送静态命令 */
    private fun sendStaticCommand(item: CommandItem) {
        val payload: ByteArray = when (item.encoding) {
            CommandEncoding.ASCII -> item.content.toByteArray(Charsets.UTF_8)
            CommandEncoding.HEX -> try {
                HexUtils.hexToBytes(item.content)
            } catch (e: IllegalArgumentException) {
                Toast.makeText(
                    this,
                    getString(R.string.cmd_hex_invalid, item.name),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
        }
        val full = payload + item.lineEnding.bytes
        if (connection.send(full)) {
            appendLog(false, full)
        }
    }

    /** 切换静态命令的持续发送：开始 / 停止 */
    private fun toggleAutoSendStatic(item: CommandItem) {
        if (autoSendJob?.isActive == true && autoSendingCommandId == item.id) {
            stopAutoSend()
            Snackbar.make(binding.root, R.string.auto_send_stopped, Snackbar.LENGTH_SHORT).show()
            return
        }
        stopAutoSend()
        autoSendingCommandId = item.id
        autoSendJob = lifecycleScope.launch {
            while (isActive) {
                sendStaticCommand(item)
                delay(settings.autoSendIntervalMs.toLong())
            }
        }
        binding.toolbar.subtitle = getString(R.string.auto_send_running, settings.autoSendIntervalMs)
        refreshQuickCommands()
    }

    private fun stopAutoSend() {
        autoSendJob?.cancel()
        autoSendJob = null
        autoSendingCommandId = null
        refreshQuickCommands()
    }

    /** 滑杆命令控制面板：拖动滑杆调整各通道数值，实时预览，点击发送组合命令 */
    private fun showSliderDialog(item: CommandItem) {
        val cmd = item.slider ?: return
        val db = DialogSliderControlBinding.inflate(layoutInflater)
        val values = IntArray(cmd.sliders.size)

        // 拖动即发送：默认跟随全局持续发送设置（本次会话内可单独切换）
        db.swAutoSend.isChecked = settings.autoSendEnabled

        cmd.sliders.forEachIndexed { index, def ->
            val row = ItemSliderControlBinding.inflate(layoutInflater, db.sliderContainer, false)
            row.sliderName.text = def.name
            row.sliderSeek.min = def.min
            row.sliderSeek.max = def.max
            values[index] = def.defaultValue.coerceIn(def.min, def.max)
            row.sliderSeek.progress = values[index] - def.min
            row.sliderValue.text = values[index].toString()
            row.sliderSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    values[index] = def.min + progress
                    row.sliderValue.text = values[index].toString()
                    db.sliderPreview.text = buildSliderPreview(cmd, values)
                    // 持续控制：每动一次滑杆就立即发送一次
                    if (fromUser && db.swAutoSend.isChecked) {
                        sendSliderPayload(cmd, values, item.lineEnding)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
            db.sliderContainer.addView(row.root)
        }
        db.sliderPreview.text = buildSliderPreview(cmd, values)

        val dialog = AlertDialog.Builder(this)
            .setTitle(item.name)
            .setView(db.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.send, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                sendSliderPayload(cmd, values, item.lineEnding)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /** 发送一次滑杆组合命令并记录 TX 日志 */
    private fun sendSliderPayload(cmd: SliderCommand, values: IntArray, lineEnding: LineEnding) {
        val payload = buildSliderPayload(cmd, values)
        val full = payload + lineEnding.bytes
        if (connection.send(full)) {
            appendLog(false, full)
        }
    }

    /** 组装滑杆命令：prefix + 值0 + 分隔符 + 值1 + … + suffix（UTF-8 文本） */
    private fun buildSliderPayload(cmd: SliderCommand, values: IntArray): ByteArray =
        buildSliderPreview(cmd, values).toByteArray(Charsets.UTF_8)

    private fun buildSliderPreview(cmd: SliderCommand, values: IntArray): String {
        val sb = StringBuilder(cmd.prefix)
        values.forEachIndexed { i, v ->
            if (i > 0) sb.append(cmd.separator)
            sb.append(formatSliderValue(v, cmd.valueFormat))
        }
        sb.append(cmd.suffix)
        return sb.toString()
    }

    private fun formatSliderValue(value: Int, format: SliderValueFormat): String =
        if (format == SliderValueFormat.HEX) {
            String.format(Locale.US, "%02X", value)
        } else {
            value.toString()
        }

    private fun refreshQuickCommands() {
        binding.quickCommands.removeAllViews()
        val commands = commandStore.load()
        commands.forEach { item ->
            val button = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = item.name
                isAllCaps = false
                minWidth = 0
                // 正在持续发送的命令高亮显示
                isChecked = autoSendingCommandId == item.id && autoSendJob?.isActive == true
                setOnClickListener { sendCommand(item) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            binding.quickCommands.addView(button, lp)
        }
        binding.quickScroll.visibility = if (commands.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showBleSettings() {
        val services = connection.bleServices
        if (services.isEmpty()) {
            Toast.makeText(this, R.string.no_services, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.ble_settings)
            .setItems(arrayOf(getString(R.string.pick_tx), getString(R.string.pick_rx))) { _, which ->
                if (which == 0) {
                    pickCharacteristic(writable = true) { svc, ch ->
                        connection.bleConfigureTx(svc.uuid, ch.uuid)
                    }
                } else {
                    pickCharacteristic(writable = false) { svc, ch ->
                        connection.bleConfigureRx(svc.uuid, ch.uuid)
                    }
                }
            }
            .show()
    }

    private fun pickCharacteristic(
        writable: Boolean,
        onPick: (BluetoothGattService, BluetoothGattCharacteristic) -> Unit
    ) {
        val services = connection.bleServices
        val candidates = services.flatMap { s -> s.characteristics.map { s to it } }.filter { (_, c) ->
            if (writable) {
                c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            } else {
                c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            }
        }
        if (candidates.isEmpty()) {
            Toast.makeText(this, R.string.no_characteristics, Toast.LENGTH_SHORT).show()
            return
        }
        val names = candidates.map { (s, c) ->
            "${s.uuid}\n${c.uuid}  ${describeProperties(c.properties)}"
        }
        AlertDialog.Builder(this)
            .setTitle(if (writable) R.string.pick_tx else R.string.pick_rx)
            .setItems(names.toTypedArray()) { _, index ->
                val (svc, ch) = candidates[index]
                onPick(svc, ch)
            }
            .show()
    }

    private fun describeProperties(properties: Int): String {
        val list = mutableListOf<String>()
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) list.add("READ")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) list.add("WRITE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) list.add("WRITE_NR")
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) list.add("NOTIFY")
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) list.add("INDICATE")
        return list.joinToString("|")
    }

    companion object {
        const val EXTRA_DEVICE_NAME = "device_name"
        private const val MAX_LOG_ENTRIES = 500
        private const val MAX_LOG_CHARS = 200_000
    }
}
