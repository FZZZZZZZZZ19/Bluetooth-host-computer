package com.example.bluetoothassistant

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.bluetoothassistant.adapter.DeviceAdapter
import com.example.bluetoothassistant.adapter.DeviceRow
import com.example.bluetoothassistant.base.BaseActivity
import com.example.bluetoothassistant.bluetooth.ConnectionManager
import com.example.bluetoothassistant.databinding.ActivityMainBinding
import com.example.bluetoothassistant.model.ConnectionMode
import com.example.bluetoothassistant.model.ConnectionState
import com.example.bluetoothassistant.util.Permissions
import com.example.bluetoothassistant.util.SettingsStore
import kotlinx.coroutines.launch

/**
 * 蓝牙连接页（由首页"蓝牙连接"卡片进入）：
 * 模式切换（经典蓝牙 / BLE）+ 分组设备列表 + 扫描。
 */
class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private val connection: ConnectionManager get() = (application as App).connectionManager
    private val settings by lazy { SettingsStore(this) }

    private var mode = ConnectionMode.CLASSIC
    private var navigatedToTerminal = false

    private val deviceAdapter = DeviceAdapter { device -> connectTo(device) }
    private val classicDevices = LinkedHashMap<String, BluetoothDevice>()
    private val bleDevices = LinkedHashMap<String, Pair<BluetoothDevice, Int>>()
    private var scanCallback: ScanCallback? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                refreshDevices()
            } else {
                binding.statusText.text = getString(R.string.permission_denied)
            }
        }

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.bluetoothDevice()
                    if (device != null) {
                        classicDevices[device.address] = device
                        renderClassicList()
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    binding.statusText.text = getString(R.string.discovery_finished)
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.bluetoothDevice()
                    if (device != null) {
                        classicDevices[device.address] = device
                        renderClassicList()
                    }
                }
            }
        }
    }

    private fun Intent.bluetoothDevice(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.deviceList.layoutManager = LinearLayoutManager(this)
        binding.deviceList.adapter = deviceAdapter

        binding.modeToggle.check(binding.btnClassic.id)
        binding.modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            mode = if (checkedId == binding.btnBle.id) ConnectionMode.BLE else ConnectionMode.CLASSIC
            stopScanning()
            binding.hintText.setText(
                if (mode == ConnectionMode.BLE) R.string.hint_ble else R.string.hint_classic
            )
            refreshDevices()
        }

        binding.btnScan.setOnClickListener { refreshDevices() }
        binding.btnCancelConnect.setOnClickListener { connection.disconnect() }

        observeConnectionState()
    }

    override fun onResume() {
        super.onResume()
        registerDiscoveryReceiver()
        if (Permissions.allGranted(this)) {
            refreshDevices()
        } else {
            permissionLauncher.launch(Permissions.requiredRuntimePermissions().toTypedArray())
        }
    }

    override fun onPause() {
        super.onPause()
        stopScanning()
        unregisterDiscoveryReceiver()
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_commands -> {
                startActivity(Intent(this, CommandActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun observeConnectionState() {
        lifecycleScope.launch {
            connection.state.collect { s ->
                binding.btnCancelConnect.isVisible = s is ConnectionState.Connecting
                when (s) {
                    is ConnectionState.Connected -> {
                        binding.statusText.text = getString(R.string.connected_hint, s.deviceName)
                        if (settings.autoOpenTerminal && !navigatedToTerminal) {
                            openTerminal(s.deviceName)
                        }
                    }
                    is ConnectionState.Connecting -> {
                        binding.statusText.text = getString(R.string.connecting_hint, s.deviceName)
                    }
                    is ConnectionState.Error -> {
                        binding.statusText.text = s.message
                        Toast.makeText(this@MainActivity, s.message, Toast.LENGTH_SHORT).show()
                    }
                    ConnectionState.Disconnected -> {
                        navigatedToTerminal = false
                        binding.statusText.text = getString(R.string.disconnected_hint)
                    }
                }
            }
        }
    }

    private fun openTerminal(deviceName: String) {
        navigatedToTerminal = true
        startActivity(
            Intent(this, TerminalActivity::class.java)
                .putExtra(TerminalActivity.EXTRA_DEVICE_NAME, deviceName)
        )
    }

    private fun refreshDevices() {
        if (!Permissions.allGranted(this)) return
        val adapter = bluetoothAdapter()
        if (adapter == null) {
            binding.statusText.text = getString(R.string.bt_not_supported)
            return
        }
        if (!adapter.isEnabled) {
            binding.statusText.text = getString(R.string.bt_disabled)
            return
        }
        when (mode) {
            ConnectionMode.CLASSIC -> {
                classicDevices.clear()
                adapter.bondedDevices.forEach { classicDevices[it.address] = it }
                renderClassicList()
                binding.statusText.text = getString(R.string.discovering)
                if (!adapter.startDiscovery()) {
                    binding.statusText.text = getString(R.string.paired_only)
                }
            }
            ConnectionMode.BLE -> startBleScan(adapter)
        }
    }

    private fun renderClassicList() {
        val paired = classicDevices.values.filter { it.bondState == BluetoothDevice.BOND_BONDED }
        val unpaired = classicDevices.values.filter { it.bondState != BluetoothDevice.BOND_BONDED }
        val rows = mutableListOf<DeviceRow>()
        if (paired.isNotEmpty()) {
            rows.add(DeviceRow.Header(getString(R.string.paired_devices)))
            rows.addAll(paired.map { DeviceRow.Device(it, getString(R.string.paired)) })
        }
        if (unpaired.isNotEmpty()) {
            rows.add(DeviceRow.Header(getString(R.string.nearby_devices)))
            rows.addAll(unpaired.map { DeviceRow.Device(it, getString(R.string.unpaired)) })
        }
        deviceAdapter.submit(rows)
    }

    private fun startBleScan(adapter: BluetoothAdapter) {
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            binding.statusText.text = getString(R.string.ble_not_supported)
            return
        }
        stopScanning()
        bleDevices.clear()
        deviceAdapter.submit(emptyList())
        binding.statusText.text = getString(R.string.scanning)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val address = device.address
                // 仅当出现新设备时才刷新列表；RSSI 变化不触发重绘，
                // 避免高频重建列表导致点击卡顿/丢失
                if (bleDevices[address] == null) {
                    bleDevices[address] = device to result.rssi
                    submitBleList()
                }
            }

            override fun onScanFailed(errorCode: Int) {
                binding.statusText.text = getString(R.string.scan_failed, errorCode)
            }
        }
        scanCallback = callback
        scanner.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            callback
        )
    }

    private fun submitBleList() {
        val rows = mutableListOf<DeviceRow>()
        rows.add(DeviceRow.Header(getString(R.string.scan_results)))
        rows.addAll(
            bleDevices.values.map { (d, rssi) -> DeviceRow.Device(d, "RSSI: $rssi dBm") }
        )
        deviceAdapter.submit(rows)
    }

    private fun stopScanning() {
        scanCallback?.let { cb ->
            runCatching { bluetoothAdapter()?.bluetoothLeScanner?.stopScan(cb) }
        }
        scanCallback = null
        runCatching { bluetoothAdapter()?.cancelDiscovery() }
    }

    private fun connectTo(device: BluetoothDevice) {
        // 权限缺失时先请求，避免 connectGatt 抛 SecurityException 崩溃
        if (!Permissions.allGranted(this)) {
            permissionLauncher.launch(Permissions.requiredRuntimePermissions().toTypedArray())
            return
        }
        // 连接前必须停止扫描/搜索，否则系统会中断连接
        stopScanning()
        // 已连接同一设备：直接进入终端
        val current = connection.state.value
        if (current is ConnectionState.Connected && connection.connectedAddress == device.address) {
            openTerminal(current.deviceName)
            return
        }
        if (mode == ConnectionMode.CLASSIC && device.bondState != BluetoothDevice.BOND_BONDED) {
            binding.statusText.text = getString(R.string.bonding)
            if (!device.createBond()) {
                Toast.makeText(this, R.string.bond_failed, Toast.LENGTH_SHORT).show()
            }
            return
        }
        navigatedToTerminal = false
        when (mode) {
            ConnectionMode.CLASSIC -> connection.connectClassic(device)
            ConnectionMode.BLE -> connection.connectBle(device)
        }
    }

    private fun bluetoothAdapter(): BluetoothAdapter? {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return null
        return manager.adapter
    }

    private fun registerDiscoveryReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                this,
                discoveryReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(discoveryReceiver, filter)
        }
    }

    private fun unregisterDiscoveryReceiver() {
        runCatching { unregisterReceiver(discoveryReceiver) }
    }
}
