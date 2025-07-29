package com.example.garageopenerui

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.garageopenerui.ui.theme.GarageOpenerUITheme
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var bleService: BleService

    // Required permissions
    private val requiredPermissions = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            // Android 12+ only needs Bluetooth permissions, not location
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
            // Android 10-11 needs these permissions
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        else -> {
            // Android 9 and below
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    // Permission request launcher
    private val requestMultiplePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allPermissionsGranted = permissions.entries.all { it.value }
        if (allPermissionsGranted) {
            // All permissions granted, initialize Bluetooth
            initializeBluetooth()
        } else {
            Toast.makeText(
                this,
                "Bluetooth permissions are required for this app",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize BLE service
        bleService = BleService(this)

        // Check permissions
        checkAndRequestPermissions()

        enableEdgeToEdge()
        setContent {
            GarageOpenerUITheme {
                GarageDoorUI(bleService)
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val pendingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (pendingPermissions.isNotEmpty()) {
            requestMultiplePermissions.launch(pendingPermissions)
        } else {
            // All permissions already granted
            initializeBluetooth()
        }
    }

    private fun initializeBluetooth() {
        // Check if Bluetooth is enabled
        if (!bleService.isBluetoothEnabled()) {
            // Request to enable Bluetooth
            val enableBtIntent = Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                // Bluetooth was enabled
                Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show()
            } else {
                // User declined to enable Bluetooth
                Toast.makeText(
                    this,
                    "Bluetooth is required for this app to function",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleService.stopScanning()
        bleService.disconnect()
        bleService.close()
    }

    companion object {
        private const val REQUEST_ENABLE_BT = 1
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GarageDoorUI(bleService: BleService) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // State for the UI
    var isScanning by rememberSaveable { mutableStateOf(false) }
    var showScanResults by rememberSaveable { mutableStateOf(false) }

    // Collect states from BleService's flows
    val deviceList by bleService.deviceList.collectAsState()
    val pairedDeviceList by bleService.pairedDeviceList.collectAsState()
    val connectionState by bleService.connectionState.collectAsState()
    val operationState by bleService.operationState.collectAsState()

    // UI state based on connection
    val isConnected = connectionState == BleService.ConnectionState.CONNECTED
    val isConnecting = connectionState == BleService.ConnectionState.CONNECTING
    val buttonColor = when {
        isConnected -> Color.Green
        isConnecting -> Color.Yellow
        else -> Color.Red
    }

    val buttonText = when {
        isConnected -> "Connected"
        isConnecting -> "Connecting..."
        else -> "Disconnected"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Garage Door Controller") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Connection status
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .background(buttonColor, shape = MaterialTheme.shapes.small)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = buttonText,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            // If connected, show the garage door control button
            if (isConnected) {
                Spacer(modifier = Modifier.height(16.dp))

                // Garage door button
                Button(
                    onClick = {
                        coroutineScope.launch {
                            bleService.triggerGarageDoor { success ->
                                if (!success) {
                                    Toast.makeText(
                                        context,
                                        "Failed to trigger garage door",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    enabled = operationState != BleService.OperationState.SENDING
                ) {
                    Text(
                        when (operationState) {
                            BleService.OperationState.SENDING -> "Sending..."
                            BleService.OperationState.SUCCESS -> "Success!"
                            BleService.OperationState.FAILED -> "Failed!"
                            else -> "Open/Close Garage Door"
                        },
                        fontSize = 24.sp
                    )
                }

                // Disconnect button
                TextButton(
                    onClick = { bleService.disconnect() },
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text("Disconnect")
                }
            } else {
                // When disconnected, show scan button and device lists

                // Tabs for switching between scan and paired devices
                var selectedTabIndex by remember { mutableStateOf(0) }

                TabRow(selectedTabIndex = selectedTabIndex) {
                    Tab(
                        selected = selectedTabIndex == 0,
                        onClick = { selectedTabIndex = 0 },
                        text = { Text("Paired Devices") },
                        icon = { Icon(Icons.Default.Check, contentDescription = "Paired devices") }
                    )
                    Tab(
                        selected = selectedTabIndex == 1,
                        onClick = { selectedTabIndex = 1 },
                        text = { Text("Scan") },
                        icon = { Icon(Icons.Default.Search, contentDescription = "Scan for devices") }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                when (selectedTabIndex) {
                    0 -> {
                        // Paired devices list
                        Text(
                            "Previously Paired Devices",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )

                        if (pairedDeviceList.isEmpty()) {
                            Text(
                                "No paired devices found.\nUse the Scan tab to find and pair with your garage door opener.",
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            ) {
                                items(pairedDeviceList) { device ->
                                    DeviceItem(device = device) {
                                        // Handle device selection
                                        coroutineScope.launch {
                                            bleService.connectToDevice(device) { success ->
                                                if (!success) {
                                                    Toast.makeText(
                                                        context,
                                                        "Connection failed",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Button(
                                onClick = { bleService.refreshPairedDevices() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Refresh Paired Devices")
                            }
                        }
                    }
                    1 -> {
                        // Scan button
                        Button(
                            onClick = {
                                if (!isScanning) {
                                    isScanning = true
                                    showScanResults = true
                                    coroutineScope.launch {
                                        bleService.startScanning { success ->
                                            isScanning = false
                                            if (!success) {
                                                Toast.makeText(
                                                    context,
                                                    "Failed to start scanning",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                } else {
                                    bleService.stopScanning()
                                    isScanning = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isScanning) "Stop Scanning" else "Scan for Garage Door")
                        }

                        // Scan results
                        if (showScanResults) {
                            Text(
                                "Available Devices",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )

                            if (deviceList.isEmpty() && !isScanning) {
                                Text("No devices found")
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                ) {
                                    items(deviceList) { device ->
                                        DeviceItem(device = device) {
                                            // Handle device selection
                                            coroutineScope.launch {
                                                bleService.connectToDevice(device) { success ->
                                                    if (!success) {
                                                        Toast.makeText(
                                                            context,
                                                            "Connection failed",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                }
                                            }
                                            showScanResults = false
                                        }
                                    }
                                }

                                if (isScanning) {
                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceItem(device: BluetoothDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = device.name ?: "Unknown Device",
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = device.address,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GarageOpenerUIPreview() {
    GarageOpenerUITheme {
        val mockContext = LocalContext.current
        val mockBleService = BleService(mockContext)
        GarageDoorUI(mockBleService)
    }
}