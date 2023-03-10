package com.coolhabit.bluetoothtestapp.data

data class GameBandResult(
    val hrValue: String,
    val eegValue: String,
    val heartRate: String,
    val batteryLevel: String,
    val unrecognized: String,
    val gattTable: Map<String, List<String>>,
    val connectionState: ConnectionState
)
