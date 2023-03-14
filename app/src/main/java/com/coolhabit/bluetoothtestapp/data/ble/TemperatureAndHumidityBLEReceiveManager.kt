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

    var count = 3
    private val DEVICE_NAME = "Game Band"

    var savedBattery = "0.0"
    var savedEeg = "0.0"
    var savedHrv = 0.0
    var savedHeartRate = 0.0
    var unrecognized = "UNREC"

    val MADDCOG_HRV_SERVICE = "5000df9c-9a41-47ac-a82a-7c301d509580"
    val MADDCOG_HRV_CHAR = "dab33654-9a44-4107-bb28-3abc12f52688"

    val MADDCOG_EEG_SERVICE = "b62ea99a-5371-4cab-9ab9-7f338623b1a3"
    val MADDCOG_EEG_CHAR = "1688c50a-1344-4358-a43c-8bed435a11c7"

    val MADDCOG_HEARTRATE_SERVICE = "0000180d-0000-1000-8000-00805f9b34fb"
    val MADDCOG_HEARTRATE_CHAR = "00002a37-0000-1000-8000-00805f9b34fb"

    val MADDCOG_BATTERY_SERVICE = "0000180f-0000-1000-8000-00805f9b34fb"
    val MADDCOG_BATTERY_CHAR = "00002a19-0000-1000-8000-00805f9b34fb"

    val chars = listOfNotNull(
        findCharacteristic(MADDCOG_EEG_SERVICE, MADDCOG_EEG_CHAR),
        findCharacteristic(MADDCOG_HRV_SERVICE, MADDCOG_HRV_CHAR),
        findCharacteristic(MADDCOG_HEARTRATE_SERVICE, MADDCOG_HEARTRATE_CHAR),
//        findCharacteristic(MADDCOG_BATTERY_SERVICE, MADDCOG_BATTERY_CHAR),
    )

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
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val characteristicsList = listOf(
                findCharacteristic(MADDCOG_EEG_SERVICE, MADDCOG_EEG_CHAR),
                findCharacteristic(MADDCOG_HRV_SERVICE, MADDCOG_HRV_CHAR),
                findCharacteristic(MADDCOG_HEARTRATE_SERVICE, MADDCOG_HEARTRATE_CHAR),
                findCharacteristic(MADDCOG_BATTERY_SERVICE, MADDCOG_BATTERY_CHAR),
            )
            val filteredList = characteristicsList.filterNotNull()
            if (filteredList.isEmpty()) {
                coroutineScope.launch {
                    data.emit(Resource.Error(errorMessage = "Could not find characteristics"))
                }
            }

            findCharacteristic(MADDCOG_HEARTRATE_SERVICE, MADDCOG_HEARTRATE_CHAR)?.let {
                enableNotification(
                    it
                )
            }

//            filteredList.forEach { char ->
//                char.let { it ->
//                    enableNotification(it)
//                }
//            }
        }

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
                        savedHrv = hrValue
                        Log.d("MADDCOG_HRV", hrValue.toString())
                        val gameBandResult = GameBandResult(
                            eegValue = savedEeg.toString(),
                            hrValue = hrValue.toString(),
                            heartRate = savedHeartRate.toString(),
                            batteryLevel = savedBattery.toString(),
                            unrecognized = unrecognized,
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
                        savedHeartRate = heartRate
                        Log.d("MADDCOG_HEARTRATE", heartRate.toString())
                        val gameBandResult = GameBandResult(
                            eegValue = savedEeg.toString(),
                            hrValue = savedHrv.toString(),
                            heartRate = heartRate.toString(),
                            batteryLevel = savedBattery.toString(),
                            unrecognized = unrecognized,
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
                        savedEeg = eegValue
                        Log.d("MADDCOG_EEG", eegValue)
                        val gameBandResult = GameBandResult(
                            eegValue = eegValue,
                            hrValue = savedHrv.toString(),
                            heartRate = savedHeartRate.toString(),
                            batteryLevel = savedBattery.toString(),
                            unrecognized = unrecognized,
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
                        unrecognized = unrecog
                        val gameBandResult = GameBandResult(
                            eegValue = savedEeg,
                            hrValue = savedHrv.toString(),
                            heartRate = savedHeartRate.toString(),
                            batteryLevel = savedBattery,
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
                            UUID.fromString(MADDCOG_BATTERY_CHAR) -> {
                                //XX XX XX XX XX XX
                                val batteryLevel = value.contentToString()
                                savedBattery = batteryLevel
                                Log.d("read_MADDCOG_BATTERY", batteryLevel)
                                val gameBandResult = GameBandResult(
                                    eegValue = savedEeg,
                                    hrValue = savedHrv.toString(),
                                    heartRate = savedHeartRate.toString(),
                                    batteryLevel = batteryLevel,
                                    unrecognized = unrecognized,
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
                                unrecognized = unrecog
                                val gameBandResult = GameBandResult(
                                    eegValue = savedEeg,
                                    hrValue = savedHrv.toString(),
                                    heartRate = savedHeartRate.toString(),
                                    batteryLevel = savedBattery,
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

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                when (count) {
                    1 -> {
                        findCharacteristic(MADDCOG_EEG_SERVICE, MADDCOG_EEG_CHAR)?.let {
                            enableNotification(it)
                        }
                    }
                    0 -> enableNotification(chars[0])
                }
            }
        }
    }

    private fun setEegNotifications() {

    }

    private fun enableNotification(characteristic: BluetoothGattCharacteristic) {
        val cccdUuid = UUID.fromString(CCCD_DESCRIPTOR_UUID)
//        val payload = when {
//            characteristic.isIndicatable() -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
//            characteristic.isNotifiable() -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
//            else -> return
//        }
        val registered = gatt?.setCharacteristicNotification(characteristic, true)
        if (registered == true) {
            val descriptor = characteristic.getDescriptor(cccdUuid)
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt?.writeDescriptor(descriptor)
        }
        count--

//        characteristic.getDescriptor(cccdUuid)?.let { cccdDescriptor ->
//            if (gatt?.setCharacteristicNotification(characteristic, true) == false) {
//                Log.d("BLEReceiveManager", "set characteristics notification failed")
//            }
//            writeDescription(cccdDescriptor, payload)
//        }
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
