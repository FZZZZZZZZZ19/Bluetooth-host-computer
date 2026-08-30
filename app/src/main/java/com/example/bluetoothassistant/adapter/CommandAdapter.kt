package com.example.bluetoothassistant.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.bluetoothassistant.databinding.ItemCommandBinding
import com.example.bluetoothassistant.model.CommandItem
import com.example.bluetoothassistant.model.CommandType

class CommandAdapter(
    private val onEdit: (CommandItem) -> Unit,
    private val onDelete: (CommandItem) -> Unit
) : RecyclerView.Adapter<CommandAdapter.VH>() {

    private val items = mutableListOf<CommandItem>()

    fun submit(list: List<CommandItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val binding: ItemCommandBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemCommandBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.cmdName.text = item.name
        holder.binding.cmdContent.text = when (item.type) {
            CommandType.STATIC -> item.content
            CommandType.SLIDER -> {
                val n = item.slider?.sliders?.size ?: 0
                val preview = buildSliderPreview(item)
                if (preview.isBlank()) "滑杆 × $n" else "$preview ｜ 滑杆 × $n"
            }
        }
        holder.binding.cmdMeta.text = when (item.type) {
            CommandType.STATIC -> "${item.encoding.name} · 追加 ${item.lineEnding.label}"
            CommandType.SLIDER ->
                "滑杆组合 · ${item.slider?.sliders?.size ?: 0} 个 · 追加 ${item.lineEnding.label}"
        }
        holder.binding.root.setOnClickListener { onEdit(item) }
        holder.binding.btnDelete.setOnClickListener { onDelete(item) }
    }

    private fun buildSliderPreview(item: CommandItem): String {
        val cmd = item.slider ?: return ""
        val sb = StringBuilder(cmd.prefix)
        cmd.sliders.forEachIndexed { i, def ->
            if (i > 0) sb.append(cmd.separator)
            sb.append(def.defaultValue)
        }
        sb.append(cmd.suffix)
        return sb.toString()
    }

    override fun getItemCount(): Int = items.size
}
