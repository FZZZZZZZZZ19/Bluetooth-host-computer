package com.example.bluetoothassistant.bluetooth

object HexUtils {
    private val HEX = "0123456789ABCDEF".toCharArray()

    fun bytesToHex(bytes: ByteArray, withSpace: Boolean = true): String {
        val sb = StringBuilder(bytes.size * (if (withSpace) 3 else 2))
        for ((index, b) in bytes.withIndex()) {
            if (withSpace && index > 0) sb.append(' ')
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /** 解析 "AA 55 01" / "AA5501" / "0xAA 0x55" 形式的十六进制字符串 */
    fun hexToBytes(hex: String): ByteArray {
        val cleaned = hex
            .replace(" ", "")
            .replace("\t", "")
            .replace("\n", "")
            .replace("\r", "")
            .replace("0x", "")
            .replace("0X", "")
        require(cleaned.isNotEmpty()) { "十六进制内容为空" }
        require(cleaned.length % 2 == 0) { "十六进制长度必须为偶数（如 AA 55 01）" }
        require(cleaned.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            "包含非十六进制字符"
        }
        return ByteArray(cleaned.length / 2) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun isHexString(s: String): Boolean {
        val cleaned = s.replace(" ", "").replace("\t", "").replace("\n", "").replace("\r", "")
        return cleaned.isNotEmpty() &&
            cleaned.length % 2 == 0 &&
            cleaned.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
    }
}
