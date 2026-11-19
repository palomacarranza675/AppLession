package com.example.lessionapp.ble

data class EmgSample(
    val timestamp: Long,
    val adcRaw: Int,
    val status: String = "valid"
)

enum class ReceptionStatus {
    WAITING,
    RECEIVING,
    NO_DATA,
    ERROR
}

class EmgDataBuffer(private val onLineReady: (String) -> Unit) {
    private val stringBuilder = StringBuilder()
    private val maxBufferSize = 4096

    @Synchronized
    fun append(bytes: ByteArray) {
        try {
            val text = String(bytes, Charsets.UTF_8)
            stringBuilder.append(text)
            if (stringBuilder.length > maxBufferSize) {
                stringBuilder.setLength(0)
                return
            }

            var newlineIndex = stringBuilder.indexOf('\n')
            while (newlineIndex != -1) {
                var line = stringBuilder.substring(0, newlineIndex)
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length - 1)
                }
                line = line.trim()
                stringBuilder.delete(0, newlineIndex + 1)
                if (line.isNotEmpty()) {
                    onLineReady(line)
                }
                newlineIndex = stringBuilder.indexOf('\n')
            }
        } catch (e: Exception) {
            stringBuilder.setLength(0)
        }
    }

    @Synchronized
    fun clear() {
        stringBuilder.setLength(0)
    }
}

object EmgTextParser {
    // Protocol: t_ms,adc\n (e.g. 12340,2048)
    fun parse(line: String): EmgSample? {
        return try {
            val parts = line.split(",")
            if (parts.size != 2) return null

            val timestamp = parts[0].trim().toLongOrNull() ?: return null
            val adcRaw = parts[1].trim().toIntOrNull() ?: return null

            // Validate adc range (0 to 4095) and timestamp >= 0
            if (timestamp < 0 || adcRaw !in 0..4095) {
                return null
            }

            EmgSample(
                timestamp = timestamp,
                adcRaw = adcRaw,
                status = "valid"
            )
        } catch (e: Exception) {
            null
        }
    }
}
