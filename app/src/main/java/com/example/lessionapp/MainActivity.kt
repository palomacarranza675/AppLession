package com.example.lessionapp

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.lessionapp.ble.BleConnectionState
import com.example.lessionapp.ble.EmgSample
import com.example.lessionapp.ble.ReceptionStatus
import com.example.lessionapp.ui.BleViewModel
import com.example.lessionapp.ui.Screen
import com.example.lessionapp.ui.SessionStats
import com.example.lessionapp.ui.theme.LessionAppTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LessionAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp() {
    val context = LocalContext.current
    val viewModel = remember { BleViewModel(context.applicationContext) }

    DisposableEffect(viewModel) {
        onDispose {
            viewModel.onCleared()
        }
    }

    val currentScreen by viewModel.currentScreen.collectAsState()
    val showBluetoothDialog by viewModel.showBluetoothDialog.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val foundDevice by viewModel.foundDevice.collectAsState()
    val deviceName by viewModel.deviceName.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val showReadyDialog by viewModel.showReadyDialog.collectAsState()
    val latestSample by viewModel.latestSample.collectAsState()
    val receptionStatus by viewModel.receptionStatus.collectAsState()
    val sessionSamples by viewModel.sessionSamples.collectAsState()
    val sessionStats by viewModel.sessionStats.collectAsState()

    // Brand Colors
    val lViolet = Color(0xFF4025C6)
    val lYellow = Color(0xFFE8FF2F)
    val lWhite = Color(0xFFFFFFFF)

    val isConnected = connectionState == BleConnectionState.READY

    // Ready Dialog confirmation
    if (showReadyDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissReadyDialog() },
            title = {
                Text(
                    text = "¡Dispositivo conectado!",
                    color = lViolet,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            },
            text = {
                Text(
                    text = "ESP32 LESSION se conectó correctamente y está listo para comunicarse",
                    color = Color.DarkGray,
                    fontSize = 15.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.dismissReadyDialog() },
                    colors = ButtonDefaults.buttonColors(containerColor = lViolet)
                ) {
                    Text(
                        text = "Aceptar",
                        color = lYellow,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Bluetooth Management Dialog (Popup)
    if (showBluetoothDialog) {
        BluetoothPopupDialog(
            connectionState = connectionState,
            statusMessage = statusMessage,
            foundDevice = foundDevice,
            deviceName = deviceName,
            discoveredDevices = discoveredDevices,
            onDismiss = { viewModel.setBluetoothDialogVisible(false) },
            viewModel = viewModel,
            context = context
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "LESSION",
                            color = lYellow,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }
                },
                navigationIcon = {
                    if (currentScreen == Screen.SYSTEM_INFO) {
                        IconButton(onClick = { viewModel.navigateTo(Screen.MAIN) }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Volver",
                                tint = lWhite
                            )
                        }
                    }
                },
                actions = {
                    if (currentScreen == Screen.MAIN) {
                        // Bluetooth icon button in header (top right)
                        IconButton(onClick = { viewModel.setBluetoothDialogVisible(true) }) {
                            Text(
                                text = "BT",
                                color = if (isConnected) lYellow else lWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        // Info icon
                        IconButton(onClick = { viewModel.navigateTo(Screen.SYSTEM_INFO) }) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Información del Sistema",
                                tint = lWhite
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF2d1a94))
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(lViolet)
                .padding(innerPadding)
        ) {
            when (currentScreen) {
                Screen.MAIN -> UnifiedMainScreen(
                    connectionState = connectionState,
                    receptionStatus = receptionStatus,
                    latestSample = latestSample,
                    sessionSamples = sessionSamples,
                    sessionStats = sessionStats,
                    onExportCsv = { viewModel.exportCsv(context) }
                )
                Screen.SYSTEM_INFO -> SystemScreen()
            }
        }
    }
}

@Composable
fun UnifiedMainScreen(
    connectionState: BleConnectionState,
    receptionStatus: ReceptionStatus,
    latestSample: EmgSample?,
    sessionSamples: List<EmgSample>,
    sessionStats: SessionStats,
    onExportCsv: () -> Unit
) {
    val lYellow = Color(0xFFE8FF2F)
    val lWhite = Color(0xFFFFFFFF)
    val isReady = connectionState == BleConnectionState.READY

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Current EMG Value Card (Central Focus)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2d1a94))
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "VALOR EMG ACTUAL (ADC)",
                    color = lYellow,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (!isReady) {
                    Text(
                        text = "Esperando conexión con el dispositivo",
                        color = Color.LightGray,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                } else {
                    val valStr = latestSample?.let {
                        String.format(Locale.getDefault(), "%d", it.adcRaw)
                    } ?: "---"

                    Text(
                        text = valStr,
                        color = lWhite,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Recepción: " + when (receptionStatus) {
                            ReceptionStatus.WAITING -> "Esperando datos..."
                            ReceptionStatus.RECEIVING -> "Recibiendo datos (~50 Hz)"
                            ReceptionStatus.NO_DATA -> "Sin datos recientes"
                            ReceptionStatus.ERROR -> "Error de recepción"
                        },
                        color = if (receptionStatus == ReceptionStatus.RECEIVING) lYellow else Color.LightGray,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Evolution Graph Card (Central Focus)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2d1a94))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "GRÁFICO DE EVOLUCIÓN (ADC)",
                    color = lYellow,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))
                EmgLineChart(samples = sessionSamples)
            }
        }

        // Statistics Card (Subetapa 2.2 & 2.3 unified)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2d1a94))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "ESTADÍSTICAS AVANZADAS DE LA SESIÓN",
                    color = lYellow,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))
                if (sessionStats.sampleCount == 0) {
                    Text(
                        text = "No hay mediciones disponibles",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )
                } else {
                    val modeStr = if (sessionStats.mode.isEmpty()) "Sin moda" else sessionStats.mode.joinToString(", ")
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            StatItem(label = "Muestras", value = "${sessionStats.sampleCount}")
                            StatItem(label = "Media", value = String.format(Locale.getDefault(), "%.1f", sessionStats.mean))
                            StatItem(label = "Mediana", value = String.format(Locale.getDefault(), "%.1f", sessionStats.median))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            StatItem(label = "Moda", value = modeStr)
                            StatItem(label = "Mínimo", value = "${sessionStats.min}")
                            StatItem(label = "Máximo", value = "${sessionStats.max}")
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            StatItem(label = "Rango", value = "${sessionStats.range}")
                            StatItem(label = "Varianza", value = String.format(Locale.getDefault(), "%.1f", sessionStats.variance))
                            StatItem(label = "Desv. Est.", value = String.format(Locale.getDefault(), "%.1f", sessionStats.standardDeviation))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            StatItem(label = "Coef. Var.", value = String.format(Locale.getDefault(), "%.1f%%", sessionStats.coefficientOfVariation))
                            StatItem(label = "Q1 / Q3", value = String.format(Locale.getDefault(), "%.1f / %.1f", sessionStats.q1, sessionStats.q3))
                            StatItem(label = "P10 / P90", value = String.format(Locale.getDefault(), "%.1f / %.1f", sessionStats.p10, sessionStats.p90))
                        }
                    }
                }
            }
        }

        // CSV Export Button
        Button(
            onClick = onExportCsv,
            enabled = sessionStats.sampleCount > 0,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = lYellow)
        ) {
            Text(
                text = "Exportar Sesión en CSV",
                color = Color(0xFF4025C6),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = Color.LightGray, fontSize = 11.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
fun EmgLineChart(samples: List<EmgSample>, modifier: Modifier = Modifier) {
    val lYellow = Color(0xFFE8FF2F)
    val gridColor = Color(0xFF4a30db)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(Color(0xFF1e0f6e), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        if (samples.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No hay mediciones disponibles",
                    color = Color.LightGray,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height

                val lines = 4
                for (i in 0..lines) {
                    val y = height * i / lines
                    drawLine(
                        color = gridColor,
                        start = androidx.compose.ui.geometry.Offset(0f, y),
                        end = androidx.compose.ui.geometry.Offset(width, y),
                        strokeWidth = 1f
                    )
                }

                val maxVal = maxOf(samples.maxOf { it.adcRaw }, 4095).toDouble()
                val minVal = 0.0
                val range = maxVal - minVal

                val points = samples.mapIndexed { index, sample ->
                    val x = if (samples.size == 1) width / 2f else width * index / (samples.size - 1)
                    val y = height - ((sample.adcRaw - minVal) / range * height).toFloat()
                    androidx.compose.ui.geometry.Offset(x, y.coerceIn(0f, height))
                }

                for (i in 0 until points.size - 1) {
                    drawLine(
                        color = lYellow,
                        start = points[i],
                        end = points[i + 1],
                        strokeWidth = 3f
                    )
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun BluetoothPopupDialog(
    connectionState: BleConnectionState,
    statusMessage: String,
    foundDevice: BluetoothDevice?,
    deviceName: String?,
    discoveredDevices: List<com.example.lessionapp.ui.BleDeviceItem>,
    onDismiss: () -> Unit,
    viewModel: BleViewModel,
    context: android.content.Context
) {
    val lYellow = Color(0xFFE8FF2F)
    val lWhite = Color(0xFFFFFFFF)
    val lViolet = Color(0xFF4025C6)

    val isScanning = connectionState == BleConnectionState.SCANNING
    val isConnectedOrReady = connectionState == BleConnectionState.READY ||
            connectionState == BleConnectionState.CONNECTED ||
            connectionState == BleConnectionState.DISCOVERING_SERVICES ||
            connectionState == BleConnectionState.CONFIGURING_NOTIFICATIONS ||
            connectionState == BleConnectionState.CONNECTING

    // Permission launcher
    val permissionsToRequest = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            viewModel.startScan()
        } else {
            viewModel.setPermissionDeniedStatus("Permisos necesarios denegados")
        }
    }

    fun checkAndStartScan() {
        val missing = permissionsToRequest.any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            permissionLauncher.launch(permissionsToRequest)
        } else {
            viewModel.startScan()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Gestión Bluetooth",
                color = lViolet,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = when (connectionState) {
                        BleConnectionState.READY -> "Estado: Conectado y listo"
                        BleConnectionState.CONNECTING -> "Estado: Conectando..."
                        BleConnectionState.CONNECTED,
                        BleConnectionState.DISCOVERING_SERVICES,
                        BleConnectionState.CONFIGURING_NOTIFICATIONS -> "Estado: Configurando comunicación"
                        BleConnectionState.SCANNING -> "Estado: Escaneando..."
                        else -> "Estado: Desconectado"
                    },
                    color = Color.DarkGray,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
                Text(
                    text = statusMessage,
                    color = Color.Gray,
                    fontSize = 11.sp
                )

                val displayName = deviceName ?: try { foundDevice?.name } catch (_: SecurityException) { null } ?: "Ninguno seleccionado"
                Text(
                    text = "Dispositivo: $displayName",
                    color = Color.DarkGray,
                    fontSize = 12.sp
                )

                // Discovered Devices List
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2d1a94))
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "DISPOSITIVOS DISPONIBLES",
                            color = lYellow,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        if (discoveredDevices.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isScanning) "Buscando 'ESP32 LESSION'..." else "Pulse 'Buscar' para iniciar",
                                    color = Color.LightGray,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(discoveredDevices) { item ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF3b22b8))
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = item.name,
                                                    color = lWhite,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp
                                                )
                                                Text(
                                                    text = "MAC: ${item.address} | RSSI: ${item.rssi} dBm",
                                                    color = Color.LightGray,
                                                    fontSize = 10.sp
                                                )
                                            }
                                            Button(
                                                onClick = { viewModel.connectToDevice(item.device) },
                                                enabled = !isConnectedOrReady && !isScanning,
                                                colors = ButtonDefaults.buttonColors(containerColor = lYellow),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "Conectar",
                                                    color = lViolet,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Action buttons inside dialog
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (isScanning) viewModel.stopScan() else checkAndStartScan()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = lYellow),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        Text(
                            text = if (isScanning) "Detener" else "Buscar",
                            color = lViolet,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    Button(
                        onClick = { viewModel.disconnect() },
                        enabled = isConnectedOrReady,
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFc62525)),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        Text(
                            text = "Desconectar",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = lViolet)
            ) {
                Text(
                    text = "Cerrar",
                    color = lYellow,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        },
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
fun SystemScreen() {
    val lYellow = Color(0xFFE8FF2F)
    val lWhite = Color(0xFFFFFFFF)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Información del Sistema",
            color = lYellow,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2d1a94))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text = "Lession", color = lYellow, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    text = "Aplicación Android desarrollada para el monitoreo de actividad muscular mediante señales EMG.",
                    color = lWhite,
                    fontSize = 13.sp
                )
                HorizontalDivider(color = Color(0xFF4025C6))
                Text(text = "Sensor EMG MyoWare 3.0", color = lYellow, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    text = "Mide la actividad eléctrica de los músculos (electromiografía) y transmite la envolvente rectificada y suavizada (emgEnvelope) en cuentas ADC (0 - 4095).",
                    color = lWhite,
                    fontSize = 13.sp
                )
                HorizontalDivider(color = Color(0xFF4025C6))
                Text(text = "Microcontrolador ESP32-C3", color = lYellow, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    text = "Actúa como servidor BLE (peripheral), transmitiendo muestras en formato de texto plano (t_ms,adc) a través del servicio UART de Nordic.",
                    color = lWhite,
                    fontSize = 13.sp
                )
                HorizontalDivider(color = Color(0xFF4025C6))
                Text(text = "Comunicación BLE", color = lYellow, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    text = "Utiliza Bluetooth Low Energy como cliente central con UUIDs estándar de servicio UART (6E400001-...) y características TX/RX.",
                    color = lWhite,
                    fontSize = 13.sp
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}
