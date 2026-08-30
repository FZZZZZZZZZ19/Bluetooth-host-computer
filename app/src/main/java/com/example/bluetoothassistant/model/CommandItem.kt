package com.example.bluetoothassistant.model

import org.json.JSONArray
import org.json.JSONObject

/** 命令内容编码格式（静态命令） */
enum class CommandEncoding {
    ASCII,
    HEX
}

/** 命令类型 */
enum class CommandType {
    /** 静态命令：固定文本或 HEX */
    STATIC,

    /** 滑杆命令：多个数字滑杆组合（如机械臂多关节角度） */
    SLIDER
}

/** 滑杆数值格式 */
enum class SliderValueFormat {
    /** 十进制，如 90 */
    DEC,

    /** 十六进制两位，如 5A */
    HEX
}

/** 发送时追加的换行 */
enum class LineEnding(val label: String, val bytes: ByteArray) {
    NONE("无", byteArrayOf()),
    CR("\\r", byteArrayOf(0x0D)),
    LF("\\n", byteArrayOf(0x0A)),
    CRLF("\\r\\n", byteArrayOf(0x0D, 0x0A))
}

/** 单个滑杆定义 */
data class SliderDef(
    val name: String,
    val min: Int,
    val max: Int,
    val defaultValue: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("min", min)
        put("max", max)
        put("defaultValue", defaultValue)
    }

    companion object {
        fun fromJson(o: JSONObject): SliderDef {
            val min = o.optInt("min", 0)
            val max = o.optInt("max", 180)
            return SliderDef(
                name = o.optString("name", "滑杆"),
                min = min,
                max = max,
                defaultValue = o.optInt("defaultValue", (min + max) / 2)
            )
        }
    }
}

/**
 * 滑杆组合命令：
 * 发送格式 = prefix + 值0 + separator + 值1 + separator + … + suffix
 * 例如 prefix="MOVE "、separator=","、滑杆[0..180]×2、suffix="" → "MOVE 90,45"
 */
data class SliderCommand(
    val prefix: String,
    val separator: String,
    val valueFormat: SliderValueFormat,
    val sliders: List<SliderDef>,
    val suffix: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("prefix", prefix)
        put("separator", separator)
        put("valueFormat", valueFormat.name)
        put("suffix", suffix)
        val arr = JSONArray()
        sliders.forEach { arr.put(it.toJson()) }
        put("sliders", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): SliderCommand {
            val arr = o.optJSONArray("sliders") ?: JSONArray()
            val sliders = (0 until arr.length()).map { SliderDef.fromJson(arr.getJSONObject(it)) }
            return SliderCommand(
                prefix = o.optString("prefix", ""),
                separator = o.optString("separator", ","),
                valueFormat = runCatching {
                    SliderValueFormat.valueOf(o.optString("valueFormat", "DEC"))
                }.getOrDefault(SliderValueFormat.DEC),
                sliders = sliders,
                suffix = o.optString("suffix", "")
            )
        }
    }
}

/** 用户自定义命令 */
data class CommandItem(
    val id: Long,
    val name: String,
    val content: String,
    val encoding: CommandEncoding,
    val lineEnding: LineEnding,
    val type: CommandType = CommandType.STATIC,
    val slider: SliderCommand? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("content", content)
        put("encoding", encoding.name)
        put("lineEnding", lineEnding.name)
        put("type", type.name)
        slider?.let { put("slider", it.toJson()) }
    }

    companion object {
        fun fromJson(json: JSONObject): CommandItem {
            val type = runCatching { CommandType.valueOf(json.optString("type", "STATIC")) }
                .getOrDefault(CommandType.STATIC)
            return CommandItem(
                id = json.optLong("id", System.currentTimeMillis()),
                name = json.optString("name", ""),
                content = json.optString("content", ""),
                encoding = runCatching { CommandEncoding.valueOf(json.optString("encoding", "ASCII")) }
                    .getOrDefault(CommandEncoding.ASCII),
                lineEnding = runCatching { LineEnding.valueOf(json.optString("lineEnding", "NONE")) }
                    .getOrDefault(LineEnding.NONE),
                type = type,
                slider = json.optJSONObject("slider")?.let { SliderCommand.fromJson(it) }
            )
        }
    }
}
