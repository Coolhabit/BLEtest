package com.coolhabit.bluetoothtestapp.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ContentValues.TAG
import android.content.Context
import android.util.Log
import com.coolhabit.bluetoothtestapp.data.ConnectionState
import com.coolhabit.bluetoothtestapp.data.GameBandResult
import com.coolhabit.bluetoothtestapp.data.TemperatureAndHumidityReceiveManager
import com.coolhabit.bluetoothtestapp.util.Resource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

@SuppressLint("MissingPermission")
class TemperatureAndHumidityBLEReceiveManager @Inject constructor(
    private val bluetoothAdapter: BluetoothAdapter,
    private val context: Context,
) : TemperatureAndHumidityReceiveManager {

    private val DEVICE_NAME = "Game Band"

    //    val MADDCOG_HRV_SERVICE = "ca704ada-4d05-89d3-25a4-92b80d7b932a"
    val MADDCOG_HRV_SERVICE = "5000df9c-9a41-47ac-a82a-7c301d509580"
    val MADDCOG_HRV_CHAR = "dab33654-9a44-4107-bb28-3abc12f52688"

    val MADDCOG_EEG_SERVICE = "b62ea99a-5371-4cab-9ab9-7f338623b1a3"
    val MADDCOG_EEG_CHAR = "1688c50a-1344-4358-a43c-8bed435a11c7"

    val MADDCOG_HEARTRATE_SERVICE = "0000180d-0000-1000-8000-00805f9b34fb"
    val MADDCOG_HEARTRATE_CHAR = "00002a37-0000-1000-8000-00805f9b34fb"

    val MADDCOG_BATTERY_SERVICE = "0000180f-0000-1000-8000-00805f9b34fb"
    val MADDCOG_BATTERY_CHAR = "00002a19-0000-1000-8000-00805f9b34fb"

    val MADDCOG_FIFTH_SERVICE = "1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0"
    val MADDCOG_FIFTH_CHAR = "f7bf3564-fb6d-4e53-88a4-5e37e0326063"

    override val data: MutableSharedFlow<Resource<GameBandResult>> = MutableSharedFlow()

    private val bleScanner by lazy {
        bluetoothAdapter.bluetoothLeScanner
    }

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
        .build()

    private var gatt: BluetoothGatt? = null

    private var isScanning = false

    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    private var currentConnectionAttempt = 1
    private var MAXIMUM_CONNECTION_ATTEMPT = 5

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (result.device.name == DEVICE_NAME) {
                coroutineScope.launch {
                    data.emit(Resource.Loading(message = "Connecting to device..."))
                }
                if (isScanning) {
                    result.device?.connectGatt(context, false, gattCallback)
                    isScanning = false
                    bleScanner.stopScan(this)
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    coroutineScope.launch {
                        data.emit(Resource.Loading(message = "Discovering services..."))
                    }
                    gatt.discoverServices()
                    this@TemperatureAndHumidityBLEReceiveManager.gatt = gatt
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                coroutineScope.launch {
                    data.emit(
                        Resource.Success(
                            data = GameBandResult(
                                "waiting value",
                                "waiting value",
                                "waiting value",
                                "waiting value",
                                unrecognized = "waiting value",
                                gattTable = gatt.getGattMap(),
                                ConnectionState.Disconnected
                            )
                        )
                    )
                }
                gatt.close()
            } else {
                gatt.close()
                currentConnectionAttempt += 1
                coroutineScope.launch {
                    data.emit(Resource.Loading(message = "Attempting to connect $currentConnectionAttempt/$MAXIMUM_CONNECTION_ATTEMPT"))
                }
                if (currentConnectionAttempt <= MAXIMUM_CONNECTION_ATTEMPT) {
                    startReceiving()
                } else {
                    coroutineScope.launch {
                        data.emit(Resource.Error(errorMessage = "Could not connect to device"))
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            with(gatt) {
                printGattTable()
                coroutineScope.launch {
                    data.emit(Resource.Loading(message = "Adjusting MTU space..."))
                }
                gatt.requestMtu(517)
                val allCharacteristicsList = mutableListOf<BluetoothGattCharacteristic>()
                gatt.services.forEach { service ->
                    service.characteristics.forEach { char ->
                        allCharacteristicsList.add(char)
                    }
                }
                allCharacteristicsList.forEach {
                    enableNotification(it)
                }
            }
        }

//        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
//            val characteristicsList = listOf(
//                findCharacteristic(MADDCOG_EEG_SERVICE, MADDCOG_EEG_CHAR),
//                findCharacteristic(MADDCOG_HRV_SERVICE, MADDCOG_HRV_CHAR),
//                findCharacteristic(MADDCOG_HEARTRATE_SERVICE, MADDCOG_HEARTRATE_CHAR),
//                findCharacteristic(MADDCOG_BATTERY_SERVICE, MADDCOG_BATTERY_CHAR),
//            )
//            val filteredList = characteristicsList.filterNotNull()
//            if (filteredList.isEmpty()) {
//                coroutineScope.launch {
//                    data.emit(Resource.Error(errorMessage = "Could not find characteristics"))
//                }
//            }
//            filteredList.forEach { char ->
//                char.let { it ->
//                    enableNotification(it)
//                }
//            }
//        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            gatt.readCharacteristic(
                gatt.getService(UUID.fromString(MADDCOG_BATTERY_SERVICE))
                    .getCharacteristic(UUID.fromString(MADDCOG_BATTERY_CHAR))
            )
            with(characteristic) {
                when (this.uuid) {
                    UUID.fromString(MADDCOG_HRV_CHAR) -> {
                        //XX XX XX XX XX XX
                        val hrValue = extractHeartRate(this)
                        Log.d("MADDCOG_HRV", hrValue.toString())
                        val gameBandResult = GameBandResult(
                            eegValue = "",
                            hrValue = hrValue.toString(),
                            heartRate = "",
                            batteryLevel = "",
                            unrecognized = "",
                            gattTable = gatt.getGattMap(),
                            connectionState = ConnectionState.Connected
                        )
                        coroutineScope.launch {
                            data.emit(
                                Resource.Success(data = gameBandResult)
                            )
                        }
                    }
                    UUID.fromString(MADDCOG_HEARTRATE_CHAR) -> {
                        //XX XX XX XX XX XX
                        val heartRate = extractHeartRate(this)
                        Log.d("MADDCOG_HEARTRATE", heartRate.toString())
                        val gameBandResult = GameBandResult(
                            eegValue = "",
                            hrValue = "",
                            heartRate = heartRate.toString(),
                            batteryLevel = "",
                            unrecognized = "",
                            gattTable = gatt.getGattMap(),
                            connectionState = ConnectionState.Connected
                        )
                        coroutineScope.launch {
                            data.emit(
                                Resource.Success(data = gameBandResult)
                            )
                        }
                    }
                    UUID.fromString(MADDCOG_EEG_CHAR) -> {
                        //XX XX XX XX XX XX
                        val eegValue = value.toHexString()
                        Log.d("MADDCOG_EEG", eegValue)
                        val gameBandResult = GameBandResult(
                            eegValue = eegValue,
                            hrValue = "",
                            heartRate = "",
                            batteryLevel = "",
                            unrecognized = "",
                            gattTable = gatt.getGattMap(),
                            connectionState = ConnectionState.Connected
                        )
                        coroutineScope.launch {
                            data.emit(
                                Resource.Success(data = gameBandResult)
                            )
                        }
                    }
                    UUID.fromString(MADDCOG_BATTERY_CHAR) -> {
                        //XX XX XX XX XX XX
                        val batteryLevel = value.toHexString()
                        Log.d("MADDCOG_BATTERY", batteryLevel)
                        val gameBandResult = GameBandResult(
                            eegValue = "",
                            hrValue = "",
                            heartRate = "",
                            batteryLevel = batteryLevel,
                            unrecognized = "",
                            gattTable = gatt.getGattMap(),
                            connectionState = ConnectionState.Connected
                        )
                        coroutineScope.launch {
                            data.emit(
                                Resource.Success(data = gameBandResult)
                            )
                        }
                    }
                    else -> {
                        val unrecog = value.toHexString()
                        val gameBandResult = GameBandResult(
                            eegValue = "",
                            hrValue = "",
                            heartRate = "",
                            batteryLevel = "",
                            unrecognized = unrecog,
                            gattTable = gatt.getGattMap(),
                            connectionState = ConnectionState.Connected
                        )
                        coroutineScope.launch {
                            data.emit(
                                Resource.Success(data = gameBandResult)
                            )
                        }
                    }
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            with(characteristic) {
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> {
                        when (this.uuid) {
                            UUID.fromString(MADDCOG_HRV_CHAR) -> {
                                //XX XX XX XX XX XX
                                val hrValue = extractHeartRate(this)
                                Log.d("MADDCOG_HRV", hrValue.toString())
                                val gameBandResult = GameBandResult(
                                    eegValue = "",
                                    hrValue = hrValue.toString(),
                                    heartRate = "",
                                    batteryLevel = "",
                                    unrecognized = "",
                                    gattTable = gatt.getGattMap(),
                                    connectionState = ConnectionState.Connected
                                )
                                coroutineScope.launch {
                                    data.emit(
                                        Resource.Success(data = gameBandResult)
                                    )
                                }
                            }
                            UUID.fromString(MADDCOG_BATTERY_CHAR) -> {
                                //XX XX XX XX XX XX
                                val batteryLevel = value.contentToString()
                                Log.d("read_MADDCOG_BATTERY", batteryLevel)
                                val gameBandResult = GameBandResult(
                                    eegValue = "",
                                    hrValue = "",
                                    heartRate = "",
                                    batteryLevel = batteryLevel,
                                    unrecognized = "",
                                    gattTable = gatt.getGattMap(),
                                    connectionState = ConnectionState.Connected
                                )
                                coroutineScope.launch {
                                    data.emit(
                                        Resource.Success(data = gameBandResult)
                                    )
                                }
                            }
                            UUID.fromString(MADDCOG_HEARTRATE_CHAR) -> {
                                //XX XX XX XX XX XX
                                val heartRate = extractHeartRate(this)
                                Log.d("MADDCOG_HEARTRATE", heartRate.toString())
                                val gameBandResult = GameBandResult(
                                    eegValue = "",
                                    hrValue = "",
                                    heartRate = heartRate.toString(),
                                    batteryLevel = "",
                                    unrecognized = "",
                                    gattTable = gatt.getGattMap(),
                                    connectionState = ConnectionState.Connected
                                )
                                coroutineScope.launch {
                                    data.emit(
                                        Resource.Success(data = gameBandResult)
                                    )
                                }
                            }
                            else -> {
                                val unrecog = value.toHexString()
                                val gameBandResult = GameBandResult(
                                    eegValue = "",
                                    hrValue = "",
                                    heartRate = "",
                                    batteryLevel = "",
                                    unrecognized = unrecog,
                                    gattTable = gatt.getGattMap(),
                                    connectionState = ConnectionState.Connected
                                )
                                coroutineScope.launch {
                                    data.emit(
                                        Resource.Success(data = gameBandResult)
                                    )
                                }
                            }
                        }
                        Log.i("BluetoothGattCallback", "Read characteristic $uuid:\n${value.toHexString()}")
                    }
                    BluetoothGatt.GATT_READ_NOT_PERMITTED -> {
                        Log.e("BluetoothGattCallback", "Read not permitted for $uuid!")
                    }
                    else -> {
                        Log.e("BluetoothGattCallback", "Characteristic read failed for $uuid, error: $status")
                    }
                }
            }
        }

        override fun onDescriptorRead(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            with(descriptor) {
                when (this.characteristic.uuid) {
                    UUID.fromString(MADDCOG_HRV_CHAR) -> {
                        //XX XX XX XX XX XX
                        val hrValue = value.toHexString()
                        Log.d("descr_MADDCOG_HRV", hrValue)
                        val gameBandResult = GameBandResult(
                            eegValue = "",
                            hrValue = hrValue,
                            heartRate = "",
                            batteryLevel = "",
                            unrecognized = "",
                            gattTable = gatt.getGattMap(),
                            connectionState = ConnectionState.Connected
                        )
                        coroutineScope.launch {
                            data.emit(
                                Resource.Success(data = gameBandResult)
                            )
                        }
                    }
                    UUID.fromString(MADDCOG_HEARTRATE_CHAR) -> {
                        //XX XX XX XX XX XX
                        val heartRate = value.toHexString()
                        Log.d("descr_MADDCOG_HEARTRATE", heartRate)
                        val gameBandResult = GameBandResult(
                            eegValue = "",
                            hrValue = "",
                            heartRate = heartRate,
                            batteryLevel = "",
                            unrecognized = "",
                            gattTable = gatt.getGattMap(),
                            connectionState = ConnectionState.Connected
                        )
                        coroutineScope.launch {
                            data.emit(
                                Resource.Success(data = gameBandResult)
                            )
                        }
                    }
                    else -> {
                        val unrecog = value.toHexString()
                        val gameBandResult = GameBandResult(
                            eegValue = "",
                            hrValue = "",
                            heartRate = "",
                            batteryLevel = "",
                            unrecognized = unrecog,
                            gattTable = gatt.getGattMap(),
                            connectionState = ConnectionState.Connected
                        )
                        coroutineScope.launch {
                            data.emit(
                                Resource.Success(data = gameBandResult)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun enableNotification(characteristic: BluetoothGattCharacteristic) {
        val cccdUuid = UUID.fromString(CCCD_DESCRIPTOR_UUID)
        val payload = when {
            characteristic.isIndicatable() -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            characteristic.isNotifiable() -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else -> return
        }

        characteristic.getDescriptor(cccdUuid)?.let { cccdDescriptor ->
            if (gatt?.setCharacteristicNotification(characteristic, true) == false) {
                Log.d("BLEReceiveManager", "set characteristics notification failed")
                return
            }
            writeDescription(cccdDescriptor, payload)
            println(characteristic.uuid)
        }
    }

    private fun findCharacteristic(
        serviceUUID: String,
        characteristicsUUID: String
    ): BluetoothGattCharacteristic? {
        return gatt?.services?.find { service ->
            service.uuid.toString() == serviceUUID
        }?.characteristics?.find { characteristics ->
            characteristics.uuid.toString() == characteristicsUUID
        }
    }

    override fun startReceiving() {
        coroutineScope.launch {
            data.emit(Resource.Loading(message = "Scanning BLE devices"))
        }
        isScanning = true
        bleScanner.startScan(null, scanSettings, scanCallback)
    }

    override fun reconnect() {
        gatt?.connect()
    }

    override fun disconnect() {
        gatt?.disconnect()
    }

    override fun closeConnection() {
        bleScanner.stopScan(scanCallback)
        val characteristicsList = listOf(
            findCharacteristic(MADDCOG_EEG_SERVICE, MADDCOG_EEG_CHAR),
            findCharacteristic(MADDCOG_HRV_SERVICE, MADDCOG_HRV_CHAR),
            findCharacteristic(MADDCOG_HEARTRATE_SERVICE, MADDCOG_HEARTRATE_CHAR),
            findCharacteristic(MADDCOG_BATTERY_SERVICE, MADDCOG_BATTERY_CHAR),
        )
        characteristicsList.forEach { characteristic ->
            if (characteristic != null) {
                disconnectCharacteristic(characteristic)
            }
        }
        gatt?.close()
    }

    private fun disconnectCharacteristic(characteristic: BluetoothGattCharacteristic) {
        val cccdUuid = UUID.fromString(CCCD_DESCRIPTOR_UUID)
        characteristic.getDescriptor(cccdUuid)?.let { cccdDescriptor ->
            if (gatt?.setCharacteristicNotification(characteristic, false) == false) {
                Log.d("ReceiveManager", "set characteristics notification failed")
                return
            }
            writeDescription(cccdDescriptor, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
        }
    }


    private fun writeDescription(descriptor: BluetoothGattDescriptor, payload: ByteArray) {
        gatt?.let { gatt ->
            descriptor.value = payload
            gatt.writeDescriptor(descriptor)
        } ?: error("Not connected to a BLE device!")
    }

    private fun extractHeartRate(
        characteristic: BluetoothGattCharacteristic
    ): Double {
        val flag = characteristic.properties
        Log.d(TAG, "Heart rate flag: $flag")
        var format = -1
        // Heart rate bit number format
        if (flag and 0x01 != 0) {
            format = BluetoothGattCharacteristic.FORMAT_UINT16
            Log.d(TAG, "Heart rate format UINT16.")
        } else {
            format = BluetoothGattCharacteristic.FORMAT_UINT8
            Log.d(TAG, "Heart rate format UINT8.")
        }
        val heartRate = characteristic.getIntValue(format, 1)
        Log.d(TAG, String.format("Received heart rate: %d", heartRate))
        return heartRate.toDouble()
    }
}
