package com.example.surveillanceapp.ui.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.surveillanceapp.dji.DjiMobileSdk
import com.example.surveillanceapp.dji.DjiSdkUiState
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.KeyManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Screen 1 state: SDK registration/product link plus slow battery polling from KeyManager cache.
 */
class ConnectionViewModel : ViewModel() {

    val sdkState: StateFlow<DjiSdkUiState> = DjiMobileSdk.state

    private val _batteryPercent = MutableStateFlow<Int?>(null)
    val batteryPercent: StateFlow<Int?> = _batteryPercent

    val statusLine: StateFlow<String> = sdkState.map(::formatStatus).stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = formatStatus(sdkState.value),
    )

    private var batteryJob: Job? = null

    private val batteryKey = KeyTools.createKey(
        BatteryKey.KeyChargeRemainingInPercent,
        ComponentIndexType.LEFT_OR_MAIN,
    )

    init {
        viewModelScope.launch {
            sdkState.collect { s ->
                if (s.productConnected) {
                    if (batteryJob == null) startBatteryPolling()
                } else {
                    stopBatteryPolling()
                    _batteryPercent.value = null
                }
            }
        }
    }

    private fun startBatteryPolling() {
        if (batteryJob != null) return
        batteryJob = viewModelScope.launch {
            while (isActive) {
                runCatching {
                    val pct = KeyManager.getInstance().getValue(batteryKey, -1)
                    _batteryPercent.value = pct.takeIf { it >= 0 }
                }
                delay(1_000L)
            }
        }
    }

    private fun stopBatteryPolling() {
        batteryJob?.cancel()
        batteryJob = null
    }

    override fun onCleared() {
        stopBatteryPolling()
        super.onCleared()
    }
}

private fun formatStatus(s: DjiSdkUiState): String = buildString {
    append("Init: ").append(s.initEvent?.name ?: "—")
    append(" | Registered: ").append(s.sdkRegistered)
    s.registrationError?.let { append(" | Reg err: ").append(it) }
    append(" | Aircraft: ").append(if (s.productConnected) "connected" else "disconnected")
    s.productId?.let { append(" (id ").append(it).append(")") }
}
