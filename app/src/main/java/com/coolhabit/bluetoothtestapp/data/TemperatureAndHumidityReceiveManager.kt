package com.coolhabit.bluetoothtestapp.data

import com.coolhabit.bluetoothtestapp.util.Resource
import kotlinx.coroutines.flow.MutableSharedFlow

interface TemperatureAndHumidityReceiveManager {

    val data: MutableSharedFlow<Resource<TempHumidityResult>>
    fun reconnect()
    fun disconnect()
    fun startReceiving()
    fun closeConnection()
}
