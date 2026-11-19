package com.example.lessionapp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

class BleManager(private val context: Context) {
    private val bluetoothManager: BluetoothManager? = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var bluetoothGatt: BluetoothGatt? = null
    private var scanCallback: android.bluetooth.le.ScanCallback? = null

    var onStateChanged: ((BleConnectionState, String?) -> Unit)? = null
    var onDeviceFound: ((BluetoothDevice, String, Int) -> Unit)? = null
    var onDataReceived: ((ByteArray) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var scanRunnable: Runnable? = null

    private fun checkBleAvailability(): Boolean {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            onStateChanged?.invoke(BleConnectionState.ERROR, "Este dispositivo no soporta Bluetooth Low Energy (BLE)")
            return false
        }
        return true
    }

    private fun checkLocationServices(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val gpsEnabled = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
            val netEnabled = locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
            if (!gpsEnabled && !netEnabled) {
                onStateChanged?.invoke(BleConnectionState.ERROR, "Los servicios de ubicación deben estar activados para escanear BLE en esta versión de Android")
                return false
            }
        }
        return true
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!checkBleAvailability()) return
        if (!checkLocationServices()) return

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            onStateChanged?.invoke(BleConnectionState.ERROR, "Bluetooth no disponible o desactivado")
            Log.e("BleManager", "Bluetooth is disabled or null")
            return
        }

        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            onStateChanged?.invoke(BleConnectionState.ERROR, "BLE Scanner no disponible")
            Log.e("BleManager", "BluetoothLeScanner is null")
            return
        }

        // Ensure any previous scan is stopped before starting a new one
        stopScan()

        onStateChanged?.invoke(BleConnectionState.SCANNING, "Escaneando dispositivos BLE...")
        Log.d("BleManager", "Starting BLE scan for target: ${BleConstants.TARGET_DEVICE_NAME}")

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCallback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val device = result.device
                val scanRecord = result.scanRecord
                val scanRecordName = scanRecord?.deviceName
                val deviceName = try { device.name } catch (e: SecurityException) { null }
                val rssi = result.rssi
                val serviceUuids = scanRecord?.serviceUuids

                val resolvedName = scanRecordName ?: deviceName ?: "Desconocido"

                Log.d("BleManager", "Discovered device: MAC=${device.address}, name=$resolvedName, scanRecordName=$scanRecordName, deviceName=$deviceName, RSSI=$rssi, UUIDs=$serviceUuids")

                val nameMatches = resolvedName.contains(BleConstants.TARGET_DEVICE_NAME, ignoreCase = true) ||
                        (scanRecordName != null && scanRecordName.contains(BleConstants.TARGET_DEVICE_NAME, ignoreCase = true)) ||
                        (deviceName != null && deviceName.contains(BleConstants.TARGET_DEVICE_NAME, ignoreCase = true))

                val uuidMatches = serviceUuids?.any { it.uuid == BleConstants.SERVICE_UUID } == true

                if (nameMatches || uuidMatches) {
                    Log.i("BleManager", "MATCHED ESP32 FOUND! MAC=${device.address}, name=$resolvedName, UUID match=$uuidMatches")
                    onDeviceFound?.invoke(device, resolvedName, rssi)
                }
            }

            override fun onBatchScanResults(results: MutableList<android.bluetooth.le.ScanResult>) {
                for (result in results) {
                    onScanResult(0, result)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e("BleManager", "Scan failed with error code: $errorCode")
                onStateChanged?.invoke(BleConnectionState.ERROR, "Error en escaneo BLE: código $errorCode")
            }
        }

        try {
            // Scan without filters to receive scan responses correctly across all devices
            scanner.startScan(null, scanSettings, scanCallback)
            scanRunnable = Runnable {
                stopScan()
                Log.w("BleManager", "Scan timeout reached (10s)")
                onStateChanged?.invoke(BleConnectionState.DISCONNECTED, "Escaneo finalizado (tiempo agotado)")
            }
            handler.postDelayed(scanRunnable!!, 10000)
        } catch (e: Exception) {
            Log.e("BleManager", "Exception starting scan", e)
            onStateChanged?.invoke(BleConnectionState.ERROR, "Excepción al iniciar escaneo: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanRunnable?.let { handler.removeCallbacks(it) }
        scanRunnable = null
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanCallback != null && scanner != null && bluetoothAdapter?.isEnabled == true) {
            try {
                scanner.stopScan(scanCallback)
            } catch (e: Exception) {
                // Ignore
            }
        }
        scanCallback = null
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (!checkBleAvailability()) return
        stopScan()

        // Close existing gatt before connecting to a new one
        try {
            bluetoothGatt?.close()
        } catch (e: Exception) {}
        bluetoothGatt = null

        Log.d("BleManager", "Connecting to device: MAC=${device.address}")
        onStateChanged?.invoke(BleConnectionState.CONNECTING, "Conectando con ${try { device.name ?: device.address } catch (e: SecurityException) { device.address }}...")

        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        Log.d("BleManager", "Disconnecting device")
        try {
            bluetoothGatt?.disconnect()
            // State and close() will be handled asynchronously in onConnectionStateChange STATE_DISCONNECTED
        } catch (e: Exception) {
            // Ignore
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (bluetoothGatt != gatt) return
            Log.d("BleManager", "onConnectionStateChange: status=$status, newState=$newState")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    onStateChanged?.invoke(BleConnectionState.CONNECTED, "Conectado GATT exitosamente")
                    onStateChanged?.invoke(BleConnectionState.DISCOVERING_SERVICES, "Descubriendo servicios...")

                    val discoveryStarted = gatt.discoverServices()
                    if (!discoveryStarted) {
                        Log.e("BleManager", "Failed to start service discovery")
                        onStateChanged?.invoke(BleConnectionState.ERROR, "No se pudo iniciar el descubrimiento de servicios")
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i("BleManager", "Device disconnected")
                    onStateChanged?.invoke(BleConnectionState.DISCONNECTED, "Dispositivo desconectado")
                    try {
                        gatt.close()
                    } catch (e: Exception) {}
                    if (bluetoothGatt == gatt) {
                        bluetoothGatt = null
                    }
                }
            } else {
                Log.e("BleManager", "GATT connection error: status=$status")
                onStateChanged?.invoke(BleConnectionState.ERROR, "Error de conexión GATT: status=$status")
                try {
                    gatt.close()
                } catch (e: Exception) {}
                if (bluetoothGatt == gatt) {
                    bluetoothGatt = null
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (bluetoothGatt != gatt) return
            Log.d("BleManager", "onServicesDiscovered: status=$status")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(BleConstants.SERVICE_UUID)
                if (service != null) {
                    Log.i("BleManager", "UART service found: ${BleConstants.SERVICE_UUID}")
                    val txChar = service.getCharacteristic(BleConstants.TX_CHAR_UUID)
                    val rxChar = service.getCharacteristic(BleConstants.RX_CHAR_UUID)

                    // 1. Check TX characteristic and properties (READ & NOTIFY)
                    if (txChar == null) {
                        Log.e("BleManager", "TX characteristic not found")
                        onStateChanged?.invoke(BleConnectionState.ERROR, "Característica TX no encontrada en el servicio UART")
                        return
                    }
                    val txProps = txChar.properties
                    val txHasRead = (txProps and BluetoothGattCharacteristic.PROPERTY_READ) != 0
                    val txHasNotify = (txProps and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    if (!txHasRead || !txHasNotify) {
                        Log.e("BleManager", "TX properties invalid: read=$txHasRead, notify=$txHasNotify")
                        onStateChanged?.invoke(BleConnectionState.ERROR, "La característica TX no tiene las propiedades READ y NOTIFY requeridas")
                        return
                    }

                    // 2. Check RX characteristic and property (WRITE)
                    if (rxChar == null) {
                        Log.e("BleManager", "RX characteristic not found")
                        onStateChanged?.invoke(BleConnectionState.ERROR, "Característica RX no encontrada en el servicio UART")
                        return
                    }
                    val rxProps = rxChar.properties
                    val rxHasWrite = (rxProps and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                            (rxProps and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    if (!rxHasWrite) {
                        Log.e("BleManager", "RX properties invalid: write=$rxHasWrite")
                        onStateChanged?.invoke(BleConnectionState.ERROR, "La característica RX no tiene la propiedad WRITE requerida")
                        return
                    }

                    // 3. Configure notifications
                    onStateChanged?.invoke(BleConnectionState.CONFIGURING_NOTIFICATIONS, "Configurando notificaciones TX...")
                    val notificationSet = gatt.setCharacteristicNotification(txChar, true)
                    if (!notificationSet) {
                        Log.e("BleManager", "Failed to set local characteristic notification")
                        onStateChanged?.invoke(BleConnectionState.ERROR, "Fallo al habilitar notificaciones locales para TX")
                        return
                    }

                    // 4. Write CCCD descriptor (0x2902)
                    val descriptor = txChar.getDescriptor(BleConstants.CCCD_UUID)
                    if (descriptor != null) {
                        val writeInitiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(descriptor)
                        }

                        if (!writeInitiated) {
                            Log.e("BleManager", "Failed to initiate CCCD write")
                            onStateChanged?.invoke(BleConnectionState.ERROR, "No se pudo iniciar la escritura del descriptor CCCD")
                        } else {
                            Log.d("BleManager", "CCCD write initiated successfully")
                        }
                    } else {
                        Log.e("BleManager", "CCCD descriptor not found in TX")
                        onStateChanged?.invoke(BleConnectionState.ERROR, "Descriptor CCCD (0x2902) no encontrado en TX")
                    }
                } else {
                    Log.e("BleManager", "UART service not found: ${BleConstants.SERVICE_UUID}")
                    onStateChanged?.invoke(BleConnectionState.ERROR, "Servicio UART no encontrado (UUID incorrecto o ausente)")
                }
            } else {
                Log.e("BleManager", "Service discovery failed: status=$status")
                onStateChanged?.invoke(BleConnectionState.ERROR, "Error al descubrir servicios: status=$status")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (bluetoothGatt != gatt) return
            Log.d("BleManager", "onDescriptorWrite: status=$status, uuid=${descriptor.uuid}")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (descriptor.uuid == BleConstants.CCCD_UUID && descriptor.characteristic?.uuid == BleConstants.TX_CHAR_UUID) {
                    Log.i("BleManager", "BLE communication prepared successfully!")
                    onStateChanged?.invoke(BleConnectionState.READY, "Comunicación BLE preparada")
                }
            } else {
                Log.e("BleManager", "CCCD write failed with status=$status")
                onStateChanged?.invoke(BleConnectionState.ERROR, "Error al escribir descriptor CCCD: status=$status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (bluetoothGatt != gatt) return
            if (characteristic.uuid == BleConstants.TX_CHAR_UUID) {
                characteristic.value?.let { data ->
                    onDataReceived?.invoke(data)
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (bluetoothGatt != gatt) return
            if (characteristic.uuid == BleConstants.TX_CHAR_UUID) {
                onDataReceived?.invoke(value)
            }
        }
    }
}
