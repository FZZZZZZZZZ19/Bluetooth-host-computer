package com.example.bluetoothassistant.adapter

import android.bluetooth.BluetoothDevice
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.bluetoothassistant.databinding.ItemDeviceBinding
import com.example.bluetoothassistant.databinding.ItemHeaderBinding

/** 设备列表行：分组标题 或 设备 */
sealed interface DeviceRow {
    data class Header(val title: String) : DeviceRow
    data class Device(val device: BluetoothDevice, val info: String) : DeviceRow
}

class DeviceAdapter(
    private val onClick: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = mutableListOf<DeviceRow>()

    fun submit(list: List<DeviceRow>) {
        rows.clear()
        rows.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is DeviceRow.Header) TYPE_HEADER else TYPE_DEVICE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_HEADER) {
            HeaderVH(ItemHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        } else {
            DeviceVH(ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is DeviceRow.Header -> (holder as HeaderVH).binding.headerTitle.text = row.title
            is DeviceRow.Device -> (holder as DeviceVH).bind(row)
        }
    }

    override fun getItemCount(): Int = rows.size

    inner class HeaderVH(val binding: ItemHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    inner class DeviceVH(val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: DeviceRow.Device) {
            binding.deviceName.text = row.device.name ?: row.device.address
            binding.deviceInfo.text = "${row.device.address} · ${row.info}"
            binding.root.setOnClickListener { onClick(row.device) }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_DEVICE = 1
    }
}
