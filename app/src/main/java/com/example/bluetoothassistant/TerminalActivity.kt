package com.example.bluetoothassistant

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.example.bluetoothassistant.model.SequenceCommand
import com.example.bluetoothassistant.model.SliderCommand
import com.example.bluetoothassistant.model.SliderDef
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

    /** 滑杆循环扫描任务 */
    private var scanJob: Job? = null

    /** 保存日志到文件（系统文件选择器） */
    private val saveLogLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri ?: return@registerForActivityResult
            runCatching {
                contentResolver.openOutputStream(uri)?.use { out ->
                    val sb = StringBuilder()
                    logEntries.forEach { sb.append(formatEntry(it)).append('\n') }
                    out.write(sb.toString().toByteArray(Charsets.UTF_8))
                }
                Snackbar.make(binding.root, R.string.log_saved, Snackbar.LENGTH_SHORT).show()
            }.onFailure {
                Snackbar.make(binding.root, R.string.log_save_failed, Snackbar.LENGTH_SHORT).show()
            }
        }

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
        // 应用屏幕常亮设置（从设置中心返回时也生效）
        binding.root.keepScreenOn = settings.keepScreenOn
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
            R.id.action_save_log -> {
                saveLogLauncher.launch("bluetooth_log.txt")
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

    /** 单次发送静态型命令（静态命令 / 组合命令），失败返回 false 且不追加日志 */
    private fun sendStaticCommand(item: CommandItem) {
        val payload = buildStaticPayload(item) ?: return
        val full = payload + item.lineEnding.bytes
        if (connection.send(full)) {
            appendLog(false, full)
        }
    }

    /** 组装静态型命令的负载字节（静态命令 / 组合命令）；非法时提示并返回 null */
    private fun buildStaticPayload(item: CommandItem): ByteArray? = when (item.type) {
        CommandType.STATIC -> when (item.encoding) {
            CommandEncoding.ASCII -> item.content.toByteArray(Charsets.UTF_8)
            CommandEncoding.HEX -> try {
                HexUtils.hexToBytes(item.content)
            } catch (e: IllegalArgumentException) {
                Toast.makeText(
                    this,
                    getString(R.string.cmd_hex_invalid, item.name),
                    Toast.LENGTH_SHORT
                ).show()
                null
            }
        }
        CommandType.SEQUENCE -> buildSequencePayload(item.sequence)
        else -> null
    }

    /** 组装组合命令：encode(prefix) + 片段0 + [separator] + 片段1 + … + encode(suffix) */
    private fun buildSequencePayload(seq: SequenceCommand?): ByteArray? {
        if (seq == null) return null
        val out = java.io.ByteArrayOutputStream()
        try {
            out.write(encodeSegment(seq.prefix, seq.prefixEncoding))
            val sep = seq.separator.toByteArray(Charsets.UTF_8)
            seq.items.forEachIndexed { i, item ->
                if (i > 0) out.write(sep)
                out.write(encodeSegment(item.content, item.encoding))
            }
            out.write(encodeSegment(seq.suffix, seq.suffixEncoding))
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, R.string.hex_invalid, Toast.LENGTH_SHORT).show()
            return null
        }
        return out.toByteArray()
    }

    private fun encodeSegment(content: String, encoding: CommandEncoding): ByteArray =
        if (encoding == CommandEncoding.HEX) {
            HexUtils.hexToBytes(content)
        } else {
            content.toByteArray(Charsets.UTF_8)
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

    /**
     * 滑杆命令控制面板：
     * 拖动滑杆或手动输入数值，实时预览；支持拖动即发、复位默认、循环扫描（自动往返发送）。
     */
    private fun showSliderDialog(item: CommandItem) {
        val cmd = item.slider ?: return
        val db = DialogSliderControlBinding.inflate(layoutInflater)
        val values = IntArray(cmd.sliders.size)
        val rows = mutableListOf<SliderRow>()

        // 拖动即发送：默认跟随全局持续发送设置（本次会话内可单独切换）
        db.swAutoSend.isChecked = settings.autoSendEnabled
        db.swScan.isChecked = false

        fun refreshPreview() {
            db.sliderPreview.text = buildSliderPreview(cmd, values)
        }

        cmd.sliders.forEachIndexed { index, def ->
            values[index] = def.defaultValue.coerceIn(def.min, def.max)
            val rowBinding =
                ItemSliderControlBinding.inflate(layoutInflater, db.sliderContainer, false)
            rowBinding.sliderName.text = def.name
            rowBinding.sliderSeek.min = def.min
            rowBinding.sliderSeek.max = def.max
            val holder = SliderRow(index, def, rowBinding)
            holder.onValueChanged = { v ->
                values[index] = v
                refreshPreview()
                // 持续控制：手动/拖动变化立即发送（扫描模式下由扫描任务负责发送）
                if (db.swAutoSend.isChecked && !db.swScan.isChecked) {
                    sendSliderPayload(cmd, values, item.lineEnding)
                }
            }
            holder.update(values[index])
            rows.add(holder)
            db.sliderContainer.addView(rowBinding.root)
        }
        refreshPreview()

        fun resetValues() {
            cmd.sliders.forEachIndexed { i, def ->
                values[i] = def.defaultValue.coerceIn(def.min, def.max)
            }
            rows.forEach { it.update(values[it.index]) }
            refreshPreview()
        }

        fun stopScan() {
            scanJob?.cancel()
            scanJob = null
        }

        fun applyScanStep(step: Int) {
            rows.forEach { row ->
                val v = (row.def.min + (row.def.max - row.def.min) * step / 100)
                    .coerceIn(row.def.min, row.def.max)
                values[row.index] = v
                row.update(v)
            }
            refreshPreview()
            sendSliderPayload(cmd, values, item.lineEnding)
        }

        fun startScan() {
            stopScan()
            scanJob = lifecycleScope.launch {
                while (isActive) {
                    for (step in 0..100) {
                        if (!isActive) break
                        applyScanStep(step)
                        delay(SCAN_STEP_DELAY_MS)
                    }
                    for (step in 100 downTo 0) {
                        if (!isActive) break
                        applyScanStep(step)
                        delay(SCAN_STEP_DELAY_MS)
                    }
                }
            }
        }

        db.swScan.setOnCheckedChangeListener { _, checked ->
            if (checked) startScan() else stopScan()
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(item.name)
            .setView(db.root)
            .setNeutralButton(R.string.reset_default, null)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.send, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                resetValues()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                stopScan()
                sendSliderPayload(cmd, values, item.lineEnding)
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener { stopScan() }
        dialog.show()
    }

    /** 单个滑杆的控制行：SeekBar 与数值输入框双向同步（带防重入） */
    private inner class SliderRow(
        val index: Int,
        val def: SliderDef,
        val binding: ItemSliderControlBinding
    ) {
        private var updating = false

        /** 值变化回调（用户拖动或输入触发） */
        var onValueChanged: (Int) -> Unit = {}

        init {
            binding.sliderSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (updating) return
                    updating = true
                    onValueChanged(def.min + progress)
                    binding.sliderValueInput.setText((def.min + progress).toString())
                    binding.sliderValueInput.setSelection(binding.sliderValueInput.text?.length ?: 0)
                    updating = false
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })

            binding.sliderValueInput.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    if (updating) return
                    updating = true
                    val raw = s?.toString()?.trim().orEmpty()
                    if (raw.isNotEmpty()) {
                        val input = raw.toIntOrNull()
                        if (input != null) {
                            val clamped = input.coerceIn(def.min, def.max)
                            onValueChanged(clamped)
                            binding.sliderSeek.progress = clamped - def.min
                            if (clamped != input) {
                                binding.sliderValueInput.setText(clamped.toString())
                                binding.sliderValueInput.setSelection(
                                    binding.sliderValueInput.text?.length ?: 0
                                )
                            }
                        }
                    }
                    updating = false
                }

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            })
        }

        /** 程序化更新（初始化/复位/扫描），不触发回调 */
        fun update(v: Int) {
            updating = true
            binding.sliderSeek.progress = v - def.min
            binding.sliderValueInput.setText(v.toString())
            binding.sliderValueInput.setSelection(binding.sliderValueInput.text?.length ?: 0)
            updating = false
        }
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
        private const val SCAN_STEP_DELAY_MS = 40L
    }
}
