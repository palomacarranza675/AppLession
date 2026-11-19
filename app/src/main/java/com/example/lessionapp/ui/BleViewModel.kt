package com.example.lessionapp.ui

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.lessionapp.ble.BleConnectionState
import com.example.lessionapp.ble.BleManager
import com.example.lessionapp.ble.EmgDataBuffer
import com.example.lessionapp.ble.EmgSample
import com.example.lessionapp.ble.EmgTextParser
import com.example.lessionapp.ble.ReceptionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class BleDeviceItem(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int
)

enum class Screen {
    MAIN,
    SYSTEM_INFO
}

class BleViewModel(private val appContext: Context) {
    private val bleManager = BleManager(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _currentScreen = MutableStateFlow(Screen.MAIN)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    private val _showBluetoothDialog = MutableStateFlow(false)
    val showBluetoothDialog: StateFlow<Boolean> = _showBluetoothDialog.asStateFlow()

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _statusMessage = MutableStateFlow("Dispositivo desconectado")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BleDeviceItem>>(emptyList())
    val discoveredDevices: StateFlow<List<BleDeviceItem>> = _discoveredDevices.asStateFlow()

    private val _foundDevice = MutableStateFlow<BluetoothDevice?>(null)
    val foundDevice: StateFlow<BluetoothDevice?> = _foundDevice.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private val _showReadyDialog = MutableStateFlow(false)
    val showReadyDialog: StateFlow<Boolean> = _showReadyDialog.asStateFlow()

    private val _latestSample = MutableStateFlow<EmgSample?>(null)
    val latestSample: StateFlow<EmgSample?> = _latestSample.asStateFlow()

    private val _receptionStatus = MutableStateFlow(ReceptionStatus.WAITING)
    val receptionStatus: StateFlow<ReceptionStatus> = _receptionStatus.asStateFlow()

    private val _sessionSamples = MutableStateFlow<List<EmgSample>>(emptyList())
    val sessionSamples: StateFlow<List<EmgSample>> = _sessionSamples.asStateFlow()

    private val _sessionStats = MutableStateFlow(SessionStats())
    val sessionStats: StateFlow<SessionStats> = _sessionStats.asStateFlow()

    private var hasShownReadyDialog = false
    private var watchdogJob: Job? = null
    private var lastSampleTime = 0L

    private val emgBuffer = EmgDataBuffer { line ->
        android.util.Log.d("EmgParser", "Raw line received: $line")
        val sample = EmgTextParser.parse(line)
        if (sample != null) {
            lastSampleTime = System.currentTimeMillis()
            _latestSample.value = sample
            _receptionStatus.value = ReceptionStatus.RECEIVING

            val updatedList = _sessionSamples.value.toMutableList()
            updatedList.add(sample)
            if (updatedList.size > 300) {
                updatedList.removeAt(0)
            }
            _sessionSamples.value = updatedList
            updateStats(updatedList)
        }
    }

    init {
        bleManager.onDataReceived = { bytes ->
            emgBuffer.append(bytes)
        }

        bleManager.onStateChanged = { state, message ->
            scope.launch {
                _connectionState.value = state
                if (message != null) {
                    _statusMessage.value = message
                }
                if (state == BleConnectionState.READY) {
                    if (!hasShownReadyDialog) {
                        hasShownReadyDialog = true
                        _showReadyDialog.value = true
                    }
                    _receptionStatus.value = ReceptionStatus.WAITING
                    lastSampleTime = 0L
                    startWatchdog()
                } else if (state == BleConnectionState.DISCONNECTED || state == BleConnectionState.ERROR) {
                    hasShownReadyDialog = false
                    _showReadyDialog.value = false
                    stopWatchdog()
                    emgBuffer.clear()
                    if (state == BleConnectionState.DISCONNECTED) {
                        _statusMessage.value = "Dispositivo desconectado"
                    }
                }
            }
        }

        bleManager.onDeviceFound = { device, name, rssi ->
            scope.launch {
                val currentList = _discoveredDevices.value.toMutableList()
                val existingIndex = currentList.indexOfFirst { it.address == device.address }
                val newItem = BleDeviceItem(device, name, device.address, rssi)
                if (existingIndex >= 0) {
                    currentList[existingIndex] = newItem
                } else {
                    currentList.add(newItem)
                }
                _discoveredDevices.value = currentList
                _foundDevice.value = device
                _deviceName.value = name
                _connectionState.value = BleConnectionState.DEVICE_FOUND
                _statusMessage.value = "Dispositivos compatibles: ${currentList.size}"
            }
        }
    }

    private fun updateStats(samples: List<EmgSample>) {
        if (samples.isEmpty()) {
            _sessionStats.value = SessionStats()
            return
        }
        _sessionStats.value = StatisticsCalculator.compute(samples.map { it.adcRaw })
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (true) {
                delay(1000)
                if (_connectionState.value == BleConnectionState.READY) {
                    val now = System.currentTimeMillis()
                    if (lastSampleTime == 0L) {
                        _receptionStatus.value = ReceptionStatus.WAITING
                    } else if (now - lastSampleTime > 2000) {
                        _receptionStatus.value = ReceptionStatus.NO_DATA
                    }
                }
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
        lastSampleTime = 0L
    }

    fun navigateTo(screen: Screen) {
        _currentScreen.value = screen
    }

    fun setBluetoothDialogVisible(visible: Boolean) {
        _showBluetoothDialog.value = visible
    }

    fun startScan() {
        hasShownReadyDialog = false
        _showReadyDialog.value = false
        _discoveredDevices.value = emptyList()
        _foundDevice.value = null
        _deviceName.value = null
        bleManager.startScan()
    }

    fun stopScan() {
        bleManager.stopScan()
        _statusMessage.value = "Escaneo detenido"
    }

    fun connectToDevice(device: BluetoothDevice) {
        hasShownReadyDialog = false
        _showReadyDialog.value = false
        bleManager.connect(device)
    }

    fun connectToFoundDevice() {
        _foundDevice.value?.let { device ->
            connectToDevice(device)
        } ?: run {
            _statusMessage.value = "Ningún dispositivo seleccionado"
        }
    }

    fun disconnect() {
        stopWatchdog()
        bleManager.disconnect()
        _foundDevice.value = null
        _deviceName.value = null
        _receptionStatus.value = ReceptionStatus.WAITING
        emgBuffer.clear()
    }

    fun exportCsv(context: Context) {
        val samples = _sessionSamples.value
        if (samples.isEmpty()) return
        try {
            val sb = StringBuilder()
            sb.append("Timestamp (ms),ADC Raw,Status\n")
            for (s in samples) {
                sb.append("${s.timestamp},${s.adcRaw},${s.status}\n")
            }
            val file = File(context.cacheDir, "lession_session_${System.currentTimeMillis()}.csv")
            file.writeText(sb.toString())

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Exportar sesión CSV")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            // Handle export error
        }
    }

    fun setPermissionDeniedStatus(message: String) {
        _connectionState.value = BleConnectionState.ERROR
        _statusMessage.value = message
    }

    fun dismissReadyDialog() {
        _showReadyDialog.value = false
    }

    fun onCleared() {
        disconnect()
    }
}
