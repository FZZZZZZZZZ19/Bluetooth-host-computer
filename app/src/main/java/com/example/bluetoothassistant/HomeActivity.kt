package com.example.bluetoothassistant

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.example.bluetoothassistant.base.BaseActivity
import com.example.bluetoothassistant.bluetooth.ConnectionManager
import com.example.bluetoothassistant.databinding.ActivityHomeBinding
import com.example.bluetoothassistant.model.ConnectionState
import kotlinx.coroutines.launch

/**
 * 首页（四宫格入口）：
 * 连接状态 / 蓝牙连接（设备选择）/ 命令管理中心 / 设置中心
 */
class HomeActivity : BaseActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val connection: ConnectionManager get() = (application as App).connectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cardStatus.setOnClickListener {
            startActivity(Intent(this, ConnectionStatusActivity::class.java))
        }
        binding.cardBluetooth.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        binding.cardCommands.setOnClickListener {
            startActivity(Intent(this, CommandActivity::class.java))
        }
        binding.cardSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        lifecycleScope.launch {
            connection.state.collect { updateStatusCard(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatusCard(connection.state.value)
    }

    private fun updateStatusCard(s: ConnectionState) {
        val summary = when (s) {
            is ConnectionState.Connected -> getString(R.string.home_connected, s.deviceName)
            is ConnectionState.Connecting -> getString(R.string.home_connecting, s.deviceName)
            is ConnectionState.Error -> s.message
            ConnectionState.Disconnected -> getString(R.string.not_connected)
        }
        binding.statusSummary.text = summary
        val colorRes = when (s) {
            is ConnectionState.Connected -> R.color.state_connected
            is ConnectionState.Connecting -> R.color.state_connecting
            is ConnectionState.Error -> R.color.state_error
            ConnectionState.Disconnected -> R.color.state_disconnected
        }
        ViewCompat.setBackgroundTintList(
            binding.homeStatusDot,
            ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
        )
    }
}
