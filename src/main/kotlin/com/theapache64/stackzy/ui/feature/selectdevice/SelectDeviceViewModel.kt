package com.theapache64.stackzy.ui.feature.selectdevice

import com.theapache64.stackzy.data.repo.AdbRepo
import com.theapache64.stackzy.model.AndroidDeviceWrapper
import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

class SelectDeviceViewModel @Inject constructor(
    private val adbRepo: AdbRepo
) {

    private lateinit var viewModelScope: CoroutineScope
    private val _connectedDevices = MutableStateFlow<List<AndroidDeviceWrapper>?>(null)
    val connectedDevices: StateFlow<List<AndroidDeviceWrapper>?> = _connectedDevices


    fun init(scope: CoroutineScope) {
        this.viewModelScope = scope
    }


    /**
     * To start watching connected devices
     */
    fun watchConnectedDevices() {
        viewModelScope.launch {
            adbRepo.watchConnectedDevice()
                .catch {
                    Arbor.d("Error: ${it.message}")
                }
                .collect {
                    Arbor.d("Devices : $it")
                    _connectedDevices.value = it.map { device -> AndroidDeviceWrapper(device) }
                }
        }
    }


    /**
     * To stop watching connected devices
     */
    fun stopWatchConnectedDevices() {
        Arbor.d("Removing watcher")
        adbRepo.cancelWatchConnectedDevice()
    }

}