package com.coolhabit.bluetoothtestapp.data

import com.coolhabit.bluetoothtestapp.util.Resource
import kotlinx.coroutines.flow.MutableSharedFlow

interface TemperatureAndHumidityReceiveManager {

    val data: MutableSharedFlow<Resource<GameBandResult>>
    fun reconnect()
    fun disconnect()
    fun startReceiving()
    fun closeConnection()
}
