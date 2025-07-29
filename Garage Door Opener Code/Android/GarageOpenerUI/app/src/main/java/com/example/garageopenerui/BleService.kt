package com.example.garageopenerui

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.util.*

class BleService(private val context: Context) {
    private val TAG = "BleService"

    // UUID constants that match the ESP32 device
    private val SERVICE_UUID: UUID = UUID.fromString("9ba08ea3-3fa9-4622-bae5-bdd3f0c7fedf")
    private val CHARACTERISTIC_UUID: UUID = UUID.fromString("427c5c12-0f90-46be-ba43-7e4a207be489")

    // BluetoothManager and adapter
    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    // Device scanning variables
    private var scanning = false
    private val scanHandler = Handler(Looper.getMainLooper())
    private val SCAN_PERIOD_MS: Long = 10000

    // Last connected device tracking (for saved devices list, not auto-reconnect)
    private var lastConnectedDeviceAddress: String? = null
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("BleServicePrefs", Context.MODE_PRIVATE)

    // Connection variables
    private var gatt: BluetoothGatt? = null
    private var targetDeviceName = "Garage" // Name of the ESP32 device
    private var gattCallback: BluetoothGattCallback? = null

    // Paired/known devices list
    private val _pairedDeviceList = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val pairedDeviceList: StateFlow<List<BluetoothDevice>> = _pairedDeviceList

    // State flows for UI updates
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _deviceList = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val deviceList: StateFlow<List<BluetoothDevice>> = _deviceList

    // Operation state to manage command issuance
    private val _operationState = MutableStateFlow(OperationState.IDLE)
    val operationState: StateFlow<OperationState> = _operationState

    init {
        // Load previously connected device on init (just for records)
        lastConnectedDeviceAddress = sharedPreferences.getString("lastConnectedDevice", null)

        // Load paired devices
        loadPairedDevices()
    }

    @SuppressLint("MissingPermission")
    private fun loadPairedDevices() {
        try {
            // Load paired devices from the system
            val pairedDevices = bluetoothAdapter?.bondedDevices
            if (pairedDevices != null) {
                // Filter for devices that might be garage door openers
                _pairedDeviceList.value = pairedDevices.filter { device ->
                    device.name?.contains("Garage", ignoreCase = true) == true ||
                            savedDevices().contains(device.address)
                }.toList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading paired devices", e)
        }
    }

    // Get list of saved device addresses
    fun savedDevices(): List<String> {
        val savedDevicesJson = sharedPreferences.getString("savedDevices", "[]")
        val list = mutableListOf<String>()
        try {
            val jsonArray = JSONArray(savedDevicesJson)
            for (i in 0 until jsonArray.length()) {
                list.add(jsonArray.getString(i))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing saved devices", e)
        }
        return list
    }

    // Add device to saved devices
    private fun addSavedDevice(address: String) {
        val devices = savedDevices().toMutableList()
        if (!devices.contains(address)) {
            devices.add(address)
            val jsonArray = JSONArray()
            devices.forEach { jsonArray.put(it) }
            sharedPreferences.edit().putString("savedDevices", jsonArray.toString()).apply()
        }
    }

    // Check if Bluetooth is enabled
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    // Start scanning for BLE devices
    @SuppressLint("MissingPermission")
    fun startScanning(callback: (Boolean) -> Unit) {
        if (!isBluetoothEnabled()) {
            callback(false)
            return
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            callback(false)
            return
        }

        if (scanning) {
            scanner.stopScan(scanCallback)
            scanning = false
        }

        // Clear existing device list
        _deviceList.value = emptyList()

        // Set up scan filter for our specific service
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // Start scanning
        scanner.startScan(filters, settings, scanCallback)
        scanning = true

        // Stop scanning after SCAN_PERIOD_MS
        scanHandler.postDelayed({
            if (scanning) {
                scanner.stopScan(scanCallback)
                scanning = false
                callback(true)
            }
        }, SCAN_PERIOD_MS)

        callback(true)
    }

    // Stop scanning for devices
    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (scanning && bluetoothAdapter?.isEnabled == true) {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            scanning = false
        }
    }

    // Connect to a specific device (only when user explicitly selects)
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice, callback: (Boolean) -> Unit) {
        gattCallback = createGattCallback(callback)
        _connectionState.value = ConnectionState.CONNECTING

        // Remember this device for the saved devices list
        lastConnectedDeviceAddress = device.address
        sharedPreferences.edit().putString("lastConnectedDevice", device.address).apply()

        // Add to saved devices list
        addSavedDevice(device.address)

        // Update the paired devices list
        loadPairedDevices()

        // Connect to device - autoConnect=false for immediate connection attempt
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    // Function to connect to a device by its address (used by widget)
    @SuppressLint("MissingPermission")
    fun connectToDeviceByAddress(address: String, callback: (Boolean) -> Unit) {
        if (bluetoothAdapter == null) {
            callback(false)
            return
        }

        try {
            val device = bluetoothAdapter?.getRemoteDevice(address)
            if (device != null) {
                connectToDevice(device, callback)
            } else {
                callback(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to device by address", e)
            callback(false)
        }
    }

    // Disconnect from current device
    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        _connectionState.value = ConnectionState.DISCONNECTED
        _operationState.value = OperationState.IDLE
    }

    // Close GATT client
    @SuppressLint("MissingPermission")
    fun close() {
        gatt?.close()
        gatt = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _operationState.value = OperationState.IDLE
    }

    // Clear last connected device (forget device)
    fun forgetDevice() {
        lastConnectedDeviceAddress = null
        sharedPreferences.edit().remove("lastConnectedDevice").apply()
    }

    // Trigger the garage door (send the TRIGGER command)
    @SuppressLint("MissingPermission")
    fun triggerGarageDoor(callback: (Boolean) -> Unit) {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Log.e(TAG, "Can't trigger garage door: not connected")
            callback(false)
            return
        }

        val characteristic = gatt?.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
        if (characteristic == null) {
            Log.e(TAG, "Characteristic not found")
            callback(false)
            return
        }

        _operationState.value = OperationState.SENDING

        // Write the command to the characteristic
        characteristic.setValue("TRIGGER")
        gatt?.writeCharacteristic(characteristic)

        // Set a timeout to reset the operation state if no response
        scanHandler.postDelayed({
            if (_operationState.value == OperationState.SENDING) {
                _operationState.value = OperationState.IDLE
                callback(false)
            }
        }, 5000) // 5-second timeout
    }

    // General method to send any command to the connected device
    @SuppressLint("MissingPermission")
    fun sendCommand(command: String, callback: (Boolean) -> Unit) {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Log.e(TAG, "Can't send command: not connected")
            callback(false)
            return
        }

        val characteristic = gatt?.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
        if (characteristic == null) {
            Log.e(TAG, "Characteristic not found")
            callback(false)
            return
        }

        _operationState.value = OperationState.SENDING

        // Write the command to the characteristic
        characteristic.setValue(command)
        gatt?.writeCharacteristic(characteristic)

        // Set a timeout to reset the operation state if no response
        scanHandler.postDelayed({
            if (_operationState.value == OperationState.SENDING) {
                _operationState.value = OperationState.IDLE
                callback(false)
            }
        }, 5000) // 5-second timeout
    }

    // Scan callback
    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { scanResult ->
                val device = scanResult.device

                // Only add device if it's the one we're looking for or not already in the list
                if (device.name == targetDeviceName || device.name?.contains("Garage") == true) {
                    val currentList = _deviceList.value.toMutableList()
                    if (currentList.none { it.address == device.address }) {
                        currentList.add(device)
                        _deviceList.value = currentList
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
        }
    }

    // Create GATT callback
    private fun createGattCallback(connectionCallback: (Boolean) -> Unit): BluetoothGattCallback {
        return object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "Connected to GATT server. Attempting to discover services...")
                        _connectionState.value = ConnectionState.CONNECTED
                        gatt?.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Disconnected from GATT server")
                        _connectionState.value = ConnectionState.DISCONNECTED
                        connectionCallback(false)
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Services discovered successfully")
                    val service = gatt?.getService(SERVICE_UUID)
                    if (service != null) {
                        Log.i(TAG, "Found our service")
                        connectionCallback(true)
                    } else {
                        Log.e(TAG, "Service not found")
                        connectionCallback(false)
                    }
                } else {
                    Log.w(TAG, "Service discovery failed with status: $status")
                    connectionCallback(false)
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic?.uuid == CHARACTERISTIC_UUID) {
                    val value = characteristic.value
                    Log.i(TAG, "Read characteristic value: ${String(value)}")
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
                if (characteristic?.uuid == CHARACTERISTIC_UUID) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.i(TAG, "Write characteristic successful")
                        _operationState.value = OperationState.SUCCESS
                    } else {
                        Log.e(TAG, "Write characteristic failed with status: $status")
                        _operationState.value = OperationState.FAILED
                    }
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?
            ) {
                if (characteristic?.uuid == CHARACTERISTIC_UUID) {
                    val value = characteristic.value
                    Log.i(TAG, "Characteristic notification received: ${String(value)}")
                }
            }
        }
    }

    // Refresh the list of paired devices
    @SuppressLint("MissingPermission")
    fun refreshPairedDevices() {
        loadPairedDevices()
    }

    // Enum to represent connection states
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

    // Enum to represent operation states
    enum class OperationState {
        IDLE,
        SENDING,
        SUCCESS,
        FAILED
    }
}
