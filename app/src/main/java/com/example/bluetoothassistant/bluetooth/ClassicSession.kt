package com.example.bluetoothassistant.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.example.bluetoothassistant.model.ConnectionMode
import com.example.bluetoothassistant.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * 经典蓝牙（SPP/RFCOMM）会话，用于 HC-05 / HC-06 等串口模块。
 * 所有 socket 操作在 IO 线程执行。
 */
class ClassicSession(
    private val device: BluetoothDevice,
    private val onData: (ByteArray) -> Unit,
    private val onState: (ConnectionState) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: BluetoothSocket? = null
    /** 正在连接中（connect() 尚未返回）的 socket，用于中途取消 */
    private var pendingSocket: BluetoothSocket? = null

    fun start() {
        onState(ConnectionState.Connecting(device.name ?: device.address))
        scope.launch {
            var sock: BluetoothSocket? = null
            try {
                sock = connectWithRetry()
                socket = sock
                withContext(Dispatchers.Main) {
                    onState(ConnectionState.Connected(device.name ?: device.address, ConnectionMode.CLASSIC))
                }
                readLoop(sock)
            } catch (e: Exception) {
                if (currentCoroutineContext().isActive) {
                    withContext(Dispatchers.Main) {
                        onState(ConnectionState.Error(friendlyError(e)))
                    }
                }
            } finally {
                runCatching { sock?.close() }
                socket = null
            }
        }
    }

    /**
     * 带重试的连接：HC-06 等模块首次连接偶发失败
     * （"read failed, socket might closed or timeout, read ret: -1"），
     * 失败后间隔 1 秒重试一次可明显提高成功率。
     */
    private suspend fun connectWithRetry(): BluetoothSocket {
        var lastError: IOException? = null
        repeat(MAX_CONNECT_ATTEMPTS) { attempt ->
            var s: BluetoothSocket? = null
            try {
                s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                val sock = s
                pendingSocket = sock
                // 连接看门狗：超过超时时间仍未连上则关闭 socket，中断阻塞的 connect()
                val watchdog = scope.launch {
                    delay(CONNECT_TIMEOUT_MS)
                    if (!sock.isConnected) {
                        runCatching { sock.close() }
                    }
                }
                try {
                    sock.connect()
                } finally {
                    watchdog.cancel()
                    pendingSocket = null
                }
                return sock
            } catch (e: IOException) {
                pendingSocket = null
                runCatching { s?.close() }
                lastError = e
                if (attempt < MAX_CONNECT_ATTEMPTS - 1) {
                    delay(RETRY_DELAY_MS)
                }
            }
        }
        throw lastError ?: IOException("连接失败")
    }

    private suspend fun readLoop(sock: BluetoothSocket) {
        val buffer = ByteArray(1024)
        try {
            while (currentCoroutineContext().isActive) {
                val n = sock.inputStream.read(buffer)
                if (n <= 0) break
                val data = buffer.copyOf(n)
                withContext(Dispatchers.Main) { onData(data) }
            }
            // 对端主动关闭（read 返回 EOF）
            if (currentCoroutineContext().isActive) {
                withContext(Dispatchers.Main) {
                    onState(ConnectionState.Error("连接断开：对端关闭了连接"))
                }
            }
        } catch (e: IOException) {
            if (currentCoroutineContext().isActive) {
                withContext(Dispatchers.Main) {
                    onState(ConnectionState.Error(friendlyError(e)))
                }
            }
        }
    }

    fun send(bytes: ByteArray) {
        scope.launch {
            val s = socket
            if (s == null || !s.isConnected) {
                withContext(Dispatchers.Main) { onState(ConnectionState.Error("设备未连接")) }
                return@launch
            }
            try {
                s.outputStream.write(bytes)
                s.outputStream.flush()
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    onState(ConnectionState.Error("发送失败：${e.message ?: "未知错误"}"))
                }
            }
        }
    }

    fun disconnect() {
        scope.cancel()
        // 连接中：关闭未完成的 socket 以中断阻塞的 connect()；已连接：关闭通信 socket
        runCatching { pendingSocket?.close() }
        runCatching { socket?.close() }
        socket = null
        pendingSocket = null
    }

    /** 把框架的原始报错翻译成可操作的中文提示 */
    private fun friendlyError(e: Exception): String {
        val msg = e.message ?: "未知错误"
        return when {
            msg.contains("read failed", ignoreCase = true) ||
                msg.contains("socket might closed", ignoreCase = true) ->
                "连接失败：模块在握手时断开（read failed）。请检查：① 模块已上电且 LED 处于待连接状态；② 未被其他手机占用；③ 仍失败请先取消配对再重新配对"
            msg.contains("Service discovery failed", ignoreCase = true) ->
                "连接失败：未找到 SPP 串口服务（模块不支持或未就绪）"
            else -> "连接失败：$msg"
        }
    }

    companion object {
        private const val MAX_CONNECT_ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 1_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        /** 标准 SPP UUID */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
