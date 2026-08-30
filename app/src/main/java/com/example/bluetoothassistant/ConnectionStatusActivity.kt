package com.example.bluetoothassistant

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.bluetoothassistant.base.BaseActivity
import com.example.bluetoothassistant.bluetooth.ConnectionManager
import com.example.bluetoothassistant.databinding.ActivityConnectionStatusBinding
import com.example.bluetoothassistant.model.ConnectionState
import kotlinx.coroutines.launch

/**
 * 连接状态页：大号状态显示 + 进入终端 / 断开连接 / 选择设备
 */
class ConnectionStatusActivity : BaseActivity() {

    private lateinit var binding: ActivityConnectionStatusBinding
    private val connection: ConnectionManager get() = (application as App).connectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectionStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.btnOpenTerminal.setOnClickListener {
            val s = connection.state.value
            if (s is ConnectionState.Connected) {
                startActivity(
                    Intent(this, TerminalActivity::class.java)
                        .putExtra(TerminalActivity.EXTRA_DEVICE_NAME, s.deviceName)
                )
            }
        }
        binding.btnDisconnect.setOnClickListener { connection.disconnect() }
        binding.btnCancelConnect.setOnClickListener { connection.disconnect() }
        binding.btnGoSelect.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        lifecycleScope.launch {
            connection.state.collect { render(it) }
        }
        render(connection.state.value)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun render(s: ConnectionState) {
        val (name, detail, colorRes) = when (s) {
            is ConnectionState.Connected -> Triple(
                s.deviceName,
                "${s.mode.label} · ${getString(R.string.connected)}",
                R.color.state_connected
            )
            is ConnectionState.Connecting -> Triple(
                s.deviceName,
                getString(R.string.status_connecting, s.deviceName),
                R.color.state_connecting
            )
            is ConnectionState.Error -> Triple(
                binding.deviceName.text.toString(),
                s.message,
                R.color.state_error
            )
            ConnectionState.Disconnected -> Triple(
                getString(R.string.not_connected),
                getString(R.string.no_connection_detail),
                R.color.state_disconnected
            )
        }
        binding.deviceName.text = name
        binding.statusDetail.text = detail
        binding.deviceAddress.text = connection.connectedAddress ?: "-"
        ViewCompat.setBackgroundTintList(
            binding.statusDot,
            ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
        )
        val connected = s is ConnectionState.Connected
        binding.btnOpenTerminal.isVisible = connected
        binding.btnDisconnect.isVisible = connected || s is ConnectionState.Error
        binding.btnCancelConnect.isVisible = s is ConnectionState.Connecting
    }
}
