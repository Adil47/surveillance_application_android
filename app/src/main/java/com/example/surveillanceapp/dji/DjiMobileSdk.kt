package com.example.surveillanceapp.dji

import android.content.Context
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback
import dji.v5.network.DJINetworkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns DJI Mobile SDK v5 process: init → registerApp → automatic product connection.
 * SDK callbacks are funneled into a small [StateFlow] for MVVM screens.
 */
object DjiMobileSdk {

    private val _state = MutableStateFlow(DjiSdkUiState())
    val state: StateFlow<DjiSdkUiState> = _state.asStateFlow()

    @Volatile
    private var initComplete = false

    @Volatile
    private var started = false

    private val sdkCallback = object : SDKManagerCallback {
        override fun onRegisterSuccess() {
            _state.update {
                it.copy(
                    sdkRegistered = true,
                    registrationError = null,
                )
            }
        }

        override fun onRegisterFailure(error: IDJIError) {
            _state.update {
                it.copy(
                    sdkRegistered = false,
                    registrationError = error.toString(),
                )
            }
        }

        override fun onProductDisconnect(productId: Int) {
            _state.update {
                it.copy(
                    productConnected = false,
                    productId = null,
                )
            }
        }

        override fun onProductConnect(productId: Int) {
            _state.update {
                it.copy(
                    productConnected = true,
                    productId = productId,
                )
            }
        }

        override fun onProductChanged(productId: Int) {
            _state.update { it.copy(productId = productId) }
        }

        override fun onInitProcess(event: DJISDKInitEvent, totalProcess: Int) {
            _state.update {
                it.copy(
                    initEvent = event,
                    initProgressTotal = totalProcess,
                )
            }
            if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                initComplete = true
                SDKManager.getInstance().registerApp()
            }
        }

        override fun onDatabaseDownloadProgress(current: Long, total: Long) {
            _state.update {
                it.copy(
                    dbDownloadCurrent = current,
                    dbDownloadTotal = total,
                )
            }
        }
    }

    /**
     * Idempotent: safe to call once from [android.app.Application.onCreate].
     */
    fun start(appContext: Context) {
        if (started) return
        started = true
        SDKManager.getInstance().init(appContext.applicationContext, sdkCallback)
        DJINetworkManager.getInstance().addNetworkStatusListener { isAvailable ->
            if (initComplete && isAvailable && !SDKManager.getInstance().isRegistered) {
                SDKManager.getInstance().registerApp()
            }
        }
    }

    fun destroy() {
        if (!started) return
        runCatching { SDKManager.getInstance().destroy() }
        started = false
        initComplete = false
    }
}

data class DjiSdkUiState(
    val initEvent: DJISDKInitEvent? = null,
    val initProgressTotal: Int = 0,
    val dbDownloadCurrent: Long = 0L,
    val dbDownloadTotal: Long = 0L,
    val sdkRegistered: Boolean = false,
    val registrationError: String? = null,
    val productConnected: Boolean = false,
    val productId: Int? = null,
)
