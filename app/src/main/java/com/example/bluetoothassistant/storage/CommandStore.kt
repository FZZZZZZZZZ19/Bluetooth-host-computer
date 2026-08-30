package com.example.bluetoothassistant.storage

import android.content.Context
import com.example.bluetoothassistant.model.CommandItem
import org.json.JSONArray

/** 命令库持久化（SharedPreferences + JSON） */
class CommandStore(context: Context) {
    private val prefs = context.getSharedPreferences("command_library", Context.MODE_PRIVATE)

    fun load(): List<CommandItem> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { CommandItem.fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun save(list: List<CommandItem>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    companion object {
        private const val KEY = "commands"
    }
}
