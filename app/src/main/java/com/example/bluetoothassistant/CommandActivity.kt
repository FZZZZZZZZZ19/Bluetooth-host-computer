package com.example.bluetoothassistant

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.bluetoothassistant.adapter.CommandAdapter
import com.example.bluetoothassistant.base.BaseActivity
import com.example.bluetoothassistant.bluetooth.HexUtils
import com.example.bluetoothassistant.databinding.ActivityCommandsBinding
import com.example.bluetoothassistant.databinding.DialogCommandEditBinding
import com.example.bluetoothassistant.databinding.ItemSequenceDefBinding
import com.example.bluetoothassistant.databinding.ItemSliderDefBinding
import com.example.bluetoothassistant.model.CommandEncoding
import com.example.bluetoothassistant.model.CommandItem
import com.example.bluetoothassistant.model.CommandType
import com.example.bluetoothassistant.model.LineEnding
import com.example.bluetoothassistant.model.SequenceCommand
import com.example.bluetoothassistant.model.SequenceItem
import com.example.bluetoothassistant.model.SliderCommand
import com.example.bluetoothassistant.model.SliderDef
import com.example.bluetoothassistant.model.SliderValueFormat
import com.example.bluetoothassistant.storage.CommandStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 命令管理中心：静态命令（文本/HEX）、滑杆命令（多滑杆组合）、组合命令（多片段数组 + 前后缀），支持导入/导出
 */
class CommandActivity : BaseActivity() {

    private lateinit var binding: ActivityCommandsBinding
    private val store by lazy { CommandStore(this) }
    private val commands = mutableListOf<CommandItem>()
    private var nextId = 1L

    /** 导出命令库到文件 */
    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri ?: return@registerForActivityResult
            runCatching {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(exportJson().toByteArray(Charsets.UTF_8))
                }
                toast(R.string.export_success)
            }.onFailure { toast(R.string.export_failed) }
        }

    /** 从文件导入命令库 */
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            val content = runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (content.isNullOrBlank()) {
                toast(R.string.import_failed)
                return@registerForActivityResult
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.import_commands)
                .setMessage(R.string.import_confirm)
                .setPositiveButton(R.string.import_commands) { _, _ -> doImport(content) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

    private val adapter = CommandAdapter(
        onEdit = { showEditDialog(it) },
        onDelete = { showDeleteConfirm(it) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCommandsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.commandList.layoutManager = LinearLayoutManager(this)
        binding.commandList.adapter = adapter

        binding.fabAdd.setOnClickListener { showEditDialog(null) }
        reload()
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_export -> {
                exportLauncher.launch("bluetooth_commands.json")
                true
            }
            R.id.action_import -> {
                importLauncher.launch(arrayOf("application/json"))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun reload() {
        commands.clear()
        commands.addAll(store.load())
        nextId = (commands.maxOfOrNull { it.id } ?: 0L) + 1
        adapter.submit(commands)
    }

    private fun showEditDialog(item: CommandItem?) {
        val db = DialogCommandEditBinding.inflate(layoutInflater)
        val sliderRows = mutableListOf<SliderDefRow>()
        val sequenceRows = mutableListOf<SequenceRow>()

        // 换行下拉
        val endingAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            LineEnding.entries.map { it.label }
        )
        endingAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        db.lineSpinner.adapter = endingAdapter

        // 编码下拉（文本/HEX），用于组合命令的前缀、后缀与片段
        setupEncodingSpinner(db.seqPrefixEncoding)
        setupEncodingSpinner(db.seqSuffixEncoding)

        // 命令类型切换
        val type = item?.type ?: CommandType.STATIC
        db.typeGroup.check(
            when (type) {
                CommandType.SLIDER -> db.rbSlider.id
                CommandType.SEQUENCE -> db.rbSequence.id
                else -> db.rbStatic.id
            }
        )
        updateTypeSections(db, type)
        db.typeGroup.setOnCheckedChangeListener { _, id ->
            updateTypeSections(
                db,
                when (id) {
                    db.rbSlider.id -> CommandType.SLIDER
                    db.rbSequence.id -> CommandType.SEQUENCE
                    else -> CommandType.STATIC
                }
            )
        }

        // 滑杆数据回填
        item?.slider?.let { slider ->
            db.etSliderPrefix.setText(slider.prefix)
            db.etSliderSuffix.setText(slider.suffix)
            db.etSliderSeparator.setText(slider.separator)
            db.valueFormatGroup.check(
                if (slider.valueFormat == SliderValueFormat.HEX) db.rbValHex.id else db.rbValDec.id
            )
            slider.sliders.forEach { addSliderRow(db, sliderRows, it) }
        }

        // 组合命令数据回填
        item?.sequence?.let { seq ->
            db.etSeqPrefix.setText(seq.prefix)
            db.etSeqSuffix.setText(seq.suffix)
            db.etSeqSeparator.setText(seq.separator)
            db.seqPrefixEncoding.setSelection(
                if (seq.prefixEncoding == CommandEncoding.HEX) 1 else 0
            )
            db.seqSuffixEncoding.setSelection(
                if (seq.suffixEncoding == CommandEncoding.HEX) 1 else 0
            )
            seq.items.forEach { addSequenceRow(db, sequenceRows, it) }
        }

        // 静态字段回填
        item?.let {
            db.etName.setText(it.name)
            db.etContent.setText(it.content)
            db.encodingGroup.check(
                if (it.encoding == CommandEncoding.HEX) db.rbHex.id else db.rbAscii.id
            )
            db.lineSpinner.setSelection(LineEnding.entries.indexOf(it.lineEnding))
        }

        db.btnAddSlider.setOnClickListener { addSliderRow(db, sliderRows, null) }
        db.btnAddSequence.setOnClickListener { addSequenceRow(db, sequenceRows, null) }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (item == null) R.string.add_command else R.string.edit_command)
            .setView(db.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = db.etName.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    toast(R.string.name_required)
                    return@setOnClickListener
                }
                val ending = LineEnding.entries[db.lineSpinner.selectedItemPosition]
                val cmdType = when (db.typeGroup.checkedRadioButtonId) {
                    db.rbSlider.id -> CommandType.SLIDER
                    db.rbSequence.id -> CommandType.SEQUENCE
                    else -> CommandType.STATIC
                }

                when (cmdType) {
                    // ---------- 静态命令 ----------
                    CommandType.STATIC -> {
                        val content = db.etContent.text?.toString()?.trim().orEmpty()
                        if (content.isEmpty()) {
                            toast(R.string.content_required)
                            return@setOnClickListener
                        }
                        val isHex = db.encodingGroup.checkedRadioButtonId == db.rbHex.id
                        if (isHex && !HexUtils.isHexString(content)) {
                            toast(R.string.hex_invalid)
                            return@setOnClickListener
                        }
                        val encoding = if (isHex) CommandEncoding.HEX else CommandEncoding.ASCII
                        if (item == null) {
                            commands.add(CommandItem(nextId++, name, content, encoding, ending))
                        } else {
                            val index = commands.indexOfFirst { it.id == item.id }
                            if (index >= 0) {
                                commands[index] = item.copy(
                                    name = name,
                                    content = content,
                                    encoding = encoding,
                                    lineEnding = ending,
                                    type = CommandType.STATIC,
                                    slider = null,
                                    sequence = null
                                )
                            }
                        }
                    }

                    // ---------- 滑杆命令 ----------
                    CommandType.SLIDER -> {
                        val defs = mutableListOf<SliderDef>()
                        var invalid = false
                        sliderRows.forEach { row ->
                            val def = row.read()
                            if (def == null) invalid = true else defs.add(def)
                        }
                        if (invalid) {
                            toast(R.string.slider_invalid)
                            return@setOnClickListener
                        }
                        if (defs.isEmpty()) {
                            toast(R.string.slider_empty)
                            return@setOnClickListener
                        }
                        val prefix = db.etSliderPrefix.text?.toString().orEmpty()
                        val suffix = db.etSliderSuffix.text?.toString().orEmpty()
                        // 分隔符为自由文本：注意保留空格等空白字符，不做 trim
                        val separator = db.etSliderSeparator.text?.toString().orEmpty()
                        val format = if (db.valueFormatGroup.checkedRadioButtonId == db.rbValHex.id) {
                            SliderValueFormat.HEX
                        } else {
                            SliderValueFormat.DEC
                        }
                        val sliderCmd = SliderCommand(prefix, separator, format, defs, suffix)
                        if (item == null) {
                            commands.add(
                                CommandItem(
                                    id = nextId++,
                                    name = name,
                                    content = "",
                                    encoding = CommandEncoding.ASCII,
                                    lineEnding = ending,
                                    type = CommandType.SLIDER,
                                    slider = sliderCmd
                                )
                            )
                        } else {
                            val index = commands.indexOfFirst { it.id == item.id }
                            if (index >= 0) {
                                commands[index] = item.copy(
                                    name = name,
                                    lineEnding = ending,
                                    type = CommandType.SLIDER,
                                    slider = sliderCmd,
                                    sequence = null
                                )
                            }
                        }
                    }

                    // ---------- 组合命令（数组） ----------
                    CommandType.SEQUENCE -> {
                        val prefix = db.etSeqPrefix.text?.toString().orEmpty()
                        val prefixEncoding = if (db.seqPrefixEncoding.selectedItemPosition == 1) {
                            CommandEncoding.HEX
                        } else {
                            CommandEncoding.ASCII
                        }
                        val suffix = db.etSeqSuffix.text?.toString().orEmpty()
                        val suffixEncoding = if (db.seqSuffixEncoding.selectedItemPosition == 1) {
                            CommandEncoding.HEX
                        } else {
                            CommandEncoding.ASCII
                        }
                        // 分隔符为自由文本，保留空白字符
                        val separator = db.etSeqSeparator.text?.toString().orEmpty()

                        if (prefixEncoding == CommandEncoding.HEX && prefix.isNotEmpty() &&
                            !HexUtils.isHexString(prefix)
                        ) {
                            toast(R.string.sequence_invalid)
                            return@setOnClickListener
                        }
                        if (suffixEncoding == CommandEncoding.HEX && suffix.isNotEmpty() &&
                            !HexUtils.isHexString(suffix)
                        ) {
                            toast(R.string.sequence_invalid)
                            return@setOnClickListener
                        }

                        val seqItems = mutableListOf<SequenceItem>()
                        var invalidItem = false
                        sequenceRows.forEach { row ->
                            val it = row.read()
                            if (it == null) invalidItem = true else seqItems.add(it)
                        }
                        if (invalidItem) {
                            toast(R.string.sequence_invalid)
                            return@setOnClickListener
                        }
                        if (seqItems.isEmpty()) {
                            toast(R.string.sequence_empty)
                            return@setOnClickListener
                        }
                        val seqCmd = SequenceCommand(
                            prefix = prefix,
                            prefixEncoding = prefixEncoding,
                            separator = separator,
                            items = seqItems,
                            suffix = suffix,
                            suffixEncoding = suffixEncoding
                        )
                        if (item == null) {
                            commands.add(
                                CommandItem(
                                    id = nextId++,
                                    name = name,
                                    content = "",
                                    encoding = CommandEncoding.ASCII,
                                    lineEnding = ending,
                                    type = CommandType.SEQUENCE,
                                    sequence = seqCmd
                                )
                            )
                        } else {
                            val index = commands.indexOfFirst { it.id == item.id }
                            if (index >= 0) {
                                commands[index] = item.copy(
                                    name = name,
                                    lineEnding = ending,
                                    type = CommandType.SEQUENCE,
                                    slider = null,
                                    sequence = seqCmd
                                )
                            }
                        }
                    }
                }
                store.save(commands)
                adapter.submit(commands)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun updateTypeSections(db: DialogCommandEditBinding, type: CommandType) {
        db.staticSection.visibility = if (type == CommandType.STATIC) View.VISIBLE else View.GONE
        db.sliderSection.visibility = if (type == CommandType.SLIDER) View.VISIBLE else View.GONE
        db.sequenceSection.visibility = if (type == CommandType.SEQUENCE) View.VISIBLE else View.GONE
    }

    private fun setupEncodingSpinner(spinner: Spinner) {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf(getString(R.string.text_mode), getString(R.string.hex_mode))
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
    }

    private fun addSliderRow(
        db: DialogCommandEditBinding,
        rows: MutableList<SliderDefRow>,
        def: SliderDef?
    ) {
        val rowBinding = ItemSliderDefBinding.inflate(layoutInflater, db.sliderList, false)
        def?.let {
            rowBinding.sliderName.setText(it.name)
            rowBinding.sliderMin.setText(it.min.toString())
            rowBinding.sliderMax.setText(it.max.toString())
            rowBinding.sliderDefault.setText(it.defaultValue.toString())
        }
        rowBinding.btnRemoveSlider.setOnClickListener {
            db.sliderList.removeView(rowBinding.root)
            rows.removeAll { it.binding === rowBinding }
        }
        rows.add(SliderDefRow(rowBinding))
        db.sliderList.addView(rowBinding.root)
    }

    private fun addSequenceRow(
        db: DialogCommandEditBinding,
        rows: MutableList<SequenceRow>,
        item: SequenceItem?
    ) {
        val rowBinding = ItemSequenceDefBinding.inflate(layoutInflater, db.sequenceList, false)
        setupEncodingSpinner(rowBinding.seqEncoding)
        item?.let {
            rowBinding.seqContent.setText(it.content)
            rowBinding.seqEncoding.setSelection(if (it.encoding == CommandEncoding.HEX) 1 else 0)
        }
        rowBinding.btnRemoveSequence.setOnClickListener {
            db.sequenceList.removeView(rowBinding.root)
            rows.removeAll { it.binding === rowBinding }
        }
        rows.add(SequenceRow(rowBinding))
        db.sequenceList.addView(rowBinding.root)
    }

    /** 一行滑杆定义的读写 */
    private class SliderDefRow(val binding: ItemSliderDefBinding) {
        fun read(): SliderDef? {
            val name = binding.sliderName.text?.toString()?.trim().orEmpty().ifEmpty { "滑杆" }
            val min = binding.sliderMin.text?.toString()?.toIntOrNull() ?: return null
            val max = binding.sliderMax.text?.toString()?.toIntOrNull() ?: return null
            if (min > max) return null
            val def = binding.sliderDefault.text?.toString()?.toIntOrNull() ?: min
            return SliderDef(name, min, max, def.coerceIn(min, max))
        }
    }

    /** 一行组合片段定义的读写 */
    private class SequenceRow(val binding: ItemSequenceDefBinding) {
        fun read(): SequenceItem? {
            val content = binding.seqContent.text?.toString()?.trim().orEmpty()
            if (content.isEmpty()) return null
            val encoding = if (binding.seqEncoding.selectedItemPosition == 1) {
                CommandEncoding.HEX
            } else {
                CommandEncoding.ASCII
            }
            if (encoding == CommandEncoding.HEX && !HexUtils.isHexString(content)) return null
            return SequenceItem(content, encoding)
        }
    }

    private fun showDeleteConfirm(item: CommandItem) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_command)
            .setMessage(getString(R.string.delete_command_msg, item.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                commands.removeAll { it.id == item.id }
                store.save(commands)
                adapter.submit(commands)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 序列化命令库为 JSON 字符串（含导入导出标识） */
    private fun exportJson(): String {
        val arr = JSONArray()
        commands.forEach { arr.put(it.toJson()) }
        return JSONObject().apply {
            put("app", "bluetooth_serial_assistant")
            put("version", 1)
            put("commands", arr)
        }.toString(2)
    }

    /** 解析并覆盖导入命令库（兼容带包裹对象或裸数组两种格式） */
    private fun doImport(content: String) {
        val list = runCatching {
            val obj = JSONObject(content)
            val arr = obj.optJSONArray("commands") ?: JSONArray(content)
            (0 until arr.length()).map { CommandItem.fromJson(arr.getJSONObject(it)) }
        }.getOrNull()
        if (list == null) {
            toast(R.string.import_failed)
            return
        }
        commands.clear()
        commands.addAll(list)
        nextId = (commands.maxOfOrNull { it.id } ?: 0L) + 1
        store.save(commands)
        adapter.submit(commands)
        toast(R.string.import_success)
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }
}
